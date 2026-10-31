package com.firstelement.security.platform;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Append-Only Manual Payment Ledger and Subscription Derivation Service.
 *
 * <p>Specification compliance:
 * - Amount is numeric, JOD with 3 decimals (scale 3).
 * - Methods: CASH, BANK_TRANSFER, CLIQ, OTHER.
 * - Append-only: Corrections are reversal entries with reason, NEVER edits or deletes.
 * - Idempotency key required on payment creation: duplicate submission returns existing payment.
 * - paid_until is derived from recorded periods plus explicit extensions.
 * - Optimistic locking (version column) updated on every mutation.
 */
public class SubscriptionPaymentService {

    private final NamedParameterJdbcTemplate jdbc;
    private final PlatformAuditRepository audit;

    public SubscriptionPaymentService(NamedParameterJdbcTemplate jdbc, PlatformAuditRepository audit) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    public record PaymentRecordDto(
        UUID id,
        UUID subscriptionId,
        UUID projectId,
        UUID organizationId,
        String idempotencyKey,
        BigDecimal amount,
        String currency,
        String paymentMethod,
        String referenceNumber,
        Instant periodStart,
        Instant periodEnd,
        UUID recordedByOperatorId,
        String notes,
        boolean isReversal,
        UUID reversalOfPaymentId,
        String reversalReason,
        Instant createdAt,
        boolean isReversed
    ) {}

    public record RecordPaymentCommand(
        String idempotencyKey,
        BigDecimal amount,
        String currency,
        String paymentMethod,
        String referenceNumber,
        int periodMonths,
        String notes
    ) {}

    public record SubscriptionOverviewDto(
        UUID subscriptionId,
        UUID projectId,
        String clinicName,
        String clinicSlug,
        String city,
        String planCode,
        String planName,
        String status,
        Instant paidUntil,
        Instant trialEndsAt,
        int gracePeriodDays,
        int version,
        int paymentsCount,
        BigDecimal totalPaidAmount,
        Instant createdAt
    ) {}

    /**
     * Lists all clinic subscriptions with plan, status, paid_until, and ledger metrics.
     */
    @Transactional(readOnly = true)
    public List<SubscriptionOverviewDto> listSubscriptions() {
        return jdbc.query("""
            select s."id" as "subscriptionId",
                   s."projectId",
                   p."name" as "clinicName",
                   p."slug" as "clinicSlug",
                   p."city",
                   coalesce(pl."code", 'GROWTH') as "planCode",
                   coalesce(pl."name", 'Growth Plan') as "planName",
                   s."status",
                   s."paidUntil",
                   s."trialEndsAt",
                   s."gracePeriodDays",
                   s."version",
                   coalesce(pay."cnt", 0) as "paymentsCount",
                   coalesce(pay."total", 0.000) as "totalPaidAmount",
                   s."createdAt"
              from "public"."clinic_subscriptions" s
              join "public"."projects" p on s."projectId" = p."id"
              left join "public"."plans" pl on s."planId" = pl."id"
              left join (
                  select "subscriptionId",
                         count(*) filter (where "isReversal" = false) as "cnt",
                         coalesce(sum("amount"), 0.000) as "total"
                    from "public"."subscription_payments"
                   group by "subscriptionId"
              ) pay on pay."subscriptionId" = s."id"
             order by p."name" asc
            """,
            new MapSqlParameterSource(),
            (rs, rowNum) -> {
                Timestamp paidTs = rs.getTimestamp("paidUntil");
                Timestamp trialTs = rs.getTimestamp("trialEndsAt");
                return new SubscriptionOverviewDto(
                    (UUID) rs.getObject("subscriptionId"),
                    (UUID) rs.getObject("projectId"),
                    rs.getString("clinicName"),
                    rs.getString("clinicSlug"),
                    rs.getString("city"),
                    rs.getString("planCode"),
                    rs.getString("planName"),
                    rs.getString("status"),
                    paidTs != null ? paidTs.toInstant() : null,
                    trialTs != null ? trialTs.toInstant() : null,
                    rs.getInt("gracePeriodDays"),
                    rs.getInt("version"),
                    rs.getInt("paymentsCount"),
                    rs.getBigDecimal("totalPaidAmount"),
                    rs.getTimestamp("createdAt").toInstant()
                );
            });
    }

    /**
     * Lists the append-only ledger for a clinic in reverse chronological order.
     */
    @Transactional(readOnly = true)
    public List<PaymentRecordDto> listLedger(UUID projectId) {
        return jdbc.query("""
            select p.*,
                   exists(select 1 from "public"."subscription_payments" r where r."reversalOfPaymentId" = p."id") as "isReversed"
              from "public"."subscription_payments" p
             where p."projectId" = :projectId
             order by p."createdAt" desc
            """,
            new MapSqlParameterSource("projectId", projectId),
            this::mapPaymentRow);
    }

    /**
     * Finds a single payment by idempotency key.
     */
    @Transactional(readOnly = true)
    public Optional<PaymentRecordDto> findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        List<PaymentRecordDto> list = jdbc.query("""
            select p.*,
                   exists(select 1 from "public"."subscription_payments" r where r."reversalOfPaymentId" = p."id") as "isReversed"
              from "public"."subscription_payments" p
             where p."idempotencyKey" = :idempotencyKey
            """,
            new MapSqlParameterSource("idempotencyKey", idempotencyKey.trim()),
            this::mapPaymentRow);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * Records a manual payment with strict idempotency and append-only ledger semantics.
     */
    @Transactional
    public PaymentRecordDto recordPayment(PlatformOperatorContext operator, UUID projectId,
                                         RecordPaymentCommand cmd, String correlationId) {
        if (operator == null || (!operator.permits(PlatformCapability.MANAGE_BILLING, Instant.now())
                && !operator.permits(PlatformCapability.CREATE_CLINIC, Instant.now()))) {
            throw new PlatformOperatorContextHolder.PlatformAccessDenied("MANAGE_BILLING capability is required.");
        }
        if (cmd.idempotencyKey() == null || cmd.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required.");
        }
        String cleanKey = cmd.idempotencyKey().trim();

        // 1. Idempotency Check: Return existing payment if duplicate key presented
        Optional<PaymentRecordDto> existing = findByIdempotencyKey(cleanKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        // 2. Validate input parameters
        if (cmd.amount() == null || cmd.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Payment amount must be positive.");
        }
        BigDecimal normalizedAmount = cmd.amount().setScale(3, RoundingMode.HALF_UP);

        int months = cmd.periodMonths() <= 0 ? 1 : cmd.periodMonths();
        String method = (cmd.paymentMethod() == null || cmd.paymentMethod().isBlank())
                ? "CASH" : cmd.paymentMethod().trim().toUpperCase();
        String currency = (cmd.currency() == null || cmd.currency().isBlank())
                ? "JOD" : cmd.currency().trim().toUpperCase();

        // 3. Retrieve Subscription & Organization
        Map<String, Object> subMap = getSubscriptionForProject(projectId);
        UUID subId = (UUID) subMap.get("id");
        UUID orgId = (UUID) subMap.get("organizationId");
        Timestamp paidUntilTs = (Timestamp) subMap.get("paidUntil");
        String currentStatus = (String) subMap.get("status");
        int currentVersion = ((Number) subMap.get("version")).intValue();

        Instant now = Instant.now();
        Instant currentPaidUntil = paidUntilTs != null ? paidUntilTs.toInstant() : now;

        // Calculate period: start from currentPaidUntil if active, or from now if overdue/expired
        Instant periodStart = currentPaidUntil.isAfter(now) ? currentPaidUntil : now;
        Instant periodEnd = periodStart.atZone(ZoneOffset.UTC).plusMonths(months).toInstant();

        UUID paymentId = UUID.randomUUID();

        // 4. Append payment entry to ledger
        try {
            jdbc.update("""
                insert into "public"."subscription_payments"
                    ("id", "subscriptionId", "projectId", "organizationId", "idempotencyKey",
                     "amount", "currency", "paymentMethod", "referenceNumber",
                     "periodStart", "periodEnd", "recordedByOperatorId", "notes",
                     "isReversal", "reversalOfPaymentId", "reversalReason", "createdAt")
                values
                    (:id, :subId, :projectId, :orgId, :idempotencyKey,
                     :amount, :currency, :method, :ref,
                     :periodStart, :periodEnd, :operatorId, :notes,
                     false, null, null, :now)
                """,
                new MapSqlParameterSource()
                    .addValue("id", paymentId)
                    .addValue("subId", subId)
                    .addValue("projectId", projectId)
                    .addValue("orgId", orgId)
                    .addValue("idempotencyKey", cleanKey)
                    .addValue("amount", normalizedAmount)
                    .addValue("currency", currency)
                    .addValue("method", method)
                    .addValue("ref", cmd.referenceNumber())
                    .addValue("periodStart", Timestamp.from(periodStart))
                    .addValue("periodEnd", Timestamp.from(periodEnd))
                    .addValue("operatorId", operator.operatorId())
                    .addValue("notes", cmd.notes())
                    .addValue("now", Timestamp.from(now)));
        } catch (DuplicateKeyException ex) {
            // Race condition: another thread inserted with the same key
            return findByIdempotencyKey(cleanKey).orElseThrow(() -> ex);
        }

        // 5. Update subscription paid_until, version, and status to ACTIVE
        String nextStatus = "SUSPENDED".equalsIgnoreCase(currentStatus) ? "SUSPENDED" : "ACTIVE";
        jdbc.update("""
            update "public"."clinic_subscriptions"
               set "paidUntil" = :newPaidUntil,
                   "status" = :nextStatus,
                   "version" = "version" + 1,
                   "updatedAt" = :now
             where "id" = :subId
               and "version" = :expectedVersion
            """,
            new MapSqlParameterSource()
                .addValue("newPaidUntil", Timestamp.from(periodEnd))
                .addValue("nextStatus", nextStatus)
                .addValue("now", Timestamp.from(now))
                .addValue("subId", subId)
                .addValue("expectedVersion", currentVersion));

        // If project status was EXPIRED, revive to ACTIVE
        jdbc.update("""
            update "public"."projects"
               set "status" = 'ACTIVE',
                   "version" = "version" + 1,
                   "updatedAt" = :now
             where "id" = :projectId
               and "status" in ('EXPIRED', 'GRACE')
            """,
            new MapSqlParameterSource()
                .addValue("projectId", projectId)
                .addValue("now", Timestamp.from(now)));

        // 6. Record Audit
        audit.record("SUBSCRIPTION_PAYMENT_RECORDED", operator.operatorId(), operator.sessionId(),
                orgId, projectId, "SubscriptionPayment", paymentId.toString(),
                "Subscription payment recorded",
                "Payment recorded: " + normalizedAmount + " " + currency + " (" + method + ") for " + months + " months",
                "ALLOWED", correlationId, null);

        return new PaymentRecordDto(
            paymentId, subId, projectId, orgId, cleanKey, normalizedAmount, currency, method,
            cmd.referenceNumber(), periodStart, periodEnd, operator.operatorId(), cmd.notes(),
            false, null, null, now, false
        );
    }

    /**
     * Reverses a prior payment with an append-only compensating reversal entry.
     * Recomputes and restores paid_until accurately.
     */
    @Transactional
    public PaymentRecordDto reversePayment(PlatformOperatorContext operator, UUID projectId,
                                          UUID paymentId, String reversalReason, String correlationId) {
        if (operator == null || (!operator.permits(PlatformCapability.MANAGE_BILLING, Instant.now())
                && !operator.permits(PlatformCapability.CREATE_CLINIC, Instant.now()))) {
            throw new PlatformOperatorContextHolder.PlatformAccessDenied("MANAGE_BILLING capability is required.");
        }
        if (reversalReason == null || reversalReason.trim().length() < 3) {
            throw new IllegalArgumentException("Reversal reason is required (minimum 3 characters).");
        }
        String cleanReason = reversalReason.trim();

        // 1. Fetch original payment
        List<PaymentRecordDto> originalList = jdbc.query("""
            select p.*,
                   exists(select 1 from "public"."subscription_payments" r where r."reversalOfPaymentId" = p."id") as "isReversed"
              from "public"."subscription_payments" p
             where p."id" = :paymentId
               and p."projectId" = :projectId
            """,
            new MapSqlParameterSource().addValue("paymentId", paymentId).addValue("projectId", projectId),
            this::mapPaymentRow);

        if (originalList.isEmpty()) {
            throw new IllegalArgumentException("Payment not found for project.");
        }
        PaymentRecordDto original = originalList.get(0);
        if (original.isReversal()) {
            throw new IllegalArgumentException("Cannot reverse a reversal transaction.");
        }
        if (original.isReversed()) {
            throw new IllegalStateException("Payment has already been reversed.");
        }

        // 2. Insert Append-Only Reversal Entry
        UUID reversalId = UUID.randomUUID();
        String reversalKey = "REV-" + original.idempotencyKey() + "-" + UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.now();
        BigDecimal reversalAmount = original.amount().negate();

        jdbc.update("""
            insert into "public"."subscription_payments"
                ("id", "subscriptionId", "projectId", "organizationId", "idempotencyKey",
                 "amount", "currency", "paymentMethod", "referenceNumber",
                 "periodStart", "periodEnd", "recordedByOperatorId", "notes",
                 "isReversal", "reversalOfPaymentId", "reversalReason", "createdAt")
            values
                (:id, :subId, :projectId, :orgId, :idempotencyKey,
                 :amount, :currency, :method, :ref,
                 :periodStart, :periodEnd, :operatorId, :notes,
                 true, :originalId, :reason, :now)
            """,
            new MapSqlParameterSource()
                .addValue("id", reversalId)
                .addValue("subId", original.subscriptionId())
                .addValue("projectId", projectId)
                .addValue("orgId", original.organizationId())
                .addValue("idempotencyKey", reversalKey)
                .addValue("amount", reversalAmount)
                .addValue("currency", original.currency())
                .addValue("method", original.paymentMethod())
                .addValue("ref", "REV-" + (original.referenceNumber() != null ? original.referenceNumber() : original.id().toString().substring(0, 8)))
                .addValue("periodStart", Timestamp.from(original.periodStart()))
                .addValue("periodEnd", Timestamp.from(original.periodEnd()))
                .addValue("operatorId", operator.operatorId())
                .addValue("notes", "Reversal: " + cleanReason)
                .addValue("originalId", original.id())
                .addValue("reason", cleanReason)
                .addValue("now", Timestamp.from(now)));

        // 3. Recalculate paid_until by deriving from remaining unreversed payments
        recalculateSubscriptionPaidUntil(original.subscriptionId(), projectId);

        // 4. Audit
        audit.record("SUBSCRIPTION_PAYMENT_REVERSED", operator.operatorId(), operator.sessionId(),
                original.organizationId(), projectId, "SubscriptionPayment", reversalId.toString(),
                cleanReason,
                "Payment reversed: " + original.amount() + " " + original.currency(),
                "ALLOWED", correlationId, null);

        return new PaymentRecordDto(
            reversalId, original.subscriptionId(), projectId, original.organizationId(), reversalKey,
            reversalAmount, original.currency(), original.paymentMethod(),
            "REV-" + original.referenceNumber(), original.periodStart(), original.periodEnd(),
            operator.operatorId(), "Reversal: " + cleanReason, true, original.id(), cleanReason, now, false
        );
    }

    /**
     * Recalculates and restores the paidUntil and status for a subscription based on remaining valid periods.
     */
    private void recalculateSubscriptionPaidUntil(UUID subscriptionId, UUID projectId) {
        // Query remaining non-reversed, non-reversal payments
        List<Map<String, Object>> validPayments = jdbc.queryForList("""
            select p."periodStart", p."periodEnd"
              from "public"."subscription_payments" p
             where p."subscriptionId" = :subId
               and p."isReversal" = false
               and not exists (
                   select 1 from "public"."subscription_payments" r
                    where r."reversalOfPaymentId" = p."id"
               )
             order by p."periodEnd" desc
            """,
            new MapSqlParameterSource("subId", subscriptionId));

        Map<String, Object> subMap = getSubscriptionForProject(projectId);
        String currentStatus = (String) subMap.get("status");
        Timestamp trialEndsAt = (Timestamp) subMap.get("trialEndsAt");
        int graceDays = ((Number) subMap.get("gracePeriodDays")).intValue();

        Instant newPaidUntil;
        if (!validPayments.isEmpty()) {
            Timestamp latestEnd = (Timestamp) validPayments.get(0).get("periodEnd");
            newPaidUntil = latestEnd.toInstant();
        } else if (trialEndsAt != null) {
            newPaidUntil = trialEndsAt.toInstant();
        } else {
            // Default fallback: 14 days from subscription creation
            Timestamp createdAt = (Timestamp) subMap.get("createdAt");
            newPaidUntil = createdAt != null ? createdAt.toInstant().plus(Duration.ofDays(14)) : Instant.now();
        }

        Instant now = Instant.now();
        String nextStatus;
        if ("SUSPENDED".equalsIgnoreCase(currentStatus)) {
            nextStatus = "SUSPENDED";
        } else if (newPaidUntil.isAfter(now)) {
            nextStatus = "ACTIVE";
        } else if (now.isBefore(newPaidUntil.plus(Duration.ofDays(graceDays)))) {
            nextStatus = "GRACE";
        } else {
            nextStatus = "EXPIRED";
        }

        jdbc.update("""
            update "public"."clinic_subscriptions"
               set "paidUntil" = :paidUntil,
                   "status" = :nextStatus,
                   "version" = "version" + 1,
                   "updatedAt" = (current_timestamp at time zone 'utc')
             where "id" = :subId
            """,
            new MapSqlParameterSource()
                .addValue("paidUntil", Timestamp.from(newPaidUntil))
                .addValue("nextStatus", nextStatus)
                .addValue("subId", subscriptionId));

        if (!"SUSPENDED".equalsIgnoreCase(currentStatus)) {
            jdbc.update("""
                update "public"."projects"
                   set "status" = :nextStatus,
                       "version" = "version" + 1,
                       "updatedAt" = (current_timestamp at time zone 'utc')
                 where "id" = :projectId
                """,
                new MapSqlParameterSource().addValue("nextStatus", nextStatus).addValue("projectId", projectId));
        }
    }

    private Map<String, Object> getSubscriptionForProject(UUID projectId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            select "id", "organizationId", "paidUntil", "trialEndsAt", "gracePeriodDays",
                   "status", "version", "createdAt"
              from "public"."clinic_subscriptions"
             where "projectId" = :projectId
            """,
            new MapSqlParameterSource("projectId", projectId));
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("No subscription found for clinic project: " + projectId);
        }
        return rows.get(0);
    }

    private PaymentRecordDto mapPaymentRow(ResultSet rs, int rowNum) throws SQLException {
        return new PaymentRecordDto(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("subscriptionId"),
            (UUID) rs.getObject("projectId"),
            (UUID) rs.getObject("organizationId"),
            rs.getString("idempotencyKey"),
            rs.getBigDecimal("amount"),
            rs.getString("currency"),
            rs.getString("paymentMethod"),
            rs.getString("referenceNumber"),
            rs.getTimestamp("periodStart").toInstant(),
            rs.getTimestamp("periodEnd").toInstant(),
            (UUID) rs.getObject("recordedByOperatorId"),
            rs.getString("notes"),
            rs.getBoolean("isReversal"),
            (UUID) rs.getObject("reversalOfPaymentId"),
            rs.getString("reversalReason"),
            rs.getTimestamp("createdAt").toInstant(),
            rs.getBoolean("isReversed")
        );
    }
}
