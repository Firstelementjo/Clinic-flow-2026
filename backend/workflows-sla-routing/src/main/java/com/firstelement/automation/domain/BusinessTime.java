package com.firstelement.automation.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Business-time arithmetic: how much of a wall-clock interval was inside working hours.
 *
 * <h2>What this exists for</h2>
 *
 * <p>An SLA clock must pause outside working hours and on holidays, and must resume afterwards.
 * Rather than materialise a tick table (which {@code V20261005030000} deliberately did not create),
 * the elapsed business time is <em>derived</em>: {@link #businessMinutesBetween} walks the local days
 * the interval touches and adds the open minutes. Pause and resume then need no state at all, and the
 * number is reproducible for any historical interval even after the calendar is edited — provided the
 * caller passes the calendar version that was in force.
 *
 * <h2>DST, stated once and enforced structurally</h2>
 *
 * <p>A window is a <b>local wall-clock range</b> ({@code 09:00–17:00 Europe/London} is 09:00 local on
 * both sides of a change), which is why {@code V20261005030000} stores minutes-from-local-midnight and
 * an IANA {@code timezone} and refuses a UTC-offset column: the two disagree on change days.
 *
 * <p>That has a consequence this class gets right by construction: a window boundary is built with
 * {@link LocalTime#atDate(java.time.LocalDate)} and {@link LocalTime#atZone(ZoneId)}, <b>never</b> by
 * adding minutes to an instant. {@code dayStart.plusMinutes(600)} is 600 <em>real</em> minutes after
 * local midnight, which is not 10:00 local on a day whose offset changed in between — and a business
 * week that silently loses an hour is how a Levant clinic's Tuesday disappears.
 *
 * <p>The residual ambiguity — a window that crosses the repeated hour — is why
 * {@code sla_calendar_windows} refuses a same-day window whose end is not after its start, and this
 * class says so where it handles a hand-edited one.
 *
 * <h2>Iteration is bounded</h2>
 *
 * <p>The walk is capped at {@link #MAX_DAYS_PER_SPAN} local days and throws when it exceeds it. An
 * unbounded walk over a corrupt calendar is a scheduler hang, and a hang in the SLA evaluator takes
 * down every clock in the tenant with it.
 */
public final class BusinessTime {

    /** Upper bound on the local days one business-time computation may walk. */
    public static final int MAX_DAYS_PER_SPAN = 366 * 3;

    private BusinessTime() {
    }

    /**
     * Business minutes inside {@code [from, to)} according to {@code calendar}.
     *
     * @param calendar {@code null} is an explicit 24/7 policy, not an absence of configuration
     * @return the count, never negative; an inverted or empty interval is 0
     * @throws IllegalArgumentException when the span exceeds {@link #MAX_DAYS_PER_SPAN} local days
     */
    public static long businessMinutesBetween(BusinessCalendar calendar,
                                              LocalDateTime from,
                                              LocalDateTime to) {
        if (from == null || to == null || !to.isAfter(from)) {
            return 0L;
        }
        if (calendar == null) {
            // NULL = 24/7, "which is a legitimate choice for a digital channel". Returning 0 here
            // would make every 24/7 policy breach instantly, which is the "forced 0 would be a
            // silent 'immediately breached'" trap the migration warns about.
            return Duration.between(from, to).toMinutes();
        }
        ZoneId zone = ZoneId.of(calendar.timezone());
        ZonedDateTime start = WorkingHours.toZoned(from, calendar.timezone());
        ZonedDateTime end = WorkingHours.toZoned(to, calendar.timezone());

        LocalDate day = start.toLocalDate();
        LocalDate lastDay = end.toLocalDate();
        if (lastDay.isAfter(day.plusDays(MAX_DAYS_PER_SPAN))) {
            throw new IllegalArgumentException(
                "Refusing to walk " + Duration.between(start, end) + " of business time: the span "
                    + "exceeds " + MAX_DAYS_PER_SPAN + " local days. A calendar with a full-day window "
                    + "or a corrupt effective range would otherwise hang the SLA evaluator.");
        }

        long minutes = 0L;
        while (!day.isAfter(lastDay)) {
            minutes += minutesOnDay(calendar, day, start, end);
            day = day.plusDays(1);
        }
        return minutes;
    }

    /** Open business minutes on one local day, clipped to {@code [from, to)}. */
    public static long minutesOnDay(BusinessCalendar calendar, LocalDate day,
                                    ZonedDateTime from, ZonedDateTime to) {
        ZoneId zone = ZoneId.of(calendar.timezone());
        BusinessCalendar.Holiday holiday = calendar.holidayOn(day);
        if (holiday != null && holiday.wholeDay()) {
            return 0L;
        }

        ZonedDateTime dayStart = day.atStartOfDay(zone);
        ZonedDateTime nextDayStart = day.plusDays(1).atStartOfDay(zone);
        ZonedDateTime windowStart = from.isAfter(dayStart) ? from : dayStart;
        ZonedDateTime windowEnd = to.isBefore(nextDayStart) ? to : nextDayStart;
        if (!windowEnd.isAfter(windowStart)) {
            return 0L;
        }
        return sumWindowOverlap(calendar, day, windowStart, windowEnd);
    }

    private static long sumWindowOverlap(BusinessCalendar calendar, LocalDate day,
                                         ZonedDateTime from, ZonedDateTime to) {
        ZoneId zone = ZoneId.of(calendar.timezone());
        BusinessCalendar.Holiday holiday = calendar.holidayOn(day);

        List<Interval> intervals = new ArrayList<>();
        for (AvailabilityWindow window : calendar.windowsFor(day.getDayOfWeek().getValue() % 7)) {
            // A window whose end is not after its start crosses midnight. sla_calendar_windows
            // forbids that shape, but a hand-edited or imported one must still evaluate sanely rather
            // than producing a negative duration.
            int endMinute = window.crossesMidnight() ? 1440 : window.endMinute();
            ZonedDateTime start = localMinute(zone, day, window.startMinute());
            ZonedDateTime end = localMinute(zone, day, endMinute);
            if (end.isAfter(start)) {
                intervals.add(new Interval(start, end));
            }
        }
        intervals.sort(Comparator.comparing(Interval::from));

        long total = 0L;
        for (Interval interval : intervals) {
            ZonedDateTime start = interval.from().isBefore(from) ? from : interval.from();
            ZonedDateTime end = interval.to().isAfter(to) ? to : interval.to();
            if (!end.isAfter(start)) {
                continue;
            }
            if (holiday != null && !holiday.wholeDay()) {
                ZonedDateTime closureStart = localMinute(zone, day, holiday.startMinute());
                ZonedDateTime closureEnd = localMinute(zone, day, holiday.endMinute());
                // Skip the closed slice rather than subtracting from the whole day: a partial closure
                // must not turn an open window into a shorter one twice over when windows overlap.
                if (start.isBefore(closureEnd) && end.isAfter(closureStart)) {
                    total += minutesBetween(start, closureStart);
                    start = closureEnd.isAfter(end) ? end : closureEnd;
                }
            }
            total += minutesBetween(start, end);
        }
        return total;
    }

    /**
     * The instant at {@code minute}-past-local-midnight on {@code day}.
     *
     * <p>Built from local fields so the zone's own rules resolve the offset. On a transition day this
     * is the only construction that means what an operator wrote in the calendar; see the class
     * comment.
     */
    static ZonedDateTime localMinute(ZoneId zone, LocalDate day, int minute) {
        if (minute >= 1440) {
            return day.plusDays(minute / 1440).atStartOfDay(zone);
        }
        return LocalTime.of(minute / 60, minute % 60).atDate(day).atZone(zone);
    }

    private static long minutesBetween(ZonedDateTime from, ZonedDateTime to) {
        return Duration.between(from, to).toMinutes();
    }

    /**
     * The next instant at or after {@code from} that is inside working hours.
     *
     * @param withinDays how many local days ahead to search before giving up
     * @return the first open instant, or {@code null} when the calendar has no open window at all
     */
    public static Instant nextBusinessInstant(BusinessCalendar calendar, Instant from, int withinDays) {
        if (calendar == null || from == null) {
            return null;
        }
        ZoneId zone = ZoneId.of(calendar.timezone());
        ZonedDateTime cursor = from.atZone(zone);
        int horizon = Math.max(1, Math.min(withinDays, MAX_DAYS_PER_SPAN));
        for (int dayOffset = 0; dayOffset <= horizon; dayOffset++) {
            LocalDate day = cursor.toLocalDate().plusDays(dayOffset);
            for (AvailabilityWindow window : calendar.windowsFor(day.getDayOfWeek().getValue() % 7)) {
                int endMinute = window.crossesMidnight() ? 1440 : window.endMinute();
                ZonedDateTime windowStart = localMinute(zone, day, window.startMinute());
                ZonedDateTime windowEnd = localMinute(zone, day, endMinute);
                if (!windowEnd.isAfter(windowStart)) {
                    continue;
                }
                BusinessCalendar.Holiday holiday = calendar.holidayOn(day);
                if (holiday != null && holiday.wholeDay()) {
                    continue;
                }
                ZonedDateTime candidate = windowStart.isBefore(cursor) ? cursor : windowStart;
                if (holiday != null && !holiday.wholeDay()) {
                    ZonedDateTime closureStart = localMinute(zone, day, holiday.startMinute());
                    ZonedDateTime closureEnd = localMinute(zone, day, holiday.endMinute());
                    if (candidate.isBefore(closureEnd) && closureStart.isBefore(windowEnd)) {
                        candidate = closureEnd;
                    }
                }
                if (candidate.isBefore(windowEnd) && !candidate.toInstant().isBefore(from)) {
                    return candidate.toInstant();
                }
            }
        }
        return null;
    }

    /**
     * Adds {@code businessMinutes} of working time to {@code from}.
     *
     * <p>The mirror of {@link #nextBusinessInstant}: the deadline of a budget. Returns the instant the
     * budget is exhausted, walking forward over closed days.
     */
    public static Instant addBusinessMinutes(BusinessCalendar calendar, Instant from,
                                             long businessMinutes) {
        if (calendar == null || from == null) {
            return null;
        }
        if (businessMinutes <= 0L) {
            return from;
        }
        long remaining = businessMinutes;
        ZoneId zone = ZoneId.of(calendar.timezone());
        ZonedDateTime cursor = from.atZone(zone);
        int dayOffset = 0;
        while (dayOffset <= MAX_DAYS_PER_SPAN) {
            LocalDate day = cursor.toLocalDate();
            for (AvailabilityWindow window : calendar.windowsFor(day.getDayOfWeek().getValue() % 7)) {
                int endMinute = window.crossesMidnight() ? 1440 : window.endMinute();
                ZonedDateTime windowStart = localMinute(zone, day, window.startMinute());
                ZonedDateTime windowEnd = localMinute(zone, day, endMinute);
                if (!windowEnd.isAfter(windowStart)) {
                    continue;
                }
                BusinessCalendar.Holiday holiday = calendar.holidayOn(day);
                if (holiday != null && holiday.wholeDay()) {
                    continue;
                }
                ZonedDateTime usableStart = windowStart.isBefore(cursor) ? cursor : windowStart;
                if (holiday != null && !holiday.wholeDay()) {
                    ZonedDateTime closureStart = localMinute(zone, day, holiday.startMinute());
                    ZonedDateTime closureEnd = localMinute(zone, day, holiday.endMinute());
                    if (usableStart.isBefore(closureEnd) && closureStart.isBefore(windowEnd)) {
                        usableStart = closureEnd;
                    }
                }
                if (!usableStart.isBefore(windowEnd)) {
                    continue;
                }
                long available = minutesBetween(usableStart, windowEnd);
                if (available >= remaining) {
                    return usableStart.plusMinutes(remaining).toInstant();
                }
                remaining -= available;
                cursor = windowEnd;
            }
            dayOffset++;
            cursor = day.plusDays(1).atStartOfDay(zone);
        }
        throw new IllegalArgumentException(
            "Refusing to add " + businessMinutes + " business minutes: no open window was found in "
                + MAX_DAYS_PER_SPAN + " local days. The calendar version may have no windows at all.");
    }

    /** The calendar version in force on {@code onDate}, newest {@code effectiveFrom} first. */
    public static BusinessCalendar resolveEffective(List<BusinessCalendar> versions, LocalDate onDate) {
        if (versions == null || versions.isEmpty()) {
            return null;
        }
        return versions.stream()
            .filter(calendar -> calendar.covers(onDate))
            .max(Comparator.comparing(BusinessCalendar::effectiveFrom)
                .thenComparing(BusinessCalendar::version))
            .orElse(null);
    }

    /** Groups flat window rows by weekday so the calendar constructor can index them. */
    public static Map<Integer, List<AvailabilityWindow>> groupWindows(
            List<AvailabilityWindow> windows) {
        Map<Integer, List<AvailabilityWindow>> grouped = new java.util.LinkedHashMap<>();
        for (AvailabilityWindow window : windows) {
            grouped.computeIfAbsent(window.dayOfWeek(), key -> new ArrayList<>()).add(window);
        }
        grouped.values().forEach(list -> list.sort(
            Comparator.comparingInt(AvailabilityWindow::startMinute)));
        return grouped;
    }

    private record Interval(ZonedDateTime from, ZonedDateTime to) {
    }
}