package com.firstelement.notifications.event;

import com.firstelement.security.TenantContext;
import com.firstelement.security.realtime.RealtimeTopicPolicy;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One realtime frame.
 *
 * <h2>What a frame may contain</h2>
 *
 * <p>Ids, a type, a version, an occurrence time and — for a notification — the fields the
 * notification list itself renders (title, type, read state). Never a message body, never a
 * customer's name, never a credential, never a payload the client would have to be trusted to
 * filter. {@link #auditLabel()} is the only string this class contributes to a log.
 *
 * <h2>How a frame finds its subscribers</h2>
 *
 * <p>{@link #topic(TenantContext)} is the single place a frame's audience is computed, and it uses
 * {@link RealtimeTopicPolicy} — the same class that authorizes a subscription. If the two disagreed,
 * a frame could reach a subscriber the policy would have refused, which is exactly the class of
 * bug the policy class documents.
 */
public record RealtimeEvent(RealtimeEventType type,
                            int payloadVersion,
                            UUID organizationId,
                            UUID projectId,
                            UUID userId,
                            UUID resourceId,
                            LocalDateTime occurredAt,
                            Map<String, Object> payload) {

    public RealtimeEvent {
        if (type == null) {
            throw new IllegalArgumentException("type is required");
        }
        if (organizationId == null) {
            throw new IllegalArgumentException(
                "organizationId is required; a realtime frame without a tenant has no audience");
        }
        // Null values are dropped rather than refused: a frame field that has no value is absent,
        // and Map.copyOf rejects nulls, which would turn "this resource has no type" into an error.
        Map<String, Object> copy = new LinkedHashMap<>();
        if (payload != null) {
            payload.forEach((key, value) -> {
                if (key != null && value != null) {
                    copy.put(key, value);
                }
            });
        }
        payload = Map.copyOf(copy);
        occurredAt = occurredAt == null ? LocalDateTime.now() : occurredAt;
    }

    /** Builds a frame with this event type's declared payload version. */
    public static RealtimeEvent of(RealtimeEventType type, TenantContext context, UUID projectId,
                                   UUID userId, UUID resourceId, Map<String, Object> payload) {
        return new RealtimeEvent(type, type.payloadVersion(), context.organizationId(), projectId,
            userId, resourceId, LocalDateTime.now(), payload);
    }

    /**
     * The topic this frame is published on.
     *
     * <p>A user-targeted frame goes on the organization topic and the subscription registry filters
     * it by {@code userId}; a project frame goes on the project topic. Both are authorized by
     * {@link RealtimeTopicPolicy}, never re-derived here.
     */
    public String topic() {
        if (projectId != null) {
            return RealtimeTopicPolicy.projectTopic(organizationId, projectId);
        }
        return RealtimeTopicPolicy.organizationTopic(organizationId);
    }

    /** The wire frame. Deliberately small. */
    public Map<String, Object> toFrame() {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", type.eventName());
        frame.put("version", payloadVersion);
        frame.put("organizationId", organizationId);
        if (projectId != null) {
            frame.put("projectId", projectId);
        }
        if (userId != null) {
            frame.put("userId", userId);
        }
        if (resourceId != null) {
            frame.put("resourceId", resourceId);
        }
        frame.put("occurredAt", occurredAt);
        frame.put("payload", payload);
        return frame;
    }

    /** A non-secret label for logs and metrics. Never the payload. */
    public String auditLabel() {
        return type.eventName() + "/v" + payloadVersion + " topic=" + topic();
    }

    /** Whether this frame is only for one user. */
    public boolean userScoped() {
        return userId != null;
    }
}