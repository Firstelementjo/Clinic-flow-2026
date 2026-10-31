package com.firstelement.airag.tools;

import com.firstelement.airag.policy.AiPolicySnapshot;
import java.util.Map;
import java.util.UUID;

/**
 * What the executor did with a request. Every field is safe to persist and to show an operator.
 *
 * @param decision  ALLOW / REQUIRE_APPROVAL / DENY, exactly as {@code ai_tool_executions."decision"}
 * @param execution the {@code ai_tool_executions} row id, so a caller can point an operator at the evidence
 * @param approvalId the approval row when the decision was REQUIRE_APPROVAL or an approved ALLOW
 * @param result    the tool's redacted output, or an empty map on a denial
 * @param refusal   the {@link com.firstelement.airag.policy.AiRefusalReason} code on a denial
 * @param dryRun    whether this result describes an intended effect rather than a performed one
 */
public record AiToolResult(AiPolicySnapshot.ActionClass actionClass,
                           String decision,
                           UUID execution,
                           UUID approvalId,
                           Map<String, Object> result,
                           String refusal,
                           boolean dryRun) {

    public AiToolResult {
        // NOT Map.copyOf: a tool's result legitimately contains NULL values — an appointment with no
        // firstResponseAt, a lookup that found nothing — and Map.copyOf throws on a null value. The naive
        // copyOf here would turn "this tool has a null field" into a runtime failure, which is a way for a
        // tool's own data shape to break the policy layer.
        result = result == null ? Map.of() : java.util.Collections.unmodifiableMap(
            new java.util.LinkedHashMap<>(result));
    }

    public boolean allowed() {
        return "ALLOW".equals(decision);
    }

    public boolean awaitingApproval() {
        return "REQUIRE_APPROVAL".equals(decision);
    }

    public boolean denied() {
        return "DENY".equals(decision);
    }

    static AiToolResult deny(String refusal, UUID execution) {
        return new AiToolResult(AiPolicySnapshot.ActionClass.FORBIDDEN, "DENY", execution, null,
            Map.of(), refusal, false);
    }

    static AiToolResult requireApproval(AiPolicySnapshot.ActionClass actionClass, UUID execution,
                                        UUID approvalId) {
        return new AiToolResult(actionClass, "REQUIRE_APPROVAL", execution, approvalId, Map.of(), null,
            false);
    }
}