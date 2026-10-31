-- =====================================================================
-- V7_tenant_scope.sql
-- Phase 2 / database verification.
--
-- Purpose: tenant and project scope audit (FR-1.2 / FR-1.3).
--
-- Findings recorded in docs/migration/schema-drift-report.md:
--   * 45 application tables.
--   * 3 tables carry organizationId but no projectId because they are
--     organization-scoped by design: organizations (the tenant root itself),
--     users, sessions. Plus employees / employee_skills / employee_availability
--     / agent_status_sessions / projects, whose scope is derived through the
--     employee or project row rather than duplicated.
--   * 4 tables carry neither column because they are CHILD rows of an
--     already-scoped parent: ai_run_steps, ai_run_retrievals,
--     ai_run_tool_calls (child of ai_runs). Their scope is inherited.
--   * audit_logs.projectId and notifications.projectId are NULLABLE: audit
--     and notification events can be organization-wide. They are backfilled
--     and tightened by the resumable backfill, not by the baseline.
--
-- The important invariant: NO table may reference a row from another
-- organization through a foreign key. V8 proves that with data, not metadata.
--
-- Read-only.
-- =====================================================================
-- 1. Scope classification of every table.
SELECT c.relname AS table_name,
       EXISTS (SELECT 1 FROM pg_attribute a
                WHERE a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
                  AND a.attname = 'organizationId')  AS has_organization_id,
       EXISTS (SELECT 1 FROM pg_attribute a
                WHERE a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
                  AND a.attname = 'projectId')       AS has_project_id,
       CASE
         WHEN c.relname IN ('organizations') THEN 'tenant root'
         WHEN c.relname IN ('users', 'sessions') THEN 'organization-scoped principal'
         WHEN c.relname IN ('projects') THEN 'project root (organization-scoped)'
         WHEN c.relname IN ('employees', 'employee_skills', 'employee_availability',
                            'agent_status_sessions') THEN 'employee-scoped (scope via employees.organizationId)'
         WHEN c.relname IN ('ai_runs', 'ai_run_steps', 'ai_run_retrievals',
                            'ai_run_tool_calls') THEN 'run-scoped child (scope via ai_runs)'
         ELSE 'explicit organization + project scope'
       END AS scope_model
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public' AND c.relkind = 'r'
 ORDER BY scope_model, c.relname;

-- 2. Nullable tenant columns (must be an explicit, reviewed list).
SELECT c.relname AS table_name,
       a.attname AS column_name
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
  JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
 WHERE n.nspname = 'public'
   AND c.relkind = 'r'
   AND a.attname IN ('organizationId', 'projectId')
   AND NOT a.attnotnull
 ORDER BY c.relname, a.attname;

-- 3. Every index that leads with organizationId/projectId. FR-1.3 requires
--    that indexes are led by the tenant filters.
SELECT tablename, indexname
  FROM pg_indexes
 WHERE schemaname = 'public'
   AND (indexdef ~ '\(\s*"?organizationId"?' OR indexdef ~ '\(\s*"?projectId"?')
 ORDER BY tablename, indexname;