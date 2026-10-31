package com.firstelement.crm.clinic.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.crm.CrmFixture;
import com.firstelement.crm.CrmIntegrationTestBase;
import com.firstelement.crm.clinic.ClinicScope;
import com.firstelement.security.query.TenantQueryGuard;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Availability, under the DEC-001 rule that a buffer is padding and not patient time.
 *
 * <p>The centre of this file is {@code anExistingBookingOccupiesItsOwnBuffer}. It is written
 * against the defect it closes: the legacy AI path widened CANDIDATES by the buffer and left
 * existing bookings at their bare duration, which is how a chair ends up double-booked across a
 * procedure that carries a buffer.
 */
@SpringBootTest(classes = com.firstelement.crm.CrmTestApplication.class)
class AvailabilityServiceIntegrationTest extends CrmIntegrationTestBase {

    /** A Monday, so the ISO weekday is 1 and the arithmetic is easy to read. */
    private static final LocalDate MONDAY = LocalDate.of(2026, 3, 2);
    private static final int NINE = 9 * 60;
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);

    @Autowired
    private AvailabilityService availability;

    @Autowired
    private ScheduleRepository repository;

    @Autowired
    private CrmFixture fixture;

    @Autowired
    private TenantQueryGuard guard;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    void resetDatabase() {
        fixture.reset();
    }

    // ------------------------------------------------------------------
    // DEC-001: buffer is padding
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a 30 minute service with a 10 minute buffer steps by 40, patient booked for 30")
    void theBufferOccupiesTheScheduleWithoutBeingPatientTime() {
        ClinicScope scope = tenantAScope();
        UUID doctor = seedDoctor("Dr Ada");
        addWindow(scope, doctor, 1, NINE, 12 * 60);

        var slots = availability.slotsFor(scope, doctor, MONDAY, 30, 10, null);

        assertThat(slots).extracting(ScheduleModels.Slot::startsAt)
                .as("step is patient time PLUS padding, so 40 minutes, not 30")
                .containsExactly(
                        LocalTime.of(9, 0), LocalTime.of(9, 40),
                        LocalTime.of(10, 20), LocalTime.of(11, 0));

        assertThat(slots).allSatisfy(slot -> {
            assertThat(slot.patientMinutes())
                    .as("DEC-001: the patient is booked for the SERVICE duration")
                    .isEqualTo(30);
            assertThat(slot.blockedUntil()).isAfter(slot.startsAt().plusMinutes(30));
        });
    }

    @Test
    @DisplayName("an existing booking occupies its own buffer, which is the defect this closes")
    void anExistingBookingOccupiesItsOwnBuffer() {
        ClinicScope scope = tenantAScope();
        UUID doctor = seedDoctor("Dr Bo");
        addWindow(scope, doctor, 1, NINE, 12 * 60);

        // A 30 minute procedure carrying a 10 minute buffer, booked at 09:00.
        UUID procedure = seedProcedure("Filling", 30, 10);
        seedAppointment(doctor, procedure, MONDAY.atTime(9, 0), 30, "CONFIRMED");

        assertThat(availability.slotsFor(scope, doctor, MONDAY, 30, 0, null))
                .as("asked with buffer 0, so the step is 30 and only the BOOKING''s padding "
                        + "is under test. The booking occupies 09:00-09:40, so 09:30 is inside "
                        + "its padding and must not be offered. The legacy AI path offered it, "
                        + "because it widened candidates by the buffer but left existing bookings "
                        + "at their bare 30 minutes.")
                .extracting(ScheduleModels.Slot::startsAt)
                .doesNotContain(LocalTime.of(9, 30));

        assertThat(availability.slotsFor(scope, doctor, MONDAY, 30, 0, null))
                .extracting(ScheduleModels.Slot::startsAt)
                .as("with a 30 minute step the candidates are on the half hour, so 09:40 is never "
                        + "generated; the first bookable minute is 10:00. An earlier version of "
                        + "this test expected 09:40, which no 30 minute step can produce.")
                .startsWith(LocalTime.of(10, 0));
    }

    @Test
    @DisplayName("a booking with no procedure carries no padding")
    void aBookingWithNoProcedureHasNoBuffer() {
        ClinicScope scope = tenantAScope();
        UUID doctor = seedDoctor("Dr Cy");
        addWindow(scope, doctor, 1, NINE, 12 * 60);

        seedAppointment(doctor, null, MONDAY.atTime(9, 0), 30, "CONFIRMED");

        assertThat(availability.slotsFor(scope, doctor, MONDAY, 30, 0, null))
                .extracting(ScheduleModels.Slot::startsAt)
                .as("nothing pads a booking with no procedure, so it ends at 09:30 and a 30 "
                        + "minute candidate at 09:30 is free")
                .startsWith(LocalTime.of(9, 30));
    }

    @Test
    @DisplayName("padding that would spill past closing is not offered")
    void paddingMustFitInsideTheWindow() {
        ClinicScope scope = tenantAScope();
        UUID doctor = seedDoctor("Dr Di");
        // 09:00 - 10:20. A 30 minute service with 10 padding steps every 40, so the last
        // candidate is 09:40, whose padding ends exactly at 10:20.
        addWindow(scope, doctor, 1, NINE, 10 * 60 + 20);

        var slots = availability.slotsFor(scope, doctor, MONDAY, 30, 10, null);

        assertThat(slots).extracting(ScheduleModels.Slot::startsAt)
                .containsExactly(LocalTime.of(9, 0), LocalTime.of(9, 40));
        assertThat(slots).last().extracting(ScheduleModels.Slot::blockedUntil)
                .isEqualTo(LocalTime.of(10, 20));
    }

    @Test
    @DisplayName("the per-dentist override changes the step, and null means the service duration")
    void theDentistOverrideWins() {
        ClinicScope scope = tenantAScope();
        UUID doctor = seedDoctor("Dr Ed");
        addWindowWithOverride(scope, doctor, 1, NINE, 12 * 60, 45);

        assertThat(availability.slotsFor(scope, doctor, MONDAY, 30, 0, null))
                .as("DEC-001: no override means the service duration of 30")
                .extracting(ScheduleModels.Slot::startsAt)
                .startsWith(LocalTime.of(9, 0), LocalTime.of(9, 30));

        assertThat(availability.slotsFor(scope, doctor, MONDAY, 30, 0, 45))
                .as("the per-dentist override of 45 replaces it")
                .extracting(ScheduleModels.Slot::startsAt)
                .startsWith(LocalTime.of(9, 0), LocalTime.of(9, 45));
    }

    // ------------------------------------------------------------------
    // calendars
    // ------------------------------------------------------------------

    @Test
    @DisplayName("two windows on one weekday are a split shift, and both contribute slots")
    void twoWindowsAreASplitShift() {
        ClinicScope scope = tenantAScope();
        UUID doctor = seedDoctor("Dr Fay");
        addWindow(scope, doctor, 1, NINE, 10 * 60);
        addWindow(scope, doctor, 1, 14 * 60, 15 * 60);

        assertThat(availability.slotsFor(scope, doctor, MONDAY, 30, 0, null))
                .extracting(ScheduleModels.Slot::startsAt)
                .as("morning and afternoon only; the lunch break produces nothing")
                .containsExactly(
                        LocalTime.of(9, 0), LocalTime.of(9, 30),
                        LocalTime.of(14, 0), LocalTime.of(14, 30));
    }

    @Test
    @DisplayName("leave removes the whole day, inclusive of both ends")
    void leaveRemovesTheDay() {
        ClinicScope scope = tenantAScope();
        UUID doctor = seedDoctor("Dr Gil");
        addWindow(scope, doctor, 1, NINE, 12 * 60);

        assertThat(availability.slotsFor(scope, doctor, MONDAY, 30, 0, null)).isNotEmpty();

        availability.addTimeOff(scope, doctor, MONDAY, MONDAY.plusDays(1), "annual leave");

        assertThat(availability.slotsFor(scope, doctor, MONDAY, 30, 0, null))
                .as("a single day is startsOn = endsOn, and it is inclusive")
                .isEmpty();
        assertThat(availability.slotsFor(scope, doctor, MONDAY.plusDays(1), 30, 0, null))
                .as("the last day of leave is inclusive too")
                .isEmpty();
        assertThat(availability.slotsFor(scope, doctor, MONDAY.minusWeeks(1), 30, 0, null))
                .as("the Monday BEFORE the leave is a normal working day. (The day after is a "
                        + "TUESDAY with no window at all, so asserting it tested the wrong "
                        + "thing - this test only ever added a weekday-1 schedule.)")
                .isNotEmpty();
    }

    @Test
    @DisplayName("a schedule that has ended stops offering slots")
    void anEndedScheduleStopsOffering() {
        ClinicScope scope = tenantAScope();
        UUID doctor = seedDoctor("Dr Hal");
        addWindowEndingOn(scope, doctor, 1, NINE, 12 * 60, MONDAY);

        assertThat(availability.slotsFor(scope, doctor, MONDAY, 30, 0, null))
                .as("effectiveTo is inclusive, so the last day in force still works")
                .isNotEmpty();
        assertThat(availability.slotsFor(scope, doctor, MONDAY.plusWeeks(1), 30, 0, null))
                .as("and the week after is not covered at all")
                .isEmpty();
    }

    // ------------------------------------------------------------------
    // DEC-017: the status filter must work without the CHECK
    // ------------------------------------------------------------------

    @Test
    @DisplayName("CANCELLED and NO_SHOW free the slot; every other status holds it")
    void cancelledAndNoShowFreeTheSlot() {
        for (String status : List.of("CANCELLED", "NO_SHOW")) {
            resetDatabase();
            ClinicScope scope = tenantAScope();
            UUID doctor = seedDoctor("Dr Ida");
            addWindow(scope, doctor, 1, NINE, 12 * 60);
            seedAppointment(doctor, null, MONDAY.atTime(9, 0), 30, status);

            assertThat(availability.slotsFor(scope, doctor, MONDAY, 30, 0, null))
                    .as("%s frees the slot", status)
                    .extracting(ScheduleModels.Slot::startsAt)
                    .startsWith(LocalTime.of(9, 0));
        }

        // The five slot-HOLDING statuses all keep 09:00 occupied. APPROVED is no longer among
        // them: it cannot be written at all (see refusedStatuses), which is a stronger guarantee
        // than the one this test used to make.
        for (String status : List.of("PENDING", "CONFIRMED", "CHECKED_IN", "IN_PROGRESS",
                "COMPLETED")) {
            resetDatabase();
            ClinicScope scope = tenantAScope();
            UUID doctor = seedDoctor("Dr Ida");
            addWindow(scope, doctor, 1, NINE, 12 * 60);
            seedAppointment(doctor, null, MONDAY.atTime(9, 0), 30, status);

            assertThat(availability.slotsFor(scope, doctor, MONDAY, 30, 0, null))
                    .as("%s HOLDS the slot", status)
                    .extracting(ScheduleModels.Slot::startsAt)
                    .doesNotContain(LocalTime.of(9, 0));
        }
    }

    @Test
    @DisplayName("the database refuses every status outside DEC-003, APPROVED included")
    void refusedStatuses() {
        ClinicScope scope = tenantAScope();
        UUID doctor = seedDoctor("Dr Ida");
        addWindow(scope, doctor, 1, NINE, 12 * 60);

        // This replaces an earlier version of this file that INSERTED an APPROVED appointment to
        // prove the slot filter treated it as holding. It could not, once
        // appointments_status_dec003 existed - and that is the point: the eighth status is now
        // UNREACHABLE rather than merely handled correctly by one query.
        for (String status : List.of("APPROVED", "SCHEDULED", "BOOKED", "DONE", "ATTENDED",
                "confirmed", "")) {
            assertThatThrownBy(() -> seedAppointment(doctor, null, MONDAY.atTime(9, 0), 30, status))
                    .as("DEC-003 has exactly seven statuses; %s is not one of them", status)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        // And the empty string is refused too: a statusless appointment is not "pending by
        // default" here, it is a row whose lifecycle nobody decided.
        assertThat(availability.slotsFor(scope, doctor, MONDAY, 30, 0, null))
                .as("nothing was written, so the whole day is free")
                .isNotEmpty();
    }

    // ------------------------------------------------------------------
    // isolation and validation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("another clinic's dentist offers nothing")
    void availabilityIsTenantIsolated() {
        ClinicScope mine = tenantAScope();
        UUID doctor = seedDoctor("Dr Jan");
        addWindow(mine, doctor, 1, NINE, 12 * 60);
        seedAppointment(doctor, null, MONDAY.atTime(9, 0), 30, "CONFIRMED");

        assertThat(availability.slotsFor(tenantBScope(), doctor, MONDAY, 30, 0, null))
                .as("a dentist id from another organization has no schedule there")
                .isEmpty();
    }

    @Test
    @DisplayName("a schedule with no timezone is refused, rather than assumed to be UTC")
    void aScheduleMustNameItsTimezone() {
        ClinicScope scope = tenantAScope();
        UUID doctor = seedDoctor("Dr Kit");

        assertThatThrownBy(() -> rawWindow(scope, doctor, 1, NINE, 12 * 60, "   "))
                .as("a blank zone has undefined DST behaviour, and defaulting it to UTC would "
                        + "move real appointments across a DST boundary")
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject("""
                select is_nullable from information_schema.columns
                 where table_schema = 'public'
                   and table_name = 'clinic_schedules'
                   and column_name = 'timezone'""", new MapSqlParameterSource(), String.class))
                .as("and the column is NOT NULL, which is what refuses an ABSENT zone. Asserted "
                        + "directly rather than through a bind, because a null JDBC parameter is "
                        + "a driver-binding question rather than a schema one.")
                .isEqualTo("NO");
    }

    @Test
    @DisplayName("a window must not end before it starts, and ISO weekday is 1..7")
    void windowsAreValidatedByTheDatabase() {
        ClinicScope scope = tenantAScope();
        UUID doctor = seedDoctor("Dr Lee");

        assertThatThrownBy(() -> addWindow(scope, doctor, 1, 12 * 60, NINE))
                .as("a zero or negative-length window would generate no slots and read as a "
                        + "schedule that exists")
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> addWindow(scope, doctor, 0, NINE, 12 * 60))
                .as("ISO weekday is 1..7; 0 is Sunday in a different convention and accepting "
                        + "both would be worse than refusing one")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("overlapping windows for one dentist are refused at write time")
    void overlappingWindowsAreRefused() {
        ClinicScope scope = tenantAScope();
        UUID doctor = seedDoctor("Dr Mo");
        addWindow(scope, doctor, 1, NINE, 12 * 60);

        assertThatThrownBy(() -> addWindow(scope, doctor, 1, 11 * 60, 14 * 60))
                .as("two overlapping windows would emit each overlapping minute twice and a "
                        + "receptionist could book the same slot from either")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("writing a schedule without naming a clinic is refused")
    void writingRequiresAProjectScope() {
        ClinicScope ambiguous = ClinicScope.forCaller(fixture.adminAContext());
        UUID doctor = seedDoctor("Dr Nia");

        assertThatThrownBy(() -> availability.addWindow(ambiguous, doctor, 1, NINE, 12 * 60,
                null, "Asia/Amman", FROM, null))
                .as("a schedule belongs to exactly one clinic")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("one clinic");
    }

    // ------------------------------------------------------------------
    // DEC-002: shared resources
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a resource is shared, carries no dentist, and one capability is added once")
    void resourcesAreSharedAndCapabilityTagged() {
        ClinicScope scope = tenantAScope();
        UUID chair = availability.addResource(scope, "Chair 1");

        assertThat(availability.resources(scope)).extracting(ScheduleModels.Resource::name)
                .containsExactly("Chair 1");

        availability.addCapability(scope, chair, "AUTOCLAVE");
        assertThatThrownBy(() -> availability.addCapability(scope, chair, "AUTOCLAVE"))
                .as("the same tag twice on one resource is noise, not capability")
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(availability.resources(tenantBScope()))
                .as("DEC-002: resources belong to a clinic, not to a chain")
                .isEmpty();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private ClinicScope tenantAScope() {
        return ClinicScope.forRequestedProject(guard, fixture.adminAContext(), fixture.projectA1);
    }

    private ClinicScope tenantBScope() {
        return ClinicScope.forRequestedProject(guard, fixture.adminBContext(), fixture.projectB1);
    }

    private void addWindow(ClinicScope scope, UUID doctor, int weekday, int start, int end) {
        availability.addWindow(scope, doctor, weekday, start, end, null, "Asia/Amman", FROM, null);
    }

    private void addWindowWithOverride(
            ClinicScope scope, UUID doctor, int weekday, int start, int end, Integer override) {
        availability.addWindow(scope, doctor, weekday, start, end, override, "Asia/Amman", FROM, null);
    }

    private void addWindowEndingOn(
            ClinicScope scope, UUID doctor, int weekday, int start, int end, LocalDate effectiveTo) {
        availability.addWindow(scope, doctor, weekday, start, end, null, "Asia/Amman", FROM, effectiveTo);
    }

    /** Bypasses the service so the column's own NOT NULL/CHECK is what answers. */
    private void rawWindow(
            ClinicScope scope, UUID doctor, int weekday, int start, int end, String timezone) {
        Map<String, Object> values = new HashMap<>();
        values.put("doctorId", doctor);
        values.put("weekday", weekday);
        values.put("startMinute", start);
        values.put("endMinute", end);
        values.put("durationOverrideMinutes", null);
        values.put("timezone", timezone);
        values.put("effectiveFrom", FROM);
        values.put("effectiveTo", null);
        values.put("isActive", true);
        repository.insertWindow(scope, values);
    }

    private UUID seedDoctor(String name) {
        UUID id = UUID.randomUUID();
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("id", id).addValue("org", fixture.orgA)
                .addValue("project", fixture.projectA1).addValue("name", name);
        jdbc.update("""
                insert into "doctors"
                    ("id", "organizationId", "projectId", "name", "specialty",
                     "workingDays", "startHour", "endHour", "slotDuration", "isActive",
                     "createdAt", "updatedAt")
                values (:id, :org, :project, :name, 'General',
                        ARRAY[1,2,3,4,5]::integer[], '09:00', '17:00', 30, true,
                        (current_timestamp at time zone 'utc'),
                        (current_timestamp at time zone 'utc'))
                """, p);
        return id;
    }

    private UUID seedProcedure(String name, int duration, int buffer) {
        UUID id = UUID.randomUUID();
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("id", id).addValue("org", fixture.orgA)
                .addValue("project", fixture.projectA1).addValue("name", name)
                .addValue("duration", duration).addValue("buffer", buffer);
        jdbc.update("""
                insert into "procedures"
                    ("id", "organizationId", "projectId", "name", "specialty",
                     "durationMinutes", "bufferMinutes", "price", "isActive",
                     "createdAt", "updatedAt")
                values (:id, :org, :project, :name, 'General',
                        :duration, :buffer, 0, true,
                        (current_timestamp at time zone 'utc'),
                        (current_timestamp at time zone 'utc'))
                """, p);
        return id;
    }

    private void seedAppointment(
            UUID doctor, UUID procedure, LocalDateTime at, int duration, String status) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("id", UUID.randomUUID()).addValue("org", fixture.orgA)
                .addValue("project", fixture.projectA1).addValue("title", "Booked")
                .addValue("doctor", doctor).addValue("procedure", procedure)
                .addValue("at", at).addValue("duration", duration).addValue("status", status);
        jdbc.update("""
                insert into "appointments"
                    ("id", "organizationId", "projectId", "title", "doctorId", "procedureId",
                     "appointmentDate", "durationMinutes", "status", "isAiProposed",
                     "createdAt", "updatedAt")
                values (:id, :org, :project, :title, :doctor, :procedure,
                        :at, :duration, :status, false,
                        (current_timestamp at time zone 'utc'),
                        (current_timestamp at time zone 'utc'))
                """, p);
    }
}