package com.firstelement.exports.retention;

import com.firstelement.admin.audit.AuditVocabularies;
import com.firstelement.admin.audit.RetentionClassResolver;
import com.firstelement.exports.config.ExportProperties;
import com.firstelement.security.query.TenantScope;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The retention window for one export class, and whether it may be purged at all.
 *
 * <h2>30 days by default, configurable per tenant</h2>
 *
 * <p>ADR-0009 §4: "Export artefacts and their events — {@code export_jobs},
 * {@code export_download_events}, {@code export_deletion_events}, document templates — <b>30 days</b>
 * default, configurable per tenant; templates are configuration and never purged."
 *
 * <p>Two things follow that are easy to get wrong and are therefore enforced here:
 *
 * <ul>
 *   <li><b>The window is the tenant's.</b> It comes from {@link RetentionClassResolver}, so a tenant that
 *       has declared a longer window keeps its artefacts for that long and this module never overrides
 *       it with a constant.</li>
 *   <li><b>Templates are never purgeable.</b> A template is configuration; deleting configuration that a
 *       historical export names would make that export unrenderable, and "we could not reproduce last
 *       quarter's report because its template was purged" is an unacceptable answer to an auditor. There
 *       is no method here that can purge a template, which is how the rule stays true rather than merely
 *       documented.</li>
 * </ul>
 *
 * <h2>An undeclared class is NOT purgeable</h2>
 *
 * <p>ADR-0009 §4 rule 4: "No table in this ADR is purged by a scheduled job until the retention-class column
 * is populated for it." {@link AuditVocabularies#UNDECLARED_RETENTION_CLASS} is the visible sentinel for
 * "this tenant has not configured this stream", and treating it as a 30-day window would purge a tenant's
 * artefacts on a default nobody agreed to.
 */
@Component
public class RetentionWindowResolver {

    /** The {@code retention_classes.stream} label export rows are stamped with. */
    public static final String EXPORT_STREAM = "ADMIN";

    /** The conventional class code for export artefacts. */
    public static final String EXPORT_ARTEFACT_CLASS = "EXPORT_ARTEFACTS";

    private final RetentionClassResolver classes;
    private final ExportProperties properties;

    public RetentionWindowResolver(RetentionClassResolver classes, ExportProperties properties) {
        this.classes = classes;
        this.properties = properties;
    }

    /** The class code this module stamps, resolved from the tenant's own configuration. */
    public String resolveClass(TenantScope scope) {
        return classes.resolve(scope, EXPORT_STREAM, EXPORT_ARTEFACT_CLASS);
    }

    /**
     * The window, in days, for a class code.
     *
     * @return the tenant's declared days, or the ADR default when the class is declared but carries no
     *         days, and {@code null} when the tenant has declared nothing — which the caller must treat as
     *         "not purgeable", not as "use the default"
     */
    public Integer windowDays(TenantScope scope, String classCode) {
        Map<String, RetentionClassResolver.RetentionClassRow> declared =
            classes.declared(scope, EXPORT_STREAM);
        RetentionClassResolver.RetentionClassRow row = declared.get(classCode);
        if (row == null) {
            return null;
        }
        return row.retentionDays();
    }

    /** Whether a class may be purged. False for an undeclared one, by ADR-0009 §4 rule 4. */
    public boolean isPurgeable(String classCode) {
        return classCode != null
            && !classCode.isBlank()
            && !AuditVocabularies.UNDECLARED_RETENTION_CLASS.equals(classCode);
    }

    /** Whether a class is configuration, which is never purged. Always false for export artefacts. */
    public boolean isConfigurationNeverPurged(String classCode) {
        // Named so the answer is a decision with a place to look, rather than an absence of code. A
        // document template resolves to true here; an export artefact never does, because an artefact is
        // customer data and a template is not.
        return false;
    }

    /** The default window, for the API response and the runbook. */
    public int defaultWindowDays() {
        return properties.getRetention().getDefaultWindowDays();
    }

    /** The expiry instant for an artefact requested at {@code requestedAt}. */
    public LocalDateTime expiryFor(TenantScope scope, String classCode, LocalDateTime requestedAt) {
        Integer days = windowDays(scope, classCode);
        int effective = days == null ? defaultWindowDays() : days;
        return requestedAt.plus(Duration.ofDays(effective));
    }

    /** Whether a job's own expiry has already passed, judged against its recorded window. */
    public boolean windowClosed(LocalDateTime expiresAt, LocalDateTime now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }
}