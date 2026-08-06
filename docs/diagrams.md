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
            orch["ProvisionWorkflowService<br/>ProvisionWorkflowAsyncRunner<br/>StepExecutor<br/>StepRegistry<br/>StepFailureTranslator<br/>JobStateWriter<br/>SourceStepConfigProvider"]
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
    }

    class ProvisionContext {
        -jobId : UUID
        -rawOrgUid : String
        -rawOrgType : String
        -rawSource : String
        -serviceUserAccount : String
        -externalJobUid : String
        -organizationId : UUID
        -orgType : OrgType
        -attributes : Map
        +markValidated(orgId, orgType) void
        +get(key, type) Optional
        +put(key, value) void
    }

    class SourceStepConfigProvider {
        +isKnownSource(source) boolean
        +stepsFor(source) Set~StepName~
        +knownSources() Set~String~
    }

    class ProvisionWorkflowService {
        +findOrCreateJob(orgUid, rawSource, serviceUserAccount, externalJobUid) Job
        +firstPendingStepOrder(jobId) int
        +markResuming(jobId) void
        +execute(jobId, rawOrgUid, rawOrgType, rawSource, serviceUserAccount, externalJobUid, resumeFromOrder) void
        -createJob(orgUid, rawSource, serviceUserAccount, externalJobUid) Job
        -ensureStepsSeeded(jobId, rawSource) void
    }

    class ProvisionWorkflowAsyncRunner {
        +run(jobId, rawOrgUid, rawOrgType, rawSource, serviceUserAccount, externalJobUid, resumeFromOrder) void
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
        +markValidationResolved(jobId, orgType, source) void
        +markFinished(jobId, status) void
    }

    class ProvisionJobQueryService {
        +fetch(jobId) ProvisionJobResponse
    }

    class ExternalOrganizationClient {
        <<interface>>
    }

    class OrganizationProvisionJob {
        id, orgUid, externalJobUid, source, orgType, status,
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
    ProvisionWorkflowService --> SourceStepConfigProvider
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

    Client->>OC: POST /organizations {org_uid, org_type, source, service_user_account, external_job_uid}
    OC->>WS: findOrCreateJob(orgUid, rawSource, serviceUserAccount, externalJobUid)
    WS->>DB: INSERT job (PENDING)
    WS->>WS: enabledSteps = SourceStepConfigProvider.stepsFor(rawSource)<br/>= all 12 non-validation steps (source="DEFAULT")
    WS->>DB: INSERT 13 step rows: INITIAL_REQUEST_VALIDATION + enabledSteps (all NOT_STARTED)
    Note over WS,DB: ensureStepsSeeded, called from createJob — resolved straight from<br/>the raw request's source string, before any step (incl. validation) has run
    WS-->>OC: job (id, status=PENDING)
    OC->>WS: firstPendingStepOrder(jobId)
    WS-->>OC: 0 (INITIAL_REQUEST_VALIDATION)
    OC->>WS: markResuming(jobId)
    WS->>DB: UPDATE job status=IN_PROGRESS
    OC->>AR: run(jobId, orgUid, rawOrgType, rawSource, serviceUserAccount, externalJobUid, resumeFromOrder=0)
    OC-->>Client: 202 { jobId, orgUid, externalJobUid, source, status: IN_PROGRESS, progress: 0, totalSteps: 13 }
    Note over OC,Client: every row is NOT_STARTED at this point — nothing has executed yet,<br/>not even INITIAL_REQUEST_VALIDATION; it all runs asynchronously below

    Note over AR,DB: async — provisioning-N thread pool

    AR->>WS: execute(jobId, rawOrgUid, rawOrgType, rawSource, ..., resumeFromOrder=0)
    WS->>DB: SELECT job (orgType)
    Note over WS: job.orgType is null (first run) — context is NOT pre-populated;<br/>INITIAL_REQUEST_VALIDATION will populate it when its turn comes
    WS->>DB: SELECT step rows for jobId → seededSteps = {INITIAL_REQUEST_VALIDATION, ...12 more}
    WS->>DB: UPDATE job status=IN_PROGRESS
    loop each step in registry.ordered() with order >= resumeFromOrder
        alt step.name() not in seededSteps
            WS->>WS: continue — no row exists for this step, nothing to update
        else seeded for this job
            WS->>DB: UPDATE job currentStep=<name>
            WS->>SE: execute(jobId, step, ctx)
            SE->>DB: UPDATE step status=IN_PROGRESS, startedAt
            SE->>Step: execute(ctx)
            Step->>Ext: <method>()
            Ext-->>Step: result
            Step-->>SE: return
            SE->>DB: UPDATE step status=SUCCESS, finishedAt, duration
            opt step is INITIAL_REQUEST_VALIDATION
                WS->>DB: UPDATE job orgType=STANDARD (markValidationResolved)
            end
        end
    end
    WS->>DB: UPDATE job status=SUCCESS, finishedAt

    Client->>OC: GET /organization-provision-jobs/{jobId}
    OC-->>Client: 200 { status: SUCCESS, progress: 13, totalSteps: 13, steps: [...13 entries...] }
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
    Note over DB: SETUP_DEFAULT_BRANDING_PRM_PREFERENCES..SETUP_FSP_BOOSTERS remain NOT_STARTED (pre-seeded) —<br/>this assumes source=DEFAULT (all steps seeded); a restricted source simply has fewer rows to leave NOT_STARTED
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
    OC->>WS: findOrCreateJob(orgUid, rawSource, ...)
    WS->>DB: SELECT job WHERE org_uid = ?
    DB-->>WS: existing job, status=FAILED
    Note over WS: job already has all 13 step rows from its first attempt — nothing re-seeded here
    WS-->>OC: job (existing)
    OC->>WS: firstPendingStepOrder(jobId)
    WS->>DB: SELECT steps WHERE job_id = ? ORDER BY step_order
    Note over WS: first NOT_STARTED/FAILED = ASSIGN_FSP_RECOMMENDATION_MODELS (order 30)
    WS-->>OC: 30
    OC->>WS: markResuming(jobId)
    WS->>DB: UPDATE job status=IN_PROGRESS
    OC->>AR: run(jobId, ..., resumeFromOrder=30)
    OC-->>Client: 202 { status: IN_PROGRESS, progress: 2, ... }

    Note over AR,DB: async
    AR->>WS: execute(jobId, ..., resumeFromOrder=30)
    Note over WS: job.orgType already set (validation succeeded on the first attempt) —<br/>context is pre-populated from the persisted job row, not re-parsed from this request
    Note over WS: steps with order < 30 (INITIAL_REQUEST_VALIDATION, CREATE_ORG_IN_FSP, SETUP_ORG_IN_FSP) are skipped entirely — not re-executed
    WS->>DB: retry ASSIGN_FSP_RECOMMENDATION_MODELS, then continue through SETUP_FSP_BOOSTERS
    WS->>DB: UPDATE job status=SUCCESS
```

Note the asymmetry: `findOrCreateJob` (step 2 above) refreshes only
`serviceUserAccount`/`externalJobUid` on the existing row — `orgType`
and `source` are deliberately left untouched here even if this retry's
request body specifies different values for them. They can only be
corrected while the job is still stuck at `INITIAL_REQUEST_VALIDATION`
(i.e. validation never actually succeeded in a prior attempt — see
[Sequence — source restricts which steps are even seeded](#sequence--source-restricts-which-steps-are-even-seeded)
for what happens to step rows in that case); once validation has
succeeded, `execute` always reconstructs `orgType` from what was
persisted, so a job's step selection can never desync mid-flight from
a later request that tries to change it.

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
    SUCCESS --> [*]
    FAILED --> [*]
```

A step is pre-seeded `NOT_STARTED` **only if `source` selected it** —
see the seeding sequence below. Steps outside that set never enter
this state machine at all; there's no row, so there's no status to
report. For a seeded step, when the orchestrator reaches it, it always
executes (`IN_PROGRESS` → `SUCCESS`/`FAILED`) — `ProvisionStep` has no
per-step business gate, so `source` selecting a step is the only
condition that determines whether it runs.

## Sequence — source restricts which steps are even seeded

The request's `source` selects the fixed set of steps that caller may
trigger at all
([`source_config.json`](../src/main/resources/source_config.json)) —
and it does so at **seed time**, straight from the request's raw,
not-yet-validated `source` string, right at job creation — before
`INITIAL_REQUEST_VALIDATION` itself has even run — not by marking
excluded steps some other status during execution. Shown for
`source = "ETL_JOB"`, whose profile only lists `CREATE_ORG_IN_FSP` and
`ENABLE_PRM_LICENSES` — every other step never gets a row at all, so
it can never appear in a `GET`/`POST` response for this job, under any
status. The two steps that *are* seeded keep their normal catalog
order relative to each other (`ENABLE_PRM_LICENSES` still runs after
`CREATE_ORG_IN_FSP`, despite everything between them in the catalog
never existing for this job).

```mermaid
sequenceDiagram
    autonumber
    participant WS as ProvisionWorkflowService
    participant SRC as SourceStepConfigProvider
    participant DB as H2
    participant SE as StepExecutor

    Note over WS: createJob — right when the job row is first inserted,<br/>using this attempt's raw source string, before any step has run
    WS->>SRC: stepsFor("ETL_JOB")
    SRC-->>WS: {CREATE_ORG_IN_FSP, ENABLE_PRM_LICENSES}
    WS->>WS: ensureStepsSeeded(jobId, "ETL_JOB")
    loop registry.ordered()
        alt step.name() == INITIAL_REQUEST_VALIDATION or in enabledSteps
            WS->>DB: INSERT step row (NOT_STARTED) if not already present
        else not selected by source
            WS->>WS: skip — no row ever created for this step
        end
    end
    Note over DB: only 3 rows exist for this job, ever:<br/>INITIAL_REQUEST_VALIDATION, CREATE_ORG_IN_FSP, ENABLE_PRM_LICENSES

    Note over WS,SE: later, during execute() (fully async, including INITIAL_REQUEST_VALIDATION itself)<br/>— only seeded rows are ever touched
    WS->>SE: execute(jobId, INITIAL_REQUEST_VALIDATION, ctx)
    SE->>DB: UPDATE step status=IN_PROGRESS → SUCCESS
    WS->>SE: execute(jobId, CREATE_ORG_IN_FSP, ctx)
    SE->>DB: UPDATE step status=IN_PROGRESS → SUCCESS
    Note over WS: SETUP_ORG_IN_FSP, ASSIGN_FSP_RECOMMENDATION_MODELS, SETUP_DEFAULT_*, DISABLE_PRM_LICENSES,<br/>SETUP_FSP_BOOSTERS — never touched, no row exists, loop just continues past them
    WS->>SE: execute(jobId, ENABLE_PRM_LICENSES, ctx)
    SE->>DB: UPDATE step status=IN_PROGRESS → SUCCESS

    Note over WS,DB: GET /organization-provision-jobs/{jobId} → steps: [INITIAL_REQUEST_VALIDATION, CREATE_ORG_IN_FSP, ENABLE_PRM_LICENSES], totalSteps: 3
```

If `"ETL_JOB"` had instead been an unrecognized string, `stepsFor`
would return an empty set and only `INITIAL_REQUEST_VALIDATION` would
be seeded. A later retry with a corrected, recognized `source` tops up
the missing rows (`ensureStepsSeeded` is idempotent and re-runs on
every retry until validation actually succeeds) before execution
resumes — see the note on `findOrCreateJob` above.
