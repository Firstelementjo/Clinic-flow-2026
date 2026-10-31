package com.firstelement.notifications;

import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The notifications fixture: two tenants, real Flyway, and the {@code notifications} table the
 * realtime stream is only a hint about.
 */
@Component
public class NotificationsFixture {

    public static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16"))
            .withDatabaseName("firstelement_notifications")
            .withUsername("firstelement")
            .withPassword("firstelement");

    public static final UUID ORG_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    public static final UUID ORG_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    public static final UUID PROJECT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000a1");
    public static final UUID PROJECT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000000b1");

    public static final UUID USER_A = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000e1");
    public static final UUID USER_A2 = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000e2");
    public static final UUID USER_B = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000000e3");

    public static final LocalDateTime SEED_AT = LocalDateTime.of(2026, 8, 14, 9, 30, 0);

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationsFixture(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void reset() {
        // `sessions` and `audit_logs` are here for a reason: a suite that issues real browser
        // sessions must not inherit one from a previous test, and a suite that asserts "exactly one
        // refused-subscription audit row" must not inherit one either. They are deleted before the
        // rows they reference.
        for (String table : new String[] {"notifications", "job_dead_letters", "job_attempts",
            "idempotency_records", "outbox_events", "sessions", "audit_logs", "conversations",
            "messages", "project_memberships", "users", "employees", "projects", "organizations"}) {
            jdbc.update("delete from " + table, new MapSqlParameterSource());
        }
        org(ORG_A, "Alpha Clinic", "alpha-clinic");
        org(ORG_B, "Beta Clinic", "beta-clinic");
        project(PROJECT_A, ORG_A, "Alpha One", "alpha-one");
        project(PROJECT_B, ORG_B, "Beta One", "beta-one");
        user(USER_A, ORG_A, "staff@a.test", "EMPLOYEE");
        user(USER_A2, ORG_A, "lead@a.test", "EMPLOYEE");
        user(USER_B, ORG_B, "admin@b.test", "ADMIN");
        employee(USER_A, ORG_A);
        membership(USER_A, ORG_A, PROJECT_A);
        membership(USER_A2, ORG_A, PROJECT_A);
        membership(USER_B, ORG_B, PROJECT_B);
    }

    private void org(UUID id, String name, String slug) {
        at("""
            insert into organizations ("id", "name", "slug", "createdAt", "updatedAt")
            values (:id, :name, :slug, :now, :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("name", name)
                .addValue("slug", slug));
    }

    private void project(UUID id, UUID org, String name, String slug) {
        at("""
            insert into projects ("id", "organizationId", "name", "slug", "createdAt", "updatedAt")
            values (:id, :org, :name, :slug, :now, :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("org", org)
                .addValue("name", name).addValue("slug", slug));
    }

    private void user(UUID id, UUID org, String email, String role) {
        at("""
            insert into users ("id", "organizationId", "email", "passwordHash", "fullName", "role",
                               "locale", "isActive", "createdAt", "updatedAt")
            values (:id, :org, :email, 'not-a-real-hash', 'Seeded User', cast(:role as "UserRole"),
                    cast('ar' as "Locale"), true, :now, :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("org", org)
                .addValue("email", email).addValue("role", role));
    }

    private void employee(UUID userId, UUID org) {
        at("""
            insert into employees ("id", "organizationId", "userId", "availabilityStatus",
                                   "statusChangedAt", "maxConcurrentConversations",
                                   "activeConversationCount", "createdAt", "updatedAt")
            values (:id, :org, :user, cast('ONLINE' as "EmployeeAvailabilityStatus"), :now,
                    5, 0, :now, :now)""",
            new MapSqlParameterSource()
                .addValue("id", UUID.nameUUIDFromBytes(userId.toString()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .addValue("org", org).addValue("user", userId));
    }

    private void membership(UUID userId, UUID org, UUID projectId) {
        at("""
            insert into project_memberships ("id", "organizationId", "projectId", "userId",
                                              "createdAt")
            values (:id, :org, :project, :user, :now)""",
            new MapSqlParameterSource()
                .addValue("id", UUID.nameUUIDFromBytes((projectId.toString() + userId.toString())
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .addValue("org", org).addValue("project", projectId).addValue("user", userId));
    }

    public Integer count(String sql) {
        return jdbc.queryForObject(sql, new MapSqlParameterSource(), Integer.class);
    }

    public NamedParameterJdbcTemplate jdbc() {
        return jdbc;
    }

    private void at(String sql, MapSqlParameterSource parameters) {
        jdbc.update(sql, parameters.addValue("now", SEED_AT));
    }
}