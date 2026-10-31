package com.firstelement.observ.workflow;

import com.firstelement.automation.persistence.WorkflowCatalogRepository;
import com.firstelement.automation.persistence.WorkflowHistoryRepository;
import com.firstelement.security.TenantContext;
import com.firstelement.security.query.TenantQueryGuard;
import com.firstelement.security.query.TenantScope;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workflow versions, activation and rollback state, dry-run counts and failed executions.
 *
 * <h2>Reuse, not re-derivation</h2>
 *
 * <p>Every statement here belongs to {@code WorkflowCatalogRepository} and
 * {@code WorkflowHistoryRepository} in {@code :backend:workflows-sla-routing}, whose SQL is
 * transcribed from {@code ops/flyway/reference/workflow-sla-statements.sql} and shaped around the
 * indexes {@code V20261005010000} created. A second copy of "the operator's run list" would be a
 * second query that could drift off its index, so this class composes their results and adds only
 * the presentation.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <p>The state machine body. {@code workflow_versions."stateMachine"} is the authored workflow, and an
 * Admin read model that dumped it would be a configuration editor wearing a read model's clothes.
 * What an operator needs is: which version is live, what the live version is allowed to do
 * ({@code capabilityScope}), its content hash so two versions can be compared, and how it has behaved.
 */
@Service
@Transactional(readOnly = true)
public class WorkflowOperationsService {

    private final WorkflowCatalogRepository catalog;
    private final WorkflowHistoryRepository history;
    private final TenantQueryGuard guard;
    private final com.firstelement.security.permission.AuthorizationService authorization;

    public WorkflowOperationsService(WorkflowCatalogRepository catalog,
                                     WorkflowHistoryRepository history,
                                     TenantQueryGuard guard,
                                     com.firstelement.security.permission.AuthorizationService
                                         authorization) {
        this.catalog = catalog;
        this.history = history;
        this.guard = guard;
        this.authorization = authorization;
    }

    /**
     * The permission check, here as well as on the route.
     *
     * <p>{@code @RequirePermission} on the controller makes the declaration reviewable in one place;
     * plan.md §0.4 requires the domain service to enforce it as well, because a read model is
     * reachable from more than HTTP — a scheduled report, a future internal caller, or a test that
     * binds a context directly. {@link Permission#SYSTEM_HEALTH_READ} is the permission these reads
     * declare; see the class note on the controller for why a dedicated {@code WORKFLOW_READ} is a
     * follow-up rather than something this phase may add.
     */
    private TenantScope authorize(TenantContext caller,
                                  com.firstelement.security.permission.Permission permission) {
        if (caller == null) {
            throw new IllegalStateException(
                "An operations read requires a resolved TenantContext; refusing to run unscoped.");
        }
        authorization.check(caller, permission,
            com.firstelement.security.permission.PolicyResource.NONE);
        return guard.scope(caller, null);
    }

    /** The workflow panel: definitions, their live version, and the recent run outcomes. */
    public WorkflowView workflows(TenantContext caller, UUID projectId, Integer limit) {
        authorize(caller, com.firstelement.security.permission.Permission.SYSTEM_HEALTH_READ);
        TenantScope scope = guard.scope(caller, projectId);
        int bounded = limit == null || limit < 1
            ? com.firstelement.observ.OperationsObservabilityModule.Limits.WORKFLOWS
            : Math.min(limit, com.firstelement.observ.OperationsObservabilityModule.Limits.WORKFLOWS);

        List<WorkflowCatalogRepository.DefinitionRow> definitions =
            catalog.definitionsForDryRun(scope, bounded);
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (WorkflowCatalogRepository.DefinitionRow definition : definitions) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", definition.id());
            entry.put("key", definition.key());
            entry.put("name", definition.name());
            entry.put("triggerType", definition.triggerType());
            entry.put("priority", definition.priority());
            entry.put("enabled", definition.enabled());
            entry.put("latestVersionNumber", definition.latestVersionNumber());
            entry.put("activeVersionId", definition.activeVersionId());
            // The version history is what answers "can I roll back to something?" and "who activated
            // this, and when", so it is included rather than being a second request.
            entry.put("versions", catalog.versionHistory(scope, definition.id(), 20).stream()
                .map(version -> {
                    Map<String, Object> v = new LinkedHashMap<>();
                    v.put("id", version.id());
                    v.put("versionNumber", version.versionNumber());
                    v.put("state", version.state());
                    v.put("contentHash", version.contentHash());
                    v.put("capabilityScope", version.capabilityScope());
                    v.put("activatedAt", iso(version.activatedAt()));
                    v.put("supersededAt", iso(version.supersededAt()));
                    v.put("supersededByVersionId", version.supersededByVersionId());
                    v.put("rolledBackAt", iso(version.rolledBackAt()));
                    v.put("rolledBackByUserId", version.rolledBackByUserId());
                    v.put("rolledBackToVersionId", version.rolledBackToVersionId());
                    v.put("changeNote", version.changeNote());
                    return v;
                }).toList());
            rows.add(entry);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scope", scope.isProjectScoped() ? "PROJECT" : "TENANT");
        payload.put("projectId", scope.projectId());
        payload.put("definitions", rows);
        payload.put("recentRuns", runHistoryAcrossScope(caller, scope, bounded));
        payload.put("note", "A definition with activeVersionId = null is still being authored, which "
            + "is a legitimate state and not a defect. The state machine body is not projected: an "
            + "Admin read model is not a configuration editor.");
        return new WorkflowView(payload);
    }

    /**
     * Recent runs for the caller's reachable projects.
     *
     * <p>{@code WorkflowHistoryRepository.RUN_SUMMARY} filters on
     * {@code "__fe_tenant_project_id"} unconditionally — it is a per-project statement, shaped around
     * the {@code workflow_runs_organizationId_projectId_...} index, and it was written to be called
     * with a project-scoped {@link TenantScope}. A tenant-scoped scope does not bind that parameter,
     * so passing one would fail the query rather than widen it.
     *
     * <p>So a tenant-wide read projects through the caller's own project list instead of pretending
     * one project stands for the tenant. That is the same approach {@code AdminReadModelController}'s
     * security-stream route takes, and it keeps the answer inside the caller's reach: a project the
     * principal cannot reach contributes no runs, and no run can arrive from outside the tenant.
     */
    private List<WorkflowHistoryRepository.RunSummaryRow> runHistoryAcrossScope(
        TenantContext caller, TenantScope scope, int limit) {
        List<WorkflowHistoryRepository.RunSummaryRow> runs = new java.util.ArrayList<>();
        for (UUID projectId : projectIdsInScope(caller, scope)) {
            runs.addAll(history.runHistory(guard.scope(caller, projectId), projectId, List.of(), limit));
        }
        return runs;
    }

    /**
     * Collision tallies, for the same reason as {@link #runHistoryAcrossScope}.
     *
     * <p>{@code COLLISION_TALLY} is likewise a per-project statement, and a tenant-scoped call would
     * fail on the unbound project parameter rather than silently returning the whole tenant.
     */
    private List<WorkflowHistoryRepository.CollisionTally> collisionsAcrossScope(
        TenantContext caller, TenantScope scope, LocalDateTime since, int limit) {
        List<WorkflowHistoryRepository.CollisionTally> collisions = new java.util.ArrayList<>();
        for (UUID projectId : projectIdsInScope(caller, scope)) {
            collisions.addAll(history.collisionTally(guard.scope(caller, projectId), since, limit));
        }
        return collisions;
    }

    /**
     * The projects a scope covers, always non-empty for a caller who can reach at least one project.
     *
     * <p>A project-scoped scope answers with that project. A tenant-scoped scope projects through the
     * caller's own {@code projectIds}: those are server-resolved on the {@link TenantContext}, never
     * taken from the request, so iterating them cannot reach a project the principal is not entitled
     * to.
     */
    private java.util.List<UUID> projectIdsInScope(TenantContext caller, TenantScope scope) {
        if (scope.isProjectScoped()) {
            return List.of(scope.projectId());
        }
        java.util.List<UUID> reachable = new java.util.ArrayList<>();
        for (UUID projectId : caller.projectIds()) {
            if (projectId != null) {
                reachable.add(projectId);
            }
        }
        return reachable;
    }

    /** Failed and retryable action executions over the window: the failure dashboard. */
    public FailureView failures(TenantContext caller, Integer windowHours, Integer limit) {
        authorize(caller, com.firstelement.security.permission.Permission.SYSTEM_HEALTH_READ);
        TenantScope scope = guard.tenantScope(caller);
        int window = windowHours == null || windowHours < 1 ? 168 : Math.min(windowHours, 8760);
        int bounded = limit == null || limit < 1
            ? com.firstelement.observ.OperationsObservabilityModule.Limits.WORKFLOWS
            : Math.min(limit, com.firstelement.observ.OperationsObservabilityModule.Limits.WORKFLOWS);
        LocalDateTime since = com.firstelement.admin.audit.AuditAttribution.now().minusHours(window);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("windowHours", window);
        // failureDashboard filters on organizationId alone, so a tenant-scoped scope is correct here.
        payload.put("failedActions", history.failureDashboard(scope, since, bounded));
        payload.put("collisions", collisionsAcrossScope(caller, scope, since, bounded));
        payload.put("note", "collisions is the Phase-6 acceptance counter: how often a human won a "
            + "takeover, per outcome. failedActions is tenant-scoped; collisions are counted per "
            + "project because collision_records is a per-project relation.");
        return new FailureView(payload);
    }

    /** The workflow panel document. */
    public record WorkflowView(Map<String, Object> document) {
    }

    /** The workflow failure panel document. */
    public record FailureView(Map<String, Object> document) {
    }

    private static String iso(LocalDateTime value) {
        return value == null ? null : value.toString();
    }
}