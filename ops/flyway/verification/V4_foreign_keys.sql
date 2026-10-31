-- =====================================================================
-- V4_foreign_keys.sql
-- Phase 2 / database verification.
--
-- Purpose: prove that the foreign-key graph of the verified schema is intact
-- and that deleting a parent cannot silently orphan tenant-owned data.
--
-- Expected for the Phase-2 baseline: 62 foreign keys and 45 primary keys,
-- every foreign key ON UPDATE CASCADE, and ON DELETE either CASCADE,
-- SET NULL, or RESTRICT. Uniqueness is carried by unique INDEXES rather than
-- unique CONSTRAINTS (Prisma/db-push behaviour), so `unique_constraints`
-- is legitimately 0; V5 counts the unique indexes instead.
--
-- Read-only.
-- =====================================================================
-- Summary assertions (all three must hold).
SELECT count(*) FILTER (WHERE contype = 'f') AS foreign_keys,
       count(*) FILTER (WHERE contype = 'f' AND confupdtype <> 'c') AS not_cascade_on_update,
       count(*) FILTER (WHERE contype = 'f' AND confdeltype NOT IN ('c', 'n', 'r')) AS unsafe_on_delete,
       count(*) FILTER (WHERE contype = 'p') AS primary_keys,
       count(*) FILTER (WHERE contype = 'u') AS unique_constraints,
       count(*) FILTER (WHERE contype = 'c') AS check_constraints
  FROM pg_constraint con
  JOIN pg_class c ON c.oid = con.conrelid
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public';

-- Full inventory.
SELECT c.relname      AS child_table,
       con.conname    AS fk_name,
       CASE con.confdeltype WHEN 'a' THEN 'NO ACTION' WHEN 'r' THEN 'RESTRICT'
                           WHEN 'c' THEN 'CASCADE'  WHEN 'n' THEN 'SET NULL'
                           WHEN 'd' THEN 'SET DEFAULT' ELSE con.confdeltype::text END AS on_delete,
       pg_get_constraintdef(con.oid) AS definition
  FROM pg_constraint con
  JOIN pg_class c ON c.oid = con.conrelid
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public' AND con.contype = 'f'
 ORDER BY c.relname, con.conname;