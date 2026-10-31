package com.firstelement.admin.web;

import com.firstelement.admin.ai.AiGovernanceRepository;
import com.firstelement.admin.audit.AdminAuditStreamWriter;
import com.firstelement.admin.audit.AuditAttribution;
import com.firstelement.admin.exports.ExportActivityRepository;
import com.firstelement.admin.governance.LegalHoldRepository;
import com.firstelement.admin.governance.PrivacyDeletionRepository;
import com.firstelement.admin.identity.IdentityDirectoryRepository;
import com.firstelement.admin.identity.IdentityDirectoryService;
import com.firstelement.admin.migration.MigrationStateRepository;
import com.firstelement.admin.overview.SystemOverviewService;
import com.firstelement.security.TenantContextHolder;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.query.TenantQueryGuard;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Admin read-model routes.
 *
 * <h2>Every route names its permission</h2>
 *
 * <p>FE-WEB-003 was resolved by naming the permission at the HTTP edge for every Admin and
 * operations-observability route, and this class is where that is true. The reason is not
 * defensiveness: an administrative endpoint whose only declaration is {@code authenticated()} is a
 * route whose authorization nobody can review from the controller, and Admin is the surface most
 * likely to be attacked and least likely to be exercised by users — which is exactly where an
 * unreviewed default survives longest.
 *
 * <p>The service layer still enforces (plan.md §0.4: "Domain services, not only controllers, must
 * enforce authorization"). The annotation makes the declaration reviewable; the service makes it
 * unbypassable.
 *
 * <h2>The permission chosen per route, and why</h2>
 *
 * <table>
 *   <caption>Read-model permissions</caption>
 *   <tr><th>Route</th><th>Permission</th><th>Why</th></tr>
 *   <tr><td>overview</td><td>SYSTEM_HEALTH_READ</td>
 *       <td>It is a status document about the deployment's operation, and the least-privilege
 *           operational read in the catalogue.</td></tr>
 *   <tr><td>identity</td><td>USER_MANAGE</td>
 *       <td>It lists users, their roles, their live sessions and their credential presence. There
 *           is no weaker permission for "read the user directory".</td></tr>
 *   <tr><td>sessions</td><td>SESSION_REVOKE</td>
 *       <td>Reading the session list is the preparation for the revoke this permission exists
 *           for.</td></tr>
 *   <tr><td>permissions/{userId}</td><td>PERMISSION_INSPECTOR_READ</td>
 *       <td>Phase 3's dedicated inspector grant, for exactly this question.</td></tr>
 *   <tr><td>audit/events</td><td>AUDIT_LOG_READ</td>
 *       <td>The admin stream's audience.</td></tr>
 *   <tr><td>audit/security</td><td>SECURITY_EVENT_READ</td>
 *       <td>ADR-0005 §5 made the security stream a separate relation precisely so this grant can be
 *           separate.</td></tr>
 *   <tr><td>governance/*</td><td>SECURITY_EVENT_READ</td>
 *       <td>Retention classes, legal holds and deletion requests are what a security/compliance
 *           reviewer reads.</td></tr>
 *   <tr><td>ai/*</td><td>AI_AGENT_READ</td>
 *       <td>Policy, tool registry, blocked calls, approvals and budgets are the agent
 *           configuration surface.</td></tr>
 *   <tr><td>exports/*</td><td>ANALYTICS_READ_ORGANIZATION</td>
 *       <td>An export is an organisation-level extraction, and ADR-0007 rate-card ownership puts
 *           that with the administrator capability set.</td></tr>
 *   <tr><td>migrations, backups</td><td>SYSTEM_HEALTH_READ</td>
 *       <td>Process-level operational evidence, no tenant data.</td></tr>
 * </table>
 *
 * <p>No permission was added or widened to make one of these fit.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminReadModelController {

    private final SystemOverviewService overview;
    private final IdentityDirectoryService identity;
    private final com.firstelement.admin.audit.AuditStreamRepository auditStreams;
    private final com.firstelement.admin.audit.RetentionClassResolver retentionClasses;
    private final com.firstelement.admin.migration.BackupRestoreEvidenceReader evidence;
    private final LegalHoldRepository legalHolds;
    private final PrivacyDeletionRepository deletions;
    private final AiGovernanceRepository ai;
    private final ExportActivityRepository exports;
    private final MigrationStateRepository migrations;
    private final TenantQueryGuard guard;

    public AdminReadModelController(SystemOverviewService overview,
                                    IdentityDirectoryService identity,
                                    com.firstelement.admin.audit.AuditStreamRepository auditStreams,
                                    com.firstelement.admin.audit.RetentionClassResolver
                                        retentionClasses,
                                    com.firstelement.admin.migration.BackupRestoreEvidenceReader
                                        evidence,
                                    LegalHoldRepository legalHolds,
                                    PrivacyDeletionRepository deletions,
                                    AiGovernanceRepository ai,
                                    ExportActivityRepository exports,
                                    MigrationStateRepository migrations,
                                    TenantQueryGuard guard) {
        this.overview = overview;
        this.identity = identity;
        this.auditStreams = auditStreams;
        this.retentionClasses = retentionClasses;
        this.evidence = evidence;
        this.legalHolds = legalHolds;
        this.deletions = deletions;
        this.ai = ai;
        this.exports = exports;
        this.migrations = migrations;
        this.guard = guard;
    }

    // ------------------------------------------------------------------
    // overview
    // ------------------------------------------------------------------

    /** {@code GET /api/v1/admin/overview} */
    @GetMapping("/overview")
    @RequirePermission(Permission.SYSTEM_HEALTH_READ)
    public Map<String, Object> overview(
        @RequestParam(name = "windowHours", required = false) Integer windowHours) {
        return overview.overview(TenantContextHolder.get(), windowHours);
    }

    // ------------------------------------------------------------------
    // identity
    // ------------------------------------------------------------------

    /** {@code GET /api/v1/admin/identity} */
    @GetMapping("/identity")
    @RequirePermission(Permission.USER_MANAGE)
    public IdentityDirectoryService.DirectoryView identity() {
        return identity.directory(TenantContextHolder.get());
    }

    /** {@code GET /api/v1/admin/identity/sessions} */
    @GetMapping("/identity/sessions")
    @RequirePermission(Permission.SESSION_REVOKE)
    public List<IdentityDirectoryRepository.SessionRow> sessions(
        @RequestParam(name = "userId", required = false) UUID userId) {
        return identity.sessions(TenantContextHolder.get(), userId);
    }

    /**
     * {@code GET /api/v1/admin/identity/permissions/{userId}}
     *
     * <p>A {@code userId} on the wire is a <em>request</em>: the service resolves it inside the
     * caller's organization and raises the same non-disclosing denial for a foreign id and a
     * nonexistent one.
     */
    @GetMapping("/identity/permissions/{userId}")
    @RequirePermission(Permission.PERMISSION_INSPECTOR_READ)
    public IdentityDirectoryService.InspectionView permissions(@PathVariable UUID userId) {
        return identity.inspect(TenantContextHolder.get(), userId);
    }

    // ------------------------------------------------------------------
    // audit
    // ------------------------------------------------------------------

    /** {@code GET /api/v1/admin/audit/events} */
    @GetMapping("/audit/events")
    @RequirePermission(Permission.AUDIT_LOG_READ)
    public Map<String, Object> auditEvents(
        @RequestParam(name = "projectId", required = false) UUID projectId,
        @RequestParam(name = "hours", defaultValue = "168") int hours) {
        var scope = guard.scope(TenantContextHolder.get(), projectId);
        LocalDateTime since = AuditAttribution.now().minusHours(Math.max(1, Math.min(hours, 8760)));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scope", scope.isProjectScoped() ? "PROJECT" : "TENANT");
        payload.put("projectId", scope.projectId());
        payload.put("since", since.toString());
        payload.put("streamCounts", auditStreams.streamCounts(scope, since));
        payload.put("events", auditStreams.events(scope, since,
            com.firstelement.admin.AdministrationModule.Limits.AUDIT_PAGE));
        payload.put("sourcesNote",
            "events carry the physical table they came from. LEGACY_AUDIT_LOG is still written by "
                + ":backend:identity-access during the strangler window, so ADR-0005 section 5's "
                + "split is physical for the new streams and conventional for the legacy one.");
        return payload;
    }

    /**
     * {@code GET /api/v1/admin/audit/security}
     *
     * <p>The security stream, broken down by event type, plus the denial list. Held by
     * {@code SECURITY_EVENT_READ} rather than {@code AUDIT_LOG_READ} — the whole point of three
     * separate tables is that the grants can be separate.
     */
    @GetMapping("/audit/security")
    @RequirePermission(Permission.SECURITY_EVENT_READ)
    public Map<String, Object> securityEvents(
        @RequestParam(name = "hours", defaultValue = "168") int hours) {
        var caller = TenantContextHolder.get();
        // A security decision is project-scoped by schema, so a tenant-wide read projects through
        // the caller's own project list rather than pretending one project stands for the tenant.
        List<Map<String, Object>> byType = new java.util.ArrayList<>();
        List<Map<String, Object>> denials = new java.util.ArrayList<>();
        for (UUID projectId : caller.projectIds()) {
            var scope = guard.scope(caller, projectId);
            LocalDateTime since = AuditAttribution.now()
                .minusHours(Math.max(1, Math.min(hours, 8760)));
            byType.addAll(auditStreams.securityByType(scope, since,
                com.firstelement.admin.AdministrationModule.Limits.AUDIT_PAGE));
            for (var denial : auditStreams.denials(scope, since,
                com.firstelement.admin.AdministrationModule.Limits.AUDIT_PAGE)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("eventType", denial.eventType());
                row.put("denyReason", denial.denyReason());
                row.put("principalName", denial.principalName());
                row.put("ipAddress", denial.ipAddress());
                row.put("projectId", denial.projectId());
                row.put("occurredAt", denial.occurredAt() == null ? null
                    : denial.occurredAt().toString());
                row.put("correlationId", denial.correlationId());
                denials.add(row);
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("byEventType", byType);
        payload.put("denials", denials);
        payload.put("note", "A denial row never names the object it was refused; it names the reason, "
            + "the principal, the address and the correlation id.");
        return payload;
    }

    // ------------------------------------------------------------------
    // governance
    // ------------------------------------------------------------------

    /** {@code GET /api/v1/admin/governance/retention-classes} */
    @GetMapping("/governance/retention-classes")
    @RequirePermission(Permission.SECURITY_EVENT_READ)
    public Map<String, Object> retentionClasses() {
        var scope = guard.tenantScope(TenantContextHolder.get());
        Map<String, Object> perStream = new LinkedHashMap<>();
        for (String stream : List.of("ADMIN", "SECURITY", "JOB_INTEGRATION", "FINANCE", "PRIVACY")) {
            perStream.put(stream, retentionClasses.declared(scope, stream));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("classes", perStream);
        payload.put("undeclaredClassToken",
            com.firstelement.admin.audit.AuditVocabularies.UNDECLARED_RETENTION_CLASS);
        payload.put("note", "A row whose retentionClass is UNDECLARED is an audit row for a stream "
            + "this tenant has not configured a class for. ADR-0009 section 4 rule 4 says no table "
            + "in that ADR is purged until its retention-class column is populated, so UNDECLARED is "
            + "the signal that a purge job must not run — which is why it is a visible token rather "
            + "than a plausible-looking default.");
        return payload;
    }

    /** {@code GET /api/v1/admin/governance/legal-holds} */
    @GetMapping("/governance/legal-holds")
    @RequirePermission(Permission.SECURITY_EVENT_READ)
    public Map<String, Object> legalHoldState(
        @RequestParam(name = "projectId", required = false) UUID projectId) {
        var scope = guard.scope(TenantContextHolder.get(), projectId);
        int limit = com.firstelement.admin.AdministrationModule.Limits.GOVERNANCE_PAGE;
        var active = legalHolds.activeHolds(scope, limit);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scope", scope.isProjectScoped() ? "PROJECT" : "TENANT");
        payload.put("projectId", scope.projectId());
        payload.put("activeCount", active.size());
        payload.put("activeHolds", active);
        payload.put("coverThisScope", legalHolds.coveringCounts(scope, scope.projectId()));
        payload.put("mayPurge",
            legalHolds.activeHoldsCovering(scope, scope.projectId(), limit).isEmpty());
        payload.put("note", "ADR-0008 rule 5: a purge must refuse while a hold is active. mayPurge is "
            + "that question answered; performing the purge is a separate, currently disabled job.");
        return payload;
    }

    /** {@code GET /api/v1/admin/governance/legal-holds/{holdId}/history} */
    @GetMapping("/governance/legal-holds/{holdId}/history")
    @RequirePermission(Permission.SECURITY_EVENT_READ)
    public Map<String, Object> legalHoldHistory(@PathVariable UUID holdId) {
        var scope = guard.tenantScope(TenantContextHolder.get());
        int limit = com.firstelement.admin.AdministrationModule.Limits.GOVERNANCE_PAGE;
        return Map.of(
            "hold", legalHolds.find(scope, holdId).map(h -> Map.of(
                "id", h.id(),
                "scopeType", h.scopeType(),
                "projectId", String.valueOf(h.projectId()),
                "reason", h.reason(),
                "status", h.status(),
                "version", String.valueOf(h.version()))).orElse(Map.of()),
            "events", legalHolds.history(scope, holdId, limit));
    }

    /** {@code GET /api/v1/admin/governance/deletions} */
    @GetMapping("/governance/deletions")
    @RequirePermission(Permission.SECURITY_EVENT_READ)
    public Map<String, Object> deletionState(
        @RequestParam(name = "projectId", required = false) UUID projectId) {
        var scope = guard.scope(TenantContextHolder.get(), projectId);
        int limit = com.firstelement.admin.AdministrationModule.Limits.GOVERNANCE_PAGE;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("countsByStatus", deletions.countsByStatus(scope));
        payload.put("outstanding", deletions.openRequests(scope, limit));
        payload.put("all", deletions.requests(scope, scope.projectId(), limit));
        payload.put("note", "A request at REQUESTED removes nothing. Execution is a durable job that "
            + "ships disabled; the request and its per-target decisions are the record.");
        return payload;
    }

    /** {@code GET /api/v1/admin/governance/deletions/{requestId}/targets} */
    @GetMapping("/governance/deletions/{requestId}/targets")
    @RequirePermission(Permission.SECURITY_EVENT_READ)
    public List<PrivacyDeletionRepository.TargetRow> deletionTargets(@PathVariable UUID requestId) {
        return deletions.targets(guard.tenantScope(TenantContextHolder.get()), requestId,
            com.firstelement.admin.AdministrationModule.Limits.DELETION_TARGET_CAP);
    }

    // ------------------------------------------------------------------
    // AI governance
    // ------------------------------------------------------------------

    /** {@code GET /api/v1/admin/ai/policies} */
    @GetMapping("/ai/policies")
    @RequirePermission(Permission.AI_AGENT_READ)
    public Map<String, Object> aiPolicies(
        @RequestParam(name = "projectId", required = false) UUID projectId) {
        var scope = guard.scope(TenantContextHolder.get(), projectId);
        int limit = com.firstelement.admin.AdministrationModule.Limits.AI_PAGE;
        return Map.of(
            "policies", ai.policies(scope, scope.projectId(), limit),
            "approvedVersions", ai.approvedVersions(scope, limit));
    }

    /**
     * {@code GET /api/v1/admin/ai/tools}
     *
     * <p>The tool registry with its deny-by-default state, plus why each disabled tool is disabled.
     * The schema makes the reason unskippable; this surfaces it.
     */
    @GetMapping("/ai/tools")
    @RequirePermission(Permission.AI_AGENT_READ)
    public Map<String, Object> aiTools() {
        var scope = guard.tenantScope(TenantContextHolder.get());
        List<AiGovernanceRepository.ToolRow> tools = ai.tools(scope,
            com.firstelement.admin.AdministrationModule.Limits.AI_PAGE);
        return Map.of(
            "tools", tools,
            "summary", ai.summary(scope),
            "note", "isEnabled defaults to false, so a newly registered tool is inert until an "
                + "administrator enables it. A disabled tool carries disabledAt, disabledByUserId "
                + "and disabledReason, which the schema enforces as a biconditional.");
    }

    /**
     * {@code GET /api/v1/admin/ai/blocked-calls}
     *
     * <p>A refused or approval-gated tool call, with no argument or result field of any kind. This
     * is the route that satisfies "a denied AI action is visible to an operator without exposing
     * customer content": the tool name, the action class and the deny reason are what an operator
     * acts on, and nothing here is a customer's words.
     */
    @GetMapping("/ai/blocked-calls")
    @RequirePermission(Permission.AI_AGENT_READ)
    public Map<String, Object> blockedCalls(
        @RequestParam(name = "projectId", required = false) UUID projectId,
        @RequestParam(name = "decision", required = false) String decision,
        @RequestParam(name = "tool", required = false) String tool) {
        var scope = guard.scope(TenantContextHolder.get(), projectId);
        int limit = com.firstelement.admin.AdministrationModule.Limits.AI_EVENT_PAGE;
        return Map.of(
            "calls", ai.blockedCalls(scope, scope.projectId(), decision, tool, limit),
            "summary", ai.blockedSummary(scope, limit),
            "note", "No argumentsRedacted or resultRedacted column is projected here. A redacted "
                + "argument map still reveals the shape of a customer record, and the shape is not "
                + "needed to act on a denial. Redaction COUNTS come from the redaction read model.");
    }

    /** {@code GET /api/v1/admin/ai/approvals} */
    @GetMapping("/ai/approvals")
    @RequirePermission(Permission.AI_AGENT_READ)
    public Map<String, Object> approvals(
        @RequestParam(name = "projectId", required = false) UUID projectId,
        @RequestParam(name = "status", required = false) String status) {
        var scope = guard.scope(TenantContextHolder.get(), projectId);
        int limit = com.firstelement.admin.AdministrationModule.Limits.AI_PAGE;
        return Map.of("approvals", ai.approvals(scope, scope.projectId(), status, limit),
            "summary", ai.summary(scope),
            "note", "ADR-0007 Part 2: only a CONSEQUENTIAL action is ever approvable, the schema "
                + "refuses anybody else, and nobody may approve their own request.");
    }

    /** {@code GET /api/v1/admin/ai/approvals/{approvalId}/decisions} */
    @GetMapping("/ai/approvals/{approvalId}/decisions")
    @RequirePermission(Permission.AI_AGENT_READ)
    public List<AiGovernanceRepository.ApprovalDecisionRow> approvalDecisions(
        @PathVariable UUID approvalId) {
        return ai.approvalDecisions(guard.tenantScope(TenantContextHolder.get()), approvalId,
            com.firstelement.admin.AdministrationModule.Limits.AI_PAGE);
    }

    /** {@code GET /api/v1/admin/ai/budgets} */
    @GetMapping("/ai/budgets")
    @RequirePermission(Permission.AI_AGENT_READ)
    public Map<String, Object> budgets(
        @RequestParam(name = "projectId", required = false) UUID projectId,
        @RequestParam(name = "sinceDays", defaultValue = "30") int sinceDays) {
        var scope = guard.scope(TenantContextHolder.get(), projectId);
        int limit = com.firstelement.admin.AdministrationModule.Limits.AI_PAGE;
        LocalDateTime since = AuditAttribution.now()
            .minusDays(Math.max(1, Math.min(sinceDays, 365)));
        return Map.of(
            "budgets", ai.budgets(scope, scope.projectId(), limit),
            "spend", ai.budgetSpend(scope, since, limit),
            "note", "Spend is a SUM over ai_budget_consumptions, never a mutable counter, so it is "
                + "re-derivable from the same ledger the finance read model uses.");
    }

    /** {@code GET /api/v1/admin/ai/redactions} */
    @GetMapping("/ai/redactions")
    @RequirePermission(Permission.AI_AGENT_READ)
    public Map<String, Object> redactions(
        @RequestParam(name = "projectId", required = false) UUID projectId) {
        var scope = guard.scope(TenantContextHolder.get(), projectId);
        int limit = com.firstelement.admin.AdministrationModule.Limits.AI_EVENT_PAGE;
        return Map.of(
            "records", ai.redactions(scope, scope.projectId(), limit),
            "summary", ai.redactionSummary(scope, limit),
            "note", "WHICH policy, WHICH rule, WHICH field, HOW MANY values. Never the values: the "
                + "migration's guard fails if a column named rawValue or originalText appears on "
                + "these tables.");
    }

    // ------------------------------------------------------------------
    // exports
    // ------------------------------------------------------------------

    /** {@code GET /api/v1/admin/exports/jobs} */
    @GetMapping("/exports/jobs")
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    public Map<String, Object> exportJobs(
        @RequestParam(name = "projectId", required = false) UUID projectId,
        @RequestParam(name = "status", required = false) String status) {
        var scope = guard.scope(TenantContextHolder.get(), projectId);
        int limit = com.firstelement.admin.AdministrationModule.Limits.EXPORT_PAGE;
        UUID jobId = null;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("countsByStatus", exports.countsByStatus(scope));
        payload.put("completedButExpiredStillPresent", exports.expiredButStillPresent(scope));
        payload.put("deniedDownloads", exports.deniedDownloads(scope, limit));
        payload.put("jobs", exports.jobs(scope, scope.projectId(), status, limit));
        payload.put("note", "filters and authorizationSnapshot are not projected: a filter can name "
            + "a customer's date of birth and a snapshot is a record of somebody's permissions on "
            + "the day. Neither is needed to answer 'did this export work'.");
        return payload;
    }

    /** {@code GET /api/v1/admin/exports/jobs/{jobId}/detail} */
    @GetMapping("/exports/jobs/{jobId}/detail")
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    public Map<String, Object> exportDetail(@PathVariable UUID jobId) {
        var scope = guard.tenantScope(TenantContextHolder.get());
        int limit = com.firstelement.admin.AdministrationModule.Limits.EXPORT_PAGE;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("attempts", exports.attempts(scope, jobId, limit));
        payload.put("downloads", exports.downloads(scope, null, jobId, limit));
        payload.put("deletions", exports.deletions(scope, jobId, limit));
        return payload;
    }

    // ------------------------------------------------------------------
    // migration and backup evidence
    // ------------------------------------------------------------------

    /**
     * {@code GET /api/v1/admin/migrations}
     *
     * <p>Process-level, not tenant-level: {@code flyway_schema_history} describes one deployment.
     * Read-only, and there is deliberately no repair endpoint.
     */
    @GetMapping("/migrations")
    @RequirePermission(Permission.SYSTEM_HEALTH_READ)
    public Map<String, Object> migrationState() {
        List<Map<String, Object>> chain = new java.util.ArrayList<>();
        for (MigrationStateRepository.AppliedMigration row : migrations.applied(
            com.firstelement.admin.AdministrationModule.Limits.MIGRATION_PAGE)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("rank", row.installedRank());
            entry.put("version", row.version());
            entry.put("description", row.description());
            entry.put("type", row.type());
            entry.put("script", row.script());
            entry.put("checksum", row.checksum());
            entry.put("installedOn", row.installedOn() == null ? null : row.installedOn().toString());
            entry.put("executionTimeMs", row.executionTime());
            entry.put("success", row.success());
            chain.add(entry);
        }
        MigrationStateRepository.StampState stamp = migrations.stamp();
        return Map.of(
            "scope", "PROCESS",
            "stamped", stamp.stamped(),
            "appliedCount", stamp.applied(),
            "latestRank", stamp.latestRank(),
            "failedCount", migrations.failedCount(),
            "chain", chain,
            "note", "Read-only. The migration chain is owned by ops/flyway and its runbook; an Admin "
                + "surface that could mark a failed migration as applied would be exactly the "
                + "unrestricted database mutation FR-6 forbids.");
    }

    /**
     * {@code GET /api/v1/admin/backups}
     *
     * <p>The restore evidence the Phase-2/7-10a tooling wrote, read from disk. Never {@code 0}:
     * {@code NOT_CONFIGURED}, {@code NO_EVIDENCE_ROOT} and {@code NO_EVIDENCE} are three distinct
     * answers.
     */
    @GetMapping("/backups")
    @RequirePermission(Permission.SYSTEM_HEALTH_READ)
    public Map<String, Object> backups() {
        var latest = evidence.latest();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scope", "PROCESS");
        payload.put("configured", evidence.configured());
        payload.put("rehearsals", evidence.listRehearsals());
        payload.put("status", latest.status());
        payload.put("latest", latest.name());
        payload.put("artefactsFound", latest.found());
        payload.put("checks", latest.checks());
        payload.put("sourceSummary", latest.sourceSummary());
        payload.put("restoredSummary", latest.restoredSummary());
        payload.put("evidenceRootLastModified", evidence.rootLastModified());
        payload.put("message", latest.message());
        payload.put("note", "Read-only, and never executed from here. There is no shell in this "
            + "path: a restore rehearsal needs a disposable target and a rehearsed rollback, which "
            + "is an operator runbook, not an HTTP endpoint.");
        return payload;
    }

    /** {@code GET /api/v1/admin/backups/{name}} */
    @GetMapping("/backups/{name}")
    @RequirePermission(Permission.SYSTEM_HEALTH_READ)
    public Map<String, Object> backupDetail(@PathVariable String name) {
        var read = evidence.read(name);
        return Map.of(
            "scope", "PROCESS",
            "status", read.status(),
            "name", String.valueOf(read.name()),
            "artefactsFound", read.found(),
            "checks", read.checks(),
            "sourceSummary", read.sourceSummary(),
            "restoredSummary", read.restoredSummary(),
            "message", read.message());
    }
}