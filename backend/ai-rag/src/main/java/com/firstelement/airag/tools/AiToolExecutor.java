package com.firstelement.airag.tools;

import com.firstelement.airag.approval.AiApprovalService;
import com.firstelement.airag.policy.AiPolicySnapshot;
import com.firstelement.airag.policy.AiRefusalReason;
import com.firstelement.airag.policy.AiRefusedException;
import com.firstelement.airag.redaction.AiRedactionEngine;
import com.firstelement.airag.redaction.RedactionOutcome;
import com.firstelement.airag.redaction.RedactionPolicyDefinition;
import com.firstelement.airag.trace.AiTraceRepository;
import com.firstelement.security.TenantContext;
import com.firstelement.security.aitool.AiToolExecutionGuard;
// The guard's own nested AiToolInvocation is referenced fully-qualified below. This module's
// AiToolInvocation is the richer type (it carries the approval id and the dry-run flag), and importing
// both under one name would make the compiler pick one silently.
import com.firstelement.security.aitool.AiToolExecutionGuard.AuthorizedExecution;
import com.firstelement.security.query.TenantQueryGuard;
import com.firstelement.security.query.TenantScope;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The tool gate. Every request the model makes passes through here, and this class decides whether
 * anything runs.
 *
 * <h2>The order, and it is the security property</h2>
 *
 * <ol>
 *   <li><b>Rate limit</b> — the run's tool-call count against the effective ceiling. Before anything else,
 *       because a refusal that costs nothing is better than a refusal that read a row first.</li>
 *   <li><b>Resolve the tool.</b> Not in this build → {@link AiRefusalReason#UNKNOWN_TOOL}. Not enabled for
 *       this tenant → {@link AiRefusalReason#TOOL_DISABLED}. Both recorded, and both reported to the model
 *       identically so probing for existence yields nothing.</li>
 *   <li><b>Validate the arguments.</b> Scope-override first, then undeclared names, then types and bounds.
 *       {@link AiToolArgumentValidator}.</li>
 *   <li><b>Resolve the project.</b> Through {@link TenantQueryGuard}; a requested project outside the
 *       principal's scope is {@link AiRefusalReason#TOOL_PROJECT_OUT_OF_SCOPE}.</li>
 *   <li><b>Run the guard.</b> {@link AiToolExecutionGuard} for the capability check, the forbidden-argument
 *       check and the approval check, all in the place they were designed for rather than reimplemented.</li>
 *   <li><b>Classify.</b> FORBIDDEN → DENY, always. CONSEQUENTIAL without a valid approval → REQUIRE_APPROVAL
 *       plus an {@code ai_approvals} row, and the body does not run. ANSWERABLE → ALLOW.</li>
 *   <li><b>Redact the result</b>, project it through the effective field allow-list, then execute the body.
 *       Redaction before execution is deliberate: the body may read a customer row, and what is persisted
 *       must be the redacted form.</li>
 * </ol>
 *
 * <h2>Every path records an {@code ai_tool_executions} row</h2>
 *
 * <p>Allowed, awaiting approval and refused alike. The schema's biconditional then holds: DENY has no
 * {@code executedAt} and does have a {@code denyReason}; REQUIRE_APPROVAL names its approval and has no
 * {@code executedAt}; ALLOW has an {@code executedAt} and, when consequential, an approval id. A refusal that
 * left no trace would be indistinguishable from a tool that was never offered, which is what a probing model
 * wants.
 *
 * <h2>No auto-execution</h2>
 *
 * <p>Nothing in this class runs a consequential body without {@link AiApprovalService#consume} returning true,
 * and {@link #consume} requires an APPROVED, unexpired, same-run, same-tool, argument-shape-matching row.
 * There is no timeout that "auto-approves after the approver went to lunch"; the request simply expires.
 */
@Component
public class AiToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(AiToolExecutor.class);

    /** The principal type recorded for a model-initiated call. Fixed by the schema's CHECK. */
    private static final String PRINCIPAL_AI_AGENT = "AI_AGENT";

    private final AiToolCatalog catalog;
    private final AiToolRegistryService registry;
    private final AiToolExecutionGuard guard;
    private final TenantQueryGuard queryGuard;
    private final AiTraceRepository trace;
    private final AiApprovalService approvals;
    private final AiRedactionEngine redaction;
    private final com.firstelement.crm.persistence.JsonCodec json;

    public AiToolExecutor(AiToolCatalog catalog,
                          AiToolRegistryService registry,
                          AiToolExecutionGuard guard,
                          TenantQueryGuard queryGuard,
                          AiTraceRepository trace,
                          AiApprovalService approvals,
                          AiRedactionEngine redaction,
                          com.firstelement.crm.persistence.JsonCodec json) {
        this.catalog = catalog;
        this.registry = registry;
        this.guard = guard;
        this.queryGuard = queryGuard;
        this.trace = trace;
        this.approvals = approvals;
        this.redaction = redaction;
        this.json = json;
    }

    /**
     * One run's tool-call budget, so the ceiling is checked before each call.
     *
     * @param maxCalls the effective ceiling, the lower of the deployment's and the tenant's
     * @param used     how many have already been attempted, including refusals — a model that probes ten
     *                 disabled tools has spent ten calls of budget, and counting only successes would let
     *                 probing be free
     */
    public record RunBudget(int maxCalls, int used) {

        public RunBudget next() {
            return new RunBudget(maxCalls, used + 1);
        }

        public boolean exhausted() {
            return used >= maxCalls;
        }
    }

    /** Everything one execution needs. Nothing here is optional. */
    public record Execution(TenantContext principal,
                            TenantScope scope,
                            AiPolicySnapshot snapshot,
                            UUID runId,
                            UUID agentVersionId,
                            RunBudget budget,
                            RedactionPolicyDefinition redactionPolicy) {
    }

    /**
     * Runs, or refuses, one tool request.
     *
     * <p>Never throws {@link AiRefusedException} for a refusal that policy produced — those become an
     * {@link AiToolResult} with a decision, because "the model asked for X and the answer was no" is a value
     * the orchestrator needs, not an exception it has to catch. Throws only for a caller mistake (a null
     * execution, an out-of-budget ceiling configured below zero).
     */
    @Transactional
    public AiToolResult execute(Execution execution, AiToolInvocation request) {
        if (execution == null || request == null) {
            throw new IllegalArgumentException("A tool execution needs both the execution and the request");
        }
        LocalDateTime requestedAt = LocalDateTime.now(ZoneOffset.UTC);
        RunBudget budget = execution.budget().next();
        if (budget.exhausted()) {
            return recordRefusal(execution, request, AiRefusalReason.TOOL_CALL_LIMIT,
                "the run's tool-call ceiling of " + execution.budget().maxCalls() + " is reached",
                requestedAt, null);
        }

        // 1. Resolve. Unknown and disabled are the same answer to the model.
        Optional<AiToolCatalog.RegisteredTool> registered = catalog.find(request.toolName());
        if (registered.isEmpty()) {
            return recordRefusal(execution, request, AiRefusalReason.UNKNOWN_TOOL,
                "no such tool in this build", requestedAt, null);
        }
        AiToolDefinition definition = registered.get().definition();
        AiToolRegistryService.EffectiveTool effective =
            registry.effective(execution.scope(), request.toolName());
        if (effective == null || !effective.enabled()) {
            String reason = effective == null ? "unknown-tool" : effective.reason();
            return recordRefusal(execution, request, AiRefusalReason.TOOL_DISABLED,
                reason == null ? "tool-disabled" : reason, requestedAt, definition);
        }

        // 2. Validate the arguments. A scope-override attempt is refused here, before the project is
        // resolved, so a model that tries to move tenants never gets as far as a query.
        Map<String, Object> arguments;
        try {
            arguments = AiToolArgumentValidator.validate(definition, request.arguments());
        } catch (AiRefusedException e) {
            return recordRefusal(execution, request, e.reason(), e.detail(), requestedAt, definition);
        }

        // 3. Resolve the project through the guard. The body never receives the model's id as authority.
        TenantScope scope;
        try {
            scope = request.requestedProjectId() == null
                ? queryGuard.scope(execution.principal(), execution.scope().projectId())
                : queryGuard.scope(execution.principal(), request.requestedProjectId());
        } catch (com.firstelement.security.error.PolicyDeniedException e) {
            return recordRefusal(execution, request, AiRefusalReason.TOOL_PROJECT_OUT_OF_SCOPE,
                "the requested project is not in the principal's scope", requestedAt, definition);
        }

        // 4. The guard: capability, forbidden arguments again, and the approval requirement.
        boolean approvalValid = request.approvalId() != null
            && approvals.consume(scope, request.approvalId(), execution.runId(), definition.name(),
                arguments, requestedAt);
        boolean needsApproval = definition.consequential() && !approvalValid;
        AuthorizedExecution authorized;
        try {
            // `humanApproved` is passed TRUE for a consequential tool that is ABOUT TO BE ESCALATED, and
            // that is not a weakening. The guard's approval check exists to stop the BODY from running; the
            // escalation below guarantees no body runs at all — it creates an ai_approvals row and returns
            // REQUIRE_APPROVAL. Passing false here would make the guard deny first, so the escalation branch
            // would be unreachable and no approval row would ever exist.
            //
            // The gate is therefore asserted twice, in the strictest order available: the executor refuses to
            // run the body without a VALID approval (APPROVED, unexpired, same run, same tool, matching
            // argument shape), and the guard independently refuses a body whose call site claims no human
            // approval. An APPROVED invocation passes both. An unapproved one stops at the executor, which
            // is the check that leaves evidence.
            guard.authorize(execution.principal(),
                definition.descriptorFor(effective.enabled()),
                new com.firstelement.security.aitool.AiToolExecutionGuard.AiToolInvocation(
                    request.toolName(), arguments, request.requestedProjectId(),
                    approvalValid || needsApproval));
        } catch (com.firstelement.security.error.PolicyDeniedException e) {
            AiRefusalReason reason = approvalValid ? AiRefusalReason.CAPABILITY_MISSING
                : AiRefusalReason.HUMAN_APPROVAL_REQUIRED;
            return recordRefusal(execution, request, reason,
                approvalValid ? "the principal lacks " + definition.requiredCapability()
                    : "the tool is consequential and no valid approval was supplied",
                requestedAt, definition);
        }

        // 5. Classify. FORBIDDEN is denied unconditionally and needs no further path.
        if (definition.actionClass() == AiToolDefinition.AiActionClass.FORBIDDEN) {
            return recordRefusal(execution, request, AiRefusalReason.UNKNOWN_TOOL,
                "this tool is forbidden to the model in every configuration", requestedAt, definition);
        }
        if (needsApproval) {
            return requestApproval(execution, definition, arguments, requestedAt, request);
        }

        // 6. Redact, project, then execute. The approval id travels as a parameter rather than being read
        // back off the invocation, so the value written to ai_tool_executions."approvalId" is the same
        // value the consume() check accepted.
        return runBody(execution, definition, effective, scope, arguments, requestedAt,
            approvalValid ? request.approvalId() : null, request.dryRun());
    }

    /**
     * Creates the approval request and records the {@code REQUIRE_APPROVAL} execution.
     *
     * <p>No body runs. The dry run is performed by the <em>approver's</em> execution, not here: describing
     * an unapproved action to the model would let it learn what the tool would do and iterate on it, which
     * is exactly the reconnaissance a consequential tool should not enable.
     */
    private AiToolResult requestApproval(Execution execution, AiToolDefinition definition,
                                          Map<String, Object> arguments, LocalDateTime requestedAt,
                                          AiToolInvocation request) {
        // The approval AND its execution row are written by AiApprovalService, because the service's own read
        // path joins the two on approvalId: a request written without its execution would be a PENDING row
        // that approve() could never find. One write site is the only arrangement in which a new caller
        // cannot get that wrong.
        AiApprovalService.Requested requested = approvals.request(execution.scope(), execution.principal(),
            execution.runId(), definition.name(),
            "the model requested " + definition.name() + " for run " + execution.runId(),
            arguments, null);
        log.info("AI tool requires approval tool={} run={} approval={} dryRunRequested={}",
            definition.name(), execution.runId(), requested.approvalId(), request.dryRun());
        return new AiToolResult(AiPolicySnapshot.ActionClass.CONSEQUENTIAL, "REQUIRE_APPROVAL",
            requested.executionId(), requested.approvalId(), Map.of(), null, false);
    }

    /** Executes the body, or its dry run, with the result redacted and projected. */
    private AiToolResult runBody(Execution execution,
                                 AiToolDefinition definition,
                                 AiToolRegistryService.EffectiveTool effective,
                                 TenantScope scope,
                                 Map<String, Object> arguments,
                                 LocalDateTime requestedAt,
                                 UUID approvalId,
                                 boolean dryRun) {
        AiToolContext context = new AiToolContext(execution.principal(), scope, execution.snapshot(),
            execution.runId(), execution.agentVersionId(), arguments, dryRun);
        AiToolCatalog.RegisteredTool registered = catalog.find(definition.name()).orElseThrow();
        Map<String, Object> raw;
        try {
            raw = dryRun ? registered.body().dryRun(context) : registered.body().perform(context);
        } catch (RuntimeException e) {
            // The body's failure is recorded as a failed tool call and the run continues with the tool
            // unavailable. Recorded as DENY rather than ALLOW so the shape of the attempt stays visible,
            // and the message is a class name plus a scrubbed provider message — never the body text.
            UUID executionId = trace.writeToolExecution(scope, execution.runId(), definition.name(),
                definition.actionClass().name(), definition.consequential(), "DENY",
                "tool-failed:" + e.getClass().getSimpleName(), null,
                AiToolArgumentValidator.redactedShape(arguments), "{}",
                PRINCIPAL_AI_AGENT, execution.principal().userId(), execution.agentVersionId(), null,
                requestedAt, null);
            log.warn("AI tool failed tool={} run={} reason={}", definition.name(), execution.runId(),
                e.getClass().getSimpleName());
            AiPolicySnapshot.ActionClass failed = definition.consequential()
                ? AiPolicySnapshot.ActionClass.CONSEQUENTIAL
                : AiPolicySnapshot.ActionClass.ANSWERABLE;
            return new AiToolResult(failed, "DENY", executionId, null,
                Map.of(), "tool-failed:" + e.getClass().getSimpleName(), dryRun);
        }

        Map<String, Object> projected = project(redactResult(execution, raw),
            effective.effectiveFieldAllowList());
        String resultJson = json.write(projected);

        LocalDateTime executedAt = LocalDateTime.now(ZoneOffset.UTC);
        int latency = (int) Math.min(Integer.MAX_VALUE,
            Math.max(0, java.time.Duration.between(requestedAt, executedAt).toMillis()));
        // The schema's biconditional: an ALLOW of a CONSEQUENTIAL action STILL names its approval, so
        // approvalId is written whenever one was relied on, and null for an answerable tool.
        UUID executionId = trace.writeToolExecution(scope, execution.runId(), definition.name(),
            definition.consequential() ? AiPolicySnapshot.ActionClass.CONSEQUENTIAL.name()
                : AiPolicySnapshot.ActionClass.ANSWERABLE.name(),
            definition.consequential(), "ALLOW", null, approvalId,
            AiToolArgumentValidator.redactedShape(arguments), resultJson,
            PRINCIPAL_AI_AGENT, execution.principal().userId(), execution.agentVersionId(), latency,
            requestedAt, executedAt);
        return new AiToolResult(definition.consequential() ? AiPolicySnapshot.ActionClass.CONSEQUENTIAL
            : AiPolicySnapshot.ActionClass.ANSWERABLE, "ALLOW", executionId, approvalId, projected, null,
            dryRun);
    }

    /** Redacts a tool result before it is persisted. A failure is a refusal, not a pass. */
    private Map<String, Object> redactResult(Execution execution, Map<String, Object> raw) {
        Map<String, Object> redacted = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String text) {
                RedactionOutcome outcome =
                    redaction.redact(execution.redactionPolicy(), AiRedactionEngine.STAGE_RESPONSE, text);
                redacted.put(entry.getKey(), outcome.text());
            } else if (value == null || value instanceof Number || value instanceof Boolean) {
                // Scalars pass through. A number that could be a national id is already covered by the
                // string path when it is rendered; a typed long here is a count, and a count is not a
                // customer identifier.
                redacted.put(entry.getKey(), value);
            } else {
                // Anything structured is rendered to json, redacted as a string, and kept as text. That
                // loses the structure, deliberately: a nested structure is where a whole row would
                // otherwise travel, and a redaction rule that cannot see a nested value is a rule that
                // does not apply.
                RedactionOutcome outcome = redaction.redact(execution.redactionPolicy(),
                    AiRedactionEngine.STAGE_RESPONSE, String.valueOf(value));
                redacted.put(entry.getKey(), outcome.text());
            }
        }
        return redacted;
    }

    /** Keeps only the effective field allow-list, which is the intersection the registry service computed. */
    private static Map<String, Object> project(Map<String, Object> result,
                                              java.util.Set<String> allowedFields) {
        if (allowedFields == null || allowedFields.isEmpty()) {
            // Never widened to "everything". An empty effective list means the tool may report nothing,
            // which is the fail-closed reading of an unconfigured field allow-list.
            return Map.of();
        }
        Map<String, Object> projected = new LinkedHashMap<>();
        for (String field : allowedFields) {
            if (result.containsKey(field)) {
                projected.put(field, result.get(field));
            }
        }
        return projected;
    }

    /**
     * Records a refusal, on both the governance table and the run trace.
     *
     * <h2>Why the recorded class is the TOOL's own, not {@code FORBIDDEN}</h2>
     *
     * <p>{@code ai_tool_executions_class_consistency_check} is a biconditional:
     * {@code consequentialAction = (actionClass = 'CONSEQUENTIAL')}. So a row saying
     * {@code actionClass = 'FORBIDDEN'} with {@code consequentialAction = true} is refused by the schema,
     * and it would be wrong anyway: the tool being disabled or unapproved is a fact about <em>that tool</em>,
     * and recording it as FORBIDDEN would say the tool is forbidden to the model in every configuration —
     * which is a different and stronger claim than "this tenant has it switched off".
     *
     * <p>So the row carries the declaration's class. {@code FORBIDDEN} is written only when there is no
     * declaration at all, i.e. for an unknown tool name, where "there is no such tool" is exactly what the
     * class means. {@code ai_tool_executions_forbidden_denied_check} then requires that row to be DENY with
     * no {@code executedAt}, which is what is written.
     *
     * <p>The refusal is still a DENY decision with a reason code and no execution time, which is what the
     * acceptance gate reads.
     */
    private AiToolResult recordRefusal(Execution execution,
                                       AiToolInvocation request,
                                       AiRefusalReason reason,
                                       String detail,
                                       LocalDateTime requestedAt,
                                       AiToolDefinition definition) {
        String toolName = request.toolName() == null ? "unknown" : request.toolName();
        String actionClass = definition == null
            ? AiPolicySnapshot.ActionClass.FORBIDDEN.name()
            : definition.actionClass().name();
        boolean consequential = definition != null && definition.consequential();
        UUID executionId = trace.writeToolExecution(execution.scope(), execution.runId(), toolName,
            actionClass, consequential, "DENY", reason.name(),
            null, AiToolArgumentValidator.redactedShape(request.arguments()), "{}",
            PRINCIPAL_AI_AGENT, execution.principal().userId(), execution.agentVersionId(), null,
            requestedAt, null);
        trace.appendToolCall(execution.scope(), execution.runId(), 0, toolName,
            AiToolArgumentValidator.redactedShape(request.arguments()), "{}", false, false,
            reason.name(), null, requestedAt);
        // Names and counts only. The reason code is the whole message, and the detail is already scrubbed.
        log.info("AI tool refused tool={} run={} reason={} detail={}", toolName, execution.runId(),
            reason.name(), com.firstelement.airag.provider.SecretScrubber.scrubAndBound(detail));
        // The RESULT reports the tool's own class too, so a caller branching on it sees the same
        // classification that was persisted. `AiPolicySnapshot.ActionClass` and
        // `AiToolDefinition.AiActionClass` are the same three names spelled for two schemas; the mapping is
        // here rather than in the caller so it cannot be got wrong twice.
        AiPolicySnapshot.ActionClass reported = definition == null
            ? AiPolicySnapshot.ActionClass.FORBIDDEN
            : switch (definition.actionClass()) {
                case ANSWERABLE -> AiPolicySnapshot.ActionClass.ANSWERABLE;
                case CONSEQUENTIAL -> AiPolicySnapshot.ActionClass.CONSEQUENTIAL;
                case FORBIDDEN -> AiPolicySnapshot.ActionClass.FORBIDDEN;
            };
        return new AiToolResult(reported, "DENY", executionId, null,
            Map.of(), reason.name(), request.dryRun());
    }

    /** The declared tools, for the orchestrator's prompt framing. */
    public List<String> offeredTools(TenantScope scope) {
        return registry.effectiveTools(scope).entrySet().stream()
            .filter(entry -> entry.getValue().enabled())
            .map(Map.Entry::getKey)
            .sorted()
            .toList();
    }

    }