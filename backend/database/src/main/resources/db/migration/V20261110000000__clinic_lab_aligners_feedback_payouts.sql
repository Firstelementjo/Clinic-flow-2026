-- V20261110000000: Lab, Clear Aligner Lab, Patient Feedback and Doctor Payouts
--
-- Phase 40 completes the four operations screens the ToothLogic clinic
-- administration shows beyond billing and reporting:
--
--   * clinic_lab_orders - the prosthetic lab pipeline. Until now the table
--     was created lazily by EncounterRepository.ensureLabOrdersTable() on the
--     first request, which makes the schema a property of request order
--     rather than of the migration chain. This migration owns the table, and
--     folds the drawer's impression type, multi-tooth selection and uploaded
--     filenames into it so the screen can stop keeping them in memory.
--
--   * clinic_aligner_cases - the clear-aligner pipeline: case type, arch
--     steps, IPR, attachments, shade and the Rx notes the drawer records.
--
--   * clinic_feedback - patient feedback is a clinic record, not a survey
--     attachment: it names the patient, the doctor and the service, and it
--     stores the five ten-point ratings the list renders.
--
--   * clinic_doctor_payouts - one row per doctor per settled period. The
--     commission percentage itself already lives on doctors.commission from
--     Phase 39; a payout snapshots the rate it was computed with, because
--     changing the rate tomorrow must not rewrite what was paid last month.
--
-- Tenant integrity follows the project's established composite-key pattern:
-- ("id", "organizationId") is unique on patients and doctors, so every
-- cross-table reference carries both columns. Deletes are RESTRICT: a lab
-- case or a feedback row that names a patient is a record, not a child.
--
-- Every added column is nullable or defaulted. The lab table may already
-- exist from the lazy create, so its definition is CREATE IF NOT EXISTS and
-- every extra column is ADD COLUMN IF NOT EXISTS.

-- ---------------------------------------------------------------------
-- clinic_lab_orders: the prosthetic lab pipeline
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS "clinic_lab_orders" (
    "id" uuid NOT NULL DEFAULT gen_random_uuid(),
    "organizationId" uuid NOT NULL,
    "projectId" uuid NOT NULL,
    "patientId" uuid NOT NULL,
    "doctorId" uuid NULL,
    "labName" varchar(255) NOT NULL,
    "workType" varchar(255) NOT NULL,
    "shade" varchar(64) NULL,
    "toothFdi" integer NULL,
    "status" varchar(40) NOT NULL DEFAULT 'SENT_TO_LAB',
    "sentDate" timestamp without time zone NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    "dueDate" timestamp without time zone NULL,
    "receivedDate" timestamp without time zone NULL,
    "cost" numeric(12, 3) NOT NULL DEFAULT 0.000,
    "currency" varchar(10) NOT NULL DEFAULT 'JOD',
    "notes" text NULL,
    "createdAt" timestamp without time zone NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    "updatedAt" timestamp without time zone NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    CONSTRAINT "clinic_lab_orders_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "clinic_lab_orders_patient_tenant_fkey"
        FOREIGN KEY ("patientId", "organizationId")
        REFERENCES "patients" ("id", "organizationId") ON DELETE RESTRICT,
    CONSTRAINT "clinic_lab_orders_doctor_tenant_fkey"
        FOREIGN KEY ("doctorId", "organizationId")
        REFERENCES "doctors" ("id", "organizationId") ON DELETE RESTRICT,
    CONSTRAINT "clinic_lab_orders_work_type_check"
        CHECK (length(trim("workType")) > 0),
    CONSTRAINT "clinic_lab_orders_cost_check"
        CHECK ("cost" >= 0)
);

ALTER TABLE "clinic_lab_orders"
    ADD COLUMN IF NOT EXISTS "impressionType" varchar(255) NULL,
    ADD COLUMN IF NOT EXISTS "teethFdi" integer[] NULL,
    ADD COLUMN IF NOT EXISTS "files" text[] NULL;

CREATE INDEX IF NOT EXISTS "clinic_lab_orders_organizationId_projectId_idx"
    ON "clinic_lab_orders" ("organizationId", "projectId", "createdAt" DESC);
CREATE INDEX IF NOT EXISTS "clinic_lab_orders_organizationId_patientId_idx"
    ON "clinic_lab_orders" ("organizationId", "patientId");

-- ---------------------------------------------------------------------
-- clinic_aligner_cases: the clear-aligner pipeline
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS "clinic_aligner_cases" (
    "id" uuid NOT NULL DEFAULT gen_random_uuid(),
    "organizationId" uuid NOT NULL,
    "projectId" uuid NOT NULL,
    "patientId" uuid NOT NULL,
    "doctorId" uuid NULL,
    "caseType" varchar(255) NOT NULL,
    "impressionType" varchar(255) NULL,
    "shade" varchar(64) NULL,
    "status" varchar(40) NOT NULL DEFAULT 'SENT_TO_LAB',
    "upperSteps" integer NOT NULL DEFAULT 0,
    "lowerSteps" integer NOT NULL DEFAULT 0,
    "teethSummary" text NULL,
    "iprSummary" text NULL,
    "attachmentsCount" integer NOT NULL DEFAULT 0,
    "rxNotes" text NULL,
    "files" text[] NULL,
    "createdAt" timestamp without time zone NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    "updatedAt" timestamp without time zone NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    CONSTRAINT "clinic_aligner_cases_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "clinic_aligner_cases_patient_tenant_fkey"
        FOREIGN KEY ("patientId", "organizationId")
        REFERENCES "patients" ("id", "organizationId") ON DELETE RESTRICT,
    CONSTRAINT "clinic_aligner_cases_doctor_tenant_fkey"
        FOREIGN KEY ("doctorId", "organizationId")
        REFERENCES "doctors" ("id", "organizationId") ON DELETE RESTRICT,
    CONSTRAINT "clinic_aligner_cases_status_check"
        CHECK ("status" IN ('SENT_TO_LAB', 'IN_PRODUCTION', 'QUALITY_CHECK', 'COMPLETED', 'ON_HOLD')),
    CONSTRAINT "clinic_aligner_cases_case_type_check"
        CHECK (length(trim("caseType")) > 0),
    CONSTRAINT "clinic_aligner_cases_steps_check"
        CHECK ("upperSteps" >= 0 AND "lowerSteps" >= 0),
    CONSTRAINT "clinic_aligner_cases_attachments_check"
        CHECK ("attachmentsCount" >= 0)
);

CREATE INDEX IF NOT EXISTS "clinic_aligner_cases_organizationId_projectId_idx"
    ON "clinic_aligner_cases" ("organizationId", "projectId", "createdAt" DESC);
CREATE INDEX IF NOT EXISTS "clinic_aligner_cases_organizationId_patientId_idx"
    ON "clinic_aligner_cases" ("organizationId", "patientId");

-- ---------------------------------------------------------------------
-- clinic_feedback: the patient feedback register
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS "clinic_feedback" (
    "id" uuid NOT NULL DEFAULT gen_random_uuid(),
    "organizationId" uuid NOT NULL,
    "projectId" uuid NOT NULL,
    "patientId" uuid NULL,
    "doctorId" uuid NULL,
    "patientName" varchar(255) NOT NULL,
    "patientEmail" varchar(320) NULL,
    "patientPhone" varchar(64) NULL,
    "patientAge" integer NULL,
    "doctorName" varchar(255) NULL,
    "serviceName" varchar(255) NULL,
    "treatmentsReceived" text NULL,
    "clinicLocation" varchar(255) NULL,
    "referralSource" varchar(255) NULL,
    "ratingExplanation" integer NOT NULL DEFAULT 0,
    "ratingHygiene" integer NOT NULL DEFAULT 0,
    "ratingCourtesy" integer NOT NULL DEFAULT 0,
    "ratingComfort" integer NOT NULL DEFAULT 0,
    "ratingRecommend" integer NOT NULL DEFAULT 0,
    "message" text NULL,
    "submittedAt" timestamp without time zone NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    "createdAt" timestamp without time zone NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    CONSTRAINT "clinic_feedback_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "clinic_feedback_patient_tenant_fkey"
        FOREIGN KEY ("patientId", "organizationId")
        REFERENCES "patients" ("id", "organizationId") ON DELETE RESTRICT,
    CONSTRAINT "clinic_feedback_doctor_tenant_fkey"
        FOREIGN KEY ("doctorId", "organizationId")
        REFERENCES "doctors" ("id", "organizationId") ON DELETE RESTRICT,
    CONSTRAINT "clinic_feedback_patient_name_check"
        CHECK (length(trim("patientName")) > 0),
    CONSTRAINT "clinic_feedback_ratings_check"
        CHECK ("ratingExplanation" BETWEEN 0 AND 10
           AND "ratingHygiene" BETWEEN 0 AND 10
           AND "ratingCourtesy" BETWEEN 0 AND 10
           AND "ratingComfort" BETWEEN 0 AND 10
           AND "ratingRecommend" BETWEEN 0 AND 10),
    CONSTRAINT "clinic_feedback_age_check"
        CHECK ("patientAge" IS NULL OR ("patientAge" >= 0 AND "patientAge" <= 150))
);

CREATE INDEX IF NOT EXISTS "clinic_feedback_organizationId_projectId_idx"
    ON "clinic_feedback" ("organizationId", "projectId", "submittedAt" DESC);
CREATE INDEX IF NOT EXISTS "clinic_feedback_organizationId_doctorId_idx"
    ON "clinic_feedback" ("organizationId", "doctorId");

-- ---------------------------------------------------------------------
-- clinic_doctor_payouts: settled doctor commission periods
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS "clinic_doctor_payouts" (
    "id" uuid NOT NULL DEFAULT gen_random_uuid(),
    "organizationId" uuid NOT NULL,
    "projectId" uuid NOT NULL,
    "doctorId" uuid NOT NULL,
    "periodStart" date NOT NULL,
    "periodEnd" date NOT NULL,
    "grossAmount" numeric(14, 3) NOT NULL DEFAULT 0.000,
    "commissionRate" numeric(6, 2) NOT NULL DEFAULT 0.00,
    "commissionAmount" numeric(14, 3) NOT NULL DEFAULT 0.000,
    "currency" varchar(10) NOT NULL DEFAULT 'JOD',
    "status" varchar(20) NOT NULL DEFAULT 'PENDING',
    "paidAt" timestamp without time zone NULL,
    "notes" text NULL,
    "createdByUserId" uuid NULL,
    "createdAt" timestamp without time zone NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    "updatedAt" timestamp without time zone NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    CONSTRAINT "clinic_doctor_payouts_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "clinic_doctor_payouts_doctor_tenant_fkey"
        FOREIGN KEY ("doctorId", "organizationId")
        REFERENCES "doctors" ("id", "organizationId") ON DELETE RESTRICT,
    CONSTRAINT "clinic_doctor_payouts_period_check"
        CHECK ("periodStart" <= "periodEnd"),
    CONSTRAINT "clinic_doctor_payouts_status_check"
        CHECK ("status" IN ('PENDING', 'PAID')),
    CONSTRAINT "clinic_doctor_payouts_amounts_check"
        CHECK ("grossAmount" >= 0 AND "commissionAmount" >= 0
           AND "commissionRate" >= 0 AND "commissionRate" <= 100)
);

CREATE UNIQUE INDEX IF NOT EXISTS "clinic_doctor_payouts_period_key"
    ON "clinic_doctor_payouts" ("organizationId", "projectId", "doctorId", "periodStart", "periodEnd");
CREATE INDEX IF NOT EXISTS "clinic_doctor_payouts_organizationId_projectId_idx"
    ON "clinic_doctor_payouts" ("organizationId", "projectId", "periodStart" DESC);
