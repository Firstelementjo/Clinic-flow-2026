package com.firstelement.automation.domain;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Working-hours evaluation. Ported from {@code packages/routing/src/working-hours.ts}.
 *
 * <h2>The two decisions this class makes</h2>
 *
 * <ol>
 *   <li>"Now" as the <em>employee</em> experiences it. The conversion is done with
 *       {@link ZoneId}/{@link ZonedDateTime} rather than a hand-rolled offset table, which is the
 *       classic way this feature breaks in October — and the legacy comment said so explicitly:
 *       "{@code Intl} is doing the timezone arithmetic, including DST — a hand-rolled offset table
 *       is the classic way this feature breaks in October."</li>
 *   <li>Whether a moment is inside any of an employee's windows. No windows means <b>always</b>
 *       available, not never: "an organization that has not configured schedules yet should still
 *       be able to receive escalations, and a routing engine that silently assigned nothing would
 *       look like a broken queue."</li>
 * </ol>
 *
 * <h2>Why this is a domain class and not a repository</h2>
 *
 * <p>Because it is a pure function of (windows, instant). The routing decision is then testable
 * without a database or a clock, which is the property the legacy {@code strategy.ts} test suite
 * relied on and the reason a DST regression is caught by a unit test rather than in production.
 *
 * <h2>Zone validation fails loudly</h2>
 *
 * <p>An unknown IANA id throws {@link java.time.zone.ZoneRulesException} from
 * {@link ZoneId#of}. It is deliberately not caught and defaulted to UTC: a window that silently
 * evaluates in the wrong zone sends a night's escalations to the morning shift, and nothing else in
 * the system would object.
 */
public final class WorkingHours {

    private WorkingHours() {
    }

    /**
     * Whether {@code instant} is inside any of {@code windows}.
     *
     * @param windows the employee's windows; empty means "always available"
     * @param instant the moment to judge
     */
    public static boolean isWithinWorkingHours(List<AvailabilityWindow> windows, Instant instant) {
        if (windows == null || windows.isEmpty()) {
            return true;
        }
        LocalMoment local = LocalMoment.of(instant, windows.get(0).timezone());
        for (AvailabilityWindow window : windows) {
            if (contains(window, local)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The single-window predicate, split out so a caller with one window does not have to build a
     * list. A window that ends before it starts crosses midnight and both halves must match, which
     * is exactly the legacy behaviour for a 22:00-06:00 night shift.
     */
    public static boolean contains(AvailabilityWindow window, LocalMoment local) {
        if (window.crossesMidnight()) {
            int previousDay = (window.dayOfWeek() + 1) % 7;
            return (local.dayOfWeek() == window.dayOfWeek() && local.minuteOfDay() >= window.startMinute())
                || (local.dayOfWeek() == previousDay && local.minuteOfDay() < window.endMinute());
        }
        return local.dayOfWeek() == window.dayOfWeek()
            && local.minuteOfDay() >= window.startMinute()
            && local.minuteOfDay() < window.endMinute();
    }

    /** The minute-of-day and weekday an instant falls on, in one zone. */
    public record LocalMoment(int dayOfWeek, int minuteOfDay) {

        /** Converts {@code instant} into the calendar the zone's own wall clock would show. */
        public static LocalMoment of(Instant instant, String timezone) {
            ZonedDateTime local = instant.atZone(ZoneId.of(timezone));
            // java.time renders midnight as hour 0, which is already what minuteOfDay wants; the
            // legacy `hour === 24 ? 0 : hour` guard exists because Intl renders it as 24 in some
            // locales, and the same class of bug is worth keeping named.
            int hour = local.getHour() == 24 ? 0 : local.getHour();
            int minuteOfDay = hour * 60 + local.getMinute();
            return new LocalMoment(local.getDayOfWeek().getValue() % 7, minuteOfDay);
        }
    }

    /**
     * Converts a stored UTC timestamp into the calendar of {@code timezone}.
     *
     * <p>Every timestamp column in this schema is {@code timestamp(3) without time zone} holding
     * UTC (ADR-0002 / Phase-5a {@code UtcTime}), so this is the only conversion a calendar needs:
     * {@code LocalDateTime} (UTC) to {@code ZonedDateTime} (local). Exposed here rather than
     * duplicated in the SLA evaluator so there is exactly one answer to "what time is it for them".
     */
    public static ZonedDateTime toZoned(LocalDateTime utc, String timezone) {
        return utc.toInstant(java.time.ZoneOffset.UTC).atZone(ZoneId.of(timezone));
    }

    /** Formats a window as {@code 09:00–17:00}, for an operator-facing read model. */
    public static String formatWindow(AvailabilityWindow window) {
        return formatMinute(window.startMinute()) + "–" + formatMinute(window.endMinute());
    }

    private static String formatMinute(int minute) {
        int hours = (minute / 60) % 24;
        int minutes = minute % 60;
        return String.format("%02d:%02d", hours, minutes);
    }
}