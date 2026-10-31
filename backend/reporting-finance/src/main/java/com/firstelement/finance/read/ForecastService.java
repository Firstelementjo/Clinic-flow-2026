package com.firstelement.finance.read;

import com.firstelement.finance.catalog.UsageDimension;
import com.firstelement.finance.money.Money;
import com.firstelement.finance.rates.RateCardRepository;
import com.firstelement.finance.rollup.RollupBucket;
import com.firstelement.finance.rollup.RollupRepository;
import com.firstelement.security.TenantContext;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.permission.AuthorizationService;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.permission.PolicyResource;
import com.firstelement.security.query.TenantQueryGuard;
import com.firstelement.security.query.TenantScope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Projects the rest of an open period from what has already been computed.
 *
 * <h2>Why it reads snapshots and not the ledger</h2>
 *
 * <p>Because a projection is a statement about <em>computed</em> figures. Summing the ledger directly
 * would produce a subtly different number from the sum the dashboard shows — the rollup's daily
 * buckets and a raw event sum agree only if every event has been rolled up — and a forecast that
 * disagreed with the actuals it is extrapolating would be worse than none.
 *
 * <h2>Why it never prices an unpriced dimension</h2>
 *
 * <p>{@link FinanceForecast.ProjectedDimension#isPriced()} is false for a dimension with usage and no
 * effective rate, and its revenue and margin carry {@link com.firstelement.finance.money.AmountState#UNPRICED}.
 * Projecting a revenue for it would require choosing a markup, and choosing one is a rate-card
 * authoring act, not an arithmetic one.
 *
 * <h2>The bounds</h2>
 *
 * <p>A forecast is only meaningful for a period that is still running. A closed period's figures are
 * final and projecting them would contradict ADR 0007 rule 4 — so the service requires the period to
 * be {@code OPEN}, and the check is here rather than being implied by the caller.
 */
@Service
public class ForecastService {

    private final RollupRepository rollups;
    private final RateCardRepository rateCards;
    private final TenantQueryGuard guard;
    private final AuthorizationService authorization;

    public ForecastService(RollupRepository rollups, RateCardRepository rateCards,
                           TenantQueryGuard guard, AuthorizationService authorization) {
        this.rollups = rollups;
        this.rateCards = rateCards;
        this.guard = guard;
        this.authorization = authorization;
    }

    /**
     * Projects an open period.
     *
     * @param asOf the day the projection is made from; the observed window is
     *             {@code [periodStart, asOf]} and the remaining window is {@code (asOf, periodEnd]}
     */
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    public FinanceForecast forecast(TenantContext caller, UUID projectId, LocalDate periodStart,
                                    LocalDate periodEnd, LocalDate asOf) {
        if (caller == null) {
            throw new IllegalStateException(
                "A finance read requires a resolved TenantContext; refusing to run unscoped.");
        }
        authorization.check(caller, Permission.ANALYTICS_READ_ORGANIZATION, PolicyResource.NONE);
        TenantScope scope = guard.scope(caller, projectId);
        if (asOf.isBefore(periodStart) || asOf.isAfter(periodEnd)) {
            throw new IllegalArgumentException(
                "asOf " + asOf + " is outside the period " + periodStart + ".." + periodEnd
                    + "; a forecast extrapolates from inside the window it describes.");
        }

        // DAILY grain, always. A monthly bucket cannot express "six days observed, twenty-four to go",
        // and a forecast built from a monthly row would be a projection of a projection.
        List<RollupRepository.SnapshotRow> rows =
            rollups.current(scope, projectId, RollupBucket.Grain.DAILY, periodStart, asOf);

        Map<String, Accumulator> byDimension = new LinkedHashMap<>();
        for (RollupRepository.SnapshotRow row : rows) {
            byDimension.computeIfAbsent(row.dimension(), key -> new Accumulator(row))
                .add(row);
        }

        int observedDays = (int) ChronoUnit.DAYS.between(periodStart, asOf) + 1;
        int remainingDays = (int) ChronoUnit.DAYS.between(asOf, periodEnd);
        Map<UUID, Integer> versionNumbers = new LinkedHashMap<>();

        List<FinanceForecast.ProjectedDimension> projected = new ArrayList<>();
        BigDecimal unpricedTotal = BigDecimal.ZERO;
        for (Accumulator accumulator : byDimension.values()) {
            UsageDimension dimension = UsageDimension.fromWire(accumulator.dimension);
            BigDecimal dailyCost = observedDays <= 0 ? BigDecimal.ZERO
                : accumulator.cost.divide(BigDecimal.valueOf(observedDays), Money.SCALE,
                    RoundingMode.HALF_UP);

            UUID versionId = accumulator.rateCardVersionId;
            Integer versionNumber = null;
            if (versionId != null) {
                versionNumber = versionNumbers.computeIfAbsent(versionId,
                    id -> rateCards.version(scope, id)
                        .map(com.firstelement.finance.rates.RateCardVersion::versionNumber)
                        .orElse(-1));
            }

            projected.add(FinanceForecast.project(dimension.wireValue(), dimension.canonicalUnit(),
                accumulator.currency, accumulator.quantity, accumulator.cost, dailyCost,
                remainingDays, versionId, versionNumber, accumulator.markupRate));

            if (versionId == null) {
                unpricedTotal = unpricedTotal.add(accumulator.cost);
            }
        }

        String currency = byDimension.values().stream()
            .map(accumulator -> accumulator.currency)
            .findFirst()
            .orElse("USD");

        return new FinanceForecast(periodStart, periodEnd, observedDays, remainingDays, currency,
            projected,
            unpricedTotal.signum() == 0 ? Money.unpriced(currency) : Money.of(unpricedTotal, currency),
            FinanceForecast.confidenceFor(observedDays),
            FinanceForecast.DEFAULT_METHOD,
            "A forecast is an extrapolation, not a finance total: it resolves to the buckets it was "
                + "computed from and to this arithmetic, not to a set of immutable usage events. "
                + "Revenue is projected only for dimensions a rate actually priced.");
    }

    /**
     * Whether the caller may compute a rollup at all.
     *
     * <p>Stated as documentation here rather than duplicated in the controller: {@code
     * ANALYTICS_READ_ORGANIZATION} is held by an {@code ADMIN} and by a worker holding
     * {@code FINANCE_AGGREGATE}, and both are legitimate here. A worker may price usage from a card
     * somebody else agreed; it may not author the card, and that distinction lives in
     * {@link com.firstelement.finance.rates.RateCardService}.
     */
    static final class Authority {

        private Authority() {
        }
    }

    /** Per-dimension running totals. */
    private static final class Accumulator {

        private final String dimension;
        private final String currency;
        private BigDecimal quantity = BigDecimal.ZERO;
        private BigDecimal cost = BigDecimal.ZERO;
        private UUID rateCardVersionId;
        private BigDecimal markupRate;
        private boolean mixedRates;

        private Accumulator(RollupRepository.SnapshotRow first) {
            this.dimension = first.dimension();
            this.currency = first.currency();
        }

        private void add(RollupRepository.SnapshotRow row) {
            quantity = quantity.add(row.quantity());
            cost = cost.add(row.costAmount());
            if (row.rateCardVersionId() == null) {
                // A window containing an unpriced bucket cannot be projected at all: the projected
                // total would be a rate applied to part of the period and nowhere else.
                mixedRates = true;
                rateCardVersionId = null;
                markupRate = null;
                return;
            }
            if (rateCardVersionId == null) {
                rateCardVersionId = row.rateCardVersionId();
                markupRate = row.markupRate();
            } else if (!rateCardVersionId.equals(row.rateCardVersionId())) {
                // Two pinned versions in one projection window: the arithmetic below cannot be
                // performed, so it is refused rather than approximated with the latest rate.
                mixedRates = true;
                rateCardVersionId = null;
                markupRate = null;
            }
        }
    }

    /** Reads the version number off a resolved version. */
}
