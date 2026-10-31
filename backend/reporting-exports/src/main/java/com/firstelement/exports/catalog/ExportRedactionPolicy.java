package com.firstelement.exports.catalog;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The tenant-declared field policy a report version names in {@code report_definitions."redactionPolicy"}.
 *
 * <h2>Three gates, and which one is which</h2>
 *
 * <ol>
 *   <li><b>The permission gate</b> — {@link ReportField#requiredPermission()} — is not configurable and
 *       is evaluated for every export. It is why an {@code EMPLOYEE}'s usage export contains no money
 *       columns at all.</li>
 *   <li><b>The policy gate</b> — this enum — is the tenant's and report author's declared floor. It
 *       withholds fields <em>regardless</em> of permission, which is the "we never export phone
 *       numbers" decision expressed as data rather than as a hard-coded omission in a query.</li>
 *   <li><b>{@link com.firstelement.security.redaction.FieldRedactionPolicy#NEVER_EXPOSED}</b> — credentials
 *       and secrets — is applied unconditionally in
 *       {@link com.firstelement.exports.redaction.ExportRedactionResolver} and cannot be configured
 *       away, for any principal including an administrator.</li>
 * </ol>
 *
 * <p>All three end in the same place: the field is <strong>absent</strong> from the artefact. Nothing
 * writes a placeholder, an empty-looking cell, or a masked string, because a blank cell reads as "no
 * value" and the whole point is that a reader can tell the difference. See
 * {@code docs/migration/export-redaction-policy.md}.
 *
 * <h2>The policy name is stored on the job, so the decision is answerable later</h2>
 *
 * <p>{@code export_jobs."redactionPolicy"} carries the name and
 * {@code export_jobs."authorizationSnapshot"} carries the field-level outcome — which keys were
 * emitted and which were withheld, with the reason. "What was redacted from this export, and why" is
 * therefore answerable from the job row months later, without re-running anything and without reading
 * the artefact.
 */
public enum ExportRedactionPolicy {

    /**
     * Only the permission gate and the never-exposed list apply.
     *
     * <p>The honest default for an internal report over aggregate figures. It is still a real gate: an
     * {@code EMPLOYEE} still sees no money columns, because that is the permission gate and not the
     * policy.
     */
    NONE("none", Set.of()),

    /**
     * Withholds contact detail whatever the caller's permissions.
     *
     * <p>{@code customer.primaryEmail} and {@code customer.primaryPhone}. The common case for a report
     * shared with someone who legitimately holds {@code customer:read} but should not receive a
     * contact list.
     */
    NO_CONTACT_DETAILS("no-contact-details",
        Set.of("customer.primaryEmail", "customer.primaryPhone")),

    /**
     * Withholds personal detail beyond identity and conversation shape.
     *
     * <p>Everything {@link #NO_CONTACT_DETAILS} withholds, plus the conversation timestamps that
     * describe when a named person was on a channel. Suitable for a report that leaves the tenant's
     * own tooling.
     */
    NO_PERSONAL_DETAIL("no-personal-detail",
        Set.of("customer.primaryEmail", "customer.primaryPhone",
            "customer.firstSeenAt", "customer.lastSeenAt",
            "conversation.firstResponseAt", "conversation.resolvedAt")),

    /**
     * Everything money plus everything personal.
     *
     * <p>The strictest declared policy: no {@code usage.cost}, {@code usage.revenue},
     * {@code usage.margin}, no markup rate and no rate-card version, in addition to
     * {@link #NO_PERSONAL_DETAIL}. Used for a report handed to somebody outside the finance function.
     */
    STRICT("strict",
        Set.of("customer.primaryEmail", "customer.primaryPhone",
            "customer.firstSeenAt", "customer.lastSeenAt",
            "conversation.firstResponseAt", "conversation.resolvedAt",
            "usage.cost", "usage.revenue", "usage.margin",
            "usage.markupRate", "usage.rateCardVersionId"));

    private final String wireValue;
    private final Set<String> withheldKeys;

    ExportRedactionPolicy(String wireValue, Set<String> withheldKeys) {
        this.wireValue = wireValue;
        this.withheldKeys = Set.copyOf(new LinkedHashSet<>(withheldKeys));
    }

    /** The value stored in {@code report_definitions."redactionPolicy"} and on the job. */
    public String wireValue() {
        return wireValue;
    }

    /** The field keys this policy withholds unconditionally. */
    public Set<String> withheldKeys() {
        return withheldKeys;
    }

    /** The policy a stored name names, or empty. An undeclared name is a refusal at authoring time. */
    public static Optional<ExportRedactionPolicy> fromWireValue(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (ExportRedactionPolicy policy : values()) {
            if (policy.wireValue.equals(normalized)) {
                return Optional.of(policy);
            }
        }
        return Optional.empty();
    }

    /**
     * The declared policy names, for the authoring surface.
     *
     * <p>A closed list on purpose: {@code report_definitions_redaction_policy_check} requires a
     * non-blank string, and this enum is what makes that string mean something rather than being
     * whatever an author typed.
     */
    public static List<String> declared() {
        return java.util.Arrays.stream(values()).map(ExportRedactionPolicy::wireValue).toList();
    }
}