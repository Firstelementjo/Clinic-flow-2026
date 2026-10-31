-- =====================================================================
-- V8_reference_integrity.sql
-- Phase 2 / database verification.
--
-- Purpose: prove with DATA (not metadata) that
--   (a) no foreign-key orphan exists anywhere in the tenant scope, and
--   (b) no row references an entity belonging to a different organization.
-- (b) is the single most important tenant-isolation assertion available at
-- the database layer: application policy is the real boundary, but a
-- cross-organization FK row would mean the data itself is already crossed.
--
-- Known modelled-but-unenforced references (deliberate debt, NOT drift).
-- These columns hold identifiers without a foreign key and must be validated
-- by the application:
--   deals.customer            uuid   -> customers.id
--   tasks.customer / lead / deal / assignedTo   text, NOT uuid, free text
-- They are listed in docs/migration/schema-drift-report.md as Agent D / Agent B
-- follow-ups. Nothing in this file may "fix" them with DDL in this phase.
--
-- Read-only. Every section returns a single row whose *_violations column
-- must be 0.
-- =====================================================================
-- 1. Orphaned FK references, discovered dynamically so a new table cannot
--    escape the check.
DO $$
DECLARE
    r          record;
    violations bigint := 0;
    detail     text   := '';
BEGIN
    FOR r IN
        SELECT n.nspname, cl.relname, con.conname, att2.attname AS fk_column,
               cl2.relname AS parent_table, att.attname AS pk_column
          FROM pg_constraint con
          JOIN pg_class cl       ON cl.oid = con.conrelid
          JOIN pg_namespace n    ON n.oid = cl.relnamespace
          JOIN pg_class cl2      ON cl2.oid = con.confrelid
          JOIN pg_attribute att2 ON att2.attrelid = cl.oid AND att2.attnum = con.conkey[1]
          JOIN pg_attribute att  ON att.attrelid = cl2.oid AND att.attnum = con.confkey[1]
         WHERE con.contype = 'f'
           AND n.nspname = 'public'
           AND array_length(con.conkey, 1) = 1
           AND array_length(con.confkey, 1) = 1
         ORDER BY cl.relname, con.conname
    LOOP
        EXECUTE format(
            'SELECT count(*) FROM %I.%I c
              WHERE c.%I IS NOT NULL
                AND NOT EXISTS (SELECT 1 FROM %I.%I p WHERE p.%I = c.%I)',
            r.nspname, r.relname, r.fk_column, r.nspname, r.parent_table, r.pk_column, r.fk_column)
        INTO violations;

        IF violations > 0 THEN
            detail := detail || format(E'\n  %I.%I -> %I: %s orphan(s)',
                                       r.relname, r.fk_column, r.parent_table, violations);
        END IF;
    END LOOP;

    RAISE NOTICE 'FK orphan violations:%  ->% ', violations, detail;
END
$$;

-- 2. Cross-organization references on the tenant root.
SELECT
    (SELECT count(*) FROM conversations c
       JOIN projects p ON p.id = c."projectId"
      WHERE p."organizationId" <> c."organizationId")                       AS conversations_cross_org,
    (SELECT count(*) FROM project_memberships m
       JOIN projects p ON p.id = m."projectId"
      WHERE p."organizationId" <> m."organizationId")                      AS memberships_cross_org,
    (SELECT count(*) FROM customers cu
       JOIN projects p ON p.id = cu."projectId"
      WHERE p."organizationId" <> cu."organizationId")                     AS customers_cross_org,
    (SELECT count(*) FROM messages m
       JOIN conversations c ON c.id = m."conversationId"
      WHERE c."organizationId" <> m."organizationId"
         OR c."projectId" <> m."projectId")                               AS messages_cross_scope,
    (SELECT count(*) FROM document_chunks dc
       JOIN documents d ON d.id = dc."documentId"
      WHERE d."organizationId" <> dc."organizationId"
         OR d."projectId" <> dc."projectId"
         OR d."knowledgeBaseId" <> dc."knowledgeBaseId")                   AS chunks_cross_scope,
    (SELECT count(*) FROM ai_usage u
       JOIN ai_runs r ON r.id = u."runId"
      WHERE r."organizationId" <> u."organizationId"
         OR r."projectId" <> u."projectId")                               AS ai_usage_cross_scope;

-- 3. UUID / source-ID preservation. Legacy UUID primary keys must stay
--    uuid:1111... shaped and unique. A migration that rebuilt tables with
--    generated identifiers would break ticket numbers, cxId, tokenHash and
--    every external provider id.
SELECT
    (SELECT count(*) FROM information_schema.columns
      WHERE table_schema = 'public' AND data_type = 'uuid'
        AND column_name = 'id' AND is_nullable = 'NO')                     AS uuid_pk_columns,
    (SELECT count(*) FROM pg_tables WHERE schemaname = 'public'
        AND (tablename = 'flyway_schema_history'))                        AS flyway_history_tables,
    (SELECT count(*) FROM conversations WHERE "ticketNumber" IS NULL)     AS conversations_without_ticket,
    (SELECT count(*) FROM customers WHERE "cxId" IS NULL)                 AS customers_without_cx_id,
    (SELECT count(*) FROM sessions WHERE "tokenHash" IS NULL)             AS sessions_without_token_hash;

-- 4. Deferred-to-application references (expected non-zero; listed as debt).
SELECT
    (SELECT count(*) FROM deals WHERE "customer" IS NOT NULL)              AS deals_with_raw_customer_uuid,
    (SELECT count(*) FROM tasks WHERE "customer" IS NOT NULL
                                 OR "lead" IS NOT NULL
                                 OR "deal" IS NOT NULL
                                 OR "assignedTo" IS NOT NULL)            AS tasks_with_free_text_refs;