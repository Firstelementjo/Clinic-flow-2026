package com.firstelement.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.finance.catalog.UsageDimension;
import com.firstelement.finance.ledger.LedgerPrincipal;
import com.firstelement.finance.money.AmountState;
import com.firstelement.finance.read.FinanceForecast;
import com.firstelement.finance.read.FinanceUsageReadService;
import com.firstelement.finance.read.FinanceUsageView;
import com.firstelement.finance.read.ForecastService;
import com.firstelement.finance.rollup.RollupBucket;
import com.firstelement.finance.rollup.RollupService;
import com.firstelement.security.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The read model's four states, which ADR 0007 rule 5 requires to be distinguishable: <b>priced,
 * unpriced, priced-zero and no-data</b>.
 *
 * <h2>Why each one is seeded separately</h2>
 *
 * <p>Because a two-state model can hold at most two of them, and a test that seeds only the happy path
 * would pass against a reader that collapses the rest. So every fixture here produces a state a
 * two-state reader would render identically to another one:
 *
 * <ul>
 *   <li>a <b>priced</b> bucket, for the ordinary case;</li>
 *   <li>a <b>priced zero</b> bucket — a rate that applied and the cost summed to exactly zero, which
 *       {@code coalesce(amount, 0)} cannot distinguish from no data;</li>
 *   <li>an <b>unpriced</b> bucket — real usage, a real cost, and no rate effective on the date;</li>
 *   <li>a <b>gap</b> in the middle of the window, so no-data is an absence in a populated series rather
 *       than an empty result.</li>
 * </ul>
 *
 * <h2>Why the unpriced bucket is the interesting one</h2>
 *
 * <p>Because it is the state that a careless implementation turns into zero. An unpriced bucket's
 * {@code revenueAmount} is NULL in the database and its {@code unpricedQuantity} is positive, and the
 * read model has to carry that distinction all the way to the wire. {@link Money#amount()} throws for
 * the absent states, so a caller that forgets to branch gets an exception rather than a {@code 0} — which
 * is the point of the type.
 */
@SpringBootTest(classes = FinanceTestApplication.class)
class UsageReadModelStateIntegrationTest extends FinanceIntegrationTestBase {

    @Autowired
    private RollupService rollups;
    @Autowired
    private FinanceUsageReadService usage;
    @Autowired
    private ForecastService forecasts;
    @Autowired
    private FinanceFixture fixture;

    private TenantContext adminA;

    @BeforeEach
    void reset() {
        fixture.reset();
        adminA = FinancePrincipalScope.adminA();
    }

    // ==================================================================
    // the four states
    // ==================================================================

    @Nested
    @DisplayName("unpriced, zero and no-data are three distinct states, not two")
    class FourStates {

        @Test
        @DisplayName("a priced bucket reports numbers in its currency, naming its rate")
        void aPricedBucket() {
            LocalDate day = LocalDate.of(2026, 3, 10);
            priceMessages(new BigDecimal("2.000000"));
            fixture.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, new BigDecimal("10"),
                FinanceFixture.inMarch(10, 9), new BigDecimal("4.000000"), "USD");
            compute(day);

            FinanceUsageView.BucketRow row = messageRow(day);

            assertThat(row.isPriced()).isTrue();
            assertThat(row.cost().state()).isEqualTo(AmountState.PRICED_NONZERO);
            assertThat(row.cost().amount()).isEqualByComparingTo("4.000000");
            assertThat(row.revenue().amount()).isEqualByComparingTo("8.000000");
            assertThat(row.margin().amount()).isEqualByComparingTo("4.000000");
            assertThat(row.cost().currency()).isEqualTo("USD");
            assertThat(row.margin().isNegative()).isFalse();
        }

        @Test
        @DisplayName("a rate that applies to a zero cost is PRICED_ZERO, not no-data")
        void aPricedZeroIsNotNoData() {
            LocalDate day = LocalDate.of(2026, 3, 11);
            priceMessages(new BigDecimal("2.000000"));
            // A real usage event whose measured cost is genuinely zero: a free channel tier.
            fixture.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, new BigDecimal("1"),
                FinanceFixture.inMarch(11, 9), BigDecimal.ZERO, "USD");
            compute(day);

            FinanceUsageView.BucketRow row = messageRow(day);

            assertThat(row.sourceEventCount()).as("usage really happened").isEqualTo(1);
            assertThat(row.isPriced()).isTrue();
            assertThat(row.cost().state()).isEqualTo(AmountState.PRICED_ZERO);
            assertThat(row.revenue().state()).isEqualTo(AmountState.PRICED_ZERO);
            assertThat(row.revenue().amount()).isEqualByComparingTo("0.000000");
            assertThat(row.cost().isZero()).isTrue();
            assertThat(row.cost().isNoData()).isFalse();
            assertThat(row.cost().isUnpriced()).isFalse();
        }

        @Test
        @DisplayName("an unpriced bucket keeps its cost and refuses to produce revenue or margin")
        void anUnpricedBucketKeepsItsCost() {
            // No rate card at all: the strongest form of unpriced, because there is nothing that could
            // have priced it.
            LocalDate day = LocalDate.of(2026, 3, 12);
            fixture.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, new BigDecimal("10"),
                FinanceFixture.inMarch(12, 9), new BigDecimal("4.000000"), "USD");
            compute(day);

            FinanceUsageView.BucketRow row = messageRow(day);

            assertThat(row.isPriced()).isFalse();
            assertThat(row.cost().state())
                .as("the cost is real and is not the thing that is missing")
                .isEqualTo(AmountState.PRICED_NONZERO);
            assertThat(row.cost().amount()).isEqualByComparingTo("4.000000");
            assertThat(row.revenue().state()).isEqualTo(AmountState.UNPRICED);
            assertThat(row.margin().state()).isEqualTo(AmountState.UNPRICED);
            assertThat(row.markupRate()).isNull();
            assertThat(row.rateCardVersionId()).isNull();
            assertThat(row.rateDefinition())
                .contains("unpriced")
                .contains("2026-03-12");

            // The type refuses to hand out a number for an absent figure, so a caller cannot render an
            // unpriced bucket as zero even by accident.
            assertThatThrownBy(row.revenue()::amount)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UNPRICED");
            assertThatThrownBy(row.margin()::amount)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UNPRICED");
        }

        @Test
        @DisplayName("a day with no usage is listed as a gap, never as a zero bucket")
        void aDayWithNoUsageIsAGap() {
            LocalDate priced = LocalDate.of(2026, 3, 10);
            LocalDate gap = LocalDate.of(2026, 3, 11);
            priceMessages(new BigDecimal("2.000000"));
            fixture.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, new BigDecimal("10"),
                FinanceFixture.inMarch(10, 9), new BigDecimal("4.000000"), "USD");
            compute(priced);

            FinanceUsageView view = view(LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 13));

            assertThat(view.dataState()).isEqualTo(FinanceUsageView.DataState.DATA);
            assertThat(view.buckets()).as("only the day with usage has a row").hasSize(1);
            assertThat(view.missingBuckets())
                .as("the gap is enumerated, so a client can render 'no data' rather than 0")
                .containsExactly(gap, LocalDate.of(2026, 3, 12), LocalDate.of(2026, 3, 13));
            assertThat(view.missingBuckets()).doesNotContain(priced);
        }

        @Test
        @DisplayName("a window with no usage at all is NO_DATA, with every amount absent")
        void anEmptyWindowIsNoData() {
            priceMessages(new BigDecimal("2.000000"));

            FinanceUsageView view = view(LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 12));

            assertThat(view.dataState()).isEqualTo(FinanceUsageView.DataState.NO_DATA);
            assertThat(view.buckets()).isEmpty();
            assertThat(view.missingBuckets())
                .containsExactly(LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 11),
                    LocalDate.of(2026, 3, 12));
            assertThat(view.totals().cost().state()).isEqualTo(AmountState.NO_DATA);
            assertThat(view.totals().revenue().state()).isEqualTo(AmountState.NO_DATA);
            assertThat(view.totals().margin().state()).isEqualTo(AmountState.NO_DATA);
            assertThat(view.totals().sourceEventCount()).isZero();
        }

        @Test
        @DisplayName("the four states are four different objects, not one number rendered four ways")
        void theFourStatesAreDistinct() {
            LocalDate pricedDay = LocalDate.of(2026, 3, 10);
            LocalDate zeroDay = LocalDate.of(2026, 3, 11);
            LocalDate unpricedDay = LocalDate.of(2026, 3, 12);

            // A rate effective for the 10th and the 11th but NOT the 12th, so the three days carry three
            // different pricing states in one window.
            UUID version = fixture.activeVersion(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, 1,
                "USD", new BigDecimal("2.000000"), LocalDate.of(2026, 3, 10),
                LocalDate.of(2026, 3, 11), "two days only", FinanceFixture.ADMIN_A);
            fixture.line(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, version, "MESSAGE", "message",
                null, new BigDecimal("2.000000"), "USD");

            fixture.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, new BigDecimal("10"),
                FinanceFixture.inMarch(10, 9), new BigDecimal("4.000000"), "USD");
            fixture.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, new BigDecimal("1"),
                FinanceFixture.inMarch(11, 9), BigDecimal.ZERO, "USD");
            fixture.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, new BigDecimal("10"),
                FinanceFixture.inMarch(12, 9), new BigDecimal("4.000000"), "USD");

            compute(LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 12));

            FinanceUsageView view = view(pricedDay, unpricedDay);
            assertThat(view.buckets()).hasSize(3);

            FinanceUsageView.BucketRow priced = rowFor(view, pricedDay);
            FinanceUsageView.BucketRow zero = rowFor(view, zeroDay);
            FinanceUsageView.BucketRow unpriced = rowFor(view, unpricedDay);

            assertThat(priced.revenue().state()).isEqualTo(AmountState.PRICED_NONZERO);
            assertThat(zero.revenue().state()).isEqualTo(AmountState.PRICED_ZERO);
            assertThat(unpriced.revenue().state()).isEqualTo(AmountState.UNPRICED);
            assertThat(AmountState.values()).as("four states, and NO_DATA is the fourth")
                .hasSize(4);
            assertThat(view.dataState()).isEqualTo(FinanceUsageView.DataState.DATA);

            // The window as a whole. MESSAGE is priced on two of its three days, so it is counted in
            // BOTH the priced and the unpriced dimension lists — and because something in the window is
            // unpriced, the revenue total is not reported as complete.
            assertThat(view.totals().pricedDimensionCount()).isEqualTo(1);
            assertThat(view.totals().unpricedDimensionCount())
                .as("a partially priced dimension counts as unpriced too")
                .isEqualTo(1);
            assertThat(view.totals().revenueIsComplete()).isFalse();
            assertThat(view.unpriced().bucketCount()).isEqualTo(1);
            assertThat(view.unpriced().buckets().get(0).bucketStart()).isEqualTo(unpricedDay);
            assertThat(view.unpriced().reason())
                .contains("usage exists with no agreed price");
        }
    }

    // ==================================================================
    // totals and margin
    // ==================================================================

    @Nested
    @DisplayName("revenue and margin are derived, and pinned")
    class Derived {

        @Test
        @DisplayName("revenue equals cost times the pinned markup, to six places")
        void revenueEqualsCostTimesThePinnedMarkup() {
            LocalDate day = LocalDate.of(2026, 3, 10);
            // 1.333333 markup on 3.000000 cost is 3.999999, which is the case where a float and a
            // round-half-up disagree, so the six-place arithmetic is actually being checked.
            priceMessages(new BigDecimal("1.333333"));
            fixture.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, new BigDecimal("1"),
                FinanceFixture.inMarch(10, 9), new BigDecimal("3.000000"), "USD");
            compute(day);

            FinanceUsageView.BucketRow row = messageRow(day);

            assertThat(row.revenue().amount())
                .isEqualByComparingTo(row.cost().amount()
                    .multiply(new BigDecimal("1.333333")).setScale(6,
                        java.math.RoundingMode.HALF_UP));
            assertThat(row.margin().amount())
                .isEqualByComparingTo(row.revenue().amount().subtract(row.cost().amount()));
        }

        @Test
        @DisplayName("a margin below zero is reported as a loss, not clamped")
        void aNegativeMarginIsReported() {
            // A markup below 1 is arithmetically valid and commercially alarming. Clamping it to zero
            // is how a losing month reads as break-even.
            LocalDate day = LocalDate.of(2026, 3, 10);
            priceMessages(new BigDecimal("0.500000"));
            fixture.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, new BigDecimal("1"),
                FinanceFixture.inMarch(10, 9), new BigDecimal("4.000000"), "USD");
            compute(day);

            FinanceUsageView.BucketRow row = messageRow(day);

            assertThat(row.revenue().amount()).isEqualByComparingTo("2.000000");
            assertThat(row.margin().amount()).isEqualByComparingTo("-2.000000");
            assertThat(row.margin().isNegative()).isTrue();
        }

        @Test
        @DisplayName("a window mixing priced and unpriced dimensions reports revenue as incomplete")
        void aMixedWindowReportsIncompleteRevenue() {
            // MESSAGE is priced; AI_TOKEN has usage and no rate. A single revenue total over the two
            // would be a partial sum presented as a total.
            priceMessages(new BigDecimal("2.000000"));
            fixture.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, new BigDecimal("10"),
                FinanceFixture.inMarch(10, 9), new BigDecimal("4.000000"), "USD");
            fixture.aiToken(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, new BigDecimal("5000"),
                FinanceFixture.inMarch(10, 9), null, "USD");
            compute(LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 10));

            FinanceUsageView view = view(LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 10));

            assertThat(view.buckets()).hasSize(2);
            assertThat(view.totals().revenueIsComplete()).isFalse();
            assertThat(view.totals().revenue().state())
                .as("a partial sum is not a finished revenue, and must not read as one")
                .isEqualTo(AmountState.UNPRICED);
            assertThat(view.totals().margin().state()).isEqualTo(AmountState.UNPRICED);
            assertThat(view.totals().unpricedDimensionCount()).isEqualTo(1);
            assertThat(view.totals().hasUnpriced()).isTrue();
            assertThat(view.totals().revenueIsComplete()).isFalse();
        }

        @Test
        @DisplayName("a fully priced window reports complete revenue")
        void aFullyPricedWindowReportsCompleteRevenue() {
            LocalDate day = LocalDate.of(2026, 3, 10);
            priceMessages(new BigDecimal("2.000000"));
            fixture.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, new BigDecimal("10"),
                FinanceFixture.inMarch(10, 9), new BigDecimal("4.000000"), "USD");
            compute(day);

            FinanceUsageView view = view(day, day);

            assertThat(view.totals().revenueIsComplete()).isTrue();
            assertThat(view.totals().revenue().state()).isEqualTo(AmountState.PRICED_NONZERO);
            assertThat(view.totals().margin().state()).isEqualTo(AmountState.PRICED_NONZERO);
            assertThat(view.totals().unpricedDimensionCount()).isZero();
            assertThat(view.unpriced().bucketCount()).isZero();
        }
    }

    // ==================================================================
    // forecast
    // ==================================================================

    @Nested
    @DisplayName("a forecast is an extrapolation, and says so")
    class Forecast {

        @Test
        @DisplayName("a priced window projects the rest of the period and names its method")
        void aPricedWindowProjects() {
            LocalDate periodStart = LocalDate.of(2026, 3, 1);
            LocalDate periodEnd = LocalDate.of(2026, 3, 31);
            LocalDate asOf = LocalDate.of(2026, 3, 10);
            priceMessagesFrom(new BigDecimal("2.000000"), periodStart, periodEnd);
            // Four days at 4.000000/day.
            for (int day = 1; day <= 4; day++) {
                fixture.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A,
                    new BigDecimal("10"), periodStart.plusDays(day - 1).atTime(9, 0),
                    new BigDecimal("4.000000"), "USD");
            }
            compute(periodStart, asOf);

            FinanceForecast forecast = FinancePrincipalScope.call(adminA,
                () -> forecasts.forecast(adminA, FinanceFixture.PROJECT_A, periodStart, periodEnd,
                    asOf));

            assertThat(forecast.observedDays()).isEqualTo(10);
            assertThat(forecast.remainingDays()).isEqualTo(21);
            assertThat(forecast.confidence()).isEqualTo(FinanceForecast.Confidence.REASONABLE);
            assertThat(forecast.method())
                .as("the arithmetic is stated so a reader can redo it")
                .contains("observedDays")
                .contains("remainingDays");
            assertThat(forecast.dimensions()).hasSize(1);

            FinanceForecast.ProjectedDimension message = forecast.dimensions().get(0);
            assertThat(message.dimension()).isEqualTo("MESSAGE");
            assertThat(message.observedCost()).isEqualByComparingTo("16.000000");
            assertThat(message.isPriced()).isTrue();
            // 16.000000 + (16/10 * 21) = 49.600000
            assertThat(message.projectedCost().amount()).isEqualByComparingTo("49.600000");
            assertThat(message.projectedRevenue().amount()).isEqualByComparingTo("99.200000");
            assertThat(message.projectedMargin().amount()).isEqualByComparingTo("49.600000");
            assertThat(message.rateCardVersionNumber()).isNotNull();
        }

        @Test
        @DisplayName("an unpriced dimension is excluded from the revenue projection and disclosed")
        void anUnpricedDimensionIsExcludedFromTheProjection() {
            LocalDate periodStart = LocalDate.of(2026, 3, 1);
            LocalDate periodEnd = LocalDate.of(2026, 3, 31);
            LocalDate asOf = LocalDate.of(2026, 3, 10);
            fixture.aiToken(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, new BigDecimal("5000"),
                FinanceFixture.inMarch(10, 9), null, "USD");
            compute(periodStart, asOf);

            FinanceForecast forecast = FinancePrincipalScope.call(adminA,
                () -> forecasts.forecast(adminA, FinanceFixture.PROJECT_A, periodStart, periodEnd,
                    asOf));

            FinanceForecast.ProjectedDimension tokens = forecast.dimensions().stream()
                .filter(dimension -> dimension.dimension().equals("AI_TOKEN"))
                .findFirst()
                .orElseThrow();

            assertThat(tokens.isPriced()).isFalse();
            assertThat(tokens.projectedRevenue().state()).isEqualTo(AmountState.UNPRICED);
            assertThatThrownBy(tokens.projectedRevenue()::amount)
                .as("a forecast must not invent a price for unpriced usage")
                .isInstanceOf(IllegalStateException.class);
            assertThat(forecast.hasUnpriced()).isTrue();
            assertThat(forecast.unpricedDimensions())
                .extracting(FinanceForecast.ProjectedDimension::dimension)
                .containsExactly("AI_TOKEN");
        }

        @Test
        @DisplayName("fewer than two observed days carries no confidence")
        void oneObservedDayCarriesNoConfidence() {
            LocalDate periodStart = LocalDate.of(2026, 3, 1);
            LocalDate periodEnd = LocalDate.of(2026, 3, 31);
            LocalDate asOf = LocalDate.of(2026, 3, 1);
            priceMessagesFrom(new BigDecimal("2.000000"), periodStart, periodEnd);
            fixture.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, new BigDecimal("10"),
                FinanceFixture.inMarch(1, 9), new BigDecimal("4.000000"), "USD");
            compute(periodStart, asOf);

            FinanceForecast forecast = FinancePrincipalScope.call(adminA,
                () -> forecasts.forecast(adminA, FinanceFixture.PROJECT_A, periodStart, periodEnd,
                    asOf));

            assertThat(forecast.confidence())
                .as("a single point is not a trend")
                .isEqualTo(FinanceForecast.Confidence.NONE);
        }

        @Test
        @DisplayName("an asOf outside the period is refused")
        void anAsOfOutsideThePeriodIsRefused() {
            LocalDate periodStart = LocalDate.of(2026, 3, 1);
            LocalDate periodEnd = LocalDate.of(2026, 3, 31);

            assertThatThrownBy(() -> FinancePrincipalScope.run(adminA,
                () -> forecasts.forecast(adminA, FinanceFixture.PROJECT_A, periodStart, periodEnd,
                    LocalDate.of(2026, 2, 28))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the period");
        }
    }

    // ==================================================================
    // helpers
    // ==================================================================

    /** An ACTIVE MESSAGE rate covering the whole of March. */
    private void priceMessages(BigDecimal markup) {
        priceMessagesFrom(markup, FinanceFixture.MARCH_1, FinanceFixture.MARCH_31);
    }

    /** An ACTIVE MESSAGE rate covering an explicit range. */
    private void priceMessagesFrom(BigDecimal markup, LocalDate from, LocalDate to) {
        UUID version = fixture.activeVersion(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, 1, "USD",
            markup, from, to, "seeded rate", FinanceFixture.ADMIN_A);
        fixture.line(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, version, "MESSAGE", "message",
            null, markup, "USD");
    }

    private void compute(LocalDate day) {
        compute(day, day);
    }

    private void compute(LocalDate from, LocalDate to) {
        FinancePrincipalScope.run(adminA, () -> rollups.computeDaily(adminA, FinanceFixture.PROJECT_A,
            from, to, LedgerPrincipal.job("test-rollup"), "corr-read-model"));
    }

    private FinanceUsageView view(LocalDate from, LocalDate to) {
        return FinancePrincipalScope.call(adminA,
            () -> usage.usage(adminA, FinanceFixture.PROJECT_A, RollupBucket.Grain.DAILY, from, to));
    }

    private FinanceUsageView.BucketRow messageRow(LocalDate day) {
        return rowFor(view(day, day), day);
    }

    private FinanceUsageView.BucketRow rowFor(FinanceUsageView view, LocalDate day) {
        return view.buckets().stream()
            .filter(bucket -> bucket.dimension() == UsageDimension.MESSAGE)
            .filter(bucket -> bucket.bucketStart().equals(day))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no MESSAGE bucket on " + day));
    }
}
