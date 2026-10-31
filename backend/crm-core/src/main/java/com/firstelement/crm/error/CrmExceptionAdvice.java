package com.firstelement.crm.error;

import com.firstelement.kernel.error.ErrorResponse;
import com.firstelement.kernel.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Renders {@link CrmDomainException} into the stable Phase-1 {@link ErrorResponse} body.
 *
 * <h2>Scope is the whole application, because the exception escapes the whole application</h2>
 *
 * <p>This advice used to declare {@code basePackages = {"com.firstelement.crm",
 * "com.firstelement.inbox"}}, which read like good hygiene and was in fact a trap.
 * {@link ValidationFailedException} is the CRM domain's <em>shared</em> refusal type: it is thrown
 * by {@code CaseService}, by {@code NoteService}, by {@code CustomerService} and also by
 * {@code :backend:workflows-sla-routing}'s {@code WorkflowDefinitionService},
 * {@code RoutingService}, {@code EscalationService}, {@code SlaClockService},
 * {@code HumanTakeoverService} and {@code AiSendService}. A package-scoped advice answers only for
 * controllers inside its own packages, so the moment one of those services was reached from a
 * controller in {@code com.firstelement.admin}, {@code com.firstelement.finance},
 * {@code com.firstelement.exports} or {@code com.firstelement.operations}, the refusal fell through
 * to {@code app-bootstrap}'s catch-all and was reported as {@code 500 INTERNAL_ERROR}.
 *
 * <p>That is the defect Phase 11 recorded as Hook 17 and Hook 18, and it was never a property of
 * one route: it was a property of the advice's scope. The fix is to own the family once, globally,
 * from the module that declares the type. {@code app-bootstrap}'s {@code ErrorHandlingAdvice} then
 * remains the floor -- the {@code Exception} catch-all -- and there is exactly one handler per
 * exception type in the whole application, so no status depends on bean ordering.
 *
 * <h2>Why it must precede the catch-all</h2>
 *
 * <p>{@code ErrorHandlingAdvice} holds {@code @ExceptionHandler(Exception.class)}, and Spring stops
 * at the first advice bean that has a handler for the thrown type. Both advices previously
 * declared no {@code @Order}, so they tied at {@link Ordered#LOWEST_PRECEDENCE} and the winner was
 * decided by component-scan registration order -- which happened to favour this one only because
 * {@code :backend:crm-core} is listed before {@code :backend:app-bootstrap} in the API module's
 * dependency block. A handler that is registered but shadowed is worse than an absent one, because
 * it reads as fixed. The order is therefore explicit:
 * {@link Ordered#HIGHEST_PRECEDENCE} for {@code SecurityExceptionAdvice},
 * {@link #ORDER} here, {@link Ordered#LOWEST_PRECEDENCE} for the catch-all.
 *
 * <h2>What this advice deliberately does not handle</h2>
 *
 * <ul>
 *   <li>{@code PolicyDeniedException}, and therefore {@code AccessDeniedException} and every other
 *       Spring Security authorization failure. Those belong to {@code SecurityExceptionAdvice} in
 *       {@code :backend:tenant-security}, which renders a denial as a non-disclosing {@code 404} and
 *       publishes a security event. A policy denial is not a client error: it is the one refusal in
 *       the system that must keep looking like a security event, and a broad handler here would turn
 *       it into a {@code 400} and stop it being one. {@code PolicyDeniedException} extends
 *       {@code AccessDeniedException}, not {@code CrmDomainException}, so no handler in this class
 *       can reach it -- and {@code ErrorMappingHttpIntegrationTest} asserts the rendered status
 *       rather than trusting the type hierarchy.</li>
 *   <li>{@code IllegalArgumentException}, {@code IllegalStateException} and the data-access
 *       exceptions. They are ambiguous by construction -- 299 call sites raise an
 *       {@code IllegalArgumentException} and most of them are internal misuse, not a request the
 *       server refused -- and the application advice owns them for exactly that reason.</li>
 * </ul>
 *
 * <h2>Non-disclosure</h2>
 *
 * <p>The body carries the domain code, a message written for display, and the collision or
 * field details the caller needs to recover. It never carries a tenant, a role, a permission,
 * or a row that the scope check rejected.
 */
@Order(CrmExceptionAdvice.ORDER)
@RestControllerAdvice
public class CrmExceptionAdvice {

    /**
     * Ahead of the application's catch-all, behind the security advice.
     *
     * <p>A named constant rather than a bare arithmetic expression at the annotation site, because
     * the relation it encodes -- "after {@code SecurityExceptionAdvice}, before
     * {@code ErrorHandlingAdvice}" -- is the whole point and is asserted by the HTTP tests.
     */
    public static final int ORDER = Ordered.LOWEST_PRECEDENCE - 100;

    private static final Logger log = LoggerFactory.getLogger(CrmExceptionAdvice.class);

    @ExceptionHandler(CrmDomainException.class)
    public ResponseEntity<ErrorResponse> handle(CrmDomainException ex, HttpServletRequest request) {
        Object correlationId = request.getAttribute(CorrelationIdFilter.MDC_KEY);
        log.warn("CRM domain exception [{}] on {} {}: {} (details={}, correlationId={})",
            ex.code(), request.getMethod(), request.getRequestURI(), ex.getMessage(), ex.details(), correlationId);
        ErrorResponse body = new ErrorResponse(
            ex.code(),
            ex.getMessage(),
            ex.details(),
            correlationId == null ? null : correlationId.toString(),
            Instant.now(),
            ex.retryable());
        return ResponseEntity.status(ex.status()).body(body);
    }

    /**
     * A query parameter the server cannot bind is a <b>refusal</b>, not a crash.
     *
     * <p>Spring raises this before the controller runs, when a query parameter's string does not
     * convert to the declared type. The inbox's {@code filter} is the case that matters: it is
     * declared as {@code InboxFilter} so the OpenAPI document publishes an {@code enum} for it, and
     * a client that ignores the enum — or a hand-typed URL — must get {@code 400 VALIDATION_FAILED}
     * rather than the {@code 500} an unhandled binding failure produces. {@code plan.md} requires a
     * refusal here, and a 500 on a malformed query is the failure mode the enum was introduced to
     * remove.
     *
     * <p>The message names the accepted values <em>when the parameter is an enum</em>, because that
     * list is exactly what the caller needs to recover and it is derived from the same type the
     * document publishes. It never names the rejected value back for a parameter that is not an
     * enum, so this handler cannot become an oracle for probing accepted inputs on other
     * parameters.
     *
     * <p>Declared here rather than in the application advice because the rendering is this
     * module's vocabulary and {@code app-bootstrap}'s advice is the floor, which by definition may
     * not claim a specific failure ahead of it. It applies application-wide, so the finance and
     * export routes get a 400 for an unbindable query parameter too instead of a 500.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                            HttpServletRequest request) {
        Object correlationId = request.getAttribute(CorrelationIdFilter.MDC_KEY);
        String parameter = ex.getName();
        Class<?> required = ex.getRequiredType();

        String message;
        Map<String, Object> details;
        if (required != null && required.isEnum()) {
            List<String> accepted = List.of(required.getEnumConstants())
                .stream().map(constant -> ((Enum<?>) constant).name()).toList();
            message = parameter + " must be one of " + accepted;
            details = Map.of("field", parameter, "accepted", accepted);
        }
        else {
            message = parameter + " is not a valid value";
            details = Map.of("field", parameter);
        }

        ErrorResponse body = new ErrorResponse(
            "VALIDATION_FAILED", message, details,
            correlationId == null ? null : correlationId.toString(),
            Instant.now(), false);
        return ResponseEntity.badRequest().body(body);
    }
}