-- =====================================================================
-- V20261112000000__clinic_patient_intake_portal.sql
-- ClinicFlow Patient Intake Portal & 9-Step Patient History Persistence
-- ADDITIVE. TOUCHES NO EXISTING ROW DATA DESTRUCTIVELY.
-- =====================================================================

-- 1. Clinic Intake Portal QR key & enable flag on projects (clinics)
ALTER TABLE "public"."projects"
    ADD COLUMN IF NOT EXISTS "intakePortalKey" VARCHAR(64) NULL,
    ADD COLUMN IF NOT EXISTS "intakePortalEnabled" BOOLEAN NOT NULL DEFAULT true;

WITH ranked AS (
    SELECT "id",
           COALESCE(NULLIF(regexp_replace(lower("slug"), '[^a-z0-9_-]+', '-', 'g'), ''), 'clinic') AS base_key,
           ROW_NUMBER() OVER (
               PARTITION BY COALESCE(NULLIF(regexp_replace(lower("slug"), '[^a-z0-9_-]+', '-', 'g'), ''), 'clinic')
               ORDER BY "createdAt" ASC, "id" ASC
           ) AS rn
      FROM "public"."projects"
     WHERE "intakePortalKey" IS NULL
)
UPDATE "public"."projects" p
   SET "intakePortalKey" = CASE
           WHEN r.rn = 1 THEN LEFT(r.base_key, 64)
           ELSE LEFT(r.base_key, 55) || '-' || SUBSTRING(CAST(p."id" AS TEXT), 1, 8)
       END
  FROM ranked r
 WHERE p."id" = r."id"
   AND p."intakePortalKey" IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS "uq_projects_intake_portal_key"
    ON "public"."projects" ("intakePortalKey")
    WHERE "intakePortalKey" IS NOT NULL;

-- 2. Add occupation column to patients for Step 1 Demographics sync
ALTER TABLE "public"."patients"
    ADD COLUMN IF NOT EXISTS "occupation" TEXT NULL;

-- 3. One-time 1-hour intake access codes + short-lived 30-min submission tokens
CREATE TABLE IF NOT EXISTS "public"."patient_intake_codes" (
    "id"                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    "organizationId"        UUID NOT NULL REFERENCES "public"."organizations"("id") ON UPDATE CASCADE ON DELETE CASCADE,
    "projectId"             UUID NOT NULL REFERENCES "public"."projects"("id") ON UPDATE CASCADE ON DELETE CASCADE,
    "patientId"             UUID NOT NULL REFERENCES "public"."patients"("id") ON UPDATE CASCADE ON DELETE CASCADE,
    "codeHash"              VARCHAR(64) NOT NULL,
    "codeHint"              VARCHAR(16) NOT NULL,
    "expiresAt"             TIMESTAMP WITH TIME ZONE NOT NULL,
    "sessionTokenHash"      VARCHAR(64) NULL,
    "sessionExpiresAt"      TIMESTAMP WITH TIME ZONE NULL,
    "verifiedAt"            TIMESTAMP WITH TIME ZONE NULL,
    "usedAt"                TIMESTAMP WITH TIME ZONE NULL,
    "revokedAt"             TIMESTAMP WITH TIME ZONE NULL,
    "failedAttempts"        INT NOT NULL DEFAULT 0,
    "generatedByUserId"     UUID NULL REFERENCES "public"."users"("id") ON UPDATE CASCADE ON DELETE SET NULL,
    "createdAt"             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC')
);

CREATE INDEX IF NOT EXISTS "idx_patient_intake_codes_lookup"
    ON "public"."patient_intake_codes" ("projectId", "codeHash")
    WHERE "usedAt" IS NULL AND "revokedAt" IS NULL;

CREATE INDEX IF NOT EXISTS "idx_patient_intake_codes_session"
    ON "public"."patient_intake_codes" ("projectId", "sessionTokenHash")
    WHERE "usedAt" IS NULL AND "revokedAt" IS NULL AND "sessionTokenHash" IS NOT NULL;

CREATE INDEX IF NOT EXISTS "idx_patient_intake_codes_patient"
    ON "public"."patient_intake_codes" ("organizationId", "patientId", "createdAt" DESC);

-- 4. Full 9-step Patient History & Patient Portal Intake Quarantine records
CREATE TABLE IF NOT EXISTS "public"."patient_history_intakes" (
    "id"                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    "organizationId"        UUID NOT NULL REFERENCES "public"."organizations"("id") ON UPDATE CASCADE ON DELETE CASCADE,
    "projectId"             UUID NOT NULL REFERENCES "public"."projects"("id") ON UPDATE CASCADE ON DELETE CASCADE,
    "patientId"             UUID NOT NULL REFERENCES "public"."patients"("id") ON UPDATE CASCADE ON DELETE CASCADE,
    "encounterId"           UUID NULL REFERENCES "public"."clinical_encounters"("id") ON UPDATE CASCADE ON DELETE SET NULL,
    "status"                VARCHAR(32) NOT NULL DEFAULT 'PENDING_APPROVAL'
        CHECK ("status" IN ('PENDING_APPROVAL', 'APPROVED', 'COMPLETED')),
    "source"                VARCHAR(32) NOT NULL DEFAULT 'PATIENT_PORTAL'
        CHECK ("source" IN ('PATIENT_PORTAL', 'CLINIC_STAFF')),
    "isComplete"            BOOLEAN NOT NULL DEFAULT false,
    "demographicsJson"      JSONB NOT NULL DEFAULT '{}'::jsonb,
    "medicalHistoryJson"    JSONB NOT NULL DEFAULT '{}'::jsonb,
    "dentalHistoryJson"     JSONB NOT NULL DEFAULT '{}'::jsonb,
    "chiefComplaintJson"    JSONB NOT NULL DEFAULT '{}'::jsonb,
    "clinicalExamJson"      JSONB NOT NULL DEFAULT '{}'::jsonb,
    "radiographsJson"       JSONB NOT NULL DEFAULT '{}'::jsonb,
    "diagnosisAndPlanJson"  JSONB NOT NULL DEFAULT '{}'::jsonb,
    "dentalChartJson"       JSONB NOT NULL DEFAULT '{}'::jsonb,
    "informedConsentJson"   JSONB NOT NULL DEFAULT '{}'::jsonb,
    "submittedAt"           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    "approvedAt"            TIMESTAMP WITH TIME ZONE NULL,
    "approvedByUserId"      UUID NULL REFERENCES "public"."users"("id") ON UPDATE CASCADE ON DELETE SET NULL,
    "createdAt"             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    "updatedAt"             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC')
);

CREATE INDEX IF NOT EXISTS "idx_patient_history_intakes_patient"
    ON "public"."patient_history_intakes" ("organizationId", "patientId", "submittedAt" DESC);
