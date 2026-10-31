package com.firstelement.admin.maintenance;

import java.util.List;
import java.util.Map;

/**
 * What a maintenance command did, and everything an operator needs to show a reviewer afterwards.
 *
 * <h2>The shape is the same for every command</h2>
 *
 * <p>PROJECT_REQUIREMENTS §3.1 requires each dangerous action to carry "confirmation, dry-run,
 * impact counts, authorization checks, and rollback or recovery instructions". One result record for
 * every command means the UI does not have to know which shape each one returns, and it means a
 * refusal is a first-class answer with the same fields as a success — which is what lets a test
 * assert on <em>state</em> after a refusal instead of on a message (ADR-0009 §5).
 *
 * @param applied      {@code false} for a refusal. Never omitted
 * @param outcome      a stable token: the machine-readable answer
 * @param scope        the tenant and optional project the command acted on, echoed back so an audit
 *                     review does not have to reconstruct it
 * @param impact       free-form impact numbers, always present (possibly empty) so "nothing to
 *                     affect" is distinguishable from "not measured"
 * @param message      the operator-facing sentence. A claim, not evidence; the state is the evidence
 * @param recovery     what to do next if this went wrong. Present on every non-trivial outcome
 */
public record MaintenanceOutcome(boolean applied,
                                 String command,
                                 String outcome,
                                 Scope scope,
                                 Map<String, Object> impact,
                                 String message,
                                 String recovery,
                                 List<String> auditRefusals) {

    /** The scope a command acted on. */
    public record Scope(java.util.UUID organizationId,
                        java.util.UUID projectId,
                        java.util.UUID resourceId,
                        String resourceType) {
    }

    public static MaintenanceOutcome applied(MaintenanceCommand command,
                                             Scope scope,
                                             String outcome,
                                             Map<String, Object> impact,
                                             String message,
                                             String recovery) {
        return new MaintenanceOutcome(true, command.wireValue(), outcome, scope, impact, message,
            recovery, List.of());
    }

    /** A refusal. The audit rows that recorded it are named, so a caller can find them. */
    public static MaintenanceOutcome refused(MaintenanceCommand command,
                                             Scope scope,
                                             String outcome,
                                             Map<String, Object> impact,
                                             String message,
                                             String recovery,
                                             List<String> auditRefusals) {
        return new MaintenanceOutcome(false, command.wireValue(), outcome, scope, impact, message,
            recovery, List.copyOf(auditRefusals));
    }
}