package com.firstelement.automation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The three SLA clocks, independently, in business time.
 *
 * <p>Each case is the product promise: a clinic may answer within 15 minutes and still take a day to
 * resolve, so a ticket can satisfy one budget and breach another, and "we promised nothing" has to
 * stay distinguishable from "we promised and missed it".
 */
class SlaClockEvaluatorTest {

    private static SlaPolicySnapshot policy(Integer first, Integer next, Integer resolution) {
        return new SlaPolicySnapshot("policy-1", "gold", 10, SlaClockType.RESOLUTION, first, next,
            resolution, null, 5, null);
    }

    private static BusinessCalendar calendar() {
        Map<Integer, List<AvailabilityWindow>> windows = BusinessTime.groupWindows(List.of(
            new AvailabilityWindow(0, 9 * 60, 17 * 60, "Asia/Riyadh"),
            new AvailabilityWindow(1, 9 * 60, 17 * 60, "Asia/Riyadh"),
            new AvailabilityWindow(2, 9 * 60, 17 * 60, "Asia/Riyadh"),
            new AvailabilityWindow(3, 9 * 60, 17 * 60, "Asia/Riyadh"),
            new AvailabilityWindow(4, 9 * 60, 17 * 60, "Asia/Riyadh")));
        return new BusinessCalendar("cal-1", "gulf-week", 1, "Asia/Riyadh",
            java.time.LocalDate.of(2026, 1, 1), null, windows, java.util.Map.of());
    }

    /** Wednesday 2026-03-11, 10:00 local Riyadh = 07:00Z. */
    private static LocalDateTime opened() {
        return LocalDateTime.of(2026, 3, 11, 7, 0);
    }

    @Test
    @DisplayName("a policy that promises nothing is NOT_PROMISED, never BREACHED")
    void notPromisedIsNotBreached() {
        SlaClockEvaluator.Assessment assessment = SlaClockEvaluator.evaluate(policy(null, null, null),
            null, new SlaClockEvaluator.ClockStart(opened(), null, null, null,
                opened().plusDays(30)));

        assertThat(assessment.breached()).isFalse();
        for (SlaClockType type : SlaClockType.values()) {
            assertThat(assessment.clock(type).state())
                .isEqualTo(SlaClockEvaluator.ClockState.NOT_PROMISED);
            assertThat(assessment.clock(type).budgetMinutes()).isNull();
            assertThat(assessment.clock(type).dueAt()).isNull();
            assertThat(assessment.clock(type).explanation()).contains("promises no");
        }
    }

    @Test
    @DisplayName("the three clocks are independent: first response met, resolution breached")
    void clocksAreIndependent() {
        // Opened 10:00, first response at 10:20 (20 business minutes of a 15-minute budget is a
        // breach, so use a 30-minute budget), and a resolution budget of one day still running.
        SlaPolicySnapshot policy = policy(30, null, 60);
        SlaClockEvaluator.Assessment assessment = SlaClockEvaluator.evaluate(policy, calendar(),
            new SlaClockEvaluator.ClockStart(opened(), opened().plusMinutes(20),
                opened().plusMinutes(20), null, opened().plusMinutes(90)));

        SlaClockEvaluator.Clock first = assessment.clock(SlaClockType.FIRST_RESPONSE);
        SlaClockEvaluator.Clock next = assessment.clock(SlaClockType.NEXT_RESPONSE);
        SlaClockEvaluator.Clock resolution = assessment.clock(SlaClockType.RESOLUTION);

        assertThat(first.state()).isEqualTo(SlaClockEvaluator.ClockState.MET);
        assertThat(first.elapsedMinutes()).isEqualTo(20L);
        assertThat(next.state()).isEqualTo(SlaClockEvaluator.ClockState.NOT_PROMISED);
        assertThat(resolution.state()).isEqualTo(SlaClockEvaluator.ClockState.BREACHED);
        assertThat(resolution.elapsedMinutes()).isEqualTo(90L);
        assertThat(assessment.breachedClocks()).containsExactly(SlaClockType.RESOLUTION);
    }

    @Test
    @DisplayName("the next-response clock does not start until the first reply exists")
    void nextResponseClockHasNotStarted() {
        SlaClockEvaluator.Assessment assessment = SlaClockEvaluator.evaluate(policy(null, 30, null),
            calendar(), new SlaClockEvaluator.ClockStart(opened(), null, null, null,
                opened().plusHours(4)));

        assertThat(assessment.clock(SlaClockType.NEXT_RESPONSE).state())
            .isEqualTo(SlaClockEvaluator.ClockState.NOT_STARTED);
        assertThat(assessment.clock(SlaClockType.NEXT_RESPONSE).explanation())
            .contains("starts at the first reply");
    }

    @Test
    @DisplayName("the next-response clock runs from the last reply we sent")
    void nextResponseRunsFromTheLastReply() {
        LocalDateTime lastReply = opened().plusMinutes(60);
        SlaClockEvaluator.Assessment assessment = SlaClockEvaluator.evaluate(policy(null, 30, null),
            calendar(), new SlaClockEvaluator.ClockStart(opened(), lastReply, opened().plusMinutes(10),
                null, lastReply.plusMinutes(45)));

        assertThat(assessment.clock(SlaClockType.NEXT_RESPONSE).elapsedMinutes()).isEqualTo(45L);
        assertThat(assessment.clock(SlaClockType.NEXT_RESPONSE).state())
            .isEqualTo(SlaClockEvaluator.ClockState.BREACHED);
    }

    @Test
    @DisplayName("a running clock inside its warning lead time is AT_RISK, not ON_TRACK")
    void atRiskBeforeTheWarningLeadTime() {
        // 30-minute budget, 5-minute warning, 26 minutes elapsed: inside the budget, inside the warning.
        SlaClockEvaluator.Assessment assessment = SlaClockEvaluator.evaluate(policy(null, null, 30),
            calendar(), new SlaClockEvaluator.ClockStart(opened(), null, null, null,
                opened().plusMinutes(26)));

        assertThat(assessment.clock(SlaClockType.RESOLUTION).state())
            .isEqualTo(SlaClockEvaluator.ClockState.AT_RISK);
        assertThat(assessment.clock(SlaClockType.RESOLUTION).remainingMinutes()).isEqualTo(4);
    }

    @Test
    @DisplayName("a stopped clock that ran late is still BREACHED, not MET")
    void stoppedLateIsBreached() {
        SlaClockEvaluator.Assessment assessment = SlaClockEvaluator.evaluate(policy(null, null, 30),
            calendar(), new SlaClockEvaluator.ClockStart(opened(), null, null,
                opened().plusMinutes(45), opened().plusHours(2)));

        assertThat(assessment.clock(SlaClockType.RESOLUTION).state())
            .isEqualTo(SlaClockEvaluator.ClockState.BREACHED);
        assertThat(assessment.clock(SlaClockType.RESOLUTION).explanation()).contains("late");
    }

    @Test
    @DisplayName("a clock pauses outside working hours and resumes afterwards")
    void pausesAndResumes() {
        // Thursday 16:00 local, and "now" is Sunday 11:00 local: 63 hours of wall clock, of which
        // 60 minutes fall on Thursday's tail and 120 on Sunday's head. Friday and Saturday are closed
        // in the Gulf week, so they contribute nothing at all.
        LocalDateTime thursdayLate = LocalDateTime.of(2026, 3, 12, 13, 0);
        SlaClockEvaluator.Assessment assessment = SlaClockEvaluator.evaluate(policy(null, null, 120),
            calendar(), new SlaClockEvaluator.ClockStart(thursdayLate, null, null, null,
                LocalDateTime.of(2026, 3, 15, 8, 0)));

        assertThat(assessment.clock(SlaClockType.RESOLUTION).elapsedMinutes()).isEqualTo(180L);
        // 180 business minutes against a 120-minute budget, so the clock really is breached: the pause
        // is what makes 63 hours of wall clock only two and a half hours of SLA.
        assertThat(assessment.clock(SlaClockType.RESOLUTION).state())
            .isEqualTo(SlaClockEvaluator.ClockState.BREACHED);
    }

    @Test
    @DisplayName("a budget that fits inside the open hours is MET once the conversation resolves")
    void pausedBudgetThatFitsIsMetOnResolution() {
        LocalDateTime thursdayLate = LocalDateTime.of(2026, 3, 12, 13, 0);
        SlaClockEvaluator.Assessment assessment = SlaClockEvaluator.evaluate(policy(null, null, 180),
            calendar(), new SlaClockEvaluator.ClockStart(thursdayLate, null, null,
                LocalDateTime.of(2026, 3, 15, 8, 0), LocalDateTime.of(2026, 3, 15, 8, 0)));

        assertThat(assessment.clock(SlaClockType.RESOLUTION).state())
            .isEqualTo(SlaClockEvaluator.ClockState.MET);
        assertThat(assessment.clock(SlaClockType.RESOLUTION).explanation())
            .contains("business minutes to spare");
    }

    @Test
    @DisplayName("the due instant lands inside working hours, not at 03:00 on a Sunday")
    void dueInstantLandsInsideWorkingHours() {
        SlaClockEvaluator.Clock clock = SlaClockEvaluator.evaluateClock(SlaClockType.RESOLUTION,
            policy(null, null, 120), calendar(),
            new SlaClockEvaluator.ClockStart(LocalDateTime.of(2026, 3, 12, 13, 0), null, null, null,
                LocalDateTime.of(2026, 3, 12, 13, 0)));

        // Thursday 16:00 local + 120 business minutes: 60 on Thursday's tail and 60 on Sunday's head,
        // i.e. Sunday 10:00 local = 07:00Z.
        assertThat(java.time.LocalDateTime.ofInstant(clock.dueAt(), java.time.ZoneOffset.UTC))
            .isEqualTo(LocalDateTime.of(2026, 3, 15, 7, 0));
    }

    @Test
    @DisplayName("a 24/7 policy has a due instant in plain elapsed time")
    void dueInstantWithoutACalendar() {
        SlaClockEvaluator.Clock clock = SlaClockEvaluator.evaluateClock(SlaClockType.RESOLUTION,
            policy(null, null, 120), null,
            new SlaClockEvaluator.ClockStart(opened(), null, null, null, opened()));

        assertThat(java.time.LocalDateTime.ofInstant(clock.dueAt(), java.time.ZoneOffset.UTC))
            .isEqualTo(opened().plusHours(2));
    }

    @Test
    @DisplayName("an inverted policy is refused on the read path, not just by the table CHECK")
    void invertedPolicyIsRefused() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
            () -> policy(60, null, 30));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
            () -> policy(60, 30, null));
    }

    @Test
    @DisplayName("a zero or negative budget is refused rather than breaching at birth")
    void nonPositiveBudgetIsRefused() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> policy(0, null, null));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> policy(null, null, -5));
    }

    @Test
    @DisplayName("the budget lookup follows the clock type")
    void budgetLookup() {
        SlaPolicySnapshot snapshot = policy(15, 60, 1440);

        assertThat(snapshot.budgetFor(SlaClockType.FIRST_RESPONSE)).isEqualTo(15);
        assertThat(snapshot.budgetFor(SlaClockType.NEXT_RESPONSE)).isEqualTo(60);
        assertThat(snapshot.budgetFor(SlaClockType.RESOLUTION)).isEqualTo(1440);
    }

    @Test
    @DisplayName("the breached-clock list is in enum order, never name order")
    void breachedClocksAreInEnumOrder() {
        SlaClockEvaluator.Assessment assessment = SlaClockEvaluator.evaluate(policy(15, 15, 15),
            null, new SlaClockEvaluator.ClockStart(opened(), null, null, opened().plusHours(5),
                opened().plusHours(5)));

        assertThat(assessment.breached()).isTrue();
        assertThat(assessment.breachedClocks()).containsExactly(SlaClockType.FIRST_RESPONSE,
            SlaClockType.RESOLUTION);
    }

    @Test
    @DisplayName("the clock type vocabulary is the stored enum's values")
    void clockTypeVocabulary() {
        assertThat(SlaClockType.values()).containsExactly(SlaClockType.FIRST_RESPONSE,
            SlaClockType.NEXT_RESPONSE, SlaClockType.RESOLUTION);
        assertThat(SlaClockType.fromWireValue("FIRST_RESPONSE"))
            .isEqualTo(SlaClockType.FIRST_RESPONSE);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> SlaClockType.fromWireValue("SECOND_RESPONSE"));
    }
}
