package com.firstelement.clinic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The <b>declared</b> half of the clinic contract, read from the document the assembled
 * application actually serves.
 *
 * <h2>Why this suite lives in app-bootstrap and not in the module that owns the routes</h2>
 *
 * <p>Because the refusals are stamped by {@code ContractResponsesOpenApiCustomizer} and the
 * success codes by {@code ObservedRouteStatuses}, both of which live in
 * {@code :backend:app-bootstrap}. {@code :backend:inbox-helpdesk}'s test application does not
 * carry them, so a module-local document read sees a document with no refusals on it — and
 * asserting against that would assert the shape of a <em>partial</em> context, which is the
 * Phase-10 lesson applied to contracts: a module's own suite can be perfectly green while the
 * deployed contract is wrong.
 *
 * <h2>Why filters are off</h2>
 *
 * <p>Same reason as {@code OpenApiDocumentExportTest}: asking the API to describe itself is not
 * a tenant question, and in a real deployment this document is served on the management port
 * where the filter chain does not apply. {@code springdoc.use-management-port=false} binds it
 * to the main context here so the suite needs no listening socket. Neither choice changes the
 * document; which port it is published on is a deployment decision.
 *
 * <h2>How this pairs with the observed half</h2>
 *
 * <p>The route behaviour is executed over real HTTP by
 * {@code com.firstelement.inbox.ClinicReadModelHttpIntegrationTest}, which asserts the observed
 * status of every clinic route. This file asserts the declared status. The two are kept in
 * different files on purpose: a single test that did both would pass on any consistent pair,
 * including a consistently wrong one, because the same defect would produce the same wrong
 * answer in the document and in the assertion. ADR 0016 rule 1 is "observe, then declare", and
 * the separation is what makes the ordering real rather than rhetorical.
 */
@SpringBootTest(properties = {
    "firstelement.environment=TEST",
    "management.health.db.enabled=false",
    "management.health.redis.enabled=false",
    "springdoc.use-management-port=false"
})
@AutoConfigureMockMvc(addFilters = false)
class ClinicOpenApiContractTest {

    /**
     * The clinic routes, with the status each was observed to return.
     *
     * <p>Written down rather than derived, because the observation is made over a socket in
     * another module and this one cannot reach it. If a route's real code changes, this table is
     * the thing that must be re-derived from a run — not adjusted until the assertion is green,
     * which is the failure ADR 0016 exists to prevent.
     *
     * <p>All seven are {@code 200}: they are synchronous reads that complete inside the request,
     * so {@code 202} would be a different lie.
     */
    private static final Map<String, Integer> OBSERVED_SUCCESS = new LinkedHashMap<>(Map.of(
        "GET /api/v1/clinic/doctors", 200,
        "GET /api/v1/clinic/doctors/{doctorId}", 200,
        "GET /api/v1/clinic/procedures", 200,
        "GET /api/v1/clinic/procedures/{procedureId}", 200,
        "GET /api/v1/clinic/appointments", 200,
        "GET /api/v1/clinic/appointments/{appointmentId}", 200,
        "GET /api/v1/clinic/analytics", 200));

    private static final List<String> LIST_ROUTES = List.of(
        "/api/v1/clinic/doctors",
        "/api/v1/clinic/procedures",
        "/api/v1/clinic/appointments",
        "/api/v1/clinic/analytics");

    private static final List<String> ADDRESSED_ROUTES = List.of(
        "/api/v1/clinic/doctors/{doctorId}",
        "/api/v1/clinic/procedures/{procedureId}",
        "/api/v1/clinic/appointments/{appointmentId}");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;

    // ==================================================================
    // success codes
    // ==================================================================

    @Nested
    @DisplayName("declared success status")
    class SuccessStatus {

        @Test
        @DisplayName("every clinic operation declares exactly the code it was observed to return")
        void everyClinicOperationDeclaresItsObservedCode() throws Exception {
            JsonNode document = served();

            for (Map.Entry<String, Integer> observed : OBSERVED_SUCCESS.entrySet()) {
                String path = observed.getKey().substring(observed.getKey().indexOf(' ') + 1);

                assertThat(successCodes(operation(document, path)))
                    .as("%s was observed over HTTP to return %d, so the document must declare "
                        + "exactly that. It is a synchronous read: 202 would tell a client the "
                        + "work is outstanding when it has already happened",
                        observed.getKey(), observed.getValue())
                    .containsExactly(String.valueOf(observed.getValue()));
            }
        }

        @Test
        @DisplayName("no clinic operation declares two success codes")
        void noClinicOperationDeclaresTwoSuccessCodes() throws Exception {
            JsonNode document = served();

            for (String path : OBSERVED_SUCCESS.keySet().stream()
                    .map(key -> key.substring(key.indexOf(' ') + 1)).toList()) {
                assertThat(successCodes(operation(document, path)))
                    .as("ADR-0016 rule 3: a second code a route never returns is hedging, not "
                        + "honesty")
                    .hasSize(1);
            }
        }

        @Test
        @DisplayName("no clinic route is on the outstanding-work table")
        void noClinicRouteIsGovernedByTheAsyncTable() {
            for (var observation : com.firstelement.kernel.contract.ObservedRouteStatuses
                    .governed()) {
                assertThat(observation.path())
                    .as("%s is recorded as work that is outstanding when the response is written, "
                        + "but a clinic read has completed. A read on that table would teach a "
                        + "generated client to poll for something that already exists",
                        observation.key())
                    .doesNotStartWith("/api/v1/clinic");
            }
        }
    }

    // ==================================================================
    // refusals
    // ==================================================================

    @Nested
    @DisplayName("declared refusals")
    class RefusalStatus {

        @Test
        @DisplayName("a list route declares exactly 401 and 403, and nothing it cannot produce")
        void aListRouteDeclaresTwoRefusals() throws Exception {
            JsonNode document = served();

            for (String path : LIST_ROUTES) {
                assertThat(refusalCodes(operation(document, path)))
                    .as("%s consumes no body, requires no parameter and takes no path variable, "
                        + "so 400 (an unbindable input) and 404 (a name that matches nothing) are "
                        + "refusals it cannot produce. Declaring either is the speculative paste "
                        + "this derivation exists to avoid", path)
                    .containsExactlyInAnyOrder("401", "403");
            }
        }

        @Test
        @DisplayName("an addressed route declares exactly 401, 403 and 404")
        void anAddressedRouteDeclaresThreeRefusals() throws Exception {
            JsonNode document = served();

            for (String path : ADDRESSED_ROUTES) {
                assertThat(refusalCodes(operation(document, path)))
                    .as("%s takes a REQUIRED path variable, so 404 is reachable and so is a 400 for one that is not a UUID — and it is the SAME 404 a "
                        + "foreign id produces, which is the non-disclosure property rather than "
                        + "a separate case", path)
                    .containsExactlyInAnyOrder("400", "401", "403", "404");
            }
        }

        @Test
        @DisplayName("no clinic route declares a 5xx")
        void noClinicRouteDeclaresAServerFault() throws Exception {
            JsonNode document = served();

            for (String path : allPaths()) {
                assertThat(refusalCodes(operation(document, path)))
                    .as("%s declares no 5xx: a fault in this codebase is not a branch a client "
                        + "should plan around, and publishing one types an outage as a normal "
                        + "outcome", path)
                    .noneMatch(code -> code.startsWith("5"));
            }
        }

        @Test
        @DisplayName("every declared refusal is the shared ErrorResponse")
        void refusalsCarryTheSharedErrorBody() throws Exception {
            JsonNode document = served();

            for (String path : ADDRESSED_ROUTES) {
                for (String code : refusalCodes(operation(document, path))) {
                    JsonNode refusal = operation(document, path).get("responses").get(code);
                    assertThat(refusal.get("content").get("application/json").get("schema")
                        .get("$ref").asText())
                        .as("%s %s", path, code)
                        .isEqualTo("#/components/schemas/ErrorResponse");
                    assertThat(refusal.get("description").asText())
                        .as("%s %s must explain itself; a client that has to guess what a 404 "
                            + "means here cannot tell 'not yours' from 'no such record'", path, code)
                        .isNotBlank();
                }
            }
        }
    }

    // ==================================================================
    // schema
    // ==================================================================

    @Nested
    @DisplayName("the published schemas")
    class Schemas {

        @Test
        @DisplayName("the clinic response shapes are published, not free-form")
        void theClinicShapesAreTyped() throws Exception {
            JsonNode schemas = served().get("components").get("schemas");

            assertThat(schemas.has("ClinicAnalytics"))
                .as("the analytics body is a typed component, so a generated client reads "
                    + "funnelByStatus and demandByProcedure rather than a free-form object")
                .isTrue();
            assertThat(schemas.has("Doctor"))
                .as("the roster projection is typed too — a bare Map<String,Object> would leave a "
                    + "client defensively reading fields the server always sends")
                .isTrue();
            assertThat(schemas.has("Appointment")).isTrue();
            assertThat(schemas.has("Procedure")).isTrue();
        }

        @Test
        @DisplayName("the analytics component carries the provenance fields, not only the figures")
        void theAnalyticsComponentCarriesProvenance() throws Exception {
            JsonNode analytics = served().get("components").get("schemas").get("ClinicAnalytics");
            List<String> properties = new ArrayList<>();
            analytics.get("properties").fieldNames().forEachRemaining(properties::add);

            assertThat(properties)
                .as("the three provenance records are record COMPONENTS, so Jackson serialises "
                    + "them. They were methods on the record in the first draft, which read more "
                    + "cleanly in Java and published nothing at all — the response carried the "
                    + "figures and dropped the only field saying where they came from")
                .contains("funnelProvenance", "demandProvenance", "workloadProvenance",
                    "drillThroughPath", "sourceRowSampleLimit");

            JsonNode funnel = analytics.get("properties").get("funnelProvenance");
            assertThat(funnel.has("allOf") || funnel.has("$ref") || funnel.has("type"))
                .as("funnelProvenance is declared, whatever its inline shape")
                .isTrue();
        }

        @Test
        @DisplayName("the roster projection does not publish clinician contact details")
        void theRosterSchemaOmitsContactDetails() throws Exception {
            JsonNode doctor = served().get("components").get("schemas").get("Doctor");
            List<String> properties = new ArrayList<>();
            doctor.get("properties").fieldNames().forEachRemaining(properties::add);

            assertThat(properties)
                .as("Phase 33 split doctor:read from doctor:read-management over exactly these "
                    + "two columns. A schema that published them would let a client be written "
                    + "against fields this permission does not authorize")
                .doesNotContain("email", "phone")
                .contains("name", "specialty", "workingDays", "startHour", "endHour",
                    "slotDuration");
        }

        @Test
        @DisplayName("the appointment projection does not publish clinical notes or patient phone")
        void theAppointmentSchemaOmitsUnauthorisedFields() throws Exception {
            JsonNode appointment =
                served().get("components").get("schemas").get("Appointment");
            List<String> properties = new ArrayList<>();
            appointment.get("properties").fieldNames().forEachRemaining(properties::add);

            assertThat(properties)
                .as("no clinic permission in the enum names a free-text clinical note or a "
                    + "second patient identifier. appointment:read names the schedule and the "
                    + "Phase 3 appointment list needs patientEmail to identify the patient; the "
                    + "phone number remains withheld because the legacy matrix reserves that "
                    + "disclosure for GET /clinic/reminders behind REMINDER_READ, which is not in "
                    + "the enum yet. Projecting it here would hand the disclosure to a permission "
                    + "that never named it")
                .doesNotContain("notes", "aiProposalReason", "patientPhone")
                .contains("patientName", "patientEmail", "appointmentDate", "status",
                    "isAiProposed");
        }
    }

    // ==================================================================
    // fixtures
    // ==================================================================

    private JsonNode served() throws Exception {
        byte[] document = mvc.perform(get("/api/v1/openapi"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsByteArray();
        JsonNode parsed = json.readTree(document);
        assertThat(parsed.get("paths").has("/api/v1/clinic/analytics"))
            .as("the clinic surface must be in the served document. A document without it would "
                + "generate a client with no clinic client at all, which is the state this slice "
                + "exists to end")
            .isTrue();
        return parsed;
    }

    private List<String> allPaths() {
        List<String> paths = new ArrayList<>(LIST_ROUTES);
        paths.addAll(ADDRESSED_ROUTES);
        return paths;
    }

    private JsonNode operation(JsonNode document, String path) {
        JsonNode paths = document.get("paths");
        assertThat(paths.has(path)).as("%s is missing from the served document", path).isTrue();
        JsonNode get = paths.get(path).get("get");
        assertThat(get).as("%s declares no GET", path).isNotNull();
        return get;
    }

    private List<String> successCodes(JsonNode operation) {
        return codesWithPrefix(operation, '2');
    }

    private List<String> refusalCodes(JsonNode operation) {
        return codesWithPrefix(operation, '4');
    }

    private List<String> codesWithPrefix(JsonNode operation, char prefix) {
        List<String> codes = new ArrayList<>();
        operation.get("responses").fieldNames().forEachRemaining(code -> {
            if (code.length() == 3 && code.charAt(0) == prefix) {
                codes.add(code);
            }
        });
        return codes;
    }
}