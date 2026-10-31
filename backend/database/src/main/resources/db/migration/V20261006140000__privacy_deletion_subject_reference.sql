-- =====================================================================
-- V20261006140000__privacy_deletion_subject_reference.sql
-- ADDITIVE.  TRANSACTIONAL.  ONE COLUMN, ONE FK, ONE INDEX, ONE CHECK,
-- AND ONE NOT-NULL RELAXATION.
--
-- REMEDIATION OF DEFECT D5, AND THE RULING THAT PROMISED IT
--   Phase 7 reported it in docs/migration/phases/phase-07.md, "Schema gaps
--   reported to the orchestrator", item 3:
--
--       privacy_deletion_requests."subjectUserId" is NOT NULL with a composite
--       FK to users, even when subjectType = 'CUSTOMER'. A deletion for a
--       customer who has no users row cannot be requested.
--
--   And from V20261005050000 itself (line 433 onwards):
--
--       "subjectUserId"      uuid NOT NULL,
--       "subjectType"        text NOT NULL DEFAULT 'CUSTOMER',
--       ...
--       CONSTRAINT "privacy_deletion_requests_subject_type_check"
--           CHECK ("subjectType" IN ('CUSTOMER','USER','EMPLOYEE','TENANT')),
--
--       ALTER TABLE ONLY "public"."privacy_deletion_requests"
--           ADD CONSTRAINT "privacy_deletion_requests_subject_tenant_fkey"
--           FOREIGN KEY ("subjectUserId", "organizationId")
--           REFERENCES "public"."users"("id", "organizationId")
--
--   So the table's DEFAULT subjectType is CUSTOMER and its ONLY way to name a
--   subject is a users row.  The commonest privacy request in FR-7 -- delete a
--   customer's data -- cannot be recorded, because a customer is a
--   customers row and NOT a users row.  Phase 7 works around it in the
--   application by refusing before the insert ("so the failure is a
--   non-disclosing denial rather than a foreign-key violation"), which is the
--   right immediate behaviour and is also evidence the state is unexpressible.
--
--   A privacy request that cannot be recorded is also a privacy request that
--   cannot be evidenced: FR-7 requires a completed deletion to point at what
--   was removed, and privacy_deletion_targets can only hang off a request.
--
-- WHAT IS ADDED, AND WHY IT IS A COLUMN RATHER THAN A TYPE CHANGE
--   "subjectCustomerId" uuid, plus a composite tenant FK to customers, plus a
--   tenant-led index.  The alternative -- a subjectType-discriminated union --
--   would have meant rewriting the existing FK and CHECKs in place, which is
--   exactly the kind of schema rewrite this batch is forbidden to do.  Two
--   nullable columns with a biconditional over them is the additive shape, and
--   it is the shape the rest of this chain already uses (ai_approvals carries
--   requestedByUserId AND requestedByPrincipal for the same reason:
--   "A null user cannot be told apart from 'we forgot', so the actor is spelled
--   out in one of two columns and the CHECK below refuses both being absent").
--
--   A project subject reference was considered and is NOT added.
--   "projectId" is already on the row and already FKs projects, and
--   subjectType's vocabulary ('CUSTOMER','USER','EMPLOYEE','TENANT') contains no
--   PROJECT member -- so a project would be the scope of a request, never its
--   subject.  Adding a subjectProjectId would create a column whose only
--   possible values duplicate projectId.
--
-- WHAT THE USERS FK IS -- CHECKED, NOT ASSUMED
--   It exists, and it is COMPOSITE: privacy_deletion_requests_subject_tenant_fkey
--   on ("subjectUserId", "organizationId") -> users("id", "organizationId"),
--   ON UPDATE CASCADE ON DELETE RESTRICT.  Three consequences are load-bearing:
--
--     1. It is NOT dropped.  Relaxing subjectUserId to nullable leaves the FK
--        exactly as it was, and it still means "if a user id is named, it is a
--        user of THIS tenant" -- the cross-tenant refusal is unchanged.
--     2. ON DELETE RESTRICT means the existing behaviour is preserved: a user
--        row cannot be deleted out from under an open deletion request.  A
--        privacy request outliving its subject is exactly the case where the
--        request must not silently lose its target.
--     3. Because it is a composite FK on a column that becomes nullable, the
--        new CUSTOMER arm cannot accidentally accept another tenant's customer:
--        the FK applies to whatever value is present.
--
-- THE DECISION PAIR, AND THE VERIFICATION EVIDENCE, ARE NOT TOUCHED
--   Nothing in this file changes privacy_deletion_requests_status,
--   privacy_deletion_requests_verified_pair_check,
--   privacy_deletion_requests_start_requires_verified_check,
--   privacy_deletion_requests_completion_check,
--   privacy_deletion_requests_cancellation_check,
--   privacy_deletion_requests_rejection_check or
--   privacy_deletion_requests_evidence_check.  A REQUESTED / VERIFIED /
--   IN_PROGRESS / COMPLETED / REJECTED / CANCELLED request behaves exactly as
--   it did, keeps its verification pair, its rejection reason and its evidence
--   reference, and the post-condition below proves each of those still holds.
--
--   That matters more than it looks: the temptation when widening a subject
--   check is to relax the whole CHECK set, and the whole CHECK set is the
--   privacy state machine.
--
-- THE LEGACY ARM, AND WHY IT IS STILL HERE
--   Before this file, a CUSTOMER deletion was recorded by naming a users row.
--   Every such row that exists on a cutover database must keep satisfying a
--   CHECK, and this migration must not fail on it, delete it, or silently
--   change what it says.  So the new CHECK admits THREE arms:
--
--     USER / EMPLOYEE -> subjectUserId, never a customer
--     CUSTOMER         -> subjectCustomerId                     (new, correct)
--     CUSTOMER         -> subjectUserId                         (LEGACY, temporary)
--     TENANT           -> neither; the tenant is organizationId
--
--   The LEGACY arm is a real weakening and it is labelled as one.  It is
--   temporary, it is measured, and the follow-up that removes it is written
--   down rather than implied: docs/migration/phases/phase-10b-schema-remediation.md
--   records the exact removal statement and the precondition (the legacy count
--   is zero, verified on a disposable copy) -- which is why this file measures
--   and prints that count instead of not looking at it.
--
-- WHAT DOES NOT HAPPEN HERE
--   * No DROP or RENAME of a table, column, index, type or constraint.  The
--     subjectUserId NOT NULL is RELAXED (ALTER COLUMN ... DROP NOT NULL), which
--     removes a column's nullability restriction and drops no object.
--   * No change to subjectType's vocabulary, and no change to any column's type.
--   * No UPDATE of any existing row.  No deletion, ever.
--   * No NOT NULL added anywhere, and none on ai_run_* (ADR-0009 section 2).
--
-- TIMESTAMPS  nothing in this file changes a timestamp's type.
-- Rollback: ops/flyway/rollback/undo_phase10b.sql re-applies NOT NULL to
-- subjectUserId, drops the new column, FK and index, and drops the new CHECK.
-- It refuses if any row has subjectCustomerId set, because the NOT NULL it
-- restores could not be satisfied by such a row.
-- Every identifier is schema-qualified (the baseline sets search_path = '').
-- =====================================================================

-- ---------------------------------------------------------------------
-- Step 1. MEASURE FIRST: the new CHECK must be safe against existing rows,
-- and the legacy-arm count is the number the follow-up migration needs.
--
-- scopeType/subjectType is NOT NULL so both arms are decidable, and
-- "subjectUserId" IS NULL / IS NOT NULL is never NULL, so the expression has
-- no NULL hole either.
--
--   legacy_customer_rows : rows the LEGACY arm is the only reason to admit.
--                          Must be 0 on a fresh database. Non-zero on a
--                          cutover database is reported, NOT refused -- those
--                          rows are real, they are the record of deletions that
--                          already happened, and refusing here would block the
--                          boot over data this migration is not allowed to
--                          rewrite.
--   cross_tenant_rows   : rows whose existing users FK would already have
--                          refused. Should be 0; a non-zero count means the
--                          pre-existing FK was added after the rows and this
--                          file says so rather than silently widening.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    total               bigint;
    legacy_customer_rows bigint;
    cross_tenant_rows   bigint;
BEGIN
    SELECT count(*) INTO total FROM "public"."privacy_deletion_requests";
    RAISE NOTICE 'V20261006140000: privacy_deletion_requests total rows = %', total;

    -- The LEGACY arm, counted longhand so the report is not a tautology
    -- against the CHECK it is about to install.  The column does not exist yet
    -- at this point and is NULL on every row once it does, so the arm reduces
    -- to "a CUSTOMER request that names a user".
    SELECT count(*) INTO legacy_customer_rows
      FROM "public"."privacy_deletion_requests"
     WHERE "subjectType" = 'CUSTOMER'
       AND "subjectUserId" IS NOT NULL;

    SELECT count(*) INTO cross_tenant_rows
      FROM "public"."privacy_deletion_requests" AS r
     WHERE r."subjectUserId" IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM "public"."users" AS u
                        WHERE u."id" = r."subjectUserId"
                          AND u."organizationId" = r."organizationId");

    IF cross_tenant_rows > 0 THEN
        RAISE EXCEPTION
            'V20261006140000 REFUSED: % privacy_deletion_requests row(s) name a subjectUserId that '
            'is not a user of their own tenant. privacy_deletion_requests_subject_tenant_fkey '
            'should already have refused them, so the FK was added after the rows. This '
            'migration will not widen or drop that FK; resolve the rows first.', cross_tenant_rows;
    END IF;

    RAISE NOTICE
        'V20261006140000: CUSTOMER rows relying on the LEGACY subjectUserId arm = %. '
        'Follow-up F-3 removes that arm once this is 0 on a disposable copy.',
        legacy_customer_rows;
END
$$;

-- ---------------------------------------------------------------------
-- Step 2. The new column. Nullable -- a CUSTOMER subject does not have a
-- user id, which is the entire point.
-- ---------------------------------------------------------------------
ALTER TABLE "public"."privacy_deletion_requests"
    ADD COLUMN IF NOT EXISTS "subjectCustomerId" uuid;

-- ---------------------------------------------------------------------
-- Step 3. Relax the NOT NULL, and only that.
--
-- DROP NOT NULL removes a restriction on a column.  It drops no column, no
-- constraint object and no data.  It is what makes the defect fixable without
-- rewriting the table, and it is reversed by the undo.
-- ---------------------------------------------------------------------
ALTER TABLE "public"."privacy_deletion_requests"
    ALTER COLUMN "subjectUserId" DROP NOT NULL;

-- ---------------------------------------------------------------------
-- Step 4. The composite FK target, then the composite FK.
--
-- customers has no (id, organizationId) unique object in the baseline, so a
-- composite FK needs one as its target.  UNIQUE INDEX, not UNIQUE CONSTRAINT,
-- so V4's unique_constraints = 0 invariant survives -- the technique Phase 6a
-- established and Phase 7-10a used six times.  IF NOT EXISTS, because customers
-- is a pre-existing baseline table and the chain's rule for indexes on
-- pre-existing tables is that they must not fail a boot.
-- ---------------------------------------------------------------------
CREATE UNIQUE INDEX IF NOT EXISTS "customers_id_organizationId_key"
    ON "public"."customers" USING "btree" ("id", "organizationId");

-- A customer subject is scoped to a tenant exactly as a user subject is.  ON
-- DELETE RESTRICT, matching privacy_deletion_requests_subject_tenant_fkey: a
-- customer row cannot be deleted out from under a deletion request about that
-- customer.
ALTER TABLE ONLY "public"."privacy_deletion_requests"
    ADD CONSTRAINT "privacy_deletion_requests_subject_customer_tenant_fkey"
    FOREIGN KEY ("subjectCustomerId", "organizationId")
    REFERENCES "public"."customers"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

-- ---------------------------------------------------------------------
-- Step 5. The subject-reference CHECK. Three arms, both references exclusive.
--
-- Why a disjunction over a NOT NULL text column rather than a CASE: the column
-- is NOT NULL, so the expression is TOTAL -- there is no combination that
-- evaluates to NULL and therefore no row that slips past on a NULL arm.
--
-- The exclusivity ("never both") is inside each arm rather than a separate
-- AND, so there is exactly one place to read which subject a row names.
-- ---------------------------------------------------------------------
ALTER TABLE ONLY "public"."privacy_deletion_requests"
    ADD CONSTRAINT "privacy_deletion_requests_subject_reference_check"
    CHECK (
        (("subjectType" IN ('USER', 'EMPLOYEE'))
             AND "subjectUserId" IS NOT NULL
             AND "subjectCustomerId" IS NULL)
     OR (("subjectType" = 'CUSTOMER')
             AND (("subjectCustomerId" IS NOT NULL AND "subjectUserId" IS NULL)
                  -- LEGACY, TEMPORARY.  See the header: a CUSTOMER request
                  -- recorded before this migration names a users row.  Removed
                  -- by follow-up F-3 once the count is zero.
                  OR ("subjectCustomerId" IS NULL AND "subjectUserId" IS NOT NULL)))
     OR (("subjectType" = 'TENANT')
             AND "subjectUserId" IS NULL
             AND "subjectCustomerId" IS NULL)
    );

-- ---------------------------------------------------------------------
-- Step 6. The read path. Tenant-led, for "every open deletion request naming
-- this customer", which is the query the purge/deletion sweep and the Admin
-- queue both need.
-- ---------------------------------------------------------------------
CREATE INDEX "privacy_deletion_requests_organizationId_subjectCustomerId_idx"
    ON "public"."privacy_deletion_requests" USING "btree"
       ("organizationId", "subjectCustomerId");

-- ---------------------------------------------------------------------
-- Step 7. POST-CONDITION. Assert on the STATE (ADR-0009 section 5).
--
-- (a) the column exists, is nullable, and is uuid;
-- (b) the NEW CHECK exists and names both references;
-- (c) the composite FK exists on BOTH columns against customers on BOTH
--     columns, with ON DELETE RESTRICT -- "a customer subject belongs to a
--     tenant" existing is not enough;
-- (d) the pre-existing USERS FK is still there, still composite and still
--     RESTRICT.  Relaxing the NOT NULL must not have taken the cross-tenant
--     refusal with it;
-- (e) the privacy state machine is intact: verification pair, start-requires-
--     verified, completion, cancellation, rejection reason and evidence
--     reference each still hold for every row.  Nothing in this file was
--     supposed to touch them and this is the proof;
-- (f) zero rows violate the new CHECK.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    col_type      text;
    col_nullable  boolean;
    new_check     text;
    customer_fk   text;
    customer_del  text;
    user_fk       text;
    user_del      text;
    customer_shape boolean;
    user_shape     boolean;
    violations    bigint;
    broken        text;
BEGIN
    -- (a)
    SELECT format_type(a.atttypid, a.atttypmod), NOT a.attnotnull
      INTO col_type, col_nullable
      FROM pg_attribute a
     WHERE a.attrelid = 'public.privacy_deletion_requests'::regclass
       AND a.attname = 'subjectCustomerId'
       AND a.attnum > 0 AND NOT a.attisdropped;

    IF col_type IS NULL THEN
        RAISE EXCEPTION
            'V20261006140000 failed: privacy_deletion_requests."subjectCustomerId" does not exist, '
            'so a CUSTOMER deletion still has nothing to name.';
    END IF;

    IF col_type <> 'uuid' THEN
        RAISE EXCEPTION
            'V20261006140000 failed: "subjectCustomerId" is %, expected uuid.', col_type;
    END IF;

    IF col_nullable IS DISTINCT FROM true THEN
        RAISE EXCEPTION
            'V20261006140000 failed: "subjectCustomerId" is NOT NULL, which is the defect -- a '
            'CUSTOMER subject has no user id to put here.';
    END IF;

    -- and subjectUserId really was relaxed
    IF EXISTS (SELECT 1 FROM pg_attribute
                WHERE attrelid = 'public.privacy_deletion_requests'::regclass
                  AND attname = 'subjectUserId'
                  AND attnum > 0 AND NOT attisdropped
                  AND attnotnull) THEN
        RAISE EXCEPTION
            'V20261006140000 failed: "subjectUserId" is still NOT NULL, so a CUSTOMER request '
            'must still name a users row.';
    END IF;

    -- (b)
    SELECT pg_get_constraintdef(oid) INTO new_check
      FROM pg_constraint
     WHERE conrelid = 'public.privacy_deletion_requests'::regclass
       AND conname = 'privacy_deletion_requests_subject_reference_check';

    IF new_check IS NULL THEN
        RAISE EXCEPTION
            'V20261006140000 failed: privacy_deletion_requests_subject_reference_check is absent.';
    END IF;

    IF new_check NOT LIKE '%subjectUserId%' OR new_check NOT LIKE '%subjectCustomerId%' THEN
        RAISE EXCEPTION
            'V20261006140000 failed: the subject-reference check is "%", which does not mention '
            'both references. A check that names only one leaves the other unconstrained.',
            new_check;
    END IF;

    IF new_check NOT LIKE '%CUSTOMER%' THEN
        RAISE EXCEPTION
            'V20261006140000 failed: the subject-reference check is "%" and has no CUSTOMER arm -- '
            'the defect this migration exists to remove.', new_check;
    END IF;

    -- (c)
    -- Shape first, rendered text second. The shape is read structurally from
    -- pg_constraint conkey / confkey / confrelid because pg_get_constraintdef()
    -- renders "REFERENCES customers(...)" or "REFERENCES public.customers(...)"
    -- depending on the caller's search_path, and the baseline leaves
    -- search_path = '' for the whole connection. Measured on PostgreSQL 16.15
    -- under both settings. See phase-30-migration-chain-bootstrap.md.
    SELECT
        (SELECT array_agg(a.attname::text ORDER BY k.ord)
           FROM unnest(cn.conkey) WITH ORDINALITY AS k(attnum, ord)
           JOIN pg_attribute a ON a.attrelid = cn.conrelid AND a.attnum = k.attnum)
          = ARRAY['subjectCustomerId', 'organizationId']::text[]
        AND cn.confrelid = 'public.customers'::regclass
        AND (SELECT array_agg(a.attname::text ORDER BY k.ord)
               FROM unnest(cn.confkey) WITH ORDINALITY AS k(attnum, ord)
               JOIN pg_attribute a ON a.attrelid = cn.confrelid AND a.attnum = k.attnum)
          = ARRAY['id', 'organizationId']::text[]
      INTO customer_shape
      FROM pg_constraint cn
     WHERE cn.conrelid = 'public.privacy_deletion_requests'::regclass
       AND cn.conname = 'privacy_deletion_requests_subject_customer_tenant_fkey';
        SELECT pg_get_constraintdef(oid), confdeltype
          INTO customer_fk, customer_del
          FROM pg_constraint
         WHERE conrelid = 'public.privacy_deletion_requests'::regclass
           AND conname = 'privacy_deletion_requests_subject_customer_tenant_fkey';
    
    IF customer_shape IS DISTINCT FROM true THEN
        RAISE EXCEPTION
            'V20261006140000 failed: the customer-subject FK is "%" -- a deletion request could '
            'name another tenant''s customer.', coalesce(customer_fk, '(absent)');
    END IF;

    IF customer_del <> 'r' THEN
        RAISE EXCEPTION
            'V20261006140000 failed: the customer-subject FK has confdeltype "%", expected "r" '
            '(RESTRICT). A customer row must not be deletable out from under an open request.',
            customer_del;
    END IF;

    -- (d) the pre-existing users FK must be untouched
    -- Same reasoning as (c): structural, not rendered. This FK is PRE-EXISTING,
    -- so its rendering under search_path = '' carries public. as well -- which is
    -- exactly why a rendered-text pattern for it fails.
    SELECT
        (SELECT array_agg(a.attname::text ORDER BY k.ord)
           FROM unnest(cn.conkey) WITH ORDINALITY AS k(attnum, ord)
           JOIN pg_attribute a ON a.attrelid = cn.conrelid AND a.attnum = k.attnum)
          = ARRAY['subjectUserId', 'organizationId']::text[]
        AND cn.confrelid = 'public.users'::regclass
        AND (SELECT array_agg(a.attname::text ORDER BY k.ord)
               FROM unnest(cn.confkey) WITH ORDINALITY AS k(attnum, ord)
               JOIN pg_attribute a ON a.attrelid = cn.confrelid AND a.attnum = k.attnum)
          = ARRAY['id', 'organizationId']::text[]
      INTO user_shape
      FROM pg_constraint cn
     WHERE cn.conrelid = 'public.privacy_deletion_requests'::regclass
       AND cn.conname = 'privacy_deletion_requests_subject_tenant_fkey';
        SELECT pg_get_constraintdef(oid), confdeltype INTO user_fk, user_del
          FROM pg_constraint
         WHERE conrelid = 'public.privacy_deletion_requests'::regclass
           AND conname = 'privacy_deletion_requests_subject_tenant_fkey';
    
    IF user_shape IS DISTINCT FROM true THEN
        RAISE EXCEPTION
            'V20261006140000 failed: the pre-existing subjectUserId FK is "%". Relaxing the NOT '
            'NULL must not have removed the cross-tenant refusal.', coalesce(user_fk, '(absent)');
    END IF;

    IF user_del <> 'r' THEN
        RAISE EXCEPTION
            'V20261006140000 failed: the subjectUserId FK has confdeltype "%", expected "r" '
            '(RESTRICT). It was RESTRICT before this migration.', user_del;
    END IF;

    -- (e) the privacy state machine, unchanged. Each row names the rule that
    --     is broken rather than printing one opaque count.
    SELECT string_agg(rule, ', ') INTO broken FROM unnest(ARRAY[
        CASE WHEN EXISTS (SELECT 1 FROM "public"."privacy_deletion_requests"
                           WHERE ("verifiedAt" IS NULL) <> ("verifiedByUserId" IS NULL))
             THEN 'verification pair' END,
        CASE WHEN EXISTS (SELECT 1 FROM "public"."privacy_deletion_requests"
                           WHERE "startedAt" IS NOT NULL AND "verifiedAt" IS NULL)
             THEN 'started without verification' END,
        CASE WHEN EXISTS (SELECT 1 FROM "public"."privacy_deletion_requests"
                           WHERE (status = 'COMPLETED') <> ("completedAt" IS NOT NULL))
             THEN 'completion pair' END,
        CASE WHEN EXISTS (SELECT 1 FROM "public"."privacy_deletion_requests"
                           WHERE (status = 'CANCELLED') <> ("cancelledAt" IS NOT NULL))
             THEN 'cancellation pair' END,
        CASE WHEN EXISTS (SELECT 1 FROM "public"."privacy_deletion_requests"
                           WHERE status = 'REJECTED'
                             AND ("rejectionReason" IS NULL
                                  OR length(btrim("rejectionReason")) = 0))
             THEN 'rejection without a reason' END,
        CASE WHEN EXISTS (SELECT 1 FROM "public"."privacy_deletion_requests"
                           WHERE status = 'COMPLETED'
                             AND ("evidenceReference" IS NULL
                                  OR length(btrim("evidenceReference")) = 0))
             THEN 'completed without evidence' END,
        CASE WHEN EXISTS (SELECT 1 FROM "public"."privacy_deletion_requests"
                           WHERE "subjectType" NOT IN ('CUSTOMER','USER','EMPLOYEE','TENANT'))
             THEN 'subjectType outside its vocabulary' END]) AS rule(rule)
     WHERE rule IS NOT NULL;

    IF broken IS NOT NULL THEN
        RAISE EXCEPTION
            'V20261006140000 failed: the privacy state machine is broken (%). This migration is '
            'meant to widen the SUBJECT and nothing else; a request that cannot be verified, '
            'rejected with a reason, or completed with evidence is not a request that can be '
            'acted on.', broken;
    END IF;

    -- (f) zero rows violate the installed subject check
    SELECT count(*) INTO violations
      FROM "public"."privacy_deletion_requests"
     WHERE NOT (
        (("subjectType" IN ('USER', 'EMPLOYEE'))
             AND "subjectUserId" IS NOT NULL
             AND "subjectCustomerId" IS NULL)
     OR (("subjectType" = 'CUSTOMER')
             AND (("subjectCustomerId" IS NOT NULL AND "subjectUserId" IS NULL)
                  OR ("subjectCustomerId" IS NULL AND "subjectUserId" IS NOT NULL)))
     OR (("subjectType" = 'TENANT')
             AND "subjectUserId" IS NULL
             AND "subjectCustomerId" IS NULL)
    );

    IF violations > 0 THEN
        RAISE EXCEPTION
            'V20261006140000 failed: % privacy_deletion_requests row(s) violate the new '
            'subject-reference check. ADD CONSTRAINT validates existing rows, so this must be 0.',
            violations;
    END IF;

    RAISE NOTICE
        'V20261006140000: subjectCustomerId added (uuid, nullable); composite customer FK and '
        'subject-reference check installed; pre-existing composite users FK still RESTRICT; '
        'privacy state machine verified intact; 0 violating rows.';
END
$$;
