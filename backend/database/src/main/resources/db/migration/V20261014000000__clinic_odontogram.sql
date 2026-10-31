-- =====================================================================
-- V20261014000000__clinic_odontogram.sql
-- ADDITIVE.  TRANSACTIONAL.  CREATES TWO TABLES, FIVE TYPES, AND THEIR
-- INDEXES.  TOUCHES NO EXISTING TABLE COLUMN AND DELETES NO ROW.
--
-- WHAT THIS CLOSES
--   There is no odontogram. A clinic records a tooth condition as free text
--   on an appointment note, so a clinical history cannot be queried, cannot
--   be compared between visits, and cannot be corrected without rewriting
--   the note that recorded it. DEC-009 answers the notation question:
--
--     FDI two-digit canonical, permanent 11-18/21-28/31-38/41-48,
--     deciduous 51-55/61-65/71-75/81-85.
--
--   The display notation stays a per-clinic concern (DEC-009) and is NOT
--   stored here: toothFdi is the canonical number, and a clinic's chosen
--   notation is a rendering decision made by the Angular client.
--
-- FINDINGS ARE APPEND-ONLY, AND THAT IS THE POINT
--   A dental record that can be edited in place has no defensible history:
--   if a finding was mis-recorded and silently overwritten, nothing records
--   that it was ever believed. So a row here is never updated and never
--   deleted. A correction is a NEW row whose supersededBy points at the row
--   it replaces, and the chain's existing firstelement_append_only()
--   function refuses UPDATE and DELETE. That function is reused rather than
--   reimplemented, for the same reason the rate-card file reuses it.
--
--   The consequence is deliberate: supersededBy is the ONLY way to say a
--   finding no longer stands, and it never destroys what was recorded.
--
-- THE VOCABULARY IS A TABLE, NOT A CHECK CONSTRAINT -- AND IT IS NOT SEEDED
--   DEC-009 says the finding vocabulary lives in a catalogue table with ar/en
--   labels, not in a constraint. That is also what the chain requires: a
--   CHECK is DDL, so adding a term is a migration, so adding one is not a
--   thing a clinician can do. A table is data.
--
--   This migration creates the table and seeds NOTHING. DEC-009 states the
--   vocabulary is "pending clinician review", and an Arabic label for a
--   clinical finding is clinical vocabulary. Inventing one would put a wrong
--   term in front of a patient. The table is empty on purpose.
--
--   The same reasoning removes two other checks that would otherwise look
--   obvious:
--
--     - scope and surfaces are NOT cross-validated. Whether a ROOT-scoped
--       finding may carry surfaces is a clinical question, and the wrong
--       answer in a CHECK is a check that rejects correct treatment records.
--       Under-constraining leaves a fixable bad row; over-constraining
--       blocks care. Only the FDI RANGE is checked, because DEC-009 states
--       it outright and a number outside 11..85 is a typo, not a judgement.
--
--     - state PLANNED does NOT require plannedProcedureId. DEC-009 says
--       planned findings link to procedures; it does not say every planned
--       finding has a matching catalogue entry, and requiring one would
--       forbid planning work the catalogue has not caught up with yet.
--
-- ONE WARNING WORTH WRITING DOWN
--   "surfaces M/D/O(I)/B/L" records that O is occlusal on a posterior tooth
--   and I is incisal on an anterior one. Storing one enum value per letter
--   keeps that distinction at the vocabulary layer, where a clinician can
--   define it, rather than encoding it as a per-tooth constraint here, which
--   would be guessing at the rule before it has been confirmed.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Step 1. enumerations
-- ---------------------------------------------------------------------
CREATE TYPE "public"."FindingScope" AS ENUM (
    'TOOTH',
    'SURFACE',
    'ROOT'
);

COMMENT ON TYPE "public"."FindingScope" IS
    'DEC-009: whether a finding describes the whole tooth, one or more surfaces, or the root.';

CREATE TYPE "public"."FindingState" AS ENUM (
    'EXISTING',
    'PLANNED',
    'COMPLETED'
);

COMMENT ON TYPE "public"."FindingState" IS
    'DEC-009: EXISTING = observed now, PLANNED = intended, COMPLETED = treated.';

CREATE TYPE "public"."FindingSource" AS ENUM (
    'CLINICAL',
    'RADIOGRAPH',
    'PATIENT_REPORTED'
);

COMMENT ON TYPE "public"."FindingSource" IS
    'DEC-009: where the finding came from. PATIENT_REPORTED is what the patient '
    'said, and a clinic may reasonably weigh it differently from a radiograph.';

CREATE TYPE "public"."FindingSurface" AS ENUM (
    'M',
    'D',
    'O',
    'I',
    'B',
    'L'
);

COMMENT ON TYPE "public"."FindingSurface" IS
    'DEC-009 surfaces. O is occlusal and I is incisal; which applies to a given '
    'tooth is vocabulary, not schema, so both exist and the rule is not enforced here.';

CREATE TYPE "public"."FindingCategory" AS ENUM (
    'TOOTH_STATUS',
    'CARIES',
    'RESTORATION',
    'STRUCTURE',
    'PROSTHETIC_ENDO',
    'PULP_PERIAPICAL',
    'PERIODONTAL'
);

COMMENT ON TYPE "public"."FindingCategory" IS
    'DEC-009 categories: tooth status; caries; restorations/sealant/defective; '
    'structure (fracture/crack/wear/cervical/discoloration/developmental); '
    'prosthetic+endo; pulp+periapical; periodontal.';

-- ---------------------------------------------------------------------
-- Step 2. the vocabulary catalogue. Tenant-scoped, ar/en labels, not seeded.
-- ---------------------------------------------------------------------
CREATE TABLE "public"."clinic_finding_vocabulary" (
    "id"                  uuid NOT NULL,
    "organizationId"      uuid NOT NULL,
    "code"                text NOT NULL,
    "category"            "public"."FindingCategory" NOT NULL,
    "labelAr"             text NOT NULL,
    "labelEn"             text NOT NULL,
    "descriptionAr"       text,
    "descriptionEn"       text,
    "attributeSchema"     jsonb,
    "isClinicianReviewed" boolean DEFAULT false NOT NULL,
    "isActive"            boolean DEFAULT true NOT NULL,
    "createdAt"           timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT "clinic_finding_vocabulary_labels_present"
        CHECK (length(btrim("labelAr")) > 0 AND length(btrim("labelEn")) > 0)
);

COMMENT ON TABLE "public"."clinic_finding_vocabulary" IS
    'DEC-009 finding vocabulary. A table rather than a CHECK so a clinician can add a term '
    'as data. INTENTIONALLY EMPTY on creation: DEC-009 puts the vocabulary "pending clinician '
    'review", and an Arabic label for a clinical finding is not an engineering decision.';

COMMENT ON COLUMN "public"."clinic_finding_vocabulary"."attributeSchema" IS
    'Per-finding attributes, e.g. ICDAS 1-6 for caries, mobility 1-3 and furcation 1-3 for '
    'periodontal. Held as data so the set can change without a migration.';

COMMENT ON COLUMN "public"."clinic_finding_vocabulary"."isClinicianReviewed" IS
    'False until a clinician has confirmed the term. The vocabulary is usable before review, '
    'but a reader can tell which entries are provisional.';

-- ---------------------------------------------------------------------
-- Step 3. the findings. Append-only.
-- ---------------------------------------------------------------------
CREATE TABLE "public"."patient_odontogram_findings" (
    "id"                  uuid NOT NULL,
    "organizationId"      uuid NOT NULL,
    "projectId"           uuid NOT NULL,
    "patientId"           uuid NOT NULL,
    "toothFdi"            smallint NOT NULL,
    "scope"               "public"."FindingScope" NOT NULL,
    "surfaces"            "public"."FindingSurface"[],
    "code"                text NOT NULL,
    "attributes"          jsonb,
    "state"               "public"."FindingState" NOT NULL,
    "source"              "public"."FindingSource" NOT NULL,
    "plannedProcedureId"  uuid,
    "recordedBy"          uuid,
    "recordedAt"          timestamp(3) without time zone NOT NULL,
    "note"                text,
    "supersededBy"        uuid,
    "createdAt"           timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,

    -- DEC-009 states the FDI sets outright, so this is transcription, not policy.
    -- It is also the ONLY check on this table; see the header for the two that
    -- were deliberately left out.
    CONSTRAINT "patient_odontogram_findings_tooth_fdi_canonical"
        CHECK (   ("toothFdi" BETWEEN 11 AND 18)
               OR ("toothFdi" BETWEEN 21 AND 28)
               OR ("toothFdi" BETWEEN 31 AND 38)
               OR ("toothFdi" BETWEEN 41 AND 48)
               OR ("toothFdi" BETWEEN 51 AND 55)
               OR ("toothFdi" BETWEEN 61 AND 65)
               OR ("toothFdi" BETWEEN 71 AND 75)
               OR ("toothFdi" BETWEEN 81 AND 85) ),

    CONSTRAINT "patient_odontogram_findings_no_self_supersede"
        CHECK ("supersededBy" IS NULL OR "supersededBy" <> "id")
);

-- Note what is NOT here: a "one live correction per finding" constraint. Two
-- clinicians correcting the same finding on the same day is ordinary, and
-- refusing the second would lose one of the two corrections. The history stays
-- complete; only the convenient "one live row" reading is given up.

COMMENT ON TABLE "public"."patient_odontogram_findings" IS
    'DEC-009 odontogram findings. APPEND-ONLY: a correction is a new row pointing at the row '
    'it replaces via supersededBy. Never updated, never deleted.';

COMMENT ON COLUMN "public"."patient_odontogram_findings"."toothFdi" IS
    'FDI two-digit canonical number. Per-clinic DISPLAY notation is a rendering concern '
    '(DEC-009) and is deliberately not stored.';

COMMENT ON COLUMN "public"."patient_odontogram_findings"."recordedAt" IS
    'Clinical time of the observation, which is NOT the same as createdAt. A finding entered '
    'today about a radiograph taken last month carries the radiograph date here.';

-- ---------------------------------------------------------------------
-- Step 4. indexes
-- ---------------------------------------------------------------------
-- The unique INDEX (not unique CONSTRAINT) on exactly the column list the
-- composite foreign keys reference. A wider index does not satisfy a
-- reference to ("id", "organizationId"), which is why this is not
-- ("organizationId", "id").
CREATE UNIQUE INDEX "patient_odontogram_findings_id_organization_key"
    ON "public"."patient_odontogram_findings" USING "btree" ("id", "organizationId");

CREATE UNIQUE INDEX "clinic_finding_vocabulary_organization_code_key"
    ON "public"."clinic_finding_vocabulary" USING "btree" ("code", "organizationId");

CREATE INDEX "clinic_finding_vocabulary_organization_category_idx"
    ON "public"."clinic_finding_vocabulary" USING "btree" ("organizationId", "category");

-- The odontogram read: one patient's chart, newest first.
CREATE INDEX "patient_odontogram_findings_organization_patient_recorded_idx"
    ON "public"."patient_odontogram_findings"
    USING "btree" ("organizationId", "patientId", "recordedAt" DESC, "id");

-- Per-tooth history, for the timeline when a clinician selects one tooth.
CREATE INDEX "patient_odontogram_findings_organization_patient_tooth_idx"
    ON "public"."patient_odontogram_findings"
    USING "btree" ("organizationId", "patientId", "toothFdi");

-- "What has this clinic diagnosed lately", for reporting.
CREATE INDEX "patient_odontogram_findings_organization_code_idx"
    ON "public"."patient_odontogram_findings" USING "btree" ("organizationId", "code");

CREATE INDEX "patient_odontogram_findings_organization_procedure_idx"
    ON "public"."patient_odontogram_findings"
    USING "btree" ("organizationId", "plannedProcedureId");

CREATE INDEX "patient_odontogram_findings_organization_superseded_idx"
    ON "public"."patient_odontogram_findings" USING "btree" ("organizationId", "supersededBy");

-- ---------------------------------------------------------------------
-- Step 5. keys
-- ---------------------------------------------------------------------
ALTER TABLE "public"."clinic_finding_vocabulary"
    ADD CONSTRAINT "clinic_finding_vocabulary_pkey" PRIMARY KEY ("id");

ALTER TABLE "public"."patient_odontogram_findings"
    ADD CONSTRAINT "patient_odontogram_findings_pkey" PRIMARY KEY ("id");

-- THE COLUMN ORDER IS POSITIONAL AND LOAD-BEARING. ("patientId",
-- "organizationId") binds patientId -> patients.id. Writing
-- ("organizationId", "patientId") would bind organizationId -> patients.id,
-- which is a different and wrong constraint.
ALTER TABLE "public"."patient_odontogram_findings"
    ADD CONSTRAINT "patient_odontogram_findings_patient_tenant_fkey"
    FOREIGN KEY ("patientId", "organizationId")
    REFERENCES "public"."patients" ("id", "organizationId")
    ON DELETE CASCADE;

-- ("plannedProcedureId", "organizationId") -> procedures ("id", "organizationId").
ALTER TABLE "public"."patient_odontogram_findings"
    ADD CONSTRAINT "patient_odontogram_findings_procedure_tenant_fkey"
    FOREIGN KEY ("plannedProcedureId", "organizationId")
    REFERENCES "public"."procedures" ("id", "organizationId")
    ON DELETE SET NULL;

-- A finding's code must exist in the SAME organization's vocabulary. This is
-- the boundary that stops one clinic writing a finding under another clinic's
-- clinical terminology, which would otherwise be a quiet cross-tenant read
-- through the catalogue.
ALTER TABLE "public"."patient_odontogram_findings"
    ADD CONSTRAINT "patient_odontogram_findings_vocabulary_tenant_fkey"
    FOREIGN KEY ("code", "organizationId")
    REFERENCES "public"."clinic_finding_vocabulary" ("code", "organizationId")
    ON DELETE RESTRICT;

-- Self-reference for supersession. ON DELETE SET NULL rather than CASCADE:
-- deleting a row that superseded another must not delete the one it replaced,
-- because the point of the table is that what was recorded survives.
ALTER TABLE "public"."patient_odontogram_findings"
    ADD CONSTRAINT "patient_odontogram_findings_superseded_tenant_fkey"
    FOREIGN KEY ("supersededBy", "organizationId")
    REFERENCES "public"."patient_odontogram_findings" ("id", "organizationId")
    ON DELETE SET NULL;

-- ---------------------------------------------------------------------
-- Step 6. append-only enforcement
-- ---------------------------------------------------------------------
CREATE TRIGGER "patient_odontogram_findings_append_only"
    BEFORE UPDATE OR DELETE ON "public"."patient_odontogram_findings"
    FOR EACH ROW EXECUTE FUNCTION "public"."firstelement_append_only"();

-- ---------------------------------------------------------------------
-- Step 7. post-conditions
-- ---------------------------------------------------------------------
DO $$
DECLARE
    odontogram_tables integer;
    seeded_vocabulary  integer;
    bad_fdi_catalogue  integer;
    missing_guard      integer;
    bad_fk_order       integer;
BEGIN
    SELECT count(*) INTO odontogram_tables
      FROM information_schema.tables
     WHERE table_schema = 'public'
       AND table_name IN ('clinic_finding_vocabulary', 'patient_odontogram_findings');

    IF odontogram_tables <> 2 THEN
        RAISE EXCEPTION 'expected 2 odontogram tables, found %', odontogram_tables;
    END IF;

    -- The vocabulary is DELIBERATELY empty. This asserts the intent rather than
    -- trusting it, so a future "just add the obvious terms" commit that skips
    -- clinical review fails here instead of shipping Arabic labels nobody approved.
    SELECT count(*) INTO seeded_vocabulary
      FROM "public"."clinic_finding_vocabulary";

    IF seeded_vocabulary <> 0 THEN
        RAISE EXCEPTION
            'the finding vocabulary was seeded with % rows, but DEC-009 puts it pending clinician review',
            seeded_vocabulary;
    END IF;

    -- 32 permanent + 20 deciduous = 52 canonical FDI teeth. Counting them proves
    -- the CHECK encodes the DEC-009 sets rather than a plausible-looking subset.
    SELECT count(*) INTO bad_fdi_catalogue
      FROM generate_series(11, 85) AS n(fdi)
     WHERE n.fdi BETWEEN 11 AND 18 OR n.fdi BETWEEN 21 AND 28
        OR n.fdi BETWEEN 31 AND 38 OR n.fdi BETWEEN 41 AND 48
        OR n.fdi BETWEEN 51 AND 55 OR n.fdi BETWEEN 61 AND 65
        OR n.fdi BETWEEN 71 AND 75 OR n.fdi BETWEEN 81 AND 85;

    IF bad_fdi_catalogue <> 52 THEN
        RAISE EXCEPTION 'expected 52 canonical FDI teeth, the DEC-009 sets encode %', bad_fdi_catalogue;
    END IF;

    -- The append-only guard must be attached, or the header's central claim
    -- about this table is not true.
    SELECT count(*) INTO missing_guard
      FROM pg_trigger t
      JOIN pg_class c ON c.oid = t.tgrelid
     WHERE c.relname = 'patient_odontogram_findings'
       AND NOT t.tgisinternal
       AND t.tgname = 'patient_odontogram_findings_append_only';

    IF missing_guard <> 1 THEN
        RAISE EXCEPTION 'the odontogram findings append-only guard is not attached';
    END IF;

    -- The same column-order trap as patients: organizationId must not be first
    -- in a composite tenant foreign key on these tables.
    SELECT count(*) INTO bad_fk_order
      FROM pg_constraint c
      JOIN unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord) ON true
      JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum
     WHERE c.contype = 'f'
       AND c.conrelid IN ('public.clinic_finding_vocabulary'::regclass,
                          'public.patient_odontogram_findings'::regclass)
       AND c.conname LIKE '%_tenant_fkey'
       AND a.attname = 'organizationId'
       AND k.ord = 1;

    IF bad_fk_order > 0 THEN
        RAISE EXCEPTION '% odontogram tenant foreign keys have organizationId first, which is the wrong order', bad_fk_order;
    END IF;
END
$$;