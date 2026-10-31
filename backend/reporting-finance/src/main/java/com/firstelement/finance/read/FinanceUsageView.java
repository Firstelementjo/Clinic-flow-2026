package com.firstelement.finance.read;

import com.firstelement.finance.catalog.UsageDimension;
import com.firstelement.finance.money.AmountState;
import com.firstelement.finance.money.Money;
import com.firstelement.finance.rollup.RollupBucket;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The daily/monthly read model: usage, cost, derived revenue, derived margin, and the unpriced view.
 *
 * <h2>What a reader must be able to tell apart</h2>
 *
 * <table>
 *   <caption>The three states, and where each one comes from</caption>
 *   <tr><th>Situation</th><th>cost</th><th>revenue</th><th>margin</th><th>Where it shows up</th></tr>
 *   <tr><td>Usage exists, rate effective</td><td>number</td><td>number</td><td>number</td>
 *       <td>a bucket row</td></tr>
 *   <tr><td>Usage exists, no rate</td><td>number</td>
 *       <td>{@link AmountState#UNPRICED}</td><td>{@link AmountState#UNPRICED}</td>
 *       <td>a bucket row, and the unpriced list</td></tr>
 *   <tr><td>Rate effective, cost summed to zero</td>
 *       <td>{@link AmountState#PRICED_ZERO}</td><td>{@link AmountState#PRICED_ZERO}</td>
 *       <td>{@link AmountState#PRICED_ZERO}</td><td>a bucket row</td></tr>
 *   <tr><td>No usage in the bucket</td><td>{@link AmountState#NO_DATA}</td>
 *       <td>{@link AmountState#NO_DATA}</td><td>{@link AmountState#NO_DATA}</td>
 *       <td>the missing-bucket list, never a row</td></tr>
 * </table>
 *
 * <h2>Why the missing-bucket list exists</h2>
 *
 * <p>Because a dashboard that renders an empty cell for a day with no usage, and one that renders
 * {@code 0}, are the same picture — and ADR 0007 rule 5 says that is a defect. So the read model
 * enumerates the buckets in the requested range that have no row, explicitly, as
 * {@link #missingBuckets()}. A client can then render "no data" for a day and a real zero for another,
 * which is the only way the two remain distinguishable once they leave the API.
 *
 * <h2>Why margin is absent on an unpriced bucket</h2>
 *
 * <p>ADR 0007: "Margin: revenue − cost, <b>only where a rate exists</b>; derived; never displayed where
 * the row is unpriced." So {@link #margin()} carries {@link AmountState#UNPRICED} and there is no
 * {@code null}-means-zero path: {@link Money#amount()} throws for an absent state, so a caller cannot
 * render a margin for an unpriced bucket even by accident.
 */
public record FinanceUsageView(
        UUID organizationId,
        UUID projectId,
        RollupBucket.Grain grain,
        LocalDate fromDate,
        LocalDate toDate,
        String currency,
        DataState dataState,
        Totals totals,
        Unpriced unpriced,
        List<BucketRow> buckets,
        List<LocalDate> missingBuckets,
        String note
) {

    public FinanceUsageView {
        buckets = buckets == null ? List.of() : List.copyOf(buckets);
        missingBuckets = missingBuckets == null ? List.of() : List.copyOf(missingBuckets);
    }

    /**
     * Whether the whole window has any usage at all.
     *
     * <p>{@code NO_DATA} is not "zero" and is never rendered as it. The distinction is between "we
     * recorded nothing" and "we recorded something that cost nothing", and the two are separated here
     * as well as per bucket.
     */
    public enum DataState {
        /** At least one usage event contributed to the window. */
        DATA,
        /** No usage event was recorded anywhere in the window. */
        NO_DATA
    }

    /**
     * The window's totals.
     *
     * <p>Each total carries its own {@link AmountState} rather than one for the whole window, because
     * a window can hold a priced dimension and an unpriced one simultaneously — and a single nullable
     * revenue would have to pick one.
     */
    public record Totals(
            long sourceEventCount,
            BigDecimal quantity,
            Money cost,
            Money revenue,
            Money margin,
            int pricedDimensionCount,
            int unpricedDimensionCount
    ) {

        /** Whether any part of the window is unpriced. The unpriced view is not empty. */
        public boolean hasUnpriced() {
            return unpricedDimensionCount > 0;
        }

        /**
         * Whether revenue is complete.
         *
         * <p>False when any dimension is unpriced, which is what stops a caller from treating the
         * revenue total as a finished figure. A partial sum presented as a total is how unpriced usage
         * quietly becomes zero.
         */
        public boolean revenueIsComplete() {
            return unpricedDimensionCount == 0;
        }
    }

    /**
     * The unpriced view.
     *
     * <p>First-class and separate from the totals, because ADR 0007 requires unpriced usage to be a
     * view of its own rather than an error state or a footnote. {@code reason} names which of the two
     * unpriced causes applies — no rate card at all, or a rate that did not cover the whole bucket —
     * because the two have different fixes.
     *
     * <p>Over the buckets with no rate at all, which is the only set whose exposure to unbilled money is
     * the whole of it:
     *
     * @param bucketCount how many buckets have no rate
     * @param quantity    their unpriced usage, in each row's own dimension unit — a count, never money
     * @param cost        their unpriced <em>money</em>: the sum of {@code unpricedCostAmount}, in the
     *                    view's currency. NO_DATA when the window holds no bucket at all, so an empty
     *                    window does not report an unbilled figure of zero.
     */
    public record Unpriced(
            int bucketCount,
            BigDecimal quantity,
            Money cost,
            List<BucketRow> buckets,
            String reason
    ) {
    }

    /**
     * One bucket as a reader sees it.
     *
     * <p>{@code unpricedQuantity} and {@code unpricedCost} are what is <em>still</em> unpriced on this
     * bucket, read straight off the snapshot row. On an unpriced row that is the whole bucket — usage in
     * its own unit and money in this row's currency. On a priced row it is only the residue
     * ({@link #hasUnpricedResidue()}), and its cost is zero, because a rate priced this bucket's cost in
     * full. A priced row whose unpriced cost equalled its cost would be reporting the same money as
     * billed and as unbilled.
     */
    public record BucketRow(
            UUID snapshotId,
            LocalDate bucketStart,
            LocalDate bucketEnd,
            UsageDimension dimension,
            String unit,
            long sourceEventCount,
            BigDecimal quantity,
            Money cost,
            Money revenue,
            Money margin,
            RollupBucket.RollupState pricingState,
            BigDecimal markupRate,
            UUID rateCardVersionId,
            Integer rateCardVersionNumber,
            BigDecimal unpricedQuantity,
            BigDecimal unpricedCost,
            int generation,
            boolean currentGeneration,
            String rateDefinition
    ) {

        /** Whether a rate priced this bucket. */
        public boolean isPriced() {
            return pricingState == RollupBucket.RollupState.PRICED;
        }

        /**
         * Whether some of this bucket's usage remained unpriced even though a rate applied.
         *
         * <p>A rate card that marks up costs but has no per-unit cost price will mark up the costs it
         * was given and leave the costless events unpriced. Reporting that bucket as plainly priced
         * would understate how much of it is still an estimate.
         */
        public boolean hasUnpricedResidue() {
            return unpricedQuantity != null && unpricedQuantity.signum() > 0;
        }
    }
}
