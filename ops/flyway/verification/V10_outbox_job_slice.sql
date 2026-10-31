-- =====================================================================
-- V10_outbox_job_slice.sql
-- Phase 5a / database verification: the outbox, job-attempt, dead-letter and
-- idempotency slice introduced by V20261004000100, V20261004000700 and
-- V20261004001000.
--
-- Purpose: prove the Phase-5a contract on a real database -- shape, tenant
-- scope, index justification, and that the claim query is index-supported --
-- on a database that MAY OR MAY NOT have run those migrations yet.
--
-- Every section is guarded: on the pre-cutover database (no Phase-5a migration
-- applied) each section reports PHASE5A_NOT_PRESENT instead of failing, so the
-- evidence bundle keeps running before and after the cutover.
--
-- READ-ONLY. No DDL, no DML, no object created. The one EXPLAIN is EXPLAIN
-- without ANALYZE, so no row is read and no statement is executed against the
-- table; the plan is only estimated from statistics and the index definitions.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Presence. The Phase-5a slice is either applied as a whole or not at all.
-- ---------------------------------------------------------------------
SELECT CASE
         WHEN to_regclass('public.outbox_events') IS NOT NULL
          AND to_regclass('public.job_attempts') IS NOT NULL
          AND to_regclass('public.job_dead_letters') IS NOT NULL
          AND to_regclass('public.idempotency_records') IS NOT NULL
         THEN 'PHASE5A_PRESENT'
         WHEN to_regclass('public.outbox_events') IS NULL
          AND to_regclass('public.job_attempts') IS NULL
          AND to_regclass('public.job_dead_letters') IS NULL
          AND to_regclass('public.idempotency_records') IS NULL
         THEN 'PHASE5A_NOT_PRESENT'
         ELSE 'PHASE5A_PARTIAL -- a Phase-5a migration failed halfway; stop'
       END AS phase5a_state;

-- ---------------------------------------------------------------------
-- 2. Enum value lists. These ARE the dispatcher contract.
-- ---------------------------------------------------------------------
SELECT t.typname AS enum_name,
       string_agg(e.enumlabel, ',' ORDER BY e.enumsortorder) AS enum_values
  FROM pg_type t
  JOIN pg_enum e ON e.enumtypid = t.oid
  JOIN pg_namespace n ON n.oid = t.typnamespace
 WHERE n.nspname = 'public'
   AND t.typname IN ('OutboxState', 'JobAttemptOutcome',
                     'DeadLetterStatus', 'IdempotencyState')
 GROUP BY t.typname
 ORDER BY t.typname;

-- ---------------------------------------------------------------------
-- 3. Column shape of the four new tables: position, type, nullability,
--    default. Every timestamp must be timestamp(3) WITHOUT time zone (V6).
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
   AND c.relname IN ('outbox_events', 'job_attempts',
                     'job_dead_letters', 'idempotency_records')
 ORDER BY c.relname, a.attnum;

-- ---------------------------------------------------------------------
-- 4. Summary assertions. Expect, once Phase 5a is applied:
--      tables 49, fks 76, pks 49, new_tables 4, enums 27, checks 14
-- ---------------------------------------------------------------------
SELECT (SELECT count(*) FROM pg_tables
         WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history') AS tables,
       (SELECT count(*) FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public' AND c.relkind = 'r'
           AND c.relname IN ('outbox_events', 'job_attempts',
                             'job_dead_letters', 'idempotency_records'))     AS new_tables,
       (SELECT count(*) FROM pg_constraint con
          JOIN pg_class c ON c.oid = con.conrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public' AND con.contype = 'f')                  AS foreign_keys,
       (SELECT count(*) FROM pg_constraint con
          JOIN pg_class c ON c.oid = con.conrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public' AND con.contype = 'p')                  AS primary_keys,
       (SELECT count(*) FROM pg_constraint con
          JOIN pg_class c ON c.oid = con.conrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public' AND con.contype = 'c')                  AS check_constraints,
       (SELECT count(DISTINCT t.typname) FROM pg_type t
          JOIN pg_namespace n ON n.oid = t.typnamespace
          JOIN pg_enum e ON e.enumtypid = t.oid
         WHERE n.nspname = 'public')                                        AS enums,
       (SELECT count(*) FROM pg_indexes WHERE schemaname = 'public')        AS indexes;

-- 4a. FR-1.2: no nullable tenant column on the new tables. Must be 0.
SELECT count(*) AS nullable_organization_id
  FROM pg_attribute a
  JOIN pg_class c ON c.oid = a.attrelid
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public'
   AND c.relname IN ('outbox_events', 'job_attempts',
                     'job_dead_letters', 'idempotency_records')
   AND a.attname = 'organizationId' AND NOT a.attnotnull;

-- 4b. No timestamptz may appear in a new column. Must be 0.
SELECT count(*) AS timestamptz_columns
  FROM pg_attribute a
  JOIN pg_class c ON c.oid = a.attrelid
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public'
   AND c.relname IN ('outbox_events', 'job_attempts',
                     'job_dead_letters', 'idempotency_records')
   AND a.attnum > 0 AND NOT a.attisdropped
   AND a.atttypid = 'timestamptz'::regtype;

-- 4c. job_attempts is append-only: it must NOT have an updatedAt column.
--     Must return 0 rows once Phase 5a is applied.
SELECT c.relname AS table_with_updated_at
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public' AND c.relname = 'job_attempts'
   AND EXISTS (SELECT 1 FROM pg_attribute a
                WHERE a.attrelid = c.oid AND a.attnum > 0
                  AND NOT a.attisdropped AND a.attname = 'updatedAt');

-- 4d. Nullable projectId on the new tables. This is the documented,
--     deliberate "organisation-wide work" case (V20261004000100 Q4) and it is
--     listed here so it can never widen unnoticed: exactly job_attempts,
--     job_dead_letters, idempotency_records and outbox_events.
SELECT c.relname AS table_name, a.attname AS column_name
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
  JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
 WHERE n.nspname = 'public'
   AND c.relname IN ('outbox_events', 'job_attempts',
                     'job_dead_letters', 'idempotency_records')
   AND a.attname = 'projectId' AND NOT a.attnotnull
 ORDER BY c.relname;

-- ---------------------------------------------------------------------
-- 5. Index justification. Two questions, both answerable only here:
--    a) does the claim index support the Phase-5a claim predicate?
--    b) is every tenant-less index in this slice a deliberate one?
--
--    EXPLAIN (no ANALYZE) does not execute the statement.
-- ---------------------------------------------------------------------
SELECT tablename, indexname, indexdef
  FROM pg_indexes
 WHERE schemaname = 'public'
   AND tablename IN ('outbox_events', 'job_attempts',
                     'job_dead_letters', 'idempotency_records')
 ORDER BY tablename, indexname;

-- 5a. THE TENANT-LESS ALLOW LIST. V9 classifies an index as "global" when it
--     carries no tenant column. On the Phase-2 baseline there was exactly one
--     such index. Phase 5a adds two, both SCHEDULING-only (state/lease timing,
--     never payload or tenant data) and both documented in
--     docs/migration/phases/phase-05a.md. Any index outside this list is a
--     tenant-scope regression.
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

-- 5b/5c/5d. Claim plans: each index must be the one the planner can use for
--     its statement. EXPLAIN only -- no rows are read and nothing is executed
--     against the table; the plan is estimated from statistics and the index
--     definitions.
--
--     These run inside a guarded block because an EXPLAIN that names
--     outbox_events is a parse-time error on a database where Phase 5a has not
--     been applied, and the evidence bundle must keep working on both sides of
--     the cutover. plpgsql's EXECUTE can run EXPLAIN and return its plan rows;
--     they are reported as NOTICEs rather than as a result set.
DO $$
DECLARE
    plan text;
    i    int;
    statements text[] := ARRAY[
        'explain (costs off) select id from public.outbox_events
          where "organizationId" = ''00000000-0000-0000-0000-000000000000''
            and state = ''PENDING''
            and "nextAttemptAt" <= (now() at time zone ''UTC'')
            and ("leaseUntil" is null or "leaseUntil" < (now() at time zone ''UTC''))
          order by "nextAttemptAt", "createdAt", id limit 50',
        'explain (costs off) select id from public.outbox_events
          where state = ''PENDING''
            and "nextAttemptAt" <= (now() at time zone ''UTC'')
            and ("leaseUntil" is null or "leaseUntil" < (now() at time zone ''UTC''))
          order by "nextAttemptAt", "createdAt", id limit 50',
        'explain (costs off) select id from public.outbox_events
          where state = ''LEASED''
            and "leaseUntil" < (now() at time zone ''UTC'')
          order by "leaseUntil" limit 50'
    ];
BEGIN
    IF to_regclass('public.outbox_events') IS NULL THEN
        RAISE NOTICE 'V10 5b-5d: PHASE5A_NOT_PRESENT (no claim plan to prove)';
        RETURN;
    END IF;

    FOR i IN 1 .. 3 LOOP
        RAISE NOTICE 'V10 plan %:', i;
        FOR plan IN EXECUTE statements[i] LOOP
            RAISE NOTICE '   %', plan;
        END LOOP;
    END LOOP;
END
$$;

-- ---------------------------------------------------------------------
-- 6. Operational state, guarded. Only meaningful on a database that has run
--    Phase 5a AND has queue rows. RAISE NOTICE keeps it read-only.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    leased_expired   bigint;
    pending_due      bigint;
    dead_total       bigint;
    dlq_open         bigint;
    attempts_total   bigint;
    crossed          bigint;
    wrong_project    bigint;
BEGIN
    IF to_regclass('public.outbox_events') IS NULL THEN
        RAISE NOTICE 'V10 section 6: PHASE5A_NOT_PRESENT';
        RETURN;
    END IF;

    SELECT count(*) INTO pending_due
      FROM public.outbox_events
     WHERE state = 'PENDING'
       AND "nextAttemptAt" <= (now() AT TIME ZONE 'UTC')
       AND ("leaseUntil" IS NULL OR "leaseUntil" < (now() AT TIME ZONE 'UTC'));

    SELECT count(*) INTO leased_expired
      FROM public.outbox_events
     WHERE state = 'LEASED' AND "leaseUntil" < (now() AT TIME ZONE 'UTC');

    SELECT count(*) INTO dead_total
      FROM public.outbox_events WHERE state = 'DEAD';

    SELECT count(*) INTO dlq_open
      FROM public.job_dead_letters WHERE status = 'OPEN';

    SELECT count(*) INTO attempts_total FROM public.job_attempts;

    -- Cross-tenant negatives, with data rather than metadata:
    --   a) an outbox row whose tenant does not exist (the FK makes this
    --      impossible, so a non-zero count means the FK was dropped)
    SELECT count(*) INTO crossed
      FROM public.outbox_events o
     WHERE NOT EXISTS (SELECT 1 FROM public.organizations g WHERE g.id = o."organizationId");

    --   b) a project-scoped row whose project belongs to a DIFFERENT tenant.
    --      The FK only guarantees the project exists; this is the check that
    --      guarantees it belongs to the same tenant.
    SELECT count(*) INTO wrong_project
      FROM public.outbox_events o
      JOIN public.projects p ON p.id = o."projectId"
     WHERE p."organizationId" <> o."organizationId";

    RAISE NOTICE 'V10 outbox: pending_due=% leased_expired=% dead=% dlq_open=% attempts=%',
                 pending_due, leased_expired, dead_total, dlq_open, attempts_total;
    RAISE NOTICE 'V10 tenancy: unknown_organization_rows=% cross_project_rows=%',
                 crossed, wrong_project;

    -- A dead letter without its timestamp, or a SENT row without one, is
    -- impossible through the CHECK constraints; assert it anyway, because a
    -- dropped constraint must be visible here.
    IF EXISTS (SELECT 1 FROM public.outbox_events
                WHERE (state = 'DEAD') <> ("deadLetteredAt" IS NOT NULL)) THEN
        RAISE EXCEPTION 'V10: outbox_events DEAD state and deadLetteredAt disagree';
    END IF;

    IF EXISTS (SELECT 1 FROM public.outbox_events
                WHERE (state = 'SENT') <> ("deliveredAt" IS NOT NULL)) THEN
        RAISE EXCEPTION 'V10: outbox_events SENT state and deliveredAt disagree';
    END IF;

    IF EXISTS (SELECT 1 FROM public.outbox_events
                WHERE ("leaseOwner" IS NULL) <> ("leaseUntil" IS NULL)) THEN
        RAISE EXCEPTION 'V10: outbox_events has a half-set lease';
    END IF;

    IF EXISTS (SELECT 1 FROM public.job_attempts
                WHERE "isReplay" AND "replayOfId" IS NULL) THEN
        RAISE EXCEPTION 'V10: a replay attempt does not name what it replays';
    END IF;
END
$$;

-- ---------------------------------------------------------------------
-- 7. Session timezone. A connection whose TimeZone is not UTC would resolve
--    (now() AT TIME ZONE 'UTC') comparisons against the wrong wall clock for
--    every timestamp the application binds as LocalDateTime. The application
--    must configure this; the schema cannot.
-- ---------------------------------------------------------------------
SHOW TimeZone;