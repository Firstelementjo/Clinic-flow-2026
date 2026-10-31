package com.firstelement.identity;

import com.firstelement.security.TenantContext;
import com.firstelement.security.UserRole;
import com.firstelement.security.auth.PasswordVerifier;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The fixture shared by the DB-backed security tests.
 *
 * <h2>Two tenants, three projects, and a user with no membership</h2>
 *
 * <pre>
 *   Tenant A (alpha)          Tenant B (beta)
 *     project A1 "alpha-1"      project B1 "beta-1"     <- NEVER visible to A
 *     project A2 "alpha-2"
 *
 *   admin@a  : ADMIN of A   -> both A projects (archived included)
 *   staff@a  : EMPLOYEE of A -> only A1
 *   admin@b  : ADMIN of B    -> B1
 *   nobody@a : EMPLOYEE of A -> no project_memberships row at all
 * </pre>
 *
 * <p>{@code nobody@a} is the row that proves FR-1.4 ("a user with no membership must
 * receive no tenant data") and the cross-tenant cases are what prove FR-2.7.
 *
 * <h2>Mapping notes</h2>
 *
 * <ul>
 *   <li>Every identifier is the quoted camelCase column name, exactly as the verified
 *       baseline defines it.</li>
 *   <li>Every timestamp is a {@link LocalDateTime}, because all 98 of them are
 *       {@code timestamp without time zone}.</li>
 *   <li>Passwords are hashed with the production {@link PasswordVerifier} so the login
 *       path under test is the real bcrypt comparison, not a stub.</li>
 * </ul>
 */
@Component
public class SecurityFixture {

    /** The Phase-2 database contract requires pgvector; a plain postgres image is not a substitute. */
    public static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16"))
            .withDatabaseName("firstelement_security")
            .withUsername("firstelement")
            .withPassword("firstelement");

    public final UUID orgA = UUID.randomUUID();
    public final UUID orgB = UUID.randomUUID();
    public final UUID projectA1 = UUID.randomUUID();
    public final UUID projectA2 = UUID.randomUUID();
    public final UUID projectB1 = UUID.randomUUID();
    public final UUID adminA = UUID.randomUUID();
    public final UUID staffA = UUID.randomUUID();
    public final UUID nobodyA = UUID.randomUUID();
    public final UUID adminB = UUID.randomUUID();

    /** Customer rows, one project each; used for row/aggregate/field negatives. */
    public final UUID customerA1 = UUID.randomUUID();
    public final UUID customerA2 = UUID.randomUUID();
    public final UUID customerB1 = UUID.randomUUID();

    public static final String PASSWORD = "Str0ng-Passw0rd!";
    public static final String ADMIN_A_EMAIL = "admin@a.test";
    public static final String STAFF_A_EMAIL = "staff@a.test";
    public static final String NOBODY_A_EMAIL = "nobody@a.test";
    public static final String ADMIN_B_EMAIL = "admin@b.test";
    public static final String UNKNOWN_EMAIL = "ghost@nowhere.test";

    private final NamedParameterJdbcTemplate jdbc;
    private final PasswordVerifier passwords = new PasswordVerifier();

    public SecurityFixture(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Truncates every table the fixture writes, then re-seeds. */
    public void reset() {
        // customers first: they reference projects/users.
        jdbc.update("delete from customers", new MapSqlParameterSource());
        jdbc.update("delete from project_memberships", new MapSqlParameterSource());
        jdbc.update("delete from sessions", new MapSqlParameterSource());
        jdbc.update("delete from audit_logs", new MapSqlParameterSource());
        jdbc.update("delete from employees", new MapSqlParameterSource());
        jdbc.update("delete from users", new MapSqlParameterSource());
        jdbc.update("delete from projects", new MapSqlParameterSource());
        jdbc.update("delete from organizations", new MapSqlParameterSource());

        insert("""
            insert into organizations ("id", "name", "slug", "createdAt", "updatedAt")
            values (:id, :name, :slug, :now, :now)""",
            new MapSqlParameterSource().addValue("id", orgA).addValue("name", "Alpha Clinic")
                .addValue("slug", "alpha-clinic"));
        insert("""
            insert into organizations ("id", "name", "slug", "createdAt", "updatedAt")
            values (:id, :name, :slug, :now, :now)""",
            new MapSqlParameterSource().addValue("id", orgB).addValue("name", "Beta Clinic")
                .addValue("slug", "beta-clinic"));

        insert("""
            insert into projects ("id", "organizationId", "name", "slug", "createdAt", "updatedAt")
            values (:id, :org, :name, :slug, :now, :now)""",
            new MapSqlParameterSource().addValue("id", projectA1).addValue("org", orgA)
                .addValue("name", "Alpha One").addValue("slug", "alpha-one"));
        // Archived on purpose: an ADMIN keeps access to an archived project so the archive
        // is not a one-way door. The membership test covers it.
        insert("""
            insert into projects ("id", "organizationId", "name", "slug", "isArchived",
                                  "createdAt", "updatedAt")
            values (:id, :org, :name, :slug, true, :now, :now)""",
            new MapSqlParameterSource().addValue("id", projectA2).addValue("org", orgA)
                .addValue("name", "Alpha Two Archived").addValue("slug", "alpha-two"));
        insert("""
            insert into projects ("id", "organizationId", "name", "slug", "createdAt", "updatedAt")
            values (:id, :org, :name, :slug, :now, :now)""",
            new MapSqlParameterSource().addValue("id", projectB1).addValue("org", orgB)
                .addValue("name", "Beta One").addValue("slug", "beta-one"));

        user(adminA, orgA, ADMIN_A_EMAIL, "Alpha Admin", UserRole.ADMIN);
        user(staffA, orgA, STAFF_A_EMAIL, "Alpha Staff", UserRole.EMPLOYEE);
        user(nobodyA, orgA, NOBODY_A_EMAIL, "Alpha Nobody", UserRole.EMPLOYEE);
        user(adminB, orgB, ADMIN_B_EMAIL, "Beta Admin", UserRole.ADMIN);

        // staffA is a member of projectA1 only.
        membership(staffA, orgA, projectA1);
        // nobodyA has no membership row at all.
        // adminA needs none: an ADMIN's scope is their whole organization.

        customer(customerA1, orgA, projectA1, "C-A1-0001", "Alpha One Clinic",
            "+966500000001", "alpha primary");
        customer(customerA2, orgA, projectA2, "C-A2-0001", "Alpha Two Clinic",
            "+966500000002", "alpha archived");
        customer(customerB1, orgB, projectB1, "C-B1-0001", "Beta One Clinic",
            "+966500000003", "beta private note");
    }

    private void user(UUID id, UUID org, String email, String fullName, UserRole role) {
        // The cast is required, not stylistic: users."role" is the "UserRole" enum and the
        // driver binds a String as varchar. This is the class of detail the explicit-mapping
        // rule exists for -- there is no naming strategy or JPA conversion to hide it.
        insert("""
            insert into users ("id", "organizationId", "email", "passwordHash", "fullName",
                               "role", "locale", "isActive", "createdAt", "updatedAt")
            values (:id, :org, :email, :hash, :name, cast(:role as "UserRole"),
                    cast('ar' as "Locale"), true, :now, :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("org", org)
                .addValue("email", email).addValue("hash", passwords.hash(PASSWORD))
                .addValue("name", fullName).addValue("role", role.value()));
    }

    private void membership(UUID userId, UUID org, UUID projectId) {
        insert("""
            insert into project_memberships ("id", "organizationId", "projectId", "userId", "createdAt")
            values (:id, :org, :project, :user, :now)""",
            new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("org", org)
                .addValue("project", projectId).addValue("user", userId));
    }

    private void customer(UUID id, UUID org, UUID projectId, String cxId, String displayName,
                          String phone, String notes) {
        insert("""
            insert into customers ("id", "organizationId", "projectId", "cxId", "displayName",
                                   "primaryPhone", "notes", "firstSeenAt", "lastSeenAt",
                                   "createdAt", "updatedAt")
            values (:id, :org, :project, :cx, :name, :phone, :notes, :now, :now, :now, :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("org", org)
                .addValue("project", projectId).addValue("cx", cxId).addValue("name", displayName)
                .addValue("phone", phone).addValue("notes", notes));
    }

    private void insert(String sql, MapSqlParameterSource parameters) {
        jdbc.update(sql, parameters.addValue("now", LocalDateTime.now()));
    }

    // ------------------------------------------------------------------
    // helpers for tests
    // ------------------------------------------------------------------

    public NamedParameterJdbcTemplate jdbc() {
        return jdbc;
    }

    /** A tenant context equivalent to what the filter builds for each fixture user. */
    public TenantContext contextFor(UUID sessionId, UUID userId, UUID org, UserRole role,
                                    java.util.Set<UUID> projects) {
        return TenantContext.forUser(sessionId == null ? UUID.randomUUID() : sessionId,
            userId, org, role, projects);
    }

    public TenantContext staffAContext() {
        return contextFor(null, staffA, orgA, UserRole.EMPLOYEE, java.util.Set.of(projectA1));
    }

    public TenantContext nobodyAContext() {
        return contextFor(null, nobodyA, orgA, UserRole.EMPLOYEE, java.util.Set.of());
    }

    public TenantContext adminAContext() {
        return contextFor(null, adminA, orgA, UserRole.ADMIN,
            java.util.Set.of(projectA1, projectA2));
    }

    public TenantContext adminBContext() {
        return contextFor(null, adminB, orgB, UserRole.ADMIN, java.util.Set.of(projectB1));
    }
}