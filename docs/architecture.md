# Architecture

## Goals

- **Extensibility.** Adding a step must not require touching the orchestrator, controllers, or DTOs. Only a new bean.
- **Observability.** The GET endpoint must at all times return a snapshot that lets a UI render completed / current / remaining steps plus a human error on failure.
- **Isolation of concerns.** Persistence, orchestration, and step business logic each live in their own class family so each can evolve independently.
- **Room to grow.** The design must remain intact when the workflow expands to 30–50 steps and when retry, compensation, distributed execution, or event sourcing are added later.

## Layers

```
api          → HTTP surface: controllers, DTOs, exception handler
workflow.spi → Step contract + per-job context (public extension surface)
workflow.engine → Orchestrator, executor, registry, failure translator, state writer
workflow.steps  → Concrete step beans (one file per business action)
workflow.query  → Read-model assembly for the GET endpoint
domain          → JPA entities, enums, repositories
external        → SPI + mock for the downstream platform
config          → Async / thread-pool configuration
```

Dependencies flow **inward**: `api → workflow → domain → external`, with `workflow.spi` intentionally the narrowest surface a new step needs to know.

## Key decisions

### 1. Auto-discovered steps ordered by `int order()` on the SPI

`ProvisionWorkflowService` is injected with `List<ProvisionStep>`; Spring collects every bean implementing the interface. `StepRegistry` sorts once at startup and stores an immutable list; it also enforces **order uniqueness** — duplicate values fail application startup, not the first job.

Alternatives rejected:

- `@Order` on the bean class hides sequence in annotations, forcing grep-across-files to trace the workflow.
- `StepName` enum ordinal couples ordering to declaration order in a single file; reformatting the enum silently changes production behavior.

Values are gapped (10, 20, 30 …) so inserting a step between two existing ones is a one-line change, not a renumbering cascade.

### 2. Step rows are pre-seeded at job creation

The GET contract requires returning `NOT_STARTED` remaining steps after a failure. Two options were considered:

- **Pre-seed** every step row when the job is created (chosen).
- **Synthesize** remaining steps at read time by diffing the step catalog against persisted rows.

Pre-seeding keeps the read path a trivial `findByJobIdOrderByStepOrder` — no business logic in the query handler. It also freezes each job's step list at the catalog snapshot in force when it started, giving automatic historical auditability if steps are added to the catalog later.

### 3. Per-step `REQUIRES_NEW` writes bracketing the external call

A single job-wide transaction would (a) hold a DB connection across all 12 external calls and (b) roll back the audit trail on failure — the exact opposite of what we need, since the FAILED row is the point. So each status write is its own short `REQUIRES_NEW` transaction.

The writes **bracket** the external call rather than wrapping it: `StepExecutor` (not itself transactional) calls `StepStateWriter.markInProgress` (commits) → invokes the step's external call outside any transaction → then `markSuccess` / `markFailed` (commits). Two properties depend on this split:

- A concurrent GET observes `IN_PROGRESS` in real time, because that write commits *before* the slow call instead of being held open across it.
- **A `FAILED` row survives.** `StepExecutor` signals failure to the orchestrator by throwing `StepExecutionException` *after* `markFailed` has committed. If the write and the throw shared one transaction (as an earlier single-`@Transactional`-method design did), the throw would roll back the very audit row it was trying to record — and continue-on-failure, which reads persisted `FAILED` rows, could not work.

`JobStateWriter` (job row) and `StepStateWriter` (step row) are separate beans so Spring's transactional proxy actually engages — a self-invoked method on the orchestrator or executor would bypass it.

### 4. Async via a named `ThreadPoolTaskExecutor`, wrapped in a separate bean

`ProvisionWorkflowAsyncRunner.run(UUID, ...)` is `@Async("provisioningExecutor")`. It is a **separate bean** because `@Async` requires proxied invocation — a self-invocation from within `ProvisionWorkflowService` would silently run synchronously. Only primitive/immutable arguments cross the async boundary (`UUID`, `String`) — no JPA entities, which would be detached in the target thread.

### 5. `StepFailureTranslator` isolates exception mapping from persistence

`StepExecutor` knows how to write step rows; it does not know how to interpret arbitrary `Throwable`s. The translator maps `ExternalCallException → (its code, its message)` and everything else to `INTERNAL_ERROR`. When retry lands, this is the one place that will need to classify failures as retryable/non-retryable — the executor stays untouched.

### 6. Read and write paths are separate services

`ProvisionWorkflowService` (write) and `ProvisionJobQueryService` (read) live in different packages and do not share state. The read service assembles the DTO tree; it never mutates. This makes it trivial to later swap the read side for a projection, cache, or read replica without disturbing the workflow.

### 7. Constructor injection only, immutable where possible

Every bean uses `@RequiredArgsConstructor` with `final` fields. DTOs are Java `record`s. Entities are Lombok `@Getter/@Setter/@Builder` because JPA needs setters. No field injection anywhere.

## Concurrency

Each `POST /organizations` is handed off to the `provisioningExecutor` pool (core=4, max=8, queue=100). Two in-flight jobs make independent progress; per-step `REQUIRES_NEW` transactions ensure their audit rows never interfere. The pool is bounded so a burst of requests queues rather than blowing the JVM.

## Failure semantics

Steps run as a chain; failure handling is per-step, driven by `ProvisionStep.critical()` (default `false`).

- **Step throws** → executor writes `FAILED` (with translated code + message) in its own committed transaction, then rethrows `StepExecutionException`.
- **Orchestrator catches, non-critical step** → increments a failure counter and **continues** with the remaining steps. The `FAILED` row stays; nothing is rolled back.
- **Orchestrator catches, critical step** → writes job `FAILED` with `finishedAt`, leaves the `currentStep` pointer on the failed step, and stops. Remaining steps stay `NOT_STARTED` (pre-seeded that way — no follow-up code).
- **End of chain** → job status is `SUCCESS` if the failure counter is zero, else `COMPLETED_WITH_ERRORS`.
- **Unexpected non-step exception** (e.g., DB blip while marking state) → job `FAILED`, logged at ERROR.

Only `CREATE_ORG_IN_FSP` is currently `critical`: it produces the `organizationId` every later step consumes, so its failure leaves nothing to provision. Every other step is best-effort — a failure degrades the result (`COMPLETED_WITH_ERRORS`) without blocking the rest.

## Enterprise concerns intentionally out of scope for the POC

Flagged in `future-improvements.md`:

- Retry with policy per step (retry, backoff, dead-letter after N attempts)
- Compensation / Saga rollback of already-completed steps
- Distributed / crash-safe async (Quartz, Kafka, Temporal)
- Event sourcing of state transitions
- Idempotency of `POST` under network retries
- Auth, tenancy, correlation IDs, tracing, metrics

None of these break the current shape; they add either a new bean or a new field on the SPI.
