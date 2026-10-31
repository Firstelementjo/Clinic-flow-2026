-- =====================================================================
-- undo_phase6a_seed.sql
-- REHEARSAL ONLY. Never run against `firstelement`.
--
-- Purpose: give ops/flyway/rollback/undo_phase6a.sql something to REFUSE to
-- destroy, so the second gate is proven to work rather than assumed. A guard
-- that has never rejected anything is not a guard.
--
-- Synthetic data only: two organisations named undo-1 / undo-2, one project, one
-- user, one customer and one conversation each, one workflow definition and
-- version, two RUNNING workflow_runs, and one collision whose ABORTED_BY_HUMAN
-- outcome names the run it aborted (required by
-- collision_records_aborted_run_check, which is a biconditional).
--
-- Throwaway database only:
--   docker cp ops/flyway/rollback/rehearsal/undo_phase6a_seed.sql \
--           firstelement-postgres:/tmp/undo_phase6a_seed.sql
--   docker exec firstelement-postgres psql -U firstelement \
--       -d firstelement_6a_check -v ON_ERROR_STOP=1 -f /tmp/undo_phase6a_seed.sql
-- =====================================================================

INSERT INTO organizations (id, name, slug, "createdAt", "updatedAt")
SELECT gen_random_uuid(), 'Undo Tenant ' || n, 'undo-' || n, now(), now()
  FROM generate_series(1, 2) AS n;

INSERT INTO projects (id, "organizationId", name, slug, "createdAt", "updatedAt")
SELECT gen_random_uuid(), o.id, 'Undo Project', 'undo-p-' || o.slug, now(), now()
  FROM organizations o WHERE o.slug LIKE 'undo-%';

INSERT INTO users (id, "organizationId", email, "passwordHash", "fullName",
                   role, locale, "createdAt", "updatedAt")
SELECT gen_random_uuid(), o.id, o.slug || '@undo.test', 'not-a-real-hash',
       'Undo Operator', 'EMPLOYEE'::"UserRole", 'ar'::"Locale", now(), now()
  FROM organizations o WHERE o.slug LIKE 'undo-%';

INSERT INTO customers (id, "organizationId", "projectId", "cxId", "displayName",
                       "createdAt", "updatedAt")
SELECT gen_random_uuid(), o.id, p.id,
       'CX' || substr(md5(o.slug), 1, 6), 'Undo Customer', now(), now()
  FROM organizations o JOIN projects p ON p."organizationId" = o.id
 WHERE o.slug LIKE 'undo-%';

INSERT INTO conversations (id, "organizationId", "projectId", "customerId",
                           "ticketNumber", channel, "createdAt", "updatedAt")
SELECT gen_random_uuid(), c."organizationId", c."projectId", c.id,
       'T' || substr(md5(c."cxId"), 1, 8), 'MOCK'::"ChannelType", now(), now()
  FROM customers c;

INSERT INTO workflow_definitions (id, "organizationId", "key", "name",
                                  "triggerType", "createdAt", "updatedAt")
SELECT gen_random_uuid(), o.id, 'undo-' || o.slug, 'Undo workflow',
       'CONVERSATION_OPENED', now(), now()
  FROM organizations o WHERE o.slug LIKE 'undo-%';

INSERT INTO workflow_versions (id, "organizationId", "workflowDefinitionId",
                               "versionNumber", "contentHash", "createdAt", "updatedAt")
SELECT gen_random_uuid(), d."organizationId", d.id, 1, 'sha256:undo', now(), now()
  FROM workflow_definitions d;

-- Two RUNNING runs, so workflow_runs is non-empty as well.
INSERT INTO workflow_runs (id, "organizationId", "projectId", "workflowDefinitionId",
                           "workflowVersionId", "conversationId", "triggerInstanceId",
                           "status", "principalUserId", "startedAt", "createdAt", "updatedAt")
SELECT gen_random_uuid(), d."organizationId", cv."projectId", d.id, v.id, cv.id,
       'undo-' || d."key", 'RUNNING', u.id, now(), now(), now()
  FROM workflow_definitions d
  JOIN workflow_versions v ON v."workflowDefinitionId" = d.id
  JOIN conversations cv ON cv."organizationId" = d."organizationId"
  JOIN users u ON u."organizationId" = d."organizationId";

-- One collision per run, and the ABORTED_BY_HUMAN outcome names the run: the
-- biconditional requires BOTH directions.
INSERT INTO collision_records (id, "organizationId", "projectId", scope,
                               "resourceType", "resourceId", "counterpartyId",
                               "counterpartyType", "collisionWindow", outcome,
                               "resolutionDetail", "abortedRunId", "observedAt", "createdAt")
SELECT gen_random_uuid(), c."organizationId", c."projectId",
       'CONVERSATION'::"CollisionScope", 'Conversation', cv.id, 'racer-A',
       'WORKFLOW_RUN', 42, 'ABORTED_BY_HUMAN'::"CollisionResolution", 'human won',
       r.id, now(), now()
  FROM customers c
  JOIN conversations cv ON cv."customerId" = c.id
  JOIN workflow_runs r ON r."conversationId" = cv.id;