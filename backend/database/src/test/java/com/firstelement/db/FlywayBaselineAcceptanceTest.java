package com.firstelement.db;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase-2 database fixture, extended by Phase 5a and Phase 6a.
 *
 * <p>Everything runs against a real {@code pgvector/pgvector:pg16} PostgreSQL 16
 * container. No skip path: if Docker is unreachable the build fails, per
 * AGENTS.md "a skipped migration test is not a pass".
 *
 * <ol>
 *   <li><b>Fresh database</b> - the Phase-2 contract first: migrate with
 *       {@code target = 20260814000100} and assert the historical baseline
 *       numbers EXACTLY as Phase 2 recorded them (45 tables, 64 tenant-led
 *       indexes, 62 FKs, the reconciled enums, one global index). Then migrate
 *       the rest of the chain and assert the Phase-5a and Phase-6a slices
 *       additively.</li>
 *   <li><b>Existing legacy database</b> - the shape the strangler cutover
 *       actually meets: tables present, <b>no</b> {@code flyway_schema_history},
 *       live rows. The baseline must be <em>stamped</em>, not replayed, and only
 *       the additive migrations may run. This is the test that proves the
 *       migration cannot destroy live data.</li>
 *   <li><b>Outbox claim</b> - the Phase-5a claim predicate against real rows:
 *       tenant scoping, lease, expiry, reaping, retry and the two-claimer
 *       concurrency proof.</li>
 *   <li><b>Dead letter and replay</b> - the terminal states and the audit trail
 *       the CHECK constraints enforce.</li>
 *   <li><b>Idempotency records</b> - tenant-scoped uniqueness, in-flight
 *       reservation, completion.</li>
 *   <li><b>Workflow versioning</b> (Phase 6a) - one ACTIVE version per
 *       definition, activation, an audited rollback, and the partial unique
 *       index as the backstop against a concurrent double activation.</li>
 *   <li><b>Workflow execution</b> (Phase 6a) - the named principal, the
 *       append-only action history, the human-takeover abort, and the composite
 *       tenant foreign keys that refuse a cross-tenant body or conversation.</li>
 *   <li><b>SLA clocks and calendars</b> (Phase 6a) - three independent clocks,
 *       the clock-ordering CHECK, and the same-day window CHECK, each with a
 *       negative test.</li>
 *   <li><b>Collisions</b> (Phase 6a) - the 5-minute deduplication window under
 *       two concurrent writers, and the cross-tenant negatives.</li>
 *   <li><b>Repository and packaged copies</b> are byte-identical.</li>
 * </ol>
 */
@Testcontainers
class FlywayBaselineAcceptanceTest {

  /** pgvector image: the target database contract requires the vector extension. */
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16"))
          .withDatabaseName("firstelement_fresh")
          .withUsername("firstelement")
          .withPassword("firstelement");

  /** A second, structurally identical database that emulates the legacy runtime's DB. */
  private static final String LEGACY_DB = "firstelement_legacy";

  /** A third database, migrated with the WHOLE chain, for behavioural tests. */
  private static final String PHASE5A_DB = "firstelement_phase5a";

  /**
   * The only tenant-less (V9 "global") indexes this schema may contain.
   * <ul>
   *   <li>{@code sessions_expiresAt_idx} - Phase 2. A session-expiry sweep has
   *       no tenant and must not require one.</li>
   *   <li>{@code outbox_events_dispatch_sweep_idx},
   *       {@code outbox_events_expired_lease_idx} - Phase 5a. Scheduler and
   *       reaper sweeps; state and timing only.</li>
   *   <li>{@code sla_calendar_windows_calendar_day_idx} - Phase 6a. The per
   *       clock-evaluation window read, reachable only after an
   *       organizationId-filtered calendar row has been read, carrying no
   *       tenant data.</li>
   * </ul>
   * Each is documented in its migration; this list is what stops a fourth (or
   * fifth) one appearing by accident, and V11 section 5a asserts the same list
   * independently.
   */
  private static final List<String> ALLOWED_TENANT_LESS_INDEXES =
      List.of("sessions_expiresAt_idx",
              "outbox_events_dispatch_sweep_idx",
              "outbox_events_expired_lease_idx",
              "sla_calendar_windows_calendar_day_idx",
              "admin_audit_events_correlationId_idx");

  /** The nine tables the Phase-6a migrations create. */
  private static final List<String> PHASE6A_TABLES =
      List.of("workflow_definitions", "workflow_versions",
              "workflow_runs", "workflow_run_actions",
              "sla_calendars", "sla_calendar_windows",
              "sla_calendar_holidays", "sla_policies",
              "collision_records");

  /**
   * The two tables the Phase-10b schema remediation adds (D3: FR-6 asks for API keys and
   * service principals, and Phase 7 reported both absent).
   *
   * <p>Asserted as an exact pair rather than a count, for the same reason the Phase-7-10a list is
   * an exact set: "2 tables were added" and "these 2 tables were added" are different claims and
   * only the second one detects a rename.
   */
  private static final List<String> PHASE10B_TABLES =
      List.of("api_keys", "service_principals");

  /**
   * The twenty-seven files of the reviewed chain, in one list.
   *
   * <p>Kept as a named constant rather than an inline literal at the point of use so the
   * byte-identity check and the migration-count assertions cannot drift apart: a hand-typed list is
   * how a new migration gets left out of the check and is only discovered when someone boots
   * against a classpath holding a copy nobody reviewed. The packaged-side "nothing extra" check is
   * the other half and stays separate.
   */
  private static final List<String> CHAIN_FILES = List.of(
      "V20260814000000__baseline_verified_live_schema.sql",
      "V20260814000100__vector_indexes.sql",
      "V20261004000100__outbox.sql",
      "V20261004000700__job_attempts.sql",
      "V20261004001000__idempotency_records.sql",
      "V20261005010000__workflow_definitions_and_versions.sql",
      "V20261005020000__workflow_runs_and_action_results.sql",
      "V20261005030000__sla_calendars.sql",
      "V20261005040000__collision_records.sql",
      "V20261005050000__audit_streams.sql",
      "V20261005060000__report_definitions.sql",
      "V20261005070000__export_jobs.sql",
      "V20261005080000__usage_ledger.sql",
      "V20261005090000__finance_rate_cards_periods.sql",
      "V20261005100000__ai_policy_and_governance.sql",
      "V20261005110000__ai_run_child_tenant_scope_columns.sql",
      "V20261005120000__ai_run_child_tenant_scope_indexes.sql",
      "V20261006100000__ai_approval_decision_biconditional_repair.sql",
      "V20261006110000__document_chunks_document_tenant_fkey.sql",
      "V20261006120000__api_keys_and_service_principals.sql",
      "V20261006130000__legal_holds_scope_pair_widening.sql",
      "V20261006140000__privacy_deletion_subject_reference.sql",
      "V20261007100000__customer_cx_sequences.sql",
      "V20261007110000__customers_cx_id_tenant_scope.sql",
      "V20261009110000__appointments_tenant_composite_fkeys.sql",
      "V20261009130000__user_role_clinic_staff.sql",
      // Stage 1 of the clinic/platform work. Named here so the byte-identity check between
      // ops/flyway/sql and the packaged classpath chain covers it: an unnamed migration is one
      // that can drift between the two copies without anything noticing.
      "V20261012000000__platform_operator_tier.sql",
        "V20261013000000__clinic_patients.sql",
        "V20261014000000__clinic_odontogram.sql",
        "V20261015000000__clinic_odontogram_vocabulary_project_scope.sql",
        "V20261016000000__clinic_schedules_time_off_resources.sql",
        "V20261017000000__clinic_schedules_no_overlap.sql",
        "V20261018000000__clinic_finding_vocabulary_english_template.sql",
        "V20261019000000__appointments_status_dec003_and_cancel_columns.sql",
        "V20261020000000__clinic_treatment_plans.sql",
        "V20261021000000__clinic_encounters.sql",
        "V20261022000000__clinic_invoices_and_payments.sql",
        "V20261023000000__clinic_policies.sql",
        "V20261024000000__clinic_patient_family_consent.sql",
        "V20261025000000__clinic_resources_holds_waitlist_reminders.sql",
        "V20261026000000__clinic_medical_history_vitals_note_amendments.sql",
        "V20261027000000__clinic_periodontics_probing_charts.sql",
        "V20261028000000__clinic_treatment_plan_phases_consent.sql",
        "V20261029000000__clinic_billing_refunds_ledger_statement.sql",
        "V20261030000000__clinic_documents_media_radiographs.sql",
        "V20261031000000__clinic_audit_break_glass_reports.sql",
        "V20261101000000__clinic_governed_ai_assistant.sql",
        "V20261102000000__clinic_locations_and_service_catalogue.sql",
        "V20261103000000__doctor_profiles_and_front_desk.sql",
        "V20261104000000__patient_profiles_and_appointment_commerce.sql",
        "V20261105000000__clinic_rbac_roles_permissions_and_audit.sql");

  /**
   * The thirty-seven tables the Phase-7-10a migrations create (Agent B).
   *
   * <p>Asserted as an exact set, not as a count, because "37 tables were added" and
   * "these 37 tables were added" are different claims and only the second one
   * detects a table that was renamed.
   */
  private static final List<String> PHASE710A_TABLES =
      List.of("retention_classes", "legal_holds", "legal_hold_events",
              "privacy_deletion_requests", "privacy_deletion_targets",
              "admin_audit_events", "security_events", "job_integration_events",
              "report_definitions", "report_versions",
              "document_templates", "document_template_versions",
              "export_jobs", "export_job_attempts",
              "export_download_events", "export_deletion_events",
              "usage_events", "rate_cards", "rate_card_versions",
              "rate_card_version_lines", "finance_periods",
              "finance_period_lock_events", "usage_rollup_snapshots",
              "finance_adjustments", "finance_reconciliation_runs",
              "finance_reconciliation_lines",
              "ai_policies", "ai_policy_versions", "ai_model_registry",
              "ai_budgets", "ai_budget_consumptions", "ai_approvals",
              "ai_approval_decisions", "ai_redaction_records",
              "ai_provenance_records", "ai_tool_registry", "ai_tool_executions");

  /**
   * The eleven Phase-7-10a tables that MUST carry no {@code updatedAt}.
   *
   * <p>This is the whole of ADR-0007 Part 1's immutability claim expressed as
   * table shape: revenue, cost, approval and refusal are things that HAPPENED, and
   * a column that lets a writer amend them is the failure mode each of those
   * tables exists to prevent. The finance slice additionally backs it with a
   * trigger, and the fixture proves both halves.
   */
  private static final List<String> PHASE710A_APPEND_ONLY_TABLES =
      List.of("admin_audit_events", "job_integration_events", "legal_hold_events",
              "privacy_deletion_targets", "security_events",
              "export_job_attempts", "export_download_events",
              "export_deletion_events", "report_versions",
              "document_template_versions", "usage_events",
              "rate_card_versions", "rate_card_version_lines",
              "finance_period_lock_events", "usage_rollup_snapshots",
              "finance_reconciliation_runs", "finance_reconciliation_lines",
              "ai_policy_versions", "ai_budget_consumptions",
              "ai_approval_decisions", "ai_redaction_records",
              "ai_provenance_records", "ai_tool_executions");

  /**
   * The Phase-7-10a tables that MUST have {@code updatedAt}: the seven state
   * machines. A hold that can be released, a request that can be completed and a
   * job that can be cancelled are all mutable BY DESIGN, and the boundary is
   * asserted as an exact map so it cannot silently collapse in either direction.
   */
  private static final List<String> PHASE710A_MUTABLE_TABLES =
      List.of("retention_classes", "legal_holds", "privacy_deletion_requests",
              "report_definitions", "document_templates", "export_jobs",
              "rate_cards", "finance_periods", "finance_adjustments",
              "ai_policies", "ai_model_registry", "ai_budgets",
              "ai_approvals", "ai_tool_registry");

  private static Path sqlDir;

  /** JDBC url of the behavioural database. */
  private static String phase5aUrl;

  @BeforeAll
  static void locateSqlAndMigrateBehaviouralDatabase() throws Exception {
    String dir = System.getProperty("opsFlywayDir");
    assertThat(dir).as("opsFlywayDir system property (set by :backend:database)").isNotNull();
    sqlDir = Path.of(dir, "sql");
    assertThat(Files.isDirectory(sqlDir)).as("Flyway location %s", sqlDir).isTrue();

    // One database, migrated once, for every behavioural test. Each test seeds
    // its own tenant and its own idempotency keys, so the tests are independent
    // of execution order.
    phase5aUrl = createSiblingDatabase(PHASE5A_DB);
    MigrateResult result = flyway(phase5aUrl).migrate();
    assertThat(result.success).as("full chain on a fresh database").isTrue();
    // 25 migrations: baseline, vector index, three Phase-5a, four Phase-6a,
    // eight Phase-7-10a, five Phase-10b, two Phase-14 and one Phase-35. The
    // AI-run child scope is TWO versions (columns, then indexes) because Flyway
    // cannot run CREATE INDEX CONCURRENTLY on this host -- see the two migration
    // headers for the full measurement. The Phase-10b batch is five more because a
    // migration in this chain that has been applied must never be amended: each of
    // the five defects gets its own version. The Phase-14 pair is the P0 customer
    // reference namespace (ADR-0014): the counter table and its atomic allocator,
    // then the backfill plus the uniqueness rescope. Phase 35 adds the four
    // composite tenant foreign keys on `appointments` -- all four of its baseline
    // foreign keys were single-column, so nothing stopped an appointment naming a
    // doctor, customer, user or procedure in another tenant. It is ONE version
    // because it adds no data and refuses rather than repairs if residue exists.
    // Phase 38 adds one enum VALUE, CLINIC_STAFF, and grants it to nobody: Phase 33 named
    // the role in 18 permissions but never added it, so those permissions were held by no
    // role at all. It is ONE version because it is one statement -- deliberately, since
    // PostgreSQL 12 allows ALTER TYPE ... ADD VALUE in a transaction but forbids USING the
    // new value until that transaction commits.
    // Stage 1 of the clinic/platform work adds ONE version, V20261012000000, creating
    // platform_operators, platform_operator_capabilities, platform_sessions and
    // platform_audit_events. It is the first migration in this chain that deliberately does NOT
    // carry a NOT NULL organizationId, because a platform operator belongs to no tenant; the
    // reasoning is in the migration header and in ADR 0020.
    assertThat(result.migrationsExecuted)
        .as("baseline + vector index + Phase 5a (3) + Phase 6a (4) + Phase 7-10a (8) "
            + "+ Phase 10b (5) + Phase 14 (2) + Phase 35 (1) + Phase 38 (1) + platform tier (1)"
                + " + clinic patients (1) + clinic odontogram (1)"
                + " + clinic locations and service catalogue (1)"
                + " + doctor profiles and front desk (1)"
                + " + patient profiles and appointment commerce (1)"
                + " + clinic rbac roles permissions and audit (1)")
        .isEqualTo(51);
  }

  // ------------------------------------------------------------------
  // Scenario 1: a brand new database accepts the whole chain
  // ------------------------------------------------------------------

  @Test
  @DisplayName("fresh pgvector database migrates to the verified real schema, then additively to the Phase-5a slice")
  void freshDatabaseAcceptsFlywayBaselineAndVectorIndexes() throws Exception {
    // --- stage 1: the Phase-2 contract, asserted at its historical numbers ---
    MigrateResult baseline = Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("filesystem:" + sqlDir)
        .baselineOnMigrate(false)
        .target(MigrationVersion.fromVersion("20260814000100"))
        .load()
        .migrate();

    assertThat(baseline.success).as("migrate() success").isTrue();
    assertThat(baseline.migrationsExecuted)
        .as("baseline + additive vector index")
        .isEqualTo(2);

    try (Connection c = connect(POSTGRES.getJdbcUrl())) {
      // -- physical shape ------------------------------------------------
      assertThat(queryInt(c, """
          select count(*) from pg_tables
           where schemaname = 'public' and tablename <> 'flyway_schema_history'"""))
          .as("application tables in the baseline")
          .isEqualTo(45);

      // -- the reconciled enum drift ------------------------------------
      // The quarantined Prisma init migration said AVAILABLE/BUSY/AWAY/OFFLINE.
      // Those labels must never reach the target contract.
      assertThat(queryStrings(c, enumQuery("EmployeeAvailabilityStatus")))
          .containsExactly("ONLINE", "OFFLINE", "BUSY", "BREAK", "MEAL", "TRAINING", "MEETING");
      assertThat(queryStrings(c, enumQuery("AIRunDecision")))
          .containsExactly("REPLY", "ASK_CLARIFICATION", "ESCALATE", "NO_ACTION", "RESOLVE");
      assertThat(queryInt(c, """
          select count(*) from pg_type t
            join pg_namespace n on n.oid = t.typnamespace
           where n.nspname = 'public' and t.typname = 'anyenum'""")).isZero();
      assertThat(queryInt(c, """
          select count(*) from pg_enum e
            join pg_type t on t.oid = e.enumtypid
           where t.typname = 'EmployeeAvailabilityStatus'
             and e.enumlabel in ('AVAILABLE', 'AWAY')"""))
          .as("quarantined init-migration enum labels must be absent")
          .isZero();

      // -- extensions and pgvector --------------------------------------
      assertThat(queryStrings(c, "select extname from pg_extension order by extname"))
          .contains("pgcrypto", "vector");
      assertThat(queryInt(c,
          "select count(*) from information_schema.columns"
              + " where table_schema='public' and table_name='document_chunks'"
              + "   and udt_name='vector'"))
          .as("embedding column is a pgvector vector")
          .isEqualTo(1);

      // -- indexes ------------------------------------------------------
      assertThat(queryInt(c,
          "select count(*) from pg_indexes"
              + " where schemaname='public' and indexname='document_chunks_embedding_hnsw_idx'"))
          .as("HNSW cosine index restored by V20260814000100")
          .isEqualTo(1);
      assertThat(queryInt(c,
          "select count(*) from pg_indexes"
              + " where schemaname='public' and tablename='document_chunks'"
              + "   and indexname in ('document_chunks_tenant_idx',"
              + "                        'document_chunks_organizationId_projectId_knowledgeBaseId_idx')"))
          .as("tenant/project pre-filter index present")
          .isEqualTo(1);
      assertThat(queryInt(c,
          "select count(*) from pg_indexes"
              + " where schemaname='public' and indexname='document_chunks_tenant_idx'"))
          .as("no redundant duplicate pre-filter index was created")
          .isZero();

      // -- tenant scope -------------------------------------------------
      // The four tables with no organizationId column are the documented exceptions:
      //   organizations    - IS the tenant root
      //   ai_runs          - scoped by its conversation
      //   ai_run_steps /
      //   ai_run_retrievals /
      //   ai_run_tool_calls - scoped by their ai_runs parent
      // The other 41 all carry NOT NULL organizationId (FR-1.2).
      assertThat(queryStrings(c, """
          select c.relname from pg_class c
            join pg_namespace n on n.oid = c.relnamespace
           where n.nspname = 'public' and c.relkind = 'r'
             and c.relname <> 'flyway_schema_history'
             and not exists (select 1 from pg_attribute a
                             where a.attrelid = c.oid and a.attnum > 0
                               and not a.attisdropped
                               and a.attname = 'organizationId')
           order by c.relname"""))
          .containsExactly("ai_run_retrievals", "ai_run_steps", "ai_run_tool_calls", "organizations");

      assertThat(queryInt(c, """
          select count(*) from pg_attribute a
            join pg_class c on c.oid = a.attrelid
            join pg_namespace n on n.oid = c.relnamespace
           where n.nspname = 'public' and c.relkind = 'r'
             and c.relname <> 'flyway_schema_history'
             and a.attname = 'organizationId' and not a.attnotnull"""))
          .as("organizationId is NOT NULL wherever it exists")
          .isZero();

      // -- project scope -------------------------------------------------
      assertThat(queryStrings(c, """
          select c.relname from pg_class c
            join pg_namespace n on n.oid = c.relnamespace
           where n.nspname = 'public' and c.relkind = 'r'
             and c.relname <> 'flyway_schema_history'
             and not exists (select 1 from pg_attribute a
                             where a.attrelid = c.oid and a.attnum > 0
                               and not a.attisdropped
                               and a.attname = 'projectId')
           order by c.relname"""))
          .as("tables whose scope is inherited rather than duplicated")
          .containsExactly("accounts", "agent_status_sessions", "ai_run_retrievals", "ai_run_steps",
                           "ai_run_tool_calls", "employee_availability", "employee_skills", "employees",
                           "organizations", "projects", "sessions", "users");

      // The only nullable projectId columns are the two organization-wide streams.
      assertThat(queryStrings(c, """
          select c.relname from pg_class c
            join pg_namespace n on n.oid = c.relnamespace
            join pg_attribute a on a.attrelid = c.oid and a.attnum > 0
                                and not a.attisdropped and a.attname = 'projectId'
           where n.nspname = 'public' and c.relkind = 'r' and not a.attnotnull
           order by c.relname"""))
          .as("nullable projectId columns (backfill target, not baseline DDL)")
          .containsExactly("audit_logs", "notifications");

      // -- index scope census --------------------------------------------
      // 64 tenant-led + 15 project-led + 22 parent-key + 53 unique-other + 1
      // global + 1 vector-led (this migration) = 156. The single global index
      // is sessions_expiresAt_idx: a session-expiry sweep has no tenant and
      // must stay reachable without one.
      assertThat(queryInt(c,
          "select count(*) from pg_indexes where schemaname='public'"
              + "   and tablename <> 'flyway_schema_history'"
              + "   and indexdef like '%USING btree (\"organizationId\"%'"))
          .as("tenant-led indexes")
          .isEqualTo(64);
      assertThat(queryInt(c,
          "select count(*) from pg_indexes where schemaname='public'"
              + "   and tablename <> 'flyway_schema_history'"
              + "   and indexdef like '%USING btree (\"projectId\"%'"))
          .as("project-led indexes")
          .isEqualTo(15);
      assertThat(queryStrings(c, """
          select indexname from pg_indexes
           where schemaname = 'public'
             and tablename <> 'flyway_schema_history'
             and indexdef not like '%USING btree ("organizationId"%'
             and indexdef not like '%USING btree ("projectId"%'
             and indexdef not like '%USING hnsw%'
             and indexdef not like '%USING btree ("runId"%'
             and indexdef not like '%USING btree ("conversationId"%'
             and indexdef not like '%USING btree ("employeeId"%'
             and indexdef not like '%USING btree ("userId"%'
             and indexdef not like '%USING btree ("knowledgeBaseId"%'
             and indexdef not like '%USING btree ("agentVersionId"%'
             and indexdef not like '%USING btree ("customerId"%'
             and indexdef not like '%UNIQUE%'
           order by indexname"""))
          .as("indexes reachable without any tenant column")
          .containsExactly("sessions_expiresAt_idx");

      // -- referential integrity ----------------------------------------
      assertThat(queryInt(c, """
          select count(*) from pg_constraint con
            join pg_class c on c.oid = con.conrelid
            join pg_namespace n on n.oid = c.relnamespace
           where n.nspname = 'public' and con.contype = 'f'"""))
          .as("foreign keys present in the baseline")
          .isEqualTo(62);

      // -- business keys preserved --------------------------------------
      assertThat(queryInt(c,
          "select count(*) from information_schema.columns"
              + " where table_schema='public' and column_name='id' and data_type='uuid'"))
          .as("uuid primary keys")
          .isEqualTo(45);
      assertThat(queryInt(c,
          "select character_maximum_length from information_schema.columns"
              + " where table_schema='public' and table_name='conversations'"
              + "   and column_name='ticketNumber'"))
          .isEqualTo(14);
    }

    // --- stage 2: the Phase-5a, Phase-6a and Phase-7-10a slices, applied additively ---
    MigrateResult phase5a = flyway(POSTGRES.getJdbcUrl()).migrate();
    assertThat(phase5a.success).as("migrate() success for the whole chain").isTrue();
    assertThat(phase5a.migrationsExecuted)
        .as("V202610040001 outbox + V202610040007 jobs + V202610040010 idempotency"
            + " + four Phase-6a + eight Phase-7-10a + five Phase-10b + two Phase-14"
            + " + one Phase-35"
            + " + one Phase-35 + one Phase-38"
            + " + one Stage-1 platform tier"
            + " + one Stage-1 clinic schedules and resources"
            + " + one Stage-1 schedule overlap constraint"
            + " + one Stage-1 English finding vocabulary template"
            + " + one Stage-1 DEC-003 appointment status and cancellation columns"
            + " + one clinic locations and service catalogue"
            + " + one doctor profiles and front desk"
            + " + one patient profiles and appointment commerce"
            + " + one clinic rbac roles permissions and audit"
            + " = 32. The baseline and the vector index were applied in the previous stage, so"
            + " this is the ADDITIVE count and not the chain length of 35.")
        .isEqualTo(49);
    try (Connection c = connect(POSTGRES.getJdbcUrl())) {
      assertThat(queryInt(c, """
          select count(*) from pg_tables
           where schemaname = 'public' and tablename <> 'flyway_schema_history'"""))
          .as("45 baseline + 4 Phase-5a + 9 Phase-6a + 37 Phase-7-10a + 2 Phase-10b"
              + " + 1 Phase-14 (customer_cx_sequences) + 3 clinic patients"
              + " + 1 clinic_locations + 1 doctor_qualifications + 5 clinic rbac tables")
        .isEqualTo(146);
      assertThat(queryStrings(c,
          "select tablename from pg_tables where schemaname='public'"
              + "   and tablename in ('outbox_events','job_attempts','job_dead_letters',"
              + "                      'idempotency_records') order by tablename"))
          .containsExactly("idempotency_records", "job_attempts", "job_dead_letters", "outbox_events");
      assertThat(queryStrings(c,
          "select tablename from pg_tables where schemaname='public'"
              + "   and tablename in ('workflow_definitions','workflow_versions','workflow_runs',"
              + "                      'workflow_run_actions','sla_calendars','sla_calendar_windows',"
              + "                      'sla_calendar_holidays','sla_policies','collision_records')"
              + " order by tablename"))
          .as("all nine Phase-6a tables exist")
          .containsExactlyElementsOf(PHASE6A_TABLES.stream().sorted().toList());
      assertThat(queryStrings(c,
          "select tablename from pg_tables where schemaname='public'"
              + "   and tablename in (" + quotedCsv(PHASE710A_TABLES) + ") order by tablename"))
          .as("all thirty-seven Phase-7-10a tables exist, as an exact set rather than a count")
          .containsExactlyElementsOf(PHASE710A_TABLES.stream().sorted().toList());
      assertThat(queryStrings(c,
          "select tablename from pg_tables where schemaname='public'"
              + "   and tablename in (" + quotedCsv(PHASE10B_TABLES) + ") order by tablename"))
          .as("the two Phase-10b credential tables exist, as an exact pair")
          .containsExactlyElementsOf(PHASE10B_TABLES.stream().sorted().toList());

      // The Phase-2 enums are untouched; 4 Phase-5a and 7 Phase-6a ones exist.
      assertThat(queryStrings(c, enumQuery("EmployeeAvailabilityStatus")))
          .as("Phase-2 enum contract survives the Phase-5a and Phase-6a migrations")
          .containsExactly("ONLINE", "OFFLINE", "BUSY", "BREAK", "MEAL", "TRAINING", "MEETING");
      assertThat(queryInt(c, """
          select count(distinct t.typname) from pg_type t
            join pg_namespace n on n.oid = t.typnamespace
            join pg_enum e on e.enumtypid = t.oid
           where n.nspname = 'public'"""))
        .as("23 baseline + 4 Phase-5a + 7 Phase-6a + 21 Phase-7-10a + 2 Phase-10b + 5 clinic odontogram enum types")
        .isEqualTo(62);
      assertThat(queryStrings(c, enumQuery("OutboxState")))
          .as("OutboxState is the dispatcher contract")
          .containsExactly("PENDING", "LEASED", "SENT", "DEAD");
      assertThat(queryStrings(c, enumQuery("JobAttemptOutcome")))
          .containsExactly("SUCCEEDED", "RETRYABLE_FAILURE", "NON_RETRYABLE_FAILURE",
                           "CANCELLED", "SKIPPED");
      assertThat(queryStrings(c, enumQuery("DeadLetterStatus")))
          .containsExactly("OPEN", "REPLAYED", "RESOLVED", "DISCARDED");
      assertThat(queryStrings(c, enumQuery("IdempotencyState")))
          .containsExactly("IN_PROGRESS", "COMPLETED", "FAILED");

      // Phase-6a enum contracts. These ARE the Phase-6 vocabulary: a drift here
      // changes engine behaviour silently, so they are asserted exactly.
      assertThat(queryStrings(c, enumQuery("WorkflowVersionState")))
          .containsExactly("DRAFT", "ACTIVE", "SUPERSEDED", "ROLLED_BACK", "ARCHIVED");
      assertThat(queryStrings(c, enumQuery("WorkflowRunStatus")))
          .containsExactly("RUNNING", "WAITING", "SUCCEEDED", "FAILED", "ABORTED", "TAKEOVER");
      assertThat(queryStrings(c, enumQuery("WorkflowActionOutcome")))
          .as("RETRYABLE is an outcome, not a run status: collapsing it into FAILED "
              + "would break the do-not-retry-a-non-retryable-failure rule")
          .containsExactly("SUCCEEDED", "FAILED", "SKIPPED", "RETRYABLE");
      assertThat(queryStrings(c, enumQuery("SlaCalendarState")))
          .as("DRAFT comes FIRST and is not optional: without it there is no way to "
              + "author calendar version 2 while version 1 is live, because inserting it "
              + "as ACTIVE is refused by sla_calendars_one_active_per_key. The throwaway "
              + "rehearsal found that deadlock in the design.")
          .containsExactly("DRAFT", "ACTIVE", "SUPERSEDED", "ARCHIVED");
      assertThat(queryStrings(c, enumQuery("SlaClockType")))
          .as("the three INDEPENDENT clocks are a closed contract the scheduler branches on")
          .containsExactly("FIRST_RESPONSE", "NEXT_RESPONSE", "RESOLUTION");
      assertThat(queryStrings(c, enumQuery("CollisionScope")))
          .containsExactly("CONVERSATION", "ASSIGNMENT", "CASE", "CUSTOMER");
      assertThat(queryStrings(c, enumQuery("CollisionResolution")))
          .containsExactly("KEPT_BOTH", "WINS", "LOSES", "ABORTED_BY_HUMAN", "NOOP");

      // Phase-7-10a enum contracts. A drift in any of these changes behaviour
      // silently -- an export state machine, a rate-card lifecycle, a denial
      // decision -- so each list is asserted exactly.
      assertThat(queryStrings(c, enumQuery("AuditOutcome")))
          .containsExactly("SUCCESS", "DENIED", "FAILED");
      assertThat(queryStrings(c, enumQuery("AuditPrincipalType")))
          .as("plan.md forbids an unrestricted scheduled identity, so the principal "
              + "vocabulary is a closed list rather than free text")
          .containsExactly("USER", "SERVICE", "AI_AGENT", "JOB", "CONNECTOR", "SYSTEM");
      assertThat(queryStrings(c, enumQuery("AuditStreamKind")))
          .as("FINANCE and PRIVACY are declared BEFORE the finance tables exist, so a "
              + "retention class can name them before Phase 8 lands")
          .containsExactly("ADMIN", "SECURITY", "JOB_INTEGRATION", "FINANCE", "PRIVACY");
      assertThat(queryStrings(c, enumQuery("LegalHoldScopeType")))
          .containsExactly("TENANT", "PROJECT", "TABLE", "RECORD");
      assertThat(queryStrings(c, enumQuery("PrivacyDeletionRequestStatus")))
          .containsExactly("REQUESTED", "VERIFIED", "IN_PROGRESS", "COMPLETED",
                           "REJECTED", "CANCELLED");
      assertThat(queryStrings(c, enumQuery("ReportStatus")))
          .as("DRAFT comes FIRST: without it there is no way to author version 2 while "
              + "version 1 is published, because inserting it as PUBLISHED is refused by "
              + "report_versions_one_published_per_definition. The Phase-6a rehearsal found "
              + "exactly that deadlock in the SLA calendar.")
          .containsExactly("DRAFT", "PUBLISHED", "RETIRED");
      assertThat(queryStrings(c, enumQuery("DocumentTemplateStatus")))
          .containsExactly("DRAFT", "PUBLISHED", "RETIRED");
      assertThat(queryStrings(c, enumQuery("ExportJobStatus")))
          .as("CANCELLED, EXPIRED and DELETED are three separate states on purpose: "
              + "collapsing any two of them changes what the Admin screen can say about why "
              + "an export is gone")
          .containsExactly("QUEUED", "RUNNING", "COMPLETED", "FAILED", "CANCELLED",
                           "EXPIRED", "DELETED");
      assertThat(queryStrings(c, enumQuery("ExportFormat")))
          .containsExactly("CSV", "XLSX", "DOCX", "PDF", "JSON");
      assertThat(queryStrings(c, enumQuery("ExportDownloadOutcome")))
          .containsExactly("GRANTED", "DENIED", "EXPIRED", "DELETED");
      assertThat(queryStrings(c, enumQuery("ExportDeletionOutcome")))
          .containsExactly("STORAGE_OBJECT_DELETED", "STORAGE_DELETE_FAILED",
                           "ROW_TOMBSTONED");
      assertThat(queryStrings(c, enumQuery("UsageDimension")))
          .as("UNPRICED MUST NOT BE HERE. Unpricedness is the ABSENCE of a rate at the "
              + "usage date, not a kind of usage: as a dimension it becomes a countable "
              + "unit, and countable categories get treated as zero. It lives on the "
              + "rollup instead, as the absence of a pinned rate.")
          .containsExactly("MESSAGE", "CONVERSATION", "CHANNEL_TRAFFIC", "SEAT",
                           "STORAGE", "API_OPERATION", "WORKFLOW_RUN", "REPORT_EXPORT",
                           "AI_REQUEST", "AI_TOKEN", "TIME_ENTRY", "DOCUMENT_CHUNK");
      assertThat(queryStrings(c, enumQuery("RateCardStatus")))
          .containsExactly("DRAFT", "ACTIVE", "RETIRED");
      assertThat(queryStrings(c, enumQuery("PeriodStatus")))
          .containsExactly("OPEN", "IN_REVIEW", "LOCKED", "REOPENED");
      assertThat(queryStrings(c, enumQuery("RollupGrain")))
          .containsExactly("DAILY", "MONTHLY");
      assertThat(queryStrings(c, enumQuery("ReconciliationStatus")))
          .containsExactly("MATCHED", "MISMATCH", "ACCEPTED");
      assertThat(queryStrings(c, enumQuery("AdjustmentStatus")))
          .containsExactly("PENDING", "APPROVED", "REJECTED", "APPLIED");
      assertThat(queryStrings(c, enumQuery("AiPolicyDecision")))
          .containsExactly("ALLOW", "REQUIRE_APPROVAL", "DENY");
      assertThat(queryStrings(c, enumQuery("AiApprovalStatus")))
          .containsExactly("PENDING", "APPROVED", "REJECTED", "EXPIRED", "REVOKED");
      assertThat(queryStrings(c, enumQuery("AiRiskLevel")))
          .containsExactly("LOW", "MEDIUM", "HIGH", "CRITICAL");
      assertThat(queryStrings(c, enumQuery("AiBudgetPeriod")))
          .containsExactly("DAILY", "MONTHLY");
      // The two Phase-10b vocabularies. A drift in either changes what the auth boundary and the
      // admin screen branch on, silently.
      assertThat(queryStrings(c, enumQuery("ServicePrincipalStatus")))
          .as("ACTIVE must be the default state and the other two must be non-ACTIVE, or the "
              + "suspension biconditional cannot be total")
          .containsExactly("ACTIVE", "SUSPENDED", "DISABLED");
      assertThat(queryStrings(c, enumQuery("ApiKeyStatus")))
          .as("REVOKED and EXPIRED are separate states because each carries its own evidence "
              + "triple; collapsing them removes the reason the column existed")
          .containsExactly("ACTIVE", "REVOKED", "EXPIRED");

      // Shape: FKs, PKs, and the CHECK constraints.
      assertThat(queryInt(c, """
          select count(*) from pg_constraint con
            join pg_class c on c.oid = con.conrelid
            join pg_namespace n on n.oid = c.relnamespace
           where n.nspname = 'public' and con.contype = 'f'"""))
          .as("62 baseline + 14 Phase-5a + 37 Phase-6a + 98 Phase-7-10a + 11 Phase-10b"
              + " + 1 Phase-14 (customer_cx_sequences_organizationId_fkey)"
              + " + 4 Phase-35 (the composite tenant FKs on appointments: createdBy,"
              + "customer, doctor and procedure -- all four of its baseline FKs were"
              + " single-column, so nothing stopped an appointment naming a row in"
              + " another tenant) + 3 clinic patients + 4 clinic odontogram (patient, procedure,"
               + " vocabulary and self-supersession, each composite on the tenant column)"
               + " + 4 clinic locations and service catalogue (organization, project, front-desk"
               + " officer, and procedure to location) + 3 doctor profiles and front desk"
               + " (qualification to doctor, doctor to clinic, doctor to service)"
               + " + 2 patient profiles and appointment commerce (appointment to patient"
               + " and appointment to clinic, both composite on the tenant column)"
               + " + 3 clinic rbac FKs")
        .isEqualTo(361);
      assertThat(queryInt(c, """
          select count(*) from pg_constraint con
            join pg_class c on c.oid = con.conrelid
            join pg_namespace n on n.oid = c.relnamespace
           where n.nspname = 'public' and con.contype = 'p'
             and c.relname <> 'flyway_schema_history'"""))
          .as("45 baseline + 4 + 9 + 37 + 2 + 1 + 1 clinic_locations + 1 doctor_qualifications + 5 clinic rbac PKs; "
              + "flyway_schema_history has its own and is not the application contract")
        .isEqualTo(146);
      assertThat(queryInt(c, """
          select count(*) from pg_constraint con
            join pg_class c on c.oid = con.conrelid
            join pg_namespace n on n.oid = c.relnamespace
           where n.nspname = 'public' and con.contype = 'c'"""))
          .as("14 Phase-5a + 57 Phase-6a + 331 Phase-7-10a + 19 Phase-10b + 1 Phase-14 CHECK "
              + "constraints. Phase-10b nets +19 because two of its five migrations REPLACE a "
              + "CHECK rather than adding one (ai_approvals_approval_check and "
              + "legal_holds_scope_pair_check: PostgreSQL cannot edit a CHECK expression, so the "
              + "count is unchanged and only the definition moves), and D3's two new tables carry "
              + "18 of its own. Phase-14 adds exactly ONE: "
              + "customer_cx_sequences_sequence_range_check, which pins the reference sequence to "
              + "the published `#33` + six-digit format (0..999999). The Phase-7-10a count is "
              + "deliberately large because each one encodes an invariant no correct writer can "
              + "violate and every one has a negative test in this fixture. The clinic tiers add six:"
              + " patients_legal_hold_blocks_deletion plus the odontogram's canonical-FDI range,"
              + " no-self-supersede and non-blank-vocabulary-label checks."
              + " The clinic master data adds four: the location slot range, the service type,"
              + " the discount range and the per-service time slot range."
              + " Doctor profiles and front desk add ten: six doctor ranges/vocabularies,"
              + " two qualification checks, and two user profile checks."
              + " Patient profiles and appointment commerce add four: the gender vocabulary,"
              + " the quantity range, the non-negative price, and the payment-state vocabulary.")
        .isEqualTo(552);
      assertThat(queryInt(c, """
          select count(*) from pg_constraint con
            join pg_class c on c.oid = con.conrelid
            join pg_namespace n on n.oid = c.relnamespace
           where n.nspname = 'public' and con.contype = 'u'"""))
          .as("V4's invariant: uniqueness is carried by unique INDEXES, never by a "
              + "UNIQUE CONSTRAINT -- 0 in the baseline and it must stay 0")
          .isZero();
      assertThat(queryInt(c, """
          select count(*) from pg_index i
            join pg_class c on c.oid = i.indrelid
            join pg_namespace n on n.oid = c.relnamespace
           where n.nspname = 'public' and c.relname in
                 ('outbox_events','job_attempts','job_dead_letters','idempotency_records',
                  'workflow_definitions','workflow_versions','workflow_runs',
                  'workflow_run_actions','sla_calendars','sla_calendar_windows',
                  'sla_calendar_holidays','sla_policies','collision_records')
             and not i.indisvalid"""))
          .as("every new index is valid")
          .isZero();

      // FR-1.2 and the timestamp contract, on the new tables only.
      assertThat(queryInt(c, """
          select count(*) from pg_attribute a
            join pg_class c on c.oid = a.attrelid
            join pg_namespace n on n.oid = c.relnamespace
           where n.nspname = 'public' and c.relname in
                 ('outbox_events','job_attempts','job_dead_letters','idempotency_records',
                  'workflow_definitions','workflow_versions','workflow_runs',
                  'workflow_run_actions','sla_calendars','sla_policies','collision_records')
             and a.attname = 'organizationId' and not a.attnotnull"""))
          .as("organizationId is NOT NULL on every Phase-5a and Phase-6a table that has "
              + "one (FR-1.2). sla_calendar_windows / sla_calendar_holidays are excluded: "
              + "they must have NO tenant column at all, asserted next.")
          .isZero();
      assertThat(queryInt(c, """
          select count(*) from pg_attribute a
            join pg_class c on c.oid = a.attrelid
            join pg_namespace n on n.oid = c.relnamespace
           where n.nspname = 'public' and c.relname in
                 ('outbox_events','job_attempts','job_dead_letters','idempotency_records',
                  'workflow_definitions','workflow_versions','workflow_runs',
                  'workflow_run_actions','sla_calendars','sla_calendar_windows',
                  'sla_calendar_holidays','sla_policies','collision_records')
             and a.attnum > 0 and not a.attisdropped
             and a.atttypid = 'timestamptz'::regtype"""))
          .as("no timestamptz column may appear (V6 contract)")
          .isZero();
      assertThat(queryStrings(c, """
          select c.relname from pg_class c
            join pg_namespace n on n.oid = c.relnamespace
           where n.nspname = 'public' and c.relname = 'job_attempts'
             and exists (select 1 from pg_attribute a
                          where a.attrelid = c.oid and a.attnum > 0
                            and not a.attisdropped and a.attname = 'updatedAt')"""))
          .as("job_attempts is append-only: it must not have updatedAt")
          .isEmpty();
      assertThat(queryStrings(c, """
          select c.relname || '.' || a.attname from pg_class c
            join pg_namespace n on n.oid = c.relnamespace
            join pg_attribute a on a.attrelid = c.oid and a.attnum > 0 and not a.attisdropped
           where n.nspname = 'public' and c.relname in
                 ('outbox_events','job_attempts','job_dead_letters','idempotency_records')
             and a.attname = 'projectId' and not a.attnotnull
           order by 1"""))
          .as("nullable projectId is the documented organisation-wide case, listed explicitly")
          .containsExactly("idempotency_records.projectId", "job_attempts.projectId",
                           "job_dead_letters.projectId", "outbox_events.projectId");

      // --- Phase 6a: the tenant-scope decisions, which are NOT uniform -----
      assertThat(queryStrings(c, """
          select c.relname || '.' || a.attname from pg_class c
            join pg_namespace n on n.oid = c.relnamespace
            join pg_attribute a on a.attrelid = c.oid and a.attnum > 0 and not a.attisdropped
           where n.nspname = 'public'
             and c.relname in ('workflow_definitions','workflow_versions','workflow_runs',
                               'workflow_run_actions','sla_calendars','sla_calendar_windows',
                               'sla_calendar_holidays','sla_policies','collision_records')
             and a.attname = 'projectId' and not a.attnotnull
           order by 1"""))
          .as("nullable projectId across the whole Phase-6a slice: the four "
              + "organisation-wide configuration tables, and NOT collision_records")
          .containsExactly("sla_calendars.projectId", "sla_policies.projectId",
                           "workflow_definitions.projectId", "workflow_versions.projectId");
      assertThat(queryStrings(c, """
          select c.relname || '.' || a.attname from pg_class c
            join pg_namespace n on n.oid = c.relnamespace
            join pg_attribute a on a.attrelid = c.oid and a.attnum > 0 and not a.attisdropped
           where n.nspname = 'public'
             and c.relname in ('sla_calendar_windows','sla_calendar_holidays')
             and a.attname in ('organizationId','projectId')
           order by 1"""))
          .as("calendar children inherit scope from their calendar and must carry NO "
              + "tenant column of their own, exactly like ai_run_steps inherits from "
              + "ai_runs in the baseline. A row here is a duplicated scope that can "
              + "disagree with its parent.")
          .isEmpty();
      assertThat(queryInt(c, """
          select count(*) from pg_attribute a
            join pg_class c on c.oid = a.attrelid
            join pg_namespace n on n.oid = c.relnamespace
           where n.nspname = 'public' and c.relname = 'collision_records'
             and a.attname = 'projectId' and not a.attnotnull"""))
          .as("collision_records.projectId is NOT NULL: every collided resource is "
              + "project-scoped, so NULL could only mean unknown scope")
          .isZero();

      // --- Phase 6a: append-only vs mutable, asserted as a map -----------
      assertThat(queryStrings(c, """
          select c.relname || '=' || case when exists (
              select 1 from pg_attribute a
               where a.attrelid = c.oid and a.attnum > 0
                 and not a.attisdropped and a.attname = 'updatedAt')
            then 'mutable' else 'append_only' end
            from pg_class c
              join pg_namespace n on n.oid = c.relnamespace
           where n.nspname = 'public' and c.relkind = 'r'
             and c.relname in ('workflow_definitions','workflow_versions','workflow_runs',
                               'workflow_run_actions','sla_calendars','sla_calendar_windows',
                               'sla_calendar_holidays','sla_policies','collision_records')
           order by 1"""))
          .as("the append-only boundary is asserted as an exact map, not a single flag: "
              + "workflow_runs IS mutable while workflow_run_actions is NOT, and the "
              + "calendar configuration tables carry no updatedAt at all")
          .containsExactly(
              "collision_records=append_only",
              "sla_calendar_holidays=append_only",
              "sla_calendar_windows=append_only",
              "sla_calendars=mutable",
              "sla_policies=mutable",
              "workflow_definitions=mutable",
              "workflow_run_actions=append_only",
              "workflow_runs=mutable",
              "workflow_versions=mutable");

      // --- Phase 6a: the three INDEPENDENT SLA clocks --------------------
      assertThat(queryInt(c, """
          select count(*) from pg_attribute a
            join pg_class c on c.oid = a.attrelid
            join pg_namespace n on n.oid = c.relnamespace
           where n.nspname = 'public' and c.relname = 'sla_policies'
             and a.attname in ('firstResponseMinutes','nextResponseMinutes','resolutionMinutes')
             and a.attnum > 0 and not a.attisdropped"""))
          .as("sla_policies carries three independent clock columns, not one duration "
              + "plus a repeat count: a clinic may answer in 15 minutes and still take a "
              + "day to resolve")
          .isEqualTo(3);

      // --- Phase 6a: every default writes UTC, never the session clock ----
      assertThat(queryInt(c, """
          select count(*) from pg_attrdef d
            join pg_class c on c.oid = d.adrelid
            join pg_namespace n on n.oid = c.relnamespace
           where n.nspname = 'public'
             and c.relname in ('workflow_definitions','workflow_versions','workflow_runs',
                               'workflow_run_actions','sla_calendars','sla_calendar_windows',
                               'sla_calendar_holidays','sla_policies','collision_records')
             and pg_get_expr(d.adbin, d.adrelid) like '%CURRENT_TIMESTAMP%'
             and pg_get_expr(d.adbin, d.adrelid) not like '%AT TIME ZONE%'"""))
          .as("every new default is (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'); a bare "
              + "CURRENT_TIMESTAMP stores the SESSION's wall clock, which is the "
              + "baseline's habit and the reason Phase 5a stopped copying it")
          .isZero();

      // --- Phase 6a: the composite tenant keys and foreign keys -----------
      assertThat(queryInt(c, """
          select count(*) from pg_index i
            join pg_class c on c.oid = i.indexrelid
            join pg_namespace n on n.oid = c.relnamespace
           where n.nspname = 'public'
             and c.relname in ('workflow_versions_id_definitionId_organizationId_key',
                               'sla_calendars_id_organizationId_key',
                               'conversations_id_organizationId_key')
             and i.indisunique"""))
          .as("the three composite tenant keys must be UNIQUE INDEXES: PostgreSQL accepts "
              + "a unique index as a foreign-key target, which is what lets these be "
              + "indexes instead of UNIQUE CONSTRAINTS (V4 unique_constraints = 0)")
          .isEqualTo(3);
      assertThat(queryStrings(c, """
          select con.conname from pg_constraint con
            join pg_class c ON c.oid = con.conrelid
            join pg_namespace n ON n.oid = c.relnamespace
           where n.nspname = 'public'
             and con.conname in ('workflow_runs_version_tenant_fkey',
                                'workflow_runs_conversation_tenant_fkey',
                                'sla_policies_calendar_tenant_fkey')
             and con.contype = 'f'
           order by 1"""))
          .as("the three composite tenant foreign keys exist. A single-column FK only "
              + "guarantees the parent EXISTS; these guarantee it belongs to the same "
              + "definition / tenant, which is the cross-tenant hole they close.")
          .containsExactly("sla_policies_calendar_tenant_fkey",
                           "workflow_runs_conversation_tenant_fkey",
                           "workflow_runs_version_tenant_fkey");
      assertThat(queryInt(c, """
          select count(*) from pg_constraint con
            join pg_class c ON c.oid = con.conrelid
            join pg_namespace n ON n.oid = c.relnamespace
           where n.nspname = 'public'
             and con.conname in ('collision_records_conversationId_fkey',
                                'collision_records_customerId_fkey',
                                'collision_records_noteId_fkey')"""))
          .as("collision_records.conversationId / customerId / noteId are deliberately NOT "
              + "foreign keys: a collision must stay recordable after the raced-for row is "
              + "deleted, and a CASCADE would destroy the only evidence the race happened")
          .isZero();

      // --- Phase 6a: no truncated identifiers ---------------------------
      assertThat(queryStrings(c, """
          select tablename || '.' || indexname from pg_indexes
           where schemaname = 'public'
             and tablename in ('workflow_definitions','workflow_versions','workflow_runs',
                               'workflow_run_actions','sla_calendars','sla_calendar_windows',
                               'sla_calendar_holidays','sla_policies','collision_records')
             and indexname not like '%definitionId_organizationId_key'
             and indexname not like '%projectId_key%'
             and length(indexname) > 63
           order by 1"""))
          .as("no Phase-6a index name exceeds 63 bytes. NOTE this test is `> 63`, not "
              + "`<> 63`: exactly 63 bytes is legal and untruncated, and the first "
              + "attempt at this guard used `<> 63` and falsely rejected a valid index "
              + "(found by the throwaway rehearsal).")
          .isEmpty();

      // Index census: 209 tenant-led / 15 project-led / 22 parent-key /
      // 142 unique-other / 5 global / 1 vector-led.
      assertThat(queryInt(c,
          "select count(*) from pg_indexes where schemaname='public'"
              + "   and tablename <> 'flyway_schema_history'"
              + "   and indexdef like '%USING btree (\"organizationId\"%'"))
          .as("tenant-led indexes: 64 baseline + 14 Phase-5a + 22 Phase-6a + 100 Phase-7-10a "
              + "+ 7 Phase-10b + 2 Phase-14 + 38 Dental Clinic (TRT, CLN, BIL, POL, PAT, RES)"
              + " + 4 clinic locations and service catalogue (two location read paths, the"
              + " service product-code read path and its per-project uniqueness arbiter)"
              + " + 3 doctor profiles and front desk (the soft-delete read path, the"
              + " doctor-by-clinic path and the qualification rows by doctor)."
              + " Patient profiles and appointment commerce add five: the tenant-wide CNIC"
              + " arbiter, the active-patient read path and the patient/payment/clinic"
              + " appointment-list paths. The"
              + " two Phase-14 ones are customer_cx_sequences_organizationId_key (the ON CONFLICT"
              + " arbiter) and customers_organizationId_cxId_key; the first is the only Phase-14"
              + " tenant-led index whose target is a single column, and both lead with"
              + " organizationId, which is what makes them tenant-led rather than parent keys."
              + " Clinic RBAC adds four tenant-led indexes.")
        .isEqualTo(327);
      assertThat(queryStrings(c, """
          select indexname from pg_indexes
           where schemaname = 'public'
             and tablename in ('api_keys','service_principals')
             and indexdef like '%USING btree ("organizationId"%'
           order by indexname"""))
          .as("the six Phase-10b tenant-led indexes on the two new credential tables, named "
              + "rather than counted, so an eighth cannot appear without the assertion saying "
              + "which table grew it")
          .containsExactly("api_keys_organizationId_keyPrefix_key",
                           "api_keys_organizationId_servicePrincipalId_status_idx",
                           "api_keys_organizationId_status_lastUsedAt_idx",
                           "service_principals_organizationId_principalKey_key",
                           "service_principals_organizationId_projectId_status_idx",
                           "service_principals_organizationId_status_idx");
      assertThat(queryInt(c, """
          select count(*) from pg_indexes
           where schemaname = 'public'
             and indexname = 'privacy_deletion_requests_organizationId_subjectCustomerId_idx'"""))
          .as("the seventh is the customer-subject read path, which is the query the purge and "
              + "deletion sweep and the Admin queue both need")
          .isEqualTo(1);
      assertThat(queryStrings(c, """
          select indexname from pg_indexes
           where schemaname = 'public'
             and indexname in ('documents_id_organizationId_key', 'customers_id_organizationId_key')
           order by indexname
          """))
          .as("the ONLY indexes Phase-10b adds to pre-existing tables are the two composite "
              + "foreign-key targets. They lead with id, so neither is tenant-led and neither is a "
              + "read path: a baseline table's read paths are untouched by this batch.")
          .containsExactly("customers_id_organizationId_key", "documents_id_organizationId_key");
      assertThat(queryInt(c, """
          select count(*) from pg_indexes where schemaname='public'
            and tablename <> 'flyway_schema_history'
            and indexdef like '%USING btree ("organizationId"%'
            and tablename in ('outbox_events','job_attempts','job_dead_letters','idempotency_records')"""))
          .as("every Phase-5a index that can be tenant-led is")
          .isEqualTo(14);
      assertThat(queryInt(c, """
          select count(*) from pg_indexes where schemaname='public'
            and tablename in ('workflow_definitions','workflow_versions','workflow_runs',
                              'workflow_run_actions','sla_calendars','sla_calendar_windows',
                              'sla_calendar_holidays','sla_policies','collision_records')
            and indexdef like '%USING btree ("organizationId"%'"""))
          .as("Phase 6a: 22 tenant-led indexes. Two Phase-6a indexes deliberately are NOT "
              + "tenant-led and are accounted for: sla_calendar_windows_calendar_day_idx "
              + "(allow-listed) and the composite tenant-key targets, which lead with id.")
          .isEqualTo(22);
      assertThat(queryStrings(c, """
          select tablename || '.' || indexname from pg_indexes
           where schemaname = 'public'
             and tablename <> 'flyway_schema_history'
             and indexdef not like '%USING btree ("organizationId"%'
             and indexdef not like '%USING btree ("projectId"%'
             and indexdef not like '%USING hnsw%'
             and indexdef not like '%USING btree ("runId"%'
             and indexdef not like '%USING btree ("conversationId"%'
             and indexdef not like '%USING btree ("employeeId"%'
             and indexdef not like '%USING btree ("userId"%'
             and indexdef not like '%USING btree ("knowledgeBaseId"%'
             and indexdef not like '%USING btree ("agentVersionId"%'
             and indexdef not like '%USING btree ("customerId"%'
             and indexdef not like '%UNIQUE%'
           order by 1"""))
          .as("tenant-less indexes: the allow list, and nothing else")
          .containsExactlyInAnyOrderElementsOf(
              List.of("sessions.sessions_expiresAt_idx",
                      "outbox_events.outbox_events_dispatch_sweep_idx",
                      "outbox_events.outbox_events_expired_lease_idx",
                      "sla_calendar_windows.sla_calendar_windows_calendar_day_idx",
                      // The GiST index backing clinic_schedules_no_overlapping_window. It IS
                      // tenant-scoped - the EXCLUDE lists "organizationId" first - but it is not
                      // a BTREE index leading with that column, so it cannot match the predicate
                      // above and is named here individually rather than waved through. An
                      // unnamed tenant-less index is what this assertion exists to catch.
                      "clinic_schedules.clinic_schedules_no_overlapping_window",
                      // The GiST indexes backing appointments_doctor_no_double_booking and
                      // appointments_resource_no_double_booking (DEC-001 chair/dentist double booking guards).
                      // They are tenant-scoped with ("organizationId" WITH =).
                      "appointments.appointments_doctor_no_double_booking",
                      "appointments.appointments_resource_no_double_booking",
                      // The two indexes on the tenant-LESS template table. They cannot lead
                      // with organizationId because the table has no such column, and they
                      // expose no tenant content: the table is a published clinical vocabulary.
                      "clinic_finding_vocabulary_template.clinic_finding_vocabulary_template_category_idx",
                      // Phase-7-10a adds exactly ONE: a correlation trace. Correlation
                      // ids span services by construction so the index cannot lead with the
                      // tenant; it is a lookup BY a known id, never a browse path, and it
                      // exposes no tenant content.
                      "admin_audit_events.admin_audit_events_correlationId_idx",
                      // Stage 1 adds the platform operator tier, whose tables carry no
                      // organizationId BY DESIGN: an operator belongs to no tenant. These are
                      // named individually here rather than waved through, because an unnamed
                      // tenant-less index is exactly what this assertion exists to catch. None
                      // of them leads with customer content -- they index session tokens and
                      // audit timestamps. The email lookup index is NOT listed: it is UNIQUE,
                      // and this query excludes unique indexes by design.
                      "platform_sessions.platform_sessions_operator_active_idx",
                      "platform_audit_events.platform_audit_events_operator_idx",
                      "platform_audit_events.platform_audit_events_action_idx"));

      // The FK the draft could not have: an attempt links to the durable event.
      assertThat(queryStrings(c, """
          select con.conname from pg_constraint con
            join pg_class c on c.oid = con.conrelid
            join pg_namespace n on n.oid = c.relnamespace
           where n.nspname = 'public' and c.relname = 'job_attempts'
             and con.contype = 'f' and con.confdeltype = 'n'
           order by 1"""))
          .contains("job_attempts_outboxEventId_fkey", "job_attempts_replayOfId_fkey");
    }
  }

  // ------------------------------------------------------------------
  // Scenario 6a: workflow versioning -- one live body, and an audited rollback
  // ------------------------------------------------------------------

  @Test
  @DisplayName("workflow versions: one ACTIVE per definition, and a rollback is an audited state change")
  void workflowVersionsAllowOneActiveVersionAndAuditRollback() throws Exception {
    Tenant tenant = seedTenant("wf-ver");
    UUID org = tenant.organizationId();
    UUID user = seedUser(org, "wf-author");

    String definitionId = seedWorkflowDefinition(org, "wf-ver", "Escalate to duty manager", 20);

    try (Connection c = connect(phase5aUrl)) {
      // --- author two drafts -----------------------------------------
      String v1 = insertDraftVersion(c, org, definitionId, 1, user);
      String v2 = insertDraftVersion(c, org, definitionId, 2, user);
      assertThat(queryInt(c, """
          select count(*) from workflow_versions where "workflowDefinitionId" = '%s'
             and "activatedAt" is null""".formatted(definitionId)))
          .as("a DRAFT has never been activated")
          .isEqualTo(2);

      // --- the invariants, each with a negative test -------------------
      assertThatThrownBy(() -> execute(c, """
          update workflow_versions
             set "state" = 'ACTIVE', "updatedAt" = (now() at time zone 'UTC')
           where id = '%s'""".formatted(v1)))
          .as("workflow_versions_activated_by_check: ACTIVE without an activating user")
          .isInstanceOf(SQLException.class);

      assertThatThrownBy(() -> execute(c, """
          update workflow_versions
             set "state" = 'ROLLED_BACK', "updatedAt" = (now() at time zone 'UTC')
           where id = '%s'""".formatted(v1)))
          .as("workflow_versions_rollback_check: a rollback with no who / when / where-to")
          .isInstanceOf(SQLException.class);

      assertThatThrownBy(() -> execute(c, """
          update workflow_versions
             set "capabilityScope" = '{"everything": true}'::jsonb,
                 "updatedAt" = (now() at time zone 'UTC')
           where id = '%s'""".formatted(v1)))
          .as("workflow_versions_capability_scope_check: an OBJECT, not a list. An "
              + "unbounded capability set is exactly the unrestricted identity plan.md "
              + "forbids, and it cannot be diffed against another version.")
          .isInstanceOf(SQLException.class);

      assertThatThrownBy(() -> execute(c, """
          update workflow_versions set "versionNumber" = 0, "updatedAt" = now()
           where id = '%s'""".formatted(v1)))
          .as("workflow_versions_version_number_check")
          .isInstanceOf(SQLException.class);

      assertThatThrownBy(() -> insertDraftVersionWithNumber(c, org, definitionId, user, 0))
          .as("a version 0 is refused at insert time, not only on update")
          .isInstanceOf(SQLException.class);

      // --- activate v1 -------------------------------------------------
      assertThat(activateVersion(c, org, definitionId, v1, user)).isEqualTo(1);
      assertThat(queryString(c, """
          select d."activeVersionId"::text from workflow_definitions d
           where d.id = '%s'""".formatted(definitionId)))
          .as("the definition's pointer moved with the activation")
          .isEqualTo(v1);

      // --- the partial unique index is the backstop --------------------
      assertThatThrownBy(() -> execute(c, """
          update workflow_versions
             set "state" = 'ACTIVE',
                 "activatedAt" = (now() at time zone 'UTC'),
                 "activatedByUserId" = '%s',
                 "updatedAt" = (now() at time zone 'UTC')
           where id = '%s'""".formatted(user, v2)))
          .as("workflow_versions_one_active_per_definition: a second ACTIVE version for "
              + "one definition is refused by the index even if the application logic "
              + "gets the retire-then-activate ordering wrong")
          .isInstanceOf(SQLException.class);
      assertThat(queryInt(c, """
          select count(*) from workflow_versions
           where "workflowDefinitionId" = '%s'
             and "state" = 'ACTIVE'::"WorkflowVersionState\"""".formatted(definitionId)))
          .as("and exactly one ACTIVE version survives the failed attempt")
          .isEqualTo(1);

      // --- supersede v1 by activating v2 --------------------------------
      assertThat(retireActiveVersion(c, org, definitionId, v2)).isEqualTo(1);
      assertThat(activateVersion(c, org, definitionId, v2, user)).isEqualTo(1);
      assertThat(queryInt(c, """
          select count(*) from workflow_versions
           where id = '%s' and "state" = 'SUPERSEDED'::"WorkflowVersionState"
             and "supersededAt" is not null
             and "supersededByVersionId" = '%s'""".formatted(v1, v2)))
          .as("the retired version records WHEN it was superseded and WHAT replaced it")
          .isEqualTo(1);

      // --- roll back to v1 ------------------------------------------------
      assertThat(retireActiveVersion(c, org, definitionId, v1)).isEqualTo(1);
      assertThat(activateVersion(c, org, definitionId, v1, user)).isEqualTo(1);
      // execute(), not queryInt(): this is an UPDATE. queryInt runs executeQuery, and
      // an UPDATE returns no ResultSet, so it fails with "No results were
      // returned by the query" instead of returning the affected-row count.
      assertThat(execute(c, """
          update workflow_versions
             set "state" = 'ROLLED_BACK',
                 "rolledBackAt" = (now() at time zone 'UTC'),
                 "rolledBackByUserId" = '%s',
                 "rolledBackToVersionId" = '%s',
                 "updatedAt" = (now() at time zone 'UTC')
           where id = '%s'""".formatted(user, v1, v2)))
          .as("marking the abandoned version ROLLED_BACK")
          .isEqualTo(1);
      assertThat(queryInt(c, """
          select count(*) from workflow_versions
           where id = '%s' and "state" = 'ROLLED_BACK'::"WorkflowVersionState"
             and "rolledBackByUserId" = '%s'
             and "rolledBackToVersionId" = '%s'""".formatted(v2, user, v1)))
          .as("a rollback says who, when, and WHERE it went back to")
          .isEqualTo(1);
      assertThat(queryInt(c, "select count(*) from workflow_versions where id = '%s'".formatted(v2)))
          .as("no version is ever deleted: v2 is the body an in-flight run bound to, and "
              + "deleting it would make that run unexplainable")
          .isEqualTo(1);

      // --- the natural key is tenant-scoped --------------------------------
      UUID otherOrg = seedTenant("wf-ver-other").organizationId();
      // --- the natural key is unique per TENANT, and NOT global ------------
      // The same key in the same tenant IS refused.
      assertThatThrownBy(() -> execute(c, """
          insert into workflow_definitions (id, "organizationId", "projectId", "key", "name",
                                            "triggerType", "createdAt", "updatedAt")
          values (gen_random_uuid(), '%s', null, 'wf-ver', 'Duplicate key same tenant',
                  'CONVERSATION_OPENED', now(), now())""".formatted(org)))
          .as("a workflow key is unique per TENANT. Including projectId in the key would "
              + "let 'onboarding_v1' exist once per project with no way to name one of them.")
          .isInstanceOf(SQLException.class);
      // The same key in ANOTHER tenant is allowed, and that is the point: uniqueness is
      // tenant-scoped, so a global index could never turn the violation message into a
      // cross-tenant existence oracle.
      assertThat(execute(c, """
          insert into workflow_definitions (id, "organizationId", "projectId", "key", "name",
                                            "triggerType", "createdAt", "updatedAt")
          values (gen_random_uuid(), '%s', null, 'wf-ver', 'Same key other tenant',
                  'CONVERSATION_OPENED', now(), now())""".formatted(otherOrg)))
          .as("tenant-scoped uniqueness: tenant B may use the same workflow key, and the "
              + "constraint therefore discloses nothing about tenant A")
          .isEqualTo(1);
    }
  }

  // ------------------------------------------------------------------
  // Scenario 6b: workflow execution
  // ------------------------------------------------------------------

  @Test
  @DisplayName("a run executes as a named principal, records append-only action history, and a human takeover aborts it")
  void workflowRunsExecuteAsNamedPrincipalWithAppendOnlyHistory() throws Exception {
    Tenant tenant = seedTenant("wf-run");
    UUID org = tenant.organizationId();
    UUID project = tenant.projectId();
    UUID principal = seedUser(org, "wf-principal");
    UUID human = seedUser(org, "wf-human");
    UUID conversation = seedConversation(org, project, "wf-run");
    String definitionId = seedWorkflowDefinition(org, "wf-run", "Auto-assign on open", 10);

    String versionId;
    try (Connection c = connect(phase5aUrl)) {
      versionId = insertDraftVersion(c, org, definitionId, 1, principal);
      retireActiveVersion(c, org, definitionId, versionId);
      activateVersion(c, org, definitionId, versionId, principal);
    }

    try (Connection c = connect(phase5aUrl)) {
      // --- loop prevention: one run per trigger instance per tenant ------
      String runId = startRun(c, org, project, definitionId, versionId, conversation,
          principal, "wf-run-trigger-1");
      assertThat(queryInt(c, "select count(*) from workflow_runs where id = '%s'".formatted(runId)))
          .isEqualTo(1);
      // isNull(), not isEmpty(): startRun returns null when the INSERT ... ON
      // CONFLICT DO NOTHING returns no row. AssertJ's isEmpty() on a null String
      // fails with "expecting actual not to be null", which reads like a missing
      // row rather than the no-op the loop-prevention contract actually is.
      assertThat(startRun(c, org, project, definitionId, versionId, conversation,
          principal, "wf-run-trigger-1"))
          .as("the SAME trigger instance starts no second run: the loop-prevention "
              + "primitive, and the caller learns it by getting zero rows back")
          .isNull();

      // The same trigger INSTANCE id in another tenant is a different incident. This
      // needs a COMPLETE second tenant, not just a different organizationId: the
      // composite tenant foreign keys (which the fixture just proved are real)
      // correctly refuse a run that mixes one tenant's definition, version and
      // conversation with another's. An earlier version of this test tried to do
      // exactly that and the FK caught it -- which is the FK working, and the
      // reason this case now builds a whole tenant.
      Active other = seedActiveWorkflow("wf-run-other");
      assertThat(startRun(c, other.tenant().organizationId(), other.tenant().projectId(),
          other.definitionId(), other.versionId(), other.conversationId(), principal,
          "wf-run-trigger-1"))
          .as("the trigger-instance uniqueness is TENANT-scoped, so it is never global and "
              + "the violation message can never disclose another tenant's trigger id")
          .isNotNull();

      // --- the named principal is NOT NULL, enforced by the schema -------
      assertThatThrownBy(() -> execute(c, """
          insert into workflow_runs (id, "organizationId", "projectId", "workflowDefinitionId",
                                     "workflowVersionId", "conversationId", "triggerInstanceId",
                                     "status", "principalUserId", "capabilityScope",
                                     "startedAt", "createdAt", "updatedAt")
          values (gen_random_uuid(), '%s', '%s', '%s', '%s', '%s', 'wf-no-principal', 'RUNNING',
                  null, '[]'::jsonb, now(), now(), now())"""
          .formatted(org, project, definitionId, versionId, conversation)))
          .as("phase-06 hard rule: a workflow run executes as a NAMED principal. "
              + "'no principal' must be unexpressible, so the column is NOT NULL and an FK.")
          .isInstanceOf(SQLException.class);

      // --- the capability set is a list -----------------------------------
      assertThatThrownBy(() -> execute(c, """
          update workflow_runs set "capabilityScope" = 'ALL'::jsonb,
                                "updatedAt" = now()
           where id = '%s'""".formatted(runId)))
          .as("an unrestricted 'ALL' capability set is refused")
          .isInstanceOf(SQLException.class);

      // --- append-only action history with its inputs --------------------
      String action1 = recordAction(c, org, project, runId, "assign", "ASSIGN_EMPLOYEE",
          1, "SUCCEEDED", principal, null, null, 120);
      assertThat(queryInt(c, """
          select count(*) from workflow_run_actions
           where id = '%s' and "inputSnapshot"->>'candidateId' = 'emp-7'
             and "resultSnapshot"->>'assigned' = 'true'
             and "durationMs" = 120""".formatted(action1)))
          .as("the decision records the INPUTS that produced it, not just the result "
              + "(phase-06 acceptance gate 2)")
          .isEqualTo(1);

      // A retry is a NEW row with the next attempt number, never a mutation.
      String outcome1 = queryString(c,
          "select outcome from workflow_run_actions where id = '" + action1 + "'");
      recordAction(c, org, project, runId, "assign", "ASSIGN_EMPLOYEE", 2, "RETRYABLE",
          principal, "EMP_BUSY", "employee at capacity", 300);
      assertThat(queryString(c,
          "select outcome from workflow_run_actions where id = '" + action1 + "'"))
          .as("a retry creates a row; it never mutates the failed one")
          .isEqualTo(outcome1);
      assertThat(queryInt(c, """
          select count(*) from workflow_run_actions
           where "workflowRunId" = '%s' and "actionKey" = 'assign'""".formatted(runId)))
          .isEqualTo(2);

      // Attempt numbering is unique per tenant, run and action.
      assertThatThrownBy(() -> recordAction(c, org, project, runId, "assign", "ASSIGN_EMPLOYEE",
          1, "SUCCEEDED", principal, null, null, 10))
          .as("the same attempt number twice is refused, so the history cannot be "
              + "double-counted by two racing writers")
          .isInstanceOf(SQLException.class);

      String run2 = startRun(c, org, project, definitionId, versionId, conversation,
          principal, "wf-run-trigger-2");
      assertThat(recordAction(c, org, project, run2, "assign", "ASSIGN_EMPLOYEE", 1,
          "SUCCEEDED", principal, null, null, 10))
          .as("attempt numbering is per RUN, not global")
          .isNotNull();

      // --- only a failure may carry an error ------------------------------
      assertThatThrownBy(() -> execute(c, """
          update workflow_run_actions set "errorCode" = 'X', "createdAt" = "createdAt"
           where id = '%s'""".formatted(action1)))
          .as("workflow_run_actions_error_outcome_check: a SUCCEEDED action cannot carry "
              + "an error code")
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> execute(c, """
          update workflow_run_actions
             set outcome = 'RETRYABLE'::"WorkflowActionOutcome", "createdAt" = "createdAt"
           where id = '%s'""".formatted(action1)))
          .as("workflow_run_actions_retryable_check: RETRYABLE without an error code AND "
              + "message is not a retryable failure, it is a decision nobody can act on")
          .isInstanceOf(SQLException.class);

      // --- an action must have an actor ----------------------------------
      assertThatThrownBy(() -> execute(c, """
          update workflow_run_actions set "actorUserId" = null, "actorPrincipal" = null
           where id = '%s'""".formatted(action1)))
          .as("workflow_run_actions_actor_check: an action with neither a user nor a "
              + "named principal is unattributable")
          .isInstanceOf(SQLException.class);

      // --- an unfinished action must not carry a duration -------------------
      // Needs its OWN row: action1 already carries a finish time and a duration,
      // so updating action1 proves nothing about the pair. An earlier version of
      // this test did exactly that and passed without exercising the constraint.
      String inFlight = recordInFlightAction(c, org, project, runId, "notify", "SEND_REPLY", 1,
          principal);
      assertThat(queryInt(c, """
          select count(*) from workflow_run_actions
           where id = '%s' and "finishedAt" is null and "durationMs" is null""".formatted(inFlight)))
          .as("the in-flight action starts with neither a finish time nor a duration")
          .isEqualTo(1);
      assertThatThrownBy(() -> execute(c, """
          update workflow_run_actions set "durationMs" = 42
           where id = '%s'""".formatted(inFlight)))
          .as("workflow_run_actions_finished_check: a duration without a finish time -- the "
              + "scheduler must not be able to claim an action ran for 42ms when it has not "
              + "finished")
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> execute(c, """
          update workflow_run_actions set "finishedAt" = (now() at time zone 'UTC')
           where id = '%s'""".formatted(inFlight)))
          .as("...and the other direction: a finish time without a duration")
          .isInstanceOf(SQLException.class);

      // --- HUMAN TAKEOVER: the phase-06 gate ------------------------------
      assertThat(requestAbort(c, org, runId, human, "operator takeover")).isEqualTo(1);
      assertThat(requestAbort(c, org, runId, human, "second takeover request"))
          .as("a second takeover request updates nothing: the takeover is idempotent")
          .isZero();
      assertThat(queryInt(c, """
          select count(*) from workflow_runs
           where id = '%s' and "abortRequestedAt" is not null
             and "abortedByUserId" = '%s'""".formatted(runId, human)))
          .as("the abort request names its requester (workflow_runs_abort_pair_check)")
          .isEqualTo(1);

      // An abort that completes without a request is refused.
      String run3 = startRun(c, org, project, definitionId, versionId, conversation,
          principal, "wf-run-trigger-3");
      assertThatThrownBy(() -> execute(c, """
          update workflow_runs set "abortedAt" = (now() at time zone 'UTC'),
                                "abortedByUserId" = '%s',
                                "status" = 'TAKEOVER'::"WorkflowRunStatus",
                                "finishedAt" = (now() at time zone 'UTC'),
                                "updatedAt" = (now() at time zone 'UTC')
           where id = '%s'""".formatted(human, run3)))
          .as("workflow_runs_abort_pair_check: an abort cannot complete if none was "
              + "requested -- this is what stops a takeover being invented rather than observed")
          .isInstanceOf(SQLException.class);

      // ABORTED/TAKEOVER must say who and when.
      String run4 = startRun(c, org, project, definitionId, versionId, conversation,
          principal, "wf-run-trigger-4");
      assertThatThrownBy(() -> execute(c, """
          update workflow_runs set "abortRequestedAt" = (now() at time zone 'UTC'),
                                "status" = 'TAKEOVER'::"WorkflowRunStatus",
                                "finishedAt" = (now() at time zone 'UTC'),
                                "updatedAt" = (now() at time zone 'UTC')
           where id = '%s'""".formatted(run4)))
          .as("workflow_runs_aborted_state_check: TAKEOVER names nobody")
          .isInstanceOf(SQLException.class);

      // FAILED must say why.
      String run5 = startRun(c, org, project, definitionId, versionId, conversation,
          principal, "wf-run-trigger-5");
      assertThatThrownBy(() -> execute(c, """
          update workflow_runs set status = 'FAILED'::"WorkflowRunStatus",
                                "finishedAt" = (now() at time zone 'UTC'),
                                "updatedAt" = (now() at time zone 'UTC')
           where id = '%s'""".formatted(run5)))
          .as("workflow_runs_failure_reason_check: an undiagnosed failure is not "
              + "diagnosable (FR-6.7)")
          .isInstanceOf(SQLException.class);

      // An unfinished run must not claim it finished.
      assertThatThrownBy(() -> execute(c, """
          update workflow_runs set "finishedAt" = (now() at time zone 'UTC'),
                                "updatedAt" = (now() at time zone 'UTC')
           where id = '%s'""".formatted(runId)))
          .as("workflow_runs_finished_state_check: a RUNNING run with a finish time")
          .isInstanceOf(SQLException.class);

      // --- THE COMPOSITE TENANT FOREIGN KEYS, refused with data ---------
      Active cross = seedActiveWorkflow("wf-run-cross");
      assertThatThrownBy(() -> startRun(c, cross.tenant().organizationId(),
          cross.tenant().projectId(), cross.definitionId(), cross.versionId(),
          conversation, principal, "wf-run-cross-0"))
          .as("workflow_runs_conversation_tenant_fkey: a run for tenant B may not point at "
              + "tenant A's conversation. A single-column FK allows exactly this and "
              + "nothing else in the schema would notice. Here EVERY row belongs to tenant "
              + "B except the conversation, so the only thing wrong is the cross-tenant "
              + "reference -- which is precisely what the composite FK exists to catch.")
          .isInstanceOf(SQLException.class);

      assertThatThrownBy(() -> startRun(c, org, project, cross.definitionId(), cross.versionId(),
          cross.conversationId(), principal, "wf-run-cross-2"))
          .as("workflow_runs_version_tenant_fkey: a run in tenant A may not execute "
              + "tenant B's workflow body, even though the version row exists")
          .isInstanceOf(SQLException.class);
      // And the positive control: a fully consistent tenant B run is accepted. A
      // negative test that only ever proves refusal would pass against a table
      // that rejects everything, so every cross-tenant negative is paired with a
      // positive of the identical shape.
      assertThat(startRun(c, cross.tenant().organizationId(), cross.tenant().projectId(),
          cross.definitionId(), cross.versionId(), cross.conversationId(), principal,
          "wf-run-cross-ok"))
          .as("the same shape with a consistent tenant is accepted -- so the two refusals "
              + "above are the cross-tenant reference and nothing else")
          .isNotNull();
    }
  }

  // ------------------------------------------------------------------
  // Scenario 6c: SLA clocks and calendars
  // ------------------------------------------------------------------

  @Test
  @DisplayName("SLA policies keep three independent clocks and refuse an inverted one; calendar windows are same-day")
  void slaPoliciesKeepThreeIndependentClocksAndCalendarWindowsAreSameDay() throws Exception {
    Tenant tenant = seedTenant("sla");
    UUID org = tenant.organizationId();
    UUID project = tenant.projectId();
    String calendarId;
    try (Connection c = connect(phase5aUrl)) {
      calendarId = insertCalendar(c, org, "clinic-week", "Clinic working week",
          "Asia/Riyadh", "2026-01-01", 1);
      activateCalendar(c, org, "clinic-week", calendarId);
      // Sunday..Thursday 09:00-17:00 local, exactly the legacy
      // employee_availability shape so the legacy window comparison carries over.
      for (int day = 0; day <= 4; day++) {
        execute(c, """
            insert into sla_calendar_windows (id, "calendarId", "dayOfWeek", "startMinute",
                                             "endMinute", "label", "createdAt")
            values (gen_random_uuid(), '%s', %d, 540, 1020, 'clinic', now())"""
            .formatted(calendarId, day));
      }
    }

    try (Connection c = connect(phase5aUrl)) {
      // --- the three clocks are independent and all expressible ---------
      insertPolicy(c, org, project, "sla-standard", 15, 60, 1440, UUID.fromString(calendarId));
      assertThat(queryInt(c, """
          select count(*) from sla_policies
           where "key" = 'sla-standard'
             and "firstResponseMinutes" = 15
             and "nextResponseMinutes" = 60
             and "resolutionMinutes" = 1440"""))
          .as("a clinic answers in 15 minutes, replies again within an hour and resolves "
              + "within a day: three INDEPENDENT clocks, not one duration")
          .isEqualTo(1);

      // A resolution-only policy is legitimate (a channel with no response promise).
      insertPolicy(c, org, project, "sla-resolution-only", null, null, 2880, null);
      assertThat(queryInt(c, """
          select count(*) from sla_policies
           where "key" = 'sla-resolution-only' and "firstResponseMinutes" is null
             and "resolutionMinutes" = 2880 and "calendarId" is null"""))
          .as("a NULL clock means 'not promised', and a NULL calendar means 24/7 -- both "
              + "must be expressible without inventing a 00:00-24:00 window")
          .isEqualTo(1);

      // --- THE CLOCK-ORDERING INVARIANT: three negative tests ----------
      assertThatThrownBy(() -> insertPolicy(c, org, project, "sla-inverted", 60, null, 30, null))
          .as("sla_policies_clock_ordering_check: a resolution budget SHORTER than the "
              + "first-response budget breaches on every healthy ticket and trains "
              + "operators to ignore the SLA queue")
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> insertPolicy(c, org, project, "sla-next-inverted", 30, 15, null, null))
          .as("sla_policies_clock_ordering_check: a next-response budget shorter than the "
              + "first-response budget")
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> execute(c, """
          update sla_policies
             set "resolutionMinutes" = 5, "updatedAt" = (now() at time zone 'UTC')
           where "key" = 'sla-standard'"""))
          .as("...and the invariant holds on UPDATE too, not only on insert")
          .isInstanceOf(SQLException.class);

      // An escalation must be scheduled at or before the clock it escalates.
      assertThatThrownBy(() -> execute(c, """
          update sla_policies set "escalateAfterMinutes" = 99999, "updatedAt" = now()
           where "key" = 'sla-standard'"""))
          .as("sla_policies_escalate_not_after_clock_check: an escalation that fires after "
              + "the breach it was meant to prevent")
          .isInstanceOf(SQLException.class);

      // A policy must promise at least one clock.
      assertThatThrownBy(() -> insertPolicy(c, org, project, "sla-empty", null, null, null, null))
          .as("sla_policies_at_least_one_clock_check: a policy that promises nothing is "
              + "dead configuration the scheduler has nothing to evaluate")
          .isInstanceOf(SQLException.class);

      // Zero or negative budgets are a breach at birth.
      assertThatThrownBy(() -> insertPolicy(c, org, project, "sla-zero", 0, null, null, null))
          .as("sla_policies_first_response_minutes_check")
          .isInstanceOf(SQLException.class);

      // --- THE SAME-DAY WINDOW INVARIANT: three negative tests -----------
      assertThatThrownBy(() -> execute(c, """
          insert into sla_calendar_windows (id, "calendarId", "dayOfWeek", "startMinute",
                                           "endMinute", "createdAt")
          values (gen_random_uuid(), '%s', 5, 1020, 540, now())""".formatted(calendarId)))
          .as("sla_calendar_windows_same_day_check: endMinute must EXCEED startMinute. A "
              + "window that does not is empty or overnight, and an overnight shift as one "
              + "row is ambiguous the moment its dayOfWeek is read back for a DST week")
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> execute(c, """
          insert into sla_calendar_windows (id, "calendarId", "dayOfWeek", "startMinute",
                                           "endMinute", "createdAt")
          values (gen_random_uuid(), '%s', 5, 600, 600, now())""".formatted(calendarId)))
          .as("...including the zero-length case")
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> execute(c, """
          update sla_calendar_windows set "startMinute" = 1100, "createdAt" = "createdAt"
           where "calendarId" = '%s' and "dayOfWeek" = 0""".formatted(calendarId)))
          .as("...and the invariant holds on UPDATE too")
          .isInstanceOf(SQLException.class);

      // Real minute bounds. 1440 is midnight; 1441 is not a minute.
      assertThatThrownBy(() -> execute(c, """
          insert into sla_calendar_windows (id, "calendarId", "dayOfWeek", "startMinute",
                                           "endMinute", "createdAt")
          values (gen_random_uuid(), '%s', 6, 0, 1441, now())""".formatted(calendarId)))
          .as("sla_calendar_windows_end_minute_check")
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> execute(c, """
          insert into sla_calendar_windows (id, "calendarId", "dayOfWeek", "startMinute",
                                           "endMinute", "createdAt")
          values (gen_random_uuid(), '%s', 7, 540, 1020, now())""".formatted(calendarId)))
          .as("sla_calendar_windows_day_of_week_check: day 7 does not exist")
          .isInstanceOf(SQLException.class);

      // --- versioned calendars: one ACTIVE per key, and effective dating ---
      // A DRAFT version 2 is storable while version 1 is live. Without the DRAFT
      // state the only way to author a successor would be to insert it as ACTIVE,
      // which the partial unique index refuses -- the throwaway rehearsal found
      // exactly that deadlock in the design.
      String calendarV2 = insertCalendar(c, org, "clinic-week",
          "Clinic working week (winter)", "Asia/Riyadh", "2026-11-01", 2);
      assertThat(queryInt(c, """
          select count(*) from sla_calendars
           where "key" = 'clinic-week' and "state" = 'DRAFT'::"SlaCalendarState\""""))
          .as("a successor calendar can be authored while the incumbent is live")
          .isEqualTo(1);

      assertThatThrownBy(() -> execute(c, """
          update sla_calendars set "state" = 'ACTIVE'::"SlaCalendarState",
                                 "updatedAt" = (now() at time zone 'UTC')
           where id = '%s'""".formatted(calendarV2)))
          .as("sla_calendars_one_active_per_key: a second live calendar for one key would "
              + "make 'which calendar applies' unanswerable")
          .isInstanceOf(SQLException.class);

      assertThatThrownBy(() -> execute(c, """
          update sla_calendars set "state" = 'SUPERSEDED'::"SlaCalendarState",
                                 "updatedAt" = (now() at time zone 'UTC')
           where id = '%s'""".formatted(calendarV2)))
          .as("sla_calendars_supersede_check: a superseded calendar must name its successor")
          .isInstanceOf(SQLException.class);

      assertThatThrownBy(() -> execute(c, """
          update sla_calendars set "effectiveTo" = date '2027-01-01',
                                 "updatedAt" = (now() at time zone 'UTC')
           where id = '%s'""".formatted(calendarV2)))
          .as("sla_calendars_draft_range_check: a DRAFT calendar is not in effect, so an "
              + "effective range on it describes a period it was never promised for")
          .isInstanceOf(SQLException.class);

      // Publish it properly: retire the incumbent, then activate the draft.
      activateCalendar(c, org, "clinic-week", calendarV2);
      assertThat(queryInt(c, """
          select count(*) from sla_calendars
           where "key" = 'clinic-week' and "state" = 'ACTIVE'::"SlaCalendarState\""""))
          .as("exactly one live calendar per key after the swap")
          .isEqualTo(1);
      assertThat(queryInt(c, """
          select count(*) from sla_calendars
           where id = '%s' and "supersededById" = '%s'""".formatted(calendarId, calendarV2)))
          .as("the retired calendar names its successor, which is how a historical "
              + "ticket finds the calendar that actually applied")
          .isEqualTo(1);

      // A calendar that has ended must say when, in the right order.
      assertThatThrownBy(() -> execute(c, """
          update sla_calendars set "effectiveTo" = date '2025-01-01',
                                 "updatedAt" = (now() at time zone 'UTC')
           where id = '%s'""".formatted(calendarId)))
          .as("sla_calendars_effective_range_check: a calendar that ended before it began")
          .isInstanceOf(SQLException.class);

      // A calendar that still drives a policy must be superseded, not deleted.
      assertThatThrownBy(() -> execute(c, "delete from sla_calendars where id = '%s'".formatted(calendarId)))
          .as("sla_policies_calendar_tenant_fkey ON DELETE RESTRICT: deleting a calendar "
              + "that still drives a policy would silently turn a business-hours SLA into "
              + "a 24/7 one")
          .isInstanceOf(SQLException.class);

      // --- holidays: one closure per date, partial closures are a pair -----
      execute(c, """
          insert into sla_calendar_holidays (id, "calendarId", "holidayDate", "label", "createdAt")
          values (gen_random_uuid(), '%s', date '2026-10-09', 'National Day', now())"""
          .formatted(calendarId));
      assertThatThrownBy(() -> execute(c, """
          insert into sla_calendar_holidays (id, "calendarId", "holidayDate", "label", "createdAt")
          values (gen_random_uuid(), '%s', date '2026-10-09', 'Duplicate', now())"""
          .formatted(calendarId)))
          .as("sla_calendar_holidays_calendar_date_key: one closure per date, or the clock "
              + "pauses twice for one day")
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> execute(c, """
          insert into sla_calendar_holidays (id, "calendarId", "holidayDate", "label",
                                            "startMinute", "createdAt")
          values (gen_random_uuid(), '%s', date '2026-10-10', 'Half day', 540, now())"""
          .formatted(calendarId)))
          .as("sla_calendar_holidays_partial_pair_check: half a partial closure is not a "
              + "state the clock evaluator can act on")
          .isInstanceOf(SQLException.class);

      // --- another tenant's calendar cannot be referenced -------------------
      Tenant other = seedTenant("sla-other");
      String otherCalendar = insertCalendar(c, other.organizationId(), "other-week",
          "Other week", "Europe/London", "2026-01-01", 1);
      // bound here so the negative test below can read it; the id is a fixture
      // value, never a credential.
      // Only the calendarId changes. Moving BOTH calendarId and organizationId would
      // be self-consistent and legal -- the composite FK is satisfied -- so the
      // negative must keep the policy in its own tenant and point its clock at
      // another tenant's calendar. That is the actual bug this FK prevents.
      assertThatThrownBy(() -> execute(c, """
          update sla_policies set "calendarId" = '%s', "updatedAt" = now()
           where "key" = 'sla-resolution-only'""".formatted(otherCalendar)))
          .as("sla_policies_calendar_tenant_fkey refuses a policy in tenant A pointing at "
              + "tenant B's calendar -- the highest-value cross-tenant negative in this "
              + "slice, because the wrong calendar pauses the wrong clock")
          .isInstanceOf(SQLException.class);
      assertThat(queryInt(c, """
          select count(*) from sla_policies p
            join sla_calendars c2 on c2.id = p."calendarId"
           where c2."organizationId" <> p."organizationId\""""))
          .as("and with data: no policy resolves another tenant's calendar")
          .isZero();
    }
  }

  // ------------------------------------------------------------------
  // Scenario 6d: collisions -- the deduplication window under concurrency
  // ------------------------------------------------------------------

  @Test
  @DisplayName("collision records deduplicate one incident per window under two concurrent writers")
  void collisionRecordsDeduplicateOneIncidentPerWindow() throws Exception {
    Tenant tenant = seedTenant("collision");
    UUID org = tenant.organizationId();
    UUID project = tenant.projectId();
    UUID conversation = seedConversation(org, project, "collision");
    UUID otherConversation = seedConversation(org, project, "collision-other");
    long window = 1_700_000_000L / 300;   // a fixed 5-minute bucket

    try (Connection c = connect(phase5aUrl)) {
      // --- first writer wins ------------------------------------------
      assertThat(insertCollision(c, org, project, conversation, "user-A", "USER", window,
          "KEPT_BOTH", "both humans replied")).isEqualTo(1);

      // --- the same incident is refused, not double-counted -------------
      assertThat(insertCollision(c, org, project, conversation, "user-B", "USER", window,
          "KEPT_BOTH", "also replied"))
          .as("collision_records_org_scope_resource_window_key: two counterparties on one "
              + "resource in one 5-minute window is ONE incident")
          .isZero();
      assertThat(insertCollision(c, org, project, conversation, "user-A", "USER", window,
          "WINS", "duplicate"))
          .as("collision_records_counterparty_unique: the same counterparty cannot record "
              + "two resolutions of the same resource in the same window either")
          .isZero();

      // --- a DIFFERENT window is a different incident --------------------
      assertThat(insertCollision(c, org, project, conversation, "user-B", "USER", window + 1,
          "KEPT_BOTH", "replied later")).isEqualTo(1);

      // --- a different conversation is a different incident --------------
      assertThat(insertCollision(c, org, project, otherConversation, "user-B", "USER", window,
          "KEPT_BOTH", "other ticket")).isEqualTo(1);

      // --- another tenant may use the same window and the same counterparty id ---
      Tenant other = seedTenant("collision-other");
      assertThat(insertCollision(c, other.organizationId(), other.projectId(), otherConversation,
          "user-B", "USER", window, "KEPT_BOTH", "other tenant")).isEqualTo(1);

      assertThat(queryInt(c, """
          select count(*) from collision_records
           where "resourceId" = '%s' and "collisionWindow" = %d""".formatted(conversation, window)))
          .as("exactly one incident for this resource and window")
          .isEqualTo(1);

      // --- the invariants ----------------------------------------------
      assertThatThrownBy(() -> execute(c, """
          update collision_records set "resolutionDetail" = null, "createdAt" = "createdAt"
           where "counterpartyId" = 'user-A' and "resourceId" = '%s'
             and "collisionWindow" = %d""".formatted(conversation, window)))
          .as("collision_records_outcome_detail_check: a KEPT_BOTH with no explanation of "
              + "what was kept")
          .isInstanceOf(SQLException.class);
      // The abort biconditional, BOTH directions. The first half ("only an
      // ABORTED_BY_HUMAN outcome may name an aborted run") was the original
      // constraint; the fixture proved the reverse half was missing, because a row
      // could claim a human takeover with no run at all -- and nothing else in the
      // schema records that a race happened.
      assertThatThrownBy(() -> execute(c, """
          update collision_records set "outcome" = 'ABORTED_BY_HUMAN'::"CollisionResolution",
                                      "createdAt" = "createdAt"
           where "counterpartyId" = 'user-A' and "resourceId" = '%s'
             and "collisionWindow" = %d""".formatted(conversation, window)))
          .as("collision_records_aborted_run_check: an ABORTED_BY_HUMAN outcome must name "
              + "the run it aborted -- an unattributable takeover is not evidence of "
              + "anything")
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> insertCollision(c, org, project, conversation,
          "user-D", "WORKFLOW_RUN", window + 97, "ABORTED_BY_HUMAN", "no run named", null))
          .as("...and at insert time, not only on update")
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> execute(c, """
          insert into collision_records (id, "organizationId", "projectId", "scope",
                                         "resourceType", "resourceId", "conversationId",
                                         "customerId", "counterpartyId", "counterpartyType",
                                         "collisionWindow", "outcome", "resolutionDetail",
                                         "observedAt", "createdAt")
          values (gen_random_uuid(), '%s', '%s', 'CONVERSATION'::"CollisionScope", 'Conversation',
                  '%s', '%s', '%s', 'user-C', 'USER', %d, 'KEPT_BOTH'::"CollisionResolution",
                  'both', now(), now())"""
          .formatted(org, project, conversation, conversation, otherConversation, window + 99)))
          .as("collision_records_single_resource_check: a collision is about ONE resource; "
              + "three nullable pointers that can all be set would let a row describe two "
              + "incidents")
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> execute(c, """
          insert into collision_records (id, "organizationId", "projectId", "scope",
                                         "resourceType", "resourceId", "counterpartyId",
                                         "counterpartyType", "collisionWindow", "outcome",
                                         "resolutionDetail", "observedAt", "createdAt")
          values (gen_random_uuid(), '%s', '%s', 'CONVERSATION'::"CollisionScope", 'Conversation',
                  '%s', '', 'USER', %d, 'KEPT_BOTH'::"CollisionResolution", 'both', now(), now())"""
          .formatted(org, project, conversation, window + 98)))
          .as("collision_records_counterparty_check: an unnamed counterparty is an "
              + "unattributable incident")
          .isInstanceOf(SQLException.class);

      // --- cross-tenant negatives, with data -----------------------------
      assertThat(queryInt(c, """
          select count(*) from collision_records r
           where r."organizationId" = '%s'
             and not exists (select 1 from projects p
                              where p.id = r."projectId"
                                and p."organizationId" = r."organizationId")""".formatted(org)))
          .as("no collision row points at another tenant's project")
          .isZero();
      assertThat(queryInt(c, """
          select count(*) from collision_records
           where "resourceId" = '%s' and "organizationId" <> '%s'"""
          .formatted(conversation, org)))
          .as("another tenant's collision is not reachable through this tenant's resource id")
          .isZero();
    }

    // --- TWO CONCURRENT WRITERS: exactly one incident is recorded ---------
    //    Handshake, not a sleep: A inserts and holds its transaction OPEN, so B
    //    races against a row that is definitely there and definitely uncommitted.
    //    SKIP LOCKED is deliberately NOT used -- the point is that the UNIQUE
    //    index makes the second insert a no-op even against an uncommitted first.
    UUID conversation2 = seedConversation(org, project, "collision-race");
    long raceWindow = 1_700_000_500L / 300;
    CountDownLatch aHasInserted = new CountDownLatch(1);
    CountDownLatch bHasTried = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Integer> a = pool.submit(() -> {
        try (Connection c = connect(phase5aUrl)) {
          c.setAutoCommit(false);
          int rows = insertCollision(c, org, project, conversation2, "racer-A", "WORKFLOW_RUN",
              raceWindow, "ABORTED_BY_HUMAN", "human won");
          aHasInserted.countDown();
          assertThat(bHasTried.await(30, TimeUnit.SECONDS))
              .as("writer B reached its insert while A held its transaction open").isTrue();
          c.commit();
          return rows;
        }
      });
      Future<Integer> b = pool.submit(() -> {
        assertThat(aHasInserted.await(30, TimeUnit.SECONDS)).as("writer A inserted").isTrue();
        try (Connection c = connect(phase5aUrl)) {
          c.setAutoCommit(false);
          // Signal BEFORE the insert, not after. B's insert BLOCKS: the unique
          // index makes it wait on A's speculative insertion until A commits. If
          // the signal were raised after the statement, A would wait for B to
          // finish while B waits for A to commit, and the handshake would time
          // out ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¯ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¿ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â½ which is what the first version of this test did.
          bHasTried.countDown();
          int rows = insertCollision(c, org, project, conversation2, "racer-B", "WORKFLOW_RUN",
              raceWindow, "ABORTED_BY_HUMAN", "human won too");
          c.commit();
          return rows;
        }
      });
      assertThat(a.get(60, TimeUnit.SECONDS)).isEqualTo(1);
      assertThat(b.get(60, TimeUnit.SECONDS))
          .as("the second concurrent writer records NOTHING: one incident, counted once")
          .isZero();
    } finally {
      pool.shutdownNow();
    }

    try (Connection c = connect(phase5aUrl)) {
      assertThat(queryInt(c, """
          select count(*) from collision_records
           where "resourceId" = '%s' and "collisionWindow" = %d"""
          .formatted(conversation2, raceWindow)))
          .as("exactly one row exists for the raced incident")
          .isEqualTo(1);
      assertThat(queryInt(c, """
          select count(*) from collision_records
           where "resourceId" = '%s'
             and "outcome" = 'ABORTED_BY_HUMAN'::"CollisionResolution\""""
          .formatted(conversation2)))
          .as("the phase-06 acceptance gate in one number: how often a human takeover "
              + "actually beat an in-flight AI send")
          .isEqualTo(1);
    }
  }

  // ------------------------------------------------------------------
  // Scenario 7a: audit streams, legal hold, privacy deletion
  // ------------------------------------------------------------------

  @Test
  @DisplayName("audit streams are physically separate, a legal hold is storable before any purge, and neither can be attributed across tenants")
  void auditStreamsAreSeparateAndLegalHoldIsRepresentable() throws Exception {
    Tenant tenant = seedTenant("audit");
    UUID org = tenant.organizationId();
    UUID project = tenant.projectId();
    UUID user = seedUser(org, "audit-operator");
    Tenant other = seedTenant("audit-other");
    UUID otherUser = seedUser(other.organizationId(), "audit-other-operator");

    try (Connection c = connect(phase5aUrl)) {
      // --- the legacy table is untouched and still there ----------------
      assertThat(queryInt(c, "select count(*) from pg_tables"
          + " where schemaname='public' and tablename='audit_logs'"))
          .as("audit_logs must still exist: the legacy NestJS runtime still writes it, and "
              + "this migration is additive by contract")
          .isEqualTo(1);
      assertThat(queryStrings(c, """
          select a.attname from pg_class c
            join pg_namespace n on n.oid = c.relnamespace
            join pg_attribute a on a.attrelid = c.oid and a.attnum > 0 and not a.attisdropped
           where n.nspname='public' and c.relname='audit_logs' and a.attname='stream'"""))
          .as("audit_logs has no stream column: the separation is PHYSICAL, not a "
              + "discriminator somebody can forget to filter on")
          .isEmpty();

      // --- LEGAL HOLD, representable BEFORE any purge job exists ---------
      // ADR-0008 rule 5: "A purge job refuses to run for a tenant under legal hold.
      // This is a Phase 7 dependency: Admin must expose hold state before the first
      // purge job is enabled." This is the row that makes that sentence true.
      String holdId = insertLegalHold(c, org, "TENANT", "undisputed patient complaint");
      assertThat(queryInt(c, """
          select count(*) from legal_holds
           where id = '%s' and status = 'ACTIVE' and "placedByUserId" = '%s'
             and length(btrim(reason)) > 0""".formatted(holdId, user)))
          .as("a TENANT-scoped hold with no project is the case ADR-0008 needs, and it is "
              + "expressible without inventing a project")
          .isEqualTo(1);

      // The refusal negatives, each with its own positive control.
      assertThatThrownBy(() -> execute(c, """
          insert into legal_holds (id, "organizationId", "scopeType", reason, status,
                                   "placedAt", "createdAt", "updatedAt")
          values (gen_random_uuid(), '%s', 'PROJECT', 'hold with no project', 'ACTIVE',
                  (now() at time zone 'UTC'), now(), now())""".formatted(org)))
          .as("legal_holds_scope_pair_check: a PROJECT-scoped hold that names no project "
              + "holds NOTHING, which is the one combination a purge job would silently obey")
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> execute(c, """
          insert into legal_holds (id, "organizationId", "scopeType", reason, status,
                                   "placedAt", "createdAt", "updatedAt")
          values (gen_random_uuid(), '%s', 'TENANT', '   ', 'ACTIVE',
                  (now() at time zone 'UTC'), now(), now())""".formatted(org)))
          .as("legal_holds_reason_check: a hold with a blank reason cannot be defended to a "
              + "regulator and cannot be reviewed for release")
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> execute(c, """
          update legal_holds set status = 'RELEASED', "updatedAt" = now()
           where id = '%s'""".formatted(holdId)))
          .as("legal_holds_released_pair_check: a hold cannot claim to be released with no "
              + "release timestamp -- the biconditional half")
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> execute(c, """
          update legal_holds set "releasedAt" = (now() at time zone 'UTC'),
                              "updatedAt" = now()
           where id = '%s'""".formatted(holdId)))
          .as("...and the other direction: a release timestamp while still ACTIVE. "
              + "'released on the 3rd but still holding' is a contradiction an operator "
              + "would act on wrongly")
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> execute(c, """
          update legal_holds set "expiresAt" = date '2020-01-01', "updatedAt" = now()
           where id = '%s'""".formatted(holdId)))
          .as("legal_holds_expiry_check: a hold that ends before it was placed is not a hold")
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> execute(c, """
          update legal_holds set "placedByUserId" = '%s', "updatedAt" = now()
           where id = '%s'""".formatted(otherUser, holdId)))
          .as("legal_holds_placed_by_tenant_fkey refuses another tenant's administrator as "
              + "the placer. A single-column FK permits exactly this and nothing else in "
              + "the schema would notice.")
          .isInstanceOf(SQLException.class);

      // The append-only history is a history, and it is write-only.
      execute(c, """
          insert into legal_hold_events (id, "organizationId", "legalHoldId", "eventType",
                                         "actorUserId", "actorPrincipal", "eventAt", "createdAt")
          values (gen_random_uuid(), '%s', '%s', 'PLACED', '%s', 'audit-operator@example.test',
                  (now() at time zone 'UTC'), now())""".formatted(org, holdId, user));
      // `legal_hold_events` is append-only BY SHAPE, not by trigger: there is no
      // `updatedAt` column at all. An earlier version of this test asserted that
      // `update ... set "note" = 'edited'` was refused, and it was NOT -- it
      // succeeded, because the table has no mechanism to refuse it. Asserting a
      // behaviour the schema does not have is worse than not asserting it: the
      // test would have "passed" for the wrong reason in any future run where the
      // row set differed. The append-only claim is therefore asserted the only way
      // it is actually true -- by the absence of any column an UPDATE would use.
      assertThat(queryStrings(c, """
          select a.attname
            from pg_class cl
              join pg_namespace n on n.oid = cl.relnamespace
              join pg_attribute a on a.attrelid = cl.oid and a.attnum > 0 and not a.attisdropped
           where n.nspname = 'public' and cl.relname = 'legal_hold_events'
             and a.attname in ('updatedAt', 'updatedByUserId', 'deletedAt')"""))
          .as("legal_hold_events is APPEND-ONLY BY SHAPE: it carries no updatedAt, no "
              + "updatedByUserId and no deletedAt, so an UPDATE has nothing to write and a "
              + "soft delete is not expressible. Asserted as SHAPE rather than as a refused "
              + "statement because this table has no trigger -- unlike the six finance tables "
              + "in V20261005090000. Recorded as Phase-8 debt: a grant-level REVOKE UPDATE, or a "
              + "trigger here too, would make this a boundary rather than a convention.")
          .isEmpty();
      assertThat(queryInt(c, """
          select count(*) from pg_trigger t
            join pg_class cl on cl.oid = t.tgrelid
            join pg_namespace n on n.oid = cl.relnamespace
           where n.nspname = 'public' and cl.relname = 'legal_hold_events'
             and not t.tgisinternal"""))
          .as("...and specifically NO trigger is faking that boundary. A future migration that "
              + "adds one is an improvement, but it must be a deliberate one with its own "
              + "negative test rather than something this assertion silently absorbs.")
          .isZero();
      assertThatThrownBy(() -> execute(c, """
          insert into legal_hold_events (id, "organizationId", "legalHoldId", "eventType",
                                         "actorPrincipal", "eventAt", "createdAt")
          values (gen_random_uuid(), '%s', '%s', 'PLACED', '  ',
                  (now() at time zone 'UTC'), now())""".formatted(org, holdId)))
          .as("legal_hold_events_actor_check: an event with neither a user nor a named "
              + "principal is unattributable")
          .isInstanceOf(SQLException.class);

      // --- the admin / security / integration streams ------------------
      String adminEvent = insertAdminAuditEvent(c, org, project, user, "billing.plan.changed");
      // AuditOutcome is SUCCESS / DENIED / FAILED and nothing else. An earlier
      // version passed 'PERMISSION_DENIED' here, which is not a member -- so this
      // helper was testing that the enum rejects an unknown label while the test
      // was asserting that a denied security event carries a reason. Both matter;
      // they are now separate assertions, and the closed-list one is asserted
      // below on its own row.
      String securityEvent = insertSecurityEvent(c, org, project, user, "LOGIN_FAILED",
          "DENIED", "no such project");
      assertThatThrownBy(() -> insertSecurityEvent(c, org, project, user, "LOGIN_FAILED",
          "PERMISSION_DENIED", "no such project"))
          .as("AuditOutcome is a CLOSED list (SUCCESS, DENIED, FAILED). An outcome vocabulary "
              + "that accepts a new label by default is one a dashboard filter cannot rely on, "
              + "and 'PERMISSION_DENIED' is exactly the label somebody would reach for -- which "
              + "is why the schema says DENIED and leaves the specificity to eventType.")
          .isInstanceOf(SQLException.class);
      String integrationEvent = insertJobIntegrationEvent(c, org, project, "outbox-worker",
          "JOB_FAILED", "FAILED", "PROVIDER_DOWN");
      assertThat(queryInt(c, """
          select count(*) from admin_audit_events where id='%s'
           and "principalType" = 'USER'::"AuditPrincipalType\"""".formatted(adminEvent)))
          .isEqualTo(1);
      assertThat(queryInt(c, """
          select count(*) from security_events where id='%s'
           and outcome = 'DENIED'::"AuditOutcome" and length(btrim("denyReason")) > 0"""
          .formatted(securityEvent)))
          .as("FR-6.9: a denied security event must say why, or the denial stream is a list "
              + "of timestamps with no diagnostic value")
          .isEqualTo(1);
      assertThatThrownBy(() -> execute(c, """
          update security_events set outcome = 'DENIED'::"AuditOutcome",
                                     "denyReason" = null
           where id = '%s'""".formatted(securityEvent)))
          .as("security_events_deny_reason_check: a DENIED outcome with no reason")
          .isInstanceOf(SQLException.class);
      assertThat(queryInt(c, """
          select count(*) from job_integration_events where id='%s'
           and outcome = 'FAILED'::"AuditOutcome" and "failureCode" = 'PROVIDER_DOWN'"""
          .formatted(integrationEvent)))
          .isEqualTo(1);
      assertThatThrownBy(() -> execute(c, """
          update admin_audit_events set outcome = 'DENIED'::"AuditOutcome",
                                       "denyReason" = 'no reason column here'
           where id = '%s'""".formatted(adminEvent)))
          .as("...and an admin event cannot be marked DENIED without a reason column that "
              + "does not exist, so the shapes stay distinct")
          .isInstanceOf(SQLException.class);

      // --- THE ATTRIBUTION FK, with data and a positive control ----------
      assertThatThrownBy(() -> insertAdminAuditEvent(c, org, null, otherUser,
          "other.tenant.operator.attributed.here"))
          .as("admin_audit_events_actor_tenant_fkey: an event in tenant A may not name tenant "
              + "B's administrator. Here ONLY the actor is wrong -- the organizationId is "
              + "tenant A's -- so the refusal is the cross-tenant reference and nothing else.")
          .isInstanceOf(SQLException.class);
      assertThat(insertAdminAuditEvent(c, org, null, user, "tenant.a.operator.attributed"))
          .as("the same shape with a consistent tenant is accepted, so the refusal above is "
              + "proving the cross-tenant reference and not that the table rejects everything")
          .isNotNull();

      // --- privacy deletion: a state machine, not a flag -----------------
      String request = insertPrivacyDeletionRequest(c, org, user, "customer asked to be forgotten");
      assertThatThrownBy(() -> execute(c, """
          update privacy_deletion_requests
             set "startedAt" = (now() at time zone 'UTC'), "updatedAt" = now()
           where id = '%s'""".formatted(request)))
          .as("privacy_deletion_requests_start_requires_verified_check: deleting a tenant's "
              + "data on an UNVERIFIED request is irreversible, so the wrong order must be "
              + "impossible rather than merely discouraged")
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> execute(c, """
          update privacy_deletion_requests set "verifiedAt" = (now() at time zone 'UTC'),
                                            "updatedAt" = now()
           where id = '%s'""".formatted(request)))
          .as("privacy_deletion_requests_verified_pair_check: verification without a verifier")
          .isInstanceOf(SQLException.class);
      // The seeded row already carries an evidence reference, so the negative has to
      // REMOVE it. An earlier version only set status and completedAt and expected
      // a refusal -- and got none, correctly: with evidenceReference still present
      // the constraint is satisfied and the row is legal. A negative that cannot
      // fail because the fixture pre-satisfied the constraint is not a test of that
      // constraint.
      assertThatThrownBy(() -> execute(c, """
          update privacy_deletion_requests set status = 'COMPLETED'::"PrivacyDeletionRequestStatus",
                                            "completedAt" = (now() at time zone 'UTC'),
                                            "evidenceReference" = null,
                                            "updatedAt" = now()
           where id = '%s'""".formatted(request)))
          .as("privacy_deletion_requests_evidence_check: a COMPLETED deletion of personal "
              + "data must point at what was removed, or FR-7 privacy reporting cannot be "
              + "answered. evidenceReference is cleared HERE on purpose -- leaving the seeded "
              + "value in place would satisfy the constraint and the row would be legal.")
          .isInstanceOf(SQLException.class);
      assertThat(queryInt(c, """
          select count(*) from privacy_deletion_requests
           where id = '%s' and status = 'REQUESTED'::"PrivacyDeletionRequestStatus"
             and "completedAt" is null""".formatted(request)))
          .as("...and the row is untouched: the refused UPDATE rolled back, so the request is "
              + "still REQUESTED with no completion time. Asserted because a constraint "
              + "violation in autocommit leaves no partial row, and a test that assumed "
              + "otherwise would be relying on that silently.")
          .isEqualTo(1);
      // The real path: verify, then start.
      assertThat(update(c, """
          update privacy_deletion_requests
             set "verifiedAt" = (now() at time zone 'UTC'),
                 "verifiedByUserId" = ?,
                 "startedAt" = (now() at time zone 'UTC'),
                 "startedByUserId" = ?,
                 status = 'IN_PROGRESS'::"PrivacyDeletionRequestStatus",
                 "updatedAt" = (now() at time zone 'UTC')
           where id = ?""", user, user, UUID.fromString(request))).isEqualTo(1);

      // A target kept under legal hold MUST name the hold, both directions.
      assertThatThrownBy(() -> execute(c, """
          insert into privacy_deletion_targets (id, "organizationId", "deletionRequestId",
              "targetType", "targetRef", decision, "decidedAt", "createdAt")
          values (gen_random_uuid(), '%s', '%s', 'CONVERSATION', 'undo-conv',
                  'RETAINED_LEGAL_HOLD', (now() at time zone 'UTC'), now())"""
          .formatted(org, request)))
          .as("privacy_deletion_targets_legal_hold_check: 'we kept this because a hold "
              + "existed' with no hold named is not a decision anybody can audit")
          .isInstanceOf(SQLException.class);
      assertThat(execute(c, """
          insert into privacy_deletion_targets (id, "organizationId", "deletionRequestId",
              "targetType", "targetRef", decision, "legalHoldId", "decidedAt", "createdAt")
          values (gen_random_uuid(), '%s', '%s', 'CONVERSATION', 'undo-conv',
                  'RETAINED_LEGAL_HOLD', '%s', (now() at time zone 'UTC'), now())"""
          .formatted(org, request, holdId))).isEqualTo(1);
      // "targetType" is camelCase and MUST stay quoted in the INSERT column list
      // as well as in a SET clause. An earlier version wrote it unquoted in all
      // three statements here; PostgreSQL folds an unquoted identifier to
      // `targettype`, so the INSERTs failed with "column does not exist" -- and an
      // INSERT that fails for the wrong reason still counts as a refusal to the
      // assertThatThrownBy around it. Quoted everywhere now.
      assertThatThrownBy(() -> execute(c, """
          update privacy_deletion_targets set decision = 'REMOVED', "legalHoldId" = null,
                                           "targetType" = 'CONVERSATION',
                                           "createdAt" = "createdAt"
           where "legalHoldId" = '%s'""".formatted(holdId)))
          .as("privacy_deletion_targets_legal_hold_check, the other direction: a row that "
              + "claims to have REMOVED data while still citing the hold that kept it reads "
              + "as 'removed, and there was a hold', which is the worst possible ambiguity "
              + "in a privacy decision")
          .isInstanceOf(SQLException.class);

      // --- the retention class is DATA, and it is tenant-scoped ----------
      assertThat(execute(c, """
          insert into retention_classes (id, "organizationId", "classCode", stream,
                                         "retentionDays", "legalHoldExempt", "createdByUserId",
                                         "createdAt", "updatedAt")
          values (gen_random_uuid(), '%s', 'AUDIT_STANDARD', 'ADMIN'::"AuditStreamKind",
                  2555, true, '%s', now(), now())""".formatted(org, user))).isEqualTo(1);
      assertThatThrownBy(() -> execute(c, """
          insert into retention_classes (id, "organizationId", "classCode", stream,
                                         "retentionDays", "createdAt", "updatedAt")
          values (gen_random_uuid(), '%s', 'ZERO_DAYS', 'ADMIN'::"AuditStreamKind",
                  0, now(), now())""".formatted(org)))
          .as("retention_classes_retention_days_check: a zero-day window is a purge "
              + "immediately, which is not a retention policy")
          .isInstanceOf(SQLException.class);
      assertThat(execute(c, """
          insert into retention_classes (id, "organizationId", "classCode", stream,
                                         "retentionDays", "createdAt", "updatedAt")
          select gen_random_uuid(), '%s', 'AUDIT_STANDARD', 'ADMIN'::"AuditStreamKind",
                 2555, now(), now()
           where false""".formatted(other.organizationId())))
          .as("uniqueness is TENANT-scoped: tenant B may declare the same class code, so a "
              + "global index could never turn the violation message into a cross-tenant "
              + "existence oracle")
          .isZero();
    }
  }

  // ------------------------------------------------------------------
  // Scenario 7b: export jobs -- the state machine and the pinned report
  // ------------------------------------------------------------------

  @Test
  @DisplayName("export jobs run the legal state machine, pin a report version, and refuse a cross-tenant pin")
  void exportJobsRunTheLegalStateMachineAndPinARecord() throws Exception {
    Tenant tenant = seedTenant("export");
    UUID org = tenant.organizationId();
    UUID project = tenant.projectId();
    UUID requester = seedUser(org, "export-requester");
    Tenant other = seedTenant("export-other");
    UUID otherRequester = seedUser(other.organizationId(), "export-other-requester");

    String reportId;
    String otherReportId;
    try (Connection c = connect(phase5aUrl)) {
      reportId = seedReportDefinition(c, org, "undo-export-report");
      otherReportId = seedReportDefinition(c, other.organizationId(), "undo-export-report");
      String versionId = seedReportVersion(c, org, reportId, requester, 1);
      assertThat(versionId).as("a report version is storable").isNotNull();
    }

    try (Connection c = connect(phase5aUrl)) {
      // --- QUEUED is a legal state and needs nothing but the evidence ----
      String jobId = insertExportJob(c, org, project, requester, reportId, 1);
      assertThat(queryString(c,
          "select status::text from export_jobs where id = '" + jobId + "'"))
          .as("the machine starts at QUEUED")
          .isEqualTo("QUEUED");

      // The insertion negatives, each proved on its OWN row.
      assertThatThrownBy(() -> insertExportJobWithVersion(c, org, project, requester, reportId,
          null))
          .as("export_jobs_report_pin_check: a report DEFINITION with no pinned VERSION "
              + "resolves to whatever is published when the job RUNS, which is the "
              + "reproducibility bug ADR-0007 rule 2 is about in another table")
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> insertExportJobWithSnapshot(c, org, project, requester, reportId,
          1, "{}"))
          .as("export_jobs_authorization_snapshot_check: an EMPTY snapshot looks recorded "
              + "and is worse than an absent one")
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> insertExportJobWithSnapshot(c, org, project, requester, reportId,
          1, "[\"role\"]"))
          .as("...and a snapshot that is an ARRAY is a payload, not evidence")
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> insertExportJobWithChecksum(c, org, project, requester, reportId,
          1, "not-a-checksum"))
          .as("export_jobs_checksum_format_check: \"some string\" as a checksum cannot be "
              + "compared against anything, and FR-8.7's \"the export is the one we "
              + "produced\" test needs a byte-comparable value")
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> insertExportJobWithStorageKey(c, org, project, requester,
          reportId, 1, "https://objects.example/undo.csv?sig=abc"))
          .as("export_jobs_storage_key_check: a presigned URL in a database row is a bearer "
              + "credential with an expiry nobody manages. The signed URL is minted per "
              + "download and recorded in export_download_events with its own expiry.")
          .isInstanceOf(SQLException.class);

      // --- the legal transitions ----------------------------------------
      assertThat(startExport(c, org, jobId)).isEqualTo(1);
      String queuedJob = insertExportJob(c, org, project, requester, reportId, 1);
      assertThat(queryString(c,
          "select status::text from export_jobs where id = '" + queuedJob + "'"))
          .as("the negative below proves its own precondition rather than assuming it: this row "
              + "is genuinely QUEUED, with no startedAt, so a refusal is attributable to the "
              + "constraint and not to a stale row from an earlier block")
          .isEqualTo("QUEUED");
      assertThatThrownBy(() -> execute(c, """
          update export_jobs set "finishedAt" = (now() at time zone 'UTC'),
                              "updatedAt" = (now() at time zone 'UTC')
           where id = '%s'""".formatted(queuedJob)))
          .as("export_jobs_finished_requires_start_check: a finish time with NO start time. "
              + "\"finished at 12:04\" against \"started at NULL\" is the row a report "
              + "generator cannot reconcile, and the shape is only expressible while the "
              + "machine has not yet run -- which is why this negative owns a fresh QUEUED "
              + "row rather than reusing the job that was just started. An earlier version "
              + "reused that job, by which point it HAD a startedAt, so the constraint was "
              + "satisfied and the statement legitimately succeeded: a negative test that "
              + "cannot fail is not a test.")
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> completeExport(c, org, jobId, "sha256:not-hex"))
          .as("export_jobs_checksum_format_check on the transition, not only at insert")
          .isInstanceOf(SQLException.class);
      assertThat(completeExport(c, org, jobId,
          "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"))
          .isEqualTo(1);
      assertThat(queryInt(c, """
          select count(*) from export_jobs
           where id = '%s' and status = 'COMPLETED'::"ExportJobStatus"
             and "progressPct" = 100 and "rowCount" = 0 and "storageKey" is not null
             and "checksum" is not null and "finishedAt" is not null""".formatted(jobId)))
          .as("COMPLETED means an object, a checksum, a row count and 100% progress, and "
              + "every one of them is CHECKed")
          .isEqualTo(1);

      // --- the illegal one: DELETED while the object still exists --------
      assertThatThrownBy(() -> tombstoneExport(c, org, jobId, requester, true))
          .as("export_jobs_deleted_check: a DELETED export that still points at storage is "
              + "the exact state the whole deletion design exists to prevent, because the "
              + "object is supposed to be gone")
          .isInstanceOf(SQLException.class);

      // --- an attempt is append-only, and retry is a NEW row -------------
      String attempt1 = recordExportAttempt(c, org, project, jobId, 1, "SUCCEEDED", 1200L);
      assertThatThrownBy(() -> recordExportAttempt(c, org, project, jobId, 1, "SUCCEEDED", 5L))
          .as("export_job_attempts_job_attempt_key: two racing schedulers cannot both record "
              + "attempt 1, so the retry count cannot be inflated")
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> recordExportAttemptFailed(c, org, project, jobId, 2))
          .as("export_job_attempts_error_check: a failure with no code and no message is not "
              + "a diagnosable failure (FR-6.7)")
          .isInstanceOf(SQLException.class);
      String outcome1 = queryString(c,
          "select outcome from export_job_attempts where id = '" + attempt1 + "'");
      recordExportAttempt(c, org, project, jobId, 2, "SUCCEEDED", 900L);
      assertThat(queryString(c,
          "select outcome from export_job_attempts where id = '" + attempt1 + "'"))
          .as("a retry creates a row; it never mutates the earlier one")
          .isEqualTo(outcome1);

      // --- the download record: GRANTED carries a signature with an expiry -
      String signatureExpiry = "2026-01-01T00:05:00";
      execute(c, """
          insert into export_download_events (id, "organizationId", "projectId",
              "exportJobId", "actorUserId", "principalName", outcome, "signatureId",
              "signatureExpiresAt", "exportExpiresAt", "occurredAt", "createdAt")
          values (gen_random_uuid(), '%s', '%s', '%s', '%s', 'export-requester@example.test',
                  'GRANTED'::"ExportDownloadOutcome", 'sig-undo-1',
                  timestamp '%s', timestamp '2026-01-01 00:10:00',
                  (now() at time zone 'UTC'), now())"""
          .formatted(org, project, jobId, requester, signatureExpiry));
      assertThatThrownBy(() -> execute(c, """
          insert into export_download_events (id, "organizationId", "projectId",
              "exportJobId", "principalName", outcome, "denyReason", "occurredAt", "createdAt")
          values (gen_random_uuid(), '%s', '%s', '%s', 'someone@example.test',
                  'DENIED'::"ExportDownloadOutcome", null,
                  (now() at time zone 'UTC'), now())""".formatted(org, project, jobId)))
          .as("export_download_events_deny_reason_check: a refusal that says nothing")
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> execute(c, """
          insert into export_download_events (id, "organizationId", "projectId",
              "exportJobId", "principalName", outcome, "signatureId", "signatureExpiresAt",
              "exportExpiresAt", "occurredAt", "createdAt")
          values (gen_random_uuid(), '%s', '%s', '%s', 'someone@example.test',
                  'GRANTED'::"ExportDownloadOutcome", 'sig-2',
                  timestamp '2026-01-01 00:30:00', timestamp '2026-01-01 00:10:00',
                  (now() at time zone 'UTC'), now())""".formatted(org, project, jobId)))
          .as("export_download_events_signature_expiry_check: a signature that OUTLIVES the "
              + "export it was minted for is a second expiry to get wrong")
          .isInstanceOf(SQLException.class);

      // --- deletion is a tombstone plus an event, and the event is RESTRICTed -
      assertThat(tombstoneExport(c, org, jobId, requester, false)).isEqualTo(1);
      assertThat(queryInt(c, """
          select count(*) from export_jobs
           where id = '%s' and status = 'DELETED'::"ExportJobStatus"
             and "deletedAt" is not null and "storageKey" is null""".formatted(jobId)))
          .as("DELETED clears the object key and keeps the row as evidence")
          .isEqualTo(1);
      execute(c, """
          insert into export_deletion_events (id, "organizationId", "projectId",
              "exportJobId", outcome, "storageKeyAtDelete", "bytesReclaimed",
              "requestedByUserId", "legalHoldChecked", "legalHoldId", "occurredAt", "createdAt")
          values (gen_random_uuid(), '%s', '%s', '%s',
                  'STORAGE_OBJECT_DELETED'::"ExportDeletionOutcome",
                  'undo/objects/undo.csv', 2048, '%s', true, null,
                  (now() at time zone 'UTC'), now())"""
          .formatted(org, project, jobId, requester));
      assertThatThrownBy(() -> execute(c, "delete from export_jobs where id = '%s'".formatted(jobId)))
          .as("export_deletion_events_job_tenant_fkey is ON DELETE RESTRICT: a CASCADE would "
              + "let removing the job row destroy the only evidence that the object's storage "
              + "was cleaned up, which is exactly what an operator needs when a customer asks "
              + "\"did you really delete my file?\"")
          .isInstanceOf(SQLException.class);

      // --- THE CROSS-TENANT PIN -----------------------------------------
      assertThatThrownBy(() -> insertExportJob(c, org, project, requester, otherReportId, 1))
          .as("export_jobs_report_tenant_fkey: an export in tenant A may not pin tenant B's "
              + "report definition. EVERY row here is tenant A's except the report, so the "
              + "only thing wrong is the cross-tenant reference -- which is precisely what a "
              + "single-column FK would permit and nothing else would notice.")
          .isInstanceOf(SQLException.class);
      assertThat(insertExportJob(c, other.organizationId(), other.projectId(), otherRequester,
          otherReportId, 1))
          .as("the same shape with a CONSISTENT tenant B is accepted, so the refusal above is "
              + "proving the cross-tenant reference and not that the table rejects everything")
          .isNotNull();
      assertThatThrownBy(() -> insertExportJobWithRequester(c, org, project, otherRequester,
          reportId, 1))
          .as("export_jobs_requester_tenant_fkey: the authorization snapshot belongs to "
              + "somebody, and that somebody has to belong to this tenant")
          .isInstanceOf(SQLException.class);
    }
  }

  // ------------------------------------------------------------------
  // Scenario 8: usage ledger and finance -- revenue is DERIVED
  // ------------------------------------------------------------------

  @Test
  @DisplayName("the ledger stores cost only, revenue is derived from a pinned rate, and a rate change cannot mutate a closed snapshot")
  void revenueIsDerivedFromAPinnedRateAndARateChangeCannotMutateASnapshot() throws Exception {
    Tenant tenant = seedTenant("finance");
    UUID org = tenant.organizationId();
    UUID project = tenant.projectId();
    UUID author = seedUser(org, "finance-author");
    Tenant other = seedTenant("finance-other");
    UUID otherAuthor = seedUser(other.organizationId(), "finance-other-author");

    String cardId;
    String version1Id;
    String periodId;
    try (Connection c = connect(phase5aUrl)) {
      cardId = insertRateCard(c, org, "undo-card");
      version1Id = insertRateCardVersion(c, org, cardId, author, 1, "2.000000",
          "2026-01-01", null);
      periodId = insertFinancePeriod(c, org, "2026-01", "2026-01-01", "2026-01-31");
      assertThat(version1Id).isNotNull();
    }

    try (Connection c = connect(phase5aUrl)) {
      // --- 1. usage_events holds COST AND NOTHING DERIVED ----------------
      assertThat(queryStrings(c, """
          select a.attname from pg_class cl
            join pg_namespace n on n.oid = cl.relnamespace
            join pg_attribute a on a.attrelid = cl.oid and a.attnum > 0 and not a.attisdropped
           where n.nspname='public' and cl.relname='usage_events'
             and (a.attname ilike '%revenue%' or a.attname ilike '%billable%'
                  or a.attname ilike '%margin%'
                  or lower(a.attname) in ('price','markup','markuprate'))"""))
          .as("ADR-0007 Part 1: revenue is DERIVED, never a stored input. A stored revenue on "
              + "the immutable event is a denormalised copy of a rate-card computation, and "
              + "when a rate card is corrected with retroactive effect it can only be wrong "
              + "(and need an UPDATE this table forbids) or a correction row for money that "
              + "was never authoritative.")
          .isEmpty();
      assertThat(queryStrings(c, """
          select a.attname from pg_class cl
            join pg_namespace n on n.oid = cl.relnamespace
            join pg_attribute a on a.attrelid = cl.oid and a.attnum > 0 and not a.attisdropped
           where n.nspname='public' and cl.relname='usage_events'
             and a.attname='rateCardVersionId'"""))
          .as("and no rate reference either: the rate effective at the usage date is resolved "
              + "once, at rollup time, and pinned on the snapshot")
          .isEmpty();

      String eventId = insertUsageEvent(c, org, project, "10.000000", 1000, "2026-01-15");
      assertThat(queryInt(c, """
          select count(*) from usage_events
           where id = '%s' and "costAmount" = 10.000000 and currency = 'USD'
             and "fxSource" is null and "fxRateDate" is null""".formatted(eventId)))
          .as("cost and currency, and NO fx unless the rate, its source AND its date all "
              + "travel together")
          .isEqualTo(1);
      assertThatThrownBy(() -> insertUsageEventWithFxRateOnly(c, org, project))
          .as("usage_events_fx_pair_check: a conversion that does not say where its rate came "
              + "from is not reproducible")
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> insertUsageEventOutsidePeriod(c, org, project))
          .as("usage_events_period_contains_event_check: an event dated outside the period it "
              + "claims, because a period boundary that moves after the fact is how a LOCKED "
              + "period stops agreeing with its own events")
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> insertUsageEventWithCorrectionOnly(c, org, project, eventId))
          .as("usage_events_correction_check: a correction with no reason is indistinguishable "
              + "from tampering with money")
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> insertUsageEventNegativeQuantity(c, org, project))
          .as("usage_events_quantity_check: a negative quantity is a sign error, and a discount "
              + "is a rate-card line rather than a usage event")
          .isInstanceOf(SQLException.class);
      // The provider invoice is the same source event for every correction below, which
      // is what makes the duplicate-suppression negative below a real duplicate.
      String providerInvoice = UUID.randomUUID().toString();
      assertThat(insertUsageEventCorrecting(c, org, project, eventId, providerInvoice,
          "11.000000", "provider corrected the invoice"))
          .isNotNull();
      assertThatThrownBy(() -> insertUsageEventCorrecting(c, org, project, eventId,
          providerInvoice, "12.000000", "second correction of the same source"))
          .as("usage_events_source_dimension_key: duplicate suppression is at the SOURCE and "
              + "is TENANT-scoped -- the draft's version omitted organizationId and was a "
              + "GLOBAL constraint on a tenant-scoped table, which both collides two tenants "
              + "and discloses one to the other. The same sourceEventId is reused on purpose: "
              + "with a fresh random id per call this negative could never fail.")
          .isInstanceOf(SQLException.class);
      // Duplicate suppression must be tenant-scoped, and that is only demonstrable if
      // tenant B's row is a duplicate OF ITSELF. An earlier version had tenant B
      // correct TENANT A's event, which `usage_events_correction_tenant_fkey`
      // (correcting an event in your own tenant or not at all) correctly refuses --
      // so the assertion was measuring the composite FK, not the unique index. The
      // source event is now seeded per tenant.
      String otherEvent = insertUsageEvent(c, other.organizationId(), other.projectId(),
          "10.000000", 1000, "2026-01-15");
      assertThat(insertUsageEventCorrecting(c, other.organizationId(), other.projectId(),
          otherEvent, providerInvoice, "13.000000",
          "another tenant's correction of the same provider invoice"))
          .as("...and the SAME provider source event corrected by ANOTHER tenant is not a "
              + "collision, so the tenant can never be probed across tenants. This is the "
              + "assertion a GLOBAL unique index would fail and a tenant-scoped one passes: "
              + "both tenants carry (organizationId, 'undo.ProviderInvoice', providerInvoice, "
              + "AI_TOKEN) and neither sees the other.")
          .isNotNull();
      assertThat(queryInt(c, """
          select count(*) from usage_events
           where "sourceEventType" = 'undo.ProviderInvoice' and "sourceEventId" = '%s'
             and "organizationId" = '%s'"""
          .formatted(providerInvoice, other.organizationId())))
          .as("...and tenant B ended with its own single correction, not zero and not two")
          .isEqualTo(1);
      assertThatThrownBy(() -> insertUsageEventCorrecting(c, other.organizationId(),
          other.projectId(), eventId, providerInvoice, "14.000000",
          "tenant B corrects tenant A's event"))
          .as("usage_events_correction_tenant_fkey: a correction must point at an event in "
              + "the correcting row's OWN tenant. A single-column FK on correctionOfId would "
              + "accept this and let tenant B write a correction against tenant A's cost -- "
              + "which is not a privacy leak, it is a forged financial fact.")
          .isInstanceOf(SQLException.class);

      // --- 2. the snapshot DERIVES revenue from the PINNED markup --------
      String snapshotId = insertRollupSnapshot(c, org, project, periodId, "10.000000",
          "20.000000", "2.000000", version1Id, 1);
      assertThat(queryInt(c, """
          select count(*) from usage_rollup_snapshots
           where id = '%s' and "revenueAmount" = "costAmount" * "markupRate"
             and "rateCardVersionId" = '%s' and "markupRate" = 2.000000""".formatted(
                 snapshotId, version1Id)))
          .as("usage_rollup_snapshots_derivation_check: revenue = cost x the PINNED markup, "
              + "exactly. The markup VALUE is stored next to the revenue, which is what makes "
              + "\"changing a rate card never mutates a closed snapshot\" a property of the row "
              + "rather than a promise.")
          .isEqualTo(1);

      assertThatThrownBy(() -> insertRollupSnapshot(c, org, project, periodId, "10.000000",
          "99.000000", "2.000000", version1Id, 2))
          .as("...and a revenue that does not equal its own inputs is refused by the DATABASE. "
              + "A hand-entered revenue is the failure mode this rule exists to prevent.")
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> insertRollupSnapshotHalfPriced(c, org, project, periodId,
          "10.000000", "20.000000", null, version1Id, 3))
          .as("usage_rollup_snapshots_pricing_pin_check: priced means ALL THREE of "
              + "rateCardVersionId, markupRate and revenueAmount present, and unpriced means all "
              + "three absent. There is no state with a revenue and no rate behind it.")
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> insertRollupSnapshotEmpty(c, org, project, periodId))
          .as("usage_rollup_snapshots_source_count_check: a bucket with NO contributing events "
              + "must not be materialised -- materialising it as zero is exactly how \"no data\" "
              + "becomes indistinguishable from zero in a dashboard (ADR-0007 rule 5)")
          .isInstanceOf(SQLException.class);
      assertThat(insertUnpricedRollupSnapshot(c, org, project, periodId))
          .as("an UNPRICED bucket is a first-class state (ADR-0007), not an error and not zero")
          .isNotNull();
      assertThatThrownBy(() -> insertRollupSnapshotWithOtherTenantRate(c, org, project,
          periodId, otherAuthor))
          .as("usage_rollup_snapshots_rate_card_tenant_fkey: pinning another tenant's rate card "
              + "version would put one tenant's price on another tenant's revenue")
          .isInstanceOf(SQLException.class);

      // --- 3. A RATE CHANGE MUST NOT MUTATE THE SNAPSHOT -----------------
      // A second version of the same card at 3.0, plus a RETIRE of the first with
      // the successor named. Neither touches the snapshot, because the snapshot
      // pinned the VALUE, not a live lookup.
      String version2Id = insertRateCardVersion(c, org, cardId, author, 2, "3.000000",
          "2026-02-01", null);
      // The ONE legal UPDATE on rate_card_versions. This assertion exists because
      // the migration originally attached the blanket firstelement_append_only()
      // here, which refuses every UPDATE -- so RETIRED was unreachable and
      // rate_card_versions_retired_check was dead code. The migration now carries
      // a narrower firstelement_rate_card_version_guard(). If this line ever needs
      // the blanket guard again, the retirement becomes inexpressible and a
      // historical snapshot's pinned version is left dangling.
      assertThat(retireRateCardVersion(c, org, version1Id, author, version2Id)).isEqualTo(1);
      assertThatThrownBy(() -> retireRateCardVersionWithoutSuccessor(c, org, version2Id, author))
          .as("rate_card_versions_retired_check: retiring a version says who, when, why AND "
              + "what replaced it, so a historical snapshot's pinned version is never dangling")
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> update(c, """
          update public.rate_card_versions set "markupRate" = 9.000000
           where id = '%s'""".formatted(version1Id)))
          .as("...and once RETIRED the pricing is FROZEN: firstelement_rate_card_version_guard "
              + "refuses any further UPDATE. This is the direction that actually destroys "
              + "history -- the row is what every rollup snapshot pinned, so a markup that "
              + "moves after the fact silently rewrites revenue nobody can recompute.")
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> execute(c, """
          delete from rate_card_versions where id = '%s'""".formatted(version1Id)))
          .as("...and DELETE is refused outright: a price that applied on a date is history, and "
              + "a deleted version leaves usage_rollup_snapshots pointing at nothing. Supersede "
              + "with a new version instead of deleting this one.")
          .isInstanceOf(SQLException.class);
      assertThat(queryInt(c, """
          select count(*) from usage_rollup_snapshots
           where id = '%s' and "revenueAmount" = 20.000000 and "markupRate" = 2.000000
             and "rateCardVersionId" = '%s'""".formatted(snapshotId, version1Id)))
          .as("THE CRITICAL ASSERTION: after the rate card moved from a 2.0 to a 3.0 markup, "
              + "the January snapshot still reads revenue 20.000000 at markup 2.000000 against "
              + "the ORIGINAL pinned version. ADR-0007 rule 2 -- changing a rate card never "
              + "mutates an already-computed snapshot -- is a property of the row, not a "
              + "convention.")
          .isEqualTo(1);
      assertThat(queryInt(c, """
          select count(*) from usage_rollup_snapshots
           where "revenueAmount" is not null
             and "revenueAmount" <> round("costAmount" * "markupRate", 6)"""))
          .as("and with data: no snapshot in the database has a revenue that disagrees with "
              + "its own pinned inputs")
          .isZero();

      // --- 4. a recomputation is a NEW GENERATION, never an update -------
      assertThatThrownBy(() -> execute(c, """
          update usage_rollup_snapshots set "revenueAmount" = 30.000000
           where id = '%s'""".formatted(snapshotId)))
          .as("the append-only TRIGGER, not merely the absence of updatedAt: "
              + "usage_rollup_snapshots refuses an UPDATE outright. \"Append-only enforced by "
              + "convention is not append-only\" was the draft's own words.")
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> execute(c, """
          update usage_events set "costAmount" = 999.000000
           where id = '%s'""".formatted(eventId)))
          .as("...and the immutable LEDGER refuses one too")
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> execute(c, """
          delete from usage_events where id = '%s'""".formatted(eventId)))
          .as("...including DELETE: a correction is a NEW row, never a removal")
          .isInstanceOf(SQLException.class);
      String generation2 = insertRollupSnapshotGeneration2(c, org, project, periodId,
          snapshotId, "10.000000", "30.000000", "3.000000", version2Id);
      assertThat(queryInt(c, """
          select count(*) from usage_rollup_snapshots
           where id = '%s' and generation = 2 and "supersedesSnapshotId" = '%s'""".formatted(
               generation2, snapshotId)))
          .as("a recomputation ADDS a row with the next generation and a supersede pointer, so "
              + "generation N's arithmetic is frozen and both are still visible")
          .isEqualTo(1);
      assertThat(queryInt(c, """
          select count(*) from usage_rollup_snapshots
           where id = '%s' and "revenueAmount" = 20.000000""".formatted(snapshotId)))
          .as("...and generation 1 is untouched")
          .isEqualTo(1);

      // --- 5. a LOCKED period refuses new snapshots ----------------------
      assertThat(lockFinancePeriod(c, org, periodId, author)).isEqualTo(1);
      // This negative needs its OWN period. An earlier version reused `periodId`,
      // which by now had lockedAt AND lockedByUserId set by the lock above -- so
      // finance_periods_locked_check's biconditional was satisfied and the
      // statement legitimately SUCCEEDED. The fresh row below starts OPEN with no
      // lock audit columns at all, which is the shape the constraint exists to
      // refuse.
      String unlockedPeriod = insertFinancePeriod(c, org, "undo_period_never_locked",
          "2026-04-01", "2026-04-30");
      assertThat(queryString(c, """
          select status::text from finance_periods where id = '%s'""".formatted(unlockedPeriod)))
          .as("...and the precondition is asserted, not assumed: this row is genuinely OPEN "
              + "with no lockedAt, so a refusal is attributable to the constraint")
          .isEqualTo("OPEN");
      assertThatThrownBy(() -> execute(c, """
          update finance_periods set status = 'LOCKED'::"PeriodStatus",
                                   "lockedAt" = (now() at time zone 'UTC'),
                                   "updatedAt" = now()
           where id = '%s'""".formatted(unlockedPeriod)))
          .as("finance_periods_locked_check: a LOCKED period with no lockedByUserId is a lock "
              + "nobody can prove happened. The rule is ONE-WAY -- being LOCKED demands BOTH "
              + "audit columns -- and deliberately not a biconditional: a REOPENED period keeps "
              + "its lock columns so \"was this ever closed?\" stays answerable from the row. "
              + "A biconditional here made the ADR-0007 rule 4 reopen path inexpressible.")
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> insertRollupSnapshot(c, org, project, periodId, "10.000000",
          "30.000000", "3.000000", version2Id, 4))
          .as("usage_rollup_snapshots_locked_period_guard: a CLOSED period accepts no new "
              + "snapshot (ADR-0007 rule 4). This cannot be a CHECK constraint -- the locked "
              + "state lives in ANOTHER ROW -- so it is a trigger, and a trigger is droppable "
              + "where a CHECK is not.")
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> execute(c, """
          update finance_periods set status = 'REOPENED'::"PeriodStatus",
                                   "reopenedAt" = (now() at time zone 'UTC'),
                                   "updatedAt" = now()
           where id = '%s'""".formatted(periodId)))
          // The same rule of its own row: periodId is LOCKED with lockedAt and
          // lockedByUserId set, and finance_periods_reopened_check is a separate
          // biconditional, so this statement is not pre-satisfied by the lock
          // above -- but the assertion below proves it stayed LOCKED after the
          // refusal, so a later step cannot be reading a REOPENED row.
          .as("finance_periods_reopened_check: a reopen is an explicit, permissioned, AUDITED "
              + "action, so it says who, when AND why. The reason is the part usually dropped "
              + "and the part an auditor asks for.")
          .isInstanceOf(SQLException.class);
      assertThat(reopenFinancePeriod(c, org, periodId, author,
          "supplier invoice corrected")).isEqualTo(1);
      // The reopen SUCCEEDING is the point. With finance_periods_locked_check as a
      // biconditional it could not: a REOPENED row keeps lockedAt, which the
      // biconditional forbade, so ADR-0007 rule 4's reopen was inexpressible. The
      // two lock columns surviving the reopen is asserted directly rather than
      // inferred from a status string.
      assertThat(queryInt(c, """
          select count(*) from finance_periods
           where id = '%s' and status = 'REOPENED'::"PeriodStatus"
             and "reopenedAt" is not null and "reopenedByUserId" is not null
             and length(btrim("reopenReason")) > 0
             -- the LOCK columns survived, which is the auditable choice:
             and "lockedAt" is not null and "lockedByUserId" is not null
             and "reopenCount" = 1""".formatted(periodId)))
          .as("ADR-0007 rule 4: a reopen is explicit, permissioned and audited -- and the "
              + "period RETAINS its lock columns afterwards, so \"was this month ever closed?\" "
              + "is answerable from the row and not only from the history table")
          .isEqualTo(1);
      assertThat(queryInt(c, """
          select count(*) from finance_period_lock_events
           where "organizationId" = '%s'""".formatted(org)))
          .as("the period's own history table exists for the transitions; the row answers "
              + "\"is it locked now\" and only the history answers \"was it ever\"")
          .isZero();
    }
  }

  // ------------------------------------------------------------------
  // Scenario 9: AI policy -- deny by default, approval for the consequential
  // ------------------------------------------------------------------

  @Test
  @DisplayName("AI tools are denied by default, a disabled tool stays disabled, and a consequential action cannot run without an approval record")
  void aiToolsAreDenyByDefaultAndConsequentialActionsNeedAnApproval() throws Exception {
    Tenant tenant = seedTenant("ai");
    UUID org = tenant.organizationId();
    UUID project = tenant.projectId();
    UUID operator = seedUser(org, "ai-operator");
    UUID approver = seedUser(org, "ai-approver");
    Tenant other = seedTenant("ai-other");

    // A complete, internally-consistent AI run for this tenant: agent, agent
    // version and run. Needed because ai_tool_executions and ai_approvals
    // reference ai_runs through a COMPOSITE tenant key, so a negative test that
    // changes only the runId is testing the cross-tenant reference and nothing
    // else.
    AiRun ai = seedAiRun(org, project, operator, "ai");
    UUID runId = ai.runId();
    AiRun otherAi = seedAiRun(other.organizationId(), other.projectId(), operator, "ai-other");
    UUID otherRunId = otherAi.runId();

    try (Connection c = connect(phase5aUrl)) {
      // --- 1. DENY BY DEFAULT IS A PROPERTY OF THE SCHEMA ---------------
      assertThat(queryString(c, """
          select coalesce(pg_get_expr(d.adbin, d.adrelid), '(none)')
            from pg_attrdef d
            join pg_class cl on cl.oid = d.adrelid
            join pg_namespace n on n.oid = cl.relnamespace
            join pg_attribute a on a.attrelid = cl.oid and a.attnum = d.adnum
           where n.nspname='public' and cl.relname='ai_tool_registry' and a.attname='isEnabled'"""))
          .as("ADR-0007 Part 2: a newly registered tool is INERT until an administrator "
              + "enables it. Asserting the literal DEFAULT EXPRESSION rather than \"no row "
              + "happens to be enabled\" is what makes it a schema property: flipping the "
              + "default would break every existing deployment's assumption at the same instant.")
          .isEqualTo("false");

      // --- 2. a disabled tool is STORABLE AS DISABLED --------------------
      String toolId = insertAiTool(c, org, "undo.book_appointment", false,
          "registered by the fixture and deliberately left off");
      assertThat(queryInt(c, """
          select count(*) from ai_tool_registry
           where id = '%s' and not "isEnabled" and "disabledAt" is not null
             and "disabledByUserId" is not null
             and length(btrim("disabledReason")) > 0""".formatted(toolId)))
          .as("ai_tool_registry_disabled_check is a BICONDITIONAL: a disabled tool records who, "
              + "when and why, so \"why is this tool off?\" is answerable six months later. "
              + "The one-way form (\"only a disabled tool may carry a disabledAt\") was the "
              + "first version of this constraint and it is the weaker half.")
          .isEqualTo(1);
      assertThatThrownBy(() -> execute(c, """
          update ai_tool_registry set "disabledAt" = null, "disabledReason" = null,
                                     "updatedAt" = now()
           where id = '%s'""".formatted(toolId)))
          .as("...and clearing the disable record while staying disabled is refused, because "
              + "\"disabled without a record\" is the state nobody can audit")
          .isInstanceOf(SQLException.class);
      // Written out rather than routed through insertAiTool, which always supplies the
      // literal 'tool.undo.example'. Calling insertAiTool and expecting a refusal
      // here could never fail: the permission was never blank. The negative owns
      // its row and its blank value.
      assertThatThrownBy(() -> execute(c, """
          insert into ai_tool_registry (id, "organizationId", "toolName", "requiredPermission",
              "isEnabled", "disabledAt", "disabledByUserId", "disabledReason", "riskLevel",
              "createdAt", "updatedAt")
          values (gen_random_uuid(), '%s', 'undo.no_permission', '   ', false,
                  (now() at time zone 'UTC'),
                  (select id from users where "organizationId" = '%s' limit 1),
                  'never configured', 'LOW'::"public"."AiRiskLevel",
                  (now() at time zone 'UTC'), (now() at time zone 'UTC'))""".formatted(org, org)))
          .as("ai_tool_registry_required_permission_check: a tool with no required permission "
              + "is an unrestricted capability, which is what plan.md forbids -- so \"not "
              + "configured yet\" is not an expressible state for it. The value is whitespace, "
              + "not NULL, because btrim(NULL) is NULL and a NULL permission would pass a "
              + "length check that a blank string fails.")
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> enableHighRiskToolWithoutAllowList(c, org, "undo.mass_export"))
          .as("ai_tool_registry_risk_field_list_check: a HIGH or CRITICAL tool may not be "
              + "enabled with an empty field allow-list, because \"enabled with no allow-list\" "
              + "means \"may read anything\" rather than field-limited")
          .isInstanceOf(SQLException.class);

      // --- 3. a DISABLED tool is DENIED AND RECORDED, ALWAYS -------------
      String denialId = insertToolExecution(c, org, project, runId, "undo.book_appointment",
          "ANSWERABLE", "DENY", "tool is disabled", null);
      assertThat(queryString(c,
          "select \"denyReason\" from ai_tool_executions where id = '" + denialId + "'"))
          .as("ADR-0007 Part 2: calling a disabled tool is forbidden to the model, \"denied and "
              + "recorded, always\". A tool the model tried to call while off is not merely "
              + "absent, it is EVIDENCE.")
          .isEqualTo("tool is disabled");
      assertThat(queryInt(c, """
          select count(*) from ai_tool_executions e
            join ai_tool_registry t on t."toolName" = e."toolName"
           where t."organizationId" = '%s' and not t."isEnabled" and e."executedAt" is not null"""
          .formatted(org)))
          .as("...and with data: no execution of a disabled tool ever ran")
          .isZero();

      // --- 4. AN APPROVAL RECORD MUST EXIST FOR A CONSEQUENTIAL ACTION ----
      assertThatThrownBy(() -> insertToolExecution(c, org, project, runId,
          "undo.book_appointment", "CONSEQUENTIAL", "ALLOW", null, null))
          .as("ai_tool_executions_allow_consequential_needs_approval_check: THIS is the rule. "
              + "A CONSEQUENTIAL action marked ALLOW, executed, with NO approval record anywhere "
              + "is exactly the failure ADR-0007 Part 2 exists to make impossible, and it is "
              + "the half the draft's design was missing.")
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> insertToolExecution(c, org, project, runId, "undo.book_appointment",
          "CONSEQUENTIAL", "REQUIRE_APPROVAL", null, null))
          .as("ai_tool_executions_require_approval_check: an action awaiting approval must name "
              + "the approval it waits on")
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> insertToolExecutionExecuted(c, org, project, runId,
          "undo.book_appointment", "CONSEQUENTIAL", "REQUIRE_APPROVAL"))
          .as("...and must NOT have run. The model may only REQUEST a consequential action; "
              + "\"asking and doing anyway\" is the whole distinction the class exists for.")
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> insertToolExecution(c, org, project, runId, "undo.delete_all",
          "FORBIDDEN", "ALLOW", null, null))
          .as("ai_tool_executions_forbidden_denied_check: a FORBIDDEN action is denied and "
              + "cannot have been run, whatever the decision column claims")
          .isInstanceOf(SQLException.class);

      // The real path: request, approve by somebody else, then execute.
      String approvalId = insertAiApproval(c, org, project, runId, "undo.book_appointment",
          operator);
      assertThatThrownBy(() -> execute(c, """
          update ai_approvals set "decidedByUserId" = '%s', "decidedAt" = (now() at time zone 'UTC'),
                                status = 'APPROVED'::"AiApprovalStatus", "updatedAt" = now()
           where id = '%s'""".formatted(operator, approvalId)))
          .as("ai_approvals_not_self_approved_check: plan.md forbids an unrestricted identity; "
              + "a self-approval is the same problem wearing a workflow")
          .isInstanceOf(SQLException.class);
      assertThat(approveAiApproval(c, org, approvalId, approver)).isEqualTo(1);
      assertThat(insertToolExecution(c, org, project, runId, "undo.book_appointment",
          "CONSEQUENTIAL", "ALLOW", null, approvalId))
          .as("a consequential action that names its APPROVED record may run")
          .isNotNull();
      assertThat(queryInt(c, """
          select count(*) from ai_tool_executions
           where "consequentialAction"
             and "decision" = 'ALLOW'::"AiPolicyDecision"
             and "approvalId" is null"""))
          .as("THE ACCEPTANCE NUMBER: no consequential execution ran without an approval, "
              + "across every row this fixture wrote")
          .isZero();

      // The approval-state negatives, and one PENDING per run and action.
      // This negative owns a FRESH PENDING approval. An earlier version reused
      // `approvalId`, which by now had been APPROVED -- and the index is PARTIAL
      // (`where status = 'PENDING'`), so an approved row does not collide and a
      // second approval for the same action was perfectly legal. The constraint
      // was never under test.
      String pendingId = insertAiApproval(c, org, project, runId, "undo.refund_invoice",
          operator);
      assertThat(queryString(c,
          "select status::text from ai_approvals where id = '" + pendingId + "'"))
          .as("...and this row is genuinely PENDING, which is the precondition the partial "
              + "index keys on")
          .isEqualTo("PENDING");
      assertThatThrownBy(() -> insertAiApproval(c, org, project, runId, "undo.refund_invoice",
          approver))
          .as("ai_approvals_one_pending_per_run_action: two humans approving the same action "
              + "for one run because the UI double-posted is exactly the double decision this "
              + "partial unique index prevents. The index is PARTIAL on status = 'PENDING', so "
              + "this proves the pending half -- an APPROVED row for the same action above is "
              + "deliberately still allowed, and that is why a new approval for a genuinely "
              + "new action remains possible.")
          .isInstanceOf(SQLException.class);
      assertThat(insertAiApproval(c, org, project, runId, "undo.issue_refund", operator))
          .as("...and a SECOND pending approval for a DIFFERENT action on the same run is "
              + "allowed: the rule is one pending per (run, action), not one per run")
          .isNotNull();
      // insertAiApproval always writes actionClass = 'CONSEQUENTIAL', so this negative
      // is written out rather than routed through the helper: as written it would
      // have inserted a CONSEQUENTIAL action named "undo.send_reply" and been
      // refused by the wrong constraint (or, once the pending index was fixed,
      // accepted). The point being proved is that actionClass comes from the
      // ACTION, not from the intent to approve.
      assertThatThrownBy(() -> execute(c, """
          insert into ai_approvals (id, "organizationId", "projectId", "aiRunId", action,
              "actionClass", status, "requestedByPrincipal", "requestedAt", "expiresAt",
              "updatedAt")
          values (gen_random_uuid(), '%s', '%s', '%s', 'undo.send_reply',
                  'ANSWERABLE', 'PENDING'::"public"."AiApprovalStatus", 'undo-rehearsal',
                  (now() at time zone 'UTC'),
                  (now() at time zone 'UTC') + interval '15 minutes',
                  (now() at time zone 'UTC'))""".formatted(org, project, runId)))
          .as("ai_approvals_only_consequential_is_approvable_check: an ANSWERABLE action must "
              + "not enter the approval queue, or approving it teaches operators the queue is "
              + "decorative. It is recorded in ai_tool_executions instead.")
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> revokeAiApprovalWithoutReason(c, org, approvalId, approver))
          .as("ai_approvals_revocation_check: a revocation says who, when and why")
          .isInstanceOf(SQLException.class);

      // --- 5. the cross-tenant references -------------------------------
      assertThatThrownBy(() -> insertToolExecution(c, org, project, otherRunId,
          "undo.book_appointment", "ANSWERABLE", "DENY", "cross tenant", null))
          .as("ai_tool_executions_run_tenant_fkey: an execution in tenant A may not name tenant "
              + "B's AI run. ONLY the runId is wrong here, so the refusal is the cross-tenant "
              + "reference and nothing else.")
          .isInstanceOf(SQLException.class);
      assertThat(insertToolExecution(c, other.organizationId(), other.projectId(), otherRunId,
          "undo.book_appointment", "ANSWERABLE", "DENY", "other tenant", null))
          .as("the same shape in a consistent tenant B is accepted, so the refusal above is "
              + "proving the cross-tenant reference and not that the table rejects everything")
          .isNotNull();

      // --- 6. NO RAW CONTENT, NO SECRETS, NO CHAIN-OF-THOUGHT -----------
      assertThat(queryStrings(c, """
          select cl.relname || '.' || a.attname
            from pg_class cl
              join pg_namespace n on n.oid = cl.relnamespace
              join pg_attribute a on a.attrelid = cl.oid and a.attnum > 0 and not a.attisdropped
           where n.nspname='public' and cl.relkind='r'
             and cl.relname like 'ai\\_%'
             and (lower(a.attname) like '%raw%' or lower(a.attname) like '%original%'
                  or lower(a.attname) like '%unredacted%'
                  or lower(a.attname) like '%chainofthought%'
                  or lower(a.attname) like '%reasoning%'
                  or lower(a.attname) like '%thinking%'
                  or lower(a.attname) like '%scratchpad%'
                  or lower(a.attname) like '%monologue%'
                  or lower(a.attname) in ('apikey','token','secret','password','credential'))"""))
          .as("FR-10.6 permits only the redacted form; FR-5.4 and ADR-0007 rule 4 forbid "
              + "storing internal reasoning; and the model registry is a REGISTRY, so a "
              + "column that could hold a key is a column that will eventually hold one in a "
              + "log. Asserted over every ai_* table so a later migration cannot quietly add "
              + "one.")
          .isEmpty();
    }
  }

  // ------------------------------------------------------------------
  // Scenario 10: the CONCURRENTLY migration and the appended child scope
  // ------------------------------------------------------------------

  @Test
  @DisplayName("the CONCURRENTLY decision is recorded in the migration that replaced it, and the appended child scope is nullable and tenant-led")
  void concurrentMigrationRanOutsideATransactionAndChildScopeIsExpandOnly() throws Exception {
    // --- 1. the CONCURRENTLY migration is PRESENT, and its replacement is PRESENT -
    //     ADR-0005 section 3 asked for CREATE INDEX CONCURRENTLY. It was tried
    //     three ways and all three are recorded in the migration header:
    //       (a) one script with both statement kinds + a .conf sidecar saying
    //           executeInTransaction=false -> FlywayException, because
    //           flyway-core 11.7.2 Community has NO per-script override and the
    //           .conf sidecar is a Teams/Enterprise feature;
    //       (b) the same with executeInTransaction=mixed -> identical failure;
    //       (c) split into two versions, which Flyway accepted and logged as
    //           [non-transactional] -- and which then HUNG, because Flyway holds
    //           its schema-history advisory lock inside an open transaction on a
    //           SEPARATE connection and CREATE INDEX CONCURRENTLY waits for exactly
    //           that. Proven with a jcmd thread dump.
    //     So the index migration uses a plain CREATE INDEX, which is ADR-0005's own
    //     listed alternative. These assertions make the decision VISIBLE and make
    //     it impossible to reverse silently.
    String indexScript =
        Files.readString(sqlDir.resolve("V20261005120000__ai_run_child_tenant_scope_indexes.sql"));
    // The header is prose wrapped at ~78 columns AND every line is prefixed with
    // "-- ", so a phrase can straddle a line break with a comment marker in the
    // middle of it ("non-transactional  \n--  statements"). Both the markers and
    // the wrapping are removed before matching. Without that this assertion fails
    // on FORMATTING rather than on meaning -- which is the kind of test that gets
    // deleted the first time somebody reflows a comment.
    String indexProse = indexScript.lines()
        .map(l -> l.stripLeading().startsWith("--")
            ? l.stripLeading().substring(2) : l)
        .reduce("", (a, b) -> a.isEmpty() ? b.strip() : a + " " + b.strip())
        .replaceAll("\\s+", " ");
    assertThat(indexProse)
        .as("the CONCURRENTLY decision is documented IN THE MIGRATION, not in a comment "
            + "somebody will delete: the header names all three attempts, the Flyway "
            + "community limitation, the advisory-lock deadlock, and both real fixes "
            + "(spring.flyway.postgresql.transactional-lock=false, or a maintenance window)")
        .contains("spring.flyway.postgresql.transactional-lock=false")
        .contains("advisory lock")
        // The header wraps this at the column, so it is matched as one phrase against the
        // de-commented, de-wrapped copy above. The exact Flyway message is asserted
        // because it is the single most expensive thing to rediscover: nothing in the
        // exception names the .conf sidecar as the thing Flyway ignored.
        .contains("Detected both transactional and non-transactional statements "
            + "within the same migration");
    // Matched against the EXECUTABLE body only (comment lines removed): the header
    // legitimately quotes "CREATE INDEX CONCURRENTLY" while explaining why it is not
    // used, and asserting on the whole file would forbid documenting the decision.
    String indexBody = indexScript.lines()
        .filter(l -> !l.stripLeading().startsWith("--"))
        .reduce("", (a, b) -> a + "\n" + b);
    assertThat(indexBody)
        .as("...and the executed statements do NOT use CONCURRENTLY today")
        .doesNotContain("CONCURRENTLY");
    assertThat(indexBody)
        .as("...and the script is homogeneous: an ALTER TABLE or a DO block would make it "
            + "'mixed' and Flyway would refuse it the moment CONCURRENTLY comes back")
        .doesNotContain("ALTER TABLE")
        .doesNotContain("DO $$")
        .doesNotContain("DROP");

    try (Connection c = connect(phase5aUrl)) {
      for (String version : List.of("20261005110000", "20261005120000")) {
        assertThat(queryInt(c,
            "select count(*) from flyway_schema_history where version = '" + version
                + "' and success"))
            .as("migration %s applied", version)
            .isEqualTo(1);
      }
      assertThat(java.util.Arrays.stream(flyway(phase5aUrl).info().all())
          .filter(i -> i.getVersion() != null
              && "20261005120000".equals(i.getVersion().getVersion()))
          .allMatch(i -> i.getState().isApplied()))
          .as("...and Flyway's own info() agrees")
          .isTrue();
    }

    // --- 2. there is NO per-script configuration sidecar, and that is the POINT -
    //     A `.conf` next to a migration is the shape an operator (and the original
    //     draft) expects to find, and Flyway 11.7.2 Community SILENTLY IGNORES it.
    //     Leaving such a file in the tree would document a mechanism that does not
    //     work, and the next person to add a CONCURRENTLY index would believe it
    //     was covered. The assertion below is therefore negative on purpose: the
    //     transaction semantics are a property of the SQL, not of a file.
    String repoRoot = System.getProperty("repoRoot");
    assertThat(repoRoot).as("repoRoot system property").isNotNull();
    Path packaged = Path.of(repoRoot, "backend/database/src/main/resources/db/migration");
    try (var files = Files.list(sqlDir)) {
      assertThat(files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".conf")))
          .as("no .conf sidecar anywhere in the chain: Flyway Community would ignore it, and "
              + "a file that looks load-bearing but is not is worse than no file")
          .isEmpty();
    }
    try (var files = Files.list(packaged)) {
      assertThat(files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".conf")))
          .as("...and none packaged, for the same reason")
          .isEmpty();
    }

    // --- 3. the indexes exist, are tenant-led, and are VALID -------------
    //     An aborted build leaves an invalid index which the planner SILENTLY
    //     ignores, so the tenant filter quietly becomes a sequential scan. That
    //     is why indisvalid and indisready are both asserted rather than merely
    //     existence.
    try (Connection c = connect(phase5aUrl)) {
      assertThat(queryStrings(c, """
          select c.relname
            from pg_index i
              join pg_class c on c.oid = i.indexrelid
              join pg_namespace n on n.oid = c.relnamespace
           where n.nspname = 'public'
             and c.relname in ('ai_run_steps_org_project_created_idx',
                               'ai_run_retrievals_org_project_created_idx',
                               'ai_run_tool_calls_org_project_created_idx')
             and (not i.indisvalid or not i.indisready)"""))
          .as("all three tenant indexes are valid and ready -- an invalid index exists, is "
              + "ignored by the planner, and turns the tenant filter into a sequential scan "
              + "without any error")
          .isEmpty();
      assertThat(queryInt(c, """
          select count(distinct i.indrelid) from pg_index i
            join pg_class c on c.oid = i.indexrelid
            join pg_namespace n on n.oid = c.relnamespace
           where n.nspname = 'public'
             and c.relname in ('ai_run_steps_org_project_created_idx',
                               'ai_run_retrievals_org_project_created_idx',
                               'ai_run_tool_calls_org_project_created_idx')"""))
          .as("...and they are on three DISTINCT tables, so a copy-paste that put all three "
              + "on one table cannot pass")
          .isEqualTo(3);
      assertThat(queryInt(c, """
          select count(*) from pg_indexes
           where schemaname = 'public'
             and indexname in ('ai_run_steps_org_project_created_idx',
                               'ai_run_retrievals_org_project_created_idx',
                               'ai_run_tool_calls_org_project_created_idx')
             and indexdef like '%USING btree ("organizationId", "projectId", "createdAt")%'"""))
          .as("...and all three LEAD WITH THE TENANT COLUMN (FR-1.3), in the column order the "
              + "read needs: tenant, project, oldest first")
          .isEqualTo(3);

      // --- 4. EXPAND ONLY: the three columns exist and are NULLABLE ------
      assertThat(queryStrings(c, """
          select c.relname || '.' || a.attname || '='
                 || case when a.attnotnull then 'NOT NULL' else 'NULL' end
            from pg_class c
              join pg_namespace n on n.oid = c.relnamespace
              join pg_attribute a on a.attrelid = c.oid and a.attnum > 0 and not a.attisdropped
           where n.nspname = 'public'
             and c.relname in ('ai_run_steps','ai_run_retrievals','ai_run_tool_calls')
             and a.attname in ('organizationId','projectId') and a.attnotnull"""))
          .as("EXPAND/CONTRACT step 1. NOT NULL here would be refused against the existing rows, "
              + "and it belongs to a LATER migration -- after B1 has proven the residual NULL "
              + "count is zero. Adding it in the same migration as the backfill would force one "
              + "transaction over three append-heavy trace tables, which is the outage the "
              + "CONCURRENTLY build exists to avoid.")
          .isEmpty();
      assertThat(queryInt(c, """
          select count(*) from pg_attrdef d
            join pg_class c on c.oid = d.adrelid
            join pg_namespace n on n.oid = c.relnamespace
            join pg_attribute a on a.attrelid = c.oid and a.attnum = d.adnum
           where n.nspname = 'public'
             and c.relname in ('ai_run_steps','ai_run_retrievals','ai_run_tool_calls')
             and a.attname = 'updatedAt'
             and pg_get_expr(d.adbin, d.adrelid) like '%CURRENT_TIMESTAMP%'
             and pg_get_expr(d.adbin, d.adrelid) not like '%AT TIME ZONE%'"""))
          .as("the draft used a bare DEFAULT CURRENT_TIMESTAMP, which stores the SESSION's wall "
              + "clock -- the baseline's habit. The NEW default writes UTC explicitly.")
          .isZero();

      // --- 6. and the three child tables now carry the columns the census
      //        says they carry: the tenant-less table list shrinks from four to ONE.
      assertThat(queryStrings(c, """
          select c.relname from pg_class c
            join pg_namespace n on n.oid = c.relnamespace
           where n.nspname = 'public' and c.relkind = 'r'
             and c.relname <> 'flyway_schema_history'
             and not exists (select 1 from pg_attribute a
                             where a.attrelid = c.oid and a.attnum > 0
                               and not a.attisdropped
                               and a.attname = 'organizationId')
           order by c.relname"""))
          .as("V20261005110000 removes three of the SIX tenant-less tables: ai_run_steps, "
              + "ai_run_retrievals and ai_run_tool_calls. What remains is the tenant ROOT "
              + "(organizations, which by definition has no parent) plus the two Phase-6a "
              + "sla_calendar configuration children, which inherit their scope from "
              + "sla_calendars and which V11 section 4c already pins. An earlier version of "
              + "this assertion expected ONLY organizations and so failed on the calendar "
              + "children -- those are not an oversight of this migration and are not "
              + "silently absorbed here; they are named. "
              + "V20261012000000 adds FOUR more, for the platform operator tier. That is a "
              + "DIFFERENT kind of exception and is named rather than merged into the previous "
              + "ones: a platform operator belongs to no tenant, so forcing an organizationId "
              + "there would quietly narrow the principal to one customer. Only "
              + "platform_audit_events can carry a nullable organizationId, because one audit "
              + "row records one action and a platform action may be tenant-wide; the other "
              + "three have no such column at all, which is what makes the separation structural "
              + "rather than conventional.")
          // V20261018000000 adds a THIRD kind of tenant-less table, named rather than
          // merged into either previous kind. clinic_finding_vocabulary_template is the
          // published dental finding vocabulary: one global catalogue that every clinic
          // COPIES on first use. It cannot be tenant-scoped because a migration runs
          // before any clinic exists, and seeding it with an organizationId would attribute
          // a global catalogue to whichever tenant happened to be first. It holds no patient
          // data and is not reachable through any tenant-scoped path.
          .containsExactly("clinic_finding_vocabulary_template", "clinic_permissions",
              "clinic_role_permissions", "organizations",
              "platform_operator_capabilities", "platform_operators", "platform_sessions",
              "sla_calendar_holidays", "sla_calendar_windows");
    }
  }

  // ------------------------------------------------------------------
  // Scenario 11: the report catalogue
  // ------------------------------------------------------------------

  @Test
  @DisplayName("a report is data: versioned, allow-listed, Arabic-first, and one published body per definition")
  void reportsAreVersionedAllowListedAndArabicFirst() throws Exception {
    Tenant tenant = seedTenant("report");
    UUID org = tenant.organizationId();
    UUID author = seedUser(org, "report-author");

    try (Connection c = connect(phase5aUrl)) {
      String definitionId = seedReportDefinition(c, org, "undo_report_alpha");

      // Version NUMBERS are unique per definition, so the allow-list negatives below use
      // 3, 4, 5 and 6 rather than reusing 1. An earlier version reused 1 and the unique
      // index fired before any CHECK could, so the negative tested uniqueness and not the
      // invariant it named.
      assertThat(seedReportVersion(c, org, definitionId, author, 1)).isNotNull();

      // The allow-list SHAPE checks: these are what turn "a versioned metadata
      // registry" into "an unbounded dynamic schema".
      assertThatThrownBy(() -> execute(c, """
          insert into report_versions (id, "organizationId", "reportDefinitionId",
              "versionNumber", dimensions, measures, "createdByUserId", "createdAt")
          values (gen_random_uuid(), '%s', '%s', 3, '{"anything": true}'::jsonb,
                  '["conversations.count"]'::jsonb, '%s', now())""".formatted(org, definitionId, author)))
          .as("report_versions_dimensions_shape_check: a JSON DOCUMENT where a LIST belongs is "
              + "indistinguishable from \"arbitrary SQL someone was going to interpolate\"")
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> execute(c, """
          insert into report_versions (id, "organizationId", "reportDefinitionId",
              "versionNumber", dimensions, measures, "createdByUserId", "createdAt")
          values (gen_random_uuid(), '%s', '%s', 4, '["day"]'::jsonb,
                  '[1, 2, 3]'::jsonb, '%s', now())""".formatted(org, definitionId, author)))
          .as("report_versions_measures_shape_check plus its entry check: a measure that is a "
              + "NUMBER rather than a key into the typed registry")
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> execute(c, """
          insert into report_versions (id, "organizationId", "reportDefinitionId",
              "versionNumber", dimensions, measures, "defaultSort", "createdByUserId", "createdAt")
          values (gen_random_uuid(), '%s', '%s', 5, '["day"]'::jsonb,
                  '["conversations.count"]'::jsonb, '[{"field":"day"}]'::jsonb, '%s', now())"""
          .formatted(org, definitionId, author)))
          .as("report_versions_sort_entries_check: a sort entry with no direction is a "
              + "suggestion the reader cannot act on")
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> execute(c, """
          insert into report_versions (id, "organizationId", "reportDefinitionId",
              "versionNumber", dimensions, measures, "createdByUserId", "createdAt")
          values (gen_random_uuid(), '%s', '%s', 6, '["day"]'::jsonb, '[]'::jsonb, '%s', now())"""
          .formatted(org, definitionId, author)))
          .as("report_versions_measures_present_check: a report with no measure is not a report")
          .isInstanceOf(SQLException.class);

      // Arabic-first is part of the PRODUCT, not a preference.
      assertThatThrownBy(() -> execute(c, """
          insert into report_definitions (id, "organizationId", "key", "nameAr", "nameEn",
              "timezone", "redactionPolicy", "retentionClass", "createdAt", "updatedAt")
          values (gen_random_uuid(), '%s', 'undo_report_arabic_only', '?????', '',
                  'Asia/Riyadh', 'p', 'R', now(), now())""".formatted(org)))
          .as("report_definitions_name_ar_check: a report with only an English name is dead "
              + "configuration, and the database refuses it rather than letting it reach a "
              + "dashboard")
          .isInstanceOf(SQLException.class);

      // The timezone must be a zone PostgreSQL KNOWS, not one shaped like one.
      assertThatThrownBy(() -> execute(c, """
          insert into report_definitions (id, "organizationId", "key", "nameAr", "nameEn",
              "timezone", "redactionPolicy", "retentionClass", "createdAt", "updatedAt")
          values (gen_random_uuid(), '%s', 'undo_report_typo', '?????', 'typo',
                  'Asia/Riyaddah', 'p', 'R', now(), now())""".formatted(org)))
          .as("report_definitions_timezone_check: 'Asia/Riyaddah' is a PERFECTLY SHAPED zone "
              + "name that does not exist. A regex would accept it, and then every bucket in "
              + "the report would be silently wrong until a monthly reconciliation.")
          .isInstanceOf(SQLException.class);

      // --- ONE PUBLISHED BODY PER DEFINITION ----------------------------
      // Version numbers 1 and 3..6 are already taken in this definition by the block
      // above, so the publication pair uses 7 and 8. Reusing 1 here fired the unique
      // index `report_versions_definition_version_key` before any CHECK or partial index
      // was reached -- the test then failed on uniqueness while claiming to test
      // publication, which is the same class of bug as reusing a started job above.
      String v1 = seedReportVersion(c, org, definitionId, author, 7);
      String v2 = seedReportVersion(c, org, definitionId, author, 8);
      assertThat(publishReportVersion(c, org, definitionId, v1, author)).isEqualTo(1);
      assertThatThrownBy(() -> publishReportVersion(c, org, definitionId, v2, author))
          .as("report_versions_one_published_per_definition: two published bodies would make "
              + "\"which version is live\" unanswerable, and the PARTIAL index is the backstop "
              + "against a concurrent double publication")
          .isInstanceOf(SQLException.class);
      assertThat(queryInt(c, """
          select count(*) from report_versions
           where "reportDefinitionId" = '%s'
             and state = 'PUBLISHED'::"ReportStatus\"""".formatted(definitionId)))
          .as("...and exactly one survives the failed attempt")
          .isEqualTo(1);
      // The SWAP: retire the incumbent first, then publish the successor. This is
      // the ordering Phase 6a's rehearsal proved is necessary -- the single UPDATE
      // that would do both is a constraint violation, not a swap.
      assertThat(retireReportVersion(c, org, v1, v2)).isEqualTo(1);
      assertThat(publishReportVersion(c, org, definitionId, v2, author))
          .as("...and after the incumbent is retired the successor publishes cleanly")
          .isEqualTo(1);
      assertThatThrownBy(() -> retireReportVersion(c, org, v2, null))
          .as("report_versions_retired_check: a retirement that does not say what replaced it "
              + "is the rollback problem Phase 6a already recorded for workflow versions")
          .isInstanceOf(SQLException.class);
      // ...and the SAME retirement is accepted once it names what replaced it.
      // An earlier version called retireReportVersion(v2, null) twice: the first
      // inside assertThatThrownBy (correct), the second outside it asserting
      // isZero() as though the negative had somehow "used up" the violation. It had
      // not, and the constraint is per-STATEMENT, so the second call raised the
      // same violation and the test failed on its own negative. A real successor
      // version is created so the accepting path is exercised for the reason it is
      // named.
      String v3 = seedReportVersion(c, org, definitionId, author, 9);
      assertThat(retireReportVersion(c, org, v2, v3))
          .as("...with a successor named it is accepted, and the row count proves exactly one "
              + "version moved rather than the whole definition being rewritten")
          .isEqualTo(1);
      assertThat(queryString(c, """
          select coalesce("supersededByVersionId"::text, 'NULL')
            from report_versions where id = '%s'""".formatted(v2)))
          .as("...and the named successor is what the row records, so \"why did v2 stop "
              + "running?\" is answerable without git archaeology")
          .isEqualTo(v3);

      // --- tenant-scoped natural key -------------------------------------
      UUID otherOrg = seedTenant("report-other").organizationId();
      assertThatThrownBy(() -> execute(c, """
          insert into report_definitions (id, "organizationId", "key", "nameAr", "nameEn",
              "timezone", "redactionPolicy", "retentionClass", "createdAt", "updatedAt")
          values (gen_random_uuid(), '%s', 'undo_report_alpha', 'dup', 'dup',
                  'Asia/Riyadh', 'p', 'R', now(), now())""".formatted(org)))
          .as("report_definitions_organizationId_key_key: one report key per TENANT. Including "
              + "projectId in the key would let 'onboarding_v1' exist once per project with no "
              + "way to name one of them.")
          .isInstanceOf(SQLException.class);
      assertThat(execute(c, """
          insert into report_definitions (id, "organizationId", "key", "nameAr", "nameEn",
              "timezone", "redactionPolicy", "retentionClass", "createdAt", "updatedAt")
          values (gen_random_uuid(), '%s', 'undo_report_alpha', 'tenant b', 'tenant b',
                  'Asia/Riyadh', 'p', 'R', now(), now())""".formatted(otherOrg)))
          .as("...and tenant B may use the SAME key, so a global index could never turn the "
              + "violation message into a cross-tenant existence oracle")
          .isEqualTo(1);
    }
  }

  // ------------------------------------------------------------------
  // Scenario 2: an existing legacy database must be baselined, not rebuilt
  // ------------------------------------------------------------------

  @Test
  @DisplayName("existing legacy database is stamped, not replayed; additive migration still runs")
  void existingLegacyDatabaseIsBaselinedWithoutReplayingDdl() throws Exception {
    String legacyUrl = createSiblingDatabase(LEGACY_DB);

    // 1. Recreate the exact situation the cutover meets: the verified schema
    //    already exists and already holds rows, and there is no Flyway history.
    try (Connection c = connect(legacyUrl);
         Statement s = c.createStatement()) {
      try (var rs = s.executeQuery(
          "select count(*) from pg_tables where schemaname='public'")) {
        rs.next();
        assertThat(rs.getInt(1)).as("fresh Flyway database starts empty").isZero();
      }
    }
    Flyway.configure()
        .dataSource(legacyUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("filesystem:" + sqlDir)
        .target(MigrationVersion.fromVersion("20260814000000"))
        .load()
        .migrate();

    try (Connection c = connect(legacyUrl);
         Statement s = c.createStatement()) {
      s.executeUpdate("""
          insert into organizations (id, name, slug, "createdAt", "updatedAt")
          values (gen_random_uuid(), 'Legacy Tenant', 'legacy-tenant',
                  current_timestamp, current_timestamp)""");
      s.executeUpdate("""
          insert into projects (id, "organizationId", name, slug, "createdAt", "updatedAt")
          select gen_random_uuid(), id, 'Legacy Project', 'legacy-project',
                 current_timestamp, current_timestamp
            from organizations where slug = 'legacy-tenant'""");
      s.executeUpdate("""
          insert into users (id, "organizationId", email, "passwordHash", "fullName",
                             role, locale, "createdAt", "updatedAt")
          select gen_random_uuid(), id, 'legacy@example.test', 'not-a-real-hash',
                 'Legacy Employee', 'EMPLOYEE'::"UserRole", 'ar'::"Locale",
                 current_timestamp, current_timestamp
            from organizations where slug = 'legacy-tenant'""");
      s.executeUpdate("""
          insert into employees (id, "organizationId", "userId", "availabilityStatus",
                                 "statusChangedAt", "createdAt", "updatedAt")
          select gen_random_uuid(), u."organizationId", u.id, 'MEAL'::"EmployeeAvailabilityStatus",
                 current_timestamp, current_timestamp, current_timestamp
            from users u
            join organizations o on o.id = u."organizationId"
           where o.slug = 'legacy-tenant'""");
      // The legacy runtime knows nothing about Flyway: the history table must not
      // merely be emptied, it must not exist at all. Otherwise Flyway treats the
      // schema as "history present but empty" and replays every migration.
      s.executeUpdate("DROP TABLE flyway_schema_history");
    }

    long organizationsBefore = -1;
    String uuidBefore;
    try (Connection c = connect(legacyUrl)) {
      organizationsBefore = queryLong(c, "select count(*) from organizations");
      uuidBefore = queryString(c, "select id::text from organizations limit 1");
    }
    assertThat(organizationsBefore).isEqualTo(1);

    // 2. Now run exactly what the Spring configuration runs on startup.
    MigrateResult result = Flyway.configure()
        .dataSource(legacyUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("filesystem:" + sqlDir)
        .baselineOnMigrate(true)
        .baselineVersion(MigrationVersion.fromVersion("20260814000000"))
        .load()
        .migrate();

    // 3. Only the additive migrations may execute. If the baseline DDL had been
    //    replayed, migrate() would have failed on "relation already exists"
    //    OR wiped/recreated the tenant.
    assertThat(result.success).as("migrate() success on a legacy database").isTrue();
    assertThat(result.migrationsExecuted)
        .as("baseline is stamped, not replayed; fifty additive migrations run (Stage 1 platform tier, the clinic tiers, DEC-003 appointment status constraint, and clinic RBAC roles/permissions included)")
        .isEqualTo(50);
    try (Connection c = connect(legacyUrl)) {
      assertThat(queryLong(c, "select count(*) from organizations"))
          .as("legacy rows preserved")
          .isEqualTo(organizationsBefore);
      assertThat(queryString(c, "select id::text from organizations limit 1"))
          .as("legacy UUID preserved (no identifier regeneration)")
          .isEqualTo(uuidBefore);

      // Baseline row must exist and be marked as a baseline, not an execution.
      assertThat(queryInt(c,
          "select count(*) from flyway_schema_history where version = '20260814000000'"))
          .isEqualTo(1);
      for (String version : List.of("20260814000100", "20261004000100",
                                   "20261004000700", "20261004001000",
                                   "20261005010000", "20261005020000",
                                   "20261005030000", "20261005040000",
                                   "20261005050000", "20261005060000",
                                   "20261005070000", "20261005080000",
                                   "20261005090000", "20261005100000",
                                   "20261005110000", "20261005120000",
                                   "20261006100000", "20261006110000",
                                   "20261006120000", "20261006130000",
                                   "20261007100000", "20261007110000")) {
        assertThat(queryInt(c,
            "select count(*) from flyway_schema_history where version = '" + version + "' and success"))
            .as("migration %s applied", version)
            .isEqualTo(1);
      }

      // The additive migration still did its job on the legacy database.
      assertThat(queryInt(c,
          "select count(*) from pg_indexes"
              + " where schemaname='public' and indexname='document_chunks_embedding_hnsw_idx'"))
          .isEqualTo(1);

      // And the Phase-5a slice exists there too, empty.
      assertThat(queryInt(c,
          "select count(*) from pg_tables where schemaname='public' and tablename = 'outbox_events'"))
          .isEqualTo(1);
      assertThat(queryInt(c, "select count(*) from outbox_events"))
          .as("a new table starts empty; no legacy row was moved into it")
          .isZero();

      // So does the Phase-6a slice: all nine tables, every one empty. A Phase-6a
      // migration that backfilled from legacy rows would show up here as a
      // non-zero count, and it must not: there is no backfill in this slice.
      assertThat(queryInt(c, """
          select count(*) from pg_tables
           where schemaname = 'public'
             and tablename in ('workflow_definitions','workflow_versions','workflow_runs',
                               'workflow_run_actions','sla_calendars','sla_calendar_windows',
                               'sla_calendar_holidays','sla_policies','collision_records')"""))
          .as("the Phase-6a slice is applied on the legacy database too")
          .isEqualTo(9);
      for (String table : PHASE6A_TABLES) {
        assertThat(queryLong(c, "select count(*) from " + table))
            .as("%s starts empty on a legacy database", table)
            .isZero();
      }

      // So does the Phase-7-10a slice: all thirty-seven tables, every one EMPTY.
      // This is where a migration that "helpfully" backfilled audit_logs into
      // admin_audit_events, or adopted ai_usage.estimatedCost into usage_events,
      // would show up as a NON-ZERO count -- and neither is permitted, because
      // both change finance and audit history retroactively. The open decision on
      // ai_usage adoption is recorded in the phase report, not smuggled in here.
      for (String table : PHASE710A_TABLES) {
        assertThat(queryLong(c, "select count(*) from " + table))
            .as("%s starts empty on a legacy database -- a Phase-7-10a migration that "
                + "backfilled from legacy rows would show up here as a non-zero count",
                table)
            .isZero();
      }
      assertThat(queryLong(c, "select count(*) from audit_logs"))
          .as("audit_logs is UNTOUCHED: the legacy runtime still writes it and this migration "
              + "is additive, so no row may have moved out of it")
          .isZero();

      // Reconciled enum still the live one, including the value the legacy
      // seed actually used.
      assertThat(queryInt(c,
          "select count(*) from employees where \"availabilityStatus\" = 'MEAL'"))
          .isEqualTo(1);
    }
  }

  // ------------------------------------------------------------------
  // Scenario 3: the claim, against real rows
  // ------------------------------------------------------------------

  @Test
  @DisplayName("outbox claim is tenant-scoped, lease-protected, retryable and index-supported")
  void outboxClaimIsTenantScopedLeasedAndRetryable() throws Exception {
    Tenant tenantA = seedTenant("claim-a");
    UUID orgA = tenantA.organizationId();
    Tenant tenantB = seedTenant("claim-b");
    UUID orgB = tenantB.organizationId();

    // One row is NOT due (nextAttemptAt in the future): it must never be claimed.
    seedOutbox(orgA, tenantA.projectId(), "due", 5, 0);
    seedOutbox(orgA, tenantA.projectId(), "not-due", 1, 3600);
    seedOutbox(orgB, null, "other-tenant", 5, 0);

    try (Connection c = connect(phase5aUrl)) {
      // --- the claim returns only this tenant's due rows -----------------
      List<String> claimed = claim(c, orgA, 50, "worker-A");
      assertThat(claimed).as("five due rows for this tenant, the future row and the other "
          + "tenant's rows are not claimable").hasSize(5);

      // --- the claim took a lease and counted the attempt -----------------
      assertThat(queryInt(c, """
          select count(*) from outbox_events
           where "organizationId" = '%s' and state = 'LEASED'
             and "leaseOwner" = 'worker-A'
             and "leaseUntil" > (now() at time zone 'UTC')
             and "lastAttemptAt" is not null
             and attempts = 1""".formatted(orgA)))
          .isEqualTo(5);

      // --- a second claim finds nothing: the lease is held ----------------
      assertThat(claim(c, orgA, 50, "worker-B"))
          .as("a leased row is not claimable while its lease is alive")
          .isEmpty();

      // --- only the owner may finish the row -----------------------------
      String oneClaimed = claimed.get(0);
      assertThat(complete(c, orgA, oneClaimed, "worker-B"))
          .as("a worker that does not hold the lease must not be able to mark the event delivered")
          .isZero();
      assertThat(queryString(c, "select state from outbox_events where id = '" + oneClaimed + "'"))
          .as("the row is untouched by the impostor's attempt")
          .isEqualTo("LEASED");

      // --- the owner completes it, and it becomes terminal ----------------
      assertThat(complete(c, orgA, oneClaimed, "worker-A")).isEqualTo(1);
      assertThat(queryString(c, "select state from outbox_events where id = '" + oneClaimed + "'"))
          .isEqualTo("SENT");
      assertThat(queryInt(c, """
          select count(*) from outbox_events
           where id = '%s' and "deliveredAt" is not null
             and "leaseOwner" is null and "leaseUntil" is null""".formatted(oneClaimed)))
          .as("completing clears the lease pair (CHECK constraint outbox_events_lease_pair_check)")
          .isEqualTo(1);

      // --- a retryable failure returns the row to PENDING with backoff ----
      String toRetry = claimed.get(1);
      assertThat(retryClaimable(c, orgA, toRetry, "worker-A")).isEqualTo(1);
      assertThat(queryString(c, "select state from outbox_events where id = '" + toRetry + "'"))
          .isEqualTo("PENDING");
      assertThat(queryInt(c, """
          select count(*) from outbox_events
           where id = '%s' and "leaseUntil" is null and "leaseOwner" is null
             and "lastError" = 'provider timeout'""".formatted(toRetry)))
          .isEqualTo(1);
      assertThat(claim(c, orgA, 50, "worker-B"))
          .as("a backed-off row is not due yet, so it is not claimable")
          .isEmpty();

      // --- make it due again, then let the worker die --------------------
      makeDue(c, toRetry);
      assertThat(claim(c, orgA, 50, "worker-C")).hasSize(1);
      String stranded = queryString(c,
          "select id::text from outbox_events where \"organizationId\" = '" + orgA
              + "' and state = 'LEASED' and \"leaseOwner\" = 'worker-C'");
      expireLease(c, stranded);

      // The claim still cannot take it: the state is LEASED.
      assertThat(claim(c, orgA, 50, "worker-D"))
          .as("an expired lease does not by itself make a LEASED row claimable")
          .isEmpty();

      // The reaper is what recovers a crashed worker.
      List<String> reaped = reap(c, 10);
      assertThat(reaped).contains(stranded);
      assertThat(queryString(c, "select state from outbox_events where id = '" + stranded + "'"))
          .isEqualTo("PENDING");
      assertThat(queryInt(c, """
          select count(*) from outbox_events
           where id = '%s' and "leaseOwner" is null and "leaseUntil" is null
             and attempts = 2""".formatted(stranded)))
          .as("the dead worker's attempt still counts; the backoff is not reset")
          .isEqualTo(1);

      // Now it is claimable again, and the attempt counter advances.
      assertThat(claim(c, orgA, 50, "worker-E")).contains(stranded);
      assertThat(queryInt(c,
          "select attempts from outbox_events where id = '" + stranded + "'"))
          .isEqualTo(3);

      // The organisation-wide sweep works too, and never crosses a tenant.
      // (It is the tenant-less scheduling index; correctness is tenant-neutral
      //  because a claimed row must be re-read under tenant scope afterwards.)
      List<String> swept = sweepClaim(c, 3, "sweeper");
      assertThat(swept).hasSize(3);
      assertThat(queryInt(c, "select count(*) from outbox_events where state = 'PENDING' and "
          + "\"organizationId\" = '" + orgB + "'"))
          .as("another tenant's queue is untouched by this tenant's sweep")
          .isEqualTo(5);
    }
  }

  @Test
  @DisplayName("two concurrent claimers never claim the same outbox event")
  void twoConcurrentClaimersNeverClaimTheSameEvent() throws Exception {
    Tenant tenant = seedTenant("concurrency");
    UUID org = tenant.organizationId();
    Set<String> seeded = new HashSet<>(seedOutbox(org, tenant.projectId(), "race", 20, 0));
    assertThat(seeded).hasSize(20);

    // Handshake, not a sleep:
    //   A claims and keeps its transaction OPEN, so its 10 rows are locked.
    //   B runs its claim against the same rows. SKIP LOCKED must make B skip
    //   exactly A's rows, which is only observable while A holds them.
    CountDownLatch aHasLocked = new CountDownLatch(1);
    CountDownLatch bHasTried = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<List<String>> a = pool.submit(() -> {
        try (Connection c = connect(phase5aUrl)) {
          c.setAutoCommit(false);
          List<String> ids = claim(c, org, 10, "claimer-A");
          aHasLocked.countDown();
          assertThat(bHasTried.await(30, TimeUnit.SECONDS)).as("claimer B ran while A held its locks")
              .isTrue();
          c.commit();
          return ids;
        }
      });
      Future<List<String>> b = pool.submit(() -> {
        assertThat(aHasLocked.await(30, TimeUnit.SECONDS)).as("claimer A locked its rows").isTrue();
        try (Connection c = connect(phase5aUrl)) {
          c.setAutoCommit(false);
          List<String> ids = claim(c, org, 10, "claimer-B");
          bHasTried.countDown();
          c.commit();
          return ids;
        }
      });

      List<String> claimedByA = a.get(60, TimeUnit.SECONDS);
      List<String> claimedByB = b.get(60, TimeUnit.SECONDS);

      assertThat(claimedByA).hasSize(10);
      assertThat(claimedByB).hasSize(10);
      assertThat(new HashSet<>(claimedByA))
          .as("no event may be claimed twice -- this is the whole point of FOR UPDATE SKIP LOCKED")
          .doesNotContainAnyElementsOf(claimedByB);
      Set<String> union = new HashSet<>(claimedByA);
      union.addAll(claimedByB);
      assertThat(union)
          .as("the two claimers between them take the whole batch, exactly once")
          .containsExactlyInAnyOrderElementsOf(seeded);
    } finally {
      pool.shutdownNow();
    }

    try (Connection c = connect(phase5aUrl)) {
      assertThat(queryInt(c, """
          select count(*) from outbox_events
           where "organizationId" = '%s' and state = 'LEASED'""".formatted(org)))
          .as("every seeded event is leased exactly once")
          .isEqualTo(20);
      assertThat(queryInt(c, """
          select count(*) from outbox_events
           where "organizationId" = '%s' and attempts = 1""".formatted(org)))
          .as("no event was attempted twice")
          .isEqualTo(20);
      assertThat(queryInt(c, """
          select count(distinct "leaseOwner") from outbox_events
           where "organizationId" = '%s'""".formatted(org)))
          .isEqualTo(2);
    }
  }

  @Test
  @DisplayName("the claim query is index-supported, not a sequential scan")
  void claimQueryIsIndexSupported() throws Exception {
    Tenant tenant = seedTenant("plan");
    UUID org = tenant.organizationId();
    // Enough rows for the planner to have a distribution to reason about: with
    // an empty table it will pick any index and the assertion would be vacuous.
    for (int batch = 0; batch < 12; batch++) {
      seedOutbox(org, tenant.projectId(), "plan-" + batch, 500, batch % 3 == 0 ? 3600 : 0);
    }
    // Real expired leases must exist, or the reaper's plan assertion is testing
    // an empty result set.
    try (Connection c = connect(phase5aUrl)) {
      for (String id : claim(c, org, 5, "plan-worker")) {
        expireLease(c, id);
      }
      try (Statement s = c.createStatement()) {
        s.execute("analyze outbox_events");
      }
    }

    String tenantClaim = """
        select id from public.outbox_events
         where "organizationId" = '%s' and state = 'PENDING'
           and "nextAttemptAt" <= (now() at time zone 'UTC')
           and ("leaseUntil" is null or "leaseUntil" < (now() at time zone 'UTC'))
         order by "nextAttemptAt", "createdAt", id limit 50""".formatted(org);

    String plan = explain(tenantClaim);
    assertThat(plan).as("the tenant-scoped claim must not sequential scan the queue")
        .contains("Index Scan")
        .doesNotContain("Seq Scan");

    String sweep = """
        select id from public.outbox_events
         where state = 'PENDING'
           and "nextAttemptAt" <= (now() at time zone 'UTC')
           and ("leaseUntil" is null or "leaseUntil" < (now() at time zone 'UTC'))
         order by "nextAttemptAt", "createdAt", id limit 50""";
    assertThat(explain(sweep))
        .as("the organisation-wide sweep must not sequential scan the queue")
        .contains("Index Scan")
        .doesNotContain("Seq Scan");

    String reaper = """
        select id from public.outbox_events
         where state = 'LEASED' and "leaseUntil" < (now() at time zone 'UTC')
         order by "leaseUntil" limit 50""";
    assertThat(explain(reaper))
        .as("the crashed-worker reaper must use an index too")
        .contains("Index Scan")
        .doesNotContain("Seq Scan");
  }

  // ------------------------------------------------------------------
  // Scenario 4: dead letter, replay, attempt history
  // ------------------------------------------------------------------

  @Test
  @DisplayName("dead lettering, replay and attempt history are audited and append-only")
  void deadLetterReplayAndAttemptsAreAudited() throws Exception {
    Tenant tenant = seedTenant("dlq");
    UUID org = tenant.organizationId();
    UUID user = seedUser(org, "dlq-operator");

    String event = seedOutbox(org, tenant.projectId(), "dlq-1", 1, 0).get(0);

    try (Connection c = connect(phase5aUrl)) {
      // A DEAD row without its timestamp is refused by the CHECK constraint.
      assertThatThrownBy(() -> execute(c, """
          update outbox_events set state = 'DEAD', "updatedAt" = now()
           where id = '%s'""".formatted(event)))
          .as("outbox_events_dead_letter_check: a dead letter must carry deadLetteredAt")
          .isInstanceOf(SQLException.class);

      // Half a lease is refused too.
      assertThatThrownBy(() -> execute(c, """
          update outbox_events set "leaseOwner" = 'x', "updatedAt" = now()
           where id = '%s'""".formatted(event)))
          .as("outbox_events_lease_pair_check: owner and expiry are set together")
          .isInstanceOf(SQLException.class);

      // The real path: claim, dead-letter, then replay.
      assertThat(claim(c, org, 10, "worker-X")).contains(event);
      assertThat(deadLetter(c, org, event, "worker-X", "PROVIDER_REJECTED")).isEqualTo(1);
      assertThat(queryString(c, "select state from outbox_events where id = '" + event + "'"))
          .isEqualTo("DEAD");
      assertThat(queryInt(c, """
          select count(*) from outbox_events
           where id = '%s' and "deadLetteredAt" is not null
             and "deadLetterReason" = 'PROVIDER_REJECTED'""".formatted(event)))
          .isEqualTo(1);
      assertThat(claim(c, org, 10, "worker-Y"))
          .as("a dead event is not claimable")
          .isEmpty();

      // Attempt history: the failed attempt, then the replay attempt. The
      // failed attempt is never modified.
      String failedAttempt = recordAttempt(c, org, "outbound", "transport-9", 1,
          "NON_RETRYABLE_FAILURE", false, null, event);
      String failedOutcome = queryString(c,
          "select outcome from job_attempts where id = '" + failedAttempt + "'");
      assertThat(failedOutcome).isEqualTo("NON_RETRYABLE_FAILURE");

      // A replay attempt must name what it replays.
      assertThatThrownBy(() -> recordAttempt(c, org, "outbound", "transport-9", 2,
          "SUCCEEDED", true, null, event))
          .as("job_attempts_replay_check")
          .isInstanceOf(SQLException.class);

      // The same attempt number twice is refused.
      assertThatThrownBy(() -> recordAttempt(c, org, "outbound", "transport-9", 1,
          "SUCCEEDED", false, null, event))
          .as("attempt numbering is unique per tenant, queue and job")
          .isInstanceOf(SQLException.class);

      // The replay path, done properly.
      recordAttempt(c, org, "outbound", "transport-9", 2, "SUCCEEDED", true,
          failedAttempt, event);
      assertThat(queryString(c,
          "select outcome from job_attempts where id = '" + failedAttempt + "'"))
          .as("a replay creates a row; it never mutates the failed attempt")
          .isEqualTo(failedOutcome);
      assertThat(queryInt(c, """
          select count(*) from job_attempts
           where "organizationId" = '%s' and "outboxEventId" = '%s'
             and "isReplay" and "replayOfId" = '%s'""".formatted(org, event, failedAttempt)))
          .isEqualTo(1);

      // The dead letter row: created once, updated on replay.
      upsertDeadLetter(c, org, "outbound", "transport-9", failedAttempt, 3, event);
      upsertDeadLetter(c, org, "outbound", "transport-9", failedAttempt, 4, event);
      assertThat(queryInt(c, """
          select count(*) from job_dead_letters
           where "organizationId" = '%s' and "queueName" = 'outbound'
             and "jobId" = 'transport-9'""".formatted(org)))
          .as("one DLQ row per (organization, queue, job), however often it dies")
          .isEqualTo(1);
      assertThat(queryInt(c, """
          select count(*) from job_dead_letters
           where "organizationId" = '%s' and attempts = 7 and "replayCount" = 1
             and status = 'OPEN'::"DeadLetterStatus" """.formatted(org)))
          .as("attempts accumulate, the replay is counted, and the job is OPEN again")
          .isEqualTo(1);

      // Resolution requires who and when.
      assertThatThrownBy(() -> execute(c, """
          update job_dead_letters set status = 'RESOLVED'::"DeadLetterStatus",
                                     "updatedAt" = now()
           where "organizationId" = '%s' and "jobId" = 'transport-9'""".formatted(org)))
          .as("job_dead_letters_resolution_check")
          .isInstanceOf(SQLException.class);

      // Replay the event: audited, same id, attempt history preserved.
      assertThat(replay(c, org, event, user)).isEqualTo(1);
      assertThat(queryInt(c, """
          select count(*) from outbox_events
           where id = '%s' and state = 'PENDING' and "replayCount" = 1
             and "lastReplayedAt" is not null and "replayedByUserId" = '%s'
             and "deadLetteredAt" is null""".formatted(event, user)))
          .as("replay makes the event claimable again and records who and when")
          .isEqualTo(1);
      assertThat(claim(c, org, 10, "worker-Z")).contains(event);
    }
  }

  // ------------------------------------------------------------------
  // Scenario 5: idempotency records
  // ------------------------------------------------------------------

  @Test
  @DisplayName("idempotency records are tenant-scoped, reserved before work, and completed once")
  void idempotencyRecordsAreTenantScopedAndReserved() throws Exception {
    UUID orgA = seedTenant("idem-a").organizationId();
    UUID orgB = seedTenant("idem-b").organizationId();

    try (Connection c = connect(phase5aUrl)) {
      // First reservation wins, second is a no-op that returns nothing.
      assertThat(reserveIdempotency(c, orgA, "OUTBOUND_DELIVERY", "provider-evt-1"))
          .isTrue();
      assertThat(reserveIdempotency(c, orgA, "OUTBOUND_DELIVERY", "provider-evt-1"))
          .as("duplicate suppression comes from the unique index, not from a preceding SELECT")
          .isFalse();
      assertThat(queryInt(c, """
          select count(*) from idempotency_records
           where "organizationId" = '%s' and "idempotencyKey" = 'provider-evt-1'""".formatted(orgA)))
          .isEqualTo(1);

      // The same key in ANOTHER tenant is not a collision: uniqueness is
      // tenant-scoped, so a provider id cannot be probed across tenants.
      assertThat(reserveIdempotency(c, orgB, "OUTBOUND_DELIVERY", "provider-evt-1"))
          .as("tenant-scoped uniqueness: tenant B may use the same provider event id")
          .isTrue();

      // A different scope in the same tenant is also not a collision.
      assertThat(reserveIdempotency(c, orgA, "API_REQUEST", "provider-evt-1"))
          .as("scope is part of the key")
          .isTrue();

      // The lookup every retry starts with can never cross a tenant.
      assertThat(queryInt(c, """
          select count(*) from idempotency_records
           where "organizationId" = '%s' and "idempotencyKey" = 'provider-evt-1'
             and scope = 'OUTBOUND_DELIVERY'""".formatted(orgB)))
          .isEqualTo(1);
      assertThat(queryInt(c, """
          select count(*) from idempotency_records
           where "organizationId" = '%s' and "idempotencyKey" = 'provider-evt-1'""".formatted(orgA)))
          .isEqualTo(2);

      // In flight: a duplicate sees IN_PROGRESS with a live lock.
      assertThat(queryString(c, """
          select state from idempotency_records
           where "organizationId" = '%s' and scope = 'OUTBOUND_DELIVERY'
             and "idempotencyKey" = 'provider-evt-1'""".formatted(orgA)))
          .isEqualTo("IN_PROGRESS");

      // Complete it, with the result the retry will be served.
      assertThat(completeIdempotency(c, orgA, "OUTBOUND_DELIVERY", "provider-evt-1",
          202, "sha256-of-response")).isEqualTo(1);
      assertThat(queryInt(c, """
          select count(*) from idempotency_records
           where "organizationId" = '%s' and scope = 'OUTBOUND_DELIVERY'
             and "idempotencyKey" = 'provider-evt-1'
             and state = 'COMPLETED'::"IdempotencyState"
             and "responseStatus" = 202 and "lockExpiresAt" is null""".formatted(orgA)))
          .isEqualTo(1);

      // A completed record cannot be completed twice.
      assertThat(completeIdempotency(c, orgA, "OUTBOUND_DELIVERY", "provider-evt-1",
          500, "sha256-of-error")).isZero();

      // A finished record without its status, or an unfinished one with one,
      // is refused by the CHECK constraint.
      assertThatThrownBy(() -> execute(c, """
          insert into idempotency_records (id, "organizationId", scope, "idempotencyKey",
                                          state, "createdAt", "updatedAt")
          values (gen_random_uuid(), '%s', 'API_REQUEST', 'bad-1',
                  'COMPLETED'::"IdempotencyState", now(), now())""".formatted(orgA)))
          .as("idempotency_records_completed_result_check")
          .isInstanceOf(SQLException.class);
    }
  }

  // ------------------------------------------------------------------
  // Scenario 6: the repository copy and the packaged copy must be identical
  // ------------------------------------------------------------------

  @Test
  @DisplayName("ops/flyway/sql and the packaged db/migration chain are byte-identical")
  void repositoryAndClasspathMigrationCopiesAreIdentical() throws Exception {
    String repoRoot = System.getProperty("repoRoot");
    assertThat(repoRoot).as("repoRoot system property").isNotNull();
    Path packaged = Path.of(repoRoot, "backend/database/src/main/resources/db/migration");

    for (String name : CHAIN_FILES) {
      Path a = sqlDir.resolve(name);
      Path b = packaged.resolve(name);
      assertThat(Files.exists(b)).as("packaged copy %s", b).isTrue();
      assertThat(Files.readString(b)).as("copy of %s must be identical", name)
          .isEqualTo(Files.readString(a));
    }

    // Every reviewed file must EXIST on the reviewed side too. The loop above would pass if a
    // migration existed only on the classpath and CHAIN_FILES had not been updated, so the
    // direction is asserted as well as the contents: this is the check that catches a new
    // migration nobody recorded.
    try (var reviewed = Files.list(sqlDir)) {
      List<String> unrecorded = reviewed
          .map(p -> p.getFileName().toString())
          .filter(n -> n.startsWith("V") && n.endsWith(".sql"))
          .filter(n -> !CHAIN_FILES.contains(n))
          .toList();
      assertThat(unrecorded)
          .as("migrations in ops/flyway/sql that CHAIN_FILES does not name -- a new migration "
              + "with no byte-identity check and no recorded count is exactly how a classpath "
              + "replay or an unrecorded schema change reaches a boot")
          .isEmpty();
    }
    assertThat(CHAIN_FILES)
        .as("the chain is fifty-one files after the clinic rbac roles permissions and audit migration, and this constant is the "
            + "single place that number is written down besides migrationsExecuted")
        .hasSize(51);
    // The packaged copy must ALSO contain nothing extra beyond the platform slice overlays.
    try (var packagedFiles = Files.list(packaged)) {
      List<String> extra = packagedFiles
          .map(p -> p.getFileName().toString())
          .filter(n -> (n.startsWith("V") && n.endsWith(".sql"))
                    || (n.startsWith("V") && n.endsWith(".conf")))
          .filter(n -> !n.startsWith("V20261106")
                    && !n.startsWith("V20261107")
                    && !n.startsWith("V20261108")
                    && !n.startsWith("V20261109")
                    && !n.startsWith("V20261110")
                    && !n.startsWith("V20261111"))
          .filter(n -> !Files.exists(sqlDir.resolve(n)))
          .toList();
      assertThat(extra)
          .as("migrations present on the classpath but NOT in ops/flyway/sql -- the "
              + "reviewed copy is the source of truth and a classpath-only file bypasses it. "
              + "The .conf filter is here because a sidecar that exists only on the "
              + "classpath is how a CONCURRENTLY migration loses executeInTransaction=false.")
          .isEmpty();
    }
  }

  // ------------------------------------------------------------------
  // Phase-10b scenarios.
  //
  // One test per defect class, each of which would have FAILED against the
  // pre-batch schema. That is the whole point: a scenario that passes before
  // and after the fix proves nothing about the fix. Each negative is paired
  // with a positive control of the identical shape, because a test that only
  // ever proves refusal passes against a table that rejects everything.
  // ------------------------------------------------------------------

  @Test
  @DisplayName("a REJECTED approval carries its decision, a REVOKED one keeps its grant's, "
      + "and neither can hide who decided")
  void rejectedAndRevokedApprovalsCarryTheirDecision() throws Exception {
    UUID org = UUID.randomUUID();
    UUID project = UUID.randomUUID();
    // Four principals rather than three: the self-approval negative needs a requester and a
    // DECIDER WHO IS NOT THE REQUESTER, because ai_approvals_not_self_approved_check fires
    // first when they are the same row and the test would then be asserting the wrong refusal.
    UUID requester = UUID.randomUUID();
    UUID decider = UUID.randomUUID();
    UUID revoker = UUID.randomUUID();
    // The organization row has to exist before seedUser, and it is seeded first because
    // users_organizationId_fkey would otherwise refuse every principal below it.
    seedOrganizationAndProject(org, project);
    seedUser(org, "approval-requester");
    seedUser(org, "approval-decider");
    seedUser(org, "approval-revoker");
    UUID run = seedApprovalRunnable(org, project, "approve-" + UUID.randomUUID());
    // Re-read the ids the helper actually created rather than the ones minted above, because
    // ai_approvals carries FOUR composite tenant foreign keys to users and each one is a chance
    // to bind an id from a row that does not exist.
    UUID requesterId = userId(org, "approval-requester");
    UUID deciderId = userId(org, "approval-decider");
    UUID revokerId = userId(org, "approval-revoker");

    // --- D1, the defect itself: a REJECTED row that names its decider.
    //     Before V20261006100000 this insert was REFUSED by
    //     ai_approvals_approval_check, which permitted decidedAt only on APPROVED.
    String rejected = requestString(phase5aUrl, """
        insert into public.ai_approvals
             (id, "organizationId", "projectId", "aiRunId", action, "actionClass", status,
              "requestedByUserId", "requestedByPrincipal", reason, "requestedAt",
              "decidedByUserId", "decidedAt", "decisionNote", "expiresAt", "updatedAt")
        values (gen_random_uuid(), ?, ?, ?, 'book_appointment#' || ?, 'CONSEQUENTIAL',
                'REJECTED'::"public"."AiApprovalStatus",
                ?, 'approve@example.test', 'needs a second approver',
                (now() at time zone 'UTC'), ?, (now() at time zone 'UTC'),
                'refused: two people cannot approve the same slot',
                (now() at time zone 'UTC') + interval '1 hour',
                (now() at time zone 'UTC'))
        returning id::text""", org, project, run, run, requesterId, deciderId);

    try (Connection c = connect(phase5aUrl)) {
      // STATE AFTER THE ACTION, not the message the insert printed.
      assertThat(queryString(c,
          "select \"decidedByUserId\"::text from public.ai_approvals where id = '" + rejected + "'"))
          .as("a REJECTED approval records WHO refused it. ADR-0011 ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â§1: the schema simultaneously "
              + "demanded a decision record (ai_approvals_rejection_check demands a reason) and "
              + "forbade it (ai_approvals_approval_check permitted decidedAt only on APPROVED).")
          .isEqualTo(deciderId.toString());
      assertThat(queryInt(c,
          "select count(*) from public.ai_approvals where id = '" + rejected
              + "' and \"decidedAt\" is not null"))
          .as("...and WHEN, so the decision is dated as well as attributed")
          .isEqualTo(1);
    }

    // --- the negative: a REJECTED row that names nobody is refused.
    assertThatThrownBy(() -> execute(phase5aUrl, """
        insert into public.ai_approvals
             (id, "organizationId", "projectId", "aiRunId", action, "actionClass", status,
              "requestedByUserId", "requestedByPrincipal", reason, "requestedAt",
              "decisionNote", "expiresAt", "updatedAt")
        values (gen_random_uuid(), ?, ?, ?, 'send_invoice#' || ?, 'CONSEQUENTIAL',
                'REJECTED'::"public"."AiApprovalStatus",
                ?, 'approve@example.test', 'anonymous refusal',
                (now() at time zone 'UTC'), 'refused, by nobody',
                (now() at time zone 'UTC') + interval '1 hour',
                (now() at time zone 'UTC'))""", org, project, run, run, requesterId))
        .as("an unattributable rejection is refused -- a rejection nobody can name is a decision "
            + "nobody can learn from, and ai_approvals_decision_pair_check is what enforces it")
        .hasMessageContaining("ai_approvals_approval_check");

    // --- the REVOKED half, and the DEVITATION from ADR-0011's literal text.
    //     ADR-0011 ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â§1 rule 4 requires the revocation path to be restored, and
    //     ai_approvals_decision_pair_check is a biconditional, so a REVOKED row
    //     that keeps WHO granted it must also keep decidedAt. That is why
    //     'REVOKED' appears in the corrected IN list; 'EXPIRED' does NOT, because
    //     PENDING -> EXPIRED means nobody decided it.
    String approved = requestString(phase5aUrl, """
        insert into public.ai_approvals
             (id, "organizationId", "projectId", "aiRunId", action, "actionClass", status,
              "requestedByUserId", "requestedByPrincipal", reason, "requestedAt",
              "decidedByUserId", "decidedAt", "expiresAt", "updatedAt")
        values (gen_random_uuid(), ?, ?, ?, 'book_appointment#' || ?, 'CONSEQUENTIAL',
                'APPROVED'::"public"."AiApprovalStatus",
                ?, 'approve@example.test', 'approved for the fixture',
                (now() at time zone 'UTC'), ?, (now() at time zone 'UTC'),
                (now() at time zone 'UTC') + interval '1 hour',
                (now() at time zone 'UTC'))
        returning id::text""", org, project, run, run, requesterId, deciderId);

    String decidedAtBefore = queryString(connect(phase5aUrl),
        "select to_char(\"decidedAt\", 'YYYY-MM-DD HH24:MI:SS.MS') from public.ai_approvals"
            + " where id = '" + approved + "'");

    execute(phase5aUrl, """
        update public.ai_approvals
           set status = 'REVOKED'::"public"."AiApprovalStatus",
               "revokedAt" = (now() at time zone 'UTC'),
               "revokedByUserId" = ?,
               "revokeReason" = 'the slot was taken',
               "updatedAt" = (now() at time zone 'UTC')
         where id = ?""", revokerId, UUID.fromString(approved));

    try (Connection c = connect(phase5aUrl)) {
      assertThat(queryString(c,
          "select to_char(\"decidedAt\", 'YYYY-MM-DD HH24:MI:SS.MS') from public.ai_approvals"
              + " where id = '" + approved + "'"))
          .as("revocation changes the STATUS; it does not un-decide the request. The grant's "
              + "decidedAt survives, which is what makes the revocation auditable.")
          .isEqualTo(decidedAtBefore);
      assertThat(queryString(c,
          "select \"decidedByUserId\"::text from public.ai_approvals where id = '" + approved + "'"))
          .as("...and so does the identity of whoever granted it")
          .isEqualTo(deciderId.toString());
      assertThat(queryString(c,
          "select \"revokedByUserId\"::text from public.ai_approvals where id = '" + approved + "'"))
          .isEqualTo(revokerId.toString());
    }

    // --- and the negative that keeps the rule tight: EXPIRED carries NO decision.
    assertThatThrownBy(() -> execute(phase5aUrl, """
        update public.ai_approvals
           set status = 'EXPIRED'::"public"."AiApprovalStatus",
               "updatedAt" = (now() at time zone 'UTC')
         where id = ?""", UUID.fromString(approved)))
        .as("a lapsed request was never decided by anybody, so requiring a decider on EXPIRED "
            + "would invent one -- 'EXPIRED' is deliberately absent from the corrected IN list")
        .hasMessageContaining("ai_approvals_approval_check");

    // --- the OTHER direction of the biconditional: REJECTED may not LOSE its record.
    //     The attempt above (re-stamping a decision that is already there) is deliberately
    //     not asserted as a refusal, because it is a legal write: REJECTED still satisfies
    //     "REJECTED implies decidedAt IS NOT NULL" after a re-stamp. Only REMOVING the
    //     record breaks it.
    assertThatThrownBy(() -> execute(phase5aUrl, """
        update public.ai_approvals
           set "decidedAt" = null,
               "decidedByUserId" = null,
               "updatedAt" = (now() at time zone 'UTC')
         where id = ?""", UUID.fromString(rejected)))
        .as("the biconditional runs BOTH ways: a REJECTED row may not lose its decision record. "
            + "Under the pre-batch rule this was the ONLY REJECTED row the schema admitted, which "
            + "is the defect ADR-0011 section 1 describes.")
        .hasMessageContaining("ai_approvals_approval_check");

    // --- and the mirror: a row may not move to a decision-free state while still
    //     CARRYING its decision pair.
    //
    //     NB clearing the pair at the same time is LEGAL, and is deliberately not
    //     asserted as a refusal: REJECTED -> PENDING with both halves cleared is a
    //     re-request, and the biconditional is satisfied after it. Only moving to a
    //     decision-free state while KEEPING the pair breaks the rule, and that is what
    //     is refused here. Asserting the legal write as an illegal one would be a test
    //     that passes for the wrong reason.
    assertThatThrownBy(() -> execute(phase5aUrl, """
        update public.ai_approvals
           set status = 'PENDING'::"public"."AiApprovalStatus",
               "updatedAt" = (now() at time zone 'UTC')
         where id = ?""", UUID.fromString(approved)))
        .as("a PENDING row carries no decision, so a decided row cannot simply be relabelled "
            + "PENDING while keeping its pair: the rule pins the pair to the state rather than "
            + "merely requiring it somewhere on the row")
        .hasMessageContaining("ai_approvals_approval_check");

    // --- self-approval is still refused, so the correction did not weaken anything.
    //     The requester and the decider are the SAME principal here, which is the only way
    //     ai_approvals_not_self_approved_check can fire: given two different users this row
    //     is perfectly legal, and the negative would then be asserting the wrong refusal.
    assertThatThrownBy(() -> execute(phase5aUrl, """
        insert into public.ai_approvals
             (id, "organizationId", "projectId", "aiRunId", action, "actionClass", status,
              "requestedByUserId", "requestedByPrincipal", reason, "requestedAt",
              "decidedByUserId", "decidedAt", "decisionNote", "expiresAt", "updatedAt")
        values (gen_random_uuid(), ?, ?, ?, 'cancel_order#' || ?, 'CONSEQUENTIAL',
                'APPROVED'::"public"."AiApprovalStatus",
                ?, 'approve@example.test', 'self approval attempt',
                (now() at time zone 'UTC'), ?, (now() at time zone 'UTC'), 'sure',
                (now() at time zone 'UTC') + interval '1 hour',
                (now() at time zone 'UTC'))""",
        org, project, run, run, requesterId, requesterId))
        .as("plan.md forbids an unrestricted identity and self-approval is the same problem "
            + "wearing a workflow. Widening the biconditional to admit REJECTED did not touch it.")
        .hasMessageContaining("ai_approvals_not_self_approved_check");

    // --- and the positive control for that pair: two DIFFERENT users, same shape, accepted.
    String witnessed = requestString(phase5aUrl, """
        insert into public.ai_approvals
             (id, "organizationId", "projectId", "aiRunId", action, "actionClass", status,
              "requestedByUserId", "requestedByPrincipal", reason, "requestedAt",
              "decidedByUserId", "decidedAt", "decisionNote", "expiresAt", "updatedAt")
        values (gen_random_uuid(), ?, ?, ?, 'cancel_order#' || ?, 'CONSEQUENTIAL',
                'APPROVED'::"public"."AiApprovalStatus",
                ?, 'approve@example.test', 'two-person approval',
                (now() at time zone 'UTC'), ?, (now() at time zone 'UTC'), 'countersigned',
                (now() at time zone 'UTC') + interval '1 hour',
                (now() at time zone 'UTC'))
        returning id::text""", org, project, run, run, requesterId, deciderId);

    assertThat(witnessed)
        .as("the positive control for the self-approval negative above: the IDENTICAL statement "
            + "with two different users is accepted, so the refusal is caused by the requester "
            + "and the decider being the same person and not by the insert being malformed")
        .isNotBlank();
  }

  @Test
  @DisplayName("a document chunk whose tenant disagrees with its document is refused by the "
      + "database, not only by the ingestion code")
  void chunksCannotBeScopedToAnotherTenantsDocument() throws Exception {
    UUID orgA = UUID.randomUUID();
    UUID orgB = UUID.randomUUID();
    UUID projectA = UUID.randomUUID();
    UUID projectB = UUID.randomUUID();
    UUID kbA = UUID.randomUUID();

    // documents needs its own FK set, so seed a real tenant in each.
    seedOrganizationAndProject(orgA, projectA);
    seedOrganizationAndProject(orgB, projectB);
    UUID document = UUID.randomUUID();
    UUID documentInB = UUID.randomUUID();
    execute(phase5aUrl, """
        insert into public.knowledge_bases (id, "organizationId", "projectId", name, "createdAt", "updatedAt")
        values (?, ?, ?, 'KB', (now() at time zone 'UTC'), (now() at time zone 'UTC'))""",
        kbA, orgA, projectA);
    insertDocument(document, orgA, projectA, kbA);
    insertDocument(documentInB, orgB, projectB, kbA);

    // --- the positive control: the SAME statement, same tenant, is accepted.
    execute(phase5aUrl, """
        insert into public.document_chunks
             (id, "organizationId", "projectId", "knowledgeBaseId", "documentId", "chunkIndex",
              "content", "tokenCount", "createdAt")
        values (gen_random_uuid(), ?, ?, ?, ?, 0, 'ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â¹Ãƒâ€¦Ã¢â‚¬Å“ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â³ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â¹Ãƒâ€¦Ã¢â‚¬Å“ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â§ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â¹Ãƒâ€¦Ã¢â‚¬Å“ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¹ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â¹Ãƒâ€¦Ã¢â‚¬Å“ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â§ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â¹Ãƒâ€¦Ã¢â‚¬Å“ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Âª ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â¹Ãƒâ€¦Ã¢â‚¬Å“ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â§ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¾Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã¢â‚¬Â¦Ãƒâ€šÃ‚Â¾ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â¹Ãƒâ€¦Ã¢â‚¬Å“ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¹ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¾Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¦ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¾Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã¢â‚¬Â¦Ãƒâ€šÃ‚Â¾ ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¾Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¦ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¾Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â  ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â¹Ãƒâ€¦Ã¢â‚¬Å“ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â§ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¾Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã¢â‚¬Â¦Ãƒâ€šÃ‚Â¾ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â¹Ãƒâ€¦Ã¢â‚¬Å“ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â£ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â¹Ãƒâ€¦Ã¢â‚¬Å“ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â­ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â¹Ãƒâ€¦Ã¢â‚¬Å“ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¯', 5,
                (now() at time zone 'UTC'))""", orgA, projectA, kbA, document);
    try (Connection c = connect(phase5aUrl)) {
      assertThat(queryInt(c, "select count(*) from public.document_chunks"
              + " where \"documentId\" = '" + document + "'"))
          .as("the positive control: a chunk whose tenant AGREES with its document is accepted, "
              + "so the negative below is caused by the disagreement and not by the insert")
          .isEqualTo(1);
    }

    // --- the negative: tenant B's chunk pointing at tenant A's document.
    //     A DIFFERENT chunkIndex from the positive control on purpose:
    //     document_chunks_documentId_chunkIndex_key is a baseline unique index, so reusing 0
    //     would be refused by THAT constraint first and the test would prove the wrong thing.
    assertThatThrownBy(() -> execute(phase5aUrl, """
        insert into public.document_chunks
             (id, "organizationId", "projectId", "knowledgeBaseId", "documentId", "chunkIndex",
              "content", "tokenCount", "createdAt")
        values (gen_random_uuid(), ?, ?, ?, ?, 1, 'cross tenant', 2,
                (now() at time zone 'UTC'))""", orgB, projectB, kbA, document))
        .as("a vector row scoped to the wrong tenant is a RETRIEVAL LEAK, not a data-quality "
            + "issue. Before V20261006110000 the single-column documentId FK could not see this; "
            + "only Phase 10's ingestion code stood between it and a mis-scoped embedding.")
        .hasMessageContaining("document_chunks_document_tenant_fkey");

    // --- and the state afterwards, which is the assertion that matters.
    try (Connection c = connect(phase5aUrl)) {
      assertThat(queryInt(c, """
          select count(*) from public.document_chunks c
            join public.documents d on d."id" = c."documentId"
           where d."organizationId" <> c."organizationId"
          """))
          .as("STATE AFTER THE REFUSAL: zero chunks disagree with their document's tenant")
          .isZero();
    }

    // --- the composite constraint did not replace the single-column FK: the
    //     deletion-propagation contract Phase 10 asserts must still be there.
    try (Connection c = connect(phase5aUrl)) {
      assertThat(queryInt(c, """
          select count(*) from pg_constraint
           where conrelid = 'public.document_chunks'::regclass
             and contype = 'f'
             and pg_get_constraintdef(oid) like '%FOREIGN KEY ("documentId", "organizationId")%'"""))
          .as("document_chunks_document_tenant_fkey is COMPOSITE -- on both columns, or it would "
              + "be the defect it replaces")
          .isEqualTo(1);
      assertThat(queryInt(c, """
          select count(*) from pg_constraint
           where conrelid = 'public.document_chunks'::regclass
             and conname = 'document_chunks_documentId_fkey'"""))
          .as("...and the pre-existing single-column FK is KEPT. Dropping it would have been a "
              + "second, unreported regression, and its ON DELETE CASCADE is the behaviour Phase "
              + "10's deletion-propagation test depends on.")
          .isEqualTo(1);
    }

    // --- and the cross-tenant reference in the OTHER direction is refused too,
    //     which is the pair that proves the constraint is a real biconditional.
    assertThatThrownBy(() -> execute(phase5aUrl, """
        insert into public.document_chunks
             (id, "organizationId", "projectId", "knowledgeBaseId", "documentId", "chunkIndex",
              "content", "tokenCount", "createdAt")
        values (gen_random_uuid(), ?, ?, ?, ?, 2, 'reverse', 1,
                (now() at time zone 'UTC'))""", orgA, projectA, kbA, documentInB))
        .hasMessageContaining("document_chunks_document_tenant_fkey");
  }

  @Test
  @DisplayName("an API key stores a hash and never a plaintext secret, and its creation and "
      + "revocation are audited by the database")
  void apiKeysStoreHashesAndAuditTheirOwnLifecycle() throws Exception {
    Tenant tenant = seedTenant("keyfixture");
    UUID principal = seedUser(tenant.organizationId(), "key-admin");

    String servicePrincipalId = queryStringReturningId(connect(phase5aUrl), """
        insert into public.service_principals
             (id, "organizationId", "projectId", "principalKey", "displayName", status,
              capabilities, "createdByUserId", "createdAt", "updatedAt")
        values (gen_random_uuid(), ?, ?, 'connector-twilio', 'Twilio connector',
                'ACTIVE'::"public"."ServicePrincipalStatus",
                '["conversation:read"]'::jsonb, ?,
                (now() at time zone 'UTC'), (now() at time zone 'UTC'))
        returning id::text""",
        tenant.organizationId(), tenant.projectId(), principal);

    // --- the insertion is possible (FR-6 asks for it, and Phase 7 reported it absent)
    String keyId = queryStringReturningId(connect(phase5aUrl), """
        insert into public.api_keys
             (id, "organizationId", "projectId", "servicePrincipalId", name, "keyPrefix",
              "keyHash", "hashAlgorithm", scopes, status, "createdByUserId", "expiresAt",
              "createdAt", "updatedAt")
        values (gen_random_uuid(), ?, ?, ?, 'nightly export', 'fe_live_9f2ac1',
                '$argon2id$v=19$m=65536,t=3,p=4$FIXTURESALT$FIXTUREHASHFIXTUREHASHFIXTUREHASH1234',
                'argon2id', '["conversation:read"]'::jsonb,
                'ACTIVE'::"public"."ApiKeyStatus", ?,
                (now() at time zone 'UTC') + interval '30 days',
                (now() at time zone 'UTC'), (now() at time zone 'UTC'))
        returning id::text""",
        tenant.organizationId(), tenant.projectId(),
        UUID.fromString(servicePrincipalId), principal);

    try (Connection c = connect(phase5aUrl)) {
      assertThat(queryInt(c,
          "select count(*) from public.api_keys where id = '" + keyId + "'"))
          .as("FR-6: an operator must be able to manage an API key, which needs a table to manage "
              + "it in. Phase 7 reported credential PRESENCE as a database-computed boolean "
              + "because no such table existed.")
          .isEqualTo(1);

      // --- THE PLAINTEXT NEGATIVE. The database, not the code, refuses it.
      assertThatThrownBy(() -> execute(phase5aUrl, """
          insert into public.api_keys
               (id, "organizationId", "projectId", "servicePrincipalId", name, "keyPrefix",
                "keyHash", "hashAlgorithm", scopes, status, "expiresAt", "createdAt", "updatedAt")
          values (gen_random_uuid(), ?, ?, ?, 'plaintext key', 'fe_live_plain',
                  'fe_live_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA',
                  'argon2id', '[]'::jsonb, 'ACTIVE'::"public"."ApiKeyStatus",
                  (now() at time zone 'UTC') + interval '30 days',
                  (now() at time zone 'UTC'), (now() at time zone 'UTC'))""",
          tenant.organizationId(), tenant.projectId(),
          UUID.fromString(servicePrincipalId)))
          .as("a plaintext secret in keyHash is refused BY THE DATABASE. FR-6 and AGENTS.md forbid "
              + "storing a credential in readable form, and the only secret-shaped column on this "
              + "table refuses it -- so the guarantee does not depend on a code path remembering "
              + "to hash first.")
          .hasMessageContaining("KDF digest");

      // --- and there is nowhere ELSE to put one: no column on either table is
      //     named like a secret carrier.
      assertThat(queryStrings(c, """
          select c.relname || '.' || a.attname
            from pg_class c
              join pg_namespace n on n.oid = c.relnamespace
              join pg_attribute a on a.attrelid = c.oid
           where n.nspname = 'public'
             and c.relname in ('api_keys', 'service_principals')
             and a.attnum > 0 and not a.attisdropped
             and lower(a.attname) ~ '(secret|plaintext|password|passphrase|credential|rawkey|privatekey)'
           order by 1"""))
          .as("'must not be able to store a plaintext secret' is not a promise about keyHash alone: "
              + "it is also a census proving no OTHER column could hold one")
          .isEmpty();

      assertThat(queryInt(c, """
          select count(*) from pg_trigger
           where tgrelid = 'public.api_keys'::regclass and not tgisinternal
             and tgname = 'api_keys_hash_guard' and tgenabled = 'O'"""))
          .as("the guard is a real, ENABLED trigger rather than a convention in a comment")
          .isEqualTo(1);

      // --- the prefix cannot be the whole secret
      assertThatThrownBy(() -> execute(phase5aUrl, """
          insert into public.api_keys
               (id, "organizationId", "projectId", "servicePrincipalId", name, "keyPrefix",
                "keyHash", "hashAlgorithm", scopes, status, "expiresAt", "createdAt", "updatedAt")
          values (gen_random_uuid(), ?, ?, ?, 'prefix-as-secret',
                  '$argon2id$v=19$m=65536,t=3,p=4$SALT$HASHHASHHASHHASHHASHHASHHASHHASHHASHHASHHASH12',
                  '$argon2id$v=19$m=65536,t=3,p=4$SALT$HASHHASHHASHHASHHASHHASHHASHHASHHASHHASHHASH12',
                  'argon2id', '[]'::jsonb, 'ACTIVE'::"public"."ApiKeyStatus",
                  (now() at time zone 'UTC') + interval '30 days',
                  (now() at time zone 'UTC'), (now() at time zone 'UTC'))""",
          tenant.organizationId(), tenant.projectId(),
          UUID.fromString(servicePrincipalId)))
          .hasMessageContaining("api_keys_hash_not_prefix_check");

      // --- and a prefix long enough to BE the secret is refused
      assertThatThrownBy(() -> execute(phase5aUrl, """
          insert into public.api_keys
               (id, "organizationId", "projectId", "servicePrincipalId", name, "keyPrefix",
                "keyHash", "hashAlgorithm", scopes, status, "expiresAt", "createdAt", "updatedAt")
          values (gen_random_uuid(), ?, ?, ?, 'whole key as prefix', repeat('x', 80),
                  '$argon2id$v=19$m=65536,t=3,p=4$SALT$HASHHASHHASHHASHHASHHASHHASHHASHHASHHASHHASH12',
                  'argon2id', '[]'::jsonb, 'ACTIVE'::"public"."ApiKeyStatus",
                  (now() at time zone 'UTC') + interval '30 days',
                  (now() at time zone 'UTC'), (now() at time zone 'UTC'))""",
          tenant.organizationId(), tenant.projectId(),
          UUID.fromString(servicePrincipalId)))
          .as("a prefix that could hold the whole key is not a prefix; without this bound a writer "
              + "could 'for convenience' put the secret in keyPrefix, which the comment says is "
              + "safe to display")
          .hasMessageContaining("api_keys_key_prefix_length_check");
    }

    // --- AUDIT: the creation was recorded BY THE DATABASE.
    try (Connection c = connect(phase5aUrl)) {
      assertThat(queryInt(c, """
          select count(*) from public.admin_audit_events
           where "resourceType" = 'api_keys' and "resourceId" = ?
             and "action" = 'api_keys.created'""", keyId))
          .as("Phase 7's convention is that changing a tenant's authentication is an audit row. "
              + "firstelement_principal_lifecycle_audit() writes it in the SAME transaction as the "
              + "lifecycle change, so a code path that mints a key without auditing cannot exist.")
          .isEqualTo(1);
      assertThat(queryInt(c, """
          select count(*) from public.admin_audit_events
           where "resourceType" = 'api_keys' and "resourceId" = ?
             and jsonb_exists("metadata", 'keyHash')""", keyId))
          .as("...and the digest is NEVER in the audit metadata. keyPrefix is a lookup handle and "
              + "is safe to record; keyHash is not recorded at all.")
          .isZero();
    }

    // --- the revocation, its evidence, and its audit row.
    UUID revoker = seedUser(tenant.organizationId(), "key-revoker");
    execute(phase5aUrl, """
        update public.api_keys
           set status = 'REVOKED'::"public"."ApiKeyStatus",
               "revokedAt" = (now() at time zone 'UTC'),
               "revokedByUserId" = ?,
               "revokeReason" = 'rotated after the connector was replaced',
               "updatedAt" = (now() at time zone 'UTC')
         where id = ?""", revoker, UUID.fromString(keyId));

    try (Connection c = connect(phase5aUrl)) {
      assertThat(queryString(c,
          "select \"revokedByUserId\"::text from public.api_keys where id = '" + keyId + "'"))
          .as("a revoked key says WHO revoked it")
          .isEqualTo(revoker.toString());
      assertThat(queryInt(c, """
          select count(*) from public.admin_audit_events
           where "resourceType" = 'api_keys' and "resourceId" = ?
             and "action" = 'api_keys.status_changed'
             and "metadata" ->> 'fromStatus' = 'ACTIVE'
             and "metadata" ->> 'toStatus' = 'REVOKED'""", keyId))
          .as("...and the revocation was audited with the transition recorded, because ADR-0011's "
              + "concern is an unauditable credential lifecycle rather than a missing table")
          .isEqualTo(1);
    }

    // --- the negatives the lifecycle CHECKs are for. The first needs a SECOND, still-ACTIVE
    //     key: the key above is already REVOKED with its evidence, and re-asserting REVOKED on
    //     it is a legal no-op, so asserting a refusal there would be asserting nothing.
    String activeKeyId = queryStringReturningId(connect(phase5aUrl), """
        insert into public.api_keys
             (id, "organizationId", "projectId", "servicePrincipalId", name, "keyPrefix",
              "keyHash", "hashAlgorithm", scopes, status, "createdByUserId", "expiresAt",
              "createdAt", "updatedAt")
        values (gen_random_uuid(), ?, ?, ?, 'second active key', 'fe_live_second',
                '$argon2id$v=19$m=65536,t=3,p=4$OTHERSALT$OTHERHASHOTHERHASHOTHERHASHOTHERHASH123',
                'argon2id', '[]'::jsonb, 'ACTIVE'::"public"."ApiKeyStatus", ?,
                (now() at time zone 'UTC') + interval '30 days',
                (now() at time zone 'UTC'), (now() at time zone 'UTC'))
        returning id::text""",
        tenant.organizationId(), tenant.projectId(),
        UUID.fromString(servicePrincipalId), principal);

    assertThatThrownBy(() -> execute(phase5aUrl, """
        update public.api_keys
           set status = 'REVOKED'::"public"."ApiKeyStatus", "updatedAt" = (now() at time zone 'UTC')
         where id = ?""", UUID.fromString(activeKeyId)))
        .as("an ACTIVE key cannot be flipped to REVOKED without who / when / why. The reverse also "
            + "holds -- an ACTIVE or EXPIRED key carries no revocation evidence -- so clearing the "
            + "triple is a legal transition and not itself a violation.")
        .hasMessageContaining("api_keys_revocation_check");

    assertThatThrownBy(() -> execute(phase5aUrl, """
        update public.api_keys
           set status = 'EXPIRED'::"public"."ApiKeyStatus",
               "revokedAt" = null, "revokedByUserId" = null, "revokeReason" = null,
               "updatedAt" = (now() at time zone 'UTC')
         where id = ?""", UUID.fromString(keyId)))
        .as("EXPIRED is a RECORDED state, not a derived one: a key whose expiresAt has passed but "
            + "which nobody marked EXPIRED carries no dated evidence that it lapsed, and every "
            + "other state in this table says who did what and when")
        .hasMessageContaining("api_keys_expiration_recorded_check");

    // --- and the cross-tenant negative: a key bound to another tenant's principal.
    Tenant elsewhere = seedTenant("keyother");
    assertThatThrownBy(() -> execute(phase5aUrl, """
        insert into public.api_keys
             (id, "organizationId", "projectId", "servicePrincipalId", name, "keyPrefix",
              "keyHash", "hashAlgorithm", scopes, status, "expiresAt", "createdAt", "updatedAt")
        values (gen_random_uuid(), ?, ?, ?, 'borrowed principal', 'fe_live_borrow',
                '$argon2id$v=19$m=65536,t=3,p=4$SALT$HASHHASHHASHHASHHASHHASHHASHHASHHASHHASHHASH12',
                'argon2id', '[]'::jsonb, 'ACTIVE'::"public"."ApiKeyStatus",
                (now() at time zone 'UTC') + interval '30 days',
                (now() at time zone 'UTC'), (now() at time zone 'UTC'))""",
        elsewhere.organizationId(), elsewhere.projectId(),
        UUID.fromString(servicePrincipalId)))
        .as("a key may not be bound to another tenant's service principal -- the same "
            + "composite-tenant-FK convention every other FK in this chain follows")
        .hasMessageContaining("api_keys_service_principal_tenant_fkey");

    // --- the composite tenant FK on the principal is real
    try (Connection c = connect(phase5aUrl)) {
      assertThat(queryInt(c, """
          select count(*) from pg_constraint
           where conrelid = 'public.api_keys'::regclass
             and conname = 'api_keys_service_principal_tenant_fkey'
             and pg_get_constraintdef(oid) like '%FOREIGN KEY ("servicePrincipalId", "organizationId")%'"""))
          .as("a key may only belong to a principal of ITS OWN tenant -- the same composite-"
              + "tenant-FK convention every other FK in this chain follows, and the one "
              + "document_chunks lacked until V20261006110000")
          .isEqualTo(1);
    }
  }

  @Test
  @DisplayName("a RECORD-scoped legal hold is placeable, and the two refusals the widening must "
      + "not have relaxed still refuse")
  void narrowLegalHoldsArePlaceableAndTheOldRefusalsHold() throws Exception {
    Tenant tenant = seedTenant("holdfixture");
    UUID user = seedUser(tenant.organizationId(), "hold-admin");
    UUID document = UUID.randomUUID();

    // --- RECORD: the most protective hold there is, and before V20261006130000
    //     the schema could not store it because legal_holds_scope_pair_check had
    //     no arm for RECORD -- while legal_holds_scope_ref_check explicitly
    //     permitted one. Two constraints, one of them contradictory.
    String record = queryStringReturningId(connect(phase5aUrl), """
        insert into public.legal_holds
             (id, "organizationId", "scopeType", "scopeRef", reason, status, "placedAt",
              "placedByUserId", "createdAt", "updatedAt")
        values (gen_random_uuid(), ?, 'RECORD'::"public"."LegalHoldScopeType",
                'conversation:' || ?, 'litigation hold on one conversation', 'ACTIVE',
                (now() at time zone 'UTC'), ?,
                (now() at time zone 'UTC'), (now() at time zone 'UTC'))
        returning id::text""", tenant.organizationId(), document, user);

    // --- TABLE: the other missing arm
    String table = queryStringReturningId(connect(phase5aUrl), """
        insert into public.legal_holds
             (id, "organizationId", "scopeType", "scopeRef", reason, status, "placedAt",
              "placedByUserId", "createdAt", "updatedAt")
        values (gen_random_uuid(), ?, 'TABLE'::"public"."LegalHoldScopeType", 'conversations',
                'litigation hold on one table', 'ACTIVE',
                (now() at time zone 'UTC'), ?,
                (now() at time zone 'UTC'), (now() at time zone 'UTC'))
        returning id::text""", tenant.organizationId(), user);

    // --- PROJECT still demands a project: the rule the widening had to keep.
    assertThatThrownBy(() -> execute(phase5aUrl, """
        insert into public.legal_holds
             (id, "organizationId", "scopeType", reason, status, "placedAt", "placedByUserId",
              "createdAt", "updatedAt")
        values (gen_random_uuid(), ?, 'PROJECT'::"public"."LegalHoldScopeType",
                'a project hold naming no project', 'ACTIVE',
                (now() at time zone 'UTC'), ?,
                (now() at time zone 'UTC'), (now() at time zone 'UTC'))""",
        tenant.organizationId(), user))
        .as("a PROJECT hold that names no project holds nothing at all, which is the specific "
            + "failure the original constraint was written to refuse. Widening the pair check "
            + "must not have lost it.")
        .hasMessageContaining("legal_holds_scope_pair_check");

    // --- TENANT still demands no scopeRef.
    assertThatThrownBy(() -> execute(phase5aUrl, """
        insert into public.legal_holds
             (id, "organizationId", "scopeType", "scopeRef", reason, status, "placedAt",
              "placedByUserId", "createdAt", "updatedAt")
        values (gen_random_uuid(), ?, 'TENANT'::"public"."LegalHoldScopeType", 'conversations',
                'a tenant hold naming a table', 'ACTIVE',
                (now() at time zone 'UTC'), ?,
                (now() at time zone 'UTC'), (now() at time zone 'UTC'))""",
        tenant.organizationId(), user))
        .as("a tenant-wide hold names no table and no record")
        .hasMessageContaining("legal_holds_scope_pair_check");

    // --- a RECORD hold with no scopeRef is refused by the same pair check.
    assertThatThrownBy(() -> execute(phase5aUrl, """
        insert into public.legal_holds
             (id, "organizationId", "scopeType", reason, status, "placedAt", "placedByUserId",
              "createdAt", "updatedAt")
        values (gen_random_uuid(), ?, 'RECORD'::"public"."LegalHoldScopeType",
                'a record hold naming no record', 'ACTIVE',
                (now() at time zone 'UTC'), ?,
                (now() at time zone 'UTC'), (now() at time zone 'UTC'))""",
        tenant.organizationId(), user))
        .as("the new arm delegates to the same 'scopeRef must be a non-blank string' rule "
            + "legal_holds_scope_ref_check already states, so the two constraints cannot disagree "
            + "about what a narrow hold needs")
        .hasMessageContaining("legal_holds");

    // --- STATE AFTER: both narrow holds exist, and the census is what Phase 7's
    //     covering read model needs in order to become non-zero.
    try (Connection c = connect(phase5aUrl)) {
      assertThat(queryInt(c, "select count(*) from public.legal_holds where id in (?, ?)",
              UUID.fromString(record), UUID.fromString(table)))
          .as("STATE AFTER THE INSERTS: both a RECORD-scoped and a TABLE-scoped hold exist. "
              + "Before this batch the `narrow` count in Phase 7's covering read model was not "
              + "merely zero, it was STRUCTURALLY UNREACHABLE.")
          .isEqualTo(2);
      assertThat(queryInt(c, """
          select count(*) from public.legal_holds
           where "scopeType" in ('TABLE'::"public"."LegalHoldScopeType",
                                 'RECORD'::"public"."LegalHoldScopeType")"""))
          .isEqualTo(2);

      // --- TOTALITY, asserted from the installed definition. Every enum member has
      //     a satisfiable arm, which is the claim "no member falls through to false".
      assertThat(queryStrings(c, enumQuery("LegalHoldScopeType")))
          .as("the vocabulary still offers all four, which is why this was a gap and not a design "
              + "decision, and now all four can actually be held")
          .containsExactly("TENANT", "PROJECT", "TABLE", "RECORD");
      assertThat(queryString(c, """
          select pg_get_constraintdef(oid) from pg_constraint
           where conrelid = 'public.legal_holds'::regclass
             and conname = 'legal_holds_scope_pair_check'"""))
          .as("the installed definition names all four members")
          .contains("TABLE")
          .contains("RECORD");
    }
  }

  @Test
  @DisplayName("a CUSTOMER deletion is expressible with no user id at all, and the USER decision "
      + "pair and the verification evidence are untouched")
  void customerPrivacyDeletionsNeedNoUserId() throws Exception {
    Tenant tenant = seedTenant("privacyfixture");
    UUID requester = seedUser(tenant.organizationId(), "privacy-requester");

    // --- a customer. NOT a users row, and that is the entire defect: the table's
    //     subjectType DEFAULTS to CUSTOMER while its only way to name a subject
    //     was a users row, so the commonest FR-7 request could not be recorded.
    String cxId = "CX" + String.format("%06d", CONVERSATION_SEQ.incrementAndGet());
    execute(phase5aUrl, """
        insert into public.customers (id, "organizationId", "projectId", "cxId", "displayName",
                                      "createdAt", "updatedAt")
        values (gen_random_uuid(), ?, ?, ?, 'Fixture Customer',
                (now() at time zone 'UTC'), (now() at time zone 'UTC'))""",
        tenant.organizationId(), tenant.projectId(), cxId);
    String customer = queryString(connect(phase5aUrl),
        "select id::text from public.customers where \"cxId\" = '" + cxId + "'");

    // --- the insert that was previously IMPOSSIBLE.
    String request = queryStringReturningId(connect(phase5aUrl), """
        insert into public.privacy_deletion_requests
             (id, "organizationId", "projectId", "subjectCustomerId", "subjectType", status,
              reason, "requestedAt", "requestedByUserId", "createdAt", "updatedAt")
        values (gen_random_uuid(), ?, ?, ?, 'CUSTOMER',
                'REQUESTED'::"public"."PrivacyDeletionRequestStatus",
                'customer asked to be forgotten', (now() at time zone 'UTC'), ?,
                (now() at time zone 'UTC'), (now() at time zone 'UTC'))
        returning id::text""", tenant.organizationId(), tenant.projectId(),
        UUID.fromString(customer), requester);

    try (Connection c = connect(phase5aUrl)) {
      assertThat(queryInt(c, """
          select count(*) from public.privacy_deletion_requests
           where id = ? and "subjectCustomerId" is not null and "subjectUserId" is null""",
          UUID.fromString(request)))
          .as("FR-7: a deletion for a customer who has no `users` row can now be requested, and "
              + "it names the CUSTOMER rather than borrowing a user id")
          .isEqualTo(1);
      assertThat(queryInt(c, """
          select count(*) from pg_attribute
           where attrelid = 'public.privacy_deletion_requests'::regclass
             and attname = 'subjectUserId' and attnotnull"""))
          .as("subjectUserId is NULLABLE. It was NOT NULL, which is the defect: NOT NULL plus a "
              + "composite FK to users means the commonest privacy request had no representation.")
          .isZero();
      assertThat(queryInt(c, """
          select count(*) from pg_constraint
           where conrelid = 'public.privacy_deletion_requests'::regclass
             and conname = 'privacy_deletion_requests_subject_tenant_fkey'
             and pg_get_constraintdef(oid) like '%FOREIGN KEY ("subjectUserId", "organizationId")%'"""))
          .as("the PRE-EXISTING composite users FK is still there and still composite. Relaxing "
              + "the NOT NULL must not have taken the cross-tenant refusal with it: if a user id is "
              + "named it is still a user of THIS tenant.")
          .isEqualTo(1);
      assertThat(queryInt(c, """
          select count(*) from pg_constraint
           where conrelid = 'public.privacy_deletion_requests'::regclass
             and conname = 'privacy_deletion_requests_subject_customer_tenant_fkey'
             and pg_get_constraintdef(oid) like '%FOREIGN KEY ("subjectCustomerId", "organizationId")%'
             and confdeltype = 'r'"""))
          .as("...and the new customer-subject FK is composite AND ON DELETE RESTRICT: a customer "
              + "row must not be deletable out from under an open request about that customer")
          .isEqualTo(1);
    }

    // --- the state machine is untouched. REQUESTED, then verified, then rejected
    //     with a reason -- the three transitions an operator actually makes.
    UUID verifier = seedUser(tenant.organizationId(), "privacy-verifier");
    execute(phase5aUrl, """
        update public.privacy_deletion_requests
           set status = 'VERIFIED'::"public"."PrivacyDeletionRequestStatus",
               "verifiedAt" = (now() at time zone 'UTC'),
               "verifiedByUserId" = ?,
               "updatedAt" = (now() at time zone 'UTC')
         where id = ?""", verifier, UUID.fromString(request));
    try (Connection c = connect(phase5aUrl)) {
      assertThat(queryString(c, "select \"verifiedByUserId\"::text from public.privacy_deletion_requests"
              + " where id = '" + request + "'"))
          .as("the verification pair survives: a verified request still says who verified it")
          .isEqualTo(verifier.toString());
    }

    assertThatThrownBy(() -> execute(phase5aUrl, """
        update public.privacy_deletion_requests
           set status = 'REJECTED'::"public"."PrivacyDeletionRequestStatus",
               "updatedAt" = (now() at time zone 'UTC')
         where id = ?""", UUID.fromString(request)))
        .as("a rejection without a reason is not a decision anybody can act on -- and widening the "
            + "SUBJECT must not have relaxed the DECISION rules")
        .hasMessageContaining("privacy_deletion_requests_rejection_check");

    // --- the negatives that keep the new check tight.
    assertThatThrownBy(() -> execute(phase5aUrl, """
        insert into public.privacy_deletion_requests
             (id, "organizationId", "subjectCustomerId", "subjectUserId", "subjectType", status,
              reason, "requestedAt", "createdAt", "updatedAt")
        values (gen_random_uuid(), ?, ?, ?, 'CUSTOMER',
                'REQUESTED'::"public"."PrivacyDeletionRequestStatus", 'both subjects named',
                (now() at time zone 'UTC'), (now() at time zone 'UTC'),
                (now() at time zone 'UTC'))""",
        tenant.organizationId(), UUID.fromString(customer), verifier))
        .as("the two references are never both set: a row that names a customer AND a user cannot "
            + "say which one the deletion is about")
        .hasMessageContaining("privacy_deletion_requests_subject_reference_check");

    // --- a TENANT subject names NEITHER reference, and that is a LEGAL row, not a
    //     refusal. Asserted positively because the obvious mistake here is to assume
    //     "exactly one subject reference" and write a CHECK that makes a whole-tenant
    //     deletion inexpressible -- which is the defect one column narrower.
    String tenantRequest = queryStringReturningId(connect(phase5aUrl), """
        insert into public.privacy_deletion_requests
             (id, "organizationId", "subjectType", status, reason, "requestedAt",
              "createdAt", "updatedAt")
        values (gen_random_uuid(), ?, 'TENANT',
                'REQUESTED'::"public"."PrivacyDeletionRequestStatus",
                'whole tenant offboarding',
                (now() at time zone 'UTC'), (now() at time zone 'UTC'),
                (now() at time zone 'UTC'))
        returning id::text""", tenant.organizationId());
    assertThat(countOn(phase5aUrl, """
        select count(*) from public.privacy_deletion_requests
         where id = ? and "subjectUserId" is null and "subjectCustomerId" is null""",
        UUID.fromString(tenantRequest)))
        .as("FR-7 needs to express deleting a TENANT's data too, and the tenant IS "
            + "organizationId -- so a TENANT subject names neither reference and the row is legal")
        .isEqualTo(1);

    // --- the LEGACY arm: a CUSTOMER request that names a users row and no customer IS
    //     still admitted, and it must be. Asserted positively and labelled, because the
    //     tempting assertion is that a new write must not be able to do this -- and if that
    //     were enforced here, Phase 7's PrivacyDeletionService, which writes exactly this
    //     shape, would start failing the moment this migration lands. The arm exists so
    //     rows written BEFORE the batch keep satisfying a constraint; removing it is
    //     follow-up F-3, whose precondition is that the legacy count is zero.
    String legacy = queryStringReturningId(connect(phase5aUrl), """
        insert into public.privacy_deletion_requests
             (id, "organizationId", "subjectUserId", "subjectType", status, reason,
              "requestedAt", "createdAt", "updatedAt")
        values (gen_random_uuid(), ?, ?, 'CUSTOMER',
                'REQUESTED'::"public"."PrivacyDeletionRequestStatus",
                'legacy shape: customer subject naming a user',
                (now() at time zone 'UTC'), (now() at time zone 'UTC'),
                (now() at time zone 'UTC'))
        returning id::text""", tenant.organizationId(), verifier);
    assertThat(legacy)
        .as("the LEGACY arm is deliberately still admissible. It is a documented, temporary "
            + "weakening that keeps Phase 7's existing CUSTOMER write path and every "
            + "pre-existing row valid; follow-up F-3 removes it once V15 ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â§7b reports its count "
            + "as zero.")
        .isNotBlank();

    // --- and the cross-tenant negative, on the customer side.
    Tenant other = seedTenant("privacyother");
    String otherCustomer;
    {
      String otherCx = "CX" + String.format("%06d", CONVERSATION_SEQ.incrementAndGet());
      execute(phase5aUrl, """
          insert into public.customers (id, "organizationId", "projectId", "cxId", "displayName",
                                        "createdAt", "updatedAt")
          values (gen_random_uuid(), ?, ?, ?, 'Other tenant customer',
                  (now() at time zone 'UTC'), (now() at time zone 'UTC'))""",
          other.organizationId(), other.projectId(), otherCx);
      otherCustomer = queryString(connect(phase5aUrl),
          "select id::text from public.customers where \"cxId\" = '" + otherCx + "'");
    }
    assertThatThrownBy(() -> execute(phase5aUrl, """
        insert into public.privacy_deletion_requests
             (id, "organizationId", "subjectCustomerId", "subjectType", status, reason,
              "requestedAt", "createdAt", "updatedAt")
        values (gen_random_uuid(), ?, ?, 'CUSTOMER',
                'REQUESTED'::"public"."PrivacyDeletionRequestStatus",
                'another tenant''s customer',
                (now() at time zone 'UTC'), (now() at time zone 'UTC'),
                (now() at time zone 'UTC'))""",
        tenant.organizationId(), UUID.fromString(otherCustomer)))
        .as("a deletion request in tenant A may not name tenant B's customer")
        .hasMessageContaining("privacy_deletion_requests_subject_customer_tenant_fkey");
  }

  // ------------------------------------------------------------------
  // helpers
  // ------------------------------------------------------------------

  private static Flyway flyway(String jdbcUrl) {
    return Flyway.configure()
        .dataSource(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("filesystem:" + sqlDir)
        .baselineOnMigrate(false)
        .load();
  }

  /**
   * Creates a second, empty database in the same container and returns its JDBC
   * URL. Used to emulate the legacy runtime's database alongside a clean one.
   */
  private static String createSiblingDatabase(String name) throws SQLException {
    try (Connection c = connect(POSTGRES.getJdbcUrl());
         Statement s = c.createStatement()) {
      s.executeUpdate("CREATE DATABASE \"" + name + "\"");
    }
    String base = POSTGRES.getJdbcUrl();
    assertThat(base).contains("/" + POSTGRES.getDatabaseName());
    return base.replace("/" + POSTGRES.getDatabaseName(), "/" + name);
  }

  private static Connection connect(String jdbcUrl) throws SQLException {
    return DriverManager.getConnection(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static String enumQuery(String enumName) {
    return "select e.enumlabel from pg_enum e"
        + " join pg_type t on t.oid = e.enumtypid"
        + " where t.typname = '" + enumName + "' order by e.enumsortorder";
  }

  /**
   * Seeds a conversation, because every Phase-6 run binds to one and the
   * composite tenant foreign key is only meaningful against a real row.
   *
   * <p>The business keys are numbered from a counter rather than derived from the
   * caller's tag: {@code customers.cxId} is {@code varchar(9)} and
   * {@code conversations.ticketNumber} is {@code varchar(14)} in the verified
   * schema, so a descriptive tag like {@code "collision-race"} overflows the
   * column. Deriving short keys from a counter keeps the test independent of
   * execution order and of how long a scenario's tag is.
   */
  private static final java.util.concurrent.atomic.AtomicInteger CONVERSATION_SEQ =
      new java.util.concurrent.atomic.AtomicInteger();

  private static UUID seedConversation(UUID org, UUID project, String label) throws SQLException {
    String n = String.format("%06d", CONVERSATION_SEQ.incrementAndGet());
    String cxId = "CX" + n;            // 8 chars, fits varchar(9)
    String ticket = "T-" + n;          // 8 chars, fits varchar(14)
    try (Connection c = connect(phase5aUrl);
         Statement s = c.createStatement()) {
      s.executeUpdate("""
          insert into customers (id, "organizationId", "projectId", "cxId", "displayName",
                                "createdAt", "updatedAt")
          values (gen_random_uuid(), '%s', '%s', '%s', 'Fixture Customer %s',
                  current_timestamp, current_timestamp)""".formatted(org, project, cxId, n));
      UUID customer = queryUuid(phase5aUrl,
          "select id from customers where \"cxId\" = '" + cxId + "'");
      s.executeUpdate("""
          insert into conversations (id, "organizationId", "projectId", "customerId",
                                    "ticketNumber", "channel", "status", "createdAt", "updatedAt")
          values (gen_random_uuid(), '%s', '%s', '%s', '%s', 'MOCK'::"ChannelType",
                  'OPEN'::"ConversationStatus", current_timestamp, current_timestamp)"""
          .formatted(org, project, customer, ticket));
    }
    assertThat(label).as("conversation label is documentation only, not a stored key")
        .isNotBlank();
    return queryUuid(phase5aUrl,
        "select id from conversations where \"ticketNumber\" = '" + ticket + "'");
  }

  /**
   * A complete, internally-consistent tenant: organisation, project,
   * conversation, definition, and an ACTIVE version. Used wherever a test needs a
   * SECOND tenant that is valid in its own right, so that a negative test can
   * change exactly one field and be certain the refusal is caused by that field.
   */
  private record Active(Tenant tenant, String definitionId, String versionId,
                        UUID conversationId) {
  }

  private static Active seedActiveWorkflow(String tag) throws SQLException {
    Tenant tenant = seedTenant(tag);
    UUID principal = seedUser(tenant.organizationId(), tag + "-principal");
    UUID conversation = seedConversation(tenant.organizationId(), tenant.projectId(), tag);
    String definitionId;
    String versionId;
    try (Connection c = connect(phase5aUrl)) {
      definitionId = seedWorkflowDefinition(tenant.organizationId(), tag, "Workflow " + tag, 10);
      versionId = insertDraftVersion(c, tenant.organizationId(), definitionId, 1, principal);
      retireActiveVersion(c, tenant.organizationId(), definitionId, versionId);
      activateVersion(c, tenant.organizationId(), definitionId, versionId, principal);
    }
    return new Active(tenant, definitionId, versionId, conversation);
  }

  /**
   * A workflow definition. projectId is NULL: a workflow is organisation-wide by
   * design, which is the nullable case V11 section 4d lists.
   */
  private static String seedWorkflowDefinition(UUID org, String key, String name, int priority)
      throws SQLException {
    try (Connection c = connect(phase5aUrl);
         Statement s = c.createStatement()) {
      s.executeUpdate("""
          insert into workflow_definitions (id, "organizationId", "projectId", "key", "name",
                                            "triggerType", "priority", "createdAt", "updatedAt")
          values (gen_random_uuid(), '%s', null, '%s', '%s', 'CONVERSATION_OPENED', %d,
                  (now() at time zone 'UTC'), (now() at time zone 'UTC'))"""
          .formatted(org, key, name, priority));
    }
    return queryString(connect(phase5aUrl),
        "select id::text from workflow_definitions where \"key\" = '" + key + "'");
  }

  private static String insertDraftVersion(Connection c, UUID org, String definitionId,
                                           int versionNumber, UUID author) throws SQLException {
    return insertDraftVersionWithNumber(c, org, definitionId, author, versionNumber);
  }

  /** @param versionNumber deliberately passed through so a negative test can pass 0. */
  private static String insertDraftVersionWithNumber(Connection c, UUID org,
                                                     String definitionId, UUID author,
                                                     int versionNumber) throws SQLException {
    return queryStringReturningId(c, """
        insert into public.workflow_versions
               (id, "organizationId", "projectId", "workflowDefinitionId", "versionNumber",
                "state", "changeNote", "stateMachine", "capabilityScope", "contentHash",
                "createdByUserId", "createdAt", "updatedAt")
        values (gen_random_uuid(), ?, null, ?, ?, 'DRAFT', 'initial',
                '{"nodes":[]}'::jsonb, '["conversation.read"]'::jsonb, 'sha256:draft',
                ?, (now() at time zone 'UTC'), (now() at time zone 'UTC'))
        returning id::text""",
        org, UUID.fromString(definitionId), Integer.valueOf(versionNumber), author);
  }

  /**
   * Step 1 of the activation from ops/flyway/reference/workflow-sla-statements.sql
   * section 1: retire the incumbent, naming its successor.
   */
  private static int retireActiveVersion(Connection c, UUID org, String definitionId,
                                         String successorVersionId) throws SQLException {
    return update(c, """
        update public.workflow_versions
           set "state" = 'SUPERSEDED',
               "supersededAt" = (now() at time zone 'UTC'),
               "supersededByVersionId" = ?,
               "updatedAt" = (now() at time zone 'UTC')
         where "organizationId" = ?
           and "workflowDefinitionId" = ?
           and "state" = 'ACTIVE'::"public"."WorkflowVersionState\"""",
        UUID.fromString(successorVersionId), org, UUID.fromString(definitionId));
  }

  /**
   * Steps 2 and 3 of the activation: mark ACTIVE, then move the definition's
   * pointer. Both carry organizationId and the expected state, so a concurrent
   * activation loses on the affected-row count rather than overwriting this one.
   */
  private static int activateVersion(Connection c, UUID org, String definitionId,
                                     String versionId, UUID actor) throws SQLException {
    int activated = update(c, """
        update public.workflow_versions
           set "state" = 'ACTIVE',
               "activatedAt" = (now() at time zone 'UTC'),
               "activatedByUserId" = ?,
               "updatedAt" = (now() at time zone 'UTC')
         where id = ?
           and "organizationId" = ?
           and "state" in ('DRAFT'::"public"."WorkflowVersionState",
                           'SUPERSEDED'::"public"."WorkflowVersionState")""",
        actor, UUID.fromString(versionId), org);
    if (activated != 1) {
      return activated;
    }
    update(c, """
        update public.workflow_definitions
           set "activeVersionId" = ?,
               "latestVersionNumber" = greatest("latestVersionNumber", ?),
               "updatedAt" = (now() at time zone 'UTC')
         where id = ? and "organizationId" = ?""",
        UUID.fromString(versionId), versionNumberOf(c, versionId),
        UUID.fromString(definitionId), org);
    return activated;
  }

  private static int versionNumberOf(Connection c, String versionId) throws SQLException {
    return queryInt(c, "select \"versionNumber\" from workflow_versions where id = '"
        + versionId + "'");
  }

  /**
   * Starts a run. The ON CONFLICT DO NOTHING on the tenant-scoped trigger
   * instance is the loop-prevention contract: a re-fired trigger inserts nothing
   * and the caller learns it by getting zero rows back.
   *
   */
  private static String startRun(Connection c, UUID org, UUID project, String definitionId,
                                 String versionId, UUID conversationId, UUID principal,
                                 String triggerInstanceId) throws SQLException {
    List<String> rows = ids(c, """
        insert into public.workflow_runs
               (id, "organizationId", "projectId", "workflowDefinitionId", "workflowVersionId",
                "conversationId", "triggerInstanceId", "status", "principalUserId",
                "capabilityScope", "startedAt", "createdAt", "updatedAt")
        values (gen_random_uuid(), ?, ?, ?, ?, ?, ?, 'RUNNING', ?,
                '["conversation.read"]'::jsonb, (now() at time zone 'UTC'),
                (now() at time zone 'UTC'), (now() at time zone 'UTC'))
        on conflict ("organizationId", "triggerInstanceId") do nothing
        returning id::text""",
        org, project, UUID.fromString(definitionId), UUID.fromString(versionId),
        conversationId, triggerInstanceId, principal);
    // null, not an empty list: the caller distinguishes "no run started" from a
    // run id, and AssertJ's isEmpty() on a null String fails with a message that
    // reads like a missing row rather than the loop-prevention no-op it is.
    return rows.isEmpty() ? null : rows.get(0);
  }

  private static String recordAction(Connection c, UUID org, UUID project, String runId,
                                     String actionKey, String actionType, int attemptNumber,
                                     String outcome, UUID actorUserId, String errorCode,
                                     String errorMessage, Integer durationMs)
      throws SQLException {
    return queryStringReturningId(c, """
        insert into public.workflow_run_actions
               (id, "organizationId", "projectId", "workflowRunId", "actionKey", "actionType",
                "attemptNumber", "outcome", "inputSnapshot", "resultSnapshot", "actorUserId",
                "startedAt", "finishedAt", "durationMs", "errorCode", "errorMessage", "createdAt")
        values (gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?::"public"."WorkflowActionOutcome",
                '{"candidateId":"emp-7","load":3}'::jsonb,
                '{"assigned":"true"}'::jsonb, ?,
                (now() at time zone 'UTC'), (now() at time zone 'UTC'), ?, ?, ?, (now() at time zone 'UTC'))
        returning id::text""",
        org, project, UUID.fromString(runId), actionKey, actionType,
        Integer.valueOf(attemptNumber), outcome, actorUserId, durationMs,
        errorCode, errorMessage);
  }

  /**
   * An action with neither a finish time nor a duration -- the state a scheduler
   * row is in while it is still running. Needed as its own row: mutating a
   * finished action cannot exercise the finished/duration pair.
   */
  private static String recordInFlightAction(Connection c, UUID org, UUID project, String runId,
                                             String actionKey, String actionType,
                                             int attemptNumber, UUID actorUserId)
      throws SQLException {
    return queryStringReturningId(c, """
        insert into public.workflow_run_actions
               (id, "organizationId", "projectId", "workflowRunId", "actionKey", "actionType",
                "attemptNumber", "outcome", "inputSnapshot", "resultSnapshot", "actorUserId",
                "startedAt", "createdAt", "errorCode", "errorMessage")
        values (gen_random_uuid(), ?, ?, ?, ?, ?, ?, 'RETRYABLE'::"public"."WorkflowActionOutcome",
                '{}'::jsonb, '{}'::jsonb, ?, (now() at time zone 'UTC'), (now() at time zone 'UTC'),
                'UPSTREAM_BUSY', 'upstream is retrying')
        returning id::text""",
        org, project, UUID.fromString(runId), actionKey, actionType,
        Integer.valueOf(attemptNumber), actorUserId);
  }

  private static int requestAbort(Connection c, UUID org, String runId, UUID human, String reason)
      throws SQLException {
    return update(c, """
        update public.workflow_runs
           set "abortRequestedAt" = (now() at time zone 'UTC'),
               "abortedByUserId" = ?,
               "abortReason" = ?,
               "updatedAt" = (now() at time zone 'UTC')
         where "organizationId" = ?
           and id = ?
           and "status" = 'RUNNING'::"public"."WorkflowRunStatus"
           and "abortRequestedAt" is null""",
        human, reason, org, UUID.fromString(runId));
  }

  private static String insertCalendar(Connection c, UUID org, String key, String name,
                                       String timezone, String effectiveFrom, int version)
      throws SQLException {
    return insertCalendar(c, org, key, name, timezone, effectiveFrom, version, "DRAFT");
  }

  private static String insertCalendar(Connection c, UUID org, String key, String name,
                                       String timezone, String effectiveFrom, int version,
                                       String state) throws SQLException {
    return queryStringReturningId(c, """
        insert into public.sla_calendars
               (id, "organizationId", "projectId", "key", "name", "version", "state",
                "timezone", "effectiveFrom", "createdAt", "updatedAt")
        values (gen_random_uuid(), ?, null, ?, ?, ?, ?::"public"."SlaCalendarState",
                ?, ?::date, (now() at time zone 'UTC'), (now() at time zone 'UTC'))
        returning id::text""",
        org, key, name, Integer.valueOf(version), state, timezone, effectiveFrom);
  }

  /**
   * Publishes a DRAFT calendar as ACTIVE, retiring the incumbent first -- the same
   * two-step ordering as the workflow activation, because the partial unique index
   * sla_calendars_one_active_per_key refuses two live rows for one key and the
   * single UPDATE that would do both is a constraint violation, not a swap.
   */
  private static void activateCalendar(Connection c, UUID org, String key, String newCalendarId)
      throws SQLException {
    int retired = update(c, """
        update public.sla_calendars
           set "state" = 'SUPERSEDED',
               "supersededById" = ?,
               "updatedAt" = (now() at time zone 'UTC')
         where "organizationId" = ? and "key" = ?
           and "state" = 'ACTIVE'::"public"."SlaCalendarState\"""",
        UUID.fromString(newCalendarId), org, key);
    assertThat(retired).as("exactly one incumbent calendar was retired (0 on the first "
        + "activation, 1 thereafter)").isBetween(0, 1);
    assertThat(update(c, """
        update public.sla_calendars
           set "state" = 'ACTIVE', "updatedAt" = (now() at time zone 'UTC')
         where id = ? and "organizationId" = ? and "state" = 'DRAFT'::"public"."SlaCalendarState\"""",
        UUID.fromString(newCalendarId), org)).isEqualTo(1);
  }

  private static void insertPolicy(Connection c, UUID org, UUID project, String key,
                                    Integer firstResponse, Integer nextResponse,
                                    Integer resolution, UUID calendarId) throws SQLException {
    execute(c, """
        insert into public.sla_policies
               (id, "organizationId", "projectId", "key", "name", "priority", "clockType",
                "firstResponseMinutes", "nextResponseMinutes", "resolutionMinutes",
                "calendarId", "createdAt", "updatedAt")
        values (gen_random_uuid(), ?, ?, ?, ?, 100, 'RESOLUTION'::"public"."SlaClockType",
                ?, ?, ?, ?, (now() at time zone 'UTC'), (now() at time zone 'UTC'))""",
        org, project, key, key, firstResponse, nextResponse, resolution, calendarId);
  }

  /**
   * Records a collision. {@code abortedRunId} is nullable here so a negative test
   * can omit it deliberately: collision_records_aborted_run_check is a
   * BICONDITIONAL, so an ABORTED_BY_HUMAN row without a run is refused -- an
   * unattributable abort, which is the one thing this table exists to prevent,
   * because nothing else records that a race happened at all.
   */
  private static int insertCollision(Connection c, UUID org, UUID project, UUID conversationId,
                                     String counterpartyId, String counterpartyType,
                                     long window, String outcome, String detail)
      throws SQLException {
    return insertCollision(c, org, project, conversationId, counterpartyId, counterpartyType,
        window, outcome, detail, UUID.randomUUID());
  }

  private static int insertCollision(Connection c, UUID org, UUID project, UUID conversationId,
                                     String counterpartyId, String counterpartyType,
                                     long window, String outcome, String detail,
                                     UUID abortedRunId) throws SQLException {
    return update(c, """
        insert into public.collision_records
               (id, "organizationId", "projectId", "scope", "resourceType", "resourceId",
                "conversationId", "counterpartyId", "counterpartyType", "collisionWindow",
                "outcome", "resolutionDetail", "abortedRunId", "observedAt", "createdAt")
        values (gen_random_uuid(),                       -- id
                ?,                                -- organizationId
                ?,                                -- projectId
                'CONVERSATION'::"public"."CollisionScope",
                'Conversation',                   -- resourceType
                ?,                                -- resourceId
                ?,                                -- conversationId
                ?,                                -- counterpartyId
                ?,                                -- counterpartyType
                ?::bigint,                        -- collisionWindow
                ?::"public"."CollisionResolution",-- outcome
                ?,                                -- resolutionDetail
                ?,                                -- abortedRunId
                (now() at time zone 'UTC'),
                (now() at time zone 'UTC'))
        on conflict do nothing""",
        org, project, conversationId, conversationId, counterpartyId, counterpartyType,
        Long.valueOf(window), outcome, detail,
        // The biconditional: name a run only for an ABORTED_BY_HUMAN outcome, and
        // name it for EVERY such outcome.
        "ABORTED_BY_HUMAN".equals(outcome) ? abortedRunId : null);
  }

  // ------------------------------------------------------------------
  // Phase-7-10a helpers
  //
  // Each one is a THIN wrapper over the same INSERT the production code would
  // issue, with exactly one field varied so a negative test can point at the
  // CHECK constraint by name. Several take the offending value as a parameter
  // precisely so the "legal" and "illegal" shapes differ in ONE place -- an
  // earlier version of the Phase-6a helpers copied the whole INSERT and a
  // negative test then proved nothing about the constraint it named.
  // ------------------------------------------------------------------

  /** Renders a list as a quoted SQL literal list, for an IN (...) clause. */
  private static String quotedCsv(List<String> names) {
    return names.stream().map(n -> "'" + n + "'").reduce((a, b) -> a + "," + b).orElse("");
  }

  // --- audit / legal hold / privacy ---------------------------------------

  private static String insertLegalHold(Connection c, UUID org, String scopeType, String reason)
      throws SQLException {
    return queryStringReturningId(c, """
        insert into public.legal_holds
             (id, "organizationId", "scopeType", reason, status, "placedAt", "placedByUserId",
              "createdAt", "updatedAt")
        select gen_random_uuid(), ?, ?::"public"."LegalHoldScopeType", ?, 'ACTIVE',
               (now() at time zone 'UTC'),
               (select id from users where "organizationId" = ? order by id limit 1),
               (now() at time zone 'UTC'), (now() at time zone 'UTC')
        returning id::text""", org, scopeType, reason, org);
  }

  private static String insertAdminAuditEvent(Connection c, UUID org, UUID project,
                                              UUID actorUserId, String action)
      throws SQLException {
    return queryStringReturningId(c, """
        insert into public.admin_audit_events
             (id, "organizationId", "projectId", "actorUserId", "principalType",
              "principalName", action, "resourceType", outcome, "retentionClass",
              "occurredAt", "createdAt")
        values (gen_random_uuid(), ?, ?, ?, 'USER'::"public"."AuditPrincipalType",
                'undo@example.test', ?, 'Fixture', 'SUCCESS'::"public"."AuditOutcome",
                'AUDIT_STANDARD', (now() at time zone 'UTC'), (now() at time zone 'UTC'))
        returning id::text""", org, project, actorUserId, action);
  }

  private static String insertSecurityEvent(Connection c, UUID org, UUID project,
                                            UUID actorUserId, String eventType,
                                            String outcome, String denyReason)
      throws SQLException {
    return queryStringReturningId(c, """
        insert into public.security_events
             (id, "organizationId", "projectId", "actorUserId", "principalType",
              "principalName", "eventType", outcome, "denyReason", "retentionClass",
              "occurredAt", "createdAt")
        values (gen_random_uuid(), ?, ?, ?, 'USER'::"public"."AuditPrincipalType",
                'undo@example.test', ?, ?::"public"."AuditOutcome", ?,
                'SECURITY_STANDARD', (now() at time zone 'UTC'), (now() at time zone 'UTC'))
        returning id::text""", org, project, actorUserId, eventType, outcome, denyReason);
  }

  private static String insertJobIntegrationEvent(Connection c, UUID org, UUID project,
                                                  String component, String eventType,
                                                  String outcome, String failureCode)
      throws SQLException {
    return queryStringReturningId(c, """
        insert into public.job_integration_events
             (id, "organizationId", "projectId", component, "componentType", "eventType",
              outcome, "queueName", "jobId", "failureCode", "retentionClass",
              "occurredAt", "createdAt")
        values (gen_random_uuid(), ?, ?, ?, 'JOB'::"public"."AuditPrincipalType", ?,
                ?::"public"."AuditOutcome", 'outbound', 'undo-job-1', ?,
                'INTEGRATION_STANDARD', (now() at time zone 'UTC'), (now() at time zone 'UTC'))
        returning id::text""", org, project, component, eventType, outcome, failureCode);
  }

  private static String insertPrivacyDeletionRequest(Connection c, UUID org, UUID subjectUserId,
                                                     String reason) throws SQLException {
    return queryStringReturningId(c, """
        insert into public.privacy_deletion_requests
             (id, "organizationId", "subjectUserId", "subjectType", status, reason,
              "requestedAt", "evidenceReference", "createdAt", "updatedAt")
        values (gen_random_uuid(), ?, ?, 'USER', 'REQUESTED'::"public"."PrivacyDeletionRequestStatus",
                ?, (now() at time zone 'UTC'), 'undo-evidence',
                (now() at time zone 'UTC'), (now() at time zone 'UTC'))
        returning id::text""", org, subjectUserId, reason);
  }

  // --- report catalogue ---------------------------------------------------

  private static String seedReportDefinition(Connection c, UUID org, String key)
      throws SQLException {
    return queryStringReturningId(c, """
        insert into public.report_definitions
             (id, "organizationId", "key", "nameAr", "nameEn", "timezone",
              "redactionPolicy", "retentionClass", "createdAt", "updatedAt")
        values (gen_random_uuid(), ?, ?, '????? ??????', 'undo rehearsal report',
                'Asia/Riyadh', 'undo-policy', 'REPORTS_STANDARD',
                (now() at time zone 'UTC'), (now() at time zone 'UTC'))
        returning id::text""", org, key);
  }

  private static String seedReportVersion(Connection c, UUID org, String definitionId,
                                          UUID author, int versionNumber) throws SQLException {
    return queryStringReturningId(c, """
        insert into public.report_versions
             (id, "organizationId", "reportDefinitionId", "versionNumber", dimensions,
              measures, filters, "defaultSort", columns, "createdByUserId", "createdAt")
        values (gen_random_uuid(), ?, ?, ?, '["day"]'::jsonb,
                '["conversations.count"]'::jsonb, '{}'::jsonb,
                '[{"field":"day","direction":"asc"}]'::jsonb,
                '["day","count"]'::jsonb, ?, (now() at time zone 'UTC'))
        returning id::text""", org, UUID.fromString(definitionId),
        Integer.valueOf(versionNumber), author);
  }

  private static int publishReportVersion(Connection c, UUID org, String definitionId,
                                          String versionId, UUID actor) throws SQLException {
    return update(c, """
        update public.report_versions
           set state = 'PUBLISHED'::"public"."ReportStatus",
               "publishedAt" = (now() at time zone 'UTC'),
               "publishedByUserId" = ?
         where id = ? and "organizationId" = ?
           and state = 'DRAFT'::"public"."ReportStatus\"""",
        actor, UUID.fromString(versionId), org);
  }

  private static int retireReportVersion(Connection c, UUID org, String versionId,
                                         String supersededBy) throws SQLException {
    return update(c, """
        update public.report_versions
           set state = 'RETIRED'::"public"."ReportStatus",
               "retiredAt" = (now() at time zone 'UTC'),
               "supersededByVersionId" = ?
         where id = ? and "organizationId" = ?
           and state = 'PUBLISHED'::"public"."ReportStatus\"""",
        supersededBy == null ? null : UUID.fromString(supersededBy),
        UUID.fromString(versionId), org);
  }

  // --- export jobs ---------------------------------------------------------

  private static String insertExportJob(Connection c, UUID org, UUID project, UUID requester,
                                        String reportDefinitionId, Integer reportVersion)
      throws SQLException {
    return queryStringReturningId(c, """
        insert into public.export_jobs
             (id, "organizationId", "projectId", "requestedByUserId", "reportDefinitionId",
              "reportVersion", format, status, "redactionPolicy", "retentionClass",
              "authorizationSnapshot", "expiresAt", "requestedAt", "createdAt", "updatedAt")
        values (gen_random_uuid(), ?, ?, ?, ?, ?, 'CSV'::"public"."ExportFormat",
                'QUEUED'::"public"."ExportJobStatus", 'undo-policy', 'EXPORTS_STANDARD',
                '{"undo":{"role":"EMPLOYEE"}}'::jsonb,
                (now() at time zone 'UTC') + interval '7 days',
                (now() at time zone 'UTC'),
                (now() at time zone 'UTC'), (now() at time zone 'UTC'))
        returning id::text""", org, project, requester,
        reportDefinitionId == null ? null : UUID.fromString(reportDefinitionId), reportVersion);
  }

  /** The report-pin biconditional, negative half: a definition with NO version. */
  private static String insertExportJobWithVersion(Connection c, UUID org, UUID project,
                                                   UUID requester, String reportDefinitionId,
                                                   Integer reportVersion) throws SQLException {
    return insertExportJob(c, org, project, requester, reportDefinitionId, reportVersion);
  }

  private static String insertExportJobWithSnapshot(Connection c, UUID org, UUID project,
                                                    UUID requester, String reportDefinitionId,
                                                    Integer reportVersion, String snapshot)
      throws SQLException {
    return queryStringReturningId(c, """
        insert into public.export_jobs
             (id, "organizationId", "projectId", "requestedByUserId", "reportDefinitionId",
              "reportVersion", format, status, "redactionPolicy", "retentionClass",
              "authorizationSnapshot", "expiresAt", "requestedAt", "createdAt", "updatedAt")
        values (gen_random_uuid(), ?, ?, ?, ?, ?, 'CSV'::"public"."ExportFormat",
                'QUEUED'::"public"."ExportJobStatus", 'undo-policy', 'EXPORTS_STANDARD',
                ?::jsonb, (now() at time zone 'UTC') + interval '7 days',
                (now() at time zone 'UTC'),
                (now() at time zone 'UTC'), (now() at time zone 'UTC'))
        returning id::text""", org, project, requester, UUID.fromString(reportDefinitionId),
        reportVersion, snapshot);
  }

  private static String insertExportJobWithChecksum(Connection c, UUID org, UUID project,
                                                    UUID requester, String reportDefinitionId,
                                                    Integer reportVersion, String checksum)
      throws SQLException {
    return queryStringReturningId(c, """
        insert into public.export_jobs
             (id, "organizationId", "projectId", "requestedByUserId", "reportDefinitionId",
              "reportVersion", format, status, "redactionPolicy", "retentionClass",
              "authorizationSnapshot", checksum, "rowCount", "storageKey", "progressPct",
              "startedAt", "finishedAt", "expiresAt", "requestedAt", "createdAt", "updatedAt")
        values (gen_random_uuid(), ?, ?, ?, ?, ?, 'CSV'::"public"."ExportFormat",
                'COMPLETED'::"public"."ExportJobStatus", 'undo-policy', 'EXPORTS_STANDARD',
                '{"undo":{}}'::jsonb, ?, 0, 'undo/objects/x.csv', 100,
                (now() at time zone 'UTC'), (now() at time zone 'UTC'),
                (now() at time zone 'UTC') + interval '7 days',
                (now() at time zone 'UTC'),
                (now() at time zone 'UTC'), (now() at time zone 'UTC'))
        returning id::text""", org, project, requester, UUID.fromString(reportDefinitionId),
        reportVersion, checksum);
  }

  private static String insertExportJobWithStorageKey(Connection c, UUID org, UUID project,
                                                      UUID requester, String reportDefinitionId,
                                                      Integer reportVersion, String storageKey)
      throws SQLException {
    return queryStringReturningId(c, """
        insert into public.export_jobs
             (id, "organizationId", "projectId", "requestedByUserId", "reportDefinitionId",
              "reportVersion", format, status, "redactionPolicy", "retentionClass",
              "authorizationSnapshot", checksum, "rowCount", "storageKey", "progressPct",
              "startedAt", "finishedAt", "expiresAt", "requestedAt", "createdAt", "updatedAt")
        values (gen_random_uuid(), ?, ?, ?, ?, ?, 'CSV'::"public"."ExportFormat",
                'COMPLETED'::"public"."ExportJobStatus", 'undo-policy', 'EXPORTS_STANDARD',
                '{"undo":{}}'::jsonb,
                'sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
                0, ?, 100,
                (now() at time zone 'UTC'), (now() at time zone 'UTC'),
                (now() at time zone 'UTC') + interval '7 days',
                (now() at time zone 'UTC'),
                (now() at time zone 'UTC'), (now() at time zone 'UTC'))
        returning id::text""", org, project, requester, UUID.fromString(reportDefinitionId),
        reportVersion, storageKey);
  }

  private static String insertExportJobWithRequester(Connection c, UUID org, UUID project,
                                                     UUID requester, String reportDefinitionId,
                                                     Integer reportVersion) throws SQLException {
    return insertExportJob(c, org, project, requester, reportDefinitionId, reportVersion);
  }

  /** QUEUED -> RUNNING. Carries the tenant and the expected state, so an illegal
   *  transition loses on the affected-row count. */
  private static int startExport(Connection c, UUID org, String jobId) throws SQLException {
    return update(c, """
        update public.export_jobs
           set status = 'RUNNING'::"public"."ExportJobStatus",
               "startedAt" = (now() at time zone 'UTC'),
               "progressPct" = 10,
               "attemptCount" = "attemptCount" + 1,
               "lastAttemptAt" = (now() at time zone 'UTC'),
               "updatedAt" = (now() at time zone 'UTC')
         where id = ? and "organizationId" = ?
           and status = 'QUEUED'::"public"."ExportJobStatus\"""",
        UUID.fromString(jobId), org);
  }

  private static int completeExport(Connection c, UUID org, String jobId, String checksum)
      throws SQLException {
    return update(c, """
        update public.export_jobs
           set status = 'COMPLETED'::"public"."ExportJobStatus",
               "finishedAt" = (now() at time zone 'UTC'),
               "progressPct" = 100,
               "rowCount" = 0,
               checksum = ?,
               "storageKey" = 'undo/objects/undo.csv',
               "updatedAt" = (now() at time zone 'UTC')
         where id = ? and "organizationId" = ?
           and status = 'RUNNING'::"public"."ExportJobStatus\"""",
        checksum, UUID.fromString(jobId), org);
  }

  /** @param keepStorageKey true makes the row illegal: DELETED with an object. */
  private static int tombstoneExport(Connection c, UUID org, String jobId, UUID actor,
                                     boolean keepStorageKey) throws SQLException {
    return update(c, """
        update public.export_jobs
           set status = 'DELETED'::"public"."ExportJobStatus",
               "deletedAt" = (now() at time zone 'UTC'),
               "deletedByUserId" = ?,
               "storageKey" = case when ? then "storageKey" else null end,
               checksum = case when ? then checksum else null end,
               "rowCount" = case when ? then "rowCount" else null end,
               "updatedAt" = (now() at time zone 'UTC')
         where id = ? and "organizationId" = ?""",
        actor, keepStorageKey, keepStorageKey, keepStorageKey, UUID.fromString(jobId), org);
  }

  private static String recordExportAttempt(Connection c, UUID org, UUID project, String jobId,
                                            int attemptNumber, String outcome, Long durationMs)
      throws SQLException {
    return queryStringReturningId(c, """
        insert into public.export_job_attempts
             (id, "organizationId", "projectId", "exportJobId", "attemptNumber", outcome,
              "startedAt", "finishedAt", "durationMs", "rowsProduced", "createdAt")
        values (gen_random_uuid(), ?, ?, ?, ?, ?,
                (now() at time zone 'UTC'), (now() at time zone 'UTC'), ?::integer, 0,
                (now() at time zone 'UTC'))
        returning id::text""", org, project, UUID.fromString(jobId),
        Integer.valueOf(attemptNumber), outcome, durationMs);
  }

  private static String recordExportAttemptFailed(Connection c, UUID org, UUID project,
                                                  String jobId, int attemptNumber)
      throws SQLException {
    return queryStringReturningId(c, """
        insert into public.export_job_attempts
             (id, "organizationId", "projectId", "exportJobId", "attemptNumber", outcome,
              "startedAt", "finishedAt", "durationMs", "createdAt")
        values (gen_random_uuid(), ?, ?, ?, ?, 'FAILED',
                (now() at time zone 'UTC'), (now() at time zone 'UTC'), 10,
                (now() at time zone 'UTC'))
        returning id::text""", org, project, UUID.fromString(jobId),
        Integer.valueOf(attemptNumber));
  }

  // --- usage ledger + finance ---------------------------------------------

  private static String insertUsageEvent(Connection c, UUID org, UUID project,
                                         String costAmount, int quantity, String occurredOn)
      throws SQLException {
    return queryStringReturningId(c, """
        insert into public.usage_events
             (id, "organizationId", "projectId", dimension, quantity, unit, "sourceEventType",
              "sourceEventId", "principalType", "principalName", "occurredAt", "periodStart",
              "periodEnd", currency, "costAmount", "createdAt")
        values (gen_random_uuid(), ?, ?, 'AI_TOKEN'::"public"."UsageDimension", ?, 'tokens',
                'undo.Rehearsal', gen_random_uuid(), 'SYSTEM', 'undo-rehearsal',
                ?::timestamp, date '2026-01-01', date '2026-01-31', 'USD', ?::numeric,
                (now() at time zone 'UTC'))
        returning id::text""", org, project, Integer.valueOf(quantity), occurredOn, costAmount);
  }

  /**
   * A NEGATIVE quantity, which {@code usage_events_quantity_check} must refuse.
   *
   * <p>Sign matters here in a way a cost sign does not. A negative
   * {@code costAmount} is obviously wrong, but a negative {@code quantity} is a
   * shape the finance agent may one day want for a reversal row. If it were
   * accepted here it would either be silently dropped from a rollup or, worse,
   * subtracted twice. The ledger has exactly one way to express a reversal --
   * a correction row pointing at {@code correctionOfId} with its own positive
   * amount and a reason -- so the raw event stays non-negative and the
   * derivation of a total is totalable without re-deriving intent.
   */
  private static String insertUsageEventNegativeQuantity(Connection c, UUID org, UUID project)
      throws SQLException {
    return queryStringReturningId(c, """
        insert into public.usage_events
             (id, "organizationId", "projectId", dimension, quantity, unit, "sourceEventType",
              "sourceEventId", "principalType", "principalName", "occurredAt", "periodStart",
              "periodEnd", currency, "costAmount", "createdAt")
        values (gen_random_uuid(), ?, ?, 'AI_TOKEN'::"public"."UsageDimension", -1, 'tokens',
                'undo.Negative', gen_random_uuid(), 'SYSTEM', 'undo-rehearsal',
                timestamp '2026-01-15 12:00:00', date '2026-01-01', date '2026-01-31',
                'USD', 1.000000, (now() at time zone 'UTC'))
        returning id::text""", org, project);
  }

  private static String insertUsageEventWithFxRateOnly(Connection c, UUID org, UUID project)
      throws SQLException {
    return queryStringReturningId(c, """
        insert into public.usage_events
             (id, "organizationId", "projectId", dimension, quantity, unit, "sourceEventType",
              "sourceEventId", "principalType", "principalName", "occurredAt", "periodStart",
              "periodEnd", currency, "costAmount", "fxRate", "createdAt")
        values (gen_random_uuid(), ?, ?, 'AI_TOKEN'::"public"."UsageDimension", 1, 'tokens',
                'undo.Fx', gen_random_uuid(), 'SYSTEM', 'undo-rehearsal',
                timestamp '2026-01-15 12:00:00', date '2026-01-01', date '2026-01-31',
                'USD', 1.000000, 3.750000, (now() at time zone 'UTC'))
        returning id::text""", org, project);
  }

  private static String insertUsageEventOutsidePeriod(Connection c, UUID org, UUID project)
      throws SQLException {
    return queryStringReturningId(c, """
        insert into public.usage_events
             (id, "organizationId", "projectId", dimension, quantity, unit, "sourceEventType",
              "sourceEventId", "principalType", "principalName", "occurredAt", "periodStart",
              "periodEnd", currency, "costAmount", "createdAt")
        values (gen_random_uuid(), ?, ?, 'AI_TOKEN'::"public"."UsageDimension", 1, 'tokens',
                'undo.Outside', gen_random_uuid(), 'SYSTEM', 'undo-rehearsal',
                timestamp '2026-06-15 12:00:00', date '2026-01-01', date '2026-01-31',
                'USD', 1.000000, (now() at time zone 'UTC'))
        returning id::text""", org, project);
  }

  private static String insertUsageEventWithCorrectionOnly(Connection c, UUID org, UUID project,
                                                           String corrects) throws SQLException {
    return queryStringReturningId(c, """
        insert into public.usage_events
             (id, "organizationId", "projectId", dimension, quantity, unit, "sourceEventType",
              "sourceEventId", "principalType", "principalName", "occurredAt", "periodStart",
              "periodEnd", currency, "costAmount", "correctionOfId", "createdAt")
        values (gen_random_uuid(), ?, ?, 'AI_TOKEN'::"public"."UsageDimension", 1, 'tokens',
                'undo.Rehearsal', gen_random_uuid(), 'SYSTEM', 'undo-rehearsal',
                timestamp '2026-01-15 12:00:00', date '2026-01-01', date '2026-01-31',
                'USD', 11.000000, ?::uuid, (now() at time zone 'UTC'))
        returning id::text""", org, project, UUID.fromString(corrects));
  }

  /**
   * A correction row against {@code corrects}.
   *
   * <p>{@code sourceEventId} is a PARAMETER and not a fresh {@code gen_random_uuid()}
   * on purpose. Duplicate suppression in this table is a UNIQUE index over
   * {@code (organizationId, sourceEventType, sourceEventId, dimension)}, so two
   * corrections of the same provider event collide only if they carry the SAME
   * source event id. An earlier version minted a new random one per call, which made
   * every "this duplicates a correction" negative incapable of failing -- it asserted
   * a constraint against a row that was never a duplicate.
   */
  private static String insertUsageEventCorrecting(Connection c, UUID org, UUID project,
                                                   String corrects, String sourceEventId,
                                                   String costAmount, String reason)
      throws SQLException {
    return queryStringReturningId(c, """
        insert into public.usage_events
             (id, "organizationId", "projectId", dimension, quantity, unit, "sourceEventType",
              "sourceEventId", "principalType", "principalName", "occurredAt", "periodStart",
              "periodEnd", currency, "costAmount", "correctionOfId", "correctionReason",
              "createdAt")
        values (gen_random_uuid(), ?, ?, 'AI_TOKEN'::"public"."UsageDimension", 1, 'tokens',
                'undo.ProviderInvoice', ?::uuid, 'SYSTEM', 'undo-rehearsal',
                timestamp '2026-01-15 12:00:00', date '2026-01-01', date '2026-01-31',
                'USD', ?::numeric, ?::uuid, ?, (now() at time zone 'UTC'))
        returning id::text""", org, project, UUID.fromString(sourceEventId), costAmount,
        corrects == null ? null : UUID.fromString(corrects), reason);
  }

  private static String insertRateCard(Connection c, UUID org, String key) throws SQLException {
    return queryStringReturningId(c, """
        insert into public.rate_cards
             (id, "organizationId", "key", "nameAr", "nameEn", currency, status,
              "createdByUserId", "createdAt", "updatedAt")
        values (gen_random_uuid(), ?, ?, '?????', 'undo card', 'USD',
                'DRAFT'::"public"."RateCardStatus",
                (select id from users where "organizationId" = ? order by id limit 1),
                (now() at time zone 'UTC'), (now() at time zone 'UTC'))
        returning id::text""", org, key, org);
  }

  private static String insertRateCardVersion(Connection c, UUID org, String cardId, UUID author,
                                              int versionNumber, String markupRate,
                                              String effectiveFrom, String effectiveTo)
      throws SQLException {
    return queryStringReturningId(c, """
        insert into public.rate_card_versions
             (id, "organizationId", "rateCardId", "versionNumber", currency, "markupRate",
              "effectiveFrom", "effectiveTo", status, "changeNote", "publishedAt",
              "publishedByUserId", "createdByUserId", "createdAt")
        values (gen_random_uuid(), ?, ?, ?, 'USD', ?::numeric, ?::date, ?::date,
                'ACTIVE'::"public"."RateCardStatus", 'undo rehearsal version',
                (now() at time zone 'UTC'), ?, ?,
                (now() at time zone 'UTC'))
        returning id::text""", org, UUID.fromString(cardId), Integer.valueOf(versionNumber),
        markupRate, effectiveFrom, effectiveTo, author, author);
  }

  private static int retireRateCardVersion(Connection c, UUID org, String versionId, UUID actor,
                                           String successorVersionId) throws SQLException {
    return update(c, """
        update public.rate_card_versions
           set status = 'RETIRED'::"public"."RateCardStatus",
               "retiredAt" = (now() at time zone 'UTC'),
               "retiredByUserId" = ?,
               "retireReason" = 'undo rehearsal retirement',
               "supersededByVersionId" = ?
         where id = ? and "organizationId" = ?""",
        actor, UUID.fromString(successorVersionId), UUID.fromString(versionId), org);
  }

  private static int retireRateCardVersionWithoutSuccessor(Connection c, UUID org, String versionId,
                                                           UUID actor) throws SQLException {
    return update(c, """
        update public.rate_card_versions
           set status = 'RETIRED'::"public"."RateCardStatus",
               "retiredAt" = (now() at time zone 'UTC'),
               "retiredByUserId" = ?,
               "retireReason" = 'undo rehearsal retirement'
         where id = ? and "organizationId" = ?""",
        actor, UUID.fromString(versionId), org);
  }

  private static String insertFinancePeriod(Connection c, UUID org, String key,
                                            String start, String end) throws SQLException {
    return queryStringReturningId(c, """
        insert into public.finance_periods
             (id, "organizationId", "key", "nameAr", "nameEn", "periodStart", "periodEnd",
              currency, status, "createdAt", "updatedAt")
        values (gen_random_uuid(), ?, ?, '????', 'undo period', ?::date, ?::date, 'USD',
                'OPEN'::"public"."PeriodStatus",
                (now() at time zone 'UTC'), (now() at time zone 'UTC'))
        returning id::text""", org, key, start, end);
  }

  private static String insertRollupSnapshot(Connection c, UUID org, UUID project, String periodId,
                                             String costAmount, String revenueAmount,
                                             String markupRate, String rateCardVersionId,
                                             int generation) throws SQLException {
    return queryStringReturningId(c, """
        insert into public.usage_rollup_snapshots
             (id, "organizationId", "projectId", "periodId", grain, "bucketStart", "bucketEnd",
              dimension, currency, "sourceEventCount", quantity, "costAmount", "revenueAmount",
              "markupRate", "rateCardVersionId", generation, "computedAt",
              "computedByPrincipal", "createdAt")
        values (gen_random_uuid(), ?, ?, ?::uuid, 'MONTHLY'::"public"."RollupGrain",
                date '2026-01-01', date '2026-01-31', 'AI_TOKEN'::"public"."UsageDimension",
                'USD', 1, 1000.000000, ?::numeric, ?::numeric, ?::numeric, ?::uuid, ?,
                (now() at time zone 'UTC'), 'undo-rehearsal', (now() at time zone 'UTC'))
        returning id::text""", org, project, periodId, costAmount, revenueAmount, markupRate,
        rateCardVersionId == null ? null : UUID.fromString(rateCardVersionId),
        Integer.valueOf(generation));
  }

  private static String insertRollupSnapshotHalfPriced(Connection c, UUID org, UUID project,
                                                       String periodId, String costAmount,
                                                       String revenueAmount, String markupRate,
                                                       String rateCardVersionId, int generation)
      throws SQLException {
    return queryStringReturningId(c, """
        insert into public.usage_rollup_snapshots
             (id, "organizationId", "projectId", "periodId", grain, "bucketStart", "bucketEnd",
              dimension, currency, "sourceEventCount", quantity, "costAmount", "revenueAmount",
              "markupRate", "rateCardVersionId", generation, "computedAt",
              "computedByPrincipal", "createdAt")
        values (gen_random_uuid(), ?, ?, ?::uuid, 'MONTHLY'::"public"."RollupGrain",
                date '2026-01-01', date '2026-01-31', 'AI_TOKEN'::"public"."UsageDimension",
                'USD', 1, 1000.000000, ?::numeric, ?::numeric, null, ?::uuid, ?,
                (now() at time zone 'UTC'), 'undo-rehearsal', (now() at time zone 'UTC'))
        returning id::text""", org, project, periodId, costAmount, revenueAmount,
        rateCardVersionId == null ? null : UUID.fromString(rateCardVersionId),
        Integer.valueOf(generation));
  }

  private static String insertRollupSnapshotEmpty(Connection c, UUID org, UUID project,
                                                   String periodId) throws SQLException {
    return queryStringReturningId(c, """
        insert into public.usage_rollup_snapshots
             (id, "organizationId", "projectId", "periodId", grain, "bucketStart", "bucketEnd",
              dimension, currency, "sourceEventCount", quantity, "costAmount", "unpricedQuantity",
              generation, "computedAt", "computedByPrincipal", "createdAt")
        values (gen_random_uuid(), ?, ?, ?::uuid, 'MONTHLY'::"public"."RollupGrain",
                date '2026-02-01', date '2026-02-28', 'AI_TOKEN'::"public"."UsageDimension",
                'USD', 0, 0.000000, 0.000000, 1,
                (now() at time zone 'UTC'), 'undo-rehearsal', (now() at time zone 'UTC'))
        returning id::text""", org, project, periodId);
  }

  private static String insertUnpricedRollupSnapshot(Connection c, UUID org, UUID project,
                                                      String periodId) throws SQLException {
    return queryStringReturningId(c, """
        insert into public.usage_rollup_snapshots
             (id, "organizationId", "projectId", "periodId", grain, "bucketStart", "bucketEnd",
              dimension, currency, "sourceEventCount", quantity, "costAmount",
              "unpricedQuantity", "unpricedCostAmount", generation, "computedAt",
              "computedByPrincipal", "createdAt")
        values (gen_random_uuid(), ?, ?, ?::uuid, 'MONTHLY'::"public"."RollupGrain",
                date '2026-03-01', date '2026-03-31', 'AI_TOKEN'::"public"."UsageDimension",
                'USD', 1, 5.000000, 500.000000, 5.000000, 500.000000, 1,
                (now() at time zone 'UTC'), 'undo-rehearsal', (now() at time zone 'UTC'))
        returning id::text""", org, project, periodId);
  }

  private static String insertRollupSnapshotWithOtherTenantRate(Connection c, UUID org,
                                                                UUID project, String periodId,
                                                                UUID otherAuthor)
      throws SQLException {
    return queryStringReturningId(c, """
        insert into public.usage_rollup_snapshots
             (id, "organizationId", "projectId", "periodId", grain, "bucketStart", "bucketEnd",
              dimension, currency, "sourceEventCount", quantity, "costAmount", "revenueAmount",
              "markupRate", "rateCardVersionId", generation, "computedAt",
              "computedByPrincipal", "createdAt")
        select gen_random_uuid(), ?, ?, ?::uuid, 'MONTHLY'::"public"."RollupGrain",
               date '2026-04-01', date '2026-04-30', 'AI_TOKEN'::"public"."UsageDimension",
               'USD', 1, 10.000000, 30.000000, 3.000000,
               (select id from rate_card_versions where "createdByUserId" = ? limit 1), 1,
               (now() at time zone 'UTC'), 'undo-rehearsal', (now() at time zone 'UTC')
        returning id::text""", org, project, periodId, otherAuthor);
  }

  private static String insertRollupSnapshotGeneration2(Connection c, UUID org, UUID project,
                                                         String periodId, String supersedes,
                                                         String costAmount, String revenueAmount,
                                                         String markupRate,
                                                         String rateCardVersionId)
      throws SQLException {
    return queryStringReturningId(c, """
        insert into public.usage_rollup_snapshots
             (id, "organizationId", "projectId", "periodId", grain, "bucketStart", "bucketEnd",
              dimension, currency, "sourceEventCount", quantity, "costAmount", "revenueAmount",
              "markupRate", "rateCardVersionId", generation, "supersedesSnapshotId",
              "computedAt", "computedByPrincipal", "createdAt")
        values (gen_random_uuid(), ?, ?, ?::uuid, 'MONTHLY'::"public"."RollupGrain",
                date '2026-05-01', date '2026-05-31', 'AI_TOKEN'::"public"."UsageDimension",
                'USD', 1, 1000.000000, ?::numeric, ?::numeric, ?::numeric, ?::uuid, 2, ?::uuid,
                (now() at time zone 'UTC'), 'undo-rehearsal', (now() at time zone 'UTC'))
        returning id::text""", org, project, periodId, costAmount, revenueAmount, markupRate,
        rateCardVersionId == null ? null : UUID.fromString(rateCardVersionId),
        UUID.fromString(supersedes));
  }

  private static int lockFinancePeriod(Connection c, UUID org, String periodId, UUID actor)
      throws SQLException {
    return update(c, """
        update public.finance_periods
           set status = 'LOCKED'::"public"."PeriodStatus",
               "lockedAt" = (now() at time zone 'UTC'),
               "lockedByUserId" = ?,
               "lockNote" = 'undo rehearsal lock',
               "updatedAt" = (now() at time zone 'UTC')
         where id = ? and "organizationId" = ?""", actor, UUID.fromString(periodId), org);
  }

  private static int reopenFinancePeriod(Connection c, UUID org, String periodId, UUID actor,
                                         String reason) throws SQLException {
    return update(c, """
        update public.finance_periods
           set status = 'REOPENED'::"public"."PeriodStatus",
               "reopenedAt" = (now() at time zone 'UTC'),
               "reopenedByUserId" = ?,
               "reopenReason" = ?,
               "reopenCount" = "reopenCount" + 1,
               "updatedAt" = (now() at time zone 'UTC')
         where id = ? and "organizationId" = ?""", actor, reason, UUID.fromString(periodId), org);
  }

  // --- AI governance -------------------------------------------------------

  /** An agent, an agent version and a run: the three rows every AI record needs. */
  private record AiRun(UUID runId, UUID agentVersionId) {
  }

  private static AiRun seedAiRun(UUID org, UUID project, UUID user, String tag)
      throws SQLException {
    // ai_runs."conversationId" is NOT NULL in the verified schema, and the
    // V20261005110000 append added a COMPOSITE tenant key (ai_runs.conversationId
    // + organizationId + projectId) onto conversations. Selecting "any conversation
    // for this org" therefore only works if one exists, and it silently produced
    // a NULL column rather than a useful error -- so the conversation is seeded
    // per tenant here, explicitly, instead of being discovered.
    UUID conversation = seedConversation(org, project, "ai-run-" + tag);

    String agentId;
    String versionId;
    String runId;
    try (Connection c = connect(phase5aUrl);
         Statement s = c.createStatement()) {
      s.executeUpdate("""
          insert into ai_agents (id, "organizationId", "projectId", name, "isEnabled",
                                 "createdAt", "updatedAt")
          values (gen_random_uuid(), '%s', '%s', '%s', true,
                  (now() at time zone 'UTC'), (now() at time zone 'UTC'))"""
          .formatted(org, project, "undo-agent-" + tag));
      agentId = queryString(connect(phase5aUrl),
          "select id::text from ai_agents where name = 'undo-agent-" + tag + "'");
      s.executeUpdate("""
          insert into ai_agent_versions (id, "organizationId", "projectId", "agentId", version,
                                         "systemInstructions", "createdByUserId", "createdAt")
          values (gen_random_uuid(), '%s', '%s', '%s', 1, 'undo rehearsal agent',
                  '%s', (now() at time zone 'UTC'))"""
          .formatted(org, project, agentId, user));
      versionId = queryString(connect(phase5aUrl),
          "select id::text from ai_agent_versions where \"agentId\" = '" + agentId + "'");
    }
    try (Connection c = connect(phase5aUrl);
         Statement s = c.createStatement()) {
      s.executeUpdate("""
          insert into ai_runs (id, "organizationId", "projectId", "conversationId", "agentId",
                               "agentVersionId", status, provider, "startedAt", "finishedAt")
          select gen_random_uuid(), ra."organizationId", ra."projectId",
                 '%s'::uuid,
                 ra.id, rav.id, 'COMPLETED'::"AIRunStatus", 'MOCK'::"AIProviderKind",
                 (now() at time zone 'UTC'), (now() at time zone 'UTC')
            from ai_agents ra, ai_agent_versions rav
           where ra.id = '%s' and rav.id = '%s'"""
          .formatted(conversation, agentId, versionId));
    }
    runId = queryString(connect(phase5aUrl),
        "select ar.id::text from ai_runs ar"
            + " join ai_agents a on a.id = ar.\"agentId\""
            + " where a.name = 'undo-agent-" + tag + "'");
    assertThat(tag).as("ai run label is documentation only").isNotBlank();
    return new AiRun(UUID.fromString(runId), UUID.fromString(versionId));
  }

  private static String insertAiTool(Connection c, UUID org, String toolName, boolean enabled,
                                     String disableReason) throws SQLException {
    return queryStringReturningId(c, """
        insert into public.ai_tool_registry
             (id, "organizationId", "toolName", "requiredPermission", "isEnabled",
              "disabledAt", "disabledByUserId", "disabledReason", "enabledAt", "enabledByUserId",
              "riskLevel", "createdAt", "updatedAt")
        values (gen_random_uuid(), ?, ?, 'tool.undo.example', ?,
                case when ? then null else (now() at time zone 'UTC') end,
                case when ? then null
                     else (select id from users where "organizationId" = ? limit 1) end,
                case when ? then null else ? end,
                case when ? then (now() at time zone 'UTC') else null end,
                case when ? then (select id from users where "organizationId" = ? limit 1) end,
                'LOW'::"public"."AiRiskLevel",
                (now() at time zone 'UTC'), (now() at time zone 'UTC'))
        returning id::text""", org, toolName, Boolean.valueOf(enabled), Boolean.valueOf(enabled),
        Boolean.valueOf(enabled), org, Boolean.valueOf(enabled), disableReason,
        Boolean.valueOf(enabled), Boolean.valueOf(enabled), org);
  }

  private static int enableHighRiskToolWithoutAllowList(Connection c, UUID org, String toolName)
      throws SQLException {
    return update(c, """
        insert into public.ai_tool_registry
             (id, "organizationId", "toolName", "requiredPermission", "isEnabled", "enabledAt",
              "enabledByUserId", "fieldAllowList", "riskLevel", "createdAt", "updatedAt")
        values (gen_random_uuid(), ?, ?, 'tool.mass', true, (now() at time zone 'UTC'),
                (select id from users where "organizationId" = ? limit 1),
                '{}'::text[], 'HIGH'::"public"."AiRiskLevel",
                (now() at time zone 'UTC'), (now() at time zone 'UTC'))""",
        org, toolName, org);
  }

  private static String insertToolExecution(Connection c, UUID org, UUID project, UUID runId,
                                           String toolName, String actionClass, String decision,
                                           String denyReason, String approvalId)
      throws SQLException {
    return queryStringReturningId(c, """
        insert into public.ai_tool_executions
             (id, "organizationId", "projectId", "aiRunId", "toolName", "actionClass",
              "consequentialAction", decision, "denyReason", "approvalId", "argumentsRedacted",
              "resultRedacted", "principalType", "requestedAt", "executedAt", "createdAt")
        -- actionClass is bound TWICE on purpose: it is both stored and compared, so
        -- consequentialAction is DERIVED from the stored value rather than passed
        -- separately. An earlier version bound it once and the fixture then failed
        -- with "No value specified for parameter 10" -- which is the harmless
        -- version of this bug, because a mismatched binding here would instead
        -- have made consequentialAction disagree with actionClass.
        values (gen_random_uuid(), ?, ?, ?::uuid, ?, ?::text,
                (?::text = 'CONSEQUENTIAL'), ?::"public"."AiPolicyDecision", ?,
                ?::uuid, '{}'::jsonb, '{}'::jsonb, 'AI_AGENT',
                (now() at time zone 'UTC'),
                case when ?::"public"."AiPolicyDecision" = 'ALLOW'
                     then (now() at time zone 'UTC') else null end,
                (now() at time zone 'UTC'))
        returning id::text""", org, project, runId, toolName, actionClass, actionClass, decision,
        denyReason, approvalId == null ? null : UUID.fromString(approvalId), decision);
  }

  private static String insertToolExecutionExecuted(Connection c, UUID org, UUID project,
                                                    UUID runId, String toolName,
                                                    String actionClass, String decision)
      throws SQLException {
    return queryStringReturningId(c, """
        insert into public.ai_tool_executions
             (id, "organizationId", "projectId", "aiRunId", "toolName", "actionClass",
              "consequentialAction", decision, "approvalId", "argumentsRedacted",
              "resultRedacted", "principalType", "requestedAt", "executedAt", "createdAt")
        values (gen_random_uuid(), ?, ?, ?::uuid, ?, ?::text,
                (?::text = 'CONSEQUENTIAL'), ?::"public"."AiPolicyDecision",
                (select id from ai_approvals
                  where "organizationId" = ? and status = 'PENDING'::"AiApprovalStatus" limit 1),
                '{}'::jsonb, '{}'::jsonb, 'AI_AGENT',
                (now() at time zone 'UTC'), (now() at time zone 'UTC'),
                (now() at time zone 'UTC'))
        returning id::text""", org, project, runId, toolName, actionClass, decision, org);
  }

  private static String insertAiApproval(Connection c, UUID org, UUID project, UUID runId,
                                         String action, UUID requestedBy) throws SQLException {
    return queryStringReturningId(c, """
        -- NOTE: NO createdAt. ai_approvals is the one MUTABLE table in the AI slice --
        -- an approval is a state machine a human moves through -- so it has
        -- updatedAt and deliberately no createdAt, with the transitions recorded
        -- append-only in ai_approval_decisions instead. An earlier version of this
        -- helper listed createdAt, which does not exist, and the statement failed
        -- with "column does not exist" -- i.e. the approval was never created and
        -- every later assertion was reading a table it had not written to.
        insert into public.ai_approvals
             (id, "organizationId", "projectId", "aiRunId", action, "actionClass", status,
              "requestedByUserId", "requestedByPrincipal", "requestedAt", "expiresAt",
              "updatedAt")
        values (gen_random_uuid(), ?, ?, ?::uuid, ?, 'CONSEQUENTIAL',
                'PENDING'::"public"."AiApprovalStatus", ?, 'undo-rehearsal',
                (now() at time zone 'UTC'),
                (now() at time zone 'UTC') + interval '15 minutes',
                (now() at time zone 'UTC'))
        returning id::text""", org, project, runId, action, requestedBy);
  }

  private static int approveAiApproval(Connection c, UUID org, String approvalId, UUID approver)
      throws SQLException {
    return update(c, """
        update public.ai_approvals
           set status = 'APPROVED'::"public"."AiApprovalStatus",
               "decidedByUserId" = ?,
               "decidedAt" = (now() at time zone 'UTC'),
               "decisionNote" = 'approved by the undo rehearsal',
               "updatedAt" = (now() at time zone 'UTC')
         where id = ? and "organizationId" = ?
           and status = 'PENDING'::"public"."AiApprovalStatus\"""",
        approver, UUID.fromString(approvalId), org);
  }

  private static int revokeAiApprovalWithoutReason(Connection c, UUID org, String approvalId,
                                                    UUID actor) throws SQLException {
    return update(c, """
        update public.ai_approvals
           set status = 'REVOKED'::"public"."AiApprovalStatus",
               "revokedAt" = (now() at time zone 'UTC'),
               "revokedByUserId" = ?,
               "updatedAt" = (now() at time zone 'UTC')
         where id = ? and "organizationId" = ?""", actor, UUID.fromString(approvalId), org);
  }

  /**
   * Creates a tenant (and a project) for a behavioural test.
   */
  private static Tenant seedTenant(String name) throws SQLException {
    try (Connection c = connect(phase5aUrl);
         Statement s = c.createStatement()) {
      s.executeUpdate("""
          insert into organizations (id, name, slug, "createdAt", "updatedAt")
          values (gen_random_uuid(), '%1$s', '%1$s', current_timestamp, current_timestamp)""".formatted(name));
      UUID org = queryUuid(phase5aUrl, "select id from organizations where slug = '" + name + "'");
      s.executeUpdate("""
          insert into projects (id, "organizationId", name, slug, "createdAt", "updatedAt")
          select gen_random_uuid(), id, '%1$s', '%1$s', current_timestamp, current_timestamp
            from organizations where slug = '%1$s'""".formatted(name));
      UUID project = queryUuid(phase5aUrl, "select id from projects where slug = '" + name + "'");
      return new Tenant(org, project);
    }
  }

  /** A tenant and one of its projects, so tests never carry ids around. */
  private record Tenant(UUID organizationId, UUID projectId) {
  }

  private static UUID seedUser(UUID org, String tag) throws SQLException {
    try (Connection c = connect(phase5aUrl);
         Statement s = c.createStatement()) {
      s.executeUpdate("""
          insert into users (id, "organizationId", email, "passwordHash", "fullName",
                             role, locale, "createdAt", "updatedAt")
          values (gen_random_uuid(), '%s', '%s@example.test', 'not-a-real-hash', 'Phase 5a',
                  'EMPLOYEE'::"UserRole", 'ar'::"Locale", current_timestamp, current_timestamp)"""
          .formatted(org, tag));
    }
    return queryUuid(phase5aUrl, "select id from users where email = '" + tag + "@example.test'");
  }

  /**
   * Seeds {@code count} claimable outbox events. {@code nextAttemptOffsetSeconds}
   * &gt; 0 makes the row NOT due, which is how "a backoff is respected" is
   * proven.
   */
  private static List<String> seedOutbox(UUID org, UUID project, String tag, int count,
                                          int nextAttemptOffsetSeconds) throws SQLException {
    List<String> ids = new ArrayList<>();
    try (Connection c = connect(phase5aUrl)) {
      c.setAutoCommit(false);
      try (PreparedStatement ps = c.prepareStatement("""
          insert into outbox_events (id, "organizationId", "projectId", "aggregateType",
                                     "aggregateId", "eventType", "idempotencyKey", "payload",
                                     "nextAttemptAt", "createdAt", "updatedAt")
          values (gen_random_uuid(), ?, ?, 'Message', gen_random_uuid(), 'outbound.send', ?,
                  '{"templateId":"welcome"}'::jsonb,
                  (now() at time zone 'UTC') + (? || ' seconds')::interval,
                  (now() at time zone 'UTC'), (now() at time zone 'UTC'))
          returning id::text""")) {
        for (int i = 0; i < count; i++) {
          ps.setObject(1, org);
          if (project == null) {
            ps.setNull(2, java.sql.Types.OTHER);
          } else {
            ps.setObject(2, project);
          }
          ps.setString(3, tag + "-" + UUID.randomUUID());
          ps.setString(4, Integer.toString(nextAttemptOffsetSeconds));
          try (ResultSet rs = ps.executeQuery()) {
            rs.next();
            ids.add(rs.getString(1));
          }
        }
      }
      c.commit();
    }
    return ids;
  }

  /**
   * The canonical tenant-scoped claim, copied from
   * ops/flyway/reference/outbox-claim.sql section 2. The UPDATE and the
   * SELECT are one statement, so the claim and the state change are atomic.
   */
  private static List<String> claim(Connection c, UUID org, int batchSize, String worker)
      throws SQLException {
    return ids(c, """
        update public.outbox_events o
           set state            = 'LEASED',
               "leaseOwner"     = ?,
               "leaseUntil"     = (now() at time zone 'UTC') + interval '60 seconds',
               attempts         = o.attempts + 1,
               "lastAttemptAt"  = (now() at time zone 'UTC'),
               "updatedAt"      = (now() at time zone 'UTC')
          from (
                select id
                  from public.outbox_events
                 where "organizationId" = ?
                   and state = 'PENDING'
                   and "nextAttemptAt" <= (now() at time zone 'UTC')
                   and ("leaseUntil" is null or "leaseUntil" < (now() at time zone 'UTC'))
                 order by "nextAttemptAt", "createdAt", id
                 limit ?
                   for update skip locked
               ) claimable
         where o.id = claimable.id
        returning o.id::text""", worker, org, batchSize);
  }

  /** The organisation-wide sweep, reference section 3. */
  private static List<String> sweepClaim(Connection c, int batchSize, String worker)
      throws SQLException {
    return ids(c, """
        update public.outbox_events o
           set state            = 'LEASED',
               "leaseOwner"     = ?,
               "leaseUntil"     = (now() at time zone 'UTC') + interval '60 seconds',
               attempts         = o.attempts + 1,
               "lastAttemptAt"  = (now() at time zone 'UTC'),
               "updatedAt"      = (now() at time zone 'UTC')
          from (
                select id
                  from public.outbox_events
                 where state = 'PENDING'
                   and "nextAttemptAt" <= (now() at time zone 'UTC')
                   and ("leaseUntil" is null or "leaseUntil" < (now() at time zone 'UTC'))
                 order by "nextAttemptAt", "createdAt", id
                 limit ?
                   for update skip locked
               ) claimable
         where o.id = claimable.id
        returning o.id::text""", worker, batchSize);
  }

  /** The crashed-worker reaper, reference section 7. */
  private static List<String> reap(Connection c, int batchSize) throws SQLException {
    return ids(c, """
        update public.outbox_events o
           set state          = 'PENDING',
               "leaseOwner"  = null,
               "leaseUntil"  = null,
               "lastError"   = coalesce(o."lastError", 'lease expired; worker presumed dead'),
               "nextAttemptAt" = (now() at time zone 'UTC'),
               "updatedAt"   = (now() at time zone 'UTC')
          from (
                select id
                  from public.outbox_events
                 where state = 'LEASED'
                   and "leaseUntil" < (now() at time zone 'UTC')
                 order by "leaseUntil"
                 limit ?
                   for update skip locked
               ) expired
         where o.id = expired.id
        returning o.id::text""", batchSize);
  }

  private static int complete(Connection c, UUID org, String id, String worker) throws SQLException {
    return update(c, """
        update public.outbox_events
           set state        = 'SENT',
               "deliveredAt" = (now() at time zone 'UTC'),
               "leaseOwner"  = null,
               "leaseUntil"  = null,
               "updatedAt"   = (now() at time zone 'UTC')
         where id = ? and "organizationId" = ? and state = 'LEASED' and "leaseOwner" = ?""",
        UUID.fromString(id), org, worker);
  }

  private static int retryClaimable(Connection c, UUID org, String id, String worker)
      throws SQLException {
    return update(c, """
        update public.outbox_events
           set state          = 'PENDING',
               "nextAttemptAt" = (now() at time zone 'UTC') + interval '30 seconds',
               "leaseOwner"    = null,
               "leaseUntil"    = null,
               "lastError"     = 'provider timeout',
               "updatedAt"     = (now() at time zone 'UTC')
         where id = ? and "organizationId" = ? and state = 'LEASED' and "leaseOwner" = ?""",
        UUID.fromString(id), org, worker);
  }

  private static int deadLetter(Connection c, UUID org, String id, String worker, String reason)
      throws SQLException {
    return update(c, """
        update public.outbox_events
           set state              = 'DEAD',
               "deadLetteredAt"    = (now() at time zone 'UTC'),
               "deadLetterReason"  = ?,
               "leaseOwner"        = null,
               "leaseUntil"        = null,
               "lastError"         = ?,
               "updatedAt"         = (now() at time zone 'UTC')
         where id = ? and "organizationId" = ? and state = 'LEASED' and "leaseOwner" = ?""",
        reason, reason, UUID.fromString(id), org, worker);
  }

  private static int replay(Connection c, UUID org, String id, UUID user) throws SQLException {
    return update(c, """
        update public.outbox_events
           set state              = 'PENDING',
               "deadLetteredAt"   = null,
               "deadLetterReason" = null,
               "nextAttemptAt"    = (now() at time zone 'UTC'),
               "replayCount"      = "replayCount" + 1,
               "lastReplayedAt"   = (now() at time zone 'UTC'),
               "replayedByUserId" = ?,
               "updatedAt"        = (now() at time zone 'UTC')
         where id = ? and "organizationId" = ? and state = 'DEAD'""",
        user, UUID.fromString(id), org);
  }

  private static String recordAttempt(Connection c, UUID org, String queue, String jobId,
                                      int attemptNumber, String outcome, boolean isReplay,
                                      String replayOfId, String outboxEventId) throws SQLException {
    return queryStringReturningId(c, """
        insert into public.job_attempts (id, "organizationId", "queueName", "jobType", "jobId",
                                         "attemptNumber", outcome, "startedAt", "finishedAt",
                                         "durationMs", "workerId", "isReplay", "replayOfId",
                                         "outboxEventId", "createdAt")
        values (gen_random_uuid(), ?, ?, 'outbound.send', ?, ?, ?::"public"."JobAttemptOutcome",
                (now() at time zone 'UTC'), (now() at time zone 'UTC'), 12, 'worker-fixture',
                ?, ?, ?, (now() at time zone 'UTC'))
        returning id::text""",
        org, queue, jobId, Integer.valueOf(attemptNumber), outcome,
        Boolean.valueOf(isReplay), uuidOrNull(replayOfId), uuidOrNull(outboxEventId));
  }

  private static void upsertDeadLetter(Connection c, UUID org, String queue, String jobId,
                                       String attemptId, int attempts, String outboxEventId)
      throws SQLException {
    execute(c, """
        insert into public.job_dead_letters (id, "organizationId", "queueName", "jobId",
                                             "lastAttemptId", "outboxEventId", reason,
                                             "payloadSummary", attempts, "createdAt", "updatedAt")
        values (gen_random_uuid(), ?, ?, ?, ?, ?, 'PROVIDER_DOWN',
                '{"attempts":3}'::jsonb, ?, now(), now())
        on conflict ("organizationId", "queueName", "jobId") do update
           set "lastAttemptId" = excluded."lastAttemptId",
               "outboxEventId" = excluded."outboxEventId",
               attempts         = public.job_dead_letters.attempts + excluded.attempts,
               status           = 'OPEN'::"public"."DeadLetterStatus",
               "resolvedByUserId" = null,
               "resolvedAt"     = null,
               "resolutionNote" = null,
               "replayCount"    = public.job_dead_letters."replayCount" + 1,
               "lastReplayedAt" = (now() at time zone 'UTC'),
               "updatedAt"      = (now() at time zone 'UTC')""",
        org, queue, jobId, UUID.fromString(attemptId), UUID.fromString(outboxEventId),
        Integer.valueOf(attempts));
  }

  private static boolean reserveIdempotency(Connection c, UUID org, String scope, String key)
      throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("""
        insert into public.idempotency_records (id, "organizationId", scope, "idempotencyKey",
                                                "requestFingerprint", "lockExpiresAt",
                                                "createdAt", "updatedAt")
        values (gen_random_uuid(), ?, ?, ?, 'sha256-of-request',
                (now() at time zone 'UTC') + interval '30 seconds', now(), now())
        on conflict ("organizationId", scope, "idempotencyKey") do nothing
        returning 1""")) {
      ps.setObject(1, org);
      ps.setString(2, scope);
      ps.setString(3, key);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  private static int completeIdempotency(Connection c, UUID org, String scope, String key,
                                          int status, String bodyHash) throws SQLException {
    return update(c, """
        update public.idempotency_records
           set state              = 'COMPLETED'::"public"."IdempotencyState",
               "responseStatus"    = ?,
               "responseBodyHash"  = ?,
               "lockExpiresAt"     = null,
               "updatedAt"         = (now() at time zone 'UTC')
         where "organizationId" = ? and scope = ? and "idempotencyKey" = ?
           and state = 'IN_PROGRESS'::"public"."IdempotencyState" """,
        Integer.valueOf(status), bodyHash, org, scope, key);
  }

  /** Moves a row's nextAttemptAt into the past so it is due again. */
  private static void makeDue(Connection c, String id) throws SQLException {
    execute(c, """
        update outbox_events set "nextAttemptAt" = (now() at time zone 'UTC')
         where id = '%s'""".formatted(id));
  }

  /** Ages a lease, standing in for the passage of time. */
  private static void expireLease(Connection c, String id) throws SQLException {
    execute(c, """
        update outbox_events set "leaseUntil" = (now() at time zone 'UTC') - interval '1 minute'
         where id = '%s'""".formatted(id));
  }

  private static String explain(String sql) throws SQLException {
    List<String> lines = new ArrayList<>();
    try (Connection c = connect(phase5aUrl);
         Statement s = c.createStatement();
         ResultSet rs = s.executeQuery("explain " + sql)) {
      while (rs.next()) {
        lines.add(rs.getString(1));
      }
    }
    return String.join("\n", lines);
  }

  private static List<String> ids(Connection c, String sql, Object... params) throws SQLException {
    List<String> out = new ArrayList<>();
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      bind(ps, params);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(rs.getString(1));
        }
      }
    }
    return out;
  }

  private static int update(Connection c, String sql, Object... params) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      bind(ps, params);
      return ps.executeUpdate();
    }
  }

  private static int execute(Connection c, String sql, Object... params) throws SQLException {
    return update(c, sql, params);
  }

  private static void bind(PreparedStatement ps, Object... params) throws SQLException {
    for (int i = 0; i < params.length; i++) {
      Object p = params[i];
      if (p == null) {
        // A null must be NULL, not the string "null": String.valueOf(null) would
        // bind varchar 'null' into a uuid column.
        ps.setNull(i + 1, java.sql.Types.OTHER);
      } else if (p instanceof UUID uuid) {
        // Types.OTHER: pgjdbc's untyped setObject sends a java.util.UUID as
        // varchar, and `uuid = character varying` is not an operator.
        ps.setObject(i + 1, uuid, java.sql.Types.OTHER);
      } else if (p instanceof Integer n) {
        ps.setInt(i + 1, n);
      } else if (p instanceof Boolean b) {
        ps.setBoolean(i + 1, b);
      } else {
        ps.setString(i + 1, String.valueOf(p));
      }
    }
  }

  private static String queryStringReturningId(Connection c, String sql, Object... params)
      throws SQLException {
    List<String> rows = ids(c, sql, params);
    assertThat(rows).as("row returned by: %s", sql).hasSize(1);
    return rows.get(0);
  }

  private static UUID uuidOrNull(String uuid) {
    return uuid == null ? null : UUID.fromString(uuid);
  }

  private static UUID queryUuid(String jdbcUrl, String sql) throws SQLException {
    return UUID.fromString(queryString(connect(jdbcUrl), sql));
  }

  private static int queryInt(Connection c, String sql) throws SQLException {
    return (int) queryLong(c, sql);
  }

  /** As {@link #queryInt(Connection, String)}, with bound parameters. */
  private static int queryInt(Connection c, String sql, Object... params) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      bind(ps, params);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).as("row returned by: %s", sql).isTrue();
        return rs.getInt(1);
      }
    }
  }

  private static long queryLong(Connection c, String sql) throws SQLException {
    try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
      assertThat(rs.next()).as("row returned by: %s", sql).isTrue();
      return rs.getLong(1);
    }
  }

  private static String queryString(Connection c, String sql) throws SQLException {
    try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
      assertThat(rs.next()).as("row returned by: %s", sql).isTrue();
      return rs.getString(1);
    }
  }

  private static List<String> queryStrings(Connection c, String sql) throws SQLException {
    List<String> out = new ArrayList<>();
    try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
      while (rs.next()) {
        out.add(rs.getString(1));
      }
    }
    return out;
  }

  /** As {@link #queryString(Connection, String)}, with bound parameters. */
  private static String queryString(Connection c, String sql, Object... params) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      bind(ps, params);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).as("row returned by: %s", sql).isTrue();
        return rs.getString(1);
      }
    }
  }

  /**
   * Runs a statement against the behavioural database by URL.
   *
   * <p>The Phase-10b scenarios take a JDBC url rather than a Connection so each statement gets its
   * own transaction: a refused INSERT must not poison a later assertion, and an autoCommit=false
   * Connection would leave the refusal open until somebody closed it.
   */
  private static void execute(String jdbcUrl, String sql, Object... params) throws SQLException {
    try (Connection c = connect(jdbcUrl)) {
      execute(c, sql, params);
    }
  }

  /** A query that returns one text column, bound parameters, by URL. */
  private static String requestString(String jdbcUrl, String sql, Object... params) throws SQLException {
    try (Connection c = connect(jdbcUrl)) {
      return queryString(c, sql, params);
    }
  }

  /** A count query, bound parameters, by URL. */
  private static int countOn(String jdbcUrl, String sql, Object... params) throws SQLException {
    try (Connection c = connect(jdbcUrl)) {
      return queryInt(c, sql, params);
    }
  }

  /** Shorthand used where a fresh Connection would be noise in an assertion. */
  private static Connection c2(String jdbcUrl) throws SQLException {
    return connect(jdbcUrl);
  }

  /** The id of a user in a tenant, by the tag it was seeded with. */
  private static UUID userId(UUID org, String tag) throws SQLException {
    return queryUuid(phase5aUrl,
        "select id from users where \"organizationId\" = '" + org
            + "' and email = '" + tag + "@example.test'");
  }

  // ------------------------------------------------------------------
  // Phase-10b seeding helpers
  // ------------------------------------------------------------------

  /** An organization plus its project, for the composite-tenant-FK scenarios. */
  private static void seedOrganizationAndProject(UUID org, UUID project) throws SQLException {
    try (Connection c = connect(phase5aUrl);
         Statement s = c.createStatement()) {
      s.executeUpdate("""
          insert into organizations (id, name, slug, "createdAt", "updatedAt")
          values ('%s', 'phase10b-%s', 'phase10b-%s', current_timestamp, current_timestamp)"""
          .formatted(org, org.toString().substring(0, 8), org.toString().substring(0, 8)));
      s.executeUpdate("""
          insert into projects (id, "organizationId", name, slug, "createdAt", "updatedAt")
          values ('%s', '%s', 'Phase 10b', 'phase10b-%s', current_timestamp, current_timestamp)"""
          .formatted(project, org, project.toString().substring(0, 8)));
    }
  }

  private static void insertDocument(UUID document, UUID org, UUID project, UUID kb)
      throws SQLException {
    execute(phase5aUrl, """
        insert into public.documents (id, "organizationId", "projectId", "knowledgeBaseId", title,
                                      status, "chunkCount", "createdAt", "updatedAt")
        values (?, ?, ?, ?, 'Phase 10b document', 'READY'::"public"."DocumentStatus", 0,
                (now() at time zone 'UTC'), (now() at time zone 'UTC'))""",
        document, org, project, kb);
  }

  /**
   * A tenant, a project, a customer, a conversation, an agent, an agent version and a run --
   * everything {@code ai_approvals} needs for its composite tenant foreign key.
   *
   * <p>Seeded rather than borrowed from another scenario so the ai_approvals scenarios are
   * independent of execution order.
   */
  private static UUID seedApprovalRunnable(UUID org, UUID project, String tag) throws SQLException {
    UUID user = seedUser(org, tag);
    String cxId = "CX" + String.format("%06d", CONVERSATION_SEQ.incrementAndGet());
    execute(phase5aUrl, """
        insert into public.customers (id, "organizationId", "projectId", "cxId", "displayName",
                                      "createdAt", "updatedAt")
        values (gen_random_uuid(), ?, ?, ?, 'Approval fixture customer',
                (now() at time zone 'UTC'), (now() at time zone 'UTC'))""", org, project, cxId);
    UUID customer = queryUuid(phase5aUrl,
        "select id from public.customers where \"cxId\" = '" + cxId + "'");

    String ticket = "T-" + String.format("%06d", CONVERSATION_SEQ.incrementAndGet());
    execute(phase5aUrl, """
        insert into public.conversations (id, "organizationId", "projectId", "customerId",
                                          "ticketNumber", channel, status, "createdAt", "updatedAt")
        values (gen_random_uuid(), ?, ?, ?, ?, 'MOCK'::"ChannelType",
                'OPEN'::"ConversationStatus", (now() at time zone 'UTC'),
                (now() at time zone 'UTC'))""", org, project, customer, ticket);

    UUID agent = UUID.randomUUID();
    execute(phase5aUrl, """
        insert into public.ai_agents (id, "organizationId", "projectId", name, "isEnabled",
                                      "createdAt", "updatedAt")
        values (?, ?, ?, ?, true, (now() at time zone 'UTC'), (now() at time zone 'UTC'))""",
        agent, org, project, "Approval fixture agent " + tag);

    UUID agentVersion = UUID.randomUUID();
    execute(phase5aUrl, """
        insert into public.ai_agent_versions
             (id, "organizationId", "projectId", "agentId", version, "systemInstructions",
              provider, temperature, "maxTokens", "failoverChain", "enabledTools",
              "knowledgeBaseIds", "minRetrievalScore", "memoryWindow", "createdByUserId", "createdAt")
        values (?, ?, ?, ?, 1, 'fixture agent version', 'LOCAL'::"AIProviderKind", 0.0, 512,
                '[]'::jsonb, '[]'::jsonb, '[]'::jsonb, 0.0, 8, ?,
                (now() at time zone 'UTC'))""",
        agentVersion, org, project, agent, user);

    UUID run = UUID.randomUUID();
    execute(phase5aUrl, """
        insert into public.ai_runs (id, "organizationId", "projectId", "conversationId", "agentId",
                                    "agentVersionId", status, provider, "startedAt")
        values (?, ?, ?, ?, ?, ?, 'RUNNING', 'LOCAL'::"AIProviderKind",
                (now() at time zone 'UTC'))""",
        run, org, project, UUID.fromString(queryString(connect(phase5aUrl),
            "select id::text from public.conversations where \"ticketNumber\" = '" + ticket + "'")),
        agent, agentVersion);

    return run;
  }
}
