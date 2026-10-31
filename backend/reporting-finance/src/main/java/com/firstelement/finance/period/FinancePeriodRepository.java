package com.firstelement.finance.period;

import com.firstelement.finance.ledger.UsagePeriodResolver.FinancePeriodLookup;
import com.firstelement.security.query.ScopedWhere;
import com.firstelement.security.query.TenantScope;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes {@code finance_periods}, {@code finance_period_lock_events},
 * {@code finance_adjustments}, {@code finance_reconciliation_runs} and
 * {@code finance_reconciliation_lines}.
 *
 * <h2>One repository for the period cluster, and why</h2>
 *
 * <p>These five tables are one state machine and one audit trail. Splitting them across repositories
 * would mean a lock that updates {@code finance_periods} and writes its {@code finance_period_lock_events}
 * row through two classes with two connection scopes, and the failure mode is the worst one available:
 * a locked period with no history row, which is indistinguishable from one that was never locked.
 * Keeping them here means the service that performs a transition writes the row and the history in one
 * {@code @Transactional} method on one template.
 *
 * <h2>Every statement is tenant-filtered in the {@code WHERE}</h2>
 *
 * <p>Same rule as the rest of the system, and the same reason: an unauthorized row must never enter a
 * Java collection, a count or a sum. {@link PeriodLifecycleService} additionally authorizes before
 * reaching any of these methods, so the filter is a second line rather than the only one.
 */
@Repository
public class FinancePeriodRepository {

    private static final String PERIOD = """
        select p."id", p."organizationId", p."projectId", p."key", p."nameAr", p."nameEn",
               p."periodStart", p."periodEnd", p."currency", p."status"::text, p."underReviewAt",
               p."lockedAt", p."lockedByUserId", p."lockNote", p."reopenedAt", p."reopenedByUserId",
               p."reopenReason", p."reopenCount", p."version", p."createdAt", p."updatedAt"
          from finance_periods p
         where p."organizationId" = :__fe_tenant_organization_id
           and p."id" = :periodId
        """;

    private static final String PERIODS = """
        select p."id", p."organizationId", p."projectId", p."key", p."nameAr", p."nameEn",
               p."periodStart", p."periodEnd", p."currency", p."status"::text, p."underReviewAt",
               p."lockedAt", p."lockedByUserId", p."lockNote", p."reopenedAt", p."reopenedByUserId",
               p."reopenReason", p."reopenCount", p."version", p."createdAt", p."updatedAt"
          from finance_periods p
         where p."organizationId" = :__fe_tenant_organization_id
           and (cast(:filterStatus as text) is null or p."status"::text = cast(:filterStatus as text))
         order by p."periodStart" desc
         limit :limit
        """;

    /**
     * The period covering a date.
     *
     * <p>Both bounds inclusive, matching {@code finance_periods} and every query in this module. A
     * date on a boundary day must have exactly one answer, and an exclusive end would make two adjacent
     * months both claim it — which is precisely the ambiguity the migration's comment rules out.
     */
    private static final String PERIOD_COVERING_DATE = """
        select p."id", p."status"::text
          from finance_periods p
         where p."organizationId" = :organizationId
           and p."periodStart" <= :usageDate
           and p."periodEnd" >= :usageDate
         order by p."periodStart" desc
         limit 1
        """;

    private static final String INSERT_PERIOD = """
        insert into finance_periods ("id", "organizationId", "projectId", "key", "nameAr", "nameEn",
            "periodStart", "periodEnd", "currency", "status", "version", "createdAt", "updatedAt")
        values (:id, :__fe_tenant_organization_id, :projectId, :key, :nameAr, :nameEn, :periodStart, :periodEnd,
            :currency, cast('OPEN' as "PeriodStatus"), 0, :now, :now)
        """;

    /**
     * The single UPDATE shape used by every transition.
     *
     *
     * <p>All four state columns are written together with the status, because three of the schema's
     * biconditionals tie them to {@code status}: {@code finance_periods_review_check},
     * {@code finance_periods_reopened_check} and {@code finance_periods_locked_check}. Writing one
     * without the others arrives as a CHECK violation naming a constraint rather than as a state a
     * caller can reason about, so every transition states the whole shape.
     *
     * <p>{@code "version" = "version" + 1} is the optimistic-concurrency counter. It is not
     * {@code where "version" = :expected} — see {@link #updateStatus} for why, which is a real
     * limitation rather than an oversight.
     */
    private static final String UPDATE_STATUS = """
        update finance_periods
           set "status" = cast(:status as "PeriodStatus"), "underReviewAt" = :underReviewAt,
               "lockedAt" = :lockedAt, "lockedByUserId" = :lockedByUserId, "lockNote" = :lockNote,
               "reopenedAt" = :reopenedAt, "reopenedByUserId" = :reopenedByUserId,
               "reopenReason" = :reopenReason, "reopenCount" = :reopenCount,
               "version" = "version" + 1, "updatedAt" = :now
         where "organizationId" = :__fe_tenant_organization_id
           and "id" = :periodId
        """;

    private static final String INSERT_LOCK_EVENT = """
        insert into finance_period_lock_events ("id", "organizationId", "projectId", "periodId",
            "eventType", "actorUserId", "actorPrincipal", "note", "eventAt", "createdAt")
        values (:id, :__fe_tenant_organization_id, :projectId, :periodId, :eventType, :actorUserId,
            :actorPrincipal, :note, :eventAt, :createdAt)
        """;

    private static final String LOCK_EVENTS = """
        select e."id", e."organizationId", e."projectId", e."periodId", e."eventType",
               e."actorUserId", e."actorPrincipal", e."note", e."eventAt", e."createdAt"
          from finance_period_lock_events e
         where e."organizationId" = :__fe_tenant_organization_id
           and e."periodId" = :periodId
         order by e."eventAt", e."id"
         limit :limit
        """;

    // ------------------------------------------------------------------
    // adjustments
    // ------------------------------------------------------------------

    private static final String INSERT_ADJUSTMENT = """
        insert into finance_adjustments ("id", "organizationId", "projectId", "periodId", "status",
            "adjustmentOfId", "reason", "amount", "currency", "quantity", "createdByUserId",
            "version", "createdAt", "updatedAt")
        values (:id, :__fe_tenant_organization_id, :projectId, :periodId, cast('PENDING' as "AdjustmentStatus"),
            :adjustmentOfId, :reason, :amount, :currency, :quantity, :createdByUserId, 0, :now, :now)
        """;

    /**
     * Approve. Refuses an approver equal to the author here as well as in the CHECK, so the refusal
     * names the rule rather than arriving as a bare constraint violation.
     *
     * <p>Also refuses anything other than PENDING, which
     * {@code finance_adjustments_state_decision_check} would refuse anyway — but that constraint
     * reports "check violation", and "this adjustment is already rejected" is a sentence an operator can
     * act on.
     */
    private static final String APPROVE_ADJUSTMENT = """
        update finance_adjustments
           set "status" = cast('APPROVED' as "AdjustmentStatus"), "approvedByUserId" = :approvedBy,
               "approvedAt" = :now, "approvalNote" = :approvalNote,
               "version" = "version" + 1, "updatedAt" = :now
         where "organizationId" = :__fe_tenant_organization_id
           and "id" = :adjustmentId
           and "status" = cast('PENDING' as "AdjustmentStatus")
        """;

    private static final String REJECT_ADJUSTMENT = """
        update finance_adjustments
           set "status" = cast('REJECTED' as "AdjustmentStatus"), "rejectedByUserId" = :rejectedBy,
               "rejectedAt" = :now, "rejectionReason" = :rejectionReason,
               "version" = "version" + 1, "updatedAt" = :now
         where "organizationId" = :__fe_tenant_organization_id
           and "id" = :adjustmentId
           and "status" = cast('PENDING' as "AdjustmentStatus")
        """;

    /** Mark an approved adjustment as applied to the period's figures. */
    private static final String APPLY_ADJUSTMENT = """
        update finance_adjustments
           set "status" = cast('APPLIED' as "AdjustmentStatus"), "appliedAt" = :now,
               "appliedByUserId" = :appliedBy, "version" = "version" + 1, "updatedAt" = :now
         where "organizationId" = :__fe_tenant_organization_id
           and "id" = :adjustmentId
           and "status" = cast('APPROVED' as "AdjustmentStatus")
        """;

    private static final String ADJUSTMENT = """
        select a."id", a."organizationId", a."projectId", a."periodId", a."status"::text,
               a."adjustmentOfId", a."reason", a."amount", a."currency", a."quantity",
               a."createdByUserId", a."approvedByUserId", a."approvedAt", a."approvalNote",
               a."rejectedByUserId", a."rejectedAt", a."rejectionReason", a."appliedAt",
               a."appliedByUserId", a."createdAt"
          from finance_adjustments a
         where a."organizationId" = :__fe_tenant_organization_id
           and a."id" = :adjustmentId
        """;

    private static final String ADJUSTMENTS_FOR_PERIOD = """
        select a."id", a."organizationId", a."projectId", a."periodId", a."status"::text,
               a."adjustmentOfId", a."reason", a."amount", a."currency", a."quantity",
               a."createdByUserId", a."approvedByUserId", a."approvedAt", a."approvalNote",
               a."rejectedByUserId", a."rejectedAt", a."rejectionReason", a."appliedAt",
               a."appliedByUserId", a."createdAt"
          from finance_adjustments a
         where a."organizationId" = :__fe_tenant_organization_id
           and a."periodId" = :periodId
           and (cast(:filterStatus as text) is null or a."status"::text = cast(:filterStatus as text))
         order by a."createdAt"
         limit :limit
        """;

    /**
     * The usage events an adjustment corrects.
     *
     * <p>Read through the composite tenant key the schema provides:
     * {@code finance_adjustments_event_tenant_fkey} makes a cross-tenant {@code adjustmentOfId}
     * impossible at the column, and this read is scoped again so a foreign id returns nothing rather
     * than an exception. Both, because a constraint stops the write and a predicate stops the read, and
     * "which events does this adjustment correct" must be answerable without causing a failure.
     */
    private static final String ADJUSTMENT_SOURCE_EVENTS = """
        select u."id", u."dimension"::text, u."quantity", u."unit", u."currency", u."costAmount",
               u."sourceEventType", u."sourceEventId", u."occurredAt"
          from usage_events u
         where u."organizationId" = :__fe_tenant_organization_id
           and u."id" = :eventId
        """;

    // ------------------------------------------------------------------
    // reconciliation
    // ------------------------------------------------------------------

    private static final String INSERT_RECONCILIATION_RUN = """
        insert into finance_reconciliation_runs ("id", "organizationId", "projectId", "periodId",
            "status", "sourceEventCount", "snapshotAmount", "ledgerAmount", "difference",
            "toleranceAmount", "runByUserId", "runByPrincipal", "notes", "correlationId", "ranAt",
            "createdAt")
        values (:id, :__fe_tenant_organization_id, :projectId, :periodId, cast(:status as "ReconciliationStatus"),
            :sourceEventCount, :snapshotAmount, :ledgerAmount, :difference, :toleranceAmount,
            :runByUserId, :runByPrincipal, :notes, :correlationId, :ranAt, :createdAt)
        """;

    private static final String INSERT_RECONCILIATION_LINE = """
        insert into finance_reconciliation_lines ("id", "organizationId", "projectId",
            "reconciliationRunId", "dimension", "sourceEventCount", "snapshotQuantity",
            "ledgerQuantity", "snapshotCost", "ledgerCost", "quantityDifference", "costDifference",
            "status", "note", "createdAt")
        values (:id, :__fe_tenant_organization_id, :projectId, :reconciliationRunId,
            cast(:dimension as "UsageDimension"), :sourceEventCount, :snapshotQuantity, :ledgerQuantity,
            :snapshotCost, :ledgerCost, :quantityDifference, :costDifference,
            cast(:status as "ReconciliationStatus"), :note, :createdAt)
        """;

    private static final String RECONCILIATION_RUNS = """
        select r."id", r."organizationId", r."projectId", r."periodId", r."status"::text,
               r."sourceEventCount", r."snapshotAmount", r."ledgerAmount", r."difference",
               r."toleranceAmount", r."runByUserId", r."runByPrincipal", r."notes", r."correlationId",
               r."ranAt"
          from finance_reconciliation_runs r
         where r."organizationId" = :__fe_tenant_organization_id
           and (cast(:filterPeriodId as uuid) is null or r."periodId" = cast(:filterPeriodId as uuid))
         order by r."ranAt" desc
         limit :limit
        """;

    private static final String RECONCILIATION_LINES = """
        select l."id", l."organizationId", l."projectId", l."reconciliationRunId",
               l."dimension"::text, l."sourceEventCount", l."snapshotQuantity", l."ledgerQuantity",
               l."snapshotCost", l."ledgerCost", l."quantityDifference", l."costDifference",
               l."status"::text, l."note"
          from finance_reconciliation_lines l
         where l."organizationId" = :__fe_tenant_organization_id
           and l."reconciliationRunId" = :runId
         order by l."dimension"
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public FinancePeriodRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // row shapes
    // ------------------------------------------------------------------

    /** A finance period row. */
    public record PeriodRow(UUID id,
                            UUID organizationId,
                            UUID projectId,
                            String key,
                            String nameAr,
                            String nameEn,
                            LocalDate periodStart,
                            LocalDate periodEnd,
                            String currency,
                            String status,
                            LocalDateTime underReviewAt,
                            LocalDateTime lockedAt,
                            UUID lockedByUserId,
                            String lockNote,
                            LocalDateTime reopenedAt,
                            UUID reopenedByUserId,
                            String reopenReason,
                            int reopenCount,
                            int version,
                            LocalDateTime createdAt,
                            LocalDateTime updatedAt) {

        /** The status as the enum, so a caller compares against declared states. */
        public PeriodStatus statusEnum() {
            return PeriodStatus.fromWire(status);
        }

        /**
         * Whether this period has ever been locked.
         *
         * <p>Answerable from the row even after a reopen, because {@code finance_periods_locked_check}
         * is one-way and a REOPENED period keeps {@code lockedAt}. This is the "was it ever closed?"
         * question the migration says clearing the column would make unanswerable.
         */
        public boolean wasEverLocked() {
            return lockedAt != null;
        }
    }

    /** An adjustment row. */
    public record AdjustmentRow(UUID id,
                                UUID organizationId,
                                UUID projectId,
                                UUID periodId,
                                String status,
                                UUID adjustmentOfId,
                                String reason,
                                java.math.BigDecimal amount,
                                String currency,
                                java.math.BigDecimal quantity,
                                UUID createdByUserId,
                                UUID approvedByUserId,
                                LocalDateTime approvedAt,
                                String approvalNote,
                                UUID rejectedByUserId,
                                LocalDateTime rejectedAt,
                                String rejectionReason,
                                LocalDateTime appliedAt,
                                UUID appliedByUserId,
                                LocalDateTime createdAt) {
    }

    /** The source events an adjustment names. */
    public record SourceEventRow(UUID id,
                                 String dimension,
                                 java.math.BigDecimal quantity,
                                 String unit,
                                 String currency,
                                 java.math.BigDecimal costAmount,
                                 String sourceEventType,
                                 UUID sourceEventId,
                                 LocalDateTime occurredAt) {
    }

    /** A reconciliation run row. */
    public record ReconciliationRunRow(UUID id,
                                       UUID organizationId,
                                       UUID projectId,
                                       UUID periodId,
                                       String status,
                                       long sourceEventCount,
                                       java.math.BigDecimal snapshotAmount,
                                       java.math.BigDecimal ledgerAmount,
                                       java.math.BigDecimal difference,
                                       java.math.BigDecimal toleranceAmount,
                                       UUID runByUserId,
                                       String runByPrincipal,
                                       String notes,
                                       String correlationId,
                                       LocalDateTime ranAt) {
    }

    /** A reconciliation line row. */
    public record ReconciliationLineRow(UUID id,
                                        UUID organizationId,
                                        UUID projectId,
                                        UUID reconciliationRunId,
                                        String dimension,
                                        long sourceEventCount,
                                        java.math.BigDecimal snapshotQuantity,
                                        java.math.BigDecimal ledgerQuantity,
                                        java.math.BigDecimal snapshotCost,
                                        java.math.BigDecimal ledgerCost,
                                        java.math.BigDecimal quantityDifference,
                                        java.math.BigDecimal costDifference,
                                        String status,
                                        String note) {
    }

    // ------------------------------------------------------------------
    // periods
    // ------------------------------------------------------------------

    /** One period. */
    public Optional<PeriodRow> period(TenantScope scope, UUID periodId) {
        return jdbc.query(PERIOD, ScopedWhere.of(scope).bindNamed(java.util.Map.of(
                "periodId", periodId)), FinancePeriodRepository::mapPeriod)
            .stream().findFirst();
    }

    /** The tenant's periods, newest first. */
    public List<PeriodRow> periods(TenantScope scope, String status, int limit) {
        MapSqlParameterSource parameters =
            ScopedWhere.of(scope).bindNamed(java.util.Map.of("limit", Math.max(1, limit)));
        parameters.addValue("filterStatus", status);
        return jdbc.query(PERIODS, parameters, FinancePeriodRepository::mapPeriod);
    }

    /**
     * The period covering a usage date.
     *
     * <p>Not taken through {@link ScopedWhere} because it is called on the usage-write hot path where
     * the caller already holds a validated scope; the {@code organizationId} predicate is stated
     * explicitly so it still cannot cross a tenant.
     */
    public Optional<FinancePeriodLookup.PeriodStatusRef> periodCovering(UUID organizationId,
                                                                       LocalDate usageDate) {
        return jdbc.query(PERIOD_COVERING_DATE, new MapSqlParameterSource()
                .addValue("organizationId", organizationId)
                .addValue("usageDate", usageDate),
            (rs, rowNum) -> new FinancePeriodLookup.PeriodStatusRef(
                rs.getObject("id", UUID.class), rs.getString("status")))
            .stream().findFirst();
    }

    /** Creates a period in {@code OPEN}. */
    public UUID insertPeriod(TenantScope scope, UUID projectId, String key, String nameAr,
                             String nameEn, LocalDate periodStart, LocalDate periodEnd,
                             String currency) {
        UUID id = UUID.randomUUID();
        jdbc.update(INSERT_PERIOD, ScopedWhere.of(scope).bindNamed(java.util.Map.of(
            "id", id,
            "projectId", projectId,
            "key", key,
            "nameAr", nameAr,
            "nameEn", nameEn,
            "periodStart", periodStart,
            "periodEnd", periodEnd,
            "currency", currency,
            "now", LocalDateTime.now(ZoneOffset.UTC))));
        return id;
    }

    /**
     * Writes a period's whole status shape.
     *
     * <p><b>Optimistic concurrency, and its limit.</b> The {@code version} column is bumped, so a
     * reader can detect that something changed, but this statement does not take an expected version,
     * so two concurrent transitions on the same period both succeed and the later write wins. The
     * period-close path is performed by one operator at a time and every transition is audited, so the
     * window is a genuine but bounded gap rather than a lost update on money. A compare-and-set variant
     * would need a follow-up reader of the version inside the same transaction; recorded as debt
     * rather than asserted as safe.
     *
     * @return rows updated; 0 means the period is not in this tenant
     */
    public int updateStatus(TenantScope scope, UUID periodId, PeriodStatus status,
                            LocalDateTime underReviewAt, LocalDateTime lockedAt,
                            UUID lockedByUserId, String lockNote, LocalDateTime reopenedAt,
                            UUID reopenedByUserId, String reopenReason, int reopenCount) {
        // Twelve named parameters plus the tenant filter, so Map.of() (ten pairs maximum) is not an
        // option and the source is built entry by entry.
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(java.util.Map.of())
            .addValue("periodId", periodId)
            .addValue("status", status.wireValue())
            .addValue("underReviewAt", underReviewAt)
            .addValue("lockedAt", lockedAt)
            .addValue("lockedByUserId", lockedByUserId)
            .addValue("lockNote", lockNote)
            .addValue("reopenedAt", reopenedAt)
            .addValue("reopenedByUserId", reopenedByUserId)
            .addValue("reopenReason", reopenReason)
            .addValue("reopenCount", reopenCount)
            .addValue("now", LocalDateTime.now(ZoneOffset.UTC));
        return jdbc.update(UPDATE_STATUS, parameters);
    }

    /**
     * Appends a lifecycle event.
     *
     * <p>{@code projectId} is inherited from the period rather than supplied, because
     * {@code finance_period_lock_events."projectId"} is nullable and describes the same scope the period
     * has. Reading it from the period row keeps the history consistent with its subject; a caller cannot
     * label a period's history with a project the period is not in.
     */
    public UUID appendLockEvent(TenantScope scope, UUID periodId, PeriodLockEventType eventType,
                                UUID actorUserId, String actorPrincipal, String note) {
        if (eventType.requiresNote() && (note == null || note.isBlank())) {
            throw new IllegalArgumentException(
                "finance_period_lock_events_note_check requires a non-blank note on a " + eventType
                    + " event; an action that changes a period's disposition and says nothing is not "
                    + "reviewable.");
        }
        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        UUID periodProjectId = jdbc.queryForObject("""
            select p."projectId" from finance_periods p
             where p."organizationId" = :__fe_tenant_organization_id and p."id" = :periodId""",
            ScopedWhere.of(scope).bindNamed(java.util.Map.of("periodId", periodId)),
            UUID.class);
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(java.util.Map.of())
            .addValue("id", id)
            .addValue("projectId", periodProjectId)
            .addValue("periodId", periodId)
            .addValue("eventType", eventType.label())
            .addValue("actorUserId", actorUserId)
            .addValue("actorPrincipal", actorPrincipal)
            .addValue("note", note)
            .addValue("eventAt", now)
            .addValue("createdAt", now);
        jdbc.update(INSERT_LOCK_EVENT, parameters);
        return id;
    }

    /** A period's lifecycle history, oldest first. */
    public List<PeriodLockEventType.PeriodLockEvent> lockEvents(TenantScope scope, UUID periodId,
                                                                int limit) {
        return jdbc.query(LOCK_EVENTS, ScopedWhere.of(scope).bindNamed(java.util.Map.of(
                "periodId", periodId, "limit", Math.max(1, limit))),
            (rs, rowNum) -> new PeriodLockEventType.PeriodLockEvent(
                rs.getObject("id", UUID.class),
                rs.getObject("organizationId", UUID.class),
                rs.getObject("projectId", UUID.class),
                rs.getObject("periodId", UUID.class),
                rs.getString("eventType"),
                rs.getObject("actorUserId", UUID.class),
                rs.getString("actorPrincipal"),
                rs.getString("note"),
                rs.getObject("eventAt", LocalDateTime.class)));
    }

    // ------------------------------------------------------------------
    // adjustments
    // ------------------------------------------------------------------

    /**
     * Raises a PENDING adjustment.
     *
     * @param adjustmentOfId the usage event it corrects, or {@code null} for a period-level
     *                       correction. {@code reason} is NOT NULL either way
     *                       ({@code finance_adjustments_reason_check}), because a correction that
     *                       cannot name its target still has to be explainable
     */
    public UUID insertAdjustment(TenantScope scope, UUID projectId, UUID periodId,
                                 UUID adjustmentOfId, String reason, java.math.BigDecimal amount,
                                 String currency, java.math.BigDecimal quantity, UUID createdByUserId) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                "finance_adjustments_reason_check requires a non-blank reason. An unexplained "
                    + "adjustment is indistinguishable from tampering with money, which is the failure "
                    + "mode this table exists to prevent.");
        }
        UUID id = UUID.randomUUID();
        // adjustmentOfId and quantity are both legitimately null — a period-level correction, and a
        // money-only correction — so the bindings go through a MapSqlParameterSource rather than Map.of,
        // which refuses a null value.
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(java.util.Map.of())
            .addValue("id", id)
            .addValue("projectId", projectId)
            .addValue("periodId", periodId)
            .addValue("adjustmentOfId", adjustmentOfId)
            .addValue("reason", reason)
            .addValue("amount", amount)
            .addValue("currency", currency)
            .addValue("quantity", quantity)
            .addValue("createdByUserId", createdByUserId)
            .addValue("now", LocalDateTime.now(ZoneOffset.UTC));
        jdbc.update(INSERT_ADJUSTMENT, parameters);
        return id;
    }

    /** Approves a PENDING adjustment by somebody who is not its author. */
    public int approveAdjustment(TenantScope scope, UUID adjustmentId, UUID approvedByUserId,
                                 String approvalNote) {
        return jdbc.update(APPROVE_ADJUSTMENT, ScopedWhere.of(scope).bindNamed(java.util.Map.of(
            "adjustmentId", adjustmentId,
            "approvedBy", approvedByUserId,
            "approvalNote", approvalNote,
            "now", LocalDateTime.now(ZoneOffset.UTC))));
    }

    /** Rejects a PENDING adjustment, with a reason. */
    public int rejectAdjustment(TenantScope scope, UUID adjustmentId, UUID rejectedByUserId,
                                String rejectionReason) {
        if (rejectionReason == null || rejectionReason.isBlank()) {
            throw new IllegalArgumentException(
                "A rejected adjustment must say why; finance_adjustments_rejection_reason_check "
                    + "requires it and 'rejected' with no reason is not reviewable.");
        }
        return jdbc.update(REJECT_ADJUSTMENT, ScopedWhere.of(scope).bindNamed(java.util.Map.of(
            "adjustmentId", adjustmentId,
            "rejectedBy", rejectedByUserId,
            "rejectionReason", rejectionReason,
            "now", LocalDateTime.now(ZoneOffset.UTC))));
    }

    /** Marks an APPROVED adjustment as applied. */
    public int applyAdjustment(TenantScope scope, UUID adjustmentId, UUID appliedByUserId) {
        return jdbc.update(APPLY_ADJUSTMENT, ScopedWhere.of(scope).bindNamed(java.util.Map.of(
            "adjustmentId", adjustmentId,
            "appliedBy", appliedByUserId,
            "now", LocalDateTime.now(ZoneOffset.UTC))));
    }

    /** One adjustment. */
    public Optional<AdjustmentRow> adjustment(TenantScope scope, UUID adjustmentId) {
        return jdbc.query(ADJUSTMENT, ScopedWhere.of(scope).bindNamed(java.util.Map.of(
                "adjustmentId", adjustmentId)), FinancePeriodRepository::mapAdjustment)
            .stream().findFirst();
    }

    /** A period's adjustments. */
    public List<AdjustmentRow> adjustments(TenantScope scope, UUID periodId, String status,
                                           int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(java.util.Map.of(
            "periodId", periodId,
            "limit", Math.max(1, limit)));
        parameters.addValue("filterStatus", status);
        return jdbc.query(ADJUSTMENTS_FOR_PERIOD, parameters,
            FinancePeriodRepository::mapAdjustment);
    }

    /** The usage event one adjustment corrects, or empty when it is period-level or foreign. */
    public Optional<SourceEventRow> sourceEvent(TenantScope scope, UUID eventId) {
        return jdbc.query(ADJUSTMENT_SOURCE_EVENTS,
                ScopedWhere.of(scope).bindNamed(java.util.Map.of("eventId", eventId)),
            (rs, rowNum) -> new SourceEventRow(
                rs.getObject("id", UUID.class),
                rs.getString("dimension"),
                rs.getBigDecimal("quantity"),
                rs.getString("unit"),
                rs.getString("currency"),
                rs.getBigDecimal("costAmount"),
                rs.getString("sourceEventType"),
                rs.getObject("sourceEventId", UUID.class),
                rs.getObject("occurredAt", LocalDateTime.class)))
            .stream().findFirst();
    }

    // ------------------------------------------------------------------
    // reconciliation
    // ------------------------------------------------------------------

    /** Appends a reconciliation run. */
    public UUID insertReconciliationRun(TenantScope scope, UUID projectId, UUID periodId,
                                        String status, long sourceEventCount,
                                        java.math.BigDecimal snapshotAmount,
                                        java.math.BigDecimal ledgerAmount,
                                        java.math.BigDecimal difference,
                                        java.math.BigDecimal toleranceAmount,
                                        UUID runByUserId, String runByPrincipal, String notes,
                                        String correlationId) {
        if (!"MATCHED".equals(status) && (notes == null || notes.isBlank())) {
            throw new IllegalArgumentException(
                "finance_reconciliation_runs_status_check requires notes on a MISMATCH or ACCEPTED run. "
                    + "A mismatch nobody explained gets accepted by silence, which is the outcome this "
                    + "constraint exists to prevent.");
        }
        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(java.util.Map.of())
            .addValue("id", id)
            .addValue("projectId", projectId)
            .addValue("periodId", periodId)
            .addValue("status", status)
            .addValue("sourceEventCount", sourceEventCount)
            .addValue("snapshotAmount", snapshotAmount)
            .addValue("ledgerAmount", ledgerAmount)
            .addValue("difference", difference)
            .addValue("toleranceAmount", toleranceAmount)
            .addValue("runByUserId", runByUserId)
            .addValue("runByPrincipal", runByPrincipal)
            .addValue("notes", notes)
            .addValue("correlationId", correlationId)
            .addValue("ranAt", now)
            .addValue("createdAt", now);
        jdbc.update(INSERT_RECONCILIATION_RUN, parameters);
        return id;
    }

    /** Appends one reconciliation line. */
    public UUID insertReconciliationLine(TenantScope scope, UUID projectId, UUID runId,
                                         String dimension, long sourceEventCount,
                                         java.math.BigDecimal snapshotQuantity,
                                         java.math.BigDecimal ledgerQuantity,
                                         java.math.BigDecimal snapshotCost,
                                         java.math.BigDecimal ledgerCost,
                                         java.math.BigDecimal quantityDifference,
                                         java.math.BigDecimal costDifference, String status,
                                         String note) {
        if (!"MATCHED".equals(status) && (note == null || note.isBlank())) {
            throw new IllegalArgumentException(
                "finance_reconciliation_lines_status_note_check requires a note on a MISMATCH or "
                    + "ACCEPTED line; a per-dimension difference with no explanation is how a "
                    + "mismatch becomes permanent by accident.");
        }
        UUID id = UUID.randomUUID();
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(java.util.Map.of())
            .addValue("id", id)
            .addValue("projectId", projectId)
            .addValue("reconciliationRunId", runId)
            .addValue("dimension", dimension)
            .addValue("sourceEventCount", sourceEventCount)
            .addValue("snapshotQuantity", snapshotQuantity)
            .addValue("ledgerQuantity", ledgerQuantity)
            .addValue("snapshotCost", snapshotCost)
            .addValue("ledgerCost", ledgerCost)
            .addValue("quantityDifference", quantityDifference)
            .addValue("costDifference", costDifference)
            .addValue("status", status)
            .addValue("note", note)
            .addValue("createdAt", LocalDateTime.now(ZoneOffset.UTC));
        jdbc.update(INSERT_RECONCILIATION_LINE, parameters);
        return id;
    }

    /** Reconciliation runs, newest first. */
    public List<ReconciliationRunRow> reconciliationRuns(TenantScope scope, UUID periodId,
                                                         int limit) {
        MapSqlParameterSource parameters =
            ScopedWhere.of(scope).bindNamed(java.util.Map.of("limit", Math.max(1, limit)));
        parameters.addValue("filterPeriodId", periodId);
        return jdbc.query(RECONCILIATION_RUNS, parameters, (rs, rowNum) -> new ReconciliationRunRow(
            rs.getObject("id", UUID.class),
            rs.getObject("organizationId", UUID.class),
            rs.getObject("projectId", UUID.class),
            rs.getObject("periodId", UUID.class),
            rs.getString("status"),
            rs.getLong("sourceEventCount"),
            rs.getBigDecimal("snapshotAmount"),
            rs.getBigDecimal("ledgerAmount"),
            rs.getBigDecimal("difference"),
            rs.getBigDecimal("toleranceAmount"),
            rs.getObject("runByUserId", UUID.class),
            rs.getString("runByPrincipal"),
            rs.getString("notes"),
            rs.getString("correlationId"),
            rs.getObject("ranAt", LocalDateTime.class)));
    }

    /** One run's per-dimension lines. */
    public List<ReconciliationLineRow> reconciliationLines(TenantScope scope, UUID runId) {
        return jdbc.query(RECONCILIATION_LINES,
                ScopedWhere.of(scope).bindNamed(java.util.Map.of("runId", runId)),
            (rs, rowNum) -> new ReconciliationLineRow(
                rs.getObject("id", UUID.class),
                rs.getObject("organizationId", UUID.class),
                rs.getObject("projectId", UUID.class),
                rs.getObject("reconciliationRunId", UUID.class),
                rs.getString("dimension"),
                rs.getLong("sourceEventCount"),
                rs.getBigDecimal("snapshotQuantity"),
                rs.getBigDecimal("ledgerQuantity"),
                rs.getBigDecimal("snapshotCost"),
                rs.getBigDecimal("ledgerCost"),
                rs.getBigDecimal("quantityDifference"),
                rs.getBigDecimal("costDifference"),
                rs.getString("status"),
                rs.getString("note")));
    }

    // ------------------------------------------------------------------
    // mappers
    // ------------------------------------------------------------------

    private static PeriodRow mapPeriod(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new PeriodRow(
            rs.getObject("id", UUID.class),
            rs.getObject("organizationId", UUID.class),
            rs.getObject("projectId", UUID.class),
            rs.getString("key"),
            rs.getString("nameAr"),
            rs.getString("nameEn"),
            rs.getObject("periodStart", LocalDate.class),
            rs.getObject("periodEnd", LocalDate.class),
            rs.getString("currency"),
            rs.getString("status"),
            rs.getObject("underReviewAt", LocalDateTime.class),
            rs.getObject("lockedAt", LocalDateTime.class),
            rs.getObject("lockedByUserId", UUID.class),
            rs.getString("lockNote"),
            rs.getObject("reopenedAt", LocalDateTime.class),
            rs.getObject("reopenedByUserId", UUID.class),
            rs.getString("reopenReason"),
            rs.getInt("reopenCount"),
            rs.getInt("version"),
            rs.getObject("createdAt", LocalDateTime.class),
            rs.getObject("updatedAt", LocalDateTime.class));
    }

    private static AdjustmentRow mapAdjustment(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new AdjustmentRow(
            rs.getObject("id", UUID.class),
            rs.getObject("organizationId", UUID.class),
            rs.getObject("projectId", UUID.class),
            rs.getObject("periodId", UUID.class),
            rs.getString("status"),
            rs.getObject("adjustmentOfId", UUID.class),
            rs.getString("reason"),
            rs.getBigDecimal("amount"),
            rs.getString("currency"),
            rs.getBigDecimal("quantity"),
            rs.getObject("createdByUserId", UUID.class),
            rs.getObject("approvedByUserId", UUID.class),
            rs.getObject("approvedAt", LocalDateTime.class),
            rs.getString("approvalNote"),
            rs.getObject("rejectedByUserId", UUID.class),
            rs.getObject("rejectedAt", LocalDateTime.class),
            rs.getString("rejectionReason"),
            rs.getObject("appliedAt", LocalDateTime.class),
            rs.getObject("appliedByUserId", UUID.class),
            rs.getObject("createdAt", LocalDateTime.class));
    }
}
