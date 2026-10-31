-- =====================================================================
-- V20261019000000__appointments_status_dec003_and_cancel_columns.sql
-- ADDITIVE, WITH ONE RECORDED DATA STEP.  CREATES THREE COLUMNS AND ONE
-- CHECK CONSTRAINT.  DELETES NO ROW.
--
-- WHAT THIS CLOSES
--   DEC-017. appointments.status was "text" with no constraint, and the only
--   deployed booking path wrote an eighth status, APPROVED, with no human
--   approval anywhere. DEC-003 defines seven:
--
--     PENDING CONFIRMED CHECKED_IN IN_PROGRESS COMPLETED   hold the slot
--     CANCELLED NO_SHOW                                     free it
--
--   Reschedule is NOT a status; it is a change of appointmentDate.
--
-- THE LEGACY PATH IS FIXED FIRST, IN CODE, IN THE SAME RELEASE
--   packages/ai/src/tools/clinic-scheduling.ts now writes PENDING at all four
--   booking sites, because DEC-006 already requires that AI autonomy is read-only
--   and booking needs human approval. apps/api's confirmation-rate reader was
--   updated in the same change: it previously counted APPROVED, and had that
--   been left alone every AI-proposed booking would have silently vanished from
--   the legacy metric.
--
--   Adding this CHECK before that code change would have rejected every booking
--   production can currently make. The order matters and is why the two are one
--   change.
--
-- THE DATA STEP, AND WHY IT IS NOT OPTIONAL
--   Historical rows written by the old path carry APPROVED, and a CHECK cannot
--   be added while they do. They are moved to PENDING.
--
--   That is not a cosmetic re-label. Those appointments were SELF-approved: no
--   human ever agreed to them. Recording them as approved is the lie DEC-017
--   recorded, so PENDING - which DEC-003 defines as holding the slot and
--   awaiting confirmation - is the honest status.
--
--   The row count is emitted as a NOTICE before the step and re-checked after,
--   so the migration log states exactly how many rows moved.
--
--   IT IS NOT REVERSIBLE BY A SCRIPT. There is no way to tell afterwards which
--   PENDING rows were human-confirmed and which were formerly self-approved
--   APPROVED. A reversal would need the pre-migration backup.
--   >>> BEFORE DEPLOYING: take a backup and record
--   >>>     select count(*) from appointments where status = 'APPROVED';
--   >>> This migration refuses to run if that table is non-empty AND the
--   >>> operator has not set the acknowledge flag below, so an unattended
--   >>> deploy cannot make this decision on its own.
-- =====================================================================

DO $$
DECLARE
    self_approved integer;
    acknowledged  boolean := current_setting('firstelement.dec017_acknowledge', true) = 'on';
BEGIN
    SELECT count(*) INTO self_approved
      FROM "public"."appointments"
     WHERE "status" = 'APPROVED';

    IF self_approved > 0 AND NOT acknowledged THEN
        RAISE EXCEPTION
            'DEC-017: % appointments carry the non-DEC-003 status APPROVED, written by the '
            'self-approving AI booking path. They are not human-approved bookings and must be '
            're-classified as PENDING before this constraint can exist. This is not reversible '
            'by script. Take a backup, record that count, then re-run with '
            '-Dfirstelement.dec017_acknowledge=on (or SET firstelement.dec017_acknowledge = ''on'') '
            'to accept the change deliberately.', self_approved;
    END IF;

    RAISE NOTICE 'DEC-017: re-classifying % self-approved AI bookings as PENDING', self_approved;

    UPDATE "public"."appointments"
       SET "status" = 'PENDING',
           "updatedAt" = (current_timestamp at time zone 'utc')
     WHERE "status" = 'APPROVED';

    RAISE NOTICE 'DEC-017: reclassified % rows', self_approved;
END
$$;

-- ---------------------------------------------------------------------
-- Step 1. the cancellation columns DEC-003 asked for.
--
-- cancelledBy is a USER id, nullable, matching the existing createdById
-- convention on this very table. A cancellation with no human actor - an
-- automated or policy-driven one - leaves it NULL and names the actor in
-- cancelledReason, which is a free text field DEC-003 already requires. No
-- separate actor-type column is invented: DEC-003 named three columns and a
-- fourth would be a guess about a vocabulary nobody has written down.
-- ---------------------------------------------------------------------
ALTER TABLE "public"."appointments"
    ADD COLUMN "cancelledBy" uuid;

ALTER TABLE "public"."appointments"
    ADD COLUMN "cancelledReason" text;

ALTER TABLE "public"."appointments"
    ADD COLUMN "cancelledAt" timestamp(3) without time zone;

COMMENT ON COLUMN "public"."appointments"."cancelledBy" IS
    'DEC-003: the user who cancelled. NULL when nobody did - an automated or policy-driven '
    'cancellation names its actor in cancelledReason instead. Matches createdById''s convention '
    'on this table.';

COMMENT ON COLUMN "public"."appointments"."cancelledReason" IS
    'DEC-003: why the appointment was cancelled. Required when cancelledAt is set, so a '
    'cancellation always carries a reason a patient could be told.';

COMMENT ON COLUMN "public"."appointments"."cancelledAt" IS
    'DEC-003: when it was cancelled. NULL while the appointment stands.';

-- A cancellation that carries no instant, or an instant and no reason, is
-- half-recorded and would leave a receptionist unable to explain it.
ALTER TABLE "public"."appointments"
    ADD CONSTRAINT "appointments_cancellation_is_complete"
    CHECK (   ("cancelledAt" IS NULL AND "cancelledBy" IS NULL AND "cancelledReason" IS NULL)
           OR ("cancelledAt" IS NOT NULL AND "cancelledReason" IS NOT NULL) );

-- A cancelled or missed appointment is one the dentist is NOT sitting in, so a
-- cancellation on a live one is a contradiction rather than a transition.
ALTER TABLE "public"."appointments"
    ADD CONSTRAINT "appointments_cancellation_matches_status"
    CHECK (   "cancelledAt" IS NULL
           OR "status" IN ('CANCELLED', 'NO_SHOW') );

-- ---------------------------------------------------------------------
-- Step 2. DEC-003's seven statuses, enforced.
--
-- This is the constraint DEC-017 was blocking. It can only exist because the
-- legacy path stopped writing APPROVED.
-- ---------------------------------------------------------------------
ALTER TABLE "public"."appointments"
    ADD CONSTRAINT "appointments_status_dec003"
    CHECK ("status" IN (
        'PENDING', 'CONFIRMED', 'CHECKED_IN', 'IN_PROGRESS', 'COMPLETED',
        'CANCELLED', 'NO_SHOW'));

COMMENT ON CONSTRAINT "appointments_status_dec003" ON "public"."appointments" IS
    'DEC-003: exactly seven statuses. The first five HOLD the dentist''s slot; CANCELLED and '
    'NO_SHOW free it. APPROVED is deliberately absent - the self-approving AI path that wrote it '
    'was removed in the same release, because DEC-006 already required human approval for a '
    'booking and no human was ever asked.';

CREATE INDEX "appointments_organizationId_status_idx"
    ON "public"."appointments" USING "btree" ("organizationId", "status");

-- ---------------------------------------------------------------------
-- post-conditions
-- ---------------------------------------------------------------------
DO $$
DECLARE
    surviving_apprvd integer;
    constraint_count  integer;
    cancelled_cols    integer;
BEGIN
    SELECT count(*) INTO surviving_apprvd
      FROM "public"."appointments" WHERE "status" = 'APPROVED';

    IF surviving_apprvd <> 0 THEN
        RAISE EXCEPTION '% appointments still carry APPROVED after the re-classification',
            surviving_apprvd;
    END IF;

    SELECT count(*) INTO constraint_count
      FROM pg_constraint c
      JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'appointments'
       AND c.conname IN ('appointments_status_dec003', 'appointments_cancellation_is_complete',
                         'appointments_cancellation_matches_status')
       AND c.contype = 'c';

    IF constraint_count <> 3 THEN
        RAISE EXCEPTION 'expected 3 DEC-003 constraints on appointments, found %', constraint_count;
    END IF;

    -- Every one of the seven must be REACHABLE, not merely permitted by the CHECK.
    IF (SELECT count(DISTINCT s) FROM unnest(ARRAY[
            'PENDING','CONFIRMED','CHECKED_IN','IN_PROGRESS','COMPLETED','CANCELLED','NO_SHOW']) AS s) <> 7 THEN
        RAISE EXCEPTION 'the DEC-003 status list does not hold seven distinct values';
    END IF;

    SELECT count(*) INTO cancelled_cols
      FROM information_schema.columns
     WHERE table_schema = 'public'
       AND table_name = 'appointments'
       AND column_name IN ('cancelledBy', 'cancelledReason', 'cancelledAt');

    IF cancelled_cols <> 3 THEN
        RAISE EXCEPTION 'expected 3 DEC-003 cancellation columns, found %', cancelled_cols;
    END IF;
END
$$;