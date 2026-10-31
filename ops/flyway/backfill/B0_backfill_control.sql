-- =====================================================================
-- B0_backfill_control.sql
-- Phase 2 / backfill control plane.
--
-- Adds the ONLY DDL any backfill needs: a control table that records progress,
-- batches, and outcomes so a backfill can be interrupted and resumed.
--
-- This is additive (CREATE TABLE IF NOT EXISTS). It creates no index on an
-- existing table, changes no column, and deletes nothing. It is safe to run on
-- the verified dev database; it has been rehearsed on a throwaway copy.
--
-- Run explicitly:
--   docker exec -i firstelement-postgres psql -U firstelement -d firstelement \
--     -v ON_ERROR_STOP=1 -f - < ops/flyway/backfill/B0_backfill_control.sql
--
-- It is deliberately NOT a Flyway migration: it is operational scaffolding,
-- not a schema change, and it must be appliable without stamping a migration.
-- =====================================================================

CREATE TABLE IF NOT EXISTS "backfill_runs" (
    "id"            uuid        NOT NULL,
    "backfillName"  text        NOT NULL,
    "startedAt"     timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "finishedAt"    timestamp(3),
    "status"        text        NOT NULL DEFAULT 'RUNNING',  -- RUNNING|COMPLETED|ABORTED|FAILED
    "batchSize"     integer     NOT NULL,
    "batchesRun"    integer     NOT NULL DEFAULT 0,
    "rowsProcessed" bigint      NOT NULL DEFAULT 0,
    "rowsFailed"    bigint      NOT NULL DEFAULT 0,
    "operator"      text        NOT NULL,
    "correlationId" text,
    "notes"         text,
    CONSTRAINT "backfill_runs_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "backfill_runs_name_idx" UNIQUE ("backfillName")
);

CREATE TABLE IF NOT EXISTS "backfill_rejections" (
    "id"           uuid        NOT NULL,
    "backfillName" text        NOT NULL,
    "rowId"        uuid        NOT NULL,
    "reason"       text        NOT NULL,
    "detail"       jsonb       NOT NULL DEFAULT '{}'::jsonb,
    "createdAt"    timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "backfill_rejections_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "backfill_rejections_unique" UNIQUE ("backfillName", "rowId")
);

CREATE INDEX IF NOT EXISTS "backfill_rejections_name_idx"
    ON "backfill_rejections" ("backfillName", "createdAt");

COMMENT ON TABLE "backfill_runs" IS
    'One row per backfill. Progress is committed in the SAME transaction as each '
    'batch, so an interrupted backfill resumes from the last committed batch '
    'with no double counting and no lost batch.';

COMMENT ON TABLE "backfill_rejections" IS
    'Rows a backfill refused to touch, with the reason. Rejections are data, '
    'not log lines: they must be reviewable before the backfill is declared done.';