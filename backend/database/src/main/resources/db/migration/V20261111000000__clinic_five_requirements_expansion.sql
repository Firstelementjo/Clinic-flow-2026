-- V20261111000000: ClinicFlow Five Requirements Expansion
--
-- Expands ClinicFlow with:
--   1. Inventory Management, Service-to-Material Mapping & Automatic Deduction Ledger
--   2. HR Lite: Staff Scheduling (Weekly Templates & Date Rosters) & Leave Request Lifecycle
--   3. Financial Management: Operational Expenses & Monthly Recurring Expense/Salary Templates
--   4. Treatment Room Management: Organization -> Branch -> Treatment Rooms & Room Assignment
--   5. Dental Imaging Integration: Preferred External Labs & Imaging Requests
--
-- Schema Invariants:
--   * Every table carries "organizationId" uuid NOT NULL and "projectId" uuid NOT NULL.
--   * Zero raw UNIQUE table constraints (uses CREATE UNIQUE INDEX IF NOT EXISTS per ADR V4).
--   * Zero timestamptz columns (uses timestamp without time zone UTC per V6).
--   * All monetary columns use numeric(15, 3) (JOD 3-decimal standard).

-- ---------------------------------------------------------------------------
-- 1. Inventory Management & Service-to-Material Mapping (Requirement 1)
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS "public"."inventory_items" (
    "id" uuid NOT NULL DEFAULT gen_random_uuid(),
    "organizationId" uuid NOT NULL,
    "projectId" uuid NOT NULL,
    "clinicId" uuid NULL,
    "name" varchar(255) NOT NULL,
    "sku" varchar(64) NULL,
    "category" varchar(64) NOT NULL DEFAULT 'CONSUMABLES',
    "unitOfMeasure" varchar(32) NOT NULL DEFAULT 'UNIT',
    "unitCost" numeric(15, 3) NOT NULL DEFAULT 0.000,
    "quantityOnHand" integer NOT NULL DEFAULT 0,
    "minStockThreshold" integer NOT NULL DEFAULT 5,
    "isActive" boolean NOT NULL DEFAULT TRUE,
    "createdAt" timestamp without time zone NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    "updatedAt" timestamp without time zone NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    CONSTRAINT "inventory_items_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "inventory_items_name_check" CHECK (length(trim("name")) > 0),
    CONSTRAINT "inventory_items_qty_check" CHECK ("quantityOnHand" >= 0),
    CONSTRAINT "inventory_items_min_stock_check" CHECK ("minStockThreshold" >= 0),
    CONSTRAINT "inventory_items_cost_check" CHECK ("unitCost" >= 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS "inventory_items_id_organizationId_key"
    ON "public"."inventory_items" ("id", "organizationId");

CREATE INDEX IF NOT EXISTS "inventory_items_org_proj_idx"
    ON "public"."inventory_items" ("organizationId", "projectId", "isActive");

CREATE TABLE IF NOT EXISTS "public"."service_materials" (
    "id" uuid NOT NULL DEFAULT gen_random_uuid(),
    "organizationId" uuid NOT NULL,
    "projectId" uuid NOT NULL,
    "procedureId" uuid NOT NULL,
    "itemId" uuid NOT NULL,
    "quantityRequired" integer NOT NULL DEFAULT 1,
    "createdAt" timestamp without time zone NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    CONSTRAINT "service_materials_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "service_materials_qty_check" CHECK ("quantityRequired" > 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS "service_materials_proc_item_key"
    ON "public"."service_materials" ("organizationId", "projectId", "procedureId", "itemId");

CREATE INDEX IF NOT EXISTS "service_materials_org_proc_idx"
    ON "public"."service_materials" ("organizationId", "procedureId");

CREATE TABLE IF NOT EXISTS "public"."inventory_transactions" (
    "id" uuid NOT NULL DEFAULT gen_random_uuid(),
    "organizationId" uuid NOT NULL,
    "projectId" uuid NOT NULL,
    "clinicId" uuid NULL,
    "itemId" uuid NOT NULL,
    "transactionType" varchar(32) NOT NULL,
    "quantity" integer NOT NULL,
    "quantityBefore" integer NOT NULL DEFAULT 0,
    "quantityAfter" integer NOT NULL DEFAULT 0,
    "shortfallQuantity" integer NOT NULL DEFAULT 0,
    "unitCost" numeric(15, 3) NOT NULL DEFAULT 0.000,
    "referenceType" varchar(64) NULL,
    "referenceId" uuid NULL,
    "performedByUserId" uuid NULL,
    "notes" text NULL,
    "createdAt" timestamp without time zone NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    CONSTRAINT "inventory_transactions_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "inventory_transactions_type_check"
        CHECK ("transactionType" IN ('PROCEDURE_DEDUCTION', 'MANUAL_PURCHASE', 'MANUAL_ADJUSTMENT', 'AUDIT_DISCREPANCY')),
    CONSTRAINT "inventory_transactions_shortfall_check" CHECK ("shortfallQuantity" >= 0),
    CONSTRAINT "inventory_transactions_unit_cost_check" CHECK ("unitCost" >= 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS "inventory_transactions_dedup_key"
    ON "public"."inventory_transactions" ("organizationId", "projectId", "referenceType", "referenceId", "itemId")
    WHERE "referenceType" IS NOT NULL AND "referenceId" IS NOT NULL AND "transactionType" = 'PROCEDURE_DEDUCTION';

CREATE INDEX IF NOT EXISTS "inventory_transactions_org_proj_idx"
    ON "public"."inventory_transactions" ("organizationId", "projectId", "createdAt" DESC);

-- ---------------------------------------------------------------------------
-- 2. HR Lite: Staff Scheduling & Leave Management (Requirement 2)
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS "public"."clinic_staff_schedules" (
    "id" uuid NOT NULL DEFAULT gen_random_uuid(),
    "organizationId" uuid NOT NULL,
    "projectId" uuid NOT NULL,
    "clinicId" uuid NULL,
    "userId" uuid NULL,
    "doctorId" uuid NULL,
    "staffName" varchar(255) NOT NULL,
    "staffRole" varchar(64) NOT NULL DEFAULT 'CLINIC_STAFF',
    "scheduleType" varchar(32) NOT NULL DEFAULT 'WEEKLY_TEMPLATE',
    "weekday" smallint NULL,
    "shiftDate" date NULL,
    "startTime" varchar(16) NOT NULL DEFAULT '09:00',
    "endTime" varchar(16) NOT NULL DEFAULT '17:00',
    "notes" text NULL,
    "isActive" boolean NOT NULL DEFAULT TRUE,
    "createdAt" timestamp without time zone NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    "updatedAt" timestamp without time zone NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    CONSTRAINT "clinic_staff_schedules_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "clinic_staff_schedules_type_check"
        CHECK ("scheduleType" IN ('WEEKLY_TEMPLATE', 'DATE_ROSTER')),
    CONSTRAINT "clinic_staff_schedules_weekday_check"
        CHECK ("weekday" IS NULL OR ("weekday" BETWEEN 1 AND 7)),
    CONSTRAINT "clinic_staff_schedules_name_check"
        CHECK (length(trim("staffName")) > 0)
);

CREATE INDEX IF NOT EXISTS "clinic_staff_schedules_org_proj_idx"
    ON "public"."clinic_staff_schedules" ("organizationId", "projectId", "isActive");

CREATE TABLE IF NOT EXISTS "public"."clinic_leave_requests" (
    "id" uuid NOT NULL DEFAULT gen_random_uuid(),
    "organizationId" uuid NOT NULL,
    "projectId" uuid NOT NULL,
    "clinicId" uuid NULL,
    "userId" uuid NULL,
    "doctorId" uuid NULL,
    "staffName" varchar(255) NOT NULL,
    "staffRole" varchar(64) NOT NULL DEFAULT 'CLINIC_STAFF',
    "leaveType" varchar(32) NOT NULL DEFAULT 'ANNUAL_VACATION',
    "startDate" date NOT NULL,
    "endDate" date NOT NULL,
    "reason" text NULL,
    "status" varchar(32) NOT NULL DEFAULT 'PENDING',
    "reviewedByUserId" uuid NULL,
    "reviewedByName" varchar(255) NULL,
    "reviewedAt" timestamp without time zone NULL,
    "reviewNotes" text NULL,
    "createdAt" timestamp without time zone NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    "updatedAt" timestamp without time zone NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    CONSTRAINT "clinic_leave_requests_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "clinic_leave_requests_type_check"
        CHECK ("leaveType" IN ('ANNUAL_VACATION', 'SICK_LEAVE', 'EMERGENCY_LEAVE', 'UNPAID_LEAVE')),
    CONSTRAINT "clinic_leave_requests_dates_check"
        CHECK ("endDate" >= "startDate"),
    CONSTRAINT "clinic_leave_requests_status_check"
        CHECK ("status" IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT "clinic_leave_requests_name_check"
        CHECK (length(trim("staffName")) > 0)
);

CREATE INDEX IF NOT EXISTS "clinic_leave_requests_org_proj_idx"
    ON "public"."clinic_leave_requests" ("organizationId", "projectId", "status", "startDate" DESC);

-- ---------------------------------------------------------------------------
-- 3. Financial Management: Operational Expenses & Recurring Templates (Req 3)
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS "public"."clinic_expenses" (
    "id" uuid NOT NULL DEFAULT gen_random_uuid(),
    "organizationId" uuid NOT NULL,
    "projectId" uuid NOT NULL,
    "clinicId" uuid NULL,
    "category" varchar(64) NOT NULL,
    "title" varchar(255) NOT NULL,
    "amount" numeric(15, 3) NOT NULL DEFAULT 0.000,
    "currency" varchar(10) NOT NULL DEFAULT 'JOD',
    "expenseFrequency" varchar(32) NOT NULL DEFAULT 'ONE_TIME',
    "paymentDate" date NOT NULL DEFAULT CURRENT_DATE,
    "templateId" uuid NULL,
    "referenceType" varchar(64) NULL,
    "referenceId" uuid NULL,
    "notes" text NULL,
    "createdByUserId" uuid NULL,
    "createdAt" timestamp without time zone NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    "updatedAt" timestamp without time zone NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    CONSTRAINT "clinic_expenses_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "clinic_expenses_category_check"
        CHECK ("category" IN ('RENT', 'ELECTRICITY_WATER', 'SALARIES', 'MAINTENANCE', 'INVENTORY_PURCHASE', 'LAB_FEES', 'OTHER')),
    CONSTRAINT "clinic_expenses_freq_check"
        CHECK ("expenseFrequency" IN ('ONE_TIME', 'RECURRING_MONTHLY')),
    CONSTRAINT "clinic_expenses_amount_check" CHECK ("amount" >= 0),
    CONSTRAINT "clinic_expenses_title_check" CHECK (length(trim("title")) > 0)
);

CREATE INDEX IF NOT EXISTS "clinic_expenses_org_proj_date_idx"
    ON "public"."clinic_expenses" ("organizationId", "projectId", "paymentDate" DESC);

CREATE TABLE IF NOT EXISTS "public"."clinic_recurring_expense_templates" (
    "id" uuid NOT NULL DEFAULT gen_random_uuid(),
    "organizationId" uuid NOT NULL,
    "projectId" uuid NOT NULL,
    "clinicId" uuid NULL,
    "category" varchar(64) NOT NULL,
    "title" varchar(255) NOT NULL,
    "defaultAmount" numeric(15, 3) NOT NULL DEFAULT 0.000,
    "dayOfMonth" integer NOT NULL DEFAULT 1,
    "lastConfirmedMonth" varchar(7) NULL,
    "notes" text NULL,
    "isActive" boolean NOT NULL DEFAULT TRUE,
    "createdAt" timestamp without time zone NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    "updatedAt" timestamp without time zone NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    CONSTRAINT "clinic_recurring_expense_templates_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "clinic_recurring_expense_templates_category_check"
        CHECK ("category" IN ('RENT', 'ELECTRICITY_WATER', 'SALARIES', 'MAINTENANCE', 'OTHER')),
    CONSTRAINT "clinic_recurring_expense_templates_amount_check" CHECK ("defaultAmount" >= 0),
    CONSTRAINT "clinic_recurring_expense_templates_day_check" CHECK ("dayOfMonth" BETWEEN 1 AND 28),
    CONSTRAINT "clinic_recurring_expense_templates_title_check" CHECK (length(trim("title")) > 0)
);

CREATE INDEX IF NOT EXISTS "clinic_recurring_expense_templates_org_proj_idx"
    ON "public"."clinic_recurring_expense_templates" ("organizationId", "projectId", "isActive");

-- ---------------------------------------------------------------------------
-- 4. Clinic & Treatment Room Management (Requirement 4)
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS "public"."treatment_rooms" (
    "id" uuid NOT NULL DEFAULT gen_random_uuid(),
    "organizationId" uuid NOT NULL,
    "projectId" uuid NOT NULL,
    "clinicId" uuid NULL,
    "name" varchar(120) NOT NULL,
    "roomNumber" varchar(32) NULL,
    "roomType" varchar(64) NOT NULL DEFAULT 'GENERAL_OPERATORY',
    "assignedDoctorId" uuid NULL,
    "operationalStartHour" varchar(16) NOT NULL DEFAULT '09:00',
    "operationalEndHour" varchar(16) NOT NULL DEFAULT '17:00',
    "isActive" boolean NOT NULL DEFAULT TRUE,
    "createdAt" timestamp without time zone NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    "updatedAt" timestamp without time zone NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    CONSTRAINT "treatment_rooms_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "treatment_rooms_name_check" CHECK (length(trim("name")) > 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS "treatment_rooms_id_organizationId_key"
    ON "public"."treatment_rooms" ("id", "organizationId");

CREATE INDEX IF NOT EXISTS "treatment_rooms_org_proj_idx"
    ON "public"."treatment_rooms" ("organizationId", "projectId", "isActive");

ALTER TABLE "public"."appointments"
    ADD COLUMN IF NOT EXISTS "treatmentRoomId" uuid NULL;

CREATE INDEX IF NOT EXISTS "appointments_org_proj_room_date_idx"
    ON "public"."appointments" ("organizationId", "projectId", "treatmentRoomId", "appointmentDate");

-- ---------------------------------------------------------------------------
-- 5. Dental Imaging Integration & External Laboratories (Requirement 5)
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS "public"."clinic_imaging_labs" (
    "id" uuid NOT NULL DEFAULT gen_random_uuid(),
    "organizationId" uuid NOT NULL,
    "projectId" uuid NOT NULL,
    "clinicId" uuid NULL,
    "name" varchar(255) NOT NULL,
    "contactPhone" varchar(64) NULL,
    "contactEmail" varchar(255) NULL,
    "address" text NULL,
    "integrationMode" varchar(32) NOT NULL DEFAULT 'MANUAL',
    "apiEndpointUrl" text NULL,
    "notes" text NULL,
    "isActive" boolean NOT NULL DEFAULT TRUE,
    "createdAt" timestamp without time zone NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    "updatedAt" timestamp without time zone NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    CONSTRAINT "clinic_imaging_labs_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "clinic_imaging_labs_name_check" CHECK (length(trim("name")) > 0),
    CONSTRAINT "clinic_imaging_labs_mode_check"
        CHECK ("integrationMode" IN ('MANUAL', 'DIRECT_CLOUD_API'))
);

CREATE INDEX IF NOT EXISTS "clinic_imaging_labs_org_proj_idx"
    ON "public"."clinic_imaging_labs" ("organizationId", "projectId", "isActive");

CREATE TABLE IF NOT EXISTS "public"."clinic_imaging_requests" (
    "id" uuid NOT NULL DEFAULT gen_random_uuid(),
    "organizationId" uuid NOT NULL,
    "projectId" uuid NOT NULL,
    "clinicId" uuid NULL,
    "patientId" uuid NOT NULL,
    "doctorId" uuid NULL,
    "labId" uuid NULL,
    "labName" varchar(255) NULL,
    "imagingType" varchar(64) NOT NULL DEFAULT 'PANORAMIC',
    "proceduresCount" integer NOT NULL DEFAULT 1,
    "toothFdi" integer NULL,
    "teethSummary" varchar(120) NULL,
    "clinicalIndication" text NULL,
    "status" varchar(32) NOT NULL DEFAULT 'REQUESTED',
    "requestDate" date NOT NULL DEFAULT CURRENT_DATE,
    "completionDate" date NULL,
    "costToClinic" numeric(15, 3) NOT NULL DEFAULT 0.000,
    "amountChargedToPatient" numeric(15, 3) NOT NULL DEFAULT 0.000,
    "invoiceId" uuid NULL,
    "expenseId" uuid NULL,
    "cloudStorageUrl" text NULL,
    "relatedFiles" text[] NULL,
    "findingsNotes" text NULL,
    "createdAt" timestamp without time zone NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    "updatedAt" timestamp without time zone NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    CONSTRAINT "clinic_imaging_requests_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "clinic_imaging_requests_type_check"
        CHECK ("imagingType" IN ('CBCT', 'PANORAMIC', 'PERIAPICAL', 'BITEWING', 'CEPHALOMETRIC', 'OTHER')),
    CONSTRAINT "clinic_imaging_requests_count_check" CHECK ("proceduresCount" > 0),
    CONSTRAINT "clinic_imaging_requests_status_check"
        CHECK ("status" IN ('REQUESTED', 'SENT_TO_LAB', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT "clinic_imaging_requests_cost_check" CHECK ("costToClinic" >= 0),
    CONSTRAINT "clinic_imaging_requests_charge_check" CHECK ("amountChargedToPatient" >= 0)
);

CREATE INDEX IF NOT EXISTS "clinic_imaging_requests_org_proj_idx"
    ON "public"."clinic_imaging_requests" ("organizationId", "projectId", "createdAt" DESC);

CREATE INDEX IF NOT EXISTS "clinic_imaging_requests_org_patient_idx"
    ON "public"."clinic_imaging_requests" ("organizationId", "patientId");
