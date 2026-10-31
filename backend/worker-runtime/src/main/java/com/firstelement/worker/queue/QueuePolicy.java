package com.firstelement.worker.queue;

import java.time.Duration;

/**
 * Retry policy for one queue: how many attempts, and how long to wait between them.
 *
 * <h2>Where the numbers come from</h2>
 *
 * <p>{@code attempts = 5}, {@code backoff delay = 2s}, exponential — the legacy
 * {@code DEFAULT_JOB_OPTIONS}. {@code removeOnFail: 7 days} becomes {@link #failureRetention()},
 * because "keep failures around so a dead letter can be inspected rather than discovered by a
 * customer complaint" is an observability requirement, and the only durable place to keep it is
 * {@code job_attempts} / {@code job_dead_letters}.
 *
 * <h2>What the schema deliberately does not store</h2>
 *
 * <p>None of this is in the database. {@code V20261004000100__outbox.sql} says it plainly: the
 * intervals in the reference statements are illustrative and "lease window and backoff come from
 * configuration; the schema deliberately stores no policy". A policy that lived in a row could be
 * edited by whoever can edit rows; a policy that lives in configuration is reviewable.
 *
 * @param queue             the queue this applies to
 * @param maxAttempts       total attempts including the first; {@code 1} means no retry
 * @param baseBackoff       delay before the <em>second</em> attempt
 * @param maxBackoff        ceiling the exponential growth is clamped to
 * @param jitterRatio       fraction of the computed delay that jitter may add or remove, in
 *                          {@code [0,1]}; {@code 0} disables jitter (deterministic tests do)
 * @param failureRetention  how long an operator is expected to need the failed attempts
 */
public record QueuePolicy(QueueName queue,
                          int maxAttempts,
                          Duration baseBackoff,
                          Duration maxBackoff,
                          double jitterRatio,
                          Duration failureRetention) {

    public QueuePolicy {
        if (queue == null) {
            throw new IllegalArgumentException("queue is required");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1 for " + queue);
        }
        if (baseBackoff == null || baseBackoff.isNegative() || baseBackoff.isZero()) {
            throw new IllegalArgumentException("baseBackoff must be positive for " + queue);
        }
        if (maxBackoff == null || maxBackoff.compareTo(baseBackoff) < 0) {
            throw new IllegalArgumentException("maxBackoff must be >= baseBackoff for " + queue);
        }
        if (jitterRatio < 0d || jitterRatio > 1d) {
            throw new IllegalArgumentException("jitterRatio must be within [0,1] for " + queue);
        }
        if (failureRetention == null || failureRetention.isNegative()) {
            throw new IllegalArgumentException("failureRetention is required for " + queue);
        }
    }

    /** The scheduling class this queue competes in, delegated from the queue itself. */
    public QueueClass queueClass() {
        return queue.queueClass();
    }

    /** Whether another attempt is permitted after {@code attemptsSoFar} have been made. */
    public boolean allowsRetry(int attemptsSoFar) {
        return attemptsSoFar < maxAttempts;
    }

    /** Attempts remaining after {@code attemptsSoFar}. Zero means the next failure dead-letters. */
    public int remainingAttempts(int attemptsSoFar) {
        return Math.max(0, maxAttempts - attemptsSoFar);
    }
}