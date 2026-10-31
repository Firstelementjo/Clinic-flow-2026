package com.firstelement.finance.rates;

import com.firstelement.finance.catalog.UsageDimension;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One priced dimension on one rate-card version.
 *
 * <h2>Why a line can carry a cost, a markup, or both</h2>
 *
 * <p>Three real shapes exist and collapsing them would be wrong:
 *
 * <ul>
 *   <li><b>cost only</b> — a carrier's per-message tariff in SAR. The cost is a fact from a contract
 *       and the markup is the card's default.</li>
 *   <li><b>markup only</b> — tokens. The cost comes from the provider invoice recorded on the usage
 *       event, and what the card adds is the multiplier that turns it into a billable amount.</li>
 *   <li><b>both</b> — a per-unit list price that the card also marks up.</li>
 * </ul>
 *
 * <p>So {@code rate_card_version_lines_prices_something_check} requires at least one, and neither is
 * invented from the other.
 *
 * <h2>Why the line's currency is not trusted</h2>
 *
 * <p>{@code currency} is NOT NULL here and the schema checks its shape, but a line priced in a
 * different currency from its own card is a cross-row property that no CHECK can see — the migration
 * says so and leaves it to {@code ops/flyway/verification/V13_usage_finance_slice.sql} section 8. This
 * class closes the gap on the write path instead: {@link #forVersion} refuses a currency that
 * disagrees with the version, so the defect cannot be introduced rather than merely being detectable.
 *
 * @param costAmount        cost per unit, or {@code null} when the cost comes from the usage event
 * @param markupRate        per-line markup override, or {@code null} to use the version's default
 */
public record RateCardVersionLine(
        UUID id,
        UUID rateCardVersionId,
        UsageDimension dimension,
        String unit,
        BigDecimal costAmount,
        BigDecimal markupRate,
        String currency,
        String taxClassification
) {

    public RateCardVersionLine {
        if (costAmount == null && markupRate == null) {
            throw new IllegalArgumentException(
                "rate_card_version_lines_prices_something_check requires a cost, a markup, or both. A "
                    + "line with neither prices nothing while looking configured, which is how an "
                    + "unpriced bucket gets mistaken for a configured one.");
        }
        if (costAmount != null && costAmount.signum() < 0) {
            throw new IllegalArgumentException(
                "A unit cost must not be negative; a discount is another line, not a negative price.");
        }
        if (markupRate != null && (markupRate.signum() <= 0
            || markupRate.compareTo(BigDecimal.valueOf(1000)) > 0)) {
            throw new IllegalArgumentException(
                "A line markup must be greater than 0 and at most 1000; got " + markupRate);
        }
    }

    /**
     * The markup that applies to this line: its own if it has one, otherwise the version default.
     *
     * @param versionDefault the version's {@code markupRate}
     */
    public BigDecimal effectiveMarkup(BigDecimal versionDefault) {
        return markupRate == null ? versionDefault : markupRate;
    }

    /**
     * Builds a line, refusing a currency that disagrees with its version.
     *
     * @param versionCurrency the version's currency
     */
    public static RateCardVersionLine forVersion(UUID id, UUID rateCardVersionId,
                                                 UsageDimension dimension, String unit,
                                                 BigDecimal costAmount, BigDecimal markupRate,
                                                 String versionCurrency, String lineCurrency,
                                                 String taxClassification) {
        if (!versionCurrency.equals(lineCurrency)) {
            throw new IllegalArgumentException(
                "rate_card_version_lines.currency must equal its version's currency. The line prices "
                    + dimension + " in " + lineCurrency + " on a version that prices in "
                    + versionCurrency + "; no CHECK constraint can see that (they are different rows), "
                    + "and the mistake only surfaces in a reconciliation weeks later.");
        }
        return new RateCardVersionLine(id, rateCardVersionId, dimension, unit, costAmount, markupRate,
            lineCurrency, taxClassification);
    }
}
