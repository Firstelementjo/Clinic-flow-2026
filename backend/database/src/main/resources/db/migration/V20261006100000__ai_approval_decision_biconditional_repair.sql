-- =====================================================================
-- V20261006100000__ai_approval_decision_biconditional_repair.sql
-- ADDITIVE IN EFFECT.  TRANSACTIONAL.  CORRECTS ONE CONSTRAINT DEFINITION.
--
-- REMEDIATION OF DEFECT D1, AND THE RULING THAT PROMISED IT
--   ADR-0011 section 1: "a chain-owner migration corrects this. It is not
--   optional cleanup; the constraint currently makes two documented
--   transitions unstorable."  The comment immediately above the constraint
--   in V20261005100000 says:
--
--       -- The decision pair, and it is a BICONDITIONAL: APPROVED and REJECTED
--       -- each say who and when, and nothing else carries a decision.
--       CONSTRAINT "ai_approvals_decision_pair_check"
--           CHECK (("decidedAt" IS NULL) = ("decidedByUserId" IS NULL)),
--       CONSTRAINT "ai_approvals_approval_check"
--           CHECK (("status" = 'APPROVED') = ("decidedAt" IS NOT NULL)),
--
--   The comment names REJECTED; the body does not admit it.  The three
--   consequences are all real, and all measured:
--
--     1. A REJECTED approval that names who refused it and when is REFUSED by
--        the database.  The only REJECTED row the old rule admits is one that
--        hides its decider.
--     2. ai_approvals_rejection_check DEMANDS a decisionNote on every REJECTED
--        row.  So the schema demands a decision record and forbids it.
--     3. Phase 10's AiApprovalService.reject() and .revoke() both write
--        "decidedAt" = null with a comment saying the constraint forces it
--        (AiApprovalService.java lines 264-267 and 303).  Code contorted
--        around a schema defect is how a schema defect survives, and ADR-0011
--        section 1 rule 4 records that those two paths are restored after this
--        migration lands.  This file is that landing.
--
-- THE NEW EXPRESSION, AND THE ONE PLACE IT DEVIATES FROM ADR-0011
--   ADR-0011 section 1 states the required form as:
--
--       CHECK (("status" IN ('APPROVED','REJECTED')) = ("decidedAt" IS NOT NULL))
--
--   This migration ships that expression with ONE additional enum literal:
--
--       CHECK (("status" IN ('APPROVED','REJECTED','REVOKED'))
--              = ("decidedAt" IS NOT NULL))
--
--   The reason is that the ADR's literal form does NOT satisfy the same ADR's
--   rule 4, and the addition is forced rather than chosen:
--
--     * ADR-0011 section 1 bullet 3 says a REVOKED row "cannot keep the
--       grant's decidedAt, so revocation is unrepresentable".
--     * ADR-0011 section 1 rule 4 says both the rejection AND the revocation
--       path must be restored.
--     * ai_approvals_decision_pair_check is a biconditional, so keeping WHO
--       granted it requires keeping decidedAt too.  There is no arrangement in
--       which a REVOKED row names the approver and leaves decidedAt NULL.
--     * Therefore the literal two-literal form still refuses a REVOKED row that
--       keeps its decision, and rule 4 remains unimplementable.
--
--   'REVOKED' is the whole deviation.  It is recorded here, in the phase
--   report, and in the fixture, rather than buried.
--
--   'EXPIRED' is deliberately NOT added.  AiTraceRepository.markApprovalExpired()
--   moves a row PENDING -> EXPIRED with the pair cleared, and its javadoc says
--   why: "The actor is SYSTEM on the decisions row, because nobody decided
--   this -- it simply ran out."  Requiring a decision record on an EXPIRED row
--   would invent a decider for a request that was never decided.  So the final
--   rule is exactly:
--
--       PENDING, EXPIRED  -> no decision pair          (nobody decided)
--       APPROVED, REJECTED, REVOKED -> decision pair    (somebody decided)
--
--   and because "AiApprovalStatus" has exactly those five members, the check is
--   equivalent to ("status" = 'PENDING') = ("decidedAt" IS NULL).  It is written
--   with the IN list so a reviewer can read it against ADR-0011's text directly.
--
--   Note the rule got STRICTER, not looser, in one direction: under the old
--   expression a REJECTED row with NO decision pair was accepted, and under the
--   new one it is refused.  That is the point -- it is why this file needs the
--   repair step below at all.
--
-- THE CONSTRAINT KEEPS ITS NAME
--   ai_approvals_approval_check is referenced by name in AiApprovalService's
--   javadoc, in ApprovalLifecycleIntegrationTest, and in the runbook.  The name
--   describes the RULE ("an approval's state and its decision record agree"),
--   not the old expression, and the fixture asserts the new expression rather
--   than the old one, so reusing the name cannot let the old semantics hide.
--   PostgreSQL has no ALTER CONSTRAINT ... CHECK, so DROP + ADD is the only way
--   to change a CHECK expression; that is the whole reason this file exists.
--
-- REPAIR FIRST, THEN CONSTRAIN -- NEVER DELETE (ADR-0009 section 5)
--   The order is: count and report -> drop the wrong constraint -> repair from
--   the append-only evidence -> REFUSE if anything is unrepairable -> add the
--   right constraint.  All of it in ONE transaction, so a refusal leaves the
--   database exactly as it was, wrong constraint and all.
--
--   The repair source is ai_approval_decisions, which is append-only and
--   triggered-free precisely so it can be trusted as the record of what
--   happened: for a REJECTED row the PENDING->REJECTED decision, for a REVOKED
--   row the PENDING->APPROVED decision that granted it.  Nothing is invented:
--   a row whose evidence is missing, or whose decider was a non-human principal
--   with no actorUserId, is UNREPAIRABLE and the migration RAISES.  It never
--   deletes an approval, never fabricates a decider, and never guesses.
--
--   If this migration refuses on a real cutover database, the operator decision
--   is recorded in docs/migration/phases/phase-10b-schema-remediation.md and the
--   refusal message names the remediation.
--
-- WHAT DOES NOT HAPPEN HERE
--   * No DROP or RENAME of a table, column, index or type.  The only DROP is the
--     one CHECK constraint being replaced by its corrected definition.
--   * No NOT NULL anywhere, and specifically none on ai_run_* (ADR-0009 section 2).
--   * No CREATE INDEX, so this file is transactional and can carry a DO block.
--   * No change to ai_approval_decisions, which was never the thing at fault.
--
-- TIMESTAMPS  timestamp(3) without time zone throughout (V6).
-- Rollback: ops/flyway/rollback/undo_phase10b.sql restores the previous
-- expression, byte for byte.  It must run BEFORE any undo that drops the
-- Phase-7-10a AI tables, because the constraint lives on one of them.
-- Every identifier is schema-qualified (the baseline sets search_path = '').
-- =====================================================================

-- ---------------------------------------------------------------------
-- Step 1. REPORT what exists today, before anything is changed.
--
-- The two counts that matter are printed as NOTICE because they are the
-- evidence an operator needs, and printed rather than raised because neither
-- of them is by itself a reason to stop:
--
--   AI_APPROVALS_DECISION_VIOLATORS  rows the NEW expression would refuse
--   AI_APPROVALS_TOTAL                the denominator, so a "0" is legible
--
-- Per ADR-0009 section 5 the counts are not the assertion.  The assertion is
-- the post-condition block at the end of this file, which reads the catalog and
-- the table back and refuses to let this migration finish unless both agree
-- with the rule it claims to install.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    violators  bigint;
    total      bigint;
    by_status  text;
BEGIN
    SELECT count(*) INTO total FROM "public"."ai_approvals";
    IF total > 0 THEN
        RAISE NOTICE
            'V20261006100000: ai_approvals holds % row(s); this migration is not a no-op for them.',
            total;
    END IF;

    -- The predicate is spelled out longhand rather than copied from the new
    -- CHECK, so the report cannot be a tautology against the thing it reports.
    -- status is NOT NULL and (decidedAt IS NOT NULL) is never NULL, so neither
    -- side of the equality can be NULL and the comparison has no NULL hole.
    SELECT count(*), string_agg(status || '=' || n, ', ' ORDER BY status)
      INTO violators, by_status
      FROM (SELECT status::text AS status, count(*) AS n
              FROM "public"."ai_approvals"
             WHERE (status IN ('APPROVED', 'REJECTED', 'REVOKED')) <> ("decidedAt" IS NOT NULL)
             GROUP BY status) AS grouped;

    RAISE NOTICE 'V20261006100000: ai_approvals total rows = %', total;
    RAISE NOTICE 'V20261006100000: rows the corrected rule would refuse = % %',
                 coalesce(violators, 0),
                 CASE WHEN by_status IS NULL THEN '(none)'
                      ELSE '[' || by_status || ']' END;
END
$$;

-- ---------------------------------------------------------------------
-- Step 2. Drop the wrong definition.
--
-- The only DROP in this file, and it is the reason the file exists.  Nothing
-- that holds data is dropped; a CHECK constraint is a rule, and a wrong rule
-- cannot be edited in place.  If this statement is ever reached together with
-- a data change, the transaction below is what makes it safe.
-- ---------------------------------------------------------------------
ALTER TABLE ONLY "public"."ai_approvals"
    DROP CONSTRAINT "ai_approvals_approval_check";

-- ---------------------------------------------------------------------
-- Step 3. REPAIR from ai_approval_decisions.
--
--   REJECTED row  <- the PENDING->REJECTED decision for that approval
--   REVOKED  row  <- the PENDING->APPROVED decision that granted it
--
-- Both halves of the decision pair are copied together, because
-- ai_approvals_decision_pair_check is a biconditional and a half-copied pair is
-- itself a violation.  Only rows with decidedAt IS NULL are touched, so a rerun
-- is a no-op -- the same idempotence rule ops/flyway/backfill/B1 follows.
--
-- DISTINCT ON picks the LATEST matching decision per approval, so if the state
-- machine ever permits a re-decision the repair uses the most recent evidence
-- rather than an arbitrary one.  The shape is a CTE rather than a correlated
-- LATERAL because PostgreSQL does not allow an UPDATE ... FROM LATERAL
-- subquery to reference the table being updated -- measured on this host, not
-- assumed.
-- ---------------------------------------------------------------------
WITH candidates AS (
    SELECT a."id"             AS approval_id,
           a."organizationId" AS approval_org,
           a."status"         AS approval_status
      FROM "public"."ai_approvals" AS a
     WHERE a."decidedAt" IS NULL
       AND a."status" IN ('REJECTED'::"public"."AiApprovalStatus",
                           'REVOKED'::"public"."AiApprovalStatus")
),
picked AS (
    SELECT DISTINCT ON (c.approval_id)
           c.approval_id,
           x."decidedAt"    AS decided_at,
           x."actorUserId"  AS actor_user_id
      FROM candidates AS c
      JOIN "public"."ai_approval_decisions" AS x
        ON x."approvalId" = c.approval_id
       AND x."organizationId" = c.approval_org
       AND x."actorUserId" IS NOT NULL
       AND x."toStatus" = CASE WHEN c.approval_status = 'REJECTED'::"public"."AiApprovalStatus"
                                THEN 'REJECTED'::"public"."AiApprovalStatus"
                                ELSE 'APPROVED'::"public"."AiApprovalStatus"
                               END
     ORDER BY c.approval_id, x."decidedAt" DESC, x."createdAt" DESC
)
UPDATE "public"."ai_approvals" AS a
   SET "decidedAt"       = p.decided_at,
       "decidedByUserId" = p.actor_user_id,
       "updatedAt"       = (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')
  FROM picked AS p
 WHERE p.approval_id = a."id";

-- ---------------------------------------------------------------------
-- Step 4. REFUSE rather than delete.
--
-- Anything still violating the corrected rule after the repair is a row whose
-- decision is not recoverable from the append-only evidence:
--
--   * no matching ai_approval_decisions row at all, or
--   * the decision was recorded against a non-human principal (actorUserId IS
--     NULL), which cannot be written into a user-keyed column, or
--   * the recovered decider is the requester, which
--     ai_approvals_not_self_approved_check would then refuse.
--
-- The migration stops.  It does not delete the row, does not null a status,
-- does not relax another constraint to make the count reach zero, and does not
-- proceed with a partially repaired table.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    unrepairable  bigint;
    self_approved bigint;
    offenders     text;
BEGIN
    SELECT count(*), string_agg(a.id::text, ', ' ORDER BY a.id)
      INTO unrepairable, offenders
      FROM "public"."ai_approvals" AS a
     WHERE a."decidedAt" IS NULL
       AND a."status" IN ('REJECTED'::"public"."AiApprovalStatus",
                           'REVOKED'::"public"."AiApprovalStatus");

    IF unrepairable > 0 THEN
        RAISE EXCEPTION
            'V20261006100000 REFUSED: % ai_approvals row(s) are REJECTED or REVOKED with no '
            'recoverable decision record: %. ai_approval_decisions is the only trustworthy '
            'source and it has no row for them (or the decider was a non-human principal with '
            'no actorUserId). This migration repairs from evidence and never invents a decider, '
            'so it stops here rather than deleting the rows or leaving a table the corrected '
            'CHECK cannot hold. Operator decision required: re-source the decider into '
            'ai_approval_decisions, or record the approval in the incident before this '
            'migration is re-run.',
            unrepairable, coalesce(offenders, '(ids above)');
    END IF;

    -- Checked BEFORE the constraint is added, so the message names the cause
    -- rather than an opaque check-violation on a future write.
    SELECT count(*) INTO self_approved
      FROM "public"."ai_approvals"
     WHERE "decidedByUserId" IS NOT NULL
       AND "requestedByUserId" IS NOT NULL
       AND "decidedByUserId" = "requestedByUserId";

    IF self_approved > 0 THEN
        RAISE EXCEPTION
            'V20261006100000 REFUSED: % ai_approvals row(s) would still record a self-approval '
            'after the repair, which ai_approvals_not_self_approved_check forbids. This '
            'migration does not relax that rule and does not invent a second decider.',
            self_approved;
    END IF;
END
$$;

-- ---------------------------------------------------------------------
-- Step 5. The corrected rule.
--
-- See the header for the deviation from ADR-0011's literal text and the reason
-- it is forced.  Both sides of the equality are non-NULLable given
-- status NOT NULL, so there is no NULL-passes-the-CHECK hole.
-- ---------------------------------------------------------------------
ALTER TABLE ONLY "public"."ai_approvals"
    ADD CONSTRAINT "ai_approvals_approval_check"
    CHECK (("status" IN ('APPROVED', 'REJECTED', 'REVOKED'))
           = ("decidedAt" IS NOT NULL));

-- ---------------------------------------------------------------------
-- Step 6. POST-CONDITION. Assert on the STATE, not on this file's intent.
--
-- (ADR-0009 section 5: "Assert on state after the action, never on the message
-- it printed.")  Three independent reads, all from the catalog and the table:
-- the constraint exists, its definition is the new one, and no row violates it.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    definition     text;
    definition_norm text;
    old_form       text;
    violators      bigint;
    absent_half    bigint;
BEGIN
    -- 6a. The constraint exists AND its definition is the corrected one. Read
    --     from pg_constraint, so an amendment elsewhere cannot pass unnoticed.
    SELECT pg_get_constraintdef(oid) INTO definition
      FROM pg_constraint
     WHERE conrelid = 'public.ai_approvals'::regclass
       AND conname = 'ai_approvals_approval_check';

    IF definition IS NULL THEN
        RAISE EXCEPTION
            'V20261006100000 failed: ai_approvals_approval_check is absent after the migration.';
    END IF;

    -- Each of the three decision-bearing states must appear in the installed
    -- definition. Read as separate literals because PostgreSQL renders the IN
    -- list as status = ANY (ARRAY['APPROVED'::"AiApprovalStatus", ...]), so a
    -- single substring test on the whole sequence would be brittle.
    IF definition NOT LIKE '%''APPROVED''%'
       OR definition NOT LIKE '%''REJECTED''%'
       OR definition NOT LIKE '%''REVOKED''%' THEN
        RAISE EXCEPTION
            'V20261006100000 failed: ai_approvals_approval_check is "%", which does not admit '
            'APPROVED, REJECTED and REVOKED as the decision-bearing states. A rejection that '
            'cannot name its decider is an approval system that cannot be audited.', definition;
    END IF;

    -- NORMALISE ONCE, AND ONLY THE SCHEMA QUALIFIER.
    --
    -- pg_get_constraintdef() writes a type name qualified or unqualified
    -- depending on whether the type is visible on the CURRENT search_path:
    --
    --     search_path = "$user", public
    --       CHECK (((status = ANY (ARRAY['APPROVED'::"AiApprovalStatus", ...])) = (...)))
    --     search_path = ''
    --       CHECK (((status = ANY (ARRAY['APPROVED'::public."AiApprovalStatus", ...])) = (...)))
    --
    -- and V20260814000000 line 56 installs an EMPTY search_path with
    -- is_local = false, which is a session setting that lasts for the rest of
    -- the connection -- so under Flyway, which runs every migration of a boot on
    -- one connection, the second rendering is the one this file will always
    -- see. Measured on PostgreSQL 16.15; recorded in
    -- docs/migration/phases/phase-30-migration-chain-bootstrap.md.
    --
    -- The strip is deliberately narrow. It removes a schema qualifier in front
    -- of a QUOTED identifier and nothing else. It cannot turn one expression
    -- into another, cannot introduce or remove a literal, and cannot hide a
    -- changed column -- it only makes the type name read the same either way.
    -- A guard whose answer flips on a session setting is not a verification.
    definition_norm := regexp_replace(coalesce(definition, ''),
                                      '[A-Za-z_][A-Za-z_0-9$]*\."', '"', 'g');

    -- And the single-literal form must be gone.
    --
    -- The pattern is written from the EXACT rendering PostgreSQL produces, and
    -- it was WRONG TWICE. First it read
    --     %=''APPROVED''::"AiApprovalStatus") = ("decidedAt" IS NOT NULL)%
    -- with no space after the equals sign, while pg_get_constraintdef renders
    --     (status = 'APPROVED'::"AiApprovalStatus") = ("decidedAt" IS NOT NULL)
    -- WITH one. So the guard could never have fired. It passed against a correct
    -- migration for the wrong reason, which ADR-0009 section 5 calls a failed
    -- rehearsal; the throwaway undo rehearsal is what exposed it, because its
    -- post-condition re-checks the same pattern against the RESTORED definition.
    --
    -- Second -- and this is what Phase 30 found -- it ALSO could never fire
    -- under the empty search_path the baseline installs, because the enum type
    -- then renders ::public."AiApprovalStatus". So a removal check that could not
    -- detect the removal shipped and passed for eleven phases. The normalisation
    -- above is what fixes it.
    --
    -- It is asserted POSITIVELY -- "the old form is present" is the failure
    -- condition -- so the direction of the test cannot hide behind a NOT LIKE
    -- that was never really evaluated.
    IF definition_norm LIKE '%= ''APPROVED''::"AiApprovalStatus") = ("decidedAt" IS NOT NULL)%' THEN
        RAISE EXCEPTION
            'V20261006100000 failed: ai_approvals_approval_check is still the single-literal '
            'form ("%"). That is the defect this migration exists to remove.', definition;
    END IF;

    -- ...and the NEW form is positively present, so the pair is symmetric: the
    -- rule that must be there is asserted to be there, and the rule that must
    -- be gone is asserted to be gone. Without this, "the old form is absent"
    -- would also be satisfied by a definition that is neither form.
    IF definition_norm NOT LIKE '%= ANY (ARRAY[''APPROVED''::"AiApprovalStatus", ''REJECTED''::"AiApprovalStatus", ''REVOKED''::"AiApprovalStatus"])) = ("decidedAt" IS NOT NULL)%' THEN
        RAISE EXCEPTION
            'V20261006100000 failed: ai_approvals_approval_check is "%", which is neither the '
            'pre-batch single-literal form nor the corrected ANY-list form. This migration '
            'installs one of exactly two definitions and will not accept a third.',
            definition;
    END IF;

    -- ...and the pattern itself is proven to discriminate, AGAINST A RENDERING
    -- BUILT HERE, UNDER THIS SESSION'S OWN search_path. A guard whose pattern
    -- matches nothing is worse than no guard.
    --
    -- The previous self-test compared the pattern with a HARDCODED string, so it
    -- answered "yes, it discriminates" in an environment where the pattern could
    -- never match anything at all. That is the failure this whole block exists
    -- to prevent, repeated one line lower. The probe below therefore installs the
    -- pre-batch expression on a scratch table and asks the catalogue to render
    -- it HERE. It creates nothing in public, reads no application row, and is
    -- dropped before the block ends; it is a rendering oracle, not a re-derivation
    -- of the rule, so a re-derived expression cannot agree with itself.
    -- NB no ON COMMIT DROP. A psql rehearsal that pipes the chain without
    -- --single-transaction commits each statement separately, so the probe would
    -- be dropped at the end of the CREATE and the SELECT below would find
    -- nothing. It is dropped explicitly at the end of this block instead, and it
    -- lives in pg_temp, never in public, so a raise before that DROP leaves at
    -- most a scratch table behind for the rest of the session.
    CREATE TEMP TABLE firstelement_old_approval_form_probe (
        "status"     "public"."AiApprovalStatus",
        "decidedAt"  timestamp(3)
    );

    ALTER TABLE pg_temp.firstelement_old_approval_form_probe
        ADD CONSTRAINT firstelement_old_approval_form
        CHECK (("status" = 'APPROVED'::"public"."AiApprovalStatus") = ("decidedAt" IS NOT NULL));

    SELECT pg_get_constraintdef(oid) INTO old_form
      FROM pg_constraint
     WHERE conrelid = 'pg_temp.firstelement_old_approval_form_probe'::regclass
       AND conname = 'firstelement_old_approval_form';

    IF old_form IS NULL THEN
        RAISE EXCEPTION
            'V20261006100000 failed: the old-form probe did not render, so this migration cannot '
            'prove its own detector works. Treat the detector as unverified until it does.';
    END IF;

    IF regexp_replace(old_form, '[A-Za-z_][A-Za-z_0-9$]*\."', '"', 'g')
         NOT LIKE '%= ''APPROVED''::"AiApprovalStatus") = ("decidedAt" IS NOT NULL)%' THEN
        RAISE EXCEPTION
            'V20261006100000 failed: the old-form detection pattern does not match the rendering '
            'this session produces for the pre-batch expression ("%"), so the guard above could '
            'never fire. Fix the pattern before trusting the check above it.', old_form;
    END IF;

    DROP TABLE pg_temp.firstelement_old_approval_form_probe;

    -- 6b. No row violates it. CHECK is only enforced for new writes, so a
    --     migration that left a violating row behind would look successful.
    SELECT count(*) INTO violators
      FROM "public"."ai_approvals"
     WHERE (status IN ('APPROVED', 'REJECTED', 'REVOKED')) <> ("decidedAt" IS NOT NULL);

    IF violators > 0 THEN
        RAISE EXCEPTION
            'V20261006100000 failed: % ai_approvals row(s) still disagree with the corrected '
            'rule. A CHECK is enforced for new writes only; this migration must leave zero.',
            violators;
    END IF;

    -- 6c. The OTHER half of the biconditional is still whole: the pair. A
    --     migration that fixed decidedAt and broke decidedByUserId would have
    --     traded one unauditable approval for another.
    SELECT count(*) INTO absent_half
      FROM "public"."ai_approvals"
     WHERE ("decidedAt" IS NULL) <> ("decidedByUserId" IS NULL);

    IF absent_half > 0 THEN
        RAISE EXCEPTION
            'V20261006100000 failed: % ai_approvals row(s) carry half a decision pair, which '
            'ai_approvals_decision_pair_check forbids.', absent_half;
    END IF;

    RAISE NOTICE
        'V20261006100000: ai_approvals_approval_check = %; 0 violating rows; decision pairs intact.',
        definition;
END
$$;
