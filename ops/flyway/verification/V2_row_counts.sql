-- =====================================================================
-- V2_row_counts.sql
-- Phase 2 / database verification.
--
-- Purpose: row counts for every application table plus a single checksum.
-- Row counts are the pre/post migration and pre/post restore evidence
-- required before any DDL runs (AGENTS.md: no destructive DDL without row
-- counts, checksums, and a recovery path).
--
-- A table that legitimately holds no customer data still appears with 0, so
-- a missing table can never be mistaken for an empty one.
--
-- Read-only.
-- =====================================================================
SELECT jsonb_object_agg(tbl, rows) AS row_counts,
       (SELECT sum(rows) FROM (
            SELECT (xpath('/row/c/text()',
                    query_to_xml(format('SELECT count(*) AS c FROM %I.%I', n.nspname, c2.relname),
                                 false, true, '')))[1]::text::bigint AS rows
              FROM pg_class c2
              JOIN pg_namespace n ON n.oid = c2.relnamespace
             WHERE n.nspname = 'public' AND c2.relkind = 'r') s) AS total_rows,
       md5(coalesce(jsonb_object_agg(tbl, rows)::text, '')) AS row_counts_md5
  FROM (
        SELECT quote_ident(c.relname) AS tbl,
               (xpath('/row/c/text()',
                 query_to_xml(format('SELECT count(*) AS c FROM %I.%I', n.nspname, c.relname),
                              false, true, '')))[1]::text::bigint AS rows
          FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public' AND c.relkind = 'r'
       ) s;