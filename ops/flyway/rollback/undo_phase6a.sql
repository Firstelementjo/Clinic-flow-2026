-- =====================================================================
-- undo_phase6a.sql
-- ARMED, ADDITIVE-SLICE UNDO for the Phase-6a workflow / SLA / collision
-- migrations. The Phase-6a addendum to ops/flyway/rollback/README-phase6a.md
-- is the runbook; this file is the mechanism.
--
-- Usage (read-only preview first -- always):
--   Get-Content -Raw ops\flyway\rollback\undo_phase6a.sql |
--     docker exec -i firstelement-postgres psql -U firstelement -d <throwaway> \
--       -v ON_ERROR_STOP=1 -v firstelement_undo_armed=on -f -
--
-- It is DESTRUCTIVE by design and therefore armed TWICE:
--   1. firstelement_undo_armed must be 'on' or 'yes'. Unarmed it prints
--      UNDO PHASE6A SKIPPED and touches nothing.
--   2. It REFUSES while ANY of the eight Phase-6a tables holds a row, and
--      prints the per-table row counts first. Destroying workflow execution
--      history or collision evidence is never an automatic consequence of
--      rolling back a schema, so it needs
--        -v confirm_data_loss=yes
--      on top of the arming.
--
-- It never touches flyway_schema_history, any baseline table, any index that
-- is not one of the eight tables' own, or any extension. Removing the
-- migrations from disk + `flyway repair` is a separate, deliberate operator act
-- (README-phase6a.md section 6).
--
-- PostgreSQL folds an unquoted identifier to lower case, so every type name
-- here is QUOTED. The Phase-5a rehearsal caught the unquoted version of this
-- bug: `DROP TYPE IF EXISTS public.OutboxState` matched nothing, printed
-- `NOTICE: ... does not exist, skipping`, left every enum behind, and the
-- script still reported success. Never write an unquoted CamelCase type name
-- in this file.
-- =====================================================================
\set ON_ERROR_STOP on

-- ---------------------------------------------------------------------
-- GATE 1: arming, in psql's own vocabulary.
--
-- The first version of this script read the flag with
-- current_setting('firstelement_undo_armed', true), which was WRONG and was
-- caught by the rehearsal: `-v name=value` defines a PSQL VARIABLE, not a
-- server GUC, so current_setting() returned NULL and the script reported
-- "UNDO PHASE6A SKIPPED" even when it had been armed correctly. A safety gate
-- that is always closed is at least safe, but a script whose arming flag cannot
-- be raised is unusable and its later guards never execute.
--
-- \if :{?name} asks psql whether the variable EXISTS, which is exactly the
-- question. The value is then normalised to the literal yes/no below so
-- nothing a caller types reaches the DO block as SQL.
-- ---------------------------------------------------------------------
\if :{?firstelement_undo_armed}
\else
  \echo 'UNDO PHASE6A SKIPPED: pass -v firstelement_undo_armed=on. This script drops 9 tables and 7 enums.'
  \quit
\endif

\if :{?confirm_data_loss}
\else
  \set confirm_data_loss no
\endif

-- Normalise the flags to the exact literals 'yes' / 'no'.
--
-- \if :var is TRUE only when the variable's value is a psql-recognised boolean
-- ('on', 'true', 'yes', '1' and so on). Anything else -- including a typo like
-- 'armed' -- evaluates false, so the script fails CLOSED. The value is then
-- passed into the DO block as a quoted literal rather than spliced as SQL.
\set armed_yes no
\if :firstelement_undo_armed
  \set armed_yes yes
\endif
\set loss_yes no
\if :confirm_data_loss
  \set loss_yes yes
\endif

-- psql does NOT substitute :variables inside a dollar-quoted body, so the flags
-- cannot be interpolated into the DO block directly (that attempt failed with
-- `syntax error at or near ":"`). They are handed over in a plain SQL statement
-- -- where substitution does happen -- as a session GUC, and the DO block reads
-- them back with current_setting(). That is also why the earlier
-- current_setting('firstelement_undo_armed') attempt did not work: a -v variable
-- is a PSQL variable, and only this explicit set_config() puts the value in the
-- server session.
SELECT set_config('firstelement.undo_armed',  :'armed_yes', false),
       set_config('firstelement.confirm_loss', :'loss_yes',  false);

DO $$
DECLARE
    armed            boolean := current_setting('firstelement.undo_armed')::boolean;
    confirm_loss     boolean := current_setting('firstelement.confirm_loss')::boolean;
    phase6a_tables   text[] := ARRAY[
        'collision_records',
        'sla_calendar_holidays',
        'sla_calendar_windows',
        'sla_calendars',
        'sla_policies',
        'workflow_definitions',
        'workflow_run_actions',
        'workflow_runs',
        'workflow_versions'
    ];
    present          text[];
    absent           text[];
    total_rows       bigint := 0;
    counts           text;
    cnt              bigint;
    i                int;
    baseline_tables  bigint;
    fk_orphans       bigint;
BEGIN
    -- ---------------------------------------------------------------------
    -- 1. Arming.
    -- ---------------------------------------------------------------------
    IF NOT armed THEN
        RAISE NOTICE 'UNDO PHASE6A SKIPPED: pass -v firstelement_undo_armed=on to enable.';
        RETURN;
    END IF;

    present := ARRAY(SELECT t FROM unnest(phase6a_tables) AS t
                      WHERE to_regclass('public.' || quote_ident(t)) IS NOT NULL);
    absent  := ARRAY(SELECT t FROM unnest(phase6a_tables) AS t
                      WHERE to_regclass('public.' || quote_ident(t)) IS NULL);

    IF array_length(present, 1) IS NULL THEN
        RAISE NOTICE 'UNDO PHASE6A: nothing to remove; the Phase-6a slice is not present.';
        RETURN;
    END IF;

    IF array_length(absent, 1) IS NOT NULL THEN
        RAISE EXCEPTION
            'UNDO PHASE6A REFUSED: the Phase-6a slice is PARTIAL. Present: %. Absent: %. '
            'A partial slice means a Phase-6a migration failed halfway -- fix the file, '
            'CHANGE ITS VERSION, and re-run. Do not remove half a slice.',
            array_to_string(present, ', '), array_to_string(absent, ', ');
    END IF;

    -- ---------------------------------------------------------------------
    -- 2. Row counts, printed BEFORE anything is dropped, always.
    --
    --    Counted with EXECUTE + format(), not with a SELECT that splices the
    --    table name into a quoted identifier. plpgsql cannot evaluate
    --    'public."' || u.t || '"' inside a plain SQL expression -- the
    --    concatenation is a plpgsql construct and needs a dynamic statement.
    --    The first version of this script did it inline and failed at runtime
    --    with `syntax error at or near "||"`, which meant the refusal that
    --    prints the row counts NEVER RAN. A guard that cannot execute is worse
    --    than no guard, so the rehearsal caught it before anything was dropped.
    -- ---------------------------------------------------------------------
    -- counts starts as '' , NOT NULL, and the loop appends unconditionally. The
    -- first version wrote `counts := coalesce(counts || ..., '')`, which is the
    -- exact case where coalesce's fallback FIRES: NULL || anything is NULL, so
    -- the FIRST table's row count was silently dropped from the message the
    -- operator is supposed to read before authorising a destructive action.
    -- Total rows was still right, so the refusal worked -- but its evidence was
    -- incomplete, and incomplete evidence on a destructive gate is the failure
    -- mode. The rehearsal caught it because the printed list was one table
    -- short of the table list.
    counts := '';
    FOR i IN 1 .. coalesce(array_length(present, 1), 0) LOOP
        EXECUTE format('SELECT count(*) FROM public.%I', present[i]) INTO cnt;
        counts := counts || format('%s=%s ', present[i], cnt);
        total_rows := total_rows + cnt;
    END LOOP;
    counts := btrim(counts);

    RAISE NOTICE 'UNDO PHASE6A: per-table row counts before removal: %', counts;

    -- ---------------------------------------------------------------------
    -- 3. The second gate. Data loss needs both switches, and the operator has
    --    just been shown what the data is.
    -- ---------------------------------------------------------------------
    IF total_rows > 0 AND NOT confirm_loss THEN
        RAISE EXCEPTION
            'UNDO PHASE6A REFUSED: % row(s) exist across the Phase-6a slice (%). '
            'Rolling back the schema is not a decision to destroy workflow execution history, '
            'SLA policies or collision evidence. Either leave the tables in place -- they are '
            'additive and inert to the old runtime -- or re-run with -v confirm_data_loss=yes '
            'and record why.',
            total_rows, counts;
    END IF;

    -- ---------------------------------------------------------------------
    -- 4. Baseline protection: this script must never reduce the Phase-2
    --    contract below its historical shape.
    -- ---------------------------------------------------------------------
    SELECT count(*) INTO baseline_tables
      FROM pg_tables
     WHERE schemaname = 'public'
       AND tablename NOT IN ('flyway_schema_history')
       AND tablename NOT IN (SELECT unnest(phase6a_tables));

    IF baseline_tables < 45 THEN
        RAISE EXCEPTION
            'UNDO PHASE6A REFUSED: only % baseline table(s) remain; the verified schema has 45. '
            'This undo is for the Phase-6a slice only -- do not run it against a partially '
            'migrated database.', baseline_tables;
    END IF;

    -- Any FK still pointing at a Phase-6a table means something outside this
    -- slice depends on it, and dropping would be surprising rather than undoing.
    SELECT count(*) INTO fk_orphans
      FROM pg_constraint con
     WHERE con.contype = 'f'
       AND con.confrelid IN (SELECT c.oid FROM pg_class c
                              JOIN pg_namespace n ON n.oid = c.relnamespace
                             WHERE n.nspname = 'public'
                               AND c.relname IN (SELECT unnest(phase6a_tables)))
       AND con.conrelid NOT IN (SELECT c.oid FROM pg_class c
                                 JOIN pg_namespace n ON n.oid = c.relnamespace
                                WHERE n.nspname = 'public'
                                  AND c.relname IN (SELECT unnest(phase6a_tables)));

    IF fk_orphans > 0 THEN
        RAISE EXCEPTION
            'UNDO PHASE6A REFUSED: % foreign key(s) from outside the Phase-6a slice still '
            'reference it. Another migration depends on these tables; undo them in reverse '
            'order or stop and reassess.', fk_orphans;
    END IF;

    -- ---------------------------------------------------------------------
    -- 5. Drop. Children first, then the CIRCULAR pair, then the rest. Types
    --    last, because the CHECK constraints and column types depend on them.
    --
    --    workflow_definitions.activeVersionId references workflow_versions AND
    --    workflow_versions.workflowDefinitionId references
    --    workflow_definitions. The two references are genuinely circular, so
    --    there is no drop order that satisfies both: dropping either table
    --    first fails with "cannot drop table ... because other objects depend on
    --    it". The rehearsal found exactly that. The fix is to break ONE link --
    --    the definition's pointer, which is a convenience pointer and carries
    --    no data of its own -- and then drop in a linear order. This is the only
    --    place in this script where a constraint is removed, and it is removed
    --    only as part of dropping the table it belongs to.
    -- ---------------------------------------------------------------------
    EXECUTE 'DROP TABLE IF EXISTS public."workflow_run_actions"';
    EXECUTE 'DROP TABLE IF EXISTS public."workflow_runs"';
    -- QUOTED constraint name. Unquoted, PostgreSQL folds
    -- workflow_definitions_activeVersionId_fkey to lower case, matches nothing,
    -- prints "does not exist, skipping", and the very next DROP TABLE then fails
    -- because the dependency is still there. This is the SAME bug class as the
    -- Phase-5a undo's unquoted DROP TYPE, in the same file family, and the
    -- rehearsal caught it the same way: by the drop failing loudly instead of
    -- quietly succeeding. Always quote.
    EXECUTE 'ALTER TABLE public."workflow_definitions" '
         || 'DROP CONSTRAINT IF EXISTS "workflow_definitions_activeVersionId_fkey"';
    EXECUTE 'DROP TABLE IF EXISTS public."workflow_versions"';
    EXECUTE 'DROP TABLE IF EXISTS public."workflow_definitions"';
    EXECUTE 'DROP TABLE IF EXISTS public."sla_calendar_holidays"';
    EXECUTE 'DROP TABLE IF EXISTS public."sla_calendar_windows"';
    EXECUTE 'DROP TABLE IF EXISTS public."sla_policies"';
    EXECUTE 'ALTER TABLE public."sla_calendars" '
         || 'DROP CONSTRAINT IF EXISTS "sla_calendars_supersededById_fkey"';
    EXECUTE 'DROP TABLE IF EXISTS public."sla_calendars"';
    EXECUTE 'DROP TABLE IF EXISTS public."collision_records"';

    -- The ONE object outside the nine tables this slice creates.
    -- conversations_id_organizationId_key is added by V20261005020000 as the
    -- composite foreign-key target that refuses a run pointing at another
    -- tenant's conversation. It is created with IF NOT EXISTS because it must not
    -- fail on a database that already has it, which means this script cannot
    -- know whether it created it -- so it DROPS it, and the fingerprint
    -- comparison after the undo is what proves the schema returned exactly.
    -- Recorded here because it is the only place this script touches a
    -- pre-existing baseline table, and an undo that quietly leaves it behind
    -- would leave a fingerprint that never matches its documented value.
    EXECUTE 'DROP INDEX IF EXISTS public."conversations_id_organizationId_key"';

    EXECUTE 'DROP TYPE IF EXISTS public."WorkflowActionOutcome"';
    EXECUTE 'DROP TYPE IF EXISTS public."WorkflowRunStatus"';
    EXECUTE 'DROP TYPE IF EXISTS public."WorkflowVersionState"';
    EXECUTE 'DROP TYPE IF EXISTS public."SlaClockType"';
    EXECUTE 'DROP TYPE IF EXISTS public."SlaCalendarState"';
    EXECUTE 'DROP TYPE IF EXISTS public."CollisionResolution"';
    EXECUTE 'DROP TYPE IF EXISTS public."CollisionScope"';

    -- ---------------------------------------------------------------------
    -- 6. Post-condition. The Phase-5a rehearsal proved this step is not
    --    optional: an unquoted DROP TYPE silently succeeded while leaving every
    --    enum behind, and only the fingerprint comparison caught it. So the
    --    check covers the TYPES as well as the tables.
    -- ---------------------------------------------------------------------
    IF EXISTS (SELECT 1 FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
               WHERE n.nspname = 'public'
                 AND c.relkind = 'r'
                 AND c.relname IN (SELECT unnest(phase6a_tables))) THEN
        RAISE EXCEPTION 'UNDO PHASE6A FAILED: a Phase-6a table survived the drop.';
    END IF;

    IF EXISTS (SELECT 1 FROM pg_type t
                JOIN pg_namespace n ON n.oid = t.typnamespace
               WHERE n.nspname = 'public'
                 AND t.typname IN ('WorkflowVersionState', 'WorkflowRunStatus',
                                   'WorkflowActionOutcome', 'SlaCalendarState',
                                   'SlaClockType', 'CollisionScope',
                                   'CollisionResolution')) THEN
        RAISE EXCEPTION 'UNDO PHASE6A FAILED: a Phase-6a enum type survived the drop.';
    END IF;

    IF EXISTS (SELECT 1 FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename IN (SELECT unnest(phase6a_tables))) THEN
        RAISE EXCEPTION
            'UNDO PHASE6A FAILED: an index belonging to a Phase-6a table survived the drop.';
    END IF;

    -- The composite tenant-key target on conversations must be gone too. Its
    -- survival is the exact defect found by the rehearsal: the first successful
    -- undo left it behind and the fingerprint did not return to the
    -- post-V20260814000100 value.
    IF to_regclass('public.conversations_id_organizationId_key') IS NOT NULL THEN
        RAISE EXCEPTION
            'UNDO PHASE6A FAILED: conversations_id_organizationId_key survived; the schema '
            'would not match the post-V20260814000100 fingerprint.';
    END IF;

    RAISE NOTICE 'PHASE6A_REMOVED: 9 tables and 7 enums dropped. % baseline table(s) intact.',
                 baseline_tables;
END
$$;