package com.firstelement.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.crm.domain.InboxFilter;
import com.firstelement.inbox.api.InboxQuery;
import com.firstelement.inbox.web.ConversationsController;
import com.firstelement.inbox.web.InboxFilterEnumOpenApiCustomizer;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code filter} contract, with no Spring context and no database.
 *
 * <h2>Why this suite deliberately needs neither</h2>
 *
 * <p>It exists because the defect it closes was a <em>contract</em> defect — {@code filter} was a
 * bare {@code type: string} — and a contract defect must be provable on a host with no Docker.
 * Every other suite in this module needs PostgreSQL, and a suite that needs PostgreSQL is a suite
 * that can go unrun on a machine without it. This one cannot.
 *
 * <h2>What "declared as an enum" means here, precisely</h2>
 *
 * <p>Not "the document mentions the words". The served schema must carry {@code type: string}
 * <em>and</em> {@code enum: [...]} whose members are the five <b>wire</b> values, so a generated
 * client produces a closed union instead of a free string. The wire values matter: springdoc
 * renders a typed enum parameter as its Java constant names, which would have published
 * {@code UNASSIGNED} and invited {@code ?filter=UNASSIGNED}. That is a different vocabulary from
 * the one the legacy client has always sent, and it is the reason the parameter is not typed.
 */
class InboxFilterContractTest {

    private static final String PATH = "/api/v1/conversations";

    private final InboxFilterEnumOpenApiCustomizer customizer = new InboxFilterEnumOpenApiCustomizer();

    @Test
    @DisplayName("the served document declares filter as an enum of the five WIRE values")
    void filterIsDeclaredAsAnEnumOfWireValues() {
        Parameter filter = customise(documentWithFilter());

        assertThat(filter.getSchema()).isNotNull();
        assertThat(filter.getSchema().getType()).isEqualTo("string");
        assertThat(filter.getSchema().getEnum())
            .containsExactly("unassigned", "all-open", "assigned-to-others", "assigned-to-me",
                "history");

        // The two lists must be the same list, or the document would advertise a vocabulary the
        // server does not accept.
        assertThat(filter.getSchema().getEnum()).containsExactlyElementsOf(InboxFilter.acceptedValues());
    }

    @Test
    @DisplayName("the documented values are NOT the Java constant names")
    void valuesAreWireValuesNotConstantNames() {
        Parameter filter = customise(documentWithFilter());

        List<String> documented = (List<String>) filter.getSchema().getEnum();
        for (String value : documented) {
            assertThat(value).doesNotMatch("^[A-Z_]+$");
        }
        // `?filter=UNASSIGNED` is still a 400: the document offers only the wire values, and the
        // server accepts only the wire values.
        assertThat(documented).doesNotContain("UNASSIGNED", "ASSIGNED_TO_ME", "HISTORY");
    }

    @Test
    @DisplayName("the document names the default, so a client is not left guessing")
    void theDocumentStatesTheDefault() {
        Parameter filter = customise(documentWithFilter());

        assertThat(filter.getDescription())
            .contains(ConversationsController.defaultFilter().value())
            .contains("unassigned");
        assertThat(filter.getExample()).isEqualTo("unassigned");
    }

    @Test
    @DisplayName("the default is the work queue, it is one named place, and it is not a guess")
    void theDefaultIsTheWorkQueueAndIsOneNamedPlace() {
        // The ruling (ADR 0015): the default moved from `assigned-to-me` to `unassigned` because a
        // default must not be able to hide work from every employee at once. Showing someone a
        // conversation that is not theirs costs a moment of scanning; hiding an unassigned one from
        // everybody costs a customer waiting on a reply nobody can see is outstanding.
        //
        // This test previously asserted the OPPOSITE and carried a comment saying the change was a
        // product decision for the orchestrator rather than the engineer's to take. That was the
        // right boundary at the time and the right call by the agent to raise it. The decision has
        // now been taken, so the assertion follows the ruling.
        //
        // The second half is the part that matters over time: the default is named ONCE, on the enum,
        // and both the HTTP layer and the request record read that one constant. A literal repeated in
        // the controller and again in the record's compact constructor is two places for them to
        // disagree, and they would disagree silently.
        assertThat(InboxFilter.DEFAULT).isEqualTo(InboxFilter.UNASSIGNED);
        assertThat(ConversationsController.defaultFilter()).isSameAs(InboxFilter.DEFAULT);
        assertThat(ConversationsController.parseFilter(null)).isSameAs(InboxFilter.DEFAULT);
        assertThat(ConversationsController.parseFilter("   ")).isSameAs(InboxFilter.DEFAULT);
        // And the record's own default is the same constant, not a second literal.
        assertThat(new InboxQuery(null, null, null, null, null, 30).filter())
            .isSameAs(InboxFilter.DEFAULT);
    }

    @Test
    @DisplayName("a missing filter parameter fails the document export rather than silently losing the enum")
    void aRenamedParameterIsLoud() {
        // A silently-skipped customiser is an enum that vanished on a controller rename, and nobody
        // would know until a client sent a value the server refused. The promotion check is
        // blocking, so failing the export is the right severity.
        assertThatThrownBy(() -> customizer.customise(documentWithoutFilter()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("filter");

        assertThatThrownBy(() -> customizer.customise(new OpenAPI()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(PATH);
    }

    @Test
    @DisplayName("the refusal for an unaccepted value carries the accepted values")
    void theRefusalNamesWhatTheCallerMaySend() {
        assertThatThrownBy(() -> ConversationsController.parseFilter("everything"))
            .isInstanceOf(com.firstelement.crm.error.ValidationFailedException.class)
            .hasMessageContaining("unassigned")
            .hasMessageContaining("assigned-to-me");
    }

    @Test
    @DisplayName("every value the document publishes is one the parser accepts, and vice versa")
    void documentAndParserAgreeExactly() {
        // The closure property that makes the enum worth having: nothing is documented and refused,
        // and nothing is accepted and undocumented. Tested against the parser rather than against
        // the customiser alone, because the parser is what the HTTP layer actually calls.
        for (String documented : (List<String>) customise(documentWithFilter()).getSchema().getEnum()) {
            assertThat(ConversationsController.parseFilter(documented).value()).isEqualTo(documented);
        }
        assertThat(InboxFilter.acceptedValues()).hasSize(5);
    }

    @Test
    @DisplayName("NO other parameter in the document gains an enum -- the shared-Schema trap")
    void noOtherParameterIsContaminated() {
        // springdoc hands out SHARED model objects: the Schema instance behind one query parameter
        // is the same instance behind every other query parameter. So the obvious implementation --
        // mutate `filter`'s schema in place -- stamps the inbox vocabulary onto unrelated
        // parameters across the whole contract. That version of this class did exactly that, and it
        // corrupted five paths that have nothing to do with the inbox.
        //
        // The trap only reproduces if the schemas really are shared, so this test shares them the
        // way springdoc does. A test with freshly allocated schemas would pass against the buggy
        // implementation and prove nothing.
        StringSchema shared = new StringSchema().type("string");
        Parameter otherOnTheSamePath = new Parameter().name("projectId").in("query").required(false)
            .schema(shared);
        Parameter otherOnAnotherPath = new Parameter().name("resourceType").in("query")
            .required(false).schema(shared);

        Parameter filter = new Parameter().name("filter").in("query").required(false).schema(shared);
        Parameter cursor = new Parameter().name("cursor").in("query").required(false).schema(shared);

        Operation list = new Operation();
        list.setParameters(new ArrayList<>(List.of(otherOnTheSamePath, filter, cursor)));
        Operation notes = new Operation();
        notes.setParameters(new ArrayList<>(List.of(otherOnAnotherPath)));

        Paths paths = new Paths();
        paths.put(PATH, new PathItem().get(list));
        paths.put("/api/v1/notes", new PathItem().get(notes));
        paths.put(COUNTS_PATH, new PathItem().get(countsOperation()));
        OpenAPI openApi = new OpenAPI();
        openApi.setPaths(paths);

        customizer.customise(openApi);

        // The filter still carries the vocabulary...
        assertThat(schemaOf(list, "filter").getEnum())
            .containsExactlyElementsOf(InboxFilter.acceptedValues());
        // ...and nothing else in the document does.
        for (String unrelated : List.of("projectId", "cursor")) {
            assertThat(schemaOf(list, unrelated).getEnum())
                .as("%s must stay an unconstrained string", unrelated)
                .isNull();
        }
        assertThat(schemaOf(notes, "resourceType").getEnum())
            .as("a parameter on another path must stay an unconstrained string")
            .isNull();

        // Exactly one enum-bearing schema exists, counted across the WHOLE document rather than
        // asserted at three hand-picked spots: a spot check is what let the leak through.
        assertThat(countEnumBearingParameters(openApi)).isEqualTo(1);
    }

    @Test
    @DisplayName("the counts response names every view, so a client learns the key set")
    void theCountsResponseIsTyped() {
        // The route returns Map<String, Integer>, which springdoc publishes as a free-form
        // additionalProperties bag: self-describing about the values and silent about the keys.
        // The keys ARE the contract of this endpoint. A client reading only this response cannot
        // know which badges to render, so it renders whatever happens to arrive -- which is how the
        // original two-filter loop left three tabs showing a permanent zero that read as a broken
        // product rather than a missing number.
        OpenAPI openApi = documentWithFilter();
        customizer.customise(openApi);

        Schema<?> schema = countsSchemaOf(openApi);

        assertThat(schema.getType()).isEqualTo("object");
        // No free-form bag survives: a closed key set is the entire point.
        assertThat(schema.getAdditionalProperties())
            .as("the free-form map is what this replaces")
            .isNull();
        assertThat(schema.getProperties().keySet())
            .as("properties carry the tab order a client lays out, so order IS asserted here")
            .containsExactlyElementsOf(InboxFilter.acceptedValues());
        // `required` is a SET in JSON Schema and swagger-core normalises it to sorted order, so its
        // order is deliberately NOT asserted: that would assert a detail of the serialiser rather than
        // anything a client can rely on. The set is the whole claim -- a zero is a real answer, a
        // missing key is not, and this says so to a generated client.
        assertThat(schema.getRequired())
            .as("a zero is a real answer; a missing key is not, and required makes that visible")
            .containsExactlyInAnyOrderElementsOf(InboxFilter.acceptedValues());
        for (String view : InboxFilter.acceptedValues()) {
            assertThat(schema.getProperties().get(view).getType())
                .as("%s must be an integer, not an arbitrary value", view)
                .isEqualTo("integer");
        }
        // And the description has to warn about the thing a client will otherwise get wrong: these
        // views OVERLAP, so summing them yields a number that means nothing.
        assertThat(schema.getDescription()).containsIgnoringCase("overlap");
    }

    @Test
    @DisplayName("a missing counts route fails the document export rather than shipping a bare map")
    void aVanishedCountsRouteIsLoud() {
        // Same reasoning as the missing filter parameter. A customiser that quietly does nothing is a
        // contract constraint that vanished on a controller rename, and nobody finds out until a
        // client renders permanent zeroes. The document export is promotion-blocking, so failing it
        // is the right severity rather than a soft log line.
        Parameter filter = new Parameter().name("filter").in("query").required(false)
            .schema(new StringSchema().type("string"));
        Paths paths = new Paths();
        paths.put(PATH, new PathItem().get(new Operation().summary("List conversations")
            .parameters(new ArrayList<>(List.of(filter)))));
        OpenAPI withoutCounts = new OpenAPI();
        withoutCounts.setPaths(paths);

        assertThatThrownBy(() -> customizer.customise(withoutCounts))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(COUNTS_PATH);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    // Raw Schema: Parameter.getSchema() returns the swagger type itself, so getEnum() on the
    // wildcard form would infer List<? extends CAP> and refuse a List<String> comparison.
    @SuppressWarnings("rawtypes")
    private static io.swagger.v3.oas.models.media.Schema schemaOf(Operation operation,
                                                                     String name) {
        return operation.getParameters().stream()
            .filter(parameter -> name.equals(parameter.getName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no parameter named " + name))
            .getSchema();
    }

    /**
     * The {@code /inbox/counts} 200 schema, after customisation.
     *
     * <p>Reads whatever single media type the document carries rather than assuming JSON: the
     * served document still declares a wildcard media type for this endpoint, which is open
     * contract defect 5 in the ledger and not this suite's to fix. Hard-coding a media type here
     * would make this test fail for that unrelated reason, and a test that fails for an unrelated
     * reason gets deleted.
     */
    private static Schema<?> countsSchemaOf(OpenAPI openApi) {
        return openApi.getPaths().get(COUNTS_PATH).getGet()
            .getResponses().get("200").getContent().values().iterator().next().getSchema();
    }

    private static int countEnumBearingParameters(OpenAPI openApi) {
        int count = 0;
        for (io.swagger.v3.oas.models.PathItem path : openApi.getPaths().values()) {
            for (Operation operation : path.readOperations()) {
                if (operation == null || operation.getParameters() == null) {
                    continue;
                }
                for (Parameter parameter : operation.getParameters()) {
                    if (parameter.getSchema() != null && parameter.getSchema().getEnum() != null) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private Parameter customise(OpenAPI openApi) {
        customizer.customise(openApi);
        return openApi.getPaths().get(PATH).getGet().getParameters().stream()
            .filter(parameter -> "filter".equals(parameter.getName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the filter parameter vanished from the document"));
    }

    /** A document shaped like the served one: the path, the GET, and a bare string `filter`. */
    private static OpenAPI documentWithFilter() {
        Parameter filter = new Parameter()
            .name("filter").in("query").required(false)
            .schema(new StringSchema().type("string"));
        return documentWith(List.of(filter));
    }

    private static OpenAPI documentWithoutFilter() {
        Parameter projectId = new Parameter()
            .name("projectId").in("query").required(false)
            .schema(new StringSchema().type("string"));
        return documentWith(List.of(projectId));
    }

    private static OpenAPI documentWith(List<Parameter> parameters) {
        Operation get = new Operation().summary("List conversations");
        get.setParameters(new ArrayList<>(parameters));

        Paths paths = new Paths();
        paths.put(PATH, new PathItem().get(get));
        paths.put(COUNTS_PATH, new PathItem().get(countsOperation()));

        OpenAPI openApi = new OpenAPI();
        openApi.setPaths(paths);
        return openApi;
    }

    private static final String COUNTS_PATH = "/api/v1/inbox/counts";

    /**
     * A {@code /inbox/counts} operation shaped like the served one.
     *
     * <p>The response starts as the free-form {@code additionalProperties} map springdoc infers
     * from {@code Map<String, Integer>}. Every fixture in this class includes it, so the customiser
     * is always exercised against both of the things it does -- and so removing this from a fixture
     * fails loudly rather than quietly skipping the counts typing.
     */
    private static Operation countsOperation() {
        ObjectSchema inferred = new ObjectSchema();
        inferred.setType("object");
        inferred.setAdditionalProperties(new IntegerSchema().type("integer").format("int32"));

        MediaType media = new MediaType().schema(inferred);
        Content content = new Content().addMediaType("*/*", media);

        Operation counts = new Operation().summary("Saved-view counts");
        counts.setResponses(new ApiResponses().addApiResponse("200",
            new ApiResponse().description("OK").content(content)));
        return counts;
    }
}
