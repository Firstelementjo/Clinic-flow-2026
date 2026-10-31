package com.firstelement.finance.legacy;

import com.firstelement.finance.catalog.UsageDimension;
import com.firstelement.finance.money.Money;
import com.firstelement.finance.rollup.RollupBucket;
import com.firstelement.finance.rollup.RollupRepository;
import com.firstelement.finance.legacy.VarianceCause;
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
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Legacy-versus-new analytics comparison, with every difference explained.
 *
 * <h2>What is being compared and what is not</h2>
 *
 * <p>Only the AI cost figures, because that is where the two systems overlap.
 * {@code ai_usage} carries {@code inputTokens}, {@code outputTokens} and {@code estimatedCost}; the
 * ledger carries {@code AI_TOKEN} and {@code AI_REQUEST} usage events. So the comparison is per
 * dimension per window, and everything else the legacy analytics service reported — conversation
 * counts, CSAT, median first-response time, the workload list — is <em>not</em> comparable, because
 * this system has no equivalent metric and inventing one would be the "no mutable counter becomes
 * money" failure in a new place.
 *
 * <h2>Why the legacy table is read and never written, and never migrated</h2>
 *
 * <p>ADR 0009 §3: "do not migrate it as an amount… {@code ai_usage} stays readable as a legacy
 * comparison source only." So this class issues one {@code select} and one aggregate over that table and
 * nothing else. There is no backfill, no adoption path, and no column on
 * {@code usage_events} that could hold an {@code estimatedCost}: the migration's own guard fails the
 * build if a revenue, billable, margin or price column ever appears there, and a hand-written cost on
 * an event would be worse than either because it would at least look traceable.
 *
 * <h2>Why the report never reconciles</h2>
 *
 * <p>Because there is nothing to reconcile to. The ledger is the finance truth; the legacy figure is a
 * mutable estimate that may or may not have had a price for the model involved. So the output is a
 * pair of numbers plus a {@link LegacyComparisonRow.VarianceCause} naming the difference, and there is
 * no "adjusted" column, no auto-correction and no suggestion that one side should be written back. A
 * comparison that adjusted would be a second source of truth for the same money.
 */
@Service
public class LegacyAnalyticsComparisonService {

    /**
     * The legacy table's own numbers over a window.
     *
     * <p>Read-only, tenant-scoped, and shaped around
     * {@code ai_usage_organizationId_projectId_createdAt_idx}. {@code estimatedCost} is a
     * {@code double precision} column, so it is summed as-is and the comparison reports the difference
     * rather than trying to make a double a finance figure.
     */
    private static final String LEGACY_AI_USAGE = """
        select coalesce(sum(u."inputTokens"), 0) as "inputTokens",
               coalesce(sum(u."outputTokens"), 0) as "outputTokens",
               coalesce(sum(u."estimatedCost"), 0) as "estimatedCost",
               count(*) as "runCount",
               count(*) filter (where u."estimatedCost" = 0) as "zeroCostRuns",
               count(distinct u."model") as "modelCount"
          from ai_usage u
         where u."organizationId" = :__fe_tenant_organization_id
           and (cast(:filterProjectId as uuid) is null or u."projectId" = cast(:filterProjectId as uuid))
           and (u."createdAt" AT TIME ZONE 'UTC')::date >= :fromDate
           and (u."createdAt" AT TIME ZONE 'UTC')::date <= :toDate
        """;

    /**
     * The models the legacy table saw in the window.
     *
     * <p>Selected so a reader can see <em>which</em> models produced a zero-cost run, which is the
     * evidence behind {@link LegacyComparisonRow.VarianceCause#LEGACY_MODEL_UNPRICED}. The model name
     * is not a customer identifier and is not credential-shaped; the provider key is never selected
     * from anywhere.
     */
    private static final String LEGACY_MODELS = """
        select coalesce(u."model", '(unknown)') as "model",
               count(*) as "runCount",
               coalesce(sum(u."estimatedCost"), 0) as "estimatedCost"
          from ai_usage u
         where u."organizationId" = :__fe_tenant_organization_id
           and (cast(:filterProjectId as uuid) is null or u."projectId" = cast(:filterProjectId as uuid))
           and (u."createdAt" AT TIME ZONE 'UTC')::date >= :fromDate
           and (u."createdAt" AT TIME ZONE 'UTC')::date <= :toDate
         group by coalesce(u."model", '(unknown)')
         order by "estimatedCost", "runCount" desc
        """;

    /** The legacy per-run figures, so a reviewer can drill to the rows behind a variance. */
    private static final String LEGACY_RUNS = """
        select u."id", u."model", u."inputTokens", u."outputTokens", u."estimatedCost",
               u."createdAt"
          from ai_usage u
         where u."organizationId" = :__fe_tenant_organization_id
           and (cast(:filterProjectId as uuid) is null or u."projectId" = cast(:filterProjectId as uuid))
           and (u."createdAt" AT TIME ZONE 'UTC')::date >= :fromDate
           and (u."createdAt" AT TIME ZONE 'UTC')::date <= :toDate
         order by u."createdAt" desc
         limit :limit
        """;

    private final NamedParameterJdbcTemplate jdbc;
    private final RollupRepository rollups;
    private final TenantQueryGuard guard;
    private final AuthorizationService authorization;

    public LegacyAnalyticsComparisonService(NamedParameterJdbcTemplate jdbc,
                                            RollupRepository rollups, TenantQueryGuard guard,
                                            AuthorizationService authorization) {
        this.jdbc = jdbc;
        this.rollups = rollups;
        this.guard = guard;
        this.authorization = authorization;
    }

    /**
     * Compares the legacy AI figures with the ledger's for a window.
     *
     * <p>Read-only and {@code @Transactional(readOnly = true)}: this view must never take a lock on
     * either table, because it runs beside a live rollup and the whole point of ADR 0007 rule 3 is that
     * finance reads the ledger while the ledger is being written.
     */
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    @Transactional(readOnly = true)
    public ComparisonReport compare(TenantContext caller, UUID projectId, LocalDate fromDate,
                                    LocalDate toDate, int runLimit) {
        if (caller == null) {
            throw new IllegalStateException(
                "A finance read requires a resolved TenantContext; refusing to run unscoped.");
        }
        authorization.check(caller, Permission.ANALYTICS_READ_ORGANIZATION, PolicyResource.NONE);
        TenantScope scope = guard.scope(caller, projectId);

        LegacyTotals legacy = legacyTotals(scope, projectId, fromDate, toDate);
        Map<UsageDimension, LedgerTotals> ledger = ledgerTotals(scope, projectId, fromDate, toDate);

        List<LegacyComparisonRow> rows = new ArrayList<>();
        for (UsageDimension dimension : List.of(UsageDimension.AI_TOKEN, UsageDimension.AI_REQUEST)) {
            LedgerTotals side = ledger.get(dimension);
            BigDecimal legacyTokens = dimension == UsageDimension.AI_TOKEN
                ? legacy.totalTokens() : BigDecimal.valueOf(legacy.runCount());
            BigDecimal ledgerTokens = side == null ? BigDecimal.ZERO : side.quantity();
            BigDecimal ledgerCost = side == null ? BigDecimal.ZERO : side.cost();
            String currency = side == null ? legacyCurrency() : side.currency();

            rows.add(compareOne(dimension, currency, legacyTokens, ledgerTokens,
                legacy.estimatedCost(), ledgerCost, side == null ? null : side.revenue()));
        }

        List<Map<String, Object>> models = legacyModels(scope, projectId, fromDate, toDate);
        List<Map<String, Object>> runs = legacyRuns(scope, projectId, fromDate, toDate, runLimit);

        return new ComparisonReport(scope.organizationId(), projectId, fromDate, toDate,
            legacy, rows, models, runs, NOTE);
    }

    /**
     * Names the difference between the two sides.
     *
     * <p>The order of the tests is the order of how much the difference tells you. "Legacy cost is
     * zero while the ledger has one" is the strongest signal and the most common expected case; "both
     * are zero but the ledger has tokens" is an emission gap; "same tokens, different money" is the
     * pricing basis ADR 0009 §3 decided about; anything else is the widest case and the only one
     * flagged for investigation.
     */
    private LegacyComparisonRow compareOne(UsageDimension dimension, String currency,
                                           BigDecimal legacyTokens, BigDecimal ledgerTokens,
                                           BigDecimal legacyCost, BigDecimal ledgerCost,
                                           Money ledgerRevenue) {
        VarianceCause cause;
        if (legacyCost.signum() == 0 && ledgerCost.signum() == 0 && ledgerTokens.signum() == 0) {
            cause = VarianceCause.NONE;
        } else if (legacyCost.signum() == 0 && ledgerCost.signum() > 0) {
            cause = VarianceCause.LEGACY_MODEL_UNPRICED;
        } else if (legacyCost.signum() == 0 && ledgerCost.signum() == 0) {
            cause = VarianceCause.LEGACY_ONLY;
        } else if (ledgerCost.signum() == 0 && legacyTokens.signum() == 0) {
            cause = VarianceCause.LEDGER_ONLY;
        } else if (legacyTokens.subtract(ledgerTokens).signum() == 0) {
            cause = VarianceCause.PRICE_BASIS_DIFFERS;
        } else {
            cause = VarianceCause.QUANTITY_AND_PRICE_DIFFER;
        }

        return LegacyComparisonRow.of(dimension.wireValue(), currency, legacyTokens, ledgerTokens,
            legacyCost, ledgerCost,
            ledgerRevenue == null ? Money.unpriced(currency) : ledgerRevenue, cause);
    }

    private LegacyTotals legacyTotals(TenantScope scope, UUID projectId, LocalDate fromDate,
                                      LocalDate toDate) {
        MapSqlParameterSource parameters =
            com.firstelement.security.query.ScopedWhere.of(scope).bindNamed(Map.of(
                "fromDate", fromDate,
                "toDate", toDate));
        parameters.addValue("filterProjectId", projectId);
        Map<String, Object> row = jdbc.queryForMap(LEGACY_AI_USAGE, parameters);
        return new LegacyTotals(
            ((Number) row.get("inputTokens")).longValue(),
            ((Number) row.get("outputTokens")).longValue(),
            ((Number) row.get("runCount")).longValue(),
            new BigDecimal(String.valueOf(row.get("estimatedCost"))),
            ((Number) row.get("zeroCostRuns")).longValue(),
            ((Number) row.get("modelCount")).longValue());
    }

    private List<Map<String, Object>> legacyModels(TenantScope scope, UUID projectId,
                                                   LocalDate fromDate, LocalDate toDate) {
        MapSqlParameterSource parameters =
            com.firstelement.security.query.ScopedWhere.of(scope).bindNamed(Map.of(
                "fromDate", fromDate,
                "toDate", toDate));
        parameters.addValue("filterProjectId", projectId);
        return jdbc.queryForList(LEGACY_MODELS, parameters);
    }

    private List<Map<String, Object>> legacyRuns(TenantScope scope, UUID projectId,
                                                 LocalDate fromDate, LocalDate toDate, int limit) {
        MapSqlParameterSource parameters =
            com.firstelement.security.query.ScopedWhere.of(scope).bindNamed(Map.of(
                "fromDate", fromDate,
                "toDate", toDate,
                "limit", Math.max(1, Math.min(limit,
                    com.firstelement.finance.ReportingFinanceModule.Limits.LEGACY_RUN_PAGE))));
        parameters.addValue("filterProjectId", projectId);
        return jdbc.queryForList(LEGACY_RUNS, parameters);
    }

    /** The ledger's totals for the AI dimensions over the window. */
    private Map<UsageDimension, LedgerTotals> ledgerTotals(TenantScope scope, UUID projectId,
                                                            LocalDate fromDate, LocalDate toDate) {
        Map<UsageDimension, LedgerTotals> totals = new LinkedHashMap<>();
        for (RollupRepository.SnapshotRow row : rollups.current(scope, projectId,
            RollupBucket.Grain.DAILY, fromDate, toDate)) {
            UsageDimension dimension = UsageDimension.fromWire(row.dimension());
            if (dimension != UsageDimension.AI_TOKEN && dimension != UsageDimension.AI_REQUEST) {
                continue;
            }
            totals.merge(dimension, LedgerTotals.of(row), LedgerTotals::add);
        }
        return totals;
    }

    private static String legacyCurrency() {
        // ai_usage has no currency column; the legacy estimator produced USD by construction
        // (packages/ai/src/providers/types.ts PRICES are "USD per million tokens"). Stated here as a
        // constant with its source rather than inferred, because a currency on a comparison row has to
        // be a decision somebody made.
        return "USD";
    }

    /** The legacy table's own totals over the window. */
    public record LegacyTotals(
            long inputTokens,
            long outputTokens,
            long runCount,
            BigDecimal estimatedCost,
            long zeroCostRuns,
            long modelCount
    ) {

        /** Input plus output, as one token count. */
        public BigDecimal totalTokens() {
            return BigDecimal.valueOf(inputTokens + outputTokens);
        }

        /**
         * Whether any run recorded a zero cost.
         *
         * <p>The evidence behind {@link LegacyComparisonRow.VarianceCause#LEGACY_MODEL_UNPRICED}: a
         * zero-cost run against a model with usage means the legacy estimator had no price, not that
         * the run was free.
         */
        public boolean hasUnpricedModels() {
            return zeroCostRuns > 0;
        }
    }

    /** The ledger's totals for one dimension. */
    private record LedgerTotals(BigDecimal quantity, BigDecimal cost, String currency, Money revenue) {

        private static LedgerTotals of(RollupRepository.SnapshotRow row) {
            Money revenue = row.rateCardVersionId() == null ? Money.unpriced(row.currency())
                : Money.of(row.revenueAmount(), row.currency());
            return new LedgerTotals(row.quantity(), row.costAmount(), row.currency(), revenue);
        }

        private LedgerTotals add(LedgerTotals other) {
            Money summedRevenue;
            if (revenue.isPriced() && other.revenue.isPriced()) {
                summedRevenue = revenue.plus(other.revenue);
            } else {
                summedRevenue = Money.unpriced(currency);
            }
            return new LedgerTotals(quantity.add(other.quantity), cost.add(other.cost), currency,
                summedRevenue);
        }
    }

    /**
     * The comparison document.
     *
     * @param note carried on the record rather than left to the client, because the note is the part
     *             that stops a reader treating the legacy column as an alternative truth
     */
    public record ComparisonReport(
            UUID organizationId,
            UUID projectId,
            LocalDate fromDate,
            LocalDate toDate,
            LegacyTotals legacy,
            List<LegacyComparisonRow> rows,
            List<Map<String, Object>> models,
            List<Map<String, Object>> runs,
            String note
    ) {
    }

    private static final String NOTE =
        "ai_usage.estimatedCost is NOT finance truth and is never migrated into the ledger as an amount "
            + "(ADR 0009 section 3). It is read here as a comparison source only, and the difference is "
            + "reported as a variance with a cause, not reconciled. The legacy figure was produced by an "
            + "in-code price table that returns 0 for a model it does not know, so a legacy zero means 'no "
            + "price', not 'free'. The ledger figure is a sum over immutable usage_events priced by the "
            + "rate-card version effective on the usage date. Only the AI cost figures are comparable: the "
            + "conversation, CSAT and workload metrics the legacy analytics service reported have no "
            + "equivalent here, and inventing one would put a dashboard counter into a finance total.";
}
