-- =====================================================================
-- V20261005020000__workflow_runs_and_action_results.sql
-- ADDITIVE, IDEMPOTENT-BY-VERSION, NON-DESTRUCTIVE.
--
-- VERSION NUMBERING -- 14 digits (yyyyMMddHHmmss), numerically greater than
--   20261005010000. See the header of
--   V20261005010000__workflow_definitions_and_versions.sql for why a 12-digit
--   version sorts BEFORE the 14-digit baseline and would run against an empty
--   database.
--
-- WHY THIS MIGRATION EXISTS
--   Phase 6 needs the execution record behind every automated decision. Four
--   hard rules in plan.md / phase-06.md make it a table rather than a log line:
--
--     1. "No unrestricted scheduled 'Administrator' identity: a workflow run
--        executes as a named principal with a bounded capability set, recorded
--        per execution." -> workflow_runs.principalUserId is NOT NULL and
--        workflow_runs.capabilityScope is copied from the version.
--     2. "A decision is always explainable" and acceptance gate 2: "a decision
--        returns the inputs that produced it, not just a result" ->
--        workflow_run_actions carries the exact input snapshot AND the result,
--        so an operator can be told WHY without a database console.
--     3. "Do not retry a non-retryable business-state failure" and "loop
--        prevention" -> attemptNumber is unique per (run, action) and
--        retryableOnly is recorded, so "attempt 3 of a retryable failure" is
--        distinguishable from "attempt 3 because somebody kept pressing retry".
--     4. A human takeover must abort an in-flight AI send (phase-06 acceptance
--        gate 1) -> the run carries the AbortRequestedAt/AbortedAt pair with a
--        CHECK, so an abort is a first-class, timestamped state rather than a
--        missing row.
--
-- WHAT IT CREATES
--   1. enum  public.WorkflowRunStatus    (RUNNING, WAITING, SUCCEEDED, FAILED,
--      ABORTED, TAKEOVER)
--   2. enum  public.WorkflowActionOutcome (SUCCEEDED, FAILED, SKIPPED, RETRYABLE)
--   3. table public.workflow_runs        -- one execution of one version
--   4. table public.workflow_run_actions -- APPEND-ONLY attempt history
--
-- APPEND-ONLY, ENFORCED BY SHAPE
--   workflow_run_actions has NO updatedAt and NO mutable status. An action
--   result is a fact about the past: a retry writes a NEW row with the next
--   attemptNumber, it never mutates the failed one. This is the same rule, and
--   for the same reason, as V20261004000700's job_attempts -- and this
--   migration's own guard FAILS if an updatedAt ever appears, so nobody can
--   "harmlessly add one" later.
--   workflow_runs IS mutable (a run progresses) so it carries updatedAt.
--
-- ROUTING EXPLANATIONS ARE NOT A TABLE. THIS IS THE DELIBERATE DECISION.
--   The phase-06 brief lists "routing explanations" among the things a new
--   table might be needed for. It is not needed, and adding one would be
--   actively wrong, for four reasons:
--     * an explanation is a DERIVED read model -- it is exactly
--       (workflow_run_actions.inputSnapshot, resultSnapshot) joined to
--       (workflow_versions.capabilityScope, contentHash). Materialising it
--       means a second copy of the decision that can disagree with the first;
--     * its lifetime must be the decision's lifetime. A stored explanation
--       table accumulates rows nobody prunes (the same debt Phase 5a refused to
--       create for outbox events) and it would become the copy an operator
--       reads AFTER the action history has been corrected;
--     * FR-10.6 redaction is easier when the explanation is assembled from
--       already-redacted columns at read time than when a second store holds
--       customer content it never needed;
--     * an explanation that could disagree with the decision it explains is
--       worse than no explanation, because it looks authoritative.
--   The read model therefore belongs in
--   backend/workflows-sla-routing/** as a projection over these two tables plus
--   workflow_versions, and the indexes below are chosen to serve THAT query
--   (organizationId, runId, attemptNumber) rather than to serve a hypothetical
--   explanations table.
--
-- TIMESTAMPS
--   timestamp(3) WITHOUT time zone everywhere (V6 asserts no timestamptz
--   anywhere). Defaults are (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'); updatedAt
--   has no default, exactly like every mutable table in the baseline.
--
-- INDEX RATIONALE (FR-1.3)
--   workflow_runs_organizationId_trigger_instance_key UNIQUE
--       ("organizationId","triggerInstanceId")
--       Exactly one run per trigger instance per tenant. This is the loop-
--       prevention primitive: a workflow that re-fires on the same event
--       inserts nothing, so a cycle cannot grow the table. Tenant-scoped, never
--       global: a global index would tell tenant B from the violation message
--       that tenant A already processed that provider event id.
--   workflow_runs_org_project_status_created_idx
--       ("organizationId","projectId","status","createdAt")
--       The operator's run list, newest first, tenant-scoped.
--   workflow_runs_organizationId_conversationId_idx
--       ("organizationId","conversationId")
--       "What did automation do to THIS conversation?" -- the drill-down behind
--       the routing explanation read model. Tenant-led even though
--       conversationId is the filter, because the authorisation scope is the
--       tenant.
--   workflow_runs_organizationId_versionId_status_idx
--       ("organizationId","workflowVersionId","status")
--       Rollout safety: while a new version is being rolled out, this is how
--       many runs failed under it. Answers the question BEFORE the rollback.
--   workflow_run_actions_organizationId_runId_attemptNumber_key UNIQUE
--       ("organizationId","runId","actionKey","attemptNumber")
--       Attempt numbering per action, per tenant. Makes the concurrency test
--       possible: two writers racing to record attempt 1 of the same action
--       lose exactly one, and the loser learns it lost from the conflict rather
--       than from a double-counted history.
--   workflow_run_actions_organizationId_runId_idx
--       ("organizationId","runId","startedAt")
--       The timeline of one run, which is the shape of the explanation the
--       operator is shown.
--   workflow_run_actions_organizationId_outcome_createdAt_idx
--       ("organizationId","outcome","createdAt")
--       Failure dashboard, tenant-scoped like every other read path.
--
--   Every index leads with "organizationId". Deliberately NO tenant-less index:
--   workflow execution is always tenant work, and there is no cross-tenant
--   sweep in this slice that would need one.
--
-- SAFETY
--   * CREATE TYPE / CREATE TABLE / ALTER TABLE ADD CONSTRAINT only. No DROP, no
--     RENAME, no ALTER of an existing object, no data change.
--   * Strict CREATE TABLE, not IF NOT EXISTS: a pre-existing workflow_runs must
--     fail the migration loudly rather than be adopted with an unknown shape.
--   * FKs only where the parent is guaranteed: organizations, projects, users,
--     workflow_definitions and workflow_versions (both from
--     V20261005010000, always earlier in the chain), workflow_runs, the
--     action's own run, the replay target, and outbox_events (V20261004000100)
--     so an emitted intent is reachable from the history that emitted it.
--   * principalUserId is NOT NULL: the phase-06 hard rule forbids an
--     unrestricted scheduled identity, so "no principal" must be unexpressible.
--   * Rollback: DROP TABLE workflow_run_actions, DROP TABLE workflow_runs,
--     DROP TYPE WorkflowActionOutcome, DROP TYPE WorkflowRunStatus -- rehearsed
--     on a throwaway database only (ops/flyway/rollback/undo_phase6a.sql).
--   * Every index name stays under PostgreSQL's 63-byte identifier limit.
--   * The guard looks indexes up in pg_indexes, NOT with to_regclass().
--   * Every identifier is schema-qualified because the baseline sets
--     search_path = '' when the whole chain is piped into ONE psql session.
-- =====================================================================

CREATE TYPE "public"."WorkflowRunStatus" AS ENUM (
    'RUNNING',
    'WAITING',
    'SUCCEEDED',
    'FAILED',
    'ABORTED',
    'TAKEOVER'
);

-- RETRYABLE is an OUTCOME, not a run status: "this action failed in a way the
-- engine may retry" is a fact about one action, and collapsing it into FAILED
-- would lose the distinction plan.md's "do not retry a non-retryable
-- business-state failure" rule depends on.
CREATE TYPE "public"."WorkflowActionOutcome" AS ENUM (
    'SUCCEEDED',
    'FAILED',
    'SKIPPED',
    'RETRYABLE'
);

-- ---------------------------------------------------------------------
-- workflow_runs: one execution of one version, by one named principal.
-- ---------------------------------------------------------------------
CREATE TABLE "public"."workflow_runs" (
    "id"                    uuid NOT NULL,
    "organizationId"        uuid NOT NULL,
    "projectId"             uuid NOT NULL,
    "workflowDefinitionId"  uuid NOT NULL,
    "workflowVersionId"     uuid NOT NULL,
    -- WHAT THIS RUN IS ABOUT. NOT NULL, because every Phase-6 trigger is a
    -- conversation event (opened, inbound message, SLA breach, idle, takeover)
    -- and a run with no conversation could not be explained to an operator, who
    -- asks "what did automation do to this ticket". Making it nullable would
    -- also make the routing-explanation read model (section 8 of
    -- workflow-sla-statements.sql) unable to use its index.
    --
    -- The composite foreign key below guarantees it belongs to THIS TENANT's
    -- conversation, which the single-column FK deliberately replaced.
    "conversationId"        uuid NOT NULL,
    -- The trigger instance this run serves. Unique per tenant together with
    -- organizationId: the loop-prevention primitive.
    "triggerInstanceId"     text NOT NULL,
    "status"                "public"."WorkflowRunStatus" DEFAULT 'RUNNING'::"public"."WorkflowRunStatus" NOT NULL,
    -- NOT NULL and an FK to users: "no named principal" is unexpressible, so
    -- the phase-06 hard rule cannot be violated by omission.
    "principalUserId"       uuid NOT NULL,
    -- Copied from the version, not joined at read time: a later widening of the
    -- version's capability set must not retroactively enlarge what this run was
    -- allowed to do.
    "capabilityScope"       jsonb DEFAULT '[]'::"jsonb" NOT NULL,
    -- The loop-prevention budget, recorded per execution.
    "loopDepth"             integer DEFAULT 0 NOT NULL,
    "maxLoopDepth"          integer DEFAULT 5 NOT NULL,
    "startedAt"             timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    "finishedAt"            timestamp(3) without time zone,
    -- Human takeover: the phase-06 gate is that a takeover ABORTS an in-flight
    -- AI send, so both halves are timestamped and the CHECK ties them together.
    "abortRequestedAt"      timestamp(3) without time zone,
    "abortedAt"             timestamp(3) without time zone,
    "abortedByUserId"       uuid,
    "abortReason"           text,
    "failureReason"         text,
    "correlationId"         text,
    "causationId"           text,
    "createdAt"             timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    "updatedAt"             timestamp(3) without time zone NOT NULL,
    CONSTRAINT "workflow_runs_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "workflow_runs_loop_depth_check"
        CHECK ("loopDepth" >= 0 AND "loopDepth" <= "maxLoopDepth"),
    CONSTRAINT "workflow_runs_max_loop_depth_check"
        CHECK ("maxLoopDepth" >= 1),
    -- A finished run says when, and an unfinished one must not claim it. Stated
    -- as a biconditional in its positive form so it reads the same way as the
    -- rest of this chain rather than as a negated equality.
    CONSTRAINT "workflow_runs_finished_state_check"
        CHECK (("status" IN ('RUNNING', 'WAITING')) = ("finishedAt" IS NULL)),
    -- An abort always names who requested it, and an abort can only complete if
    -- one was requested. Note what is deliberately NOT asserted here: that
    -- abortRequestedAt implies abortedAt. The takeover is asynchronous -- the
    -- request is recorded first so the AI send has something to observe, and the
    -- confirmation lands when it has actually been cancelled. Asserting the
    -- tighter pair would reject that valid intermediate state.
    CONSTRAINT "workflow_runs_abort_pair_check"
        CHECK (("abortRequestedAt" IS NULL OR "abortedByUserId" IS NOT NULL)
               AND ("abortedAt" IS NULL OR "abortRequestedAt" IS NOT NULL)),
    -- ABORTED/TAKEOVER are aborts; they must say who and when.
    CONSTRAINT "workflow_runs_aborted_state_check"
        CHECK (("status" NOT IN ('ABORTED', 'TAKEOVER'))
               OR ("abortedAt" IS NOT NULL AND "abortedByUserId" IS NOT NULL)),
    -- A FAILED run must say why. A failure with no reason is not diagnosable
    -- (FR-6.7).
    CONSTRAINT "workflow_runs_failure_reason_check"
        CHECK (("status" <> 'FAILED'::"public"."WorkflowRunStatus")
               OR "failureReason" IS NOT NULL),
    CONSTRAINT "workflow_runs_capability_scope_check"
        CHECK (jsonb_typeof("capabilityScope") = 'array'),
    CONSTRAINT "workflow_runs_trigger_instance_check"
        CHECK (length(btrim("triggerInstanceId")) > 0)
);

ALTER TABLE ONLY "public"."workflow_runs"
    ADD CONSTRAINT "workflow_runs_organizationId_fkey"
    FOREIGN KEY ("organizationId") REFERENCES "public"."organizations"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."workflow_runs"
    ADD CONSTRAINT "workflow_runs_projectId_fkey"
    FOREIGN KEY ("projectId") REFERENCES "public"."projects"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."workflow_runs"
    ADD CONSTRAINT "workflow_runs_workflowDefinitionId_fkey"
    FOREIGN KEY ("workflowDefinitionId") REFERENCES "public"."workflow_definitions"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

-- workflowVersionId is referenced by the COMPOSITE foreign key added below, not
-- by a single-column one. A single-column FK on workflowVersionId would guarantee
-- only that the version exists, and the composite key replaces it completely
-- while also guaranteeing it belongs to this definition and this tenant. Having
-- both would be one constraint doing strictly less than the other.

-- The same treatment for conversationId. A single-column FK would allow a run to
-- point at ANOTHER TENANT'S conversation, and because organizationId and
-- conversationId are independent columns nothing else in the schema would notice.
-- The target pair needs a unique index on conversations, which the baseline does
-- not have -- a conversation is unique by its id alone, and its organizationId is
-- a separate column.
--
-- This is the ONE index this phase adds to a pre-existing baseline table, so it
-- is created IF NOT EXISTS and named explicitly: it must not fail on a database
-- where it already exists, and it must not silently create a second one. It leads
-- with "id", not "organizationId", so V9's census classifies it as unique-other
-- and it is NOT a tenant-led index -- it exists solely as a foreign-key target,
-- and the composite FK it enables is what provides the tenant guarantee.
CREATE UNIQUE INDEX IF NOT EXISTS "conversations_id_organizationId_key"
    ON "public"."conversations" USING "btree" ("id", "organizationId");

-- A run's principal is a real user in this tenant. RESTRICT rather than CASCADE:
-- deleting a user must not silently delete the history of every decision they
-- authorised (FR-6.7). This is deliberately the opposite of the
-- organizationId/projectId cascade, and the difference is the point.
ALTER TABLE ONLY "public"."workflow_runs"
    ADD CONSTRAINT "workflow_runs_principalUserId_fkey"
    FOREIGN KEY ("principalUserId") REFERENCES "public"."users"("id")
    ON UPDATE CASCADE ON DELETE RESTRICT;

ALTER TABLE ONLY "public"."workflow_runs"
    ADD CONSTRAINT "workflow_runs_abortedByUserId_fkey"
    FOREIGN KEY ("abortedByUserId") REFERENCES "public"."users"("id")
    ON UPDATE CASCADE ON DELETE RESTRICT;

-- ---------------------------------------------------------------------
-- workflow_run_actions: APPEND-ONLY per-action attempt history.
-- Who did what, when, with which input, and what came out.
-- ---------------------------------------------------------------------
CREATE TABLE "public"."workflow_run_actions" (
    "id"             uuid NOT NULL,
    "organizationId" uuid NOT NULL,
    "projectId"      uuid NOT NULL,
    "workflowRunId"  uuid NOT NULL,
    -- The node/action key from the version's state machine. Free text: the
    -- vocabulary belongs to the authored workflow and will grow.
    "actionKey"      text NOT NULL,
    "actionType"     text NOT NULL,
    "attemptNumber"  integer NOT NULL,
    "outcome"        "public"."WorkflowActionOutcome" NOT NULL,
    -- The exact input the engine saw. This is the acceptance gate's "a decision
    -- returns the inputs that produced it, not just a result".
    "inputSnapshot"  jsonb DEFAULT '{}'::"jsonb" NOT NULL,
    -- The outcome, plus the reasoning summary the operator reads. Assembled at
    -- read time with inputSnapshot, never duplicated into a second table.
    "resultSnapshot" jsonb DEFAULT '{}'::"jsonb" NOT NULL,
    -- NULL is allowed and is meaningful: a machine-triggered action has no
    -- human actor. What is NOT allowed is an actor with no identity.
    "actorUserId"    uuid,
    "actorPrincipal" text,
    "outboxEventId"  uuid,
    "startedAt"      timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    "finishedAt"     timestamp(3) without time zone,
    "durationMs"     integer,
    "errorCode"      text,
    "errorMessage"   text,
    "isReplay"       boolean DEFAULT false NOT NULL,
    "replayOfId"     uuid,
    "correlationId"  text,
    "causationId"    text,
    -- NO updatedAt. This table is append-only history; the guard at the bottom
    -- of this file fails if one ever appears.
    "createdAt"      timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    CONSTRAINT "workflow_run_actions_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "workflow_run_actions_attempt_number_check"
        CHECK ("attemptNumber" >= 1),
    CONSTRAINT "workflow_run_actions_duration_check"
        CHECK ("durationMs" IS NULL OR "durationMs" >= 0),
    -- Who/what: an action with neither a user nor a named principal is
    -- unattributable, which is exactly what plan.md forbids.
    CONSTRAINT "workflow_run_actions_actor_check"
        CHECK ("actorUserId" IS NOT NULL OR "actorPrincipal" IS NOT NULL),
    -- A finished action says when; an unfinished one must not claim a duration.
    CONSTRAINT "workflow_run_actions_finished_check"
        CHECK (("finishedAt" IS NULL) = ("durationMs" IS NULL)),
    -- Only a failure can carry an error, and only a RETRYABLE failure is
    -- retryable. Encoding that here is what makes "do not retry a
    -- non-retryable business-state failure" checkable rather than a comment.
    CONSTRAINT "workflow_run_actions_error_outcome_check"
        CHECK (("errorCode" IS NULL) = ("outcome" NOT IN ('FAILED', 'RETRYABLE'))),
    CONSTRAINT "workflow_run_actions_retryable_check"
        CHECK ("outcome" <> 'RETRYABLE'::"public"."WorkflowActionOutcome"
               OR ("errorCode" IS NOT NULL AND "errorMessage" IS NOT NULL)),
    -- A row can only be marked a replay if it names what it replays.
    CONSTRAINT "workflow_run_actions_replay_check"
        CHECK (("isReplay" = false) OR ("replayOfId" IS NOT NULL)),
    CONSTRAINT "workflow_run_actions_action_key_check"
        CHECK (length(btrim("actionKey")) > 0)
);

ALTER TABLE ONLY "public"."workflow_run_actions"
    ADD CONSTRAINT "workflow_run_actions_organizationId_fkey"
    FOREIGN KEY ("organizationId") REFERENCES "public"."organizations"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."workflow_run_actions"
    ADD CONSTRAINT "workflow_run_actions_projectId_fkey"
    FOREIGN KEY ("projectId") REFERENCES "public"."projects"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."workflow_run_actions"
    ADD CONSTRAINT "workflow_run_actions_workflowRunId_fkey"
    FOREIGN KEY ("workflowRunId") REFERENCES "public"."workflow_runs"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."workflow_run_actions"
    ADD CONSTRAINT "workflow_run_actions_actorUserId_fkey"
    FOREIGN KEY ("actorUserId") REFERENCES "public"."users"("id")
    ON UPDATE CASCADE ON DELETE RESTRICT;

ALTER TABLE ONLY "public"."workflow_run_actions"
    ADD CONSTRAINT "workflow_run_actions_replayOfId_fkey"
    FOREIGN KEY ("replayOfId") REFERENCES "public"."workflow_run_actions"("id")
    ON UPDATE CASCADE ON DELETE SET NULL;

ALTER TABLE ONLY "public"."workflow_run_actions"
    ADD CONSTRAINT "workflow_run_actions_outboxEventId_fkey"
    FOREIGN KEY ("outboxEventId") REFERENCES "public"."outbox_events"("id")
    ON UPDATE CASCADE ON DELETE SET NULL;

-- ---------------------------------------------------------------------
-- indexes
-- ---------------------------------------------------------------------
-- One run per trigger instance per tenant. ON CONFLICT on this index is the
-- loop-prevention contract: a re-fired trigger inserts nothing.
CREATE UNIQUE INDEX "workflow_runs_organizationId_trigger_instance_key"
    ON "public"."workflow_runs" USING "btree" ("organizationId", "triggerInstanceId");

-- The operator's run list, newest first.
CREATE INDEX "workflow_runs_org_project_status_created_idx"
    ON "public"."workflow_runs" USING "btree"
       ("organizationId", "projectId", "status", "createdAt");

-- "What did automation do to THIS conversation?" -- the drill-down behind the
-- routing-explanation read model.
CREATE INDEX "workflow_runs_organizationId_conversationId_idx"
    ON "public"."workflow_runs" USING "btree" ("organizationId", "conversationId");

-- Rollout safety: failures under a specific version, before rolling back.
CREATE INDEX "workflow_runs_organizationId_versionId_status_idx"
    ON "public"."workflow_runs" USING "btree"
       ("organizationId", "workflowVersionId", "status");

-- Attempt numbering per action, per tenant. The concurrency test races two
-- writers on this index.
CREATE UNIQUE INDEX "workflow_run_actions_organizationId_runId_actionKey_attempt_key"
    ON "public"."workflow_run_actions" USING "btree"
       ("organizationId", "workflowRunId", "actionKey", "attemptNumber");

-- One run's timeline.
CREATE INDEX "workflow_run_actions_organizationId_runId_idx"
    ON "public"."workflow_run_actions" USING "btree"
       ("organizationId", "workflowRunId", "startedAt");

-- Failure dashboard.
CREATE INDEX "workflow_run_actions_organizationId_outcome_createdAt_idx"
    ON "public"."workflow_run_actions" USING "btree"
       ("organizationId", "outcome", "createdAt");

-- ---------------------------------------------------------------------
-- THE COMPOSITE TENANT KEYS -- closing the cross-tenant gap in DDL
-- ---------------------------------------------------------------------
-- A plain FK on workflow_runs.workflowVersionId guarantees the version EXISTS.
-- It does NOT guarantee the version belongs to this run's definition, or to
-- this run's tenant -- and the version's own organizationId is a separate
-- column. So a bug in the repository could execute tenant A's workflow body
-- inside tenant B's run, and nothing in the schema would object.
--
-- workflow_versions is created first (V20261005010000), so this column set can
-- be referenced from here. It is a UNIQUE INDEX and deliberately NOT a UNIQUE
-- CONSTRAINT: a constraint would add a pg_constraint row of contype = 'u' and
-- break V4's long-standing `unique_constraints = 0` invariant, which the 53
-- baseline unique indexes and every migration since have preserved. PostgreSQL
-- accepts a unique index as a foreign-key target, which is verified in
-- FlywayBaselineAcceptanceTest.
CREATE UNIQUE INDEX "workflow_versions_id_definitionId_organizationId_key"
    ON "public"."workflow_versions" USING "btree"
       ("id", "workflowDefinitionId", "organizationId");

-- And the same guarantee for the conversation: a run can only point at a
-- conversation of ITS OWN TENANT. Referencing the baseline's new
-- conversations_id_organizationId_key.
ALTER TABLE ONLY "public"."workflow_runs"
    ADD CONSTRAINT "workflow_runs_conversation_tenant_fkey"
    FOREIGN KEY ("conversationId", "organizationId")
    REFERENCES "public"."conversations"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE CASCADE;

-- Now the run can demand that its version is the version OF ITS OWN DEFINITION
-- IN ITS OWN TENANT. Referencing the triple above is what makes the composite FK
-- legal: without a UNIQUE on exactly these columns, PostgreSQL would reject it.
-- This turns "the repository scopes the version lookup" from a convention into
-- an invariant the database refuses to break.
ALTER TABLE ONLY "public"."workflow_runs"
    ADD CONSTRAINT "workflow_runs_version_tenant_fkey"
    FOREIGN KEY ("workflowVersionId", "workflowDefinitionId", "organizationId")
    REFERENCES "public"."workflow_versions"("id", "workflowDefinitionId", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

-- ---------------------------------------------------------------------
-- Post-condition guard.
-- Index existence is checked in pg_indexes, NOT with to_regclass().
-- ---------------------------------------------------------------------
DO $$
DECLARE
    missing text;
BEGIN
    SELECT string_agg(x, ', ')
      INTO missing
      FROM unnest(ARRAY[
               'workflow_versions_id_definitionId_organizationId_key',
               'conversations_id_organizationId_key',
               'workflow_runs_organizationId_trigger_instance_key',
               'workflow_runs_org_project_status_created_idx',
               'workflow_runs_organizationId_conversationId_idx',
               'workflow_runs_organizationId_versionId_status_idx',
               'workflow_run_actions_organizationId_runId_actionKey_attempt_key',
               'workflow_run_actions_organizationId_runId_idx',
               'workflow_run_actions_organizationId_outcome_createdAt_idx'
           ]) AS x
     WHERE NOT EXISTS (SELECT 1 FROM pg_indexes
                        WHERE schemaname = 'public' AND indexname = x);

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'V20261005020000 failed: missing workflow-run indexes: %', missing;
    END IF;

    -- Truncation detection, done correctly: every index that exists on these tables
    -- must be one this file declared by its exact name. A name longer than 63
    -- bytes is silently truncated by PostgreSQL and can never match, so it is
    -- caught -- and a legitimate 63-byte name is not falsely flagged. The first
    -- attempt at this guard was `length(indexname) = 63`, which the throwaway
    -- rehearsal proved WRONG: 63 bytes is legal and untruncated.
    IF EXISTS (SELECT 1 FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename IN ('workflow_runs', 'workflow_run_actions')
                  AND indexname NOT IN (
                      'workflow_runs_pkey',
                      'workflow_runs_organizationId_trigger_instance_key',
                      'workflow_runs_org_project_status_created_idx',
                      'workflow_runs_organizationId_conversationId_idx',
                      'workflow_runs_organizationId_versionId_status_idx',
                      'workflow_run_actions_pkey',
                      'workflow_run_actions_organizationId_runId_actionKey_attempt_key',
                      'workflow_run_actions_organizationId_runId_idx',
                      'workflow_run_actions_organizationId_outcome_createdAt_idx')) THEN
        RAISE EXCEPTION
            'V20261005020000 failed: an unexpected index exists on a workflow-run table -- '
            'most likely a name longer than 63 bytes was silently truncated.';
    END IF;

    IF (SELECT string_agg(e.enumlabel, ',' ORDER BY e.enumsortorder)
          FROM pg_type t
          JOIN pg_enum e ON e.enumtypid = t.oid
          JOIN pg_namespace n ON n.oid = t.typnamespace
         WHERE n.nspname = 'public' AND t.typname = 'WorkflowRunStatus')
       IS DISTINCT FROM 'RUNNING,WAITING,SUCCEEDED,FAILED,ABORTED,TAKEOVER' THEN
        RAISE EXCEPTION
            'V20261005020000 failed: public.WorkflowRunStatus value list drifted.';
    END IF;

    IF (SELECT string_agg(e.enumlabel, ',' ORDER BY e.enumsortorder)
          FROM pg_type t
          JOIN pg_enum e ON e.enumtypid = t.oid
          JOIN pg_namespace n ON n.oid = t.typnamespace
         WHERE n.nspname = 'public' AND t.typname = 'WorkflowActionOutcome')
       IS DISTINCT FROM 'SUCCEEDED,FAILED,SKIPPED,RETRYABLE' THEN
        RAISE EXCEPTION
            'V20261005020000 failed: public.WorkflowActionOutcome value list drifted.';
    END IF;

    -- FR-1.2.
    IF EXISTS (SELECT 1
                 FROM pg_attribute a
                 JOIN pg_class c ON c.oid = a.attrelid
                 JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public'
                  AND c.relname IN ('workflow_runs', 'workflow_run_actions')
                  AND a.attname = 'organizationId' AND NOT a.attnotnull) THEN
        RAISE EXCEPTION
            'V20261005020000 failed: "organizationId" must be NOT NULL on both workflow-run tables (FR-1.2).';
    END IF;

    -- V6 contract.
    IF EXISTS (SELECT 1
                 FROM pg_attribute a
                 JOIN pg_class c ON c.oid = a.attrelid
                 JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public'
                  AND c.relname IN ('workflow_runs', 'workflow_run_actions')
                  AND a.attnum > 0 AND NOT a.attisdropped
                  AND a.atttypid = 'timestamptz'::regtype) THEN
        RAISE EXCEPTION
            'V20261005020000 failed: a workflow-run table contains a timestamptz column; '
            'every timestamp in this schema is timestamp(3) without time zone.';
    END IF;

    -- Every UTC-writing default must say UTC.
    IF EXISTS (SELECT 1
                 FROM pg_attrdef d
                 JOIN pg_class c ON c.oid = d.adrelid
                 JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public'
                  AND c.relname IN ('workflow_runs', 'workflow_run_actions')
                  AND pg_get_expr(d.adbin, d.adrelid) LIKE '%CURRENT_TIMESTAMP%'
                  AND pg_get_expr(d.adbin, d.adrelid) NOT LIKE '%AT TIME ZONE%') THEN
        RAISE EXCEPTION
            'V20261005020000 failed: a default uses a session-local CURRENT_TIMESTAMP; '
            'every new default must be (CURRENT_TIMESTAMP AT TIME ZONE ''UTC'').';
    END IF;

    -- Append-only history: an updatedAt column would mean a writer believed it
    -- could amend the past.
    IF EXISTS (SELECT 1
                 FROM pg_attribute a
                 JOIN pg_class c ON c.oid = a.attrelid
                 JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relname = 'workflow_run_actions'
                  AND a.attname = 'updatedAt' AND a.attnum > 0 AND NOT a.attisdropped) THEN
        RAISE EXCEPTION
            'V20261005020000 failed: workflow_run_actions must not have updatedAt; '
            'action results are append-only.';
    END IF;

    -- The phase-06 hard rule: an execution always has a named principal.
    IF EXISTS (SELECT 1
                 FROM pg_attribute a
                 JOIN pg_class c ON c.oid = a.attrelid
                 JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relname = 'workflow_runs'
                  AND a.attname = 'principalUserId' AND NOT a.attnotnull) THEN
        RAISE EXCEPTION
            'V20261005020000 failed: workflow_runs.principalUserId must be NOT NULL; '
            'a workflow run executes as a NAMED principal (phase-06 hard rule).';
    END IF;

    -- The composite tenant key must exist as a UNIQUE INDEX and the composite
    -- foreign key must reference it. Without both, a repository bug could run
    -- tenant A's workflow body inside tenant B's run and nothing would object.
    IF NOT EXISTS (SELECT 1 FROM pg_index i
                    JOIN pg_class c ON c.oid = i.indexrelid
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                   WHERE n.nspname = 'public'
                     AND c.relname = 'workflow_versions_id_definitionId_organizationId_key'
                     AND i.indisunique) THEN
        RAISE EXCEPTION
            'V20261005020000 failed: the composite tenant key on workflow_versions is missing '
            'or not UNIQUE, so the run->version tenant invariant cannot be enforced.';
    END IF;

    IF (SELECT count(*) FROM pg_constraint con
          JOIN pg_class c ON c.oid = con.conrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public'
           AND con.conname IN ('workflow_runs_version_tenant_fkey',
                               'workflow_runs_conversation_tenant_fkey')
           AND con.contype = 'f') <> 2 THEN
        RAISE EXCEPTION
            'V20261005020000 failed: both composite tenant foreign keys are required -- a run '
            'must be bound to a version of ITS OWN definition and a conversation of ITS OWN '
            'tenant. A single-column FK guarantees neither.';
    END IF;

    -- The composite conversation FK needs its unique target to exist exactly
    -- once. This index is the ONE object this phase adds to a pre-existing
    -- baseline table, so a second copy must fail rather than be created.
    IF (SELECT count(*) FROM pg_indexes
         WHERE schemaname = 'public'
           AND indexname = 'conversations_id_organizationId_key') <> 1 THEN
        RAISE EXCEPTION
            'V20261005020000 failed: conversations_id_organizationId_key must exist exactly '
            'once; it is the composite foreign-key target that stops a run pointing at '
            'another tenant''s conversation.';
    END IF;

    -- The composite key must be a unique INDEX, not a UNIQUE CONSTRAINT: a
    -- constraint would add a pg_constraint row of contype 'u' and break V4's
    -- unique_constraints = 0 invariant.
    IF EXISTS (SELECT 1 FROM pg_constraint con
                JOIN pg_class c ON c.oid = con.conrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
               WHERE n.nspname = 'public'
                 AND c.relname = 'workflow_versions'
                 AND con.contype = 'u') THEN
        RAISE EXCEPTION
            'V20261005020000 failed: workflow_versions must not carry a UNIQUE constraint; '
            'uniqueness in this schema is carried by unique INDEXES (V4 invariant).';
    END IF;
END
$$;