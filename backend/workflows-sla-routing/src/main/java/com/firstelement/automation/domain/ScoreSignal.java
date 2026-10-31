package com.firstelement.automation.domain;

/**
 * The signals an escalation rule may test. Ported verbatim from
 * {@code packages/ai/src/scoring/rules.ts}.
 *
 * <p>Two of the eight are deliberately <b>not</b> scores, and the legacy comments say why:
 * {@link #AI_TURNS_WITHOUT_RESOLUTION} is a count, and {@link #EXPLICIT_HUMAN_REQUEST} is 1 or 0
 * because "1 when the customer explicitly asked for a person".
 *
 * <p>Closed enum rather than free text: an unknown signal name in a stored rule must fail loudly
 * rather than read as zero, because "a signal the evaluator did not produce must not silently
 * count as zero and fire an '<= 30' rule".
 */
public enum ScoreSignal {

    FRUSTRATION("frustration"),
    SENTIMENT("sentiment"),
    URGENCY("urgency"),
    RISK("risk"),
    INTENT_CONFIDENCE("intentConfidence"),
    AI_CONFIDENCE("aiConfidence"),
    /** Not a score: how many AI turns have passed without resolution. */
    AI_TURNS_WITHOUT_RESOLUTION("aiTurnsWithoutResolution"),
    /** 1 when the customer explicitly asked for a person, else 0. */
    EXPLICIT_HUMAN_REQUEST("explicitHumanRequest");

    private final String wireValue;

    ScoreSignal(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    /** @return the signal, or {@code null} when the stored value names no signal. */
    public static ScoreSignal fromWireValue(String value) {
        if (value == null) {
            return null;
        }
        for (ScoreSignal signal : values()) {
            if (signal.wireValue.equals(value)) {
                return signal;
            }
        }
        return null;
    }
}