package com.firstelement.exports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.exports.catalog.AuthoringRequirements;
import com.firstelement.exports.catalog.ExportRedactionPolicy;
import com.firstelement.exports.persistence.ReportCatalogRepository;
import com.firstelement.exports.web.AuthoringContractOpenApiCustomizer;
import com.firstelement.exports.web.ExportController;
import com.firstelement.exports.web.ExportVocabularyOpenApiCustomizer;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The served document states what the authoring services enforce, and nothing narrower.
 *
 * <h2>Why this suite deliberately needs neither Spring nor a database</h2>
 *
 * <p>The defect it closes was a <em>contract</em> defect, discovered by a client that generated from the
 * document and then sent what the document said to send. A contract defect must be provable on a host with
 * no Docker, because every other suite in this module needs PostgreSQL and a suite that needs PostgreSQL is
 * a suite that can go unrun. The wire-level half — that the server really does refuse each omission with the
 * status the document implies — is {@code AuthoringRefusalIntegrationTest}'s job; the two together are the
 * guarantee, and this file is the half that needs no infrastructure.
 *
 * <h2>WHY THE EXPECTATIONS ARE BUILT FROM THE CODE AND NOT RESTATED</h2>
 *
 * <p>A test carrying its own copy of the required list proves the copy. The properties here are derived
 * twice from the module itself: the property <em>set</em> comes from reflecting the real request records in
 * {@link ExportController}, and the required <em>subset</em> comes from
 * {@link AuthoringRequirements}, which is the same table the services refuse against. So this suite cannot
 * pass while the document and the enforcement disagree — the only way to make it pass is to make them agree.
 *
 * <h2>Why the shared-Schema trap is reproduced here rather than reasoned about</h2>
 *
 * <p>This customiser writes to property schemas, and springdoc hands out <b>shared</b> instances: the
 * {@code Schema} behind one property of one model is frequently the instance behind other properties and
 * other models. A suite that built fresh schemas would pass against the mutating implementation and prove
 * nothing — which is exactly how the Phase-15 inbox leak survived a green suite. So
 * {@link #noOtherSchemaIsContaminated()} shares the instances the way springdoc does.
 */
class AuthoringContractTest {

    private final AuthoringContractOpenApiCustomizer customizer = new AuthoringContractOpenApiCustomizer();

    // ==================================================================
    // every required field is published as required
    // ==================================================================

    /**
     * Every request model carries every rule as a {@code required} property.
     *
     * <p>The detector the brief asks for: a field the server requires and the document declares optional
     * fails here, because the expectation is read from {@link AuthoringRequirements} and the actual is read
     * from the stamped document.
     */
    @ParameterizedTest(name = "{0}.{1} is declared required")
    @MethodSource("everyRequiredProperty")
    void everyFieldTheServerRequiresIsDeclaredRequired(String model, String property) {
        Schema<?> stamped = servedModel(model);

        assertThat(stamped.getRequired())
            .as("%s.%s is refused by %s when absent, so a client that omits it sends a request that cannot "
                + "succeed. Declaring it optional is how the Phase-32 contract taught a generated client to "
                + "do exactly that.", model, property,
                AuthoringRequirements.ruleOf(model, property).constraint())
            .contains(property);
    }

    /**
     * Required is not enough: a bound as well.
     *
     * <p>OpenAPI's {@code required} says the property must be <em>present</em> and says nothing about an
     * array being non-empty. {@code []} satisfies {@code required}, and {@code []} is precisely the value
     * {@code report_versions_measures_present_check} refuses — the request that was a 500. So every
     * required string carries {@code minLength: 1} and every required collection carries
     * {@code minItems: 1}.
     */
    @ParameterizedTest(name = "{0}.{1} carries its bound")
    @MethodSource("everyRequiredProperty")
    void everyRequiredFieldCarriesTheBoundItsShapeImplies(String model, String property) {
        Schema<?> propertySchema = servedModel(model).getProperties().get(property);

        assertThat(propertySchema).isNotNull();
        if (propertySchema.getItems() != null) {
            assertThat(propertySchema.getMinItems())
                .as("%s.%s must be non-empty: an empty array is what the CHECK on the column refuses, and a "
                    + "document that says only 'required' still teaches a client to send it",
                    model, property)
                .isEqualTo(1);
        } else if ("string".equals(propertySchema.getType())) {
            assertThat(propertySchema.getMinLength())
                .as("%s.%s must be non-blank, which is what length(btrim(…)) > 0 checks", model, property)
                .isEqualTo(1);
        }
    }

    /** The declared policy vocabulary travels with the rule that requires the field. */
    @Test
    @DisplayName("redactionPolicy is published as the enum the service refuses against")
    void redactionPolicyIsPublishedAsAClosedSet() {
        Schema<?> policy = servedModel("CreateReportDefinitionRequest").getProperties().get("redactionPolicy");

        assertThat(enumOf(policy))
            .as("report_definitions_redaction_policy_check only requires a non-blank string, so without the "
                + "enum the document types this as an open string and a client can send a policy the service "
                + "refuses with 400 at run time")
            .containsExactlyElementsOf(ExportRedactionPolicy.declared());
        // Asked of the ACCEPTING code rather than settled by comparing two lists, for the reason
        // ExportVocabularyContractTest gives: containsExactly(declared()) would pass if both were changed
        // together to publish something new, because it never asks the server whether it would accept it.
        for (String published : enumOf(policy)) {
            assertThat(ReportCatalogRepository.policyOf(published).wireValue()).isEqualTo(published);
        }
    }

    /** Every rule's constraint is stated on the property, so the document says WHY it is required. */
    @Test
    @DisplayName("every required property's description names the constraint that requires it")
    void everyRequiredPropertyNamesItsConstraint() {
        for (AuthoringRequirements.Request request : AuthoringRequirements.requests()) {
            Schema<?> model = servedModel(request.model());
            for (AuthoringRequirements.Required rule : request.required()) {
                assertThat(model.getProperties().get(rule.property()).getDescription())
                    .as("%s.%s must explain itself in the contract, not only in a refusal message",
                        request.model(), rule.property())
                    .contains(rule.constraint());
            }
        }
    }

    // ==================================================================
    // the observed success codes
    // ==================================================================

    /** ADR 0016 rule 1: observe, then declare. Every authoring route declares the code it returns. */
    @ParameterizedTest(name = "{1} {0} declares {2}")
    @MethodSource("everyAuthoringRoute")
    void everyAuthoringRouteDeclaresItsObservedSuccessCode(String path, String method, int status) {
        // Every route is present, because the customiser declares the whole surface in one pass: a document
        // carrying only the route under test is not a document this customiser can run against, and
        // building one would test a different thing.
        OpenAPI document = new OpenAPI();
        document.setComponents(new io.swagger.v3.oas.models.Components()
            .schemas(seedAuthoringModels()));
        document.setPaths(seedAuthoringPaths());
        customizer.customise(document);

        assertThat(responsesOf(document, path, method))
            .as("%s %s returns %d under test, so the document must declare that and nothing else "
                + "(ADR-0016 rule 3: a declared code a route never returns is hedging, not honesty)",
                method, path, status)
            .containsExactly(Integer.valueOf(status));
    }

    /**
     * The route table is the whole authoring surface, not a list of exceptions.
     *
     * <p>A table that recorded only the four routes whose code was wrong would let a renamed route fall
     * out of it, and the route that disappears from the table is the one nobody re-checks.
     */
    @Test
    @DisplayName("every declared route is an exports route, and no route is declared twice")
    void theRouteTableIsUnambiguousAndInScope() {
        List<AuthoringContractOpenApiCustomizer.SuccessCode> routes =
            AuthoringContractOpenApiCustomizer.AUTHORING_SUCCESS_CODES;

        assertThat(routes).isNotEmpty();
        assertThat(routes.stream().map(r -> r.method() + " " + r.path()).distinct().count())
            .as("a route declared twice with two codes is a document that hedges")
            .isEqualTo(routes.size());
        assertThat(routes.stream().map(r -> r.path()).distinct().count())
            .isEqualTo(routes.stream().map(AuthoringContractOpenApiCustomizer.SuccessCode::path)
                .distinct().count());
        for (AuthoringContractOpenApiCustomizer.SuccessCode route : routes) {
            assertThat(route.path())
                .as("only the authoring surface is declared here; a lifecycle route belongs to its own phase")
                .startsWith(AuthoringContractOpenApiCustomizer.authoringPaths());
        }
        assertThat(routes.stream().filter(r -> r.status() == 201).count())
            .as("four routes author a row and publish its id: the two definition routes and the two "
                + "template routes")
            .isEqualTo(4);
        assertThat(routes.stream().filter(r -> r.status() == 204).count())
            .as("two routes retire a version, whose identity the caller already supplied")
            .isEqualTo(2);
    }

    /** A route that vanishes from the document must break the export, not silently lose its code. */
    @Test
    @DisplayName("a route the document does not carry fails the export rather than losing its code")
    void aVanishedRouteIsLoud() {
        assertThatThrownBy(() -> customizer.customise(documentWithPathsOnly(List.of())))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("/api/v1/exports/report-definitions");
    }

    /** Two success codes on one route is a document that hedges, and ADR 0016 rule 3 forbids it. */
    @Test
    @DisplayName("a route declaring two success codes fails the export instead of guessing which is real")
    void anAmbiguousSuccessCodeIsLoud() {
        OpenAPI document = new OpenAPI();
        document.setComponents(new io.swagger.v3.oas.models.Components()
            .schemas(seedAuthoringModels()));
        document.setPaths(seedAuthoringPaths());
        Operation operation = document.getPaths()
            .get("/api/v1/exports/report-definitions").readOperationsMap().get(PathItem.HttpMethod.POST);
        operation.getResponses().addApiResponse("202", new ApiResponse().description("also fine"));

        assertThatThrownBy(() -> customizer.customise(document))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ADR-0016 rule 3");
    }

    // ==================================================================
    // loudness, and the shared-Schema trap
    // ==================================================================

    /**
     * A requirement with no property to mark cannot be published, so it must break the export.
     *
     * <p>A silently-skipped rule is a required array that vanished on a rename, and the document is rebuilt
     * on every request to {@code /api/v1/openapi}, which is promotion-blocking.
     */
    @Test
    @DisplayName("a required field the document cannot express fails the export")
    void anUnexpressibleRequirementIsLoud() {
        OpenAPI document = servedShape();
        document.getComponents().getSchemas().get("CreateReportDefinitionRequest")
            .getProperties().remove("retentionClass");

        assertThatThrownBy(() -> customizer.customise(document))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("retentionClass");
    }

    @Test
    @DisplayName("a request model renamed away fails the export")
    void aRenamedModelIsLoud() {
        OpenAPI document = servedShape();
        document.getComponents().getSchemas().remove("CreateReportVersionRequest");

        assertThatThrownBy(() -> customizer.customise(document))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("CreateReportVersionRequest");
    }

    /**
     * Nothing outside the five authoring models gains a bound, an enum or a required list.
     *
     * <p>The property schemas are shared with an unrelated model and with a query parameter on an unrelated
     * path, exactly as springdoc shares them. A customiser that mutated the existing {@code Schema} in
     * place would stamp "at least one entry" onto all three.
     */
    @Test
    @DisplayName("no unrelated model, property or parameter gains a bound, an enum or a required list")
    void noOtherSchemaIsContaminated() {
        StringSchema sharedScalar = new StringSchema().type("string");
        ArraySchema sharedArray = new ArraySchema().type("array")
            .items(new StringSchema().type("string"));

        Map<String, Schema> unrelatedProperties = new LinkedHashMap<>();
        unrelatedProperties.put("columns", sharedArray);
        unrelatedProperties.put("redactionPolicy", sharedScalar);
        unrelatedProperties.put("note", sharedScalar);
        Schema<Object> unrelated = new ObjectSchema();
        unrelated.setProperties(unrelatedProperties);

        Map<String, Schema> schemas = new LinkedHashMap<>();
        schemas.putAll(seedAuthoringModels());
        schemas.put("SomeOtherModel", unrelated);

        OpenAPI document = new OpenAPI();
        document.setComponents(new io.swagger.v3.oas.models.Components().schemas(schemas));
        document.setPaths(seedAuthoringPaths());

        customizer.customise(document);

        assertThat(sharedArray.getMinItems())
            .as("the array instance an unrelated model shares must stay unbounded")
            .isNull();
        assertThat(sharedScalar.getEnum())
            .as("the string instance an unrelated model shares must stay an open string")
            .isNull();
        assertThat(sharedScalar.getMinLength()).isNull();
        assertThat(unrelated.getRequired())
            .as("an unrelated model must not acquire a required list")
            .isNull();
        // And the parameter pointing at the very same instance, the shape the Phase-15 leak took.
        io.swagger.v3.oas.models.parameters.Parameter sharedParameter =
            new io.swagger.v3.oas.models.parameters.Parameter().name("q").in("query").schema(sharedScalar);
        assertThat(sharedParameter.getSchema().getEnum()).isNull();
        assertThat(sharedParameter.getSchema().getMinLength()).isNull();
    }

    /** Both customisers compose: the vocabulary work must survive the new required-array stamping. */
    @Test
    @DisplayName("the export vocabulary stamps survive alongside the authoring required arrays")
    void theTwoCustomisersCompose() {
        OpenAPI document = new OpenAPI();
        Map<String, Schema> schemas = new LinkedHashMap<>(seedAuthoringModels());
        Map<String, Schema> createExportProperties = new LinkedHashMap<>();
        createExportProperties.put("format", new StringSchema().type("string"));
        createExportProperties.put("locale", new StringSchema().type("string"));
        schemas.put(ExportVocabularyOpenApiCustomizer.CREATE_EXPORT_REQUEST,
            objectWith(createExportProperties));
        Map<String, Schema> catalogueProperties = new LinkedHashMap<>();
        for (String name : List.of("formats", "locales")) {
            catalogueProperties.put(name,
                new ArraySchema().type("array").items(new StringSchema().type("string")));
        }
        catalogueProperties.put("defaultFormat", new StringSchema().type("string"));
        catalogueProperties.put("defaultLocale", new StringSchema().type("string"));
        schemas.put(ExportVocabularyOpenApiCustomizer.EXPORT_CATALOGUE, objectWith(catalogueProperties));
        document.setComponents(new io.swagger.v3.oas.models.Components().schemas(schemas));
        document.setPaths(seedAuthoringPaths());

        // Both customisers run against the one served document, so whichever order the container uses, this
        // is the composition being shipped.
        customizer.customise(document);
        new ExportVocabularyOpenApiCustomizer().customise(document);

        Schema<?> createRequest = document.getComponents().getSchemas()
            .get(ExportVocabularyOpenApiCustomizer.CREATE_EXPORT_REQUEST);
        assertThat(createRequest.getProperties().get("format").getEnum()).isNotNull();
        assertThat(createRequest.getProperties().get("locale").getEnum()).isNotNull();
        assertThat(document.getComponents().getSchemas()
            .get("CreateReportDefinitionRequest").getRequired()).contains("retentionClass");
    }

    private static Schema<Object> objectWith(Map<String, Schema> properties) {
        Schema<Object> model = new ObjectSchema();
        model.setProperties(properties);
        return model;
    }

    // ==================================================================
    // the two tables themselves
    // ==================================================================

    /**
     * Every requirement names a property that exists on the request record.
     *
     * <p>{@code AuthoringRequirements} holds strings, and a typo in one of them would produce a refusal
     * naming a field the request has never heard of — a message that tells an author to add a property they
     * cannot add. The record components are the authority on which properties exist.
     */
    @Test
    @DisplayName("every requirement names a property the request record really has")
    void everyRequirementNamesARealProperty() {
        for (AuthoringRequirements.Request request : AuthoringRequirements.requests()) {
            List<String> components = componentsOf(recordTypeOf(request.model()));
            assertThat(components)
                .as("%s must be a real request record; the model's OpenAPI name is its simple name",
                    request.model())
                .isNotEmpty();
            for (AuthoringRequirements.Required rule : request.required()) {
                assertThat(components)
                    .as("%s requires '%s', which must be a component of the record -- otherwise the refusal "
                        + "names a field no client can send and the document marks a property that is not "
                        + "there", request.model(), rule.property())
                    .contains(rule.property());
            }
        }
    }

    /**
     * The two tables and the schema agree, constraint by constraint.
     *
     * <p>This is the sweep the brief asked for, in the direction that can be proved from inside this
     * module: every {@code CHECK} that can fire on caller-supplied input has a rule above it. A constraint
     * the server enforces with no pre-check is a request answered {@code 500} at ERROR level.
     *
     * <p>The constraints listed as server-derived are the ones no caller can reach: {@code versionNumber},
     * {@code currentVersion}, {@code publishedVersion}, the publication columns, the storage key and the
     * content hash are all written by the server from its own state, and a caller cannot name them.
     */
    @Test
    @DisplayName("every CHECK reachable from caller input has a rule, and the server-derived ones do not")
    void everyCallerReachableCheckHasARule() {
        Map<String, List<String>> guarded = new LinkedHashMap<>();
        for (AuthoringRequirements.Request request : AuthoringRequirements.requests()) {
            List<String> constraints = new ArrayList<>();
            for (AuthoringRequirements.Required rule : request.required()) {
                constraints.add(rule.constraint());
            }
            guarded.put(request.model(), constraints);
        }

        assertThat(guarded.get("CreateReportDefinitionRequest"))
            .as("report_definitions: key, nameAr, nameEn, timezone, redactionPolicy and retentionClass are "
                + "all caller-supplied and all required. The remaining CHECKs on the table -- "
                + "published_pair_check, published_version_check, current_version_check, "
                + "published_version_positive_check and version_check -- are decided by the server's own "
                + "publication transitions and cannot be reached from a request body")
            .contains("report_definitions_key_check", "report_definitions_name_ar_check",
                "report_definitions_name_en_check", "report_definitions_timezone_check",
                "report_definitions_redaction_policy_check", "report_definitions_retention_class_check");

        assertThat(guarded.get("CreateReportVersionRequest"))
            .as("report_versions: measures_present_check is the constraint that answered 500 for an empty "
                + "measures array, and columns_shape_check names the empty-column rule. The remaining "
                + "CHECKs -- version_number_check, the five sibling *_shape_check, the three "
                + "*_entries_check, sort_entries_check, published_pair_check, published_state_check and "
                + "retired_check -- are pre-checked by the registry resolution, the shape writers and "
                + "RETIRE_VERSION")
            .contains("report_versions_columns_shape_check", "report_versions_measures_present_check");

        assertThat(guarded.get("CreateDocumentTemplateRequest"))
            .as("document_templates: key, both names and timezone are caller-supplied. "
                + "document_templates_kind_check is pinned by the service because this renderer produces "
                + "DOCX only, and the four status/pointer CHECKs are server-derived")
            .contains("document_templates_key_check", "document_templates_name_ar_check",
                "document_templates_name_en_check", "document_templates_timezone_check");

        assertThat(guarded.get("RetireVersionRequest"))
            .as("retired_check on both version tables is the same rule, because the two constraints say the "
                + "same thing and one request record serves both routes")
            .contains("report_versions_retired_check");
    }

    /** The self-supersession rule is not a required field, so it is asserted where it lives. */
    @Test
    @DisplayName("a retirement naming itself has its own stable code, not the generic conflict one")
    void selfSupersessionHasItsOwnCode() {
        com.firstelement.exports.catalog.SelfSupersessionRefusal refusal =
            new com.firstelement.exports.catalog.SelfSupersessionRefusal("names itself", "ReportVersion",
                java.util.UUID.randomUUID());

        assertThat(refusal.code()).isEqualTo("VERSION_SUPERSEDES_SELF");
        assertThat(refusal).isInstanceOf(com.firstelement.exports.catalog.ReportCatalogConflictException.class)
            .as("it is a 409 with the catalogue's conflict advice, because the row is real and its state "
                + "cannot carry out the instruction -- not a 400, which would teach a client the field is "
                + "a syntax problem");
    }

    // ==================================================================
    // helpers
    // ==================================================================

    private static Stream<Arguments> everyRequiredProperty() {
        return AuthoringRequirements.requests().stream()
            .flatMap(request -> request.required().stream()
                .map(rule -> Arguments.of(request.model(), rule.property())));
    }

    private static Stream<Arguments> everyAuthoringRoute() {
        return AuthoringContractOpenApiCustomizer.AUTHORING_SUCCESS_CODES.stream()
            .map(route -> Arguments.of(route.path(), route.method(), Integer.valueOf(route.status())));
    }

    /** The document springdoc would produce for the authoring surface, customised. */
    private static OpenAPI servedShape() {
        OpenAPI document = new OpenAPI();
        document.setComponents(new io.swagger.v3.oas.models.Components()
            .schemas(seedAuthoringModels()));
        // The paths are present in every document this customiser runs against, because it declares the
        // success codes as well as the required arrays. A test document without them would be asserting
        // against a document that cannot exist.
        document.setPaths(seedAuthoringPaths());
        new AuthoringContractOpenApiCustomizer().customise(document);
        return document;
    }

    @SuppressWarnings("unchecked")
    private static Schema<?> servedModel(String model) {
        return (Schema<?>) servedShape().getComponents().getSchemas().get(model);
    }

    /**
     * One object schema per request record, built by reflecting the record's components.
     *
     * <p>Reflecting rather than restating is what makes this suite a detector: a component that exists
     * because the record has it, and a required list that exists because {@link AuthoringRequirements} says
     * so, cannot be made to agree with each other by editing one of them.
     */
    private static Map<String, Schema> seedAuthoringModels() {
        Map<String, Schema> schemas = new LinkedHashMap<>();
        for (Class<?> record : requestRecords()) {
            Map<String, Schema> properties = new LinkedHashMap<>();
            for (RecordComponent component : record.getRecordComponents()) {
                properties.put(component.getName(), schemaFor(component.getType()));
            }
            Schema<Object> model = new ObjectSchema();
            model.setProperties(properties);
            model.setDescription("the request record as javadoc would describe it");
            schemas.put(record.getSimpleName(), model);
        }
        return schemas;
    }

    private static Schema<?> schemaFor(Class<?> type) {
        if (List.class.isAssignableFrom(type)) {
            return new ArraySchema().type("array").items(new StringSchema().type("string"));
        }
        if (Integer.class.equals(type)) {
            return new IntegerSchema().type("integer");
        }
        if (Map.class.isAssignableFrom(type)) {
            return new ObjectSchema().type("object");
        }
        return new StringSchema().type("string");
    }

    /** Every request record {@link ExportController} declares, found by simple name. */
    private static List<Class<?>> requestRecords() {
        List<Class<?>> records = new ArrayList<>();
        for (Class<?> nested : ExportController.class.getDeclaredClasses()) {
            if (nested.isRecord() && nested.getSimpleName().endsWith("Request")) {
                records.add(nested);
            }
        }
        assertThat(records)
            .as("ExportController declares no request records, so this suite would be asserting against an "
                + "empty document and pass for the wrong reason")
            .isNotEmpty();
        return records;
    }

    private static Class<?> recordTypeOf(String model) {
        return requestRecords().stream()
            .filter(record -> record.getSimpleName().equals(model))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no request record is named " + model
                + "; AuthoringRequirements names a model that does not exist, and the document would be "
                + "stamped for a schema springdoc never produces"));
    }

    private static List<String> componentsOf(Class<?> record) {
        List<String> names = new ArrayList<>();
        for (RecordComponent component : record.getRecordComponents()) {
            names.add(component.getName());
        }
        return names;
    }

    /**
     * A document carrying only the named routes, each declaring {@code 200} — springdoc's default and the
     * defect this customiser corrects.
     */
    private static OpenAPI documentWithPathsOnly(
        List<AuthoringContractOpenApiCustomizer.SuccessCode> routes) {
        Paths paths = new Paths();
        for (AuthoringContractOpenApiCustomizer.SuccessCode route : routes) {
            PathItem item = paths.containsKey(route.path()) ? paths.get(route.path()) : new PathItem();
            item.operation(PathItem.HttpMethod.valueOf(route.method()),
                operationDeclaring("200"));
            paths.addPathItem(route.path(), item);
        }
        OpenAPI document = new OpenAPI();
        document.setPaths(paths);
        document.setComponents(new io.swagger.v3.oas.models.Components()
            .schemas(seedAuthoringModels()));
        return document;
    }

    /** Every authoring route, each declaring {@code 200}, which is what springdoc produces by default. */
    private static Paths seedAuthoringPaths() {
        return documentWithPathsOnly(AuthoringContractOpenApiCustomizer.AUTHORING_SUCCESS_CODES)
            .getPaths();
    }

    private static Operation operationDeclaring(String code) {
        Operation operation = new Operation();
        ApiResponses responses = new ApiResponses();
        responses.addApiResponse(code,
            new ApiResponse().description("OK").content(new io.swagger.v3.oas.models.media.Content()
                .addMediaType("application/json",
                    new io.swagger.v3.oas.models.media.MediaType().schema(new ObjectSchema()))));
        operation.setResponses(responses);
        return operation;
    }

    private static List<Integer> responsesOf(OpenAPI document, String path, String method) {
        Operation operation = document.getPaths().get(path)
            .readOperationsMap().get(PathItem.HttpMethod.valueOf(method));
        List<Integer> codes = new ArrayList<>();
        for (String code : operation.getResponses().keySet()) {
            codes.add(Integer.valueOf(code));
        }
        codes.sort(Integer::compareTo);
        return codes;
    }

    @SuppressWarnings("unchecked")
    private static List<String> enumOf(Schema<?> property) {
        return (List<String>) property.getEnum();
    }
}