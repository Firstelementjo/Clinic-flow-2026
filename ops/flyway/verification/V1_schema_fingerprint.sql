-- =====================================================================
-- V1_schema_fingerprint.sql
-- Phase 2 / database verification.
--
-- Purpose: produce ONE deterministic fingerprint of the physical schema.
-- Two databases with the same fingerprint are the same contract. This is the
-- drift detector used before and after every Flyway run, backup, and restore.
--
-- Usage (dev container):
--   docker exec -i firstelement-postgres psql -U firstelement -d firstelement \
--     -t -A -F'|' -f - < ops/flyway/verification/V1_schema_fingerprint.sql
--
-- Read-only. Creates no objects.
-- =====================================================================
WITH objects AS (
    -- extensions
    SELECT 'extension'                        AS kind,
           e.extname                          AS object,
           e.extversion                       AS detail
      FROM pg_extension e
     WHERE e.extname NOT IN ('plpgsql')

    UNION ALL
    -- enums (values in sort order: enum order is part of the contract)
    SELECT 'enum',
           t.typname,
           string_agg(e.enumlabel, ',' ORDER BY e.enumsortorder)
      FROM pg_type t
      JOIN pg_enum e ON e.enumtypid = t.oid
      JOIN pg_namespace n ON n.oid = t.typnamespace
     WHERE n.nspname = 'public'
     GROUP BY t.typname

    UNION ALL
    -- columns: name, ordinal position, type, nullability, default
    SELECT 'column',
           c.relname || '.' || a.attname,
           a.attnum || ' ' || format_type(a.atttypid, a.atttypmod)
             || ' ' || CASE WHEN a.attnotnull THEN 'NOT NULL' ELSE 'NULL' END
             || ' ' || COALESCE(pg_get_expr(d.adbin, d.adrelid), '-')
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
      JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
      LEFT JOIN pg_attrdef d ON d.adrelid = c.oid AND d.adnum = a.attnum
     WHERE n.nspname = 'public' AND c.relkind = 'r'
       -- flyway_schema_history is the migration LOG, not part of the schema contract. Including it
       -- makes the fingerprint change merely because a database was stamped by Flyway rather than
       -- rehearsed through psql, which is not the claim this fingerprint makes -- and it would make
       -- every recorded postBoot figure unreproducible on the real, booted target. Every fingerprint
       -- recorded so far was measured on a database WITHOUT this table, so excluding it leaves them
       -- all valid and makes them reproducible on a genuinely booted database too.
       AND c.relname <> 'flyway_schema_history'

    UNION ALL
    -- constraints (PK / UNIQUE / FK / CHECK)
    SELECT 'constraint',
           con.conname,
           con.contype::text || ' ' || pg_get_constraintdef(con.oid)
      FROM pg_constraint con
      JOIN pg_class c ON c.oid = con.conrelid
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public'
       -- Same exclusion as the column arm: the history table's own PRIMARY KEY is a fact about the
       -- migration log, not about the schema contract this fingerprint claims to compare.
       AND c.relname <> 'flyway_schema_history'

    UNION ALL
    -- indexes (definition, not just the name)
    SELECT 'index',
           i.indexname,
           pg_get_indexdef(idx.indexrelid)
      FROM pg_indexes i
      JOIN pg_class idxc ON idxc.relname = i.indexname
                       AND idxc.relnamespace = (SELECT oid FROM pg_namespace
                                                 WHERE nspname = 'public')
      JOIN pg_index idx ON idx.indexrelid = idxc.oid
     WHERE i.schemaname = 'public'
       -- Same exclusion as the column and constraint arms. The history table's PRIMARY KEY index
       -- appears in pg_indexes, so without this the fingerprint still moved when a database was
       -- stamped -- which is the entire thing this exclusion exists to prevent. Measured: with only
       -- the first two arms excluded, adding flyway_schema_history still moved the fingerprint from
       -- 66eeaadab4ad28a8f8eaf8fe1a0748f9 to 7fae7da269a9fd488a8da175ba7fade1.
       AND i.tablename <> 'flyway_schema_history'
)
SELECT kind,
       object,
       detail,
       md5(kind || '|' || object || '|' || detail) AS object_md5
  FROM objects
 ORDER BY kind, object;
