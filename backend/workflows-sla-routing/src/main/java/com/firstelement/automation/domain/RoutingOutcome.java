package com.firstelement.automation.domain;

import java.util.List;

/**
 * A routing decision and everything that produced it.
 *
 * <h2>Why the trace is part of the result</h2>
 *
 * <p>Phase-06 acceptance gate 2: "a decision returns the inputs that produced it, not just a
 * result." The legacy {@code RoutingOutcome} carried only {@code blockedAt}, which answers "why was
 * nothing assigned" but not "why was <em>this</em> person assigned". So this record carries the
 * per-stage survivor counts <em>and</em> the candidate list, and the service writes them into
 * {@code workflow_run_actions.inputSnapshot}: the decision records its own inputs, which is what
 * makes it explainable forever rather than only while it is in memory.
 *
 * @param stageTrace one entry per filter that ran, in order, with the count before and after
 * @param reasons    the winner's inputs (workload, skill level, status) — the tie-break evidence
 */
public record RoutingOutcome(RoutingCandidate candidate,
                             RoutingStage blockedAt,
                             int considered,
                             int eligible,
                             List<StageTrace> stageTrace,
                             List<Reason> reasons) {

    /** One filter's effect on the pool. */
    public record StageTrace(RoutingStage stage, int before, int after) {
    }

    /** One input of the decision, stated as a fact rather than a verdict. */
    public record Reason(String key, String value) {
    }

    public RoutingOutcome {
        stageTrace = stageTrace == null ? List.of() : List.copyOf(stageTrace);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    /** Whether somebody was chosen. */
    public boolean assigned() {
        return candidate != null;
    }

    /** Whether the ticket has to stay in the unassigned queue. */
    public boolean queued() {
        return candidate == null;
    }

    /**
     * The operator-facing sentence when nothing was assigned.
     *
     * <p>Legacy {@code describeBlock}, unchanged, so an ops manager who learned the old wording
     * still recognises it.
     */
    public String blockedExplanation() {
        return blockedAt == null ? RoutingStage.NONE.explanation() : blockedAt.explanation();
    }
}