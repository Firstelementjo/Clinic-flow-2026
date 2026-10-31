-- =====================================================================
-- F-cross-tenant-access.sql
--
-- FORENSIC QUERY. READ ONLY. NEVER WRITES.
--
-- Question: was the legacy cross-tenant clinic breach exploited?
--
-- Every row this query emits has the same shape, so a caller cannot
-- accidentally read a column that was not designed for the report:
--
--   section        'FINDING' | 'COVERAGE'
--   check_id       FE-FOR-0NN
--   severity       CRITICAL | HIGH | MEDIUM | INFO
--   organization_a uuid  -- the ACTOR's tenant, or the row's own tenant
--   organization_b uuid  -- the TARGET's tenant; equal to a when no mismatch
--   resource_id    uuid  -- the row in question; never a name, never a phone
--   occurred_at    the timestamp that dates the finding
--   note           a FIXED vocabulary token. Never row data.
--
-- PATIENT DATA: this file selects no patient name, phone, email, note,
-- title, service, doctor name or audit metadata. The runner refuses to
-- execute if any of those column names appear here; that check is in
-- Invoke-ForensicScan.ps1 (rule FE-FOR-REDACT-001), not in a comment.
--
-- `audit_logs.metadata` in particular is NEVER selected. The clinic
-- service writes `patientPhone` and `doctorName` into it on
-- REMINDER_SENT, so selecting metadata -- even to filter it -- would
-- pull patient contact details into a forensic artefact.
--
-- SESSION READ-ONLY: the runner sets PGOPTIONS
-- `-c default_transaction_read_only=on` for the whole psql session, so
-- this file cannot write even if it were edited to try. Every statement
-- below is additionally inside one explicit BEGIN READ ONLY.
--
-- `:'maxids'` is supplied by the runner (-v maxids=N, default 50) and
-- caps how many resource ids a single finding lists, so a large breach
-- cannot produce an unbounded report.
-- =====================================================================

BEGIN READ ONLY;

-- ---------------------------------------------------------------------
-- SECTION 1 -- FINDINGS
--
-- Eight signals the schema actually supports. None of them is "the log
-- says somebody logged in"; each is a comparison between two records
-- that should agree and do not.
-- ---------------------------------------------------------------------

-- FE-FOR-001  The named lead. An appointment lifecycle audit row records
-- the ACTOR's organizationId against the TARGET's appointment id. If the
-- two organizations differ, a tenant acted on another tenant's
-- appointment. Covers exactly four actions: APPOINTMENT_CREATED,
-- APPOINTMENT_RESCHEDULED, APPOINTMENT_CANCELLED, REMINDER_SENT.
-- Needs the appointment row to still exist; excludes deletes.
SELECT 'FINDING' AS section,
       'FE-FOR-001' AS check_id,
       'CRITICAL' AS severity,
       l."organizationId" AS organization_a,
       a."organizationId" AS organization_b,
       a.id AS resource_id,
       l."createdAt" AS occurred_at,
       'AUDIT_ACTOR_ORG_DIFFERS_FROM_APPOINTMENT_ORG:' || l.action AS note
FROM audit_logs l
JOIN appointments a ON a.id::text = l."resourceId"
WHERE l.action IN ('APPOINTMENT_CREATED', 'APPOINTMENT_RESCHEDULED',
                   'APPOINTMENT_CANCELLED', 'REMINDER_SENT')
  AND l."organizationId" <> a."organizationId"
LIMIT :'maxids';

-- FE-FOR-002  Same join, one level down: the audit row's projectId
-- against the appointment's projectId. Catches a cross-PROJECT breach
-- inside a single tenant, which FE-FOR-001 is blind to by construction.
SELECT 'FINDING' AS section,
       'FE-FOR-002' AS check_id,
       'HIGH' AS severity,
       l."organizationId" AS organization_a,
       a."organizationId" AS organization_b,
       a.id AS resource_id,
       l."createdAt" AS occurred_at,
       'AUDIT_ACTOR_PROJECT_DIFFERS_FROM_APPOINTMENT_PROJECT:' || l.action AS note
FROM audit_logs l
JOIN appointments a ON a.id::text = l."resourceId"
WHERE l.action IN ('APPOINTMENT_CREATED', 'APPOINTMENT_RESCHEDULED',
                   'APPOINTMENT_CANCELLED', 'REMINDER_SENT')
  AND l."projectId" IS DISTINCT FROM a."projectId"
LIMIT :'maxids';

-- FE-FOR-003  An audited appointment lifecycle action whose target row
-- is gone. The appointment lifecycle actions ARE audited, so a missing
-- target means the row was removed after the fact -- and deletes write
-- no audit row of their own, so this is the only delete signal the
-- schema supports. It bounds deletion to appointments that were at some
-- point created through an audited path; a doctor or procedure deletion
-- leaves nothing at all to find.
SELECT 'FINDING' AS section,
       'FE-FOR-003' AS check_id,
       'HIGH' AS severity,
       l."organizationId" AS organization_a,
       NULL::uuid AS organization_b,
       l.id AS resource_id,
       l."createdAt" AS occurred_at,
       'AUDITED_APPOINTMENT_TARGET_NO_LONGER_EXISTS:' || l.action AS note
FROM audit_logs l
WHERE l.action IN ('APPOINTMENT_CREATED', 'APPOINTMENT_RESCHEDULED',
                   'APPOINTMENT_CANCELLED', 'REMINDER_SENT')
  AND l."resourceId" ~ ('^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}'
                        || '-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$')
  AND NOT EXISTS (SELECT 1 FROM appointments a WHERE a.id::text = l."resourceId")
LIMIT :'maxids';

-- FE-FOR-004  The strongest signal available, because it is not limited
-- to the clinic module: for ANY audit action, the organization the row
-- was written under must equal the organization of the user who wrote
-- it. Disagreement means an actor operated under a tenant they do not
-- belong to. False positive shape: a user legitimately moved between
-- tenants. Every hit needs adjudication against the HR record.
SELECT 'FINDING' AS section,
       'FE-FOR-004' AS check_id,
       'CRITICAL' AS severity,
       l."organizationId" AS organization_a,
       u."organizationId" AS organization_b,
       l.id AS resource_id,
       l."createdAt" AS occurred_at,
       'AUDIT_ACTOR_ORG_DIFFERS_FROM_ACTOR_USER_ORG:' || l.action AS note
FROM audit_logs l
JOIN users u ON u.id = l."actorUserId"
WHERE l."organizationId" <> u."organizationId"
LIMIT :'maxids';

-- FE-FOR-005  Persistent residue of a cross-tenant WRITE, independent of
-- any audit row. An appointment whose doctor, procedure, customer or
-- project belongs to a different organization than the appointment
-- itself. A cross-tenant create or reassignment leaves this behind
-- forever, so it is detectable for the write paths that write no audit
-- row at all -- and equally, a clean result here says nothing about a
-- write that was later reverted.
SELECT 'FINDING' AS section,
       'FE-FOR-005' AS check_id,
       'CRITICAL' AS severity,
       a."organizationId" AS organization_a,
       ref."organizationId" AS organization_b,
       a.id AS resource_id,
       a."updatedAt" AS occurred_at,
       'APPOINTMENT_REFERENCES_' || ref.kind || '_IN_OTHER_ORG' AS note
FROM appointments a
CROSS JOIN LATERAL (
    SELECT 'DOCTOR' AS kind, d."organizationId" AS "organizationId"
    FROM doctors d WHERE d.id = a."doctorId"
    UNION ALL
    SELECT 'PROCEDURE', p."organizationId"
    FROM procedures p WHERE p.id = a."procedureId"
    UNION ALL
    SELECT 'CUSTOMER', c."organizationId"
    FROM customers c WHERE c.id = a."customerId"
    UNION ALL
    SELECT 'PROJECT', pr."organizationId"
    FROM projects pr WHERE pr.id = a."projectId"
) ref
WHERE ref."organizationId" IS DISTINCT FROM a."organizationId"
LIMIT :'maxids';

-- FE-FOR-006  The signature the incident description names directly:
-- "a doctor could be moved into another tenant". A doctors or
-- procedures row whose own organizationId disagrees with the
-- organization of the project it belongs to. Also catches a create
-- that spread a request body over the tenant column, and a tasks row
-- written the same way.
SELECT 'FINDING' AS section,
       'FE-FOR-006' AS check_id,
       'CRITICAL' AS severity,
       t."organizationId" AS organization_a,
       p."organizationId" AS organization_b,
       t.id AS resource_id,
       t."updatedAt" AS occurred_at,
       'ROSTER_OR_TASK_ROW_ORG_DIFFERS_FROM_PROJECT_ORG:' || t.kind AS note
FROM (
    SELECT 'DOCTOR' AS kind, d.id, d."organizationId", d."projectId", d."updatedAt" FROM doctors d
    UNION ALL
    SELECT 'PROCEDURE', pr.id, pr."organizationId", pr."projectId", pr."updatedAt" FROM procedures pr
    UNION ALL
    SELECT 'TASK', tk.id, tk."organizationId", tk."projectId", tk."updatedAt" FROM tasks tk
) t
JOIN projects p ON p.id = t."projectId"
WHERE p."organizationId" IS DISTINCT FROM t."organizationId"
LIMIT :'maxids';

-- FE-FOR-007  An appointment whose updatedAt is later than the newest
-- audit row naming it. The three lifecycle actions always write their
-- audit row AFTER the row change, so a legitimate audited write leaves
-- updatedAt EARLIER than its audit row. updatedAt later means
-- something moved the row without auditing -- the patient-response
-- status change does exactly this. Reported as a MEDIUM triage count,
-- not as evidence of attack: reminders and batch jobs also move rows.
SELECT 'FINDING' AS section,
       'FE-FOR-007' AS check_id,
       'MEDIUM' AS severity,
       a."organizationId" AS organization_a,
       NULL::uuid AS organization_b,
       a.id AS resource_id,
       a."updatedAt" AS occurred_at,
       'APPOINTMENT_MODIFIED_AFTER_LAST_AUDIT_ROW' AS note
FROM appointments a
WHERE a."updatedAt" > COALESCE(
        (SELECT max(l."createdAt") FROM audit_logs l WHERE l."resourceId" = a.id::text),
        a."createdAt")
LIMIT :'maxids';

-- FE-FOR-008  The breach needed only "a valid session ... with no
-- permission, membership or project access". For every audited action
-- carrying a projectId, the actor should hold a membership in that
-- project. Absence is the shape the breach would leave -- for the four
-- clinic actions AND for every other audited write in the system.
-- False positive shape: membership granted after the fact, or an
-- organization-wide administrator. Needs adjudication either way.
SELECT 'FINDING' AS section,
       'FE-FOR-008' AS check_id,
       'HIGH' AS severity,
       l."organizationId" AS organization_a,
       p."organizationId" AS organization_b,
       l.id AS resource_id,
       l."createdAt" AS occurred_at,
       'AUDIT_ACTOR_HAS_NO_MEMBERSHIP_IN_AUDITED_PROJECT:' || l.action AS note
FROM audit_logs l
JOIN projects p ON p.id = l."projectId"
WHERE NOT EXISTS (
        SELECT 1 FROM project_memberships m
        WHERE m."userId" = l."actorUserId" AND m."projectId" = l."projectId")
LIMIT :'maxids';

-- ---------------------------------------------------------------------
-- SECTION 2 -- COVERAGE
--
-- This section exists so that "zero findings" is interpretable.
--
-- A clean FE-FOR-001 on a database holding no appointment audit rows is
-- not evidence of anything: there was nothing to find. The runner
-- refuses to report CLEAN on a zero-coverage answer, and this section
-- is the input to that decision.
-- ---------------------------------------------------------------------

-- The complete inventory of what IS audited. A reader can see the
-- entire audit surface of the database in one screen, and can see that
-- DOCTOR and PROCEDURE actions are simply absent from it.
SELECT 'COVERAGE' AS section,
       'FE-FOR-C01' AS check_id,
       'INFO' AS severity,
       NULL::uuid AS organization_a,
       NULL::uuid AS organization_b,
       NULL::uuid AS resource_id,
       NULL::timestamp AS occurred_at,
       'AUDITED_ACTION_COUNT:' || l.action || '=' || count(*)::text AS note
FROM audit_logs l
GROUP BY l.action
ORDER BY count(*) DESC, l.action;

-- Row counts of the clinical tables the breach touched, and the count
-- of appointments that carry ANY audit row naming them. The ratio is
-- the detection coverage of the named lead.
SELECT 'COVERAGE' AS section,
       'FE-FOR-C02' AS check_id,
       'INFO' AS severity,
       NULL::uuid AS organization_a,
       NULL::uuid AS organization_b,
       NULL::uuid AS resource_id,
       NULL::timestamp AS occurred_at,
       'TABLE_ROW_COUNT:' || t.kind || '=' || t.cnt::text AS note
FROM (
    SELECT 'APPOINTMENTS' AS kind, count(*) AS cnt FROM appointments
    UNION ALL SELECT 'DOCTORS', count(*) FROM doctors
    UNION ALL SELECT 'PROCEDURES', count(*) FROM procedures
    UNION ALL SELECT 'CUSTOMERS', count(*) FROM customers
    UNION ALL SELECT 'TASKS', count(*) FROM tasks
    UNION ALL SELECT 'AUDIT_LOGS', count(*) FROM audit_logs
    UNION ALL SELECT 'ORGANIZATIONS', count(*) FROM organizations
    UNION ALL SELECT 'PROJECTS', count(*) FROM projects
    UNION ALL SELECT 'USERS', count(*) FROM users
) t
ORDER BY t.kind;

SELECT 'COVERAGE' AS section,
       'FE-FOR-C03' AS check_id,
       'INFO' AS severity,
       NULL::uuid AS organization_a,
       NULL::uuid AS organization_b,
       NULL::uuid AS resource_id,
       NULL::timestamp AS occurred_at,
       'APPOINTMENTS_WITH_NO_AUDIT_ROW_AT_ALL=' || count(*)::text AS note
FROM appointments a
WHERE NOT EXISTS (SELECT 1 FROM audit_logs l WHERE l."resourceId" = a.id::text);

-- Explicit statement of the roster blind spot, derived from the data
-- rather than asserted from a code reading: are doctor or procedure
-- lifecycle actions audited anywhere in this database?
SELECT 'COVERAGE' AS section,
       'FE-FOR-C04' AS check_id,
       'INFO' AS severity,
       NULL::uuid AS organization_a,
       NULL::uuid AS organization_b,
       NULL::uuid AS resource_id,
       NULL::timestamp AS occurred_at,
       'ROSTER_AUDIT_ACTION_ROWS=' || count(*)::text AS note
FROM audit_logs l
WHERE l.action ILIKE '%DOCTOR%'
   OR l.action ILIKE '%PROCEDURE%'
   OR l."resourceType" ILIKE '%DOCTOR%'
   OR l."resourceType" ILIKE '%PROCEDURE%';

-- The window in which the audit table can speak at all, and the window
-- in which the clinical rows were touched. A breach that predates the
-- first audit row, or falls in a gap, is outside what this query can see.
SELECT 'COVERAGE' AS section,
       'FE-FOR-C05' AS check_id,
       'INFO' AS severity,
       NULL::uuid AS organization_a,
       NULL::uuid AS organization_b,
       NULL::uuid AS resource_id,
       NULL::timestamp AS occurred_at,
       'AUDIT_WINDOW=' || COALESCE(min(l."createdAt")::text, 'NONE')
         || '..' || COALESCE(max(l."createdAt")::text, 'NONE') AS note
FROM audit_logs l;

SELECT 'COVERAGE' AS section,
       'FE-FOR-C06' AS check_id,
       'INFO' AS severity,
       NULL::uuid AS organization_a,
       NULL::uuid AS organization_b,
       NULL::uuid AS resource_id,
       NULL::timestamp AS occurred_at,
       'CLINICAL_ROW_WINDOW=appointments:'
         || COALESCE((SELECT min(a2."createdAt")::text FROM appointments a2), 'NONE') || '..'
         || COALESCE((SELECT max(a3."updatedAt")::text FROM appointments a3), 'NONE')
         || '|doctors:' || COALESCE((SELECT min(d."createdAt")::text FROM doctors d), 'NONE')
         || '..' || COALESCE((SELECT max(d."updatedAt")::text FROM doctors d), 'NONE')
         || '|procedures:' || COALESCE((SELECT min(pp."createdAt")::text FROM procedures pp), 'NONE')
         || '..' || COALESCE((SELECT max(pp."updatedAt")::text FROM procedures pp), 'NONE') AS note;

-- The scanner asserts this reports 'on' and refuses to produce a report
-- otherwise, so a scanner launched WITHOUT the read-only guard fails
-- loudly instead of quietly running with full write access.
--
-- default_transaction_read_only is checked rather than
-- transaction_read_only deliberately: the latter reads 'on' inside the
-- explicit BEGIN READ ONLY below however the session was launched, so
-- asserting on it would prove nothing about the guard that matters.
SELECT 'COVERAGE' AS section,
       'FE-FOR-C07' AS check_id,
       'INFO' AS severity,
       NULL::uuid AS organization_a,
       NULL::uuid AS organization_b,
       NULL::uuid AS resource_id,
       NULL::timestamp AS occurred_at,
       'SESSION_DEFAULT_READ_ONLY=' || current_setting('default_transaction_read_only') AS note;

COMMIT;