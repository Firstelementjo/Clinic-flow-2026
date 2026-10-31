-- =====================================================================
-- V20261004000100__outbox.sql
-- ADDITIVE, IDEMPOTENT-BY-VERSION, NON-DESTRUCTIVE.
--
-- VERSION NUMBERING -- WHY THIS IS NOT 202610040001
--   Flyway orders versions NUMERICALLY, not lexically. This chain is
--   14 digits (yyyyMMddHHmmss): 20260814000000, 20260814000100. The Phase-2
--   DRAFTS were 12 digits: 202610040001 ... 202610040009. 202610040001 is
--   202,610,040,001, which is much SMALLER than 20,260,814,000,000, so a
--   draft-numbered migration sorts BEFORE the baseline. The fixture caught it
--   exactly: Flyway applied this outbox migration to an EMPTY database first
--   and failed with `relation "public.organizations" does not exist`.
--   The promoted files therefore use 14 digits and keep the draft ordinal in
--   the seconds field (001 -> 00:01:00), so the chain is strictly ascending
--   and the draft number is still visible:
--       20260814000000  baseline
--       20260814000100  vector indexes
--       20261004000100  outbox              (draft 001)
--       20261004000700  job attempts        (draft 007)
--       20261004001000  idempotency records (new)
--   EVERY remaining ops/flyway/pending/*.sql.todo still carries 12 digits and
--   MUST be renumbered to 14 digits when it is promoted, or it will run before
--   the baseline on a fresh database.
--
-- Promoted from the deleted draft
-- ops/flyway/pending/V202610040001__outbox.sql.todo
-- (Phase 5a, Agent B, 2026-10-04). That draft was an inert design sketch:
-- `.sql.todo` is not matched by Flyway's `V*.sql` pattern, so it could never
-- be executed. This file is the promoted, reviewed, executable form and the
-- draft no longer exists -- its content and every open question are recorded
-- below.
--
-- WHY THIS MIGRATION EXISTS
--   Durable, at-least-once delivery of outbound work. Rows are written in the
--   SAME transaction as the domain change that caused them, so a rollback can
--   never publish work for a change that did not happen, and a commit can
--   never lose the work it promised. Redis is a cache and a queue transport,
--   never the source of truth (plan.md, "Redis is not the source of truth");
--   this table is.
--
--   Requirements: PROJECT_REQUIREMENTS.md FR-4.6 (durable async delivery),
--   FR-10.7 (outbox / jobs / retries / dead letter / replay / idempotency
--   records operational), section 5.
--
-- WHAT IT CREATES
--   1. enum  public.OutboxState   (PENDING, LEASED, SENT, DEAD)
--   2. table public.outbox_events
--   3. indexes, all listed with the query each one serves (see INDEX RATIONALE)
--
-- STATE MACHINE (authoritative; Phase 5b must not invent a fourth state)
--
--            +---------------------------------------------+
--            |                                             |
--      claim |  PENDING --(lease)--> LEASED --(ack)--> SENT   [terminal]
--            |     ^                  |
--            |     |                  | (failure, retryable, attempts < max)
--            |     +------------------+   -> state=PENDING,
--            |        nextAttemptAt = now() + backoff + jitter
--            |
--            +--(failure, non-retryable OR attempts >= max)---> DEAD  [terminal
--                                                                   until replay]
--
--   * PENDING  eligible for claim. Retry state is carried by (attempts,
--              nextAttemptAt, lastError) -- NOT by a state value.
--   * LEASED   claimed by a worker; leaseOwner + leaseUntil are set. A worker
--              that dies leaves an expired lease, which the reaper statement in
--              ops/flyway/reference/outbox-claim.sql returns to PENDING. This
--              is why the lease is a column pair and not the LEASED state alone.
--   * SENT     handed to the transport successfully. deliveredAt is set.
--   * DEAD     dead lettered: deadLetteredAt + deadLetterReason are set. The row
--              is never deleted here; replay is an explicit, audited state
--              change (replayCount / lastReplayedAt / replayedByUserId).
--
-- OPEN QUESTIONS FROM THE DRAFT -- RESOLVED
--
--   Q1 "Retention. Dead letters must outlive a replay window, but must not
--       grow forever. Needs PROJECT_REQUIREMENTS section 7 sign-off."
--       RESOLVED AS: no retention behaviour is created here, deliberately.
--       Nothing in this migration deletes a row. A retention policy is a
--       destructive, audited, tenant-scoped purge that needs the section 7
--       data-lifecycle decision and the Phase 7 typed-deletion flow; shipping
--       it as a trigger or a rule inside DDL would make an unreviewed deletion
--       permanent. What the schema owes that decision is the state that makes
--       a purge safe to express: SENT and DEAD rows are terminal, their age is
--       in createdAt/deliveredAt/deadLetteredAt, and DEAD rows are identifiable
--       by state without reading the payload. UNRESOLVED IN PRODUCT TERMS and
--       carried as a follow-up: the actual window is a product decision.
--
--   Q2 "Payload redaction policy: a channel message body is customer content.
--       Confirm what the dispatcher is allowed to see versus what only the
--       worker may read."
--       RESOLVED AS: the payload column is required to carry the delivery
--       ENVELOPE (aggregate reference, provider, template/attachment
--       references, correlation ids), not customer prose. That is a policy on
--       writers, not something DDL can enforce, so this migration records it
--       and the fixture proves the envelope shape. job_dead_letters
--       (V202610040007) is where the hard rule lives: payloadSummary is a
--       summary and must never receive the payload. FR-10.6 remains an
--       application responsibility; a future Phase 7 redaction review may add a
--       column-level policy.
--
--   Q3 "Exactly-once: idempotencyKey uniqueness gives at-least-once delivery
--       with duplicate suppression, not exactly-once external side effects."
--       RESOLVED AS: agreed and unchanged. This table promises AT LEAST ONCE
--       plus duplicate suppression on enqueue (see the unique index below).
--       Exactly-once at a provider is impossible from here; it is Phase 5b's
--       provider-idempotency work, proven by the "worker crash after external
--       send does not duplicate on retry" acceptance test. Nothing in this
--       schema claims otherwise.
--
--   Q4 "Does projectId stay nullable? Organization-wide jobs (connector
--       reconnection, rotation) have no project."
--       RESOLVED AS: YES, nullable, and deliberately so. organizationId is NOT
--       NULL on every row (FR-1.2) and is the scope that authorises a read; a
--       NULL projectId means ORGANISATION-WIDE work, never "unknown scope". The
--       baseline already carries exactly two nullable projectId columns
--       (audit_logs, notifications) for the same reason, so this is not a new
--       exemption class -- but V10_outbox_job_slice.sql lists it explicitly so
--       it cannot quietly widen.
--
-- DRAFT-SKETCH NAMING -- CHANGED, AND WHY
--   The draft sketch used status / availableAt / leaseExpiresAt. This migration
--   uses state / nextAttemptAt / leaseUntil because that is the vocabulary the
--   Phase 5a request fixed for the claim predicate
--       WHERE state = 'PENDING' AND "nextAttemptAt" <= now()
--         AND ("leaseUntil" IS NULL OR "leaseUntil" < now())
--   One name per concept, shared by the migration, the dispatcher and the
--   fixture. Quoting convention is unchanged from the baseline: snake_case
--   table names, quoted camelCase columns (ADR-0002). Spring persistence must
--   map EXPLICITLY; there is no naming strategy.
--
--   The draft also listed a FAILED state. It is NOT created: "failed but
--   retryable" is exactly (attempts, nextAttemptAt, lastError) and "failed
--   permanently" is DEAD, so a FAILED value would have no query that needs it
--   and no transition that does not duplicate a column. Adding a value later is
--   additive; removing one is not.
--
-- TIMESTAMPS
--   timestamp(3) WITHOUT time zone, matching all 98 timestamp columns in the
--   baseline (V6 asserts no timestamptz anywhere). Bind LocalDateTime, never
--   Instant/OffsetDateTime.
--   now() is timestamptz; comparing it to a `timestamp without time zone`
--   column resolves through the session TimeZone. The canonical statements in
--   ops/flyway/reference/outbox-claim.sql therefore use
--   (now() AT TIME ZONE 'UTC') and this migration's defaults use
--   (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'), so a lease is judged against UTC
--   regardless of the connection's zone. Every connection must also be UTC
--   (AGENTS.md forbids silent timezone shifts) -- V10 reports SHOW TimeZone.
--
-- INDEX RATIONALE (FR-1.3: the tenant filter leads, or the index is not
-- tenant scope at all and says so)
--   outbox_events_organizationId_idempotencyKey_key  UNIQUE ("organizationId","idempotencyKey")
--       Tenant-scoped duplicate suppression on enqueue. Deliberately NOT a
--       global unique key: a global index would let tenant B learn that tenant
--       A already used a provider event id from the unique-violation message,
--       and provider ids are only guaranteed unique per tenant.
--   outbox_events_claim_idx  ("organizationId","state","nextAttemptAt","leaseUntil")
--       The canonical claim. Tenant-led so the dispatcher can claim per tenant,
--       which is what per-tenant rate limits and queue fairness (plan.md Phase
--       5, item 7) require: one tenant's backlog must not starve another.
--   outbox_events_dispatch_sweep_idx  ("state","nextAttemptAt","leaseUntil")
--       The same predicate WITHOUT a tenant filter, for the organisation-wide
--       sweep and for an operator looking at "what is due". This is an
--       intentional tenant-less SCHEDULING index: it exposes only state and
--       timing, never payload, tenant data or customer content, and it is not
--       an authorisation path. A claimed row MUST be re-read under tenant
--       scope before it is acted on. V9's census therefore now reports two
--       global indexes, this one and the pre-existing sessions_expiresAt_idx;
--       both are recorded in docs/migration/phases/phase-05a.md.
--   outbox_events_expired_lease_idx  ("leaseUntil") WHERE "state" = 'LEASED'
--       Partial: only rows currently under lease are indexed, so the
--       crashed-worker reaper stays cheap without adding write amplification to
--       the PENDING/SENT majority.
--   outbox_events_organizationId_projectId_state_createdAt_idx
--       ("organizationId","projectId","state","createdAt")
--       The admin outbox read model (FR-6.7): a tenant-scoped list newest first.
--   outbox_events_organizationId_aggregate_idx
--       ("organizationId","aggregateType","aggregateId")
--       "What happened to THIS message?" -- the drill-down an operator needs
--       after a delivery failure, tenant-led like every other read path.
--
-- SAFETY
--   * CREATE TABLE / CREATE TYPE only. No DROP, no RENAME, no ALTER of an
--     existing object, no data change, no TRUNCATE, no UPDATE of legacy rows.
--   * Strict, not CREATE ... IF NOT EXISTS: if an outbox_events already exists,
--     this migration must FAIL LOUDLY rather than silently adopt an unknown
--     shape. (V20260814000100 uses IF NOT EXISTS only because it reconciles an
--     index name that may already exist.)
--   * createdAt/updatedAt follow the baseline convention exactly: createdAt
--     defaults to CURRENT_TIMESTAMP, updatedAt is NOT NULL with no default, so
--     a writer that forgets it fails loudly and transactionally rather than
--     writing an unstamped row.
--   * FKs are ON UPDATE CASCADE ON DELETE CASCADE for the tenant/project scope,
--     matching 40+ baseline tables, because a typed tenant/project deletion
--     must be able to remove delivery evidence as well as pending work (privacy
--     deletion). No tenant deletion may be performed until the section 7
--     lifecycle decision exists; that is a process gate, not a schema one.
--   * Rollback: DROP TABLE outbox_events (drops its indexes) and DROP TYPE
--     OutboxState. Rehearsed on a throwaway database only -- see
--     ops/flyway/rollback/undo_phase5a.sql. It is safe only while nothing has
--     been cut over to write through the outbox.
--   * Uniqueness is carried by unique INDEXES, not unique CONSTRAINTS, to match
--     the 53 existing unique indexes and keep V4's `unique_constraints = 0`
--     invariant true. Enforcement is identical and ON CONFLICT inference still
--     works.
--   * Three CHECK constraints are the only ones this schema has; they encode
--     invariants no writer should ever violate (see the table definition).
--   * Index and constraint names are kept under PostgreSQL's 63-byte identifier
--     limit. An over-long name is silently TRUNCATED, so the name in the guard
--     below would not match the created object and the migration would fail on
--     its own index -- which is the intended loud failure, but a name that can
--     never work must not be written in the first place. (The baseline's
--     truncated names, e.g.
--     conversations_organizationId_projectId_assignedEmployeeId_l_idx, are
--     `prisma db push` output and are left exactly as they are.)
--   * The post-condition guard looks indexes up in pg_indexes, NOT with
--     to_regclass(): an index is a pg_class row of relkind 'i' and is not a
--     regclass-visible relation, so to_regclass() returns NULL for it and the
--     guard would reject a migration that did everything correctly.
-- =====================================================================

CREATE TYPE "public"."OutboxState" AS ENUM (
    'PENDING',
    'LEASED',
    'SENT',
    'DEAD'
);

CREATE TABLE "public"."outbox_events" (
    "id"                  uuid NOT NULL,
    "organizationId"      uuid NOT NULL,
    "projectId"           uuid,
    "aggregateType"       text NOT NULL,
    "aggregateId"         uuid NOT NULL,
    "eventType"           text NOT NULL,
    "state"               "public"."OutboxState" DEFAULT 'PENDING'::"public"."OutboxState" NOT NULL,
    "payload"             jsonb DEFAULT '{}'::"jsonb" NOT NULL,
    "idempotencyKey"      text NOT NULL,
    "attempts"            integer DEFAULT 0 NOT NULL,
    "nextAttemptAt"       timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    "lastAttemptAt"       timestamp(3) without time zone,
    "leaseOwner"          text,
    "leaseUntil"          timestamp(3) without time zone,
    "deliveredAt"         timestamp(3) without time zone,
    "deadLetteredAt"      timestamp(3) without time zone,
    "deadLetterReason"    text,
    "replayCount"         integer DEFAULT 0 NOT NULL,
    "lastReplayedAt"      timestamp(3) without time zone,
    "replayedByUserId"    uuid,
    "lastError"           text,
    "correlationId"       text,
    "causationId"         text,
    "createdAt"           timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    "updatedAt"           timestamp(3) without time zone NOT NULL,
    CONSTRAINT "outbox_events_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "outbox_events_attempts_check" CHECK ("attempts" >= 0),
    CONSTRAINT "outbox_events_replay_count_check" CHECK ("replayCount" >= 0),
    CONSTRAINT "outbox_events_lease_pair_check"
        CHECK ((("leaseOwner" IS NULL) = ("leaseUntil" IS NULL))),
    CONSTRAINT "outbox_events_delivered_state_check"
        CHECK (("deliveredAt" IS NULL) = ("state" <> 'SENT')),
    CONSTRAINT "outbox_events_dead_letter_check"
        CHECK (("deadLetteredAt" IS NULL) = ("state" <> 'DEAD'))
);

-- Tenant scope. organizationId is NOT NULL (FR-1.2) on every row, always.
ALTER TABLE ONLY "public"."outbox_events"
    ADD CONSTRAINT "outbox_events_organizationId_fkey"
    FOREIGN KEY ("organizationId") REFERENCES "public"."organizations"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

-- Project scope. NULL means organisation-wide work (draft Q4), never unknown.
ALTER TABLE ONLY "public"."outbox_events"
    ADD CONSTRAINT "outbox_events_projectId_fkey"
    FOREIGN KEY ("projectId") REFERENCES "public"."projects"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

-- Replay is an audited action, so it names the user who performed it.
ALTER TABLE ONLY "public"."outbox_events"
    ADD CONSTRAINT "outbox_events_replayedByUserId_fkey"
    FOREIGN KEY ("replayedByUserId") REFERENCES "public"."users"("id")
    ON UPDATE CASCADE ON DELETE SET NULL;

-- Duplicate suppression on enqueue, scoped to the tenant. ON CONFLICT
-- ("organizationId","idempotencyKey") DO NOTHING is the enqueue contract.
CREATE UNIQUE INDEX "outbox_events_organizationId_idempotencyKey_key"
    ON "public"."outbox_events" USING "btree" ("organizationId", "idempotencyKey");

-- The canonical claim, tenant-led (per-tenant fairness and rate limits).
CREATE INDEX "outbox_events_claim_idx"
    ON "public"."outbox_events" USING "btree" ("organizationId", "state", "nextAttemptAt", "leaseUntil");

-- The same predicate with no tenant filter, for the org-wide sweep and for
-- operators. Scheduling columns only; never an authorisation path.
CREATE INDEX "outbox_events_dispatch_sweep_idx"
    ON "public"."outbox_events" USING "btree" ("state", "nextAttemptAt", "leaseUntil");

-- Crashed-worker reaper. Partial so only leased rows are indexed.
CREATE INDEX "outbox_events_expired_lease_idx"
    ON "public"."outbox_events" USING "btree" ("leaseUntil")
    WHERE "state" = 'LEASED';

-- Admin outbox read model (FR-6.7).
CREATE INDEX "outbox_events_organizationId_projectId_state_createdAt_idx"
    ON "public"."outbox_events" USING "btree" ("organizationId", "projectId", "state", "createdAt");

-- "What happened to this message?" drill-down, tenant-led.
CREATE INDEX "outbox_events_organizationId_aggregate_idx"
    ON "public"."outbox_events" USING "btree" ("organizationId", "aggregateType", "aggregateId");

-- Post-condition guard. Fail the migration loudly instead of leaving a
-- half-created queue that a dispatcher would misread.
DO $$
DECLARE
    missing text;
BEGIN
    SELECT string_agg(x, ', ')
      INTO missing
      FROM unnest(ARRAY[
               'outbox_events_organizationId_idempotencyKey_key',
               'outbox_events_claim_idx',
               'outbox_events_dispatch_sweep_idx',
               'outbox_events_expired_lease_idx',
               'outbox_events_organizationId_projectId_state_createdAt_idx',
               'outbox_events_organizationId_aggregate_idx'
           ]) AS x
     WHERE NOT EXISTS (SELECT 1 FROM pg_indexes
                         WHERE schemaname = 'public' AND indexname = x);

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'V20261004000100 failed: missing outbox indexes: %', missing;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_type t
                     JOIN pg_namespace n ON n.oid = t.typnamespace
                    WHERE n.nspname = 'public' AND t.typname = 'OutboxState') THEN
        RAISE EXCEPTION 'V20261004000100 failed: public.OutboxState was not created.';
    END IF;

    -- The enum value list IS the contract; a drift here changes dispatcher
    -- behaviour silently.
    IF (SELECT string_agg(e.enumlabel, ',' ORDER BY e.enumsortorder)
          FROM pg_type t
          JOIN pg_enum e ON e.enumtypid = t.oid
          JOIN pg_namespace n ON n.oid = t.typnamespace
         WHERE n.nspname = 'public' AND t.typname = 'OutboxState')
       IS DISTINCT FROM 'PENDING,LEASED,SENT,DEAD' THEN
        RAISE EXCEPTION
            'V20261004000100 failed: public.OutboxState is not exactly PENDING,LEASED,SENT,DEAD.';
    END IF;

    -- FR-1.2: a nullable tenant column on a new table is a regression, not a
    -- style question.
    IF EXISTS (SELECT 1
                 FROM pg_attribute a
                 JOIN pg_class c ON c.oid = a.attrelid
                 JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relname = 'outbox_events'
                  AND a.attname = 'organizationId' AND NOT a.attnotnull) THEN
        RAISE EXCEPTION
            'V20261004000100 failed: outbox_events."organizationId" must be NOT NULL (FR-1.2).';
    END IF;

    -- No timestamptz may appear in a new column (V6 contract).
    IF EXISTS (SELECT 1
                 FROM pg_attribute a
                 JOIN pg_class c ON c.oid = a.attrelid
                 JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relname = 'outbox_events'
                  AND a.attnum > 0 AND NOT a.attisdropped
                  AND a.atttypid = 'timestamptz'::regtype) THEN
        RAISE EXCEPTION
            'V20261004000100 failed: outbox_events contains a timestamptz column; '
            'every timestamp in this schema is timestamp(3) without time zone.';
    END IF;
END
$$;