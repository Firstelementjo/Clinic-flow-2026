-- =====================================================================
-- V20260814000100__vector_indexes.sql
-- ADDITIVE, IDEMPOTENT, NON-DESTRUCTIVE.
--
-- PHASE 5a AMENDMENT (2026-10-04, Agent B)
--   The table name and the operator class are now schema-qualified
--   ("public"."document_chunks", public.vector_cosine_ops). The Phase-2
--   version was unqualified, which worked only because Flyway resets the
--   connection's search_path between migrations. Applying the chain in ONE psql
--   session (`psql -f baseline.sql -f this.sql -f outbox.sql`) inherits the
--   baseline's set_config('search_path','',false) and then fails here with
--   `relation "document_chunks" does not exist`, or with
--   `operator class "vector_cosine_ops" does not exist for access method
--   "hnsw"`. Both were observed. That is a plausible operator action during a
--   restore rehearsal, so both traps are closed.
--   This file has never been applied to any database (nothing anywhere has a
--   flyway_schema_history yet), so the change invalidates no recorded checksum.
--   The orchestrator must update the SHA-256 recorded in
--   docs/migration/flyway-runbook.md section 1.
--
-- Why this migration exists
--   The verified live database was built with `prisma db push`, which cannot
--   express pgvector's HNSW access method. The hand-written Prisma migration
--   packages/database/prisma/migrations/20260814000100_vector_index/migration.sql
--   was therefore never applied, so every RAG retrieval degrades to a
--   sequential scan over document_chunks.
--
--   Evidence (ops/flyway/verification/V5_indexes_and_vector.sql on the
--   verified dev database, 2026-10-04):
--       hnsw_indexes           = 0
--       vector_cosine_indexes  = 0
--       tables                 = 45, indexes = 155
--
-- What it creates
--   1. document_chunks_embedding_hnsw_idx - HNSW over cosine distance.
--      vector_cosine_ops matches the `<=>` operator the retrieval query orders
--      by, and HNSW needs no training step and stays correct as rows arrive.
--
-- What it deliberately does NOT create
--   The Prisma migration also declared
--       document_chunks_tenant_idx ON document_chunks
--           ("organizationId", "projectId", "knowledgeBaseId")
--   Those exact three columns are ALREADY indexed in the verified database as
--   document_chunks_organizationId_projectId_knowledgeBaseId_idx (created by
--   `prisma db push` from the same Prisma model). Creating a second index on
--   identical columns would double write amplification on the hottest
--   knowledge table for zero query benefit, so this migration does not.
--   The pre-filter requirement is therefore ALREADY satisfied; only the HNSW
--   index is missing.
--
-- Tenant isolation note (PROJECT_REQUIREMENTS.md FR-5.7)
--   A vector index is a retrieval accelerator, never an authorization
--   boundary. The organizationId/projectId pre-filter must be applied in the
--   WHERE clause before similarity ordering; this index only makes that
--   pre-filter cheap.
--
-- Safety
--   * CREATE INDEX IF NOT EXISTS only. No DROP, no rewrite, no data change.
--   * Runs identically on a fresh baseline-created database and on the
--     existing legacy database.
--   * On a large table run it inside a maintenance window; PostgreSQL takes a
--     SHARE lock that blocks writes to document_chunks while building.
--   * Rollback: DROP INDEX document_chunks_embedding_hnsw_idx (index only, no
--     data loss). Never roll back by dropping the table.
-- =====================================================================

CREATE INDEX IF NOT EXISTS "document_chunks_embedding_hnsw_idx"
  ON "public"."document_chunks"
  USING hnsw ("embedding" public.vector_cosine_ops);

-- Post-condition guard: fail the migration loudly if pgvector could not build
-- the operator class, instead of leaving a silent sequential-scan database.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_indexes
                    WHERE schemaname = 'public'
                      AND indexname = 'document_chunks_embedding_hnsw_idx') THEN
        RAISE EXCEPTION
            'V20260814000100 failed: document_chunks_embedding_hnsw_idx was not created. '
            'The pgvector extension must be installed on the target (image pgvector/pgvector:pg16).';
    END IF;

    -- The tenant pre-filter index must exist under EITHER name.
    IF NOT EXISTS (SELECT 1 FROM pg_indexes
                    WHERE schemaname = 'public'
                      AND tablename = 'document_chunks'
                      AND indexname IN ('document_chunks_tenant_idx',
                                        'document_chunks_organizationId_projectId_knowledgeBaseId_idx')) THEN
        RAISE EXCEPTION
            'V20260814000100 failed: no (organizationId, projectId, knowledgeBaseId) '
            'pre-filter index exists on document_chunks. pgvector retrieval would '
            'silently lose its tenant pre-filter.';
    END IF;
END
$$;