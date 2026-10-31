package com.firstelement.security.platform;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Platform Operator Plan Management & Clinic Limits Overrides Service (Slice 4).
 *
 * <p>Specification compliance:
 * - Plan = max users, max branches, storage quota, monthly AI quota, feature entitlements.
 * - Per-clinic overrides for numerical limits and feature entitlements.
 * - Zero component-scan stereotype annotations (@Service, @Repository) per ADR-0020 and PlatformConsoleDisabledTest.
 * - Complete audit logging for all operator plan and limit mutations.
 */
public class PlatformPlanService {

    private final NamedParameterJdbcTemplate jdbc;
    private final PlatformAuditRepository audit;

    public PlatformPlanService(NamedParameterJdbcTemplate jdbc, PlatformAuditRepository audit) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    public record PlanSummaryDto(
        UUID id,
        String code,
        String name,
        String description,
        BigDecimal priceAmount,
        String currency,
        String billingInterval,
        int maxUsers,
        int maxBranches,
        int storageQuotaMb,
        int monthlyAiQuota,
        boolean isActive,
        int version,
        List<String> enabledFeatures,
        int activeSubscriptionsCount
    ) {}

    public record PlanDetailDto(
        UUID id,
        String code,
        String name,
        String description,
        BigDecimal priceAmount,
        String currency,
        String billingInterval,
        int maxUsers,
        int maxBranches,
        int storageQuotaMb,
        int monthlyAiQuota,
        boolean isActive,
        int version,
        Map<String, Boolean> entitlements,
        int activeSubscriptionsCount
    ) {}

    public record UpdatePlanCommand(
        String name,
        String description,
        BigDecimal priceAmount,
        Integer maxUsers,
        Integer maxBranches,
        Integer storageQuotaMb,
        Integer monthlyAiQuota,
        Map<String, Boolean> entitlements,
        String reason
    ) {}

    public record ClinicLimitsOverrideCommand(
        Integer maxUsers,
        Integer maxBranches,
        Integer storageQuotaMb,
        Integer monthlyAiQuota,
        String reason
    ) {}

    public record ClinicLimitsDto(
        UUID projectId,
        String clinicName,
        String planCode,
        String planName,
        int effectiveMaxUsers,
        boolean isMaxUsersOverridden,
        Integer maxUsersOverride,
        int effectiveMaxBranches,
        boolean isMaxBranchesOverridden,
        Integer maxBranchesOverride,
        int effectiveStorageQuotaMb,
        boolean isStorageQuotaOverridden,
        Integer storageQuotaMbOverride,
        int effectiveMonthlyAiQuota,
        boolean isMonthlyAiQuotaOverridden,
        Integer monthlyAiQuotaOverride,
        Map<String, Boolean> entitlements,
        int activeUsersCount,
        int activeBranchesCount,
        int monthlyAiRunsCount
    ) {}

    /**
     * Lists all subscription plans with entitlement features and subscriber counts.
     */
    @Transactional(readOnly = true)
    public List<PlanSummaryDto> listPlans() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            select p."id",
                   p."code",
                   p."name",
                   p."description",
                   p."priceAmount",
                   p."currency",
                   p."billingInterval",
                   p."maxUsers",
                   p."maxBranches",
                   p."storageQuotaMb",
                   p."monthlyAiQuota",
                   p."isActive",
                   p."version",
                   coalesce(count(distinct cs."id"), 0) as "subCount"
              from "public"."plans" p
              left join "public"."clinic_subscriptions" cs on cs."planId" = p."id"
             group by p."id"
             order by p."priceAmount" asc
            """,
            new MapSqlParameterSource());

        List<PlanSummaryDto> result = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            UUID planId = (UUID) r.get("id");
            List<String> enabledFeatures = jdbc.queryForList("""
                select "featureKey"
                  from "public"."plan_entitlements"
                 where "planId" = :planId
                   and "enabled" = true
                 order by "featureKey" asc
                """,
                new MapSqlParameterSource("planId", planId),
                String.class);

            result.add(new PlanSummaryDto(
                planId,
                (String) r.get("code"),
                (String) r.get("name"),
                (String) r.get("description"),
                (BigDecimal) r.get("priceAmount"),
                (String) r.get("currency"),
                (String) r.get("billingInterval"),
                ((Number) r.get("maxUsers")).intValue(),
                ((Number) r.get("maxBranches")).intValue(),
                ((Number) r.get("storageQuotaMb")).intValue(),
                ((Number) r.get("monthlyAiQuota")).intValue(),
                (Boolean) r.get("isActive"),
                ((Number) r.get("version")).intValue(),
                enabledFeatures,
                ((Number) r.get("subCount")).intValue()
            ));
        }
        return result;
    }

    /**
     * Retrieves detailed plan configuration including all feature entitlement flags.
     */
    @Transactional(readOnly = true)
    public PlanDetailDto getPlan(UUID planId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            select p."id",
                   p."code",
                   p."name",
                   p."description",
                   p."priceAmount",
                   p."currency",
                   p."billingInterval",
                   p."maxUsers",
                   p."maxBranches",
                   p."storageQuotaMb",
                   p."monthlyAiQuota",
                   p."isActive",
                   p."version",
                   coalesce(count(distinct cs."id"), 0) as "subCount"
              from "public"."plans" p
              left join "public"."clinic_subscriptions" cs on cs."planId" = p."id"
             where p."id" = :planId
             group by p."id"
            """,
            new MapSqlParameterSource("planId", planId));

        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Plan not found: " + planId);
        }

        Map<String, Object> r = rows.get(0);
        Map<String, Boolean> entitlements = new LinkedHashMap<>();
        jdbc.query("""
            select "featureKey", "enabled"
              from "public"."plan_entitlements"
             where "planId" = :planId
             order by "featureKey" asc
            """,
            new MapSqlParameterSource("planId", planId),
            rs -> {
                entitlements.put(rs.getString("featureKey"), rs.getBoolean("enabled"));
            });

        return new PlanDetailDto(
            planId,
            (String) r.get("code"),
            (String) r.get("name"),
            (String) r.get("description"),
            (BigDecimal) r.get("priceAmount"),
            (String) r.get("currency"),
            (String) r.get("billingInterval"),
            ((Number) r.get("maxUsers")).intValue(),
            ((Number) r.get("maxBranches")).intValue(),
            ((Number) r.get("storageQuotaMb")).intValue(),
            ((Number) r.get("monthlyAiQuota")).intValue(),
            (Boolean) r.get("isActive"),
            ((Number) r.get("version")).intValue(),
            entitlements,
            ((Number) r.get("subCount")).intValue()
        );
    }

    /**
     * Updates plan limits, pricing, and default entitlements.
     */
    @Transactional
    public PlanDetailDto updatePlan(PlatformOperatorContext operator, UUID planId,
                                    UpdatePlanCommand cmd, String correlationId) {
        Objects.requireNonNull(operator, "operator");
        if (cmd.reason() == null || cmd.reason().isBlank()) {
            throw new IllegalArgumentException("Reason is required to update plan configuration.");
        }

        Timestamp now = Timestamp.from(Instant.now());
        int updated = jdbc.update("""
            update "public"."plans"
               set "name" = coalesce(:name, "name"),
                   "description" = coalesce(:desc, "description"),
                   "priceAmount" = coalesce(:price, "priceAmount"),
                   "maxUsers" = coalesce(:maxUsers, "maxUsers"),
                   "maxBranches" = coalesce(:maxBranches, "maxBranches"),
                   "storageQuotaMb" = coalesce(:storage, "storageQuotaMb"),
                   "monthlyAiQuota" = coalesce(:aiQuota, "monthlyAiQuota"),
                   "version" = "version" + 1,
                   "updatedAt" = :now
             where "id" = :planId
            """,
            new MapSqlParameterSource()
                .addValue("name", cmd.name())
                .addValue("desc", cmd.description())
                .addValue("price", cmd.priceAmount())
                .addValue("maxUsers", cmd.maxUsers())
                .addValue("maxBranches", cmd.maxBranches())
                .addValue("storage", cmd.storageQuotaMb())
                .addValue("aiQuota", cmd.monthlyAiQuota())
                .addValue("now", now)
                .addValue("planId", planId));

        if (updated == 0) {
            throw new IllegalArgumentException("Plan not found: " + planId);
        }

        // Upsert entitlements if supplied
        if (cmd.entitlements() != null) {
            for (Map.Entry<String, Boolean> entry : cmd.entitlements().entrySet()) {
                jdbc.update("""
                    insert into "public"."plan_entitlements" ("id", "planId", "featureKey", "enabled", "createdAt")
                    values (gen_random_uuid(), :planId, :featureKey, :enabled, :now)
                    on conflict ("planId", "featureKey") do update
                        set "enabled" = :enabled
                    """,
                    new MapSqlParameterSource()
                        .addValue("planId", planId)
                        .addValue("featureKey", entry.getKey())
                        .addValue("enabled", entry.getValue())
                        .addValue("now", now));
            }
        }

        audit.record(
            "PLAN_UPDATED",
            operator.operatorId(),
            operator.sessionId(),
            null,
            null,
            "Plan",
            planId.toString(),
            cmd.reason(),
            "Plan " + planId + " limits and entitlements updated by operator " + operator.displayName(),
            "ALLOWED",
            correlationId,
            null
        );

        return getPlan(planId);
    }

    /**
     * Retrieves effective limits and current usage for a clinic.
     */
    @Transactional(readOnly = true)
    public ClinicLimitsDto getClinicLimits(UUID projectId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            select cs."id" as "subscriptionId",
                   cs."projectId",
                   cs."maxUsersOverride",
                   cs."maxBranchesOverride",
                   cs."storageQuotaMbOverride",
                   cs."monthlyAiQuotaOverride",
                   pr."name" as "clinicName",
                   p."id" as "planId",
                   p."code" as "planCode",
                   p."name" as "planName",
                   p."maxUsers" as "planMaxUsers",
                   p."maxBranches" as "planMaxBranches",
                   p."storageQuotaMb" as "planStorageQuotaMb",
                   p."monthlyAiQuota" as "planMonthlyAiQuota"
              from "public"."clinic_subscriptions" cs
              join "public"."projects" pr on cs."projectId" = pr."id"
              join "public"."plans" p on cs."planId" = p."id"
             where cs."projectId" = :projectId
            """,
            new MapSqlParameterSource("projectId", projectId));

        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Clinic subscription not found for project: " + projectId);
        }

        Map<String, Object> r = rows.get(0);
        UUID planId = (UUID) r.get("planId");
        String clinicName = (String) r.get("clinicName");
        String planCode = (String) r.get("planCode");
        String planName = (String) r.get("planName");

        Integer maxUsersOverride = (Integer) r.get("maxUsersOverride");
        Integer maxBranchesOverride = (Integer) r.get("maxBranchesOverride");
        Integer storageQuotaMbOverride = (Integer) r.get("storageQuotaMbOverride");
        Integer monthlyAiQuotaOverride = (Integer) r.get("monthlyAiQuotaOverride");

        int planMaxUsers = ((Number) r.get("planMaxUsers")).intValue();
        int planMaxBranches = ((Number) r.get("planMaxBranches")).intValue();
        int planStorage = ((Number) r.get("planStorageQuotaMb")).intValue();
        int planAiQuota = ((Number) r.get("planMonthlyAiQuota")).intValue();

        Map<String, Boolean> entitlements = new LinkedHashMap<>();
        // Plan entitlements
        jdbc.query("""
            select "featureKey", "enabled"
              from "public"."plan_entitlements"
             where "planId" = :planId
             order by "featureKey" asc
            """,
            new MapSqlParameterSource("planId", planId),
            rs -> {
                entitlements.put(rs.getString("featureKey"), rs.getBoolean("enabled"));
            });

        // Per-clinic entitlement overrides
        jdbc.query("""
            select "featureKey", "enabled"
              from "public"."clinic_entitlement_overrides"
             where "projectId" = :projectId
             order by "featureKey" asc
            """,
            new MapSqlParameterSource("projectId", projectId),
            rs -> {
                entitlements.put(rs.getString("featureKey"), rs.getBoolean("enabled"));
            });

        // Current usages
        Integer activeUsersCount = jdbc.queryForObject("""
            select count(*)
              from "public"."users" u
              join "public"."projects" p on u."organizationId" = p."organizationId"
             where p."id" = :projectId
               and u."isActive" = true
            """,
            new MapSqlParameterSource("projectId", projectId),
            Integer.class);

        Integer activeBranchesCount = jdbc.queryForObject("""
            select count(*)
              from "public"."clinic_locations"
             where "projectId" = :projectId
               and "isActive" = true
            """,
            new MapSqlParameterSource("projectId", projectId),
            Integer.class);

        Integer monthlyAiRunsCount = jdbc.queryForObject("""
            select count(*)
              from "public"."clinic_ai_proposals"
             where "projectId" = :projectId
               and "createdAt" >= date_trunc('month', current_timestamp at time zone 'utc')
            """,
            new MapSqlParameterSource("projectId", projectId),
            Integer.class);

        return new ClinicLimitsDto(
            projectId,
            clinicName,
            planCode,
            planName,
            maxUsersOverride != null ? maxUsersOverride : planMaxUsers,
            maxUsersOverride != null,
            maxUsersOverride,
            maxBranchesOverride != null ? maxBranchesOverride : planMaxBranches,
            maxBranchesOverride != null,
            maxBranchesOverride,
            storageQuotaMbOverride != null ? storageQuotaMbOverride : planStorage,
            storageQuotaMbOverride != null,
            storageQuotaMbOverride,
            monthlyAiQuotaOverride != null ? monthlyAiQuotaOverride : planAiQuota,
            monthlyAiQuotaOverride != null,
            monthlyAiQuotaOverride,
            entitlements,
            activeUsersCount != null ? activeUsersCount : 0,
            activeBranchesCount != null ? activeBranchesCount : 0,
            monthlyAiRunsCount != null ? monthlyAiRunsCount : 0
        );
    }

    /**
     * Sets or updates per-clinic numerical limit overrides.
     */
    @Transactional
    public ClinicLimitsDto updateClinicLimits(PlatformOperatorContext operator, UUID projectId,
                                            ClinicLimitsOverrideCommand cmd, String correlationId) {
        Objects.requireNonNull(operator, "operator");
        if (cmd.reason() == null || cmd.reason().isBlank()) {
            throw new IllegalArgumentException("Reason is required to update clinic limits.");
        }

        UUID orgId = jdbc.queryForObject(
            "select \"organizationId\" from \"public\".\"projects\" where \"id\" = :pid",
            new MapSqlParameterSource("pid", projectId),
            UUID.class);

        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
            update "public"."clinic_subscriptions"
               set "maxUsersOverride" = :maxUsers,
                   "maxBranchesOverride" = :maxBranches,
                   "storageQuotaMbOverride" = :storageQuotaMb,
                   "monthlyAiQuotaOverride" = :monthlyAiQuota,
                   "version" = "version" + 1,
                   "updatedAt" = :now
             where "projectId" = :projectId
            """,
            new MapSqlParameterSource()
                .addValue("maxUsers", cmd.maxUsers())
                .addValue("maxBranches", cmd.maxBranches())
                .addValue("storageQuotaMb", cmd.storageQuotaMb())
                .addValue("monthlyAiQuota", cmd.monthlyAiQuota())
                .addValue("now", now)
                .addValue("projectId", projectId));

        audit.record(
            "CLINIC_LIMITS_OVERRIDDEN",
            operator.operatorId(),
            operator.sessionId(),
            orgId,
            projectId,
            "ClinicSubscription",
            projectId.toString(),
            cmd.reason(),
            String.format("Limits overridden: users=%s, branches=%s, storage=%s, ai=%s",
                    cmd.maxUsers(), cmd.maxBranches(), cmd.storageQuotaMb(), cmd.monthlyAiQuota()),
            "ALLOWED",
            correlationId,
            null
        );

        return getClinicLimits(projectId);
    }

    /**
     * Sets or updates a per-clinic feature entitlement override.
     */
    @Transactional
    public void updateClinicEntitlementOverride(PlatformOperatorContext operator, UUID projectId,
                                                String featureKey, boolean enabled, String reason, String correlationId) {
        Objects.requireNonNull(operator, "operator");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Reason is required to override feature entitlement.");
        }

        UUID orgId = jdbc.queryForObject(
            "select \"organizationId\" from \"public\".\"projects\" where \"id\" = :pid",
            new MapSqlParameterSource("pid", projectId),
            UUID.class);

        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
            insert into "public"."clinic_entitlement_overrides"
                ("id", "projectId", "featureKey", "enabled", "createdAt", "updatedAt")
            values
                (gen_random_uuid(), :projectId, :featureKey, :enabled, :now, :now)
            on conflict ("projectId", "featureKey") do update
                set "enabled" = :enabled,
                    "updatedAt" = :now
            """,
            new MapSqlParameterSource()
                .addValue("projectId", projectId)
                .addValue("featureKey", featureKey)
                .addValue("enabled", enabled)
                .addValue("now", now));

        audit.record(
            "CLINIC_ENTITLEMENT_OVERRIDDEN",
            operator.operatorId(),
            operator.sessionId(),
            orgId,
            projectId,
            "ClinicSubscription",
            projectId.toString(),
            reason,
            String.format("Feature '%s' override set to %s", featureKey, enabled),
            "ALLOWED",
            correlationId,
            null
        );
    }
}
