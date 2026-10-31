package com.firstelement.admin.identity;

import com.firstelement.security.Capability;
import com.firstelement.security.PrincipalType;
import com.firstelement.security.permission.RolePermissions;
import com.firstelement.security.query.ScopedWhere;
import com.firstelement.security.query.TenantScope;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The Admin identity directory: users, memberships, live sessions, and the machine principals the
 * boundary actually recognises.
 *
 * <h2>What is not in this file, and why that is the design</h2>
 *
 * <p>FR-6 asks the dashboard for "users, active/inactive state, roles, project memberships,
 * sessions, API keys, service principals, and effective permissions". Two of those have no table in
 * the promoted schema: there is no {@code api_keys} table and no {@code service_principals} table,
 * and Phase 7 may not write DDL. Rather than invent them or silently omit them, this repository
 * answers each question from what exists and says which answer it is giving:
 *
 * <ul>
 *   <li><b>Service principals</b> are the {@link PrincipalType} × {@link Capability} matrix the
 *       security boundary enforces at runtime, plus the machine principals this tenant has actually
 *       been seen using — read from the actor columns of the audit streams, which are the only place
 *       a machine principal leaves a durable trace. Both are tenant-scoped: the matrix is global
 *       vocabulary, the observed principals are this tenant's own rows.</li>
 *   <li><b>API keys</b> have no representation at all, and the honest answer is therefore
 *       "this schema has no API-key table"; what does exist is credential <em>presence</em> on
 *       {@code channel_connections}, computed by the database as a boolean and reported as such. The
 *       gap is reported to the orchestrator rather than papered over.</li>
 * </ul>
 *
 * <h2>No projection here selects a secret</h2>
 *
 * <p>{@code users."passwordHash"} and {@code sessions."tokenHash"} are in the schema and are in
 * <em>no</em> statement below. Not "redacted afterwards" — absent from the SELECT list, so there is
 * no value in this process to leak, log by accident, or serialise into an Admin response. The
 * session read model proves revocation state (which is what an operator needs) without the token.
 */
@Repository
public class IdentityDirectoryRepository {

    private static final String USERS = """
        select u."id", u."organizationId", u."email", u."fullName", u."role"::text as "role",
               u."locale"::text as "locale", u."isActive", u."lastLoginAt", u."createdAt",
               (select count(*) from sessions s
                 where s."organizationId" = u."organizationId" and s."userId" = u."id"
                   and s."revokedAt" is null
                   and s."expiresAt" > (now() AT TIME ZONE 'UTC')) as "liveSessions",
               (select count(*) from project_memberships m
                 where m."organizationId" = u."organizationId" and m."userId" = u."id")
                   as "membershipCount"
          from users u
         where u."organizationId" = :__fe_tenant_organization_id
         order by u."createdAt", u."id"
         limit :limit
        """;

    private static final String MEMBERSHIPS = """
        select m."id", m."organizationId", m."projectId", m."userId", m."createdAt",
               p."name" as "projectName", p."isArchived" as "projectArchived"
          from project_memberships m
          join projects p on p."id" = m."projectId" and p."organizationId" = m."organizationId"
         where m."organizationId" = :__fe_tenant_organization_id
         order by m."userId", p."name"
         limit :limit
        """;

    /**
     * Live and revoked sessions, with the token column absent.
     *
     * <p>{@code "tokenHash"} is deliberately not selected: ADR-0001 stores only the SHA-256 and
     * calls it the property that makes revocation immediate, which is exactly why an operator must
     * never be able to read it — a hash of a live cookie token is a bearer credential with a longer
     * life than the token it protects.
     */
    private static final String SESSIONS = """
        select s."id", s."organizationId", s."userId", s."userAgent", s."ipAddress", s."expiresAt",
               s."revokedAt", s."createdAt", s."lastSeenAt", u."email" as "userEmail"
          from sessions s
          join users u on u."id" = s."userId" and u."organizationId" = s."organizationId"
         where s."organizationId" = :__fe_tenant_organization_id
           and (cast(:filterUserId as uuid) is null or s."userId" = cast(:filterUserId as uuid))
         order by s."lastSeenAt" desc, s."id" desc
         limit :limit
        """;

    /** Machine principals this tenant has actually used, read from the audit streams. */
    private static final String OBSERVED_MACHINE_PRINCIPALS = """
        select principal."principalType" as principal_type,
               principal."principalName" as principal_name,
               principal."lastSeenAt" as last_seen,
               principal."occurrences" as occurrences
          from (
                select "principalType"::text as "principalType", "principalName" as "principalName",
                       max("occurredAt") as "lastSeenAt", count(*) as occurrences
                  from admin_audit_events
                 where "organizationId" = :__fe_tenant_organization_id
                   and "principalType" <> cast('USER' as "AuditPrincipalType")
                 group by "principalType", "principalName"
                union all
                select "componentType"::text, "component", max("occurredAt"), count(*)
                  from job_integration_events
                 where "organizationId" = :__fe_tenant_organization_id
                   and "componentType" <> cast('USER' as "AuditPrincipalType")
                 group by "componentType", "component"
               ) principal
         order by principal."lastSeenAt" desc, principal."principalName"
         limit :limit
        """;

    /** Credential presence per connector. Booleans computed by the database; never the value. */
    private static final String CREDENTIAL_PRESENCE = """
        select cc."id", cc."projectId", cc."channel"::text as "channel", cc."displayName",
               cc."isEnabled", cc."status"::text as "status",
               (cc."credentials" is not null and cc."credentials" <> '') as "hasCredentials",
               (cc."webhookSecret" is not null and cc."webhookSecret" <> '') as "hasWebhookSecret"
          from channel_connections cc
         where cc."organizationId" = :__fe_tenant_organization_id
         order by cc."createdAt", cc."id"
         limit :limit
        """;

    /** Directory tallies for the overview. */
    private static final String SUMMARY = """
        select (select count(*) from users u
                 where u."organizationId" = :__fe_tenant_organization_id) as total_users,
               (select count(*) from users u
                 where u."organizationId" = :__fe_tenant_organization_id
                   and not u."isActive") as inactive_users,
               (select count(*) from users u
                 where u."organizationId" = :__fe_tenant_organization_id
                   and u."role" = cast('ADMIN' as "UserRole")) as administrators,
               (select count(*) from sessions s
                 where s."organizationId" = :__fe_tenant_organization_id
                   and s."revokedAt" is null
                   and s."expiresAt" > (now() AT TIME ZONE 'UTC')) as live_sessions,
               (select count(*) from project_memberships m
                 where m."organizationId" = :__fe_tenant_organization_id) as memberships,
               (select count(*) from projects p
                 where p."organizationId" = :__fe_tenant_organization_id
                   and p."isArchived") as archived_projects,
               (select count(*) from projects p
                 where p."organizationId" = :__fe_tenant_organization_id) as projects
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public IdentityDirectoryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One user.
     *
     * @param liveSessions    how many of this user's sessions are usable right now
     * @param membershipCount how many projects this user can reach
     */
    public record UserRow(UUID id,
                          UUID organizationId,
                          String email,
                          String fullName,
                          String role,
                          String locale,
                          boolean active,
                          LocalDateTime lastLoginAt,
                          LocalDateTime createdAt,
                          long liveSessions,
                          long membershipCount) {
    }

    /** One membership, with the project's archive state so an admin can see why reach persists. */
    public record MembershipRow(UUID id,
                                UUID organizationId,
                                UUID projectId,
                                UUID userId,
                                LocalDateTime createdAt,
                                String projectName,
                                boolean projectArchived) {
    }

    /**
     * One session.
     *
     * <p>No token, no hash. {@code userAgent} and {@code ipAddress} are the two fields an operator
     * actually uses to answer "is this my session", and both are already recorded on the login
     * security event.
     */
    public record SessionRow(UUID id,
                             UUID organizationId,
                             UUID userId,
                             String userEmail,
                             String userAgent,
                             String ipAddress,
                             LocalDateTime expiresAt,
                             LocalDateTime revokedAt,
                             LocalDateTime createdAt,
                             LocalDateTime lastSeenAt) {

        /** Whether the session could still authenticate right now. */
        public boolean live(LocalDateTime utcNow) {
            return revokedAt == null && expiresAt != null && expiresAt.isAfter(utcNow);
        }
    }

    /** One machine principal this tenant has used. */
    public record ObservedPrincipal(String principalType,
                                    String principalName,
                                    LocalDateTime lastSeenAt,
                                    long occurrences) {
    }

    /** One credential's presence. Never its value. */
    public record CredentialPresence(UUID connectionId,
                                     UUID projectId,
                                     String channel,
                                     String displayName,
                                     boolean enabled,
                                     String status,
                                     boolean hasCredentials,
                                     boolean hasWebhookSecret) {
    }

    /** The directory tallies. Every figure is present even at zero. */
    public record DirectorySummary(long totalUsers,
                                   long inactiveUsers,
                                   long administrators,
                                   long liveSessions,
                                   long memberships,
                                   long archivedProjects,
                                   long projects) {
    }

    /** The tenant's users. */
    public List<UserRow> users(TenantScope scope, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("limit", Math.max(1, limit)));
        return jdbc.query(USERS, parameters, (rs, rowNum) -> new UserRow(
            rs.getObject("id", UUID.class),
            rs.getObject("organizationId", UUID.class),
            rs.getString("email"),
            rs.getString("fullName"),
            rs.getString("role"),
            rs.getString("locale"),
            rs.getBoolean("isActive"),
            rs.getObject("lastLoginAt", LocalDateTime.class),
            rs.getObject("createdAt", LocalDateTime.class),
            rs.getLong("liveSessions"),
            rs.getLong("membershipCount")));
    }

    /** The tenant's memberships. */
    public List<MembershipRow> memberships(TenantScope scope, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("limit", Math.max(1, limit)));
        return jdbc.query(MEMBERSHIPS, parameters, (rs, rowNum) -> new MembershipRow(
            rs.getObject("id", UUID.class),
            rs.getObject("organizationId", UUID.class),
            rs.getObject("projectId", UUID.class),
            rs.getObject("userId", UUID.class),
            rs.getObject("createdAt", LocalDateTime.class),
            rs.getString("projectName"),
            rs.getBoolean("projectArchived")));
    }

    /** Sessions, optionally narrowed to one user. */
    public List<SessionRow> sessions(TenantScope scope, UUID userId, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("limit", Math.max(1, limit)));
        parameters.addValue("filterUserId", userId);
        return jdbc.query(SESSIONS, parameters, (rs, rowNum) -> new SessionRow(
            rs.getObject("id", UUID.class),
            rs.getObject("organizationId", UUID.class),
            rs.getObject("userId", UUID.class),
            rs.getString("userEmail"),
            rs.getString("userAgent"),
            rs.getString("ipAddress"),
            rs.getObject("expiresAt", LocalDateTime.class),
            rs.getObject("revokedAt", LocalDateTime.class),
            rs.getObject("createdAt", LocalDateTime.class),
            rs.getObject("lastSeenAt", LocalDateTime.class)));
    }

    /** The machine principals this tenant has actually used. */
    public List<ObservedPrincipal> observedMachinePrincipals(TenantScope scope, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("limit", Math.max(1, limit)));
        return jdbc.query(OBSERVED_MACHINE_PRINCIPALS, parameters, (rs, rowNum) ->
            new ObservedPrincipal(
                rs.getString("principal_type"),
                rs.getString("principal_name"),
                rs.getObject("last_seen", LocalDateTime.class),
                rs.getLong("occurrences")));
    }

    /** Credential presence per connector. */
    public List<CredentialPresence> credentialPresence(TenantScope scope, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("limit", Math.max(1, limit)));
        return jdbc.query(CREDENTIAL_PRESENCE, parameters, (rs, rowNum) -> new CredentialPresence(
            rs.getObject("id", UUID.class),
            rs.getObject("projectId", UUID.class),
            rs.getString("channel"),
            rs.getString("displayName"),
            rs.getBoolean("isEnabled"),
            rs.getString("status"),
            rs.getBoolean("hasCredentials"),
            rs.getBoolean("hasWebhookSecret")));
    }

    /** The directory tallies. */
    public DirectorySummary summary(TenantScope scope) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of());
        return jdbc.queryForObject(SUMMARY, parameters, (rs, rowNum) -> new DirectorySummary(
            rs.getLong("total_users"),
            rs.getLong("inactive_users"),
            rs.getLong("administrators"),
            rs.getLong("live_sessions"),
            rs.getLong("memberships"),
            rs.getLong("archived_projects"),
            rs.getLong("projects")));
    }

    /**
     * The service-principal <em>vocabulary</em>: which principal types exist, which capability each
     * may hold, and what each capability grants.
     *
     * <p>Read from {@link RolePermissions} rather than from the database because the grant table is
     * code, and a read model that reported a different answer from the enforcing table would be the
     * worst possible artefact: an operator would be reviewing a permission set that does not exist.
     * Everything an operator can do about it — see who holds what, revoke a session, remove a
     * membership — is elsewhere in this module.
     */
    public static List<ServicePrincipalRow> servicePrincipalVocabulary() {
        List<ServicePrincipalRow> rows = new ArrayList<>();
        for (PrincipalType type : PrincipalType.values()) {
            for (Capability capability : Capability.values()) {
                if (!capability.mayBeHeldBy(type)) {
                    continue;
                }
                Set<com.firstelement.security.permission.Permission> granted =
                    RolePermissions.forCapabilities(Set.of(capability));
                List<String> permissions = new ArrayList<>();
                granted.stream().map(com.firstelement.security.permission.Permission::value)
                    .sorted().forEach(permissions::add);
                rows.add(new ServicePrincipalRow(type.value(), capability.name(),
                    capability.isAdministrative(), List.copyOf(permissions)));
            }
        }
        return List.copyOf(rows);
    }

    /**
     * One capability a principal type may hold.
     *
     * @param administrative whether this is an administrative capability, which plan.md forbids a
     *                       human role from holding and which an agent may never hold
     * @param permissions    sorted wire values the capability grants
     */
    public record ServicePrincipalRow(String principalType,
                                      String capability,
                                      boolean administrative,
                                      List<String> permissions) {
    }

    /**
     * The stable, deterministic capability index for tests and documentation.
     *
     * @return capability name to principal types that may hold it
     */
    public static Map<String, List<String>> capabilityMatrix() {
        Map<String, List<String>> matrix = new TreeMap<>();
        for (Capability capability : Capability.values()) {
            List<String> holders = new ArrayList<>();
            for (PrincipalType type : PrincipalType.values()) {
                if (capability.mayBeHeldBy(type)) {
                    holders.add(type.value());
                }
            }
            matrix.put(capability.name(), List.copyOf(holders));
        }
        return new LinkedHashMap<>(matrix);
    }
}