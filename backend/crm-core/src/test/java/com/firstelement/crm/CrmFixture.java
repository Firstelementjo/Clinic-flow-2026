package com.firstelement.crm;

import com.firstelement.security.TenantContext;
import com.firstelement.security.UserRole;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The fixture shared by the DB-backed CRM tests.
 *
 * <h2>Two tenants, three projects</h2>
 *
 * <pre>
 *   Tenant A (alpha)              Tenant B (beta)
 *     project A1 "alpha-one"        project B1 "beta-one"     &lt;- NEVER visible to A
 *     project A2 "alpha-two"
 *
 *   admin@a  : ADMIN of A   -&gt; both A projects
 *   staff@a  : EMPLOYEE of A -&gt; only A1
 *   outsider : EMPLOYEE of A -&gt; no project_memberships row at all
 * </pre>
 *
 * <p>{@code outsider} is the row that proves FR-1.4 ("a user with no membership must receive no
 * tenant data"); the cross-tenant rows prove FR-2.7.
 *
 * <h2>Mapping notes</h2>
 *
 * <ul>
 *   <li>Every identifier is the quoted camelCase column name the verified baseline defines.</li>
 *   <li>Every timestamp is a {@link LocalDateTime}, because all of them are
 *       {@code timestamp without time zone}.</li>
 *   <li>Enum columns are written with an explicit {@code cast(:x as "Enum")}; the driver binds a
 *       String as {@code varchar}.</li>
 * </ul>
 */
@Component
public class CrmFixture {

    /** The Phase-2 database contract requires pgvector; a plain postgres image is not one. */
    public static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16"))
            .withDatabaseName("firstelement_crm")
            .withUsername("firstelement")
            .withPassword("firstelement");

    public final UUID orgA = UUID.randomUUID();
    public final UUID orgB = UUID.randomUUID();
    public final UUID projectA1 = UUID.randomUUID();
    public final UUID projectA2 = UUID.randomUUID();
    public final UUID projectB1 = UUID.randomUUID();
    public final UUID adminA = UUID.randomUUID();
    public final UUID staffA = UUID.randomUUID();
    public final UUID outsiderA = UUID.randomUUID();
    public final UUID adminB = UUID.randomUUID();

    public final UUID customerA1 = UUID.randomUUID();
    public final UUID customerA2 = UUID.randomUUID();
    public final UUID customerB1 = UUID.randomUUID();

    public final UUID employeeA = UUID.randomUUID();
    public final UUID employeeA2 = UUID.randomUUID();

    private final NamedParameterJdbcTemplate jdbc;

    public CrmFixture(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Truncates every table the fixture writes, then re-seeds. */
    public void reset() {
        // The clinic tables, cleared BEFORE the deletes below so a stale finding cannot
      // hold a patient row open.
      //
      // TRUNCATE, not DELETE, and deliberately so: patient_odontogram_findings is guarded by
      // firstelement_append_only(), which refuses row-level DELETE because a clinical record
      // must never be rewritten. TRUNCATE does not fire row triggers. This is a disposable
      // Testcontainer that is thrown away with the test run; it is never production, and the
      // alternative would be to weaken an append-only guard to make a fixture convenient.
      jdbc.getJdbcOperations().execute(
          "truncate table clinic_media_access_audit, patient_periodontal_measurements, patient_periodontal_exams, patient_odontogram_findings, patient_attachments,"
              + " patient_identities, patients, clinic_finding_vocabulary cascade");

      // Children first: they reference projects/users/customers.
        jdbc.update("delete from clinic_ai_proposals", new MapSqlParameterSource());
        jdbc.update("delete from clinic_ai_governance", new MapSqlParameterSource());
        jdbc.update("delete from clinic_media_access_audit", new MapSqlParameterSource());
        jdbc.update("delete from patient_attachments", new MapSqlParameterSource());
        jdbc.update("delete from clinic_audit_log", new MapSqlParameterSource());
        jdbc.update("delete from clinic_break_glass_grants", new MapSqlParameterSource());
        jdbc.update("delete from conversation_events", new MapSqlParameterSource());
        jdbc.update("delete from messages", new MapSqlParameterSource());
        jdbc.update("delete from conversations", new MapSqlParameterSource());
        jdbc.update("delete from conversation_classifications", new MapSqlParameterSource());
        jdbc.update("delete from case_resolutions", new MapSqlParameterSource());
        jdbc.update("delete from case_solution_mappings", new MapSqlParameterSource());
        jdbc.update("delete from case_classification_questions", new MapSqlParameterSource());
        jdbc.update("delete from notes", new MapSqlParameterSource());
        jdbc.update("delete from document_chunks", new MapSqlParameterSource());
        jdbc.update("delete from documents", new MapSqlParameterSource());
        jdbc.update("delete from knowledge_bases", new MapSqlParameterSource());
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

        org("Alpha Clinic", "alpha-clinic");
        org(orgB, "Beta Clinic", "beta-clinic");
        project(projectA1, orgA, "Alpha One", "alpha-one", false);
        project(projectA2, orgA, "Alpha Two", "alpha-two", false);
        project(projectB1, orgB, "Beta One", "beta-one", false);

        user(adminA, orgA, "admin@a.test", "Alpha Admin", UserRole.ADMIN);
        user(staffA, orgA, "staff@a.test", "Alpha Staff", UserRole.EMPLOYEE);
        user(outsiderA, orgA, "outsider@a.test", "Alpha Outsider", UserRole.EMPLOYEE);
        user(adminB, orgB, "admin@b.test", "Beta Admin", UserRole.ADMIN);

        membership(staffA, orgA, projectA1);
        membership(adminA, orgA, projectA1);
        // outsiderA has no membership row at all.
        // adminA needs no membership to reach A2 as an ADMIN, but employeeA2's eligibility is
        // decided by a membership row, so the seeded admin has one in A1 only. That is what makes
        // the cross-project assignment refusal testable.

        employee(employeeA, orgA, staffA, 5);
        employee(employeeA2, orgA, adminA, 5);

        customer(customerA1, orgA, projectA1, "#33000001", "Alpha One Clinic",
            "+966500000001", "alpha-one notes");
        customer(customerA2, orgA, projectA2, "#33000002", "Alpha Two Clinic",
            "+966500000002", null);
        customer(customerB1, orgB, projectB1, "#33000003", "Beta One Clinic",
            "+966500000003", "beta private note");

        backfillCxSequences();
    }

    /**
     * Seeds {@code customer_cx_sequences} from {@code max("cxId")} per organization.
     *
     * <p>This is exactly what {@code V20261007110000} does, and the fixture has to do it too.
     *
     * <p><b>Without it, the fixture's customers sit ABOVE the counter.</b> The allocator's floor is
     * the format floor, so a fresh counter hands out {@code #33000000} and then {@code #33000001} —
     * which the seeded {@code customerA1} already holds. Every allocation would spend two attempts
     * walking onto a live reference, the tests would pass for the wrong reason, and the contention
     * loop would be doing work the production data never asks it to do.
     *
     * <p>That is not a hypothetical: the counter was introduced with a backfill migration precisely
     * because a {@code count(*)}-derived allocator cannot know what a tenant has already been
     * <em>issued</em>. Seeding customers without seeding the counter reproduces the very gap ADR-0014
     * was raised about, in the one place that could have caught it.
     *
     * <p>Derived from the rows rather than hard-coded, so it stays correct if the seeded
     * references above ever change.
     */
    private void backfillCxSequences() {
        insert("""
            insert into customer_cx_sequences ("id", "organizationId", "lastAllocatedCxSequence",
                "createdAt", "updatedAt")
            select gen_random_uuid(), "organizationId", max((substr("cxId", 4)::bigint)),
                   (current_timestamp at time zone 'UTC'), (current_timestamp at time zone 'UTC')
              from customers
             group by "organizationId"
            on conflict ("organizationId") do nothing""", new MapSqlParameterSource());
    }

    // ------------------------------------------------------------------
    // seeds
    // ------------------------------------------------------------------

    private void org(String name, String slug) {
        org(orgA, name, slug);
    }

    private void org(UUID id, String name, String slug) {
        insert("""
            insert into organizations ("id", "name", "slug", "createdAt", "updatedAt")
            values (:id, :name, :slug, :now, :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("name", name)
                .addValue("slug", slug));
    }

    private void project(UUID id, UUID org, String name, String slug, boolean archived) {
        insert("""
            insert into projects ("id", "organizationId", "name", "slug", "isArchived",
                                  "createdAt", "updatedAt")
            values (:id, :org, :name, :slug, :archived, :now, :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("org", org)
                .addValue("name", name).addValue("slug", slug).addValue("archived", archived));
    }

    private void user(UUID id, UUID org, String email, String fullName, UserRole role) {
        insert("""
            insert into users ("id", "organizationId", "email", "passwordHash", "fullName",
                               "role", "locale", "isActive", "createdAt", "updatedAt")
            values (:id, :org, :email, 'not-a-real-hash', :name, cast(:role as "UserRole"),
                    cast('ar' as "Locale"), true, :now, :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("org", org)
                .addValue("email", email).addValue("name", fullName).addValue("role", role.value()));
    }

    private void membership(UUID userId, UUID org, UUID projectId) {
        insert("""
            insert into project_memberships ("id", "organizationId", "projectId", "userId", "createdAt")
            values (:id, :org, :project, :user, :now)""",
            new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("org", org)
                .addValue("project", projectId).addValue("user", userId));
    }

    private void employee(UUID id, UUID org, UUID userId, int maxConcurrent) {
        insert("""
            insert into employees ("id", "organizationId", "userId", "availabilityStatus",
                                   "statusChangedAt", "maxConcurrentConversations",
                                   "activeConversationCount", "createdAt", "updatedAt")
            values (:id, :org, :user, cast('ONLINE' as "EmployeeAvailabilityStatus"), :now,
                    :max, 0, :now, :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("org", org)
                .addValue("user", userId).addValue("max", maxConcurrent));
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
    // row helpers used by tests
    // ------------------------------------------------------------------

    public UUID insertConversation(UUID org, UUID project, UUID customer, String ticketNumber,
                                   String channel, String status, String mode) {
        UUID id = UUID.randomUUID();
        insert("""
            insert into conversations ("id", "organizationId", "projectId", "customerId",
                "ticketNumber", "channel", "status", "mode", "priority", "messageCount",
                "lastMessageAt", "createdAt", "updatedAt")
            values (:id, :org, :project, :customer, :ticket, cast(:channel as "ChannelType"),
                    cast(:status as "ConversationStatus"), cast(:mode as "ConversationMode"),
                    cast('NORMAL' as "ConversationPriority"), 0, :now, :now, :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("org", org)
                .addValue("project", project).addValue("customer", customer)
                .addValue("ticket", ticketNumber).addValue("channel", channel)
                .addValue("status", status).addValue("mode", mode));
        return id;
    }

    public UUID insertMessage(UUID org, UUID project, UUID conversation, String direction,
                              String authorType, String body, String externalMessageId) {
        UUID id = UUID.randomUUID();
        insert("""
            insert into messages ("id", "organizationId", "projectId", "conversationId", "channel",
                "direction", "authorType", "body", "externalMessageId", "deliveryStatus",
                "sentAt", "createdAt")
            values (:id, :org, :project, :conversation, cast('MOCK' as "ChannelType"),
                    cast(:direction as "MessageDirection"), cast(:author as "MessageAuthorType"),
                    :body, :external, cast('DELIVERED' as "MessageDeliveryStatus"), :now, :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("org", org)
                .addValue("project", project).addValue("conversation", conversation)
                .addValue("direction", direction).addValue("author", authorType)
                .addValue("body", body).addValue("external", externalMessageId));
        return id;
    }

    public void insertTimelineEvent(UUID org, UUID project, UUID conversation, String type,
                                    String actorType, String payload, LocalDateTime occurredAt) {
        insert("""
            insert into conversation_events ("id", "organizationId", "projectId", "conversationId",
                "type", "actorType", "payload", "occurredAt")
            values (:id, :org, :project, :conversation, cast(:type as "ConversationEventType"),
                    cast(:actor as "MessageAuthorType"), CAST(:payload AS jsonb), :occurredAt)""",
            new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("org", org)
                .addValue("project", project).addValue("conversation", conversation)
                .addValue("type", type).addValue("actor", actorType)
                .addValue("payload", payload).addValue("occurredAt", occurredAt));
    }

    public void insertIdentity(UUID org, UUID project, UUID customer, String platform,
                               String externalId, String displayName, boolean verified) {
        insert("""
            insert into customer_identities ("id", "organizationId", "projectId", "customerId",
                "platform", "externalId", "displayName", "isVerified", "createdAt", "updatedAt")
            values (:id, :org, :project, :customer, cast(:platform as "ChannelType"), :external,
                    :name, :verified, :now, :now)""",
            new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("org", org)
                .addValue("project", project).addValue("customer", customer)
                .addValue("platform", platform).addValue("external", externalId)
                .addValue("name", displayName).addValue("verified", verified));
    }

    public void insertChannelConnection(UUID org, UUID project, String channel, boolean enabled) {
        insert("""
            insert into channel_connections ("id", "organizationId", "projectId", "channel",
                "isEnabled", "status", "createdAt", "updatedAt")
            values (:id, :org, :project, cast(:channel as "ChannelType"), :enabled,
                    cast('CONNECTED' as "ChannelConnectionStatus"), :now, :now)""",
            new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("org", org)
                .addValue("project", project).addValue("channel", channel)
                .addValue("enabled", enabled));
    }

    public NamedParameterJdbcTemplate jdbc() {
        return jdbc;
    }

    // ------------------------------------------------------------------
    // contexts
    // ------------------------------------------------------------------

    public TenantContext staffAContext() {
        return contextFor(staffA, orgA, UserRole.EMPLOYEE, Set.of(projectA1));
    }

    public TenantContext adminAContext() {
        return contextFor(adminA, orgA, UserRole.ADMIN, Set.of(projectA1, projectA2));
    }

    public TenantContext outsiderAContext() {
        return contextFor(outsiderA, orgA, UserRole.EMPLOYEE, Set.of());
    }

    public TenantContext adminBContext() {
        return contextFor(adminB, orgB, UserRole.ADMIN, Set.of(projectB1));
    }

    public TenantContext contextFor(UUID userId, UUID org, UserRole role, Set<UUID> projects) {
        return TenantContext.forUser(UUID.randomUUID(), userId, org, role, projects);
    }

    /** A connection for the mock simulator gate. */
    public void mockChannelFor(UUID project) {
        insertChannelConnection(orgA, project, "MOCK", true);
    }

    /** The five ACTIVE-status constants routing and workload count as active. */
    public static final List<String> ACTIVE_STATUSES = List.of("OPEN", "PENDING");

    public static Map<String, Object> noParams() {
        return Map.of();
    }
}