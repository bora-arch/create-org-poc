# create-org-poc

Production-shaped POC of an **Organization Provisioning Workflow** built with Java 21, Spring Boot 3, and the **Orchestrator pattern**. A REST endpoint accepts a create/retry request, validates it (`INITIAL_REQUEST_VALIDATION`, synchronously), and drives a sequence of 12 further external calls asynchronously while persisting per-step progress to H2. `org_uid` doubles as the idempotency/retry key: resubmitting the same `org_uid` after a failure resumes the job at its failed step (fail-fast — later steps never ran) instead of starting over. A GET endpoint returns full workflow state — completed steps, the failed step (if any), skipped steps, remaining `NOT_STARTED` steps, and progress — for a UI to render.

Steps that do not apply to a given job (by business rule) are recorded `SKIPPED` and never executed — see [Conditional steps](#conditional-steps-skipped).

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
  -d '{"org_uid":"3f2a9c14-7b41-4e2a-9c31-8a2f6d1eb7d2","org_type":"base","service_user_account":"sav20006@gmail.com","external_job_uid":"ext-job-482"}'
```

All four fields are required. `org_type` is one of `base` \| `internal` \| `enterprise` — it selects a profile in `default_config.json` that decides which steps run and which are recorded `SKIPPED`. `org_uid` is also the **idempotency/retry key**: resubmitting the same request body (or a corrected one) after a failure resumes that job at its failed step instead of creating a new one — see [Retrying a failed job](#retrying-a-failed-job).

`INITIAL_REQUEST_VALIDATION` always runs first, and runs **synchronously** on the request thread — format/semantic errors (e.g. `org_uid` isn't a UUID, `org_type` isn't recognized) never start the async workflow; the call returns immediately with `status: FAILED` and the validation error (see below). A structurally invalid request (a required field missing/blank) still gets a plain `400` (see [API docs](#api-docs-openapi--swagger)).

If the request is valid, returns `202 Accepted` with the current snapshot (already reflecting `INITIAL_REQUEST_VALIDATION: SUCCESS`, everything else still queued):

```json
{
  "jobId": "8b1b2f2c-4a11-4e7a-9c6b-7a1c3b2a0e11",
  "orgUid": "3f2a9c14-7b41-4e2a-9c31-8a2f6d1eb7d2",
  "externalJobUid": "ext-job-482",
  "status": "IN_PROGRESS",
  "currentStep": "INITIAL_REQUEST_VALIDATION",
  "progress": 1,
  "totalSteps": 13
}
```

If validation fails, returns `200 OK` (the job is already terminal — nothing was started):

```json
{
  "jobId": "8b1b2f2c-4a11-4e7a-9c6b-7a1c3b2a0e11",
  "orgUid": "not-a-uuid",
  "externalJobUid": "ext-job-482",
  "status": "FAILED",
  "currentStep": "INITIAL_REQUEST_VALIDATION",
  "progress": 0,
  "totalSteps": 13,
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

Full sample payloads under `docs/samples/` (including [`GET-job-with-skip.json`](docs/samples/GET-job-with-skip.json) and [`db-rows.md`](docs/samples/db-rows.md) showing the persisted table rows).

### Retrying a failed job

Resubmit the exact same `POST /organizations` request (same `org_uid`,
corrected fields if the failure was a validation error):

```bash
curl -sS -X POST http://localhost:8080/organizations \
  -H 'content-type: application/json' \
  -d '{"org_uid":"3f2a9c14-7b41-4e2a-9c31-8a2f6d1eb7d2","org_type":"base","service_user_account":"sav20006@gmail.com","external_job_uid":"ext-job-482"}'
```

The workflow **resumes at the failed step** — steps that already
`SUCCESS`/`SKIPPED` on the previous attempt are not re-run. If the job
is still `IN_PROGRESS` or already `SUCCESS`, the same `org_uid` is not
restarted; the current snapshot is returned as-is (`200 OK`).

## Conditional steps (SKIPPED)

Not every step applies to every job. A step declares its applicability by
overriding `boolean shouldRun(ProvisionContext)` on `ProvisionStep`
(default `true`). Before each step, the orchestrator asks:

- `shouldRun == true` → execute normally (`IN_PROGRESS` → `SUCCESS`/`FAILED`).
- `shouldRun == false` → `StepExecutor.skip(...)` records the row as
  `SKIPPED` — no external call is made.

`SKIPPED` is a terminal, success-like state: it counts toward `progress`
and never fails the job.

**What drives the skip: the org type's config profile.** The request's
`org_type` (`base` \| `internal` \| `enterprise`) is resolved by
`INITIAL_REQUEST_VALIDATION` into an `OrgType` (`base` → `STANDARD`),
which selects a profile in
[`default_config.json`](src/main/resources/default_config.json) (keyed
by the internal enum names `STANDARD`/`INTERNAL`/`ENTERPRISE`), listing
the enabled config *sections* for that tier. Each conditional step
checks its section via `context.hasSection(...)` — a missing section
means the step is skipped:

| Step | Section (`ConfigSections`) | base | internal | enterprise |
|------|----------------------------|:----:|:--------:|:----------:|
| `ASSIGN_FSP_RECOMMENDATION_MODELS`       | `recommendation_models`   | skip | skip | run |
| `SETUP_DEFAULT_BRANDING_PRM_PREFERENCES` | `branding`                | run  | skip | run |
| `SETUP_DEFAULT_RFS_UI_PRM_PREFERENCES`   | `rfs_ui_prm_preferences`  | run  | skip | run |
| `SETUP_DEFAULT_CITATIONS`                | `citations`               | run  | skip | run |
| `ENABLE_PRM_LICENSES`                    | `license` present         | run  | skip | run |
| `DISABLE_PRM_LICENSES`                   | `license` **absent**      | skip | run  | skip |
| `SETUP_FSP_BOOSTERS`                     | `boosters`                | skip | skip | run |

The `license` section shows the mutually exclusive idiom: when it is
present the step *enables* licenses and the *disable* step is skipped;
when absent, the reverse. `INITIAL_REQUEST_VALIDATION` and the core
steps (`CREATE_ORG_IN_FSP`, `SETUP_ORG_IN_FSP`,
`SETUP_DEFAULT_PRM_PREFERENCES`, `SETUP_DEFAULT_VOCABULARIES_IN_CE`,
`SETUP_DEFAULT_DATASOURCES_IN_FSP`)
always run. Persisted `SKIPPED` rows carry no `started_at` or
`duration_ms` — see [`docs/samples/db-rows.md`](docs/samples/db-rows.md).

## Docs

- [`docs/openapi.yaml`](docs/openapi.yaml) — generated OpenAPI 3 spec (also served live at `/v3/api-docs`)
- [`docs/architecture.md`](docs/architecture.md) — architecture decisions and rationale
- [`docs/diagrams.md`](docs/diagrams.md) — package, class, sequence, and status-transition diagrams (Mermaid)
- [`docs/samples/db-rows.md`](docs/samples/db-rows.md) — example `organization_provision_job` / `organization_provision_step` rows (with a skipped step)
- [`docs/future-improvements.md`](docs/future-improvements.md) — retry, Saga, distributed async, event sourcing, Temporal/Camunda

## Adding a new step

1. Create a new `@Component` implementing `com.example.provisioning.workflow.spi.ProvisionStep`.
2. Return a new `StepName` enum value from `name()`, and a unique `order()` value gapped between existing steps (`10, 20, 30 …`).
3. Add whatever downstream calls you need using constructor-injected clients.
4. Optionally override `shouldRun(context)` to make the step conditional — return `false` and it is recorded `SKIPPED` instead of executed.

The orchestrator discovers it automatically. `StepRegistry` fails the app at startup if `order()` collides with an existing step — no other code changes required.

## Project layout

```
com.example.provisioning
├── api                          controllers + DTOs + exception handling
├── config                       async / thread-pool config
├── domain                       JPA entities, enums, repositories
├── external                     downstream client SPI + mock implementation
└── workflow
    ├── spi                      ProvisionStep interface + ProvisionContext
    ├── engine                   orchestrator + step executor + registry + failure translator + state writer
    ├── steps                    13 concrete step beans (INITIAL_REQUEST_VALIDATION + 12)
    └── query                    read-model service for GET endpoint
```
