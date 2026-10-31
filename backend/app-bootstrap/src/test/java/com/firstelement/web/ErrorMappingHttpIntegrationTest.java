package com.firstelement.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstelement.ApiApplication;
import com.firstelement.crm.error.CrmExceptionAdvice;
import com.firstelement.kernel.web.CorrelationIdFilter;
import com.firstelement.security.event.SecurityEvent;
import com.firstelement.security.event.SecurityEventType;
import errorcontract.probe.ErrorMappingProbeConfiguration;
import errorcontract.probe.ErrorMappingProbeConfiguration.RecordingSecurityEventPublisher;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase 18: a request the server refuses must be a 4xx, and a fault must be a 500.
 *
 * <h2>What the defect was</h2>
 *
 * <p>{@code app-bootstrap}'s {@code ErrorHandlingAdvice} handled five exception types, and
 * {@code com.firstelement.crm.error.ValidationFailedException} was not one of them. It reached the
 * {@code @ExceptionHandler(Exception.class)} catch-all and was answered
 * {@code 500 INTERNAL_ERROR}. The same was true of {@code MissingServletRequestParameterException},
 * which {@code GET /api/v1/exports/finance-gate} raises whenever a caller sends two of its three
 * required parameters.
 *
 * <p>Two consequences, and the second is the one that matters. A 500 is logged, alerted on and
 * counted as an availability incident, so every ordinary validation rejection was manufacturing
 * incident signal, and an operator who has learned to ignore it is worse off than one who never was.
 * And with no 4xx or 5xx documented anywhere in the contract, the status code was the only signal a
 * generated client had — and it was the wrong one.
 *
 * <h2>Why these assertions are made over HTTP</h2>
 *
 * <p>Because the defect was never in a handler. It was in which handler Spring MVC picked. That
 * choice happens inside the dispatcher, over the advice beans <em>in order</em>, for the exception
 * that actually escaped the handler method. Calling {@code advice.validation(ex, request)} directly
 * would have asserted the handler and skipped the only part that was broken — and a handler that is
 * registered but shadowed reads exactly like one that is absent. So every case below is a real HTTP
 * request through the assembled application, on a real route, and the assertions are on the response.
 *
 * <h2>Why the filters are off and one is put back</h2>
 *
 * <p>{@code addFilters = false} takes the security chain out of the path, because the probe routes
 * sit outside the API's permission model and this suite is about error mapping, not authorization.
 * {@code CorrelationIdFilter} is then added back explicitly: {@code requestId} is part of the error
 * contract, so a suite asserting error bodies without one would assert a shape the client never
 * receives. Nothing else is weakened — the dispatcher, the advice chain and the message converters
 * all run exactly as they do in production.
 *
 * <h2>Why this is one suite and not nested classes</h2>
 *
 * <p>A JUnit {@code @Nested} class is reported under its {@code @DisplayName}, not its class name,
 * so seven nested groups would be seven manifest entries for the orchestrator to register and keep
 * in step. One flat suite is one entry, and the grouping is carried by the section banners.
 */
@SpringBootTest(classes = {ApiApplication.class, ErrorMappingProbeConfiguration.class},
    properties = {
        "firstelement.environment=TEST",
        "management.health.db.enabled=false",
        "management.health.redis.enabled=false",
        "spring.flyway.enabled=false"
    })
@AutoConfigureMockMvc(addFilters = false)
class ErrorMappingHttpIntegrationTest {

    private static final String PROBE = "/error-mapping-probe";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private RecordingSecurityEventPublisher events;

    /** Every {@code ERROR} the floor logged while a request was in flight. */
    private ListAppender<ILoggingEvent> floorLog;

    private Logger floorLogger;

    @BeforeEach
    void setUp() {
        events.reset();
        floorLog = new ListAppender<>();
        floorLog.start();
        floorLogger = (Logger) LoggerFactory.getLogger(ErrorHandlingAdvice.class);
        floorLogger.addAppender(floorLog);
    }

    @AfterEach
    void tearDown() {
        floorLogger.detachAppender(floorLog);
        floorLog.stop();
    }

    // ==================================================================
    // the defect: the CRM refusal family
    // ==================================================================

    @Test
    @DisplayName("a ValidationFailedException is 400 VALIDATION_FAILED with the full error body")
    void validationFailureIsBadRequest() throws Exception {
        mvc.perform(get(PROBE + "/validation"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.message").value("That employee is not a member of this project"))
            .andExpect(jsonPath("$.requestId").isNotEmpty())
            .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    @DisplayName("a refusal keeps the caller-facing code it was raised with")
    void refusalKeepsItsSpecificCode() throws Exception {
        mvc.perform(get(PROBE + "/validation-with-code"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_CURSOR"))
            .andExpect(jsonPath("$.details.field").value("cursor"));
    }

    @Test
    @DisplayName("the refusal body carries the correlation id the caller sent")
    void refusalCarriesTheCorrelationId() throws Exception {
        mvc.perform(get(PROBE + "/validation").header(CorrelationIdFilter.HEADER, "corr-refusal-1"))
            .andExpect(status().isBadRequest())
            .andExpect(header().string(CorrelationIdFilter.HEADER, "corr-refusal-1"))
            .andExpect(jsonPath("$.requestId").value("corr-refusal-1"));
    }

    @Test
    @DisplayName("a refusal is not logged as an incident; a fault is")
    void refusalsDoNotGenerateIncidentSignal() throws Exception {
        mvc.perform(get(PROBE + "/validation"))
            .andExpect(status().isBadRequest())
            .andExpect(status().is4xxClientError());
        assertThat(errorLevelLogs())
            .as("a 400 that logs at ERROR is a false page: this is the operational half of the defect")
            .isEmpty();

        mvc.perform(get(PROBE + "/unexpected"))
            .andExpect(status().isInternalServerError());
        assertThat(errorLevelLogs())
            .as("and the 500 still is one, or the fix would have silenced the real signal")
            .isNotEmpty();
    }

    // ==================================================================
    // the rest of the family, and the classification each one gets
    // ==================================================================

    @Test
    @DisplayName("a ResourceNotFoundException is 404 with a non-disclosing body")
    void missingRowIsNotFound() throws Exception {
        mvc.perform(get(PROBE + "/not-found"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.message").value("Resource not found"))
            .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    @DisplayName("the domain may name the resource it is entitled to name")
    void domainResourceLabelSurvives() throws Exception {
        mvc.perform(get(PROBE + "/missing-conversation"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.message").value("Conversation not found"));
    }

    @Test
    @DisplayName("a VersionConflictException is 409 and carries the version to recover from")
    void lostUpdateIsConflict() throws Exception {
        mvc.perform(get(PROBE + "/conflict"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
            .andExpect(jsonPath("$.details.currentVersion").value(8))
            .andExpect(jsonPath("$.details.expectedVersion").value(7))
            .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    @DisplayName("an unmapped path in the real application is 404 with the same code")
    void unmappedPathIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/does-not-exist"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    @DisplayName("a wrong method on a real route is 405, naming the method that was refused")
    void wrongMethodIsNotAllowed() throws Exception {
        mvc.perform(delete(PROBE + "/json-only"))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
            .andExpect(jsonPath("$.message").value("DELETE is not allowed on this resource"));
    }

    // ==================================================================
    // a request the dispatcher itself refuses: 4xx, not 500
    // ==================================================================

    @Test
    @DisplayName("a missing required query parameter is 400 and names the parameter")
    void missingRequiredParameterIsBadRequest() throws Exception {
        mvc.perform(get(PROBE + "/finance-gate")
                .param("from", "2026-01-01").param("to", "2026-01-31"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.message").value("projectId is required"))
            .andExpect(jsonPath("$.details.parameter").value("projectId"))
            .andExpect(jsonPath("$.details.expectedType").value("UUID"))
            .andExpect(jsonPath("$.requestId").isNotEmpty())
            .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    @DisplayName("sending all three required parameters answers 200, so nothing else was broken")
    void theRouteStillWorksWhenTheCallerIsCorrect() throws Exception {
        mvc.perform(get(PROBE + "/finance-gate")
                .param("projectId", "00000000-0000-0000-0000-0000000000c3")
                .param("from", "2026-01-01").param("to", "2026-01-31"))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a missing required header is 400 and names the header")
    void missingRequiredHeaderIsBadRequest() throws Exception {
        mvc.perform(get(PROBE + "/conditional"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.message").value("The If-Match header is required"))
            .andExpect(jsonPath("$.details.header").value("If-Match"));
    }

    @Test
    @DisplayName("an unparseable body is 400 and says nothing about the server's DTO shape")
    void unreadableBodyIsBadRequest() throws Exception {
        String body = mvc.perform(post(PROBE + "/echo")
                .contentType(MediaType.APPLICATION_JSON).content("{\"a\": "))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST_BODY"))
            .andReturn().getResponse().getContentAsString();

        assertThat(body)
            .as("a Jackson failure names this server's classes and fields; echoing it turns the "
                + "endpoint into a probe for every request body it accepts")
            .doesNotContain("ProbeRefusal")
            .doesNotContain("LinkedHashMap")
            .doesNotContain("com.fasterxml");
    }

    @Test
    @DisplayName("a Content-Type the route does not accept is 415, with the accepted set")
    void unsupportedContentTypeIsUnsupportedMediaType() throws Exception {
        mvc.perform(post(PROBE + "/echo")
                .contentType(MediaType.TEXT_PLAIN).content("plain text"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
            .andExpect(jsonPath("$.details.supported").isArray());
    }

    @Test
    @DisplayName("an Accept the route cannot produce is 406 rather than 500")
    void unacceptableAcceptIsNotAcceptable() throws Exception {
        // No body is asserted, and there cannot be one: a caller that demands a representation this
        // API does not produce also refuses the JSON error body, so the status is the whole of the
        // answer here. That is not a defect in the mapping — the mapping is what stops it being a
        // 500 and a false page for a caller who typed the wrong Accept header.
        mvc.perform(get(PROBE + "/json-only").accept(MediaType.APPLICATION_XML))
            .andExpect(status().isNotAcceptable());

        // And the same route answers normally for an Accept it can satisfy, so the 406 is the
        // negotiation and not a broken handler.
        mvc.perform(get(PROBE + "/json-only").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.json").value(true));
    }

    @Test
    @DisplayName("an unbindable closed vocabulary names what it accepts, so the client can fix itself")
    void closedVocabularyNamesWhatItAccepts() throws Exception {
        String body = mvc.perform(get(PROBE + "/closed-vocabulary").param("status", "everything"))
            .andExpect(status().is4xxClientError())
            .andReturn().getResponse().getContentAsString();

        // ADR 0015's requirement, generalized from the inbox filter to any closed vocabulary: a
        // refused value must come back with the list the caller can choose from.
        assertThat(body).contains("OPEN").contains("CLOSED").contains("must be one of");
    }

    @Test
    @DisplayName("an unbindable non-enum parameter is a 4xx that does not echo the rejected value back")
    void openVocabularyDoesNotEchoTheRejectedValue() throws Exception {
        mvc.perform(get(PROBE + "/project-summary").param("projectId", "not-a-uuid"))
            .andExpect(status().is4xxClientError())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.message").value("projectId is not a valid value"))
            .andExpect(jsonPath("$.details.field").value("projectId"))
            .andExpect(jsonPath("$.details.accepted").doesNotExist());
    }

    // ==================================================================
    // the half that must not regress
    // ==================================================================

    @Test
    @DisplayName("an unexpected RuntimeException is still 500 INTERNAL_ERROR")
    void unexpectedIsInternalServerError() throws Exception {
        mvc.perform(get(PROBE + "/unexpected"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.message").value("Unexpected server error"))
            .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    @DisplayName("the 500 body does not carry the exception's message")
    void internalFaultDoesNotDiscloseItsMessage() throws Exception {
        String body = mvc.perform(get(PROBE + "/unexpected"))
            .andExpect(status().isInternalServerError())
            .andReturn().getResponse().getContentAsString();

        assertThat(body)
            .as("a relation name and a filesystem path in an error body is a disclosure surface")
            .doesNotContain("case_classification_questions_pkey")
            .doesNotContain("/var/lib/postgresql")
            .doesNotContain("IllegalStateException");
    }

    @Test
    @DisplayName("a refusal handler was not widened into a catch-all")
    void noRefusalHandlerAnswersFiveHundred() throws Exception {
        // A catch-all that answered 400 would convert a real outage into a silent bad request,
        // which is worse than the defect this phase fixed: nothing would page, and the operator
        // would believe the server was healthy. The floor stays 500 and only the floor answers 500.
        assertThat(statusOf(PROBE + "/unexpected")).isEqualTo(500);
        assertThat(statusOf(PROBE + "/validation")).isEqualTo(400);
        assertThat(statusOf(PROBE + "/not-found")).isEqualTo(404);
        assertThat(statusOf(PROBE + "/conflict")).isEqualTo(409);
    }

    // ==================================================================
    // the security negatives
    // ==================================================================

    @Test
    @DisplayName("a cross-tenant read is 404, not a 400")
    void crossTenantReadIsNotFound() throws Exception {
        mvc.perform(get(PROBE + "/cross-tenant"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.message").value("Resource not found"))
            .andExpect(jsonPath("$.details").isEmpty());
    }

    @Test
    @DisplayName("the denial and a nonexistent row differ in nothing a caller can see")
    void crossTenantReadIsIndistinguishableFromAMissingRow() throws Exception {
        String denied = mvc.perform(get(PROBE + "/cross-tenant"))
            .andReturn().getResponse().getContentAsString();
        String missing = mvc.perform(get(PROBE + "/not-found"))
            .andReturn().getResponse().getContentAsString();

        // FR-2.7. If these two bodies differ in any field a caller can see, the difference is an
        // existence oracle. Only the correlation id and the timestamp may differ.
        JsonNode a = MAPPER.readTree(denied);
        JsonNode b = MAPPER.readTree(missing);
        assertThat(a.get("code")).as("code").isEqualTo(b.get("code"));
        assertThat(a.get("message")).as("message").isEqualTo(b.get("message"));
        assertThat(a.get("details")).as("details").isEqualTo(b.get("details"));
        assertThat(a.get("retryable")).as("retryable").isEqualTo(b.get("retryable"));
        assertThat(denied.replaceAll("\"requestId\":\"[^\"]*\"", "R")
                .replaceAll("\"timestamp\":\"[^\"]*\"", "T"))
            .as("the whole body, modulo the id and the clock")
            .isEqualTo(missing.replaceAll("\"requestId\":\"[^\"]*\"", "R")
                .replaceAll("\"timestamp\":\"[^\"]*\"", "T"));
    }

    @Test
    @DisplayName("a denial still publishes a security event")
    void crossTenantReadStillPublishesASecurityEvent() throws Exception {
        mvc.perform(get(PROBE + "/cross-tenant")).andExpect(status().isNotFound());

        assertThat(events.published())
            .as("a 404 alone is not what makes a denial a security event; the event is, and a handler "
                + "that turned it into a 400 would quietly stop it being one")
            .isNotEmpty()
            .allSatisfy(event -> assertThat(event.type())
                .isIn(SecurityEventType.AUTHORIZATION_DENIED, SecurityEventType.ACCESS_DENIED));
    }

    @Test
    @DisplayName("the denial's security event carries the correlation id the caller was given")
    void theSecurityEventCarriesTheCorrelationId() throws Exception {
        mvc.perform(get(PROBE + "/cross-tenant").header(CorrelationIdFilter.HEADER, "corr-denial-1"))
            .andExpect(status().isNotFound());

        List<SecurityEvent> published = events.published();
        assertThat(published).isNotEmpty();
        assertThat(published.get(0).correlationId()).isEqualTo("corr-denial-1");
    }

    @Test
    @DisplayName("a capability denial with no object to leak is still 403")
    void capabilityDenialIsForbidden() throws Exception {
        mvc.perform(get(PROBE + "/capability-denied"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("a validation refusal publishes no security event, so it is not a denial")
    void aValidationRefusalIsNotASecurityEvent() throws Exception {
        mvc.perform(get(PROBE + "/validation")).andExpect(status().isBadRequest());
        mvc.perform(get(PROBE + "/conflict")).andExpect(status().isConflict());

        assertThat(events.published())
            .as("if a refusal published a denial event, the security stream would fill with ordinary "
                + "client mistakes and stop meaning anything")
            .isEmpty();
    }

    // ==================================================================
    // nested raises: a rule enforced below the service keeps its status
    // ==================================================================

    @Test
    @DisplayName("a nested ValidationFailedException is 400, not caught and re-mapped to a 500")
    void nestedValidationFailureStaysBadRequest() throws Exception {
        mvc.perform(get(PROBE + "/nested/validation"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.message").value("A body is required"))
            .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    @DisplayName("a nested ResourceNotFoundException is 404")
    void nestedMissingRowStaysNotFound() throws Exception {
        mvc.perform(get(PROBE + "/nested/not-found"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("a nested internal fault is still 500")
    void nestedFaultStaysInternalServerError() throws Exception {
        mvc.perform(get(PROBE + "/nested/unexpected"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    // ==================================================================
    // the ordering, asserted as behaviour and then as declaration
    // ==================================================================

    @Test
    @DisplayName("the domain advice is not shadowed by the Exception handler")
    void theDomainAdviceWinsOverTheCatchAll() throws Exception {
        // Both advices are applicable to this controller. Were `ErrorHandlingAdvice`, which holds
        // `@ExceptionHandler(Exception.class)`, consulted first, every one of these would be a 500
        // with code INTERNAL_ERROR. Each carries its own code, so the specific advice won — which is
        // what `@Order` now guarantees instead of leaving to the order component scan happened to
        // register the two beans in.
        mvc.perform(get(PROBE + "/validation"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get(PROBE + "/not-found"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get(PROBE + "/conflict"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test
    @DisplayName("the domain advice is declared ahead of the catch-all")
    void theDeclaredOrdersPutTheFloorLast() {
        int domain = AnnotationUtils.findAnnotation(CrmExceptionAdvice.class, Order.class).value();
        int floor = AnnotationUtils.findAnnotation(ErrorHandlingAdvice.class, Order.class).value();

        assertThat(domain)
            .as("CrmExceptionAdvice must sort before ErrorHandlingAdvice, or every CRM 400/404/409 "
                + "depends on component-scan registration order")
            .isLessThan(floor);
        assertThat(floor)
            .as("the advice holding @ExceptionHandler(Exception.class) must be the last resort")
            .isEqualTo(Ordered.LOWEST_PRECEDENCE);
    }

    @Test
    @DisplayName("the security advice is ahead of both, so a denial is never a validation failure")
    void theSecurityAdviceWinsOverEverything() throws Exception {
        mvc.perform(get(PROBE + "/cross-tenant"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.message").value("Resource not found"));
        assertThat(events.published())
            .as("and the denial is still recorded, which is the half a status code cannot show")
            .isNotEmpty();
    }

    // ==================================================================
    // helpers
    // ==================================================================

    private int statusOf(String path) throws Exception {
        return mvc.perform(get(path)).andReturn().getResponse().getStatus();
    }

    private List<ILoggingEvent> errorLevelLogs() {
        return floorLog.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
    }
}