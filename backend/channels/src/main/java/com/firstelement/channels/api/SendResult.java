package com.firstelement.channels.api;

import com.firstelement.worker.queue.RetryDecision;

/**
 * The result of one provider call.
 *
 * <h2>A failure is a value, not an exception</h2>
 *
 * <p>The legacy adapters returned {@code { ok, error, retryable }} rather than throwing, and the
 * comment on {@code OutboundDispatcher} explains why: "Retryable failures throw so BullMQ backs
 * off and tries again; permanent ones are already recorded on the message row." The adapter's job
 * is to report honestly whether the platform rejected the request or merely failed to answer; the
 * decision about what to do belongs to {@link RetryDecision} in the dispatcher, not to a
 * {@code boolean} in an adapter.
 *
 * @param providerMessageId the platform's id for the sent message, stored on the message row
 * @param decision always set when {@code ok} is false; null on success
 */
public record SendResult(boolean ok, String providerMessageId, RetryDecision decision) {

    public static SendResult sent(String providerMessageId) {
        return new SendResult(true, providerMessageId, null);
    }

    public static SendResult failed(RetryDecision decision) {
        if (decision == null) {
            throw new IllegalArgumentException(
                "A failed send must carry a decision; the dispatcher needs to know whether to retry");
        }
        return new SendResult(false, null, decision);
    }

    /** Whether another attempt could plausibly succeed. */
    public boolean retryable() {
        return decision != null && decision.retryable();
    }
}