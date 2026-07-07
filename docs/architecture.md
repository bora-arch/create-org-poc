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

### 3. Per-step `REQUIRES_NEW` transaction, not job-wide

A single job-wide transaction would (a) hold a DB connection across all 8 external calls and (b) roll back the audit trail on failure — the exact opposite of what we need, since the FAILED row is the point. Per-step boundaries let the GET endpoint observe `IN_PROGRESS` in real time. `StepExecutor` and `JobStateWriter` are both annotated `@Transactional(REQUIRES_NEW)`.

For POC simplicity the external call runs inside the step's transaction. Production would bracket the external call with two short transactions (mark-start, then mark-end after the call) with the network I/O outside any transaction — the code shape is identical, only the `@Transactional` boundary moves.

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

- **Step throws** → executor writes `FAILED` (with translated code + message), rethrows `StepExecutionException`.
- **Orchestrator catches** → writes job `FAILED` with `finishedAt` set; leaves the failed step's `currentStep` pointer intact.
- **Remaining steps** stay `NOT_STARTED` — no follow-up code needed, because they were pre-seeded that way.
- **Unexpected non-step exception** (e.g., DB blip while marking state) → same terminal treatment, logged at ERROR.

## Enterprise concerns intentionally out of scope for the POC

Flagged in `future-improvements.md`:

- Retry with policy per step (retry, backoff, dead-letter after N attempts)
- Compensation / Saga rollback of already-completed steps
- Distributed / crash-safe async (Quartz, Kafka, Temporal)
- Event sourcing of state transitions
- Idempotency of `POST` under network retries
- Auth, tenancy, correlation IDs, tracing, metrics

None of these break the current shape; they add either a new bean or a new field on the SPI.
