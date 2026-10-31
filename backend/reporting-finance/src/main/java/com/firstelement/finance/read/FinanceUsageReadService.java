package com.firstelement.finance.read;

import com.firstelement.finance.catalog.UsageDimension;
import com.firstelement.finance.money.AmountState;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Assembles the daily and monthly read models from computed snapshots.
 *
 * <h2>What this class adds over the repository</h2>
 *
 * <p>Presentation and the three distinctions the ADR demands. It adds no arithmetic: cost, revenue and
 * margin all come off the snapshot row with the markup that was pinned when it was computed, and none
 * of them is recomputed here. That is deliberate — a read model that recomputed revenue would be a
 * second pricing implementation, and two implementations disagree eventually.
 *
 * <h2>The three distinctions, made structural</h2>
 *
 * <ol>
 *   <li><b>Unpriced vs zero.</b> An unpriced bucket keeps its cost and reports
 *       {@link AmountState#UNPRICED} for revenue and margin. A bucket whose cost genuinely summed to
 *       zero reports {@link AmountState#PRICED_ZERO}. {@link Money#amount()} throws for the first, so
 *       no caller can reach the number and render it as {@code 0}.</li>
 *   <li><b>Zero vs no data.</b> A bucket with no usage produces no snapshot row at all
 *       ({@code usage_rollup_snapshots_source_count_check}), and this class enumerates those gaps
 *       explicitly as {@link FinanceUsageView#missingBuckets()} rather than leaving them to a client
 *       that would render an empty cell as zero.</li>
 *   <li><b>Partial vs complete revenue.</b> {@link FinanceUsageView.Totals#revenueIsComplete()} is
 *       false when any dimension in the window is unpriced, so a total cannot be presented as finished
 *       while part of the window has no agreed price.</li>
 * </ol>
 *
 * <h2>Why the pinned version number is joined in</h2>
 *
 * <p>ADR 0007 rule 1: "A total that cannot name its rate definition is a bug, not a rounding artefact."
 * The snapshot stores the version <em>id</em> and the pinned markup <em>value</em>; the human-readable
 * version number lives on {@code rate_card_versions}. So this class resolves it once per row rather
 * than leaving a reader with a uuid, which is a reference rather than a definition.
 */
@Service
public class FinanceUsageReadService {

    private static final String NOTE =
        "Revenue and margin are DERIVED, never stored: revenue = round(cost x the markup pinned on this "
            + "snapshot at computation time), margin = revenue - cost. A rate change never mutates a "
            + "computed snapshot; it makes a recomputation, which is a NEW generation. An unpriced "
            + "bucket has real usage and no rate effective on its date, which is a different state from "
            + "zero and from no data.";

    private final RollupRepository rollups;
    private final RateCardRepository rateCards;
    private final TenantQueryGuard guard;
    private final AuthorizationService authorization;

    public FinanceUsageReadService(RollupRepository rollups, RateCardRepository rateCards,
                                   TenantQueryGuard guard, AuthorizationService authorization) {
        this.rollups = rollups;
        this.rateCards = rateCards;
        this.guard = guard;
        this.authorization = authorization;
    }

    /**
     * The daily or monthly read model for a project over a date range.
     *
     * <p>Authorization happens before the query and the tenant predicate is in its {@code WHERE}, so an
     * unauthorized project yields no rows and no aggregate — the same empty answer as a project with no
     * usage, with no way to tell the two apart from the outside.
     */
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    public FinanceUsageView usage(TenantContext caller, UUID projectId, RollupBucket.Grain grain,
                                  LocalDate fromDate, LocalDate toDate) {
        if (caller == null) {
            throw new IllegalStateException(
                "A finance read requires a resolved TenantContext; refusing to run unscoped.");
        }
        authorization.check(caller, Permission.ANALYTICS_READ_ORGANIZATION, PolicyResource.NONE);
        TenantScope scope = guard.scope(caller, projectId);
        if (fromDate.isAfter(toDate)) {
            throw new IllegalArgumentException(
                "fromDate " + fromDate + " is after toDate " + toDate + "; an inverted window would "
                    + "enumerate no buckets and report NO_DATA for a period that has usage.");
        }

        List<RollupRepository.SnapshotRow> rows =
            rollups.current(scope, projectId, grain, fromDate, toDate);
        return assemble(scope, projectId, grain, fromDate, toDate, rows);
    }

    /**
     * Builds the view from a set of snapshot rows.
     *
     * <p>Package-visible and separated from the query so the arithmetic can be tested against hand-built
     * rows, and so a second entry point (a cached read, an export) can reuse it without re-authorizing.
     */
    FinanceUsageView assemble(TenantScope scope, UUID projectId, RollupBucket.Grain grain,
                              LocalDate fromDate, LocalDate toDate,
                              List<RollupRepository.SnapshotRow> rows) {
        Map<UUID, Integer> versionNumbers = resolveVersionNumbers(scope, rows);

        List<FinanceUsageView.BucketRow> buckets = new ArrayList<>(rows.size());
        List<FinanceUsageView.BucketRow> unpricedBuckets = new ArrayList<>();
        BigDecimal totalQuantity = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalRevenue = BigDecimal.ZERO;
        BigDecimal unpricedQuantity = BigDecimal.ZERO;
        BigDecimal unpricedCost = BigDecimal.ZERO;
        long totalEvents = 0;
        String currency = null;

        for (RollupRepository.SnapshotRow row : rows) {
            Money cost = Money.of(row.costAmount(), row.currency());
            boolean priced = row.rateCardVersionId() != null;
            Money revenue = priced
                ? Money.of(row.revenueAmount(), row.currency())
                : Money.unpriced(row.currency());
            Money margin = priced ? cost.marginOf(revenue) : Money.unpriced(row.currency());

            Integer versionNumber = row.rateCardVersionId() == null ? null
                : versionNumbers.get(row.rateCardVersionId());
            FinanceUsageView.BucketRow bucket = new FinanceUsageView.BucketRow(
                row.id(),
                row.bucketStart(),
                row.bucketEnd(),
                UsageDimension.fromWire(row.dimension()),
                UsageDimension.fromWire(row.dimension()).canonicalUnit(),
                row.sourceEventCount(),
                row.quantity(),
                cost,
                revenue,
                margin,
                priced ? RollupBucket.RollupState.PRICED : RollupBucket.RollupState.UNPRICED,
                row.markupRate(),
                row.rateCardVersionId(),
                versionNumber,
                row.unpricedQuantity(),
                row.unpricedCostAmount(),
                row.generation(),
                // Always true, and that is the point: the query selected this row through the
                // "nobody supersedes me" anti-join, so by construction it is the current generation. The
                // flag is carried so a reader can see the repository's guarantee rather than infer it
                // from a null pointer — and the FIRST generation is the one with a null
                // supersedesSnapshotId, so deriving the flag that way would label the current row as
                // superseded from the second generation onwards.
                true,
                rateDefinitionLabel(row, versionNumber));
            buckets.add(bucket);

            totalQuantity = totalQuantity.add(row.quantity());
            totalCost = totalCost.add(row.costAmount());
            totalEvents += row.sourceEventCount();
            if (currency == null) {
                currency = row.currency();
            }
            if (priced) {
                totalRevenue = totalRevenue.add(row.revenueAmount());
            } else {
                unpricedBuckets.add(bucket);
                // THE TWO HALVES COME FROM THEIR OWN COLUMNS. unpricedQuantity is usage in the
                // dimension's unit and unpricedCostAmount is money in the row's currency; adding the
                // quantity and handing it to Money.of produced a figure that read as an amount and was
                // a count, and it is what the period close-readiness document publishes as
                // "unpricedCost". Summing only over unpriced rows also means a priced bucket's residue
                // cannot inflate the unbilled exposure of the window it sits in.
                unpricedQuantity = unpricedQuantity.add(row.unpricedQuantity());
                unpricedCost = unpricedCost.add(row.unpricedCostAmount());
            }
        }

        int distinctDimensions = (int) buckets.stream()
            .map(bucket -> bucket.dimension().name()).distinct().count();
        // A dimension can be priced on some days and unpriced on others — a rate whose effective range
        // starts mid-window. It is counted in BOTH lists, deliberately: the priced count answers "which
        // dimensions have a price at all" and the unpriced count answers "which still have usage nobody
        // priced". Either count alone would misreport a partially priced dimension, and
        // revenueIsComplete() keys off the unpriced one.
        int pricedDimensionCount = (int) buckets.stream()
            .filter(FinanceUsageView.BucketRow::isPriced)
            .map(bucket -> bucket.dimension().name()).distinct().count();
        int unpricedDimensionCount = (int) buckets.stream()
            .filter(bucket -> !bucket.isPriced())
            .map(bucket -> bucket.dimension().name()).distinct().count();
        String viewCurrency = currency == null ? "USD" : currency;

        FinanceUsageView.DataState dataState = buckets.isEmpty()
            ? FinanceUsageView.DataState.NO_DATA : FinanceUsageView.DataState.DATA;

        // The window's margin is DERIVED from the two totals by the same Money.marginOf the per-bucket
        // rows use, rather than by a subtraction here. That is deliberate twice over: it is the only
        // construction permitted to produce a negative amount (a window can be a loss while every
        // individual bucket is not), and it keeps "margin = revenue - cost" in exactly one place, so the
        // total and the rows cannot drift apart.
        Money totalCostMoney = buckets.isEmpty() ? Money.noData(viewCurrency)
            : Money.of(totalCost, viewCurrency);
        Money totalRevenueMoney = buckets.isEmpty() ? Money.noData(viewCurrency)
            : (unpricedDimensionCount == 0 ? Money.of(totalRevenue, viewCurrency)
                : Money.unpriced(viewCurrency));
        Money totalMarginMoney = buckets.isEmpty() ? Money.noData(viewCurrency)
            : (unpricedDimensionCount == 0 ? totalCostMoney.marginOf(totalRevenueMoney)
                : Money.unpriced(viewCurrency));

        FinanceUsageView.Totals totals = new FinanceUsageView.Totals(
            totalEvents,
            totalQuantity,
            totalCostMoney,
            totalRevenueMoney,
            totalMarginMoney,
            pricedDimensionCount,
            unpricedDimensionCount);

        FinanceUsageView.Unpriced unpriced = new FinanceUsageView.Unpriced(
            unpricedBuckets.size(),
            unpricedQuantity,
            buckets.isEmpty() ? Money.noData(viewCurrency)
                : Money.of(unpricedCost, viewCurrency),
            unpricedBuckets,
            unpricedBuckets.isEmpty() ? null
                : "no rate-card version was effective on the bucket's date for these dimensions, so "
                    + "usage exists with no agreed price; the cost is real and the revenue is not");

        List<LocalDate> missing = missingBuckets(grain, fromDate, toDate, rows);

        return new FinanceUsageView(scope.organizationId(), projectId, grain, fromDate, toDate,
            viewCurrency, dataState, totals, unpriced, buckets, missing,
            NOTE + " Dimensions observed: " + distinctDimensions + ".");
    }

    /**
     * The buckets in the requested range with no snapshot row.
     *
     * <p>Enumerated so a client can distinguish "nothing happened" from "nothing cost anything". The
     * walk is bounded by the range, which is itself bounded by
     * {@link com.firstelement.finance.ReportingFinanceModule.Limits}, so an unbounded window cannot
     * become an unbounded list.
     */
    private static List<LocalDate> missingBuckets(RollupBucket.Grain grain, LocalDate fromDate,
                                                  LocalDate toDate,
                                                  List<RollupRepository.SnapshotRow> rows) {
        Map<LocalDate, Boolean> present = new LinkedHashMap<>();
        for (RollupRepository.SnapshotRow row : rows) {
            present.put(row.bucketStart(), Boolean.TRUE);
        }
        List<LocalDate> missing = new ArrayList<>();
        int guard = 0;
        LocalDate cursor = fromDate;
        while (!cursor.isAfter(toDate) && guard++ < 400) {
            if (!present.containsKey(cursor)) {
                missing.add(cursor);
            }
            cursor = grain == RollupBucket.Grain.MONTHLY ? cursor.plusMonths(1) : cursor.plusDays(1);
        }
        return missing;
    }

    /**
     * The pinned version numbers, resolved once per distinct version.
     *
     * <p>Cached per request rather than per row: a month of daily buckets carries the same version id on
     * every row, and a lookup per row would be thirty-one round trips for one number.
     */
    private Map<UUID, Integer> resolveVersionNumbers(TenantScope scope,
                                                     List<RollupRepository.SnapshotRow> rows) {
        Map<UUID, Integer> numbers = new LinkedHashMap<>();
        for (RollupRepository.SnapshotRow row : rows) {
            UUID versionId = row.rateCardVersionId();
            if (versionId == null || numbers.containsKey(versionId)) {
                continue;
            }
            int number = rateCards.version(scope, versionId)
                .map(version -> version.versionNumber())
                .orElse(-1);
            // -1 rather than null: a pinned version that cannot be read is a broken drill-through and
            // must be visible as such, not silently rendered as "no version".
            numbers.put(versionId, number);
        }
        return numbers;
    }

    private static String rateDefinitionLabel(RollupRepository.SnapshotRow row,
                                              Integer versionNumber) {
        if (row.rateCardVersionId() == null) {
            return "unpriced: no rate-card version was effective on " + row.bucketStart();
        }
        return "rate-card-version " + (versionNumber == null || versionNumber < 0 ? "?" : versionNumber)
            + " (markup " + row.markupRate() + " pinned at computation time)";
    }
}
