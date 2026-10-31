package com.firstelement.notifications.realtime;

import com.firstelement.notifications.event.RealtimeEvent;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The per-process subscriber registry.
 *
 * <h2>Two filters, both server-side</h2>
 *
 * <p>A subscription is stored against one topic plus the subscriber's resolved scope, and a frame is
 * delivered when both match: the topic is in the allowed set, and a user-targeted frame belongs to
 * that user. The client is never sent something it must be trusted to ignore — the legacy gateway's
 * comment, and the reason filtering lives here rather than in Angular.
 *
 * <h2>The registry holds no state worth keeping</h2>
 *
 * <p>A subscriber that disconnects is removed on completion, on error and on a heartbeat timeout.
 * Nothing here is a record of anything: a lost subscriber loses a frame, and the client resyncs.
 */
public class RealtimeSubscriptionRegistry {

    private static final Logger log = LoggerFactory.getLogger(RealtimeSubscriptionRegistry.class);

    /** One connected subscriber. */
    public record Subscriber(String id,
                             UUID organizationId,
                             UUID userId,
                             java.util.Set<UUID> projectIds,
                             java.util.Set<String> topics,
                             Sink sink) {

        /** Where a frame goes for this subscriber. */
        @FunctionalInterface
        public interface Sink {
            void send(RealtimeEvent event);
        }
    }

    private final Map<String, Subscriber> subscribers = new ConcurrentHashMap<>();
    private final AtomicLong published = new AtomicLong();

    /** Registers a subscriber. @return the subscriber id used to remove it */
    public String register(Subscriber subscriber) {
        subscribers.put(subscriber.id(), subscriber);
        return subscriber.id();
    }

    /**
     * Removes a subscriber.
     *
     * <p>A null id is a no-op rather than a {@code NullPointerException} from the backing map:
     * removal happens on every disconnect path, including ones that never had an id, and an
     * exception there would turn a closed browser tab into a server error.
     */
    public void remove(String subscriberId) {
        if (subscriberId == null) {
            return;
        }
        subscribers.remove(subscriberId);
    }

    public int count() {
        return subscribers.size();
    }

    public int count(String topic) {
        return (int) subscribers.values().stream()
            .filter(subscriber -> subscriber.topics().contains(topic)).count();
    }

    public long publishedFrames() {
        return published.get();
    }

    /**
     * Delivers a frame to the subscribers entitled to it.
     *
     * @return how many received it
     */
    public int deliver(RealtimeEvent event) {
        String topic = event.topic();
        int delivered = 0;
        for (Subscriber subscriber : subscribers.values()) {
            if (!entitled(subscriber, event, topic)) {
                continue;
            }
            try {
                subscriber.sink().send(event);
                delivered++;
            } catch (RuntimeException e) {
                // One broken subscriber must not stop the others from being told.
                log.warn("realtime subscriber {} failed to receive {}; removing it", subscriber.id(),
                    event.auditLabel(), e);
                subscribers.remove(subscriber.id());
            }
        }
        if (delivered > 0) {
            published.addAndGet(delivered);
        }
        return delivered;
    }

    /**
     * Whether this subscriber may receive this frame.
     *
     * <p>Three checks, in this order: the topic was authorized at subscribe time; the frame's tenant
     * is the subscriber's tenant; and a user-targeted frame names this user. The tenant check is
     * redundant with the topic check by construction — the topic embeds the organization id — and it
     * stays because a redundant check on a tenant boundary is cheap and a missing one is a breach.
     */
    private static boolean entitled(Subscriber subscriber, RealtimeEvent event, String topic) {
        if (!subscriber.topics().contains(topic)) {
            return false;
        }
        if (!subscriber.organizationId().equals(event.organizationId())) {
            return false;
        }
        if (event.userId() != null && !event.userId().equals(subscriber.userId())) {
            return false;
        }
        if (event.projectId() != null && !subscriber.projectIds().contains(event.projectId())) {
            return false;
        }
        return true;
    }
}