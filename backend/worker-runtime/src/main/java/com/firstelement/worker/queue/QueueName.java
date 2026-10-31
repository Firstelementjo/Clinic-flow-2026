package com.firstelement.worker.queue;

/**
 * The queue vocabulary, ported from {@code packages/messaging/src/queues.ts} and
 * {@code apps/worker/src/queues.ts}.
 *
 * <h2>Why the names are a closed enum here</h2>
 *
 * <p>The legacy comment states the reason: "Declared here rather than in either app so the
 * producer (API) and the consumer (worker) cannot drift on a name or disagree about how many
 * times a customer's message is retried before it is treated as lost." That is now structural
 * rather than conventional — {@code outbox_events."eventType"} is the queue name, and
 * {@code job_attempts."queueName"} is it again, so a typo would be a second queue that nothing
 * ever drains instead of an error.
 *
 * <p>{@link #wireValue()} is the stored spelling and is byte-compatible with the legacy string,
 * so {@code job_attempts} rows written by the BullMQ runtime stay readable.
 *
 * <h2>Declared-but-inert queues</h2>
 *
 * <p>AI_RESPONSE, SCORING, ESCALATION_ROUTING, DOCUMENT_PROCESSING and ANALYTICS_ROLLUP are
 * Phase 6/8/9/10 work. They are declared now because the *vocabulary* is a contract, and a
 * dispatcher that meets an unknown queue dead-letters it as non-retryable rather than silently
 * discarding it. Nothing in this phase produces them.
 */
public enum QueueName {

    /** Inbound provider event normalised and queued for the conversation pipeline. */
    INCOMING_MESSAGE("incoming-message", QueueClass.INTERACTIVE),

    /** A persisted outbound message handed to its channel connector. */
    OUTBOUND_SEND("outbound-send", QueueClass.INTERACTIVE),

    /** An in-app notification persisted and fanned out to the realtime stream. */
    NOTIFICATION_FANOUT("notification-fanout", QueueClass.DEFAULT),

    /** Lease reaping and queue-health refresh. Self-owned by this module. */
    OUTBOX_MAINTENANCE("outbox-maintenance", QueueClass.MAINTENANCE),

    // ------------------------------------------------------------------
    // declared by the legacy runtime; produced by a later phase
    // ------------------------------------------------------------------

    AI_RESPONSE("ai-response", QueueClass.DEFAULT),
    SCORING("scoring", QueueClass.DEFAULT),
    ESCALATION_ROUTING("escalation-routing", QueueClass.DEFAULT),
    DOCUMENT_PROCESSING("document-processing", QueueClass.BULK),
    ANALYTICS_ROLLUP("analytics-rollup", QueueClass.BULK);

    private final String wireValue;
    private final QueueClass queueClass;

    QueueName(String wireValue, QueueClass queueClass) {
        this.wireValue = wireValue;
        this.queueClass = queueClass;
    }

    /** The value stored in {@code outbox_events."eventType"} and {@code job_attempts."queueName"}. */
    public String wireValue() {
        return wireValue;
    }

    /** The scheduling class: what this queue is competing for. */
    public QueueClass queueClass() {
        return queueClass;
    }

    /**
     * @return the queue, or {@code null} when the value is not one of ours.
     *         A {@code null} here is a hard error for the dispatcher: an unknown
     *         {@code eventType} must dead-letter loudly rather than be dropped.
     */
    public static QueueName fromWireValue(String value) {
        if (value == null) {
            return null;
        }
        for (QueueName queue : values()) {
            if (queue.wireValue.equals(value)) {
                return queue;
            }
        }
        return null;
    }

    /**
     * @throws UnknownQueueException when the value names no declared queue
     */
    public static QueueName require(String value) {
        QueueName queue = fromWireValue(value);
        if (queue == null) {
            throw new UnknownQueueException(value);
        }
        return queue;
    }
}