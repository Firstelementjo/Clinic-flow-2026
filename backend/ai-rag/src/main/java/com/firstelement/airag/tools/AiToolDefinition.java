package com.firstelement.airag.tools;

import com.firstelement.security.Capability;
import com.firstelement.security.aitool.AiToolDescriptor;
import com.firstelement.security.permission.Permission;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * One application-owned tool, declared in code.
 *
 * <h2>"A tool may not be constructed from caller-supplied SQL, a template, or a column list"</h2>
 *
 * <p>This record has no field for any of them, and that is the whole design. There is no
 * {@code sql}, no {@code queryTemplate}, no {@code columns}, no {@code statement} — a tool is a
 * {@link #name()}, a fixed argument allow-list, and a Java body that issues a statement this module
 * already wrote. A model cannot widen what a tool reads by naming a column, because there is no field in
 * which to name one.
 *
 * <p>The fields that <em>are</em> here are the ones FR-5.5 enumerates: explicit (a developer registered
 * it), schema-defined ({@link #argumentSchema()}), tenant-scoped ({@link #projectScoped()}),
 * capability-checked ({@link #requiredCapability()}), field-limited ({@link #allowedArguments()} and
 * {@link #fieldAllowList()}), audited (every decision becomes an {@code ai_tool_executions} row), and
 * deny-by-default ({@link #enabledByDefault()} is {@code false} for every tool, and the registry's
 * {@code isEnabled} has to agree).
 *
 * <h2>Why {@link #requiredPermission()} is separate from {@link #requiredCapability()}</h2>
 *
 * <p>Because they answer different questions and ADR 0010 §2's rule applies to both: a permission is named
 * after what it authorizes. The capability is a machine-principal grant ({@code AI_TOOL_EXECUTE}); the
 * permission is a human role grant. {@code ai_tool_registry."requiredPermission"} is NOT NULL because "a
 * tool with no required permission is an unrestricted capability" — so this field is the tenant's
 * declaration, and {@link #descriptor(Capability)} intersects it rather than replacing it.
 */
public record AiToolDefinition(String name,
                               String descriptionAr,
                               Set<String> allowedArguments,
                               Map<String, ArgumentSpec> argumentSchema,
                               Set<String> fieldAllowList,
                               Capability requiredCapability,
                               String requiredPermission,
                               AiRiskLevel riskLevel,
                               boolean consequential,
                               boolean projectScoped,
                               boolean dryRunSupported,
                               AiActionClass actionClass) {

    /** The argument kinds a tool may declare. A closed set, like every other vocabulary here. */
    public enum ArgumentType {
        /** A free-form string, always bounded before use. */
        STRING,
        /** A UUID, validated before it reaches a statement. */
        UUID,
        /** An ISO-8601 date, bounded to a sane range. */
        DATE,
        /** A non-negative integer. */
        INTEGER
    }

    /** One declared argument: its type, whether it is required, and a bound. */
    public record ArgumentSpec(String name,
                               ArgumentType type,
                               boolean required,
                               Integer maxLength,
                               Integer minimum,
                               Integer maximum) {

        public ArgumentSpec {
            if (type == null) {
                throw new IllegalArgumentException("An argument spec must declare a type");
            }
        }

        public static ArgumentSpec requiredString(String name, int maxLength) {
            return new ArgumentSpec(name, ArgumentType.STRING, true, maxLength, null, null);
        }

        public static ArgumentSpec optionalString(String name, int maxLength) {
            return new ArgumentSpec(name, ArgumentType.STRING, false, maxLength, null, null);
        }

        public static ArgumentSpec requiredUuid(String name) {
            return new ArgumentSpec(name, ArgumentType.UUID, true, null, null, null);
        }

        public static ArgumentSpec optionalUuid(String name) {
            return new ArgumentSpec(name, ArgumentType.UUID, false, null, null, null);
        }

        public static ArgumentSpec requiredInteger(String name, int minimum, int maximum) {
            return new ArgumentSpec(name, ArgumentType.INTEGER, true, null, minimum, maximum);
        }

        public static ArgumentSpec optionalInteger(String name, int minimum, int maximum) {
            return new ArgumentSpec(name, ArgumentType.INTEGER, false, null, minimum, maximum);
        }
    }

    /** ADR 0007 Part 2's action classes, spelled as {@code ai_tool_executions."actionClass"}. */
    public enum AiActionClass {
        ANSWERABLE,
        CONSEQUENTIAL,
        FORBIDDEN
    }

    /** The risk levels, matching {@code public."AiRiskLevel"}. */
    public enum AiRiskLevel {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }

    /**
     * Deny by default, literally.
     *
     * <p>Not a parameter. {@code ai_tool_registry."isEnabled"} already defaults to {@code false} and the
     * migration's guard fails if that default is ever flipped; a tool definition that could declare itself
     * enabled would reintroduce at the application layer exactly the hole the schema closes.
     */
    public boolean enabledByDefault() {
        return false;
    }

    public AiToolDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("An AI tool needs a name");
        }
        if (actionClass == null || riskLevel == null || requiredCapability == null) {
            throw new IllegalArgumentException(
                "An AI tool must declare an action class, a risk level and a required capability. An "
                    + "undeclared one is an unrestricted capability, which is what FR-5.5 forbids.");
        }
        if (requiredPermission == null || requiredPermission.isBlank()) {
            throw new IllegalArgumentException(
                "ai_tool_registry.\"requiredPermission\" is NOT NULL for the same reason: a tool with no "
                    + "required permission is an unrestricted capability.");
        }
        if (actionClass == AiActionClass.FORBIDDEN && !consequential) {
            throw new IllegalArgumentException(
                "A FORBIDDEN tool must be marked consequential; the schema's class-consistency CHECK "
                    + "derives one from the other and would refuse the row.");
        }
        allowedArguments = allowedArguments == null ? Set.of() : Set.copyOf(allowedArguments);
        argumentSchema = argumentSchema == null ? Map.of() : Map.copyOf(argumentSchema);
        fieldAllowList = fieldAllowList == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(fieldAllowList));
        // The allow-list and the schema must agree, and the disagreement is caught here rather than at
        // runtime: a declared argument with no spec would be passed through unvalidated.
        for (String declared : allowedArguments) {
            if (!argumentSchema.containsKey(declared)) {
                throw new IllegalArgumentException("AI tool " + name + " allows argument '" + declared
                    + "' but declares no spec for it. An argument without a type is an unvalidated input.");
            }
        }
        for (String spec : argumentSchema.keySet()) {
            if (!allowedArguments.contains(spec)) {
                throw new IllegalArgumentException("AI tool " + name + " declares spec '" + spec
                    + "' but does not allow it. A spec that cannot arrive is a statement that the tool was "
                    + "written expecting input it forbids.");
            }
        }
    }

    /**
     * The {@link AiToolDescriptor} this tool answers to at the guard boundary.
     *
     * <p>{@code enabled} is passed in rather than read from the definition: the definition is code, and
     * whether a tenant has switched it on is {@code ai_tool_registry."isEnabled"}, which is data. The
     * descriptor is constructed with the <em>effective</em> state so
     * {@link com.firstelement.security.aitool.AiToolExecutionGuard} cannot accidentally approve a tool the
     * tenant has disabled.
     *
     * @param registryEnabled the tenant's effective enablement for this tool
     */
    public AiToolDescriptor descriptorFor(boolean registryEnabled) {
        // highRisk mirrors "needs a human before it runs". The schema forbids enabling a HIGH or CRITICAL
        // tool with an empty field allow-list, and consequential is the class the approval flow keys on.
        // `consequential` alone is what the guard checks, because it is the same fact the tool execution
        // row records; the risk level is a second, independent reason to stop.
        return new AiToolDescriptor(name, registryEnabled, requiredCapability, projectScoped,
            allowedArguments, consequential);
    }

    /**
     * Whether a concrete risk level means a human must dispose of the request.
     *
     * <p>Independent of {@link #consequential()}: the schema allows a LOW-risk tool that is still marked
     * consequential, and a HIGH-risk tool that is not. Both stop for approval.
     */
    public boolean requiresHumanApproval() {
        return consequential || riskLevel == AiRiskLevel.HIGH || riskLevel == AiRiskLevel.CRITICAL;
    }

    /** The permission a human operator must hold to enable this tool. Named for what it authorizes. */
    public Permission enablingPermission() {
        return Permission.AI_AGENT_CONFIGURE;
    }

    /** A schema-derived jsonb object, for {@code ai_tool_registry."schemaDefinition"}. */
    public String schemaJson() {
        StringBuilder json = new StringBuilder("{\"type\":\"object\",\"properties\":{");
        boolean first = true;
        for (Map.Entry<String, ArgumentSpec> entry : argumentSchema.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            ArgumentSpec spec = entry.getValue();
            json.append('"').append(entry.getKey()).append("\":{\"type\":\"")
                .append(switch (spec.type()) {
                    case STRING -> "string";
                    case UUID -> "string";
                    case DATE -> "string";
                    case INTEGER -> "integer";
                })
                .append('"');
            if (spec.maxLength() != null) {
                json.append(",\"maxLength\":").append(spec.maxLength());
            }
            if (spec.minimum() != null) {
                json.append(",\"minimum\":").append(spec.minimum());
            }
            if (spec.maximum() != null) {
                json.append(",\"maximum\":").append(spec.maximum());
            }
            json.append('}');
        }
        json.append("},\"required\":[");
        first = true;
        for (Map.Entry<String, ArgumentSpec> entry : argumentSchema.entrySet()) {
            if (entry.getValue().required()) {
                if (!first) {
                    json.append(',');
                }
                first = false;
                json.append('"').append(entry.getKey()).append('"');
            }
        }
        return json.append("]}").toString();
    }
}