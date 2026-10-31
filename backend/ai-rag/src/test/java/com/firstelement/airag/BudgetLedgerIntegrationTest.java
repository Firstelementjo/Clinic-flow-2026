package com.firstelement.airag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.airag.orchestration.AiOrchestrator;
import com.firstelement.airag.orchestration.AiRunResult;
import com.firstelement.airag.policy.AiPolicySnapshot;
import com.firstelement.airag.policy.AiRefusalReason;
import com.firstelement.airag.policy.AiRefusedException;
import com.firstelement.airag.principal.AiPrincipalScope;
import com.firstelement.security.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Budgets as a <b>ledger</b>, not as a per-run comparison.
 *
 * <h2>The defect this suite was written for</h2>
 *
 * <p>{@code ai_budget_consumptions} is documented in {@code V20261005100000} as "a SUM over immutable
 * rows, never a mutable counter", and {@code BudgetGate} reads it to decide whether a HARD ceiling is
 * crossed. An earlier version of the orchestrator posted nothing to it — it passed {@code null} for the
 * budget id — so the sum was zero forever, the gate could only compare against its own per-run reservation,
 * and a tenant who had already spent their whole month was never stopped. A budget that cannot accumulate
 * is not a budget.
 *
 * <p>So the first tests here assert the ordinary thing: two runs against the same budget produce two
 * postings, and the second run sees the first run's spend. Without those rows the rest of this file would
 * pass for the wrong reason.
 *
 * <h2>Why the ceilings look absurd</h2>
 *
 * <p>{@code ai_budgets."limitAmount"} is {@code numeric(20,6)}, so the smallest representable positive
 * ceiling is {@code 0.000001} — anything smaller rounds to zero and is refused by
 * {@code ai_budgets_limit_check}. The ceiling therefore cannot be made tiny; the <em>projection</em> is made
 * large instead, by registering a per-1k price of 1.0 against a prompt of a few hundred tokens. Every test
 * here that expects a refusal says so in a comment, so nobody later "simplifies" the price to a realistic
 * one and leaves a test that passes without exercising its refusal.
 */
@SpringBootTest(classes = AiRagTestApplication.class)
class BudgetLedgerIntegrationTest extends AiIntegrationTestBase {

    @Autowired
    private AiFixture fixture;

    @Autowired
    private AiOrchestrator orchestrator;

    @Autowired
    private com.firstelement.airag.policy.BudgetGate gate;

    private UUID knowledgeBase;
    private AiFixture.AgentVersion agent;
    private UUID conversation;
    private UUID triggerMessage;

    @BeforeEach
    void seed() {
        fixture.reset();
        knowledgeBase = fixture.knowledgeBase(AiFixture.ORG_A, AiFixture.PROJECT_A, "KB");
        agent = fixture.agentWithVersion(AiFixture.ORG_A, AiFixture.PROJECT_A, AiFixture.ADMIN_A,
            "MOCK", "mock-deterministic-v1", "[]", "[\"" + knowledgeBase + "\"]", 0.0d,
            "أجب من المصادر فقط");
        fixture.policy(AiFixture.ORG_A, AiFixture.PROJECT_A, "default", 4000, 4, "STRICT_CLINICAL",
            AiFixture.ADMIN_A);
        fixture.model(AiFixture.ORG_A, AiFixture.PROJECT_A, "MOCK", "mock-deterministic-v1", true,
            "LOCAL", false, "USD", "0.00000150", "0.00000600");
        conversation = fixture.conversation(AiFixture.ORG_A, AiFixture.PROJECT_A, "AI");
        triggerMessage = fixture.customerMessage(AiFixture.ORG_A, AiFixture.PROJECT_A, conversation,
            "ما ساعات العمل؟");

        UUID document = fixture.document(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, "ساعات",
            "READY");
        fixture.chunk(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, document, 0,
            "ساعات العمل من الأحد إلى الخميس.", "ساعات العمل");
    }

    @Nested
    @DisplayName("the ledger is written")
    class Ledger {

        @Test
        @DisplayName("a priced run posts exactly one consumption against the budget in force")
        void aRunPostsToItsBudget() {
            rateCard();
            UUID budget = fixture.hardBudget(AiFixture.ORG_A, AiFixture.PROJECT_A, "daily", "USD",
                "1000000.000000", "DAILY");

            AiRunResult result = run();

            assertThat(result.hasAnswer()).isTrue();
            assertThat(consumptionCount(budget))
                .as("without this row the gate sums nothing and can never stop a tenant")
                .isEqualTo(1);
            String amount = fixture.text("""
                select c."amount"::text from ai_budget_consumptions c where c."aiRunId" = :run
                """, Map.of("run", result.runId()));
            assertThat(new BigDecimal(amount))
                .as("the amount is the RATE CARD's figure, not a reservation and not zero")
                .isGreaterThan(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("two runs post two rows, and the second run's budget check sees the first run's spend")
        void spendAccumulatesAcrossRuns() {
            rateCard();
            UUID budget = fixture.hardBudget(AiFixture.ORG_A, AiFixture.PROJECT_A, "daily", "USD",
                "1000000.000000", "DAILY");

            run();
            run();

            assertThat(consumptionCount(budget)).isEqualTo(2);
            String total = fixture.text("""
                select sum(c."amount")::text from ai_budget_consumptions c where c."budgetId" = :budget
                """, Map.of("budget", budget));
            assertThat(new BigDecimal(total))
                .as("the gate's SUM has to be growing, or a ceiling is never reached")
                .isGreaterThan(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("a re-posted run is suppressed by the unique index, so a retry cannot double-charge")
        void duplicatePostingIsSuppressed() {
            rateCard();
            UUID budget = fixture.hardBudget(AiFixture.ORG_A, AiFixture.PROJECT_A, "daily", "USD",
                "1000000.000000", "DAILY");
            run();
            UUID runId = lastRunId();

            // ai_budget_consumptions_budget_run_period_key is UNIQUE on
            // (organizationId, budgetId, periodStart, aiRunId) and the insert carries ON CONFLICT DO
            // NOTHING, so a second accounting write for the same run is a no-op rather than a second charge.
            // Reproduced by re-posting the same row directly, because the dispatcher is what retries and it
            // is not in this module's test scope.
            fixture.jdbcUpdate("""
                insert into ai_budget_consumptions ("id", "organizationId", "projectId", "budgetId",
                    "aiRunId", "periodStart", "periodEnd", "currency", "amount", "inputTokens", "outputTokens",
                    "recordedAt", "createdAt")
                values (gen_random_uuid(), :org, :project, :budget, :run,
                    cast(:start as date), cast(:start as date), 'USD', cast(1.0 as numeric), 10, 10,
                    :now, :now)
                on conflict ("organizationId", "budgetId", "periodStart", "aiRunId") do nothing
                """, Map.of("org", AiFixture.ORG_A, "project", AiFixture.PROJECT_A, "budget", budget,
                "run", runId, "start", today(), "now", AiFixture.now()));

            assertThat(consumptionCount(budget)).isEqualTo(1);
        }

        @Test
        @DisplayName("a run with NO budget in force posts nothing, and still answers")
        void noBudgetMeansNoPosting() {
            rateCard();

            AiRunResult result = run();

            assertThat(result.hasAnswer()).isTrue();
            // "No budget" is not "no spend"; it is "no ceiling", and the ledger events still exist.
            assertThat(fixture.count("select 1 from ai_budget_consumptions c where c.\"organizationId\" = :org",
                Map.of("org", AiFixture.ORG_A))).isZero();
            assertThat(usageEventCount("AI_REQUEST")).isEqualTo(1);
            assertThat(usageEventCount("AI_TOKEN")).isEqualTo(1);
        }

        @Test
        @DisplayName("an UNPRICED run posts no consumption, rather than posting a zero")
        void unpricedRunPostsNoConsumption() {
            // No rate card. The amount would be null, and a zero here is a claim that the tokens were free.
            UUID budget = fixture.hardBudget(AiFixture.ORG_A, AiFixture.PROJECT_A, "daily", "USD",
                "1000000.000000", "DAILY");

            AiRunResult result = run();

            assertThat(result.hasAnswer()).isTrue();
            assertThat(result.costAmount()).isNull();
            assertThat(consumptionCount(budget)).isZero();
        }

        @Test
        @DisplayName("a tenant's DAILY and MONTHLY budgets each get their own posting for one run")
        void everyBudgetInForceIsCharged() {
            rateCard();
            UUID daily = fixture.hardBudget(AiFixture.ORG_A, AiFixture.PROJECT_A, "daily", "USD",
                "1000000.000000", "DAILY");
            UUID monthly = fixture.hardBudget(AiFixture.ORG_A, AiFixture.PROJECT_A, "monthly", "USD",
                "1000000.000000", "MONTHLY");

            run();

            // Each budget has its own ceiling and each must see the spend to be able to stop the next run.
            assertThat(consumptionCount(daily)).isEqualTo(1);
            assertThat(consumptionCount(monthly)).isEqualTo(1);
        }

        @Test
        @DisplayName("each posting names its OWN bucket, so a monthly budget is not credited with a day's spend")
        void eachPostingCarriesItsOwnBucket() {
            rateCard();
            UUID daily = fixture.hardBudget(AiFixture.ORG_A, AiFixture.PROJECT_A, "daily", "USD",
                "1000000.000000", "DAILY");
            UUID monthly = fixture.hardBudget(AiFixture.ORG_A, AiFixture.PROJECT_A, "monthly", "USD",
                "1000000.000000", "MONTHLY");

            AiRunResult result = run();

            String dailyBucket = fixture.text("""
                select c."periodStart"::text || '/' || c."periodEnd"::text
                  from ai_budget_consumptions c where c."budgetId" = :budget and c."aiRunId" = :run
                """, Map.of("budget", daily, "run", result.runId()));
            String monthlyBucket = fixture.text("""
                select c."periodStart"::text || '/' || c."periodEnd"::text
                  from ai_budget_consumptions c where c."budgetId" = :budget and c."aiRunId" = :run
                """, Map.of("budget", monthly, "run", result.runId()));

            assertThat(dailyBucket).isEqualTo(today() + "/" + today());
            // The schema's periodEnd >= periodStart, and the month is written inclusively -- the convention
            // ai_policy_repository's own reader uses, so a spend on the last day is counted rather than
            // falling out of the window.
            assertThat(monthlyBucket).isEqualTo(monthStart() + "/" + monthEnd());
        }
    }

    @Nested
    @DisplayName("a HARD ceiling is reached by ACCUMULATED spend, not by the reservation alone")
    class HardStop {

        @Test
        @DisplayName("the first run is allowed and the SECOND is refused, because the first spent the budget")
        void accumulatedSpendStopsTheNextRun() {
            rateCard();
            // A price of 1.0 per 1k tokens makes one run cost roughly 0.1-0.2. The ceiling is 0.000001, so
            // the very first reservation would also cross it -- which would make this test pass without ever
            // exercising accumulation. It is therefore staged in two phases below.
            fixture.jdbcUpdate("""
                update ai_model_registry set "inputCostPer1kTokens" = cast(0.0001 as numeric)
                 where "organizationId" = :org and "model" = 'mock-deterministic-v1'
                """, Map.of("org", AiFixture.ORG_A));
            UUID budget = fixture.hardBudget(AiFixture.ORG_A, AiFixture.PROJECT_A, "daily", "USD",
                "0.000001", "DAILY");

            // Phase 1: a generous ceiling lets exactly one run through, and its spend is booked.
            fixture.jdbcUpdate("""
                update ai_budgets set "limitAmount" = cast(1000000.000000 as numeric) where "id" = :budget
                """, Map.of("budget", budget));
            run();
            assertThat(consumptionCount(budget)).isEqualTo(1);

            // Phase 2: the ceiling drops below what has ALREADY been spent. Only a gate that reads the
            // ledger can refuse here; a gate that compares against the reservation alone would let this run
            // through, because this run's own reservation is small.
            fixture.jdbcUpdate("""
                update ai_budgets set "limitAmount" = cast(0.000001 as numeric) where "id" = :budget
                """, Map.of("budget", budget));

            assertThatThrownBy(() -> run())
                .isInstanceOf(AiRefusedException.class)
                .satisfies(thrown -> assertThat(((AiRefusedException) thrown).reason())
                    .isEqualTo(AiRefusalReason.BUDGET_HARD_STOP));
        }

        @Test
        @DisplayName("an ALERT budget over its ceiling still runs, and keeps posting")
        void alertBudgetRunsAndKeepsAccruing() {
            rateCard();
            UUID budget = fixture.alertBudget(AiFixture.ORG_A, AiFixture.PROJECT_A, "soft", "USD",
                "0.000001");

            AiRunResult result = run();

            assertThat(result.hasAnswer()).isTrue();
            // A monitor that never records the spend is not a monitor: the tenant asked to be told what they
            // WOULD have spent, and that figure has to exist.
            assertThat(consumptionCount(budget)).isEqualTo(1);
        }

        @Test
        @DisplayName("a budget in another project does not gate this project")
        void budgetsAreProjectScoped() {
            rateCard();
            // A HARD budget whose reservation would stop any run at all, seeded into the OTHER project.
            fixture.jdbcUpdate("""
                update ai_model_registry set "inputCostPer1kTokens" = cast(1.0 as numeric)
                 where "organizationId" = :org and "model" = 'mock-deterministic-v1'
                """, Map.of("org", AiFixture.ORG_A));
            fixture.hardBudget(AiFixture.ORG_A, AiFixture.PROJECT_A2, "other", "USD", "0.000001", "DAILY");

            assertThat(run().hasAnswer()).isTrue();
        }

        @Test
        @DisplayName("a budget in another TENANT does not gate this tenant")
        void budgetsAreTenantScoped() {
            rateCard();
            fixture.jdbcUpdate("""
                update ai_model_registry set "inputCostPer1kTokens" = cast(1.0 as numeric)
                 where "organizationId" = :org and "model" = 'mock-deterministic-v1'
                """, Map.of("org", AiFixture.ORG_A));
            // Tenant B needs a budget of its own to exist at all; the composite FK on budgetId carries the
            // organization, so a budget row is always inside one tenant.
            fixture.hardBudget(AiFixture.ORG_B, AiFixture.PROJECT_B, "b-side", "USD", "0.000001", "DAILY");

            assertThat(run().hasAnswer()).isTrue();
        }
    }

    @Nested
    @DisplayName("the token ceiling")
    class Tokens {

        @Test
        @DisplayName("the effective token ceiling is the LOWER of the deployment's and the tenant's")
        void theEffectiveCeilingIsTheFloor() {
            // Asserted on the gate rather than through a run, because the property IS the arithmetic and a
            // run only reports it indirectly. A budget either party can raise is not a budget: a tenant must
            // not be able to exceed the deployment, and a deployment must not be able to overrule a tenant
            // who set a tighter limit of their own.
            AiPolicySnapshot generous = snapshot(8_000);
            AiPolicySnapshot tight = snapshot(50);

            assertThat(gate.effectiveMaxTokens(generous))
                .as("the tenant asked for more than the deployment allows, so the deployment binds")
                .isEqualTo(gate.effectiveMaxTokens(null));
            assertThat(gate.effectiveMaxTokens(tight))
                .as("the tenant asked for less, so the tenant binds")
                .isEqualTo(50);
            assertThat(gate.effectiveMaxTokens(snapshot(null)))
                .as("a policy that names no ceiling inherits the deployment's")
                .isEqualTo(gate.effectiveMaxTokens(null));
        }

        @Test
        @DisplayName("the tool-call ceiling follows the same floor rule")
        void theToolCeilingIsAlsoAFloor() {
            assertThat(gate.effectiveMaxToolCalls(snapshotTools(8_000))).isEqualTo(4);
            assertThat(gate.effectiveMaxToolCalls(snapshotTools(2))).isEqualTo(2);
            assertThat(gate.effectiveMaxToolCalls(snapshotTools(null))).isEqualTo(4);
        }

        @Test
        @DisplayName("a run inside the ceiling records the BUDGET_CHECKED step, so the decision is auditable")
        void theBudgetDecisionIsRecorded() {
            AiRunResult result = run();

            String budgetStep = fixture.text("""
                select s."detail"::text from ai_run_steps s
                 where s."runId" = :run and s."detail"->>'stage' = 'BUDGET_CHECKED'
                """, Map.of("run", result.runId()));

            assertThat(budgetStep)
                .as("a run records WHICH gate cleared it, or an operator cannot tell a stop from a pass")
                .contains("BUDGET_CHECKED")
                .contains("within-limits");
        }

        @Test
        @DisplayName("a prompt above the tenant's ceiling is refused BEFORE the provider is called")
        void tenantCeilingRefusesBeforeTheCall() {
            UUID document = fixture.document(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, "طويل",
                "READY");
            fixture.chunk(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, document, 0,
                "محتوى طويل جدا ".repeat(400), "محتوى طويل جدا");
            fixture.jdbcUpdate("""
                update ai_policies set "maxTokensPerRun" = 5 where "organizationId" = :org
                """, Map.of("org", AiFixture.ORG_A));

            assertThatThrownBy(() -> run())
                .isInstanceOf(AiRefusedException.class)
                .satisfies(thrown -> assertThat(((AiRefusedException) thrown).reason())
                    .isEqualTo(AiRefusalReason.POLICY_TOKEN_LIMIT));
            // Refused before the call, so nothing was spent and nothing was posted.
            assertThat(fixture.count("select 1 from usage_events e where e.\"organizationId\" = :org",
                Map.of("org", AiFixture.ORG_A))).isZero();
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * An ACTIVE rate card effective now, with an {@code AI_TOKEN} unit cost.
     *
     * <p>Validity starts at {@code WIDE_FROM} because {@code RateCardResolver} resolves against today's UTC
     * date; a card that expired in March would make every cost assertion below assert "unpriced" instead.
     */
    private void rateCard() {
        UUID versionId = fixture.activeRateCardVersion(AiFixture.ORG_A, AiFixture.PROJECT_A, 1, "USD",
            "2.000000", AiFixture.WIDE_FROM, null, "seeded AI rate", AiFixture.ADMIN_A);
        fixture.rateCardLine(AiFixture.ORG_A, AiFixture.PROJECT_A, versionId, "AI_TOKEN", "token",
            "0.00010000", null, "USD");
        fixture.rateCardLine(AiFixture.ORG_A, AiFixture.PROJECT_A, versionId, "AI_REQUEST", "request",
            "0.00010000", null, "USD");
    }

    private AiRunResult run() {
        TenantContext principal = AiPrincipalScope.agentScopedTo(AiFixture.ORG_A, AiFixture.PROJECT_A);
        return orchestrator.run(new AiOrchestrator.RunRequest(principal, AiFixture.PROJECT_A, conversation,
            triggerMessage, agent.agentId(), agent.versionId(), "ما ساعات العمل؟"));
    }

    private UUID lastRunId() {
        return fixture.uuidValue("""
            select r."id" from ai_runs r where r."organizationId" = :org
             order by r."startedAt" desc, r."id" desc limit 1
            """, Map.of("org", AiFixture.ORG_A));
    }

    private int consumptionCount(UUID budgetId) {
        return fixture.count("""
            select 1 from ai_budget_consumptions c
             where c."organizationId" = :org and c."budgetId" = :budget
            """, Map.of("org", AiFixture.ORG_A, "budget", budgetId));
    }

    /** A policy snapshot with only the token ceiling set, for the floor arithmetic. */
    private static AiPolicySnapshot snapshot(Integer maxTokens) {
        return new AiPolicySnapshot(UUID.randomUUID(), UUID.randomUUID(), "default", "STRICT_CLINICAL",
            "AI_PROVENANCE", maxTokens, 4,
            List.of(com.firstelement.airag.provider.AiProviderKind.MOCK), "LOCAL_ONLY",
            true, true, true, true, 1);
    }

    /** The same, with only the tool ceiling set. */
    private static AiPolicySnapshot snapshotTools(Integer maxToolCalls) {
        return new AiPolicySnapshot(UUID.randomUUID(), UUID.randomUUID(), "default", "STRICT_CLINICAL",
            "AI_PROVENANCE", 4000, maxToolCalls,
            List.of(com.firstelement.airag.provider.AiProviderKind.MOCK), "LOCAL_ONLY",
            true, true, true, true, 1);
    }

    private int usageEventCount(String dimension) {
        return fixture.count("""
            select 1 from usage_events e where e."organizationId" = :org
              and e."dimension" = cast(:dimension as "UsageDimension")
            """, Map.of("org", AiFixture.ORG_A, "dimension", dimension));
    }

    private static String today() {
        return AiFixture.now().toLocalDate().toString();
    }

    private static String monthStart() {
        return AiFixture.now().toLocalDate().withDayOfMonth(1).toString();
    }

    private static String monthEnd() {
        java.time.LocalDate date = AiFixture.now().toLocalDate();
        return date.withDayOfMonth(date.lengthOfMonth()).toString();
    }
}
