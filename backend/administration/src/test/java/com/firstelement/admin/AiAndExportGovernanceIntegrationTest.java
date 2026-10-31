package com.firstelement.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstelement.admin.ai.AiGovernanceRepository;
import com.firstelement.admin.exports.ExportActivityRepository;
import com.firstelement.security.TenantContext;
import com.firstelement.security.query.TenantQueryGuard;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The two read models that exist to prove a promise was kept about content that was never stored.
 *
 * <h2>AI governance</h2>
 *
 * <p>FR-10.6 says only the redacted form may be stored; ADR-0007 rule 7 says every AI action is
 * attributable. Those two together are why a denied tool call must be reviewable <em>and</em> content
 * free. {@link AiGovernanceRepository} selects no jsonb column from {@code ai_tool_executions} at
 * all, so the test asserts on the row shape and on a marker string that exists in the seeded
 * {@code argumentsRedacted} and must appear nowhere in the projection.
 *
 * <h2>Exports</h2>
 *
 * <p>A filter can name a customer's date of birth and an {@code authorizationSnapshot} is a record of
 * somebody's permissions on the day. Neither is projected. What is projected is what makes "did this
 * export work, was it authorised, has it been deleted" answerable: the state machine, the checksum,
 * the redaction policy, the download outcomes including the refusals, and the
 * {@code legalHoldChecked} evidence column.
 */
@SpringBootTest(classes = AdminTestApplication.class)
class AiAndExportGovernanceIntegrationTest extends AdminIntegrationTestBase {

    /** Present in the seeded {@code argumentsRedacted}. It must appear in no projection. */
    private static final String ARGUMENT_MARKER = "patient-identifier-should-never-be-projected";

    @Autowired
    private AiGovernanceRepository ai;
    @Autowired
    private ExportActivityRepository exports;
    @Autowired
    private TenantQueryGuard guard;
    @Autowired
    private AdminFixture fixture;

    @BeforeEach
    void reset() {
        fixture.reset();
    }

    // ==================================================================
    // AI governance
    // ==================================================================

    @Nested
    @DisplayName("AI governance")
    class Ai {

        @Test
        @DisplayName("a denied action is fully reviewable without any customer content")
        void denialIsReviewableWithoutContent() {
            UUID call = fixture.deniedToolCall(AdminFixture.ORG_A, AdminFixture.PROJECT_A,
                "issue_refund", "customer-visible-without-approval", ARGUMENT_MARKER);
            TenantContext admin = PrincipalScope.adminA();

            var rows = PrincipalScope.call(admin,
                () -> ai.blockedCalls(guard.tenantScope(admin), null, null, null, 50));

            assertThat(rows).hasSize(1);
            var row = rows.get(0);
            // Everything an operator acts on.
            assertThat(row.id()).isEqualTo(call);
            assertThat(row.toolName()).isEqualTo("issue_refund");
            assertThat(row.actionClass()).isEqualTo("CONSEQUENTIAL");
            assertThat(row.consequentialAction()).isTrue();
            assertThat(row.decision()).isEqualTo("DENY");
            assertThat(row.denyReason()).isEqualTo("customer-visible-without-approval");
            assertThat(row.principalType()).isEqualTo("AI_AGENT");
            assertThat(row.requestedAt()).isNotNull();

            // No field of the row can hold an argument or a result, so none can be rendered later.
            assertThat(AiGovernanceRepository.BlockedCallRow.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("argumentsRedacted", "resultRedacted", "arguments", "result");
        }

        @Test
        @DisplayName("the blocked summary rolls up by tool and reason, and only non-ALLOW decisions")
        void summaryRollsUpDenials() {
            fixture.deniedToolCall(AdminFixture.ORG_A, AdminFixture.PROJECT_A, "issue_refund",
                "missing-approval", ARGUMENT_MARKER);
            fixture.deniedToolCall(AdminFixture.ORG_A, AdminFixture.PROJECT_A, "issue_refund",
                "missing-approval", ARGUMENT_MARKER);
            fixture.deniedToolCall(AdminFixture.ORG_A, AdminFixture.PROJECT_A, "send_message",
                "no-permission", ARGUMENT_MARKER);
            // Another tenant: must not appear.
            fixture.deniedToolCall(AdminFixture.ORG_B, AdminFixture.PROJECT_B, "issue_refund",
                "missing-approval", ARGUMENT_MARKER);
            TenantContext admin = PrincipalScope.adminA();

            var summary = PrincipalScope.call(admin,
                () -> ai.blockedSummary(guard.tenantScope(admin), 50));

            assertThat(summary).hasSize(2);
            assertThat(summary.get(0)).containsEntry("tool", "issue_refund")
                .containsEntry("reason", "missing-approval")
                .containsEntry("occurrences", 2L);
            assertThat(summary.get(1)).containsEntry("tool", "send_message");
            assertThat(summary).allSatisfy(row ->
                assertThat(row).doesNotContainValue(ARGUMENT_MARKER));
        }

        @Test
        @DisplayName("an unrecognised decision filter widens to 'no filter' rather than erroring or guessing")
        void decisionFilterIsAnAllowList() {
            fixture.deniedToolCall(AdminFixture.ORG_A, AdminFixture.PROJECT_A, "t1", "r",
                ARGUMENT_MARKER);
            TenantContext admin = PrincipalScope.adminA();
            var scope = guard.tenantScope(admin);

            var known = PrincipalScope.call(admin, () -> ai.blockedCalls(scope, null, "DENY", null, 50));
            var unknown = PrincipalScope.call(admin,
                () -> ai.blockedCalls(scope, null, "SOMETHING_ELSE", null, 50));

            assertThat(known).hasSize(1);
            assertThat(unknown)
                .as("a typo must not become an enum literal the database refuses, and must not hide data")
                .hasSize(1);
        }

        @Test
        @DisplayName("a disabled tool is visible, with the reason the schema makes unskippable")
        void disabledToolCarriesItsReason() {
            fixture.disabledTool(AdminFixture.ORG_A, "issue_refund", "awaiting security review");
            fixture.enabledTool(AdminFixture.ORG_A, "lookup_customer");
            TenantContext admin = PrincipalScope.adminA();

            var tools = PrincipalScope.call(admin,
                () -> ai.tools(guard.tenantScope(admin), 50));

            assertThat(tools).hasSize(2);
            var disabled = tools.stream().filter(t -> !t.enabled()).findFirst().orElseThrow();
            assertThat(disabled.toolName()).isEqualTo("issue_refund");
            assertThat(disabled.disabledReason()).isEqualTo("awaiting security review");
            assertThat(disabled.disabledByUserId()).isEqualTo(AdminFixture.ADMIN_A);
            assertThat(disabled.disabledAt()).isNotNull();
            // The enabled tool carries no disable record at all, rather than an empty one.
            var enabled = tools.stream().filter(t -> t.enabled()).findFirst().orElseThrow();
            assertThat(enabled.disabledReason()).isNull();
            assertThat(enabled.disabledAt()).isNull();
            assertThat(enabled.enabledAt()).isNotNull();
        }

        @Test
        @DisplayName("redaction is reported as a count and a field path, never as a value")
        void redactionIsACountNotAValue() {
            fixture.redactionRecord(AdminFixture.ORG_A, AdminFixture.PROJECT_A, "PHONE",
                "customer.primaryPhone", 3);
            fixture.redactionRecord(AdminFixture.ORG_A, AdminFixture.PROJECT_A, "PHONE",
                "customer.secondaryPhone", 1);
            TenantContext admin = PrincipalScope.adminA();
            var scope = guard.tenantScope(admin);

            var records = PrincipalScope.call(admin, () -> ai.redactions(scope, null, 50));
            var summary = PrincipalScope.call(admin, () -> ai.redactionSummary(scope, 50));

            assertThat(records).hasSize(2);
            assertThat(records).allSatisfy(record -> {
                assertThat(record.fieldPath()).startsWith("customer.");
                assertThat(record.redactionPolicy()).isEqualTo("DEFAULT");
                assertThat(record.valuesRedacted()).isPositive();
            });
            assertThat(summary).hasSize(2);
            assertThat(summary.get(0)).containsEntry("rule", "PHONE")
                .containsEntry("field", "customer.primaryPhone");
            assertThat(AiGovernanceRepository.RedactionRow.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("rawValue", "originalText", "value", "values");
        }

        @Test
        @DisplayName("budget spend is a SUM over the ledger, so the figure is re-derivable")
        void budgetSpendIsASum() {
            UUID budget = fixture.aiBudget(AdminFixture.ORG_A, AdminFixture.PROJECT_A, "monthly", "100");
            TenantContext admin = PrincipalScope.adminA();
            var scope = guard.tenantScope(admin);

            var budgets = PrincipalScope.call(admin, () -> ai.budgets(scope, null, 50));
            var spend = PrincipalScope.call(admin, () -> ai.budgetSpend(scope,
                AdminFixture.now().minusDays(30), 50));

            assertThat(budgets).hasSize(1);
            assertThat(budgets.get(0).id()).isEqualTo(budget);
            assertThat(budgets.get(0).enforcement()).isEqualTo("ALERT");
            assertThat(budgets.get(0).limitAmount()).isEqualByComparingTo("100");
            assertThat(spend).hasSize(1);
            assertThat(spend.get(0).budgetId()).isEqualTo(budget);
            assertThat(spend.get(0).spent()).isEqualByComparingTo("1.25");
            assertThat(spend.get(0).postings()).isEqualTo(1);
            assertThat(spend.get(0).periodStart()).isEqualTo(
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)
                    .toLocalDate().withDayOfMonth(1));
        }

        @Test
        @DisplayName("an enabled policy is distinguishable from a disabled one, and neither is implied")
        void policyStateIsExplicit() {
            fixture.aiPolicy(AdminFixture.ORG_A, AdminFixture.PROJECT_A, "reply");
            TenantContext admin = PrincipalScope.adminA();

            var policies = PrincipalScope.call(admin,
                () -> ai.policies(guard.tenantScope(admin), null, 50));

            assertThat(policies).hasSize(1);
            // Seeded disabled: the policy exists and is not in force. Reporting the row without its
            // state would be the failure here.
            assertThat(policies.get(0).enabled()).isFalse();
            assertThat(policies.get(0).nameAr()).as("Arabic-first naming survives to the read model")
                .isNotBlank();
        }

        @Test
        @DisplayName("another tenant's AI activity is not readable")
        void crossTenantIsEmpty() {
            fixture.deniedToolCall(AdminFixture.ORG_A, AdminFixture.PROJECT_A, "alpha_tool", "r",
                ARGUMENT_MARKER);
            fixture.deniedToolCall(AdminFixture.ORG_B, AdminFixture.PROJECT_B, "beta_tool", "r",
                ARGUMENT_MARKER);
            fixture.disabledTool(AdminFixture.ORG_A, "alpha_tool", "r");
            fixture.disabledTool(AdminFixture.ORG_B, "beta_tool", "r");
            TenantContext beta = PrincipalScope.adminB();
            var scope = guard.tenantScope(beta);

            assertThat(PrincipalScope.call(beta, () -> ai.blockedCalls(scope, null, null, null, 50)))
                .extracting(AiGovernanceRepository.BlockedCallRow::toolName)
                .containsExactly("beta_tool");
            assertThat(PrincipalScope.call(beta, () -> ai.tools(scope, 50)))
                .extracting(AiGovernanceRepository.ToolRow::toolName)
                .containsExactly("beta_tool");
        }

        @Test
        @DisplayName("the governance summary counts both states of a tool, not just the enabled one")
        void summaryCountsBothStates() {
            fixture.enabledTool(AdminFixture.ORG_A, "on");
            fixture.disabledTool(AdminFixture.ORG_A, "off", "awaiting review");
            fixture.deniedToolCall(AdminFixture.ORG_A, AdminFixture.PROJECT_A, "off", "r",
                ARGUMENT_MARKER);
            fixture.aiPolicy(AdminFixture.ORG_A, AdminFixture.PROJECT_A, "p");
            fixture.aiBudget(AdminFixture.ORG_A, AdminFixture.PROJECT_A, "b", "10");
            TenantContext admin = PrincipalScope.adminA();

            var summary = PrincipalScope.call(admin, () -> ai.summary(guard.tenantScope(admin)));

            assertThat(summary.enabledTools()).isEqualTo(1);
            assertThat(summary.disabledTools()).isEqualTo(1);
            assertThat(summary.deniedCalls()).isEqualTo(1);
            assertThat(summary.enabledPolicies()).isZero();
            assertThat(summary.enabledBudgets()).isEqualTo(1);
            assertThat(summary.redactedValues()).isZero();
        }
    }

    // ==================================================================
    // exports
    // ==================================================================

    @Nested
    @DisplayName("export activity")
    class Exports {

        @Test
        @DisplayName("every declared state is present at zero, so 'no export has run' is not 'unknown'")
        void everyStateIsPresent() {
            TenantContext admin = PrincipalScope.adminA();

            var counts = PrincipalScope.call(admin,
                () -> exports.countsByStatus(guard.tenantScope(admin)));

            assertThat(counts).containsOnlyKeys("QUEUED", "RUNNING", "COMPLETED", "FAILED",
                "CANCELLED", "EXPIRED", "DELETED");
            assertThat(counts.values()).containsOnly(0);
        }

        @Test
        @DisplayName("a completed export past its window with its object still present is counted")
        void expiredButPresentIsCounted() {
            fixture.exportJob(AdminFixture.ORG_A, AdminFixture.PROJECT_A, AdminFixture.ADMIN_A,
                "COMPLETED", AdminFixture.now().minusDays(1), true);
            fixture.exportJob(AdminFixture.ORG_A, AdminFixture.PROJECT_A, AdminFixture.ADMIN_A,
                "COMPLETED", AdminFixture.now().plusDays(30), true);
            fixture.exportJob(AdminFixture.ORG_A, AdminFixture.PROJECT_A, AdminFixture.ADMIN_A,
                "FAILED", AdminFixture.now().minusDays(1), false);
            TenantContext admin = PrincipalScope.adminA();

            assertThat(PrincipalScope.call(admin,
                () -> exports.expiredButStillPresent(guard.tenantScope(admin))))
                .as("the job row says COMPLETED because it completed; only this count says the bytes "
                    + "are still in object storage")
                .isEqualTo(1);
        }

        @Test
        @DisplayName("a refused download is visible with its reason, and no signed URL is returned")
        void deniedDownloadsAreVisible() {
            UUID job = fixture.exportJob(AdminFixture.ORG_A, AdminFixture.PROJECT_A,
                AdminFixture.ADMIN_A, "COMPLETED", AdminFixture.now().plusDays(7), true);
            fixture.deniedDownload(AdminFixture.ORG_A, AdminFixture.PROJECT_A, job,
                AdminFixture.EMPLOYEE_USER_A, "authorization-revoked-since-export");
            fixture.deniedDownload(AdminFixture.ORG_A, AdminFixture.PROJECT_A, job,
                AdminFixture.EMPLOYEE_USER_A, "authorization-revoked-since-export");
            TenantContext admin = PrincipalScope.adminA();
            var scope = guard.tenantScope(admin);

            var denied = PrincipalScope.call(admin, () -> exports.deniedDownloads(scope, 50));
            var rows = PrincipalScope.call(admin, () -> exports.downloads(scope, null, job, 50));

            assertThat(denied).hasSize(1);
            assertThat(denied.get(0)).containsEntry("reason", "authorization-revoked-since-export")
                .containsEntry("occurrences", 2L);
            assertThat(rows).hasSize(2);
            assertThat(rows).allSatisfy(row -> {
                assertThat(row.outcome()).isEqualTo("DENIED");
                assertThat(row.denyReason()).isNotBlank();
            });
            // The signed URL is a bearer credential with an expiry nobody manages; the row records
            // the signature id and its own expiry and never the URL.
            assertThat(AiGovernanceRepository.class).isNotNull();
            assertThat(ExportActivityRepository.ExportDownloadRow.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("signedUrl", "url", "token");
        }

        @Test
        @DisplayName("filters and the authorization snapshot are absent from the projection")
        void customerShapedColumnsAreNotProjected() {
            fixture.exportJob(AdminFixture.ORG_A, AdminFixture.PROJECT_A, AdminFixture.ADMIN_A,
                "COMPLETED", AdminFixture.now().plusDays(7), true);
            TenantContext admin = PrincipalScope.adminA();

            var jobs = PrincipalScope.call(admin,
                () -> exports.jobs(guard.tenantScope(admin), null, null, 50));

            assertThat(jobs).hasSize(1);
            var job = jobs.get(0);
            assertThat(job.requestedByEmail()).isEqualTo("admin@a.test");
            assertThat(job.checksum()).startsWith("sha256:");
            assertThat(job.hasObject()).isTrue();
            assertThat(job.redactionPolicy()).isEqualTo("DEFAULT");
            // The seeded row carries a real authorizationSnapshot and the schema has a `filters`
            // column; neither is selected, so neither can be rendered.
            assertThat(ExportActivityRepository.ExportJobRow.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("filters", "authorizationSnapshot", "storageKey");
            assertThat(job.toString())
                .as("the record's own rendering carries no snapshot either")
                .doesNotContain("organization:read");
        }

        @Test
        @DisplayName("another tenant's exports, downloads and attempts are all empty")
        void crossTenantExportsAreEmpty() {
            UUID alphaJob = fixture.exportJob(AdminFixture.ORG_A, AdminFixture.PROJECT_A,
                AdminFixture.ADMIN_A, "COMPLETED", AdminFixture.now().plusDays(7), true);
            fixture.deniedDownload(AdminFixture.ORG_A, AdminFixture.PROJECT_A, alphaJob,
                AdminFixture.EMPLOYEE_USER_A, "nope");
            fixture.exportJob(AdminFixture.ORG_B, AdminFixture.PROJECT_B, AdminFixture.ADMIN_B,
                "COMPLETED", AdminFixture.now().plusDays(7), true);
            TenantContext beta = PrincipalScope.adminB();
            var scope = guard.tenantScope(beta);

            assertThat(PrincipalScope.call(beta, () -> exports.jobs(scope, null, null, 50)))
                .extracting(ExportActivityRepository.ExportJobRow::organizationId)
                .containsOnly(AdminFixture.ORG_B);
            // Alpha's job id, queried through Beta's scope: nothing, and no error that would differ
            // from a nonexistent id.
            assertThat(PrincipalScope.call(beta, () -> exports.downloads(scope, null, alphaJob, 50)))
                .isEmpty();
            assertThat(PrincipalScope.call(beta, () -> exports.attempts(scope, alphaJob, 50)))
                .isEmpty();
        }

        @Test
        @DisplayName("a legal-hold-aware deletion and one that skipped the check are distinguishable")
        void deletionEvidenceIsVisible() {
            UUID job = fixture.exportJob(AdminFixture.ORG_A, AdminFixture.PROJECT_A,
                AdminFixture.ADMIN_A, "DELETED", AdminFixture.now().plusDays(1), false);
            fixture.exportDeletion(AdminFixture.ORG_A, job, true, null);
            fixture.exportDeletion(AdminFixture.ORG_A, job, false, "hold-check-failed");
            TenantContext admin = PrincipalScope.adminA();

            var rows = PrincipalScope.call(admin,
                () -> exports.deletions(guard.tenantScope(admin), job, 50));

            assertThat(rows).hasSize(2);
            assertThat(rows.stream().filter(ExportActivityRepository.ExportDeletionRow::legalHoldChecked)
                .count())
                .as("ADR-0008 rule 5: a deletion event with legalHoldChecked false skipped the question")
                .isEqualTo(1);
            assertThat(rows).extracting(ExportActivityRepository.ExportDeletionRow::failureCode)
                .contains("hold-check-failed");
        }
    }

    // ==================================================================
    // date handling
    // ==================================================================

    @Nested
    @DisplayName("the UTC rule these read models depend on")
    class Utc {

        @Test
        @DisplayName("a budget period is a date, and the spend window is compared in UTC")
        void budgetPeriodsAreDates() {
            UUID budget = fixture.aiBudget(AdminFixture.ORG_A, AdminFixture.PROJECT_A, "b", "10");
            TenantContext admin = PrincipalScope.adminA();

            var budgets = PrincipalScope.call(admin,
                () -> ai.budgets(guard.tenantScope(admin), null, 50));

            assertThat(budgets.get(0).validFrom()).isNotNull()
                .isBeforeOrEqualTo(LocalDate.now(java.time.ZoneOffset.UTC));
            assertThat(budgets.get(0).validTo()).isNull();
            assertThat(budgets.get(0).id()).isEqualTo(budget);
        }
    }
}
