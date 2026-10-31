package com.firstelement.worker.outbox;

import java.util.UUID;

/**
 * One intent to do asynchronous work, written inside the domain transaction that caused it.
 *
 * <p>This is the write side of the outbox. {@link OutboxRepository} owns the read/claim side; both
 * are shaped by {@code ops/flyway/reference/outbox-claim.sql}.
 *
 * <h2>Invariants</h2>
 *
 * <ul>
 *   <li>{@code organizationId} is required. A client-supplied id is a request; the caller must pass
 *       the scope it actually wrote in, and {@link OutboxEnqueuer} binds it to the thread.</li>
 *   <li>{@code eventType} is a declared {@link com.firstelement.worker.queue.QueueName} wire value.
 *       An unknown type would create work nothing drains, so it is refused at write time rather
 *       than dead-lettered later.</li>
 *   <li>{@code aggregateId} is required and must be the object the job will act on. That is what
 *       makes {@code outbox_events_organizationId_aggregate_idx} — "what happened to this
 *       message?" — answerable without a payload scan.</li>
 *   <li>{@code idempotencyKey} is tenant-scoped unique. It is the duplicate-suppression mechanism
 *       for the enqueue itself, which is how a retried webhook produces one intent, not two.</li>
 * </ul>
 *
 * @param payload the delivery ENVELOPE — references, provider, correlation ids — never customer
 *                prose. The single exception is documented on
 *                {@code InboundWebhookIntakeService}: no durable pre-conversation store exists in
 *                this schema, so an inbound provider payload has to survive in the queue.
 */
public record OutboxIntent(UUID id,
                           UUID organizationId,
                           UUID projectId,
                           String aggregateType,
                           UUID aggregateId,
                           String eventType,
                           String payloadJson,
                           String idempotencyKey,
                           String correlationId,
                           String causationId) {

    public OutboxIntent {
        if (organizationId == null) {
            throw new IllegalArgumentException(
                "organizationId is required; an outbox row with no tenant is never written");
        }
        if (aggregateId == null) {
            throw new IllegalArgumentException("aggregateId is required");
        }
        if (aggregateType == null || aggregateType.isBlank()) {
            throw new IllegalArgumentException("aggregateType is required");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType (the queue name) is required");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required: it is the tenant-scoped "
                + "unique key that suppresses a duplicate enqueue");
        }
        if (payloadJson == null || payloadJson.isBlank()) {
            payloadJson = "{}";
        }
        id = id == null ? UUID.randomUUID() : id;
    }

    public static OutboxIntent of(UUID organizationId, UUID projectId, String aggregateType,
                                  UUID aggregateId, String eventType, String payloadJson,
                                  String idempotencyKey, String correlationId, String causationId) {
        return new OutboxIntent(UUID.randomUUID(), organizationId, projectId, aggregateType,
            aggregateId, eventType, payloadJson, idempotencyKey, correlationId, causationId);
    }
}