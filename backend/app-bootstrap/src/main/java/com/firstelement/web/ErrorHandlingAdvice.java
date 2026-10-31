package com.firstelement.web;

import com.firstelement.kernel.error.ErrorResponse;
import com.firstelement.kernel.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * The application's error floor: stable code/message/details/requestId/retryable body,
 * correlation id propagated from MDC, and no cross-tenant existence leaks.
 *
 * <h2>This advice is the floor, and the order is explicit because of that</h2>
 *
 * <p>The last handler here catches {@code Exception}. Spring resolves an exception against the
 * advice beans <em>in order</em> and stops at the first advice that has a handler for it, so an
 * advice carrying a {@code @ExceptionHandler(Exception.class)} must be <b>last</b>. That used to be
 * true only by accident: this class and {@code CrmExceptionAdvice} both carried no {@code @Order},
 * both defaulted to {@link Ordered#LOWEST_PRECEDENCE}, and the winner was whichever component scan
 * happened to register first. On the assembled application that was CRM first, so every CRM
 * 400/404/409 was correct -- and a change to the module list in {@code app-bootstrap}'s
 * {@code build.gradle.kts} would have silently turned every one of them into a 500. An implicit
 * ordering between a specific handler and a catch-all is exactly the failure the phase-18 report
 * calls out: a handler that is registered but shadowed looks identical to one that is absent.
 *
 * <p>So the order is now stated rather than discovered:
 * {@link Ordered#HIGHEST_PRECEDENCE} for {@code SecurityExceptionAdvice} (denials and
 * authentication), {@code LOWEST_PRECEDENCE - 100} for {@code CrmExceptionAdvice} (the CRM domain
 * family), and {@link Ordered#LOWEST_PRECEDENCE} for this class. The catch-all stays a catch-all:
 * it still answers 500 for the unexpected, because a catch-all that answered 400 would convert a
 * real outage into a silent bad request, which is strictly worse than a mislabelled refusal.
 *
 * <h2>Why the catch-all is logged at error and the refusals are not</h2>
 *
 * <p>A 500 is paged. A refusal is not. A system that answers 500 for every validation rejection
 * therefore manufactures incident signal out of ordinary client mistakes, and an operator who has
 * learned to ignore it is worse off than one who has never been trained to. That is why the
 * refusal handlers below exist at all, and why none of them logs at {@code error}.
 *
 * <h2>The enumeration</h2>
 *
 * <p>Every exception type that can reach a handler method was classified. Handled here as a
 * <b>refusal</b> (4xx): {@code MethodArgumentNotValidException},
 * {@code MissingServletRequestParameterException}, {@code MissingRequestHeaderException},
 * {@code HttpMessageNotReadableException}, {@code HttpMediaTypeNotSupportedException},
 * {@code HttpMediaTypeNotAcceptableException}, {@code IllegalArgumentException}. Handled here as
 * <b>not found / not allowed</b> (404/405): {@code NoResourceFoundException},
 * {@code HttpRequestMethodNotSupportedException}. Handled by a module that owns the type:
 * {@code CrmExceptionAdvice} for {@code CrmDomainException} and its subclasses (400/404/409),
 * {@code SecurityExceptionAdvice} for {@code PolicyDeniedException}, {@code AccessDeniedException},
 * {@code AuthenticationException}, {@code CsrfException} and the login failures (401/403/404/429),
 * {@code ExportExceptionHandler} for the export catalogue and job families.
 *
 * <p>Deliberately <b>not</b> handled, and therefore a 500 through the catch-all, because each is a
 * fault in this codebase rather than in the request: {@code IllegalStateException},
 * {@code UnsupportedOperationException}, {@code MissingPathVariableException} and
 * {@code ConversionNotSupportedException} (a route that declares a variable it does not bind, or a
 * property it cannot convert, is a code defect); {@code org.springframework.dao.DataAccessException}
 * and its subclasses outside the export controller (a broken query or constraint); and every
 * connector, worker and AI exception that no request thread can reach
 * ({@code PermanentFailureException}, {@code ProviderSendFailedException},
 * {@code CredentialUnavailableException}, {@code UnsupportedCapabilityException},
 * {@code AiRefusedException}, {@code AiRedactionFailedException},
 * {@code FinancePeriodClosedException}). Those are enumerated with their classifications in
 * {@code docs/migration/phases/phase-18-error-mapping.md}; the ones that belong to a module this
 * phase does not own are reported there rather than silently mapped here, because an exception's
 * meaning is the owning module's to decide.
 *
 * <h2>What no body may carry</h2>
 *
 * <p>A message written for display, never a tenant, a project, a role, a permission, or another
 * row's field value. The framework refusals below name the <em>parameter</em> or the <em>accepted
 * media type</em>, because that is the caller's own input and the accepted set is the only thing
 * that lets them recover. They never name a rejected value back for a non-enum parameter, and never
 * echo a Jackson failure, whose message describes the server's DTO shape rather than the request.
 */
@Order(Ordered.LOWEST_PRECEDENCE)
@RestControllerAdvice
public class ErrorHandlingAdvice {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandlingAdvice.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, Object> details = new HashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            details.put(fe.getField(), fe.getDefaultMessage());
        }
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", details, request);
    }

    /**
     * A required query parameter the caller did not send.
     *
     * <p>{@code 400 VALIDATION_FAILED}, the same code every other binding refusal uses, so a
     * generated client has one branch and not four. The message names the parameter and the
     * details carry its declared type: both are the caller's own contract, published by this
     * server's own route declaration, and both are what turns a refused request into a fixed one.
     *
     * <p>This is the exception {@code GET /api/v1/exports/finance-gate} raises when
     * {@code projectId}, {@code from} or {@code to} is absent, and until now it fell through to the
     * catch-all: a client that sent two of three parameters was told the server had broken.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> missingParameter(MissingServletRequestParameterException ex,
                                                          HttpServletRequest request) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("parameter", ex.getParameterName());
        if (ex.getParameterType() != null && !ex.getParameterType().isBlank()) {
            details.put("expectedType", ex.getParameterType());
        }
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
            ex.getParameterName() + " is required", details, request, Boolean.FALSE);
    }

    /**
     * A required request header the caller did not send. The same refusal as a missing parameter,
     * reached through a different binding: an {@code If-Match} the route demands, for instance.
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> missingHeader(MissingRequestHeaderException ex,
                                                       HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
            "The " + ex.getHeaderName() + " header is required",
            Map.of("header", ex.getHeaderName()), request, Boolean.FALSE);
    }

    /**
     * A body the converter could not read: truncated JSON, a wrong scalar for a field, or a
     * trailing token.
     *
     * <p>Unambiguously the caller's. The Jackson cause names a class, a field and sometimes an
     * offset; that describes this server's DTOs, not the caller's mistake, so it is logged at
     * {@code debug} and never returned. Echoing it would make the endpoint a probe for the shape of
     * every request body it accepts.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> unreadableBody(HttpMessageNotReadableException ex,
                                                        HttpServletRequest request) {
        log.debug("A request body could not be read on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST_BODY",
            "The request body could not be parsed as JSON", Map.of(), request, Boolean.FALSE);
    }

    /**
     * A {@code Content-Type} this route does not accept. The supported set is returned because it
     * is the only way the caller can discover what to send, and it is derived from this server's own
     * route declaration.
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> unsupportedMediaType(HttpMediaTypeNotSupportedException ex,
                                                              HttpServletRequest request) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
            "This endpoint does not accept that Content-Type",
            Map.of("supported", mediaTypes(ex.getSupportedMediaTypes())), request, Boolean.FALSE);
    }

    /**
     * An {@code Accept} header this route cannot satisfy. Same reasoning as above: the produced set
     * is the caller's only route to a usable request.
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ErrorResponse> notAcceptable(HttpMediaTypeNotAcceptableException ex,
                                                       HttpServletRequest request) {
        return build(HttpStatus.NOT_ACCEPTABLE, "NOT_ACCEPTABLE",
            "This endpoint cannot produce any of the requested media types",
            Map.of("supported", mediaTypes(ex.getSupportedMediaTypes())), request, Boolean.FALSE);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(NoResourceFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found", Map.of(), request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> methodNotSupported(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", ex.getMethod() + " is not allowed on this resource", Map.of(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> badRequest(IllegalArgumentException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage(), Map.of(), request);
    }

    /**
     * The floor itself. Anything not claimed by a more specific handler is an internal fault until
     * proven otherwise, and is reported and logged as one.
     *
     * <p>The body never carries the exception's message. A {@code NullPointerException}'s message,
     * a JDBC constraint name, a file path or a fragment of a customer's record would each turn this
     * endpoint into a disclosure surface, and the operator has the stack trace in the log line
     * above -- keyed by the same correlation id the caller was given.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unhandled(Exception ex, HttpServletRequest request) {
        log.error("Unhandled error on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected server error", Map.of(), request);
    }

    private static List<String> mediaTypes(List<org.springframework.http.MediaType> types) {
        return types == null ? List.of() : types.stream().map(Object::toString).toList();
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String code, String message,
                                                Map<String, Object> details, HttpServletRequest request) {
        return build(status, code, message, details, request, null);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String code, String message,
                                                Map<String, Object> details, HttpServletRequest request,
                                                Boolean retryable) {
        Object cid = request.getAttribute(CorrelationIdFilter.MDC_KEY);
        return ResponseEntity.status(status)
            .body(new ErrorResponse(code, message, details, cid == null ? null : cid.toString(),
                java.time.Instant.now(), retryable));
    }
}