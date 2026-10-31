package com.firstelement.finance.money;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * The FX attribution that travels with every converted amount: the rate, where it came from, and the
 * date it applied on.
 *
 * <h2>All three or none</h2>
 *
 * <p>ADR 0007 rule 6: "conversion records the FX source and rate date." The schema enforces the same
 * biconditional on {@code usage_events} ({@code usage_events_fx_pair_check}: a rate with no source is
 * not reproducible, and a source with no rate is a leftover). This record makes it unrepresentable in
 * Java too, so the failure arrives at the point the conversion is written rather than at the column.
 *
 * <h2>Why the rate date is separate from the usage date</h2>
 *
 * <p>A usage event on the 3rd priced with the rate published on the 5th is a different number from
 * the same usage priced with the rate in force on the 3rd, and only the second is defensible without
 * a conversation. Carrying the rate date separately from {@code occurredAt} lets a reviewer see which
 * one was used, and lets the read model say "priced at the 2026-03-05 rate" instead of implying a
 * rate that did not exist yet.
 *
 * <h2>Rate direction</h2>
 *
 * <p>{@link #rate()} is always <em>source per one target</em>: {@code cost in target = cost in source
 * × rate}. Converting the other way divides. Storing one number with a direction attached to it is
 * what makes a reversible conversion possible; two free-floating rates is how a total ends up
 * multiplied by 3.75.
 *
 * @param rate     strictly positive, the number of {@code targetCurrency} per one {@code sourceCurrency}
 * @param source   where the rate came from, non-blank
 * @param rateDate the date the rate applies to
 * @param from     the currency the amount was originally expressed in
 * @param to       the currency the amount is expressed in now
 */
public record FxAttribution(
        BigDecimal rate,
        String source,
        LocalDate rateDate,
        String from,
        String to
) {

    /** The column scale on {@code usage_events.fxRate} ({@code numeric(20,8)}). */
    public static final int RATE_SCALE = 8;

    public FxAttribution {
        Objects.requireNonNull(rate, "rate");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(rateDate, "rateDate");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (rate.signum() <= 0) {
            throw new IllegalArgumentException(
                "An FX rate must be greater than zero; got " + rate.toPlainString() + ". A zero or "
                    + "negative rate would make a conversion produce a zero or negative amount, which "
                    + "the schema refuses anyway (usage_events_fx_rate_check).");
        }
        if (source.isBlank()) {
            throw new IllegalArgumentException(
                "An FX rate with no source is not reproducible. Record where the rate came from.");
        }
        if (!from.matches("^[A-Z]{3}$") || !to.matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException(
                "FX currencies must be ISO-4217-shaped; got " + from + " -> " + to);
        }
        if (from.equals(to)) {
            throw new IllegalArgumentException(
                "An FX attribution from " + from + " to " + from + " is not a conversion; there is no "
                    + "rate to record. A same-currency amount carries a null FxAttribution.");
        }
        rate = rate.setScale(RATE_SCALE, java.math.RoundingMode.HALF_UP);
    }

    /** Whether this attribution converts {@code from} into the given target currency. */
    public boolean convertsTo(String targetCurrency) {
        return to.equals(targetCurrency);
    }
}
