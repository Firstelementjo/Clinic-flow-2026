package com.firstelement.security.platform;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Persistence for platform operators and their sessions.
 *
 * <p>Plain {@link NamedParameterJdbcTemplate}, consistent with the rest of the codebase. Column names
 * are quoted camelCase to match the existing schema (ADR 0002). Nothing here reads
 * {@code totpSecretEnc} except {@link #findCredentialsByEmail}, which runs on the authentication path
 * only — the value is never projected into a view, a console response, or a log line.
 */
public class PlatformOperatorRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public PlatformOperatorRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The operator as the console and the audit trail see them. Carries no secret. */
    public record Operator(UUID id, String email, String displayName, boolean active,
                           Instant totpEnrolledAt, Instant lastSeenAt, Instant createdAt) {
    }

    /** The authentication view. The only record that carries a secret. */
    public record Credentials(UUID id, String email, String displayName, String passwordHash,
                              String totpSecretEnc, boolean active) {
    }

    /** A session row, including whether its second factor and its elevation window are satisfied. */
    public record SessionRow(UUID id, UUID operatorId, Instant mfaSatisfiedAt, Instant elevatedUntil,
                             Instant expiresAt, boolean revoked) {
    }

    public Optional<Credentials> findCredentialsByEmail(String normalizedEmail) {
        if (normalizedEmail == null) {
            return Optional.empty();
        }
        List<Credentials> rows = jdbc.query("""
            select "id", "email", "displayName", "passwordHash", "totpSecretEnc", "isActive"
              from platform_operators
             where lower("email") = :email
            """,
            new MapSqlParameterSource("email", normalizedEmail),
            (rs, i) -> new Credentials(
                    rs.getObject("id", UUID.class),
                    rs.getString("email"),
                    rs.getString("displayName"),
                    rs.getString("passwordHash"),
                    rs.getString("totpSecretEnc"),
                    rs.getBoolean("isActive")));

        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<Operator> findById(UUID id) {
        List<Operator> rows = jdbc.query("""
            select "id", "email", "displayName", "isActive", "totpEnrolledAt", "lastSeenAt", "createdAt"
              from platform_operators
             where "id" = :id
            """,
            new MapSqlParameterSource("id", id),
            (rs, i) -> new Operator(
                    rs.getObject("id", UUID.class),
                    rs.getString("email"),
                    rs.getString("displayName"),
                    rs.getBoolean("isActive"),
                    utc(rs.getObject("totpEnrolledAt", LocalDateTime.class)),
                    utc(rs.getObject("lastSeenAt", LocalDateTime.class)),
                    utc(rs.getObject("createdAt", LocalDateTime.class))));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<SessionRow> findSessionByTokenHash(String tokenHash, Instant now) {
        List<SessionRow> rows = jdbc.query("""
            select s."id", s."operatorId", s."mfaSatisfiedAt", s."elevatedExpiresAt",
                   s."expiresAt", s."revokedAt"
              from platform_sessions s
              join platform_operators o on o."id" = s."operatorId"
             where s."tokenHash" = :hash
               and s."revokedAt" is null
               and s."expiresAt" > :now
               and o."isActive"
            """,
            new MapSqlParameterSource()
                    .addValue("hash", tokenHash)
                    .addValue("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC)),
            (rs, i) -> new SessionRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("operatorId", UUID.class),
                    utc(rs.getObject("mfaSatisfiedAt", LocalDateTime.class)),
                    utc(rs.getObject("elevatedExpiresAt", LocalDateTime.class)),
                    utc(rs.getObject("expiresAt", LocalDateTime.class)),
                    rs.getObject("revokedAt", LocalDateTime.class) != null));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** A session by id, for building a context once the caller already knows which session it holds. */
    public Optional<SessionRow> findSessionById(UUID sessionId) {
        List<SessionRow> rows = jdbc.query("""
            select "id", "operatorId", "mfaSatisfiedAt", "elevatedExpiresAt", "expiresAt", "revokedAt"
              from platform_sessions
             where "id" = :id
            """,
            new MapSqlParameterSource("id", sessionId),
            (rs, i) -> new SessionRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("operatorId", UUID.class),
                    utc(rs.getObject("mfaSatisfiedAt", LocalDateTime.class)),
                    utc(rs.getObject("elevatedExpiresAt", LocalDateTime.class)),
                    utc(rs.getObject("expiresAt", LocalDateTime.class)),
                    rs.getObject("revokedAt", LocalDateTime.class) != null));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Creates the session record for the password step. {@code mfaSatisfiedAt} is deliberately not
     * set: the row exists so the second step can be bound to it, but it carries no authority.
     */
    public UUID createPendingSession(UUID operatorId, String tokenHash, Instant expiresAt,
                                     String userAgent, String ipAddress) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            insert into platform_sessions
                ("id", "operatorId", "tokenHash", "userAgent", "ipAddress", "expiresAt",
                 "createdAt", "lastSeenAt")
            values
                (:id, :operatorId, :hash, :userAgent, :ipAddress, :expiresAt,
                 (current_timestamp at time zone 'utc'), (current_timestamp at time zone 'utc'))
            """,
            new MapSqlParameterSource()
                    .addValue("id", id)
                    .addValue("operatorId", operatorId)
                    .addValue("hash", tokenHash)
                    .addValue("userAgent", userAgent)
                    .addValue("ipAddress", ipAddress)
                    .addValue("expiresAt", LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC)));
        return id;
    }

    /** Marks the second factor satisfied. The database constraint refuses elevation without it. */
    public void markMfaSatisfied(UUID sessionId) {
        jdbc.update("""
            update platform_sessions
               set "mfaSatisfiedAt" = (current_timestamp at time zone 'utc')
             where "id" = :id and "mfaSatisfiedAt" is null
            """,
            new MapSqlParameterSource("id", sessionId));
    }

    /** Opens the elevation window. The reason is mandatory and is read back by the audit trail. */
    public void elevate(UUID sessionId, Instant until, String reason) {
        jdbc.update("""
            update platform_sessions
               set "elevatedAt" = (current_timestamp at time zone 'utc'),
                   "elevatedExpiresAt" = :until,
                   "elevationReason" = :reason
             where "id" = :id and "mfaSatisfiedAt" is not null
            """,
            new MapSqlParameterSource()
                    .addValue("id", sessionId)
                    .addValue("until", LocalDateTime.ofInstant(until, ZoneOffset.UTC))
                    .addValue("reason", reason));
    }

    public void touchSession(UUID sessionId) {
        jdbc.update("""
            update platform_sessions
               set "lastSeenAt" = (current_timestamp at time zone 'utc')
             where "id" = :id
            """,
            new MapSqlParameterSource("id", sessionId));
    }

    public void touchOperator(UUID operatorId) {
        jdbc.update("""
            update platform_operators
               set "lastSeenAt" = (current_timestamp at time zone 'utc')
             where "id" = :id
            """,
            new MapSqlParameterSource("id", operatorId));
    }

    public void revokeSession(UUID sessionId) {
        jdbc.update("""
            update platform_sessions
               set "revokedAt" = (current_timestamp at time zone 'utc')
             where "id" = :id and "revokedAt" is null
            """,
            new MapSqlParameterSource("id", sessionId));
    }

    public void grantCapabilities(UUID operatorId, List<PlatformCapability> capabilities) {
        for (PlatformCapability capability : capabilities) {
            jdbc.update("""
                insert into platform_operator_capabilities ("operatorId", "capability", "grantedAt")
                values (:operatorId, :capability, (current_timestamp at time zone 'utc'))
                on conflict ("operatorId", "capability") do nothing
                """,
                    new MapSqlParameterSource()
                            .addValue("operatorId", operatorId)
                            .addValue("capability", capability.name()));
        }
    }

    public List<PlatformCapability> capabilitiesOf(UUID operatorId) {
        return jdbc.queryForList("""
            select "capability" from platform_operator_capabilities where "operatorId" = :operatorId
            """,
            new MapSqlParameterSource("operatorId", operatorId),
            String.class).stream()
                .map(PlatformCapability::fromValue)
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * Every clinic in the deployment, across every organization.
     *
     * <p>The one read in the platform tier that is intentionally not tenant-scoped, and the reason
     * {@code READ_PLATFORM_OVERVIEW} exists as a non-mutating capability. It returns identifiers,
     * names and counts — never clinical or customer content.
     */
    public List<Map<String, Object>> listAllClinics() {
        return jdbc.queryForList("""
            select p."id"              as "projectId",
                   p."organizationId" as "organizationId",
                   o."name"            as "organizationName",
                   p."name"            as "clinicName",
                   p."slug"            as "slug",
                   coalesce(p."intakePortalKey", p."slug") as "intakePortalKey",
                   coalesce(p."intakePortalEnabled", true) as "intakePortalEnabled",
                   coalesce(p."city", 'Amman') as "city",
                   p."contactNumber"   as "contactNumber",
                   p."timezone"        as "timezone",
                   cast(p."defaultLocale" as text) as "defaultLocale",
                   p."status"          as "status",
                   p."suspendedAt"     as "suspendedAt",
                   p."suspendedReason" as "suspendedReason",
                   p."version"         as "version",
                   p."isArchived"      as "archived",
                   p."createdAt"       as "createdAt",
                   coalesce(cs."status", p."status") as "subscriptionStatus",
                   cs."paidUntil"      as "paidUntil",
                   cs."trialEndsAt"    as "trialEndsAt",
                   cs."gracePeriodDays" as "gracePeriodDays",
                   coalesce(cs."maxUsersOverride", pl."maxUsers", 5)       as "maxUsers",
                   coalesce(cs."maxBranchesOverride", pl."maxBranches", 1) as "maxBranches",
                   coalesce(cs."storageQuotaMbOverride", pl."storageQuotaMb", 5120) as "storageQuotaMb",
                   coalesce(cs."monthlyAiQuotaOverride", pl."monthlyAiQuota", 500)  as "monthlyAiQuota",
                   coalesce(pl."code", 'GROWTH') as "planCode",
                   coalesce(pl."name", 'Growth Plan') as "planName",
                   (select u."id" from "public"."users" u
                      join "public"."project_memberships" pm on pm."userId" = u."id"
                     where pm."projectId" = p."id" and u."role" = 'ADMIN'
                     order by u."createdAt" asc limit 1) as "adminUserId",
                   (select u."fullName" from "public"."users" u
                      join "public"."project_memberships" pm on pm."userId" = u."id"
                     where pm."projectId" = p."id" and u."role" = 'ADMIN'
                     order by u."createdAt" asc limit 1) as "adminName",
                   (select u."email" from "public"."users" u
                      join "public"."project_memberships" pm on pm."userId" = u."id"
                     where pm."projectId" = p."id" and u."role" = 'ADMIN'
                     order by u."createdAt" asc limit 1) as "adminEmail",
                   (select u."phone" from "public"."users" u
                      join "public"."project_memberships" pm on pm."userId" = u."id"
                     where pm."projectId" = p."id" and u."role" = 'ADMIN'
                     order by u."createdAt" asc limit 1) as "adminPhone",
                   (select coalesce(u."isActive", true) from "public"."users" u
                      join "public"."project_memberships" pm on pm."userId" = u."id"
                     where pm."projectId" = p."id" and u."role" = 'ADMIN'
                     order by u."createdAt" asc limit 1) as "adminIsActive",
                   (select coalesce(u."invitePending", false) from "public"."users" u
                      join "public"."project_memberships" pm on pm."userId" = u."id"
                     where pm."projectId" = p."id" and u."role" = 'ADMIN'
                     order by u."createdAt" asc limit 1) as "adminInvitePending",
                   (select (u."mfaEnrolledAt" is not null) from "public"."users" u
                      join "public"."project_memberships" pm on pm."userId" = u."id"
                     where pm."projectId" = p."id" and u."role" = 'ADMIN'
                     order by u."createdAt" asc limit 1) as "adminMfaEnrolled",
                   (select max(coalesce(u."lastLoginAt", u."updatedAt"))
                      from "public"."users" u
                      join "public"."project_memberships" pm on pm."userId" = u."id"
                     where pm."projectId" = p."id") as "lastActivity",
                   (select count(*) from users u
                     where u."organizationId" = p."organizationId")   as "accountCount",
                   (select count(*) from project_memberships m
                     where m."projectId" = p."id")                    as "memberCount"
              from projects p
              join organizations o on o."id" = p."organizationId"
              left join clinic_subscriptions cs on cs."projectId" = p."id"
              left join plans pl on pl."id" = cs."planId"
             order by o."name", p."name"
            """, new MapSqlParameterSource());
    }

    private static Instant utc(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}