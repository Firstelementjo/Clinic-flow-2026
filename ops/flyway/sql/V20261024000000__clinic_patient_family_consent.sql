-- V20261024000000: Clinic Patient Family, Guardians, Consents, Disclosures and Merge Audit (Slice S1)
-- Multi-tenant scoped relationships, consent governance, HIPAA/DEC-007 disclosure log, and patient merge tracking.

-- 1. Patient Relationships (Guardians, Family, Guarantors)
CREATE TABLE IF NOT EXISTS "public"."patient_relationships" (
    "id"                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    "organizationId"        UUID NOT NULL REFERENCES "public"."organizations"("id") ON UPDATE CASCADE ON DELETE CASCADE,
    "projectId"             UUID NOT NULL REFERENCES "public"."projects"("id") ON UPDATE CASCADE ON DELETE CASCADE,
    "patientId"             UUID NOT NULL REFERENCES "public"."patients"("id") ON UPDATE CASCADE ON DELETE CASCADE,
    "relatedPatientId"      UUID NULL REFERENCES "public"."patients"("id") ON UPDATE CASCADE ON DELETE SET NULL,
    "relationshipType"      VARCHAR(50) NOT NULL
        CHECK ("relationshipType" IN ('PARENT', 'GUARDIAN', 'SPOUSE', 'CHILD', 'SIBLING', 'GUARANTOR', 'OTHER')),
    "customRelationship"    TEXT NULL,
    "relatedPersonName"     TEXT NULL,
    "relatedPersonPhone"    TEXT NULL,
    "isGuarantor"           BOOLEAN DEFAULT false NOT NULL,
    "isEmergencyContact"    BOOLEAN DEFAULT false NOT NULL,
    "notes"                 TEXT NULL,
    "createdAt"             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    "updatedAt"             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC')
);

CREATE UNIQUE INDEX IF NOT EXISTS "uq_patient_relationships_org_id"
    ON "public"."patient_relationships" ("organizationId", "id");

CREATE INDEX IF NOT EXISTS "idx_patient_relationships_patient"
    ON "public"."patient_relationships" ("organizationId", "patientId");

CREATE INDEX IF NOT EXISTS "idx_patient_relationships_related"
    ON "public"."patient_relationships" ("organizationId", "relatedPatientId")
    WHERE "relatedPatientId" IS NOT NULL;

-- 2. Informed Consent Records
CREATE TABLE IF NOT EXISTS "public"."patient_consents" (
    "id"                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    "organizationId"        UUID NOT NULL REFERENCES "public"."organizations"("id") ON UPDATE CASCADE ON DELETE CASCADE,
    "projectId"             UUID NOT NULL REFERENCES "public"."projects"("id") ON UPDATE CASCADE ON DELETE CASCADE,
    "patientId"             UUID NOT NULL REFERENCES "public"."patients"("id") ON UPDATE CASCADE ON DELETE CASCADE,
    "consentType"           VARCHAR(64) NOT NULL
        CHECK ("consentType" IN ('GENERAL_TREATMENT', 'LOCAL_ANESTHESIA', 'SURGERY_EXTRACTION', 'ORTHODONTICS', 'ENDODONTICS', 'DATA_SHARING', 'PHOTOGRAPHY')),
    "status"                VARCHAR(32) NOT NULL DEFAULT 'GRANTED'
        CHECK ("status" IN ('GRANTED', 'REVOKED', 'EXPIRED')),
    "grantedAt"             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    "revokedAt"             TIMESTAMP WITH TIME ZONE NULL,
    "revocationReason"      TEXT NULL,
    "expiresAt"             TIMESTAMP WITH TIME ZONE NULL,
    "signedBy"              VARCHAR(255) NOT NULL,
    "signerRelationship"    VARCHAR(50) DEFAULT 'SELF' NOT NULL,
    "witnessName"           VARCHAR(255) NULL,
    "documentReference"     TEXT NULL,
    "notes"                 TEXT NULL,
    "createdAt"             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    "updatedAt"             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC')
);

CREATE UNIQUE INDEX IF NOT EXISTS "uq_patient_consents_org_id"
    ON "public"."patient_consents" ("organizationId", "id");

CREATE INDEX IF NOT EXISTS "idx_patient_consents_patient"
    ON "public"."patient_consents" ("organizationId", "patientId", "grantedAt" DESC);

-- 3. Patient Disclosure Logs (Legal / PHI Disclosures)
CREATE TABLE IF NOT EXISTS "public"."patient_disclosure_logs" (
    "id"                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    "organizationId"        UUID NOT NULL REFERENCES "public"."organizations"("id") ON UPDATE CASCADE ON DELETE CASCADE,
    "projectId"             UUID NOT NULL REFERENCES "public"."projects"("id") ON UPDATE CASCADE ON DELETE CASCADE,
    "patientId"             UUID NOT NULL REFERENCES "public"."patients"("id") ON UPDATE CASCADE ON DELETE CASCADE,
    "disclosedTo"           VARCHAR(255) NOT NULL,
    "purpose"               TEXT NOT NULL,
    "recordsDisclosed"      TEXT NOT NULL,
    "disclosedAt"           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    "disclosedBy"           UUID NOT NULL REFERENCES "public"."users"("id") ON UPDATE CASCADE ON DELETE RESTRICT,
    "createdAt"             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC')
);

CREATE UNIQUE INDEX IF NOT EXISTS "uq_patient_disclosure_logs_org_id"
    ON "public"."patient_disclosure_logs" ("organizationId", "id");

CREATE INDEX IF NOT EXISTS "idx_patient_disclosure_logs_patient"
    ON "public"."patient_disclosure_logs" ("organizationId", "patientId", "disclosedAt" DESC);

-- 4. Patient Merge Audit (Reversible Merge Tracking)
CREATE TABLE IF NOT EXISTS "public"."patient_merges" (
    "id"                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    "organizationId"        UUID NOT NULL REFERENCES "public"."organizations"("id") ON UPDATE CASCADE ON DELETE CASCADE,
    "projectId"             UUID NOT NULL REFERENCES "public"."projects"("id") ON UPDATE CASCADE ON DELETE CASCADE,
    "sourcePatientId"       UUID NOT NULL,
    "targetPatientId"       UUID NOT NULL,
    "mergedBy"              UUID NOT NULL REFERENCES "public"."users"("id") ON UPDATE CASCADE ON DELETE RESTRICT,
    "mergedAt"              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    "snapshotData"          JSONB NOT NULL,
    "status"                VARCHAR(32) NOT NULL DEFAULT 'MERGED'
        CHECK ("status" IN ('MERGED', 'REVERSED')),
    "reversedAt"            TIMESTAMP WITH TIME ZONE NULL,
    "reversedBy"            UUID NULL REFERENCES "public"."users"("id") ON UPDATE CASCADE ON DELETE SET NULL,
    "createdAt"             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC')
);

CREATE UNIQUE INDEX IF NOT EXISTS "uq_patient_merges_org_id"
    ON "public"."patient_merges" ("organizationId", "id");

CREATE INDEX IF NOT EXISTS "idx_patient_merges_target"
    ON "public"."patient_merges" ("organizationId", "targetPatientId");
