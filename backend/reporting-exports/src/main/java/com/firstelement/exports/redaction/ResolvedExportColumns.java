package com.firstelement.exports.redaction;

import com.firstelement.exports.catalog.ExportRedactionPolicy;
import com.firstelement.exports.catalog.ReportField;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The outcome of field-level authorization for one caller against one report version.
 *
 * @param emitted   the fields written to the artefact, in the version's declared order. This is the
 *                  COLUMN SET of the file: a withheld field has no column at all.
 * @param withheld  the fields that were dropped, each with the reason, so an operator can answer
 *                  "what did this export hide and why" from the job row rather than by reading bytes
 * @param policy    the tenant's declared policy, recorded on the job
 * @param requiredPermissions the version's {@code requiredPermissions}, recorded on the job so the
 *                  export states what the author demanded of a caller
 */
public record ResolvedExportColumns(List<ReportField> emitted,
                                     List<WithheldColumn> withheld,
                                     ExportRedactionPolicy policy,
                                     List<String> requiredPermissions) {

    public ResolvedExportColumns {
        emitted = List.copyOf(emitted);
        withheld = List.copyOf(withheld);
        requiredPermissions = List.copyOf(requiredPermissions);
    }

    /** Why a field is absent from the artefact. */
    public enum WithholdingReason {
        /**
         * The caller does not hold the field's required permission.
         *
         * <p>Recorded rather than swallowed, because "the employee export has no revenue column" is a
         * fact somebody will otherwise assume is a bug.
         */
        MISSING_PERMISSION,

        /**
         * The tenant's declared redaction policy withholds it regardless of permission.
         */
        POLICY,

        /**
         * The field is in {@code FieldRedactionPolicy.NEVER_EXPOSED}.
         *
         * <p>Unreachable in practice — no declared field is a credential — and kept so the reason set
         * stays honest if one ever is. A credential reaching an export column is a defect that this
         * reason makes visible rather than a silent omission.
         */
        NEVER_EXPOSED
    }

    /**
     * One absent field and the reason it is absent.
     *
     * @param field          the field, so the report names what was dropped
     * @param reason         why
     * @param requiredPermission the permission that would have admitted it, or the wire value of
     *                          {@code "*"} for a policy withholding
     */
    public record WithheldColumn(ReportField field, WithholdingReason reason,
                                 String requiredPermission) {
    }

    /** The emitted column keys, which is what {@code export_jobs."columns"} stores. */
    public Set<String> emittedKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (ReportField field : emitted) {
            keys.add(field.key());
        }
        return Collections.unmodifiableSet(keys);
    }

    /** The withheld column keys. Recorded in the job's authorization snapshot. */
    public Set<String> withheldKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (WithheldColumn column : withheld) {
            keys.add(column.field.key());
        }
        return Collections.unmodifiableSet(keys);
    }

    /** Whether anything was withheld. Reported, so "nothing was redacted" is a stated fact. */
    public boolean redactedAnything() {
        return !withheld.isEmpty();
    }

    /**
     * Whether the caller still holds every permission the emitted fields require.
     *
     * <p>This is the re-authorization predicate used at <em>download</em>. It is deliberately not the
     * same question as {@link #redactedAnything()}: a download is refused when a permission the
     * produced artefact depends on has been revoked, rather than the artefact being silently
     * re-redacted — because the object's checksum is evidence of what was produced, and serving a
     * different byte sequence under the same checksum would make that evidence a lie.
     */
    public boolean satisfiedBy(Set<com.firstelement.security.permission.Permission> effectivePermissions) {
        return firstUnmetField(effectivePermissions) == null;
    }

    /** Whether this column set carries money, and therefore drags the finance policy with it. */
    public boolean carriesMoney() {
        return emitted.stream().anyMatch(ReportField::isMoney);
    }

    /**
     * The subject every emitted field belongs to.
     *
     * <p>Requires a non-empty emission; {@link #hasNothingToEmit()} is the guard, because "the subject of
     * nothing" is not a subject.
     */
    public com.firstelement.exports.catalog.ReportSubject subject() {
        if (emitted.isEmpty()) {
            throw new IllegalStateException(
                "An empty column set has no subject. Check hasNothingToEmit() before asking.");
        }
        return emitted.get(0).subject();
    }

    /**
     * Whether a resolution leaves nothing to write.
     *
     * <p>Every column withheld cannot produce an artefact that says anything, and a file whose header row
     * is empty cannot be told apart from a genuinely empty report. The caller refuses instead, and the
     * refusal names the policy that emptied it.
     */
    public boolean hasNothingToEmit() {
        return emitted.isEmpty();
    }

    /**
     * The first emitted field whose permission is missing from {@code effectivePermissions}, or
     * {@code null} when the download is still authorized.
     *
     * <p>The field is returned rather than a boolean so the download event can say <em>which</em>
     * permission lapsed, which is the only version of this refusal an operator can act on.
     */
    public ReportField firstUnmetField(Set<com.firstelement.security.permission.Permission> effectivePermissions) {
        for (ReportField field : emitted) {
            if (!effectivePermissions.contains(field.requiredPermission())) {
                return field;
            }
        }
        return null;
    }
}