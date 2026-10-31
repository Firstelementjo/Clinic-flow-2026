package com.firstelement.observ;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The operations-observability fixture: two tenants and one row of every kind the diagnosis read
 * models claim to show.
 *
 * <h2>What is here and why</h2>
 *
 * <p>Not decoration. Every seeded row makes a specific claim falsifiable:
 *
 * <ul>
 *   <li>a <b>dead-lettered outbox event with a payload and attempt messages</b>, so the "the payload
 *       and the error messages are not projected" assertion has real content to be absent from — a
 *       reader tested only against empty columns proves nothing;</li>
 *   <li>a <b>stranded lease</b>, so "a LEASED row whose lease has expired" is a real row rather
 *       than a definition;</li>
 *   <li>a <b>connection with a credential</b> and a webhook-secret, so the connector panel is tested
 *       against the case that matters;</li>
 *   <li>a <b>READY document with unembedded chunks</b>, which is the failure mode that matters:
 *       indexed for the operator, invisible to retrieval;</li>
 *   <li>a <b>knowledge gap</b>, so "the query text is not projected" is falsifiable;</li>
 *   <li>a <b>workflow with an active version</b> and one <b>with none</b>, because
 *       {@code activeVersionId = null} is a legitimate state and not a defect.</li>
 * </ul>
 *
 * <h2>Two tenants, always</h2>
 *
 * <p>Every acceptance gate has a cross-tenant negative, so the fixture cannot be one tenant. A second
 * tenant also makes "the same id in two tenants is a different thing" testable, which is the shape
 * of the {@code NOT_FOUND} guarantees the read models make.
 *
 * <h2>No skip path</h2>
 *
 * <p>{@link #POSTGRES} is started by the test base and every property supplier starts it first, so a
 * Docker problem fails the build rather than turning an operations suite green.
 */
@Component
public class ObservFixture {

    public static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16"))
            .withDatabaseName("firstelement_observ")
            .withUsername("firstelement")
            .withPassword("firstelement");

    public static final UUID ORG_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    public static final UUID ORG_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    public static final UUID PROJECT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000a1");
    public static final UUID PROJECT_A2 = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000a2");
    public static final UUID PROJECT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000000b1");

    public static final UUID ADMIN_A = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000e1");
    public static final UUID EMPLOYEE_A = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000e2");
    public static final UUID ADMIN_B = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000000e4");

    public static final UUID KNOWLEDGE_BASE_A =
        UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000b1");
    public static final UUID KNOWLEDGE_BASE_B =
        UUID.fromString("bbbbbbbb-0000-0000-0000-0000000000b2");

    /** A fixed past instant, so "three hours ago" is reproducible. */
    public static final LocalDateTime SEED_AT = LocalDateTime.of(2026, 8, 14, 9, 30, 0);

    /**
     * The strings that must never appear in an operations read model.
     *
     * <p>Seeded into the outbox payload, the attempt error message and the knowledge-gap query. Each
     * is the kind of content those three columns exist to hold, and each is asserted absent from the
     * corresponding projection.
     */
    public static final String PAYLOAD_MARKER = "customer-prose-marker-must-not-be-projected";
    public static final String ERROR_MESSAGE_MARKER = "provider-error-prose-must-not-be-projected";
    public static final String GAP_QUERY_MARKER = "typed-by-a-customer-must-not-be-projected";
    public static final String CREDENTIAL_MARKER = "super-secret-auth-token";

    /** Truncated in child-to-parent order. */
    private static final String[] TABLES = {
        "workflow_run_actions", "workflow_runs", "workflow_versions", "workflow_definitions",
        "document_chunks", "documents", "knowledge_gaps", "knowledge_bases",
        "ai_tool_executions", "job_integration_events", "security_events", "admin_audit_events",
        "job_dead_letters", "job_attempts", "outbox_events",
        "channel_connections", "notifications", "sessions", "audit_logs",
        "project_memberships", "users", "projects", "organizations",
    };

    private final NamedParameterJdbcTemplate jdbc;

    public ObservFixture(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The UTC clock the fixture and the tests agree on. */
    public static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    /** Truncates and re-seeds. Children first, parents last. */
    public void reset() {
        for (String table : TABLES) {
            jdbc.update("delete from " + table, new MapSqlParameterSource());
        }

        org(ORG_A, "Alpha Clinic", "alpha-clinic");
        org(ORG_B, "Beta Clinic", "beta-clinic");
        project(PROJECT_A, ORG_A, "Alpha One", "alpha-one");
        project(PROJECT_A2, ORG_A, "Alpha Two", "alpha-two");
        project(PROJECT_B, ORG_B, "Beta One", "beta-one");

        user(ADMIN_A, ORG_A, "admin@a.test", "ADMIN");
        user(EMPLOYEE_A, ORG_A, "staff@a.test", "EMPLOYEE");
        user(ADMIN_B, ORG_B, "admin@b.test", "ADMIN");

        membership(ADMIN_A, PROJECT_A);
        membership(ADMIN_A, PROJECT_A2);
        membership(EMPLOYEE_A, PROJECT_A);
        membership(ADMIN_B, PROJECT_B);

        knowledgeBase(KNOWLEDGE_BASE_A, ORG_A, PROJECT_A, "Alpha Knowledge");
        knowledgeBase(KNOWLEDGE_BASE_B, ORG_B, PROJECT_B, "Beta Knowledge");
    }

    // ------------------------------------------------------------------
    // seeders
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

    private void user(UUID id, UUID org, String email, String role) {
        at(SEED_AT, """
            insert into users ("id", "organizationId", "email", "passwordHash", "fullName", "role",
                               "locale", "isActive", "createdAt", "updatedAt")
            values (:id, :org, :email, 'not-a-real-hash', 'Seeded User', cast(:role as "UserRole"),
                    cast('ar' as "Locale"), true, :now, :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("org", org)
                .addValue("email", email).addValue("role", role));
    }

    private void membership(UUID userId, UUID projectId) {
        jdbc.update("""
            insert into project_memberships ("id", "organizationId", "projectId", "userId", "createdAt")
            values (:id, :org, :project, :user, :now)
            """, new MapSqlParameterSource().addValue("id", UUID.randomUUID())
                .addValue("org", orgOf(userId)).addValue("project", projectId)
                .addValue("user", userId).addValue("now", SEED_AT));
    }

    private void knowledgeBase(UUID id, UUID org, UUID projectId, String name) {
        at(SEED_AT, """
            insert into knowledge_bases ("id", "organizationId", "projectId", "name", "isDefault",
                "createdAt", "updatedAt")
            values (:id, :org, :project, :name, true, :now, :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("org", org)
                .addValue("project", projectId).addValue("name", name));
    }

    // ------------------------------------------------------------------
    // outbox / job history
    // ------------------------------------------------------------------

    /**
     * A dead-lettered outbox event, with a payload and attempt error messages carrying markers.
     *
     * <p>The markers are the whole point. A read model that never selected {@code payload} would
     * pass an assertion against a {@code payload} of {@code '{}'}, so the column is seeded with
     * something that must be absent.
     */
    public UUID deadOutboxEvent(UUID org, UUID projectId, String queue, int attempts,
                                String deadLetterReason) {
        UUID eventId = UUID.randomUUID();
        at(now(), """
            insert into outbox_events ("id", "organizationId", "projectId", "aggregateType",
                "aggregateId", "eventType", "payload", "idempotencyKey", "state", attempts,
                "correlationId", "nextAttemptAt", "deadLetteredAt", "deadLetterReason",
                "lastError", "createdAt", "updatedAt")
            values (:id, :org, :project, 'Seeded', gen_random_uuid(), :queue,
                    cast(:payload as jsonb), :key, 'DEAD', :attempts, :correlation, :now, :now,
                    :reason, 'LAST_ERROR_CODE', :now, :now)""",
            new MapSqlParameterSource().addValue("id", eventId).addValue("org", org)
                .addValue("project", projectId).addValue("queue", queue)
                .addValue("payload", "{\"text\":\"" + PAYLOAD_MARKER + "\"}")
                .addValue("key", "seed:" + eventId)
                .addValue("attempts", attempts).addValue("correlation", "corr-" + eventId)
                .addValue("reason", deadLetterReason));

        // The last attempt is non-retryable, which is why the event is DEAD at all; the earlier ones
        // are the retryable kind, so the history shows a job that was retried and then gave up.
        for (int attempt = 1; attempt <= Math.min(attempts, 3); attempt++) {
            attempt(org, projectId, queue, eventId, attempt,
                attempt == attempts ? "NON_RETRYABLE_FAILURE" : "RETRYABLE_FAILURE");
        }

        deadLetter(org, projectId, queue, eventId, deadLetterReason, attempts);
        integrationEvent(org, projectId, "JOB", "JOB_FAILED", "FAILED", queue,
            "PROVIDER_REJECTED", "corr-" + eventId, eventId);
        return eventId;
    }

    /** A stranded lease: a LEASED row whose lease has expired, i.e. a worker that died. */
    public UUID strandedLease(UUID org, UUID projectId, String queue, String workerId,
                              LocalDateTime leaseUntil) {
        UUID id = UUID.randomUUID();
        at(now(), """
            insert into outbox_events ("id", "organizationId", "projectId", "aggregateType",
                "aggregateId", "eventType", "payload", "idempotencyKey", "state", attempts,
                "leaseOwner", "leaseUntil", "correlationId", "nextAttemptAt", "createdAt", "updatedAt")
            values (:id, :org, :project, 'Seeded', gen_random_uuid(), :queue,
                    cast('{"seeded":true}' as jsonb), :key, 'LEASED', 1, :worker, :leaseUntil,
                    :now, :now, :now, :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("org", org)
                .addValue("project", projectId).addValue("queue", queue)
                .addValue("key", "stranded:" + id).addValue("worker", workerId)
                .addValue("leaseUntil", leaseUntil));
        return id;
    }

    private void attempt(UUID org, UUID projectId, String queue, UUID eventId, int attemptNumber,
                         String outcome) {
        at(now(), """
            insert into job_attempts ("id", "organizationId", "projectId", "queueName", "jobType",
                "jobId", "attemptNumber", "outcome", "retryClass", "errorCode", "errorMessage",
                "startedAt", "finishedAt", "durationMs", "workerId", "outboxEventId", "createdAt")
            values (:id, :org, :project, :queue, :queue, :jobId, :attemptNumber,
                    cast(:outcome as "JobAttemptOutcome"), 'PERMANENT', 'SEEDED_FAILURE',
                    :errorMessage, :now, :now, 12, 'fe-worker@seed', :eventId, :now)""",
            new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("org", org)
                .addValue("project", projectId).addValue("queue", queue)
                .addValue("jobId", eventId.toString()).addValue("attemptNumber", attemptNumber)
                // A JobAttemptOutcome, not a retry class: the retry decision lives in retryClass, and the
            // attempt's own outcome is what the diagnosis reports.
                .addValue("outcome", outcome)
                .addValue("errorMessage", ERROR_MESSAGE_MARKER + " attempt " + attemptNumber)
                .addValue("eventId", eventId));
    }

    private void deadLetter(UUID org, UUID projectId, String queue, UUID eventId, String reason,
                            int attempts) {
        at(now(), """
            insert into job_dead_letters ("id", "organizationId", "projectId", "queueName", "jobId",
                "outboxEventId", "reason", "payloadSummary", "attempts", "status", "createdAt",
                "updatedAt")
            values (:id, :org, :project, :queue, :jobId, :eventId, :reason,
                    cast(:summary as jsonb), :attempts, cast('OPEN' as "DeadLetterStatus"), :now,
                    :now)""",
            new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("org", org)
                .addValue("project", projectId).addValue("queue", queue)
                .addValue("jobId", eventId.toString()).addValue("eventId", eventId)
                // The dispatcher builds a summary from counts, queue and error class, never from the
                // payload. Seeded that way so the test asserts the shape, not the accident.
                .addValue("summary", "{\"fields\":3,\"bytes\":128}")
                .addValue("reason", reason).addValue("attempts", attempts));
    }

    /** One row on the job/integration stream. */
    public UUID integrationEvent(UUID org, UUID projectId, String componentType, String eventType,
                                 String outcome, String queue, String failureCode,
                                 String correlationId, UUID outboxEventId) {
        UUID id = UUID.randomUUID();
        at(now(), """
            insert into job_integration_events ("id", "organizationId", "projectId", "component",
                "componentType", "eventType", "outcome", "queueName", "jobId", "outboxEventId",
                "correlationId", "detail", "failureCode", "retentionClass", "occurredAt")
            values (:id, :org, :project, 'OutboxEvent', cast(:componentType as "AuditPrincipalType"),
                    :eventType, cast(:outcome as "AuditOutcome"), :queue, :jobId, :eventId,
                    :correlation, 'seeded detail', :failure, 'JOB_PROVENANCE', :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("org", org)
                .addValue("project", projectId).addValue("componentType", componentType)
                .addValue("eventType", eventType).addValue("outcome", outcome)
                .addValue("queue", queue)
                .addValue("jobId", outboxEventId == null ? null : outboxEventId.toString())
                .addValue("eventId", outboxEventId).addValue("correlation", correlationId)
                .addValue("failure", failureCode));
        return id;
    }

    // ------------------------------------------------------------------
    // connectors
    // ------------------------------------------------------------------

    /** A connection whose credential and webhook secret must never be projected. */
    public UUID connection(UUID org, UUID projectId, String channel, String credentials,
                           String webhookSecret, String status, String lastError) {
        UUID id = UUID.randomUUID();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("id", id).addValue("org", org).addValue("project", projectId)
            .addValue("channel", channel).addValue("credentials", credentials)
            .addValue("webhookSecret", webhookSecret).addValue("status", status)
            .addValue("lastError", lastError).addValue("now", now());
        jdbc.update("""
            insert into channel_connections ("id", "organizationId", "projectId", "channel",
                "displayName", "isEnabled", "status", "credentials", "webhookSecret", "lastError",
                "lastInboundAt", "lastOutboundAt", "createdAt", "updatedAt")
            values (:id, :org, :project, cast(:channel as "ChannelType"), 'Seeded connection', true,
                    cast(:status as "ChannelConnectionStatus"), :credentials, :webhookSecret,
                    :lastError, :now, :now, :now, :now)
            """, parameters);
        return id;
    }

    /** An outbound message with a delivery outcome, which is where the delivery counters come from. */
    public UUID outboundMessage(UUID org, UUID projectId, String channel, String deliveryStatus,
                                String failureReason) {
        UUID id = UUID.randomUUID();
        at(now(), """
            insert into messages ("id", "organizationId", "projectId", "conversationId", "channel",
                "direction", "authorType", "body", "deliveryStatus", "failureReason", "sentAt",
                "createdAt")
            values (:id, :org, :project, gen_random_uuid(), cast(:channel as "ChannelType"),
                    cast('OUTBOUND' as "MessageDirection"), cast('SYSTEM' as "MessageAuthorType"),
                    :body, cast(:deliveryStatus as "MessageDeliveryStatus"), :failure, :now, :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("org", org)
                .addValue("project", projectId).addValue("channel", channel)
                .addValue("deliveryStatus", deliveryStatus).addValue("failure", failureReason)
                .addValue("body", "seeded outbound body"));
        return id;
    }

    // ------------------------------------------------------------------
    // knowledge
    // ------------------------------------------------------------------

    /** A document with an explicit chunk/embedding profile. */
    public UUID document(UUID org, UUID projectId, UUID knowledgeBaseId, String title,
                         String status, int chunkCount, int actualChunks, int unembeddedChunks,
                         LocalDateTime updatedAt) {
        UUID id = UUID.randomUUID();
        at(updatedAt, """
            insert into documents ("id", "organizationId", "projectId", "knowledgeBaseId", "title",
                "sourceType", "status", "failureReason", "chunkCount", "processedAt", "createdAt",
                "updatedAt")
            values (:id, :org, :project, :kb, :title, cast('FILE' as "DocumentSourceType"),
                    cast(:status as "DocumentStatus"), :failure, :chunkCount, :processed, :now,
                    :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("org", org)
                .addValue("project", projectId).addValue("kb", knowledgeBaseId)
                .addValue("title", title).addValue("status", status)
                .addValue("failure", "FAILED".equals(status) ? "seeded failure" : null)
                .addValue("chunkCount", chunkCount)
                .addValue("processed", "READY".equals(status) ? updatedAt : null));

        for (int index = 0; index < actualChunks; index++) {
            MapSqlParameterSource chunkParameters = new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID()).addValue("org", org).addValue("project", projectId)
                .addValue("kb", knowledgeBaseId).addValue("documentId", id).addValue("index", index)
                .addValue("actual", actualChunks).addValue("unembedded", unembeddedChunks)
                .addValue("now", updatedAt);
            jdbc.update("""
                insert into document_chunks ("id", "organizationId", "projectId", "knowledgeBaseId",
                    "documentId", "chunkIndex", "content", "tokenCount", "embedding", "createdAt")
                values (:id, :org, :project, :kb, :documentId, :index, 'seeded chunk text', 12,
                        -- array_fill's second argument is an int[]; ARRAY[1536] rather than the
                        -- bare integer, which PostgreSQL will not implicitly cast.
                        case when :index < (:actual - :unembedded)
                             then (select e from (select
                                ('[' || array_to_string(
                                    array_fill(0::real, ARRAY[1536]), ',') || ']')::vector
                                  as e) seed)
                             else null end,
                        :now)
                """, chunkParameters);
        }
        return id;
    }

    /** A knowledge gap whose query text is the customer's, and must not be projected. */
    public UUID knowledgeGap(UUID org, UUID projectId, double bestScore, int retrievedCount,
                             LocalDateTime createdAt) {
        UUID id = UUID.randomUUID();
        at(createdAt, """
            insert into knowledge_gaps ("id", "organizationId", "projectId", "query", "bestScore",
                "retrievedCount", "createdAt")
            values (:id, :org, :project, :query, :bestScore, :retrieved, :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("org", org)
                .addValue("project", projectId).addValue("query", GAP_QUERY_MARKER)
                .addValue("bestScore", bestScore).addValue("retrieved", retrievedCount));
        return id;
    }

    // ------------------------------------------------------------------
    // workflows
    // ------------------------------------------------------------------

    /**
     * A workflow definition, and optionally an activated version.
     *
     * <p>{@code withActiveVersion = false} is a first-class case: {@code activeVersionId} NULL is the
     * documented "still being authored" state, and a panel that treated it as a fault would be
     * wrong.
     */
    public UUID workflow(UUID org, UUID projectId, String key, boolean withActiveVersion) {
        UUID definitionId = UUID.randomUUID();
        at(now(), """
            insert into workflow_definitions ("id", "organizationId", "projectId", "key", "name",
                "triggerType", "priority", "isEnabled", "latestVersionNumber", "createdByUserId",
                "createdAt", "updatedAt")
            values (:id, :org, :project, :key, 'Seeded workflow', 'CONVERSATION_CREATED', 100, true,
                    :latest, :by, :now, :now)""",
            new MapSqlParameterSource().addValue("id", definitionId).addValue("org", org)
                .addValue("project", projectId).addValue("key", key)
                .addValue("latest", withActiveVersion ? 2 : 0)
                .addValue("by", adminOf(org)));

        if (!withActiveVersion) {
            return definitionId;
        }

        UUID versionId = UUID.randomUUID();
        at(now(), """
            insert into workflow_versions ("id", "organizationId", "projectId", "workflowDefinitionId",
                "versionNumber", "state", "stateMachine", "triggerConfig", "capabilityScope",
                "contentHash", "activatedAt", "activatedByUserId", "createdByUserId", "changeNote",
                "createdAt", "updatedAt")
            values (:id, :org, :project, :definition, 2, 'ACTIVE', cast(:machine as jsonb),
                    '{}'::jsonb, cast('["conversation:read"]' as jsonb), :hash, :now, :by, :by,
                    'second version', :now, :now)""",
            new MapSqlParameterSource().addValue("id", versionId).addValue("org", org)
                .addValue("project", projectId).addValue("definition", definitionId)
                .addValue("machine", "{\"states\":{\"INIT\":{\"on\":\"DONE\",\"to\":\"DONE\"}}}")
                .addValue("hash", "content-hash-abcdef0123456789")
                .addValue("by", adminOf(org)));

        jdbc.update("""
            update workflow_definitions set "activeVersionId" = :version, "updatedAt" = :now
             where "id" = :id
            """, new MapSqlParameterSource().addValue("version", versionId)
                .addValue("now", now()).addValue("id", definitionId));
        return definitionId;
    }

    /** The id of the open dead letter for an outbox event, for a test that resolves it. */
    public UUID deadLetterId(UUID outboxEventId) {
        return jdbc.queryForObject(
            "select d.\"id\" from job_dead_letters d where d.\"outboxEventId\" = :eventId",
            new MapSqlParameterSource().addValue("eventId", outboxEventId), UUID.class);
    }

    /** The ADMIN user of an organization, for a row the schema ties to a tenant. */
    private UUID adminOf(UUID organizationId) {
        return jdbc.queryForObject(
            "select u.\"id\" from users u where u.\"organizationId\" = :org"
                + " and u.\"role\" = cast('ADMIN' as \"UserRole\") limit 1",
            new MapSqlParameterSource().addValue("org", organizationId), UUID.class);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private UUID orgOf(UUID userId) {
        return jdbc.queryForObject(
            "select u.\"organizationId\" from users u where u.\"id\" = :id",
            new MapSqlParameterSource().addValue("id", userId), UUID.class);
    }

    private void at(LocalDateTime when, String sql, MapSqlParameterSource parameters) {
        parameters.addValue("now", when);
        jdbc.update(sql, parameters);
    }

    /** Counts rows in a table, tenant-scoped or not. Test-only convenience. */
    public int count(String sql, Map<String, Object> parameters) {
        // Wrapped so a caller can write the natural predicate (`select 1 from t where ...`) and get
        // 0 when nothing matches: queryForObject throws EmptyResultDataAccessException on a zero-row
        // result, which would turn "this removed nothing" into an error instead of a pass.
        Integer count = jdbc.queryForObject("select count(*) from (" + sql + ") fe_counted",
            new MapSqlParameterSource(parameters), Integer.class);
        return count == null ? 0 : count;
    }

    /** The number of rows in a table for one tenant. */
    public int tenantRows(String table, UUID organizationId) {
        // Deliberately not routed through count(...): that wraps its SQL to tolerate a zero-row
        // predicate result, and wrapping an aggregate that always returns exactly one row would
        // report 1 forever.
        Integer rows = jdbc.queryForObject(
            "select count(*) from " + table + " where \"organizationId\" = :org",
            new MapSqlParameterSource().addValue("org", organizationId), Integer.class);
        return rows == null ? 0 : rows;
    }

    /** Counts rows matching a predicate, for a state assertion after an action. */
    public int countWhere(String sql, Map<String, Object> parameters) {
        return count(sql, parameters);
    }
}
