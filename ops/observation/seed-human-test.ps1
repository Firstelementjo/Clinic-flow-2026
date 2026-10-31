#!/usr/bin/env pwsh
# =====================================================================
# seed-human-test.ps1
# =====================================================================
# Creates named accounts with a credential that SURVIVES, so a person can
# sign in to the human-test environment and use the application.
#
# Creates all 6 role accounts from the Multi-Tenant Dental Clinic RBAC spec:
#   1. platform@human.invalid      -> platform_admin
#   2. clinic-admin@human.invalid  -> clinic_admin   (plus alias admin@human.invalid)
#   3. receptionist@human.invalid  -> receptionist   (plus alias clinic@human.invalid)
#   4. doctor@human.invalid        -> doctor         (plus alias employee@human.invalid)
#   5. accountant@human.invalid    -> accountant
#   6. owner-doctor@human.invalid  -> clinic_admin + doctor (multi-role union)
# =====================================================================

[CmdletBinding()]
param(
    [string] $Database = 'firstelement_human',
    [string] $Container = 'firstelement-postgres',
    [string] $DbUser = 'firstelement',
    [string] $RepoRoot = $(if ($PSScriptRoot) { Split-Path -Parent (Split-Path -Parent $PSScriptRoot) } else { (Get-Location).Path }),
    [string] $CredentialsFile,
    [string] $Password,
    [string] $ExistingOrganizationId,
    [string] $ExistingProjectId
)

$ErrorActionPreference = 'Stop'

if ($Database -eq 'firstelement') {
    throw ('refusing to seed "firstelement". It is the protected legacy reference: 45 tables, ' +
           'deliberately unstamped, and the one database in this stack nobody may mutate. ' +
           'Seed a database whose name says it is throwaway or human-facing.')
}

if (-not $CredentialsFile) {
    $CredentialsFile = Join-Path $RepoRoot 'build\observation\human-test-credentials.txt'
}

function Write-QLog {
    param([string] $Message, [string] $Level = 'INFO')
    Write-Host ("{0}  {1,-5} {2}" -f (Get-Date).ToString('HH:mm:ss'), $Level, $Message)
}

function Invoke-Psql {
    param([string] $Sql, [string] $Db = 'postgres')
    $out = ($Sql | docker exec -i $Container psql -U $DbUser -d $Db -v ON_ERROR_STOP=1 -t -A -f - 2>&1) |
        ForEach-Object { [string]$_ }
    if ($LASTEXITCODE -ne 0) { throw ("psql failed: {0}" -f (($out | Where-Object { $_ }) -join ' ')) }
    return $out
}

function Get-BcryptHash12 {
    param([Parameter(Mandatory = $true)][string] $Plaintext)
    $nodeScript = 'const b=require("bcryptjs");let d="";process.stdin.on("data",c=>d+=c);' +
                  'process.stdin.on("end",()=>process.stdout.write(b.hashSync(d.trim(),12)));'
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = 'node'
    $psi.Arguments = '-e "' + ($nodeScript -replace '"', '\"') + '"'
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.WorkingDirectory = $RepoRoot
    $psi.EnvironmentVariables['NODE_PATH'] = (Join-Path $RepoRoot 'frontend\node_modules')
    $proc = [System.Diagnostics.Process]::Start($psi)
    $proc.StandardInput.Write($Plaintext)
    $proc.StandardInput.Close()
    $hash = $proc.StandardOutput.ReadToEnd().Trim()
    $err = $proc.StandardError.ReadToEnd()
    $proc.WaitForExit()
    if (-not $hash) { throw ("bcryptjs produced no hash: {0}" -f $err) }
    return $hash
}

function New-Password {
    $rng = [System.Random]::new(20261011)
    $bytes = New-Object byte[] 18
    $rng.NextBytes($bytes)
    return 'Ht-' + ([Convert]::ToBase64String($bytes) -replace '[^A-Za-z0-9]', '').Substring(0, 16) + '!'
}

if (-not $Password) { $Password = New-Password }
$hash = Get-BcryptHash12 -Plaintext $Password

$orgId        = if ($ExistingOrganizationId) { $ExistingOrganizationId } else { 'b0000001-0000-4000-8000-000000000001' }
$projId       = if ($ExistingProjectId)      { $ExistingProjectId }      else { 'b0000002-0000-4000-8000-000000000001' }
$clinicP      = 'b0000002-0000-4000-8000-000000000002'

# User IDs
$uAdmin       = 'b0000003-0000-4000-8000-000000000001'
$uEmp         = 'b0000003-0000-4000-8000-000000000002'
$uClinic      = 'b0000003-0000-4000-8000-000000000003'
$uPlatform    = 'b0000003-0000-4000-8000-000000000004'
$uClinicAdmin = 'b0000003-0000-4000-8000-000000000005'
$uReception   = 'b0000003-0000-4000-8000-000000000006'
$uDoctor      = 'b0000003-0000-4000-8000-000000000007'
$uAccountant  = 'b0000003-0000-4000-8000-000000000008'
$uOwnerDoctor = 'b0000003-0000-4000-8000-000000000009'

# Role IDs
$rPlatform    = 'd0000001-0000-4000-8000-000000000001'
$rClinicAdmin = 'd0000001-0000-4000-8000-000000000002'
$rReception   = 'd0000001-0000-4000-8000-000000000003'
$rDoctor      = 'd0000001-0000-4000-8000-000000000004'
$rAccountant  = 'd0000001-0000-4000-8000-000000000005'

Write-QLog ("seeding {0}" -f $Database) 'STEP'

if (-not $ExistingOrganizationId) {
    $null = Invoke-Psql -Db $Database -Sql @"
INSERT INTO organizations ("id","name","slug","defaultLocale","timezone","isActive","createdAt","updatedAt")
VALUES ('$orgId','Human Test Clinic','human-test','ar','Asia/Riyadh',true,now(),now())
ON CONFLICT ("id") DO UPDATE SET "name" = EXCLUDED."name", "updatedAt" = now();

INSERT INTO projects ("id","organizationId","name","slug","isArchived","createdAt","updatedAt")
VALUES ('$projId','$orgId','Human Test Main','human-test-main',false,now(),now()),
       ('$clinicP','$orgId','Human Test Clinic Front','human-test-clinic',false,now(),now())
ON CONFLICT ("id") DO UPDATE SET "name" = EXCLUDED."name", "updatedAt" = now();
"@
}

$null = Invoke-Psql -Db $Database -Sql @"
INSERT INTO users ("id","organizationId","email","passwordHash","fullName","role","locale","isActive","createdAt","updatedAt")
VALUES
 ('$uAdmin',       '$orgId','admin@human.invalid',        '$hash','Clinic Admin (Default)',      'ADMIN',       'en',true,now(),now()),
 ('$uEmp',         '$orgId','employee@human.invalid',     '$hash','Dr. Treating Dentist',        'EMPLOYEE',    'en',true,now(),now()),
 ('$uClinic',      '$orgId','clinic@human.invalid',       '$hash','Front Desk Receptionist',     'CLINIC_STAFF','en',true,now(),now()),
 ('$uPlatform',    '$orgId','platform@human.invalid',     '$hash','Platform Super-Admin',        'ADMIN',       'en',true,now(),now()),
 ('$uClinicAdmin', '$orgId','clinic-admin@human.invalid', '$hash','Dr. Tariq (Clinic Manager)',  'ADMIN',       'en',true,now(),now()),
 ('$uReception',   '$orgId','receptionist@human.invalid', '$hash','Sara Al-Mansour (Reception)', 'CLINIC_STAFF','en',true,now(),now()),
 ('$uDoctor',      '$orgId','doctor@human.invalid',       '$hash','Dr. Amira Haddad (Dentist)',  'EMPLOYEE',    'en',true,now(),now()),
 ('$uAccountant',  '$orgId','accountant@human.invalid',   '$hash','Khaled Nasser (Accountant)',  'CLINIC_STAFF','en',true,now(),now()),
 ('$uOwnerDoctor', '$orgId','owner-doctor@human.invalid', '$hash','Dr. Omar Khalil (Owner+Doc)', 'ADMIN',       'en',true,now(),now())
ON CONFLICT ("id") DO UPDATE SET
  "email" = EXCLUDED."email",
  "fullName" = EXCLUDED."fullName",
  "role" = EXCLUDED."role",
  "passwordHash" = EXCLUDED."passwordHash",
  "isActive" = true,
  "updatedAt" = now();

INSERT INTO project_memberships ("id","organizationId","projectId","userId","createdAt")
VALUES
 ('c0000004-0000-4000-8000-000000000001','$orgId','$projId','$uAdmin',now()),
 ('c0000004-0000-4000-8000-000000000002','$orgId','$projId','$uEmp',now()),
 ('c0000004-0000-4000-8000-000000000003','$orgId','$projId','$uClinic',now()),
 ('c0000004-0000-4000-8000-000000000004','$orgId','$projId','$uPlatform',now()),
 ('c0000004-0000-4000-8000-000000000005','$orgId','$projId','$uClinicAdmin',now()),
 ('c0000004-0000-4000-8000-000000000006','$orgId','$projId','$uReception',now()),
 ('c0000004-0000-4000-8000-000000000007','$orgId','$projId','$uDoctor',now()),
 ('c0000004-0000-4000-8000-000000000008','$orgId','$projId','$uAccountant',now()),
 ('c0000004-0000-4000-8000-000000000009','$orgId','$projId','$uOwnerDoctor',now())
ON CONFLICT ("id") DO NOTHING;

INSERT INTO employees ("id","organizationId","userId","department","createdAt","updatedAt")
VALUES
 ('c0000005-0000-4000-8000-000000000001','$orgId','$uEmp','Restorative',now(),now()),
 ('c0000005-0000-4000-8000-000000000002','$orgId','$uDoctor','General Dentistry',now(),now()),
 ('c0000005-0000-4000-8000-000000000003','$orgId','$uOwnerDoctor','Orthodontics',now(),now())
ON CONFLICT ("id") DO UPDATE SET "updatedAt" = now();
"@

# Ensure Clinic RBAC tables exist and are seeded with the 5 roles + user assignments
$null = Invoke-Psql -Db $Database -Sql @"
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

INSERT INTO "public"."clinic_roles" ("id", "organizationId", "clinicId", "roleKey", "displayName", "isPreset")
VALUES
 ('$rPlatform',    '$orgId', '$projId', 'platform_admin', 'Platform Admin', true),
 ('$rClinicAdmin', '$orgId', '$projId', 'clinic_admin',   'Clinic Admin',   true),
 ('$rReception',   '$orgId', '$projId', 'receptionist',   'Receptionist',   true),
 ('$rDoctor',      '$orgId', '$projId', 'doctor',         'Doctor',         true),
 ('$rAccountant',  '$orgId', '$projId', 'accountant',     'Accountant',     true)
ON CONFLICT ("id") DO UPDATE SET "displayName" = EXCLUDED."displayName";

DELETE FROM "public"."clinic_role_permissions" WHERE "roleId" IN ('$rPlatform','$rClinicAdmin','$rReception','$rDoctor','$rAccountant');

INSERT INTO "public"."clinic_role_permissions" ("roleId", "permissionKey") VALUES
 -- platform_admin (2)
 ('$rPlatform', 'clinics.manage'),
 ('$rPlatform', 'subscriptions.manage'),
 -- clinic_admin (15)
 ('$rClinicAdmin', 'clinic.settings.manage'),
 ('$rClinicAdmin', 'users.manage'),
 ('$rClinicAdmin', 'roles.manage'),
 ('$rClinicAdmin', 'patients.view_admin'),
 ('$rClinicAdmin', 'patients.manage_admin'),
 ('$rClinicAdmin', 'appointments.view'),
 ('$rClinicAdmin', 'appointments.manage'),
 ('$rClinicAdmin', 'treatment_plans.view_financials'),
 ('$rClinicAdmin', 'invoices.view'),
 ('$rClinicAdmin', 'invoices.create'),
 ('$rClinicAdmin', 'payments.record'),
 ('$rClinicAdmin', 'discounts.apply'),
 ('$rClinicAdmin', 'invoices.cancel'),
 ('$rClinicAdmin', 'reports.operational.view'),
 ('$rClinicAdmin', 'reports.financial.view'),
 -- receptionist (9)
 ('$rReception', 'patients.view_admin'),
 ('$rReception', 'patients.manage_admin'),
 ('$rReception', 'appointments.view'),
 ('$rReception', 'appointments.manage'),
 ('$rReception', 'treatment_plans.view_status'),
 ('$rReception', 'invoices.view'),
 ('$rReception', 'invoices.create'),
 ('$rReception', 'payments.record'),
 ('$rReception', 'reports.operational.view'),
 -- doctor (9)
 ('$rDoctor', 'patients.view_admin'),
 ('$rDoctor', 'appointments.view_own'),
 ('$rDoctor', 'clinical.view_history'),
 ('$rDoctor', 'clinical.view_record'),
 ('$rDoctor', 'clinical.write_record'),
 ('$rDoctor', 'treatment_plans.view_full'),
 ('$rDoctor', 'treatment_plans.manage'),
 ('$rDoctor', 'invoices.view_own_patients'),
 ('$rDoctor', 'reports.operational.view'),
 -- accountant (9)
 ('$rAccountant', 'patients.view_admin'),
 ('$rAccountant', 'appointments.view'),
 ('$rAccountant', 'treatment_plans.view_financials'),
 ('$rAccountant', 'invoices.view'),
 ('$rAccountant', 'invoices.create'),
 ('$rAccountant', 'payments.record'),
 ('$rAccountant', 'discounts.apply'),
 ('$rAccountant', 'invoices.cancel'),
 ('$rAccountant', 'reports.financial.view')
ON CONFLICT DO NOTHING;

DELETE FROM "public"."clinic_user_roles"
 WHERE "userId" IN ('$uAdmin','$uEmp','$uClinic','$uPlatform','$uClinicAdmin','$uReception','$uDoctor','$uAccountant','$uOwnerDoctor');

INSERT INTO "public"."clinic_user_roles" ("organizationId", "clinicId", "userId", "roleId", "assignedByUserId")
VALUES
 ('$orgId', '$projId', '$uPlatform',    '$rPlatform',    '$uAdmin'),
 ('$orgId', '$projId', '$uAdmin',       '$rClinicAdmin', '$uAdmin'),
 ('$orgId', '$projId', '$uClinicAdmin', '$rClinicAdmin', '$uAdmin'),
 ('$orgId', '$projId', '$uClinic',      '$rReception',   '$uAdmin'),
 ('$orgId', '$projId', '$uReception',   '$rReception',   '$uAdmin'),
 ('$orgId', '$projId', '$uEmp',         '$rDoctor',      '$uAdmin'),
 ('$orgId', '$projId', '$uDoctor',      '$rDoctor',      '$uAdmin'),
 ('$orgId', '$projId', '$uAccountant',  '$rAccountant',  '$uAdmin'),
 -- Multi-role union: Owner + Doctor holds both clinic_admin AND doctor
 ('$orgId', '$projId', '$uOwnerDoctor', '$rClinicAdmin', '$uAdmin'),
 ('$orgId', '$projId', '$uOwnerDoctor', '$rDoctor',      '$uAdmin')
ON CONFLICT DO NOTHING;
"@

$dir = Split-Path -Parent $CredentialsFile
if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
@(
  'FirstElement Dental Clinic RBAC Credentials'
  ('generated: ' + (Get-Date).ToUniversalTime().ToString('o'))
  ('database: ' + $Database)
  ''
  ('password (all accounts): ' + $Password)
  ''
  '  1. platform@human.invalid      [platform_admin]         Clinics & Subscriptions only (zero clinic data access)'
  '  2. clinic-admin@human.invalid  [clinic_admin]           Clinic Settings, Users/Roles, Appointments, Billing, Reports (no medical records by default)'
  '  3. receptionist@human.invalid  [receptionist]           Appointments, Patients Admin, Create Invoice, Record Payment (no discounts/cancel/clinical)'
  '  4. doctor@human.invalid        [doctor]                 Own Appointments, Medical History, Odontogram, Clinical Notes, Treatment Plans'
  '  5. accountant@human.invalid    [accountant]             Invoices, Payments, Discounts, Cancel Invoice, Financial Reports (no clinical/settings)'
  '  6. owner-doctor@human.invalid  [clinic_admin + doctor]  Multi-role union: full Clinic Admin + full Doctor clinical access'
  ''
  '  Legacy aliases (still supported):'
  '  - admin@human.invalid          -> clinic_admin'
  '  - clinic@human.invalid         -> receptionist'
  '  - employee@human.invalid       -> doctor'
  ''
  'This is a THROWAWAY environment. Rotate it by re-running seed-human-test.ps1 -Password <new>.'
) | Set-Content -LiteralPath $CredentialsFile -Encoding UTF8

Write-QLog ("all 6 Clinic RBAC accounts (+ 3 aliases) seeded; password written to {0}" -f $CredentialsFile) 'STEP'
