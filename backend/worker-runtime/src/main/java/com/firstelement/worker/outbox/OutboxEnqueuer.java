package com.firstelement.worker.outbox;

import com.firstelement.security.TenantContextHolder;
import com.firstelement.security.job.TenantScopedExecutor;
import com.firstelement.worker.queue.QueueName;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The domain-facing writer for the transactional outbox.
 *
 * <h2>The whole point: one transaction</h2>
 *
 * <p>{@code V20261004000100} §1 says the enqueue "MUST run inside the SAME transaction as the
 * domain change. This is the whole point of the outbox: if the domain transaction rolls back, no
 * work is published; if it commits, the work is already durable." A dispatcher that runs even one
 * poll before a commit can therefore publish work for a change that never happened, and the
 * alternative everybody reaches for first — "send first, persist after" — is the failure the
 * legacy {@code OutboundDispatcher} documentation explicitly rejects:
 * "a send that succeeded while the write fails leaves a customer holding a reply the CRM has no
 * record of".
 *
 * <p>So {@link #publish} refuses to run outside an active transaction rather than silently
 * opening its own. There is no "best effort" mode: an intent published outside the domain
 * transaction is exactly the bug this class exists to prevent.
 *
 * <h2>Tenant scope comes from the thread, not from the argument</h2>
 *
 * <p>{@link TenantContextHolder} is bound by {@code TenantContextFilter} on a request and by
 * {@link TenantScopedExecutor} in a job. The enqueue asserts that the intent's
 * {@code organizationId} equals the bound context's tenant, so a payload writer cannot publish
 * another tenant's work — the AGENTS.md rule "a client-provided tenant id is a request, never
 * authorization", applied to the queue itself.
 *
 * <h2>Duplicate suppression is not an error</h2>
 *
 * <p>{@code ON CONFLICT ("organizationId","idempotencyKey") DO NOTHING} means a retried webhook
 * produces one intent and the caller is told it did not win the race. That is a normal outcome,
 * not a failure, and callers continue.
 */
@Component
public class OutboxEnqueuer {

    private static final Logger log = LoggerFactory.getLogger(OutboxEnqueuer.class);

    private final OutboxRepository outbox;

    public OutboxEnqueuer(OutboxRepository outbox) {
        this.outbox = outbox;
    }

    /**
     * Writes one intent in the caller's transaction.
     *
     * @return the new row's id, or {@code null} when the same
     *         {@code (organizationId, idempotencyKey)} was already queued
     * @throws UnscopedOutboxPublishException when there is no bound tenant context
     * @throws OutboxPublishedOutsideTransactionException when no transaction is active
     */
    public UUID publish(OutboxIntent intent) {
        requireTransaction();
        requireMatchingTenant(intent.organizationId());
        requireDeclaredQueue(intent.eventType());

        java.util.Optional<OutboxRepository.Enqueued> enqueued = outbox.enqueue(intent);
        if (enqueued.isEmpty()) {
            log.info("outbox intent {} already queued for tenant {} key={}",
                intent.aggregateType(), intent.organizationId(), intent.idempotencyKey());
            return null;
        }
        log.debug("outbox intent queued id={} queue={} aggregate={}/{}",
            enqueued.get().id(), intent.eventType(), intent.aggregateType(), intent.aggregateId());
        return enqueued.get().id();
    }

    /**
     * Whether a transaction is active. Exposed so a caller can assert its own ordering rather than
     * discovering it from an exception at the end of a long transaction.
     */
    public boolean inTransaction() {
        return TransactionSynchronizationManager.isActualTransactionActive();
    }

    private void requireTransaction() {
        if (inTransaction()) {
            return;
        }
        throw new OutboxPublishedOutsideTransactionException(
            "Refusing to write an outbox intent outside a transaction. The outbox exists so a "
                + "domain change and the work it promises commit or roll back together; publishing "
                + "on its own can announce work for a change that never happened, and publishing "
                + "after the commit has lost the 'persist before external send' ordering.");
    }

    private void requireMatchingTenant(UUID organizationId) {
        TenantContextHolder.current().ifPresentOrElse(context -> {
            if (!context.organizationId().equals(organizationId)) {
                throw new UnscopedOutboxPublishException(
                    "Refusing to publish an outbox intent for tenant " + organizationId
                        + " from a thread bound to tenant " + context.organizationId() + ".");
            }
        }, () -> {
            throw new UnscopedOutboxPublishException(
                "Refusing to publish an outbox intent with no TenantContext bound to the thread. A "
                    + "queue row is tenant-scoped (FR-1.2); an unscoped write is a cross-tenant "
                    + "risk, not a default.");
        });
    }

    private void requireDeclaredQueue(String eventType) {
        if (QueueName.fromWireValue(eventType) == null) {
            throw new IllegalArgumentException(
                "outbox eventType '" + eventType + "' is not a declared queue. Write-time refusal: "
                    + "a queue nothing drains would be work the domain transaction promised and the "
                    + "product cannot perform.");
        }
    }

    /** No tenant context on the thread. Never caught silently. */
    public static class UnscopedOutboxPublishException extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        public UnscopedOutboxPublishException(String message) {
            super(message);
        }
    }

    /** An intent published outside the domain transaction. Never caught silently. */
    public static class OutboxPublishedOutsideTransactionException extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        public OutboxPublishedOutsideTransactionException(String message) {
            super(message);
        }
    }
}