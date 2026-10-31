package com.firstelement.automation.domain;

/**
 * One recurring availability window for one employee.
 *
 * <h2>Why minutes-from-local-midnight, not timestamps</h2>
 *
 * <p>Ported from {@code packages/routing/src/working-hours.ts} with the reasoning intact:
 * "Windows are stored as a local weekday plus minutes from local midnight, not as instants,
 * because working hours are a recurring local-time concept. Storing them as timestamps makes every
 * daylight-saving change a data migration." The baseline column is {@code employee_availability.
 * (dayOfWeek, startMinute, endMinute, timezone)} and {@code sla_calendar_windows} stores the same
 * three numbers with the zone on the parent calendar, so the two are the same shape.
 *
 * <p>{@code dayOfWeek} is 0 = Sunday .. 6 = Saturday, matching the Gulf working week the legacy
 * runtime used and matching {@code sla_calendar_windows.dayOfWeek}'s own CHECK.
 *
 * <p>Minutes are deliberately allowed to run past midnight by the caller. {@link WorkingHours}
 * handles a wrapping window as two cases, exactly as the legacy implementation did; the SLA
 * calendar table forbids it (a same-day CHECK) because an overnight shift there would be ambiguous
 * once read back for a DST week. The two are different writers with different answers, so both
 * behaviours are stated here rather than one of them being inherited by accident.
 */
public record AvailabilityWindow(int dayOfWeek, int startMinute, int endMinute, String timezone) {

    public AvailabilityWindow {
        if (dayOfWeek < 0 || dayOfWeek > 6) {
            throw new IllegalArgumentException("dayOfWeek must be 0..6 (0 = Sunday), got " + dayOfWeek);
        }
        if (startMinute < 0 || startMinute > 1440) {
            throw new IllegalArgumentException("startMinute out of range: " + startMinute);
        }
        if (endMinute < 0 || endMinute > 1440) {
            throw new IllegalArgumentException("endMinute out of range: " + endMinute);
        }
        if (timezone == null || timezone.isBlank()) {
            throw new IllegalArgumentException("timezone is required");
        }
    }

    /** Whether this window crosses local midnight (a night shift). */
    public boolean crossesMidnight() {
        return endMinute <= startMinute;
    }
}