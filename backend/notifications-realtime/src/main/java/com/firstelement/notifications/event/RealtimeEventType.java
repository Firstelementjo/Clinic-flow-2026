package com.firstelement.notifications.event;

/**
 * The realtime event vocabulary.
 *
 * <h2>Every frame is a hint, never the payload</h2>
 *
 * <p>ADR-0003 is unambiguous: "Payloads remain ids/states only — no message content on the wire."
 * A browser tab left open in a shared office is not a place to push customer text that the viewer
 * may not be scoped to read, and the client resynchronizes from the REST API anyway. So a frame
 * carries ids, a type, a version and a timestamp — and the client fetches the row.
 *
 * <h2>{@code version} is a string, and it is required</h2>
 *
 * <p>A payload shape that changes without a version bump is how a rolling deployment breaks a tab
 * that has been open for two days. Each event declares the shape version it was built with, and the
 * client is expected to discard a version it does not understand rather than guess.
 */
public enum RealtimeEventType {

    /** A conversation's visible state changed: assigned, escalated, status, mode. */
    CONVERSATION_CHANGED("conversation", 1),

    /** A message was created on a conversation. The payload carries the message id only. */
    MESSAGE_CREATED("message", 1),

    /** A customer record changed. */
    CUSTOMER_CHANGED("customer", 1),

    /** An in-app notification was created for this user. */
    NOTIFICATION_CREATED("notification", 1),

    /**
     * Sent on (re)connect before any domain event: "your view may be stale, resynchronize from the
     * API". This is the mechanism ADR-0003 requires and the reason a dropped frame is harmless.
     */
    RESYNC_REQUIRED("resync", 1),

    /** A keepalive. Carries nothing; its arrival proves the stream is alive. */
    HEARTBEAT("heartbeat", 1);

    private final String eventName;
    private final int payloadVersion;

    RealtimeEventType(String eventName, int payloadVersion) {
        this.eventName = eventName;
        this.payloadVersion = payloadVersion;
    }

    /** The SSE {@code event:} name. Stable, lowercase, and part of the client contract. */
    public String eventName() {
        return eventName;
    }

    /** The payload shape version this frame was built with. */
    public int payloadVersion() {
        return payloadVersion;
    }
}