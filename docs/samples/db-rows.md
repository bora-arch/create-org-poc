# Database rows — sample state

The workflow persists two tables (H2 in the POC): one job row in
`organization_provision_job` and one row per catalog step in
`organization_provision_step`. Below is a completed job created with
`org_type = "STANDARD"` and `source = "DEFAULT"` (the unrestricted
source — see [`source_config.json`](../../src/main/resources/source_config.json)).
`source` is the only thing that decides which steps get a row at all;
`DEFAULT`'s profile lists all 12 non-validation steps, so every one of
them gets a row here. `org_type` is validated and persisted onto the
job, but no step reads it — every seeded step runs unconditionally to
`SUCCESS` or `FAILED`, there is no per-step business gate.

Job id: `8b1b2f2c-4a11-4e7a-9c6b-7a1c3b2a0e11`
`org_uid` (client-supplied, also the retry key): `3f2a9c14-7b41-4e2a-9c31-8a2f6d1eb7d2`

## `organization_provision_job`

| id | org_uid | external_job_uid | source | org_type | status | current_step | started_at | finished_at | service_user_account |
|----|---------|-------------------|--------|----------|--------|--------------|------------|-------------|----------------------|
| 8b1b2f2c-…-0e11 | 3f2a9c14-…-b7d2 | ext-job-482 | DEFAULT | STANDARD | SUCCESS | SETUP_FSP_BOOSTERS | 2026-07-07T10:15:02.100Z | 2026-07-07T10:15:04.480Z | sav20006@gmail.com |

`current_step` holds the last step the orchestrator pointed at — on a
successful run that's the final entry in the catalog for this job's
source.

The job row's `org_type` and `source` **columns** both start `NULL`
and are only set once `INITIAL_REQUEST_VALIDATION` succeeds
(`JobStateWriter.markValidationResolved`) — a job that failed at
validation has `org_type = NULL`, `source = NULL`, and
`status = FAILED`, even though (as below) its `organization_provision_step`
rows were already seeded straight from the request's raw `source`
string at creation, before validation ever ran. Once the columns are
resolved, neither is overwritten by a later retry
(`ProvisionWorkflowService.findOrCreateJob` only refreshes
`service_user_account` / `external_job_uid`) — a job's step selection
can't desync mid-flight from a request that later changes org type or
source.

**`source = "DEFAULT"` is what makes the 13-row table below possible
at all** — `DEFAULT`'s profile in `source_config.json` lists all 12
non-validation steps, so every one of them gets a row.

A job created with `source = "ETL_JOB"` instead would **not** have 13
rows — it would have exactly **3** rows, period, because
`ensureStepsSeeded` (called from `createJob`, straight from the
request's raw `source` string, before `INITIAL_REQUEST_VALIDATION`
itself has run) only ever inserts a row for a step in the source's
allow-list (`CREATE_ORG_IN_FSP` and `ENABLE_PRM_LICENSES`, plus the
always-seeded `INITIAL_REQUEST_VALIDATION`):

| id | job_id | step_order | step_name | status |
|----|--------|-----------:|-----------|--------|
| …-0000 | (etl job id) |   0 | INITIAL_REQUEST_VALIDATION | SUCCESS |
| …-0001 | (etl job id) |  10 | CREATE_ORG_IN_FSP          | SUCCESS |
| …-0002 | (etl job id) | 100 | ENABLE_PRM_LICENSES        | SUCCESS |

No row for `step_order` 20, 30, 40, … ever exists for this job — not
`NOT_STARTED`, nothing. `GET`/`POST` responses for it report
`totalSteps: 3`, never `13`. See
[`GET-job-source-restricted.json`](GET-job-source-restricted.json).

## `organization_provision_step`

| id | job_id | step_order | step_name | status | started_at | finished_at | duration_ms | error_code | error_message |
|----|--------|-----------:|-----------|--------|------------|-------------|------------:|-----------|---------------|
| …-0000 | 8b1b2f2c-…-0e11 |   0 | INITIAL_REQUEST_VALIDATION              | SUCCESS | 2026-07-07T10:15:02.080Z | 2026-07-07T10:15:02.100Z | 20  | | |
| …-0001 | 8b1b2f2c-…-0e11 |  10 | CREATE_ORG_IN_FSP                      | SUCCESS | 2026-07-07T10:15:02.110Z | 2026-07-07T10:15:02.290Z | 180 | | |
| …-0002 | 8b1b2f2c-…-0e11 |  20 | SETUP_ORG_IN_FSP                       | SUCCESS | 2026-07-07T10:15:02.300Z | 2026-07-07T10:15:02.480Z | 180 | | |
| …-0003 | 8b1b2f2c-…-0e11 |  30 | ASSIGN_FSP_RECOMMENDATION_MODELS       | SUCCESS | 2026-07-07T10:15:02.490Z | 2026-07-07T10:15:02.670Z | 180 | | |
| …-0004 | 8b1b2f2c-…-0e11 |  40 | SETUP_DEFAULT_BRANDING_PRM_PREFERENCES | SUCCESS | 2026-07-07T10:15:02.680Z | 2026-07-07T10:15:02.870Z | 190 | | |
| …-0005 | 8b1b2f2c-…-0e11 |  50 | SETUP_DEFAULT_PRM_PREFERENCES          | SUCCESS | 2026-07-07T10:15:02.880Z | 2026-07-07T10:15:03.050Z | 170 | | |
| …-0006 | 8b1b2f2c-…-0e11 |  60 | SETUP_DEFAULT_RFS_UI_PRM_PREFERENCES   | SUCCESS | 2026-07-07T10:15:03.060Z | 2026-07-07T10:15:03.230Z | 170 | | |
| …-0007 | 8b1b2f2c-…-0e11 |  70 | SETUP_DEFAULT_VOCABULARIES_IN_CE       | SUCCESS | 2026-07-07T10:15:03.240Z | 2026-07-07T10:15:03.440Z | 200 | | |
| …-0008 | 8b1b2f2c-…-0e11 |  80 | SETUP_DEFAULT_DATASOURCES_IN_FSP       | SUCCESS | 2026-07-07T10:15:03.450Z | 2026-07-07T10:15:03.650Z | 200 | | |
| …-0009 | 8b1b2f2c-…-0e11 |  90 | SETUP_DEFAULT_CITATIONS                | SUCCESS | 2026-07-07T10:15:03.660Z | 2026-07-07T10:15:03.840Z | 180 | | |
| …-0010 | 8b1b2f2c-…-0e11 | 100 | ENABLE_PRM_LICENSES                    | SUCCESS | 2026-07-07T10:15:03.850Z | 2026-07-07T10:15:04.030Z | 180 | | |
| …-0011 | 8b1b2f2c-…-0e11 | 110 | DISABLE_PRM_LICENSES                   | SUCCESS | 2026-07-07T10:15:04.040Z | 2026-07-07T10:15:04.050Z | 10  | | |
| …-0012 | 8b1b2f2c-…-0e11 | 120 | SETUP_FSP_BOOSTERS                     | SUCCESS | 2026-07-07T10:15:04.060Z | 2026-07-07T10:15:04.480Z | 420 | | |

Every row seeded for a job runs to `SUCCESS` (or `FAILED`, on the row
that halted the job) — `ProvisionStep` has no per-step business
condition, so `ENABLE_PRM_LICENSES` and `DISABLE_PRM_LICENSES` both run
unconditionally here, same as every other step, regardless of
`org_type`. The `SKIPPED` status still exists in the `StepStatus`
enum/API contract for forward compatibility, but nothing in the
current codebase ever produces it.

## Retry / resume example

If `SETUP_DEFAULT_PRM_PREFERENCES` (order 50) had instead failed on the
first attempt, steps 60–120 would stay `NOT_STARTED` and the job row
would show `status = FAILED`. Resubmitting `POST /organizations` with
the same `org_uid` looks the job up by `org_uid`, computes
`firstPendingStepOrder` (→ 50), and resumes execution there — rows
0–40 (`INITIAL_REQUEST_VALIDATION` through
`SETUP_DEFAULT_BRANDING_PRM_PREFERENCES`) are never touched again; only
`step_order >= 50` is (re-)executed.

## Query it live (H2 console)

`spring.h2.console.enabled=true` exposes the console at `/h2-console`.

```sql
SELECT step_order, step_name, status, duration_ms
FROM   organization_provision_step
WHERE  job_id = '8b1b2f2c-4a11-4e7a-9c6b-7a1c3b2a0e11'
ORDER  BY step_order;

-- How did each job resolve its steps?
SELECT j.source, j.org_type, s.status, COUNT(*)
FROM   organization_provision_step s
JOIN   organization_provision_job  j ON j.id = s.job_id
GROUP  BY j.source, j.org_type, s.status
ORDER  BY j.source, j.org_type, s.status;

-- Look up a job by its retry key
SELECT * FROM organization_provision_job WHERE org_uid = '3f2a9c14-7b41-4e2a-9c31-8a2f6d1eb7d2';
```
