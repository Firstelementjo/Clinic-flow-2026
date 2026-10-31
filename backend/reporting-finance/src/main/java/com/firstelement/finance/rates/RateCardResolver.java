package com.firstelement.finance.rates;

import com.firstelement.finance.catalog.UsageDimension;
import com.firstelement.security.query.TenantScope;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Answers one question: <b>which rate applied to this dimension on this date?</b>
 *
 * <h2>Resolution order, and why it is project-specific first</h2>
 *
 * <ol>
 *   <li>a <b>project-specific</b> card ({@code rate_cards.projectId = the project's}),</li>
 *   <li>otherwise the <b>organisation-wide</b> card ({@code projectId is null}).</li>
 * </ol>
 *
 * <p>Within one card, the version with the greatest {@code effectiveFrom} that is still effective on
 * the date. "Effective" means {@code effectiveFrom <= date} and
 * ({@code effectiveTo is null} or {@code effectiveTo >= date}) — both ends inclusive, which is the
 * same convention {@code finance_periods} uses, so a rate's last day and a period's last day line up
 * and an event on that day is priced rather than falling through to "unpriced".
 *
 * <h2>Why project-specific wins rather than being an error</h2>
 *
 * <p>Because the migration made {@code rate_cards.projectId} nullable with a comment that says why:
 * "A rate card is agreed once for the tenant; per-project copies are how two projects end up paying
 * different prices for the same token count." Per-project copies are permitted, so the resolver must
 * have an order, and "most specific wins" is the only one that lets an organisation-wide default
 * coexist with a negotiated exception.
 *
 * <h2>What "no rate" returns</h2>
 *
 * <p>{@link Optional#empty()}, never a zero-valued rate and never a rate of 1.0. This is the single
 * most important behaviour in the class: a rate of 1.0 would produce revenue equal to cost and read as
 * "priced at break-even", which is a commercial statement this system is not entitled to make. An empty
 * result becomes {@link com.firstelement.finance.money.AmountState#UNPRICED} and is reported as its own
 * view, which is what ADR 0007 requires.
 */
@Component
public class RateCardResolver {

    private final RateCardRepository cards;

    public RateCardResolver(RateCardRepository cards) {
        this.cards = cards;
    }

    /**
     * The effective rate for a dimension on a date, or empty when nothing prices it.
     *
     * <p>Empty covers three distinct situations, which the caller may want to tell apart and this
     * method deliberately does not: no card exists at all, a card exists but has no ACTIVE version
     * effective on the date, and a card version exists but has no line for this dimension and unit.
     * All three are "unpriced" as far as finance is concerned; the difference matters to whoever sets
     * the card up, and {@link RateCardService} exposes it there rather than here where it would only
     * add a branch to the pricing path.
     *
     * @param projectId the usage's project, which selects a project-specific card when one exists
     */
    public Optional<EffectiveRate> resolve(TenantScope scope, java.util.UUID projectId,
                                           UsageDimension dimension, LocalDate asOf) {
        Optional<EffectiveRate> projectSpecific = resolveFor(scope, projectId, dimension, asOf);
        if (projectSpecific.isPresent()) {
            return projectSpecific;
        }
        return resolveFor(scope, null, dimension, asOf);
    }

    /** The rate for one card scope, ignoring the fallback order. */
    private Optional<EffectiveRate> resolveFor(TenantScope scope, java.util.UUID projectId,
                                               UsageDimension dimension, LocalDate asOf) {
        List<RateCardVersion> candidates =
            cards.effectiveVersions(scope, asOf).stream()
                .filter(version -> java.util.Objects.equals(version.projectId(), projectId))
                .toList();
        for (RateCardVersion version : candidates) {
            Optional<RateCardVersionLine> line = version.lineFor(dimension, dimension.canonicalUnit());
            if (line.isPresent()) {
                // candidates is ordered by effectiveFrom desc, so the first match is the version that
                // most recently took effect — the correct answer when two ranges touch.
                return Optional.of(EffectiveRate.resolve(version, line.get()));
            }
        }
        return Optional.empty();
    }

    /**
     * Whether a bucket is priced entirely by one version.
     *
     * <p>A monthly rollup bucket must not be priced with a version that started mid-month: the days
     * before it took effect were governed by a different version, and using one rate for the whole month
     * would put a price on days that had none. When this is false the caller either uses the daily
     * grain, or records the bucket as spanning two versions — {@link com.firstelement.finance.rollup.RollupService}
     * refuses to guess.
     */
    public boolean singleVersionCovers(TenantScope scope, java.util.UUID projectId,
                                       UsageDimension dimension, LocalDate bucketStart,
                                       LocalDate bucketEnd) {
        Optional<EffectiveRate> rate = resolve(scope, projectId, dimension, bucketStart);
        return rate.isPresent() && rate.get().covers(bucketStart, bucketEnd);
    }

    /**
     * The set of versions that apply to a bucket, one per sub-range.
     *
     * <p>Used by the reconciliation to name, rather than hide, the case where a rate changed mid-period.
     */
    public List<EffectiveRate> versionsSpanning(TenantScope scope, java.util.UUID projectId,
                                                UsageDimension dimension, LocalDate from,
                                                LocalDate to) {
        java.util.LinkedHashMap<java.util.UUID, EffectiveRate> collected = new java.util.LinkedHashMap<>();
        LocalDate cursor = from;
        int guard = 0;
        while (!cursor.isAfter(to) && guard++ < 64) {
            Optional<EffectiveRate> rate = resolve(scope, projectId, dimension, cursor);
            if (rate.isEmpty()) {
                break;
            }
            collected.put(rate.get().rateCardVersionId(), rate.get());
            LocalDate next = rate.get().effectiveTo();
            if (next == null || !next.isAfter(cursor)) {
                // An open-ended or same-day version ends the walk; there is nothing after it.
                break;
            }
            cursor = next.plusDays(1);
        }
        return List.copyOf(collected.values());
    }
}
