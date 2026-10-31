package com.firstelement.channels.service;

import com.firstelement.channels.api.MessagingConnector;
import com.firstelement.channels.api.OutgoingMessage;
import com.firstelement.channels.api.SendResult;
import com.firstelement.channels.connector.ConnectorRegistry;
import com.firstelement.channels.connector.OutboundGuard;
import com.firstelement.channels.persistence.ChannelConnectionRepository;
import com.firstelement.channels.persistence.CustomerChannelIdentityRepository;
import com.firstelement.channels.persistence.OutboundDeliveryRepository;
import com.firstelement.crm.domain.ChannelType;
import com.firstelement.crm.query.CrmQueryScope;
import com.firstelement.security.TenantContext;
import com.firstelement.security.query.TenantQueryGuard;
import com.firstelement.worker.queue.PermanentFailureException;
import com.firstelement.worker.queue.RetryDecision;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Delivers one already-persisted outbound message to its channel.
 *
 * <h2>Persist first, send second — and the proof is the check on line one</h2>
 *
 * <p>This service never creates a message. It reads one that already exists with
 * {@code deliveryStatus = 'PENDING'} and moves it to SENT or FAILED, exactly as the legacy
 * {@code OutboundDispatcher} did: "A send that succeeded while the write failed leaves a customer
 * holding a reply the CRM has no record of, and the agent repeats themselves."
 *
 * <h2>A retry is not a second send</h2>
 *
 * <p>The first thing this class does is refuse to send a message whose status is already
 * {@code SENT}/{@code DELIVERED}/{@code READ}. That single check is what makes "worker crash after
 * external send does not duplicate on retry" true in the crash-between-send-and-write window: the
 * provider message id is written to {@code messages.externalMessageId} with the status, so the
 * re-run reads SENT and skips. The residual window — the process died after the provider accepted
 * but before the status write — is the one no local mechanism can close, and it is why the message
 * id is generated deterministically from the conversation turn rather than at send time: the
 * provider id lands on the same row either way, and the operator sees one message with an attempt
 * count, not two.
 *
 * <h2>Every refusal is a permanent failure with a recorded reason</h2>
 *
 * <p>No enabled connection, no customer handle, an unsupported capability, a message over the
 * platform's limit, a send outside the customer-service window — each of these will fail
 * identically on the next attempt, so they are {@link PermanentFailureException}s and the reason
 * lands on the message row for the employee who pressed Send.
 */
@Service
public class OutboundDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(OutboundDeliveryService.class);

    private final OutboundDeliveryRepository messages;
    private final CustomerChannelIdentityRepository identities;
    private final ChannelConnectionRepository connections;
    private final ConnectorRegistry registry;
    private final TenantQueryGuard guard;

    public OutboundDeliveryService(OutboundDeliveryRepository messages,
                                  CustomerChannelIdentityRepository identities,
                                  ChannelConnectionRepository connections,
                                  ConnectorRegistry registry,
                                  TenantQueryGuard guard) {
        this.messages = messages;
        this.identities = identities;
        this.connections = connections;
        this.registry = registry;
        this.guard = guard;
    }

    /** What one delivery attempt did. */
    public record DeliveryOutcome(Status status, String providerMessageId, String reason,
                                  int deliveryAttempts) {

        public enum Status {
            SENT,
            /** The message was already delivered; the retry was suppressed. */
            ALREADY_DELIVERED,
            /** No message row for this job in this tenant. */
            NOT_FOUND,
            /** Not outbound; nothing to send. */
            NOT_OUTBOUND,
            FAILED
        }
    }

    /**
     * Delivers {@code messageId} for {@code context}.
     *
     * @throws PermanentFailureException when retrying cannot help
     */
    public DeliveryOutcome deliver(TenantContext context, UUID messageId) {
        // The scope is built from the resolved context, so the project comes from the resolved
        // projectIds and not from a job payload.
        CrmQueryScope scope = CrmQueryScope.forProject(guard, context, null);
        Optional<OutboundDeliveryRepository.DeliverableMessage> found =
            messages.load(scope, messageId);
        if (found.isEmpty()) {
            log.info("outbound delivery skipped: message {} is not visible to tenant {}",
                messageId, context.organizationId());
            return new DeliveryOutcome(DeliveryOutcome.Status.NOT_FOUND, null,
                "no message row for this job", 0);
        }

        OutboundDeliveryRepository.DeliverableMessage message = found.get();
        if (!message.outbound()) {
            return new DeliveryOutcome(DeliveryOutcome.Status.NOT_OUTBOUND, null,
                "the message is inbound", message.deliveryAttempts());
        }
        if (OutboundDeliveryRepository.alreadyDelivered(message.deliveryStatus())) {
            // The idempotency proof. A retried job must not send the same reply twice.
            log.info("outbound delivery skipped: message {} is already {}",
                messageId, message.deliveryStatus());
            return new DeliveryOutcome(DeliveryOutcome.Status.ALREADY_DELIVERED,
                message.providerMessageId(), null, message.deliveryAttempts());
        }

        ChannelType channel = ChannelType.fromValue(message.channel());
        CrmQueryScope projectScope = CrmQueryScope.forProject(guard, context, message.projectId());

        Optional<ChannelConnectionRepository.Connection> connection =
            connections.findEnabled(context.organizationId(), message.projectId(), channel);
        if (connection.isEmpty()) {
            return fail(projectScope, message,
                "No enabled " + channel + " connection for this project");
        }

        Optional<CustomerChannelIdentityRepository.ChannelIdentity> identity =
            identities.find(projectScope, message.customerId(), channel.name());
        if (identity.isEmpty()) {
            return fail(projectScope, message,
                "Customer has no " + channel + " handle");
        }

        MessagingConnector connector;
        try {
            connector = registry.connectorFor(connection.get());
        } catch (ConnectorRegistry.CredentialUnavailableException e) {
            return fail(projectScope, message, e.getMessage());
        }

        // Ask the channel before acting rather than assuming parity.
        OutboundGuard.Verdict verdict = OutboundGuard.check(connector.capabilities(), message.body(),
            insideSessionWindow(projectScope, message,
                connector.capabilities().sessionWindowHours()));
        if (!verdict.allowed()) {
            return fail(projectScope, message, verdict.reason());
        }

        SendResult result = connector.sendText(new OutgoingMessage(message.id(),
            identity.get().externalId(), identity.get().externalId(), message.body()));
        if (!result.ok()) {
            RetryDecision decision = result.decision();
            return fail(projectScope, message,
                decision.retryClass() + "/" + decision.errorCode() + ": " + decision.reason(),
                decision);
        }

        boolean recorded = messages.markSent(projectScope, message.id(), result.providerMessageId());
        if (!recorded) {
            // The provider accepted the message and the status write did not land. Say so plainly
            // rather than claiming delivery: the next attempt will find the row still PENDING and
            // the idempotency guarantee takes it from there.
            throw new IllegalStateException(
                "The provider accepted message " + message.id() + " but the SENT status could not "
                    + "be recorded. The message row is still PENDING and will be re-checked.");
        }
        connections.clearError(context.organizationId(), connection.get().id());
        log.info("outbound delivered message={} channel={} providerMessageId={} attempt={}",
            message.id(), channel, result.providerMessageId(), message.deliveryAttempts() + 1);
        return new DeliveryOutcome(DeliveryOutcome.Status.SENT, result.providerMessageId(), null,
            message.deliveryAttempts() + 1);
    }

    /**
     * Whether the customer has messaged within the platform's session window.
     *
     * <p>WhatsApp's 24-hour customer-service window is the case that matters: outside it only an
     * approved template may be sent. The anchor is the conversation's most recent <em>inbound</em>
     * message — the last moment we know the customer was present — and an absent anchor means
     * "unknown", which is treated as outside the window so the guard refuses rather than the
     * platform. Refusing is the cheap direction: the employee sees "this conversation is outside
     * the window" instead of an opaque platform rejection.
     */
    private boolean insideSessionWindow(CrmQueryScope scope,
                                        OutboundDeliveryRepository.DeliverableMessage message,
                                        Integer windowHours) {
        if (windowHours == null) {
            // No window on this platform; the guard's session check does not apply.
            return true;
        }
        Optional<LocalDateTime> lastInbound =
            messages.lastInboundAt(scope, message.conversationId());
        if (lastInbound.isEmpty()) {
            return false;
        }
        return lastInbound.get().isAfter(LocalDateTime.now().minusHours(windowHours));
    }

    private DeliveryOutcome fail(CrmQueryScope scope,
                                 OutboundDeliveryRepository.DeliverableMessage message,
                                 String reason) {
        messages.markFailed(scope, message.id(), reason);
        throw new PermanentFailureException(RetryDecision.INVALID_REQUEST, "OUTBOUND_REJECTED",
            reason);
    }

    private DeliveryOutcome fail(CrmQueryScope scope,
                                 OutboundDeliveryRepository.DeliverableMessage message,
                                 String reason,
                                 RetryDecision decision) {
        messages.markFailed(scope, message.id(), reason);
        throw new ProviderSendFailedException(message.id(), decision);
    }

    /** The provider refused or could not be reached. Carries the decision verbatim. */
    public static class ProviderSendFailedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final UUID messageId;
        private final RetryDecision decision;

        public ProviderSendFailedException(UUID messageId, RetryDecision decision) {
            super("Provider send failed for message " + messageId + ": " + decision.reason());
            this.messageId = messageId;
            this.decision = decision;
        }

        public UUID messageId() {
            return messageId;
        }

        public RetryDecision decision() {
            return decision;
        }
    }
}