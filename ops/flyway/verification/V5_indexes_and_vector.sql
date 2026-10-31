-- =====================================================================
-- V5_indexes_and_vector.sql
-- Phase 2 / database verification.
--
-- Purpose: index inventory plus the pgvector contract.
--
-- pgvector is part of Release A: retrieval must run on HNSW over cosine
-- distance, with a tenant/project pre-filter index that the planner can use
-- BEFORE similarity ordering. The pgvector index is a retrieval accelerator,
-- never an authorization boundary (PROJECT_REQUIREMENTS.md FR-5.7).
--
-- Drift reconciled by Flyway migration V20260814000100:
--   document_chunks_embedding_hnsw_idx  MISSING in the live dev DB
--     (created with `prisma db push`, which cannot express the HNSW index)
--
-- The tenant pre-filter already exists under the db-push generated name
-- document_chunks_organizationId_projectId_knowledgeBaseId_idx on the same
-- three columns. V20260814000100 therefore does NOT create a second,
-- redundant index over identical columns.
--
-- Expected for the Phase-2 baseline: 45 tables, 155 indexes before
-- V20260814000100 and 156 after (the HNSW index is the only addition).
--
-- Read-only.
-- =====================================================================
-- --- extensions -----------------------------------------------------
SELECT extname, extversion FROM pg_extension ORDER BY extname;

-- --- summary assertions ---------------------------------------------
SELECT
    (SELECT count(*) FROM pg_tables  WHERE schemaname = 'public')                       AS tables,
    (SELECT count(*) FROM pg_indexes WHERE schemaname = 'public')                       AS indexes,
    (SELECT count(*) FROM pg_indexes WHERE schemaname = 'public'
                                AND indexdef LIKE '%UNIQUE%')                          AS unique_indexes,
    (SELECT count(*) FROM pg_indexes WHERE schemaname = 'public'
                                AND indexdef LIKE '%hnsw%')                            AS hnsw_indexes,
    (SELECT count(*) FROM pg_indexes WHERE schemaname = 'public'
                                AND indexdef LIKE '%vector_cosine_ops%')               AS vector_cosine_indexes,
    (SELECT count(*) FROM pg_indexes
      WHERE schemaname = 'public' AND tablename = 'document_chunks'
        AND indexdef LIKE '%UNIQUE%')                                                   AS document_chunks_uniques,
    (SELECT count(*) FROM pg_index i
       JOIN pg_class c    ON c.oid = i.indrelid
       JOIN pg_namespace n ON n.oid = c.relnamespace
      WHERE n.nspname = 'public' AND c.relname = 'document_chunks'
        AND i.indisvalid)                                                               AS document_chunks_valid_indexes,
    (SELECT count(*) FROM information_schema.columns
      WHERE table_schema = 'public' AND table_name = 'document_chunks'
        AND udt_name = 'vector')                                                       AS embedding_vector_columns;

-- --- vector capability proof (not just "the extension exists") -------
-- Proves pgvector can actually build and use the cosine operator class that
-- the HNSW index depends on. Run AFTER V20260814000100.
SELECT extversion FROM pg_extension WHERE extname = 'vector';
SELECT '[0,1,2]'::vector <=> '[3,4,5]'::vector AS cosine_distance_probe;

-- --- full index inventory -------------------------------------------
SELECT tablename, indexname, indexdef
  FROM pg_indexes
 WHERE schemaname = 'public'
 ORDER BY tablename, indexname;