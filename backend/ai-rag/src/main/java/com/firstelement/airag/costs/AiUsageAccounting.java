package com.firstelement.airag.costs;

import com.firstelement.airag.policy.AiModelGate;
import com.firstelement.airag.policy.AiPolicyRepository;
import com.firstelement.airag.policy.BudgetGate;
import com.firstelement.airag.trace.AiTraceRepository;
import com.firstelement.finance.catalog.UsageDimension;
import com.firstelement.finance.ledger.LedgerPrincipal;
import com.firstelement.finance.ledger.LedgerPrincipalType;
import com.firstelement.finance.ledger.UsageEvent;
import com.firstelement.finance.ledger.UsageEventFactory;
import com.firstelement.finance.ledger.UsageEventWriter;
import com.firstelement.finance.money.Money;
import com.firstelement.finance.rates.EffectiveRate;
import com.firstelement.finance.rates.RateCardResolver;
import com.firstelement.security.TenantContext;
import com.firstelement.security.query.TenantScope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

/**
 * AI usage accounting: the finance ledger events, the budget posting, and the cost an answer is explained
 * by.
 *
 * <h2>The cost this class reports is the RATE CARD's cost, never a provider estimate</h2>
 *
 * <p>ADR 0009 §3 and ADR 0007 Part 1, and the two together pin down the shape exactly:
 *
 * <ul>
 *   <li>{@code ai_usage.estimatedCost} is a <b>legacy comparison source only</b>. It was written by the
 *       NestJS orchestrator from an in-code price estimate — an untraceable number. Copying it into the
 *       immutable ledger would import a guess as if it were a source event, and every downstream total
 *       would inherit it.</li>
 *   <li>{@code UsageDimensionTaxonomy.AI.costKnownAtEmitTime() == false}, so
 *       {@link UsageEventFactory#aiToken} has <b>no cost parameter at all</b>. The method signature is the
 *       decision: an {@code AI_TOKEN} event carrying a hand-written dollar figure is exactly what ADR 0007
 *       rule 3 forbids.</li>
 * </ul>
 *
 * <p>So the cost is derived here, on the run, from {@link RateCardResolver} — the same resolution
 * {@code RollupService} uses — and it is written to {@code ai_provenance_records."costAmount"} (which is
 * attribution, not a ledger amount) and to {@code ai_budget_consumptions."amount"} (which is a tenant's own
 * ceiling accounting). Neither is finance truth; the finance truth is the {@code usage_events} rows plus the
 * rate-card version the rollup resolves.
 *
 * <h2>Three states, never collapsed</h2>
 *
 * <ul>
 *   <li><b>no rate on the usage date</b> → {@link AiCost#unpriced()}, and no budget posting. The usage
 *       events are still written, because <b>usage is not contingent on being priced</b>: a clinic used its
 *       tokens whether or not a rate card exists, and suppressing the events would make an unconfigured
 *       tenant look like an inactive one.</li>
 *   <li><b>a rate with no unit cost</b> → the rate prices revenue via markup but has no per-unit cost. The
 *       cost is {@link AiCost#unpriced()} and revenue is derivable from quantity × markup at rollup time.</li>
 *   <li><b>a rate with a unit cost</b> → cost = quantity × the line's {@code costAmount}. This is the only
 *       path that produces a cost amount, and it comes from a versioned, effective-dated, admin-managed
 *       rate card.</li>
 * </ul>
 *
 * <h2>Two ledger events per run</h2>
 *
 * <p>{@code AI_REQUEST} and {@code AI_TOKEN}, because the rate card prices them separately and a rollup
 * carrying only tokens could not name which of the two produced a figure.
 *
 * <h2>Same transaction as the run</h2>
 *
 * <p>{@link Propagation#MANDATORY}, inherited from {@link UsageEventWriter} and for its reason: a usage
 * event committed apart from the run it measures survives a rolled-back run, or is missing for a committed
 * one, and neither is recoverable by a later job.
 */
@Component
public class AiUsageAccounting {

    private static final Logger log = LoggerFactory.getLogger(AiUsageAccounting.class);

    private final UsageEventWriter usageEvents;
    private final RateCardResolver rateCards;
    private final AiModelGate modelGate;
    private final AiTraceRepository trace;

    public AiUsageAccounting(UsageEventWriter usageEvents,
                             RateCardResolver rateCards,
                             AiModelGate modelGate,
                             AiTraceRepository trace) {
        this.usageEvents = usageEvents;
        this.rateCards = rateCards;
        this.modelGate = modelGate;
        this.trace = trace;
    }

    /**
     * The cost of a run, as the rate card computes it.
     *
     * @param amount    the cost, or {@code null} when unpriced
     * @param currency  the rate card's currency, or the registry's when no rate was found
     * @param priced    whether an amount was produced
     * @param rateLabel the rate definition, when one resolved. Present on a priced result so the figure can
     *                  name its rate — ADR 0007 Part 1 rule 1: "a total that cannot name its rate
     *                  definition is a bug"
     */
    public record AiCost(BigDecimal amount, String currency, boolean priced, String rateLabel) {

        public static AiCost unpriced(String currency) {
            return new AiCost(null, currency, false, null);
        }
    }

    /**
     * Resolves the rate for a dimension on the usage date and computes the cost.
     *
     * <p>{@code RateCardResolver} returns {@link Optional#empty()} rather than a rate of 1.0, and this
     * method preserves that: a rate of 1.0 would produce revenue equal to cost and read as "priced at
     * break-even", which is a commercial statement this system is not entitled to make.
     */
    public AiCost resolve(TenantScope scope, UUID projectId, UsageDimension dimension, LocalDate asOf,
                          BigDecimal quantity, String registryCurrency) {
        Optional<EffectiveRate> rate = rateCards.resolve(scope, projectId, dimension, asOf);
        if (rate.isEmpty()) {
            return AiCost.unpriced(registryCurrency);
        }
        EffectiveRate resolved = rate.get();
        if (!resolved.hasUnitCost()) {
            // Priced for revenue (markup) but with no per-unit cost. The absence is reported as unpriced
            // rather than as zero, because a zero here would be a claim that the tokens were free.
            return new AiCost(null, resolved.currency(), false, resolved.rateDefinitionLabel());
        }
        BigDecimal amount = (quantity == null ? BigDecimal.ZERO : quantity)
            .multiply(resolved.costAmount())
            .setScale(Money.SCALE, RoundingMode.HALF_UP);
        return new AiCost(amount, resolved.currency(), true, resolved.rateDefinitionLabel());
    }

    /**
     * Writes every accounting row for one completed run.
     *
     * <p>One place, one resolution, so the ledger events, the budget posting and the provenance cost cannot
     * disagree. A run with no rate card still produces its two ledger events; only the budget posting is
     * skipped, because there is no amount to post against a ceiling.
     *
     * @param model the registry row, which carries the model's identity and the registered per-1k prices.
     *              The prices are recorded as <b>attribution metadata</b> on the provenance step, never as
     *              the cost — the cost came from the rate card above.
     * @param charges the budgets in force, each with its own bucket. <b>Must not be empty when a budget is
     *                configured</b>: {@code ai_budget_consumptions} is the only ledger the budget gate sums,
     *                so a run that posts nothing leaves the gate comparing forever against its own
     *                reservation and never stops a tenant who has already crossed the ceiling. Empty is
     *                correct only when no budget was in force.
     * @return the cost, so the caller writes the same figure onto the provenance row
     */
    @org.springframework.transaction.annotation.Transactional(propagation = Propagation.MANDATORY)
    public AiCost record(TenantScope scope,
                         TenantContext principal,
                         UUID runId,
                         UsageDimension dimension,
                         AiPolicyRepository.ModelRow model,
                         int inputTokens,
                         int outputTokens,
                         int latencyMs,
                         LocalDateTime occurredAt,
                         List<BudgetGate.BudgetDecision.BudgetCharge> charges) {
        UUID projectId = scope.projectId();
        LocalDate asOf = occurredAt.toLocalDate();
        // usage_events_principal_check requires a non-blank principalName; an AI_AGENT principal with no user id
        // is the normal shape, because the run is caused by an agent version rather than by a person.
        LedgerPrincipal ledgerPrincipal = new LedgerPrincipal(LedgerPrincipalType.AI_AGENT,
            principal.auditActorLabel(), principal.userId());

        AiCost requestCost = resolve(scope, projectId, UsageDimension.AI_REQUEST, asOf, BigDecimal.ONE,
            model == null ? null : model.costCurrency());
        AiCost tokenCost = resolve(scope, projectId, UsageDimension.AI_TOKEN, asOf,
            BigDecimal.valueOf(Math.max(0, inputTokens) + Math.max(0, outputTokens)),
            model == null ? null : model.costCurrency());

        LocalDate[] requestBucket = periodBucket(asOf, "MONTHLY");
        LocalDate[] tokenBucket = periodBucket(asOf, "MONTHLY");

        usageEvents.record(UsageEventFactory.aiRequest(scope.organizationId(), projectId, runId,
            occurredAt, requestBucket[0], requestBucket[1], ledgerPrincipal, requestCost.currency()));

        usageEvents.record(UsageEventFactory.aiToken(scope.organizationId(), projectId, runId,
            BigDecimal.valueOf(Math.max(0, inputTokens) + Math.max(0, outputTokens)),
            occurredAt, tokenBucket[0], tokenBucket[1], ledgerPrincipal, tokenCost.currency()));

        // The budget posting uses the TOKEN cost: the ceiling is about spend, and a request and its tokens
        // are one billable event rather than two postings that could double-charge.
        if (charges != null && tokenCost.priced()) {
            for (BudgetGate.BudgetDecision.BudgetCharge charge : charges) {
                trace.recordBudgetConsumption(scope, charge.budgetId(), runId, charge.periodStart(),
                    charge.periodEnd(), tokenCost.currency(), tokenCost.amount(), inputTokens, outputTokens,
                    occurredAt);
            }
        }

        // The legacy comparison row. Written so Phase 8's legacy-versus-new analytics has something to
        // compare against, and populated with the RATE CARD figure rather than a legacy estimate — so a
        // variance reported by LegacyAnalyticsComparisonService is a difference between two real
        // computations, not a difference between a computation and a guess.
        if (model != null && model.provider() != null) {
            trace.recordLegacyUsage(scope, runId,
                com.firstelement.airag.provider.AiProviderKind.parseOrNull(model.provider()), model.model(),
                inputTokens, outputTokens, latencyMs,
                tokenCost.amount() == null ? 0d : tokenCost.amount().doubleValue(), occurredAt);
        }

        log.info("AI usage recorded run={} inputTokens={} outputTokens={} priced={} rate={}", runId,
            inputTokens, outputTokens, tokenCost.priced(),
            tokenCost.rateLabel() == null ? "none-effective-on-" + asOf : tokenCost.rateLabel());
        return tokenCost;
    }

    /** The budget's period bucket for a usage date, so a posting lands in the right window. */
    public static LocalDate[] periodBucket(LocalDate asOf, String period) {
        if ("DAILY".equals(period)) {
            return new LocalDate[]{asOf, asOf};
        }
        return new LocalDate[]{asOf.withDayOfMonth(1), asOf.withDayOfMonth(asOf.lengthOfMonth())};
    }

    /**
     * The registry's registered prices, for attribution only.
     *
     * <p>Returned as a pair rather than folded into {@link AiCost} because they are <b>not</b> the cost.
     * A provenance row that carries "registered price per 1k: 0.0000015" and "cost: 0.0000042" lets an
     * auditor see the difference between what the tenant configured and what the rate card charged, which
     * is the comparison ADR 0009 §3 asks for.
     */
    public BigDecimal registeredInputPrice(AiPolicyRepository.ModelRow model) {
        return modelGate.inputPrice(model);
    }

    /** The registry's registered output price, or {@code null}. Attribution only. */
    public BigDecimal registeredOutputPrice(AiPolicyRepository.ModelRow model) {
        return modelGate.outputPrice(model);
    }

    /** The UTC clock the accounting stamps with. */
    public static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}