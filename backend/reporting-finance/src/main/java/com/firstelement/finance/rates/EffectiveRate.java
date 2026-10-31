package com.firstelement.finance.rates;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A {@link RateCardVersionLine} resolved for one usage event: the line's own values, or the version's
 * defaults where the line has none, plus the provenance a drill-through needs.
 *
 * <h2>Why this is a separate type from the line</h2>
 *
 * <p>Because "the line as stored" and "the rate that will be applied" are different questions, and
 * conflating them produces a specific bug: a line with {@code markupRate = null} applied as a
 * multiplier of zero gives revenue of zero, which is a <em>priced zero</em> — a rate that applied and
 * produced nothing — rather than the unpriced state it should be. {@link #markupRate()} here is
 * already resolved against the version default, so that failure cannot occur.
 *
 * <h2>What a drill-through gets</h2>
 *
 * <p>The version id, the version number, the effective range, and the pinned markup value. ADR 0007
 * rule 1: "A total that cannot name its rate definition is a bug, not a rounding artefact." Every
 * figure this class contributes to carries all of it.
 *
 * @param costAmount  the per-unit cost to multiply the event's quantity by, or {@code null} when the
 *                    cost is already on the event
 * @param markupRate  the resolved multiplier; never {@code null}, because a resolved rate exists to be
 *                    applied and an unresolved one is represented by the absence of a resolution
 */
public record EffectiveRate(
        UUID rateCardVersionId,
        int versionNumber,
        String currency,
        BigDecimal markupRate,
        BigDecimal costAmount,
        java.time.LocalDate effectiveFrom,
        java.time.LocalDate effectiveTo,
        String taxClassification,
        String changeNote
) {

    public EffectiveRate {
        if (markupRate == null) {
            throw new IllegalArgumentException(
                "An EffectiveRate exists only after the markup has been resolved against the version "
                    + "default. A null markup would multiply a cost by nothing and produce a priced "
                    + "zero, which is a different state from unpriced.");
        }
    }

    /** Builds the resolved rate for a line, substituting the version default where the line is bare. */
    public static EffectiveRate resolve(RateCardVersion version, RateCardVersionLine line) {
        return new EffectiveRate(
            version.id(),
            version.versionNumber(),
            version.currency(),
            line.effectiveMarkup(version.markupRate()),
            line.costAmount(),
            version.effectiveFrom(),
            version.effectiveTo(),
            line.taxClassification(),
            version.changeNote());
    }

    /** Whether this rate covers a whole bucket. See {@link RateCardVersion#covers}. */
    public boolean covers(java.time.LocalDate bucketStart, java.time.LocalDate bucketEnd) {
        if (bucketStart.isBefore(effectiveFrom)) {
            return false;
        }
        return effectiveTo == null || !effectiveTo.isBefore(bucketEnd);
    }

    /** Whether this rate has a per-unit cost, as opposed to taking the cost from the usage event. */
    public boolean hasUnitCost() {
        return costAmount != null;
    }

    /** A short human-facing label naming the rate definition, for a read model's {@code note}. */
    public String rateDefinitionLabel() {
        return "rate-card-version " + versionNumber + " (" + currency + ", markup "
            + markupRate.toPlainString() + ", effective " + effectiveFrom + ".."
            + (effectiveTo == null ? "open" : effectiveTo) + ")";
    }
}
