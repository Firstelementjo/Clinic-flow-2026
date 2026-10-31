package com.firstelement.finance.period;

import com.firstelement.admin.audit.AdminAuditStreamWriter;
import com.firstelement.admin.audit.AuditAttribution;
import com.firstelement.finance.money.Money;
import com.firstelement.security.TenantContext;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.permission.AuthorizationService;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.permission.PolicyResource;
import com.firstelement.security.query.TenantQueryGuard;
import com.firstelement.security.query.TenantScope;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only way a closed period's figures change.
 *
 * <h2>The chain, in full</h2>
 *
 * <p>ADR 0007 rule 4: "A closed period changes only through an approved, audited correction that links
 * to source events." That is one sentence and it is four requirements, each enforced here:
 *
 * <ol>
 *   <li><b>links to source events</b> — {@code adjustmentOfId} names a {@code usage_events} row through
 *       a <em>composite tenant</em> foreign key, so one tenant's adjustment can never correct another
 *       tenant's cost. A period-level correction may omit it, but must then carry a reason, because an
 *       adjustment with neither a target nor a reason is untraceable.</li>
 *   <li><b>approved</b> — {@code PENDING → APPROVED} by somebody who is not the author.
 *       {@code finance_adjustments_not_self_approved_check} refuses a self-approval and this class
 *       refuses it first, so the error names the rule rather than arriving as a constraint
 *       violation.</li>
 *   <li><b>audited</b> — every transition writes a {@code finance_period_lock_events} row
 *       ({@code ADJUSTMENT_APPROVED} / {@code ADJUSTMENT_REJECTED}) <em>and</em> an
 *       {@code admin_audit_events} row, plus the approval note and timestamp on the adjustment
 *       itself.</li>
 *   <li><b>closed first</b> — an adjustment applies to a {@code REOPENED} period, never to a
 *       {@code LOCKED} one. This is the ordering that makes rule 4 true: the period stops moving, a
 *       reason is recorded, and only then can the correction land. Applying to a locked period is
 *       refused by the database trigger on
 *       {@code usage_rollup_snapshots}, and refused here with a sentence.</li>
 * </ol>
 *
 * <h2>Why an adjustment is not a revenue override</h2>
 *
 * <p>Because it is a correction to a cost, not a price. {@code finance_adjustments.amount} is an
 * amount in a currency and it adjusts the <em>cost</em> the period was computed from; the rate card
 * then marks that cost up as usual. A scheme that let an adjustment state a revenue figure directly
 * would be a second, unaudited pricing mechanism sitting beside the rate cards — exactly what ADR 0007
 * rule 2 forbids. There is no {@code revenue} column on the table and there is no way to write one.
 *
 * <h2>Why approval and authorship must differ</h2>
 *
 * <p>{@code finance_adjustments_not_self_approved_check} says so, and plan.md forbids an unrestricted
 * identity. In a finance context the practical risk is not impersonation, it is that a single person
 * creates and approves a correction to their own figure and nobody else ever sees it. The CHECK is the
 * minimum; the audit rows and the required approval note are what make it reviewable.
 */
@Service
public class AdjustmentService {

    private final FinancePeriodRepository periods;
    private final AdminAuditStreamWriter audit;
    private final TenantQueryGuard guard;
    private final AuthorizationService authorization;

    public AdjustmentService(FinancePeriodRepository periods, AdminAuditStreamWriter audit,
                             TenantQueryGuard guard, AuthorizationService authorization) {
        this.periods = periods;
        this.audit = audit;
        this.guard = guard;
        this.authorization = authorization;
    }

    // ------------------------------------------------------------------
    // reads
    // ------------------------------------------------------------------

    /** A period's adjustments. */
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    public List<FinancePeriodRepository.AdjustmentRow> list(TenantContext caller, UUID periodId,
                                                            String status, int limit) {
        requireOrganizationAnalytics(caller);
        return periods.adjustments(guard.tenantScope(caller), periodId, status,
            bounded(limit));
    }

    /** One adjustment. */
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    public Optional<FinancePeriodRepository.AdjustmentRow> find(TenantContext caller,
                                                               UUID adjustmentId) {
        requireOrganizationAnalytics(caller);
        return periods.adjustment(guard.tenantScope(caller), adjustmentId);
    }

    /**
     * The usage events an adjustment corrects.
     *
     * <p>Empty for a period-level correction and empty for a foreign event id — the same answer for
     * both, so a caller cannot use this to discover whether another tenant's event exists.
     */
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    public Optional<FinancePeriodRepository.SourceEventRow> sourceEvent(TenantContext caller,
                                                                       UUID eventId) {
        requireOrganizationAnalytics(caller);
        return periods.sourceEvent(guard.tenantScope(caller), eventId);
    }

    // ------------------------------------------------------------------
    // the correction path
    // ------------------------------------------------------------------

    /**
     * Raises a PENDING adjustment against a period.
     *
     * @param adjustmentOfId the usage event it corrects, or {@code null} for a period-level
     *                       correction; {@code reason} is required either way
     * @param amount         the signed correction to the cost. Must be non-zero:
     *                       {@code finance_adjustments_amount_check} refuses a zero, because a zero
     *                       adjustment is a record of having done nothing, which is a cancelled
     *                       request
     */
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    @Transactional
    public UUID propose(TenantContext caller, UUID periodId, UUID adjustmentOfId, String reason,
                        BigDecimal amount, String currency, BigDecimal quantity) {
        requireFinanceAdmin(caller);
        TenantScope scope = guard.tenantScope(caller);
        FinancePeriodRepository.PeriodRow period = requirePeriod(scope, periodId);
        if (!period.statusEnum().isClosed()) {
            throw new IllegalStateException(
                "An adjustment is a correction to a CLOSED period; " + periodId + " is "
                    + period.status() + ". Usage in an open period is corrected by the late-arrival "
                    + "path or by recomputing the bucket, not by an adjustment.");
        }
        if (amount == null || amount.signum() == 0) {
            throw new IllegalArgumentException(
                "finance_adjustments_amount_check refuses a zero adjustment. A record of having done "
                    + "nothing is a cancelled request, not a correction.");
        }
        if (adjustmentOfId != null
            && periods.sourceEvent(scope, adjustmentOfId).isEmpty()) {
            // Empty for a foreign id as well as a nonexistent one, and the refusal says neither which.
            throw new IllegalArgumentException(
                "adjustmentOfId " + adjustmentOfId + " names no usage event in this tenant. The "
                    + "composite foreign key finance_adjustments_event_tenant_fkey would refuse the "
                    + "write anyway; refusing here keeps the message from disclosing whether another "
                    + "tenant has that event.");
        }
        UUID authorId = requireHuman(caller);
        UUID id = periods.insertAdjustment(scope, period.projectId(), periodId, adjustmentOfId,
            reason, amount, currency == null ? period.currency() : currency, quantity, authorId);
        writeAdminAudit(caller, scope, "FINANCE_ADJUSTMENT_PROPOSE", "finance_adjustments", id,
            "period " + period.key() + ": " + reason);
        return id;
    }

    /**
     * Approves a PENDING adjustment. Must not be its own author.
     *
     * <p>Approval alone does not move any figure: {@link #apply} is a separate, separately audited step.
     * That separation is deliberate — approving a correction and applying it are different acts with
     * different failure modes, and combining them means a single mistaken click changes a closed
     * period's numbers with no intermediate state anybody could inspect.
     */
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    @Transactional
    public void approve(TenantContext caller, UUID adjustmentId, String approvalNote) {
        requireFinanceAdmin(caller);
        TenantScope scope = guard.tenantScope(caller);
        FinancePeriodRepository.AdjustmentRow adjustment = requireAdjustment(scope, adjustmentId);
        UUID approverId = requireHuman(caller);
        if (approverId.equals(adjustment.createdByUserId())) {
            throw new AccessDeniedException(
                "finance_adjustments_not_self_approved_check: the approver must not be the author. A "
                    + "correction to closed figures that its own author approved is the failure mode "
                    + "that constraint exists to prevent.");
        }
        if (!"PENDING".equals(adjustment.status())) {
            throw new IllegalStateException(
                "Only a PENDING adjustment can be approved; " + adjustmentId + " is "
                    + adjustment.status() + ".");
        }
        int updated = periods.approveAdjustment(scope, adjustmentId, approverId, approvalNote);
        if (updated == 0) {
            throw new IllegalStateException(
                "Adjustment " + adjustmentId + " was not PENDING when the approval landed. "
                    + "finance_adjustments_state_decision_check would have refused the write; this "
                    + "message names the actual cause.");
        }
        periods.appendLockEvent(scope, adjustment.periodId(),
            PeriodLockEventType.ADJUSTMENT_APPROVED, approverId, caller.auditActorLabel(),
            "approved adjustment " + adjustmentId + ": "
                + (approvalNote == null || approvalNote.isBlank()
                    ? adjustment.reason() : approvalNote));
        writeAdminAudit(caller, scope, "FINANCE_ADJUSTMENT_APPROVE", "finance_adjustments",
            adjustmentId, "approved by " + approverId);
    }

    /** Rejects a PENDING adjustment, with a reason. */
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    @Transactional
    public void reject(TenantContext caller, UUID adjustmentId, String rejectionReason) {
        requireFinanceAdmin(caller);
        TenantScope scope = guard.tenantScope(caller);
        FinancePeriodRepository.AdjustmentRow adjustment = requireAdjustment(scope, adjustmentId);
        UUID rejecterId = requireHuman(caller);
        int updated = periods.rejectAdjustment(scope, adjustmentId, rejecterId, rejectionReason);
        if (updated == 0) {
            throw new IllegalStateException(
                "Adjustment " + adjustmentId + " is " + adjustment.status() + ", not PENDING; a "
                    + "decided adjustment cannot be decided again.");
        }
        periods.appendLockEvent(scope, adjustment.periodId(),
            PeriodLockEventType.ADJUSTMENT_REJECTED, rejecterId, caller.auditActorLabel(),
            "rejected adjustment " + adjustmentId + ": " + rejectionReason);
        writeAdminAudit(caller, scope, "FINANCE_ADJUSTMENT_REJECT", "finance_adjustments",
            adjustmentId, "rejected by " + rejecterId + ": " + rejectionReason);
    }

    /**
     * Applies an APPROVED adjustment to its period's figures.
     *
     * <p>Requires the period to be {@code REOPENED}. A {@code LOCKED} period refuses this in the
     * database — {@code usage_rollup_snapshots_locked_period_guard} raises on any snapshot insert naming
     * a locked period — and refusing it here with a sentence is what turns that constraint into a
     * workflow an operator can follow: reopen, apply, re-lock.
     *
     * <p>Marks the adjustment {@code APPLIED} and records who and when.
     * {@code finance_adjustments_applied_requires_approved_check} requires {@code approvedAt} to be set
     * first, so an unapproved correction cannot reach this line.
     *
     * @return the adjustment's cost amount as a {@link Money}, which is what the caller recomputes with
     */
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    @Transactional
    public Money apply(TenantContext caller, UUID adjustmentId) {
        requireFinanceAdmin(caller);
        TenantScope scope = guard.tenantScope(caller);
        FinancePeriodRepository.AdjustmentRow adjustment = requireAdjustment(scope, adjustmentId);
        if (!"APPROVED".equals(adjustment.status())) {
            throw new IllegalStateException(
                "Only an APPROVED adjustment may be applied; " + adjustmentId + " is "
                    + adjustment.status() + ". finance_adjustments_applied_requires_approved_check "
                    + "requires approvedAt, and approval is a separate, separately audited step.");
        }
        FinancePeriodRepository.PeriodRow period = requirePeriod(scope, adjustment.periodId());
        if (period.statusEnum() != PeriodStatus.REOPENED) {
            throw new IllegalStateException(
                "An adjustment applies to a REOPENED period; " + period.id() + " is "
                    + period.status() + ". ADR 0007 rule 4: a closed period changes only through an "
                    + "approved, audited correction -- so reopen it first (which is itself permissioned "
                    + "and audited), then apply, then re-lock.");
        }
        UUID applierId = requireHuman(caller);
        int updated = periods.applyAdjustment(scope, adjustmentId, applierId);
        if (updated == 0) {
            throw new IllegalStateException(
                "Adjustment " + adjustmentId + " was not APPROVED when the application landed.");
        }
        periods.appendLockEvent(scope, adjustment.periodId(),
            PeriodLockEventType.SNAPSHOT_REFRESHED, applierId, caller.auditActorLabel(),
            "applied adjustment " + adjustmentId + " (" + adjustment.amount().toPlainString() + " "
                + adjustment.currency() + "): " + adjustment.reason());
        writeAdminAudit(caller, scope, "FINANCE_ADJUSTMENT_APPLY", "finance_adjustments",
            adjustmentId, "applied to period " + period.key());
        return Money.of(adjustment.amount(), adjustment.currency());
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private FinancePeriodRepository.PeriodRow requirePeriod(TenantScope scope, UUID periodId) {
        return periods.period(scope, periodId).orElseThrow(() -> new IllegalArgumentException(
            "No finance period " + periodId + " in this tenant"));
    }

    private FinancePeriodRepository.AdjustmentRow requireAdjustment(TenantScope scope,
                                                                    UUID adjustmentId) {
        return periods.adjustment(scope, adjustmentId).orElseThrow(() ->
            new IllegalArgumentException("No finance adjustment " + adjustmentId + " in this tenant"));
    }

    private void requireOrganizationAnalytics(TenantContext caller) {
        if (caller == null) {
            throw new IllegalStateException(
                "A finance read requires a resolved TenantContext; refusing to run unscoped.");
        }
        authorization.check(caller, Permission.ANALYTICS_READ_ORGANIZATION, PolicyResource.NONE);
    }

    private void requireFinanceAdmin(TenantContext caller) {
        requireOrganizationAnalytics(caller);
        if (!caller.isAdmin()) {
            throw new AccessDeniedException(
                "Raising, approving and applying a finance adjustment requires the finance "
                    + "administrator capability set (an ADMIN role as well as "
                    + "analytics:read-organization). This principal is a " + caller.principalType() + ".");
        }
    }

    private UUID requireHuman(TenantContext caller) {
        if (!caller.isUser() || caller.userId() == null) {
            throw new AccessDeniedException(
                "A finance adjustment must be attributable to a person; every actor column is a "
                    + "composite tenant foreign key to users and the database would refuse a machine.");
        }
        return caller.userId();
    }

    private void writeAdminAudit(TenantContext caller, TenantScope scope, String action,
                                 String resourceType, UUID resourceId, String detail) {
        audit.adminAction(scope, caller,
            AuditAttribution.forActor(scope.organizationId(), caller)
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId.toString())
                .outcome(com.firstelement.admin.audit.AuditVocabularies.AuditOutcome.SUCCESS)
                .detail(detail));
    }

    private static int bounded(int limit) {
        return limit < 1 ? com.firstelement.finance.ReportingFinanceModule.Limits.ADJUSTMENT_PAGE
            : Math.min(limit, com.firstelement.finance.ReportingFinanceModule.Limits.ADJUSTMENT_PAGE);
    }
}
