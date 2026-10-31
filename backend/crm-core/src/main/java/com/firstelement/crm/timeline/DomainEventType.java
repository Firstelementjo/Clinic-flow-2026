package com.firstelement.crm.timeline;

/**
 * The domain-event vocabulary, ported verbatim from {@code packages/events/src/index.ts}.
 *
 * <p>The wire values are the dotted legacy strings, so an event stream captured from the
 * NestJS runtime and one produced by Spring are the same stream.
 */
public enum DomainEventType {
    CONVERSATION_CREATED("conversation.created"),
    CONVERSATION_UPDATED("conversation.updated"),
    CONVERSATION_ASSIGNED("conversation.assigned"),
    CONVERSATION_RESOLVED("conversation.resolved"),
    CONVERSATION_ESCALATED("conversation.escalated"),
    CONVERSATION_MODE_CHANGED("conversation.mode-changed"),
    CONVERSATION_SCORED("conversation.scored"),
    MESSAGE_CREATED("message.created"),
    CUSTOMER_CREATED("customer.created"),
    CUSTOMER_UPDATED("customer.updated"),
    EMPLOYEE_AVAILABILITY_CHANGED("employee.availability.changed");

    private final String value;

    DomainEventType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}