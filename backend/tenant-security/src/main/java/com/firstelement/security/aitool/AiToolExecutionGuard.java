package com.firstelement.security.aitool;

import com.firstelement.security.TenantContext;
import com.firstelement.security.error.PolicyDeniedException;
import com.firstelement.security.event.SecurityEvent;
import com.firstelement.security.event.SecurityEventPublisher;
import com.firstelement.security.event.SecurityEventType;
import com.firstelement.security.query.TenantQueryGuard;
import com.firstelement.security.query.TenantScope;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The gate every application-owned tool call passes through, whether it was requested
 * by a model or by application code.
 *
 * <h2>FR-5.6: "The model may request a tool, but the application owns tool execution
 * and authorization."</h2>
 *
 * <p>So the request that reaches here is untrusted input, and the checks are, in order:
 *
 * <ol>
 *   <li><b>Registered and enabled.</b> An unknown tool and a disabled tool are the same
 *       denial, and neither is distinguishable to the caller.</li>
 *   <li><b>Capability held.</b> The principal's context must carry the tool's required
 *       capability. This is what stops a retrieval-only worker from calling a write tool.</li>
 *   <li><b>Arguments cannot override scope.</b> Any argument named in
 *       {@link AiToolDescriptor#FORBIDDEN_ARGUMENTS} is rejected outright. A model that
 *       asks for {@code {"organizationId": "<other tenant>"}} is refused, not honoured
 *       and not ignored. Accepting it "and then ignoring it" would make the tool's
 *       behaviour depend on which code path read the map.</li>
 *   <li><b>Arguments are an allow-list.</b> Any argument the descriptor does not
 *       declare is rejected.</li>
 *   <li><b>Project id is validated, not trusted.</b> It is resolved through
 *       {@link TenantQueryGuard}, so the tool body receives a {@link TenantScope} it
 *       cannot disagree with.</li>
 *   <li><b>High-risk actions need human approval.</b> Recorded as
 *       {@link AiToolInvocation#humanApproved()} before the body may run.</li>
 * </ol>
 *
 * <p>Every denial is published to the security stream, including the tool name and the
 * reason, so a disabled-tool probe is visible to operators while the model receives
 * nothing that indicates whether the tool exists.
 */
@Component
public class AiToolExecutionGuard {

    private static final Logger log = LoggerFactory.getLogger(AiToolExecutionGuard.class);

    private final TenantQueryGuard guard;
    private final SecurityEventPublisher events;

    public AiToolExecutionGuard(TenantQueryGuard guard, SecurityEventPublisher events) {
        this.guard = guard;
        this.events = events == null ? event -> { } : events;
    }

    /**
     * A model- or code-supplied request to run a tool.
     *
     * @param toolName       the requested tool
     * @param arguments      the arguments, untrusted
     * @param requestedProjectId the project's id the model asked for, if any
     * @param humanApproved  whether a human approved a high-risk action
     */
    public record AiToolInvocation(String toolName,
                                   Map<String, Object> arguments,
                                   UUID requestedProjectId,
                                   boolean humanApproved) {

        public static AiToolInvocation of(String toolName, Map<String, Object> arguments) {
            return new AiToolInvocation(toolName, arguments, null, false);
        }
    }

    /** What the tool body is allowed to act on. It cannot construct a different one. */
    public record AuthorizedExecution(AiToolDescriptor tool, TenantScope scope, Set<String> argumentNames) {
    }

    /**
     * @throws PolicyDeniedException on any denial
     */
    public AuthorizedExecution authorize(TenantContext context, AiToolDescriptor tool, AiToolInvocation call) {
        if (context == null) {
            throw deny(context, tool, call, "no-tenant-context");
        }
        if (tool == null || call == null) {
            throw deny(context, tool, call, "unknown-tool");
        }
        if (!tool.enabled()) {
            throw deny(context, tool, call, "tool-disabled");
        }
        if (!context.hasCapability(tool.requiredCapability())) {
            throw deny(context, tool, call, "capability-missing");
        }

        Set<String> supplied = call.arguments() == null ? Set.of() : call.arguments().keySet();
        for (String argument : supplied) {
            if (AiToolDescriptor.FORBIDDEN_ARGUMENTS.contains(argument)) {
                throw deny(context, tool, call, "scope-override-attempt");
            }
            if (!tool.allowedArguments().contains(argument)) {
                throw deny(context, tool, call, "argument-not-allowed");
            }
        }

        if (tool.highRisk() && !call.humanApproved()) {
            throw deny(context, tool, call, "human-approval-required");
        }

        TenantScope scope;
        try {
            scope = tool.projectScoped()
                ? guard.scope(context, call.requestedProjectId())
                : guard.tenantScope(context);
        } catch (PolicyDeniedException e) {
            // The query guard already recorded the scope denial. Record it again as an
            // AI-tool denial so the AI stream shows a model asking for data it may not
            // have, which is the signal an operator needs to see a probing agent.
            publishDenied(context, tool, call, "project-out-of-scope");
            throw e;
        }

        return new AuthorizedExecution(tool, scope, new LinkedHashSet<>(supplied));
    }

    private PolicyDeniedException deny(TenantContext context,
                                       AiToolDescriptor tool,
                                       AiToolInvocation call,
                                       String reason) {
        publishDenied(context, tool, call, reason);
        return PolicyDeniedException.projectOutOfScope(reason,
            context == null ? null : context.organizationId(), null);
    }

    private void publishDenied(TenantContext context, AiToolDescriptor tool,
                               AiToolInvocation call, String reason) {
        String toolName = tool != null ? tool.name() : (call == null ? "unknown" : call.toolName());
        try {
            events.publish(SecurityEvent.builder(SecurityEventType.AI_TOOL_DENIED)
                .tenantContext(context)
                .resourceType("AiTool")
                .resourceId(toolName)
                .reason(reason)
                // Argument NAMES only. Values are model output and may contain
                // prompt-injection content; they are not written to the event stream.
                .metadata(Map.of("tool", toolName, "reason", reason,
                    "argumentNames", call == null || call.arguments() == null
                        ? "[]" : String.join(",", call.arguments().keySet())))
                .correlationId(org.slf4j.MDC.get("correlationId"))
                .build());
        } catch (RuntimeException e) {
            log.error("Failed to publish AI_TOOL_DENIED tool={}", toolName, e);
        }
    }
}