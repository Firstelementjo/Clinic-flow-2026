package com.firstelement.channels;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The channels fixture: two tenants, real Flyway, a real conversations/messages schema.
 *
 * <h2>Why it seeds conversations and customers</h2>
 *
 * <p>The claims this phase has to prove are about what happens to a message row, so the fixture has
 * to be able to create one: an outbound {@code PENDING} row with a conversation behind it, an
 * inbound row with a provider id, and a customer identity on the channel.
 */
@Component
public class ChannelsFixture {

    public static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16"))
            .withDatabaseName("firstelement_channels")
            .withUsername("firstelement")
            .withPassword("firstelement");

    public static final UUID ORG_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    public static final UUID ORG_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    public static final UUID PROJECT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000a1");
    public static final UUID PROJECT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000000b1");

    public static final UUID USER_A = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000e1");
    public static final UUID USER_B = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000000e2");

    public static final UUID EMPLOYEE_A = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000ee01");
    public static final UUID CUSTOMER_A = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000cc01");
    public static final UUID CUSTOMER_B = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000cc02");

    public static final LocalDateTime SEED_AT = LocalDateTime.of(2026, 8, 14, 9, 30, 0);

    private final NamedParameterJdbcTemplate jdbc;

    public ChannelsFixture(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Truncates and re-seeds. Child tables first, in foreign-key order. */
    public void reset() {
        for (String table : new String[] {"conversation_events", "messages", "conversations",
            "customer_identities", "customers", "project_memberships", "sessions", "audit_logs",
            "employees", "users", "channel_connections", "projects", "job_dead_letters",
            "job_attempts", "idempotency_records", "outbox_events", "organizations"}) {
            jdbc.update("delete from " + table, new MapSqlParameterSource());
        }

        org(ORG_A, "Alpha Clinic", "alpha-clinic");
        org(ORG_B, "Beta Clinic", "beta-clinic");
        project(PROJECT_A, ORG_A, "Alpha One", "alpha-one");
        project(PROJECT_B, ORG_B, "Beta One", "beta-one");
        user(USER_A, ORG_A, "staff@a.test", UserRoleValue.EMPLOYEE);
        user(USER_B, ORG_B, "admin@b.test", UserRoleValue.ADMIN);
        employee(EMPLOYEE_A, ORG_A, USER_A);
        customer(CUSTOMER_A, ORG_A, PROJECT_A, "#33000001", "+966500000001");
        customer(CUSTOMER_B, ORG_B, PROJECT_B, "#33000002", "+966500000002");
    }

    /** Keeps the role spellings in one place so a typo cannot silently widen a grant. */
    public static final class UserRoleValue {
        private UserRoleValue() {
        }

        public static final String EMPLOYEE = "EMPLOYEE";
        public static final String ADMIN = "ADMIN";
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
                .addValue("email", email).addValue("name", "Seeded User").addValue("role", role));
    }

    private void employee(UUID id, UUID org, UUID userId) {
        at(SEED_AT, """
            insert into employees ("id", "organizationId", "userId", "availabilityStatus",
                                   "statusChangedAt", "maxConcurrentConversations",
                                   "activeConversationCount", "createdAt", "updatedAt")
            values (:id, :org, :user, cast('ONLINE' as "EmployeeAvailabilityStatus"), :now,
                    5, 0, :now, :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("org", org)
                .addValue("user", userId));
    }

    private void customer(UUID id, UUID org, UUID projectId, String cxId, String phone) {
        at(SEED_AT, """
            insert into customers ("id", "organizationId", "projectId", "cxId", "displayName",
                                   "primaryPhone", "firstSeenAt", "lastSeenAt", "createdAt",
                                   "updatedAt")
            values (:id, :org, :project, :cx, :name, :phone, :now, :now, :now, :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("org", org)
                .addValue("project", projectId).addValue("cx", cxId)
                .addValue("name", "Seeded Customer").addValue("phone", phone));
    }

    /** A conversation, returned by id so a test can assert against it. Always HUMAN mode. */
    public UUID conversation(UUID org, UUID projectId, UUID customerId, String channel,
                             String externalUserId) {
        return conversation(org, projectId, customerId, channel, externalUserId, "HUMAN");
    }

    /**
     * A conversation in a named mode.
     *
     * <p>{@code mode} is a parameter rather than a post-hoc UPDATE because the mode is the value the
     * AI trigger reads: a conversation seeded HUMAN and flipped to AI afterwards would not prove
     * that a HUMAN conversation enqueues nothing, only that one did before it was flipped.
     */
    public UUID conversation(UUID org, UUID projectId, UUID customerId, String channel,
                             String externalUserId, String mode) {
        UUID id = UUID.randomUUID();
        LocalDateTime now = com.firstelement.worker.config.UtcTime.now();
        jdbc.update("""
            insert into conversations ("id", "organizationId", "projectId", "customerId",
                "ticketNumber", "channel", "status", "mode", "priority", "messageCount",
                "lastMessageAt", "createdAt", "updatedAt")
            values (:id, :org, :project, :customer, :ticket, cast(:channel as "ChannelType"),
                    cast('OPEN' as "ConversationStatus"), cast(:mode as "ConversationMode"),
                    cast('NORMAL' as "ConversationPriority"), 0, :now, :now, :now)""",
            new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("org", org)
                .addValue("project", projectId)
                .addValue("customer", customerId)
                .addValue("ticket", "#T" + Math.abs(id.hashCode()))
                .addValue("channel", channel)
                .addValue("mode", mode)
                .addValue("now", now));
        identity(org, projectId, customerId, channel, externalUserId);
        return id;
    }

    /** A customer identity on a channel: the handle the outbound path must resolve. */
    public UUID identity(UUID org, UUID projectId, UUID customerId, String channel,
                         String externalId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            insert into customer_identities ("id", "organizationId", "projectId", "customerId",
                "platform", "externalId", "displayName", "isVerified", "createdAt", "updatedAt")
            values (:id, :org, :project, :customer, cast(:platform as "ChannelType"), :external,
                    'Seeded Customer', true, :now, :now)""",
            new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("org", org)
                .addValue("project", projectId)
                .addValue("customer", customerId)
                .addValue("platform", channel)
                .addValue("external", externalId)
                .addValue("now", com.firstelement.worker.config.UtcTime.now()));
        return id;
    }

    /** An enabled channel connection. Returns its id. */
    public UUID connection(UUID org, UUID projectId, String channel, String credentials) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            insert into channel_connections ("id", "organizationId", "projectId", "channel",
                "isEnabled", "status", "credentials", "webhookSecret", "createdAt", "updatedAt")
            values (:id, :org, :project, cast(:channel as "ChannelType"), true,
                    cast('CONNECTED' as "ChannelConnectionStatus"), :credentials, :secret,
                    :now, :now)""",
            new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("org", org)
                .addValue("project", projectId)
                .addValue("channel", channel)
                .addValue("credentials", credentials)
                .addValue("secret", "test-webhook-secret")
                .addValue("now", com.firstelement.worker.config.UtcTime.now()));
        return id;
    }

    /** A {@code PENDING} outbound message, which is what the dispatcher acts on. */
    public UUID pendingOutbound(UUID org, UUID projectId, UUID conversationId, UUID customerId,
                                String channel, String body, String idempotencyKey) {
        UUID id = UUID.randomUUID();
        LocalDateTime now = com.firstelement.worker.config.UtcTime.now();
        jdbc.update("""
            insert into messages ("id", "organizationId", "projectId", "conversationId", "channel",
                "direction", "authorType", "body", "deliveryStatus", "metadata", "sentAt",
                "createdAt")
            values (:id, :org, :project, :conversation, cast(:channel as "ChannelType"),
                    cast('OUTBOUND' as "MessageDirection"), cast('EMPLOYEE' as "MessageAuthorType"),
                    :body, cast('PENDING' as "MessageDeliveryStatus"), CAST(:metadata AS jsonb),
                    :now, :now)""",
            new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("org", org)
                .addValue("project", projectId)
                .addValue("conversation", conversationId)
                .addValue("channel", channel)
                .addValue("body", body)
                .addValue("metadata", idempotencyKey == null ? "{}"
                    : "{\"idempotencyKey\":\"" + idempotencyKey + "\"}")
                .addValue("now", now));
        return id;
    }

    /** An inbound message with a provider id — the row the unique index protects. */
    public UUID inboundMessage(UUID org, UUID projectId, UUID conversationId, String channel,
                               String externalMessageId, String body) {
        UUID id = UUID.randomUUID();
        LocalDateTime now = com.firstelement.worker.config.UtcTime.now();
        jdbc.update("""
            insert into messages ("id", "organizationId", "projectId", "conversationId", "channel",
                "direction", "authorType", "body", "externalMessageId", "deliveryStatus",
                "sentAt", "createdAt")
            values (:id, :org, :project, :conversation, cast(:channel as "ChannelType"),
                    cast('INBOUND' as "MessageDirection"), cast('CUSTOMER' as "MessageAuthorType"),
                    :body, :external, cast('DELIVERED' as "MessageDeliveryStatus"), :now, :now)""",
            new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("org", org)
                .addValue("project", projectId)
                .addValue("conversation", conversationId)
                .addValue("channel", channel)
                .addValue("body", body)
                .addValue("external", externalMessageId)
                .addValue("now", now));
        return id;
    }

    // ------------------------------------------------------------------

    public String deliveryStatusOf(UUID messageId) {
        return jdbc.queryForObject(
            "select \"deliveryStatus\"::text from messages where \"id\" = :id",
            new MapSqlParameterSource().addValue("id", messageId), String.class);
    }

    public String providerMessageIdOf(UUID messageId) {
        return jdbc.queryForObject(
            "select \"externalMessageId\" from messages where \"id\" = :id",
            new MapSqlParameterSource().addValue("id", messageId), String.class);
    }

    public String failureReasonOf(UUID messageId) {
        return jdbc.queryForObject("select \"failureReason\" from messages where \"id\" = :id",
            new MapSqlParameterSource().addValue("id", messageId), String.class);
    }

    public String metadataOf(UUID messageId) {
        return jdbc.queryForObject(
            "select \"metadata\"::text from messages where \"id\" = :id",
            new MapSqlParameterSource().addValue("id", messageId), String.class);
    }

    public String lastErrorOf(UUID connectionId) {
        return jdbc.queryForObject(
            "select \"lastError\" from channel_connections where \"id\" = :id",
            new MapSqlParameterSource().addValue("id", connectionId), String.class);
    }

    public Integer count(String sql) {
        return jdbc.queryForObject(sql, new MapSqlParameterSource(), Integer.class);
    }

    // ------------------------------------------------------------------
    // the queue's own state, so a fixture can assert the dispatcher's decisions
    // ------------------------------------------------------------------

    public String stateOf(UUID outboxEventId) {
        return jdbc.queryForObject("select \"state\"::text from outbox_events where \"id\" = :id",
            new MapSqlParameterSource().addValue("id", outboxEventId), String.class);
    }

    public Integer attemptsOf(UUID outboxEventId) {
        return jdbc.queryForObject("select attempts from outbox_events where \"id\" = :id",
            new MapSqlParameterSource().addValue("id", outboxEventId), Integer.class);
    }

    /** Ages a lease into the past: the row a worker that stopped answering leaves behind. */
    public void expireLease(UUID outboxEventId) {
        jdbc.update("""
            update outbox_events
               set "leaseUntil" = (now() AT TIME ZONE 'UTC') - interval '1 minute',
                   "updatedAt"   = (now() AT TIME ZONE 'UTC')
             where "id" = :id""",
            new MapSqlParameterSource().addValue("id", outboxEventId));
    }

    /** One dead-letter row, asserted without naming a tenant's data in the assertion. */
    public Integer deadLetterCount() {
        return count("select count(*) from job_dead_letters");
    }

    /** The recorded outcomes for one tenant's queue, oldest attempt first. */
    public java.util.List<String> attemptOutcomes(UUID organizationId, String queueName) {
        return jdbc.queryForList(
            "select outcome::text from job_attempts"
                + " where \"organizationId\" = :org and \"queueName\" = :queue"
                + " order by \"attemptNumber\"",
            new MapSqlParameterSource().addValue("org", organizationId)
                .addValue("queue", queueName), String.class);
    }

    public NamedParameterJdbcTemplate jdbc() {
        return jdbc;
    }

    public Map<String, Object> noop() {
        return Map.of();
    }

    private void at(LocalDateTime now, String sql, MapSqlParameterSource parameters) {
        jdbc.update(sql, parameters.addValue("now", now));
    }
}