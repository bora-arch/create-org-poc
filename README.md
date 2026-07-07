# create-org-poc

Production-shaped POC of an **Organization Provisioning Workflow** built with Java 21, Spring Boot 3, and the **Orchestrator pattern**. A REST endpoint accepts a create request, immediately returns `202 Accepted`, and drives a sequence of 12 external calls asynchronously while persisting per-step progress to H2. A GET endpoint returns full workflow state — completed steps, the failed step (if any), remaining `NOT_STARTED` steps, and progress — for a UI to render. On failure the step row records the error code and message, and the orchestrator writes an `ERROR`-level log with the full stack trace.

## Run

```bash
./gradlew bootRun        # if the wrapper can reach services.gradle.org
# or, if the wrapper is offline in your sandbox:
gradle bootRun
```

Application starts on `http://localhost:8080`. H2 console at `http://localhost:8080/h2-console` (`jdbc:h2:mem:provisioning`, user `sa`, no password).

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
  -d '{"name":"Acme Corporation","createdBy":"sav20006@gmail.com"}'
```

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
  "currentStep": "SETUP_DEFAULT_BRANDING_PREFERENCES",
  "progress": 3,
  "totalSteps": 12,
  "steps": [
    { "name": "CREATE_ORG_IN_FSP",                  "status": "SUCCESS" },
    { "name": "SETUP_ORG_IN_FSP",                   "status": "SUCCESS" },
    { "name": "ASSIGN_RECOMMENDATION_MODELS",       "status": "SUCCESS" },
    { "name": "SETUP_DEFAULT_BRANDING_PREFERENCES", "status": "IN_PROGRESS" },
    { "name": "SETUP_DEFAULT_PRM_PREFERENCES",      "status": "NOT_STARTED" }
  ]
}
```

On failure (mock client randomly fails 15% of calls):

```json
{
  "jobId": "8b1b2f2c-4a11-4e7a-9c6b-7a1c3b2a0e11",
  "status": "FAILED",
  "currentStep": "ASSIGN_RECOMMENDATION_MODELS",
  "progress": 3,
  "totalSteps": 12,
  "steps": [
    { "name": "CREATE_ORG_IN_FSP",            "status": "SUCCESS" },
    { "name": "SETUP_ORG_IN_FSP",             "status": "SUCCESS" },
    { "name": "ASSIGN_RECOMMENDATION_MODELS", "status": "FAILED", "errorCode": "401", "errorMessage": "Unauthorized" },
    { "name": "SETUP_DEFAULT_BRANDING_PREFERENCES", "status": "NOT_STARTED" }
  ]
}
```

Full sample payloads under `docs/samples/`.

## Docs

- [`docs/architecture.md`](docs/architecture.md) — architecture decisions and rationale
- [`docs/diagrams.md`](docs/diagrams.md) — package, class, sequence, and status-transition diagrams (Mermaid)
- [`docs/future-improvements.md`](docs/future-improvements.md) — retry, Saga, distributed async, event sourcing, Temporal/Camunda

## Adding a new step

1. Create a new `@Component` implementing `com.example.provisioning.workflow.spi.ProvisionStep`.
2. Return a new `StepName` enum value from `name()`, and a unique `order()` value gapped between existing steps (`10, 20, 30 …`).
3. Add whatever downstream calls you need using constructor-injected clients.

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
