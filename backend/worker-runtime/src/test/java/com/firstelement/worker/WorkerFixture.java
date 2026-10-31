package com.firstelement.worker;

import com.firstelement.worker.config.UtcTime;
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
 * The worker-runtime fixture: two tenants, one shared container, real Flyway.
 *
 * <h2>Fixed ids, so an assertion can name a row</h2>
 *
 * <p>Like the inbox fixture, tenants and projects use fixed UUIDs. A fixture that has to blank out
 * every id proves nothing about ordering, ownership or which row a lease belongs to — and lease
 * ownership is the thing this phase has to be exact about.
 *
 * <h2>Reset order matters</h2>
 *
 * <p>Every child table first. {@code job_dead_letters} references {@code job_attempts},
 * {@code job_attempts} references {@code outbox_events}, and all three cascade from
 * {@code organizations}; truncating in the wrong order would fail on a foreign key rather than
 * leaving a clean slate.
 */
@Component
public class WorkerFixture {

    public static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16"))
            .withDatabaseName("firstelement_worker")
            .withUsername("firstelement")
            .withPassword("firstelement");

    public static final UUID ORG_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    public static final UUID ORG_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    public static final UUID PROJECT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000a1");
    public static final UUID PROJECT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000000b1");

    public static final UUID USER_A = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000e1");
    public static final UUID USER_B = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000000e2");

    public static final LocalDateTime SEED_AT = LocalDateTime.of(2026, 8, 14, 9, 30, 0);

    private final NamedParameterJdbcTemplate jdbc;

    public WorkerFixture(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Truncates and re-seeds. Deterministic: same rows and same ids every time. */
    public void reset() {
        jdbc.update("delete from job_dead_letters", new MapSqlParameterSource());
        jdbc.update("delete from job_attempts", new MapSqlParameterSource());
        jdbc.update("delete from idempotency_records", new MapSqlParameterSource());
        jdbc.update("delete from outbox_events", new MapSqlParameterSource());
        jdbc.update("delete from project_memberships", new MapSqlParameterSource());
        jdbc.update("delete from users", new MapSqlParameterSource());
        jdbc.update("delete from projects", new MapSqlParameterSource());
        jdbc.update("delete from organizations", new MapSqlParameterSource());

        at(SEED_AT, """
            insert into organizations ("id", "name", "slug", "createdAt", "updatedAt")
            values (:id, :name, :slug, :now, :now)""",
            new MapSqlParameterSource().addValue("id", ORG_A).addValue("name", "Alpha Clinic")
                .addValue("slug", "alpha-clinic"));
        at(SEED_AT, """
            insert into organizations ("id", "name", "slug", "createdAt", "updatedAt")
            values (:id, :name, :slug, :now, :now)""",
            new MapSqlParameterSource().addValue("id", ORG_B).addValue("name", "Beta Clinic")
                .addValue("slug", "beta-clinic"));
        at(SEED_AT, """
            insert into projects ("id", "organizationId", "name", "slug", "createdAt", "updatedAt")
            values (:id, :org, :name, :slug, :now, :now)""",
            new MapSqlParameterSource().addValue("id", PROJECT_A).addValue("org", ORG_A)
                .addValue("name", "Alpha One").addValue("slug", "alpha-one"));
        at(SEED_AT, """
            insert into projects ("id", "organizationId", "name", "slug", "createdAt", "updatedAt")
            values (:id, :org, :name, :slug, :now, :now)""",
            new MapSqlParameterSource().addValue("id", PROJECT_B).addValue("org", ORG_B)
                .addValue("name", "Beta One").addValue("slug", "beta-one"));
        at(SEED_AT, """
            insert into users ("id", "organizationId", "email", "passwordHash", "fullName", "role",
                               "locale", "isActive", "createdAt", "updatedAt")
            values (:id, :org, :email, 'not-a-real-hash', :name, cast('ADMIN' as "UserRole"),
                    cast('ar' as "Locale"), true, :now, :now)""",
            new MapSqlParameterSource().addValue("id", USER_A).addValue("org", ORG_A)
                .addValue("email", "admin@a.test").addValue("name", "Alpha Admin"));
        at(SEED_AT, """
            insert into users ("id", "organizationId", "email", "passwordHash", "fullName", "role",
                               "locale", "isActive", "createdAt", "updatedAt")
            values (:id, :org, :email, 'not-a-real-hash', :name, cast('ADMIN' as "UserRole"),
                    cast('ar' as "Locale"), true, :now, :now)""",
            new MapSqlParameterSource().addValue("id", USER_B).addValue("org", ORG_B)
                .addValue("email", "admin@b.test").addValue("name", "Beta Admin"));
    }

    /** Seeds one outbox intent directly, for a fixture that is not testing the enqueue. */
    public UUID seedOutbox(UUID organizationId, UUID projectId, String eventType,
                           String idempotencyKey) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            insert into outbox_events ("id", "organizationId", "projectId", "aggregateType",
                "aggregateId", "eventType", "payload", "idempotencyKey", "nextAttemptAt",
                "correlationId", "createdAt", "updatedAt")
            values (:id, :org, :project, 'Fixture', :aggregate, :eventType,
                CAST(:payload AS jsonb), :key, :now, 'corr-fixture', :now, :now)""",
            new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("org", organizationId)
                .addValue("project", projectId)
                .addValue("aggregate", UUID.randomUUID())
                .addValue("eventType", eventType)
                .addValue("payload", "{}")
                .addValue("key", idempotencyKey)
                .addValue("now", UtcTime.now()));
        return id;
    }

    /**
     * Ages a row''s lease into the past, simulating a worker that stopped answering.
     *
     * <p>Both halves of the lease are written. {@code outbox_events_lease_pair_check} requires
     * {@code leaseOwner} and {@code leaseUntil} to be null together or set together, so pushing only
     * {@code leaseUntil} into the past on an unleased row is not a stranded lease, it is an
     * impossible row — and the database is right to refuse it.
     */
    public void expireLease(UUID outboxEventId) {
        jdbc.update("""
            update outbox_events
               set "leaseOwner" = :owner,
                   "leaseUntil" = (now() AT TIME ZONE 'UTC') - interval '1 minute',
                   "updatedAt"  = (now() AT TIME ZONE 'UTC')
             where "id" = :id""",
            new MapSqlParameterSource()
                .addValue("id", outboxEventId)
                .addValue("owner", "worker-that-died"));
    }

    /** Pushes a row's next attempt into the past so it becomes claimable. */
    public void makeDue(UUID outboxEventId) {
        jdbc.update("""
            update outbox_events
               set "nextAttemptAt" = (now() AT TIME ZONE 'UTC') - interval '1 second',
                   "updatedAt"      = (now() AT TIME ZONE 'UTC')
             where "id" = :id""",
            new MapSqlParameterSource().addValue("id", outboxEventId));
    }

    public String stateOf(UUID outboxEventId) {
        return jdbc.queryForObject("select \"state\"::text from outbox_events where \"id\" = :id",
            new MapSqlParameterSource().addValue("id", outboxEventId), String.class);
    }

    public String leaseOwnerOf(UUID outboxEventId) {
        return jdbc.queryForObject("select \"leaseOwner\" from outbox_events where \"id\" = :id",
            new MapSqlParameterSource().addValue("id", outboxEventId), String.class);
    }

    public String lastErrorOf(UUID outboxEventId) {
        return jdbc.queryForObject("select \"lastError\" from outbox_events where \"id\" = :id",
            new MapSqlParameterSource().addValue("id", outboxEventId), String.class);
    }

    public Integer attemptsOf(UUID outboxEventId) {
        return jdbc.queryForObject("select attempts from outbox_events where \"id\" = :id",
            new MapSqlParameterSource().addValue("id", outboxEventId), Integer.class);
    }

    public Integer replayCountOf(UUID outboxEventId) {
        return jdbc.queryForObject("select \"replayCount\" from outbox_events where \"id\" = :id",
            new MapSqlParameterSource().addValue("id", outboxEventId), Integer.class);
    }

    public Integer deadLetterCount() {
        return count("select count(*) from job_dead_letters");
    }

    public Integer attemptCount(UUID organizationId, String queueName) {
        return count("select count(*) from job_attempts where \"organizationId\" = '"
            + organizationId + "' and \"queueName\" = '" + queueName + "'");
    }

    public List<String> attemptOutcomes(UUID organizationId, String queueName) {
        return jdbc.queryForList(
            "select outcome::text from job_attempts"
                + " where \"organizationId\" = :org and \"queueName\" = :queue"
                + " order by \"attemptNumber\"",
            new MapSqlParameterSource().addValue("org", organizationId)
                .addValue("queue", queueName), String.class);
    }

    public Integer count(String sql) {
        return jdbc.queryForObject(sql, new MapSqlParameterSource(), Integer.class);
    }

    /** A single text column, so an assertion can name a UUID by its stored spelling. */
    public String textOf(String sql, UUID id) {
        return jdbc.queryForObject(sql, new MapSqlParameterSource().addValue("id", id), String.class);
    }

    /** A single timestamp column, or null. Used to assert that "when" was recorded at all. */
    public LocalDateTime timestampOf(String sql, UUID id) {
        return jdbc.queryForObject(sql, new MapSqlParameterSource().addValue("id", id),
            LocalDateTime.class);
    }

    public NamedParameterJdbcTemplate jdbc() {
        return jdbc;
    }

    /** The active tenants a fan-out worker would iterate. */
    public Set<UUID> tenants() {
        return Set.of(ORG_A, ORG_B);
    }

    public Map<String, Object> adminA() {
        return Map.of("organizationId", ORG_A);
    }

    private void at(LocalDateTime now, String sql, MapSqlParameterSource parameters) {
        jdbc.update(sql, parameters.addValue("now", now));
    }
}