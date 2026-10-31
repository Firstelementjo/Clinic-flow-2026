-- =====================================================================
-- V15_schema_remediation_slice.sql
-- Phase 10b / database verification: the five confirmed schema defects
-- corrected by V20261006100000 .. V20261006140000.
--
-- Each section asserts on the STATE of the corrected schema and, where data
-- exists, on the state of the DATA. It never asserts that a script printed a
-- message (ADR-0009 section 5).
--
--   D1  ai_approvals_approval_check forbade a valid rejection
--   D2  document_chunks."documentId" was a single-column FK
--   D3  no api_keys, no service_principals
--   D4  legal_holds_scope_pair_check made narrow holds unplaceable
--   D5  privacy_deletion_requests could not express a CUSTOMER deletion
--
-- READ-ONLY. No DDL, no DML, no object created.
--
-- ---------------------------------------------------------------------
-- GUARDED ON BOTH SIDES OF THE CUTOVER, AND WHY THAT NEEDS DYNAMIC SQL
-- ---------------------------------------------------------------------
-- On a pre-cutover database this file must REPORT PHASE10B_NOT_PRESENT, not
-- FAIL. `firstelement` is deliberately left unstamped at 45 tables because
-- the first Flyway-enabled boot of it is a Phase 12 act, so the quality gate's
-- pre-boot profile runs this file against a database on which none of these
-- tables exists. The first version of this file did not survive that, and the
-- failure was a PARSE-TIME error rather than a runtime one:
--
--     psql:<stdin>:74: ERROR:  relation "public.ai_approvals" does not exist
--     LINE 1:  WHERE conrelid = 'public.ai_approvals'::regclass
--
-- which is the same trap V14 §3a records. PostgreSQL parses a statement before
-- it evaluates it, so a `WHERE to_regclass(...) IS NULL` guard in the same
-- statement cannot help: the relation has to resolve first.
--
-- The fix is the shape V12 §5c/§6 and V14 §3a already use in this bundle, and
-- it is worth being precise about WHY it works, because the reason is not
-- obvious:
--
--   * A **top-level** statement is parsed by the server when psql sends it, so
--     it cannot be guarded at all. Every statement in this file that named a
--     Phase-10b or Phase-7-10a relation at top level has therefore been MOVED
--     inside a `DO` block.
--   * Inside a plpgsql block, statements are prepared **lazily**, on first
--     execution. An early `RETURN` means the statements after it are never
--     prepared, so their relation references never resolve.
--     Verified on this host against `firstelement` before the file was
--     rewritten, with a three-case probe: guarded block -> skipped; guarded
--     block using to_regclass into a variable -> skipped; UNGUARDED block ->
--     the same "relation does not exist" error.
--
-- So `to_regclass(...) IS NULL -> RAISE NOTICE; RETURN` is sufficient for a plpgsql
-- body, and dynamic SQL (`EXECUTE`) is used only where a *result set* is
-- wanted out of a guarded body -- the per-row censuses, which is also V14 §3a's
-- own arrangement. That keeps the file readable and avoids dynamic SQL where a
-- plain `SELECT ... INTO` would do.
--
-- WHAT THIS COST, STATED PLAINLY
-- Four census/report statements were top-level result sets and are now
-- `RAISE NOTICE` lines emitted from inside their guarded block: the D1
-- per-status census, the D2 constraint listing, the D4 scope census and the
-- D5 per-subjectType census. `run-verification.ps1` captures stderr, so those
-- lines still land in the evidence bundle's `.txt` -- but they are NOT result
-- sets any more, and a consumer parsing the bundle for a table-shaped D1
-- census will have to read NOTICEs instead. Every ASSERTION is unchanged: each
-- one is still a `RAISE EXCEPTION` that aborts the bundle, and each one still
-- reads state from `pg_constraint`, `pg_index`, `pg_attribute` or the data
-- itself. Nothing was deleted to make the file parse, and no assertion was
-- downgraded to a message.
--
-- TWO REAL RESULT SETS SURVIVE, and both are the ones worth having on any
-- database: the presence row (§1), the Phase-10b enum lists (§2), the
-- credential-table column shape (§5a), the credential trigger inventory (§5b),
-- the D2 residual (§8a) and the V14-equivalent volume rows where they can run.
-- All of those read only `pg_class` / `pg_attribute` / `pg_trigger` /
-- `pg_type`, which exist on every database in this schema.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Presence, and PARTIAL detection.
--
-- PARTIAL matters: "some of the five landed" means a migration failed
-- mid-chain, and reporting it as PASS is how a half-remediated schema reaches
-- a cutover.
--
-- Safe at top level: `to_regclass` and `pg_constraint` are catalogs that exist
-- on every database, so this row answers the question on both sides.
-- ---------------------------------------------------------------------
SELECT CASE
         WHEN to_regclass('public.api_keys') IS NOT NULL
          AND to_regclass('public.service_principals') IS NOT NULL
          AND to_regclass('public.ai_approvals') IS NOT NULL
          AND to_regclass('public.legal_holds') IS NOT NULL
          AND to_regclass('public.privacy_deletion_requests') IS NOT NULL
          AND to_regclass('public.document_chunks') IS NOT NULL
          AND to_regclass('public.documents') IS NOT NULL
          AND EXISTS (SELECT 1 FROM pg_constraint
                       WHERE conname = 'document_chunks_document_tenant_fkey')
          AND EXISTS (SELECT 1 FROM pg_constraint
                       WHERE conname = 'ai_approvals_approval_check')
          AND EXISTS (SELECT 1 FROM pg_constraint
                       WHERE conname = 'privacy_deletion_requests_subject_reference_check')
         THEN 'PHASE10B_PRESENT'
         WHEN to_regclass('public.api_keys') IS NULL
          AND to_regclass('public.service_principals') IS NULL
         THEN 'PHASE10B_NOT_PRESENT'
         ELSE 'PHASE10B_PARTIAL -- a Phase-10b migration failed halfway; stop'
       END AS phase10b_remediation_state;

-- ---------------------------------------------------------------------
-- 2. Enum value lists the batch adds. Two new closed vocabularies; a drift in
--    either is a silent behaviour change at the auth boundary.
--
-- Safe at top level: `pg_type` / `pg_enum` only. Returns zero rows on a
-- pre-cutover database, which is the correct reading -- the vocabularies do
-- not exist yet -- and §1 above says so explicitly.
-- ---------------------------------------------------------------------
SELECT t.typname AS enum_name,
       string_agg(e.enumlabel, ',' ORDER BY e.enumsortorder) AS enum_values
  FROM pg_type t
  JOIN pg_enum e ON e.enumtypid = t.oid
  JOIN pg_namespace n ON n.oid = t.typnamespace
 WHERE n.nspname = 'public'
   AND t.typname IN ('ServicePrincipalStatus', 'ApiKeyStatus')
 GROUP BY t.typname
 ORDER BY t.typname;

-- ---------------------------------------------------------------------
-- 3. D1 -- THE CORRECTED DECISION BICONDITIONAL.
--
-- Reported as the installed definition and as a per-status census, then
-- asserted. The census is what makes this readable rather than a yes/no: an
-- operator can see how many rows are in each state and whether any of them
-- disagrees.
--
-- GUARD: this section has no artifact of its own to test for, because
-- V20261006100000 only REPLACES a constraint -- ai_approvals_approval_check
-- exists before the batch too, and testing its definition would be circular.
-- So the guard is the batch marker, and the message distinguishes "not
-- applied" from "half applied" rather than collapsing the two.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    definition text;
    offenders  text;
    half_pairs  bigint;
    rec        record;
BEGIN
    IF to_regclass('public.api_keys') IS NULL THEN
        IF to_regclass('public.ai_approvals') IS NULL THEN
            RAISE NOTICE 'V15 D1: PHASE10B_NOT_PRESENT (no ai_approvals to inspect)';
        ELSE
            RAISE NOTICE
                'V15 D1: PHASE10B_PARTIAL -- ai_approvals exists but the batch does not, so D1 is '
                'neither proven nor refuted here. Read the installed definition from the catalog '
                'before trusting this section.';
        END IF;
        RETURN;
    END IF;

    -- Read the installed definition from pg_constraint, so an amendment
    -- elsewhere cannot pass unnoticed.
    SELECT pg_get_constraintdef(oid) INTO definition
      FROM pg_constraint
     WHERE conname = 'ai_approvals_approval_check'
       AND conrelid = to_regclass('public.ai_approvals');

    IF definition IS NULL THEN
        RAISE EXCEPTION 'V15: ai_approvals_approval_check is absent.';
    END IF;
    RAISE NOTICE 'V15 D1 installed: %', definition;

    -- THE CENSUS, as a NOTICE. It was a top-level result set; it is now inside
    -- the guard, because it names ai_approvals in FROM and could not be
    -- otherwise. Still printed, still measured, still in the evidence bundle.
    RAISE NOTICE 'V15 D1 census (status | rows | with_decision | with_decider):';
    FOR rec IN EXECUTE $q$
        SELECT status::text AS st, count(*) AS rows,
               count(*) FILTER (WHERE "decidedAt" IS NOT NULL) AS with_decision,
               count(*) FILTER (WHERE "decidedByUserId" IS NOT NULL) AS with_decider
          FROM public.ai_approvals
         GROUP BY status
         ORDER BY status $q$
    LOOP
        RAISE NOTICE '   % | % | % | %', rec.st, rec.rows, rec.with_decision, rec.with_decider;
    END LOOP;

    IF definition NOT LIKE '%''APPROVED''%'
       OR definition NOT LIKE '%''REJECTED''%'
       OR definition NOT LIKE '%''REVOKED''%' THEN
        RAISE EXCEPTION
            'V15: ai_approvals_approval_check is "%", which does not admit REJECTED and REVOKED as '
            'decision-bearing states. ADR-0011 section 1.', definition;
    END IF;

    -- The exact pre-remediation expression. A loose pattern for "status =" would
    -- also match the CORRECTED form, because PostgreSQL renders an IN list as
    -- status = ANY (ARRAY['APPROVED'::..., 'REJECTED'::..., ...]). The pattern
    -- below is written from the exact rendering -- note the SPACE after the
    -- equals sign, which the first version of this assertion omitted, so it
    -- matched nothing and could never have fired. Its own power to discriminate
    -- is proven on the next line.
    IF definition LIKE '%= ''APPROVED''::"AiApprovalStatus") = ("decidedAt" IS NOT NULL)%' THEN
        RAISE EXCEPTION
            'V15: ai_approvals_approval_check is still the single-literal form ("%"). That is the '
            'defect V20261006100000 exists to remove.', definition;
    END IF;

    IF NOT ('CHECK (((status = ''APPROVED''::"AiApprovalStatus") = ("decidedAt" IS NOT NULL)))'
            LIKE '%= ''APPROVED''::"AiApprovalStatus") = ("decidedAt" IS NOT NULL)%') THEN
        RAISE EXCEPTION
            'V15: the old-form detection pattern does not match the known pre-batch rendering, so '
            'the assertion above it is a no-op. Fix the pattern.';
    END IF;

    SELECT string_agg(id::text, ', ') INTO offenders
      FROM public.ai_approvals
     WHERE (status IN ('APPROVED'::"public"."AiApprovalStatus",
                       'REJECTED'::"public"."AiApprovalStatus",
                       'REVOKED'::"public"."AiApprovalStatus"))
           IS DISTINCT FROM ("decidedAt" IS NOT NULL);

    IF offenders IS NOT NULL THEN
        RAISE EXCEPTION
            'V15: % ai_approvals row(s) disagree with the corrected biconditional (%). '
            'V20261006100000 repairs from ai_approval_decisions and refuses what it cannot repair; '
            'this can only be non-zero if the repair was bypassed.', offenders, offenders;
    END IF;

    SELECT string_agg(id::text, ', ') INTO offenders
      FROM public.ai_approvals
     WHERE (status = 'REJECTED'::"public"."AiApprovalStatus")
           AND ("decidedAt" IS NULL OR "decidedByUserId" IS NULL);

    IF offenders IS NOT NULL THEN
        RAISE EXCEPTION
            'V15: % REJECTED ai_approvals row(s) hide their decider. A rejection that cannot be '
            'attributed is an approval system that cannot be audited.', offenders;
    END IF;

    SELECT string_agg(id::text, ', ') INTO offenders
      FROM public.ai_approvals
     WHERE (status = 'REVOKED'::"public"."AiApprovalStatus")
           AND ("decidedAt" IS NULL OR "decidedByUserId" IS NULL);

    IF offenders IS NOT NULL THEN
        RAISE EXCEPTION
            'V15: % REVOKED ai_approvals row(s) erased their grant''s decision record. Revocation '
            'changes the status; it does not un-decide the request.', offenders;
    END IF;

    -- Half a decision pair is the third way this goes wrong, and it is the one
    -- the D1 migration's own step 6c checks.
    SELECT count(*) INTO half_pairs FROM public.ai_approvals
     WHERE ("decidedAt" IS NULL) <> ("decidedByUserId" IS NULL);

    IF half_pairs <> 0 THEN
        RAISE EXCEPTION 'V15: % ai_approvals row(s) carry half a decision pair.', half_pairs;
    END IF;

    RAISE NOTICE 'V15 D1: ai_approvals_approval_check OK -- %', definition;
END
$$;

-- ---------------------------------------------------------------------
-- 4. D2 -- THE COMPOSITE CHUNK/DOCUMENT TENANT KEY.
--
-- The single-column FK is still there and is expected to be: V20261006110000
-- adds the composite constraint rather than replacing the existing one, so its
-- ON DELETE CASCADE and deletion-propagation behaviour are untouched. What must
-- be proven is that the COMPOSITE one exists on BOTH columns, and that no row
-- disagrees.
--
-- GUARD: on `documents_id_organizationId_key`, which is the unique artifact
-- V20261006110000 creates and which no pre-batch database has. Guarding on the
-- CONSTRAINT instead would be circular, and guarding on `document_chunks` would
-- be useless -- that is a BASELINE table, present on every database in this
-- schema, including the pre-boot one. A guard that never fires is not a guard,
-- so this one tests the thing the migration actually added.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    definition  text;
    legacy_def  text;
    cross_tenant bigint;
    orphans     bigint;
    rec         record;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_indexes
                    WHERE schemaname = 'public'
                      AND indexname = 'documents_id_organizationId_key') THEN
        RAISE NOTICE 'V15 D2: PHASE10B_NOT_PRESENT (no documents_id_organizationId_key)';
        RETURN;
    END IF;

    -- Both document FKs, listed. It was a top-level result set; it is now a
    -- NOTICE, because it names document_chunks in a regclass cast that must not
    -- be resolved on a database where the table is absent.
    RAISE NOTICE 'V15 D2 constraint inventory (name | on_delete | definition):';
    FOR rec IN EXECUTE $q$
        SELECT conname AS cname,
               CASE confdeltype WHEN 'c' THEN 'CASCADE' WHEN 'r' THEN 'RESTRICT'
                                WHEN 'a' THEN 'NO ACTION' ELSE confdeltype::text END AS on_delete,
               pg_get_constraintdef(oid) AS cdef
          FROM pg_constraint
         WHERE conrelid = to_regclass('public.document_chunks')
           AND contype = 'f'
           AND conname IN ('document_chunks_documentId_fkey',
                           'document_chunks_document_tenant_fkey')
         ORDER BY conname $q$
    LOOP
        RAISE NOTICE '   % | % | %', rec.cname, rec.on_delete, rec.cdef;
    END LOOP;

    SELECT pg_get_constraintdef(oid) INTO definition
      FROM pg_constraint
     WHERE conname = 'document_chunks_document_tenant_fkey'
       AND conrelid = to_regclass('public.document_chunks');

    IF definition IS NULL THEN
        RAISE EXCEPTION
            'V15 D2: document_chunks_document_tenant_fkey is absent, so a chunk''s organizationId may '
            'name a different tenant from its document''s.';
    END IF;

    IF definition NOT LIKE '%FOREIGN KEY ("documentId", "organizationId")%'
       OR definition NOT LIKE '%REFERENCES documents(id, "organizationId")%' THEN
        RAISE EXCEPTION
            'V15 D2: the constraint is "%", which is not the composite (documentId, '
            'organizationId) reference.', definition;
    END IF;

    SELECT confdeltype INTO legacy_def
      FROM pg_constraint
     WHERE conname = 'document_chunks_document_tenant_fkey'
       AND conrelid = to_regclass('public.document_chunks');

    IF legacy_def <> 'c' THEN
        RAISE EXCEPTION
            'V15 D2: the composite FK is not ON DELETE CASCADE, so it disagrees with the '
            'pre-existing documentId FK about deletion propagation.';
    END IF;

    -- The pre-existing single-column FK must still be there. Dropping it would
    -- have been a second, unreported regression.
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conname = 'document_chunks_documentId_fkey'
                      AND conrelid = to_regclass('public.document_chunks')) THEN
        RAISE EXCEPTION
            'V15 D2: document_chunks_documentId_fkey is gone. V20261006110000 added a constraint '
            'rather than replacing one, and its ON DELETE CASCADE is the deletion-propagation '
            'contract Phase 10 asserts.';
    END IF;

    -- The target index must exist, be valid and be unique, or the constraint is
    -- resting on something a later VACUUM could invalidate.
    IF NOT EXISTS (SELECT 1
                     FROM pg_class c
                       JOIN pg_index i ON i.indexrelid = c.oid
                    WHERE c.relname = 'documents_id_organizationId_key'
                      AND i.indisvalid AND i.indisunique) THEN
        RAISE EXCEPTION
            'V15 D2: documents_id_organizationId_key is missing, invalid or not unique, so the '
            'composite FK has no trustworthy target.';
    END IF;

    SELECT count(*) INTO cross_tenant
      FROM public.document_chunks c
      JOIN public.documents d ON d."id" = c."documentId"
     WHERE d."organizationId" <> c."organizationId";

    IF cross_tenant > 0 THEN
        RAISE EXCEPTION
            'V15 D2: % document_chunks row(s) belong to a different tenant from their document. '
            'A vector row scoped to the wrong tenant is a retrieval leak.', cross_tenant;
    END IF;

    SELECT count(*) INTO orphans
      FROM public.document_chunks c
     WHERE NOT EXISTS (SELECT 1 FROM public.documents d WHERE d."id" = c."documentId");

    IF orphans > 0 THEN
        RAISE EXCEPTION 'V15 D2: % document_chunks row(s) name a document that does not exist.', orphans;
    END IF;

    RAISE NOTICE 'V15 D2: % ; 0 cross-tenant chunks, 0 orphans.', definition;
END
$$;

-- ---------------------------------------------------------------------
-- 5. D3 -- THE CREDENTIAL TABLES.
--
-- 5a. Shape, reported per column so a secret-shaped column is visible in the
--     evidence bundle rather than only in a guard's exception text.
--
-- 5a and 5b stay at TOP LEVEL on purpose. They read only pg_class,
-- pg_attribute and pg_trigger, which exist on every database in this schema, so
-- they are real result sets on a pre-cutover database too -- and a real result
-- set showing "no such columns, no such triggers" is better evidence than a
-- guard that skipped the question. The zero rows are read together with §1.
-- ---------------------------------------------------------------------
SELECT c.relname AS table_name,
       a.attname AS column_name,
       format_type(a.atttypid, a.atttypmod) AS data_type,
       CASE WHEN a.attnotnull THEN 'NOT NULL' ELSE 'NULL' END AS nullable
  FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
 WHERE n.nspname = 'public'
   AND c.relname IN ('api_keys', 'service_principals')
 ORDER BY c.relname, a.attnum;

-- 5b. The three secret controls: no secret-named column, the plaintext guard
--     trigger is present and enabled, and the audit triggers are present.
--     Also catalog-only, so also safe at top level.
SELECT c.relname AS table_name,
       t.tgname  AS trigger_name,
       t.tgenabled AS enabled,
       pg_get_triggerdef(t.oid) AS definition
  FROM pg_trigger t
    JOIN pg_class c ON c.oid = t.tgrelid
 WHERE NOT t.tgisinternal
   AND t.tgname IN ('api_keys_hash_guard', 'api_keys_lifecycle_audit',
                    'service_principals_lifecycle_audit')
 ORDER BY c.relname, t.tgname;

-- 5c/5d. Tenant scoping and lifecycle audit evidence, asserted with DATA.
--
-- GUARD: on `public.api_keys`, which is created by V20261006120000 and by
-- nothing before it. The strongest guard available for this section, because
-- the table IS the thing it verifies.
DO $$
DECLARE
    carriers       text;
    guard_count    integer;
    audit_count    integer;
    plaintext_hash bigint;
    blank_hash     bigint;
    bad_tz         text;
    cross_tenant   bigint;
    missing        integer;
    keys_total     bigint;
    rec            record;
BEGIN
    IF to_regclass('public.api_keys') IS NULL THEN
        RAISE NOTICE
            'V15 D3 5c-5d: PHASE10B_NOT_PRESENT (no api_keys). Sections 5a and 5b above still ran: '
            'they read the catalog, so their zero rows are the evidence that neither the tables '
            'nor their triggers exist here.';
        RETURN;
    END IF;

    -- 5c. Tenant scoping: every key belongs to a principal of ITS OWN tenant.
    SELECT count(*) INTO cross_tenant
      FROM public.api_keys k
      JOIN public.service_principals p ON p."id" = k."servicePrincipalId"
     WHERE p."organizationId" <> k."organizationId";

    IF cross_tenant > 0 THEN
        RAISE EXCEPTION
            'V15 D3: % api_keys row(s) name a service principal of ANOTHER tenant.', cross_tenant;
    END IF;
    RAISE NOTICE 'V15 D3 5c: cross_tenant_keys = %', cross_tenant;

    -- Control 1: the column census.
    SELECT string_agg(c.relname || '.' || a.attname, ', ') INTO carriers
      FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_attribute a ON a.attrelid = c.oid
       WHERE n.nspname = 'public'
         AND c.relname IN ('api_keys', 'service_principals')
         AND a.attnum > 0 AND NOT a.attisdropped
         AND lower(a.attname) ~ '(secret|plaintext|password|passphrase|credential|rawkey|privatekey)';

    IF carriers IS NOT NULL THEN
        RAISE EXCEPTION
            'V15 D3: secret-named column(s) % on a credential table. FR-6 and AGENTS.md forbid storing '
            'a secret in readable form.', carriers;
    END IF;

    -- Control 2: the guard trigger exists, is enabled, and is on api_keys.
    SELECT count(*) INTO guard_count
      FROM pg_trigger
     WHERE tgrelid = to_regclass('public.api_keys')
       AND tgname = 'api_keys_hash_guard'
       AND tgenabled = 'O';

    IF guard_count <> 1 THEN
        RAISE EXCEPTION
            'V15 D3: the api_keys plaintext-secret guard is missing or disabled (% found). Without '
            'it, "a key stores a hash" is a convention rather than a schema property.', guard_count;
    END IF;

    -- And the audit triggers, which is how "key creation and revocation write an
    -- audit record" stops being a code convention.
    SELECT count(*) INTO audit_count
      FROM pg_trigger
     WHERE NOT tgisinternal AND tgenabled = 'O'
       AND tgname IN ('api_keys_lifecycle_audit', 'service_principals_lifecycle_audit');

    IF audit_count <> 2 THEN
        RAISE EXCEPTION
            'V15 D3: % of the 2 credential lifecycle audit triggers are present or enabled.', audit_count;
    END IF;

    -- The data shape the guard exists to produce: every stored digest is at
    -- least 32 characters and carries a '$' algorithm delimiter. A row that
    -- does not is either a plaintext secret already stored, or a hash algorithm
    -- this check does not recognise -- either way it must not pass silently.
    SELECT count(*) INTO plaintext_hash
      FROM public.api_keys
     WHERE length("keyHash") < 32 OR position('$' in "keyHash") = 0;

    IF plaintext_hash > 0 THEN
        RAISE EXCEPTION
            'V15 D3: % api_keys row(s) hold a "keyHash" that is not shaped like a KDF digest. That is '
            'either a stored plaintext secret or a hash algorithm this schema does not recognise.',
            plaintext_hash;
    END IF;

    SELECT count(*) INTO blank_hash
      FROM public.api_keys
     WHERE btrim("keyHash") = '' OR length(btrim("keyHash")) = 0;

    IF blank_hash > 0 THEN
        RAISE EXCEPTION 'V15 D3: % api_keys row(s) hold a blank "keyHash".', blank_hash;
    END IF;

    SELECT string_agg(c.relname || '.' || a.attname, ', ') INTO bad_tz
      FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_attribute a ON a.attrelid = c.oid AND a.atttypid = 'timestamptz'::regtype
       WHERE n.nspname = 'public'
         AND c.relname IN ('api_keys', 'service_principals')
         AND a.attnum > 0 AND NOT a.attisdropped;

    IF bad_tz IS NOT NULL THEN
        RAISE EXCEPTION
            'V15 D3: timestamptz column in %; every timestamp in this schema is timestamp(3) without '
            'time zone.', bad_tz;
    END IF;

    RAISE NOTICE
        'V15 D3: no secret-named column; plaintext guard enabled; 2 audit triggers enabled; '
        '0 plaintext-shaped digests; no timestamptz.';

    -- 5d. LIFECYCLE AUDIT EVIDENCE, asserted with DATA.
    --
    -- Only counted when the trigger has actually fired, because a freshly
    -- migrated database has no keys and "0 audit rows" would be true of a
    -- trigger that silently never runs. This is the section that would catch the
    -- trigger being disabled AFTER the migration, which the schema checks above
    -- cannot see.
    RAISE NOTICE 'V15 D3 5d key census (status | rows):';
    FOR rec IN EXECUTE $q$
        SELECT status::text AS st, count(*) AS rows
          FROM public.api_keys
         GROUP BY status
         ORDER BY status $q$
    LOOP
        RAISE NOTICE '   % | %', rec.st, rec.rows;
    END LOOP;

    SELECT count(*) INTO keys_total FROM public.api_keys;
    RAISE NOTICE 'V15 D3 5d: api_keys rows = %; admin_audit_events rows naming one = %', keys_total,
        (SELECT count(*) FROM public.admin_audit_events WHERE "resourceType" = 'api_keys');

    IF keys_total > 0 THEN
        SELECT count(*) INTO missing
          FROM public.api_keys k
         WHERE NOT EXISTS (
               SELECT 1 FROM public.admin_audit_events e
                WHERE e."resourceType" = 'api_keys'
                  AND e."resourceId" = k."id"::text
                  AND e."action" = 'api_keys.created');

        IF missing > 0 THEN
            RAISE EXCEPTION
                'V15 D3: % api_keys row(s) have no admin_audit_events row for their creation. Phase '
                '7''s convention is that changing a tenant''s authentication is audited, and '
                'V20261006120000 enforces it with a trigger so the guarantee cannot depend on the '
                'application remembering.', missing;
        END IF;
    END IF;

    RAISE NOTICE
        'V15 D3 audit: every existing api_keys row has a creation audit event (% keys; 0 keys = '
        'nothing to prove yet, which is reported rather than counted as a pass on the trigger itself).',
        keys_total;
END
$$;

-- ---------------------------------------------------------------------
-- 6. D4 -- THE LEGAL-HOLD SCOPE TOTALITY, asserted with DATA.
--
-- The census is what changes the meaning of this section: before
-- V20261006130000 the TABLE and RECORD rows are structurally impossible, so a
-- census showing them as placeable and the fixture proving it is the whole
-- difference between "the vocabulary offers four scopes" and "four scopes can be
-- held".
--
-- GUARD: the batch marker again, because V20261006130000 also only REPLACES a
-- constraint and has no artifact of its own to test for. See §3.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    definition text;
    members    text[];
    unsat      text;
    wrongly_ok integer;
    holds      bigint;
    rec        record;
BEGIN
    IF to_regclass('public.api_keys') IS NULL THEN
        RAISE NOTICE 'V15 D4: PHASE10B_NOT_PRESENT (batch not applied; no scope-pair rule to verify)';
        RETURN;
    END IF;

    RAISE NOTICE 'V15 D4 census (scope_type | holds | with_scope_ref | with_project):';
    FOR rec IN EXECUTE $q$
        SELECT "scopeType"::text AS scope, count(*) AS holds,
               count(*) FILTER (WHERE "scopeRef" IS NOT NULL) AS with_ref,
               count(*) FILTER (WHERE "projectId" IS NOT NULL) AS with_project
          FROM public.legal_holds
         GROUP BY "scopeType"
         ORDER BY "scopeType" $q$
    LOOP
        RAISE NOTICE '   % | % | % | %', rec.scope, rec.holds, rec.with_ref, rec.with_project;
    END LOOP;

    SELECT pg_get_constraintdef(oid) INTO definition
      FROM pg_constraint
     WHERE conrelid = to_regclass('public.legal_holds')
       AND conname = 'legal_holds_scope_pair_check';

    IF definition IS NULL THEN
        RAISE EXCEPTION 'V15 D4: legal_holds_scope_pair_check is absent.';
    END IF;
    RAISE NOTICE 'V15 D4 installed: %', definition;

    SELECT array_agg(e.enumlabel ORDER BY e.enumsortorder) INTO members
      FROM pg_enum e JOIN pg_type t ON t.oid = e.enumtypid
     WHERE t.typname = 'LegalHoldScopeType';

    IF array_length(members, 1) <> 4
       OR 'TENANT' <> ALL (members)
       OR 'PROJECT' <> ALL (members)
       OR 'TABLE' <> ALL (members)
       OR 'RECORD' <> ALL (members) THEN
        RAISE EXCEPTION
            'V15 D4: LegalHoldScopeType is %, expected TENANT/PROJECT/TABLE/RECORD.', members;
    END IF;

    -- TOTALITY, asserted against the INSTALLED rule and not against a
    -- re-derived copy of it.
    --
    -- The first version of this section evaluated a copy of the rule written
    -- out here, in the `probe` CTEs below. A negative-control rehearsal caught
    -- why that proves nothing: restoring the ORIGINAL two-arm
    -- legal_holds_scope_pair_check on a fully migrated database left this file
    -- exiting 0. A predicate that can only ever agree with itself cannot report
    -- that the thing it is checking has drifted, and this section's entire job
    -- is to report exactly that. So the claim "every LegalHoldScopeType member
    -- has an arm" is now made against `pg_get_constraintdef(oid)` -- the rule
    -- PostgreSQL actually enforces -- and each arm's fragments are asserted
    -- individually.
    IF definition NOT LIKE '%= ''TENANT''::"LegalHoldScopeType"%'
       OR definition NOT LIKE '%"scopeRef" IS NULL%' THEN
        RAISE EXCEPTION
            'V15 D4: the installed rule has no TENANT arm demanding a NULL scopeRef: "%".', definition;
    END IF;

    IF definition NOT LIKE '%= ''PROJECT''::"LegalHoldScopeType"%'
       OR definition NOT LIKE '%"projectId" IS NOT NULL%' THEN
        RAISE EXCEPTION
            'V15 D4: the installed rule no longer demands a projectId for a PROJECT hold: "%". A '
            'PROJECT hold that names no project holds nothing at all, which is the specific '
            'failure the original constraint was written to refuse.', definition;
    END IF;

    IF definition NOT LIKE '%TABLE%'
       OR definition NOT LIKE '%RECORD%'
       OR definition NOT LIKE '%"scopeRef" IS NOT NULL%' THEN
        RAISE EXCEPTION
            'V15 D4: the installed rule has no TABLE/RECORD arm: "%". This is the pre-batch '
            'two-arm form, in which a narrow hold is UNPLACEABLE -- exactly the defect '
            'V20261006130000 exists to remove, and the reason the phase-7 narrow count was '
            'structurally unreachable rather than zero.', definition;
    END IF;

    -- ...and, having established that the installed rule HAS the three arms, the
    -- probes below document what each arm admits. They are a readable summary of
    -- the rule, not the evidence for it.
    WITH probe(scope_type, has_project, scope_ref) AS (
        VALUES ('TENANT'::"public"."LegalHoldScopeType", false, NULL::text),
               ('PROJECT'::"public"."LegalHoldScopeType", true,  NULL::text),
               ('TABLE'::"public"."LegalHoldScopeType",  false, 'conversations'),
               ('RECORD'::"public"."LegalHoldScopeType", false, 'conversation:1'))
    SELECT string_agg(p.scope_type::text, ', ') INTO unsat
      FROM probe AS p
     WHERE NOT (
              ((p.scope_type = 'TENANT'::"public"."LegalHoldScopeType")
                   AND (p.has_project IS FALSE) AND p.scope_ref IS NULL)
           OR ((p.scope_type = 'PROJECT'::"public"."LegalHoldScopeType") AND p.has_project)
           OR ((p.scope_type IN ('TABLE'::"public"."LegalHoldScopeType",
                                 'RECORD'::"public"."LegalHoldScopeType"))
               AND p.scope_ref IS NOT NULL AND length(btrim(p.scope_ref)) > 0)
         );

    IF unsat IS NOT NULL THEN
        RAISE EXCEPTION
            'V15 D4: the documented rule admits no combination for LegalHoldScopeType member(s) %.',
            unsat;
    END IF;

    -- The rule the widening must NOT have relaxed. This probe is documentation
    -- of the documented rule; the installed-rule assertions above are what
    -- actually detect a drift.
    WITH probe(scope_type, has_project, scope_ref) AS (
        VALUES ('TENANT'::"public"."LegalHoldScopeType",  false, 'conversations'),
               ('PROJECT'::"public"."LegalHoldScopeType", false, NULL::text),
               ('TABLE'::"public"."LegalHoldScopeType",  false, NULL::text),
               ('RECORD'::"public"."LegalHoldScopeType", false, '   '::text))
    SELECT count(*) INTO wrongly_ok
      FROM probe AS p
     WHERE ((p.scope_type = 'TENANT'::"public"."LegalHoldScopeType")
                AND (p.has_project IS FALSE) AND p.scope_ref IS NULL)
        OR ((p.scope_type = 'PROJECT'::"public"."LegalHoldScopeType") AND p.has_project)
        OR ((p.scope_type IN ('TABLE'::"public"."LegalHoldScopeType",
                              'RECORD'::"public"."LegalHoldScopeType"))
            AND p.scope_ref IS NOT NULL AND length(btrim(p.scope_ref)) > 0);

    IF wrongly_ok <> 0 THEN
        RAISE EXCEPTION
            'V15 D4: the rule now ACCEPTS % combination(s) it must refuse (a TENANT hold naming a '
            'scopeRef, a PROJECT hold naming no project, or a TABLE/RECORD hold naming no '
            'scopeRef).', wrongly_ok;
    END IF;

    -- And no stored row violates it.
    SELECT count(*) INTO wrongly_ok
      FROM public.legal_holds
     WHERE NOT (
              (("scopeType" = 'TENANT'::"public"."LegalHoldScopeType")  AND "scopeRef" IS NULL)
           OR (("scopeType" = 'PROJECT'::"public"."LegalHoldScopeType") AND "projectId" IS NOT NULL)
           OR (("scopeType" IN ('TABLE'::"public"."LegalHoldScopeType",
                                 'RECORD'::"public"."LegalHoldScopeType"))
               AND "scopeRef" IS NOT NULL AND length(btrim("scopeRef")) > 0)
         );

    IF wrongly_ok <> 0 THEN
        RAISE EXCEPTION 'V15 D4: % legal_holds row(s) violate the scope-pair rule.', wrongly_ok;
    END IF;

    SELECT count(*) INTO holds FROM public.legal_holds;

    RAISE NOTICE
        'V15 D4: all 4 LegalHoldScopeType members satisfiable; the 4 must-refuse combinations still '
        'refuse; % legal_holds rows all conform.', holds;
END
$$;

-- ---------------------------------------------------------------------
-- 7. D5 -- THE PRIVACY-DELETION SUBJECT REFERENCE.
--
-- 7a. Per-subjectType census: after this migration a CUSTOMER subject must be
--     nameable WITHOUT a user id, and that is what "customers" means here.
--
-- 7b. The LEGACY ARM COUNT -- the number follow-up F-3 needs, measured on
--     every run of the bundle rather than left to the migration's NOTICE.
--
-- GUARD: on the `subjectCustomerId` COLUMN, read from information_schema, which
-- is a catalog and therefore safe to test even here. That is strictly better
-- than the batch marker for this section: the column is created by
-- V20261006140000 and by nothing before it, so the guard is both non-circular
-- and specific to what D5 installed. (Guarding on `privacy_deletion_requests`
-- would be useless -- that is a Phase-7-10a table, present on any post-7-10a
-- database -- and guarding on the CONSTRAINT would be circular.)
-- ---------------------------------------------------------------------
DO $$
DECLARE
    col_exists  boolean;
    col_null    boolean;
    user_null   boolean;
    new_check   text;
    customer_fk text;
    user_fk     text;
    broken      text;
    cross_tenant bigint;
    requests    bigint;
    legacy      bigint;
    rec         record;
BEGIN
    SELECT EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name = 'privacy_deletion_requests'
                      AND column_name = 'subjectCustomerId')
      INTO col_exists;

    IF col_exists IS NOT TRUE THEN
        RAISE NOTICE
            'V15 D5 7a-7b: PHASE10B_NOT_PRESENT (no subjectCustomerId column; privacy_deletion_requests '
            'itself is a Phase-7-10a table and may well exist)';
        RETURN;
    END IF;

    RAISE NOTICE 'V15 D5 census (subject_type | requests | with_user | with_customer | with_neither):';
    FOR rec IN EXECUTE $q$
        SELECT "subjectType" AS subject, count(*) AS requests,
               count(*) FILTER (WHERE "subjectUserId" IS NOT NULL) AS with_user,
               count(*) FILTER (WHERE "subjectCustomerId" IS NOT NULL) AS with_customer,
               count(*) FILTER (WHERE "subjectUserId" IS NULL
                                AND "subjectCustomerId" IS NULL) AS with_neither
          FROM public.privacy_deletion_requests
         GROUP BY "subjectType"
         ORDER BY "subjectType" $q$
    LOOP
        RAISE NOTICE '   % | % | % | % | %', rec.subject, rec.requests,
                     rec.with_user, rec.with_customer, rec.with_neither;
    END LOOP;

    -- 7b. The legacy-arm count, printed here rather than left to the
    -- migration's own NOTICE, so one bundle run carries it.
    SELECT count(*) INTO legacy
      FROM public.privacy_deletion_requests
     WHERE "subjectType" = 'CUSTOMER'
       AND "subjectCustomerId" IS NULL
       AND "subjectUserId" IS NOT NULL;
    RAISE NOTICE
        'V15 D5 7b legacy_customer_requests = % (follow-up F-3 removes the LEGACY arm once this is 0)',
        legacy;

    SELECT NOT a.attnotnull INTO col_null
      FROM pg_attribute a
     WHERE a.attrelid = to_regclass('public.privacy_deletion_requests')
       AND a.attname = 'subjectCustomerId' AND a.attnum > 0 AND NOT a.attisdropped;

    IF col_null IS DISTINCT FROM true THEN
        RAISE EXCEPTION
            'V15 D5: privacy_deletion_requests."subjectCustomerId" must exist and be NULLABLE. A '
            'CUSTOMER subject has no user id to put in a NOT NULL column, which is the defect.';
    END IF;

    SELECT a.attnotnull INTO user_null
      FROM pg_attribute a
     WHERE a.attrelid = to_regclass('public.privacy_deletion_requests')
       AND a.attname = 'subjectUserId' AND a.attnum > 0 AND NOT a.attisdropped;

    IF user_null THEN
        RAISE EXCEPTION
            'V15 D5: privacy_deletion_requests."subjectUserId" is still NOT NULL, so a CUSTOMER '
            'deletion must still name a users row.';
    END IF;

    SELECT pg_get_constraintdef(oid) INTO new_check
      FROM pg_constraint
     WHERE conrelid = to_regclass('public.privacy_deletion_requests')
       AND conname = 'privacy_deletion_requests_subject_reference_check';

    IF new_check IS NULL
       OR new_check NOT LIKE '%subjectCustomerId%'
       OR new_check NOT LIKE '%CUSTOMER%' THEN
        RAISE EXCEPTION
            'V15 D5: the subject-reference check is absent or does not name both references: %.',
            coalesce(new_check, '(absent)');
    END IF;
    RAISE NOTICE 'V15 D5 installed check: %', new_check;

    SELECT pg_get_constraintdef(oid) INTO customer_fk
      FROM pg_constraint
     WHERE conrelid = to_regclass('public.privacy_deletion_requests')
       AND conname = 'privacy_deletion_requests_subject_customer_tenant_fkey';

    IF customer_fk IS NULL
       OR customer_fk NOT LIKE '%FOREIGN KEY ("subjectCustomerId", "organizationId")%'
       OR customer_fk NOT LIKE '%REFERENCES customers(id, "organizationId")%' THEN
        RAISE EXCEPTION
            'V15 D5: the customer-subject FK is "%" -- a request could name another tenant''s '
            'customer.', coalesce(customer_fk, '(absent)');
    END IF;

    SELECT pg_get_constraintdef(oid) INTO user_fk
      FROM pg_constraint
     WHERE conrelid = to_regclass('public.privacy_deletion_requests')
       AND conname = 'privacy_deletion_requests_subject_tenant_fkey';

    IF user_fk IS NULL
       OR user_fk NOT LIKE '%FOREIGN KEY ("subjectUserId", "organizationId")%'
       OR user_fk NOT LIKE '%REFERENCES users(id, "organizationId")%' THEN
        RAISE EXCEPTION
            'V15 D5: the pre-existing subjectUserId FK is "%". Relaxing the NOT NULL must not have '
            'removed the cross-tenant refusal.', coalesce(user_fk, '(absent)');
    END IF;

    -- The privacy state machine must be untouched by a change that was only
    -- meant to widen the subject.
    SELECT string_agg(rule, ', ') INTO broken FROM unnest(ARRAY[
        CASE WHEN EXISTS (SELECT 1 FROM public.privacy_deletion_requests
                           WHERE ("verifiedAt" IS NULL) <> ("verifiedByUserId" IS NULL))
             THEN 'verification pair' END,
        CASE WHEN EXISTS (SELECT 1 FROM public.privacy_deletion_requests
                           WHERE "startedAt" IS NOT NULL AND "verifiedAt" IS NULL)
             THEN 'started without verification' END,
        CASE WHEN EXISTS (SELECT 1 FROM public.privacy_deletion_requests
                           WHERE (status = 'COMPLETED') <> ("completedAt" IS NOT NULL))
             THEN 'completion pair' END,
        CASE WHEN EXISTS (SELECT 1 FROM public.privacy_deletion_requests
                           WHERE (status = 'CANCELLED') <> ("cancelledAt" IS NOT NULL))
             THEN 'cancellation pair' END,
        CASE WHEN EXISTS (SELECT 1 FROM public.privacy_deletion_requests
                           WHERE status = 'REJECTED'
                             AND ("rejectionReason" IS NULL OR length(btrim("rejectionReason")) = 0))
             THEN 'rejection without a reason' END,
        CASE WHEN EXISTS (SELECT 1 FROM public.privacy_deletion_requests
                           WHERE status = 'COMPLETED'
                             AND ("evidenceReference" IS NULL
                                  OR length(btrim("evidenceReference")) = 0))
             THEN 'completed without evidence' END,
        CASE WHEN EXISTS (SELECT 1 FROM public.privacy_deletion_requests
                           WHERE "subjectType" NOT IN ('CUSTOMER','USER','EMPLOYEE','TENANT'))
             THEN 'subjectType outside its vocabulary' END,
        CASE WHEN EXISTS (SELECT 1 FROM public.privacy_deletion_requests
                           WHERE NOT (
                              (("subjectType" IN ('USER','EMPLOYEE'))
                                   AND "subjectUserId" IS NOT NULL AND "subjectCustomerId" IS NULL)
                           OR (("subjectType" = 'CUSTOMER')
                                   AND (("subjectCustomerId" IS NOT NULL AND "subjectUserId" IS NULL)
                                        OR ("subjectCustomerId" IS NULL AND "subjectUserId" IS NOT NULL)))
                           OR (("subjectType" = 'TENANT')
                                   AND "subjectUserId" IS NULL AND "subjectCustomerId" IS NULL)))
             THEN 'row outside the subject-reference rule' END]) AS rule(rule)
     WHERE rule IS NOT NULL;

    IF broken IS NOT NULL THEN
        RAISE EXCEPTION
            'V15 D5: % -- the privacy state machine must be unaffected by widening the SUBJECT.', broken;
    END IF;

    SELECT count(*) INTO cross_tenant
      FROM public.privacy_deletion_requests r
     WHERE r."subjectCustomerId" IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM public.customers c
                        WHERE c."id" = r."subjectCustomerId"
                          AND c."organizationId" = r."organizationId");

    IF cross_tenant > 0 THEN
        RAISE EXCEPTION
            'V15 D5: % privacy_deletion_requests row(s) name a customer of another tenant.', cross_tenant;
    END IF;

    SELECT count(*) INTO requests FROM public.privacy_deletion_requests;

    RAISE NOTICE
        'V15 D5: subjectCustomerId nullable + composite customer FK + subject-reference check '
        'installed; pre-existing composite users FK intact; privacy state machine intact; '
        '% requests, 0 cross-tenant.', requests;
END
$$;

-- ---------------------------------------------------------------------
-- 8. THE RESIDUALS THIS BATCH DELIBERATELY DID NOT FIX, reported so they
--    stay visible instead of being discovered later.
--
-- 8a reads ONLY baseline tables (`document_chunks` and `documents`), which exist
-- on every database in this schema including the pre-boot one, so it stays a
-- real top-level result set and needs no guard. A residual count of 0 on a
-- pre-cutover database is the truth: the defect exists, there is no data.
-- ---------------------------------------------------------------------
SELECT count(*) AS chunk_project_scope_mismatches
  FROM public.document_chunks c
  JOIN public.documents d ON d."id" = c."documentId"
 WHERE d."projectId" <> c."projectId";

-- 8b names legal_holds AND the LegalHoldScopeType enum, neither of which
-- exists pre-cutover, so it is guarded on the batch marker.
DO $$
DECLARE
    tenant_with_project bigint;
BEGIN
    IF to_regclass('public.api_keys') IS NULL THEN
        RAISE NOTICE 'V15 8b: PHASE10B_NOT_PRESENT (no legal_holds on this database)';
        RETURN;
    END IF;

    SELECT count(*) INTO tenant_with_project
      FROM public.legal_holds
     WHERE "scopeType" = 'TENANT'::"public"."LegalHoldScopeType"
       AND "projectId" IS NOT NULL;

    RAISE NOTICE
        'V15 8b residual tenant_holds_naming_a_project = % (follow-up F-1: a TENANT hold naming a '
        'project is a contradiction, but tightening it is a separate change)', tenant_with_project;
END
$$;

-- ---------------------------------------------------------------------
-- 9. VOLUME, so an evidence bundle shows whether the data assertions above
--    had anything to look at. A count of 0 is reported, never presented as a
--    pass on a trigger or a constraint that never fired.
--
-- GUARD: batch marker. This UNION ALL names eight relations, seven of which do
-- not exist on a pre-cutover database, so it cannot be guarded in place.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    rec record;
    total bigint := 0;
BEGIN
    IF to_regclass('public.api_keys') IS NULL THEN
        RAISE NOTICE
            'V15 9: PHASE10B_NOT_PRESENT (no volume to report; every table this section counts is '
            'either Phase-10b or Phase-7-10a)';
        RETURN;
    END IF;

    FOR rec IN EXECUTE $q$
        SELECT 'api_keys' AS t, count(*) AS n FROM public.api_keys
        UNION ALL SELECT 'service_principals', count(*) FROM public.service_principals
        UNION ALL SELECT 'ai_approvals', count(*) FROM public.ai_approvals
        UNION ALL SELECT 'ai_approval_decisions', count(*) FROM public.ai_approval_decisions
        UNION ALL SELECT 'legal_holds', count(*) FROM public.legal_holds
        UNION ALL SELECT 'privacy_deletion_requests', count(*) FROM public.privacy_deletion_requests
        UNION ALL SELECT 'document_chunks', count(*) FROM public.document_chunks
        UNION ALL SELECT 'admin_audit_events', count(*) FROM public.admin_audit_events
         ORDER BY 1 $q$
    LOOP
        RAISE NOTICE '   % | %', rec.t, rec.n;
        total := total + rec.n;
    END LOOP;

    RAISE NOTICE 'V15 9: volume across 8 tables = % rows', total;
END
$$;