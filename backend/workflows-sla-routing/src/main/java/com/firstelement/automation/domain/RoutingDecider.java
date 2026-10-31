package com.firstelement.automation.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The deterministic routing pipeline. Ported from {@code packages/routing/src/strategy.ts}.
 *
 * <pre>
 * intent → department → required skill → availability status
 *   → working hours → workload headroom → strategy
 * </pre>
 *
 * <h2>Why it is a pure function</h2>
 *
 * <p>The whole routing decision is testable without a database or a clock, which is what makes the
 * "deterministic and explainable" gate provable rather than asserted. {@code RoutingDeciderTest}
 * re-runs the same input a thousand times and requires the same answer.
 *
 * <h2>Ordering of the tie-break is load-bearing</h2>
 *
 * <p>Fewest active conversations, then the highest level of the <em>required</em> skill, then the
 * employee id ascending. The legacy code states the reason for the last term: "ties break on skill
 * level, then on id so the same input always produces the same assignment — a router that shuffles
 * under a tie is untestable." Nothing else may be consulted: no "created at", no hash order, no
 * database return order. A term that is not in the list below is a bug, because a term that varies
 * between two identical requests is what makes a queue unexplainable.
 */
public final class RoutingDecider {

    private RoutingDecider() {
    }

    /**
     * Runs the pipeline.
     *
     * @param candidates every employee who is a member of the project, however unlikely
     * @param requirement what the ticket asks for; null fields are wildcards
     * @param now         the instant the working-hours stage judges
     */
    public static RoutingOutcome select(List<RoutingCandidate> candidates,
                                        RoutingRequirement requirement,
                                        Instant now) {
        int considered = candidates == null ? 0 : candidates.size();
        if (considered == 0) {
            return new RoutingOutcome(null, RoutingStage.MEMBERSHIP, 0, 0,
                List.of(new RoutingOutcome.StageTrace(RoutingStage.MEMBERSHIP, 0, 0)),
                List.of(new RoutingOutcome.Reason("candidates", "0")));
        }

        List<RoutingCandidate> pool = new ArrayList<>(candidates);
        List<RoutingOutcome.StageTrace> trace = new ArrayList<>();
        RoutingStage blockedAt = null;

        // MEMBERSHIP is established by the caller: it loads only employees with a
        // project_memberships row for this project, exactly as the legacy loadCandidates did
        // ("Membership is the first filter, so routing can never hand a conversation to someone
        // who would get a 404 opening it"). Reaching here with an empty list IS the block.
        trace.add(new RoutingOutcome.StageTrace(RoutingStage.MEMBERSHIP, considered, pool.size()));

        if (requirement.department() != null && !requirement.department().isBlank()) {
            pool = filter(pool, RoutingStage.DEPARTMENT, trace,
                candidate -> requirement.department().equals(candidate.department()));
            if (pool.isEmpty()) {
                blockedAt = RoutingStage.DEPARTMENT;
            }
        } else {
            trace.add(new RoutingOutcome.StageTrace(RoutingStage.DEPARTMENT, considered, considered));
        }

        if (blockedAt == null && requirement.requiredSkill() != null
            && !requirement.requiredSkill().isBlank()) {
            pool = filter(pool, RoutingStage.SKILL, trace,
                candidate -> candidate.hasSkill(requirement.requiredSkill()));
            if (pool.isEmpty()) {
                blockedAt = RoutingStage.SKILL;
            }
        } else if (blockedAt == null) {
            trace.add(new RoutingOutcome.StageTrace(RoutingStage.SKILL, pool.size(), pool.size()));
        }

        if (blockedAt == null) {
            pool = filter(pool, RoutingStage.STATUS, trace, RoutingCandidate::atDesk);
            if (pool.isEmpty()) {
                blockedAt = RoutingStage.STATUS;
            }
        }

        if (blockedAt == null) {
            pool = filter(pool, RoutingStage.WORKING_HOURS, trace,
                candidate -> WorkingHours.isWithinWorkingHours(candidate.windows(), now));
            if (pool.isEmpty()) {
                blockedAt = RoutingStage.WORKING_HOURS;
            }
        }

        if (blockedAt == null) {
            pool = filter(pool, RoutingStage.WORKLOAD, trace, candidate -> !candidate.atCapacity());
            if (pool.isEmpty()) {
                blockedAt = RoutingStage.WORKLOAD;
            }
        }

        if (blockedAt != null) {
            return new RoutingOutcome(null, blockedAt, considered, 0, trace,
                List.of(new RoutingOutcome.Reason("blockedAt", blockedAt.name()),
                    new RoutingOutcome.Reason("explanation", blockedAt.explanation())));
        }

        RoutingCandidate chosen = pick(pool, requirement);
        List<RoutingOutcome.Reason> reasons = new ArrayList<>();
        reasons.add(new RoutingOutcome.Reason("strategy", requirement.strategy().name()));
        reasons.add(new RoutingOutcome.Reason("activeConversationCount",
            String.valueOf(chosen.activeConversationCount())));
        reasons.add(new RoutingOutcome.Reason("maxConcurrentConversations",
            String.valueOf(chosen.maxConcurrentConversations())));
        reasons.add(new RoutingOutcome.Reason("availabilityStatus", chosen.availabilityStatus()));
        if (requirement.requiredSkill() != null && !requirement.requiredSkill().isBlank()) {
            reasons.add(new RoutingOutcome.Reason("requiredSkillLevel",
                requirement.requiredSkill() + "=" + chosen.skillLevel(requirement.requiredSkill())));
        }
        reasons.add(new RoutingOutcome.Reason("eligiblePool", String.valueOf(pool.size())));
        return new RoutingOutcome(chosen, null, considered, pool.size(), trace, reasons);
    }

    private static List<RoutingCandidate> filter(List<RoutingCandidate> pool,
                                                 RoutingStage stage,
                                                 List<RoutingOutcome.StageTrace> trace,
                                                 java.util.function.Predicate<RoutingCandidate> test) {
        List<RoutingCandidate> next = pool.stream().filter(test).toList();
        trace.add(new RoutingOutcome.StageTrace(stage, pool.size(), next.size()));
        return next;
    }

    /**
     * The strategy. Both strategies route least-loaded in this runtime; see
     * {@link RoutingRequirement.Strategy} for why a real round-robin needs a cursor this schema does
     * not have.
     */
    static RoutingCandidate pick(List<RoutingCandidate> pool, RoutingRequirement requirement) {
        String skill = requirement.requiredSkill();
        Comparator<RoutingCandidate> order = Comparator
            .comparingInt(RoutingCandidate::activeConversationCount)
            // Highest skill level first.
            .thenComparing(Comparator.comparingInt((RoutingCandidate candidate) -> candidate.skillLevel(skill))
                .reversed())
            // The deterministic final term.
            .thenComparing(candidate -> candidate.employeeId().toString());
        return pool.stream().min(order).orElseThrow(
            () -> new IllegalStateException("pick() called on an empty pool"));
    }
}