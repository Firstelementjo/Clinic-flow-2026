package com.firstelement.crm.clinic;

import com.firstelement.crm.error.FeatureNotEntitledException;
import com.firstelement.crm.error.PlanLimitExceededException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Server-side Enforcement of Subscription Plans, Quotas, and Feature Entitlements (Slice 4).
 *
 * <p>Specification compliance:
 * - Plan = max users, max branches, storage quota, monthly AI quota, feature entitlements.
 * - Per-clinic overrides: override takes precedence over plan default if non-null.
 * - Server-side enforcement (HTTP 403 / 429), not just hidden in the UI.
 * - Entitlements: CLINIC_AI, CHANNEL_WHATSAPP, CHANNEL_TELEGRAM, CHANNEL_INSTAGRAM.
 */
@Service
public class PlanLimitEnforcementService {

    private static final Logger log = LoggerFactory.getLogger(PlanLimitEnforcementService.class);

    private final NamedParameterJdbcTemplate jdbc;

    public PlanLimitEnforcementService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public record EffectiveLimits(
        UUID projectId,
        String planCode,
        String planName,
        int effectiveMaxUsers,
        boolean isMaxUsersOverridden,
        int effectiveMaxBranches,
        boolean isMaxBranchesOverridden,
        int effectiveStorageQuotaMb,
        boolean isStorageQuotaOverridden,
        int effectiveMonthlyAiQuota,
        boolean isMonthlyAiQuotaOverridden,
        Map<String, Boolean> entitlements,
        int activeUsersCount,
        int activeBranchesCount,
        int monthlyAiRunsCount
    ) {}

    /**
     * Enforces physical clinic location / branch creation limit.
     */
    @Transactional(readOnly = true)
    public void enforceBranchLimit(UUID projectId) {
        if (projectId == null) return;
        EffectiveLimits limits = getEffectiveLimits(projectId);
        if (limits == null) return;

        if (limits.activeBranchesCount() >= limits.effectiveMaxBranches()) {
            log.warn("Branch limit exceeded for clinic {}: active={}, max={}",
                    projectId, limits.activeBranchesCount(), limits.effectiveMaxBranches());
            throw new PlanLimitExceededException("Branches / Locations",
                    limits.effectiveMaxBranches(), limits.activeBranchesCount());
        }
    }

    /**
     * Enforces active user account / staff limit.
     */
    @Transactional(readOnly = true)
    public void enforceUserLimit(UUID projectId) {
        if (projectId == null) return;
        EffectiveLimits limits = getEffectiveLimits(projectId);
        if (limits == null) return;

        if (limits.activeUsersCount() >= limits.effectiveMaxUsers()) {
            log.warn("User account limit exceeded for clinic {}: active={}, max={}",
                    projectId, limits.activeUsersCount(), limits.effectiveMaxUsers());
            throw new PlanLimitExceededException("Staff Users",
                    limits.effectiveMaxUsers(), limits.activeUsersCount());
        }
    }

    /**
     * Enforces feature entitlement (e.g. CLINIC_AI, CHANNEL_WHATSAPP).
     */
    @Transactional(readOnly = true)
    public void enforceFeatureEntitlement(UUID projectId, String featureKey) {
        if (projectId == null || featureKey == null) return;
        EffectiveLimits limits = getEffectiveLimits(projectId);
        if (limits == null) return;

        Boolean entitled = limits.entitlements().get(featureKey);
        if (entitled == null || !entitled) {
            log.warn("Feature '{}' not entitled for clinic {}", featureKey, projectId);
            throw new FeatureNotEntitledException(featureKey);
        }
    }

    /**
     * Enforces monthly AI run generation quota.
     */
    @Transactional(readOnly = true)
    public void enforceAiQuota(UUID projectId) {
        if (projectId == null) return;
        // First verify CLINIC_AI feature entitlement
        enforceFeatureEntitlement(projectId, "CLINIC_AI");

        EffectiveLimits limits = getEffectiveLimits(projectId);
        if (limits == null) return;

        if (limits.monthlyAiRunsCount() >= limits.effectiveMonthlyAiQuota()) {
            log.warn("Monthly AI run quota exceeded for clinic {}: used={}, limit={}",
                    projectId, limits.monthlyAiRunsCount(), limits.effectiveMonthlyAiQuota());
            throw new PlanLimitExceededException("Monthly AI Assistant Runs",
                    limits.effectiveMonthlyAiQuota(), limits.monthlyAiRunsCount());
        }
    }

    /**
     * Resolves the effective limits, overrides, entitlements, and live usage for a clinic.
     */
    @Transactional(readOnly = true)
    public EffectiveLimits getEffectiveLimits(UUID projectId) {
        if (projectId == null) return null;

        List<Map<String, Object>> rows = jdbc.queryForList("""
            select cs."id" as "subscriptionId",
                   cs."projectId",
                   cs."maxUsersOverride",
                   cs."maxBranchesOverride",
                   cs."storageQuotaMbOverride",
                   cs."monthlyAiQuotaOverride",
                   p."id" as "planId",
                   p."code" as "planCode",
                   p."name" as "planName",
                   p."maxUsers" as "planMaxUsers",
                   p."maxBranches" as "planMaxBranches",
                   p."storageQuotaMb" as "planStorageQuotaMb",
                   p."monthlyAiQuota" as "planMonthlyAiQuota"
              from "public"."clinic_subscriptions" cs
              join "public"."plans" p on cs."planId" = p."id"
             where cs."projectId" = :projectId
            """,
            new MapSqlParameterSource("projectId", projectId));

        if (rows.isEmpty()) {
            return null;
        }

        Map<String, Object> r = rows.get(0);
        UUID planId = (UUID) r.get("planId");
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

        int effectiveMaxUsers = maxUsersOverride != null ? maxUsersOverride : planMaxUsers;
        int effectiveMaxBranches = maxBranchesOverride != null ? maxBranchesOverride : planMaxBranches;
        int effectiveStorage = storageQuotaMbOverride != null ? storageQuotaMbOverride : planStorage;
        int effectiveAiQuota = monthlyAiQuotaOverride != null ? monthlyAiQuotaOverride : planAiQuota;

        // Resolve Entitlements: start with plan defaults, then apply per-clinic overrides
        Map<String, Boolean> entitlements = new HashMap<>();

        // 1. Plan entitlements
        jdbc.query("""
            select "featureKey", "enabled"
              from "public"."plan_entitlements"
             where "planId" = :planId
            """,
            new MapSqlParameterSource("planId", planId),
            rs -> {
                entitlements.put(rs.getString("featureKey"), rs.getBoolean("enabled"));
            });

        // 2. Per-clinic entitlement overrides
        jdbc.query("""
            select "featureKey", "enabled"
              from "public"."clinic_entitlement_overrides"
             where "projectId" = :projectId
            """,
            new MapSqlParameterSource("projectId", projectId),
            rs -> {
                entitlements.put(rs.getString("featureKey"), rs.getBoolean("enabled"));
            });

        // Current usage counts
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

        return new EffectiveLimits(
            projectId,
            planCode,
            planName,
            effectiveMaxUsers,
            maxUsersOverride != null,
            effectiveMaxBranches,
            maxBranchesOverride != null,
            effectiveStorage,
            storageQuotaMbOverride != null,
            effectiveAiQuota,
            monthlyAiQuotaOverride != null,
            entitlements,
            activeUsersCount != null ? activeUsersCount : 0,
            activeBranchesCount != null ? activeBranchesCount : 0,
            monthlyAiRunsCount != null ? monthlyAiRunsCount : 0
        );
    }
}
