-- V20261022000000: Dental Clinic Invoices & Payments (BIL-01 to BIL-09)
-- Multi-tenant scoped billing, invoices, items, and receipted payments.

CREATE TABLE IF NOT EXISTS clinic_invoice_sequences (
    "organizationId" UUID NOT NULL REFERENCES organizations(id) ON UPDATE CASCADE ON DELETE CASCADE,
    "sequenceType" VARCHAR(32) NOT NULL CHECK ("sequenceType" IN ('INVOICE', 'PAYMENT')),
    "currentValue" BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY ("organizationId", "sequenceType")
);

CREATE TABLE IF NOT EXISTS clinic_invoices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    "organizationId" UUID NOT NULL REFERENCES organizations(id) ON UPDATE CASCADE ON DELETE CASCADE,
    "projectId" UUID NOT NULL REFERENCES projects(id) ON UPDATE CASCADE ON DELETE CASCADE,
    "patientId" UUID NOT NULL REFERENCES patients(id) ON UPDATE CASCADE ON DELETE CASCADE,
    "encounterId" UUID NULL REFERENCES clinical_encounters(id) ON UPDATE CASCADE ON DELETE SET NULL,
    "treatmentPlanId" UUID NULL REFERENCES treatment_plans(id) ON UPDATE CASCADE ON DELETE SET NULL,
    "invoiceNumber" VARCHAR(64) NOT NULL,
    "currency" VARCHAR(3) NOT NULL DEFAULT 'JOD' CHECK ("currency" IN ('JOD', 'USD')),
    "subtotal" NUMERIC(12, 3) NOT NULL DEFAULT 0.000 CHECK ("subtotal" >= 0),
    "discountAmount" NUMERIC(12, 3) NOT NULL DEFAULT 0.000 CHECK ("discountAmount" >= 0),
    "discountReason" TEXT NULL,
    "discountAppliedBy" UUID NULL REFERENCES users(id) ON UPDATE CASCADE ON DELETE SET NULL,
    "totalAmount" NUMERIC(12, 3) NOT NULL DEFAULT 0.000 CHECK ("totalAmount" >= 0),
    "paidAmount" NUMERIC(12, 3) NOT NULL DEFAULT 0.000 CHECK ("paidAmount" >= 0),
    "balanceDue" NUMERIC(12, 3) NOT NULL DEFAULT 0.000 CHECK ("balanceDue" >= 0),
    "status" VARCHAR(32) NOT NULL DEFAULT 'ISSUED'
        CHECK ("status" IN ('ISSUED', 'PARTIALLY_PAID', 'PAID', 'CANCELLED')),
    "notes" TEXT NULL,
    "issuedAt" TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    "cancelledAt" TIMESTAMP WITH TIME ZONE NULL,
    "cancelledBy" UUID NULL REFERENCES users(id) ON UPDATE CASCADE ON DELETE SET NULL,
    "cancellationReason" TEXT NULL,
    "createdAt" TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    "updatedAt" TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC')
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_clinic_invoices_org_num
    ON clinic_invoices ("organizationId", "invoiceNumber");

CREATE UNIQUE INDEX IF NOT EXISTS uq_clinic_invoices_org_id
    ON clinic_invoices ("organizationId", id);

CREATE INDEX IF NOT EXISTS idx_clinic_invoices_patient
    ON clinic_invoices ("organizationId", "patientId", "issuedAt" DESC);

CREATE INDEX IF NOT EXISTS idx_clinic_invoices_status
    ON clinic_invoices ("organizationId", "status", "issuedAt" DESC);

CREATE INDEX IF NOT EXISTS idx_clinic_invoices_plan
    ON clinic_invoices ("organizationId", "treatmentPlanId") WHERE "treatmentPlanId" IS NOT NULL;

CREATE TABLE IF NOT EXISTS clinic_invoice_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    "organizationId" UUID NOT NULL REFERENCES organizations(id) ON UPDATE CASCADE ON DELETE CASCADE,
    "projectId" UUID NOT NULL REFERENCES projects(id) ON UPDATE CASCADE ON DELETE CASCADE,
    "invoiceId" UUID NOT NULL REFERENCES clinic_invoices(id) ON UPDATE CASCADE ON DELETE CASCADE,
    "treatmentPlanItemId" UUID NULL REFERENCES treatment_plan_items(id) ON UPDATE CASCADE ON DELETE SET NULL,
    "procedureId" UUID NULL REFERENCES procedures(id) ON UPDATE CASCADE ON DELETE SET NULL,
    "itemType" VARCHAR(32) NOT NULL DEFAULT 'PROCEDURE'
        CHECK ("itemType" IN ('PROCEDURE', 'CONSULTATION', 'MANUAL')),
    "description" VARCHAR(255) NOT NULL,
    "toothFdi" INT NULL CHECK ("toothFdi" IS NULL OR ("toothFdi" >= 11 AND "toothFdi" <= 85)),
    "unitPrice" NUMERIC(12, 3) NOT NULL DEFAULT 0.000 CHECK ("unitPrice" >= 0),
    "quantity" INT NOT NULL DEFAULT 1 CHECK ("quantity" > 0),
    "totalPrice" NUMERIC(12, 3) NOT NULL DEFAULT 0.000 CHECK ("totalPrice" >= 0),
    "createdAt" TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC')
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_clinic_invoice_items_org_id
    ON clinic_invoice_items ("organizationId", id);

CREATE INDEX IF NOT EXISTS idx_clinic_invoice_items_invoice
    ON clinic_invoice_items ("organizationId", "invoiceId");

CREATE TABLE IF NOT EXISTS clinic_payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    "organizationId" UUID NOT NULL REFERENCES organizations(id) ON UPDATE CASCADE ON DELETE CASCADE,
    "projectId" UUID NOT NULL REFERENCES projects(id) ON UPDATE CASCADE ON DELETE CASCADE,
    "invoiceId" UUID NOT NULL REFERENCES clinic_invoices(id) ON UPDATE CASCADE ON DELETE RESTRICT,
    "patientId" UUID NOT NULL REFERENCES patients(id) ON UPDATE CASCADE ON DELETE CASCADE,
    "paymentNumber" VARCHAR(64) NOT NULL,
    "amount" NUMERIC(12, 3) NOT NULL CHECK ("amount" > 0),
    "currency" VARCHAR(3) NOT NULL DEFAULT 'JOD' CHECK ("currency" IN ('JOD', 'USD')),
    "paymentMethod" VARCHAR(32) NOT NULL
        CHECK ("paymentMethod" IN ('CASH', 'CARD', 'BANK_TRANSFER')),
    "referenceNumber" VARCHAR(100) NULL,
    "notes" TEXT NULL,
    "recordedByUserId" UUID NOT NULL REFERENCES users(id) ON UPDATE CASCADE ON DELETE RESTRICT,
    "paymentDate" TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    "createdAt" TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC')
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_clinic_payments_org_num
    ON clinic_payments ("organizationId", "paymentNumber");

CREATE UNIQUE INDEX IF NOT EXISTS uq_clinic_payments_org_id
    ON clinic_payments ("organizationId", id);

CREATE INDEX IF NOT EXISTS idx_clinic_payments_invoice
    ON clinic_payments ("organizationId", "invoiceId", "paymentDate" DESC);

CREATE INDEX IF NOT EXISTS idx_clinic_payments_patient
    ON clinic_payments ("organizationId", "patientId", "paymentDate" DESC);
