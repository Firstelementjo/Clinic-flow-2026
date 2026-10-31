-- V20261027000000: Clinic Periodontics and 6-Site Probing Charts (Slice S4)
-- Multi-tenant periodontal examination sessions and 6-site probing depth, recession, derived CAL, and bleeding metrics.

-- 1. Periodontal Examination Sessions
CREATE TABLE IF NOT EXISTS "public"."patient_periodontal_exams" (
    "id"                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    "organizationId"        UUID NOT NULL REFERENCES "public"."organizations"("id") ON UPDATE CASCADE ON DELETE CASCADE,
    "projectId"             UUID NOT NULL REFERENCES "public"."projects"("id") ON UPDATE CASCADE ON DELETE CASCADE,
    "patientId"             UUID NOT NULL REFERENCES "public"."patients"("id") ON UPDATE CASCADE ON DELETE CASCADE,
    "examinedByUserId"      UUID NULL REFERENCES "public"."users"("id") ON UPDATE CASCADE ON DELETE SET NULL,
    "examinedAt"            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    "notes"                 TEXT NULL,
    "createdAt"             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC')
);

CREATE UNIQUE INDEX IF NOT EXISTS "uq_patient_periodontal_exams_org_id"
    ON "public"."patient_periodontal_exams" ("organizationId", "id");

CREATE INDEX IF NOT EXISTS "idx_patient_periodontal_exams_org_patient_date"
    ON "public"."patient_periodontal_exams" ("organizationId", "patientId", "examinedAt" DESC);

-- 2. Periodontal 6-Site Probing Measurements per Tooth
CREATE TABLE IF NOT EXISTS "public"."patient_periodontal_measurements" (
    "id"                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    "organizationId"        UUID NOT NULL REFERENCES "public"."organizations"("id") ON UPDATE CASCADE ON DELETE CASCADE,
    "projectId"             UUID NOT NULL REFERENCES "public"."projects"("id") ON UPDATE CASCADE ON DELETE CASCADE,
    "examId"                UUID NOT NULL REFERENCES "public"."patient_periodontal_exams"("id") ON UPDATE CASCADE ON DELETE CASCADE,
    "patientId"             UUID NOT NULL REFERENCES "public"."patients"("id") ON UPDATE CASCADE ON DELETE CASCADE,
    "toothFdi"              SMALLINT NOT NULL,

    -- 6-site Probing Depth (PD, mm: 0 to 20)
    "probingDepthMb"        SMALLINT NULL CHECK ("probingDepthMb" IS NULL OR ("probingDepthMb" >= 0 AND "probingDepthMb" <= 20)),
    "probingDepthB"         SMALLINT NULL CHECK ("probingDepthB" IS NULL OR ("probingDepthB" >= 0 AND "probingDepthB" <= 20)),
    "probingDepthDb"        SMALLINT NULL CHECK ("probingDepthDb" IS NULL OR ("probingDepthDb" >= 0 AND "probingDepthDb" <= 20)),
    "probingDepthMl"        SMALLINT NULL CHECK ("probingDepthMl" IS NULL OR ("probingDepthMl" >= 0 AND "probingDepthMl" <= 20)),
    "probingDepthL"         SMALLINT NULL CHECK ("probingDepthL" IS NULL OR ("probingDepthL" >= 0 AND "probingDepthL" <= 20)),
    "probingDepthDl"        SMALLINT NULL CHECK ("probingDepthDl" IS NULL OR ("probingDepthDl" >= 0 AND "probingDepthDl" <= 20)),

    -- 6-site Gingival Margin Recession (GM, mm: -20 to 20)
    "gingivalMarginMb"      SMALLINT NULL CHECK ("gingivalMarginMb" IS NULL OR ("gingivalMarginMb" >= -20 AND "gingivalMarginMb" <= 20)),
    "gingivalMarginB"       SMALLINT NULL CHECK ("gingivalMarginB" IS NULL OR ("gingivalMarginB" >= -20 AND "gingivalMarginB" <= 20)),
    "gingivalMarginDb"      SMALLINT NULL CHECK ("gingivalMarginDb" IS NULL OR ("gingivalMarginDb" >= -20 AND "gingivalMarginDb" <= 20)),
    "gingivalMarginMl"      SMALLINT NULL CHECK ("gingivalMarginMl" IS NULL OR ("gingivalMarginMl" >= -20 AND "gingivalMarginMl" <= 20)),
    "gingivalMarginL"       SMALLINT NULL CHECK ("gingivalMarginL" IS NULL OR ("gingivalMarginL" >= -20 AND "gingivalMarginL" <= 20)),
    "gingivalMarginDl"      SMALLINT NULL CHECK ("gingivalMarginDl" IS NULL OR ("gingivalMarginDl" >= -20 AND "gingivalMarginDl" <= 20)),

    -- 6-site Derived Clinical Attachment Loss (CAL = PD + GM)
    "calMb"                 SMALLINT NULL,
    "calB"                  SMALLINT NULL,
    "calDb"                 SMALLINT NULL,
    "calMl"                 SMALLINT NULL,
    "calL"                  SMALLINT NULL,
    "calDl"                 SMALLINT NULL,

    -- 6-site Bleeding on Probing (BOP)
    "bopMb"                 BOOLEAN NOT NULL DEFAULT false,
    "bopB"                  BOOLEAN NOT NULL DEFAULT false,
    "bopDb"                 BOOLEAN NOT NULL DEFAULT false,
    "bopMl"                 BOOLEAN NOT NULL DEFAULT false,
    "bopL"                  BOOLEAN NOT NULL DEFAULT false,
    "bopDl"                 BOOLEAN NOT NULL DEFAULT false,

    -- 6-site Suppuration / Pus
    "suppurationMb"         BOOLEAN NOT NULL DEFAULT false,
    "suppurationB"          BOOLEAN NOT NULL DEFAULT false,
    "suppurationDb"         BOOLEAN NOT NULL DEFAULT false,
    "suppurationMl"         BOOLEAN NOT NULL DEFAULT false,
    "suppurationL"          BOOLEAN NOT NULL DEFAULT false,
    "suppurationDl"         BOOLEAN NOT NULL DEFAULT false,

    -- 6-site Plaque
    "plaqueMb"              BOOLEAN NOT NULL DEFAULT false,
    "plaqueB"               BOOLEAN NOT NULL DEFAULT false,
    "plaqueDb"              BOOLEAN NOT NULL DEFAULT false,
    "plaqueMl"              BOOLEAN NOT NULL DEFAULT false,
    "plaqueL"               BOOLEAN NOT NULL DEFAULT false,
    "plaqueDl"              BOOLEAN NOT NULL DEFAULT false,

    -- Tooth-level indices: furcation (0..4) and mobility (0..3)
    "furcationClass"        SMALLINT NULL CHECK ("furcationClass" IS NULL OR ("furcationClass" >= 0 AND "furcationClass" <= 4)),
    "mobilityGrade"         SMALLINT NULL CHECK ("mobilityGrade" IS NULL OR ("mobilityGrade" >= 0 AND "mobilityGrade" <= 3)),

    "createdAt"             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),

    CONSTRAINT "patient_periodontal_measurements_tooth_fdi_canonical"
        CHECK (("toothFdi" >= 11 AND "toothFdi" <= 48) OR ("toothFdi" >= 51 AND "toothFdi" <= 85))
);

CREATE UNIQUE INDEX IF NOT EXISTS "uq_patient_periodontal_measurements_org_id"
    ON "public"."patient_periodontal_measurements" ("organizationId", "id");

CREATE UNIQUE INDEX IF NOT EXISTS "uq_patient_periodontal_measurements_exam_tooth"
    ON "public"."patient_periodontal_measurements" ("organizationId", "examId", "toothFdi");

CREATE INDEX IF NOT EXISTS "idx_patient_periodontal_measurements_patient_tooth"
    ON "public"."patient_periodontal_measurements" ("organizationId", "patientId", "toothFdi");
