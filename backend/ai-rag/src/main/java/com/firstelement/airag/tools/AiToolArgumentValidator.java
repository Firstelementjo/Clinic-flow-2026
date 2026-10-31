package com.firstelement.airag.tools;

import com.firstelement.airag.policy.AiRefusalReason;
import com.firstelement.airag.policy.AiRefusedException;
import com.firstelement.security.aitool.AiToolDescriptor;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Validates a model's tool arguments against a declared schema, before anything acts on them.
 *
 * <h2>Order matters, and the order is the security property</h2>
 *
 * <ol>
 *   <li><b>Forbidden arguments first.</b> A map containing {@code organizationId}, {@code tenantId},
 *       {@code projectIdOverride}, {@code scopeOverride}, {@code asUserId} or {@code role} is rejected
 *       <em>outright</em> — {@link AiRefusalReason#SCOPE_OVERRIDE_ATTEMPT} — regardless of what else is in
 *       it and regardless of whether the value is the caller's own tenant.</li>
 *   <li><b>Unknown arguments next.</b> Anything not in the declared allow-list is
 *       {@link AiRefusalReason#ARGUMENT_NOT_ALLOWED}.</li>
 *   <li><b>Required arguments.</b></li>
 *   <li><b>Types and bounds.</b></li>
 * </ol>
 *
 * <p>The first rule is first for a reason specific to AI: a scope-override attempt is <em>evidence</em>, not
 * a malformed request. Honouring it and ignoring it would make the tool's behaviour depend on which code
 * path read the map, and silently dropping it would report the call as ordinary. It is refused and recorded
 * before anything else looks at the map, so the record says what actually happened.
 *
 * <h2>Values are converted, never passed through</h2>
 *
 * <p>The returned map holds a {@code UUID}, a {@code LocalDate}, an {@code Integer} or a bounded
 * {@code String} — never the model's raw value. A body therefore cannot receive a string where it expected
 * a uuid and cast it itself, which is where a type-confusion bug becomes an injection.
 */
public final class AiToolArgumentValidator {

    private AiToolArgumentValidator() {
    }

    /**
     * Validates and converts.
     *
     * @throws AiRefusedException with a named reason. The detail carries <em>names</em> and declared bounds,
     *         never the offending value.
     */
    public static Map<String, Object> validate(AiToolDefinition definition, Map<String, Object> supplied) {
        if (definition == null) {
            throw AiRefusedException.of(AiRefusalReason.UNKNOWN_TOOL);
        }
        Map<String, Object> arguments = supplied == null ? Map.of() : supplied;

        for (String name : arguments.keySet()) {
            if (AiToolDescriptor.FORBIDDEN_ARGUMENTS.contains(name)) {
                throw AiRefusedException.of(AiRefusalReason.SCOPE_OVERRIDE_ATTEMPT,
                    "argument '" + name + "' is reserved; a model may not choose its own scope");
            }
        }
        for (String name : arguments.keySet()) {
            if (!definition.allowedArguments().contains(name)) {
                throw AiRefusedException.of(AiRefusalReason.ARGUMENT_NOT_ALLOWED,
                    "argument '" + name + "' is not declared by " + definition.name()
                        + "; declared arguments are " + definition.allowedArguments());
            }
        }
        for (AiToolDefinition.ArgumentSpec spec : definition.argumentSchema().values()) {
            Object value = arguments.get(spec.name());
            if (value == null) {
                if (spec.required()) {
                    throw AiRefusedException.of(AiRefusalReason.ARGUMENT_NOT_ALLOWED,
                        definition.name() + " requires argument '" + spec.name() + "'");
                }
                continue;
            }
            // A supplied-but-empty value for an optional argument is dropped rather than converted to
            // "", so an empty string never reaches a statement as if it were a deliberate choice.
            if (value instanceof String text && text.isBlank() && !spec.required()) {
                continue;
            }
        }

        Map<String, Object> converted = new LinkedHashMap<>();
        for (AiToolDefinition.ArgumentSpec spec : definition.argumentSchema().values()) {
            Object value = arguments.get(spec.name());
            if (value == null) {
                continue;
            }
            converted.put(spec.name(), convert(definition, spec, value));
        }
        return java.util.Collections.unmodifiableMap(converted);
    }

    private static Object convert(AiToolDefinition definition, AiToolDefinition.ArgumentSpec spec, Object value) {
        return switch (spec.type()) {
            case UUID -> toUuid(definition, spec, value);
            case DATE -> toDate(definition, spec, value);
            case INTEGER -> toInteger(definition, spec, value);
            case STRING -> toBoundedString(definition, spec, value);
        };
    }

    private static UUID toUuid(AiToolDefinition definition, AiToolDefinition.ArgumentSpec spec, Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (RuntimeException e) {
            throw AiRefusedException.of(AiRefusalReason.ARGUMENT_NOT_ALLOWED,
                definition.name() + " argument '" + spec.name() + "' must be a UUID");
        }
    }

    private static LocalDate toDate(AiToolDefinition definition, AiToolDefinition.ArgumentSpec spec,
                                    Object value) {
        if (value instanceof LocalDate date) {
            return date;
        }
        try {
            return LocalDate.parse(String.valueOf(value).trim());
        } catch (RuntimeException e) {
            throw AiRefusedException.of(AiRefusalReason.ARGUMENT_NOT_ALLOWED,
                definition.name() + " argument '" + spec.name() + "' must be an ISO-8601 date");
        }
    }

    private static Integer toInteger(AiToolDefinition definition, AiToolDefinition.ArgumentSpec spec,
                                     Object value) {
        int parsed;
        if (value instanceof Number number) {
            parsed = number.intValue();
        } else {
            try {
                parsed = Integer.parseInt(String.valueOf(value).trim());
            } catch (RuntimeException e) {
                throw AiRefusedException.of(AiRefusalReason.ARGUMENT_NOT_ALLOWED,
                    definition.name() + " argument '" + spec.name() + "' must be an integer");
            }
        }
        if (spec.minimum() != null && parsed < spec.minimum()) {
            throw AiRefusedException.of(AiRefusalReason.ARGUMENT_NOT_ALLOWED,
                definition.name() + " argument '" + spec.name() + "' is below the declared minimum of "
                    + spec.minimum());
        }
        if (spec.maximum() != null && parsed > spec.maximum()) {
            throw AiRefusedException.of(AiRefusalReason.ARGUMENT_NOT_ALLOWED,
                definition.name() + " argument '" + spec.name() + "' is above the declared maximum of "
                    + spec.maximum());
        }
        return parsed;
    }

    /**
     * Bounds a string argument.
     *
     * <p>A declared {@code maxLength} is enforced here rather than at the use site, because a string
     * argument is the one that could carry unbounded customer content into a statement or a log.
     */
    private static String toBoundedString(AiToolDefinition definition, AiToolDefinition.ArgumentSpec spec,
                                          Object value) {
        String text = String.valueOf(value);
        if (spec.maxLength() != null && text.length() > spec.maxLength()) {
            throw AiRefusedException.of(AiRefusalReason.ARGUMENT_NOT_ALLOWED,
                definition.name() + " argument '" + spec.name() + "' exceeds the declared maximum length of "
                    + spec.maxLength() + " characters");
        }
        return text;
    }

    /**
     * The redacted form of an argument map, for {@code ai_tool_executions."argumentsRedacted"}.
     *
     * <p><b>Names and types only.</b> FR-5.6 permits only the redacted form, and the honest reading of
     * "redacted" for a model-supplied map is "no values": a value is model output that may contain
     * prompt-injection content or a customer's details quoted from a document, and neither belongs in a
     * durable row. So this returns {@code {"doctorId": "uuid", "day": "date"}} — the shape, which is what an
     * operator needs to understand a refusal, and nothing else.
     */
    public static String redactedShape(Map<String, Object> arguments) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(entry.getKey()).append("\":\"")
                .append(typeName(entry.getValue())).append('"');
        }
        return json.append('}').toString();
    }

    /**
     * The persisted shape of an invocation's arguments, as the same {@link Map} the stored JSON parses to.
     *
     * <p>Built with {@link #redactedShape(Map)} so the type labels are produced by the one function that
     * defines them. An earlier version of this method rebuilt each label by wrapping a single value in
     * {@code Map.of("v", value)} and stripping the braces — which yields the literal key {@code "v"}, so
     * the comparison could never succeed and every {@code consume()} returned false. A binding that can
     * never match is worse than no binding: it reads as "the approval was refused", which sends an
     * operator looking at the approval instead of at the comparison.
     *
     * <p>Returns a {@link Map} rather than a JSON string so {@link #matches} compares the parsed stored
     * object against the live shape directly, with no second parser to keep in step.
     */
    public static Map<String, Object> redactedShapeOf(Map<String, Object> arguments) {
        Map<String, Object> shape = new LinkedHashMap<>();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(redactedShape(arguments == null ? Map.of() : arguments), Map.class);
            if (parsed != null) {
                shape.putAll(parsed);
            }
        } catch (Exception e) {
            // An unparseable shape is treated as "no approved shape", which consume() reads as no match. A
            // binding that cannot be computed must fail closed, and failing closed here means the approval is
            // not spendable.
            return Map.of();
        }
        return shape;
    }

    private static String typeName(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof UUID) {
            return "uuid";
        }
        if (value instanceof LocalDate) {
            return "date";
        }
        if (value instanceof Number) {
            return "integer";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Map) {
            return "object";
        }
        if (value instanceof Iterable) {
            return "array";
        }
        return "string";
    }
}