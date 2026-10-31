package com.firstelement.airag.provider;

/**
 * The provider vocabulary, transcribed from {@code public."AIProviderKind"} of the verified baseline.
 *
 * <h2>Why not the database enum directly</h2>
 *
 * <p>There is no ORM in this runtime (ADR-0002), so an enum column is a {@code String} on the Java side
 * and a {@code cast(:x as "AIProviderKind")} in SQL. {@link #fromWire(String)} refuses anything outside
 * this list rather than handing it to PostgreSQL, so a typo becomes a rejected command with the declared
 * values in the message instead of an error from three frames down.
 *
 * <h2>Why {@link #MOCK} is a first-class member</h2>
 *
 * <p>FR-5.2 and ADR 0007 Part 2 rule 3. The CRM must run with no provider key, and the only honest way
 * to guarantee that is for "no model available" to be a normal, tested path rather than a deployment
 * accident. {@code MOCK} is that path.
 */
public enum AiProviderKind {

    MOCK,
    OPENAI,
    ANTHROPIC,
    GEMINI,
    OPENAI_COMPATIBLE,
    LOCAL;

    /** The stored spelling, which is the enum label. */
    public String wireValue() {
        return name();
    }

    /**
     * Whether reaching this provider needs a credential this build was not given.
     *
     * <p>The distinction the whole "CRM without a key" claim rests on: {@code MOCK} and {@code LOCAL}
     * need nothing, and every other kind does. A kind in this second group with no registered adapter is
     * a refusal, never a boot failure and never a silent substitution.
     */
    public boolean requiresCredential() {
        return this != MOCK && this != LOCAL;
    }

    public static AiProviderKind fromWire(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("An AI provider kind must not be null");
        }
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        for (AiProviderKind kind : values()) {
            if (kind.name().equals(normalized)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown AIProviderKind '" + value + "'. Declared values are "
            + java.util.Arrays.stream(values()).map(Enum::name)
                .collect(java.util.stream.Collectors.joining(",")));
    }

    /** As {@link #fromWire(String)} but {@code null} instead of an exception, for filters. */
    public static AiProviderKind parseOrNull(String value) {
        try {
            return fromWire(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}