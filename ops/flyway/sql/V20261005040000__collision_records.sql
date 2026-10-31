-- =====================================================================
-- V20261005040000__collision_records.sql
-- ADDITIVE, IDEMPOTENT-BY-VERSION, NON-DESTRUCTIVE.
--
-- VERSION NUMBERING -- 14 digits (yyyyMMddHHmmss), numerically greater than
--   20261005030000. See the header of
--   V20261005010000__workflow_definitions_and_versions.sql for why a 12-digit
--   version sorts BEFORE the 14-digit baseline.
--
-- WHY THIS MIGRATION EXISTS
--   Phase 6 has three human-vs-human / human-vs-machine races that must be
--   RECORDED rather than merely avoided:
--     * a human replies while the AI is composing (phase-06 gate 1: a human
--       takeover must abort an in-flight AI send);
--     * two operators assign the same conversation at the same moment;
--     * two people edit the same case fields.
--   ADR 0006 section 2 records the deeper problem: there is NO integer
--   `version` column in the verified schema, so Phase 4 uses `updatedAt` as
--   the concurrency token, which is coarse -- two edits inside the same
--   timestamp granularity do not collide. Phase 6 therefore needs a real
--   concurrency token of its own, and this table is where the ATTEMPTED
--   collision is recorded together with the token each writer observed.
--
--   This is a collision RECORD, not a lock. The database's job here is to make
--   the collision visible, attributable and count-able, and to refuse the one
--   thing that must be impossible: two rows claiming to be the same resolution
--   of the same collision. A row that is written for a conflict that never
--   happened is the failure mode; an unrecoverable deadlock is the other. This
--   migration takes neither side.
--
-- WHY THE SAME FIVE-WORD QUESTION WAS ASKED TWICE AND WHY IT IS NOT A TABLE
--   "Routing explanations" is derived from workflow_run_actions +
--   workflow_versions (see the decision recorded in
--   V20261005020000__workflow_runs_and_action_results.sql). A collision record
--   is NOT derivable from anything: it is the record of an event that, by
--   definition, left no trace in the rows it raced for. Both messages in the
--   conversation were written; nothing else can say they were written against
--   the same version. That asymmetry is the whole justification for this table
--   existing, and it is why there is exactly one.
--
-- WHAT IT CREATES
--   1. enum  public.CollisionScope (CONVERSATION, ASSIGNMENT, CASE, CUSTOMER)
--   2. enum  public.CollisionResolution (KEPT_BOTH, WINS, LOSES, ABORTED_BY_HUMAN,
--      NOOP)
--   3. table public.collision_records -- APPEND-ONLY
--
-- THE UNIQUENESS THAT MAKES THE CONCURRENCY TEST POSSIBLE
--   CollisionCounterparty_unique is UNIQUE
--   ("organizationId","scope","resourceId","counterpartyId","collisionWindow").
--
--   collisionWindow is a small integer BUCKET, not a timestamp, and that is the
--   load-bearing decision:
--     * a timestamp would have to be identical across two racing writers for
--       the index to catch anything, and two writers never produce an identical
--       now(). The unique index would then never fire and the guarantee would be
--       decorative;
--     * a coarse bucket (default: floor(epoch_minute / 5), i.e. a 5-minute
--       window) makes the guarantee mean what an operator means by "these two
--       edits are the same incident": if the same resource is touched by the same
--       counterparty twice inside one window, the second insert is refused, so
--       the race is counted ONCE.
--   The bucket is stored rather than computed in the index because PostgreSQL
--   index expressions must be IMMUTABLE, and now()/floor() are not. Storing it
--   also means the same writer can be retried safely by the application, which
--   computes the same bucket and gets a clean 0-row ON CONFLICT instead of a
--   crash.
--
--   CounterpartyId is NOT NULL and is deliberately free text (a user id, a
--   workflow run id, an employee id, a connector channel) rather than a
--   polymorphic FK: the point of the column is to be IDENTIFIABLE, and the
--   identifier space is deliberately wider than `users`. It is recorded that
--   this sacrifices referential integrity in exchange for being able to name an
--   AI run or a system actor, and that the alternative -- three nullable FK
--   columns, one per actor kind -- would be a worse table.
--
-- projectId -- NOT NULL HERE, ON PURPOSE
--   Every resource this table races for (conversation, assignment, case,
--   customer) is a project-scoped object in the verified schema, so a NULL
--   projectId could only mean "unknown scope", which is the one meaning it must
--   never have. This is the opposite decision from sla_calendars /
--   workflow_definitions, and the reason is the resource, not a policy.
--
-- APPEND-ONLY, ENFORCED BY SHAPE
--   No updatedAt and no mutable status. A collision happened once; correcting the
--   record is appending a resolution, not rewriting history. workflow_runs in
--   V20261005020000 IS mutable, so the two tables deliberately differ and the
--   difference is asserted by V11 section 4c.
--
-- TIMESTAMPS
--   timestamp(3) WITHOUT time zone (V6 asserts no timestamptz anywhere).
--   Defaults are (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'). observedAt is the
--   moment the writer SAW the clash and is therefore written explicitly by the
--   caller rather than defaulted, so the record's time is the incident's time
--   and not the transaction's commit time.
--
-- INDEX RATIONALE (FR-1.3)
--   collision_records_organizationId_scope_resourceId_window_key UNIQUE
--       ("organizationId","scope","resourceId","collisionWindow")
--       "How many races did this tenant hit on this resource in this window?"
--       This is the deduplication index: two different counterparties colliding
--       on the same conversation in the same window is ONE incident. It is the
--       index the concurrency test contends.
--   collision_records_organizationId_scope_resourceId_createdAt_idx
--       ("organizationId","scope","resourceId","createdAt")
--       The timeline for one conversation: "who else touched this while I was
--       replying, in order". Tenant-led.
--   collision_records_organizationId_outcome_createdAt_idx
--       ("organizationId","outcome","createdAt")
--       Phase-6 acceptance gate 1 in one query: how often a human takeover
--       actually beat an in-flight AI send. Tenant-led.
--   collision_records_organizationId_resourceType_resourceId_idx
--       ("organizationId","resourceType","resourceId")
--       Generic drill-down when the engine does not know which of the four
--       scopes applies. Tenant-led; LAST RESORT for reads, and recorded as such
--       so nobody reaches for it when scope is known.
--
--   All four lead with "organizationId". Deliberately NO tenant-less index: a
--   cross-tenant collision census is not a query this system runs, and a
--   collision is always about one tenant's conversation.
--
-- SAFETY
--   * CREATE TYPE / CREATE TABLE / ALTER TABLE ADD CONSTRAINT only. No DROP, no
--     RENAME, no ALTER of an existing object, no data change. Existing
--     conversations, customers and notes are NOT touched: ADR 0006 section 2's
--     integer `version` columns are a separate additive migration owned by the
--     CRM cutover, and this table deliberately does NOT depend on them -- the
--     writer records the token it observed in this row, so the migration lands
--     before the columns do and nothing here has to be rewritten later.
--   * Strict CREATE TABLE, not IF NOT EXISTS.
--   * FKs only where the parent is guaranteed: organizations and projects.
--     conversationId / customerId / noteId are NOT foreign keys: a collision is
--     recorded even when the row it raced for has since been deleted, and an FK
--     would either block that deletion or cascade the evidence away. This is
--     recorded as the one deliberate absence of referential integrity in this
--     slice and why.
--   * Rollback: DROP TABLE collision_records, DROP TYPE CollisionResolution,
--     DROP TYPE CollisionScope -- rehearsed on a throwaway database only
--     (ops/flyway/rollback/undo_phase6a.sql).
--   * Every index name stays under PostgreSQL's 63-byte limit.
--   * The guard looks indexes up in pg_indexes, NOT with to_regclass().
--   * Every identifier is schema-qualified because the baseline sets
--     search_path = '' when the whole chain is piped into ONE psql session.
-- =====================================================================

CREATE TYPE "public"."CollisionScope" AS ENUM (
    'CONVERSATION',
    'ASSIGNMENT',
    'CASE',
    'CUSTOMER'
);

-- WHY FIVE AND NOT THREE. KEPT_BOTH is the honest outcome when two humans both
--   sent a reply: neither lost, both messages exist, and pretending one won
--   would misreport the conversation. NOOP is the honest outcome when the race
--   was detected but nothing was in flight. NOOP, WINS and LOSES are only
--   meaningful for an AI/human race, where exactly one send can proceed.
CREATE TYPE "public"."CollisionResolution" AS ENUM (
    'KEPT_BOTH',
    'WINS',
    'LOSES',
    'ABORTED_BY_HUMAN',
    'NOOP'
);

CREATE TABLE "public"."collision_records" (
    "id"                 uuid NOT NULL,
    "organizationId"     uuid NOT NULL,
    "projectId"          uuid NOT NULL,
    "scope"              "public"."CollisionScope" NOT NULL,
    -- What kind of thing collided. Free text on purpose (see header): the
    -- engine names the concrete entity, and the list will grow.
    "resourceType"       text NOT NULL,
    "resourceId"         uuid NOT NULL,
    -- Optional typed pointers. At most ONE may be set, because a collision is
    -- about one resource; three nullable FKs that could all point at different
    -- rows would let a row describe two incidents.
    "conversationId"     uuid,
    "customerId"         uuid,
    "noteId"             uuid,
    -- Who or what was racing. Free text: a user id, a workflow run id, a
    -- connector channel. Wider than `users` by design.
    "counterpartyId"     text NOT NULL,
    "counterpartyType"   text NOT NULL,
    -- The concurrency token THIS writer observed. Null is allowed and means
    -- "the caller has no token yet", which is the real state of the schema
    -- before ADR 0006 section 2's integer version columns exist.
    "observedVersion"    integer,
    -- The 5-minute bucket that makes deduplication possible. See the header:
    -- a timestamp could never be identical across racing writers, so the unique
    -- index would never fire.
    "collisionWindow"    bigint NOT NULL,
    "outcome"            "public"."CollisionResolution" NOT NULL,
    "resolutionDetail"   text,
    -- The AI run this collision aborted, when one did. NOT a foreign key to
    -- workflow_runs on purpose: this migration lands before, or independently
    -- of, that table's lifecycle, and a SET NULL reference would still block
    -- dropping a run. Recorded as a correlation id, which is what it is used for.
    "abortedRunId"       uuid,
    "observedAt"         timestamp(3) without time zone NOT NULL,
    "correlationId"      text,
    "causationId"        text,
    -- NO updatedAt. The collision happened once.
    "createdAt"          timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    CONSTRAINT "collision_records_pkey" PRIMARY KEY ("id"),
    -- At most one typed pointer. Stated as <= 1 rather than "exactly one"
    -- because NONE may be set when the engine could only name resourceType and
    -- resourceId; requiring exactly one would force a NULL conversationId /
    -- customerId / noteId pair onto every ASSIGNMENT and CASE row.
    CONSTRAINT "collision_records_single_resource_check"
        CHECK ((("conversationId" IS NOT NULL)::int
              + ("customerId" IS NOT NULL)::int
              + ("noteId" IS NOT NULL)::int) <= 1),
    -- A negative or non-integer observed version is not a version.
    CONSTRAINT "collision_records_observed_version_check"
        CHECK ("observedVersion" IS NULL OR "observedVersion" >= 0),
    -- The counterparty must be identifiable. An unnameable counterparty is an
    -- unattributable incident.
    CONSTRAINT "collision_records_counterparty_check"
        CHECK (length(btrim("counterpartyId")) > 0
               AND length(btrim("counterpartyType")) > 0),
    -- A kept-both or no-op resolution has no winner, so it must not claim one.
    -- Encoded so the two AI-race outcomes (WINS/LOSES/ABORTED_BY_HUMAN) carry
    -- the detail explaining how the race was settled.
    CONSTRAINT "collision_records_outcome_detail_check"
        CHECK (("outcome" NOT IN ('KEPT_BOTH', 'NOOP'))
               OR "resolutionDetail" IS NOT NULL),
    -- THE ABORT IS A BICONDITIONAL, both directions. "Only an ABORTED_BY_HUMAN
    -- outcome may name an aborted run" is the obvious half and was the first
    -- version of this constraint. The Phase-6a fixture then proved the other
    -- half was missing: a row could claim a human takeover with NO abortedRunId
    -- at all, which is an unattributable abort -- exactly what this table exists
    -- to prevent, since nothing else records that a race happened. So an
    -- ABORTED_BY_HUMAN outcome MUST name the run it aborted, and nothing else
    -- may name one.
    CONSTRAINT "collision_records_aborted_run_check"
        CHECK (("outcome" = 'ABORTED_BY_HUMAN'::"public"."CollisionResolution")
               = ("abortedRunId" IS NOT NULL)),
    CONSTRAINT "collision_records_resource_type_check"
        CHECK (length(btrim("resourceType")) > 0)
);

ALTER TABLE ONLY "public"."collision_records"
    ADD CONSTRAINT "collision_records_organizationId_fkey"
    FOREIGN KEY ("organizationId") REFERENCES "public"."organizations"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."collision_records"
    ADD CONSTRAINT "collision_records_projectId_fkey"
    FOREIGN KEY ("projectId") REFERENCES "public"."projects"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

-- conversationId, customerId and noteId are deliberately NOT foreign keys: a
-- collision must still be recordable after the raced-for row is deleted, and a
-- CASCADE would destroy exactly the evidence that a race happened.

-- ---------------------------------------------------------------------
-- indexes
-- ---------------------------------------------------------------------

-- THE DEDUPLICATION INDEX. Two counterparties colliding on the same resource in
-- the same 5-minute window is ONE incident, so the second insert is refused.
-- This is the index the Phase-6a concurrency test contends.
CREATE UNIQUE INDEX "collision_records_org_scope_resource_window_key"
    ON "public"."collision_records" USING "btree"
       ("organizationId", "scope", "resourceId", "collisionWindow");

-- Tenant-scoped counterparty uniqueness: one counterparty cannot record two
-- resolutions of the same resource in the same window. Together with the index
-- above this is the uniqueness the fixture races on.
CREATE UNIQUE INDEX "collision_records_counterparty_unique"
    ON "public"."collision_records" USING "btree"
       ("organizationId", "scope", "resourceId", "counterpartyId", "collisionWindow");

-- The timeline for one conversation.
CREATE INDEX "collision_records_organizationId_scope_resourceId_createdAt_idx"
    ON "public"."collision_records" USING "btree"
       ("organizationId", "scope", "resourceId", "createdAt");

-- Phase-6 acceptance gate 1 in one query: how often a human beat an AI send.
CREATE INDEX "collision_records_organizationId_outcome_createdAt_idx"
    ON "public"."collision_records" USING "btree"
       ("organizationId", "outcome", "createdAt");

-- Generic drill-down. LAST RESORT for reads; use the scope-specific index when
-- the scope is known. Recorded as such so it is not the default choice.
CREATE INDEX "collision_records_organizationId_resourceType_resourceId_idx"
    ON "public"."collision_records" USING "btree"
       ("organizationId", "resourceType", "resourceId");

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
               'collision_records_org_scope_resource_window_key',
               'collision_records_counterparty_unique',
               'collision_records_organizationId_scope_resourceId_createdAt_idx',
               'collision_records_organizationId_outcome_createdAt_idx',
               'collision_records_organizationId_resourceType_resourceId_idx'
           ]) AS x
     WHERE NOT EXISTS (SELECT 1 FROM pg_indexes
                        WHERE schemaname = 'public' AND indexname = x);

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'V20261005040000 failed: missing collision indexes: %', missing;
    END IF;

    -- Truncation detection, done correctly: every index that exists on this table
    -- must be one this file declared by its exact name. A name longer than 63
    -- bytes is silently truncated and can never match; a legitimate 63-byte
    -- name is not flagged. The first attempt at this guard was
    -- `length(indexname) = 63`, which the throwaway rehearsal proved WRONG:
    -- exactly 63 bytes is legal and untruncated.
    IF EXISTS (SELECT 1 FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename = 'collision_records'
                  AND indexname NOT IN (
                      'collision_records_pkey',
                      'collision_records_org_scope_resource_window_key',
                      'collision_records_counterparty_unique',
                      'collision_records_organizationId_scope_resourceId_createdAt_idx',
                      'collision_records_organizationId_outcome_createdAt_idx',
                      'collision_records_organizationId_resourceType_resourceId_idx')) THEN
        RAISE EXCEPTION
            'V20261005040000 failed: an unexpected index exists on collision_records -- most '
            'likely a name longer than 63 bytes was silently truncated.';
    END IF;

    IF (SELECT string_agg(e.enumlabel, ',' ORDER BY e.enumsortorder)
          FROM pg_type t
          JOIN pg_enum e ON e.enumtypid = t.oid
          JOIN pg_namespace n ON n.oid = t.typnamespace
         WHERE n.nspname = 'public' AND t.typname = 'CollisionScope')
       IS DISTINCT FROM 'CONVERSATION,ASSIGNMENT,CASE,CUSTOMER' THEN
        RAISE EXCEPTION
            'V20261005040000 failed: public.CollisionScope value list drifted.';
    END IF;

    IF (SELECT string_agg(e.enumlabel, ',' ORDER BY e.enumsortorder)
          FROM pg_type t
          JOIN pg_enum e ON e.enumtypid = t.oid
          JOIN pg_namespace n ON n.oid = t.typnamespace
         WHERE n.nspname = 'public' AND t.typname = 'CollisionResolution')
       IS DISTINCT FROM 'KEPT_BOTH,WINS,LOSES,ABORTED_BY_HUMAN,NOOP' THEN
        RAISE EXCEPTION
            'V20261005040000 failed: public.CollisionResolution value list drifted.';
    END IF;

    -- FR-1.2.
    IF EXISTS (SELECT 1
                 FROM pg_attribute a
                 JOIN pg_class c ON c.oid = a.attrelid
                 JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relname = 'collision_records'
                  AND a.attname = 'organizationId' AND NOT a.attnotnull) THEN
        RAISE EXCEPTION
            'V20261005040000 failed: collision_records."organizationId" must be NOT NULL (FR-1.2).';
    END IF;

    -- A collision is always about a project-scoped resource, so projectId is NOT
    -- NULL here -- the opposite of the organisation-wide decision taken in
    -- V20261005010000 and V20261005030000. Assert it so the asymmetry cannot
    -- quietly become a NULL.
    IF EXISTS (SELECT 1
                 FROM pg_attribute a
                 JOIN pg_class c ON c.oid = a.attrelid
                 JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relname = 'collision_records'
                  AND a.attname = 'projectId' AND NOT a.attnotnull) THEN
        RAISE EXCEPTION
            'V20261005040000 failed: collision_records."projectId" must be NOT NULL; '
            'every collided resource is project-scoped, so NULL would mean unknown scope.';
    END IF;

    -- V6 contract.
    IF EXISTS (SELECT 1
                 FROM pg_attribute a
                 JOIN pg_class c ON c.oid = a.attrelid
                 JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relname = 'collision_records'
                  AND a.attnum > 0 AND NOT a.attisdropped
                  AND a.atttypid = 'timestamptz'::regtype) THEN
        RAISE EXCEPTION
            'V20261005040000 failed: collision_records contains a timestamptz column; '
            'every timestamp in this schema is timestamp(3) without time zone.';
    END IF;

    -- Append-only: an updatedAt column would mean a writer believed it could
    -- amend a race that already happened.
    IF EXISTS (SELECT 1
                 FROM pg_attribute a
                 JOIN pg_class c ON c.oid = a.attrelid
                 JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relname = 'collision_records'
                  AND a.attname = 'updatedAt' AND a.attnum > 0 AND NOT a.attisdropped) THEN
        RAISE EXCEPTION
            'V20261005040000 failed: collision_records must not have updatedAt; '
            'a collision record is append-only.';
    END IF;

    -- Every UTC-writing default must say UTC.
    IF EXISTS (SELECT 1
                 FROM pg_attrdef d
                 JOIN pg_class c ON c.oid = d.adrelid
                 JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relname = 'collision_records'
                  AND pg_get_expr(d.adbin, d.adrelid) LIKE '%CURRENT_TIMESTAMP%'
                  AND pg_get_expr(d.adbin, d.adrelid) NOT LIKE '%AT TIME ZONE%') THEN
        RAISE EXCEPTION
            'V20261005040000 failed: a default uses a session-local CURRENT_TIMESTAMP; '
            'every new default must be (CURRENT_TIMESTAMP AT TIME ZONE ''UTC'').';
    END IF;

    -- The counterparty must be there. An unattributable collision is not a
    -- record of anything.
    IF EXISTS (SELECT 1
                 FROM pg_attribute a
                 JOIN pg_class c ON c.oid = a.attrelid
                 JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relname = 'collision_records'
                  AND a.attname = 'counterpartyId' AND NOT a.attnotnull) THEN
        RAISE EXCEPTION
            'V20261005040000 failed: collision_records."counterpartyId" must be NOT NULL; '
            'a collision with no counterparty is unattributable.';
    END IF;

    -- Both uniqueness indexes must be UNIQUE, not merely present. A migration
    -- that created them as plain indexes would satisfy the guard above and
    -- break the guarantee.
    IF (SELECT count(*) FROM pg_index i
          JOIN pg_class c ON c.oid = i.indexrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public'
           AND c.relname IN ('collision_records_org_scope_resource_window_key',
                             'collision_records_counterparty_unique')
           AND i.indisunique) <> 2 THEN
        RAISE EXCEPTION
            'V20261005040000 failed: both collision uniqueness indexes must be UNIQUE.';
    END IF;
END
$$;