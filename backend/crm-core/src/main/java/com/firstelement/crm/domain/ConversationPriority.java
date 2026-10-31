package com.firstelement.crm.domain;

/** Conversation priority. Ported verbatim from the verified {@code "ConversationPriority"} enum. */
public enum ConversationPriority {
    LOW,
    NORMAL,
    HIGH,
    URGENT;

    public static ConversationPriority fromValue(String value) {
        for (ConversationPriority priority : values()) {
            if (priority.name().equals(value)) {
                return priority;
            }
        }
        throw new IllegalArgumentException("Unknown conversation priority: " + value);
    }
}