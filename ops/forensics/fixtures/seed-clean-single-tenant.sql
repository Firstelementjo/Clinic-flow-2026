-- =====================================================================
-- seed-clean-single-tenant.sql
--
-- SYNTHETIC forensic fixture. Two tenants, fully populated, and every
-- record internally consistent: no audit actor organization disagrees
-- with its target, no roster row sits in the wrong tenant, no audit row
-- names a project its actor does not hold.
--
-- Purpose: prove the forensic query returns CLEAN on data that is known
-- clean. Without this, a clean result on production is uninterpretable --
-- a query that is broken, or scoped wrongly, or silently returning zero
-- for the wrong reason looks exactly like a clean one.
--
-- Every value here is fabricated. There is no production content in
-- this file and no patient data: patients are "SYNTHETIC-PATIENT-n".
--
-- Scenario coverage (the rehearsal runs this file, and a second file
-- that is this one plus deliberate contamination):
--   * FE-FOR-001  clean -- audit actor org == appointment org
--   * FE-FOR-002  clean -- audit project == appointment project
--   * FE-FOR-003  clean -- every audited appointment target exists
--   * FE-FOR-004  clean -- audit org == the actor user's org
--   * FE-FOR-005  clean -- appointment refs all same-org
--   * FE-FOR-006  clean -- roster/task org == project org
--   * FE-FOR-007  clean -- updatedAt never later than the last audit row
--   * FE-FOR-008  clean -- every audit actor holds project membership
-- =====================================================================

BEGIN;

-- --- tenants ---------------------------------------------------------
INSERT INTO organizations (id, name, slug, "isActive", "createdAt", "updatedAt") VALUES
    ('11111111-0000-4000-8000-000000000001', 'Synthetic Tenant Alpha', 'synthetic-alpha', true, '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
    ('11111111-0000-4000-8000-000000000002', 'Synthetic Tenant Beta',  'synthetic-beta',  true, '2026-01-01 00:00:00', '2026-01-01 00:00:00');

INSERT INTO projects (id, "organizationId", name, slug, "isArchived", "createdAt", "updatedAt") VALUES
    ('22222222-0000-4000-8000-0000000000a1', '11111111-0000-4000-8000-000000000001', 'Synthetic Project Alpha', 'synthetic-project-alpha', false, '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
    ('22222222-0000-4000-8000-0000000000b1', '11111111-0000-4000-8000-000000000002', 'Synthetic Project Beta',  'synthetic-project-beta',  false, '2026-01-01 00:00:00', '2026-01-01 00:00:00');

-- --- users, one per tenant ------------------------------------------
INSERT INTO users (id, "organizationId", email, "passwordHash", "fullName", role, "isActive", "createdAt", "updatedAt") VALUES
    ('33333333-0000-4000-8000-0000000000a1', '11111111-0000-4000-8000-000000000001', 'synthetic.actor.alpha@example.invalid', 'SYNTHETIC-NOT-A-HASH', 'Synthetic Actor Alpha', 'ADMIN', true, '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
    ('33333333-0000-4000-8000-0000000000b1', '11111111-0000-4000-8000-000000000002', 'synthetic.actor.beta@example.invalid',  'SYNTHETIC-NOT-A-HASH', 'Synthetic Actor Beta',  'ADMIN', true, '2026-01-01 00:00:00', '2026-01-01 00:00:00');

INSERT INTO project_memberships (id, "organizationId", "projectId", "userId", "createdAt") VALUES
    ('44444444-0000-4000-8000-0000000000a1', '11111111-0000-4000-8000-000000000001', '22222222-0000-4000-8000-0000000000a1', '33333333-0000-4000-8000-0000000000a1', '2026-01-01 00:00:00'),
    ('44444444-0000-4000-8000-0000000000b1', '11111111-0000-4000-8000-000000000002', '22222222-0000-4000-8000-0000000000b1', '33333333-0000-4000-8000-0000000000b1', '2026-01-01 00:00:00');

-- --- customers -------------------------------------------------------
INSERT INTO customers (id, "organizationId", "projectId", "cxId", "displayName", "createdAt", "updatedAt") VALUES
    ('55555555-0000-4000-8000-0000000000a1', '11111111-0000-4000-8000-000000000001', '22222222-0000-4000-8000-0000000000a1', 'SYN-A001', 'SYNTHETIC-PATIENT-1', '2026-01-02 00:00:00', '2026-01-02 00:00:00'),
    ('55555555-0000-4000-8000-0000000000b1', '11111111-0000-4000-8000-000000000002', '22222222-0000-4000-8000-0000000000b1', 'SYN-B001', 'SYNTHETIC-PATIENT-2', '2026-01-02 00:00:00', '2026-01-02 00:00:00');

-- --- roster ----------------------------------------------------------
INSERT INTO doctors (id, "organizationId", "projectId", name, specialty, "startHour", "endHour", "slotDuration", "isActive", "createdAt", "updatedAt") VALUES
    ('66666666-0000-4000-8000-0000000000a1', '11111111-0000-4000-8000-000000000001', '22222222-0000-4000-8000-0000000000a1', 'SYNTHETIC-DOCTOR-ALPHA', 'SYNTHETIC-SPECIALTY', '09:00', '17:00', 30, true, '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
    ('66666666-0000-4000-8000-0000000000b1', '11111111-0000-4000-8000-000000000002', '22222222-0000-4000-8000-0000000000b1', 'SYNTHETIC-DOCTOR-BETA',  'SYNTHETIC-SPECIALTY', '09:00', '17:00', 30, true, '2026-01-01 00:00:00', '2026-01-01 00:00:00');

INSERT INTO procedures (id, "organizationId", "projectId", name, specialty, "durationMinutes", "bufferMinutes", "isActive", "createdAt", "updatedAt") VALUES
    ('77777777-0000-4000-8000-0000000000a1', '11111111-0000-4000-8000-000000000001', '22222222-0000-4000-8000-0000000000a1', 'SYNTHETIC-PROCEDURE-ALPHA', 'SYNTHETIC-SPECIALTY', 30, 0, true, '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
    ('77777777-0000-4000-8000-0000000000b1', '11111111-0000-4000-8000-000000000002', '22222222-0000-4000-8000-0000000000b1', 'SYNTHETIC-PROCEDURE-BETA',  'SYNTHETIC-SPECIALTY', 30, 0, true, '2026-01-01 00:00:00', '2026-01-01 00:00:00');

INSERT INTO tasks (id, "organizationId", "projectId", title, "priority", status, "createdAt", "updatedAt") VALUES
    ('88888888-0000-4000-8000-0000000000a1', '11111111-0000-4000-8000-000000000001', '22222222-0000-4000-8000-0000000000a1', 'SYNTHETIC-TASK-ALPHA', 'MEDIUM', 'OPEN', '2026-01-03 00:00:00', '2026-01-03 00:00:00'),
    ('88888888-0000-4000-8000-0000000000b1', '11111111-0000-4000-8000-000000000002', '22222222-0000-4000-8000-0000000000b1', 'SYNTHETIC-TASK-BETA',  'MEDIUM', 'OPEN', '2026-01-03 00:00:00', '2026-01-03 00:00:00');

-- --- appointments ----------------------------------------------------
-- updatedAt is deliberately EARLIER than the corresponding audit row's
-- createdAt, because the service always writes the audit row after the
-- row change. That is what makes FE-FOR-007 quiet on clean data.
INSERT INTO appointments (id, "organizationId", "projectId", "customerId", "doctorId", "procedureId", title, "patientName", "appointmentDate", "durationMinutes", status, "createdById", "createdAt", "updatedAt") VALUES
    ('99999999-0000-4000-8000-0000000000a1', '11111111-0000-4000-8000-000000000001', '22222222-0000-4000-8000-0000000000a1', '55555555-0000-4000-8000-0000000000a1', '66666666-0000-4000-8000-0000000000a1', '77777777-0000-4000-8000-0000000000a1', 'SYNTHETIC-APPOINTMENT-ALPHA', 'SYNTHETIC-PATIENT-1', '2026-02-01 09:00:00', 30, 'CONFIRMED', '33333333-0000-4000-8000-0000000000a1', '2026-01-10 08:00:00', '2026-01-10 08:00:00'),
    ('99999999-0000-4000-8000-0000000000b1', '11111111-0000-4000-8000-000000000002', '22222222-0000-4000-8000-0000000000b1', '55555555-0000-4000-8000-0000000000b1', '66666666-0000-4000-8000-0000000000b1', '77777777-0000-4000-8000-0000000000b1', 'SYNTHETIC-APPOINTMENT-BETA',  'SYNTHETIC-PATIENT-2', '2026-02-01 09:00:00', 30, 'CONFIRMED', '33333333-0000-4000-8000-0000000000b1', '2026-01-10 08:00:00', '2026-01-10 08:00:00');

-- --- audit rows ------------------------------------------------------
-- Each actor's organizationId equals the target's, each actor holds
-- project membership, and each appointment is audited for creation,
-- reschedule and cancellation. metadata is an empty object on purpose:
-- the forensic query never reads it, and this fixture does not need to
-- put anything in it.
INSERT INTO audit_logs (id, "organizationId", "projectId", "actorUserId", "actorType", action, "resourceType", "resourceId", metadata, "createdAt") VALUES
    ('aaaaaaaa-0000-4000-8000-0000000000a1', '11111111-0000-4000-8000-000000000001', '22222222-0000-4000-8000-0000000000a1', '33333333-0000-4000-8000-0000000000a1', 'USER', 'APPOINTMENT_CREATED',     'APPOINTMENT', '99999999-0000-4000-8000-0000000000a1', '{}'::jsonb, '2026-01-10 08:00:05'),
    ('aaaaaaaa-0000-4000-8000-0000000000a2', '11111111-0000-4000-8000-000000000001', '22222222-0000-4000-8000-0000000000a1', '33333333-0000-4000-8000-0000000000a1', 'USER', 'APPOINTMENT_RESCHEDULED', 'APPOINTMENT', '99999999-0000-4000-8000-0000000000a1', '{}'::jsonb, '2026-01-12 11:30:00'),
    ('aaaaaaaa-0000-4000-8000-0000000000a3', '11111111-0000-4000-8000-000000000001', '22222222-0000-4000-8000-0000000000a1', '33333333-0000-4000-8000-0000000000a1', 'USER', 'APPOINTMENT_CANCELLED',   'APPOINTMENT', '99999999-0000-4000-8000-0000000000a1', '{}'::jsonb, '2026-01-15 07:45:00'),
    ('aaaaaaaa-0000-4000-8000-0000000000b1', '11111111-0000-4000-8000-000000000002', '22222222-0000-4000-8000-0000000000b1', '33333333-0000-4000-8000-0000000000b1', 'USER', 'APPOINTMENT_CREATED',     'APPOINTMENT', '99999999-0000-4000-8000-0000000000b1', '{}'::jsonb, '2026-01-10 08:00:05'),
    ('aaaaaaaa-0000-4000-8000-0000000000b2', '11111111-0000-4000-8000-000000000002', '22222222-0000-4000-8000-0000000000b1', '33333333-0000-4000-8000-0000000000b1', 'USER', 'APPOINTMENT_RESCHEDULED', 'APPOINTMENT', '99999999-0000-4000-8000-0000000000b1', '{}'::jsonb, '2026-01-12 11:30:00');

COMMIT;