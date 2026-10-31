package com.firstelement.admin.governance;

import com.firstelement.admin.AdministrationModule;
import com.firstelement.admin.audit.AdminAuditStreamWriter;
import com.firstelement.admin.audit.AuditAttribution;
import com.firstelement.admin.audit.AuditVocabularies;
import com.firstelement.admin.audit.AuditWrite;
import com.firstelement.security.TenantContext;
import com.firstelement.security.error.PolicyDeniedException;
import com.firstelement.security.query.TenantQueryGuard;
import com.firstelement.security.query.TenantScope;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The typed privacy-deletion request: the only destructive-adjacent write in this module.
 *
 * <h2>What "no silent destructive action" is made to mean here</h2>
 *
 * <p>The phase rule is that tenant data deletion requires "a typed request, an impact count, a
 * policy check, a retention/legal-hold check, and an audit record". Each of those five is a
 * separate, visible step in {@link #request} and each one has a test that asserts it:
 *
 * <ol>
 *   <li><b>Typed request.</b> {@link #request} takes a subject, a subject type and a reason. There
 *       is no free-form target list and no "delete everything for this tenant" verb.</li>
 *   <li><b>Impact count.</b> {@link DeletionImpactProbe} counts, per table, before anything is
 *       written, and the count is recorded on the audit row and returned to the operator. A request
 *       whose impact is zero is still accepted and recorded — "nothing to delete" and "not
 *       measured" must stay distinct.</li>
 *   <li><b>Policy check.</b> The subject must be a user of the caller's own organization, checked
 *       through the tenant scope rather than assumed from the request body; a cross-tenant subject
 *       produces the same non-disclosing denial as a nonexistent one.</li>
 *   <li><b>Retention/legal-hold check.</b> {@link LegalHoldService#gate} answers before the request
 *       is written, and every active hold covering the scope becomes a
 *       {@code RETAINED_LEGAL_HOLD} target row naming the hold. "We kept this because a hold
 *       existed" is therefore a row, not a silence.</li>
 *   <li><b>Audit record.</b> One {@code admin_audit_events} row carrying the impact total, the hold
 *       count and the request id — written whether the request was accepted or refused.</li>
 * </ol>
 *
 * <h2>What it does not do</h2>
 *
 * <p>It removes nothing. {@code privacy_deletion_requests} starts at {@code REQUESTED}, and the
 * transitions to {@code VERIFIED}/{@code IN_PROGRESS}/{@code COMPLETED} belong to a durable job that
 * ships disabled (ADR-0009 §4 rule 3). The schema's own
 * {@code privacy_deletion_requests_start_requires_verified_check} refuses a request that started
 * without a verification, so the order is enforced in the database as well as here.
 *
 * <h2>Idempotency</h2>
 *
 * <p>A second identical request is a second request, not a duplicate: two people asking for the same
 * deletion is two decisions, and collapsing them would lose one of the two records of who asked.
 * What is idempotent is the <em>execution</em>, which does not exist yet, and the hold decision,
 * which is written per covering hold and is bounded by the hold count.
 */
@Service
public class PrivacyDeletionService {

    private static final Logger log = LoggerFactory.getLogger(PrivacyDeletionService.class);

    /** The subject types the schema's {@code privacy_deletion_requests_subject_type_check} allows. */
    public enum SubjectType {
        CUSTOMER,
        USER,
        EMPLOYEE,
        TENANT;

        static SubjectType of(String value) {
            for (SubjectType type : values()) {
                if (type.name().equalsIgnoreCase(value)) {
                    return type;
                }
            }
            throw new IllegalArgumentException(
                "subjectType must be one of CUSTOMER, USER, EMPLOYEE or TENANT; got: " + value);
        }

        /**
         * The {@code privacy_deletion_targets_target_type_check} spelling for a retained subject.
         *
         * <p>The two vocabularies are deliberately different: {@code privacy_deletion_requests} names
         * <em>whose</em> data is in scope, and {@code privacy_deletion_targets} names <em>what class of
         * row</em> a decision applies to. CUSTOMER, USER and EMPLOYEE appear in both.
         *
         * <p>{@code TENANT} has no counterpart in the target vocabulary — the schema offers no
         * tenant-wide target type — so a tenant-scoped request under a covering hold cannot name its
         * retained row. That is refused here with a message naming the reason rather than inventing a
         * target type the schema never declared. Reported to the orchestrator as a schema gap.
         */
        String asTargetType() {
            return switch (this) {
                case CUSTOMER, USER, EMPLOYEE -> name();
                case TENANT -> throw new IllegalArgumentException(
                    "privacy_deletion_targets declares no TENANT target type, so a tenant-scoped "
                        + "request cannot name a retained row; the covering hold is still visible on "
                        + "the legal-hold read model and in the purge gate.");
            };
        }
    }

    private final PrivacyDeletionRepository deletions;
    private final DeletionImpactProbe impact;
    private final LegalHoldService holds;
    private final AdminAuditStreamWriter audit;
    private final TenantQueryGuard guard;

    public PrivacyDeletionService(PrivacyDeletionRepository deletions,
                                  DeletionImpactProbe impact,
                                  LegalHoldService holds,
                                  AdminAuditStreamWriter audit,
                                  TenantQueryGuard guard) {
        this.deletions = deletions;
        this.impact = impact;
        this.holds = holds;
        this.audit = audit;
        this.guard = guard;
    }

    /** What a request did, and everything the operator needs to show a reviewer. */
    public record DeletionRequestOutcome(boolean accepted,
                                         String outcome,
                                         UUID requestId,
                                         UUID subjectUserId,
                                         DeletionImpactProbe.Impact impact,
                                         LegalHoldService.PurgeGate legalHoldGate,
                                         int retainedTargets,
                                         String message) {
    }

    /**
     * Measures without writing. Safe to call from a UI as the operator types.
     *
     * <p>Returns the impact and the hold gate together, because "what would this delete" and "what
     * would stop it" are the same question and an operator who sees them separately will act on the
     * first one and discover the second one afterwards.
     */
    public DeletionRequestOutcome preview(TenantContext caller, UUID subjectUserId, UUID projectId) {
        TenantScope scope = guard.tenantScope(caller);
        requireHuman(caller, "assess a privacy deletion");
        if (!deletions.subjectInTenant(scope, subjectUserId)) {
            throw PolicyDeniedException.tenantOutOfScope("subject-out-of-scope",
                caller.organizationId(), subjectUserId);
        }
        DeletionImpactProbe.Impact measured = impact.count(guard, caller, subjectUserId);
        LegalHoldService.PurgeGate gate = holds.gate(caller, projectId);
        return new DeletionRequestOutcome(false, "PREVIEW", null, subjectUserId, measured, gate, 0,
            gate.mayPurge()
                ? "No active legal hold covers this scope. Opening a request still removes nothing."
                : "A legal hold covers this scope. A request opened now would record every covered "
                    + "target as RETAINED_LEGAL_HOLD.");
    }

    /**
     * Opens a typed deletion request.
     *
     * <p>Writes exactly three things: the {@code privacy_deletion_requests} row, one
     * {@code privacy_deletion_targets} row per covering legal hold, and one admin audit row. It
     * deletes nothing, and there is no flag that makes it delete something.
     *
     * @param reason why the deletion is being asked for; required by the schema and by any
     *               data-protection review that later asks why
     */
    @Transactional
    public DeletionRequestOutcome request(TenantContext caller,
                                          UUID subjectUserId,
                                          UUID projectId,
                                          String subjectType,
                                          String reason,
                                          String evidenceReference) {
        TenantScope scope = guard.tenantScope(caller);
        UUID actor = requireHuman(caller, "request a privacy deletion");
        SubjectType type = SubjectType.of(subjectType);
        String trimmedReason = requireReason(reason);
        String evidence = bound(evidenceReference);
        if (projectId != null && !caller.hasProject(projectId)) {
            throw PolicyDeniedException.projectOutOfScope("project-out-of-scope",
                caller.organizationId(), projectId);
        }
        if (!deletions.subjectInTenant(scope, subjectUserId)) {
            // Non-disclosing: a subject in another tenant and a subject that does not exist produce
            // the same denial, so this cannot be used to probe for user ids.
            throw PolicyDeniedException.tenantOutOfScope("subject-out-of-scope",
                caller.organizationId(), subjectUserId);
        }

        // (2) impact, measured before anything is written.
        DeletionImpactProbe.Impact measured = impact.count(guard, caller, subjectUserId);
        // (4) legal hold, asked before anything is written.
        LegalHoldService.PurgeGate gate = holds.gate(caller, projectId);

        LocalDateTime at = AuditAttribution.now();
        UUID requestId = UUID.randomUUID();
        deletions.insertRequest(scope, requestId, projectId, subjectUserId, type.name(),
            trimmedReason, actor, evidence, at);

        // (4, continued) every covering hold becomes a retained target row. Bounded by the hold
        // count, which is bounded by the governance page size.
        int retained = 0;
        int cap = AdministrationModule.Limits.DELETION_TARGET_CAP;
        for (LegalHoldRepository.HoldRow hold : gate.blockingHolds()) {
            if (retained >= cap) {
                log.warn("privacy deletion request {} hit the retained-target cap of {}; the "
                    + "remaining holds are still visible on the legal-hold read model",
                    requestId, cap);
                break;
            }
            deletions.insertTarget(scope, UUID.randomUUID(), requestId, projectId, type.asTargetType(),
                subjectUserId, "legal-hold:" + hold.scopeType() + ":" + hold.scopeRef(), null,
                "RETAINED_LEGAL_HOLD", actor, hold.id().toString(), at);
            retained++;
        }

        // (5) the audit record, carrying the numbers the reviewer needs.
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("requestId", requestId.toString());
        metadata.put("subjectType", type.name());
        metadata.put("projectId", projectId == null ? "" : projectId.toString());
        metadata.put("impactTotalRows", String.valueOf(measured.total()));
        metadata.put("legalHoldGate", gate.refusalReason());
        metadata.put("blockingHolds", String.valueOf(gate.blockingCount()));
        metadata.put("retainedTargets", String.valueOf(retained));
        metadata.put("removedRows", "0");
        audit.adminAction(scope, caller, AuditWrite.builder(caller.organizationId())
            .action("privacy-deletion.request")
            .resourceType("PrivacyDeletionRequest")
            .resourceId(requestId.toString())
            .outcome(AuditVocabularies.AuditOutcome.SUCCESS)
            .metadata(metadata));

        log.info("privacy deletion request opened id={} subject={} type={} impactRows={} "
                + "blockingHolds={} by {}",
            requestId, subjectUserId, type, measured.total(), gate.blockingCount(),
            caller.auditActorLabel());

        return new DeletionRequestOutcome(true, gate.mayPurge() ? "REQUESTED" : "REQUESTED_UNDER_HOLD",
            requestId, subjectUserId, measured, gate, retained,
            gate.mayPurge()
                ? "The request is recorded and nothing has been removed. Execution is a disabled job."
                : "The request is recorded, nothing has been removed, and " + retained
                    + " covered target(s) are recorded as RETAINED_LEGAL_HOLD.");
    }

    /** The outstanding queue, oldest first. */
    public List<PrivacyDeletionRepository.RequestRow> openQueue(TenantContext caller) {
        return deletions.openRequests(guard.tenantScope(caller),
            AdministrationModule.Limits.GOVERNANCE_PAGE);
    }

    /** What was decided for one request, tenant-scoped. */
    public List<PrivacyDeletionRepository.TargetRow> targets(TenantContext caller, UUID requestId) {
        return deletions.targets(guard.tenantScope(caller), requestId,
            AdministrationModule.Limits.DELETION_TARGET_CAP);
    }

    /** Outstanding counts by status, for the overview. */
    public Map<String, Integer> countsByStatus(TenantContext caller) {
        return deletions.countsByStatus(guard.tenantScope(caller));
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private static UUID requireHuman(TenantContext caller, String what) {
        if (caller == null || caller.userId() == null) {
            throw new IllegalStateException(
                "Only an authenticated user may " + what + "; a machine principal has no user id "
                    + "to record in requestedByUserId.");
        }
        return caller.userId();
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                "A privacy-deletion request must state why; privacy_deletion_requests_reason_check "
                    + "requires it and a data-protection review will ask.");
        }
        String trimmed = reason.trim();
        return trimmed.length() <= 1000 ? trimmed : trimmed.substring(0, 1000) + "…";
    }

    private static String bound(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= 500 ? trimmed : trimmed.substring(0, 500) + "…";
    }

    /** Kept for the log line's benefit: the subject type as an upper-case token. */
    static String token(SubjectType type) {
        return type.name().toUpperCase(Locale.ROOT);
    }
}