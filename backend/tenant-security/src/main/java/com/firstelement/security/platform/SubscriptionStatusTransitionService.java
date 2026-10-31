package com.firstelement.security.platform;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent Scheduled Subscription Status Transition and Expiry Reminder Job.
 *
 * <p>Specification compliance:
 * - Statuses: Trial, Active, Grace, Suspended, Expired.
 * - Scheduled, idempotent job moves clinics between statuses based on paid_until and grace period.
 * - Suspended clinics are administrative locks under DEC-007 and are NEVER modified by the job.
 * - Reminders at 7, 3 and 1 day(s) before expiry to the clinic admin.
 * - Grace = full access + warning banner; Suspended/Expired = blocked from write (read-only); data untouched.
 */
public class SubscriptionStatusTransitionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionStatusTransitionService.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final PlatformAuditRepository audit;

    public SubscriptionStatusTransitionService(NamedParameterJdbcTemplate jdbc, PlatformAuditRepository audit) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    public record TransitionResult(
        int totalEvaluated,
        int statusChangedCount,
        int remindersDispatched,
        Instant evaluationTime,
        List<String> details
    ) {}

    public record SubscriptionCandidate(
        UUID subscriptionId,
        UUID projectId,
        UUID organizationId,
        String currentStatus,
        Instant paidUntil,
        Instant trialEndsAt,
        int gracePeriodDays,
        int version,
        String timezone,
        String adminEmail,
        String clinicName
    ) {}

    /**
     * Periodic scheduled run: runs every 30 minutes in production.
     */
    @Scheduled(fixedDelayString = "${firstelement.platform.subscription-job-interval-ms:1800000}")
    public void scheduledRun() {
        try {
            TransitionResult result = runTransitionJob(Instant.now(), null, "SCHEDULED");
            if (result.statusChangedCount() > 0 || result.remindersDispatched() > 0) {
                log.info("Subscription status job completed: {} changed, {} reminders, {} evaluated.",
                        result.statusChangedCount(), result.remindersDispatched(), result.totalEvaluated());
            }
        } catch (Exception ex) {
            log.error("Scheduled subscription transition job failed: {}", ex.getMessage(), ex);
        }
    }

    /**
     * Executes the idempotent status transition and reminder algorithm for all eligible clinics.
     * Can be invoked with a simulated referenceTime for testing boundary precision.
     */
    @Transactional
    public TransitionResult runTransitionJob(Instant referenceTime, PlatformOperatorContext operator, String correlationId) {
        Instant evalTime = referenceTime != null ? referenceTime : Instant.now();
        List<SubscriptionCandidate> candidates = loadActiveCandidates();
        int changedCount = 0;
        int remindersCount = 0;
        List<String> logMessages = new ArrayList<>();

        for (SubscriptionCandidate sub : candidates) {
            // Determine effective expiration boundary
            Instant boundary = sub.trialEndsAt() != null && "TRIAL".equalsIgnoreCase(sub.currentStatus())
                    ? sub.trialEndsAt()
                    : sub.paidUntil();

            if (boundary == null) {
                continue;
            }

            // Adjust comparison according to clinic timezone if specified
            ZoneId zoneId = ZoneId.of("Asia/Amman");
            if (sub.timezone() != null && !sub.timezone().isBlank()) {
                try {
                    zoneId = ZoneId.of(sub.timezone().trim());
                } catch (Exception ignored) {
                    // Fallback to Asia/Amman
                }
            }

            Instant graceBoundary = boundary.plus(Duration.ofDays(sub.gracePeriodDays()));

            // Determine target status
            String targetStatus;
            if (!evalTime.isAfter(boundary)) {
                // Not expired yet
                targetStatus = "TRIAL".equalsIgnoreCase(sub.currentStatus()) ? "TRIAL" : "ACTIVE";
            } else if (!evalTime.isAfter(graceBoundary)) {
                // Past paid_until / trial_ends_at, but within grace period
                targetStatus = "GRACE";
            } else {
                // Past grace period
                targetStatus = "EXPIRED";
            }

            // Idempotency: only execute update if status changed
            if (!targetStatus.equalsIgnoreCase(sub.currentStatus())) {
                boolean updated = updateSubscriptionStatus(sub.subscriptionId(), sub.projectId(),
                        targetStatus, sub.version(), evalTime);
                if (updated) {
                    changedCount++;
                    String note = String.format("Clinic %s (%s) transitioned from %s to %s [boundary=%s, grace=%s]",
                            sub.clinicName(), sub.projectId(), sub.currentStatus(), targetStatus, boundary, graceBoundary);
                    logMessages.add(note);

                    UUID opId = operator != null ? operator.operatorId() : null;
                    UUID sessId = operator != null ? operator.sessionId() : null;
                    audit.record("SUBSCRIPTION_STATUS_CHANGED", opId, sessId,
                            sub.organizationId(), sub.projectId(), "ClinicSubscription", sub.subscriptionId().toString(),
                            "Automated status transition", note, "ALLOWED", correlationId, null);
                }
            }

            // -------------------------------------------------------------
            // Expiry reminders at 7, 3, and 1 day(s) before expiry
            // -------------------------------------------------------------
            if (evalTime.isBefore(boundary)) {
                long daysUntilExpiry = ChronoUnit.DAYS.between(
                        evalTime.atZone(zoneId).toLocalDate(),
                        boundary.atZone(zoneId).toLocalDate()
                );

                if (daysUntilExpiry == 7 || daysUntilExpiry == 3 || daysUntilExpiry == 1) {
                    boolean sent = recordReminderNotice(sub.projectId(), sub.organizationId(),
                            sub.adminEmail(), (int) daysUntilExpiry, boundary, evalTime);
                    if (sent) {
                        remindersCount++;
                    }
                }
            }
        }

        return new TransitionResult(candidates.size(), changedCount, remindersCount, evalTime, logMessages);
    }

    private List<SubscriptionCandidate> loadActiveCandidates() {
        return jdbc.query("""
            select s."id" as "subscriptionId",
                   s."projectId",
                   s."organizationId",
                   s."status" as "currentStatus",
                   s."paidUntil",
                   s."trialEndsAt",
                   s."gracePeriodDays",
                   s."version",
                   p."timezone",
                   p."name" as "clinicName",
                   u."email" as "adminEmail"
              from "public"."clinic_subscriptions" s
              join "public"."projects" p on s."projectId" = p."id"
              left join "public"."users" u on u."organizationId" = s."organizationId" and u."role" = 'ADMIN'
             where s."status" != 'SUSPENDED'
               and p."status" != 'SUSPENDED'
            """,
            new MapSqlParameterSource(),
            this::mapCandidateRow);
    }

    private boolean updateSubscriptionStatus(UUID subId, UUID projectId, String targetStatus,
                                            int expectedVersion, Instant now) {
        int rows = jdbc.update("""
            update "public"."clinic_subscriptions"
               set "status" = :status,
                   "version" = "version" + 1,
                   "updatedAt" = :now
             where "id" = :subId
               and "version" = :expectedVersion
            """,
            new MapSqlParameterSource()
                .addValue("status", targetStatus)
                .addValue("now", Timestamp.from(now))
                .addValue("subId", subId)
                .addValue("expectedVersion", expectedVersion));

        if (rows > 0) {
            jdbc.update("""
                update "public"."projects"
                   set "status" = :status,
                       "version" = "version" + 1,
                       "updatedAt" = :now
                 where "id" = :projectId
                """,
                new MapSqlParameterSource()
                    .addValue("status", targetStatus)
                    .addValue("now", Timestamp.from(now))
                    .addValue("projectId", projectId));
            return true;
        }
        return false;
    }

    /**
     * Dispatches/records an idempotent expiry reminder for a clinic admin.
     */
    private boolean recordReminderNotice(UUID projectId, UUID orgId, String email,
                                         int daysRemaining, Instant expiryDate, Instant now) {
        // Idempotency: only record one reminder per clinic per day
        String eventKey = "EXPIRY_REMINDER_" + daysRemaining + "_DAYS";
        Integer existingCount = jdbc.queryForObject("""
            select count(*)
              from "public"."platform_audit_events"
             where "projectId" = :projectId
               and "action" = :eventKey
               and "createdAt" >= :today
            """,
            new MapSqlParameterSource()
                .addValue("projectId", projectId)
                .addValue("eventKey", eventKey)
                .addValue("today", Timestamp.from(now.truncatedTo(ChronoUnit.DAYS))),
            Integer.class);

        if (existingCount != null && existingCount > 0) {
            return false;
        }

        audit.record(eventKey,
                null,
                null,
                orgId, projectId, "Notification", email != null ? email : "admin",
                "Expiry reminder scheduled",
                daysRemaining + " days before expiry reminder recorded for " + email,
                "ALLOWED", null, null);
        return true;
    }

    private SubscriptionCandidate mapCandidateRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp paidTs = rs.getTimestamp("paidUntil");
        Timestamp trialTs = rs.getTimestamp("trialEndsAt");
        return new SubscriptionCandidate(
            (UUID) rs.getObject("subscriptionId"),
            (UUID) rs.getObject("projectId"),
            (UUID) rs.getObject("organizationId"),
            rs.getString("currentStatus"),
            paidTs != null ? paidTs.toInstant() : null,
            trialTs != null ? trialTs.toInstant() : null,
            rs.getInt("gracePeriodDays"),
            rs.getInt("version"),
            rs.getString("timezone"),
            rs.getString("adminEmail"),
            rs.getString("clinicName")
        );
    }
}
