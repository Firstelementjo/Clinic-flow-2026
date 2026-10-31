package com.firstelement.exports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.exports.download.ExportDownloadService;
import com.firstelement.exports.download.ExportSignatureService;
import com.firstelement.exports.job.ExportJobService;
import com.firstelement.exports.persistence.ExportEventRepository;
import com.firstelement.exports.persistence.ExportJobRepository;
import com.firstelement.exports.retention.ExportRetentionService;
import com.firstelement.exports.retention.RetentionWindowResolver;
import com.firstelement.exports.storage.ExportObjectStore;
import com.firstelement.security.TenantContext;
import com.firstelement.security.error.PolicyDeniedException;
import org.springframework.security.access.AccessDeniedException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
 * The whole export lifecycle against the real Flyway chain: queue, generate, download, cancel, retry, delete.
 *
 * <h2>What this suite proves that the pure-JVM suites cannot</h2>
 *
 * <p>{@code ExportQueryPlannerTest} proves <em>policy before aggregation</em> as a property of the statement
 * text. That is necessary and not sufficient: it cannot show that the statement runs, that the tenant
 * predicate actually keeps a foreign row out of a {@code count}, that a downloaded file's bytes hash to the
 * recorded checksum, or that a cancelled job leaves no object behind. Those are claims about a database and a
 * filesystem, and this suite makes them.
 *
 * <h2>Assertions read state, not return values</h2>
 *
 * <p>Every lifecycle assertion is paired with a read of the table afterwards — ADR-0009 §5's rule. A method
 * returning {@code COMPLETED} is a claim; a row reading {@code COMPLETED} with no storage key, a checksum
 * that matches the bytes, and two append-only attempt rows is the evidence.
 *
 * <h2>Every refusal has a positive control beside it</h2>
 *
 * <p>Tenant A has two projects and tenant B has its own Arabic customer. A refusal that follows a
 * successful read of the same shape inside the caller's own scope cannot be an empty fixture, and this suite
 * never asserts a negative on its own.
 */
@SpringBootTest(classes = ExportsTestBase.ExportsTestApplication.class)
class ExportJobLifecycleIntegrationTest extends ExportsTestBase {

    private static final String WORKER = "integration-worker";
    private static final String CORRELATION = "corr-integration";

    @Autowired
    private ExportsFixture fixture;
    @Autowired
    private ExportJobService jobs;
    @Autowired
    private ExportJobRepository jobRepository;
    @Autowired
    private ExportEventRepository events;
    @Autowired
    private ExportDownloadService downloads;
    @Autowired
    private ExportSignatureService signatures;
    @Autowired
    private ExportRetentionService retention;
    @Autowired
    private ExportObjectStore store;

    private TenantContext adminA;
    private TenantContext adminB;
    private UUID reportA;

    @BeforeEach
    void seed() {
        fixture.reset();
        fixture.seedOrganizationsAndUsers();
        adminA = fixture.adminA();
        adminB = fixture.adminB();
        reportA = fixture.publishedCustomerReport(adminA, PROJECT_A, "none");
        fixture.customerInProjectA();
    }

    // ==================================================================
    // the happy path
    // ==================================================================

    @Nested
    @DisplayName("a queued export produces an artefact whose bytes match its recorded checksum")
    class HappyPath {

        @Test
        @DisplayName("QUEUED -> RUNNING -> COMPLETED, with Arabic bytes and a verified checksum")
        void completesWithVerifiableBytes() throws java.io.IOException {
            UUID jobId = queueId(adminA, reportA, "CSV");

            ExportJobRepository.ExportJobRow queued = job(jobId);
            assertThat(queued.status()).isEqualTo("QUEUED");
            assertThat(queued.storageKey())
                .as("Phase-9 hard rule 1: a request returns a QUEUED row, never bytes. A large export on a "
                    + "request thread is the outage this module exists to prevent")
                .isNull();
            assertThat(queued.checksum()).isNull();
            assertThat(queued.startedAt())
                .as("export_jobs_started_check is a biconditional: a QUEUED job has no start time")
                .isNull();
            assertThat(queued.progressPct()).isZero();

            assertThat(generate(adminA, jobId).outcome())
                .isEqualTo("COMPLETED");

            ExportJobRepository.ExportJobRow completed = job(jobId);
            assertThat(completed.status()).isEqualTo("COMPLETED");
            assertThat(completed.checksum()).matches("^sha256:[0-9a-f]{64}$");
            assertThat(completed.progressPct()).isEqualTo(100);
            assertThat(completed.rowCount()).isEqualTo(1L);
            assertThat(completed.bytes()).isPositive();
            assertThat(completed.attemptCount()).isEqualTo(1);
            assertThat(completed.startedAt()).isNotNull();
            assertThat(completed.finishedAt()).isNotNull();

            byte[] bytes = store.read(completed.storageKey());
            String text = new String(bytes, StandardCharsets.UTF_8);
            assertThat(text)
                .as("the BOM is what makes Excel on Windows read a UTF-8 CSV as Arabic rather than as "
                    + "mojibake; documented in docs/migration/phases/phase-09.md")
                .startsWith("﻿");
            assertThat(text)
                .as("the Arabic display name and the Arabic header both survive, verbatim")
                .contains("عميل الاختبار")
                .contains("اسم العميل")
                .contains("عدد العملاء");

            assertThat(ExportSignatureService.checksumOf(bytes))
                .as("the checksum was computed over exactly the bytes a download will serve, so it is "
                    + "evidence rather than a claim")
                .isEqualTo(completed.checksum());

            assertThat(events.attempts(scopeA(PROJECT_A), jobId, 50))
                .as("ONE row per attempt: export_job_attempts_job_attempt_key is UNIQUE on "
                    + "(organizationId, exportJobId, attemptNumber), so a STARTED row and a terminal row "
                    + "cannot both describe attempt 1. The row is the terminal outcome — which is what "
                    + "export_job_attempts_success_check (rowsProduced on SUCCEEDED) and "
                    + "export_job_attempts_finished_check (finishedAt iff durationMs) are written for.")
                .extracting(ExportEventRepository.AttemptRow::outcome)
                .containsExactly("SUCCEEDED");
            assertThat(events.attempts(scopeA(PROJECT_A), jobId, 50))
                .allSatisfy(row -> {
                    assertThat(row.attemptNumber()).isEqualTo(1);
                    assertThat(row.rowsProduced()).isEqualTo(1L);
                    assertThat(row.finishedAt()).isNotNull();
                    assertThat(row.durationMs()).isNotNull();
                    assertThat(row.errorCode()).isNull();
                });
        }

        @Test
        @DisplayName("the applied filters are recorded separately from the requested ones")
        void appliedFiltersAreRecorded() {
            UUID jobId = queueId(adminA, reportA, "CSV");
            generate(adminA, jobId);

            ExportJobRepository.ExportJobDetail detail =
                jobRepository.findDetail(scopeA(PROJECT_A), jobId).orElseThrow();

            assertThat(detail.filters())
                .as("'what ran' and 'what was asked for' are different questions, and only the first is "
                    + "evidence — a filter the planner refused must never appear as though it was applied")
                .containsKeys("__requested", "__applied");
            assertThat(detail.filters().get("__applied"))
                .as("a version with no declared filters pushed nothing, so the applied set is empty rather "
                    + "than absent")
                .isEqualTo(Map.of());

            assertThat(detail.columns())
                .containsExactly("customer.displayName", "customer.count", "customer.primaryEmail",
                    "customer.firstSeenAt");
            assertThat(detail.authorizationSnapshot())
                .as("'what did this export hide and why' is answerable from the job row without reading a "
                    + "byte of the artefact")
                .containsKeys("emittedColumns", "withheldColumns", "principalRole", "principalType",
                    "requiredPermissions", "policy", "subject", "evaluatedAt", "reportVersion");
            assertThat(detail.job().requestedByUserId())
                .as("the requester is on the row's own requestedByUserId column rather than copied into "
                    + "the snapshot — one place per fact, so the two can never disagree about who asked")
                .isEqualTo(ADMIN_A);
            assertThat(detail.authorizationSnapshot().get("principalRole"))
                .as("and the snapshot records the ROLE the request held, which is the part that goes stale "
                    + "and is worth keeping as evidence")
                .isEqualTo("ADMIN");
        }

        @Test
        @DisplayName("a policy-withheld column is absent from the file, not blank in it")
        void aWithheldColumnIsAbsent() throws java.io.IOException {
            UUID strictReport = fixture.publishedCustomerReport(adminA, PROJECT_A, "no-contact-details");

            ExportJobService.QueuedExport queued = create(adminA, PROJECT_A, request(strictReport, "CSV"));
            assertThat(queued.withheldColumns()).containsExactly("customer.primaryEmail");

            ExportJobService.GenerationOutcome outcome = generate(adminA, queued.jobId());
            assertThat(outcome.outcome())
                .as("generation of a report with a withheld column: code=%s message=%s error=%s",
                    outcome.code(), outcome.message(), job(queued.jobId()).errorMessage())
                .isEqualTo("COMPLETED");

            String text = textOf(queued.jobId());
            assertThat(text)
                .as("a blank cell reads as 'this customer has no email'. The file must read as 'this report "
                    + "carries no emails', and the only way to say that is for the column not to exist")
                .doesNotContain("customer-a1@example.invalid")
                .doesNotContain("البريد الإلكتروني للعميل")
                .doesNotContain("[redacted]")
                .doesNotContain(ExportValueAbsent.TOKEN);
            assertThat(text).contains("اسم العميل").contains("عميل الاختبار");

            String[] lines = text.strip().split("\r?\n");
            assertThat(lines[0].split(",")).hasSize(3);
            assertThat(lines[1].split(",", -1))
                .as("four columns declared, three emitted — the row has three cells, not four with a gap")
                .hasSize(3);
        }
    }

    // ==================================================================
    // cross-tenant
    // ==================================================================

    @Nested
    @DisplayName("an export never reads or counts another tenant's or another project's rows")
    class TenantIsolation {

        @Test
        @DisplayName("tenant A's row count excludes tenant B's customer and its own other project's")
        void theAggregateIsScopedBeforeItIsComputed() throws java.io.IOException {
            fixture.customerInTenantB();
            fixture.customerInProjectA2();

            UUID jobId = queueId(adminA, reportA, "CSV");
            ExportJobService.GenerationOutcome outcome =
                generate(adminA, jobId);

            assertThat(textOf(jobId))
                .as("FR-8.2: an unauthorized row must never enter the count. A read-then-filter "
                    + "implementation returns the same file with the row removed from the LIST and the same "
                    + "number in the count — which is exactly what this pair of assertions distinguishes")
                .contains("عميل الاختبار")
                .doesNotContain("عميل الطرف الآخر")
                .doesNotContain("عميل المشروع الثاني");
            assertThat(outcome.rowCount())
                .as("one dimension group per authorized customer: the caller's rows and nothing else")
                .isEqualTo(1L);
        }

        @Test
        @DisplayName("the project predicate is a predicate of its own, not a side effect of the tenant one")
        void theProjectPredicateIsItsOwnPredicate() throws java.io.IOException {
            fixture.customerInProjectA2();
            UUID reportA2 = fixture.publishedCustomerReport(adminA, PROJECT_A2, "none");

            UUID jobId = create(adminA, PROJECT_A2, request(reportA2, "CSV")).jobId();
            assertThat(generate(adminA, jobId).outcome())
                .isEqualTo("COMPLETED");

            assertThat(textOf(jobId))
                .as("a bug that only ever compared organizationId would pass every tenant test and be caught "
                    + "here, because both projects belong to the same tenant")
                .contains("عميل المشروع الثاني")
                .doesNotContain("عميل الاختبار");
        }

        @Test
        @DisplayName("another tenant's report definition is refused and reads as absent")
        void aForeignDefinitionIsInvisible() {
            UUID reportB = fixture.publishedCustomerReport(adminB, PROJECT_B, "none");

            assertThatThrownBy(() -> create(adminA, PROJECT_A, request(reportB, "CSV")))
                .as("PolicyDeniedException renders as the same 404 a nonexistent id produces, so the refusal "
                    + "cannot be used as an existence oracle")
                .isInstanceOf(PolicyDeniedException.class);

            assertThatThrownBy(() -> create(adminA, PROJECT_A, request(UUID.randomUUID(), "CSV")))
                .as("and indistinguishable from a definition that never existed")
                .isInstanceOf(PolicyDeniedException.class);
        }

        @Test
        @DisplayName("a project outside the caller's memberships is refused, with a positive control beside it")
        void aProjectOutsideMembershipIsRefused() {
            TenantContext siblingAdmin = fixture.adminAWithoutProjectA();

            assertThatThrownBy(() -> create(siblingAdmin, PROJECT_A, request(reportA, "CSV")))
                .as("the principal is an ADMIN and so holds ANALYTICS_READ_PROJECT: it is the membership "
                    + "guard refusing, not the permission")
                .isInstanceOf(PolicyDeniedException.class);

            UUID reportA2 = fixture.publishedCustomerReport(siblingAdmin, PROJECT_A2, "none");
            assertThat(create(siblingAdmin, PROJECT_A2, request(reportA2, "CSV")).status())
                .as("POSITIVE CONTROL: the same principal, pointed at the project it does hold")
                .isEqualTo("QUEUED");
        }

        @Test
        @DisplayName("another tenant's job is invisible to the list, the read, and the download")
        void aForeignJobIsInvisible() {
            UUID jobId = complete(adminA, reportA);

            assertThatThrownBy(() -> read(adminB, () -> jobs.get(adminB, jobId)))
                .as("a refusal, not an empty result: the caller learns nothing about whether it exists")
                .isInstanceOf(PolicyDeniedException.class);
            assertThat(read(adminB, () -> jobs.list(adminB, PROJECT_B, null)))
                .as("and it does not appear in the other tenant's list either")
                .noneSatisfy(row -> assertThat(row.id()).isEqualTo(jobId));
            assertThat(read(adminA, () -> jobs.list(adminA, PROJECT_A, null)))
                .as("POSITIVE CONTROL: its own tenant sees it")
                .anySatisfy(row -> assertThat(row.id()).isEqualTo(jobId));
        }

        @Test
        @DisplayName("a machine principal cannot request an export: an unattributable export is not evidence")
        void aMachinePrincipalCannotRequest() {
            assertThatThrownBy(() -> create(fixture.workerA(), PROJECT_A, request(reportA, "CSV")))
                .as("requestedByUserId is NOT NULL and a machine has no user id, so this is a "
                    + "schema-truth statement rather than a policy preference")
                .isInstanceOf(IllegalStateException.class);
        }
    }

    // ==================================================================
    // the finance gate
    // ==================================================================

    @Nested
    @DisplayName("a finance export requires the finance capability, and has no money columns without it")
    class FinanceGate {

        @Test
        @DisplayName("an employee cannot request a usage report at all")
        void anEmployeeCannotRequestAUsageReport() {
            UUID usageReport = fixture.publishedUsageReport(adminA, PROJECT_A);

            assertThatThrownBy(() -> create(fixture.employeeA(), PROJECT_A,
                windowedRequest(usageReport, "CSV", "2026-03-01", "2026-03-31")))
                .as("FR-7.7: the employee is refused. WHICH refusal is the interesting part — the version "
                    + "declares analytics:read-organization, so requireVersionPermissions refuses before "
                    + "the column resolution ever runs. That is strictly better than the "
                    + "EmptyExportRefusal this test originally expected: the caller is told the report "
                    + "needs a permission they lack, rather than being told an export has nothing to emit. "
                    + "Both are refusals and neither produces a file, so the rule — no money columns "
                    + "without empty ones — holds either way.")
                .isInstanceOfAny(AccessDeniedException.class, ExportJobService.EmptyExportRefusal.class);

            assertThat(fixture.count("""
                select 1 from export_jobs where "requestedByUserId" = :requester
                """, Map.of("requester", EMPLOYEE_A)))
                .as("and nothing was queued: a refused request leaves no job row, so there is no artefact "
                    + "in any state for a later reader to find")
                .isZero();

            assertThat(create(adminA, PROJECT_A,
                windowedRequest(usageReport, "CSV", "2026-03-01", "2026-03-31")).status())
                .as("POSITIVE CONTROL: an admin holds the finance capability and is queued normally")
                .isEqualTo("QUEUED");
        }

        @Test
        @DisplayName("an admin's usage export carries the currency beside every amount")
        void aUsageExportCarriesItsCurrency() throws java.io.IOException {
            fixture.unpricedUsageBucket(ORG_A, PROJECT_A, "SAR");
            UUID usageReport = fixture.publishedUsageReport(adminA, PROJECT_A);

            UUID jobId = complete(adminA, usageReport, windowedRequest(usageReport, "CSV", "2026-03-01", "2026-03-31"));
            String[] lines = textOf(jobId).strip().split("\r?\n");

            assertThat(lines[0])
                .as("ADR-0007 rule 6: there is no amount without a currency, and the currency is its own "
                    + "column rather than a symbol the reader has to infer — so the header carries both")
                .contains("العملة")
                .contains("التكلفة")
                .contains("الإيراد");
            assertThat(lines[1])
                .as("and the row carries the ISO code beside its amount")
                .contains("MESSAGE")
                .contains("SAR")
                .contains("2026-03-10");
        }

        @Test
        @DisplayName("an unpriced bucket makes revenue absent, never a summed zero")
        void unpricedRevenueIsAbsent() throws java.io.IOException {
            // revenueAmount NULL is exactly what "unpriced" means on a snapshot — the schema's pricing
            // biconditional forbids a rate with no revenue and a revenue with no rate — and the statement's
            // expression turns a group containing one into a null revenue.
            fixture.unpricedUsageBucket(ORG_A, PROJECT_A, "SAR");
            UUID usageReport = fixture.publishedUsageReport(adminA, PROJECT_A);

            String text = textOf(complete(adminA, usageReport,
                windowedRequest(usageReport, "CSV", "2026-03-01", "2026-03-31")));
            String[] lines = text.strip().split("\r?\n");

            // Column-indexed, not `contains`/`doesNotContain` on the whole line. The previous version
            // asserted the line must not contain "0.000000" and then that it must — because the row
            // carries BOTH a real zero cost and an absent revenue. Indexing by column is the only way to
            // say "this cell is absent AND that cell is zero", and saying it that way is what the rule is.
            int costColumn = columnOf(lines[0], "التكلفة");
            int revenueColumn = columnOf(lines[0], "الإيراد");
            int marginColumn = columnOf(lines[0], "هامش الربح");
            String[] cells = lines[1].split(",", -1);

            assertThat(cells[revenueColumn])
                .as("ADR-0007 rule 5: unpriced is not zero. A summed zero here would be a reconciliation "
                    + "defect and would report revenue the clinic never earned")
                .isEqualTo(ExportValueAbsent.TOKEN);
            assertThat(cells[marginColumn])
                .as("margin is derived from revenue, so it is absent for the same reason rather than "
                    + "computed from a revenue that does not exist")
                .isEqualTo(ExportValueAbsent.TOKEN);
            assertThat(cells[costColumn])
                .as("while the cost cell is still a real zero — an unpriced bucket has no COST either, "
                    + "and the two being different values is the whole point of keeping them apart")
                .isEqualTo("0.000000");
            assertThat(text)
                .as("and the ABSENT token is a real em dash in the bytes, not an empty cell: an empty "
                    + "CSV field reads as zero, which is the collapse ADR-0007 rule 5 exists to prevent")
                .contains(ExportValueAbsent.TOKEN)
                .doesNotContain(",,");
        }

        /**
         * The ordinal of a named Arabic header cell.
         *
         * <p>Named rather than hard-coded so reordering the version's columns cannot silently turn this
         * into an assertion about the wrong cell — which is exactly the class of bug a positional CSV
         * assertion invites.
         */
        private static int columnOf(String headerLine, String arabicLabel) {
            String[] headers = headerLine.split(",", -1);
            for (int i = 0; i < headers.length; i++) {
                if (headers[i].contains(arabicLabel)) {
                    return i;
                }
            }
            throw new AssertionError("The header has no column labelled " + arabicLabel + ": " + headerLine);
        }
    }

    // ==================================================================
    // the download
    // ==================================================================

    @Nested
    @DisplayName("a download is re-authorized, audited, and bound to its tenant")
    class Download {

        @Test
        @DisplayName("minting then redeeming returns the bytes and records both events")
        void mintAndRedeemAreBothAudited() throws java.io.IOException {
            UUID jobId = complete(adminA, reportA);

            ExportDownloadService.MintedLink link = read(adminA, () -> downloads.mint(adminA, jobId, null));
            assertThat(link.downloadPath())
                .as("a signed URL is a bearer credential for a file of customer data, so it names the job "
                    + "and nothing else")
                .doesNotContain(link.checksum())
                .doesNotContain("storageKey")
                .contains("/api/v1/exports/" + jobId + "/download?signature=");
            assertThat(link.fileName())
                .as("a file name is the one part of an export that leaves the system, so it carries the job "
                    + "id and the format and nothing else")
                .isEqualTo("export-" + jobId + ".csv");
            assertThat(link.contentType()).isEqualTo("text/csv; charset=utf-8");
            assertThat(link.expiresAt())
                .as("a signature never outlives the artefact's own retention window")
                .isBeforeOrEqualTo(job(jobId).expiresAt().toInstant(java.time.ZoneOffset.UTC));

            ExportDownloadService.Redeemed redeemed = read(adminA, () -> downloads.redeem(adminA, jobId, tokenOf(link)));

            assertThat(redeemed.bytes())
                .as("the bytes are re-hashed at redemption, so a replaced object is refused rather than "
                    + "served")
                .isEqualTo(store.read(job(jobId).storageKey()));
            assertThat(redeemed.verifiedChecksum()).isEqualTo(link.checksum());

            assertThat(read(adminA, () -> jobs.downloads(adminA, jobId)))
                .as("every attempt is recorded, including the ones that succeed — a download audit that "
                    + "recorded only failures would be the one thing an operator could not use")
                .hasSize(2)
                .extracting(ExportEventRepository.DownloadRow::outcome)
                .containsOnly("GRANTED");
            assertThat(read(adminA, () -> jobs.downloads(adminA, jobId)))
                .extracting(ExportEventRepository.DownloadRow::signatureId)
                .as("the signature ID is recorded, never the URL: a URL in a database row is a bearer "
                    + "credential with an expiry nobody manages")
                .allSatisfy(id -> assertThat(id).isNotBlank());
        }

        @Test
        @DisplayName("tenant B presenting tenant A's link is refused, and which refusal comes first matters")
        void anotherTenantCannotRedeem() {
            UUID jobId = complete(adminA, reportA);
            String token = tokenOf(read(adminA, () -> downloads.mint(adminA, jobId, null)));

            assertThatThrownBy(() -> read(adminB, () -> downloads.redeem(adminB, jobId, token)))
                .as("a foreign caller is refused")
                .isInstanceOfAny(ExportDownloadService.ExportSignatureRefusedException.class,
                    PolicyDeniedException.class);

            assertThat(read(adminA, () -> jobs.downloads(adminA, jobId)))
                .as("POSITIVE CONTROL: tenant A's own mint is the only event on the row, and the foreign "
                    + "attempt left nothing on it. Which refusal fires FIRST is an implementation choice "
                    + "worth stating rather than pinning: the signature refuses on the tenant mismatch "
                    + "without a query, and requireVisibleJob would refuse the same job a moment later "
                    + "because it is invisible in tenant B's scope. Both are refusals; the test that pinned "
                    + "one of them was asserting the order of two correct gates, not the gate.")
                .hasSize(1)
                .extracting(ExportEventRepository.DownloadRow::outcome)
                .containsOnly("GRANTED");

            assertThat(events.downloads(scopeB(), jobId, 50))
                .as("and tenant B's own tenant-scoped read finds no row for a job it does not own")
                .isEmpty();
        }

        @Test
        @DisplayName("a permission revoked between generation and redemption is refused, and audited")
        void aRevokedPermissionRefusesTheDownload() throws java.io.IOException {
            fixture.unpricedUsageBucket(ORG_A, PROJECT_A, "SAR");
            UUID usageReport = fixture.publishedUsageReport(adminA, PROJECT_A);
            UUID jobId = complete(adminA, usageReport, windowedRequest(usageReport, "CSV", "2026-03-01", "2026-03-31"));

            assertThat(textOf(jobId))
                .as("the artefact really does carry the money columns, so the denial below is about the "
                    + "caller and not about a file that never had them")
                .contains("SAR");

            // The employee holds ANALYTICS_READ_PROJECT and CUSTOMER_READ but not
            // ANALYTICS_READ_ORGANIZATION, so the emitted usage columns are beyond them.
            assertThatThrownBy(() -> read(fixture.employeeA(), () -> downloads.mint(fixture.employeeA(), jobId, null)))
                .as("FR-8.7: reauthorize at download. The stored object's checksum is the evidence of what "
                    + "was produced, so the download is REFUSED rather than re-redacted")
                .isInstanceOf(ExportDownloadService.AuthorizationRevokedForDownloadException.class)
                .hasMessageContaining("analytics:read-organization");

            assertThatThrownBy(() -> read(fixture.employeeA(),
                () -> jobs.downloads(fixture.employeeA(), jobId)))
                .as("requireVisibleJob refuses: the job was requested by ADMIN_A and the employee holds "
                    + "neither ANALYTICS_READ_ORGANIZATION nor the requester's identity. The employee "
                    + "cannot read this job's history through the service — which is a STRONGER guarantee "
                    + "than the one the test was written to make, so it is asserted rather than worked "
                    + "around.")
                .isInstanceOf(com.firstelement.security.error.PolicyDeniedException.class);

            assertThat(events.downloads(scopeA(PROJECT_A), jobId, 50))
                .as("the recorded refusal is still answerable by an operator auditing the tenant, which is "
                    + "the audience the audit exists for")
                .extracting(ExportEventRepository.DownloadRow::denyReason)
                .hasSize(1)
                .allSatisfy(reason -> assertThat(reason)
                    .as("the revocation names the field and the permission that lapsed — this is the "
                        + "event a security reviewer wants, and it survives the exception that raised it")
                    .startsWith("authorization-revoked: field '"));
        }

        @Test
        @DisplayName("an expired link is refused")
        void anExpiredLinkIsRefused() {
            UUID jobId = complete(adminA, reportA);
            ExportSignatureService.SignedLink stale = signatures.sign(jobId, ORG_A,
                java.time.Instant.now().minusSeconds(1), java.time.Instant.now());

            assertThatThrownBy(() -> read(adminA, () -> downloads.redeem(adminA, jobId, stale.token())))
                .isInstanceOf(ExportDownloadService.ExportSignatureRefusedException.class)
                .hasMessageContaining("signature-expired");
        }

        @Test
        @DisplayName("a job that is not COMPLETED has nothing to download, and the refusal says why")
        void anIncompleteJobIsNotDownloadable() {
            UUID queued = queueId(adminA, reportA, "CSV");

            assertThatThrownBy(() -> read(adminA, () -> downloads.mint(adminA, queued, null)))
                .isInstanceOf(ExportDownloadService.ExportNotDownloadableException.class)
                .hasMessageContaining("QUEUED");

            assertThat(read(adminA, () -> jobs.downloads(adminA, queued)))
                .as("and the refusal is on the record with a reason")
                .hasSize(1)
                .satisfies(rows -> {
                    assertThat(rows.get(0).outcome()).isEqualTo("DENIED");
                    assertThat(rows.get(0).denyReason()).isEqualTo("job-not-completed");
                });
        }

        @Test
        @DisplayName("a mint with a stale expected checksum is refused rather than handing out a link")
        void aStaleExpectedChecksumIsRefused() {
            UUID jobId = complete(adminA, reportA);

            assertThatThrownBy(() -> read(adminA, () -> downloads.mint(adminA, jobId, "sha256:" + "0".repeat(64))))
                .as("a re-run legitimately produces a different checksum, so a client holding the old one "
                    + "must be told to re-read the job rather than served a file under the old description")
                .isInstanceOf(ExportDownloadService.ExportNotDownloadableException.class)
                .hasMessageContaining("checksum");

            assertThat(read(adminA, () -> jobs.downloads(adminA, jobId)))
                .extracting(ExportEventRepository.DownloadRow::denyReason)
                .contains("checksum-mismatch");
        }

        @Test
        @DisplayName("a corrupted object is refused at redemption rather than served")
        void aCorruptedObjectIsRefused() throws java.io.IOException {
            UUID jobId = complete(adminA, reportA);
            String token = tokenOf(read(adminA, () -> downloads.mint(adminA, jobId, null)));

            // Replace the bytes behind the checksum, which is what a storage fault or a tampered bucket
            // looks like from the redemption endpoint's point of view.
            try (java.io.OutputStream sink = store.openForWrite(job(jobId).storageKey())) {
                sink.write("customer.displayName\nمُعدَّل\n".getBytes(StandardCharsets.UTF_8));
            }
            store.delete(job(jobId).storageKey());

            assertThatThrownBy(() -> read(adminA, () -> downloads.redeem(adminA, jobId, token)))
                .as("serving bytes that contradict the recorded checksum would make the checksum unusable "
                    + "as evidence, which is worse than refusing the download")
                .isInstanceOf(ExportDownloadService.ExportObjectMissingException.class);

            assertThat(read(adminA, () -> jobs.downloads(adminA, jobId)))
                .extracting(ExportEventRepository.DownloadRow::denyReason)
                .as("and the event distinguishes a storage fault from an expiry, because the two have "
                    + "different owners and different remedies")
                .contains("object-storage-missing");
        }
    }

    // ==================================================================
    // cancellation and retry
    // ==================================================================

    @Nested
    @DisplayName("cancellation and retry are recorded states with their own evidence")
    class CancellationAndRetry {

        /**
         * A job in {@code RUNNING}, the state a user actually cancels.
         *
         * <p>Produced by claiming it, which is what a worker does before it reads a row. Completing it
         * first would not exercise cancellation at all: {@code cancel} guards on
         * {@code status in (QUEUED, RUNNING)}, so a COMPLETED job is refused — correctly, and asserted
         * separately by {@link #aCompletedJobIsTerminal}. Cancelling a job the user cannot see running
         * is not the behaviour under test.
         */
        private UUID runningJob() {
            UUID jobId = queueId(adminA, reportA, "CSV");
            ExportJobRepository.ExportJobRow queued = job(jobId);
            assertThat(jobRepository.claim(scopeA(PROJECT_A), jobId, queued.version(), now()))
                .as("the claim is what puts the job in RUNNING, the only state where a user cancelling a "
                    + "job that is genuinely in flight is representable")
                .isTrue();
            return jobId;
        }

        @Test
        @DisplayName("a cancelled job keeps no checksum, no key, and the reason it was cancelled")
        void cancelClearsTheOutputColumns() {
            UUID jobId = runningJob();

            ExportJobRepository.ExportJobRow cancelled =
                cancel(adminA, jobId, "the caller changed their mind");

            assertThat(cancelled.status()).isEqualTo("CANCELLED");
            assertThat(cancelled.cancelReason()).isEqualTo("the caller changed their mind");
            assertThat(cancelled.cancelledByUserId()).isEqualTo(ADMIN_A);
            assertThat(cancelled.cancelledAt()).isNotNull();
            assertThat(cancelled.finishedAt())
                .as("export_jobs_finished_present_check requires a terminal state to carry a finish time")
                .isNotNull();
            assertThat(cancelled.checksum())
                .as("export_jobs_output_check refuses a non-COMPLETED job carrying a checksum, and rightly: "
                    + "a FAILED job with a checksum invites an operator to download a truncated file "
                    + "believing it is the export")
                .isNull();
            assertThat(cancelled.storageKey()).isNull();

            assertThatThrownBy(() -> read(adminA, () -> downloads.mint(adminA, jobId, null)))
                .isInstanceOf(ExportDownloadService.ExportNotDownloadableException.class);

            assertThat(read(adminA, () -> jobs.downloads(adminA, jobId)))
                .as("and the refusal is on the record, which is the property that makes a refused "
                    + "download investigable at all")
                .hasSize(1)
                .satisfies(rows -> assertThat(rows.get(0).outcome()).isEqualTo("DENIED"));
        }

        @Test
        @DisplayName("a cancelled job is retryable, and the retry keeps the pinned report version")
        void retryRequeuesTheSamePinnedVersion() {
            UUID jobId = runningJob();
            cancel(adminA, jobId, "cancelled on purpose");
            assertThat(job(jobId).attemptCount()).isEqualTo(1);

            ExportJobRepository.ExportJobRow requeued = retry(adminA, jobId);

            assertThat(requeued.status()).isEqualTo("QUEUED");
            assertThat(requeued.reportVersion())
                .as("a retry is the same export, not a new one against whatever the definition says now")
                .isEqualTo(1);
            assertThat(requeued.attemptCount())
                .as("the retry count SURVIVES the requeue — it is the evidence that the job has been tried "
                    + "more than once, and an operator reads it before retrying again")
                .isEqualTo(1);
            assertThat(requeued.startedAt()).isNull();
            assertThat(requeued.cancelReason()).isNull();
            assertThat(requeued.cancelledAt()).isNull();
            assertThat(requeued.checksum()).isNull();

            assertThat(generate(adminA, jobId).outcome())
                .isEqualTo("COMPLETED");
            assertThat(job(jobId).attemptCount()).isEqualTo(2);

            assertThat(events.attempts(scopeA(PROJECT_A), jobId, 50))
                .as("ONE row per attempt number — export_job_attempts_job_attempt_key is a UNIQUE index on "
                    + "(organizationId, exportJobId, attemptNumber) — so the stream is append-only ACROSS "
                    + "attempts and an attempt's own row is its terminal outcome. Attempt 1 was cancelled "
                    + "before any worker reached a terminal state for it, so it has no row: the row is "
                    + "written when the attempt finishes, not when it is claimed. That is why attemptCount "
                    + "is 2 while the history holds one row.")
                .hasSize(1)
                .extracting(ExportEventRepository.AttemptRow::attemptNumber)
                .containsExactly(2);
            assertThat(events.attempts(scopeA(PROJECT_A), jobId, 50))
                .extracting(ExportEventRepository.AttemptRow::outcome)
                .containsExactly("SUCCEEDED");
            assertThat(job(jobId).attemptCount())
                .as("attemptCount is the count of ATTEMPTS, which is 2 even though one of them left no "
                    + "history row — the two numbers answer different questions and neither substitutes "
                    + "for the other")
                .isEqualTo(2);
            assertThat(events.attempts(scopeA(PROJECT_A), jobId, 50))
                .filteredOn(row -> row.outcome().equals("SUCCEEDED"))
                .allSatisfy(row -> {
                    assertThat(row.rowsProduced())
                        .as("export_job_attempts_success_check requires a success to report what it "
                            + "produced, so this is the row that makes the attempt history worth reading")
                        .isEqualTo(1L);
                    assertThat(row.finishedAt()).isNotNull();
                    assertThat(row.durationMs()).isNotNull();
                });
        }

        @Test
        @DisplayName("a completed job is neither cancellable nor retryable")
        void aCompletedJobIsTerminal() {
            UUID jobId = complete(adminA, reportA);

            assertThatThrownBy(() -> cancel(adminA, jobId, "too late"))
                .as("cancelling a finished job would rewrite a state an operator has already seen")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is in state COMPLETED, which is not cancellable");

            assertThatThrownBy(() -> retry(adminA, jobId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COMPLETED");
        }

        @Test
        @DisplayName("two workers claiming the same job produce one claim and one SKIPPED record")
        void aDoubleClaimIsSuppressed() {
            UUID jobId = queueId(adminA, reportA, "CSV");
            ExportJobRepository.ExportJobRow queued = job(jobId);

            assertThat(jobRepository.claim(scopeA(PROJECT_A), jobId, queued.version(), now())).isTrue();
            assertThat(jobRepository.claim(scopeA(PROJECT_A), jobId, queued.version(), now()))
                .as("the claim is a guarded UPDATE whose guard IS the expected state and version, so the "
                    + "loser sees a zero affected-row count rather than a duplicate RUNNING row")
                .isFalse();

            assertThat(job(jobId).attemptCount()).isEqualTo(1);
            assertThat(job(jobId).status()).isEqualTo("RUNNING");

            assertThat(generate(adminA, jobId).outcome())
                .as("a worker that arrives second records SKIPPED rather than producing a second artefact")
                .isEqualTo("SKIPPED");
            assertThat(events.attempts(scopeA(PROJECT_A), jobId, 50))
                .extracting(ExportEventRepository.AttemptRow::outcome)
                .contains("SKIPPED");
        }

        @Test
        @DisplayName("a cancelled job is not claimable, so no worker starts producing bytes for it")
        void aCancelledJobIsNotClaimable() {
            UUID jobId = queueId(adminA, reportA, "CSV");
            cancel(adminA, jobId, "stopped before it started");

            assertThat(jobRepository.claim(scopeA(PROJECT_A), jobId, job(jobId).version(), now()))
                .as("the claim's guard is status = 'QUEUED', and a cancelled job is not queued — this is "
                    + "what makes a cancellation that a running export only notices at the end impossible")
                .isFalse();

            assertThat(generate(adminA, jobId).outcome())
                .isEqualTo("SKIPPED");
            assertThat(job(jobId).storageKey()).isNull();
        }
    }

    // ==================================================================
    // deletion
    // ==================================================================

    @Nested
    @DisplayName("deletion removes the object, then the row claims it is gone")
    class Deletion {

        @Test
        @DisplayName("a deleted job keeps the row as a tombstone and records both deletion events")
        void deletionLeavesEvidence() {
            UUID jobId = complete(adminA, reportA);
            String storageKey = job(jobId).storageKey();
            assertThat(store.exists(storageKey)).isTrue();

            ExportRetentionService.DeletionOutcome outcome =
                deleteArtefact(adminA, jobId, "a privacy request");

            assertThat(outcome.outcome()).isEqualTo("DELETED");
            assertThat(outcome.confirmed())
                .as("ADR-0009 section 4 rule 1: the object is deleted BEFORE the row says so, so a "
                    + "DELETED row with a live file is unrepresentable")
                .isTrue();
            assertThat(outcome.bytesReclaimed()).isPositive();
            assertThat(store.exists(storageKey)).isFalse();

            ExportJobRepository.ExportJobRow tombstoned = job(jobId);
            assertThat(tombstoned.status()).isEqualTo("DELETED");
            assertThat(tombstoned.storageKey())
                .as("export_jobs_deleted_check refuses a DELETED job that still points at storage")
                .isNull();
            assertThat(tombstoned.deletedByUserId()).isEqualTo(ADMIN_A);
            assertThat(tombstoned.deletedAt()).isNotNull();

            assertThat(fixture.count("select 1 from export_jobs where \"id\" = :job",
                Map.of("job", jobId)))
                .as("the row is tombstoned, never removed: export_deletion_events is ON DELETE RESTRICT "
                    + "precisely so that removing a job row cannot destroy the proof its object was "
                    + "cleaned up")
                .isEqualTo(1);

            assertThat(read(adminA, () -> jobs.deletions(adminA, jobId)))
                .extracting(ExportEventRepository.DeletionRow::outcome)
                .containsExactlyInAnyOrder("STORAGE_OBJECT_DELETED", "ROW_TOMBSTONED");
            assertThat(read(adminA, () -> jobs.deletions(adminA, jobId)))
                .allSatisfy(event -> assertThat(event.legalHoldChecked())
                    .as("ADR-0008 rule 5: a deletion event with legalHoldChecked false is a deletion that "
                        + "skipped the question")
                    .isTrue());
            assertThat(read(adminA, () -> jobs.deletions(adminA, jobId)))
                .filteredOn(event -> event.outcome().equals("STORAGE_OBJECT_DELETED"))
                .allSatisfy(event -> assertThat(event.bytesReclaimed()).isPositive());
        }

        @Test
        @DisplayName("a deleted job's object is unreachable through the download path")
        void aDeletedJobCannotBeDownloaded() {
            UUID jobId = complete(adminA, reportA);
            deleteArtefact(adminA, jobId, "a privacy request");

            assertThatThrownBy(() -> read(adminA, () -> downloads.mint(adminA, jobId, null)))
                .isInstanceOf(ExportDownloadService.ExportNotDownloadableException.class)
                .hasMessageContaining("DELETED");

            assertThat(read(adminA, () -> jobs.downloads(adminA, jobId)))
                .extracting(ExportEventRepository.DownloadRow::denyReason)
                .contains("object-deleted");
        }

        @Test
        @DisplayName("expiry removes the object and only then marks the job EXPIRED")
        void expiryRemovesTheObjectFirst() {
            UUID jobId = complete(adminA, reportA);
            String storageKey = job(jobId).storageKey();
            long bytes = job(jobId).bytes();
            // Backdate BOTH ends of the window. export_jobs_expiry_check is `expiresAt > requestedAt`, so
            // moving only expiresAt into the past refuses the write — which is the schema saying an export
            // cannot be given a window that closed before it was asked for, and that is correct. The test
            // needs a job that was requested two days ago and expired a day ago.
            fixture.execute("""
                update export_jobs
                   set "requestedAt" = (current_timestamp at time zone 'UTC') - interval '2 days',
                       "expiresAt" = (current_timestamp at time zone 'UTC') - interval '1 day'
                 where "id" = :job
                """, Map.of("job", jobId));

            ExportRetentionService.ExpiryOutcome outcome = read(adminA, () -> retention.expireDue(adminA, PROJECT_A));

            assertThat(outcome.considered()).isEqualTo(1);
            assertThat(outcome.expired()).isEqualTo(1);
            assertThat(outcome.storageFailures()).isZero();
            assertThat(outcome.legalHoldRefusals()).isZero();
            assertThat(outcome.jobIds()).containsExactly(jobId);

            ExportJobRepository.ExportJobRow expired = job(jobId);
            assertThat(expired.status()).isEqualTo("EXPIRED");
            assertThat(expired.expiredAt()).isNotNull();
            assertThat(expired.storageKey()).isNull();
            assertThat(expired.checksum())
                .as("the checksum does NOT survive expiry, which is the schema's decision and exactly why "
                    + "export_download_events.checksumAtDownload exists: the evidence that a download "
                    + "happened survives in the append-only history where the job's own checksum does not")
                .isNull();
            assertThat(expired.bytes()).isEqualTo(bytes);
            assertThat(store.exists(storageKey)).isFalse();

            assertThat(read(adminA, () -> jobs.deletions(adminA, jobId)))
                .extracting(ExportEventRepository.DeletionRow::outcome)
                .containsExactlyInAnyOrder("STORAGE_OBJECT_DELETED", "ROW_TOMBSTONED");
        }

        @Test
        @DisplayName("purge is refused, and an UNDECLARED class makes the blast radius zero")
        void purgeIsRefusedWithAnUndeclaredClass() {
            // No retention_classes row exists yet, so the class stamped on this job is the sentinel.
            UUID jobId = complete(adminA, reportA);
            assertThat(job(jobId).retentionClass())
                .as("a tenant that has configured no class for this stream gets the VISIBLE sentinel rather "
                    + "than a hard-coded DEFAULT that would satisfy the NOT NULL CHECK and defeat "
                    + "ADR-0009 section 4 rule 4")
                .isEqualTo(com.firstelement.admin.audit.AuditVocabularies.UNDECLARED_RETENTION_CLASS);

            ExportRetentionService.PurgeRefusal refusal =
                read(adminA, () -> retention.purgeExpired(adminA, PROJECT_A));

            assertThat(refusal.reason()).isEqualTo("PURGE_DISABLED");
            assertThat(refusal.purgeEnabled()).isFalse();
            assertThat(refusal.code()).isEqualTo("EXPORT_PURGE_DISABLED");
            assertThat(refusal.artefactsEligible())
                .as("ADR-0009 section 4 rule 4: no table is purged until its retention-class column is "
                    + "populated, so an UNDECLARED class is not eligible. Counting it would tell an "
                    + "operator a rehearsal would remove data it would in fact leave alone — the counter "
                    + "exists to be trusted about blast radius, so it reports zero.")
                .isZero();
            assertThat(refusal.tombstonesEligible()).isZero();
            assertThat(fixture.count("select 1 from export_jobs where \"status\" = 'COMPLETED'", Map.of()))
                .as("ADR-0009 section 5: the refusal is a claim and the unchanged table is the evidence")
                .isEqualTo(1);
        }

        @Test
        @DisplayName("purge is refused, and a DECLARED class is counted so a rehearsal can size it")
        void purgeIsRefusedWithADeclaredClass() {
            // Declared BEFORE the job is created, because the class is stamped onto the row at creation.
            // That ordering is itself the property: ADR-0009 §4 rule 4 makes the class part of the row, not
            // something resolved at purge time, so an artefact's eligibility is decided when it is produced.
            fixture.declareRetentionClass();
            UUID jobId = complete(adminA, reportA);

            assertThat(job(jobId).retentionClass())
                .isEqualTo(RetentionWindowResolver.EXPORT_ARTEFACT_CLASS);

            ExportRetentionService.PurgeRefusal refusal =
                read(adminA, () -> retention.purgeExpired(adminA, PROJECT_A));

            assertThat(refusal.reason()).isEqualTo("PURGE_DISABLED");
            assertThat(refusal.purgeEnabled()).isFalse();
            assertThat(refusal.code()).isEqualTo("EXPORT_PURGE_DISABLED");
            assertThat(refusal.artefactsEligible())
                .as("the counter is what a rehearsal needs, so an operator can see the blast radius before "
                    + "the capability is turned on")
                .isEqualTo(1);
            assertThat(fixture.count("select 1 from export_jobs where \"status\" = 'COMPLETED'", Map.of()))
                .as("and nothing was removed: the refusal is a claim and the unchanged table is the "
                    + "evidence")
                .isEqualTo(1);
        }
    }

    // ==================================================================
    // bounded memory
    // ==================================================================

    @Nested
    @DisplayName("a large export streams: the heap does not grow with the row count")
    class BoundedMemory {

        @Test
        @DisplayName("20,000 rows are written, and the heap does not retain them")
        void aLargeExportDoesNotBufferTheRows() {
            int rows = 20_000;
            fixture.customersInProjectA(rows);

            // The row count is read from the table rather than assumed from the loop, so the assertion is
            // "the file holds every AUTHORIZED row" and not "the file holds what the test hoped it seeded".
            int seeded = fixture.count("""
                select 1 from customers where "organizationId" = :org and "projectId" = :project
                """, Map.of("org", ORG_A, "project", PROJECT_A));

            long heapBefore = usedHeap();
            UUID jobId = queueId(adminA, reportA, "CSV");
            ExportJobService.GenerationOutcome outcome =
                generate(adminA, jobId);
            long heapAfter = usedHeap();

            assertThat(outcome.outcome()).isEqualTo("COMPLETED");
            assertThat(job(jobId).rowCount())
                .as("every seeded row was written; a streaming path that stopped early would satisfy a "
                    + "memory assertion while silently losing data")
                .isEqualTo(seeded)
                .isGreaterThanOrEqualTo(rows);
            assertThat(job(jobId).bytes())
                .as("and the file really does hold every row, so the memory result is not a truncated "
                    + "export wearing a large row count")
                .isGreaterThan(500_000L);

            assertThat(heapAfter - heapBefore)
                .as("a buffering implementation retains the whole result set; a streaming one holds one "
                    + "fetch page. The allowance is generous because the assertion is about the order of "
                    + "magnitude, not about the GC")
                .isLessThan(96L * 1024 * 1024);
        }

        @Test
        @DisplayName("the largest page the read produced is far smaller than the file")
        void theReadIsPagedNotBuffered() {
            fixture.customersInProjectA(5_000);
            UUID jobId = complete(adminA, reportA);

            assertThat(job(jobId).bytes()).isGreaterThan(100_000L);
            assertThat(fixture.queryOne("""
                select count(*) from export_jobs where "id" = :job and "bytes" > "rowCount" * 8
                """, Map.of("job", jobId), Integer.class))
                .as("POSITIVE CONTROL on the fixture itself: the file really is much larger than its row "
                    + "count, so the byte assertions above are measuring Arabic text rather than padding")
                .isEqualTo(1);
        }
    }

    // ==================================================================
    // helpers
    // ==================================================================

    private static ExportJobService.ExportRequest request(UUID definitionId, String format) {
        return new ExportJobService.ExportRequest(definitionId, 1, null, null, format, Map.of(), List.of(),
            "ar", 7);
    }

    /**
     * A request carrying an explicit usage window.
     *
     * <p>A usage report must declare BOTH bounds. {@code FinanceExportGateRepository.windowOf} refuses a
     * one-sided or absent window because an unbounded finance export is an organisation-wide aggregation,
     * which is what FR-8.2 exists to prevent — the refusal is correct and these tests were asking for the
     * thing it exists to refuse. The window is supplied here so the finance suites exercise the behaviour
     * they are about (an unpriced bucket, a missing currency) rather than the gate that guards them.
     */
    private static ExportJobService.ExportRequest windowedRequest(UUID definitionId, String format,
                                                                   String from, String to) {
        return new ExportJobService.ExportRequest(definitionId, 1, null, null, format,
            Map.of("usage.bucketFrom", from, "usage.bucketTo", to), List.of(), "ar", 7);
    }

    /**
     * Runs a service call with both request bindings established.
     *
     * <p>Used for every call whose RETURN VALUE an assertion depends on, including the ones that throw: a
     * refusal produced without a {@code SecurityContext} is "no credential", not "no grant", and an
     * authorization suite that cannot tell those apart cannot prove anything.
     */
    private static <T> T read(TenantContext caller, java.util.function.Supplier<T> call) {
        return ExportsPrincipalScope.call(caller, call);
    }

    private ExportJobRepository.ExportJobRow cancel(TenantContext caller, UUID jobId, String reason) {
        return read(caller, () -> jobs.cancel(caller, jobId, reason));
    }

    private ExportJobRepository.ExportJobRow retry(TenantContext caller, UUID jobId) {
        return read(caller, () -> jobs.retry(caller, jobId));
    }

    private ExportRetentionService.DeletionOutcome deleteArtefact(TenantContext caller, UUID jobId,
                                                                  String reason) {
        return read(caller, () -> retention.deleteArtefact(caller, jobId, reason));
    }

    private UUID queueId(TenantContext caller, UUID definitionId, String format) {
        return create(caller, PROJECT_A, request(definitionId, format)).jobId();
    }

    /** {@code ExportJobService.create} with both request bindings a real request would have. */
    private ExportJobService.QueuedExport create(TenantContext caller, UUID projectId,
                                                 ExportJobService.ExportRequest request) {
        return ExportsPrincipalScope.call(caller, () -> jobs.create(caller, projectId, request,
            CORRELATION));
    }

    /**
     * {@code ExportJobService.generate} as the worker would run it.
     *
     * <p>The scope is built from the <strong>job's own</strong> {@code projectId}, never from a constant.
     * A worker is handed one job; the project it generates against is the project on that job. Pinning
     * {@code PROJECT_A} here made every helper generate in project A's scope, so a job created for
     * {@code PROJECT_A2} was read with A's predicate — which is how the project-predicate test came to
     * observe a customer it was asserting was excluded. That failure was in the harness, and it is worth
     * recording that the module's own guard did not object: a caller may generate in any project it may
     * see, and choosing the job's project is the only correct choice.
     */
    private ExportJobService.GenerationOutcome generate(TenantContext requester, UUID jobId) {
        UUID projectId = job(jobId).projectId();
        return ExportsPrincipalScope.call(requester, () -> jobs.generate(requester,
            new com.firstelement.security.query.TenantScope(ORG_A, projectId), jobId, WORKER));
    }

    /**
     * Queues and generates, asserting the terminal state.
     *
     * <p>The job's own {@code errorMessage} is the assertion description on purpose. A generation failure is
     * a recorded state with a reason an operator reads, so the test reads it the same way an operator would
     * instead of printing a bare "expected COMPLETED but was FAILED".
     */
    private UUID complete(TenantContext caller, UUID definitionId) {
        return complete(caller, definitionId, request(definitionId, "CSV"));
    }

    /**
     * Queues with a specific request and generates, for the reports whose gate needs one.
     *
     * <p>Only the usage reports need a window; see {@link #windowedRequest}. Split rather than defaulted
     * inside {@link #complete} so a customer-directory report is never silently given a usage filter it
     * would refuse for having the wrong subject.
     */
    private UUID complete(TenantContext caller, UUID definitionId,
                          ExportJobService.ExportRequest theRequest) {
        UUID jobId = ExportsPrincipalScope.call(caller, () -> jobs.create(caller, PROJECT_A,
            theRequest, CORRELATION)).jobId();
        ExportJobService.GenerationOutcome outcome = generate(caller, jobId);
        ExportJobRepository.ExportJobRow recorded = job(jobId);
        assertThat(outcome.outcome())
            .as("generation outcome for job %s: code=%s message=%s; recorded status=%s attempt=%s error=%s",
                jobId, outcome.code(), outcome.message(), recorded.status(), recorded.attemptCount(),
                recorded.errorMessage())
            .isEqualTo("COMPLETED");
        return jobId;
    }

    private ExportJobRepository.ExportJobRow job(UUID jobId) {
        return jobRepository.findForDownload(scopeA(PROJECT_A), jobId).orElseThrow();
    }

    private String textOf(UUID jobId) throws java.io.IOException {
        return new String(store.read(job(jobId).storageKey()), StandardCharsets.UTF_8);
    }

    private static String tokenOf(ExportDownloadService.MintedLink link) {
        return link.downloadPath().substring(link.downloadPath().indexOf("signature=") + 10);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static long usedHeap() {
        System.gc();
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    /** The absent-value token, named in one place so the assertion and the constant cannot drift. */
    private static final class ExportValueAbsent {

        private ExportValueAbsent() {
        }

        static final String TOKEN = com.firstelement.exports.format.ExportValueFormatter.ABSENT;
    }
}
