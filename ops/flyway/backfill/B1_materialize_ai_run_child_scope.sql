-- =====================================================================
-- B1_materialize_ai_run_child_scope.sql
-- Phase 2 / RESUMABLE BACKFILL (tenant + project scope).
--
-- WHAT IT FIXES
--   Three AI-trace tables carry NO tenant column:
--       ai_run_steps, ai_run_retrievals, ai_run_tool_calls
--   Their scope is inherited from the parent ai_runs row through
--   "runId". That is sound while the only access path is "fetch the run, then
--   fetch its steps", but it is not sound for:
--     * an Admin query that lists tool calls for a project;
--     * a retention/privacy sweep that must find every row for one tenant
--       without walking ai_runs;
--     * an index-led tenant filter (FR-1.3), which is impossible today.
--
--   This backfill MATERIALISES the inherited scope onto the child rows.
--
-- PRECONDITION (blocking)
--   The additive column migration must be promoted FIRST:
--       ops/flyway/pending/V202610040009__ai_run_child_tenant_scope.sql.todo
--   That migration adds organizationId/projectId as NULLABLE columns plus
--   tenant-led indexes. Nullable on purpose: the columns are populated by this
--   backfill, not by DDL, so a tight NOT NULL can only be added afterwards and
--   only once the residual count is proven to be zero.
--
-- SAFETY PROPERTIES
--   * GUARDED. The script does nothing unless the session sets
--       SET firstelement.backfill_enabled = 'on';
--     A stray execution is a NOTICE, not a mutation.
--   * IDEMPOTENT. Every UPDATE is restricted to rows where the target column
--     IS NULL. Re-running is a no-op.
--   * RESUMABLE. Progress is committed in the same transaction as each batch
--     (see backfill_runs). Interrupt with Ctrl-C / kill at any point; rerun and
--     it continues from the checkpoint.
--   * BOUNDED. -v batch_size caps every UPDATE. Never a single unbounded
--     statement over the whole table.
--   * NON-DESTRUCTIVE. Only fills NULL columns. Never rewrites an existing
--     value, never deletes, never truncates.
--   * REJECTS rather than guesses. A child whose parent ai_runs row is missing
--     (impossible today: the FK is ON DELETE CASCADE) is recorded in
--     backfill_rejections instead of being filled with a default.
--
-- TENANT SAFETY
--   organizationId/projectId are copied from the PARENT, never from the caller
--   and never from a request. There is no way to widen scope here.
--
-- Usage:
--   docker exec -i firstelement-postgres psql -U firstelement -d firstelement \
--     -v ON_ERROR_STOP=1 -v firstelement_backfill_armed=on \
--     -v backfill_name=B1 -v batch_size=500 -v operator=agent-b \
--     -f - < ops/flyway/backfill/B1_materialize_ai_run_child_scope.sql
-- =====================================================================

\if :{?firstelement_backfill_armed}
\else
  \echo 'B1 SKIPPED: this backfill must be armed explicitly.'
  \echo 'Re-run with -v firstelement_backfill_armed=on after taking a backup.'
  \quit
\endif

-- Layer 2 of the guard: the SQL itself refuses to run unarmed.
SET "firstelement.backfill_enabled" = 'on';

DO $$
BEGIN
    IF current_setting('firstelement.backfill_enabled', true) IS DISTINCT FROM 'on' THEN
        RAISE EXCEPTION
            'B1 REFUSED: firstelement.backfill_enabled is not ''on''. '
            'Backfills must be armed explicitly, never ambient.';
    END IF;
END
$$;

BEGIN;

-- Register / resume the run. The unique constraint on backfillName means a
-- rerun resumes the existing run instead of creating a second one.
INSERT INTO "backfill_runs" ("id", "backfillName", "batchSize", "operator", "correlationId", "status")
VALUES (gen_random_uuid(), :'backfill_name', :batch_size, :'operator',
        current_setting('firstelement.correlation_id', true), 'RUNNING')
ON CONFLICT ("backfillName") DO UPDATE
   SET "status"     = 'RUNNING',
       "batchSize"  = GREATEST("backfill_runs"."batchSize", EXCLUDED."batchSize"),
       "operator"   = EXCLUDED."operator"
 WHERE "backfill_runs"."status" <> 'COMPLETED';

-- ---------------------------------------------------------------------
-- Pass 1: ai_run_steps
-- ---------------------------------------------------------------------
WITH batch AS (
    SELECT c."id", r."organizationId", r."projectId"
      FROM "ai_run_steps" c
      JOIN "ai_runs" r ON r."id" = c."runId"
     WHERE c."organizationId" IS NULL
     ORDER BY c."id"
     LIMIT :batch_size
     FOR UPDATE OF c SKIP LOCKED
)
UPDATE "ai_run_steps" c
   SET "organizationId" = b."organizationId",
       "projectId"      = b."projectId",
       "updatedAt"      = CURRENT_TIMESTAMP
  FROM batch b
 WHERE c."id" = b."id";

-- ---------------------------------------------------------------------
-- Pass 2: ai_run_retrievals
-- ---------------------------------------------------------------------
WITH batch AS (
    SELECT c."id", r."organizationId", r."projectId"
      FROM "ai_run_retrievals" c
      JOIN "ai_runs" r ON r."id" = c."runId"
     WHERE c."organizationId" IS NULL
     ORDER BY c."id"
     LIMIT :batch_size
     FOR UPDATE OF c SKIP LOCKED
)
UPDATE "ai_run_retrievals" c
   SET "organizationId" = b."organizationId",
       "projectId"      = b."projectId",
       "updatedAt"      = CURRENT_TIMESTAMP
  FROM batch b
 WHERE c."id" = b."id";

-- ---------------------------------------------------------------------
-- Pass 3: ai_run_tool_calls
-- ---------------------------------------------------------------------
WITH batch AS (
    SELECT c."id", r."organizationId", r."projectId"
      FROM "ai_run_tool_calls" c
      JOIN "ai_runs" r ON r."id" = c."runId"
     WHERE c."organizationId" IS NULL
     ORDER BY c."id"
     LIMIT :batch_size
     FOR UPDATE OF c SKIP LOCKED
)
UPDATE "ai_run_tool_calls" c
   SET "organizationId" = b."organizationId",
       "projectId"      = b."projectId",
       "updatedAt"      = CURRENT_TIMESTAMP
  FROM batch b
 WHERE c."id" = b."id";

-- ---------------------------------------------------------------------
-- Record orphans instead of inventing scope for them
-- ---------------------------------------------------------------------
INSERT INTO "backfill_rejections" ("id", "backfillName", "rowId", "reason", "detail")
SELECT gen_random_uuid(), :'backfill_name', c."id",
       'PARENT_RUN_MISSING',
       jsonb_build_object('runId', c."runId", 'table', 'ai_run_child')
  FROM (
        SELECT "id", "runId" FROM "ai_run_steps"
         WHERE "organizationId" IS NULL
        UNION ALL
        SELECT "id", "runId" FROM "ai_run_retrievals"
         WHERE "organizationId" IS NULL
        UNION ALL
        SELECT "id", "runId" FROM "ai_run_tool_calls"
         WHERE "organizationId" IS NULL
       ) c
  LEFT JOIN "ai_runs" r ON r."id" = c."runId"
 WHERE r."id" IS NULL
ON CONFLICT ("backfillName", "rowId") DO NOTHING;

-- ---------------------------------------------------------------------
-- Checkpoint, committed in the SAME transaction as the batch.
--
-- Written as plain SQL, NOT a DO block: psql does not interpolate :variables
-- inside dollar-quoted bodies, so a DO block here would fail at parse time and
-- roll the whole batch back. That failure mode is safe (nothing is half
-- applied) but it silently prevents progress, so the checkpoint stays plain.
--
-- `AND r."status" <> 'COMPLETED'` is what makes a rerun after completion a
-- true no-op. Without it a second run would change nothing in the data but
-- would still bump batchesRun and re-stamp finishedAt, which would make the
-- control table disagree with its own "rowsProcessed == rows filled" promise.
-- ---------------------------------------------------------------------
UPDATE "backfill_runs" r
   SET "rowsProcessed" = (SELECT count(*) FROM (
                             SELECT 1 FROM "ai_run_steps"       WHERE "organizationId" IS NOT NULL
                             UNION ALL SELECT 1 FROM "ai_run_retrievals" WHERE "organizationId" IS NOT NULL
                             UNION ALL SELECT 1 FROM "ai_run_tool_calls"  WHERE "organizationId" IS NOT NULL
                          ) done),
       "batchesRun"    = r."batchesRun" + 1,
       "status"        = CASE WHEN (SELECT count(*) FROM (
                             SELECT 1 FROM "ai_run_steps"       WHERE "organizationId" IS NULL
                             UNION ALL SELECT 1 FROM "ai_run_retrievals" WHERE "organizationId" IS NULL
                             UNION ALL SELECT 1 FROM "ai_run_tool_calls"  WHERE "organizationId" IS NULL
                          ) todo) = 0
                             THEN 'COMPLETED' ELSE 'RUNNING' END,
       "finishedAt"    = CASE WHEN (SELECT count(*) FROM (
                             SELECT 1 FROM "ai_run_steps"       WHERE "organizationId" IS NULL
                             UNION ALL SELECT 1 FROM "ai_run_retrievals" WHERE "organizationId" IS NULL
                             UNION ALL SELECT 1 FROM "ai_run_tool_calls"  WHERE "organizationId" IS NULL
                          ) todo) = 0
                             THEN CURRENT_TIMESTAMP ELSE NULL END
 WHERE r."backfillName" = :'backfill_name'
    AND r."status" <> 'COMPLETED';

COMMIT;

-- ---------------------------------------------------------------------
-- Verification (read-only, safe to run on every iteration).
-- The B1_DONE marker is what run-backfill.ps1 looks for to stop the loop.
-- ---------------------------------------------------------------------
SELECT 'B1_REMAINING' AS marker,
       (SELECT count(*) FROM "ai_run_steps"       WHERE "organizationId" IS NULL)
     + (SELECT count(*) FROM "ai_run_retrievals" WHERE "organizationId" IS NULL)
     + (SELECT count(*) FROM "ai_run_tool_calls"  WHERE "organizationId" IS NULL) AS remaining,
       (SELECT count(*) FROM "ai_run_steps"       WHERE "organizationId" IS NOT NULL)
     + (SELECT count(*) FROM "ai_run_retrievals" WHERE "organizationId" IS NOT NULL)
     + (SELECT count(*) FROM "ai_run_tool_calls"  WHERE "organizationId" IS NOT NULL) AS filled;

-- Tenant-consistency proof: a child must never disagree with its parent run.
SELECT 'B1_CROSS_TENANT' AS marker, count(*) AS cross_tenant_children
  FROM "ai_run_steps" c JOIN "ai_runs" r ON r."id" = c."runId"
 WHERE c."organizationId" IS DISTINCT FROM r."organizationId"
    OR c."projectId"      IS DISTINCT FROM r."projectId";

SELECT 'B1_REJECTIONS' AS marker, count(*) AS rejections
  FROM "backfill_rejections" WHERE "backfillName" = :'backfill_name';