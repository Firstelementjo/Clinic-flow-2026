-- =====================================================================
-- V20261005090000__finance_rate_cards_periods.sql
-- ADDITIVE, IDEMPOTENT-BY-VERSION, NON-DESTRUCTIVE.
--
-- VERSION NUMBERING -- 14 digits (yyyyMMddHHmmss), numerically greater than
--   20261005080000.
--
-- PROMOTED FROM ops/flyway/pending/V202610040004__finance_rate_cards_periods.sql.todo
--   (deleted on promotion).
--
-- WHAT IT CREATES
--   1. enum  public.RateCardStatus       (DRAFT, ACTIVE, RETIRED)
--   2. enum  public.PeriodStatus         (OPEN, IN_REVIEW, LOCKED, REOPENED)
--   3. enum  public.RollupGrain          (DAILY, MONTHLY)
--   4. enum  public.ReconciliationStatus (MATCHED, MISMATCH, ACCEPTED)
--   5. enum  public.AdjustmentStatus     (PENDING, APPROVED, REJECTED, APPLIED)
--   6. table public.rate_cards
--   7. table public.rate_card_versions            -- APPEND-ONLY body
--   8. table public.rate_card_version_lines       -- APPEND-ONLY body
--   9. table public.finance_periods
--  10. table public.finance_period_lock_events    -- APPEND-ONLY
--  11. table public.usage_rollup_snapshots        -- APPEND-ONLY, REVENUE PINNED
--  12. table public.finance_adjustments
--  13. table public.finance_reconciliation_runs   -- APPEND-ONLY
--  14. table public.finance_reconciliation_lines  -- APPEND-ONLY
--  15. fn   public.firstelement_append_only()  + append-only triggers
--
-- NO PAYMENT AND NO INVOICING TABLES. ADR-0007 rule 7: "No payment execution and
--   no external billing. This is an internal finance and usage surface. Invoices
--   and payment credentials are explicitly out of scope." Nothing in this file
--   stores a card, a mandate, an invoice number or a payment state, and that is
--   a decision rather than an omission.
--
-- THE LOAD-BEARING RULE: REVENUE IS DERIVED, AND THE RATE IS PINNED
--   ADR-0007 Part 1 rule 2: "Rates are versioned and effective-dated. A rate card
--   version is pinned onto the rollup row at computation time. Changing a rate
--   card never mutates an already-computed snapshot."
--   Two decisions make that a property of the row instead of a promise:
--     * `usage_rollup_snapshots` stores BOTH `rateCardVersionId` AND the
--       `markupRate` VALUE it was computed with. Pinning only the id would still
--       leave the snapshot mutable, because the rate card version's own markup
--       could be edited in place. Pinning the value means a later correction is a
--       NEW generation of the snapshot, and generation N's arithmetic is frozen.
--     * `usage_rollup_snapshots_derivation_check` requires
--       revenueAmount = round(costAmount * markupRate, 6) EXACTLY. A hand-entered
--       revenue that does not equal its own inputs is refused by the database, so
--       "where did this number come from" always has an arithmetic answer.
--   And `usage_rollup_snapshots_margin_column_absent` is enforced by the
--   post-condition guard: MARGIN IS ALSO DERIVED (ADR-0007: "Margin: revenue -
--   cost, only where a rate exists; derived; never displayed where the row is
--   unpriced"), so a stored margin column is the same defect as a stored revenue
--   column and is refused by name.
--
-- `revenueAmount` IS NULL EXACTLY WHEN THE ROW IS UNPRICED
--   A single biconditional ties "priced" to (rateCardVersionId, markupRate,
--   revenueAmount) all being present or all being absent. Unpriced usage is a
--   first-class view (ADR-0007 Consequences), NOT an error and NOT zero, and the
--   only way to keep it distinct from zero is to refuse to store a zero revenue
--   as the stand-in for "we had no rate".
--
-- ZERO IS NOT "NO DATA" (ADR-0007 rule 5)
--   `sourceEventCount >= 1` on every snapshot row. A bucket with no contributing
--   usage events must NOT be materialised, because materialising it as zero is
--   precisely how "no data" becomes "zero" in a dashboard and nobody can tell
--   them apart afterwards.
--
-- SNAPSHOTS ARE APPEND-ONLY, AND A LOCKED PERIOD REFUSES NEW ONES
--   A snapshot is never UPDATEd. A recomputation is a NEW row with the next
--   `generation` and a `supersedesSnapshotId` pointer, which the fixture proves.
--   "Closed periods are immutable" (ADR-0007 rule 4) cannot be a CHECK, because
--   the locked state lives in ANOTHER ROW. It is therefore a trigger:
--   usage_rollup_snapshots_reject_locked_period() raises on any INSERT whose
--   period is LOCKED. That is the answer to the draft's open question
--   "Immutability enforcement mechanism chosen (DB privilege vs trigger)": a
--   trigger, because a privilege cannot see the state of another row, and a
--   trigger is droppable, which a CHECK is not.
--
-- APPEND-ONLY IS ENFORCED, NOT CONVENTIONALLY ATTESTED
--   The draft said: "No UPDATE path. A trigger or a REVOKE UPDATE is required
--   before this table carries real numbers -- 'append-only' enforced by
--   convention is not append-only." This file delivers the trigger:
--   public.firstelement_append_only() is attached to rate_card_version_lines,
--   finance_period_lock_events, usage_rollup_snapshots,
--   finance_reconciliation_runs and finance_reconciliation_lines, and it RAISEs
--   on UPDATE or DELETE. The absence of `updatedAt` stays as the shape half of
--   the same rule.
--   rate_card_versions gets the NARROWER guard described two paragraphs below,
--   because it is the one table in this set that has a legitimate state
--   transition, and the fixture caught the contradiction.
--   NOT DONE HERE, AND DELIBERATELY: REVOKE UPDATE from the application role. A
--   migration owns schema, not grants, and a REVOKE is a decision about which
--   role writes this table -- Phase 8 owns it. The trigger does not depend on
--   it and does not assume it.
--
-- THIS MIGRATION ALSO CLOSES A GAP THE PREVIOUS ONE LEFT OPEN
--   V20261005080000 (usage_ledger) delivered `usage_events` append-only BY
--   SHAPE only -- no updatedAt, no mutable status -- and said so honestly in its
--   own header, listing "a trigger or a REVOKE UPDATE" as the draft's own
--   requirement and as deferred debt. The fixture then measured the consequence
--   of leaving it there: `update usage_events set "costAmount" = 999` SUCCEEDED.
--   "No updatedAt" is a shape, not a boundary -- it stops the laziest writer and
--   nobody else.
--   This migration owns the guard FUNCTION, so it is the natural place to attach
--   it to usage_events. That is also why the trigger is created HERE rather than
--   in ...080000: the function did not exist when that migration was written,
--   and amending it would change a checksum this chain has already recorded.
--   It remains purely additive -- CREATE TRIGGER on an existing table, no column
--   touched -- and undo_phase7_10a.sql drops it, so both the object list and the
--   fingerprint return exactly.
--
-- RATE CARDS ARE EFFECTIVE-DATED AND NEVER EDITED IN PLACE
--   A rate card version's PRICING is written once and never changed. Its
--   LIFECYCLE moves exactly once, from ACTIVE to RETIRED, and that single
--   transition is the only UPDATE the table accepts.
--
--   WHY NOT THE BLANKET TRIGGER HERE, WHICH IS WHAT THIS FILE SHIPPED FIRST
--   The first version attached firstelement_append_only() to
--   rate_card_versions as well. That is a contradiction the fixture caught, and
--   it is worth recording rather than quietly fixing:
--     * the table carries `retiredAt`, `retiredByUserId`, `retireReason` and
--       `supersededByVersionId`, and `rate_card_versions_retired_check` REQUIRES
--       all four plus status = RETIRED -- so a retirement is an UPDATE by
--       construction;
--     * a blanket BEFORE UPDATE trigger refuses every UPDATE, so a version could
--       never be retired, the RETIRED status would be unreachable, and
--       rate_card_versions_retired_check would be dead code that no application
--       could ever satisfy.
--   "Never edited" was therefore taken to mean "never REPRICED", which is what
--   the domain actually requires: a snapshot pins `markupRate`, so a version
--   whose markup moved would silently invalidate every rollup that referenced
--   it. So the guard below allows exactly one UPDATE shape and refuses
--   everything else -- including any change to `markupRate`, `currency`,
--   `effectiveFrom`, `effectiveTo`, `versionNumber` or `rateCardId` on a
--   RETIRED row, which is the direction that actually destroys history.
--   What it CANNOT enforce: a row being retired twice (a second transition is
--   refused because OLD.status is no longer ACTIVE), and the overlap rule below.
--   What CANNOT be enforced in DDL: two ACTIVE versions of one card whose
--   effective ranges overlap. That is a cross-row interval property and would
--   need btree_gist and an EXCLUDE constraint, which is a heavier dependency
--   than the invariant deserves. It is therefore (a) narrowed to what DDL CAN
--   enforce -- at most one version per (card, effectiveFrom) -- and (b) reported
--   as a zero-count assertion by ops/flyway/verification/V13_usage_finance_slice.sql
--   section 7, so an overlap is a visible failure rather than a silent one. The
--   application must still not create one.
--
-- `effectiveFrom` AND `effectiveTo` ARE DATES, NOT TIMESTAMPS
--   The question is "which rate applied on 2026-03-14"; a timestamp implies a
--   second-of-day effect that is not implemented, and would put a timestamptz-
--   shaped trap into a schema that has none. `effectiveTo` NULL means
--   open-ended; `finance_periods` follows the same rule for the same reason.
--
-- PERIOD LOCKS ARE AUDITED, AND A REOPEN SAYS WHY
--   ADR-0007 rule 4: "Reopen is an explicit, permissioned, audited action." The
--   finance_periods row carries lockedAt/lockedByUserId and
--   reopenedAt/reopenedByUserId/reopenReason; the finance_period_lock_events
--   history carries every transition append-only. Both a state AND a history are
--   needed: the row answers "is it locked now", the history answers "was it ever
--   locked", and only the second is answerable to an auditor.
--
--   WHY finance_periods_locked_check IS NOT A BICONDITIONAL (the fixture decided
--   this, and it is the one place this file departs from the pattern used by
--   ai_tool_registry and rate_card_versions)
--   The first version wrote it as one, mirroring the AI policy slice:
--       CHECK ((status = 'LOCKED') = (lockedAt IS NOT NULL AND lockedByUserId IS NOT NULL))
--   That is correct while a period is OPEN or LOCKED, and it is WRONG the moment
--   a period is reopened. This file's own comment two constraints below says a
--   reopened period "keeps BOTH sets" of columns, and
--   finance_periods_reopen_requires_lock_check explicitly REQUIRES lockedAt to
--   still be set after a reopen. So the biconditional and the reopen rule are in
--   direct contradiction: no REOPENED row can ever be written, which means the
--   reopen path ADR-0007 rule 4 mandates is inexpressible. The fixture found this
--   by attempting the reopen and being refused.
--   Which side gives? The biconditional gives, deliberately. Clearing lockedAt on
--   reopen would make "was this period ever closed?" unanswerable from the row,
--   and the lock audit would live ONLY in finance_period_lock_events -- so a
--   reader of finance_periods alone would see a REOPENED period that appears never
--   to have been locked. Keeping the columns is the auditable choice; the
--   biconditional is a nicety that costs the reopen its existence.
--   So the rule is ONE-WAY: being LOCKED demands the audit columns, and the audit
--   columns may outlive the lock. The negative test that motivated the biconditional
--   -- "LOCKED with no lockedByUserId" -- is still refused, which is the half that
--   matters.
--
-- ADJUSTMENTS LINK TO SOURCE EVENTS OR SAY WHY THEY CANNOT
--   ADR-0007: "an adjustment record linked to source events". `adjustmentOfId`
--   points at a usage_events row through a COMPOSITE tenant foreign key;
--   `adjustmentReason` is NOT NULL either way, because a correction that cannot
--   be traced to what it corrects still has to be explainable. Approving an
--   adjustment names who and when -- `finance_adjustments_approval_check`.
--
-- TENANT SCOPE
--   organizationId uuid NOT NULL on all nine tables (FR-1.2). projectId:
--     * NULLABLE, organisation-wide: rate_cards, rate_card_versions,
--       rate_card_version_lines, finance_adjustments. A rate card is agreed once
--       for the tenant; per-project copies are how two projects end up paying
--       different prices for the same token count.
--     * NULLABLE, organisation-wide: finance_periods, finance_reconciliation_runs.
--       A month is a month.
--     * NULLABLE on finance_period_lock_events (inherits the period).
--     * NOT NULL: usage_rollup_snapshots and finance_reconciliation_lines. A
--       rollup is about one project's usage -- the whole point of it -- so NULL
--       could only mean "we aggregated the whole tenant and called it a project".
--   V13 section 4d lists the nullable set explicitly so it cannot widen.
--
-- TIMESTAMPS
--   timestamp(3) WITHOUT time zone; every default is
--   (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'); updatedAt is never defaulted.
--
-- SAFETY
--   CREATE TYPE / CREATE FUNCTION / CREATE TABLE / CREATE TRIGGER only. No DROP,
--   no RENAME, no ALTER of an existing object, no data change, no index on any
--   pre-existing table. The ONE exception is a single ADD CONSTRAINT on
--   usage_events, adding the composite period reference that
--   V20261005080000 deliberately left as a bare uuid because a migration must not
--   reference an object a later migration creates.
--   Rollback: drop the triggers, the tables in reverse dependency order, the
--   function and the five types, and the added constraint -- rehearsed on a
--   throwaway database only (ops/flyway/rollback/undo_phase7_10a.sql).
--   Every identifier is schema-qualified (the baseline sets search_path = '').
-- =====================================================================

CREATE TYPE "public"."RateCardStatus" AS ENUM (
    'DRAFT',
    'ACTIVE',
    'RETIRED'
);

CREATE TYPE "public"."PeriodStatus" AS ENUM (
    'OPEN',
    'IN_REVIEW',
    'LOCKED',
    'REOPENED'
);

CREATE TYPE "public"."RollupGrain" AS ENUM (
    'DAILY',
    'MONTHLY'
);

CREATE TYPE "public"."ReconciliationStatus" AS ENUM (
    'MATCHED',
    'MISMATCH',
    'ACCEPTED'
);

CREATE TYPE "public"."AdjustmentStatus" AS ENUM (
    'PENDING',
    'APPROVED',
    'REJECTED',
    'APPLIED'
);

-- ---------------------------------------------------------------------
-- The append-only guard.
-- ---------------------------------------------------------------------
-- ONE FUNCTION, SIX TABLES. It is deliberately tiny: its job is to make an
-- UPDATE or a DELETE on a table whose rows are evidence fail loudly and name
-- themselves. It reads nothing and writes nothing, and it raises BEFORE the
-- statement has done anything, so there is no window in which a mutation is
-- half-applied.
CREATE OR REPLACE FUNCTION "public"."firstelement_append_only"()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        '% is APPEND-ONLY: % is refused. Correct the record by INSERTING a new row '
        '(a new generation, a correction, or an event), never by rewriting this one.',
        TG_TABLE_SCHEMA || '.' || TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
    -- Unreachable: RAISE EXCEPTION always aborts. RETURN NULL is here so the
    -- function's shape is a valid trigger function even if someone edits the
    -- RAISE into a NOTICE while debugging.
    RETURN NULL;
END
$$;

COMMENT ON FUNCTION "public"."firstelement_append_only"() IS
    'Phase 7-10a: generic BEFORE UPDATE OR DELETE guard for append-only evidence tables. '
    'Raises restrict_violation naming the table and the attempted operation.';

-- The rate-card version guard. Deliberately NOT firstelement_append_only(),
-- because this table has one legitimate state transition and the blanket guard
-- would make it unreachable. See the header for the contradiction this resolves.
CREATE OR REPLACE FUNCTION "public"."firstelement_rate_card_version_guard"()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    changed text;
    message text;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION
            'public.rate_card_versions is never DELETED. A price that applied on a date is '
            'part of the historical record; supersede it with a new version instead. '
            'A deleted version leaves a usage_rollup_snapshot pointing at nothing.'
            USING ERRCODE = 'restrict_violation';
        RETURN NULL;
    END IF;

    -- The one legal UPDATE: ACTIVE -> RETIRED, saying who, when, why, and what
    -- replaced it. `rate_card_versions_retired_check` enforces the same four
    -- fields; this trigger additionally refuses the transition when they are
    -- absent, so the error names the trigger's rule rather than arriving as a
    -- bare CHECK violation from an unexpected direction.
    IF OLD.status = 'RETIRED'::"public"."RateCardStatus" THEN
        RAISE EXCEPTION
            'public.rate_card_versions is ALREADY RETIRED and is frozen: its pricing is part '
            'of the historical record and every rollup snapshot pins it. Insert a new version '
            'instead of editing this one.'
            USING ERRCODE = 'restrict_violation';
    END IF;

    IF NEW.status = 'RETIRED'::"public"."RateCardStatus"
       AND OLD.status = 'ACTIVE'::"public"."RateCardStatus"
       AND NEW."retiredAt" IS NOT NULL
       AND NEW."retiredByUserId" IS NOT NULL
       AND NEW."retireReason" IS NOT NULL
       AND NEW."supersededByVersionId" IS NOT NULL
       -- ...and nothing that determines WHAT WAS PRICED moved.
       AND NEW."markupRate"        IS NOT DISTINCT FROM OLD."markupRate"
       AND NEW.currency             IS NOT DISTINCT FROM OLD.currency
       AND NEW."effectiveFrom"      IS NOT DISTINCT FROM OLD."effectiveFrom"
       AND NEW."effectiveTo"        IS NOT DISTINCT FROM OLD."effectiveTo"
       AND NEW."versionNumber"      IS NOT DISTINCT FROM OLD."versionNumber"
       AND NEW."rateCardId"         IS NOT DISTINCT FROM OLD."rateCardId"
       AND NEW."organizationId"     IS NOT DISTINCT FROM OLD."organizationId"
       AND NEW."projectId"          IS NOT DISTINCT FROM OLD."projectId"
       AND NEW.id                   IS NOT DISTINCT FROM OLD.id
       AND NEW."createdAt"          IS NOT DISTINCT FROM OLD."createdAt"
       AND NEW."createdByUserId"    IS NOT DISTINCT FROM OLD."createdByUserId"
       AND NEW."publishedAt"        IS NOT DISTINCT FROM OLD."publishedAt"
       AND NEW."publishedByUserId"  IS NOT DISTINCT FROM OLD."publishedByUserId"
       AND NEW."changeNote"         IS NOT DISTINCT FROM OLD."changeNote"
    THEN
        RETURN NEW;
    END IF;

    -- Name the offending column rather than saying "no". An operator who
    -- attempted to change a markup needs to know it was the markup.
    SELECT string_agg(name, ', ' ORDER BY name) INTO changed
      FROM unnest(ARRAY[
             CASE WHEN NEW."markupRate"       IS DISTINCT FROM OLD."markupRate"       THEN '"markupRate"' END,
             CASE WHEN NEW.currency            IS DISTINCT FROM OLD.currency            THEN 'currency' END,
             CASE WHEN NEW."effectiveFrom"     IS DISTINCT FROM OLD."effectiveFrom"     THEN '"effectiveFrom"' END,
             CASE WHEN NEW."effectiveTo"       IS DISTINCT FROM OLD."effectiveTo"       THEN '"effectiveTo"' END,
             CASE WHEN NEW."versionNumber"     IS DISTINCT FROM OLD."versionNumber"     THEN '"versionNumber"' END,
             CASE WHEN NEW."rateCardId"        IS DISTINCT FROM OLD."rateCardId"        THEN '"rateCardId"' END,
             CASE WHEN NEW."status"            IS DISTINCT FROM OLD."status"            THEN 'status' END,
             CASE WHEN NEW."retiredAt"         IS DISTINCT FROM OLD."retiredAt"         THEN '"retiredAt"' END,
             CASE WHEN NEW."retiredByUserId"   IS DISTINCT FROM OLD."retiredByUserId"   THEN '"retiredByUserId"' END,
             CASE WHEN NEW."retireReason"      IS DISTINCT FROM OLD."retireReason"      THEN '"retireReason"' END,
             CASE WHEN NEW."supersededByVersionId"
                                       IS DISTINCT FROM OLD."supersededByVersionId"
                                                                 THEN '"supersededByVersionId"' END
           ]) AS u(name)
     WHERE name IS NOT NULL;

    -- NOTE ON SPELLING: the message is built into a VARIABLE rather than written as
    -- adjacent string literals followed by an argument. PostgreSQL concatenates
    -- adjacent literals in a RAISE *format* position, but not once an argument
    -- list follows -- `RAISE EXCEPTION 'a ' 'b %s', x` is a syntax error, and it
    -- is a syntax error INSIDE a function body, so the whole migration fails to
    -- apply rather than the guard misbehaving at runtime. Cost one variable.
    message := 'public.rate_card_versions accepts exactly one UPDATE: ACTIVE -> RETIRED with '
           || '"retiredAt", "retiredByUserId", "retireReason" and "supersededByVersionId" all '
           || 'set, and no pricing column touched. This UPDATE changes: '
           || coalesce(changed, '(nothing -- the row is unchanged)')
           || '. A price that applied on a date is history; insert a new version instead.';

    RAISE EXCEPTION '%', message USING ERRCODE = 'restrict_violation';
    RETURN NULL;
END
$$;

COMMENT ON FUNCTION "public"."firstelement_rate_card_version_guard"() IS
    'Phase 7-10a: rate_card_versions permits exactly one UPDATE (ACTIVE -> RETIRED, fully '
    'attributed, successor named, no pricing column moved) and refuses DELETE and every '
    'other UPDATE. The blanket firstelement_append_only() cannot be used here because the '
    'table has a legitimate state transition; see the migration header.';

-- ---------------------------------------------------------------------
-- rate_cards
-- ---------------------------------------------------------------------
CREATE TABLE "public"."rate_cards" (
    "id"                 uuid NOT NULL,
    "organizationId"     uuid NOT NULL,
    "projectId"          uuid,
    "key"                text NOT NULL,
    "nameAr"             text NOT NULL,
    "nameEn"             text NOT NULL,
    -- Every amount this card prices is in this currency. The card is
    -- single-currency on purpose: a card that prices USD and SAR in one row has a
    -- rate inside it, and a rate inside a rate card is how "which rate applies"
    -- stops being answerable.
    "currency"           text NOT NULL,
    "status"             "public"."RateCardStatus" NOT NULL DEFAULT 'DRAFT',
    "currentVersionId"   uuid,
    "latestVersionNumber" integer,
    "createdByUserId"    uuid,
    "retiredAt"          timestamp(3) without time zone,
    "retiredByUserId"    uuid,
    "retireReason"       text,
    "version"            integer NOT NULL DEFAULT 0,
    "createdAt"          timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    "updatedAt"          timestamp(3) without time zone NOT NULL,
    CONSTRAINT "rate_cards_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "rate_cards_key_check"
        CHECK (length(btrim("key")) > 0),
    CONSTRAINT "rate_cards_name_ar_check"
        CHECK (length(btrim("nameAr")) > 0),
    CONSTRAINT "rate_cards_name_en_check"
        CHECK (length(btrim("nameEn")) > 0),
    CONSTRAINT "rate_cards_currency_check"
        CHECK ("public"."firstelement_currency_code"("currency")),
    -- A RETIRED card says when and why. A retirement that says nothing is how a
    -- card quietly stops applying with no record of who decided.
    CONSTRAINT "rate_cards_retired_check"
        CHECK ("status" <> 'RETIRED'::"public"."RateCardStatus"
               OR ("retiredAt" IS NOT NULL AND "retiredByUserId" IS NOT NULL
                   AND "retireReason" IS NOT NULL)),
    -- An ACTIVE card has no retirement recorded.
    CONSTRAINT "rate_cards_active_not_retired_check"
        CHECK ("status" = 'RETIRED'::"public"."RateCardStatus"
               OR "retiredAt" IS NULL),
    -- A pointer to a version must be accompanied by the number it points at, so
    -- "which version is live" needs one lookup rather than two.
    CONSTRAINT "rate_cards_current_version_pointer_check"
        CHECK (("currentVersionId" IS NULL) = ("latestVersionNumber" IS NULL)),
    CONSTRAINT "rate_cards_version_pointer_check"
        CHECK ("latestVersionNumber" IS NULL OR "latestVersionNumber" >= 1),
    CONSTRAINT "rate_cards_version_check"
        CHECK ("version" >= 0)
);

CREATE UNIQUE INDEX "rate_cards_id_organizationId_key"
    ON "public"."rate_cards" USING "btree" ("id", "organizationId");

-- TENANT-SCOPED business key. A global one would be a cross-tenant existence
-- oracle for a finance identifier.
CREATE UNIQUE INDEX "rate_cards_organizationId_key_key"
    ON "public"."rate_cards" USING "btree" ("organizationId", "key");

CREATE INDEX "rate_cards_organizationId_status_idx"
    ON "public"."rate_cards" USING "btree" ("organizationId", "status");

-- ---------------------------------------------------------------------
-- rate_card_versions -- APPEND-ONLY body, EFFECTIVE-DATED
-- ---------------------------------------------------------------------
CREATE TABLE "public"."rate_card_versions" (
    "id"                 uuid NOT NULL,
    "organizationId"     uuid NOT NULL,
    "projectId"          uuid,
    "rateCardId"         uuid NOT NULL,
    "versionNumber"      integer NOT NULL,
    "currency"           text NOT NULL,
    -- The DEFAULT markup applied by this version. A per-line override lives in
    -- rate_card_version_lines. Both are copied onto the rollup snapshot at
    -- computation time, so neither can change afterwards.
    "markupRate"         numeric(12,6) NOT NULL,
    "effectiveFrom"      date NOT NULL,
    "effectiveTo"        date,
    "status"             "public"."RateCardStatus" NOT NULL DEFAULT 'DRAFT',
    "changeNote"         text,
    "publishedAt"        timestamp(3) without time zone,
    "publishedByUserId"  uuid,
    "retiredAt"          timestamp(3) without time zone,
    "retiredByUserId"    uuid,
    "retireReason"       text,
    "supersededByVersionId" uuid,
    "createdByUserId"    uuid NOT NULL,
    -- NO updatedAt.
    "createdAt"          timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    CONSTRAINT "rate_card_versions_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "rate_card_versions_version_number_check"
        CHECK ("versionNumber" >= 1),
    CONSTRAINT "rate_card_versions_currency_check"
        CHECK ("public"."firstelement_currency_code"("currency")),
    -- A MARKUP IS A MULTIPLIER. Zero or negative would make revenue negative or
    -- free, which is an adjustment, not a rate. The upper bound exists because a
    -- markup of 10^9 is a data-entry error and a snapshot pinned to it would be
    -- arithmetically valid and commercially absurd.
    CONSTRAINT "rate_card_versions_markup_check"
        CHECK ("markupRate" > 0 AND "markupRate" <= 1000),
    CONSTRAINT "rate_card_versions_effective_range_check"
        CHECK ("effectiveTo" IS NULL OR "effectiveTo" >= "effectiveFrom"),
    CONSTRAINT "rate_card_versions_published_pair_check"
        CHECK (("publishedAt" IS NULL) = ("publishedByUserId" IS NULL)),
    CONSTRAINT "rate_card_versions_published_state_check"
        CHECK (("status" = 'DRAFT'::"public"."RateCardStatus") = ("publishedAt" IS NULL)),
    -- Retiring a version says who, when and why -- and points at what replaced
    -- it, so a historical snapshot's pinned version is never dangling.
    CONSTRAINT "rate_card_versions_retired_check"
        CHECK ("status" <> 'RETIRED'::"public"."RateCardStatus"
               OR ("retiredAt" IS NOT NULL AND "retiredByUserId" IS NOT NULL
                   AND "retireReason" IS NOT NULL
                   AND "supersededByVersionId" IS NOT NULL)),
    CONSTRAINT "rate_card_versions_supersede_self_check"
        CHECK ("supersededByVersionId" IS NULL OR "supersededByVersionId" <> "id"),
    CONSTRAINT "rate_card_versions_change_note_check"
        CHECK ("status" = 'DRAFT'::"public"."RateCardStatus"
               OR ("changeNote" IS NOT NULL AND length(btrim("changeNote")) > 0))
);

CREATE UNIQUE INDEX "rate_card_versions_id_organizationId_key"
    ON "public"."rate_card_versions" USING "btree" ("id", "organizationId");

CREATE UNIQUE INDEX "rate_card_versions_card_version_key"
    ON "public"."rate_card_versions" USING "btree"
       ("organizationId", "rateCardId", "versionNumber");

-- "Which rate applied on this date": the lookup the rollup runs. Narrowed to
-- ACTIVE versions by a partial index because a DRAFT or RETIRED version is never
-- the answer, and an index that carries them costs writes and confuses reads.
CREATE INDEX "rate_card_versions_active_effective_idx"
    ON "public"."rate_card_versions" USING "btree"
       ("organizationId", "rateCardId", "effectiveFrom", "effectiveTo")
    WHERE "status" = 'ACTIVE';

-- At most one version may START on a given day for one card. This is the part of
-- the overlap rule that DDL can express; the rest is reported by V13 section 7.
CREATE UNIQUE INDEX "rate_card_versions_one_start_per_day"
    ON "public"."rate_card_versions" USING "btree"
       ("organizationId", "rateCardId", "effectiveFrom");

ALTER TABLE ONLY "public"."rate_card_versions"
    ADD CONSTRAINT "rate_card_versions_card_tenant_fkey"
    FOREIGN KEY ("rateCardId", "organizationId")
    REFERENCES "public"."rate_cards"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."rate_card_versions"
    ADD CONSTRAINT "rate_card_versions_author_tenant_fkey"
    FOREIGN KEY ("createdByUserId", "organizationId")
    REFERENCES "public"."users"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

ALTER TABLE ONLY "public"."rate_card_versions"
    ADD CONSTRAINT "rate_card_versions_published_by_tenant_fkey"
    FOREIGN KEY ("publishedByUserId", "organizationId")
    REFERENCES "public"."users"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

-- ---------------------------------------------------------------------
-- rate_card_version_lines -- APPEND-ONLY body
-- ---------------------------------------------------------------------
CREATE TABLE "public"."rate_card_version_lines" (
    "id"                 uuid NOT NULL,
    "organizationId"     uuid NOT NULL,
    "projectId"          uuid,
    "rateCardVersionId"  uuid NOT NULL,
    "dimension"          "public"."UsageDimension" NOT NULL,
    "unit"               text NOT NULL,
    -- Cost per unit, when this dimension has a measured cost. Either a cost or a
    -- markup override must be present: a line with neither is a line that prices
    -- nothing while looking configured.
    "costAmount"         numeric(20,6),
    "markupRate"         numeric(12,6),
    "currency"           text NOT NULL,
    "taxClassification"  text,
    -- NO updatedAt.
    "createdAt"          timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    CONSTRAINT "rate_card_version_lines_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "rate_card_version_lines_unit_check"
        CHECK (length(btrim("unit")) > 0),
    CONSTRAINT "rate_card_version_lines_currency_check"
        CHECK ("public"."firstelement_currency_code"("currency")),
    CONSTRAINT "rate_card_version_lines_cost_check"
        CHECK ("costAmount" IS NULL OR "costAmount" >= 0),
    CONSTRAINT "rate_card_version_lines_markup_check"
        CHECK ("markupRate" IS NULL OR ("markupRate" > 0 AND "markupRate" <= 1000)),
    -- A line that prices nothing is dead configuration.
    CONSTRAINT "rate_card_version_lines_prices_something_check"
        CHECK ("costAmount" IS NOT NULL OR "markupRate" IS NOT NULL)
);

ALTER TABLE ONLY "public"."rate_card_version_lines"
    ADD CONSTRAINT "rate_card_version_lines_version_tenant_fkey"
    FOREIGN KEY ("rateCardVersionId", "organizationId")
    REFERENCES "public"."rate_card_versions"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE CASCADE;

CREATE UNIQUE INDEX "rate_card_version_lines_dimension_unit_key"
    ON "public"."rate_card_version_lines" USING "btree"
       ("organizationId", "rateCardVersionId", "dimension", "unit");

CREATE INDEX "rate_card_version_lines_organizationId_version_idx"
    ON "public"."rate_card_version_lines" USING "btree"
       ("organizationId", "rateCardVersionId");

-- The `currency` on a line must be the currency of the version it belongs to.
-- That is a cross-row property, so it is asserted by V13 section 8 rather than
-- by a CHECK here, and it is recorded because a line priced in a different
-- currency from its card is the kind of mistake that only shows up in a
-- reconciliation.

-- ---------------------------------------------------------------------
-- finance_periods -- OPEN / IN_REVIEW / LOCKED / REOPENED
-- ---------------------------------------------------------------------
CREATE TABLE "public"."finance_periods" (
    "id"                 uuid NOT NULL,
    "organizationId"     uuid NOT NULL,
    "projectId"          uuid,
    "key"                text NOT NULL,
    "nameAr"             text NOT NULL,
    "nameEn"             text NOT NULL,
    "periodStart"        date NOT NULL,
    -- INCLUSIVE: the question "was this event inside the period" must have one
    -- answer, and an exclusive end would make adjacent periods overlap on their
    -- boundary day.
    "periodEnd"          date NOT NULL,
    "currency"           text NOT NULL,
    "status"             "public"."PeriodStatus" NOT NULL DEFAULT 'OPEN',
    "underReviewAt"      timestamp(3) without time zone,
    "lockedAt"           timestamp(3) without time zone,
    "lockedByUserId"     uuid,
    "lockNote"           text,
    "reopenedAt"         timestamp(3) without time zone,
    "reopenedByUserId"   uuid,
    "reopenReason"       text,
    "reopenCount"        integer NOT NULL DEFAULT 0,
    "version"            integer NOT NULL DEFAULT 0,
    "createdAt"          timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    "updatedAt"          timestamp(3) without time zone NOT NULL,
    CONSTRAINT "finance_periods_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "finance_periods_key_check"
        CHECK (length(btrim("key")) > 0),
    CONSTRAINT "finance_periods_name_ar_check"
        CHECK (length(btrim("nameAr")) > 0),
    CONSTRAINT "finance_periods_name_en_check"
        CHECK (length(btrim("nameEn")) > 0),
    -- A period that ends before it begins contains nothing and would lock
    -- instantly.
    CONSTRAINT "finance_periods_range_check"
        CHECK ("periodEnd" >= "periodStart"),
    CONSTRAINT "finance_periods_currency_check"
        CHECK ("public"."firstelement_currency_code"("currency")),
    -- ONE-WAY, not a biconditional: see the header. Being LOCKED demands the audit
    -- columns; the audit columns may OUTLIVE the lock, because a REOPENED period
    -- keeps them and clearing them would make "was this ever closed?"
    -- unanswerable from this row.
    CONSTRAINT "finance_periods_locked_check"
        CHECK ("status" <> 'LOCKED'::"public"."PeriodStatus"
               OR ("lockedAt" IS NOT NULL AND "lockedByUserId" IS NOT NULL)),
    -- The review flag belongs to IN_REVIEW and to nothing else.
    CONSTRAINT "finance_periods_review_check"
        CHECK (("status" = 'IN_REVIEW'::"public"."PeriodStatus")
               = ("underReviewAt" IS NOT NULL)),
    -- A REOPEN is an explicit, permissioned, audited action (ADR-0007 rule 4),
    -- so it says who, when AND why. The reason is the part that is usually
    -- dropped, and it is the part an auditor asks for.
    CONSTRAINT "finance_periods_reopened_check"
        CHECK (("status" = 'REOPENED'::"public"."PeriodStatus")
               = ("reopenedAt" IS NOT NULL AND "reopenedByUserId" IS NOT NULL
                  AND "reopenReason" IS NOT NULL)),
    -- The two lock columns and the two reopen columns are separate stories: a
    -- period that was locked, then reopened, keeps BOTH sets.
    CONSTRAINT "finance_periods_reopen_requires_lock_check"
        CHECK ("reopenedAt" IS NULL OR "lockedAt" IS NOT NULL),
    CONSTRAINT "finance_periods_reopen_count_check"
        CHECK ("reopenCount" >= 0),
    CONSTRAINT "finance_periods_reopen_count_state_check"
        CHECK (("reopenCount" > 0) = ("reopenedAt" IS NOT NULL)),
    CONSTRAINT "finance_periods_version_check"
        CHECK ("version" >= 0)
);

CREATE UNIQUE INDEX "finance_periods_id_organizationId_key"
    ON "public"."finance_periods" USING "btree" ("id", "organizationId");

-- One period per tenant per (currency, start, end): two overlapping periods with
-- the same bounds would both claim the same events.
CREATE UNIQUE INDEX "finance_periods_bounds_key"
    ON "public"."finance_periods" USING "btree"
       ("organizationId", "currency", "periodStart", "periodEnd");

CREATE UNIQUE INDEX "finance_periods_organizationId_key_key"
    ON "public"."finance_periods" USING "btree" ("organizationId", "key");

CREATE INDEX "finance_periods_organizationId_status_idx"
    ON "public"."finance_periods" USING "btree" ("organizationId", "status");

-- The close run: which periods are due to be reviewed or locked, in order.
CREATE INDEX "finance_periods_organizationId_periodEnd_status_idx"
    ON "public"."finance_periods" USING "btree"
       ("organizationId", "periodEnd", "status");

ALTER TABLE ONLY "public"."finance_periods"
    ADD CONSTRAINT "finance_periods_locked_by_tenant_fkey"
    FOREIGN KEY ("lockedByUserId", "organizationId")
    REFERENCES "public"."users"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

ALTER TABLE ONLY "public"."finance_periods"
    ADD CONSTRAINT "finance_periods_reopened_by_tenant_fkey"
    FOREIGN KEY ("reopenedByUserId", "organizationId")
    REFERENCES "public"."users"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

-- V20261005080000 declared usage_events."billingPeriodId" as a bare uuid and
-- said so in its own comment, because a migration must not reference an object a
-- later migration creates. This is where it becomes a COMPOSITE reference, so a
-- usage event may only be billed to a period of its OWN tenant -- a single-column
-- FK would permit exactly that, and a cross-tenant period reference is a finance
-- number appearing on the wrong tenant's invoice.
ALTER TABLE ONLY "public"."usage_events"
    ADD CONSTRAINT "usage_events_period_tenant_fkey"
    FOREIGN KEY ("billingPeriodId", "organizationId")
    REFERENCES "public"."finance_periods"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

-- ---------------------------------------------------------------------
-- finance_period_lock_events -- APPEND-ONLY history
-- ---------------------------------------------------------------------
CREATE TABLE "public"."finance_period_lock_events" (
    "id"                 uuid NOT NULL,
    "organizationId"     uuid NOT NULL,
    "projectId"          uuid,
    "periodId"           uuid NOT NULL,
    "eventType"          text NOT NULL,
    "actorUserId"        uuid,
    "actorPrincipal"     text NOT NULL,
    "note"               text,
    "eventAt"            timestamp(3) without time zone NOT NULL,
    -- NO updatedAt.
    "createdAt"          timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    CONSTRAINT "finance_period_lock_events_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "finance_period_lock_events_event_type_check"
        CHECK ("eventType" IN ('LOCKED', 'UNLOCKED', 'REOPENED',
                               'UNDER_REVIEW', 'ADJUSTMENT_APPROVED',
                               'ADJUSTMENT_REJECTED', 'SNAPSHOT_REFRESHED')),
    CONSTRAINT "finance_period_lock_events_actor_check"
        CHECK ("actorUserId" IS NOT NULL OR length(btrim("actorPrincipal")) > 0),
    CONSTRAINT "finance_period_lock_events_actor_principal_check"
        CHECK (length(btrim("actorPrincipal")) > 0),
    -- A lock or a reopen that does not say why is an action nobody can review.
    -- These are the two events that change a period's disposition.
    CONSTRAINT "finance_period_lock_events_note_check"
        CHECK ("eventType" NOT IN ('LOCKED', 'REOPENED', 'UNLOCKED')
               OR ("note" IS NOT NULL AND length(btrim("note")) > 0))
);

ALTER TABLE ONLY "public"."finance_period_lock_events"
    ADD CONSTRAINT "finance_period_lock_events_period_tenant_fkey"
    FOREIGN KEY ("periodId", "organizationId")
    REFERENCES "public"."finance_periods"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."finance_period_lock_events"
    ADD CONSTRAINT "finance_period_lock_events_actor_tenant_fkey"
    FOREIGN KEY ("actorUserId", "organizationId")
    REFERENCES "public"."users"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

CREATE INDEX "finance_period_lock_events_organizationId_period_idx"
    ON "public"."finance_period_lock_events" USING "btree"
       ("organizationId", "periodId", "eventAt");

CREATE INDEX "finance_period_lock_events_organizationId_eventType_idx"
    ON "public"."finance_period_lock_events" USING "btree"
       ("organizationId", "eventType", "eventAt");

-- ---------------------------------------------------------------------
-- usage_rollup_snapshots -- APPEND-ONLY, REVENUE DERIVED AND PINNED
-- ---------------------------------------------------------------------
CREATE TABLE "public"."usage_rollup_snapshots" (
    "id"                 uuid NOT NULL,
    "organizationId"     uuid NOT NULL,
    "projectId"          uuid NOT NULL,
    "periodId"           uuid,
    "grain"              "public"."RollupGrain" NOT NULL,
    -- The bucket start. A `date`, because a rollup bucket is a calendar day or
    -- month and a timestamp would imply a time-of-day effect that does not exist.
    "bucketStart"        date NOT NULL,
    "bucketEnd"          date NOT NULL,
    "dimension"          "public"."UsageDimension" NOT NULL,
    "currency"           text NOT NULL,
    -- HOW MANY USAGE EVENTS PRODUCED THIS ROW. >= 1, always: a bucket with no
    -- contributing events must not be materialised, because materialising it as
    -- zero is how "no data" becomes "zero" in a dashboard (ADR-0007 rule 5).
    "sourceEventCount"   bigint NOT NULL,
    "quantity"           numeric(20,6) NOT NULL,
    "costAmount"         numeric(20,6) NOT NULL,
    -- THE DERIVED AMOUNT. Present exactly when the row is priced.
    "revenueAmount"      numeric(20,6),
    -- THE PINNED MARKUP VALUE -- not a lookup. This is what makes "changing a
    -- rate card never mutates a closed snapshot" (ADR-0007 rule 2) a property of
    -- the row rather than a promise: the arithmetic that produced revenueAmount
    -- is stored next to it.
    "markupRate"         numeric(12,6),
    "rateCardVersionId"  uuid,
    "unpricedQuantity"   numeric(20,6) NOT NULL DEFAULT 0,
    "unpricedCostAmount" numeric(20,6) NOT NULL DEFAULT 0,
    -- A RECOMPUTATION IS A NEW GENERATION, NEVER AN UPDATE. `generation` starts
    -- at 1; a recomputed bucket inserts generation 2 and points at what it
    -- replaces.
    "generation"         integer NOT NULL DEFAULT 1,
    "supersedesSnapshotId" uuid,
    "computedAt"         timestamp(3) without time zone NOT NULL,
    "computedByPrincipal" text NOT NULL,
    "correlationId"      text,
    -- NO updatedAt, and NO margin column (margin is derived: revenue - cost).
    "createdAt"          timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    CONSTRAINT "usage_rollup_snapshots_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "usage_rollup_snapshots_bucket_check"
        CHECK ("bucketEnd" >= "bucketStart"),
    -- ZERO IS NOT "NO DATA".
    CONSTRAINT "usage_rollup_snapshots_source_count_check"
        CHECK ("sourceEventCount" >= 1),
    CONSTRAINT "usage_rollup_snapshots_quantity_check"
        CHECK ("quantity" >= 0),
    CONSTRAINT "usage_rollup_snapshots_cost_check"
        CHECK ("costAmount" >= 0),
    CONSTRAINT "usage_rollup_snapshots_unpriced_quantity_check"
        CHECK ("unpricedQuantity" >= 0),
    CONSTRAINT "usage_rollup_snapshots_unpriced_cost_check"
        CHECK ("unpricedCostAmount" >= 0),
    -- THE PRICING BICONDITIONAL. "Priced" means all three of rateCardVersionId,
    -- markupRate and revenueAmount are present; "unpriced" means all three are
    -- absent. There is no state in which a revenue exists with no rate behind it,
    -- and no state in which a rate exists with no revenue computed from it.
    CONSTRAINT "usage_rollup_snapshots_pricing_pin_check"
        CHECK ((("rateCardVersionId" IS NULL AND "markupRate" IS NULL
                 AND "revenueAmount" IS NULL)
               OR ("rateCardVersionId" IS NOT NULL AND "markupRate" IS NOT NULL
                   AND "revenueAmount" IS NOT NULL))),
    -- THE DERIVATION ITSELF. revenueAmount must equal costAmount x markupRate,
    -- exactly. A hand-entered revenue that does not equal its own inputs is
    -- refused by the database, so "where did this number come from" always has an
    -- arithmetic answer and an auditor can always check it.
    CONSTRAINT "usage_rollup_snapshots_derivation_check"
        CHECK ("revenueAmount" IS NULL
               OR "revenueAmount" = round("costAmount" * "markupRate", 6)),
    CONSTRAINT "usage_rollup_snapshots_markup_check"
        CHECK ("markupRate" IS NULL OR ("markupRate" > 0 AND "markupRate" <= 1000)),
    CONSTRAINT "usage_rollup_snapshots_currency_check"
        CHECK ("public"."firstelement_currency_code"("currency")),
    CONSTRAINT "usage_rollup_snapshots_generation_check"
        CHECK ("generation" >= 1),
    CONSTRAINT "usage_rollup_snapshots_supersede_self_check"
        CHECK ("supersedesSnapshotId" IS NULL OR "supersedesSnapshotId" <> "id"),
    -- Generation 1 has nothing to supersede, and generation > 1 must name what it
    -- replaces -- otherwise two rows both claim to be the current bucket.
    CONSTRAINT "usage_rollup_snapshots_generation_pair_check"
        CHECK (("generation" = 1) = ("supersedesSnapshotId" IS NULL)),
    -- An unpriced row must actually have unpriced usage on it, or "unpriced" is
    -- being used to mean "we did not bother to price this" and the first-class
    -- unpriced view becomes a dumping ground.
    CONSTRAINT "usage_rollup_snapshots_unpriced_pair_check"
        CHECK (("rateCardVersionId" IS NOT NULL)
               OR ("unpricedQuantity" > 0 OR "unpricedCostAmount" > 0)),
    CONSTRAINT "usage_rollup_snapshots_computed_by_check"
        CHECK (length(btrim("computedByPrincipal")) > 0)
);

-- Composite tenant keys declared BEFORE the foreign keys, because the snapshot's
-- own composite FK needs its target to exist first.
CREATE UNIQUE INDEX "usage_rollup_snapshots_id_organizationId_key"
    ON "public"."usage_rollup_snapshots" USING "btree" ("id", "organizationId");

-- One current row per (tenant, project, grain, bucket, dimension, generation).
-- The generation is part of the key because a recomputation ADDS a row; without
-- it the unique index would refuse the recomputation that the supersede pointer
-- exists to record.
CREATE UNIQUE INDEX "usage_rollup_snapshots_bucket_generation_key"
    ON "public"."usage_rollup_snapshots" USING "btree"
       ("organizationId", "projectId", "grain", "bucketStart", "dimension",
        "generation");

-- "The current snapshot for this bucket": the read every dashboard hits. Partial,
-- so only the highest generation is indexed for it.
CREATE UNIQUE INDEX "usage_rollup_snapshots_current_bucket_key"
    ON "public"."usage_rollup_snapshots" USING "btree"
       ("organizationId", "projectId", "grain", "bucketStart", "dimension")
    WHERE "supersedesSnapshotId" IS NULL;

-- A period close: every snapshot inside a period, for one tenant.
CREATE INDEX "usage_rollup_snapshots_organizationId_period_idx"
    ON "public"."usage_rollup_snapshots" USING "btree"
       ("organizationId", "periodId", "grain", "bucketStart");

-- The "which rate priced this" drill-down (ADR-0007 rule 1).
CREATE INDEX "usage_rollup_snapshots_organizationId_rateCardVersion_idx"
    ON "public"."usage_rollup_snapshots" USING "btree"
       ("organizationId", "rateCardVersionId");

-- The unpriced view: what exists but could not be priced.
CREATE INDEX "usage_rollup_snapshots_unpriced_idx"
    ON "public"."usage_rollup_snapshots" USING "btree"
       ("organizationId", "bucketStart")
    WHERE "rateCardVersionId" IS NULL;

ALTER TABLE ONLY "public"."usage_rollup_snapshots"
    ADD CONSTRAINT "usage_rollup_snapshots_organizationId_fkey"
    FOREIGN KEY ("organizationId") REFERENCES "public"."organizations"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."usage_rollup_snapshots"
    ADD CONSTRAINT "usage_rollup_snapshots_projectId_fkey"
    FOREIGN KEY ("projectId") REFERENCES "public"."projects"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."usage_rollup_snapshots"
    ADD CONSTRAINT "usage_rollup_snapshots_period_tenant_fkey"
    FOREIGN KEY ("periodId", "organizationId")
    REFERENCES "public"."finance_periods"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

-- COMPOSITE: pinning another tenant's rate card version would put one tenant's
-- price on another tenant's revenue. The single-column FK permits it.
ALTER TABLE ONLY "public"."usage_rollup_snapshots"
    ADD CONSTRAINT "usage_rollup_snapshots_rate_card_tenant_fkey"
    FOREIGN KEY ("rateCardVersionId", "organizationId")
    REFERENCES "public"."rate_card_versions"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

ALTER TABLE ONLY "public"."usage_rollup_snapshots"
    ADD CONSTRAINT "usage_rollup_snapshots_supersedes_tenant_fkey"
    FOREIGN KEY ("supersedesSnapshotId", "organizationId")
    REFERENCES "public"."usage_rollup_snapshots"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

-- "Closed periods are immutable" (ADR-0007 rule 4) cannot be a CHECK constraint:
-- the locked state lives in ANOTHER ROW. It is a trigger, and a trigger is
-- droppable, which a CHECK is not.
CREATE OR REPLACE FUNCTION "public"."usage_rollup_snapshots_reject_locked_period"()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    period_state "public"."PeriodStatus";
BEGIN
    IF NEW."periodId" IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT "status" INTO period_state
      FROM "public"."finance_periods"
     WHERE id = NEW."periodId"
       AND "organizationId" = NEW."organizationId";

    IF period_state IS NULL THEN
        -- The composite FK will reject this row anyway. Saying so here produces a
        -- clearer message than the FK's.
        RAISE EXCEPTION
            'usage_rollup_snapshots: period % does not exist in organisation %.',
            NEW."periodId", NEW."organizationId"
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF period_state IN ('LOCKED', 'IN_REVIEW') THEN
        RAISE EXCEPTION
            'usage_rollup_snapshots: period % is %, so no new snapshot may be recorded '
            'against it (ADR-0007 rule 4: closed periods change only through an approved, '
            'audited correction). Reopen the period, or apply a finance_adjustments row.',
            NEW."periodId", period_state
            USING ERRCODE = 'restrict_violation';
    END IF;

    RETURN NEW;
END
$$;

COMMENT ON FUNCTION "public"."usage_rollup_snapshots_reject_locked_period"() IS
    'Phase 7-10a: BEFORE INSERT guard refusing a rollup snapshot against a LOCKED or '
    'IN_REVIEW finance period. A CHECK cannot see another row, so this is a trigger.';

CREATE TRIGGER "usage_rollup_snapshots_locked_period_guard"
    BEFORE INSERT ON "public"."usage_rollup_snapshots"
    FOR EACH ROW EXECUTE FUNCTION "public"."usage_rollup_snapshots_reject_locked_period"();

-- ---------------------------------------------------------------------
-- finance_adjustments -- a reasoned, approved correction
-- ---------------------------------------------------------------------
CREATE TABLE "public"."finance_adjustments" (
    "id"                 uuid NOT NULL,
    "organizationId"     uuid NOT NULL,
    "projectId"          uuid,
    "periodId"           uuid NOT NULL,
    "status"             "public"."AdjustmentStatus" NOT NULL DEFAULT 'PENDING',
    -- WHAT IT CORRECTS. ADR-0007: "an adjustment record linked to source events".
    -- Nullable because an adjustment can correct a whole period rather than one
    -- event -- but then it MUST say why, which the reason NOT NULL already
    -- requires.
    "adjustmentOfId"     uuid,
    "reason"             text NOT NULL,
    "amount"             numeric(20,6) NOT NULL,
    "currency"           text NOT NULL,
    "quantity"           numeric(20,6),
    "createdByUserId"    uuid NOT NULL,
    "approvedByUserId"   uuid,
    "approvedAt"         timestamp(3) without time zone,
    "approvalNote"       text,
    "rejectedByUserId"   uuid,
    "rejectedAt"         timestamp(3) without time zone,
    "rejectionReason"    text,
    "appliedAt"          timestamp(3) without time zone,
    "appliedByUserId"    uuid,
    "version"            integer NOT NULL DEFAULT 0,
    "createdAt"          timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    "updatedAt"          timestamp(3) without time zone NOT NULL,
    CONSTRAINT "finance_adjustments_pkey" PRIMARY KEY ("id"),
    -- A reason is never blank. An unexplained adjustment is indistinguishable
    -- from tampering with money, which is the failure mode this table exists to
    -- prevent.
    CONSTRAINT "finance_adjustments_reason_check"
        CHECK (length(btrim("reason")) > 0),
    -- A zero adjustment is not an adjustment; it is a record of having done
    -- nothing, which is what a cancelled request is.
    CONSTRAINT "finance_adjustments_amount_check"
        CHECK ("amount" <> 0),
    CONSTRAINT "finance_adjustments_quantity_check"
        CHECK ("quantity" IS NULL OR "quantity" <> 0),
    CONSTRAINT "finance_adjustments_currency_check"
        CHECK ("public"."firstelement_currency_code"("currency")),
    -- Approval and rejection are paired, and mutually exclusive. An adjustment
    -- that was both approved and rejected is not a state anybody can act on.
    CONSTRAINT "finance_adjustments_approval_pair_check"
        CHECK (("approvedAt" IS NULL) = ("approvedByUserId" IS NULL)),
    CONSTRAINT "finance_adjustments_rejection_pair_check"
        CHECK (("rejectedAt" IS NULL) = ("rejectedByUserId" IS NULL)),
    CONSTRAINT "finance_adjustments_decision_exclusive_check"
        CHECK (NOT ("approvedAt" IS NOT NULL AND "rejectedAt" IS NOT NULL)),
    -- A state says what happened. APPROVED/APPLIED name who and when; REJECTED
    -- also says why; PENDING carries no decision columns at all.
    CONSTRAINT "finance_adjustments_state_decision_check"
        CHECK (("status" = 'PENDING'::"public"."AdjustmentStatus")
               = ("approvedAt" IS NULL AND "rejectedAt" IS NULL)),
    CONSTRAINT "finance_adjustments_rejection_reason_check"
        CHECK ("status" <> 'REJECTED'::"public"."AdjustmentStatus"
               OR "rejectionReason" IS NOT NULL),
    -- Approval means somebody who is not the author approved it. plan.md forbids
    -- an unrestricted identity; a self-approval is the same problem in a finance
    -- costume.
    CONSTRAINT "finance_adjustments_not_self_approved_check"
        CHECK ("approvedByUserId" IS NULL OR "approvedByUserId" <> "createdByUserId"),
    CONSTRAINT "finance_adjustments_applied_requires_approved_check"
        CHECK ("appliedAt" IS NULL
               OR ("approvedAt" IS NOT NULL AND "appliedByUserId" IS NOT NULL)),
    CONSTRAINT "finance_adjustments_version_check"
        CHECK ("version" >= 0)
);

CREATE UNIQUE INDEX "finance_adjustments_id_organizationId_key"
    ON "public"."finance_adjustments" USING "btree" ("id", "organizationId");

-- The approval queue: this tenant's outstanding adjustments, oldest first.
CREATE INDEX "finance_adjustments_organizationId_status_created_idx"
    ON "public"."finance_adjustments" USING "btree"
       ("organizationId", "status", "createdAt");

CREATE INDEX "finance_adjustments_organizationId_period_idx"
    ON "public"."finance_adjustments" USING "btree"
       ("organizationId", "periodId");

ALTER TABLE ONLY "public"."finance_adjustments"
    ADD CONSTRAINT "finance_adjustments_period_tenant_fkey"
    FOREIGN KEY ("periodId", "organizationId")
    REFERENCES "public"."finance_periods"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

-- COMPOSITE: an adjustment may only correct a usage event of its OWN tenant.
ALTER TABLE ONLY "public"."finance_adjustments"
    ADD CONSTRAINT "finance_adjustments_event_tenant_fkey"
    FOREIGN KEY ("adjustmentOfId", "organizationId")
    REFERENCES "public"."usage_events"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

ALTER TABLE ONLY "public"."finance_adjustments"
    ADD CONSTRAINT "finance_adjustments_author_tenant_fkey"
    FOREIGN KEY ("createdByUserId", "organizationId")
    REFERENCES "public"."users"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

ALTER TABLE ONLY "public"."finance_adjustments"
    ADD CONSTRAINT "finance_adjustments_approver_tenant_fkey"
    FOREIGN KEY ("approvedByUserId", "organizationId")
    REFERENCES "public"."users"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

ALTER TABLE ONLY "public"."finance_adjustments"
    ADD CONSTRAINT "finance_adjustments_rejecter_tenant_fkey"
    FOREIGN KEY ("rejectedByUserId", "organizationId")
    REFERENCES "public"."users"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

ALTER TABLE ONLY "public"."finance_adjustments"
    ADD CONSTRAINT "finance_adjustments_applier_tenant_fkey"
    FOREIGN KEY ("appliedByUserId", "organizationId")
    REFERENCES "public"."users"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

-- ---------------------------------------------------------------------
-- finance_reconciliation_runs -- APPEND-ONLY
-- ---------------------------------------------------------------------
CREATE TABLE "public"."finance_reconciliation_runs" (
    "id"                 uuid NOT NULL,
    "organizationId"     uuid NOT NULL,
    "projectId"          uuid,
    "periodId"           uuid NOT NULL,
    "status"             "public"."ReconciliationStatus" NOT NULL,
    "sourceEventCount"   bigint NOT NULL,
    "snapshotAmount"     numeric(20,6) NOT NULL,
    -- WHAT THE LEDGER SAYS, recomputed independently of the rollup. Finance
    -- always reads the ledger (ADR-0007 rule 3), so the comparison is the whole
    -- point of the exercise.
    "ledgerAmount"       numeric(20,6) NOT NULL,
    -- DERIVED, and CHECKed to equal ledger - snapshot. A difference that is not
    -- the difference is a number somebody typed.
    "difference"         numeric(20,6) NOT NULL,
    "toleranceAmount"    numeric(20,6) NOT NULL DEFAULT 0,
    "runByUserId"        uuid,
    "runByPrincipal"     text NOT NULL,
    "notes"              text,
    "correlationId"      text,
    "ranAt"              timestamp(3) without time zone NOT NULL,
    -- NO updatedAt.
    "createdAt"          timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    CONSTRAINT "finance_reconciliation_runs_pkey" PRIMARY KEY ("id"),
    -- "no data" is a real answer and 0 events is one of them.
    CONSTRAINT "finance_reconciliation_runs_source_count_check"
        CHECK ("sourceEventCount" >= 0),
    CONSTRAINT "finance_reconciliation_runs_difference_check"
        CHECK ("difference" = "ledgerAmount" - "snapshotAmount"),
    CONSTRAINT "finance_reconciliation_runs_tolerance_check"
        CHECK ("toleranceAmount" >= 0),
    -- MATCHED means the difference is within tolerance. The other two states must
    -- say something, because a MISMATCH that nobody explained gets accepted by
    -- silence.
    CONSTRAINT "finance_reconciliation_runs_status_check"
        CHECK (("status" = 'MATCHED'::"public"."ReconciliationStatus")
               OR ("notes" IS NOT NULL AND length(btrim("notes")) > 0)),
    CONSTRAINT "finance_reconciliation_runs_actor_check"
        CHECK ("runByUserId" IS NOT NULL OR length(btrim("runByPrincipal")) > 0),
    CONSTRAINT "finance_reconciliation_runs_principal_check"
        CHECK (length(btrim("runByPrincipal")) > 0),
    CONSTRAINT "finance_reconciliation_runs_amounts_check"
        CHECK (abs("snapshotAmount") < numeric '99999999999999.999999'
               AND abs("ledgerAmount") < numeric '99999999999999.999999')
);

CREATE UNIQUE INDEX "finance_reconciliation_runs_id_organizationId_key"
    ON "public"."finance_reconciliation_runs" USING "btree" ("id", "organizationId");

CREATE INDEX "finance_reconciliation_runs_organizationId_period_idx"
    ON "public"."finance_reconciliation_runs" USING "btree"
       ("organizationId", "periodId", "ranAt");

CREATE INDEX "finance_reconciliation_runs_organizationId_status_idx"
    ON "public"."finance_reconciliation_runs" USING "btree"
       ("organizationId", "status", "ranAt");

ALTER TABLE ONLY "public"."finance_reconciliation_runs"
    ADD CONSTRAINT "finance_reconciliation_runs_period_tenant_fkey"
    FOREIGN KEY ("periodId", "organizationId")
    REFERENCES "public"."finance_periods"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

ALTER TABLE ONLY "public"."finance_reconciliation_runs"
    ADD CONSTRAINT "finance_reconciliation_runs_actor_tenant_fkey"
    FOREIGN KEY ("runByUserId", "organizationId")
    REFERENCES "public"."users"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

-- ---------------------------------------------------------------------
-- finance_reconciliation_lines -- APPEND-ONLY, the per-dimension drill-down
-- ---------------------------------------------------------------------
CREATE TABLE "public"."finance_reconciliation_lines" (
    "id"                 uuid NOT NULL,
    "organizationId"     uuid NOT NULL,
    "projectId"          uuid NOT NULL,
    "reconciliationRunId" uuid NOT NULL,
    "dimension"          "public"."UsageDimension" NOT NULL,
    "sourceEventCount"   bigint NOT NULL,
    "snapshotQuantity"   numeric(20,6) NOT NULL,
    "ledgerQuantity"     numeric(20,6) NOT NULL,
    "snapshotCost"       numeric(20,6) NOT NULL,
    "ledgerCost"         numeric(20,6) NOT NULL,
    "quantityDifference" numeric(20,6) NOT NULL,
    "costDifference"     numeric(20,6) NOT NULL,
    "status"             "public"."ReconciliationStatus" NOT NULL,
    "note"               text,
    -- NO updatedAt.
    "createdAt"          timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    CONSTRAINT "finance_reconciliation_lines_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "finance_reconciliation_lines_source_count_check"
        CHECK ("sourceEventCount" >= 0),
    -- BOTH differences are derived and CHECKed. A reconciliation line whose
    -- difference is not the difference is worse than no reconciliation.
    CONSTRAINT "finance_reconciliation_lines_quantity_difference_check"
        CHECK ("quantityDifference" = "ledgerQuantity" - "snapshotQuantity"),
    CONSTRAINT "finance_reconciliation_lines_cost_difference_check"
        CHECK ("costDifference" = "ledgerCost" - "snapshotCost"),
    CONSTRAINT "finance_reconciliation_lines_quantities_check"
        CHECK ("snapshotQuantity" >= 0 AND "ledgerQuantity" >= 0),
    CONSTRAINT "finance_reconciliation_lines_status_note_check"
        CHECK ("status" = 'MATCHED'::"public"."ReconciliationStatus"
               OR ("note" IS NOT NULL AND length(btrim("note")) > 0))
);

ALTER TABLE ONLY "public"."finance_reconciliation_lines"
    ADD CONSTRAINT "finance_reconciliation_lines_run_tenant_fkey"
    FOREIGN KEY ("reconciliationRunId", "organizationId")
    REFERENCES "public"."finance_reconciliation_runs"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE CASCADE;

-- One line per dimension per run: a reconciliation that reports the same
-- dimension twice is a reconciliation that cannot be summed.
CREATE UNIQUE INDEX "finance_reconciliation_lines_run_dimension_key"
    ON "public"."finance_reconciliation_lines" USING "btree"
       ("organizationId", "reconciliationRunId", "dimension");

CREATE INDEX "finance_reconciliation_lines_organizationId_run_idx"
    ON "public"."finance_reconciliation_lines" USING "btree"
       ("organizationId", "reconciliationRunId");

-- ---------------------------------------------------------------------
-- The append-only triggers.
-- ---------------------------------------------------------------------
-- Attached to the six evidence tables. Deliberately NOT attached to
-- rate_cards, finance_periods or finance_adjustments, which are mutable state
-- machines: a period has to be lockable and a card has to be retirable, and both
-- of those are audited by their own history tables rather than by being frozen.
-- NOT firstelement_append_only(): this table has one legitimate UPDATE and the
-- blanket guard would make RETIRED unreachable, which would leave
-- rate_card_versions_retired_check dead code. See the migration header.
CREATE TRIGGER "rate_card_versions_append_only"
    BEFORE UPDATE OR DELETE ON "public"."rate_card_versions"
    FOR EACH ROW EXECUTE FUNCTION "public"."firstelement_rate_card_version_guard"();

CREATE TRIGGER "rate_card_version_lines_append_only"
    BEFORE UPDATE OR DELETE ON "public"."rate_card_version_lines"
    FOR EACH ROW EXECUTE FUNCTION "public"."firstelement_append_only"();

CREATE TRIGGER "finance_period_lock_events_append_only"
    BEFORE UPDATE OR DELETE ON "public"."finance_period_lock_events"
    FOR EACH ROW EXECUTE FUNCTION "public"."firstelement_append_only"();

CREATE TRIGGER "usage_rollup_snapshots_append_only"
    BEFORE UPDATE OR DELETE ON "public"."usage_rollup_snapshots"
    FOR EACH ROW EXECUTE FUNCTION "public"."firstelement_append_only"();

CREATE TRIGGER "finance_reconciliation_runs_append_only"
    BEFORE UPDATE OR DELETE ON "public"."finance_reconciliation_runs"
    FOR EACH ROW EXECUTE FUNCTION "public"."firstelement_append_only"();

CREATE TRIGGER "finance_reconciliation_lines_append_only"
    BEFORE UPDATE OR DELETE ON "public"."finance_reconciliation_lines"
    FOR EACH ROW EXECUTE FUNCTION "public"."firstelement_append_only"();

-- The one trigger here that attaches to a table created by the PREVIOUS
-- migration. V20261005080000 shipped usage_events append-only by shape alone and
-- recorded the missing trigger as debt; this is that debt paid. See the header.
CREATE TRIGGER "usage_events_append_only"
    BEFORE UPDATE OR DELETE ON "public"."usage_events"
    FOR EACH ROW EXECUTE FUNCTION "public"."firstelement_append_only"();

-- ---------------------------------------------------------------------
-- Post-condition guard.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    missing        text;
    ok_tables      text[] := ARRAY[
        'rate_cards', 'rate_card_versions', 'rate_card_version_lines',
        'finance_periods', 'finance_period_lock_events',
        'usage_rollup_snapshots', 'finance_adjustments',
        'finance_reconciliation_runs', 'finance_reconciliation_lines'];
    -- rate_card_versions is listed separately because it carries the narrower
    -- firstelement_rate_card_version_guard(), not the blanket one. Both are
    -- "append-only"; only one is "frozen".
    append_only    text[] := ARRAY[
        'usage_events',
        'rate_card_version_lines',
        'finance_period_lock_events', 'usage_rollup_snapshots',
        'finance_reconciliation_runs', 'finance_reconciliation_lines'];
    narrow_guard   text[] := ARRAY['rate_card_versions'];
    mutable_tables text[] := ARRAY[
        'rate_cards', 'finance_periods', 'finance_adjustments'];
    margin_column  text;
    missing_trig   text;
BEGIN
    SELECT string_agg(x, ', ')
      INTO missing
      FROM unnest(ARRAY[
               'rate_cards_id_organizationId_key',
               'rate_cards_organizationId_key_key',
               'rate_cards_organizationId_status_idx',
               'rate_card_versions_id_organizationId_key',
               'rate_card_versions_card_version_key',
               'rate_card_versions_active_effective_idx',
               'rate_card_versions_one_start_per_day',
               'rate_card_version_lines_dimension_unit_key',
               'rate_card_version_lines_organizationId_version_idx',
               'finance_periods_id_organizationId_key',
               'finance_periods_bounds_key',
               'finance_periods_organizationId_key_key',
               'finance_periods_organizationId_status_idx',
               'finance_periods_organizationId_periodEnd_status_idx',
               'finance_period_lock_events_organizationId_period_idx',
               'finance_period_lock_events_organizationId_eventType_idx',
               'usage_rollup_snapshots_id_organizationId_key',
               'usage_rollup_snapshots_bucket_generation_key',
               'usage_rollup_snapshots_current_bucket_key',
               'usage_rollup_snapshots_organizationId_period_idx',
               'usage_rollup_snapshots_organizationId_rateCardVersion_idx',
               'usage_rollup_snapshots_unpriced_idx',
               'finance_adjustments_id_organizationId_key',
               'finance_adjustments_organizationId_status_created_idx',
               'finance_adjustments_organizationId_period_idx',
               'finance_reconciliation_runs_id_organizationId_key',
               'finance_reconciliation_runs_organizationId_period_idx',
               'finance_reconciliation_runs_organizationId_status_idx',
               'finance_reconciliation_lines_run_dimension_key',
               'finance_reconciliation_lines_organizationId_run_idx'
           ]) AS x
     WHERE NOT EXISTS (SELECT 1 FROM pg_indexes
                        WHERE schemaname = 'public' AND indexname = x);

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'V20261005090000 failed: missing indexes: %', missing;
    END IF;

    SELECT string_agg(t, ', ')
      INTO missing
      FROM unnest(ok_tables) AS t
     WHERE to_regclass('public.' || quote_ident(t)) IS NULL;

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'V20261005090000 failed: missing tables: %', missing;
    END IF;

    -- 1. NO MARGIN COLUMN, ANYWHERE. Margin is derived (revenue - cost) and is
    --    "never displayed where the row is unpriced" (ADR-0007), so there is no
    --    legal place for it on a row whose pricing is optional. Note what this
    --    check does NOT refuse: usage_rollup_snapshots.revenueAmount and
    --    finance_*_difference columns. Those are derived and STORED on purpose,
    --    and both are CHECKed to equal their own inputs -- storing a derived
    --    value next to the inputs it was derived from is what makes it auditable.
    --    The first version of this guard also matched '%revenue%' and therefore
    --    failed on the file's own revenueAmount column; the throwaway rehearsal
    --    caught it, and the lesson is that a guard must not match the columns it
    --    deliberately created.
    SELECT string_agg(c.relname || '.' || a.attname, ', ')
      INTO margin_column
      FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_attribute a ON a.attrelid = c.oid
                              AND a.attnum > 0 AND NOT a.attisdropped
     WHERE n.nspname = 'public'
       AND c.relname IN (SELECT unnest(ok_tables))
       AND a.attname ILIKE '%margin%';

    IF margin_column IS NOT NULL THEN
        RAISE EXCEPTION
            'V20261005090000 failed: a margin column exists (%); margin is DERIVED and is '
            'never shown on an unpriced row.', margin_column;
    END IF;

    -- 2. APPEND-ONLY SHAPE: the six evidence tables must NOT have updatedAt, and
    --    the three state machines MUST have it.
    SELECT string_agg(c.relname, ', ')
      INTO missing
      FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_attribute a ON a.attrelid = c.oid
                              AND a.attname = 'updatedAt'
                              AND a.attnum > 0 AND NOT a.attisdropped
     WHERE n.nspname = 'public'
       AND c.relname IN (SELECT unnest(append_only));

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION
            'V20261005090000 failed: these tables are append-only and must not have '
            'updatedAt: %', missing;
    END IF;

    SELECT string_agg(x, ', ')
      INTO missing
      FROM unnest(mutable_tables) AS x
     WHERE NOT EXISTS (SELECT 1 FROM pg_attribute a
                        JOIN pg_class c ON c.oid = a.attrelid
                        JOIN pg_namespace n ON n.oid = c.relnamespace
                       WHERE n.nspname = 'public' AND c.relname = x
                         AND a.attname = 'updatedAt'
                         AND a.attnum > 0 AND NOT a.attisdropped);

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION
            'V20261005090000 failed: these tables are mutable state machines and must '
            'have updatedAt: %', missing;
    END IF;

    -- 3. THE APPEND-ONLY TRIGGERS MUST EXIST ON ALL SIX TABLES. A missing trigger
    --    would leave "append-only enforced by convention", which the draft
    --    explicitly rejected.
    SELECT string_agg(x, ', ')
      INTO missing_trig
      FROM unnest(append_only) AS x
     WHERE NOT EXISTS (SELECT 1 FROM pg_trigger tg
                         JOIN pg_class c ON c.oid = tg.tgrelid
                         JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = 'public' AND c.relname = x
                          AND tg.tgname = x || '_append_only'
                          AND NOT tg.tgisinternal);

    IF missing_trig IS NOT NULL THEN
        RAISE EXCEPTION
            'V20261005090000 failed: missing append-only triggers on: %', missing_trig;
    END IF;

    -- 3a. The NARROW guard must exist and must be the narrow one. A migration that
    --     silently swapped it back to firstelement_append_only() would leave
    --     rate_card_versions_retired_check unreachable, which is the exact defect
    --     this guard was rewritten to fix -- so it is asserted, not assumed.
    SELECT string_agg(x, ', ')
      INTO missing_trig
      FROM unnest(narrow_guard) AS x
     WHERE NOT EXISTS (
             SELECT 1 FROM pg_trigger tg
               JOIN pg_class c ON c.oid = tg.tgrelid
               JOIN pg_namespace n ON n.oid = c.relnamespace
               JOIN pg_proc p ON p.oid = tg.tgfoid
              WHERE n.nspname = 'public' AND c.relname = x
                AND tg.tgname = x || '_append_only'
                AND NOT tg.tgisinternal
                AND p.proname = 'firstelement_rate_card_version_guard');

    IF missing_trig IS NOT NULL THEN
        RAISE EXCEPTION
            'V20261005090000 failed: % must carry firstelement_rate_card_version_guard(), '
            'not the blanket firstelement_append_only() -- the blanket guard refuses every '
            'UPDATE and would make the RETIRED status unreachable.', missing_trig;
    END IF;

    -- 4. THE LOCKED-PERIOD GUARD MUST EXIST. It is the only thing standing
    --    between a closed period and a new snapshot, and it is a trigger because
    --    a CHECK cannot read another row.
    IF NOT EXISTS (SELECT 1 FROM pg_trigger tg
                     JOIN pg_class c ON c.oid = tg.tgrelid
                     JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'public'
                      AND c.relname = 'usage_rollup_snapshots'
                      AND tg.tgname = 'usage_rollup_snapshots_locked_period_guard'
                      AND NOT tg.tgisinternal) THEN
        RAISE EXCEPTION
            'V20261005090000 failed: usage_rollup_snapshots_locked_period_guard is missing.';
    END IF;

    -- 5. FR-1.2 on all nine.
    SELECT string_agg(c.relname, ', ')
      INTO missing
      FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_attribute a ON a.attrelid = c.oid
                              AND a.attname = 'organizationId'
                              AND a.attnum > 0 AND NOT a.attisdropped
     WHERE n.nspname = 'public'
       AND c.relname IN (SELECT unnest(ok_tables))
       AND NOT a.attnotnull;

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION
            'V20261005090000 failed: organizationId must be NOT NULL (FR-1.2) on: %', missing;
    END IF;

    -- 6. projectId NOT NULL exactly on the two tables that aggregate a project.
    SELECT string_agg(c.relname, ', ')
      INTO missing
      FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_attribute a ON a.attrelid = c.oid
                              AND a.attname = 'projectId'
                              AND a.attnum > 0 AND NOT a.attisdropped
     WHERE n.nspname = 'public'
       AND c.relname IN ('usage_rollup_snapshots', 'finance_reconciliation_lines')
       AND NOT a.attnotnull;

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION
            'V20261005090000 failed: projectId must be NOT NULL on % -- a rollup is about '
            'one project''s usage.', missing;
    END IF;

    -- 7. V6 contract: no timestamptz.
    SELECT string_agg(c.relname, ', ')
      INTO missing
      FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_attribute a ON a.attrelid = c.oid
                              AND a.atttypid = 'timestamptz'::regtype
                              AND a.attnum > 0 AND NOT a.attisdropped
     WHERE n.nspname = 'public'
       AND c.relname IN (SELECT unnest(ok_tables));

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION
            'V20261005090000 failed: timestamptz column in: %', missing;
    END IF;

    -- 8. Every UTC-writing default must say UTC.
    SELECT string_agg(c.relname || '.' || a.attname, ', ')
      INTO missing
      FROM pg_attrdef d
        JOIN pg_class c ON c.oid = d.adrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = d.adnum
                              AND a.attnum > 0 AND NOT a.attisdropped
     WHERE n.nspname = 'public'
       AND c.relname IN (SELECT unnest(ok_tables))
       AND pg_get_expr(d.adbin, d.adrelid) LIKE '%CURRENT_TIMESTAMP%'
       AND pg_get_expr(d.adbin, d.adrelid) NOT LIKE '%AT TIME ZONE%';

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION
            'V20261005090000 failed: a default uses a session-local CURRENT_TIMESTAMP: %',
            missing;
    END IF;

    -- 9. Enum value lists ARE the contract.
    IF (SELECT string_agg(e.enumlabel, ',' ORDER BY e.enumsortorder)
          FROM pg_type t JOIN pg_enum e ON e.enumtypid = t.oid
          JOIN pg_namespace n ON n.oid = t.typnamespace
         WHERE n.nspname = 'public' AND t.typname = 'RateCardStatus')
       IS DISTINCT FROM 'DRAFT,ACTIVE,RETIRED' THEN
        RAISE EXCEPTION 'V20261005090000 failed: public.RateCardStatus value list drifted.';
    END IF;

    IF (SELECT string_agg(e.enumlabel, ',' ORDER BY e.enumsortorder)
          FROM pg_type t JOIN pg_enum e ON e.enumtypid = t.oid
          JOIN pg_namespace n ON n.oid = t.typnamespace
         WHERE n.nspname = 'public' AND t.typname = 'PeriodStatus')
       IS DISTINCT FROM 'OPEN,IN_REVIEW,LOCKED,REOPENED' THEN
        RAISE EXCEPTION 'V20261005090000 failed: public.PeriodStatus value list drifted.';
    END IF;

    IF (SELECT string_agg(e.enumlabel, ',' ORDER BY e.enumsortorder)
          FROM pg_type t JOIN pg_enum e ON e.enumtypid = t.oid
          JOIN pg_namespace n ON n.oid = t.typnamespace
         WHERE n.nspname = 'public' AND t.typname = 'RollupGrain')
       IS DISTINCT FROM 'DAILY,MONTHLY' THEN
        RAISE EXCEPTION 'V20261005090000 failed: public.RollupGrain value list drifted.';
    END IF;

    IF (SELECT string_agg(e.enumlabel, ',' ORDER BY e.enumsortorder)
          FROM pg_type t JOIN pg_enum e ON e.enumtypid = t.oid
          JOIN pg_namespace n ON n.oid = t.typnamespace
         WHERE n.nspname = 'public' AND t.typname = 'ReconciliationStatus')
       IS DISTINCT FROM 'MATCHED,MISMATCH,ACCEPTED' THEN
        RAISE EXCEPTION
            'V20261005090000 failed: public.ReconciliationStatus value list drifted.';
    END IF;

    IF (SELECT string_agg(e.enumlabel, ',' ORDER BY e.enumsortorder)
          FROM pg_type t JOIN pg_enum e ON e.enumtypid = t.oid
          JOIN pg_namespace n ON n.oid = t.typnamespace
         WHERE n.nspname = 'public' AND t.typname = 'AdjustmentStatus')
       IS DISTINCT FROM 'PENDING,APPROVED,REJECTED,APPLIED' THEN
        RAISE EXCEPTION 'V20261005090000 failed: public.AdjustmentStatus value list drifted.';
    END IF;

    -- 10. THE PERIOD REFERENCE MUST BE COMPOSITE. A single-column FK from
    --     usage_events.billingPeriodId would let one tenant's usage be billed to
    --     another tenant's period, which is a finance number on the wrong invoice.
    IF (SELECT count(*) FROM pg_constraint con
          JOIN pg_class c ON c.oid = con.conrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public'
           AND con.conname IN ('usage_events_period_tenant_fkey',
                               'usage_rollup_snapshots_period_tenant_fkey',
                               'usage_rollup_snapshots_rate_card_tenant_fkey',
                               'usage_rollup_snapshots_supersedes_tenant_fkey',
                               'finance_adjustments_period_tenant_fkey',
                               'finance_adjustments_event_tenant_fkey',
                               'rate_card_versions_card_tenant_fkey',
                               'rate_card_version_lines_version_tenant_fkey',
                               'finance_period_lock_events_period_tenant_fkey',
                               'finance_reconciliation_runs_period_tenant_fkey',
                               'finance_reconciliation_lines_run_tenant_fkey')
           AND con.contype = 'f') <> 11 THEN
        RAISE EXCEPTION
            'V20261005090000 failed: the eleven cross-table finance foreign keys must all '
            'exist as composite tenant keys.';
    END IF;

    -- 11. The append-only guard must be a real trigger function, not a stub.
    IF to_regprocedure('public.firstelement_append_only()') IS NULL
       OR NOT EXISTS (SELECT 1 FROM pg_proc p
                        JOIN pg_namespace n ON n.oid = p.pronamespace
                       WHERE n.nspname = 'public'
                         AND p.proname = 'firstelement_append_only'
                         AND p.prorettype = 'trigger'::regtype) THEN
        RAISE EXCEPTION
            'V20261005090000 failed: public.firstelement_append_only() must exist and must '
            'return trigger.';
    END IF;

    -- 11a. Same for the narrow rate-card guard. Two functions with similar names
    --      is a trap: a future migration that reaches for the wrong one re-creates
    --      the unreachable-RETIRED defect, so its existence is a post-condition.
    IF to_regprocedure('public.firstelement_rate_card_version_guard()') IS NULL
       OR NOT EXISTS (SELECT 1 FROM pg_proc p
                        JOIN pg_namespace n ON n.oid = p.pronamespace
                       WHERE n.nspname = 'public'
                         AND p.proname = 'firstelement_rate_card_version_guard'
                         AND p.prorettype = 'trigger'::regtype) THEN
        RAISE EXCEPTION
            'V20261005090000 failed: public.firstelement_rate_card_version_guard() must exist '
            'and must return trigger.';
    END IF;

    -- 12. Truncation detection.
    IF EXISTS (SELECT 1 FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename = 'finance_reconciliation_lines'
                  AND indexname NOT IN (
                      'finance_reconciliation_lines_pkey',
                      'finance_reconciliation_lines_run_dimension_key',
                      'finance_reconciliation_lines_organizationId_run_idx')) THEN
        RAISE EXCEPTION
            'V20261005090000 failed: an unexpected index exists on '
            'finance_reconciliation_lines -- most likely a name longer than 63 bytes was '
            'silently truncated.';
    END IF;
END
$$;