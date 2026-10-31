-- =====================================================================
-- V20261016000000__clinic_schedules_time_off_resources.sql
-- ADDITIVE.  TRANSACTIONAL.  CREATES FOUR TABLES AND THEIR INDEXES.
-- TOUCHES NO EXISTING TABLE COLUMN AND DELETES NO ROW.
--
-- WHAT THIS CLOSES
--   There is no schedule. A doctor's availability is four denormalised columns
--   on the doctors row -- workingDays integer[], startHour text, endHour text,
--   slotDuration integer -- which cannot express:
--
--     - a split shift (a lunch break, an afternoon-only session)
--     - leave, a public holiday, or a training day
--     - a schedule that changes on a date rather than being rewritten
--     - two doctors sharing a chair, or a chair that only does certain work
--     - anything timezone-aware, because the columns are wall-clock strings
--
--   And it cannot express the DEC-001 rule that made the two legacy
--   implementations disagree: a buffer is PADDING, not patient time.
--
-- WHY ONE ROW PER SHIFT RATHER THAN ONE ROW PER WEEK
--   A "working week" row cannot hold a lunch break. Two rows on the same
--   weekday with disjoint windows is the smallest shape that can, and it costs
--   one row. Overlapping windows are therefore possible in principle; the
--   service merges them, and a post-condition asserts no two schedules for one
--   doctor and weekday overlap, so a bad pair fails at migration time rather
--   than producing double-booked slots at 09:30 on a Tuesday.
--
-- THE BUFFER RULE IS ENFORCED IN THE SERVICE, NOT HERE
--   DEC-001: the patient's booked interval is the service duration; the buffer
--   occupies the schedule AROUND it. So a 30-minute service with a 10-minute
--   buffer blocks 40 minutes of the doctor's day, and the next patient starts
--   at +40, not +30.
--
--   That is enforced in AvailabilityService, and -- the part that matters -- it
--   is applied to EXISTING BOOKINGS as well as to candidates. The legacy AI
--   path widened candidates by the buffer but left existing bookings at their
--   bare duration, which is how a chair ends up double-booked across a
--   procedure that has a buffer. Both sides are expanded here.
--
--   No UNIQUE INDEX on time-off, because overlapping leave is a real thing and
--   refusing it would be refusing reality.
--
-- NO BOOKING STATUS CHECK APPEARS IN THIS FILE
--   appointments.status is still unconstrained text, because DEC-017 is open:
--   the only deployed booking path writes an eighth status, APPROVED, and a
--   seven-value CHECK would reject every booking production can currently make.
--   The availability service therefore filters
--       status not in ('CANCELLED', 'NO_SHOW')
--   which is correct under DEC-003 whether the set has seven values or eight,
--   because those two are exactly the statuses that free a slot.
--
-- NO RESOURCE CAPABILITY VOCABULARY IS SEEDED
--   DEC-002 says chairs are capability-tagged and does not enumerate the tags.
--   Tags are a table, not a CHECK, for the same reason the finding vocabulary is:
--   a CHECK is DDL, so adding a capability would be a migration rather than
--   something a clinic administrator can do.
--
-- THE timezone COLUMN IS NOT NULL AND HAS NO DEFAULT
--   A schedule with no zone is a schedule whose DST behaviour nobody has
--   decided. Forcing the caller to name the clinic's zone means no schedule can
--   ever silently be interpreted as UTC. This migration records the zone; it
--   does not yet resolve it - see the header note in AvailabilityService.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Step 1. clinic_schedules. One row per working window.
-- ---------------------------------------------------------------------
CREATE TABLE "public"."clinic_schedules" (
    "id"                        uuid NOT NULL,
    "organizationId"            uuid NOT NULL,
    "projectId"                 uuid NOT NULL,
    "doctorId"                  uuid NOT NULL,
    "weekday"                   smallint NOT NULL,
    "startMinute"               integer NOT NULL,
    "endMinute"                 integer NOT NULL,
    "durationOverrideMinutes"   integer,
    "timezone"                  text NOT NULL,
    "effectiveFrom"             date NOT NULL,
    "effectiveTo"               date,
    "isActive"                  boolean DEFAULT true NOT NULL,
    "createdAt"                 timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,

    -- ISO weekday: 1 = Monday .. 7 = Sunday. 0 is Sunday in java.time's
    -- DayOfWeek.getValue() convention and is NOT accepted here, because the
    -- denormalised doctors.workingDays uses a different numbering and silently
    -- accepting both would be worse than refusing one.
    CONSTRAINT "clinic_schedules_weekday_canonical"
        CHECK ("weekday" BETWEEN 1 AND 7),

    -- Minute-of-day, half-open [startMinute, endMinute). A zero-length window
    -- is refused rather than stored: it would generate no slots and read as a
    -- schedule that exists.
    CONSTRAINT "clinic_schedules_window_ordered"
        CHECK ("startMinute" >= 0 AND "endMinute" <= 1440 AND "startMinute" < "endMinute"),

    -- DEC-001's per-dentist override. NULL means "use the service duration",
    -- which is the common case and must not be written as 0.
    CONSTRAINT "clinic_schedules_override_positive"
        CHECK ("durationOverrideMinutes" IS NULL OR "durationOverrideMinutes" > 0),

    CONSTRAINT "clinic_schedules_effective_range"
        CHECK ("effectiveTo" IS NULL OR "effectiveTo" >= "effectiveFrom"),

    CONSTRAINT "clinic_schedules_timezone_named"
        CHECK (length(btrim("timezone")) > 0)
);

COMMENT ON TABLE "public"."clinic_schedules" IS
    'DEC-001 working windows for a dentist. Two rows on one weekday is a split shift. '
    'supersedes the denormalised doctors.workingDays/startHour/endHour, which are left in '
    'place until the legacy clinic read model stops depending on them.';

COMMENT ON COLUMN "public"."clinic_schedules"."durationOverrideMinutes" IS
    'DEC-001 nullable per-dentist override of the service duration. NULL = use the duration the '
    'procedure declares. Never defaulted to the service value, so an override is always visible.';

COMMENT ON COLUMN "public"."clinic_schedules"."timezone" IS
    'The clinic''s IANA zone for this window. NOT NULL and undefaulted on purpose: a schedule '
    'with no zone has undefined DST behaviour, and guessing UTC would move real appointments.';

-- ---------------------------------------------------------------------
-- Step 2. clinic_time_off
-- ---------------------------------------------------------------------
CREATE TABLE "public"."clinic_time_off" (
    "id"                uuid NOT NULL,
    "organizationId"    uuid NOT NULL,
    "projectId"         uuid NOT NULL,
    "doctorId"          uuid NOT NULL,
    "startsOn"          date NOT NULL,
    "endsOn"            date NOT NULL,
    "reason"            text,
    "createdAt"         timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,

    CONSTRAINT "clinic_time_off_range_ordered"
        CHECK ("endsOn" >= "startsOn")
);

COMMENT ON TABLE "public"."clinic_time_off" IS
    'DEC-001 leave, holidays and training days. Inclusive of both ends, so a single day is '
    'startsOn = endsOn. Overlapping ranges are permitted and their union is applied.';

-- ---------------------------------------------------------------------
-- Step 3. clinic_resources and their capability tags (DEC-002)
-- ---------------------------------------------------------------------
CREATE TABLE "public"."clinic_resources" (
    "id"                uuid NOT NULL,
    "organizationId"    uuid NOT NULL,
    "projectId"         uuid NOT NULL,
    "name"              text NOT NULL,
    "isActive"          boolean DEFAULT true NOT NULL,
    "createdAt"         timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,

    CONSTRAINT "clinic_resources_name_named"
        CHECK (length(btrim("name")) > 0)
);

COMMENT ON TABLE "public"."clinic_resources" IS
    'DEC-002: chairs and equipment are SHARED between dentists, not owned by one. A resource '
    'therefore has no doctorId, which is the whole point of the decision. STAFF-ONLY: DEC-002 '
    'says resources never appear on the patient-facing booking surface, so every read of this '
    'table is a staff capability and none is reachable by a patient session.';

CREATE TABLE "public"."clinic_resource_capabilities" (
    "id"                uuid NOT NULL,
    "organizationId"    uuid NOT NULL,
    "resourceId"        uuid NOT NULL,
    "capability"        text NOT NULL,
    "createdAt"         timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,

    CONSTRAINT "clinic_resource_capabilities_capability_named"
        CHECK (length(btrim("capability")) > 0)
);

COMMENT ON TABLE "public"."clinic_resource_capabilities" IS
    'DEC-002 capability tags. A table and not a CHECK, so a clinic can add a capability as data '
    'rather than waiting for a migration. Deliberately UNSEEDED: DEC-002 does not enumerate the '
    'tags, and inventing clinical capability names is not engineering.';

-- ---------------------------------------------------------------------
-- Step 4. indexes
-- ---------------------------------------------------------------------
-- Unique INDEX (never a unique CONSTRAINT) on exactly the column list each
-- composite foreign key below references.
CREATE UNIQUE INDEX "clinic_schedules_id_organization_key"
    ON "public"."clinic_schedules" USING "btree" ("id", "organizationId");

CREATE UNIQUE INDEX "clinic_time_off_id_organization_key"
    ON "public"."clinic_time_off" USING "btree" ("id", "organizationId");

CREATE UNIQUE INDEX "clinic_resources_id_organization_key"
    ON "public"."clinic_resources" USING "btree" ("id", "organizationId");

CREATE UNIQUE INDEX "clinic_resource_capabilities_id_organization_key"
    ON "public"."clinic_resource_capabilities" USING "btree" ("id", "organizationId");

-- One tag once per resource per clinic.
CREATE UNIQUE INDEX "clinic_resource_capabilities_resource_capability_key"
    ON "public"."clinic_resource_capabilities" USING "btree" ("organizationId", "resourceId", "capability");

-- The availability query: one doctor, one weekday, the windows in force.
CREATE INDEX "clinic_schedules_organization_doctor_weekday_idx"
    ON "public"."clinic_schedules" USING "btree" ("organizationId", "doctorId", "weekday");

CREATE INDEX "clinic_time_off_organization_doctor_dates_idx"
    ON "public"."clinic_time_off" USING "btree" ("organizationId", "doctorId", "startsOn", "endsOn");

CREATE INDEX "clinic_resource_capabilities_organization_resource_idx"
    ON "public"."clinic_resource_capabilities" USING "btree" ("organizationId", "resourceId");

CREATE INDEX "clinic_resources_organization_active_idx"
    ON "public"."clinic_resources" USING "btree" ("organizationId", "projectId", "isActive");

-- ---------------------------------------------------------------------
-- Step 5. keys
-- ---------------------------------------------------------------------
ALTER TABLE "public"."clinic_schedules"
    ADD CONSTRAINT "clinic_schedules_pkey" PRIMARY KEY ("id");

ALTER TABLE "public"."clinic_time_off"
    ADD CONSTRAINT "clinic_time_off_pkey" PRIMARY KEY ("id");

ALTER TABLE "public"."clinic_resources"
    ADD CONSTRAINT "clinic_resources_pkey" PRIMARY KEY ("id");

ALTER TABLE "public"."clinic_resource_capabilities"
    ADD CONSTRAINT "clinic_resource_capabilities_pkey" PRIMARY KEY ("id");

-- THE COLUMN ORDER IS POSITIONAL AND LOAD-BEARING. ("doctorId",
-- "organizationId") binds doctorId -> doctors.id. Writing
-- ("organizationId", "doctorId") would bind organizationId -> doctors.id, which
-- is a different and wrong constraint.
ALTER TABLE "public"."clinic_schedules"
    ADD CONSTRAINT "clinic_schedules_doctor_tenant_fkey"
    FOREIGN KEY ("doctorId", "organizationId")
    REFERENCES "public"."doctors" ("id", "organizationId")
    ON DELETE CASCADE;

ALTER TABLE "public"."clinic_time_off"
    ADD CONSTRAINT "clinic_time_off_doctor_tenant_fkey"
    FOREIGN KEY ("doctorId", "organizationId")
    REFERENCES "public"."doctors" ("id", "organizationId")
    ON DELETE CASCADE;

ALTER TABLE "public"."clinic_resource_capabilities"
    ADD CONSTRAINT "clinic_resource_capabilities_resource_tenant_fkey"
    FOREIGN KEY ("resourceId", "organizationId")
    REFERENCES "public"."clinic_resources" ("id", "organizationId")
    ON DELETE CASCADE;

-- ---------------------------------------------------------------------
-- Step 6. post-conditions
-- ---------------------------------------------------------------------
DO $$
DECLARE
    schedule_tables integer;
    seeded_caps     integer;
    bad_window      integer;
    bad_weekday     integer;
    missing_timezone integer;
    bad_fk_order    integer;
    tenantless_idx  integer;
BEGIN
    SELECT count(*) INTO schedule_tables
      FROM information_schema.tables
     WHERE table_schema = 'public'
       AND table_name IN ('clinic_schedules', 'clinic_time_off',
                          'clinic_resources', 'clinic_resource_capabilities');

    IF schedule_tables <> 4 THEN
        RAISE EXCEPTION 'expected 4 schedule/resource tables, found %', schedule_tables;
    END IF;

    -- Capability tags are DELIBERATELY UNSEEDED, for the same reason the finding
    -- vocabulary is: DEC-002 does not enumerate them and a guess is not a tag.
    SELECT count(*) INTO seeded_caps
      FROM "public"."clinic_resource_capabilities";

    IF seeded_caps <> 0 THEN
        RAISE EXCEPTION 'resource capabilities were seeded with % rows, but DEC-002 does not enumerate them', seeded_caps;
    END IF;

    -- Two schedules for one doctor and weekday must not overlap, or the
    -- availability service would emit each overlapping minute twice and a
    -- receptionist could book the same slot from two windows.
    SELECT count(*) INTO bad_window
      FROM "public"."clinic_schedules" a
      JOIN "public"."clinic_schedules" b
        ON a."doctorId" = b."doctorId"
       AND a."organizationId" = b."organizationId"
       AND a."weekday" = b."weekday"
       AND a."id" < b."id"
       AND a."isActive" AND b."isActive"
       AND daterange(a."effectiveFrom",
                     coalesce(a."effectiveTo", a."effectiveFrom" + 36500), '[]')
        && daterange(b."effectiveFrom",
                     coalesce(b."effectiveTo", b."effectiveFrom" + 36500), '[]')
     WHERE a."startMinute" < b."endMinute"
       AND b."startMinute" < a."endMinute";

    IF bad_window <> 0 THEN
        RAISE EXCEPTION '% overlapping schedule windows for the same doctor and weekday', bad_window;
    END IF;

    SELECT count(*) INTO bad_weekday
      FROM "public"."clinic_schedules"
     WHERE "weekday" < 1 OR "weekday" > 7;

    IF bad_weekday <> 0 THEN
        RAISE EXCEPTION '% schedule rows carry a weekday outside ISO 1..7', bad_weekday;
    END IF;

    -- A schedule must never be interpretable as UTC by omission.
    SELECT count(*) INTO missing_timezone
      FROM "public"."clinic_schedules"
     WHERE "timezone" IS NULL OR btrim("timezone") = '';

    IF missing_timezone <> 0 THEN
        RAISE EXCEPTION '% schedule rows have no timezone', missing_timezone;
    END IF;

    SELECT count(*) INTO bad_fk_order
      FROM pg_constraint c
      JOIN unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord) ON true
      JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum
     WHERE c.contype = 'f'
       AND c.conrelid IN ('public.clinic_schedules'::regclass,
                          'public.clinic_time_off'::regclass,
                          'public.clinic_resource_capabilities'::regclass)
       AND c.conname LIKE '%_tenant_fkey'
       AND a.attname = 'organizationId'
       AND k.ord = 1;

    IF bad_fk_order > 0 THEN
        RAISE EXCEPTION '% schedule tenant foreign keys have organizationId first, which is the wrong order', bad_fk_order;
    END IF;

    SELECT count(*) INTO tenantless_idx
      FROM pg_indexes
     WHERE schemaname = 'public'
       AND tablename IN ('clinic_schedules', 'clinic_time_off',
                          'clinic_resources', 'clinic_resource_capabilities')
       AND indexdef NOT LIKE '%organizationId%'
       AND indexname NOT LIKE '%_pkey';

    IF tenantless_idx > 0 THEN
        RAISE EXCEPTION '% schedule/resource indexes do not lead with organizationId', tenantless_idx;
    END IF;
END
$$;