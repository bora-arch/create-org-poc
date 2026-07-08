# Future improvements

The POC deliberately stops at the minimum shape that demonstrates the orchestrator pattern. This document sketches the concrete deltas needed to take it from POC to production, ordered by the likely priority a team would tackle them.

## 1. Retry with per-step policy

Add a default method to the SPI:

```java
default RetryPolicy retryPolicy() { return RetryPolicy.none(); }
```

`RetryPolicy` encapsulates `maxAttempts`, `initialBackoff`, `multiplier`, and `retryableCodes`. `StepExecutor` consults it after `StepFailureTranslator.translate` (which already classifies the failure); a retryable failure schedules a retry via the `provisioningExecutor` after the backoff, incrementing an `attemptCount` column on the step row. Non-retryable or exhausted → FAILED as today.

Nothing about orchestration changes. Steps opt into retry by overriding `retryPolicy()`.

## 2. Compensation / Saga rollback

Extend the SPI:

```java
default void compensate(ProvisionContext context) { /* no-op */ }
```

On terminal failure, the orchestrator walks the already-succeeded steps in reverse and calls `compensate` on each. Compensation itself is retried under the same policy as forward execution. Steps that need no rollback (validation, idempotent puts) leave the default.

Persist a `compensation_status` column on each step row so a GET can render partial rollback progress.

## 3. Crash-safe async / distributed execution

The current `@Async` model loses in-flight jobs on JVM restart. Two migration paths:

- **Lightweight** — persist a work queue table (`provision_work_item`) inserted transactionally with the job, polled by a `@Scheduled` worker with `SELECT … FOR UPDATE SKIP LOCKED`. A crashed worker's rows simply get picked up by the next one. Multi-instance safe.
- **Heavy** — hand the workflow to Kafka / RabbitMQ. `POST /organizations` writes the initial event; workers consume, run one step, publish the next. Naturally distributed; per-partition ordering guarantees per-job serialization.

Neither requires touching step code. `StepExecutor` becomes reused inside the worker.

## 4. Event sourcing of state transitions

Persist an append-only `provision_event` table where every status transition (job.IN_PROGRESS, step.SUCCESS with duration, step.FAILED with translated error) is a row. The current `job` and `step` tables become materialized views (either continuously projected or rebuilt on demand).

Benefits: full audit, easy replay, trivial time-travel debugging (`what was the state at 14:37`), a stream to publish onto a downstream bus without touching the write path.

## 5. Migration to Temporal or Camunda

For workflows that outgrow the pattern — long-running, human-in-the-loop, cross-service, requiring versioning:

- **Temporal.** The `ProvisionStep` interface maps naturally to Temporal Activities. The orchestrator body (the `for` loop) becomes a Workflow method — deterministic, replayable, side-effect-free. `ProvisionContext` becomes Workflow state. Retry/backoff/heartbeat/timeouts move into activity annotations. Compensation becomes explicit `Saga` API calls.
- **Camunda.** Better fit if business users need to author or inspect the flow visually. BPMN diagram is the source of truth; each service task binds to a Java class implementing the step.

In both cases the domain layer (entities, DTOs, controllers, mock external client) is reused unchanged. The migration effort is confined to the `workflow.engine` package.

## 6. Idempotent POST

`POST /organizations` should accept an `Idempotency-Key` header and dedupe within a TTL — otherwise a mobile client retrying a timed-out request will spawn a duplicate provisioning job. Two-line change: a `Filter` that looks up the key in a Caffeine cache backed by a table.

## 7. Observability

- **Correlation ID** propagated from `POST` into MDC via a filter; the async pool needs `TaskDecorator` to copy MDC across the thread hop.
- **Metrics.** Micrometer `Timer` per step name (`provisioning.step.duration{name=CREATE_ORG_IN_FSP,outcome=SUCCESS}`), counters for outcomes, gauge for queue depth on `provisioningExecutor`.
- **Tracing.** OpenTelemetry span per step; span attributes for `jobId`, `stepName`, `attempt`.

## 8. Security / tenancy

- Extract `createdBy` from an authenticated principal, not the request body.
- Add tenancy — every job carries a `tenantId`; every repository query filters by it; a `JobNotFoundException` is returned when tenants don't match (never a 403, to avoid leaking existence).
- Rate limit `POST /organizations` per tenant.

## 9. Persistence

- **Flyway** for schema — the POC's `ddl-auto=create-drop` is fine for the demo, not for production. Migrations become part of the deployable.
- **Postgres** in production. The entity layer is portable; only the JDBC URL and dialect change.

## 10. Testing

- Contract tests for the JSON shape (Spring REST Docs or an OpenAPI-conformance suite).
- A property-based test that generates arbitrary sequences of `SUCCESS`/`FAILED` per step and asserts the invariants: a *critical* step failing halts the chain (the failed step is the last non-`NOT_STARTED` step, job `FAILED`); any *non-critical* failures let the chain run to the end (no `NOT_STARTED` remain, job `COMPLETED_WITH_ERRORS` iff ≥1 step failed, else `SUCCESS`).
- Load tests hitting the async pool to size `corePoolSize`, `queueCapacity`, and DB pool.
