-- =====================================================================
-- outbox-claim.sql
-- CANONICAL OUTBOX / JOB / IDEMPOTENCY STATEMENTS for Phase 5a.
--
-- THIS FILE IS NOT A MIGRATION AND MUST NEVER BE EXECUTED AS ONE.
-- Its name does not match Flyway's `V*.sql` pattern, so Flyway ignores it; it
-- lives outside ops/flyway/sql so it cannot be picked up by accident.
--
-- WHY IT EXISTS
--   The migrations define the CONTRACT (columns, indexes, constraints). This
--   file defines the STATEMENTS that satisfy it. Phase 5b's dispatcher must
--   use these shapes -- in particular the claim predicate and its ordering --
--   or the claim index stops being the right index.
--
-- Conventions
--   * :name is a named parameter. In Spring/JdbcClient bind it as ? or $1.
--   * All timestamps are `timestamp(3) without time zone` (no timestamptz in
--     this schema). now() is timestamptz, so every comparison against a stored
--     timestamp uses (now() AT TIME ZONE 'UTC'): a lease is judged against UTC
--     whatever the connection's TimeZone is. A connection whose TimeZone is not
--     UTC is a defect (V10 reports SHOW TimeZone) and must not be relied on to
--     make this work.
--   * Intervals below are ILLUSTRATIVE. Lease window and backoff come from
--     configuration; the schema deliberately stores no policy.
--   * One statement per block. Each block states its transaction boundary.
-- =====================================================================


-- ---------------------------------------------------------------------
-- 1. ENQUEUE  --  MUST run inside the SAME transaction as the domain change.
-- ---------------------------------------------------------------------
-- This is the whole point of the outbox: if the domain transaction rolls back,
-- no work is published; if it commits, the work is already durable.
--
-- ON CONFLICT ("organizationId","idempotencyKey") DO NOTHING is the duplicate
-- suppression. A conflict means "this exact work is already queued"; it is NOT
-- an error and the caller continues. Returning the row tells the caller
-- whether it won the race (inserted) or found the existing one.
--
-- The payload carries the delivery ENVELOPE (aggregate reference, provider,
-- template/attachment reference, correlation ids). It is never customer prose:
-- the dispatcher reads it and the worker is the only component allowed to
-- resolve customer content. See V20261004000100 Q2.

INSERT INTO public.outbox_events (
        id, "organizationId", "projectId",
        "aggregateType", "aggregateId", "eventType",
        payload, "idempotencyKey", "nextAttemptAt",
        "correlationId", "causationId", "createdAt", "updatedAt")
VALUES (
        :id, :organization_id, :project_id,
        :aggregate_type, :aggregate_id, :event_type,
        CAST(:payload AS jsonb), :idempotency_key,
        (now() AT TIME ZONE 'UTC'),
        :correlation_id, :causation_id,
        (now() AT TIME ZONE 'UTC'), (now() AT TIME ZONE 'UTC'))
ON CONFLICT ("organizationId", "idempotencyKey") DO NOTHING
RETURNING id, state, attempts;


-- ---------------------------------------------------------------------
-- 2. CLAIM (canonical, tenant-scoped)  --  one short transaction per batch.
-- ---------------------------------------------------------------------
-- The Phase-5a predicate, plus the tenant. Tenant scope is mandatory in jobs
-- (AGENTS.md / FR-1.2): the dispatcher enumerates active organisations and
-- claims per tenant, round-robin, which is what makes per-tenant rate limits
-- and queue fairness possible (plan.md Phase 5 item 7). One tenant's backlog
-- must not starve another.
--
-- WHY THE PREDICATE IS EXACTLY THIS
--   state = 'PENDING'              -- not leased, not finished, not dead
--   "nextAttemptAt" <= now()       -- retry backoff has elapsed
--   "leaseUntil" IS NULL OR < now()-- never claimed before, or a lease expired
-- FOR UPDATE SKIP LOCKED           -- two workers never take the same row
-- ORDER BY "nextAttemptAt", "createdAt", id  -- deterministic FIFO, and the
--                                     tie-breakers match the index
--
-- The UPDATE and the SELECT are ONE statement so the claim and the state change
-- are atomic: there is no window in which a row is leased but not owned.
-- RETURNING gives the worker the row it now owns.
--
-- After claiming, the worker MUST re-read the row under tenant scope before
-- acting on it (the claim sweep index is tenant-less by design and is not an
-- authorisation path).

UPDATE public.outbox_events o
   SET state            = 'LEASED',
       "leaseOwner"     = :worker_id,
       "leaseUntil"     = (now() AT TIME ZONE 'UTC') + interval '60 seconds',
       attempts         = o.attempts + 1,
       "lastAttemptAt"  = (now() AT TIME ZONE 'UTC'),
       "updatedAt"      = (now() AT TIME ZONE 'UTC')
  FROM (
        SELECT id
          FROM public.outbox_events
         WHERE "organizationId" = :organization_id
           AND state = 'PENDING'
           AND "nextAttemptAt" <= (now() AT TIME ZONE 'UTC')
           AND ("leaseUntil" IS NULL OR "leaseUntil" < (now() AT TIME ZONE 'UTC'))
         ORDER BY "nextAttemptAt", "createdAt", id
         LIMIT :batch_size
           FOR UPDATE SKIP LOCKED
       ) claimable
 WHERE o.id = claimable.id
RETURNING o.id, o."organizationId", o."projectId", o."aggregateType",
          o."aggregateId", o."eventType", o.payload, o.attempts,
          o."leaseUntil", o."correlationId";


-- ---------------------------------------------------------------------
-- 3. CLAIM (organisation-wide sweep)  --  same predicate, no tenant filter.
-- ---------------------------------------------------------------------
-- Supported by outbox_events_dispatch_sweep_idx ("state","nextAttemptAt",
-- "leaseUntil"). This is a tenant-less SCHEDULING scan: it exposes state and
-- timing only, never payload or tenant data, and it is not an authorisation
-- path. Use it for a single pass over everything due (an operator's "what is
-- overdue" view, or a dispatcher that has not yet adopted per-tenant claiming).
-- Section 2 remains the preferred shape.

UPDATE public.outbox_events o
   SET state            = 'LEASED',
       "leaseOwner"     = :worker_id,
       "leaseUntil"     = (now() AT TIME ZONE 'UTC') + interval '60 seconds',
       attempts         = o.attempts + 1,
       "lastAttemptAt"  = (now() AT TIME ZONE 'UTC'),
       "updatedAt"      = (now() AT TIME ZONE 'UTC')
  FROM (
        SELECT id
          FROM public.outbox_events
         WHERE state = 'PENDING'
           AND "nextAttemptAt" <= (now() AT TIME ZONE 'UTC')
           AND ("leaseUntil" IS NULL OR "leaseUntil" < (now() AT TIME ZONE 'UTC'))
         ORDER BY "nextAttemptAt", "createdAt", id
         LIMIT :batch_size
           FOR UPDATE SKIP LOCKED
       ) claimable
 WHERE o.id = claimable.id
RETURNING o.id, o."organizationId", o.attempts;


-- ---------------------------------------------------------------------
-- 4. COMPLETE  --  handed to the transport successfully.  Terminal.
-- ---------------------------------------------------------------------
UPDATE public.outbox_events
   SET state        = 'SENT',
       "deliveredAt" = (now() AT TIME ZONE 'UTC'),
       "leaseOwner"  = NULL,
       "leaseUntil"  = NULL,
       "updatedAt"   = (now() AT TIME ZONE 'UTC')
 WHERE id = :outbox_event_id
   AND "organizationId" = :organization_id      -- the row is re-read under
   AND state = 'LEASED'                        -- tenant scope, never bare id
   AND "leaseOwner" = :worker_id;              -- only the owner may finish it


-- ---------------------------------------------------------------------
-- 5. RETRYABLE FAILURE  --  back to PENDING with backoff + jitter.
-- ---------------------------------------------------------------------
-- The CHECK constraint outbox_events_lease_pair_check requires owner and expiry
-- to be cleared together, which is why both are set here.
--
-- Backoff and jitter are computed by the caller (:delay) from configuration
-- and from attempts; the schema stores policy nowhere.

UPDATE public.outbox_events
   SET state          = 'PENDING',
       "nextAttemptAt" = (now() AT TIME ZONE 'UTC') + :delay,
       "leaseOwner"    = NULL,
       "leaseUntil"    = NULL,
       "lastError"     = :last_error,
       "updatedAt"     = (now() AT TIME ZONE 'UTC')
 WHERE id = :outbox_event_id
   AND "organizationId" = :organization_id
   AND state = 'LEASED'
   AND "leaseOwner" = :worker_id;


-- ---------------------------------------------------------------------
-- 6. DEAD LETTER  --  non-retryable, or attempts exhausted.  Terminal until
--    an explicit replay.
-- ---------------------------------------------------------------------
-- outbox_events_dead_letter_check REQUIRES deadLetteredAt to be set exactly
-- when state = 'DEAD'. A dead letter without its reason is not an audit trail,
-- so the CHECK is the database's contribution to FR-6.7.

UPDATE public.outbox_events
   SET state               = 'DEAD',
       "deadLetteredAt"    = (now() AT TIME ZONE 'UTC'),
       "deadLetterReason"  = :reason,
       "leaseOwner"        = NULL,
       "leaseUntil"        = NULL,
       "lastError"         = :last_error,
       "updatedAt"         = (now() AT TIME ZONE 'UTC')
 WHERE id = :outbox_event_id
   AND "organizationId" = :organization_id
   AND state = 'LEASED'
   AND "leaseOwner" = :worker_id;


-- ---------------------------------------------------------------------
-- 7. REAP AN EXPIRED LEASE  --  the crashed-worker path.  Repeatable, and the
--    statement is idempotent: re-running it finds no eligible rows.
-- ---------------------------------------------------------------------
-- A worker that dies mid-delivery leaves state='LEASED' with a lease in the
-- past. The claim predicate in sections 2/3 only looks at state='PENDING', so
-- without this statement that row would be stranded forever. Run it on a timer
-- from EVERY worker (it is safe to run concurrently -- SKIP LOCKED) or from a
-- single reaper task. Supported by outbox_events_expired_lease_idx, which is
-- partial on state='LEASED' so only leased rows are indexed.

UPDATE public.outbox_events o
   SET state         = 'PENDING',
       "leaseOwner"  = NULL,
       "leaseUntil"  = NULL,
       "lastError"   = COALESCE(o."lastError", 'lease expired; worker presumed dead'),
       -- Give up nothing: the attempt that died still counts, so the backoff
       -- for the next one is computed from attempts, not from a reset.
       "nextAttemptAt" = (now() AT TIME ZONE 'UTC'),
       "updatedAt"   = (now() AT TIME ZONE 'UTC')
  FROM (
        SELECT id
          FROM public.outbox_events
         WHERE state = 'LEASED'
           AND "leaseUntil" < (now() AT TIME ZONE 'UTC')
         ORDER BY "leaseUntil"
         LIMIT :batch_size
           FOR UPDATE SKIP LOCKED
       ) expired
 WHERE o.id = expired.id
RETURNING o.id, o."organizationId", o.attempts;


-- ---------------------------------------------------------------------
-- 8. REPLAY  --  explicit, audited, and never a mutation of history.
-- ---------------------------------------------------------------------
-- Replaying a DEAD event makes it claimable again and records WHO and WHEN.
-- It does not delete anything, does not reset attempts (the backoff stays
-- monotonic) and does not create a second outbox row: the same id is reused,
-- so a message cannot acquire two independent delivery histories.

UPDATE public.outbox_events
   SET state              = 'PENDING',
       "deadLetteredAt"   = NULL,
       "deadLetterReason" = NULL,
       "nextAttemptAt"    = (now() AT TIME ZONE 'UTC'),
       "replayCount"      = "replayCount" + 1,
       "lastReplayedAt"   = (now() AT TIME ZONE 'UTC'),
       "replayedByUserId" = :user_id,
       "updatedAt"        = (now() AT TIME ZONE 'UTC')
 WHERE id = :outbox_event_id
   AND "organizationId" = :organization_id
   AND state = 'DEAD';

-- The replay itself is an attempt, like any other, and belongs in job_attempts
-- (section 9). Replays are never silent.