        // migration header calls this out: substr("cxId", 3) reads SEVEN characters and shifts the
        // number a digit left, so #33000002 parses as 3000002 and the 0..999999 CHECK refuses it.
package com.firstelement.inbox;

import com.firstelement.security.TenantContext;
import com.firstelement.security.UserRole;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The fixture for the DB-backed inbox tests.
 *
 * <h2>Deterministic ids</h2>
 *
 * <p>Unlike the CRM fixture this one uses <b>fixed</b> UUIDs for tenants, projects, users,
 * customers and conversations, and fixed timestamps for the seeded rows. That is what lets the
 * legacy comparison fixtures assert real values instead of marking everything volatile: a fixture
 * that has to blank out every id proves nothing about ordering or truncation.
 *
 * <h2>Shape</h2>
 *
 * <pre>
 *   Tenant A (org A)            Tenant B (org B)
 *     project A1                 project B1        &lt;- never visible to A
 *     project A2
 *
 *   staff-a  EMPLOYEE of A -&gt; A1 only
 *   admin-a  ADMIN of A    -&gt; A1, A2
 *   outsider EMPLOYEE of A -&gt; no membership row
 *   admin-b  ADMIN of B    -&gt; B1
 * </pre>
 */
@Component
public class InboxFixture {

    public static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16"))
            .withDatabaseName("firstelement_inbox")
            .withUsername("firstelement")
            .withPassword("firstelement");

    /** Fixed so the legacy fixtures can assert literal values. */
    public static final UUID ORG_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    public static final UUID ORG_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    public static final UUID PROJECT_A1 = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000a1");
    public static final UUID PROJECT_A2 = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000a2");
    public static final UUID PROJECT_B1 = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000000b1");

    public static final UUID USER_STAFF_A = UUID.fromString("aaaaaaaa-0000-0000-0000-00000000e001");
    public static final UUID USER_ADMIN_A = UUID.fromString("aaaaaaaa-0000-0000-0000-00000000e002");
    public static final UUID USER_OUTSIDER_A = UUID.fromString("aaaaaaaa-0000-0000-0000-00000000e003");
    public static final UUID USER_ADMIN_B = UUID.fromString("bbbbbbbb-0000-0000-0000-00000000e004");

    public static final UUID EMPLOYEE_STAFF_A = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000ee001");
    public static final UUID EMPLOYEE_ADMIN_A = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000ee002");
    public static final UUID EMPLOYEE_BETA = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000ee003");

    public static final UUID CUSTOMER_A1 = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000cc001");
    public static final UUID CUSTOMER_A2 = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000cc002");
    public static final UUID CUSTOMER_B1 = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000cc003");

    /** A fixed instant so every rendered timestamp in a fixture is predictable. */
    public static final LocalDateTime SEED_AT = LocalDateTime.of(2026, 8, 14, 9, 30, 0);

    private final NamedParameterJdbcTemplate jdbc;

    public InboxFixture(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Truncates and re-seeds. Deterministic: same rows and same ids every time. */
    public void reset() {
        // appointments and their two roster parents go first, and they go FIRST rather than
        // last because they are not this fixture's rows: ClinicFixture may have seeded them for a
        // clinic suite, and a reset that left them behind would let one suite's schedule show up
        // in the next suite's customer 360. They are also deleted child-first because the
        // composite tenant foreign keys Phase 35 added are ON DELETE SET NULL (column) -- deleting
        // a doctor first would silently null every appointment's doctorId instead of refusing,
        // which would leave a clinic assertion quietly running against unassigned rows.
        // clinical_encounters / clinical_prescriptions reference doctors with ON DELETE RESTRICT.
        // A clinic suite that records an encounter would otherwise make "delete from doctors"
        // below fail for every suite that runs after it. TRUNCATE (not DELETE) because the
        // clinical tables carry append-only row triggers; disposable test containers only.
        jdbc.getJdbcOperations().execute(
            "truncate table clinical_prescriptions, clinical_encounters cascade");
        jdbc.update("delete from appointments", new MapSqlParameterSource());
        jdbc.update("delete from procedures", new MapSqlParameterSource());
        jdbc.update("delete from doctors", new MapSqlParameterSource());
        jdbc.update("delete from conversation_events", new MapSqlParameterSource());
        jdbc.update("delete from messages", new MapSqlParameterSource());
        jdbc.update("delete from conversations", new MapSqlParameterSource());
        jdbc.update("delete from conversation_classifications", new MapSqlParameterSource());
        jdbc.update("delete from case_resolutions", new MapSqlParameterSource());
        jdbc.update("delete from case_solution_mappings", new MapSqlParameterSource());
        jdbc.update("delete from case_classification_questions", new MapSqlParameterSource());
        jdbc.update("delete from notes", new MapSqlParameterSource());
        jdbc.update("delete from customer_identities", new MapSqlParameterSource());
        jdbc.update("delete from customers", new MapSqlParameterSource());
        jdbc.update("delete from project_memberships", new MapSqlParameterSource());
        jdbc.update("delete from sessions", new MapSqlParameterSource());
        jdbc.update("delete from audit_logs", new MapSqlParameterSource());
        jdbc.update("delete from employees", new MapSqlParameterSource());
        jdbc.update("delete from users", new MapSqlParameterSource());
        jdbc.update("delete from channel_connections", new MapSqlParameterSource());
        jdbc.update("delete from projects", new MapSqlParameterSource());
        jdbc.update("delete from organizations", new MapSqlParameterSource());

        org(ORG_A, "Alpha Clinic", "alpha-clinic");
        org(ORG_B, "Beta Clinic", "beta-clinic");
        project(PROJECT_A1, ORG_A, "Alpha One", "alpha-one");
        project(PROJECT_A2, ORG_A, "Alpha Two", "alpha-two");
        project(PROJECT_B1, ORG_B, "Beta One", "beta-one");

        user(USER_STAFF_A, ORG_A, "staff@a.test", "Alpha Staff", UserRole.EMPLOYEE);
        user(USER_ADMIN_A, ORG_A, "admin@a.test", "Alpha Admin", UserRole.ADMIN);
        user(USER_OUTSIDER_A, ORG_A, "outsider@a.test", "Alpha Outsider", UserRole.EMPLOYEE);
        user(USER_ADMIN_B, ORG_B, "admin@b.test", "Beta Admin", UserRole.ADMIN);

        membership(USER_STAFF_A, ORG_A, PROJECT_A1);
        membership(USER_ADMIN_A, ORG_A, PROJECT_A1);
        // outsider-a has no membership row; admin-b relies on the ADMIN organization scope.

        employee(EMPLOYEE_STAFF_A, ORG_A, USER_STAFF_A, 5, "ONLINE");
        employee(EMPLOYEE_ADMIN_A, ORG_A, USER_ADMIN_A, 1, "ONLINE");
        employee(EMPLOYEE_BETA, ORG_B, USER_ADMIN_B, 5, "ONLINE");

        customer(CUSTOMER_A1, ORG_A, PROJECT_A1, "#33000001", "Alpha One Clinic",
            "+966500000001");
        customer(CUSTOMER_A2, ORG_A, PROJECT_A2, "#33000002", "Alpha Two Clinic",
            "+966500000002");
        customer(CUSTOMER_B1, ORG_B, PROJECT_B1, "#33000003", "Beta One Clinic",
            "+966500000003");

        // The clinic roster, catalogue and schedule are NOT seeded here. They belong to
        // ClinicFixture, and a suite that needs them calls `clinic.seedClinic()` itself.
        //
        // Seeding them from `reset()` was tried and reverted, and the reason is worth keeping:
        // it broke `LegacyFixtureComparisonTest`, whose entire claim is that the Spring responses
        // match a recorded LEGACY body byte for byte. Those fixtures record a customer 360 with an
        // empty `appointments` array, and seeding appointments onto `CUSTOMER_A1` made the new
        // implementation disagree with the legacy capture -- not because the implementation was
        // wrong, but because the fixture had been changed underneath the comparison. A shared
        // reset that other suites observe must not gain rows that alter what "legacy-shaped"
        // means.

        // The customer-reference counter must know what this fixture has ALREADY been issued.
        //
        // `customer(...)` above inserts references such as #33000001, and the allocator
        // `firstelement_next_customer_cx_id` starts from `customer_cx_sequences`. With the counter
        // empty it hands out #33000001 again, the composite (organizationId, cxId) unique refuses
        // the insert, and because that failure lands mid-transaction PostgreSQL then rejects every
        // later statement with "current transaction is aborted" -- which reads as a JDBC or schema
        // fault rather than as a fixture that never seeded its counter.
        //
        // This is the same trap CrmFixture.reset() hit (ADR-0014): a counter cannot be derived from a
        // count of what exists, because it must also account for what has already been handed out.
        // The statement is the backfill from V20261007110000, derived from the rows rather than
        // hard-coded, so it stays correct if the seeded references ever change.
        // `#33` occupies positions 1-3, so the six digits begin at position 4 -- NOT 3. The
        // migration header calls this out: substr("cxId", 3) reads SEVEN characters and shifts the
        // number a digit left, so #33000002 parses as 3000002 and the 0..999999 CHECK refuses it.
        jdbc.update("""
            insert into customer_cx_sequences ("id", "organizationId", "lastAllocatedCxSequence",
                                               "createdAt", "updatedAt")
            select gen_random_uuid(), c."organizationId", max(substr(c."cxId", 4)::bigint),
                   :now, :now
              from customers c
             group by c."organizationId"
            on conflict ("organizationId") do update
               set "lastAllocatedCxSequence" = excluded."lastAllocatedCxSequence",
                   "updatedAt" = excluded."updatedAt"
            """, new MapSqlParameterSource().addValue("now", SEED_AT));
    }

    // ------------------------------------------------------------------
    // seeds
    // ------------------------------------------------------------------

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

    private void user(UUID id, UUID org, String email, String fullName, UserRole role) {
        at(SEED_AT, """
            insert into users ("id", "organizationId", "email", "passwordHash", "fullName",
                               "role", "locale", "isActive", "createdAt", "updatedAt")
            values (:id, :org, :email, 'not-a-real-hash', :name, cast(:role as "UserRole"),
                    cast('ar' as "Locale"), true, :now, :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("org", org)
                .addValue("email", email).addValue("name", fullName).addValue("role", role.value()));
    }

    private void membership(UUID userId, UUID org, UUID projectId) {
        at(SEED_AT, """
            insert into project_memberships ("id", "organizationId", "projectId", "userId", "createdAt")
            values (:id, :org, :project, :user, :now)""",
            new MapSqlParameterSource().addValue("id", UUID.nameUUIDFromBytes(
                (projectId.toString() + userId.toString()).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .addValue("org", org).addValue("project", projectId).addValue("user", userId));
    }

    private void employee(UUID id, UUID org, UUID userId, int maxConcurrent, String availability) {
        at(SEED_AT, """
            insert into employees ("id", "organizationId", "userId", "availabilityStatus",
                                   "statusChangedAt", "maxConcurrentConversations",
                                   "activeConversationCount", "createdAt", "updatedAt")
            values (:id, :org, :user, cast(:availability as "EmployeeAvailabilityStatus"), :now,
                    :max, 0, :now, :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("org", org)
                .addValue("user", userId).addValue("max", maxConcurrent)
                .addValue("availability", availability));
    }

    private void customer(UUID id, UUID org, UUID projectId, String cxId, String displayName,
                          String phone) {
        at(SEED_AT, """
            insert into customers ("id", "organizationId", "projectId", "cxId", "displayName",
                                   "primaryPhone", "firstSeenAt", "lastSeenAt", "createdAt",
                                   "updatedAt")
            values (:id, :org, :project, :cx, :name, :phone, :now, :now, :now, :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("org", org)
                .addValue("project", projectId).addValue("cx", cxId).addValue("name", displayName)
                .addValue("phone", phone));
    }

    /** Seeds a conversation at a fixed instant with a fixed id, so a fixture can assert it. */
    public UUID conversation(String suffix, UUID org, UUID project, UUID customer,
                             String ticketNumber, String status, String mode,
                             LocalDateTime lastMessageAt) {
        UUID id = UUID.fromString("cccccccc-0000-0000-0000-" + pad(suffix));
        at(lastMessageAt, """
            insert into conversations ("id", "organizationId", "projectId", "customerId",
                "ticketNumber", "channel", "status", "mode", "priority", "subject",
                "assignedEmployeeId", "lastReplierUserId", "modeVersion", "messageCount",
                "lastMessageAt", "createdAt", "updatedAt")
            values (:id, :org, :project, :customer, :ticket, cast('MOCK' as "ChannelType"),
                    cast(:status as "ConversationStatus"), cast(:mode as "ConversationMode"),
                    cast('NORMAL' as "ConversationPriority"), :subject, :assignee, :replier, 0,
                    :count, :now, :now, :now)""",
            new MapSqlParameterSource()
                .addValue("id", id).addValue("org", org).addValue("project", project)
                .addValue("customer", customer).addValue("ticket", ticketNumber)
                .addValue("status", status).addValue("mode", mode)
                .addValue("subject", "Seeded thread " + suffix)
                .addValue("assignee", null).addValue("replier", null).addValue("count", 0));
        return id;
    }

    /** Seeds a message at a fixed instant. */
    public UUID message(String suffix, UUID org, UUID project, UUID conversation, String direction,
                        String authorType, String body, LocalDateTime sentAt, UUID authorUserId) {
        UUID id = UUID.fromString("dddddddd-0000-0000-0000-" + pad(suffix));
        at(sentAt, """
            insert into messages ("id", "organizationId", "projectId", "conversationId", "channel",
                "direction", "authorType", "authorUserId", "body", "deliveryStatus", "sentAt",
                "createdAt")
            values (:id, :org, :project, :conversation, cast('MOCK' as "ChannelType"),
                    cast(:direction as "MessageDirection"), cast(:author as "MessageAuthorType"),
                    :authorUserId, :body, cast('DELIVERED' as "MessageDeliveryStatus"), :now, :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("org", org)
                .addValue("project", project).addValue("conversation", conversation)
                .addValue("direction", direction).addValue("author", authorType)
                .addValue("authorUserId", authorUserId).addValue("body", body));
        return id;
    }

    /** Seeds a timeline entry at a fixed instant. */
    public UUID event(String suffix, UUID org, UUID project, UUID conversation, String type,
                      String actorType, String payload, LocalDateTime occurredAt, UUID actorUserId) {
        UUID id = UUID.fromString("eeeeeeee-0000-0000-0000-" + pad(suffix));
        at(occurredAt, """
            insert into conversation_events ("id", "organizationId", "projectId", "conversationId",
                "type", "actorType", "actorUserId", "payload", "occurredAt")
            values (:id, :org, :project, :conversation, cast(:type as "ConversationEventType"),
                    cast(:actor as "MessageAuthorType"), :actorUserId, CAST(:payload AS jsonb), :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("org", org)
                .addValue("project", project).addValue("conversation", conversation)
                .addValue("type", type).addValue("actor", actorType)
                .addValue("actorUserId", actorUserId).addValue("payload", payload));
        return id;
    }

    /** Seeds a customer identity at a fixed instant. */
    public UUID identity(String suffix, UUID org, UUID project, UUID customer, String platform,
                         String externalId, String displayName, boolean verified) {
        UUID id = UUID.fromString("ffffffff-0000-0000-0000-" + pad(suffix));
        at(SEED_AT, """
            insert into customer_identities ("id", "organizationId", "projectId", "customerId",
                "platform", "externalId", "displayName", "isVerified", "createdAt", "updatedAt")
            values (:id, :org, :project, :customer, cast(:platform as "ChannelType"), :external,
                    :name, :verified, :now, :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("org", org)
                .addValue("project", project).addValue("customer", customer)
                .addValue("platform", platform).addValue("external", externalId)
                .addValue("name", displayName).addValue("verified", verified));
        return id;
    }

    public void mockChannel(UUID org, UUID project) {
        at(SEED_AT, """
            insert into channel_connections ("id", "organizationId", "projectId", "channel",
                "isEnabled", "status", "createdAt", "updatedAt")
            values (:id, :org, :project, cast('MOCK' as "ChannelType"), true,
                    cast('CONNECTED' as "ChannelConnectionStatus"), :now, :now)""",
            new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("org", org)
                .addValue("project", project));
    }

    /** The row's {@code updatedAt}, which is the conversation's concurrency token. */
    public LocalDateTime updatedAtOf(String table, UUID id) {
        return jdbc.queryForObject("select \"updatedAt\" from " + table + " where \"id\" = :id",
            new MapSqlParameterSource().addValue("id", id), LocalDateTime.class);
    }

    /** A column rendered as text, so a boolean or integer can be asserted without a cast. */
    public String columnText(String table, UUID id, String column) {
        return jdbc.queryForObject(
            "select \"" + column + "\"::text from " + table + " where \"id\" = :id",
            new MapSqlParameterSource().addValue("id", id), String.class);
    }

    /** A count query. The SQL is a literal in the test, never a caller-supplied fragment. */
    public Integer count(String sql) {
        return jdbc.queryForObject(sql, new MapSqlParameterSource(), Integer.class);
    }

    public NamedParameterJdbcTemplate jdbc() {
        return jdbc;
    }

    // ------------------------------------------------------------------
    // contexts
    // ------------------------------------------------------------------

    public TenantContext staffA() {
        return contextFor(USER_STAFF_A, ORG_A, UserRole.EMPLOYEE, Set.of(PROJECT_A1));
    }

    public TenantContext adminA() {
        return contextFor(USER_ADMIN_A, ORG_A, UserRole.ADMIN, Set.of(PROJECT_A1, PROJECT_A2));
    }

    public TenantContext outsiderA() {
        return contextFor(USER_OUTSIDER_A, ORG_A, UserRole.EMPLOYEE, Set.of());
    }

    public TenantContext adminB() {
        return contextFor(USER_ADMIN_B, ORG_B, UserRole.ADMIN, Set.of(PROJECT_B1));
    }

    public TenantContext contextFor(UUID userId, UUID org, UserRole role, Set<UUID> projects) {
        return TenantContext.forUser(UUID.randomUUID(), userId, org, role, projects);
    }

    // ------------------------------------------------------------------

    private static String pad(String suffix) {
        StringBuilder padded = new StringBuilder(suffix);
        while (padded.length() < 12) {
            padded.append('0');
        }
        return padded.substring(0, 12);
    }

    private void at(LocalDateTime now, String sql, MapSqlParameterSource parameters) {
        jdbc.update(sql, parameters.addValue("now", now));
    }

    /** Convenience for tests that need a per-test deterministic instant. */
    public static LocalDateTime at(int minutesAfterSeed) {
        return SEED_AT.plusMinutes(minutesAfterSeed);
    }

    public static List<String> activeStatuses() {
        return List.of("OPEN", "PENDING");
    }
}
