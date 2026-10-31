package com.firstelement.finance;

import com.firstelement.finance.ledger.LedgerPrincipal;
import com.firstelement.finance.ledger.UsageEvent;
import com.firstelement.finance.ledger.UsageEventFactory;
import com.firstelement.finance.ledger.UsageEventWriter;
import com.firstelement.finance.ledger.UsageWriteResult;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional call shapes a module that causes usage actually has.
 *
 * <h2>Why this is a component and not a method on the test</h2>
 *
 * <p>Because {@code UsageEventWriter.record} is {@code Propagation.MANDATORY}, and a
 * {@code @Transactional} method on a test class is <em>not</em> proxied: the test calls
 * {@code this.record(...)} directly, so the annotation never takes effect and the writer correctly
 * refuses with "no existing transaction". That is the writer behaving as designed — the failure was in
 * the test's shape, not in the code under test.
 *
 * <p>Putting the transactional methods in a bean makes the proxy apply, and it also makes the tests
 * read as what they are: a module that writes its domain change and its usage event in one transaction.
 *
 * <h2>The three shapes</h2>
 *
 * <ul>
 *   <li>{@link #record} — the ordinary path: usage alone in its own transaction.</li>
 *   <li>{@link CommittedCaller#deliver} — usage alongside a committed domain change.</li>
 *   <li>{@link RollingBackCaller#deliverAndFail} — usage written and then the transaction rolled back,
 *       which is the case that proves usage cannot outlive the thing it measures.</li>
 * </ul>
 */
@Component
public class UsageTransactionCaller {

    private final UsageEventWriter writer;

    public UsageTransactionCaller(UsageEventWriter writer) {
        this.writer = writer;
    }

    /** Writes one event in its own transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UsageWriteResult record(UsageEvent event) {
        return writer.record(event);
    }

    /** Writes one usage event in its own transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UsageWriteResult recordDelivered(UUID organizationId, UUID projectId, UUID sourceId,
                                            BigDecimal quantity, BigDecimal cost) {
        return writer.record(UsageEventFactory.message(organizationId, projectId, sourceId, quantity,
            FinanceFixture.inMarch(10, 9), FinanceFixture.MARCH_1, FinanceFixture.MARCH_31,
            LedgerPrincipal.job("carrier-meter"), "USD", cost, null));
    }

    /** Writes usage, then throws, so the transaction rolls back. */
    @Component
    public static class RollingBackCaller {

        private final UsageEventWriter writer;

        public RollingBackCaller(UsageEventWriter writer) {
            this.writer = writer;
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void deliverAndFail(UUID organizationId, UUID projectId, UUID sourceId) {
            writer.record(UsageEventFactory.message(organizationId, projectId, sourceId, BigDecimal.ONE,
                FinanceFixture.inMarch(10, 9), FinanceFixture.MARCH_1, FinanceFixture.MARCH_31,
                LedgerPrincipal.job("carrier-meter"), "USD", null, null));
            throw new IllegalStateException("domain change failed after the usage write");
        }
    }

    /** Writes usage and commits, the normal path. */
    @Component
    public static class CommittedCaller {

        private final UsageEventWriter writer;

        public CommittedCaller(UsageEventWriter writer) {
            this.writer = writer;
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void deliver(UUID organizationId, UUID projectId, UUID sourceId) {
            writer.record(UsageEventFactory.message(organizationId, projectId, sourceId,
                BigDecimal.ONE, FinanceFixture.inMarch(10, 9), FinanceFixture.MARCH_1,
                FinanceFixture.MARCH_31, LedgerPrincipal.job("carrier-meter"), "USD", null, null));
        }
    }
}
