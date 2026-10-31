-- =====================================================================
-- V20261018000000__clinic_finding_vocabulary_english_template.sql
-- ADDITIVE.  TRANSACTIONAL.  RELAXES ONE COLUMN, CREATES ONE TABLE,
-- INSERTS ITS ROWS.  DELETES NO EXISTING ROW.
--
-- WHAT THIS CLOSES
--   V20261014000000 created clinic_finding_vocabulary and asserted it stays
--   empty, because DEC-009 puts the vocabulary "pending clinician review" and
--   an Arabic label for a clinical finding is not an engineering decision. The
--   table has therefore been unusable since it was created.
--
--   Two things have since been established:
--
--   1. THERE IS NO ARABIC VOCABULARY. The clinic records findings in English.
--      So labelAr must become OPTIONAL. The alternative - refusing a term with
--      no Arabic - means the odontogram cannot record anything at all.
--
--   2. The terms were enumerated (DEC-009, Jordan, 2026-10-13):
--        tooth status
--        caries             (ICDAS 1-6 attribute, root, recurrent)
--        restorations       (sealant, defective)
--        structure          (fracture, crack, wear, cervical, discoloration, developmental)
--        prosthetic + endo  (crown, veneer, RCT, post, bridge, implant, denture)
--        pulp + periapical
--        periodontal        (mobility 1-3, furcation 1-3, recession, pocket depth, BOP)
--
-- WHY A TEMPLATE TABLE RATHER THAN SEEDING THE CLINIC TABLE
--   clinic_finding_vocabulary is (organizationId, projectId) scoped, because
--   DEC-008 says a clinic IS a project and a clinic may word a finding its own
--   way. But a MIGRATION RUNS BEFORE ANY CLINIC EXISTS, so there is no
--   organizationId to write. Seeding the clinic table here is impossible, not
--   merely awkward.
--
--   So the terms live in a GLOBAL, TENANT-LESS template, and a clinic's own
--   catalogue is COPIED from it the first time that clinic records a finding.
--   The copy is data, not DDL. It also means a term added to the template in a
--   later migration reaches existing clinics only when they re-sync, which is
--   visible, rather than by rewriting their rows behind their backs.
--
-- WHY EVERY TERM IS isClinicianReviewed = FALSE
--   These are the standard, widely published dental findings, but "which
--   findings THIS clinic records" is the clinic's answer and not the standard's.
--   The post-condition asserts that no template row claims otherwise. A
--   clinician confirms terms on the clinic's COPY by UPDATEing rows, so
--   trimming a clinic's vocabulary is data rather than a migration.
--
-- WHAT WAS NOT DONE, AND WHY
--   No external terminology was consulted. A web search for the ICDAS and AAP
--   descriptors was attempted and unavailable, so NOTHING here is presented as
--   externally verified. The scales in attributeSchema are the ones the
--   decision itself named (ICDAS 1-6, mobility 1-3, furcation 1-3), not a
--   quote from a standard this migration cannot cite. A clinician reviewing
--   these rows is doing real work, not rubber-stamping.
--
--   The two categories DEC-009 named WITHOUT enumerating - tooth status and
--   pulp+periapical - carry only the most standard, unambiguous terms, for the
--   same reason.
--
-- GRADES LIVE IN attributeSchema, NOT IN THE CODE
--   Mobility and furcation are graded 1-3, so each is ONE code whose scale and
--   grades are declared in jsonb. A clinic that later adopts a finer scale
--   widens a document rather than accumulating near-duplicate codes, and a
--   finding row records which grade applied.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Step 1. labelAr becomes optional. English-only is a supported reality,
--          not a defect.
-- ---------------------------------------------------------------------
ALTER TABLE "public"."clinic_finding_vocabulary"
    ALTER COLUMN "labelAr" DROP NOT NULL;

COMMENT ON COLUMN "public"."clinic_finding_vocabulary"."labelAr" IS
    'Optional. DEC-009 confirmed on 2026-10-13 that there is no Arabic vocabulary: the clinic '
    'records findings in English. NULL means "no Arabic label yet", which is different from an '
    'empty string and different again from the term being absent.';

-- ---------------------------------------------------------------------
-- Step 2. the global template. Tenant-LESS BY DESIGN, and that is the one
--          place in this chain where an index does not lead with the tenant.
-- ---------------------------------------------------------------------
CREATE TABLE "public"."clinic_finding_vocabulary_template" (
    "id"                uuid NOT NULL,
    "code"              text NOT NULL,
    "category"          "public"."FindingCategory" NOT NULL,
    "labelEn"           text NOT NULL,
    "descriptionEn"     text,
    "attributeSchema"   jsonb,
    "createdAt"         timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,

    CONSTRAINT "clinic_finding_vocabulary_template_code_named"
        CHECK (length(btrim("code")) > 0),

    CONSTRAINT "clinic_finding_vocabulary_template_label_named"
        CHECK (length(btrim("labelEn")) > 0)
);

COMMENT ON TABLE "public"."clinic_finding_vocabulary_template" IS
    'The published dental finding vocabulary, TENANT-LESS and global on purpose. A clinic''s '
    'own catalogue is a copy of this, taken the first time that clinic records a finding. It '
    'cannot be seeded directly into clinic_finding_vocabulary because that table is '
    '(organizationId, projectId) scoped and no clinic exists when a migration runs.';

COMMENT ON COLUMN "public"."clinic_finding_vocabulary_template"."attributeSchema" IS
    'Declares the scale a finding under this code is graded on, e.g. ICDAS 1-6 or mobility 1-3. '
    'The grade itself is stored on the FINDING row, so one code serves every grade.';

-- ---------------------------------------------------------------------
-- Step 3. the terms DEC-009 enumerated
-- ---------------------------------------------------------------------
INSERT INTO "public"."clinic_finding_vocabulary_template"
    ("id", "code", "category", "labelEn", "descriptionEn", "attributeSchema", "createdAt")
SELECT gen_random_uuid(), v.code, v.category::"public"."FindingCategory",
       v.label, v.note, v.attrs::jsonb, (current_timestamp at time zone 'utc')
FROM (VALUES
    -- ---- tooth status -------------------------------------------------
    ('TOOTH-PRESENT',           'TOOTH_STATUS',    'Tooth present',           NULL, NULL),
    ('TOOTH-MISSING',           'TOOTH_STATUS',    'Missing tooth',           NULL, NULL),
    ('TOOTH-IMPACTED',          'TOOTH_STATUS',    'Impacted tooth',          NULL, NULL),
    ('TOOTH-ERUPTING',          'TOOTH_STATUS',    'Partially erupted tooth', NULL, NULL),
    ('TOOTH-UNERUPTED',         'TOOTH_STATUS',    'Unerupted tooth',         NULL, NULL),

    -- ---- caries: the ICDAS 1-6 scale, one code per grade -------------
    ('CARIES-ICDAS-1',          'CARIES',          'Caries ICDAS 1',
        'Initial enamel lesion visible after prolonged air drying.', '{"scale":"ICDAS","grades":[1,2,3,4,5,6]}'),
    ('CARIES-ICDAS-2',          'CARIES',          'Caries ICDAS 2',
        'Initial enamel lesion visible when wet.',                    '{"scale":"ICDAS","grades":[1,2,3,4,5,6]}'),
    ('CARIES-ICDAS-3',          'CARIES',          'Caries ICDAS 3',
        'Localised enamel breakdown with no visible dentine.',        '{"scale":"ICDAS","grades":[1,2,3,4,5,6]}'),
    ('CARIES-ICDAS-4',          'CARIES',          'Caries ICDAS 4',
        'Underlying dark shadow from dentine, with or without localised enamel breakdown.',
                                                                 '{"scale":"ICDAS","grades":[1,2,3,4,5,6]}'),
    ('CARIES-ICDAS-5',          'CARIES',          'Caries ICDAS 5',
        'Distinct cavity with visible dentine.',                      '{"scale":"ICDAS","grades":[1,2,3,4,5,6]}'),
    ('CARIES-ICDAS-6',          'CARIES',          'Caries ICDAS 6',
        'Extensive distinct cavity with visible dentine.',           '{"scale":"ICDAS","grades":[1,2,3,4,5,6]}'),
    ('CARIES-ROOT',             'CARIES',          'Root caries',         NULL, '{"qualifiers":["root"]}'),
    ('CARIES-RECURRENT',        'CARIES',          'Recurrent caries',    NULL, '{"qualifiers":["recurrent"]}'),

    -- ---- restorations -------------------------------------------------
    ('RESTORATION-FILLING',     'RESTORATION',     'Filling',               NULL, NULL),
    ('RESTORATION-SEALANT',     'RESTORATION',     'Sealant',               NULL, NULL),
    ('RESTORATION-DEFECTIVE',   'RESTORATION',     'Defective restoration', NULL, NULL),

    -- ---- structure ----------------------------------------------------
    ('STRUCTURE-FRACTURE',      'STRUCTURE',       'Fracture',              NULL, NULL),
    ('STRUCTURE-CRACK',         'STRUCTURE',       'Crack',                 NULL, NULL),
    ('STRUCTURE-WEAR',          'STRUCTURE',       'Wear',                  NULL, NULL),
    ('STRUCTURE-CERVICAL',      'STRUCTURE',       'Cervical lesion',       NULL, NULL),
    ('STRUCTURE-DISCOLORATION', 'STRUCTURE',       'Discoloration',         NULL, NULL),
    ('STRUCTURE-DEVELOPMENTAL', 'STRUCTURE',       'Developmental defect',  NULL, NULL),

    -- ---- prosthetic and endodontic ------------------------------------
    ('PROSTHETIC-CROWN',        'PROSTHETIC_ENDO', 'Crown',                 NULL, NULL),
    ('PROSTHETIC-VENEER',       'PROSTHETIC_ENDO', 'Veneer',                NULL, NULL),
    ('PROSTHETIC-BRIDGE',       'PROSTHETIC_ENDO', 'Bridge',                NULL, NULL),
    ('PROSTHETIC-IMPLANT',      'PROSTHETIC_ENDO', 'Implant',               NULL, NULL),
    ('PROSTHETIC-DENTURE',      'PROSTHETIC_ENDO', 'Denture',               NULL, NULL),
    ('ENDO-RCT',                'PROSTHETIC_ENDO', 'Root canal treatment',  NULL, NULL),
    ('ENDO-POST',               'PROSTHETIC_ENDO', 'Post',                  NULL, NULL),

    -- ---- pulp and periapical -----------------------------------------
    ('PULP-NO-DIAGNOSIS',       'PULP_PERIAPICAL', 'No pulpal diagnosis',    NULL, NULL),
    ('PULP-PAIN',               'PULP_PERIAPICAL', 'Pulp pain',             NULL, NULL),
    ('PERIAPICAL-LESION',       'PULP_PERIAPICAL', 'Periapical lesion',     NULL, NULL),

    -- ---- periodontal. One code per measure; the grade is an attribute.
    ('PERIODONTAL-MOBILITY',      'PERIODONTAL', 'Tooth mobility',
        NULL, '{"scale":"MOBILITY","grades":[1,2,3]}'),
    ('PERIODONTAL-FURCATION',     'PERIODONTAL', 'Furcation involvement',
        NULL, '{"scale":"FURCATION","grades":[1,2,3]}'),
    ('PERIODONTAL-RECESSION',     'PERIODONTAL', 'Gingival recession',      NULL, NULL),
    ('PERIODONTAL-POCKET-DEPTH',  'PERIODONTAL', 'Pocket depth',
        NULL, '{"unit":"mm"}'),
    ('PERIODONTAL-BOP',           'PERIODONTAL', 'Bleeding on probing',     NULL, NULL)
) AS v(code, category, label, note, attrs);

-- ---------------------------------------------------------------------
-- Step 4. indexes and keys
-- ---------------------------------------------------------------------
ALTER TABLE "public"."clinic_finding_vocabulary_template"
    ADD CONSTRAINT "clinic_finding_vocabulary_template_pkey" PRIMARY KEY ("id");

CREATE UNIQUE INDEX "clinic_finding_vocabulary_template_code_key"
    ON "public"."clinic_finding_vocabulary_template" USING "btree" ("code");

CREATE INDEX "clinic_finding_vocabulary_template_category_idx"
    ON "public"."clinic_finding_vocabulary_template" USING "btree" ("category", "code");

-- ---------------------------------------------------------------------
-- Step 5. post-conditions
-- ---------------------------------------------------------------------
DO $$
DECLARE
    template_rows integer;
    bad_category  integer;
    blank_label   integer;
    unscoped_terms integer;
    ICDAS_SCALE   CONSTANT jsonb := '{"scale":"ICDAS","grades":[1,2,3,4,5,6]}';
    icdas_rows    integer;
    scale_rows    integer;
BEGIN
    SELECT count(*) INTO template_rows
      FROM "public"."clinic_finding_vocabulary_template";

    IF template_rows <> 37 THEN
        RAISE EXCEPTION 'expected 37 seeded finding terms, found %', template_rows;
    END IF;

    -- A term with no label, or no code, is a term nobody can select.
    SELECT count(*) INTO blank_label
      FROM "public"."clinic_finding_vocabulary_template"
     WHERE btrim("labelEn") = '' OR btrim("code") = '';

    IF blank_label <> 0 THEN
        RAISE EXCEPTION '% template terms have a blank code or label', blank_label;
    END IF;

    -- All six ICDAS grades must exist and all six must declare the same scale,
    -- because a finding recorded under ICDAS 3 has to mean ICDAS 3 everywhere.
    SELECT count(*) INTO icdas_rows
      FROM "public"."clinic_finding_vocabulary_template"
     WHERE "code" LIKE 'CARIES-ICDAS-%';

    IF icdas_rows <> 6 THEN
        RAISE EXCEPTION 'expected 6 ICDAS grades, found %', icdas_rows;
    END IF;

    SELECT count(*) INTO scale_rows
      FROM "public"."clinic_finding_vocabulary_template"
     WHERE "code" LIKE 'CARIES-ICDAS-%'
       AND "attributeSchema" = ICDAS_SCALE;

    IF scale_rows <> 6 THEN
        RAISE EXCEPTION 'only % of 6 ICDAS terms declare the same scale, so the grades are not comparable', scale_rows;
    END IF;

    -- The graded periodontal measures must each be ONE code carrying its scale,
    -- not three codes per grade. Six codes named MOBILITY-1..3 would make a
    -- finding's grade part of its identity and stop a finer scale being a
    -- document change.
    SELECT count(*) INTO bad_category
      FROM "public"."clinic_finding_vocabulary_template"
     WHERE "code" IN ('PERIODONTAL-MOBILITY', 'PERIODONTAL-FURCATION')
       AND ("attributeSchema" ->> 'scale') IS NULL;

    IF bad_category <> 0 THEN
        RAISE EXCEPTION '% graded periodontal measures do not declare a scale', bad_category;
    END IF;

    -- The template is deliberately TENANT-LESS. A seeded organizationId here
    -- would be a constant pretending to be a tenant, and every clinic would
    -- then inherit the first clinic's identity.
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = 'clinic_finding_vocabulary_template'
           AND column_name IN ('organizationId', 'projectId')
    ) THEN
        RAISE EXCEPTION 'the template carries tenant columns, so a global catalogue would be '
                        'attributed to whichever tenant happened to be first';
    END IF;

    -- And the clinic catalogue is still empty, which is now CORRECT: a clinic
    -- copies the template when it first records a finding. The earlier
    -- migrations asserted emptiness to keep it usable as a catalogue; this one
    -- asserts it to keep the copy-on-first-use contract honest.
    IF EXISTS (SELECT 1 FROM "public"."clinic_finding_vocabulary") THEN
        RAISE EXCEPTION 'clinic_finding_vocabulary is not empty; the copy-on-first-use contract '
                        'in this migration no longer describes how catalogues are populated';
    END IF;
END
$$;