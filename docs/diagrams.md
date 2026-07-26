# Diagrams

All diagrams are Mermaid — GitHub renders them inline in the browser and they diff cleanly in pull requests.

## Package diagram

```mermaid
flowchart TB
    subgraph api["api"]
        controllers["OrganizationController<br/>ProvisionJobController"]
        dtos["dto.*"]
        errs["error.*"]
    end

    subgraph workflow["workflow"]
        subgraph spi["workflow.spi"]
            step_iface["ProvisionStep<br/>ProvisionContext<br/>ConfigSections"]
        end
        subgraph engine["workflow.engine"]
            orch["ProvisionWorkflowService<br/>ProvisionWorkflowAsyncRunner<br/>StepExecutor<br/>StepRegistry<br/>StepFailureTranslator<br/>JobStateWriter<br/>DefaultConfigProvider"]
        end
        subgraph steps["workflow.steps"]
            step_beans["13 concrete steps (INITIAL_REQUEST_VALIDATION + 12)"]
        end
        subgraph query["workflow.query"]
            qs["ProvisionJobQueryService"]
        end
    end

    subgraph domain["domain"]
        entities["OrganizationProvisionJob<br/>OrganizationProvisionStep<br/>enums"]
        repos["WorkflowRepository<br/>StepRepository"]
    end

    subgraph external["external"]
        ext["ExternalOrganizationClient<br/>MockExternalOrganizationClient"]
    end

    subgraph config["config"]
        cfg["AsyncConfig"]
    end

    api --> workflow
    orch --> spi
    orch --> engine
    orch --> domain
    step_beans --> spi
    step_beans --> external
    qs --> domain
    engine --> domain
    orch --> cfg
```

## Class diagram

```mermaid
classDiagram
    class ProvisionStep {
        <<interface>>
        +name() StepName
        +order() int
        +execute(ctx) void
        +shouldRun(ctx) boolean
    }

    class ProvisionContext {
        -jobId : UUID
        -rawOrgUid : String
        -rawOrgType : String
        -serviceUserAccount : String
        -externalJobUid : String
        -organizationId : UUID
        -orgType : OrgType
        -enabledSections : Set
        -attributes : Map
        +hasSection(section) boolean
        +markValidated(orgId, orgType, sections) void
        +get(key, type) Optional
        +put(key, value) void
    }

    class DefaultConfigProvider {
        +sectionsFor(orgType) Set
    }

    class ProvisionWorkflowService {
        +findOrCreateJob(orgUid, serviceUserAccount, externalJobUid) Job
        +firstPendingStepOrder(jobId) int
        +validateSynchronously(jobId, rawOrgUid, rawOrgType, serviceUserAccount, externalJobUid) boolean
        +markResuming(jobId) void
        +execute(jobId, rawOrgUid, serviceUserAccount, externalJobUid, resumeFromOrder) void
    }

    class ProvisionWorkflowAsyncRunner {
        +run(jobId, rawOrgUid, serviceUserAccount, externalJobUid, resumeFromOrder) void
    }

    class StepRegistry {
        -ordered : List~ProvisionStep~
        +ordered() List
        +total() int
    }

    class StepExecutor {
        +execute(jobId, step, ctx) void
    }

    class StepFailureTranslator {
        +translate(t) StepFailure
    }

    class JobStateWriter {
        +markStarted(jobId) void
        +markCurrentStep(jobId, name) void
        +markOrgTypeResolved(jobId, orgType) void
        +markFinished(jobId, status) void
    }

    class ProvisionJobQueryService {
        +fetch(jobId) ProvisionJobResponse
    }

    class ExternalOrganizationClient {
        <<interface>>
    }

    class OrganizationProvisionJob {
        id, orgUid, externalJobUid, orgType, status,
        currentStep, startedAt, finishedAt, serviceUserAccount
    }

    class OrganizationProvisionStep {
        id, jobId, stepOrder, stepName,
        status, startedAt, finishedAt,
        durationMs, errorCode, errorMessage
    }

    ProvisionWorkflowAsyncRunner --> ProvisionWorkflowService
    ProvisionWorkflowService --> StepRegistry
    ProvisionWorkflowService --> StepExecutor
    ProvisionWorkflowService --> JobStateWriter
    ProvisionWorkflowService --> DefaultConfigProvider
    StepRegistry o--> "*" ProvisionStep
    StepExecutor --> StepFailureTranslator
    StepExecutor --> OrganizationProvisionStep
    JobStateWriter --> OrganizationProvisionJob
    ProvisionJobQueryService --> OrganizationProvisionJob
    ProvisionJobQueryService --> OrganizationProvisionStep
    ProvisionStep <|.. InitialRequestValidationStep
    ProvisionStep <|.. CreateOrgInFspStep
    ProvisionStep <|.. SetupOrgInFspStep
    ProvisionStep <|.. AssignFspRecommendationModelsStep
    ProvisionStep <|.. SetupDefaultBrandingPrmPreferencesStep
    ProvisionStep <|.. SetupDefaultPrmPreferencesStep
    ProvisionStep <|.. SetupDefaultRfsUiPrmPreferencesStep
    ProvisionStep <|.. SetupDefaultVocabulariesInCeStep
    ProvisionStep <|.. SetupDefaultDatasourcesInFspStep
    ProvisionStep <|.. SetupDefaultCitationsStep
    ProvisionStep <|.. EnablePrmLicensesStep
    ProvisionStep <|.. DisablePrmLicensesStep
    ProvisionStep <|.. SetupFspBoostersStep
    CreateOrgInFspStep --> ExternalOrganizationClient
```

## Sequence — successful workflow

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant OC as OrganizationController
    participant WS as ProvisionWorkflowService
    participant AR as AsyncRunner
    participant SE as StepExecutor
    participant Step as ProvisionStep
    participant Ext as ExternalOrganizationClient
    participant DB as H2 (job / step rows)

    Client->>OC: POST /organizations {org_uid, org_type, service_user_account, external_job_uid}
    OC->>WS: findOrCreateJob(orgUid, serviceUserAccount, externalJobUid)
    WS->>DB: INSERT job (PENDING)
    WS->>DB: INSERT 13 step rows (NOT_STARTED)
    WS-->>OC: job (id, status=PENDING)
    OC->>WS: firstPendingStepOrder(jobId)
    WS-->>OC: 0 (INITIAL_REQUEST_VALIDATION)
    OC->>WS: validateSynchronously(jobId, rawOrgUid, rawOrgType, ...)
    Note over OC,WS: runs on the request thread, not async
    WS->>DB: UPDATE job status=IN_PROGRESS
    WS->>DB: UPDATE step INITIAL_REQUEST_VALIDATION status=SUCCESS
    WS-->>OC: true (valid)
    OC->>WS: firstPendingStepOrder(jobId)
    WS-->>OC: 10 (CREATE_ORG_IN_FSP)
    OC->>WS: markResuming(jobId)
    OC->>AR: run(jobId, orgUid, serviceUserAccount, externalJobUid, resumeFromOrder=10)
    OC-->>Client: 202 { jobId, orgUid, externalJobUid, status: IN_PROGRESS, progress: 1 }

    Note over AR,DB: async — provisioning-N thread pool

    AR->>WS: execute(jobId, ..., resumeFromOrder=10)
    WS->>DB: UPDATE job status=IN_PROGRESS
    loop each step in registry.ordered() with order >= resumeFromOrder
        alt step.shouldRun(ctx) == false
            WS->>SE: skip(jobId, step)
            SE->>DB: UPDATE step status=SKIPPED, finishedAt
        else applies to this org type
            WS->>DB: UPDATE job currentStep=<name>
            WS->>SE: execute(jobId, step, ctx)
            SE->>DB: UPDATE step status=IN_PROGRESS, startedAt
            SE->>Step: execute(ctx)
            Step->>Ext: <method>()
            Ext-->>Step: result
            Step-->>SE: return
            SE->>DB: UPDATE step status=SUCCESS, finishedAt, duration
        end
    end
    WS->>DB: UPDATE job status=SUCCESS, finishedAt

    Client->>OC: GET /organization-provision-jobs/{jobId}
    OC-->>Client: 200 { status: SUCCESS, progress: 13, steps: [...] }
```

## Sequence — failed workflow

```mermaid
sequenceDiagram
    autonumber
    participant WS as ProvisionWorkflowService
    participant SE as StepExecutor
    participant Step as AssignFspRecommendationModelsStep
    participant Ext as ExternalOrganizationClient
    participant FT as StepFailureTranslator
    participant DB as H2

    WS->>SE: execute(jobId, ASSIGN_FSP_RECOMMENDATION_MODELS, ctx)
    SE->>DB: UPDATE step ASSIGN_FSP_RECOMMENDATION_MODELS status=IN_PROGRESS
    SE->>Step: execute(ctx)
    Step->>Ext: assignFspRecommendationModels(orgId)
    Ext--x Step: ExternalCallException(401, "Unauthorized")
    Step--x SE: propagates
    SE->>FT: translate(exception)
    FT-->>SE: StepFailure("401", "Unauthorized")
    SE->>DB: UPDATE step ASSIGN_FSP_RECOMMENDATION_MODELS status=FAILED, errorCode, errorMessage
    Note over SE: @Transactional(REQUIRES_NEW, noRollbackFor=StepExecutionException) — the FAILED write survives the throw
    SE--x WS: StepExecutionException(ASSIGN_FSP_RECOMMENDATION_MODELS)
    WS->>DB: UPDATE job status=FAILED, finishedAt
    Note over DB: SETUP_DEFAULT_BRANDING_PRM_PREFERENCES..SETUP_FSP_BOOSTERS remain NOT_STARTED (pre-seeded)
```

## Sequence — retry resumes at the failed step

Same request body, same `org_uid`, resubmitted after the failure above.
`CREATE_ORG_IN_FSP` / `SETUP_ORG_IN_FSP` (already `SUCCESS`) and
`INITIAL_REQUEST_VALIDATION` (already `SUCCESS`) are **not** re-run —
`resumeFromOrder` skips every step ordered below the first non-terminal
one.

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant OC as OrganizationController
    participant WS as ProvisionWorkflowService
    participant AR as AsyncRunner
    participant DB as H2

    Client->>OC: POST /organizations {org_uid: <same>, ...}
    OC->>WS: findOrCreateJob(orgUid, ...)
    WS->>DB: SELECT job WHERE org_uid = ?
    DB-->>WS: existing job, status=FAILED
    WS-->>OC: job (existing)
    OC->>WS: firstPendingStepOrder(jobId)
    WS->>DB: SELECT steps WHERE job_id = ? ORDER BY step_order
    Note over WS: first NOT_STARTED/FAILED = ASSIGN_FSP_RECOMMENDATION_MODELS (order 30)
    WS-->>OC: 30
    Note over OC: 30 != INITIAL_REQUEST_VALIDATION.ORDER(0) — sync validation is skipped
    OC->>WS: markResuming(jobId)
    WS->>DB: UPDATE job status=IN_PROGRESS
    OC->>AR: run(jobId, ..., resumeFromOrder=30)
    OC-->>Client: 202 { status: IN_PROGRESS, progress: 2, ... }

    Note over AR,DB: async
    AR->>WS: execute(jobId, ..., resumeFromOrder=30)
    Note over WS: reconstructs orgType/enabledSections from the persisted job row (validation already succeeded)
    Note over WS: steps with order < 30 (INITIAL_REQUEST_VALIDATION, CREATE_ORG_IN_FSP, SETUP_ORG_IN_FSP) are skipped entirely — not re-executed
    WS->>DB: retry ASSIGN_FSP_RECOMMENDATION_MODELS, then continue through SETUP_FSP_BOOSTERS
    WS->>DB: UPDATE job status=SUCCESS
```

## State transitions

### Workflow status

```mermaid
stateDiagram-v2
    [*] --> PENDING : createJob
    PENDING --> IN_PROGRESS : execute begins
    IN_PROGRESS --> SUCCESS : every step SUCCESS
    IN_PROGRESS --> FAILED : any step FAILED
    SUCCESS --> [*]
    FAILED --> [*]
```

### Step status

```mermaid
stateDiagram-v2
    [*] --> NOT_STARTED : pre-seed
    NOT_STARTED --> IN_PROGRESS : StepExecutor.execute (shouldRun == true)
    NOT_STARTED --> SKIPPED : StepExecutor.skip (shouldRun == false)
    IN_PROGRESS --> SUCCESS : step returns
    IN_PROGRESS --> FAILED : step throws
    SUCCESS --> [*]
    FAILED --> [*]
    SKIPPED --> [*]
```

A step is pre-seeded `NOT_STARTED`. When the orchestrator reaches it,
`ProvisionStep.shouldRun(context)` decides the branch: `true` → the
step executes (`IN_PROGRESS` → `SUCCESS`/`FAILED`); `false` → the step
is recorded `SKIPPED` and never invoked. `SKIPPED` is a terminal
success-like state — it counts toward `progress` and does **not** fail
the job.

## Sequence — conditional skip (SKIPPED)

The request's `org_type` must exactly match one of the `OrgType` enum
constants (`STANDARD` / `INTERNAL` / `ENTERPRISE`) — `INITIAL_REQUEST_VALIDATION`
parses it via `OrgType.valueOf(...)` (no case-insensitive or alias mapping)
and publishes it onto the context via `markValidated`; `DefaultConfigProvider`
resolves that type's enabled sections. Each later step's `shouldRun(ctx)`
checks its section — a missing section means the step is skipped with no
external call.

Shown for `org_type = "STANDARD"`, whose profile has `license` but not
`boosters`: `ENABLE_PRM_LICENSES` runs, `DISABLE_PRM_LICENSES` and
`SETUP_FSP_BOOSTERS` are skipped.

```mermaid
sequenceDiagram
    autonumber
    participant WS as ProvisionWorkflowService
    participant CFG as DefaultConfigProvider
    participant Step as ProvisionStep
    participant SE as StepExecutor
    participant DB as H2

    WS->>CFG: sectionsFor(STANDARD)
    CFG-->>WS: {branding, rfs_ui_prm_preferences, citations, license}
    Note over WS: context.enabledSections = ↑

    WS->>Step: shouldRun(ctx)  [ENABLE_PRM_LICENSES → hasSection("license")]
    Step-->>WS: true
    WS->>SE: execute(jobId, ENABLE_PRM_LICENSES, ctx)
    SE->>DB: UPDATE step status=IN_PROGRESS → SUCCESS

    WS->>Step: shouldRun(ctx)  [DISABLE_PRM_LICENSES → !hasSection("license")]
    Step-->>WS: false
    WS->>SE: skip(jobId, DISABLE_PRM_LICENSES)
    SE->>DB: UPDATE step status=SKIPPED, finishedAt

    WS->>Step: shouldRun(ctx)  [SETUP_FSP_BOOSTERS → hasSection("boosters")]
    Step-->>WS: false
    WS->>SE: skip(jobId, SETUP_FSP_BOOSTERS)
    SE->>DB: UPDATE step status=SKIPPED, finishedAt
    Note over WS,DB: execute() never called for skipped steps — no external request
```
