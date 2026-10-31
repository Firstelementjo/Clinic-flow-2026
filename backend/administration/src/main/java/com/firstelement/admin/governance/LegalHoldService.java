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
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The legal-hold lifecycle, and the one gate every purge has to pass.
 *
 * <h2>Why placing and releasing a hold is an Admin action at all</h2>
 *
 * <p>ADR-0008 rule 5 makes hold state a <em>precondition</em> of a purge, and a precondition nobody
 * can read is a precondition nobody can satisfy. So Phase 7's obligation here is to expose hold
 * state — which the read model does — and to give an operator the two typed acts that change it.
 * Both are narrow by construction: the scope is one of four declared values, the reason is required
 * by the schema, and the actor comes from the resolved {@link TenantContext}.
 *
 * <h2>Both acts are append-only plus a mutable row</h2>
 *
 * <p>{@code V20261005050000} is explicit that the mutable {@code legal_holds} row answers "is it held
 * now" and only the {@code legal_hold_events} history answers "was it ever". A regulator asks the
 * second question, so both are written, in the same transaction, and neither is written without the
 * other.
 *
 * <h2>The release is guarded twice</h2>
 *
 * <p>On {@code status = 'ACTIVE'} <em>and</em> on the version the caller read. The status guard makes
 * a second release a no-op; the version guard makes a concurrent release by a different operator lose
 * rather than silently overwrite. A caller that loses gets the hold back with its current version,
 * so the UI can show the state that actually exists instead of the one it assumed.
 */
@Service
public class LegalHoldService {

    private static final Logger log = LoggerFactory.getLogger(LegalHoldService.class);

    private final LegalHoldRepository holds;
    private final AdminAuditStreamWriter audit;
    private final TenantQueryGuard guard;

    public LegalHoldService(LegalHoldRepository holds,
                            AdminAuditStreamWriter audit,
                            TenantQueryGuard guard) {
        this.holds = holds;
        this.audit = audit;
        this.guard = guard;
    }

    /** What a placement or a release did, including what it refused to do. */
    public record HoldOutcome(boolean applied,
                              String outcome,
                              UUID holdId,
                              int activeHoldsCovering,
                              String message) {
    }

    /** The four scopes the schema declares. Anything else is not a hold. */
    public enum HoldScopeType {
        /** The whole tenant. {@code projectId} must be null. */
        TENANT,
        /** One project. {@code projectId} is required by the schema's pair CHECK. */
        PROJECT,
        /** One table, named in {@code scopeRef}. */
        TABLE,
        /** One record, named in {@code scopeRef}. */
        RECORD
    }

    /**
     * Places a hold.
     *
     * @param scope    TENANT or PROJECT: {@code projectId} follows from it. TABLE/RECORD additionally
     *                 require {@code scopeRef}, which the schema's {@code _scope_ref_check} refuses
     *                 to be blank.
     * @param projectId the project, for a PROJECT-scoped hold; ignored otherwise
     */
    @Transactional
    public HoldOutcome place(TenantContext caller,
                             HoldScopeType scope,
                             UUID projectId,
                             String scopeRef,
                             String reason,
                             LocalDateTime expiresAt) {
        TenantScope tenantScope = guard.tenantScope(caller);
        UUID actor = requireHumanActor(caller, "place a legal hold");
        String trimmedReason = requireReason(reason);
        String trimmedRef = scope == HoldScopeType.TENANT ? null : requireScopeRef(scope, scopeRef);
        UUID effectiveProject = scope == HoldScopeType.PROJECT ? projectId : null;
        if (scope == HoldScopeType.PROJECT && effectiveProject == null) {
            // A PROJECT hold with no project holds nothing, and the schema refuses it. Saying so
            // here names the operator mistake instead of surfacing a constraint violation.
            throw new IllegalArgumentException(
                "A PROJECT-scoped legal hold must name the project it covers; a hold with no scope "
                    + "would hold nothing at all.");
        }
        if (effectiveProject != null && !caller.hasProject(effectiveProject)) {
            throw PolicyDeniedException.projectOutOfScope("project-out-of-scope",
                caller.organizationId(), effectiveProject);
        }

        LocalDateTime at = AuditAttribution.now();
        UUID holdId = UUID.randomUUID();
        holds.insertHold(tenantScope, holdId, scope.name(), effectiveProject, trimmedRef,
            trimmedReason, actor, at, expiresAt);
        holds.insertEvent(tenantScope, UUID.randomUUID(), holdId, effectiveProject, "PLACED", actor,
            caller.auditActorLabel(), trimmedReason, at);

        int covering = holds.activeCountCovering(tenantScope, effectiveProject);
        audit.adminAction(tenantScope, caller, AuditWrite.builder(caller.organizationId())
            .action("legal-hold.place")
            .resourceType("LegalHold")
            .resourceId(holdId.toString())
            .outcome(AuditVocabularies.AuditOutcome.SUCCESS)
            .metadata(java.util.Map.of(
                "scopeType", scope.name(),
                "projectId", effectiveProject == null ? "" : effectiveProject.toString(),
                "activeHoldsCovering", String.valueOf(covering))));
        log.info("legal hold placed id={} scope={} covering={} by {}",
            holdId, scope, covering, caller.auditActorLabel());
        return new HoldOutcome(true, "PLACED", holdId, covering,
            "The hold is active and any purge covering this scope must refuse.");
    }

    /**
     * Releases a hold.
     *
     * <p>Never anonymous: {@code legal_holds_released_actor_check} refuses a RELEASED row without an
     * actor and a reason, and this refuses it earlier with a message that names the operator.
     */
    @Transactional
    public HoldOutcome release(TenantContext caller, UUID holdId, String releaseReason,
                               int expectedVersion) {
        TenantScope tenantScope = guard.tenantScope(caller);
        UUID actor = requireHumanActor(caller, "release a legal hold");
        String trimmedReason = requireReason(releaseReason);
        LegalHoldRepository.HoldRow hold = holds.find(tenantScope, holdId)
            .orElseThrow(() -> PolicyDeniedException.tenantOutOfScope("legal-hold-out-of-scope",
                caller.organizationId(), holdId));
        if (!"ACTIVE".equals(hold.status())) {
            return auditedNoop(caller, tenantScope, holdId, "ALREADY_RELEASED",
                "The hold was already released; nothing was written.", hold);
        }

        LocalDateTime at = AuditAttribution.now();
        boolean released = holds.release(tenantScope, holdId, expectedVersion, actor, trimmedReason,
            at);
        if (!released) {
            return auditedNoop(caller, tenantScope, holdId, "CONCURRENTLY_CHANGED",
                "The hold changed while this request was in flight; re-read it and try again.",
                hold);
        }
        holds.insertEvent(tenantScope, UUID.randomUUID(), holdId, hold.projectId(), "RELEASED", actor,
            caller.auditActorLabel(), trimmedReason, at);
        int covering = holds.activeCountCovering(tenantScope, hold.projectId());
        audit.adminAction(tenantScope, caller, AuditWrite.builder(caller.organizationId())
            .action("legal-hold.release")
            .resourceType("LegalHold")
            .resourceId(holdId.toString())
            .outcome(AuditVocabularies.AuditOutcome.SUCCESS)
            .metadata(java.util.Map.of(
                "scopeType", hold.scopeType(),
                "activeHoldsCovering", String.valueOf(covering),
                "expectedVersion", String.valueOf(expectedVersion))));
        log.info("legal hold released id={} covering={} by {}", holdId, covering,
            caller.auditActorLabel());
        return new HoldOutcome(true, "RELEASED", holdId, covering,
            "The hold is released. The history records who released it and when.");
    }

    /** The active holds covering a purge scope, with the count the purge gate uses. */
    public PurgeGate gate(TenantContext caller, UUID projectId) {
        TenantScope scope = guard.tenantScope(caller);
        List<LegalHoldRepository.HoldRow> covering = holds.activeHoldsCovering(scope, projectId,
            AdministrationModule.Limits.GOVERNANCE_PAGE);
        // mayPurge is the absence of a covering hold. Inverting this here would report the gate as
        // open while a hold is active — the exact failure ADR-0008 rule 5 exists to prevent.
        return new PurgeGate(caller.organizationId(), projectId, covering.isEmpty(), covering,
            covering.size());
    }

    /**
     * The answer ADR-0008 rule 5 makes every purge ask first.
     *
     * @param mayPurge {@code false} when at least one active hold covers the scope
     */
    public record PurgeGate(UUID organizationId,
                            UUID projectId,
                            boolean mayPurge,
                            List<LegalHoldRepository.HoldRow> blockingHolds,
                            int blockingCount) {

        /**
         * The reason a purge refused.
         *
         * <p>Named after ADR-0008 rule 5 so a log line, a metric and a support conversation all use
         * the same word. It is a claim; the rows beside it are the evidence.
         */
        public String refusalReason() {
            return mayPurge ? "LEGAL_HOLD_CLEAR" : "LEGAL_HOLD_ACTIVE";
        }
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private HoldOutcome auditedNoop(TenantContext caller, TenantScope scope, UUID holdId,
                                    String outcome, String message,
                                    LegalHoldRepository.HoldRow hold) {
        // A refused no-op is still audited. "I clicked release and nothing happened" is the moment an
        // operator most needs a record, and a silent no-op is indistinguishable from a lost request.
        audit.adminAction(scope, caller, AuditWrite.builder(caller.organizationId())
            .action("legal-hold.release")
            .resourceType("LegalHold")
            .resourceId(holdId.toString())
            .outcome(AuditVocabularies.AuditOutcome.DENIED)
            .metadata(java.util.Map.of(
                "outcome", outcome,
                "status", hold.status(),
                "message", message)));
        return new HoldOutcome(false, outcome, holdId, 0, message);
    }

    private static UUID requireHumanActor(TenantContext caller, String what) {
        if (caller == null || caller.userId() == null) {
            // The schema requires actorUserId for the composite tenant FK on a hold, and an
            // unattributable hold cannot be defended to a regulator.
            throw new IllegalStateException(
                "Only an authenticated user may " + what + "; a machine principal has no user id to "
                    + "record in placedByUserId/releasedByUserId.");
        }
        return caller.userId();
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            // legal_holds_reason_check: "Legal hold" with no reason cannot be defended and cannot be
            // reviewed for release.
            throw new IllegalArgumentException("A legal hold must state a reason.");
        }
        String trimmed = reason.trim();
        return trimmed.length() <= 500 ? trimmed : trimmed.substring(0, 500) + "…";
    }

    private static String requireScopeRef(HoldScopeType scope, String scopeRef) {
        if ((scope == HoldScopeType.TABLE || scope == HoldScopeType.RECORD)
            && (scopeRef == null || scopeRef.isBlank())) {
            throw new IllegalArgumentException(
                "A " + scope.name().toLowerCase(Locale.ROOT) + "-scoped hold must name what it holds "
                    + "in scopeRef; the schema refuses a blank one.");
        }
        return scopeRef == null ? null : scopeRef.trim();
    }
}