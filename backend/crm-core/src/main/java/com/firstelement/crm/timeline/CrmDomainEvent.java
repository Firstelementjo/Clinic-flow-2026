package com.firstelement.crm.timeline;

import com.firstelement.crm.domain.ConversationEventType;
import com.firstelement.crm.domain.MessageAuthorType;
import java.util.Map;
import java.util.UUID;

/**
 * One domain event, in the legacy envelope shape.
 *
 * <p>Mirrors {@code DomainEventEnvelope} from {@code packages/events}. Every event carries
 * its tenant and its project, so a subscriber never has to re-resolve the scope — and so a
 * timeline writer physically cannot record an event for a row it did not read the scope of.
 *
 * @param type          the event kind
 * @param organizationId tenant, never null
 * @param projectId      project, never null
 * @param aggregateId    the id of the aggregate the event is about
 * @param aggregateType  {@code Conversation}, {@code Message}, {@code Customer}, ...
 * @param payload        the event's attributes; never message content
 * @param occurredAt     when it happened
 * @param actorUserId    the acting user, or {@code null} for a system-initiated event
 */
public record CrmDomainEvent(DomainEventType type,
                             UUID organizationId,
                             UUID projectId,
                             UUID aggregateId,
                             String aggregateType,
                             Map<String, Object> payload,
                             java.time.LocalDateTime occurredAt,
                             UUID actorUserId) {

    public CrmDomainEvent {
        if (type == null) {
            throw new IllegalArgumentException("type");
        }
        if (organizationId == null) {
            throw new IllegalArgumentException("organizationId");
        }
        if (projectId == null) {
            throw new IllegalArgumentException("projectId");
        }
        if (aggregateId == null) {
            throw new IllegalArgumentException("aggregateId");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt");
        }
        // LinkedHashMap + unmodifiableMap rather than Map.copyOf: several payloads legitimately
        // carry a null (an unassignment has no employeeId, a status change has no priority), and
        // Map.copyOf rejects null values outright.
        payload = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(
            payload == null ? Map.of() : payload));
    }

    public static CrmDomainEvent conversationCreated(UUID organizationId, UUID projectId,
                                                      UUID conversationId, UUID customerId,
                                                      String channel, String mode,
                                                      java.time.LocalDateTime occurredAt) {
        return new CrmDomainEvent(DomainEventType.CONVERSATION_CREATED, organizationId, projectId,
            conversationId, "Conversation",
            Map.of("customerId", customerId, "channel", channel, "mode", mode),
            occurredAt, null);
    }

    public static CrmDomainEvent messageCreated(UUID organizationId, UUID projectId,
                                                 UUID conversationId, UUID customerId,
                                                 UUID messageId, String channel, String direction,
                                                 String authorType, int bodyLength,
                                                 java.time.LocalDateTime occurredAt,
                                                 UUID actorUserId) {
        return new CrmDomainEvent(DomainEventType.MESSAGE_CREATED, organizationId, projectId,
            messageId, "Message",
            Map.of("conversationId", conversationId, "customerId", customerId, "channel", channel,
                "direction", direction, "authorType", authorType, "bodyLength", bodyLength),
            occurredAt, actorUserId);
    }

    public static CrmDomainEvent assigned(UUID organizationId, UUID projectId,
                                          UUID conversationId, UUID employeeId,
                                          UUID previousEmployeeId, String reason,
                                          java.time.LocalDateTime occurredAt, UUID actorUserId) {
        // Map.of rejects null values and an unassignment legitimately has none, so the payload is
        // assembled with a null-tolerant map.
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("employeeId", employeeId);
        payload.put("previousEmployeeId", previousEmployeeId);
        payload.put("reason", reason);
        return new CrmDomainEvent(DomainEventType.CONVERSATION_ASSIGNED, organizationId, projectId,
            conversationId, "Conversation", payload, occurredAt, actorUserId);
    }

    public static CrmDomainEvent updated(UUID organizationId, UUID projectId,
                                         UUID conversationId, String status, String priority,
                                         String previousStatus,
                                         java.time.LocalDateTime occurredAt, UUID actorUserId) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        if (status != null) {
            payload.put("status", status);
        }
        if (priority != null) {
            payload.put("priority", priority);
        }
        payload.put("previousStatus", previousStatus);
        return new CrmDomainEvent(DomainEventType.CONVERSATION_UPDATED, organizationId, projectId,
            conversationId, "Conversation", payload, occurredAt, actorUserId);
    }

    public static CrmDomainEvent resolved(UUID organizationId, UUID projectId,
                                          UUID conversationId, String resolvedBy,
                                          int messageCount, java.time.LocalDateTime occurredAt,
                                          UUID actorUserId) {
        return new CrmDomainEvent(DomainEventType.CONVERSATION_RESOLVED, organizationId, projectId,
            conversationId, "Conversation",
            Map.of("resolvedBy", resolvedBy, "messageCount", messageCount), occurredAt, actorUserId);
    }

    public static CrmDomainEvent modeChanged(UUID organizationId, UUID projectId,
                                              UUID conversationId, String from, String to,
                                              String reason, java.time.LocalDateTime occurredAt,
                                              UUID actorUserId) {
        return new CrmDomainEvent(DomainEventType.CONVERSATION_MODE_CHANGED, organizationId,
            projectId, conversationId, "Conversation",
            Map.of("from", from, "to", to, "reason", reason), occurredAt, actorUserId);
    }

    public static CrmDomainEvent customerCreated(UUID organizationId, UUID projectId,
                                                  UUID customerId, String channel,
                                                  String externalId, java.time.LocalDateTime occurredAt) {
        return new CrmDomainEvent(DomainEventType.CUSTOMER_CREATED, organizationId, projectId,
            customerId, "Customer", Map.of("channel", channel, "externalId", externalId),
            occurredAt, null);
    }

    /** The {@code ConversationEventType} this event maps to, or {@code null}. */
    public ConversationEventType eventTypeOrNull() {
        return TimelineEventMapper.mapType(this);
    }

    /** The actor to stamp on the timeline row; {@code SYSTEM} when there is no user. */
    public MessageAuthorType actorOrSystem() {
        return actorUserId == null ? MessageAuthorType.SYSTEM : MessageAuthorType.EMPLOYEE;
    }
}