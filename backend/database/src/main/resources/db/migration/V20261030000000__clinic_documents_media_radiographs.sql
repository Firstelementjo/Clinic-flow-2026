-- V20261030000000: Dental Clinic Documents, Clinical Radiographs, Photos & Media (Slice S7)
-- Multi-tenant media attachments, radiograph categorization, FDI tooth linkage,
-- access auditing, legal hold retention, and storage metadata.

-- 1. Ensure composite tenant unique index on patient_attachments
CREATE UNIQUE INDEX IF NOT EXISTS uq_patient_attachments_org_id
    ON "public"."patient_attachments" ("organizationId", "id");

-- 2. Add clinical media columns to patient_attachments
ALTER TABLE "public"."patient_attachments"
    ADD COLUMN IF NOT EXISTS "category" VARCHAR(64) NOT NULL DEFAULT 'DOCUMENT_PDF',
    ADD COLUMN IF NOT EXISTS "toothFdi" INT NULL,
    ADD COLUMN IF NOT EXISTS "acquisitionDate" TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    ADD COLUMN IF NOT EXISTS "radiographerName" TEXT NULL,
    ADD COLUMN IF NOT EXISTS "encrypted" BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN IF NOT EXISTS "encryptionAlgorithm" VARCHAR(32) NOT NULL DEFAULT 'AES-256-GCM',
    ADD COLUMN IF NOT EXISTS "scanStatus" VARCHAR(32) NOT NULL DEFAULT 'CLEAN',
    ADD COLUMN IF NOT EXISTS "notes" TEXT NULL;

-- 3. Add CHECK constraints on category, toothFdi, scanStatus
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'patient_attachments_category_check'
    ) THEN
        ALTER TABLE "public"."patient_attachments" ADD CONSTRAINT patient_attachments_category_check
            CHECK ("category" IN (
                'RADIOGRAPH_INTRAORAL',
                'RADIOGRAPH_PANORAMIC',
                'RADIOGRAPH_CEPHALOMETRIC',
                'PHOTO_INTRAORAL',
                'PHOTO_EXTRAORAL',
                'DOCUMENT_PDF',
                'CONSENT_FORM',
                'LAB_PRESCRIPTION',
                'OTHER'
            ));
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'patient_attachments_tooth_fdi_check'
    ) THEN
        ALTER TABLE "public"."patient_attachments" ADD CONSTRAINT patient_attachments_tooth_fdi_check
            CHECK ("toothFdi" IS NULL OR ("toothFdi" BETWEEN 11 AND 85));
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'patient_attachments_scan_status_check'
    ) THEN
        ALTER TABLE "public"."patient_attachments" ADD CONSTRAINT patient_attachments_scan_status_check
            CHECK ("scanStatus" IN ('CLEAN', 'INFECTED', 'PENDING', 'FAILED'));
    END IF;
END $$;

-- 4. Clinical media access audit trail (views, downloads, hold adjustments, deletions)
CREATE TABLE IF NOT EXISTS "public"."clinic_media_access_audit" (
    "id" UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    "organizationId" UUID NOT NULL REFERENCES organizations(id) ON UPDATE CASCADE ON DELETE CASCADE,
    "projectId" UUID NOT NULL REFERENCES projects(id) ON UPDATE CASCADE ON DELETE CASCADE,
    "patientId" UUID NOT NULL REFERENCES patients(id) ON UPDATE CASCADE ON DELETE CASCADE,
    "attachmentId" UUID NOT NULL REFERENCES patient_attachments(id) ON UPDATE CASCADE ON DELETE CASCADE,
    "action" VARCHAR(32) NOT NULL
        CHECK ("action" IN ('VIEW', 'DOWNLOAD', 'SIGNED_URL_MINTED', 'LEGAL_HOLD_SET', 'LEGAL_HOLD_REMOVED', 'SOFT_DELETED')),
    "accessedByUserId" UUID NOT NULL REFERENCES users(id) ON UPDATE CASCADE ON DELETE RESTRICT,
    "accessedByUserName" TEXT NOT NULL,
    "accessIp" VARCHAR(64) NULL,
    "notes" TEXT NULL,
    "accessedAt" TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC')
);

-- 5. Tenant-led composite indexes (0 UNIQUE constraints per ADR V4)
CREATE UNIQUE INDEX IF NOT EXISTS uq_clinic_media_access_audit_org_id
    ON "public"."clinic_media_access_audit" ("organizationId", "id");

CREATE INDEX IF NOT EXISTS idx_clinic_media_access_audit_attachment
    ON "public"."clinic_media_access_audit" ("organizationId", "attachmentId", "accessedAt" DESC);

CREATE INDEX IF NOT EXISTS idx_clinic_media_access_audit_patient
    ON "public"."clinic_media_access_audit" ("organizationId", "patientId", "accessedAt" DESC);

CREATE INDEX IF NOT EXISTS idx_patient_attachments_org_patient_cat
    ON "public"."patient_attachments" ("organizationId", "patientId", "category")
    WHERE "deletedAt" IS NULL;

CREATE INDEX IF NOT EXISTS idx_patient_attachments_org_patient_tooth
    ON "public"."patient_attachments" ("organizationId", "patientId", "toothFdi")
    WHERE "deletedAt" IS NULL AND "toothFdi" IS NOT NULL;
