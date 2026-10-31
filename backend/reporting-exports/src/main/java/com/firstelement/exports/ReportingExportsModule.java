package com.firstelement.exports;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Wires {@code backend/reporting-exports}.
 *
 * <h2>Why an explicit module configuration</h2>
 *
 * <p>Like every other {@code :backend} module: an {@code @AutoConfiguration} plus a
 * {@code @ComponentScan} of its own package, so a test application that scans
 * {@code com.firstelement.exports} alone still gets a wired graph. There is deliberately no
 * {@code @Bean} that constructs a service: the services, repositories and the production object
 * store are annotated and discovered, so a second way to build them would be a second set of wiring
 * that can disagree with a deployment.
 *
 * <h2>What this module writes, in full</h2>
 *
 * <ol>
 *   <li><b>{@code report_definitions} / {@code report_versions}</b> — a definition is mutable so a
 *       DRAFT can be renamed; a version body is written once and never updated (the schema has no
 *       {@code updatedAt} on it and its post-condition guard fails the migration if one appears).;</li>
 *   <li><b>{@code document_templates} / {@code document_template_versions}</b> — same shape, and a
 *       template version is configuration: ADR 0009 §4 classes it <b>never purge</b>;</li>
 *   <li><b>{@code export_jobs}</b> — the mutable state machine, including the tombstone that a
 *       deletion leaves behind;</li>
 *   <li><b>{@code export_job_attempts}, {@code export_download_events},
 *       {@code export_deletion_events}</b> — append-only, never updated.</li>
 * </ol>
 *
 * <p>And nothing else. No DDL, no {@code usage_events} row (the finance ledger is owned by
 * {@code :backend:reporting-finance} and an export emits its usage through that module's factory), no
 * {@code legal_holds} row, and no {@code admin_audit_events} row written by a statement of its own —
 * every operator-visible action goes through {@code AdminAuditStreamWriter}.
 *
 * <h2>What this module structurally cannot do</h2>
 *
 * <ul>
 *   <li><b>No SQL from a caller.</b> No method accepts a table name, a column name, a predicate
 *       fragment, a sort expression, or a {@code WHERE} clause. A report definition is a list of keys
 *       into {@link com.firstelement.exports.catalog.ReportFieldRegistry}; the statement is assembled
 *       from enum-constant fragments, and a key the registry does not declare is a refusal. That is
 *       the difference between "a versioned metadata registry" and "an arbitrary query surface",
 *       and it is enforced by the types rather than by review.</li>
 *   <li><b>No placeholder rendering of an unapproved field.</b> A DOCX template that references a
 *       field outside the report version's approved column set fails the job. It never emits an empty
 *       cell, because an empty cell reads as "no value" and a redacted field must read as absent.</li>
 *   <li><b>No generation on a request thread.</b> The only entry point that produces bytes is
 *       {@link com.firstelement.exports.job.ExportGenerationRunner}, reached from
 *       {@link com.firstelement.exports.job.ExportJobService#generate} through the durable
 *       {@link com.firstelement.exports.job.ExportJobHandler}. A controller can create, inspect,
 *       cancel and mint a link; it cannot produce an artefact.</li>
 *   <li><b>No purge enabled.</b> {@link com.firstelement.exports.retention.ExportRetentionService}
 *       exposes the question and answers it with a recorded refusal until an operator turns the flag
 *       on after legal-hold state and a declared retention class exist (ADR-0008 §2 rule 5,
 *       ADR-0009 §4 rules 3 and 4).</li>
 * </ul>
 */
@AutoConfiguration
@ComponentScan(basePackages = "com.firstelement.exports")
public class ReportingExportsModule {

    /**
     * The bounded sizes, collected so a deployment has one place to see them.
     *
     * <p>Every list this module returns is bounded, and the export row cap is a property rather than a
     * constant because the right number depends on the deployment's memory budget: an export that
     * streams can be large, and an export that buffers is the thing that takes the process down.
     */
    public static final class Limits {

        private Limits() {
        }

        /** Export jobs per page. */
        public static final int EXPORT_JOB_PAGE = 100;

        /** Attempts, downloads or deletions per page. */
        public static final int EXPORT_EVENT_PAGE = 200;

        /** Report definitions and document templates per page. Small, fixed-size catalogues. */
        public static final int CATALOGUE_PAGE = 100;

        /** Versions of one definition or template per page. */
        public static final int VERSION_PAGE = 50;

        /**
         * Rows written between two cancellation checks.
         *
         * <p>Not a tuning knob: it is the granularity at which a cancellation is observed, and a
         * cancellation that a running export only notices when it finishes is not a cancellation.
         */
        public static final int CANCELLATION_POLL_ROWS = 2000;

        /** Finance periods consulted for one export's date window. */
        public static final int FINANCE_PERIOD_SCAN = 64;
    }
}