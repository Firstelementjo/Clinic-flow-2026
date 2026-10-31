package com.firstelement.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.finance.catalog.UsageDimension;
import com.firstelement.finance.ledger.LedgerPrincipal;
import com.firstelement.finance.money.AmountState;
import com.firstelement.finance.reconcile.ReconciliationResult;
import com.firstelement.finance.reconcile.ReconciliationService;
import com.firstelement.finance.read.FinanceUsageReadService;
import com.firstelement.finance.read.FinanceUsageView;
import com.firstelement.finance.rollup.RollupBucket;
import com.firstelement.finance.rollup.RollupRepository;
import com.firstelement.finance.rollup.RollupService;
import com.firstelement.security.TenantContext;
import com.firstelement.security.error.PolicyDeniedException;
import com.firstelement.security.query.TenantQueryGuard;
import com.firstelement.security.query.TenantScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

/**
 * FIN-01: the amount a rollup bucket reports as <b>still unpriced</b> must never be the amount it
 * reports as <b>priced</b>.
 *
 * <h2>The defect this suite exists for</h2>
 *
 * <p>{@code RollupService.computeInScope} passed the bucket's own {@code cost} into
 * {@code RollupBucket.priced}'s {@code unpricedCost} slot while passing
 * {@code ledger.uncostedQuantity()} into the {@code unpricedQuantity} slot. Every priced bucket
 * therefore recorded its full cost a second time in the field that means "not yet priced", and any
 * figure that summed {@code unpricedCostAmount} counted billed money as unbilled exposure.
 *
 * <p>The database cannot catch it. {@code usage_rollup_snapshots_pricing_pin_check} ties the three
 * <em>pricing</em> columns, and {@code usage_rollup_snapshots_unpriced_pair_check} constrains only rows
 * whose {@code rateCardVersionId} is NULL — a fully priced row with a non-zero unpriced cost satisfies
 * both. The evidence therefore has to be at the writer and at every reader.
 *
 * <h2>Why the assertions are relationships and not only literals</h2>
 *
 * <p>Where the suite asserts {@code unpricedCost == 0} it is asserting what the arithmetic says: a
 * residue of {@code NULL}-cost events has no cost. The assertion beside it is the relationship,
 * {@code unpricedCost != cost}, because that is what the defect broke and it stays true if the residue's
 * definition ever widens.
 *
 * <h2>Why the unpriced branch is asserted at all</h2>
 *
 * <p>Because the obvious way to make the failing assertions go green is to zero the unpriced columns
 * everywhere, which would satisfy the priced assertions and destroy the one thing they must not: an
 * unpriced bucket's cost <em>is</em> its unpriced cost.
 * {@code usage_rollup_snapshots_unpriced_pair_check} refuses a zeroed unpriced row outright, so the bug
 * would move rather than disappear — but a suite that checked only the writer would not notice it had
 * been traded.
 *
 * <h2>Why the readers are here and not only in {@code UsageReadModelStateIntegrationTest}</h2>
 *
 * <p>Because a test that checks only the writer passes while a reader still sums the column wrongly.
 * {@link Readers} drives the read model, a tenant-scoped SQL sum of the shape the export and report
 * surfaces use, and the reconciler, all against the same seeded buckets.
 */
@SpringBootTest(classes = FinanceTestApplication.class)
class RollupUnpricedCostIntegrityIntegrationTest extends FinanceIntegrationTestBase {

    @Autowired
    private RollupService rollups;
    @Autowired
    private RollupRepository rollupRepository;
    @Autowired
    private FinanceUsageReadService usage;
    @Autowired
    private ReconciliationService reconciliation;
    @Autowired
    private TenantQueryGuard guard;
    @Autowired
    private FinanceFixture fixture;
    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    private TenantContext adminA;

    @BeforeEach
    void reset() {
        fixture.reset();
        adminA = FinancePrincipalScope.adminA();
    }

    // ==================================================================
    // the writer
    // ==================================================================

    @Nested
    @DisplayName("a priced bucket's unpriced cost is not its cost")
    class PricedWriter {

        @Test
        @DisplayName("a priced bucket records no unpriced cost, and cannot equal its own cost")
        void aPricedBucketDoesNotRecordItsCostAsUnpriced() {
            LocalDate day = LocalDate.of(2026, 3, 10);
            priceMessages(new BigDecimal("2.000000"));
            fixture.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, new BigDecimal("10"),
                FinanceFixture.inMarch(10, 9), new BigDecimal("4.000000"), "USD");

            RollupBucket bucket = computeOne(day);

            assertThat(bucket.isPriced()).isTrue();
            assertThat(bucket.cost().amount()).isEqualByComparingTo("4.000000");

            // THE RELATIONSHIP. The defect made these two the same number on every priced bucket, so it
            // is the assertion that survives a change to what the residue means.
            assertThat(bucket.unpricedCost())
                .as("the same money cannot be billed and unbilled at once")
                .isNotEqualByComparingTo(bucket.cost().amount());
            assertThat(bucket.unpricedCost())
                .as("the residue is NULL-cost usage, so its cost is the sum of no rows")
                .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(bucket.hasUnpricedResidue())
                .as("every event here carried a measured cost, so there is no residue to disclose")
                .isFalse();
        }

        @Test
        @DisplayName("the stored row says the same thing the returned bucket does")
        void theStoredRowAgreesWithTheReturnedBucket() {
            LocalDate day = LocalDate.of(2026, 3, 10);
            priceMessages(new BigDecimal("2.000000"));
            fixture.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, new BigDecimal("10"),
                FinanceFixture.inMarch(10, 9), new BigDecimal("4.000000"), "USD");

            RollupBucket bucket = computeOne(day);
            RollupRepository.SnapshotRow stored = stored(day);

            assertThat(stored.id()).isEqualTo(bucket.snapshotId());
            assertThat(stored.costAmount()).isEqualByComparingTo(bucket.cost().amount());
            assertThat(stored.rateCardVersionId())
                .as("a control: the row really was priced, so this is not an unpriced bucket")
                .isNotNull();
            assertThat(stored.unpricedCostAmount())
                .as("STATE, not the response: the row in the table is the evidence")
                .isNotEqualByComparingTo(stored.costAmount());
            assertThat(stored.unpricedCostAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(stored.unpricedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("the MONTHLY grain is fixed too, not only the daily one")
        void theMonthlyGrainIsFixedToo() {
            // DAILY and MONTHLY share computeInScope, so they shared the fault. Asserted separately
            // because "they share one method" is a claim about the code and this is a claim about a
            // stored figure.
            UUID version = fixture.activeVersion(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, 1,
                "USD", new BigDecimal("2.000000"), FinanceFixture.MARCH_1, FinanceFixture.MARCH_31,
                "the March price", FinanceFixture.ADMIN_A);
            fixture.line(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, version, "MESSAGE", "message",
                null, new BigDecimal("2.000000"), "USD");
            fixture.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, new BigDecimal("10"),
                FinanceFixture.inMarch(10, 9), new BigDecimal("4.000000"), "USD");
            fixture.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, new BigDecimal("10"),
                FinanceFixture.inMarch(20, 9), new BigDecimal("6.000000"), "USD");

            RollupBucket messages = only(computeMonthly(), UsageDimension.MESSAGE);

            assertThat(messages.grain()).isEqualTo(RollupBucket.Grain.MONTHLY);
            assertThat(messages.isPriced()).isTrue();
            assertThat(messages.cost().amount()).isEqualByComparingTo("10.000000");
            assertThat(messages.revenue().amount())
                .as("the pinned markup still applies to the whole monthly cost")
                .isEqualByComparingTo("20.000000");
            assertThat(messages.unpricedCost()).isNotEqualByComparingTo(messages.cost().amount());
            assertThat(messages.unpricedCost()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("a priced bucket with uncosted events discloses the residue, and no money with it")
        void aPricedBucketDisclosesResidueWithoutInventingUnbilledMoney() {
            // The markup-only rate case: it marks up the costs it was given and the events that had none
            // are still unpriced. This is the state RollupBucket.hasUnpricedResidue() describes, and the
            // only case where a PRICED row carries a positive unpricedQuantity.
            LocalDate day = LocalDate.of(2026, 3, 10);
            priceMessages(new BigDecimal("2.000000"));
            fixture.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, new BigDecimal("10"),
                FinanceFixture.inMarch(10, 9), new BigDecimal("4.000000"), "USD");
            fixture.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, new BigDecimal("5"),
                FinanceFixture.inMarch(10, 10), null, "USD");

            RollupBucket bucket = computeOne(day);

            assertThat(bucket.isPriced()).isTrue();
            assertThat(bucket.sourceEventCount()).as("both events counted").isEqualTo(2);
            assertThat(bucket.quantity()).isEqualByComparingTo("15");
            assertThat(bucket.unpricedQuantity())
                .as("the five messages whose event carried no measured cost")
                .isEqualByComparingTo("5");
            assertThat(bucket.unpricedCost())
                .as("a NULL-cost event contributes nothing to a cost sum, so the residue costs nothing")
                .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(bucket.unpricedCost()).isNotEqualByComparingTo(bucket.cost().amount());
            assertThat(bucket.hasUnpricedResidue()).isTrue();
        }
    }

    // ==================================================================
    // the unpriced branch must keep working
    // ==================================================================

    @Nested
    @DisplayName("an unpriced bucket still records its cost as unpriced")
    class UnpricedWriter {

        @Test
        @DisplayName("usage with no rate carries its whole cost in the unpriced columns")
        void anUnpricedBucketRecordsItsCostAsUnpriced() {
            // No rate card at all. The defect's tempting "fix" is to zero these columns everywhere, and
            // this is the assertion that refuses it.
            LocalDate day = LocalDate.of(2026, 3, 12);
            fixture.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, new BigDecimal("10"),
                FinanceFixture.inMarch(12, 9), new BigDecimal("4.000000"), "USD");

            RollupBucket bucket = computeOne(day);

            assertThat(bucket.isUnpriced()).isTrue();
            assertThat(bucket.cost().amount()).as("the cost is real").isEqualByComparingTo("4.000000");
            assertThat(bucket.revenue().state()).isEqualTo(AmountState.UNPRICED);
            assertThat(bucket.unpricedQuantity()).isEqualByComparingTo(bucket.quantity());
            assertThat(bucket.unpricedCost())
                .as("an unpriced bucket's cost IS its unpriced cost -- that is the whole claim")
                .isEqualByComparingTo(bucket.cost().amount());
            assertThat(bucket.hasUnpricedResidue()).isTrue();

            RollupRepository.SnapshotRow stored = stored(day);
            assertThat(stored.unpricedCostAmount()).isEqualByComparingTo("4.000000");
            assertThat(stored.unpricedQuantity()).isEqualByComparingTo("10");
        }

        @Test
        @DisplayName("a rate that does not cover the whole bucket is recorded unpriced, not half-priced")
        void aRateThatDoesNotCoverTheBucketIsUnpriced() {
            // The MONTHLY mid-period-rate-change case: a rate that ended on the 15th cannot price the
            // 31st, so the bucket is unpriced and its cost is unpriced money. This is the other route to
            // the unpriced branch, and a fix to the priced branch must not disturb it.
            UUID version = fixture.activeVersion(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, 1,
                "USD", new BigDecimal("2.000000"), FinanceFixture.MARCH_1, LocalDate.of(2026, 3, 15),
                "expires mid-month", FinanceFixture.ADMIN_A);
            fixture.line(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, version, "MESSAGE", "message",
                null, new BigDecimal("2.000000"), "USD");
            fixture.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, new BigDecimal("10"),
                FinanceFixture.inMarch(20, 9), new BigDecimal("7.000000"), "USD");

            RollupBucket messages = only(computeMonthly(), UsageDimension.MESSAGE);

            assertThat(messages.isUnpriced()).as("the version does not span March").isTrue();
            assertThat(messages.unpricedCost()).isEqualByComparingTo(messages.cost().amount());
            assertThat(messages.unpricedCost()).isEqualByComparingTo("7.000000");
        }
    }

    // ==================================================================
    // the four states
    // ==================================================================

    @Nested
    @DisplayName("unpriced, priced-zero, priced and no-data are four different things")
    class FourStates {

        @Test
        @DisplayName("each of the four states keeps its own unpriced signature")
        void theFourStatesHaveDistinctUnpricedSignatures() {
            LocalDate pricedDay = LocalDate.of(2026, 3, 10);
            LocalDate zeroDay = LocalDate.of(2026, 3, 11);
            LocalDate unpricedDay = LocalDate.of(2026, 3, 12);

            UUID version = fixture.activeVersion(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, 1,
                "USD", new BigDecimal("2.000000"), pricedDay, zeroDay, "two days only",
                FinanceFixture.ADMIN_A);
            fixture.line(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, version, "MESSAGE", "message",
                null, new BigDecimal("2.000000"), "USD");
            fixture.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, new BigDecimal("10"),
                FinanceFixture.inMarch(10, 9), new BigDecimal("4.000000"), "USD");
            fixture.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, new BigDecimal("1"),
                FinanceFixture.inMarch(11, 9), BigDecimal.ZERO, "USD");
            fixture.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, new BigDecimal("10"),
                FinanceFixture.inMarch(12, 9), new BigDecimal("4.000000"), "USD");

            computeDaily(pricedDay, unpricedDay, "corr-four");
            // The window runs one day past the seeded usage, so NO_DATA is a gap inside a populated
            // window rather than the absence of a window.
            FinanceUsageView view = view(pricedDay, unpricedDay.plusDays(1));

            FinanceUsageView.BucketRow priced = row(view, pricedDay);
            FinanceUsageView.BucketRow zero = row(view, zeroDay);
            FinanceUsageView.BucketRow unpriced = row(view, unpricedDay);

            assertThat(priced.revenue().state()).isEqualTo(AmountState.PRICED_NONZERO);
            assertThat(zero.revenue().state()).isEqualTo(AmountState.PRICED_ZERO);
            assertThat(unpriced.revenue().state()).isEqualTo(AmountState.UNPRICED);
            assertThat(AmountState.values()).as("NO_DATA is the fourth").hasSize(4);

            // PRICED and PRICED_ZERO: priced money, no unpriced money. They are told apart by the cost
            // and revenue states, and must not be told apart by this column either.
            assertThat(priced.unpricedCost()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(zero.unpricedCost()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(priced.hasUnpricedResidue()).isFalse();
            assertThat(zero.hasUnpricedResidue()).isFalse();

            // UNPRICED: a real cost, and it is unpriced money. The one state where the column is
            // non-zero, which is what makes the column worth having.
            assertThat(unpriced.unpricedCost()).isEqualByComparingTo("4.000000");
            assertThat(unpriced.unpricedCost()).isEqualByComparingTo(unpriced.cost().amount());

            // NO_DATA: no row at all, so nothing to summarise. A zero row here would render the fourth
            // state as the third.
            assertThat(view.buckets()).as("three rows, no fourth for the gap").hasSize(3);
            assertThat(view.missingBuckets())
                .as("the 13th had no usage and is enumerated, not materialised as a zero bucket")
                .containsExactly(unpricedDay.plusDays(1));
            assertThat(view.unpriced().bucketCount()).isEqualTo(1);
            assertThat(view.unpriced().cost().amount())
                .as("the window's unbilled exposure is that bucket's cost and nothing else")
                .isEqualByComparingTo("4.000000");
        }
    }

    // ==================================================================
    // the readers
    // ==================================================================

    @Nested
    @DisplayName("every reader of the unpriced columns is asserted here")
    class Readers {

        @Test
        @DisplayName("the read model's unbilled figure excludes a priced bucket's cost")
        void theReadModelExcludesPricedCost() {
            // MESSAGE is priced at 4.000000; DOCUMENT_CHUNK has usage, no rate, and a real cost.
            LocalDate day = LocalDate.of(2026, 3, 10);
            priceMessages(new BigDecimal("2.000000"));
            fixture.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, new BigDecimal("10"),
                FinanceFixture.inMarch(10, 9), new BigDecimal("4.000000"), "USD");
            chunk(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, new BigDecimal("40"),
                FinanceFixture.inMarch(10, 11), new BigDecimal("1.500000"), "USD");

            computeDaily(day, day, "corr-readers");
            FinanceUsageView view = view(day, day);

            assertThat(view.buckets()).as("both dimensions have a row").hasSize(2);
            assertThat(view.totals().cost().amount())
                .as("the window's total cost is the sum of both buckets")
                .isEqualByComparingTo("5.500000");
            assertThat(view.unpriced().bucketCount()).isEqualTo(1);
            assertThat(view.unpriced().cost().amount())
                .as("the unbilled exposure is the unpriced bucket's 1.500000, NOT 5.500000")
                .isEqualByComparingTo("1.500000");
            assertThat(view.unpriced().cost().amount())
                .isNotEqualByComparingTo(view.totals().cost().amount());
            assertThat(view.unpriced().cost().currency()).isEqualTo("USD");

            // And the quantity is a count, not an amount: 40 chunks. Handing the quantity to the money
            // field is the reader half of the defect, and it is visible only as a disagreement between
            // the two figures -- so both are asserted.
            assertThat(view.unpriced().quantity()).isEqualByComparingTo("40");
            assertThat(view.unpriced().cost().amount())
                .as("40 chunks is not 40 USD, and the two figures must not be the same number")
                .isNotEqualByComparingTo(view.unpriced().quantity());
        }

        @Test
        @DisplayName("a SQL sum of unpricedCostAmount double-counts nothing")
        void aSqlSumOfUnpricedCostDoubleCountsNothing() {
            // V13's unpriced view, and the shape any unbilled-exposure report takes. The first sum has NO
            // state filter, which is the only way a writer defect shows up through a reader.
            LocalDate pricedDay = LocalDate.of(2026, 3, 10);
            LocalDate unpricedDay = LocalDate.of(2026, 3, 12);
            priceMessages(new BigDecimal("2.000000"));
            fixture.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, new BigDecimal("10"),
                FinanceFixture.inMarch(10, 9), new BigDecimal("4.000000"), "USD");
            chunk(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, new BigDecimal("40"),
                FinanceFixture.inMarch(12, 11), new BigDecimal("1.500000"), "USD");

            computeDaily(pricedDay, unpricedDay, "corr-sql-sum");

            BigDecimal unfiltered = unpricedCostSumForProject(false);
            BigDecimal unpricedOnly = unpricedCostSumForProject(true);
            BigDecimal totalCost = jdbc.queryForObject("""
                select coalesce(sum("costAmount"), 0) from usage_rollup_snapshots
                 where "organizationId" = :org and "projectId" = :project""",
                tenantAProjectParameters(), BigDecimal.class);

            // The defect made unfiltered == totalCost, because the priced bucket's cost sat in the column
            // too: 5.500000 reported as unbilled against 4.000000 genuinely billed. A reader that sums
            // the column with no state filter has to land on the same answer as one that filters, or the
            // writer is lying.
            assertThat(unfiltered)
                .as("an unfiltered sum must agree with the unpriced-only sum")
                .isEqualByComparingTo(unpricedOnly);
            assertThat(unfiltered).isEqualByComparingTo("1.500000");
            assertThat(totalCost).isEqualByComparingTo("5.500000");
            assertThat(unfiltered)
                .as("unbilled money must not overlap billed money")
                .isLessThan(totalCost);
        }

        @Test
        @DisplayName("the reconciler matches a period whose snapshots carry both states")
        void theReconcilerStillMatches() {
            LocalDate pricedDay = LocalDate.of(2026, 3, 10);
            LocalDate unpricedDay = LocalDate.of(2026, 3, 12);
            UUID periodId = fixture.period(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, "2026-03",
                FinanceFixture.MARCH_1, FinanceFixture.MARCH_31, "USD");
            priceMessages(new BigDecimal("2.000000"));
            fixture.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, new BigDecimal("10"),
                FinanceFixture.inMarch(10, 9), new BigDecimal("4.000000"), "USD");
            chunk(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, new BigDecimal("40"),
                FinanceFixture.inMarch(12, 11), new BigDecimal("1.500000"), "USD");

            computeDaily(pricedDay, unpricedDay, "corr-recon");
            ReconciliationResult result = FinancePrincipalScope.call(adminA,
                () -> reconciliation.reconcile(adminA, FinanceFixture.PROJECT_A, periodId,
                    BigDecimal.ZERO, null, "corr-recon"));

            assertThat(result.outcome()).isEqualTo(ReconciliationResult.Outcome.MATCHED);
            assertThat(result.varianceLines()).isEmpty();
            assertThat(result.snapshotAmount().amount())
                .as("the reconciled money total is costAmount alone; the unpriced column is not added")
                .isEqualByComparingTo("5.500000");
            assertThat(result.ledgerAmount().amount()).isEqualByComparingTo("5.500000");
            assertThat(result.snapshotAmount().amount())
                .as("a reconciler that also summed unpricedCostAmount would report 7.000000 here")
                .isNotEqualByComparingTo("7.000000");
        }

        @Test
        @DisplayName("the snapshot read the drill-through uses carries the corrected columns")
        void theSnapshotReadCarriesTheCorrectedColumns() {
            // DrillThroughService re-reads the snapshot by id through its own statement, so a stale
            // projection of unpricedCostAmount there would not be caught by the read model's assertions.
            LocalDate day = LocalDate.of(2026, 3, 10);
            priceMessages(new BigDecimal("2.000000"));
            fixture.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, new BigDecimal("10"),
                FinanceFixture.inMarch(10, 9), new BigDecimal("4.000000"), "USD");

            RollupBucket bucket = computeOne(day);

            // The same statement shape DrillThroughService.resolve issues, tenant-scoped.
            RollupRepository.SnapshotRow drilled = jdbc.queryForObject("""
                select s."id", s."organizationId", s."projectId", s."periodId", s."grain"::text,
                       s."bucketStart", s."bucketEnd", s."dimension"::text, s."currency",
                       s."sourceEventCount", s."quantity", s."costAmount", s."revenueAmount",
                       s."markupRate", s."rateCardVersionId", s."unpricedQuantity",
                       s."unpricedCostAmount", s."generation", s."supersedesSnapshotId",
                       s."computedAt", s."computedByPrincipal", s."correlationId"
                  from usage_rollup_snapshots s
                 where s."organizationId" = :__fe_tenant_organization_id
                   and s."id" = :snapshotId""",
                new MapSqlParameterSource()
                    .addValue("__fe_tenant_organization_id", FinanceFixture.ORG_A)
                    .addValue("snapshotId", bucket.snapshotId()),
                (rs, rowNum) -> new RollupRepository.SnapshotRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("organizationId", UUID.class),
                    rs.getObject("projectId", UUID.class),
                    rs.getObject("periodId", UUID.class),
                    rs.getString("grain"),
                    rs.getObject("bucketStart", LocalDate.class),
                    rs.getObject("bucketEnd", LocalDate.class),
                    rs.getString("dimension"),
                    rs.getString("currency"),
                    rs.getLong("sourceEventCount"),
                    rs.getBigDecimal("quantity"),
                    rs.getBigDecimal("costAmount"),
                    rs.getBigDecimal("revenueAmount"),
                    rs.getBigDecimal("markupRate"),
                    rs.getObject("rateCardVersionId", UUID.class),
                    rs.getBigDecimal("unpricedQuantity"),
                    rs.getBigDecimal("unpricedCostAmount"),
                    rs.getInt("generation"),
                    rs.getObject("supersedesSnapshotId", UUID.class),
                    rs.getObject("computedAt", LocalDateTime.class),
                    rs.getString("computedByPrincipal"),
                    rs.getString("correlationId")));

            assertThat(drilled.id()).isEqualTo(bucket.snapshotId());
            assertThat(drilled.costAmount()).isEqualByComparingTo("4.000000");
            assertThat(drilled.unpricedCostAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(drilled.unpricedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    // ==================================================================
    // tenant isolation
    // ==================================================================

    @Nested
    @DisplayName("no other tenant's unpriced money is visible")
    class TenantIsolation {

        @Test
        @DisplayName("tenant A's unbilled exposure excludes tenant B's buckets, priced and unpriced")
        void unpricedExposureIsTenantScoped() {
            LocalDate day = LocalDate.of(2026, 3, 10);

            // Tenant A: a priced MESSAGE bucket and an unpriced DOCUMENT_CHUNK bucket, so A's window has
            // real unbilled money of its own -- otherwise a missing tenant predicate would have nothing
            // to distort and the assertion would be vacuous.
            priceMessages(new BigDecimal("2.000000"));
            fixture.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, new BigDecimal("10"),
                FinanceFixture.inMarch(10, 9), new BigDecimal("4.000000"), "USD");
            chunk(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, new BigDecimal("40"),
                FinanceFixture.inMarch(10, 11), new BigDecimal("1.500000"), "USD");

            // Tenant B: the same two states with magnitudes an order of magnitude larger, so a leak is a
            // wrong number rather than a plausible one.
            TenantContext adminB = FinancePrincipalScope.adminB();
            UUID versionB = fixture.activeVersion(FinanceFixture.ORG_B, FinanceFixture.PROJECT_B, 1,
                "USD", new BigDecimal("2.000000"), FinanceFixture.MARCH_1, FinanceFixture.MARCH_31,
                "tenant B price", FinanceFixture.ADMIN_B);
            fixture.line(FinanceFixture.ORG_B, FinanceFixture.PROJECT_B, versionB, "MESSAGE",
                "message", null, new BigDecimal("2.000000"), "USD");
            fixture.message(FinanceFixture.ORG_B, FinanceFixture.PROJECT_B, new BigDecimal("10"),
                FinanceFixture.inMarch(10, 9), new BigDecimal("900.000000"), "USD");
            chunk(FinanceFixture.ORG_B, FinanceFixture.PROJECT_B, new BigDecimal("40"),
                FinanceFixture.inMarch(10, 11), new BigDecimal("800.000000"), "USD");
            FinancePrincipalScope.run(adminB, () -> rollups.computeDaily(adminB,
                FinanceFixture.PROJECT_B, day, day, LedgerPrincipal.job("test-rollup"),
                "corr-tenant-b"));
            computeDaily(day, day, "corr-tenant-a");

            FinanceUsageView viewForA = view(day, day);

            assertThat(viewForA.organizationId()).isEqualTo(FinanceFixture.ORG_A);
            assertThat(viewForA.buckets()).as("only tenant A's two dimensions").hasSize(2);
            assertThat(viewForA.totals().cost().amount()).isEqualByComparingTo("5.500000");
            assertThat(viewForA.unpriced().bucketCount()).isEqualTo(1);
            assertThat(viewForA.unpriced().cost().amount())
                .as("tenant A's own unbilled money, and only that")
                .isEqualByComparingTo("1.500000");

            // Tenant B holds 800.000000 of genuinely unbilled money, none of which may appear in A's
            // view. The assertion is on the aggregate rather than on the row list, because a read-then-
            // filter reader would satisfy the list and still report the wrong exposure.
            BigDecimal tenantBUnpriced = jdbc.queryForObject("""
                select coalesce(sum("unpricedCostAmount"), 0) from usage_rollup_snapshots
                 where "organizationId" = :org""",
                new MapSqlParameterSource().addValue("org", FinanceFixture.ORG_B), BigDecimal.class);
            assertThat(tenantBUnpriced).as("B really does hold unbilled money")
                .isEqualByComparingTo("800.000000");
            assertThat(viewForA.unpriced().cost().amount())
                .as("another tenant's unbilled money is not in this one's view")
                .isNotEqualByComparingTo("800.000000");
            assertThat(unpricedCostSumFor(FinanceFixture.ORG_A))
                .as("and a tenant-scoped SQL sum agrees with the read model")
                .isEqualByComparingTo("1.500000");
        }

        @Test
        @DisplayName("a principal outside the project sees no rollup bucket at all")
        void aPrincipalOutsideTheProjectSeesNothing() {
            LocalDate day = LocalDate.of(2026, 3, 10);
            priceMessages(new BigDecimal("2.000000"));
            fixture.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, new BigDecimal("10"),
                FinanceFixture.inMarch(10, 9), new BigDecimal("4.000000"), "USD");
            chunk(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, new BigDecimal("40"),
                FinanceFixture.inMarch(10, 11), new BigDecimal("1.500000"), "USD");
            computeDaily(day, day, "corr-outside");

            // Satisfies ANALYTICS_READ_ORGANIZATION, is in the snapshot's own tenant, and is refused only
            // because of the project. ADMIN on purpose: an EMPLOYEE would be stopped earlier by
            // @RequirePermission and this would pass without the membership guard ever running.
            TenantContext outside = FinancePrincipalScope.adminAWithoutProjectA();

            assertThatThrownBy(() -> FinancePrincipalScope.call(outside, () -> usage.usage(outside,
                FinanceFixture.PROJECT_A, RollupBucket.Grain.DAILY, day, day)))
                .as("the permission holds, so it is the project scope that refuses")
                .isInstanceOf(PolicyDeniedException.class);

            // POSITIVE CONTROL: the same principal, pointed at the project it does hold. Without it, a
            // reader that returned NO_DATA for everything would satisfy the refusal. Its own card, since
            // rateCard is keyed by (organization, key) and PROJECT_A2's "standard" card is PROJECT_A's.
            UUID versionA2 = fixture.activeVersionOnKey(FinanceFixture.ORG_A,
                FinanceFixture.PROJECT_A2, "second-project", 1, "USD", new BigDecimal("2.000000"),
                FinanceFixture.MARCH_1, FinanceFixture.MARCH_31, "second project price",
                FinanceFixture.ADMIN_A);
            fixture.line(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A2, versionA2, "MESSAGE",
                "message", null, new BigDecimal("2.000000"), "USD");
            fixture.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A2, new BigDecimal("10"),
                FinanceFixture.inMarch(10, 9), new BigDecimal("3.000000"), "USD");
            FinancePrincipalScope.run(outside, () -> rollups.computeDaily(outside,
                FinanceFixture.PROJECT_A2, day, day, LedgerPrincipal.job("test-rollup"),
                "corr-outside-2"));

            FinanceUsageView own = FinancePrincipalScope.call(outside, () -> usage.usage(outside,
                FinanceFixture.PROJECT_A2, RollupBucket.Grain.DAILY, day, day));

            assertThat(own.buckets())
                .as("so the refusal is about PROJECT_A's membership, not about the caller's emptiness")
                .hasSize(1);
            assertThat(own.unpriced().cost().amount())
                .as("a fully priced project reports no unbilled money at all")
                .isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    // ==================================================================
    // helpers
    // ==================================================================

    private void priceMessages(BigDecimal markup) {
        UUID version = fixture.activeVersion(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, 1, "USD",
            markup, FinanceFixture.MARCH_1, FinanceFixture.MARCH_31, "seeded rate",
            FinanceFixture.ADMIN_A);
        fixture.line(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, version, "MESSAGE", "message",
            null, markup, "USD");
    }

    /** A DOCUMENT_CHUNK event, which no seeded rate card prices. */
    private void chunk(UUID organizationId, UUID projectId, BigDecimal quantity,
                       LocalDateTime occurredAt, BigDecimal cost, String currency) {
        fixture.usageEvent(organizationId, projectId, "DOCUMENT_CHUNK", quantity, "chunk", occurredAt,
            FinanceFixture.MARCH_1, FinanceFixture.MARCH_31, UUID.randomUUID(), "documents.indexed",
            cost, currency, null, Map.of());
    }

    private RollupBucket computeOne(LocalDate day) {
        List<RollupBucket> computed = FinancePrincipalScope.call(adminA,
            () -> rollups.computeDaily(adminA, FinanceFixture.PROJECT_A, day, day,
                LedgerPrincipal.job("test-rollup"), "corr-unpriced"));
        assertThat(computed).as("a bucket with usage is materialised").hasSize(1);
        return computed.get(0);
    }

    private void computeDaily(LocalDate from, LocalDate to, String correlationId) {
        FinancePrincipalScope.run(adminA, () -> rollups.computeDaily(adminA, FinanceFixture.PROJECT_A,
            from, to, LedgerPrincipal.job("test-rollup"), correlationId));
    }

    private List<RollupBucket> computeMonthly() {
        return FinancePrincipalScope.call(adminA,
            () -> rollups.computeRange(adminA, FinanceFixture.PROJECT_A, RollupBucket.Grain.MONTHLY,
                FinanceFixture.MARCH_1, FinanceFixture.MARCH_31, LedgerPrincipal.job("test-rollup"),
                "corr-monthly"));
    }

    private static RollupBucket only(List<RollupBucket> buckets, UsageDimension dimension) {
        return buckets.stream().filter(bucket -> bucket.dimension() == dimension).findFirst()
            .orElseThrow(() -> new AssertionError("no " + dimension + " bucket"));
    }

    private RollupRepository.SnapshotRow stored(LocalDate day) {
        return FinancePrincipalScope.call(adminA,
            () -> rollupRepository.currentBucket(tenantScope(), FinanceFixture.PROJECT_A,
                RollupBucket.Grain.DAILY, day, UsageDimension.MESSAGE)).orElseThrow();
    }

    private FinanceUsageView view(LocalDate from, LocalDate to) {
        return FinancePrincipalScope.call(adminA,
            () -> usage.usage(adminA, FinanceFixture.PROJECT_A, RollupBucket.Grain.DAILY, from, to));
    }

    private static FinanceUsageView.BucketRow row(FinanceUsageView view, LocalDate day) {
        return view.buckets().stream()
            .filter(bucket -> bucket.dimension() == UsageDimension.MESSAGE)
            .filter(bucket -> bucket.bucketStart().equals(day))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no MESSAGE bucket on " + day));
    }

    /**
     * Sums {@code unpricedCostAmount} the way an unbilled-exposure reader would.
     *
     * @param unpricedOnly appends {@code and "rateCardVersionId" is null}; empty for an unfiltered sum,
     *                     which is the shape that turns a writer defect into a doubled report
     */
    private BigDecimal unpricedCostSumForProject(boolean unpricedOnly) {
        return jdbc.queryForObject("""
            select coalesce(sum(s."unpricedCostAmount"), 0) from usage_rollup_snapshots s
             where s."organizationId" = :org and s."projectId" = :project"""
            + (unpricedOnly ? " and s.\"rateCardVersionId\" is null" : ""),
            tenantAProjectParameters(), BigDecimal.class);
    }

    /** The same sum for one tenant as a whole, which is the tenant-scoped reader's shape. */
    private BigDecimal unpricedCostSumFor(UUID organizationId) {
        return jdbc.queryForObject("""
            select coalesce(sum(s."unpricedCostAmount"), 0) from usage_rollup_snapshots s
             where s."organizationId" = :__fe_tenant_organization_id""",
            new MapSqlParameterSource().addValue("__fe_tenant_organization_id", organizationId),
            BigDecimal.class);
    }

    private MapSqlParameterSource tenantAProjectParameters() {
        return new MapSqlParameterSource()
            .addValue("org", FinanceFixture.ORG_A)
            .addValue("project", FinanceFixture.PROJECT_A);
    }

    private TenantScope tenantScope() {
        return guard.tenantScope(adminA);
    }
}
