package com.firstelement.security.platform;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Service providing platform analytics, operational key performance indicators (KPIs),
 * the "Needs Attention" triage feed, visual chart aggregates, and recent operator audit trails.
 *
 * <p>Compliance notes:
 * <ul>
 *   <li>Registered as a `@Bean` in {@link PlatformConsoleConfiguration} to respect ADR-0020.</li>
 *   <li>Subscription revenue refers strictly to PLATFORM subscription income, not clinical revenue.</li>
 *   <li>Amounts are represented in JOD with 3 decimal precision.</li>
 * </ul>
 */
public class PlatformDashboardService {

    private final NamedParameterJdbcTemplate jdbc;
    private final PlatformAuditRepository audit;

    public PlatformDashboardService(NamedParameterJdbcTemplate jdbc, PlatformAuditRepository audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record DashboardKpisDto(
        int totalClinics,
        int activeClinics,
        int trialClinics,
        int graceClinics,
        int suspendedClinics,
        int expiredClinics,
        int expiringIn7Days,
        int expiringIn30Days,
        BigDecimal subscriptionRevenueThisMonth,
        String currency,
        int newClinicsThisMonth
    ) {}

    public record NeedsAttentionItemDto(
        UUID clinicId,
        UUID organizationId,
        String clinicName,
        String organizationName,
        String subscriptionStatus,
        String attentionType,
        String attentionReason,
        String severity,
        Instant paidUntil,
        String adminName,
        String adminEmail
    ) {}

    public record RevenueByMonthDto(
        String monthKey,
        String monthLabel,
        BigDecimal amount,
        String currency
    ) {}

    public record ClinicsByStatusDto(
        String status,
        int count,
        double percentage
    ) {}

    public record ClinicAiUsageQuotaDto(
        UUID clinicId,
        String clinicName,
        int usageCount,
        int quotaLimit,
        double usagePercent
    ) {}

    public record PlatformAuditEventDto(
        UUID id,
        Instant timestamp,
        String action,
        UUID operatorId,
        String operatorEmail,
        String operatorName,
        String targetType,
        String targetId,
        String clinicName,
        String outcome,
        String details,
        String ipAddress,
        String correlationId
    ) {}

    public record OperatorDashboardOverviewDto(
        DashboardKpisDto kpis,
        List<NeedsAttentionItemDto> needsAttention,
        List<RevenueByMonthDto> revenueByMonth,
        List<ClinicsByStatusDto> clinicsByStatus,
        List<ClinicAiUsageQuotaDto> aiUsage,
        List<PlatformAuditEventDto> recentAuditEvents
    ) {}

    public OperatorDashboardOverviewDto getOverview(PlatformOperatorContext operator, String correlationId) {
        audit.record(
            PlatformAuditRepository.VIEWED,
            operator.operatorId(),
            operator.sessionId(),
            null,
            null,
            "PlatformDashboard",
            null,
            "viewing operator dashboard overview",
            "overview",
            PlatformAuditRepository.ALLOWED,
            correlationId,
            null
        );

        DashboardKpisDto kpis = computeKpis();
        List<NeedsAttentionItemDto> needsAttention = computeNeedsAttention();
        List<RevenueByMonthDto> revenueByMonth = computeRevenueByMonth();
        List<ClinicsByStatusDto> clinicsByStatus = computeClinicsByStatus(kpis.totalClinics());
        List<ClinicAiUsageQuotaDto> aiUsage = computeAiUsage();
        List<PlatformAuditEventDto> recentAuditEvents = computeRecentAuditEvents();

        return new OperatorDashboardOverviewDto(
            kpis,
            needsAttention,
            revenueByMonth,
            clinicsByStatus,
            aiUsage,
            recentAuditEvents
        );
    }

    private DashboardKpisDto computeKpis() {
        Integer totalClinics = jdbc.queryForObject(
            "select count(*) from \"public\".\"projects\" where \"isArchived\" = false",
            Map.of(),
            Integer.class
        );
        int total = totalClinics != null ? totalClinics : 0;

        List<Map<String, Object>> statusRows = jdbc.queryForList("""
            select coalesce(cs."status", p."status") as "status", count(*) as "cnt"
              from "public"."projects" p
              left join "public"."clinic_subscriptions" cs on cs."projectId" = p."id"
             where p."isArchived" = false
             group by coalesce(cs."status", p."status")
            """, Map.of());

        int active = 0, trial = 0, grace = 0, suspended = 0, expired = 0;
        for (Map<String, Object> row : statusRows) {
            String st = String.valueOf(row.get("status")).toUpperCase(Locale.ROOT);
            int count = ((Number) row.get("cnt")).intValue();
            switch (st) {
                case "ACTIVE" -> active += count;
                case "TRIAL" -> trial += count;
                case "GRACE" -> grace += count;
                case "SUSPENDED" -> suspended += count;
                case "EXPIRED" -> expired += count;
                default -> active += count;
            }
        }

        Integer expiring7 = jdbc.queryForObject("""
            select count(*)
              from "public"."projects" p
              join "public"."clinic_subscriptions" cs on cs."projectId" = p."id"
             where p."isArchived" = false
               and cs."paidUntil" is not null
               and cs."paidUntil" between (current_timestamp at time zone 'utc')
                                      and (current_timestamp at time zone 'utc' + interval '7 days')
            """, Map.of(), Integer.class);

        Integer expiring30 = jdbc.queryForObject("""
            select count(*)
              from "public"."projects" p
              join "public"."clinic_subscriptions" cs on cs."projectId" = p."id"
             where p."isArchived" = false
               and cs."paidUntil" is not null
               and cs."paidUntil" between (current_timestamp at time zone 'utc')
                                      and (current_timestamp at time zone 'utc' + interval '30 days')
            """, Map.of(), Integer.class);

        BigDecimal currentMonthRevenue = jdbc.queryForObject("""
            select coalesce(sum(case when "isReversal" then -"amount" else "amount" end), 0)
              from "public"."subscription_payments"
             where "createdAt" >= date_trunc('month', current_timestamp at time zone 'utc')
            """, Map.of(), BigDecimal.class);

        BigDecimal revenue = currentMonthRevenue != null
            ? currentMonthRevenue.setScale(3, RoundingMode.HALF_UP)
            : BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP);

        Integer newClinics = jdbc.queryForObject("""
            select count(*)
              from "public"."projects"
             where "createdAt" >= date_trunc('month', current_timestamp at time zone 'utc')
               and "isArchived" = false
            """, Map.of(), Integer.class);

        return new DashboardKpisDto(
            total,
            active,
            trial,
            grace,
            suspended,
            expired,
            expiring7 != null ? expiring7 : 0,
            expiring30 != null ? expiring30 : 0,
            revenue,
            "JOD",
            newClinics != null ? newClinics : 0
        );
    }

    private List<NeedsAttentionItemDto> computeNeedsAttention() {
        String sql = """
            select p."id"                                  as "clinicId",
                   p."organizationId"                      as "organizationId",
                   p."name"                                as "clinicName",
                   o."name"                                as "organizationName",
                   coalesce(cs."status", p."status")       as "subscriptionStatus",
                   cs."paidUntil"                          as "paidUntil",
                   cs."trialEndsAt"                        as "trialEndsAt",
                   cs."gracePeriodDays"                    as "gracePeriodDays",
                   (select u."fullName" from "public"."users" u
                      join "public"."project_memberships" pm on pm."userId" = u."id"
                     where pm."projectId" = p."id" and u."role" = 'ADMIN'
                     order by u."createdAt" asc limit 1)   as "adminName",
                   (select u."email" from "public"."users" u
                      join "public"."project_memberships" pm on pm."userId" = u."id"
                     where pm."projectId" = p."id" and u."role" = 'ADMIN'
                     order by u."createdAt" asc limit 1)   as "adminEmail",
                   (select coalesce(u."isActive", false) from "public"."users" u
                      join "public"."project_memberships" pm on pm."userId" = u."id"
                     where pm."projectId" = p."id" and u."role" = 'ADMIN'
                     order by u."createdAt" asc limit 1)   as "adminIsActive",
                   (select coalesce(u."invitePending", false) from "public"."users" u
                      join "public"."project_memberships" pm on pm."userId" = u."id"
                     where pm."projectId" = p."id" and u."role" = 'ADMIN'
                     order by u."createdAt" asc limit 1)   as "adminInvitePending",
                   (select count(*) from "public"."export_jobs" ej
                     where ej."projectId" = p."id"
                       and cast(ej."status" as text) = 'FAILED'
                       and ej."createdAt" >= current_timestamp at time zone 'utc' - interval '7 days') as "failedJobsCount"
              from "public"."projects" p
              join "public"."organizations" o on o."id" = p."organizationId"
              left join "public"."clinic_subscriptions" cs on cs."projectId" = p."id"
             where p."isArchived" = false
             order by p."name" asc
            """;

        List<Map<String, Object>> rows = jdbc.queryForList(sql, Map.of());
        List<NeedsAttentionItemDto> items = new ArrayList<>();
        Instant now = Instant.now();
        Instant inSevenDays = now.plus(7, ChronoUnit.DAYS);

        for (Map<String, Object> row : rows) {
            UUID clinicId = (UUID) row.get("clinicId");
            UUID orgId = (UUID) row.get("organizationId");
            String clinicName = (String) row.get("clinicName");
            String orgName = (String) row.get("organizationName");
            String status = String.valueOf(row.get("subscriptionStatus")).toUpperCase(Locale.ROOT);
            Timestamp tsPaidUntil = (Timestamp) row.get("paidUntil");
            Instant paidUntil = tsPaidUntil != null ? tsPaidUntil.toInstant() : null;
            String adminName = (String) row.get("adminName");
            String adminEmail = (String) row.get("adminEmail");
            Boolean adminIsActive = (Boolean) row.get("adminIsActive");
            Boolean adminInvitePending = (Boolean) row.get("adminInvitePending");
            int failedJobs = ((Number) row.get("failedJobsCount")).intValue();

            // 1. Failed backup / export jobs
            if (failedJobs > 0) {
                items.add(new NeedsAttentionItemDto(
                    clinicId, orgId, clinicName, orgName, status,
                    "FAILED_JOBS",
                    String.format("%d backup or export job(s) failed in the last 7 days", failedJobs),
                    "CRITICAL",
                    paidUntil, adminName, adminEmail
                ));
            }

            // 2. Suspended
            if ("SUSPENDED".equals(status)) {
                items.add(new NeedsAttentionItemDto(
                    clinicId, orgId, clinicName, orgName, status,
                    "SUSPENDED",
                    "Clinic access suspended; staff mutations blocked",
                    "CRITICAL",
                    paidUntil, adminName, adminEmail
                ));
            } else if ("EXPIRED".equals(status)) {
                items.add(new NeedsAttentionItemDto(
                    clinicId, orgId, clinicName, orgName, status,
                    "OVERDUE",
                    "Subscription expired; clinic in read-only renewal mode",
                    "CRITICAL",
                    paidUntil, adminName, adminEmail
                ));
            } else if ("GRACE".equals(status)) {
                items.add(new NeedsAttentionItemDto(
                    clinicId, orgId, clinicName, orgName, status,
                    "IN_GRACE",
                    "In grace period; renewal payment urgently required",
                    "WARNING",
                    paidUntil, adminName, adminEmail
                ));
            } else if (paidUntil != null && paidUntil.isAfter(now) && paidUntil.isBefore(inSevenDays)) {
                long days = ChronoUnit.DAYS.between(now, paidUntil);
                items.add(new NeedsAttentionItemDto(
                    clinicId, orgId, clinicName, orgName, status,
                    "EXPIRING_SOON",
                    String.format("Subscription expires in %d day(s)", Math.max(1, days)),
                    "WARNING",
                    paidUntil, adminName, adminEmail
                ));
            }

            // 3. No active admin
            if (adminEmail == null) {
                items.add(new NeedsAttentionItemDto(
                    clinicId, orgId, clinicName, orgName, status,
                    "NO_ACTIVE_ADMIN",
                    "No clinic administrator account provisioned",
                    "CRITICAL",
                    paidUntil, null, null
                ));
            } else if (Boolean.FALSE.equals(adminIsActive)) {
                items.add(new NeedsAttentionItemDto(
                    clinicId, orgId, clinicName, orgName, status,
                    "NO_ACTIVE_ADMIN",
                    String.format("Administrator %s is deactivated", adminEmail),
                    "WARNING",
                    paidUntil, adminName, adminEmail
                ));
            } else if (Boolean.TRUE.equals(adminInvitePending)) {
                items.add(new NeedsAttentionItemDto(
                    clinicId, orgId, clinicName, orgName, status,
                    "NO_ACTIVE_ADMIN",
                    String.format("Administrator invite for %s is pending activation", adminEmail),
                    "INFO",
                    paidUntil, adminName, adminEmail
                ));
            }
        }

        return items;
    }

    private List<RevenueByMonthDto> computeRevenueByMonth() {
        // Compute last 6 calendar months
        LocalDate currentMonth = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
        List<LocalDate> months = new ArrayList<>();
        for (int i = 5; i >= 0; i--) {
            months.add(currentMonth.minusMonths(i));
        }

        String sql = """
            select to_char(date_trunc('month', "createdAt"), 'YYYY-MM') as "monthKey",
                   coalesce(sum(case when "isReversal" then -"amount" else "amount" end), 0) as "revenue"
              from "public"."subscription_payments"
             where "createdAt" >= date_trunc('month', current_timestamp at time zone 'utc') - interval '5 months'
             group by date_trunc('month', "createdAt")
            """;

        Map<String, BigDecimal> actualMap = new HashMap<>();
        jdbc.query(sql, rs -> {
            actualMap.put(
                rs.getString("monthKey"),
                rs.getBigDecimal("revenue").setScale(3, RoundingMode.HALF_UP)
            );
        });

        DateTimeFormatter keyFmt = DateTimeFormatter.ofPattern("yyyy-MM", Locale.ROOT);
        DateTimeFormatter labelFmt = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ROOT);

        List<RevenueByMonthDto> list = new ArrayList<>();
        for (LocalDate m : months) {
            String key = m.format(keyFmt);
            String label = m.format(labelFmt);
            BigDecimal amt = actualMap.getOrDefault(key, BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP));
            list.add(new RevenueByMonthDto(key, label, amt, "JOD"));
        }
        return list;
    }

    private List<ClinicsByStatusDto> computeClinicsByStatus(int totalClinics) {
        if (totalClinics == 0) {
            return List.of(
                new ClinicsByStatusDto("ACTIVE", 0, 0.0),
                new ClinicsByStatusDto("TRIAL", 0, 0.0),
                new ClinicsByStatusDto("GRACE", 0, 0.0),
                new ClinicsByStatusDto("SUSPENDED", 0, 0.0),
                new ClinicsByStatusDto("EXPIRED", 0, 0.0)
            );
        }

        List<Map<String, Object>> rows = jdbc.queryForList("""
            select coalesce(cs."status", p."status") as "status", count(*) as "cnt"
              from "public"."projects" p
              left join "public"."clinic_subscriptions" cs on cs."projectId" = p."id"
             where p."isArchived" = false
             group by coalesce(cs."status", p."status")
            """, Map.of());

        Map<String, Integer> counts = new HashMap<>();
        for (Map<String, Object> r : rows) {
            String s = String.valueOf(r.get("status")).toUpperCase(Locale.ROOT);
            int c = ((Number) r.get("cnt")).intValue();
            counts.put(s, c);
        }

        List<ClinicsByStatusDto> list = new ArrayList<>();
        for (String st : List.of("ACTIVE", "TRIAL", "GRACE", "SUSPENDED", "EXPIRED")) {
            int cnt = counts.getOrDefault(st, 0);
            double pct = Math.round((cnt * 100.0 / totalClinics) * 10.0) / 10.0;
            list.add(new ClinicsByStatusDto(st, cnt, pct));
        }
        return list;
    }

    private List<ClinicAiUsageQuotaDto> computeAiUsage() {
        String sql = """
            select p."id"                                                                   as "clinicId",
                   p."name"                                                                 as "clinicName",
                   coalesce(cs."monthlyAiQuotaOverride", pl."monthlyAiQuota", 500)          as "quotaLimit",
                   (select count(*) from "public"."clinic_ai_proposals" cap
                     where cap."projectId" = p."id"
                       and cap."createdAt" >= date_trunc('month', current_timestamp at time zone 'utc')) as "usageCount"
              from "public"."projects" p
              left join "public"."clinic_subscriptions" cs on cs."projectId" = p."id"
              left join "public"."plans" pl on pl."id" = cs."planId"
             where p."isArchived" = false
             order by "usageCount" desc, p."name" asc
             limit 10
            """;

        return jdbc.query(sql, (rs, rowNum) -> {
            int usage = rs.getInt("usageCount");
            int quota = rs.getInt("quotaLimit");
            double pct = quota > 0 ? Math.min(100.0, Math.round((usage * 100.0 / quota) * 10.0) / 10.0) : 0.0;
            return new ClinicAiUsageQuotaDto(
                rs.getObject("clinicId", UUID.class),
                rs.getString("clinicName"),
                usage,
                quota,
                pct
            );
        });
    }

    private List<PlatformAuditEventDto> computeRecentAuditEvents() {
        String sql = """
            select pae."id",
                   pae."occurredAt"                                   as "timestamp",
                   pae."action",
                   pae."operatorId",
                   po."email"                                         as "operatorEmail",
                   po."displayName"                                   as "operatorName",
                   pae."resourceType"                                 as "targetType",
                   pae."resourceId"                                   as "targetId",
                   p."name"                                           as "clinicName",
                   pae."outcome",
                   coalesce(pae."detail", pae."reason")               as "details",
                   pae."ipAddress",
                   pae."correlationId"
              from "public"."platform_audit_events" pae
              left join "public"."platform_operators" po on po."id" = pae."operatorId"
              left join "public"."projects" p on p."id" = pae."projectId"
             order by pae."occurredAt" desc
             limit 15
            """;

        return jdbc.query(sql, (rs, rowNum) -> {
            Timestamp ts = rs.getTimestamp("timestamp");
            return new PlatformAuditEventDto(
                rs.getObject("id", UUID.class),
                ts != null ? ts.toInstant() : null,
                rs.getString("action"),
                rs.getObject("operatorId", UUID.class),
                rs.getString("operatorEmail"),
                rs.getString("operatorName"),
                rs.getString("targetType"),
                rs.getString("targetId"),
                rs.getString("clinicName"),
                rs.getString("outcome"),
                rs.getString("details"),
                rs.getString("ipAddress"),
                rs.getString("correlationId")
            );
        });
    }
}
