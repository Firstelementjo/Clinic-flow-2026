package com.firstelement.automation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The deterministic routing pipeline. Ported from
 * {@code packages/routing/src/strategy.test.ts} and extended with the properties the Phase-6 gate
 * actually turns on: determinism under a tie, and an explanation that says which stage emptied the
 * list.
 */
class RoutingDecisionTest {

    /** Fixed ids so the tie-break is assertable by value rather than by "whatever it picked". */
    private static final UUID LOW = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID HIGH = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private static final Instant WEDNESDAY_NOON = Instant.parse("2026-03-11T09:00:00Z");

    private static RoutingCandidate candidate(UUID id, String department, String status, int load,
                                              int max, String skill, int level) {
        return new RoutingCandidate(id, id, "Agent " + id, department, status, load, max,
            skill == null ? List.of() : List.of(new RoutingCandidate.Skill(skill, level)), List.of());
    }

    @Test
    @DisplayName("an empty roster is blocked at MEMBERSHIP")
    void emptyRoster() {
        RoutingOutcome outcome = RoutingDecider.select(List.of(),
            RoutingRequirement.leastLoaded(null, null), WEDNESDAY_NOON);

        assertThat(outcome.assigned()).isFalse();
        assertThat(outcome.blockedAt()).isEqualTo(RoutingStage.MEMBERSHIP);
        assertThat(outcome.considered()).isZero();
        assertThat(outcome.blockedExplanation()).isEqualTo("No employees are assigned to this project");
    }

    @Test
    @DisplayName("the least-loaded candidate wins")
    void leastLoadedWins() {
        RoutingOutcome outcome = RoutingDecider.select(List.of(
            candidate(HIGH, null, "ONLINE", 7, 10, null, 0),
            candidate(MID, null, "ONLINE", 3, 10, null, 0),
            candidate(LOW, null, "ONLINE", 5, 10, null, 0)),
            RoutingRequirement.leastLoaded(null, null), WEDNESDAY_NOON);

        assertThat(outcome.candidate().employeeId()).isEqualTo(MID);
        assertThat(outcome.eligible()).isEqualTo(3);
    }

    @Test
    @DisplayName("a tie on load breaks on the required skill's level, highest first")
    void skillLevelBreaksALoadTie() {
        RoutingOutcome outcome = RoutingDecider.select(List.of(
            candidate(LOW, null, "ONLINE", 2, 10, "billing", 1),
            candidate(HIGH, null, "ONLINE", 2, 10, "billing", 5),
            candidate(MID, null, "ONLINE", 2, 10, "billing", 3)),
            RoutingRequirement.leastLoaded(null, "billing"), WEDNESDAY_NOON);

        assertThat(outcome.candidate().employeeId()).isEqualTo(HIGH);
    }

    @Test
    @DisplayName("a tie on load and skill breaks on the employee id, ascending")
    void employeeIdBreaksARemainingTie() {
        RoutingOutcome outcome = RoutingDecider.select(List.of(
            candidate(HIGH, null, "ONLINE", 2, 10, "billing", 3),
            candidate(LOW, null, "ONLINE", 2, 10, "billing", 3),
            candidate(MID, null, "ONLINE", 2, 10, "billing", 3)),
            RoutingRequirement.leastLoaded(null, "billing"), WEDNESDAY_NOON);

        assertThat(outcome.candidate().employeeId()).isEqualTo(LOW);
    }

    @Test
    @DisplayName("a tie never depends on the input order")
    void inputOrderDoesNotChangeTheAnswer() {
        List<RoutingCandidate> roster = List.of(
            candidate(HIGH, null, "ONLINE", 2, 10, "billing", 3),
            candidate(LOW, null, "ONLINE", 2, 10, "billing", 3),
            candidate(MID, null, "BUSY", 2, 10, "billing", 3));

        UUID first = RoutingDecider.select(roster, RoutingRequirement.leastLoaded(null, "billing"),
            WEDNESDAY_NOON).candidate().employeeId();

        List<RoutingCandidate> reversed = new ArrayList<>(roster);
        java.util.Collections.reverse(reversed);
        for (int attempt = 0; attempt < 200; attempt++) {
            UUID chosen = RoutingDecider.select(reversed,
                RoutingRequirement.leastLoaded(null, "billing"), WEDNESDAY_NOON)
                .candidate().employeeId();
            assertThat(chosen).isEqualTo(first);
            java.util.Collections.rotate(reversed, 1);
        }
    }

    @Test
    @DisplayName("a required department with nobody in it blocks at DEPARTMENT")
    void departmentStage() {
        RoutingOutcome outcome = RoutingDecider.select(
            List.of(candidate(LOW, "support", "ONLINE", 0, 10, null, 0)),
            RoutingRequirement.leastLoaded("billing", null), WEDNESDAY_NOON);

        assertThat(outcome.blockedAt()).isEqualTo(RoutingStage.DEPARTMENT);
        assertThat(outcome.blockedExplanation()).isEqualTo("No employee in the required department");
    }

    @Test
    @DisplayName("a required skill nobody declares blocks at SKILL")
    void skillStage() {
        RoutingOutcome outcome = RoutingDecider.select(
            List.of(candidate(LOW, "billing", "ONLINE", 0, 10, "invoicing", 3)),
            RoutingRequirement.leastLoaded("billing", "refunds"), WEDNESDAY_NOON);

        assertThat(outcome.blockedAt()).isEqualTo(RoutingStage.SKILL);
    }

    @Test
    @DisplayName("BUSY stays eligible but OFFLINE, BREAK, MEAL, TRAINING and MEETING do not")
    void statusStage() {
        for (String away : List.of("OFFLINE", "BREAK", "MEAL", "TRAINING", "MEETING")) {
            RoutingOutcome outcome = RoutingDecider.select(
                List.of(candidate(LOW, null, away, 0, 10, null, 0)),
                RoutingRequirement.leastLoaded(null, null), WEDNESDAY_NOON);
            assertThat(outcome.blockedAt()).isEqualTo(RoutingStage.STATUS);
        }
        for (String present : List.of("ONLINE", "BUSY")) {
            RoutingOutcome outcome = RoutingDecider.select(
                List.of(candidate(LOW, null, present, 0, 10, null, 0)),
                RoutingRequirement.leastLoaded(null, null), WEDNESDAY_NOON);
            assertThat(outcome.assigned()).isTrue();
        }
    }

    @Test
    @DisplayName("nobody inside working hours blocks at WORKING_HOURS")
    void workingHoursStage() {
        RoutingCandidate nightShift = new RoutingCandidate(LOW, LOW, "Night", null, "ONLINE", 0, 10,
            List.of(), List.of(new AvailabilityWindow(1, 22 * 60, 2 * 60, "UTC")));
        RoutingOutcome outcome = RoutingDecider.select(List.of(nightShift),
            RoutingRequirement.leastLoaded(null, null), WEDNESDAY_NOON);

        assertThat(outcome.blockedAt()).isEqualTo(RoutingStage.WORKING_HOURS);
        assertThat(outcome.blockedExplanation()).isEqualTo("Nobody is inside their working hours");
    }

    @Test
    @DisplayName("everyone at their conversation limit blocks at WORKLOAD")
    void workloadStage() {
        RoutingOutcome outcome = RoutingDecider.select(List.of(
            candidate(LOW, null, "ONLINE", 5, 5, null, 0),
            candidate(MID, null, "ONLINE", 9, 5, null, 0)),
            RoutingRequirement.leastLoaded(null, null), WEDNESDAY_NOON);

        assertThat(outcome.blockedAt()).isEqualTo(RoutingStage.WORKLOAD);
        assertThat(outcome.blockedExplanation())
            .isEqualTo("Everyone eligible is at their conversation limit");
    }

    @Test
    @DisplayName("capacity is exclusive: one below the limit is eligible, at the limit is not")
    void capacityBoundary() {
        assertThat(candidate(LOW, null, "ONLINE", 4, 5, null, 0).atCapacity()).isFalse();
        assertThat(candidate(LOW, null, "ONLINE", 5, 5, null, 0).atCapacity()).isTrue();
    }

    @Test
    @DisplayName("the stage trace explains which filter emptied the list")
    void stageTraceIsCompleteAndExplainable() {
        RoutingOutcome outcome = RoutingDecider.select(List.of(
            candidate(LOW, "billing", "ONLINE", 5, 5, null, 0),
            candidate(MID, "billing", "ONLINE", 5, 5, null, 0)),
            RoutingRequirement.leastLoaded("billing", null), WEDNESDAY_NOON);

        assertThat(outcome.blockedAt()).isEqualTo(RoutingStage.WORKLOAD);
        assertThat(outcome.stageTrace()).extracting(RoutingOutcome.StageTrace::stage)
            .containsExactly(RoutingStage.MEMBERSHIP, RoutingStage.DEPARTMENT, RoutingStage.SKILL,
                RoutingStage.STATUS, RoutingStage.WORKING_HOURS, RoutingStage.WORKLOAD);
        assertThat(outcome.stageTrace()).extracting(RoutingOutcome.StageTrace::after)
            .containsExactly(2, 2, 2, 2, 2, 0);
    }

    @Test
    @DisplayName("a successful decision records the winner's inputs, not just its id")
    void aSuccessCarriesItsInputs() {
        RoutingOutcome outcome = RoutingDecider.select(List.of(
            candidate(LOW, "billing", "BUSY", 4, 10, "refunds", 4),
            candidate(MID, "billing", "ONLINE", 1, 10, "refunds", 2)),
            RoutingRequirement.leastLoaded("billing", "refunds"), WEDNESDAY_NOON);

        assertThat(outcome.reasons()).extracting(RoutingOutcome.Reason::key)
            .contains("strategy", "activeConversationCount", "maxConcurrentConversations",
                "availabilityStatus", "requiredSkillLevel", "eligiblePool");
        assertThat(outcome.reasons())
            .filteredOn(reason -> reason.key().equals("requiredSkillLevel"))
            .singleElement()
            .satisfies(reason -> assertThat(reason.value()).isEqualTo("refunds=2"));
    }

    @Test
    @DisplayName("a null department or skill is a wildcard, not a requirement to be null")
    void wildcards() {
        RoutingOutcome withWildcards = RoutingDecider.select(
            List.of(candidate(LOW, "anything", "ONLINE", 0, 10, "other", 1)),
            RoutingRequirement.leastLoaded(null, null), WEDNESDAY_NOON);
        assertThat(withWildcards.assigned()).isTrue();

        RoutingOutcome blankWildcards = RoutingDecider.select(
            List.of(candidate(LOW, "anything", "ONLINE", 0, 10, "other", 1)),
            new RoutingRequirement("  ", "", RoutingRequirement.Strategy.LEAST_LOADED),
            WEDNESDAY_NOON);
        assertThat(blankWildcards.assigned()).isTrue();
    }

    @Test
    @DisplayName("ROUND_ROBIN is accepted and routes least-loaded, as the legacy runtime did")
    void roundRobinRoutesLeastLoaded() {
        List<RoutingCandidate> roster = List.of(
            candidate(HIGH, null, "ONLINE", 5, 10, null, 0),
            candidate(LOW, null, "ONLINE", 1, 10, null, 0));
        RoutingOutcome outcome = RoutingDecider.select(roster,
            new RoutingRequirement(null, null, RoutingRequirement.Strategy.ROUND_ROBIN), WEDNESDAY_NOON);

        assertThat(outcome.candidate().employeeId()).isEqualTo(LOW);
        assertThat(outcome.reasons()).extracting(RoutingOutcome.Reason::key).contains("strategy");
    }

    @Test
    @DisplayName("a candidate that is malformed is refused rather than silently defaulted")
    void candidateValidation() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> new RoutingCandidate(null, LOW, "x", null, "ONLINE", 0, 5, List.of(), List.of()));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> new RoutingCandidate(LOW, LOW, "x", null, "ONLINE", -1, 5, List.of(), List.of()));
    }
}