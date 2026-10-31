package com.firstelement.airag.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The compiled tool catalogue: every tool this build can run, by name.
 *
 * <h2>Why the catalogue is code and the registry is data</h2>
 *
 * <p>The split is the whole design and it is worth stating precisely, because getting it the other way
 * round is the common failure:
 *
 * <ul>
 *   <li><b>Code</b> owns the body, the argument schema, the required capability and the field allow-list.
 *       Nothing a tenant, a model or a client supplies can add a tool, add an argument, or widen the output
 *       projection. That is what "a tool may not be constructed from caller-supplied SQL, a template, or a
 *       column list" means in practice.</li>
 *   <li><b>Data</b> ({@code ai_tool_registry}) owns whether a tenant has switched it on, for which
 *       projects, and with which declared permission. That is an operational decision, it has an
 *       approver, and it is revocable without a deployment.</li>
 * </ul>
 *
 * <p>{@code ai_tool_registry."schemaDefinition"} exists and is <em>not</em> used to author anything. It is
 * written from {@link AiToolDefinition#schemaJson()} so an operator can read what a tool expects, and a
 * divergence between it and the compiled schema is a reporting defect rather than a security hole — because
 * the compiled schema is the one enforced. That is stated in the resolver, which logs a mismatch rather
 * than trusting the column.
 *
 * <h2>An unknown name is not an error, it is a denial</h2>
 *
 * <p>{@link #find} returns empty and the executor records {@link
 * com.firstelement.airag.policy.AiRefusalReason#UNKNOWN_TOOL}. The model is told the tool does not exist
 * and the operator is told a model asked for it — the two audiences get different information on purpose.
 */
@Component
public class AiToolCatalog {

    private final Map<String, RegisteredTool> byName;

    public AiToolCatalog(List<AiToolBody> bodies) {
        Map<String, RegisteredTool> tools = new LinkedHashMap<>();
        for (AiToolBody body : bodies == null ? List.<AiToolBody>of() : bodies) {
            if (body == null) {
                continue;
            }
            RegisteredTool registered = RegisteredTool.of(body);
            RegisteredTool existing = tools.put(registered.definition().name(), registered);
            if (existing != null) {
                throw new IllegalStateException("Two AI tool bodies claim the name '"
                    + registered.definition().name() + "': " + existing.body().getClass().getName()
                    + " and " + body.getClass().getName()
                    + ". Which one a model reaches would depend on bean order.");
            }
        }
        this.byName = Map.copyOf(tools);
    }

    /**
     * A body and its declaration, bound together.
     *
     * <p>Binding them in one object is what removes the possibility of a body existing without a
     * declaration, or with a declaration that describes a different body. A registry lookup returns the
     * pair, so the executor cannot check one and execute the other.
     */
    public record RegisteredTool(AiToolDefinition definition, AiToolBody body) {

        public static RegisteredTool of(AiToolBody body) {
            // Each body exposes its own declaration. A body that is not one of this module's tools fails
            // here at startup rather than being absent from the catalogue and discovered at run time.
            if (body instanceof ConversationLookupTool) {
                return new RegisteredTool(ConversationLookupTool.definition(), body);
            }
            if (body instanceof AppointmentProposalTool) {
                return new RegisteredTool(AppointmentProposalTool.definition(), body);
            }
            throw new IllegalStateException("AI tool body " + body.getClass().getName()
                + " does not expose an AiToolDefinition. A tool without a declared schema, capability, risk "
                + "level and field allow-list cannot be registered, because the declaration is what the "
                + "policy engine checks.");
        }
    }

    /** The tool, when this build has one by that name. */
    public Optional<RegisteredTool> find(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(byName.get(name));
    }

    /** Every tool name this build serves. For the registry reconciliation report. */
    public List<String> names() {
        return List.copyOf(byName.keySet());
    }

    /** Every declaration, for a registry comparison. */
    public List<AiToolDefinition> definitions() {
        return byName.values().stream().map(RegisteredTool::definition).toList();
    }

    /**
     * The definitions a registry row is checked against.
     *
     * <p>A registry row whose {@code schemaDefinition} disagrees with the compiled declaration is reported,
     * not honoured. The compiled declaration wins because it is what the guard enforces; a mismatch means
     * the registry is stale or was hand-edited, and silently preferring the column would be preferring the
     * thing nobody reviewed.
     */
    public Map<String, Boolean> schemaAgreement(Map<String, String> registrySchemas) {
        Map<String, Boolean> agreement = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : registrySchemas.entrySet()) {
            Optional<RegisteredTool> tool = find(entry.getKey());
            agreement.put(entry.getKey(),
                tool.isPresent() && tool.get().definition().schemaJson().equals(entry.getValue()));
        }
        return agreement;
    }
}