package com.firstelement.crm.domain;

/**
 * Who authored a message or a timeline entry. Ported verbatim from the verified
 * {@code "MessageAuthorType"} enum.
 *
 * <p>The same enum is used by {@code conversation_events."actorType"}, which is why the
 * legacy {@code timeline.ts} could write {@code actorType: 'SYSTEM'} directly.
 */
public enum MessageAuthorType {
    CUSTOMER,
    EMPLOYEE,
    AI,
    SYSTEM;

    public static MessageAuthorType fromValue(String value) {
        for (MessageAuthorType author : values()) {
            if (author.name().equals(value)) {
                return author;
            }
        }
        throw new IllegalArgumentException("Unknown message author type: " + value);
    }
}