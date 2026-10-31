package com.firstelement.automation;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The workflow fixture: two tenants, the real Flyway chain, and the rows every claim in this phase
 * needs in order to be falsifiable.
 *
 * <h2>Two tenants, always</h2>
 *
 * <p>Every Phase-6 acceptance gate has a cross-tenant negative, so the fixture cannot be one tenant.
 * A second tenant also makes "the same key in another tenant is a different thing" testable for
 * workflows, calendars, policies, rules and conversations.
 *
 * <h2>Seeded in foreign-key order, truncated in reverse</h2>
 *
 * <p>{@link #reset()} deletes child tables first and seeds parents last. The Phase-6a tables are
 * included, because a run/action/collision row from a previous test is exactly what makes a later
 * "cross-tenant negatives return zero rows" assertion accidentally pass.
 *
 * <h2>No skip path</h2>
 *
 * <p>{@link #POSTGRES} is started by {@link WorkflowIntegrationTestBase} and every property supplier
 * starts it first, so a Docker problem fails the build rather than turning a concurrency test green.
 */
@Component
public class WorkflowFixture {

    public static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16"))
            .withDatabaseName("firstelement_workflows")
            .withUsername("firstelement")
            .withPassword("firstelement");

    public static final UUID ORG_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    public static final UUID ORG_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    public static final UUID PROJECT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000a1");
    public static final UUID PROJECT_A2 = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000a2");
    public static final UUID PROJECT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000000b1");

    public static final UUID ADMIN_A = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000e1");
    public static final UUID EMPLOYEE_USER_A = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000e2");
    public static final UUID ADMIN_B = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000000e3");

    public static final UUID EMPLOYEE_A = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000ee01");
    public static final UUID EMPLOYEE_A_BUSY = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000ee02");
    public static final UUID EMPLOYEE_B = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000ee03");

    public static final UUID CUSTOMER_A = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000cc01");
    public static final UUID CUSTOMER_B = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000cc02");

    /** A fixed past instant, so "created two days ago" is reproducible. */
    public static final LocalDateTime SEED_AT = LocalDateTime.of(2026, 8, 14, 9, 30, 0);

    private final NamedParameterJdbcTemplate jdbc;

    public WorkflowFixture(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Truncates and re-seeds. Children first, parents last. */
    public void reset() {
        for (String table : new String[] {
            "workflow_run_actions", "workflow_runs", "collision_records", "escalations",
            "conversation_scores", "escalation_rules", "routing_rules", "messages",
            "conversation_events", "employee_availability", "employee_skills", "sla_policies",
            "sla_calendar_holidays", "sla_calendar_windows", "sla_calendars", "workflow_versions",
            "workflow_definitions", "conversations", "customer_identities", "customers",
            "project_memberships", "sessions", "audit_logs", "employees", "users",
            "channel_connections", "job_dead_letters", "job_attempts", "idempotency_records",
            "outbox_events", "projects", "organizations"}) {
            jdbc.update("delete from " + table, new MapSqlParameterSource());
        }

        org(ORG_A, "Alpha Clinic", "alpha-clinic");
        org(ORG_B, "Beta Clinic", "beta-clinic");
        project(PROJECT_A, ORG_A, "Alpha One", "alpha-one");
        project(PROJECT_A2, ORG_A, "Alpha Two", "alpha-two");
        project(PROJECT_B, ORG_B, "Beta One", "beta-one");

        user(ADMIN_A, ORG_A, "admin@a.test", "ADMIN");
        user(EMPLOYEE_USER_A, ORG_A, "staff@a.test", "EMPLOYEE");
        user(ADMIN_B, ORG_B, "admin@b.test", "ADMIN");

        membership(ADMIN_A, PROJECT_A);
        membership(ADMIN_A, PROJECT_A2);
        membership(EMPLOYEE_USER_A, PROJECT_A);
        membership(ADMIN_B, PROJECT_B);

        employee(EMPLOYEE_A, ORG_A, ADMIN_A, "billing", "ONLINE", 0, 5);
        employee(EMPLOYEE_A_BUSY, ORG_A, EMPLOYEE_USER_A, "support", "BUSY", 3, 5);
        employee(EMPLOYEE_B, ORG_B, ADMIN_B, "billing", "ONLINE", 0, 5);

        customer(CUSTOMER_A, ORG_A, PROJECT_A, "#33000001");
        customer(CUSTOMER_B, ORG_B, PROJECT_B, "#33000002");
    }

    private static final class Roles {
        private Roles() {
        }

        static final String EMPLOYEE = "EMPLOYEE";
        static final String ADMIN = "ADMIN";
    }

    private void org(UUID id, String name, String slug) {
        at(SEED_AT, """
            insert into organizations ("id", "name", "slug", "createdAt", "updatedAt")
            values (:id, :name, :slug, :now, :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("name", name)
                .addValue("slug", slug));
    }

    private void project(UUID id, UUID org, String name, String slug) {
        at(SEED_AT, """
            insert into projects ("id", "organizationId", "name", "slug", "createdAt", "updatedAt")
            values (:id, :org, :name, :slug, :now, :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("org", org)
                .addValue("name", name).addValue("slug", slug));
    }

    private void user(UUID id, UUID org, String email, String role) {
        at(SEED_AT, """
            insert into users ("id", "organizationId", "email", "passwordHash", "fullName", "role",
                               "locale", "isActive", "createdAt", "updatedAt")
            values (:id, :org, :email, 'not-a-real-hash', :name, cast(:role as "UserRole"),
                    cast('ar' as "Locale"), true, :now, :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("org", org)
                .addValue("email", email).addValue("role", role).addValue("name", "Seeded User"));
    }

    private void membership(UUID userId, UUID projectId) {
        // project_memberships has no role column: the role is on users, and a membership is only the
        // existence of the pairing. Stating that here because guessing the shape from other modules'
        // fixtures is how a fixture ends up writing a column that does not exist.
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("userId", userId).addValue("projectId", projectId)
            .addValue("org", organizationOf(userId))
            .addValue("id", UUID.randomUUID())
            .addValue("now", SEED_AT);
        jdbc.update("""
            insert into project_memberships ("id", "organizationId", "projectId", "userId", "createdAt")
            values (:id, :org, :projectId, :userId, :now)
            """, parameters);
    }

    private void employee(UUID id, UUID org, UUID userId, String department, String status,
                          int active, int max) {
        at(SEED_AT, """
            insert into employees ("id", "organizationId", "userId", "department",
                                   "availabilityStatus", "statusChangedAt",
                                   "maxConcurrentConversations", "activeConversationCount",
                                   "createdAt", "updatedAt")
            values (:id, :org, :user, :department, cast(:status as "EmployeeAvailabilityStatus"), :now,
                    :max, :active, :now, :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("org", org)
                .addValue("user", userId).addValue("department", department).addValue("status", status)
                .addValue("max", max).addValue("active", active));
    }

    private void customer(UUID id, UUID org, UUID projectId, String cxId) {
        at(SEED_AT, """
            insert into customers ("id", "organizationId", "projectId", "cxId", "displayName",
                                   "primaryPhone", "firstSeenAt", "lastSeenAt", "createdAt",
                                   "updatedAt")
            values (:id, :org, :project, :cx, 'Seeded Customer', '+966500000001', :now, :now, :now, :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("org", org)
                .addValue("project", projectId).addValue("cx", cxId));
    }

    /** A conversation in {@code AI} mode, the state the two-phase send starts from. */
    public UUID conversation(UUID org, UUID projectId, UUID customerId, String mode, String status) {
        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        jdbc.update("""
            insert into conversations ("id", "organizationId", "projectId", "customerId",
                "ticketNumber", "channel", "status", "mode", "priority", "messageCount",
                "lastMessageAt", "createdAt", "updatedAt")
            values (:id, :org, :project, :customer, :ticket, cast('MOCK' as "ChannelType"),
                    cast(:status as "ConversationStatus"), cast(:mode as "ConversationMode"),
                    cast('NORMAL' as "ConversationPriority"), 0, :now, :createdAt, :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("org", org)
                .addValue("project", projectId).addValue("customer", customerId)
                .addValue("ticket", "#WF" + Math.abs(id.hashCode() % 100000))
                .addValue("mode", mode).addValue("status", status)
                .addValue("createdAt", now.minusDays(1))
                .addValue("now", now));
        return id;
    }

    /** A customer message, used by the scorer and the handover summary. */
    public UUID customerMessage(UUID org, UUID projectId, UUID conversationId, String body,
                                LocalDateTime sentAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            insert into messages ("id", "organizationId", "projectId", "conversationId", "channel",
                "direction", "authorType", "body", "deliveryStatus", "sentAt", "createdAt")
            values (:id, :org, :project, :conversationId, cast('MOCK' as "ChannelType"),
                    cast('INBOUND' as "MessageDirection"), cast('CUSTOMER' as "MessageAuthorType"),
                    :body, cast('SENT' as "MessageDeliveryStatus"), :sentAt, :sentAt)""",
            new MapSqlParameterSource().addValue("id", id).addValue("org", org)
                .addValue("project", projectId).addValue("conversationId", conversationId)
                .addValue("body", body).addValue("sentAt", sentAt));
        return id;
    }

    /** A routing rule, in the baseline {@code routing_rules} table. */
    public UUID routingRule(UUID org, UUID projectId, String name, int priority, String department,
                            String requiredSkill, String strategy) {
        UUID id = UUID.randomUUID();
        at(SEED_AT, """
            insert into routing_rules ("id", "organizationId", "projectId", "name", "isEnabled",
                "priority", "intent", "department", "requiredSkill", "strategy", "isTerminal",
                "createdAt", "updatedAt")
            values (:id, :org, :project, :name, true, :priority, null, :department, :requiredSkill,
                    cast(:strategy as "RoutingStrategy"), true, :now, :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("org", org)
                .addValue("project", projectId).addValue("name", name).addValue("priority", priority)
                .addValue("department", department).addValue("requiredSkill", requiredSkill)
                .addValue("strategy", strategy));
        return id;
    }

    /** An escalation rule, with its conditions as the stored jsonb array. */
    public UUID escalationRule(UUID org, UUID projectId, String name, int priority,
                               String combinator, String conditionsJson, String department) {
        UUID id = UUID.randomUUID();
        at(SEED_AT, """
            insert into escalation_rules ("id", "organizationId", "projectId", "name", "isEnabled",
                "priority", "conditions", "combinator", "department", "createdAt", "updatedAt")
            values (:id, :org, :project, :name, true, :priority, cast(:conditions as jsonb),
                    :combinator, :department, :now, :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("org", org)
                .addValue("project", projectId).addValue("name", name).addValue("priority", priority)
                .addValue("conditions", conditionsJson).addValue("combinator", combinator)
                .addValue("department", department));
        return id;
    }

    /** An employee skill row. */
    public void employeeSkill(UUID org, UUID employeeId, String skill, int level) {
        at(SEED_AT, """
            insert into employee_skills ("id", "organizationId", "employeeId", "skill", "level",
                                         "createdAt")
            values (:id, :org, :employee, :skill, :level, :now)""",
            new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("org", org)
                .addValue("employee", employeeId).addValue("skill", skill).addValue("level", level));
    }

    /** An availability window for an employee. */
    public void employeeWindow(UUID org, UUID employeeId, int dayOfWeek, int startMinute,
                               int endMinute, String timezone) {
        at(SEED_AT, """
            insert into employee_availability ("id", "organizationId", "employeeId", "dayOfWeek",
                "startMinute", "endMinute", "timezone", "createdAt")
            values (:id, :org, :employee, :dayOfWeek, :startMinute, :endMinute, :timezone, :now)""",
            new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("org", org)
                .addValue("employee", employeeId).addValue("dayOfWeek", dayOfWeek)
                .addValue("startMinute", startMinute).addValue("endMinute", endMinute)
                .addValue("timezone", timezone));
    }

    private UUID organizationOf(UUID userId) {
        return jdbc.queryForObject("select u.\"organizationId\" from users u where u.\"id\" = :id",
            new MapSqlParameterSource().addValue("id", userId), UUID.class);
    }

    private void at(LocalDateTime when, String sql, MapSqlParameterSource parameters) {
        parameters.addValue("now", when);
        jdbc.update(sql, parameters);
    }

    /** Counts rows in a table, tenant-scoped or not. Test-only convenience. */
    public int count(String sql, Map<String, Object> parameters) {
        Integer count = jdbc.queryForObject(sql, new MapSqlParameterSource(parameters), Integer.class);
        return count == null ? 0 : count;
    }

    /** The role a seeded user has, for a principal assertion. */
    public String roleOf(UUID userId) {
        return jdbc.queryForObject("select u.\"role\"::text from users u where u.\"id\" = :id",
            new MapSqlParameterSource().addValue("id", userId), String.class);
    }

    /** Employee ids to make the fixture's roster explicit in an assertion message. */
    public Map<UUID, String> roster(UUID org) {
        Map<UUID, String> roster = new java.util.LinkedHashMap<>();
        for (var row : jdbc.queryForList("""
            select e."id" as id, u."email" as email from employees e
              join users u on u."id" = e."userId"
             where e."organizationId" = :org
            """, new MapSqlParameterSource().addValue("org", org))) {
            roster.put((UUID) row.get("id"), String.valueOf(row.get("email")));
        }
        return roster;
    }

    /** Roles kept reachable for a reader; the seed uses them. */
    public static String adminRole() {
        return Roles.ADMIN;
    }

    public static String employeeRole() {
        return Roles.EMPLOYEE;
    }
}