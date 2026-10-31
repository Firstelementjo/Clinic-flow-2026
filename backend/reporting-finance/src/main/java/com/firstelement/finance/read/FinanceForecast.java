package com.firstelement.finance.read;

import com.firstelement.finance.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A run-rate projection for the rest of an open period.
 *
 * <h2>Why a forecast is not a finance total</h2>
 *
 * <p>Because it is an extrapolation from observed cost, and ADR 0007 is about figures that resolve to
 * immutable usage events. A forecast resolves to a window of events and an arithmetic method, not to a
 * set of events — so it lives in its own type, carries its own {@link Confidence}, and is never summed
 * into a total. There is no forecast column anywhere in the schema, and adding one would be the same
 * defect as storing revenue on the event.
 *
 * <h2>The method, stated so it can be checked</h2>
 *
 * <p>{@code projected = observed + (observed / observedDays) × remainingDays}, per dimension, over the
 * buckets that already have a computed snapshot. Both inputs are visible on the record
 * ({@link #observedDays()} and {@link #remainingDays()}), so a reader can redo the arithmetic rather
 * than trust it.
 *
 * <h2>What it refuses to do</h2>
 *
 * <ul>
 *   <li><b>It never prices anything.</b> An unpriced dimension contributes to {@link #unpriced()} and
 *       nowhere else. Projecting a revenue for usage with no rate would be inventing a price, which is
 *       the one thing unpriced usage exists to prevent.</li>
 *   <li><b>It never crosses a currency.</b> Per dimension per currency; there is no implicit rate.</li>
 *   <li><b>It says how much it trusts itself.</b> {@link Confidence#NONE} when there are fewer than two
 *       observed days, because a single day's usage projected across a month is not a forecast, it is
 *       an extrapolation of one point.</li>
 * </ul>
 */
public record FinanceForecast(
        LocalDate periodStart,
        LocalDate periodEnd,
        int observedDays,
        int remainingDays,
        String currency,
        List<ProjectedDimension> dimensions,
        Money unpricedCost,
        Confidence confidence,
        String method,
        String note
) {

    public FinanceForecast {
        dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
    }

    /** How much weight the projection deserves. Derived, never claimed. */
    public enum Confidence {
        /** Fewer than two observed days: one point is not a trend. */
        NONE,
        /** Two to six observed days. */
        LOW,
        /** Seven or more observed days within the same period. */
        REASONABLE
    }

    /** One dimension's projection. */
    public record ProjectedDimension(
            String dimension,
            String unit,
            String currency,
            BigDecimal observedQuantity,
            BigDecimal observedCost,
            Money projectedCost,
            Money projectedRevenue,
            Money projectedMargin,
            UUID rateCardVersionId,
            Integer rateCardVersionNumber,
            BigDecimal markupRate
    ) {

        /**
         * Whether this projection is priced.
         *
         * <p>False means the dimension had usage and no rate on the observed dates, so no revenue can
         * be projected — and the read model shows it in the unpriced column instead.
         */
        public boolean isPriced() {
            return rateCardVersionId != null && projectedRevenue().isPriced();
        }
    }

    /** The method text, carried on the record so a reader never has to guess. */
    public static final String DEFAULT_METHOD =
        "projected = observed + (observed / observedDays) x remainingDays, per dimension and currency, "
            + "over the buckets that already have a computed snapshot. Unpriced dimensions are excluded "
            + "from the revenue projection and reported separately.";

    /**
     * Projects one dimension.
     *
     * @param observedQuantity  usage observed so far in the period
     * @param observedCost      cost observed so far, which must already be priced
     * @param dailyCost         {@code observedCost / observedDays}, or null when fewer than one day
     * @param remainingDays     days left in the period
     * @param rateCardVersionId the pinned rate, or null when the dimension is unpriced
     */
    public static ProjectedDimension project(String dimension, String unit, String currency,
                                             BigDecimal observedQuantity, BigDecimal observedCost,
                                             BigDecimal dailyCost, int remainingDays,
                                             UUID rateCardVersionId, Integer versionNumber,
                                             BigDecimal markupRate) {
        if (rateCardVersionId == null) {
            return new ProjectedDimension(dimension, unit, currency, observedQuantity, observedCost,
                Money.unpriced(currency), Money.unpriced(currency), Money.unpriced(currency), null,
                null, null);
        }
        BigDecimal projected = observedCost.add(
            dailyCost.multiply(BigDecimal.valueOf(remainingDays))).setScale(Money.SCALE,
                RoundingMode.HALF_UP);
        Money projectedCost = Money.of(projected, currency);
        Money revenue = projectedCost.multipliedBy(markupRate);
        Money margin = projectedCost.marginOf(revenue);
        return new ProjectedDimension(dimension, unit, currency, observedQuantity, observedCost,
            projectedCost, revenue, margin, rateCardVersionId, versionNumber, markupRate);
    }

    /** The confidence implied by an observed-day count. */
    public static Confidence confidenceFor(int observedDays) {
        if (observedDays < 2) {
            return Confidence.NONE;
        }
        return observedDays < 7 ? Confidence.LOW : Confidence.REASONABLE;
    }

    /**
     * Whether any part of the period is still unpriced, which the projection must disclose.
     *
     * <p>Keyed off the dimensions rather than off {@code unpricedCost}, because unpriced usage frequently
     * has a cost of zero — a token count with no price and no measured cost — and an amount-based test
     * would then report "nothing unpriced" for a window that is entirely unpriced. The dimensions are
     * the thing that carries the state.
     */
    public boolean hasUnpriced() {
        return dimensions.stream().anyMatch(dimension -> !dimension.isPriced());
    }

    /** The dimensions that could not be projected, which a reader must see beside the projection. */
    public List<ProjectedDimension> unpricedDimensions() {
        return dimensions.stream().filter(dimension -> !dimension.isPriced()).toList();
    }
}
