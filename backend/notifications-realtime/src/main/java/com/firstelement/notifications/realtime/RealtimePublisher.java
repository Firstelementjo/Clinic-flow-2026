package com.firstelement.notifications.realtime;

import com.firstelement.notifications.event.RealtimeEvent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The realtime fan-out port.
 *
 * <h2>Why this is an interface</h2>
 *
 * <p>ADR-0003: "Spring replaces the in-memory subject with a broadcaster backed by Redis pub/sub
 * for multi-instance fan-out." That is two implementations of one behaviour, and the difference
 * between them is deployment, not contract. Keeping the port narrow also means a test can prove the
 * resync semantics without a broker.
 *
 * <h2>Redis is a transport, not the record</h2>
 *
 * <p>AGENTS.md: "Redis is not system of record." {@link #publish} is best-effort by contract — the
 * implementation logs and moves on when the broker is down, because a notification row that exists
 * and a frame that did not arrive is a recoverable state, while a publish that throws inside a
 * domain transaction is a lost assignment. The client's guarantee is the resync, not the frame.
 */
public interface RealtimePublisher {

    /**
     * Fans a frame out to the subscribers of its topic.
     *
     * <p>Never throws. A broker outage degrades realtime, not the product.
     *
     * @return how many local subscribers received it, for the metrics
     */
    int publish(RealtimeEvent event);

    /** How many subscribers this process is currently serving across all topics. */
    int localSubscriberCount();

    /** Subscribers on one topic. Used by the realtime health read model. */
    int localSubscriberCount(String topic);

    /**
     * Whether this publisher can reach other instances.
     *
     * <p>{@code false} in a single-instance deployment and when the broker is unavailable, and the
     * health indicator reports it: an operator seeing {@code multiInstance=false} on a
     * load-balanced deployment is looking at the one configuration where a client connected to
     * instance A misses an event published on instance B.
     */
    boolean multiInstanceCapable();

    /** The channel name this process subscribes on. */
    default Optional<String> channelName() {
        return Optional.empty();
    }

    /** A count of frames by type, for the realtime health surface. */
    record RealtimeEventTypeSummary(String eventName, long published) {
    }
}