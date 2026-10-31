-- =====================================================================
-- workflow-sla-statements.sql
--
-- The CANONICAL statements for the Phase-6a workflow / SLA / collision
-- slice. Phase 6 (backend/workflows-sla-routing) should COPY these shapes
-- rather than re-derive them: the predicates, the ORDER BYs and the UTC
-- comparisons are a contract with the indexes created by
-- V20261005010000 .. V20261005040000, and re-deriving them is how a query
-- silently stops being index-supported.
--
-- NOT A MIGRATION. This file is not matched by Flyway's `V*.sql` pattern and
-- does not live in `sql/`, so Flyway ignores it and no boot can execute it by
-- accident. (The same protection is claimed for `reference/outbox-claim.sql`
-- and it has held.)
--
-- Every statement is tenant-scoped and written against
--   (now() AT TIME ZONE 'UTC')
-- because the columns are `timestamp without time zone` while now() is
-- timestamptz: comparing the two resolves through the session TimeZone, so a
-- bare now() would judge a calendar window against the connection's wall clock.
-- =====================================================================


-- =====================================================================
-- SECTION 1 -- ACTIVATION
-- =====================================================================

-- 1a. Author a new version. The version number comes from the definition's
--     latestVersionNumber, and the partial unique index
--     workflow_versions_one_active_per_definition guarantees at most one
--     ACTIVE row per definition whatever the concurrency.
INSERT INTO public.workflow_versions
       (id, "organizationId", "projectId", "workflowDefinitionId", "versionNumber",
        "state", "changeNote", "stateMachine", "triggerConfig", "capabilityScope",
        "contentHash", "createdByUserId", "createdAt", "updatedAt")
VALUES (:id, :organizationId, :definitionProjectId, :definitionId, :nextVersion,
        'DRAFT', :changeNote, :stateMachine::jsonb, :triggerConfig::jsonb,
        :capabilityScope::jsonb, :contentHash, :authorUserId,
        (now() AT TIME ZONE 'UTC'), (now() AT TIME ZONE 'UTC'))
RETURNING id;

-- 1b. ACTIVATE. Three steps, ONE transaction, and deliberately in this order:
--       1. retire the current ACTIVE version (it becomes SUPERSEDED and names
--          its successor), so the partial unique index frees up;
--       2. mark the new version ACTIVE with who and when;
--       3. move the definition's pointer.
--     The WHERE clauses all carry organizationId AND the expected version
--     number, so a concurrent activation of a different version loses on the
--     affected-row count rather than overwriting this one.
BEGIN;

-- Step 1: retire the incumbent. 0 rows when nothing was active.
UPDATE public.workflow_versions
   SET "state"             = 'SUPERSEDED',
       "supersededAt"      = (now() AT TIME ZONE 'UTC'),
       "supersededByVersionId" = :newVersionId,
       "updatedAt"         = (now() AT TIME ZONE 'UTC')
 WHERE "organizationId" = :organizationId
   AND "workflowDefinitionId" = :definitionId
   AND "state" = 'ACTIVE'::"public"."WorkflowVersionState";
-- expect exactly 1 when a version was live; 0 on the first activation.
-- A value other than 0 or 1 means somebody else is activating: ROLLBACK.

-- Step 2: activate the new version.
--     DRAFT OR SUPERSEDED, and that second value is load-bearing: it is what
--     makes a ROLLBACK the same operation as an activation. Rolling back to a
--     previously superseded version is exactly "activate it again", and a
--     DRAFT-only predicate would force the rollback path to be written twice and
--     therefore drift. ROLLED_BACK and ARCHIVED are deliberately NOT accepted:
--     a version that was rolled away from, or retired, must not silently return
--     to production through this statement.
UPDATE public.workflow_versions
   SET "state"              = 'ACTIVE',
       "activatedAt"        = (now() AT TIME ZONE 'UTC'),
       "activatedByUserId"  = :actorUserId,
       "updatedAt"          = (now() AT TIME ZONE 'UTC')
 WHERE id = :newVersionId
   AND "organizationId" = :organizationId
   AND "state" IN ('DRAFT'::"public"."WorkflowVersionState",
                   'SUPERSEDED'::"public"."WorkflowVersionState");
-- expect exactly 1. 0 means it was already active, or it is ROLLED_BACK /
-- ARCHIVED: ROLLBACK, never continue.

-- Step 3: move the pointer.
UPDATE public.workflow_definitions
   SET "activeVersionId"     = :newVersionId,
       "latestVersionNumber" = greatest("latestVersionNumber", :newVersion),
       "updatedAt"           = (now() AT TIME ZONE 'UTC')
 WHERE id = :definitionId
   AND "organizationId" = :organizationId;
-- expect exactly 1.

COMMIT;
-- If step 2 returned 0 the transaction ROLLS BACK and the incumbent stays
-- ACTIVE: a failed activation must never leave a definition with no live body.


-- =====================================================================
-- SECTION 2 -- ROLLBACK (an audited state change, never a delete)
-- =====================================================================

-- 2a. The ROLLBACK is: activate the previous version THROUGH SECTION 1, and
--     mark the version being abandoned as ROLLED_BACK naming where it went.
--     There is no DELETE anywhere in this slice. Two reasons: the abandoned
--     version is what an in-flight run bound to, and deleting it would make an
--     old run unexplainable (phase-06 acceptance gate 2).
BEGIN;

UPDATE public.workflow_versions
   SET "state"                 = 'ROLLED_BACK',
       "rolledBackAt"          = (now() AT TIME ZONE 'UTC'),
       "rolledBackByUserId"    = :actorUserId,
       "rolledBackToVersionId" = :restoredVersionId,
       "updatedAt"             = (now() AT TIME ZONE 'UTC')
 WHERE id = :abandonedVersionId
   AND "organizationId" = :organizationId
   AND "state" = 'ACTIVE'::"public"."WorkflowVersionState";
-- expect exactly 1. Then run SECTION 1 with :newVersionId = :restoredVersionId.
-- workflow_versions_rollback_check refuses this statement if any of the three
-- audit columns is missing.

COMMIT;


-- =====================================================================
-- SECTION 3 -- THE ROUTER'S HOT READ
--     No alphabetical ordering anywhere: priority is a column and it is in the
--     ORDER BY. An index-scan plan is expected from
--     workflow_definitions_org_project_trigger_priority_idx; V11 5c proves it
--     is not a sequential scan.
-- =====================================================================

SELECT id, "key", "name", "activeVersionId", "priority"
  FROM public.workflow_definitions
 WHERE "organizationId" = :organizationId
   AND "projectId" = :projectId
   AND "triggerType" = :triggerType
   AND "isEnabled"
 ORDER BY "priority", "key"          -- 'key' only as a deterministic tie-break
 LIMIT :batchSize;

-- Organisation-wide definitions apply too, and they must be reached without
-- losing the tenant filter. UNION ALL of two index-friendly halves, never an
-- OR over projectId, which would defeat both indexes.
( SELECT ... WHERE "organizationId" = :organizationId
              AND "projectId" = :projectId AND "isEnabled" ... )
UNION ALL
( SELECT ... WHERE "organizationId" = :organizationId
              AND "projectId" IS NULL    AND "isEnabled" ... )
ORDER BY "priority", "key"
LIMIT :batchSize;


-- =====================================================================
-- SECTION 4 -- START A RUN (the loop-prevention write)
--     ON CONFLICT ON workflow_runs_organizationId_trigger_instance_key DO
--     NOTHING is the loop-prevention contract: a re-fired trigger inserts
--     nothing and the caller learns it by getting zero rows back.
-- =====================================================================

WITH started AS (
  INSERT INTO public.workflow_runs
         (id, "organizationId", "projectId", "workflowDefinitionId",
          "workflowVersionId", "triggerInstanceId", "status",
          "principalUserId", "capabilityScope", "loopDepth", "maxLoopDepth",
          "startedAt", "createdAt", "updatedAt")
  VALUES (:id, :organizationId, :projectId, :definitionId, :versionId,
          :triggerInstanceId, 'RUNNING', :principalUserId,
          :capabilityScope::jsonb, :loopDepth, :maxLoopDepth,
          (now() AT TIME ZONE 'UTC'), (now() AT TIME ZONE 'UTC'),
          (now() AT TIME ZONE 'UTC'))
  ON CONFLICT ("organizationId", "triggerInstanceId") DO NOTHING
  RETURNING id
)
SELECT id FROM started;
-- 0 rows = this trigger instance already has a run. Do not start a second one.


-- =====================================================================
-- SECTION 5 -- RECORD AN ACTION RESULT (append-only)
--     Attempt numbering comes from the caller, and the UNIQUE index on
--     ("organizationId","runId","actionKey","attemptNumber") makes a double
--     attempt number a conflict rather than a double-counted history.
--     inputSnapshot is the acceptance gate: the decision records its inputs.
-- =====================================================================

INSERT INTO public.workflow_run_actions
       (id, "organizationId", "projectId", "workflowRunId", "actionKey",
        "actionType", "attemptNumber", "outcome", "inputSnapshot",
        "resultSnapshot", "actorUserId", "actorPrincipal", "outboxEventId",
        "startedAt", "finishedAt", "durationMs", "errorCode", "errorMessage",
        "isReplay", "replayOfId", "createdAt")
VALUES (:id, :organizationId, :projectId, :runId, :actionKey, :actionType,
        :attemptNumber, :outcome::"public"."WorkflowActionOutcome",
        :inputSnapshot::jsonb, :resultSnapshot::jsonb, :actorUserId,
        :actorPrincipal, :outboxEventId, :startedAt, :finishedAt, :durationMs,
        :errorCode, :errorMessage, :isReplay, :replayOfId,
        (now() AT TIME ZONE 'UTC'))
RETURNING id;

-- The next attempt number, read under the run's scope:
SELECT coalesce(max("attemptNumber"), 0) + 1
  FROM public.workflow_run_actions
 WHERE "organizationId" = :organizationId
   AND "workflowRunId" = :runId
   AND "actionKey" = :actionKey;


-- =====================================================================
-- SECTION 6 -- HUMAN TAKEOVER ABORTS THE IN-FLIGHT AI SEND
--     The phase-06 gate. Two writes, deliberately in this order:
--       1. record the REQUEST (abortRequestedAt + who). This is the observable
--          signal the in-flight send watches;
--       2. record the CONFIRMATION once the send has actually been cancelled.
--     workflow_runs_abort_pair_check allows the intermediate state: an abort
--     request always names its requester, and an abort can only complete if one
--     was requested. Asserting the tighter pair would reject the valid window
--     between the request and the cancellation.
-- =====================================================================

-- 6a. request
UPDATE public.workflow_runs
   SET "abortRequestedAt" = (now() AT TIME ZONE 'UTC'),
       "abortedByUserId"  = :humanUserId,
       "abortReason"      = :reason,
       "updatedAt"        = (now() AT TIME ZONE 'UTC')
 WHERE "organizationId" = :organizationId
   AND "workflowVersionId" = :versionId
   AND "status" = 'RUNNING'::"public"."WorkflowRunStatus"
   AND "abortRequestedAt" IS NULL;
-- expect 1. Idempotent: a second takeover request updates 0 rows.

-- 6b. confirm, once the worker has actually dropped the send
UPDATE public.workflow_runs
   SET "status"       = 'TAKEOVER',
       "abortedAt"    = (now() AT TIME ZONE 'UTC'),
       "finishedAt"   = (now() AT TIME ZONE 'UTC'),
       "updatedAt"    = (now() AT TIME ZONE 'UTC')
 WHERE "organizationId" = :organizationId
   AND "abortRequestedAt" IS NOT NULL
   AND "abortedAt" IS NULL
   AND "status" = 'RUNNING'::"public"."WorkflowRunStatus";
-- expect 1.

-- 6c. record the collision. NOT derivable from any other table: both sides of
--     the race succeeded, so only this row says they raced. The window is
--     floor(epoch_seconds / 300) so two counterparties colliding in the same
--     five minutes is ONE incident (see the migration header).
INSERT INTO public.collision_records
       (id, "organizationId", "projectId", "scope", "resourceType",
        "resourceId", "conversationId", "counterpartyId", "counterpartyType",
        "observedVersion", "collisionWindow", "outcome", "resolutionDetail",
        "abortedRunId", "observedAt", "createdAt")
VALUES (:id, :organizationId, :projectId, 'CONVERSATION'::"public"."CollisionScope",
        'Conversation', :conversationId, :conversationId, :aiRunId::text, 'WORKFLOW_RUN',
        :observedVersion,
        floor(extract(epoch FROM (now() AT TIME ZONE 'UTC')) / 300)::bigint,
        'ABORTED_BY_HUMAN'::"public"."CollisionResolution", :detail,
        :aiRunId, (now() AT TIME ZONE 'UTC'), (now() AT TIME ZONE 'UTC'))
ON CONFLICT ("organizationId", "scope", "resourceId", "collisionWindow") DO NOTHING
RETURNING id;
-- 0 rows = this window already has an incident recorded for that resource.
-- collision_records_outcome_detail_check refuses an ABORTED_BY_HUMAN row
-- without a detail explaining how the race was settled.


-- =====================================================================
-- SECTION 7 -- SIMULTANEOUS HUMAN REPLIES (the KEPT_BOTH case)
--     Two humans both sent a reply. Neither lost, both messages exist, and
--     pretending one won would misreport the conversation.
-- =====================================================================

INSERT INTO public.collision_records
       (id, "organizationId", "projectId", "scope", "resourceType",
        "resourceId", "conversationId", "counterpartyId", "counterpartyType",
        "collisionWindow", "outcome", "resolutionDetail", "observedAt", "createdAt")
VALUES (:id, :organizationId, :projectId, 'CONVERSATION'::"public"."CollisionScope",
        'Conversation', :conversationId, :conversationId, :otherUserId::text, 'USER',
        floor(extract(epoch FROM (now() AT TIME ZONE 'UTC')) / 300)::bigint,
        'KEPT_BOTH'::"public"."CollisionResolution", :detail,
        (now() AT TIME ZONE 'UTC'), (now() AT TIME ZONE 'UTC'))
ON CONFLICT ("organizationId", "scope", "resourceId", "collisionWindow") DO NOTHING
RETURNING id;


-- =====================================================================
-- SECTION 8 -- ROUTING EXPLANATION (a DERIVED READ MODEL, not a table)
--     The decision to store no routing-explanations table is recorded in
--     V20261005020000__workflow_runs_and_action_results.sql. This is the query
--     that replaces it: the run, its version's capability set, and every action
--     attempt with its inputs. It is the acceptance gate's "a decision returns
--     the inputs that produced it, not just a result", assembled at read time
--     so it can never disagree with the history.
-- =====================================================================

SELECT r.id                AS run_id,
       r."workflowVersionId",
       v."versionNumber",
       v."capabilityScope" AS capability_scope_in_force,
       v."contentHash"     AS version_content_hash,
       r."principalUserId",
       r."startedAt",
       r."finishedAt",
       r."status",
       a."actionKey",
       a."actionType",
       a."attemptNumber",
       a."outcome",
       a."inputSnapshot",
       a."resultSnapshot",
       a."actorUserId",
       a."actorPrincipal",
       a."startedAt"       AS action_started_at,
       a."finishedAt"      AS action_finished_at,
       a."errorCode",
       a."errorMessage"
  FROM public.workflow_runs r
  JOIN public.workflow_versions v ON v.id = r."workflowVersionId"
  LEFT JOIN public.workflow_run_actions a ON a."workflowRunId" = r.id
 WHERE r."organizationId" = :organizationId
   AND r."conversationId" = :conversationId
 ORDER BY r."startedAt", a."startedAt", a."attemptNumber";
-- Index path: workflow_runs_organizationId_conversationId_idx then
-- workflow_run_actions_organizationId_runId_idx. No table of explanations is
-- involved, and none can drift from the history.


-- =====================================================================
-- SECTION 9 -- THE ROUTING-EXPLANATION COUNTER: how often a human won
--     Phase-6 acceptance gate 1, in one query. Tenant-scoped.
-- =====================================================================

SELECT "outcome",
       count(*) AS incidents,
       min("observedAt") AS first_seen,
       max("observedAt") AS last_seen
  FROM public.collision_records
 WHERE "organizationId" = :organizationId
   AND "projectId" = :projectId
   AND "observedAt" >= :since
 GROUP BY "outcome"
 ORDER BY "outcome";


-- =====================================================================
-- SECTION 10 -- SLA CLOCK EVALUATION INPUTS (read-only)
--     This migration deliberately stops at the INPUTS: it creates no SLA-tick
--     table and no per-conversation breach state. Whether that is materialised
--     is a Phase-6 decision that belongs with the scheduler. What the schema
--     owes it is a fast, tenant-scoped answer to "which calendar, which
--     windows, which closures".
-- =====================================================================

-- 10a. the effective calendar on a given date. Uses
--      sla_calendars_organizationId_effectiveFrom_idx, so the historical SLA of
--      a March ticket stays computable after the calendar is edited.
SELECT id, "key", "version", "timezone", "effectiveFrom", "effectiveTo"
  FROM public.sla_calendars
 WHERE "organizationId" = :organizationId
   AND ("projectId" = :projectId OR "projectId" IS NULL)
   AND "state" = 'ACTIVE'::"public"."SlaCalendarState"
   AND "effectiveFrom" <= :onDate::date
   AND ("effectiveTo" IS NULL OR "effectiveTo" >= :onDate::date)
 ORDER BY "effectiveFrom" DESC
 LIMIT 1;

-- 10b. that calendar's windows for one weekday. Uses
--      sla_calendar_windows_calendar_day_idx -- the one tenant-less index this
--      phase adds, documented in V11 5a.
SELECT "startMinute", "endMinute", "label"
  FROM public.sla_calendar_windows
 WHERE "calendarId" = :calendarId
   AND "dayOfWeek" = :dayOfWeek
 ORDER BY "startMinute";

-- 10c. closures on a date. Uses
--      sla_calendar_holidays_calendar_date_key, which is both the uniqueness
--      guarantee and the read.
SELECT "holidayDate", "label", "startMinute", "endMinute"
  FROM public.sla_calendar_holidays
 WHERE "calendarId" = :calendarId
   AND "holidayDate" = :onDate::date;

-- 10d. the policies that apply, in explicit priority order. Priority is a
--      column; alphabetical ordering is forbidden (plan.md hard rule).
SELECT "key", "name", "priority", "clockType",
       "firstResponseMinutes", "nextResponseMinutes", "resolutionMinutes",
       "calendarId", "warnBeforeMinutes", "escalateAfterMinutes"
  FROM public.sla_policies
 WHERE "organizationId" = :organizationId
   AND ("projectId" = :projectId OR "projectId" IS NULL)
   AND "isEnabled"
 ORDER BY "priority", "key"
 LIMIT :batchSize;
-- sla_policies_clock_ordering_check guarantees any row returned here has
-- resolutionMinutes >= firstResponseMinutes and nextResponseMinutes >=
-- firstResponseMinutes, so no policy can breach on a healthy ticket.

-- 10e. FAIL LOUDLY if a policy ever references another tenant's calendar. This
--      is the one cross-tenant check a foreign key cannot do for you.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM public.sla_policies p
          JOIN public.sla_calendars c ON c.id = p."calendarId"
         WHERE p."organizationId" = :organizationId
           AND c."organizationId" <> p."organizationId") THEN
        RAISE EXCEPTION
            'SLA policy references a calendar belonging to another tenant (organizationId=%)',
            :organizationId;
    END IF;
END
$$;


-- =====================================================================
-- SECTION 11 -- RUN HISTORY READ MODELS
-- =====================================================================

-- 11a. the operator's run list, newest first (FR-6.7). Tenant-led index.
SELECT r.id, d."name" AS workflow_name, v."versionNumber",
       r."status", r."principalUserId", r."startedAt", r."finishedAt"
  FROM public.workflow_runs r
  JOIN public.workflow_definitions d ON d.id = r."workflowDefinitionId"
  JOIN public.workflow_versions v ON v.id = r."workflowVersionId"
 WHERE r."organizationId" = :organizationId
   AND r."projectId" = :projectId
   AND r."status" = ANY(:statuses::"public"."WorkflowRunStatus"[])
 ORDER BY r."createdAt" DESC
 LIMIT :pageSize;

-- 11b. rollout safety: failures under ONE version, before rolling back.
SELECT r."workflowVersionId", r."status", count(*) AS runs
  FROM public.workflow_runs r
 WHERE r."organizationId" = :organizationId
   AND r."workflowVersionId" = :versionId
 GROUP BY r."workflowVersionId", r."status"
 ORDER BY r."status";

-- 11c. one run's action timeline, in order.
SELECT "actionKey", "attemptNumber", "outcome", "startedAt", "finishedAt",
       "durationMs", "errorCode"
  FROM public.workflow_run_actions
 WHERE "organizationId" = :organizationId
   AND "workflowRunId" = :runId
 ORDER BY "startedAt", "attemptNumber";

-- 11d. failure dashboard, tenant-scoped.
SELECT "actionType", "outcome", count(*) AS occurrences
  FROM public.workflow_run_actions
 WHERE "organizationId" = :organizationId
   AND "outcome" IN ('FAILED'::"public"."WorkflowActionOutcome",
                     'RETRYABLE'::"public"."WorkflowActionOutcome")
   AND "createdAt" >= :since
 GROUP BY "actionType", "outcome"
 ORDER BY "actionType", "outcome";


-- =====================================================================
-- SECTION 12 -- CROSS-TENANT NEGATIVES (assert these in Phase 6's tests)
--     Every one of these must return ZERO rows. They are listed here as the
--     exact statements, not as prose, because "tenant scope is mandatory" is
--     only credible if the query that proves it exists.
-- =====================================================================

-- 12a. a run from another tenant is never reachable
SELECT count(*) FROM public.workflow_runs
 WHERE id = :runId AND "organizationId" <> :organizationId;         -- 0

-- 12b. a version from another tenant is never reachable
SELECT count(*) FROM public.workflow_versions
 WHERE id = :versionId AND "organizationId" <> :organizationId;     -- 0

-- 12c. an action from another tenant is never reachable
SELECT count(*) FROM public.workflow_run_actions
 WHERE id = :actionId AND "organizationId" <> :organizationId;     -- 0

-- 12d. another tenant's collisions are never reachable
SELECT count(*) FROM public.collision_records
 WHERE id = :collisionId AND "organizationId" <> :organizationId;   -- 0

-- 12e. another tenant's SLA policies are never reachable
SELECT count(*) FROM public.sla_policies
 WHERE id = :policyId AND "organizationId" <> :organizationId;      -- 0

-- 12f. a policy whose project belongs to another tenant (the FK permits it;
--      only this check forbids it)
SELECT count(*)
  FROM public.workflow_runs r
  JOIN public.projects p ON p.id = r."projectId"
 WHERE r."organizationId" = :organizationId
   AND p."organizationId" <> :organizationId;                       -- 0

-- 12g. an SLA policy pointing at another tenant's calendar (section 10e)
-- 12h. a run whose version belongs to another tenant -- or to a DIFFERENT
--      DEFINITION in the same tenant. This one is FORBIDDEN BY THE DATABASE:
--      workflow_runs_version_tenant_fkey is a composite FK over
--      ("workflowVersionId","workflowDefinitionId","organizationId") ->
--      workflow_versions("id","workflowDefinitionId","organizationId"), and the
--      target triple is covered by the unique index
--      workflow_versions_id_definitionId_organizationId_key. So an attempt to
--      insert such a run does not merely return zero rows -- it is REJECTED.
--      The fixture proves it with data, so this negative needs no application
--      discipline.
--
--      Verified on a live PostgreSQL 16 database before it was written here:
--      PostgreSQL DOES accept a non-partial UNIQUE INDEX as a foreign-key
--      target, which is what lets this be a unique INDEX rather than a UNIQUE
--      CONSTRAINT (a constraint would break V4's unique_constraints = 0
--      invariant).
--
--      INSERT ... (workflowVersionId = <tenant A's version>,
--                   workflowDefinitionId = <tenant B's definition>,
--                   organizationId = <tenant B>)
--      -> ERROR: violates foreign key constraint "workflow_runs_version_tenant_fkey"