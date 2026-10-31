package com.firstelement.crm.domain;

/** Message direction. Ported verbatim from the verified {@code "MessageDirection"} enum. */
public enum MessageDirection {
    INBOUND,
    OUTBOUND;

    public static MessageDirection fromValue(String value) {
        for (MessageDirection direction : values()) {
            if (direction.name().equals(value)) {
                return direction;
            }
        }
        throw new IllegalArgumentException("Unknown message direction: " + value);
    }
}