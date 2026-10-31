-- =====================================================================
-- V20261101000000__clinic_governed_ai_assistant.sql
-- Dental Clinic Slice S9 — Governed AI Assistant
--
-- Tables:
--   1. clinic_ai_proposals  -- draft AI proposals requiring human approval
--   2. clinic_ai_governance -- tenant-level kill switch and CLN-09 policy enforcement
--
-- Compliance with ADR V4:
--   - Hand-written JDBC SQL compatibility
--   - Tenant-led composite btree indexes
--   - 0 raw unique constraints (0 pg_constraint.contype = 'u')
--   - Explicit foreign keys to users(id) and patients(id)
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. clinic_ai_proposals: Governed drafts with transparent factors & human approval
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS "public"."clinic_ai_proposals" (
    "id" UUID NOT NULL,
    "organizationId" UUID NOT NULL,
    "projectId" UUID NULL,
    "patientId" UUID NULL,
    "proposalType" VARCHAR(64) NOT NULL,
    "status" VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    "model" VARCHAR(64) NOT NULL,
    "provider" VARCHAR(64) NOT NULL,
    "promptTemplate" VARCHAR(128) NOT NULL,
    "inputPayloadRedacted" JSONB NOT NULL DEFAULT '{}'::jsonb,
    "outputContent" JSONB NOT NULL DEFAULT '{}'::jsonb,
    "transparentFactors" JSONB NOT NULL DEFAULT '{}'::jsonb,
    "confidencePercent" INTEGER NULL,
    "phiRedacted" BOOLEAN NOT NULL DEFAULT true,
    "approvedByUserId" UUID NULL,
    "approvedAt" TIMESTAMPTZ NULL,
    "rejectionReason" TEXT NULL,
    "createdAt" TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "clinic_ai_proposals_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "clinic_ai_proposals_type_check" CHECK (
        "proposalType" IN ('VISIT_SUMMARY', 'RECALL_PRIORITIZATION', 'MISSING_FIELDS', 'PAYMENT_MATCHING', 'PATIENT_MESSAGE')
    ),
    CONSTRAINT "clinic_ai_proposals_status_check" CHECK (
        "status" IN ('DRAFT', 'APPROVED', 'REJECTED', 'DISCARDED')
    ),
    CONSTRAINT "clinic_ai_proposals_confidence_check" CHECK (
        "confidencePercent" IS NULL OR ("confidencePercent" >= 0 AND "confidencePercent" <= 100)
    ),
    CONSTRAINT "clinic_ai_proposals_patient_fkey" FOREIGN KEY ("patientId")
        REFERENCES "public"."patients"("id") ON UPDATE CASCADE ON DELETE SET NULL,
    CONSTRAINT "clinic_ai_proposals_approver_fkey" FOREIGN KEY ("approvedByUserId")
        REFERENCES "public"."users"("id") ON UPDATE CASCADE ON DELETE SET NULL
);

-- Tenant-led composite btree indexes for clinic_ai_proposals
CREATE INDEX IF NOT EXISTS "idx_clinic_ai_prop_org_pat"
    ON "public"."clinic_ai_proposals" USING "btree" ("organizationId", "patientId", "createdAt" DESC);

CREATE INDEX IF NOT EXISTS "idx_clinic_ai_prop_org_status"
    ON "public"."clinic_ai_proposals" USING "btree" ("organizationId", "status", "createdAt" DESC);

CREATE INDEX IF NOT EXISTS "idx_clinic_ai_prop_org_type"
    ON "public"."clinic_ai_proposals" USING "btree" ("organizationId", "proposalType", "createdAt" DESC);

-- ---------------------------------------------------------------------
-- 2. clinic_ai_governance: Emergency AI kill-switch and CLN-09 safeguards
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS "public"."clinic_ai_governance" (
    "id" UUID NOT NULL,
    "organizationId" UUID NOT NULL,
    "projectId" UUID NULL,
    "killSwitchActive" BOOLEAN NOT NULL DEFAULT false,
    "killSwitchReason" TEXT NULL,
    "killSwitchEngagedByUserId" UUID NULL,
    "killSwitchEngagedAt" TIMESTAMPTZ NULL,
    "clinicalSuggestionsDisabled" BOOLEAN NOT NULL DEFAULT true,
    "updatedAt" TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "clinic_ai_governance_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "clinic_ai_governance_user_fkey" FOREIGN KEY ("killSwitchEngagedByUserId")
        REFERENCES "public"."users"("id") ON UPDATE CASCADE ON DELETE SET NULL
);

-- Tenant-led composite btree index for clinic_ai_governance
CREATE INDEX IF NOT EXISTS "idx_clinic_ai_gov_org_proj"
    ON "public"."clinic_ai_governance" USING "btree" ("organizationId", "projectId");

-- ---------------------------------------------------------------------
-- 3. Update clinic_audit_log constraints to allow AI actions & resources
-- ---------------------------------------------------------------------
ALTER TABLE "public"."clinic_audit_log" DROP CONSTRAINT IF EXISTS "chk_clinic_audit_action";
ALTER TABLE "public"."clinic_audit_log" ADD CONSTRAINT "chk_clinic_audit_action" CHECK ("action" IN (
    'READ_PATIENT_RECORD', 'READ_CLINICAL_NOTE', 'READ_ODONTOGRAM', 'VIEW_MEDIA',
    'WRITE_CLINICAL_NOTE', 'SIGN_CLINICAL_NOTE', 'AMEND_CLINICAL_NOTE',
    'BREAK_GLASS_ACTIVATED', 'BREAK_GLASS_REVOKED', 'BREAK_GLASS_REVIEWED',
    'REPORT_GENERATED', 'DATA_EXPORTED', 'PERMISSION_CHANGED', 'LEGAL_HOLD_SET', 'LEGAL_HOLD_REMOVED',
    'AI_KILL_SWITCH_ENGAGED', 'AI_KILL_SWITCH_DISENGAGED',
    'AI_PROPOSAL_GENERATED', 'AI_PROPOSAL_APPROVED', 'AI_PROPOSAL_REJECTED'
));

ALTER TABLE "public"."clinic_audit_log" DROP CONSTRAINT IF EXISTS "chk_clinic_audit_resource";
ALTER TABLE "public"."clinic_audit_log" ADD CONSTRAINT "chk_clinic_audit_resource" CHECK ("resourceType" IN (
    'PATIENT', 'ENCOUNTER', 'CLINICAL_NOTE', 'ODONTOGRAM', 'TREATMENT_PLAN',
    'INVOICE', 'PAYMENT', 'MEDIA', 'REPORT', 'BREAK_GLASS', 'USER_PERMISSION',
    'AI_GOVERNANCE', 'AI_PROPOSAL'
));

