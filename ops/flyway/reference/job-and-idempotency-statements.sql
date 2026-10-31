-- =====================================================================
-- job-and-idempotency-statements.sql
-- CANONICAL job_attempts / job_dead_letters / idempotency_records STATEMENTS
-- for Phase 5a. Companion to outbox-claim.sql.
--
-- THIS FILE IS NOT A MIGRATION AND MUST NEVER BE EXECUTED AS ONE. Its name
-- does not match Flyway's `V*.sql` pattern and it lives outside
-- ops/flyway/sql, so Flyway ignores it.
--
-- Conventions: :name parameters, timestamp(3) without time zone,
-- (now() AT TIME ZONE 'UTC') for every comparison, intervals illustrative.
-- Every statement below carries "organizationId" in its predicate. A bare id
-- is not an authorisation check: a client-supplied or remembered id must be
-- re-read under tenant scope before it is acted on.
-- =====================================================================


-- ---------------------------------------------------------------------
-- 1. RECORD AN ATTEMPT  --  append only, own transaction or the delivery
--    transaction. There is no UPDATE path and no updatedAt column, because an
--    attempt is a fact about the past.
-- ---------------------------------------------------------------------
-- attemptNumber comes from the caller (it is the count of prior attempts for
-- this job) and the unique index
-- job_attempts_organizationId_queueName_jobId_attemptNumber_key turns a duplicate
-- write into a constraint violation instead of a second history entry. That is
-- the whole idempotency story for a retrying worker.
--
-- outboxEventId links the attempt to the durable row an operator must be able
-- to open; it is NULL for work that has no outbox row (a scheduled sweep).

INSERT INTO public.job_attempts (
        id, "organizationId", "projectId",
        "queueName", "jobType", "jobId", "attemptNumber",
        outcome, "retryClass", "errorCode", "errorMessage",
        "startedAt", "finishedAt", "durationMs",
        "workerId", "correlationId", "causationId",
        "isReplay", "replayOfId", "outboxEventId", "createdAt")
VALUES (
        :id, :organization_id, :project_id,
        :queue_name, :job_type, :job_id, :attempt_number,
        CAST(:outcome AS "public"."JobAttemptOutcome"),
        :retry_class, :error_code, :error_message,
        :started_at, :finished_at, :duration_ms,
        :worker_id, :correlation_id, :causation_id,
        :is_replay, :replay_of_id, :outbox_event_id,
        (now() AT TIME ZONE 'UTC'));


-- ---------------------------------------------------------------------
-- 2. RECORD A REPLAY ATTEMPT  --  the same INSERT, and that is the whole
--    mechanism.
-- ---------------------------------------------------------------------
-- job_attempts_replay_check REQUIRES isReplay = true to come with a
-- replayOfId, so a replay cannot be recorded without naming what it replays.
-- The failed attempt is never modified.


-- ---------------------------------------------------------------------
-- 3. UPSERT THE DEAD LETTER  --  one row per (organization, queue, job).
-- ---------------------------------------------------------------------
-- The unique index job_dead_letters_organizationId_queueName_jobId_key means a
-- job that dies, is replayed and dies again updates ONE row instead of growing
-- a second entry per retry: attempts accumulates, replayCount accumulates, and
-- status returns to OPEN because the job is broken again.
--
-- payloadSummary is a SUMMARY (counts, template id, provider, error class) and
-- must never contain the outbox payload or customer text (FR-10.6). It is
-- written by the worker, not read back into a message.
--
-- The resolution CHECK: moving to RESOLVED/DISCARDED requires resolvedAt and
-- resolvedByUserId. An unattributable resolution is not an audit trail.

INSERT INTO public.job_dead_letters (
        id, "organizationId", "projectId",
        "queueName", "jobId", "outboxEventId", "lastAttemptId",
        reason, "payloadSummary", attempts,
        "createdAt", "updatedAt")
VALUES (
        :id, :organization_id, :project_id,
        :queue_name, :job_id, :outbox_event_id, :last_attempt_id,
        :reason, CAST(:payload_summary AS jsonb), :attempts,
        (now() AT TIME ZONE 'UTC'), (now() AT TIME ZONE 'UTC'))
ON CONFLICT ("organizationId", "queueName", "jobId") DO UPDATE
   SET "lastAttemptId"  = EXCLUDED."lastAttemptId",
       "outboxEventId"  = EXCLUDED."outboxEventId",
       reason           = EXCLUDED.reason,
       "payloadSummary" = EXCLUDED."payloadSummary",
       attempts         = public.job_dead_letters.attempts + EXCLUDED.attempts,
       status           = 'OPEN'::"public"."DeadLetterStatus",
       "resolvedByUserId" = NULL,
       "resolvedAt"     = NULL,
       "resolutionNote" = NULL,
       "replayCount"    = public.job_dead_letters."replayCount" + 1,
       "lastReplayedAt" = (now() AT TIME ZONE 'UTC'),
       "updatedAt"      = (now() AT TIME ZONE 'UTC');


-- 3a. RESOLVE / DISCARD (an operator action, always audited):

UPDATE public.job_dead_letters
   SET status            = :status,                    -- RESOLVED | DISCARDED
       "resolvedByUserId" = :user_id,
       "resolvedAt"       = (now() AT TIME ZONE 'UTC'),
       "resolutionNote"   = :note,
       "updatedAt"        = (now() AT TIME ZONE 'UTC')
 WHERE id = :dead_letter_id
   AND "organizationId" = :organization_id;

-- 3b. MOVE A DEAD LETTER TO REPLAYED (the replay has been requested; the job
--     is not yet re-failed). Idempotent: re-running it changes nothing.

UPDATE public.job_dead_letters
   SET status          = 'REPLAYED'::"public"."DeadLetterStatus",
       "lastReplayedAt" = (now() AT TIME ZONE 'UTC'),
       "updatedAt"      = (now() AT TIME ZONE 'UTC')
 WHERE id = :dead_letter_id
   AND "organizationId" = :organization_id
   AND status = 'OPEN'::"public"."DeadLetterStatus";


-- ---------------------------------------------------------------------
-- 4. IDEMPOTENCY: reserve the key BEFORE doing the work.
-- ---------------------------------------------------------------------
-- The duplicate arrives while the first is still running: it finds IN_PROGRESS
-- with a lock in the future and is told the operation is already in flight
-- (HTTP 409). A record whose lock has expired means the original caller died
-- and may be taken over. A COMPLETED/FAILED record is replayed to the caller
-- from responseStatus + responseBodyHash.
--
-- ON CONFLICT DO NOTHING + RETURNING is the reservation: the winner inserts,
-- the loser gets no row back and re-reads (section 5). Do not SELECT-then-INSERT
-- -- that is the race Phase 4 found and fixed on messages.

INSERT INTO public.idempotency_records (
        id, "organizationId", "projectId",
        scope, "idempotencyKey", "requestFingerprint",
        state, "lockExpiresAt",
        "correlationId", "expiresAt", "createdAt", "updatedAt")
VALUES (
        :id, :organization_id, :project_id,
        :scope, :idempotency_key, :request_fingerprint,
        'IN_PROGRESS'::"public"."IdempotencyState",
        (now() AT TIME ZONE 'UTC') + interval '30 seconds',
        :correlation_id, :expires_at,
        (now() AT TIME ZONE 'UTC'), (now() AT TIME ZONE 'UTC'))
ON CONFLICT ("organizationId", scope, "idempotencyKey") DO NOTHING
RETURNING id, state;


-- 4a. COMPLETE. The CHECK requires responseStatus exactly when state is
--     COMPLETED, so a finished record cannot be written without its result.

UPDATE public.idempotency_records
   SET state            = 'COMPLETED'::"public"."IdempotencyState",
       "responseStatus" = :response_status,
       "responseBodyHash" = :response_body_hash,
       "resourceType"   = :resource_type,
       "resourceId"     = :resource_id,
       "lockExpiresAt"  = NULL,
       "updatedAt"      = (now() AT TIME ZONE 'UTC')
 WHERE id = :id
   AND "organizationId" = :organization_id
   AND state = 'IN_PROGRESS'::"public"."IdempotencyState";


-- 4b. TERMINAL FAILURE. Terminal for this key: the caller gets the stored
--     answer, and a genuine retry uses a new key. That is the documented
--     behaviour, not an accident of the CHECK constraints.

UPDATE public.idempotency_records
   SET state           = 'FAILED'::"public"."IdempotencyState",
       "resourceType"  = :resource_type,
       "lockExpiresAt"  = NULL,
       "updatedAt"      = (now() AT TIME ZONE 'UTC')
 WHERE id = :id
   AND "organizationId" = :organization_id
   AND state = 'IN_PROGRESS'::"public"."IdempotencyState";


-- 4c. THE LOOKUP every retry starts with. Tenant-scoped: the unique index is
--     ("organizationId", scope, "idempotencyKey"), so this is one index probe
--     and it can never return another tenant's record.
--
--     requestFingerprint must be compared by the CALLER: same key + different
--     fingerprint is a client bug or an attempt to reuse someone else's result,
--     and must be refused rather than silently served the stored response.

SELECT id, state, "requestFingerprint", "responseStatus", "responseBodyHash",
       "resourceType", "resourceId", "lockExpiresAt", "expiresAt"
  FROM public.idempotency_records
 WHERE "organizationId" = :organization_id
   AND scope          = :scope
   AND "idempotencyKey" = :idempotency_key;


-- ---------------------------------------------------------------------
-- 5. READ MODELS (tenant-scoped; FR-6.7 without database access)
-- ---------------------------------------------------------------------
-- What is due right now, per tenant. Uses the claim index.
-- SELECT id, "aggregateType", "aggregateId", "eventType", attempts,
--        "nextAttemptAt", "leaseUntil"
--   FROM public.outbox_events
--  WHERE "organizationId" = :organization_id
--    AND state = 'PENDING'
--    AND "nextAttemptAt" <= (now() AT TIME ZONE 'UTC')
--    AND ("leaseUntil" IS NULL OR "leaseUntil" < (now() AT TIME ZONE 'UTC'))
--  ORDER BY "nextAttemptAt", "createdAt", id
--  LIMIT :batch_size;

-- Leases that have expired and are waiting to be reaped. Uses the partial
-- index. An operator seeing rows here is seeing a worker that died.
-- SELECT id, "organizationId", "leaseOwner", "leaseUntil", attempts
--   FROM public.outbox_events
--  WHERE state = 'LEASED' AND "leaseUntil" < (now() AT TIME ZONE 'UTC')
--  ORDER BY "leaseUntil";

-- "What happened to THIS message?" Uses the tenant-led aggregate index.
-- SELECT state, attempts, "nextAttemptAt", "deliveredAt",
--        "deadLetteredAt", "deadLetterReason", "lastError", "replayCount"
--   FROM public.outbox_events
--  WHERE "organizationId" = :organization_id
--    AND "aggregateType" = :aggregate_type
--    AND "aggregateId" = :aggregate_id
--  ORDER BY "createdAt";

-- Queue health for one tenant. Uses the tenant-led queue index.
-- SELECT "queueName", outcome, count(*) AS attempts, min("createdAt"), max("createdAt")
--   FROM public.job_attempts
--  WHERE "organizationId" = :organization_id
--    AND "createdAt" >= :since
--  GROUP BY "queueName", outcome;

-- The dead-letter queue an operator works through. Tenant-scoped.
-- SELECT id, "queueName", "jobId", reason, attempts, "replayCount", "createdAt"
--   FROM public.job_dead_letters
--  WHERE "organizationId" = :organization_id
--    AND status = 'OPEN'::"public"."DeadLetterStatus"
--  ORDER BY "createdAt";