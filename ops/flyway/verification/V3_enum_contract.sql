-- =====================================================================
-- V3_enum_contract.sql
-- Phase 2 / database verification.
--
-- Purpose: the enum contract. Enum VALUE LISTS are part of the application
-- contract (the legacy NestJS/Prisma code writes these labels), so this is
-- checked before a Flyway baseline and after every restore.
--
-- Drift that was reconciled in Phase 2 and must never reappear:
--   EmployeeAvailabilityStatus
--     wrong (init migration): AVAILABLE, BUSY, AWAY, OFFLINE
--     correct (live + schema.prisma): ONLINE, OFFLINE, BUSY, BREAK, MEAL, TRAINING, MEETING
--   AIRunDecision
--     wrong (init migration): REPLY, ASK_CLARIFICATION, ESCALATE, NO_ACTION
--     correct (live + schema.prisma): REPLY, ASK_CLARIFICATION, ESCALATE, NO_ACTION, RESOLVE
--
-- Expected: 23 enums in the public schema.
-- Read-only.
-- =====================================================================
SELECT count(*) AS enum_count,
       count(*) FILTER (WHERE enum_values = 'ONLINE,OFFLINE,BUSY,BREAK,MEAL,TRAINING,MEETING')
           AS employee_availability_ok,
       count(*) FILTER (WHERE enum_values = 'REPLY,ASK_CLARIFICATION,ESCALATE,NO_ACTION,RESOLVE')
           AS ai_run_decision_ok
  FROM (
        SELECT t.typname,
               string_agg(e.enumlabel, ',' ORDER BY e.enumsortorder) AS enum_values
          FROM pg_type t
          JOIN pg_enum e ON e.enumtypid = t.oid
          JOIN pg_namespace n ON n.oid = t.typnamespace
         WHERE n.nspname = 'public'
         GROUP BY t.typname
       ) e;

-- Full listing for the drift report / restore evidence.
SELECT t.typname AS enum_name,
       e.enumsortorder AS ord,
       e.enumlabel      AS enum_value
  FROM pg_type t
  JOIN pg_enum e ON e.enumtypid = t.oid
  JOIN pg_namespace n ON n.oid = t.typnamespace
 WHERE n.nspname = 'public'
 ORDER BY t.typname, e.enumsortorder;