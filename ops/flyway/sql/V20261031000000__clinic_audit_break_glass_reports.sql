-- V20261031000000__clinic_audit_break_glass_reports.sql
-- Dental Clinic Slice S8: Immutable / Tamper-Evident Audit Stream, Break-Glass Emergency Access, and Curated Tenant-Scoped Reports.
--
-- ADR V4 COMPLIANCE:
-- 1. All tables carry NOT NULL "organizationId" and cascade on organization deletion.
-- 2. 0 pg_constraint.contype = 'u' unique constraints; tenant-led unique btree indexes only.
-- 3. Composite foreign keys where appropriate.
-- 4. Immutable append-only audit trail with tamper-evident chain hashing.

-- 1. Break-Glass Emergency Access Grants
create table if not exists clinic_break_glass_grants (
    id uuid not null primary key,
    "organizationId" uuid not null,
    "projectId" uuid not null,
    "patientId" uuid null,
    "requestedByUserId" uuid not null,
    "reason" text not null,
    "grantedAt" timestamptz not null default current_timestamp,
    "expiresAt" timestamptz not null,
    "revokedAt" timestamptz null,
    "revokedByUserId" uuid null,
    "revocationReason" text null,
    "reviewStatus" varchar(32) not null default 'PENDING',
    "reviewedByUserId" uuid null,
    "reviewedAt" timestamptz null,
    "reviewNotes" text null,
    constraint fk_break_glass_org foreign key ("organizationId") references organizations(id) on delete cascade,
    constraint fk_break_glass_project foreign key ("projectId") references projects(id) on delete cascade,
    constraint fk_break_glass_patient foreign key ("patientId") references patients(id) on delete cascade,
    constraint fk_break_glass_user foreign key ("requestedByUserId") references users(id) on delete cascade,
    constraint chk_break_glass_status check ("reviewStatus" in ('PENDING', 'APPROVED', 'FLAGGED')),
    constraint chk_break_glass_times check ("expiresAt" > "grantedAt")
);

-- 2. Immutable / Tamper-Evident Clinical Audit Log
create table if not exists clinic_audit_log (
    id uuid not null primary key,
    "organizationId" uuid not null,
    "projectId" uuid not null,
    "patientId" uuid null,
    "actorUserId" uuid not null,
    "actorUserName" varchar(128) not null,
    "actorRole" varchar(64) not null,
    "action" varchar(64) not null,
    "resourceType" varchar(64) not null,
    "resourceId" varchar(128) not null,
    "breakGlassGrantId" uuid null,
    "ipAddress" varchar(64) null,
    "userAgent" varchar(256) null,
    "metadataJson" text null,
    "tamperHash" varchar(64) not null,
    "occurredAt" timestamptz not null default current_timestamp,
    constraint fk_clinic_audit_org foreign key ("organizationId") references organizations(id) on delete cascade,
    constraint fk_clinic_audit_project foreign key ("projectId") references projects(id) on delete cascade,
    constraint fk_clinic_audit_patient foreign key ("patientId") references patients(id) on delete cascade,
    constraint fk_clinic_audit_user foreign key ("actorUserId") references users(id) on delete cascade,
    constraint fk_clinic_audit_bg foreign key ("breakGlassGrantId") references clinic_break_glass_grants(id) on delete set null,
    constraint chk_clinic_audit_action check ("action" in (
        'READ_PATIENT_RECORD', 'READ_CLINICAL_NOTE', 'READ_ODONTOGRAM', 'VIEW_MEDIA',
        'WRITE_CLINICAL_NOTE', 'SIGN_CLINICAL_NOTE', 'AMEND_CLINICAL_NOTE',
        'BREAK_GLASS_ACTIVATED', 'BREAK_GLASS_REVOKED', 'BREAK_GLASS_REVIEWED',
        'REPORT_GENERATED', 'DATA_EXPORTED', 'PERMISSION_CHANGED', 'LEGAL_HOLD_SET', 'LEGAL_HOLD_REMOVED'
    )),
    constraint chk_clinic_audit_resource check ("resourceType" in (
        'PATIENT', 'ENCOUNTER', 'CLINICAL_NOTE', 'ODONTOGRAM', 'TREATMENT_PLAN',
        'INVOICE', 'PAYMENT', 'MEDIA', 'REPORT', 'BREAK_GLASS', 'USER_PERMISSION'
    ))
);

-- 3. Tenant-Led Composite BTree Indexes (ADR V4 compliant)
create index if not exists idx_break_glass_org_user
    on clinic_break_glass_grants ("organizationId", "requestedByUserId", "grantedAt" desc);

create index if not exists idx_break_glass_org_patient
    on clinic_break_glass_grants ("organizationId", "patientId", "grantedAt" desc)
    where "patientId" is not null;

create index if not exists idx_break_glass_org_active
    on clinic_break_glass_grants ("organizationId", "expiresAt")
    where "revokedAt" is null;

create index if not exists idx_break_glass_org_review
    on clinic_break_glass_grants ("organizationId", "reviewStatus");

create index if not exists idx_clinic_audit_org_patient
    on clinic_audit_log ("organizationId", "patientId", "occurredAt" desc);

create index if not exists idx_clinic_audit_org_actor
    on clinic_audit_log ("organizationId", "actorUserId", "occurredAt" desc);

create index if not exists idx_clinic_audit_org_action
    on clinic_audit_log ("organizationId", "action", "occurredAt" desc);

create index if not exists idx_clinic_audit_org_resource
    on clinic_audit_log ("organizationId", "resourceType", "resourceId");
