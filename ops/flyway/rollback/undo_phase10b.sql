-- =====================================================================
-- undo_phase10b.sql
--
-- ARMED, REFUSING, REVERSIBLE undo of the Phase-10b schema remediation batch:
--     V20261006100000__ai_approval_decision_biconditional_repair.sql   (D1)
--     V20261006110000__document_chunks_document_tenant_fkey.sql        (D2)
--     V20261006120000__api_keys_and_service_principals.sql             (D3)
--     V20261006130000__legal_holds_scope_pair_widening.sql             (D4)
--     V20261006140000__privacy_deletion_subject_reference.sql          (D5)
--
-- ORDER MATTERS: THIS SCRIPT RUNS BEFORE undo_phase7_10a.sql, NEVER AFTER.
--   D1 replaces a constraint ON ai_approvals, D4 one ON legal_holds and D5 a
--   column ON privacy_deletion_requests -- all Phase-7-10a tables. Undoing the
--   older slice first drops those tables and takes this batch's objects with
--   them, at which point undo_phase10b.sql refuses on its partial-slice guard.
--   Inside out, always.
--
-- WHAT IT IS FOR
--   ONE situation: the batch was applied, nothing has been cut over to it, and
--   no writer has ever put a row in anything it created. The Phase-5a/6a/7-10a
--   rule applies -- the batch is additive in effect, so "this table is wrong"
--   is almost never answered by dropping it -- and this script exists so the
--   judgement is made by MEASUREMENT.
--
-- WHAT IT IS NOT FOR
--   * NOT a rollback of a failed migration. Every one of the five is
--     transactional, so PostgreSQL rolled it back atomically and there is
--     nothing to undo.
--   * NOT a way to undo D1, D2, D4 or D5 once anything reads them. Undoing D1
--     makes a rejection that names its decider unstorable again; undoing D4
--     makes a narrow legal hold unplaceable again; undoing D5 makes a CUSTOMER
--     deletion inexpressible again. The guards below measure exactly that.
--
-- THE TWO DEFENCES, AND WHY BOTH ARE HERE (ADR-0009 section 5)
--   1. \set ON_ERROR_STOP on, so a RAISE anywhere stops the script. Without it
--      psql reports the error and runs the NEXT statement -- which is exactly
--      how the Phase-7-10a undo printed REFUSED and dropped 37 tables anyway.
--   2. The destructive section is gated on a psql VARIABLE that the guards
--      themselves set with \gset. \gset only defines the variable when the
--      query returns a row, so if any guard raised, execution never reaches the
--      gate -- and even with ON_ERROR_STOP somehow lost, the drops are
--      unreachable.
--   3. The post-condition at the end asserts on the STATE: tables, types,
--      functions, triggers, constraints, indexes and the column, each read back
--      from the catalog. A message is a claim; a surviving pg_class row is
--      evidence.
-- =====================================================================

-- The WHOLE BODY IS INSIDE THE CONDITIONAL, so an unarmed run is a clean no-op
-- and not a no-op followed by a syntax error on an unsubstituted variable.
\if :{?firstelement_undo_armed}

\set ON_ERROR_STOP on

-- Each flag gets its OWN \if: psql leaves :'name' completely unsubstituted when
-- name is undefined, so coalesce(:'confirm_data_loss','no') is a syntax error,
-- not a null. Plain statements, because psql does not substitute variables
-- inside a dollar-quoted body.
SELECT set_config('firstelement.undo_armed', :'firstelement_undo_armed', false);

\if :{?confirm_data_loss}
SELECT set_config('firstelement.confirm_data_loss', :'confirm_data_loss', false);
\endif

-- THE ARMING DECISION IS MADE HERE, IN PSQL, NOT IN A DO BLOCK.
--
-- The first version of this file armed the destructive section with
--
--     SELECT 'on' AS undo_go WHERE current_setting(...) = 'on' \gset
--     \if :{?undo_go}
--
-- and an unarmed run exited with psql's error code 3 while printing a correct
-- and complete "SKIPPED". The cause is `\gset` with a query that returns NO
-- ROWS: psql treats that as a script error, not as a falsy value, so
-- ON_ERROR_STOP aborts on a run that did nothing wrong. It is harmless -- no
-- object was touched -- but an unarmed invocation must be trustworthy from its
-- exit code, and "exit 3" is not a reading of "nothing happened".
--
-- `SELECT (boolean) AS undo_go` ALWAYS returns exactly one row, so \gset cannot
-- fail, and `\if :undo_go` is a real boolean test of the value. The guard DO
-- block below therefore runs only on the armed branch, where a refusal is a
-- refusal and must abort the script.
SELECT (current_setting('firstelement.undo_armed', true) = 'on') AS undo_go \gset

\if :undo_go

DO $$
DECLARE
    armed       boolean;
    confirmed   boolean;
    present     text;
    absent      text;
    counts      text := '';
    r           record;
    others      int;
    narrow_holds bigint;
    customer_subjects bigint;
    undecided   bigint;
BEGIN
    -- The arming test has already been made by the \if above, so this block only
    -- ever runs armed. The internal re-read is kept as a SECOND, INDEPENDENT
    -- check: if the \if were ever deleted from this file without this line, the
    -- block would refuse rather than proceed.
    armed := current_setting('firstelement.undo_armed', true) = 'on';
    IF NOT armed THEN
        RAISE EXCEPTION
            'UNDO PHASE10B REFUSED: the psql gate passed but the session GUC says "not armed". '
            'Two gates disagreeing is a reason to stop, not a reason to trust one of them.';
    END IF;

    confirmed := coalesce(current_setting('firstelement.confirm_data_loss', true), 'no') = 'yes';

    -- 1. THE BATCH MUST BE WHOLLY PRESENT. A half-applied batch means a
    --    migration failed mid-chain, and dropping half of it is not undoing it.
    --
    --    NB the condition is `absent IS NOT NULL OR present IS NULL`, NOT
    --    `present IS NULL OR absent IS NULL`. The throwaway rehearsal ran the
    --    SECOND form: with both tables present, absent is NULL, so
    --    `absent IS NULL` was true and the guard REFUSED a perfectly whole
    --    batch. The refusal was harmless -- nothing was dropped -- but it was a
    --    refusal for the wrong reason, which ADR-0009 section 5 calls a failed
    --    rehearsal. string_agg returns NULL over an empty set, so "absent" is
    --    the one that needs the IS NOT NULL test.
    SELECT string_agg(x, ', ') INTO present
      FROM unnest(ARRAY['api_keys', 'service_principals']) AS x
     WHERE to_regclass('public.' || quote_ident(x)) IS NOT NULL;

    SELECT string_agg(x, ', ') INTO absent
      FROM unnest(ARRAY['api_keys', 'service_principals']) AS x
     WHERE to_regclass('public.' || quote_ident(x)) IS NULL;

    IF absent IS NOT NULL OR present IS NULL THEN
        RAISE EXCEPTION
            'UNDO PHASE10B REFUSED: the batch is only partially present. Present: %. Missing: %. '
            'A half-applied batch means a migration failed mid-chain -- fix it and re-run rather '
            'than dropping half of it.', coalesce(present, '(none)'), coalesce(absent, '(none)');
    END IF;

    SELECT string_agg(x, ', ') INTO absent
      FROM unnest(ARRAY['document_chunks_document_tenant_fkey',
                        'privacy_deletion_requests_subject_reference_check',
                        'ai_approvals_approval_check',
                        'legal_holds_scope_pair_check']) AS x
     WHERE NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = x);

    IF absent IS NOT NULL THEN
        RAISE EXCEPTION
            'UNDO PHASE10B REFUSED: a constraint this batch installed is already absent: %. The '
            'fingerprint will not return exactly, so this is not the database the undo was written '
            'for.', absent;
    END IF;

    -- 2. BASELINE FLOOR. The post-Phase-7-10a state is 95 tables; refuse to run
    --    against anything smaller, because that is a partially migrated database.
    SELECT count(*) INTO others
      FROM pg_tables WHERE schemaname = 'public'
       AND tablename <> 'flyway_schema_history'
       AND tablename NOT IN ('api_keys', 'service_principals');

    IF others <> 95 THEN
        RAISE EXCEPTION
            'UNDO PHASE10B REFUSED: % non-batch tables exist; the post-Phase-7-10a state is 95. '
            'This database is not the one the undo was written for. (If the Phase-7-10a slice has '
            'ALREADY been undone, undo it no further -- its fingerprint checkpoint is 58 tables.)',
            others;
    END IF;

    -- 3. ROW COUNTS, PRINTED ALWAYS -- before any refusal, so the refusal is
    --    INFORMED. `counts` starts at '' and appends unconditionally, because
    --    coalesce(counts || ..., '') is exactly the case where coalesce's
    --    fallback fires and the first entry disappears.
    FOR r IN
        SELECT * FROM (VALUES ('api_keys'), ('service_principals')) AS t(name)
    LOOP
        EXECUTE format('SELECT count(*) FROM public.%I', r.name) INTO STRICT undecided;
        counts := counts || r.name || '=' || undecided || ' ';
    END LOOP;
    RAISE NOTICE 'UNDO PHASE10B row counts before removal: %', btrim(counts);

    -- 4. THE DATA GATE. A credential row is not tidy-able evidence, but it IS a
    --    secret-holding record, and an admin_audit_events row was written for
    --    every one of them -- so dropping the table silently orphans audit rows
    --    that name a resourceId no longer resolvable. Refuse unless told.
    IF confirmed = false AND btrim(counts) ~ '=[1-9]' THEN
        RAISE EXCEPTION
            'UNDO PHASE10B REFUSED: the batch holds rows (see the counts printed above), and every '
            'credential row has an admin_audit_events row naming it. Deleting the table leaves the '
            'audit trail pointing at a resourceId nothing resolves to. Re-run with '
            '-v confirm_data_loss=yes if you have approval and a backup, or roll the application '
            'back and leave the tables in place.';
    END IF;

    -- 5. D3/D4/D5 DATA GUARDS. Each one undoes a REPAIR, and an undo of a repair
    --    that real data now depends on destroys information.

    --    D5: dropping subjectCustomerId discards which customer a deletion
    --    request was about. Unrecoverable: the value is not derivable.
    SELECT count(*) INTO customer_subjects
      FROM public.privacy_deletion_requests
     WHERE "subjectCustomerId" IS NOT NULL;

    IF customer_subjects > 0 THEN
        RAISE EXCEPTION
            'UNDO PHASE10B REFUSED: % privacy_deletion_requests row(s) name a CUSTOMER subject. '
            'Restoring NOT NULL on "subjectUserId" would refuse them and dropping the column would '
            'discard WHICH CUSTOMER each request was about -- information that is not derivable from '
            'anything else. Undo D1/D2/D3/D4 individually instead.', customer_subjects;
    END IF;

    --    D4: the original pair CHECK has no arm for TABLE or RECORD, so a narrow
    --    hold cannot exist once it is restored. Restoring it therefore REFUSES.
    --    Better to refuse here, before touching anything.
    SELECT count(*) INTO narrow_holds
      FROM public.legal_holds
     WHERE "scopeType" IN ('TABLE'::"public"."LegalHoldScopeType",
                           'RECORD'::"public"."LegalHoldScopeType");

    IF narrow_holds > 0 THEN
        RAISE EXCEPTION
            'UNDO PHASE10B REFUSED: % narrow (TABLE or RECORD) legal hold(s) exist. Restoring the '
            'original legal_holds_scope_pair_check would refuse them, and they are the most '
            'protective holds there are. Undo D1/D2/D3/D5 individually instead.', narrow_holds;
    END IF;

    --    D1: the original CHECK refuses a REJECTED or REVOKED row that carries a
    --    decision, so restoring it needs those rows cleared -- which is the
    --    data loss the whole batch exists to stop. Refuse.
    SELECT count(*) INTO undecided
      FROM public.ai_approvals
     WHERE "decidedAt" IS NOT NULL
       AND status IN ('REJECTED'::"public"."AiApprovalStatus",
                      'REVOKED'::"public"."AiApprovalStatus");

    IF undecided > 0 THEN
        RAISE EXCEPTION
            'UNDO PHASE10B REFUSED: % ai_approvals row(s) are REJECTED or REVOKED and carry a '
            'decision record. Restoring the original CHECK would require CLEARING it, which is '
            'exactly the defect ADR-0011 section 1 rules against. Undo D2/D3/D4/D5 individually '
            'instead.', undecided;
    END IF;

    -- 6. NOTHING OUTSIDE THE BATCH MAY DEPEND ON IT. api_keys has a composite FK
    --    into service_principals, which is INSIDE the batch; any other table
    --    pointing in means a later migration, and reverse order.
    IF EXISTS (SELECT 1 FROM pg_constraint con
                WHERE con.contype = 'f'
                  AND con.confrelid IN (SELECT oid FROM pg_class
                                         WHERE oid IN ('public.api_keys'::regclass,
                                                       'public.service_principals'::regclass))
                  AND con.conrelid NOT IN ('public.api_keys'::regclass,
                                           'public.service_principals'::regclass)) THEN
        RAISE EXCEPTION
            'UNDO PHASE10B REFUSED: an object OUTSIDE this batch has a foreign key into it. That '
            'means reverse order, not a surprise drop. Find the referencing migration and undo it '
            'first.';
    END IF;

    RAISE NOTICE 'UNDO PHASE10B armed and cleared. Proceeding.';
END
$$;

-- =====================================================================
-- REVERSE VERSION ORDER.
-- =====================================================================

-- ---------------------------------------------------------------------
-- V20261006140000 (D5): privacy deletion subject reference.
-- The FK and the index first, then the CHECK, then the column, then the NOT
-- NULL -- in that order, because the column cannot go while an index and a
-- CHECK still reference it.
-- ---------------------------------------------------------------------
DROP INDEX IF EXISTS "public"."privacy_deletion_requests_organizationId_subjectCustomerId_idx";
ALTER TABLE "public"."privacy_deletion_requests"
    DROP CONSTRAINT IF EXISTS "privacy_deletion_requests_subject_customer_tenant_fkey";
ALTER TABLE "public"."privacy_deletion_requests"
    DROP CONSTRAINT IF EXISTS "privacy_deletion_requests_subject_reference_check";
ALTER TABLE "public"."privacy_deletion_requests"
    DROP COLUMN IF EXISTS "subjectCustomerId";
-- Restored byte for byte from V20261005050000. The guard above has already
-- established that no row carries a customer subject, so this cannot fail.
ALTER TABLE "public"."privacy_deletion_requests"
    ALTER COLUMN "subjectUserId" SET NOT NULL;
DROP INDEX IF EXISTS "public"."customers_id_organizationId_key";

-- ---------------------------------------------------------------------
-- V20261006130000 (D4): legal hold scope pair.
-- Restored byte for byte from V20261005050000 -- which, as the header of that
-- constraint says, makes a TABLE or RECORD hold unplaceable again. The guard
-- above refuses if one exists, so this cannot fail silently.
-- ---------------------------------------------------------------------
ALTER TABLE "public"."legal_holds"
    DROP CONSTRAINT IF EXISTS "legal_holds_scope_pair_check";
ALTER TABLE "public"."legal_holds"
    ADD CONSTRAINT "legal_holds_scope_pair_check"
    CHECK (("scopeType" = 'TENANT'::"public"."LegalHoldScopeType")
               AND "scopeRef" IS NULL
        OR ("scopeType" = 'PROJECT'::"public"."LegalHoldScopeType")
               AND "projectId" IS NOT NULL);

-- ---------------------------------------------------------------------
-- V20261006120000 (D3): api_keys and service_principals.
-- Triggers, then the tables (reverse dependency), then the functions, then the
-- enums. QUOTED -- the Phase-5a bug class was an unquoted DROP TYPE that folded
-- to lower case, matched nothing, and left four enums behind while the script
-- reported success.
-- ---------------------------------------------------------------------
DROP TRIGGER IF EXISTS "api_keys_hash_guard" ON "public"."api_keys";
DROP TRIGGER IF EXISTS "api_keys_lifecycle_audit" ON "public"."api_keys";
DROP TRIGGER IF EXISTS "service_principals_lifecycle_audit" ON "public"."service_principals";

DROP TABLE IF EXISTS "public"."api_keys";
DROP TABLE IF EXISTS "public"."service_principals";

DROP FUNCTION IF EXISTS "public"."firstelement_principal_lifecycle_audit"();
DROP FUNCTION IF EXISTS "public"."firstelement_api_key_hash_guard"();

DROP TYPE IF EXISTS "public"."ApiKeyStatus";
DROP TYPE IF EXISTS "public"."ServicePrincipalStatus";

-- ---------------------------------------------------------------------
-- V20261006110000 (D2): the composite chunk/document tenant key.
-- The single-column document_chunks_documentId_fkey is NOT touched: this batch
-- never changed it.
-- ---------------------------------------------------------------------
ALTER TABLE "public"."document_chunks"
    DROP CONSTRAINT IF EXISTS "document_chunks_document_tenant_fkey";
DROP INDEX IF EXISTS "public"."documents_id_organizationId_key";

-- ---------------------------------------------------------------------
-- V20261006100000 (D1): the decision biconditional.
-- Restored byte for byte from V20261005100000.
-- ---------------------------------------------------------------------
ALTER TABLE "public"."ai_approvals"
    DROP CONSTRAINT IF EXISTS "ai_approvals_approval_check";
ALTER TABLE "public"."ai_approvals"
    ADD CONSTRAINT "ai_approvals_approval_check"
    CHECK (("status" = 'APPROVED'::"public"."AiApprovalStatus")
           = ("decidedAt" IS NOT NULL));

-- ---------------------------------------------------------------------
-- POST-CONDITION. Assert on the STATE (ADR-0009 section 5). Tables AND types
-- AND functions AND triggers AND constraints AND indexes AND the column --
-- because the Phase-5a undo reported success while leaving four enums behind,
-- and a fingerprint that does not return exactly means something is left.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    leftover text;
    tables_left int;
    enums_left int;
    checks_left int;
BEGIN
    SELECT string_agg(x, ', ') INTO leftover
      FROM unnest(ARRAY['api_keys', 'service_principals']) AS x
     WHERE to_regclass('public.' || quote_ident(x)) IS NOT NULL;

    IF leftover IS NOT NULL THEN
        RAISE EXCEPTION 'PHASE10B_UNDO_INCOMPLETE: these tables survive: %', leftover;
    END IF;

    SELECT string_agg(x, ', ') INTO leftover
      FROM unnest(ARRAY['ApiKeyStatus', 'ServicePrincipalStatus']) AS x
     WHERE EXISTS (SELECT 1 FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
                    WHERE n.nspname = 'public' AND t.typname = x);

    IF leftover IS NOT NULL THEN
        RAISE EXCEPTION 'PHASE10B_UNDO_INCOMPLETE: these types survive: %', leftover;
    END IF;

    SELECT string_agg(x, ', ') INTO leftover
      FROM unnest(ARRAY['firstelement_api_key_hash_guard',
                        'firstelement_principal_lifecycle_audit']) AS x
     WHERE to_regprocedure('public.' || x || '()') IS NOT NULL;

    IF leftover IS NOT NULL THEN
        RAISE EXCEPTION
            'PHASE10B_UNDO_INCOMPLETE: these functions survive: % -- a function with no table is '
            'invisible to the fingerprint and would be reported as a clean undo.', leftover;
    END IF;

    SELECT string_agg(x, ', ') INTO leftover
      FROM unnest(ARRAY['api_keys_hash_guard', 'api_keys_lifecycle_audit',
                        'service_principals_lifecycle_audit']) AS x
     WHERE EXISTS (SELECT 1 FROM pg_trigger tg
                     JOIN pg_class c ON c.oid = tg.tgrelid
                     JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'public' AND tg.tgname = x AND NOT tg.tgisinternal);

    IF leftover IS NOT NULL THEN
        RAISE EXCEPTION
            'PHASE10B_UNDO_INCOMPLETE: these triggers survive: % -- their tables are gone, so '
            'nothing reports them.', leftover;
    END IF;

    -- The PRE-EXISTING single-column chunk FK must still be there. It is the
    -- deletion-propagation contract, and an undo that removed it would be a
    -- second, unreported regression.
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conrelid = 'public.document_chunks'::regclass
                      AND conname = 'document_chunks_documentId_fkey') THEN
        RAISE EXCEPTION
            'PHASE10B_UNDO_INCOMPLETE: document_chunks_documentId_fkey is gone. This batch never '
            'touched it and neither must its undo.';
    END IF;

    -- The pre-existing composite users FK must still be there too.
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conrelid = 'public.privacy_deletion_requests'::regclass
                      AND conname = 'privacy_deletion_requests_subject_tenant_fkey') THEN
        RAISE EXCEPTION
            'PHASE10B_UNDO_INCOMPLETE: privacy_deletion_requests_subject_tenant_fkey is gone. This '
            'batch never touched it and neither must its undo.';
    END IF;

    -- The restored expressions must be the ORIGINAL ones, checked against the
    -- exact rendering PostgreSQL produces for the pre-batch definitions. NB the
    -- SPACE after the equals sign: the first version of this check omitted it,
    -- so it never matched and the undo reported
    -- PHASE10B_UNDO_INCOMPLETE on a database it had undone correctly. A guard that
    -- cannot fire is not a guard.
    IF NOT ((SELECT pg_get_constraintdef(oid) FROM pg_constraint
              WHERE conrelid = 'public.ai_approvals'::regclass
                AND conname = 'ai_approvals_approval_check')
            LIKE '%= ''APPROVED''::"AiApprovalStatus") = ("decidedAt" IS NOT NULL)%') THEN
        RAISE EXCEPTION
            'PHASE10B_UNDO_INCOMPLETE: ai_approvals_approval_check is not the pre-batch '
            'single-literal expression, so the fingerprint will not return exactly.';
    END IF;

    -- ...and the pattern is proven to discriminate against the form this batch
    -- REMOVED, so "matches the old one" cannot be true of anything.
    IF ((SELECT pg_get_constraintdef(oid) FROM pg_constraint
          WHERE conrelid = 'public.ai_approvals'::regclass
            AND conname = 'ai_approvals_approval_check')
        LIKE '%= ANY (ARRAY[''APPROVED''::%') THEN
        RAISE EXCEPTION
            'PHASE10B_UNDO_INCOMPLETE: ai_approvals_approval_check still carries the corrected '
            'IN-list form, so the batch was not actually undone.';
    END IF;

    -- The pre-batch scope-pair expression names only TENANT and PROJECT. The
    -- CORRECTED one contains a TABLE arm, so its presence is the failure.
    --
    -- NB this was INVERTED in the first version: it raised when the definition
    -- did NOT contain TABLE, i.e. it raised on a correct undo. Caught by the
    -- throwaway rehearsal's STEP 5, and recorded here because the symptom was a
    -- confident PHASE10B_UNDO_INCOMPLETE on a database that had been undone
    -- correctly -- exactly the shape of "an assertion that fails for the wrong
    -- reason" ADR-0009 §5 warns about.
    IF ((SELECT pg_get_constraintdef(oid) FROM pg_constraint
          WHERE conrelid = 'public.legal_holds'::regclass
            AND conname = 'legal_holds_scope_pair_check')
        LIKE '%TABLE%') THEN
        RAISE EXCEPTION
            'PHASE10B_UNDO_INCOMPLETE: legal_holds_scope_pair_check still carries a TABLE arm, so '
            'the batch was not actually undone and a narrow hold is still placeable.';
    END IF;

    -- The object counts, which are the numbers a fingerprint is made of.
    SELECT count(*) INTO tables_left
      FROM pg_tables WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history';
    IF tables_left <> 95 THEN
        RAISE EXCEPTION
            'PHASE10B_UNDO_INCOMPLETE: % application tables remain; the post-Phase-7-10a state is '
            '95.', tables_left;
    END IF;

    SELECT count(DISTINCT t.typname) INTO enums_left
      FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
      JOIN pg_enum e ON e.enumtypid = t.oid
     WHERE n.nspname = 'public';
    IF enums_left <> 55 THEN
        RAISE EXCEPTION
            'PHASE10B_UNDO_INCOMPLETE: % enum types remain; the post-Phase-7-10a state is 55.',
            enums_left;
    END IF;

    SELECT count(*) INTO checks_left
      FROM pg_constraint con
        JOIN pg_class c ON c.oid = con.conrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public' AND con.contype = 'c';
    IF checks_left <> 402 THEN
        RAISE EXCEPTION
            'PHASE10B_UNDO_INCOMPLETE: % CHECK constraints remain; the post-Phase-7-10a state is '
            '402.', checks_left;
    END IF;

    RAISE NOTICE
        'PHASE10B_REMOVED: 2 tables, 2 enums, 2 functions and 3 triggers dropped; 1 column and '
        '1 NOT NULL reverted; 3 indexes removed from pre-existing tables; 2 CHECK constraints '
        'restored to their pre-batch expressions. 95 tables / 55 enums / 402 CHECKs remain, which '
        'is the post-Phase-7-10a state.';
END
$$;

\else
  \echo 'UNDO PHASE10B SKIPPED: the psql gate read the session GUC as "not armed". Nothing was touched. Pass -v firstelement_undo_armed=on to arm it.'
\endif

\else
  \echo 'UNDO PHASE10B SKIPPED: pass -v firstelement_undo_armed=on to arm it. Nothing was touched.'
\endif

-- There is deliberately no "ABORTED BEFORE ANY DROP" branch any more.
--
-- With the arming decision made by `\if :undo_go` BEFORE the guard DO block, a
-- guard refusal no longer reaches any `\else`: `\set ON_ERROR_STOP on` aborts
-- the script at the RAISE, so the destructive section is not merely skipped but
-- never started, and psql exits non-zero. That is a stronger arrangement than
-- the one it replaced -- a branch that merely echoed a message and then fell
-- through to the end of the file -- and it is why the rehearsal asserts the exit
-- CODE as well as the state (ADR-0009 §5).
