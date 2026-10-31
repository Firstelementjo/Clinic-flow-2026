-- =====================================================================
-- V20261104000000__patient_profiles_and_appointment_commerce.sql
--
-- Phase 3 closes two gaps that the screenshots expose as ordinary fields:
--
--   1. The patient register keeps only a name, phone, birth date and email.
--      The Create New Patient drawer asks for gender, national id, a profile
--      image, an address, an account status and an optional portal password.
--      They are real columns here rather than a JSON blob so they can be
--      indexed, validated and projected one by one.
--
--   2. An appointment keeps a patient's identity as free text and has no
--      link back to the patients row, no clinic, no quantity, no unit price,
--      no payment status and no uploaded medical report. The appointment list
--      and Create Appointment drawer both depend on those being stored, not
--      recomputed from whatever the browser happened to submit.
--
-- ADDITIVE. TOUCHES NO EXISTING COLUMN AND DELETES NO ROW. Every new column
-- is nullable or has a default, so existing rows remain readable.
--
-- The patient link is DIRECT, not through appointments."customerId". DEC-015
-- says a patient is not a CRM customer; writing a patient UUID into
-- customerId was a semantic defect and is corrected in the repository.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Patient profile and contact columns.
-- ---------------------------------------------------------------------
ALTER TABLE "public"."patients"
    ADD COLUMN IF NOT EXISTS "gender"            text,
    ADD COLUMN IF NOT EXISTS "cnic"              text,
    ADD COLUMN IF NOT EXISTS "profileImageUrl"   text,
    ADD COLUMN IF NOT EXISTS "addressLine"       text,
    ADD COLUMN IF NOT EXISTS "country"           text,
    ADD COLUMN IF NOT EXISTS "state"             text,
    ADD COLUMN IF NOT EXISTS "city"              text,
    ADD COLUMN IF NOT EXISTS "postalCode"        text,
    ADD COLUMN IF NOT EXISTS "isActive"          boolean DEFAULT true NOT NULL,
    -- A bcrypt hash, never the password. The column is deliberately absent
    -- from every patient projection; there is no route that reads it back.
    ADD COLUMN IF NOT EXISTS "portalPasswordHash" text;

ALTER TABLE "public"."patients"
    ADD CONSTRAINT "patients_gender_check"
        CHECK ("gender" IS NULL OR "gender" IN ('MALE', 'FEMALE', 'OTHER'));

-- A national id is an identity inside one tenant. Null is normal and is not
-- indexed, because most walk-in rows will not have one.
CREATE UNIQUE INDEX IF NOT EXISTS "patients_organizationId_cnic_key"
    ON "public"."patients" USING "btree" ("organizationId", "cnic")
    WHERE "cnic" IS NOT NULL;

CREATE INDEX IF NOT EXISTS "patients_organizationId_isActive_idx"
    ON "public"."patients" USING "btree" ("organizationId", "projectId", "isActive");

-- ---------------------------------------------------------------------
-- 2. Appointment patient link and commercial columns.
-- ---------------------------------------------------------------------
ALTER TABLE "public"."appointments"
    ADD COLUMN IF NOT EXISTS "patientId"             uuid,
    ADD COLUMN IF NOT EXISTS "clinicId"              uuid,
    ADD COLUMN IF NOT EXISTS "price"                 numeric(12, 2),
    ADD COLUMN IF NOT EXISTS "quantity"              integer DEFAULT 1 NOT NULL,
    ADD COLUMN IF NOT EXISTS "paymentStatus"         text DEFAULT 'UNPAID' NOT NULL,
    ADD COLUMN IF NOT EXISTS "medicalReportName"     text,
    ADD COLUMN IF NOT EXISTS "medicalReportContentType" text,
    -- A data URL today, because the repository has no object-storage route.
    -- The column is text so a future storage key can replace the value without
    -- a second schema change.
    ADD COLUMN IF NOT EXISTS "medicalReportUrl"      text;

ALTER TABLE "public"."appointments"
    ADD CONSTRAINT "appointments_quantity_positive_check"
        CHECK ("quantity" > 0 AND "quantity" <= 999),
    ADD CONSTRAINT "appointments_price_non_negative_check"
        CHECK ("price" IS NULL OR "price" >= 0),
    ADD CONSTRAINT "appointments_payment_status_check"
        CHECK ("paymentStatus" IN ('PAID', 'UNPAID', 'PARTIAL', 'REFUNDED'));

-- Composite tenant foreign keys. The column order is positional:
-- ("patientId", "organizationId") references patients("id", "organizationId").
-- A mis-ordered pair binds organizationId to patients.id and rejects correct
-- rows; see the note in V20261013000000.
ALTER TABLE ONLY "public"."appointments"
    ADD CONSTRAINT "appointments_patient_tenant_fkey"
        FOREIGN KEY ("patientId", "organizationId")
        REFERENCES "public"."patients" ("id", "organizationId")
        ON UPDATE CASCADE;

ALTER TABLE ONLY "public"."appointments"
    ADD CONSTRAINT "appointments_clinic_tenant_fkey"
        FOREIGN KEY ("clinicId", "organizationId")
        REFERENCES "public"."clinic_locations" ("id", "organizationId")
        ON UPDATE CASCADE;

-- ---------------------------------------------------------------------
-- 3. Query indexes for the appointment list.
-- ---------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS "appointments_organizationId_projectId_patientId_idx"
    ON "public"."appointments" USING "btree"
        ("organizationId", "projectId", "patientId", "appointmentDate" DESC);

CREATE INDEX IF NOT EXISTS "appointments_organizationId_projectId_paymentStatus_idx"
    ON "public"."appointments" USING "btree"
        ("organizationId", "projectId", "paymentStatus", "appointmentDate" DESC);

CREATE INDEX IF NOT EXISTS "appointments_organizationId_projectId_clinicId_idx"
    ON "public"."appointments" USING "btree"
        ("organizationId", "projectId", "clinicId", "appointmentDate" DESC);
