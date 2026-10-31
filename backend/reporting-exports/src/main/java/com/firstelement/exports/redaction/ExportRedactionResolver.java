package com.firstelement.exports.redaction;

import com.firstelement.exports.catalog.ExportRedactionPolicy;
import com.firstelement.exports.catalog.ReportField;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.redaction.FieldRedactionPolicy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides which columns of a report version a caller may receive.
 *
 * <h2>Redaction here is ABSENCE, not substitution</h2>
 *
 * <p>{@link FieldRedactor} in {@code :backend:tenant-security} offers both
 * {@link FieldRedactor#redact} (substitute a constant placeholder) and
 * {@link FieldRedactor#project} (drop the column). This module uses <strong>only</strong> the dropping
 * form, and the reason is specific to exports rather than a general preference:
 *
 * <ul>
 *   <li>A CSV cell and a DOCX cell are both <em>present</em>. A blank cell reads as "this customer has
 *       no email"; a redacted cell has to read as "this report does not carry emails", and the only way
 *       to say that is for the column not to exist.</li>
 *   <li>An empty-looking cell is indistinguishable from a genuine null, which for {@code usage.revenue}
 *       would be indistinguishable from {@code UNPRICED} — the exact collapse ADR-0007 rule 5
 *       forbids.</li>
 *   <li>A placeholder is a value. Any constant in every export is information about how many columns
 *       were hidden, and a report that varies its placeholder by policy is a covert channel.</li>
 * </ul>
 *
 * <p>Where a reader needs to know that something was removed, the answer is on the job row
 * ({@code export_jobs."columns"} and {@code "authorizationSnapshot"}), not in the bytes. The artefact
 * answers "what am I allowed to see"; the job answers "what did this export hide".
 *
 * <h2>The three gates, in evaluation order</h2>
 *
 * <ol>
 *   <li>{@link FieldRedactionPolicy#NEVER_EXPOSED} — unconditional, for every principal;</li>
 *   <li>the tenant's declared {@link ExportRedactionPolicy} — a floor, regardless of permission;</li>
 *   <li>the field's {@link ReportField#requiredPermission()} — the caller's effective permission set.</li>
 * </ol>
 *
 * <p>Order matters only for the recorded reason, which is why a field that is both credential-class and
 * permission-gated reports {@code NEVER_EXPOSED}.
 */
public final class ExportRedactionResolver {

    private ExportRedactionResolver() {
    }

    /**
     * Resolves the emitted column set.
     *
     * @param declaredFields       the report version's declared columns, in its declared order
     * @param policy               the tenant's declared redaction policy
     * @param requiredPermissions  the version's {@code requiredPermissions}; recorded on the result so
     *                              the job states what the author demanded
     * @param effectivePermissions the caller's effective permissions, as
     *                              {@code AuthorizationService.effectivePermissions} reports them
     */
    public static ResolvedExportColumns resolve(List<ReportField> declaredFields,
                                                ExportRedactionPolicy policy,
                                                List<String> requiredPermissions,
                                                Set<Permission> effectivePermissions) {
        if (declaredFields == null || declaredFields.isEmpty()) {
            throw new IllegalArgumentException(
                "Field resolution needs at least one declared column; a version with none exports "
                    + "nothing and is not a report.");
        }
        ExportRedactionPolicy effectivePolicy = policy == null ? ExportRedactionPolicy.NONE : policy;
        Set<Permission> granted = effectivePermissions == null ? Set.of() : effectivePermissions;

        List<ReportField> emitted = new ArrayList<>(declaredFields.size());
        List<ResolvedExportColumns.WithheldColumn> withheld =
            new ArrayList<>(declaredFields.size());

        for (ReportField field : declaredFields) {
            ResolvedExportColumns.WithholdingReason reason = reasonFor(field, effectivePolicy, granted);
            if (reason == null) {
                emitted.add(field);
            } else {
                withheld.add(new ResolvedExportColumns.WithheldColumn(field, reason,
                    field.requiredPermission().value()));
            }
        }

        return new ResolvedExportColumns(emitted, withheld, effectivePolicy,
            requiredPermissions == null ? List.of() : requiredPermissions);
    }

    /** Convenience for the many call sites that resolve against {@link FieldRedactionPolicy#none()}. */
    public static ResolvedExportColumns resolve(List<ReportField> declaredFields,
                                                List<String> requiredPermissions,
                                                Set<Permission> effectivePermissions) {
        return resolve(declaredFields, ExportRedactionPolicy.NONE, requiredPermissions,
            effectivePermissions);
    }

    private static ResolvedExportColumns.WithholdingReason reasonFor(ReportField field,
                                                                      ExportRedactionPolicy policy,
                                                                      Set<Permission> granted) {
        if (FieldRedactionPolicy.NEVER_EXPOSED.contains(field.key())) {
            return ResolvedExportColumns.WithholdingReason.NEVER_EXPOSED;
        }
        if (policy.withheldKeys().contains(field.key())) {
            return ResolvedExportColumns.WithholdingReason.POLICY;
        }
        if (!granted.contains(field.requiredPermission())) {
            return ResolvedExportColumns.WithholdingReason.MISSING_PERMISSION;
        }
        return null;
    }

    /**
     * Whether a resolution leaves at least one column.
     *
     * <p>A version whose every column is withheld cannot produce an artefact that says anything, and
     * writing a file whose header row is empty is a worse outcome than refusing: a reader cannot tell
     * it apart from a genuinely empty report. The caller refuses the export instead, and the refusal
     * names the policy that emptied it.
     */
    public static boolean hasNothingToEmit(ResolvedExportColumns resolved) {
        return resolved.emitted().isEmpty();
    }

    /**
     * The reason recorded on a download event when the caller no longer holds a permission the produced
     * artefact depends on.
     *
     * <p>Names the field and the permission but never a tenant, a project, or anything about the withheld
     * value — a download audit is read by operators, and the whole point of the refusal is that the value
     * never left.
     */
    public static String downloadDenialReason(ReportField unmetField) {
        return denialReasonCode() + ": field '" + unmetField.key() + "' requires '"
            + unmetField.requiredPermission().value() + "'";
    }

    /**
     * The stable reason label for a revocation denial, without the field detail.
     *
     * <p>Groupable by an operator: every revocation reads {@code authorization-revoked} in the summary and
     * the field detail is one click away.
     */
    public static String denialReasonCode() {
        return "authorization-revoked";
    }

    /**
     * The declared column list a resolution was computed from, emitted plus withheld.
     *
     * <p>Used by the job to store {@code export_jobs."columns"}: the EMITTED set, so a re-run is
     * byte-comparable, and the authorization snapshot carries the withheld set separately.
     */
    public static Set<String> declaredKeys(List<ReportField> declaredFields) {
        Set<String> keys = new LinkedHashSet<>();
        for (ReportField field : declaredFields) {
            keys.add(field.key());
        }
        return java.util.Collections.unmodifiableSet(keys);
    }
}