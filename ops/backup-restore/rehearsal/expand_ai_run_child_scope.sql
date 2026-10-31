-- =====================================================================
-- REHEARSAL ONLY -- expand DDL for V202610040009, executed against a
-- DISPOSABLE database (firstelement_restore_check). This file exists so the
-- Phase-2 backfill rehearsal is reproducible; it is NOT a Flyway migration
-- and must never be run against `firstelement`.
--
-- Rehearsal commands (2026-10-04, evidence in
-- docs/migration/flyway-runbook.md):
--
--   $sql = Get-Content -Raw ops/backup-restore/rehearsal/expand_ai_run_child_scope.sql
--   $sql | docker exec -i firstelement-postgres psql -U firstelement `
--          -d firstelement_restore_check -v ON_ERROR_STOP=1 -f -
-- =====================================================================

ALTER TABLE "ai_run_steps"
  ADD COLUMN IF NOT EXISTS "organizationId" uuid,
  ADD COLUMN IF NOT EXISTS "projectId"      uuid,
  ADD COLUMN IF NOT EXISTS "updatedAt"      timestamp(3) NOT NULL
                                                DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE "ai_run_retrievals"
  ADD COLUMN IF NOT EXISTS "organizationId" uuid,
  ADD COLUMN IF NOT EXISTS "projectId"      uuid,
  ADD COLUMN IF NOT EXISTS "updatedAt"      timestamp(3) NOT NULL
                                                DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE "ai_run_tool_calls"
  ADD COLUMN IF NOT EXISTS "organizationId" uuid,
  ADD COLUMN IF NOT EXISTS "projectId"      uuid,
  ADD COLUMN IF NOT EXISTS "updatedAt"      timestamp(3) NOT NULL
                                                DEFAULT CURRENT_TIMESTAMP;

CREATE INDEX IF NOT EXISTS "ai_run_steps_org_project_created_idx"
    ON "ai_run_steps" ("organizationId", "projectId", "createdAt");
CREATE INDEX IF NOT EXISTS "ai_run_retrievals_org_project_created_idx"
    ON "ai_run_retrievals" ("organizationId", "projectId", "createdAt");
CREATE INDEX IF NOT EXISTS "ai_run_tool_calls_org_project_created_idx"
    ON "ai_run_tool_calls" ("organizationId", "projectId", "createdAt");

-- Seed enough rows to prove the backfill actually moves data rather than
-- merely reporting "0 remaining" on an empty table. Synthetic rows in a
-- disposable database only.
INSERT INTO "conversations" ("id","organizationId","projectId","customerId","ticketNumber",
                             "channel","status","mode","priority","lastMessageAt",
                             "createdAt","updatedAt")
SELECT gen_random_uuid(), o."id", p."id",
       (SELECT "id" FROM customers c WHERE c."projectId" = p."id" LIMIT 1),
       'REH-' || substr(md5(random()::text), 1, 8),
       'MOCK'::"ChannelType", 'OPEN'::"ConversationStatus", 'HUMAN'::"ConversationMode",
       'NORMAL'::"ConversationPriority", CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
  FROM organizations o
  CROSS JOIN LATERAL (SELECT "id" FROM projects WHERE "organizationId" = o."id" LIMIT 1) p
 LIMIT 3;

-- The verified dev database has 0 knowledge_bases rows, so the
-- ai_run_retrievals pass of B1 would have nothing to move. Seed one in this
-- DISPOSABLE database so all three backfill passes are actually exercised.
INSERT INTO "knowledge_bases" ("id","organizationId","projectId","name","isDefault",
                               "createdAt","updatedAt")
SELECT gen_random_uuid(), o."id", p."id", 'REHEARSAL KB', false,
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
  FROM organizations o
  CROSS JOIN LATERAL (SELECT "id" FROM projects WHERE "organizationId" = o."id" LIMIT 1) p
 LIMIT 1;

INSERT INTO "ai_runs" ("id","organizationId","projectId","conversationId","agentId",
                        "agentVersionId","status","provider","startedAt")
SELECT gen_random_uuid(), c."organizationId", c."projectId", c."id",
       (SELECT "id" FROM ai_agents LIMIT 1),
       (SELECT "id" FROM ai_agent_versions LIMIT 1),
       'COMPLETED'::"AIRunStatus", 'MOCK'::"AIProviderKind", CURRENT_TIMESTAMP
  FROM conversations c
 WHERE c."ticketNumber" LIKE 'REH-%'
 LIMIT 3;

INSERT INTO "ai_run_steps" ("id","runId","sequence","kind","status","createdAt")
SELECT gen_random_uuid(), r."id", s, 'CONTEXT'::"AIRunStepKind", 'OK', CURRENT_TIMESTAMP
  FROM ai_runs r, generate_series(1, 4) s
 WHERE r."conversationId" IN (SELECT "id" FROM conversations WHERE "ticketNumber" LIKE 'REH-%');

INSERT INTO "ai_run_tool_calls" ("id","runId","sequence","toolName","createdAt")
SELECT gen_random_uuid(), r."id", s, 'rehearsal_tool_' || s, CURRENT_TIMESTAMP
  FROM ai_runs r, generate_series(1, 3) s
 WHERE r."conversationId" IN (SELECT "id" FROM conversations WHERE "ticketNumber" LIKE 'REH-%');

INSERT INTO "ai_run_retrievals" ("id","runId","knowledgeBaseId","documentId","chunkId",
                                 "documentTitle","score","createdAt")
SELECT gen_random_uuid(), r."id", kb."id", gen_random_uuid(), gen_random_uuid(),
       'rehearsal chunk', 0.5, CURRENT_TIMESTAMP
  FROM ai_runs r
  CROSS JOIN LATERAL (SELECT "id" FROM knowledge_bases LIMIT 1) kb
 WHERE r."conversationId" IN (SELECT "id" FROM conversations WHERE "ticketNumber" LIKE 'REH-%');

SELECT 'seeded' AS step,
       (SELECT count(*) FROM conversations WHERE "ticketNumber" LIKE 'REH-%') AS conversations,
       (SELECT count(*) FROM ai_runs) AS ai_runs,
       (SELECT count(*) FROM ai_run_steps) AS ai_run_steps,
       (SELECT count(*) FROM ai_run_tool_calls) AS ai_run_tool_calls,
       (SELECT count(*) FROM ai_run_retrievals) AS ai_run_retrievals;