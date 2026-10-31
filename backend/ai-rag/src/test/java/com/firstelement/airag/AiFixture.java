package com.firstelement.airag;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The AI fixture: two tenants, two projects in the first tenant, the real Flyway chain, and one row of
 * every kind the AI policy claims to read.
 *
 * <h2>Two tenants, always</h2>
 *
 * <p>Every Phase-10 acceptance gate has a cross-tenant negative, so the fixture cannot be one tenant.
 * Tenant B holds its own agent, policy, tool registry, documents and chunks, so a retrieval that forgot a
 * tenant predicate would produce a visibly wrong answer rather than a plausible one — which is the failure
 * mode a tenant test is most prone to.
 *
 * <h2>What is seeded, and why each row exists</h2>
 *
 * <ul>
 *   <li>a <b>tenant A policy with an APPROVED version</b>, so a run has something to resolve. The
 *       {@code failoverChain} names {@code MOCK} last, which is the compiled floor and the "no provider key"
 *       path.</li>
 *   <li>a <b>project A2</b> with its own policy, so a project-scoped negative has somewhere to point.</li>
 *   <li>a <b>tenant-wide tool registry row</b> for the read tool ({@code projectId is null}) and a
 *       <b>project-specific disabled row</b> for the consequential tool, so both halves of
 *       "tenant-wide default plus project override" are exercised.</li>
 *   <li>a <b>model registry row</b> for {@code MOCK} with a registered per-1k price, so a priced run and an
 *       unpriced run are both expressible.</li>
 *   <li>a <b>rate-card ACTIVE version with an AI_TOKEN line carrying a unit cost</b>, so the cost path is
 *       real rather than hypothetical, and a second tenant with <b>no rate card</b> so the unpriced path is a
 *       row in the fixture rather than a branch nobody exercises.</li>
 *   <li><b>documents in every status</b> — UPLOADED, READY, FAILED — plus chunks with and without
 *       embeddings, so "not retrievable yet" and "not retrievable because failed" are distinguishable.</li>
 * </ul>
 *
 * <h2>No skip path</h2>
 *
 * <p>{@link #POSTGRES} is started by the test base and every property supplier starts it first, so a Docker
 * problem fails the build rather than turning an AI suite green.
 */
@Component
public class AiFixture {

    public static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16"))
            .withDatabaseName("firstelement_airag")
            .withUsername("firstelement")
            .withPassword("firstelement");

    public static final UUID ORG_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    public static final UUID ORG_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    public static final UUID PROJECT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000a1");
    public static final UUID PROJECT_A2 = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000a2");
    public static final UUID PROJECT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000000b1");

    public static final UUID ADMIN_A = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000e1");
    public static final UUID STAFF_A = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000e2");
    /** A second administrator, so self-approval refusal and a legitimate second approver are both testable. */
    public static final UUID APPROVER_A = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000e6");
    /**
     * A THIRD administrator in tenant A, distinct from both the requester and the approver.
     *
     * <p>Exists so a revocation can be performed by someone other than the grantor. Without it the revoker and
     * the grantor are the same user, and a regression that overwrote {@code decidedByUserId} — the grant's
     * decider — with the revoker's id would leave the row looking correct and the assertion unable to tell.
     * The grant and the withdrawal are two separate records, and a test that cannot distinguish the two actors
     * is not testing that they are separate.
     */
    public static final UUID REVOKER_A = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000e7");
    public static final UUID ADMIN_B = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000000e4");

    /** A fixed month, for the assertions that want a reproducible bucket. */
    public static final LocalDate MARCH_1 = LocalDate.of(2026, 3, 1);
    public static final LocalDate MARCH_31 = LocalDate.of(2026, 3, 31);

    /**
     * The start date for a seeded budget or rate card that must be effective <b>now</b>.
     *
     * <p>The budget gate and {@code RateCardResolver} both resolve against today's UTC date, so a row whose
     * validity ended in March is invisible to an October run. Seeding the wide window is what makes the
     * budget and cost assertions test the gate rather than test that nothing was in force — a distinction
     * that is easy to get wrong and produces a green test proving nothing.
     */
    public static final LocalDate WIDE_FROM = LocalDate.of(2020, 1, 1);

    /**
     * The string that must never appear in a log line, an exception message, an audit metadata field, or a
     * persisted provider error.
     *
     * <p>Seeded into a document's chunk content. It is a credential-shaped string, so the scrubber must
     * remove it from anything that escapes a run — and the tests assert exactly that by reading the
     * persisted rows back rather than trusting the in-memory objects.
     */
    public static final String SECRET_MARKER = "sk-live-AKIA1234567890abcdefghijklmnop";

    /** A patient identifier that redaction must remove, in Arabic, so the Arabic rules are exercised. */
    public static final String PATIENT_NATIONAL_ID = "1012345678";

    /** The tool a model may request for a read. */
    public static final String READ_TOOL = "conversation.lookup";
    /** The tool that may only ever be proposed. */
    public static final String CONSEQUENTIAL_TOOL = "appointment.propose";
    /** A name no build registers, so an unknown-tool request is provable. */
    public static final String UNKNOWN_TOOL = "patient.delete";

    /**
     * Tables truncated in child-to-parent order.
     *
     * <p>The AI chain is seeded through {@code users -> customers -> conversations -> knowledge_bases ->
     * documents -> ai_agents -> ai_agent_versions -> ai_runs -> ai_usage}, because {@code ai_runs} has three NOT
     * NULL foreign keys into it and {@code ai_usage} has a NOT NULL {@code runId}.
     */
    private static final String[] TABLES = {
        "ai_provenance_records", "ai_redaction_records", "ai_tool_executions", "ai_approval_decisions",
        "ai_approvals", "ai_budget_consumptions", "ai_policy_versions", "ai_policies",
        "ai_model_registry", "ai_tool_registry", "ai_budgets", "rate_card_version_lines",
        "rate_card_versions", "rate_cards", "finance_reconciliation_lines", "finance_reconciliation_runs",
        "finance_adjustments", "finance_period_lock_events", "usage_rollup_snapshots", "usage_events",
        "ai_usage", "ai_run_steps", "ai_run_retrievals", "ai_run_tool_calls", "ai_runs",
        "ai_agent_versions", "ai_agents", "document_chunks", "documents", "knowledge_gaps",
        "knowledge_bases", "notifications", "notes", "appointments", "conversation_scores",
        "conversation_events", "messages", "conversations", "customers", "customer_identities",
        "project_memberships", "channel_connections", "users", "projects", "organizations",
        "admin_audit_events", "security_events", "job_integration_events", "retention_classes",
        "legal_hold_events", "legal_holds", "privacy_deletion_targets", "privacy_deletion_requests",
        // The exactly-once arbiter for an AI turn. Left behind deliberately across a reset only if a
        // suite forgot it, and truncating here is what lets the redelivery test start from a clean
        // slate rather than inheriting a claim from an earlier test method.
        "idempotency_records",
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final com.firstelement.airag.ingestion.KnowledgeIngestionService ingestion;
    private final com.firstelement.security.query.TenantQueryGuard guard;
    private final com.firstelement.airag.config.AiRagProperties properties;

    /**
     * The fixture takes the collaborators it hands back.
     *
     * <p>They are <b>production beans</b> from {@code src/main}, injected rather than constructed here. A
     * fixture that built its own {@code KnowledgeIngestionService} would test a copy, and would hide a
     * missing production bean from {@code ApiApplicationSmokeTest} — the exact failure Phase 8 records.
     */
    public AiFixture(NamedParameterJdbcTemplate jdbc,
                     com.firstelement.airag.ingestion.KnowledgeIngestionService ingestion,
                     com.firstelement.security.query.TenantQueryGuard guard,
                     com.firstelement.airag.config.AiRagProperties properties) {
        this.jdbc = jdbc;
        this.ingestion = ingestion;
        this.guard = guard;
        this.properties = properties;
    }

    /** The UTC clock the fixture and the tests agree on. */
    public static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    /** Truncates and re-seeds. Children first, parents last. */
    public void reset() {
        suspendGuards();
        try {
            for (String table : TABLES) {
                jdbc.update("delete from " + table, new MapSqlParameterSource());
            }
        } finally {
            restoreGuards();
        }
        memoized.clear();

        org(ORG_A, "Alpha Clinic", "alpha-clinic");
        org(ORG_B, "Beta Clinic", "beta-clinic");
        project(PROJECT_A, ORG_A, "Alpha One", "alpha-one");
        project(PROJECT_A2, ORG_A, "Alpha Two", "alpha-two");
        project(PROJECT_B, ORG_B, "Beta One", "beta-one");

        user(ADMIN_A, ORG_A, "admin@a.test", "ADMIN");
        user(APPROVER_A, ORG_A, "approver@a.test", "ADMIN");
        user(REVOKER_A, ORG_A, "revoker@a.test", "ADMIN");
        user(STAFF_A, ORG_A, "staff@a.test", "EMPLOYEE");
        user(ADMIN_B, ORG_B, "admin@b.test", "ADMIN");

        membership(ADMIN_A, PROJECT_A);
        membership(ADMIN_A, PROJECT_A2);
        membership(APPROVER_A, PROJECT_A);
        membership(REVOKER_A, PROJECT_A);
        membership(STAFF_A, PROJECT_A);
        membership(ADMIN_B, PROJECT_B);
    }

    // ------------------------------------------------------------------
    // seeders
    // ------------------------------------------------------------------

    private void org(UUID id, String name, String slug) {
        seed("""
            insert into organizations ("id", "name", "slug", "createdAt", "updatedAt")
            values (:id, :name, :slug, :now, :now)""",
            Map.of("id", id, "name", name, "slug", slug));
    }

    private void project(UUID id, UUID org, String name, String slug) {
        seed("""
            insert into projects ("id", "organizationId", "name", "slug", "createdAt", "updatedAt")
            values (:id, :org, :name, :slug, :now, :now)""",
            Map.of("id", id, "org", org, "name", name, "slug", slug));
    }

    public void user(UUID id, UUID org, String email, String role) {
        seed("""
            insert into users ("id", "organizationId", "email", "passwordHash", "fullName", "role",
                "locale", "isActive", "createdAt", "updatedAt")
            values (:id, :org, :email, 'not-a-real-hash', :name, cast(:role as "UserRole"),
                cast('ar' as "Locale"), true, :now, :now)""",
            Map.of("id", id, "org", org, "email", email, "role", role, "name", "Seeded User"));
    }

    private void membership(UUID userId, UUID projectId) {
        seed("""
            insert into project_memberships ("id", "organizationId", "projectId", "userId", "createdAt")
            values (:id, (select u."organizationId" from users u where u."id" = :user), :project, :user,
                :now)""",
            Map.of("id", UUID.randomUUID(), "user", userId, "project", projectId));
    }

    // ------------------------------------------------------------------
    // the agent chain
    // ------------------------------------------------------------------

    /** A knowledge base for a project. */
    public UUID knowledgeBase(UUID org, UUID projectId, String name) {
        UUID id = UUID.randomUUID();
        seed("""
            insert into knowledge_bases ("id", "organizationId", "projectId", "name", "isDefault",
                "createdAt", "updatedAt")
            values (:id, :org, :project, :name, false, :now, :now)""",
            Map.of("id", id, "org", org, "project", projectId, "name", name));
        return id;
    }

    /**
     * An agent and one version.
     *
     * <p>The version's {@code knowledgeBaseIds}, {@code failoverChain}, {@code provider} and
     * {@code minRetrievalScore} are parameters, because the interesting tests differ on exactly those.
     */
    public AgentVersion agentWithVersion(UUID org,
                                         UUID projectId,
                                         UUID creatorId,
                                         String provider,
                                         String model,
                                         String failoverChainJson,
                                         String knowledgeBasesJson,
                                         double minRetrievalScore,
                                         String systemInstructions) {
        UUID agentId = UUID.randomUUID();
        seed("""
            insert into ai_agents ("id", "organizationId", "projectId", "name", "isEnabled", "createdAt",
                "updatedAt")
            values (:id, :org, :project, 'Seeded agent', true, :now, :now)""",
            Map.of("id", agentId, "org", org, "project", projectId));
        UUID versionId = UUID.randomUUID();
        seed("""
            insert into ai_agent_versions ("id", "organizationId", "projectId", "agentId", "version",
                "systemInstructions", "provider", "model", "failoverChain", "enabledTools",
                "knowledgeBaseIds", "minRetrievalScore", "createdAt")
            values (:id, :org, :project, :agent, 1, :instructions, cast(:provider as "AIProviderKind"),
                :model, cast(:failover as jsonb), cast('[]' as jsonb), cast(:bases as jsonb), :minScore, :now)""",
            new MapSqlParameterSource()
                .addValue("id", versionId)
                .addValue("org", org)
                .addValue("project", projectId)
                .addValue("agent", agentId)
                .addValue("instructions", systemInstructions)
                .addValue("provider", provider)
                .addValue("model", model)
                .addValue("failover", failoverChainJson)
                .addValue("bases", knowledgeBasesJson)
                .addValue("minScore", minRetrievalScore));
        jdbc.update("update ai_agents set \"activeVersionId\" = :version where \"id\" = :agent",
            new MapSqlParameterSource().addValue("version", versionId).addValue("agent", agentId));
        return new AgentVersion(agentId, versionId);
    }

    /** An agent and version, named by the caller. */
    public record AgentVersion(UUID agentId, UUID versionId) {
    }

    // ------------------------------------------------------------------
    // policy
    // ------------------------------------------------------------------

    /** A live, enabled policy for a project, with an APPROVED version. */
    public UUID policy(UUID org, UUID projectId, String key, Integer maxTokensPerRun,
                       Integer maxToolCallsPerRun, String redactionPolicy, UUID authorId) {
        UUID policyId = UUID.randomUUID();
        seed("""
            insert into ai_policies ("id", "organizationId", "projectId", "key", "nameAr", "nameEn",
                "isEnabled", "maxTokensPerRun", "maxToolCallsPerRun", "redactionPolicy", "retentionClass",
                "failoverChain", "createdAt", "updatedAt")
            values (:id, :org, :project, :key, 'سياسة', :key, true, :maxTokens, :maxTools,
                :redaction, 'AI_PROVENANCE', cast('[]' as jsonb), :now, :now)""",
            new MapSqlParameterSource()
                .addValue("id", policyId)
                .addValue("org", org)
                .addValue("project", projectId)
                .addValue("key", key)
                .addValue("maxTokens", maxTokensPerRun)
                .addValue("maxTools", maxToolCallsPerRun)
                .addValue("redaction", redactionPolicy)
                .addValue("now", now()));
        UUID versionId = UUID.randomUUID();
        seed("""
            insert into ai_policy_versions ("id", "organizationId", "projectId", "aiPolicyId",
                "versionNumber", "maxTokensPerRun", "maxToolCallsPerRun", "redactionPolicy",
                "retentionClass", "failoverChain", "changeNote", "state", "createdByUserId",
                "approvedAt", "approvedByUserId", "createdAt")
            values (:id, :org, :project, :policy, 1, :maxTokens, :maxTools, :redaction, 'AI_PROVENANCE',
                cast('[]' as jsonb), 'seeded approved policy', cast('APPROVED' as "AiApprovalStatus"), :author,
                :now, :approver, :now)""",
            new MapSqlParameterSource()
                .addValue("id", versionId)
                .addValue("org", org)
                .addValue("project", projectId)
                .addValue("policy", policyId)
                .addValue("maxTokens", maxTokensPerRun)
                .addValue("maxTools", maxToolCallsPerRun)
                .addValue("redaction", redactionPolicy)
                .addValue("author", authorId)
                .addValue("approver", authorId)
                .addValue("now", now()));
        return policyId;
    }

    /**
     * A policy row with NO approved version, so the resolver's second refusal is reachable.
     *
     * <p>{@code maxTokensPerRun} is set because {@code ai_policies_enabled_limits_check} refuses an enabled
     * policy that promises neither a token cap nor a redaction policy — "no limits and no redaction is not a
     * configuration". The unapproved state this exists to express is the missing <em>version</em>, not a
     * limitless policy; a row the CHECK refuses would have made the state unreachable rather than
     * reachable, and the test would have been asserting a schema error.
     */
    public UUID policyWithoutApprovedVersion(UUID org, UUID projectId, String key, UUID authorId) {
        UUID policyId = UUID.randomUUID();
        seed("""
            insert into ai_policies ("id", "organizationId", "projectId", "key", "nameAr", "nameEn",
                "isEnabled", "maxTokensPerRun", "redactionPolicy", "retentionClass", "createdAt",
                "updatedAt")
            values (:id, :org, :project, :key, 'سياسة', :key, true, 4000, 'STRICT_CLINICAL',
                'AI_PROVENANCE', :now, :now)""",
            new MapSqlParameterSource().addValue("id", policyId).addValue("org", org)
                .addValue("project", projectId).addValue("key", key).addValue("now", now()));
        return policyId;
    }

    /**
     * A registered model.
     *
     * @param prices {@code null} prices make the run unpriced, which is a real state rather than a gap
     */
    public UUID model(UUID org, UUID projectId, String provider, String model, boolean enabled,
                      String dataRetentionPolicy, boolean sendsCustomerData, String currency,
                      String inputPer1k, String outputPer1k) {
        UUID id = UUID.randomUUID();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("org", org)
            .addValue("project", projectId)
            .addValue("provider", provider)
            .addValue("model", model)
            .addValue("enabled", enabled)
            .addValue("retention", dataRetentionPolicy)
            .addValue("customerData", sendsCustomerData)
            .addValue("currency", currency)
            .addValue("inputPrice", inputPer1k)
            .addValue("outputPrice", outputPer1k)
            .addValue("now", now())
            // ai_model_registry has NO enabledAt / enabledByUserId column -- unlike
            // ai_tool_registry, whose enabling is a decided act with a recorded decider. Here the
            // biconditional is one-way-ish: (NOT isEnabled) = (disabledAt IS NOT NULL), so an enabled row
            // carries a NULL disabledAt and a disabled row must carry one plus a reason. Both keys are
            // always bound because Spring refuses an unbound placeholder outright.
            .addValue("disabledAt", enabled ? null : now())
            .addValue("disabledReason", enabled ? null : "seeded as disabled");
        seed("""
            insert into ai_model_registry ("id", "organizationId", "projectId", "provider", "model",
                "isEnabled", "dataRetentionPolicy", "sendsCustomerData", "inputCostPer1kTokens",
                "outputCostPer1kTokens", "costCurrency", "disabledAt", "disabledReason", "createdAt",
                "updatedAt")
            values (:id, :org, :project, cast(:provider as "AIProviderKind"), :model, :enabled, :retention,
                :customerData, cast(:inputPrice as numeric), cast(:outputPrice as numeric), :currency,
                :disabledAt, :disabledReason, :now, :now)""",
            parameters);
        return id;
    }

    /**
     * A registered tool.
     *
     * @param enabled the deny-by-default half: a {@code false} row must carry the disabled triple, which the
     *                schema's biconditional requires, and the fixture writes it rather than letting the
     *                test discover the constraint
     */
    public UUID tool(UUID org, UUID projectId, String toolName, String requiredPermission,
                     boolean enabled, String[] fieldAllowList, String riskLevel, boolean consequential,
                     UUID actorId) {
        UUID id = UUID.randomUUID();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("org", org)
            .addValue("project", projectId)
            .addValue("tool", toolName)
            .addValue("permission", requiredPermission)
            .addValue("enabled", enabled)
            .addValue("fields", fieldAllowList)
            .addValue("risk", riskLevel)
            .addValue("consequential", consequential)
            .addValue("now", now())
            // Both halves bound, one null. `ai_tool_registry_disabled_check` and
            // `ai_tool_registry_enabled_check` are biconditionals, so exactly one half must be present and
            // the other absent -- which is why the two cannot be bound conditionally.
            .addValue("disabledAt", enabled ? null : now())
            .addValue("disabledBy", enabled ? null : actorId)
            .addValue("disabledReason", enabled ? null : "seeded as disabled")
            .addValue("enabledAt", enabled ? now() : null)
            .addValue("enabledBy", enabled ? actorId : null);
        insert("""
            insert into ai_tool_registry ("id", "organizationId", "projectId", "toolName",
                "requiredPermission", "isEnabled", "fieldAllowList", "riskLevel", "consequential",
                "disabledAt", "disabledByUserId", "disabledReason", "enabledAt", "enabledByUserId",
                "createdAt", "updatedAt")
            values (:id, :org, :project, :tool, :permission, :enabled, :fields,
                cast(:risk as "AiRiskLevel"), :consequential, :disabledAt, :disabledBy, :disabledReason,
                :enabledAt, :enabledBy, :now, :now)""",
            parameters);
        return id;
    }

    /** An enabled budget with a HARD ceiling, so the budget gate's refusal is reachable. */
    public UUID hardBudget(UUID org, UUID projectId, String key, String currency, String limitAmount,
                           String period) {
        UUID id = UUID.randomUUID();
        seed("""
            insert into ai_budgets ("id", "organizationId", "projectId", "key", "nameAr", "nameEn",
                "period", "currency", "limitAmount", "enforcement", "isEnabled", "validFrom", "createdAt",
                "updatedAt")
            values (:id, :org, :project, :key, 'ميزانية', :key, cast(:period as "AiBudgetPeriod"), :currency,
                cast(:limit as numeric), 'HARD', true, :from, :now, :now)""",
            new MapSqlParameterSource()
                .addValue("id", id).addValue("org", org).addValue("project", projectId).addValue("key", key)
                .addValue("currency", currency).addValue("limit", limitAmount).addValue("period", period)
                .addValue("from", WIDE_FROM).addValue("now", now()));
        return id;
    }

    /**
     * An ALERT budget, so "over ceiling but not stopped" is a data state rather than a branch.
     *
     * <p>Validity starts {@link #WIDE_FROM} and is open-ended, because the budget gate resolves against
     * <b>today</b> in UTC rather than against a fixture-fixed month. A budget that expired in March would be
     * invisible to an October run, and the "still runs" assertion would then pass for the wrong reason —
     * no budget in force at all, rather than a soft one that did not stop the run.
     */
    public UUID alertBudget(UUID org, UUID projectId, String key, String currency, String limitAmount) {
        UUID id = UUID.randomUUID();
        seed("""
            insert into ai_budgets ("id", "organizationId", "projectId", "key", "nameAr", "nameEn",
                "period", "currency", "limitAmount", "enforcement", "isEnabled", "validFrom", "createdAt",
                "updatedAt")
            values (:id, :org, :project, :key, 'ميزانية', :key, cast('MONTHLY' as "AiBudgetPeriod"),
                :currency, cast(:limit as numeric), 'ALERT', true, :from, :now, :now)""",
            new MapSqlParameterSource()
                .addValue("id", id).addValue("org", org).addValue("project", projectId).addValue("key", key)
                .addValue("currency", currency).addValue("limit", limitAmount)
                .addValue("from", WIDE_FROM).addValue("now", now()));
        return id;
    }

    // ------------------------------------------------------------------
    // rate cards
    // ------------------------------------------------------------------

    /**
     * An ACTIVE rate-card version with a line per dimension.
     *
     * <p>Seeded directly rather than through {@code RateCardService} because the service's publish path
     * inserts a <em>new</em> version (the schema's narrow guard makes an in-place {@code DRAFT -> ACTIVE}
     * impossible) and these tests need a specific version number on a specific date.
     */
    public UUID activeRateCardVersion(UUID org, UUID projectId, int versionNumber, String currency,
                                      String markupRate, LocalDate from, LocalDate to, String note,
                                      UUID authorId) {
        UUID cardId = rateCard(org, projectId, "standard", currency, authorId);
        UUID versionId = UUID.randomUUID();
        seed("""
            insert into rate_card_versions ("id", "organizationId", "projectId", "rateCardId",
                "versionNumber", "currency", "markupRate", "effectiveFrom", "effectiveTo", "status",
                "changeNote", "publishedAt", "publishedByUserId", "createdByUserId", "createdAt")
            values (:id, :org, :project, :card, :number, :currency, cast(:markup as numeric), :from, :to,
                cast('ACTIVE' as "RateCardStatus"), :note, :now, :author, :author, :now)""",
            new MapSqlParameterSource()
                .addValue("id", versionId).addValue("org", org).addValue("project", projectId)
                .addValue("card", cardId).addValue("number", versionNumber).addValue("currency", currency)
                .addValue("markup", markupRate).addValue("from", from).addValue("to", to)
                .addValue("note", note).addValue("author", authorId).addValue("now", now()));
        jdbc.update("""
            update rate_cards set "currentVersionId" = :version, "latestVersionNumber" = :number,
                "status" = cast('ACTIVE' as "RateCardStatus"), "version" = "version" + 1,
                "updatedAt" = :now
             where "id" = :card""",
            new MapSqlParameterSource().addValue("version", versionId).addValue("number", versionNumber)
                .addValue("card", cardId).addValue("now", now()));
        return versionId;
    }

    /** A rate card, created once per (org, key). */
    public UUID rateCard(UUID org, UUID projectId, String key, String currency, UUID authorId) {
        List<UUID> existing = jdbc.queryForList(
            "select \"id\" from rate_cards where \"organizationId\" = :org and \"key\" = :key",
            new MapSqlParameterSource().addValue("org", org).addValue("key", key), UUID.class);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        UUID id = UUID.randomUUID();
        seed("""
            insert into rate_cards ("id", "organizationId", "projectId", "key", "nameAr", "nameEn",
                "currency", "status", "createdByUserId", "version", "createdAt", "updatedAt")
            values (:id, :org, :project, :key, 'بطاقة', :key, :currency, cast('DRAFT' as "RateCardStatus"),
                :author, 0, :now, :now)""",
            Map.of("id", id, "org", org, "project", projectId, "key", key, "currency", currency,
                "author", authorId));
        return id;
    }

    /** A priced line on a version. {@code costAmount} of {@code null} takes the version's markup only. */
    public UUID rateCardLine(UUID org, UUID projectId, UUID versionId, String dimension, String unit,
                             String costAmount, String markupRate, String currency) {
        UUID id = UUID.randomUUID();
        insert("""
            insert into rate_card_version_lines ("id", "organizationId", "projectId", "rateCardVersionId",
                "dimension", "unit", "costAmount", "markupRate", "currency", "createdAt")
            values (:id, :org, :project, :version, cast(:dimension as "UsageDimension"), :unit,
                cast(:cost as numeric), cast(:markup as numeric), :currency, :now)""",
            new MapSqlParameterSource()
                .addValue("id", id).addValue("org", org).addValue("project", projectId)
                .addValue("version", versionId).addValue("dimension", dimension).addValue("unit", unit)
                .addValue("cost", costAmount).addValue("markup", markupRate).addValue("currency", currency)
                .addValue("now", now()));
        return id;
    }

    // ------------------------------------------------------------------
    // documents and chunks
    // ------------------------------------------------------------------

    /** A document in a given status. */
    public UUID document(UUID org, UUID projectId, UUID knowledgeBaseId, String title, String status) {
        UUID id = UUID.randomUUID();
        seed("""
            insert into documents ("id", "organizationId", "projectId", "knowledgeBaseId", "title",
                "sourceType", "status", "chunkCount", "createdAt", "updatedAt")
            values (:id, :org, :project, :kb, :title, cast('TEXT' as "DocumentSourceType"),
                cast(:status as "DocumentStatus"), 0, :now, :now)""",
            Map.of("id", id, "org", org, "project", projectId, "kb", knowledgeBaseId, "title", title,
                "status", status));
        return id;
    }

    /**
     * A chunk, embedded or not.
     *
     * <p>{@code embeddingText} of {@code null} writes a NULL embedding, which is the state a freshly
     * uploaded document is in and which the retrieval statement must not see.
     */
    public UUID chunk(UUID org, UUID projectId, UUID knowledgeBaseId, UUID documentId, int index,
                      String content, String embeddingText) {
        UUID id = UUID.randomUUID();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("org", org)
            .addValue("project", projectId)
            .addValue("kb", knowledgeBaseId)
            .addValue("document", documentId)
            .addValue("index", index)
            .addValue("content", content)
            .addValue("tokens", com.firstelement.airag.provider.MockTokenCounter.count(content))
            .addValue("metadata", "{}")
            .addValue("now", now());
        if (embeddingText != null) {
            float[] vector = new com.firstelement.airag.provider.DeterministicEmbeddingProvider(null)
                .embed(embeddingText);
            parameters.addValue("embedding",
                com.firstelement.airag.provider.DeterministicEmbeddingProvider.toVectorLiteral(vector));
        } else {
            // Explicitly bound as SQL NULL rather than left unbound. Spring refuses a placeholder with no
            // registered key at all, and an UNEMBEDDED chunk is the state this fixture exists to produce --
            // the one a freshly uploaded, not-yet-indexed document is in, and the one the retrieval
            // statement must not see.
            parameters.addValue("embedding", null, java.sql.Types.OTHER);
        }
        insert("""
            insert into document_chunks ("id", "organizationId", "projectId", "knowledgeBaseId",
                "documentId", "chunkIndex", "content", "tokenCount", "metadata", "embedding", "createdAt")
            values (:id, :org, :project, :kb, :document, :index, :content, :tokens, cast(:metadata as
                jsonb), cast(:embedding as vector), :now)""",
            parameters);
        return id;
    }

    // ------------------------------------------------------------------
    // conversations and messages
    // ------------------------------------------------------------------

    /** A customer, created once per project. */
    public UUID customer(UUID org, UUID projectId) {
        return memo("customer:" + projectId, () -> {
            UUID id = UUID.randomUUID();
            seed("""
                insert into customers ("id", "organizationId", "projectId", "cxId", "displayName",
                    "createdAt", "updatedAt")
                values (:id, :org, :project, :cx, 'عميل تجريبي', :now, :now)""",
                Map.of("id", id, "org", org, "project", projectId, "cx", customerCode(projectId)));
            return id;
        });
    }

    /** A conversation in a given mode. {@code AI} or {@code HUMAN}. */
    public UUID conversation(UUID org, UUID projectId, String mode) {
        UUID id = UUID.randomUUID();
        seed("""
            insert into conversations ("id", "organizationId", "projectId", "customerId", "ticketNumber",
                "channel", "status", "mode", "messageCount", "createdAt", "updatedAt")
            values (:id, :org, :project, :customer, :ticket, cast('MOCK' as "ChannelType"),
                cast('OPEN' as "ConversationStatus"), cast(:mode as "ConversationMode"), 0, :now, :now)""",
            new MapSqlParameterSource()
                .addValue("id", id).addValue("org", org).addValue("project", projectId)
                .addValue("customer", customer(org, projectId))
                // varchar(14) with a tenant-unique index, so the fixture generates a stable one per row.
                .addValue("ticket", "AI-" + id.toString().substring(0, 8).toUpperCase())
                .addValue("mode", mode).addValue("now", now()));
        return id;
    }

    /** An inbound customer message. */
    public UUID customerMessage(UUID org, UUID projectId, UUID conversationId, String body) {
        UUID id = UUID.randomUUID();
        seed("""
            insert into messages ("id", "organizationId", "projectId", "conversationId", "channel",
                "direction", "authorType", "body", "contentType", "deliveryStatus", "sentAt", "createdAt")
            values (:id, :org, :project, :conversation, cast('MOCK' as "ChannelType"),
                cast('INBOUND' as "MessageDirection"), cast('CUSTOMER' as "MessageAuthorType"), :body,
                'text/plain', cast('DELIVERED' as "MessageDeliveryStatus"), :now, :now)""",
            new MapSqlParameterSource()
                .addValue("id", id).addValue("org", org).addValue("project", projectId)
                .addValue("conversation", conversationId).addValue("body", body).addValue("now", now()));
        return id;
    }

    // ------------------------------------------------------------------
    // queries
    // ------------------------------------------------------------------

    /** Counts rows matching a predicate. Zero matches reads as 0 rather than throwing. */
    public int count(String sql, Map<String, Object> parameters) {
        Integer rows = jdbc.queryForObject("select count(*) from (" + sql + ") fe_counted",
            new MapSqlParameterSource(parameters), Integer.class);
        return rows == null ? 0 : rows;
    }

    /**
     * A single string column of one row, or {@code null}.
     *
     * <p>Returns the SQL {@code NULL} as a Java {@code null} rather than the four characters "null": a
     * test asserting {@code assertThat(cost).isNull()} is asserting about a value, and a string that
     * happens to spell "null" would pass an equality check against the literal while meaning nothing.
     */
    public String text(String sql, Map<String, Object> parameters) {
        return textValue(sql, parameters);
    }

    /**
     * Runs one statement that returns no rows.
     *
     * <p>Present so a test can move a row to a state the application would not create — a model name absent
     * from the registry, a retention policy outside the zone — and therefore test the <em>refusal</em>
     * rather than only the happy path. Using raw SQL here is the same discipline the finance fixture
     * applies: seeding states the service deliberately refuses to reach is honest, and asserting the
     * refusal indirectly against a state the application never produces is not.
     */
    public void jdbcUpdate(String sql, Map<String, Object> parameters) {
        jdbc.update(sql, new MapSqlParameterSource(parameters));
    }

    /**
     * A single string column of one row, or {@code null} when the column is SQL NULL.
     *
     * <p>Every {@code uuid} comparison in this helper's callers binds a value the driver cannot type
     * unambiguously when the test passes {@code String.valueOf(uuid)}. Passing the {@link UUID} directly is
     * what makes PostgreSQL see {@code uuid = uuid} instead of {@code uuid = character varying} — an error
     * that names an operator rather than the string conversion that caused it.
     */
    public String textValue(String sql, Map<String, Object> parameters) {
        List<String> found = jdbc.queryForList(sql, new MapSqlParameterSource(parameters), String.class);
        return found.isEmpty() || found.get(0) == null ? null : found.get(0);
    }

    /** A single {@code uuid} column of one row, or {@code null}. */
    public UUID uuidValue(String sql, Map<String, Object> parameters) {
        List<UUID> found = jdbc.queryForList(sql, new MapSqlParameterSource(parameters), UUID.class);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * A single integer column of one row.
     *
     * <p>For aggregates — {@code sum}, {@code count} over something other than rows, {@code max} of a length —
     * where {@link #count} (which wraps the query in its own {@code select count(*) from (...)}) cannot
     * express the query. The same {@code uuid}-typed binding discipline as {@link #textValue} applies.
     */
    public int intValue(String sql, Map<String, Object> parameters) {
        Integer value = jdbc.queryForObject(sql, new MapSqlParameterSource(parameters), Integer.class);
        return value == null ? 0 : value;
    }

    /** Every value of a text column, so a test can assert that no row contains a marker. */
    public List<String> column(String sql, Map<String, Object> parameters) {
        return jdbc.queryForList(sql, new MapSqlParameterSource(parameters), String.class);
    }

    /**
     * A RUNNING {@code ai_runs} row for a conversation and agent version.
 *
     * <p>Needed by any test that writes a trace row directly, because
     * {@code ai_tool_executions_run_tenant_fkey} is a COMPOSITE reference to
     * {@code (ai_runs.id, ai_runs.organizationId)}. A random run id is therefore refused by the database —
     * which is correct, and the reason the refusal is useful: a tool execution with no run is exactly the
     * unattributable row ADR 0007 Part 2 rule 7 exists to prevent. A test that seeds its own run therefore
     * gets the FK check as a free assertion.
     */
    public UUID aiRun(UUID org, UUID projectId, UUID conversationId, UUID agentId, UUID agentVersionId) {
        UUID id = UUID.randomUUID();
        seed("""
            insert into ai_runs ("id", "organizationId", "projectId", "conversationId", "agentId",
                "agentVersionId", "status", "provider", "model", "startedAt")
            values (:id, :org, :project, :conversation, :agent, :version,
                cast('RUNNING' as "AIRunStatus"), cast('MOCK' as "AIProviderKind"), 'mock-deterministic-v1',
                :now)""",
            new MapSqlParameterSource()
                .addValue("id", id).addValue("org", org).addValue("project", projectId)
                .addValue("conversation", conversationId).addValue("agent", agentId)
                .addValue("version", agentVersionId).addValue("now", now()));
        return id;
    }

/**
     * A {@code customers."cxId"} for a project.
 *
 * <p>Derived from a hash of the whole project id rather than from its first eight characters. The fixture's
 * project ids share a prefix by design ({@code aaaaaaaa-0000-0000-0000-0000000000a1} and
 * {@code ...a2}), so a prefix-derived {@code cxId} collides — and {@code customers_cxId_key} is UNIQUE, so the
 * second conversation insert fails with a {@code DuplicateKeyException} that names a customer table and says
 * nothing about the id derivation that caused it.
 */
private static String customerCode(UUID projectId) {
    return "C" + String.format("%08X", projectId.hashCode());
}

/**
     * Flips a registered tool to enabled, with the enabling record the schema's biconditional requires.
 *
 * * <p>{@code ai_tool_registry_enabled_check} is {@code isEnabled = (enabledAt IS NOT NULL AND
 * enabledByUserId IS NOT NULL)}, and {@code ai_tool_registry_disabled_check} is the same shape for the
 * disabled half. So an "enabled" row must carry <em>both</em> facts — the disabled triple cleared and the
 * enabled pair set — and a fixture that only flipped the boolean would be refused. Doing it through a
 * two-statement update rather than one keeps each half's predicate checkable.
 */
public void enableTool(String toolName) {
jdbcUpdate("""
update ai_tool_registry
set "isEnabled" = true, "disabledAt" = null, "disabledByUserId" = null, "disabledReason" = null,
"enabledAt" = :now, "enabledByUserId" = :user, "updatedAt" = :now
where "organizationId" = :org and "toolName" = :tool
""", Map.of("now", now(), "user", ADMIN_A, "org", ORG_A, "tool", toolName));
int enabled = count("""
select 1 from ai_tool_registry t
where t."organizationId" = :org and t."toolName" = :tool and t."isEnabled"
""", Map.of("org", ORG_A, "tool", toolName));
if (enabled != 1) {
throw new IllegalStateException("enableTool(" + toolName + ") did not enable exactly one row");
}
}

/** The document a chunk belongs to, so a test can delete the document by id. */
    public UUID documentIdOf(UUID chunkId) {
        UUID id = jdbc.queryForObject("select c.\"documentId\" from document_chunks c where c.\"id\" = :id",
            new MapSqlParameterSource().addValue("id", chunkId), UUID.class);
        if (id == null) {
            throw new IllegalStateException("No chunk " + chunkId + "; the fixture must be seeded first");
        }
        return id;
    }

    /** The ingestion service, so the deletion-propagation test drives the real one. */
    public com.firstelement.airag.ingestion.KnowledgeIngestionService ingestionService() {
        return ingestion;
    }

    /** The tenant query guard, for the assertion that an out-of-scope project is refused. */
    public com.firstelement.security.query.TenantQueryGuard tenantQueryGuard() {
        return guard;
    }

    /** The deployment's retrieval ceiling, so a limit assertion names the configured value. */
    public int retrievalLimit() {
        return properties.getRetrievalLimit();
    }

    /** The tenant's first ADMIN, for a row the schema ties to a tenant composite key. */
    public UUID adminOf(UUID organizationId) {
        UUID admin = jdbc.queryForObject(
            "select u.\"id\" from users u where u.\"organizationId\" = :org and u.\"role\" ="
                + " cast('ADMIN' as \"UserRole\") order by u.\"id\" limit 1",
            new MapSqlParameterSource().addValue("org", organizationId), UUID.class);
        if (admin == null) {
            throw new IllegalStateException(
                "No ADMIN in organization " + organizationId + "; the fixture must be reset first");
        }
        return admin;
    }

    /** The append-only tables the reset suspends. Named, not discovered, so exactly these are touched. */
    private static final List<String> APPEND_ONLY_TABLES = List.of(
        "usage_events", "usage_rollup_snapshots", "rate_card_versions", "rate_card_version_lines",
        "finance_period_lock_events", "finance_reconciliation_runs", "finance_reconciliation_lines");

    /**
     * Whether every named append-only guard is enabled right now.
     *
     * <p>Read from {@code pg_trigger} rather than assumed from the {@code ALTER}. A fixture that left a guard
     * disabled would make every append-only assertion in the suite pass for the wrong reason.
     */
    public int enabledAppendOnlyGuards() {
        Integer enabled = jdbc.queryForObject("""
            select count(*)
              from pg_trigger tg
              join pg_class c on c.oid = tg.tgrelid
              join pg_namespace n on n.oid = c.relnamespace
             where n.nspname = 'public'
               and c.relname in (:tables)
               and not tg.tgisinternal
               and tg.tgenabled = 'O'
               and tg.tgname = c.relname || '_append_only'
            """, new MapSqlParameterSource().addValue("tables", APPEND_ONLY_TABLES), Integer.class);
        return enabled == null ? 0 : enabled;
    }

    private void suspendGuards() {
        for (String table : APPEND_ONLY_TABLES) {
            jdbc.update("alter table " + table + " disable trigger " + table + "_append_only",
                new MapSqlParameterSource());
        }
    }

    /** Restores them, and fails loudly if any is still disabled. */
    private void restoreGuards() {
        for (String table : APPEND_ONLY_TABLES) {
            jdbc.update("alter table " + table + " enable trigger " + table + "_append_only",
                new MapSqlParameterSource());
        }
        int stillOff = APPEND_ONLY_TABLES.size() - enabledAppendOnlyGuards();
        if (stillOff != 0) {
            throw new IllegalStateException(stillOff + " append-only guard(s) are still disabled after the "
                + "fixture reset. Every append-only assertion in this suite would pass for the wrong reason.");
        }
    }

    private final Map<String, UUID> memoized = new java.util.concurrent.ConcurrentHashMap<>();

    private UUID memo(String key, java.util.function.Supplier<UUID> create) {
        return memoized.computeIfAbsent(key, ignored -> create.get());
    }

    /**
     * Seeds with a plain map, adding the fixture's own clock.
     *
     * <p>{@code Map.of} caps at ten pairs and several of these statements carry more, so a caller that
     * exceeds it uses {@link #insert} with an explicit source instead of this. Splitting the two is why the
     * {@code now} binding is here rather than being the caller's job: a caller that forgot it would get a
     * NOT NULL violation on {@code createdAt} that looks like a schema defect.
     */
    private void seed(String sql, Map<String, Object> parameters) {
        seed(sql, new MapSqlParameterSource(parameters));
    }

    private void seed(String sql, MapSqlParameterSource parameters) {
        MapSqlParameterSource source = new MapSqlParameterSource();
        if (parameters.getValues() != null) {
            parameters.getValues().forEach(source::addValue);
        }
        source.addValue("now", now());
        jdbc.update(sql, source);
    }

    /** Seeds with an explicit parameter source, for statements with more than ten bindings. */
    private void insert(String sql, MapSqlParameterSource parameters) {
        jdbc.update(sql, parameters);
    }
}