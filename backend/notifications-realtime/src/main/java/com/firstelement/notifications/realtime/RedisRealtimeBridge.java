package com.firstelement.notifications.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstelement.notifications.event.RealtimeEvent;
import com.firstelement.notifications.event.RealtimeEventType;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * The Redis pub/sub bridge for multi-instance fan-out.
 *
 * <h2>What the legacy gateway could not do</h2>
 *
 * <p>Its own comment is the specification: "One process holds one in-memory subject. That is
 * correct for a single API instance and is the known limit: with several instances behind a load
 * balancer, a client connected to instance A misses events published on instance B, and this needs a
 * Redis pub/sub bridge." This is that bridge: {@link #publish} sends the frame to the channel and
 * {@link #deliverRemote} is what the listener side runs, so a subscriber on instance A is told about
 * a frame published on instance B.
 *
 * <h2>What crosses the channel is ids, not content</h2>
 *
 * <p>The envelope is {@code type}, {@code version}, the tenant, the project, the user, the resource
 * and the occurrence time — with {@code payload} always empty. A frame that arrived through the
 * bridge therefore tells a subscriber <em>that something changed</em> and the client refetches the
 * row, which is exactly the ADR-0003 contract ("payloads remain ids/states only"). It also means the
 * broker never holds a customer's words, so a broker compromise is not a data breach. The cost is
 * stated plainly: a frame delivered through the bridge may carry fewer display fields than the same
 * frame delivered locally, and the client must treat the payload as optional. That is not a new
 * requirement — every frame is a hint and the authoritative answer is {@code /api/v1/realtime/state}.
 *
 * <h2>The broker is a transport, not the record</h2>
 *
 * <p>When Redis is unreachable, {@link #publish} logs and delivers locally, and a frame that is
 * missed is recovered by the client's resync rather than by the broker. Nothing here is authoritative
 * and nothing here is durable.
 *
 * <h2>Ordering and durability are explicitly not promised</h2>
 *
 * <p>pub/sub has neither. That is acceptable <em>because</em> realtime is a refresh channel: the
 * client's guarantee is the resync from the API, not the frame. A frame that is published twice, or
 * not at all, is recoverable; a frame that carries the only copy of a fact would not be, which is
 * why no frame ever carries customer content.
 */
@Component
public class RedisRealtimeBridge implements RealtimePublisher {

    private static final Logger log = LoggerFactory.getLogger(RedisRealtimeBridge.class);

    /** One channel for every tenant and every topic; the frame carries its own scope. */
    public static final String CHANNEL = "firstelement:realtime";

    /**
     * 8 KiB. A bridge envelope is a few hundred ASCII characters; anything larger is either a bug or
     * someone using the channel as a general-purpose message bus, and it is refused rather than
     * parsed. The bound is on characters of the received string, which is the value the listener
     * actually holds; the envelopes this class writes are pure ASCII, so the two agree.
     */
    public static final int MAX_BRIDGE_BYTES = 8 * 1024;

    /**
     * How many recently published frame ids are remembered for echo suppression.
     *
     * <p>Redis broadcasts to every subscriber, so the publishing instance receives its own frame
     * back. Delivering it twice is harmless in principle (one extra API call) but it doubles the
     * frames every tab sees for no reason. The bound is what keeps this from becoming a leak: the
     * set is an access-ordered LRU, and a frame older than this many publications is delivered
     * twice rather than remembered forever.
     */
    private static final int ECHO_MEMORY = 4096;

    private final RealtimeSubscriptionRegistry registry;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Map<String, Boolean> recentlyPublished = Collections.synchronizedMap(
        new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > ECHO_MEMORY;
            }
        });

    public RedisRealtimeBridge(RealtimeSubscriptionRegistry registry, StringRedisTemplate redis,
                               ObjectMapper json) {
        this.registry = registry;
        this.redis = redis;
        this.json = json;
    }

    @Override
    public int publish(RealtimeEvent event) {
        // Deliver locally first: the common case is a single instance, and it must not depend on a
        // round trip to a broker.
        int local = registry.deliver(event);
        Envelope envelope = toEnvelope(event);
        recentlyPublished.put(envelope.frameId(), Boolean.TRUE);
        try {
            redis.convertAndSend(CHANNEL, envelope.body());
        } catch (RuntimeException e) {
            log.warn("realtime publish to Redis failed; delivering locally only. reason={}",
                e.getClass().getSimpleName());
        }
        return local;
    }

    /**
     * Delivers a frame published by <em>another</em> instance to this instance's subscribers.
     *
     * <p>This is the listener side, and it is deliberately total: an unreadable, oversized,
     * tenant-less or unknown-type envelope is ignored rather than thrown, because the caller is a
     * Redis listener callback whose only correct response to bad input is to keep listening.
     *
     * @return how many local subscribers received it; 0 when the envelope was refused or was this
     *         instance's own echo
     */
    public int deliverRemote(String body) {
        if (body == null || body.isBlank()) {
            return 0;
        }
        if (body.length() > MAX_BRIDGE_BYTES) {
            log.warn("refusing a {} character realtime bridge frame; the maximum is {}",
                body.length(), MAX_BRIDGE_BYTES);
            return 0;
        }
        RealtimeEvent event;
        try {
            event = parse(body);
        } catch (JsonProcessingException | RuntimeException e) {
            // The body is never logged: it arrived from outside this process.
            log.warn("ignoring an unreadable realtime bridge frame ({} characters, {})",
                body.length(), e.getClass().getSimpleName());
            return 0;
        }
        if (event == null) {
            return 0;
        }
        return registry.deliver(event);
    }

    @Override
    public int localSubscriberCount() {
        return registry.count();
    }

    @Override
    public int localSubscriberCount(String topic) {
        return registry.count(topic);
    }

    @Override
    public boolean multiInstanceCapable() {
        try {
            return redis.getConnectionFactory() != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public Optional<String> channelName() {
        return Optional.of(CHANNEL);
    }

    /** The channel topic bean, so a listener container can subscribe. */
    public ChannelTopic topic() {
        return new ChannelTopic(CHANNEL);
    }

    /** The wire encoding is UTF-8 and carries ids only; stated for the reader. */
    static java.nio.charset.Charset charset() {
        return java.nio.charset.StandardCharsets.UTF_8;
    }

    // ------------------------------------------------------------------
    // the envelope
    // ------------------------------------------------------------------

    /**
     * The bridge encoding of one frame: ids, a type, a version, a time, and always an empty payload.
     *
     * <p>{@code frameId} exists for echo suppression only. It is generated here and is not part of
     * the browser contract: the SSE frame id is the subscriber-scoped value the client already uses
     * for its own reconnect bookkeeping.
     */
    public static Envelope toEnvelope(RealtimeEvent event) {
        String frameId = UUID.randomUUID().toString();
        StringBuilder body = new StringBuilder("{");
        body.append("\"frameId\":\"").append(frameId).append('"');
        body.append(",\"type\":\"").append(event.type().eventName()).append('"');
        body.append(",\"version\":").append(event.payloadVersion());
        body.append(",\"organizationId\":\"").append(event.organizationId()).append('"');
        if (event.projectId() != null) {
            body.append(",\"projectId\":\"").append(event.projectId()).append('"');
        }
        if (event.userId() != null) {
            body.append(",\"userId\":\"").append(event.userId()).append('"');
        }
        if (event.resourceId() != null) {
            body.append(",\"resourceId\":\"").append(event.resourceId()).append('"');
        }
        body.append(",\"occurredAt\":\"").append(event.occurredAt()).append('"');
        // Never the payload: the bridge carries no customer content, so a frame that crossed
        // another instance's network tells the subscriber only what to refetch.
        body.append(",\"payload\":{}");
        body.append('}');
        return new Envelope(frameId, body.toString());
    }

    /**
     * One bridge message: the id used for echo suppression, and the bytes on the wire.
     *
     * @param frameId generated per publish; never part of the browser contract
     * @param body    the envelope, ids only
     */
    public record Envelope(String frameId, String body) {
    }

    /**
     * Parses a bridge envelope into a frame.
     *
     * @return {@code null} when the envelope cannot be a frame at all: no tenant (a tenant-less
     *         frame has no audience, which {@code RealtimeEvent} itself refuses), an unknown event
     *         name (this process is older than the publisher), or an unparseable id
     */
    private RealtimeEvent parse(String body) throws JsonProcessingException {
        JsonNode node = json.readTree(body);
        if (node == null || !node.isObject()) {
            log.warn("ignoring a realtime bridge frame that is not a JSON object");
            return null;
        }
        String frameId = text(node, "frameId");
        if (frameId != null && !firstEcho(frameId)) {
            // This instance published it; its own subscribers already have it.
            return null;
        }
        RealtimeEventType type = typeOf(text(node, "type"));
        if (type == null) {
            log.warn("ignoring a realtime bridge frame of an unknown type; this instance does not "
                + "understand that version of the contract");
            return null;
        }
        UUID organizationId = uuid(node, "organizationId");
        if (organizationId == null) {
            log.warn("ignoring a tenant-less realtime bridge frame: it has no audience");
            return null;
        }
        int version = node.path("version").asInt(type.payloadVersion());
        return new RealtimeEvent(type, Math.max(1, version), organizationId,
            uuid(node, "projectId"), uuid(node, "userId"), uuid(node, "resourceId"),
            occurredAt(node), Map.of());
    }

    private static RealtimeEventType typeOf(String wireName) {
        if (wireName == null) {
            return null;
        }
        for (RealtimeEventType type : RealtimeEventType.values()) {
            if (type.eventName().equalsIgnoreCase(wireName)) {
                return type;
            }
        }
        return null;
    }

    private static LocalDateTime occurredAt(JsonNode node) {
        String raw = text(node, "occurredAt");
        if (raw == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw);
        } catch (DateTimeParseException e) {
            // A timestamp this process cannot read is not a reason to drop the frame; the record
            // stamps "now" and the client refetches anyway.
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText(null);
    }

    private static UUID uuid(JsonNode node, String field) {
        String raw = text(node, field);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * @return {@code true} the first time this instance sees {@code frameId}, {@code false} when it
     *         published the frame itself
     */
    private boolean firstEcho(String frameId) {
        synchronized (recentlyPublished) {
            if (recentlyPublished.containsKey(frameId)) {
                return false;
            }
            recentlyPublished.put(frameId, Boolean.TRUE);
            return true;
        }
    }
}