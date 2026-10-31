package com.firstelement.airag.orchestration;

/**
 * What a run did, as the closed list {@code ai_provenance_records."disposition"} CHECKs.
 *
 * <h2>Why the list is closed and where it comes from</h2>
 *
 * <p>The schema constrains it to exactly these seven values, and this enum is the transcription. The
 * constraint is what makes attribution mean something: "the model did something" is not an attribution, and
 * an open-ended disposition field is where attribution quietly stops.
 *
 * <h2>The two that matter most</h2>
 *
 * <ul>
 *   <li>{@link #REFUSED} — the run was stopped by policy. {@code refused = true}, which the schema ties
 *       biconditionally to this value. A refusal is a <b>recorded outcome</b>, not an error: ADR 0007 Part 2
 *       rule 3 requires the conversation to fall back to "a clearly recorded no-action state", and this is
 *       it.</li>
 *   <li>{@link #ESCALATED} — the run decided a human should take over. ADR 0007 Part 2: a consequential
 *       action may only be <em>requested</em>. Escalation is the honest disposition for a run that wanted to
 *       do something consequential.</li>
 * </ul>
 */
public enum AiRunDisposition {

    /** The run produced a customer-visible answer. */
    ANSWERED,

    /** The run asked the customer a question instead of answering. */
    ASKED_CLARIFICATION,

    /** The run handed the conversation to a human. */
    ESCALATED,

    /** The run deliberately took no action: nothing to do, or nothing it was allowed to do. */
    NO_ACTION,

    /** The run resolved the conversation. */
    RESOLVED,

    /** Policy stopped the run. The reason is in {@code ai_run_steps} and the security stream. */
    REFUSED,

    /** A human's answer overrode the AI's. */
    OVERRIDDEN;

    /** The {@code ai_runs."decision"} enum value that accompanies this disposition, when there is one. */
    public String runDecision() {
        return switch (this) {
            case ANSWERED -> "REPLY";
            case ASKED_CLARIFICATION -> "ASK_CLARIFICATION";
            case ESCALATED -> "ESCALATE";
            case NO_ACTION -> "NO_ACTION";
            case RESOLVED -> "RESOLVE";
            case REFUSED -> null;
            case OVERRIDDEN -> null;
        };
    }

    /** The terminal {@code ai_runs."status"}. A refusal is FAILED; everything else is COMPLETED. */
    public String runStatus() {
        return this == REFUSED ? "FAILED" : "COMPLETED";
    }
}