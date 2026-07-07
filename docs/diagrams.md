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
            step_iface["ProvisionStep<br/>ProvisionContext"]
        end
        subgraph engine["workflow.engine"]
            orch["ProvisionWorkflowService<br/>ProvisionWorkflowAsyncRunner<br/>StepExecutor<br/>StepRegistry<br/>StepFailureTranslator<br/>JobStateWriter"]
        end
        subgraph steps["workflow.steps"]
            step_beans["12 concrete steps"]
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
    }

    class ProvisionContext {
        -jobId : UUID
        -organizationName : String
        -organizationId : UUID
        -attributes : Map
        +get(key, type) Optional
        +put(key, value) void
    }

    class ProvisionWorkflowService {
        +createJob(name, createdBy) Job
        +execute(jobId, name, createdBy) void
    }

    class ProvisionWorkflowAsyncRunner {
        +run(jobId, name, createdBy) void
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
        +markOrganizationId(jobId, orgId) void
        +markFinished(jobId, status) void
    }

    class ProvisionJobQueryService {
        +fetch(jobId) ProvisionJobResponse
    }

    class ExternalOrganizationClient {
        <<interface>>
    }

    class OrganizationProvisionJob {
        id, organizationId, status,
        currentStep, startedAt, finishedAt, createdBy
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
    StepRegistry o--> "*" ProvisionStep
    StepExecutor --> StepFailureTranslator
    StepExecutor --> OrganizationProvisionStep
    JobStateWriter --> OrganizationProvisionJob
    ProvisionJobQueryService --> OrganizationProvisionJob
    ProvisionJobQueryService --> OrganizationProvisionStep
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

    Client->>OC: POST /organizations {name, createdBy}
    OC->>WS: createJob(name, createdBy)
    WS->>DB: INSERT job (PENDING)
    WS->>DB: INSERT 12 step rows (NOT_STARTED)
    WS-->>OC: job (id)
    OC->>AR: run(jobId, ...)
    OC-->>Client: 202 { jobId, status: IN_PROGRESS }

    Note over AR,DB: async — provisioning-N thread pool

    AR->>WS: execute(jobId, ...)
    WS->>DB: UPDATE job status=IN_PROGRESS
    loop each step in registry.ordered()
        WS->>DB: UPDATE job currentStep=<name>
        WS->>SE: execute(jobId, step, ctx)
        SE->>DB: UPDATE step status=IN_PROGRESS, startedAt
        SE->>Step: execute(ctx)
        Step->>Ext: <method>()
        Ext-->>Step: result
        Step-->>SE: return
        SE->>DB: UPDATE step status=SUCCESS, finishedAt, duration
    end
    WS->>DB: UPDATE job status=SUCCESS, finishedAt

    Client->>OC: GET /organization-provision-jobs/{jobId}
    OC-->>Client: 200 { status: SUCCESS, progress: 12, steps: [...] }
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
    SE--x WS: StepExecutionException(ASSIGN_FSP_RECOMMENDATION_MODELS)
    WS->>DB: UPDATE job status=FAILED, finishedAt
    Note over DB: SETUP_DEFAULT_BRANDING_PRM_PREFERENCES..SETUP_FSP_BOOSTERS remain NOT_STARTED (pre-seeded)
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
    NOT_STARTED --> IN_PROGRESS : StepExecutor.execute
    IN_PROGRESS --> SUCCESS : step returns
    IN_PROGRESS --> FAILED : step throws
    NOT_STARTED --> SKIPPED : (future — conditional steps)
    SUCCESS --> [*]
    FAILED --> [*]
    SKIPPED --> [*]
```
