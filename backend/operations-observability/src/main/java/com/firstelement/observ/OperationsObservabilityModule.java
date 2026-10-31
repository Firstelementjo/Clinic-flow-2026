package com.firstelement.observ;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Wires {@code backend/operations-observability}.
 *
 * <h2>What this module is for</h2>
 *
 * <p>{@code :backend:administration} answers "is the system controlled and secure?"; this one answers
 * "is it operating?". The split is along the read/write line rather than along the domain line:
 * administration owns everything that <em>changes</em> state and every audit trail, and this module
 * owns the diagnosis read models that sit next to a change without making one.
 *
 * <p>Concretely, this module is what lets an operator answer the acceptance question without a
 * database console: <em>"which message failed, why, and is replaying it safe?"</em>
 *
 * <h2>What it reuses rather than re-derives</h2>
 *
 * <ul>
 *   <li>Outbox, attempt and dead-letter reads come from {@code :backend:worker-runtime}'s
 *       repositories, whose statements are transcriptions of
 *       {@code ops/flyway/reference/outbox-claim.sql}. A second copy would be a second statement that
 *       could drift off the index the query depends on.</li>
 *   <li>Connector state comes from {@code :backend:channels}' {@code ChannelConnectionRepository},
 *       whose admin projection already selects no credential column.</li>
 *   <li>Realtime counters come from {@code RealtimeStreamService.health()}, so the Admin panel and
 *       {@code /api/v1/realtime/state} cannot disagree.</li>
 *   <li>Workflow history comes from {@code :backend:workflows-sla-routing}'s repositories, which own
 *       the canonical statements from {@code ops/flyway/reference/workflow-sla-statements.sql}.</li>
 *   <li>Replay, dead-letter resolution, legal holds and deletion requests go through
 *       {@code :backend:administration}'s {@code MaintenanceCommandService}, so there is one
 *       allow-list and one audit writer.</li>
 * </ul>
 */
@AutoConfiguration
@ComponentScan(basePackages = "com.firstelement.observ")
public class OperationsObservabilityModule {

    /**
     * The bounded page sizes.
     *
     * <p>Every list here is bounded. An operator panel that runs {@code select *} against a tenant
     * with a million attempts is the outage it was built to diagnose.
     */
    public static final class Limits {

        private Limits() {
        }

        /** Attempts for one job identity: twelve plus a few replays, bounded anyway. */
        public static final int ATTEMPTS_PER_JOB = 50;

        /** The integration-stream rows for one job. */
        public static final int JOB_EVENTS = 100;

        /** The dead-letter worklist. */
        public static final int DEAD_LETTERS = 100;

        /** Connector / webhook health rows. */
        public static final int CONNECTORS = 100;

        /** Documents in the freshness read model. */
        public static final int DOCUMENTS = 200;

        /** Knowledge gaps. */
        public static final int KNOWLEDGE_GAPS = 100;

        /** Workflow runs and definitions. */
        public static final int WORKFLOWS = 100;
    }
}