package com.firstelement.admin;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Wires {@code backend/administration}.
 *
 * <h2>Why an explicit module configuration</h2>
 *
 * <p>Like every other {@code :backend} module this is an {@code @AutoConfiguration} plus a
 * {@code @ComponentScan} of its own package, so a test application that scans
 * {@code com.firstelement.admin} alone still gets a wired graph. There is deliberately no
 * {@code @Bean} here: the services and repositories are annotated and discovered, so a second way
 * to construct them would be a second set of wiring that can disagree.
 *
 * <h2>What this module is allowed to write</h2>
 *
 * <p>Exactly three things, and the list is the point:
 *
 * <ol>
 *   <li>an append-only row in {@code admin_audit_events} for every Admin action;</li>
 *   <li>the mutable {@code legal_holds} row and its append-only {@code legal_hold_events} history,
 *       because placing and releasing a hold is a governance act an operator performs;</li>
 *   <li>the {@code privacy_deletion_requests} / {@code privacy_deletion_targets} rows a typed
 *       deletion request produces, including the per-target decision.</li>
 * </ol>
 *
 * <p>No other table in the whole system is mutated from here. There is no statement in this module
 * that accepts a table name, a column name, or a predicate fragment from a caller, and there is no
 * route that accepts SQL or a free-form row. PROJECT_REQUIREMENTS §3.1 and §8 forbid exactly that,
 * and the absence is structural rather than documented: a caller cannot express one.
 *
 * <h2>What this module never does</h2>
 *
 * <ul>
 *   <li><b>No purge.</b> {@link com.firstelement.admin.governance.LegalHoldGate} answers whether a
 *       tenant may be purged, and the purge itself is a scheduled job that ships disabled
 *       (ADR-0008 §2 rule 5, ADR-0009 §4 rule 3). Exposing the question is what this phase owes;
 *       performing the deletion is a later, separately-approved step.</li>
 *   <li><b>No secret read.</b> No projection in this module selects {@code passwordHash},
 *       {@code tokenHash}, {@code credentials} or {@code webhookSecret}. Credential <em>presence</em>
 *       is a boolean computed by the database, and nothing else.</li>
 * </ul>
 */
@AutoConfiguration
@ComponentScan(basePackages = "com.firstelement.admin")
public class AdministrationModule {

    /**
     * The bounded page sizes, collected so a deployment has one place to see them.
     *
     * <p>Every list this module returns is bounded. An unbounded {@code select} against a tenant
     * with a million audit rows is how an Admin page becomes the outage it was built to diagnose.
     */
    public static final class Limits {

        private Limits() {
        }

        /** One audit/security/job-integration page. */
        public static final int AUDIT_PAGE = 100;

        /** Retention classes, legal holds, deletion requests: small, fixed-size catalogues. */
        public static final int GOVERNANCE_PAGE = 100;

        /** AI policies / models / tools / budgets. */
        public static final int AI_PAGE = 100;

        /** Blocked tool executions and redaction records. */
        public static final int AI_EVENT_PAGE = 100;

        /** Export jobs and their download/deletion events. */
        public static final int EXPORT_PAGE = 100;

        /** Users, memberships, sessions in the directory read model. */
        public static final int DIRECTORY_PAGE = 200;

        /** The migration chain. Eighteen entries today; the ceiling is a guard, not a target. */
        public static final int MIGRATION_PAGE = 200;

        /** Cap on how many targets one privacy-deletion request may name. */
        public static final int DELETION_TARGET_CAP = 500;
    }
}