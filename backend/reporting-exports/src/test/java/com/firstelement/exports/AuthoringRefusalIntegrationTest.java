package com.firstelement.exports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstelement.exports.catalog.AuthoringRequirements;
import com.firstelement.security.TenantAuthentication;
import com.firstelement.security.TenantContext;
import com.firstelement.security.TenantContextHolder;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Every authoring refusal the server owes a client, asserted over HTTP with the status it really returns.
 *
 * <h2>Why this suite exists</h2>
 *
 * <p>Phase 32: the Angular agent drove the real API and found six requests the contract said would succeed
 * and the server refused. One of them — an <b>empty {@code measures}</b> — was answered {@code 500},
 * logged at ERROR, for a client mistake. That is the Phase-18 class, and {@code plan.md} requires that a
 * request the server cannot answer be a refusal and never a 500.
 *
 * <p>The previous suite in this module was green throughout all of that, which is the fact this one has to
 * answer. It was green because it asserted what the code <em>described</em> rather than what the schema
 * <em>refuses</em>, and because every refusal it exercised was one somebody had already thought about.
 *
 * <h2>Every case asserts three things, not one</h2>
 *
 * <ol>
 *   <li>the <b>status</b>, which is the thing that was wrong;</li>
 *   <li>the <b>message names the field</b> and the CHECK that requires it — a refusal an author cannot act on
 *       is a support ticket, and {@code 400 BAD_REQUEST} with "something is missing" is exactly that;</li>
 *   <li>the <b>state afterwards</b> — that a refusal wrote no row, consumed no version number and published
 *       nothing. A 400 that leaves a half-written row behind is not a refusal.</li>
 * </ol>
 *
 * <h2>WHY THE CASES COME FROM {@link AuthoringRequirements}</h2>
 *
 * <p>The parameter sources read the module's own rule table rather than a list written here. A list in this
 * file would prove this file: adding a rule to the services would not add a case, and the suite would keep
 * passing over a defect it was written to catch. The rule table is what the services enforce <em>and</em>
 * what {@code AuthoringContractOpenApiCustomizer} publishes, so a rule cannot be enforced without being
 * tested and cannot be tested without being published.
 *
 * <h2>Why the document is fetched over HTTP rather than assembled</h2>
 *
 * <p>{@link #aRequestBuiltFromTheServedContractAloneSucceeds()} asks the running application for
 * {@code /api/v1/openapi} and builds its request bodies from the {@code required} array it publishes. That
 * is the shape a generated client would build, and it is the only assertion that catches the contract being
 * narrower than the server from BOTH ends at once: a document that under-declares produces a request the
 * server refuses, and a server that started requiring something new while the document did not would be
 * caught by the same request. Hence the two {@code @SpringBootTest} properties — the document is published
 * on a management port in production (ADR-0005 §8) and this suite binds it to the servlet context so it can
 * read its own API surface, exactly as {@code OpenApiDocumentExportTest} does.
 *
 * <p>Nothing here weakens the checks the sibling suite proves. Filters are off for the same reason they are
 * off there: {@code @RequirePermission} is an AOP proxy and still runs, so the authorization cases below are
 * real; the servlet chain's session requirement is identity-access's to prove.
 */
@SpringBootTest(classes = ExportsTestBase.ExportsTestApplication.class,
    properties = {
        // The document lives on the management port in a deployment (ADR-0005 §8). This suite needs to read
        // it over the servlet context, which is a test-harness decision and not a contract change.
        "springdoc.use-management-port=false",
        "springdoc.api-docs.path=/api/v1/openapi"
    })
@AutoConfigureMockMvc(addFilters = false)
class AuthoringRefusalIntegrationTest extends ExportsTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ExportsFixture fixture;

    private TenantContext adminA;

    @BeforeEach
    void seedOnlyTheReferenceRows() {
        fixture.reset();
        fixture.seedOrganizationsAndUsers();
        adminA = fixture.adminA();
    }

    // ==================================================================
    // EVERY REQUIRED FIELD, ABSENT
    // ==================================================================

    /**
     * Every rule in the table, with the field omitted from an otherwise valid request.
     *
     * <p>The headline of the phase is in here: {@code CreateReportVersionRequest.measures} with the field
     * absent was a {@code 500}, and this asserts {@code 400} for it alongside the other fifteen.
     */
    @ParameterizedTest(name = "{0}.{1} absent is a 400 naming the field")
    @MethodSource("everyRequiredField")
    void everyRequiredFieldAbsentIsARefusal(String model, String property) throws Exception {
        Scenario scenario = scenarioForModel(model);
        assertRefusal(scenario.requestWithout(property), property, scenario);
    }

    /**
     * Every rule whose value is a string or a list, with the field sent blank or empty.
     *
     * <p>"Absent" and "present but empty" are different requests and both are reachable from a client that
     * serialises an unset form field, so both are refused — and both name the field. A field typed as an
     * integer has no blank form and is covered by the absent case above.
     */
    @ParameterizedTest(name = "{0}.{1} blank is a 400 naming the field")
    @MethodSource("everyBlankableField")
    void everyBlankableFieldIsARefusal(String model, String property) throws Exception {
        Scenario scenario = scenarioForModel(model);
        assertRefusal(scenario.requestWith(property, blankValueFor(model, property)), property, scenario);
    }

    // ==================================================================
    // DEFECT 1: THE 500 THAT WAS A 400
    // ==================================================================

    /**
     * An empty {@code measures} array answers 400, and the state afterwards is unchanged.
     *
     * <p>{@code report_versions_measures_present_check} is
     * {@code CHECK (jsonb_array_length("measures") >= 1)}. Nothing refused first — {@code measures} was
     * written as {@code []}, the CHECK fired, and a {@code DataIntegrityViolationException} has no handler
     * in {@code ExportExceptionHandler}, so it reached the application floor: {@code 500 INTERNAL_ERROR},
     * logged at ERROR, paged, for a client that left one array empty.
     *
     * <p>The three state assertions are the half that matters. A refusal that left a row, or that consumed
     * version number 1, would still have answered 400 and would still be a defect.
     */
    @Test
    @DisplayName("an empty measures array answers 400, not 500, and writes nothing")
    void anEmptyMeasuresArrayIsARefusalAndWritesNothing() throws Exception {
        JsonNode definition = authorDefinition("empty-measures");
        UUID definitionId = definitionIdOf(definition);

        MvcResult result = postJson("/api/v1/exports/report-definitions/" + definitionId + "/versions",
            Map.of("columns", List.of("customer.displayName", "customer.count"),
                "dimensions", List.of("customer.displayName"),
                "measures", List.of()));

        assertThat(result.getResponse().getStatus())
            .as("an empty measures array is a malformed request, not an availability incident")
            .isEqualTo(400);
        assertThat(errorBody(result).get("code").asText()).isEqualTo("BAD_REQUEST");
        assertThat(errorBody(result).get("message").asText())
            .contains("measures")
            .contains("report_versions_measures_present_check");

        assertThat(count("report_versions", ORG_A))
            .as("no version row for a body the server refused")
            .isZero();
        assertThat(reportDefinition(definitionId).get("currentVersion").isNull())
            .as("and the definition's version pointer did not move, so no version number was consumed")
            .isTrue();

        // The control that makes the previous two assertions mean something: the very next authoring on the
        // same definition is version 1, not version 2. A refused request that had consumed a number would
        // leave a gap nobody could explain.
        JsonNode next = postJsonBody("/api/v1/exports/report-definitions/" + definitionId + "/versions",
            Map.of("columns", List.of("customer.displayName", "customer.count"),
                "dimensions", List.of("customer.displayName"),
                "measures", List.of("customer.count")));
        assertThat(next.get("reportVersion").asInt())
            .as("the first version of a definition is still 1 after a refused one")
            .isEqualTo(1);
    }

    // ==================================================================
    // DEFECT 2, continued: the other two instances the sweep found
    // ==================================================================

    /**
     * A version retired naming itself is a refusal, and the version stays published.
     *
     * <p>{@code report_versions_supersede_self_check} refuses a version whose successor is itself. Nothing
     * checked first, so this reached the database and was a {@code 500}. Found by the Phase-32 sweep rather
     * than by a caller, which is the point: the empty-{@code measures} report was one instance of a shape,
     * and the sweep is how the rest of the shape is found.
     */
    @Test
    @DisplayName("a report version retired naming itself is refused, and stays published")
    void aReportVersionCannotSupersedeItself() throws Exception {
        JsonNode definition = authorDefinition("self-supersede");
        UUID definitionId = definitionIdOf(definition);
        int version = authorVersion(definitionId, "the only version");
        publish(definitionId, version);

        MvcResult result = postJson("/api/v1/exports/report-definitions/" + definitionId + "/versions/"
            + version + "/retire", Map.of("successorVersion", version));

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(errorBody(result).get("code").asText())
            .as("its own code, not the generic catalog conflict: a client whose retirement names itself has "
                + "to choose a different successor, and a retry loop over a self-named successor never "
                + "succeeds")
            .isEqualTo("VERSION_SUPERSEDES_SELF");
        assertThat(errorBody(result).get("message").asText())
            .contains("report_versions_supersede_self_check");
        assertThat(countWhere("report_versions", ORG_A, "state", "PUBLISHED"))
            .as("and the version is untouched: still the live body")
            .isEqualTo(1);
        assertThat(countWhere("report_versions", ORG_A, "state", "RETIRED")).isZero();
    }

    /** The same rule on the template side, which has its own constraint saying the same thing. */
    @Test
    @DisplayName("a template version retired naming itself is refused, and stays published")
    void aTemplateVersionCannotSupersedeItself() throws Exception {
        UUID templateId = authorTemplate("self-supersede-docx");
        int first = storeTemplateVersion(templateId);
        int second = storeTemplateVersion(templateId);
        publishTemplate(templateId, first);

        MvcResult result = postJson("/api/v1/exports/document-templates/" + templateId + "/versions/"
            + first + "/retire", Map.of("successorVersion", first));

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(errorBody(result).get("code").asText()).isEqualTo("VERSION_SUPERSEDES_SELF");
        assertThat(errorBody(result).get("message").asText())
            .contains("document_template_versions_supersede_self_check");
        assertThat(countWhere("document_template_versions", ORG_A, "state", "PUBLISHED")).isEqualTo(1);
        assertThat(second).isEqualTo(first + 1);
    }

    /**
     * A null sort entry is a refusal, not a {@code NullPointerException}.
     *
     * <p>Not a CHECK constraint and therefore not in the sweep, but the same defect and one hop away:
     * {@code {"defaultSort": [null]}} bound a null element and {@code entry.lastIndexOf(':')} threw, so the
     * floor answered {@code 500} for a body a person could type by accident.
     */
    @Test
    @DisplayName("a null sort entry is refused by name, not answered with a NullPointerException")
    void aNullSortEntryIsARefusal() throws Exception {
        JsonNode definition = authorDefinition("null-sort-entry");
        UUID definitionId = definitionIdOf(definition);

        MvcResult result = postJson("/api/v1/exports/report-definitions/" + definitionId + "/versions",
            Map.of("columns", List.of("customer.displayName", "customer.count"),
                "dimensions", List.of("customer.displayName"),
                "measures", List.of("customer.count"),
                // A JSON null inside the array. Jackson binds it as a null element, which is exactly what
                // the old lastIndexOf call dereferenced.
                "defaultSort", java.util.Arrays.asList("customer.displayName:asc", null)));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(errorBody(result).get("message").asText()).contains("defaultSort");
        assertThat(count("report_versions", ORG_A)).isZero();
    }

    // ==================================================================
    // THE MESSAGE NEVER PRINTS THE WORD "null"
    // ==================================================================

    /**
     * An absent value is rendered {@code (absent)}, never the four characters {@code null}.
     *
     * <p>The agent reported {@code 400 … names the redaction policy 'null'} for a request that omitted the
     * field. The required-field check answers that request first now, which is the correct answer — but the
     * stringifier underneath still renders {@code null} for every <em>other</em> absent value, and a caller
     * told it sent {@code "null"} when it sent nothing will look for the wrong fix.
     */
    @Test
    @DisplayName("no refusal quotes an absent value as the word null")
    void noRefusalQuotesAnAbsentValueAsNull() throws Exception {
        JsonNode definition = authorDefinition("null-message");
        UUID definitionId = definitionIdOf(definition);
        int definitionsBefore = count("report_definitions", ORG_A);
        int versionsBefore = count("report_versions", ORG_A);

        List<MvcResult> refusals = List.of(
            // Omitted entirely.
            postJson("/api/v1/exports/report-definitions", withoutKey(definitionBody(), "redactionPolicy")),
            // Sent as an explicit JSON null, which binds the same way.
            postJson("/api/v1/exports/report-definitions",
                withNull(definitionBody(), "redactionPolicy")),
            // A null INSIDE an allow-list, where the field itself is present. Arrays.asList, not List.of:
            // List.of refuses a null element, which would make the case unreachable.
            postJson("/api/v1/exports/report-definitions/" + definitionId + "/versions",
                Map.of("columns", java.util.Arrays.asList("customer.displayName", null),
                    "dimensions", List.of("customer.displayName"),
                    "measures", List.of("customer.count"))),
            // And on the declaration side: a null timezone rather than an absent one.
            postJson("/api/v1/exports/report-definitions", withNull(definitionBody(), "timezone")));

        for (MvcResult refusal : refusals) {
            String message = errorBody(refusal).get("message").asText();
            assertThat(message)
                .as("status %s, message: %s", refusal.getResponse().getStatus(), message)
                .doesNotContain("'null'");
        }
        assertThat(count("report_definitions", ORG_A))
            .as("not one of those four requests wrote a definition")
            .isEqualTo(definitionsBefore);
        assertThat(count("report_versions", ORG_A))
            .as("or a version")
            .isEqualTo(versionsBefore);
    }

    // ==================================================================
    // THE 404 STAYS UNIFORM
    // ==================================================================

    /**
     * Authorization runs before the body is looked at, on every authoring route.
     *
     * <p>Two of these refusals used to be made in the controller — the rename's {@code expectedVersion} and
     * the retirement's {@code successorVersion} — which meant they ran <em>before</em> the service's
     * authorization guard. A caller without {@code report:manage} was answered {@code 400} on those two and
     * the non-disclosing {@code 404} everywhere else, which is a difference between routes on a surface
     * whose whole answer vocabulary is meant to be uniform. They are enforced in the services now.
     */
    @Test
    @DisplayName("a caller without report:manage is refused 404 even with a malformed body")
    void authorizationPrecedesBodyValidation() throws Exception {
        TenantContext employee = fixture.employeeA();
        UUID real = definitionIdOf(authorDefinition("uniform-404"));

        List<MockHttpServletRequestBuilder> malformed = List.of(
            post("/api/v1/exports/report-definitions").param("projectId", PROJECT_A.toString())
                .with(asPrincipal(employee)).contentType(MediaType.APPLICATION_JSON).content("{}"),
            post("/api/v1/exports/document-templates").param("projectId", PROJECT_A.toString())
                .with(asPrincipal(employee)).contentType(MediaType.APPLICATION_JSON).content("{}"),
            patch("/api/v1/exports/report-definitions/" + real).with(asPrincipal(employee))
                .contentType(MediaType.APPLICATION_JSON).content("{}"),
            post("/api/v1/exports/report-definitions/" + real + "/versions")
                .with(asPrincipal(employee)).contentType(MediaType.APPLICATION_JSON).content("{}"),
            post("/api/v1/exports/report-definitions/" + real + "/versions/1/retire")
                .with(asPrincipal(employee)).contentType(MediaType.APPLICATION_JSON).content("{}"),
            post("/api/v1/exports/document-templates/" + real + "/versions/1/retire")
                .with(asPrincipal(employee)).contentType(MediaType.APPLICATION_JSON).content("{}"));

        for (MockHttpServletRequestBuilder request : malformed) {
            MvcResult result = mvc.perform(request).andReturn();
            assertThat(result.getResponse().getStatus())
                .as("a refusal that arrives before the 404 tells a caller without report:manage that this "
                    + "endpoint validates its body, which is a difference between routes")
                .isEqualTo(404);
        }
        assertThat(count("report_definitions", ORG_A))
            .as("and the one real definition is all that exists: no refusal wrote anything")
            .isEqualTo(1);
        assertThat(count("report_versions", ORG_A)).isZero();
    }

    /**
     * A foreign target is refused 404 even with a body that is missing everything.
     *
     * <p>The other half of the same property, and the one that matters for disclosure: the target is
     * resolved <em>before</em> the body is validated, so a caller cannot use the difference between a
     * {@code 400} and a {@code 404} to learn whether a row exists in another tenant.
     */
    @Test
    @DisplayName("a body that names nothing is refused 404 for a foreign target, not 400")
    void bodyValidationDoesNotDiscloseAnotherTenantsRows() throws Exception {
        UUID foreign = definitionIdOf(authorDefinition(fixture.adminB(), "tenant-b-private"));

        assertThat(mvc.perform(post("/api/v1/exports/report-definitions/" + foreign + "/versions")
            .with(asPrincipal(adminA)).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andReturn().getResponse().getStatus())
            .as("an empty body is a 400 for a row that is yours and a 404 for one that is not -- which is "
                + "the point: the answer does not depend on whether the row exists elsewhere")
            .isEqualTo(404);

        // The control: the identical body against this tenant's own definition is a 400.
        UUID mine = definitionIdOf(authorDefinition("mine"));
        assertThat(mvc.perform(post("/api/v1/exports/report-definitions/" + mine + "/versions")
            .with(asPrincipal(adminA)).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andReturn().getResponse().getStatus()).isEqualTo(400);
    }

    // ==================================================================
    // A GENERATED CLIENT'S REQUEST, BUILT FROM THE DOCUMENT ALONE
    // ==================================================================

    /**
     * A request whose shape is the document's own {@code required} array succeeds first time.
     *
     * <p>This is the end-to-end version of the Phase-32 finding, and it is the only assertion that catches
     * the mismatch from both ends at once. If the document under-declares, the request it produces omits a
     * field the server refuses and this goes red at the POST. If the server started requiring something
     * new and the document did not follow, the same request goes red. Either way the fix is to make the
     * two agree — which is what {@code AuthoringRequirements} is for.
     *
     * <p>The <em>shape</em> comes from the document; the <em>values</em> are a client's choices. No
     * contract can tell a caller which IANA zone their clinic is in, and it does not try to: what it does
     * say is that the field is required and that it must be a real zone, which is what the values below
     * obey.
     */
    @Test
    @DisplayName("a request shaped only by the served contract's required array succeeds first time")
    void aRequestBuiltFromTheServedContractAloneSucceeds() throws Exception {
        // ---- a report definition --------------------------------------------------
        Map<String, Object> definitionValues = Map.of(
            "key", "contract-shaped-definition",
            "nameAr", "دليل العملاء",
            "nameEn", "Customer directory",
            "timezone", "Asia/Riyadh",
            "redactionPolicy", "none",
            "retentionClass", "EXPORT_ARTEFACTS");
        Map<String, Object> definitionRequest = requestFromContract("CreateReportDefinitionRequest",
            definitionValues);
        MvcResult definitionResult = mvc.perform(post("/api/v1/exports/report-definitions")
            .param("projectId", PROJECT_A.toString())
            .with(asPrincipal(adminA))
            .contentType(MediaType.APPLICATION_JSON)
            .content(MAPPER.writeValueAsString(definitionRequest))).andReturn();
        assertThat(definitionResult.getResponse().getStatus())
            .as("the contract declares 201 for this route, and the route returns it")
            .isEqualTo(201);
        UUID definitionId = UUID.fromString(
            MAPPER.readTree(definitionResult.getResponse().getContentAsString())
                .get("reportDefinitionId").asText());

        // ---- a version body ------------------------------------------------------
        MvcResult versionResult = mvc.perform(post("/api/v1/exports/report-definitions/" + definitionId
            + "/versions")
            .with(asPrincipal(adminA))
            .contentType(MediaType.APPLICATION_JSON)
            .content(MAPPER.writeValueAsString(requestFromContract("CreateReportVersionRequest", Map.of(
                "columns", List.of("customer.displayName", "customer.count"),
                "measures", List.of("customer.count"))))))
            .andReturn();
        assertThat(versionResult.getResponse().getStatus())
            .as("columns and measures are the two the contract used to under-declare, and one of them was a "
                + "500")
            .isEqualTo(201);

        // ---- a document template -------------------------------------------------
        MvcResult templateResult = mvc.perform(post("/api/v1/exports/document-templates")
            .param("projectId", PROJECT_A.toString())
            .with(asPrincipal(adminA))
            .contentType(MediaType.APPLICATION_JSON)
            .content(MAPPER.writeValueAsString(requestFromContract("CreateDocumentTemplateRequest", Map.of(
                "key", "contract-shaped-docx",
                "nameAr", "دليل العملاء",
                "nameEn", "Customer directory",
                "timezone", "Asia/Riyadh")))))
            .andReturn();
        assertThat(templateResult.getResponse().getStatus()).isEqualTo(201);
    }

    /**
     * The served document declares the four authoring success codes the routes actually return.
     *
     * <p>ADR 0016 rule 1 asks for the observed code and rule 5 asks for the regression protection; this is
     * both, in one place, read from the document the running application serves rather than from a model
     * of it.
     */
    @Test
    @DisplayName("the served document declares 201 for the four authoring creates and 204 for the retires")
    void theServedDocumentDeclaresTheObservedCodes() throws Exception {
        JsonNode document = openApi();
        for (com.firstelement.exports.web.AuthoringContractOpenApiCustomizer.SuccessCode route
            : com.firstelement.exports.web.AuthoringContractOpenApiCustomizer.AUTHORING_SUCCESS_CODES) {
            JsonNode operation = document.at("/paths/" + route.path().replace("/", "~1")
                + "/" + route.method().toLowerCase(java.util.Locale.ROOT));
            List<String> declared = new java.util.ArrayList<>();
            operation.get("responses").fieldNames().forEachRemaining(declared::add);
            assertThat(declared)
                .as("%s %s returns %d under the tests above", route.method(), route.path(), route.status())
                .containsExactly(String.valueOf(route.status()));
        }
    }

    // ==================================================================
    // helpers: the three request shapes
    // ==================================================================

    /** The parameterised source: every rule, straight out of the module's own table. */
    static Stream<Arguments> everyRequiredField() {
        return AuthoringRequirements.requests().stream()
            .flatMap(request -> request.required().stream()
                .map(rule -> Arguments.of(request.model(), rule.property())));
    }

    /** Every rule whose value can be blank or empty, which is every rule whose property is a String or a List. */
    static Stream<Arguments> everyBlankableField() {
        return everyRequiredField()
            .filter(arguments -> isBlankable((String) arguments.get()[0], (String) arguments.get()[1]));
    }

    private static boolean isBlankable(String model, String property) {
        for (RecordComponent component : recordOf(model).getRecordComponents()) {
            if (component.getName().equals(property)) {
                return CharSequence.class.isAssignableFrom(component.getType())
                    || List.class.isAssignableFrom(component.getType());
            }
        }
        return false;
    }

    private static Class<?> recordOf(String model) {
        for (Class<?> nested : com.firstelement.exports.web.ExportController.class.getDeclaredClasses()) {
            if (nested.isRecord() && nested.getSimpleName().equals(model)) {
                return nested;
            }
        }
        throw new AssertionError("no request record is named " + model
            + "; AuthoringRequirements names a model that does not exist");
    }

    /** The value that stands for "sent, but empty", per the property's declared type. */
    private static Object blankValueFor(String model, String property) {
        for (RecordComponent component : recordOf(model).getRecordComponents()) {
            if (component.getName().equals(property)) {
                return List.class.isAssignableFrom(component.getType()) ? List.of() : "   ";
            }
        }
        throw new AssertionError("no request record has a property named " + property);
    }

    /**
     * One request per model, aimed at real rows where the model needs a target.
     *
     * <p>The rename, version and retirement cases author their target through the real services first, so
     * the refusal under test is the only failure on the route and "nothing was written" is unambiguous.
     */
    private final class Scenario {

        private final String model;
        private final UUID definitionId;
        private final int publishedVersion;
        private final int rowVersion;

        private Scenario(String model, UUID definitionId, int publishedVersion, int rowVersion) {
            this.model = model;
            this.definitionId = definitionId;
            this.publishedVersion = publishedVersion;
            this.rowVersion = rowVersion;
        }

        int countsBefore() {
            return count("report_definitions", ORG_A) + count("report_versions", ORG_A)
                + count("document_templates", ORG_A) + count("document_template_versions", ORG_A);
        }

        MockHttpServletRequestBuilder requestWithout(String property) throws Exception {
            return requestWithBody(bodyWithout(property));
        }

        MockHttpServletRequestBuilder requestWith(String property, Object value) throws Exception {
            return requestWithBody(bodyWith(property, value));
        }

        private MockHttpServletRequestBuilder requestWithBody(Map<String, Object> body) throws Exception {
            return switch (model) {
                case "CreateReportDefinitionRequest" -> jsonPost(
                    "/api/v1/exports/report-definitions", body);
                case "CreateDocumentTemplateRequest" -> jsonPost(
                    "/api/v1/exports/document-templates", body);
                case "CreateReportVersionRequest" -> jsonPost(
                    "/api/v1/exports/report-definitions/" + definitionId + "/versions", body);
                case "RenameReportDefinitionRequest" -> jsonPatch(
                    "/api/v1/exports/report-definitions/" + definitionId, body);
                case "RetireVersionRequest" -> jsonPost(
                    "/api/v1/exports/report-definitions/" + definitionId + "/versions/"
                        + publishedVersion + "/retire", body);
                default -> throw new AssertionError("unhandled model " + model);
            };
        }

        private Map<String, Object> bodyWithout(String property) throws Exception {
            Map<String, Object> body = switch (model) {
                case "CreateReportDefinitionRequest" -> definitionBody();
                case "CreateDocumentTemplateRequest" -> templateBody();
                case "CreateReportVersionRequest" -> versionBody();
                case "RenameReportDefinitionRequest" -> renameBody(rowVersion);
                case "RetireVersionRequest" -> new LinkedHashMap<>();
                default -> throw new AssertionError("unhandled model " + model);
            };
            body.remove(property);
            return body;
        }

        private Map<String, Object> bodyWith(String property, Object value) throws Exception {
            Map<String, Object> body = bodyWithout(property);
            body.put(property, value);
            return body;
        }
    }

    /**
     * A real target for the models that need one.
     *
     * <p>Authored through the real services before the refusal under test, so the only thing that can fail
     * the route is the body and "nothing was written" is unambiguous.
     */
    private Scenario scenarioForModel(String model) throws Exception {
        switch (model) {
            case "CreateReportDefinitionRequest", "CreateDocumentTemplateRequest":
                return new Scenario(model, null, 0, 0);
            case "RenameReportDefinitionRequest": {
                JsonNode definition = authorDefinition("rename-target");
                return new Scenario(model, definitionIdOf(definition), 0,
                    definition.get("rowVersion").asInt());
            }
            case "CreateReportVersionRequest": {
                JsonNode definition = authorDefinition("version-target");
                return new Scenario(model, definitionIdOf(definition), 0, 0);
            }
            case "RetireVersionRequest": {
                JsonNode definition = authorDefinition("retire-target");
                UUID id = definitionIdOf(definition);
                int version = authorVersion(id, "the body to retire");
                publish(id, version);
                return new Scenario(model, id, version, 0);
            }
            default:
                throw new AssertionError("no scenario is written for " + model
                    + "; a rule the table declares and no test can exercise is a rule nothing proves");
        }
    }

    /** The three assertions every refusal owes the caller. */
    private void assertRefusal(MockHttpServletRequestBuilder request, String property, Scenario scenario)
        throws Exception {
        int before = scenario.countsBefore();
        MvcResult result = mvc.perform(request).andReturn();
        String message = result.getResponse().getStatus() < 400
            ? result.getResponse().getContentAsString()
            : errorBody(result).get("message").asText();

        assertThat(result.getResponse().getStatus())
            .as("%s absent or blank must be a 400 refusal. Message was: %s", property, message)
            .isEqualTo(400);
        assertThat(errorBody(result).get("code").asText())
            .as("a refusal the caller can act on carries the module's own 400 code")
            .isEqualTo("BAD_REQUEST");
        assertThat(message)
            .as("the refusal names the field, so the author knows what to add")
            .contains(property);
        assertThat(message)
            .as("...and names the constraint that requires it, so an operator can find the rule")
            .contains(AuthoringRequirements.ruleOf(scenario.model, property).constraint());
        assertThat(message)
            .as("...and never quotes an absent value as the word null")
            .doesNotContain("'null'");
        assertThat(scenario.countsBefore())
            .as("a refusal wrote no row: report_definitions, report_versions, document_templates and "
                + "document_template_versions are all unchanged")
            .isEqualTo(before);
    }

    // ------------------------------------------------------------------
    // bodies
    // ------------------------------------------------------------------

    private static Map<String, Object> definitionBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", "probe-" + UUID.randomUUID());
        body.put("nameAr", "دليل العملاء");
        body.put("nameEn", "Customer directory");
        body.put("descriptionAr", "تقرير العملاء");
        body.put("descriptionEn", "Customer report");
        body.put("timezone", "Asia/Riyadh");
        body.put("localeDefault", "ar");
        body.put("requiredPermissions", List.of("customer:read"));
        body.put("redactionPolicy", "none");
        body.put("retentionClass", "EXPORT_ARTEFACTS");
        return body;
    }

    private static Map<String, Object> templateBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", "probe-" + UUID.randomUUID());
        body.put("nameAr", "دليل العملاء");
        body.put("nameEn", "Customer directory");
        body.put("locale", "ar");
        body.put("timezone", "Asia/Riyadh");
        return body;
    }

    private static Map<String, Object> versionBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("columns", List.of("customer.displayName", "customer.count"));
        body.put("dimensions", List.of("customer.displayName"));
        body.put("measures", List.of("customer.count"));
        body.put("changeNote", "authored over http");
        return body;
    }

    private static Map<String, Object> renameBody(int rowVersion) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nameAr", "ع");
        body.put("nameEn", "E");
        // The version the row actually carries, read out of the authoring response. A rename guarded on
        // anything else would be refused for a different reason and this suite would prove nothing about
        // the fields it does mean to test.
        body.put("expectedVersion", Integer.valueOf(rowVersion));
        return body;
    }

    private static Map<String, Object> withoutKey(Map<String, Object> body, String key) {
        Map<String, Object> copy = new LinkedHashMap<>(body);
        copy.remove(key);
        return copy;
    }

    private static Map<String, Object> withNull(Map<String, Object> body, String key) {
        Map<String, Object> copy = new LinkedHashMap<>(body);
        copy.put(key, null);
        return copy;
    }

    /**
     * A request body containing exactly the properties the served document declares required.
     *
     * <p>The assertion that the supplied values and the declared {@code required} set are the same set is
     * the load-bearing part. A document that required fewer would make this method silently send a request
     * the server refuses; a document that required more would make it silently omit one. Both are defects
     * in the same place, and neither is visible without this equality.
     */
    private Map<String, Object> requestFromContract(String model, Map<String, Object> values)
        throws Exception {
        JsonNode schema = openApi().at("/components/schemas/" + model);
        JsonNode required = schema.get("required");
        assertThat(required)
            .as("%s declares no 'required' array in the served document, so a client is taught to omit "
                + "whatever the server refuses", model)
            .isNotNull();
        List<String> declared = new java.util.ArrayList<>();
        required.forEach(node -> declared.add(node.asText()));
        assertThat(declared)
            .as("%s: the document's required set must be exactly the set this test can supply, or the "
                + "request below is not the one the contract describes", model)
            .containsExactlyInAnyOrderElementsOf(values.keySet());

        Map<String, Object> body = new LinkedHashMap<>();
        for (String property : declared) {
            body.put(property, values.get(property));
        }
        return body;
    }

    // ------------------------------------------------------------------
    // transport
    // ------------------------------------------------------------------

    private JsonNode openApi() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/openapi")).andReturn();
        assertThat(result.getResponse().getStatus())
            .as("the application serves its own contract; without it the contract-driven cases below would "
                + "be asserting against a model of the document rather than the document")
            .isEqualTo(200);
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode errorBody(MvcResult result) throws Exception {
        String text = result.getResponse().getContentAsString();
        assertThat(text).as("a refusal must carry a body an author can read").isNotBlank();
        JsonNode body = MAPPER.readTree(text);
        assertThat(body.get("message"))
            .as("and the body must carry a message: %s", text)
            .isNotNull();
        return body;
    }

    private MockHttpServletRequestBuilder jsonPost(String path, Map<String, Object> body)
        throws Exception {
        MockHttpServletRequestBuilder request = post(path).with(asPrincipal(adminA))
            .contentType(MediaType.APPLICATION_JSON)
            .content(MAPPER.writeValueAsString(body));
        if (path.endsWith("/report-definitions") || path.endsWith("/document-templates")) {
            request.param("projectId", PROJECT_A.toString());
        }
        return request;
    }

    private MockHttpServletRequestBuilder jsonPatch(String path, Map<String, Object> body)
        throws Exception {
        return patch(path).with(asPrincipal(adminA))
            .contentType(MediaType.APPLICATION_JSON)
            .content(MAPPER.writeValueAsString(body));
    }

    private MvcResult postJson(String path, Map<String, Object> body) throws Exception {
        return mvc.perform(jsonPost(path, body)).andReturn();
    }

    private JsonNode postJsonBody(String path, Map<String, Object> body) throws Exception {
        MvcResult result = postJson(path, body);
        assertThat(result.getResponse().getStatus())
            .as("body: %s", result.getResponse().getContentAsString())
            .isEqualTo(201);
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    // ------------------------------------------------------------------
    // authoring helpers
    // ------------------------------------------------------------------

    private JsonNode authorDefinition(String key) throws Exception {
        return authorDefinition(adminA, key);
    }

    private JsonNode authorDefinition(TenantContext caller, String key) throws Exception {
        Map<String, Object> body = definitionBody();
        body.put("key", key);
        MvcResult result = mvc.perform(post("/api/v1/exports/report-definitions")
            .param("projectId", ORG_B.equals(caller.organizationId()) ? PROJECT_B.toString()
                : PROJECT_A.toString())
            .with(asPrincipal(caller))
            .contentType(MediaType.APPLICATION_JSON)
            .content(MAPPER.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private int authorVersion(UUID definitionId, String changeNote) throws Exception {
        Map<String, Object> body = versionBody();
        body.put("changeNote", changeNote);
        return postJsonBody("/api/v1/exports/report-definitions/" + definitionId + "/versions", body)
            .get("reportVersion").asInt();
    }

    private void publish(UUID definitionId, int versionNumber) throws Exception {
        mvc.perform(post("/api/v1/exports/report-definitions/" + definitionId + "/versions/"
                + versionNumber + "/publish").with(asPrincipal(adminA)))
            .andExpect(status().isOk());
    }

    private UUID authorTemplate(String key) throws Exception {
        Map<String, Object> body = templateBody();
        body.put("key", key);
        MvcResult result = mvc.perform(post("/api/v1/exports/document-templates")
            .param("projectId", PROJECT_A.toString())
            .with(asPrincipal(adminA))
            .contentType(MediaType.APPLICATION_JSON)
            .content(MAPPER.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn();
        return UUID.fromString(MAPPER.readTree(result.getResponse().getContentAsString())
            .get("documentTemplateId").asText());
    }

    private int storeTemplateVersion(UUID templateId) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/exports/document-templates/" + templateId
                + "/versions")
            .param("locale", "ar")
            .with(asPrincipal(adminA))
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .content(minimalDocx("{{report.customer.displayName}}")))
            .andExpect(status().isCreated())
            .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString())
            .get("documentTemplateVersion").asInt();
    }

    private void publishTemplate(UUID templateId, int versionNumber) throws Exception {
        mvc.perform(post("/api/v1/exports/document-templates/" + templateId + "/versions/"
                + versionNumber + "/publish").with(asPrincipal(adminA)))
            .andExpect(status().isOk());
    }

    private JsonNode reportDefinition(UUID definitionId) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/exports/report-definitions/" + definitionId)
            .with(asPrincipal(adminA))).andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private static UUID definitionIdOf(JsonNode definition) {
        return UUID.fromString(definition.get("reportDefinitionId").asText());
    }

    /** The smallest DOCX this module will accept: a real OOXML package naming one declared field. */
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

    private static RequestPostProcessor asPrincipal(TenantContext context) {
        return request -> {
            SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
            SecurityContextHolder.getContext()
                .setAuthentication(new TenantAuthentication(context));
            TenantContextHolder.set(context);
            return request;
        };
    }

    // ------------------------------------------------------------------
    // storage reads
    // ------------------------------------------------------------------

    private int count(String table, UUID organizationId) {
        return fixture.count("select 1 from " + table
            + " where \"organizationId\" = :__fe_tenant_organization_id",
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
}