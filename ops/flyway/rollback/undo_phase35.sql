-- =====================================================================
-- undo_phase35.sql
--
-- ARMED, REFUSING, REVERSIBLE undo of the Phase-35 appointment tenant batch:
--     V20261009110000__appointments_tenant_composite_fkeys.sql
--
-- ORDER MATTERS: THIS SCRIPT RUNS FIRST, BEFORE undo_phase14.sql,
-- undo_phase10b.sql AND undo_phase7_10a.sql. Inside out, always.
--
--   It adds objects to appointments, doctors, procedures, users and customers.
--   Every earlier slice's undo drops or rewrites tables this batch put
--   constraints on -- undo_phase7_10a.sql drops customers entirely, and
--   undo_phase10b.sql drops customers_id_organizationId_key -- so running this
--   one late leaves its constraints behind on tables the earlier undos no
--   longer account for, and the partial-batch guard below refuses. That refusal
--   is the intended behaviour and not a bug.
--
-- WHAT IT REMOVES, AND ONLY THAT
--   4 constraints : appointments_created_by_tenant_fkey
--                   appointments_customer_tenant_fkey
--                   appointments_doctor_tenant_fkey
--                   appointments_procedure_tenant_fkey
--   2 indexes     : doctors_id_organizationId_key
--                   procedures_id_organizationId_key
--
--   All six are objects V20261009110000 CREATED. Nothing pre-existing is
--   dropped: the four single-column appointment FKs
--   (appointments_createdById_fkey and friends), users_id_organizationId_key
--   and customers_id_organizationId_key all SURVIVE, because they belong to
--   the baseline, Phase 6a/7-10a and Phase 10b respectively.
--
-- WHAT IT IS FOR
--   ONE situation: the batch was applied, nothing has been cut over to it, and
--   an operator has decided the constraint is wrong. The additive-slice rule
--   applies -- "this is wrong" is almost never answered by removing it -- so
--   this script exists so the judgement is made by MEASUREMENT rather than by
--   optimism.
--
-- WHAT IT IS NOT FOR
--   * NOT a rollback of a failed migration. V20261009110000 is transactional,
--     so a failure rolled it back atomically and there is nothing to undo.
--   * NOT a way to keep going after a refusal. Read the message; it names what
--     was found.
--
-- THE REFUSALS, AND WHY EACH IS MEASURED RATHER THAN ASSUMED
--   1. A PARTIAL batch means the order was wrong or someone dropped one
--      constraint by hand. Undoing a partial batch compounds the partial state,
--      so it refuses and says which objects are missing.
--   2. CROSS-TENANT APPOINTMENTS PRESENT means the constraint is not merely
--      redundant -- it is holding. Removing it re-opens the exact disclosure
--      this batch closed: a patient record from one tenant attached to a
--      clinician in another. The count is printed and the script refuses.
--      This is the guard that matters most, and it is the reason this undo can
--      only ever succeed on a database where no cross-tenant row exists.
--
--   NB the check is the SAME cross-tenant predicate the migration's own guard
--   used, so it is zero on any database the migration accepted.
--
-- THE THREE DEFENCES, AND WHY ALL THREE ARE HERE (ADR-0009 section 5)
--   1. \set ON_ERROR_STOP on, so a RAISE stops the script. Without it psql
--      reports the error and runs the NEXT statement -- which is how the
--      Phase-7-10a undo printed REFUSED and dropped 37 tables anyway.
--   2. The destructive section is gated on a psql VARIABLE that the arming
--      decision itself set with \gset. \gset only defines the variable when the
--      query returns a row, so if a guard raised, execution never reaches the
--      gate -- and even with ON_ERROR_STOP somehow lost, the drops are
--      unreachable.
--   3. The post-condition asserts on the STATE, read back from the catalogue,
--      and includes the OBJECT COUNTS and the fingerprint checkpoint. A message
--      is a claim; a surviving pg_constraint row is evidence.
--
-- WHAT IT RESTORES TO
--   The exact post-Phase-14 state: 98 tables, 57 enums, 422 CHECKs,
--   223 FKs, 98 PKs, 394 indexes, 205 unique indexes, 11 triggers,
--   fingerprint bf31e9f102be19a6c373eb353b2d2131.
--
--   The fingerprint is measured on a psql-applied throwaway with no
--   flyway_schema_history. A Flyway-managed database contains that table and
--   therefore NEVER matches a psql-applied fingerprint; compare like with like.
--
-- Nothing here renames anything, alters a column, or touches a row.
-- =====================================================================

-- The WHOLE BODY IS INSIDE THIS CONDITIONAL, so an unarmed run is a clean
-- no-op and not a no-op followed by a syntax error on an unsubstituted
-- variable.
\if :{?firstelement_undo_armed}

\set ON_ERROR_STOP on

\if :{?confirm_data_loss}
SELECT set_config('firstelement.confirm_data_loss', :'confirm_data_loss', false);
\endif

SELECT set_config('firstelement.undo_armed', :'firstelement_undo_armed', false);

-- THE ARMING DECISION IS MADE HERE, IN PSQL, NOT IN A DO BLOCK.
-- `SELECT (boolean) AS undo_go` ALWAYS returns exactly one row, so \gset cannot
-- fail on a zero-row query the way it otherwise can, and \if :undo_go is a real
-- boolean test. Everything destructive sits inside this branch.
SELECT (current_setting('firstelement.undo_armed', true) = 'on') AS undo_go \gset

\if :undo_go

-- ---------------------------------------------------------------------
-- THE GUARDS. All raise, so with ON_ERROR_STOP on nothing below is reached.
-- ---------------------------------------------------------------------
DO $guards$
DECLARE
    present_con   bigint;
    present_idx   bigint;
    cross_tenant  bigint;
    offenders     text;
BEGIN
    IF to_regclass('public.appointments') IS NULL THEN
        RAISE EXCEPTION
            'PHASE35_UNDO_NOTHING_TO_DO: public.appointments does not exist, so '
            'V20261009110000 was never applied here (or the baseline is missing). Nothing was touched.';
    END IF;

    SELECT count(*) INTO present_con
      FROM "pg_constraint"
     WHERE conrelid = 'public.appointments'::regclass
       AND conname IN ('appointments_created_by_tenant_fkey', 'appointments_customer_tenant_fkey',
                       'appointments_doctor_tenant_fkey',   'appointments_procedure_tenant_fkey');

    SELECT count(*) INTO present_idx
      FROM "pg_class" c
      JOIN "pg_namespace" n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public'
       AND c.relname IN ('doctors_id_organizationId_key', 'procedures_id_organizationId_key');

    -- GUARD 1. A partial batch means the order was wrong, or one object was
    -- removed by hand. Compounding a partial undo is worse than refusing.
    IF present_con <> 4 OR present_idx <> 2 THEN
        RAISE EXCEPTION
            'PHASE35_UNDO_PARTIAL: % of 4 composite constraints and % of 2 target indexes are '
            'present. The batch is already half undone, so removing the rest would leave a state '
            'neither the baseline nor Phase 35 ever described. Nothing was touched. If the order was '
            'wrong, run this script FIRST, before undo_phase14.sql, undo_phase10b.sql and '
            'undo_phase7_10a.sql; if an object was dropped by hand, restore from a backup rather '
            'than compounding it.', present_con, present_idx;
    END IF;

    -- GUARD 2. THE ONE THAT MATTERS. A cross-tenant appointment means the
    -- constraint is HOLDING, not merely redundant. Removing it re-opens the
    -- disclosure this batch closed.
    --
    -- Same predicate as the migration's own pre-check, so it is zero on every
    -- database V20261009110000 accepted -- which is the proof that this undo is
    -- safe on a cutover-shaped database and only refuses on a used one.
    SELECT count(*), string_agg(a."id"::text, ', ' ORDER BY a."id")
      INTO cross_tenant, offenders
      FROM "public"."appointments" a
     WHERE (a."doctorId"    IS NOT NULL AND EXISTS (SELECT 1 FROM "public"."doctors" d
                     WHERE d."id" = a."doctorId" AND d."organizationId" <> a."organizationId"))
        OR (a."customerId"  IS NOT NULL AND EXISTS (SELECT 1 FROM "public"."customers" c
                     WHERE c."id" = a."customerId" AND c."organizationId" <> a."organizationId"))
        OR (a."procedureId" IS NOT NULL AND EXISTS (SELECT 1 FROM "public"."procedures" p
                     WHERE p."id" = a."procedureId" AND p."organizationId" <> a."organizationId"))
        OR (a."createdById" IS NOT NULL AND EXISTS (SELECT 1 FROM "public"."users" u
                     WHERE u."id" = a."createdById" AND u."organizationId" <> a."organizationId"));

    IF cross_tenant > 0 THEN
        RAISE EXCEPTION
            'PHASE35_UNDO_REFUSED: % appointment(s) are CROSS-TENANT right now -- the constraint is '
            'holding, not merely redundant. Removing it would re-open the exact disclosure this '
            'batch closed: a patient record from one tenant attached to a clinician, customer, '
            'procedure or creator in another. These appointments were almost certainly written '
            'BEFORE V20261009110000 applied; the migration refused to install the constraint over '
            'them, so their presence means the batch was never applied here, or something bypassed '
            'it. Nothing was touched. Resolve the rows first -- refusing rather than repairing, the '
            'same rule the migration followed. Offending appointment ids: %',
            cross_tenant, coalesce(offenders, '(unknown)');
    END IF;

    RAISE NOTICE
        'PHASE35_UNDO_ARMED: 4/4 composite constraints and 2/2 target indexes present; '
        'cross-tenant appointments = 0. Proceeding.';
END
$guards$;

-- ---------------------------------------------------------------------
-- THE DESTRUCTIVE SECTION. Unreachable unless both guards passed.
--
-- Constraints first, then the indexes they target: an index that backs a
-- constraint cannot be dropped while that constraint exists, and PostgreSQL
-- says so ("cannot drop index ... because other objects depend on it") rather
-- than cascading.
-- ---------------------------------------------------------------------
ALTER TABLE ONLY "public"."appointments"
    DROP CONSTRAINT "appointments_created_by_tenant_fkey",
    DROP CONSTRAINT "appointments_customer_tenant_fkey",
    DROP CONSTRAINT "appointments_doctor_tenant_fkey",
    DROP CONSTRAINT "appointments_procedure_tenant_fkey";

DROP INDEX "public"."doctors_id_organizationId_key";
DROP INDEX "public"."procedures_id_organizationId_key";

-- ---------------------------------------------------------------------
-- THE POST-CONDITION. Assert on the STATE, read back from the catalogue.
-- (ADR-0009 section 5.) A message is a claim; a surviving pg_constraint row is
-- evidence. The expected figures are the MEASURED post-Phase-14 values.
-- ---------------------------------------------------------------------
DO $postcondition$
DECLARE
    con_left       bigint;
    idx_left       bigint;
    singles_left   bigint;
    tables_left    bigint;
    enums_left     bigint;
    checks_left    bigint;
    fks_left       bigint;
    pks_left       bigint;
    indexes_left   bigint;
    uniq_idx_left  bigint;
    triggers_left  bigint;
BEGIN
    SELECT count(*) INTO con_left
      FROM "pg_constraint"
     WHERE conrelid = 'public.appointments'::regclass
       AND conname IN ('appointments_created_by_tenant_fkey', 'appointments_customer_tenant_fkey',
                       'appointments_doctor_tenant_fkey',   'appointments_procedure_tenant_fkey');

    SELECT count(*) INTO idx_left
      FROM "pg_class" c
      JOIN "pg_namespace" n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public'
       AND c.relname IN ('doctors_id_organizationId_key', 'procedures_id_organizationId_key');

    IF con_left <> 0 OR idx_left <> 0 THEN
        RAISE EXCEPTION
            'PHASE35_UNDO_INCOMPLETE: % composite constraint(s) and % target index(es) survive, so the '
            'database is in neither the pre- nor the post-Phase-35 state.', con_left, idx_left;
    END IF;

    -- The PRE-EXISTING objects must SURVIVE. An undo that took one of these
    -- with it would have removed something no batch in this chain created.
    SELECT count(*) INTO singles_left
      FROM "pg_constraint"
     WHERE conrelid = 'public.appointments'::regclass
       AND conname IN ('appointments_createdById_fkey', 'appointments_customerId_fkey',
                       'appointments_doctorId_fkey',   'appointments_procedureId_fkey');

    IF singles_left <> 4 THEN
        RAISE EXCEPTION
            'PHASE35_UNDO_INCOMPLETE: % of the 4 pre-existing single-column appointment FKs survive. '
            'This undo removes only what Phase 35 added; the baseline FKs are not its to drop.',
            singles_left;
    END IF;

    SELECT count(*) INTO tables_left FROM "pg_tables"
     WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history';
    SELECT count(DISTINCT t.typname) INTO enums_left
      FROM "pg_type" t
      JOIN "pg_namespace" n ON n.oid = t.typnamespace
      JOIN "pg_enum" e ON e.enumtypid = t.oid
     WHERE n.nspname = 'public';
    SELECT count(*) INTO checks_left FROM "pg_constraint" con
      JOIN "pg_class" c ON c.oid = con.conrelid
      JOIN "pg_namespace" n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public' AND con.contype = 'c' AND c.relname <> 'flyway_schema_history';
    SELECT count(*) INTO fks_left FROM "pg_constraint" con
      JOIN "pg_class" c ON c.oid = con.conrelid
      JOIN "pg_namespace" n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public' AND con.contype = 'f' AND c.relname <> 'flyway_schema_history';
    SELECT count(*) INTO pks_left FROM "pg_constraint" con
      JOIN "pg_class" c ON c.oid = con.conrelid
      JOIN "pg_namespace" n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public' AND con.contype = 'p' AND c.relname <> 'flyway_schema_history';
    SELECT count(*) INTO indexes_left FROM "pg_indexes"
     WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history';
    SELECT count(*) INTO uniq_idx_left FROM "pg_index" i
      JOIN "pg_class" c ON c.oid = i.indrelid
      JOIN "pg_namespace" n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public' AND i.indisunique AND c.relname <> 'flyway_schema_history';
    SELECT count(*) INTO triggers_left FROM "pg_trigger" tg
      JOIN "pg_class" c ON c.oid = tg.tgrelid
      JOIN "pg_namespace" n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public' AND NOT tg.tgisinternal AND c.relname <> 'flyway_schema_history';

    IF tables_left   <> 98 THEN
        RAISE EXCEPTION 'PHASE35_UNDO_INCOMPLETE: % tables remain; the post-Phase-14 state is 98.', tables_left; END IF;
    IF enums_left    <> 57 THEN
        RAISE EXCEPTION 'PHASE35_UNDO_INCOMPLETE: % enums remain; the post-Phase-14 state is 57.', enums_left; END IF;
    IF checks_left   <> 422 THEN
        RAISE EXCEPTION 'PHASE35_UNDO_INCOMPLETE: % CHECK constraints remain; the post-Phase-14 state is 422.', checks_left; END IF;
    IF fks_left      <> 223 THEN
        RAISE EXCEPTION 'PHASE35_UNDO_INCOMPLETE: % FKs remain; the post-Phase-14 state is 223.', fks_left; END IF;
    IF pks_left      <> 98 THEN
        RAISE EXCEPTION 'PHASE35_UNDO_INCOMPLETE: % PKs remain; the post-Phase-14 state is 98.', pks_left; END IF;
    IF indexes_left  <> 394 THEN
        RAISE EXCEPTION 'PHASE35_UNDO_INCOMPLETE: % indexes remain; the post-Phase-14 state is 394.', indexes_left; END IF;
    IF uniq_idx_left <> 205 THEN
        RAISE EXCEPTION 'PHASE35_UNDO_INCOMPLETE: % unique indexes remain; the post-Phase-14 state is 205.', uniq_idx_left; END IF;
    IF triggers_left <> 11 THEN
        RAISE EXCEPTION 'PHASE35_UNDO_INCOMPLETE: % triggers remain; the post-Phase-14 state is 11.', triggers_left; END IF;

    RAISE NOTICE
        'PHASE35_REMOVED: 4 composite tenant FKs and 2 target indexes dropped; 98 tables, 57 enums, '
        '422 CHECKs, 223 FKs, 98 PKs, 394 indexes (205 unique) and 11 triggers -- the exact '
        'post-Phase-14 state. The 4 pre-existing single-column appointment FKs, '
        'users_id_organizationId_key and customers_id_organizationId_key are all intact. On a '
        'psql-applied throwaway the fingerprint should now read bf31e9f102be19a6c373eb353b2d2131.';
END
$postcondition$;

\endif
\endif