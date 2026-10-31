package com.firstelement.security.redaction;

import com.firstelement.security.permission.Permission;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Which columns a principal's field may see.
 *
 * <p>Row authorization answers "which rows". This answers "which columns of the rows I
 * am allowed to see". FR-7.7 and FR-8.2 both require field authorization to be applied
 * before output, and PROJECT_REQUIREMENTS §8 forbids the Admin UI from displaying
 * passwords, API keys, session tokens, connector secrets, or encryption keys.
 *
 * <h2>Two layers, and the first one cannot be configured away</h2>
 *
 * <ol>
 *   <li>{@link #NEVER_EXPOSED} — credentials and secrets. Applied unconditionally, for
 *       every principal including {@code ADMIN}. A record with these columns is a bug
 *       in the projection, and the redactor makes it a non-leak rather than a breach.</li>
 *   <li>{@link #redacted()} — policy redactions that depend on the effective permission
 *       set, e.g. finance-only fields for a caller without the finance capability.</li>
 * </ol>
 *
 * @param redacted columns replaced by the placeholder for this principal
 */
public record FieldRedactionPolicy(Set<String> redacted) {

    /** Never exposed to anyone, at any permission level. */
    public static final Set<String> NEVER_EXPOSED = Collections.unmodifiableSet(new LinkedHashSet<>(Set.of(
        "passwordHash", "password",
        "tokenHash", "sessionToken", "csrfToken",
        "credentials", "encryptedCredentials", "secretBox",
        "apiKey", "apiKeyHash",
        "encryptionKey", "privateKey")));

    /** The value substituted for a redacted column. Constant, so absence is not a signal. */
    public static final String PLACEHOLDER = "[redacted]";

    public FieldRedactionPolicy {
        redacted = redacted == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(redacted));
    }

    /** Nothing redacted beyond {@link #NEVER_EXPOSED}. */
    public static FieldRedactionPolicy none() {
        return new FieldRedactionPolicy(Set.of());
    }

    public static FieldRedactionPolicy of(Set<String> redacted) {
        return new FieldRedactionPolicy(redacted);
    }

    /**
     * The columns this policy hides: the caller's policy redactions plus everything
     * in {@link #NEVER_EXPOSED}.
     */
    public Set<String> effectiveRedacted() {
        Set<String> merged = new LinkedHashSet<>(NEVER_EXPOSED);
        merged.addAll(redacted);
        return Collections.unmodifiableSet(merged);
    }

    /**
     * A per-permission field policy.
     *
     * <p>Modelled as allow-lists per permission rather than deny-lists, so a column
     * added to a table later is hidden by default from the projections that were not
     * updated to include it. That is the direction a redaction policy must fail.
     *
     * @param required permission a caller must hold to see the column at all
     * @param columns  the column names visible to such a caller
     */
    public record Column(String name, Permission required) {
    }

    /**
     * Derives the redacted set for a caller: every known column whose required
     * permission the caller does not hold.
     */
    public static FieldRedactionPolicy forColumns(Set<Permission> effectivePermissions,
                                                  java.util.List<Column> columns) {
        Set<String> hidden = new java.util.LinkedHashSet<>();
        for (Column column : columns) {
            if (!effectivePermissions.contains(column.required())) {
                hidden.add(column.name());
            }
        }
        return new FieldRedactionPolicy(hidden);
    }
}