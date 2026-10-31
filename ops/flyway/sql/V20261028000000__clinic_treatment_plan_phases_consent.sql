-- V20261028000000: Dental Clinic Treatment Plan Phases, Versioning, Estimates, and Consent
-- Extends treatment_plans and treatment_plan_items with versioning, alternative plans, multi-visit stages, financial estimates, and immutable approval.

-- 1. Extend treatment_plans status constraint to support PRESENTED status
ALTER TABLE treatment_plans DROP CONSTRAINT IF EXISTS treatment_plans_status_check;

ALTER TABLE treatment_plans
    ADD CONSTRAINT treatment_plans_status_check
    CHECK (status IN ('DRAFT', 'PRESENTED', 'ACTIVE', 'COMPLETED', 'CANCELLED'));

-- 2. Add versioning, plan type, financial estimates, and approval/cancellation fields to treatment_plans
ALTER TABLE treatment_plans
    ADD COLUMN IF NOT EXISTS "version" INT NOT NULL DEFAULT 1 CHECK ("version" >= 1),
    ADD COLUMN IF NOT EXISTS "parentPlanId" UUID NULL REFERENCES treatment_plans(id) ON UPDATE CASCADE ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS "planType" VARCHAR(32) NOT NULL DEFAULT 'PRIMARY' CHECK ("planType" IN ('PRIMARY', 'ALTERNATIVE', 'MAINTENANCE')),
    ADD COLUMN IF NOT EXISTS "totalDiscount" NUMERIC(12, 3) NOT NULL DEFAULT 0.000 CHECK ("totalDiscount" >= 0),
    ADD COLUMN IF NOT EXISTS "insuranceEstimatedCoverage" NUMERIC(12, 3) NOT NULL DEFAULT 0.000 CHECK ("insuranceEstimatedCoverage" >= 0),
    ADD COLUMN IF NOT EXISTS "patientEstimatedPortion" NUMERIC(12, 3) NOT NULL DEFAULT 0.000 CHECK ("patientEstimatedPortion" >= 0),
    ADD COLUMN IF NOT EXISTS "approvedAt" TIMESTAMP WITH TIME ZONE NULL,
    ADD COLUMN IF NOT EXISTS "approvedByUserId" UUID NULL REFERENCES users(id) ON UPDATE CASCADE ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS "cancellationReason" TEXT NULL,
    ADD COLUMN IF NOT EXISTS "cancelledAt" TIMESTAMP WITH TIME ZONE NULL;

CREATE INDEX IF NOT EXISTS idx_treatment_plans_parent
    ON treatment_plans ("organizationId", "parentPlanId") WHERE "parentPlanId" IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_treatment_plans_version
    ON treatment_plans ("organizationId", "patientId", "version" DESC);

-- 3. Add stages, multi-visit counts, item-level discounts, and outcome notes to treatment_plan_items
ALTER TABLE treatment_plan_items
    ADD COLUMN IF NOT EXISTS "stage" VARCHAR(64) NOT NULL DEFAULT 'PHASE_1',
    ADD COLUMN IF NOT EXISTS "plannedVisits" INT NOT NULL DEFAULT 1 CHECK ("plannedVisits" >= 1),
    ADD COLUMN IF NOT EXISTS "completedVisits" INT NOT NULL DEFAULT 0 CHECK ("completedVisits" >= 0),
    ADD COLUMN IF NOT EXISTS "discountAmount" NUMERIC(12, 3) NOT NULL DEFAULT 0.000 CHECK ("discountAmount" >= 0),
    ADD COLUMN IF NOT EXISTS "insuranceCoverage" NUMERIC(12, 3) NOT NULL DEFAULT 0.000 CHECK ("insuranceCoverage" >= 0),
    ADD COLUMN IF NOT EXISTS "patientPortion" NUMERIC(12, 3) NOT NULL DEFAULT 0.000 CHECK ("patientPortion" >= 0),
    ADD COLUMN IF NOT EXISTS "outcomeNotes" TEXT NULL;

CREATE INDEX IF NOT EXISTS idx_treatment_plan_items_stage
    ON treatment_plan_items ("organizationId", "planId", "stage");
