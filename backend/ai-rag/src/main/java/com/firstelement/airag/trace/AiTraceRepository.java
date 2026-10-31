package com.firstelement.airag.trace;

import com.firstelement.airag.policy.AiPolicySnapshot;
import com.firstelement.security.query.ScopedWhere;
import com.firstelement.security.query.TenantScope;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The write surface for the AI trace: {@code ai_runs}, {@code ai_run_steps}, {@code ai_run_retrievals},
 * {@code ai_run_tool_calls}, {@code ai_provenance_records}, {@code ai_redaction_records},
 * {@code ai_tool_executions}, {@code ai_approvals}, {@code ai_approval_decisions},
 * {@code ai_budget_consumptions} and the legacy {@code ai_usage} comparison row.
 *
 * <h2>Every column is named, and none of them is reasoning</h2>
 *
 * <p>ADR 0007 Part 2 rule 4 and FR-5.4. {@code ai_run_steps."detail"} is a <b>summary payload</b>: which
 * stage, which ids, which counts, which reason code. No prompt text, no model reasoning, no scratchpad. The
 * migration's post-condition guard fails if a chain-of-thought-shaped column ever appears on these tables,
 * and this repository is the other half of that promise: it does not put one there.
 *
 * <h2>The {@code detail} payload is built by a type, never assembled from a map</h2>
 *
 * <p>Every step detail is an {@link StepDetail} record whose fields are declared. That is not ceremony: a
 * {@code Map<String, Object>} detail column is where a provider's echo of a prompt ends up, because
 * "just put the whole result in" is one line away. A record with declared fields cannot carry a text blob.
 *
 * <h2>Why the child rows carry their own scope</h2>
 *
 * <p>{@code ai_run_steps}, {@code ai_run_retrievals} and {@code ai_run_tool_calls} gained
 * {@code organizationId}/{@code projectId} in {@code V20261005110000} as <b>nullable</b> columns (ADR 0009
 * §2, expand step). This repository writes both on every insert, so a row it creates is immediately
 * filterable — but it never <em>reads</em> those columns without joining the parent, which is the other
 * half of the same ruling and is enforced by {@link #stepsForRun}.
 */
@Repository
public class AiTraceRepository {

    private static final String INSERT_RUN = """
        insert into ai_runs ("id", "organizationId", "projectId", "conversationId", "agentId",
            "agentVersionId", "triggerMessageId", "status", "provider", "model", "startedAt")
        values (:id, :__fe_tenant_organization_id, :__fe_tenant_project_id, :conversationId, :agentId, :agentVersionId,
            :triggerMessageId, cast('RUNNING' as "AIRunStatus"), cast(:provider as "AIProviderKind"),
            :model, :startedAt)
        """;

    /**
     * The run's terminal state.
     *
     * <p>{@code errorMessage} carries a <b>code</b> such as {@code PROVIDER_UNAVAILABLE} or a scrubbed
     * provider message — never prompt text. It is the column an operator reads when the AI went quiet, and
     * a column that can hold a prompt is a column that will eventually hold one.
     */
    private static final String FINISH_RUN = """
        update ai_runs
           set "status" = cast(:status as "AIRunStatus"),
               "decision" = cast(:decision as "AIRunDecision"),
               "confidence" = :confidence,
               "decisionReason" = :decisionReason,
               "model" = :model,
               "errorMessage" = :errorMessage,
               "latencyMs" = :latencyMs,
               "finishedAt" = :finishedAt,
               "responseMessageId" = :responseMessageId
         where "id" = :id
           and "organizationId" = :__fe_tenant_organization_id
           and "projectId" = :__fe_tenant_project_id
        """;

    private static final String INSERT_STEP = """
        insert into ai_run_steps ("id", "runId", "sequence", "kind", "status", "latencyMs", "detail",
            "createdAt", "organizationId", "projectId", "updatedAt")
        values (:id, :runId, :sequence, cast(:kind as "AIRunStepKind"), :status, :latencyMs,
            cast(:detail as jsonb), :createdAt, :__fe_tenant_organization_id, :__fe_tenant_project_id, :updatedAt)
        """;

    private static final String INSERT_RETRIEVAL = """
        insert into ai_run_retrievals ("id", "runId", "knowledgeBaseId", "documentId", "chunkId",
            "documentTitle", "score", "wasUsed", "createdAt", "organizationId", "projectId", "updatedAt")
        values (:id, :runId, :knowledgeBaseId, :documentId, :chunkId, :documentTitle, :score, :wasUsed,
            :createdAt, :__fe_tenant_organization_id, :__fe_tenant_project_id, :updatedAt)
        """;

    private static final String INSERT_TOOL_CALL = """
        insert into ai_run_tool_calls ("id", "runId", "sequence", "toolName", "arguments", "result",
            "authorized", "isError", "errorMessage", "latencyMs", "createdAt", "organizationId",
            "projectId", "updatedAt")
        values (:id, :runId, :sequence, :toolName, cast(:arguments as jsonb), cast(:result as jsonb),
            :authorized, :isError, :errorMessage, :latencyMs, :createdAt, :__fe_tenant_organization_id, :__fe_tenant_project_id,
            :updatedAt)
        """;

    /**
     * The one provenance row per run.
     *
     * <p>{@code on conflict ("organizationId", "aiRunId") do nothing}: {@code ai_provenance_records_run_key}
     * is UNIQUE, and a redelivered job must not produce a second account of what the customer saw. The
     * conflict target is named rather than inferred, so a future unique index cannot change what this
     * statement silently absorbs.
     */
    private static final String INSERT_PROVENANCE = """
        insert into ai_provenance_records ("id", "organizationId", "projectId", "aiRunId", "agentId",
            "agentVersionId", "promptVersion", "modelRegistryId", "provider", "model", "policyId",
            "policyVersionId", "retrievalIds", "toolCallCount", "toolDeniedCount", "approvalId",
            "inputTokens", "outputTokens", "costAmount", "costCurrency", "redactionCount", "refused",
            "overriddenByUserId", "disposition", "decidedAt", "createdAt")
        values (:id, :__fe_tenant_organization_id, :__fe_tenant_project_id, :aiRunId, :agentId, :agentVersionId, :promptVersion,
            :modelRegistryId, cast(:provider as "AIProviderKind"), :model, :policyId, :policyVersionId,
            :retrievalIds, :toolCallCount, :toolDeniedCount, :approvalId, :inputTokens, :outputTokens,
            :costAmount, :costCurrency, :redactionCount, :refused, :overriddenByUserId, :disposition,
            :decidedAt, :createdAt)
        on conflict ("organizationId", "aiRunId") do nothing
        """;

    /**
     * Redaction records: policy, rule, field path, counts. Never the values.
     *
     * <p>{@code valuesRedacted >= 1} is a CHECK, so a "redaction" that removed nothing cannot be written —
     * which is the point. The row that makes a compliance report look better than reality is exactly the
     * row that claims a scrub happened and did not.
     */
    private static final String INSERT_REDACTION = """
        insert into ai_redaction_records ("id", "organizationId", "projectId", "aiRunId", "policyId",
            "policyVersionId", "redactionPolicy", "ruleId", "fieldPath", "valuesRedacted",
            "charactersRedacted", "redactedAt", "createdAt")
        values (:id, :__fe_tenant_organization_id, :__fe_tenant_project_id, :aiRunId, :policyId, :policyVersionId, :redactionPolicy,
            :ruleId, :fieldPath, :valuesRedacted, :charactersRedacted, :redactedAt, :createdAt)
        """;

    private static final String INSERT_TOOL_EXECUTION = """
        insert into ai_tool_executions ("id", "organizationId", "projectId", "aiRunId", "toolName",
            "actionClass", "consequentialAction", "decision", "denyReason", "approvalId",
            "argumentsRedacted", "resultRedacted", "principalType", "actorUserId", "agentVersionId",
            "latencyMs", "requestedAt", "executedAt", "createdAt")
        values (:id, :__fe_tenant_organization_id, :__fe_tenant_project_id, :aiRunId, :toolName, :actionClass,
            :consequentialAction, cast(:decision as "AiPolicyDecision"), :denyReason, :approvalId,
            cast(:argumentsRedacted as jsonb), cast(:resultRedacted as jsonb), :principalType, :actorUserId,
            :agentVersionId, :latencyMs, :requestedAt, :executedAt, :createdAt)
        """;

    private static final String INSERT_APPROVAL = """
        insert into ai_approvals ("id", "organizationId", "projectId", "aiRunId", "action", "actionClass",
            "status", "requestedByUserId", "requestedByPrincipal", "reason", "requestedAt", "expiresAt",
            "updatedAt")
        values (:id, :__fe_tenant_organization_id, :__fe_tenant_project_id, :aiRunId, :action, :actionClass,
            cast('PENDING' as "AiApprovalStatus"), :requestedByUserId, :requestedByPrincipal, :reason,
            :requestedAt, :expiresAt, :updatedAt)
        """;

    /**
     * {@code fromStatus} carries the SAME {@code cast(... as "AiApprovalStatus")} as {@code toStatus}.
     *
     * <p>It did not, and the consequence was that {@code approve}, {@code reject}, {@code revoke} and
     * {@code expire} <b>all failed at runtime</b> with {@code column "fromStatus" is of type
     * "AiApprovalStatus" but expression is of type character varying}. The initial {@code PENDING} request
     * passes {@code null} for {@code fromStatus}, so it did not cast and did not fail — which is why the
     * request path was exercised and the decision paths were not. Found by
     * {@code ApprovalLifecycleIntegrationTest}; every enum-typed column in this repository is now bound
     * through an explicit cast, and a null is a valid input to one.
     */
    private static final String INSERT_APPROVAL_DECISION = """
        insert into ai_approval_decisions ("id", "organizationId", "projectId", "approvalId", "fromStatus",
            "toStatus", "actorUserId", "actorPrincipal", "note", "decidedAt", "createdAt")
        values (:id, :__fe_tenant_organization_id, :__fe_tenant_project_id, :approvalId,
            cast(:fromStatus as "AiApprovalStatus"), cast(:toStatus as "AiApprovalStatus"), :actorUserId,
            :actorPrincipal, :note, :decidedAt, :createdAt)
        """;

    private static final String INSERT_BUDGET_CONSUMPTION = """
        insert into ai_budget_consumptions ("id", "organizationId", "projectId", "budgetId", "aiRunId",
            "periodStart", "periodEnd", "currency", "amount", "inputTokens", "outputTokens", "recordedAt",
            "createdAt")
        values (:id, :__fe_tenant_organization_id, :__fe_tenant_project_id, :budgetId, :aiRunId, :periodStart, :periodEnd, :currency,
            :amount, :inputTokens, :outputTokens, :recordedAt, :createdAt)
        on conflict ("organizationId", "budgetId", "periodStart", "aiRunId") do nothing
        """;

    /**
     * The legacy {@code ai_usage} comparison row.
     *
     * <p>ADR 0009 §3: this table is a <b>legacy comparison source only</b>. {@code "estimatedCost"} is
     * written from the same rate-card computation the ledger uses, so the comparison shows a variance only
     * when the legacy runtime's own estimate differs — which is the case the comparison exists to surface.
     * Nothing reads this row as finance truth, and {@code usage_events} is written in the same transaction
     * by {@code AiUsageAccounting}.
     */
    private static final String INSERT_LEGACY_USAGE = """
        insert into ai_usage ("id", "organizationId", "projectId", "runId", "provider", "model",
            "inputTokens", "outputTokens", "latencyMs", "estimatedCost", "createdAt")
        values (:id, :__fe_tenant_organization_id, :__fe_tenant_project_id, :runId, cast(:provider as "AIProviderKind"), :model,
            :inputTokens, :outputTokens, :latencyMs, :estimatedCost, :createdAt)
        """;

    /**
     * The steps of a run, read through the parent {@code ai_runs} row.
     *
     * <p>This is ADR 0009 §2 rule 4 stated as code. {@code ai_run_steps."organizationId"} is NULLABLE until
     * the B1 backfill completes, so a bare {@code "organizationId" = ?} predicate on this table silently
     * returns <b>no rows</b> for every row the backfill has not reached — which reads as "no access" rather
     * than "not yet migrated", and is the dangerous failure mode because it looks like a security control
     * working. The join is not an optimisation; it is the only correct filter until the residual NULL count
     * is proven zero.
     */
    private static final String STEPS_JOINING_PARENT = """
        select s."id", s."sequence", s."kind"::text as "kind", s."status", s."latencyMs", s."detail",
               s."organizationId" as "childOrganizationId", s."projectId" as "childProjectId",
               r."organizationId" as "parentOrganizationId", r."projectId" as "parentProjectId"
          from ai_run_steps s
          join ai_runs r on r."id" = s."runId"
         where s."runId" = :runId
           and r."organizationId" = :__fe_tenant_organization_id
           and r."projectId" = :__fe_tenant_project_id
         order by s."sequence"
         limit :limit
        """;

    /** The retrievals of a run, joined to the parent for the same reason. */
    private static final String RETRIEVALS_JOINING_PARENT = """
        select v."id", v."knowledgeBaseId", v."documentId", v."chunkId", v."documentTitle", v."score",
               v."wasUsed", v."organizationId" as "childOrganizationId"
          from ai_run_retrievals v
          join ai_runs r on r."id" = v."runId"
         where v."runId" = :runId
           and r."organizationId" = :__fe_tenant_organization_id
           and r."projectId" = :__fe_tenant_project_id
         order by v."score" desc
         limit :limit
        """;

    /** The tool calls of a run, joined to the parent for the same reason. */
    private static final String TOOL_CALLS_JOINING_PARENT = """
        select t."id", t."sequence", t."toolName", t."authorized", t."isError", t."errorMessage",
               t."latencyMs", t."organizationId" as "childOrganizationId"
          from ai_run_tool_calls t
          join ai_runs r on r."id" = t."runId"
         where t."runId" = :runId
           and r."organizationId" = :__fe_tenant_organization_id
           and r."projectId" = :__fe_tenant_project_id
         order by t."sequence"
         limit :limit
        """;

    private final NamedParameterJdbcTemplate jdbc;
    private final com.firstelement.crm.persistence.JsonCodec json;

    public AiTraceRepository(NamedParameterJdbcTemplate jdbc,
                             com.firstelement.crm.persistence.JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * The tenant filter, written onto a caller-built parameter source.
     *
     * <p>{@code ScopedWhere.bindNamed} takes a {@code Map}, and several of these statements carry more than
     * ten bindings, which {@code Map.of} cannot express. This is the same rule applied the same way: the
     * tenant values are written <b>last</b>, so a caller cannot shadow the filter by binding one of the
     * reserved names, and a caller that tries is refused rather than silently overwritten. The alternative
     * — copying into a map and losing the reserved-name check — is how a filter gets swapped out of a
     * predicate without anybody editing the predicate.
     */
    private static MapSqlParameterSource scoped(TenantScope scope, MapSqlParameterSource source) {
        MapSqlParameterSource merged = new MapSqlParameterSource();
        if (source.getValues() != null) {
            source.getValues().forEach((name, value) -> {
                if (ScopedWhere.ORGANIZATION_PARAM.equals(name) || ScopedWhere.PROJECT_PARAM.equals(name)) {
                    throw new IllegalArgumentException("'" + name
                        + "' is reserved for the tenant filter and cannot be supplied by a caller");
                }
                merged.addValue(name, value);
            });
        }
        merged.addValue(ScopedWhere.ORGANIZATION_PARAM, scope.organizationId());
        if (scope.isProjectScoped()) {
            merged.addValue(ScopedWhere.PROJECT_PARAM, scope.projectId());
        }
        return merged;
    }

    /** One step, with the scope columns of both the child and its parent. */
    public record StepRow(UUID id,
                          int sequence,
                          String kind,
                          String status,
                          Integer latencyMs,
                          String detail,
                          UUID childOrganizationId,
                          UUID childProjectId,
                          UUID parentOrganizationId,
                          UUID parentProjectId) {
    }

    /** One retrieval, with the child's own scope column so a migration gap is visible. */
    public record RetrievalRow(UUID id,
                               UUID knowledgeBaseId,
                               UUID documentId,
                               UUID chunkId,
                               String documentTitle,
                               double score,
                               boolean wasUsed,
                               UUID childOrganizationId) {
    }

    /** One tool call. */
    public record ToolCallRow(UUID id,
                              int sequence,
                              String toolName,
                              boolean authorized,
                              boolean isError,
                              String errorMessage,
                              Integer latencyMs,
                              UUID childOrganizationId) {
    }

    // ------------------------------------------------------------------

    /**
     * Starts a run.
     *
     * <p>{@code on conflict} is absent here on purpose: two runs for one trigger message is a real defect
     * and the caller dedupes on the conversation plus the trigger message id before reaching here.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID startRun(TenantScope scope,
                         UUID conversationId,
                         UUID agentId,
                         UUID agentVersionId,
                         UUID triggerMessageId,
                         com.firstelement.airag.provider.AiProviderKind provider,
                         String model,
                         LocalDateTime startedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update(INSERT_RUN, scoped(scope, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("conversationId", conversationId)
            .addValue("agentId", agentId)
            .addValue("agentVersionId", agentVersionId)
            .addValue("triggerMessageId", triggerMessageId)
            .addValue("provider", provider.wireValue())
            .addValue("model", model)
            .addValue("startedAt", startedAt)));
        return id;
    }

    /**
     * Finishes a run.
     *
     * <p>The predicate is scoped, so a run in another tenant is simply not updated rather than being
     * updated by id. {@code responseMessageId} is written by whoever persisted the message; Phase 6's
     * {@code AiSendService} owns the send, and this module never sends.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishRun(TenantScope scope,
                          UUID runId,
                          String status,
                          String decision,
                          Integer confidence,
                          String decisionReason,
                          String model,
                          String errorMessage,
                          Integer latencyMs,
                          LocalDateTime finishedAt,
                          UUID responseMessageId) {
        jdbc.update(FINISH_RUN, scoped(scope, new MapSqlParameterSource()
            .addValue("status", status)
            .addValue("decision", decision)
            .addValue("confidence", confidence)
            .addValue("decisionReason", decisionReason)
            .addValue("model", model)
            .addValue("errorMessage", errorMessage)
            .addValue("latencyMs", latencyMs)
            .addValue("finishedAt", finishedAt)
            .addValue("responseMessageId", responseMessageId)
            .addValue("id", runId)));
    }

    /**
     * Appends one step.
     *
     * <p>Writes {@code organizationId} and {@code projectId} from the <b>run's</b> scope, which is the
     * value the B1 backfill would derive. Writing them here means a row this module creates is filterable
     * by index from the moment it exists, so the backfill only has historical rows to deal with.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID appendStep(TenantScope scope,
                           UUID runId,
                           int sequence,
                           String kind,
                           String status,
                           Integer latencyMs,
                           StepDetail detail,
                           LocalDateTime createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update(INSERT_STEP, scoped(scope, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("runId", runId)
            .addValue("sequence", sequence)
            .addValue("kind", kind)
            .addValue("status", status)
            .addValue("latencyMs", latencyMs)
            .addValue("detail", json.write(detail))
            .addValue("createdAt", createdAt)
            .addValue("updatedAt", createdAt)));
        return id;
    }

    /** Appends one retrieval, for the chunks that became prompt context. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID appendRetrieval(TenantScope scope,
                                UUID runId,
                                UUID knowledgeBaseId,
                                UUID documentId,
                                UUID chunkId,
                                String documentTitle,
                                double score,
                                boolean wasUsed,
                                LocalDateTime createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update(INSERT_RETRIEVAL, scoped(scope, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("runId", runId)
            .addValue("knowledgeBaseId", knowledgeBaseId)
            .addValue("documentId", documentId)
            .addValue("chunkId", chunkId)
            .addValue("documentTitle", documentTitle)
            .addValue("score", score)
            .addValue("wasUsed", wasUsed)
            .addValue("createdAt", createdAt)
            .addValue("updatedAt", createdAt)));
        return id;
    }

    /** Appends one tool call, with the arguments and result already redacted. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID appendToolCall(TenantScope scope,
                               UUID runId,
                               int sequence,
                               String toolName,
                               String argumentsJson,
                               String resultJson,
                               boolean authorized,
                               boolean isError,
                               String errorMessage,
                               Integer latencyMs,
                               LocalDateTime createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update(INSERT_TOOL_CALL, scoped(scope, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("runId", runId)
            .addValue("sequence", sequence)
            .addValue("toolName", toolName)
            .addValue("arguments", argumentsJson == null ? "{}" : argumentsJson)
            .addValue("result", resultJson == null ? "{}" : resultJson)
            .addValue("authorized", authorized)
            .addValue("isError", isError)
            .addValue("errorMessage", errorMessage)
            .addValue("latencyMs", latencyMs)
            .addValue("createdAt", createdAt)
            .addValue("updatedAt", createdAt)));
        return id;
    }

    /**
     * Writes the one provenance row for a run.
     *
     * <p>{@code disposition} is a closed list in the schema and {@code refused} is a biconditional with it,
     * so a caller cannot record "refused" on an ANSWERED row. {@code toolDeniedCount <= toolCallCount} is
     * checked too: an inversion means two code paths wrote the counter and neither is trustworthy.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID writeProvenance(TenantScope scope,
                                UUID runId,
                                UUID agentId,
                                UUID agentVersionId,
                                String promptVersion,
                                UUID modelRegistryId,
                                com.firstelement.airag.provider.AiProviderKind provider,
                                String model,
                                UUID policyId,
                                UUID policyVersionId,
                                List<UUID> retrievalIds,
                                int toolCallCount,
                                int toolDeniedCount,
                                UUID approvalId,
                                Integer inputTokens,
                                Integer outputTokens,
                                java.math.BigDecimal costAmount,
                                String costCurrency,
                                int redactionCount,
                                boolean refused,
                                UUID overriddenByUserId,
                                String disposition,
                                LocalDateTime decidedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update(INSERT_PROVENANCE, scoped(scope, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("aiRunId", runId)
            .addValue("agentId", agentId)
            .addValue("agentVersionId", agentVersionId)
            .addValue("promptVersion", promptVersion)
            .addValue("modelRegistryId", modelRegistryId)
            .addValue("provider", provider == null ? null : provider.wireValue())
            .addValue("model", model)
            .addValue("policyId", policyId)
            .addValue("policyVersionId", policyVersionId)
            .addValue("retrievalIds", retrievalIds == null ? new String[0]
                : retrievalIds.stream().map(UUID::toString).toArray(String[]::new))
            .addValue("toolCallCount", toolCallCount)
            .addValue("toolDeniedCount", toolDeniedCount)
            .addValue("approvalId", approvalId)
            .addValue("inputTokens", inputTokens)
            .addValue("outputTokens", outputTokens)
            .addValue("costAmount", costAmount)
            .addValue("costCurrency", costAmount == null ? null : costCurrency)
            .addValue("redactionCount", redactionCount)
            .addValue("refused", refused)
            .addValue("overriddenByUserId", overriddenByUserId)
            .addValue("disposition", disposition)
            .addValue("decidedAt", decidedAt)
            .addValue("createdAt", decidedAt)));
        return id;
    }

    /** One redaction record: counts, never values. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID writeRedaction(TenantScope scope,
                               UUID runId,
                               UUID policyId,
                               UUID policyVersionId,
                               String redactionPolicy,
                               String ruleId,
                               String fieldPath,
                               int valuesRedacted,
                               Integer charactersRedacted,
                               LocalDateTime redactedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update(INSERT_REDACTION, scoped(scope, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("aiRunId", runId)
            .addValue("policyId", policyId)
            .addValue("policyVersionId", policyVersionId)
            .addValue("redactionPolicy", redactionPolicy)
            .addValue("ruleId", ruleId)
            .addValue("fieldPath", fieldPath)
            .addValue("valuesRedacted", valuesRedacted)
            .addValue("charactersRedacted", charactersRedacted)
            .addValue("redactedAt", redactedAt)
            .addValue("createdAt", redactedAt)));
        return id;
    }

    /**
     * Writes the evidence row for a tool call, allowed or refused.
     *
     * <p>Every invocation gets a row. That is what makes "a disabled tool is denied <em>and recorded</em>"
     * (FR-5.6, acceptance gate 3) true: a refusal that leaves no trace is indistinguishable from a tool
     * that was never offered, and the second is exactly what an attacker wants.
     *
     * <p>{@code argumentsRedacted} and {@code resultRedacted} are supplied by the caller already redacted;
     * the migration's guard forbids a raw twin column, so there is nowhere else this could have come from.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID writeToolExecution(TenantScope scope,
                                   UUID runId,
                                   String toolName,
                                   String actionClass,
                                   boolean consequentialAction,
                                   String decision,
                                   String denyReason,
                                   UUID approvalId,
                                   String argumentsRedactedJson,
                                   String resultRedactedJson,
                                   String principalType,
                                   UUID actorUserId,
                                   UUID agentVersionId,
                                   Integer latencyMs,
                                   LocalDateTime requestedAt,
                                   LocalDateTime executedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update(INSERT_TOOL_EXECUTION, scoped(scope, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("aiRunId", runId)
            .addValue("toolName", toolName == null ? "unknown" : toolName)
            .addValue("actionClass", actionClass)
            .addValue("consequentialAction", consequentialAction)
            .addValue("decision", decision)
            .addValue("denyReason", decision.equals("DENY") ? denyReason : null)
            .addValue("approvalId", approvalId)
            .addValue("argumentsRedacted", argumentsRedactedJson == null ? "{}" : argumentsRedactedJson)
            .addValue("resultRedacted", resultRedactedJson == null ? "{}" : resultRedactedJson)
            .addValue("principalType", principalType)
            .addValue("actorUserId", actorUserId)
            .addValue("agentVersionId", agentVersionId)
            .addValue("latencyMs", latencyMs)
            .addValue("requestedAt", requestedAt)
            .addValue("executedAt", "ALLOW".equals(decision) ? executedAt : null)
            .addValue("createdAt", requestedAt)));
        return id;
    }

    /**
     * Requests a human approval.
     *
     * <p>{@code actionClass} is CONSTANT-checked to {@code 'CONSEQUENTIAL'} by the schema, so the caller
     * cannot request approval for an answerable or forbidden action — that is a policy bug, not a workflow.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID requestApproval(TenantScope scope,
                                UUID runId,
                                String action,
                                UUID requestedByUserId,
                                String requestedByPrincipal,
                                String reason,
                                LocalDateTime requestedAt,
                                LocalDateTime expiresAt) {
        UUID id = UUID.randomUUID();
        jdbc.update(INSERT_APPROVAL, scoped(scope, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("aiRunId", runId)
            .addValue("action", action)
            .addValue("actionClass", "CONSEQUENTIAL")
            .addValue("requestedByUserId", requestedByUserId)
            .addValue("requestedByPrincipal", requestedByPrincipal)
            .addValue("reason", reason)
            .addValue("requestedAt", requestedAt)
            .addValue("expiresAt", expiresAt)
            .addValue("updatedAt", requestedAt)));
        return id;
    }

    /**
     * Moves a lapsed PENDING approval to EXPIRED, and appends the transition.
     *
     * <p>{@code REQUIRES_NEW} because the caller — {@code AiApprovalService.approve} — throws immediately
     * afterwards, and a self-invoked write on the caller's connection would be rolled back by that
     * exception. Without this the row stays PENDING forever while every {@code approve} keeps reporting
     * "expired", which is a state machine that cannot advance.
     *
     * <p>The decision pair is cleared, and that is <b>required, not incidental</b>:
     * {@code ai_approvals_approval_check} — as corrected by {@code V20261006100000} — mandates
     * {@code decidedAt} on {@code APPROVED}, {@code REJECTED} and {@code REVOKED}, and therefore requires it
     * <b>absent</b> on {@code PENDING} and {@code EXPIRED}. An expiry is not a decision: nobody refused this
     * request, it simply ran out, and writing a decider here would put a name against a request no human ever
     * looked at. The actor is {@code SYSTEM} on the append-only decisions row, and {@code SYSTEM} is not a
     * user id, so it cannot be written into the user-keyed half of the pair anyway.
     *
     * <p>So this method clears the pair on the same grounds {@code reject} and {@code revoke} no longer do:
     * the difference is the <em>status</em>, not a workaround. That distinction is the reason the
     * correction was worth making — under the previous constraint, {@code EXPIRED} and {@code REJECTED} both
     * had to hide their decider, and now only the one that genuinely has none does.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markApprovalExpired(TenantScope scope, UUID approvalId, LocalDateTime now) {
        jdbc.update("""
            update ai_approvals
               set "status" = cast('EXPIRED' as "AiApprovalStatus"),
                   "decidedByUserId" = null,
                   "decidedAt" = null,
                   "decisionNote" = 'expired without a decision',
                   "updatedAt" = :now
             where "id" = :id
               and "organizationId" = :__fe_tenant_organization_id
               and "projectId" = :__fe_tenant_project_id
               and "status" = cast('PENDING' as "AiApprovalStatus")
            """, scoped(scope, new MapSqlParameterSource().addValue("id", approvalId).addValue("now", now)));
        recordApprovalDecision(scope, approvalId, "PENDING", "EXPIRED", null, "SYSTEM",
            "expired without a decision", now);
    }

    /** Appends one approval transition. The mutable row is state; this is the evidence. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID recordApprovalDecision(TenantScope scope,
                                       UUID approvalId,
                                       String fromStatus,
                                       String toStatus,
                                       UUID actorUserId,
                                       String actorPrincipal,
                                       String note,
                                       LocalDateTime decidedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update(INSERT_APPROVAL_DECISION, scoped(scope, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("approvalId", approvalId)
            .addValue("fromStatus", fromStatus)
            .addValue("toStatus", toStatus)
            .addValue("actorUserId", actorUserId)
            .addValue("actorPrincipal", actorPrincipal)
            .addValue("note", note)
            .addValue("decidedAt", decidedAt)
            .addValue("createdAt", decidedAt)));
        return id;
    }

    /**
     * Books a budget consumption.
     *
     * <p>Append-only with {@code on conflict do nothing} on
     * {@code ai_budget_consumptions_budget_run_period_key} — a retried accounting write must not
     * double-charge. The {@code usage_events} rows are written by {@code AiUsageAccounting} and suppressed
     * the same way, in the same transaction, so a redelivered job cannot produce two charges under two
     * different tables.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID recordBudgetConsumption(TenantScope scope,
                                        UUID budgetId,
                                        UUID runId,
                                        LocalDate periodStart,
                                        LocalDate periodEnd,
                                        String currency,
                                        java.math.BigDecimal amount,
                                        Integer inputTokens,
                                        Integer outputTokens,
                                        LocalDateTime recordedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update(INSERT_BUDGET_CONSUMPTION, scoped(scope, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("budgetId", budgetId)
            .addValue("aiRunId", runId)
            .addValue("periodStart", periodStart)
            .addValue("periodEnd", periodEnd)
            .addValue("currency", currency)
            .addValue("amount", amount)
            .addValue("inputTokens", inputTokens)
            .addValue("outputTokens", outputTokens)
            .addValue("recordedAt", recordedAt)
            .addValue("createdAt", recordedAt)));
        return id;
    }

    /** The legacy comparison row. Never read as finance truth (ADR 0009 §3). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID recordLegacyUsage(TenantScope scope,
                                  UUID runId,
                                  com.firstelement.airag.provider.AiProviderKind provider,
                                  String model,
                                  int inputTokens,
                                  int outputTokens,
                                  int latencyMs,
                                  double estimatedCost,
                                  LocalDateTime createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update(INSERT_LEGACY_USAGE, scoped(scope, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("runId", runId)
            .addValue("provider", provider.wireValue())
            .addValue("model", model)
            .addValue("inputTokens", inputTokens)
            .addValue("outputTokens", outputTokens)
            .addValue("latencyMs", latencyMs)
            .addValue("estimatedCost", estimatedCost)
            .addValue("createdAt", createdAt)));
        return id;
    }

    /** The steps of a run, filtered through the parent run's scope. */
    public List<StepRow> stepsForRun(TenantScope scope, UUID runId, int limit) {
        return jdbc.query(STEPS_JOINING_PARENT, ScopedWhere.of(scope)
                .bindNamed(Map.of("runId", runId, "limit", Math.max(1, limit))),
            (rs, rowNum) -> new StepRow(
                rs.getObject("id", UUID.class),
                rs.getInt("sequence"),
                rs.getString("kind"),
                rs.getString("status"),
                rs.getObject("latencyMs", Integer.class),
                rs.getString("detail"),
                rs.getObject("childOrganizationId", UUID.class),
                rs.getObject("childProjectId", UUID.class),
                rs.getObject("parentOrganizationId", UUID.class),
                rs.getObject("parentProjectId", UUID.class)));
    }

    /** The retrievals of a run, filtered through the parent run's scope. */
    public List<RetrievalRow> retrievalsForRun(TenantScope scope, UUID runId, int limit) {
        return jdbc.query(RETRIEVALS_JOINING_PARENT, ScopedWhere.of(scope)
                .bindNamed(Map.of("runId", runId, "limit", Math.max(1, limit))),
            (rs, rowNum) -> new RetrievalRow(
                rs.getObject("id", UUID.class),
                rs.getObject("knowledgeBaseId", UUID.class),
                rs.getObject("documentId", UUID.class),
                rs.getObject("chunkId", UUID.class),
                rs.getString("documentTitle"),
                rs.getDouble("score"),
                rs.getBoolean("wasUsed"),
                rs.getObject("childOrganizationId", UUID.class)));
    }

    /** The tool calls of a run, filtered through the parent run's scope. */
    public List<ToolCallRow> toolCallsForRun(TenantScope scope, UUID runId, int limit) {
        return jdbc.query(TOOL_CALLS_JOINING_PARENT, ScopedWhere.of(scope)
                .bindNamed(Map.of("runId", runId, "limit", Math.max(1, limit))),
            (rs, rowNum) -> new ToolCallRow(
                rs.getObject("id", UUID.class),
                rs.getInt("sequence"),
                rs.getString("toolName"),
                rs.getBoolean("authorized"),
                rs.getBoolean("isError"),
                rs.getString("errorMessage"),
                rs.getObject("latencyMs", Integer.class),
                rs.getObject("childOrganizationId", UUID.class)));
    }

    /**
     * The declared shape of a step's {@code detail} payload.
     *
     * <p>A record, so the jsonb is built from declared fields and cannot carry a text blob. Every field is
     * an id, a count, a score or a reason code. There is deliberately no {@code String} field that could
     * hold a model answer, a prompt, or a provider message.
     *
     * @param reason a <b>code</b> from a closed vocabulary, never free text
     */
    public record StepDetail(String stage,
                             String reason,
                             int consideredCount,
                             int returnedCount,
                             double minimumScore,
                             List<String> providersAttempted,
                             String promptVersion,
                             int inputTokens,
                             int outputTokens,
                             int redactionCount,
                             long elapsedMillis) {

        public StepDetail {
            providersAttempted = providersAttempted == null ? List.of() : List.copyOf(providersAttempted);
        }

        /** A step with only a stage and a reason code, which is what most stages are. */
        public static StepDetail of(String stage, String reason) {
            return new StepDetail(stage, reason, 0, 0, 0d, List.of(), null, 0, 0, 0, 0L);
        }
    }
}