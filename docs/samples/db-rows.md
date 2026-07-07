# Database rows — sample state

The workflow persists two tables (H2 in the POC): one job row in
`organization_provision_job` and one row per catalog step in
`organization_provision_step`. Below is a completed job provisioned
with `prmLicensesEnabled = true` (the default): `ENABLE_PRM_LICENSES`
ran, `DISABLE_PRM_LICENSES` was **skipped** by business rule.

Job id: `8b1b2f2c-4a11-4e7a-9c6b-7a1c3b2a0e11`

## `organization_provision_job`

| id | organization_id | status | current_step | started_at | finished_at | created_by |
|----|-----------------|--------|--------------|------------|-------------|------------|
| 8b1b2f2c-…-0e11 | 3f2a9c14-…-b7d2 | SUCCESS | SETUP_FSP_BOOSTERS | 2026-07-07T10:15:02.100Z | 2026-07-07T10:15:04.480Z | sav20006@gmail.com |

`current_step` holds the last step the orchestrator pointed at
(`markCurrentStep` is not called for skipped steps, so it never lands
on `DISABLE_PRM_LICENSES`).

## `organization_provision_step`

| id | job_id | step_order | step_name | status | started_at | finished_at | duration_ms | error_code | error_message |
|----|--------|-----------:|-----------|--------|------------|-------------|------------:|-----------|---------------|
| …-0001 | 8b1b2f2c-…-0e11 |  10 | CREATE_ORG_IN_FSP                      | SUCCESS     | 2026-07-07T10:15:02.110Z | 2026-07-07T10:15:02.290Z | 180 | | |
| …-0002 | 8b1b2f2c-…-0e11 |  20 | SETUP_ORG_IN_FSP                       | SUCCESS     | 2026-07-07T10:15:02.300Z | 2026-07-07T10:15:02.480Z | 180 | | |
| …-0003 | 8b1b2f2c-…-0e11 |  30 | ASSIGN_FSP_RECOMMENDATION_MODELS       | SUCCESS     | 2026-07-07T10:15:02.490Z | 2026-07-07T10:15:02.700Z | 210 | | |
| …-0004 | 8b1b2f2c-…-0e11 |  40 | SETUP_DEFAULT_BRANDING_PRM_PREFERENCES | SUCCESS     | 2026-07-07T10:15:02.710Z | 2026-07-07T10:15:02.900Z | 190 | | |
| …-0005 | 8b1b2f2c-…-0e11 |  50 | SETUP_DEFAULT_PRM_PREFERENCES          | SUCCESS     | 2026-07-07T10:15:02.910Z | 2026-07-07T10:15:03.080Z | 170 | | |
| …-0006 | 8b1b2f2c-…-0e11 |  60 | SETUP_DEFAULT_RFS_UI_PRM_PREFERENCES   | SUCCESS     | 2026-07-07T10:15:03.090Z | 2026-07-07T10:15:03.260Z | 170 | | |
| …-0007 | 8b1b2f2c-…-0e11 |  70 | SETUP_DEFAULT_VOCABULARIES_IN_CE       | SUCCESS     | 2026-07-07T10:15:03.270Z | 2026-07-07T10:15:03.470Z | 200 | | |
| …-0008 | 8b1b2f2c-…-0e11 |  80 | SETUP_DEFAULT_DATASOURCES_IN_FSP       | SUCCESS     | 2026-07-07T10:15:03.480Z | 2026-07-07T10:15:03.680Z | 200 | | |
| …-0009 | 8b1b2f2c-…-0e11 |  90 | SETUP_DEFAULT_CITATIONS                | SUCCESS     | 2026-07-07T10:15:03.690Z | 2026-07-07T10:15:03.870Z | 180 | | |
| …-0010 | 8b1b2f2c-…-0e11 | 100 | ENABLE_PRM_LICENSES                    | SUCCESS     | 2026-07-07T10:15:03.880Z | 2026-07-07T10:15:04.060Z | 180 | | |
| …-0011 | 8b1b2f2c-…-0e11 | 110 | DISABLE_PRM_LICENSES                   | **SKIPPED** | *(null)*                 | 2026-07-07T10:15:04.070Z | *(null)* | | |
| …-0012 | 8b1b2f2c-…-0e11 | 120 | SETUP_FSP_BOOSTERS                     | SUCCESS     | 2026-07-07T10:15:04.080Z | 2026-07-07T10:15:04.470Z | 390 | | |

A `SKIPPED` row has **no `started_at`** and **no `duration_ms`**
(the step never ran); only `finished_at` is stamped, marking when the
skip decision was recorded. `error_code` / `error_message` stay null —
a skip is not a failure.

## Query it live (H2 console)

`spring.h2.console.enabled=true` exposes the console at `/h2-console`.

```sql
SELECT step_order, step_name, status, duration_ms
FROM   organization_provision_step
WHERE  job_id = '8b1b2f2c-4a11-4e7a-9c6b-7a1c3b2a0e11'
ORDER  BY step_order;

-- Count how each job resolved its steps
SELECT status, COUNT(*)
FROM   organization_provision_step
GROUP  BY status;
```
