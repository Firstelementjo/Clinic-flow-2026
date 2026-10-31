package com.firstelement.admin;

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
 * The Admin fixture: two tenants, the real Flyway chain, and one row of every kind the Admin read
 * models claim to show.
 *
 * <h2>Two tenants, always</h2>
 *
 * <p>Every Phase-7 acceptance gate has a cross-tenant negative, so the fixture cannot be one tenant.
 * A second tenant also makes "the same subject id in two tenants is a different thing" testable for
 * holds, deletion requests, audit rows and AI configuration.
 *
 * <h2>What is seeded, and why each row exists</h2>
 *
 * <p>Not decoration. Every seeded row is there so that a specific claim is falsifiable: a legal hold
 * so the purge gate has something to refuse, a disabled AI tool so "a disabled tool is visible"
 * has a reason column with a value in it, a DENIED tool execution so "a denied AI action is visible
 * without customer content" has something to deny, a privacy-deletion request so the outstanding
 * queue is non-empty, and an export job so the "completed but the object outlived its window" count
 * can be non-zero.
 *
 * <h2>No skip path</h2>
 *
 * <p>{@link #POSTGRES} is started by the test base and every property supplier starts it first, so a
 * Docker problem fails the build rather than turning an Admin suite green.
 */
@Component
public class AdminFixture {

    public static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16"))
            .withDatabaseName("firstelement_admin")
            .withUsername("firstelement")
            .withPassword("firstelement");

    public static final UUID ORG_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    public static final UUID ORG_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    public static final UUID PROJECT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000a1");
    public static final UUID PROJECT_A2 = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000a2");
    public static final UUID PROJECT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000000b1");

    public static final UUID ADMIN_A = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000e1");
    public static final UUID EMPLOYEE_USER_A = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000e2");
    public static final UUID SUBJECT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000e3");
    public static final UUID ADMIN_B = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000000e4");
    public static final UUID SUBJECT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000000e5");

    public static final UUID KNOWLEDGE_BASE_A =
        UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000b1");

    /** A fixed past instant, so "created two days ago" is reproducible. */
    public static final LocalDateTime SEED_AT =
        LocalDateTime.of(2026, 8, 14, 9, 30, 0);

    /** Tables truncated in child-to-parent order. */
    private static final String[] TABLES = {
        "privacy_deletion_targets", "privacy_deletion_requests",
        "legal_hold_events", "legal_holds", "retention_classes",
        "export_deletion_events", "export_download_events", "export_job_attempts", "export_jobs",
        "ai_tool_executions", "ai_approvals", "ai_redaction_records", "ai_budget_consumptions",
        "ai_budgets", "ai_tool_registry", "ai_model_registry", "ai_policy_versions", "ai_policies",
        "job_integration_events", "security_events", "admin_audit_events",
        "job_dead_letters", "job_attempts", "idempotency_records", "outbox_events",
        "workflow_run_actions", "workflow_runs", "workflow_versions", "workflow_definitions",
        "document_chunks", "documents", "knowledge_gaps", "knowledge_bases",
        "notifications", "sessions", "audit_logs",
        "project_memberships", "channel_connections", "users", "projects", "organizations",
    };

    private final NamedParameterJdbcTemplate jdbc;

    public AdminFixture(NamedParameterJdbcTemplate jdbc) {
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
        user(EMPLOYEE_USER_A, ORG_A, "staff@a.test", "EMPLOYEE");
        user(SUBJECT_A, ORG_A, "subject@a.test", "EMPLOYEE");
        user(ADMIN_B, ORG_B, "admin@b.test", "ADMIN");
        user(SUBJECT_B, ORG_B, "subject@b.test", "EMPLOYEE");

        membership(ADMIN_A, PROJECT_A);
        membership(ADMIN_A, PROJECT_A2);
        membership(EMPLOYEE_USER_A, PROJECT_A);
        membership(SUBJECT_A, PROJECT_A);
        membership(ADMIN_B, PROJECT_B);
        membership(SUBJECT_B, PROJECT_B);

        knowledgeBase(KNOWLEDGE_BASE_A, ORG_A, PROJECT_A, "Alpha Knowledge");
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

    /** A user, for a test that needs one the default seed does not create. */
    public void user(UUID id, UUID org, String email, String role) {
        at(SEED_AT, """
            insert into users ("id", "organizationId", "email", "passwordHash", "fullName", "role",
                               "locale", "isActive", "createdAt", "updatedAt")
            values (:id, :org, :email, 'not-a-real-hash', :name, cast(:role as "UserRole"),
                    cast('ar' as "Locale"), true, :now, :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("org", org)
                .addValue("email", email).addValue("role", role).addValue("name", "Seeded User"));
    }

    private void membership(UUID userId, UUID projectId) {
        jdbc.update("""
            insert into project_memberships ("id", "organizationId", "projectId", "userId", "createdAt")
            values (:id, :org, :projectId, :userId, :now)
            """, new MapSqlParameterSource()
            .addValue("id", UUID.randomUUID())
            .addValue("org", orgOf(userId))
            .addValue("projectId", projectId)
            .addValue("userId", userId)
            .addValue("now", SEED_AT));
    }

    private void knowledgeBase(UUID id, UUID org, UUID projectId, String name) {
        at(SEED_AT, """
            insert into knowledge_bases ("id", "organizationId", "projectId", "name", "isDefault",
                                         "createdAt", "updatedAt")
            values (:id, :org, :project, :name, true, :now, :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("org", org)
                .addValue("project", projectId).addValue("name", name));
    }

    /** A live browser session for a user. */
    public UUID session(UUID org, UUID userId, String userAgent, String ipAddress,
                        LocalDateTime expiresAt, LocalDateTime revokedAt) {
        UUID id = UUID.randomUUID();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("id", id).addValue("org", org).addValue("user", userId)
            .addValue("userAgent", userAgent).addValue("ipAddress", ipAddress)
            .addValue("expiresAt", expiresAt).addValue("revokedAt", revokedAt)
            .addValue("now", now());
        jdbc.update("""
            insert into sessions ("id", "organizationId", "userId", "tokenHash", "userAgent",
                                  "ipAddress", "expiresAt", "revokedAt", "createdAt", "lastSeenAt")
            values (:id, :org, :user, encode(digest(:id::text, 'sha256'), 'hex'), :userAgent,
                    :ipAddress, :expiresAt, :revokedAt, :now, :now)
            """, parameters);
        return id;
    }

    /**
     * A job-integration event attributed to a machine principal.
     *
     * <p>Seeded rather than written through the audit writer so the test can prove the identity
     * read model derives observed machine principals from the <em>rows</em> an existing worker
     * already wrote, rather than from a table it would have to own.
     */
    public UUID jobIntegrationEvent(UUID org, String componentType, String component) {
        UUID id = UUID.randomUUID();
        at(now(), """
            insert into job_integration_events ("id", "organizationId", "component", "componentType",
                "eventType", "outcome", "retentionClass", "occurredAt")
            values (:id, :org, :component, cast(:componentType as "AuditPrincipalType"), 'JOB_STARTED',
                    'SUCCESS', 'JOB_PROVENANCE', :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("org", org)
                .addValue("component", component).addValue("componentType", componentType));
        return id;
    }

    /** A notification addressed to a user, so the impact probe has a non-zero row to find. */
    public void notification(UUID org, UUID projectId, UUID userId) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("id", UUID.randomUUID()).addValue("org", org).addValue("project", projectId)
            .addValue("user", userId).addValue("now", now());
        jdbc.update("""
            insert into notifications ("id", "organizationId", "projectId", "userId", "type", "title",
                "createdAt")
            values (:id, :org, :project, :user, cast('MENTION' as "NotificationType"),
                'Seeded notification', :now)
            """, parameters);
    }

    /** A connector connection with a credential the Admin surface must never render. */
    public UUID connection(UUID org, UUID projectId, String channel, String credentials,
                           String webhookSecret, String lastError) {
        UUID id = UUID.randomUUID();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("id", id).addValue("org", org).addValue("project", projectId)
            .addValue("channel", channel).addValue("credentials", credentials)
            .addValue("webhookSecret", webhookSecret).addValue("lastError", lastError)
            .addValue("now", now());
        jdbc.update("""
            insert into channel_connections ("id", "organizationId", "projectId", "channel",
                "displayName", "isEnabled", "status", "credentials", "webhookSecret", "lastError",
                "createdAt", "updatedAt")
            values (:id, :org, :project, cast(:channel as "ChannelType"), 'Seeded connection', true,
                    cast('CONNECTED' as "ChannelConnectionStatus"), :credentials, :webhookSecret,
                    :lastError, :now, :now)
            """, parameters);
        return id;
    }

    /** A dead-lettered outbox event, with the attempts that killed it. */
    public UUID deadOutboxEvent(UUID org, UUID projectId, String queue, int attempts,
                                String deadLetterReason) {
        UUID eventId = UUID.randomUUID();
        at(now(), """
            insert into outbox_events ("id", "organizationId", "projectId", "aggregateType",
                "aggregateId", "eventType", "payload", "idempotencyKey", "state", attempts,
                "correlationId", "nextAttemptAt", "deadLetteredAt", "deadLetterReason",
                "createdAt", "updatedAt")
            values (:id, :org, :project, 'Seeded', gen_random_uuid(), :queue,
                    cast('{"seeded":true}' as jsonb), :key, 'DEAD', :attempts, :correlation,
                    :now, :now, :reason, :now, :now)""",
            new MapSqlParameterSource().addValue("id", eventId).addValue("org", org)
                .addValue("project", projectId).addValue("queue", queue)
                .addValue("key", "seed:" + eventId)
                .addValue("attempts", attempts).addValue("correlation", "corr-" + eventId)
                .addValue("reason", deadLetterReason));

        for (int attempt = 1; attempt <= Math.min(attempts, 3); attempt++) {
            attempt(org, projectId, queue, eventId, attempt,
                attempt == attempts ? "NON_RETRYABLE_FAILURE" : "RETRYABLE_FAILURE");
        }

        deadLetter(org, projectId, queue, eventId, deadLetterReason, attempts);
        return eventId;
    }

    private void attempt(UUID org, UUID projectId, String queue, UUID eventId, int attemptNumber,
                         String outcome) {
        at(now(), """
            insert into job_attempts ("id", "organizationId", "projectId", "queueName", "jobType",
                "jobId", "attemptNumber", "outcome", "retryClass", "errorCode", "startedAt",
                "finishedAt", "durationMs", "workerId", "outboxEventId", "createdAt")
            values (:id, :org, :project, :queue, :queue, :jobId, :attemptNumber,
                    cast(:outcome as "JobAttemptOutcome"), :retryClass, :errorCode, :now, :now, 12,
                    'fe-worker@seed', :eventId, :now)""",
            new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("org", org)
                .addValue("project", projectId).addValue("queue", queue)
                .addValue("jobId", eventId.toString()).addValue("attemptNumber", attemptNumber)
                .addValue("outcome", outcome)
                .addValue("eventId", eventId)
                .addValue("retryClass", "PERMANENT")
                .addValue("errorCode", "SEEDED_FAILURE"));
    }

    private void deadLetter(UUID org, UUID projectId, String queue, UUID eventId, String reason,
                            int attempts) {
        at(now(), """
            insert into job_dead_letters ("id", "organizationId", "projectId", "queueName", "jobId",
                "outboxEventId", "reason", "payloadSummary", "attempts", "status", "createdAt",
                "updatedAt")
            values (:id, :org, :project, :queue, :jobId, :eventId, :reason,
                    cast('{"seeded":true}' as jsonb), :attempts,
                    cast('OPEN' as "DeadLetterStatus"), :now, :now)""",
            new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("org", org)
                .addValue("project", projectId).addValue("queue", queue)
                .addValue("jobId", eventId.toString()).addValue("eventId", eventId)
                .addValue("reason", reason).addValue("attempts", attempts));
    }

    /**
     * An AI tool in the deny-by-default state, with the reason the schema requires.
     *
     * <p>The disabling user is looked up in <em>this</em> organization rather than hard-wired to
     * Alpha's admin, because {@code ai_tool_registry_disabled_by_tenant_fkey} is a composite tenant
     * key and the database refuses a foreign actor. That refusal is itself worth having: it is the
     * same shape of rule as {@code privacy_deletion_requests.subjectUserId}.
     */
    public UUID disabledTool(UUID org, String toolName, String reason) {
        UUID id = UUID.randomUUID();
        at(now(), """
            insert into ai_tool_registry ("id", "organizationId", "projectId", "toolName",
                "requiredPermission", "isEnabled", "disabledAt", "disabledByUserId", "disabledReason",
                "fieldAllowList", "riskLevel", "consequential", "createdAt", "updatedAt")
            values (:id, :org, null, :toolName, 'customer:read', false, :now, :disabledBy, :reason,
                    '{}'::text[], cast('LOW' as "AiRiskLevel"), false, :now, :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("org", org)
                .addValue("toolName", toolName).addValue("disabledBy", adminOf(org))
                .addValue("reason", reason));
        return id;
    }

    /** An enabled AI tool. */
    public UUID enabledTool(UUID org, String toolName) {
        UUID id = UUID.randomUUID();
        at(now(), """
            insert into ai_tool_registry ("id", "organizationId", "projectId", "toolName",
                "requiredPermission", "isEnabled", "enabledAt", "enabledByUserId", "fieldAllowList",
                "riskLevel", "consequential", "createdAt", "updatedAt")
            values (:id, :org, null, :toolName, 'customer:read', true, :now, :enabledBy,
                    '{}'::text[], cast('LOW' as "AiRiskLevel"), false, :now, :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("org", org)
                .addValue("toolName", toolName).addValue("enabledBy", adminOf(org)));
        return id;
    }

    /** The ADMIN user of an organization, for a row the schema ties to a tenant. */
    private UUID adminOf(UUID organizationId) {
        return jdbc.queryForObject(
            "select u.\"id\" from users u where u.\"organizationId\" = :org and u.\"role\" ="
                + " cast('ADMIN' as \"UserRole\") limit 1",
            new MapSqlParameterSource().addValue("org", organizationId), UUID.class);
    }

    /**
     * A DENIED tool execution whose {@code argumentsRedacted} holds a marker string.
     *
     * <p>The marker exists so a test can prove it never appears in a read model: the read model
     * selects no jsonb column from this table, so the assertion is about the <em>projection</em>, not
     * about redaction.
     */
    public UUID deniedToolCall(UUID org, UUID projectId, String toolName, String denyReason,
                               String argumentsMarker) {
        UUID id = UUID.randomUUID();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("id", id).addValue("org", org).addValue("project", projectId)
            .addValue("toolName", toolName).addValue("reason", denyReason)
            // A JSON object, so the marker is a value inside a well-formed document rather than the
            // whole document. The assertion is the same either way: the marker is in the table and
            // in no projection.
            .addValue("arguments", "{\"note\":\"" + argumentsMarker + "\"}").addValue("now", now());
        jdbc.update("""
            insert into ai_tool_executions ("id", "organizationId", "projectId", "toolName",
                "actionClass", "consequentialAction", "decision", "denyReason", "argumentsRedacted",
                "resultRedacted", "principalType", "requestedAt")
            values (:id, :org, :project, :toolName, 'CONSEQUENTIAL', true,
                    cast('DENY' as "AiPolicyDecision"), :reason, cast(:arguments as jsonb),
                    '{}'::jsonb, 'AI_AGENT', :now)
            """, parameters);
        return id;
    }

    /** A redaction record: policy, rule, field, and how many values. Never the values. */
    public UUID redactionRecord(UUID org, UUID projectId, String ruleId, String fieldPath,
                                int valuesRedacted) {
        UUID id = UUID.randomUUID();
        at(now(), """
            insert into ai_redaction_records ("id", "organizationId", "projectId", "redactionPolicy",
                "ruleId", "fieldPath", "valuesRedacted", "charactersRedacted", "redactedAt")
            values (:id, :org, :project, 'DEFAULT', :ruleId, :fieldPath, :values, 42, :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("org", org)
                .addValue("project", projectId).addValue("ruleId", ruleId)
                .addValue("fieldPath", fieldPath).addValue("values", valuesRedacted));
        return id;
    }

    /** An AI policy, disabled by default, with the four approval classes on. */
    public UUID aiPolicy(UUID org, UUID projectId, String key) {
        UUID id = UUID.randomUUID();
        at(now(), """
            insert into ai_policies ("id", "organizationId", "projectId", "key", "nameAr", "nameEn",
                "isEnabled", "maxTokensPerRun", "maxToolCallsPerRun", "redactionPolicy",
                "retentionClass", "createdAt", "updatedAt")
            values (:id, :org, :project, :key, 'سياسة', 'Policy', false, 4096, 8, 'DEFAULT',
                    'AI_PROVENANCE', :now, :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("org", org)
                .addValue("project", projectId).addValue("key", key));
        return id;
    }

    /** A budget with a ceiling and a consumption posting against it. */
    public UUID aiBudget(UUID org, UUID projectId, String key, String amount) {
        UUID id = UUID.randomUUID();
        at(now(), """
            insert into ai_budgets ("id", "organizationId", "projectId", "key", "nameAr", "nameEn",
                "period", "currency", "limitAmount", "enforcement", "isEnabled", "validFrom",
                "createdAt", "updatedAt")
            values (:id, :org, :project, :key, 'ميزانية', 'Budget', cast('MONTHLY' as "AiBudgetPeriod"),
                    'USD', cast(:amount as numeric), 'ALERT', true, current_date, :now, :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("org", org)
                .addValue("project", projectId).addValue("key", key).addValue("amount", amount));

        at(now(), """
            insert into ai_budget_consumptions ("id", "organizationId", "projectId", "budgetId",
                "periodStart", "periodEnd", "currency", "amount", "inputTokens", "outputTokens",
                "recordedAt")
            values (gen_random_uuid(), :org, :project, :budget, date_trunc('month', current_date),
                    date_trunc('month', current_date) + interval '1 month' - interval '1 day',
                    'USD', 1.25, 100, 20, :now)""",
            new MapSqlParameterSource().addValue("org", org).addValue("project", projectId)
                .addValue("budget", id));
        return id;
    }

    /**
     * An export job in the given state, so the state machine has a row to read.
     *
     * <p>The state machine's biconditionals are all honoured here rather than worked around:
     * {@code EXPIRED} iff {@code expiredAt}, and {@code DELETED} iff {@code deletedAt} and
     * {@code deletedByUserId} and a NULL {@code storageKey}. A seeder that ignored them would let a
     * read model be tested against rows the schema forbids, which is how a projection starts
     * looking correct for states that cannot exist.
     */
    public UUID exportJob(UUID org, UUID projectId, UUID requestedBy, String status,
                          LocalDateTime expiresAt, boolean objectPresent) {
        UUID id = UUID.randomUUID();
        LocalDateTime clock = now();
        LocalDateTime deletedAt = "DELETED".equals(status) ? clock : null;
        LocalDateTime expiredAt = "EXPIRED".equals(status) ? clock : null;
        // DELETED is a tombstone: the row stays, the object does not.
        boolean objectPresentForRow = objectPresent && !"DELETED".equals(status);
        // export_jobs_expiry_check is `expiresAt > requestedAt`. Deriving requestedAt from the
        // expiry — a 30-day window — keeps that true for both a window still open and one that has
        // already closed, which is the case the sweeper check exists to catch.
        LocalDateTime requestedAt = expiresAt == null
            ? clock.minusDays(30)
            : expiresAt.minusDays(30);
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("id", id).addValue("org", org).addValue("project", projectId)
            .addValue("requestedBy", requestedBy)
            .addValue("status", status).addValue("expiresAt", expiresAt)
            .addValue("requestedAt", requestedAt)
            .addValue("storageKey", objectPresentForRow ? "exports/" + id + ".csv" : null)
            .addValue("checksum", objectPresentForRow
                ? "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef" : null)
            .addValue("rowCount", objectPresentForRow ? 42L : null)
            .addValue("startedAt", "QUEUED".equals(status) ? null : requestedAt.plusSeconds(1))
            .addValue("now", clock);
        jdbc.update("""
            insert into export_jobs ("id", "organizationId", "projectId", "requestedByUserId",
                "format", "status", "redactionPolicy", "retentionClass", "authorizationSnapshot",
                "progressPct", "attemptCount", "startedAt", "finishedAt", "rowCount", "checksum",
                "storageKey", "bytes", "expiresAt", "requestedAt", "createdAt", "updatedAt",
                "deletedAt", "deletedByUserId", "expiredAt", "errorMessage")
            values (:id, :org, :project, :requestedBy, cast('CSV' as "ExportFormat"),
                    cast(:status as "ExportJobStatus"), 'DEFAULT', 'EXPORT_ARTEFACTS',
                    '{"permission":"organization:read"}'::jsonb,
                    case when cast(:status as text) = 'COMPLETED' then 100 else 0 end,
                    0, :startedAt,
                    case when cast(:status as text) in ('COMPLETED', 'FAILED', 'CANCELLED')
                         then :now else null end,
                    :rowCount, :checksum, :storageKey,
                    case when cast(:storageKey as text) is null then null else 2048 end,
                    :expiresAt, :requestedAt, :now, :now,
                    cast(:deletedAt as timestamp), cast(:deletedBy as uuid),
                    cast(:expiredAt as timestamp), :errorMessage)
            """, parameters.addValue("deletedAt", deletedAt)
                .addValue("deletedBy", deletedAt == null ? null : requestedBy)
                .addValue("expiredAt", expiredAt)
                // export_jobs_failure_reason_check: FAILED iff errorMessage. Seeding the message is
                // what makes the row a state a reader can actually be shown.
                .addValue("errorMessage", "FAILED".equals(status) ? "seeded export failure" : null));
        return id;
    }

    /** A refused export download. */
    public void deniedDownload(UUID org, UUID projectId, UUID jobId, UUID actorUserId,
                               String denyReason) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("id", UUID.randomUUID()).addValue("org", org).addValue("project", projectId)
            .addValue("jobId", jobId).addValue("actor", actorUserId)
            .addValue("reason", denyReason).addValue("now", now());
        jdbc.update("""
            insert into export_download_events ("id", "organizationId", "projectId", "exportJobId",
                "actorUserId", "principalName", "outcome", "denyReason", "occurredAt")
            values (:id, :org, :project, :jobId, :actor, 'USER:' || :actor,
                    cast('DENIED' as "ExportDownloadOutcome"), :reason, :now)
            """, parameters);
    }

    /**
     * An object-storage deletion event.
     *
     * <p>{@code legalHoldChecked} is the ADR-0008 rule 5 evidence column, so a test seeds both the
     * checked and the unchecked case: a reader that could not tell them apart would be reporting
     * "a deletion happened", which is the claim the column exists to prevent.
     */
    public UUID exportDeletion(UUID org, UUID jobId, boolean legalHoldChecked, String failureCode) {
        UUID id = UUID.randomUUID();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("id", id).addValue("org", org).addValue("job", jobId)
            .addValue("checked", legalHoldChecked).addValue("failure", failureCode)
            .addValue("failureMessage", failureCode == null ? null : "seeded failure")
            .addValue("outcome", failureCode == null ? "STORAGE_OBJECT_DELETED" : "STORAGE_DELETE_FAILED")
            .addValue("project", projectOf(org))
            .addValue("now", now());
        jdbc.update("""
            insert into export_deletion_events ("id", "organizationId", "projectId", "exportJobId",
                "outcome", "storageKeyAtDelete", "bytesReclaimed", "requestedByUserId", "failureCode",
                "failureMessage", "legalHoldChecked", "occurredAt")
            values (:id, :org, :project, :job, cast(:outcome as "ExportDeletionOutcome"),
                    'exports/' || :job, 2048, :requestedBy, :failure, :failureMessage, :checked, :now)
            """, parameters.addValue("requestedBy", ADMIN_A));
        return id;
    }

    /** The first project of an organization, for a row the schema declares NOT NULL on. */
    private UUID projectOf(UUID organizationId) {
        return jdbc.queryForObject(
            "select p.\"id\" from projects p where p.\"organizationId\" = :org order by p.\"slug\""
                + " limit 1",
            new MapSqlParameterSource().addValue("org", organizationId), UUID.class);
    }

    /**
     * An active legal hold for the tenant.
     *
     * <p>The actor is looked up in <em>this</em> organization, because
     * {@code legal_holds_placed_by_tenant_fkey} is a composite tenant key and the database refuses a
     * foreign actor. A {@code TABLE} hold also gets a {@code scopeRef}, which
     * {@code legal_holds_scope_ref_check} requires: a hold naming no table is meaningless, and a
     * seeder that ignored that would be testing a row the schema forbids.
     */
    public UUID legalHold(UUID org, UUID projectId, String scopeType, String reason) {
        UUID id = UUID.randomUUID();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("id", id).addValue("org", org).addValue("project", projectId)
            .addValue("scopeType", scopeType).addValue("reason", reason)
            .addValue("scopeRef", isNarrowScope(scopeType) ? "public.messages" : null)
            .addValue("placedBy", adminOf(org)).addValue("now", now());
        jdbc.update("""
            insert into legal_holds ("id", "organizationId", "projectId", "scopeType", "scopeRef",
                "reason", "status", "placedAt", "placedByUserId", "version", "createdAt", "updatedAt")
            values (:id, :org, :project, cast(:scopeType as "LegalHoldScopeType"), :scopeRef,
                    :reason, 'ACTIVE', :now, :placedBy, 0, :now, :now)
            """, parameters);
        return id;
    }

    /**
     * A narrow hold that deliberately omits its {@code scopeRef}.
     *
     * <p>Exists so a test can prove the rule that survived the pair-check widening still refuses a
     * narrow hold naming nothing. Without a seeder that omits the target, "narrow holds are now
     * placeable" and "narrow holds accept anything" are indistinguishable.
     */
    public UUID legalHoldNoScopeRef(UUID org, String scopeType, String reason) {
        UUID id = UUID.randomUUID();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("id", id).addValue("org", org).addValue("project", null)
            .addValue("scopeType", scopeType).addValue("reason", reason)
            .addValue("scopeRef", null)
            .addValue("placedBy", adminOf(org)).addValue("now", now());
        jdbc.update("""
            insert into legal_holds ("id", "organizationId", "projectId", "scopeType", "scopeRef",
                "reason", "status", "placedAt", "placedByUserId", "version", "createdAt", "updatedAt")
            values (:id, :org, :project, cast(:scopeType as "LegalHoldScopeType"), :scopeRef,
                    :reason, 'ACTIVE', :now, :placedBy, 0, :now, :now)
            """, parameters);
        return id;
    }

    /** Whether a hold scope names something narrower than a project, and so needs a scopeRef. */
    private static boolean isNarrowScope(String scopeType) {
        return "TABLE".equals(scopeType) || "RECORD".equals(scopeType);
    }

    /** A privacy-deletion request in the REQUESTED state. */
    public UUID deletionRequest(UUID org, UUID projectId, UUID subjectUserId, String status) {
        UUID id = UUID.randomUUID();
        at(now(), """
            insert into privacy_deletion_requests ("id", "organizationId", "projectId",
                "subjectUserId", "subjectType", "status", "reason", "requestedAt",
                "requestedByUserId", "completedAt", "evidenceReference", "version", "createdAt",
                "updatedAt")
            values (:id, :org, :project, :subject, 'USER', cast(:status as
                "PrivacyDeletionRequestStatus"), 'seeded request', :now, :by,
                case when :status = 'COMPLETED' then :now else null end,
                case when :status = 'COMPLETED' then 'seed-evidence' else null end,
                0, :now, :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("org", org)
                .addValue("project", projectId).addValue("subject", subjectUserId)
                // The requester is resolved in the request's own tenant:
                // privacy_deletion_requests_actor_tenant_fkey is a composite tenant key, so a
                // requester from another tenant is refused by the database rather than by this
                // fixture — the same shape of rule as the hold's placedByUserId.
                .addValue("status", status).addValue("by", adminOf(org)));
        return id;
    }

    /**
     * A decided privacy-deletion target.
     *
     * <p>{@code decision} and {@code legalHoldId} are a biconditional in the schema: a
     * {@code RETAINED_LEGAL_HOLD} row must name the hold and no other decision may. The deciding
     * user is resolved in the request's own tenant, because
     * {@code privacy_deletion_targets_actor_tenant_fkey} is a composite tenant key.
     */
    public UUID deletionTarget(UUID org, UUID requestId, UUID subjectUserId, String decision,
                              Long rowsRemoved) {
        UUID id = UUID.randomUUID();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("id", id).addValue("org", org).addValue("request", requestId)
            .addValue("subject", subjectUserId).addValue("decision", decision)
            .addValue("rows", rowsRemoved).addValue("by", adminOf(org))
            .addValue("hold",
                "RETAINED_LEGAL_HOLD".equals(decision) ? UUID.randomUUID().toString() : null)
            .addValue("now", now());
        jdbc.update("""
            insert into privacy_deletion_targets ("id", "organizationId", "deletionRequestId",
                "targetType", "targetId", "targetRef", "rowsRemoved", "decision", "decidedAt",
                "decidedByUserId", "legalHoldId")
            values (:id, :org, :request, 'USER', :subject, 'seeded target', :rows, :decision,
                    :now, :by, :hold)
            """, parameters);
        return id;
    }

    /** A document in the given ingestion state. */
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
                .addValue("now", updatedAt);
            jdbc.update("""
                insert into document_chunks ("id", "organizationId", "projectId", "knowledgeBaseId",
                    "documentId", "chunkIndex", "content", "tokenCount", "embedding", "createdAt")
                values (:id, :org, :project, :kb, :documentId, :index, 'seeded chunk text', 12,
                        case when :index < (:actual - :unembedded)
                             then (select e from (select
                                ('[' || array_to_string(array_fill(0::real, 1536), ',') || ']')::vector
                                  as e) seed)
                             else null end,
                        :now)
                """, chunkParameters.addValue("actual", actualChunks));
        }
        return id;
    }

    /** A knowledge gap with no query text, which the fixture never needs. */
    public UUID knowledgeGap(UUID org, UUID projectId, double bestScore, int retrievedCount,
                             LocalDateTime createdAt) {
        UUID id = UUID.randomUUID();
        at(createdAt, """
            insert into knowledge_gaps ("id", "organizationId", "projectId", "query", "bestScore",
                "retrievedCount", "createdAt")
            values (:id, :org, :project, 'seeded query text', :bestScore, :retrieved, :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("org", org)
                .addValue("project", projectId).addValue("bestScore", bestScore)
                .addValue("retrieved", retrievedCount));
        return id;
    }

    /** A retention class for a stream. */
    public UUID retentionClass(UUID org, UUID projectId, String classCode, String stream,
                               int retentionDays) {
        UUID id = UUID.randomUUID();
        at(now(), """
            insert into retention_classes ("id", "organizationId", "projectId", "classCode",
                "stream", "retentionDays", "legalHoldExempt", "description", "createdByUserId",
                "createdAt", "updatedAt")
            values (:id, :org, :project, :classCode, cast(:stream as "AuditStreamKind"), :days, true,
                    'seeded class', :by, :now, :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("org", org)
                .addValue("project", projectId).addValue("classCode", classCode)
                .addValue("stream", stream).addValue("days", retentionDays).addValue("by", ADMIN_A));
        return id;
    }

    /** A legacy audit row, written by the still-live {@code audit_logs} writer. */
    public void legacyAudit(UUID org, UUID actorUserId, String action) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("id", UUID.randomUUID()).addValue("org", org).addValue("actor", actorUserId)
            .addValue("action", action).addValue("now", now());
        jdbc.update("""
            insert into audit_logs ("id", "organizationId", "actorUserId", "actorType", "action",
                "resourceType", "resourceId", "metadata", "createdAt")
            values (:id, :org, :actor, 'USER', :action, 'Seeded', 'seed', '{}'::jsonb, :now)
            """, parameters);
    }

    /** A DENIED row on the physical security stream. */
    public UUID deniedSecurityEvent(UUID org, UUID projectId, UUID actorUserId, String eventType,
                                    String denyReason) {
        UUID id = UUID.randomUUID();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("id", id).addValue("org", org).addValue("project", projectId)
            .addValue("actor", actorUserId).addValue("eventType", eventType)
            .addValue("reason", denyReason).addValue("now", now());
        jdbc.update("""
            insert into security_events ("id", "organizationId", "projectId", "actorUserId",
                "principalType", "principalName", "eventType", "outcome", "denyReason",
                "retentionClass", "occurredAt")
            values (:id, :org, :project, :actor, cast('USER' as "AuditPrincipalType"),
                    'USER:' || :actor, :eventType, cast('DENIED' as "AuditOutcome"), :reason,
                    'UNDECLARED', :now)
            """, parameters);
        return id;
    }

    /** A stranded lease: a LEASED row whose lease has expired. */
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

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private UUID orgOf(UUID userId) {
        return jdbc.queryForObject("select u.\"organizationId\" from users u where u.\"id\" = :id",
            new MapSqlParameterSource().addValue("id", userId), UUID.class);
    }

    private void at(LocalDateTime when, String sql, MapSqlParameterSource parameters) {
        parameters.addValue("now", when);
        jdbc.update(sql, parameters);
    }

    /** Counts rows in a table, tenant-scoped or not. Test-only convenience. */
    public int count(String sql, Map<String, Object> parameters) {
        // Wrapped so a caller can write the natural predicate (`select 1 from t where ...`) and get 0
        // when nothing matches: queryForObject throws EmptyResultDataAccessException on a zero-row
        // result, which would turn "this removed nothing" into an error instead of a pass.
        Integer count = jdbc.queryForObject("select count(*) from (" + sql + ") fe_counted",
            new MapSqlParameterSource(parameters), Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * The number of rows in a table for one tenant.
     *
     * <p>Deliberately not routed through {@link #count}: that wraps its SQL so a zero-row
     * predicate result reads as 0, and wrapping an aggregate that always returns exactly one row
     * would report 1 forever.
     */
    public int tenantRows(String table, UUID organizationId) {
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
