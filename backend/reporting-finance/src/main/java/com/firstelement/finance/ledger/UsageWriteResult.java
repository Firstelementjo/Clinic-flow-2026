package com.firstelement.finance.ledger;

/**
 * The result of a {@link UsageEventWriter} write.
 *
 * <h2>Why the outcome is a value and not a boolean</h2>
 *
 * <p>Three things can happen and a caller must be able to tell them apart:
 *
 * <ol>
 *   <li>the event was inserted;</li>
 *   <li>the event was <b>already there</b> — the same source row, same dimension, same tenant — which
 *       is the normal outcome of a retried job and is <em>not</em> an error;</li>
 *   <li>the event is a correction that supersedes an earlier one, which was inserted.</li>
 * </ol>
 *
 * <p>A boolean would make (1) and (2) indistinguishable, and the natural reaction to an indistinguishable
 * pair is to log an error and raise an alert. That turns the idempotency guarantee — the whole reason
 * a retried job is safe — into a recurring false alarm, and the fix people reach for is to suppress
 * it, which removes the signal that an actual double-emission would have produced.
 *
 * <p>{@link #recorded()} is therefore the only question a caller usually needs, and the distinction
 * between "inserted" and "already present" is available for the caller that cares — the
 * reconciliation, for instance, which wants to know whether a duplicate was suppressed.
 */
public record UsageWriteResult(Outcome outcome, java.util.UUID eventId,
                               UsageEvent event) {

    /** What happened. */
    public enum Outcome {
        /** A new immutable row. */
        INSERTED,
        /**
         * The tenant already had this source event in this dimension, and the insert was suppressed by
         * {@code usage_events_source_dimension_key}.
         *
         * <p>The correct outcome of a retry. Not an error, and not something to alert on.
         */
        DUPLICATE_SUPPRESSED
    }

    public static UsageWriteResult inserted(UsageEvent event) {
        return new UsageWriteResult(Outcome.INSERTED, event.id(), event);
    }

    /**
     * A duplicate was suppressed, and this is the row that already existed.
     *
     * @param existing the row the database kept, so the caller can see the original rather than
     *                 assuming its own values won
     */
    public static UsageWriteResult suppressed(UsageEvent attempted, UsageEvent existing) {
        return new UsageWriteResult(Outcome.DUPLICATE_SUPPRESSED,
            existing == null ? attempted.id() : existing.id(),
            existing == null ? attempted : existing);
    }

    /** Whether a row now exists for this source event in this dimension. Always true. */
    public boolean recorded() {
        return true;
    }

    /** Whether this call inserted. False means the row was already there. */
    public boolean isNew() {
        return outcome == Outcome.INSERTED;
    }

    /** Whether the write was suppressed as a duplicate. */
    public boolean isDuplicate() {
        return outcome == Outcome.DUPLICATE_SUPPRESSED;
    }

    /**
     * The caller may proceed.
     *
     * <p>Present as a named method because "did the write work" should never be answered by reading
     * an enum. Both outcomes are successes; the enum is for callers that need the distinction.
     */
    public boolean succeeded() {
        return true;
    }
}
