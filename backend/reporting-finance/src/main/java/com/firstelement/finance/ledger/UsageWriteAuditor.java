package com.firstelement.finance.ledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Observes {@link UsageEventWriter} writes without becoming a dependency that can fail them.
 *
 * <h2>Why this exists at all, given "the ledger is the audit trail"</h2>
 *
 * <p>Because it does not, quite. {@code usage_events} records <em>usage</em>: what was consumed, when,
 * and what it cost. It does not record that the <em>emitter</em> attempted a write and was refused,
 * because a refused write produces no row. And a silently-dropped usage event is the exact failure
 * mode a ledger is supposed to make impossible — a message that was delivered, metered, and never
 * recorded because the job crashed between the provider's callback and the insert.
 *
 * <p>So the writer announces what it did to something that is not the ledger, and this is where that
 * announcement goes. It is deliberately structural logging, not a row: an observability dependency on
 * the write path is exactly the kind of thing that turns a metrics outage into a delivery outage.
 *
 * <h2>Why a duplicate is logged at all</h2>
 *
 * <p>Because it is the single most useful signal available for a double-counting bug. A suppressed
 * duplicate is normal for a redelivered webhook and <em>exceptional</em> for a job that reprocesses a
 * queue it should have checkpointed. At INFO with the suppression key it is visible in a log search
 * without being an alert; making it an error would turn the idempotency guarantee into recurring noise,
 * and the response to recurring noise is suppression.
 *
 * <h2>How to replace it</h2>
 *
 * <p>The interface has one method. A deployment that ships Micrometer implements it with a counter;
 * one that ships an audit stream writes a {@code JOB_INTEGRATION} row. Neither changes the writer, and
 * neither can make the writer fail.
 */
public interface UsageWriteAuditor {

    /** A new immutable row was inserted. */
    void recorded(UsageEvent event);

    /**
     * A write was suppressed because the tenant already had this source event in this dimension.
     *
     * @param attempted what the caller tried to write
     * @param existing  the row the database kept, or {@code null} when it could not be read back
     */
    void suppressed(UsageEvent attempted, UsageEvent existing);

    /**
     * Logs both outcomes and never throws.
     *
     * <p>The default, so a deployment gets useful behaviour with no configuration. A real
     * implementation replaces it.
     */
    final class Logging implements UsageWriteAuditor {

        private static final Logger log = LoggerFactory.getLogger(UsageWriteAuditor.class);

        @Override
        public void recorded(UsageEvent event) {
            log.info("usage recorded organization={} project={} dimension={} quantity={} unit={} "
                    + "source={}/{} cost={} {} occurredAt={}",
                event.organizationId(), event.projectId(), event.dimension(),
                event.quantity().toPlainString(), event.unit(), event.sourceEventType(),
                event.sourceEventId(), event.costOrNull(), event.currency(), event.occurredAt());
        }

        @Override
        public void suppressed(UsageEvent attempted, UsageEvent existing) {
            // INFO, not WARN and not ERROR: a redelivered webhook suppressing its own duplicate is
            // the mechanism working. The key is in the message because "which source row" is the only
            // thing that makes this searchable during a double-counting investigation.
            log.info("usage duplicate suppressed key={} attemptedQuantity={} storedQuantity={}",
                attempted.idempotencyKey(),
                attempted.quantity().toPlainString(),
                existing == null || existing.quantity() == null
                    ? "?" : existing.quantity().toPlainString());
        }
    }
}
