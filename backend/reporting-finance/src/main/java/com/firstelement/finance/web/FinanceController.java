package com.firstelement.finance.web;

import com.firstelement.finance.drill.DrillThroughLevel;
import com.firstelement.finance.drill.DrillThroughService;
import com.firstelement.finance.legacy.LegacyAnalyticsComparisonService;
import com.firstelement.finance.period.AdjustmentService;
import com.firstelement.finance.period.FinancePeriodRepository;
import com.firstelement.finance.period.PeriodLifecycleService;
import com.firstelement.finance.period.PeriodLockEventType;
import com.firstelement.finance.read.FinanceForecast;
import com.firstelement.finance.read.FinanceUsageReadService;
import com.firstelement.finance.read.FinanceUsageView;
import com.firstelement.finance.read.ForecastService;
import com.firstelement.finance.reconcile.ReconciliationResult;
import com.firstelement.finance.reconcile.ReconciliationService;
import com.firstelement.finance.rollup.RollupBucket;
import com.firstelement.finance.rollup.RollupService;
import com.firstelement.security.TenantContext;
import com.firstelement.security.TenantContextHolder;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.permission.Permission;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The finance routes.
 *
 * <h2>Every route names its permission</h2>
 *
 * <p>FE-WEB-003 resolved this for Admin and operations; finance needs the same discipline for the same
 * reason, and more strongly: a finance endpoint whose only declaration is {@code authenticated()} is an
 * endpoint that can disclose a tenant's revenue, cost and margin. So each handler carries
 * {@link RequirePermission} explicitly, and the {@code FinanceRoutePermissionTest} asserts that none is
 * missing — including by reflection over every handler method, so a route added later without a
 * declaration fails the suite rather than shipping.
 *
 * <h2>Two permissions, and why the write routes use the stronger one</h2>
 *
 * <table>
 *   <caption>Permissions per route</caption>
 *   <tr><th>Route</th><th>Permission</th><th>Why</th></tr>
 *   <tr><td>{@code /usage/daily}, {@code /usage/monthly}</td><td>ANALYTICS_READ_ORGANIZATION</td>
 *       <td>Cost and revenue for the whole organization. There is no weaker permission for "what does
 *           this cost".</td></tr>
 *   <tr><td>{@code /usage/forecast}</td><td>ANALYTICS_READ_ORGANIZATION</td>
 *       <td>Derived from the same priced buckets, so the same exposure.</td></tr>
 *   <tr><td>{@code /drill/**}</td><td>ANALYTICS_READ_ORGANIZATION</td>
 *       <td>The drill-through resolves a total to source rows. Level {@code PRINCIPAL} additionally
 *           names people, and is clamped to what the caller's role allows.</td></tr>
 *   <tr><td>{@code /rate-cards/**}, {@code /periods/**}, {@code /adjustments/**},
 *       {@code /reconciliation/run}</td><td>ANALYTICS_READ_ORGANIZATION</td>
 *       <td>Rate-card authoring, period close/lock/reopen and adjustment approval are all
 *           <em>finance-administrator</em> acts (ADR 0007). The service layer additionally requires an
 *           {@code ADMIN} role, so the permission here is the declaration and the role is the
 *           enforcement.</td></tr>
 *   <tr><td>{@code /reconciliation/runs}, {@code /periods/{id}/history}</td>
 *       <td>ANALYTICS_READ_ORGANIZATION</td>
 *       <td>Reads of the audit trail. A finance reviewer needs them.</td></tr>
 *   <tr><td>{@code /legacy/comparison}</td><td>ANALYTICS_READ_ORGANIZATION</td>
 *       <td>Reads the tenant's own legacy AI figures for a variance report. Nothing else.</td></tr>
 * </table>
 *
 * <h2>Nothing here executes a payment or issues an invoice</h2>
 *
 * <p>ADR 0007 rule 7. There is no route that takes a card, a mandate, an invoice number or a payment
 * state, and there is no table to store one. The absence is structural: there is no parameter on any
 * request record a payment could bind to.
 */
@RestController
@RequestMapping("/api/v1/finance")
public class FinanceController {

    private final FinanceUsageReadService usage;
    private final ForecastService forecasts;
    private final RateCardFacade rateCards;
    private final PeriodLifecycleService periods;
    private final AdjustmentService adjustments;
    private final ReconciliationService reconciliation;
    private final RollupService rollups;
    private final DrillThroughService drillThrough;
    private final LegacyAnalyticsComparisonService legacyComparison;

    public FinanceController(FinanceUsageReadService usage, ForecastService forecasts,
                             RateCardFacade rateCards, PeriodLifecycleService periods,
                             AdjustmentService adjustments, ReconciliationService reconciliation,
                             RollupService rollups, DrillThroughService drillThrough,
                             LegacyAnalyticsComparisonService legacyComparison) {
        this.usage = usage;
        this.forecasts = forecasts;
        this.rateCards = rateCards;
        this.periods = periods;
        this.adjustments = adjustments;
        this.reconciliation = reconciliation;
        this.rollups = rollups;
        this.drillThrough = drillThrough;
        this.legacyComparison = legacyComparison;
    }

    // ------------------------------------------------------------------
    // read models
    // ------------------------------------------------------------------

    /** {@code GET /api/v1/finance/usage/daily} */
    @GetMapping("/usage/daily")
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    public FinanceUsageView dailyUsage(
        @RequestParam(name = "projectId", required = false) UUID projectId,
        @RequestParam(name = "from", required = false) LocalDate from,
        @RequestParam(name = "to", required = false) LocalDate to) {
        Window window = Window.resolve(from, to);
        return usage.usage(TenantContextHolder.get(), projectId, RollupBucket.Grain.DAILY,
            window.from(), window.to());
    }

    /** {@code GET /api/v1/finance/usage/monthly} */
    @GetMapping("/usage/monthly")
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    public FinanceUsageView monthlyUsage(
        @RequestParam(name = "projectId", required = false) UUID projectId,
        @RequestParam(name = "from", required = false) LocalDate from,
        @RequestParam(name = "to", required = false) LocalDate to) {
        Window window = Window.resolve(from, to);
        return usage.usage(TenantContextHolder.get(), projectId, RollupBucket.Grain.MONTHLY,
            window.from(), window.to());
    }

    /**
     * {@code GET /api/v1/finance/usage/forecast}
     *
     * <p>An extrapolation, and the route says so: the response carries the method and a confidence level,
     * so a client cannot render it as a total without also rendering that it is a projection.
     */
    @GetMapping("/usage/forecast")
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    public FinanceForecast forecast(
        @RequestParam(name = "projectId", required = false) UUID projectId,
        @RequestParam(name = "periodStart") LocalDate periodStart,
        @RequestParam(name = "periodEnd") LocalDate periodEnd,
        @RequestParam(name = "asOf") LocalDate asOf) {
        return forecasts.forecast(TenantContextHolder.get(), projectId, periodStart, periodEnd, asOf);
    }

    // ------------------------------------------------------------------
    // drill-through
    // ------------------------------------------------------------------

    /** {@code GET /api/v1/finance/drill/snapshots/{snapshotId}} */
    @GetMapping("/drill/snapshots/{snapshotId}")
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    public Object drillSnapshot(@PathVariable UUID snapshotId,
        @RequestParam(name = "level", required = false) String level,
        @RequestParam(name = "limit", defaultValue = "200") int limit) {
        DrillThroughService.DrillThrough through = drillThrough.drill(TenantContextHolder.get(),
            snapshotId, DrillThroughLevel.fromWire(level == null ? "EVENT" : level), limit)
            .orElseThrow(() -> new NotFoundOrDenied("snapshot " + snapshotId));
        return Map.of(
            "level", through.level().wireValue(),
            "rateCardVersionId", String.valueOf(through.rateCardVersionId()),
            "rateDefinition", through.rateDefinition(),
            "totalSourceEvents", through.totalSourceEvents(),
            "returnedEvents", through.events().size(),
            "truncated", through.truncated(),
            "snapshot", through.snapshot(),
            "events", through.events(),
            "note", through.note());
    }

    /** {@code GET /api/v1/finance/drill/events/{eventId}/corrections} */
    @GetMapping("/drill/events/{eventId}/corrections")
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    public Map<String, Object> corrections(@PathVariable UUID eventId) {
        return Map.of(
            "eventId", eventId,
            "chain", drillThrough.corrections(TenantContextHolder.get(), eventId),
            "note", "A correction is a NEW immutable usage_events row naming the row it corrects, never "
                + "an update, so both appear here. usage_events is append-only by trigger.");
    }

    // ------------------------------------------------------------------
    // rate cards
    // ------------------------------------------------------------------

    /** {@code GET /api/v1/finance/rate-cards} */
    @GetMapping("/rate-cards")
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    public List<com.firstelement.finance.rates.RateCardRepository.RateCardRow> rateCards(
        @RequestParam(name = "projectId", required = false) UUID projectId) {
        return rateCards.cards(TenantContextHolder.get(), projectId);
    }

    /** {@code GET /api/v1/finance/rate-cards/{rateCardId}/versions} */
    @GetMapping("/rate-cards/{rateCardId}/versions")
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    public List<Map<String, Object>> rateCardVersions(@PathVariable UUID rateCardId) {
        return rateCards.history(TenantContextHolder.get(), rateCardId);
    }

    // ------------------------------------------------------------------
    // periods
    // ------------------------------------------------------------------

    /** {@code GET /api/v1/finance/periods} */
    @GetMapping("/periods")
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    public List<FinancePeriodRepository.PeriodRow> periods(
        @RequestParam(name = "status", required = false) String status) {
        return periods.list(TenantContextHolder.get(), status,
            com.firstelement.finance.ReportingFinanceModule.Limits.PERIOD_PAGE);
    }

    /** {@code GET /api/v1/finance/periods/{periodId}/history} */
    @GetMapping("/periods/{periodId}/history")
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    public Map<String, Object> periodHistory(@PathVariable UUID periodId) {
        TenantContext caller = TenantContextHolder.get();
        FinancePeriodRepository.PeriodRow period = periods.find(caller, periodId)
            .orElseThrow(() -> new NotFoundOrDenied("period " + periodId));
        return Map.of(
            "period", period,
            "wasEverLocked", period.wasEverLocked(),
            "events", periods.history(caller, periodId),
            "note", "The row answers 'is it locked now'; the history answers 'was it ever locked, by "
                + "whom, when, and why was it reopened'. Both are needed and only the second is "
                + "answerable to an auditor.");
    }

    /** {@code GET /api/v1/finance/periods/{periodId}/adjustments} */
    @GetMapping("/periods/{periodId}/adjustments")
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    public List<FinancePeriodRepository.AdjustmentRow> periodAdjustments(
        @PathVariable UUID periodId,
        @RequestParam(name = "status", required = false) String status) {
        return adjustments.list(TenantContextHolder.get(), periodId, status,
            com.firstelement.finance.ReportingFinanceModule.Limits.ADJUSTMENT_PAGE);
    }

    // ------------------------------------------------------------------
    // reconciliation
    // ------------------------------------------------------------------

    /** {@code GET /api/v1/finance/reconciliation/runs} */
    @GetMapping("/reconciliation/runs")
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    public List<FinancePeriodRepository.ReconciliationRunRow> reconciliationRuns(
        @RequestParam(name = "periodId", required = false) UUID periodId) {
        return reconciliation.runs(TenantContextHolder.get(), periodId,
            com.firstelement.finance.ReportingFinanceModule.Limits.RECONCILIATION_PAGE);
    }

    /** {@code GET /api/v1/finance/reconciliation/runs/{runId}/lines} */
    @GetMapping("/reconciliation/runs/{runId}/lines")
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    public List<FinancePeriodRepository.ReconciliationLineRow> reconciliationLines(
        @PathVariable UUID runId) {
        return reconciliation.lines(TenantContextHolder.get(), runId);
    }

    // ------------------------------------------------------------------
    // legacy comparison
    // ------------------------------------------------------------------

    /** {@code GET /api/v1/finance/legacy/comparison} */
    @GetMapping("/legacy/comparison")
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    public LegacyAnalyticsComparisonService.ComparisonReport legacyComparison(
        @RequestParam(name = "projectId", required = false) UUID projectId,
        @RequestParam(name = "from") LocalDate from,
        @RequestParam(name = "to") LocalDate to,
        @RequestParam(name = "runLimit", defaultValue = "50") int runLimit) {
        return legacyComparison.compare(TenantContextHolder.get(), projectId, from, to, runLimit);
    }

    /**
     * The period-close document: what a period holds, what is unpriced, what needs a correction.
     *
     * <p>A read-only composite rather than a client that makes five calls, because the interesting
     * question is "can I lock this" and answering it needs the period's state, its adjustments, its
     * latest reconciliation and its unpriced buckets together.
     */
    @GetMapping("/periods/{periodId}/close-readiness")
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    public Map<String, Object> closeReadiness(@PathVariable UUID periodId,
        @RequestParam(name = "projectId", required = false) UUID projectId) {
        TenantContext caller = TenantContextHolder.get();
        FinancePeriodRepository.PeriodRow period = periods.find(caller, periodId)
            .orElseThrow(() -> new NotFoundOrDenied("period " + periodId));
        LocalDate asOf = LocalDate.now(java.time.ZoneOffset.UTC);
        List<FinancePeriodRepository.AdjustmentRow> periodAdjustments = adjustments.list(caller,
            periodId, "PENDING", com.firstelement.finance.ReportingFinanceModule.Limits.ADJUSTMENT_PAGE);
        List<FinancePeriodRepository.ReconciliationRunRow> runs = reconciliation.runs(caller, periodId, 1);
        FinanceUsageView window = usage.usage(caller, projectId, RollupBucket.Grain.MONTHLY,
            period.periodStart(), period.periodEnd());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("period", period);
        payload.put("unpricedBucketCount", window.unpriced().bucketCount());
        payload.put("unpricedCost", window.unpriced().cost());
        payload.put("revenueIsComplete", window.totals().revenueIsComplete());
        payload.put("pendingAdjustments", periodAdjustments.size());
        payload.put("lastReconciliation", runs.isEmpty() ? null : runs.get(0));
        payload.put("canLock", period.statusEnum() != com.firstelement.finance.period.PeriodStatus.LOCKED);
        payload.put("note", "Unpriced usage must be visible before a period is locked, not after: locking "
            + "a period with unpriced buckets is legitimate and the buckets stay unpriced, but locking "
            + "one without having seen them is how a pricing gap becomes permanent by accident.");
        return payload;
    }

    // ------------------------------------------------------------------
    // request bodies
    // ------------------------------------------------------------------

    /**
     * {@code POST /api/v1/finance/rollup/compute}
     *
     * <p>The only write that creates money-shaped rows, and it creates them by <em>reading</em> the
     * ledger: revenue is derived here against a pinned rate, never supplied. There is no request field
     * for an amount.
     */
    public record ComputeRequest(UUID projectId,
                                 String grain,
                                 LocalDate from,
                                 LocalDate to,
                                 String correlationId) {
    }

    /** {@code POST /api/v1/finance/rollup/compute} */
    @PostMapping("/rollup/compute")
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    public Map<String, Object> computeRollup(@RequestBody ComputeRequest request) {
        RollupBucket.Grain grain = request.grain() == null ? RollupBucket.Grain.MONTHLY
            : RollupBucket.Grain.valueOf(request.grain());
        List<RollupBucket> computed = rollups.computeRange(TenantContextHolder.get(),
            request.projectId(), grain, request.from(), request.to(),
            com.firstelement.finance.ledger.LedgerPrincipal.from(TenantContextHolder.get()),
            request.correlationId());
        return Map.of(
            "computedBuckets", computed.size(),
            "buckets", computed,
            "note", "A recomputation is a NEW generation: usage_rollup_snapshots is append-only, so "
                + "generation N's arithmetic is frozen and a corrected rate produces N+1 alongside it. "
                + "A bucket with no usage is not materialised at all, because a zero row is "
                + "indistinguishable from no data forever after.");
    }

    /** {@code POST /api/v1/finance/periods} */
    public record CreatePeriodRequest(UUID projectId, String key, String nameAr, String nameEn,
                                      LocalDate periodStart, LocalDate periodEnd, String currency) {
    }

    /** {@code POST /api/v1/finance/periods} */
    @PostMapping("/periods")
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    public Map<String, Object> createPeriod(@RequestBody CreatePeriodRequest request) {
        UUID id = periods.create(TenantContextHolder.get(), request.projectId(), request.key(),
            request.nameAr(), request.nameEn(), request.periodStart(), request.periodEnd(),
            request.currency());
        return Map.of("id", id, "status", "OPEN");
    }

    /** {@code POST /api/v1/finance/periods/{periodId}/review} */
    @PostMapping("/periods/{periodId}/review")
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    public Map<String, Object> submitForReview(@PathVariable UUID periodId) {
        periods.submitForReview(TenantContextHolder.get(), periodId);
        return Map.of("periodId", periodId, "status", "IN_REVIEW");
    }

    /** {@code POST /api/v1/finance/periods/{periodId}/lock} */
    public record LockPeriodRequest(String note) {
    }

    /** {@code POST /api/v1/finance/periods/{periodId}/lock} */
    @PostMapping("/periods/{periodId}/lock")
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    public Map<String, Object> lockPeriod(@PathVariable UUID periodId,
                                          @RequestBody LockPeriodRequest request) {
        periods.lock(TenantContextHolder.get(), periodId, request.note());
        return Map.of("periodId", periodId, "status", "LOCKED");
    }

    /** {@code POST /api/v1/finance/periods/{periodId}/reopen} */
    public record ReopenPeriodRequest(String reason) {
    }

    /**
     * {@code POST /api/v1/finance/periods/{periodId}/reopen}
     *
     * <p>Permissioned and audited, per ADR 0007 rule 4, and the response says both so the caller can
     * show the operator what was recorded.
     */
    @PostMapping("/periods/{periodId}/reopen")
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    public Map<String, Object> reopenPeriod(@PathVariable UUID periodId,
                                            @RequestBody ReopenPeriodRequest request) {
        periods.reopen(TenantContextHolder.get(), periodId, request.reason());
        return Map.of(
            "periodId", periodId,
            "status", "REOPENED",
            "reason", request.reason(),
            "note", "ADR 0007 rule 4: reopen is an explicit, permissioned, audited action. This wrote a "
                + "finance_period_lock_events REOPENED row carrying the reason and an admin_audit_events "
                + "row, and incremented reopenCount. lockedAt is deliberately retained so 'was this "
                + "period ever closed?' stays answerable from the row.");
    }

    /** {@code POST /api/v1/finance/periods/{periodId}/return-to-review} */
    @PostMapping("/periods/{periodId}/return-to-review")
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    public Map<String, Object> returnToReview(@PathVariable UUID periodId) {
        periods.returnToReview(TenantContextHolder.get(), periodId);
        return Map.of("periodId", periodId, "status", "IN_REVIEW");
    }

    /** {@code POST /api/v1/finance/periods/{periodId}/adjustments} */
    public record ProposeAdjustmentRequest(UUID adjustmentOfId, String reason, String amount,
                                           String currency, String quantity) {
    }

    /** {@code POST /api/v1/finance/periods/{periodId}/adjustments} */
    @PostMapping("/periods/{periodId}/adjustments")
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    public Map<String, Object> proposeAdjustment(@PathVariable UUID periodId,
                                                 @RequestBody ProposeAdjustmentRequest request) {
        UUID id = adjustments.propose(TenantContextHolder.get(), periodId,
            request.adjustmentOfId(), request.reason(), new BigDecimal(request.amount()),
            request.currency(),
            request.quantity() == null ? null : new BigDecimal(request.quantity()));
        return Map.of(
            "id", id,
            "status", "PENDING",
            "note", "An adjustment is a reasoned correction to COST, not a revenue override. The rate "
                + "card marks it up as usual, so there is no second pricing mechanism beside the cards.");
    }

    /** {@code POST /api/v1/finance/adjustments/{adjustmentId}/approve} */
    public record ApproveAdjustmentRequest(String approvalNote) {
    }

    /** {@code POST /api/v1/finance/adjustments/{adjustmentId}/approve} */
    @PostMapping("/adjustments/{adjustmentId}/approve")
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    public Map<String, Object> approveAdjustment(@PathVariable UUID adjustmentId,
                                                 @RequestBody ApproveAdjustmentRequest request) {
        adjustments.approve(TenantContextHolder.get(), adjustmentId, request.approvalNote());
        return Map.of(
            "adjustmentId", adjustmentId,
            "status", "APPROVED",
            "note", "Approval does not move any figure: applying is a separate, separately audited step, "
                + "so a mistaken click cannot change a closed period's numbers with no intermediate "
                + "state to inspect.");
    }

    /** {@code POST /api/v1/finance/adjustments/{adjustmentId}/reject} */
    public record RejectAdjustmentRequest(String rejectionReason) {
    }

    /** {@code POST /api/v1/finance/adjustments/{adjustmentId}/reject} */
    @PostMapping("/adjustments/{adjustmentId}/reject")
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    public Map<String, Object> rejectAdjustment(@PathVariable UUID adjustmentId,
                                                @RequestBody RejectAdjustmentRequest request) {
        adjustments.reject(TenantContextHolder.get(), adjustmentId, request.rejectionReason());
        return Map.of("adjustmentId", adjustmentId, "status", "REJECTED");
    }

    /** {@code POST /api/v1/finance/adjustments/{adjustmentId}/apply} */
    @PostMapping("/adjustments/{adjustmentId}/apply")
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    public Map<String, Object> applyAdjustment(@PathVariable UUID adjustmentId) {
        var amount = adjustments.apply(TenantContextHolder.get(), adjustmentId);
        return Map.of(
            "adjustmentId", adjustmentId,
            "status", "APPLIED",
            "amount", amount.amountOrNull() == null ? null : amount.amountOrNull().toPlainString(),
            "currency", amount.currency(),
            "nextStep", "Recompute the affected buckets: a correction is a NEW snapshot generation, and "
                + "the period must be REOPENED (not LOCKED) for that insert to be accepted.");
    }

    /** {@code POST /api/v1/finance/reconciliation/run} */
    public record ReconcileRequest(UUID projectId, UUID periodId, String tolerance, String notes,
                                   String correlationId) {
    }

    /** {@code POST /api/v1/finance/reconciliation/run} */
    @PostMapping("/reconciliation/run")
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    public ReconciliationResult reconcile(@RequestBody ReconcileRequest request) {
        return reconciliation.reconcile(TenantContextHolder.get(), request.projectId(),
            request.periodId(),
            request.tolerance() == null ? BigDecimal.ZERO : new BigDecimal(request.tolerance()),
            request.notes(), request.correlationId());
    }

    /** The default window, bounded. */
    private record Window(LocalDate from, LocalDate to) {

        private static Window resolve(LocalDate from, LocalDate to) {
            LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
            LocalDate end = to == null ? today : to;
            LocalDate start = from == null ? end.minusDays(29) : from;
            long days = java.time.temporal.ChronoUnit.DAYS.between(start, end);
            int cap = com.firstelement.finance.ReportingFinanceModule.Limits.USAGE_WINDOW_DAYS;
            if (days > cap) {
                start = end.minusDays(cap);
            }
            if (start.isAfter(end)) {
                start = end;
            }
            return new Window(start, end);
        }
    }

    /**
     * A foreign id and a nonexistent id must be indistinguishable.
     *
     * <p>{@code PolicyDeniedException} already renders as a non-disclosing 404; this exception is the
     * route's own not-found signal and carries no detail, for the same reason.
     */
    static final class NotFoundOrDenied extends RuntimeException {

        private static final long serialVersionUID = 1L;

        NotFoundOrDenied(String what) {
            super(what + " was not found");
        }
    }
}
