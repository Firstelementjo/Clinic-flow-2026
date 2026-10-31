package com.firstelement.security.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstelement.kernel.web.CorrelationIdFilter;
import com.firstelement.security.event.SecurityEvent;
import com.firstelement.security.event.SecurityEventType;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import securityscope.probe.SecurityScopeProbeConfiguration;
import securityscope.probe.SecurityScopeProbeConfiguration.RecordingSecurityEventPublisher;
import securityscope.probe.SecurityScopeProbeRoutes;

/**
 * Phase 19: a policy denial must never become an internal error, and the reason must never be a
 * convention.
 *
 * <h2>What the defect was</h2>
 *
 * <p>{@code SecurityExceptionAdvice} declared
 * {@code @RestControllerAdvice(basePackages = "com.firstelement")}. A {@code @RestControllerAdvice}
 * that declares {@code basePackages} is consulted only for a controller in that package, so a
 * {@link PolicyDeniedException} raised from a controller outside it was not rendered as the
 * non-disclosing {@code 404} the whole boundary exists to produce: it fell past the security advice
 * to the application's {@code Exception} catch-all and a cross-tenant denial came back
 * {@code 500 INTERNAL_ERROR}.
 *
 * <p>That is wrong twice. A {@code 500} is logged at {@code error} and paged, so the request that
 * tells you an attacker is walking another tenant's ids is the one that manufactures the most
 * incident signal. And a refusal that is only a refusal for controllers somebody remembered to
 * place in a namespace is a security control held by a convention instead of by configuration.
 *
 * <h2>Why every assertion here is made over HTTP, from outside the namespace</h2>
 *
 * <p>Because the defect was never in a handler. It was in which advice Spring MVC picked, and that
 * choice happens inside the dispatcher over the advice beans <em>in order</em>, for the exception
 * that actually escaped the handler method. Calling {@code advice.policyDenied(ex, request)}
 * directly would assert the handler and skip the only part that was broken -- and a handler that is
 * registered but shadowed reads exactly like one that is absent. So each case below is a real
 * request through a real {@code DispatcherServlet}, and the assertions are on the response.
 *
 * <p>The probe controller lives in {@code securityscope.probe}, outside {@code com.firstelement},
 * which is the whole point: in the product every controller is inside the namespace, so the scoped
 * advice looked correct there forever. {@link #theProbeControllerIsOutsideTheNamespace()} asserts
 * that property of the fixture directly, so it cannot stop covering the defect by being moved back
 * inside the namespace.
 *
 * <h2>Why a database is not involved</h2>
 *
 * <p>Nothing here touches persistence. The security event publisher is replaced by a recorder, as
 * {@code app-bootstrap}'s probe configuration does, because the property under test is what the
 * advice <em>published</em>, not what the production publisher would have written to
 * {@code audit_logs}. A container would buy nothing and cost a stage. The {@code firstelement}
 * development database is never opened by this suite.
 *
 * <h2>Why the assembled application is proven elsewhere</h2>
 *
 * <p>This module cannot depend on {@code :backend:crm-core} or {@code :backend:app-bootstrap}: both
 * depend on it. So the two advices below the security one are mirrored in
 * {@code securityscope.probe.ProbeAdviceLadder} with their order and their handling width intact,
 * and only {@link SecurityExceptionAdvice} is the production class. The rendering of the real
 * advices over real controllers is asserted by {@code app-bootstrap}'s
 * {@code ErrorMappingHttpIntegrationTest}; this suite is the complement that only this module can
 * write, because only it can put a controller somewhere the convention does not cover.
 */
@SpringBootTest(classes = SecurityScopeProbeConfiguration.class,
    properties = {
        "firstelement.environment=TEST",
        "management.health.db.enabled=false",
        "spring.flyway.enabled=false",
        "spring.main.banner-mode=off"
    })
@AutoConfigureMockMvc(addFilters = false)
class SecurityExceptionAdviceScopeHttpTest {

    private static final String PROBE = "/advice-scope-probe";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private RecordingSecurityEventPublisher events;

    @BeforeEach
    void setUp() {
        events.reset();
    }

    // ==================================================================
    // the assertion this phase exists for
    // ==================================================================

    @Test
    @DisplayName("a cross-tenant denial raised outside com.firstelement is 404, not 500")
    void crossTenantDenialIsNotFoundAndNotFiveHundred() throws Exception {
        MvcResult result = mvc.perform(get(PROBE + "/cross-tenant"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.message").value("Resource not found"))
            .andExpect(jsonPath("$.details").isEmpty())
            .andReturn();

        assertThat(result.getResponse().getStatus())
            .as("a denial rendered by the floor instead of the security advice is 500, which pages "
                + "and discloses that the server treats a foreign id differently from no id at all")
            .isEqualTo(404);
    }

    @Test
    @DisplayName("the probe controller really is outside com.firstelement, or nothing above proves anything")
    void theProbeControllerIsOutsideTheNamespace() {
        String probePackage = SecurityScopeProbeRoutes.class.getPackageName();

        assertThat(probePackage)
            .as("this suite exists because a package-scoped advice answers 500 outside its package; "
                + "moving the fixture back inside the namespace would make every other test here pass "
                + "without covering the defect")
            .doesNotStartWith("com.firstelement");
    }

    @Test
    @DisplayName("a denial raised from a service below the controller keeps its 404")
    void nestedDenialKeepsItsStatus() throws Exception {
        mvc.perform(get(PROBE + "/cross-tenant/nested"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    @Test
    @DisplayName("the same route answers 200 when nothing is wrong, so the 404 is the denial and not a broken route")
    void theRouteIsReachable() throws Exception {
        mvc.perform(get(PROBE + "/reachable"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.reachable").value(true));
    }

    // ==================================================================
    // non-disclosure: the denial must look exactly like a missing row
    // ==================================================================

    @Test
    @DisplayName("a denial and a genuinely nonexistent row differ in nothing a caller can see")
    void theDenialIsIndistinguishableFromAMissingRow() throws Exception {
        // Both requests carry the SAME correlation id, so the response header is comparable. Two
        // generated ids would differ for reasons that have nothing to do with disclosure and would
        // make this assertion fail on the plumbing rather than on the contract.
        MvcResult denied = mvc.perform(get(PROBE + "/cross-tenant")
            .header(CorrelationIdFilter.HEADER, "corr-twin")).andReturn();
        MvcResult missing = mvc.perform(get(PROBE + "/missing-row")
            .header(CorrelationIdFilter.HEADER, "corr-twin")).andReturn();

        // FR-2.7. Any field a caller can read and that differs is an existence oracle: the whole
        // reason a denial is a 404 rather than a 403. Only the correlation id and the clock may
        // differ, so the comparison is made over the serialised body and not over parsed fields --
        // a field the security advice adds would show up here even if both sides parsed cleanly.
        assertThat(normalise(denied.getResponse().getContentAsString()))
            .as("the denial body, modulo the request id and the clock")
            .isEqualTo(normalise(missing.getResponse().getContentAsString()));

        assertThat(denied.getResponse().getStatus()).isEqualTo(missing.getResponse().getStatus());
        assertThat(denied.getResponse().getContentType())
            .isEqualTo(missing.getResponse().getContentType());
        assertThat(denied.getResponse().getHeader(CorrelationIdFilter.HEADER))
            .isEqualTo(missing.getResponse().getHeader(CorrelationIdFilter.HEADER));
    }

    @Test
    @DisplayName("the denial body names no tenant, no project and no reason")
    void theDenialBodyDisclosesNothing() throws Exception {
        String body = mvc.perform(get(PROBE + "/cross-tenant"))
            .andReturn().getResponse().getContentAsString();

        JsonNode json = MAPPER.readTree(body);
        assertThat(json.get("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(json.get("message").asText()).isEqualTo("Resource not found");
        assertThat(json.get("details").isEmpty()).isTrue();

        assertThat(body)
            .as("the foreign organization, the foreign row and the internal reason are for the "
                + "security stream, not for the caller")
            .doesNotContain("00000000-0000-0000-0000-0000000000a1")
            .doesNotContain("00000000-0000-0000-0000-0000000000b2")
            .doesNotContain("organization-out-of-scope")
            .doesNotContain("project-out-of-scope")
            .doesNotContain("PolicyDeniedException");
    }

    // ==================================================================
    // the denial is still a security event, which a status code cannot show
    // ==================================================================

    @Test
    @DisplayName("a denial still publishes a security event")
    void theDenialStillPublishesASecurityEvent() throws Exception {
        mvc.perform(get(PROBE + "/cross-tenant")).andExpect(status().isNotFound());

        assertThat(events.published())
            .as("a 404 alone is not what makes a denial a security event; the event is, and widening "
                + "the advice must not cost the record that makes it one")
            .isNotEmpty()
            .allSatisfy(event -> assertThat(event.type())
                .isIn(SecurityEventType.AUTHORIZATION_DENIED, SecurityEventType.ACCESS_DENIED));
    }

    @Test
    @DisplayName("the denial's security event carries the correlation id the caller was given")
    void theSecurityEventCarriesTheCorrelationId() throws Exception {
        mvc.perform(get(PROBE + "/cross-tenant")
                .header(CorrelationIdFilter.HEADER, "corr-denial-scope"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.requestId").value("corr-denial-scope"));

        List<SecurityEvent> published = events.published();
        assertThat(published).isNotEmpty();
        assertThat(published.get(0).correlationId()).isEqualTo("corr-denial-scope");
    }

    @Test
    @DisplayName("a capability denial with no object to leak is still 403")
    void capabilityDenialIsForbidden() throws Exception {
        mvc.perform(get(PROBE + "/capability-denied"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
            .andExpect(jsonPath("$.message").value("Access denied"));
    }

    // ==================================================================
    // what widening must NOT have swallowed
    // ==================================================================

    @Test
    @DisplayName("a validation refusal publishes no security event")
    void aValidationRefusalIsNotASecurityEvent() throws Exception {
        mvc.perform(get(PROBE + "/refused"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(events.published())
            .as("an advice widened to every controller must not start classifying ordinary bad "
                + "requests as denials, or the security stream fills with client mistakes and stops "
                + "meaning anything")
            .isEmpty();
    }

    @Test
    @DisplayName("a missing row publishes no security event either: nobody was denied")
    void aMissingRowIsNotASecurityEvent() throws Exception {
        mvc.perform(get(PROBE + "/missing-row")).andExpect(status().isNotFound());
        assertThat(events.published()).isEmpty();
    }

    @Test
    @DisplayName("an unexpected fault is still 500, so the floor still catches what nothing claims")
    void anUnexpectedFaultIsStillInternalServerError() throws Exception {
        mvc.perform(get(PROBE + "/fault"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @Test
    @DisplayName("the 500 body still does not carry the exception's message")
    void theFaultBodyDisclosesNothing() throws Exception {
        String body = mvc.perform(get(PROBE + "/fault"))
            .andExpect(status().isInternalServerError())
            .andReturn().getResponse().getContentAsString();

        assertThat(body)
            .as("a relation name and a filesystem path in an error body is a disclosure surface")
            .doesNotContain("case_classification_questions_pkey")
            .doesNotContain("/var/lib/postgresql")
            .doesNotContain("IllegalStateException");
    }

    // ==================================================================
    // the scope itself, asserted as a declaration and not only as behaviour
    // ==================================================================

    @Test
    @DisplayName("the advice applies to every controller, not to a package, an annotation or a type")
    void theAdviceIsNotScoped() {
        RestControllerAdvice advice =
            AnnotationUtils.findAnnotation(SecurityExceptionAdvice.class, RestControllerAdvice.class);

        assertThat(advice).as("the advice must still be a @RestControllerAdvice").isNotNull();
        assertThat(advice.basePackages())
            .as("basePackages is the defect: a denial outside it is answered 500")
            .isEmpty();
        assertThat(advice.assignableTypes())
            .as("assignableTypes would scope it just as tightly, and just as silently")
            .isEmpty();
        assertThat(advice.annotations())
            .as("an annotation selector is a third way to be scoped, and the narrowest of the three")
            .isEmpty();
        assertThat(advice.basePackageClasses()).isEmpty();
    }

    @Test
    @DisplayName("the advice still sorts ahead of the floor, which is what makes the 404 reachable")
    void theAdviceIsAheadOfTheFloor() {
        int security = AnnotationUtils.findAnnotation(SecurityExceptionAdvice.class, Order.class).value();
        int floor = AnnotationUtils.findAnnotation(
            securityscope.probe.ProbeAdviceLadder.ProbeFloorAdvice.class, Order.class).value();

        assertThat(security)
            .as("the advice must sort before the catch-all, or the catch-all answers first")
            .isLessThan(floor);
        assertThat(security).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    // ==================================================================
    // helpers
    // ==================================================================

    /** The whole body with the two legitimately varying fields blanked. */
    private String normalise(String body) {
        return body.replaceAll("\"requestId\":\"[^\"]*\"", "\"requestId\":\"<id>\"")
            .replaceAll("\"timestamp\":\"[^\"]*\"", "\"timestamp\":\"<ts>\"");
    }
}
