package com.firstelement.notifications.realtime;

import com.firstelement.notifications.config.RealtimeProperties;
import com.firstelement.notifications.event.RealtimeEvent;
import com.firstelement.notifications.event.RealtimeEventType;
import com.firstelement.security.TenantContext;
import com.firstelement.security.realtime.RealtimeTopicPolicy;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The SSE stream: subscribe, filter, heartbeat, resync.
 *
 * <h2>SSE, not WebSocket — ADR-0003</h2>
 *
 * <p>"Every event in this product flows server→client, browsers reconnect automatically, and it
 * rides the existing cookie auth and CORS setup instead of needing a second authentication path.
 * Nothing here needs client→server frames — the client has a REST API for that." No requirement in
 * this phase introduces bidirectional frames.
 *
 * <h2>Reconnect and resync are the contract</h2>
 *
 * <p>The very first frame on every connection — including a reconnect — is
 * {@link RealtimeEventType#RESYNC_REQUIRED}. The browser reconnects on its own after a network blip
 * and this process may have been down for a minute; the client's state is stale by an unknown
 * amount and the only correct answer is "fetch again". ADR-0003 requires this explicitly: "Realtime
 * tests must prove resync-from-API after reconnect, not trust missed frames."
 *
 * <h2>Everything is filtered server-side</h2>
 *
 * <p>A subscription's topics come from {@link RealtimeTopicPolicy.allowedTopics}, which is derived
 * from the resolved {@code TenantContext}. A client-supplied topic outside that set is refused and
 * recorded on the security stream by the policy itself. Frames are then filtered again per
 * delivery by {@link RealtimeSubscriptionRegistry}.
 *
 * <h2>The heartbeat exists so a dead connection is noticed</h2>
 *
 * <p>A proxy or a browser tab in the background will drop an idle SSE connection silently. A
 * heartbeat frame every {@code heartbeatSeconds} both keeps intermediaries from closing the stream
 * and proves the path is alive; a write failure removes the subscriber.
 */
@Component
public class RealtimeStreamService implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(RealtimeStreamService.class);

    private final RealtimeSubscriptionRegistry registry;
    private final RedisRealtimeBridge publisher;
    private final RealtimeProperties properties;
    private final RealtimeTopicPolicy topicPolicy;
    private final RedisMessageListenerContainer listenerContainer;
    private final AtomicLong connections = new AtomicLong();
    private final AtomicLong resyncsSent = new AtomicLong();

    public RealtimeStreamService(RealtimeSubscriptionRegistry registry,
                                 RedisRealtimeBridge publisher,
                                 RealtimeProperties properties,
                                 RealtimeTopicPolicy topicPolicy,
                                 RedisMessageListenerContainer listenerContainer) {
        this.registry = registry;
        this.publisher = publisher;
        this.properties = properties;
        this.topicPolicy = topicPolicy;
        this.listenerContainer = listenerContainer;
    }

    /**
     * @param requestedTopics the client's topics; anything outside the allowed set is refused and
     *                         recorded, and the client simply never receives that stream
     */
    public SseEmitter open(TenantContext context, java.util.List<String> requestedTopics) {
        RealtimeTopicPolicy.SubscriptionResult authorized =
            topicPolicy.filter(context, requestedTopics);
        java.util.Set<String> topics = authorized.allowed();
        if (topics.isEmpty()) {
            // A principal with no allowed topic still gets a stream; it will only ever receive the
            // resync and heartbeats. That is the honest answer for a machine principal with no
            // project scope rather than an authorization failure the client cannot act on.
            topics = java.util.Set.of(RealtimeTopicPolicy.organizationTopic(context.organizationId()));
        }
        if (!authorized.fullyAuthorized()) {
            log.warn("realtime subscription refused {} topic(s) for principal={}",
                authorized.denied().size(), context.auditActorLabel());
        }

        long timeoutMillis = properties.getStreamTimeoutSeconds() * 1000L;
        SseEmitter emitter = new SseEmitter(timeoutMillis);
        String subscriberId = UUID.randomUUID().toString();

        RealtimeSubscriptionRegistry.Subscriber subscriber =
            new RealtimeSubscriptionRegistry.Subscriber(subscriberId, context.organizationId(),
                context.userId(), context.projectIds(), topics, event -> send(emitter, event, subscriberId));
        registry.register(subscriber);
        connections.incrementAndGet();

        Runnable cleanup = () -> {
            registry.remove(subscriberId);
            connections.decrementAndGet();
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(() -> {
            cleanup.run();
            emitter.complete();
        });
        emitter.onError(error -> cleanup.run());

        // The resync frame is the first thing on the wire, on every connection including a
        // reconnect. See the class note.
        send(emitter, resync(context, requestedTopics), subscriberId);
        send(emitter, heartbeat(), subscriberId);

        log.info("realtime stream opened principal={} topics={}", context.auditActorLabel(), topics);
        return emitter;
    }

    private RealtimeEvent resync(TenantContext context, java.util.List<String> requestedTopics) {
        resyncsSent.incrementAndGet();
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        // No row counts and no state: the client is told to refetch, not what to refetch. A count
        // would be a per-tenant figure on a public health-adjacent surface.
        payload.put("reason", "stream-opened");
        payload.put("resyncFrom", "/api/v1/realtime/state");
        payload.put("topics", requestedTopics == null ? java.util.List.of()
            : requestedTopics.stream().filter(java.util.Objects::nonNull).distinct().toList());
        payload.put("serverTime", java.time.LocalDateTime.now().toString());
        return RealtimeEvent.of(RealtimeEventType.RESYNC_REQUIRED, context, null, context.userId(),
            null, payload);
    }

    private RealtimeEvent heartbeat() {
        return new RealtimeEvent(RealtimeEventType.HEARTBEAT, 1, UUID.randomUUID(), null, null, null,
            java.time.LocalDateTime.now(), Map.of("at", java.time.LocalDateTime.now().toString()));
    }

    private void send(SseEmitter emitter, RealtimeEvent event, String subscriberId) {
        try {
            emitter.send(SseEmitter.event()
                .id(subscriberId + ":" + event.type().eventName() + ":" + event.occurredAt())
                .name(event.type().eventName())
                .data(event.toFrame()));
        } catch (IOException | IllegalStateException e) {
            // The client is gone. This is the normal end of an SSE stream, not an error worth
            // escalating; the completion callback removes the subscriber.
            log.debug("realtime subscriber {} is gone; ending the stream ({})", subscriberId,
                e.getClass().getSimpleName());
            registry.remove(subscriberId);
        }
    }

    /**
     * Receives frames published by another instance.
     *
     * <p>Redis broadcasts to every subscriber, including the instance that published the frame. The
     * bridge recognises and suppresses its own echo, so this delivers other instances' frames to
     * this instance's subscribers and nothing else.
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        String body = message == null ? null : message.toString();
        int delivered = publisher.deliverRemote(body);
        // Counts and lengths only: the body arrived from another process and is never logged.
        log.debug("realtime bridge delivered {} frame(s) to local subscribers", delivered);
    }

    /**
     * Subscribes to the bridge channel. Called once the context is ready.
     *
     * <p>Gated on {@code firstelement.realtime.bridge-enabled}: a single-instance deployment has no
     * bridge to join, and starting a listener that can only fail would turn a working deployment
     * into a noisy one.
     */
    public void subscribeToBridge() {
        if (!properties.isBridgeEnabled()) {
            log.info("realtime bridge subscription disabled; this instance serves local subscribers "
                + "only");
            return;
        }
        try {
            listenerContainer.addMessageListener(this, publisher.topic());
            log.info("realtime bridge subscribed to {}", publisher.channelName().orElse("none"));
        } catch (RuntimeException e) {
            log.warn("realtime bridge subscription failed; this instance serves local subscribers "
                + "only. reason={}", e.getClass().getSimpleName());
        }
    }

    @PreDestroy
    void stop() {
        log.info("realtime stream service stopping with {} local subscriber(s)", registry.count());
    }

    /** The realtime health read model. No tenant data. */
    public RealtimeHealth health() {
        return new RealtimeHealth(registry.count(), connections.get(), resyncsSent.get(),
            publisher.multiInstanceCapable(),
            publisher.channelName().orElse("none"), publisher.localSubscriberCount());
    }

    /** Connection and resync counters for the management port. */
    public record RealtimeHealth(int localSubscribers, long connectionsOpened, long resyncsSent,
                                 boolean multiInstanceCapable, String channel,
                                 int subscribersOnChannel) {
    }
}
