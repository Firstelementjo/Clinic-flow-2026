-- =====================================================================
-- B2_scope_audit_report.sql
-- Phase 2 / READ-ONLY scope audit.
--
-- NOT a backfill: this script mutates nothing. It classifies every row whose
-- tenant/project scope is NULL so an operator can decide, per class, whether
-- the NULL is a data gap or a correct semantic scope. Fabricating scope to
-- make a report look clean would be worse than leaving the NULL.
--
-- Findings on the verified dev database (2026-10-04):
--   audit_logs     43 rows, 43 with NULL projectId.
--                 All 43 are resourceType in ('User','Session') with action
--                 USER_LOGGED_IN / USER_LOGGED_OUT. These are ORGANIZATION-wide
--                 security events by nature: a login belongs to the
--                 organization, not to a project. Filling projectId here would
--                 be fabricated data.
--                 => No backfill. Only the eventual security_events stream
--                    (pending V...0008) makes this explicit.
--   notifications   0 rows. Nothing to do yet; the derivation is still
--                 specified below so the script stays useful once data exists.
--
-- Usage:
--   docker exec -i firstelement-postgres psql -U firstelement -d firstelement \
--     -v ON_ERROR_STOP=1 -f - < ops/flyway/backfill/B2_scope_audit_report.sql
-- =====================================================================

\echo '--- audit_logs with NULL projectId, grouped by semantic class ---'
SELECT "resourceType",
       "action",
       CASE
         WHEN "resourceType" IN ('User', 'Session')                       THEN 'organization-wide (correct)'
         WHEN "resourceType" = 'Project'                                 THEN 'backfillable: resourceId IS the projectId'
         WHEN "resourceType" IN ('Conversation', 'Message', 'Note', 'Task',
                                 'Customer', 'Escalation', 'KnowledgeBase',
                                 'Document', 'View', 'RoutingRule',
                                 'EscalationRule', 'CaseResolution')     THEN 'backfillable: derive from resourceId'
         ELSE 'unclassified -- review manually'
       END AS classification,
       count(*) AS rows
  FROM "audit_logs"
 WHERE "projectId" IS NULL
 GROUP BY 1, 2, 3
 ORDER BY rows DESC, 1;

\echo ''
\echo '--- notifications with NULL projectId, by type ---'
SELECT "type",
       "resourceType",
       CASE
         WHEN "resourceType" = 'Conversation'    THEN 'backfillable: conversations.projectId'
         WHEN "resourceType" = 'Message'         THEN 'backfillable: messages.projectId'
         WHEN "resourceType" = 'Appointment'     THEN 'backfillable: appointments.projectId'
         ELSE 'organization-wide (correct)'
       END AS classification,
       count(*) AS rows
  FROM "notifications"
 WHERE "projectId" IS NULL
 GROUP BY 1, 2, 3
 ORDER BY rows DESC, 1;

\echo ''
\echo '--- reference derivation (preview only, NOT applied) ---'
-- If and when a backfill is approved for the project-scoped classes above,
-- these are the derivation keys. Previewed so the operator can judge coverage.
SELECT "resourceType",
       count(*) AS rows_with_null_project,
       count(*) FILTER (
         WHERE ("resourceType" = 'Conversation' AND EXISTS
                (SELECT 1 FROM conversations c WHERE c."id"::text = a."resourceId"))
            OR ("resourceType" = 'Message' AND EXISTS
                (SELECT 1 FROM messages m WHERE m."id"::text = a."resourceId"))
            OR ("resourceType" = 'Customer' AND EXISTS
                (SELECT 1 FROM customers cu WHERE cu."id"::text = a."resourceId"))
       ) AS derivable_now
  FROM "audit_logs" a
 WHERE a."projectId" IS NULL
   AND a."resourceType" IN ('Conversation', 'Message', 'Customer', 'Note', 'Task')
 GROUP BY 1
 ORDER BY 1;

\echo ''
\echo '--- unresolved raw identifier columns (debt, NOT silently fixed) ---'
-- deals.customer is a uuid with no FK. tasks.customer/lead/deal/assignedTo are
-- free text. These are application-level decisions; no DDL in this phase.
SELECT
    (SELECT count(*) FROM deals d
      WHERE d."customer" IS NOT NULL
        AND NOT EXISTS (SELECT 1 FROM customers c WHERE c."id" = d."customer")
    ) AS deals_customer_unresolvable,
    (SELECT count(*) FROM deals WHERE "customer" IS NOT NULL) AS deals_customer_total,
    (SELECT count(*) FROM tasks WHERE "customer" ~ '^[0-9a-fA-F-]{36}$') AS tasks_customer_looks_like_uuid,
    (SELECT count(*) FROM tasks WHERE "assignedTo" IS NOT NULL) AS tasks_assigned_to_total,
    (SELECT count(*) FROM tasks
      WHERE "assignedTo" ~ '^[0-9a-fA-F-]{36}$'
        AND NOT EXISTS (SELECT 1 FROM users u WHERE u."id"::text = tasks."assignedTo")
    ) AS tasks_assigned_to_unresolvable;