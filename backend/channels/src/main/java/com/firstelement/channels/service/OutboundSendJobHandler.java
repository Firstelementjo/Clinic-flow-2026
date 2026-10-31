package com.firstelement.channels.service;

import com.firstelement.security.TenantContext;
import com.firstelement.worker.queue.JobEnvelope;
import com.firstelement.worker.queue.JobHandler;
import com.firstelement.worker.queue.PermanentFailureException;
import com.firstelement.worker.queue.QueueName;
import com.firstelement.worker.queue.RetryDecision;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Delivers one persisted outbound message.
 *
 * <h2>The envelope must name exactly one message</h2>
 *
 * <p>Anything else is a permanent failure: an intent whose {@code messageId} is missing is not going
 * to acquire one, and a queue that retries it five times only delays the dead letter an operator
 * needs.
 *
 * <h2>Failures are values, not surprises</h2>
 *
 * <p>{@link OutboundDeliveryService} records the reason on the message row and then throws, so the
 * dispatcher classifies it and the attempt history carries the retry class. The employee who pressed
 * Send sees the reason on the message; the operator sees the class in the dead letter.
 */
@Component
public class OutboundSendJobHandler implements JobHandler {

    private final OutboundDeliveryService delivery;

    public OutboundSendJobHandler(OutboundDeliveryService delivery) {
        this.delivery = delivery;
    }

    @Override
    public QueueName queue() {
        return QueueName.OUTBOUND_SEND;
    }

    @Override
    public String handle(TenantContext context, JobEnvelope job) {
        UUID messageId = job.payloadUuid("messageId");
        if (messageId == null) {
            messageId = job.aggregateId();
        }
        if (messageId == null) {
            throw new PermanentFailureException(RetryDecision.INVALID_PAYLOAD, "MISSING_MESSAGE_ID",
                "the outbound envelope names no messageId and has no aggregateId to fall back on");
        }
        if (!context.organizationId().equals(job.organizationId())) {
            throw new PermanentFailureException(RetryDecision.SCOPE_REFUSED, "TENANT_MISMATCH",
                "Refusing to deliver for a tenant other than the claimed one");
        }

        OutboundDeliveryService.DeliveryOutcome outcome = delivery.deliver(context, messageId);
        return switch (outcome.status()) {
            case SENT -> "sent providerMessageId="
                + (outcome.providerMessageId() == null ? "none" : outcome.providerMessageId())
                + " attempt=" + outcome.deliveryAttempts();
            // The idempotency proof: a retried job must not send the same reply twice. Recorded as
            // SUCCEEDED because the work this job promised is already done.
            case ALREADY_DELIVERED -> "already delivered attempt=" + outcome.deliveryAttempts();
            case NOT_FOUND -> "no message row for this job in this tenant";
            case NOT_OUTBOUND -> "the message is inbound; nothing to send";
            case FAILED -> "failed: " + outcome.reason();
        };
    }
}