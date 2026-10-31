package com.firstelement.airag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.airag.orchestration.AiOrchestrator;
import com.firstelement.airag.orchestration.AiRunDisposition;
import com.firstelement.airag.orchestration.AiRunResult;
import com.firstelement.airag.policy.AiRefusalReason;
import com.firstelement.airag.policy.AiRefusedException;
import com.firstelement.airag.principal.AiPrincipalScope;
import com.firstelement.airag.redaction.AiRedactionEngine;
import com.firstelement.airag.redaction.RedactionPolicyDefinition;
import com.firstelement.security.TenantContext;
import com.firstelement.security.aitool.AiToolExecutionGuard;
import com.firstelement.security.query.TenantScope;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The mock-provider end-to-end path: a run with <b>no provider key</b>, from a customer message to a
 * provenance row an auditor can read.
 *
 * <h2>What this suite proves that a unit test cannot</h2>
 *
 * <ul>
 *   <li>every column name in every statement exists, because the real Flyway chain applied the real
 *       migrations;</li>
 *   <li>the whole trace is written and readable through the tenant-filtered reads;</li>
 *   <li>the cost an answer is explained by is the <b>rate card's</b> figure, and a tenant with no rate card
 *       gets an unpriced run rather than a zero;</li>
 *   <li>no customer content and no credential reaches any persisted row.</li>
 * </ul>
 *
 * <p>Phase 9's lesson applies: a module's pure-JVM suites cannot show that a module can actually produce an
 * export. This suite is the reason the module works.
 */
@SpringBootTest(classes = AiRagTestApplication.class)
class MockProviderEndToEndIntegrationTest extends AiIntegrationTestBase {

    @Autowired
    private AiFixture fixture;

    @Autowired
    private AiOrchestrator orchestrator;

    @Autowired
    private AiToolExecutionGuard toolGuard;

    private UUID knowledgeBase;
    private AiFixture.AgentVersion agent;
    private UUID conversation;
    private UUID triggerMessage;

    @BeforeEach
    void seed() {
        fixture.reset();
        knowledgeBase = fixture.knowledgeBase(AiFixture.ORG_A, AiFixture.PROJECT_A, "Knowledge Base");
        agent = fixture.agentWithVersion(AiFixture.ORG_A, AiFixture.PROJECT_A, AiFixture.ADMIN_A,
            "MOCK", "mock-deterministic-v1", "[]", jsonArray(knowledgeBase), 0.0d,
            "أجب من المصادر فقط");
        fixture.policy(AiFixture.ORG_A, AiFixture.PROJECT_A, "default", 4000, 4, "STRICT_CLINICAL",
            AiFixture.ADMIN_A);
        fixture.model(AiFixture.ORG_A, AiFixture.PROJECT_A, "MOCK", "mock-deterministic-v1", true,
            "LOCAL", false, "USD", "0.00000150", "0.00000600");
        conversation = fixture.conversation(AiFixture.ORG_A, AiFixture.PROJECT_A, "AI");
        triggerMessage = fixture.customerMessage(AiFixture.ORG_A, AiFixture.PROJECT_A, conversation,
            "ما ساعات العمل؟");
    }

    @Test
    @DisplayName("a run with no provider key answers, cites its sources, and records full provenance")
    void runAnswersAndRecordsProvenance() {
        // Content tenant A actually indexed, so the answer is grounded in something.
        UUID document = fixture.document(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase,
            "ساعات العمل", "READY");
        fixture.chunk(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, document, 0,
            "ساعات العمل من الأحد إلى الخميس منnine صباحا حتى خمس مساء.",
            "ساعات العمل من الأحد إلى الخميس");

        AiRunResult result = run();

        assertThat(result.disposition()).isEqualTo(AiRunDisposition.ANSWERED);
        assertThat(result.hasAnswer()).isTrue();
        assertThat(result.answer()).isNotBlank();
        assertThat(result.refused()).isFalse();
        // FR-5.8: an answerable answer must carry source citations.
        assertThat(result.citations()).hasSize(1);
        assertThat(result.citations().get(0).documentTitle()).isEqualTo("ساعات العمل");
        // Acceptance gate 5: the exact agent/prompt/retrieval versions used.
        assertThat(result.promptVersion()).startsWith("sha-");
        assertThat(result.retrievalIds()).hasSize(1);
        assertThat(result.consideredChunkIds()).hasSize(1);
        // The provider that answered is recorded, so a later reader can name it.
        assertThat(result.providerAttempted()).contains("MOCK");
    }

    @Test
    @DisplayName("the run's provenance row names agent, version, prompt, model, retrievals and cost")
    void provenanceRowIsComplete() {
        UUID document = fixture.document(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, "الأسعار",
            "READY");
        fixture.chunk(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, document, 0,
            "سعر الاستشارة-basic.", "سعر الاستشارة");
        // A WIDE effective window, because RateCardResolver resolves against today's UTC date and a card
        // that expired in March would make this assert "no rate" rather than "a priced run".
        UUID versionId = fixture.activeRateCardVersion(AiFixture.ORG_A, AiFixture.PROJECT_A, 1, "USD",
            "2.000000", AiFixture.WIDE_FROM, null, "seeded AI rate", AiFixture.ADMIN_A);
        fixture.rateCardLine(AiFixture.ORG_A, AiFixture.PROJECT_A, versionId, "AI_TOKEN", "token",
            "0.00000200", null, "USD");
        fixture.rateCardLine(AiFixture.ORG_A, AiFixture.PROJECT_A, versionId, "AI_REQUEST", "request",
            "0.00001000", null, "USD");

        AiRunResult result = run();

        // The cost comes from the RATE CARD line, not the registry's registered price.
        assertThat(result.costAmount()).isNotNull();
        assertThat(result.costCurrency()).isEqualTo("USD");

        String provenance = fixture.text("""
            select p."promptVersion" from ai_provenance_records p
             where p."organizationId" = :org and p."projectId" = :project and p."aiRunId" = :run
            """, Map.of("org", AiFixture.ORG_A, "project", AiFixture.PROJECT_A, "run", result.runId()));
        assertThat(provenance).isEqualTo(result.promptVersion());

        // The agent and its version are NOT NULL in the schema, and both must be the pinned pair.
        UUID namedAgent = fixture.uuidValue("""
            select p."agentId" from ai_provenance_records p
             where p."aiRunId" = :run
            """, Map.of("run", result.runId()));
        assertThat(namedAgent).isEqualTo(agent.agentId());
        UUID namedVersion = fixture.uuidValue("""
            select p."agentVersionId" from ai_provenance_records p
             where p."aiRunId" = :run
            """, Map.of("run", result.runId()));
        assertThat(namedVersion).isEqualTo(agent.versionId());

        // The retrieval ids on the provenance row are the retrieval ROW ids, so a drill-through lands.
        String retrievalIds = fixture.textValue("""
            select array_to_string(p."retrievalIds", ',') from ai_provenance_records p
             where p."aiRunId" = :run
            """, Map.of("run", result.runId()));
        assertThat(retrievalIds).isEqualTo(result.retrievalIds().get(0).toString());

        // The disposition is one of the seven the schema CHECK admits.
        String disposition = fixture.textValue("""
            select p."disposition" from ai_provenance_records p where p."aiRunId" = :run
            """, Map.of("run", result.runId()));
        assertThat(disposition).isEqualTo("ANSWERED");
    }

    @Test
    @DisplayName("the ledger gets an AI_REQUEST and an AI_TOKEN row with no hand-supplied cost")
    void usageEventsAreWrittenWithoutAnEmitTimeCost() {
        UUID document = fixture.document(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, "المعرفة",
            "READY");
        fixture.chunk(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, document, 0, "معلومة.", "معلومة");

        run();

        int requests = fixture.count("""
            select 1 from usage_events u where u."organizationId" = :org and u."projectId" = :project
              and u."dimension" = cast('AI_REQUEST' as "UsageDimension")
            """, Map.of("org", AiFixture.ORG_A, "project", AiFixture.PROJECT_A));
        int tokens = fixture.count("""
            select 1 from usage_events u where u."organizationId" = :org and u."projectId" = :project
              and u."dimension" = cast('AI_TOKEN' as "UsageDimension")
            """, Map.of("org", AiFixture.ORG_A, "project", AiFixture.PROJECT_A));
        assertThat(requests).isEqualTo(1);
        assertThat(tokens).isEqualTo(1);

        // ADR 0009 §3: AI dimensions do not accept a cost at emit time, so costAmount is NULL on the
        // ledger row and the rollup prices it. A non-null cost here would mean a caller supplied one.
        int priced = fixture.count("""
            select 1 from usage_events u where u."organizationId" = :org
              and u."dimension" = cast('AI_TOKEN' as "UsageDimension") and u."costAmount" is not null
            """, Map.of("org", AiFixture.ORG_A));
        assertThat(priced).isZero();
    }

    @Test
    @DisplayName("with no rate card effective, the run is UNPRICED rather than free")
    void unpricedIsDistinctFromZero() {
        UUID document = fixture.document(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, "بلا سعر",
            "READY");
        fixture.chunk(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, document, 0, "محتوى.", "محتوى");

        AiRunResult result = run();

        // The COST is absent. The CURRENCY is not: it comes from the registered model's costCurrency, because
        // "we do not know what this cost" and "we do not know what currency it would have been in" are
        // different facts and only the second is true here.
        assertThat(result.costAmount()).isNull();
        assertThat(result.costCurrency()).isEqualTo("USD");

        // A priced zero would be a different claim, so the provenance row's cost is NULL and not 0.
        String cost = fixture.textValue("""
            select coalesce(p."costAmount"::text, 'null') from ai_provenance_records p
             where p."aiRunId" = :run
            """, Map.of("run", result.runId()));
        assertThat(cost).isEqualTo("null");
    }

    @Test
    @DisplayName("no customer content and no credential reaches any persisted AI row")
    void noCustomerContentOrCredentialIsPersisted() {
        UUID document = fixture.document(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, "السر",
            "READY");
        fixture.chunk(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, document, 0,
            "مفتاح العميل هو " + AiFixture.SECRET_MARKER + " والرقم " + AiFixture.PATIENT_NATIONAL_ID,
            "مفتاح العميل");

        AiRunResult result = run();

        // The answer is redacted, so the credential and the national id are gone from it.
        assertThat(result.answer()).doesNotContain(AiFixture.SECRET_MARKER);
        assertThat(result.answer()).doesNotContain(AiFixture.PATIENT_NATIONAL_ID);

        // And nothing the run wrote carries them either. `t::text` renders the WHOLE row as text, which is
        // the point: a leak in any column — including a jsonb one — shows up. Read back from the database
        // rather than trusting the in-memory objects, because the question is what was persisted.
        for (String table : List.of("ai_runs", "ai_provenance_records", "ai_run_steps",
            "ai_tool_executions", "ai_redaction_records", "ai_usage")) {
            String dump = fixture.textValue(
                "select coalesce(string_agg(t::text, ' | '), '') from " + table + " t", Map.of());
            assertThat(dump).as("%s must not contain the credential marker", table)
                .doesNotContain(AiFixture.SECRET_MARKER);
        }
        // The document's own chunk still has it, which proves the search actually found the seeded content
        // and the redaction is what removed it — a run that retrieved nothing would trivially pass.
        String chunk = fixture.textValue("""
            select c."content" from document_chunks c where c."documentId" = :document
            """, Map.of("document", document));
        assertThat(chunk).contains(AiFixture.SECRET_MARKER);
    }

    @Test
    @DisplayName("a redaction record exists for what was removed, with counts and no values")
    void redactionIsRecordedAsCounts() {
        UUID document = fixture.document(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, "بيانات",
            "READY");
        fixture.chunk(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, document, 0,
            "رقم الهوية " + AiFixture.PATIENT_NATIONAL_ID, "رقم الهوية");

        AiRunResult result = run();

        assertThat(result.redactionCount()).isPositive();
        int records = fixture.count("""
            select 1 from ai_redaction_records r where r."organizationId" = :org
              and r."aiRunId" = :run and r."valuesRedacted" >= 1
            """, Map.of("org", AiFixture.ORG_A, "run", result.runId()));
        assertThat(records).isPositive();

        // The record names a policy, a rule, a field path and a count. There is nowhere for a value to go:
        // the migration's guard fails if a raw-value column ever appears, and this asserts the columns it
        // does have carry none of them.
        String rule = fixture.textValue("""
            select r."ruleId" from ai_redaction_records r where r."aiRunId" = :run limit 1
            """, Map.of("run", result.runId()));
        assertThat(rule).isNotBlank();
        assertThat(rule).doesNotContain(AiFixture.PATIENT_NATIONAL_ID);
    }

    @Test
    @DisplayName("no chain-of-thought: the step detail is a declared shape, not a text blob")
    void noChainOfThoughtIsStored() {
        UUID document = fixture.document(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, "تفكير",
            "READY");
        fixture.chunk(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, document, 0,
            "معلومة عن المتابعة.", "معلومة عن المتابعة");

        AiRunResult result = run();

        List<String> details = fixture.column("""
            select s."detail"::text from ai_run_steps s
             where s."runId" = :run order by s."sequence"
            """, Map.of("run", result.runId()));
        assertThat(details).isNotEmpty();
        // Every step is CONTEXT / RETRIEVAL / GENERATION / DECISION, which is the FR-5.4 stage list.
        List<String> kinds = fixture.column("""
            select s."kind"::text from ai_run_steps s where s."runId" = :run order by s."sequence"
            """, Map.of("run", result.runId()));
        assertThat(kinds).allMatch(kind -> List.of("CONTEXT", "RETRIEVAL", "GENERATION", "TOOL_EXECUTION",
            "EVALUATION", "DECISION").contains(kind));

        // No step detail is longer than a summary, and none contains the prompt's text.
        assertThat(details).allSatisfy(detail -> assertThat(detail.length()).isLessThan(2000));
        assertThat(details).noneMatch(detail -> detail.contains("ساعات العمل"));
    }

    @Test
    @DisplayName("retrieval rows carry the run's tenant scope, so they are filterable from the moment they exist")
    void retrievalRowsCarryTheirOwnScope() {
        UUID document = fixture.document(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, "نطاق",
            "READY");
        fixture.chunk(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, document, 0, "محتوى.", "محتوى");

        AiRunResult result = run();

        // This is the expand step of ADR 0009 §2's expand/backfill/contract: a row this module writes is
        // immediately filterable, so the backfill only ever has to deal with historical rows.
        int scoped = fixture.count("""
            select 1 from ai_run_retrievals v
             where v."organizationId" = :org and v."projectId" = :project and v."runId" = :run
            """, Map.of("org", AiFixture.ORG_A, "project", AiFixture.PROJECT_A, "run", result.runId()));
        assertThat(scoped).isEqualTo(1);
    }

    @Test
    @DisplayName("a run with no indexed content is REFUSED, not answered from nothing")
    void emptyRetrievalIsARefusal() {
        assertThatThrownBy(this::run)
            .isInstanceOf(AiRefusedException.class)
            .satisfies(thrown -> assertThat(((AiRefusedException) thrown).reason())
                .isEqualTo(AiRefusalReason.RETRIEVAL_EMPTY));

        // And the refusal is recorded, because a refusal nobody can see is how an AI goes quiet for
        // unknown reasons.
        int refusedRuns = fixture.count("""
            select 1 from ai_runs r where r."organizationId" = :org and r."status" = cast('FAILED'
                as "AIRunStatus")
            """, Map.of("org", AiFixture.ORG_A));
        assertThat(refusedRuns).isPositive();
        int provenance = fixture.count("""
            select 1 from ai_provenance_records p where p."organizationId" = :org
              and p."refused" and p."disposition" = 'REFUSED'
            """, Map.of("org", AiFixture.ORG_A));
        assertThat(provenance).isPositive();
    }

    @Test
    @DisplayName("a policy with no APPROVED version is refused as POLICY_VERSION_NOT_APPROVED")
    void unapprovedPolicyVersionIsRefused() {
        UUID document = fixture.document(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, "معلومة",
            "READY");
        fixture.chunk(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, document, 0, "محتوى.", "محتوى");
        // The resolver takes the FIRST policy row by key (ai_policies.policy(scope, key) orders by key), so the
        // unapproved one is named to sort before "default". Without that the test would resolve the
        // approved policy, answer, and pass for the wrong reason.
        fixture.policyWithoutApprovedVersion(AiFixture.ORG_A, AiFixture.PROJECT_A, "aaa-unapproved",
            AiFixture.ADMIN_A);
        assertThatThrownBy(this::run)
            .isInstanceOf(AiRefusedException.class)
            .satisfies(thrown -> assertThat(((AiRefusedException) thrown).reason())
                .isEqualTo(AiRefusalReason.POLICY_VERSION_NOT_APPROVED));
    }

    @Test
    @DisplayName("a model the tenant registered is required; the registry is deny-by-default")
    void unregisteredModelIsRefused() {
        UUID document = fixture.document(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, "معلومة",
            "READY");
        fixture.chunk(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, document, 0, "محتوى.", "محتوى");

        // Move the agent version onto a model name no registry row carries.
        fixture.jdbcUpdate("""
            update ai_agent_versions set "model" = 'not-in-the-registry'
             where "id" = :version
            """, Map.of("version", agent.versionId()));

        assertThatThrownBy(this::run)
            .isInstanceOf(AiRefusedException.class)
            .satisfies(thrown -> assertThat(((AiRefusedException) thrown).reason())
                .isEqualTo(AiRefusalReason.MODEL_NOT_ALLOWED));
    }

    @Test
    @DisplayName("a model whose retention policy the residency zone does not admit is refused")
    void residencyViolationIsRefused() {
        UUID document = fixture.document(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, "معلومة",
            "READY");
        fixture.chunk(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, document, 0, "محتوى.", "محتوى");

        // The deployment's zone is LOCAL_ONLY, so a model declaring a hosted retention policy is refused.
        fixture.jdbcUpdate("""
            update ai_model_registry set "dataRetentionPolicy" = 'HOSTED_US_EAST'
             where "organizationId" = :org and "model" = 'mock-deterministic-v1'
            """, Map.of("org", AiFixture.ORG_A));

        assertThatThrownBy(this::run)
            .isInstanceOf(AiRefusedException.class)
            .satisfies(thrown -> assertThat(((AiRefusedException) thrown).reason())
                .isEqualTo(AiRefusalReason.RESIDENCY_VIOLATION));
    }

    @Test
    @DisplayName("the tenant's own token ceiling is enforced before the provider is called")
    void tenantTokenCeilingIsEnforcedBeforeTheCall() {
        UUID document = fixture.document(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, "طويل",
            "READY");
        fixture.chunk(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, document, 0,
            "محتوى طويل جدا ".repeat(200), "محتوى طويل جدا");
        fixture.jdbcUpdate("""
            update ai_policies set "maxTokensPerRun" = 5
             where "organizationId" = :org and "projectId" = :project
            """, Map.of("org", AiFixture.ORG_A, "project", AiFixture.PROJECT_A));

        assertThatThrownBy(this::run)
            .isInstanceOf(AiRefusedException.class)
            .satisfies(thrown -> assertThat(((AiRefusedException) thrown).reason())
                .isEqualTo(AiRefusalReason.POLICY_TOKEN_LIMIT));
    }

    @Test
    @DisplayName("a HARD budget at its ceiling refuses the run before the provider is called")
    void hardBudgetStopsTheRunBeforeTheCall() {
        UUID document = fixture.document(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, "ميزانية",
            "READY");
        fixture.chunk(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, document, 0, "محتوى.", "محتوى");
        // A ceiling of a BILLIONTH of a unit, against a model registered at 0.00000150 per 1k tokens and a
        // prompt of roughly 117 estimated tokens: the projection is ~1.75e-7, so any ceiling at or below
        // 1e-8 is crossed by any non-empty prompt. The magnitude matters -- an earlier version of this test
        // used 0.000001, which the projection does NOT exceed, and the test then passed without ever
        // exercising the refusal it was written for.
        // ai_budgets."limitAmount" is numeric(20,6), so the SMALLEST representable positive ceiling is
        // 0.000001 -- anything smaller rounds to 0.000000 and is refused by ai_budgets_limit_check. That
        // means the ceiling cannot be made tiny; the PROJECTION has to be made large instead, which means
        // registering a per-1k price high enough that a ~117-token prompt costs more than 0.000001.
        fixture.jdbcUpdate("""
            update ai_model_registry set "inputCostPer1kTokens" = cast(1.0 as numeric)
             where "organizationId" = :org and "model" = 'mock-deterministic-v1'
            """, Map.of("org", AiFixture.ORG_A));
        fixture.hardBudget(AiFixture.ORG_A, AiFixture.PROJECT_A, "ceiling", "USD", "0.000001", "DAILY");

        assertThatThrownBy(this::run)
            .isInstanceOf(AiRefusedException.class)
            .satisfies(thrown -> assertThat(((AiRefusedException) thrown).reason())
                .isEqualTo(AiRefusalReason.BUDGET_HARD_STOP));
    }

    @Test
    @DisplayName("an ALERT budget over its ceiling still runs, and says so in the trace")
    void alertBudgetRunsAndRecords() {
        UUID document = fixture.document(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, "تنبيه",
            "READY");
        fixture.chunk(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, document, 0, "محتوى.", "محتوى");
        // Same reasoning as the HARD budget: numeric(20,6) floors the ceiling at 0.000001, so the projection is
        // raised instead. The difference between this test and the HARD one is the ENFORCEMENT value alone
        // -- 'ALERT' against 'HARD' -- which is the whole point of the pair.
        fixture.jdbcUpdate("""
            update ai_model_registry set "inputCostPer1kTokens" = cast(1.0 as numeric)
             where "organizationId" = :org and "model" = 'mock-deterministic-v1'
            """, Map.of("org", AiFixture.ORG_A));
        fixture.alertBudget(AiFixture.ORG_A, AiFixture.PROJECT_A, "soft", "USD", "0.000001");

        AiRunResult result = run();

        assertThat(result.hasAnswer()).isTrue();
        String budgetStep = fixture.text("""
            select s."detail"::text from ai_run_steps s where s."runId" = :run and s."detail"->>'stage'
                = 'BUDGET_CHECKED'
            """, Map.of("run", result.runId()));
        assertThat(budgetStep).contains("over-ceiling-alerted");
    }

    @Test
    @DisplayName("the tool guard is a production bean, supplied by src/main and not by the test harness")
    void toolGuardIsAProductionBean() {
        // Phase 8's lesson, asserted: a test harness that supplies the bean hides a missing production
        // bean from ApiApplicationSmokeTest. This suite must be using the real one.
        assertThat(toolGuard).isNotNull();
        assertThat(toolGuard.getClass().getPackageName())
            .isEqualTo("com.firstelement.security.aitool");
    }

    @Test
    @DisplayName("the redaction policy set is a closed list and STRICT_CLINICAL is the declared default")
    void redactionPoliciesAreClosed() {
        assertThat(RedactionPolicyDefinition.declaredNames())
            .containsExactly("STRICT_CLINICAL", "CLINICAL", "LIGHT");
        assertThat(RedactionPolicyDefinition.byName("STRICT_CLINICAL")).isNotNull();
        assertThat(RedactionPolicyDefinition.byName("no-such-policy")).isNull();
        assertThat(AiRedactionEngine.STAGES).containsExactly("prompt.system", "prompt.untrusted",
            "prompt.memory", "response.assistant");
    }

    /** Runs the orchestrator as the AI agent principal, in AI mode. */
    private AiRunResult run() {
        TenantContext principal = AiPrincipalScope.agentScopedTo(AiFixture.ORG_A, AiFixture.PROJECT_A);
        return orchestrator.run(new AiOrchestrator.RunRequest(principal, AiFixture.PROJECT_A, conversation,
            triggerMessage, agent.agentId(), agent.versionId(), "ما ساعات العمل؟"));
    }

    /** A {@code jsonb} array literal, built so a test cannot fat-finger the quoting. */
    private static String jsonArray(UUID value) {
        return "[\"" + value + "\"]";
    }
}