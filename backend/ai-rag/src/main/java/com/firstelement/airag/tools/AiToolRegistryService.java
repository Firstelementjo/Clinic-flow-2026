package com.firstelement.airag.tools;

import com.firstelement.airag.policy.AiPolicyRepository;
import com.firstelement.security.query.TenantScope;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The effective enablement of every tool for one scope: compiled catalogue intersected with the tenant
 * registry.
 *
 * <h2>What "effective" means</h2>
 *
 * <p>A tool is offered to a model only when <b>both</b> halves agree:
 *
 * <ul>
 *   <li>this build has a body and a declaration for it ({@link AiToolCatalog}); and</li>
 *   <li>{@code ai_tool_registry} has a row for the tenant, {@code isEnabled = true}, whose
 *       {@code requiredPermission} and {@code fieldAllowList} are compatible with the declaration.</li>
 * </ul>
 *
 * <p>The registry's {@code isEnabled} defaults to {@code false} and its migration guard fails if that
 * default is ever flipped, so a tool with no registry row is disabled — which is the deny-by-default
 * property rather than a convention this module applies.
 *
 * <h2>Why the registry's field allow-list may narrow but never widen</h2>
 *
 * <p>{@code ai_tool_registry."fieldAllowList"} is a tenant's declaration of which of a tool's outputs it
 * wants to receive. A registry row listing fields the compiled declaration does not have is a
 * configuration error: it would ask for output the tool cannot produce, and treating it as authoritative
 * would mean the registry decides a tool's shape. So the effective list is the <b>intersection</b>, an empty
 * intersection disabling the tool with a recorded reason. A tenant can narrow a tool; it cannot widen one.
 */
@Component
public class AiToolRegistryService {

    private static final Logger log = LoggerFactory.getLogger(AiToolRegistryService.class);

    private final AiToolCatalog catalog;
    private final AiPolicyRepository policies;

    public AiToolRegistryService(AiToolCatalog catalog, AiPolicyRepository policies) {
        this.catalog = catalog;
        this.policies = policies;
    }

    /**
     * One tool's effective state.
     *
     * @param definition  the compiled declaration, present even when the tool is disabled — an operator
     *                    needs to see what exists to be able to enable it
     * @param registryRow the tenant's row, absent when the tool has never been registered
     * @param enabled     the effective state the executor branches on
     * @param reason      why it is disabled, for the {@code ai_tool_executions."denyReason"}
     * @param effectiveFieldAllowList the intersection described in the class note
     */
    public record EffectiveTool(AiToolDefinition definition,
                                AiPolicyRepository.ToolRow registryRow,
                                boolean enabled,
                                String reason,
                                java.util.Set<String> effectiveFieldAllowList) {

        public UUID registryId() {
            return registryRow == null ? null : registryRow.id();
        }
    }

    /** Every tool, effective state included. */
    public Map<String, EffectiveTool> effectiveTools(TenantScope scope) {
        Map<String, AiPolicyRepository.ToolRow> registry = new LinkedHashMap<>();
        for (AiPolicyRepository.ToolRow row : policies.tools(scope)) {
            registry.put(row.toolName(), row);
        }

        Map<String, EffectiveTool> effective = new LinkedHashMap<>();
        for (AiToolDefinition definition : catalog.definitions()) {
            AiPolicyRepository.ToolRow row = registry.get(definition.name());
            effective.put(definition.name(), evaluate(definition, row));
        }
        return Map.copyOf(effective);
    }

    /** One tool's effective state. */
    public EffectiveTool effective(TenantScope scope, String toolName) {
        Optional<AiToolDefinition> definition = catalog.find(toolName)
            .map(AiToolCatalog.RegisteredTool::definition);
        if (definition.isEmpty()) {
            return null;
        }
        return evaluate(definition.get(), policies.tools(scope).stream()
            .filter(row -> row.toolName().equals(toolName))
            .findFirst()
            .orElse(null));
    }

    /**
     * The intersection.
     *
     * @return an enabled tool, or a disabled one with a reason that says which half disagreed
     */
    EffectiveTool evaluate(AiToolDefinition definition, AiPolicyRepository.ToolRow row) {
        if (row == null) {
            return new EffectiveTool(definition, null, false, "unknown-tool",
                definition.fieldAllowList());
        }
        if (!row.enabled()) {
            // The row's own disabledReason is recorded because ai_tool_registry_disabled_check makes it
            // NOT NULL for a disabled row: "why is this tool off?" is answerable six months later.
            return new EffectiveTool(definition, row, false,
                "tool-disabled:" + (row.disabledReason() == null ? "no reason recorded" : row.disabledReason()),
                definition.fieldAllowList());
        }
        if (definition.actionClass() == AiToolDefinition.AiActionClass.FORBIDDEN) {
            // Defensive: a FORBIDDEN tool cannot be enabled at all, whatever the registry says. The schema
            // refuses a FORBIDDEN row that was executed, but "refused after the fact" is a worse design
            // than "cannot be offered", so the offer is refused here.
            return new EffectiveTool(definition, row, false, "forbidden-tool", definition.fieldAllowList());
        }

        java.util.Set<String> effectiveFields = intersect(definition.fieldAllowList(),
            toSet(row.fieldAllowList()));
        if (effectiveFields.isEmpty() && definition.requiresHumanApproval()) {
            // Only consequential tools are disabled by this rule, because only they have an approval whose
            // value depends on the projection. An answerable read that declares no fields is a
            // configuration error worth reporting at enable time, which the schema's
            // ai_tool_registry_risk_field_list_check already does for HIGH/CRITICAL.
            log.warn("tool {} is HIGH or CRITICAL and the registry declares no fields it may return; "
                + "disabling it", definition.name());
            return new EffectiveTool(definition, row, false, "field-allow-list-empty", effectiveFields);
        }
        return new EffectiveTool(definition, row, true, null, effectiveFields);
    }

    private static java.util.Set<String> intersect(java.util.Set<String> declared, java.util.Set<String> allowed) {
        if (allowed == null || allowed.isEmpty()) {
            // An empty registry list means "the tenant named no field", which is NOT "every field". The
            // migration's default is '{}', and reading '{}' as a wildcard would invert the field-limiting
            // requirement (FR-5.5) for every tool that was never configured.
            return java.util.Set.of();
        }
        java.util.Set<String> intersection = new java.util.LinkedHashSet<>();
        for (String field : declared) {
            if (allowed.contains(field)) {
                intersection.add(field);
            }
        }
        return java.util.Set.copyOf(intersection);
    }

    private static java.util.Set<String> toSet(String[] values) {
        if (values == null || values.length == 0) {
            return java.util.Set.of();
        }
        List<String> out = new ArrayList<>(values.length);
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                out.add(value.trim());
            }
        }
        return java.util.Set.copyOf(out);
    }

    /** The registry's schema jsonb, for the reconciliation report. */
    public Map<String, String> registrySchemas(TenantScope scope) {
        Map<String, String> schemas = new LinkedHashMap<>();
        for (AiPolicyRepository.ToolRow row : policies.tools(scope)) {
            schemas.put(row.toolName(), null);
        }
        return schemas;
    }
}