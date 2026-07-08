# create-org-poc

Production-shaped POC of an **Organization Provisioning Workflow** built with Java 21, Spring Boot 3, and the **Orchestrator pattern**. A REST endpoint accepts a create request, immediately returns `202 Accepted`, and drives a sequence of 12 external calls asynchronously while persisting per-step progress to H2. A GET endpoint returns full workflow state — completed steps, failed steps, skipped steps, remaining `NOT_STARTED` steps, and progress — for a UI to render.

Steps run as a **chain**: a step whose external call fails is recorded `FAILED`, but the workflow keeps going with the remaining steps rather than aborting — unless the failed step is a hard prerequisite (`critical`). See [Chained execution & continue-on-failure](#chained-execution--continue-on-failure).

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
  -d '{"name":"Acme Corporation","createdBy":"sav20006@gmail.com","orgType":"STANDARD"}'
```

`orgType` is optional (`STANDARD` \| `INTERNAL` \| `ENTERPRISE`, defaults to `STANDARD`). It selects a profile in `default_config.json` that decides which steps run and which are recorded `SKIPPED`.

Returns `202 Accepted`:

```json
{ "jobId": "8b1b2f2c-4a11-4e7a-9c6b-7a1c3b2a0e11", "status": "IN_PROGRESS" }
```

### Poll workflow state

```bash
curl -sS http://localhost:8080/organization-provision-jobs/8b1b2f2c-4a11-4e7a-9c6b-7a1c3b2a0e11
```

While running:

```json
{
  "jobId": "8b1b2f2c-4a11-4e7a-9c6b-7a1c3b2a0e11",
  "status": "IN_PROGRESS",
  "currentStep": "SETUP_DEFAULT_BRANDING_PRM_PREFERENCES",
  "progress": 3,
  "totalSteps": 12,
  "steps": [
    { "name": "CREATE_ORG_IN_FSP",                      "status": "SUCCESS" },
    { "name": "SETUP_ORG_IN_FSP",                       "status": "SUCCESS" },
    { "name": "ASSIGN_FSP_RECOMMENDATION_MODELS",       "status": "SUCCESS" },
    { "name": "SETUP_DEFAULT_BRANDING_PRM_PREFERENCES", "status": "IN_PROGRESS" },
    { "name": "SETUP_DEFAULT_PRM_PREFERENCES",          "status": "NOT_STARTED" }
  ]
}
```

On a non-critical failure the chain continues to the end and the job finishes `COMPLETED_WITH_ERRORS` (mock client randomly fails 15% of calls — here `SETUP_ORG_IN_FSP` failed but every later step still ran):

```json
{
  "jobId": "b29b50ba-8380-47ce-9692-e210d80e24b1",
  "status": "COMPLETED_WITH_ERRORS",
  "currentStep": "SETUP_FSP_BOOSTERS",
  "progress": 12,
  "totalSteps": 12,
  "failedSteps": 1,
  "steps": [
    { "name": "CREATE_ORG_IN_FSP",  "status": "SUCCESS" },
    { "name": "SETUP_ORG_IN_FSP",   "status": "FAILED", "errorCode": "404", "errorMessage": "Not Found" },
    { "name": "ASSIGN_FSP_RECOMMENDATION_MODELS", "status": "SUCCESS" },
    { "name": "SETUP_DEFAULT_BRANDING_PRM_PREFERENCES", "status": "SUCCESS" }
  ]
}
```

If instead a **critical** step fails (only `CREATE_ORG_IN_FSP` — it produces the org id every later step needs), the chain halts, the job is `FAILED`, and the remaining steps stay `NOT_STARTED`:

```json
{
  "jobId": "3f7c1a90-2b64-4d0e-8c11-9a5e6b7c0d22",
  "status": "FAILED",
  "currentStep": "CREATE_ORG_IN_FSP",
  "progress": 1,
  "totalSteps": 12,
  "failedSteps": 1,
  "steps": [
    { "name": "CREATE_ORG_IN_FSP", "status": "FAILED", "errorCode": "500", "errorMessage": "Internal Server Error" },
    { "name": "SETUP_ORG_IN_FSP",  "status": "NOT_STARTED" }
  ]
}
```

Full sample payloads under `docs/samples/`: [`GET-job-completed-with-errors.json`](docs/samples/GET-job-completed-with-errors.json), [`GET-job-critical-failed.json`](docs/samples/GET-job-critical-failed.json), [`GET-job-with-skip.json`](docs/samples/GET-job-with-skip.json), and [`db-rows.md`](docs/samples/db-rows.md) showing the persisted table rows.

## Chained execution & continue-on-failure

Steps execute as an ordered chain. A step failing does **not** abort the
whole workflow by default — the orchestrator records the `FAILED` step
(with its error code/message) and continues with the rest. Whether a
failure is fatal is a per-step property:

| | Non-critical step (default) | Critical step |
|---|---|---|
| A step declares it by | *(nothing — the default)* | overriding `boolean critical()` → `true` |
| On failure | record `FAILED`, **continue** the chain | record `FAILED`, **halt** the chain |
| Remaining steps | still run | left `NOT_STARTED` |
| Terminal job status | `COMPLETED_WITH_ERRORS` (if any step failed) | `FAILED` |

Only **`CREATE_ORG_IN_FSP`** is critical: it produces the
`organizationId` every downstream call consumes, so if it fails there is
nothing left to provision. Every other step is best-effort — e.g. if
branding setup fails, the org is still created and configured; the job
just ends `COMPLETED_WITH_ERRORS` so an operator can retry the one
degraded step.

Terminal job statuses:

- `SUCCESS` — every applicable step succeeded (skipped steps are success-like).
- `COMPLETED_WITH_ERRORS` — the chain ran to the end but ≥1 non-critical step failed. `failedSteps` says how many.
- `FAILED` — a critical step failed and the chain was halted.

A step opts into being critical the same way it opts into being
conditional — one override, no engine change:

```java
@Override
public boolean critical() {
    return true;   // failure of this step halts the chain
}
```

### Does this change the database or the response format?

**Database: no structural change.** No new tables or columns. Per-step
`FAILED` rows (with `error_code` / `error_message`) were always part of
the schema, and the job `status` column already stores the enum as a
string — `COMPLETED_WITH_ERRORS` is just a new value in the existing
column. (One correctness fix came with the feature: each step's status
write now commits in its own transaction *bracketing* the external call,
so a `FAILED` row survives instead of being rolled back with the
failure signal — see [`docs/architecture.md`](docs/architecture.md).)

**Response: backward-compatible.** Same JSON shape. `status` can now be
`COMPLETED_WITH_ERRORS`, and one additive field — `failedSteps` (an
`int`, always present) — reports how many steps failed. Existing
consumers keep working.

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
`orgType` selects a profile in
[`default_config.json`](src/main/resources/default_config.json), which
lists the enabled config *sections* for that tier. Each conditional step
checks its section via `context.hasSection(...)` — a missing section
means the step is skipped:

| Step | Section (`ConfigSections`) | STANDARD | INTERNAL | ENTERPRISE |
|------|----------------------------|:--------:|:--------:|:----------:|
| `ASSIGN_FSP_RECOMMENDATION_MODELS`       | `recommendation_models`   | skip | skip | run |
| `SETUP_DEFAULT_BRANDING_PRM_PREFERENCES` | `branding`                | run  | skip | run |
| `SETUP_DEFAULT_RFS_UI_PRM_PREFERENCES`   | `rfs_ui_prm_preferences`  | run  | skip | run |
| `SETUP_DEFAULT_CITATIONS`                | `citations`               | run  | skip | run |
| `ENABLE_PRM_LICENSES`                    | `license` present         | run  | skip | run |
| `DISABLE_PRM_LICENSES`                   | `license` **absent**      | skip | run  | skip |
| `SETUP_FSP_BOOSTERS`                     | `boosters`                | skip | skip | run |

The `license` section shows the mutually exclusive idiom: when it is
present the step *enables* licenses and the *disable* step is skipped;
when absent, the reverse. Core steps (`CREATE_ORG_IN_FSP`,
`SETUP_ORG_IN_FSP`, `SETUP_DEFAULT_PRM_PREFERENCES`,
`SETUP_DEFAULT_VOCABULARIES_IN_CE`, `SETUP_DEFAULT_DATASOURCES_IN_FSP`)
always run. Persisted `SKIPPED` rows carry no `started_at` or
`duration_ms` — see [`docs/samples/db-rows.md`](docs/samples/db-rows.md).

## Docs

- [`docs/openapi.yaml`](docs/openapi.yaml) — generated OpenAPI 3 spec (also served live at `/v3/api-docs`)
- [`docs/architecture.md`](docs/architecture.md) — architecture decisions and rationale
- [`docs/diagrams.md`](docs/diagrams.md) — package, class, sequence, and status-transition diagrams (Mermaid)
- [`docs/samples/`](docs/samples/) — example GET payloads: [success](docs/samples/GET-job-success.json), [in-progress](docs/samples/GET-job-in-progress.json), [with a skipped step](docs/samples/GET-job-with-skip.json), [completed-with-errors](docs/samples/GET-job-completed-with-errors.json), [critical-failed](docs/samples/GET-job-critical-failed.json), and [`db-rows.md`](docs/samples/db-rows.md)
- [`docs/future-improvements.md`](docs/future-improvements.md) — retry, Saga, distributed async, event sourcing, Temporal/Camunda

## Adding a new step

1. Create a new `@Component` implementing `com.example.provisioning.workflow.spi.ProvisionStep`.
2. Return a new `StepName` enum value from `name()`, and a unique `order()` value gapped between existing steps (`10, 20, 30 …`).
3. Add whatever downstream calls you need using constructor-injected clients.
4. Optionally override `shouldRun(context)` to make the step conditional — return `false` and it is recorded `SKIPPED` instead of executed.
5. Optionally override `critical()` to return `true` if a failure of this step must halt the chain (a hard prerequisite). Otherwise a failure is non-blocking and the chain continues.

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
    ├── steps                    12 concrete step beans
    └── query                    read-model service for GET endpoint
```
