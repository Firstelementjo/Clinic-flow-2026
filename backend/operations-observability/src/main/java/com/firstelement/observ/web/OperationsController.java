package com.firstelement.observ.web;

import com.firstelement.admin.maintenance.MaintenanceOutcome;
import com.firstelement.observ.connectors.ConnectorHealthService;
import com.firstelement.observ.jobs.JobDiagnosisRepository;
import com.firstelement.observ.jobs.JobOperationsService;
import com.firstelement.observ.search.SearchFreshnessService;
import com.firstelement.observ.workflow.WorkflowOperationsService;
import com.firstelement.security.TenantContextHolder;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.permission.Permission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/operations} — the operational read models and the two operational commands.
 *
 * <h2>FE-WEB-003, applied</h2>
 *
 * <p>Every route here names its permission at the HTTP edge. This module is the second half of that
 * decision: "every Admin <em>and operations-observability</em> route". The reasoning is in
 * {@code AdminReadModelController}'s class note and applies identically — an operational endpoint
 * whose only declaration is {@code authenticated()} is one nobody can review from the controller, and
 * operational endpoints are the ones an attacker reaches for because they look like read-only
 * telemetry.
 *
 * <h2>The permission chosen per route</h2>
 *
 * <table>
 *   <caption>Operations permissions</caption>
 *   <tr><th>Route</th><th>Permission</th><th>Why</th></tr>
 *   <tr><td>jobs, worklist, stranded-leases</td><td>SYSTEM_HEALTH_READ</td>
 *       <td>Read-only operational telemetry, ADMIN-only.</td></tr>
 *   <tr><td>jobs/{id}</td><td>SYSTEM_HEALTH_READ</td>
 *       <td>One event's diagnosis. Contains no payload and no attempt message, so it is the same
 *           exposure as the list.</td></tr>
 *   <tr><td>jobs/{id}/replay</td><td>SYSTEM_HEALTH_READ</td>
 *       <td>The recovery action on the delivery pipeline. See the note on
 *           {@code MaintenanceCommand} for why this is the existing operator capability rather than a
 *           new permission, and what that costs.</td></tr>
 *   <tr><td>dead-letters/{id}/resolve</td><td>SYSTEM_HEALTH_READ</td><td>As above.</td></tr>
 *   <tr><td>connectors, webhooks</td><td>CHANNEL_READ</td>
 *       <td>The connector panel, which the connector module already gates with this permission, so
 *           there is one declaration rather than two.</td></tr>
 *   <tr><td>search, gaps</td><td>KNOWLEDGE_READ</td>
 *       <td>Index freshness is knowledge-base state.</td></tr>
 *   <tr><td>realtime</td><td>SYSTEM_HEALTH_READ</td>
 *       <td>Process-level connection counters with no tenant data.</td></tr>
 *   <tr><td>workflows, workflow-failures</td><td>SYSTEM_HEALTH_READ</td>
 *       <td>See below.</td></tr>
 * </table>
 *
 * <p>Workflow reads therefore use {@link Permission#SYSTEM_HEALTH_READ}: an operational view of
 * automation health that changes nothing, held by {@code ADMIN} only. The catalogue has no
 * workflow-read permission and this phase may not widen one, so the follow-up recorded in the phase
 * report is a dedicated {@code WORKFLOW_READ} permission owned by {@code :backend:tenant-security},
 * which would let an operations reader see automation health without the workflow-authoring
 * capability.
 */
@RestController
@RequestMapping("/api/v1/operations")
public class OperationsController {

    private final JobOperationsService jobs;
    private final ConnectorHealthService connectors;
    private final SearchFreshnessService search;
    private final WorkflowOperationsService workflows;

    public OperationsController(JobOperationsService jobs,
                                ConnectorHealthService connectors,
                                SearchFreshnessService search,
                                WorkflowOperationsService workflows) {
        this.jobs = jobs;
        this.connectors = connectors;
        this.search = search;
        this.workflows = workflows;
    }

    // ------------------------------------------------------------------
    // jobs
    // ------------------------------------------------------------------

    /** {@code GET /api/v1/operations/jobs} */
    @GetMapping("/jobs")
    @RequirePermission(Permission.SYSTEM_HEALTH_READ)
    public JobOperationsService.QueueView jobs(
        @RequestParam(name = "projectId", required = false) UUID projectId,
        @RequestParam(name = "queue", required = false) String queue,
        @RequestParam(name = "windowHours", required = false) Integer windowHours) {
        return jobs.queues(TenantContextHolder.get(), projectId, queue, windowHours);
    }

    /** {@code GET /api/v1/operations/jobs/worklist} */
    @GetMapping("/jobs/worklist")
    @RequirePermission(Permission.SYSTEM_HEALTH_READ)
    public List<JobDiagnosisRepository.WorklistRow> worklist(
        @RequestParam(name = "projectId", required = false) UUID projectId,
        @RequestParam(name = "queue", required = false) String queue,
        @RequestParam(name = "limit", required = false) Integer limit) {
        return jobs.worklist(TenantContextHolder.get(), projectId, queue,
            limit == null ? com.firstelement.observ.OperationsObservabilityModule.Limits.DEAD_LETTERS
                : limit);
    }

    /** {@code GET /api/v1/operations/jobs/stranded-leases} */
    @GetMapping("/jobs/stranded-leases")
    @RequirePermission(Permission.SYSTEM_HEALTH_READ)
    public List<JobDiagnosisRepository.StrandedLease> strandedLeases(
        @RequestParam(name = "projectId", required = false) UUID projectId,
        @RequestParam(name = "limit", required = false) Integer limit) {
        return jobs.strandedLeases(TenantContextHolder.get(), projectId,
            limit == null ? com.firstelement.observ.OperationsObservabilityModule.Limits.DEAD_LETTERS
                : limit);
    }

    /**
     * {@code GET /api/v1/operations/jobs/{outboxEventId}}
     *
     * <p>The one call that answers "what happened to this message?" — the event, every attempt with
     * its retry class and error code, the dead-letter record, the integration stream for the same
     * correlation id, and whether a replay is safe. The outbox payload and the attempt
     * {@code errorMessage} are not projected, so this is a diagnosis rather than a transcript.
     */
    @GetMapping("/jobs/{outboxEventId}")
    @RequirePermission(Permission.SYSTEM_HEALTH_READ)
    public ResponseEntity<JobOperationsService.Diagnosis> diagnose(
        @PathVariable UUID outboxEventId) {
        return jobs.diagnose(TenantContextHolder.get(), outboxEventId)
            .map(ResponseEntity::ok)
            // A cross-tenant id and a nonexistent id are the same 404, so this cannot be used to
            // probe for ids in another tenant.
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * {@code POST /api/v1/operations/jobs/{outboxEventId}/replay}
     *
     * <p>ADR-0016 rule 2: an applied replay is work ACCEPTED and still outstanding. What this route
     * does is make a DEAD event claimable again — it moves the row to PENDING and returns. No worker
     * has run, no provider has been called, and the attempts are not reset. A 200 told a client the
     * replay had happened, which is the same failure {@code POST /api/v1/exports} had: work that is
     * outstanding published as finished, and the operator told so before anything had been delivered.
     *
     * <p><b>And a refused replay is not accepted work, so it is not 202.</b> ADR-0016 rule 3 forbids
     * declaring a code a route does not return, and the refusal branches — NOT_FOUND, NOT_DEAD,
     * NOT_REPLAY_SAFE — complete on this thread having written nothing. Answering them 202 would say
     * "your work is under way" about a request that was declined, which is the opposite error and the
     * more dangerous one: an operator would poll a job that will never run. So the status follows
     * the outcome, which is the only honest signal available.
     */
    @PostMapping("/jobs/{outboxEventId}/replay")
    @RequirePermission(Permission.SYSTEM_HEALTH_READ)
    public ResponseEntity<MaintenanceOutcome> replay(@PathVariable UUID outboxEventId) {
        MaintenanceOutcome outcome = jobs.replay(TenantContextHolder.get(), outboxEventId);
        return outcome.applied()
            ? ResponseEntity.accepted().body(outcome)
            : ResponseEntity.ok(outcome);
    }

    /** {@code POST /api/v1/operations/dead-letters/{deadLetterId}/resolve} */
    public record ResolveDeadLetterRequest(@NotBlank String status, @NotBlank String note) {
    }

    /**
     * {@code POST /api/v1/operations/dead-letters/{deadLetterId}/resolve}
     *
     * <p>{@code status} is {@code RESOLVED} or {@code DISCARDED} and nothing else; the service
     * refuses an unrecognised value before touching the row, so an unrecognised token cannot become
     * an enum literal the database rejects.
     */
    @PostMapping("/dead-letters/{deadLetterId}/resolve")
    @RequirePermission(Permission.SYSTEM_HEALTH_READ)
    public ResponseEntity<MaintenanceOutcome> resolveDeadLetter(
        @PathVariable UUID deadLetterId,
        @Valid @RequestBody ResolveDeadLetterRequest body) {
        return ResponseEntity.ok(jobs.resolveDeadLetter(TenantContextHolder.get(), deadLetterId,
            body.status(), body.note()));
    }

    // ------------------------------------------------------------------
    // connectors
    // ------------------------------------------------------------------

    /** {@code GET /api/v1/operations/connectors} */
    @GetMapping("/connectors")
    @RequirePermission(Permission.CHANNEL_READ)
    public ConnectorHealthService.ConnectorView connectors(
        @RequestParam(name = "windowHours", required = false) Integer windowHours) {
        return connectors.connectors(TenantContextHolder.get(), windowHours);
    }

    /** {@code GET /api/v1/operations/webhooks} */
    @GetMapping("/webhooks")
    @RequirePermission(Permission.CHANNEL_READ)
    public ConnectorHealthService.WebhookView webhooks(
        @RequestParam(name = "windowHours", required = false) Integer windowHours) {
        return connectors.webhooks(TenantContextHolder.get(), windowHours);
    }

    // ------------------------------------------------------------------
    // search and realtime
    // ------------------------------------------------------------------

    /** {@code GET /api/v1/operations/search/freshness} */
    @GetMapping("/search/freshness")
    @RequirePermission(Permission.KNOWLEDGE_READ)
    public SearchFreshnessService.FreshnessView freshness(
        @RequestParam(name = "projectId", required = false) UUID projectId,
        @RequestParam(name = "idleHours", required = false) Integer idleHours,
        @RequestParam(name = "limit", required = false) Integer limit) {
        return search.freshness(TenantContextHolder.get(), projectId, idleHours, limit);
    }

    /** {@code GET /api/v1/operations/search/gaps} */
    @GetMapping("/search/gaps")
    @RequirePermission(Permission.KNOWLEDGE_READ)
    public SearchFreshnessService.KnowledgeGapView gaps(
        @RequestParam(name = "projectId", required = false) UUID projectId,
        @RequestParam(name = "onlyOpen", required = false) Boolean onlyOpen,
        @RequestParam(name = "limit", required = false) Integer limit) {
        return search.knowledgeGaps(TenantContextHolder.get(), projectId, onlyOpen, limit);
    }

    /** {@code GET /api/v1/operations/realtime} */
    @GetMapping("/realtime")
    @RequirePermission(Permission.SYSTEM_HEALTH_READ)
    public SearchFreshnessService.RealtimeView realtime() {
        return search.realtime(TenantContextHolder.get());
    }

    // ------------------------------------------------------------------
    // workflows
    // ------------------------------------------------------------------

    /** {@code GET /api/v1/operations/workflows} */
    @GetMapping("/workflows")
    @RequirePermission(Permission.SYSTEM_HEALTH_READ)
    public WorkflowOperationsService.WorkflowView workflowsPanel(
        @RequestParam(name = "projectId", required = false) UUID projectId,
        @RequestParam(name = "limit", required = false) Integer limit) {
        return workflows.workflows(TenantContextHolder.get(), projectId, limit);
    }

    /** {@code GET /api/v1/operations/workflow-failures} */
    @GetMapping("/workflow-failures")
    @RequirePermission(Permission.SYSTEM_HEALTH_READ)
    public WorkflowOperationsService.FailureView workflowFailures(
        @RequestParam(name = "windowHours", required = false) Integer windowHours,
        @RequestParam(name = "limit", required = false) Integer limit) {
        return workflows.failures(TenantContextHolder.get(), windowHours, limit);
    }
}