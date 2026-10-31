package com.firstelement.crm.domain;

/**
 * The append-only conversation timeline vocabulary. Ported verbatim from the verified
 * {@code "ConversationEventType"} enum.
 *
 * <p>Rows in {@code conversation_events} are never updated or deleted; see
 * {@code ConversationTimelineService}.
 */
public enum ConversationEventType {
    CONVERSATION_CREATED,
    MESSAGE_SENT,
    MESSAGE_RECEIVED,
    ASSIGNED,
    UNASSIGNED,
    TAKEN_OVER,
    RETURNED_TO_AI,
    ESCALATED,
    SCORE_UPDATED,
    STATUS_CHANGED,
    PRIORITY_CHANGED,
    TAG_ADDED,
    NOTE_ADDED;

    public static ConversationEventType fromValue(String value) {
        for (ConversationEventType type : values()) {
            if (type.name().equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown conversation event type: " + value);
    }
}