package com.firstelement.crm.domain;

/**
 * Who is answering a conversation. Ported verbatim from the verified
 * {@code "ConversationMode"} enum.
 *
 * <p>Human takeover is the safety property this phase must not weaken: while the mode is
 * {@code HUMAN} an in-flight AI reply must not be sent. The transition itself is guarded
 * by {@code conversations."modeVersion"} in {@code ConversationService#changeMode}.
 */
public enum ConversationMode {
    AI,
    HUMAN;

    public static ConversationMode fromValue(String value) {
        for (ConversationMode mode : values()) {
            if (mode.name().equals(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown conversation mode: " + value);
    }
}