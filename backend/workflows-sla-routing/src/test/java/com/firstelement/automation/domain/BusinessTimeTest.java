package com.firstelement.automation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Business-time arithmetic: the SLA clock's pause and resume.
 *
 * <p>The calendar under test is the Gulf working week — <b>Sunday to Thursday</b> — because that is
 * what {@code employee_availability} and {@code sla_calendar_windows} describe in this product, and a
 * test written against a Monday-to-Friday week would pass while the real configuration fails. Riyadh
 * is UTC+3 all year, so a local hour maps to a fixed UTC hour and the only variable left is the DST
 * cases, which use London explicitly.
 */
class BusinessTimeTest {

    /** Sunday..Thursday, 09:00-17:00 local, in Riyadh. */
    private static Map<Integer, List<AvailabilityWindow>> gulfWeek() {
        return BusinessTime.groupWindows(List.of(
            new AvailabilityWindow(0, 9 * 60, 17 * 60, "Asia/Riyadh"),
            new AvailabilityWindow(1, 9 * 60, 17 * 60, "Asia/Riyadh"),
            new AvailabilityWindow(2, 9 * 60, 17 * 60, "Asia/Riyadh"),
            new AvailabilityWindow(3, 9 * 60, 17 * 60, "Asia/Riyadh"),
            new AvailabilityWindow(4, 9 * 60, 17 * 60, "Asia/Riyadh")));
    }

    private static BusinessCalendar weeklyRiyadh() {
        return new BusinessCalendar("cal-1", "gulf-week", 1, "Asia/Riyadh",
            LocalDate.of(2026, 1, 1), null, gulfWeek(), Map.of());
    }

    private static BusinessCalendar riyadhWith(Map<LocalDate, BusinessCalendar.Holiday> holidays) {
        return new BusinessCalendar("cal-1", "gulf-week", 1, "Asia/Riyadh",
            LocalDate.of(2026, 1, 1), null, gulfWeek(), holidays);
    }

    /** An explicit UTC wall clock. Riyadh local = UTC + 3 all year. */
    private static LocalDateTime utc(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute);
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    @Test
    @DisplayName("a null calendar is 24/7, so the whole interval counts")
    void nullCalendarIsAlwaysOpen() {
        assertThat(BusinessTime.businessMinutesBetween(null, utc(2026, 3, 12, 10, 0),
            utc(2026, 3, 12, 11, 0))).isEqualTo(60L);
        // A 24/7 policy must not read as an instant breach. The migration says it outright: forcing 0
        // "would be a silent 'immediately breached'".
        assertThat(BusinessTime.businessMinutesBetween(null, utc(2026, 3, 12, 10, 0),
            utc(2026, 3, 14, 10, 0))).isEqualTo(2880L);
    }

    @Test
    @DisplayName("inside the working week, elapsed time is elapsed time")
    void insideTheWeek() {
        // 2026-03-11 is a Wednesday. Riyadh is UTC+3, so 10:00-12:00 local is 07:00-09:00Z.
        assertThat(BusinessTime.businessMinutesBetween(weeklyRiyadh(),
            utc(2026, 3, 11, 7, 0), utc(2026, 3, 11, 9, 0))).isEqualTo(120L);
    }

    @Test
    @DisplayName("the clock pauses at closing and resumes the next working morning")
    void pausesAndResumes() {
        // Thursday 15:30 local (12:30Z) to Friday 10:30 local (07:30Z). Thursday contributes its last
        // 90 minutes; Friday is the weekend in the Gulf week, so it contributes nothing.
        assertThat(BusinessTime.businessMinutesBetween(weeklyRiyadh(),
            utc(2026, 3, 12, 12, 30), utc(2026, 3, 13, 7, 30))).isEqualTo(90L);
    }

    @Test
    @DisplayName("Thursday into Sunday crosses the whole weekend")
    void weekendIsClosed() {
        // Thursday 16:00 local to Sunday 10:00 local: 60 minutes on Thursday, 60 on Sunday, nothing on
        // Friday or Saturday.
        long minutes = BusinessTime.businessMinutesBetween(weeklyRiyadh(),
            utc(2026, 3, 12, 13, 0), utc(2026, 3, 15, 7, 0));
        assertThat(minutes).isEqualTo(60L + 60L);
    }

    @Test
    @DisplayName("a whole-day holiday removes the entire day")
    void wholeDayHoliday() {
        BusinessCalendar calendar = riyadhWith(Map.of(LocalDate.of(2026, 3, 11),
            new BusinessCalendar.Holiday(LocalDate.of(2026, 3, 11), "Founding Day", null, null)));
        // Wednesday 10:00 local through Thursday 11:00 local. Wednesday is the holiday, so only
        // Thursday's 09:00-11:00 counts.
        long minutes = BusinessTime.businessMinutesBetween(calendar,
            utc(2026, 3, 11, 7, 0), utc(2026, 3, 12, 8, 0));
        assertThat(minutes).isEqualTo(120L);
    }

    @Test
    @DisplayName("a partial holiday closure pauses only the closed slice")
    void partialHolidayClosure() {
        BusinessCalendar calendar = riyadhWith(Map.of(LocalDate.of(2026, 3, 11),
            new BusinessCalendar.Holiday(LocalDate.of(2026, 3, 11), "half day", 9 * 60, 12 * 60)));
        // Wednesday 09:00-17:00 local with 09:00-12:00 closed leaves 12:00-17:00: five hours.
        long minutes = BusinessTime.businessMinutesBetween(calendar,
            utc(2026, 3, 11, 6, 0), utc(2026, 3, 11, 14, 0));
        assertThat(minutes).isEqualTo(300L);
    }

    @Test
    @DisplayName("an inverted or empty interval is zero, never negative")
    void invertedInterval() {
        BusinessCalendar calendar = weeklyRiyadh();
        assertThat(BusinessTime.businessMinutesBetween(calendar, utc(2026, 3, 11, 9, 0),
            utc(2026, 3, 11, 7, 0))).isZero();
        assertThat(BusinessTime.businessMinutesBetween(calendar, utc(2026, 3, 11, 7, 0),
            utc(2026, 3, 11, 7, 0))).isZero();
        assertThat(BusinessTime.businessMinutesBetween(weeklyRiyadh(), null,
            utc(2026, 3, 11, 7, 0))).isZero();
    }

    @Test
    @DisplayName("a working day is eight real hours in both Riyadh and London")
    void eightHourDay() {
        BusinessCalendar london = new BusinessCalendar("cal-2", "uk-week", 1, "Europe/London",
            LocalDate.of(2026, 1, 1), null,
            BusinessTime.groupWindows(
                List.of(new AvailabilityWindow(0, 9 * 60, 17 * 60, "Europe/London"))), Map.of());
        // 2026-03-29 is the UK spring-forward Sunday, and the transition is at 01:00 local, so a
        // 09:00-17:00 local day is still eight real hours.
        assertThat(BusinessTime.businessMinutesBetween(london,
            utc(2026, 3, 29, 0, 0), utc(2026, 4, 1, 0, 0))).isEqualTo(480L);
    }

    @Test
    @DisplayName("DST inside a window makes the day a real hour shorter")
    void dstInsideTheWindow() {
        // A Sunday 01:00-05:00 local window on the London spring-forward night. The clock jumps
        // 01:00 -> 02:00 local, so the window spans four hours of wall clock but only three of real
        // time. This is the assertion that fails if a window boundary is built by adding minutes to
        // an instant instead of from local fields.
        BusinessCalendar london = new BusinessCalendar("cal-2", "uk-night", 1, "Europe/London",
            LocalDate.of(2026, 1, 1), null,
            BusinessTime.groupWindows(
                List.of(new AvailabilityWindow(0, 60, 300, "Europe/London"))), Map.of());
        // The preceding Saturday has no window, so only the transition Sunday contributes.
        assertThat(BusinessTime.businessMinutesBetween(london,
            utc(2026, 3, 28, 0, 0), utc(2026, 3, 30, 0, 0))).isEqualTo(180L);
    }

    @Test
    @DisplayName("DST autumn makes a window a real hour longer")
    void dstInsideTheWindowAutumn() {
        // 2026-10-25 is the UK fall-back Sunday: 02:00 local repeats, so a 01:00-05:00 local window is
        // five real hours. The asymmetry with the spring case is the whole point.
        BusinessCalendar london = new BusinessCalendar("cal-2", "uk-night", 1, "Europe/London",
            LocalDate.of(2026, 1, 1), null,
            BusinessTime.groupWindows(
                List.of(new AvailabilityWindow(0, 60, 300, "Europe/London"))), Map.of());
        assertThat(BusinessTime.businessMinutesBetween(london,
            utc(2026, 10, 25, 0, 0), utc(2026, 10, 27, 0, 0))).isEqualTo(300L);
    }

    @Test
    @DisplayName("a budget expires at the right working instant")
    void addBusinessMinutes() {
        // Thursday 16:30 local (13:30Z) + 60 business minutes. Thursday's window has 30 minutes left,
        // so the remaining 30 land on Sunday 09:30 local (06:30Z) — Friday and Saturday are closed.
        Instant due = BusinessTime.addBusinessMinutes(weeklyRiyadh(),
            Instant.parse("2026-03-12T13:30:00Z"), 60L);
        assertThat(utc(due)).isEqualTo(utc(2026, 3, 15, 6, 30));
    }

    @Test
    @DisplayName("a budget that fits inside one day does not spill over")
    void addBusinessMinutesWithinOneDay() {
        // Wednesday 09:00 local + 30 business minutes is the same day at 09:30 local.
        assertThat(BusinessTime.addBusinessMinutes(weeklyRiyadh(),
            Instant.parse("2026-03-11T06:00:00Z"), 30L))
            .isEqualTo(Instant.parse("2026-03-11T06:30:00Z"));
    }

    @Test
    @DisplayName("adding zero returns the same instant")
    void addZeroBusinessMinutes() {
        Instant start = Instant.parse("2026-03-12T20:00:00Z");
        assertThat(BusinessTime.addBusinessMinutes(weeklyRiyadh(), start, 0L)).isEqualTo(start);
    }

    @Test
    @DisplayName("a budget that cannot be met is refused rather than guessed")
    void addUnboundedBusinessMinutes() {
        BusinessCalendar empty = new BusinessCalendar("cal-3", "no-windows", 1, "UTC",
            LocalDate.of(2026, 1, 1), null, Map.of(), Map.of());
        assertThatThrownBy(() -> BusinessTime.addBusinessMinutes(empty,
            Instant.parse("2026-03-12T10:00:00Z"), 60L))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("open window");
    }

    @Test
    @DisplayName("the next business instant skips the weekend")
    void nextBusinessInstantSkipsTheWeekend() {
        // Friday 16:00 local is closed in the Gulf week; the next open instant is Sunday 09:00 local.
        assertThat(BusinessTime.nextBusinessInstant(weeklyRiyadh(),
            Instant.parse("2026-03-13T13:00:00Z"), 10))
            .isEqualTo(Instant.parse("2026-03-15T06:00:00Z"));
    }

    @Test
    @DisplayName("the next business instant skips a holiday")
    void nextBusinessInstantSkipsAHoliday() {
        BusinessCalendar calendar = riyadhWith(Map.of(LocalDate.of(2026, 3, 15),
            new BusinessCalendar.Holiday(LocalDate.of(2026, 3, 15), "holiday", null, null)));
        assertThat(BusinessTime.nextBusinessInstant(calendar,
            Instant.parse("2026-03-14T10:00:00Z"), 10))
            .isEqualTo(Instant.parse("2026-03-16T06:00:00Z"));
    }

    @Test
    @DisplayName("the next business instant from inside a window is now")
    void nextBusinessInstantInsideAWindow() {
        assertThat(BusinessTime.nextBusinessInstant(weeklyRiyadh(),
            Instant.parse("2026-03-11T07:00:00Z"), 10))
            .isEqualTo(Instant.parse("2026-03-11T07:00:00Z"));
    }

    @Test
    @DisplayName("the effective calendar is the newest one covering the date")
    void resolveEffective() {
        BusinessCalendar v1 = weeklyRiyadh();
        BusinessCalendar v2 = new BusinessCalendar("cal-2", "gulf-week", 2, "Asia/Riyadh",
            LocalDate.of(2026, 6, 1), null, gulfWeek(), Map.of());
        List<BusinessCalendar> versions = List.of(v1, v2);

        assertThat(BusinessTime.resolveEffective(versions, LocalDate.of(2026, 3, 12)).version())
            .isEqualTo(1);
        assertThat(BusinessTime.resolveEffective(versions, LocalDate.of(2026, 7, 1)).version())
            .isEqualTo(2);
        assertThat(BusinessTime.resolveEffective(List.of(), LocalDate.of(2026, 7, 1))).isNull();
    }

    @Test
    @DisplayName("a calendar with an end date stops covering after it")
    void closedEffectiveRange() {
        BusinessCalendar closed = new BusinessCalendar("cal-1", "old", 1, "UTC",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1), Map.of(), Map.of());
        assertThat(closed.covers(LocalDate.of(2026, 1, 31))).isTrue();
        assertThat(closed.covers(LocalDate.of(2026, 2, 2))).isFalse();
    }

    @Test
    @DisplayName("an absurd span is refused instead of hanging the evaluator")
    void boundedIteration() {
        BusinessCalendar calendar = weeklyRiyadh();
        assertThatThrownBy(() -> BusinessTime.businessMinutesBetween(calendar,
            utc(2000, 1, 1, 0, 0), utc(2026, 1, 1, 0, 0)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exceeds");
    }

    @Test
    @DisplayName("a calendar cannot describe an end before its start")
    void calendarValidation() {
        assertThatThrownBy(() -> new BusinessCalendar("c", "k", 1, "UTC",
            LocalDate.of(2026, 5, 1), LocalDate.of(2026, 4, 1), Map.of(), Map.of()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("effectiveTo");
        assertThatThrownBy(() -> new BusinessCalendar("c", "k", 0, "UTC",
            LocalDate.of(2026, 5, 1), null, Map.of(), Map.of()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("version");
        assertThatThrownBy(() -> new BusinessCalendar("c", "k", 1, "UTC", null, null, Map.of(), Map.of()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("effectiveFrom");
    }
}