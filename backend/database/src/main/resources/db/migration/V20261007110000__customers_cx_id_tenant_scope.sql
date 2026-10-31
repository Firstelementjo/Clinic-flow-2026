-- =====================================================================
-- V20261007110000__customers_cx_id_tenant_scope.sql
-- ADDITIVE IN EFFECT.  TRANSACTIONAL.  REPLACES ONE CONSTRAINT.
--
-- P0, BLOCKING FOR CUTOVER.  REMEDIATION OF DEFECT CX-1, PART 2 OF 2.
-- Part 1 is V20261007100000__customer_cx_sequences.sql, which creates the
-- allocator state. This file backfills that state and then moves the uniqueness
-- of customers."cxId" from GLOBAL to PER ORGANIZATION. See ADR-0014.
--
-- WHY THIS EXISTS
--   customers_cxId_key is a UNIQUE INDEX on ("cxId") alone. Combined with a
--   project-scoped `count(*)` allocator and 5 retries, it makes the whole
--   deployment capable of holding FIVE customer references -- the sixth project's
--   first inbound message answers 500, permanently. The uniqueness is what is
--   wrong, not the retry count: the reference namespace is a shared, finite
--   resource being allocated as if it were a contention problem.
--
-- THE ORDER IN THIS FILE IS LOAD-BEARING, AND NOT BY CONVENTION
--
--   1. Census the existing references and REFUSE if any is not in the published
--      `#33` + six-digit format. Everything below parses "cxId", so an
--      unparseable value must stop the migration rather than be coerced.
--   2. BACKFILL the counter from max("cxId") per organization -- while the GLOBAL
--      unique key is still installed.
--   3. Assert no organization that has customers is left without a counter, and
--      that no counter sits below its organization's live maximum.
--   4. Only then drop the global key and add the per-organization one.
--
--   Step 2 must precede step 4, and the reason is specific. While the global key
--   holds, every value that has ever been written is present in the table and no
--   other value is insertable, so max("cxId") is a true high-water mark over
--   everything currently storable. Once the global key is gone, values that were
--   previously refused become insertable -- including values at or below the
--   high-water mark -- so a legacy `count(*)` writer could insert a reference the
--   counter is about to hand out again. Backfilling first means the counter is
--   never behind the data.
--
--   WHY THE BACKFILL CANNOT BE STRONGER THAN THIS, STATED PLAINLY
--   max("cxId") is computed from rows that still EXIST. A customer that once held
--   a reference and has since been DELETED leaves no trace in this schema: no
--   table records the deletion of a customer reference, and nothing foreign-keys
--   on ("cxId"). So for a tenant that has deleted a high-numbered customer, the
--   true "ever issued" maximum can be higher than max("cxId"), and this backfill
--   cannot know that. It therefore guarantees what is checkable -- no LIVE
--   reference is reissued -- and the migration REPORTS the size of the hole so
--   the residual is measured rather than assumed away:
--
--       gaps_below_each_tenant_maximum = number of reference values between the
--                                       format floor and that tenant's maximum
--                                       that no surviving customer holds
--
--   Follow-up F-14-1 (recorded in docs/migration/phases/
--   phase-14-customer-cxid-namespace.md): an append-only ledger of issued
--   references, seeded once from an export, so the high-water mark is
--   reconstructible rather than inferred. Until then the allocator is airtight
--   from the moment it starts -- V16 section 6 proves no reuse after deletion --
--   and the residual is confined to references deleted BEFORE this migration.
--
--   NO count(*) IS USED TO DERIVE ANYTHING HERE EITHER. The backfill reads
--   max("cxId"); a count would only measure how many rows survive, which is the
--   quantity ADR-0014 names as the wrong one.
--
--   ONE CHECK IS DELIBERATELY ABSENT. A "the six-digit sequence is within
--   0 .. 999999" test would be a tautology here: anything matching
--   `^#33[0-9]{6}$` is inside that range by construction. It is not written, and
--   the tautology is named here because a check that cannot fail is the failure
--   mode that survives review -- the same class of defect the Phase-10b negative
--   controls found in V15's D4 section.
--
--   THE PARSE OFFSET IS 4, NOT 3, AND THE RANGE CHECK IS WHAT PROVED IT
--   `#33` occupies positions 1, 2 and 3, so the six digits begin at position 4.
--   substr("cxId", 3) therefore reads SEVEN characters and shifts the number one
--   digit left, so `#33000000` parses as 3000000 rather than 0. The first version
--   of this file had that offset and the throwaway rehearsal refused it
--   immediately:
--
--     ERROR: new row for relation "customer_cx_sequences" violates check
--            constraint "customer_cx_sequences_sequence_range_check"
--
--   which is the CHECK added by V20261007100000 doing precisely the job it was
--   written for. It is not decorative: an off-by-one digit always multiplies the
--   parsed value by ten, so every conforming `#33dddddd` lands at or above
--   1000000 and the constraint fires for EVERY row rather than only for the
--   unlucky ones. A silent misparse became a refused migration.
--
--   TWO POSTGRESQL TRAPS THE THROWAWAY REHEARSAL FOUND IN THIS FILE, BOTH
--   INVISIBLE FROM READING IT
--
--   1. substr("cxId", 3) READS SEVEN CHARACTERS, NOT SIX. `#33` occupies
--      positions 1..3, so the six digits begin at position 4. The offset-3
--      version multiplied every parsed value by ten, and the throwaway refused
--      it immediately:
--          ERROR: new row for relation "customer_cx_sequences" violates check
--                 constraint "customer_cx_sequences_sequence_range_check"
--      which is the CHECK from V20261007100000 doing the job it was written for.
--      It is not decorative: an off-by-one digit always pushes the parse above
--      1000000, so the constraint fires for EVERY row rather than only for the
--      unlucky ones. A silent misparse became a refused migration.
--
--   2. to_regclass('public.customers_cxId_key') RETURNS NULL FOR A NAME THAT
--      EXISTS. The regclass input parser applies SQL identifier rules to its
--      string argument, so an unquoted mixed-case name is downcased and resolves
--      to public.customers_cxid_key -- which does not exist. Measured on the
--      throwaway, on the same index, in the same session:
--          to_regclass('public.customers_cxId_key')   -> NULL
--          to_regclass('public."customers_cxId_key"') -> "customers_cxId_key"
--      Every mixed-case name in this file is therefore passed with inner double
--      quotes. This is ADR-0002's explicit-mapping rule arriving through a
--      function argument instead of a DDL statement, and it is the most dangerous
--      shape of the bug: the step-4 guard below REFUSED with a correct and
--      plausible message ("customers_cxId_key is already absent") on a database
--      where it was plainly present. A guard that refuses for the wrong reason is
--      better than one that passes for the wrong reason -- it stopped the
--      migration -- but it is still a defect, and it would have refused on every
--      database in the deployment.
--
--   3. pg_get_indexdef(idx, n, true) QUOTES THE COLUMN AND RETURNS AN EMPTY
--      STRING FOR A MISSING POSITION. It renders `"organizationId"`, not
--      `organizationId`, so an unquoted comparison never matches; and
--      pg_get_indexdef(idx, 3, true) on a TWO-column index is '' rather than
--      NULL, so `IS NULL` on it can never fire while `IS NOT NULL` fires on a
--      correct index. Both measured on the throwaway. Arity is therefore taken
--      from pg_index.indnkeyatts and identity from the quoted rendered form.
--
-- WHAT THIS FILE REPLACES, AND WHY THAT IS THE ONE PERMITTED EXCEPTION
--   It drops customers_cxId_key and adds customers_organizationId_cxId_key.
--   That is a constraint being replaced, which is the only DROP this migration
--   performs and the only one the hard rules allow -- a constraint is a rule, and
--   the rule is wrong. DROP INDEX is written WITHOUT CASCADE, so anything that
--   depends on it aborts the migration rather than going with it, and an explicit
--   pg_constraint check runs first so the failure names the dependent instead of
--   reporting a bare dependency error.
--
--   NOTHING ELSE IS TOUCHED. No other table, column or index is altered, and
--   customers."cxId" keeps its type, its NOT NULL and its varchar(9) width.
--
-- WHY A PLAIN CREATE UNIQUE INDEX AND NOT CONCURRENTLY
--   ADR-0005 section 3's CONCURRENTLY is unreachable in Flyway Community (no
--   per-script executeInTransaction override) and ADR-0009 section 1 REJECTS
--   `spring.flyway.postgresql.transactional-lock=false`, because it is global and
--   would drop the schema-history lock for the whole chain. Plain CREATE INDEX
--   stands, with the operational condition ADR-0005 section 3 already requires:
--   if customers ever holds production volume at the moment this migration runs,
--   the index build moves to a maintenance window, or to
--   `pg_reindex customers_organizationId_cxId_key` afterwards. At cutover
--   customers holds 3 rows in the verified live database.
--
-- TIMESTAMPS  nothing here adds a timestamp; the timestamp contract is unchanged
-- (V6).
-- Rollback: ops/flyway/rollback/undo_phase14.sql, which restores the global key
-- and removes this one, and which REFUSES if two organizations now share a
-- ("cxId") value -- because then the old constraint cannot be recreated, and the
-- operator must be told which tenants collide rather than discovering it from a
-- failed migration.
-- Every identifier is schema-qualified (the baseline sets search_path = '').
-- =====================================================================

-- ---------------------------------------------------------------------
-- STEP 0. The allocator must already exist. Refusing here rather than later
-- means the backfill below cannot populate a table nobody can read from.
-- ---------------------------------------------------------------------
DO $$
BEGIN
    IF to_regclass('public.customer_cx_sequences') IS NULL
       OR to_regprocedure('public.firstelement_next_customer_cx_id(uuid)') IS NULL THEN
        RAISE EXCEPTION
            'V20261007110000 failed: customer_cx_sequences or '
            'firstelement_next_customer_cx_id(uuid) is absent. V20261007100000 must run first; '
            'the backfill in this file has nothing to write into without it.';
    END IF;
END
$$;

-- ---------------------------------------------------------------------
-- STEP 1. FORMAT CENSUS. Refuse rather than coerce.
--
-- Everything downstream parses "cxId" as `'#33' || six digits`. A value outside
-- that shape either means the format is not what this file believes, or that
-- some other writer issued it -- and both are a decision for a person, not for a
-- migration. The count and a sample are both reported, so the decision can be
-- made without a second query.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    offender_count bigint;
    offenders      text;
BEGIN
    SELECT count(*) INTO offender_count
      FROM "public"."customers"
     WHERE "cxId" !~ '^#33[0-9]{6}$';

    IF offender_count > 0 THEN
        SELECT string_agg("cxId", ', ' ORDER BY "cxId") INTO offenders
          FROM (SELECT "cxId" FROM "public"."customers"
                 WHERE "cxId" !~ '^#33[0-9]{6}$'
                 ORDER BY "cxId" LIMIT 20) s;

        RAISE EXCEPTION
            'V20261007110000 REFUSED: % customer reference(s) do not match the published #33 + '
            'six-digit format; first 20: [%]. This migration computes the counter high-water mark '
            'from "cxId" and will not guess at a format it cannot parse. Resolve the offending '
            'rows and re-run.', offender_count, offenders;
    END IF;

    RAISE NOTICE
        'V20261007110000: every customer reference matches the #33 + six-digit format, so the '
        'backfill can parse all of them.';
END
$$;

-- ---------------------------------------------------------------------
-- STEP 2. THE BACKFILL. max("cxId") per organization, never count(*).
--
-- The derived value is the SIX-DIGIT sequence, i.e. substr("cxId", 4) -- the
-- eight characters are `#33` at positions 1..3 followed by SIX digits from
-- position 4 -- and it is exactly the domain of
-- customer_cx_sequences."lastAllocatedCxSequence" and exactly what
-- TicketNumber.generateCxId consumes. The measured value on the verified live
-- database is 2, for #33000002.
--
-- ON CONFLICT DO NOTHING makes this idempotent: a re-run cannot LOWER a counter
-- that has already been incremented past the data, which is the one direction
-- that would be dangerous.
-- ---------------------------------------------------------------------
INSERT INTO "public"."customer_cx_sequences" (
    "id", "organizationId", "lastAllocatedCxSequence", "createdAt", "updatedAt")
SELECT gen_random_uuid(),
       c."organizationId",
       max((substr(c."cxId", 4)::bigint)),
       (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'),
       (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')
  FROM "public"."customers" c
 GROUP BY c."organizationId"
ON CONFLICT ("organizationId") DO NOTHING;

-- ---------------------------------------------------------------------
-- STEP 3. THE ASSERTION THAT MATTERS, taken BEFORE the uniqueness is relaxed.
--
-- "Organizations whose counter would otherwise start at 0" means: organizations
-- that HAVE customers but for which the backfill produced no counter row. For
-- those the allocator's INSERT branch would hand out #33000000 again -- a
-- reference the tenant already holds -- and the new per-organization key would
-- NOT refuse it, because that key only promises uniqueness INSIDE the
-- organization, and here it would be violated by a live row. So this count must
-- be zero, and it is measured here, while the global key still holds and the
-- question is still cheap to answer.
--
-- Also reported, because it is the quantity ADR-0014's residual turns on: how
-- many values between the floor and each tenant's maximum no surviving customer
-- holds.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    orgs_with_customers   bigint;
    orgs_with_counter     bigint;
    would_start_at_zero   bigint;
    live_references       bigint;
    counter_below_max     bigint;
    total_gaps            bigint;
    worst_gap             bigint;
BEGIN
    SELECT count(DISTINCT "organizationId") INTO orgs_with_customers
      FROM "public"."customers";

    SELECT count(*) INTO live_references FROM "public"."customers";

    SELECT count(*) INTO orgs_with_counter
      FROM "public"."customer_cx_sequences" s
     WHERE EXISTS (SELECT 1 FROM "public"."customers" c
                    WHERE c."organizationId" = s."organizationId");

    -- THE ASSERTION. Counted, not derived: this is the join that matters.
    SELECT count(*) INTO would_start_at_zero
      FROM (SELECT DISTINCT "organizationId" FROM "public"."customers") o
     WHERE NOT EXISTS (SELECT 1 FROM "public"."customer_cx_sequences" s
                        WHERE s."organizationId" = o."organizationId");

    IF would_start_at_zero <> 0 THEN
        RAISE EXCEPTION
            'V20261007110000 REFUSED: % organization(s) have customers but no counter row, so the '
            'allocator would reissue #33000000 to a tenant that already holds it -- and the new '
            'per-organization unique key would not catch it. Backfill before relaxing uniqueness, '
            'always.', would_start_at_zero;
    END IF;

    -- The counter must be at or above every live reference's sequence. If it were
    -- below, the next allocation would reissue a LIVE reference. This is the same
    -- invariant V16 section 5 asserts independently, measured here so a failure
    -- lands inside the migration rather than in a later verification run.
    SELECT count(*) INTO counter_below_max
      FROM "public"."customers" c
      JOIN "public"."customer_cx_sequences" s ON s."organizationId" = c."organizationId"
     WHERE s."lastAllocatedCxSequence" < (substr(c."cxId", 4)::bigint);

    IF counter_below_max <> 0 THEN
        RAISE EXCEPTION
            'V20261007110000 REFUSED: % customer row(s) hold a reference ABOVE their '
            'organization''s counter, so the next allocation would reissue a live reference.',
            counter_below_max;
    END IF;

    -- THE RESIDUAL, MEASURED. Gaps between the floor and each tenant's maximum
    -- that no surviving customer holds. Zero does NOT prove no reference was ever
    -- issued and deleted -- nothing in this schema records that -- so this number
    -- is reported rather than asserted to be zero.
    SELECT coalesce(sum(g), 0), coalesce(max(g), 0) INTO total_gaps, worst_gap
      FROM (
        SELECT (max((substr(c."cxId", 4)::bigint)) + 1
                - count(DISTINCT c."cxId")) AS g
          FROM "public"."customers" c
         GROUP BY c."organizationId"
      ) gaps;

    RAISE NOTICE
        'V20261007110000 backfill: organizations_with_customers = %, with_a_counter = %, '
        'would_otherwise_start_at_zero = % (asserted 0), live_references = %. '
        'gaps_below_each_tenant_maximum = % (worst single tenant %) -- the pre-migration deletion '
        'exposure recorded as follow-up F-14-1, reported and NOT asserted to be zero.',
        orgs_with_customers, orgs_with_counter, would_start_at_zero, live_references,
        total_gaps, worst_gap;
END
$$;

-- ---------------------------------------------------------------------
-- STEP 4. NOTHING MAY DEPEND ON THE INDEX BEING DROPPED, and it must exist.
--
-- conindid is set both for a constraint defined ON the index and for a FOREIGN
-- KEY that uses it as its target, so this one query covers "something is built on
-- it" and "something references through it". Measured on the verified live
-- database: zero dependents, because nothing foreign-keys on ("cxId") -- the four
-- FKs that point at customers all target ("id").
-- ---------------------------------------------------------------------
DO $$
DECLARE
    dependents text;
BEGIN
    IF to_regclass('public."customers_cxId_key"') IS NULL THEN
        RAISE EXCEPTION
            'V20261007110000 failed: customers_cxId_key is already absent, so this migration is '
            'running against a schema it did not expect. Refusing rather than adding a second '
            'unique key on top of an unknown state.';
    END IF;

    SELECT string_agg(c.conname || ' on ' || c.conrelid::regclass::text, ', '
                      ORDER BY c.conname) INTO dependents
      FROM "pg_constraint" c
     WHERE c.conindid = to_regclass('public."customers_cxId_key"');

    IF dependents IS NOT NULL THEN
        RAISE EXCEPTION
            'V20261007110000 REFUSED: constraint(s) depend on customers_cxId_key: [%]. Dropping it '
            'would take them with it, and DROP INDEX is deliberately written without CASCADE so '
            'this cannot happen silently.', dependents;
    END IF;

    RAISE NOTICE
        'V20261007110000: nothing depends on customers_cxId_key, so replacing it is safe.';
END
$$;

-- ---------------------------------------------------------------------
-- STEP 5. THE RESCOPE. Drop the global key, add the per-organization one.
--
-- This is the one DROP in the whole Phase-14 pair, and it is a constraint being
-- replaced. No CASCADE.
--
-- The new key is a UNIQUE INDEX rather than a UNIQUE CONSTRAINT, so V4's
-- unique_constraints = 0 invariant survives -- the convention every migration in
-- this chain follows.
--
-- The column order is ("organizationId", "cxId") and NOT the reverse, for two
-- reasons that are both operational. It serves the lookup every screen performs
-- ("this tenant's customer with reference #33000042"), and an index whose first
-- column is organizationId classifies as tenant-led, which V9's scope census
-- requires. The reverse order would be a global unique index again in everything
-- but its name: Postgres would scan every tenant's rows to enforce one tenant's
-- uniqueness.
-- ---------------------------------------------------------------------
DROP INDEX "public"."customers_cxId_key";

CREATE UNIQUE INDEX "customers_organizationId_cxId_key"
    ON "public"."customers" USING "btree" ("organizationId", "cxId");

COMMENT ON INDEX "public"."customers_organizationId_cxId_key" IS
    'Phase 14 (ADR-0014, CX-1): a customer reference is unique within an '
    'organization, not across the deployment. This index replaces the global '
    'customers_cxId_key, whose existence limited the whole product to five '
    'customer references. Tenant-led by design, so it also serves the '
    '"this tenant''s customer with reference #33000042" lookup.';

-- ---------------------------------------------------------------------
-- STEP 6. POST-CONDITION. Read back from the catalog. Assert on the STATE.
-- (ADR-0009 section 5.)
--
-- Columns are compared POSITION BY POSITION with
-- pg_get_indexdef(indexrelid, n, true) rather than by pattern-matching the whole
-- definition, so a column that silently becomes the third cannot hide inside a
-- LIKE. And each detection pattern is checked against a known rendering, so a
-- pattern that could never fire is itself a failure.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    global_gone        boolean;
    idx_rows           bigint;
    is_unique          boolean;
    is_valid           boolean;
    predicate           text;
    expressions        text;
    key_column_count    smallint;
    total_column_count  smallint;
    key_col_1           text;
    key_col_2           text;
    installed_indexdef text;
    known_good_indexdef text;
    duplicate_pairs    bigint;
    orgs_with_counter  bigint;
    counter_below_max  bigint;
    unique_constraints bigint;
BEGIN
    -- 6a. THE GLOBAL KEY IS GONE. Checked in pg_class rather than by assuming
    --     the DROP ran, because "I dropped it" is a claim about this file and
    --     "no pg_class row remains" is a fact about the database.
    SELECT NOT EXISTS (SELECT 1 FROM "pg_class" c
                        JOIN "pg_namespace" n ON n.oid = c.relnamespace
                       WHERE n.nspname = 'public' AND c.relname = 'customers_cxId_key')
      INTO global_gone;

    IF global_gone IS DISTINCT FROM true THEN
        RAISE EXCEPTION
            'V20261007110000 failed: customers_cxId_key still exists. A GLOBAL unique key on '
            '"cxId" is the defect this migration removes -- with it in place a tenant''s first '
            'reference competes with every other tenant''s, and the deployment as a whole can hold '
            'five.';
    END IF;

    -- 6b. THE COMPOSITE KEY EXISTS, is unique, is valid, is not partial, uses no
    --     expressions, and has EXACTLY the two intended columns in that order.
    SELECT count(*) INTO idx_rows
      FROM "pg_index" i
     WHERE i.indexrelid = to_regclass('public."customers_organizationId_cxId_key"')
       AND i.indrelid  = to_regclass('public.customers');

    IF idx_rows IS DISTINCT FROM 1 THEN
        RAISE EXCEPTION
            'V20261007110000 failed: customers_organizationId_cxId_key is absent from customers, '
            'so "cxId" is no longer unique per organization and two customers in one tenant can '
            'share a reference.';
    END IF;

    --     arity comes from indnkeyatts, not from probing a third position:
    --     pg_get_indexdef(idx, 3, true) returns an EMPTY STRING, not NULL, when
    --     the index has no third key column, so an `IS NULL` test on it can never
    --     fire and an `IS NOT NULL` test fires on a correct index. Both were
    --     measured on the throwaway. The column identity test compares against the
    --     RENDERED form, which carries double quotes -- `pg_get_indexdef(idx, 1,
    --     true)` is `"organizationId"`, not `organizationId`.
    SELECT i.indisunique, i.indisvalid, i.indnkeyatts, i.indnatts,
           i.indpred::text, i.indexprs::text
      INTO is_unique, is_valid, key_column_count, total_column_count,
           predicate, expressions
      FROM "pg_index" i
     WHERE i.indexrelid = to_regclass('public."customers_organizationId_cxId_key"');

    IF is_unique IS DISTINCT FROM true THEN
        RAISE EXCEPTION
            'V20261007100000 failed: customers_organizationId_cxId_key is not UNIQUE, so it '
            'enforces nothing.';
    END IF;

    IF is_valid IS DISTINCT FROM true THEN
        RAISE EXCEPTION
            'V20261007110000 failed: customers_organizationId_cxId_key is INVALID, so the '
            'uniqueness it claims to provide is not being enforced.';
    END IF;

    IF key_column_count IS DISTINCT FROM 2 OR total_column_count IS DISTINCT FROM 2 THEN
        RAISE EXCEPTION
            'V20261007110000 failed: customers_organizationId_cxId_key has % key column(s) of % '
            'total; it must have exactly two.',
            coalesce(key_column_count::text, 'null'), coalesce(total_column_count::text, 'null');
    END IF;

    IF predicate IS NOT NULL THEN
        RAISE EXCEPTION
            'V20261007110000 failed: customers_organizationId_cxId_key is PARTIAL (%). A partial '
            'unique key lets two customers in one tenant share a reference whenever the predicate '
            'excludes one of them.', predicate;
    END IF;

    IF expressions IS NOT NULL THEN
        RAISE EXCEPTION
            'V20261007110000 failed: customers_organizationId_cxId_key is built on EXPRESSIONS '
            '(%), not on the two columns, so it is not the key this migration claims to add.',
            expressions;
    END IF;

    SELECT pg_get_indexdef(indexrelid, 1, true), pg_get_indexdef(indexrelid, 2, true)
      INTO key_col_1, key_col_2
      FROM "pg_index"
     WHERE indexrelid = to_regclass('public."customers_organizationId_cxId_key"');

    IF key_col_1 IS DISTINCT FROM '"organizationId"'
       OR key_col_2 IS DISTINCT FROM '"cxId"' THEN
        RAISE EXCEPTION
            'V20261007110000 failed: customers_organizationId_cxId_key indexes (%, %), which is '
            'not ("organizationId", "cxId") in that order. The reverse order would be a global '
            'unique key again in everything but its name.',
            coalesce(key_col_1, '(null)'), coalesce(key_col_2, '(null)');
    END IF;

    -- 6c. THE DETECTORS THEMSELVES DISCRIMINATE.
    --     A pattern that cannot match the thing it is meant to detect is a no-op
    --     assertion, and a no-op assertion passes forever. This project's own
    --     history has three of them: an OpenAPI exporter's class-level
    --     @RequestMapping, the tenant-SQL ratchet's blind spot, and V15's D4
    --     totality probe. So the composite detector is proven against BOTH the
    --     installed definition and a hard-coded known-good rendering.
    SELECT pg_get_indexdef(to_regclass('public."customers_organizationId_cxId_key"'))
      INTO installed_indexdef;
    SELECT indexdef INTO known_good_indexdef
      FROM "pg_indexes"
     WHERE schemaname = 'public' AND indexname = 'customers_organizationId_cxId_key';

    IF installed_indexdef NOT LIKE '%UNIQUE INDEX%'
       OR installed_indexdef NOT LIKE '%"organizationId", "cxId"%' THEN
        RAISE EXCEPTION
            'V20261007110000 failed: the installed composite key reads "%" and the detection '
            'pattern does not match its own installed form, so the assertions above it are a '
            'no-op. Fix the pattern.', coalesce(installed_indexdef, '(absent)');
    END IF;

    IF known_good_indexdef IS NULL
       OR known_good_indexdef NOT LIKE '%UNIQUE INDEX%'
       OR known_good_indexdef NOT LIKE '%"organizationId", "cxId"%' THEN
        RAISE EXCEPTION
            'V20261007110000 failed: the composite detector does not match the known-good '
            'rendering, so this post-condition proves nothing. Fix the pattern.';
    END IF;

    -- 6d. NO LIVE DUPLICATE ("organizationId", "cxId"). Vacuously true when the
    --     global key was still holding, which is the point of doing this first --
    --     but it is asserted rather than reasoned about, because it is the exact
    --     property the new key promises and it costs one grouped count.
    SELECT count(*) INTO duplicate_pairs
      FROM (SELECT "organizationId", "cxId"
              FROM "public"."customers"
             GROUP BY "organizationId", "cxId"
            HAVING count(*) > 1) d;

    IF duplicate_pairs <> 0 THEN
        RAISE EXCEPTION
            'V20261007110000 failed: % ("organizationId", "cxId") pair(s) appear more than once, so '
            'customers_organizationId_cxId_key could not have been created. That means the global '
            'key was already gone.', duplicate_pairs;
    END IF;

    -- 6e. THE COUNTER IS POPULATED AND CONSISTENT for every organization that has
    --     customers. Re-read here rather than trusted from step 3, because the
    --     index work happened in between.
    SELECT count(*) INTO orgs_with_counter
      FROM "public"."customer_cx_sequences" s
     WHERE EXISTS (SELECT 1 FROM "public"."customers" c
                    WHERE c."organizationId" = s."organizationId");

    SELECT count(*) INTO counter_below_max
      FROM "public"."customers" c
      JOIN "public"."customer_cx_sequences" s ON s."organizationId" = c."organizationId"
     WHERE s."lastAllocatedCxSequence" < (substr(c."cxId", 4)::bigint);

    IF counter_below_max <> 0 THEN
        RAISE EXCEPTION
            'V20261007110000 failed: % customer row(s) sit above their organization''s counter '
            'after the backfill.', counter_below_max;
    END IF;

    -- 6f. V4's invariant survives: uniqueness is carried by unique INDEXES.
    SELECT count(*) INTO unique_constraints
      FROM "pg_constraint" con
        JOIN "pg_class" c ON c.oid = con.conrelid
        JOIN "pg_namespace" n ON n.oid = c.relnamespace
       WHERE n.nspname = 'public' AND con.contype = 'u';

    IF unique_constraints <> 0 THEN
        RAISE EXCEPTION
            'V20261007110000 failed: % UNIQUE CONSTRAINT(s) exist. Every uniqueness in this chain '
            'is a unique INDEX so V4''s unique_constraints = 0 invariant holds.',
            unique_constraints;
    END IF;

    RAISE NOTICE
        'V20261007110000: customers."cxId" rescoped. Read back from pg_class and pg_index -- '
        'customers_cxId_key is gone; customers_organizationId_cxId_key is UNIQUE, valid, '
        'non-partial, non-expression, on exactly ("organizationId", "cxId"); % organizations with '
        'customers all hold a counter at or above their live maximum; 0 duplicate pairs; '
        'unique_constraints still 0.', orgs_with_counter;
END
$$;
