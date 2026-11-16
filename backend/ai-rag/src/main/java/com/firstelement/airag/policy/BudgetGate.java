package com.firstelement.airag.policy;

import com.firstelement.airag.provider.AiProviderKind;
import com.firstelement.security.query.TenantScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The budget gate. Runs <b>before</b> the provider call, on the estimate.
 *
 * <h2>Why before, and why on the estimate</h2>
 *
 * <p>After the call, refusing is free of nothing: the tokens are already spent, the tenant is already
 * billed, and the customer already waited. So every check here reads the <em>request</em> — the estimated
 * prompt tokens, the budget's period total plus the estimate, and the wall clock — and refuses while the
 * cost of refusing is still zero.
 *
 * <p>The consequence is that the estimate must be <em>monotone</em>: a bigger prompt must never estimate as
 * smaller, or the gate can be walked around by rephrasing a message. {@link
 * com.firstelement.airag.provider.MockTokenCounter} is monotone by construction, which is why it is the same
 * function the mock provider reports with.
 *
 * <h2>The cost reservation is from the registered provider price, and says so when it cannot</h2>
 *
 * <p>{@link #reserveFor} uses {@code ai_model_registry."inputCostPer1kTokens"} — the tenant's own registered
 * figure for the model it is about to use — to decide whether a HARD budget would be crossed <em>before</em>
 * the call. That is a <b>reservation</b>, not an amount: the recorded cost comes from the rate card in
 * {@code AiUsageAccounting}, because ADR 0007 Part 1 makes the rate card the only source of a pricing
 * decision. With no registered price the reservation is zero, which under-reserves — the safe direction of
 * the error, because it can let one run through and cannot invent a charge.
 *
 * <h2>Hard versus soft</h2>
 *
 * <ul>
 *   <li><b>HARD</b> — refuse. The run does not start.</li>
 *   <li><b>ALERT</b> — allow, and record the overrun as a fact. The tenant asked to be told, not stopped.</li>
 *   <li><b>OFF</b> — not enforceable, and the schema refuses such a row while it is enabled. It is still read
 *       so the gate can report the state rather than "no budget".</li>
 * </ul>
 *
 * <p>The effective token ceiling is the <b>lower</b> of the deployment's and the tenant's. A tenant cannot
 * raise a deployment ceiling and a deployment cannot raise a tenant's, because a budget that the louder
 * party can override is not a budget.
 */
@Component
public class BudgetGate {

    private static final Logger log = LoggerFactory.getLogger(BudgetGate.class);

    /** The cost scale both {@code ai_budget_consumptions."amount"} and the reservation use. */
    private static final int SCALE = 6;

    private final AiPolicyRepository policies;
    private final com.firstelement.airag.config.AiRagProperties properties;

    public BudgetGate(AiPolicyRepository policies, com.firstelement.airag.config.AiRagProperties properties) {
        this.policies = policies;
        this.properties = properties;
    }

    /** What was checked, so the run's trace records the decision without re-deriving it. */
    public record BudgetDecision(int effectiveMaxTokens,
                                int estimatedPromptTokens,
                                List<String> alertKeys,
                                BigDecimal reservedAmount,
                                String currency,
                                List<BudgetCharge> charges) {

        public BudgetDecision {
            alertKeys = alertKeys == null ? List.of() : List.copyOf(alertKeys);
            charges = charges == null ? List.of() : List.copyOf(charges);
        }

        /** Kept for the paths that have no budget in force. */
        public BudgetDecision(int effectiveMaxTokens, int estimatedPromptTokens, List<String> alertKeys,
                              BigDecimal reservedAmount, String currency) {
            this(effectiveMaxTokens, estimatedPromptTokens, alertKeys, reservedAmount, currency, List.of());
        }

        public boolean withinLimits() {
            return alertKeys.isEmpty();
        }

        /**
         * One budget this run's spend must be posted to.
         *
         * <p>Exists because {@link #check} and the accounting write must agree. The gate decides whether a
         * budget is crossed by summing {@code ai_budget_consumptions}; if nothing ever writes to that
         * table, the sum is zero forever, the gate can only ever compare against its own reservation, and a
         * tenant who has already spent their whole month is never stopped. A budget that cannot accumulate
         * is not a budget.
         *
         * <p>One charge per in-force budget, not one per run, because each budget has its own ceiling and
         * each must see the spend to be able to stop the next run. A tenant with a DAILY and a MONTHLY
         * budget gets two rows for one run; the unique index is
         * {@code (organizationId, budgetId, periodStart, aiRunId)}, so each is written once even when the
         * dispatcher delivers the accounting twice.
         *
         * @param budgetId     the {@code ai_budgets} row
         * @param periodStart  the bucket's first day, written explicitly rather than derived on read
         * @param periodEnd    the bucket's last day, inclusive — the schema's {@code periodEnd >= periodStart}
         */
        public record BudgetCharge(UUID budgetId, LocalDate periodStart, LocalDate periodEnd) {
        }
    }

    /**
     * Checks the prompt estimate against the token, cost and time ceilings.
     *
     * @throws AiRefusedException with {@link AiRefusalReason#PROMPT_TOO_LARGE},
     *         {@link AiRefusalReason#POLICY_TOKEN_LIMIT}, {@link AiRefusalReason#BUDGET_HARD_STOP} or
     *         {@link AiRefusalReason#RUN_DEADLINE}
     */
    public BudgetDecision check(TenantScope scope,
                                AiPolicySnapshot snapshot,
                                List<String> promptSections,
                                LocalDateTime runStartedAt,
                                BigDecimal inputCostPer1kTokens,
                                String costCurrency) {
        int estimated = com.firstelement.airag.provider.MockTokenCounter.countAll(promptSections);

        int deploymentCeiling = properties.getMaxPromptTokensPerRun();
        if (estimated > deploymentCeiling) {
            throw AiRefusedException.of(AiRefusalReason.PROMPT_TOO_LARGE,
                "estimated " + estimated + " prompt tokens against a deployment ceiling of "
                    + deploymentCeiling);
        }

        Integer tenantCeiling = snapshot == null ? null : snapshot.maxTokensPerRun();
        if (tenantCeiling != null && tenantCeiling > 0 && estimated > tenantCeiling) {
            throw AiRefusedException.of(AiRefusalReason.POLICY_TOKEN_LIMIT,
                "estimated " + estimated + " prompt tokens against the tenant ceiling of " + tenantCeiling);
        }

        long elapsed = elapsedMillis(runStartedAt);
        if (elapsed >= properties.getRunWallClockMillis()) {
            throw AiRefusedException.of(AiRefusalReason.RUN_DEADLINE,
                elapsed + "ms elapsed against a " + properties.getRunWallClockMillis() + "ms run ceiling");
        }

        return costDecision(scope, snapshot, estimated, inputCostPer1kTokens, costCurrency);
    }

    /**
     * The money half of the gate.
     *
     * <p>The period window is derived from the usage date in <b>UTC</b> — the session's date, which is what
     * {@code timestamp without time zone} columns are compared against — and both ends are computed rather
     * than assumed, because a monthly budget whose window starts on the 1st of the previous month silently
     * counts two months of spend.
     */
    private BudgetDecision costDecision(TenantScope scope,
                                        AiPolicySnapshot snapshot,
                                        int estimatedTokens,
                                        BigDecimal inputCostPer1kTokens,
                                        String costCurrency) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDate asOf = now.toLocalDate();
        List<AiPolicyRepository.BudgetRow> budgets = policies.budgets(scope, asOf);
        if (budgets.isEmpty()) {
            // No budget is not a zero budget. The run proceeds and the trace records that no ceiling was in
            // force, which is a configuration fact an operator can see.
            return new BudgetDecision(effectiveMaxTokens(snapshot), estimatedTokens, List.of(), null, null);
        }

        Map<UUID, AiPolicyRepository.BudgetSpendRow> spend = spendFor(scope, budgets, now);
        List<String> alerts = new ArrayList<>();
        List<String> hardStops = new ArrayList<>();
        List<BudgetDecision.BudgetCharge> charges = new ArrayList<>();
        BigDecimal reserved = null;
        String currency = null;

        for (AiPolicyRepository.BudgetRow budget : budgets) {
            AiPolicyRepository.BudgetSpendRow spent = spend.get(budget.id());
            BigDecimal already = spent == null || spent.spent() == null ? BigDecimal.ZERO : spent.spent();
            BigDecimal reserve = reserveFor(estimatedTokens, inputCostPer1kTokens);
            BigDecimal projected = already.add(reserve);

            if ("OFF".equals(budget.enforcement())) {
                alerts.add(budget.key() + ":enforcement-off");
                // Still posted. An OFF budget is not enforceable, but it is the tenant's only record of what
                // they WOULD have spent, and a monitor that never records the spend is not a monitor.
                charges.add(charge(budget, asOf));
                continue;
            }
            if (projected.compareTo(budget.limitAmount()) > 0) {
                if ("HARD".equals(budget.enforcement())) {
                    hardStops.add(budget.key());
                } else {
                    alerts.add(budget.key() + ":over-ceiling");
                }
            }
            charges.add(charge(budget, asOf));
            if (reserved == null) {
                reserved = reserve;
                // ai_budgets."currency" is NOT NULL, so this is the normal answer; the registry's currency is
                // the fallback for a tenant that registered a price before configuring a budget.
                currency = budget.currency() != null ? budget.currency() : costCurrency;
            }
        }

        if (!hardStops.isEmpty()) {
            log.info("AI budget ceiling reached count={}", hardStops.size());
            throw AiRefusedException.of(AiRefusalReason.BUDGET_HARD_STOP,
                "budget(s) " + hardStops + " are at or over their ceiling with enforcement=HARD");
        }
        return new BudgetDecision(effectiveMaxTokens(snapshot), estimatedTokens, List.copyOf(alerts),
            reserved, currency, List.copyOf(charges));
    }

    /** A budget's bucket for a usage date, so the posting lands in the window the gate summed over. */
    private static BudgetDecision.BudgetCharge charge(AiPolicyRepository.BudgetRow budget, LocalDate asOf) {
        return new BudgetDecision.BudgetCharge(budget.id(), periodStart(budget, asOf).toLocalDate(),
            periodEnd(budget, asOf).toLocalDate().minusDays(1));
    }

    /** The sum per budget over the widest window any of them needs. */
    private Map<UUID, AiPolicyRepository.BudgetSpendRow> spendFor(TenantScope scope,
                                                                  List<AiPolicyRepository.BudgetRow> budgets,
                                                                  LocalDateTime now) {
        LocalDateTime earliest = now;
        LocalDateTime latest = now;
        for (AiPolicyRepository.BudgetRow budget : budgets) {
            LocalDateTime start = periodStart(budget, now.toLocalDate());
            if (start.isBefore(earliest)) {
                earliest = start;
            }
            LocalDateTime end = periodEnd(budget, now.toLocalDate());
            if (end.isAfter(latest)) {
                latest = end;
            }
        }
        Map<UUID, AiPolicyRepository.BudgetSpendRow> byBudget = new LinkedHashMap<>();
        for (AiPolicyRepository.BudgetSpendRow row : policies.budgetSpend(scope, earliest, latest)) {
            byBudget.put(row.budgetId(), row);
        }
        return byBudget;
    }

    /**
     * The start of a budget's period.
     *
     * <p>DAILY starts at the usage date; MONTHLY starts on the first of the month. Both ends inclusive, the
     * same convention {@code finance_periods} uses, so a spend on the last day of a month is counted rather
     * than falling out of the window.
     */
    static LocalDateTime periodStart(AiPolicyRepository.BudgetRow budget, LocalDate asOf) {
        if ("DAILY".equals(budget.period())) {
            return asOf.atStartOfDay();
        }
        return asOf.withDayOfMonth(1).atStartOfDay();
    }

    /** The exclusive end of a budget's period, one day past its last day. */
    static LocalDateTime periodEnd(AiPolicyRepository.BudgetRow budget, LocalDate asOf) {
        LocalDate lastDay = "DAILY".equals(budget.period()) ? asOf
            : asOf.withDayOfMonth(asOf.lengthOfMonth());
        return lastDay.plusDays(1).atStartOfDay();
    }

    /**
     * The amount to reserve for the estimated prompt, from the registered per-1k price.
     *
     * <p>Rounded {@code HALF_UP} to six decimals because {@code ai_budget_consumptions."amount"} is
     * {@code numeric(20,6)}: a reservation that does not fit the column's scale would silently round, and
     * the ledger and the budget would disagree in the last digit on every row.
     */
    static BigDecimal reserveFor(int estimatedTokens, BigDecimal inputCostPer1kTokens) {
        if (estimatedTokens <= 0 || inputCostPer1kTokens == null) {
            return BigDecimal.ZERO.setScale(SCALE, java.math.RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(estimatedTokens)
            .divide(BigDecimal.valueOf(1000), 8, java.math.RoundingMode.HALF_UP)
            .multiply(inputCostPer1kTokens)
            .setScale(SCALE, java.math.RoundingMode.HALF_UP);
    }

    /** The effective prompt ceiling: the lower of the deployment's and the tenant's. */
    public int effectiveMaxTokens(AiPolicySnapshot snapshot) {
        int deployment = properties.getMaxPromptTokensPerRun();
        Integer tenant = snapshot == null ? null : snapshot.maxTokensPerRun();
        if (tenant == null || tenant <= 0) {
            return deployment;
        }
        return Math.min(deployment, tenant);
    }

    /** The effective tool-call ceiling: the lower of the deployment's and the tenant's. */
    public int effectiveMaxToolCalls(AiPolicySnapshot snapshot) {
        int deployment = properties.getMaxToolCallsPerRun();
        Integer tenant = snapshot == null ? null : snapshot.maxToolCallsPerRun();
        if (tenant == null || tenant < 0) {
            return deployment;
        }
        return Math.min(deployment, tenant);
    }

    /** The provider kinds a snapshot would try. Exposed for the trace. */
    public static List<AiProviderKind> chainOf(AiPolicySnapshot snapshot) {
        return snapshot == null ? List.of() : snapshot.failoverChain();
    }

    private static long elapsedMillis(LocalDateTime startedAt) {
        if (startedAt == null) {
            return 0L;
        }
        return Math.max(0L, java.time.Duration.between(startedAt, LocalDateTime.now(ZoneOffset.UTC))
            .toMillis());
    }
}