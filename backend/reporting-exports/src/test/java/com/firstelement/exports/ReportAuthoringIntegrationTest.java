package com.firstelement.exports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstelement.exports.download.ExportSignatureService;
import com.firstelement.exports.job.ExportJobService;
import com.firstelement.exports.persistence.ExportJobRepository;
import com.firstelement.exports.storage.ExportObjectStore;
import com.firstelement.security.Capability;
import com.firstelement.security.PrincipalType;
import com.firstelement.security.TenantAuthentication;
import com.firstelement.security.TenantContext;
import com.firstelement.security.TenantContextHolder;
import com.firstelement.security.UserRole;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.permission.RolePermissions;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * <b>Report authoring over HTTP, on a database this suite never seeds a catalogue into.</b>
 *
 * <h2>Why this suite exists at all</h2>
 *
 * <p>Phase 16 proved the export <em>catalogue</em> could build a create request, and it passed — because
 * {@code ExportsFixture} authored the catalogue through the real services in {@code @BeforeEach}. That is
 * exactly the fixture that hid the deployment blocker: on a real database
 * {@code V20261005060000__report_definitions} creates four tables with <b>zero rows</b>, <b>no route created
 * any</b>, and therefore {@code GET /api/v1/exports/catalogue} answered an empty list while
 * {@code POST /api/v1/exports} had nothing valid to reference. The capability was real; the way to reach it
 * did not exist.
 *
 * <p>So the first nested class below authors everything over HTTP and then completes an export, with
 * <b>not one id hand-typed</b>: every id and version number is read out of a response. If a route cannot
 * publish an id, the test cannot proceed, and if the catalogue and the create route disagree the fourth
 * nested class says so empirically rather than by shared code path.
 *
 * <h2>Why nothing is seeded, anywhere in this suite</h2>
 *
 * <p>{@code ExportsFixture.seedOrganizationsAndUsers()} writes organizations, projects and users and
 * nothing else. Every report definition, version, template and template version below is created by an HTTP
 * call in the test body. A fixture that authored the catalogue is what made this gap invisible for two
 * phases, so this suite does not have one: if authoring regresses, these tests go red instead of the gap
 * going unrecorded.
 *
 * <h2>Filters are off, and nothing that matters here is weakened</h2>
 *
 * <p>{@code @RequirePermission} is an AOP proxy, not a filter, so it still runs; the session/cookie
 * requirement of the servlet chain is identity-access's to prove and is covered by
 * {@code AdminRoutePermissionHttpIntegrationTest}. {@code asPrincipal} establishes the thread bindings a real
 * request would carry.
 */
@SpringBootTest(classes = ExportsTestBase.ExportsTestApplication.class)
@AutoConfigureMockMvc(addFilters = false)
class ReportAuthoringIntegrationTest extends ExportsTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String WORKER = "authoring-integration-worker";
    private static final String CORRELATION = "corr-authoring";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ExportsFixture fixture;

    @Autowired
    private ExportJobService jobs;

    @Autowired
    private ExportJobRepository jobRepository;

    @Autowired
    private ExportObjectStore store;

    @Autowired
    private com.firstelement.exports.format.DocumentTemplateService templates;

    private TenantContext adminA;

    @BeforeEach
    void seedOnlyTheReferenceRows() {
        fixture.reset();
        fixture.seedOrganizationsAndUsers();
        adminA = fixture.adminA();
    }

    // ==================================================================
    // THE PRODUCT CAPABILITY
    // ==================================================================

    @Nested
    @DisplayName("a fresh database with nothing in it")
    class FreshDatabase {

        /**
         * The whole chain, over HTTP, with no fixture row and no hand-typed id.
         *
         * <p>Author a definition, author a version, publish it, author a template, store a template version,
         * publish it, read the catalogue, post the export, run it to completion. Every identifier below was
         * produced by the call immediately above it.
         */
        @Test
        @DisplayName("an authorised user authors a report and a template, then creates and completes an export")
        void authorsEverythingAndCompletesAnExport() throws Exception {
            // Nothing to start from. If these two assertions were dropped the rest of the test would still
            // pass on a database the fixture had quietly filled, which is the failure mode this suite exists
            // to rule out.
            assertThat(count("report_definitions", ORG_A))
                .as("the starting point is a database with no report definitions at all -- the state "
                    + "V20261005060000 leaves behind on a real deployment")
                .isZero();
            assertThat(count("document_templates", ORG_A)).isZero();

            // ---- 1. the report definition -------------------------------------------------
            JsonNode definition = created(authorA("POST", "/api/v1/exports/report-definitions",
                Map.of("key", "customer-directory",
                    "nameAr", "دليل العملاء",
                    "nameEn", "Customer directory",
                    "descriptionAr", "تقرير العملاء",
                    "descriptionEn", "Customer report",
                    "timezone", "Asia/Riyadh",
                    "localeDefault", "ar",
                    "requiredPermissions", List.of("customer:read"),
                    "redactionPolicy", "none",
                    "retentionClass", "EXPORT_ARTEFACTS")));
            UUID definitionId = UUID.fromString(definition.get("reportDefinitionId").asText());
            assertThat(definition.get("status").asText()).isEqualTo("DRAFT");
            assertThat(definition.get("currentVersion").asInt())
                .as("a fresh definition has no version yet")
                .isZero();
            assertThat(definition.get("publishedVersion").isNull())
                .as("and nothing published")
                .isTrue();

            // ---- 2. one version body ------------------------------------------------------
            JsonNode version = created(authorA("POST",
                "/api/v1/exports/report-definitions/" + definitionId + "/versions",
                Map.of("columns", List.of("customer.displayName", "customer.count",
                        "customer.primaryEmail", "customer.firstSeenAt"),
                    "dimensions", List.of("customer.displayName", "customer.primaryEmail",
                        "customer.firstSeenAt"),
                    "measures", List.of("customer.count"),
                    "filters", Map.of(),
                    "defaultSort", List.of("customer.displayName:asc"),
                    "denominatorRules", Map.of(),
                    "metricSemantics", Map.of("customer.count",
                        "a count of the authorized rows, never of the filtered set"),
                    "changeNote", "the initial shape")));
            int versionNumber = version.get("reportVersion").asInt();
            assertThat(versionNumber)
                .as("the first version of a definition is 1, allocated by the server")
                .isEqualTo(1);
            assertThat(version.get("state").asText()).isEqualTo("DRAFT");
            assertThat(version.get("reportDefinitionId").asText()).isEqualTo(definitionId.toString());

            // ---- 3. publish it ------------------------------------------------------------
            JsonNode published = body(authorA("POST", "/api/v1/exports/report-definitions/" + definitionId
                + "/versions/" + versionNumber + "/publish", null));
            assertThat(published.get("status").asText()).isEqualTo("PUBLISHED");
            assertThat(published.get("publishedVersion").asInt()).isEqualTo(versionNumber);

            // ---- 4. a document template ---------------------------------------------------
            JsonNode template = created(authorA("POST", "/api/v1/exports/document-templates",
                Map.of("key", "customer-directory-docx",
                    "nameAr", "دليل العملاء",
                    "nameEn", "Customer directory",
                    "locale", "ar",
                    "timezone", "Asia/Riyadh")));
            UUID templateId = UUID.fromString(template.get("documentTemplateId").asText());
            assertThat(template.get("kind").asText())
                .as("kind is pinned to DOCX by the service, because this renderer produces nothing else")
                .isEqualTo("DOCX");

            // ---- 5. the template body, and it must be readable by the key its own row names --
            byte[] document = minimalDocx("{{report.customer.displayName}}");
            JsonNode templateVersion = storedTemplateVersion(templateId, "ar", document);
            int templateVersionNumber = templateVersion.get("documentTemplateVersion").asInt();
            assertThat(templateVersionNumber).isEqualTo(1);
            assertThat(templateVersion.get("state").asText()).isEqualTo("DRAFT");
            assertThat(templateVersion.get("placeholders")).isNotEmpty();
            assertThat(templateVersion.toString())
                .as("the authoring response carries no object-store locator and no content hash")
                .doesNotContain("storageKey")
                .doesNotContain("contentHash");

            JsonNode templatePublished = body(authorA("POST", "/api/v1/exports/document-templates/"
                + templateId + "/versions/" + templateVersionNumber + "/publish", null));
            assertThat(templatePublished.get("status").asText()).isEqualTo("PUBLISHED");

            // ---- 6. the catalogue, read only, now has something to say --------------------
            JsonNode catalogue = readCatalogue(adminA, PROJECT_A);
            JsonNode offered = definitionById(catalogue, definitionId);
            assertThat(offered).as("the definition the author just published is in the catalogue").isNotNull();
            JsonNode offeredVersion = versionByNumber(offered, versionNumber);
            assertThat(offeredVersion.get("exportable").asBoolean())
                .as("and its published version is offered for export")
                .isTrue();
            UUID offeredTemplateId = UUID.fromString(
                templateByKey(catalogue, "customer-directory-docx").get("documentTemplateId").asText());
            assertThat(offeredTemplateId).isEqualTo(templateId);
            assertThat(firstTemplateVersion(catalogue, templateId).get("exportable").asBoolean())
                .as("and so is the published template version")
                .isTrue();

            // ---- 7. the export, from catalogue ids only ----------------------------------
            fixture.customerInProjectA();
            MvcResult queued = mvc.perform(post("/api/v1/exports")
                    .param("projectId", PROJECT_A.toString())
                    .with(asPrincipal(adminA))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(MAPPER.writeValueAsString(Map.of(
                        "reportDefinitionId",
                        offered.get("reportDefinitionId").asText(),
                        "reportVersion", offeredVersion.get("reportVersion").asInt(),
                        "documentTemplateId",
                        templateByKey(catalogue, "customer-directory-docx")
                            .get("documentTemplateId").asText(),
                        "documentTemplateVersion",
                        firstTemplateVersion(catalogue, templateId).get("documentTemplateVersion").asInt(),
                        "format", "DOCX"))))
                .andExpect(status().is2xxSuccessful())
                .andReturn();
            assertThat(queued.getResponse().getStatus())
                .as("202 Accepted: the work is durable and no artefact exists yet")
                .isEqualTo(202);
            UUID jobId = UUID.fromString(MAPPER.readTree(queued.getResponse().getContentAsString())
                .get("jobId").asText());

            // ---- 8. and it completes on the worker ---------------------------------------
            ExportJobService.GenerationOutcome outcome = generate(adminA, jobId);
            ExportJobRepository.ExportJobRow completed = jobRepository
                .findForDownload(scopeA(PROJECT_A), jobId).orElseThrow();
            assertThat(outcome.outcome())
                .as("code=%s message=%s", outcome.code(), outcome.message())
                .isEqualTo("COMPLETED");
            assertThat(completed.status()).isEqualTo("COMPLETED");
            assertThat(completed.checksum()).matches("^sha256:[0-9a-f]{64}$");
            assertThat(completed.rowCount()).isEqualTo(1L);
            assertThat(completed.progressPct()).isEqualTo(100);
            byte[] artefact = store.read(completed.storageKey());
            assertThat(ExportSignatureService.checksumOf(artefact)).isEqualTo(completed.checksum());
            assertThat(artefact.length).isPositive();
        }

        @Test
        @DisplayName("the catalogue is empty before anything is authored, so 'empty' is distinguishable")
        void theCatalogueIsEmptyToBeginWith() throws Exception {
            assertThat(readCatalogue(adminA, PROJECT_A).get("reportDefinitions"))
                .as("this is what a real deployment served before Phase 31, and it is why exports could not "
                    + "be created at all")
                .isEmpty();
            assertThat(readCatalogue(adminA, PROJECT_A).get("documentTemplates")).isEmpty();

            // ...and a create request cannot be built from it, so the refusal is honest rather than a
            // missing capability test.
            mvc.perform(post("/api/v1/exports")
                    .param("projectId", PROJECT_A.toString())
                    .with(asPrincipal(adminA))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(MAPPER.writeValueAsString(Map.of(
                        "reportDefinitionId", UUID.randomUUID().toString(),
                        "reportVersion", 1, "format", "CSV"))))
                .andExpect(status().isNotFound());
        }
    }

    // ==================================================================
    // UNAUTHORISED AUTHORING
    // ==================================================================

    @Nested
    @DisplayName("without report:manage")
    class WithoutReportManage {

        /**
         * Every authoring route, for a principal that holds neither {@code report:manage} nor
         * {@code workflow:manage}.
         *
         * <p>An {@code EMPLOYEE} is the sharpest probe available: a legitimate authenticated principal in the
         * same tenant with real project reach, who can read the catalogue and therefore knows the surface
         * exists. A 404 rather than a 403 because the answer must be the one a foreign or nonexistent id
         * gives — see {@code ReportAuthoringGuard}.
         */
        @Test
        @DisplayName("an employee is refused every authoring route with a non-disclosing 404")
        void anEmployeeIsRefusedEveryAuthoringRoute() throws Exception {
            TenantContext employee = fixture.employeeA();
            assertThat(ExportsPrincipalScope.effective(employee))
                .as("an EMPLOYEE can read analytics and holds neither authoring permission, so the refusal "
                    + "below is about the permission and not about the principal having nothing")
                .contains(Permission.ANALYTICS_READ_PROJECT)
                .doesNotContain(Permission.REPORT_MANAGE)
                .doesNotContain(Permission.WORKFLOW_MANAGE);

            UUID foreignId = UUID.randomUUID();
            for (MockHttpServletRequestBuilder request : authoringRequests(employee, foreignId)) {
                MvcResult result = mvc.perform(request).andReturn();
                assertThat(result.getResponse().getStatus())
                    .as("authoring must be refused with the answer a nonexistent id gives, never 403")
                    .isEqualTo(404);
            }

            // The same routes against a REAL id, so the refusal is not satisfied by "nothing exists".
            UUID real = UUID.fromString(authorDefinition(fixture.adminA(), "employee-probe")
                .get("reportDefinitionId").asText());
            for (MockHttpServletRequestBuilder request : authoringRequests(employee, real)) {
                assertThat(mvc.perform(request).andReturn().getResponse().getStatus())
                    .as("a known target is refused identically to a random one -- that is what makes the "
                        + "answer non-disclosing rather than merely restrictive")
                    .isEqualTo(404);
            }

            assertThat(count("report_definitions", ORG_A))
                .as("and only the administrator's one definition exists: nothing was written by any of the "
                    + "eight refusals above")
                .isEqualTo(1);
            assertThat(count("report_versions", ORG_A)).isZero();
            assertThat(count("document_templates", ORG_A)).isZero();
            assertThat(count("document_template_versions", ORG_A)).isZero();
        }

        /**
         * A machine principal is refused too.
         *
         * <p>{@code report_versions."createdByUserId"} is NOT NULL with a tenant FK to {@code users}, so a
         * machine could create a definition nobody authored and then fail on its first version. The grant
         * table does not hand it {@code report:manage} and this asserts the guard agrees.
         */
        @Test
        @DisplayName("a worker principal holding report:write is refused, because it is not a person")
        void aMachinePrincipalIsRefused() throws Exception {
            TenantContext worker = TenantContext.forMachine(PrincipalType.WORKER, ORG_A,
                "authoring-probe-worker", List.of(PROJECT_A), List.of(Capability.REPORT_WRITE));

            assertThat(ExportsPrincipalScope.effective(worker))
                .as("the capability really does grant the analytics reads, so the refusal below is about "
                    + "authorship rather than about the principal having nothing")
                .contains(Permission.ANALYTICS_READ_PROJECT)
                .doesNotContain(Permission.REPORT_MANAGE);

            for (MockHttpServletRequestBuilder request : authoringRequests(worker, UUID.randomUUID())) {
                assertThat(mvc.perform(request).andReturn().getResponse().getStatus()).isEqualTo(404);
            }
            assertThat(count("report_definitions", ORG_A)).isZero();
        }

        @Test
        @DisplayName("an administrator is answered, so the 404s above are the permission and not the route")
        void anAdministratorIsAnswered() throws Exception {
            assertThat(count("report_definitions", ORG_A)).isZero();
            JsonNode definition = authorDefinition(fixture.adminA(), "positive-control");
            assertThat(definition.get("reportDefinitionId").asText()).isNotBlank();
            assertThat(count("report_definitions", ORG_A))
                .as("the same route, the same body and the same project, answered for the principal that "
                    + "holds report:manage")
                .isEqualTo(1);
        }
    }

    // ==================================================================
    // THE WORKFLOW CONFLATION
    // ==================================================================

    @Nested
    @DisplayName("report authoring is not workflow authoring")
    class NoWorkflowConflation {

        /**
         * The specific conflation, stated over the whole grant table rather than as one principal.
         *
         * <p>The brief asks for "a workflow administrator with {@code WORKFLOW_MANAGE} alone". Such a
         * principal is <b>not constructible</b> from this repository's grant tables: only {@code ADMIN}
         * holds {@code WORKFLOW_MANAGE}, and {@code ADMIN} is {@code EnumSet.allOf} and therefore also holds
         * {@code REPORT_MANAGE}. Asserting a refusal for a principal that cannot exist would be a test that
         * passes whatever the code does.
         *
         * <p>So the property is asserted exhaustively instead, and it is strictly stronger: <b>no</b> role
         * and <b>no</b> capability may produce a grant set containing {@code WORKFLOW_MANAGE} without also
         * containing {@code REPORT_MANAGE}. Every principal the system can build is enumerated.
         */
        @Test
        @DisplayName("no grant path yields workflow:manage without report:manage")
        void workflowManageIsNeverGrantedWithoutReportManage() {
            for (UserRole role : UserRole.values()) {
                assertGrantSetIsSafe("role " + role, RolePermissions.forRole(role));
            }
            // Every singleton capability, and every PAIR. A pair is the only way a grant set could grow a
            // second administrative permission without either singleton having it, so the pairs are the
            // half of the space a singleton sweep would miss.
            List<Capability> capabilities = List.of(Capability.values());
            for (Capability one : capabilities) {
                assertGrantSetIsSafe("capability " + one,
                    RolePermissions.forCapabilities(Set.of(one)));
                for (Capability two : capabilities) {
                    if (one == two) {
                        continue;
                    }
                    assertGrantSetIsSafe("capabilities " + one + "+" + two,
                        RolePermissions.forCapabilities(Set.of(one, two)));
                }
            }
        }

        @Test
        @DisplayName("report:manage is held by ADMIN and by nothing else the grant table can build")
        void reportManageIsAdminOnly() {
            assertThat(RolePermissions.forRole(UserRole.ADMIN))
                .as("ADMIN is EnumSet.allOf(Permission), so it holds the new value with no line of "
                    + "configuration -- which is what 'granted deliberately to administrators' means here")
                .contains(Permission.REPORT_MANAGE);
            assertThat(RolePermissions.forRole(UserRole.EMPLOYEE))
                .as("Phase 3 did not widen the employee grant and Phase 31 does not either: an employee may "
                    + "read a report catalogue and may not decide what a report contains")
                .doesNotContain(Permission.REPORT_MANAGE)
                .doesNotContain(Permission.WORKFLOW_MANAGE);
            for (Capability capability : Capability.values()) {
                assertThat(RolePermissions.forCapabilities(Set.of(capability)))
                    .as("capability %s", capability)
                    .doesNotContain(Permission.REPORT_MANAGE);
            }
        }

        /**
         * The conflation cannot return silently, asserted by reflection.
         *
         * <p>A behavioural test would go red if someone re-added {@code @RequirePermission(WORKFLOW_MANAGE)}
         * to a method they then stopped exercising. This cannot: it reads the annotations off every public
         * method of both authoring services and every handler in {@code ExportController}.
         */
        @Test
        @DisplayName("no authoring method is annotated with workflow:manage")
        void noAuthoringMethodBorrowsWorkflowManage() {
            List<String> borrowing = new ArrayList<>();
            for (Class<?> service : List.of(
                com.firstelement.exports.catalog.ReportDefinitionService.class,
                com.firstelement.exports.format.DocumentTemplateService.class,
                com.firstelement.exports.web.ExportController.class)) {
                for (Method method : service.getDeclaredMethods()) {
                    if (!java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                        continue;
                    }
                    RequirePermission declared = method.getAnnotation(RequirePermission.class);
                    if (declared == null) {
                        continue;
                    }
                    if (declared.value() == Permission.WORKFLOW_MANAGE) {
                        borrowing.add(service.getSimpleName() + "#" + method.getName());
                    }
                }
            }
            assertThat(borrowing)
                .as("Phase 31 removed the borrow. Nothing on the authoring surface may name "
                    + "workflow:manage again, and nothing may name it for an export lifecycle route either "
                    + "-- that would put the workflow permission back in the path of a finance export.")
                .isEmpty();
        }

        private void assertGrantSetIsSafe(String label, Set<Permission> grants) {
            if (grants.contains(Permission.WORKFLOW_MANAGE)) {
                assertThat(grants)
                    .as("%s grants workflow:manage. If it does not also grant report:manage, then a "
                        + "workflow administrator can change what a finance export contains and the audit "
                        + "row calls it workflow configuration.", label)
                    .contains(Permission.REPORT_MANAGE);
            }
        }
    }

    // ==================================================================
    // CROSS-TENANT
    // ==================================================================

    @Nested
    @DisplayName("another tenant's catalogue")
    class CrossTenant {

        @Test
        @DisplayName("a foreign definition is invisible, unauthorable and unpublishable")
        void aForeignDefinitionIsInvisibleAndUnauthorable() throws Exception {
            JsonNode theirs = authorDefinition(fixture.adminB(), "tenant-b-report");
            UUID foreignId = UUID.fromString(theirs.get("reportDefinitionId").asText());

            // Invisible: tenant A's catalogue does not carry it, and tenant B's does -- so the exclusion is
            // the tenant predicate rather than an empty read.
            assertThat(definitionIds(readCatalogue(adminA, PROJECT_A))).doesNotContain(foreignId.toString());
            assertThat(definitionIds(readCatalogue(fixture.adminB(), PROJECT_B)))
                .contains(foreignId.toString());

            // Unreadable: 404, identical to a nonexistent id.
            mvc.perform(get("/api/v1/exports/report-definitions/" + foreignId)
                    .with(asPrincipal(adminA)))
                .andExpect(status().isNotFound());

            // Unauthorable: a version, a publish and a rename against it are all 404. Only the routes that act ON
            // the supplied id -- the create routes name no target, so they are not a cross-tenant probe.
            for (MockHttpServletRequestBuilder request : targetedAuthoringRequests(adminA, foreignId)) {
                assertThat(mvc.perform(request).andReturn().getResponse().getStatus())
                    .as("tenant A must not be able to write into tenant B's catalogue by naming its id")
                    .isEqualTo(404);
            }
            assertThat(count("report_definitions", ORG_A))
                .as("and tenant A's own catalogue is untouched")
                .isZero();
            assertThat(count("document_templates", ORG_A)).isZero();

            // And not referenceable by an export.
            mvc.perform(post("/api/v1/exports")
                    .param("projectId", PROJECT_A.toString())
                    .with(asPrincipal(adminA))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(MAPPER.writeValueAsString(Map.of(
                        "reportDefinitionId", foreignId.toString(),
                        "reportVersion", 1, "format", "CSV"))))
                .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("a foreign template is not authorable and not referenceable")
        void aForeignTemplateIsUnauthorable() throws Exception {
            JsonNode theirs = MAPPER.readTree(mvc.perform(post("/api/v1/exports/document-templates")
                    .param("projectId", PROJECT_B.toString())
                    .with(asPrincipal(fixture.adminB()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(MAPPER.writeValueAsString(Map.of(
                        "key", "tenant-b-docx", "nameAr", "قالب", "nameEn", "Template",
                        "locale", "ar", "timezone", "Asia/Riyadh"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
            UUID foreignId = UUID.fromString(theirs.get("documentTemplateId").asText());

            assertThat(templateIds(readCatalogue(adminA, PROJECT_A))).doesNotContain(foreignId.toString());

            MvcResult versionAttempt = mvc.perform(
                post("/api/v1/exports/document-templates/" + foreignId + "/versions")
                    .param("locale", "ar")
                    .with(asPrincipal(adminA))
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .content(minimalDocx("{{report.customer.displayName}}"))).andReturn();
            assertThat(versionAttempt.getResponse().getStatus()).isEqualTo(404);

            mvc.perform(post("/api/v1/exports/document-templates/" + foreignId + "/versions/1/publish")
                    .with(asPrincipal(adminA)))
                .andExpect(status().isNotFound());
        }

        /**
         * A definition belonging to another project in the same tenant.
         *
         * <p>{@code report_definitions."projectId"} is nullable, so "not in this project" has two meanings —
         * a row scoped to another project, and a tenant-wide row. {@code LIST_DEFINITIONS} reads both as
         * visible for a project that owns neither, which is correct: a tenant-wide definition is for every
         * project. What must not happen is a <em>write</em> across the project boundary, and that is
         * asserted here.
         */
        @Test
        @DisplayName("a project outside the caller's memberships is refused before a statement is built")
        void anOutOfScopeProjectIsRefused() throws Exception {
            mvc.perform(post("/api/v1/exports/report-definitions")
                    .param("projectId", PROJECT_B.toString())
                    .with(asPrincipal(adminA))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(MAPPER.writeValueAsString(Map.of(
                        "key", "cross-project", "nameAr", "ع", "nameEn", "E",
                        "timezone", "Asia/Riyadh", "localeDefault", "ar",
                        "redactionPolicy", "none", "retentionClass", "EXPORT_ARTEFACTS"))))
                .andExpect(status().isNotFound());
            assertThat(count("report_definitions", ORG_A)).isZero();
        }
    }

    // ==================================================================
    // THE VERSION LIFECYCLE, AGAINST THE SCHEMA'S OWN RULES
    // ==================================================================

    @Nested
    @DisplayName("the version lifecycle matches the schema's rules")
    class VersionLifecycle {

        @Test
        @DisplayName("a definition with no published version cannot be referenced by an export")
        void anUnpublishedDefinitionIsNotReferenceable() throws Exception {
            JsonNode definition = authorDefinition(fixture.adminA(), "draft-only");
            UUID definitionId = UUID.fromString(definition.get("reportDefinitionId").asText());
            JsonNode version = body(authorA("POST",
                "/api/v1/exports/report-definitions/" + definitionId + "/versions",
                Map.of("columns", List.of("customer.displayName", "customer.count"),
                    "dimensions", List.of("customer.displayName"),
                    "measures", List.of("customer.count"),
                    "changeNote", "never published")));
            int versionNumber = version.get("reportVersion").asInt();

            // The catalogue says so first, and says why.
            JsonNode offered = versionByNumber(definitionById(readCatalogue(adminA, PROJECT_A), definitionId),
                versionNumber);
            assertThat(offered.get("exportable").asBoolean())
                .as("a DRAFT has never been live, so offering it would be a button that always fails")
                .isFalse();
            assertThat(offered.get("refusal").asText()).contains("DRAFT");

            // And POST agrees, by refusal rather than by silence.
            MvcResult refused = create(adminA, Map.of(
                "reportDefinitionId", definitionId.toString(),
                "reportVersion", versionNumber, "format", "CSV")).andReturn();
            assertThat(refused.getResponse().getStatus()).isEqualTo(409);
            assertThat(MAPPER.readTree(refused.getResponse().getContentAsString()).get("code").asText())
                .isEqualTo("REPORT_VERSION_NOT_PUBLISHED");
            assertThat(count("export_jobs", ORG_A))
                .as("and no job row was left behind for a request that can never run")
                .isZero();

            // Positive control: publishing it makes the very same request succeed.
            publish(adminA, definitionId, versionNumber);
            create(adminA, Map.of(
                "reportDefinitionId", definitionId.toString(),
                "reportVersion", versionNumber, "format", "CSV"))
                .andExpect(status().is2xxSuccessful());
        }

        @Test
        @DisplayName("a template version that has never been published cannot be pinned either")
        void anUnpublishedTemplateVersionIsNotReferenceable() throws Exception {
            JsonNode template = body(authorA("POST", "/api/v1/exports/document-templates",
                Map.of("key", "draft-docx", "nameAr", "ق", "nameEn", "T",
                    "locale", "ar", "timezone", "Asia/Riyadh")));
            UUID templateId = UUID.fromString(template.get("documentTemplateId").asText());
            storedTemplateVersion(templateId, "ar", minimalDocx("{{report.customer.displayName}}"));

            JsonNode offered = firstTemplateVersion(readCatalogue(adminA, PROJECT_A), templateId);
            assertThat(offered.get("exportable").asBoolean()).isFalse();
            assertThat(offered.get("refusal").asText()).contains("DRAFT");

            JsonNode definition = authorDefinition(fixture.adminA(), "docx-host");
            UUID definitionId = UUID.fromString(definition.get("reportDefinitionId").asText());
            int versionNumber = body(authorA("POST",
                "/api/v1/exports/report-definitions/" + definitionId + "/versions",
                Map.of("columns", List.of("customer.displayName", "customer.count"),
                    "dimensions", List.of("customer.displayName"),
                    "measures", List.of("customer.count")))).get("reportVersion").asInt();
            publish(adminA, definitionId, versionNumber);

            create(adminA, Map.of(
                "reportDefinitionId", definitionId.toString(),
                "reportVersion", versionNumber,
                "documentTemplateId", templateId.toString(),
                "documentTemplateVersion", 1,
                "format", "DOCX"))
                .andExpect(status().isConflict());
            assertThat(count("export_jobs", ORG_A)).isZero();
        }

        /**
         * {@code report_versions_one_published_per_definition}, proved rather than asserted.
         *
         * <p>Publishing through the service retires the incumbent first, so a legitimate version bump works
         * and the index never fires. What the index exists for is a second body going live without that
         * step — and that is refused by the DATABASE, not by application ordering. So the update is issued
         * directly and the refusal is a duplicate key, after which the state is asserted unchanged.
         */
        @Test
        @DisplayName("a second live version is refused by the partial unique index, and nothing changes")
        void aSecondLiveVersionIsRefusedByTheIndex() throws Exception {
            JsonNode definition = authorDefinition(fixture.adminA(), "one-live-body");
            UUID definitionId = UUID.fromString(definition.get("reportDefinitionId").asText());
            int first = body(authorA("POST",
                "/api/v1/exports/report-definitions/" + definitionId + "/versions",
                Map.of("columns", List.of("customer.displayName", "customer.count"),
                    "dimensions", List.of("customer.displayName"),
                    "measures", List.of("customer.count")))).get("reportVersion").asInt();
            int second = body(authorA("POST",
                "/api/v1/exports/report-definitions/" + definitionId + "/versions",
                Map.of("columns", List.of("customer.displayName", "customer.count"),
                    "dimensions", List.of("customer.displayName"),
                    "measures", List.of("customer.count")))).get("reportVersion").asInt();
            assertThat(second).isEqualTo(first + 1);
            publish(adminA, definitionId, first);

            // Two DRAFTs coexist freely -- which is the whole reason DRAFT is the FIRST enum value.
            assertThat(count("report_versions", ORG_A)).isEqualTo(2);
            assertThat(countWhere("report_versions", ORG_A, "state", "DRAFT")).isEqualTo(1);

            assertThatThrownBy(() -> fixture.execute(
                "update report_versions set state = cast('PUBLISHED' as \"ReportStatus\"), "
                    + "\"publishedAt\" = :now, \"publishedByUserId\" = :actor "
                    + "where \"organizationId\" = :org "
                    + "and \"reportDefinitionId\" = :definition and \"versionNumber\" = :version",
                Map.of("now", java.time.LocalDateTime.now(java.time.ZoneOffset.UTC),
                    "actor", ADMIN_A, "org", ORG_A, "definition", definitionId, "version", second)))
                .as("the partial UNIQUE index is the arbiter, so a second live body loses on the index "
                    + "rather than on a check somebody remembered to write. Every CHECK-satisfying column "
                    + "is set, so the only thing that can refuse this statement is the index.")
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);

            assertThat(countWhere("report_versions", ORG_A, "state", "PUBLISHED"))
                .as("and the incumbent is still the only live body")
                .isEqualTo(1);
        }

        @Test
        @DisplayName("publishing the same version twice is refused, and the incumbent stays live")
        void publishingTwiceIsRefused() throws Exception {
            JsonNode definition = authorDefinition(fixture.adminA(), "double-publish");
            UUID definitionId = UUID.fromString(definition.get("reportDefinitionId").asText());
            int versionNumber = body(authorA("POST",
                "/api/v1/exports/report-definitions/" + definitionId + "/versions",
                Map.of("columns", List.of("customer.displayName", "customer.count"),
                    "dimensions", List.of("customer.displayName"),
                    "measures", List.of("customer.count")))).get("reportVersion").asInt();

            publish(adminA, definitionId, versionNumber);

            MvcResult again = mvc.perform(post("/api/v1/exports/report-definitions/" + definitionId
                + "/versions/" + versionNumber + "/publish").with(asPrincipal(adminA))).andReturn();
            assertThat(again.getResponse().getStatus())
                .as("a published body is immutable; the only transition left is RETIRED")
                .isEqualTo(409);
            assertThat(countWhere("report_versions", ORG_A, "state", "PUBLISHED")).isEqualTo(1);
            assertThat(countWhere("report_versions", ORG_A, "state", "RETIRED")).isZero();
        }

        @Test
        @DisplayName("a retirement must name its successor, because report_versions_retired_check requires it")
        void aRetirementMustNameItsSuccessor() throws Exception {
            JsonNode definition = authorDefinition(fixture.adminA(), "retire-needs-successor");
            UUID definitionId = UUID.fromString(definition.get("reportDefinitionId").asText());
            int first = body(authorA("POST",
                "/api/v1/exports/report-definitions/" + definitionId + "/versions",
                Map.of("columns", List.of("customer.displayName", "customer.count"),
                    "dimensions", List.of("customer.displayName"),
                    "measures", List.of("customer.count")))).get("reportVersion").asInt();
            int second = body(authorA("POST",
                "/api/v1/exports/report-definitions/" + definitionId + "/versions",
                Map.of("columns", List.of("customer.displayName", "customer.count"),
                    "dimensions", List.of("customer.displayName"),
                    "measures", List.of("customer.count")))).get("reportVersion").asInt();
            publish(adminA, definitionId, first);

            mvc.perform(post("/api/v1/exports/report-definitions/" + definitionId
                + "/versions/" + first + "/retire")
                    .with(asPrincipal(adminA))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                .andExpect(status().isBadRequest());

            mvc.perform(post("/api/v1/exports/report-definitions/" + definitionId
                + "/versions/" + first + "/retire")
                    .with(asPrincipal(adminA))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(MAPPER.writeValueAsString(Map.of("successorVersion", second))))
                .andExpect(status().isNoContent());
            assertThat(countWhere("report_versions", ORG_A, "state", "RETIRED")).isEqualTo(1);

            // And the retired body stays referenceable forever, which is the point of pinning.
            assertThat(versionByNumber(definitionById(readCatalogue(adminA, PROJECT_A), definitionId), first)
                .get("exportable").asBoolean())
                .as("ADR-0009 section 4: a version is never purged, so an export pinned to a retired "
                    + "version stays reproducible")
                .isTrue();
        }

        @Test
        @DisplayName("a rename is refused without the row version, because an unguarded rename overwrites")
        void aRenameNeedsTheRowVersion() throws Exception {
            JsonNode definition = authorDefinition(fixture.adminA(), "rename-guard");
            UUID definitionId = UUID.fromString(definition.get("reportDefinitionId").asText());
            int rowVersion = definition.get("rowVersion").asInt();

            mvc.perform(patch("/api/v1/exports/report-definitions/" + definitionId)
                    .with(asPrincipal(adminA))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(MAPPER.writeValueAsString(Map.of("nameAr", "ع", "nameEn", "E"))))
                .andExpect(status().isBadRequest());

            JsonNode renamed = body(authorA("PATCH", "/api/v1/exports/report-definitions/" + definitionId,
                Map.of("nameAr", "دليل العملاء المحدث", "nameEn", "Updated customer directory",
                    "expectedVersion", rowVersion)));
            assertThat(renamed.get("nameAr").asText()).isEqualTo("دليل العملاء المحدث");
            assertThat(renamed.get("rowVersion").asInt()).isGreaterThan(rowVersion);

            // A stale row version loses rather than overwrites.
            mvc.perform(patch("/api/v1/exports/report-definitions/" + definitionId)
                    .with(asPrincipal(adminA))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(MAPPER.writeValueAsString(Map.of("nameAr", "x", "nameEn", "x",
                        "expectedVersion", rowVersion))))
                .andExpect(status().isConflict());
            assertThat(definitionById(readCatalogue(adminA, PROJECT_A), definitionId).get("nameAr").asText())
                .isEqualTo("دليل العملاء المحدث");
        }

        /**
         * The commit defect, asserted on the object rather than on a boolean.
         *
         * <p>Phase 16 shipped a {@code createVersion} that wrote the bytes and never committed them to their
         * final key, so the version row named an object that was not there and every DOCX export failed at
         * generation. The read below is the exact call the generation path makes, made through the key the
         * row itself names — the check whose absence let that defect through two phases.
         */
        @Test
        @DisplayName("an authored template is readable by its own row's key, byte for byte")
        void anAuthoredTemplateIsReadableByItsOwnKey() throws Exception {
            JsonNode template = body(authorA("POST", "/api/v1/exports/document-templates",
                Map.of("key", "round-trip", "nameAr", "ق", "nameEn", "T",
                    "locale", "ar", "timezone", "Asia/Riyadh")));
            UUID templateId = UUID.fromString(template.get("documentTemplateId").asText());
            byte[] document = minimalDocx("{{report.customer.displayName}}");
            storedTemplateVersion(templateId, "ar", document);

            ReportCatalogRepositoryView row = storedRow(templateId, 1);
            byte[] readBack = store.read(row.storageKey());
            assertThat(readBack)
                .as("the object the version row names must contain exactly the document that was "
                    + "authored, or every DOCX export of this template fails at generation")
                .isEqualTo(document);
            assertThat(row.contentHash()).isEqualTo(ExportSignatureService.sha256(document));
            assertThat(row.storageKey())
                .as("an opaque, minted key: never a customer name and never a URL")
                .doesNotContain("?").doesNotContain("://");

            // The service's own resolution path, which is what a generation calls.
            byte[] resolved = templates.resolve(new com.firstelement.security.query.TenantScope(ORG_A, null),
                templateId, 1).bytes();
            assertThat(resolved).isEqualTo(document);
        }

        @Test
        @DisplayName("an undeclared placeholder is refused at authoring, and again at publication")
        void anUndeclaredPlaceholderIsRefused() throws Exception {
            JsonNode template = body(authorA("POST", "/api/v1/exports/document-templates",
                Map.of("key", "bad-placeholder", "nameAr", "ق", "nameEn", "T",
                    "locale", "ar", "timezone", "Asia/Riyadh")));
            UUID templateId = UUID.fromString(template.get("documentTemplateId").asText());

            mvc.perform(post("/api/v1/exports/document-templates/" + templateId + "/versions")
                    .param("locale", "ar")
                    .with(asPrincipal(adminA))
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .content(minimalDocx("{{report.customer.thisFieldDoesNotExist}}")))
                .andExpect(status().isUnprocessableEntity());
            assertThat(count("document_template_versions", ORG_A))
                .as("no version row for a template nothing can render, and no object written for it")
                .isZero();

            // ---- and the SAME condition at publication, on a row written outside the application ----
            // placeholderSchema is jsonb, so anything that wrote this row other than createVersion -- a
            // migration, a backfill, a future authoring surface -- can put a name in it the registry does
            // not declare. Publication is the moment such a version becomes live, so publication is where
            // the stored vocabulary is checked. createVersion already refused the same name above, so this
            // half is only reachable by writing the column directly, which is exactly why it is asserted
            // that way rather than being assumed unreachable.
            storedTemplateVersion(templateId, "ar", minimalDocx("{{report.customer.displayName}}"));
            assertThat(count("document_template_versions", ORG_A)).isEqualTo(1);
            fixture.execute(
                "update document_template_versions set \"placeholderSchema\" = "
                    + "cast(:schema as jsonb) where \"organizationId\" = :org "
                    + "and \"documentTemplateId\" = :template",
                Map.of("schema", "{\"placeholders\":[\"report.customer.thisFieldDoesNotExist\"]}",
                    "org", ORG_A, "template", templateId));

            mvc.perform(post("/api/v1/exports/document-templates/" + templateId + "/versions/1/publish")
                    .with(asPrincipal(adminA)))
                .andExpect(status().isUnprocessableEntity());
            assertThat(countWhere("document_template_versions", ORG_A, "state", "PUBLISHED"))
                .as("the version did not go live")
                .isZero();
            assertThat(countWhere("document_template_versions", ORG_A, "state", "DRAFT"))
                .isEqualTo(1);
        }

        @Test
        @DisplayName("a body that is not a DOCX is refused before any object is written")
        void aNonDocxIsRefused() throws Exception {
            JsonNode template = body(authorA("POST", "/api/v1/exports/document-templates",
                Map.of("key", "not-a-docx", "nameAr", "ق", "nameEn", "T",
                    "locale", "ar", "timezone", "Asia/Riyadh")));
            UUID templateId = UUID.fromString(template.get("documentTemplateId").asText());

            mvc.perform(post("/api/v1/exports/document-templates/" + templateId + "/versions")
                    .param("locale", "ar")
                    .with(asPrincipal(adminA))
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .content("%PDF-1.7 not really a pdf either".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isUnprocessableEntity());
            assertThat(count("document_template_versions", ORG_A)).isZero();
        }

        @Test
        @DisplayName("a body naming a field the registry does not declare never reaches the database")
        void anUndeclaredColumnIsRefused() throws Exception {
            JsonNode definition = authorDefinition(fixture.adminA(), "bad-column");
            UUID definitionId = UUID.fromString(definition.get("reportDefinitionId").asText());

            mvc.perform(post("/api/v1/exports/report-definitions/" + definitionId + "/versions")
                    .param("projectId", PROJECT_A.toString())
                    .with(asPrincipal(adminA))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(MAPPER.writeValueAsString(Map.of(
                        "columns", List.of("customer.nickname"),
                        "dimensions", List.of("customer.displayName"),
                        "measures", List.of("customer.count")))))
                .andExpect(status().isUnprocessableEntity());
            assertThat(count("report_versions", ORG_A)).isZero();
        }
    }

    // ==================================================================
    // THE CATALOGUE AND POST AGREE
    // ==================================================================

    @Nested
    @DisplayName("the catalogue and POST agree, empirically")
    class CatalogueAgreesWithPost {

        @Test
        @DisplayName("every version the catalogue offers is accepted, every withheld one is refused")
        void offeredAndWithheldAgreeWithPost() throws Exception {
            // Authored here, over HTTP, so the catalogue being read describes only what this test built.
            JsonNode definition = authorDefinition(fixture.adminA(), "agreement");
            UUID definitionId = UUID.fromString(definition.get("reportDefinitionId").asText());
            int published = body(authorA("POST",
                "/api/v1/exports/report-definitions/" + definitionId + "/versions",
                Map.of("columns", List.of("customer.displayName", "customer.count",
                        "customer.primaryEmail", "customer.firstSeenAt"),
                    "dimensions", List.of("customer.displayName", "customer.primaryEmail"),
                    "measures", List.of("customer.count")))).get("reportVersion").asInt();
            publish(adminA, definitionId, published);
            // A second, unpublished body of the SAME definition: the case where the catalogue and the create
            // route could disagree, and where Phase 30's absence of a version rule made them.
            int draft = body(authorA("POST",
                "/api/v1/exports/report-definitions/" + definitionId + "/versions",
                Map.of("columns", List.of("customer.displayName", "customer.count"),
                    "dimensions", List.of("customer.displayName"),
                    "measures", List.of("customer.count")))).get("reportVersion").asInt();

            fixture.customerInProjectA();
            JsonNode catalogue = readCatalogue(adminA, PROJECT_A);

            int offered = 0;
            int withheld = 0;
            for (JsonNode version : definitionById(catalogue, definitionId).get("versions")) {
                Map<String, Object> request = new LinkedHashMap<>();
                request.put("reportDefinitionId", definitionId.toString());
                request.put("reportVersion", version.get("reportVersion").asInt());
                request.put("format", "CSV");
                boolean exportable = version.get("exportable").asBoolean();
                int status = create(adminA, request).andReturn().getResponse().getStatus();
                if (exportable) {
                    offered++;
                    assertThat(status)
                        .as("the catalogue offered version %s as exportable, so POST must accept it",
                            version.get("reportVersion").asInt())
                        .isBetween(200, 299);
                } else {
                    withheld++;
                    assertThat(status)
                        .as("the catalogue withheld version %s (reason: %s), so POST must refuse it",
                            version.get("reportVersion").asInt(), version.get("refusal").asText())
                        .isEqualTo(409);
                }
            }
            assertThat(offered).as("the published body is offered").isEqualTo(1);
            assertThat(withheld).as("the DRAFT is withheld").isEqualTo(1);
            assertThat(draft).isNotEqualTo(published);
            assertThat(count("export_jobs", ORG_A))
                .as("only the offered version produced a job")
                .isEqualTo(1);
        }

        @Test
        @DisplayName("the authoring capability is published so a client does not build a screen of 404s")
        void theAuthoringCapabilityIsPublished() throws Exception {
            JsonNode forAdmin = body(authorA("GET", "/api/v1/exports/authoring", null));
            assertThat(forAdmin.get("mayAuthor").asBoolean()).isTrue();
            assertThat(forAdmin.get("permission").asText()).isEqualTo("report:manage");
            assertThat(forAdmin.get("redactionPolicies").toString()).contains("no-contact-details");
            assertThat(forAdmin.get("locales").toString()).contains("ar");

            JsonNode forEmployee = body(authorA("GET", "/api/v1/exports/authoring", null,
                fixture.employeeA()));
            assertThat(forEmployee.get("mayAuthor").asBoolean())
                .as("UX hint only -- the routes refuse regardless, which is what the 404s above assert")
                .isFalse();
        }
    }

    // ==================================================================
    // helpers
    // ==================================================================

    private record ReportCatalogRepositoryView(String storageKey, String contentHash) {
    }

    private MockHttpServletRequestBuilder authorA(String method, String path, Object body) throws Exception {
        return authored(adminA, method, path, body);
    }

    private MockHttpServletRequestBuilder authorA(String method, String path, Object body,
                                                 TenantContext as) throws Exception {
        return authored(as, method, path, body);
    }

    private MockHttpServletRequestBuilder authored(TenantContext caller, String method, String path,
                                                  Object body) throws Exception {
        MockHttpServletRequestBuilder builder = switch (method) {
            case "GET" -> get(path);
            case "PATCH" -> patch(path);
            case "POST" -> post(path);
            default -> throw new IllegalArgumentException("unsupported method " + method);
        };
        if (path.endsWith("/report-definitions") || path.endsWith("/document-templates")) {
            builder.param("projectId", caller.hasProject(PROJECT_A) ? PROJECT_A.toString()
                : PROJECT_B.toString());
        }
        builder.with(asPrincipal(caller));
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(body));
        }
        return builder;
    }

    private JsonNode created(MockHttpServletRequestBuilder builder) throws Exception {
        MvcResult result = mvc.perform(builder).andExpect(status().isCreated()).andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode body(MockHttpServletRequestBuilder builder) throws Exception {
        MvcResult result = mvc.perform(builder).andExpect(status().is2xxSuccessful()).andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    /**
     * Publishes a version, asserting it worked.
     *
     * <p>A named method rather than an inline {@code mvc.perform(...)} because {@code authorA(...)} alone
     * builds a request and executes nothing. Four lifecycle tests asserted their outcome after a publish that
     * never happened, and every one of them failed on the assertion two steps later with a message about
     * something else — which is the exact shape of "a helper that looks like an action".
     */
    private void publish(TenantContext caller, UUID definitionId, int versionNumber) throws Exception {
        mvc.perform(post("/api/v1/exports/report-definitions/" + definitionId + "/versions/"
                + versionNumber + "/publish")
                .with(asPrincipal(caller)))
            .andExpect(status().isOk());
    }

    private JsonNode authorDefinition(TenantContext caller, String key) throws Exception {
        return created(authored(caller, "POST", "/api/v1/exports/report-definitions", Map.of(
            "key", key,
            "nameAr", "دليل العملاء",
            "nameEn", "Customer directory",
            "descriptionAr", "تقرير العملاء",
            "descriptionEn", "Customer report",
            "timezone", "Asia/Riyadh",
            "localeDefault", "ar",
            "requiredPermissions", List.of("customer:read"),
            "redactionPolicy", "none",
            "retentionClass", "EXPORT_ARTEFACTS")));
    }

    private JsonNode storedTemplateVersion(UUID templateId, String locale, byte[] document)
        throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/exports/document-templates/" + templateId
                + "/versions")
                .param("locale", locale)
                .param("changeNote", "authored over http")
                .with(asPrincipal(adminA))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(document))
            .andExpect(status().isCreated())
            .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    /** The version row itself, read through the repository, so the test asserts on storage not on a DTO. */
    private ReportCatalogRepositoryView storedRow(UUID templateId, int versionNumber) {
        // Through the principal scope, because the service method carries @RequirePermission and is an AOP
        // proxy: calling it bare would fail for "no credential" rather than for a real reason.
        var row = ExportsPrincipalScope.call(adminA, () -> templates.versions(adminA, templateId).stream()
            .filter(candidate -> candidate.versionNumber() == versionNumber)
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "no template version row for template " + templateId + " version " + versionNumber)));
        return new ReportCatalogRepositoryView(row.storageKey(), row.contentHash());
    }

    private ExportJobService.GenerationOutcome generate(TenantContext requester, UUID jobId) {
        UUID projectId = jobRepository.findForDownload(scopeA(PROJECT_A), jobId).orElseThrow().projectId();
        return ExportsPrincipalScope.call(requester, () -> jobs.generate(requester,
            new com.firstelement.security.query.TenantScope(ORG_A, projectId), jobId, WORKER));
    }

    private ResultActions create(TenantContext caller, Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/v1/exports")
            .param("projectId", PROJECT_A.toString())
            .with(asPrincipal(caller))
            .contentType(MediaType.APPLICATION_JSON)
            .content(MAPPER.writeValueAsString(body)));
    }

    /**
     * Every authoring route, aimed at one target id.
     *
     * <p>Both a real id and a random one are exercised: a real one proves a known target is still refused,
     * a random one proves the answer is the same when nothing exists — which is what makes the answer
     * non-disclosing rather than merely restrictive.
     *
     * <p>The two CREATE routes are included here and separated out by {@link #targetedAuthoringRequests}:
     * they name no target, so an authorised caller gets a 201 from them no matter which tenant the id in the
     * neighbouring calls belongs to. Asserting 404 on them for an authorised principal would be asserting
     * the wrong thing.
     */
    private List<MockHttpServletRequestBuilder> authoringRequests(TenantContext caller, UUID definitionId)
        throws Exception {
        List<MockHttpServletRequestBuilder> requests =
            new ArrayList<>(targetedAuthoringRequests(caller, definitionId));
        requests.add(authored(caller, "POST", "/api/v1/exports/report-definitions", Map.of(
            "key", "probe-" + UUID.randomUUID(), "nameAr", "ع", "nameEn", "E",
            "timezone", "Asia/Riyadh", "localeDefault", "ar",
            "redactionPolicy", "none", "retentionClass", "EXPORT_ARTEFACTS")));
        requests.add(authored(caller, "POST", "/api/v1/exports/document-templates", Map.of(
            "key", "probe-" + UUID.randomUUID(), "nameAr", "ق", "nameEn", "T",
            "locale", "ar", "timezone", "Asia/Riyadh")));
        return requests;
    }

    /** The authoring routes that act ON a supplied id, which is what a cross-tenant probe is about. */
    private List<MockHttpServletRequestBuilder> targetedAuthoringRequests(TenantContext caller, UUID target)
        throws Exception {
        List<MockHttpServletRequestBuilder> requests = new ArrayList<>();
        requests.add(patch("/api/v1/exports/report-definitions/" + target)
            .with(asPrincipal(caller)).contentType(MediaType.APPLICATION_JSON)
            .content(MAPPER.writeValueAsString(Map.of("nameAr", "ع", "nameEn", "E",
                "expectedVersion", 0))));
        requests.add(authored(caller, "POST",
            "/api/v1/exports/report-definitions/" + target + "/versions",
            Map.of("columns", List.of("customer.displayName", "customer.count"),
                "dimensions", List.of("customer.displayName"),
                "measures", List.of("customer.count"))));
        requests.add(post("/api/v1/exports/report-definitions/" + target + "/versions/1/publish")
            .with(asPrincipal(caller)));
        requests.add(post("/api/v1/exports/report-definitions/" + target + "/versions/1/retire")
            .with(asPrincipal(caller)).contentType(MediaType.APPLICATION_JSON)
            .content(MAPPER.writeValueAsString(Map.of("successorVersion", 2))));
        requests.add(post("/api/v1/exports/document-templates/" + target + "/versions")
            .param("locale", "ar").with(asPrincipal(caller))
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .content(minimalDocx("{{report.customer.displayName}}")));
        requests.add(post("/api/v1/exports/document-templates/" + target + "/versions/1/publish")
            .with(asPrincipal(caller)));
        requests.add(post("/api/v1/exports/document-templates/" + target + "/versions/1/retire")
            .with(asPrincipal(caller)).contentType(MediaType.APPLICATION_JSON)
            .content(MAPPER.writeValueAsString(Map.of("successorVersion", 2))));
        return requests;
    }

    private static RequestPostProcessor asPrincipal(TenantContext context) {
        return request -> {
            var securityContext = SecurityContextHolder.createEmptyContext();
            securityContext.setAuthentication(new TenantAuthentication(context));
            SecurityContextHolder.setContext(securityContext);
            TenantContextHolder.set(context);
            return request;
        };
    }

    private JsonNode readCatalogue(TenantContext caller, UUID projectId) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/exports/catalogue")
                .param("projectId", projectId.toString())
                .with(asPrincipal(caller)))
            .andExpect(status().isOk())
            .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private static JsonNode definitionById(JsonNode catalogue, UUID definitionId) {
        for (JsonNode definition : catalogue.get("reportDefinitions")) {
            if (definitionId.toString().equals(definition.get("reportDefinitionId").asText())) {
                return definition;
            }
        }
        return null;
    }

    private static JsonNode versionByNumber(JsonNode definition, int reportVersion) {
        for (JsonNode version : definition.get("versions")) {
            if (version.get("reportVersion").asInt() == reportVersion) {
                return version;
            }
        }
        throw new AssertionError("the catalogue carries no version " + reportVersion + " of "
            + definition.get("reportDefinitionId").asText());
    }

    private static JsonNode templateByKey(JsonNode catalogue, String key) {
        for (JsonNode template : catalogue.get("documentTemplates")) {
            if (key.equals(template.get("key").asText())) {
                return template;
            }
        }
        throw new AssertionError("the catalogue carries no document template with key " + key);
    }

    private static JsonNode firstTemplateVersion(JsonNode catalogue, UUID templateId) {
        for (JsonNode template : catalogue.get("documentTemplates")) {
            if (templateId.toString().equals(template.get("documentTemplateId").asText())) {
                return template.get("versions").get(0);
            }
        }
        throw new AssertionError("the catalogue carries no document template " + templateId);
    }

    private static List<String> definitionIds(JsonNode catalogue) {
        List<String> ids = new ArrayList<>();
        catalogue.get("reportDefinitions").forEach(node -> ids.add(node.get("reportDefinitionId").asText()));
        return ids;
    }

    private static List<String> templateIds(JsonNode catalogue) {
        List<String> ids = new ArrayList<>();
        catalogue.get("documentTemplates").forEach(node -> ids.add(node.get("documentTemplateId").asText()));
        return ids;
    }

    /** Rows of one table in one tenant. The harness read; never a production statement. */
    private int count(String table, UUID organizationId) {
        return fixture.count("select 1 from " + table + " where \"organizationId\" = :__fe_tenant_organization_id",
            Map.of("__fe_tenant_organization_id", organizationId));
    }

    private int countWhere(String table, UUID organizationId, String column, String value) {
        // cast, because the column is a PostgreSQL enum and an untyped-varchar parameter has no operator
        // against one: `state = $1` with setString is an error, not a comparison.
        return fixture.count("select 1 from " + table
            + " where \"organizationId\" = :__fe_tenant_organization_id "
            + "and cast(\"" + column + "\" as text) = :value",
            Map.of("__fe_tenant_organization_id", organizationId, "value", value));
    }

    /**
     * The smallest DOCX this module will accept: a real OOXML package naming one declared report field.
     *
     * <p>A hand-rolled zip would make the test assert something the module would never accept, because
     * {@code requireReadableDocx} reads the bytes as an {@code XWPFDocument}.
     */
    private static byte[] minimalDocx(String placeholderText) {
        try (org.apache.poi.xwpf.usermodel.XWPFDocument document =
                 new org.apache.poi.xwpf.usermodel.XWPFDocument();
             java.io.ByteArrayOutputStream sink = new java.io.ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText(placeholderText);
            document.write(sink);
            return sink.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("the test could not build a DOCX template", e);
        }
    }
}