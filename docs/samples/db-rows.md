# Database rows — sample state

The workflow persists two tables (H2 in the POC): one job row in
`organization_provision_job` and one row per catalog step in
`organization_provision_step`. Below is a completed job created with
`org_type = "STANDARD"` and `source = "DEFAULT"` (the unrestricted
source — see [`source_config.json`](../../src/main/resources/source_config.json)).
The `org_type` profile
([`default_config.json`](../../src/main/resources/default_config.json))
enables `branding`, `rfs_ui_prm_preferences`, `citations`, and
`license` — so three steps are **skipped**:
`ASSIGN_FSP_RECOMMENDATION_MODELS` (no `recommendation_models`),
`DISABLE_PRM_LICENSES` (`license` present → enable runs instead), and
`SETUP_FSP_BOOSTERS` (no `boosters`).

Job id: `8b1b2f2c-4a11-4e7a-9c6b-7a1c3b2a0e11`
`org_uid` (client-supplied, also the retry key): `3f2a9c14-7b41-4e2a-9c31-8a2f6d1eb7d2`

## `organization_provision_job`

| id | org_uid | external_job_uid | source | org_type | status | current_step | started_at | finished_at | service_user_account |
|----|---------|-------------------|--------|----------|--------|--------------|------------|-------------|----------------------|
| 8b1b2f2c-…-0e11 | 3f2a9c14-…-b7d2 | ext-job-482 | DEFAULT | STANDARD | SUCCESS | SETUP_FSP_BOOSTERS | 2026-07-07T10:15:02.100Z | 2026-07-07T10:15:04.480Z | sav20006@gmail.com |

`current_step` holds the last step the orchestrator pointed at.
`markCurrentStep` is not called for skipped steps, so on the *last*
step of a run it lands on whichever step actually executed last, not
necessarily the final entry in the catalog.

`org_type` and `source` both start `NULL` and are only set once
`INITIAL_REQUEST_VALIDATION` succeeds
(`JobStateWriter.markValidationResolved`) — a job that failed at
validation has `org_type = NULL`, `source = NULL`, and
`status = FAILED`. Once resolved, neither is overwritten by a later
retry (`ProvisionWorkflowService.findOrCreateJob` only refreshes
`service_user_account` / `external_job_uid`) — a job's step selection
can't desync mid-flight from a request that later changes org type or
source.

A job created with, say, `source = "ETL_JOB"` instead would have most
of the rows below `SKIPPED` — only `CREATE_ORG_IN_FSP` and
`ENABLE_PRM_LICENSES` are in that source's allow-list — see
[`GET-job-source-restricted.json`](GET-job-source-restricted.json).

## `organization_provision_step`

| id | job_id | step_order | step_name | status | started_at | finished_at | duration_ms | error_code | error_message |
|----|--------|-----------:|-----------|--------|------------|-------------|------------:|-----------|---------------|
| …-0000 | 8b1b2f2c-…-0e11 |   0 | INITIAL_REQUEST_VALIDATION              | SUCCESS     | 2026-07-07T10:15:02.080Z | 2026-07-07T10:15:02.100Z | 20  | | |
| …-0001 | 8b1b2f2c-…-0e11 |  10 | CREATE_ORG_IN_FSP                      | SUCCESS     | 2026-07-07T10:15:02.110Z | 2026-07-07T10:15:02.290Z | 180 | | |
| …-0002 | 8b1b2f2c-…-0e11 |  20 | SETUP_ORG_IN_FSP                       | SUCCESS     | 2026-07-07T10:15:02.300Z | 2026-07-07T10:15:02.480Z | 180 | | |
| …-0003 | 8b1b2f2c-…-0e11 |  30 | ASSIGN_FSP_RECOMMENDATION_MODELS       | **SKIPPED** | *(null)*                 | 2026-07-07T10:15:02.490Z | *(null)* | | |
| …-0004 | 8b1b2f2c-…-0e11 |  40 | SETUP_DEFAULT_BRANDING_PRM_PREFERENCES | SUCCESS     | 2026-07-07T10:15:02.500Z | 2026-07-07T10:15:02.690Z | 190 | | |
| …-0005 | 8b1b2f2c-…-0e11 |  50 | SETUP_DEFAULT_PRM_PREFERENCES          | SUCCESS     | 2026-07-07T10:15:02.700Z | 2026-07-07T10:15:02.870Z | 170 | | |
| …-0006 | 8b1b2f2c-…-0e11 |  60 | SETUP_DEFAULT_RFS_UI_PRM_PREFERENCES   | SUCCESS     | 2026-07-07T10:15:02.880Z | 2026-07-07T10:15:03.050Z | 170 | | |
| …-0007 | 8b1b2f2c-…-0e11 |  70 | SETUP_DEFAULT_VOCABULARIES_IN_CE       | SUCCESS     | 2026-07-07T10:15:03.060Z | 2026-07-07T10:15:03.260Z | 200 | | |
| …-0008 | 8b1b2f2c-…-0e11 |  80 | SETUP_DEFAULT_DATASOURCES_IN_FSP       | SUCCESS     | 2026-07-07T10:15:03.270Z | 2026-07-07T10:15:03.470Z | 200 | | |
| …-0009 | 8b1b2f2c-…-0e11 |  90 | SETUP_DEFAULT_CITATIONS                | SUCCESS     | 2026-07-07T10:15:03.480Z | 2026-07-07T10:15:03.660Z | 180 | | |
| …-0010 | 8b1b2f2c-…-0e11 | 100 | ENABLE_PRM_LICENSES                    | SUCCESS     | 2026-07-07T10:15:03.670Z | 2026-07-07T10:15:03.850Z | 180 | | |
| …-0011 | 8b1b2f2c-…-0e11 | 110 | DISABLE_PRM_LICENSES                   | **SKIPPED** | *(null)*                 | 2026-07-07T10:15:03.860Z | *(null)* | | |
| …-0012 | 8b1b2f2c-…-0e11 | 120 | SETUP_FSP_BOOSTERS                     | SUCCESS     | 2026-07-07T10:15:03.870Z | 2026-07-07T10:15:04.480Z | 610 | | |

A `SKIPPED` row has **no `started_at`** and **no `duration_ms`**
(the step never ran); only `finished_at` is stamped, marking when the
skip decision was recorded. `error_code` / `error_message` stay null —
a skip is not a failure.

Other tiers resolve differently: `INTERNAL` skips every optional section
(only the six core steps plus `DISABLE_PRM_LICENSES` run), while
`ENTERPRISE` runs everything except `DISABLE_PRM_LICENSES`.

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
