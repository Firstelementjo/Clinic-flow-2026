package com.firstelement.crm.domain;

/** Case outcome. Ported verbatim from the verified {@code "CaseOutcome"} enum. */
public enum CaseOutcome {
    RESOLVED,
    ESCALATED,
    UNRESOLVED;

    public static CaseOutcome fromValue(String value) {
        for (CaseOutcome outcome : values()) {
            if (outcome.name().equals(value)) {
                return outcome;
            }
        }
        throw new IllegalArgumentException("Unknown case outcome: " + value);
    }
}