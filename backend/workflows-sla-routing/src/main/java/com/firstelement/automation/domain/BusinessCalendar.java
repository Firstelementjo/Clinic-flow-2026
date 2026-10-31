package com.firstelement.automation.domain;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One versioned working week: the union of {@code sla_calendar_windows} and
 * {@code sla_calendar_holidays} for a single {@code sla_calendars} row.
 *
 * <h2>Why a calendar is versioned and effective-dated rather than {@code employee_availability}</h2>
 *
 * <p>Recorded in {@code V20261005030000__sla_calendars.sql}: {@code employee_availability} answers
 * "is this one employee available now", which is a routing input; an SLA has to answer "which
 * working week applied on 2026-03-14", and that must stay computable after somebody edits the
 * calendar. A single mutable row cannot answer the second question after the fact. The rows here
 * therefore belong to a calendar <em>version</em> with an {@code effectiveFrom}/{@code effectiveTo}
 * range, and {@code SlaCalendarRepository} resolves the version in force for a date.
 *
 * <h2>Windows are grouped by weekday, once</h2>
 *
 * <p>The per-clock-evaluation read is
 * {@code sla_calendar_windows_calendar_day_idx ("calendarId","dayOfWeek")}, so the grouping
 * happens here on purpose: a calendar with no window for a weekday is a non-working day, and
 * {@link BusinessTime} must be able to say so without a second query per day.
 *
 * @param holidays keyed by date; a whole-day closure has null bounds, a partial closure does not
 */
public record BusinessCalendar(String calendarId,
                               String key,
                               int version,
                               String timezone,
                               LocalDate effectiveFrom,
                               LocalDate effectiveTo,
                               Map<Integer, List<AvailabilityWindow>> windowsByDay,
                               Map<LocalDate, Holiday> holidays) {

    /** A dated closure. {@code null} bounds close the whole day. */
    public record Holiday(LocalDate date, String label, Integer startMinute, Integer endMinute) {

        public boolean wholeDay() {
            return startMinute == null || endMinute == null;
        }
    }

    public BusinessCalendar {
        if (calendarId == null || calendarId.isBlank()) {
            throw new IllegalArgumentException("calendarId is required");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key is required");
        }
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1, got " + version);
        }
        if (timezone == null || timezone.isBlank()) {
            throw new IllegalArgumentException("timezone is required");
        }
        if (effectiveFrom == null) {
            throw new IllegalArgumentException(
                "effectiveFrom is required: a calendar not yet in effect is a DRAFT, not a row with no date");
        }
        if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
            throw new IllegalArgumentException(
                "effectiveTo " + effectiveTo + " is before effectiveFrom " + effectiveFrom);
        }
        Map<Integer, List<AvailabilityWindow>> grouped = new LinkedHashMap<>();
        if (windowsByDay != null) {
            windowsByDay.forEach((day, list) -> {
                if (day != null && list != null && !list.isEmpty()) {
                    grouped.put(day, List.copyOf(list));
                }
            });
        }
        windowsByDay = java.util.Collections.unmodifiableMap(grouped);
        holidays = holidays == null ? Map.of()
            : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(holidays));
    }

    /** The windows for one weekday, 0 = Sunday. Empty means the day is not worked. */
    public List<AvailabilityWindow> windowsFor(int dayOfWeek) {
        return windowsByDay.getOrDefault(dayOfWeek, List.of());
    }

    /** The closure recorded for a date, if any. */
    public Holiday holidayOn(LocalDate date) {
        return holidays.get(date);
    }

    /** Whether {@code date} falls inside this version's effective range. */
    public boolean covers(LocalDate date) {
        return !date.isBefore(effectiveFrom) && (effectiveTo == null || !date.isAfter(effectiveTo));
    }
}