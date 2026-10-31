package com.firstelement.exports.web;

import com.firstelement.exports.catalog.AuthoringRequirements;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

/**
 * Publishes the authoring surface's request schema and its success codes from what the code actually
 * enforces.
 *
 * <h2>Why this exists — the six fields</h2>
 *
 * <p>Phase 32: the Angular agent generated a client from the served document, sent what the document said
 * to send, and six requests could not succeed.
 *
 * <pre>
 *   retentionClass    required   the document said optional; the service refused it
 *   redactionPolicy   required   the document said optional; the service refused it, and the refusal
 *                                said the policy was 'null'
 *   timezone          required   the document said optional; the service refused it
 *   columns           non-empty  the document said any array; an empty one was refused as an
 *                                undeclared COLUMN, which is a different problem with a different fix
 *   measures          non-empty  the document said any array; an empty one was a 500
 *   successorVersion  required   not in the document at all
 * </pre>
 *
 * <p>The class of defect is the catalogue's, one layer up: <b>a value the server publishes must not be
 * narrower than what it actually does</b>. There the published value was an {@code enum}; here it is the
 * {@code required} array of a request schema. Both are read by a generated client, and a client generated
 * from a document that under-states the server's requirements cannot be built to succeed first time —
 * it can only be built to fail informatively, which is the same thing said more slowly.
 *
 * <h2>DERIVED, NOT RESTATED</h2>
 *
 * <p>The required properties come from {@link AuthoringRequirements#properties()} — the same list the
 * services refuse against, one entry per {@code CHECK} constraint on
 * {@code report_definitions}/{@code report_versions}/{@code document_templates}. A rule added to a service
 * without being added there is caught by {@code AuthoringContractTest}; a rule added there without a check
 * is caught by the same suite over HTTP. There is no third list.
 *
 * <p>For the same reason {@code required} alone is not enough. OpenAPI's {@code required} says the property
 * must be <em>present</em>; it says nothing about an array being non-empty, and {@code []} satisfies it.
 * {@code report_versions_measures_present_check} is
 * {@code CHECK (jsonb_array_length("measures") >= 1)} — the database refuses the empty array, so a document
 * that says {@code required} and nothing else is still teaching a client to send the one request that
 * cannot succeed. Every rule therefore also stamps the bound its shape implies: {@code minItems: 1} for a
 * collection, {@code minLength: 1} for a string.
 *
 * <h2>The success codes are observed, not chosen</h2>
 *
 * <p>ADR 0016 rule 1: "Observe, then declare. Record what the route <em>actually</em> returns under test,
 * and declare that." {@link #AUTHORING_SUCCESS_CODES} is that record, and
 * {@code AuthoringContractTest} drives every one of the thirteen routes and compares. Rule 3 forbids
 * declaring a code a route never returns, so each entry carries exactly one.
 *
 * <p>Four routes return {@code 201 Created} — the four that author a row and publish its id in a
 * {@code Location}. Two return {@code 204 No Content}, because a retired version's identity is the
 * definition and the number, both of which the caller supplied. The rest return {@code 200}. Before this,
 * every one of them was published as {@code 200}: the framework's default, which is a guess, which is what
 * rule 1 forbids.
 *
 * <h2>WHY A REPLACED PROPERTY RATHER THAN A MUTATED ONE — the trap, restated because it applies twice more</h2>
 *
 * <p>The same shared-instance hazard {@link ExportVocabularyOpenApiCustomizer} exists to avoid applies here
 * with more force, because this class writes to <em>property</em> schemas rather than only to models.
 * springdoc hands out shared objects: the {@code Schema} behind one property of one model is frequently the
 * instance behind other properties and other models. {@code properties.get("columns").setMinItems(1)} would
 * stamp "at least one" onto every array that happens to be the same object, across the whole contract.
 *
 * <p>So every property this class touches is replaced by a <b>fresh</b> object carrying the value it was
 * given plus the bound this module adds. The model schema is replaced for the same reason. Nothing
 * reachable from anywhere else is written to.
 *
 * <h2>Why it throws rather than doing nothing</h2>
 *
 * <p>Identical to the vocabulary customiser and for the same reason: a silently-skipped customiser is a
 * required array that vanished on a controller rename, and the document is rebuilt on every request to
 * {@code /api/v1/openapi}, which is promotion-blocking. A signature change that removes a property or a
 * route breaks this class, which breaks the export, which is the outcome you want.
 */
@Component
public class AuthoringContractOpenApiCustomizer implements OpenApiCustomizer {

    /**
     * One authoring route and the status it actually returns.
     *
     * <p>{@code public} and named because the contract suite builds its expectations from this table rather
     * than restating it: a test that carries its own copy of the routes proves the copy.
     *
     * @param method    the HTTP method, upper case, as the OpenAPI {@code paths} map keys it
     * @param path      the templated path, in the form the {@code @PathVariable} names produce
     * @param status    the observed success status, asserted over HTTP by {@code AuthoringContractTest}
     */
    public record SuccessCode(String method, String path, int status) {
    }

    /**
     * Every route on the authoring surface, with the code it returns.
     *
     * <p>Thirteen routes: nine that act and four that read. All thirteen are listed, including the ones
     * whose declared code is already correct — a table that only recorded the exceptions would let a
     * rename quietly drop a route from it, and the route that disappears from the table is exactly the one
     * nobody re-checks.
     */
    public static final List<SuccessCode> AUTHORING_SUCCESS_CODES = List.of(
        new SuccessCode("POST", "/api/v1/exports/report-definitions", 201),
        new SuccessCode("PATCH", "/api/v1/exports/report-definitions/{definitionId}", 200),
        new SuccessCode("GET", "/api/v1/exports/report-definitions/{definitionId}", 200),
        new SuccessCode("GET", "/api/v1/exports/report-definitions/{definitionId}/versions", 200),
        new SuccessCode("POST", "/api/v1/exports/report-definitions/{definitionId}/versions", 201),
        new SuccessCode("POST",
            "/api/v1/exports/report-definitions/{definitionId}/versions/{versionNumber}/publish", 200),
        new SuccessCode("POST",
            "/api/v1/exports/report-definitions/{definitionId}/versions/{versionNumber}/retire", 204),
        new SuccessCode("POST", "/api/v1/exports/document-templates", 201),
        new SuccessCode("GET", "/api/v1/exports/document-templates/{templateId}/versions", 200),
        new SuccessCode("POST", "/api/v1/exports/document-templates/{templateId}/versions", 201),
        new SuccessCode("POST",
            "/api/v1/exports/document-templates/{templateId}/versions/{versionNumber}/publish", 200),
        new SuccessCode("POST",
            "/api/v1/exports/document-templates/{templateId}/versions/{versionNumber}/retire", 204),
        new SuccessCode("GET", "/api/v1/exports/authoring", 200));

    /** The shared prefix, so a table entry reads as a route rather than as a fragment of one. */
    private static final String AUTHORING_PATHS = "/api/v1/exports";

    @Override
    public void customise(OpenAPI openApi) {
        stampRequiredProperties(openApi);
        stampSuccessCodes(openApi);
    }

    // ------------------------------------------------------------------
    // the request schema
    // ------------------------------------------------------------------

    /**
     * Marks every required property required, and adds the bound its shape implies.
     *
     * <p>One pass per model. The properties map is <b>copied</b> and every property this class writes is
     * <b>replaced</b>; the originals are left untouched for any other holder of a reference, which is the
     * trap documented on the class.
     */
    private void stampRequiredProperties(OpenAPI openApi) {
        Map<String, Schema> schemas = requireSchemas(openApi);
        for (AuthoringRequirements.Request request : AuthoringRequirements.requests()) {
            Schema<?> model = schemas.get(request.model());
            if (model == null) {
                throw new IllegalStateException(
                    "AuthoringContractOpenApiCustomizer found no " + request.model() + " schema in the "
                        + "document being served. Its required fields are therefore not published, so a "
                        + "generated client is taught to omit a field the server refuses -- which is the "
                        + "defect this customiser exists to close. A rename of the request record breaks "
                        + "the document export, which is promotion-blocking, and that is deliberate.");
            }
            Map<String, Schema> properties = model.getProperties();
            if (properties == null) {
                throw new IllegalStateException(
                    "AuthoringContractOpenApiCustomizer found no properties on " + request.model()
                        + " in the document being served, so none of its required fields can be published.");
            }

            // A copy, not the original map: every entry below replaces a shared Schema instance.
            Map<String, Schema> stamped = new LinkedHashMap<>(properties);
            for (AuthoringRequirements.Required rule : request.required()) {
                Schema existing = stamped.get(rule.property());
                if (existing == null) {
                    throw new IllegalStateException(
                        "AuthoringContractOpenApiCustomizer found no '" + rule.property() + "' property on "
                            + request.model() + " in the document being served. A requirement with no "
                            + "property to mark is a requirement the document cannot express, so failing "
                            + "the export is the honest answer rather than skipping it.");
                }
                stamped.put(rule.property(), bound(existing, rule, request));
            }

            // And a replaced MODEL schema, for the same reason: `required` is one more write into an object
            // springdoc may hand to anyone else.
            Schema<?> replacement = new Schema<>().type(model.getType());
            replacement.setName(model.getName());
            replacement.setDescription(model.getDescription());
            replacement.setProperties(stamped);
            replacement.setRequired(mergedRequired(model, request));
            schemas.put(request.model(), replacement);
        }
    }

    /**
     * The required list: this table's, unioned with whatever springdoc already inferred.
     *
     * <p>Union rather than replace, because springdoc derives {@code required} from bean validation and a
     * field it found required is required whatever this table thinks. Overwriting it would silently drop a
     * constraint the document was already stating.
     */
    private static List<String> mergedRequired(Schema<?> model, AuthoringRequirements.Request request) {
        Set<String> merged = new LinkedHashSet<>();
        if (model.getRequired() != null) {
            merged.addAll(model.getRequired());
        }
        merged.addAll(request.properties());
        return List.copyOf(merged);
    }

    /**
     * A fresh schema for one required property: the same type and description it already had, plus the
     * bound and the vocabulary this rule implies.
     *
     * <p>The bound is applied only where it means something. {@code minLength} on a non-string is a
     * keyword no validator reads; {@code minItems} on a non-array likewise. The rule's shape decides which
     * one is attempted and the property's own type decides whether it is meaningful, so a rule that
     * somehow reached a differently-typed property produces a correct document rather than a broken one.
     */
    private static Schema<?> bound(Schema<?> existing, AuthoringRequirements.Required rule,
                                   AuthoringRequirements.Request request) {
        String description = describe(existing, rule, request);
        Schema<?> fresh;
        if (existing.getItems() != null || "array".equals(existing.getType())) {
            ArraySchema array = new ArraySchema();
            array.setType("array");
            // The ITEM schema is carried over by reference, not copied and not written to: it is not this
            // class's to change, and copying it deeply would detach it from whatever springdoc shares.
            array.setItems(existing.getItems());
            if (AuthoringRequirements.Shape.NON_EMPTY_LIST == rule.shape()) {
                array.setMinItems(1);
            }
            fresh = array;
        } else if ("string".equals(existing.getType())) {
            StringSchema text = new StringSchema();
            text.setType("string");
            text.setMinLength(1);
            fresh = text;
        } else {
            fresh = new Schema<>().type(existing.getType());
            fresh.setFormat(existing.getFormat());
        }
        fresh.setDescription(description);
        fresh.setExample(existing.getExample());
        fresh.setDefault(existing.getDefault());
        fresh.setNullable(existing.getNullable());
        if (!rule.vocabulary().isEmpty()) {
            // The closed set is stamped ONLY where the server refuses against that same list, and the list
            // travels on the rule so it cannot drift from `policyOf`. Without this the document types
            // redactionPolicy as an open string while the service refuses everything outside four names.
            stampEnum(fresh, rule.vocabulary());
        }
        return fresh;
    }

    /**
     * Writes a closed vocabulary onto a schema, through a raw view.
     *
     * <p>{@code Schema<T>.setEnum(List<T>)} takes the schema's own type parameter, so on a
     * {@code Schema<?>} the compiler captures to a wildcard it cannot satisfy from a {@code List<String>}.
     * The cast is confined to this one call and the values are {@code String} by construction — the
     * caller passes {@code rule.vocabulary()}, which is a {@code List<String>} built from
     * {@code ExportRedactionPolicy.declared()}. {@code ExportVocabularyContractTest} asserts the contents
     * round-trip through the accepting code rather than trusting the cast.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void stampEnum(Schema<?> schema, List<String> vocabulary) {
        ((Schema) schema).setEnum(new ArrayList<Object>(vocabulary));
    }

    /**
     * The property's description, with the rule appended so the contract says <em>why</em>.
     *
     * <p>Kept rather than replaced: springdoc carries the record's javadoc here, and the javadoc explains
     * the business meaning. Appending rather than overwriting is what lets the document carry both.
     */
    private static String describe(Schema<?> existing, AuthoringRequirements.Required rule,
                                   AuthoringRequirements.Request request) {
        String requirement = "Required. " + rule.constraint() + ": " + rule.because() + ".";
        if (!rule.vocabulary().isEmpty()) {
            requirement += " One of " + String.join(", ", rule.vocabulary()) + ".";
        }
        String carried = existing.getDescription();
        return carried == null || carried.isBlank() ? requirement : carried + " " + requirement;
    }

    // ------------------------------------------------------------------
    // the success codes
    // ------------------------------------------------------------------

    /**
     * Declares each authoring route's observed success code, and nothing else.
     *
     * <p>Re-keys the response springdoc already computed rather than rebuilding it, so the content schema
     * springdoc inferred for the return type survives. The {@link ApiResponse} <em>object</em> is shared
     * with any other operation declaring the same return type, so only the map entry is touched — the
     * object's own fields are never written to.
     */
    private void stampSuccessCodes(OpenAPI openApi) {
        for (SuccessCode declared : AUTHORING_SUCCESS_CODES) {
            Operation operation = requireOperation(openApi, declared);
            ApiResponses responses = operation.getResponses();
            if (responses == null || responses.isEmpty()) {
                throw new IllegalStateException(
                    "AuthoringContractOpenApiCustomizer found no responses declared on " + declared.method()
                        + " " + declared.path() + ". The contract would publish no success code at all for "
                        + "a route that returns " + declared.status() + ", which is the ADR-0016 defect in "
                        + "its most literal form.");
            }
            List<String> declared2xx = new ArrayList<>();
            for (String code : responses.keySet()) {
                if (code != null && code.length() == 3 && code.charAt(0) == '2') {
                    declared2xx.add(code);
                }
            }
            String observed = String.valueOf(declared.status());
            if (declared2xx.equals(List.of(observed))) {
                continue;
            }
            if (declared2xx.size() != 1) {
                throw new IllegalStateException(
                    "AuthoringContractOpenApiCustomizer found " + declared2xx.size() + " success codes ("
                        + declared2xx + ") declared on " + declared.method() + " " + declared.path()
                        + ", which returns " + declared.status() + ". ADR-0016 rule 3 forbids declaring a "
                        + "code a route never returns, so this fails the document export rather than "
                        + "guessing which one is real.");
            }
            ApiResponse existing = responses.get(declared2xx.get(0));
            responses.remove(declared2xx.get(0));
            responses.addApiResponse(observed, existing);
        }
    }

    private static Operation requireOperation(OpenAPI openApi, SuccessCode declared) {
        PathItem path = openApi == null ? null : openApi.getPaths() == null
            ? null : openApi.getPaths().get(declared.path());
        if (path == null) {
            throw new IllegalStateException(
                "AuthoringContractOpenApiCustomizer found no path " + declared.path() + " in the document "
                    + "being served, so its " + declared.method() + " cannot declare the " + declared.status()
                    + " it returns. A renamed or removed authoring route breaks the export rather than "
                    + "silently leaving a client to guess the code.");
        }
        PathItem.HttpMethod method = methodOf(declared.method());
        Operation operation = path.readOperationsMap().get(method);
        if (operation == null) {
            throw new IllegalStateException(
                "AuthoringContractOpenApiCustomizer found no " + declared.method() + " on "
                    + declared.path() + " in the document being served, so the " + declared.status() + " it "
                    + "returns is not declared anywhere.");
        }
        return operation;
    }

    private static PathItem.HttpMethod methodOf(String method) {
        try {
            return PathItem.HttpMethod.valueOf(method.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                "AuthoringContractOpenApiCustomizer cannot map " + method + " onto an OpenAPI operation "
                    + "method; the success-code table uses GET, POST, PATCH, PUT and DELETE.", e);
        }
    }

    private static Map<String, Schema> requireSchemas(OpenAPI openApi) {
        if (openApi == null || openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
            throw new IllegalStateException(
                "AuthoringContractOpenApiCustomizer found no components in the document being served; the "
                    + "authoring request schemas and the fields they require are missing from the contract.");
        }
        return openApi.getComponents().getSchemas();
    }

    /** The authoring prefix, published so a test can assert every declared route is under it. */
    public static String authoringPaths() {
        return AUTHORING_PATHS;
    }
}