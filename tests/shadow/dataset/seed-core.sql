-- =====================================================================
-- seed-core.sql -- the FirstElement Phase-12 shadow-parity dataset
--
-- APPLIED IDENTICALLY TO BOTH DISPOSABLE DATABASES:
--   shadow_check_legacy   (the frozen NestJS/Prisma reference)
--   shadow_check_spring   (the new Spring/PostgreSQL runtime)
--
-- WHY TWO FILES. `seed-core.sql` is the slice both stacks can read: the 45
-- tables of the verified legacy schema. `seed-new-only.sql` carries the
-- finance and export tables that exist only after the Flyway chain, and is
-- applied to the Spring database alone.
--
-- WHY FIXED IDS. plan.md 6.1 requires existing UUIDs, ticket numbers, cxIds
-- and provider ids to be preserved. A parity harness cannot demonstrate that
-- with rows minted at run time, because two runs would never agree. Every id
-- below is a literal, every timestamp is a literal, and the same file is
-- replayed into both databases so "the same row" means literally the same row.
--
-- NOTHING HERE IS REAL. No customer name, phone number, email, or message body
-- in this file came from anywhere but this file. The bcrypt hash is of the
-- synthetic password in `shadow-credentials.md`; it is not a credential for
-- anything.
--
-- TENANT SHAPE. Three organizations so that "same tenant, other project" and
-- "other tenant" are separable cases rather than one vague case:
--   ORG_A  two projects (A1, A2) -- exercises cross-project scoping
--   ORG_B  one project (B1)       -- exercises cross-tenant refusal
-- =====================================================================

BEGIN;

-- ---------------------------------------------------------------------
-- Organizations
-- ---------------------------------------------------------------------
INSERT INTO organizations ("id", "name", "slug", "defaultLocale", "timezone", "isActive", "createdAt", "updatedAt") VALUES
  ('11111111-1111-4111-8111-111111111111', 'Shadow Alpha Dental', 'shadow-alpha', 'ar', 'Asia/Riyadh', true,  '2026-01-05 08:00:00', '2026-01-05 08:00:00'),
  ('22222222-2222-4222-8222-222222222222', 'Shadow Beta Clinic',  'shadow-beta',  'en', 'Asia/Riyadh', true,  '2026-01-05 08:05:00', '2026-01-05 08:05:00'),
  ('33333333-3333-4333-8333-333333333333', 'Shadow Gamma Care',  'shadow-gamma', 'ar', 'Asia/Riyadh', true,  '2026-01-05 08:10:00', '2026-01-05 08:10:00');

-- ---------------------------------------------------------------------
-- Projects
-- ---------------------------------------------------------------------
INSERT INTO projects ("id", "organizationId", "name", "slug", "defaultLocale", "timezone", "isArchived", "createdAt", "updatedAt") VALUES
  ('a1a1a1a1-0000-4000-8000-000000000001', '11111111-1111-4111-8111-111111111111', 'Alpha Main',   'alpha-main',   'ar', 'Asia/Riyadh', false, '2026-01-05 08:01:00', '2026-01-05 08:01:00'),
  ('a1a1a1a1-0000-4000-8000-000000000002', '11111111-1111-4111-8111-111111111111', 'Alpha Second', 'alpha-second', 'ar', 'Asia/Riyadh', false, '2026-01-05 08:02:00', '2026-01-05 08:02:00'),
  ('b1b1b1b1-0000-4000-8000-000000000001', '22222222-2222-4222-8222-222222222222', 'Beta Main',    'beta-main',    'en', 'Asia/Riyadh', false, '2026-01-05 08:06:00', '2026-01-05 08:06:00'),
  ('c1c1c1c1-0000-4000-8000-000000000001', '33333333-3333-4333-8333-333333333333', 'Gamma Main',   'gamma-main',   'ar', 'Asia/Riyadh', false, '2026-01-05 08:11:00', '2026-01-05 08:11:00');

-- ---------------------------------------------------------------------
-- Users. One bcrypt hash, of the synthetic password, shared by all of them:
-- the legacy runtime hashes with bcryptjs and the new runtime verifies with
-- Spring's BCryptPasswordEncoder, and both accept $2a$12$. Using one hash is
-- what lets a single synthetic principal authenticate on BOTH stacks.
-- ---------------------------------------------------------------------
INSERT INTO users ("id", "organizationId", "email", "passwordHash", "fullName", "role", "locale", "isActive", "lastLoginAt", "createdAt", "updatedAt") VALUES
  ('0a000001-0000-4000-8000-000000000001', '11111111-1111-4111-8111-111111111111', 'shadow-admin-a@shadow.invalid',   '$2a$12$oVTiTXCo/xKfWFk/WlLVJezvukJF5zgnc.H5YU0eTGjbHI83NvJ0W', 'Shadow Admin A',   'ADMIN',    'ar', true, '2026-02-01 09:00:00', '2026-01-05 08:03:00', '2026-01-05 08:03:00'),
  ('0a000001-0000-4000-8000-000000000002', '11111111-1111-4111-8111-111111111111', 'shadow-agent-a1@shadow.invalid', '$2a$12$oVTiTXCo/xKfWFk/WlLVJezvukJF5zgnc.H5YU0eTGjbHI83NvJ0W', 'Shadow Agent A One', 'EMPLOYEE','ar', true, '2026-02-01 09:01:00', '2026-01-05 08:04:00', '2026-01-05 08:04:00'),
  ('0a000001-0000-4000-8000-000000000003', '11111111-1111-4111-8111-111111111111', 'shadow-agent-a2@shadow.invalid', '$2a$12$oVTiTXCo/xKfWFk/WlLVJezvukJF5zgnc.H5YU0eTGjbHI83NvJ0W', 'Shadow Agent A Two', 'EMPLOYEE','ar', true, '2026-02-01 09:02:00', '2026-01-05 08:04:30', '2026-01-05 08:04:30'),
  ('0a000001-0000-4000-8000-000000000004', '22222222-2222-4222-8222-222222222222', 'shadow-admin-b@shadow.invalid',   '$2a$12$oVTiTXCo/xKfWFk/WlLVJezvukJF5zgnc.H5YU0eTGjbHI83NvJ0W', 'Shadow Admin B',   'ADMIN',    'en', true, '2026-02-01 09:03:00', '2026-01-05 08:07:00', '2026-01-05 08:07:00');

-- ---------------------------------------------------------------------
-- Employees (1:1 with a user)
-- ---------------------------------------------------------------------
INSERT INTO employees ("id", "organizationId", "userId", "department", "availabilityStatus", "statusChangedAt", "maxConcurrentConversations", "activeConversationCount", "createdAt", "updatedAt") VALUES
  ('0e000001-0000-4000-8000-000000000002', '11111111-1111-4111-8111-111111111111', '0a000001-0000-4000-8000-000000000002', 'Front Desk',  'ONLINE',  '2026-02-01 08:00:00', 5, 1, '2026-01-05 08:04:00', '2026-01-05 08:04:00'),
  ('0e000001-0000-4000-8000-000000000003', '11111111-1111-4111-8111-111111111111', '0a000001-0000-4000-8000-000000000003', 'Clinical',    'OFFLINE', '2026-02-01 08:00:00', 5, 0, '2026-01-05 08:04:30', '2026-01-05 08:04:30'),
  ('0e000001-0000-4000-8000-000000000004', '22222222-2222-4222-8222-222222222222', '0a000001-0000-4000-8000-000000000004', 'Reception',   'ONLINE',  '2026-02-01 08:00:00', 5, 0, '2026-01-05 08:07:00', '2026-01-05 08:07:00');

-- ---------------------------------------------------------------------
-- Project memberships. This is the whole authorization surface of the
-- dataset, so it is deliberately uneven:
--   admin-a  -> A1 and A2   (cross-project read must be ALLOWED)
--   agent-a1 -> A1 only    (A2 must be DENIED)
--   agent-a2 -> A2 only    (A1 must be DENIED)
--   admin-b  -> B1 only    (A1 and A2 must be DENIED -- other tenant)
-- ---------------------------------------------------------------------
INSERT INTO project_memberships ("id", "organizationId", "projectId", "userId", "createdAt") VALUES
  ('0d000001-0000-4000-8000-000000000001', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000001', '0a000001-0000-4000-8000-000000000001', '2026-01-05 08:03:10'),
  ('0d000001-0000-4000-8000-000000000002', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000002', '0a000001-0000-4000-8000-000000000001', '2026-01-05 08:03:20'),
  ('0d000001-0000-4000-8000-000000000003', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000001', '0a000001-0000-4000-8000-000000000002', '2026-01-05 08:04:10'),
  ('0d000001-0000-4000-8000-000000000004', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000002', '0a000001-0000-4000-8000-000000000003', '2026-01-05 08:04:40'),
  ('0d000001-0000-4000-8000-000000000005', '22222222-2222-4222-8222-222222222222', 'b1b1b1b1-0000-4000-8000-000000000001', '0a000001-0000-4000-8000-000000000004', '2026-01-05 08:07:10');

-- ---------------------------------------------------------------------
-- Customers. `cxId` is the Phase-14 reference code; it is unique per tenant
-- on the new schema and globally unique on the baseline, so every value below
-- is distinct globally and the comparison cannot pass by accident.
-- ---------------------------------------------------------------------
INSERT INTO customers ("id", "organizationId", "projectId", "cxId", "displayName", "primaryEmail", "primaryPhone", "locale", "tags", "notes", "externalIds", "metadata", "firstSeenAt", "lastSeenAt", "createdAt", "updatedAt") VALUES
  ('ca000001-0000-4000-8000-000000000001', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000001', '#33000001', 'Shadow Patient One',   'patient.one@shadow.invalid',   '+966500000001', 'ar', ARRAY['implant'], 'synthetic row', '{}', '{}', '2026-01-10 10:00:00', '2026-01-20 12:00:00', '2026-01-10 10:00:00', '2026-01-20 12:00:00'),
  ('ca000001-0000-4000-8000-000000000002', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000001', '#33000002', 'Shadow Patient Two',   'patient.two@shadow.invalid',   '+966500000002', 'ar', ARRAY[]::text[],  NULL,              '{}', '{}', '2026-01-11 10:00:00', '2026-01-21 12:00:00', '2026-01-11 10:00:00', '2026-01-21 12:00:00'),
  ('ca000001-0000-4000-8000-000000000003', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000001', '#33000003', 'Shadow Patient Three', 'patient.three@shadow.invalid', '+966500000003', 'ar', ARRAY['ortho'],  'synthetic row', '{}', '{}', '2026-01-12 10:00:00', '2026-01-22 12:00:00', '2026-01-12 10:00:00', '2026-01-22 12:00:00'),
  ('ca000002-0000-4000-8000-000000000001', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000002', '#33000004', 'Shadow Patient Four',  'patient.four@shadow.invalid',  '+966500000004', 'ar', ARRAY[]::text[],  NULL,              '{}', '{}', '2026-01-13 10:00:00', '2026-01-23 12:00:00', '2026-01-13 10:00:00', '2026-01-23 12:00:00'),
  ('cb000001-0000-4000-8000-000000000001', '22222222-2222-4222-8222-222222222222', 'b1b1b1b1-0000-4000-8000-000000000001', '#33000005', 'Shadow Patient Five',  'patient.five@shadow.invalid',  '+966500000005', 'en', ARRAY[]::text[],  NULL,              '{}', '{}', '2026-01-14 10:00:00', '2026-01-24 12:00:00', '2026-01-14 10:00:00', '2026-01-24 12:00:00'),
    -- PHASE 49. A customer with NO dependent rows, and it exists for a reason.
    --
    -- PN-05 proves that the harness detects a row carried into the wrong tenant. In the Spring
    -- database that corruption is STRUCTURALLY REFUSED for any customer that something references
    -- through a composite tenant foreign key: moving the customer cascades the new organizationId
    -- into its appointments, whose createdById then does not exist in the new organization, and
    -- appointments_created_by_tenant_fkey rejects the whole statement. Every customer the seed
    -- had was referenced, so PN-05 had no applicable target and reported the same undiagnosable
    -- "could not be applied" it always had.
    --
    -- This row gives the perturbation something it can actually move, which preserves the proof:
    -- for an unreferenced customer the ONLY thing that changes is its own organizationId, and that
    -- is exactly the property CHK-4 exists to detect. The stronger case -- a referenced customer
    -- mis-tenanted -- is not merely untested; it is impossible in this schema, and that is
    -- recorded as a Phase 12 evidence item rather than worked around.
    ('ca000003-0000-4000-8000-000000000001', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000001', '#33000006', 'Shadow Patient Six',  'patient.six@shadow.invalid',   '+966500000006', 'ar', ARRAY[]::text[],  NULL,              '{}', '{}', '2026-01-15 10:00:00', '2026-01-25 12:00:00', '2026-01-15 10:00:00', '2026-01-25 12:00:00');

-- ---------------------------------------------------------------------
-- Doctors and procedures (provider ids, preserved by plan.md 6.1)
-- ---------------------------------------------------------------------
INSERT INTO doctors ("id", "organizationId", "projectId", "name", "specialty", "department", "workingDays", "startHour", "endHour", "slotDuration", "isActive", "createdAt", "updatedAt") VALUES
  ('0d000002-0000-4000-8000-000000000001', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000001', 'Shadow Doctor One', 'General',  'Clinical', ARRAY[0,1,2,3,4], '09:00', '17:00', 30, true,  '2026-01-06 09:00:00', '2026-01-06 09:00:00'),
  ('0d000002-0000-4000-8000-000000000002', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000001', 'Shadow Doctor Two', 'Implant', 'Surgery',  ARRAY[0,1,2,3,4], '10:00', '18:00', 45, true,  '2026-01-06 09:05:00', '2026-01-06 09:05:00');

INSERT INTO procedures ("id", "organizationId", "projectId", "name", "specialty", "durationMinutes", "bufferMinutes", "price", "isActive", "createdAt", "updatedAt") VALUES
  ('0f000001-0000-4000-8000-000000000001', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000001', 'Shadow Cleaning',   'General',   30, 0, 200.00, true, '2026-01-06 09:10:00', '2026-01-06 09:10:00'),
  ('0f000001-0000-4000-8000-000000000002', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000001', 'Shadow Extraction', 'Surgery',   45, 0, 400.00, true, '2026-01-06 09:11:00', '2026-01-06 09:11:00'),
  ('0f000001-0000-4000-8000-000000000003', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000001', 'Shadow Whitening',  'Aesthetic', 60, 0, 900.00, true, '2026-01-06 09:12:00', '2026-01-06 09:12:00');

-- ---------------------------------------------------------------------
-- Conversations. Every status value appears inside ONE project so that a
-- status filter has something to select and a status filter that is ignored
-- is visible. ticketNumber is the identifier plan.md 6.1 requires preserved.
--
-- CONV_A1_OPEN is the transition subject: it starts OPEN/HUMAN/unassigned,
-- so a status-sequence comparison has a known starting state on both stacks.
-- ---------------------------------------------------------------------
INSERT INTO conversations ("id", "organizationId", "projectId", "customerId", "ticketNumber", "channel", "status", "mode", "priority", "subject", "assignedEmployeeId", "lastReplierUserId", "modeVersion", "messageCount", "lastMessageAt", "firstResponseAt", "resolvedAt", "closedAt", "createdAt", "updatedAt") VALUES
  ('f0000001-0000-4000-8000-000000000001', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000001', 'ca000001-0000-4000-8000-000000000001', '#T-000001', 'WHATSAPP', 'OPEN',     'HUMAN', 'NORMAL', 'Shadow subject one',   NULL, NULL, 0, 2, '2026-01-20 12:00:00', NULL, NULL, NULL, '2026-01-20 11:00:00', '2026-01-20 12:00:00'),
  ('f0000001-0000-4000-8000-000000000002', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000001', 'ca000001-0000-4000-8000-000000000002', '#T-000002', 'MOCK',     'PENDING',  'HUMAN', 'HIGH',   'Shadow subject two',   NULL, NULL, 0, 1, '2026-01-20 13:00:00', NULL, NULL, NULL, '2026-01-20 13:00:00', '2026-01-20 13:00:00'),
  ('f0000001-0000-4000-8000-000000000003', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000001', 'ca000001-0000-4000-8000-000000000003', '#T-000003', 'MOCK',     'RESOLVED', 'HUMAN', 'NORMAL', 'Shadow subject three', NULL, NULL, 0, 1, '2026-01-20 14:00:00', '2026-01-20 14:30:00', '2026-01-20 14:45:00', NULL, '2026-01-20 14:00:00', '2026-01-20 14:45:00'),
  ('f0000001-0000-4000-8000-000000000004', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000001', 'ca000001-0000-4000-8000-000000000002', '#T-000004', 'MOCK',     'CLOSED',   'HUMAN', 'LOW',    'Shadow subject four',  NULL, NULL, 0, 1, '2026-01-20 15:00:00', '2026-01-20 15:10:00', '2026-01-20 15:20:00', '2026-01-20 15:30:00', '2026-01-20 15:00:00', '2026-01-20 15:30:00'),
  ('f0000001-0000-4000-8000-000000000005', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000001', 'ca000001-0000-4000-8000-000000000001', '#T-000005', 'MOCK',     'OPEN',     'AI',    'URGENT', 'Shadow subject five',  NULL, NULL, 3, 1, '2026-01-20 16:00:00', NULL, NULL, NULL, '2026-01-20 16:00:00', '2026-01-20 16:00:00'),
  ('f0000002-0000-4000-8000-000000000001', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000002', 'ca000002-0000-4000-8000-000000000001', '#T-000006', 'MOCK',     'OPEN',     'HUMAN', 'NORMAL', 'Shadow subject six',   NULL, NULL, 0, 1, '2026-01-20 17:00:00', NULL, NULL, NULL, '2026-01-20 17:00:00', '2026-01-20 17:00:00'),
  ('fb000001-0000-4000-8000-000000000001', '22222222-2222-4222-8222-222222222222', 'b1b1b1b1-0000-4000-8000-000000000001', 'cb000001-0000-4000-8000-000000000001', '#T-000007', 'MOCK',     'OPEN',     'HUMAN', 'NORMAL', 'Shadow subject seven', NULL, NULL, 0, 1, '2026-01-20 18:00:00', NULL, NULL, NULL, '2026-01-20 18:00:00', '2026-01-20 18:00:00'),
  ('fb000001-0000-4000-8000-000000000002', '22222222-2222-4222-8222-222222222222', 'b1b1b1b1-0000-4000-8000-000000000001', 'cb000001-0000-4000-8000-000000000001', '#T-000008', 'MOCK',     'PENDING',  'HUMAN', 'NORMAL', 'Shadow subject eight', NULL, NULL, 0, 1, '2026-01-20 19:00:00', NULL, NULL, NULL, '2026-01-20 19:00:00', '2026-01-20 19:00:00');

-- ---------------------------------------------------------------------
-- Messages. `messageCount` above must equal the number of rows per
-- conversation here, or a parity comparison is measuring the seed, not the
-- code. CONV_A1_OPEN carries the transcript both stacks must agree on.
-- ---------------------------------------------------------------------
INSERT INTO messages ("id", "organizationId", "projectId", "conversationId", "channel", "direction", "authorType", "authorUserId", "body", "contentType", "attachments", "externalMessageId", "deliveryStatus", "metadata", "sentAt", "createdAt") VALUES
  ('a0000001-0000-4000-8000-000000000001', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000001', 'f0000001-0000-4000-8000-000000000001', 'WHATSAPP', 'INBOUND',  'CUSTOMER', NULL, 'shadow inbound one',   'text/plain', '[]', 'ext-shadow-0001', 'DELIVERED', '{}', '2026-01-20 11:30:00', '2026-01-20 11:30:00'),
  ('a0000001-0000-4000-8000-000000000002', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000001', 'f0000001-0000-4000-8000-000000000001', 'WHATSAPP', 'OUTBOUND', 'EMPLOYEE', '0a000001-0000-4000-8000-000000000002', 'shadow outbound one', 'text/plain', '[]', 'ext-shadow-0002', 'DELIVERED', '{}', '2026-01-20 12:00:00', '2026-01-20 12:00:00'),
  ('a0000001-0000-4000-8000-000000000003', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000001', 'f0000001-0000-4000-8000-000000000002', 'MOCK',     'INBOUND',  'CUSTOMER', NULL, 'shadow inbound two',   'text/plain', '[]', 'ext-shadow-0003', 'DELIVERED', '{}', '2026-01-20 13:00:00', '2026-01-20 13:00:00'),
  ('a0000001-0000-4000-8000-000000000004', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000001', 'f0000001-0000-4000-8000-000000000003', 'MOCK',     'INBOUND',  'CUSTOMER', NULL, 'shadow inbound three', 'text/plain', '[]', 'ext-shadow-0004', 'DELIVERED', '{}', '2026-01-20 14:00:00', '2026-01-20 14:00:00'),
  ('a0000001-0000-4000-8000-000000000005', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000001', 'f0000001-0000-4000-8000-000000000004', 'MOCK',     'INBOUND',  'CUSTOMER', NULL, 'shadow inbound four',  'text/plain', '[]', 'ext-shadow-0005', 'DELIVERED', '{}', '2026-01-20 15:00:00', '2026-01-20 15:00:00'),
  ('a0000001-0000-4000-8000-000000000006', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000001', 'f0000001-0000-4000-8000-000000000005', 'MOCK',     'INBOUND',  'CUSTOMER', NULL, 'shadow inbound five',  'text/plain', '[]', 'ext-shadow-0006', 'DELIVERED', '{}', '2026-01-20 16:00:00', '2026-01-20 16:00:00'),
  ('a0000002-0000-4000-8000-000000000001', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000002', 'f0000002-0000-4000-8000-000000000001', 'MOCK',     'INBOUND',  'CUSTOMER', NULL, 'shadow inbound six',   'text/plain', '[]', 'ext-shadow-0007', 'DELIVERED', '{}', '2026-01-20 17:00:00', '2026-01-20 17:00:00'),
  ('ab000001-0000-4000-8000-000000000001', '22222222-2222-4222-8222-222222222222', 'b1b1b1b1-0000-4000-8000-000000000001', 'fb000001-0000-4000-8000-000000000001', 'MOCK',     'INBOUND',  'CUSTOMER', NULL, 'shadow inbound seven', 'text/plain', '[]', 'ext-shadow-0008', 'DELIVERED', '{}', '2026-01-20 18:00:00', '2026-01-20 18:00:00'),
  ('ab000001-0000-4000-8000-000000000002', '22222222-2222-4222-8222-222222222222', 'b1b1b1b1-0000-4000-8000-000000000001', 'fb000001-0000-4000-8000-000000000002', 'MOCK',     'INBOUND',  'CUSTOMER', NULL, 'shadow inbound eight', 'text/plain', '[]', 'ext-shadow-0009', 'DELIVERED', '{}', '2026-01-20 19:00:00', '2026-01-20 19:00:00');

-- ---------------------------------------------------------------------
-- Conversation events (the timeline). One STATUS_CHANGED per seeded status
-- so a timeline read has something ordered to return.
-- ---------------------------------------------------------------------
INSERT INTO conversation_events ("id", "organizationId", "projectId", "conversationId", "type", "actorType", "actorUserId", "payload", "occurredAt") VALUES
  ('ba000001-0000-4000-8000-000000000001', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000001', 'f0000001-0000-4000-8000-000000000001', 'CONVERSATION_CREATED', 'SYSTEM', NULL, '{}', '2026-01-20 11:00:00'),
  ('ba000001-0000-4000-8000-000000000002', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000001', 'f0000001-0000-4000-8000-000000000001', 'MESSAGE_RECEIVED',    'SYSTEM', NULL, '{}', '2026-01-20 11:30:00'),
  ('ba000001-0000-4000-8000-000000000003', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000001', 'f0000001-0000-4000-8000-000000000003', 'CONVERSATION_CREATED', 'SYSTEM', NULL, '{}', '2026-01-20 14:00:00'),
  ('ba000001-0000-4000-8000-000000000031','11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000001', 'f0000001-0000-4000-8000-000000000003', 'STATUS_CHANGED',      'EMPLOYEE', '0a000001-0000-4000-8000-000000000002', '{"from":"OPEN","to":"RESOLVED"}', '2026-01-20 14:45:00'),
  ('ba000001-0000-4000-8000-000000000004', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000001', 'f0000001-0000-4000-8000-000000000004', 'CONVERSATION_CREATED', 'SYSTEM', NULL, '{}', '2026-01-20 15:00:00'),
  ('ba000001-0000-4000-8000-000000000041','11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000001', 'f0000001-0000-4000-8000-000000000004', 'STATUS_CHANGED',      'EMPLOYEE', '0a000001-0000-4000-8000-000000000002', '{"from":"OPEN","to":"CLOSED"}', '2026-01-20 15:30:00'),
  ('ba000001-0000-4000-8000-000000000005', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000001', 'f0000001-0000-4000-8000-000000000005', 'CONVERSATION_CREATED', 'SYSTEM', NULL, '{}', '2026-01-20 16:00:00'),
  ('ba000002-0000-4000-8000-000000000001', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000002', 'f0000002-0000-4000-8000-000000000001', 'CONVERSATION_CREATED', 'SYSTEM', NULL, '{}', '2026-01-20 17:00:00'),
  ('bb000001-0000-4000-8000-000000000001', '22222222-2222-4222-8222-222222222222', 'b1b1b1b1-0000-4000-8000-000000000001', 'fb000001-0000-4000-8000-000000000001', 'CONVERSATION_CREATED', 'SYSTEM', NULL, '{}', '2026-01-20 18:00:00'),
  ('bb000001-0000-4000-8000-000000000002', '22222222-2222-4222-8222-222222222222', 'b1b1b1b1-0000-4000-8000-000000000001', 'fb000001-0000-4000-8000-000000000002', 'CONVERSATION_CREATED', 'SYSTEM', NULL, '{}', '2026-01-20 19:00:00');

-- ---------------------------------------------------------------------
-- Case resolution for the RESOLVED conversation. CSAT/DSAT is computed from
-- these rows, so an analytics comparison has a source to drill to.
-- ---------------------------------------------------------------------
INSERT INTO case_resolutions ("id", "organizationId", "projectId", "conversationId", "customerIssue", "agentAction", "usedKnowledgeBase", "customerAccepted", "customerSatisfied", "escalationRequested", "escalatedToSupervisor", "outcome", "notes", "recordedByUserId", "createdAt", "updatedAt") VALUES
  ('cc000001-0000-4000-8000-000000000001', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000001', 'f0000001-0000-4000-8000-000000000003', 'shadow issue',  'shadow action', false, true, true, false, false, 'RESOLVED',  NULL, '0a000001-0000-4000-8000-000000000002', '2026-01-20 14:45:00', '2026-01-20 14:45:00'),
  ('cc000001-0000-4000-8000-000000000002', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000001', 'f0000001-0000-4000-8000-000000000004', 'shadow issue b','shadow action b',false, true, false, false, false, 'UNRESOLVED', NULL, '0a000001-0000-4000-8000-000000000002', '2026-01-20 15:30:00', '2026-01-20 15:30:00');

-- ---------------------------------------------------------------------
-- Appointments. Status is a free-text column on this table, so a status
-- filter comparison has to be explicit about which values it asks for.
-- ---------------------------------------------------------------------
INSERT INTO appointments ("id", "organizationId", "projectId", "customerId", "doctorId", "procedureId", "title", "patientName", "appointmentDate", "durationMinutes", "status", "service", "doctorName", "isAiProposed", "notes", "ticketNumber", "createdById", "createdAt", "updatedAt") VALUES
  ('aa000001-0000-4000-8000-000000000001', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000001', 'ca000001-0000-4000-8000-000000000001', '0d000002-0000-4000-8000-000000000001', '0f000001-0000-4000-8000-000000000001', 'Shadow visit one',   'Shadow Patient One',   '2026-01-21 09:00:00', 30, 'APPROVED',  'Shadow Cleaning',   'Shadow Doctor One', false, NULL, 'SH-000001', '0a000001-0000-4000-8000-000000000001', '2026-01-18 09:00:00', '2026-01-18 09:00:00'),
  ('aa000001-0000-4000-8000-000000000002', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000001', 'ca000001-0000-4000-8000-000000000002', '0d000002-0000-4000-8000-000000000001', '0f000001-0000-4000-8000-000000000002', 'Shadow visit two',   'Shadow Patient Two',   '2026-01-21 10:00:00', 45, 'PENDING',   'Shadow Extraction', 'Shadow Doctor One', true,  NULL, 'SH-000002', '0a000001-0000-4000-8000-000000000001', '2026-01-18 09:05:00', '2026-01-18 09:05:00'),
  ('aa000001-0000-4000-8000-000000000003', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000001', 'ca000001-0000-4000-8000-000000000003', '0d000002-0000-4000-8000-000000000002', '0f000001-0000-4000-8000-000000000003', 'Shadow visit three', 'Shadow Patient Three', '2026-01-21 11:00:00', 60, 'COMPLETED', 'Shadow Whitening',  'Shadow Doctor Two', false, NULL, 'SH-000003', '0a000001-0000-4000-8000-000000000001', '2026-01-18 09:10:00', '2026-01-18 09:10:00'),
  ('aa000001-0000-4000-8000-000000000004', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000001', 'ca000001-0000-4000-8000-000000000001', '0d000002-0000-4000-8000-000000000001', '0f000001-0000-4000-8000-000000000001', 'Shadow visit four',  'Shadow Patient One',   '2026-01-22 09:00:00', 30, 'CANCELLED', 'Shadow Cleaning',   'Shadow Doctor One', false, NULL, 'SH-000004', '0a000001-0000-4000-8000-000000000001', '2026-01-18 09:15:00', '2026-01-18 09:15:00'),
  ('aa000001-0000-4000-8000-000000000005', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000001', NULL,                              '0d000002-0000-4000-8000-000000000002', '0f000001-0000-4000-8000-000000000001', 'Shadow visit five',  'Shadow Walk In',       '2026-01-22 14:00:00', 30, 'PENDING',   'Shadow Cleaning',   'Shadow Doctor Two', true,  NULL, 'SH-000005', '0a000001-0000-4000-8000-000000000001', '2026-01-18 09:20:00', '2026-01-18 09:20:00');

-- ---------------------------------------------------------------------
-- Notes. The note surface is organization-or-project scoped and both stacks
-- expose it, so it is a legitimate third read comparison.
-- ---------------------------------------------------------------------
INSERT INTO notes ("id", "organizationId", "projectId", "resourceType", "resourceId", "body", "authorUserId", "createdAt", "updatedAt") VALUES
  ('ad000001-0000-4000-8000-000000000001', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000001', 'CUSTOMER', 'ca000001-0000-4000-8000-000000000001', 'shadow note one',   '0a000001-0000-4000-8000-000000000002', '2026-01-20 12:05:00', '2026-01-20 12:05:00'),
  ('ad000001-0000-4000-8000-000000000002', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000001', 'CONVERSATION', 'f0000001-0000-4000-8000-000000000001', 'shadow note two', '0a000001-0000-4000-8000-000000000002', '2026-01-20 12:06:00', '2026-01-20 12:06:00'),
  ('ad000001-0000-4000-8000-000000000003', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000002', 'CUSTOMER', 'ca000002-0000-4000-8000-000000000001', 'shadow note three', '0a000001-0000-4000-8000-000000000003', '2026-01-20 17:05:00', '2026-01-20 17:05:00'),
  ('ad000001-0000-4000-8000-000000000004', '22222222-2222-4222-8222-222222222222', 'b1b1b1b1-0000-4000-8000-000000000001', 'CUSTOMER', 'cb000001-0000-4000-8000-000000000001', 'shadow note four',  '0a000001-0000-4000-8000-000000000004', '2026-01-20 18:05:00', '2026-01-20 18:05:00');

COMMIT;