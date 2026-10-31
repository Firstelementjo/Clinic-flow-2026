package com.firstelement.automation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Working hours, ported from {@code packages/routing/src/working-hours.test.ts} plus the DST cases
 * that TypeScript could not express without a fake clock.
 *
 * <p>Every assertion here is about behaviour the legacy runtime shipped: the same instant inside the
 * same window must produce the same answer, no windows must mean "always available", and a night shift
 * must match both halves.
 */
class WorkingHoursTest {

    /** Sunday 2026-08-16 09:00 UTC. */
    private static final Instant SUNDAY_09_00 = Instant.parse("2026-08-16T09:00:00Z");

    @Test
    @DisplayName("no windows means always available, not never")
    void emptyWindowsAreAlwaysAvailable() {
        assertThat(WorkingHours.isWithinWorkingHours(List.of(), SUNDAY_09_00)).isTrue();
        assertThat(WorkingHours.isWithinWorkingHours(null, SUNDAY_09_00)).isTrue();
    }

    @Test
    @DisplayName("a moment inside the window is inside working hours")
    void insideWindow() {
        List<AvailabilityWindow> windows =
            List.of(new AvailabilityWindow(0, 9 * 60, 17 * 60, "UTC"));
        assertThat(WorkingHours.isWithinWorkingHours(windows, SUNDAY_09_00)).isTrue();
    }

    @Test
    @DisplayName("the start minute is inclusive and the end minute is exclusive")
    void boundaries() {
        List<AvailabilityWindow> windows =
            List.of(new AvailabilityWindow(0, 9 * 60, 17 * 60, "UTC"));
        assertThat(WorkingHours.isWithinWorkingHours(windows, Instant.parse("2026-08-16T09:00:00Z")))
            .isTrue();
        assertThat(WorkingHours.isWithinWorkingHours(windows, Instant.parse("2026-08-16T16:59:00Z")))
            .isTrue();
        assertThat(WorkingHours.isWithinWorkingHours(windows, Instant.parse("2026-08-16T17:00:00Z")))
            .isFalse();
        assertThat(WorkingHours.isWithinWorkingHours(windows, Instant.parse("2026-08-16T08:59:00Z")))
            .isFalse();
    }

    @Test
    @DisplayName("a window for another weekday does not match")
    void wrongWeekday() {
        List<AvailabilityWindow> windows =
            List.of(new AvailabilityWindow(1, 9 * 60, 17 * 60, "UTC"));
        assertThat(WorkingHours.isWithinWorkingHours(windows, SUNDAY_09_00)).isFalse();
    }

    @Test
    @DisplayName("a window that crosses midnight matches both halves")
    void overnightWindow() {
        // Friday 22:00 to Saturday 06:00, expressed as one legacy-style wrapping window.
        List<AvailabilityWindow> windows =
            List.of(new AvailabilityWindow(5, 22 * 60, 6 * 60, "UTC"));
        assertThat(WorkingHours.isWithinWorkingHours(windows, Instant.parse("2026-08-14T23:30:00Z")))
            .isTrue();
        assertThat(WorkingHours.isWithinWorkingHours(windows, Instant.parse("2026-08-15T05:59:00Z")))
            .isTrue();
        assertThat(WorkingHours.isWithinWorkingHours(windows, Instant.parse("2026-08-15T06:00:00Z")))
            .isFalse();
        assertThat(WorkingHours.isWithinWorkingHours(windows, Instant.parse("2026-08-15T21:00:00Z")))
            .isFalse();
    }

    @Test
    @DisplayName("one open window among several is enough")
    void severalWindows() {
        List<AvailabilityWindow> windows = List.of(
            new AvailabilityWindow(0, 9 * 60, 12 * 60, "UTC"),
            new AvailabilityWindow(0, 14 * 60, 17 * 60, "UTC"));
        assertThat(WorkingHours.isWithinWorkingHours(windows, Instant.parse("2026-08-16T15:00:00Z")))
            .isTrue();
        assertThat(WorkingHours.isWithinWorkingHours(windows, Instant.parse("2026-08-16T13:00:00Z")))
            .isFalse();
    }

    @Test
    @DisplayName("the same instant answers the same way a thousand times over")
    void deterministic() {
        List<AvailabilityWindow> windows =
            List.of(new AvailabilityWindow(0, 9 * 60, 17 * 60, "Asia/Riyadh"));
        boolean first = WorkingHours.isWithinWorkingHours(windows, SUNDAY_09_00);
        for (int attempt = 0; attempt < 1000; attempt++) {
            assertThat(WorkingHours.isWithinWorkingHours(windows, SUNDAY_09_00)).isEqualTo(first);
        }
    }

    @Test
    @DisplayName("DST: a local 09:00 window is 09:00 local on both sides of a transition")
    void dstKeepsTheLocalWallClock() {
        // Europe/London moves to BST on 2026-03-29 and back on 2026-10-25. 09:00 local is 09:00Z
        // while GMT holds and 08:00Z while BST holds; a hand-rolled offset table is what gets this
        // wrong, and the legacy comment said so by name.
        List<AvailabilityWindow> windows =
            List.of(new AvailabilityWindow(0, 9 * 60, 17 * 60, "Europe/London"));
        Instant gmtNineLocal = Instant.parse("2026-03-22T09:00:00Z");
        Instant bstNineLocal = Instant.parse("2026-04-05T08:00:00Z");

        assertThat(WorkingHours.LocalMoment.of(gmtNineLocal, "Europe/London").minuteOfDay())
            .isEqualTo(9 * 60);
        assertThat(WorkingHours.LocalMoment.of(bstNineLocal, "Europe/London").minuteOfDay())
            .isEqualTo(9 * 60);
        assertThat(WorkingHours.isWithinWorkingHours(windows, gmtNineLocal)).isTrue();
        assertThat(WorkingHours.isWithinWorkingHours(windows, bstNineLocal)).isTrue();
        // The same wall-clock instant that was 08:00 local before the change is 09:00 local after it,
        // so a fixed offset would call one of these two wrong.
        assertThat(WorkingHours.isWithinWorkingHours(windows, Instant.parse("2026-03-22T08:00:00Z")))
            .isFalse();
        assertThat(WorkingHours.isWithinWorkingHours(windows, Instant.parse("2026-04-05T08:00:00Z")))
            .isTrue();
    }

    @Test
    @DisplayName("a zone with no DST offset the whole year still answers correctly")
    void fixedOffsetZone() {
        List<AvailabilityWindow> windows =
            List.of(new AvailabilityWindow(0, 9 * 60, 17 * 60, "Asia/Riyadh"));
        // Riyadh is UTC+3 all year: 09:00 local is 06:00Z in both March and October.
        assertThat(WorkingHours.isWithinWorkingHours(windows, Instant.parse("2026-03-22T06:00:00Z")))
            .isTrue();
        assertThat(WorkingHours.isWithinWorkingHours(windows, Instant.parse("2026-10-25T06:00:00Z")))
            .isTrue();
        assertThat(WorkingHours.isWithinWorkingHours(windows, Instant.parse("2026-10-25T05:00:00Z")))
            .isFalse();
    }

    @Test
    @DisplayName("an unknown zone fails loudly rather than defaulting to UTC")
    void unknownZoneIsRefused() {
        assertThatThrownBy(() -> WorkingHours.LocalMoment.of(SUNDAY_09_00, "Mars/Olympus"))
            .isInstanceOf(java.time.zone.ZoneRulesException.class);
    }

    @Test
    @DisplayName("a window formats as HH:MM-HH:MM for an operator")
    void formatting() {
        assertThat(WorkingHours.formatWindow(new AvailabilityWindow(1, 9 * 60, 17 * 60, "UTC")))
            .isEqualTo("09:00–17:00");
        assertThat(WorkingHours.formatWindow(new AvailabilityWindow(1, 0, 1440, "UTC")))
            .isEqualTo("00:00–00:00");
    }

    @Test
    @DisplayName("an out-of-range window is refused at construction")
    void windowValidation() {
        assertThatThrownBy(() -> new AvailabilityWindow(7, 0, 60, "UTC"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dayOfWeek");
        assertThatThrownBy(() -> new AvailabilityWindow(1, -1, 60, "UTC"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AvailabilityWindow(1, 0, 60, " "))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("timezone");
    }
}