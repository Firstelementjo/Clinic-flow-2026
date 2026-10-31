package com.firstelement.security.realtime;

import com.firstelement.security.TenantContext;
import com.firstelement.security.event.SecurityEvent;
import com.firstelement.security.event.SecurityEventPublisher;
import com.firstelement.security.event.SecurityEventType;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The topic-subscription policy for realtime (SSE or WebSocket).
 *
 * <h2>Why this is an authorization problem</h2>
 *
 * <p>FR-9.6 makes realtime a refresh channel, not a source of truth — but a subscriber
 * still receives tenant data over it. The inventory records the legacy
 * {@code /realtime/stream} endpoint as gated only by {@code PROJECT_READ}, with the
 * project list coming from the session. So the subscription decision needs the same
 * treatment as a query: derive the allowed topic set from the server-resolved context,
 * and refuse anything outside it.
 *
 * <h2>Topic grammar</h2>
 *
 * <pre>
 *   fe:org:&lt;organizationId&gt;                  organization-wide stream
 *   fe:project:&lt;organizationId&gt;:&lt;projectId&gt;  one project
 * </pre>
 *
 * <p>The organization id is part of the topic so a subscriber cannot be authorized by
 * project alone: a topic naming another tenant fails on the first component before the
 * project is even considered.
 */
@Component
public class RealtimeTopicPolicy {

    private static final Logger log = LoggerFactory.getLogger(RealtimeTopicPolicy.class);

    private static final String ORG_PREFIX = "fe:org:";
    private static final String PROJECT_PREFIX = "fe:project:";

    private final SecurityEventPublisher events;

    public RealtimeTopicPolicy(SecurityEventPublisher events) {
        this.events = events == null ? event -> { } : events;
    }

    public static String organizationTopic(UUID organizationId) {
        return ORG_PREFIX + organizationId;
    }

    public static String projectTopic(UUID organizationId, UUID projectId) {
        return PROJECT_PREFIX + organizationId + ":" + projectId;
    }

    /**
     * Every topic this context may subscribe to: the organization stream plus one stream
     * per server-resolved project. Empty for a machine principal with no project scope,
     * which is the correct answer rather than a fallback to the organization stream.
     */
    public Set<String> allowedTopics(TenantContext context) {
        Set<String> topics = new LinkedHashSet<>();
        if (context == null) {
            return topics;
        }
        topics.add(organizationTopic(context.organizationId()));
        context.projectIds().forEach(projectId ->
            topics.add(projectTopic(context.organizationId(), projectId)));
        return topics;
    }

    /**
     * @return whether the context may subscribe. A denial is recorded on the security
     *         stream so a probing client is visible to operators.
     */
    public boolean canSubscribe(TenantContext context, String topic) {
        if (context == null || topic == null || topic.isBlank()) {
            return false;
        }
        boolean allowed = allowedTopics(context).contains(topic);
        if (!allowed) {
            publishDenied(context, topic);
        }
        return allowed;
    }

    /**
     * Filters a client's requested topic list down to the allowed subset, recording
     * every rejection. Returning the allowed subset (rather than failing the whole
     * connection) keeps one bad topic from turning into an information leak either way:
     * the client simply never receives that stream.
     */
    public SubscriptionResult filter(TenantContext context, Iterable<String> requestedTopics) {
        Set<String> allowed = new LinkedHashSet<>();
        Set<String> denied = new LinkedHashSet<>();
        for (String topic : requestedTopics) {
            if (allowedTopics(context).contains(topic)) {
                allowed.add(topic);
            } else {
                denied.add(topic);
                publishDenied(context, topic);
            }
        }
        return new SubscriptionResult(Set.copyOf(allowed), Set.copyOf(denied));
    }

    public record SubscriptionResult(Set<String> allowed, Set<String> denied) {

        public boolean fullyAuthorized() {
            return denied.isEmpty();
        }
    }

    private void publishDenied(TenantContext context, String topic) {
        try {
            events.publish(SecurityEvent.builder(SecurityEventType.REALTIME_SUBSCRIPTION_DENIED)
                .tenantContext(context)
                .resourceType("RealtimeTopic")
                .reason("topic-out-of-scope")
                // The topic is echoed: it is the principal's own subscription request,
                // contains no customer content, and is what makes the probe visible.
                .metadata(Map.of("topic", topic))
                .correlationId(org.slf4j.MDC.get("correlationId"))
                .build());
        } catch (RuntimeException e) {
            log.error("Failed to publish REALTIME_SUBSCRIPTION_DENIED", e);
        }
    }
}