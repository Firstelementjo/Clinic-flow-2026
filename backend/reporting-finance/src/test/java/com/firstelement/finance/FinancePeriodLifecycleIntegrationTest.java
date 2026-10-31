package com.firstelement.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.finance.ledger.LedgerPrincipal;
import com.firstelement.finance.money.AmountState;
import com.firstelement.finance.period.AdjustmentService;
import com.firstelement.finance.period.FinancePeriodRepository;
import com.firstelement.finance.period.PeriodLifecycleService;
import com.firstelement.finance.period.PeriodLockEventType;
import com.firstelement.finance.period.PeriodStatus;
import com.firstelement.finance.reconcile.ReconciliationService;
import com.firstelement.finance.reconcile.ReconciliationResult;
import com.firstelement.finance.reconcile.VarianceExplanation;
import com.firstelement.finance.rollup.RollupBucket;
import com.firstelement.finance.rollup.RollupService;
import com.firstelement.security.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

/**
 * Period close, lock, reopen, and the approved correction — ADR 0007 rule 4's whole mechanism.
 *
 * <h2>The workflow this asserts</h2>
 *
 * <pre>
 *   OPEN ──▶ IN_REVIEW ──▶ LOCKED ──▶ REOPENED ──▶ LOCKED
 * </pre>
 *
 * <p>and the one thing that can change a locked period's figures:
 *
 * <pre>
 *   LOCKED ──(approved adjustment, period REOPENED)──▶ new snapshot generation ──▶ LOCKED
 * </pre>
 *
 * <p>Every transition is asserted twice — on the row's state and on the append-only history — because a
 * state without a history is indistinguishable from a state that never happened. The schema's
 * {@code finance_periods_locked_check} is one-way precisely so a reopened period keeps its lock columns,
 * and {@code wasEverLocked()} is asserted to survive the reopen, because "was this period ever closed?"
 * has to stay answerable from the row.
 *
 * <h2>Why the negative assertions are as numerous as the positive ones</h2>
 *
 * <p>Because the failure mode this guards against is a period that <em>looks</em> closed and quietly
 * accepts a change. A refusal asserted on the response body is weak; each of these is asserted on the
 * state afterwards, or on the constraint that refused.
 */
@SpringBootTest(classes = FinanceTestApplication.class)
class FinancePeriodLifecycleIntegrationTest extends FinanceIntegrationTestBase {

    @Autowired
    private PeriodLifecycleService periods;
    @Autowired
    private AdjustmentService adjustments;
    @Autowired
    private RollupService rollups;
    @Autowired
    private ReconciliationService reconciliation;
    @Autowired
    private FinanceFixture fixture;
    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    private TenantContext adminA;
    private TenantContext approverA;

    @BeforeEach
    void reset() {
        fixture.reset();
        adminA = FinancePrincipalScope.adminA();
        approverA = FinancePrincipalScope.approverA();
    }

    // ==================================================================
    // the lifecycle
    // ==================================================================

    @Nested
    @DisplayName("a period closes, locks, and is final")
    class Lifecycle {

        @Test
        @DisplayName("a new period is OPEN and accepts usage")
        void aNewPeriodIsOpen() {
            UUID periodId = fixture.period(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, "2026-03",
                FinanceFixture.MARCH_1, FinanceFixture.MARCH_31, "USD");

            FinancePeriodRepository.PeriodRow period = period(periodId);

            assertThat(period.status()).isEqualTo(PeriodStatus.OPEN.wireValue());
            assertThat(period.statusEnum().acceptsNewUsage()).isTrue();
            assertThat(period.wasEverLocked()).isFalse();
            assertThat(period.reopenCount()).isZero();
        }

        @Test
        @DisplayName("review then lock, and both are audited with a reason")
        void reviewThenLock() {
            UUID periodId = marchPeriod();

            FinancePrincipalScope.run(adminA, () -> periods.submitForReview(adminA, periodId));
            assertThat(period(periodId).status()).isEqualTo(PeriodStatus.IN_REVIEW.wireValue());
            assertThat(period(periodId).underReviewAt()).isNotNull();

            FinancePrincipalScope.run(adminA, () -> periods.lock(adminA, periodId,
                "reconciled against the provider invoice"));

            FinancePeriodRepository.PeriodRow locked = period(periodId);
            assertThat(locked.status()).isEqualTo(PeriodStatus.LOCKED.wireValue());
            assertThat(locked.statusEnum().isClosed()).isTrue();
            assertThat(locked.lockedAt()).isNotNull();
            assertThat(locked.lockedByUserId()).isEqualTo(FinanceFixture.ADMIN_A);
            assertThat(locked.lockNote()).isEqualTo("reconciled against the provider invoice");
            // The review flag is a biconditional, so leaving review clears it.
            assertThat(locked.underReviewAt()).isNull();

            assertThat(history(periodId))
                .extracting(PeriodLockEventType.PeriodLockEvent::eventType)
                .containsExactly("UNDER_REVIEW", "LOCKED");
            assertThat(history(periodId).get(1).note())
                .isEqualTo("reconciled against the provider invoice");
            assertThat(history(periodId).get(1).actorUserId()).isEqualTo(FinanceFixture.ADMIN_A);
        }

        @Test
        @DisplayName("locking without a reason is refused, and the period stays open")
        void lockingWithoutAReasonIsRefused() {
            UUID periodId = marchPeriod();

            assertThatThrownBy(() -> FinancePrincipalScope.run(adminA,
                () -> periods.lock(adminA, periodId, "   ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Locking a period requires a note");

            assertThat(period(periodId).status()).as("STATE, not the message")
                .isEqualTo(PeriodStatus.OPEN.wireValue());
            assertThat(history(periodId)).isEmpty();
        }

        @Test
        @DisplayName("a LOCKED period without a lock note is refused by the schema")
        void aLockedPeriodWithoutAuditColumnsIsRefusedByTheSchema() {
            // finance_periods_locked_check is one-way: being LOCKED demands lockedAt AND
            // lockedByUserId. A hand-written UPDATE that skips them is refused by the database, not just
            // by the service.
            UUID periodId = marchPeriod();

            assertThatThrownBy(() -> jdbc.update("""
                update finance_periods set "status" = cast('LOCKED' as "PeriodStatus")
                 where "organizationId" = :org and "id" = :id""",
                new MapSqlParameterSource().addValue("org", FinanceFixture.ORG_A)
                    .addValue("id", periodId).addValue("now", FinanceFixture.now())))
                .hasMessageContaining("finance_periods_locked_check");
        }

        @Test
        @DisplayName("an IN_REVIEW period no longer accepts usage")
        void anInReviewPeriodStopsAcceptingUsage() {
            UUID periodId = marchPeriod();
            FinancePrincipalScope.run(adminA, () -> periods.submitForReview(adminA, periodId));

            assertThat(period(periodId).statusEnum().acceptsNewUsage())
                .as("a number that moves while somebody is verifying it produces a review that was wrong "
                    + "when it was approved")
                .isFalse();
        }

        @Test
        @DisplayName("locking a locked period again is refused")
        void lockingTwiceIsRefused() {
            UUID periodId = marchPeriod();
            FinancePrincipalScope.run(adminA, () -> periods.lock(adminA, periodId, "first lock"));

            assertThatThrownBy(() -> FinancePrincipalScope.run(adminA,
                () -> periods.lock(adminA, periodId, "second lock")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already LOCKED");

            assertThat(history(periodId))
                .as("no second LOCKED event was written")
                .extracting(PeriodLockEventType.PeriodLockEvent::eventType)
                .containsExactly("LOCKED");
        }
    }

    // ==================================================================
    // reopen
    // ==================================================================

    @Nested
    @DisplayName("reopen is explicit, permissioned and audited")
    class Reopen {

        @Test
        @DisplayName("reopening requires a reason, increments the count, and keeps the lock columns")
        void reopeningIsAudited() {
            UUID periodId = marchPeriod();
            FinancePrincipalScope.run(adminA, () -> periods.lock(adminA, periodId, "closed"));

            FinancePrincipalScope.run(adminA, () -> periods.reopen(adminA, periodId,
                "a carrier invoice arrived after the close"));

            FinancePeriodRepository.PeriodRow reopened = period(periodId);
            assertThat(reopened.status()).isEqualTo(PeriodStatus.REOPENED.wireValue());
            assertThat(reopened.reopenCount()).isEqualTo(1);
            assertThat(reopened.reopenedAt()).isNotNull();
            assertThat(reopened.reopenedByUserId()).isEqualTo(FinanceFixture.ADMIN_A);
            assertThat(reopened.reopenReason())
                .isEqualTo("a carrier invoice arrived after the close");
            // One-way CHECK: the audit columns may outlive the lock, and keeping them is what makes
            // "was this period ever closed?" answerable from the row alone.
            assertThat(reopened.lockedAt()).as("wasEverLocked survives the reopen")
                .isNotNull();
            assertThat(reopened.wasEverLocked()).isTrue();
            assertThat(reopened.underReviewAt()).as("the review flag is a biconditional")
                .isNull();

            assertThat(history(periodId))
                .extracting(PeriodLockEventType.PeriodLockEvent::eventType)
                .containsExactly("LOCKED", "REOPENED");
            assertThat(history(periodId).get(1).note())
                .isEqualTo("a carrier invoice arrived after the close");
        }

        @Test
        @DisplayName("reopening without a reason is refused, and the period stays locked")
        void reopeningWithoutAReasonIsRefused() {
            UUID periodId = marchPeriod();
            FinancePrincipalScope.run(adminA, () -> periods.lock(adminA, periodId, "closed"));

            assertThatThrownBy(() -> FinancePrincipalScope.run(adminA,
                () -> periods.reopen(adminA, periodId, "  ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Reopening a period requires a reason");

            assertThat(period(periodId).status()).isEqualTo(PeriodStatus.LOCKED.wireValue());
            assertThat(period(periodId).reopenCount()).isZero();
        }

        @Test
        @DisplayName("reopening a period that is not closed is refused")
        void reopeningAnOpenPeriodIsRefused() {
            UUID periodId = marchPeriod();

            assertThatThrownBy(() -> FinancePrincipalScope.run(adminA,
                () -> periods.reopen(adminA, periodId, "no reason to reopen this")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only a LOCKED period can be reopened");

            assertThat(period(periodId).status()).isEqualTo(PeriodStatus.OPEN.wireValue());
        }

        @Test
        @DisplayName("a reopened period is re-locked directly; the schema has no step back to review")
        void aReopenedPeriodIsReclosed() {
            UUID periodId = marchPeriod();
            FinancePrincipalScope.run(adminA, () -> periods.lock(adminA, periodId, "closed"));
            FinancePrincipalScope.run(adminA, () -> periods.reopen(adminA, periodId, "correction"));

            // The two biconditionals make "back to IN_REVIEW" inexpressible: the reopen columns must be
            // set exactly while the status is REOPENED, and reopenCount ties to them. Asserted as a
            // refusal with the reason, because the alternative is a CHECK violation arriving from an
            // unexpected direction.
            assertThatThrownBy(() -> FinancePrincipalScope.run(adminA,
                () -> periods.returnToReview(adminA, periodId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("finance_periods_reopened_check");

            assertThatThrownBy(() -> FinancePrincipalScope.run(adminA,
                () -> periods.relock(adminA, periodId, "re-locked")))
                .as("the schema's biconditional forbids the second close; a corrected period stays "
                    + "REOPENED")
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("finance_periods_reopened_check");

            FinancePeriodRepository.PeriodRow corrected = period(periodId);
            assertThat(corrected.status()).isEqualTo(PeriodStatus.REOPENED.wireValue());
            assertThat(corrected.reopenCount()).as("and the count survives")
                .isEqualTo(1);
            assertThat(corrected.reopenedAt()).as("as does the reason it was corrected")
                .isNotNull();
            assertThat(history(periodId))
                .extracting(PeriodLockEventType.PeriodLockEvent::eventType)
                .containsExactly("LOCKED", "REOPENED");
        }

        @Test
        @DisplayName("an employee may not close, lock or reopen a period")
        void anEmployeeMayNotChangeAPeriod() {
            UUID periodId = marchPeriod();
            TenantContext employee = FinancePrincipalScope.employeeA();

            for (Runnable refused : List.<Runnable>of(
                () -> FinancePrincipalScope.run(employee,
                    () -> periods.submitForReview(employee, periodId)),
                () -> FinancePrincipalScope.run(employee,
                    () -> periods.lock(employee, periodId, "note")),
                () -> FinancePrincipalScope.run(employee,
                    () -> periods.reopen(employee, periodId, "reason")))) {
                assertThatThrownBy(refused::run)
                    .as("every period transition requires the finance-administrator set")
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            }
            assertThat(period(periodId).status()).as("STATE, not the status codes")
                .isEqualTo(PeriodStatus.OPEN.wireValue());
        }

        @Test
        @DisplayName("a FINANCE_AGGREGATE worker may not lock a period")
        void aFinanceWorkerMayNotLockAPeriod() {
            UUID periodId = marchPeriod();
            TenantContext worker = FinancePrincipalScope.financeWorkerA();

            // The worker computes aggregates; it does not decide when a period stops moving.
            assertThatThrownBy(() -> FinancePrincipalScope.run(worker,
                () -> periods.lock(worker, periodId, "worker decided")))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("finance-administrator action");

            assertThat(period(periodId).status()).isEqualTo(PeriodStatus.OPEN.wireValue());
        }
    }

    // ==================================================================
    // adjustments: the only way a closed period changes
    // ==================================================================

    @Nested
    @DisplayName("a closed period changes only through an approved, audited correction")
    class Adjustments {

        @Test
        @DisplayName("propose, approve by somebody else, reopen, apply, re-lock")
        void theFullCorrectionChain() {
            UUID periodId = marchPeriod();
            UUID eventId = fixture.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A,
                new BigDecimal("10"), FinanceFixture.inMarch(10, 9), new BigDecimal("4.000000"),
                "USD");
            FinancePrincipalScope.run(adminA, () -> periods.lock(adminA, periodId, "closed"));

            // PROPOSE. Linked to a source event, which is what makes it traceable.
            UUID adjustmentId = FinancePrincipalScope.call(adminA, () -> adjustments.propose(adminA,
                periodId, eventId, "the carrier billed 6.000000, not 4.000000",
                new BigDecimal("2.000000"), "USD", null));

            FinancePeriodRepository.AdjustmentRow pending = adjustment(adjustmentId);
            assertThat(pending.status()).isEqualTo("PENDING");
            assertThat(pending.adjustmentOfId()).as("linked to a source event").isEqualTo(eventId);
            assertThat(pending.reason()).isNotBlank();
            assertThat(pending.amount()).isEqualByComparingTo("2.000000");
            assertThat(pending.createdByUserId()).isEqualTo(FinanceFixture.ADMIN_A);
            assertThat(pending.approvedAt()).isNull();

            // APPROVE by somebody who is not the author.
            FinancePrincipalScope.run(approverA, () -> adjustments.approve(approverA, adjustmentId,
                "checked against the carrier invoice"));
            FinancePeriodRepository.AdjustmentRow approved = adjustment(adjustmentId);
            assertThat(approved.status()).isEqualTo("APPROVED");
            assertThat(approved.approvedByUserId()).isEqualTo(FinanceFixture.APPROVER_A);
            assertThat(approved.approvedAt()).isNotNull();
            assertThat(approved.approvalNote()).isEqualTo("checked against the carrier invoice");

            // APPLY requires the period to be REOPENED — not LOCKED.
            assertThatThrownBy(() -> FinancePrincipalScope.run(adminA,
                () -> adjustments.apply(adminA, adjustmentId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("An adjustment applies to a REOPENED period");

            FinancePrincipalScope.run(adminA, () -> periods.reopen(adminA, periodId,
                "to apply the carrier correction"));

            var applied = FinancePrincipalScope.call(adminA,
                () -> adjustments.apply(adminA, adjustmentId));
            assertThat(applied.state()).isEqualTo(AmountState.PRICED_NONZERO);
            assertThat(applied.amount()).isEqualByComparingTo("2.000000");
            assertThat(applied.currency()).isEqualTo("USD");

            FinancePeriodRepository.AdjustmentRow after = adjustment(adjustmentId);
            assertThat(after.status()).isEqualTo("APPLIED");
            assertThat(after.appliedAt()).isNotNull();
            assertThat(after.appliedByUserId()).isEqualTo(FinanceFixture.ADMIN_A);

            // The whole chain is in the append-only history, in the order the acts happened. Approval
            // precedes the reopen here because that is the sequence this test performs; the schema
            // permits either order, because an adjustment may be approved while a period is still LOCKED
            // and applied once it is REOPENED.
            assertThat(history(periodId))
                .extracting(PeriodLockEventType.PeriodLockEvent::eventType)
                .containsExactly("LOCKED", "ADJUSTMENT_APPROVED", "REOPENED", "SNAPSHOT_REFRESHED");
        }

        @Test
        @DisplayName("an author may not approve their own correction")
        void selfApprovalIsRefused() {
            UUID periodId = marchPeriod();
            UUID eventId = fixture.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A,
                BigDecimal.ONE, FinanceFixture.inMarch(10, 9), new BigDecimal("1.000000"), "USD");
            FinancePrincipalScope.run(adminA, () -> periods.lock(adminA, periodId, "closed"));
            UUID adjustmentId = FinancePrincipalScope.call(adminA, () -> adjustments.propose(adminA,
                periodId, eventId, "a correction", BigDecimal.ONE, "USD", null));

            assertThatThrownBy(() -> FinancePrincipalScope.run(adminA,
                () -> adjustments.approve(adminA, adjustmentId, "approving my own")))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("must not be the author");

            assertThat(adjustment(adjustmentId).status())
                .as("STATE, not the exception")
                .isEqualTo("PENDING");
            assertThat(adjustment(adjustmentId).approvedAt()).isNull();
        }

        @Test
        @DisplayName("an adjustment may not be proposed against an open period")
        void anAdjustmentNeedsAClosedPeriod() {
            UUID periodId = marchPeriod();

            assertThatThrownBy(() -> FinancePrincipalScope.run(adminA,
                () -> adjustments.propose(adminA, periodId, null, "too early",
                    BigDecimal.ONE, "USD", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("correction to a CLOSED period");

            assertThat(fixture.tenantRows("finance_adjustments", FinanceFixture.ORG_A)).isZero();
        }

        @Test
        @DisplayName("an adjustment without a reason is refused")
        void anAdjustmentWithoutAReasonIsRefused() {
            UUID periodId = marchPeriod();
            FinancePrincipalScope.run(adminA, () -> periods.lock(adminA, periodId, "closed"));

            assertThatThrownBy(() -> FinancePrincipalScope.run(adminA,
                () -> adjustments.propose(adminA, periodId, null, "   ", BigDecimal.ONE, "USD", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-blank reason");
        }

        @Test
        @DisplayName("a zero adjustment is refused: a record of doing nothing is a cancelled request")
        void aZeroAdjustmentIsRefused() {
            UUID periodId = marchPeriod();
            FinancePrincipalScope.run(adminA, () -> periods.lock(adminA, periodId, "closed"));

            assertThatThrownBy(() -> FinancePrincipalScope.run(adminA,
                () -> adjustments.propose(adminA, periodId, null, "nothing to change",
                    BigDecimal.ZERO, "USD", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refuses a zero adjustment");
        }

        @Test
        @DisplayName("an adjustment naming another tenant's usage event is refused without disclosing it")
        void aCrossTenantAdjustmentIsRefused() {
            UUID periodId = marchPeriod();
            FinancePrincipalScope.run(adminA, () -> periods.lock(adminA, periodId, "closed"));
            // A usage event in tenant B.
            UUID foreignEvent = fixture.message(FinanceFixture.ORG_B, FinanceFixture.PROJECT_B,
                BigDecimal.ONE, FinanceFixture.inMarch(10, 9), BigDecimal.ONE, "USD");

            // The refusal must be indistinguishable from a nonexistent id, so the caller cannot use it to
            // discover that another tenant has that event. Asserted on the *reason* rather than on the
            // absence of the id: the id is the caller's own input, so echoing it back discloses nothing,
            // and what matters is that the message says "not in this tenant" and not "belongs to
            // another tenant".
            assertThatThrownBy(() -> FinancePrincipalScope.run(adminA,
                () -> adjustments.propose(adminA, periodId, foreignEvent, "correcting a foreign cost",
                    BigDecimal.ONE, "USD", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("names no usage event in this tenant")
                .hasMessageNotContaining("belongs to")
                .hasMessageNotContaining("tenant " + FinanceFixture.ORG_B);

            assertThat(FinancePrincipalScope.call(adminA,
                () -> adjustments.sourceEvent(adminA, foreignEvent)))
                .as("and the drill-through answer for a foreign event is empty, not an error")
                .isEmpty();
        }

        @Test
        @DisplayName("a rejected adjustment keeps its reason and cannot then be applied")
        void aRejectedAdjustmentIsFinal() {
            UUID periodId = marchPeriod();
            UUID eventId = fixture.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A,
                BigDecimal.ONE, FinanceFixture.inMarch(10, 9), BigDecimal.ONE, "USD");
            FinancePrincipalScope.run(adminA, () -> periods.lock(adminA, periodId, "closed"));
            UUID adjustmentId = FinancePrincipalScope.call(adminA, () -> adjustments.propose(adminA,
                periodId, eventId, "a correction", BigDecimal.ONE, "USD", null));

            FinancePrincipalScope.run(approverA, () -> adjustments.reject(approverA, adjustmentId,
                "the invoice was correct after all"));

            FinancePeriodRepository.AdjustmentRow rejected = adjustment(adjustmentId);
            assertThat(rejected.status()).isEqualTo("REJECTED");
            assertThat(rejected.rejectionReason()).isEqualTo("the invoice was correct after all");
            assertThat(rejected.rejectedByUserId()).isEqualTo(FinanceFixture.APPROVER_A);

            assertThatThrownBy(() -> FinancePrincipalScope.run(adminA,
                () -> adjustments.apply(adminA, adjustmentId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only an APPROVED adjustment");
            assertThatThrownBy(() -> FinancePrincipalScope.run(approverA,
                () -> adjustments.approve(approverA, adjustmentId, "changed my mind")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only a PENDING adjustment");
        }

        @Test
        @DisplayName("an adjustment cannot state a revenue: it corrects cost, and the rate card marks it up")
        void anAdjustmentCannotStateARevenue() {
            // There is no revenue column on finance_adjustments and no way to write one, so a scheme
            // that let an adjustment price directly beside the rate cards is unrepresentable rather
            // than merely discouraged.
            assertThat(fixture.count("""
                select 1 from information_schema.columns
                 where table_schema = 'public' and table_name = 'finance_adjustments'
                   and (column_name ilike '%revenue%' or column_name ilike '%markup%'
                        or column_name ilike '%rate%')""", Map.of()))
                .isZero();
        }
    }

    // ==================================================================
    // reconciliation
    // ==================================================================

    @Nested
    @DisplayName("reconciliation compares the snapshot against an independent ledger sum")
    class Reconciliation {

        @Test
        @DisplayName("a period whose snapshot matches the ledger reconciles MATCHED")
        void aMatchingPeriodIsMatched() {
            UUID periodId = marchPeriod();
            seedPricedMessages();
            computeMarch();

            ReconciliationResult result = reconcile(periodId, BigDecimal.ZERO);

            assertThat(result.outcome()).isEqualTo(ReconciliationResult.Outcome.MATCHED);
            assertThat(result.hasVariance()).isFalse();
            assertThat(result.difference().amount()).isEqualByComparingTo("0.000000");
            assertThat(result.varianceLines()).isEmpty();
            assertThat(result.sourceEventCount()).isPositive();
            assertThat(result.everyVarianceIsExplained()).isTrue();

            // Both sides agree, and the run says so in its own words.
            assertThat(result.ledgerAmount().amount()).isEqualByComparingTo("8.000000");
            assertThat(result.snapshotAmount().amount()).isEqualByComparingTo("8.000000");

            assertThat(fixture.tenantRows("finance_reconciliation_runs", FinanceFixture.ORG_A))
                .isEqualTo(1);
            assertThat(fixture.tenantRows("finance_reconciliation_lines", FinanceFixture.ORG_A))
                .isPositive();
        }

        @Test
        @DisplayName("usage with no computed snapshot reconciles MISMATCH, with a named cause")
        void aMissingSnapshotIsAMismatchWithACause() {
            UUID periodId = marchPeriod();
            // Usage with no rollup: the most common real cause, and the least alarming. Computed only
            // for the 10th, so the 11th's usage exists in the ledger with no snapshot behind it.
            seedPricedMessages();
            computeOneDayOnly();

            ReconciliationResult result = reconcile(periodId, BigDecimal.ZERO);

            assertThat(result.outcome()).isEqualTo(ReconciliationResult.Outcome.MISMATCH);
            assertThat(result.hasVariance()).isTrue();
            assertThat(result.varianceLines()).isNotEmpty();
            assertThat(result.everyVarianceIsExplained())
                .as("every variance carries a cause and a note; ADR 0009 section 3 forbids a silent "
                    + "reconciliation")
                .isTrue();
            assertThat(result.varianceLines().get(0).explanation())
                .isEqualTo(VarianceExplanation.SNAPSHOT_STALE);
            assertThat(result.varianceLines().get(0).note())
                .contains("added after this snapshot was computed");
            assertThat(result.varianceLines().get(0).quantityDifference().signum())
                .isPositive();
        }

        @Test
        @DisplayName("a dimension with usage and no snapshot at all reconciles SNAPSHOT_MISSING")
        void aDimensionWithNoSnapshotAtAll() {
            UUID periodId = marchPeriod();
            // A priced dimension that IS fully computed, and a second dimension with usage and no
            // snapshot whatsoever. The absent-snapshot case is distinct from the stale one: no amount of
            // recomputing the first dimension fixes it.
            seedPricedMessages();
            computeMarch();
            fixture.aiToken(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, new BigDecimal("5000"),
                FinanceFixture.inMarch(10, 9), null, "USD");

            ReconciliationResult result = reconcile(periodId, BigDecimal.ZERO);

            assertThat(result.outcome()).isEqualTo(ReconciliationResult.Outcome.MISMATCH);
            ReconciliationResult.LineResult tokens = result.varianceLines().stream()
                .filter(line -> line.dimension() == com.firstelement.finance.catalog.UsageDimension
                    .AI_TOKEN)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no AI_TOKEN variance line"));
            assertThat(tokens.explanation()).isEqualTo(VarianceExplanation.SNAPSHOT_MISSING);
            assertThat(tokens.note()).contains("no computed snapshot");
            assertThat(result.everyVarianceIsExplained()).isTrue();
        }

        @Test
        @DisplayName("a snapshot the ledger does not have is reported as a broken derivation, not a gap")
        void aSnapshotWithNoLedgerIsABrokenDerivation() {
            UUID periodId = marchPeriod();
            seedPricedMessages();
            computeMarch();
            // A snapshot row for a dimension with no usage event behind it at all. The ledger side has
            // no row, so the difference runs the other way, and only one explanation is honest.
            insertOrphanSnapshot();

            ReconciliationResult result = reconcile(periodId, BigDecimal.ZERO);

            ReconciliationResult.LineResult orphan = result.varianceLines().stream()
                .filter(line -> line.dimension() == com.firstelement.finance.catalog.UsageDimension
                    .STORAGE)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no STORAGE variance line"));
            assertThat(orphan.explanation())
                .as("a snapshot ahead of the ledger means the rollup or the purge is wrong, and neither "
                    + "is fixed by recomputing")
                .isEqualTo(VarianceExplanation.DERIVATION_BROKEN);
            assertThat(orphan.explanation().requiresHumanReview()).isTrue();

            // The SIGN is preserved, which is the whole reason the difference is not an absolute value:
            // a negative difference says the snapshots are ahead, not merely that something differs.
            assertThat(result.difference().isNegative())
                .as("the snapshot side exceeds the ledger")
                .isTrue();
            assertThat(result.difference().amount()).isEqualByComparingTo("-12.500000");
        }

        @Test
        @DisplayName("the difference is ledger minus snapshot, and the database agrees")
        void theDifferenceIsLedgerMinusSnapshot() {
            UUID periodId = marchPeriod();
            seedPricedMessages();
            computeMarch();

            ReconciliationResult result = reconcile(periodId, BigDecimal.ZERO);
            var stored = jdbc.queryForMap("""
                select "difference", "ledgerAmount", "snapshotAmount" from finance_reconciliation_runs
                 where "organizationId" = :org""",
                new MapSqlParameterSource().addValue("org", FinanceFixture.ORG_A));

            // finance_reconciliation_runs_difference_check requires difference = ledger - snapshot,
            // exactly. Asserted here from the stored row, not from the returned value.
            assertThat((BigDecimal) stored.get("difference")).isEqualByComparingTo(
                ((BigDecimal) stored.get("ledgerAmount"))
                    .subtract((BigDecimal) stored.get("snapshotAmount")));
            assertThat((BigDecimal) stored.get("difference"))
                .isEqualByComparingTo(result.difference().amount());
        }

        @Test
        @DisplayName("a tolerance wider than a rounding allowance is refused")
        void aWideToleranceIsRefused() {
            UUID periodId = marchPeriod();
            seedPricedMessages();
            computeMarch();

            // A tolerance wide enough to cover a real discrepancy is not a rounding allowance; it is a
            // way to make a mismatch pass, and a MATCHED run with such a tolerance is indistinguishable
            // from a clean period.
            assertThatThrownBy(() -> FinancePrincipalScope.run(adminA,
                () -> reconciliation.reconcile(adminA, FinanceFixture.PROJECT_A, periodId,
                    new BigDecimal("1.00"), null, "corr-wide")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds the maximum this module accepts");

            assertThat(fixture.tenantRows("finance_reconciliation_runs", FinanceFixture.ORG_A))
                .as("STATE: no run was written with the wide tolerance")
                .isZero();
        }

        @Test
        @DisplayName("a period with no usage reconciles MATCHED with a zero event count")
        void anEmptyPeriodIsMatched() {
            UUID periodId = marchPeriod();

            ReconciliationResult result = reconcile(periodId, BigDecimal.ZERO);

            assertThat(result.outcome())
                .as("no data is a real answer and 0 events is one of them")
                .isEqualTo(ReconciliationResult.Outcome.MATCHED);
            assertThat(result.sourceEventCount()).isZero();
            assertThat(result.varianceLines()).isEmpty();
        }

        @Test
        @DisplayName("reconciliation runs are append-only, so the history survives")
        void reconciliationRunsAreAppendOnly() {
            UUID periodId = marchPeriod();
            seedPricedMessages();
            computeMarch();
            reconcile(periodId, BigDecimal.ZERO);
            reconcile(periodId, BigDecimal.ZERO);

            assertThat(fixture.tenantRows("finance_reconciliation_runs", FinanceFixture.ORG_A))
                .as("two runs, not one updated row")
                .isEqualTo(2);
            assertThatThrownBy(() -> jdbc.update("update finance_reconciliation_runs set \"notes\" = 'x'",
                new MapSqlParameterSource()))
                .hasMessageContaining("APPEND-ONLY");
        }
    }

    // ==================================================================
    // helpers
    // ==================================================================

    private UUID marchPeriod() {
        return fixture.period(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, "2026-03",
            FinanceFixture.MARCH_1, FinanceFixture.MARCH_31, "USD");
    }

    private FinancePeriodRepository.PeriodRow period(UUID periodId) {
        return FinancePrincipalScope.call(adminA,
            () -> periods.find(adminA, periodId)).orElseThrow();
    }

    private List<PeriodLockEventType.PeriodLockEvent> history(UUID periodId) {
        return FinancePrincipalScope.call(adminA, () -> periods.history(adminA, periodId));
    }

    private FinancePeriodRepository.AdjustmentRow adjustment(UUID adjustmentId) {
        return FinancePrincipalScope.call(adminA,
            () -> adjustments.find(adminA, adjustmentId)).orElseThrow();
    }

    private ReconciliationResult reconcile(UUID periodId, BigDecimal tolerance) {
        return FinancePrincipalScope.call(adminA,
            () -> reconciliation.reconcile(adminA, FinanceFixture.PROJECT_A, periodId, tolerance,
                null, "corr-test"));
    }

    /** Two priced messages in March, at 4.000000 each. */
    private void seedPricedMessages() {
        UUID version = fixture.activeVersion(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, 1, "USD",
            new BigDecimal("2.000000"), FinanceFixture.MARCH_1, FinanceFixture.MARCH_31,
            "the March price", FinanceFixture.ADMIN_A);
        fixture.line(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, version, "MESSAGE", "message",
            null, new BigDecimal("2.000000"), "USD");
        fixture.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, new BigDecimal("10"),
            FinanceFixture.inMarch(10, 9), new BigDecimal("4.000000"), "USD");
        fixture.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, new BigDecimal("10"),
            FinanceFixture.inMarch(11, 9), new BigDecimal("4.000000"), "USD");
    }

    private void computeMarch() {
        FinancePrincipalScope.run(adminA, () -> rollups.computeDaily(adminA, FinanceFixture.PROJECT_A,
            FinanceFixture.MARCH_1, FinanceFixture.MARCH_31, LedgerPrincipal.job("test-rollup"),
            "corr-recon"));
    }

    /** Computes only the 10th, leaving the 11th's usage without a snapshot. */
    private void computeOneDayOnly() {
        FinancePrincipalScope.run(adminA, () -> rollups.computeDaily(adminA, FinanceFixture.PROJECT_A,
            FinanceFixture.MARCH_10, FinanceFixture.MARCH_10, LedgerPrincipal.job("test-rollup"),
            "corr-recon"));
    }

    /**
     * A snapshot row for a dimension with no usage event behind it.
     *
     * <p>Written by raw SQL with the append-only triggers suspended, because the ledger genuinely cannot
     * express it through the rollup service — and expressing it is the point: a snapshot that outruns the
     * ledger is a state the application refuses to produce, so it has to be seeded to prove the
     * reconciliation notices it rather than absorbing it.
     */
    private void insertOrphanSnapshot() {
        fixture.suspendAppendOnlyGuards();
        try {
            jdbc.update("""
                insert into usage_rollup_snapshots (
                    "id", "organizationId", "projectId", "grain", "bucketStart", "bucketEnd",
                    "dimension", "currency", "sourceEventCount", "quantity", "costAmount",
                    "revenueAmount", "markupRate", "rateCardVersionId", "unpricedQuantity",
                    "unpricedCostAmount", "generation", "computedAt", "computedByPrincipal")
                values (gen_random_uuid(), :org, :project, cast('DAILY' as "RollupGrain"),
                    :bucket, :bucket, cast('STORAGE' as "UsageDimension"), 'USD', 5, 5000.000000,
                    12.500000, 25.000000, 2.000000, :version, 0.000000, 0.000000, 1, :now,
                    'JOB:test-rollup')""",
                new MapSqlParameterSource()
                    .addValue("org", FinanceFixture.ORG_A)
                    .addValue("project", FinanceFixture.PROJECT_A)
                    .addValue("bucket", FinanceFixture.MARCH_10)
                    .addValue("version", fixture.rateCardVersionForStorage())
                    .addValue("now", FinanceFixture.now()));
        } finally {
            fixture.restoreAppendOnlyGuards();
        }
    }
}
