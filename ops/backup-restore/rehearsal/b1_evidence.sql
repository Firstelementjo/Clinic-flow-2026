-- =====================================================================
-- REHEARSAL ONLY -- B1 acceptance queries.
--
-- Executed against a DISPOSABLE database (firstelement_restore_check)
-- after ops/flyway/backfill/run-backfill.ps1 -Script B1. This file is NOT a
-- migration and must never be run against `firstelement`: on the live
-- database the ai_run_steps/ai_run_retrievals/ai_run_tool_calls
-- organizationId columns do not exist yet (the additive migration is still
-- `ops/flyway/pending/V202610040009__ai_run_child_tenant_scope.sql.todo`).
--
-- Expected on a completed B1 run:
--   B1_REMAINING_TOTAL        = 0   (nothing left to backfill)
--   B1_FILLED_TOTAL           = 8   (4 steps + 3 tool calls + 1 retrieval in
--                                     the 2026-10-04 rehearsal seed)
--   B1_CROSS_TENANT_*         = 0   (a child never disagrees with its parent run)
--   B1_REJECTIONS             = 0   (every child resolved a parent)
--   B1_RUN_STATUS             = COMPLETED
--   B1_ROWS_PROCESSED         = B1_FILLED_TOTAL
--
-- Rehearsal commands (2026-10-04):
--   Get-Content -Raw ops/backup-restore/rehearsal/b1_evidence.sql |
--     docker exec -i firstelement-postgres psql -U firstelement `
--       -d firstelement_restore_check -v ON_ERROR_STOP=1 -f -
-- =====================================================================
\pset pager off

select 'B1_CROSS_TENANT_STEPS' as marker, count(*)::text as value
  from "ai_run_steps" c join "ai_runs" r on r."id" = c."runId"
 where c."organizationId" is distinct from r."organizationId"
    or c."projectId"      is distinct from r."projectId"
union all
select 'B1_CROSS_TENANT_RETRIEVALS', count(*)::text
  from "ai_run_retrievals" c join "ai_runs" r on r."id" = c."runId"
 where c."organizationId" is distinct from r."organizationId"
    or c."projectId"      is distinct from r."projectId"
union all
select 'B1_CROSS_TENANT_TOOLCALLS', count(*)::text
  from "ai_run_tool_calls" c join "ai_runs" r on r."id" = c."runId"
 where c."organizationId" is distinct from r."organizationId"
    or c."projectId"      is distinct from r."projectId"
union all
select 'B1_REMAINING_TOTAL',
       ((select count(*) from "ai_run_steps"       where "organizationId" is null)
      + (select count(*) from "ai_run_retrievals" where "organizationId" is null)
      + (select count(*) from "ai_run_tool_calls"  where "organizationId" is null))::text
union all
select 'B1_FILLED_TOTAL',
       ((select count(*) from "ai_run_steps"       where "organizationId" is not null)
      + (select count(*) from "ai_run_retrievals" where "organizationId" is not null)
      + (select count(*) from "ai_run_tool_calls"  where "organizationId" is not null))::text
union all
select 'B1_REJECTIONS', count(*)::text from backfill_rejections
union all
select 'B1_RUN_STATUS', "status" from backfill_runs where "backfillName" = 'B1'
union all
select 'B1_ROWS_PROCESSED', "rowsProcessed"::text from backfill_runs where "backfillName" = 'B1'
union all
select 'B1_BATCHES_RUN', "batchesRun"::text from backfill_runs where "backfillName" = 'B1';