package com.firstelement.security.aitool;

import com.firstelement.security.Capability;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A tool the application is willing to let a model request.
 *
 * <h2>FR-5.5, made concrete</h2>
 *
 * <p>"Tools must be explicit, schema-defined, tenant-scoped, capability-checked,
 * field-limited, audited, and deny-by-default." Every clause is a field here:
 *
 * <ul>
 *   <li>{@code explicit} — a tool exists because a developer registered it.</li>
 *   <li>{@code tenant-scoped} — {@link #projectScoped()} decides whether a project id
 *       must be resolvable before the tool runs.</li>
 *   <li>{@code capability-checked} — {@link #requiredCapability()} must be on the
 *       principal's context.</li>
 *   <li>{@code field-limited} — {@link #allowedArguments()} is an allow-list; a
 *       tool cannot be handed a whole entity.</li>
 *   <li>{@code deny-by-default} — {@link #enabled()} must be true; a registered but
 *       disabled tool is denied, which is the state an admin leaves a risky tool in.</li>
 * </ul>
 *
 * @param name               stable tool name, e.g. {@code customer.lookup}
 * @param enabled             admin-controlled on/off
 * @param requiredCapability  capability the calling principal must hold
 * @param projectScoped       whether a {@code projectId} argument is required
 * @param allowedArguments    argument names the tool may receive
 * @param highRisk            whether human approval is required before execution
 */
public record AiToolDescriptor(
        String name,
        boolean enabled,
        Capability requiredCapability,
        boolean projectScoped,
        Set<String> allowedArguments,
        boolean highRisk
) {

    /** Argument names a tool may never receive, whatever it declares. */
    public static final Set<String> FORBIDDEN_ARGUMENTS = Set.of(
        "organizationId", "tenantId", "projectIdOverride", "scopeOverride", "asUserId", "role");

    public AiToolDescriptor {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("AiToolDescriptor.name is required");
        }
        allowedArguments = allowedArguments == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(allowedArguments));
    }

    public static AiToolDescriptor readOnly(String name,
                                            Capability requiredCapability,
                                            String... allowedArguments) {
        return new AiToolDescriptor(name, true, requiredCapability, false, Set.of(allowedArguments), false);
    }

    public static AiToolDescriptor projectScopedRead(String name,
                                                    Capability requiredCapability,
                                                    String... allowedArguments) {
        return new AiToolDescriptor(name, true, requiredCapability, true, Set.of(allowedArguments), false);
    }
}