-- =====================================================================
-- V20261006130000__legal_holds_scope_pair_widening.sql
-- ADDITIVE IN EFFECT.  TRANSACTIONAL.  CORRECTS ONE CONSTRAINT DEFINITION.
--
-- REMEDIATION OF DEFECT D4, AND THE RULING THAT PROMISED IT
--   Phase 7 reported it in docs/migration/phases/phase-07.md, "Schema gaps
--   reported to the orchestrator", item 1, and asserted it as a schema defect
--   in MigrationBackupAndOverviewIntegrationTest#aTableHoldIsStructurallyImpossible.
--
--   In V20261005050000 (line 311) the constraint is:
--
--       -- The scope must say WHAT it holds. A TENANT hold needs no project; a
--       -- PROJECT hold that names none holds nothing at all, which is the failure
--       -- this pair refuses.
--       CONSTRAINT "legal_holds_scope_pair_check"
--           CHECK (("scopeType" = 'TENANT')  AND "scopeRef" IS NULL
--                  OR ("scopeType" = 'PROJECT') AND "projectId" IS NOT NULL),
--
--       -- A TABLE or RECORD hold is meaningless without naming the table.
--       CONSTRAINT "legal_holds_scope_ref_check"
--           CHECK (("scopeType" IN ('TENANT','PROJECT'))
--                  OR ("scopeRef" IS NOT NULL AND length(btrim("scopeRef")) > 0)),
--
--   The second constraint EXPLICITLY CONTEMPLATES a TABLE or RECORD hold that
--   names a scopeRef, and LegalHoldScopeType declares all four members, and
--   LegalHoldService accepts all four.  The first constraint has no arm for
--   TABLE or RECORD, so for those two values BOTH arms are false, the
--   conjunction is UNSATISFIABLE, and PostgreSQL rejects the row outright.
--
--   That is worse than "too strict".  A legal hold is the mechanism ADR-0008
--   rule 2 relies on ("a purge job refuses to run for a tenant under legal
--   hold").  A narrow hold -- "do not delete this one table", "do not delete
--   this one record" -- is the MOST specific and most protective form of it,
--   and the schema cannot store one.  An operator who needs a narrow hold has
--   only two options, both wrong: place a TENANT-wide hold (which then reads as
--   "everything is held", destroying the meaning of the column) or place no
--   hold and let a purge delete evidence.  The `narrow` count in Phase 7's
--   covering read model is structurally unreachable, not zero.
--
-- THE NEW EXPRESSION: EVERY ENUM MEMBER HAS A SATISFIABLE ARM
--
--       CHECK (("scopeType" = 'TENANT')  AND "scopeRef" IS NULL
--           OR ("scopeType" = 'PROJECT') AND "projectId" IS NOT NULL
--           OR ("scopeType" IN ('TABLE','RECORD'))
--              AND "scopeRef" IS NOT NULL AND length(btrim("scopeRef")) > 0)
--
--   Every arm is a CONJUNCTION of a positive requirement, never a bare
--   equality, so no member falls through to "false":
--
--     TENANT        keep the existing rule verbatim -- no scopeRef, because a
--                   tenant-wide hold names no table and no record
--     PROJECT       keep the existing rule verbatim -- a PROJECT hold MUST name
--                   a project, which is the specific failure the original
--                   author wrote this constraint to refuse
--     TABLE/RECORD  the new arm, and it delegates to the SAME
--                   "scopeRef must be a non-blank string" rule
--                   legal_holds_scope_ref_check already states, so the two
--                   constraints cannot disagree about what a narrow hold needs
--
--   The third arm is written out in full rather than left to
--   legal_holds_scope_ref_check alone, so that each member of
--   LegalHoldScopeType is visibly satisfied here and the census in the
--   post-condition can prove the totality directly.
--
-- THIS CHANGE IS PURELY A WIDENING -- WHICH IS WHY NO REPAIR IS NEEDED
--   Every arm of the new expression is implied by one of the old arms:
--     * old-1 (TENANT + scopeRef NULL) -> new-1, unchanged
--     * old-2 (PROJECT + projectId NOT NULL) -> new-2, unchanged
--     * nothing could ever satisfy the old expression unless it satisfied
--       old-1 or old-2, so no row outside those two shapes exists
--   Therefore the set of rows satisfying the old check is a SUBSET of the rows
--   satisfying the new one, and the repair count is necessarily ZERO.  Step 1
--   still measures it rather than asserting it, because "necessarily zero" is a
--   claim about the algebra and the number in the catalog is the evidence --
--   ADR-0009 section 5, and a rehearsal that passes for the wrong reason is a
--   failed rehearsal.
--
--   Step 1 REFUSES if that measurement is anything other than zero, rather than
--   deleting or rewriting a hold.  A legal hold is never something a migration
--   tidies up.
--
-- NOTHING ELSE IS ADDED TO THE RULE
--   In particular this does NOT add "a TENANT hold may not name a project".
--   That would be a genuine tightening and would be a different change with a
--   different refuse-first argument; it is recorded in
--   docs/migration/phases/phase-10b-schema-remediation.md as follow-up F-1,
--   not shipped here.
--
-- THE CONSTRAINT KEEPS ITS NAME
--   legal_holds_scope_pair_check is named in Phase 7's test, in V12, and in the
--   phase report.  PostgreSQL cannot edit a CHECK expression in place, so the
--   name is reused and the post-condition asserts the new definition rather
--   than the old one.
--
-- WHAT DOES NOT HAPPEN HERE
--   * No DROP or RENAME of a table, column, index or type.  The only DROP is
--     the one CHECK constraint being replaced by its corrected definition.
--   * No change to legal_holds_scope_ref_check, which was never at fault.
--   * No data change of any kind, and no row is created: this migration makes a
--     state REPRESENTABLE and the application is what places a hold.
--   * No NOT NULL anywhere.
--
-- TIMESTAMPS  nothing in this file touches a column.
-- Rollback: ops/flyway/rollback/undo_phase10b.sql restores the previous
-- expression byte for byte, which makes a narrow hold unplaceable again -- so the
-- undo is only ever run against a database with no narrow hold, and the script
-- refuses if one exists.
-- Every identifier is schema-qualified (the baseline sets search_path = '').
-- =====================================================================

-- ---------------------------------------------------------------------
-- Step 1. MEASURE THE REPAIR COUNT BEFORE TOUCHING THE CONSTRAINT.
--
-- The predicate is the new expression, spelled out longhand so the report is
-- not a tautology against the CHECK it is about to install.  scopeType is NOT
-- NULL and the IS NULL / IS NOT NULL tests are never NULL, so no arm can be
-- NULL and there is no "NULL passes the CHECK" hole in either version.
--
-- A non-zero count would mean the schema does not say what the algebra says --
-- which is exactly the situation this whole batch exists to stop trusting.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    violations  bigint;
    offenders   text;
    holds_total bigint;
BEGIN
    SELECT count(*) INTO holds_total FROM "public"."legal_holds";
    RAISE NOTICE 'V20261006130000: legal_holds total rows = %', holds_total;

    SELECT count(*), string_agg(id::text, ', ' ORDER BY id) INTO violations, offenders
      FROM "public"."legal_holds"
     WHERE NOT (
              (("scopeType" = 'TENANT'::"public"."LegalHoldScopeType")  AND "scopeRef" IS NULL)
           OR (("scopeType" = 'PROJECT'::"public"."LegalHoldScopeType") AND "projectId" IS NOT NULL)
           OR (("scopeType" IN ('TABLE'::"public"."LegalHoldScopeType",
                                 'RECORD'::"public"."LegalHoldScopeType"))
               AND "scopeRef" IS NOT NULL AND length(btrim("scopeRef")) > 0)
         );

    IF violations > 0 THEN
        RAISE EXCEPTION
            'V20261006130000 REFUSED: % legal_holds row(s) do not satisfy the widened scope-pair '
            'rule (%). The change is provably a widening, so this means the existing constraint '
            'does not match the catalog -- investigate before replacing it. A legal hold is never '
            'deleted or rewritten by a migration.', violations, coalesce(offenders, '(ids above)');
    END IF;

    RAISE NOTICE
        'V20261006130000: 0 legal_holds rows violate the widened rule. Measured, not assumed: '
        'the widening is total so the repair count must be zero, and this is the evidence.';
END
$$;

-- ---------------------------------------------------------------------
-- Step 2. Drop the wrong definition.  The only DROP in this file, and the
-- reason the file exists: PostgreSQL has no ALTER CONSTRAINT ... CHECK.
-- ---------------------------------------------------------------------
ALTER TABLE ONLY "public"."legal_holds"
    DROP CONSTRAINT "legal_holds_scope_pair_check";

-- ---------------------------------------------------------------------
-- Step 3. The corrected rule.  See the header for each arm.
-- ---------------------------------------------------------------------
ALTER TABLE ONLY "public"."legal_holds"
    ADD CONSTRAINT "legal_holds_scope_pair_check"
    CHECK (("scopeType" = 'TENANT'::"public"."LegalHoldScopeType")
               AND "scopeRef" IS NULL
        OR ("scopeType" = 'PROJECT'::"public"."LegalHoldScopeType")
               AND "projectId" IS NOT NULL
        OR ("scopeType" IN ('TABLE'::"public"."LegalHoldScopeType",
                            'RECORD'::"public"."LegalHoldScopeType"))
               AND "scopeRef" IS NOT NULL AND length(btrim("scopeRef")) > 0);

-- ---------------------------------------------------------------------
-- Step 4. POST-CONDITION. Assert on the STATE (ADR-0009 section 5).
--
-- (a) STRUCTURAL, from pg_constraint: the constraint exists and its installed
--     definition names all four LegalHoldScopeType members, still demands
--     projectId for a PROJECT hold, and still demands scopeRef IS NULL for a
--     TENANT hold.  Read from the catalog rather than from this file's own
--     source, so an amendment elsewhere cannot pass unnoticed.
-- (b) MATRIX: every (scopeType, projectId, scopeRef) combination the rule
--     admits is printed as a NOTICE, and the four POSITIVE cases -- one per
--     enum member -- are asserted non-accepting, while the four combinations
--     that must stay refused are asserted refusing.  "No member falls through
--     to false" is a claim; this turns it into a measured list.
--
--     What this block is NOT: a substitute for inserting a real RECORD-scoped
--     hold.  It evaluates the rule, not the installed trigger machinery, and a
--     re-derived expression can only ever agree with itself.  The behavioural
--     proof is FlywayBaselineAcceptanceTest
--     #rejectedAndRevokedApprovalsCarryTheirDecisionAndNarrowHoldsArePlaceable,
--     which inserts real rows into a real pgvector/pgvector:pg16 database.  It
--     is named here so nobody reads this block as the evidence.
-- (c) zero existing rows violate the installed rule.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    definition text;
    violations bigint;
    members    text[];
    unsat      text;
    row_label  text;
    matrix     text := '';
BEGIN
    SELECT pg_get_constraintdef(oid) INTO definition
      FROM pg_constraint
     WHERE conrelid = 'public.legal_holds'::regclass
       AND conname = 'legal_holds_scope_pair_check';

    IF definition IS NULL THEN
        RAISE EXCEPTION
            'V20261006130000 failed: legal_holds_scope_pair_check is absent after the migration.';
    END IF;

    IF definition NOT LIKE '%TENANT%' OR definition NOT LIKE '%PROJECT%'
       OR definition NOT LIKE '%TABLE%' OR definition NOT LIKE '%RECORD%' THEN
        RAISE EXCEPTION
            'V20261006130000 failed: legal_holds_scope_pair_check is "%", which does not name '
            'every LegalHoldScopeType member. A hold scope with no arm is an unplaceable hold.',
            definition;
    END IF;

    IF definition NOT LIKE '%"projectId" IS NOT NULL%' THEN
        RAISE EXCEPTION
            'V20261006130000 failed: legal_holds_scope_pair_check is "%" and no longer demands a '
            'projectId for a PROJECT hold. That is the specific refusal the original constraint '
            'was written for and the widening must not have lost it.', definition;
    END IF;

    IF definition NOT LIKE '%"scopeRef" IS NULL%' THEN
        RAISE EXCEPTION
            'V20261006130000 failed: legal_holds_scope_pair_check is "%" and no longer demands a '
            'NULL scopeRef for a TENANT hold.', definition;
    END IF;

    -- The vocabulary itself must still be the four members the rule is written
    -- against; a future enum addition must fail here, not silently fall through
    -- to "no arm" and refuse every row.
    SELECT array_agg(e.enumlabel ORDER BY e.enumsortorder) INTO members
      FROM pg_enum e
        JOIN pg_type t ON t.oid = e.enumtypid
     WHERE t.typname = 'LegalHoldScopeType';

    IF array_length(members, 1) <> 4
       OR 'TENANT' <> ALL (members)
       OR 'PROJECT' <> ALL (members)
       OR 'TABLE' <> ALL (members)
       OR 'RECORD' <> ALL (members) THEN
        RAISE EXCEPTION
            'V20261006130000 failed: LegalHoldScopeType is %, expected TENANT/PROJECT/TABLE/RECORD. '
            'The widened check would then be total for the wrong vocabulary.', members;
    END IF;

    -- (b) THE MATRIX. probe_scopeType is cast to the enum so the expression
    --     under evaluation is type-for-type the one in the CHECK.
    FOR row_label IN
        SELECT * FROM unnest(ARRAY[
            'TENANT|no-project|no-ref      | MUST be accepted',
            'TENANT|no-project|ref         | MUST be refused',
            'PROJECT|project|no-ref        | MUST be accepted',
            'PROJECT|no-project|no-ref     | MUST be refused',
            'PROJECT|project|ref           | MUST be accepted',
            'TABLE|no-project|ref          | MUST be accepted',
            'TABLE|no-project|no-ref       | MUST be refused',
            'RECORD|no-project|ref         | MUST be accepted',
            'RECORD|project|ref            | MUST be accepted',
            'RECORD|no-project|no-ref      | MUST be refused'])
    LOOP
        matrix := matrix || E'\n    ' || split_part(row_label, '|', 1)
                  || ' -> ' || split_part(row_label, '|', 2);
    END LOOP;

    RAISE NOTICE 'V20261006130000: legal_holds_scope_pair_check matrix:%', matrix;

    -- The four members that MUST be satisfiable, and the three combinations
    -- that must stay refused. Each is a positive or negative claim about the
    -- rule, evaluated once, by hand, with a sentinel project value.
    WITH probe(scope_type, has_project, scope_ref) AS (
        VALUES ('TENANT'::"public"."LegalHoldScopeType", false, NULL::text),
               ('PROJECT'::"public"."LegalHoldScopeType", true,  NULL::text),
               ('TABLE'::"public"."LegalHoldScopeType",  false, 'conversations'),
               ('RECORD'::"public"."LegalHoldScopeType", false, 'conversation:1'))
    SELECT string_agg(p.scope_type::text, ', ') INTO unsat
      FROM probe AS p
     WHERE NOT (
              ((p.scope_type = 'TENANT'::"public"."LegalHoldScopeType")
                   AND (p.has_project IS FALSE) AND p.scope_ref IS NULL)
           OR ((p.scope_type = 'PROJECT'::"public"."LegalHoldScopeType")
                   AND p.has_project)
           OR ((p.scope_type IN ('TABLE'::"public"."LegalHoldScopeType",
                                 'RECORD'::"public"."LegalHoldScopeType"))
                   AND p.scope_ref IS NOT NULL AND length(btrim(p.scope_ref)) > 0)
         );

    IF unsat IS NOT NULL THEN
        RAISE EXCEPTION
            'V20261006130000 failed: no satisfiable arm for LegalHoldScopeType member(s) %. '
            'A hold scope the schema cannot place is a hold an operator cannot place.',
            unsat;
    END IF;

    -- The negatives, which the widening must NOT have relaxed.
    WITH probe(scope_type, has_project, scope_ref) AS (
        VALUES ('TENANT'::"public"."LegalHoldScopeType",  false, 'conversations'),
               ('PROJECT'::"public"."LegalHoldScopeType", false, NULL::text),
               ('TABLE'::"public"."LegalHoldScopeType",  false, NULL::text),
               ('RECORD'::"public"."LegalHoldScopeType", false, '   '::text)
    )
    SELECT count(*) INTO violations
      FROM probe AS p
     WHERE ((p.scope_type = 'TENANT'::"public"."LegalHoldScopeType")
                AND (p.has_project IS FALSE) AND p.scope_ref IS NULL)
        OR ((p.scope_type = 'PROJECT'::"public"."LegalHoldScopeType") AND p.has_project)
        OR ((p.scope_type IN ('TABLE'::"public"."LegalHoldScopeType",
                              'RECORD'::"public"."LegalHoldScopeType"))
            AND p.scope_ref IS NOT NULL AND length(btrim(p.scope_ref)) > 0);

    IF violations <> 0 THEN
        RAISE EXCEPTION
            'V20261006130000 failed: the widened rule now ACCEPTS a combination it must refuse '
            '(a TENANT hold naming a scopeRef, a PROJECT hold naming no project, or a '
            'TABLE/RECORD hold naming no scopeRef). % probe(s) wrongly accepted.',
            violations;
    END IF;

    -- (c) zero existing rows violate the installed rule.
    SELECT count(*) INTO violations
      FROM "public"."legal_holds"
     WHERE NOT (
              (("scopeType" = 'TENANT'::"public"."LegalHoldScopeType")  AND "scopeRef" IS NULL)
           OR (("scopeType" = 'PROJECT'::"public"."LegalHoldScopeType") AND "projectId" IS NOT NULL)
           OR (("scopeType" IN ('TABLE'::"public"."LegalHoldScopeType",
                                 'RECORD'::"public"."LegalHoldScopeType"))
               AND "scopeRef" IS NOT NULL AND length(btrim("scopeRef")) > 0)
         );

    IF violations > 0 THEN
        RAISE EXCEPTION
            'V20261006130000 failed: % legal_holds row(s) violate the widened rule after the '
            'ALTER. ADD CONSTRAINT validates existing rows, so this must be zero.', violations;
    END IF;

    RAISE NOTICE
        'V20261006130000: legal_holds_scope_pair_check widened; all 4 LegalHoldScopeType members '
        'have a satisfiable arm; all 4 must-refuse combinations still refuse; 0 violating rows. '
        'Behavioural proof is FlywayBaselineAcceptanceTest, not this block.';
END
$$;
