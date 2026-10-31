-- =====================================================================
-- V20261004001000__idempotency_records.sql
-- ADDITIVE, NON-DESTRUCTIVE. NEW IN PHASE 5a (no draft existed for this
-- table).
--
-- VERSION NUMBERING
--   14 digits (yyyyMMddHHmmss), like the rest of the chain, because Flyway
--   orders versions NUMERICALLY: a 12-digit version such as the drafts'
--   202610040001 sorts BEFORE the 14-digit baseline 20260814000000 and would
--   run against an empty database before the schema it depends on exists. The
--   ordinal 010 is kept in the seconds field. See the header of
--   V20261004000100__outbox.sql for the whole explanation.
--
-- WHY THIS MIGRATION EXISTS
--   The delivery path needs a general, tenant-scoped record of "this operation
--   was already requested, here is what it produced". Phase 4 could not add one
--   (no migration was permitted in that phase) and fell back to
--   messages.metadata->>'idempotencyKey' for replies, which is per-message and
--   cannot serve provider-event deduplication or outbound delivery.
--
--   Requirements: PROJECT_REQUIREMENTS.md FR-10.7 (idempotency records
--   operational), plan.md Phase 5 item 6 ("provider-event deduplication and
--   idempotency records").
--
--   Three scopes are anticipated, all tenant-scoped:
--     * PROVIDER_EVENT    -- a webhook/inbound provider id was already accepted.
--     * OUTBOUND_DELIVERY -- an outbound send was already requested.
--     * API_REQUEST       -- a mutating API call carried an Idempotency-Key.
--   "scope" is TEXT, not an enum: it is a vocabulary that will grow, and adding
--   a scope must not be a migration. The STATE machine below IS an enum,
--   because its transitions are the contract the dispatcher relies on.
--
-- STATE MACHINE (authoritative)
--
--        insert IN_PROGRESS (lockExpiresAt = now() + window)
--              |
--              +-- duplicate, lock not expired --> caller is told the operation
--              |                                  is already in flight (409)
--              +-- duplicate, lock expired     --> the original caller died;
--              |                                  takeover is allowed
--              +-- success --> COMPLETED (responseStatus + responseBodyHash)
--              +-- terminal failure --> FAILED (the stored answer is replayed)
--
--   A record is never deleted to make room. "expiresAt" states when the record
--   may be PURGED; it is not a deletion. See Q1 below.
--
-- OPEN QUESTIONS -- RESOLVED
--
--   Q1 "How long does a record live?" (the question Phase 4's follow-up raised)
--       RESOLVED AS: the schema carries the answer, the POLICY does not.
--       "expiresAt" is set by the writer from configuration; a NULL means
--       "retain until the lifecycle purge". No trigger, no rule and no scheduled
--       job deletes anything in this migration: deletion is destructive, must be
--       audited, and needs the PROJECT_REQUIREMENTS section 7 data-lifecycle
--       decision (same position as V20261004000100 Q1 and V20261004000700 Q1).
--       The partial index on ("organizationId","expiresAt") exists so that a
--       purge can find expired records per tenant without a sequential scan once
--       the policy is agreed.
--
--   Q2 "Which uniqueness scope?" -- RESOLVED AS tenant + scope + key.
--       Not global: a global unique index would turn a duplicate key into a
--       cross-tenant existence oracle (the violation message tells the caller
--       that somebody else already used the key) and would make provider ids
--       that collide across tenants impossible to store. Not per-project either:
--       a provider event belongs to the tenant, and forcing a project guess
--       would reject legitimate organisation-wide work (see
--       V20261004000100 Q4).
--
--   Q3 "What about the reply idempotency Phase 4 already stores in
--       messages.metadata?"
--       RESOLVED AS: NOT MIGRATED, ON PURPOSE. Moving existing keys out of
--       messages.metadata is a DATA migration on an existing table, which this
--       phase explicitly excludes (additive only, no UPDATE of legacy rows) and
--       which would require a matching change in backend/inbox-helpdesk, owned by
--       another agent. Until Phase 5b chooses one source of truth, the two
--       stores are used for DIFFERENT things -- this table for provider events
--       and outbound delivery, messages.metadata for replies -- and the overlap
--       is recorded in docs/migration/phases/phase-05a.md as an open decision.
--       This table therefore never reads or writes messages.metadata, so the two
--       cannot disagree about a row.
--
-- WHAT IT CREATES
--   1. enum  public.IdempotencyState (IN_PROGRESS, COMPLETED, FAILED)
--   2. table public.idempotency_records
--   3. indexes, each with the query it serves
--
-- TIMESTAMPS
--   timestamp(3) WITHOUT time zone, defaults at UTC (see V20261004000100).
--
-- INDEX RATIONALE (FR-1.3)
--   idempotency_records_organizationId_scope_idempotencyKey_key  UNIQUE
--       ("organizationId","scope","idempotencyKey") -- the lookup contract:
--       a caller retrying a request finds its own record with one index probe.
--   idempotency_records_organizationId_projectId_state_created_idx
--       Tenant admin view of what has been deduplicated. Named
--       ..._created_idx rather than ..._createdAt_idx because the full name is
--       64 bytes and PostgreSQL would silently truncate it to
--       ..._createdAt_id -- a name that differs from every other index here.
--   idempotency_records_organizationId_expiresAt_idx  (partial: expiresAt NOT NULL)
--       The retention sweep, tenant-led and partial, so it costs nothing until
--       a policy sets expiresAt.
--   There is deliberately NO tenant-less index: a cross-tenant sweep of
--   idempotency keys is not a query this system should be able to express.
--
-- SAFETY
--   * CREATE TYPE / CREATE TABLE only. No DROP, no RENAME, no data change, no
--     UPDATE of any existing row, no FK to a table that might not exist.
--   * Strict, not IF NOT EXISTS: a pre-existing object must fail loudly.
--   * Rollback: DROP TABLE idempotency_records, DROP TYPE IdempotencyState --
--     rehearsed on a throwaway database only (ops/flyway/rollback/undo_phase5a.sql).
-- =====================================================================

CREATE TYPE "public"."IdempotencyState" AS ENUM (
    'IN_PROGRESS',
    'COMPLETED',
    'FAILED'
);

CREATE TABLE "public"."idempotency_records" (
    "id"                  uuid NOT NULL,
    "organizationId"      uuid NOT NULL,
    "projectId"           uuid,
    "scope"               text NOT NULL,
    "idempotencyKey"      text NOT NULL,
    -- Hash of the canonical request. A retry with the SAME key and a DIFFERENT
    -- fingerprint is a client bug or an attempt to reuse someone else's result:
    -- the caller must be told, never silently served the old response.
    "requestFingerprint"  text,
    "state"               "public"."IdempotencyState" DEFAULT 'IN_PROGRESS'::"public"."IdempotencyState" NOT NULL,
    -- In-flight marker for the duplicate that arrives while the first is still
    -- running. Expired lock = the original caller died and may be taken over.
    "lockExpiresAt"       timestamp(3) without time zone,
    "resourceType"        text,
    "resourceId"          uuid,
    "responseStatus"      integer,
    -- A HASH of the stored response, never the response body: an idempotency
    -- record is operational metadata and must not become a second copy of
    -- customer data.
    "responseBodyHash"    text,
    "correlationId"       text,
    "expiresAt"           timestamp(3) without time zone,
    "createdAt"           timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    "updatedAt"           timestamp(3) without time zone NOT NULL,
    CONSTRAINT "idempotency_records_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "idempotency_records_response_status_check"
        CHECK ("responseStatus" IS NULL OR ("responseStatus" BETWEEN 100 AND 599)),
    -- A finished record must be able to say what it produced; an unfinished one
    -- must not pretend to.
    CONSTRAINT "idempotency_records_completed_result_check"
        CHECK (("state" = 'COMPLETED') = ("responseStatus" IS NOT NULL)),
    -- An in-flight record holds a lock; a finished one does not.
    CONSTRAINT "idempotency_records_lock_check"
        CHECK (("state" = 'IN_PROGRESS') OR ("lockExpiresAt" IS NULL))
);

ALTER TABLE ONLY "public"."idempotency_records"
    ADD CONSTRAINT "idempotency_records_organizationId_fkey"
    FOREIGN KEY ("organizationId") REFERENCES "public"."organizations"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."idempotency_records"
    ADD CONSTRAINT "idempotency_records_projectId_fkey"
    FOREIGN KEY ("projectId") REFERENCES "public"."projects"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

CREATE UNIQUE INDEX "idempotency_records_organizationId_scope_idempotencyKey_key"
    ON "public"."idempotency_records" USING "btree" ("organizationId", "scope", "idempotencyKey");

CREATE INDEX "idempotency_records_organizationId_projectId_state_created_idx"
    ON "public"."idempotency_records" USING "btree" ("organizationId", "projectId", "state", "createdAt");

CREATE INDEX "idempotency_records_organizationId_expiresAt_idx"
    ON "public"."idempotency_records" USING "btree" ("organizationId", "expiresAt")
    WHERE "expiresAt" IS NOT NULL;

-- Post-condition guard: fail loudly rather than leave a deduplication store
-- that silently accepts duplicates. Index existence is checked in pg_indexes,
-- NOT with to_regclass(): an index is a pg_class row of relkind 'i' and is not
-- a regclass-visible relation, so to_regclass() returns NULL for it.
DO $$
DECLARE
    missing text;
BEGIN
    SELECT string_agg(x, ', ')
      INTO missing
      FROM unnest(ARRAY[
               'idempotency_records_organizationId_scope_idempotencyKey_key',
               'idempotency_records_organizationId_projectId_state_created_idx',
               'idempotency_records_organizationId_expiresAt_idx'
           ]) AS x
     WHERE NOT EXISTS (SELECT 1 FROM pg_indexes
                        WHERE schemaname = 'public' AND indexname = x);

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'V20261004001000 failed: missing idempotency indexes: %', missing;
    END IF;

    IF (SELECT string_agg(e.enumlabel, ',' ORDER BY e.enumsortorder)
          FROM pg_type t
          JOIN pg_enum e ON e.enumtypid = t.oid
          JOIN pg_namespace n ON n.oid = t.typnamespace
         WHERE n.nspname = 'public' AND t.typname = 'IdempotencyState')
       IS DISTINCT FROM 'IN_PROGRESS,COMPLETED,FAILED' THEN
        RAISE EXCEPTION 'V20261004001000 failed: public.IdempotencyState value list drifted.';
    END IF;

    IF EXISTS (SELECT 1
                 FROM pg_attribute a
                 JOIN pg_class c ON c.oid = a.attrelid
                 JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relname = 'idempotency_records'
                  AND a.attname = 'organizationId' AND NOT a.attnotnull) THEN
        RAISE EXCEPTION
            'V20261004001000 failed: idempotency_records."organizationId" must be NOT NULL (FR-1.2).';
    END IF;
END
$$;