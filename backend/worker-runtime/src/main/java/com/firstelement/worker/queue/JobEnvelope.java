package com.firstelement.worker.queue;

import com.firstelement.worker.outbox.OutboxPayloadCodec;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * One claimed unit of work, handed to a {@link JobHandler} by the dispatcher.
 *
 * <h2>The row is re-read under tenant scope before this exists</h2>
 *
 * <p>The claim statement is executed by tenant (see {@code OutboxRepository}), so
 * {@link #organizationId()} is the tenant the claim was made <em>for</em> and is not a value
 * taken from the payload. A row whose own {@code organizationId} disagrees with the claim is
 * dropped rather than dispatched, and the dispatcher records the refusal; that is the documented
 * invariant behind the tenant-less scheduling indexes.
 *
 * <h2>What the envelope deliberately does not carry</h2>
 *
 * <p>No customer text, no credential, no resolved customer record. The payload is the delivery
 * ENVELOPE (aggregate reference, provider, template/attachment reference, correlation ids), and the
 * handler is the only component allowed to resolve customer content — by reading the row the
 * envelope points at. This is the same rule {@code V20261004000100} Q2 records.
 *
 * @param outboxEventId   the durable row this job came from; {@code null} only for scheduled
 *                        sweeps that have no outbox row of their own
 * @param queue           the declared queue; never unknown, because the dispatcher refuses first
 * @param organizationId   the tenant the claim was made for — the authorization
 * @param projectId        the project, or {@code null} for organisation-wide work
 * @param aggregateType    e.g. {@code Message}
 * @param aggregateId      the business object this job acts on
 * @param payload          the envelope, decoded from {@code outbox_events.payload}
 * @param correlationId    propagated from the domain transaction that produced the intent
 * @param causationId      the id of the record that caused this work
 * @param attemptNumber    1-based; equals {@code outbox_events.attempts} after the claim
 * @param jobId            the transport-stable job identity used for {@code job_attempts} numbering
 * @param isReplay         whether this attempt was produced by an explicit operator replay
 * @param replayOfAttemptId the failed attempt a replay names; non-null exactly when {@code isReplay}
 */
public record JobEnvelope(UUID outboxEventId,
                          QueueName queue,
                          UUID organizationId,
                          UUID projectId,
                          String aggregateType,
                          UUID aggregateId,
                          Map<String, Object> payload,
                          String correlationId,
                          String causationId,
                          int attemptNumber,
                          String jobId,
                          boolean isReplay,
                          UUID replayOfAttemptId,
                          LocalDateTime nextAttemptAt) {

    public JobEnvelope {
        if (queue == null) {
            throw new IllegalArgumentException("queue is required");
        }
        if (organizationId == null) {
            throw new IllegalArgumentException(
                "organizationId is required; an envelope without a tenant is an unscoped job");
        }
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be >= 1 (V20261004000700 CHECK)");
        }
        if (isReplay != (replayOfAttemptId != null)) {
            throw new IllegalArgumentException(
                "isReplay=true requires replayOfAttemptId and vice versa "
                    + "(job_attempts_replay_check)");
        }
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    /** Reads a string field from the envelope payload, or {@code null}. */
    public String payloadText(String key) {
        Object value = payload.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /** Reads a UUID field from the envelope payload, or {@code null}. */
    public UUID payloadUuid(String key) {
        String text = payloadText(key);
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "outbox payload field '" + key + "' is not a uuid; refusing to guess", e);
        }
    }

    /** The payload rendered back to jsonb, for the admin read models. */
    public String payloadJson(OutboxPayloadCodec json) {
        return json.write(payload);
    }
}