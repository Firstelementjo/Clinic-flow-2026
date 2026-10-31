-- =====================================================================
-- ops/flyway/rollback/undo_phase38.sql
-- THE UNDO OF PHASE 38, WHICH IS: THERE IS NOT ONE, AND HERE IS WHY.
--
-- V20261009130000 did `ALTER TYPE public."UserRole" ADD VALUE 'CLINIC_STAFF'`.
-- PostgreSQL has NO `ALTER TYPE ... DROP VALUE`. It has never had one. The
-- only removal is to recreate the type without the label and re-point every
-- column that uses it, which is a full-table rewrite of `users` and would
-- silently invalidate every prepared statement, every FK to it, and every
-- grant that names it.
--
-- SO THIS FILE DOES NOT PRETEND TO UNDO THE CHANGE. It refuses, loudly,
-- in the two states where an undo would be destructive, and records what
-- an operator actually has to do.
--
-- STATE 1 -- no row carries the label. Nothing to undo in any meaningful
--   sense: the label is unreachable, grants nothing, and changes no query
--   result. Leaving it is strictly better than the rewrite.
--
-- STATE 2 -- rows carry it. The rows MUST keep it. Dropping the label
--   would leave `users.role` holding a string no enum value exists for,
--   and `UserRole.fromValue` throws on an unknown label -- so those users
--   could not sign in at all. This is the state that makes an undo a
--   DECISION rather than an operation, and it is an operator's to make
--   with the business context: reassign the affected users to a role that
--   exists, in that order, and only then consider the type rewrite.
--
-- This file refuses rather than repairs for the same reason
-- V20261009110000 does (ADR-0009 section 5): choosing which scope was right
-- is not a side effect of reverting a schema change.
-- =====================================================================

DO
$$
    DECLARE
        holders  integer;
    BEGIN
        -- The one thing that must never be true: the type is gone but the
        -- rows are not. Checked with to_regtype so an already-absent type
        -- reads as zero holders rather than raising.
        IF to_regtype('public."UserRole"') IS NULL THEN
            RAISE EXCEPTION
                'undo_phase38: public."UserRole" does not exist at all, so this undo script has '
                'nothing to undo and something else has removed the type. Investigate before '
                'assuming the baseline was restored.';
        END IF;

        SELECT count(*) INTO holders
          FROM public.users u
         WHERE u.role::text = 'CLINIC_STAFF';

        IF holders > 0 THEN
            RAISE EXCEPTION
                'undo_phase38 REFUSES: % user(s) carry role CLINIC_STAFF. PostgreSQL cannot drop an '
                'enum label, so the only removal is recreating "UserRole" and re-pointing every '
                'column that uses it -- a full rewrite of users, which would leave these % row(s) '
                'holding a value no enum member exists for and unable to sign in at all, because '
                'UserRole.fromValue throws on an unknown label. Reassign these users to a role that '
                'exists FIRST, confirm this count is 0, and only then weigh the type rewrite as a '
                'separate, scheduled, operator-approved act.',
                holders, holders;
        END IF;

        RAISE NOTICE
            'undo_phase38: 0 users carry CLINIC_STAFF, so there is nothing to undo in any meaningful '
            'sense. The label stays: it is unreachable, grants nothing, and changes no query result, '
            'so leaving it is strictly less risky than the rewrite that removing it would require. '
            'The forward migration V20261009130000 is also replay-safe (IF NOT EXISTS), so a '
            'database restored from a backup that already carries the label re-applies it as a no-op.';
    END
$$;
