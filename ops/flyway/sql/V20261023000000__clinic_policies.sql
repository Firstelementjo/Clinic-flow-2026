-- V20261023000000: Dental Clinic Policies & Settings (USR-09, BIL-07, APT-02)
-- Clinic-level policy configurations for patient visibility, default duration, and currency.

CREATE TABLE IF NOT EXISTS clinic_policies (
    "organizationId" UUID NOT NULL REFERENCES organizations(id) ON UPDATE CASCADE ON DELETE CASCADE,
    "projectId" UUID NOT NULL REFERENCES projects(id) ON UPDATE CASCADE ON DELETE CASCADE,
    "doctorSeesAllPatients" BOOLEAN NOT NULL DEFAULT false,
    "defaultSlotDurationMinutes" INT NOT NULL DEFAULT 30 CHECK ("defaultSlotDurationMinutes" > 0),
    "allowDoubleBooking" BOOLEAN NOT NULL DEFAULT false,
    "primaryCurrency" VARCHAR(3) NOT NULL DEFAULT 'JOD' CHECK ("primaryCurrency" IN ('JOD', 'USD')),
    "toothNotationSystem" VARCHAR(16) NOT NULL DEFAULT 'FDI' CHECK ("toothNotationSystem" IN ('FDI', 'UNIVERSAL')),
    "updatedAt" TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    "updatedBy" UUID NULL REFERENCES users(id) ON UPDATE CASCADE ON DELETE SET NULL,
    PRIMARY KEY ("organizationId", "projectId")
);
