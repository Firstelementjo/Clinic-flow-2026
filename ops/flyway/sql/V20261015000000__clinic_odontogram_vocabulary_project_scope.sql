-- =====================================================================
-- V20261015000000__clinic_odontogram_vocabulary_project_scope.sql
-- ADDITIVE.  TRANSACTIONAL.  ADDS ONE COLUMN AND REPLACES TWO CONSTRAINTS.
-- DELETES NO ROW AND DESTROYS NO DATA.
--
-- WHAT THIS CLOSES
--   V20261014000000 created clinic_finding_vocabulary with organizationId only,
--   while every other clinic table carries (organizationId, projectId). DEC-008
--   says a clinic IS a project, so the clinical terminology a clinic uses is a
--   property of that clinic, not of the whole tenant. Two clinics in one tenant
--   may legitimately use different wording for the same finding, and under the
--   previous shape the second clinic could not say so.
--
--   The defect was found by running the service, not by reading the migration.
--   The vocabulary query binds ClinicScope.predicate("v"), which emits a
--   project predicate, and PostgreSQL answered:
--
--       ERROR: column v.projectId does not exist
--
--   The migration had been written against a table that did not match the scope
--   every other clinic read uses. This closes that gap rather than weakening the
--   predicate, because a vocabulary query that silently stopped scoping by
--   project would be a cross-clinic read wearing a tenant filter.
--
-- WHY THIS IS SAFE TO DO AS A FOLLOW-UP
--   V20261014000000 asserts that clinic_finding_vocabulary is EMPTY in its
--   post-condition, because DEC-009 leaves the vocabulary pending clinician
--   review. So adding a NOT NULL column needs no backfill and no default, and
--   the unique index can be replaced without any possibility of a duplicate
--   arising. If a future migration seeds terms, this reasoning stops applying
--   and the emptiness assertion below will fail loudly rather than silently.
--
-- NO FOREIGN KEY TO projects, DELIBERATELY
--   patients.projectId is a plain column with no FK to projects, and the chain
--   has no unique index on projects (id, organizationId) to hang one on. Adding
--   one here would make the vocabulary the only clinic table that validates its
--   project, which is a larger change than this defect warrants. If project
--   integrity is ever enforced, it is enforced for patients at the same time.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Step 1. the column. The table is empty, so NOT NULL costs nothing.
-- ---------------------------------------------------------------------
ALTER TABLE "public"."clinic_finding_vocabulary"
    ADD COLUMN "projectId" uuid NOT NULL;

COMMENT ON COLUMN "public"."clinic_finding_vocabulary"."projectId" IS
    'The clinic this terminology belongs to. DEC-008: a clinic IS a project, so the finding '
    'vocabulary is clinic-scoped like every other clinic table. Plain column, with no foreign '
    'key to projects, for the same reason patients.projectId has none.';

-- ---------------------------------------------------------------------
-- Step 2. drop the findings foreign key BEFORE its target index.
--
-- PostgreSQL will not drop an index another object depends on, and the
-- uniqueness of (code, organizationId) IS the target of the existing foreign
-- key. Dropping the index first answers:
--
--     ERROR: cannot drop index clinic_finding_vocabulary_organization_code_key
--            because other objects depend on it
--
-- so the order below is load-bearing rather than stylistic.
-- ---------------------------------------------------------------------
ALTER TABLE "public"."patient_odontogram_findings"
    DROP CONSTRAINT "patient_odontogram_findings_vocabulary_tenant_fkey";

DROP INDEX "public"."clinic_finding_vocabulary_organization_code_key";

-- ---------------------------------------------------------------------
-- Step 3. uniqueness moves from the tenant to the clinic.
-- ---------------------------------------------------------------------
CREATE UNIQUE INDEX "clinic_finding_vocabulary_project_code_key"
    ON "public"."clinic_finding_vocabulary" USING "btree" ("code", "projectId", "organizationId");

-- The clinic's own catalogue listing.
CREATE INDEX "clinic_finding_vocabulary_project_category_idx"
    ON "public"."clinic_finding_vocabulary" USING "btree" ("organizationId", "projectId", "category");

-- ---------------------------------------------------------------------
-- Step 4. the foreign key comes back, now on the clinic-scoped column list, so
--          a finding cannot be written under a code that belongs to a
--          different clinic of the same tenant.
-- ---------------------------------------------------------------------
ALTER TABLE "public"."patient_odontogram_findings"
    ADD CONSTRAINT "patient_odontogram_findings_vocabulary_tenant_fkey"
    FOREIGN KEY ("code", "projectId", "organizationId")
    REFERENCES "public"."clinic_finding_vocabulary" ("code", "projectId", "organizationId")
    ON DELETE RESTRICT;

-- ---------------------------------------------------------------------
-- Step 4. post-conditions
-- ---------------------------------------------------------------------
DO $$
DECLARE
    seeded_vocabulary integer;
    missing_project    integer;
    bad_fk_order      integer;
    tenant_scoped_key  integer;
BEGIN
    -- Still empty. This migration's safety argument depends on it, so it is
    -- re-asserted rather than assumed from the previous file.
    SELECT count(*) INTO seeded_vocabulary
      FROM "public"."clinic_finding_vocabulary";

    IF seeded_vocabulary <> 0 THEN
        RAISE EXCEPTION
            'the finding vocabulary holds % rows, so adding a NOT NULL projectId without a '
            'backfill is no longer the empty-table case this migration was written for', seeded_vocabulary;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = 'clinic_finding_vocabulary'
           AND column_name = 'projectId'
           AND is_nullable = 'NO'
    ) THEN
        RAISE EXCEPTION 'clinic_finding_vocabulary.projectId is missing or nullable';
    END IF;

    -- The old tenant-wide unique index must be gone, or the schema would still
    -- forbid two clinics in one tenant from using the same code for their own
    -- wording, which is the defect this migration exists to fix.
    IF EXISTS (
        SELECT 1 FROM pg_indexes
         WHERE schemaname = 'public'
           AND indexname = 'clinic_finding_vocabulary_organization_code_key'
    ) THEN
        RAISE EXCEPTION 'the tenant-wide vocabulary unique index still exists';
    END IF;

    SELECT count(*) INTO tenant_scoped_key
      FROM pg_indexes
     WHERE schemaname = 'public'
       AND tablename = 'clinic_finding_vocabulary'
       AND indexdef LIKE '%UNIQUE%'
       AND indexname NOT LIKE '%_pkey'
       AND indexdef NOT LIKE '%projectId%';

    IF tenant_scoped_key > 0 THEN
        RAISE EXCEPTION '% vocabulary unique indexes are still tenant-scoped', tenant_scoped_key;
    END IF;

    -- The same positional trap: ("code", "projectId", "organizationId") binds
    -- code -> code, projectId -> projectId. Writing organizationId earlier
    -- would be a different and wrong constraint.
    SELECT count(*) INTO bad_fk_order
      FROM pg_constraint c
      JOIN unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord) ON true
      JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum
     WHERE c.contype = 'f'
       AND c.conname = 'patient_odontogram_findings_vocabulary_tenant_fkey'
       AND a.attname = 'organizationId'
       AND k.ord = 1;

    IF bad_fk_order > 0 THEN
        RAISE EXCEPTION 'the vocabulary foreign key has organizationId first, which is the wrong order';
    END IF;
END
$$;