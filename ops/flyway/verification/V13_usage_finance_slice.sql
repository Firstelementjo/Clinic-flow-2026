-- =====================================================================
-- V13_usage_finance_slice.sql
-- Phase 7-10a / database verification: the immutable usage ledger and the
-- finance slice introduced by V20261005080000 and V20261005090000.
--
-- The point of this file is ADR-0007 Part 1 rule 2: "Rates are versioned and
-- effective-dated. A rate card version is pinned onto the rollup row at
-- computation time. Changing a rate card never mutates an already-computed
-- snapshot." Section 6 asserts that with DATA.
--
-- READ-ONLY. No DDL, no DML, no object created. Guarded: on the pre-cutover
-- database each section reports PHASE710A_NOT_PRESENT.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Presence.
-- ---------------------------------------------------------------------
SELECT CASE
         WHEN to_regclass('public.usage_events') IS NOT NULL
          AND to_regclass('public.usage_rollup_snapshots') IS NOT NULL
          AND to_regclass('public.rate_cards') IS NOT NULL
          AND to_regclass('public.rate_card_versions') IS NOT NULL
          AND to_regclass('public.finance_periods') IS NOT NULL
         THEN 'PHASE710A_FINANCE_PRESENT'
         WHEN to_regclass('public.usage_events') IS NULL
          AND to_regclass('public.usage_rollup_snapshots') IS NULL
          AND to_regclass('public.rate_cards') IS NULL
          AND to_regclass('public.rate_card_versions') IS NULL
          AND to_regclass('public.finance_periods') IS NULL
         THEN 'PHASE710A_NOT_PRESENT'
         ELSE 'PHASE710A_PARTIAL -- a Phase-7-10a finance migration failed halfway; stop'
       END AS phase7_10a_finance_state;

-- ---------------------------------------------------------------------
-- 2. Enum value lists.
-- ---------------------------------------------------------------------
SELECT t.typname AS enum_name,
       string_agg(e.enumlabel, ',' ORDER BY e.enumsortorder) AS enum_values
  FROM pg_type t
  JOIN pg_enum e ON e.enumtypid = t.oid
  JOIN pg_namespace n ON n.oid = t.typnamespace
 WHERE n.nspname = 'public'
   AND t.typname IN ('UsageDimension', 'RateCardStatus', 'PeriodStatus',
                     'RollupGrain', 'ReconciliationStatus', 'AdjustmentStatus')
 GROUP BY t.typname
 ORDER BY t.typname;

-- ---------------------------------------------------------------------
-- 3. THE NO-STORED-REVENUE RULE, checked by COLUMN NAME.
--    usage_events must carry cost and nothing derived. The name list is
--    explicit rather than a broad pattern: a pattern of '%rate%' would also
--    match the legitimate fxRateDate column.
-- ---------------------------------------------------------------------
SELECT c.relname AS table_name, a.attname AS column_name
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
  JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
 WHERE n.nspname = 'public'
   AND c.relname IN ('usage_events', 'usage_rollup_snapshots')
   AND (a.attname ILIKE '%revenue%'
        OR a.attname ILIKE '%billable%'
        OR a.attname ILIKE '%margin%'
        OR lower(a.attname) IN ('price', 'markup', 'markuprate'))
 ORDER BY 1, 2;

-- ---------------------------------------------------------------------
-- 4. Summary.
-- ---------------------------------------------------------------------
SELECT (SELECT count(*) FROM pg_tables
         WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history') AS tables,
       (SELECT count(*) FROM pg_constraint con
          JOIN pg_class c ON c.oid = con.conrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public' AND con.contype = 'f') AS foreign_keys,
       (SELECT count(*) FROM pg_constraint con
          JOIN pg_class c ON c.oid = con.conrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public' AND con.contype = 'c') AS check_constraints,
       (SELECT count(*) FROM pg_constraint con
          JOIN pg_class c ON c.oid = con.conrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public' AND con.contype = 'c'
           AND c.relname IN ('usage_events', 'rate_cards', 'rate_card_versions',
                             'rate_card_version_lines', 'finance_periods',
                             'finance_period_lock_events',
                             'usage_rollup_snapshots', 'finance_adjustments',
                             'finance_reconciliation_runs',
                             'finance_reconciliation_lines')) AS finance_checks;

-- 4a. FR-1.2. Must be 0.
SELECT count(*) AS nullable_organization_id
  FROM pg_attribute a
  JOIN pg_class c ON c.oid = a.attrelid
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public'
   AND c.relname IN ('usage_events', 'rate_cards', 'rate_card_versions',
                     'rate_card_version_lines', 'finance_periods',
                     'finance_period_lock_events', 'usage_rollup_snapshots',
                     'finance_adjustments', 'finance_reconciliation_runs',
                     'finance_reconciliation_lines')
   AND a.attname = 'organizationId' AND NOT a.attnotnull;

-- 4b. V6 contract. Must be 0.
SELECT count(*) AS timestamptz_columns
  FROM pg_attribute a
  JOIN pg_class c ON c.oid = a.attrelid
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public'
   AND c.relname IN ('usage_events', 'rate_cards', 'rate_card_versions',
                     'rate_card_version_lines', 'finance_periods',
                     'finance_period_lock_events', 'usage_rollup_snapshots',
                     'finance_adjustments', 'finance_reconciliation_runs',
                     'finance_reconciliation_lines')
   AND a.attnum > 0 AND NOT a.attisdropped
   AND a.atttypid = 'timestamptz'::regtype;

-- 4c. THE APPEND-ONLY BOUNDARY. Expected exactly:
--     rate_card_versions / rate_card_version_lines /
--     finance_period_lock_events / usage_rollup_snapshots /
--     finance_reconciliation_runs / finance_reconciliation_lines /
--     usage_events = append_only
--     rate_cards / finance_periods / finance_adjustments = mutable
SELECT c.relname || '=' || CASE WHEN EXISTS (
           SELECT 1 FROM pg_attribute a
            WHERE a.attrelid = c.oid AND a.attnum > 0
              AND NOT a.attisdropped AND a.attname = 'updatedAt')
         THEN 'mutable' ELSE 'append_only' END AS boundary
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public' AND c.relkind = 'r'
   AND c.relname IN ('usage_events', 'rate_cards', 'rate_card_versions',
                     'rate_card_version_lines', 'finance_periods',
                     'finance_period_lock_events', 'usage_rollup_snapshots',
                     'finance_adjustments', 'finance_reconciliation_runs',
                     'finance_reconciliation_lines')
 ORDER BY 1;

-- 4d. THE NULLABLE projectId LIST FOR THE FINANCE SLICE. Expected: rate_cards,
--     rate_card_versions, rate_card_version_lines, finance_periods,
--     finance_period_lock_events, finance_adjustments,
--     finance_reconciliation_runs. NOT in the list and that is the point:
--     usage_rollup_snapshots and finance_reconciliation_lines are NOT NULL.
SELECT c.relname AS table_name
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
  JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0
                      AND NOT a.attisdropped AND a.attname = 'projectId'
 WHERE n.nspname = 'public'
   AND c.relname IN ('usage_events', 'rate_cards', 'rate_card_versions',
                     'rate_card_version_lines', 'finance_periods',
                     'finance_period_lock_events', 'usage_rollup_snapshots',
                     'finance_adjustments', 'finance_reconciliation_runs',
                     'finance_reconciliation_lines')
   AND NOT a.attnotnull
 ORDER BY c.relname;

-- 4e. THE APPEND-ONLY TRIGGERS. All of them must exist; a missing trigger leaves
--     "append-only enforced by convention", which the draft explicitly rejected.
--     NOTE the guard FUNCTION, not just the trigger name: six tables use the
--     blanket firstelement_append_only() and rate_card_versions uses the narrower
--     firstelement_rate_card_version_guard(), because it has one legitimate
--     UPDATE (ACTIVE -> RETIRED). Reading the function name here is what makes
--     the difference visible instead of assumed.
--     usage_events is included because V20261005090000 attached the trigger to a
--     table created by V20261005080000 -- the ledger shipped append-only by shape
--     alone, and "no updatedAt" turned out not to stop `update ... set costAmount`.
--     Expected exactly eight rows:
--       finance_period_lock_events_append_only      firstelement_append_only
--       finance_reconciliation_lines_append_only    firstelement_append_only
--       finance_reconciliation_runs_append_only     firstelement_append_only
--       rate_card_version_lines_append_only          firstelement_append_only
--       usage_events_append_only                    firstelement_append_only
--       usage_rollup_snapshots_append_only           firstelement_append_only
--       rate_card_versions_append_only               firstelement_rate_card_version_guard
--       usage_rollup_snapshots_locked_period_guard   usage_rollup_snapshots_reject_locked_period
SELECT c.relname AS table_name,
       tg.tgname AS trigger_name,
       p.proname AS guard_function
  FROM pg_trigger tg
  JOIN pg_class c ON c.oid = tg.tgrelid
  JOIN pg_proc p ON p.oid = tg.tgfoid
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public' AND NOT tg.tgisinternal
   AND c.relname IN ('usage_events', 'rate_card_versions', 'rate_card_version_lines',
                     'finance_period_lock_events', 'usage_rollup_snapshots',
                     'finance_reconciliation_runs',
                     'finance_reconciliation_lines')
 ORDER BY 1;

-- 4f. THE LOCKED-PERIOD GUARD. It is a trigger, not a CHECK, because the locked
--     state lives in another row.
SELECT tg.tgname AS locked_period_guard,
       p.proname AS guard_function
  FROM pg_trigger tg
  JOIN pg_class c ON c.oid = tg.tgrelid
  JOIN pg_proc p ON p.oid = tg.tgfoid
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public' AND c.relname = 'usage_rollup_snapshots'
   AND tg.tgname = 'usage_rollup_snapshots_locked_period_guard'
   AND NOT tg.tgisinternal;

-- ---------------------------------------------------------------------
-- 5. Index justification. 5a is the tenant-less allow list: this slice adds
--     NONE, so the list must not gain an entry.
-- ---------------------------------------------------------------------
SELECT tablename, indexname
  FROM pg_indexes
 WHERE schemaname = 'public'
   AND tablename <> 'flyway_schema_history'
   AND indexdef NOT LIKE '%USING btree ("organizationId"%'
   AND indexdef NOT LIKE '%USING btree ("projectId"%'
   AND indexdef NOT LIKE '%USING hnsw%'
   AND indexdef NOT LIKE '%USING btree ("runId"%'
   AND indexdef NOT LIKE '%USING btree ("conversationId"%'
   AND indexdef NOT LIKE '%USING btree ("employeeId"%'
   AND indexdef NOT LIKE '%USING btree ("userId"%'
   AND indexdef NOT LIKE '%USING btree ("knowledgeBaseId"%'
   AND indexdef NOT LIKE '%USING btree ("agentVersionId"%'
   AND indexdef NOT LIKE '%USING btree ("customerId"%'
   AND indexdef NOT LIKE '%UNIQUE%'
 ORDER BY tablename, indexname;

SELECT tablename, indexname, indexdef
  FROM pg_indexes
 WHERE schemaname = 'public'
   AND tablename IN ('usage_events', 'rate_cards', 'rate_card_versions',
                     'rate_card_version_lines', 'finance_periods',
                     'finance_period_lock_events', 'usage_rollup_snapshots',
                     'finance_adjustments', 'finance_reconciliation_runs',
                     'finance_reconciliation_lines')
 ORDER BY tablename, indexname;

-- 5c/5d/5e. The three read models that actually run. EXPLAIN WITHOUT ANALYZE.
DO $$
DECLARE
    plan       text;
    i          int;
    statements text[] := ARRAY[
        'explain (costs off) select "dimension", sum("costAmount")
           from public.usage_events
          where "organizationId" = ''00000000-0000-0000-0000-000000000000''
            and "projectId" = ''00000000-0000-0000-0000-000000000000''
            and "dimension" = ''AI_TOKEN''::"public"."UsageDimension"
            and "periodStart" >= date ''2026-01-01''
            and "periodStart" <= date ''2026-01-31''
          group by "dimension"',
        'explain (costs off) select id from public.usage_rollup_snapshots
          where "organizationId" = ''00000000-0000-0000-0000-000000000000''
            and "projectId" = ''00000000-0000-0000-0000-000000000000''
            and "bucketStart" = date ''2026-01-01''
            and "dimension" = ''AI_TOKEN''::"public"."UsageDimension"',
        'explain (costs off) select "effectiveFrom", "markupRate"
           from public.rate_card_versions
          where "organizationId" = ''00000000-0000-0000-0000-000000000000''
            and "rateCardId" = ''00000000-0000-0000-0000-000000000000''
            and status = ''ACTIVE''::"public"."RateCardStatus"
            and "effectiveFrom" <= date ''2026-03-14'''
    ];
BEGIN
    IF to_regclass('public.usage_events') IS NULL THEN
        RAISE NOTICE 'V13 5c-5e: PHASE710A_NOT_PRESENT (no read-model plan to prove)';
        RETURN;
    END IF;
    FOR i IN 1 .. 3 LOOP
        RAISE NOTICE 'V13 plan %:', i;
        FOR plan IN EXECUTE statements[i] LOOP
            RAISE NOTICE '   %', plan;
        END LOOP;
    END LOOP;
END
$$;

-- ---------------------------------------------------------------------
-- 6. THE LOAD-BEARING ASSERTIONS, with data where rows exist.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    events          bigint;
    snapshots       bigint;
    crossed_org     bigint;
    crossed_project bigint;
    crossed_card    bigint;
    crossed_period  bigint;
BEGIN
    IF to_regclass('public.usage_events') IS NULL THEN
        RAISE NOTICE 'V13 section 6: PHASE710A_NOT_PRESENT';
        RETURN;
    END IF;

    SELECT count(*) INTO events    FROM public.usage_events;
    SELECT count(*) INTO snapshots FROM public.usage_rollup_snapshots;
    RAISE NOTICE 'V13 volume: usage_events=% usage_rollup_snapshots=%', events, snapshots;

    --   a) a usage event whose tenant does not exist: the FK makes it
    --      impossible, so a non-zero count means an FK was dropped.
    SELECT count(*) INTO crossed_org
      FROM public.usage_events u
     WHERE NOT EXISTS (SELECT 1 FROM public.organizations o WHERE o.id = u."organizationId");

    --   b) a usage event whose project belongs to a DIFFERENT tenant.
    SELECT count(*) INTO crossed_project
      FROM public.usage_events u
      JOIN public.projects p ON p.id = u."projectId"
     WHERE p."organizationId" <> u."organizationId";

    --   c) THE CROSS-TENANT REFERENCE THE COMPOSITE FKEYS CLOSE. A single-column
    --      FK on rateCardVersionId would permit all three of these, and nothing
    --      else in the schema would object:
    --        * a snapshot priced by another tenant's rate card version;
    --        * a period lock event naming another tenant's period;
    --        * a usage event billed to another tenant's period.
    SELECT count(*) INTO crossed_card
      FROM public.usage_rollup_snapshots s
      JOIN public.rate_card_versions v ON v.id = s."rateCardVersionId"
     WHERE v."organizationId" <> s."organizationId";

    SELECT count(*) INTO crossed_period
      FROM public.finance_period_lock_events e
      JOIN public.finance_periods p ON p.id = e."periodId"
     WHERE p."organizationId" <> e."organizationId";

    SELECT count(*) INTO crossed_period
      FROM public.usage_events u
      JOIN public.finance_periods p ON p.id = u."billingPeriodId"
     WHERE p."organizationId" <> u."organizationId";

    RAISE NOTICE 'V13 tenancy: unknown_organization=% cross_project=% cross_tenant_rate_card=% cross_tenant_period=%',
                 crossed_org, crossed_project, crossed_card, crossed_period;

    -- THE DERIVATION, asserted over real rows: revenue == cost x pinned markup.
    IF EXISTS (SELECT 1 FROM public.usage_rollup_snapshots
                WHERE "revenueAmount" IS NOT NULL
                  AND "revenueAmount" <> round("costAmount" * "markupRate", 6)) THEN
        RAISE EXCEPTION
            'V13: a rollup snapshot''s revenue does not equal cost x its PINNED markup. Either '
            'the derivation CHECK was dropped or somebody hand-entered a revenue.';
    END IF;

    -- THE PRICING BICONDITIONAL: priced means all three present, unpriced means all
    -- three absent. There is no state with a revenue and no rate behind it.
    IF EXISTS (SELECT 1 FROM public.usage_rollup_snapshots
                WHERE (("rateCardVersionId" IS NULL AND "markupRate" IS NULL
                        AND "revenueAmount" IS NULL)
                       OR ("rateCardVersionId" IS NOT NULL AND "markupRate" IS NOT NULL
                           AND "revenueAmount" IS NOT NULL)) IS NOT TRUE) THEN
        RAISE EXCEPTION 'V13: a rollup snapshot is half-priced';
    END IF;

    -- ZERO IS NOT "NO DATA": a bucket with no contributing events must not exist.
    IF EXISTS (SELECT 1 FROM public.usage_rollup_snapshots
                WHERE "sourceEventCount" < 1) THEN
        RAISE EXCEPTION
            'V13: a rollup snapshot exists with zero source events -- "no data" has been '
            'materialised as a row, which is how it becomes indistinguishable from zero.';
    END IF;

    -- A GENERATION-N snapshot must name what it supersedes, and generation 1 must
    -- not. Otherwise two rows both claim to be the current bucket.
    IF EXISTS (SELECT 1 FROM public.usage_rollup_snapshots
                WHERE ("generation" = 1) IS DISTINCT FROM
                      ("supersedesSnapshotId" IS NULL)) THEN
        RAISE EXCEPTION 'V13: a rollup snapshot''s generation and its supersede pointer disagree';
    END IF;

    -- usage_events: the money invariants, asserted with data.
    IF EXISTS (SELECT 1 FROM public.usage_events
                WHERE "costAmount" < 0
                   OR "quantity" < 0
                   OR ("fxRate" IS NOT NULL AND "fxRate" <= 0)
                   OR (("fxRate" IS NULL) IS DISTINCT FROM ("fxSource" IS NULL))
                   OR (("fxRate" IS NULL) IS DISTINCT FROM ("fxRateDate" IS NULL))
                   OR public.firstelement_currency_code("currency") IS NOT TRUE) THEN
        RAISE EXCEPTION
            'V13: a usage event violates a money invariant; a CHECK constraint has been dropped.';
    END IF;

    IF EXISTS (SELECT 1 FROM public.usage_events
                WHERE ("correctionOfId" IS NULL) IS DISTINCT FROM
                      ("correctionReason" IS NULL)) THEN
        RAISE EXCEPTION 'V13: a usage event correction does not say why';
    END IF;

    -- finance_periods: LOCKED says who and when; REOPENED says who, when and why.
    --
    -- ONE-WAY, matching finance_periods_locked_check after the fixture proved the
    -- biconditional made the ADR-0007 rule 4 reopen inexpressible. A REOPENED
    -- period RETAINS lockedAt, so "<> (status = LOCKED)" would fail on every
    -- reopened row. This assertion is therefore the direction that matters:
    -- LOCKED demands both audit columns.
    IF EXISTS (SELECT 1 FROM public.finance_periods
                WHERE "status" = 'LOCKED'::"public"."PeriodStatus"
                      AND NOT ("lockedAt" IS NOT NULL AND "lockedByUserId" IS NOT NULL)) THEN
        RAISE EXCEPTION
            'V13: a LOCKED period is missing its lock audit columns -- a lock nobody can prove '
            'happened';
    END IF;

    IF EXISTS (SELECT 1 FROM public.finance_periods
                WHERE "status" = 'REOPENED'::"public"."PeriodStatus"
                      AND NOT ("reopenedAt" IS NOT NULL AND "reopenedByUserId" IS NOT NULL
                               AND "reopenReason" IS NOT NULL)) THEN
        RAISE EXCEPTION 'V13: a reopened period does not say who, when or why';
    END IF;

    -- ...and a reopened period KEEPS its lock columns, which is the whole reason
    -- the check above is one-way. "Was this month ever closed?" has to be
    -- answerable from the row, not only from finance_period_lock_events.
    IF EXISTS (SELECT 1 FROM public.finance_periods
                WHERE "reopenedAt" IS NOT NULL AND "lockedAt" IS NULL) THEN
        RAISE EXCEPTION
            'V13: a reopened period has lost its lockedAt -- finance_periods_reopen_requires_lock_check '
            'requires it, and dropping it would make "was this ever closed?" unanswerable from the row';
    END IF;

    -- finance_adjustments: nobody approves their own correction, and a rejection
    -- says why.
    IF EXISTS (SELECT 1 FROM public.finance_adjustments
                WHERE "approvedByUserId" IS NOT NULL
                  AND "approvedByUserId" = "createdByUserId") THEN
        RAISE EXCEPTION 'V13: a finance adjustment was approved by its own author';
    END IF;

    IF EXISTS (SELECT 1 FROM public.finance_adjustments
                WHERE "status" = 'REJECTED'::"public"."AdjustmentStatus"
                  AND "rejectionReason" IS NULL) THEN
        RAISE EXCEPTION 'V13: a rejected finance adjustment does not say why';
    END IF;

    -- Reconciliation: both differences are derived and CHECKed. A reconciliation
    -- line whose difference is not the difference is worse than none.
    IF EXISTS (SELECT 1 FROM public.finance_reconciliation_runs
                WHERE "difference" <> "ledgerAmount" - "snapshotAmount") THEN
        RAISE EXCEPTION 'V13: a reconciliation run''s difference is not ledger minus snapshot';
    END IF;

    IF EXISTS (SELECT 1 FROM public.finance_reconciliation_lines
                WHERE "costDifference" <> "ledgerCost" - "snapshotCost"
                   OR "quantityDifference" <> "ledgerQuantity" - "snapshotQuantity") THEN
        RAISE EXCEPTION 'V13: a reconciliation line''s difference is not the difference';
    END IF;

    IF EXISTS (SELECT 1 FROM public.finance_reconciliation_runs
                WHERE "status" <> 'MATCHED'::"public"."ReconciliationStatus"
                  AND ("notes" IS NULL OR length(btrim("notes")) = 0)) THEN
        RAISE EXCEPTION 'V13: an unexplained mismatch or accepted difference';
    END IF;

    -- rate_card_versions: a retired version says who, when, why and what replaced
    -- it, and a markup is a positive multiplier.
    IF EXISTS (SELECT 1 FROM public.rate_card_versions
                WHERE "status" = 'RETIRED'::"public"."RateCardStatus"
                  AND ("retiredAt" IS NULL OR "retiredByUserId" IS NULL
                       OR "retireReason" IS NULL
                       OR "supersededByVersionId" IS NULL)) THEN
        RAISE EXCEPTION 'V13: a retired rate card version is unattributable';
    END IF;

    IF EXISTS (SELECT 1 FROM public.rate_card_versions
                WHERE "markupRate" <= 0 OR "markupRate" > 1000) THEN
        RAISE EXCEPTION 'V13: a rate card version has a markup outside 0..1000';
    END IF;

    IF EXISTS (SELECT 1 FROM public.rate_card_versions
                WHERE "effectiveTo" IS NOT NULL AND "effectiveTo" < "effectiveFrom") THEN
        RAISE EXCEPTION 'V13: a rate card version ends before it begins';
    END IF;
END
$$;

-- ---------------------------------------------------------------------
-- 7/8/9. WHAT DDL CANNOT ENFORCE, REPORTED WITH DATA INSTEAD, plus the
--    drill-through view.
--
--    All three live in ONE guarded DO block with dynamic SQL, and that is not a
--    style choice. A plain SELECT naming a dropped table or a dropped function is
--    a PARSE-TIME error, so after `undo_phase7_10a.sql` the bundle would fail
--    instead of reporting PHASE710A_NOT_PRESENT -- which is precisely what the
--    post-undo rehearsal on the throwaway caught. Every other verification query
--    in this directory either reads only the catalog or lives inside a guarded
--    block; these three are the ones that read data, and data queries need the
--    guard most.
--
--    7. TWO ACTIVE versions of one card whose effective ranges OVERLAP. This is a
--       cross-row interval property; enforcing it in DDL needs btree_gist and an
--       EXCLUDE constraint, which is a heavier dependency than the invariant
--       deserves. What IS enforced is one version per (card, effectiveFrom) --
--       see rate_card_versions_one_start_per_day -- and the overlap itself is
--       reported here so it is a visible failure rather than a silent one.
--       MUST BE 0.
DO $$
DECLARE
    rec  record;
    n    bigint;
    line text;
BEGIN
    IF to_regclass('public.rate_card_versions') IS NULL THEN
        RAISE NOTICE 'V13 sections 7-9: PHASE710A_NOT_PRESENT';
        RETURN;
    END IF;

    -- 7. Overlapping ACTIVE effective ranges for one rate card.
    FOR rec IN EXECUTE $q$
        select a."rateCardId"::text, a.id::text, a."effectiveFrom", a."effectiveTo",
               b.id::text, b."effectiveFrom", b."effectiveTo"
          from public.rate_card_versions a
          join public.rate_card_versions b
            on a."rateCardId" = b."rateCardId" and a.id < b.id
         where a."status" = 'ACTIVE'::"public"."RateCardStatus"
           and b."status" = 'ACTIVE'::"public"."RateCardStatus"
           and coalesce(a."effectiveTo", date '9999-12-31') >= b."effectiveFrom"
           and coalesce(b."effectiveTo", date '9999-12-31') >= a."effectiveFrom"
    $q$
    LOOP
        RAISE NOTICE 'V13 OVERLAPPING RATE CARD VERSIONS: card=% version_a=% (%..%) '
                     'version_b=% (%..%) -- a date with two prices is unanswerable',
                     rec."rateCardId", rec.id, rec."effectiveFrom", rec."effectiveTo",
                     rec.f2, rec.f4, rec.f5;
    END LOOP;

    -- 8. A rate card LINE's currency must be its VERSION's currency. Must be 0.
    FOR rec IN EXECUTE $q$
        select l.id::text, l."currency", v."currency"
          from public.rate_card_version_lines l
          join public.rate_card_versions v on v.id = l."rateCardVersionId"
         where l."currency" <> v."currency"
    $q$
    LOOP
        RAISE NOTICE 'V13 RATE LINE CURRENCY MISMATCH: line=% is % but its version is %',
                     rec.id, rec."currency", rec.f2;
    END LOOP;

    -- 9. THE DRILL-THROUGH: every priced snapshot total resolves to the immutable
    --    usage events and to the rate-card version that priced them (ADR-0007
    --    rule 1). One line per priced bucket, capped, because the point is the
    --    shape of the answer and not the volume of it.
    FOR rec IN EXECUTE $q$
        select s."organizationId"::text, s."projectId"::text, s."grain"::text,
               s."bucketStart", s."dimension"::text, s."sourceEventCount",
               s."costAmount", s."markupRate", s."revenueAmount",
               v."versionNumber",
               (select count(*) from public.usage_events u
                 where u."organizationId" = s."organizationId"
                   and u."projectId" = s."projectId"
                   and u."dimension" = s."dimension"
                   and u."periodStart" <= s."bucketEnd"
                   and u."periodEnd" >= s."bucketStart")
          from public.usage_rollup_snapshots s
          left join public.rate_card_versions v on v.id = s."rateCardVersionId"
         where s."rateCardVersionId" is not null
         order by s."organizationId", s."bucketStart"
         limit 50
    $q$
    LOOP
        RAISE NOTICE
            'V13 DRILL-THROUGH org=% project=% grain=% bucket=% dimension=% events=% cost=% '
            'markup=% revenue=% rate_version=% events_in_window=%',
            rec."organizationId", rec."projectId", rec."grain", rec."bucketStart",
            rec."dimension", rec."sourceEventCount", rec."costAmount", rec."markupRate",
            rec."revenueAmount", rec."versionNumber", rec.count;
    END LOOP;

    -- 9a. THE UNPRICED VIEW, which ADR-0007 requires to be first-class and not an
    --     error. A total of 0 with any rows here is the difference between "we
    --     could not price this" and "there was nothing", and the whole point is
    --     that a reader can tell them apart.
    EXECUTE $q$
        select count(*), coalesce(sum("unpricedQuantity"), 0),
               coalesce(sum("unpricedCostAmount"), 0)
          from public.usage_rollup_snapshots
         where "rateCardVersionId" is null
    $q$ INTO n, rec."sourceEventCount", rec."costAmount";

    RAISE NOTICE
        'V13 UNPRICED: buckets=% unpriced_quantity=% unpriced_cost=% (a first-class view, '
        'never folded into zero)', n, rec."sourceEventCount", rec."costAmount";
END
$$;

-- ---------------------------------------------------------------------
-- 10. Session timezone.
-- ---------------------------------------------------------------------
SHOW TimeZone;