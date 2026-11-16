-- =====================================================================
-- V20261105000000__clinic_rbac_roles_permissions_and_audit.sql
--
-- Multi-tenant dental clinic RBAC data model:
--   1. clinic_locations."doctorSeesAllPatients" (default false)
--   2. clinic_permissions (the 25-key permission catalog)
--   3. clinic_roles (preset and custom roles per clinic)
--   4. clinic_role_permissions (role -> permission mapping)
--   5. clinic_user_roles (many-to-many user -> role assignment per clinic)
--   6. clinic_rbac_audit_log (mandatory audit log for sensitive operations)
-- =====================================================================

ALTER TABLE "public"."clinic_locations"
    ADD COLUMN IF NOT EXISTS "doctorSeesAllPatients" boolean NOT NULL DEFAULT false;

CREATE TABLE IF NOT EXISTS "public"."clinic_permissions" (
    "key"         text NOT NULL,
    "category"    text NOT NULL,
    "description" text NOT NULL,
    CONSTRAINT "clinic_permissions_pkey" PRIMARY KEY ("key")
);

INSERT INTO "public"."clinic_permissions" ("key", "category", "description") VALUES
    ('clinics.manage',                  'PLATFORM',        'Create or suspend clinics and their clinic_admins'),
    ('subscriptions.manage',            'PLATFORM',        'Manage clinic platform subscriptions'),
    ('clinic.settings.manage',          'CLINIC_ADMIN',    'Manage clinic settings, doctors, schedules, services and prices'),
    ('users.manage',                    'CLINIC_ADMIN',    'Create, edit, suspend, and reactivate clinic users'),
    ('roles.manage',                    'CLINIC_ADMIN',    'Edit preset roles and create custom roles from the permission catalog'),
    ('patients.view_admin',             'PATIENTS',        'View administrative patient data'),
    ('patients.manage_admin',           'PATIENTS',        'Create and edit administrative patient data'),
    ('appointments.view',               'APPOINTMENTS',    'View all clinic appointments'),
    ('appointments.view_own',           'APPOINTMENTS',    'View own appointments only'),
    ('appointments.manage',             'APPOINTMENTS',    'Create, edit, cancel, and reschedule appointments'),
    ('clinical.view_history',           'CLINICAL',        'View medical history, allergies, and medications'),
    ('clinical.view_record',            'CLINICAL',        'View clinical medical record (complaint, exam, diagnosis, notes, odontogram, prescriptions)'),
    ('clinical.write_record',           'CLINICAL',        'Write clinical medical record (complaint, exam, diagnosis, notes, odontogram, procedures, prescriptions)'),
    ('treatment_plans.view_status',     'TREATMENT_PLANS', 'View treatment plan status only (e.g. in progress)'),
    ('treatment_plans.view_financials', 'TREATMENT_PLANS', 'View treatment plan financial figures only (planned, invoiced, collected)'),
    ('treatment_plans.view_full',       'TREATMENT_PLANS', 'View full clinical and financial treatment plan details'),
    ('treatment_plans.manage',          'TREATMENT_PLANS', 'Create and edit treatment plans'),
    ('invoices.view',                   'BILLING',         'View all clinic invoices'),
    ('invoices.view_own_patients',      'BILLING',         'View invoices of own assigned patients (read-only)'),
    ('invoices.create',                 'BILLING',         'Create clinic invoices'),
    ('payments.record',                 'BILLING',         'Record payments against invoices'),
    ('discounts.apply',                 'BILLING',         'Apply discounts to invoices'),
    ('invoices.cancel',                 'BILLING',         'Cancel invoices with a mandatory reason'),
    ('reports.operational.view',        'REPORTS',         'View operational reports and dashboard'),
    ('reports.financial.view',          'REPORTS',         'View financial reports and revenue summary')
ON CONFLICT ("key") DO NOTHING;

CREATE TABLE IF NOT EXISTS "public"."clinic_roles" (
    "id"             uuid NOT NULL,
    "organizationId" uuid NOT NULL,
    "clinicId"       uuid,
    "roleKey"        text NOT NULL,
    "displayName"    text NOT NULL,
    "isPreset"       boolean NOT NULL DEFAULT false,
    "createdAt"      timestamp(3) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "clinic_roles_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX IF NOT EXISTS "clinic_roles_org_clinic_key_uniq"
    ON "public"."clinic_roles" ("organizationId", "clinicId", "roleKey");

CREATE INDEX IF NOT EXISTS "clinic_roles_org_clinic_idx"
    ON "public"."clinic_roles" ("organizationId", "clinicId");

CREATE TABLE IF NOT EXISTS "public"."clinic_role_permissions" (
    "roleId"        uuid NOT NULL,
    "permissionKey" text NOT NULL,
    CONSTRAINT "clinic_role_permissions_pkey" PRIMARY KEY ("roleId", "permissionKey"),
    CONSTRAINT "clinic_role_permissions_role_fkey"
        FOREIGN KEY ("roleId") REFERENCES "public"."clinic_roles" ("id") ON DELETE CASCADE,
    CONSTRAINT "clinic_role_permissions_perm_fkey"
        FOREIGN KEY ("permissionKey") REFERENCES "public"."clinic_permissions" ("key") ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS "public"."clinic_user_roles" (
    "organizationId"   uuid NOT NULL,
    "clinicId"         uuid,
    "userId"           uuid NOT NULL,
    "roleId"           uuid NOT NULL,
    "assignedByUserId" uuid,
    "assignedAt"       timestamp(3) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "clinic_user_roles_pkey" PRIMARY KEY ("userId", "roleId"),
    CONSTRAINT "clinic_user_roles_role_fkey"
        FOREIGN KEY ("roleId") REFERENCES "public"."clinic_roles" ("id") ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS "clinic_user_roles_org_clinic_user_idx"
    ON "public"."clinic_user_roles" ("organizationId", "clinicId", "userId");

CREATE TABLE IF NOT EXISTS "public"."clinic_rbac_audit_log" (
    "id"             uuid NOT NULL,
    "organizationId" uuid NOT NULL,
    "clinicId"       uuid,
    "actorUserId"    uuid,
    "actorEmail"     text,
    "action"         text NOT NULL,
    "targetType"     text NOT NULL,
    "targetId"       text,
    "detailsJson"    text,
    "occurredAt"     timestamp(3) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "clinic_rbac_audit_log_pkey" PRIMARY KEY ("id")
);

CREATE INDEX IF NOT EXISTS "clinic_rbac_audit_log_org_clinic_occurred_idx"
    ON "public"."clinic_rbac_audit_log" ("organizationId", "clinicId", "occurredAt" DESC);

ALTER TABLE "public"."platform_operator_capabilities"
    DROP CONSTRAINT "platform_operator_capabilities_known",
    ADD CONSTRAINT "platform_operator_capabilities_known" CHECK ("capability" IN (
        'READ_PLATFORM_OVERVIEW', 'CREATE_CLINIC', 'CREATE_ACCOUNT', 'GRANT_ACCESS',
        'READ_PLATFORM_AUDIT', 'OPERATE_PLATFORM', 'REQUEST_DB_ACTION', 'MANAGE_BILLING'));
