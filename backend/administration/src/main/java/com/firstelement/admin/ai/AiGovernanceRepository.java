package com.firstelement.admin.ai;

import com.firstelement.security.query.ScopedWhere;
import com.firstelement.security.query.TenantScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The AI governance read models of {@code V20261005100000}: policies, provider registry, tool
 * registry, blocked calls, approvals, budgets and redaction records.
 *
 * <h2>The one rule this whole class exists to enforce</h2>
 *
 * <p>ADR-0007 Part 2 rule 7 makes every AI action attributable, and FR-10.6 says only the redacted
 * form may be stored. The migration then CHECKs the two halves of that: {@code ai_tool_registry} has
 * no credential-shaped column at all, {@code ai_tool_executions} has {@code argumentsRedacted} and
 * {@code resultRedacted} and no raw twin, and {@code ai_redaction_records} has {@code fieldPath} and
 * {@code valuesRedacted} and never the value.
 *
 * <p>So this repository selects <b>no jsonb column from {@code ai_tool_executions}}</b>. It takes
 * the tool name, the decision, the deny reason, the action class, the latency and the timestamps —
 * which is everything an operator needs to answer "which tool is being refused and why" — and it
 * does not select {@code argumentsRedacted} either, because a redacted argument map still reveals
 * the shape of a customer record and the shape is not needed to act on a denial. The redaction
 * <em>counts</em> come from {@code ai_redaction_records}, which is the table whose entire purpose is
 * to hold counts instead of values.
 *
 * <h2>Why a disabled tool is a first-class answer</h2>
 *
 * <p>{@code ai_tool_registry_disabled_check} is a biconditional: {@code isEnabled = false} requires
 * {@code disabledAt}, {@code disabledByUserId} and {@code disabledReason}. So "a disabled tool is
 * visible" is not a nicety — the schema makes the reason unskippable, and this read model returns it.
 */
@Repository
public class AiGovernanceRepository {

    /** The live policies, with the four ADR-0007 approval classes spelled out. */
    private static final String POLICIES = """
        select p."id", p."organizationId", p."projectId", p."key", p."nameAr", p."nameEn",
               p."isEnabled", p."maxTokensPerRun", p."maxToolCallsPerRun", p."redactionPolicy",
               p."retentionClass", p."customerVisibleRequiresApproval",
               p."financialActionRequiresApproval", p."permissionChangeRequiresApproval",
               p."externalSideEffectRequiresApproval", p."currentVersionId", p."version",
               p."updatedAt"
          from ai_policies p
         where p."organizationId" = :__fe_tenant_organization_id
           and (cast(:filterProjectId as uuid) is null or p."projectId" = cast(:filterProjectId as uuid))
         order by p."projectId" nulls first, p."key"
         limit :limit
        """;

    /** The approved version in force for each policy: at most one, by a partial unique index. */
    private static final String APPROVED_VERSIONS = """
        select v."aiPolicyId", v."versionNumber", v."state"::text as "state", v."requireApprovalClasses",
               v."changeNote", v."approvedAt", v."approvedByUserId", v."redactionPolicy",
               v."retentionClass"
          from ai_policy_versions v
         where v."organizationId" = :__fe_tenant_organization_id
           and v."state" = cast('APPROVED' as "AiApprovalStatus")
         order by v."versionNumber" desc
         limit :limit
        """;

    /** The provider registry. A registry, never a credential store — the migration guards that. */
    private static final String MODELS = """
        select m."id", m."organizationId", m."projectId", m."provider"::text as "provider", m."model",
               m."displayNameAr", m."displayNameEn", m."isEnabled", m."contextWindowTokens",
               m."maxOutputTokens", m."inputCostPer1kTokens", m."outputCostPer1kTokens",
               m."costCurrency", m."dataRetentionPolicy", m."sendsCustomerData", m."disabledAt",
               m."disabledReason"
          from ai_model_registry m
         where m."organizationId" = :__fe_tenant_organization_id
         order by m."provider", m."model"
         limit :limit
        """;

    /** The tool registry, deny by default, with the disable record the biconditional requires. */
    private static final String TOOLS = """
        select t."id", t."organizationId", t."projectId", t."toolName", t."descriptionAr",
               t."requiredPermission", t."isEnabled", t."disabledAt", t."disabledByUserId",
               t."disabledReason", t."enabledAt", t."enabledByUserId", t."fieldAllowList",
               t."riskLevel"::text as "riskLevel", t."consequential", t."version", t."updatedAt"
          from ai_tool_registry t
         where t."organizationId" = :__fe_tenant_organization_id
         order by t."toolName"
         limit :limit
        """;

    /**
     * Blocked tool calls.
     *
     * <p>No jsonb column. {@code argumentsRedacted} and {@code resultRedacted} are deliberately not
     * selected — see the class note.
     */
    private static final String BLOCKED_CALLS = """
        select x."id", x."organizationId", x."projectId", x."aiRunId", x."toolName", x."actionClass",
               x."consequentialAction", x."decision"::text as "decision", x."denyReason", x."principalType",
               x."actorUserId", x."latencyMs", x."requestedAt", x."executedAt", x."approvalId"
          from ai_tool_executions x
         where x."organizationId" = :__fe_tenant_organization_id
           and (cast(:filterProjectId as uuid) is null or x."projectId" = cast(:filterProjectId as uuid))
           and (cast(:filterDecision as text) is null
                or x."decision"::text = cast(:filterDecision as text))
           and (cast(:filterTool as text) is null or x."toolName" = cast(:filterTool as text))
         order by x."requestedAt" desc, x."id" desc
         limit :limit
        """;

    /** Blocked-call counts per tool and reason: the "which tool keeps being refused" rollup. */
    private static final String BLOCKED_SUMMARY = """
        select x."toolName" as tool, x."decision"::text as decision, x."denyReason" as reason,
               count(*) as occurrences, max(x."requestedAt") as last_seen
          from ai_tool_executions x
         where x."organizationId" = :__fe_tenant_organization_id
           and x."decision" <> cast('ALLOW' as "AiPolicyDecision")
         group by x."toolName", x."decision", x."denyReason"
         order by count(*) desc, x."toolName"
         limit :limit
        """;

    /** Human approval requests: the consequential queue. */
    private static final String APPROVALS = """
        select a."id", a."organizationId", a."projectId", a."aiRunId", a."action", a."actionClass",
               a."status"::text as "status", a."requestedByUserId", a."requestedByPrincipal",
               a."reason", a."requestedAt", a."decidedByUserId", a."decidedAt", a."decisionNote",
               a."revokedByUserId", a."revokedAt", a."revokeReason", a."expiresAt"
          from ai_approvals a
         where a."organizationId" = :__fe_tenant_organization_id
           and (cast(:filterProjectId as uuid) is null or a."projectId" = cast(:filterProjectId as uuid))
           and (cast(:filterStatus as text) is null or a."status"::text = cast(:filterStatus as text))
         order by a."requestedAt" desc, a."id" desc
         limit :limit
        """;

    /** The decision history for one approval: the mutable row is state, this is the evidence. */
    private static final String APPROVAL_DECISIONS = """
        select d."id", d."approvalId", d."fromStatus"::text as "fromStatus",
               d."toStatus"::text as "toStatus", d."actorUserId", d."actorPrincipal", d."note",
               d."decidedAt"
          from ai_approval_decisions d
         where d."organizationId" = :__fe_tenant_organization_id
           and d."approvalId" = :approvalId
         order by d."decidedAt", d."id"
         limit :limit
        """;

    /** Budgets: the ceiling, the enforcement point, and whether it is switched on. */
    private static final String BUDGETS = """
        select b."id", b."organizationId", b."projectId", b."key", b."nameAr", b."nameEn",
               b."period"::text as "period", b."currency", b."limitAmount", b."enforcement",
               b."isEnabled", b."validFrom", b."validTo"
          from ai_budgets b
         where b."organizationId" = :__fe_tenant_organization_id
           and (cast(:filterProjectId as uuid) is null or b."projectId" = cast(:filterProjectId as uuid))
         order by b."key", b."period"
         limit :limit
        """;

    /**
     * Spend per budget: a SUM over immutable consumption rows.
     *
     * <p>Not a counter column and not a mutable total — ADR-0007 rule 3 and plan.md rule 8's shape,
     * applied to budgets: "what have we spent" must be derivable from the same ledger the finance
     * read model uses, so the figure can be re-derived months later.
     */
    private static final String BUDGET_SPEND = """
        select c."budgetId" as budget_id, c."currency" as currency,
               sum(c."amount") as spent, sum(c."inputTokens") as input_tokens,
               sum(c."outputTokens") as output_tokens, min(c."periodStart") as period_start,
               max(c."periodEnd") as period_end, count(*) as postings
          from ai_budget_consumptions c
         where c."organizationId" = :__fe_tenant_organization_id
           and c."recordedAt" >= :since
         group by c."budgetId", c."currency"
         order by c."budgetId"
         limit :limit
        """;

    /**
     * Redaction records: WHICH policy, WHICH rule, WHICH field, HOW MANY values.
     *
     * <p>Never the values — the migration's guard fails if a column named {@code rawValue} or
     * {@code originalText} appears on these tables, and this statement is the read side of that
     * promise. {@code fieldPath} is a schema path such as {@code customer.primaryPhone}: the shape of
     * what was scrubbed, which is what a compliance report needs, and not the content.
     */
    private static final String REDACTIONS = """
        select r."id", r."organizationId", r."projectId", r."aiRunId", r."policyId",
               r."policyVersionId", r."redactionPolicy", r."ruleId", r."fieldPath",
               r."valuesRedacted", r."charactersRedacted", r."redactedAt"
          from ai_redaction_records r
         where r."organizationId" = :__fe_tenant_organization_id
           and (cast(:filterProjectId as uuid) is null or r."projectId" = cast(:filterProjectId as uuid))
         order by r."redactedAt" desc, r."id" desc
         limit :limit
        """;

    /** Redaction rollup per rule, for the compliance view. */
    private static final String REDACTION_SUMMARY = """
        select r."redactionPolicy" as policy, r."ruleId" as rule, r."fieldPath" as field,
               sum(r."valuesRedacted") as values_redacted,
               sum(r."charactersRedacted") as characters_redacted,
               max(r."redactedAt") as last_seen
          from ai_redaction_records r
         where r."organizationId" = :__fe_tenant_organization_id
         group by r."redactionPolicy", r."ruleId", r."fieldPath"
         order by sum(r."valuesRedacted") desc
         limit :limit
        """;

    /** Governance tallies for the overview. */
    private static final String SUMMARY = """
        select (select count(*) from ai_policies p
                 where p."organizationId" = :__fe_tenant_organization_id
                   and p."isEnabled") as enabled_policies,
               (select count(*) from ai_tool_registry t
                 where t."organizationId" = :__fe_tenant_organization_id
                   and t."isEnabled") as enabled_tools,
               (select count(*) from ai_tool_registry t
                 where t."organizationId" = :__fe_tenant_organization_id
                   and not t."isEnabled") as disabled_tools,
               (select count(*) from ai_tool_executions x
                 where x."organizationId" = :__fe_tenant_organization_id
                   and x."decision" = cast('DENY' as "AiPolicyDecision")) as denied_calls,
               (select count(*) from ai_tool_executions x
                 where x."organizationId" = :__fe_tenant_organization_id
                   and x."decision" = cast('REQUIRE_APPROVAL' as "AiPolicyDecision"))
                   as approval_required_calls,
               (select count(*) from ai_approvals a
                 where a."organizationId" = :__fe_tenant_organization_id
                   and a."status" = cast('PENDING' as "AiApprovalStatus")) as pending_approvals,
               (select count(*) from ai_budgets b
                 where b."organizationId" = :__fe_tenant_organization_id
                   and b."isEnabled") as enabled_budgets,
               (select count(*) from ai_model_registry m
                 where m."organizationId" = :__fe_tenant_organization_id
                   and m."isEnabled") as enabled_models,
               (select coalesce(sum(r."valuesRedacted"), 0) from ai_redaction_records r
                 where r."organizationId" = :__fe_tenant_organization_id) as redacted_values
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public AiGovernanceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One policy and its four approval classes. */
    public record PolicyRow(UUID id,
                            UUID organizationId,
                            UUID projectId,
                            String key,
                            String nameAr,
                            String nameEn,
                            boolean enabled,
                            Integer maxTokensPerRun,
                            Integer maxToolCallsPerRun,
                            String redactionPolicy,
                            String retentionClass,
                            boolean customerVisibleRequiresApproval,
                            boolean financialActionRequiresApproval,
                            boolean permissionChangeRequiresApproval,
                            boolean externalSideEffectRequiresApproval,
                            UUID currentVersionId,
                            int version,
                            LocalDateTime updatedAt) {
    }

    /** The one approved version per policy. */
    public record PolicyVersionRow(UUID policyId,
                                   int versionNumber,
                                   String state,
                                   String[] requireApprovalClasses,
                                   String changeNote,
                                   LocalDateTime approvedAt,
                                   UUID approvedByUserId,
                                   String redactionPolicy,
                                   String retentionClass) {
    }

    /** One registered provider/model. No credential column exists, and none is invented here. */
    public record ModelRow(UUID id,
                           UUID organizationId,
                           UUID projectId,
                           String provider,
                           String model,
                           String displayNameAr,
                           String displayNameEn,
                           boolean enabled,
                           Integer contextWindowTokens,
                           Integer maxOutputTokens,
                           BigDecimal inputCostPer1kTokens,
                           BigDecimal outputCostPer1kTokens,
                           String costCurrency,
                           String dataRetentionPolicy,
                           boolean sendsCustomerData,
                           LocalDateTime disabledAt,
                           String disabledReason) {
    }

    /** One registered tool, with the record that says why it is off. */
    public record ToolRow(UUID id,
                          UUID organizationId,
                          UUID projectId,
                          String toolName,
                          String descriptionAr,
                          String requiredPermission,
                          boolean enabled,
                          LocalDateTime disabledAt,
                          UUID disabledByUserId,
                          String disabledReason,
                          LocalDateTime enabledAt,
                          UUID enabledByUserId,
                          String[] fieldAllowList,
                          String riskLevel,
                          boolean consequential,
                          int version,
                          LocalDateTime updatedAt) {
    }

    /**
     * One tool call that was not simply allowed.
     *
     * <p>No argument or result field of any kind. {@code toolName}, {@code actionClass} and
     * {@code denyReason} are what an operator acts on; anything else would be the customer content
     * the redaction rules exist to keep out of a log.
     */
    public record BlockedCallRow(UUID id,
                                 UUID organizationId,
                                 UUID projectId,
                                 UUID aiRunId,
                                 String toolName,
                                 String actionClass,
                                 boolean consequentialAction,
                                 String decision,
                                 String denyReason,
                                 String principalType,
                                 UUID actorUserId,
                                 Integer latencyMs,
                                 LocalDateTime requestedAt,
                                 LocalDateTime executedAt,
                                 UUID approvalId) {
    }

    /** One approval request. */
    public record ApprovalRow(UUID id,
                              UUID organizationId,
                              UUID projectId,
                              UUID aiRunId,
                              String action,
                              String actionClass,
                              String status,
                              UUID requestedByUserId,
                              String requestedByPrincipal,
                              String reason,
                              LocalDateTime requestedAt,
                              UUID decidedByUserId,
                              LocalDateTime decidedAt,
                              String decisionNote,
                              UUID revokedByUserId,
                              LocalDateTime revokedAt,
                              String revokeReason,
                              LocalDateTime expiresAt) {
    }

    /** One append-only transition of an approval. */
    public record ApprovalDecisionRow(UUID id,
                                      UUID approvalId,
                                      String fromStatus,
                                      String toStatus,
                                      UUID actorUserId,
                                      String actorPrincipal,
                                      String note,
                                      LocalDateTime decidedAt) {
    }

    /** One budget ceiling. */
    public record BudgetRow(UUID id,
                            UUID organizationId,
                            UUID projectId,
                            String key,
                            String nameAr,
                            String nameEn,
                            String period,
                            String currency,
                            BigDecimal limitAmount,
                            String enforcement,
                            boolean enabled,
                            LocalDate validFrom,
                            LocalDate validTo) {
    }

    /** What a budget has been spent, summed from the immutable ledger. */
    public record BudgetSpendRow(UUID budgetId,
                                 String currency,
                                 BigDecimal spent,
                                 Long inputTokens,
                                 Long outputTokens,
                                 LocalDate periodStart,
                                 LocalDate periodEnd,
                                 long postings) {
    }

    /** One redaction record: policy, rule, field, and how many values. Never the values. */
    public record RedactionRow(UUID id,
                               UUID organizationId,
                               UUID projectId,
                               UUID aiRunId,
                               UUID policyId,
                               UUID policyVersionId,
                               String redactionPolicy,
                               String ruleId,
                               String fieldPath,
                               int valuesRedacted,
                               Integer charactersRedacted,
                               LocalDateTime redactedAt) {
    }

    /** Governance tallies. */
    public record AiSummary(long enabledPolicies,
                            long enabledTools,
                            long disabledTools,
                            long deniedCalls,
                            long approvalRequiredCalls,
                            long pendingApprovals,
                            long enabledBudgets,
                            long enabledModels,
                            long redactedValues) {
    }

    /** The tenant's AI policies. */
    public List<PolicyRow> policies(TenantScope scope, UUID projectId, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("limit", Math.max(1, limit)));
        parameters.addValue("filterProjectId", projectId);
        return jdbc.query(POLICIES, parameters, (rs, rowNum) -> new PolicyRow(
            rs.getObject("id", UUID.class),
            rs.getObject("organizationId", UUID.class),
            rs.getObject("projectId", UUID.class),
            rs.getString("key"),
            rs.getString("nameAr"),
            rs.getString("nameEn"),
            rs.getBoolean("isEnabled"),
            rs.getObject("maxTokensPerRun", Integer.class),
            rs.getObject("maxToolCallsPerRun", Integer.class),
            rs.getString("redactionPolicy"),
            rs.getString("retentionClass"),
            rs.getBoolean("customerVisibleRequiresApproval"),
            rs.getBoolean("financialActionRequiresApproval"),
            rs.getBoolean("permissionChangeRequiresApproval"),
            rs.getBoolean("externalSideEffectRequiresApproval"),
            rs.getObject("currentVersionId", UUID.class),
            rs.getInt("version"),
            rs.getObject("updatedAt", LocalDateTime.class)));
    }

    /** The approved version per policy. */
    public List<PolicyVersionRow> approvedVersions(TenantScope scope, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("limit", Math.max(1, limit)));
        return jdbc.query(APPROVED_VERSIONS, parameters, (rs, rowNum) -> new PolicyVersionRow(
            rs.getObject("aiPolicyId", UUID.class),
            rs.getInt("versionNumber"),
            rs.getString("state"),
            toStringArray(rs.getArray("requireApprovalClasses")),
            rs.getString("changeNote"),
            rs.getObject("approvedAt", LocalDateTime.class),
            rs.getObject("approvedByUserId", UUID.class),
            rs.getString("redactionPolicy"),
            rs.getString("retentionClass")));
    }

    /** The provider registry. */
    public List<ModelRow> models(TenantScope scope, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("limit", Math.max(1, limit)));
        return jdbc.query(MODELS, parameters, (rs, rowNum) -> new ModelRow(
            rs.getObject("id", UUID.class),
            rs.getObject("organizationId", UUID.class),
            rs.getObject("projectId", UUID.class),
            rs.getString("provider"),
            rs.getString("model"),
            rs.getString("displayNameAr"),
            rs.getString("displayNameEn"),
            rs.getBoolean("isEnabled"),
            rs.getObject("contextWindowTokens", Integer.class),
            rs.getObject("maxOutputTokens", Integer.class),
            rs.getObject("inputCostPer1kTokens", BigDecimal.class),
            rs.getObject("outputCostPer1kTokens", BigDecimal.class),
            rs.getString("costCurrency"),
            rs.getString("dataRetentionPolicy"),
            rs.getBoolean("sendsCustomerData"),
            rs.getObject("disabledAt", LocalDateTime.class),
            rs.getString("disabledReason")));
    }

    /** The tool registry, with the deny-by-default state and the reason it is in that state. */
    public List<ToolRow> tools(TenantScope scope, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("limit", Math.max(1, limit)));
        return jdbc.query(TOOLS, parameters, (rs, rowNum) -> new ToolRow(
            rs.getObject("id", UUID.class),
            rs.getObject("organizationId", UUID.class),
            rs.getObject("projectId", UUID.class),
            rs.getString("toolName"),
            rs.getString("descriptionAr"),
            rs.getString("requiredPermission"),
            rs.getBoolean("isEnabled"),
            rs.getObject("disabledAt", LocalDateTime.class),
            rs.getObject("disabledByUserId", UUID.class),
            rs.getString("disabledReason"),
            rs.getObject("enabledAt", LocalDateTime.class),
            rs.getObject("enabledByUserId", UUID.class),
            toStringArray(rs.getArray("fieldAllowList")),
            rs.getString("riskLevel"),
            rs.getBoolean("consequential"),
            rs.getInt("version"),
            rs.getObject("updatedAt", LocalDateTime.class)));
    }

    /** Blocked tool calls. */
    public List<BlockedCallRow> blockedCalls(TenantScope scope, UUID projectId, String decision,
                                             String toolName, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("limit", Math.max(1, limit)));
        parameters.addValue("filterProjectId", projectId);
        parameters.addValue("filterDecision", normalizeDecision(decision));
        parameters.addValue("filterTool", toolName);
        return jdbc.query(BLOCKED_CALLS, parameters, (rs, rowNum) -> new BlockedCallRow(
            rs.getObject("id", UUID.class),
            rs.getObject("organizationId", UUID.class),
            rs.getObject("projectId", UUID.class),
            rs.getObject("aiRunId", UUID.class),
            rs.getString("toolName"),
            rs.getString("actionClass"),
            rs.getBoolean("consequentialAction"),
            rs.getString("decision"),
            rs.getString("denyReason"),
            rs.getString("principalType"),
            rs.getObject("actorUserId", UUID.class),
            rs.getObject("latencyMs", Integer.class),
            rs.getObject("requestedAt", LocalDateTime.class),
            rs.getObject("executedAt", LocalDateTime.class),
            rs.getObject("approvalId", UUID.class)));
    }

    /** The blocked-call rollup, per tool and reason. */
    public List<Map<String, Object>> blockedSummary(TenantScope scope, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("limit", Math.max(1, limit)));
        return jdbc.queryForList(BLOCKED_SUMMARY, parameters);
    }

    /** Approval requests, newest first. */
    public List<ApprovalRow> approvals(TenantScope scope, UUID projectId, String status, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("limit", Math.max(1, limit)));
        parameters.addValue("filterProjectId", projectId);
        parameters.addValue("filterStatus", status);
        return jdbc.query(APPROVALS, parameters, (rs, rowNum) -> new ApprovalRow(
            rs.getObject("id", UUID.class),
            rs.getObject("organizationId", UUID.class),
            rs.getObject("projectId", UUID.class),
            rs.getObject("aiRunId", UUID.class),
            rs.getString("action"),
            rs.getString("actionClass"),
            rs.getString("status"),
            rs.getObject("requestedByUserId", UUID.class),
            rs.getString("requestedByPrincipal"),
            rs.getString("reason"),
            rs.getObject("requestedAt", LocalDateTime.class),
            rs.getObject("decidedByUserId", UUID.class),
            rs.getObject("decidedAt", LocalDateTime.class),
            rs.getString("decisionNote"),
            rs.getObject("revokedByUserId", UUID.class),
            rs.getObject("revokedAt", LocalDateTime.class),
            rs.getString("revokeReason"),
            rs.getObject("expiresAt", LocalDateTime.class)));
    }

    /** The decision history of one approval. */
    public List<ApprovalDecisionRow> approvalDecisions(TenantScope scope, UUID approvalId, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("approvalId", approvalId, "limit", Math.max(1, limit)));
        return jdbc.query(APPROVAL_DECISIONS, parameters, (rs, rowNum) -> new ApprovalDecisionRow(
            rs.getObject("id", UUID.class),
            rs.getObject("approvalId", UUID.class),
            rs.getString("fromStatus"),
            rs.getString("toStatus"),
            rs.getObject("actorUserId", UUID.class),
            rs.getString("actorPrincipal"),
            rs.getString("note"),
            rs.getObject("decidedAt", LocalDateTime.class)));
    }

    /** Budgets. */
    public List<BudgetRow> budgets(TenantScope scope, UUID projectId, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("limit", Math.max(1, limit)));
        parameters.addValue("filterProjectId", projectId);
        return jdbc.query(BUDGETS, parameters, (rs, rowNum) -> new BudgetRow(
            rs.getObject("id", UUID.class),
            rs.getObject("organizationId", UUID.class),
            rs.getObject("projectId", UUID.class),
            rs.getString("key"),
            rs.getString("nameAr"),
            rs.getString("nameEn"),
            rs.getString("period"),
            rs.getString("currency"),
            rs.getObject("limitAmount", BigDecimal.class),
            rs.getString("enforcement"),
            rs.getBoolean("isEnabled"),
            rs.getObject("validFrom", LocalDate.class),
            rs.getObject("validTo", LocalDate.class)));
    }

    /** Spend per budget over the window, summed from the ledger. */
    public List<BudgetSpendRow> budgetSpend(TenantScope scope, LocalDateTime since, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("since", since, "limit", Math.max(1, limit)));
        return jdbc.query(BUDGET_SPEND, parameters, (rs, rowNum) -> new BudgetSpendRow(
            rs.getObject("budget_id", UUID.class),
            rs.getString("currency"),
            rs.getObject("spent", BigDecimal.class),
            rs.getObject("input_tokens", Long.class),
            rs.getObject("output_tokens", Long.class),
            rs.getObject("period_start", LocalDate.class),
            rs.getObject("period_end", LocalDate.class),
            rs.getLong("postings")));
    }

    /** Redaction records. */
    public List<RedactionRow> redactions(TenantScope scope, UUID projectId, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("limit", Math.max(1, limit)));
        parameters.addValue("filterProjectId", projectId);
        return jdbc.query(REDACTIONS, parameters, (rs, rowNum) -> new RedactionRow(
            rs.getObject("id", UUID.class),
            rs.getObject("organizationId", UUID.class),
            rs.getObject("projectId", UUID.class),
            rs.getObject("aiRunId", UUID.class),
            rs.getObject("policyId", UUID.class),
            rs.getObject("policyVersionId", UUID.class),
            rs.getString("redactionPolicy"),
            rs.getString("ruleId"),
            rs.getString("fieldPath"),
            rs.getInt("valuesRedacted"),
            rs.getObject("charactersRedacted", Integer.class),
            rs.getObject("redactedAt", LocalDateTime.class)));
    }

    /** The redaction rollup per rule. */
    public List<Map<String, Object>> redactionSummary(TenantScope scope, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("limit", Math.max(1, limit)));
        return jdbc.queryForList(REDACTION_SUMMARY, parameters);
    }

    /** Governance tallies. */
    public AiSummary summary(TenantScope scope) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of());
        return jdbc.queryForObject(SUMMARY, parameters, (rs, rowNum) -> new AiSummary(
            rs.getLong("enabled_policies"),
            rs.getLong("enabled_tools"),
            rs.getLong("disabled_tools"),
            rs.getLong("denied_calls"),
            rs.getLong("approval_required_calls"),
            rs.getLong("pending_approvals"),
            rs.getLong("enabled_budgets"),
            rs.getLong("enabled_models"),
            rs.getLong("redacted_values")));
    }

    /**
     * A {@code text[]} column read through the JDBC array API.
     *
     * <p>Written out rather than using a one-liner because {@code rs.getArray(..).getArray()} returns
     * {@code Object[]} for PostgreSQL and casting that to {@code String[]} throws on the driver
     * versions in use here.
     */
    private static String[] toStringArray(java.sql.Array array) throws java.sql.SQLException {
        if (array == null) {
            return new String[0];
        }
        Object raw = array.getArray();
        if (!(raw instanceof Object[] values)) {
            return new String[0];
        }
        List<String> out = new ArrayList<>(values.length);
        for (Object value : values) {
            if (value != null) {
                out.add(String.valueOf(value));
            }
        }
        return out.toArray(new String[0]);
    }

    /**
     * The {@code decision} filter is an allow-list, not a passthrough.
     *
     * <p>The column is an enum, so an unrecognised value would otherwise become a literal the
     * database refuses. Refusing it here — by yielding {@code null}, which the statement treats as
     * "no filter" — means a typo silently shows everything rather than erroring or widening.
     */
    private static String normalizeDecision(String decision) {
        if (decision == null || decision.isBlank()) {
            return null;
        }
        String normalized = decision.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "ALLOW", "DENY", "REQUIRE_APPROVAL" -> normalized;
            default -> null;
        };
    }

    /** Kept so a caller composing a map has the empty-map constant in one place. */
    static Map<String, Object> emptyCounts() {
        return new LinkedHashMap<>();
    }
}