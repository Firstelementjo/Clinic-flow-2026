package com.firstelement.errorprobe;

import com.firstelement.crm.error.ResourceNotFoundException;
import com.firstelement.crm.error.ValidationFailedException;
import com.firstelement.crm.error.VersionConflictException;
import com.firstelement.security.error.PolicyDeniedException;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Fault injection for the error-mapping tests: one route per exception the dispatcher can raise on
 * behalf of a caller.
 *
 * <h2>Why this exists instead of calling the advice directly</h2>
 *
 * <p>The defect this suite covers is not "a handler returns the wrong status". It is "the dispatcher
 * picked a different handler than the author of the code expected". {@code @ExceptionHandler}
 * resolution happens inside Spring MVC, over the advice beans in order, for the exception that
 * actually escaped the handler method. Calling {@code advice.validation(ex, request)} directly would
 * assert the handler and skip the only part that was wrong -- and a handler that is registered but
 * shadowed reads exactly like one that is absent.
 *
 * <h2>Why the package is under {@code com.firstelement}</h2>
 *
 * <p>Because {@code SecurityExceptionAdvice} declares {@code basePackages = "com.firstelement"}, and
 * a denial raised from a controller outside that namespace is answered by the catch-all -- as
 * {@code 500 INTERNAL_ERROR}. That was found the honest way: the first version of this probe lived
 * outside the namespace, and every cross-tenant negative came back {@code INTERNAL_ERROR} while the
 * CRM ones were correct. It is a latent trap rather than a live defect, since every production
 * controller is under {@code com.firstelement}, but a probe that dodged it would assert a security
 * negative in a configuration the product never runs.
 *
 * <p>The cost of being inside the namespace is that {@code ApiApplication}'s {@code @ComponentScan}
 * finds this class in every context in this module. That is why it is {@link Hidden}: springdoc omits
 * it from the published document, so {@code OpenApiDocumentExportTest} still exports the operations
 * that exist in production. A test fixture that appeared in the contract would be a worse defect
 * than the one this suite fixes.
 *
 * <h2>Why the class is not named {@code *Controller}</h2>
 *
 * <p>Two gates count routes, and neither of them should count these. springdoc publishes what it
 * finds in a running context, which {@link Hidden} settles. {@code check-openapi-contract.ps1}
 * extracts every {@code *Controller.java} under {@code backend} -- test sources included, so that a
 * test-only controller cannot hide a product route from review -- and compares that inventory with
 * the committed one. The honest options there were to add seventeen fixture routes to
 * {@code tests/contracts/openapi-paths.json}, which would put routes that exist in no deployment into
 * the contract the Angular client is generated from, or to name the fixture for what it is. This is
 * the second, and the check was run to prove it rather than assumed:
 * {@code openapi-contract VERDICT: PASS}, 142 committed routes, 142 in code, none added.
 *
 * <h2>What each route is for</h2>
 *
 * <p>Each handler method is annotated with the exception it raises, so it reads as the ordinary
 * thing it is: a handler that throws a refusal, a conflict, a denial or a fault.
 */
@Hidden
@Controller
@RequestMapping("/error-mapping-probe")
@ResponseBody
public class ErrorMappingProbeRoutes {

    private final ProbeRefusalService service;

    public ErrorMappingProbeRoutes(ProbeRefusalService service) {
        this.service = service;
    }

    // ------------------------------------------------------------------
    // the CRM domain family -- the defect itself
    // ------------------------------------------------------------------

    /** The exception {@code CaseService.validateResolution} and {@code ConversationService} raise. */
    @GetMapping("/validation")
    public Map<String, Object> validation() {
        throw new ValidationFailedException("That employee is not a member of this project");
    }

    /** The same refusal carrying a caller-facing code instead of the default. */
    @GetMapping("/validation-with-code")
    public Map<String, Object> validationWithCode() {
        throw ValidationFailedException.code("INVALID_CURSOR",
            "cursor is not a cursor this server issued", Map.of("field", "cursor"));
    }

    /**
     * A row that does not exist, rendered with the neutral label.
     *
     * <p>The label matters: this is the twin of {@link #crossTenant()}, and the pair is what proves
     * the denial discloses nothing. A domain exception entitled to name its own resource is
     * {@link #missingConversation()}, and it is deliberately a different body.
     */
    @GetMapping("/not-found")
    public Map<String, Object> notFound() {
        throw new ResourceNotFoundException("Resource");
    }

    /** The same refusal with the resource's own label, which the domain is entitled to name. */
    @GetMapping("/missing-conversation")
    public Map<String, Object> missingConversation() {
        throw new ResourceNotFoundException("Conversation");
    }

    /** A lost optimistic-concurrency race. */
    @GetMapping("/conflict")
    public Map<String, Object> conflict() {
        throw VersionConflictException.modeChanged("Conversation", 7, 8);
    }

    // ------------------------------------------------------------------
    // the negatives that must NOT become 4xx-by-accident
    // ------------------------------------------------------------------

    /**
     * A cross-tenant read. Answers the same non-disclosing {@code 404} as {@link #notFound()} and
     * publishes a security event.
     */
    @GetMapping("/cross-tenant")
    public Map<String, Object> crossTenant() {
        throw PolicyDeniedException.tenantOutOfScope("organization-out-of-scope",
            UUID.fromString("00000000-0000-0000-0000-0000000000a1"),
            UUID.fromString("00000000-0000-0000-0000-0000000000b2"));
    }

    /** A capability denial with no object to leak: {@code 403}, not {@code 404}. */
    @GetMapping("/capability-denied")
    public Map<String, Object> capabilityDenied() {
        throw new AccessDeniedException("Access denied");
    }

    // ------------------------------------------------------------------
    // the floor
    // ------------------------------------------------------------------

    /**
     * A genuine internal fault, with a message that would disclose an internal detail if it were ever
     * rendered. It must not be.
     */
    @GetMapping("/unexpected")
    public Map<String, Object> unexpected() {
        throw new IllegalStateException(
            "relation \"case_classification_questions_pkey\" already exists at /var/lib/postgresql");
    }

    // ------------------------------------------------------------------
    // binding refusals raised by the dispatcher itself
    // ------------------------------------------------------------------

    /**
     * The shape {@code GET /api/v1/exports/finance-gate} declares: three required parameters, of
     * which a client that sends two gets a 500 today.
     */
    @GetMapping("/finance-gate")
    public Map<String, Object> financeGate(@RequestParam(name = "projectId") UUID projectId,
                                           @RequestParam(name = "from") String from,
                                           @RequestParam(name = "to") String to) {
        return Map.of("projectId", projectId, "from", from, "to", to);
    }

    /** A query parameter whose value does not convert to the declared type. */
    @GetMapping("/project-summary")
    public Map<String, Object> projectSummary(@RequestParam(name = "projectId") UUID projectId) {
        return Map.of("projectId", projectId);
    }

    /** A closed vocabulary: the refusal has to name the accepted values or it is not actionable. */
    @GetMapping("/closed-vocabulary")
    public Map<String, Object> closedVocabulary(@RequestParam(name = "status") ProbeStatus status) {
        return Map.of("status", status.name());
    }

    /** A required request header, refused the same way a required parameter is. */
    @GetMapping("/conditional")
    public Map<String, Object> conditional(@RequestHeader(name = "If-Match") String ifMatch) {
        return Map.of("ifMatch", ifMatch);
    }

    @PostMapping("/echo")
    public Map<String, Object> echo(@RequestBody Map<String, Object> body) {
        return body;
    }

    /** Declares what it produces, so a caller can ask for something else and be told so. */
    @GetMapping(value = "/json-only", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> jsonOnly() {
        return Map.of("json", true);
    }

    // ------------------------------------------------------------------
    // the same refusals, raised three layers down
    // ------------------------------------------------------------------

    @GetMapping("/nested/validation")
    public Map<String, Object> nestedValidation() {
        return service.recordNote();
    }

    @GetMapping("/nested/not-found")
    public Map<String, Object> nestedNotFound() {
        return service.loadConversation();
    }

    @GetMapping("/nested/unexpected")
    public Map<String, Object> nestedUnexpected() {
        return service.rebuildIndex();
    }

    /** A closed vocabulary with wire values that are not the Java constant names, as the inbox has. */
    public enum ProbeStatus {
        OPEN,
        CLOSED
    }
}