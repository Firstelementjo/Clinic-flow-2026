-- =====================================================================
-- seed-contaminated-cross-tenant.sql
--
-- SYNTHETIC fixture that reproduces the breach on purpose. This is the
-- POSITIVE CONTROL: without it, a forensic query that returned zero for
-- every reason other than "clean" would still look correct, and the
-- rehearsal would prove nothing.
--
-- It contains one deliberate instance of each of the eight signals. All
-- values are fabricated; there is no production content here.
--
-- Expected result: 8 findings, one per check FE-FOR-001..FE-FOR-008.
-- =====================================================================

BEGIN;

-- --- tenants ---------------------------------------------------------
INSERT INTO organizations (id, name, slug, "isActive", "createdAt", "updatedAt") VALUES
    ('11111111-0000-4000-8000-000000000001', 'Synthetic Tenant Alpha', 'synthetic-alpha', true, '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
    ('11111111-0000-4000-8000-000000000002', 'Synthetic Tenant Beta',  'synthetic-beta',  true, '2026-01-01 00:00:00', '2026-01-01 00:00:00');

INSERT INTO projects (id, "organizationId", name, slug, "isArchived", "createdAt", "updatedAt") VALUES
    ('22222222-0000-4000-8000-0000000000a1', '11111111-0000-4000-8000-000000000001', 'Synthetic Project Alpha', 'synthetic-project-alpha', false, '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
    ('22222222-0000-4000-8000-0000000000a2', '11111111-0000-4000-8000-000000000001', 'Synthetic Project Alpha 2', 'synthetic-project-alpha-2', false, '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
    ('22222222-0000-4000-8000-0000000000b1', '11111111-0000-4000-8000-000000000002', 'Synthetic Project Beta',  'synthetic-project-beta',  false, '2026-01-01 00:00:00', '2026-01-01 00:00:00');

-- --- users -----------------------------------------------------------
-- a1: Alpha admin, member of Alpha project a1.
-- a3: Alpha user with NO membership anywhere -- the "valid session, no
--      membership" principal the breach description names. This is the
--      actor used for the cross-tenant reschedule and cancel.
INSERT INTO users (id, "organizationId", email, "passwordHash", "fullName", role, "isActive", "createdAt", "updatedAt") VALUES
    ('33333333-0000-4000-8000-0000000000a1', '11111111-0000-4000-8000-000000000001', 'synthetic.actor.alpha@example.invalid', 'SYNTHETIC-NOT-A-HASH', 'Synthetic Actor Alpha', 'ADMIN', true, '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
    ('33333333-0000-4000-8000-0000000000a3', '11111111-0000-4000-8000-000000000001', 'synthetic.nomember.alpha@example.invalid', 'SYNTHETIC-NOT-A-HASH', 'Synthetic No Member Alpha', 'EMPLOYEE', true, '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
    ('33333333-0000-4000-8000-0000000000b1', '11111111-0000-4000-8000-000000000002', 'synthetic.actor.beta@example.invalid', 'SYNTHETIC-NOT-A-HASH', 'Synthetic Actor Beta', 'ADMIN', true, '2026-01-01 00:00:00', '2026-01-01 00:00:00');

INSERT INTO project_memberships (id, "organizationId", "projectId", "userId", "createdAt") VALUES
    ('44444444-0000-4000-8000-0000000000a1', '11111111-0000-4000-8000-000000000001', '22222222-0000-4000-8000-0000000000a1', '33333333-0000-4000-8000-0000000000a1', '2026-01-01 00:00:00'),
    ('44444444-0000-4000-8000-0000000000b1', '11111111-0000-4000-8000-000000000002', '22222222-0000-4000-8000-0000000000b1', '33333333-0000-4000-8000-0000000000b1', '2026-01-01 00:00:00');

-- --- customers -------------------------------------------------------
INSERT INTO customers (id, "organizationId", "projectId", "cxId", "displayName", "createdAt", "updatedAt") VALUES
    ('55555555-0000-4000-8000-0000000000a1', '11111111-0000-4000-8000-000000000001', '22222222-0000-4000-8000-0000000000a1', 'SYN-A001', 'SYNTHETIC-PATIENT-1', '2026-01-02 00:00:00', '2026-01-02 00:00:00'),
    ('55555555-0000-4000-8000-0000000000b1', '11111111-0000-4000-8000-000000000002', '22222222-0000-4000-8000-0000000000b1', 'SYN-B001', 'SYNTHETIC-PATIENT-2', '2026-01-02 00:00:00', '2026-01-02 00:00:00');

-- --- roster ----------------------------------------------------------
-- DOCTOR b1 belongs to Beta's project but carries Alpha's organizationId:
-- the "a doctor could be moved into another tenant" case, for FE-FOR-006.
INSERT INTO doctors (id, "organizationId", "projectId", name, specialty, "startHour", "endHour", "slotDuration", "isActive", "createdAt", "updatedAt") VALUES
    ('66666666-0000-4000-8000-0000000000a1', '11111111-0000-4000-8000-000000000001', '22222222-0000-4000-8000-0000000000a1', 'SYNTHETIC-DOCTOR-ALPHA', 'SYNTHETIC-SPECIALTY', '09:00', '17:00', 30, true, '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
    ('66666666-0000-4000-8000-0000000000b1', '11111111-0000-4000-8000-000000000001', '22222222-0000-4000-8000-0000000000b1', 'SYNTHETIC-DOCTOR-MOVED', 'SYNTHETIC-SPECIALTY', '09:00', '17:00', 30, true, '2026-01-01 00:00:00', '2026-03-01 00:00:00');

INSERT INTO procedures (id, "organizationId", "projectId", name, specialty, "durationMinutes", "bufferMinutes", "isActive", "createdAt", "updatedAt") VALUES
    ('77777777-0000-4000-8000-0000000000a1', '11111111-0000-4000-8000-000000000001', '22222222-0000-4000-8000-0000000000a1', 'SYNTHETIC-PROCEDURE-ALPHA', 'SYNTHETIC-SPECIALTY', 30, 0, true, '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
    ('77777777-0000-4000-8000-0000000000b1', '11111111-0000-4000-8000-000000000002', '22222222-0000-4000-8000-0000000000b1', 'SYNTHETIC-PROCEDURE-BETA',  'SYNTHETIC-SPECIALTY', 30, 0, true, '2026-01-01 00:00:00', '2026-01-01 00:00:00');

-- TASK t1 carries Alpha's organizationId inside Beta's project: the
-- tasks.update cross-tenant write, for FE-FOR-006.
INSERT INTO tasks (id, "organizationId", "projectId", title, "priority", status, "createdAt", "updatedAt") VALUES
    ('88888888-0000-4000-8000-0000000000a1', '11111111-0000-4000-8000-000000000001', '22222222-0000-4000-8000-0000000000b1', 'SYNTHETIC-TASK-CROSSED', 'MEDIUM', 'OPEN', '2026-01-03 00:00:00', '2026-03-02 00:00:00');

-- --- appointments ----------------------------------------------------
-- a1: an ordinary Beta appointment whose doctor lives in Alpha (the
--     moved roster row) -- FE-FOR-005.
-- a2: an Alpha appointment moved AFTER its last audit row, with no
--     audit row covering the move -- FE-FOR-007.
INSERT INTO appointments (id, "organizationId", "projectId", "customerId", "doctorId", "procedureId", title, "patientName", "appointmentDate", "durationMinutes", status, "createdById", "createdAt", "updatedAt") VALUES
    ('99999999-0000-4000-8000-0000000000b1', '11111111-0000-4000-8000-000000000002', '22222222-0000-4000-8000-0000000000b1', '55555555-0000-4000-8000-0000000000b1', '66666666-0000-4000-8000-0000000000b1', '77777777-0000-4000-8000-0000000000b1', 'SYNTHETIC-APPOINTMENT-BETA', 'SYNTHETIC-PATIENT-2', '2026-02-01 09:00:00', 30, 'CANCELLED', '33333333-0000-4000-8000-0000000000b1', '2026-01-10 08:00:00', '2026-01-10 08:00:00'),
    ('99999999-0000-4000-8000-0000000000a1', '11111111-0000-4000-8000-000000000001', '22222222-0000-4000-8000-0000000000a1', '55555555-0000-4000-8000-0000000000a1', '66666666-0000-4000-8000-0000000000a1', '77777777-0000-4000-8000-0000000000a1', 'SYNTHETIC-APPOINTMENT-ALPHA', 'SYNTHETIC-PATIENT-1', '2026-02-01 09:00:00', 30, 'CANCELLED', '33333333-0000-4000-8000-0000000000a1', '2026-01-10 08:00:00', '2026-03-05 12:00:00');

-- --- audit rows ------------------------------------------------------
-- a1/a2 FE-FOR-001: Alpha actor reschedules and then cancels a BETA
--      appointment. The audit row carries the ACTOR's organization
--      (Alpha) against a target in Beta -- the bounded lead, positive.
-- a3 FE-FOR-003: an audited appointment whose target row was deleted.
-- a4 FE-FOR-004: the audit row claims organization Beta, but the
--      actor user belongs to Alpha.
-- a5 FE-FOR-008: Alpha actor with no membership in Beta's project.
INSERT INTO audit_logs (id, "organizationId", "projectId", "actorUserId", "actorType", action, "resourceType", "resourceId", metadata, "createdAt") VALUES
    ('aaaaaaaa-0000-4000-8000-0000000000c1', '11111111-0000-4000-8000-000000000001', '22222222-0000-4000-8000-0000000000b1', '33333333-0000-4000-8000-0000000000a3', 'USER', 'APPOINTMENT_RESCHEDULED', 'APPOINTMENT', '99999999-0000-4000-8000-0000000000b1', '{}'::jsonb, '2026-02-10 10:00:00'),
    ('aaaaaaaa-0000-4000-8000-0000000000c2', '11111111-0000-4000-8000-000000000001', '22222222-0000-4000-8000-0000000000b1', '33333333-0000-4000-8000-0000000000a3', 'USER', 'APPOINTMENT_CANCELLED',   'APPOINTMENT', '99999999-0000-4000-8000-0000000000b1', '{}'::jsonb, '2026-02-11 10:00:00'),
    ('aaaaaaaa-0000-4000-8000-0000000000c3', '11111111-0000-4000-8000-000000000002', '22222222-0000-4000-8000-0000000000b1', '33333333-0000-4000-8000-0000000000b1', 'USER', 'APPOINTMENT_CREATED',     'APPOINTMENT', '99999999-0000-4000-8000-00000000dead', '{}'::jsonb, '2026-02-12 10:00:00'),
    ('aaaaaaaa-0000-4000-8000-0000000000c4', '11111111-0000-4000-8000-000000000002', '22222222-0000-4000-8000-0000000000b1', '33333333-0000-4000-8000-0000000000a3', 'USER', 'APPOINTMENT_RESCHEDULED', 'APPOINTMENT', '99999999-0000-4000-8000-0000000000b1', '{}'::jsonb, '2026-02-13 10:00:00'),
    ('aaaaaaaa-0000-4000-8000-0000000000c5', '11111111-0000-4000-8000-000000000001', '22222222-0000-4000-8000-0000000000b1', '33333333-0000-4000-8000-0000000000a3', 'USER', 'APPOINTMENT_RESCHEDULED', 'APPOINTMENT', '99999999-0000-4000-8000-0000000000b1', '{}'::jsonb, '2026-02-14 10:00:00'),
    -- FE-FOR-002: the SAME organization, a DIFFERENT project. Invisible
    -- to FE-FOR-001 by construction, because that check only compares
    -- organizations -- which is exactly why the cross-project case
    -- needs a signal of its own.
    ('aaaaaaaa-0000-4000-8000-0000000000c6', '11111111-0000-4000-8000-000000000001', '22222222-0000-4000-8000-0000000000a2', '33333333-0000-4000-8000-0000000000a1', 'USER', 'APPOINTMENT_RESCHEDULED', 'APPOINTMENT', '99999999-0000-4000-8000-0000000000a1', '{}'::jsonb, '2026-02-15 10:00:00');

COMMIT;