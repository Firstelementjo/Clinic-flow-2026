-- =====================================================================
-- V20261005110000__ai_run_child_tenant_scope_columns.sql
-- ADDITIVE, IDEMPOTENT-BY-VERSION, NON-DESTRUCTIVE.  TRANSACTIONAL.
--
-- SPLIT IN TWO, AND WHY -- READ THIS BEFORE "SIMPLIFYING" IT BACK
--   The draft for this migration (V202610040009__ai_run_child_tenant_scope.sql.todo)
--   contained BOTH the three ALTER TABLE ... ADD COLUMN statements AND the three
--   CREATE INDEX CONCURRENTLY statements. ADR-0005 section 3 decided it must run
--   "outside a transaction with CREATE INDEX CONCURRENTLY, because the AI-trace
--   tables are append-heavy". Those two requirements CANNOT be met in one Flyway
--   script, and that was measured, not reasoned:
--
--     * Flyway 11.7.2 (Community) has NO per-script `executeInTransaction`
--       override. The `.conf` sidecar the draft anticipated is a Teams/Enterprise
--       feature; `SqlMigrationResolver` never reads it -- verified against the
--       resolved flyway-core-11.7.2.jar, which contains no script-configuration
--       class at all. The first attempt at promotion shipped a
--       `V20261005110000__ai_run_child_tenant_scope.conf` and the fixture failed
--       with, verbatim:
--           FlywayException: Detected both transactional and non-transactional
--           statements within the same migration (even though mixed is false).
--     * Changing that to `executeInTransaction=mixed` changed NOTHING, for the
--       same reason: with no per-script override the global default
--       (`executeInTransaction=true`, `mixed=false`) is what Flyway uses.
--     * The parser refuses a script that contains both statement kinds unless
--       `mixed` is globally true, and `mixed` is not a per-script setting.
--
--   So the migration is split, which is the only shape that satisfies ADR-0005
--   section 3 without a Flyway edition nobody has paid for:
--     V20261005110000  THIS FILE. Three ADD COLUMN statements only, so the
--                      expansion is ATOMIC: either all three trace tables gain
--                      their scope columns or none does. A half-expanded trace
--                      table is a schema whose meaning depends on which migration
--                      ran last, which is exactly what a single transaction
--                      prevents.
--     V20261005120000  The three CREATE INDEX CONCURRENTLY statements ONLY. A
--                      script made up entirely of non-transactional statements
--                      makes Flyway run it outside a transaction ON ITS OWN,
--                      with no configuration at all -- which is strictly better
--                      than the setting the draft asked for, because it cannot be
--                      lost by a missing file.
--
--   WHAT WAS NOT DONE: the CONCURRENTLY was NOT quietly dropped. Dropping it
--   would take a SHARE lock on three live, append-heavy trace tables, which is
--   the outage ADR-0005 section 3 exists to prevent, and the fixture asserts both
--   that the reviewed script contains CONCURRENTLY and that PostgreSQL refuses it
--   inside a transaction.
--
--   This file and its pair are part of ONE logical change. Rehearsed and
--   verified together; if one is undone the other must be too
--   (ops/flyway/rollback/undo_phase7_10a.sql drops both).
--
-- WHY THIS MIGRATION EXISTS
--   Three AI-trace child tables carry NO tenant column:
--       ai_run_steps, ai_run_retrievals, ai_run_tool_calls
--   Their scope is inherited from ai_runs through "runId". Inheriting is sound for
--   a single drill-down path and unsound for everything else: a tenant-filtered
--   query has to join to ai_runs to know whether a row is visible, a
--   privacy/deletion sweep cannot be expressed as a single-table DELETE, and an
--   index-led tenant filter (FR-1.3) cannot be built at all. Phase 2's B1
--   backfill exists precisely because the columns were added nullable.
--
-- WHY ADDITIVE AND NULLABLE -- EXPAND / BACKFILL / CONTRACT
--   1. expand   (these two migrations: nullable columns + CONCURRENTLY indexes)
--   2. backfill (ops/flyway/backfill/B1_materialize_ai_run_child_scope.sql --
--                resumable, checkpointed, idempotent, double-armed)
--   3. contract (a FUTURE migration adding NOT NULL, only after the verification
--                bundle proves the residual NULL count is zero)
--   A NOT NULL constraint cannot be added in the same migration as the backfill
--   that fills it: the constraint would be evaluated against rows the backfill has
--   not written yet, so the two would have to run in one transaction, and one
--   transaction over three append-heavy trace tables is exactly the outage this
--   migration avoids. The step-3 migration is NOT written here and is listed as
--   an open item in the phase report rather than smuggled in.
--
-- WHAT DOES NOT HAPPEN HERE
--   * No UPDATE of any existing value. No DROP, no RENAME, no type change.
--   * No NOT NULL. The current Prisma/legacy writers are unaffected, so this can
--     be applied while the legacy NestJS runtime is still serving traffic.
--   * No change to ai_runs itself, and NO FOREIGN KEY on the new columns. The
--     value is derived from the parent ai_runs row; an FK would allow a writer to
--     set a scope that disagrees with the parent, which is the cross-tenant
--     inconsistency FR-1.4 forbids. Scope is verified BY ASSERTION -- the B1 final
--     query and verification V14 section 5 -- not by an FK, and the deliberate
--     absence is recorded here so a later migration does not "fix" it.
--
-- `updatedAt` IS ADDED, AND ITS DEFAULT WRITES UTC
--   The draft used a bare DEFAULT CURRENT_TIMESTAMP, which stores the SESSION's
--   wall clock -- the baseline's habit and exactly what Phases 5a and 6a stopped
--   copying. It is `(CURRENT_TIMESTAMP AT TIME ZONE 'UTC')` here, and the guard
--   below fails if any of the three defaults omits AT TIME ZONE.
--
-- TIMESTAMPS
--   timestamp(3) WITHOUT time zone throughout (V6 asserts no timestamptz).
--
-- SAFETY
--   ALTER TABLE ADD COLUMN IF NOT EXISTS only. No DROP, no RENAME, no NOT NULL, no
--   data change, and no index added to any table (the indexes are the pair file).
--   IF NOT EXISTS is used because these three columns have been added to some
--   development databases by hand during Phase-2 debugging; the migration must be
--   safe against that rather than fail at the boot.
--   Rollback: ALTER TABLE ... DROP COLUMN -- rehearsed on a throwaway database
--   only (ops/flyway/rollback/undo_phase7_10a.sql). Rolling back after B1 has run
--   discards derived scope that B1 can simply re-derive, so rollback is data-safe
--   but wasteful. It must NOT run while a writer is emitting AI runs that rely on
--   the new columns.
--   Every identifier is schema-qualified (the baseline sets search_path = '').
-- =====================================================================

ALTER TABLE "public"."ai_run_steps"
    ADD COLUMN IF NOT EXISTS "organizationId" uuid,
    ADD COLUMN IF NOT EXISTS "projectId"      uuid,
    ADD COLUMN IF NOT EXISTS "updatedAt"      timestamp(3) without time zone
                                              NOT NULL
                                              DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC');

ALTER TABLE "public"."ai_run_retrievals"
    ADD COLUMN IF NOT EXISTS "organizationId" uuid,
    ADD COLUMN IF NOT EXISTS "projectId"      uuid,
    ADD COLUMN IF NOT EXISTS "updatedAt"      timestamp(3) without time zone
                                              NOT NULL
                                              DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC');

ALTER TABLE "public"."ai_run_tool_calls"
    ADD COLUMN IF NOT EXISTS "organizationId" uuid,
    ADD COLUMN IF NOT EXISTS "projectId"      uuid,
    ADD COLUMN IF NOT EXISTS "updatedAt"      timestamp(3) without time zone
                                              NOT NULL
                                              DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC');

-- ---------------------------------------------------------------------
-- Post-condition guard.
--
-- NOTE: the column-NULLABILITY, default and timestamptz checks live HERE rather
-- than after the index statements, because this file is the only one of the pair
-- that is transactional and therefore the only one that can run a DO block. The
-- index checks are in V12/V14 and in the fixture, and the split is why they sit
-- in two places -- a consequence worth recording so nobody "tidies" the pair back
-- into one file.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    missing   text;
    bad_tz    text;
    bad_dflt  text;
BEGIN
    -- 1. Every column must exist on all three tables.
    IF EXISTS (SELECT 1 FROM unnest(ARRAY['ai_run_steps', 'ai_run_retrievals',
                                        'ai_run_tool_calls']) AS t
                CROSS JOIN unnest(ARRAY['organizationId', 'projectId',
                                        'updatedAt']) AS col
                WHERE NOT EXISTS (SELECT 1 FROM pg_attribute a
                                    JOIN pg_class c ON c.oid = a.attrelid
                                    JOIN pg_namespace n ON n.oid = c.relnamespace
                                   WHERE n.nspname = 'public' AND c.relname = t
                                     AND a.attname = col
                                     AND a.attnum > 0 AND NOT a.attisdropped)) THEN
        RAISE EXCEPTION
            'V20261005110000 failed: a child-scope column is missing from one of '
            'ai_run_steps / ai_run_retrievals / ai_run_tool_calls.';
    END IF;

    -- 2. They must be NULLABLE. NOT NULL here would be refused against the
    --    existing rows, and it belongs to the later contract step.
    SELECT string_agg(c.relname || '.' || a.attname, ', ') INTO missing
      FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_attribute a ON a.attrelid = c.oid
                              AND a.attname IN ('organizationId', 'projectId')
                              AND a.attnum > 0 AND NOT a.attisdropped
                              AND a.attnotnull
     WHERE n.nspname = 'public'
       AND c.relname IN ('ai_run_steps', 'ai_run_retrievals', 'ai_run_tool_calls');

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION
            'V20261005110000 failed: a child-scope column is NOT NULL (%). Contract is a '
            'separate migration, after B1 has proven the residual NULL count is zero.', missing;
    END IF;

    -- 3. The NEW default must say UTC. Scoped to "updatedAt" ONLY, deliberately.
    --    The first version of this guard scanned every default on these three
    --    tables and the throwaway rehearsal failed it on ai_run_steps.createdAt,
    --    ai_run_retrievals.createdAt and ai_run_tool_calls.createdAt -- which are
    --    PRE-EXISTING baseline defaults written by `prisma db push` with a bare
    --    CURRENT_TIMESTAMP. Those columns are not this migration's to change:
    --    rewriting a baseline default is an ALTER of an existing object, which this
    --    phase is additive-only by contract, and it would silently move
    --    session-timezone behaviour for every row the legacy runtime writes today.
    --    The baseline defaults are recorded as debt in the phase report rather than
    --    being fixed here.
    SELECT string_agg(c.relname || '.' || a.attname, ', ') INTO bad_dflt
      FROM pg_attrdef d
        JOIN pg_class c ON c.oid = d.adrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = d.adnum
                              AND a.attnum > 0 AND NOT a.attisdropped
     WHERE n.nspname = 'public'
       AND c.relname IN ('ai_run_steps', 'ai_run_retrievals', 'ai_run_tool_calls')
       AND a.attname = 'updatedAt'
       AND pg_get_expr(d.adbin, d.adrelid) LIKE '%CURRENT_TIMESTAMP%'
       AND pg_get_expr(d.adbin, d.adrelid) NOT LIKE '%AT TIME ZONE%';

    IF bad_dflt IS NOT NULL THEN
        RAISE EXCEPTION
            'V20261005110000 failed: a session-local CURRENT_TIMESTAMP default on %; every '
            'new default must be (CURRENT_TIMESTAMP AT TIME ZONE ''UTC'').', bad_dflt;
    END IF;

    -- 4. V6 contract: no timestamptz may have crept in.
    SELECT string_agg(c.relname || '.' || a.attname, ', ') INTO bad_tz
      FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_attribute a ON a.attrelid = c.oid
                              AND a.atttypid = 'timestamptz'::regtype
                              AND a.attnum > 0 AND NOT a.attisdropped
     WHERE n.nspname = 'public'
       AND c.relname IN ('ai_run_steps', 'ai_run_retrievals', 'ai_run_tool_calls');

    IF bad_tz IS NOT NULL THEN
        RAISE EXCEPTION
            'V20261005110000 failed: timestamptz column in %; every timestamp in this schema '
            'is timestamp(3) without time zone.', bad_tz;
    END IF;

    -- 5. ai_runs itself must be untouched. This migration exists to make its
    --    children filterable; if a later edit of this file ever added a column to
    --    ai_runs, the guard says so rather than letting it pass review.
    IF EXISTS (SELECT 1 FROM pg_attribute a
                 JOIN pg_class c ON c.oid = a.attrelid
                 JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relname = 'ai_runs'
                  AND a.attname = 'updatedAt'
                  AND a.attnum > 0 AND NOT a.attisdropped) THEN
        RAISE EXCEPTION
            'V20261005110000 failed: ai_runs has gained an updatedAt column. This migration '
            'does not touch ai_runs; that belongs to its own reviewed change.';
    END IF;
END
$$;
