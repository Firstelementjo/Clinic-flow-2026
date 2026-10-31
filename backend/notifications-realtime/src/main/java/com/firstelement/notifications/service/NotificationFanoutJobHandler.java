package com.firstelement.notifications.service;

import com.firstelement.notifications.persistence.NotificationRepository;
import com.firstelement.security.TenantContext;
import com.firstelement.worker.queue.JobEnvelope;
import com.firstelement.worker.queue.JobHandler;
import com.firstelement.worker.queue.PermanentFailureException;
import com.firstelement.worker.queue.QueueName;
import com.firstelement.worker.queue.RetryDecision;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Durable notification fan-out.
 *
 * <h2>What belongs in a queue and what does not</h2>
 *
 * <p>Writing the row must happen in the caller's transaction (it is a domain fact). Pushing the
 * frame may happen after that transaction commits, because the frame is a hint and a hint that
 * arrives late is still correct. This handler therefore performs only the push: it re-reads the row
 * under tenant scope and fans it out, so a delivery that was queued but not yet pushed still
 * reaches a subscriber, and a retry after a broker outage converges.
 *
 * <h2>The payload is a notification reference, never a notification body</h2>
 *
 * <p>Exactly what is safe: which user, which row, which type. The title and body live in the row and
 * are fetched by the client.
 */
@Component
public class NotificationFanoutJobHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(NotificationFanoutJobHandler.class);

    private final NotificationRepository notifications;
    private final com.firstelement.notifications.realtime.RealtimePublisher publisher;

    public NotificationFanoutJobHandler(NotificationRepository notifications,
                                        com.firstelement.notifications.realtime.RealtimePublisher
                                            publisher) {
        this.notifications = notifications;
        this.publisher = publisher;
    }

    @Override
    public QueueName queue() {
        return QueueName.NOTIFICATION_FANOUT;
    }

    @Override
    public String handle(TenantContext context, JobEnvelope job) {
        UUID requested = job.payloadUuid("notificationId");
        UUID notificationId = requested == null ? job.aggregateId() : requested;
        if (notificationId == null) {
            throw new PermanentFailureException(RetryDecision.INVALID_PAYLOAD,
                "MISSING_NOTIFICATION_ID",
                "the fanout envelope names no notificationId and has no aggregateId");
        }
        UUID recipient = job.payloadUuid("userId");
        if (recipient == null) {
            throw new PermanentFailureException(RetryDecision.INVALID_PAYLOAD, "MISSING_USER_ID",
                "the fanout envelope names no recipient");
        }

        boolean exists = notifications.list(context, false, 200).stream()
            .anyMatch(row -> row.id().equals(notificationId));
        if (!exists) {
            // The row is gone (a tenant deletion, or a retention purge). Retrying will not recreate
            // it, and the client will resync from the API, which is the authoritative answer.
            log.info("fanout skipped: notification {} is not visible to tenant {}",
                notificationId, context.organizationId());
            return "notification absent; nothing to fan out";
        }

        var event = new com.firstelement.notifications.event.RealtimeEvent(
            com.firstelement.notifications.event.RealtimeEventType.NOTIFICATION_CREATED, 1,
            context.organizationId(), job.projectId(), recipient, notificationId,
            job.nextAttemptAt(),
            java.util.Map.of("notificationId", notificationId.toString()));
        int delivered = publisher.publish(event);
        return "fanout deliveredTo=" + delivered;
    }
}