package com.firstelement.admin.overview;

import com.firstelement.admin.AdministrationModule;
import com.firstelement.admin.ai.AiGovernanceRepository;
import com.firstelement.admin.audit.AuditStreamRepository;
import com.firstelement.admin.exports.ExportActivityRepository;
import com.firstelement.admin.governance.LegalHoldRepository;
import com.firstelement.admin.governance.PrivacyDeletionRepository;
import com.firstelement.admin.identity.IdentityDirectoryRepository;
import com.firstelement.admin.migration.BackupRestoreEvidenceReader;
import com.firstelement.admin.migration.MigrationStateRepository;
import com.firstelement.security.TenantContext;
import com.firstelement.security.query.TenantQueryGuard;
import com.firstelement.security.query.TenantScope;
import com.firstelement.worker.runtime.QueueHealthService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The answer to "is the system controlled, secure, and operating?" in one document.
 *
 * <h2>What an overview is and is not</h2>
 *
 * <p>It is a set of counts and states, each of which is answerable in one bounded query, and each of
 * which says <em>which tenant</em> it is about. It is not a health score, and there is deliberately
 * no composite number: "7/10 healthy" is a number nobody can act on and hides which of the ten
 * parts is broken. The overview's job is to put the ten answers next to each other so the operator
 * sees which one moved.
 *
 * <h2>"No data" is not zero</h2>
 *
 * <p>FR-7.6 and ADR-0007 rule 5 apply to an operator panel as much as to a finance one. Every
 * count here is a real {@code count(*)}, and the read model says which table it came from, so an
 * absent table and an empty one are distinguishable. Where a source is not configured at all — the
 * backup/restore evidence root, for instance — the answer is {@code NOT_CONFIGURED}, never {@code 0}.
 */
@Service
@Transactional(readOnly = true)
public class SystemOverviewService {

    /** The window the activity counts cover. Configurable per call, defaulted here. */
    private static final int DEFAULT_WINDOW_HOURS = 24;

    private final TenantQueryGuard guard;
    private final IdentityDirectoryRepository directory;
    private final AuditStreamRepository auditStreams;
    private final LegalHoldRepository legalHolds;
    private final PrivacyDeletionRepository deletions;
    private final AiGovernanceRepository ai;
    private final ExportActivityRepository exports;
    private final MigrationStateRepository migrations;
    private final BackupRestoreEvidenceReader evidence;
    private final QueueHealthService queueHealth;

    public SystemOverviewService(TenantQueryGuard guard,
                                 IdentityDirectoryRepository directory,
                                 AuditStreamRepository auditStreams,
                                 LegalHoldRepository legalHolds,
                                 PrivacyDeletionRepository deletions,
                                 AiGovernanceRepository ai,
                                 ExportActivityRepository exports,
                                 MigrationStateRepository migrations,
                                 BackupRestoreEvidenceReader evidence,
                                 QueueHealthService queueHealth) {
        this.guard = guard;
        this.directory = directory;
        this.auditStreams = auditStreams;
        this.legalHolds = legalHolds;
        this.deletions = deletions;
        this.ai = ai;
        this.exports = exports;
        this.migrations = migrations;
        this.evidence = evidence;
        this.queueHealth = queueHealth;
    }

    /**
     * The whole overview for one tenant.
     *
     * <p>The migration and backup sections are process-level and are marked as such in the
     * payload, because they describe the deployment rather than the tenant and an operator must not
     * read "your migrations" as if another tenant's chain were theirs.
     */
    public Map<String, Object> overview(TenantContext caller, Integer windowHours) {
        TenantScope scope = guard.tenantScope(caller);
        LocalDateTime since = com.firstelement.admin.audit.AuditAttribution.now()
            .minusHours(windowHours == null || windowHours < 1 ? DEFAULT_WINDOW_HOURS : windowHours);

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("organizationId", caller.organizationId());
        document.put("principal", caller.auditActorLabel());
        document.put("windowHours", windowHours == null || windowHours < 1
            ? DEFAULT_WINDOW_HOURS : windowHours);
        document.put("since", since.toString());
        document.put("generatedAt", com.firstelement.admin.audit.AuditAttribution.now().toString());

        document.put("identity", identity(scope));
        document.put("queues", queues(caller));
        document.put("auditStreams", auditStreams.streamCounts(scope, since));
        document.put("legalHolds", legalHolds.coveringCounts(scope, null));
        document.put("privacyDeletions", deletions.countsByStatus(scope));
        document.put("aiGovernance", ai.summary(scope));
        document.put("exports", exportActivity(scope));
        document.put("migration", migrationState());
        document.put("backupEvidence", backupEvidence());
        document.put("findings", findings(scope, caller));
        return document;
    }

    /** Users, roles, sessions, projects. */
    private Map<String, Object> identity(TenantScope scope) {
        IdentityDirectoryRepository.DirectorySummary summary = directory.summary(scope);
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("totalUsers", summary.totalUsers());
        section.put("inactiveUsers", summary.inactiveUsers());
        section.put("administrators", summary.administrators());
        section.put("liveSessions", summary.liveSessions());
        section.put("memberships", summary.memberships());
        section.put("projects", summary.projects());
        section.put("archivedProjects", summary.archivedProjects());
        section.put("source", "users, project_memberships, sessions, projects");
        return section;
    }

    /** Outbox depth, queue lag, attempts and dead letters, for this tenant only. */
    private Map<String, Object> queues(TenantContext caller) {
        QueueHealthService.QueueHealth health = queueHealth.healthFor(caller);
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("outboxStateCounts", health.outboxStateCounts());
        section.put("dueNow", health.dueNow());
        section.put("oldestPendingSeconds",
            health.oldestPendingAge() == null ? 0L : health.oldestPendingAge().toSeconds());
        section.put("attemptsInWindow", health.attemptsInWindow());
        section.put("failuresInWindow", health.failuresInWindow());
        // -1 means "no attempts in the window", which is not a 0% failure rate. Reported as-is
        // rather than coerced, because the distinction is FR-7.6's whole point.
        section.put("failureRate", health.failureRate());
        section.put("openDeadLetters", health.openDeadLetters());
        return section;
    }

    /** Export activity, including the number that proves an expiry sweeper is not running. */
    private Map<String, Object> exportActivity(TenantScope scope) {
        Map<String, Object> section = new LinkedHashMap<>(exports.countsByStatus(scope));
        section.put("completedButExpiredStillPresent", exports.expiredButStillPresent(scope));
        section.put("source", "export_jobs");
        return section;
    }

    /**
     * The migration chain.
     *
     * <p>Process-level, not tenant-level, and labelled as such in the payload.
     */
    private Map<String, Object> migrationState() {
        MigrationStateRepository.StampState stamp = migrations.stamp();
        List<Map<String, Object>> applied = new ArrayList<>();
        for (MigrationStateRepository.AppliedMigration row : migrations.applied(
            AdministrationModule.Limits.MIGRATION_PAGE)) {
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
            applied.add(entry);
        }
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("scope", "PROCESS");
        section.put("stamped", stamp.stamped());
        section.put("appliedCount", stamp.applied());
        section.put("latestRank", stamp.latestRank());
        section.put("failedCount", migrations.failedCount());
        section.put("chain", applied);
        return section;
    }

    /**
     * Backup and restore evidence, read-only from the artefacts the rehearsal script writes.
     *
     * <p>Never {@code 0}: {@code NOT_CONFIGURED} and {@code NO_EVIDENCE} are distinct answers,
     * because "we never rehearsed a restore" and "we could not read the rehearsal" lead to different
     * conversations.
     */
    private Map<String, Object> backupEvidence() {
        BackupRestoreEvidenceReader.RehearsalEvidence latest = evidence.latest();
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("scope", "PROCESS");
        section.put("configured", evidence.configured());
        section.put("status", latest.status());
        section.put("latestRehearsal", latest.name());
        section.put("artefactsFound", latest.found());
        section.put("checks", latest.checks());
        section.put("sourceSummary", latest.sourceSummary());
        section.put("restoredSummary", latest.restoredSummary());
        section.put("evidenceRootLastModified", evidence.rootLastModified());
        section.put("message", latest.message());
        return section;
    }

    /**
     * The things an operator should look at first, computed rather than described.
     *
     * <p>Each finding carries the number that produced it and what to do about it, so the list is a
     * worklist rather than a set of assertions. It is bounded and deduplicated by token.
     */
    private List<Map<String, Object>> findings(TenantScope scope, TenantContext caller) {
        List<Map<String, Object>> findings = new ArrayList<>();
        addIf(findings, "OPEN_DEAD_LETTERS",
            queueHealth.healthFor(caller).openDeadLetters(), 0,
            "Jobs are dead-lettered and will not retry on their own.",
            "Open the operations job list and replay only the ones whose queue is declared "
                + "replay-safe.");
        addIf(findings, "ACTIVE_LEGAL_HOLD",
            legalHolds.activeCountCovering(scope, null), 0,
            "A legal hold is active; any purge covering this tenant must refuse.",
            "Read the hold's reason and scope before releasing it.");
        addIf(findings, "PENDING_PRIVACY_DELETIONS",
            deletions.openRequests(scope, AdministrationModule.Limits.GOVERNANCE_PAGE).size(), 0,
            "Privacy-deletion requests are outstanding. Execution is a disabled job, so these will "
                + "not complete on their own.",
            "Verify each request, then hand it to the deletion job when that job is enabled.");
        addIf(findings, "EXPORT_OBJECT_OUTLIVES_WINDOW",
            exports.expiredButStillPresent(scope), 0,
            "A completed export's object is still in object storage past its expiry window.",
            "The export job row says COMPLETED because it completed; only this count says its bytes "
                + "are still there.");
        addIf(findings, "DISABLED_AI_TOOLS",
            (int) ai.summary(scope).disabledTools(), 0,
            "Tools are registered but disabled. That is the safe default, not a fault.",
            "The disable reason, who disabled it and when are on the tool read model.");
        addIf(findings, "PENDING_AI_APPROVALS",
            (int) ai.summary(scope).pendingApprovals(), 0,
            "Consequential AI actions are waiting for a human approval that only a person can give.",
            "ADR-0007 Part 2: the model may request a consequential action but never perform it.");
        return findings;
    }

    private static void addIf(List<Map<String, Object>> findings, String token, int count,
                              int threshold, String detail, String action) {
        if (count <= threshold) {
            return;
        }
        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("token", token);
        finding.put("count", count);
        finding.put("detail", detail);
        finding.put("action", action);
        findings.add(finding);
    }
}