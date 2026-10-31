-- =====================================================================
-- V20261005080000__usage_ledger.sql
-- ADDITIVE, IDEMPOTENT-BY-VERSION, NON-DESTRUCTIVE.
--
-- VERSION NUMBERING -- 14 digits (yyyyMMddHHmmss), numerically greater than
--   20261005070000.
--
-- PROMOTED FROM ops/flyway/pending/V202610040003__usage_ledger.sql.todo
--   (deleted on promotion).
--
-- WHY THIS MIGRATION EXISTS
--   plan.md rule 8 and FR-7.1/FR-7.2: "No finance numbers from mutable counters
--   alone." Every finance total in this system must resolve to immutable usage
--   events AND to the rate-card version that priced them (ADR-0007 Part 1
--   rule 1). `usage_events` is that immutable record.
--
-- THE ONE RULE THIS FILE EXISTS TO ENFORCE
--   REVENUE IS DERIVED. IT IS NOT A COLUMN ON THIS TABLE.
--   ADR-0007 Part 1 is explicit: "a usage event stores cost. A versioned rate
--   card with an effective-dated markup converts that cost into an internal
--   billable/revenue amount. Revenue is a derived value, never a stored input."
--   The draft had `billableAmount` on `usage_events`, and it is deliberately NOT
--   here. Three reasons, each of which the rehearsal confirmed matters:
--     * a stored revenue on the event is a DENORMALISED COPY of a rate-card
--       computation. It can disagree with the rate card, and then there are two
--       numbers and no way to say which is right;
--     * it cannot be recomputed. When a rate card is corrected with retroactive
--       effect, a stored event amount is either wrong (and needs an UPDATE, which
--       this table forbids) or a correction row -- and a correction row for money
--       that was never authoritative is pure noise;
--     * "the total" then has two sources that must agree. ADR-0007 rule 3 says
--       finance reads the ledger, always; that is only true if the ledger holds
--       cost and the arithmetic lives in exactly one place.
--   The derivation happens ONCE, at rollup time, and the rollup row stores both
--   the rate-card version id AND the markup value it used
--   (V20261005090000__finance_rate_cards_periods.sql). That is what makes
--   "changing a rate card never mutates an already-computed snapshot"
--   (ADR-0007 rule 2) a property of the row rather than a promise.
--   The migration's guard FAILS if a column matching revenue / billable /
--   price ever appears on this table, and the fixture asserts it too, so the
--   rule cannot be undone by a later migration adding a convenient column.
--
-- UNPRICED IS A FIRST-CLASS STATE, NOT ZERO (ADR-0007 rule 5)
--   There is no rate-card reference on this table, so "unpriced" is expressed by
--   the ABSENCE of a computed amount on the rollup, and by `costAmount IS NULL`
--   meaning "this usage had no measured cost". Both are distinguishable from
--   zero by the row's existence, which is the whole point of ADR-0007 rule 5:
--   an empty period and a zero-valued period are different states. The fixture
--   proves it, because a schema that collapses them would pass every total
--   assertion.
--
-- IMMUTABILITY IS ENFORCED BY SHAPE, NOT BY CONVENTION
--   * NO updatedAt. The guard fails if one appears. An attempt to amend a usage
--     event that the guard did not catch would be a writer believing money can
--     be rewritten, which is the failure mode this table must not permit.
--   * `correctionOfId` is a self-reference, so a correction is a NEW row naming
--     the row it corrects, with a COMPOSITE tenant foreign key so a correction
--     may only point inside its own tenant.
--   * `usage_events` has no mutable status and no mutable amounts-in-place.
--   The draft said "a trigger or a REVOKE UPDATE is required before this table
--   carries real numbers -- 'append-only' enforced by convention is not
--   append-only". The draft is right, and this migration did NOT satisfy it:
--   the fixture measured `update usage_events set "costAmount" = 999`
--   SUCCEEDING against a database carrying this file. "No updatedAt" is a shape,
--   not a boundary -- it stops the laziest writer and nobody else.
--
--   THAT GAP IS CLOSED ONE VERSION LATER, AND DELIBERATELY SO.
--   V20261005090000 creates public.firstelement_append_only() and attaches it to
--   usage_events. Two reasons it is not attached here:
--     1. the function does not exist yet -- the finance migration is the natural
--        owner of "the append-only guard";
--     2. amending THIS file would move a checksum the chain has already
--        recorded. A migration whose checksum changes under a chain that has been
--        applied anywhere is an outage, so the additive fix costs one version.
--   Do not read this as an open item: the trigger exists in V20261005090000, and
--   ops/flyway/verification/V13_usage_finance_slice.sql section 4e asserts it by
--   name against the guard function it uses.
--
--   The privilege-level REVOKE really is Phase 8 work and is NOT claimed
--   anywhere: a REVOKE is a grant decision about which role writes this table,
--   and a migration owns schema, not grants.
--
-- DUPLICATE SUPPRESSION AT THE SOURCE, TENANT-SCOPED
--   UNIQUE ("organizationId","sourceEventType","sourceEventId","dimension"). The
--   draft had this unique on the three source columns ALONE, which is a global
--   constraint on a table that is otherwise entirely tenant-scoped: two tenants
--   whose providers both emit event id "evt-1" would collide, and the violation
--   message would disclose to tenant B that tenant A has an event of that name.
--   The tenant column is therefore part of the key, and the "same source event in
--   another tenant" case is asserted as ALLOWED.
--
-- projectId IS NOT NULL
--   A usage event that cannot be attributed to a project cannot be billed to a
--   project, and "we do not know whose it was" is not a state this ledger may
--   hold: an unattributable amount is an amount nobody can invoice, reconcile or
--   refund. The organisation-wide cases ADR-0007 contemplates (a seat, an
--   org-wide storage fee) are attributable to the project that consumes them, and
--   a tenant-level fee belongs in the finance tables, not in the event ledger.
--
-- `billingPeriodId` IS DELIBERATELY UNREFERENCED HERE
--   It is a uuid that V20261005090000 turns into a composite foreign key to
--   finance_periods(id, organizationId). A migration must never reference an
--   object a later migration creates, so the column lands here NULLABLE and the
--   FK lands one file later. That is the standard expand/contract order and it
--   is why the column is documented as "not yet referenced" in its own comment.
--
-- WHAT IT CREATES
--   1. enum  public.UsageDimension
--   2. fn   public.firstelement_currency_code(text)
--   3. table public.usage_events -- APPEND-ONLY, no revenue column
--
-- TIMESTAMPS
--   timestamp(3) WITHOUT time zone; every default is
--   (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'). occurredAt is written explicitly by
--   the caller: an event's time is the moment the usage happened, not the moment
--   the batch that recorded it committed.
--
-- SAFETY
--   CREATE TYPE / CREATE FUNCTION / CREATE TABLE only. No DROP, no RENAME, no
--   ALTER of an existing object, no data change, and no index added to any
--   pre-existing table.
--   `ai_usage` is NOT backfilled by this migration. The draft's open question 1
--   -- adopt `ai_usage.estimatedCost` verbatim or re-derive it -- changes finance
--   numbers and is therefore a finance decision, recorded as open in the phase
--   report. Adoption is expressed as usage events with
--   sourceEventType = 'AiUsage.legacy' whenever that decision is taken, and
--   nothing here reads the legacy table.
--   Rollback: DROP TABLE usage_events, DROP TYPE UsageDimension, DROP FUNCTION
--   firstelement_currency_code -- rehearsed on a throwaway database only
--   (ops/flyway/rollback/undo_phase7_10a.sql).
--   Every identifier is schema-qualified (the baseline sets search_path = '').
-- =====================================================================

CREATE TYPE "public"."UsageDimension" AS ENUM (
    'MESSAGE',
    'CONVERSATION',
    'CHANNEL_TRAFFIC',
    'SEAT',
    'STORAGE',
    'API_OPERATION',
    'WORKFLOW_RUN',
    'REPORT_EXPORT',
    'AI_REQUEST',
    'AI_TOKEN',
    'TIME_ENTRY',
    'DOCUMENT_CHUNK'
);

-- NOTE ON THE DRAFT'S 'UNPRICED' DIMENSION
--   The draft listed UNPRICED as a UsageDimension. It is deliberately absent, and
--   this is the single most important difference between this table and the
--   draft. "Unpriced" is not a KIND OF USAGE; it is the absence of a rate at the
--   usage date (ADR-0007: "Unpriced usage: usage with NO rate effective on the
--   usage date, shown distinctly from zero"). Putting it in the dimension enum
--   would make it a first-class thing you can measure, aggregate and chart --
--   which is how unpriced usage quietly becomes a category that is treated as
--   zero. Unpricedness is instead a property of the ROLLUP (no rate-card version,
--   no revenue), so it can be reported as a state without being countable as a
--   unit of usage.

-- ---------------------------------------------------------------------
-- A real ISO-4217 shape check.
--
-- WHY A FUNCTION: PostgreSQL refuses a subquery in a CHECK constraint, and there
-- is no catalog of currency codes to consult. The regex is the whole test, and
-- it is the right test for the failure that actually happens (a typo, a
-- lower-case code, a three-letter fragment of a longer tag).
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION "public"."firstelement_currency_code"("candidate" text)
RETURNS boolean
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT "candidate" IS NOT NULL AND "candidate" ~ '^[A-Z]{3}$'
$$;

COMMENT ON FUNCTION "public"."firstelement_currency_code"(text) IS
    'Phase 7-10a: true when the argument looks like an ISO-4217 alphabetic code. '
    'Every amount in the ledger carries a currency (ADR-0007 rule 6).';

-- ---------------------------------------------------------------------
-- usage_events -- APPEND-ONLY
-- ---------------------------------------------------------------------
CREATE TABLE "public"."usage_events" (
    "id"                 uuid NOT NULL,
    "organizationId"     uuid NOT NULL,
    -- NOT NULL: an amount that cannot be attributed to a project cannot be
    -- billed, reconciled or refunded. "We do not know whose it was" is not a
    -- state this ledger may hold.
    "projectId"          uuid NOT NULL,
    "customerId"         uuid,
    "dimension"          "public"."UsageDimension" NOT NULL,
    -- What was consumed: tokens, messages, GB-seconds, minutes.
    "quantity"           numeric(20,6) NOT NULL,
    "unit"               text NOT NULL,
    -- THE DRILL-THROUGH TARGET (FR-7.5). Every amount must resolve to the rows
    -- that produced it, so a usage event is unusable without naming its source.
    "sourceEventType"    text NOT NULL,
    "sourceEventId"      uuid NOT NULL,
    "sourceEntityType"   text,
    "sourceEntityId"     uuid,
    -- Who caused it. A principal is mandatory; a user is optional because a
    -- scheduled job, an AI run and a connector are all legitimate causes and
    -- plan.md forbids pretending they are a person.
    "actorUserId"        uuid,
    "principalType"      text NOT NULL,
    "principalName"      text NOT NULL,
    "occurredAt"         timestamp(3) without time zone NOT NULL,
    -- The calendar bucket the event falls in. DERIVED AT WRITE and immutable,
    -- because a period boundary that moves after the fact is how a locked period
    -- stops agreeing with its own events.
    "periodStart"        date NOT NULL,
    "periodEnd"          date NOT NULL,
    -- NOT YET REFERENCED. V20261005090000 adds the composite foreign key to
    -- finance_periods(id, organizationId); a migration must not reference an
    -- object a later migration creates.
    "billingPeriodId"    uuid,
    -- Currency is explicit on EVERY row (ADR-0007 rule 6), including the ones
    -- with no cost yet.
    "currency"           text NOT NULL,
    -- WHAT THIS USAGE COST FIRSTELEMENT. The only money on this row.
    "costAmount"         numeric(20,6),
    -- FX: rate, source and rate date travel together. A conversion that does not
    -- say where the rate came from is not reproducible.
    "fxRate"             numeric(20,8),
    "fxSource"           text,
    "fxRateDate"         date,
    "taxClassification"  text,
    "metadata"           jsonb NOT NULL DEFAULT '{}'::jsonb,
    -- THE CORRECTION MECHANISM. No UPDATE path: a correction is a NEW row naming
    -- the row it corrects.
    "correctionOfId"     uuid,
    "correctionReason"   text,
    -- NO updatedAt. A migration guard fails if one appears.
    "createdAt"          timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    CONSTRAINT "usage_events_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "usage_events_quantity_check"
        CHECK ("quantity" >= 0),
    CONSTRAINT "usage_events_unit_check"
        CHECK (length(btrim("unit")) > 0),
    CONSTRAINT "usage_events_source_type_check"
        CHECK (length(btrim("sourceEventType")) > 0),
    -- THE DRILL-THROUGH IS MANDATORY. A usage event that cannot name the row that
    -- produced it is the "a total that cannot name its source is a bug, not a
    -- rounding artefact" case (ADR-0007 rule 1) and it must be unrepresentable.
    CONSTRAINT "usage_events_source_entity_check"
        CHECK ("sourceEntityId" IS NULL OR length(btrim("sourceEntityType")) > 0),
    CONSTRAINT "usage_events_principal_check"
        CHECK ("actorUserId" IS NOT NULL OR length(btrim("principalName")) > 0),
    CONSTRAINT "usage_events_principal_name_check"
        CHECK (length(btrim("principalName")) > 0),
    CONSTRAINT "usage_events_principal_type_check"
        CHECK ("principalType" IN ('USER', 'SERVICE', 'AI_AGENT', 'JOB',
                                   'CONNECTOR', 'SYSTEM')),
    -- A period is a closed interval with a non-empty width. periodEnd < periodStart
    -- would make every rollup that reads it silently skip the event.
    CONSTRAINT "usage_events_period_check"
        CHECK ("periodEnd" >= "periodStart"),
    -- The event must fall inside the period it claims.
    CONSTRAINT "usage_events_period_contains_event_check"
        CHECK (("occurredAt" AT TIME ZONE 'UTC')::date
               BETWEEN "periodStart" AND "periodEnd"),
    CONSTRAINT "usage_events_currency_check"
        CHECK ("public"."firstelement_currency_code"("currency")),
    -- A negative cost is not a discount; it is a sign error, and a discount is a
    -- rate card line.
    CONSTRAINT "usage_events_cost_check"
        CHECK ("costAmount" IS NULL OR "costAmount" >= 0),
    -- FX RATE, SOURCE AND DATE TRAVEL TOGETHER, in both directions: a rate with no
    -- source is not reproducible, and a source with no rate is a leftover.
    CONSTRAINT "usage_events_fx_pair_check"
        CHECK (("fxRate" IS NULL) = ("fxSource" IS NULL)
               AND ("fxRate" IS NULL) = ("fxRateDate" IS NULL)),
    CONSTRAINT "usage_events_fx_rate_check"
        CHECK ("fxRate" IS NULL OR "fxRate" > 0),
    CONSTRAINT "usage_events_fx_source_check"
        CHECK ("fxSource" IS NULL OR length(btrim("fxSource")) > 0),
    CONSTRAINT "usage_events_metadata_check"
        CHECK (jsonb_typeof("metadata") = 'object'),
    -- A CORRECTION IS A NEW ROW THAT SAYS WHAT IT CORRECTS AND WHY. The obvious
    -- half is "only a correction names a row"; the half that was missing first
    -- time is that a row naming something must also give a reason, because an
    -- unexplained correction is indistinguishable from tampering with money.
    CONSTRAINT "usage_events_correction_check"
        CHECK (("correctionOfId" IS NULL) = ("correctionReason" IS NULL)),
    CONSTRAINT "usage_events_correction_reason_check"
        CHECK ("correctionReason" IS NULL OR length(btrim("correctionReason")) > 0),
    CONSTRAINT "usage_events_correction_not_self_check"
        CHECK ("correctionOfId" IS NULL OR "correctionOfId" <> "id")
);

-- Composite tenant key. Declared BEFORE the foreign keys, not after: a
-- self-referencing composite FK needs its target to exist first. It is also the
-- target the rollup and the finance tables use.
CREATE UNIQUE INDEX "usage_events_id_organizationId_key"
    ON "public"."usage_events" USING "btree" ("id", "organizationId");

ALTER TABLE ONLY "public"."usage_events"
    ADD CONSTRAINT "usage_events_organizationId_fkey"
    FOREIGN KEY ("organizationId") REFERENCES "public"."organizations"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."usage_events"
    ADD CONSTRAINT "usage_events_projectId_fkey"
    FOREIGN KEY ("projectId") REFERENCES "public"."projects"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."usage_events"
    ADD CONSTRAINT "usage_events_actor_tenant_fkey"
    FOREIGN KEY ("actorUserId", "organizationId")
    REFERENCES "public"."users"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

-- COMPOSITE, so a correction may only correct a row in the SAME tenant. A
-- single-column FK would let one tenant's event be "corrected" by another's,
-- which is both a data-integrity failure and a cross-tenant disclosure.
ALTER TABLE ONLY "public"."usage_events"
    ADD CONSTRAINT "usage_events_correction_tenant_fkey"
    FOREIGN KEY ("correctionOfId", "organizationId")
    REFERENCES "public"."usage_events"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

-- Composite tenant key: the rollup and the finance tables need this as a
-- foreign-key target, and it doubles as the self-reference target above.
-- (The index itself is created above, before the foreign keys, because a
-- self-referencing composite FK needs its target to exist first.)

-- TENANT-SCOPED duplicate suppression at the source. The draft's version omitted
-- the tenant column and was therefore a GLOBAL constraint on an otherwise
-- tenant-scoped table: two tenants whose providers both emit "evt-1" would
-- collide, and the violation message would disclose to the second tenant that the
-- first has an event with that name.
CREATE UNIQUE INDEX "usage_events_source_dimension_key"
    ON "public"."usage_events" USING "btree"
       ("organizationId", "sourceEventType", "sourceEventId", "dimension");

-- The monthly/quarterly read: this tenant's usage for this project, in order.
CREATE INDEX "usage_events_organizationId_projectId_occurredAt_idx"
    ON "public"."usage_events" USING "btree"
       ("organizationId", "projectId", "occurredAt");

-- The ROLLUP read, which is the one that actually runs: one tenant, one project,
-- one dimension, one period, aggregated. Without the dimension in the index the
-- rollup either scans a period or aggregates dimension by dimension afterwards,
-- and the first is a sequential scan of the largest table in the system.
CREATE INDEX "usage_events_org_project_dimension_period_idx"
    ON "public"."usage_events" USING "btree"
       ("organizationId", "projectId", "dimension", "periodStart");

-- The period close read: everything inside a billing period, for one tenant.
CREATE INDEX "usage_events_organizationId_period_idx"
    ON "public"."usage_events" USING "btree"
       ("organizationId", "periodStart", "periodEnd");

-- The drill-down from a total back to its source rows (FR-7.5).
CREATE INDEX "usage_events_organizationId_source_idx"
    ON "public"."usage_events" USING "btree"
       ("organizationId", "sourceEventType", "sourceEventId");

-- The correction chain.
CREATE INDEX "usage_events_organizationId_correctionOf_idx"
    ON "public"."usage_events" USING "btree"
       ("organizationId", "correctionOfId");

-- ---------------------------------------------------------------------
-- Post-condition guard.
--
-- This guard is longer than usual for one reason: the "no revenue column" rule
-- is the one thing this file exists for, so it is checked by COLUMN NAME rather
-- than trusted to review. A later migration that helpfully adds
-- billableAmount numeric(20,6) to this table would reintroduce exactly the
-- denormalised copy ADR-0007 Part 1 forbids, and it would be a one-line change
-- that nobody reviewing that file would connect back to here.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    missing        text;
    revenue_column text;
    zero_cost_rows bigint;
BEGIN
    SELECT string_agg(x, ', ')
      INTO missing
      FROM unnest(ARRAY[
               'usage_events_id_organizationId_key',
               'usage_events_source_dimension_key',
               'usage_events_organizationId_projectId_occurredAt_idx',
               'usage_events_org_project_dimension_period_idx',
               'usage_events_organizationId_period_idx',
               'usage_events_organizationId_source_idx',
               'usage_events_organizationId_correctionOf_idx'
           ]) AS x
     WHERE NOT EXISTS (SELECT 1 FROM pg_indexes
                        WHERE schemaname = 'public' AND indexname = x);

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'V20261005080000 failed: missing indexes: %', missing;
    END IF;

    IF to_regclass('public.usage_events') IS NULL THEN
        RAISE EXCEPTION 'V20261005080000 failed: usage_events was not created.';
    END IF;

    -- THE RULE. No stored revenue, no stored billable amount, no stored price or
    -- markup. The name list is an EXPLICIT allow-list of derived-money names, not
    -- a broad pattern: the first version used attname ILIKE '%rate%' and the
    -- throwaway rehearsal failed it on `fxRateDate`, which is a legitimate FX
    -- source-date column and not a revenue column. A guard that is too broad
    -- teaches people to widen the guard.
    SELECT string_agg(a.attname, ', ')
      INTO revenue_column
      FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_attribute a ON a.attrelid = c.oid
                              AND a.attnum > 0 AND NOT a.attisdropped
     WHERE n.nspname = 'public' AND c.relname = 'usage_events'
       AND (a.attname ILIKE '%revenue%'
            OR a.attname ILIKE '%billable%'
            OR a.attname ILIKE '%margin%'
            OR lower(a.attname) IN ('price', 'unitprice', 'markup',
                                    'markuprate', 'ratedamount', 'ratedcost'));

    IF revenue_column IS NOT NULL THEN
        RAISE EXCEPTION
            'V20261005080000 failed: usage_events carries derived money column(s) % -- '
            'revenue is DERIVED at rollup time (ADR-0007 Part 1) and must never be stored '
            'on the immutable event.', revenue_column;
    END IF;

    -- NO RATE-CARD REFERENCE ON AN IMMUTABLE EVENT. The rate effective at the
    -- usage date is resolved once, at rollup time, and pinned on the snapshot.
    IF EXISTS (SELECT 1 FROM pg_class c
                 JOIN pg_namespace n ON n.oid = c.relnamespace
                 JOIN pg_attribute a ON a.attrelid = c.oid
                                       AND a.attname = 'rateCardVersionId'
                                       AND a.attnum > 0 AND NOT a.attisdropped
                WHERE n.nspname = 'public' AND c.relname = 'usage_events') THEN
        RAISE EXCEPTION
            'V20261005080000 failed: usage_events.rateCardVersionId must not exist; the '
            'rate is pinned on the rollup snapshot so a rate change cannot mutate it.';
    END IF;

    -- APPEND-ONLY.
    IF EXISTS (SELECT 1 FROM pg_class c
                 JOIN pg_namespace n ON n.oid = c.relnamespace
                 JOIN pg_attribute a ON a.attrelid = c.oid
                                       AND a.attname = 'updatedAt'
                                       AND a.attnum > 0 AND NOT a.attisdropped
                WHERE n.nspname = 'public' AND c.relname = 'usage_events') THEN
        RAISE EXCEPTION
            'V20261005080000 failed: usage_events must not have updatedAt; it is '
            'append-only and its money is immutable.';
    END IF;

    -- FR-1.2 and the project rule.
    IF EXISTS (SELECT 1 FROM pg_attribute a
                 JOIN pg_class c ON c.oid = a.attrelid
                 JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relname = 'usage_events'
                  AND a.attname IN ('organizationId', 'projectId')
                  AND NOT a.attnotnull) THEN
        RAISE EXCEPTION
            'V20261005080000 failed: organizationId and projectId must both be NOT NULL on '
            'usage_events; an unattributable amount cannot be billed, reconciled or refunded.';
    END IF;

    -- V6 contract: no timestamptz.
    IF EXISTS (SELECT 1 FROM pg_class c
                 JOIN pg_namespace n ON n.oid = c.relnamespace
                 JOIN pg_attribute a ON a.attrelid = c.oid
                                       AND a.atttypid = 'timestamptz'::regtype
                                       AND a.attnum > 0 AND NOT a.attisdropped
                WHERE n.nspname = 'public' AND c.relname = 'usage_events') THEN
        RAISE EXCEPTION
            'V20261005080000 failed: usage_events contains a timestamptz column.';
    END IF;

    -- Every UTC-writing default must say UTC.
    IF EXISTS (SELECT 1 FROM pg_attrdef d
                 JOIN pg_class c ON c.oid = d.adrelid
                 JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relname = 'usage_events'
                  AND pg_get_expr(d.adbin, d.adrelid) LIKE '%CURRENT_TIMESTAMP%'
                  AND pg_get_expr(d.adbin, d.adrelid) NOT LIKE '%AT TIME ZONE%') THEN
        RAISE EXCEPTION
            'V20261005080000 failed: a default uses a session-local CURRENT_TIMESTAMP.';
    END IF;

    -- The correction self-reference must be COMPOSITE.
    IF (SELECT count(*) FROM pg_constraint con
          JOIN pg_class c ON c.oid = con.conrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public'
           AND con.conname = 'usage_events_correction_tenant_fkey'
           AND con.contype = 'f') <> 1 THEN
        RAISE EXCEPTION
            'V20261005080000 failed: usage_events_correction_tenant_fkey must exist.';
    END IF;

    -- The duplicate-suppression index must be TENANT-SCOPED. A unique index on
    -- (sourceEventType, sourceEventId, dimension) alone is the draft's version and
    -- it is a global constraint on a tenant-scoped table.
    -- The column list is compared through pg_attribute rather than by matching
    -- pg_get_indexdef text, because indexdef quotes identifiers only when it must
    -- and these are camelCase: the first version of this guard looked for
    -- quoted names and therefore matched NOTHING and would have failed here.
    IF NOT EXISTS (SELECT 1 FROM pg_index i
                     JOIN pg_class c ON c.oid = i.indexrelid
                     JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'public'
                      AND c.relname = 'usage_events_source_dimension_key'
                      AND i.indisunique
                      AND (SELECT array_agg(a2.attname::text ORDER BY k.ord)
                             FROM unnest(i.indkey) WITH ORDINALITY AS k(attnum, ord)
                             JOIN pg_attribute a2 ON a2.attrelid = i.indrelid
                                                 AND a2.attnum = k.attnum
                            WHERE k.attnum > 0)
                          = ARRAY['organizationId'::text, 'sourceEventType'::text,
                                  'sourceEventId'::text, 'dimension'::text]) THEN
        RAISE EXCEPTION
            'V20261005080000 failed: usage_events_source_dimension_key must be UNIQUE over '
            '("organizationId","sourceEventType","sourceEventId","dimension") -- a global '
            'constraint here would collide two tenants and disclose one to the other.';
    END IF;

    -- The enum list is the contract, and UNPRICED must NOT be in it.
    IF (SELECT string_agg(e.enumlabel, ',' ORDER BY e.enumsortorder)
          FROM pg_type t
          JOIN pg_enum e ON e.enumtypid = t.oid
          JOIN pg_namespace n ON n.oid = t.typnamespace
         WHERE n.nspname = 'public' AND t.typname = 'UsageDimension')
       IS DISTINCT FROM
           'MESSAGE,CONVERSATION,CHANNEL_TRAFFIC,SEAT,STORAGE,API_OPERATION,WORKFLOW_RUN,REPORT_EXPORT,AI_REQUEST,AI_TOKEN,TIME_ENTRY,DOCUMENT_CHUNK' THEN
        RAISE EXCEPTION
            'V20261005080000 failed: public.UsageDimension value list drifted. Note that '
            'UNPRICED must never be added: unpricedness is a property of the rollup, not a '
            'kind of usage.';
    END IF;

    -- The currency validator must discriminate.
    IF (SELECT public.firstelement_currency_code('USD')) IS NOT TRUE
       OR (SELECT public.firstelement_currency_code('usd')) IS NOT FALSE
       OR (SELECT public.firstelement_currency_code('US')) IS NOT FALSE THEN
        RAISE EXCEPTION
            'V20261005080000 failed: public.firstelement_currency_code does not discriminate '
            'a currency code from a typo.';
    END IF;

    -- Sanity on real rows: a negative cost must never exist. The CHECK already
    -- forbids it, so this can only fire if the constraint was dropped -- which is
    -- exactly the state this assertion exists to make visible.
    SELECT count(*) INTO zero_cost_rows
      FROM public.usage_events
     WHERE "costAmount" < 0
        OR ("fxRate" IS NOT NULL AND "fxRate" <= 0)
        OR "quantity" < 0;

    IF zero_cost_rows <> 0 THEN
        RAISE EXCEPTION
            'V20261005080000 failed: % usage_events row(s) violate a money invariant; a CHECK '
            'constraint has been dropped.', zero_cost_rows;
    END IF;
END
$$;