package com.firstelement.worker.queue;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * The one retry table, so a producer and a consumer cannot disagree about when a customer's
 * message is treated as lost.
 *
 * <h2>Why customer-facing queues retry harder and longer</h2>
 *
 * <p>AGENTS.md's product rule is that the helpdesk stays usable when a provider is down. For
 * {@code incoming-message} and {@code outbound-send} that means a long retry horizon (12 attempts
 * over roughly 2 hours with a 15-minute ceiling): a WhatsApp outage of an hour must not become a
 * dead letter a customer discovers. A malformed document will fail identically on attempt two,
 * so {@code document-processing} gets 3 attempts and moves on — the legacy worker already said
 * "the job itself is not retried, because a malformed PDF will fail identically five more times".
 *
 * <p>Nothing here is read from the database. The schema stores policy nowhere on purpose.
 */
public final class QueuePolicyCatalog {

    private static final Map<QueueName, QueuePolicy> POLICIES = build();

    private QueuePolicyCatalog() {
    }

    private static Map<QueueName, QueuePolicy> build() {
        EnumMap<QueueName, QueuePolicy> policies = new EnumMap<>(QueueName.class);
        for (QueueName queue : QueueName.values()) {
            policies.put(queue, policyFor(queue));
        }
        return Map.copyOf(policies);
    }

    private static QueuePolicy policyFor(QueueName queue) {
        return switch (queue) {
            // Customer conversations are never lost (plan 20.8): long horizon, capped so a
            // genuinely broken connection still ends up in a dead letter an operator can see.
            case INCOMING_MESSAGE, OUTBOUND_SEND ->
                new QueuePolicy(queue, 12, Duration.ofSeconds(5), Duration.ofMinutes(15), 0.30d,
                    Duration.ofDays(7));
            case NOTIFICATION_FANOUT ->
                new QueuePolicy(queue, 6, Duration.ofSeconds(2), Duration.ofMinutes(2), 0.20d,
                    Duration.ofDays(3));
            case AI_RESPONSE, SCORING, ESCALATION_ROUTING ->
                new QueuePolicy(queue, 5, Duration.ofSeconds(2), Duration.ofMinutes(5), 0.25d,
                    Duration.ofDays(7));
            case DOCUMENT_PROCESSING, ANALYTICS_ROLLUP ->
                new QueuePolicy(queue, 3, Duration.ofSeconds(10), Duration.ofMinutes(2), 0.10d,
                    Duration.ofDays(7));
            // Maintenance work must not dead-letter: a lease that cannot be reaped is a stranded
            // row forever, so this queue retries far past the others.
            case OUTBOX_MAINTENANCE ->
                new QueuePolicy(queue, 20, Duration.ofSeconds(1), Duration.ofMinutes(1), 0.10d,
                    Duration.ofDays(1));
        };
    }

    /** @return the policy for {@code queue}; never null. */
    public static QueuePolicy of(QueueName queue) {
        QueuePolicy policy = POLICIES.get(queue);
        if (policy == null) {
            throw new IllegalArgumentException("No retry policy is declared for " + queue);
        }
        return policy;
    }

    /** @return every declared policy, for the queue-health read model. */
    public static Map<QueueName, QueuePolicy> all() {
        return POLICIES;
    }
}