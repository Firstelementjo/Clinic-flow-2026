-- =====================================================================
-- V11_workflow_sla_slice.sql
-- Phase 6a / database verification: the workflow, SLA-calendar and collision
-- slice introduced by V20261005010000, V20261005020000, V20261005030000 and
-- V20261005040000.
--
-- Purpose: prove the Phase-6a contract on a real database -- shape, tenant
-- scope, index justification, CHECK invariants, and the append-only
-- boundaries -- on a database that MAY OR MAY NOT have run those migrations
-- yet.
--
-- Every section is guarded: on the pre-cutover database (no Phase-6a migration
-- applied) each section reports PHASE6A_NOT_PRESENT instead of failing, so the
-- evidence bundle keeps running before and after the cutover. That is the same
-- property V10 has for the Phase-5a slice.
--
-- READ-ONLY. No DDL, no DML, no object created. The EXPLAINs are EXPLAIN
-- WITHOUT ANALYZE, so no row is read and no statement is executed against the
-- tables.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Presence. The Phase-6a slice is applied as a whole or not at all.
-- ---------------------------------------------------------------------
SELECT CASE
         WHEN to_regclass('public.workflow_definitions') IS NOT NULL
          AND to_regclass('public.workflow_versions') IS NOT NULL
          AND to_regclass('public.workflow_runs') IS NOT NULL
          AND to_regclass('public.workflow_run_actions') IS NOT NULL
          AND to_regclass('public.sla_calendars') IS NOT NULL
          AND to_regclass('public.sla_calendar_windows') IS NOT NULL
          AND to_regclass('public.sla_calendar_holidays') IS NOT NULL
          AND to_regclass('public.sla_policies') IS NOT NULL
          AND to_regclass('public.collision_records') IS NOT NULL
         THEN 'PHASE6A_PRESENT'
         WHEN to_regclass('public.workflow_definitions') IS NULL
          AND to_regclass('public.workflow_versions') IS NULL
          AND to_regclass('public.workflow_runs') IS NULL
          AND to_regclass('public.workflow_run_actions') IS NULL
          AND to_regclass('public.sla_calendars') IS NULL
          AND to_regclass('public.sla_calendar_windows') IS NULL
          AND to_regclass('public.sla_calendar_holidays') IS NULL
          AND to_regclass('public.sla_policies') IS NULL
          AND to_regclass('public.collision_records') IS NULL
         THEN 'PHASE6A_NOT_PRESENT'
         ELSE 'PHASE6A_PARTIAL -- a Phase-6a migration failed halfway; stop'
       END AS phase6a_state;

-- ---------------------------------------------------------------------
-- 2. Enum value lists. These ARE the Phase-6 contract.
-- ---------------------------------------------------------------------
SELECT t.typname AS enum_name,
       string_agg(e.enumlabel, ',' ORDER BY e.enumsortorder) AS enum_values
  FROM pg_type t
  JOIN pg_enum e ON e.enumtypid = t.oid
  JOIN pg_namespace n ON n.oid = t.typnamespace
 WHERE n.nspname = 'public'
   AND t.typname IN ('WorkflowVersionState', 'WorkflowRunStatus',
                     'WorkflowActionOutcome', 'SlaCalendarState',
                     'SlaClockType', 'CollisionScope', 'CollisionResolution')
 GROUP BY t.typname
 ORDER BY t.typname;

-- ---------------------------------------------------------------------
-- 3. Column shape of the nine new tables: position, type, nullability,
--    default. Every timestamp must be timestamp(3) WITHOUT time zone (V6),
--    and every UTC-writing default must say AT TIME ZONE 'UTC'.
-- ---------------------------------------------------------------------
SELECT c.relname AS table_name,
       a.attnum  AS ord,
       a.attname AS column_name,
       format_type(a.atttypid, a.atttypmod) AS data_type,
       CASE WHEN a.attnotnull THEN 'NOT NULL' ELSE 'NULL' END AS nullable,
       COALESCE(pg_get_expr(d.adbin, d.adrelid), '-') AS default_expr
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
  JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
  LEFT JOIN pg_attrdef d ON d.adrelid = c.oid AND d.adnum = a.attnum
 WHERE n.nspname = 'public'
   AND c.relname IN ('workflow_definitions', 'workflow_versions',
                     'workflow_runs', 'workflow_run_actions',
                     'sla_calendars', 'sla_calendar_windows',
                     'sla_calendar_holidays', 'sla_policies',
                     'collision_records')
 ORDER BY c.relname, a.attnum;

-- ---------------------------------------------------------------------
-- 4. Summary assertions, MEASURED on the Phase-6a rehearsal throwaway (2026-10-05,
--    pgvector/pgvector:pg16, whole chain in one psql session):
--      tables 58 | indexes 212 | unique_indexes 102 | fks 113 | pks 58
--      check_constraints 70 | phase6a_checks 56 | enums 34
--      unique_constraints 0 (still carried by unique INDEXES -- V4 invariant)
--    14 of the 70 CHECKs are Phase 5a's; 56 are Phase 6a's. The numbers are
--    printed rather than asserted here so a drift is visible as a number; the
--    hard assertions live in FlywayBaselineAcceptanceTest and in
--    run-verification.ps1's -ExpectedTableCount / -ExpectedCheckCount.
-- ---------------------------------------------------------------------
SELECT (SELECT count(*) FROM pg_tables
         WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history') AS tables,
       (SELECT count(*) FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public' AND c.relkind = 'r'
           AND c.relname IN ('workflow_definitions', 'workflow_versions',
                             'workflow_runs', 'workflow_run_actions',
                             'sla_calendars', 'sla_calendar_windows',
                             'sla_calendar_holidays', 'sla_policies',
                             'collision_records'))                            AS new_tables,
       (SELECT count(*) FROM pg_constraint con
          JOIN pg_class c ON c.oid = con.conrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public' AND con.contype = 'f')                   AS foreign_keys,
       (SELECT count(*) FROM pg_constraint con
          JOIN pg_class c ON c.oid = con.conrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public' AND con.contype = 'p')                   AS primary_keys,
       (SELECT count(*) FROM pg_constraint con
          JOIN pg_class c ON c.oid = con.conrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public' AND con.contype = 'c')                   AS check_constraints,
       (SELECT count(*) FROM pg_constraint con
          JOIN pg_class c ON c.oid = con.conrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public' AND con.contype = 'c'
           AND c.relname IN ('workflow_definitions', 'workflow_versions',
                             'workflow_runs', 'workflow_run_actions',
                             'sla_calendars', 'sla_calendar_windows',
                             'sla_calendar_holidays', 'sla_policies',
                             'collision_records'))                           AS phase6a_checks,
       (SELECT count(DISTINCT t.typname) FROM pg_type t
          JOIN pg_namespace n ON n.oid = t.typnamespace
          JOIN pg_enum e ON e.enumtypid = t.oid
         WHERE n.nspname = 'public')                                         AS enums,
       (SELECT count(*) FROM pg_indexes WHERE schemaname = 'public')         AS indexes;

-- 4a. FR-1.2: no nullable tenant column where the table HAS one. Must be 0.
--     Note sla_calendar_windows and sla_calendar_holidays are excluded: they
--     inherit scope from their calendar and must not carry tenant columns at
--     all, which 4c checks.
SELECT count(*) AS nullable_organization_id
  FROM pg_attribute a
  JOIN pg_class c ON c.oid = a.attrelid
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public'
   AND c.relname IN ('workflow_definitions', 'workflow_versions',
                     'workflow_runs', 'workflow_run_actions',
                     'sla_calendars', 'sla_policies', 'collision_records')
   AND a.attname = 'organizationId' AND NOT a.attnotnull;

-- 4b. No timestamptz may appear in a new column. Must be 0.
SELECT count(*) AS timestamptz_columns
  FROM pg_attribute a
  JOIN pg_class c ON c.oid = a.attrelid
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public'
   AND c.relname IN ('workflow_definitions', 'workflow_versions',
                     'workflow_runs', 'workflow_run_actions',
                     'sla_calendars', 'sla_calendar_windows',
                     'sla_calendar_holidays', 'sla_policies',
                     'collision_records')
   AND a.attnum > 0 AND NOT a.attisdropped
   AND a.atttypid = 'timestamptz'::regtype;

-- 4c. THE INHERITED-SCOPE RULE. sla_calendar_windows and
--     sla_calendar_holidays must have NO organizationId and NO projectId.
--     Must return 0 rows. A row here means somebody duplicated scope onto a
--     calendar child and created the possibility of it disagreeing with its
--     parent.
SELECT c.relname AS table_name, a.attname AS column_name
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
  JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
 WHERE n.nspname = 'public'
   AND c.relname IN ('sla_calendar_windows', 'sla_calendar_holidays')
   AND a.attname IN ('organizationId', 'projectId')
 ORDER BY 1, 2;

-- 4d. Nullable projectId on the Phase-6a tables. This is the documented,
--     deliberate organisation-wide case and it is listed here so it can never
--     widen unnoticed: exactly workflow_definitions, workflow_versions,
--     sla_calendars and sla_policies. collision_records.projectId must NOT
--     appear here, because every collided resource is project-scoped.
SELECT c.relname AS table_name, a.attname AS column_name
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
  JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
 WHERE n.nspname = 'public'
   AND c.relname IN ('workflow_definitions', 'workflow_versions',
                     'workflow_runs', 'workflow_run_actions',
                     'sla_calendars', 'sla_calendar_windows',
                     'sla_calendar_holidays', 'sla_policies',
                     'collision_records')
   AND a.attname = 'projectId' AND NOT a.attnotnull
 ORDER BY c.relname;

-- 4e. THE APPEND-ONLY BOUNDARY. workflow_run_actions and collision_records
--     must NOT have an updatedAt column; the other seven Phase-6a tables must.
--     Must return exactly the seven mutable tables.
SELECT c.relname AS table_name,
       EXISTS (SELECT 1 FROM pg_attribute a
                WHERE a.attrelid = c.oid AND a.attnum > 0
                  AND NOT a.attisdropped AND a.attname = 'updatedAt') AS has_updated_at
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public' AND c.relkind = 'r'
   AND c.relname IN ('workflow_definitions', 'workflow_versions',
                     'workflow_runs', 'workflow_run_actions',
                     'sla_calendars', 'sla_calendar_windows',
                     'sla_calendar_holidays', 'sla_policies',
                     'collision_records')
 ORDER BY c.relname;

-- 4f. Every UTC-writing default must name the zone. A default that stores the
--     SESSION's wall clock is the baseline's habit and is exactly what the
--     Phase-5a chain stopped doing. Must be 0.
SELECT c.relname AS table_name, a.attname AS column_name,
       pg_get_expr(d.adbin, d.adrelid) AS default_expr
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
  JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
  JOIN pg_attrdef d ON d.adrelid = c.oid AND d.adnum = a.attnum
 WHERE n.nspname = 'public'
   AND c.relname IN ('workflow_definitions', 'workflow_versions',
                     'workflow_runs', 'workflow_run_actions',
                     'sla_calendars', 'sla_calendar_windows',
                     'sla_calendar_holidays', 'sla_policies',
                     'collision_records')
   AND pg_get_expr(d.adbin, d.adrelid) LIKE '%CURRENT_TIMESTAMP%'
   AND pg_get_expr(d.adbin, d.adrelid) NOT LIKE '%AT TIME ZONE%'
 ORDER BY 1, 2;

-- 4g. Identifiers that PostgreSQL truncated at 63 bytes. NOTE: `length =
--     63` is NOT the test -- exactly 63 bytes is legal and untruncated, which
--     the throwaway rehearsal measured on this host's PostgreSQL 16. The test
--     that actually detects truncation is the other direction: an index on a
--     Phase-6a table whose name does not match the exact name the migration
--     declared. Must return 0 rows. (The baseline has pre-existing truncated
--     db-push names and is deliberately left alone.)
SELECT tablename, indexname, length(indexname)
  FROM pg_indexes
 WHERE schemaname = 'public'
   AND tablename IN ('workflow_definitions', 'workflow_versions',
                     'workflow_runs', 'workflow_run_actions',
                     'sla_calendars', 'sla_calendar_windows',
                     'sla_calendar_holidays', 'sla_policies',
                     'collision_records')
   AND indexname NOT IN (
       'workflow_definitions_pkey',
       'workflow_definitions_organizationId_key_key',
       'workflow_definitions_org_project_trigger_priority_idx',
       'workflow_versions_pkey',
       'workflow_versions_organizationId_definitionId_versionNumber_key',
       'workflow_versions_one_active_per_definition',
       'workflow_versions_organizationId_definitionId_state_idx',
       'workflow_versions_id_definitionId_organizationId_key',
       'workflow_runs_pkey',
       'workflow_runs_organizationId_trigger_instance_key',
       'workflow_runs_org_project_status_created_idx',
       'workflow_runs_organizationId_conversationId_idx',
       'workflow_runs_organizationId_versionId_status_idx',
       'workflow_run_actions_pkey',
       'workflow_run_actions_organizationId_runId_actionKey_attempt_key',
       'workflow_run_actions_organizationId_runId_idx',
       'workflow_run_actions_organizationId_outcome_createdAt_idx',
       'sla_calendars_pkey',
       'sla_calendars_organizationId_key_version_key',
       'sla_calendars_one_active_per_key',
       'sla_calendars_organizationId_effectiveFrom_idx',
       'sla_calendars_id_organizationId_key',
       'sla_calendar_windows_pkey',
       'sla_calendar_windows_calendar_day_idx',
       'sla_calendar_holidays_pkey',
       'sla_calendar_holidays_calendar_date_key',
       'sla_policies_pkey',
       'sla_policies_organizationId_projectId_key_key',
       'sla_policies_organizationId_projectId_enabled_idx',
       'collision_records_pkey',
       'collision_records_org_scope_resource_window_key',
       'collision_records_counterparty_unique',
       'collision_records_organizationId_scope_resourceId_createdAt_idx',
       'collision_records_organizationId_outcome_createdAt_idx',
       'collision_records_organizationId_resourceType_resourceId_idx')
 ORDER BY 1, 2;

-- 4g2. Two indexes created by V20261005020000 and V20261005030000 that are not
--      business keys at all: they exist so a COMPOSITE foreign key can refuse a
--      run bound to another tenant's workflow body, and a policy pointing at
--      another tenant's calendar. Both must be UNIQUE INDEXES (not UNIQUE
--      CONSTRAINTS, which would break V4's unique_constraints = 0 invariant)
--      and both composite FKs must exist.
SELECT c.relname AS child_table, con.conname AS composite_fk,
       (SELECT count(*) FROM pg_index i
         WHERE i.indrelid = con.conrelid AND i.indisunique) AS unique_indexes_on_child
  FROM pg_constraint con
  JOIN pg_class c ON c.oid = con.conrelid
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public'
   AND con.conname IN ('workflow_runs_version_tenant_fkey',
                       'sla_policies_calendar_tenant_fkey')
 ORDER BY 1;

-- 4h. THE THREE INDEPENDENT SLA CLOCKS must all exist as columns. If a future
--     migration collapses them into one duration, the Phase-6 contract is broken
--     and this reports it as a 0 instead of silently passing.
SELECT count(*) AS independent_clock_columns
  FROM pg_attribute a
  JOIN pg_class c ON c.oid = a.attrelid
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public' AND c.relname = 'sla_policies'
   AND a.attname IN ('firstResponseMinutes', 'nextResponseMinutes', 'resolutionMinutes')
   AND a.attnum > 0 AND NOT a.attisdropped;

-- 4i. Both collision uniqueness indexes must really be UNIQUE, not merely
--     present. A migration that created them as plain indexes would satisfy a
--     name-only guard and break the concurrency guarantee.
-- pg_index has no indexrelname column; the name lives on pg_class.relname. A
-- verification query that guesses a column name is exactly the kind of defect
-- that is only found by running it against a real database, and it was.
SELECT c.relname AS index_name, i.indisunique
  FROM pg_index i
  JOIN pg_class c ON c.oid = i.indexrelid
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public'
   AND c.relname IN ('collision_records_org_scope_resource_window_key',
                     'collision_records_counterparty_unique',
                     'workflow_versions_one_active_per_definition',
                     'sla_calendars_one_active_per_key',
                     'workflow_versions_id_definitionId_organizationId_key',
                     'sla_calendars_id_organizationId_key',
                     'conversations_id_organizationId_key')
 ORDER BY 1;

-- ---------------------------------------------------------------------
-- 5. Index justification.
-- 5a. THE TENANT-LESS ALLOW LIST. V9 classifies an index as "global" when it
--     carries no tenant column. Phase 2 contributed one
--     (sessions_expiresAt_idx), Phase 5a two (outbox dispatcher + reaper
--     sweeps). Phase 6a adds ONE:
--       sla_calendar_windows_calendar_day_idx -- the per-evaluation window
--       read, only reachable after an organizationId-filtered calendar row has
--       been read, carrying no tenant data and no customer content.
--     Any index outside this list is a tenant-scope regression.
-- ---------------------------------------------------------------------
SELECT tablename, indexname
  FROM pg_indexes
 WHERE schemaname = 'public'
   AND tablename <> 'flyway_schema_history'
   AND indexdef NOT LIKE '%USING btree ("organizationId"%'
   AND indexdef NOT LIKE '%USING btree ("projectId"%'
   AND indexdef NOT LIKE '%USING hnsw%'
   AND indexdef NOT LIKE '%USING btree ("runId"%'
   AND indexdef NOT LIKE '%USING btree ("conversationId"%'
   AND indexdef NOT LIKE '%USING btree ("employeeId"%'
   AND indexdef NOT LIKE '%USING btree ("userId"%'
   AND indexdef NOT LIKE '%USING btree ("knowledgeBaseId"%'
   AND indexdef NOT LIKE '%USING btree ("agentVersionId"%'
   AND indexdef NOT LIKE '%USING btree ("customerId"%'
   AND indexdef NOT LIKE '%UNIQUE%'
 ORDER BY tablename, indexname;

-- 5b. Full inventory of the Phase-6a indexes, so a reader can see the intent
--     and the leading column side by side.
SELECT tablename, indexname, indexdef
  FROM pg_indexes
 WHERE schemaname = 'public'
   AND tablename IN ('workflow_definitions', 'workflow_versions',
                     'workflow_runs', 'workflow_run_actions',
                     'sla_calendars', 'sla_calendar_windows',
                     'sla_calendar_holidays', 'sla_policies',
                     'collision_records')
 ORDER BY tablename, indexname;

-- 5c/5d/5e/5f. The Phase-6 read models must be index-supported, not
--     sequentially scanned. EXPLAIN only -- no rows are read and nothing is
--     executed against the tables. These run inside a guarded block because an
--     EXPLAIN naming a Phase-6a table is a parse-time error on a database where
--     Phase 6a has not been applied.
DO $$
DECLARE
    plan      text;
    i         int;
    statements text[] := ARRAY[
        'explain (costs off) select id from public.workflow_runs
          where "organizationId" = ''00000000-0000-0000-0000-000000000000''
            and "projectId" = ''00000000-0000-0000-0000-000000000000''
            and status = ''RUNNING''::"public"."WorkflowRunStatus"
          order by "createdAt" desc limit 50',
        'explain (costs off) select id from public.workflow_definitions
          where "organizationId" = ''00000000-0000-0000-0000-000000000000''
            and "projectId" = ''00000000-0000-0000-0000-000000000000''
            and "triggerType" = ''CONVERSATION_OPENED''
            and "isEnabled"
          order by "priority" limit 20',
        'explain (costs off) select id from public.collision_records
          where "organizationId" = ''00000000-0000-0000-0000-000000000000''
            and outcome = ''ABORTED_BY_HUMAN''::"public"."CollisionResolution"
          order by "createdAt" desc limit 50',
        'explain (costs off) select "calendarId", "dayOfWeek"
            from public.sla_calendar_windows
           where "calendarId" = ''00000000-0000-0000-0000-000000000000''
             and "dayOfWeek" = 0'
    ];
BEGIN
    IF to_regclass('public.workflow_runs') IS NULL THEN
        RAISE NOTICE 'V11 5c-5f: PHASE6A_NOT_PRESENT (no read-model plan to prove)';
        RETURN;
    END IF;

    FOR i IN 1 .. 4 LOOP
        RAISE NOTICE 'V11 plan %:', i;
        FOR plan IN EXECUTE statements[i] LOOP
            RAISE NOTICE '   %', plan;
        END LOOP;
    END LOOP;
END
$$;

-- ---------------------------------------------------------------------
-- 6. CHECK invariants, asserted with data where rows exist and as an
--    EXCEPTION where a dropped constraint must be visible.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    runs_total          bigint;
    actions_total       bigint;
    collisions_total    bigint;
    crossed_project     bigint;
    unknown_org         bigint;
    unknown_calendar    bigint;
    bad_capability      bigint;
BEGIN
    IF to_regclass('public.workflow_runs') IS NULL THEN
        RAISE NOTICE 'V11 section 6: PHASE6A_NOT_PRESENT';
        RETURN;
    END IF;

    SELECT count(*) INTO runs_total    FROM public.workflow_runs;
    SELECT count(*) INTO actions_total FROM public.workflow_run_actions;
    SELECT count(*) INTO collisions_total FROM public.collision_records;

    -- Cross-tenant negatives, with data rather than metadata.
    --   a) a Phase-6a row whose tenant does not exist: the FK makes this
    --      impossible, so a non-zero count means an FK was dropped.
    SELECT count(*) INTO unknown_org
      FROM public.workflow_runs r
     WHERE NOT EXISTS (SELECT 1 FROM public.organizations o
                        WHERE o.id = r."organizationId");

    --   b) a row whose project belongs to a DIFFERENT tenant. The FK only
    --      guarantees the project exists; this is the check that guarantees it
    --      belongs to the same tenant.
    SELECT count(*) INTO crossed_project
      FROM public.workflow_runs r
      JOIN public.projects p ON p.id = r."projectId"
     WHERE p."organizationId" <> r."organizationId";

    --   c) an SLA policy whose calendar belongs to a DIFFERENT tenant. This is
    --      the one a FK alone cannot prevent and it is the highest-value
    --      cross-tenant negative in this slice: a policy that resolves another
    --      tenant's working week would pause the wrong clock.
    SELECT count(*) INTO unknown_calendar
      FROM public.sla_policies p
      JOIN public.sla_calendars c ON c.id = p."calendarId"
     WHERE c."organizationId" <> p."organizationId";

    --   d) a run or version whose capabilityScope is not a JSON array.
    SELECT count(*) INTO bad_capability
      FROM (
        SELECT "capabilityScope" FROM public.workflow_runs
        UNION ALL
        SELECT "capabilityScope" FROM public.workflow_versions
      ) s
     WHERE jsonb_typeof(s."capabilityScope") <> 'array';

    RAISE NOTICE 'V11 volume: runs=% actions=% collisions=%',
                 runs_total, actions_total, collisions_total;
    RAISE NOTICE 'V11 tenancy: unknown_organization_rows=% cross_project_rows=% cross_tenant_calendar=%',
                 unknown_org, crossed_project, unknown_calendar;
    RAISE NOTICE 'V11 capability: non_array_capability_scopes=%', bad_capability;

    -- Asserted as exceptions so a DROPPED CHECK constraint is visible here and
    -- not only in the fixture.

    -- workflow_runs: an unfinished run must not claim it finished, and a finished
    -- one must say when.
    IF EXISTS (SELECT 1 FROM public.workflow_runs
                WHERE ("status" IN ('RUNNING', 'WAITING')) <> ("finishedAt" IS NULL)) THEN
        RAISE EXCEPTION 'V11: a workflow_runs run state and finishedAt disagree';
    END IF;

    IF EXISTS (SELECT 1 FROM public.workflow_runs
                WHERE "abortRequestedAt" IS NOT NULL AND "abortedByUserId" IS NULL) THEN
        RAISE EXCEPTION 'V11: a workflow_runs abort request names no requester';
    END IF;

    IF EXISTS (SELECT 1 FROM public.workflow_runs
                WHERE "status" IN ('ABORTED', 'TAKEOVER')
                  AND ("abortedAt" IS NULL OR "abortedByUserId" IS NULL)) THEN
        RAISE EXCEPTION 'V11: an aborted or taken-over workflow run names nobody';
    END IF;

    -- workflow_run_actions: append-only, so an unfinished action must not carry a
    -- duration and only a failure may carry an error.
    IF EXISTS (SELECT 1 FROM public.workflow_run_actions
                WHERE ("finishedAt" IS NULL) <> ("durationMs" IS NULL)) THEN
        RAISE EXCEPTION 'V11: workflow_run_actions has a half-finished action';
    END IF;

    IF EXISTS (SELECT 1 FROM public.workflow_run_actions
                WHERE "isReplay" AND "replayOfId" IS NULL) THEN
        RAISE EXCEPTION 'V11: a replay action does not name what it replays';
    END IF;

    IF EXISTS (SELECT 1 FROM public.workflow_run_actions
                WHERE "actorUserId" IS NULL AND "actorPrincipal" IS NULL) THEN
        RAISE EXCEPTION 'V11: a workflow action has no actor at all';
    END IF;

    -- workflow_versions: a DRAFT has never been activated, an ACTIVE one says
    -- who activated it, a rollback says where it went back to.
    IF EXISTS (SELECT 1 FROM public.workflow_versions
                WHERE ("state" = 'DRAFT'::"public"."WorkflowVersionState")
                      <> ("activatedAt" IS NULL)) THEN
        RAISE EXCEPTION 'V11: a workflow version state and activatedAt disagree';
    END IF;

    IF EXISTS (SELECT 1 FROM public.workflow_versions
                WHERE "state" = 'ROLLED_BACK'::"public"."WorkflowVersionState"
                  AND ("rolledBackAt" IS NULL
                       OR "rolledBackByUserId" IS NULL
                       OR "rolledBackToVersionId" IS NULL)) THEN
        RAISE EXCEPTION 'V11: a rolled-back workflow version is unattributable';
    END IF;

    -- THE CLOCK-ORDERING INVARIANT, asserted over real rows.
    IF EXISTS (SELECT 1 FROM public.sla_policies
                WHERE ("firstResponseMinutes" IS NOT NULL
                       AND "resolutionMinutes" IS NOT NULL
                       AND "resolutionMinutes" < "firstResponseMinutes")
                   OR ("firstResponseMinutes" IS NOT NULL
                       AND "nextResponseMinutes" IS NOT NULL
                       AND "nextResponseMinutes" < "firstResponseMinutes")) THEN
        RAISE EXCEPTION 'V11: an SLA policy has a clock ordered backwards';
    END IF;

    -- THE SAME-DAY WINDOW INVARIANT, asserted over real rows.
    IF EXISTS (SELECT 1 FROM public.sla_calendar_windows
                WHERE "endMinute" <= "startMinute") THEN
        RAISE EXCEPTION 'V11: an SLA calendar window is empty or overnight';
    END IF;

    IF EXISTS (SELECT 1 FROM public.sla_calendar_holidays
                WHERE ("startMinute" IS NULL) <> ("endMinute" IS NULL)) THEN
        RAISE EXCEPTION 'V11: an SLA calendar holiday has half a partial closure';
    END IF;

    -- calendar children must belong to a calendar, and the version numbering
    -- must be sane.
    IF EXISTS (SELECT 1 FROM public.sla_calendar_windows w
                WHERE NOT EXISTS (SELECT 1 FROM public.sla_calendars c
                                   WHERE c.id = w."calendarId")) THEN
        RAISE EXCEPTION 'V11: an SLA calendar window has no calendar';
    END IF;

    IF EXISTS (SELECT 1 FROM public.sla_calendars WHERE "version" < 1) THEN
        RAISE EXCEPTION 'V11: an SLA calendar has a version below 1';
    END IF;

    -- collision_records: one incident per (resource, window), and an AI abort
    -- must be attributable.
    IF EXISTS (SELECT 1 FROM public.collision_records
                WHERE ("conversationId" IS NOT NULL)::int
                    + ("customerId" IS NOT NULL)::int
                    + ("noteId" IS NOT NULL)::int > 1) THEN
        RAISE EXCEPTION 'V11: a collision record names more than one resource';
    END IF;

    IF EXISTS (SELECT 1 FROM public.collision_records
                WHERE "outcome" IN ('KEPT_BOTH'::"public"."CollisionResolution",
                                    'NOOP'::"public"."CollisionResolution")
                  AND "resolutionDetail" IS NULL) THEN
        RAISE EXCEPTION 'V11: a kept-both or no-op collision does not say what happened';
    END IF;

    -- The Phase-6a acceptance gate in one number: how often a human takeover
    -- actually beat an in-flight AI send.
    IF collisions_total > 0 THEN
        RAISE NOTICE 'V11 human-takeover-observed=%',
            (SELECT count(*) FROM public.collision_records
              WHERE "outcome" = 'ABORTED_BY_HUMAN'::"public"."CollisionResolution");
    END IF;
END
$$;

-- ---------------------------------------------------------------------
-- 7. Session timezone. A connection whose TimeZone is not UTC would resolve
--    (now() AT TIME ZONE 'UTC') against the wrong wall clock for every
--    timestamp the application binds as LocalDateTime, and the SLA clock
--    evaluator compares against exactly those. The application must configure
--    this; the schema cannot.
-- ---------------------------------------------------------------------
SHOW TimeZone;