package com.firstelement.notifications.service;

import com.firstelement.notifications.event.RealtimeEvent;
import com.firstelement.notifications.event.RealtimeEventType;
import com.firstelement.notifications.persistence.NotificationRepository;
import com.firstelement.notifications.realtime.RealtimePublisher;
import com.firstelement.security.TenantContext;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persist first, push second.
 *
 * <h2>The rule, from the legacy service that got it right</h2>
 *
 * <p>"An agent who was offline when a conversation was assigned still has to find it at the start
 * of their shift, and a realtime channel that drops a frame must not lose the fact — so the row is
 * the record and the push is an optimization."
 *
 * <h2>A failed push must not roll back the notification</h2>
 *
 * <p>The legacy {@code create} caught and logged every failure for exactly that reason. Here the
 * separation is structural rather than a catch block: the row is written in its own transaction, and
 * {@link RealtimePublisher#publish} is contractually incapable of throwing. A frame that does not
 * arrive is recovered by the client's resync.
 *
 * <h2>Who gets told, and why that is a domain decision</h2>
 *
 * <p>Notification triggers subscribe to domain events in the legacy runtime rather than having the
 * routing and escalation services call a notifier, "so those services should not grow a dependency
 * on how people are told." This service preserves that: {@link #notifyConversationAssigned} and
 * {@link #notifyConversationEscalated} are the two triggers Phase 6's routing/escalation work will
 * call, and neither those services needs to know about SSE, Redis or notifications.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    /** Bounded so a provider-echoed title cannot become an unbounded column. */
    private static final int MAX_TITLE = 200;
    private static final int MAX_BODY = 500;

    private final NotificationRepository notifications;
    private final RealtimePublisher publisher;

    public NotificationService(NotificationRepository notifications, RealtimePublisher publisher) {
        this.notifications = notifications;
        this.publisher = publisher;
    }

    /**
     * Creates one notification and pushes a frame for it.
     *
     * @param context   the tenant scope the row belongs to; the user's own notification, so the
     *                  acting principal and the recipient are usually the same
     * @param recipient the user the notification is for. Cross-user reads are refused by the
     *                  repository; this method only <em>writes</em> for a resolved user id.
     * @return the row's id, or {@code null} when the notification could not be written
     */
    @Transactional
    public UUID create(TenantContext context, UUID projectId, UUID recipient, String type,
                       String title, String body, String resourceType, UUID resourceId) {
        if (recipient == null) {
            log.warn("refusing to create a notification with no recipient tenant={}",
                context.organizationId());
            return null;
        }
        NotificationRepository.NotificationRow written = notifications.insert(context, projectId,
            recipient, type, bound(title, MAX_TITLE), bound(body, MAX_BODY), resourceType,
            resourceId);

        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("notificationId", written.id());
        payload.put("type", type);
        payload.put("title", bound(title, MAX_TITLE));
        payload.put("resourceType", resourceType);
        payload.put("resourceId", resourceId == null ? "" : resourceId.toString());
        payload.put("createdAt", written.createdAt());
        // The client fetches the row; the frame carries ids and the title the list itself renders.
        // No body: an in-app notification body can quote a customer's words.
        payload.put("unread", true);

        RealtimeEvent event = RealtimeEvent.of(RealtimeEventType.NOTIFICATION_CREATED, context,
            projectId, recipient, written.id(), payload);
        int delivered = publisher.publish(event);
        log.info("notification created id={} type={} recipient={} deliveredTo={} subscribers",
            written.id(), type, recipient, delivered);
        return written.id();
    }

    /**
     * Trigger: a conversation was assigned.
     *
     * <p>Skips a self-assignment — "Assigning a conversation to yourself does not need telling" —
     * and returns {@code null} when it skipped, so the caller can distinguish "no notification" from
     * "notification written".
     */
    public UUID notifyConversationAssigned(TenantContext context, UUID projectId, UUID employeeUserId,
                                           UUID assignedByUserId, UUID conversationId, String reason) {
        if (employeeUserId == null) {
            return null;
        }
        if (employeeUserId.equals(assignedByUserId)) {
            return null;
        }
        String body = "ROUTING".equals(reason)
            ? "Routed to you automatically."
            : "Assigned by a colleague.";
        return create(context, projectId, employeeUserId,
            NotificationRepository.CONVERSATION_ASSIGNED,
            "A conversation was assigned to you", body, "Conversation", conversationId);
    }

    /**
     * Trigger: a conversation was escalated and nobody took it.
     *
     * <p>"Admins are told; the assigned agent already got their own notification from the assignment
     * event." Each recipient is a separate row: a shared row would be read by exactly one of them.
     */
    public int notifyConversationEscalated(TenantContext context, UUID projectId,
                                           java.util.List<UUID> adminUserIds, UUID conversationId,
                                           String reason) {
        int written = 0;
        for (UUID admin : adminUserIds) {
            if (create(context, projectId, admin, NotificationRepository.CONVERSATION_ESCALATED,
                "A conversation was escalated", reason, "Conversation", conversationId) != null) {
                written++;
            }
        }
        return written;
    }

    private static String bound(String value, int max) {
        if (value == null) {
            return null;
        }
        String flattened = value.replaceAll("[\\r\\n]", " ");
        return flattened.length() <= max ? flattened : flattened.substring(0, max) + "…";
    }
}