-- =====================================================================
-- V6_nullability_and_timestamps.sql
-- Phase 2 / database verification.
--
-- Purpose: guard the two properties the strangler cutover depends on.
--
--  1. NULLABILITY. Every tenant-owned table has NOT NULL organizationId
--     (FR-1.2). A NOT NULL that silently becomes NULL is a tenant-isolation
--     regression, so this lists every nullable tenant column explicitly.
--
--  2. TIMESTAMPS. All physical timestamps are `timestamp(3) without time
--     zone` -- Prisma DateTime. They are NOT `timestamptz`. This is recorded
--     drift-by-design for the target contract: Spring/JDBC must bind
--     LocalDateTime (never Instant/OffsetDateTime) and the application must
--     apply the tenant timezone itself. A `timestamptz` appearing here means
--     somebody "fixed" the column without a migration, which would silently
--     shift stored values.
--
--     Mutable tables must also carry NOT NULL createdAt/updatedAt with
--     createdAt defaulting to CURRENT_TIMESTAMP.
--
-- Read-only.
-- =====================================================================
-- 1. Tenant columns that are still nullable (audit_logs.projectId and
--    notifications.projectId are expected; see the drift report).
SELECT c.relname AS table_name,
       a.attname AS column_name,
       format_type(a.atttypid, a.atttypmod) AS data_type
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
  JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
 WHERE n.nspname = 'public'
   AND c.relkind = 'r'
   AND a.attname IN ('organizationId', 'projectId')
   AND NOT a.attnotnull
 ORDER BY c.relname, a.attname;

-- 2. Timestamp type census. Expected: every row is
--    'timestamp without time zone (Prisma DateTime) -- EXPECTED'.
SELECT CASE
           WHEN a.atttypid = 'timestamp'::regtype
             THEN 'timestamp without time zone (Prisma DateTime) -- EXPECTED'
           WHEN a.atttypid = 'timestamptz'::regtype
             THEN 'timestamp with time zone -- DRIFT, never introduce silently'
           ELSE format_type(a.atttypid, a.atttypmod) || ' -- DRIFT'
       END AS timestamp_contract,
       count(*) AS column_count
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
  JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
 WHERE n.nspname = 'public'
   AND c.relkind = 'r'
   AND a.attname IN ('createdAt', 'updatedAt', 'occurredAt', 'sentAt', 'startedAt',
                     'finishedAt', 'classifiedAt', 'assignedAt', 'resolvedAt',
                     'closedAt', 'expiresAt', 'lastSeenAt', 'lastMessageAt',
                     'firstResponseAt', 'processedAt', 'endedAt', 'statusChangedAt',
                     'appointmentDate', 'dueDate', 'followUpDate', 'ticketDate',
                     'expectedClose', 'lastInboundAt', 'lastOutboundAt', 'readAt',
                     'completedAt', 'lastLoginAt', 'revokedAt')
   AND (a.atttypid = 'timestamp'::regtype OR a.atttypid = 'timestamptz'::regtype)
 GROUP BY 1
 ORDER BY 1;

-- 3. Mutable tables that must carry createdAt/updatedAt.
SELECT c.relname AS table_without_timestamps
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public'
   AND c.relkind = 'r'
   AND NOT EXISTS (SELECT 1 FROM pg_attribute a
                    WHERE a.attrelid = c.oid AND a.attnum > 0
                      AND NOT a.attisdropped AND a.attname = 'createdAt')
 ORDER BY 1;