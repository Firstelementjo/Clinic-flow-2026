-- =====================================================================
-- V20261004000700__job_attempts.sql
-- ADDITIVE, NON-DESTRUCTIVE.
--
-- VERSION NUMBERING -- WHY THIS IS NOT 202610040007
--   Flyway orders versions NUMERICALLY, not lexically. This chain is 14
--   digits (yyyyMMddHHmmss), the Phase-2 drafts were 12, and a 12-digit
--   version sorts BEFORE the 14-digit baseline: 202610040001 is
--   202,610,040,001, which is far smaller than 20,260,814,000,000. A
--   draft-numbered migration therefore runs against an EMPTY database before
--   the schema it depends on exists. The fixture proved it: Flyway applied
--   the outbox migration first and failed with
--   `relation "public.organizations" does not exist`. The promoted files keep
--   the draft ordinal in the seconds field (007 -> 00:07:00). See the header
--   of V20261004000100__outbox.sql for the whole chain, and note that EVERY
--   remaining ops/flyway/pending/*.sql.todo must be renumbered to 14 digits
--   when it is promoted.
--
-- Promoted from the deleted draft
-- ops/flyway/pending/V202610040007__job_attempts.sql.todo
-- (Phase 5a, Agent B, 2026-10-04). That draft was inert by construction
-- (`.sql.todo` is not matched by Flyway's `V*.sql` pattern). This file is the
-- promoted, executable form; the draft no longer exists.
--
-- WHY THIS MIGRATION EXISTS
--   A durable record of every worker attempt, so queue lag, retry counts,
--   dead letters, replay and saturation are observable WITHOUT database console
--   access (FR-6.7: an operator must be able to diagnose a failed message,
--   webhook or export without database access). Redis queue state cannot do
--   this job: it is a transport, it is volatile, and it cannot answer "what
--   happened to this customer's message" after the fact.
--
--   Requirements: PROJECT_REQUIREMENTS.md FR-10.7, FR-10.8, FR-6.7;
--   plan.md section 7.3 (jobs / outbox / retries / dead letter / replay).
--
-- WHAT IT CREATES
--   1. enum  public.JobAttemptOutcome
--   2. enum  public.DeadLetterStatus
--   3. table public.job_attempts     -- append-only attempt history
--   4. table public.job_dead_letters -- the operator lifecycle of a dead job
--
-- APPEND-ONLY, ENFORCED BY SHAPE
--   job_attempts has NO updatedAt and NO mutable state column: an attempt is a
--   fact about the past. A retry writes a NEW row with the next
--   attemptNumber. This is why it carries createdAt only, and why every
--   invariant below can be stated as "a replay creates a row, it never mutates
--   one". The migration's own guard fails if an updatedAt ever appears.
--
-- INVARIANTS (recorded here because DDL cannot enforce them)
--   * Replay is explicit, audited and idempotent: a replay creates a NEW
--     job_attempts row with isReplay = true and replayOfId set to the failed
--     attempt, and it never updates the failed attempt.
--   * job_dead_letters.payloadSummary is a SUMMARY and must never receive the
--     outbox payload (FR-10.6). The outbox payload is the customer content
--     boundary; this table is deliberately outside it.
--   * jobId is a TRANSPORT id (BullMQ/Redis), not a business key, so it gets no
--     foreign key. outboxEventId is the added, guaranteed link back to the
--     durable row an operator must be able to open.
--
-- OPEN QUESTIONS FROM THE DRAFT -- RESOLVED
--
--   Q1 "Retention window for attempts vs dead letters (dead letters should
--       outlive attempts)."
--       RESOLVED AS: no retention behaviour is created, for the same reason as
--       V20261004000100 Q1 -- a retention policy is a destructive, audited,
--       tenant-scoped purge that needs the PROJECT_REQUIREMENTS section 7
--       data-lifecycle decision and Phase 7's typed-deletion flow. The ordering
--       the draft wanted (dead letters outlive attempts) is a PURGE ORDER, not
--       a schema fact, and is recorded as a product decision. What the schema
--       does provide is that the two tables are separate, so the two windows
--       can differ without a migration later.
--
--   Q2 "Volume estimate: at 5 workers and legacy BullMQ volumes this is the
--       highest-write table in the system. Partition by month?"
--       RESOLVED AS: NO partitioning in this migration. Reasons, in order:
--         (a) partitioning is not reversible additively -- undoing it means a
--             table rewrite, which would violate this phase's rules;
--         (b) a partitioned parent needs every query to carry the partition key
--             or scan every partition, and this table is read by tenant-scoped
--             admin queries whose predicates are (organizationId, createdAt)
--             windows chosen by the caller, not by a fixed month boundary;
--         (c) a UNIQUE constraint on a partitioned table must include the
--             partition key, which would change the tenant-scoped uniqueness
--             contract below.
--       The migration instead keeps write amplification bounded: four indexes,
--       all tenant-led, and no repeated payload column. If volume later forces
--       partitioning, that is a rewrite with a full backup, row-count and
--       checksum comparison first -- a decision for the phase that has the
--       measured numbers, not a guess made before any row exists.
--
--   Q3 "Do dead letters live here or in the outbox migration? Recommend here: a
--       dead letter is a JOB fact, not an event."
--       RESOLVED AS: agreed, and the split is now explicit because the two are
--       genuinely different facts:
--         * outbox_events.state = 'DEAD' is the EVENT's own terminal state,
--           with deadLetteredAt / deadLetterReason on the event row.
--         * job_dead_letters is the QUEUE JOB's operator lifecycle -- OPEN,
--           REPLAYED, RESOLVED, DISCARDED -- which an enum value on a single
--           row cannot express (a replayed job can fail again and must return
--           to OPEN without losing its history).
--       One dead-letter row per (organizationId, queueName, jobId): a replay
--       updates that row's replayCount/lastReplayedAt instead of inserting a
--       duplicate entry, so the queue cannot grow a second dead-letter row per
--       retry.
--
-- DRAFT-SKETCH NAMING -- CHANGED, AND WHY
--   * job_dead_letters.status keeps its draft name and becomes the enum-typed
--     lifecycle column "status" of type DeadLetterStatus, so the value list is
--     a contract instead of a comment. It is NOT renamed to "state": Phase 5a
--     fixed "state" as the outbox vocabulary, and a dead letter is a different
--     lifecycle with its own type name; renaming it later would be a migration
--     for no behaviour change. Recorded so Phase 5b is not surprised by two
--     column names for two lifecycles.
--   * payloadSummary and attempts keep their draft names.
--
-- TIMESTAMPS
--   timestamp(3) WITHOUT time zone everywhere, like all 98 baseline timestamp
--   columns; defaults use (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') so the stored
--   wall clock is UTC regardless of the connection's TimeZone. Bind
--   LocalDateTime, never Instant/OffsetDateTime.
--
-- INDEX RATIONALE (FR-1.3)
--   job_attempts_organizationId_queueName_jobId_attemptNumber_key  UNIQUE
--       ("organizationId","queueName","jobId","attemptNumber")
--       Tenant-scoped attempt numbering. Deliberately NOT globally unique: a
--       global index would let one tenant's write collide with another's and
--       disclose its existence through a constraint violation, and it would
--       also make transport-id reuse across tenants impossible.
--   job_attempts_organizationId_projectId_createdAt_idx
--       The admin job view (FR-6.7), newest first, tenant-scoped.
--   job_attempts_organizationId_queueName_outcome_createdAt_idx
--       Queue health for ONE tenant: failures per queue over time.
--   job_attempts_organizationId_outcome_createdAt_idx
--       The failure dashboard, tenant-scoped like every other read path.
--   job_dead_letters_organizationId_queueName_jobId_key UNIQUE
--       One dead-letter row per job identity (draft Q3).
--   job_dead_letters_organizationId_projectId_status_createdAt_idx
--       The dead-letter queue an operator works through, tenant-scoped.
--   job_dead_letters_organizationId_outboxEventId_idx
--       "Open the dead letter, then open the event it failed to deliver" -- the
--       drill-down that makes FR-6.7 possible without database access.
--
--   Every index on these two tables leads with organizationId. There is
--   deliberately NO tenant-less index here: nothing in this slice needs to
--   sweep attempts across tenants, and a global attempt index would be a
--   cross-tenant read path for no benefit.
--
-- SAFETY
--   * CREATE TYPE / CREATE TABLE only. No DROP, no RENAME, no ALTER of an
--     existing object, no data change.
--   * Strict, not IF NOT EXISTS: a pre-existing object must fail the migration
--     loudly rather than be silently adopted.
--   * FKs only where a parent is guaranteed: organizations, projects, users,
--     job_attempts (self, for replayOfId) and outbox_events (V20261004000100,
--     always earlier in the chain). jobId is deliberately NOT a foreign key.
--   * Rollback: DROP TABLE job_dead_letters, DROP TABLE job_attempts, DROP TYPE
--     DeadLetterStatus, DROP TYPE JobAttemptOutcome -- rehearsed on a throwaway
--     database only (ops/flyway/rollback/undo_phase5a.sql).
--   * The CHECK constraints below are the only ones this schema has; each
--     encodes an invariant no correct writer can violate, so they cannot reject
--     a valid write.
-- =====================================================================

CREATE TYPE "public"."JobAttemptOutcome" AS ENUM (
    'SUCCEEDED',
    'RETRYABLE_FAILURE',
    'NON_RETRYABLE_FAILURE',
    'CANCELLED',
    'SKIPPED'
);

CREATE TYPE "public"."DeadLetterStatus" AS ENUM (
    'OPEN',
    'REPLAYED',
    'RESOLVED',
    'DISCARDED'
);

-- ---------------------------------------------------------------------
-- job_attempts: append-only history of every attempt.
-- ---------------------------------------------------------------------
CREATE TABLE "public"."job_attempts" (
    "id"             uuid NOT NULL,
    "organizationId" uuid NOT NULL,
    "projectId"      uuid,
    "queueName"      text NOT NULL,
    "jobType"        text NOT NULL,
    "jobId"          text NOT NULL,
    "attemptNumber"  integer NOT NULL,
    "outcome"        "public"."JobAttemptOutcome" NOT NULL,
    "retryClass"     text,
    "errorCode"      text,
    "errorMessage"   text,
    "startedAt"      timestamp(3) without time zone NOT NULL,
    "finishedAt"     timestamp(3) without time zone,
    "durationMs"     integer,
    "workerId"       text NOT NULL,
    "correlationId"  text,
    "causationId"    text,
    "isReplay"       boolean DEFAULT false NOT NULL,
    "replayOfId"     uuid,
    "outboxEventId"  uuid,
    "createdAt"      timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    CONSTRAINT "job_attempts_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "job_attempts_attempt_number_check" CHECK ("attemptNumber" >= 1),
    CONSTRAINT "job_attempts_duration_check" CHECK ("durationMs" IS NULL OR "durationMs" >= 0),
    -- A row can only be marked a replay if it names what it replays. This is
    -- what makes "replay creates a row, never mutates one" checkable.
    CONSTRAINT "job_attempts_replay_check"
        CHECK (("isReplay" = false) OR ("replayOfId" IS NOT NULL))
);

ALTER TABLE ONLY "public"."job_attempts"
    ADD CONSTRAINT "job_attempts_organizationId_fkey"
    FOREIGN KEY ("organizationId") REFERENCES "public"."organizations"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."job_attempts"
    ADD CONSTRAINT "job_attempts_projectId_fkey"
    FOREIGN KEY ("projectId") REFERENCES "public"."projects"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."job_attempts"
    ADD CONSTRAINT "job_attempts_replayOfId_fkey"
    FOREIGN KEY ("replayOfId") REFERENCES "public"."job_attempts"("id")
    ON UPDATE CASCADE ON DELETE SET NULL;

ALTER TABLE ONLY "public"."job_attempts"
    ADD CONSTRAINT "job_attempts_outboxEventId_fkey"
    FOREIGN KEY ("outboxEventId") REFERENCES "public"."outbox_events"("id")
    ON UPDATE CASCADE ON DELETE SET NULL;

-- ---------------------------------------------------------------------
-- job_dead_letters: the operator lifecycle of a dead queue job.
-- ---------------------------------------------------------------------
CREATE TABLE "public"."job_dead_letters" (
    "id"               uuid NOT NULL,
    "organizationId"   uuid NOT NULL,
    "projectId"        uuid,
    "queueName"        text NOT NULL,
    "jobId"            text NOT NULL,
    "outboxEventId"    uuid,
    "lastAttemptId"    uuid,
    "reason"           text NOT NULL,
    -- A SUMMARY, never the payload: this is the row an operator copies into a
    -- report, so it is where FR-10.6 is enforced by convention.
    "payloadSummary"   jsonb DEFAULT '{}'::"jsonb" NOT NULL,
    "attempts"         integer NOT NULL,
    "status"           "public"."DeadLetterStatus" DEFAULT 'OPEN'::"public"."DeadLetterStatus" NOT NULL,
    "resolvedByUserId" uuid,
    "resolvedAt"       timestamp(3) without time zone,
    "resolutionNote"   text,
    "replayCount"      integer DEFAULT 0 NOT NULL,
    "lastReplayedAt"   timestamp(3) without time zone,
    "createdAt"        timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    "updatedAt"        timestamp(3) without time zone NOT NULL,
    CONSTRAINT "job_dead_letters_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "job_dead_letters_attempts_check" CHECK ("attempts" >= 1),
    CONSTRAINT "job_dead_letters_replay_count_check" CHECK ("replayCount" >= 0),
    -- OPEN/REPLAYED rows are not resolved; RESOLVED/DISCARDED rows must say who
    -- and when. An unattributable resolution is not an audit trail.
    CONSTRAINT "job_dead_letters_resolution_check"
        CHECK ((("status" IN ('RESOLVED', 'DISCARDED'))
                AND "resolvedAt" IS NOT NULL
                AND "resolvedByUserId" IS NOT NULL)
            OR (("status" IN ('OPEN', 'REPLAYED')) AND "resolvedAt" IS NULL))
);

ALTER TABLE ONLY "public"."job_dead_letters"
    ADD CONSTRAINT "job_dead_letters_organizationId_fkey"
    FOREIGN KEY ("organizationId") REFERENCES "public"."organizations"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."job_dead_letters"
    ADD CONSTRAINT "job_dead_letters_projectId_fkey"
    FOREIGN KEY ("projectId") REFERENCES "public"."projects"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."job_dead_letters"
    ADD CONSTRAINT "job_dead_letters_lastAttemptId_fkey"
    FOREIGN KEY ("lastAttemptId") REFERENCES "public"."job_attempts"("id")
    ON UPDATE CASCADE ON DELETE SET NULL;

ALTER TABLE ONLY "public"."job_dead_letters"
    ADD CONSTRAINT "job_dead_letters_resolvedByUserId_fkey"
    FOREIGN KEY ("resolvedByUserId") REFERENCES "public"."users"("id")
    ON UPDATE CASCADE ON DELETE SET NULL;

ALTER TABLE ONLY "public"."job_dead_letters"
    ADD CONSTRAINT "job_dead_letters_outboxEventId_fkey"
    FOREIGN KEY ("outboxEventId") REFERENCES "public"."outbox_events"("id")
    ON UPDATE CASCADE ON DELETE SET NULL;

-- ---------------------------------------------------------------------
-- indexes
-- ---------------------------------------------------------------------
CREATE UNIQUE INDEX "job_attempts_organizationId_queueName_jobId_attemptNumber_key"
    ON "public"."job_attempts" USING "btree" ("organizationId", "queueName", "jobId", "attemptNumber");

CREATE INDEX "job_attempts_organizationId_projectId_createdAt_idx"
    ON "public"."job_attempts" USING "btree" ("organizationId", "projectId", "createdAt");

CREATE INDEX "job_attempts_organizationId_queueName_outcome_createdAt_idx"
    ON "public"."job_attempts" USING "btree" ("organizationId", "queueName", "outcome", "createdAt");

CREATE INDEX "job_attempts_organizationId_outcome_createdAt_idx"
    ON "public"."job_attempts" USING "btree" ("organizationId", "outcome", "createdAt");

CREATE UNIQUE INDEX "job_dead_letters_organizationId_queueName_jobId_key"
    ON "public"."job_dead_letters" USING "btree" ("organizationId", "queueName", "jobId");

CREATE INDEX "job_dead_letters_organizationId_projectId_status_createdAt_idx"
    ON "public"."job_dead_letters" USING "btree" ("organizationId", "projectId", "status", "createdAt");

CREATE INDEX "job_dead_letters_organizationId_outboxEventId_idx"
    ON "public"."job_dead_letters" USING "btree" ("organizationId", "outboxEventId");

-- Post-condition guard: fail loudly rather than leave a queue whose history is
-- silently incomplete. Index existence is checked in pg_indexes, NOT with
-- to_regclass(): an index is a pg_class row of relkind 'i' and is not a
-- regclass-visible relation, so to_regclass() returns NULL for it and the guard
-- would reject a migration that did everything correctly.
DO $$
DECLARE
    missing text;
BEGIN
    SELECT string_agg(x, ', ')
      INTO missing
      FROM unnest(ARRAY[
               'job_attempts_organizationId_queueName_jobId_attemptNumber_key',
               'job_attempts_organizationId_projectId_createdAt_idx',
               'job_attempts_organizationId_queueName_outcome_createdAt_idx',
               'job_attempts_organizationId_outcome_createdAt_idx',
               'job_dead_letters_organizationId_queueName_jobId_key',
               'job_dead_letters_organizationId_projectId_status_createdAt_idx',
               'job_dead_letters_organizationId_outboxEventId_idx'
           ]) AS x
     WHERE NOT EXISTS (SELECT 1 FROM pg_indexes
                        WHERE schemaname = 'public' AND indexname = x);

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'V20261004000700 failed: missing job indexes: %', missing;
    END IF;

    IF (SELECT string_agg(e.enumlabel, ',' ORDER BY e.enumsortorder)
          FROM pg_type t
          JOIN pg_enum e ON e.enumtypid = t.oid
          JOIN pg_namespace n ON n.oid = t.typnamespace
         WHERE n.nspname = 'public' AND t.typname = 'JobAttemptOutcome')
       IS DISTINCT FROM 'SUCCEEDED,RETRYABLE_FAILURE,NON_RETRYABLE_FAILURE,CANCELLED,SKIPPED' THEN
        RAISE EXCEPTION 'V20261004000700 failed: public.JobAttemptOutcome value list drifted.';
    END IF;

    IF (SELECT string_agg(e.enumlabel, ',' ORDER BY e.enumsortorder)
          FROM pg_type t
          JOIN pg_enum e ON e.enumtypid = t.oid
          JOIN pg_namespace n ON n.oid = t.typnamespace
         WHERE n.nspname = 'public' AND t.typname = 'DeadLetterStatus')
       IS DISTINCT FROM 'OPEN,REPLAYED,RESOLVED,DISCARDED' THEN
        RAISE EXCEPTION 'V20261004000700 failed: public.DeadLetterStatus value list drifted.';
    END IF;

    -- job_attempts is append-only history: an updatedAt column would mean a
    -- writer believed it could amend the past.
    IF EXISTS (SELECT 1
                 FROM pg_attribute a
                 JOIN pg_class c ON c.oid = a.attrelid
                 JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relname = 'job_attempts'
                  AND a.attname = 'updatedAt' AND a.attnum > 0 AND NOT a.attisdropped) THEN
        RAISE EXCEPTION
            'V20261004000700 failed: job_attempts must not have updatedAt; attempts are append-only.';
    END IF;

    IF EXISTS (SELECT 1
                 FROM pg_attribute a
                 JOIN pg_class c ON c.oid = a.attrelid
                 JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public'
                  AND c.relname IN ('job_attempts', 'job_dead_letters')
                  AND a.attname = 'organizationId' AND NOT a.attnotnull) THEN
        RAISE EXCEPTION
            'V20261004000700 failed: "organizationId" must be NOT NULL on both job tables (FR-1.2).';
    END IF;
END
$$;