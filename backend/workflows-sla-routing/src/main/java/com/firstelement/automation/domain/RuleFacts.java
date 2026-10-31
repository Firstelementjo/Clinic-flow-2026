package com.firstelement.automation.domain;

import java.util.Map;

/**
 * The facts an escalation rule is evaluated against: one turn's scores plus the
 * conversation-level counters that are not scores.
 *
 * <h2>Why a signal can be absent</h2>
 *
 * <p>{@code aiConfidence} only exists when an AI run produced a confidence value, so a customer
 * turn before any AI run has no {@code aiConfidence}. {@link RuleCondition#test} therefore treats an
 * absent signal as "does not match" and never as zero — see that method for why.
 */
public record RuleFacts(Integer frustration,
                        Integer sentiment,
                        Integer urgency,
                        Integer risk,
                        Integer intentConfidence,
                        Integer aiConfidence,
                        int aiTurnsWithoutResolution,
                        boolean explicitHumanRequest) {

    /** @return the value, or {@code null} when this turn did not produce the signal. */
    public Integer read(ScoreSignal signal) {
        return switch (signal) {
            case FRUSTRATION -> frustration;
            case SENTIMENT -> sentiment;
            case URGENCY -> urgency;
            case RISK -> risk;
            case INTENT_CONFIDENCE -> intentConfidence;
            case AI_CONFIDENCE -> aiConfidence;
            case AI_TURNS_WITHOUT_RESOLUTION -> aiTurnsWithoutResolution;
            case EXPLICIT_HUMAN_REQUEST -> explicitHumanRequest ? 1 : 0;
        };
    }

    /** The raw signal values, for the {@code conversation_scores.signals} snapshot. */
    public Map<String, Object> asSnapshot() {
        return Map.of(
            "frustration", frustration,
            "sentiment", sentiment,
            "urgency", urgency,
            "risk", risk,
            "intentConfidence", intentConfidence,
            "aiConfidence", aiConfidence == null ? "" : aiConfidence,
            "aiTurnsWithoutResolution", aiTurnsWithoutResolution,
            "explicitHumanRequest", explicitHumanRequest);
    }
}