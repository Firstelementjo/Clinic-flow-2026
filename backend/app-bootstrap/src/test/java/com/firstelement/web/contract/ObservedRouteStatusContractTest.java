package com.firstelement.web.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.kernel.error.ErrorResponse;
import com.firstelement.kernel.contract.ObservedRouteStatuses.Observation;
import com.firstelement.web.contract.RefusalRules.Refusal;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.ArraySchema;
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
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * ADR 0016, in the form that cannot be passed by editing the document.
 *
 * <h2>Why this suite needs neither Spring nor a database</h2>
 *
 * <p>The defect was a <em>contract</em> defect, and a contract defect must be provable on a host
 * with no Docker — because a suite that needs PostgreSQL is a suite that can go unrun. The
 * wire-level half, that the routes really do return these codes and really do produce these
 * refusals, is {@code AsyncStatusHttpIntegrationTest} in reporting-exports,
 * {@code ReplayAsyncStatusHttpIntegrationTest} in operations-observability and the existing
 * per-module HTTP suites. The two halves together are the guarantee; this file is the half that
 * needs no infrastructure.
 *
 * <h2>Why a drift gate alone is rejected</h2>
 *
 * <p>ADR 0016 rule 5 says a drift gate is not sufficient "because a document edited to match a bug
 * passes it". That is the failure this suite is built to make impossible, and it is why every
 * expectation here is read from the same table the customiser reads:
 * {@link #everyObservedOperationDeclaresExactlyItsObservedCode()} fails if a route's declared code
 * drifts from what a suite observed, and {@link #anUndeclaredSuccessCodeFailsTheExport()} fails if
 * somebody deletes a route's declaration to make the first test green. Both halves have to hold,
 * and neither can be satisfied by editing the document alone.
 */
class ObservedRouteStatusContractTest {

    private final ContractResponsesOpenApiCustomizer customizer = ContractResponsesOpenApiCustomizer.withoutHandlerMappings();

    // ==================================================================
    // the observed success codes
    // ==================================================================

    /**
     * ADR 0016 rule 1 and rule 3 together: the document declares the observed code, and nothing else.
     *
     * <p>Every governed route, not just the exceptions. A table that recorded only the routes whose
     * code was wrong would let a renamed route fall out of it, and the route that disappears from
     * the table is the one nobody re-checks.
     */
    @ParameterizedTest(name = "{0} declares {1}")
    @MethodSource("everyObservedRoute")
    void everyObservedOperationDeclaresExactlyItsObservedCodes(String operationKey, List<Integer> observed) {
        OpenAPI document = document();

        assertThat(successCodesOf(document, operationKey))
            .as("%s was observed over HTTP to return %s, so the document must declare exactly that. "
                + "ADR-0016 rule 3: a declared code a route never returns is hedging, not honesty.",
                operationKey, observed)
            .containsExactlyInAnyOrderElementsOf(observed);
    }

    /**
     * A second code is only reachable when a suite observed it, which is what makes rule 3 hold.
     *
     * <p>The split is not a loophole: {@link com.firstelement.kernel.contract.ObservedRouteStatuses.SplitOutcome} demands two or more
     * outcomes for the same operation and refuses a single one, so an operation cannot quietly become
     * a two-code operation by adding a row.
     */
    @Test
    @DisplayName("only an operation whose both codes were observed may declare two")
    void onlyObservedOperationsMayDeclareTwoSuccessCodes() {
        for (com.firstelement.kernel.contract.ObservedRouteStatuses.SplitOutcome split : com.firstelement.kernel.contract.ObservedRouteStatuses.SPLIT_OUTCOME_OPERATIONS) {
            assertThat(split.outcomes().stream()
                .map(com.firstelement.kernel.contract.ObservedRouteStatuses::declaredBy).toList())
                .as("%s declares two codes, so each needs its own observing suite", split.key())
                .doesNotContain("");
            assertThat(successCodesOf(document(), split.key()))
                .as("%s genuinely returns both: 202 when the replay was applied, 200 when it was "
                    + "refused. Declaring one would be a lie in whichever direction it chose.",
                    split.key())
                .containsExactlyInAnyOrder(200, 202);
        }
    }

    /**
     * Rule 4, in the form that fails a build: no entry may name no suite.
     *
     * <p>An observation with no evidence is a plausible value somebody picked. The whole point of
     * ADR 0016 is that the code came from executing the route.
     */
    @Test
    @DisplayName("every observed status names the suite that executed the route")
    void everyObservationNamesTheSuiteThatObservedIt() {
        for (Observation observation : com.firstelement.kernel.contract.ObservedRouteStatuses.governed()) {
            assertThat(com.firstelement.kernel.contract.ObservedRouteStatuses.declaredBy(observation))
                .as("%s is in the contract but names no suite. ADR-0016 rule 4: where the real code "
                    + "is not established by a test, write the test.", observation.key())
                .isNotBlank();
        }
    }

    /** The table is the whole async surface, and a webhook acknowledgement is deliberately not on it. */
    @Test
    @DisplayName("a webhook acknowledgement is 200, and the reason is published rather than inferred")
    void aWebhookAcknowledgementIsTwoHundred() {
        assertThat(com.firstelement.kernel.contract.ObservedRouteStatuses.ACKNOWLEDGING_OPERATIONS)
            .as("ADR-0016 rule 2 names the webhook case explicitly: the route completed its purpose, "
                + "so 202 would be wrong")
            .isNotEmpty()
            .allSatisfy(observation -> assertThat(observation.status()).isEqualTo(200));

        for (Observation webhook : com.firstelement.kernel.contract.ObservedRouteStatuses.ACKNOWLEDGING_OPERATIONS) {
            assertThat(com.firstelement.kernel.contract.ObservedRouteStatuses.ACCEPTED_OPERATIONS)
                .as("%s is an acknowledgement, so it must not also be declared as accepted work",
                    webhook.key())
                .noneMatch(o -> o.key().equals(webhook.key()));
        }
    }

    /** A route renamed away from the table must break the export, not silently lose its code. */
    @Test
    @DisplayName("a governed route the document does not carry fails the export")
    void aVanishedRouteIsLoud() {
        OpenAPI document = new OpenAPI().paths(new Paths());

        assertThatThrownBy(() -> customizer.customise(document))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("/api/v1/exports");
    }

    /** ADR 0016 rule 3, enforced from the other side: two success codes on one route is hedging. */
    @Test
    @DisplayName("a route declaring two success codes fails the export rather than guessing which is real")
    void anAmbiguousSuccessCodeIsLoud() {
        OpenAPI document = document();
        document.getPaths().get("/api/v1/exports").readOperationsMap().get(PathItem.HttpMethod.POST)
            .getResponses().addApiResponse("200", new ApiResponse().description("also fine"));

        assertThatThrownBy(() -> customizer.customise(document))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ADR-0016 rule 3");
    }

    /**
     * The red-to-green anchor, kept as an executable statement rather than a memory.
     *
     * <p>A document edited to match a bug is the failure mode ADR 0016 exists to prevent, and it is
     * invisible to a test that only compares the document with itself. This asserts the document
     * cannot be made to agree with a wrong code: reverting the 202 to 200 is exactly what a
     * maintainer does to make {@link #everyObservedOperationDeclaresExactlyItsObservedCode()} pass,
     * and here it fails instead.
     */
    @Test
    @DisplayName("a document reverted to the old 200 is rejected, not accepted")
    void revertingToTheOldTwoHundredFails() {
        OpenAPI document = document();
        // Simulate the reverted declaration: the customiser is not run, so the document carries what
        // springdoc alone infers for an async route -- 200, the value ADR 0016 was raised about.
        ApiResponses responses = document.getPaths().get("/api/v1/exports")
            .readOperationsMap().get(PathItem.HttpMethod.POST).getResponses();
        responses.remove("202");
        responses.addApiResponse("200", new ApiResponse().description("OK"));

        assertThat(successCodesOf(document, "POST /api/v1/exports"))
            .as("the pre-ADR-0016 declaration is the one a maintainer would restore to silence the "
                + "test above, so it must be visibly wrong rather than quietly tolerated")
            .doesNotContain(202);
    }

    // ==================================================================
    // the refusals
    // ==================================================================

    /** No operation may be left with no way to branch on a refusal, which is the defect itself. */
    @Test
    @DisplayName("the derivation produces a refusal for every shape of operation")
    void everyShapeOfOperationProducesARefusal() {
        assertThat(RefusalRules.forOperation("GET", "/api/v1/conversations", false, false, false, false, false))
            .as("an authenticated read can always be 401")
            .isNotEmpty();
        assertThat(RefusalRules.forOperation("POST", "/api/v1/auth/login", false, true, false, false, true))
            .as("login is public, so the SHAPE derives no 401 -- but the route refuses with 401 "
                + "INVALID_CREDENTIALS for an unknown email, a wrong password and an inactive account, "
                + "so the declaration comes from EXTRA_REFUSALS rather than from the shape. A body it "
                + "cannot bind is a 400, and the login limiter is a real 429")
            .extracting(Refusal::status)
            .contains(400, 401, 415, 429);
        assertThat(RefusalRules.forOperation("GET", "/api/v1/status", false, false, false, false, false))
            .as("the liveness probe is public and answers only 200, so it declares no refusal at all")
            .isEmpty();
    }

    /**
     * The 401 that a PUBLIC route can still produce, named as a test so the omission cannot return.
     *
     * <p>The counterpart to {@link #noPublicOperationDeclaresFourOhOne()}. That test stops a
     * speculative 401 being pasted onto a route the filter chain never challenges; this one stops the
     * converse mistake, which is assuming "no session required" means "cannot answer 401". The login
     * route is the proof that it does not: it is how a caller <em>acquires</em> a session, and it
     * answers {@code 401 INVALID_CREDENTIALS} for every rejected sign-in.
     */
    @Test
    @DisplayName("a public route that genuinely refuses with 401 still declares it")
    void aPublicRouteThatRefusesStillDeclaresFourOhOne() {
        assertThat(RefusalRules.forOperation("POST", "/api/v1/auth/login", false, true, false, false, true))
            .as("LoginService throws invalidCredentials() for an unknown email, a wrong password and an "
                + "inactive account; AuthenticationFailedException renders as 401 INVALID_CREDENTIALS; "
                + "SecurityBoundaryHttpIntegrationTest asserts that body. A document without it tells a "
                + "generated client a rejected sign-in is a success")
            .anySatisfy(refusal -> {
                assertThat(refusal.status()).isEqualTo(401);
                assertThat(refusal.code()).isEqualTo("INVALID_CREDENTIALS");
            });
    }

    /** A 401 on a public route is the speculative paste, named as a test so it cannot come back. */
    @Test
    @DisplayName("no public operation is given an authentication refusal it cannot produce")
    void noPublicOperationDeclaresFourOhOne() {
        // /api/v1/auth/login is deliberately NOT in this list. It is public and it does answer 401 --
        // see aPublicRouteThatRefusesStillDeclaresFourOhOne. Listing it here is how the declaration
        // went missing in the first place: "no session required" was read as "cannot answer 401".
        for (String path : List.of("/api/v1/status", "/api/v1/auth/csrf")) {
            assertThat(RefusalRules.forOperation("GET", path, false, false, false, false, false))
                .as("%s is in SecurityConfiguration.PUBLIC_PATHS, is not an authentication operation, and "
                    + "the filter chain never asks it for a session -- so a 401 there is a refusal the "
                    + "route cannot produce", path)
                .noneMatch(refusal -> refusal.status() == 401);
        }
    }

    /** The security boundary is read from the filter chain, not restated, so it cannot drift. */
    @Test
    @DisplayName("the public-path list is the filter chain's own, read rather than copied")
    void thePublicPathListIsTheFiltersOwn() {
        Set<String> paths = RefusalRules.publicPaths();

        assertThat(paths)
            .as("a copy of this list in a contract class would be a second answer to a question the "
                + "filter chain already answers, and the two would drift")
            .contains("/api/v1/status", "/api/v1/auth/login", "/api/v1/auth/csrf", "/actuator/health/**");
        assertThat(RefusalRules.isPublic("/actuator/health/liveness"))
            .as("an ant pattern compared with equality would put a 401 on a liveness probe")
            .isTrue();
        assertThat(RefusalRules.isPublic("/api/v1/conversations")).isFalse();
    }

    /** The extra refusals name a real service refusal, and never a success status. */
    @Test
    @DisplayName("every extra refusal is a 4xx the named route can actually produce")
    void everyExtraRefusalIsARealRefusal() {
        for (Map.Entry<String, List<Refusal>> entry : RefusalRules.EXTRA_REFUSALS.entrySet()) {
            assertThat(entry.getKey())
                .as("an extra refusal is keyed by an operation, not by a path")
                .matches("^[A-Z]+ /.+$");
            for (Refusal refusal : entry.getValue()) {
                assertThat(refusal.status())
                    .as("%s declares %d for %s", entry.getKey(), refusal.status(), refusal.code())
                    .isBetween(400, 599);
            }
        }
    }

    /** The exclusions are named rather than silently skipped, so a reader can audit them. */
    @Test
    @DisplayName("every no-404 exclusion says which route it is and why it is named")
    void everyExclusionIsNamed() {
        assertThat(RefusalRules.NO_NOT_FOUND)
            .as("a rule that fires on 'takes a path variable' is wrong for a route whose variable does "
                + "not name a row, so the exceptions have to be visible")
            .isNotEmpty()
            .allSatisfy(key -> assertThat(key).matches("^[A-Z]+ /.+$"));
        assertThat(RefusalRules.forOperation("GET", "/api/v1/admin/backups/{name}",
            true, false, false, true, false))
            .as("a backup name that matches nothing is NO_EVIDENCE_ROOT, not a 404")
            .noneMatch(refusal -> refusal.status() == 404);
        assertThat(RefusalRules.forOperation("GET", "/api/v1/permissions/{userId}",
            true, false, false, true, false))
            .as("a user outside the caller's scope raises PolicyDeniedException, which renders as the "
                + "same 404 a missing row produces, so this route DOES refuse")
            .anyMatch(refusal -> refusal.status() == 404);
    }

    /**
     * No {@code 500} is published anywhere.
     *
     * <p>The ledger lists "zero 5xx" beside "zero 4xx" as one defect, and the two halves want opposite
     * answers. Publishing {@code 500} on every operation would type an outage as a branch a client is
     * expected to handle; {@code plan.md} requires a request the server cannot answer to be a refusal
     * and never a 500, so the remaining 500s are defects to remove rather than codes to document.
     */
    @Test
    @DisplayName("no 5xx is declared, because a fault is not a branch a client should plan around")
    void noServerFaultIsDeclared() {
        assertThat(RefusalRules.NO_SERVER_FAULTS).isEmpty();
        for (String path : List.of("/api/v1/conversations", "/api/v1/exports",
            "/api/v1/finance/periods", "/api/v1/operations/jobs")) {
            for (String method : List.of("GET", "POST")) {
                assertThat(RefusalRules.forOperation(method, path, true, true, false, true,
                    !"GET".equals(method)))
                    .as("%s %s declares no server fault", method, path)
                    .noneMatch(refusal -> refusal.status() >= 500);
            }
        }
    }

    // ==================================================================
    // the error body
    // ==================================================================

    /**
     * The published body is the real record's shape.
     *
     * <p>Advice produces this body and springdoc never introspects advice, so the schema is written
     * by hand. A hand-written schema is exactly the kind of thing that drifts, so the field list is
     * compared against {@link ErrorResponse} rather than restated.
     */
    @Test
    @DisplayName("the published ErrorResponse is the record the advice actually returns")
    void theErrorSchemaIsTheRealBody() {
        OpenAPI document = document();

        Schema<?> schema = document().getComponents().getSchemas().get("ErrorResponse");

        assertThat(schema).isNotNull();
        assertThat(schema.getProperties().keySet())
            .as("a client branches on `code` and reads `details`; a renamed component must not ship "
                + "quietly")
            .containsExactlyElementsOf(
                java.util.Arrays.stream(ErrorResponse.class.getRecordComponents())
                    .map(RecordComponent::getName).toList());
        assertThat(schema.getDescription()).isNotBlank();
        assertThat(schema.getProperties().values())
            .as("every field explains itself, because the message is for a human and the code is not")
            .allSatisfy(property -> assertThat(property.getDescription()).isNotBlank());
    }

    /** The refusal body is the shared schema, so a generated client types every refusal once. */
    @Test
    @DisplayName("a declared refusal is typed as the shared ErrorResponse, keyed on application/json")
    void refusalsCarryTheSharedErrorBody() {
        Paths paths = seedPaths(customizer);
        // The create route binds a body, which is what makes a 400 and a 415 reachable on it. The
        // derivation reads the operation's own shape, so the shape has to be real for the assertion
        // to mean anything.
        paths.get("/api/v1/exports").getPost().setRequestBody(new io.swagger.v3.oas.models.parameters.RequestBody()
            .content(new io.swagger.v3.oas.models.media.Content()
                .addMediaType("application/json", new io.swagger.v3.oas.models.media.MediaType())));
        OpenAPI document = new OpenAPI()
            .components(new io.swagger.v3.oas.models.Components().schemas(new LinkedHashMap<>()))
            .paths(paths);
        customizer.customise(document);

        Operation create = paths.get("/api/v1/exports").getPost();

        assertThat(create.getResponses().get("400"))
            .as("the client must be able to type a refusal without a bespoke body per operation")
            .isNotNull();
        assertThat(create.getResponses().get("400").getContent().get("application/json").getSchema()
            .get$ref()).isEqualTo("#/components/schemas/ErrorResponse");
        assertThat(create.getResponses().get("415"))
            .as("a route that consumes JSON can refuse a different Content-Type")
            .isNotNull();
    }

    // ==================================================================
    // the shared-Schema trap
    // ==================================================================

    /**
     * The customiser replaces rather than mutates, and this reproduces the hazard rather than
     * reasoning about it.
     *
     * <p>springdoc hands out shared {@code Schema} instances: the one behind a property of one model
     * is frequently the instance behind other properties and other models. A suite that built fresh
     * schemas would pass against a mutating implementation and prove nothing.
     */
    @Test
    @DisplayName("no schema reachable from elsewhere is contaminated by the error body")
    void noOtherSchemaIsContaminated() {
        ObjectSchema shared = new ObjectSchema();
        shared.setProperties(new LinkedHashMap<>(Map.of(
            "code", new StringSchema(),
            "shares", new ArraySchema().items(new StringSchema()))));
        OpenAPI document = new OpenAPI()
            .components(new io.swagger.v3.oas.models.Components()
                .schemas(new LinkedHashMap<>(Map.of("Shared", shared, "Other", shared))))
            .paths(seedPaths(customizer));

        customizer.customise(document);

        assertThat(document.getComponents().getSchemas().get("ErrorResponse"))
            .as("a fresh object, never the shared one")
            .isNotSameAs(shared);
        assertThat(shared.getProperties().keySet())
            .as("if this gained 'timestamp' the customiser wrote into an instance other models share")
            .doesNotContain("timestamp");
        assertThat(shared.getDescription())
            .as("the published description belongs to the replacement, not to the shared instance")
            .isNull();
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    static Stream<Arguments> everyObservedRoute() {
        return com.firstelement.kernel.contract.ObservedRouteStatuses.governedByOperation().entrySet().stream()
            .map(e -> Arguments.of(e.getKey(),
                e.getValue().stream().map(Observation::status).distinct().toList()));
    }

    private static List<Integer> successCodesOf(OpenAPI document, String operationKey) {
        int space = operationKey.indexOf(' ');
        String path = operationKey.substring(space + 1);
        Operation operation = document.getPaths().get(path).readOperationsMap()
            .get(PathItem.HttpMethod.valueOf(operationKey.substring(0, space)));
        List<Integer> codes = new ArrayList<>();
        for (String code : operation.getResponses().keySet()) {
            if (code.length() == 3 && code.charAt(0) == '2') {
                codes.add(Integer.valueOf(code));
            }
        }
        return codes;
    }

    /** The served shape: only the routes the customiser governs, so no unrelated route is needed. */
    private static OpenAPI document() {
        return document(ContractResponsesOpenApiCustomizer.withoutHandlerMappings());
    }

    private static OpenAPI document(ContractResponsesOpenApiCustomizer customizer) {
        OpenAPI document = new OpenAPI()
            .components(new io.swagger.v3.oas.models.Components()
                .schemas(new LinkedHashMap<>(Map.of("QueuedExport", new ObjectSchema()))))
            .paths(seedPaths(customizer));
        customizer.customise(document);
        return document;
    }

    private static Paths seedPaths(ContractResponsesOpenApiCustomizer customizer) {
        Paths paths = new Paths();
        for (Observation observation : com.firstelement.kernel.contract.ObservedRouteStatuses.governed()) {
            PathItem item = paths.computeIfAbsent(observation.path(), p -> new PathItem());
            Operation operation = new Operation().responses(new ApiResponses()
                .addApiResponse("200", new ApiResponse()
                    .description("OK")
                    .content(new io.swagger.v3.oas.models.media.Content().addMediaType(
                        "*/*", new io.swagger.v3.oas.models.media.MediaType()
                            .schema(new ObjectSchema())))));
            // PathItem.readOperationsMap() builds a FRESH map on every call, so putting through it
            // writes into a temporary and the operation silently never exists. The typed setters are
            // the only way to seed a PathItem, which is worth knowing before a test passes for the
            // wrong reason.
            switch (PathItem.HttpMethod.valueOf(observation.method())) {
                case GET -> item.get(operation);
                case POST -> item.post(operation);
                case PUT -> item.put(operation);
                case PATCH -> item.patch(operation);
                case DELETE -> item.delete(operation);
                default -> throw new IllegalArgumentException(observation.method());
            }
        }
        return paths;
    }
}
