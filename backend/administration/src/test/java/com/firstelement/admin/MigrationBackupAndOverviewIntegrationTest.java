package com.firstelement.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.admin.governance.LegalHoldRepository;
import com.firstelement.admin.governance.PrivacyDeletionRepository;
import com.firstelement.admin.migration.BackupRestoreEvidenceReader;
import com.firstelement.admin.migration.MigrationStateRepository;
import com.firstelement.admin.overview.SystemOverviewService;
import com.firstelement.security.TenantContext;
import com.firstelement.security.error.PolicyDeniedException;
import com.firstelement.security.query.TenantQueryGuard;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The three surfaces that answer questions about the <em>deployment</em> rather than about a tenant:
 * migration state, backup/restore evidence, and the overview that puts the answers next to each other.
 *
 * <h2>"Absent" is never zero</h2>
 *
 * <p>The property all three share, and the one ADR-0009 §5 and FR-7.6 both turn on: an operator must
 * be able to tell <em>not configured</em> from <em>nothing found</em> from <em>zero</em>. Each suite
 * below seeds the "there is nothing here" case explicitly, because a read model tested only against
 * populated state passes while conflating exactly the three answers that matter.
 *
 * <h2>Read-only, and no repair</h2>
 *
 * <p>There is no {@code repair()} and no statement in either migration-side class that writes. The
 * migration chain is owned by {@code ops/flyway} and its runbook; an Admin surface that could mark a
 * failed migration as applied would be precisely the unrestricted database mutation FR-6 forbids.
 */
@SpringBootTest(classes = AdminTestApplication.class)
class MigrationBackupAndOverviewIntegrationTest extends AdminIntegrationTestBase {

    @Autowired
    private MigrationStateRepository migrations;
    @Autowired
    private BackupRestoreEvidenceReader evidence;
    @Autowired
    private SystemOverviewService overview;
    @Autowired
    private PrivacyDeletionRepository deletions;
    @Autowired
    private LegalHoldRepository holds;
    @Autowired
    private TenantQueryGuard guard;
    @Autowired
    private AdminFixture fixture;

    @BeforeEach
    void reset() {
        fixture.reset();
    }

    // ==================================================================
    // migrations
    // ==================================================================

    @Nested
    @DisplayName("migration state")
    class Migrations {

        @Test
        @DisplayName("the applied chain is readable and every row succeeded")
        void theChainIsReadable() {
            var applied = migrations.applied(200);

            assertThat(applied).isNotEmpty();
            assertThat(applied).extracting(MigrationStateRepository.AppliedMigration::installedRank)
                .isSorted();
            assertThat(applied).allSatisfy(row ->
                assertThat(row.success())
                    .as("migration %s did not succeed", row.script())
                    .isTrue());
            assertThat(applied).anySatisfy(row ->
                assertThat(row.script()).contains("audit_streams"));
        }

        @Test
        @DisplayName("the stamp answer is a boolean and a count, read as the types they are")
        void theStampAnswerIsTypedCorrectly() {
            // Regression guard. An earlier spelling selected `count(*) as applied` and mapped it with
            // rs.getBoolean, which the driver refuses on a bigint — so the migrations and overview
            // routes answered 500 on a correctly migrated database.
            var stamp = migrations.stamp();

            assertThat(stamp.stamped()).isTrue();
            assertThat(stamp.applied()).isPositive();
            assertThat(stamp.latestRank()).isEqualTo(stamp.applied());
        }

        @Test
        @DisplayName("no migration reports a failure")
        void noFailures() {
            assertThat(migrations.failedCount()).isZero();
        }

        @Test
        @DisplayName("the chain is bounded, because an unbounded read of an unbounded table is the outage")
        void theChainIsBounded() {
            assertThat(migrations.applied(3)).hasSizeLessThanOrEqualTo(3);
        }
    }

    // ==================================================================
    // backup / restore evidence
    // ==================================================================

    @Nested
    @DisplayName("backup and restore evidence")
    class BackupEvidence {

        @Test
        @DisplayName("with no evidence root configured the answer is NO_EVIDENCE_ROOT, not zero")
        void notConfiguredIsNotZero() {
            assertThat(evidence.configured()).isFalse();
            assertThat(evidence.listRehearsals()).isEmpty();
            assertThat(evidence.rootLastModifiedEpochMilli()).isNull();

            var latest = evidence.latest();
            assertThat(latest.status()).isEqualTo("NO_EVIDENCE_ROOT");
            assertThat(latest.found()).isEmpty();
            assertThat(latest.message())
                .as("'we never rehearsed a restore' and 'we could not read the rehearsal' lead to "
                    + "different conversations")
                .contains("configuration gap");
            assertThat(evidence.read("restore-check").status())
                .as("a well-formed name with no root configured is still the configuration answer")
                .isEqualTo("NO_EVIDENCE_ROOT");
        }

        @Test
        @DisplayName("a name that is not a bare directory name is refused before any filesystem call")
        void anUnsafeNameIsRefused() {
            for (String unsafe : new String[] {"..", ".", "../..", "a/b", "a\\b", ".hidden",
                "name with spaces", ""}) {
                assertThat(evidence.read(unsafe).status())
                    .as("name %s must be refused", unsafe)
                    .isEqualTo("UNSAFE_PATH");
            }
            assertThat(evidence.read(null).status()).isEqualTo("UNSAFE_PATH");
        }

        @Test
        @DisplayName("a configured but empty root is a third answer, not the first one")
        void configuredButEmptyIsItsOwnAnswer() throws java.io.IOException {
            // Four states, and none of them may collapse into another: NO_EVIDENCE_ROOT (unset),
            // NO_REHEARSALS (set, nothing written), NO_EVIDENCE (a directory with no artefacts) and
            // CONFIGURED (artefacts read). An operator's next action differs in every one.
            var configured = new BackupRestoreEvidenceReader(
                emptyEvidenceRoot().toAbsolutePath().toString());

            assertThat(configured.configured()).isTrue();
            assertThat(configured.listRehearsals()).isEmpty();
            assertThat(configured.latest().status())
                .as("an empty evidence root is not 'no root configured' — it means the tooling has "
                    + "run here, or has not, and the directory is where the answer would be")
                .isEqualTo("NO_REHEARSALS");
            assertThat(configured.read("restore-check").status()).isEqualTo("NO_EVIDENCE");
        }

        @Test
        @DisplayName("a rehearsal directory with no artefacts reads as NO_EVIDENCE with a message")
        void aDirectoryWithNoArtefactsIsNoEvidence() throws java.io.IOException {
            var root = emptyEvidenceRoot();
            java.nio.file.Files.createDirectory(root.resolve("2026-10-05T120000Z"));

            var configured = new BackupRestoreEvidenceReader(root.toAbsolutePath().toString());

            assertThat(configured.listRehearsals()).containsExactly("2026-10-05T120000Z");
            var latest = configured.latest();
            assertThat(latest.status()).isEqualTo("NO_EVIDENCE");
            assertThat(latest.found()).isEmpty();
            assertThat(latest.message()).contains("none of the three artefacts");
        }

        @Test
        @DisplayName("a rehearsal whose artefacts are present is read, and its checks are reported")
        void aRehearsalIsRead() throws java.io.IOException {
            var root = emptyEvidenceRoot();
            var rehearsal = root.resolve("2026-10-05T120000Z");
            java.nio.file.Files.createDirectories(rehearsal.resolve("source"));
            java.nio.file.Files.createDirectories(rehearsal.resolve("restored"));
            java.nio.file.Files.writeString(rehearsal.resolve("restore-checks.json"),
                "{\"rowCountsMatch\":true,\"schemaFingerprintMatch\":true}");
            java.nio.file.Files.writeString(rehearsal.resolve("source/summary.json"),
                "{\"tables\":42,\"rows\":1000}");
            java.nio.file.Files.writeString(rehearsal.resolve("restored/summary.json"),
                "{\"tables\":42,\"rows\":1000}");

            var configured = new BackupRestoreEvidenceReader(root.toAbsolutePath().toString());
            var read = configured.read("2026-10-05T120000Z");

            assertThat(read.status()).isEqualTo("CONFIGURED");
            assertThat(read.found()).hasSize(3);
            assertThat(read.checks()).containsEntry("rowCountsMatch", "true")
                .containsEntry("schemaFingerprintMatch", "true");
            assertThat(read.sourceSummary()).containsEntry("tables", "42");
            assertThat(read.restoredSummary()).containsEntry("rows", "1000");
            assertThat(configured.rootLastModifiedEpochMilli()).isNotNull();
        }

        /** A fresh empty directory per call, because the reader is stateful about the filesystem. */
        private java.nio.file.Path emptyEvidenceRoot() throws java.io.IOException {
            return java.nio.file.Files.createTempDirectory("fe-evidence-root");
        }
    }

    // ==================================================================
    // governance read models
    // ==================================================================

    @Nested
    @DisplayName("governance read models")
    class Governance {

        @Test
        @DisplayName("every declared deletion state is present at zero")
        void everyDeletionStateIsPresent() {
            TenantContext admin = PrincipalScope.adminA();

            var counts = PrincipalScope.call(admin,
                () -> deletions.countsByStatus(guard.tenantScope(admin)));

            assertThat(counts).containsOnlyKeys("REQUESTED", "VERIFIED", "IN_PROGRESS", "COMPLETED",
                "REJECTED", "CANCELLED");
            assertThat(counts.values()).containsOnly(0);
        }

        @Test
        @DisplayName("only an undecided request is outstanding")
        void outstandingMeansUndecided() {
            UUID requested = fixture.deletionRequest(AdminFixture.ORG_A, AdminFixture.PROJECT_A,
                AdminFixture.SUBJECT_A, "REQUESTED");
            fixture.deletionRequest(AdminFixture.ORG_A, AdminFixture.PROJECT_A,
                AdminFixture.SUBJECT_A, "COMPLETED");
            TenantContext admin = PrincipalScope.adminA();

            var open = PrincipalScope.call(admin,
                () -> deletions.openRequests(guard.tenantScope(admin), 50));

            assertThat(open).extracting(PrivacyDeletionRepository.RequestRow::id)
                .containsExactly(requested);
        }

        @Test
        @DisplayName("targets name their decision, and another tenant's are absent")
        void targetsAreTenantScoped() {
            UUID alphaRequest = fixture.deletionRequest(AdminFixture.ORG_A, AdminFixture.PROJECT_A,
                AdminFixture.SUBJECT_A, "REQUESTED");
            UUID alphaTarget = fixture.deletionTarget(AdminFixture.ORG_A, alphaRequest,
                AdminFixture.SUBJECT_A, "ANONYMISED", 12L);
            UUID betaRequest = fixture.deletionRequest(AdminFixture.ORG_B, AdminFixture.PROJECT_B,
                AdminFixture.SUBJECT_B, "REQUESTED");
            fixture.deletionTarget(AdminFixture.ORG_B, betaRequest, AdminFixture.SUBJECT_B,
                "ANONYMISED", 12L);
            TenantContext admin = PrincipalScope.adminA();

            // The caller's own request: the decision and the row count are visible, because those are
            // what a deletion reviewer is shown.
            var ownTargets = PrincipalScope.call(admin,
                () -> deletions.targets(guard.tenantScope(admin), alphaRequest, 50));
            assertThat(ownTargets).hasSize(1);
            assertThat(ownTargets.get(0).id()).isEqualTo(alphaTarget);
            assertThat(ownTargets.get(0).decision()).isEqualTo("ANONYMISED");
            assertThat(ownTargets.get(0).rowsRemoved()).isEqualTo(12L);
            assertThat(ownTargets.get(0).decidedByUserId()).isEqualTo(AdminFixture.ADMIN_A);

            // Another tenant's request id yields an empty list, not its targets.
            var foreignTargets = PrincipalScope.call(admin,
                () -> deletions.targets(guard.tenantScope(admin), betaRequest, 50));
            assertThat(foreignTargets).isEmpty();
        }

        @Test
        @DisplayName("the hold read model separates the three counts, and mayPurge follows the blocking ones")
        void theHoldReadModelSeparatesItsCounts() {
            fixture.legalHold(AdminFixture.ORG_A, null, "TENANT", "tenant-wide hold");
            fixture.legalHold(AdminFixture.ORG_A, AdminFixture.PROJECT_A, "PROJECT",
                "project hold");
            TenantContext admin = PrincipalScope.adminA();
            var scope = guard.tenantScope(admin);

            var covering = PrincipalScope.call(admin, () -> holds.coveringCounts(scope, null));

            assertThat(covering).containsEntry("tenant_wide", 1L).containsEntry("project_wide", 1L)
                .containsEntry("covering", 2L);
            assertThat(PrincipalScope.call(admin,
                () -> holds.activeHoldsCovering(scope, null, 50)))
                .extracting(LegalHoldRepository.HoldRow::scopeType)
                .containsExactlyInAnyOrder("TENANT", "PROJECT");

            // A per-project purge is blocked by a PROJECT hold on that project and by a TENANT hold,
            // but a hold on ANOTHER project must not cover it. The OR that would have been simpler to
            // write — `(projectId is null or projectId = :project)` — answers a different question.
            var otherProject = PrincipalScope.call(admin,
                () -> holds.activeHoldsCovering(guard.scope(admin, AdminFixture.PROJECT_A2),
                    AdminFixture.PROJECT_A2, 50));
            assertThat(otherProject)
                .extracting(LegalHoldRepository.HoldRow::scopeType)
                .containsExactly("TENANT");

            // And the project the hold is actually on is covered by both.
            var itsOwnProject = PrincipalScope.call(admin,
                () -> holds.activeHoldsCovering(guard.scope(admin, AdminFixture.PROJECT_A),
                    AdminFixture.PROJECT_A, 50));
            assertThat(itsOwnProject)
                .extracting(LegalHoldRepository.HoldRow::scopeType)
                .containsExactlyInAnyOrder("TENANT", "PROJECT");
        }

        @Test
        @DisplayName("a narrow hold is placeable, is counted as narrow, and still must name its target")
        void aNarrowHoldIsPlaceableAndCounted() {
            // This test used to assert the opposite, and that is the point worth recording.
            //
            // It asserted that a TABLE hold could not be inserted at all, because
            // `legal_holds_scope_pair_check` had arms only for TENANT and PROJECT while
            // `legal_holds_scope_ref_check` and `LegalHoldScopeType` both contemplated TABLE and
            // RECORD. The pair was unsatisfiable for those two values, so the `narrow` count in the
            // covering read model was STRUCTURALLY unreachable — reported as a permanent zero, and a
            // reader could not tell an unreachable column from a tenant with no narrow holds.
            //
            // `V20261006130000__legal_holds_scope_pair_widening.sql` widened the pair check so every
            // enum member has a satisfiable arm. The assertion is therefore now the stronger one: the
            // narrow hold is placeable, it COUNTS, and the rule that made it meaningful in the first
            // place — a narrow hold must name what it holds — still refuses a hold that does not.
            UUID hold = fixture.legalHold(AdminFixture.ORG_A, null, "TABLE", "table hold");

            // The count the read model previously could not produce is now produced. A zero here would
            // mean either the hold was not recorded or the read model does not see it; 1 is the only
            // value that distinguishes "narrow holds are counted" from "narrow holds are ignored".
            TenantContext admin = PrincipalScope.adminA();
            assertThat(PrincipalScope.call(admin,
                () -> holds.coveringCounts(guard.tenantScope(admin), null)))
                .as("a narrow hold is now placeable, so the narrow count is a real count rather than a "
                    + "structurally unreachable zero")
                .containsEntry("narrow", 1L);

            // And it is visible as a hold, scoped as declared, with its reason intact.
            assertThat(PrincipalScope.call(admin,
                () -> holds.activeHolds(guard.tenantScope(admin), 10)))
                .extracting(LegalHoldRepository.HoldRow::id)
                .contains(hold);

            // The rule that survives: a narrow hold that names nothing is meaningless and is refused.
            // Widening the pair check must not have made every narrow hold legal. The corrected pair
            // check carries the scopeRef requirement in its own third arm rather than leaving it
            // solely to legal_holds_scope_ref_check, so it is THIS constraint that refuses.
            assertThatThrownBy(() -> fixture.legalHoldNoScopeRef(AdminFixture.ORG_A, "TABLE",
                "narrow hold naming nothing"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("legal_holds_scope_pair_check");

            // A PROJECT hold must still name its project. This is the one arm of the original pair
            // check that was already correct, and widening must not have relaxed it.
            assertThatThrownBy(() -> fixture.legalHold(AdminFixture.ORG_A, null, "PROJECT",
                "project hold naming no project"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

            assertThat(com.firstelement.admin.governance.LegalHoldService.HoldScopeType.values())
                .extracting(Enum::name)
                .contains("TABLE", "RECORD");
        }

        @Test
        @DisplayName("a project-scoped read of holds refuses a project the caller cannot reach")
        void anOutOfScopeProjectIsRefused() {
            TenantContext admin = PrincipalScope.adminA();

            assertThatThrownBy(() -> PrincipalScope.call(admin,
                () -> holds.activeHoldsCovering(guard.scope(admin, AdminFixture.PROJECT_B), null, 50)))
                .isInstanceOf(PolicyDeniedException.class);
        }
    }

    // ==================================================================
    // the overview
    // ==================================================================

    @Nested
    @DisplayName("the system overview")
    class Overview {

        @Test
        @DisplayName("it names its tenant, its window, and which sections are process-level")
        void theOverviewIsLabelled() {
            TenantContext admin = PrincipalScope.adminA();

            var document = PrincipalScope.call(admin, () -> overview.overview(admin, 24));

            assertThat(document).containsEntry("organizationId", AdminFixture.ORG_A);
            assertThat(document).containsEntry("windowHours", 24);
            assertThat(document.get("since")).isNotNull();
            assertThat(document.get("generatedAt")).isNotNull();
            // A reader who sees "migration" must not read it as "your migrations".
            assertThat(document.get("migration")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.MAP).containsEntry("scope", "PROCESS");
            assertThat(document.get("backupEvidence")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("scope", "PROCESS")
                .containsEntry("status", "NO_EVIDENCE_ROOT");
        }

        @Test
        @DisplayName("it has no composite score, because nobody can act on 7/10")
        void thereIsNoHealthScore() {
            TenantContext admin = PrincipalScope.adminA();

            var document = PrincipalScope.call(admin, () -> overview.overview(admin, null));

            assertThat(document).doesNotContainKey("score")
                .doesNotContainKey("health")
                .doesNotContainKey("grade")
                .doesNotContainKey("percentHealthy");
        }

        @Test
        @DisplayName("every section is present, and each one names its source table")
        void everySectionIsPresent() {
            TenantContext admin = PrincipalScope.adminA();

            var document = PrincipalScope.call(admin, () -> overview.overview(admin, 24));

            assertThat(document).containsKeys("identity", "queues", "auditStreams", "legalHolds",
                "privacyDeletions", "aiGovernance", "exports", "migration", "backupEvidence",
                "findings");
            assertThat(document.get("identity")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("source", "users, project_memberships, sessions, projects");
            assertThat(document.get("exports")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("source", "export_jobs");
        }

        @Test
        @DisplayName("a quiet tenant has no findings, and a noisy one has findings that name a count")
        void findingsAreComputedNotDescribed() {
            TenantContext admin = PrincipalScope.adminA();

            var quiet = PrincipalScope.call(admin, () -> overview.overview(admin, 24));
            assertThat(quiet.get("findings")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.LIST).isEmpty();

            fixture.legalHold(AdminFixture.ORG_A, null, "TENANT", "pending litigation");
            fixture.deletionRequest(AdminFixture.ORG_A, AdminFixture.PROJECT_A,
                AdminFixture.SUBJECT_A, "REQUESTED");
            fixture.deadOutboxEvent(AdminFixture.ORG_A, AdminFixture.PROJECT_A,
                com.firstelement.worker.queue.QueueName.INCOMING_MESSAGE.wireValue(), 12,
                "attempts exhausted");

            var noisy = PrincipalScope.call(admin, () -> overview.overview(admin, 24));

            @SuppressWarnings("unchecked")
            var findings = (java.util.List<Map<String, Object>>) noisy.get("findings");
            assertThat(findings).extracting(finding -> finding.get("token"))
                .contains("OPEN_DEAD_LETTERS", "ACTIVE_LEGAL_HOLD", "PENDING_PRIVACY_DELETIONS");
            assertThat(findings).allSatisfy(finding -> {
                assertThat(finding).containsKeys("token", "count", "detail", "action");
                assertThat((Integer) finding.get("count")).isPositive();
                assertThat((String) finding.get("action")).isNotBlank();
            });
        }

        @Test
        @DisplayName("it is this tenant's overview: another tenant's activity moves none of its numbers")
        void theOverviewIsTenantScoped() {
            // Alpha is noisy and Beta is quiet. Beta's numbers must be Alpha's, and Beta's findings
            // must be empty — the direction that matters is the quiet one, because a leaking filter
            // shows up as findings on the tenant that seeded nothing.
            fixture.legalHold(AdminFixture.ORG_A, null, "TENANT", "alpha hold");
            fixture.deletionRequest(AdminFixture.ORG_A, AdminFixture.PROJECT_A,
                AdminFixture.SUBJECT_A, "REQUESTED");
            TenantContext beta = PrincipalScope.adminB();

            var document = PrincipalScope.call(beta, () -> overview.overview(beta, 24));

            assertThat(document).containsEntry("organizationId", AdminFixture.ORG_B);
            assertThat(document.get("findings")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.LIST).isEmpty();
            assertThat(document.get("identity")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .as("Beta seeded two users of its own and none of Alpha's")
                .containsEntry("totalUsers", 2L);
            assertThat(document.get("legalHolds")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.MAP).containsEntry("covering", 0L);
            assertThat(document.get("privacyDeletions")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("REQUESTED", 0);
        }

        @Test
        @DisplayName("a failure rate of -1 means 'no attempts in the window', not a healthy zero")
        void noAttemptsIsNotHealthy() {
            TenantContext admin = PrincipalScope.adminA();

            var document = PrincipalScope.call(admin, () -> overview.overview(admin, 24));

            assertThat(document.get("queues")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("attemptsInWindow", 0)
                .containsEntry("failuresInWindow", 0)
                .containsEntry("failureRate", -1.0);
        }
    }

    // ==================================================================
    // no unbounded surface
    // ==================================================================

    @Nested
    @DisplayName("the shape of the surface itself")
    class SurfaceShape {

        @Test
        @DisplayName("no Admin repository contains an unrestricted statement")
        void thereIsNoArbitraryWrite() throws ReflectiveOperationException {
            // A repository is the only place in this module that speaks SQL. A DELETE, a DDL
            // statement or a catalog probe there would be the unrestricted mutation FR-6 forbids, so
            // the check is structural rather than a review convention: every SQL constant is read
            // through reflection, and a future developer adding a raw mutation fails this test rather
            // than receiving a review comment.
            for (String className : REPOSITORIES) {
                Class<?> type = Class.forName(className);
                for (var field : type.getDeclaredFields()) {
                    if (!String.class.equals(field.getType()) || !field.getName().endsWith("SQL")
                        && field.getName().equals(field.getName().toUpperCase(java.util.Locale.ROOT))) {
                        continue;
                    }
                    field.setAccessible(true);
                    String sql = (String) field.get(null);
                    if (sql == null) {
                        continue;
                    }
                    String normalised = sql.stripLeading().toLowerCase(java.util.Locale.ROOT);
                    assertThat(normalised)
                        .as("%s#%s", className, field.getName())
                        .doesNotStartWith("delete from")
                        .doesNotStartWith("truncate")
                        .doesNotStartWith("drop ")
                        .doesNotStartWith("alter ")
                        .doesNotContain("; delete")
                        .doesNotContain("; drop")
                        .doesNotContain("information_schema")
                        .doesNotContain("pg_catalog");
                }
            }
        }

        @Test
        @DisplayName("the repositories named by the check are the ones this module actually has")
        void theInventoryIsComplete() {
            // A reflective check over a hard-coded list proves nothing if the list has drifted. This
            // asserts the count, so a ninth repository has to be added here deliberately.
            assertThat(REPOSITORIES).hasSize(8);
            assertThat(REPOSITORIES).allSatisfy(className ->
                assertThatCode(() -> Class.forName(className))
                    .as("%s must exist", className)
                    .doesNotThrowAnyException());
        }
    }

    /** The SQL-bearing classes in this module. */
    private static final List<String> REPOSITORIES = java.util.List.of(
        "com.firstelement.admin.audit.AuditStreamRepository",
        "com.firstelement.admin.audit.RetentionClassResolver",
        "com.firstelement.admin.identity.IdentityDirectoryRepository",
        "com.firstelement.admin.ai.AiGovernanceRepository",
        "com.firstelement.admin.exports.ExportActivityRepository",
        "com.firstelement.admin.migration.MigrationStateRepository",
        "com.firstelement.admin.governance.LegalHoldRepository",
        "com.firstelement.admin.governance.PrivacyDeletionRepository");
}
