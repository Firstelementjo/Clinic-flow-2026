-- =====================================================================
-- V20261009110000__appointments_tenant_composite_fkeys.sql
-- ADDITIVE.  TRANSACTIONAL.  ADDS FOUR COMPOSITE FOREIGN KEYS AND THEIR
-- TARGET INDEXES.  TOUCHES NO COLUMN AND NO ROW.
--
-- WHAT THIS CLOSES
--   The baseline (V20260814000000, lines 2605-2630) gives `appointments` four
--   foreign keys, and every one of them is SINGLE-COLUMN:
--
--       appointments_createdById_fkey  FOREIGN KEY ("createdById")  REFERENCES public.users(id)
--       appointments_customerId_fkey   FOREIGN KEY ("customerId")   REFERENCES public.customers(id)
--       appointments_doctorId_fkey     FOREIGN KEY ("doctorId")     REFERENCES public.doctors(id)
--       appointments_procedureId_fkey  FOREIGN KEY ("procedureId")  REFERENCES public.procedures(id)
--
--   A single-column FK guarantees the referenced row EXISTS.  It says nothing
--   about WHICH TENANT owns it, so appointments."organizationId" -- the column
--   every list, every schedule and every export filters on -- may name a
--   different tenant from the clinician, the customer or the procedure the
--   appointment belongs to.  A patient record held by one tenant can be
--   attached to a clinician of another.  That is a cross-tenant disclosure,
--   not a data-quality issue.
--
--   This is a GAP IN PHASE 10b, not a new class of problem.  ADR-0011 section 2
--   swept every tenant reference it found and made each of them composite
--   (organizationId, id).  `appointments` was simply never in that batch's
--   scope.  The shape and the remedy are exactly those of
--   V20261006110000__document_chunks_document_tenant_fkey.sql, which is the
--   model: it added ("documentId", "organizationId") -> documents(id,
--   "organizationId") for precisely this reason.
--
-- THE COLUMN ORDER IS LOAD-BEARING, AND IT IS NOT INTERCHANGEABLE
--   The FK column list and the referenced column list are POSITIONAL.  Writing
--
--       FOREIGN KEY ("organizationId", "createdById") REFERENCES users("id", "organizationId")
--
--   binds appointments.organizationId -> users.id and
--   appointments.createdById -> users.organizationId.  That is not a stricter
--   tenant constraint; it is a different and wrong constraint, and it will
--   reject every legitimate appointment.  MEASURED on PostgreSQL 16.15 while
--   writing this file: the mis-ordered form refuses an insert whose columns are
--   all correct ("Key (organizationid, doctorid)=(aaaa..., 1111...) is not
--   present in table t_probe_src").
--
--   So the tenant column comes SECOND here, while V20261006110000 puts it
--   second as well ("documentId", "organizationId").  The Step 4 post-condition
--   resolves conkey and confkey through pg_attribute and compares them
--   POSITION BY POSITION, so a reversed constraint cannot pass.
--
-- ON DELETE -- AND WHY THE COLUMN-LIST FORM IS REQUIRED, NOT STYLISTIC
--   The four pre-existing FKs are ON DELETE SET NULL (confdeltype = 'n'), NOT
--   CASCADE.  V20261006110000 could copy CASCADE from document_chunks because
--   that pair genuinely cascades; copying it here would DELETE an appointment
--   when its doctor, customer, creator or procedure is removed.  That would be a
--   catastrophic behaviour change on clinical data.
--
--   The bare composite form `ON DELETE SET NULL` is also WRONG, and it was
--   measured rather than reasoned about.  Without a column list PostgreSQL
--   nulls EVERY referencing column of the pair, including
--   appointments."organizationId", which is NOT NULL.  MEASURED on PostgreSQL
--   16.15:
--
--       ERROR: null value in column "organizationid" of relation "t_probe_dst"
--              violates not-null constraint
--       CONTEXT: UPDATE ONLY t_probe_dst SET "doctorid" = NULL,
--              "organizationid" = NULL WHERE ...
--
--   i.e. deleting a doctor would start FAILING where it previously succeeded.
--   Adding a tenant constraint must not make deletes harder.  PostgreSQL 15+
--   accepts the column-list form, which nulls only the foreign key column and
--   leaves organizationId alone:
--
--       ON UPDATE CASCADE ON DELETE SET NULL ("doctorId")
--
--   That reproduces the pre-existing deletion-propagation behaviour exactly,
--   and it was verified on PostgreSQL 16.15: deleting the parent left the
--   child row present with "doctorId" = NULL and "organizationId" unchanged.
--
-- ON UPDATE CASCADE
--   Matches the pre-existing single-column FKs (confupdtype = 'c') and every
--   other composite tenant FK in this chain.  It also keeps the new invariant
--   true rather than merely refusing its violation: re-keying a doctor's id
--   propagates, and moving a doctor between tenants carries the appointment's
--   organizationId with it instead of raising.  The alternative -- NO ACTION --
--   would make every tenant reassignment of a doctor fail.
--
--   RECORDED RESIDUAL: the cascade carries "organizationId" but NOT
--   appointments."projectId", which the baseline declares NOT NULL.  Moving a
--   doctor between projects therefore leaves an appointment whose projectId no
--   longer matches its doctor's.  Same shape as the residual ADR-0011 Â§2
--   declined to widen (F-2, document_chunks.projectId).  Reported, not shipped:
--   a three-column FK would refuse for a reason nobody authorised.
--
-- VERIFY FIRST, THEN REFUSE RATHER THAN REPAIR (ADR-0009 section 5)
--   Step 1 counts, BEFORE the constraint exists, every appointment whose
--   organizationId disagrees with the organization that owns its createdById /
--   customerId / doctorId / procedureId.  If any count is non-zero the
--   migration RAISEs and NAMES THE OFFENDING APPOINTMENT IDS.  It does not
--   repair them and it does not delete them.
--
--   Correcting such a row by hand means deciding WHICH of two tenant scopes was
--   right -- the appointment's, or the referenced record's -- and that is a
--   clinical and commercial data decision for an operator with the business
--   context, not a side effect of adding a foreign key.  ADR-0011 section 2
--   requires this refusal in the same words: "refuse rather than delete if any
--   do".  Nothing was deleted, no tenant was rewritten, and no row was
--   modified anywhere in this file.
--
-- WHY MATCH SIMPLE (the default) IS CORRECT HERE
--   With MATCH SIMPLE a composite FK is not enforced when ANY of its columns is
--   NULL.  All four appointment columns are NULLABLE -- an appointment may name
--   no doctor yet, no procedure yet, no creating user -- so MATCH SIMPLE is
--   exactly what "no doctor assigned" needs, and it is why this batch adds no
--   NOT NULL and rewrites no row.
--
--   The other half of that reasoning is a REAL precondition, so Step 4 asserts
--   it: MATCH SIMPLE would ALSO skip the check when "organizationId" is NULL,
--   and only the NOT NULL on that column guarantees a NULL id always brings its
--   tenant with it.  If a future migration relaxes appointments.organizationId
--   to nullable, every one of these four constraints silently stops being
--   enforced on the rows that matter.  That assertion is the tripwire.
--
-- MATCH FULL IS WRONG, AND NOT CLOSELY
--   MATCH FULL would demand that a composite pair be either wholly NULL or
--   wholly non-NULL, so an appointment naming a doctor in tenant A while its
--   organizationId names tenant B would be REJECTED rather than being made
--   detectable -- but an appointment with a doctor and a NULL createdById, which
--   is ordinary, would also be rejected.  It would trade a tenant leak for a
--   functional regression.
--
-- THE SINGLE-COLUMN FKs ARE KEPT, DELIBERATELY
--   None of appointments_createdById_fkey, appointments_customerId_fkey,
--   appointments_doctorId_fkey or appointments_procedureId_fkey is dropped.
--   Once the composite exists the single-column one is redundant -- a row
--   satisfying ("doctorId", "organizationId") -> doctors(id, "organizationId")
--   already satisfies "doctorId" -> doctors(id) -- and their ON DELETE SET NULL
--   behaviour is now carried by the composite's column-list form.
--
--   They are kept anyway, for three reasons.
--     1. Dropping a PRE-EXISTING constraint is not this batch's job.  It is
--        recorded as a residual instead, below.
--     2. Their ON UPDATE CASCADE is part of the propagation behaviour existing
--        code may depend on, and the composite's cascade is not a
--        byte-for-byte substitute: it fires on organizationId changes too.
--     3. Leaving them costs one extra index probe on a write path that
--        already validates twice, and buys a chain in which NOTHING that
--        existed was weakened.  A batch whose stated purpose is to tighten
--        tenant scoping should not be the batch that removes a constraint.
--
--   DO NOT "DEDUPLICATE" THEM WITHOUT READING THIS.  If you drop them, drop the
--   composite ON DELETE SET NULL column list with them, and re-measure the
--   appointment delete path first.
--
-- THE TARGET INDEXES, AND WHY A UNIQUE INDEX AND NOT A CONSTRAINT
--   A composite FK needs a unique object on exactly (id, "organizationId").
--   users_id_organizationId_key (V20261005050000) and customers_id_organizationId_key
--   (V20261006140000) already provide it and are REUSED, not recreated.
--   doctors and procedures have no such object, so this file adds
--   doctors_id_organizationId_key and procedures_id_organizationId_key.
--
--   Both are UNIQUE INDEXES, not UNIQUE CONSTRAINTS, because V4 asserts
--   unique_constraints = 0 for this whole schema -- uniqueness is carried by
--   unique indexes everywhere in it, and Phase 6a established the technique on
--   conversations_id_organizationId_key before anything relied on it.  Step 4
--   re-asserts that invariant so this file cannot quietly break it.
--
--   IF NOT EXISTS, on the same rule V20261006110000 recorded: doctors and
--   procedures are PRE-EXISTING baseline tables, and this chain does not fail a
--   boot on a database where an operator already added the index by hand.
--
-- ASSERTIONS ARE STRUCTURAL, NOT RENDERED TEXT
--   Nothing below compares pg_get_constraintdef() output.  Six guards in this
--   chain did, and they were broken until Phase 30: pg_get_constraintdef
--   renders "REFERENCES documents(...)" under a normal search_path and
--   "REFERENCES public.documents(...)" under the search_path = '' the baseline
--   installs, so those guards passed or failed on a SESSION SETTING rather than
--   on database state, and the chain could not bootstrap a clean database at
--   all.  Every check here resolves conkey / confkey through pg_attribute and
--   compares confrelid to a regclass.  Rendered text is still SELECTed where an
--   operator reading a failure needs it, but it never decides the outcome.
--   ops/flyway/verification/audit-rendered-text-assertions.ps1 classifies this
--   file's sites on every run.
--
-- TIMESTAMPS  nothing in this file touches a column, so none are checked.
-- ROLLBACK  ops/flyway/rollback/undo_phase35.sql drops the four added
--   constraints and the two added indexes.  All six are objects this file
--   created, so the undo removes nothing pre-existing.
-- Every identifier is schema-qualified (the baseline sets search_path = '').
-- =====================================================================

-- ---------------------------------------------------------------------
-- Step 1. VERIFY, BEFORE ANY CONSTRAINT EXISTS.
--
-- Two questions per reference, and the second is the one the ruling names:
--
--   1a. orphans: appointments naming an id that is absent from the target
--       table.  The pre-existing single-column FK should make this impossible,
--       but an FK can be added after the rows, and a database restored from a
--       dump taken with the constraint disabled would never have re-validated.
--       Cheap to check, and its failure mode -- an ALTER failing with an opaque
--       FK error -- is exactly what ADR-0009 section 5 says to pre-empt.
--   1b. cross-tenant: appointments whose organizationId disagrees with the
--       organization that owns the referenced row.  THE count the ruling names.
--
-- Looped, so the refusal message names WHICH reference disagrees rather than
-- saying "4".
-- ---------------------------------------------------------------------
DO $verify$
DECLARE
    ref        record;
    orphans    bigint;
    offenders  bigint;
    ids        text;
BEGIN
    FOR ref IN
        SELECT * FROM (VALUES
            ('appointments."createdById"',  'createdById',  'users'),
            ('appointments."customerId"',   'customerId',   'customers'),
            ('appointments."doctorId"',     'doctorId',     'doctors'),
            ('appointments."procedureId"',  'procedureId',  'procedures')
        ) AS v(label, col, target)
    LOOP
        EXECUTE format(
            'SELECT count(*) FROM "public"."appointments" a
              WHERE a.%1$I IS NOT NULL
                AND NOT EXISTS (SELECT 1 FROM "public"."%2$I" t WHERE t."id" = a.%1$I)',
            ref.col, ref.target)
        INTO orphans;

        IF orphans > 0 THEN
            RAISE EXCEPTION
                'V20261009110000 REFUSED: % appointment(s) name a reference whose id is absent from '
                'the target table, so even the pre-existing single-column FK would refuse this '
                'ALTER. Offending reference: % against table %. Re-derive or remove those rows '
                'first; this migration will not delete them.',
                orphans, ref.label, ref.target;
        END IF;

        EXECUTE format(
            'SELECT count(*), string_agg(a."id"::text, '', '' ORDER BY a."id")
               FROM "public"."appointments" a
               JOIN "public"."%2$I" t ON t."id" = a.%1$I
              WHERE t."organizationId" <> a."organizationId"',
            ref.col, ref.target)
        INTO offenders, ids;

        IF offenders > 0 THEN
            RAISE EXCEPTION
                'V20261009110000 REFUSED: % appointment(s) have an "organizationId" that disagrees with the '
                'organization owning the % row they name. These are exactly the cross-tenant rows the '
                'single-column FK cannot currently prevent. Their rows are not deleted and their '
                'tenants are not rewritten here: choosing which of the two scopes is correct is a '
                'clinical and commercial data decision that needs the business context, so the '
                'migration stops and reports instead. Offending appointment ids: %',
                offenders, ref.target, ids;
        END IF;
    END LOOP;

    RAISE NOTICE
        'V20261009110000: appointments checked against users, customers, doctors and procedures -- '
        '0 orphans, 0 cross-tenant appointments. The composite FKs are safe to add.';
END
$verify$;

-- ---------------------------------------------------------------------
-- Step 2. The composite FKs' targets.
--
-- users and customers already carry theirs (V20261005050000 and
-- V20261006140000 respectively) and are reused.  doctors and procedures need
-- one, so this file adds exactly two.
--
-- UNIQUE INDEX rather than UNIQUE CONSTRAINT, so V4's unique_constraints = 0
-- invariant survives.  IF NOT EXISTS, because doctors and procedures are
-- pre-existing baseline tables.
-- ---------------------------------------------------------------------
CREATE UNIQUE INDEX IF NOT EXISTS "doctors_id_organizationId_key"
    ON "public"."doctors" USING "btree" ("id", "organizationId");

CREATE UNIQUE INDEX IF NOT EXISTS "procedures_id_organizationId_key"
    ON "public"."procedures" USING "btree" ("id", "organizationId");

-- ---------------------------------------------------------------------
-- Step 3. The four composite tenant FKs.
--
-- ON UPDATE CASCADE ON DELETE SET NULL (column) -- see the header for why the
-- column list is required and what the bare form does to a NOT NULL
-- organizationId.  The SET NULL is deletion-propagation behaviour, not an
-- authorisation path: the delete was already authorised on doctors/customers/
-- users/procedures, and an appointment outlives the record it points at.
--
-- Column order is (foreign key column, "organizationId"); see the header.
-- ---------------------------------------------------------------------
ALTER TABLE ONLY "public"."appointments"
    ADD CONSTRAINT "appointments_created_by_tenant_fkey"
    FOREIGN KEY ("createdById", "organizationId")
    REFERENCES "public"."users"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE SET NULL ("createdById");

ALTER TABLE ONLY "public"."appointments"
    ADD CONSTRAINT "appointments_customer_tenant_fkey"
    FOREIGN KEY ("customerId", "organizationId")
    REFERENCES "public"."customers"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE SET NULL ("customerId");

ALTER TABLE ONLY "public"."appointments"
    ADD CONSTRAINT "appointments_doctor_tenant_fkey"
    FOREIGN KEY ("doctorId", "organizationId")
    REFERENCES "public"."doctors"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE SET NULL ("doctorId");

ALTER TABLE ONLY "public"."appointments"
    ADD CONSTRAINT "appointments_procedure_tenant_fkey"
    FOREIGN KEY ("procedureId", "organizationId")
    REFERENCES "public"."procedures"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE SET NULL ("procedureId");

-- ---------------------------------------------------------------------
-- Step 4. POST-CONDITION. Assert on the STATE, read back from the catalogue.
--
-- (a) each constraint exists AND names BOTH columns, in order, on BOTH sides,
--     and points at the right table -- a single-column constraint of the same
--     name would satisfy "exists", and a REVERSED pair would satisfy a LIKE;
-- (b) ON DELETE is SET NULL, and ON UPDATE is CASCADE, so the four new
--     constraints cannot disagree with the four pre-existing ones about what
--     happens when a doctor, customer, creator or procedure is removed;
-- (c) MATCH SIMPLE, which is what makes a NULL doctorId legal;
-- (d) appointments.organizationId is still NOT NULL -- the precondition that
--     makes MATCH SIMPLE safe here, and the tripwire for its loss;
-- (e) both target indexes exist, are valid, unique, two-column, non-partial and
--     non-expression, and the reused users/customers ones are in the same shape;
-- (f) V4's unique_constraints = 0 invariant survives;
-- (g) zero cross-tenant appointments remain;
-- (h) the four PRE-EXISTING single-column FKs are still present -- this batch
--     tightened scope and removed nothing.
-- ---------------------------------------------------------------------
DO $postcondition$
DECLARE
    -- The loop variables are named fk, NOT con: a plpgsql record variable
    -- named "con" shadows every "con." reference inside its own scope, so the
    -- V4 invariant query below -- "FROM pg_constraint con" -- resolved to the
    -- loop record and failed at run time with 'record "fk" has no field
    -- "conrelid"'. Measured on PostgreSQL 16.15.
    fk          record;
    shape       boolean;
    definition  text;
    org_notnull boolean;
    idx_valid   boolean;
    idx_unique  boolean;
    idx_cols    int;
    idx_pred    text;
    idx_exprs   text;
    cross_tenant bigint;
    singles_left int;
    unique_constraints bigint;
BEGIN
    FOR fk IN
        SELECT * FROM (VALUES
            ('appointments_created_by_tenant_fkey',  ARRAY['createdById','organizationId'],
             'public.users',      ARRAY['id','organizationId']),
            ('appointments_customer_tenant_fkey',   ARRAY['customerId','organizationId'],
             'public.customers',  ARRAY['id','organizationId']),
            ('appointments_doctor_tenant_fkey',     ARRAY['doctorId','organizationId'],
             'public.doctors',     ARRAY['id','organizationId']),
            ('appointments_procedure_tenant_fkey',  ARRAY['procedureId','organizationId'],
             'public.procedures',  ARRAY['id','organizationId'])
        ) AS v(conname, local_cols, reftable, ref_cols)
    LOOP
        SELECT pg_get_constraintdef(cn.oid) INTO definition
          FROM "pg_constraint" cn
         WHERE cn.conrelid = 'public.appointments'::regclass
           AND cn.conname = fk.conname;

        -- (a) THE SHAPE, READ STRUCTURALLY. NOT its rendered text.
        --
        -- conkey / confkey are attnum arrays; resolved through pg_attribute they
        -- become column NAMES with no quoting or schema-qualification anywhere
        -- in the comparison, so no session setting can reach this.  ORDER is
        -- pinned because the two lists are positional -- see the header on the
        -- reversed-pair mistake, which a LIKE '%"organizationId", "doctorId"%'
        -- would not catch.
        SELECT
            (SELECT array_agg(a.attname::text ORDER BY k.ord)
               FROM unnest(cn.conkey) WITH ORDINALITY AS k(attnum, ord)
               JOIN "pg_attribute" a ON a.attrelid = cn.conrelid AND a.attnum = k.attnum)
              = fk.local_cols
            AND cn.confrelid = fk.reftable::regclass
            AND (SELECT array_agg(a.attname::text ORDER BY k.ord)
                   FROM unnest(cn.confkey) WITH ORDINALITY AS k(attnum, ord)
                   JOIN "pg_attribute" a ON a.attrelid = cn.confrelid AND a.attnum = k.attnum)
              = fk.ref_cols
          INTO shape
          FROM "pg_constraint" cn
         WHERE cn.conrelid = 'public.appointments'::regclass
           AND cn.conname = fk.conname;

        IF shape IS DISTINCT FROM true THEN
            -- NB the message keeps "(" away from directly after a "%".
            -- plpgsql's RAISE parser treats "%(" as the start of a placeholder
            -- and then finds nothing to substitute, which aborts with "too few
            -- parameters specified for RAISE" AT COMPILE TIME -- so the guard
            -- never runs and the whole migration fails to apply. Measured on
            -- PostgreSQL 16.15 while writing this file.
            RAISE EXCEPTION
                'V20261009110000 failed: % is "%" and its shape is not FOREIGN KEY with local '
                'columns % referencing %.% -- expected local columns % and referenced columns %. '
                'A single-column constraint of this name would satisfy "exists", and a REVERSED '
                'column pair would satisfy a text pattern.',
                fk.conname, coalesce(definition, '(absent)'),
                fk.local_cols::text, fk.reftable, fk.ref_cols::text,
                fk.local_cols::text, fk.ref_cols::text;
        END IF;

        -- (b) the delete and update clauses, so the four new constraints cannot
        --     disagree with the four pre-existing ones.
        IF (SELECT cn.confdeltype FROM "pg_constraint" cn
             WHERE cn.conrelid = 'public.appointments'::regclass
               AND cn.conname = fk.conname) IS DISTINCT FROM 'n' THEN
            RAISE EXCEPTION
                'V20261009110000 failed: % does not have ON DELETE SET NULL. Without it a deleted '
                'doctor would DELETE its appointments instead of clearing the reference, which is '
                'the pre-existing behaviour this constraint must reproduce.',
                fk.conname;
        END IF;

        IF (SELECT cn.confupdtype FROM "pg_constraint" cn
             WHERE cn.conrelid = 'public.appointments'::regclass
               AND cn.conname = fk.conname) IS DISTINCT FROM 'c' THEN
            RAISE EXCEPTION
                'V20261009110000 failed: % does not have ON UPDATE CASCADE; a re-key of the '
                'referenced row must propagate rather than raise.', fk.conname;
        END IF;

        -- (c) MATCH SIMPLE: a NULL doctorId / customerId / createdById /
        --     procedureId must leave the constraint unenforced, not raising.
        IF (SELECT cn.confmatchtype FROM "pg_constraint" cn
             WHERE cn.conrelid = 'public.appointments'::regclass
               AND cn.conname = fk.conname) IS DISTINCT FROM 's' THEN
            RAISE EXCEPTION
                'V20261009110000 failed: % is not MATCH SIMPLE. MATCH FULL would reject an ordinary '
                'appointment that names a doctor but no creating user.', fk.conname;
        END IF;
    END LOOP;

    -- (d) THE PRECONDITION THAT MAKES MATCH SIMPLE SAFE.  Without this NOT
    --     NULL, a NULL foreign key would bring its own tenant with it -- and a
    --     NULL tenant would switch all four constraints off for exactly the
    --     rows that matter.
    SELECT a.attnotnull INTO org_notnull
      FROM "pg_attribute" a
     WHERE a.attrelid = 'public.appointments'::regclass
       AND a.attname = 'organizationId'
       AND a.attnum > 0
       AND NOT a.attisdropped;

    IF org_notnull IS DISTINCT FROM true THEN
        RAISE EXCEPTION
            'V20261009110000 failed: appointments."organizationId" is no longer NOT NULL. All four '
            'composite FKs are MATCH SIMPLE, so a NULL organizationId would silently disable them '
            'for rows that name no doctor, customer, creator or procedure. Restoring the NOT NULL is '
            'a separate decision and is not this file''s job.';
    END IF;

    -- (e) EVERY target index this batch depends on, including the two it did
    --     NOT create: an FK onto an index that is invalid, partial or built on
    --     expressions is a constraint nobody should trust.
    FOR fk IN
        SELECT * FROM (VALUES
            ('users_id_organizationId_key',      'public.users'),
            ('customers_id_organizationId_key',   'public.customers'),
            ('doctors_id_organizationId_key',     'public.doctors'),
            ('procedures_id_organizationId_key',  'public.procedures')
        ) AS v(idxname, reftable)
    LOOP
        SELECT i.indisvalid, i.indisunique, i.indnkeyatts,
               i.indpred::text, i.indexprs::text
          INTO idx_valid, idx_unique, idx_cols, idx_pred, idx_exprs
          FROM "pg_index" i
         WHERE i.indexrelid = to_regclass(format('public.%I', fk.idxname));

        IF NOT FOUND THEN
            RAISE EXCEPTION
                'V20261009110000 failed: % is absent, so the composite FK that targets % has no '
                'declared unique object.', fk.idxname, fk.reftable;
        END IF;

        IF NOT idx_valid OR NOT idx_unique THEN
            -- NB the message deliberately does not put "=" straight after a "%".
            -- plpgsql's RAISE placeholder parser does not recognise "%=" followed
            -- by whitespace as a placeholder, so "valid=% unique=%" is read as ONE
            -- placeholder and the second argument is rejected at compile time
            -- with "too many parameters specified for RAISE". Measured on
            -- PostgreSQL 16.15 while writing this file.
            RAISE EXCEPTION
                'V20261009110000 failed: % reports valid [%] unique [%]. A composite FK onto an '
                'index that is not both valid and unique is a constraint nobody should trust.',
                fk.idxname, idx_valid, idx_unique;
        END IF;

        IF idx_cols IS DISTINCT FROM 2 THEN
            RAISE EXCEPTION
                'V20261009110000 failed: % has % key column(s); a composite FK target must have '
                'exactly two.', fk.idxname, coalesce(idx_cols::text, 'null');
        END IF;

        IF idx_pred IS NOT NULL THEN
            RAISE EXCEPTION
                'V20261009110000 failed: % is PARTIAL (%). A partial unique index does not cover '
                'every referenced row, so the FK would be satisfied by a lookup that can miss.',
                fk.idxname, idx_pred;
        END IF;

        IF idx_exprs IS NOT NULL THEN
            RAISE EXCEPTION
                'V20261009110000 failed: % is built on EXPRESSIONS (%), not on ("id", '
                '"organizationId").', fk.idxname, idx_exprs;
        END IF;
    END LOOP;

    -- (f) V4's invariant: uniqueness in this schema is carried by unique
    --     INDEXES, never by unique CONSTRAINTS.
    SELECT count(*) INTO unique_constraints
      FROM "pg_constraint" con
      JOIN "pg_class" c ON c.oid = con.conrelid
      JOIN "pg_namespace" n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public' AND con.contype = 'u';

    IF unique_constraints <> 0 THEN
        RAISE EXCEPTION
            'V20261009110000 failed: % UNIQUE CONSTRAINT(s) exist. Every uniqueness in this chain '
            'is a unique INDEX so V4''s unique_constraints = 0 invariant holds.', unique_constraints;
    END IF;

    -- (g) zero cross-tenant appointments remain.
    SELECT count(*) INTO cross_tenant
      FROM "public"."appointments" a
     WHERE (a."doctorId"    IS NOT NULL AND EXISTS (SELECT 1 FROM "public"."doctors" t
                     WHERE t."id" = a."doctorId" AND t."organizationId" <> a."organizationId"))
        OR (a."customerId"  IS NOT NULL AND EXISTS (SELECT 1 FROM "public"."customers" t
                     WHERE t."id" = a."customerId" AND t."organizationId" <> a."organizationId"))
        OR (a."procedureId" IS NOT NULL AND EXISTS (SELECT 1 FROM "public"."procedures" t
                     WHERE t."id" = a."procedureId" AND t."organizationId" <> a."organizationId"))
        OR (a."createdById" IS NOT NULL AND EXISTS (SELECT 1 FROM "public"."users" t
                     WHERE t."id" = a."createdById" AND t."organizationId" <> a."organizationId"));

    IF cross_tenant > 0 THEN
        RAISE EXCEPTION
            'V20261009110000 failed: % cross-tenant appointment(s) remain after adding the '
            'composite FKs.', cross_tenant;
    END IF;

    -- (h) The four PRE-EXISTING single-column FKs survive. This batch tightened
    --     scope; it removed nothing.
    SELECT count(*) INTO singles_left
      FROM "pg_constraint" con
     WHERE con.conrelid = 'public.appointments'::regclass
       AND con.contype = 'f'
       AND con.conname IN ('appointments_createdById_fkey', 'appointments_customerId_fkey',
                           'appointments_doctorId_fkey',   'appointments_procedureId_fkey');

    IF singles_left <> 4 THEN
        RAISE EXCEPTION
            'V20261009110000 failed: % of the 4 pre-existing single-column appointment FKs are '
            'present. This batch adds constraints; it must not remove any.', singles_left;
    END IF;

    RAISE NOTICE
        'V20261009110000: 4 composite tenant FKs present on appointments -- '
        '("createdById","organizationId")->users, ("customerId","organizationId")->customers, '
        '("doctorId","organizationId")->doctors, ("procedureId","organizationId")->procedures -- '
        'each structurally verified in BOTH column orders, ON UPDATE CASCADE / ON DELETE SET NULL '
        '(column), MATCH SIMPLE; appointments.organizationId still NOT NULL; 4 target indexes '
        'valid+unique+non-partial+2-column; unique_constraints still 0; 0 cross-tenant '
        'appointments; the 4 pre-existing single-column FKs retained.'
    ;
END
$postcondition$;