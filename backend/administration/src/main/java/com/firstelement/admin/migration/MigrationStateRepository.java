package com.firstelement.admin.migration;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;

/**
 * Migration state, read-only, from {@code flyway_schema_history}.
 *
 * <h2>Why this is not a tenant read model, stated plainly</h2>
 *
 * <p>{@code flyway_schema_history} has no {@code organizationId}. That is correct: it is
 * infrastructure state describing one database, and the whole database belongs to one deployment.
 * An operator asking "which migrations are applied" is asking about the process, not about a
 * tenant, so the read is not tenant-scoped — and this class is the only place in
 * {@code backend/administration} where that is true.
 *
 * <p>It is still safe to expose, because what it carries is a version number, a script name, a
 * checksum, a success flag and an install time. There is no row content in it and no tenant in it.
 * Contrast that with {@code QueueHealthService.expiredLeaseCount()}, which is the other deliberately
 * unscoped number in the system and exposes timing only; this one exposes less.
 *
 * <h2>Why read-only and never a repair</h2>
 *
 * <p>There is no {@code repair()}, no {@code info()}-and-act, and no statement here that writes.
 * The migration chain is owned by {@code ops/flyway} and the Flyway runbook; an Admin surface that
 * could mark a failed migration as applied would be the arbitrary database mutation FR-6 forbids.
 * Diagnosis is the whole job: an operator needs to know that {@code V20261005050000} applied at a
 * given time with a given checksum, and the remedy is the runbook, not a button.
 *
 * <h2>The timestamp cast</h2>
 *
 * <p>Flyway's PostgreSQL {@code schema_history} declares {@code installed_on} as
 * {@code TIMESTAMP}, but a deployment that stamped the table with a different Flyway build could
 * have {@code timestamptz}. The statement casts explicitly to {@code timestamp} in UTC so the mapper
 * can bind {@link LocalDateTime} (ADR-0002) without depending on which of the two it is — and the
 * cast is visible in review rather than hidden in a driver default.
 */
@Repository
public class MigrationStateRepository {

    private static final String APPLIED = """
        select "installed_rank", "version", "description", "type", "script", "checksum",
               "installed_by",
               cast("installed_on" as timestamp) as "installed_on",
               "execution_time", "success"
          from flyway_schema_history
         order by "installed_rank"
         limit :limit
        """;

    /**
     * Whether the chain has been stamped at all — the question ADR-0005 §2 turns on.
     *
     * <p>{@code stamped} is computed as a boolean in SQL rather than read back from {@code count(*)}.
     * The earlier spelling selected {@code count(*) as applied} and mapped {@code applied} with
     * {@code rs.getBoolean}, which the driver refuses on a bigint ("Cannot cast to boolean: 17") — so
     * this read threw and the migrations and overview routes answered 500. The count is still
     * selected, and it is selected <em>as</em> the number it is.
     */
    private static final String STAMPED = """
        select (coalesce(max("installed_rank"), 0) > 0) as "stamped", count(*) as "applied",
               coalesce(max("installed_rank"), 0) as "latest_rank"
          from flyway_schema_history
        """;

    /** Any migration that did not succeed. Must be empty; a non-empty answer is a page. */
    private static final String FAILED = """
        select count(*) as failures
          from flyway_schema_history
         where not "success"
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public MigrationStateRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One applied migration.
     *
     * @param version       {@code null} for the baseline marker, which Flyway stamps without a version
     * @param checksum      the script checksum; a change here means the file was edited after it ran
     * @param executionTime milliseconds
     */
    public record AppliedMigration(int installedRank,
                                   String version,
                                   String description,
                                   String type,
                                   String script,
                                   Integer checksum,
                                   String installedBy,
                                   LocalDateTime installedOn,
                                   Integer executionTime,
                                   boolean success) {
    }

    /** The whole applied chain, in installation order. */
    public List<AppliedMigration> applied(int limit) {
        return jdbc.query(APPLIED, new MapSqlParameterSource("limit", Math.max(1, limit)),
            (rs, rowNum) -> new AppliedMigration(
                rs.getInt("installed_rank"),
                rs.getString("version"),
                rs.getString("description"),
                rs.getString("type"),
                rs.getString("script"),
                rs.getObject("checksum", Integer.class),
                rs.getString("installed_by"),
                rs.getObject("installed_on", LocalDateTime.class),
                rs.getObject("execution_time", Integer.class),
                rs.getBoolean("success")));
    }

    /** The stamped/not-stamped answer and the applied count. */
    public StampState stamp() {
        return jdbc.queryForObject(STAMPED, new MapSqlParameterSource(), (rs, rowNum) ->
            new StampState(rs.getBoolean("stamped"), rs.getInt("applied"),
                rs.getInt("latest_rank")));
    }

    /** How many applied migrations report {@code success = false}. */
    public int failedCount() {
        Integer count = jdbc.queryForObject(FAILED, new MapSqlParameterSource(), Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * Whether the database is under Flyway.
     *
     * @param stamped   false means the baseline has not been stamped yet, which is the pre-boot
     *                  shape ADR-0005 §2 describes and not a failure
     * @param applied   how many history rows exist
     * @param latestRank the highest installed rank
     */
    public record StampState(boolean stamped, int applied, int latestRank) {
    }
}