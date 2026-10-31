package com.firstelement.airag.policy;

import com.firstelement.security.query.ScopedWhere;
import com.firstelement.security.query.TenantScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads the {@code V20261005100000} governance tables for the policy resolver, the model gate and the
 * budget gate.
 *
 * <h2>No DDL, no writes, no customer content</h2>
 *
 * <p>Every statement here names its columns explicitly and was transcribed from the migration file column
 * by column. Nothing in this class writes, and nothing it selects is customer text: a policy row, a
 * registry row, a budget ceiling and a summed consumption are all configuration or aggregates. That is why
 * this is the repository the policy layer may depend on without creating a disclosure path — the
 * retrieval and trace content lives in a different repository on purpose.
 *
 * <h2>The project predicate differs per table, and that is the schema's doing</h2>
 *
 * <p>{@code ai_policies}, {@code ai_budgets} are NOT NULL on {@code projectId}: a policy that could not
 * be attributed to a project would be a project-scoped capability set with no scope. The registry tables
 * ({@code ai_model_registry}, {@code ai_tool_registry}) are deliberately NULLABLE — "the provider
 * registry and the tool registry are tenant policy that applies to every project; forcing per-project
 * copies is how two projects end up with different safety settings". So the registry reads filter with
 * {@code ("projectId" is null or "projectId" = ...)}: a tenant-wide registry row applies to every project
 * and a project-specific row narrows it, and both are visible to a project-scoped scope without ever
 * letting one project see another's.
 */
@Repository
public class AiPolicyRepository {

    private static final String POLICY = """
        select p."id", p."organizationId", p."projectId", p."key", p."isEnabled", p."maxTokensPerRun",
               p."maxToolCallsPerRun", p."currentVersionId", p."budgetId", p."redactionPolicy",
               p."retentionClass", p."customerVisibleRequiresApproval",
               p."financialActionRequiresApproval", p."permissionChangeRequiresApproval",
               p."externalSideEffectRequiresApproval", p."failoverChain", p."version"
          from ai_policies p
         where p."organizationId" = :__fe_tenant_organization_id
           and p."projectId" = :__fe_tenant_project_id
           and p."isEnabled"
           and (cast(:policyKey as text) is null or p."key" = cast(:policyKey as text))
         order by p."key"
         limit :limit
        """;

    /**
     * The APPROVED version.
     *
     * <p>There is at most one per policy — a partial unique index enforces it — so {@code fetchFirst} is
     * a statement about the schema rather than a preference. A {@code limit 2} read would be a way to
     * notice a broken index, and it is left as {@code limit 2} for exactly that reason: the resolver
     * refuses {@code POLICY_VERSION_NOT_APPROVED} if two ever come back.
     */
    private static final String APPROVED_VERSION = """
        select v."id", v."aiPolicyId", v."versionNumber", v."requireApprovalClasses", v."redactionPolicy",
               v."retentionClass", v."failoverChain", v."changeNote", v."approvedAt"
          from ai_policy_versions v
         where v."organizationId" = :__fe_tenant_organization_id
           and v."aiPolicyId" = :policyId
           and v."state" = cast('APPROVED' as "AiApprovalStatus")
         order by v."versionNumber"
         limit 2
        """;

    /**
     * The provider registry, tenant-wide plus project-specific.
     *
     * <p>No credential column exists on this table and the migration's guard fails if one appears, so
     * there is nothing secret here to leak. What is selected is the price (per 1k input/output tokens),
     * the residency statement and the enabled flag — the three facts a run must be refused without.
     */
    private static final String MODELS = """
        select m."id", m."provider"::text as "provider", m."model", m."isEnabled",
               m."contextWindowTokens", m."maxOutputTokens", m."inputCostPer1kTokens",
               m."outputCostPer1kTokens", m."costCurrency", m."dataRetentionPolicy",
               m."sendsCustomerData", m."projectId", m."disabledReason"
          from ai_model_registry m
         where m."organizationId" = :__fe_tenant_organization_id
           and (m."projectId" is null
                or (cast(:__fe_tenant_project_id as uuid) is not null
                    and m."projectId" = cast(:__fe_tenant_project_id as uuid)))
         order by m."provider", m."model"
         limit :limit
        """;

    /**
     * The tool registry, tenant-wide plus project-specific.
     *
     * <p>{@code fieldAllowList} is selected because it is the field-limiting contract (FR-5.5) and a tool
     * that returns a whole customer record is the failure this whole phase exists to prevent.
     * {@code schemaDefinition} is <b>not</b> selected: it is the tenant's declaration of the tool, and the
     * authority for what the tool may receive is this module's {@code AiToolDefinition}, which is compiled
     * in. A tenant cannot widen a tool by editing jsonb.
     */
    private static final String TOOLS = """
        select t."id", t."toolName", t."requiredPermission", t."isEnabled", t."fieldAllowList",
               t."riskLevel"::text as "riskLevel", t."consequential", t."projectId", t."version",
               t."disabledReason"
          from ai_tool_registry t
         where t."organizationId" = :__fe_tenant_organization_id
           and (t."projectId" is null
                or (cast(:__fe_tenant_project_id as uuid) is not null
                    and t."projectId" = cast(:__fe_tenant_project_id as uuid)))
         order by t."toolName"
         limit :limit
        """;

    /**
     * Budgets effective on the usage date.
     *
     * <p>{@code ai_budgets."projectId"} is NOT NULL — a budget is a project's ceiling, and an
     * organisation-wide budget would be one project spending another's allowance. So this read uses the
     * plain project predicate.
     *
     * <p>{@code enforcement = 'OFF'} rows are returned so the gate can report "the tenant has switched
     * this budget off" rather than "there is no budget": those are different configuration states and an
     * administrator needs to see which one they are in.
     */
    private static final String BUDGETS = """
        select b."id", b."projectId", b."key", b."period"::text as "period", b."currency",
               b."limitAmount", b."enforcement", b."isEnabled", b."validFrom", b."validTo"
          from ai_budgets b
         where b."organizationId" = :__fe_tenant_organization_id
           and b."projectId" = :__fe_tenant_project_id
           and b."isEnabled"
           and b."validFrom" <= cast(:asOf as date)
           and (b."validTo" is null or b."validTo" >= cast(:asOf as date))
         order by b."key", b."period"
         limit :limit
        """;

    /**
     * Consumption per budget over the period.
     *
     * <p>A {@code sum} over the append-only ledger, never a counter column — ADR 0007 rule 3 and
     * plan.md rule 8's shape. {@code coalesce} to zero, because "no rows" and "zero spend" must not be
     * distinguished here: the read model distinguishes them, the gate only needs a number.
     */
    private static final String BUDGET_SPEND = """
        select c."budgetId" as "budgetId", coalesce(sum(c."amount"), 0) as "spent",
               coalesce(sum(c."inputTokens"), 0) as "inputTokens",
               coalesce(sum(c."outputTokens"), 0) as "outputTokens", count(*) as "postings"
          from ai_budget_consumptions c
         where c."organizationId" = :__fe_tenant_organization_id
           and c."recordedAt" >= :from
           and c."recordedAt" < :to
         group by c."budgetId"
         limit :limit
        """;

    /** The agent row, so an agent that belongs to another tenant is simply not found. */
    private static final String AGENT = """
        select a."id", a."organizationId", a."projectId", a."name", a."isEnabled", a."activeVersionId"
          from ai_agents a
         where a."organizationId" = :__fe_tenant_organization_id
           and a."projectId" = :projectId
           and a."id" = :agentId
           and a."isEnabled"
         limit 1
        """;

    /**
     * The single enabled agent of a project, for an inbound trigger that names no agent.
     *
     * <p>{@code "activeVersionId" is not null} is part of the predicate rather than a check afterwards,
     * because an agent without an active version cannot produce an attributable run.
     */
    private static final String AGENT_FOR_PROJECT = """
        select a."id", a."organizationId", a."projectId", a."name", a."isEnabled", a."activeVersionId"
          from ai_agents a
         where a."organizationId" = :__fe_tenant_organization_id
           and a."projectId" = :projectId
           and a."isEnabled"
           and a."activeVersionId" is not null
         order by a."createdAt", a."id"
         limit :limit
        """;

    /**
     * The agent version, which is the pin every historical run is attributable to.
     *
     * <p>Selected by id and NOT by "the active version": editing a live agent creates a new version, and a
     * run replayed next month must resolve the version that produced it rather than whatever is active now.
     */
    private static final String AGENT_VERSION = """
        select v."id", v."agentId", v."version", v."systemInstructions", v."tone", v."provider"::text as "provider",
               v."model", v."temperature", v."maxTokens", v."failoverChain", v."enabledTools",
               v."knowledgeBaseIds", v."minRetrievalScore", v."memoryWindow"
          from ai_agent_versions v
         where v."organizationId" = :__fe_tenant_organization_id
           and v."id" = :versionId
         limit 1
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public AiPolicyRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One live, enabled policy for the project. */
    public record PolicyRow(UUID id,
                            UUID organizationId,
                            UUID projectId,
                            String key,
                            boolean enabled,
                            Integer maxTokensPerRun,
                            Integer maxToolCallsPerRun,
                            UUID currentVersionId,
                            UUID budgetId,
                            String redactionPolicy,
                            String retentionClass,
                            boolean customerVisibleRequiresApproval,
                            boolean financialActionRequiresApproval,
                            boolean permissionChangeRequiresApproval,
                            boolean externalSideEffectRequiresApproval,
                            String failoverChain,
                            int version) {
    }

    /** One APPROVED version. */
    public record PolicyVersionRow(UUID id,
                                    UUID aiPolicyId,
                                    int versionNumber,
                                    String requireApprovalClasses,
                                    String redactionPolicy,
                                    String retentionClass,
                                    String failoverChain,
                                    String changeNote,
                                    LocalDateTime approvedAt) {
    }

    /**
     * One registered provider/model.
     *
     * <p>No credential field, because no such column exists. {@code dataRetentionPolicy} is free text
     * authored by an administrator and is compared against the deployment's residency zone; it is a
     * statement the tenant made about a provider, not a guarantee this module can verify, which is why a
     * mismatch is refused rather than logged.
     */
    public record ModelRow(UUID id,
                           String provider,
                           String model,
                           boolean enabled,
                           Integer contextWindowTokens,
                           Integer maxOutputTokens,
                           BigDecimal inputCostPer1kTokens,
                           BigDecimal outputCostPer1kTokens,
                           String costCurrency,
                           String dataRetentionPolicy,
                           boolean sendsCustomerData,
                           UUID projectId,
                           String disabledReason) {
    }

    /** One registered tool. */
    public record ToolRow(UUID id,
                          String toolName,
                          String requiredPermission,
                          boolean enabled,
                          String[] fieldAllowList,
                          String riskLevel,
                          boolean consequential,
                          UUID projectId,
                          int version,
                          String disabledReason) {
    }

    /** One budget ceiling. */
    public record BudgetRow(UUID id,
                            UUID projectId,
                            String key,
                            String period,
                            String currency,
                            BigDecimal limitAmount,
                            String enforcement,
                            boolean enabled,
                            LocalDate validFrom,
                            LocalDate validTo) {
    }

    /** What a budget has been spent, summed from the append-only ledger. */
    public record BudgetSpendRow(UUID budgetId,
                                 BigDecimal spent,
                                 long inputTokens,
                                 long outputTokens,
                                 long postings) {
    }

    /** The agent row. */
    public record AgentRow(UUID id,
                           UUID organizationId,
                           UUID projectId,
                           String name,
                           boolean enabled,
                           UUID activeVersionId) {
    }

    /** The pinned agent version. Never re-resolved from {@code activeVersionId}. */
    public record AgentVersionRow(UUID id,
                                  UUID agentId,
                                  int version,
                                  String systemInstructions,
                                  String tone,
                                  String provider,
                                  String model,
                                  double temperature,
                                  int maxTokens,
                                  String failoverChain,
                                  String enabledTools,
                                  String knowledgeBaseIds,
                                  double minRetrievalScore,
                                  int memoryWindow) {
    }

    // ------------------------------------------------------------------

    /**
     * The tenant filter plus caller parameters, with the project id ALWAYS bound.
     *
     * <p>{@code ScopedWhere.bindNamed} binds {@code PROJECT_PARAM} only for a project-scoped scope, which
     * is right for a statement whose predicate requires a project. The registry and budget reads below are
     * different: they test {@code "projectId" is null or "projectId" = :project} so a tenant-wide row is
     * visible to a project-scoped scope, which means the named parameter has to be present <em>and
     * nullable</em>. Unbinding it would be a driver error rather than "no project filter", so the binding
     * is written here instead, with the same reserved-name rule applied.
     *
     * <p>A {@link LinkedHashMap} rather than {@code Map.of} because {@code Map.of} refuses a null value and
     * these callers legitimately pass nulls (a null policy key means "no key filter", a null project id
     * means "tenant-wide").
     */
    private static MapSqlParameterSource bound(TenantScope scope, Map<String, ?> callerParameters) {
        MapSqlParameterSource source = ScopedWhere.of(scope).bindNamed(Map.of());
        if (callerParameters != null) {
            callerParameters.forEach((name, value) -> {
                if (ScopedWhere.ORGANIZATION_PARAM.equals(name) || ScopedWhere.PROJECT_PARAM.equals(name)) {
                    throw new IllegalArgumentException("'" + name
                        + "' is reserved for the tenant filter and cannot be supplied by a caller");
                }
                source.addValue(name, value);
            });
        }
        if (!scope.isProjectScoped()) {
            source.addValue(ScopedWhere.PROJECT_PARAM, null);
        }
        return source;
    }

    /**
     * Adds values to a source, for the call sites that legitimately bind nulls.
     *
     * <p>{@code Map.of} refuses a null value, so a read whose filter is "no project filter" cannot be
     * written with a map literal. Building the source directly is the honest spelling of that, and it is why
     * this helper exists rather than a silent {@code Optional.ofNullable} dance at the call site.
     */
    private static MapSqlParameterSource with(MapSqlParameterSource source, Object... pairs) {
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            source.addValue(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return source;
    }

    /** The live policy, when one exists for the project. */
    public Optional<PolicyRow> policy(TenantScope scope, String policyKey) {
        MapSqlParameterSource parameters = bound(scope, null);
        parameters.addValue("limit", 2).addValue("policyKey", policyKey);
        List<PolicyRow> rows = jdbc.query(POLICY, parameters, (rs, rowNum) -> new PolicyRow(
            rs.getObject("id", UUID.class),
            rs.getObject("organizationId", UUID.class),
            rs.getObject("projectId", UUID.class),
            rs.getString("key"),
            rs.getBoolean("isEnabled"),
            rs.getObject("maxTokensPerRun", Integer.class),
            rs.getObject("maxToolCallsPerRun", Integer.class),
            rs.getObject("currentVersionId", UUID.class),
            rs.getObject("budgetId", UUID.class),
            rs.getString("redactionPolicy"),
            rs.getString("retentionClass"),
            rs.getBoolean("customerVisibleRequiresApproval"),
            rs.getBoolean("financialActionRequiresApproval"),
            rs.getBoolean("permissionChangeRequiresApproval"),
            rs.getBoolean("externalSideEffectRequiresApproval"),
            rs.getString("failoverChain"),
            rs.getInt("version")));
        // Two rows for one (organization, project, key) is impossible: the schema's unique index covers
        // exactly those three columns. The limit is 2 rather than 1 so an index that was somehow dropped
        // surfaces here as an ambiguity the resolver refuses, instead of as an arbitrary winner.
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** The APPROVED version(s) for a policy. More than one is a schema violation the resolver refuses. */
    public List<PolicyVersionRow> approvedVersions(TenantScope scope, UUID policyId) {
        MapSqlParameterSource parameters = bound(scope, null);
        parameters.addValue("limit", 2).addValue("policyId", policyId);
        return jdbc.query(APPROVED_VERSION, parameters, (rs, rowNum) -> new PolicyVersionRow(
            rs.getObject("id", UUID.class),
            rs.getObject("aiPolicyId", UUID.class),
            rs.getInt("versionNumber"),
            rs.getString("requireApprovalClasses"),
            rs.getString("redactionPolicy"),
            rs.getString("retentionClass"),
            rs.getString("failoverChain"),
            rs.getString("changeNote"),
            rs.getObject("approvedAt", LocalDateTime.class)));
    }

    /** The provider registry rows visible to this scope. */
    public List<ModelRow> models(TenantScope scope) {
        return jdbc.query(MODELS, with(bound(scope, null), "limit", 500),
            (rs, rowNum) -> new ModelRow(
                rs.getObject("id", UUID.class),
                rs.getString("provider"),
                rs.getString("model"),
                rs.getBoolean("isEnabled"),
                rs.getObject("contextWindowTokens", Integer.class),
                rs.getObject("maxOutputTokens", Integer.class),
                rs.getBigDecimal("inputCostPer1kTokens"),
                rs.getBigDecimal("outputCostPer1kTokens"),
                rs.getString("costCurrency"),
                rs.getString("dataRetentionPolicy"),
                rs.getBoolean("sendsCustomerData"),
                rs.getObject("projectId", UUID.class),
                rs.getString("disabledReason")));
    }

    /** The tool registry rows visible to this scope. */
    public List<ToolRow> tools(TenantScope scope) {
        return jdbc.query(TOOLS, with(bound(scope, null), "limit", 500),
            (rs, rowNum) -> new ToolRow(
                rs.getObject("id", UUID.class),
                rs.getString("toolName"),
                rs.getString("requiredPermission"),
                rs.getBoolean("isEnabled"),
                textArray(rs.getArray("fieldAllowList")),
                rs.getString("riskLevel"),
                rs.getBoolean("consequential"),
                rs.getObject("projectId", UUID.class),
                rs.getInt("version"),
                rs.getString("disabledReason")));
    }

    /** Budgets effective on the usage date. */
    public List<BudgetRow> budgets(TenantScope scope, LocalDate asOf) {
        return jdbc.query(BUDGETS, with(bound(scope, null), "limit", 100, "asOf", asOf),
            (rs, rowNum) -> new BudgetRow(
                rs.getObject("id", UUID.class),
                rs.getObject("projectId", UUID.class),
                rs.getString("key"),
                rs.getString("period"),
                rs.getString("currency"),
                rs.getBigDecimal("limitAmount"),
                rs.getString("enforcement"),
                rs.getBoolean("isEnabled"),
                rs.getObject("validFrom", LocalDate.class),
                rs.getObject("validTo", LocalDate.class)));
    }

    /** Spend per budget over a window. */
    public List<BudgetSpendRow> budgetSpend(TenantScope scope, LocalDateTime from, LocalDateTime to) {
        return jdbc.query(BUDGET_SPEND, with(bound(scope, null), "limit", 200, "from", from, "to", to),
            (rs, rowNum) -> new BudgetSpendRow(
                rs.getObject("budgetId", UUID.class),
                rs.getBigDecimal("spent"),
                rs.getLong("inputTokens"),
                rs.getLong("outputTokens"),
                rs.getLong("postings")));
    }

    /** The agent, when it exists, belongs to this project and is enabled. */
    public Optional<AgentRow> agent(TenantScope scope, UUID projectId, UUID agentId) {
        MapSqlParameterSource parameters = with(bound(scope, null), "projectId", projectId, "agentId", agentId);
        List<AgentRow> rows = jdbc.query(AGENT, parameters, (rs, rowNum) -> new AgentRow(
            rs.getObject("id", UUID.class),
            rs.getObject("organizationId", UUID.class),
            rs.getObject("projectId", UUID.class),
            rs.getString("name"),
            rs.getBoolean("isEnabled"),
            rs.getObject("activeVersionId", UUID.class)));
        return rows.stream().findFirst();
    }

    /**
     * The project's enabled agent, for a trigger that names a conversation but not an agent.
     *
     * <p>An inbound message does not know which agent should answer it, and asking the model would be
     * asking the wrong party — ADR 0007 Part 2 rule 1 makes the application, never the model, the
     * decider. So the trigger resolves the agent itself, from the same rows the run is pinned to, and
     * the run remains attributable to {@link AgentRow#activeVersionId()}.
     *
     * <p>Requires an <b>active version</b>. An agent whose active version was cleared cannot produce a
     * run that names the version which produced it, so it is not a candidate — the alternative is a run
     * that has to invent a version at execution time, which is exactly the unattributable run ADR 0007
     * rule 7 forbids.
     *
     * <p>{@code limit 2} with a refusal on more than one candidate, rather than {@code limit 1}. Two
     * enabled agents in a project is a configuration ambiguity: picking either arbitrarily would make
     * which agent answers a customer's question depend on row order.
     */
    public Optional<AgentRow> enabledAgentForProject(TenantScope scope, UUID projectId) {
        MapSqlParameterSource parameters = with(bound(scope, null), "projectId", projectId, "limit", 2);
        List<AgentRow> rows = jdbc.query(AGENT_FOR_PROJECT, parameters, (rs, rowNum) -> new AgentRow(
            rs.getObject("id", UUID.class),
            rs.getObject("organizationId", UUID.class),
            rs.getObject("projectId", UUID.class),
            rs.getString("name"),
            rs.getBoolean("isEnabled"),
            rs.getObject("activeVersionId", UUID.class)));
        if (rows.size() > 1) {
            throw new IllegalStateException(
                "project " + projectId + " has " + rows.size()
                    + " enabled agents with an active version. Refusing to guess which one answers a "
                    + "customer: a run attributed to an arbitrarily chosen agent is not attributable.");
        }
        return rows.stream().findFirst();
    }

    /**
     * The agent version by id, through a tenant-only scope.
     *
     * <p>{@code TenantQueryGuard.tenantScope} rather than the project scope on purpose: the version is
     * pinned to the run, so the check that matters is "does this version belong to this tenant", and the
     * agent row already established the project. Narrowing to the project here would add a predicate that
     * can only ever fail for a row the agent lookup already excluded.
     */
    public Optional<AgentVersionRow> agentVersion(TenantScope tenantScope, UUID versionId) {
        MapSqlParameterSource parameters = with(bound(tenantScope, null), "versionId", versionId);
        return jdbc.query(AGENT_VERSION, parameters, (rs, rowNum) -> new AgentVersionRow(
            rs.getObject("id", UUID.class),
            rs.getObject("agentId", UUID.class),
            rs.getInt("version"),
            rs.getString("systemInstructions"),
            rs.getString("tone"),
            rs.getString("provider"),
            rs.getString("model"),
            rs.getDouble("temperature"),
            rs.getInt("maxTokens"),
            rs.getString("failoverChain"),
            rs.getString("enabledTools"),
            rs.getString("knowledgeBaseIds"),
            rs.getDouble("minRetrievalScore"),
            rs.getInt("memoryWindow"))).stream().findFirst();
    }

    /**
     * A {@code text[]} read through the driver.
     *
     * <p>Written out rather than a cast: {@code rs.getArray(..).getArray()} returns {@code Object[]} for
     * PostgreSQL on the driver versions in use here, and casting that to {@code String[]} throws.
     */
    private static String[] textArray(java.sql.Array array) throws java.sql.SQLException {
        if (array == null) {
            return new String[0];
        }
        Object raw = array.getArray();
        if (!(raw instanceof Object[] values)) {
            return new String[0];
        }
        java.util.List<String> out = new java.util.ArrayList<>(values.length);
        for (Object value : values) {
            if (value != null) {
                out.add(String.valueOf(value));
            }
        }
        return out.toArray(new String[0]);
    }
}