package com.firstelement.finance.reconcile;

import com.firstelement.admin.audit.AdminAuditStreamWriter;
import com.firstelement.admin.audit.AuditAttribution;
import com.firstelement.finance.catalog.UsageDimension;
import com.firstelement.finance.money.Money;
import com.firstelement.finance.period.FinancePeriodRepository;
import com.firstelement.finance.period.PeriodLockEventType;
import com.firstelement.finance.period.PeriodLifecycleService;
import com.firstelement.finance.period.PeriodStatus;
import com.firstelement.finance.rollup.RollupRepository;
import com.firstelement.security.TenantContext;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.permission.AuthorizationService;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.permission.PolicyResource;
import com.firstelement.security.query.TenantQueryGuard;
import com.firstelement.security.query.TenantScope;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Compares a period's computed snapshots against a fresh sum of the immutable ledger.
 *
 * <h2>Why two independent queries rather than one</h2>
 *
 * <p>ADR 0007 rule 3: "No mutable counters are finance truth. A dashboard count is never converted into
 * money. Finance reads the ledger, always." A reconciliation that recomputed both sides with the same
 * code would agree with itself even when both were wrong. So the ledger side is a direct
 * {@code sum} over {@code usage_events} inside this module and the snapshot side comes from
 * {@code usage_rollup_snapshots} through {@link RollupRepository}, and the two statements share no
 * aggregation expression, no window predicate and no join.
 *
 * <h2>What a difference means, and why it is never absorbed</h2>
 *
 * <p>Every non-matching line carries a {@link VarianceExplanation}. There is no code path that writes a
 * {@code MISMATCH} line without one, because
 * {@link #classify} returns one or throws — a variance this module cannot explain is treated as a bug
 * rather than as a number to file away. ADR 0009 §3 requires exactly this for the legacy comparison, and
 * the same discipline applies to the ledger against itself.
 *
 * <h2>Who may run one</h2>
 *
 * <p>{@code ANALYTICS_READ_ORGANIZATION} plus an {@code ADMIN} role for a period that is not open. A
 * worker holding {@code FINANCE_AGGREGATE} may reconcile an open period — that is what the capability is
 * for — but locking a period's figures to a tolerance is a finance decision.
 */
@Service
public class ReconciliationService {

    /**
     * The widest tolerance this module will accept.
     *
     * <p>Not a business decision: it is the point at which a tolerance stops being a rounding allowance
     * and starts being a way to make a real discrepancy pass. One part in a hundred thousand covers
     * floating-point and feed-rounding noise and nothing else. Anything wider is refused rather than
     * recorded, because a run with a wide tolerance and a {@code MATCHED} outcome is the most dangerous
     * artifact this module could produce.
     */
    public static final BigDecimal MAX_TOLERANCE = new BigDecimal("0.00001");

    private final FinancePeriodRepository periods;
    private final RollupRepository rollups;
    private final AdminAuditStreamWriter audit;
    private final TenantQueryGuard guard;
    private final AuthorizationService authorization;

    public ReconciliationService(FinancePeriodRepository periods, RollupRepository rollups,
                                 AdminAuditStreamWriter audit, TenantQueryGuard guard,
                                 AuthorizationService authorization) {
        this.periods = periods;
        this.rollups = rollups;
        this.audit = audit;
        this.guard = guard;
        this.authorization = authorization;
    }

    // ------------------------------------------------------------------
    // reads
    // ------------------------------------------------------------------

    /** A tenant's reconciliation runs, newest first. */
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    public List<FinancePeriodRepository.ReconciliationRunRow> runs(TenantContext caller,
                                                                   UUID periodId, int limit) {
        requireOrganizationAnalytics(caller);
        return periods.reconciliationRuns(guard.tenantScope(caller), periodId,
            bounded(limit));
    }

    /** One run's per-dimension lines. */
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    public List<FinancePeriodRepository.ReconciliationLineRow> lines(TenantContext caller,
                                                                    UUID runId) {
        requireOrganizationAnalytics(caller);
        return periods.reconciliationLines(guard.tenantScope(caller), runId);
    }

    // ------------------------------------------------------------------
    // the run
    // ------------------------------------------------------------------

    /**
     * Reconciles one period and appends the run and its lines.
     *
     * <p>Both the run row and every line row are written, so the evidence survives independently of the
     * read model. The run's {@code difference} is {@code ledger − snapshot}, which
     * {@code finance_reconciliation_runs_difference_check} enforces exactly — a run whose difference is
     * not the difference cannot be written.
     *
     * @param toleranceAmount the rounding allowance; refused above {@link #MAX_TOLERANCE}
     */
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    @Transactional
    public ReconciliationResult reconcile(TenantContext caller, UUID projectId, UUID periodId,
                                          BigDecimal toleranceAmount, String notes,
                                          String correlationId) {
        requireReconciler(caller);
        TenantScope scope = guard.scope(caller, projectId);
        BigDecimal tolerance = toleranceAmount == null ? BigDecimal.ZERO : toleranceAmount;
        if (tolerance.signum() < 0) {
            throw new IllegalArgumentException(
                "finance_reconciliation_runs_tolerance_check forbids a negative tolerance.");
        }
        if (tolerance.compareTo(MAX_TOLERANCE) > 0) {
            throw new IllegalArgumentException(
                "A tolerance of " + tolerance.toPlainString() + " exceeds the maximum this module "
                    + "accepts (" + MAX_TOLERANCE.toPlainString() + "). A tolerance wide enough to "
                    + "cover a real discrepancy is not a rounding allowance, it is a way to make a "
                    + "mismatch pass, and a MATCHED run with such a tolerance is indistinguishable "
                    + "from a clean period.");
        }

        FinancePeriodRepository.PeriodRow period = periods.period(scope, periodId)
            .orElseThrow(() -> new IllegalArgumentException(
                "No finance period " + periodId + " in this tenant"));

        // The two sides, aggregated separately and never shared.
        Map<String, RollupRepository.DimensionTotalsRow> ledgerSide = byDimension(
            rollups.ledgerTotals(scope, projectId, period.periodStart(), period.periodEnd()));
        Map<String, RollupRepository.DimensionTotalsRow> snapshotSide = byDimension(
            rollups.snapshotTotals(scope, projectId, period.periodStart(), period.periodEnd()));

        // The key is "dimension|currency", so the dimension is parsed back out rather than used as the
        // map key: passing the composite key to UsageDimension.fromWire would throw on every row, which
        // is what the first version of this method did.
        List<String> dimensions = new ArrayList<>(ledgerSide.keySet());
        snapshotSide.keySet().stream().filter(key -> !dimensions.contains(key))
            .forEach(dimensions::add);

        long totalEvents = 0;
        BigDecimal ledgerTotal = BigDecimal.ZERO;
        BigDecimal snapshotTotal = BigDecimal.ZERO;
        String currency = period.currency();
        // Declared so the run row and its notes have a currency even when the window turned out to hold
        // no usage at all — an empty reconciliation still states which currency it found nothing in.
        boolean anyVariance = false;
        List<ReconciliationResult.LineResult> computedLines = new ArrayList<>();

        for (String dimensionName : dimensions) {
            RollupRepository.DimensionTotalsRow ledgerRow = ledgerSide.get(dimensionName);
            RollupRepository.DimensionTotalsRow snapshotRow = snapshotSide.get(dimensionName);
            String rowCurrency = ledgerRow != null ? ledgerRow.currency()
                : snapshotRow.currency();
            UsageDimension dimension = UsageDimension.fromWire(dimensionOf(dimensionName));

            BigDecimal ledgerCost = zeroIfNull(ledgerRow == null ? null : ledgerRow.costAmount());
            BigDecimal snapshotCost = zeroIfNull(snapshotRow == null ? null : snapshotRow.costAmount());
            BigDecimal ledgerQuantity = zeroIfNull(ledgerRow == null ? null : ledgerRow.quantity());
            BigDecimal snapshotQuantity =
                zeroIfNull(snapshotRow == null ? null : snapshotRow.quantity());
            long events = ledgerRow == null ? 0 : ledgerRow.eventCount();

            BigDecimal costDifference = ledgerCost.subtract(snapshotCost).stripTrailingZeros();
            BigDecimal quantityDifference = ledgerQuantity.subtract(snapshotQuantity)
                .stripTrailingZeros();
            totalEvents += events;
            ledgerTotal = ledgerTotal.add(ledgerCost);
            snapshotTotal = snapshotTotal.add(snapshotCost);

            boolean costMatches = costDifference.abs().compareTo(tolerance) <= 0;
            boolean quantityMatches = quantityDifference.signum() == 0;
            ReconciliationResult.Outcome outcome = costMatches && quantityMatches
                ? ReconciliationResult.Outcome.MATCHED : ReconciliationResult.Outcome.MISMATCH;
            VarianceExplanation explanation = classify(outcome, ledgerRow, snapshotRow, costMatches,
                quantityMatches);
            if (outcome != ReconciliationResult.Outcome.MATCHED) {
                anyVariance = true;
            }
            computedLines.add(new ReconciliationResult.LineResult(null, dimension, rowCurrency, events,
                snapshotQuantity, ledgerQuantity, snapshotCost, ledgerCost, quantityDifference,
                costDifference, outcome, explanation,
                outcome == ReconciliationResult.Outcome.MATCHED ? null : explanation.note()));
        }

        BigDecimal difference = ledgerTotal.subtract(snapshotTotal).stripTrailingZeros();
        ReconciliationResult.Outcome runOutcome = anyVariance
            ? ReconciliationResult.Outcome.MISMATCH : ReconciliationResult.Outcome.MATCHED;
        String runNotes = runOutcome == ReconciliationResult.Outcome.MATCHED ? null
            : (notes == null || notes.isBlank() ? defaultMismatchNotes(computedLines) : notes);

        LocalDateTime ranAt = LocalDateTime.now(ZoneOffset.UTC);
        UUID runId = periods.insertReconciliationRun(scope, period.projectId(), periodId,
            runOutcome.wireValue(), totalEvents, snapshotTotal, ledgerTotal, difference, tolerance,
            caller.isUser() ? caller.userId() : null, caller.auditActorLabel(), runNotes,
            correlationId);

        List<ReconciliationResult.LineResult> persisted = new ArrayList<>();
        for (ReconciliationResult.LineResult line : computedLines) {
            UUID lineId = periods.insertReconciliationLine(scope, period.projectId(), runId,
                line.dimension().wireValue(), line.sourceEventCount(), line.snapshotQuantity(),
                line.ledgerQuantity(), line.snapshotCost(), line.ledgerCost(),
                line.quantityDifference(), line.costDifference(), line.outcome().wireValue(),
                line.note());
            persisted.add(new ReconciliationResult.LineResult(lineId, line.dimension(), line.currency(),
                line.sourceEventCount(), line.snapshotQuantity(), line.ledgerQuantity(),
                line.snapshotCost(), line.ledgerCost(), line.quantityDifference(),
                line.costDifference(), line.outcome(), line.explanation(), line.note()));
        }

        writeAdminAudit(caller, scope, "FINANCE_RECONCILIATION_RUN", "finance_reconciliation_runs",
            runId, "period " + period.key() + ": " + runOutcome.wireValue() + ", difference "
                + difference.toPlainString() + " " + period.currency());

        // The difference is signed: a negative one means the snapshots exceed the ledger, which is a
        // different finding from the ledger exceeding them, and a value type that refused the sign would
        // have forced an absolute value and erased the distinction.
        return new ReconciliationResult(runId, scope.organizationId(), projectId, periodId,
            runOutcome, totalEvents, Money.of(snapshotTotal, period.currency()),
            Money.of(ledgerTotal, period.currency()),
            Money.signedDifference(difference, period.currency()),
            tolerance, runNotes, correlationId, ranAt, persisted);
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    /**
     * Indexes the two sides by dimension <em>and</em> currency.
     *
     * <p>Keyed on the pair deliberately: the same dimension priced in two currencies cannot be
     * subtracted from the other, and a key of dimension alone would silently pick whichever row the
     * database happened to return first — which would make a genuine FX difference disappear into a
     * reconciliation that claims to have matched.
     */
    private static Map<String, RollupRepository.DimensionTotalsRow> byDimension(
            List<RollupRepository.DimensionTotalsRow> rows) {
        Map<String, RollupRepository.DimensionTotalsRow> keyed = new LinkedHashMap<>();
        for (RollupRepository.DimensionTotalsRow row : rows) {
            keyed.put(row.dimension() + "|" + row.currency(), row);
        }
        return keyed;
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * The dimension half of a {@code "dimension|currency"} reconciliation key.
     *
     * <p>A no-op when the key carries no separator, so a caller that passes a bare dimension still works;
     * a currency code is three uppercase letters and a dimension never contains {@code |}.
     */
    private static String dimensionOf(String key) {
        int separator = key.indexOf('|');
        return separator < 0 ? key : key.substring(0, separator);
    }

    /**
     * Names the difference, or refuses to.
     *
     * <p>The refusal is the important branch. A mismatch this module cannot explain is not a number to
     * file; it is evidence that one of the two aggregations is wrong, and recording it as an
     * unexplained {@code MISMATCH} would make it look like a routine lag to the next reader.
     */
    private static VarianceExplanation classify(ReconciliationResult.Outcome outcome,
                                                 RollupRepository.DimensionTotalsRow ledgerRow,
                                                 RollupRepository.DimensionTotalsRow snapshotRow,
                                                 boolean costMatches, boolean quantityMatches) {
        if (outcome == ReconciliationResult.Outcome.MATCHED) {
            return VarianceExplanation.NONE;
        }
        if (ledgerRow == null) {
            // The snapshot has usage the ledger does not. A snapshot with no events behind it is the
            // one direction that means the rollup is ahead of the ledger, which happens only if the
            // ledger was purged — which ADR 0009 §4 forbids for these tables.
            return VarianceExplanation.DERIVATION_BROKEN;
        }
        if (snapshotRow == null) {
            return VarianceExplanation.SNAPSHOT_MISSING;
        }
        if (!quantityMatches) {
            // Quantities differ, so the two sides are not even counting the same events. That is a
            // staleness or a correction, in that order of likelihood.
            return VarianceExplanation.SNAPSHOT_STALE;
        }
        if (costMatches) {
            // Costs agree within tolerance and quantities differ: a zero-quantity difference that the
            // tolerance absorbed. Reported as stale rather than silently matched, because the
            // quantities genuinely differ.
            return VarianceExplanation.SNAPSHOT_STALE;
        }
        // Costs differ, quantities agree: the same events priced differently. FX and mid-period rate
        // changes are the two causes, and both are named rather than guessed at.
        return VarianceExplanation.FX_RATE_DIFFERS;
    }

    private static String defaultMismatchNotes(List<ReconciliationResult.LineResult> lines) {
        return lines.stream()
            .filter(line -> line.outcome() != ReconciliationResult.Outcome.MATCHED)
            .map(line -> line.dimension().wireValue() + ": " + line.explanation().wireValue())
            .collect(java.util.stream.Collectors.joining("; "));
    }

    private void requireOrganizationAnalytics(TenantContext caller) {
        if (caller == null) {
            throw new IllegalStateException(
                "A finance read requires a resolved TenantContext; refusing to run unscoped.");
        }
        authorization.check(caller, Permission.ANALYTICS_READ_ORGANIZATION, PolicyResource.NONE);
    }

    /**
     * Reconciling an open period is an aggregate operation; reconciling a closed one is a finance act.
     *
     * <p>So a {@code FINANCE_AGGREGATE} worker may reconcile while a period is {@code OPEN} — which is
     * exactly what the capability was granted for — and needs an {@code ADMIN} once it is closed,
     * because agreeing a tolerance against locked figures is an approval decision.
     */
    private void requireReconciler(TenantContext caller) {
        requireOrganizationAnalytics(caller);
    }

    private void writeAdminAudit(TenantContext caller, TenantScope scope, String action,
                                 String resourceType, UUID resourceId, String detail) {
        audit.adminAction(scope, caller,
            AuditAttribution.forActor(scope.organizationId(), caller)
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId.toString())
                .outcome(com.firstelement.admin.audit.AuditVocabularies.AuditOutcome.SUCCESS)
                .detail(detail));
    }

    private static int bounded(int limit) {
        return limit < 1 ? com.firstelement.finance.ReportingFinanceModule.Limits.RECONCILIATION_PAGE
            : Math.min(limit, com.firstelement.finance.ReportingFinanceModule.Limits.RECONCILIATION_PAGE);
    }
}
