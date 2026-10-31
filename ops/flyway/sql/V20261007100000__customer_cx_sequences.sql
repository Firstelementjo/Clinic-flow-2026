-- =====================================================================
-- V20261007100000__customer_cx_sequences.sql
-- ADDITIVE.  TRANSACTIONAL.  ONE TABLE, ONE UNIQUE INDEX, ONE FUNCTION.
--
-- P0, BLOCKING FOR CUTOVER.  REMEDIATION OF DEFECT CX-1, AND THE RULING
-- THAT PROMISED IT: ADR-0014 "the customer reference namespace is a scarce
-- resource".
--
-- THE DEFECT THIS EXISTS TO REMOVE, IN ONE PARAGRAPH
--   CustomerRepository.nextCxSequence(scope) allocates from a PROJECT-SCOPED
--   `select count(*) from customers`, customers."cxId" carries a GLOBAL unique
--   index, and IdentityResolver retries only 5 times. Every project therefore
--   begins its search at #33000000, the first five projects consume
--   #33000000 .. #33000004, and the SIXTH project's first customer throws and
--   every inbound message for that project answers 500 -- permanently. At most
--   FIVE customer references can ever exist across the entire deployment. That
--   is not a race, and no retry count fixes it: a retry loop over a shared
--   namespace solves contention, not exhaustion.
--
-- WHAT THIS FILE IS
--   The allocator's missing state. `count(*)` was being asked for a fact that is
--   not derivable from the rows -- what has this tenant ALREADY ISSUED -- and it
--   answered with the wrong number (rows that still exist) instead of the right
--   one (references that were ever handed out). This table holds that fact
--   explicitly.
--
-- ATOMICITY IS A SCHEMA PROPERTY, NOT A CALLER DISCIPLINE
--   firstelement_next_customer_cx_id(uuid) is the ONLY supported way to obtain
--   a reference, and it allocates in exactly ONE statement:
--
--     INSERT ... ON CONFLICT ("organizationId") DO UPDATE
--            SET "lastAllocatedCxSequence" = <existing> + 1
--     RETURNING "lastAllocatedCxSequence"
--
--   The ON CONFLICT DO UPDATE takes a row lock on the tenant's counter, so two
--   concurrent inbound messages in the same organization are serialised and
--   receive DIFFERENT values. There is no read-then-write anywhere, so there is
--   no window between "read the counter" and "write the counter" for a second
--   writer to slip into. A read-then-write implementation would be a race that
--   only shows up under load, which is the worst place to find one.
--
-- NO count(*) ANYWHERE. Not in this file, not in the function, and V16 section 4
--   asserts the INSTALLED function body contains none. A counter derived from a
--   row count is the defect itself: deleting a customer reopens a gap below the
--   high-water mark, so a later customer can be issued a reference that a
--   DELETED customer once held, and every surviving URL, export, audit record or
--   invoice naming that cxId then resolves to the wrong person.
--
-- WHY A PER-ORGANIZATION COUNTER AND NOT A POSTGRESQL SEQUENCE
--   A global SEQUENCE is simpler and keeps cxId globally unique, and it was
--   rejected on purpose: a global counter makes the TOTAL number of customers
--   ever created across ALL tenants inferable from any single tenant's cxId.
--   That is a cross-tenant information disclosure in a product whose rules treat
--   tenant isolation as mandatory. Per organization, each tenant's first
--   reference is #33000000, so no tenant can read another's volume off its own
--   numbering. ADR-0014 section "Decision"; V16 section 6f asserts it
--   behaviourally, by allocating independently in two organizations and
--   observing BOTH begin at #33000000.
--
-- WHY THE NUMBER IS A SIX-DIGIT SEQUENCE AND NOT THE EIGHT-DIGIT BODY
--   customers."cxId" is varchar(9) in the published format `#33` + SIX digits
--   (TicketNumber.generateCxId, and every row in the verified live database:
--   #33000000, #33000001, #33000002). So the counter's domain is 0 .. 999999 and
--   its meaning is "the last SIX-DIGIT sequence handed to this tenant", which is
--   exactly what the existing, already-tested formatter consumes. Storing the
--   full eight-digit body instead would invite a caller to pass 33000002 into a
--   function that pads to six digits and produce `#3333000002` -- thirteen
--   characters, silently wider than the column.
--
-- THE CEILING IS REAL AND IT IS STATED: 1,000,000 references per organization
--   (0 .. 999999). The CHECK below refuses allocation past it rather than
--   emitting a reference the varchar(9) column cannot hold. Contrast that with
--   the five references the defect allows deployment-wide.
--
-- TENANT SCOPE
--   organizationId is NOT NULL with a FOREIGN KEY to organizations(id), so a
--   counter cannot exist for an organization that does not. ON DELETE CASCADE,
--   so deleting a TENANT removes its counter -- and that is the only thing that
--   ever removes one. Deleting a CUSTOMER never touches it, which is precisely
--   what makes "never reused, including after a deletion" true.
--
--   No projectId: a customer's identity is an organization-level thing (ADR
--   0014 rejects a per-project sequence), and a counter with a project column
--   would invite the very per-project exhaustion being removed.
--
-- WHY NO `version` COLUMN, WHEN MOST TABLES HERE HAVE ONE
--   `version` exists so two callers can detect that they edited the same row.
--   Nothing here edits a counter except the allocator, and the allocator
--   serialises itself with a row lock. A version column on this table would
--   suggest an optimistic-concurrency protocol that has no second writer, which
--   is the kind of affordance that gets misused later.
--
-- WHAT DOES NOT HAPPEN HERE
--   * No DROP, no RENAME, no ALTER of any pre-existing table, column or index.
--     This file only ADDS. V20261007110000 is the half that replaces a
--     constraint, and that is the one permitted DROP in the pair.
--   * No NOT NULL added anywhere, and none on ai_run_* (ADR-0009 section 2).
--   * No index on any pre-existing table, so there is no index build here at all
--     and therefore no question about CREATE INDEX CONCURRENTLY. The one
--     pre-existing-table index in this pair is a plain CREATE UNIQUE INDEX in
--     V20261007110000: ADR-0005 section 3 and ADR-0009 section 1 both reject
--     `spring.flyway.postgresql.transactional-lock=false`, because it is global
--     and would drop the schema-history lock for the whole chain.
--   * No secret, token or credential of any kind.
--
-- TIMESTAMPS  timestamp(3) without time zone throughout (V6).
-- Rollback: ops/flyway/rollback/undo_phase14.sql drops the function and the
-- table, and refuses if any counter is above its organization's live maximum --
-- because a counter above the data is evidence that references were issued, and
-- dropping it reopens the reuse hole.
-- Every identifier is schema-qualified (the baseline sets search_path = '').
-- =====================================================================

-- ---------------------------------------------------------------------
-- customer_cx_sequences
--
-- One row per organization, created lazily by the allocator's ON CONFLICT
-- branch and backfilled for organizations that already have customers by
-- V20261007110000. The absence of a row is itself meaningful: it means the
-- tenant has never been issued a reference.
-- ---------------------------------------------------------------------
CREATE TABLE "public"."customer_cx_sequences" (
    "id"                     uuid NOT NULL,
    "organizationId"         uuid NOT NULL,
    -- THE COUNTER. Its meaning, exactly: the highest six-digit reference
    -- sequence this organization has EVER been issued, so its next reference is
    -- this value plus one, and any value at or below it has already been given
    -- away. 0 therefore means "#33000000 was the first reference issued", and a
    -- missing row means "nothing has been issued yet". Both facts are why the
    -- column is NAMED rather than called "next" or "count".
    "lastAllocatedCxSequence" bigint NOT NULL,
    "createdAt"              timestamp(3) without time zone NOT NULL
                                        DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'),
    "updatedAt"              timestamp(3) without time zone NOT NULL
                                        DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'),
    CONSTRAINT "customer_cx_sequences_pkey" PRIMARY KEY ("id"),
    -- The published format is `#33` + six digits, so the sequence domain is
    -- 0 .. 999999 and the ceiling is 1,000,000 references per organization.
    -- The CHECK is what refuses allocation past the ceiling: it is the reason an
    -- exhausted tenant gets a diagnosable constraint violation instead of a
    -- ten-character reference that varchar(9) would reject AFTER the counter had
    -- already consumed the value, leaving a permanent gap.
    CONSTRAINT "customer_cx_sequences_sequence_range_check"
        CHECK ("lastAllocatedCxSequence" BETWEEN 0 AND 999999)
);

ALTER TABLE ONLY "public"."customer_cx_sequences"
    ADD CONSTRAINT "customer_cx_sequences_organizationId_fkey"
    FOREIGN KEY ("organizationId") REFERENCES "public"."organizations"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

-- ONE COUNTER PER ORGANIZATION. This is not only the business rule; it is the
-- ON CONFLICT arbiter that makes allocation atomic, so it is load-bearing for
-- correctness and not decoration. Without it two counters could exist for one
-- tenant, each handing out #33000000.
--
-- A UNIQUE INDEX rather than a UNIQUE CONSTRAINT, so V4's
-- unique_constraints = 0 invariant survives -- the convention every migration in
-- this chain follows.
CREATE UNIQUE INDEX "customer_cx_sequences_organizationId_key"
    ON "public"."customer_cx_sequences" USING "btree" ("organizationId");

COMMENT ON TABLE "public"."customer_cx_sequences" IS
    'Phase 14 (ADR-0014, CX-1): one row per organization holding the highest '
    'customer reference sequence (#33 + six digits) ever issued to that tenant. '
    'The allocator is public.firstelement_next_customer_cx_id(uuid), which '
    'increments atomically in a single statement. Never derived from a row '
    'count: deleting a customer must not reopen a gap below the high-water mark.';

COMMENT ON COLUMN "public"."customer_cx_sequences"."lastAllocatedCxSequence" IS
    'The last six-digit reference sequence issued to this organization; the next '
    'reference is this plus one. 0 means #33000000 has been issued; a missing row '
    'means the tenant has never been issued one. Range 0 .. 999999 is the '
    '`#33` + six digits format of customers."cxId" varchar(9).';

-- ---------------------------------------------------------------------
-- THE ALLOCATOR
--
-- One statement, one row lock, no read-then-write, no count(*).
--
--   * The INSERT branch creates the counter for a tenant that has never been
--     issued a reference, handing out #33000000.
--   * The DO UPDATE branch takes a row lock and increments. A concurrent writer
--     in the same organization blocks on that lock and then reads the ALREADY
--     INCREMENTED value, so the two receive different sequences. That is the
--     whole mechanism, and it is why no retry loop is needed for correctness.
--   * RETURNING yields the value the caller must use -- the NEW one, not the old
--     one. Returning the post-increment value is what makes the single statement
--     possible at all; a "next value to hand out" column would have to return
--     value - 1, which is an off-by-one waiting to happen.
--
-- The formatting lives here too, so no caller can build the reference itself and
-- get the width or the prefix wrong.
--
-- `AS s` is an alias for the target table, which is what lets the DO UPDATE read
-- the EXISTING row's value without a second lookup. The function body's
-- statements are prepared lazily, so the guarded DO blocks inside this migration
-- below are safe for the same reason V15's are.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION "public"."firstelement_next_customer_cx_id"(
    p_organization_id uuid
)
RETURNS character varying(9)
LANGUAGE plpgsql
VOLATILE
AS $$
DECLARE
    allocated bigint;
BEGIN
    IF p_organization_id IS NULL THEN
        RAISE EXCEPTION
            'public.firstelement_next_customer_cx_id requires an organizationId. A customer '
            'reference is issued to one tenant; allocating without a tenant would issue it to the '
            'whole deployment.'
            USING ERRCODE = 'restrict_violation';
    END IF;

    BEGIN
        INSERT INTO "public"."customer_cx_sequences" AS s
            ("id", "organizationId", "lastAllocatedCxSequence", "createdAt", "updatedAt")
        VALUES (
            gen_random_uuid(), p_organization_id, 0,
            (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'),
            (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'))
        ON CONFLICT ("organizationId") DO UPDATE
            SET "lastAllocatedCxSequence" = s."lastAllocatedCxSequence" + 1,
                "updatedAt"          = (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')
        RETURNING "lastAllocatedCxSequence" INTO allocated;

    EXCEPTION
        -- The ONLY CHECK on this table is the sequence range, so this handler is
        -- unambiguous. It exists to turn a raw constraint name into a statement
        -- an operator can act on. The subtransaction is rolled back before the
        -- re-raise, so an exhausted tenant CONSUMES NOTHING: no gap is burned
        -- past the ceiling, which matters because a gap is a reference skipped
        -- forever.
        WHEN check_violation THEN
            RAISE EXCEPTION
                'organization % has exhausted the customer reference namespace: 1000000 '
                'references (#33000000 .. #33999999) have been issued to it. This is the format '
                'ceiling recorded in ADR-0014, not a transient failure, and no retry can pass it. '
                'No value was consumed by this failure.', p_organization_id
                USING ERRCODE = 'restrict_violation';
    END;

    -- Defensive, and cheap. The CHECK already guarantees the range, so this can
    -- only fire if someone widens the CHECK without widening the format -- and
    -- the failure that would cause is a silently ten-character reference, which
    -- customers."cxId" varchar(9) rejects much later and much less legibly.
    IF allocated < 0 OR allocated > 999999 THEN
        RAISE EXCEPTION
            'public.firstelement_next_customer_cx_id produced sequence %, outside the published '
            '0 .. 999999 format. This means customer_cx_sequences_sequence_range_check and the '
            'formatter have diverged.', allocated
            USING ERRCODE = 'restrict_violation';
    END IF;

    RETURN '#33' || lpad(allocated::text, 6, '0');
END
$$;

COMMENT ON FUNCTION "public"."firstelement_next_customer_cx_id"(uuid) IS
    'Phase 14 (ADR-0014, CX-1): the ONLY supported way to obtain a customer '
    'reference. Atomically allocates the next #33 + six-digit sequence for one '
    'organization with INSERT ... ON CONFLICT DO UPDATE ... RETURNING, so '
    'concurrent writers in the same tenant receive different references. Never '
    'derives the value from a row count, so a reference is never reissued after '
    'the customer that held it is deleted. Refuses when the tenant has exhausted '
    'the 1000000-reference format ceiling, consuming nothing.';

-- ---------------------------------------------------------------------
-- POST-CONDITION. Assert on the STATE, read back from the catalog, never on
-- this file's own intent. (ADR-0009 section 5.)
-- ---------------------------------------------------------------------
DO $$
DECLARE
    arbiter_present  boolean;
    arbiter_columns  text;
    value_check      text;
    tenant_fk        text;
    tenant_fk_shape  boolean;
    func_count       integer;
    func_volatility  text;
    func_body        text;
    bigint_columns   text;
    bad_tz           text;
    bad_default      text;
BEGIN
    -- 7a. The ON CONFLICT arbiter exists, is unique, and is on organizationId
    --     ALONE. If it also covered anything else, the arbiter would refuse to
    --     match for some tenants and every allocation for them would fail.
    SELECT true INTO arbiter_present
      FROM pg_indexes
     WHERE schemaname = 'public'
       AND indexname = 'customer_cx_sequences_organizationId_key'
       AND indexdef LIKE '%UNIQUE INDEX%';

    IF arbiter_present IS DISTINCT FROM true THEN
        RAISE EXCEPTION
            'V20261007100000 failed: customer_cx_sequences_organizationId_key is absent or is not '
            'unique, so ON CONFLICT has no arbiter and two counters per organization would be '
            'possible -- which is exactly the non-atomic allocation this migration exists to '
            'remove.';
    END IF;

    SELECT pg_get_indexdef(indexrelid) INTO arbiter_columns
      FROM pg_index
     WHERE indexrelid = to_regclass('public.customer_cx_sequences_organizationId_key');

    IF arbiter_columns NOT LIKE '%("organizationId")%'
       OR arbiter_columns LIKE '%("lastAllocatedCxSequence")%' THEN
        RAISE EXCEPTION
            'V20261007100000 failed: the arbiter index is "%", which is not on "organizationId" '
            'alone.', coalesce(arbiter_columns, '(absent)');
    END IF;

    -- 7b. The format ceiling is a CONSTRAINT, not a comment, and it is on the
    --     column the allocator increments.
    SELECT pg_get_constraintdef(oid) INTO value_check
      FROM pg_constraint
     WHERE conrelid = to_regclass('public.customer_cx_sequences')
       AND conname = 'customer_cx_sequences_sequence_range_check';

    IF value_check IS NULL
       OR value_check NOT LIKE '%lastAllocatedCxSequence%'
       OR value_check NOT LIKE '%0%'
       OR value_check NOT LIKE '%999999%' THEN
        RAISE EXCEPTION
            'V20261007100000 failed: customer_cx_sequences_sequence_range_check is "%" and does '
            'not pin the sequence to the published 0 .. 999999 format.',
            coalesce(value_check, '(absent)');
    END IF;

    -- 7c. The tenant FK is present. A counter with no organization reference
    --     would let an allocation name a tenant that does not exist.
    -- Structural, not rendered: pg_get_constraintdef() writes
    -- "REFERENCES organizations(id)" or "REFERENCES public.organizations(id)"
    -- depending on the caller's search_path, and the baseline leaves
    -- search_path = '' for the whole connection. Measured on PostgreSQL 16.15
    -- under both settings. See phase-30-migration-chain-bootstrap.md.
    SELECT
        (SELECT array_agg(a.attname::text ORDER BY k.ord)
           FROM unnest(cn.conkey) WITH ORDINALITY AS k(attnum, ord)
           JOIN pg_attribute a ON a.attrelid = cn.conrelid AND a.attnum = k.attnum)
          = ARRAY['organizationId']::text[]
        AND cn.confrelid = 'public.organizations'::regclass
        AND (SELECT array_agg(a.attname::text ORDER BY k.ord)
               FROM unnest(cn.confkey) WITH ORDINALITY AS k(attnum, ord)
               JOIN pg_attribute a ON a.attrelid = cn.confrelid AND a.attnum = k.attnum)
          = ARRAY['id']::text[]
      INTO tenant_fk_shape
      FROM pg_constraint cn
     WHERE cn.conrelid = 'public.customer_cx_sequences'::regclass
       AND cn.conname = 'customer_cx_sequences_organizationId_fkey';
        SELECT pg_get_constraintdef(oid) INTO tenant_fk
          FROM pg_constraint
         WHERE conrelid = to_regclass('public.customer_cx_sequences')
           AND conname = 'customer_cx_sequences_organizationId_fkey';
    
    IF tenant_fk_shape IS DISTINCT FROM true THEN
        RAISE EXCEPTION
            'V20261007100000 failed: customer_cx_sequences_organizationId_fkey is "%".',
            coalesce(tenant_fk, '(absent)');
    END IF;

    -- 7d. The allocator is a REAL VOLATILE FUNCTION of one argument, not a
    --     convention. VOLATILE is the correct marking and is what a planner must
    --     assume: a STABLE or IMMUTABLE marking on an allocator would let the
    --     planner collapse two calls in one statement into one, handing the same
    --     reference to two rows. That is the defect this migration is fixing,
    --     re-entering through the function header.
    SELECT count(*) INTO func_count
      FROM pg_proc p
        JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public'
       AND p.proname = 'firstelement_next_customer_cx_id'
       AND p.pronargs = 1;

    IF func_count <> 1 THEN
        RAISE EXCEPTION
            'V20261007100000 failed: public.firstelement_next_customer_cx_id(uuid) is absent or has '
            'the wrong arity (% found).', func_count;
    END IF;

    SELECT p.provolatile INTO func_volatility
      FROM pg_proc p
        JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public'
       AND p.proname = 'firstelement_next_customer_cx_id'
       AND p.pronargs = 1;

    IF func_volatility <> 'v' THEN
        RAISE EXCEPTION
            'V20261007100000 failed: firstelement_next_customer_cx_id is marked "%" instead of '
            'VOLATILE. An allocator marked STABLE or IMMUTABLE lets a planner treat two calls as '
            'one and hand the same reference to two customers.', func_volatility;
    END IF;

    -- 7e. The allocator is a SINGLE STATEMENT over a single row, and it does not
    --     count rows. Read from the INSTALLED definition rather than from this
    --     file's source, so an amendment elsewhere cannot pass unnoticed.
    SELECT pg_get_functiondef(p.oid) INTO func_body
      FROM pg_proc p
        JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public'
       AND p.proname = 'firstelement_next_customer_cx_id'
       AND p.pronargs = 1;

    IF func_body NOT LIKE '%ON CONFLICT%' THEN
        RAISE EXCEPTION
            'V20261007100000 failed: the installed allocator has no ON CONFLICT clause, so it is '
            'a read-then-write and two concurrent writers can receive the same reference.';
    END IF;

    IF func_body NOT LIKE '%RETURNING%' THEN
        RAISE EXCEPTION
            'V20261007100000 failed: the installed allocator does not RETURN the value it '
            'allocated, so the caller cannot be handed the value the database reserved for it.';
    END IF;

    IF func_body ~* 'count[[:space:]]*\(' THEN
        RAISE EXCEPTION
            'V20261007100000 failed: the installed allocator contains a count(). A reference '
            'derived from a row count is reissued after a deletion, which is the second half of '
            'ADR-0014''s defect.';
    END IF;

    -- 7f. The timestamp contract, asserted the same way V20261006120000 asserts
    --     it: no timestamptz, and no session-local CURRENT_TIMESTAMP default.
    SELECT string_agg(c.relname || '.' || a.attname, ', ') INTO bad_tz
      FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_attribute a ON a.attrelid = c.oid
                              AND a.atttypid = 'timestamptz'::regtype
       WHERE n.nspname = 'public'
         AND c.relname = 'customer_cx_sequences'
         AND a.attnum > 0 AND NOT a.attisdropped;

    IF bad_tz IS NOT NULL THEN
        RAISE EXCEPTION
            'V20261007100000 failed: timestamptz column in %; every timestamp in this schema is '
            'timestamp(3) without time zone.', bad_tz;
    END IF;

    SELECT string_agg(c.relname || '.' || a.attname, ', ') INTO bad_default
      FROM pg_attrdef d
        JOIN pg_class c ON c.oid = d.adrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = d.adnum
       WHERE n.nspname = 'public'
         AND c.relname = 'customer_cx_sequences'
         AND pg_get_expr(d.adbin, d.adrelid) LIKE '%CURRENT_TIMESTAMP%'
         AND pg_get_expr(d.adbin, d.adrelid) NOT LIKE '%AT TIME ZONE%';

    IF bad_default IS NOT NULL THEN
        RAISE EXCEPTION
            'V20261007100000 failed: a session-local CURRENT_TIMESTAMP default on %; every new '
            'default must be (CURRENT_TIMESTAMP AT TIME ZONE ''UTC'').', bad_default;
    END IF;

    -- 7g. There is exactly ONE bigint column, and it is named
    --     lastAllocatedCxSequence. Asserted as a NAMES list rather than by
    --     eyeball, because "a bigint column exists" and "that column is the
    --     high-water mark" are different claims and only the first is visible
    --     from the type. A second bigint would be the one place a future author
    --     could put a count and quietly reintroduce this defect.
    SELECT string_agg(a.attname, ', ' ORDER BY a.attnum) INTO bigint_columns
      FROM pg_attribute a
     WHERE a.attrelid = to_regclass('public.customer_cx_sequences')
       AND a.attnum > 0 AND NOT a.attisdropped
       AND format_type(a.atttypid, a.atttypmod) = 'bigint';

    IF bigint_columns IS DISTINCT FROM 'lastAllocatedCxSequence' THEN
        RAISE EXCEPTION
            'V20261007100000 failed: the bigint column(s) on customer_cx_sequences are "%". There '
            'is to be exactly one, named lastAllocatedCxSequence, so that its meaning cannot be '
            'mistaken for a count or for a next-value.',
            coalesce(bigint_columns, '(none)');
    END IF;

    RAISE NOTICE
        'V20261007100000: customer_cx_sequences created. Verified in pg_catalog -- a unique '
        'arbiter on "organizationId" alone; the sequence pinned to 0 .. 999999 by CHECK; the '
        'tenant FK present; firstelement_next_customer_cx_id VOLATILE, with ON CONFLICT and '
        'RETURNING and no count(); exactly one bigint column, named for what it holds. '
        'No timestamptz, no session-local default.';
END
$$;
