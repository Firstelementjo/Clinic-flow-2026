package com.firstelement.automation;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Wires {@code backend/workflows-sla-routing}.
 *
 * <h2>Why an explicit module configuration</h2>
 *
 * <p>Every other {@code :backend} module relies on {@code @SpringBootApplication(scanBasePackages =
 * "com.firstelement")} in the deployable, and its tests reuse the same pattern. This module does
 * both of those things too, but it also declares the beans it needs when it is scanned on its own, so
 * a test application that scans {@code com.firstelement.automation} alone still gets a wired
 * component graph. There is no {@code @Bean} here: the services and repositories are annotated and
 * discovered, so a second way to construct them would be a second set of wiring that can disagree.
 *
 * <h2>What this module deliberately does not contribute</h2>
 *
 * <ul>
 *   <li><b>No controllers.</b> {@code tests/contracts/openapi-paths.json} is a closed contract owned by
 *       the quality agent, and the admin surface that needs these endpoints is Phase 7's. An HTTP
 *       endpoint added here would either fail the contract gate or have to be removed in Phase 7.</li>
 *   <li><b>No dispatcher loop.</b> A {@code JobHandler} would be drained by
 *       {@code :backend:worker-runtime}'s own loop; this module's jobs are consumed in Phase 7 when the
 *       admin replay surface exists. {@link com.firstelement.automation.service.WorkflowRunService#execute}
 *       is the supported entry point meanwhile, and it binds the run's own tenant.</li>
 *   <li><b>No DDL.</b> The schema comes from the packaged Flyway chain in {@code :backend:database};
 *       this module reads it and never creates or alters anything.</li>
 * </ul>
 */
@AutoConfiguration
@ComponentScan(basePackages = "com.firstelement.automation")
public class WorkflowSlaRoutingModule {

    /**
     * The bounded batch sizes, collected so a deployment has one place to see them.
     *
     * <p>Every list this module reads is bounded: a roster, a rule set, a policy set, a dry-run
     * preview, a transcript, an import. "Bounded batches" is a hard rule from the Phase-6 task file,
     * and an unbounded {@code select} from a tenant with 200k conversations is how a scheduler dies
     * quietly.
     */
    public static final class Limits {

        private Limits() {
        }

        public static final int CANDIDATES = 500;
        public static final int ROUTING_RULES = 100;
        public static final int SLA_POLICIES = 100;
        public static final int ESCALATION_RULES = 200;
        public static final int DRY_RUN = 200;
        public static final int TRANSCRIPT = 12;
        public static final int HISTORY = 50;
        public static final int EXPLANATION_ROWS = 500;
        public static final int RUN_HISTORY_PAGE = 100;
    }
}