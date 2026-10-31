package com.firstelement.crm.timeline;

import com.firstelement.crm.domain.ConversationEventType;
import com.firstelement.crm.domain.MessageAuthorType;
import com.firstelement.crm.domain.MessageDirection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Maps a domain event onto the append-only conversation timeline.
 *
 * <h2>A direct port of {@code packages/messaging/src/timeline.ts}</h2>
 *
 * <p>The legacy {@code mapEvent} function is reproduced branch for branch, including its two
 * "return {@code null}" cases and the exact payload keys. Two behaviours in the original are
 * load-bearing and are preserved deliberately:
 *
 * <ol>
 *   <li><b>Message content never travels.</b> The payload carries {@code bodyLength}, not the
 *       body. The body already lives in {@code messages}, where access is tenant-scoped;
 *       duplicating it onto an event stream widens where customer text has to be protected.</li>
 *   <li><b>Customer-level events have no conversation to hang from</b> and are dropped.
 *       {@code CUSTOMER_CREATED} therefore produces no timeline row, exactly as before.</li>
 * </ol>
 *
 * <p>This class is pure and takes no database, which is why the mapping is unit-tested
 * without PostgreSQL.
 */
public final class TimelineEventMapper {

    private TimelineEventMapper() {
    }

    /**
     * @return the timeline row to append, or {@code null} when the event does not belong on a
     *         conversation timeline
     */
    public static TimelineEntry map(CrmDomainEvent event) {
        if (event == null) {
            return null;
        }
        return switch (event.type()) {
            case CONVERSATION_CREATED -> new TimelineEntry(
                event.aggregateId(),
                ConversationEventType.CONVERSATION_CREATED,
                MessageAuthorType.SYSTEM,
                pick(event.payload(), "channel", "mode"));

            case MESSAGE_CREATED -> new TimelineEntry(
                requiredUuid(event.payload(), "conversationId"),
                MessageDirection.INBOUND.name().equals(text(event.payload(), "direction"))
                    ? ConversationEventType.MESSAGE_RECEIVED
                    : ConversationEventType.MESSAGE_SENT,
                authorType(text(event.payload(), "authorType")),
                // Length, not content: the body already lives in `messages`.
                Map.of("messageId", event.aggregateId(),
                    "bodyLength", number(event.payload(), "bodyLength")));

            case CONVERSATION_ASSIGNED -> new TimelineEntry(
                event.aggregateId(),
                event.payload().get("employeeId") == null
                    ? ConversationEventType.UNASSIGNED
                    : ConversationEventType.ASSIGNED,
                event.actorUserId() == null ? MessageAuthorType.SYSTEM : MessageAuthorType.EMPLOYEE,
                pick(event.payload(), "employeeId", "previousEmployeeId", "reason"));

            case CONVERSATION_UPDATED -> {
                if (event.payload().get("status") != null) {
                    yield new TimelineEntry(event.aggregateId(),
                        ConversationEventType.STATUS_CHANGED,
                        event.actorUserId() == null ? MessageAuthorType.SYSTEM : MessageAuthorType.EMPLOYEE,
                        Map.of("from", event.payload().get("previousStatus"),
                            "to", event.payload().get("status")));
                }
                if (event.payload().get("priority") != null) {
                    yield new TimelineEntry(event.aggregateId(),
                        ConversationEventType.PRIORITY_CHANGED,
                        event.actorUserId() == null ? MessageAuthorType.SYSTEM : MessageAuthorType.EMPLOYEE,
                        Map.of("to", event.payload().get("priority")));
                }
                // Neither status nor priority changed: nothing a human would read.
                yield null;
            }

            case CONVERSATION_RESOLVED -> new TimelineEntry(
                event.aggregateId(),
                ConversationEventType.STATUS_CHANGED,
                authorType(text(event.payload(), "resolvedBy")),
                Map.of("to", "RESOLVED",
                    "messageCount", number(event.payload(), "messageCount")));

            case CONVERSATION_MODE_CHANGED -> new TimelineEntry(
                event.aggregateId(),
                "HUMAN".equals(text(event.payload(), "to"))
                    ? ConversationEventType.TAKEN_OVER
                    : ConversationEventType.RETURNED_TO_AI,
                event.actorUserId() == null ? MessageAuthorType.SYSTEM : MessageAuthorType.EMPLOYEE,
                pick(event.payload(), "from", "to", "reason"));

            case CONVERSATION_SCORED -> new TimelineEntry(
                event.aggregateId(),
                ConversationEventType.SCORE_UPDATED,
                MessageAuthorType.SYSTEM,
                pick(event.payload(), "frustration", "sentiment", "urgency", "risk", "intent"));

            case CONVERSATION_ESCALATED -> new TimelineEntry(
                event.aggregateId(),
                ConversationEventType.ESCALATED,
                MessageAuthorType.AI,
                pick(event.payload(), "escalationId", "trigger", "reason", "intent"));

            // Customer-level and employee-level events have no conversation to hang from.
            case CUSTOMER_CREATED, CUSTOMER_UPDATED, EMPLOYEE_AVAILABILITY_CHANGED -> null;
        };
    }

    /** Convenience for the common case of "does this event produce a row?". */
    public static ConversationEventType mapType(CrmDomainEvent event) {
        TimelineEntry entry = map(event);
        return entry == null ? null : entry.type();
    }

    // ------------------------------------------------------------------

    private static MessageAuthorType authorType(String raw) {
        if (raw == null) {
            return MessageAuthorType.SYSTEM;
        }
        try {
            return MessageAuthorType.fromValue(raw);
        } catch (IllegalArgumentException e) {
            return MessageAuthorType.SYSTEM;
        }
    }

    private static String text(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : value.toString();
    }

    private static int number(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value instanceof Number number ? number.intValue() : 0;
    }

    /** {@link Map#of} rejects null values, and these payloads legitimately contain them. */
    private static Map<String, Object> pick(Map<String, Object> payload, String... keys) {
        Map<String, Object> picked = new LinkedHashMap<>();
        for (String key : keys) {
            picked.put(key, payload.get(key));
        }
        return picked;
    }

    private static UUID requiredUuid(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof UUID uuid) {
            return uuid;
        }
        throw new IllegalArgumentException(
            "payload." + key + " must be the conversation id as a UUID");
    }

    /**
     * The row to append.
     *
     * @param conversationId the conversation the entry hangs from
     * @param type           the timeline event type
     * @param actorType      who caused it
     * @param payload        non-sensitive attributes
     */
    public record TimelineEntry(UUID conversationId,
                                ConversationEventType type,
                                MessageAuthorType actorType,
                                Map<String, Object> payload) {
    }
}