-- =====================================================================
-- V20261017000000__clinic_schedules_no_overlap.sql
-- ADDITIVE.  TRANSACTIONAL.  CREATES ONE EXTENSION AND ONE CONSTRAINT.
-- DELETES NO ROW AND MODIFIES NO EXISTING COLUMN.
--
-- WHAT THIS CLOSES
--   V20261016000000 asserted, in its post-condition, that no two active schedules
--   for one dentist and weekday overlap. That assertion only runs when the
--   migration runs. Nothing stopped an OVERLAPPING WINDOW BEING INSERTED
--   LATER, and AvailabilityServiceIntegrationTest proved it by trying:
--
--       addWindow(09:00-12:00) then addWindow(11:00-14:00)  ->  accepted
--
--   Two overlapping windows emit each overlapping minute twice, so a
--   receptionist can book the same slot from either. The check has to live on
--   the table, not in a post-condition.
--
-- WHY AN EXCLUDE CONSTRAINT AND NOT A TRIGGER
--   This is the same mechanism the appointment double-booking constraint needs,
--   so btree_gist is introduced here rather than there. A trigger would be a
--   second answer to a question the database can answer directly, and it would
--   not fire for a concurrent insert the way the constraint does.
--
-- THE RANGES ARE BOTH NEEDED
--   int4range over the minutes catches two windows overlapping on one day.
--   daterange over the effective dates catches a window that overlaps only
--   because its EFFECTIVE PERIOD overlaps - a summer schedule and a winter one
--   may both be 09:00-17:00 on the same weekday, and that is legitimate.
--
--   coalesce("effectiveTo", 'infinity'::date) makes an open-ended window reach
--   forever, so an open-ended window conflicts with everything on that weekday.
--
-- PARTIAL: WHERE ("isActive")
--   A deactivated window is history, not a conflict. It stays in the table so the
--   schedule's past is auditable, and it stops constraining the present.
--
-- DEPLOYMENT NOTE
--   btree_gist must be installable by the migration role. It ships with
--   PostgreSQL contrib, so on a managed instance it is usually already present or
--   available; if a deployment cannot create it, this migration fails loudly at
--   migrate() rather than leaving an unenforced overlap rule in place.
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS "btree_gist";

ALTER TABLE "public"."clinic_schedules"
    ADD CONSTRAINT "clinic_schedules_no_overlapping_window"
    EXCLUDE USING "gist" (
        "organizationId" WITH =,
        "doctorId"       WITH =,
        "weekday"        WITH =,
        int4range("startMinute", "endMinute", '[)') WITH &&,
        daterange("effectiveFrom",
                  coalesce("effectiveTo", 'infinity'::date), '[]') WITH &&
    )
    WHERE ("isActive");

COMMENT ON CONSTRAINT "clinic_schedules_no_overlapping_window"
    ON "public"."clinic_schedules" IS
    'One dentist cannot have two active schedules covering the same weekday and the same '
    'minutes, while their effective periods overlap. Without this, a slot inside the overlap is '
    'offered twice. Established here rather than in a post-condition because a post-condition '
    'only runs when the migration does.';

COMMENT ON COLUMN "public"."clinic_schedules"."doctorId" IS
    'The EXCLUDE constraint on this table lists "organizationId" first, so the GiST index it '
    'creates leads with the tenant key and satisfies the chain rule that every clinic index is '
    'tenant-led. Leaving it off would have produced the first tenant-less index outside the '
    'documented allow list. It changes nothing in practice - a doctorId already belongs to one '
    'organization - but the index says so explicitly rather than relying on that.';

-- ---------------------------------------------------------------------
-- post-conditions
-- ---------------------------------------------------------------------
DO $$
DECLARE
    has_exclusion integer;
    has_gist      integer;
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_extension WHERE extname = 'btree_gist'
    ) THEN
        RAISE EXCEPTION 'btree_gist is not installed, so the overlap rule cannot be enforced';
    END IF;

    SELECT count(*) INTO has_gist
      FROM pg_extension WHERE extname = 'pg_trgm' OR extname = 'btree_gist';

    SELECT count(*) INTO has_exclusion
      FROM pg_constraint c
      JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'clinic_schedules'
       AND c.conname = 'clinic_schedules_no_overlapping_window'
       AND c.contype = 'x';

    IF has_exclusion <> 1 THEN
        RAISE EXCEPTION 'the schedule overlap EXCLUDE constraint is not attached';
    END IF;

    -- The constraint must be PARTIAL: a deactivated window is history and must
    -- stop conflicting with the live schedule.
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c
          JOIN pg_class t ON t.oid = c.conrelid
         WHERE t.relname = 'clinic_schedules'
           AND c.conname = 'clinic_schedules_no_overlapping_window'
           AND pg_get_constraintdef(c.oid) LIKE '%WHERE%'
    ) THEN
        RAISE EXCEPTION 'the overlap constraint is not partial, so a deactivated window would '
                        'still conflict with the live one';
    END IF;
END
$$;