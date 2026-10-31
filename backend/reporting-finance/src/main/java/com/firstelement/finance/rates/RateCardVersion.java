package com.firstelement.finance.rates;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A rate-card version together with the lines that price its dimensions.
 *
 * <h2>What a rate card is, in one paragraph</h2>
 *
 * <p>A rate card is finance-owned configuration (ADR 0007: "Engineering may seed a default card but
 * must not silently change one"). It is single-currency — deliberately, because a card that prices USD
 * and SAR in one row has an exchange rate inside it, and an exchange rate inside a rate card is how
 * "which rate applies" stops being answerable. It has versions. A version's <em>pricing</em> is written
 * once and never changed; its <em>lifecycle</em> moves exactly once, from {@code ACTIVE} to
 * {@code RETIRED}, and that is the only UPDATE
 * {@code firstelement_rate_card_version_guard()} accepts.
 *
 * <h2>Why the markup value travels with the version rather than being looked up</h2>
 *
 * <p>Because the rollup snapshot pins it too. Pinning only the version id would leave the snapshot
 * mutable: the version's own markup could be edited in place and every snapshot referencing it would
 * silently change. {@code usage_rollup_snapshots} stores both {@code rateCardVersionId} and the
 * {@code markupRate} <em>value</em> it was computed with, and
 * {@code usage_rollup_snapshots_derivation_check} requires
 * {@code revenueAmount = round(costAmount * markupRate, 6)} exactly — so "where did this number come
 * from" always has an arithmetic answer and an auditor can always check it.
 *
 * @param id              the version's id, pinned onto snapshots
 * @param rateCardId      the card it belongs to
 * @param versionNumber   1-based, unique per card
 * @param currency        the single currency this version prices in
 * @param markupRate      the default multiplier, {@code (0, 1000]}
 * @param effectiveFrom   the first date this version applies, inclusive
 * @param effectiveTo     the last date, inclusive, or {@code null} for open-ended
 */
public record RateCardVersion(
        UUID id,
        UUID organizationId,
        UUID projectId,
        UUID rateCardId,
        int versionNumber,
        String currency,
        BigDecimal markupRate,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String status,
        String changeNote,
        java.util.List<RateCardVersionLine> lines
) {

    public RateCardVersion {
        if (markupRate == null || markupRate.signum() <= 0
            || markupRate.compareTo(BigDecimal.valueOf(1000)) > 0) {
            throw new IllegalArgumentException(
                "A markup is a multiplier greater than 0 and at most 1000 (the schema's own bounds on "
                    + "rate_card_versions.markupRate); got " + markupRate);
        }
        if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
            throw new IllegalArgumentException(
                "rate_card_versions_effective_range_check forbids effectiveTo < effectiveFrom");
        }
        lines = lines == null ? java.util.List.of() : java.util.List.copyOf(lines);
    }

    /** Whether this version is effective on {@code date}. */
    public boolean isEffectiveOn(LocalDate date) {
        if (date.isBefore(effectiveFrom)) {
            return false;
        }
        return effectiveTo == null || !date.isAfter(effectiveTo);
    }

    /**
     * Whether this version covers a whole bucket, start to end.
     *
     * <p>The distinction that keeps a monthly rollup honest. A version that started on the 15th does
     * not price the whole of March, and pricing March with it anyway would put one rate on days a
     * different rate governed. When this is false the rollup must either use a daily grain or mark the
     * bucket as spanning two versions — see
     * {@link com.firstelement.finance.rollup.RollupService}.
     */
    public boolean covers(LocalDate bucketStart, LocalDate bucketEnd) {
        return !bucketStart.isBefore(effectiveFrom) && (effectiveTo == null || !effectiveEndBefore(bucketEnd));
    }

    private boolean effectiveEndBefore(LocalDate bucketEnd) {
        return effectiveTo != null && effectiveTo.isBefore(bucketEnd);
    }

    /** The line pricing one dimension and unit, or empty when this version does not price it. */
    public java.util.Optional<RateCardVersionLine> lineFor(
            com.firstelement.finance.catalog.UsageDimension dimension, String unit) {
        return lines.stream()
            .filter(line -> line.dimension() == dimension && line.unit().equals(unit))
            .findFirst();
    }

    /** Whether this version prices the dimension at all, at any unit. */
    public boolean prices(com.firstelement.finance.catalog.UsageDimension dimension) {
        return lines.stream().anyMatch(line -> line.dimension() == dimension);
    }
}
