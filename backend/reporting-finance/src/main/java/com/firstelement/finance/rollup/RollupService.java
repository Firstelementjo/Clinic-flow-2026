package com.firstelement.finance.rollup;

import com.firstelement.finance.catalog.UsageDimension;
import com.firstelement.finance.ledger.LedgerPrincipal;
import com.firstelement.finance.money.Money;
import com.firstelement.finance.rates.EffectiveRate;
import com.firstelement.finance.rates.RateCardResolver;
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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes a rollup bucket from the immutable ledger, pinning the rate that priced it.
 *
 * <h2>The computation, in one paragraph</h2>
 *
 * <p>Sum {@code usage_events} for one tenant, project, grain, bucket and dimension. Resolve the rate
 * effective on the <b>bucket start</b>. If a rate exists, derive revenue as
 * {@code round(cost × markup, 6)} and margin as {@code revenue − cost}; if none exists, write the row
 * with no rate, no markup and no revenue, and mark the quantity and cost unpriced. Insert the result
 * as a new snapshot row naming the rate-card version <em>and the markup value</em> it used.
 *
 * <p>Either way the row records what is <em>still</em> unpriced, and on a priced row that is only the
 * usage whose events carried no measured cost — never the bucket's own cost, which the resolved rate
 * has priced in full. {@link RollupBucket.UnpricedResidue} carries the two halves as one named value so
 * a caller cannot transpose them; see that record for why a Java literal would not do.
 *
 * <h2>Why a recomputation is a new row</h2>
 *
 * <p>{@code usage_rollup_snapshots} carries {@code firstelement_append_only()} on UPDATE and DELETE, so
 * there is no update path at all. Generation N's arithmetic is frozen: a corrected rate produces
 * generation N+1 with a {@code supersedesSnapshotId} pointer, and both rows remain readable. That is
 * what makes "changing a rate card never mutates an already-computed snapshot" a property of the table
 * rather than a promise — and {@link RollupRateCardMutationTest} proves it by changing a rate and
 * re-reading the old snapshot.
 *
 * <h2>Why the rate is resolved at the bucket start</h2>
 *
 * <p>Because a rate is effective-dated at day granularity — {@code effectiveFrom}/{@code effectiveTo}
 * are {@code date} columns, and {@code V20261005090000} says so explicitly: a timestamp "would imply a
 * second-of-day effect that is not implemented". A monthly bucket is therefore priced by one rate for
 * its whole length or it is not priced at all:
 *
 * <ul>
 *   <li>{@link #compute} checks {@link RateCardResolver#singleVersionCovers} and, when a version does
 *       not cover the whole bucket, records the bucket as <b>spanning versions</b> — which pins
 *       nothing and derives no revenue, rather than applying the newer rate to days the older one
 *       governed.</li>
 *   <li>{@link #computeDaily} has no such problem: a one-day bucket is covered by construction, which
 *       is the honest answer to "a rate changed on the 15th" — two daily rows, each with its own
 *       pinned rate.</li>
 * </ul>
 *
 * <p>A deployment whose rates change mid-period therefore uses the daily grain for the affected month
 * and the monthly grain afterwards. Neither is a compromise; the monthly grain simply refuses to
 * pretend.
 *
 * <h2>Who may compute</h2>
 *
 * <p>{@code ANALYTICS_READ_ORGANIZATION}, satisfied by an {@code ADMIN} or by a worker holding
 * {@code FINANCE_AGGREGATE}. Computing an aggregate is not a commercial act — it prices from a card
 * somebody else agreed — which is why this is a permission an integration can hold and rate-card
 * authoring is not.
 */
@Service
public class RollupService {

    private static final Logger log = LoggerFactory.getLogger(RollupService.class);

    private final RollupRepository rollups;
    private final RateCardResolver rates;
    private final TenantQueryGuard guard;
    private final AuthorizationService authorization;

    public RollupService(RollupRepository rollups, RateCardResolver rates, TenantQueryGuard guard,
                         AuthorizationService authorization) {
        this.rollups = rollups;
        this.rates = rates;
        this.guard = guard;
        this.authorization = authorization;
    }

    /**
     * Computes one bucket and inserts a snapshot row.
     *
     * @return the computed bucket, or empty when the bucket has no usage at all — the "no data" case,
     *         which must not be materialised as a zero row
     */
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    @Transactional
    public Optional<RollupBucket> compute(TenantContext caller, UUID projectId,
                                          RollupBucket.Grain grain, LocalDate bucketStart,
                                          LocalDate bucketEnd, UsageDimension dimension,
                                          LedgerPrincipal principal, String correlationId) {
        TenantScope scope = authorize(caller, projectId);
        return computeInScope(scope, projectId, grain, bucketStart, bucketEnd, dimension, principal,
            correlationId);
    }

    /** Computes every dimension of every bucket in a range. */
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    @Transactional
    public List<RollupBucket> computeRange(TenantContext caller, UUID projectId,
                                           RollupBucket.Grain grain, LocalDate fromDate,
                                           LocalDate toDate, LedgerPrincipal principal,
                                           String correlationId) {
        TenantScope scope = authorize(caller, projectId);
        List<RollupBucket> computed = new ArrayList<>();
        for (LocalDate bucketStart = fromDate; !bucketStart.isAfter(toDate);
             bucketStart = nextBucket(grain, bucketStart)) {
            LocalDate bucketEnd = lastDayOf(grain, bucketStart);
            for (UsageDimension dimension : UsageDimension.values()) {
                computeInScope(scope, projectId, grain, bucketStart, bucketEnd, dimension, principal,
                    correlationId).ifPresent(computed::add);
            }
        }
        return computed;
    }

    /** The daily equivalent of {@link #compute}, which has no mid-bucket rate-change problem. */
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    @Transactional
    public List<RollupBucket> computeDaily(TenantContext caller, UUID projectId, LocalDate fromDate,
                                           LocalDate toDate, LedgerPrincipal principal,
                                           String correlationId) {
        return computeRange(caller, projectId, RollupBucket.Grain.DAILY, fromDate, toDate, principal,
            correlationId);
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private Optional<RollupBucket> computeInScope(TenantScope scope, UUID projectId,
                                                   RollupBucket.Grain grain, LocalDate bucketStart,
                                                   LocalDate bucketEnd, UsageDimension dimension,
                                                   LedgerPrincipal principal, String correlationId) {
        // The DAILY grain buckets on the event's own day; the MONTHLY one on the event's stored billing
        // bucket. Two statements because the two answers differ, and because a reconciliation that
        // recomputed both sides with one expression would agree with itself even when wrong.
        List<RollupRepository.LedgerAggregateRow> rows = grain == RollupBucket.Grain.DAILY
            ? rollups.ledgerAggregateByDay(scope, projectId, bucketStart, bucketEnd)
            : rollups.ledgerAggregate(scope, projectId, bucketStart, bucketEnd);

        Optional<RollupRepository.LedgerAggregateRow> aggregate = rows.stream()
            .filter(row -> row.dimension().equals(dimension.wireValue()))
            .filter(row -> row.bucketDate().equals(bucketStart))
            .findFirst();

        if (aggregate.isEmpty() || aggregate.get().eventCount() == 0) {
            // ZERO IS NOT "NO DATA": a bucket with no contributing events is not materialised at all,
            // because usage_rollup_snapshots_source_count_check requires sourceEventCount >= 1 and a
            // zero row would be indistinguishable from a genuinely empty day forever after.
            return Optional.empty();
        }

        RollupRepository.LedgerAggregateRow ledger = aggregate.get();
        BigDecimal cost = ledger.costAmount().setScale(Money.SCALE, RoundingMode.HALF_UP);
        String currency = ledger.currency();
        RollupRepository.GenerationPosition position = rollups.generation(scope, projectId, grain,
            bucketStart, dimension);

        Optional<EffectiveRate> rate = rates.resolve(scope, projectId, dimension, bucketStart);
        if (rate.isPresent() && !rate.get().covers(bucketStart, bucketEnd)) {
            // A rate that began or ended inside the bucket cannot price the whole bucket. Recording it
            // unpriced is the honest answer; applying the newer rate to days the older one governed is
            // how a month gets a price nobody agreed to.
            log.warn("bucket spans rate versions grain={} bucket={}..{} dimension={} rate={} -- "
                    + "recorded as unpriced; use the DAILY grain for a period whose rate changed",
                grain, bucketStart, bucketEnd, dimension, rate.get().rateDefinitionLabel());
            rate = Optional.empty();
        }

        LocalDateTime computedAt = LocalDateTime.now(ZoneOffset.UTC);
        String computedBy = principal == null ? "SYSTEM:rollup" : principal.name();

        RollupBucket bucket = rate
            .map(resolved -> RollupBucket.priced(UUID.randomUUID(), scope.organizationId(),
                projectId, null, grain, bucketStart, bucketEnd, dimension, ledger.unit(), currency,
                ledger.eventCount(), ledger.quantity(), cost, resolved,
                // WHAT IS STILL UNPRICED IS A DIFFERENT FIGURE FROM WHAT WAS PRICED. A rate priced the
                // whole of `cost` above -- usage_rollup_snapshots_derivation_check multiplies costAmount
                // in full -- so none of it is unbilled. The residue is the usage whose events recorded
                // no measured cost, and the cost of exactly those events, which is zero because a
                // NULL-cost event contributes nothing to either sum. Passing `cost` here instead made
                // every priced bucket report its money as billed and unbilled at once; the two columns
                // are built from one predicate so they cannot drift apart.
                new RollupBucket.UnpricedResidue(ledger.uncostedQuantity(),
                    ledger.uncostedCostAmount()),
                position.nextGeneration(),
                position.supersedesSnapshotId(), computedAt, computedBy, correlationId))
            .orElseGet(() -> RollupBucket.unpriced(UUID.randomUUID(), scope.organizationId(),
                projectId, null, grain, bucketStart, bucketEnd, dimension, ledger.unit(), currency,
                ledger.eventCount(), ledger.quantity(), cost, position.nextGeneration(),
                position.supersedesSnapshotId(), computedAt, computedBy, correlationId));

        rollups.insert(scope, bucket);
        return Optional.of(bucket);
    }

    private TenantScope authorize(TenantContext caller, UUID projectId) {
        if (caller == null) {
            throw new IllegalStateException(
                "A rollup computation requires a resolved TenantContext; refusing to run unscoped.");
        }
        authorization.check(caller, Permission.ANALYTICS_READ_ORGANIZATION, PolicyResource.NONE);
        return guard.scope(caller, projectId);
    }

    /** The next bucket start after {@code from}, so a range walk terminates. */
    static LocalDate nextBucket(RollupBucket.Grain grain, LocalDate from) {
        return grain == RollupBucket.Grain.MONTHLY ? from.plusMonths(1) : from.plusDays(1);
    }

    /** The inclusive last day of a bucket. */
    static LocalDate lastDayOf(RollupBucket.Grain grain, LocalDate bucketStart) {
        return grain == RollupBucket.Grain.MONTHLY
            ? bucketStart.withDayOfMonth(bucketStart.lengthOfMonth())
            : bucketStart;
    }

    /**
     * The rate a bucket would be priced with, for a read model that wants to say so <em>before</em> a
     * computation.
     *
     * <p>Empty means unpriced. Never a zero and never a rate of 1.0.
     */
    public Optional<EffectiveRate> rateFor(TenantScope scope, UUID projectId,
                                           UsageDimension dimension, LocalDate asOf) {
        return rates.resolve(scope, projectId, dimension, asOf);
    }
}
