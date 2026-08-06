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

### 2. Every step row is pre-seeded in one shot, at job creation, straight from the raw request

The GET contract requires returning `NOT_STARTED` remaining steps after a failure, and — since `source` restricts the step set — must never report a step the job's `source` doesn't even apply to. Two options were considered:

- **Seed everything up front, straight from the raw request** (chosen): `createJob` seeds `INITIAL_REQUEST_VALIDATION` plus one `NOT_STARTED` row per catalog step `SourceStepConfigProvider.stepsFor(rawSource)` selects — computed directly from the request's raw, not-yet-validated `source` string, in one DB write, before any step has run. A step outside that set never gets a row, ever, for this job. This only works because every step (including validation) now executes asynchronously — see decision 5a; nothing needs to *wait* for validation to succeed before the step set is known, since `source` was already in the request body.
- **Seed lazily, only once validation resolves `source`** (previous design, rejected once validation itself became async): seed only `INITIAL_REQUEST_VALIDATION` at creation, then seed the rest after it succeeds. This was the original shape when validation ran synchronously on the request thread — it worked because the seeding write and the validation-success check happened in the same synchronous call. Once validation moved into the normal async loop (decision 5a), this would have meant a job's *other* rows didn't exist until an arbitrary point in the middle of its own asynchronous execution — awkward to reason about and no longer necessary, since the raw `source` string needed to seed is available immediately at creation regardless of whether it later turns out to be valid.
- **Seed everything, filter at read time** (rejected): pre-seed the full catalog, then have the query handler drop rows not in the job's source set. Filtering logic in the read path directly contradicts goal 6 below (read side stays dumb DTO assembly); it also means every `NOT_STARTED` row for an excluded step briefly exists in the DB for no reason.

Pre-seeding keeps the read path a trivial `findByJobIdOrderByStepOrder` — no business logic in the query handler; whatever rows exist *are* the response. It also freezes each job's applicable step list at the catalog snapshot in force at creation time, giving automatic historical auditability if steps are added to the catalog (or a source's config) later.

One consequence: if the raw `source` string itself is unrecognized, `stepsFor` yields an empty set, so a job has exactly one step row, ever (`INITIAL_REQUEST_VALIDATION`) — there was no known step set to seed. `progress`/`totalSteps` are `1`/`1` in that case, not `1`/`13`. If `source` is valid but some *other* field is invalid (`org_uid`, `org_type`, `service_user_account`), the full step set for that `source` is still seeded — validation only fails once it actually runs, which is after seeding already happened.

### 3. Per-step `REQUIRES_NEW` transaction, not job-wide

A single job-wide transaction would (a) hold a DB connection across all external calls and (b) roll back the audit trail on failure — the exact opposite of what we need, since the FAILED row is the point. Per-step boundaries let the GET endpoint observe `IN_PROGRESS` in real time. `StepExecutor` and `JobStateWriter` are both annotated `@Transactional(REQUIRES_NEW)`.

For POC simplicity the external call runs inside the step's transaction. Production would bracket the external call with two short transactions (mark-start, then mark-end after the call) with the network I/O outside any transaction — the code shape is identical, only the `@Transactional` boundary moves.

`StepExecutor.execute` also declares `noRollbackFor = StepExecutionException.class`. It deliberately throws that exception, from inside the same `@Transactional` method, to signal the orchestrator to halt — but Spring's default rule rolls back a transaction on *any* unchecked exception that propagates past its boundary, which would silently discard the `markFailed` write the method just made. Without `noRollbackFor`, no FAILED row is ever actually persisted, which breaks both the GET contract and retry (there would be nothing to resume from).

### 3a. Retry is a resume, not a restart

`org_uid` (from the request) is the job's business key — `WorkflowRepository.findByOrgUid` looks it up on every `POST`. If no job exists, one is created and every step row it will ever have is seeded immediately (see decision 2). If one exists and is `FAILED`, the same job is reused: `ProvisionWorkflowService.firstPendingStepOrder` finds the lowest-order step that is still `NOT_STARTED` or `FAILED`, and `execute(..., resumeFromOrder)` skips every step with a lower order — they are already `SUCCESS` from a prior attempt — and resumes exactly at the failed one. Combined with fail-fast (steps after a failure are never touched, guaranteed by pre-seeding), this gives "resubmit the same request, it picks up where it left off" without a separate retry endpoint or extra state machine.

If the job is already `SUCCESS` or `IN_PROGRESS`, the same `org_uid` is not restarted — the current snapshot is returned as-is (`200 OK`).

`findOrCreateJob` only refreshes `serviceUserAccount` / `externalJobUid` on an existing job — `orgType` and `source` are never overwritten there. `orgType`/`source` are persisted onto the job row once by `markValidationResolved`, when `INITIAL_REQUEST_VALIDATION` actually succeeds (which may be on a later retry, not necessarily the first attempt); until then they stay `NULL` on the job row, and each retry's `execute()` call carries that retry's raw `org_type`/`source` values for validation to parse fresh. Once validation *has* succeeded, though, a further retry's raw values are ignored — `execute()` reconstructs `orgType` from the persisted job row instead of re-parsing the request — so a job's resolved identity can't desync mid-flight from a later request that tries to change it.

While validation hasn't succeeded yet, `execute()` also calls `ensureStepsSeeded(jobId, rawSource)` — idempotently, only inserting rows that don't already exist — using *this* attempt's raw `source` before the step loop runs. This matters for a job whose first attempt had an unrecognized `source` (so only `INITIAL_REQUEST_VALIDATION` was ever seeded — see decision 2): without topping up the seeded rows on the retry that corrects `source`, validation would succeed against the corrected value but the loop would have no further rows to execute, silently reporting the job `SUCCESS` after running exactly one step. Because `ensureStepsSeeded` is gated on `job.getOrgType() == null`, it can never run once validation has already succeeded — a job's step set is fixed for good at that point, so a later request changing `source` can't silently add or remove rows out from under an in-flight or completed run.

### 4. Async via a named `ThreadPoolTaskExecutor`, wrapped in a separate bean

`ProvisionWorkflowAsyncRunner.run(UUID, ...)` is `@Async("provisioningExecutor")`. It is a **separate bean** because `@Async` requires proxied invocation — a self-invocation from within `ProvisionWorkflowService` would silently run synchronously. Only primitive/immutable arguments cross the async boundary (`UUID`, `String`) — no JPA entities, which would be detached in the target thread.

### 5. `StepFailureTranslator` isolates exception mapping from persistence

`StepExecutor` knows how to write step rows; it does not know how to interpret arbitrary `Throwable`s. The translator maps `ExternalCallException → (its code, its message)`, `RequestValidationException → VALIDATION_FAILED`, and everything else to `INTERNAL_ERROR`. When per-step retry policies (attempt/backoff, not the job-level resume described above) land, this is the one place that will need to classify failures as retryable/non-retryable — the executor stays untouched.

### 5a. `INITIAL_REQUEST_VALIDATION` always runs first, and — like every other step — runs asynchronously

The request's raw `org_uid` / `org_type` / `source` / `service_user_account` are validated by a real `ProvisionStep` (order `0`) rather than bean-validation annotations, so an invalid value is persisted and reported through the same step-failure machinery as any other step (visible identically via `POST` and `GET`). There is no synchronous validation path: `INITIAL_REQUEST_VALIDATION` runs through the exact same asynchronous `execute()` loop as every other step, on whichever `provisioningExecutor` thread picks up the job. `POST /organizations` always returns `202 Accepted` immediately, once the job is created/found and its rows are seeded (see decision 2) — an invalid request is never rejected synchronously; the caller only learns about it via a later `GET` showing `status: FAILED`. On success, the resolved `OrgType` is persisted onto the job row in one write (`JobStateWriter.markValidationResolved`, called from inside the `execute()` loop right after the validation step returns) so a later resumed run can reconstruct `ProvisionContext` without re-validating.

This is a deliberate trade from an earlier version of this design, which ran `INITIAL_REQUEST_VALIDATION` synchronously on the request thread specifically so an invalid request could be rejected in the `POST` response itself. Moving it into the async loop trades that immediate-rejection UX for a simpler execution model — every step, no exceptions, goes through one code path (`ProvisionWorkflowService.execute`) — and for the ability to seed a job's full step set at creation time (decision 2), which the synchronous-validation version couldn't do without waiting for validation to actually run first.

### 5b. `source` is the only gate — no step has a business condition of its own

`source` (via `source_config.json` / `SourceStepConfigProvider`) is the **only** thing that decides whether a step runs at all, for a given caller (e.g. `ETL_JOB`, `ADMIN_APP`). It gates at *seed time* — `ProvisionWorkflowService.ensureStepsSeeded` (called once at job creation, and again on any retry before validation has succeeded — see decision 3a) only ever creates a row for a step in `SourceStepConfigProvider.stepsFor(source)`, computed from the raw request string. A step outside that set has no row, full stop — it can't show up in a response under any status, because there's nothing in the DB for it. This directly serves the requirement that a job's step list only ever reports steps actually configured for its `source`.

The `execute()` loop checks a plain `Set<StepName>` read straight from the job's existing rows — a fast, purely in-memory guard once computed — so it never touches a step that, for whatever reason, wasn't seeded:

```java
Set<StepName> seededSteps = stepRepository.findByJobIdOrderByStepOrder(jobId).stream()
    .map(OrganizationProvisionStep::getStepName)
    .collect(Collectors.toSet());
...
if (step.order() < resumeFromOrder || !seededSteps.contains(step.name())) {
    continue;   // no row for this step — nothing to update
}
jobStateWriter.markCurrentStep(jobId, step.name());
stepExecutor.execute(jobId, step, context);
```

`ProvisionStep` has no `shouldRun` hook at all — a step selected by `source` always executes, unconditionally. `org_type` is still validated and persisted onto the job (`markValidationResolved`), and `ProvisionContext` still carries the resolved `OrgType`, but no step reads it — `EnablePrmLicensesStep`/`DisablePrmLicensesStep` included, which used to be the one pair that read `org_type`-derived config (`default_config.json` / `DefaultConfigProvider`) to decide whether to run. That mechanism, along with `DefaultConfigProvider` and `ConfigSections`, has been removed entirely: it was a second, per-step gate layered on top of `source`, and the simpler model — `source` alone decides what runs — was judged not worth the extra moving part for two steps.

`INITIAL_REQUEST_VALIDATION` is never itself gated by `source` — it always runs (and is always pre-seeded, unconditionally), since it's what validates `source` in the first place. Because unseeded steps are skipped over in the loop without ever calling `StepExecutor`, declared execution order among the steps that *are* seeded is naturally preserved — `source` restricts the set, it never reorders it.

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
- **`INITIAL_REQUEST_VALIDATION` itself fails** → job `FAILED`, with `INITIAL_REQUEST_VALIDATION` `FAILED` and every other seeded step `NOT_STARTED` (fail-fast — they were never reached). If the raw `source` was itself unrecognized, there was no step set to seed at all, so the job has exactly one step row, period; otherwise the full step set for that `source` was already seeded at creation, and the failure just means none of the rest ran yet.
- **Unexpected non-step exception** (e.g., DB blip while marking state) → same terminal treatment, logged at ERROR.
- **Retry** (`POST` again with the same `org_uid`) → resumes at the first non-terminal step; already-`SUCCESS` steps are left untouched.

## Enterprise concerns intentionally out of scope for the POC

Flagged in `future-improvements.md`:

- Per-step retry policy (attempt count, backoff, dead-letter after N attempts) — distinct from the job-level resume-on-retry described above, which is already implemented
- Compensation / Saga rollback of already-completed steps
- Distributed / crash-safe async (Quartz, Kafka, Temporal)
- Event sourcing of state transitions
- Auth, tenancy, correlation IDs, tracing, metrics

None of these break the current shape; they add either a new bean or a new field on the SPI.
