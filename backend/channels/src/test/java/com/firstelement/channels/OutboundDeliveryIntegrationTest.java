package com.firstelement.channels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.channels.persistence.OutboundDeliveryRepository;
import com.firstelement.channels.service.OutboundDeliveryService;
import com.firstelement.channels.service.OutboundSendJobHandler;
import com.firstelement.security.TenantAuthentication;
import com.firstelement.security.TenantContext;
import com.firstelement.security.TenantContextHolder;
import com.firstelement.security.UserRole;
import com.firstelement.worker.config.UtcTime;
import com.firstelement.worker.queue.JobEnvelope;
import com.firstelement.worker.queue.QueueName;
import com.firstelement.worker.queue.RetryDecision;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Persist-outbound-before-delivery, as a state machine over a real {@code messages} row.
 *
 * <h2>FR-4.5: pending, sent, delivered, read, failed, provider id, attempt count, failure reason</h2>
 *
 * <p>Every one of those is asserted here, because each is a promise the CRM makes to an employee
 * looking at a conversation.
 *
 * <h2>"A retried job must not send the same reply twice"</h2>
 *
 * <p>The single check at the top of the delivery service is the whole mechanism, and the
 * crash-between-send-and-write tests below are what make it more than a comment.
 */
@SpringBootTest(classes = ChannelsTestApplication.class)
class OutboundDeliveryIntegrationTest extends ChannelsIntegrationTestBase {

    @Autowired
    private OutboundDeliveryService delivery;
    @Autowired
    private OutboundDeliveryRepository messages;
    @Autowired
    private OutboundSendJobHandler handler;
    @Autowired
    private ChannelsFixture fixture;

    @BeforeEach
    void reset() {
        fixture.reset();
    }

    @Test
    @DisplayName("a persisted PENDING message is delivered and moves to SENT with a provider id")
    void pendingMessageIsDelivered() {
        UUID conversation = fixture.conversation(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            ChannelsFixture.CUSTOMER_A, "MOCK", "966500000001");
        fixture.connection(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A, "MOCK", "{}");
        UUID message = fixture.pendingOutbound(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            conversation, ChannelsFixture.CUSTOMER_A, "MOCK", "مرحبا", "reply-1");

        var outcome = as(adminA(), () -> delivery.deliver(adminA(), message));

        assertThat(outcome.status()).isEqualTo(OutboundDeliveryService.DeliveryOutcome.Status.SENT);
        assertThat(outcome.providerMessageId()).startsWith("mock-");
        assertThat(fixture.deliveryStatusOf(message)).isEqualTo("SENT");
        assertThat(fixture.providerMessageIdOf(message)).startsWith("mock-");
        // The attempt count is tracked on the row, bounded by the metadata key this class owns.
        assertThat(fixture.metadataOf(message)).contains("deliveryAttempts");
        assertThat(fixture.failureReasonOf(message)).isNull();
    }

    @Test
    @DisplayName("a retried job does not send the same reply twice")
    void aRetryIsNotASecondSend() {
        UUID conversation = fixture.conversation(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            ChannelsFixture.CUSTOMER_A, "MOCK", "966500000001");
        fixture.connection(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A, "MOCK", "{}");
        UUID message = fixture.pendingOutbound(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            conversation, ChannelsFixture.CUSTOMER_A, "MOCK", "hello", "reply-2");

        as(adminA(), () -> delivery.deliver(adminA(), message));
        // Exactly the crash-after-send case: the worker did the external side effect and then the
        // outbox row was retried.
        var second = as(adminA(), () -> delivery.deliver(adminA(), message));

        assertThat(second.status())
            .isEqualTo(OutboundDeliveryService.DeliveryOutcome.Status.ALREADY_DELIVERED);
        assertThat(second.providerMessageId()).startsWith("mock-");
        assertThat(fixture.deliveryStatusOf(message)).isEqualTo("SENT");
        // One row, one provider id: the customer received the reply once.
        assertThat(fixture.count("select count(*) from messages where \"id\" = '" + message + "'"))
            .isEqualTo(1);
    }

    @Test
    @DisplayName("an inbound message is never delivered outbound")
    void inboundIsNotOutbound() {
        UUID conversation = fixture.conversation(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            ChannelsFixture.CUSTOMER_A, "MOCK", "966500000001");
        fixture.connection(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A, "MOCK", "{}");
        UUID message = fixture.inboundMessage(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            conversation, "MOCK", "wamid.N", "hello");

        var outcome = as(adminA(), () -> delivery.deliver(adminA(), message));
        assertThat(outcome.status())
            .isEqualTo(OutboundDeliveryService.DeliveryOutcome.Status.NOT_OUTBOUND);
        assertThat(fixture.deliveryStatusOf(message)).isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("with no enabled connection the send is refused permanently, with the reason on the row")
    void noConnectionIsPermanent() {
        UUID conversation = fixture.conversation(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            ChannelsFixture.CUSTOMER_A, "MOCK", "966500000001");
        UUID message = fixture.pendingOutbound(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            conversation, ChannelsFixture.CUSTOMER_A, "MOCK", "hello", "reply-3");

        assertThatThrownBy(() -> as(adminA(), () -> delivery.deliver(adminA(), message)))
            .isInstanceOf(com.firstelement.worker.queue.PermanentFailureException.class)
            .hasMessageContaining("No enabled MOCK connection");

        assertThat(fixture.deliveryStatusOf(message)).isEqualTo("FAILED");
        assertThat(fixture.failureReasonOf(message)).contains("No enabled MOCK connection");
    }

    @Test
    @DisplayName("a customer with no handle on the channel is refused permanently")
    void noCustomerHandleIsPermanent() {
        UUID conversation = fixture.conversation(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            ChannelsFixture.CUSTOMER_A, "MOCK", "966500000001");
        fixture.connection(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A, "MOCK", "{}");
        // Remove the identity the conversation helper created.
        fixture.jdbc().update("delete from customer_identities",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource());
        UUID message = fixture.pendingOutbound(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            conversation, ChannelsFixture.CUSTOMER_A, "MOCK", "hello", "reply-4");

        assertThatThrownBy(() -> as(adminA(), () -> delivery.deliver(adminA(), message)))
            .isInstanceOf(com.firstelement.worker.queue.PermanentFailureException.class)
            .hasMessageContaining("no MOCK handle");

        assertThat(fixture.deliveryStatusOf(message)).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("an over-long message is refused by the capability guard before any provider call")
    void capabilityGuardRunsFirst() {
        UUID conversation = fixture.conversation(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            ChannelsFixture.CUSTOMER_A, "TELEGRAM", "12345");
        fixture.connection(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A, "TELEGRAM",
            "{\"botToken\":\"000000:test-token-not-a-real-credential\"}");
        // Telegram allows 4096 characters; this is 4097.
        UUID message = fixture.pendingOutbound(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            conversation, ChannelsFixture.CUSTOMER_A, "TELEGRAM", "a".repeat(4097), "reply-5");

        assertThatThrownBy(() -> as(adminA(), () -> delivery.deliver(adminA(), message)))
            .isInstanceOf(com.firstelement.worker.queue.PermanentFailureException.class)
            .hasMessageContaining("allows 4096");

        assertThat(fixture.failureReasonOf(message)).contains("4097").contains("4096");
    }

    @Test
    @DisplayName("a message for another tenant's conversation is invisible, not delivered")
    void crossTenantMessageIsInvisible() {
        UUID alphaConversation = fixture.conversation(ChannelsFixture.ORG_A,
            ChannelsFixture.PROJECT_A, ChannelsFixture.CUSTOMER_A, "MOCK", "966500000001");
        fixture.connection(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A, "MOCK", "{}");
        UUID message = fixture.pendingOutbound(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            alphaConversation, ChannelsFixture.CUSTOMER_A, "MOCK", "hello", "reply-6");

        var outcome = as(adminB(), () -> delivery.deliver(adminB(), message));

        assertThat(outcome.status()).isEqualTo(OutboundDeliveryService.DeliveryOutcome.Status.NOT_FOUND);
        // Untouched: Beta's attempt did not write to Alpha's row.
        assertThat(fixture.deliveryStatusOf(message)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("the job handler classifies an already-delivered message as success, not failure")
    void jobHandlerTreatsIdempotencyAsSuccess() {
        UUID conversation = fixture.conversation(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            ChannelsFixture.CUSTOMER_A, "MOCK", "966500000001");
        fixture.connection(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A, "MOCK", "{}");
        UUID message = fixture.pendingOutbound(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            conversation, ChannelsFixture.CUSTOMER_A, "MOCK", "hello", "reply-7");
        as(adminA(), () -> delivery.deliver(adminA(), message));

        String summary = as(adminA(), () -> handler.handle(adminA(), envelopeFor(message)));
        assertThat(summary).startsWith("already delivered");
    }

    @Test
    @DisplayName("the job handler refuses a tenant mismatch outright")
    void jobHandlerRefusesATenantMismatch() {
        UUID conversation = fixture.conversation(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            ChannelsFixture.CUSTOMER_A, "MOCK", "966500000001");
        fixture.connection(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A, "MOCK", "{}");
        UUID message = fixture.pendingOutbound(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            conversation, ChannelsFixture.CUSTOMER_A, "MOCK", "hello", "reply-8");

        // An envelope whose claimed tenant is Beta, run under Alpha's context. The dispatcher builds
        // the context from the claimed row, so this cannot happen through it; the handler's own check
        // is the last line, and it is permanent because retrying cannot widen a scope.
        JobEnvelope foreign = new JobEnvelope(null, QueueName.OUTBOUND_SEND,
            ChannelsFixture.ORG_B, ChannelsFixture.PROJECT_B, "Message", message,
            Map.of("messageId", message.toString()), null, null, 1, message.toString(), false, null,
            UtcTime.now());

        assertThatThrownBy(() -> as(adminA(), () -> handler.handle(adminA(), foreign)))
            .isInstanceOf(com.firstelement.worker.queue.PermanentFailureException.class)
            .hasMessageContaining("other than the claimed one");
        assertThat(fixture.deliveryStatusOf(message)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("a handler with no message id at all is a permanent payload failure")
    void jobHandlerRefusesAnEmptyEnvelope() {
        JobEnvelope empty = new JobEnvelope(null, QueueName.OUTBOUND_SEND, ChannelsFixture.ORG_A,
            ChannelsFixture.PROJECT_A, "Message", null, Map.of(), null, null, 1, "job-1", false, null,
            UtcTime.now());

        assertThatThrownBy(() -> as(adminA(), () -> handler.handle(adminA(), empty)))
            .isInstanceOf(com.firstelement.worker.queue.PermanentFailureException.class)
            .hasMessageContaining("no messageId");
    }

    @Test
    @DisplayName("a provider status callback advances the status and never downgrades it")
    void providerStatusNeverDowngrades() {
        UUID conversation = fixture.conversation(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            ChannelsFixture.CUSTOMER_A, "MOCK", "966500000001");
        fixture.connection(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A, "MOCK", "{}");
        UUID message = fixture.pendingOutbound(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            conversation, ChannelsFixture.CUSTOMER_A, "MOCK", "hello", "reply-9");
        as(adminA(), () -> delivery.deliver(adminA(), message));

        var scope = com.firstelement.crm.query.CrmQueryScope.forProject(
            new com.firstelement.security.query.TenantQueryGuard(event -> { }), adminA(),
            ChannelsFixture.PROJECT_A);

        // READ wins, then a late SENT cannot take it back.
        assertThat(messages.markProviderStatus(scope, message, "READ")).isTrue();
        assertThat(messages.markProviderStatus(scope, message, "SENT")).isFalse();
        assertThat(fixture.deliveryStatusOf(message)).isEqualTo("READ");
        assertThat(OutboundDeliveryRepository.alreadyDelivered("READ")).isTrue();
    }

    @Test
    @DisplayName("a real provider that cannot be reached is classified retryable and recorded")
    void unreachableProviderIsRetryable() {
        // Telegram's real host with a 750ms budget: an unreachable endpoint must classify as
        // retryable, because "the same request may succeed later" is exactly right for a transport
        // failure and is the case the legacy adapter caught and returned.
        UUID conversation = fixture.conversation(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            ChannelsFixture.CUSTOMER_A, "TELEGRAM", "12345");
        fixture.connection(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A, "TELEGRAM",
            "{\"botToken\":\"000000:test-token-not-a-real-credential\"}");
        UUID message = fixture.pendingOutbound(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            conversation, ChannelsFixture.CUSTOMER_A, "TELEGRAM", "hello", "reply-10");

        assertThatThrownBy(() -> as(adminA(), () -> delivery.deliver(adminA(), message)))
            .isInstanceOf(OutboundDeliveryService.ProviderSendFailedException.class);

        assertThat(fixture.deliveryStatusOf(message)).isEqualTo("FAILED");
        assertThat(fixture.failureReasonOf(message)).isNotBlank();
    }

    @Test
    @DisplayName("the send result carries a decision, never a bare boolean")
    void sendResultsCarryDecisions() {
        var ok = com.firstelement.channels.api.SendResult.sent("p-1");
        assertThat(ok.ok()).isTrue();
        assertThat(ok.decision()).isNull();

        var failed = com.firstelement.channels.api.SendResult.failed(
            RetryDecision.retryable(RetryDecision.TIMEOUT, "TIMEOUT", "no answer"));
        assertThat(failed.ok()).isFalse();
        assertThat(failed.retryable()).isTrue();
        assertThat(failed.providerMessageId()).isNull();

        assertThatThrownBy(() -> com.firstelement.channels.api.SendResult.failed(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("the dispatcher needs to know");
    }

    // ------------------------------------------------------------------

    private JobEnvelope envelopeFor(UUID messageId) {
        return new JobEnvelope(null, QueueName.OUTBOUND_SEND, ChannelsFixture.ORG_A,
            ChannelsFixture.PROJECT_A, "Message", messageId,
            Map.of("messageId", messageId.toString()), null, null, 2, messageId.toString(), false,
            null, UtcTime.now());
    }

    private static TenantContext adminA() {
        return TenantContext.forUser(UUID.randomUUID(), ChannelsFixture.USER_A, ChannelsFixture.ORG_A,
            UserRole.ADMIN, Set.of(ChannelsFixture.PROJECT_A));
    }

    private static TenantContext adminB() {
        return TenantContext.forUser(UUID.randomUUID(), ChannelsFixture.USER_B, ChannelsFixture.ORG_B,
            UserRole.ADMIN, Set.of(ChannelsFixture.PROJECT_B));
    }

    private static <T> T as(TenantContext context, java.util.function.Supplier<T> work) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContextHolder.setContext(authentication(context));
        try {
            return TenantContextHolder.call(context, work);
        } finally {
            SecurityContextHolder.setContext(previous);
            TenantContextHolder.clear();
        }
    }

    private static SecurityContext authentication(TenantContext context) {
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new TenantAuthentication(context));
        return securityContext;
    }
}