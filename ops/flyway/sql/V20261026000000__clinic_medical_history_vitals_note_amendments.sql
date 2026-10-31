-- V20261026000000: Clinic Medical History, Vitals, and Clinical Note Amendments (Slice S3)
-- Multi-tenant scoped medical history, vital signs on encounters, signing immutability, and note amendments.

-- 1. Patient Medical History (Allergies, Systemic Conditions, Medications)
CREATE TABLE IF NOT EXISTS "public"."patient_medical_histories" (
    "id"                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    "organizationId"        UUID NOT NULL REFERENCES "public"."organizations"("id") ON UPDATE CASCADE ON DELETE CASCADE,
    "projectId"             UUID NOT NULL REFERENCES "public"."projects"("id") ON UPDATE CASCADE ON DELETE CASCADE,
    "patientId"             UUID NOT NULL REFERENCES "public"."patients"("id") ON UPDATE CASCADE ON DELETE CASCADE,
    "allergies"             TEXT NULL,
    "conditions"            TEXT NULL,
    "currentMedications"    TEXT NULL,
    "bleedingDisorder"      BOOLEAN NOT NULL DEFAULT false,
    "cardiacCondition"      BOOLEAN NOT NULL DEFAULT false,
    "diabetes"              BOOLEAN NOT NULL DEFAULT false,
    "notes"                 TEXT NULL,
    "recordedByUserId"      UUID NULL REFERENCES "public"."users"("id") ON UPDATE CASCADE ON DELETE SET NULL,
    "createdAt"             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    "updatedAt"             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC')
);

CREATE UNIQUE INDEX IF NOT EXISTS "uq_patient_medical_histories_org_patient"
    ON "public"."patient_medical_histories" ("organizationId", "patientId");

CREATE UNIQUE INDEX IF NOT EXISTS "uq_patient_medical_histories_org_id"
    ON "public"."patient_medical_histories" ("organizationId", "id");

-- 2. Vitals and Digital Signature columns on clinical_encounters
ALTER TABLE "public"."clinical_encounters"
    ADD COLUMN IF NOT EXISTS "signedAt" TIMESTAMP WITH TIME ZONE NULL,
    ADD COLUMN IF NOT EXISTS "signedByUserId" UUID NULL REFERENCES "public"."users"("id") ON UPDATE CASCADE ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS "signatureHash" VARCHAR(64) NULL,
    ADD COLUMN IF NOT EXISTS "bloodPressureSystolic" INT NULL CHECK ("bloodPressureSystolic" IS NULL OR "bloodPressureSystolic" > 0),
    ADD COLUMN IF NOT EXISTS "bloodPressureDiastolic" INT NULL CHECK ("bloodPressureDiastolic" IS NULL OR "bloodPressureDiastolic" > 0),
    ADD COLUMN IF NOT EXISTS "pulseRate" INT NULL CHECK ("pulseRate" IS NULL OR "pulseRate" > 0),
    ADD COLUMN IF NOT EXISTS "temperatureC" NUMERIC(4, 1) NULL CHECK ("temperatureC" IS NULL OR "temperatureC" > 0);

-- 3. Clinical Note Amendments (Append-only immutable audit trail for signed notes)
CREATE TABLE IF NOT EXISTS "public"."clinical_note_amendments" (
    "id"                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    "organizationId"        UUID NOT NULL REFERENCES "public"."organizations"("id") ON UPDATE CASCADE ON DELETE CASCADE,
    "projectId"             UUID NOT NULL REFERENCES "public"."projects"("id") ON UPDATE CASCADE ON DELETE CASCADE,
    "encounterId"           UUID NOT NULL REFERENCES "public"."clinical_encounters"("id") ON UPDATE CASCADE ON DELETE CASCADE,
    "amendedByUserId"       UUID NOT NULL REFERENCES "public"."users"("id") ON UPDATE CASCADE ON DELETE RESTRICT,
    "amendedAt"             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    "reason"                TEXT NOT NULL,
    "amendmentContent"      TEXT NOT NULL,
    "signatureHash"         VARCHAR(64) NOT NULL,
    "createdAt"             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC')
);

CREATE UNIQUE INDEX IF NOT EXISTS "uq_clinical_note_amendments_org_id"
    ON "public"."clinical_note_amendments" ("organizationId", "id");

CREATE INDEX IF NOT EXISTS "idx_clinical_note_amendments_encounter"
    ON "public"."clinical_note_amendments" ("organizationId", "encounterId", "amendedAt" ASC);
