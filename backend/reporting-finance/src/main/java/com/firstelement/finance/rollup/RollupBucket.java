package com.firstelement.finance.rollup;

import com.firstelement.finance.catalog.UsageDimension;
import com.firstelement.finance.money.Money;
import com.firstelement.finance.rates.EffectiveRate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One computed rollup bucket: usage, cost, derived revenue, derived margin, and what is still unpriced.
 *
 * <h2>Every amount here has a currency and a state</h2>
 *
 * <p>There is no {@code BigDecimal} getter without a currency and no {@code null} without a state that
 * says which of the three absent states it is. A read model that returned {@code revenueAmount} as a
 * bare number would have to invent a currency and would have to guess what a null meant, which is the
 * guess that turns unpriced usage into zero.
 *
 * <h2>The four states, and which is which</h2>
 *
 * <table>
 *   <caption>How a bucket's pricing state arises</caption>
 *   <tr><th>Situation</th><th>{@link #state()}</th><th>cost</th><th>revenue</th><th>margin</th></tr>
 *   <tr><td>Usage exists and a rate was effective</td><td>{@link RollupState#PRICED}</td>
 *       <td>sum</td><td>{@code cost × markup}</td><td>{@code revenue − cost}</td></tr>
 *   <tr><td>Usage exists, no rate effective</td><td>{@link RollupState#UNPRICED}</td>
 *       <td>sum</td><td>absent</td><td>absent</td></tr>
 *   <tr><td>A rate applied and the cost summed to exactly zero</td>
 *       <td>{@link RollupState#PRICED}</td><td>zero</td><td>zero</td><td>zero</td></tr>
 *   <tr><td>No usage events at all</td><td>no row exists</td><td>—</td><td>—</td><td>—</td></tr>
 * </table>
 *
 * <p>The last row is the one that cannot be represented by a field, and that is deliberate:
 * {@code usage_rollup_snapshots_source_count_check} requires {@code sourceEventCount >= 1}, so a
 * bucket with no usage is never materialised. "No data" is the absence of a row, and a read model
 * that filled the gap with a zero row would have destroyed the distinction at the storage layer.
 *
 * <h2>What the two unpriced columns mean, per state</h2>
 *
 * <p>They are not a second copy of {@code quantity} and {@code cost}. They are what is <em>still</em>
 * unpriced, and that is a different quantity in each of the two states a materialised bucket can have:
 *
 * <table>
 *   <caption>unpricedQuantity / unpricedCostAmount by state</caption>
 *   <tr><th>{@link #state()}</th><th>{@code unpricedQuantity}</th><th>{@code unpricedCost}</th></tr>
 *   <tr><td>{@link RollupState#UNPRICED}</td><td>the whole bucket</td>
 *       <td>the whole bucket's cost — usage exists and nobody agreed what it is worth</td></tr>
 *   <tr><td>{@link RollupState#PRICED}</td>
 *       <td>only usage whose events recorded <em>no measured cost</em>
 *           ({@link #hasUnpricedResidue()})</td>
 *       <td>zero by construction, and derived from the ledger rather than assumed</td></tr>
 * </table>
 *
 * <p>The priced row's cost half being zero is arithmetic, not convention: the events that make up the
 * residue are precisely the ones with a {@code NULL costAmount}, and the sum of their costs is the sum
 * of no rows. It is computed as
 * {@code sum("costAmount") filter (where "costAmount" is null)} in
 * {@link RollupRepository#ledgerAggregateByDay} rather than written as a literal, so the cost half and
 * the quantity half are derived from one predicate and cannot disagree about which events are
 * uncosted.
 *
 * <p><b>Why this is stated so loudly.</b> A priced row recording its own cost in the unpriced columns
 * reports the same money as billed and as unbilled at once. {@code usage_rollup_snapshots_pricing_pin_check}
 * cannot catch it — it ties the three <em>pricing</em> columns, and a row with all three present is
 * correctly priced by construction — so the only defence is the writer passing a residue rather than a
 * cost, and a reader that sums the column only over unpriced rows.
 */
public record RollupBucket(
        UUID snapshotId,
        UUID organizationId,
        UUID projectId,
        UUID periodId,
        Grain grain,
        LocalDate bucketStart,
        LocalDate bucketEnd,
        UsageDimension dimension,
        String unit,
        String currency,
        long sourceEventCount,
        BigDecimal quantity,
        Money cost,
        Money revenue,
        Money margin,
        BigDecimal markupRate,
        UUID rateCardVersionId,
        Integer rateCardVersionNumber,
        BigDecimal unpricedQuantity,
        BigDecimal unpricedCost,
        int generation,
        UUID supersedesSnapshotId,
        java.time.LocalDateTime computedAt,
        String computedByPrincipal,
        String correlationId
) {

    /** Whether the bucket is daily or monthly. Mirrors {@code RollupGrain}. */
    public enum Grain {
        DAILY,
        MONTHLY;

        public String wireValue() {
            return name();
        }
    }

    /** The pricing state of the whole bucket. */
    public enum RollupState {
        /** A rate was effective and applied to every contributing event. */
        PRICED,
        /** Usage exists with no rate effective on the usage date. */
        UNPRICED
    }

    /**
     * Whether a rate priced this bucket.
     *
     * <p>Distinct from {@link #revenue()} being non-null for exactly one reason: a rate can be pinned
     * and still produce no revenue, which the schema forbids
     * ({@code usage_rollup_snapshots_pricing_pin_check} ties the three pricing columns together), so
     * the two are equivalent and the method exists so a caller reads the state rather than the amount.
     */
    public RollupState state() {
        return rateCardVersionId == null ? RollupState.UNPRICED : RollupState.PRICED;
    }

    /** Whether a rate priced this bucket. */
    public boolean isPriced() {
        return state() == RollupState.PRICED;
    }

    /** Whether this bucket is unpriced. */
    public boolean isUnpriced() {
        return state() == RollupState.UNPRICED;
    }

    /**
     * The part of this bucket that no rate and no measured cost reached.
     *
     * <p>Named rather than passed as two adjacent {@link BigDecimal} arguments, because two same-typed
     * arguments in a row are transposable by a caller and a transposition here is not a compile error —
     * it is a finance figure. {@code RollupService} built this bucket with the bucket's cost in the
     * unpriced-cost slot, which is why {@link UnpricedResidue} exists.
     *
     * @param quantity usage whose events recorded no measured cost
     * @param cost     the cost of exactly those events; zero when {@code quantity} came from
     *                 {@code NULL}-cost events, and the whole bucket's cost when the bucket has no rate
     */
    public record UnpricedResidue(BigDecimal quantity, BigDecimal cost) {

        /** No residue at all: a rate priced everything and every event carried a measured cost. */
        public static UnpricedResidue none() {
            return new UnpricedResidue(BigDecimal.ZERO.setScale(6), BigDecimal.ZERO.setScale(6));
        }

        /** Whether nothing is left unpriced. */
        public boolean isEmpty() {
            return quantity == null || quantity.signum() == 0;
        }
    }

    /**
     * Whether some of the bucket's usage could not be priced.
     *
     * <p>A real state that the three-state model alone does not cover: a rate card that prices a
     * dimension with a <em>markup</em> but no per-unit cost will mark up the costs that were recorded
     * and leave the events that had none unpriced. Reporting that bucket as plainly "priced" would
     * understate how much of it is still a guess, so it is surfaced explicitly.
     *
     * <p>True for an {@link RollupState#UNPRICED} bucket too, and necessarily so: an unpriced bucket
     * has the whole of itself in this state, which is why the two states cannot be told apart by this
     * predicate alone and must be read from {@link #state()}.
     */
    public boolean hasUnpricedResidue() {
        return unpricedQuantity != null && unpricedQuantity.signum() > 0;
    }

    /** The residue as one value, for a reader that wants to pass it on rather than read two fields. */
    public UnpricedResidue residue() {
        return new UnpricedResidue(unpricedQuantity, unpricedCost);
    }

    /**
     * Whether this bucket is the current generation.
     *
     * <p>Snapshots are append-only: a recomputation is a NEW row with the next {@code generation} naming
     * what it replaces. So "current" means "nobody supersedes me", and a superseded row is history that a
     * drill-through may still show.
     *
     * <p><b>Not derivable from {@code supersedesSnapshotId == null}.</b>
     * {@code usage_rollup_snapshots_generation_pair_check} ties {@code generation = 1} to
     * {@code supersedesSnapshotId IS NULL}, so a null pointer means <em>first</em>, and the current
     * generation is the one that is <em>pointed at</em>. A freshly computed bucket is therefore not
     * "current" by that test, which is the same inverted predicate the schema's
     * {@code usage_rollup_snapshots_current_bucket_key} partial index uses; see
     * {@link RollupRepository} and the report to the orchestrator.
     */
    public boolean isCurrentGeneration() {
        return generation >= 1 && supersedesSnapshotId != null || generation == 1;
    }

    /** The rate definition this bucket was priced with, for a read model's provenance line. */
    public String rateDefinitionLabel() {
        if (rateCardVersionId == null) {
            return "unpriced: no rate-card version was effective on " + bucketStart;
        }
        return "rate-card-version " + rateCardVersionNumber + " (markup " + markupRate.toPlainString()
            + " pinned at computation time)";
    }

    /**
     * Builds a priced bucket from a snapshot row and its resolved rate.
     *
     * <p>The residue is a {@link UnpricedResidue} and not two amounts, and the caller builds it from the
     * ledger's uncosted aggregate — never from this bucket's own cost. A priced bucket has had its
     * <em>whole</em> cost multiplied by the markup ({@code usage_rollup_snapshots_derivation_check}
     * applies to {@code costAmount} in full), so none of that cost is unpriced and passing any of it
     * here would report billed money as unbilled.
     */
    static RollupBucket priced(java.util.UUID snapshotId, java.util.UUID organizationId,
                               java.util.UUID projectId, java.util.UUID periodId, Grain grain,
                               LocalDate bucketStart, LocalDate bucketEnd,
                               UsageDimension dimension, String unit, String currency,
                               long sourceEventCount, BigDecimal quantity, BigDecimal costAmount,
                               EffectiveRate rate, UnpricedResidue residue, int generation,
                               java.util.UUID supersedesSnapshotId, java.time.LocalDateTime computedAt,
                               String computedByPrincipal, String correlationId) {
        Money cost = Money.of(costAmount, currency);
        Money revenue = cost.multipliedBy(rate.markupRate());
        Money margin = cost.marginOf(revenue);
        UnpricedResidue recorded = residue == null ? UnpricedResidue.none() : residue;
        return new RollupBucket(snapshotId, organizationId, projectId, periodId, grain, bucketStart,
            bucketEnd, dimension, unit, currency, sourceEventCount, quantity, cost, revenue, margin,
            rate.markupRate(), rate.rateCardVersionId(), rate.versionNumber(), recorded.quantity(),
            recorded.cost(), generation, supersedesSnapshotId, computedAt, computedByPrincipal,
            correlationId);
    }

    /**
     * Builds an unpriced bucket: usage and cost are real, revenue and margin are absent.
     *
     * <p>The whole bucket is the residue, which is what {@code usage_rollup_snapshots_unpriced_pair_check}
     * requires: a row with no rate must carry a positive unpriced quantity or a positive unpriced cost,
     * or "unpriced" would be a state the module can reach by not doing the work. Passing the bucket's own
     * quantity and cost here is therefore correct — and it is the same pair, in the same order, that a
     * <em>priced</em> bucket must not be given, which is why {@link #priced} takes a residue object.
     */
    static RollupBucket unpriced(java.util.UUID snapshotId, java.util.UUID organizationId,
                                 java.util.UUID projectId, java.util.UUID periodId, Grain grain,
                                 LocalDate bucketStart, LocalDate bucketEnd,
                                 UsageDimension dimension, String unit, String currency,
                                 long sourceEventCount, BigDecimal quantity, BigDecimal costAmount,
                                 int generation, java.util.UUID supersedesSnapshotId,
                                 java.time.LocalDateTime computedAt,
                                 String computedByPrincipal, String correlationId) {
        return new RollupBucket(snapshotId, organizationId, projectId, periodId, grain, bucketStart,
            bucketEnd, dimension, unit, currency, sourceEventCount, quantity,
            Money.of(costAmount, currency), Money.unpriced(currency), Money.unpriced(currency),
            null, null, null, quantity, costAmount, generation, supersedesSnapshotId, computedAt,
            computedByPrincipal, correlationId);
    }
}
