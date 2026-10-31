package com.firstelement.finance.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * Converts an amount between currencies, and refuses to do it without evidence.
 *
 * <h2>The one conversion this system performs</h2>
 *
 * <p>Usage costs arrive in the currency the provider billed in — USD for an AI provider, SAR for a
 * local carrier — and a rate card prices in one currency of its own. So a conversion is necessary,
 * not optional. What is not acceptable is a conversion whose rate nobody can name, which is why
 * {@link FxRate} carries a source and a date and why {@link #convert} requires both.
 *
 * <h2>Why conversion is not derived from a rate card</h2>
 *
 * <p>Rate cards carry a <em>markup</em>, not an exchange rate. Keeping FX separate means a change in
 * the markup cannot move an FX rate and a change in the FX rate cannot move a markup, so "which rate
 * priced this" always has exactly one answer.
 *
 * <h2>Direction</h2>
 *
 * <p>{@link FxRate} is {@code target per one source}. Converting B to A divides. There is no
 * {@code divide} overload on {@link Money} for that reason: the only way to move money between
 * currencies is through a named rate, and every call site therefore carries the rate it used.
 */
public final class FxConverter {

    private FxConverter() {
    }

    /**
     * One exchange rate with its provenance.
     *
     * @param rate     {@code targetCurrency} per one {@code sourceCurrency}; strictly positive
     * @param source   where it came from, non-blank
     * @param rateDate the date the rate applies to
     * @param from     the source currency
     * @param to       the target currency
     */
    public record FxRate(BigDecimal rate, String source, java.time.LocalDate rateDate,
                         String from, String to) {

        public FxRate {
            if (rate == null || rate.signum() <= 0) {
                throw new IllegalArgumentException(
                    "An FX rate must be greater than zero; got " + rate);
            }
            if (source == null || source.isBlank()) {
                throw new IllegalArgumentException("An FX rate must name its source");
            }
            if (rateDate == null) {
                throw new IllegalArgumentException("An FX rate must carry its rate date");
            }
            if (from == null || to == null || !from.matches("^[A-Z]{3}$")
                || !to.matches("^[A-Z]{3}$")) {
                throw new IllegalArgumentException(
                    "FX currencies must be ISO-4217-shaped; got " + from + " -> " + to);
            }
            if (from.equals(to)) {
                throw new IllegalArgumentException(
                    "An FX rate from " + from + " to " + from + " is not a conversion");
            }
            rate = rate.setScale(FxAttribution.RATE_SCALE, RoundingMode.HALF_UP);
        }
    }

    /**
     * Converts {@code amount} into {@code targetCurrency} at {@code rate}.
     *
     * <p>Same-currency is a no-op that returns the original amount with its attribution intact —
     * deliberately <em>not</em> a rate of 1.0. A fabricated "rate 1.0 from USD to USD" would be
     * recorded, would appear in a drill-through as evidence of a conversion that never happened, and
     * would make "did this amount cross a currency?" unanswerable from the row.
     *
     * @throws IllegalArgumentException when the rate does not convert the amount's currency into the
     *         requested one, which is the check that stops a 3.75 factor being applied to a number
     *         that was never converted
     */
    public static Money convert(Money amount, FxRate rate, String targetCurrency) {
        if (rate.from().equals(rate.to())) {
            throw new IllegalArgumentException("An FxRate must convert between two currencies");
        }
        if (!rate.from().equals(amount.currency())) {
            throw new IllegalArgumentException(
                "The rate converts " + rate.from() + " -> " + rate.to() + " but the amount is in "
                    + amount.currency() + ". Applying it would multiply by " + rate.rate()
                    + " for no reason, and the resulting total would be wrong by that factor.");
        }
        if (!rate.to().equals(targetCurrency)) {
            throw new IllegalArgumentException(
                "The rate converts into " + rate.to() + ", not " + targetCurrency);
        }
        if (amount.state() == com.firstelement.finance.money.AmountState.UNPRICED) {
            throw new IllegalStateException(
                "Refusing to convert an UNPRICED amount. There is no cost to convert; converting it "
                    + "would produce a number in the target currency that looks priced and is not.");
        }
        if (amount.isNoData()) {
            throw new IllegalStateException(
                "Refusing to convert a NO_DATA amount. 'Nothing was recorded' is not a value to "
                    + "re-express in another currency.");
        }
        BigDecimal converted = amount.amount().multiply(rate.rate())
            .setScale(Money.SCALE, RoundingMode.HALF_UP);
        return Money.of(converted, rate.to(),
            new FxAttribution(rate.rate(), rate.source(), rate.rateDate(), rate.from(), rate.to()));
    }

    /**
     * Finds the rate that converts {@code from} into {@code to}, if the caller has one.
     *
     * <p>Empty rather than throwing, because "no rate for this pair on this date" is a real state the
     * caller must report as unpriced, not an exception it should have to catch to find out.
     */
    public static Optional<FxRate> find(List<FxRate> rates, String from, String to,
                                         java.time.LocalDate onOrBefore) {
        return rates.stream()
            .filter(rate -> rate.from().equals(from) && rate.to().equals(to))
            .filter(rate -> !rate.rateDate().isAfter(onOrBefore))
            .max((a, b) -> a.rateDate().compareTo(b.rateDate()));
    }
}
