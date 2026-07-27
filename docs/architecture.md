# Architecture

## Goals

- **Extensibility.** Adding a step must not require touching the orchestrator, controllers, or DTOs. Only a new bean, plus adding it to whichever `source_config.json` entries should trigger it.
- **Observability.** The GET endpoint must at all times return a snapshot that lets a UI render completed / current / remaining steps plus a human error on failure.
- **Isolation of concerns.** Persistence, orchestration, and step business logic each live in their own class family so each can evolve independently.
- **Room to grow.** The design must remain intact when the workflow expands to 30–50 steps and when compensation, distributed execution, or event sourcing are added later.
- **Retryable.** A failed job can be resumed from its failed step without re-running already-succeeded steps, keyed by the client-supplied `org_uid`.

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

### 2. Step rows are pre-seeded, in two phases gated by what's known when

The GET contract requires returning `NOT_STARTED` remaining steps after a failure, and — since `source` restricts the step set — must never report a step the job's `source` doesn't even apply to. Two options were considered for the second requirement:

- **Seed only what's known to apply, when it becomes known** (chosen): `createJob` seeds just the `INITIAL_REQUEST_VALIDATION` row (order `0`) — `source` isn't resolved yet, so nothing else can be. Once `INITIAL_REQUEST_VALIDATION` succeeds and `source` is resolved, `ProvisionWorkflowService.seedStepsForSource` seeds one `NOT_STARTED` row per catalog step `source` selects, in catalog order. A step outside that set never gets a row, ever, for this job.
- **Seed everything, filter at read time** (rejected): pre-seed the full catalog, then have the query handler drop rows not in the job's source set. Filtering logic in the read path directly contradicts goal 6 below (read side stays dumb DTO assembly); it also means every `NOT_STARTED` row for an excluded step briefly exists in the DB for no reason.

Pre-seeding (in either phase) keeps the read path a trivial `findByJobIdOrderByStepOrder` — no business logic in the query handler; whatever rows exist *are* the response. It also freezes each job's applicable step list at the catalog snapshot in force when `source` was resolved, giving automatic historical auditability if steps are added to the catalog (or a source's config) later.

One consequence: a job that fails `INITIAL_REQUEST_VALIDATION` has exactly one step row, ever — `source` was never resolved, so nothing else was ever eligible to be seeded. `progress`/`totalSteps` are `1`/`1` in that case, not `1`/`13`.

### 3. Per-step `REQUIRES_NEW` transaction, not job-wide

A single job-wide transaction would (a) hold a DB connection across all external calls and (b) roll back the audit trail on failure — the exact opposite of what we need, since the FAILED row is the point. Per-step boundaries let the GET endpoint observe `IN_PROGRESS` in real time. `StepExecutor` and `JobStateWriter` are both annotated `@Transactional(REQUIRES_NEW)`.

For POC simplicity the external call runs inside the step's transaction. Production would bracket the external call with two short transactions (mark-start, then mark-end after the call) with the network I/O outside any transaction — the code shape is identical, only the `@Transactional` boundary moves.

`StepExecutor.execute` also declares `noRollbackFor = StepExecutionException.class`. It deliberately throws that exception, from inside the same `@Transactional` method, to signal the orchestrator to halt — but Spring's default rule rolls back a transaction on *any* unchecked exception that propagates past its boundary, which would silently discard the `markFailed` write the method just made. Without `noRollbackFor`, no FAILED row is ever actually persisted, which breaks both the GET contract and retry (there would be nothing to resume from).

### 3a. Retry is a resume, not a restart

`org_uid` (from the request) is the job's business key — `WorkflowRepository.findByOrgUid` looks it up on every `POST`. If no job exists, one is created (`INITIAL_REQUEST_VALIDATION` pre-seeded `NOT_STARTED`; the rest seeded once `source` resolves — see decision 2). If one exists and is `FAILED`, the same job is reused: `ProvisionWorkflowService.firstPendingStepOrder` finds the lowest-order step that is still `NOT_STARTED` or `FAILED`, and `execute(..., resumeFromOrder)` skips every step with a lower order — they are already `SUCCESS`/`SKIPPED` from a prior attempt — and resumes exactly at the failed one. Combined with fail-fast (steps after a failure are never touched, guaranteed by pre-seeding), this gives "resubmit the same request, it picks up where it left off" without a separate retry endpoint or extra state machine.

If the job is already `SUCCESS` or `IN_PROGRESS`, the same `org_uid` is not restarted — the current snapshot is returned as-is (`200 OK`).

`findOrCreateJob` only refreshes `serviceUserAccount` / `externalJobUid` on an existing job — `orgType` and `source` are never overwritten there. Both are only ever persisted once by `markValidationResolved`, right after `INITIAL_REQUEST_VALIDATION` succeeds; a retry that changes either only takes effect while the job is still stuck at validation (i.e. the *first* attempt never got past it). This is deliberate: once later steps have actually executed under a given `org_type`/`source` combination, silently swapping either mid-flight on a later retry would desync which steps were gated by which rules — some already-`SKIPPED` rows would reflect the old rules, and steps newly enabled by the changed value would never get a chance to run since they're not the resume point.

### 4. Async via a named `ThreadPoolTaskExecutor`, wrapped in a separate bean

`ProvisionWorkflowAsyncRunner.run(UUID, ...)` is `@Async("provisioningExecutor")`. It is a **separate bean** because `@Async` requires proxied invocation — a self-invocation from within `ProvisionWorkflowService` would silently run synchronously. Only primitive/immutable arguments cross the async boundary (`UUID`, `String`) — no JPA entities, which would be detached in the target thread.

### 5. `StepFailureTranslator` isolates exception mapping from persistence

`StepExecutor` knows how to write step rows; it does not know how to interpret arbitrary `Throwable`s. The translator maps `ExternalCallException → (its code, its message)`, `RequestValidationException → VALIDATION_FAILED`, and everything else to `INTERNAL_ERROR`. When per-step retry policies (attempt/backoff, not the job-level resume described above) land, this is the one place that will need to classify failures as retryable/non-retryable — the executor stays untouched.

### 5a. `INITIAL_REQUEST_VALIDATION` always runs first, and runs synchronously

The request's raw `org_uid` / `org_type` / `source` / `service_user_account` are validated by a real `ProvisionStep` (order `0`) rather than bean-validation annotations, so an invalid value is persisted and reported through the same step-failure machinery as any other step (visible identically via `POST` and `GET`). `ProvisionWorkflowService.validateSynchronously` runs *only* this step, on the calling thread, before any async hand-off — so an invalid request never starts the background workflow and the `POST` response itself already reflects `status: FAILED` with the failed step's error. Every other step still runs through the normal async `execute()` loop. On success, the resolved `OrgType` and `source` are persisted onto the job row in one write (`JobStateWriter.markValidationResolved`) so a later resumed run can reconstruct `ProvisionContext` without re-validating.

### 5b. `source` restricts which steps are even seeded, orthogonal to `org_type`

`org_type` (via `default_config.json` / `ConfigSections`) always decided whether an individual, already-seeded step *applies* — `ProvisionStep.shouldRun(context)`, resulting in a `SKIPPED` row when it doesn't. `source` (via `source_config.json` / `SourceStepConfigProvider`) adds a second, independent, and stronger gate: which steps a given caller (e.g. `ETL_JOB`, `ADMIN_APP`) is allowed to trigger **at all**. Rather than composing as a second `shouldRun`-style check inside the execution loop, `source` gates at *seed time* — `ProvisionWorkflowService.seedStepsForSource` (called once, right after `INITIAL_REQUEST_VALIDATION` succeeds) only ever creates a row for a step in `SourceStepConfigProvider.stepsFor(source)`. A step outside that set has no row, full stop — it can't show up as `SKIPPED`, `NOT_STARTED`, or anything else, because there's nothing in the DB for it. This directly serves the requirement that a job's step list only ever reports steps actually configured for its `source`.

The `execute()` loop still checks `context.isStepEnabledForSource(step.name())` too, as a second line of defense — it's a fast, purely in-memory guard (the set was already computed for seeding) that keeps the loop from touching a step that, for whatever reason, wasn't seeded:

```java
if (step.order() < resumeFromOrder || !context.isStepEnabledForSource(step.name())) {
    continue;   // no row for this step — nothing to update, not even a SKIPPED write
}
if (!step.shouldRun(context)) {
    stepExecutor.skip(jobId, step);   // row exists (source selected it); org type says it doesn't apply
    continue;
}
```

This was deliberately kept out of `ProvisionStep.shouldRun` implementations — a step that's already conditional on org type (e.g. `ENABLE_PRM_LICENSES`) doesn't need to know anything about sources, and a step that's unconditional today doesn't need a `shouldRun` override added just to support source restriction. Existing step classes are untouched; only the orchestrator, seeding, and `ProvisionContext` (which now also carries `enabledSteps: Set<StepName>`, published by `INITIAL_REQUEST_VALIDATION` the same way as `enabledSections`) changed.

`INITIAL_REQUEST_VALIDATION` is never itself gated by `source` — it always runs (and is always pre-seeded at job creation), since it's what validates `source` in the first place. Because unseeded steps are skipped over in the loop without ever calling `StepExecutor`, declared execution order among the steps that *are* seeded is naturally preserved — `source` restricts the set, it never reorders it.

Adding a new source (or changing an existing one's step set) is a config-only change to `source_config.json` — no Java code, no enum. `source` is deliberately a plain validated string (checked via `Set.contains`/membership in the loaded config), not a Java enum like `OrgType`, precisely because new calling systems are expected to keep appearing.

### 6. Read and write paths are separate services

`ProvisionWorkflowService` (write) and `ProvisionJobQueryService` (read) live in different packages and do not share state. The read service assembles the DTO tree; it never mutates. This makes it trivial to later swap the read side for a projection, cache, or read replica without disturbing the workflow.

### 7. Constructor injection only, immutable where possible

Every bean uses `@RequiredArgsConstructor` with `final` fields. DTOs are Java `record`s. Entities are Lombok `@Getter/@Setter/@Builder` because JPA needs setters. No field injection anywhere.

## Concurrency

Each `POST /organizations` is handed off to the `provisioningExecutor` pool (core=4, max=8, queue=100). Two in-flight jobs make independent progress; per-step `REQUIRES_NEW` transactions ensure their audit rows never interfere. The pool is bounded so a burst of requests queues rather than blowing the JVM.

## Failure semantics

- **Step throws** → executor writes `FAILED` (with translated code + message), rethrows `StepExecutionException` (transaction commits despite the throw — see `noRollbackFor` above).
- **Orchestrator catches** → writes job `FAILED` with `finishedAt` set; leaves the failed step's `currentStep` pointer intact.
- **Remaining seeded steps** stay `NOT_STARTED` — no follow-up code needed, because they were pre-seeded that way (fail-fast). Steps `source` never selected have no row and were never a candidate to begin with.
- **`INITIAL_REQUEST_VALIDATION` itself fails** → job `FAILED` with exactly one step row (itself); `source` was never resolved, so nothing else was ever seeded.
- **Unexpected non-step exception** (e.g., DB blip while marking state) → same terminal treatment, logged at ERROR.
- **Retry** (`POST` again with the same `org_uid`) → resumes at the first non-terminal step; already-`SUCCESS`/`SKIPPED` steps are left untouched.

## Enterprise concerns intentionally out of scope for the POC

Flagged in `future-improvements.md`:

- Per-step retry policy (attempt count, backoff, dead-letter after N attempts) — distinct from the job-level resume-on-retry described above, which is already implemented
- Compensation / Saga rollback of already-completed steps
- Distributed / crash-safe async (Quartz, Kafka, Temporal)
- Event sourcing of state transitions
- Auth, tenancy, correlation IDs, tracing, metrics

None of these break the current shape; they add either a new bean or a new field on the SPI.
