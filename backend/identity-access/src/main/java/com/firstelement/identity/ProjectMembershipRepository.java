package com.firstelement.identity;

import com.firstelement.security.TenantContext;
import com.firstelement.security.UserRole;
import com.firstelement.security.query.ScopedWhere;
import com.firstelement.security.query.TenantQueryGuard;
import com.firstelement.security.query.TenantScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The mapping of {@code organization} + {@code project_memberships} into
 * {@code TenantContext.projectIds}.
 *
 * <h2>The two rules, ported from the legacy runtime unchanged</h2>
 *
 * <ol>
 *   <li><b>An {@code EMPLOYEE}'s project list comes from
 *       {@code project_memberships}.</b> It is recomputed on every session resolve, so a
 *       revoked membership takes effect on the next request rather than whenever a stale
 *       cookie happens to expire. The legacy {@code session.service.ts} documented this
 *       explicitly and the behaviour is preserved.</li>
 *   <li><b>An {@code ADMIN}'s project list is every project of their own organization —
 *       including archived ones.</b> The legacy comment is the reason: otherwise an
 *       archive becomes a one-way door with no way to read it or undo it.</li>
 * </ol>
 *
 * <p>Both queries are organization-led, matching the 64 tenant-led indexes in the
 * verified schema, so neither can accidentally walk another tenant's rows.
 */
@Repository
public class ProjectMembershipRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ProjectMembershipRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The projects a user may reach, server-side.
     *
     * @param organizationId the user's own organization, already validated by the caller
     * @param userId         who is being resolved
     * @param role           decides the ADMIN-bypass branch
     */
    public List<UUID> resolveProjectIds(UUID organizationId, UUID userId, UserRole role) {
        if (role == UserRole.ADMIN) {
            return allProjectsInOrganization(organizationId);
        }
        return membershipsOf(userId, organizationId);
    }

    /** Every project of one organization, archived included. ADMIN scope only. */
    public List<UUID> allProjectsInOrganization(UUID organizationId) {
        return jdbc.queryForList(
            "select \"id\" from projects where \"organizationId\" = :org order by \"slug\"",
            new MapSqlParameterSource("org", organizationId), UUID.class);
    }

    /** A user's explicit {@code project_memberships} rows, scoped to one organization. */
    public List<UUID> membershipsOf(UUID userId, UUID organizationId) {
        return jdbc.queryForList("""
            select m."projectId"
              from project_memberships m
              join projects p on p."id" = m."projectId"
             where m."organizationId" = :org
               and m."userId" = :user
               and p."organizationId" = :org
             order by p."slug"
            """,
            new MapSqlParameterSource().addValue("org", organizationId).addValue("user", userId),
            UUID.class);
    }

    /**
     * Whether a project belongs to the organization. Used to prove that a project id in a
     * request is <em>not</em> being silently reinterpreted.
     */
    public Optional<UUID> findProjectInOrganization(UUID organizationId, UUID projectId) {
        List<UUID> found = jdbc.queryForList(
            "select \"id\" from projects where \"organizationId\" = :org and \"id\" = :id",
            new MapSqlParameterSource().addValue("org", organizationId).addValue("id", projectId),
            UUID.class);
        return found.stream().findFirst();
    }

    /**
     * Whether a project is archived.
     *
     * <p>Surfaced by the permission inspector because an {@code ADMIN} keeps access to an
     * archived project on purpose: otherwise archiving would be a one-way door with no way
     * to read the project or undo the archive. An operator seeing "archived: true" in the
     * inspector is the reason that rule is auditable rather than surprising.
     */
    public Optional<Boolean> isArchived(UUID organizationId, UUID projectId) {
        List<Boolean> found = jdbc.queryForList(
            "select \"isArchived\" from projects where \"organizationId\" = :org and \"id\" = :id",
            new MapSqlParameterSource().addValue("org", organizationId).addValue("id", projectId),
            Boolean.class);
        return found.stream().findFirst();
    }

    // ------------------------------------------------------------------
    // membership administration
    // ------------------------------------------------------------------

    /**
     * Grants a project membership.
     *
     * <p>Written as an {@code INSERT ... SELECT FROM projects} so the row can only be
     * created from a project that really belongs to the caller's organization — a project id
     * from another tenant inserts nothing rather than inserting a cross-tenant membership.
     * {@link TenantQueryGuard} has already validated the caller's scope by the time this
     * runs.
     *
     * @return rows inserted (0 when {@code (projectId, userId)} already held the
     *         membership, which is a successful no-op rather than an error)
     */
    public int addMembership(TenantQueryGuard guard,
                             TenantContext context,
                             UUID userId,
                             UUID projectId) {
        TenantScope scope = guard.tenantScope(context);
        return jdbc.update("""
            insert into project_memberships
                ("id", "organizationId", "projectId", "userId", "createdAt")
            select :id, p."organizationId", p."id", :user, :at
              from projects p
             where p."organizationId" = :org and p."id" = :project
            on conflict do nothing
            """,
            new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("org", scope.organizationId())
                .addValue("user", userId)
                .addValue("project", projectId)
                .addValue("at", java.time.LocalDateTime.now()));
    }

    /**
     * Revokes a project membership.
     *
     * @return rows removed
     */
    public int removeMembership(TenantQueryGuard guard,
                                TenantContext context,
                                UUID userId,
                                UUID projectId) {
        ScopedWhere.NamedSql query = ScopedWhere.of(guard.tenantScope(context))
            .delete("delete from project_memberships m", "m",
                "m.\"userId\" = :user and m.\"projectId\" = :project",
                Map.of("user", userId, "project", projectId));
        return jdbc.update(query.sql(), query.parameters());
    }

    /** Membership rows for the Admin inspector, scoped to the caller's organization. */
    public List<MembershipView> listMemberships(TenantQueryGuard guard, TenantContext context) {
        ScopedWhere.NamedSql query = ScopedWhere.of(guard.tenantScope(context))
            .select("""
                select m."userId", m."projectId", p."slug" as "projectSlug",
                       p."isArchived", m."createdAt"
                  from project_memberships m
                  join projects p on p."id" = m."projectId" and p."organizationId" = m."organizationId"
                """,
                "m");
        return jdbc.query(query.sql(), query.parameters(), (rs, rowNum) -> new MembershipView(
            rs.getObject("userId", UUID.class),
            rs.getObject("projectId", UUID.class),
            rs.getString("projectSlug"),
            rs.getBoolean("isArchived"),
            rs.getObject("createdAt", java.time.LocalDateTime.class)));
    }

    /** One membership row, for the inspector. */
    public record MembershipView(UUID userId, UUID projectId, String projectSlug,
                                 boolean projectArchived, java.time.LocalDateTime createdAt) {
    }

    /** Convenience for callers that only need ids. */
    public static List<UUID> ids(List<MembershipView> views) {
        List<UUID> ids = new ArrayList<>();
        views.forEach(view -> ids.add(view.projectId()));
        return ids;
    }
}