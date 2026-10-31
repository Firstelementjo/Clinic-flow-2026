package com.firstelement.crm.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstelement.crm.domain.ConversationEventType;
import com.firstelement.crm.domain.MessageAuthorType;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The domain-event → timeline mapping, with no database.
 *
 * <p>Branch-for-branch comparison against {@code packages/messaging/src/timeline.ts}. Two of the
 * original's behaviours are asserted explicitly because they are easy to "improve" into bugs:
 * a message payload carries its <em>length</em> and never its body, and a customer-level event
 * produces no row at all.
 */
class TimelineEventMapperTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID CONVERSATION = UUID.randomUUID();
    private static final UUID MESSAGE = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID EMPLOYEE = UUID.randomUUID();
    private static final LocalDateTime AT = LocalDateTime.of(2026, 8, 14, 9, 30);

    private static CrmDomainEvent base(DomainEventType type) {
        return new CrmDomainEvent(type, ORG, PROJECT, CONVERSATION, "Conversation",
            java.util.Map.of(), AT, null);
    }

    @Test
    @DisplayName("conversation.created becomes CONVERSATION_CREATED by SYSTEM")
    void conversationCreated() {
        CrmDomainEvent event = CrmDomainEvent.conversationCreated(ORG, PROJECT, CONVERSATION,
            CUSTOMER, "WHATSAPP", "AI", AT);

        TimelineEventMapper.TimelineEntry entry = TimelineEventMapper.map(event);

        assertThat(entry).isNotNull();
        assertThat(entry.type()).isEqualTo(ConversationEventType.CONVERSATION_CREATED);
        assertThat(entry.actorType()).isEqualTo(MessageAuthorType.SYSTEM);
        assertThat(entry.conversationId()).isEqualTo(CONVERSATION);
        assertThat(entry.payload()).containsEntry("channel", "WHATSAPP").containsEntry("mode", "AI");
    }

    @Test
    @DisplayName("an inbound message becomes MESSAGE_RECEIVED; an outbound one MESSAGE_SENT")
    void messageDirectionDecidesTheType() {
        TimelineEventMapper.TimelineEntry inbound = TimelineEventMapper.map(
            CrmDomainEvent.messageCreated(ORG, PROJECT, CONVERSATION, CUSTOMER, MESSAGE, "MOCK",
                "INBOUND", "CUSTOMER", 17, AT, null));
        assertThat(inbound.type()).isEqualTo(ConversationEventType.MESSAGE_RECEIVED);

        TimelineEventMapper.TimelineEntry outbound = TimelineEventMapper.map(
            CrmDomainEvent.messageCreated(ORG, PROJECT, CONVERSATION, CUSTOMER, MESSAGE, "MOCK",
                "OUTBOUND", "EMPLOYEE", 17, AT, EMPLOYEE));
        assertThat(outbound.type()).isEqualTo(ConversationEventType.MESSAGE_SENT);
        assertThat(outbound.actorType()).isEqualTo(MessageAuthorType.EMPLOYEE);
    }

    @Test
    @DisplayName("a message payload carries bodyLength, never the body")
    void messagePayloadCarriesLengthOnly() {
        TimelineEventMapper.TimelineEntry entry = TimelineEventMapper.map(
            CrmDomainEvent.messageCreated(ORG, PROJECT, CONVERSATION, CUSTOMER, MESSAGE, "MOCK",
                "OUTBOUND", "AI", 42, AT, null));

        assertThat(entry.payload()).containsEntry("messageId", MESSAGE);
        assertThat(entry.payload()).containsEntry("bodyLength", 42);
        assertThat(entry.payload().values().toString()).doesNotContain("body");
        assertThat(entry.payload()).doesNotContainKey("body");
    }

    @Test
    @DisplayName("an assignment becomes ASSIGNED and a release becomes UNASSIGNED")
    void assignmentBranches() {
        TimelineEventMapper.TimelineEntry assigned = TimelineEventMapper.map(
            CrmDomainEvent.assigned(ORG, PROJECT, CONVERSATION, EMPLOYEE, null, "MANUAL", AT,
                EMPLOYEE));
        assertThat(assigned.type()).isEqualTo(ConversationEventType.ASSIGNED);
        assertThat(assigned.actorType()).isEqualTo(MessageAuthorType.EMPLOYEE);

        TimelineEventMapper.TimelineEntry released = TimelineEventMapper.map(
            CrmDomainEvent.assigned(ORG, PROJECT, CONVERSATION, null, EMPLOYEE, "MANUAL", AT,
                EMPLOYEE));
        assertThat(released.type()).isEqualTo(ConversationEventType.UNASSIGNED);
        assertThat(released.payload()).containsEntry("previousEmployeeId", EMPLOYEE);
    }

    @Test
    @DisplayName("a system-initiated assignment has no EMPLOYEE actor")
    void systemAssignmentActor() {
        TimelineEventMapper.TimelineEntry entry = TimelineEventMapper.map(
            CrmDomainEvent.assigned(ORG, PROJECT, CONVERSATION, EMPLOYEE, null, "ROUTING", AT,
                null));

        assertThat(entry.actorType()).isEqualTo(MessageAuthorType.SYSTEM);
    }

    @Test
    @DisplayName("a status change records from and to; a priority change records only to")
    void updateBranches() {
        TimelineEventMapper.TimelineEntry status = TimelineEventMapper.map(
            CrmDomainEvent.updated(ORG, PROJECT, CONVERSATION, "RESOLVED", null, "OPEN", AT,
                EMPLOYEE));
        assertThat(status.type()).isEqualTo(ConversationEventType.STATUS_CHANGED);
        assertThat(status.payload()).containsEntry("from", "OPEN").containsEntry("to", "RESOLVED");

        TimelineEventMapper.TimelineEntry priority = TimelineEventMapper.map(
            CrmDomainEvent.updated(ORG, PROJECT, CONVERSATION, null, "URGENT", "OPEN", AT,
                EMPLOYEE));
        assertThat(priority.type()).isEqualTo(ConversationEventType.PRIORITY_CHANGED);
        assertThat(priority.payload()).containsEntry("to", "URGENT");
    }

    @Test
    @DisplayName("an update with neither status nor priority produces no row")
    void emptyUpdateIsDropped() {
        assertThat(TimelineEventMapper.map(
            CrmDomainEvent.updated(ORG, PROJECT, CONVERSATION, null, null, "OPEN", AT, EMPLOYEE)))
            .isNull();
    }

    @Test
    @DisplayName("resolution is a STATUS_CHANGED to RESOLVED attributed to the resolver")
    void resolution() {
        TimelineEventMapper.TimelineEntry entry = TimelineEventMapper.map(
            CrmDomainEvent.resolved(ORG, PROJECT, CONVERSATION, "EMPLOYEE", 6, AT, EMPLOYEE));

        assertThat(entry.type()).isEqualTo(ConversationEventType.STATUS_CHANGED);
        assertThat(entry.actorType()).isEqualTo(MessageAuthorType.EMPLOYEE);
        assertThat(entry.payload()).containsEntry("to", "RESOLVED").containsEntry("messageCount", 6);
    }

    @Test
    @DisplayName("mode change to HUMAN is TAKEN_OVER; back to AI is RETURNED_TO_AI")
    void modeChangeBranches() {
        TimelineEventMapper.TimelineEntry takeover = TimelineEventMapper.map(
            CrmDomainEvent.modeChanged(ORG, PROJECT, CONVERSATION, "AI", "HUMAN", "MANUAL", AT,
                EMPLOYEE));
        assertThat(takeover.type()).isEqualTo(ConversationEventType.TAKEN_OVER);
        assertThat(takeover.payload()).containsEntry("from", "AI").containsEntry("to", "HUMAN");

        TimelineEventMapper.TimelineEntry returned = TimelineEventMapper.map(
            CrmDomainEvent.modeChanged(ORG, PROJECT, CONVERSATION, "HUMAN", "AI", "MANUAL", AT,
                EMPLOYEE));
        assertThat(returned.type()).isEqualTo(ConversationEventType.RETURNED_TO_AI);
    }

    @Test
    @DisplayName("a customer-level event has no conversation to hang from and is dropped")
    void customerEventsAreDropped() {
        assertThat(TimelineEventMapper.map(
            CrmDomainEvent.customerCreated(ORG, PROJECT, CUSTOMER, "MOCK", "ext-1", AT))).isNull();
        assertThat(TimelineEventMapper.map(base(DomainEventType.CUSTOMER_UPDATED))).isNull();
        assertThat(TimelineEventMapper.map(base(DomainEventType.EMPLOYEE_AVAILABILITY_CHANGED)))
            .isNull();
    }

    @Test
    @DisplayName("a null event is dropped rather than throwing")
    void nullEventIsDropped() {
        assertThat(TimelineEventMapper.map(null)).isNull();
        assertThat(CrmDomainEvent.class).isNotNull();
    }

    @Test
    @DisplayName("an unrecognised author type degrades to SYSTEM instead of throwing")
    void unknownAuthorTypeDegrades() {
        CrmDomainEvent event = CrmDomainEvent.resolved(ORG, PROJECT, CONVERSATION, "ROBOT", 1, AT,
            null);

        assertThat(TimelineEventMapper.map(event).actorType()).isEqualTo(MessageAuthorType.SYSTEM);
    }

    @Test
    @DisplayName("an event without a conversation id in its payload is a programming error")
    void missingConversationIdIsLoud() {
        CrmDomainEvent broken = new CrmDomainEvent(DomainEventType.MESSAGE_CREATED, ORG, PROJECT,
            MESSAGE, "Message", java.util.Map.of("direction", "INBOUND"), AT, null);

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
            () -> TimelineEventMapper.map(broken)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}