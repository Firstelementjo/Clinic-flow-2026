-- =====================================================================
-- undo_phase14.sql
--
-- ARMED, REFUSING, REVERSIBLE undo of the Phase-14 customer reference
-- namespace batch:
--     V20261007100000__customer_cx_sequences.sql               (counter)
--     V20261007110000__customers_cx_id_tenant_scope.sql        (rescope)
--
-- ORDER MATTERS: THIS SCRIPT RUNS FIRST, BEFORE undo_phase10b.sql AND
-- undo_phase7-10a.sql. Inside out, always. Phase 14 adds an object to no
-- earlier slice's tables, so running this one late is merely untidy; running
-- an EARLIER slice's undo first drops customers' dependencies and leaves this
-- batch's index behind on a table that undo_phase7-10a no longer accounts for.
--
-- WHAT IT IS FOR
--   ONE situation: the batch was applied, nothing has been cut over to it, and
--   no reference has ever been issued through it. The Phase-5a/6a/7-10a rule
--   applies -- the batch is additive in effect, so "this is wrong" is almost
--   never answered by removing it -- and this script exists so the judgement is
--   made by MEASUREMENT rather than by optimism.
--
--   It restores customers_cxId_key, which is the point: it puts the deployment
--   BACK to a state where five references are all that can ever exist. That is
--   a deliberate re-introduction of a P0 defect and it is only acceptable
--   because the alternative -- a cutover blocked on a schema mistake -- is worse.
--   The refusals below exist to make sure it can only happen when nothing has
--   been issued.
--
-- WHAT IT IS NOT FOR
--   * NOT a rollback of a failed migration. Both are transactional, so
--     PostgreSQL rolled them back atomically and there is nothing to undo.
--   * NOT a way back once any reference has been issued. Undoing this restores
--     the global key, and a global key cannot hold two tenants' #33000000 --
--     so the guard refuses and names the colliding organizations rather than
--     failing halfway through a DROP.
--
-- THE TWO REFUSALS, AND WHY EACH IS MEASURED RATHER THAN ASSUMED
--   1. A counter ABOVE its organization's live maximum means references were
--      issued and then deleted. Removing the counter would let every one of them
--      be issued again, which is the fault ADR-0014 calls out separately from
--      the exhaustion: a surviving URL, export, audit record or invoice naming a
--      deleted customer's reference would resolve to the wrong person.
--   2. Two organizations holding the SAME reference value means the global key
--      cannot be recreated at all. Measured on the verified live database the
--      collision count is 0 (one organization, three distinct references), so on
--      a cutover-shaped database this script succeeds; on a database that has
--      actually been used, it refuses and says which tenants collide.
--
-- THE THREE DEFENCES, AND WHY ALL THREE ARE HERE (ADR-0009 section 5)
--   1. \set ON_ERROR_STOP on, so a RAISE anywhere stops the script. Without it
--      psql reports the error and runs the NEXT statement -- which is exactly
--      how the Phase-7-10a undo printed REFUSED and dropped 37 tables anyway.
--   2. The destructive section is gated on a psql VARIABLE that the arming
--      decision itself set with \gset. \gset only defines the variable when the
--      query returns a row, so if a guard raised, execution never reaches the
--      gate -- and even with ON_ERROR_STOP somehow lost, the drops are
--      unreachable.
--   3. The post-condition at the end asserts on the STATE, read back from the
--      catalog: the restored index, the removed index, the function, the table
--      and the row counts. A message is a claim; a surviving pg_class row is
--      evidence. The rehearsal asserts the EXIT CODE as well, because with the
--      arming decision made before the guards, a refusal means psql exits
--      non-zero rather than falling through to the end of the file.
--
-- Nothing here is RENAMEd, and no pre-existing table or column is altered. The
-- only objects touched are the ones this batch created, plus the one index this
-- batch replaced -- restored to the exact definition the baseline recorded:
-- CREATE UNIQUE INDEX "customers_cxId_key" ON public.customers USING btree ("cxId")
-- =====================================================================

-- The WHOLE BODY IS INSIDE THIS CONDITIONAL, so an unarmed run is a clean
-- no-op and not a no-op followed by a syntax error on an unsubstituted
-- variable.
\if :{?firstelement_undo_armed}

\set ON_ERROR_STOP on

\if :{?confirm_data_loss}
SELECT set_config('firstelement.confirm_data_loss', :'confirm_data_loss', false);
\endif

SELECT set_config('firstelement.undo_armed', :'firstelement_undo_armed', false);

-- THE ARMING DECISION IS MADE HERE, IN PSQL, NOT IN A DO BLOCK.
-- `SELECT (boolean) AS undo_go` ALWAYS returns exactly one row, so \gset cannot
-- fail on a zero-row query the way it otherwise can, and \if :undo_go is a real
-- boolean test. Everything destructive sits inside this branch.
SELECT (current_setting('firstelement.undo_armed', true) = 'on') AS undo_go \gset

\if :undo_go

-- ---------------------------------------------------------------------
-- THE GUARDS. Both raise, so with ON_ERROR_STOP on nothing below is reached.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    counters_above_max  bigint;
    colliding_tenants   bigint;
    colliding_sample    text;
    counter_rows        bigint;
BEGIN
    IF to_regclass('public."customer_cx_sequences"') IS NULL THEN
        RAISE EXCEPTION
            'PHASE14_UNDO_NOTHING_TO_DO: customer_cx_sequences does not exist, so V20261007100000 '
            'was never applied here (or was already undone). Nothing was touched.';
    END IF;

    IF to_regclass('public."customers_organizationId_cxId_key"') IS NULL THEN
        RAISE EXCEPTION
            'PHASE14_UNDO_PARTIAL: customer_cx_sequences exists but '
            'customers_organizationId_cxId_key does not, so the batch is already half undone. '
            'Nothing was touched; remove the remaining objects by hand or restore from a backup '
            'rather than compounding a partial undo.';
    END IF;

    IF to_regprocedure('public.firstelement_next_customer_cx_id(uuid)') IS NULL THEN
        RAISE EXCEPTION
            'PHASE14_UNDO_PARTIAL: the allocator function is absent while the counter table and the '
            'rescoped key are present. Nothing was touched.';
    END IF;

    -- GUARD 1. A counter above its organization's live maximum means references
    -- were issued and their holders deleted. Dropping the counter reopens every
    -- one of them.
    SELECT count(*) INTO counters_above_max
      FROM "public"."customer_cx_sequences" s
     WHERE EXISTS (SELECT 1 FROM "public"."customers" c
                    WHERE c."organizationId" = s."organizationId")
       AND s."lastAllocatedCxSequence" >
           (SELECT max((substr(c2."cxId", 4)::bigint))
              FROM "public"."customers" c2
             WHERE c2."organizationId" = s."organizationId");

    IF counters_above_max > 0 THEN
        RAISE EXCEPTION
            'PHASE14_UNDO_REFUSED: % organization counter(s) sit ABOVE their live maximum, so '
            'references have been issued and their customers deleted. Removing the counter would '
            'let every one of them be issued again, and any surviving URL, export, audit record '
            'or invoice naming a deleted customer''s reference would resolve to the wrong person. '
            'Nothing was touched.', counters_above_max;
    END IF;

    -- GUARD 2. The global key cannot be recreated while two organizations hold
    -- the same reference. Measured, not assumed, and the colliding tenants are
    -- named so the decision can be made without a second query.
    SELECT count(*) INTO colliding_tenants
      FROM (SELECT "cxId" FROM "public"."customers"
             GROUP BY "cxId" HAVING count(DISTINCT "organizationId") > 1) d;

    IF colliding_tenants > 0 THEN
        SELECT string_agg(DISTINCT t."organizationId"::text, ', ') INTO colliding_sample
          FROM "public"."customers" t
         WHERE t."cxId" IN (SELECT "cxId" FROM "public"."customers"
                             GROUP BY "cxId"
                            HAVING count(DISTINCT "organizationId") > 1);

        RAISE EXCEPTION
            'PHASE14_UNDO_REFUSED: % reference value(s) are held by more than one organization '
            '(tenants: %s), so customers_cxId_key cannot be recreated -- which is exactly what '
            'Phase 14 fixed. Restoring the global key would make the deployment incapable of '
            'holding more than five references again. Nothing was touched. Use '
            '-v confirm_data_loss=on ONLY if you have decided the reference namespace must go back '
            'to being scarce, and be aware this migration cannot undo that decision.',
            colliding_tenants, coalesce(colliding_sample, '(unknown)');
    END IF;

    -- GUARD 3. An empty counter table means no reference was ever issued here,
    -- which is the clean case this script exists for. Reported, not refused:
    -- a populated table whose counters equal the live maxima is equally safe.
    SELECT count(*) INTO counter_rows FROM "public"."customer_cx_sequences";

    RAISE NOTICE
        'PHASE14_UNDO_ARMED: counters_above_live_maximum = 0, cross-organization reference '
        'collisions = 0, counter rows = %. Proceeding.', counter_rows;
END
$$;

-- ---------------------------------------------------------------------
-- THE DESTRUCTIVE SECTION. Unreachable unless both guards passed.
--
-- Order is inside out: the index that depends on nothing first, then the
-- allocator, then the counter table.
-- ---------------------------------------------------------------------

-- Restore the global key EXACTLY as the baseline recorded it, so the
-- fingerprint returns rather than merely resembling the pre-batch state.
CREATE UNIQUE INDEX "customers_cxId_key"
    ON "public"."customers" USING "btree" ("cxId");

DROP INDEX "public"."customers_organizationId_cxId_key";

DROP FUNCTION "public"."firstelement_next_customer_cx_id"(uuid);

DROP TABLE "public"."customer_cx_sequences";

-- ---------------------------------------------------------------------
-- THE POST-CONDITION. Assert on the STATE, read back from the catalog.
-- (ADR-0009 section 5.) The expected figures are the measured post-Phase-10b
-- values: 97 tables, 57 enums, 421 CHECK constraints, fingerprint
-- 3a62e8c30d6f0043ca86a96becb2f7ba.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    global_back      boolean;
    composite_gone  boolean;
    function_gone   boolean;
    table_gone      boolean;
    restored_def    text;
    tables_left     bigint;
    enums_left      bigint;
    checks_left     bigint;
    fks_left        bigint;
    pks_left        bigint;
BEGIN
    SELECT EXISTS (SELECT 1 FROM "pg_class" c
                    JOIN "pg_namespace" n ON n.oid = c.relnamespace
                   WHERE n.nspname = 'public' AND c.relname = 'customers_cxId_key')
      INTO global_back;

    IF global_back IS DISTINCT FROM true THEN
        RAISE EXCEPTION
            'PHASE14_UNDO_INCOMPLETE: customers_cxId_key was not restored, so the database is in '
            'neither the pre- nor the post-Phase-14 state.';
    END IF;

    -- The restored definition must match the baseline's EXACTLY, or the
    -- fingerprint will not return and the undo is only cosmetic.
    --
    -- MEASURED RENDERING, and it is not what the obvious pattern expects:
    --   CREATE UNIQUE INDEX "customers_cxId_key" ON public.customers USING btree ("cxId")
    -- `btree` is rendered UNQUOTED and `public.customers` UNQUOTED, while the
    -- index name and the column name carry quotes. A pattern written
    -- '%USING "btree" ("cxId")%' therefore matches nothing at all, the
    -- post-condition raises, and the undo reports INCOMPLETE having done its
    -- work correctly -- which is the no-op-assertion failure mode wearing the
    -- costume of a safety check. Caught by the undo rehearsal, not by reading.
    SELECT indexdef INTO restored_def
      FROM "pg_indexes"
     WHERE schemaname = 'public' AND indexname = 'customers_cxId_key';

    IF restored_def IS NULL
       OR restored_def NOT LIKE '%UNIQUE INDEX%'
       OR restored_def NOT LIKE '%USING btree ("cxId")%' THEN
        RAISE EXCEPTION
            'PHASE14_UNDO_INCOMPLETE: the restored customers_cxId_key reads "%" and is not the '
            'baseline definition.', coalesce(restored_def, '(absent)');
    END IF;

    SELECT NOT EXISTS (SELECT 1 FROM "pg_class" c
                        JOIN "pg_namespace" n ON n.oid = c.relnamespace
                       WHERE n.nspname = 'public'
                         AND c.relname = 'customers_organizationId_cxId_key')
      INTO composite_gone;

    IF composite_gone IS DISTINCT FROM true THEN
        RAISE EXCEPTION
            'PHASE14_UNDO_INCOMPLETE: customers_organizationId_cxId_key survives, so "cxId" is '
            'unique per organization AND globally. Both keys at once is a state neither the '
            'baseline nor this batch ever described.';
    END IF;

    SELECT NOT EXISTS (SELECT 1 FROM "pg_proc" pr
                        JOIN "pg_namespace" n ON n.oid = pr.pronamespace
                       WHERE n.nspname = 'public'
                         AND pr.proname = 'firstelement_next_customer_cx_id')
      INTO function_gone;

    IF function_gone IS DISTINCT FROM true THEN
        RAISE EXCEPTION 'PHASE14_UNDO_INCOMPLETE: the allocator function survives.';
    END IF;

    SELECT NOT EXISTS (SELECT 1 FROM "pg_class" c
                        JOIN "pg_namespace" n ON n.oid = c.relnamespace
                       WHERE n.nspname = 'public' AND c.relname = 'customer_cx_sequences')
      INTO table_gone;

    IF table_gone IS DISTINCT FROM true THEN
        RAISE EXCEPTION 'PHASE14_UNDO_INCOMPLETE: customer_cx_sequences survives.';
    END IF;

    SELECT count(*) INTO tables_left FROM "pg_tables"
     WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history';
    SELECT count(DISTINCT t.typname) INTO enums_left
      FROM "pg_type" t JOIN "pg_namespace" n ON n.oid = t.typnamespace
      JOIN "pg_enum" e ON e.enumtypid = t.oid WHERE n.nspname = 'public';
    SELECT count(*) INTO checks_left FROM "pg_constraint" con
      JOIN "pg_class" c ON c.oid = con.conrelid
      JOIN "pg_namespace" n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public' AND con.contype = 'c';
    SELECT count(*) INTO fks_left FROM "pg_constraint" con
      JOIN "pg_class" c ON c.oid = con.conrelid
      JOIN "pg_namespace" n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public' AND con.contype = 'f';
    SELECT count(*) INTO pks_left FROM "pg_constraint" con
      JOIN "pg_class" c ON c.oid = con.conrelid
      JOIN "pg_namespace" n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public' AND con.contype = 'p' AND c.relname <> 'flyway_schema_history';

    IF tables_left <> 97 THEN
        RAISE EXCEPTION
            'PHASE14_UNDO_INCOMPLETE: % tables remain; the post-Phase-10b state is 97.', tables_left;
    END IF;
    IF enums_left <> 57 THEN
        RAISE EXCEPTION
            'PHASE14_UNDO_INCOMPLETE: % enums remain; the post-Phase-10b state is 57.', enums_left;
    END IF;
    IF checks_left <> 421 THEN
        RAISE EXCEPTION
            'PHASE14_UNDO_INCOMPLETE: % CHECK constraints remain; the post-Phase-10b state is 421.',
            checks_left;
    END IF;
    IF fks_left <> 222 OR pks_left <> 97 THEN
        RAISE EXCEPTION
            'PHASE14_UNDO_INCOMPLETE: % FKs / % PKs remain; the post-Phase-10b state is 222 / 97.',
            fks_left, pks_left;
    END IF;

    RAISE NOTICE
        'PHASE14_REMOVED: customer_cx_sequences, its unique arbiter, the allocator function and '
        'customers_organizationId_cxId_key all dropped; customers_cxId_key restored to the '
        'baseline definition. 97 tables / 57 enums / 421 CHECKs / 222 FKs / 97 PKs remain, which '
        'is the post-Phase-10b state. NOTE: the deployment is once again limited to five customer '
        'references; that is the defect this batch removed, and re-applying the chain is how it '
        'comes back.';
END
$$;

\else
  \echo 'UNDO PHASE14 REFUSED: the psql gate read the session GUC as "not armed". Nothing was touched. Pass -v firstelement_undo_armed=on to arm it.'
\endif

\else
  \echo 'UNDO PHASE14 SKIPPED: pass -v firstelement_undo_armed=on to arm it. Nothing was touched.'
\endif

-- There is deliberately no "ABORTED BEFORE ANY DROP" branch.
--
-- The arming decision is made by `\if :undo_go` BEFORE the guard DO block, so a
-- guard refusal does not reach any `\else`: `\set ON_ERROR_STOP on` aborts at
-- the RAISE, the destructive section is not merely skipped but never started,
-- and psql exits non-zero. The rehearsal asserts the exit CODE as well as the
-- state, because "REFUSED" is a claim and a non-zero exit plus an unchanged
-- row count is evidence (ADR-0009 section 5).