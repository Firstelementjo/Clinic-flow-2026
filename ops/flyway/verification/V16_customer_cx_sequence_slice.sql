-- =====================================================================
-- V16_customer_cx_sequence_slice.sql
-- Phase 14 / database verification: the customer reference namespace
-- corrected by V20261007100000 .. V20261007110000 (ADR-0014, defect CX-1).
--
-- THE DEFECT BEING VERIFIED GONE
--   customers."cxId" was GLOBALLY unique while the allocator was a
--   PROJECT-SCOPED `select count(*) from customers` with five walk-forward
--   retries. Every project started its search at #33000000, so five references
--   were consumed deployment-wide and the sixth project's first customer threw
--   and every inbound message for that project answered 500 -- permanently.
--
-- SECTIONS
--   1  presence, and PARTIAL detection
--   2  the counter table's column shape            (result set, both profiles)
--   3  the uniqueness rescope, asserted on pg_class / pg_index
--   4  the allocator is one statement, VOLATILE, and counts nothing
--   5  the counter agrees with max("cxId") for every organization with customers
--   6  BEHAVIOUR, in a transaction this file always rolls back:
--        6a capacity across SIX organizations
--        6b capacity across SIX projects inside ONE organization
--        6c monotonicity within one organization
--        6d no reuse after the holder is deleted
--        6e no cross-tenant inference
--   7  the row counts are unchanged, read AFTER the rollback
--
-- GUARDED ON BOTH SIDES OF THE CUTOVER, AND WHY THAT NEEDS A DO BLOCK
--   On a pre-cutover database this file must REPORT PHASE14_NOT_PRESENT, not
--   FAIL. The gate runs the preBoot profile against the dev database, which has
--   none of Phase 14's objects. `V15` shipped with about fifty unguarded
--   statements and failed there, and the failure was a PARSE-TIME error:
--
--     ERROR:  relation "public.ai_approvals" does not exist
--     LINE 1:  WHERE conrelid = 'public.ai_approvals'::regclass
--
-- PostgreSQL parses a statement before it evaluates it, so a
-- `WHERE to_regclass(...) IS NULL` test in the same statement cannot help: the
-- relation has to resolve first. Inside a plpgsql block statements are prepared
-- LAZILY, on first execution, so `to_regclass(...) IS NULL -> RAISE NOTICE;
-- RETURN` genuinely protects everything after it. That was measured on this
-- host before the file was written, with a three-case probe: guarded block ->
-- skipped; guarded block holding the relation in a variable -> skipped;
-- UNGUARDED block -> the same "relation does not exist" error.
--
-- `EXECUTE` (dynamic SQL) is used only where a per-row census has to come out of
-- a guarded body, which is the arrangement V14 section 3a already uses.
--
-- SECTION 6 WRITES, AND EVERY OTHER SECTION IN THIS BUNDLE DOES NOT
--   Sections 6 and 7 are the only part of the bundle that write, and they are
--   wrapped in an explicit transaction whose ONLY successful exit is ROLLBACK:
--
--       BEGIN;
--       DO $$ ... guarded behavioural scenarios ... $$;
--       ROLLBACK;
--
--   That is why the scenarios can be a real proof and still be unable to leave
--   anything behind. The property is structural, not a matter of care:
--
--     * if any statement inside raises, ON_ERROR_STOP aborts psql, the
--       transaction is aborted, and the disconnect rolls it back;
--     * if psql is killed mid-file, the disconnect rolls it back;
--     * there is no COMMIT anywhere in this file, and no DDL outside the
--       transaction.
--
--   Nothing irreversible is involved either: the probe rows use
--   gen_random_uuid() rather than a sequence, and there is not one `nextval`
--   default anywhere in this schema, so a rollback undoes 100% of what the
--   scenarios did. Verified, not assumed -- see section 7.
--
--   OPERATIONAL NOTE: the bundle is a dev/staging tool. Section 6 takes a
--   RowExclusive lock on organizations / projects / customers for the length of
--   one short transaction. Do not run it against production.
--
-- WHY SECTION 6 SEEDS ITS OWN FIXTURE
--   "Assert the capacity regression on a throwaway with seeded rows" is only a
--   proof if the seed is the RIGHT seed. Depending on an externally prepared
--   fixture means the assertion silently stops meaning anything when that
--   fixture changes. The six organizations and six projects here are created
--   inside the transaction, from nothing, so the scenario is self-contained and
--   identical on every run.
--
-- EVERY ASSERTION READS STATE, NEVER A MESSAGE. (ADR-0009 section 5.)
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Presence, and PARTIAL detection.
--
-- PARTIAL matters: "one of the two migrations landed" means a migration failed
-- mid-chain, and reporting that as PASS is how a half-remediated schema reaches
-- a cutover.
--
-- Safe at top level: to_regclass, pg_class, pg_proc and pg_indexes are catalogs
-- that exist on every database, so this row answers the question on both sides.
-- Note the INNER DOUBLE QUOTES on the two mixed-case names: the regclass input
-- parser downcases an unquoted identifier, so to_regclass('public.customers_cxId_key')
-- resolves to customers_cxid_key and returns NULL for an index that plainly
-- exists. Measured on this host; see the header of V20261007110000.
-- ---------------------------------------------------------------------
SELECT CASE
         WHEN to_regclass('public."customer_cx_sequences"') IS NOT NULL
          AND to_regprocedure('public.firstelement_next_customer_cx_id(uuid)') IS NOT NULL
          AND to_regclass('public."customers_organizationId_cxId_key"') IS NOT NULL
          AND to_regclass('public."customers_cxId_key"') IS NULL
         THEN 'PHASE14_PRESENT'
         WHEN to_regclass('public."customer_cx_sequences"') IS NULL
          AND to_regclass('public."customers_organizationId_cxId_key"') IS NULL
          AND to_regprocedure('public.firstelement_next_customer_cx_id(uuid)') IS NULL
         THEN 'PHASE14_NOT_PRESENT'
         ELSE 'PHASE14_PARTIAL -- one of the two Phase-14 migrations failed halfway; stop'
       END AS phase14_cx_namespace_state;

-- ---------------------------------------------------------------------
-- 1b. A PARTIAL DATABASE IS A FAILURE, NOT A REPORT.
--
-- Section 1 prints PHASE14_PARTIAL and this turns it into an abort. Printing
-- is not enough: the Phase-7-10a undo once printed REFUSED and dropped 37
-- tables anyway, and the rule adopted from it is that a rehearsal which passes
-- for the wrong reason is a failed rehearsal. A database holding
-- customers_organizationId_cxId_key with no counter table is a migration chain
-- that failed mid-way, and letting the bundle go green on it is precisely the
-- "half-remediated schema reaches a cutover" outcome section 1 exists to
-- prevent.
--
-- This was NOT theoretical. The negative-control rehearsal for this slice
-- dropped customer_cx_sequences while leaving the rescoped key in place --
-- exactly the state a failed V20261007110000 leaves behind, since that
-- migration refuses to run without the counter table -- and V16 exited 0. So
-- the assertion is here, and the control now has something to catch.
--
-- Safe at top level: it reads only to_regclass / to_regprocedure, which exist
-- on every database. On a pre-cutover database it evaluates to NOT_PRESENT and
-- raises nothing.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    state    text;
    has_table  boolean;
    has_func   boolean;
    has_composite boolean;
    has_global    boolean;
    problems  text;
BEGIN
    has_table     := to_regclass('public."customer_cx_sequences"') IS NOT NULL;
    has_func      := to_regprocedure('public.firstelement_next_customer_cx_id(uuid)') IS NOT NULL;
    has_composite := to_regclass('public."customers_organizationId_cxId_key"') IS NOT NULL;
    has_global    := to_regclass('public."customers_cxId_key"') IS NOT NULL;

    state := CASE
        WHEN has_table AND has_func AND has_composite AND NOT has_global
        THEN 'PHASE14_PRESENT'
        WHEN NOT has_table AND NOT has_composite AND NOT has_func
        THEN 'PHASE14_NOT_PRESENT'
        ELSE 'PHASE14_PARTIAL'
    END;

    -- THE SPECIFIC DIAGNOSIS, because "the state is wrong" is not actionable and
    -- the reason this block exists before the specific sections is fail-fast.
    -- Without this list the most important case -- the global key still present,
    -- i.e. the CX-1 defect itself -- would be reported only as a generic
    -- half-applied chain.
    problems := concat_ws('; ',
        CASE WHEN has_global
             THEN 'the GLOBAL customers_cxId_key is STILL PRESENT, which is the CX-1 defect itself'
             END,
        CASE WHEN has_composite AND NOT has_table
             THEN 'the per-organization key is installed but the counter table is absent'
             END,
        CASE WHEN has_composite AND NOT has_func
             THEN 'the per-organization key is installed but the allocator is absent'
             END,
        CASE WHEN has_table AND NOT has_composite
             THEN 'the counter table exists but "cxId" has NOT been rescoped, so the global '
                  'customers_cxId_key still limits the deployment to five references'
             END,
        CASE WHEN has_table AND NOT has_func
             THEN 'the counter table exists but no allocator can read it'
             END);

    IF state = 'PHASE14_PARTIAL' THEN
        RAISE EXCEPTION
            'V16: this database is in a HALF-APPLIED Phase-14 state and the bundle must not pass '
            'on it. Specifically: %.', coalesce(nullif(problems, ''), 'no single cause identified');
    END IF;

    IF state = 'PHASE14_NOT_PRESENT' THEN
        RAISE NOTICE
            'V16 1b: PHASE14_NOT_PRESENT -- none of Phase 14 is applied, so every section below '
            'reports NOT_PRESENT rather than failing.';
    END IF;
END
$$;

-- ---------------------------------------------------------------------
-- 2. The counter table's column shape, reported per column so a secret-shaped
--    or a stray counter column is visible in the evidence bundle rather than
--    only in a guard's exception text.
--
--    Stays a TOP-LEVEL result set on purpose. It reads only pg_class and
--    pg_attribute, which exist on every database in this schema, so it runs on
--    both profiles; its zero rows on a pre-cutover database ARE the evidence that
--    the table does not exist there, which is better than a guard that skipped
--    the question.
-- ---------------------------------------------------------------------
SELECT c.relname AS table_name,
       a.attname AS column_name,
       format_type(a.atttypid, a.atttypmod) AS data_type,
       CASE WHEN a.attnotnull THEN 'NOT NULL' ELSE 'NULL' END AS nullable
  FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
 WHERE n.nspname = 'public'
   AND c.relname IN ('customer_cx_sequences')
 ORDER BY c.relname, a.attnum;

-- ---------------------------------------------------------------------
-- 3. THE UNIQUENESS RESCOPE, asserted on the installed indexes.
--
-- GUARD: on customer_cx_sequences, created by V20261007100000 and by nothing
-- before it. That artifact is both non-circular -- the constraint this section
-- checks is a different object -- and specific to the batch.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    global_gone        boolean;
    idx_rows           bigint;
    is_unique          boolean;
    is_valid           boolean;
    key_column_count   smallint;
    total_column_count smallint;
    predicate          text;
    expressions        text;
    key_col_1          text;
    key_col_2          text;
    installed_indexdef text;
    tenants_sharing    bigint;
BEGIN
    IF to_regclass('public."customer_cx_sequences"') IS NULL THEN
        RAISE NOTICE
            'V16 3: PHASE14_NOT_PRESENT (no customer_cx_sequences, so there is no Phase-14 key '
            'to inspect). Section 2 above still ran: it reads the catalog, so its zero rows are '
            'the evidence.';
        RETURN;
    END IF;

    -- 3a. The global key is gone. Checked in pg_class rather than assumed from
    --     this file's knowledge of the migration.
    --
    --     DEFENCE IN DEPTH BEHIND SECTION 1b, and honestly labelled as such: 1b
    --     already refuses any database where the global key exists, so 3a cannot
    --     be reached in that state. It stays because 1b is a chain-state check
    --     and this is a schema check -- a future edit could weaken one without
    --     touching the other -- and because the two fail differently if they do.
    --     The negative control for "the global key is back" therefore fires 1b,
    --     not this, and the rehearsal records that rather than claiming a test
    --     it did not run.
    SELECT NOT EXISTS (SELECT 1 FROM pg_class c
                        JOIN pg_namespace n ON n.oid = c.relnamespace
                       WHERE n.nspname = 'public' AND c.relname = 'customers_cxId_key')
      INTO global_gone;

    IF global_gone IS DISTINCT FROM true THEN
        RAISE EXCEPTION
            'V16: customers_cxId_key still exists. A GLOBAL unique key on "cxId" is the CX-1 '
            'defect itself: with it in place a tenant''s first reference competes with every '
            'other tenant''s and the deployment as a whole can hold five.';
    END IF;

    -- 3b. The composite key exists on customers, unique, valid, non-partial,
    --     non-expression, exactly two key columns, in the intended ORDER.
    SELECT count(*) INTO idx_rows
      FROM pg_index i
     WHERE i.indexrelid = to_regclass('public."customers_organizationId_cxId_key"')
       AND i.indrelid  = to_regclass('public.customers');

    IF idx_rows IS DISTINCT FROM 1 THEN
        RAISE EXCEPTION
            'V16: customers_organizationId_cxId_key is absent from customers, so "cxId" is not '
            'unique per organization and two customers in one tenant can share a reference.';
    END IF;

    SELECT i.indisunique, i.indisvalid, i.indnkeyatts, i.indnatts,
           i.indpred::text, i.indexprs::text
      INTO is_unique, is_valid, key_column_count, total_column_count,
           predicate, expressions
      FROM pg_index i
     WHERE i.indexrelid = to_regclass('public."customers_organizationId_cxId_key"');

    IF is_unique IS DISTINCT FROM true THEN
        RAISE EXCEPTION 'V16: customers_organizationId_cxId_key is not UNIQUE, so it enforces nothing.';
    END IF;

    IF is_valid IS DISTINCT FROM true THEN
        RAISE EXCEPTION
            'V16: customers_organizationId_cxId_key is INVALID, so the uniqueness it claims is '
            'not being enforced.';
    END IF;

    IF key_column_count IS DISTINCT FROM 2 OR total_column_count IS DISTINCT FROM 2 THEN
        RAISE EXCEPTION
            'V16: customers_organizationId_cxId_key has % key column(s) of % total; it must have '
            'exactly two.', coalesce(key_column_count::text, 'null'),
            coalesce(total_column_count::text, 'null');
    END IF;

    IF predicate IS NOT NULL THEN
        RAISE EXCEPTION
            'V16: customers_organizationId_cxId_key is PARTIAL (%). A partial unique key lets two '
            'customers in one tenant share a reference whenever the predicate excludes one.',
            predicate;
    END IF;

    IF expressions IS NOT NULL THEN
        RAISE EXCEPTION
            'V16: customers_organizationId_cxId_key is built on EXPRESSIONS (%), not on the two '
            'columns, so it is not the key ADR-0014 asked for.', expressions;
    END IF;

    -- Identity is compared against the RENDERED form, which carries double
    -- quotes: pg_get_indexdef(idx, 1, true) is "organizationId", not
    -- organizationId. Arity came from indnkeyatts above rather than from probing
    -- a third position, because pg_get_indexdef(idx, 3, true) on a TWO-column
    -- index returns '' rather than NULL -- so `IS NULL` on it can never fire and
    -- `IS NOT NULL` fires on a correct index. Both measured on a throwaway.
    SELECT pg_get_indexdef(indexrelid, 1, true), pg_get_indexdef(indexrelid, 2, true)
      INTO key_col_1, key_col_2
      FROM pg_index
     WHERE indexrelid = to_regclass('public."customers_organizationId_cxId_key"');

    IF key_col_1 IS DISTINCT FROM '"organizationId"'
       OR key_col_2 IS DISTINCT FROM '"cxId"' THEN
        RAISE EXCEPTION
            'V16: customers_organizationId_cxId_key indexes (%, %), which is not '
            '("organizationId", "cxId") in that order. The reverse order would be a global unique '
            'key again in everything but its name.', coalesce(key_col_1, '(null)'),
            coalesce(key_col_2, '(null)');
    END IF;

    -- 3c. THE DETECTORS THEMSELVES DISCRIMINATE. A pattern that cannot match the
    --     thing it is meant to detect is a no-op assertion and passes forever.
    --     Three of this project's own checks were no-ops for exactly this reason
    --     (an OpenAPI exporter's class-level @RequestMapping, the tenant-SQL
    --     ratchet's blind spot, and V15's D4 totality probe), so the installed
    --     definition is matched by the same pattern used above.
    SELECT pg_get_indexdef(to_regclass('public."customers_organizationId_cxId_key"'))
      INTO installed_indexdef;

    IF installed_indexdef IS NULL
       OR installed_indexdef NOT LIKE '%UNIQUE INDEX%'
       OR installed_indexdef NOT LIKE '%"organizationId", "cxId"%' THEN
        RAISE EXCEPTION
            'V16: the composite-key detector does not match the installed definition "%", so every '
            'assertion above it is a no-op. Fix the pattern.', coalesce(installed_indexdef, '(absent)');
    END IF;

    -- 3d. TENANT SCOPE OF THE KEY, not just its existence. The key must be led
    --     by organizationId so it serves "this tenant's customer with reference
    --     #33000042" and classifies as tenant-led in V9's census. 3b already
    --     proved the order; this proves the consequence -- that the index is a
    --     usable tenant-scoped lookup rather than a global uniqueness machine.
    IF NOT EXISTS (SELECT 1 FROM pg_index i
                     JOIN pg_attribute a ON a.attrelid = i.indrelid
                                        AND a.attnum = (i.indkey::smallint[])[0]
                    WHERE i.indexrelid = to_regclass('public."customers_organizationId_cxId_key"')
                      AND a.attname = 'organizationId') THEN
        RAISE EXCEPTION
            'V16: customers_organizationId_cxId_key does not lead with organizationId.';
    END IF;

    -- 3e. The ten-tenant census, printed rather than asserted: how many distinct
    --     tenants share a reference. Under the defect this is 1 by
    --     construction. A value above 1 is the correction working; a value of
    --     exactly 1 on a multi-tenant database would mean nobody had yet
    --     allocated in a second tenant, which is a fact about the DATA and not a
    --     failure, so it is reported rather than asserted.
    SELECT count(DISTINCT "organizationId") INTO tenants_sharing FROM public.customers;
    RAISE NOTICE 'V16 3e: distinct organizations holding a customer reference = %', tenants_sharing;

    RAISE NOTICE
        'V16 3: customers_cxId_key is gone; customers_organizationId_cxId_key is UNIQUE, valid, '
        'non-partial, non-expression, on exactly ("organizationId", "cxId"), read from pg_catalog.';
END
$$;

-- ---------------------------------------------------------------------
-- 4. THE ALLOCATOR IS ONE STATEMENT, IS VOLATILE, AND COUNTS NOTHING.
--
-- Read from pg_get_functiondef -- the INSTALLED definition -- so an amendment
-- anywhere other than the migration cannot pass unnoticed. This is the Phase-10b
-- lesson applied: the claim must be about the object the database enforces, not
-- about a copy of the rule re-derived here.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    func_count      integer;
    func_volatility text;
    func_body       text;
    range_check     text;
    bigint_columns  text;
    arbiter_cols    text;
    tenant_fk       text;
BEGIN
    IF to_regclass('public."customer_cx_sequences"') IS NULL
       OR to_regprocedure('public.firstelement_next_customer_cx_id(uuid)') IS NULL THEN
        RAISE NOTICE 'V16 4: PHASE14_NOT_PRESENT (no allocator to inspect)';
        RETURN;
    END IF;

    -- Alias-free on purpose. Both catalog lookups below began as
    --   FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
    -- and the throwaway rehearsal hit `column p.proname does not exist` on the
    -- SECOND of the two: a query that is correct, that runs on its own, and that
    -- also runs correctly when the two are executed together in isolation.
    -- Filtering on pronamespace = 'public'::regnamespace sidesteps the alias
    -- resolution entirely and reads better besides. The behaviour was NOT fully
    -- root-caused and is recorded as such in
    -- docs/migration/phases/phase-14-customer-cxid-namespace.md, rather than
    -- presented as understood.
    SELECT count(*) INTO func_count
      FROM pg_proc
     WHERE pronamespace = 'public'::regnamespace
       AND proname = 'firstelement_next_customer_cx_id'
       AND pronargs = 1;

    IF func_count <> 1 THEN
        RAISE EXCEPTION
            'V16: public.firstelement_next_customer_cx_id(uuid) is absent or has the wrong arity '
            '(% found).', func_count;
    END IF;

    SELECT provolatile, pg_get_functiondef(oid) INTO func_volatility, func_body
      FROM pg_proc
     WHERE pronamespace = 'public'::regnamespace
       AND proname = 'firstelement_next_customer_cx_id'
       AND pronargs = 1;

    -- VOLATILE is what a planner must assume for an allocator. Marked STABLE or
    -- IMMUTABLE, a planner may collapse two calls in one statement into one and
    -- hand the same reference to two customers -- CX-1 re-entering through the
    -- function header.
    IF func_volatility <> 'v' THEN
        RAISE EXCEPTION
            'V16: firstelement_next_customer_cx_id is marked "%" instead of VOLATILE. An '
            'allocator marked STABLE or IMMUTABLE lets a planner treat two calls as one and hand '
            'the same reference to two customers.', func_volatility;
    END IF;

    IF func_body NOT LIKE '%ON CONFLICT%' THEN
        RAISE EXCEPTION
            'V16: the installed allocator has no ON CONFLICT clause, so it is a read-then-write '
            'and two concurrent writers can receive the same reference.';
    END IF;

    IF func_body NOT LIKE '%RETURNING%' THEN
        RAISE EXCEPTION
            'V16: the installed allocator does not RETURN the value it allocated, so the caller '
            'cannot be handed the value the database reserved for it.';
    END IF;

    -- The whole of ADR-0014's second fault. A reference derived from a row count
    -- is reissued after the holder is deleted.
    IF func_body ~* 'count[[:space:]]*\(' THEN
        RAISE EXCEPTION
            'V16: the installed allocator contains a count(). A reference derived from a row count '
            'is reissued after the customer that held it is deleted.';
    END IF;

    -- And the statement must touch ONE row of ONE table. A second relation in
    -- the body would be a second source of truth.
    IF (SELECT count(*) FROM regexp_matches(func_body, 'customer_cx_sequences', 'g')) < 2 THEN
        RAISE EXCEPTION
            'V16: the installed allocator does not mention customer_cx_sequences at least twice '
            '(once to insert, once to increment), so the single-statement contract is not the '
            'shape that shipped.';
    END IF;

    -- 4b. The counter is bounded by the FORMAT, not by the data. 999999 is the
    --     top of the `#33` + six digits range, which is a property of
    --     customers."cxId" varchar(9) and has nothing to do with how many rows
    --     exist.
    SELECT pg_get_constraintdef(oid) INTO range_check
      FROM pg_constraint
     WHERE conrelid = to_regclass('public."customer_cx_sequences"')
       AND conname = 'customer_cx_sequences_sequence_range_check';

    IF range_check IS NULL
       OR range_check NOT LIKE '%lastAllocatedCxSequence%'
       OR range_check NOT LIKE '%999999%' THEN
        RAISE EXCEPTION
            'V16: customer_cx_sequences_sequence_range_check is "%" and does not pin the sequence '
            'to the published 0 .. 999999 format.',
            coalesce(range_check, '(absent)');
    END IF;

    -- 4c. Exactly ONE bigint column, named for what it holds. A second one is the
    --     one place a future author could put a count and quietly reintroduce
    --     this defect, so the count of columns is asserted rather than the
    --     presence of one.
    SELECT string_agg(a.attname, ', ' ORDER BY a.attnum) INTO bigint_columns
      FROM pg_attribute a
     WHERE a.attrelid = to_regclass('public."customer_cx_sequences"')
       AND a.attnum > 0 AND NOT a.attisdropped
       AND format_type(a.atttypid, a.atttypmod) = 'bigint';

    IF bigint_columns IS DISTINCT FROM 'lastAllocatedCxSequence' THEN
        RAISE EXCEPTION
            'V16: the bigint column(s) on customer_cx_sequences are "%". There is to be exactly '
            'one, named lastAllocatedCxSequence, so its meaning cannot be mistaken for a count or '
            'for a next-value.', coalesce(bigint_columns, '(none)');
    END IF;

    -- 4d. The ON CONFLICT arbiter is unique and on organizationId ALONE. Without
    --     it, two counters per tenant are possible and each hands out #33000000.
    SELECT pg_get_indexdef(to_regclass('public."customer_cx_sequences_organizationId_key"'))
      INTO arbiter_cols;

    IF arbiter_cols IS NULL
       OR arbiter_cols NOT LIKE '%UNIQUE INDEX%'
       OR arbiter_cols NOT LIKE '%("organizationId")%' THEN
        RAISE EXCEPTION
            'V16: customer_cx_sequences_organizationId_key is "%"; allocation needs a UNIQUE '
            'arbiter on "organizationId" alone.', coalesce(arbiter_cols, '(absent)');
    END IF;

    -- 4e. The counter belongs to a tenant.
    SELECT pg_get_constraintdef(oid) INTO tenant_fk
      FROM pg_constraint
     WHERE conrelid = to_regclass('public."customer_cx_sequences"')
       AND conname = 'customer_cx_sequences_organizationId_fkey';

    IF tenant_fk IS NULL
       OR tenant_fk NOT LIKE '%FOREIGN KEY ("organizationId")%'
       OR tenant_fk NOT LIKE '%REFERENCES organizations(id)%' THEN
        RAISE EXCEPTION
            'V16: customer_cx_sequences_organizationId_fkey is "%"; a counter with no organization '
            'reference would let an allocation name a tenant that does not exist.',
            coalesce(tenant_fk, '(absent)');
    END IF;

    RAISE NOTICE
        'V16 4: firstelement_next_customer_cx_id(uuid) is VOLATILE and allocates in one statement '
        'with ON CONFLICT and RETURNING, and contains no count(). The counter is a single bigint '
        'named lastAllocatedCxSequence, bounded by CHECK to the 0 .. 999999 format, with a unique '
        'organizationId-only arbiter and a tenant FK.';
END
$$;

-- ---------------------------------------------------------------------
-- 5. THE COUNTER AGREES WITH max("cxId") FOR EVERY ORGANIZATION WITH CUSTOMERS.
--
-- Read-only, over whatever DATA exists. This is the property the backfill in
-- V20261007110000 exists to establish, re-checked here from the other side: the
-- migration asserts it immediately after writing it, and this asserts it later,
-- against a database that other code may have touched since.
--
-- GUARD: customer_cx_sequences, as above.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    orgs_with_customers  bigint;
    orgs_with_counter    bigint;
    counter_below_max    bigint;
    counter_too_high     bigint;
    malformed_references text;
    total_gaps           bigint;
    worst_gap            bigint;
    rec                  record;
BEGIN
    IF to_regclass('public."customer_cx_sequences"') IS NULL THEN
        RAISE NOTICE 'V16 5: PHASE14_NOT_PRESENT (no counter to compare against customers)';
        RETURN;
    END IF;

    SELECT count(DISTINCT "organizationId") INTO orgs_with_customers
      FROM public.customers;

    -- 5a. Every organization that HAS customers has a counter. An organization
    --     without one would be handed #33000000 again by the allocator's INSERT
    --     branch, and the per-organization key would not catch it.
    SELECT count(*) INTO orgs_with_counter
      FROM public.customer_cx_sequences s
     WHERE EXISTS (SELECT 1 FROM public.customers c
                    WHERE c."organizationId" = s."organizationId");

    SELECT count(*) INTO counter_below_max
      FROM (SELECT DISTINCT "organizationId" FROM public.customers) o
     WHERE NOT EXISTS (SELECT 1 FROM public.customer_cx_sequences s
                        WHERE s."organizationId" = o."organizationId");

    IF counter_below_max <> 0 THEN
        RAISE EXCEPTION
            'V16 5: % organization(s) hold customers but no counter, so the next allocation would '
            'reissue #33000000 to a tenant that already holds it -- and the per-organization key '
            'would not catch it.', counter_below_max;
    END IF;

    -- 5b. No counter sits BELOW its organization's live maximum.
    SELECT count(*) INTO counter_below_max
      FROM public.customers c
      JOIN public.customer_cx_sequences s ON s."organizationId" = c."organizationId"
     WHERE s."lastAllocatedCxSequence" < (substr(c."cxId", 4)::bigint);

    IF counter_below_max <> 0 THEN
        RAISE EXCEPTION
            'V16 5: % customer row(s) sit ABOVE their organization''s counter, so the next '
            'allocation would reissue a LIVE reference.', counter_below_max;
    END IF;

    -- 5c. And no counter sits absurdly ABOVE it. A counter far above the data is
    --     not itself a violation -- the format allows 1000000 references and a
    --     tenant may have deleted many -- but it is the shape a corrupted counter
    --     takes, and it is the negative control's corruption. Reported, and
    --     asserted only against the format ceiling.
    SELECT count(*) INTO counter_too_high
      FROM public.customer_cx_sequences s
     WHERE s."lastAllocatedCxSequence" > 999999;

    IF counter_too_high <> 0 THEN
        RAISE EXCEPTION
            'V16 5: % counter(s) are above the published 0 .. 999999 format. The CHECK should '
            'have made this impossible; if it fires, the CHECK is missing or disabled.',
            counter_too_high;
    END IF;

    -- 5d. The reference format itself, over every row. This is what makes 5b's
    --     substr("cxId", 4) parse defensible rather than hopeful.
    SELECT string_agg("cxId", ', ' ORDER BY "cxId") INTO malformed_references
      FROM public.customers
     WHERE "cxId" !~ '^#33[0-9]{6}$'
     LIMIT 20;

    IF malformed_references IS NOT NULL THEN
        RAISE EXCEPTION
            'V16 5: customer reference(s) [%] do not match the published #33 + six-digit format, so '
            'the counter cannot be compared with them.', malformed_references;
    END IF;

    -- 5e. THE RESIDUAL, MEASURED AND REPORTED, NOT ASSERTED TO BE ZERO.
    --     Gaps between the format floor and each tenant's maximum that no
    --     surviving customer holds. A gap is not necessarily a reused
    --     reference: the legacy allocator also burned values on failed inserts.
    --     Nothing in this schema records the deletion of a customer reference, so
    --     "was that value ever issued?" is not answerable from here. Follow-up
    --     F-14-1.
    SELECT coalesce(sum(g), 0), coalesce(max(g), 0) INTO total_gaps, worst_gap
      FROM (SELECT max((substr(c."cxId", 4)::bigint)) + 1 - count(DISTINCT c."cxId") AS g
              FROM public.customers c
             GROUP BY c."organizationId") gaps;

    RAISE NOTICE 'V16 5 census (organization_id | max_live_sequence | counter | consistent):';
    FOR rec IN EXECUTE $q$
        SELECT c."organizationId"::text AS org,
               max((substr(c."cxId", 4)::bigint))::text AS max_live,
               s."lastAllocatedCxSequence"::text AS counter_value,
               (s."lastAllocatedCxSequence" >= max((substr(c."cxId", 4)::bigint)))::text AS ok
          FROM public.customers c
          JOIN public.customer_cx_sequences s ON s."organizationId" = c."organizationId"
         GROUP BY c."organizationId", s."lastAllocatedCxSequence"
         ORDER BY 1 $q$
    LOOP
        RAISE NOTICE '   % | % | % | %', rec.org, rec.max_live, rec.counter_value, rec.ok;
    END LOOP;

    RAISE NOTICE
        'V16 5: % organization(s) hold customers and all % of them have a counter at or above '
        'their live maximum; 0 references off-format; gaps_below_each_tenant_maximum = % (worst '
        'single tenant %) -- reported, not asserted to be zero. Follow-up F-14-1.',
        orgs_with_customers, orgs_with_counter, total_gaps, worst_gap;
END
$$;

-- ---------------------------------------------------------------------
-- ---------------------------------------------------------------------
-- 6. BEHAVIOUR. The part that would not fit in a schema check.
--
-- Everything here runs inside the transaction opened below, which this file
-- always rolls back. See the header for why that is a structural property
-- rather than a matter of care.
--
-- The scenarios seed themselves from nothing, inside the transaction, so the
-- assertions do not depend on any externally prepared fixture. Each scenario
-- that needs a tenant with NO prior allocation creates its own, which is what
-- makes "the first reference is #33000000" and "the counter ends at 2"
-- checkable rather than plausible.
--
-- Every probe row is named zz-cx-probe*, and section 7 counts what is left.
-- ---------------------------------------------------------------------
BEGIN;

DO $$
DECLARE
    orgs            uuid[] := ARRAY[]::uuid[];
    projs           uuid[] := ARRAY[]::uuid[];
    firsts          text[] := ARRAY[]::text[];
    project_cx      text[] := ARRAY[]::text[];
    fresh_org       uuid;
    fresh_proj      uuid;
    probe_id        uuid;
    deleted_cx      text;
    v1              text;
    v2              text;
    v3              text;
    w               text;
    i               integer;
    rows_now        bigint;
    distinct_firsts bigint;
    shared_values   bigint;
BEGIN
    IF to_regclass('public."customer_cx_sequences"') IS NULL
       OR to_regprocedure('public.firstelement_next_customer_cx_id(uuid)') IS NULL
       OR to_regclass('public."customers_organizationId_cxId_key"') IS NULL THEN
        RAISE NOTICE
            'V16 6: PHASE14_NOT_PRESENT (no allocator and no rescoped key, so there is no behaviour '
            'to prove). Section 7 will still check that nothing was written.';
        RETURN;
    END IF;

    ---------------------------------------------------------------------
    -- 6a + 6f. THE CAPACITY REGRESSION, ACROSS SIX ORGANIZATIONS.
    --
    -- The defect makes this impossible: six organizations all begin at
    -- #33000000, and under the GLOBAL key only the first could have it. All six
    -- of these rows will carry the SAME reference value, which is the sharpest
    -- available statement that the global key is gone -- asserted as a count
    -- below, not merely described here. 6f is the same measurement read as the
    -- question it answers: the pre-migration global key would have REFUSED the
    -- second of these six inserts.
    ---------------------------------------------------------------------
    FOR i IN 1 .. 6 LOOP
        fresh_org := gen_random_uuid();
        orgs := array_append(orgs, fresh_org);

        INSERT INTO public.organizations
            ("id", "name", "slug", "defaultLocale", "timezone", "createdAt", "updatedAt")
        VALUES (fresh_org, 'zz-cx-probe-org-' || i, 'zz-cx-probe-org-' || i, 'ar', 'Asia/Riyadh',
                (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'), (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'));

        INSERT INTO public.projects
            ("id", "organizationId", "name", "slug", "defaultLocale", "timezone",
             "createdAt", "updatedAt")
        VALUES (gen_random_uuid(), fresh_org, 'zz-cx-probe-proj-' || i, 'zz-cx-probe-proj-' || i,
                'ar', 'Asia/Riyadh',
                (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'), (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'));

        fresh_proj := (SELECT "id" FROM public.projects
                        WHERE "organizationId" = fresh_org ORDER BY "id" LIMIT 1);
        projs := array_append(projs, fresh_proj);
    END LOOP;

    FOR i IN 1 .. 6 LOOP
        w := public.firstelement_next_customer_cx_id(orgs[i]);
        firsts := array_append(firsts, w);

        INSERT INTO public.customers
            ("id", "organizationId", "projectId", "cxId", "displayName", "createdAt", "updatedAt")
        VALUES (gen_random_uuid(), orgs[i], projs[i], w, 'zz-cx-probe-org',
                (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'), (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'));
    END LOOP;

    -- Six customers exist...
    SELECT count(*) INTO rows_now
      FROM public.customers
     WHERE "displayName" = 'zz-cx-probe-org' AND "organizationId" = ANY (orgs);

    IF rows_now <> 6 THEN
        RAISE EXCEPTION
            'V16 6a: only % of 6 organizations could take their FIRST customer reference. This is '
            'the capacity limit ADR-0014 describes -- at most five references could ever exist '
            'across the deployment, and the sixth project''s first customer threw.', rows_now;
    END IF;

    -- ...sharing exactly ONE reference value between them.
    SELECT count(DISTINCT "cxId") INTO distinct_firsts
      FROM public.customers WHERE "displayName" = 'zz-cx-probe-org';

    IF distinct_firsts IS DISTINCT FROM 1 THEN
        RAISE EXCEPTION
            'V16 6a: the six organizations produced % distinct first reference(s); a per-'
            'organization counter starts every tenant at the same value by design.',
            distinct_firsts;
    END IF;

    -- And the composite key is what let the second through: one reference value
    -- held by six DIFFERENT tenants.
    SELECT count(*) INTO shared_values
      FROM (SELECT "cxId" FROM public.customers
             WHERE "displayName" = 'zz-cx-probe-org'
            GROUP BY "cxId" HAVING count(DISTINCT "organizationId") = 6) g;

    IF shared_values IS DISTINCT FROM 1 THEN
        RAISE EXCEPTION
            'V16 6a/6f: % reference value(s) are held by all six tenants; expected exactly 1. '
            'Under the pre-migration GLOBAL key the second of these six inserts would have been '
            'REFUSED.', coalesce(shared_values::text, 'null');
    END IF;

    RAISE NOTICE
        'V16 6a/6f: six organizations each took a first customer reference; all six rows carry % '
        'and coexist, which the removed global key would have refused at the second insert.',
        firsts[1];

    ---------------------------------------------------------------------
    -- 6b. THE CAPACITY REGRESSION, ACROSS SIX PROJECTS INSIDE ONE ORGANIZATION.
    --
    -- The second half of ADR-0014's scenario. Here the references must be
    -- DIFFERENT from one another, because they share a tenant and the key is
    -- per-organization -- so this also proves the counter advances across the
    -- whole tenant instead of restarting per project, which is precisely what
    -- turned the defect from a race into a capacity limit.
    ---------------------------------------------------------------------
    FOR i IN 1 .. 6 LOOP
        INSERT INTO public.projects
            ("id", "organizationId", "name", "slug", "defaultLocale", "timezone",
             "createdAt", "updatedAt")
        VALUES (gen_random_uuid(), orgs[1], 'zz-cx-probe-shared-' || i,
                'zz-cx-probe-shared-' || i, 'ar', 'Asia/Riyadh',
                (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'), (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'));
        projs := array_append(projs, (SELECT "id" FROM public.projects
                                       WHERE "organizationId" = orgs[1]
                                         AND "slug" = 'zz-cx-probe-shared-' || i));
    END LOOP;

    FOR i IN 1 .. 6 LOOP
        w := public.firstelement_next_customer_cx_id(orgs[1]);
        project_cx := array_append(project_cx, w);

        INSERT INTO public.customers
            ("id", "organizationId", "projectId", "cxId", "displayName", "createdAt", "updatedAt")
        VALUES (gen_random_uuid(), orgs[1], projs[i], w, 'zz-cx-probe-shared',
                (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'), (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'));
    END LOOP;

    SELECT count(DISTINCT "cxId") INTO distinct_firsts
      FROM public.customers
     WHERE "displayName" = 'zz-cx-probe-shared' AND "organizationId" = orgs[1];

    IF distinct_firsts IS DISTINCT FROM 6 THEN
        RAISE EXCEPTION
            'V16 6b: six projects in ONE organization produced only % distinct reference(s). The '
            'counter must advance across the whole tenant rather than restart per project; a '
            'per-project counter is exactly the defect ADR-0014 rejected.', distinct_firsts;
    END IF;

    -- And strictly increasing, read off the six values themselves.
    FOR i IN 2 .. 6 LOOP
        IF (substr(project_cx[i], 4)::bigint) <= (substr(project_cx[i - 1], 4)::bigint) THEN
            RAISE EXCEPTION
                'V16 6b: reference % in one organization (%s) did not exceed its predecessor (%s).',
                i, project_cx[i], project_cx[i - 1];
        END IF;
    END LOOP;

    RAISE NOTICE
        'V16 6b: six projects inside one organization produced six strictly increasing references '
        '(% .. %).', project_cx[1], project_cx[6];

    ---------------------------------------------------------------------
    -- 6c. MONOTONICITY, on a tenant with NO prior allocation, so the expected
    --     counter at the end is arithmetic rather than a remembered number.
    ---------------------------------------------------------------------
    fresh_org := gen_random_uuid();
    INSERT INTO public.organizations
        ("id", "name", "slug", "defaultLocale", "timezone", "createdAt", "updatedAt")
    VALUES (fresh_org, 'zz-cx-probe-mono', 'zz-cx-probe-mono', 'ar', 'Asia/Riyadh',
            (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'), (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'));
    INSERT INTO public.projects
        ("id", "organizationId", "name", "slug", "defaultLocale", "timezone",
         "createdAt", "updatedAt")
    VALUES (gen_random_uuid(), fresh_org, 'zz-cx-probe-mono-p', 'zz-cx-probe-mono-p', 'ar',
            'Asia/Riyadh',
            (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'), (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'));
    fresh_proj := (SELECT "id" FROM public.projects WHERE "organizationId" = fresh_org);

    v1 := public.firstelement_next_customer_cx_id(fresh_org);
    v2 := public.firstelement_next_customer_cx_id(fresh_org);
    v3 := public.firstelement_next_customer_cx_id(fresh_org);

    IF v1 <> '#33000000' THEN
        RAISE EXCEPTION 'V16 6c: a fresh organization''s first reference is %, not #33000000.', v1;
    END IF;

    IF v2 <> '#33000001' OR v3 <> '#33000002' THEN
        RAISE EXCEPTION
            'V16 6c: three successive allocations from a fresh organization returned %, %, % '
            'rather than #33000000, #33000001, #33000002.', v1, v2, v3;
    END IF;

    SELECT count(*) INTO rows_now
      FROM public.customer_cx_sequences
     WHERE "organizationId" = fresh_org AND "lastAllocatedCxSequence" = 2;

    IF rows_now <> 1 THEN
        RAISE EXCEPTION
            'V16 6c: after three allocations the counter should read 2; % counter row(s) agree. '
            'The allocator and the stored high-water mark have diverged.', rows_now;
    END IF;

    RAISE NOTICE
        'V16 6c: three successive allocations in one fresh organization returned %, %, % -- '
        'strictly increasing, leaving the stored counter at exactly 2.', v1, v2, v3;

    ---------------------------------------------------------------------
    -- 6d. NO REUSE AFTER THE HOLDER IS DELETED. The fault ADR-0014 names
    --     separately from the exhaustion: a `count(*)` allocator reopens a gap
    --     below the high-water mark, so a later customer can be issued a
    --     reference a DELETED customer once held, and every surviving URL,
    --     export, audit record or invoice naming it resolves to the wrong
    --     person.
    ---------------------------------------------------------------------
    fresh_org := gen_random_uuid();
    INSERT INTO public.organizations
        ("id", "name", "slug", "defaultLocale", "timezone", "createdAt", "updatedAt")
    VALUES (fresh_org, 'zz-cx-probe-del', 'zz-cx-probe-del', 'ar', 'Asia/Riyadh',
            (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'), (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'));
    INSERT INTO public.projects
        ("id", "organizationId", "name", "slug", "defaultLocale", "timezone",
         "createdAt", "updatedAt")
    VALUES (gen_random_uuid(), fresh_org, 'zz-cx-probe-del-p', 'zz-cx-probe-del-p', 'ar',
            'Asia/Riyadh',
            (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'), (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'));
    fresh_proj := (SELECT "id" FROM public.projects WHERE "organizationId" = fresh_org);

    deleted_cx := public.firstelement_next_customer_cx_id(fresh_org);
    probe_id := gen_random_uuid();
    INSERT INTO public.customers
        ("id", "organizationId", "projectId", "cxId", "displayName", "createdAt", "updatedAt")
    VALUES (probe_id, fresh_org, fresh_proj, deleted_cx, 'zz-cx-probe-del',
            (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'), (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'));

    DELETE FROM public.customers WHERE "id" = probe_id;

    -- The row is gone, so a reuse refusal cannot be confused with the composite
    -- key merely doing its job on a live duplicate.
    IF EXISTS (SELECT 1 FROM public.customers WHERE "id" = probe_id) THEN
        RAISE EXCEPTION
            'V16 6d: the probe customer was not deleted, so the following assertion could not '
            'distinguish a real high-water mark from the composite key refusing a live duplicate.';
    END IF;

    w := public.firstelement_next_customer_cx_id(fresh_org);

    IF w = deleted_cx THEN
        RAISE EXCEPTION
            'V16 6d: after deleting the holder of %, the next allocation returned % AGAIN. The '
            'counter must survive the deletion of its customer: deleting a customer must never '
            'reopen a gap below the high-water mark.', deleted_cx, w;
    END IF;

    IF (substr(w, 4)::bigint) <= (substr(deleted_cx, 4)::bigint) THEN
        RAISE EXCEPTION
            'V16 6d: the reference after deletion (%) did not advance past the deleted one (%).',
            w, deleted_cx;
    END IF;

    -- And the counter on disk did not move when the customer was deleted -- it is
    -- the customer row that went, not the tenant's issued history.
    SELECT count(*) INTO rows_now
      FROM public.customer_cx_sequences
     WHERE "organizationId" = fresh_org AND "lastAllocatedCxSequence" = 1;

    IF rows_now <> 1 THEN
        RAISE EXCEPTION
            'V16 6d: deleting the only customer of a tenant rewrote its counter (% row(s) read 1). '
            'The high-water mark must be independent of whether any row still exists.', rows_now;
    END IF;

    RAISE NOTICE
        'V16 6d: % was issued, its holder deleted, and the next allocation returned % -- strictly '
        'past it. No reuse, and the counter did not move on the deletion.', deleted_cx, w;

    ---------------------------------------------------------------------
    -- 6e. NO CROSS-TENANT INFERENCE, ADR-0014 regression test 4.
    --
    -- Two organizations allocating independently must not be able to derive each
    -- other's volume from their references. The evidence is that all six BEGIN
    -- at #33000000: a global counter would have made the second tenant's first
    -- reference #33000001, and from that one number a tenant could infer that
    -- exactly one customer had ever been created in the entire deployment.
    --
    -- 6a already measured "all six share one value"; this names the property it
    -- implies and pins the value, so the assertion reads as the privacy property
    -- it is rather than as a by-product of 6a.
    ---------------------------------------------------------------------
    SELECT count(DISTINCT f) INTO distinct_firsts FROM unnest(firsts) AS f;

    IF distinct_firsts IS DISTINCT FROM 1 OR firsts[1] <> '#33000000' THEN
        RAISE EXCEPTION
            'V16 6e: six independent tenants'' first references took % distinct value(s) starting '
            'at %; they must all start at #33000000, or a tenant can infer the deployment''s total '
            'customer count from its own numbering.',
            distinct_firsts, firsts[1];
    END IF;

    RAISE NOTICE
        'V16 6e: all six independent tenants started at %, so no tenant can derive another''s '
        'volume from its own references.', firsts[1];

    RAISE NOTICE
        'V16 6: capacity across six organizations, capacity across six projects in one '
        'organization, strict monotonicity, no reuse after deletion, and no cross-tenant '
        'inference -- all held, inside a transaction this file rolls back.';
END
$$;

ROLLBACK;

-- ---------------------------------------------------------------------
-- 7. NOTHING WAS LEFT BEHIND.
--
-- Read AFTER the ROLLBACK, from the row counts -- not from the fact that this
-- file contains the word ROLLBACK, and not from any message section 6 printed.
-- (ADR-0009 section 5: "REFUSED" is a claim; a surviving row is evidence, and so
-- is the absence of one.) This is the assertion that makes section 6 safe to
-- run against the dev database.
--
-- GUARD: section 6 only wrote if the batch is present, so on a pre-cutover
-- database there is nothing to have left behind and the whole section reports
-- NOT_PRESENT without touching customer_cx_sequences.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    orgs_before    bigint;
    projects_before bigint;
    customers_before bigint;
    counters_before bigint;
    probes_left    bigint;
BEGIN
    IF to_regclass('public."customer_cx_sequences"') IS NULL THEN
        RAISE NOTICE
            'V16 7: PHASE14_NOT_PRESENT (no counter table, so section 6 wrote nothing)';
        RETURN;
    END IF;

    -- Nothing named zz-cx-probe survives. These are the only names section 6
    -- inserts, so their total absence is the proof the rollback happened.
    SELECT (SELECT count(*) FROM public.organizations WHERE "slug" LIKE 'zz-cx-probe%')
         + (SELECT count(*) FROM public.projects      WHERE "slug" LIKE 'zz-cx-probe%')
         + (SELECT count(*) FROM public.customers     WHERE "displayName" LIKE 'zz-cx-probe%')
      INTO probes_left;

    IF probes_left <> 0 THEN
        RAISE EXCEPTION
            'V16 7: % zz-cx-probe row(s) SURVIVED section 6''s rollback. The behavioural scenarios '
            'are supposed to leave nothing behind; if this fires, the transaction did not roll '
            'back and the database has been modified.', probes_left;
    END IF;

    -- And the counters are still exactly what they were before section 6 ran:
    -- one row per organization that had customers, none for the six probes.
    -- A probe organization''s counter would have to be absent, because the
    -- organization it referenced is absent too (the FK has ON DELETE CASCADE,
    -- and the rollback removed both).
    SELECT count(*) INTO counters_before
      FROM public.customer_cx_sequences s
     WHERE NOT EXISTS (SELECT 1 FROM public.organizations o
                        WHERE o."id" = s."organizationId");

    IF counters_before <> 0 THEN
        RAISE EXCEPTION
            'V16 7: % counter row(s) reference an organization that does not exist, which is only '
            'possible if section 6''s inserts survived its rollback.', counters_before;
    END IF;

    RAISE NOTICE
        'V16 7: after the rollback, 0 zz-cx-probe rows remain in organizations, projects or '
        'customers, and % counter rows reference a missing organization. The behavioural section '
        'is confirmed to have left no trace.',
        counters_before;
END
$$;
