-- V20261021000000: Dental Clinical Encounters & Prescriptions (CLN-01 to CLN-09)
-- Multi-tenant scoped clinical encounters, diagnosis tracking, tooth FDI linking, and prescriptions.

CREATE TABLE IF NOT EXISTS clinical_encounters (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    "organizationId" UUID NOT NULL REFERENCES organizations(id) ON UPDATE CASCADE ON DELETE CASCADE,
    "projectId" UUID NOT NULL REFERENCES projects(id) ON UPDATE CASCADE ON DELETE CASCADE,
    "patientId" UUID NOT NULL REFERENCES patients(id) ON UPDATE CASCADE ON DELETE CASCADE,
    "appointmentId" UUID NULL REFERENCES appointments(id) ON UPDATE CASCADE ON DELETE SET NULL,
    "doctorId" UUID NOT NULL REFERENCES doctors(id) ON UPDATE CASCADE ON DELETE RESTRICT,
    "treatmentPlanId" UUID NULL REFERENCES treatment_plans(id) ON UPDATE CASCADE ON DELETE SET NULL,
    "treatmentPlanItemId" UUID NULL REFERENCES treatment_plan_items(id) ON UPDATE CASCADE ON DELETE SET NULL,
    "chiefComplaint" TEXT NOT NULL,
    "examinationNotes" TEXT NULL,
    "diagnosis" TEXT NOT NULL,
    "diagnosisUpdatedAt" TIMESTAMP WITH TIME ZONE NULL,
    "diagnosisUpdatedBy" UUID NULL REFERENCES users(id) ON UPDATE CASCADE ON DELETE SET NULL,
    "clinicalNotes" TEXT NULL,
    "performedProcedures" TEXT NULL,
    "toothFdi" INT NULL CHECK ("toothFdi" IS NULL OR ("toothFdi" >= 11 AND "toothFdi" <= 85)),
    "toothLocation" VARCHAR(100) NULL,
    "status" VARCHAR(32) NOT NULL DEFAULT 'COMPLETED'
        CHECK ("status" IN ('IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
    "visitDate" TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    "durationMinutes" INT NOT NULL DEFAULT 30 CHECK ("durationMinutes" > 0),
    "createdAt" TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    "updatedAt" TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC')
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_clinical_encounters_org_id
    ON clinical_encounters ("organizationId", id);

CREATE INDEX IF NOT EXISTS idx_clinical_encounters_patient
    ON clinical_encounters ("organizationId", "patientId", "visitDate" DESC);

CREATE INDEX IF NOT EXISTS idx_clinical_encounters_doctor
    ON clinical_encounters ("organizationId", "doctorId", "visitDate" DESC);

CREATE INDEX IF NOT EXISTS idx_clinical_encounters_plan
    ON clinical_encounters ("organizationId", "treatmentPlanId") WHERE "treatmentPlanId" IS NOT NULL;

CREATE TABLE IF NOT EXISTS clinical_prescriptions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    "organizationId" UUID NOT NULL REFERENCES organizations(id) ON UPDATE CASCADE ON DELETE CASCADE,
    "projectId" UUID NOT NULL REFERENCES projects(id) ON UPDATE CASCADE ON DELETE CASCADE,
    "encounterId" UUID NOT NULL REFERENCES clinical_encounters(id) ON UPDATE CASCADE ON DELETE CASCADE,
    "patientId" UUID NOT NULL REFERENCES patients(id) ON UPDATE CASCADE ON DELETE CASCADE,
    "doctorId" UUID NOT NULL REFERENCES doctors(id) ON UPDATE CASCADE ON DELETE RESTRICT,
    "medicationName" VARCHAR(255) NOT NULL,
    "dosage" VARCHAR(100) NOT NULL,
    "frequency" VARCHAR(100) NOT NULL,
    "duration" VARCHAR(100) NOT NULL,
    "instructions" TEXT NULL,
    "createdAt" TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    "updatedAt" TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC')
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_clinical_prescriptions_org_id
    ON clinical_prescriptions ("organizationId", id);

CREATE INDEX IF NOT EXISTS idx_clinical_prescriptions_encounter
    ON clinical_prescriptions ("organizationId", "encounterId", "createdAt" ASC);

CREATE INDEX IF NOT EXISTS idx_clinical_prescriptions_patient
    ON clinical_prescriptions ("organizationId", "patientId", "createdAt" DESC);
