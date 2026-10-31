package com.firstelement.exports.retention;

import com.firstelement.admin.audit.AdminAuditStreamWriter;
import com.firstelement.admin.audit.AuditAttribution;
import com.firstelement.admin.audit.AuditVocabularies;
import com.firstelement.admin.audit.AuditWrite;
import com.firstelement.admin.governance.LegalHoldService;
import com.firstelement.exports.config.ExportProperties;
import com.firstelement.exports.persistence.ExportEventRepository;
import com.firstelement.exports.persistence.ExportJobRepository;
import com.firstelement.exports.storage.ExportObjectStore;
import com.firstelement.security.TenantContext;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.error.PolicyDeniedException;
import com.firstelement.security.permission.AuthorizationService;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.query.TenantQueryGuard;
import com.firstelement.security.query.TenantScope;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removes expired export artefacts, and refuses to while the purge is disabled.
 *
 * <h2>Ships DISABLED, and says so on every call</h2>
 *
 * <p>ADR-0008 §2 rule 3 and ADR-0009 §4 rule 3 both say a purge ships disabled until legal-hold state
 * exists. Phase 7 exposed hold state, so that half is satisfied. The other half — ADR-0009 §4 rule 4, "no
 * table in this ADR is purged by a scheduled job until the retention-class column is populated for it" — is a
 * per-tenant data question, and a property flip is not how an operator answers it.
 *
 * <p>So {@link #purgeExpired} refuses, records the refusal, and reports exactly what it would have done.
 * That is testable by asserting on the row state <em>afterwards</em>, which is ADR-0009 §5's rule: the
 * refusal is a claim and the unchanged table is the evidence.
 *
 * <h2>Expiry is different from purge, and only expiry is enabled</h2>
 *
 * <p>{@link #expireDue} is the state transition a completed job takes when its window closes. It is enabled,
 * it is bounded, it is per tenant, and it removes the object before the state says so. The <em>purge</em> —
 * removing the tombstone rows and their history — is the disabled capability, because that is the step that
 * destroys evidence.
 *
 * <h2>The legal-hold gate is asked on every deletion, and the answer is recorded</h2>
 *
 * <p>{@code export_deletion_events."legalHoldChecked"} is ADR-0008 rule 5's evidence column: "a deletion
 * event with legalHoldChecked false is a deletion that skipped the question". Every event this service
 * writes carries the answer, including the refusals, so an operator can see which deletions asked.
 *
 * <h2>The object is deleted BEFORE the row says so</h2>
 *
 * <p>ADR-0009 §4 rule 1: "a purge that leaves the file behind is worse than no purge, because the row no
 * longer records that it exists". So the order is delete-the-object, then record-the-event, then
 * tombstone-the-row — and if the storage delete fails, the row stays COMPLETED and the event says
 * {@code STORAGE_DELETE_FAILED}. A state that says DELETED with a file still on disk is unrepresentable.
 */
@Service
public class ExportRetentionService {

    private static final Logger log = LoggerFactory.getLogger(ExportRetentionService.class);

    static final String ARTEFACT_EXPIRED = "EXPORT_ARTEFACT_EXPIRED";
    static final String ARTEFACT_DELETED = "EXPORT_ARTEFACT_DELETED";
    static final String RETENTION_PURGE_REFUSED = "EXPORT_RETENTION_PURGE_REFUSED";

    private final ExportJobRepository jobs;
    private final ExportEventRepository events;
    private final ExportObjectStore store;
    private final LegalHoldService legalHolds;
    private final RetentionWindowResolver windows;
    private final TenantQueryGuard guard;
    private final AuthorizationService authorization;
    private final AdminAuditStreamWriter audit;
    private final ExportProperties properties;

    public ExportRetentionService(ExportJobRepository jobs,
                                  ExportEventRepository events,
                                  ExportObjectStore store,
                                  LegalHoldService legalHolds,
                                  RetentionWindowResolver windows,
                                  TenantQueryGuard guard,
                                  AuthorizationService authorization,
                                  AdminAuditStreamWriter audit,
                                  ExportProperties properties) {
        this.jobs = jobs;
        this.events = events;
        this.store = store;
        this.legalHolds = legalHolds;
        this.windows = windows;
        this.guard = guard;
        this.authorization = authorization;
        this.audit = audit;
        this.properties = properties;
    }

    // ------------------------------------------------------------------
    // expiry (enabled)
    // ------------------------------------------------------------------

    /** What one expiry pass did. Counts are asserted on the state afterwards, not on the log line. */
    public record ExpiryOutcome(int considered,
                                int expired,
                                int storageFailures,
                                int legalHoldRefusals,
                                List<UUID> jobIds) {
    }

    /**
     * Expires completed jobs past their window, per tenant.
     *
     * <p>Tenant-scoped and bounded, unlike the outbox sweep ADR-0008 §1 declined to implement: an export's
     * expiry removes customer data, so a global sweep over it would be a cross-tenant deletion path. This
     * method requires a caller's scope and takes no tenant id.
     *
     * @param projectId the project to sweep, or {@code null} for every project the caller may see
     */
    @Transactional
    @RequirePermission(Permission.ORGANIZATION_UPDATE)
    public ExpiryOutcome expireDue(TenantContext caller, UUID projectId) {
        authorization.check(caller, Permission.ORGANIZATION_UPDATE);
        TenantScope scope = guard.scope(caller, projectId);
        LocalDateTime now = AuditAttribution.now();
        int batch = Math.max(1, properties.getRetention().getPurgeBatchSize());

        List<ExportJobRepository.ExportJobRow> due = jobs.expirable(scope, projectId, now, batch);
        int expired = 0;
        int storageFailures = 0;
        int legalHoldRefusals = 0;
        List<UUID> handled = new ArrayList<>(due.size());

        for (ExportJobRepository.ExportJobRow job : due) {
            handled.add(job.id());
            LocalDateTime occurredAt = AuditAttribution.now();

            // ADR-0008 rule 5: ask about a legal hold BEFORE deleting anything, and record the answer.
            LegalHoldService.PurgeGate gate = legalHolds.gate(caller, job.projectId());
            if (!gate.mayPurge()) {
                legalHoldRefusals++;
                events.recordDeletion(scope, job.projectId(), job.id(),
                    ExportEventRepository.DeletionOutcome.STORAGE_DELETE_FAILED, job.storageKey(),
                    null, caller.userId(), "LEGAL_HOLD_ACTIVE",
                    "A legal hold covers this project's exports, so the artefact was NOT deleted.",
                    true, gate.blockingHolds().isEmpty() ? null
                        : gate.blockingHolds().get(0).id().toString(),
                    job.correlationId(), occurredAt);
                audit(caller, scope, ARTEFACT_EXPIRED, "ExportJob", job.id(),
                    AuditVocabularies.AuditOutcome.DENIED, java.util.Map.of(
                        "reason", gate.refusalReason(),
                        "blockingHolds", String.valueOf(gate.blockingCount())));
                log.info("export expiry refused for job {} because a legal hold covers project {}",
                    job.id(), job.projectId());
                continue;
            }

            long reclaimed = job.bytes() == null ? 0L : job.bytes();
            boolean removed = store.delete(job.storageKey());
            if (!removed) {
                // The row stays COMPLETED: a job that says COMPLETED and has no object is a storage fault,
                // and a job that says EXPIRED with its file still on disk is a state the whole design
                // refuses to represent.
                storageFailures++;
                events.recordDeletion(scope, job.projectId(), job.id(),
                    ExportEventRepository.DeletionOutcome.STORAGE_DELETE_FAILED, job.storageKey(), null,
                    caller.userId(), "STORAGE_DELETE_FAILED",
                    "The store could not confirm the object was removed, so the job was left COMPLETED "
                        + "rather than claiming its object is gone.",
                    true, null, job.correlationId(), occurredAt);
                audit(caller, scope, ARTEFACT_EXPIRED, "ExportJob", job.id(),
                    AuditVocabularies.AuditOutcome.FAILED, java.util.Map.of(
                        "reason", "STORAGE_DELETE_FAILED"));
                log.error("export object could not be deleted for job {}; the job stays COMPLETED",
                    job.id());
                continue;
            }

            events.recordDeletion(scope, job.projectId(), job.id(),
                ExportEventRepository.DeletionOutcome.STORAGE_OBJECT_DELETED, job.storageKey(), reclaimed,
                caller.userId(), null, null, true, null, job.correlationId(), occurredAt);
            if (jobs.expire(scope, job.id(), occurredAt)) {
                expired++;
                events.recordDeletion(scope, job.projectId(), job.id(),
                    ExportEventRepository.DeletionOutcome.ROW_TOMBSTONED, null, null, caller.userId(),
                    null, null, true, null, job.correlationId(), occurredAt);
                audit(caller, scope, ARTEFACT_EXPIRED, "ExportJob", job.id(),
                    AuditVocabularies.AuditOutcome.SUCCESS, java.util.Map.of(
                        "bytesReclaimed", String.valueOf(reclaimed),
                        "rowCount", String.valueOf(job.rowCount() == null ? 0 : job.rowCount()),
                        "expiresAt", String.valueOf(job.expiresAt())));
                log.info("export artefact expired job={} bytesReclaimed={}", job.id(), reclaimed);
            } else {
                log.warn("an export object was deleted but the job did not transition to EXPIRED; job={}",
                    job.id());
            }
        }

        log.info("export expiry pass tenant={} project={} considered={} expired={} storageFailures={} "
                + "legalHoldRefusals={}", caller.organizationId(), projectId, due.size(), expired,
            storageFailures, legalHoldRefusals);
        return new ExpiryOutcome(due.size(), expired, storageFailures, legalHoldRefusals, handled);
    }

    // ------------------------------------------------------------------
    // purge (disabled)
    // ------------------------------------------------------------------

    /** What a purge would have done, and why it did not. */
    public record PurgeRefusal(String reason,
                               String message,
                               int artefactsEligible,
                               int tombstonesEligible,
                               boolean purgeEnabled) {

        /** The stable code for the HTTP layer. */
        public String code() {
            return "EXPORT_PURGE_DISABLED";
        }
    }

    /**
     * Refuses to purge, and records the refusal.
     *
     * <p>Not "does nothing": it answers the question an operator has (which artefacts are past their
     * retention class) and states the capability's state. ADR-0009 §5's rule applies to the caller too — a
     * refusal is a claim, and the artefact rows still standing are the evidence.
     *
     * <p>The counters are computed so an operator can see what enabling it <em>would</em> remove, which is
     * the number a rehearsal needs.
     */
    @Transactional
    @RequirePermission(Permission.ORGANIZATION_UPDATE)
    public PurgeRefusal purgeExpired(TenantContext caller, UUID projectId) {
        authorization.check(caller, Permission.ORGANIZATION_UPDATE);
        TenantScope scope = guard.scope(caller, projectId);
        LocalDateTime now = AuditAttribution.now();

        int artefacts = 0;
        int tombstones = 0;
        for (ExportJobRepository.ExportJobRow job : jobs.list(scope, projectId, null,
            com.firstelement.exports.ReportingExportsModule.Limits.EXPORT_JOB_PAGE)) {
            if (!windows.isPurgeable(job.retentionClass())) {
                // ADR-0009 §4 rule 4: an undeclared class is not purgeable. Not even when the purge is on.
                continue;
            }
            if ("COMPLETED".equals(job.status())) {
                artefacts++;
            } else if ("EXPIRED".equals(job.status()) || "DELETED".equals(job.status())) {
                tombstones++;
            }
        }

        String message = properties.getRetention().isPurgeEnabled()
            ? "Purge is enabled in configuration, which the startup guard refuses in every environment. "
                + "Enabling it is a deployment change with a rehearsed runbook, not a property flip."
            : "Artefact purge is disabled. ADR-0009 section 4 rule 3 ships it disabled and rule 4 refuses a "
                + "purge of any table whose retention-class column is not populated. Nothing was removed.";

        audit(caller, scope, RETENTION_PURGE_REFUSED, "ExportJob", null,
            AuditVocabularies.AuditOutcome.DENIED, java.util.Map.of(
                "artefactsEligible", String.valueOf(artefacts),
                "tombstonesEligible", String.valueOf(tombstones),
                "reason", "PURGE_DISABLED"));
        log.warn("export purge refused tenant={} project={} artefactsEligible={} tombstonesEligible={}; "
            + "nothing was removed", caller.organizationId(), projectId, artefacts, tombstones);

        return new PurgeRefusal("PURGE_DISABLED", message, artefacts, tombstones, false);
    }

    // ------------------------------------------------------------------
    // operator-driven single deletion
    // ------------------------------------------------------------------

    /**
     * Deletes one completed or expired job's object and tombstones the row.
     *
     * <p>The one deletion path that is enabled, because an operator asking to remove a specific customer
     * export is a privacy action with a human behind it — not a scheduled sweep. It goes through the same
     * legal-hold gate and the same "delete the object first" ordering as {@link #expireDue}.
     */
    @Transactional
    @RequirePermission(Permission.ORGANIZATION_UPDATE)
    public DeletionOutcome deleteArtefact(TenantContext caller, UUID jobId, String reason) {
        authorization.check(caller, Permission.ORGANIZATION_UPDATE);
        TenantScope scope = guard.tenantScope(caller);
        ExportJobRepository.ExportJobRow job = requireTenantJob(scope, caller, jobId);
        LocalDateTime now = AuditAttribution.now();

        LegalHoldService.PurgeGate gate = legalHolds.gate(caller, job.projectId());
        if (!gate.mayPurge()) {
            events.recordDeletion(scope, job.projectId(), job.id(),
                ExportEventRepository.DeletionOutcome.STORAGE_DELETE_FAILED, job.storageKey(), null,
                caller.userId(), "LEGAL_HOLD_ACTIVE",
                "A legal hold covers this project's exports, so the artefact was NOT deleted. Reason given: "
                    + safeReason(reason),
                true, gate.blockingHolds().isEmpty() ? null
                    : gate.blockingHolds().get(0).id().toString(),
                job.correlationId(), now);
            audit(caller, scope, ARTEFACT_DELETED, "ExportJob", job.id(),
                AuditVocabularies.AuditOutcome.DENIED, java.util.Map.of(
                    "reason", gate.refusalReason(),
                    "blockingHolds", String.valueOf(gate.blockingCount())));
            return new DeletionOutcome("REFUSED", gate.refusalReason(), 0L, false);
        }

        long reclaimed = job.bytes() == null ? 0L : job.bytes();
        if (!store.delete(job.storageKey())) {
            events.recordDeletion(scope, job.projectId(), job.id(),
                ExportEventRepository.DeletionOutcome.STORAGE_DELETE_FAILED, job.storageKey(), null,
                caller.userId(), "STORAGE_DELETE_FAILED",
                "The store could not confirm removal, so the row was NOT tombstoned: a DELETED row that "
                    + "still points at a live object is exactly the state this design refuses.",
                true, null, job.correlationId(), now);
            audit(caller, scope, ARTEFACT_DELETED, "ExportJob", job.id(),
                AuditVocabularies.AuditOutcome.FAILED, java.util.Map.of("reason", "STORAGE_DELETE_FAILED"));
            return new DeletionOutcome("FAILED", "STORAGE_DELETE_FAILED", 0L, false);
        }

        events.recordDeletion(scope, job.projectId(), job.id(),
            ExportEventRepository.DeletionOutcome.STORAGE_OBJECT_DELETED, job.storageKey(), reclaimed,
            caller.userId(), null, null, true, null, job.correlationId(), now);

        if (!jobs.tombstone(scope, job.id(), caller.userId(), now)) {
            log.warn("an export object was deleted for job {} but the row did not tombstone; its state is "
                + "{}", job.id(), job.status());
            return new DeletionOutcome("PARTIAL", "ROW_TOMBSTONE_FAILED", reclaimed, false);
        }
        events.recordDeletion(scope, job.projectId(), job.id(),
            ExportEventRepository.DeletionOutcome.ROW_TOMBSTONED, null, null, caller.userId(), null, null,
            true, null, job.correlationId(), now);
        audit(caller, scope, ARTEFACT_DELETED, "ExportJob", job.id(),
            AuditVocabularies.AuditOutcome.SUCCESS, java.util.Map.of(
                "bytesReclaimed", String.valueOf(reclaimed),
                "reason", safeReason(reason)));
        log.info("export artefact deleted job={} bytesReclaimed={}", job.id(), reclaimed);
        return new DeletionOutcome("DELETED", "OK", reclaimed, true);
    }

    /** One deletion's outcome. */
    public record DeletionOutcome(String outcome, String code, long bytesReclaimed,
                                  boolean objectRemoved) {

        /** Whether the object is confirmed gone. The only claim this module makes about storage. */
        public boolean confirmed() {
            return objectRemoved;
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private ExportJobRepository.ExportJobRow requireTenantJob(TenantScope scope, TenantContext caller,
                                                              UUID jobId) {
        ExportJobRepository.ExportJobRow job = jobs.findForDownload(scope, jobId)
            .orElseThrow(() -> PolicyDeniedException.tenantOutOfScope("export-job-out-of-scope",
                caller.organizationId(), jobId));
        if (!caller.hasProject(job.projectId())) {
            throw PolicyDeniedException.projectOutOfScope("project-out-of-scope", caller.organizationId(),
                job.projectId());
        }
        return job;
    }

    private void audit(TenantContext caller, TenantScope scope, String action, String resourceType,
                       UUID resourceId, AuditVocabularies.AuditOutcome outcome,
                       java.util.Map<String, Object> metadata) {
        audit.adminAction(scope, caller, AuditWrite.builder(caller.organizationId())
            .action(action)
            .resourceType(resourceType)
            .resourceId(resourceId == null ? "" : resourceId.toString())
            .outcome(outcome)
            .metadata(metadata));
    }

    /** A reason, bounded, for the audit metadata. Never a customer value. */
    private static String safeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "no reason given";
        }
        String trimmed = reason.trim();
        return trimmed.length() <= 300 ? trimmed : trimmed.substring(0, 300) + "…";
    }
}