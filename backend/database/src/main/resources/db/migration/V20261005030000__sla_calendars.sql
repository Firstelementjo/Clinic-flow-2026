-- =====================================================================
-- V20261005030000__sla_calendars.sql
-- ADDITIVE, IDEMPOTENT-BY-VERSION, NON-DESTRUCTIVE.
--
-- VERSION NUMBERING -- 14 digits (yyyyMMddHHmmss), numerically greater than
--   20261005020000. See the header of
--   V20261005010000__workflow_definitions_and_versions.sql for why a 12-digit
--   version sorts BEFORE the 14-digit baseline.
--
-- WHY THIS MIGRATION EXISTS
--   Phase 6 needs SLA clocks that pause outside working hours, and the legacy
--   data cannot express that:
--
--     * employee_availability (baseline) stores PER-EMPLOYEE windows as
--       (dayOfWeek, startMinute, endMinute, timezone) with NO effective dating,
--       NO version and NO holiday awareness. It answers "is this one employee
--       available right now", which is a routing input.
--     * doctors.workingDays/startHour/endHour (baseline) are TEXT and describe
--       a clinic appointment book. Not a calendar, and not an SLA input.
--   An SLA needs something different and versioned: a named, tenant-scoped
--   calendar with windows and holidays that is EFFECTIVE FROM A DATE, so the
--   clock a ticket was measured against last March is still computable after
--   somebody edits the calendar. Phase-06 acceptance gate 3 ("business-hours /
--   DST tests") needs a real input for those tests, and this is it.
--
--   This migration does NOT touch employee_availability or doctors. Promoting
--   those to calendars is a data migration with real semantic questions (whose
--   windows become the organisation's?), it belongs to the module phase, and
--   the runbook's rule is: one concern per migration.
--
-- WHAT IT CREATES
--   1. enum  public.SlaCalendarState   (ACTIVE, SUPERSEDED, ARCHIVED)
--   2. table public.sla_calendars      -- the versioned business-hours calendar
--   3. table public.sla_calendar_windows      -- one calendar's weekly windows
--   4. table public.sla_calendar_holidays     -- one calendar's dated closures
--   5. enum  public.SlaClockType       (FIRST_RESPONSE, NEXT_RESPONSE, RESOLUTION)
--   6. table public.sla_policies       -- independent clocks per policy
--
-- WHY CALENDAR AND POLICY ARE SEPARATE, AND WHY THREE CLOCKS
--   * A calendar is a SHARED, versioned input: "the tenant's working week". One
--     calendar is referenced by many policies, and editing it must not rewrite
--     the SLA a customer was promised.
--   * A policy is a set of THREE INDEPENDENT budgets. Phase 6 requires
--     first-response, next-response and resolution clocks, and they are not the
--     same number: a clinic may answer within 15 minutes and still take a day
--     to resolve. Storing one "responseMinutes" column would make the second
--     clock inexpressible, which is why this is three nullable integer columns
--     rather than a duration plus a repeat count.
--   * SlaClockType is an enum because the three clocks are a closed contract the
--     scheduler branches on, not a growing vocabulary.
--
-- THE CLOCK-ORDERING INVARIANT (the CHECK this phase most cares about)
--   A resolution budget must never be SHORTER than its first-response budget.
--   If it were, a policy would resolve before it had even answered, and the SLA
--   engine would breach the resolution clock on every single healthy ticket --
--   a silent, permanent false alarm that trains operators to ignore the SLA
--   queue. It is enforced as a table CHECK rather than left to application
--   validation, because the SLA policy editor and the batch importer are two
--   writers with two code paths and the invariant is a property of the data,
--   not of either writer. Phase 6a has a negative test for it.
--
-- TIME ZONES AND DST -- WHAT THE SCHEMA DECIDES AND WHAT IT CANNOT
--   Columns are timestamp(3) WITHOUT time zone, like all 98 baseline timestamp
--   columns; V6 asserts no timestamptz anywhere and that contract is not
--   relaxed here.
--   * startMinute/endMinute are MINUTES FROM LOCAL MIDNIGHT (0..1440), exactly
--     the shape legacy employee_availability already uses, so the legacy
--     routing code's window semantics carry over unchanged.
--   * timezone is the IANA zone the minutes are LOCAL TO, stored per calendar
--     and per window. This is how DST is handled: a window is a local wall-clock
--     range, so "09:00-17:00 Europe/London" is 09:00 local on both sides of a
--     DST change and the ENGINE resolves the offset. Storing a UTC range would
--     make a working week shift by an hour twice a year, which is the bug the
--     legacy tests in packages/routing/src/working-hours.test.ts exist to catch.
--   * There is NO UTC-offset column, and deliberately no PostgreSQL
--     `timezone`-typed column: those two disagree on DST days, and a column type
--     that silently means something different on half the year is worse than
--     text with a documented contract. The engine validates the string against
--     the tz database; the schema stores it verbatim.
--   * A CHECK on the minute bounds is the one thing the schema CAN enforce, and
--     it does: 0 <= startMinute AND endMinute <= 1440 AND endMinute > startMinute.
--     The last clause is a same-day-window invariant: a window whose end is not
--     after its start is either empty or an overnight shift, and an overnight
--     shift must be expressed as TWO windows, because "this shift runs across
--     midnight" is ambiguous the moment the window's own dayOfWeek is read back
--     for a DST week. Making it unexpressible is the point.
--
-- projectId -- NULLABLE, ORGANISATION-WIDE
--   A working week is an organisation-wide fact; forcing one per project would
--   mean three projects quietly disagreeing about when the clinic is open.
--   organizationId is NOT NULL on all five tables (FR-1.2) and a NULL projectId
--   means ORGANISATION-WIDE, never unknown scope. V11 section 4d lists the three
--   tables explicitly so the exemption cannot widen unnoticed. sla_calendar_
--   windows and sla_calendar_holidays have NO projectId column at all: they are
--   children of a calendar and inherit its scope, exactly like ai_run_steps
--   inherits from ai_runs in the baseline. V7 classifies them as calendar-scoped.
--
-- TIMESTAMPS
--   timestamp(3) WITHOUT time zone everywhere. Defaults are
--   (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'); updatedAt has no default, like every
--   mutable table in the baseline. Bind LocalDateTime, never
--   Instant/OffsetDateTime.
--
-- effectiveFrom / supersededById -- WHY EFFECTIVE DATES ARE NOT TIMESTAMPS
--   effectiveFrom is a DATE. The question it answers is "which calendar applied
--   on 2026-03-14", and a date is the smallest unit that can answer it. Making
--   it a timestamp would imply a second-of-a-day effect the engine does not
--   implement, and would put a timestamptz-shaped trap in a schema that has
--   none. A DATE is also naturally NOT NULL: a calendar that is not yet in
--   effect is a DRAFT row, not a row with no date.
--
-- INDEX RATIONALE (FR-1.3)
--   sla_calendars_organizationId_key_version_key UNIQUE
--       ("organizationId","key","version")
--       The natural key. Tenant-scoped, and version INCLUDED so the versioning
--       is enforced by the same index that names the calendar -- one tenant
--       cannot publish two "different" version 3s.
--   sla_calendars_one_active_per_key UNIQUE ("organizationId","key")
--       WHERE "state" = 'ACTIVE'
--       At most one live calendar per key, per tenant. Same backstop as
--       workflow_versions_one_active_per_definition.
--   sla_calendars_organizationId_effectiveFrom_idx
--       ("organizationId","effectiveFrom")
--       "Which calendar was in effect on this date" -- the query that makes a
--       historical SLA recomputable, tenant-led so one tenant's calendar churn
--       cannot hide another's.
--   sla_calendar_windows_calendar_day_idx
--       ("calendarId","dayOfWeek")
--       The read the engine does on every clock evaluation: this calendar's
--       windows for this weekday. NOT tenant-led on purpose and documented as
--       such: it is only ever reached AFTER an
--       organizationId-filtered calendar row has been read, so it cannot be
--       entered without a tenant in hand, and its columns carry no tenant data.
--       It is added to the documented tenant-less allow-list (V11 section 5a)
--       and to the fixture's allow-list, so a fourth such index cannot appear by
--       accident.
--   sla_calendar_holidays_calendar_date_key UNIQUE
--       ("calendarId","holidayDate")
--       One closure per date per calendar, and the clock evaluation read
--       ("is this date closed, and what is it called") at the same time. The
--       key is ("calendarId", ...) rather than ("organizationId", ...)
--       deliberately: a holiday calendar is already uniquely identified by its
--       calendar row, and that calendar row is already tenant-scoped by
--       sla_calendars_organizationId_key_version_key. Adding organizationId here
--       would duplicate it without narrowing anything, and a duplicate closure
--       must not be insertable or the clock would pause twice for one day.
--   sla_calendars_id_organizationId_key  UNIQUE ("id","organizationId")
--       Not a business key at all -- it exists so
--       sla_policies_calendar_tenant_fkey can be a COMPOSITE foreign key and
--       therefore refuse a policy that points at another tenant's calendar. It
--       is a unique INDEX rather than a UNIQUE CONSTRAINT specifically so V4's
--       unique_constraints = 0 invariant survives.
--   sla_policies_organizationId_projectId_key_key UNIQUE
--       ("organizationId","projectId","key")
--       The policy natural key. projectId participates even though it is
--       nullable: PostgreSQL treats NULLs as distinct in a unique index by
--       default, so an organisation-wide policy is keyed on
--       ("organizationId", NULL, key) and will not collide with a project policy.
--       Recorded here because it is a real, deliberate property of the index and
--       not an oversight.
--   sla_policies_organizationId_projectId_enabled_idx
--       ("organizationId","projectId","isEnabled","priority")
--       Which policies apply, in explicit priority order. Priority is a column
--       because alphabetical ordering is forbidden (plan.md hard rule).
--
--   Deliberately NO tenant-less index that carries customer content, and no
--   index on conversation_id: this migration creates no SLA-tick table. Whether
--   per-conversation breach state is materialised is a Phase-6 decision that
--   belongs with the module that implements the scheduler; the schema here
--   deliberately stops at the inputs.
--
-- SAFETY
--   * CREATE TYPE / CREATE TABLE / ALTER TABLE ADD CONSTRAINT only. No DROP, no
--     RENAME, no ALTER of an existing object, no data change, no TRUNCATE, no
--     UPDATE of a legacy row. Nothing here reads employee_availability or
--     doctors.
--   * Strict CREATE TABLE, not IF NOT EXISTS.
--   * FKs only where the parent is guaranteed: organizations, projects, users,
--     and the intra-slice calendar -> windows/holidays and calendar -> policy
--     edges. supersededById is SET NULL, not CASCADE: an audit pointer that
--     vanishes with the row it points at is worse than a visible dangling one.
--   * Rollback: DROP TABLE sla_policies, DROP TABLE sla_calendar_holidays,
--     DROP TABLE sla_calendar_windows, DROP TABLE sla_calendars, DROP TYPE
--     SlaClockType, DROP TYPE SlaCalendarState -- rehearsed on a throwaway
--     database only (ops/flyway/rollback/undo_phase6a.sql).
--   * Index and constraint names stay under PostgreSQL's 63-byte limit.
--   * The guard looks indexes up in pg_indexes, NOT with to_regclass().
--   * Every identifier is schema-qualified because the baseline sets
--     search_path = '' when the whole chain is piped into ONE psql session.
-- =====================================================================

-- DRAFT IS THE FIRST VALUE AND IT IS NOT OPTIONAL. A calendar version that is
-- being authored must be storable before it is in effect, or the author has to
-- activate a calendar that does not exist yet. Without DRAFT the only way to
-- author version 2 of a key would be to insert it as ACTIVE -- which the
-- partial unique index sla_calendars_one_active_per_key refuses while version 1
-- is live. The throwaway rehearsal found exactly that: inserting version 2 as
-- ACTIVE failed on the index with no way forward. The real fix is a DRAFT
-- state, not a weaker index.
CREATE TYPE "public"."SlaCalendarState" AS ENUM (
    'DRAFT',
    'ACTIVE',
    'SUPERSEDED',
    'ARCHIVED'
);

CREATE TYPE "public"."SlaClockType" AS ENUM (
    'FIRST_RESPONSE',
    'NEXT_RESPONSE',
    'RESOLUTION'
);

-- ---------------------------------------------------------------------
-- sla_calendars: a named, versioned, effective-dated working week.
-- ---------------------------------------------------------------------
CREATE TABLE "public"."sla_calendars" (
    "id"              uuid NOT NULL,
    "organizationId"  uuid NOT NULL,
    -- NULL = organisation-wide working week. Never "unknown scope".
    "projectId"       uuid,
    "key"             text NOT NULL,
    "name"            text NOT NULL,
    -- Monotonic, per key, per tenant. Enforced by the unique index below.
    "version"         integer NOT NULL,
    -- Defaults to DRAFT, not ACTIVE. A calendar row must be authored before it is
    -- in effect, and the default must be the state that is safe to write without
    -- thinking. Activation is an explicit, audited act.
    "state"           "public"."SlaCalendarState" DEFAULT 'DRAFT'::"public"."SlaCalendarState" NOT NULL,
    -- The zone the minutes in this calendar are LOCAL TO. IANA name, stored
    -- verbatim. See the DST section in the header.
    "timezone"        text NOT NULL,
    "effectiveFrom"   date NOT NULL,
    "effectiveTo"     date,
    "supersededById"  uuid,
    "createdByUserId" uuid,
    "createdAt"       timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    "updatedAt"       timestamp(3) without time zone NOT NULL,
    CONSTRAINT "sla_calendars_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "sla_calendars_version_check"
        CHECK ("version" >= 1),
    -- A calendar that has ended must say when it ended, and a calendar that has
    -- not ended must not claim an end date. Getting this wrong in the other
    -- direction makes a clock silently stop pausing.
    CONSTRAINT "sla_calendars_effective_range_check"
        CHECK (("effectiveTo" IS NULL) OR ("effectiveTo" >= "effectiveFrom")),
    -- A superseded calendar must name its successor.
    CONSTRAINT "sla_calendars_supersede_check"
        CHECK (("state" <> 'SUPERSEDED'::"public"."SlaCalendarState")
               OR "supersededById" IS NOT NULL),
    -- A DRAFT calendar is not in effect, so it cannot have an end date: an
    -- effective range on a draft describes a period the calendar was never
    -- promised for.
    CONSTRAINT "sla_calendars_draft_range_check"
        CHECK (("state" <> 'DRAFT'::"public"."SlaCalendarState") OR "effectiveTo" IS NULL),
    CONSTRAINT "sla_calendars_key_check"
        CHECK (length(btrim("key")) > 0),
    CONSTRAINT "sla_calendars_timezone_check"
        CHECK (length(btrim("timezone")) > 0)
);

ALTER TABLE ONLY "public"."sla_calendars"
    ADD CONSTRAINT "sla_calendars_organizationId_fkey"
    FOREIGN KEY ("organizationId") REFERENCES "public"."organizations"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."sla_calendars"
    ADD CONSTRAINT "sla_calendars_projectId_fkey"
    FOREIGN KEY ("projectId") REFERENCES "public"."projects"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."sla_calendars"
    ADD CONSTRAINT "sla_calendars_createdByUserId_fkey"
    FOREIGN KEY ("createdByUserId") REFERENCES "public"."users"("id")
    ON UPDATE CASCADE ON DELETE SET NULL;

-- ---------------------------------------------------------------------
-- sla_calendar_windows: the weekly windows of ONE calendar version.
-- NO organizationId/projectId: scope is inherited from the calendar, exactly
-- like ai_run_steps inherits from ai_runs in the verified baseline. These are
-- configuration rows with no tenant content of their own.
-- ---------------------------------------------------------------------
CREATE TABLE "public"."sla_calendar_windows" (
    "id"          uuid NOT NULL,
    "calendarId"  uuid NOT NULL,
    -- 0 = Sunday .. 6 = Saturday, matching the legacy employee_availability and
    -- packages/routing working-hours semantics exactly, so the legacy window
    -- comparison logic carries over without translation.
    "dayOfWeek"   integer NOT NULL,
    "startMinute" integer NOT NULL,
    "endMinute"   integer NOT NULL,
    "label"       text,
    "createdAt"   timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    CONSTRAINT "sla_calendar_windows_pkey" PRIMARY KEY ("id"),
    -- Real minute-of-day bounds. 1440 is midnight at the end of the day; 1441
    -- is not a minute and would silently mean "the day after".
    CONSTRAINT "sla_calendar_windows_start_minute_check"
        CHECK ("startMinute" >= 0 AND "startMinute" < 1440),
    CONSTRAINT "sla_calendar_windows_end_minute_check"
        CHECK ("endMinute" > 0 AND "endMinute" <= 1440),
    -- THE SAME-DAY WINDOW INVARIANT. A window whose end is not after its start
    -- is empty or overnight; both must be expressed as two windows. See the
    -- header for why an overnight shift as one row is ambiguous across a DST
    -- week. Phase 6a has a negative test for exactly this.
    CONSTRAINT "sla_calendar_windows_same_day_check"
        CHECK ("endMinute" > "startMinute"),
    CONSTRAINT "sla_calendar_windows_day_of_week_check"
        CHECK ("dayOfWeek" >= 0 AND "dayOfWeek" <= 6)
);

ALTER TABLE ONLY "public"."sla_calendar_windows"
    ADD CONSTRAINT "sla_calendar_windows_calendarId_fkey"
    FOREIGN KEY ("calendarId") REFERENCES "public"."sla_calendars"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

-- ---------------------------------------------------------------------
-- sla_calendar_holidays: dated closures of ONE calendar version.
-- Same inherited-scope decision as sla_calendar_windows.
-- ---------------------------------------------------------------------
CREATE TABLE "public"."sla_calendar_holidays" (
    "id"          uuid NOT NULL,
    "calendarId"  uuid NOT NULL,
    "holidayDate" date NOT NULL,
    "label"       text NOT NULL,
    -- A partial closure: 09:00-13:00 still pauses the clock for those hours.
    -- NULL = the whole day.
    "startMinute" integer,
    "endMinute"   integer,
    "createdAt"   timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    CONSTRAINT "sla_calendar_holidays_pkey" PRIMARY KEY ("id"),
    -- A partial closure carries BOTH bounds or neither. Half a closure is not a
    -- state the clock evaluator can act on.
    CONSTRAINT "sla_calendar_holidays_partial_pair_check"
        CHECK (("startMinute" IS NULL) = ("endMinute" IS NULL)),
    CONSTRAINT "sla_calendar_holidays_start_minute_check"
        CHECK ("startMinute" IS NULL
               OR ("startMinute" >= 0 AND "startMinute" < 1440)),
    CONSTRAINT "sla_calendar_holidays_end_minute_check"
        CHECK ("endMinute" IS NULL
               OR ("endMinute" > 0 AND "endMinute" <= 1440)),
    -- The same same-day rule as windows.
    CONSTRAINT "sla_calendar_holidays_same_day_check"
        CHECK ("startMinute" IS NULL OR "endMinute" > "startMinute"),
    CONSTRAINT "sla_calendar_holidays_label_check"
        CHECK (length(btrim("label")) > 0)
);

ALTER TABLE ONLY "public"."sla_calendar_holidays"
    ADD CONSTRAINT "sla_calendar_holidays_calendarId_fkey"
    FOREIGN KEY ("calendarId") REFERENCES "public"."sla_calendars"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

-- ---------------------------------------------------------------------
-- sla_policies: three INDEPENDENT clocks, an optional calendar, a priority.
-- ---------------------------------------------------------------------
CREATE TABLE "public"."sla_policies" (
    "id"                    uuid NOT NULL,
    "organizationId"        uuid NOT NULL,
    "projectId"             uuid,
    "key"                   text NOT NULL,
    "name"                  text NOT NULL,
    "description"           text,
    -- NULL = applies to every conversation of the tenant. Deliberate.
    "defaultForPriority"    text,
    "priority"              integer DEFAULT 100 NOT NULL,
    "isEnabled"             boolean DEFAULT true NOT NULL,
    -- Which of the three clocks this policy governs. One row per clock would
    -- triple the rows and make "the first-response budget" a three-row join;
    -- one row with three nullable budgets is the shape the operator edits. The
    -- enum records the vocabulary of the three clocks the scheduler reads.
    "clockType"             "public"."SlaClockType" DEFAULT 'RESOLUTION'::"public"."SlaClockType" NOT NULL,
    -- THE THREE INDEPENDENT CLOCKS. Nullable: a policy may promise a resolution
    -- budget without promising a next-response budget, and forcing 0 would be a
    -- silent "immediately breached".
    "firstResponseMinutes"  integer,
    "nextResponseMinutes"   integer,
    "resolutionMinutes"     integer,
    -- Which calendar pauses these clocks. NULL = 24/7, which is a legitimate
    -- choice for a digital channel and must be expressible without inventing a
    -- 00:00-24:00 window.
    "calendarId"            uuid,
    "warnBeforeMinutes"     integer,
    "escalateAfterMinutes"  integer,
    "createdByUserId"       uuid,
    "createdAt"             timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    "updatedAt"             timestamp(3) without time zone NOT NULL,
    CONSTRAINT "sla_policies_pkey" PRIMARY KEY ("id"),
    -- Every budget is minutes, and a zero or negative budget is a breach at
    -- birth. A NULL budget means "not promised".
    CONSTRAINT "sla_policies_first_response_minutes_check"
        CHECK ("firstResponseMinutes" IS NULL OR "firstResponseMinutes" > 0),
    CONSTRAINT "sla_policies_next_response_minutes_check"
        CHECK ("nextResponseMinutes" IS NULL OR "nextResponseMinutes" > 0),
    CONSTRAINT "sla_policies_resolution_minutes_check"
        CHECK ("resolutionMinutes" IS NULL OR "resolutionMinutes" > 0),
    -- THE CLOCK-ORDERING INVARIANT. See the header: a resolution budget shorter
    -- than the first-response budget breaches on every healthy ticket and trains
    -- operators to ignore the SLA queue. Negated expression because the check
    -- is "is there an ordering violation", and writing it that way means the
    -- NULL cases fall out correctly instead of needing three separate ORs.
    CONSTRAINT "sla_policies_clock_ordering_check"
        CHECK (NOT (
              ("firstResponseMinutes" IS NOT NULL
               AND "resolutionMinutes" IS NOT NULL
               AND "resolutionMinutes" < "firstResponseMinutes")
           OR ("firstResponseMinutes" IS NOT NULL
               AND "nextResponseMinutes" IS NOT NULL
               AND "nextResponseMinutes" < "firstResponseMinutes")
        )),
    -- A policy must promise at least one clock, otherwise the scheduler has
    -- nothing to evaluate and the row is dead configuration.
    CONSTRAINT "sla_policies_at_least_one_clock_check"
        CHECK ("firstResponseMinutes" IS NOT NULL
               OR "nextResponseMinutes" IS NOT NULL
               OR "resolutionMinutes" IS NOT NULL),
    -- The warning/escalation lead times belong to the clock they precede.
    CONSTRAINT "sla_policies_warn_before_check"
        CHECK ("warnBeforeMinutes" IS NULL OR "warnBeforeMinutes" >= 0),
    CONSTRAINT "sla_policies_escalate_after_check"
        CHECK ("escalateAfterMinutes" IS NULL OR "escalateAfterMinutes" >= 0),
    -- An escalation must be scheduled at or before the clock it escalates, or
    -- it fires after the breach it was meant to prevent.
    CONSTRAINT "sla_policies_escalate_not_after_clock_check"
        CHECK (NOT (
              ("escalateAfterMinutes" IS NOT NULL
               AND "resolutionMinutes" IS NOT NULL
               AND "escalateAfterMinutes" > "resolutionMinutes")
        )),
    CONSTRAINT "sla_policies_priority_check"
        CHECK ("priority" >= 0),
    CONSTRAINT "sla_policies_key_check"
        CHECK (length(btrim("key")) > 0)
);

ALTER TABLE ONLY "public"."sla_policies"
    ADD CONSTRAINT "sla_policies_organizationId_fkey"
    FOREIGN KEY ("organizationId") REFERENCES "public"."organizations"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."sla_policies"
    ADD CONSTRAINT "sla_policies_projectId_fkey"
    FOREIGN KEY ("projectId") REFERENCES "public"."projects"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

-- The calendar is optional (24/7: calendarId is NULL and the clock never pauses)
-- but when it IS present it must be a real calendar row.
--
-- A single-column FK would guarantee the calendar EXISTS and nothing more; a
-- policy that resolved ANOTHER TENANT's working week would pause the wrong clock
-- and no constraint would object. The composite FK below closes that, the same
-- way workflow_runs_version_tenant_fkey does for the workflow body. The target
-- pair is covered by a unique INDEX, not a UNIQUE CONSTRAINT, so V4's
-- unique_constraints = 0 invariant holds.
--
-- ON DELETE is RESTRICT, not SET NULL, and the reason is load-bearing: a
-- composite SET NULL would try to null organizationId as well, which is NOT
-- NULL, so PostgreSQL would reject the action anyway; and even if it could,
-- silently detaching a calendar would turn a business-hours SLA into a 24/7 one
-- without a word. A calendar that still drives a policy must be SUPERSEDED, not
-- deleted. RESTRICT is also in V4's safe-on-delete set ('r').
CREATE UNIQUE INDEX "sla_calendars_id_organizationId_key"
    ON "public"."sla_calendars" USING "btree" ("id", "organizationId");

ALTER TABLE ONLY "public"."sla_policies"
    ADD CONSTRAINT "sla_policies_calendar_tenant_fkey"
    FOREIGN KEY ("calendarId", "organizationId")
    REFERENCES "public"."sla_calendars"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

ALTER TABLE ONLY "public"."sla_policies"
    ADD CONSTRAINT "sla_policies_createdByUserId_fkey"
    FOREIGN KEY ("createdByUserId") REFERENCES "public"."users"("id")
    ON UPDATE CASCADE ON DELETE SET NULL;

-- Added after sla_calendars exists: the second half of the calendar pair.
ALTER TABLE ONLY "public"."sla_calendars"
    ADD CONSTRAINT "sla_calendars_supersededById_fkey"
    FOREIGN KEY ("supersededById") REFERENCES "public"."sla_calendars"("id")
    ON UPDATE CASCADE ON DELETE SET NULL;

-- ---------------------------------------------------------------------
-- indexes
-- ---------------------------------------------------------------------
-- Natural key, tenant-scoped, version included.
CREATE UNIQUE INDEX "sla_calendars_organizationId_key_version_key"
    ON "public"."sla_calendars" USING "btree" ("organizationId", "key", "version");

-- At most one live calendar per key per tenant.
CREATE UNIQUE INDEX "sla_calendars_one_active_per_key"
    ON "public"."sla_calendars" USING "btree" ("organizationId", "key")
    WHERE "state" = 'ACTIVE'::"public"."SlaCalendarState";

-- "Which calendar applied on this date" -- what makes a historical SLA
-- recomputable after the calendar is edited.
CREATE INDEX "sla_calendars_organizationId_effectiveFrom_idx"
    ON "public"."sla_calendars" USING "btree" ("organizationId", "effectiveFrom");

-- The per-evaluation window read. NOT tenant-led, and in the documented
-- tenant-less allow-list: it is only reachable after an organizationId-filtered
-- calendar row has been read, and it carries no tenant data. Asserted by V11
-- section 5a and by the fixture's allow-list.
CREATE INDEX "sla_calendar_windows_calendar_day_idx"
    ON "public"."sla_calendar_windows" USING "btree" ("calendarId", "dayOfWeek");

-- One closure per date per calendar.
CREATE UNIQUE INDEX "sla_calendar_holidays_calendar_date_key"
    ON "public"."sla_calendar_holidays" USING "btree" ("calendarId", "holidayDate");

-- No separate covering index for the label. The unique index above already
-- serves the only read this table has ("is holidayDate closed, and what is it
-- called"), and a configuration table of a few dozen rows does not need a
-- covering index to avoid a heap fetch. Adding one would ALSO have added a
-- second tenant-less index to V9's global bucket for no measurable gain.

-- Policy natural key. projectId participates even though it is nullable;
-- PostgreSQL treats NULLs as distinct in a unique index, so an organisation-wide
-- policy is keyed on ("organizationId", NULL, key) and cannot collide with a
-- project policy. Deliberate, and asserted by the fixture.
CREATE UNIQUE INDEX "sla_policies_organizationId_projectId_key_key"
    ON "public"."sla_policies" USING "btree" ("organizationId", "projectId", "key");

-- Which policies apply, in explicit priority order.
CREATE INDEX "sla_policies_organizationId_projectId_enabled_idx"
    ON "public"."sla_policies" USING "btree"
       ("organizationId", "projectId", "isEnabled", "priority");

-- ---------------------------------------------------------------------
-- Post-condition guard.
-- Index existence is checked in pg_indexes, NOT with to_regclass().
-- ---------------------------------------------------------------------
DO $$
DECLARE
    missing text;
BEGIN
    SELECT string_agg(x, ', ')
      INTO missing
      FROM unnest(ARRAY[
               'sla_calendars_organizationId_key_version_key',
               'sla_calendars_one_active_per_key',
               'sla_calendars_organizationId_effectiveFrom_idx',
               'sla_calendars_id_organizationId_key',
               'sla_calendar_windows_calendar_day_idx',
               'sla_calendar_holidays_calendar_date_key',
               'sla_policies_organizationId_projectId_key_key',
               'sla_policies_organizationId_projectId_enabled_idx'
           ]) AS x
     WHERE NOT EXISTS (SELECT 1 FROM pg_indexes
                        WHERE schemaname = 'public' AND indexname = x);

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'V20261005030000 failed: missing SLA calendar/policy indexes: %', missing;
    END IF;

    -- Truncation detection, done correctly: every index that exists on these
    -- tables must be one this file declared by its exact name. A name longer
    -- than 63 bytes is silently truncated and can never match; a legitimate
    -- 63-byte name is not flagged. The first attempt at this guard was
    -- `length(indexname) = 63`, which the throwaway rehearsal proved WRONG:
    -- exactly 63 bytes is legal and untruncated.
    IF EXISTS (SELECT 1 FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename IN ('sla_calendars', 'sla_calendar_windows',
                                    'sla_calendar_holidays', 'sla_policies')
                  AND indexname NOT IN (
                      'sla_calendars_pkey',
                      'sla_calendars_organizationId_key_version_key',
                      'sla_calendars_one_active_per_key',
                      'sla_calendars_organizationId_effectiveFrom_idx',
                      'sla_calendars_id_organizationId_key',
                      'sla_calendar_windows_pkey',
                      'sla_calendar_windows_calendar_day_idx',
                      'sla_calendar_holidays_pkey',
                      'sla_calendar_holidays_calendar_date_key',
                      'sla_policies_pkey',
                      'sla_policies_organizationId_projectId_key_key',
                      'sla_policies_organizationId_projectId_enabled_idx')) THEN
        RAISE EXCEPTION
            'V20261005030000 failed: an unexpected index exists on an SLA table -- most likely '
            'a name longer than 63 bytes was silently truncated.';
    END IF;

    IF (SELECT string_agg(e.enumlabel, ',' ORDER BY e.enumsortorder)
          FROM pg_type t
          JOIN pg_enum e ON e.enumtypid = t.oid
          JOIN pg_namespace n ON n.oid = t.typnamespace
         WHERE n.nspname = 'public' AND t.typname = 'SlaCalendarState')
       IS DISTINCT FROM 'DRAFT,ACTIVE,SUPERSEDED,ARCHIVED' THEN
        RAISE EXCEPTION
            'V20261005030000 failed: public.SlaCalendarState value list drifted.';
    END IF;

    IF (SELECT string_agg(e.enumlabel, ',' ORDER BY e.enumsortorder)
          FROM pg_type t
          JOIN pg_enum e ON e.enumtypid = t.oid
          JOIN pg_namespace n ON n.oid = t.typnamespace
         WHERE n.nspname = 'public' AND t.typname = 'SlaClockType')
       IS DISTINCT FROM 'FIRST_RESPONSE,NEXT_RESPONSE,RESOLUTION' THEN
        RAISE EXCEPTION
            'V20261005030000 failed: public.SlaClockType value list drifted.';
    END IF;

    -- FR-1.2. sla_calendar_windows and sla_calendar_holidays have no
    -- organizationId AT ALL (scope is inherited from the calendar), so this
    -- check must not merely say "not null" -- it must also say the column is
    -- absent, because silently adding a nullable organizationId to them would be
    -- the more dangerous regression.
    IF EXISTS (SELECT 1
                 FROM pg_attribute a
                 JOIN pg_class c ON c.oid = a.attrelid
                 JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public'
                  AND c.relname IN ('sla_calendars', 'sla_policies')
                  AND a.attname = 'organizationId' AND NOT a.attnotnull) THEN
        RAISE EXCEPTION
            'V20261005030000 failed: "organizationId" must be NOT NULL on sla_calendars and sla_policies (FR-1.2).';
    END IF;

    IF EXISTS (SELECT 1
                 FROM pg_attribute a
                 JOIN pg_class c ON c.oid = a.attrelid
                 JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public'
                  AND c.relname IN ('sla_calendar_windows', 'sla_calendar_holidays')
                  AND a.attname IN ('organizationId', 'projectId')
                  AND a.attnum > 0 AND NOT a.attisdropped) THEN
        RAISE EXCEPTION
            'V20261005030000 failed: sla_calendar_windows / sla_calendar_holidays must inherit '
            'scope from their calendar and must not carry their own tenant columns.';
    END IF;

    -- V6 contract: no timestamptz anywhere in this slice.
    IF EXISTS (SELECT 1
                 FROM pg_attribute a
                 JOIN pg_class c ON c.oid = a.attrelid
                 JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public'
                  AND c.relname IN ('sla_calendars', 'sla_calendar_windows',
                                    'sla_calendar_holidays', 'sla_policies')
                  AND a.attnum > 0 AND NOT a.attisdropped
                  AND a.atttypid = 'timestamptz'::regtype) THEN
        RAISE EXCEPTION
            'V20261005030000 failed: an SLA table contains a timestamptz column; '
            'every timestamp in this schema is timestamp(3) without time zone.';
    END IF;

    -- Every UTC-writing default must say UTC.
    IF EXISTS (SELECT 1
                 FROM pg_attrdef d
                 JOIN pg_class c ON c.oid = d.adrelid
                 JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public'
                  AND c.relname IN ('sla_calendars', 'sla_calendar_windows',
                                    'sla_calendar_holidays', 'sla_policies')
                  AND pg_get_expr(d.adbin, d.adrelid) LIKE '%CURRENT_TIMESTAMP%'
                  AND pg_get_expr(d.adbin, d.adrelid) NOT LIKE '%AT TIME ZONE%') THEN
        RAISE EXCEPTION
            'V20261005030000 failed: a default uses a session-local CURRENT_TIMESTAMP; '
            'every new default must be (CURRENT_TIMESTAMP AT TIME ZONE ''UTC'').';
    END IF;

    -- The composite tenant key and the composite FK that uses it. Without both,
    -- a policy could resolve another tenant's working week and pause the wrong
    -- clock, which is the highest-value cross-tenant negative in this slice.
    IF NOT EXISTS (SELECT 1 FROM pg_index i
                    JOIN pg_class c ON c.oid = i.indexrelid
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                   WHERE n.nspname = 'public'
                     AND c.relname = 'sla_calendars_id_organizationId_key'
                     AND i.indisunique) THEN
        RAISE EXCEPTION
            'V20261005030000 failed: sla_calendars_id_organizationId_key is missing or not UNIQUE, '
            'so the policy->calendar tenant invariant cannot be enforced.';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint con
                     JOIN pg_class c ON c.oid = con.conrelid
                     JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'public'
                      AND con.conname = 'sla_policies_calendar_tenant_fkey'
                      AND con.contype = 'f'
                      AND con.confdeltype = 'r') THEN
        RAISE EXCEPTION
            'V20261005030000 failed: sla_policies_calendar_tenant_fkey must exist as a composite '
            'FK with ON DELETE RESTRICT; a calendar that still drives a policy is superseded, '
            'never deleted.';
    END IF;

    -- Uniqueness in this schema is carried by unique INDEXES, never UNIQUE
    -- CONSTRAINTS (V4: unique_constraints = 0).
    IF EXISTS (SELECT 1 FROM pg_constraint con
                JOIN pg_class c ON c.oid = con.conrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
               WHERE n.nspname = 'public'
                 AND c.relname IN ('sla_calendars', 'sla_calendar_windows',
                                   'sla_calendar_holidays', 'sla_policies')
                 AND con.contype = 'u') THEN
        RAISE EXCEPTION
            'V20261005030000 failed: an SLA table carries a UNIQUE constraint; uniqueness must '
            'be a unique INDEX (V4 invariant).';
    END IF;

    -- The three independent clocks must all exist as columns. If a future
    -- migration collapses them, this guard is the thing that says the Phase-6
    -- contract was broken.
    IF (SELECT count(*) FROM pg_attribute a
          JOIN pg_class c ON c.oid = a.attrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public' AND c.relname = 'sla_policies'
           AND a.attname IN ('firstResponseMinutes', 'nextResponseMinutes',
                             'resolutionMinutes')
           AND a.attnum > 0 AND NOT a.attisdropped) <> 3 THEN
        RAISE EXCEPTION
            'V20261005030000 failed: sla_policies must carry three independent clock columns.';
    END IF;
END
$$;