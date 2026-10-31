package com.firstelement.airag.tools;

import com.firstelement.airag.policy.AiPolicySnapshot;
import com.firstelement.security.TenantContext;
import com.firstelement.security.query.TenantScope;
import java.util.Map;
import java.util.UUID;

/**
 * What a tool body is allowed to act on, handed to it by the executor.
 *
 * <h2>A tool body cannot construct a different one</h2>
 *
 * <p>This is the type that makes "authorization before the body runs" structural rather than a convention.
 * The body receives a {@link TenantScope} it cannot disagree with — produced by
 * {@link com.firstelement.security.query.TenantQueryGuard} from the principal's server-resolved scope — and
 * it has no parameter through which a caller could name a different tenant, project or user. The
 * {@link #requestedProjectId()} accessor returns the model's request <em>as a request</em>, for the body to
 * ignore; the scope is what it must use.
 *
 * <h2>Why the run id is here</h2>
 *
 * <p>Because a consequential tool's whole purpose is to produce an approval request bound to a run
 * ({@code ai_approvals."aiRunId"} is NOT NULL), and the run is the thing a human reads to decide. A tool
 * that asked for approval without naming a run would leave an approval row nobody can relate to an answer.
 *
 * @param snapshot the policy in force, so a body can classify its own action without re-reading the tenant
 * @param principal the calling principal, for the audit row's {@code principalType} and {@code actorUserId}
 * @param arguments the <b>validated</b> arguments, already typed and bounded
 * @param dryRun whether to report the intended effect without performing it
 */
public record AiToolContext(TenantContext principal,
                            TenantScope scope,
                            AiPolicySnapshot snapshot,
                            UUID runId,
                            UUID agentVersionId,
                            Map<String, Object> arguments,
                            boolean dryRun) {

    public AiToolContext {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }

    /**
     * The model's requested project id.
     *
     * <p>Exposed only so a body can refuse explicitly rather than acting on a scope the caller might not
     * have noticed. Returning it at all is a deliberate small risk taken to make the mismatch legible: the
     * authoritative value is {@link #scope()}.
     */
    public UUID requestedProjectId() {
        return scope.projectId();
    }

    /** A required string argument, already validated by the executor. */
    public String requireString(String name) {
        Object value = arguments.get(name);
        return value == null ? null : String.valueOf(value);
    }

    /** A required UUID argument, already validated. */
    public UUID requireUuid(String name) {
        Object value = arguments.get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return UUID.fromString(String.valueOf(value));
    }

    /** A required integer argument, already validated. */
    public Integer requireInteger(String name) {
        Object value = arguments.get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.valueOf(String.valueOf(value));
    }

    /** An optional string argument, {@code null} when absent. */
    public String optionalString(String name) {
        return requireString(name);
    }
}