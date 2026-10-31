package com.firstelement.finance.period;

import com.firstelement.admin.audit.AdminAuditStreamWriter;
import com.firstelement.admin.audit.AuditAttribution;
import com.firstelement.admin.audit.AuditWrite;
import com.firstelement.finance.ledger.FinancePeriodClosedException;
import com.firstelement.security.TenantContext;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.permission.AuthorizationService;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.permission.PolicyResource;
import com.firstelement.security.query.TenantQueryGuard;
import com.firstelement.security.query.TenantScope;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The period lifecycle: review, lock, reopen. Every transition audited, twice.
 *
 * <h2>"Twice" is the point</h2>
 *
 * <p>Each transition writes <em>both</em> an append-only {@code finance_period_lock_events} row (the
 * finance audit trail, readable by anyone with organization analytics) <em>and</em> an
 * {@code admin_audit_events} row through {@link AdminAuditStreamWriter} (the administrative trail,
 * readable by administrators under {@code AUDIT_LOG_READ}). ADR-0005 §5 made the streams physically
 * separate for exactly this reason: a single table cannot be granted to a finance reviewer and to an
 * administrator without one of them seeing the other's rows.
 *
 * <h2>The state machine, and what it refuses</h2>
 *
 * <table>
 *   <caption>Transitions</caption>
 *   <tr><th>From</th><th>To</th><th>Who</th><th>Notes</th></tr>
 *   <tr><td>—</td><td>OPEN</td><td>ADMIN</td><td>creation</td></tr>
 *   <tr><td>OPEN</td><td>IN_REVIEW</td><td>ADMIN</td><td>the close run</td></tr>
 *   <tr><td>OPEN, IN_REVIEW</td><td>LOCKED</td><td>ADMIN</td>
 *       <td>approval; note required; figures are final</td></tr>
 *   <tr><td>LOCKED</td><td>REOPENED</td><td>ADMIN</td>
 *       <td>reason required; count incremented; figures may change again</td></tr>
 *   <tr><td>REOPENED</td><td>LOCKED</td><td>ADMIN</td><td>re-lock after a correction</td></tr>
 *   <tr><td>LOCKED, REOPENED</td><td>IN_REVIEW</td><td>ADMIN</td><td>back into the close run</td></tr>
 * </table>
 *
 * <p>Every transition requires an {@code ADMIN} role as well as
 * {@code ANALYTICS_READ_ORGANIZATION}. The permission alone is held by a {@code FINANCE_AGGREGATE}
 * worker, and a worker that could lock a period could also un-lock one — which would make the closed
 * period guarantee meaningless.
 *
 * <h2>Why {@code IN_REVIEW} stops new usage</h2>
 *
 * <p>It is not "locked", but it is being checked. {@link UsagePeriodResolver} treats only {@code OPEN}
 * as accepting, because a number that moves while somebody is verifying it produces a review that was
 * wrong when it was approved.
 */
@Service
public class PeriodLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(PeriodLifecycleService.class);

    private final FinancePeriodRepository periods;
    private final AdminAuditStreamWriter audit;
    private final TenantQueryGuard guard;
    private final AuthorizationService authorization;

    public PeriodLifecycleService(FinancePeriodRepository periods, AdminAuditStreamWriter audit,
                                  TenantQueryGuard guard, AuthorizationService authorization) {
        this.periods = periods;
        this.audit = audit;
        this.guard = guard;
        this.authorization = authorization;
    }

    // ------------------------------------------------------------------
    // reads
    // ------------------------------------------------------------------

    /** The tenant's periods, newest first. */
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    public List<FinancePeriodRepository.PeriodRow> list(TenantContext caller, String status,
                                                        int limit) {
        requireOrganizationAnalytics(caller);
        return periods.periods(guard.tenantScope(caller), status, bounded(limit));
    }

    /** One period. Absent when the id is foreign, so a foreign id and a nonexistent one agree. */
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    public Optional<FinancePeriodRepository.PeriodRow> find(TenantContext caller, UUID periodId) {
        requireOrganizationAnalytics(caller);
        return periods.period(guard.tenantScope(caller), periodId);
    }

    /** A period's lifecycle history, oldest first. */
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    public List<PeriodLockEventType.PeriodLockEvent> history(TenantContext caller, UUID periodId) {
        requireOrganizationAnalytics(caller);
        return periods.lockEvents(guard.tenantScope(caller), periodId,
            com.firstelement.finance.ReportingFinanceModule.Limits.PERIOD_EVENT_PAGE);
    }

    // ------------------------------------------------------------------
    // transitions
    // ------------------------------------------------------------------

    /** Creates a period in {@code OPEN}. */
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    @Transactional
    public UUID create(TenantContext caller, UUID projectId, String key, String nameAr, String nameEn,
                       LocalDate periodStart, LocalDate periodEnd, String currency) {
        requireFinanceAdmin(caller);
        TenantScope scope = guard.scope(caller, projectId);
        requireNonBlank(key, "finance_periods.key_check requires a non-blank key");
        requireNonBlank(nameAr, "finance_periods.name_ar_check requires a non-blank Arabic name");
        requireNonBlank(nameEn, "finance_periods.name_en_check requires a non-blank English name");
        if (periodEnd.isBefore(periodStart)) {
            throw new IllegalArgumentException(
                "finance_periods_range_check forbids periodEnd < periodStart; a period that ends before "
                    + "it begins contains nothing and would lock instantly.");
        }
        UUID id = periods.insertPeriod(scope, scope.projectId(), key, nameAr, nameEn, periodStart,
            periodEnd, currency);
        writeAdminAudit(caller, scope, "FINANCE_PERIOD_CREATE", "finance_periods", id,
            "period " + key + " " + periodStart + ".." + periodEnd + " " + currency);
        return id;
    }

    /**
     * Moves a period into review: the close run begins.
     *
     * <p>{@code finance_periods_review_check} is a biconditional, so {@code underReviewAt} is set here
     * and cleared on every transition out of review. See the class note.
     */
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    @Transactional
    public void submitForReview(TenantContext caller, UUID periodId) {
        requireFinanceAdmin(caller);
        TenantScope scope = guard.tenantScope(caller);
        FinancePeriodRepository.PeriodRow period = requirePeriod(scope, periodId);
        requireStatus(period, PeriodStatus.OPEN, "only an OPEN period can enter review");

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        periods.updateStatus(scope, periodId, PeriodStatus.IN_REVIEW, now,
            period.lockedAt(), period.lockedByUserId(), period.lockNote(),
            period.reopenedAt(), period.reopenedByUserId(), period.reopenReason(),
            period.reopenCount());
        periods.appendLockEvent(scope, periodId, PeriodLockEventType.UNDER_REVIEW,
            caller.userId(), principalName(caller), "close run started");
        writeAdminAudit(caller, scope, "FINANCE_PERIOD_UNDER_REVIEW", "finance_periods", periodId,
            "period " + period.key() + " entered review");
    }

    /**
     * Approves and locks a period. Its figures become final.
     *
     * <p>The note is mandatory because {@code finance_period_lock_events_note_check} requires it, and
     * because "we locked March" is not a statement anybody can review three months later.
     *
     * <p>Both the lock columns and, where the period has been reopened, the reopen columns are
     * rewritten. {@code finance_periods_locked_check} is one-way and the audit columns may outlive the
     * lock, so nothing is cleared here — a REOPENED period that is re-locked keeps its reopen reason,
     * which is what makes "was this period corrected?" answerable from the row.
     */
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    @Transactional
    public void lock(TenantContext caller, UUID periodId, String note) {
        requireFinanceAdmin(caller);
        requireNonBlank(note,
            "Locking a period requires a note. finance_period_lock_events_note_check requires one on a "
                + "LOCKED event, and a lock nobody can explain is indistinguishable from a mistake.");
        TenantScope scope = guard.tenantScope(caller);
        FinancePeriodRepository.PeriodRow period = requirePeriod(scope, periodId);
        if (period.statusEnum() == PeriodStatus.LOCKED) {
            throw new IllegalStateException(
                "Period " + periodId + " is already LOCKED. A second lock would be a second approval of "
                    + "the same figures; the figures have not changed since the first.");
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        periods.updateStatus(scope, periodId, PeriodStatus.LOCKED, null, now, caller.userId(), note,
            period.reopenedAt(), period.reopenedByUserId(), period.reopenReason(),
            period.reopenCount());
        periods.appendLockEvent(scope, periodId, PeriodLockEventType.LOCKED, caller.userId(),
            principalName(caller), note);
        writeAdminAudit(caller, scope, "FINANCE_PERIOD_LOCK", "finance_periods", periodId,
            "period " + period.key() + " locked: " + note);
    }

    /**
     * Reopens a locked period so an approved correction can change it.
     *
     * <p>ADR 0007 rule 4: "Reopen is an explicit, permissioned, audited action." All three of those are
     * here: explicit (a named method, not a status update), permissioned
     * ({@code ANALYTICS_READ_ORGANIZATION} plus {@code ADMIN}), audited (a {@code REOPENED} lock event
     * plus an admin audit row, both carrying the reason).
     *
     * <p>{@code reopenCount} increments, and {@code finance_periods_reopen_count_state_check} ties
     * {@code reopenCount > 0} to {@code reopenedAt IS NOT NULL} — so the count and the timestamp must
     * move together, and a period with a nonzero count can never have its reopen columns cleared.
     *
     * @throws FinancePeriodClosedException never from here; this is the method that resolves it
     */
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    @Transactional
    public void reopen(TenantContext caller, UUID periodId, String reason) {
        requireFinanceAdmin(caller);
        requireNonBlank(reason,
            "Reopening a period requires a reason. finance_periods_reopened_check requires it, and "
                + "ADR 0007 rule 4 makes the reason the part an auditor asks for.");
        TenantScope scope = guard.tenantScope(caller);
        FinancePeriodRepository.PeriodRow period = requirePeriod(scope, periodId);
        if (period.statusEnum() != PeriodStatus.LOCKED) {
            throw new IllegalStateException(
                "Only a LOCKED period can be reopened; " + periodId + " is " + period.status() + ". "
                    + "A period that is not closed has nothing to reopen.");
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        periods.updateStatus(scope, periodId, PeriodStatus.REOPENED, null, period.lockedAt(),
            period.lockedByUserId(), period.lockNote(), now, caller.userId(), reason,
            period.reopenCount() + 1);
        periods.appendLockEvent(scope, periodId, PeriodLockEventType.REOPENED, caller.userId(),
            principalName(caller), reason);
        writeAdminAudit(caller, scope, "FINANCE_PERIOD_REOPEN", "finance_periods", periodId,
            "period " + period.key() + " reopened: " + reason);
        log.info("finance period reopened organization={} periodId={} reopenCount={} by={}",
            scope.organizationId(), periodId, period.reopenCount() + 1, principalName(caller));
    }

    /** Moves a reopened or locked period back into the close run. */
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    @Transactional
    public void returnToReview(TenantContext caller, UUID periodId) {
        requireFinanceAdmin(caller);
        TenantScope scope = guard.tenantScope(caller);
        FinancePeriodRepository.PeriodRow period = requirePeriod(scope, periodId);
        if (period.statusEnum() != PeriodStatus.LOCKED && period.statusEnum() != PeriodStatus.REOPENED) {
            throw new IllegalStateException(
                "Only a LOCKED or REOPENED period can return to review; " + periodId + " is "
                    + period.status() + ".");
        }
        // finance_periods_reopened_check is a BICONDITIONAL over three columns, so a reopened period must
        // KEEP them and therefore must keep its REOPENED status. Neither IN_REVIEW nor LOCKED can hold a
        // reopened period, which is why the correction workflow ends in REOPENED rather than in a second
        // close; see relock() and the report for the full finding.
        if (period.statusEnum() == PeriodStatus.REOPENED) {
            throw new IllegalStateException(
                "A REOPENED period cannot return to IN_REVIEW: "
                    + "finance_periods_reopened_check requires the three reopen columns exactly while "
                    + "the status is REOPENED, and finance_periods_reopen_count_state_check refuses to "
                    + "clear them while reopenCount is " + period.reopenCount() + ". Apply the approved "
                    + "adjustment and recompute the affected buckets against the REOPENED period.");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        periods.updateStatus(scope, periodId, PeriodStatus.IN_REVIEW, now, period.lockedAt(),
            period.lockedByUserId(), period.lockNote(), null, null, null, period.reopenCount());
        periods.appendLockEvent(scope, periodId, PeriodLockEventType.UNDER_REVIEW, caller.userId(),
            principalName(caller), "returned to review");
        writeAdminAudit(caller, scope, "FINANCE_PERIOD_UNDER_REVIEW", "finance_periods", periodId,
            "period " + period.key() + " returned to review");
    }

    /**
     * Re-locking a corrected period — <b>refused, and the schema is why</b>.
     *
     * <p>The obvious end of the correction workflow is reopen → apply → recompute → lock again.
     * {@code V20261005090000} makes the last step inexpressible, and the reason is worth recording
     * because it was found by attempting it:
     *
     * <p>{@code finance_periods_reopened_check} is a <b>biconditional</b> over three columns:
     * {@code (status = 'REOPENED') = (reopenedAt IS NOT NULL AND reopenedByUserId IS NOT NULL AND
     * reopenReason IS NOT NULL)}. {@code finance_periods_reopen_count_state_check} then ties
     * {@code reopenCount > 0} to {@code reopenedAt IS NOT NULL}. Together they mean a reopened period must
     * keep all three columns forever, and therefore must keep the {@code REOPENED} status forever.
     *
     * <p>So the expressible correction workflow is: reopen (permissioned, audited, reason recorded) →
     * apply the approved adjustments → recompute the affected buckets as new snapshot generations. The
     * period remains {@code REOPENED}, and that is the state a corrected period stays in.
     *
     * <p><b>Reported to the orchestrator as a schema defect.</b> ADR 0007 rule 4 wants a closed period to
     * be changeable through an approved correction and then closed again; the second close is what the
     * schema forbids. The fix is to relax {@code finance_periods_reopened_check} to the one-way form
     * {@code finance_periods_locked_check} already uses — {@code status = 'REOPENED'} implies the three
     * columns, but the converse is not required — and to keep
     * {@code finance_periods_reopen_count_state_check} as the thing that preserves "was ever corrected".
     * Until that migration lands, a corrected period is {@code REOPENED} and stays there.
     */
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    public void relock(TenantContext caller, UUID periodId, String note) {
        throw new UnsupportedOperationException(
            "A REOPENED period cannot be locked again in this schema. "
                + "finance_periods_reopened_check is a biconditional requiring status = 'REOPENED' "
                + "exactly while reopenedAt/reopenedByUserId/reopenReason are all set, and "
                + "finance_periods_reopen_count_state_check refuses to clear reopenedAt while "
                + "reopenCount > 0 -- so a reopened period must keep the REOPENED status permanently. "
                + "The correction workflow the schema supports is: reopen (permissioned and audited), "
                + "apply the approved adjustments, recompute the affected buckets as new snapshot "
                + "generations, and leave the period REOPENED. Relaxing finance_periods_reopened_check "
                + "to the one-way form would make the second close expressible; reported to the "
                + "orchestrator against V20261005090000.");
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private FinancePeriodRepository.PeriodRow requirePeriod(TenantScope scope, UUID periodId) {
        return periods.period(scope, periodId)
            .orElseThrow(() -> new IllegalArgumentException(
                "No finance period " + periodId + " in this tenant"));
    }

    private void requireStatus(FinancePeriodRepository.PeriodRow period, PeriodStatus expected,
                               String message) {
        if (period.statusEnum() != expected) {
            throw new IllegalStateException(
                message + "; " + period.id() + " is " + period.status() + ".");
        }
    }

    /**
     * Read access: {@code ANALYTICS_READ_ORGANIZATION}, satisfied by an {@code ADMIN} or a
     * {@code FINANCE_AGGREGATE} worker.
     */
    private void requireOrganizationAnalytics(TenantContext caller) {
        if (caller == null) {
            throw new IllegalStateException(
                "A finance read requires a resolved TenantContext; refusing to run unscoped.");
        }
        authorization.check(caller, Permission.ANALYTICS_READ_ORGANIZATION, PolicyResource.NONE);
    }

    /**
     * Write access: the same permission <em>and</em> an {@code ADMIN} role.
     *
     * <p>Both, and the role is what keeps a {@code FINANCE_AGGREGATE} worker out. A worker may compute
     * aggregates from a card somebody agreed; it may not decide when a period stops moving.
     */
    private void requireFinanceAdmin(TenantContext caller) {
        requireOrganizationAnalytics(caller);
        if (!caller.isAdmin()) {
            throw new AccessDeniedException(
                "Closing, locking and reopening a finance period is a finance-administrator action "
                    + "(ADR 0007 rule 4: permissioned and audited). This principal is a "
                    + caller.principalType() + ".");
        }
    }

    private String principalName(TenantContext caller) {
        return caller.auditActorLabel();
    }

    private void requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * The administrative copy of a finance action.
     *
     * <p>{@link AdminAuditStreamWriter} is {@code REQUIRES_NEW} and never throws, so this cannot fail
     * the transaction it describes — an audit insert failing must not roll back a completed close.
     */
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
        return limit < 1 ? com.firstelement.finance.ReportingFinanceModule.Limits.PERIOD_PAGE
            : Math.min(limit, com.firstelement.finance.ReportingFinanceModule.Limits.PERIOD_PAGE);
    }
}
