# create-org-poc

Production-shaped POC of an **Organization Provisioning Workflow** built with Java 21, Spring Boot 3, and the **Orchestrator pattern**. A REST endpoint accepts a create/retry request, validates it (`INITIAL_REQUEST_VALIDATION`, synchronously), and drives a sequence of up to 12 further external calls asynchronously while persisting per-step progress to H2. `org_uid` doubles as the idempotency/retry key: resubmitting the same `org_uid` after a failure resumes the job at its failed step (fail-fast — later steps never ran) instead of starting over. The request's `source` (e.g. `ETL_JOB`, `ADMIN_APP`) is the **only** thing that decides which steps even run for a job — see [Restricting steps by source](#restricting-steps-by-source-source_configjson). Every step `source` selects always executes; there is no further per-step business condition. A GET endpoint returns full workflow state — completed steps, the failed step (if any), remaining `NOT_STARTED` steps, and progress — for a UI to render.

## Run

```bash
./gradlew bootRun        # if the wrapper can reach services.gradle.org
# or, if the wrapper is offline in your sandbox:
gradle bootRun
```

Application starts on `http://localhost:8080`. H2 console at `http://localhost:8080/h2-console` (`jdbc:h2:mem:provisioning`, user `sa`, no password).

### API docs (OpenAPI / Swagger)

Interactive API documentation is generated at runtime by
[springdoc-openapi](https://springdoc.org):

- **Swagger UI:** `http://localhost:8080/swagger-ui.html`
- **OpenAPI spec (JSON):** `http://localhost:8080/v3/api-docs`
- **OpenAPI spec (YAML):** `http://localhost:8080/v3/api-docs.yaml`

A snapshot of the generated spec is checked in at
[`docs/openapi.yaml`](docs/openapi.yaml) — import it into Postman, an API
gateway, or a client generator without running the app.

## Test

```bash
./gradlew test
```

Covers registry uniqueness, exception translation, and an end-to-end `MockMvc` integration test (deterministic external client → asserts the workflow drives through to SUCCESS).

## API

### Kick off a provisioning workflow

```bash
curl -sS -X POST http://localhost:8080/organizations \
  -H 'content-type: application/json' \
  -d '{"org_uid":"3f2a9c14-7b41-4e2a-9c31-8a2f6d1eb7d2","org_type":"STANDARD","source":"DEFAULT","service_user_account":"sav20006@gmail.com","external_job_uid":"ext-job-482"}'
```

All five fields are required. `org_type` must exactly match one of the `OrgType` enum constants: `STANDARD` \| `INTERNAL` \| `ENTERPRISE` — it's validated and persisted onto the job, but doesn't currently affect which steps run or how they behave (no step reads it). `source` identifies the calling system (e.g. `DEFAULT`, `ETL_JOB`, `ADMIN_APP`) and is the sole gate deciding which steps are even in play at all — see [Restricting steps by source](#restricting-steps-by-source-source_configjson). `org_uid` is also the **idempotency/retry key**: resubmitting the same request body (or a corrected one) after a failure resumes that job at its failed step instead of creating a new one — see [Retrying a failed job](#retrying-a-failed-job).

`INITIAL_REQUEST_VALIDATION` always runs first, and runs **synchronously** on the request thread — format/semantic errors (e.g. `org_uid` isn't a UUID, `org_type` isn't recognized, `source` isn't configured) never start the async workflow; the call returns immediately with `status: FAILED` and the validation error (see below). A structurally invalid request (a required field missing/blank) still gets a plain `400` (see [API docs](#api-docs-openapi--swagger)).

If the request is valid, returns `202 Accepted` with the current snapshot (already reflecting `INITIAL_REQUEST_VALIDATION: SUCCESS`, everything else still queued):

```json
{
  "jobId": "8b1b2f2c-4a11-4e7a-9c6b-7a1c3b2a0e11",
  "orgUid": "3f2a9c14-7b41-4e2a-9c31-8a2f6d1eb7d2",
  "externalJobUid": "ext-job-482",
  "source": "DEFAULT",
  "status": "IN_PROGRESS",
  "currentStep": "INITIAL_REQUEST_VALIDATION",
  "progress": 1,
  "totalSteps": 13
}
```

If validation fails, returns `200 OK` (the job is already terminal — nothing was started). Since `source` is never resolved when validation fails, no other step rows exist either — `steps` has exactly one entry, not twelve `NOT_STARTED` placeholders:

```json
{
  "jobId": "8b1b2f2c-4a11-4e7a-9c6b-7a1c3b2a0e11",
  "orgUid": "not-a-uuid",
  "externalJobUid": "ext-job-482",
  "status": "FAILED",
  "currentStep": "INITIAL_REQUEST_VALIDATION",
  "progress": 1,
  "totalSteps": 1,
  "steps": [
    { "name": "INITIAL_REQUEST_VALIDATION", "status": "FAILED", "errorCode": "VALIDATION_FAILED", "errorMessage": "org_uid must be a valid UUID" }
  ]
}
```

### Poll workflow state

```bash
curl -sS http://localhost:8080/organization-provision-jobs/8b1b2f2c-4a11-4e7a-9c6b-7a1c3b2a0e11
```

While running:

```json
{
  "jobId": "8b1b2f2c-4a11-4e7a-9c6b-7a1c3b2a0e11",
  "orgUid": "3f2a9c14-7b41-4e2a-9c31-8a2f6d1eb7d2",
  "externalJobUid": "ext-job-482",
  "source": "DEFAULT",
  "status": "IN_PROGRESS",
  "currentStep": "SETUP_DEFAULT_BRANDING_PRM_PREFERENCES",
  "progress": 4,
  "totalSteps": 13,
  "steps": [
    { "name": "INITIAL_REQUEST_VALIDATION",             "status": "SUCCESS" },
    { "name": "CREATE_ORG_IN_FSP",                      "status": "SUCCESS" },
    { "name": "SETUP_ORG_IN_FSP",                       "status": "SUCCESS" },
    { "name": "ASSIGN_FSP_RECOMMENDATION_MODELS",       "status": "SUCCESS" },
    { "name": "SETUP_DEFAULT_BRANDING_PRM_PREFERENCES", "status": "IN_PROGRESS" },
    { "name": "SETUP_DEFAULT_PRM_PREFERENCES",          "status": "NOT_STARTED" }
  ]
}
```

On failure (mock client randomly fails 15% of calls):

```json
{
  "jobId": "8b1b2f2c-4a11-4e7a-9c6b-7a1c3b2a0e11",
  "orgUid": "3f2a9c14-7b41-4e2a-9c31-8a2f6d1eb7d2",
  "externalJobUid": "ext-job-482",
  "source": "DEFAULT",
  "status": "FAILED",
  "currentStep": "ASSIGN_FSP_RECOMMENDATION_MODELS",
  "progress": 3,
  "totalSteps": 13,
  "steps": [
    { "name": "INITIAL_REQUEST_VALIDATION",       "status": "SUCCESS" },
    { "name": "CREATE_ORG_IN_FSP",                "status": "SUCCESS" },
    { "name": "SETUP_ORG_IN_FSP",                 "status": "SUCCESS" },
    { "name": "ASSIGN_FSP_RECOMMENDATION_MODELS", "status": "FAILED", "errorCode": "401", "errorMessage": "Unauthorized" },
    { "name": "SETUP_DEFAULT_BRANDING_PRM_PREFERENCES", "status": "NOT_STARTED" }
  ]
}
```

Full sample payloads under `docs/samples/` (including [`GET-job-source-restricted.json`](docs/samples/GET-job-source-restricted.json) and [`db-rows.md`](docs/samples/db-rows.md) showing the persisted table rows).

### Retrying a failed job

Resubmit the exact same `POST /organizations` request (same `org_uid`,
corrected fields if the failure was a validation error):

```bash
curl -sS -X POST http://localhost:8080/organizations \
  -H 'content-type: application/json' \
  -d '{"org_uid":"3f2a9c14-7b41-4e2a-9c31-8a2f6d1eb7d2","org_type":"STANDARD","source":"DEFAULT","service_user_account":"sav20006@gmail.com","external_job_uid":"ext-job-482"}'
```

The workflow **resumes at the failed step** — steps that already
`SUCCESS`/`SKIPPED` on the previous attempt are not re-run. If the job
is still `IN_PROGRESS` or already `SUCCESS`, the same `org_uid` is not
restarted; the current snapshot is returned as-is (`200 OK`).

`service_user_account` / `external_job_uid` can be corrected on a retry
freely. `org_type` and `source` can only be corrected while the job is
still stuck at `INITIAL_REQUEST_VALIDATION` (i.e. the *first* attempt
failed validation) — once later steps have actually run under a given
org type / source, the retry resumes under those same original values
rather than picking up a changed one, so a job's step selection never
desyncs mid-flight.

## Restricting steps by source (`source_config.json`)

The request's `source` identifies the calling system and is the
**only** thing that decides which steps are selected to run **at
all** — e.g. an ETL backfill job only needs a couple of steps, not the
full onboarding flow a human-facing admin console triggers.
[`source_config.json`](src/main/resources/source_config.json) is the
single source of truth, mapping each `source` to the exact
`StepName`s it may trigger:

```json
{
  "DEFAULT":  { "steps": ["CREATE_ORG_IN_FSP", "SETUP_ORG_IN_FSP", "...", "SETUP_FSP_BOOSTERS"] },
  "ETL_JOB":  { "steps": ["CREATE_ORG_IN_FSP", "ENABLE_PRM_LICENSES"] },
  "ADMIN_APP": { "steps": ["CREATE_ORG_IN_FSP", "SETUP_DEFAULT_VOCABULARIES_IN_CE"] }
}
```

`INITIAL_REQUEST_VALIDATION` is never listed — it always runs first
regardless of source, since it is what validates `source` itself. Once
it succeeds, a step row is only ever *created* for steps `source`
selects — a step outside that set has **no row at all** and is
**absent from `steps` entirely**. `progress` / `totalSteps` reflect
only the steps actually configured for that source (e.g. `3`, not
`13`, for `ETL_JOB`). Every step `source` *did* select always runs to
completion (`SUCCESS`/`FAILED`) — `ProvisionStep` has no per-step
business gate of its own, so there's nothing else that could stop it.
Execution order is untouched either way: steps `source` didn't select
are simply never seeded in their normal catalog position, so relative
order among the steps that *do* run is preserved (`ENABLE_PRM_LICENSES`
still runs after `CREATE_ORG_IN_FSP`, with everything in between never
appearing at all).

```bash
# ETL_JOB: only INITIAL_REQUEST_VALIDATION, CREATE_ORG_IN_FSP, ENABLE_PRM_LICENSES run.
curl -sS -X POST http://localhost:8080/organizations \
  -H 'content-type: application/json' \
  -d '{"org_uid":"3f2a9c14-7b41-4e2a-9c31-8a2f6d1eb7d2","org_type":"STANDARD","source":"ETL_JOB","service_user_account":"sav20006@gmail.com","external_job_uid":"ext-job-482"}'

# ADMIN_APP: only INITIAL_REQUEST_VALIDATION, CREATE_ORG_IN_FSP, SETUP_DEFAULT_VOCABULARIES_IN_CE run.
curl -sS -X POST http://localhost:8080/organizations \
  -H 'content-type: application/json' \
  -d '{"org_uid":"...","org_type":"STANDARD","source":"ADMIN_APP","service_user_account":"sav20006@gmail.com","external_job_uid":"ext-job-482"}'
```

The `ETL_JOB` job above, once finished, polls as exactly three steps —
compare with the [`STANDARD`/`DEFAULT` example](#poll-workflow-state)
above, which has thirteen:

```json
{
  "jobId": "8b1b2f2c-4a11-4e7a-9c6b-7a1c3b2a0e11",
  "orgUid": "3f2a9c14-7b41-4e2a-9c31-8a2f6d1eb7d2",
  "externalJobUid": "ext-job-482",
  "source": "ETL_JOB",
  "status": "SUCCESS",
  "currentStep": "ENABLE_PRM_LICENSES",
  "progress": 3,
  "totalSteps": 3,
  "steps": [
    { "name": "INITIAL_REQUEST_VALIDATION", "status": "SUCCESS" },
    { "name": "CREATE_ORG_IN_FSP",          "status": "SUCCESS" },
    { "name": "ENABLE_PRM_LICENSES",        "status": "SUCCESS" }
  ]
}
```

An unconfigured `source` is a validation failure — `INITIAL_REQUEST_VALIDATION`
fails with `"source must be one of: DEFAULT, ETL_JOB, ADMIN_APP"`
(the same synchronous-rejection shape as an invalid `org_uid`/`org_type`).

**Adding a new source is a config-only change:** add an entry to
`source_config.json` with whatever `StepName`s it needs — no Java code
changes, no recompilation of an enum. This is deliberate: new calling
systems (and their step subsets) are expected to keep showing up.

## Docs

- [`docs/openapi.yaml`](docs/openapi.yaml) — generated OpenAPI 3 spec (also served live at `/v3/api-docs`)
- [`docs/architecture.md`](docs/architecture.md) — architecture decisions and rationale
- [`docs/diagrams.md`](docs/diagrams.md) — package, class, sequence, and status-transition diagrams (Mermaid)
- [`docs/samples/db-rows.md`](docs/samples/db-rows.md) — example `organization_provision_job` / `organization_provision_step` rows
- [`docs/future-improvements.md`](docs/future-improvements.md) — retry, Saga, distributed async, event sourcing, Temporal/Camunda

## Adding a new step

1. Create a new `@Component` implementing `com.example.provisioning.workflow.spi.ProvisionStep`.
2. Return a new `StepName` enum value from `name()`, and a unique `order()` value gapped between existing steps (`10, 20, 30 …`).
3. Add whatever downstream calls you need using constructor-injected clients.
4. Add the new `StepName` to whichever `source_config.json` entries should be able to trigger it — a source that doesn't list it never gets a row for it at all, so it won't appear in that source's job responses. There is no per-step business gate beyond this — every step `source` selects always runs to completion.

The orchestrator discovers it automatically. `StepRegistry` fails the app at startup if `order()` collides with an existing step; `SourceStepConfigProvider` fails the app at startup if `source_config.json` references an unknown `StepName` — no other code changes required.

## Project layout

```
com.example.provisioning
├── api                          controllers + DTOs + exception handling
├── config                       async / thread-pool config
├── domain                       JPA entities, enums, repositories
├── external                     downstream client SPI + mock implementation
└── workflow
    ├── spi                      ProvisionStep interface + ProvisionContext
    ├── engine                   orchestrator + step executor + registry + failure translator + state writer + source config provider
    ├── steps                    13 concrete step beans (INITIAL_REQUEST_VALIDATION + 12)
    └── query                    read-model service for GET endpoint
```
