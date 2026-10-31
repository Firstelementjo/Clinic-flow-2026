package com.firstelement.exports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.firstelement.security.TenantAuthentication;
import com.firstelement.security.TenantContext;
import com.firstelement.security.TenantContextHolder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * <b>The capability test.</b> A client that reads only {@code GET /api/v1/exports/catalogue} can build a
 * {@code POST /api/v1/exports} request and have it accepted.
 *
 * <h2>Why this suite is the centre of Phase 16</h2>
 *
 * <p>The create route required four opaque ids â€” a report definition, a report version, a document
 * template and a document template version â€” and <b>no route published any of them</b>. The catalogue
 * existed but declared {@code type: object, additionalProperties: {}}, so a generated client typed the
 * response {@code unknown} and the Phase-11 Angular screen could render nothing and post nothing. An
 * export could therefore only be created by someone who already had database access.
 *
 * <p>Every other assertion in this module describes behaviour that was already reachable. This one
 * describes behaviour that was not.
 *
 * <h2>No id in this suite is hand-typed</h2>
 *
 * <p>Every id and version used below is read out of a catalogue response. A test that named a fixture id
 * directly would pass on a server whose catalogue had lost the entry â€” the exact failure this suite exists
 * to catch â€” so the helpers accept only what the HTTP response carried.
 *
 * <h2>Every negative has a positive control beside it</h2>
 *
 * <p>Tenant A has two projects, tenant B has its own tenant, and an EMPLOYEE holds strictly fewer
 * permissions than an ADMIN. A refusal that follows a successful read of the same shape inside the
 * caller's own scope cannot be an empty fixture, and this suite never asserts a negative on its own.
 */
@SpringBootTest(classes = ExportsTestBase.ExportsTestApplication.class)
// Filters off so the servlet chain's session/cookie requirement does not stand between this suite and the
// capability it proves. Nothing that matters HERE is weakened: @RequirePermission still runs â€” method
// security is an AOP proxy, not a filter â€” and asPrincipal establishes the thread bindings the production
// filter chain would. The chain itself is identity-access's to prove and is covered by
// AdminRoutePermissionHttpIntegrationTest.
@AutoConfigureMockMvc(addFilters = false)
class ExportCatalogueCapabilityIntegrationTest extends ExportsTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ExportsFixture fixture;

    private TenantContext adminA;
    private TenantContext employeeA;
    private UUID reportA;
    private UUID templateA;

    @BeforeEach
    void seed() {
        fixture.reset();
        fixture.seedOrganizationsAndUsers();
        adminA = fixture.adminA();
        employeeA = fixture.employeeA();
        reportA = fixture.publishedCustomerReport(adminA, PROJECT_A, "none");
        templateA = fixture.publishedDocxTemplate(adminA, PROJECT_A, "ar");
        fixture.customerInProjectA();
    }

    // ==================================================================
    // the capability
    // ==================================================================

    @Nested
    @DisplayName("a client that reads ONLY the catalogue can create an export")
    class CatalogueOnlyClient {

        @Test
        @DisplayName("catalogue -> POST returns 202, not a 400 for unknown ids")
        void catalogueAloneIsEnoughToCreateAnExport() throws Exception {
            JsonNode catalogue = readCatalogue(adminA, PROJECT_A);

            // Every id below is taken from the response. Nothing is known in advance -- this is the whole
            // point of the suite, so a fixture id appearing anywhere below would be a defect in it.
            JsonNode definition = firstExportableDefinition(catalogue);
            JsonNode version = firstExportableVersion(definition);
            JsonNode template = firstExportableTemplate(catalogue);
            JsonNode templateVersion = firstExportableTemplateVersion(template);

            assertThat(definition.get("reportDefinitionId").asText())
                .as("the report id the catalogue published is the one the fixture authored")
                .isEqualTo(reportA.toString());
            assertThat(template.get("documentTemplateId").asText())
                .isEqualTo(templateA.toString());

            MvcResult created = create(adminA, PROJECT_A, Map.of(
                "reportDefinitionId", definition.get("reportDefinitionId").asText(),
                "reportVersion", version.get("reportVersion").asInt(),
                "documentTemplateId", template.get("documentTemplateId").asText(),
                "documentTemplateVersion", templateVersion.get("documentTemplateVersion").asInt(),
                "format", "DOCX"))
                .andExpect(status().is2xxSuccessful())
                .andReturn();

            // 202 Accepted specifically: the work is durable and no artefact exists yet. A 200 CREATED
            // would be a lie about the lifecycle, and a 400 here is the defect this phase closes.
            assertThat(created.getResponse().getStatus()).isEqualTo(202);

            JsonNode body = MAPPER.readTree(created.getResponse().getContentAsString());
            assertThat(body.get("jobId").asText()).isNotBlank();
            assertThat(body.get("status").asText()).isEqualTo("QUEUED");
            assertThat(body.get("format").asText()).isEqualTo("DOCX");
            assertThat(body.get("columns"))
                .as("the response echoes the columns this caller may see; had the catalogue offered a "
                    + "version the create route refuses, this body would not exist")
                .isNotEmpty();
        }

        @Test
        @DisplayName("every exportable version the catalogue offers is one POST accepts")
        void everyOfferedVersionIsAccepted() throws Exception {
            JsonNode catalogue = readCatalogue(adminA, PROJECT_A);

            int posted = 0;
            for (JsonNode definition : catalogue.get("reportDefinitions")) {
                for (JsonNode version : definition.get("versions")) {
                    if (!version.get("exportable").asBoolean()) {
                        continue;
                    }
                    // No template: a CSV export needs none, so this isolates the report pin.
                    create(adminA, PROJECT_A, Map.of(
                        "reportDefinitionId", definition.get("reportDefinitionId").asText(),
                        "reportVersion", version.get("reportVersion").asInt(),
                        "format", "CSV"))
                        .andExpect(status().is2xxSuccessful());
                    posted++;
                }
            }
            assertThat(posted)
                .as("the fixture publishes a report; if this is zero the catalogue offered nothing "
                    + "usable and the capability test above proved nothing")
                .isPositive();
        }

        @Test
        @DisplayName("every template version the catalogue offers is one a DOCX export accepts")
        void everyOfferedTemplateVersionIsAccepted() throws Exception {
            JsonNode catalogue = readCatalogue(adminA, PROJECT_A);
            JsonNode definition = firstExportableDefinition(catalogue);
            JsonNode version = firstExportableVersion(definition);
            JsonNode template = firstExportableTemplate(catalogue);

            int posted = 0;
            for (JsonNode templateVersion : template.get("versions")) {
                if (!templateVersion.get("exportable").asBoolean()) {
                    continue;
                }
                create(adminA, PROJECT_A, Map.of(
                    "reportDefinitionId", definition.get("reportDefinitionId").asText(),
                    "reportVersion", version.get("reportVersion").asInt(),
                    "documentTemplateId", template.get("documentTemplateId").asText(),
                    "documentTemplateVersion", templateVersion.get("documentTemplateVersion").asInt(),
                    "format", "DOCX"))
                    .andExpect(status().is2xxSuccessful());
                posted++;
            }
            assertThat(posted)
                .as("a catalogue offering version 0 of a DOCX template, or none at all, leaves the DOCX "
                    + "path unreachable for a client that reads only this route")
                .isPositive();
        }

        @Test
        @DisplayName("a version the catalogue WITHHOLDS is one POST refuses -- never offered then refused")
        void aWithheldVersionIsRefusedByPost() throws Exception {
            // An EMPLOYEE holds ANALYTICS_READ_PROJECT but not ANALYTICS_READ_ORGANIZATION, so a report
            // declaring the latter is unreadable for them. The catalogue must mark it not exportable and
            // say why; POST must refuse it. A catalogue that offered it would leave the client offering a
            // button that always answers 403, which reads as a broken feature rather than as a denial.
            UUID usageReport = fixture.publishedUsageReport(adminA, PROJECT_A);

            JsonNode catalogue = readCatalogue(employeeA, PROJECT_A);
            JsonNode definition = byDefinitionId(catalogue, usageReport);
            assertThat(definition)
                .as("the definition is in the employee's catalogue -- it is the exportability that "
                    + "differs, not the visibility of the report itself")
                .isNotNull();

            JsonNode version = firstVersion(definition);
            assertThat(version.get("exportable").asBoolean())
                .as("the definition requires analytics:read-organization, which an EMPLOYEE lacks")
                .isFalse();
            assertThat(version.get("refusal").asText())
                .contains("analytics:read-organization");

            create(employeeA, PROJECT_A, Map.of(
                "reportDefinitionId", definition.get("reportDefinitionId").asText(),
                "reportVersion", version.get("reportVersion").asInt(),
                "format", "CSV"))
                .andExpect(status().isForbidden());

            // Positive control beside the refusal: the same caller CAN export the report it may read, so
            // the 403 above is about this report's requirement and not about the employee.
            JsonNode readable = byDefinitionId(catalogue, reportA);
            create(employeeA, PROJECT_A, Map.of(
                "reportDefinitionId", readable.get("reportDefinitionId").asText(),
                "reportVersion", firstExportableVersion(readable).get("reportVersion").asInt(),
                "format", "CSV"))
                .andExpect(status().is2xxSuccessful());
        }
    }

    // ==================================================================
    // tenant and project scope
    // ==================================================================

    @Nested
    @DisplayName("the catalogue is scoped to the caller's tenant and project")
    class TenantAndProjectScope {

        @Test
        @DisplayName("another tenant's definitions and templates never appear")
        void anotherTenantIsInvisible() throws Exception {
            UUID reportB = fixture.publishedCustomerReport(fixture.adminB(), PROJECT_B, "none");
            fixture.publishedDocxTemplate(fixture.adminB(), PROJECT_B, "ar");

            JsonNode catalogue = readCatalogue(adminA, PROJECT_A);

            assertThat(definitionIds(catalogue))
                .as("tenant A's catalogue must not carry tenant B's report definition")
                .doesNotContain(reportB.toString());
            // Read tenant B's catalogue first so the exclusion below is "none of those" rather than "none
            // of an empty list" -- otherwise the negative is satisfied by a fixture that seeded nothing.
            JsonNode theirs = readCatalogue(fixture.adminB(), PROJECT_B);
            assertThat(templateIds(theirs))
                .as("tenant B's own catalogue carries its template, so the exclusion below is the tenant "
                    + "predicate and not an empty fixture")
                .isNotEmpty();

            assertThat(templateIds(catalogue))
                .as("nor tenant B's document template")
                .doesNotContainAnyElementsOf(templateIds(theirs));
            assertThat(definitionIds(theirs))
                .as("and tenant B's own catalogue carries its own definition")
                .contains(reportB.toString());
        }

        @Test
        @DisplayName("a definition scoped to another project is absent from this project's catalogue")
        void projectScopeIsHonoured() throws Exception {
            UUID reportInA2 = fixture.publishedCustomerReport(adminA, PROJECT_A2, "none");

            assertThat(definitionIds(readCatalogue(adminA, PROJECT_A)))
                .doesNotContain(reportInA2.toString());
            assertThat(definitionIds(readCatalogue(adminA, PROJECT_A2)))
                .as("the same caller, asked about the other project they hold, does see it -- so the "
                    + "exclusion above is the project predicate and not an empty read")
                .contains(reportInA2.toString());
        }

        @Test
        @DisplayName("a project outside the caller's memberships is refused, not returned empty")
        void anOutOfScopeProjectIsRefused() throws Exception {
            // The administrator holds PROJECT_A and PROJECT_A2, so the refusal needs a project that exists
            // and is genuinely not theirs. PROJECT_B is tenant B's, which is the sharpest probe: it
            // exists, it is readable by somebody, and it is not theirs.
            mvc.perform(get("/api/v1/exports/catalogue")
                .param("projectId", PROJECT_B.toString())
                .with(asPrincipal(adminA)))
                .andExpect(status().isNotFound());

            // Positive control: the project they do hold answers normally.
            mvc.perform(get("/api/v1/exports/catalogue")
                .param("projectId", PROJECT_A.toString())
                .with(asPrincipal(adminA)))
                .andExpect(status().isOk());
        }

        @Test
        @DisplayName("the catalogue requires a project, because an export always names one")
        void theCatalogueRefusesToAnswerWithoutAProject() throws Exception {
            // export_jobs."projectId" is NOT NULL, so a project-less catalogue could only describe
            // requests the create route refuses. Offering one would be the same defect in a new place.
            mvc.perform(get("/api/v1/exports/catalogue").with(asPrincipal(adminA)))
                .andExpect(status().isBadRequest());
        }
    }

    // ==================================================================
    // closed vocabularies at the request boundary
    // ==================================================================

    @Nested
    @DisplayName("format, locale and filters are closed at the request boundary")
    class ClosedVocabularies {

        @Test
        @DisplayName("an undeclared format is refused with 400, not 500 and not ignored")
        void anUndeclaredFormatIsRefused() throws Exception {
            MvcResult result = postWith("format", "parquet");

            assertThat(result.getResponse().getStatus())
                .as("a malformed value is a malformed request. It must never reach PostgreSQL's enum cast "
                    + "and become a 500, which is exactly how this reached an Angular client.")
                .isEqualTo(400);
            assertThat(messageOf(result))
                .contains("CSV")
                .contains("DOCX");
        }

        @Test
        @DisplayName("a format the schema declares but the renderer cannot produce is refused with 422")
        void anUnrenderableFormatIsRefused() throws Exception {
            MvcResult result = postWith("format", "PDF");

            assertThat(result.getResponse().getStatus())
                .as("PDF is a real \"ExportFormat\" value, so this is a capability gap (422) rather than a "
                    + "malformed request (400) -- and neither may be a 500")
                .isEqualTo(422);
            assertThat(codeOf(result)).isEqualTo("EXPORT_FORMAT_UNSUPPORTED");
        }

        @Test
        @DisplayName("every format the catalogue publishes is one POST accepts")
        void thePublishedFormatsAreAccepted() throws Exception {
            JsonNode catalogue = readCatalogue(adminA, PROJECT_A);
            JsonNode definition = firstExportableDefinition(catalogue);
            JsonNode version = firstExportableVersion(definition);

            for (JsonNode format : catalogue.get("formats")) {
                createWith(adminA, Map.of(
                    "reportDefinitionId", definition.get("reportDefinitionId").asText(),
                    "reportVersion", version.get("reportVersion").asInt(),
                    "format", format.asText()))
                    .andExpect(status().is2xxSuccessful());
            }
            assertThat(catalogue.get("formats")).hasSize(2);
        }

        /**
         * The subset property, over the wire rather than over a Java list.
         *
         * <p>The existing test above proves every published format is accepted; this one states the property
         * that makes the catalogue trustworthy <em>as a set</em>, and adds the two facts that can only be seen
         * in a real response: the published values are the wire values the database stores (so they can be
         * echoed straight back into a request), and each default is a member of the set it defaults from.
         *
         * <p>Asserted against {@link com.firstelement.exports.job.ExportFormat#acceptedValues()} — the list
         * POST refuses against — and not against a second copy of it. A copy would drift, and a drifted copy
         * in an assertion is worse than no assertion: it fails for a reason unrelated to the defect.
         */
        @Test
        @DisplayName("the published sets are a SUBSET of what POST accepts, and each default is a member")
        void thePublishedSetsAreASubsetOfWhatPostAccepts() throws Exception {
            JsonNode catalogue = readCatalogue(adminA, PROJECT_A);

            assertThat(catalogue.get("formats"))
                .as("formats must be a subset of the accepted set, and must not be EMPTY: a subset that is "
                    + "empty advertises nothing and is what an unpopulated catalogue looks like")
                .isNotEmpty()
                .allSatisfy(value -> assertThat(
                    com.firstelement.exports.job.ExportFormat.acceptedValues()).contains(value.asText()));

            assertThat(catalogue.get("locales"))
                .isNotEmpty()
                .allSatisfy(value -> assertThat(
                    com.firstelement.exports.job.ExportLocale.acceptedValues()).contains(value.asText()));

            // The wire values, not the Java constant names. `AR` would be a value a client sends and a
            // document that says `ar` is what it should have been told.
            assertThat(catalogue.get("formats").toString()).doesNotContain("\"AR\"");
            assertThat(catalogue.get("locales").toString())
                .as("the published locales are the lower-case wire values a \"Locale\" column stores")
                .contains("ar")
                .contains("en")
                .doesNotContain("\"AR\"")
                .doesNotContain("\"EN\"");

            assertThat(catalogue.get("defaultFormat").asText())
                .as("the default a client is told to rely on must be one the catalogue offers")
                .isIn(stringValues(catalogue.get("formats")));
            assertThat(catalogue.get("defaultLocale").asText())
                .isIn(stringValues(catalogue.get("locales")));

            // And the default is genuinely usable: an export that names no format gets this one.
            JsonNode definition = firstExportableDefinition(catalogue);
            createWith(adminA, Map.of(
                "reportDefinitionId", definition.get("reportDefinitionId").asText(),
                "reportVersion", firstExportableVersion(definition).get("reportVersion").asInt()))
                .andExpect(status().is2xxSuccessful());
        }

        @Test
        @DisplayName("an undeclared locale is refused with 400, not rendered in another language")
        void anUndeclaredLocaleIsRefused() throws Exception {
            MvcResult result = postWith("locale", "fr");

            assertThat(result.getResponse().getStatus())
                .as("a locale the \"Locale\" enum cannot store cannot be rendered. Substituting one would "
                    + "produce a file that looks right and is in the wrong language, which is the worst "
                    + "outcome in this subsystem.")
                .isEqualTo(400);
            assertThat(messageOf(result)).contains("ar").contains("en");
        }

        @Test
        @DisplayName("every locale the catalogue publishes is one POST accepts")
        void thePublishedLocalesAreAccepted() throws Exception {
            JsonNode catalogue = readCatalogue(adminA, PROJECT_A);
            JsonNode definition = firstExportableDefinition(catalogue);
            JsonNode version = firstExportableVersion(definition);

            for (JsonNode locale : catalogue.get("locales")) {
                createWith(adminA, Map.of(
                    "reportDefinitionId", definition.get("reportDefinitionId").asText(),
                    "reportVersion", version.get("reportVersion").asInt(),
                    "format", "CSV",
                    "locale", locale.asText()))
                    .andExpect(status().is2xxSuccessful());
            }
            assertThat(catalogue.get("locales")).hasSize(2);
        }

        @Test
        @DisplayName("an undeclared filter key is refused, never silently dropped")
        void anUndeclaredFilterKeyIsRefused() throws Exception {
            JsonNode catalogue = readCatalogue(adminA, PROJECT_A);
            JsonNode definition = firstExportableDefinition(catalogue);
            JsonNode version = firstExportableVersion(definition);

            // A key no registry declares. Silently ignoring it produces an export that looks right and is
            // wrong -- and because finance and clinical data are involved, nobody notices until the
            // numbers are quoted.
            createWith(adminA, Map.of(
                "reportDefinitionId", definition.get("reportDefinitionId").asText(),
                "reportVersion", version.get("reportVersion").asInt(),
                "format", "CSV",
                "filters", Map.of("customer.nickname", "x")))
                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("a declared filter key is accepted, so the refusal above is not blanket")
        void aDeclaredFilterKeyIsAccepted() throws Exception {
            JsonNode catalogue = readCatalogue(adminA, PROJECT_A);
            JsonNode definition = firstExportableDefinition(catalogue);
            JsonNode version = firstExportableVersion(definition);

            // The positive control for the refusal above: a key the catalogue itself publishes for this
            // subject. Without it, "an unknown key is refused" is satisfied by refusing everything.
            assertThat(filterKeysOf(catalogue, "customers"))
                .as("the catalogue must publish the filter keys, or a client cannot build a legal filter")
                .contains("customer.hasEmail");

            createWith(adminA, Map.of(
                "reportDefinitionId", definition.get("reportDefinitionId").asText(),
                "reportVersion", version.get("reportVersion").asInt(),
                "format", "CSV",
                "filters", Map.of("customer.hasEmail", true)))
                .andExpect(status().is2xxSuccessful());
        }
    }

    // ==================================================================
    // helpers
    // ==================================================================

    /**
     * Binds a principal for the duration of one MockMvc request.
     *
     * <p>A real request gets these bindings from the servlet filter chain; MockMvc with filters disabled
     * does not run that chain, so they are established here. Both are needed: the thread-bound context is
     * what {@code TenantQueryGuard} reads, and the {@code SecurityContext} is what {@code @RequirePermission}
     * method security evaluates. A refusal produced without them would be "no credential" rather than
     * "no grant", which reads as an authorization bug and is really a harness bug.
     */
    private static RequestPostProcessor asPrincipal(TenantContext context) {
        return request -> {
            var securityContext = SecurityContextHolder.createEmptyContext();
            securityContext.setAuthentication(new TenantAuthentication(context));
            SecurityContextHolder.setContext(securityContext);
            TenantContextHolder.set(context);
            return request;
        };
    }

    private ResultActions create(TenantContext caller, UUID projectId, Map<String, Object> body)
        throws Exception {
        return mvc.perform(post("/api/v1/exports")
            .param("projectId", projectId.toString())
            .with(asPrincipal(caller))
            .contentType(MediaType.APPLICATION_JSON)
            .content(MAPPER.writeValueAsString(body)));
    }

    /** The same create call with one request field varied, and everything else read from the catalogue. */
    private ResultActions createWith(TenantContext caller, Map<String, Object> body) throws Exception {
        return create(caller, PROJECT_A, body);
    }

    private MvcResult postWith(String field, String value) throws Exception {
        JsonNode catalogue = readCatalogue(adminA, PROJECT_A);
        JsonNode definition = firstExportableDefinition(catalogue);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reportDefinitionId", definition.get("reportDefinitionId").asText());
        body.put("reportVersion", firstExportableVersion(definition).get("reportVersion").asInt());
        body.put("format", "format".equals(field) ? value : "CSV");
        if ("locale".equals(field)) {
            body.put("locale", value);
        }
        return create(adminA, PROJECT_A, body).andReturn();
    }

    private JsonNode readCatalogue(TenantContext caller, UUID projectId) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/exports/catalogue")
                .param("projectId", projectId.toString())
                .with(asPrincipal(caller)))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode catalogue = MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(catalogue.get("projectId").asText())
            .as("the catalogue echoes the scope it resolved, so a client can confirm it sent what it meant")
            .isEqualTo(projectId.toString());
        return catalogue;
    }

    private String messageOf(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString()).get("message").asText();
    }

    private String codeOf(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString()).get("code").asText();
    }

    private JsonNode firstExportableDefinition(JsonNode catalogue) {
        for (JsonNode definition : catalogue.get("reportDefinitions")) {
            if (!firstExportableVersion(definition).isMissingNode()) {
                return definition;
            }
        }
        throw new AssertionError("the catalogue published no report definition this caller may export");
    }

    private JsonNode firstExportableVersion(JsonNode definition) {
        for (JsonNode version : definition.get("versions")) {
            if (version.get("exportable").asBoolean()) {
                return version;
            }
        }
        return MissingNode.getInstance();
    }

    private JsonNode firstVersion(JsonNode definition) {
        return definition.get("versions").get(0);
    }

    private JsonNode firstExportableTemplate(JsonNode catalogue) {
        for (JsonNode template : catalogue.get("documentTemplates")) {
            if (!firstExportableTemplateVersion(template).isMissingNode()) {
                return template;
            }
        }
        throw new AssertionError("the catalogue published no document template this caller may use: "
            + catalogue.get("documentTemplates"));
    }

    private JsonNode firstExportableTemplateVersion(JsonNode template) {
        for (JsonNode version : template.get("versions")) {
            if (version.get("exportable").asBoolean()) {
                return version;
            }
        }
        return MissingNode.getInstance();
    }

    private JsonNode byDefinitionId(JsonNode catalogue, UUID definitionId) {
        for (JsonNode definition : catalogue.get("reportDefinitions")) {
            if (definitionId.toString().equals(definition.get("reportDefinitionId").asText())) {
                return definition;
            }
        }
        return null;
    }

    private List<String> definitionIds(JsonNode catalogue) {
        List<String> ids = new ArrayList<>();
        for (JsonNode definition : catalogue.get("reportDefinitions")) {
            ids.add(definition.get("reportDefinitionId").asText());
        }
        return ids;
    }

    private List<String> templateIds(JsonNode catalogue) {
        List<String> ids = new ArrayList<>();
        for (JsonNode template : catalogue.get("documentTemplates")) {
            ids.add(template.get("documentTemplateId").asText());
        }
        return ids;
    }

    /** The string values of a JSON array node, for a membership assertion. */
    private static List<String> stringValues(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    /** The filter keys the catalogue publishes for one subject. */
    private List<String> filterKeysOf(JsonNode catalogue, String subjectKey) {
        List<String> keys = new ArrayList<>();
        for (JsonNode subject : catalogue.get("subjects")) {
            if (subjectKey.equals(subject.get("key").asText())) {
                subject.get("filters").forEach(filter -> keys.add(filter.asText()));
            }
        }
        return keys;
    }
}