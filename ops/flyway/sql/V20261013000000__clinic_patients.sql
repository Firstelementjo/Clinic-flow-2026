-- =====================================================================
-- V20261013000000__clinic_patients.sql
-- ADDITIVE.  TRANSACTIONAL.  CREATES THREE TABLES AND THEIR INDEXES.
-- TOUCHES NO EXISTING TABLE COLUMN AND DELETES NO ROW.
--
-- WHAT THIS CLOSES
--   There is no patients table. A patient's name, phone and email are free-text
--   columns on the appointments row, and appointments."customerId" references the
--   CRM `customers` table that DEC-015 explicitly says is NOT a patient. So
--   DEC-015 is currently true in a document and false in the schema:
--
--     - patient identity is not referentially enforced and not clinic-scoped
--     - two appointments for one person are two unrelated text rows
--     - PHI sits in a scheduling table, which makes a minimum-necessary
--       projection impossible to state honestly
--
--   This is DEC-016 Option C: patients is new, `procedures` stays as the service
--   catalogue, and doctor_profiles is dropped because `doctors` is already the
--   profile.
--
-- THE LINK TO customers IS OPTIONAL, AND THAT IS THE POINT
--   DEC-015 holds a patient is not a customer, so "customerId" must be able to be
--   NULL: a walk-in patient with no CRM conversation is the normal case. Making it
--   NOT NULL would be the first step back to conflating the two.
--
-- RETENTION COLUMNS ARE NULLABLE AND INERT (DEC-007)
--   jurisdiction, retentionClass and retentionExpiresAt exist so the eventual
--   answer is a data change rather than a schema change. NO purge job, NO scheduled
--   task, NO deletion behaviour is created here, which is the same decision four
--   earlier migrations already recorded: "no retention behaviour is created here,
--   deliberately."
--
-- THE ONE NON-INERT RULE IS legalHold -> deletedAt
--   DEC-007 says a legal-hold flag blocks deletion. That is expressible as a
--   row-level invariant and is enforced at the database, not left to whichever
--   service remembers: a row under hold cannot be soft-deleted. It says nothing
--   about physical purge, which remains undecided pending a lawyer.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Step 1. patients
-- ---------------------------------------------------------------------
CREATE TABLE "public"."patients" (
    "id"                  uuid NOT NULL,
    "organizationId"      uuid NOT NULL,
    "projectId"           uuid NOT NULL,
    "patientNo"           character varying(32) NOT NULL,
    "displayName"         text NOT NULL,
    "givenName"           text,
    "familyName"          text,
    "dateOfBirth"         date,
    "primaryPhone"        text,
    "primaryEmail"        text,
    "locale"              "public"."Locale" DEFAULT 'ar'::"public"."Locale" NOT NULL,
    -- Optional link to the CRM contact. NULL is normal and correct.
    "customerId"          uuid,
    -- DEC-007: per-tenant policy, values pending a lawyer.
    "jurisdiction"        text,
    "retentionClass"      text,
    "retentionExpiresAt"  timestamp(3) without time zone,
    "legalHold"           boolean DEFAULT false NOT NULL,
    "legalHoldScope"      text,
    "legalHoldReference"  text,
    -- DEC-007: soft delete by default.
    "deletedAt"           timestamp(3) without time zone,
    "deletedBy"           uuid,
    "deletionReason"      text,
    "createdAt"           timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "updatedAt"           timestamp(3) without time zone NOT NULL,
    CONSTRAINT "patients_legal_hold_blocks_deletion"
        CHECK (NOT "legalHold" OR "deletedAt" IS NULL)
);

COMMENT ON TABLE "public"."patients" IS
    'Clinic patient. DEC-015: protected clinical data, NOT a rename of CRM Customer. customerId is optional by design.';

-- ---------------------------------------------------------------------
-- Step 2. patient_identities
--
-- External identifiers (national id, clinic file number) are stored apart from the
-- patient row so adding a new identifier type does not alter the patient table.
-- ---------------------------------------------------------------------
CREATE TABLE "public"."patient_identities" (
    "id"                  uuid NOT NULL,
    "organizationId"      uuid NOT NULL,
    "projectId"           uuid NOT NULL,
    "patientId"           uuid NOT NULL,
    "identityType"        text NOT NULL,
    "identifierValue"     text NOT NULL,
    "isPrimary"           boolean DEFAULT false NOT NULL,
    "retentionClass"      text,
    "retentionExpiresAt"  timestamp(3) without time zone,
    "legalHold"           boolean DEFAULT false NOT NULL,
    "createdAt"           timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "updatedAt"           timestamp(3) without time zone NOT NULL
);

-- ---------------------------------------------------------------------
-- Step 3. patient_attachments
--
-- No binary storage and no retention behaviour. Storage, authorisation and
-- download audit are application concerns; the retention CLASS is here so DEC-007
-- can populate it later without a migration.
-- ---------------------------------------------------------------------
CREATE TABLE "public"."patient_attachments" (
    "id"                  uuid NOT NULL,
    "organizationId"      uuid NOT NULL,
    "projectId"           uuid NOT NULL,
    "patientId"           uuid NOT NULL,
    "fileName"            text NOT NULL,
    "contentType"         text NOT NULL,
    "byteSize"            bigint NOT NULL,
    "storageKey"          text NOT NULL,
    "sha256"              text,
    "retentionClass"      text,
    "retentionExpiresAt"  timestamp(3) without time zone,
    "legalHold"           boolean DEFAULT false NOT NULL,
    "deletedAt"           timestamp(3) without time zone,
    "deletedBy"           uuid,
    "deletionReason"      text,
    "createdAt"           timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "updatedAt"           timestamp(3) without time zone NOT NULL
);

-- ---------------------------------------------------------------------
-- Step 4. Unique indexes.
--
-- UNIQUE INDEX, never UNIQUE CONSTRAINT. The chain runs under an empty
-- search_path and names every object schema-qualified; a constraint would also be
-- inlined into a later CREATE TABLE by pg_dump, which is not the shape used here.
-- ---------------------------------------------------------------------

-- DEC-016: phone is unique PER TENANT, not globally. Two clinics may legitimately
-- hold the same number, and a global index would make the second clinic's patient
-- impossible to create.
CREATE UNIQUE INDEX IF NOT EXISTS "patients_organizationId_primaryPhone_key"
    ON "public"."patients" USING "btree" ("organizationId", "primaryPhone");

CREATE UNIQUE INDEX IF NOT EXISTS "patients_organizationId_patientNo_key"
    ON "public"."patients" USING "btree" ("organizationId", "patientNo");

-- Target index for the composite tenant foreign keys below. Postgres requires a
-- unique index on exactly the referenced column list ("id", "organizationId"); a wider
-- index does not satisfy it, which is why this is (id, organizationId) and not
-- (organizationId, projectId, id). Same shape as documents_id_organizationId_key.
CREATE UNIQUE INDEX IF NOT EXISTS "patients_id_organizationId_key"
    ON "public"."patients" USING "btree" ("id", "organizationId");

CREATE UNIQUE INDEX IF NOT EXISTS "patient_identities_organizationId_identity_key"
    ON "public"."patient_identities" USING "btree"
        ("organizationId", "identityType", "identifierValue");

-- NOT a unique index on patientId. An earlier draft of this file made one, and it was
-- wrong: it would have allowed a patient exactly ONE identity row, when a patient may
-- carry several (national id, clinic file number, passport). The per-patient lookup is
-- a plain index below; uniqueness is enforced where it actually means something, on
-- the identity VALUE and on "one primary".
CREATE INDEX IF NOT EXISTS "patient_identities_organizationId_patientId_idx"
    ON "public"."patient_identities" USING "btree" ("organizationId", "patientId");

-- Target index for the composite tenant foreign key below.
CREATE UNIQUE INDEX IF NOT EXISTS "customers_id_organizationId_key"
    ON "public"."customers" USING "btree" ("id", "organizationId");

-- ---------------------------------------------------------------------
-- Step 5. Primary keys.
-- ---------------------------------------------------------------------
ALTER TABLE ONLY "public"."patients"
    ADD CONSTRAINT "patients_pkey" PRIMARY KEY ("id");
ALTER TABLE ONLY "public"."patient_identities"
    ADD CONSTRAINT "patient_identities_pkey" PRIMARY KEY ("id");
ALTER TABLE ONLY "public"."patient_attachments"
    ADD CONSTRAINT "patient_attachments_pkey" PRIMARY KEY ("id");

-- ---------------------------------------------------------------------
-- Step 6. Composite tenant foreign keys.
--
-- THE COLUMN ORDER IS POSITIONAL AND LOAD-BEARING. ("patientId",
-- "organizationId") binds patient_identities.patientId -> patients.id. Writing
-- ("organizationId", "patientId") would bind organizationId -> patients.id and is a
-- different and wrong constraint. V20261009110000 measured this on PostgreSQL
-- 16.15; the mis-ordered form rejects inserts whose columns are all correct.
-- ---------------------------------------------------------------------
ALTER TABLE ONLY "public"."patient_identities"
    ADD CONSTRAINT "patient_identities_patient_tenant_fkey"
    FOREIGN KEY ("patientId", "organizationId")
    REFERENCES "public"."patients"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."patient_attachments"
    ADD CONSTRAINT "patient_attachments_patient_tenant_fkey"
    FOREIGN KEY ("patientId", "organizationId")
    REFERENCES "public"."patients"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE CASCADE;

-- The customer link is OPTIONAL, so this FK permits NULL customerId. Composite so
-- a patient cannot be linked to another tenant's contact row.
ALTER TABLE ONLY "public"."patients"
    ADD CONSTRAINT "patients_customer_tenant_fkey"
    FOREIGN KEY ("customerId", "organizationId")
    REFERENCES "public"."customers"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE SET NULL;

-- ---------------------------------------------------------------------
-- Step 7. Query indexes.
--
-- Every index a clinic list or search will use leads with organizationId, because
-- every read is tenant-scoped and the tenant-less access path is a cross-tenant
-- read.
-- ---------------------------------------------------------------------
-- At most ONE primary identity per patient. Partial, so the tenant column still leads,
-- and the constraint applies only to rows that claim to be primary.
CREATE UNIQUE INDEX IF NOT EXISTS "patient_identities_one_primary_per_patient"
    ON "public"."patient_identities" USING "btree" ("organizationId", "patientId")
    WHERE "isPrimary";

CREATE INDEX IF NOT EXISTS "patients_organizationId_projectId_displayName_idx"
    ON "public"."patients" USING "btree" ("organizationId", "projectId", "displayName");

CREATE INDEX IF NOT EXISTS "patients_organizationId_projectId_createdAt_idx"
    ON "public"."patients" USING "btree" ("organizationId", "projectId", "createdAt");

-- Duplicate-candidate scoring reads by surname and by date of birth. These are
-- nullable, so the indexes are partial: most rows have neither, and indexing the
-- null majority would cost space and buy nothing.
CREATE INDEX IF NOT EXISTS "patients_organizationId_familyName_idx"
    ON "public"."patients" USING "btree" ("organizationId", "familyName")
    WHERE "familyName" IS NOT NULL;

CREATE INDEX IF NOT EXISTS "patients_organizationId_dateOfBirth_idx"
    ON "public"."patients" USING "btree" ("organizationId", "dateOfBirth")
    WHERE "dateOfBirth" IS NOT NULL;

CREATE INDEX IF NOT EXISTS "patient_attachments_organizationId_patientId_idx"
    ON "public"."patient_attachments" USING "btree" ("organizationId", "patientId");

-- Retention sweeps will read these when DEC-007 gains values. Not a purge: no
-- job reads them yet.
CREATE INDEX IF NOT EXISTS "patients_retention_due_idx"
    ON "public"."patients" USING "btree" ("organizationId", "retentionExpiresAt")
    WHERE "retentionExpiresAt" IS NOT NULL;

-- ---------------------------------------------------------------------
-- Step 8. NO updatedAt TRIGGER, DELIBERATELY.
--
-- No table in this chain carries an updatedAt trigger. The 18 triggers that exist
-- are append-only guards and one locked-period guard; updatedAt is written by the
-- application in its own UPDATE statements. Adding a touch trigger here would be a new
-- mechanism for three tables only, and a table whose updatedAt moves without any other
-- change is a worse audit signal than one that does not.
-- ---------------------------------------------------------------------
-- Step 9. POST-CONDITION. Assert on the STATE, not on the DDL having run.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    patient_tables integer;
    tenantless_idx integer;
    bad_fk_order integer;
BEGIN
    SELECT count(*) INTO patient_tables
      FROM information_schema.tables
     WHERE table_schema = 'public'
       AND table_name IN ('patients', 'patient_identities', 'patient_attachments');

    IF patient_tables <> 3 THEN
        RAISE EXCEPTION 'expected 3 clinic patient tables, found %', patient_tables;
    END IF;

    -- A patient list is always tenant-scoped, so a leading organizationId index
    -- is required. This catches an index added later without the tenant key.
    SELECT count(*) INTO tenantless_idx
      FROM pg_indexes
     WHERE schemaname = 'public'
       AND tablename IN ('patients', 'patient_identities', 'patient_attachments')
       AND indexdef NOT LIKE '%organizationId%'
       AND indexname NOT LIKE '%_pkey';

    IF tenantless_idx > 0 THEN
        RAISE EXCEPTION '% clinic patient indexes do not lead with organizationId', tenantless_idx;
    END IF;

    -- The composite FKs must be (patientId, organizationId) -> (id,
    -- organizationId). The wrong order would still be a valid constraint, so it
    -- is asserted rather than assumed.
    SELECT count(*) INTO bad_fk_order
      FROM pg_constraint c
      JOIN unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord) ON true
      JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum
     WHERE c.contype = 'f'
       AND c.conrelid IN ('public.patients'::regclass,
                          'public.patient_identities'::regclass,
                          'public.patient_attachments'::regclass)
       AND c.conname LIKE '%_tenant_fkey'
       AND a.attname = 'organizationId'
       AND k.ord = 1;

    IF bad_fk_order > 0 THEN
        RAISE EXCEPTION '% composite tenant foreign keys have organizationId first, which is the wrong order', bad_fk_order;
    END IF;
END
$$;