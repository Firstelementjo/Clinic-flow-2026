-- =====================================================================
-- V20261009130000__user_role_clinic_staff.sql
-- ADDITIVE.  TRANSACTIONAL.  ONE ENUM VALUE.  NO DATA.
--
-- THE GAP THIS EXISTS TO CLOSE, IN ONE PARAGRAPH
--   Phase 33 gave the clinic 18 role-based permissions across its 21
--   non-public handlers, and named a role `CLINIC_STAFF` for them. That
--   role was never added to the `"UserRole"` enum. A permission held only
--   by a role that cannot exist is a COMMENT, and Phase 38 found the
--   mirror-image consequence in the legacy tree: the only way to employ a
--   receptionist there was EMPLOYEE, which also carries the whole CRM read
--   surface -- so a booking clerk could read every clinical note in the
--   organization.
--
-- WHAT THIS FILE IS, AND IS NOT
--   It ADDS the value. It does not grant it to anybody, does not create a
--   user, and does not touch a single row. Assigning the role is an
--   operator decision with business context; a migration that also did it
--   would be inventing one.
--
-- WHY ONE STATEMENT, AND WHY IT NEVER USES THE NEW VALUE
--   PostgreSQL 12 added the ability to run ALTER TYPE ... ADD VALUE inside
--   a transaction block, but with a restriction that is easy to forget:
--   the new value CANNOT BE USED until that transaction commits. On 11 and
--   earlier the statement itself is rejected with "ALTER TYPE ... ADD
--   cannot run inside a transaction block".
--
--   This file therefore contains exactly ONE statement that adds a value,
--   and nothing that reads a user, writes a row, or branches on a role.
--   That makes it correct on 11 and later without the caller having to
--   know which version it is on. `rehearse-phase38.sql` proves BOTH
--   sides of this: that the value is added inside the transaction on
--   12+, and that using it in the same transaction is refused.
--
-- WHY IF NOT EXISTS
--   Flyway runs a version once, but this chain is also rehearsed against
--   databases restored from backup, and a restore carries the value with
--   it. Without the guard the deployment would abort on a database that
--   is already correct. The Prisma migration for the same change carries
--   the same guard, for the same reason.
--
-- REFUSE, DO NOT REPAIR (ADR 0009 section 5)
--   Nothing to refuse here: this file adds a label and rewrites no row.
--   There is no residue to choose a tenant's intent for. The principle is
--   recorded because the temptation is to add the role to existing users
--   here, and that would be exactly the data decision this file refuses
--   to make.
--
-- VERIFICATION IS STRUCTURAL, NEVER RENDERED (Phase 30)
--   Six guards in this chain were broken until Phase 30 because they
--   compared `pg_get_constraintdef`-style RENDERED text, which changes
--   with `search_path` -- so they asserted on a session setting rather
--   than on database state, and the chain could not bootstrap a clean
--   database at all. This file reads `pg_enum` and `pg_type` directly.
--
--   THE SAME search_path HAZARD, HIT AGAIN IN THIS VERY FILE, AND WHY
--   THE HEADER IS THIS LONG. The first draft opened with
--
--       role_type regtype := '"UserRole"'::regtype;
--
--   A DECLARE default is evaluated before the block's body runs, so it
--   cannot be guarded, and under the chain's `search_path = ''` the
--   unqualified name resolves to NOTHING. It failed the clean-bootstrap
--   rehearsal with `ERROR: type "UserRole" does not exist ... inline_code_block
--   line 3` -- an error naming neither this migration nor the cause.
--
--   It had passed a probe, because that probe ran the setup and the
--   migration in two SEPARATE psql sessions and so never reproduced the
--   one-session `search_path = ''` condition the chain actually runs
--   under. A probe that does not reproduce the harness is not a probe.
--   The fix is `to_regtype('public."UserRole"')`, which returns NULL for
--   an absent type instead of raising, so the existence question becomes
--   answerable inside the block at all -- plus a QUALIFIED
--   `ALTER TYPE public."UserRole"`, for the identical reason one line
--   later. Two unqualified references, two separate failures, both named
--   only as `type "UserRole" does not exist`.
-- =====================================================================

DO
$$
    DECLARE
        role_type   regtype;
        labels      text[];
        enum_count  integer;
    BEGIN
        -- to_regtype, NOT '"UserRole"'::regtype.
        --
        -- A cast in the DECLARE default is evaluated when the block's variables are
        -- initialised, which happens BEFORE this file can check anything, and under the
        -- chain's `search_path = ''` an unqualified name does not resolve at all. The
        -- result is an error at `inline_code_block line 3` that names neither the
        -- migration nor the reason -- which is exactly how this first draft failed the
        -- clean-bootstrap rehearsal while passing a probe that ran in a separate psql
        -- session with a normal search_path.
        --
        -- to_regtype returns NULL for an absent type instead of raising, so the
        -- "does the enum exist at all" question below is answerable at all.
        SELECT to_regtype('public."UserRole"') INTO role_type;

        IF role_type IS NULL THEN
            RAISE EXCEPTION
                'V20261009130000 failed: public."UserRole" does not exist, so this migration has no '
                'enum to add a value to. The baseline should have created it.';
        END IF;

        -- Qualified for the same reason: under `search_path = ''` an unqualified ALTER TYPE
        -- names a type in no schema and raises `type "UserRole" does not exist`.
        ALTER TYPE public."UserRole" ADD VALUE IF NOT EXISTS 'CLINIC_STAFF';

        -- Structural: read pg_enum directly. The ordinal is what the label sorts by, and it is
        -- appended, so CLINIC_STAFF is LAST. Asserting the position matters because a role enum
        -- that is ever iterated by ordinal rather than by name would change behaviour, and this
        -- file is what fixes that order for good.
        --
        -- Every regtype reference below is QUALIFIED. Under `search_path = ''` an
        -- unqualified 'public."UserRole"' written as '"UserRole"'::regtype is a different
        -- (null) answer, and a guard that reads a null silently is worse than no guard.
        SELECT array_agg(e.enumlabel ORDER BY e.enumsortorder)
          INTO labels
          FROM pg_enum e
          JOIN pg_type t ON t.oid = e.enumtypid
          JOIN pg_namespace n ON n.oid = t.typnamespace
         WHERE t.oid = role_type
           AND n.nspname = 'public';

        SELECT count(*) INTO enum_count FROM pg_enum WHERE enumtypid = role_type;

        IF NOT ('CLINIC_STAFF' = ANY (labels)) THEN
            RAISE EXCEPTION
                'V20261009130000 failed: "UserRole" does not contain CLINIC_STAFF after the ALTER. '
                'The labels are %. If this fired, the statement was rolled back rather than '
                'silently skipped, which is the correct outcome for a value the code depends on.',
                coalesce(array_to_string(labels, ', '), '(none)');
        END IF;

        IF labels <> ARRAY['ADMIN', 'EMPLOYEE', 'CLINIC_STAFF'] THEN
            RAISE EXCEPTION
                'V20261009130000 failed: "UserRole" now reads % but must read exactly '
                'ADMIN, EMPLOYEE, CLINIC_STAFF in that order. An unexpected label here means this '
                'chain and the application enum have diverged, and UserRole.fromValue throws on an '
                'unknown label -- so a user carrying it could not sign in at all.',
                coalesce(array_to_string(labels, ', '), '(none)');
        END IF;

        IF enum_count <> 3 THEN
            RAISE EXCEPTION
                'V20261009130000 failed: "UserRole" has % values but this chain expects 3. '
                'pg_enum says %.', enum_count, coalesce(array_to_string(labels, ', '), '(none)');
        END IF;

        RAISE NOTICE
            'V20261009130000: CLINIC_STAFF added to "UserRole". Verified in pg_catalog -- the type '
            'is public."UserRole" under search_path = '''', the labels are ADMIN, EMPLOYEE, '
            'CLINIC_STAFF in that order, and pg_enum holds 3 rows. No row was written and no user '
            'was granted the role: assigning it is an operator decision. Note that a Spring process '
            'started BEFORE this migration cannot read a CLINIC_STAFF user, because '
            'UserRole.fromValue throws on an unknown label.';
    END
$$;
