-- V20261020000000: Dental Clinic Treatment Plans (TRT-01 to TRT-05)
-- Multi-tenant scoped treatment plans and ordered procedure items.

CREATE TABLE IF NOT EXISTS treatment_plans (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    "organizationId" UUID NOT NULL REFERENCES organizations(id) ON UPDATE CASCADE ON DELETE CASCADE,
    "projectId" UUID NOT NULL REFERENCES projects(id) ON UPDATE CASCADE ON DELETE CASCADE,
    "patientId" UUID NOT NULL REFERENCES patients(id) ON UPDATE CASCADE ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'ACTIVE', 'COMPLETED', 'CANCELLED')),
    "patientConsent" BOOLEAN NOT NULL DEFAULT false,
    "consentAt" TIMESTAMP WITH TIME ZONE NULL,
    "consentByUserId" UUID NULL REFERENCES users(id) ON UPDATE CASCADE ON DELETE SET NULL,
    "consentNotes" TEXT NULL,
    notes TEXT NULL,
    "createdAt" TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    "updatedAt" TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC')
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_treatment_plan_org_id
    ON treatment_plans ("organizationId", id);

CREATE INDEX IF NOT EXISTS idx_treatment_plans_patient
    ON treatment_plans ("organizationId", "patientId", "createdAt" DESC);

CREATE INDEX IF NOT EXISTS idx_treatment_plans_project
    ON treatment_plans ("organizationId", "projectId", status);

CREATE TABLE IF NOT EXISTS treatment_plan_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    "organizationId" UUID NOT NULL REFERENCES organizations(id) ON UPDATE CASCADE ON DELETE CASCADE,
    "projectId" UUID NOT NULL REFERENCES projects(id) ON UPDATE CASCADE ON DELETE CASCADE,
    "planId" UUID NOT NULL REFERENCES treatment_plans(id) ON UPDATE CASCADE ON DELETE CASCADE,
    "sequenceOrder" INT NOT NULL DEFAULT 1 CHECK ("sequenceOrder" > 0),
    "procedureId" UUID NULL REFERENCES procedures(id) ON UPDATE CASCADE ON DELETE SET NULL,
    "procedureName" VARCHAR(255) NOT NULL,
    "toothFdi" INT NULL CHECK ("toothFdi" IS NULL OR ("toothFdi" >= 11 AND "toothFdi" <= 85)),
    "doctorId" UUID NULL REFERENCES doctors(id) ON UPDATE CASCADE ON DELETE SET NULL,
    "estimatedCost" NUMERIC(12, 3) NOT NULL DEFAULT 0.000 CHECK ("estimatedCost" >= 0),
    "invoicedAmount" NUMERIC(12, 3) NOT NULL DEFAULT 0.000 CHECK ("invoicedAmount" >= 0),
    "collectedAmount" NUMERIC(12, 3) NOT NULL DEFAULT 0.000 CHECK ("collectedAmount" >= 0),
    status VARCHAR(32) NOT NULL DEFAULT 'PROPOSED'
        CHECK (status IN ('PROPOSED', 'APPROVED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
    notes TEXT NULL,
    "completedAt" TIMESTAMP WITH TIME ZONE NULL,
    "createdAt" TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    "updatedAt" TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC')
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_treatment_plan_item_org_id
    ON treatment_plan_items ("organizationId", id);

CREATE INDEX IF NOT EXISTS idx_treatment_plan_items_plan
    ON treatment_plan_items ("organizationId", "planId", "sequenceOrder" ASC);

CREATE INDEX IF NOT EXISTS idx_treatment_plan_items_doctor
    ON treatment_plan_items ("organizationId", "doctorId") WHERE "doctorId" IS NOT NULL;
