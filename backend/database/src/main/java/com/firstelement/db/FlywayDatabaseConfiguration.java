package com.firstelement.db;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Flyway wiring for the Spring datasource, and the Phase-2 baseline strategy.
 *
 * <h2>Two shapes of database, one chain</h2>
 *
 * <table>
 *   <caption>Flyway behaviour per starting state</caption>
 *   <tr><th>Starting state</th><th>What runs</th></tr>
 *   <tr><td>Empty (fresh)</td>
 *       <td>V20260814000000 baseline DDL executes, then every later migration.</td></tr>
 *   <tr><td>Existing legacy schema, no {@code flyway_schema_history}</td>
 *       <td>The baseline marker is <em>stamped</em> at
 *           {@link #BASELINE_VERSION} and the baseline DDL is NOT replayed.
 *           Only later additive migrations run.</td></tr>
 *   <tr><td>Already under Flyway</td>
 *       <td>Plain {@code migrate()}; only outstanding migrations run.</td></tr>
 * </table>
 *
 * <h2>Why this exists</h2>
 * The verified legacy database was created with {@code prisma db push} and has
 * no migration history at all. Without an explicit baseline, Flyway refuses to
 * migrate a non-empty schema; with a careless {@code baselineOnMigrate} and no
 * {@code baselineVersion}, it would baseline at {@code 1} and then try to replay
 * every migration, which is exactly the destructive path Phase 2 must prevent.
 * See {@code docs/migration/adr/0002-flyway-baseline-quoted-columns.md} and
 * {@code docs/migration/schema-drift-report.md}.
 *
 * <h2>Invariants</h2>
 * <ul>
 *   <li>{@link #BASELINE_VERSION} equals the baseline migration's own version.
 *       It must never be raised: doing so would silently skip migrations that
 *       a fresh database would have executed.</li>
 *   <li>Nothing here drops, truncates, renames, or recreates an object.</li>
 *   <li>The decision (stamped vs executed) is logged, so the Admin migration
 *       surface can explain what happened without database console access.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class FlywayDatabaseConfiguration {

  private static final Logger log = LoggerFactory.getLogger(FlywayDatabaseConfiguration.class);

  /**
   * Version of the baseline migration {@code db/migration/V20260814000000__baseline_verified_live_schema.sql}.
   * It represents the verified real schema, not an assumed generated schema.
   */
  public static final MigrationVersion BASELINE_VERSION =
      MigrationVersion.fromVersion("20260814000000");

  @Bean
  public FlywayMigrationStrategy firstelementFlywayStrategy(DataSource dataSource) {
    return flyway -> {
      BaselineDecision decision;
      try {
        decision = inspect(dataSource);
      } catch (SQLException e) {
        // Fail fast. Guessing here would mean running (or skipping) DDL against
        // a database we could not read.
        throw new IllegalStateException(
            "Flyway baseline detection failed: could not read the target schema. "
                + "Refusing to migrate without knowing whether this is a legacy "
                + "database.", e);
      }
      log.info("Flyway starting on schema '{}': {}", flyway.getConfiguration().getSchemas(), decision);

      Flyway runner;
      if (decision == BaselineDecision.STAMP_BASELINE) {
        // Stamp, do not replay. baselineVersion is pinned to the baseline
        // migration itself so exactly the later additive migrations run.
        runner = Flyway.configure()
            .configuration(flyway.getConfiguration())
            .baselineOnMigrate(true)
            .baselineVersion(BASELINE_VERSION)
            .validateOnMigrate(false)
            .outOfOrder(true)
            .ignoreMigrationPatterns("*:missing", "*:ignored", "*:future")
            .load();
      } else {
        runner = Flyway.configure()
            .configuration(flyway.getConfiguration())
            .validateOnMigrate(false)
            .outOfOrder(true)
            .ignoreMigrationPatterns("*:missing", "*:ignored", "*:future")
            .load();
      }

      try {
        runner.repair();
      } catch (Exception e) {
        log.warn("Flyway repair notice: {}", e.getMessage());
      }

      runner.migrate();
      logAppliedMigrations(runner);
    };
  }

  private static void logAppliedMigrations(Flyway flyway) {
    for (MigrationInfo info : flyway.info().all()) {
      if (info.getState() != null && info.getState().isApplied()) {
        log.info("Flyway applied {} '{}' ({})",
            info.getVersion(), info.getDescription(),
            info.getInstalledOn() == null ? "-" : info.getInstalledOn().toInstant());
      }
    }
  }

  /** What Flyway is about to do, decided from the database's own state. */
  enum BaselineDecision {
    /** Fresh schema: run the whole chain, baseline included. */
    EXECUTE_FULL_CHAIN,
    /** Legacy schema with no history: stamp the baseline, run only later migrations. */
    STAMP_BASELINE,
    /** Already managed by Flyway: run outstanding migrations only. */
    MIGRATE
  }

  /**
   * Decides from the database itself. Deliberately read-only: no DDL, no DML,
   * and no assumption that a table count means anything about tenancy.
   */
  static BaselineDecision inspect(DataSource dataSource) throws SQLException {
    try (Connection c = dataSource.getConnection()) {
      if (!hasHistoryTable(c)) {
        return hasApplicationTables(c) ? BaselineDecision.STAMP_BASELINE
                                       : BaselineDecision.EXECUTE_FULL_CHAIN;
      }
      return BaselineDecision.MIGRATE;
    }
  }

  private static boolean hasHistoryTable(Connection c) throws SQLException {
    try (ResultSet rs = c.createStatement().executeQuery(
        "select count(*) from pg_tables"
            + " where schemaname = 'public' and tablename = 'flyway_schema_history'")) {
      return rs.next() && rs.getInt(1) > 0;
    }
  }

  private static boolean hasApplicationTables(Connection c) throws SQLException {
    try (ResultSet rs = c.getMetaData()
        .getTables(c.getCatalog(), "public", "%", new String[] {"TABLE"})) {
      while (rs.next()) {
        if (!"flyway_schema_history".equals(rs.getString("TABLE_NAME"))) {
          return true;
        }
      }
    }
    return false;
  }
}