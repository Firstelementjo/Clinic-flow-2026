-- =====================================================================
-- V20261006110000__document_chunks_document_tenant_fkey.sql
-- ADDITIVE.  TRANSACTIONAL.  ADDS ONE FOREIGN KEY AND ITS TARGET INDEX.
--
-- REMEDIATION OF DEFECT D2, AND THE RULING THAT PROMISED IT
--   ADR-0011 section 2: every composite tenant FK this migration set added is
--   (organizationId, id), so "a chunk cannot reference a document in another
--   tenant" -- except that document_chunks."documentId" is a SINGLE-column FK.
--
--   The baseline (V20260814000000, line 2781) has exactly this:
--
--       ADD CONSTRAINT "document_chunks_documentId_fkey"
--           FOREIGN KEY ("documentId") REFERENCES "public"."documents"("id")
--           ON UPDATE CASCADE ON DELETE CASCADE;
--
--   A single-column FK guarantees the document EXISTS.  It says nothing about
--   WHICH TENANT owns it, so document_chunks."organizationId" -- the column
--   every retrieval, every purge and every vector query filters on -- may name
--   a different tenant from the document the chunk belongs to.  A vector row
--   scoped to the wrong tenant is a retrieval leak, not a data-quality issue.
--
--   Phase 10 catches this at runtime and fails closed
--   (KnowledgeIndexLifecycleIntegrationTest#ingestionIsTenantScoped, and the
--   comment at line 263 of that file names this missing constraint).  Code
--   standing between ingestion and a mis-scoped vector row is evidence the
--   state is STORABLE, not evidence it is prevented.  This file is the
--   database-level prevention.
--
-- THE SINGLE-COLUMN FK IS KEPT, DELIBERATELY
--   It is not dropped.  It is redundant once the composite constraint exists
--   -- a row satisfying (documentId, organizationId) -> documents(id,
--   organizationId) already satisfies documentId -> documents(id) -- but
--   dropping an existing constraint is not this batch's job, and its
--   ON DELETE CASCADE is part of the deletion-propagation behaviour Phase 10
--   asserts (a deleted document's chunks are removed with it).  Leaving it
--   costs one extra index probe on a delete path and buys a chain in which
--   nothing existing was weakened.  Recorded so a later reader does not
--   "deduplicate" it without reading this.
--
-- VERIFY FIRST, REFUSE RATHER THAN DELETE (ADR-0009 section 5)
--   Step 1 counts the chunks whose organizationId disagrees with their
--   document's, BEFORE the constraint is attempted.  If that count is
--   non-zero, the migration RAISEs and names the offending ids.  It does not
--   repair them: correcting a chunk's tenant by hand means choosing which of two
--   scopes was right, and that is a data decision for an operator with the
--   business context, not a side effect of adding a foreign key.  ADR-0011
--   section 2 states this requirement in the same words: "refuse rather than
--   delete if any do".
--
-- THE TARGET INDEX, AND WHY IT IS A UNIQUE INDEX AND NOT A CONSTRAINT
--   documents has no (id, organizationId) unique object in the baseline, so a
--   composite FK needs one as its target.  It is created as a UNIQUE INDEX, not
--   a UNIQUE CONSTRAINT, because V4 asserts unique_constraints = 0 for this
--   whole schema -- uniqueness is carried by unique indexes everywhere in it,
--   and Phase 6a established the technique on
--   conversations_id_organizationId_key before anything relied on it.
--   PostgreSQL accepts a non-partial unique index as a foreign-key target; that
--   was verified on PostgreSQL 16 before being relied on, and the fixture
--   asserts the constraint's existence AND its target.
--
--   IF NOT EXISTS, because this index is on a PRE-EXISTING baseline table, and
--   the rule the chain already follows is that such an index must not fail a
--   boot on a database where an operator already added it by hand.
--
-- WHAT IS NOT FIXED HERE -- A REPORTED, NOT SHIPPED, RESIDUAL
--   The chunk's projectId still need not match its document's projectId.  A
--   chunk in tenant A pointing at a document in tenant A's OTHER project is a
--   cross-project scope leak of exactly the same shape, and the composite
--   constraint below does not touch it.  The fix is a triple-column FK
--   ("organizationId", "projectId", "documentId") ->
--   documents("organizationId", "projectId", "id") with a matching unique index,
--   plus the same refuse-rather-than-repair guard.  It is NOT shipped in this
--   batch: ADR-0011 section 2 asks specifically for the (organizationId, id)
--   form, and widening the constraint to three columns would make the guard's
--   refusal condition stricter than the ruling that authorises it -- on a
--   database holding such a row this migration would then refuse for a reason
--   nobody asked for.  Recorded in docs/migration/phases/
--   phase-10b-schema-remediation.md as follow-up F-2 with its exact statements.
--
-- TIMESTAMPS  nothing in this file touches a column.
-- Rollback: ops/flyway/rollback/undo_phase10b.sql drops the added constraint and
-- the added index.  Both are objects this file created, so the undo removes
-- nothing pre-existing.
-- Every identifier is schema-qualified (the baseline sets search_path = '').
-- =====================================================================

-- ---------------------------------------------------------------------
-- Step 1. VERIFY, BEFORE THE CONSTRAINT EXISTS.
--
-- Two reads, and the second one exists because the first is not the question:
--
--   1a. chunks whose documentId is missing from documents.  The existing
--       single-column FK should make this impossible, but an FK can be added
--       after the rows, and a database restored from a dump taken with the
--       constraint disabled would not have re-validated.  Cheap to check, and
--       its failure mode (an ALTER that fails with an opaque FK error) is
--       exactly the kind of thing ADR-0009 section 5 says to pre-empt.
--   1b. chunks whose organizationId disagrees with their document's.  THE
--       count the ruling names.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    orphans      bigint;
    cross_tenant bigint;
    offenders    text;
BEGIN
    SELECT count(*) INTO orphans
      FROM "public"."document_chunks" AS c
     WHERE NOT EXISTS (SELECT 1 FROM "public"."documents" AS d
                        WHERE d."id" = c."documentId");

    IF orphans > 0 THEN
        RAISE EXCEPTION
            'V20261006110000 REFUSED: % document_chunks row(s) name a documentId that does not '
            'exist in documents, so even the pre-existing single-column FK would refuse this '
            'ALTER. Re-derive or remove those rows first; this migration will not delete them.',
            orphans;
    END IF;

    SELECT count(*), string_agg(c.id::text, ', ' ORDER BY c.id)
      INTO cross_tenant, offenders
      FROM "public"."document_chunks" AS c
      JOIN "public"."documents" AS d ON d."id" = c."documentId"
     WHERE d."organizationId" <> c."organizationId";

    IF cross_tenant > 0 THEN
        RAISE EXCEPTION
            'V20261006110000 REFUSED: % document_chunks row(s) belong to a DIFFERENT tenant from '
            'their document: %. These are exactly the mis-scoped vector rows '
            'document_chunks_documentId_fkey cannot currently prevent. Their rows are not '
            'deleted and their tenants are not rewritten here: choosing which of the two scopes '
            'is correct is a data decision that needs the business context, so the migration '
            'stops and reports instead.',
            cross_tenant, coalesce(offenders, '(ids above)');
    END IF;

    RAISE NOTICE
        'V20261006110000: document_chunks checked -- 0 orphans, 0 cross-tenant chunks. '
        'The composite FK is safe to add.';
END
$$;

-- ---------------------------------------------------------------------
-- Step 2. The composite FK's target.
--
-- See the header: UNIQUE INDEX rather than UNIQUE CONSTRAINT, so V4's
-- unique_constraints = 0 invariant survives; IF NOT EXISTS, because
-- documents is a pre-existing baseline table.
-- ---------------------------------------------------------------------
CREATE UNIQUE INDEX IF NOT EXISTS "documents_id_organizationId_key"
    ON "public"."documents" USING "btree" ("id", "organizationId");

-- ---------------------------------------------------------------------
-- Step 3. The composite tenant FK.
--
-- ON UPDATE CASCADE ON DELETE CASCADE, matching
-- document_chunks_documentId_fkey so the two constraints cannot disagree about
-- what happens to a chunk when its document is deleted or re-keyed.  ON DELETE
-- CASCADE is the documented deletion-propagation behaviour, not an
-- authorisation path: the delete was already authorised on documents, and the
-- chunks carry no independent meaning once the document is gone.
-- ---------------------------------------------------------------------
ALTER TABLE ONLY "public"."document_chunks"
    ADD CONSTRAINT "document_chunks_document_tenant_fkey"
    FOREIGN KEY ("documentId", "organizationId")
    REFERENCES "public"."documents"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE CASCADE;

-- ---------------------------------------------------------------------
-- Step 4. POST-CONDITION. Assert on the STATE.
--
-- (a) the constraint exists and names BOTH columns and BOTH targets -- a
--     single-column constraint of the same intent would satisfy "exists";
-- (b) the ON DELETE clause is CASCADE, because the deletion-propagation
--     behaviour is a contract Phase 10 depends on;
-- (c) the target index exists and is valid and unique, because a composite FK
--     onto an index that is invalid is a constraint nobody should trust;
-- (d) zero rows disagree.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    definition  text;
    fk_del      text;
    idx_def     text;
    fk_shape    boolean;
    idx_valid   boolean;
    idx_unique  boolean;
    cross_tenant bigint;
BEGIN
    SELECT pg_get_constraintdef(oid) INTO definition
      FROM pg_constraint
     WHERE conrelid = 'public.document_chunks'::regclass
       AND conname = 'document_chunks_document_tenant_fkey';

    IF definition IS NULL THEN
        RAISE EXCEPTION
            'V20261006110000 failed: document_chunks_document_tenant_fkey is absent after the '
            'migration, so a chunk can still point at another tenant''s document.';
    END IF;

    -- (a1) THE CONSTRAINT'S SHAPE, READ STRUCTURALLY. NOT its rendered text.
    --
    -- This block used to assert on pg_get_constraintdef() output:
    --
    --     IF definition NOT LIKE '%FOREIGN KEY ("documentId", "organizationId")%'
    --        OR definition NOT LIKE '%REFERENCES documents(id, "organizationId")%' THEN
    --
    -- pg_get_constraintdef() schema-qualifies any relation that is not visible
    -- on the CURRENT search_path, so the same constraint on the same database
    -- renders two ways. Measured on PostgreSQL 16.15:
    --
    --     search_path = "$user", public
    --       FOREIGN KEY ("documentId", "organizationId") REFERENCES documents(id, "organizationId") ...
    --     search_path = ''
    --       FOREIGN KEY ("documentId", "organizationId") REFERENCES public.documents(id, "organizationId") ...
    --
    -- The second rendering does not match the pattern above. The baseline
    -- (V20260814000000, line 56) runs
    --     SELECT pg_catalog.set_config('search_path', '', false);
    -- and is_local = false makes that a SESSION setting: it persists for the
    -- rest of the connection. Flyway runs every migration of a boot on one
    -- connection, so under Flyway -- not merely under a concatenated psql
    -- session -- this guard could never pass and the chain could never
    -- bootstrap a clean database. A verification whose result flips on a
    -- session setting is not a verification.
    --
    -- The replacement reads conkey / confkey (attnum arrays) resolved through
    -- pg_attribute, and compares confrelid (an OID) to a regclass. No rendered
    -- text is involved, so quoting and qualification cannot reach it. The same
    -- technique is already used for index columns in this chain, at
    -- V20261005080000 line 504.
    --
    -- `definition` is still read above and still reported below, because an
    -- operator reading the failure message needs the rendered form -- but it no
    -- longer decides whether the migration passes.
    SELECT
        (SELECT array_agg(a.attname::text ORDER BY k.ord)
           FROM unnest(cn.conkey) WITH ORDINALITY AS k(attnum, ord)
           JOIN pg_attribute a ON a.attrelid = cn.conrelid AND a.attnum = k.attnum)
          = ARRAY['documentId', 'organizationId']::text[]
        AND cn.confrelid = 'public.documents'::regclass
        AND (SELECT array_agg(a.attname::text ORDER BY k.ord)
               FROM unnest(cn.confkey) WITH ORDINALITY AS k(attnum, ord)
               JOIN pg_attribute a ON a.attrelid = cn.confrelid AND a.attnum = k.attnum)
          = ARRAY['id', 'organizationId']::text[]
      INTO fk_shape
      FROM pg_constraint cn
     WHERE cn.conrelid = 'public.document_chunks'::regclass
       AND cn.conname = 'document_chunks_document_tenant_fkey';
    IF fk_shape IS DISTINCT FROM true THEN
        RAISE EXCEPTION
            'V20261006110000 failed: document_chunks_document_tenant_fkey is "%" and its shape is '
            'not FOREIGN KEY ("documentId", "organizationId") -> public.documents(id, '
            '"organizationId"). A single-column constraint of the same name would satisfy "exists".',
            coalesce(definition, '(absent)');
    END IF;

    SELECT confdeltype INTO fk_del
      FROM pg_constraint
     WHERE conrelid = 'public.document_chunks'::regclass
       AND conname = 'document_chunks_document_tenant_fkey';

    IF fk_del <> 'c' THEN
        RAISE EXCEPTION
            'V20261006110000 failed: document_chunks_document_tenant_fkey has confdeltype "%", '
            'expected "c" (CASCADE). Deletion propagation is a contract Phase 10 asserts and '
            'the two document FKs must not disagree about it.', fk_del;
    END IF;

    SELECT i.indisvalid, i.indisunique, pg_get_indexdef(i.indexrelid)
      INTO idx_valid, idx_unique, idx_def
      FROM pg_index i
      JOIN pg_class c ON c.oid = i.indexrelid
     WHERE c.relname = 'documents_id_organizationId_key';

    IF NOT FOUND THEN
        RAISE EXCEPTION
            'V20261006110000 failed: documents_id_organizationId_key is absent, so the composite '
            'FK has no declared target.';
    END IF;

    IF NOT idx_valid OR NOT idx_unique THEN
        -- NB the message deliberately does not put "=" straight after a "%".
        -- plpgsql's RAISE placeholder parser does not recognise "%=" followed
        -- by whitespace as a placeholder, so "is valid=% unique=" is read as ONE
        -- placeholder and the second argument is rejected at compile time with
        -- "too many parameters specified for RAISE". Measured on PostgreSQL
        -- 16.15 while writing this file.
        RAISE EXCEPTION
            'V20261006110000 failed: documents_id_organizationId_key reports valid [%] unique [%]. '
            'A composite FK onto an index that is not both valid and unique is a constraint '
            'nobody should trust.', idx_valid, idx_unique;
    END IF;

    SELECT count(*) INTO cross_tenant
      FROM "public"."document_chunks" AS c
      JOIN "public"."documents" AS d ON d."id" = c."documentId"
     WHERE d."organizationId" <> c."organizationId";

    IF cross_tenant > 0 THEN
        RAISE EXCEPTION
            'V20261006110000 failed: % cross-tenant chunk(s) remain after adding the composite FK.',
            cross_tenant;
    END IF;

    RAISE NOTICE
        'V20261006110000: % ; target index valid+unique; 0 cross-tenant chunks.',
        definition;
END
$$;
