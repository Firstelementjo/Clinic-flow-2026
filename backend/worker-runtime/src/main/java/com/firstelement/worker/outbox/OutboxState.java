package com.firstelement.worker.outbox;

/**
 * {@code public.OutboxState}. The value list is the contract with
 * {@code V20261004000100__outbox.sql} and is asserted by that migration's own guard, so this enum
 * and the database cannot drift.
 *
 * <p>"Failed but retryable" is deliberately <em>not</em> a value: it is exactly
 * {@code (attempts, nextAttemptAt, lastError)}. "Failed permanently" is {@link #DEAD}. A value with
 * no query that needs it and no transition that does not duplicate a column is a trap for the next
 * reader — which is why the draft's {@code FAILED} was dropped at the schema.
 */
public enum OutboxState {

    /** Eligible for claim. Retry state is carried by attempts/nextAttemptAt/lastError. */
    PENDING,

    /** Claimed by a worker; {@code leaseOwner} + {@code leaseUntil} are set. */
    LEASED,

    /** Handed to the transport successfully. Terminal; {@code deliveredAt} is set. */
    SENT,

    /** Dead lettered. Terminal until an explicit, audited replay. */
    DEAD;

    public static OutboxState fromValue(String value) {
        for (OutboxState state : values()) {
            if (state.name().equals(value)) {
                return state;
            }
        }
        throw new IllegalArgumentException("Unknown OutboxState: " + value);
    }
}