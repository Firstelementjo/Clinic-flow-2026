package com.firstelement.automation.domain;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Evaluates the three SLA clocks independently, in business time.
 *
 * <h2>Pause and resume without any state</h2>
 *
 * <p>There is no SLA-tick table and no per-conversation breach column (Phase 6a deliberately stopped
 * at the inputs). So elapsed time is <em>derived</em> on every read: the business minutes between
 * the clock's start instant and "now" (or its stop instant), computed against the calendar version
 * in force on that date. A clock therefore pauses over a weekend, a holiday and a partial closure
 * and resumes afterwards with nothing to reconcile, and a March ticket keeps its historical answer
 * after somebody edits the calendar — as long as the caller passes the version that applied then.
 *
 * <h2>Why the three clocks are independent</h2>
 *
 * <p>{@link ClockStart#firstResponse} starts at the conversation's creation; {@link ClockStart#
 * nextResponse} starts at the last reply we sent; {@link ClockStart#resolution} starts at creation
 * and stops at resolution. A ticket can therefore satisfy its first-response budget and breach its
 * resolution budget, which is the normal case and the reason the schema refuses an inverted pair.
 *
 * <h2>Breached is a derived fact with a named cause</h2>
 *
 * <p>{@link ClockState#BREACHED} is only reachable with a promised budget and elapsed &ge; budget.
 * A clock with no budget is {@link ClockState#NOT_PROMISED}, never {@code BREACHED}: an operator
 * must be able to tell "we promised nothing" from "we promised and missed it".
 */
public final class SlaClockEvaluator {

    /** The states a clock can be in. */
    public enum ClockState {
        /** Running, inside its budget. */
        ON_TRACK,
        /** Past the warning lead time but still inside the budget. */
        AT_RISK,
        /** Past the budget. */
        BREACHED,
        /** The budget was met and the clock stopped. */
        MET,
        /** This policy promises nothing for this clock. */
        NOT_PROMISED,
        /** The clock has not started yet. */
        NOT_STARTED
    }

    /**
     * The instants each clock runs between.
     *
     * @param nextResponseStart the last reply boundary, or {@code null} when nothing has been
     *                          answered yet — in which case the next-response clock has not started
     * @param firstResponseAt   when the first response landed, or {@code null}
     * @param resolvedAt        when the conversation resolved, or {@code null}
     */
    public record ClockStart(LocalDateTime conversationCreatedAt,
                             LocalDateTime nextResponseStart,
                             LocalDateTime firstResponseAt,
                             LocalDateTime resolvedAt,
                             LocalDateTime now) {

        public ClockStart {
            if (conversationCreatedAt == null) {
                throw new IllegalArgumentException("conversationCreatedAt is required");
            }
            if (now == null) {
                throw new IllegalArgumentException("now is required");
            }
        }

        /** When the first-response clock stops, or {@code null} when it is still running. */
        public LocalDateTime firstResponseStop() {
            return firstResponseAt;
        }

        /** When the resolution clock stops, or {@code null} when it is still running. */
        public LocalDateTime resolutionStop() {
            return resolvedAt;
        }
    }

    /** One clock's assessment. */
    public record Clock(SlaClockType type,
                        ClockState state,
                        Integer budgetMinutes,
                        long elapsedMinutes,
                        Integer remainingMinutes,
                        Instant dueAt,
                        String explanation) {
    }

    /** The three clocks of one conversation under one policy, plus why. */
    public record Assessment(SlaPolicySnapshot policy,
                             Map<SlaClockType, Clock> clocks,
                             String calendarKey,
                             int calendarVersion) {

        public Assessment {
            clocks = clocks == null ? Map.of() : java.util.Collections.unmodifiableMap(
                new LinkedHashMap<>(clocks));
        }

        public Clock clock(SlaClockType type) {
            return clocks.get(type);
        }

        /** Whether any promised clock is breached. The operator's "needs attention now" answer. */
        public boolean breached() {
            return clocks.values().stream().anyMatch(clock -> clock.state() == ClockState.BREACHED);
        }

        /** The clock types that are breached, in enum order — never name order. */
        public List<SlaClockType> breachedClocks() {
            return java.util.Arrays.stream(SlaClockType.values())
                .filter(type -> clocks.containsKey(type)
                    && clocks.get(type).state() == ClockState.BREACHED)
                .toList();
        }
    }

    private SlaClockEvaluator() {
    }

    /**
     * Evaluates all three clocks.
     *
     * @param policy   the policy in force
     * @param calendar the calendar version that applies to this conversation's window, or
     *                 {@code null} for a 24/7 policy ({@code sla_policies.calendarId IS NULL})
     * @param start    the clock boundaries
     */
    public static Assessment evaluate(SlaPolicySnapshot policy, BusinessCalendar calendar,
                                      ClockStart start) {
        if (policy == null || start == null) {
            throw new IllegalArgumentException("policy and start are required");
        }
        Map<SlaClockType, Clock> clocks = new LinkedHashMap<>();
        clocks.put(SlaClockType.FIRST_RESPONSE, firstResponse(policy, calendar, start));
        clocks.put(SlaClockType.NEXT_RESPONSE, nextResponse(policy, calendar, start));
        clocks.put(SlaClockType.RESOLUTION, resolution(policy, calendar, start));
        return new Assessment(policy, clocks,
            calendar == null ? null : calendar.key(),
            calendar == null ? 0 : calendar.version());
    }

    /** Evaluates one clock in isolation. Public because the escalation scheduler reads one. */
    public static Clock evaluateClock(SlaClockType type, SlaPolicySnapshot policy,
                                      BusinessCalendar calendar, ClockStart start) {
        return switch (type) {
            case FIRST_RESPONSE -> firstResponse(policy, calendar, start);
            case NEXT_RESPONSE -> nextResponse(policy, calendar, start);
            case RESOLUTION -> resolution(policy, calendar, start);
        };
    }

    private static Clock firstResponse(SlaPolicySnapshot policy, BusinessCalendar calendar,
                                       ClockStart start) {
        Integer budget = policy.budgetFor(SlaClockType.FIRST_RESPONSE);
        LocalDateTime stop = start.firstResponseStop();
        LocalDateTime effectiveNow = stop != null ? stop : start.now();
        long elapsed = BusinessTime.businessMinutesBetween(calendar, start.conversationCreatedAt(),
            effectiveNow);
        Instant due = dueAt(calendar, start.conversationCreatedAt(), budget);
        return state(SlaClockType.FIRST_RESPONSE, budget, elapsed, due, policy,
            stop != null, "measured from conversation creation");
    }

    private static Clock nextResponse(SlaPolicySnapshot policy, BusinessCalendar calendar,
                                      ClockStart start) {
        Integer budget = policy.budgetFor(SlaClockType.NEXT_RESPONSE);
        if (start.nextResponseStart() == null) {
            // Never answered: this clock has not started, which is a different state from
            // "breached". A ticket nobody has replied to yet is not a missed next-response
            // deadline; it is the first-response clock's job.
            return new Clock(SlaClockType.NEXT_RESPONSE,
                budget == null ? ClockState.NOT_PROMISED : ClockState.NOT_STARTED,
                budget, 0L, null, null,
                budget == null ? "the policy promises no next-response budget"
                    : "the next-response clock starts at the first reply");
        }
        long elapsed = BusinessTime.businessMinutesBetween(calendar, start.nextResponseStart(),
            start.now());
        Instant due = dueAt(calendar, start.nextResponseStart(), budget);
        return state(SlaClockType.NEXT_RESPONSE, budget, elapsed, due, policy, false,
            "measured from the last reply we sent");
    }

    private static Clock resolution(SlaPolicySnapshot policy, BusinessCalendar calendar,
                                    ClockStart start) {
        Integer budget = policy.budgetFor(SlaClockType.RESOLUTION);
        LocalDateTime stop = start.resolutionStop();
        LocalDateTime effectiveNow = stop != null ? stop : start.now();
        long elapsed = BusinessTime.businessMinutesBetween(calendar, start.conversationCreatedAt(),
            effectiveNow);
        Instant due = dueAt(calendar, start.conversationCreatedAt(), budget);
        return state(SlaClockType.RESOLUTION, budget, elapsed, due, policy, stop != null,
            stop != null ? "measured from creation to resolution"
                : "measured from creation, still running");
    }

    private static Clock state(SlaClockType type, Integer budget, long elapsed, Instant due,
                               SlaPolicySnapshot policy, boolean stopped, String basis) {
        if (budget == null) {
            return new Clock(type, ClockState.NOT_PROMISED, null, elapsed, null, null,
                "the policy promises no " + label(type) + " budget (" + basis + ")");
        }
        int remaining = (int) (budget - elapsed);
        if (stopped) {
            ClockState state = remaining < 0 ? ClockState.BREACHED : ClockState.MET;
            return new Clock(type, state, budget, elapsed, remaining, due,
                (remaining < 0 ? "stopped " + (-remaining) + " business minutes late"
                    : "met with " + remaining + " business minutes to spare") + ", " + basis);
        }
        ClockState state;
        String explanation;
        if (remaining < 0) {
            state = ClockState.BREACHED;
            explanation = "past the " + budget + "-minute budget by " + (-remaining)
                + " business minutes, " + basis;
        } else if (policy.warnBeforeMinutes() != null && elapsed >= budget - policy.warnBeforeMinutes()) {
            state = ClockState.AT_RISK;
            explanation = remaining + " business minutes left of " + budget + ", " + basis;
        } else {
            state = ClockState.ON_TRACK;
            explanation = remaining + " business minutes left of " + budget + ", " + basis;
        }
        return new Clock(type, state, budget, elapsed, remaining, due, explanation);
    }

    /**
     * When a budget expires, in wall-clock terms.
     *
     * <p>With a calendar this is the instant that is {@code budget} business minutes after the
     * start; without one it is plain elapsed minutes, because {@code sla_policies.calendarId IS
     * NULL} is an explicit "24/7" choice rather than an absence of configuration.
     */
    public static Instant dueAt(BusinessCalendar calendar, LocalDateTime start, Integer budget) {
        if (start == null || budget == null) {
            return null;
        }
        Instant from = start.toInstant(java.time.ZoneOffset.UTC);
        if (calendar == null) {
            return from.plusSeconds(budget * 60L);
        }
        return BusinessTime.addBusinessMinutes(calendar, from, budget.longValue());
    }

    private static String label(SlaClockType type) {
        return switch (type) {
            case FIRST_RESPONSE -> "first-response";
            case NEXT_RESPONSE -> "next-response";
            case RESOLUTION -> "resolution";
        };
    }

    /** Convenience for callers that already resolved a clock's boundaries. */
    public static Optional<Clock> breachedClock(Assessment assessment, SlaClockType type) {
        Clock clock = assessment == null ? null : assessment.clock(type);
        return clock != null && clock.state() == ClockState.BREACHED ? Optional.of(clock) : Optional.empty();
    }
}