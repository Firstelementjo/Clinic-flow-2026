package com.firstelement.airag.tools;

import java.util.Map;
import java.util.UUID;

/**
 * One request to run a tool, and everything the guard needs to decide about it.
 *
 * @param toolName       the requested tool; unknown names are refused and indistinguishable from disabled
 *                       ones to the caller
 * @param arguments      the model's arguments, untrusted
 * @param requestedProjectId the project the model asked for, validated against the principal's scope and
 *                       never honoured because the model named it
 * @param humanApproved  whether a human approved this <em>specific</em> invocation. Not a blanket flag:
 *                       {@link com.firstelement.airag.policy.AiApprovalService} checks the approval id, the
 *                       run, the action and the argument fingerprint, so an approval for one booking cannot
 *                       be replayed for a different time slot.
 * @param approvalId     the approval being relied on, or {@code null}
 * @param dryRun         whether to validate and report the intended effect without performing it
 */
public record AiToolInvocation(String toolName,
                               Map<String, Object> arguments,
                               UUID requestedProjectId,
                               boolean humanApproved,
                               UUID approvalId,
                               boolean dryRun) {

    public AiToolInvocation {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }

    public static AiToolInvocation of(String toolName, Map<String, Object> arguments) {
        return new AiToolInvocation(toolName, arguments, null, false, null, false);
    }

    /** The same invocation, in dry-run mode. */
    public AiToolInvocation asDryRun() {
        return new AiToolInvocation(toolName, arguments, requestedProjectId, humanApproved, approvalId, true);
    }

    /** The same invocation, relying on a specific approval. */
    public AiToolInvocation approved(UUID approval) {
        return new AiToolInvocation(toolName, arguments, requestedProjectId, true, approval, dryRun);
    }
}