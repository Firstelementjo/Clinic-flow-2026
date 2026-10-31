-- V20261029000000: Dental Clinic Billing Refunds, Ledger & Statement of Account (Slice S6)
-- Multi-tenant scoped refunds, append-only patient ledger, idempotency keys, and statement generation.

-- 1. Extend sequence types to support REFUND sequence allocation
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN (
        SELECT conname
        FROM pg_constraint
        WHERE conrelid = 'clinic_invoice_sequences'::regclass
          AND contype = 'c'
    ) LOOP
        EXECUTE 'ALTER TABLE clinic_invoice_sequences DROP CONSTRAINT ' || quote_ident(r.conname);
    END LOOP;
END $$;

ALTER TABLE clinic_invoice_sequences ADD CONSTRAINT clinic_invoice_sequences_sequence_type_check
    CHECK ("sequenceType" IN ('INVOICE', 'PAYMENT', 'REFUND'));

-- 2. Add idempotencyKey to clinic_payments to prevent duplicate payment submissions
ALTER TABLE clinic_payments ADD COLUMN IF NOT EXISTS "idempotencyKey" VARCHAR(128) NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_clinic_payments_org_idempotency
    ON clinic_payments ("organizationId", "idempotencyKey")
    WHERE "idempotencyKey" IS NOT NULL;

-- 3. Refunds and payment reversals table
CREATE TABLE IF NOT EXISTS clinic_refunds (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    "organizationId" UUID NOT NULL REFERENCES organizations(id) ON UPDATE CASCADE ON DELETE CASCADE,
    "projectId" UUID NOT NULL REFERENCES projects(id) ON UPDATE CASCADE ON DELETE CASCADE,
    "invoiceId" UUID NOT NULL REFERENCES clinic_invoices(id) ON UPDATE CASCADE ON DELETE RESTRICT,
    "paymentId" UUID NULL REFERENCES clinic_payments(id) ON UPDATE CASCADE ON DELETE RESTRICT,
    "patientId" UUID NOT NULL REFERENCES patients(id) ON UPDATE CASCADE ON DELETE CASCADE,
    "refundNumber" VARCHAR(64) NOT NULL,
    "amount" NUMERIC(12, 3) NOT NULL CHECK ("amount" > 0),
    "currency" VARCHAR(3) NOT NULL DEFAULT 'JOD' CHECK ("currency" IN ('JOD', 'USD')),
    "refundMethod" VARCHAR(32) NOT NULL
        CHECK ("refundMethod" IN ('CASH', 'CARD', 'BANK_TRANSFER', 'STORE_CREDIT')),
    "reason" TEXT NOT NULL,
    "approvedByUserId" UUID NOT NULL REFERENCES users(id) ON UPDATE CASCADE ON DELETE RESTRICT,
    "refundDate" TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    "createdAt" TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC')
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_clinic_refunds_org_num
    ON clinic_refunds ("organizationId", "refundNumber");

CREATE UNIQUE INDEX IF NOT EXISTS uq_clinic_refunds_org_id
    ON clinic_refunds ("organizationId", id);

CREATE INDEX IF NOT EXISTS idx_clinic_refunds_invoice
    ON clinic_refunds ("organizationId", "invoiceId");

CREATE INDEX IF NOT EXISTS idx_clinic_refunds_patient
    ON clinic_refunds ("organizationId", "patientId", "refundDate" DESC);

-- 4. Append-only patient ledger entries
CREATE TABLE IF NOT EXISTS clinic_patient_ledger_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    "organizationId" UUID NOT NULL REFERENCES organizations(id) ON UPDATE CASCADE ON DELETE CASCADE,
    "projectId" UUID NOT NULL REFERENCES projects(id) ON UPDATE CASCADE ON DELETE CASCADE,
    "patientId" UUID NOT NULL REFERENCES patients(id) ON UPDATE CASCADE ON DELETE CASCADE,
    "entryType" VARCHAR(32) NOT NULL
        CHECK ("entryType" IN ('INVOICE_CHARGE', 'DISCOUNT_CREDIT', 'PAYMENT_CREDIT', 'REFUND_DEBIT', 'CANCELLATION_CREDIT', 'ADJUSTMENT')),
    "direction" VARCHAR(8) NOT NULL
        CHECK ("direction" IN ('DEBIT', 'CREDIT')),
    "amount" NUMERIC(12, 3) NOT NULL CHECK ("amount" > 0),
    "currency" VARCHAR(3) NOT NULL DEFAULT 'JOD' CHECK ("currency" IN ('JOD', 'USD')),
    "invoiceId" UUID NULL REFERENCES clinic_invoices(id) ON UPDATE CASCADE ON DELETE SET NULL,
    "paymentId" UUID NULL REFERENCES clinic_payments(id) ON UPDATE CASCADE ON DELETE SET NULL,
    "refundId" UUID NULL REFERENCES clinic_refunds(id) ON UPDATE CASCADE ON DELETE SET NULL,
    "referenceNumber" VARCHAR(64) NOT NULL,
    "description" TEXT NOT NULL,
    "recordedByUserId" UUID NOT NULL REFERENCES users(id) ON UPDATE CASCADE ON DELETE RESTRICT,
    "entryDate" TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    "createdAt" TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC')
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_clinic_ledger_org_id
    ON clinic_patient_ledger_entries ("organizationId", id);

CREATE INDEX IF NOT EXISTS idx_clinic_ledger_patient_date
    ON clinic_patient_ledger_entries ("organizationId", "patientId", "entryDate" DESC);

CREATE INDEX IF NOT EXISTS idx_clinic_ledger_invoice
    ON clinic_patient_ledger_entries ("organizationId", "invoiceId");
