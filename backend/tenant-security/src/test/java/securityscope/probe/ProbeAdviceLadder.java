package securityscope.probe;

import com.firstelement.kernel.error.ErrorResponse;
import com.firstelement.kernel.web.CorrelationIdFilter;
import java.time.Instant;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import securityscope.probe.ProbeRefusals.RefusedException;
import securityscope.probe.ProbeRefusals.RowMissingException;

/**
 * The other two rungs of the ladder {@code SecurityExceptionAdvice} has to sit on top of, mirrored.
 *
 * <h2>Why mirrors and not the real advices</h2>
 *
 * <p>{@code :backend:tenant-security} is depended on by {@code :backend:crm-core} and
 * {@code :backend:app-bootstrap}, so this module's tests cannot depend on either: a test-scope edge
 * back to a module that already depends on this one is the cycle {@code check-architecture.ps1}
 * FE-ARCH-004 exists to forbid. The two advices this suite needs as its floor and its neighbour are
 * therefore reproduced here with their <em>order</em> and their <em>handling width</em> intact, and
 * only {@code SecurityExceptionAdvice} is the production class.
 *
 * <p>That is enough for the claim this suite makes, because the claim is about scope and ordering
 * rather than about any handler's rendering: a policy denial raised from a controller outside
 * {@code com.firstelement} reaches the security advice, and neither a 400-family neighbour nor a
 * {@code Exception} catch-all gets the chance to answer first. The assembled-application proof of the
 * rendered bodies -- real advices, real controllers, real filters -- is {@code app-bootstrap}'s
 * {@code ErrorMappingHttpIntegrationTest}, and this suite is its complement rather than its
 * replacement.
 *
 * <p>The mirrors are also the reason the suite can assert something the assembled test cannot: that
 * the security advice is genuinely global. In the product every controller is under
 * {@code com.firstelement}, so a package-scoped advice looks correct there forever.
 *
 * <h2>What the catch-all must keep doing</h2>
 *
 * <p>{@link ProbeFloorAdvice} holds {@code @ExceptionHandler(Exception.class)} and answers
 * {@code 500}. That is the property that makes the suite meaningful at all: if the security advice
 * were scoped away from this controller, the denial would not become a {@code 400} or a missing
 * route, it would become a {@code 500} with a logged stack trace. A test that only asserted
 * {@code isNot5xxServerError()} would have passed for the wrong reasons a hundred times over.
 */
public final class ProbeAdviceLadder {

    private ProbeAdviceLadder() {
    }

    /**
     * Stands in for {@code CrmExceptionAdvice}: the domain refusal family, one rung below the
     * security advice.
     */
    @Order(Ordered.LOWEST_PRECEDENCE - 100)
    @RestControllerAdvice
    public static class ProbeDomainRefusalAdvice {

        @ExceptionHandler(RefusedException.class)
        public ResponseEntity<ErrorResponse> refused(RefusedException ex,
                                                     jakarta.servlet.http.HttpServletRequest request) {
            return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", ex.getMessage(), request);
        }
    }

    /**
     * Stands in for {@code ErrorHandlingAdvice}: the application's floor, which holds the
     * {@code Exception} catch-all and must therefore sort last.
     */
    @Order(Ordered.LOWEST_PRECEDENCE)
    @RestControllerAdvice
    public static class ProbeFloorAdvice {

        @ExceptionHandler(RowMissingException.class)
        public ResponseEntity<ErrorResponse> notFound(RowMissingException ex,
                                                       jakarta.servlet.http.HttpServletRequest request) {
            return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found", request);
        }

        /**
         * An unmapped path. The real floor answers {@code 404} for this, and it has to here too:
         * otherwise a mistyped path in this suite is a {@code 500}, and the {@code 404} assertions
         * would be checking the wrong thing.
         */
        @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
        public ResponseEntity<ErrorResponse> unmapped(
            org.springframework.web.servlet.resource.NoResourceFoundException ex,
            jakarta.servlet.http.HttpServletRequest request) {
            return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found", request);
        }

        @ExceptionHandler(Exception.class)
        public ResponseEntity<ErrorResponse> unhandled(Exception ex,
                                                       jakarta.servlet.http.HttpServletRequest request) {
            return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected server error",
                request);
        }
    }

    /**
     * The same body construction every advice in the product uses, so a byte comparison between a
     * denial and a missing row cannot be won by one of the two helpers here serialising a field the
     * other drops.
     */
    static ResponseEntity<ErrorResponse> build(HttpStatus status, String code, String message,
                                               jakarta.servlet.http.HttpServletRequest request) {
        Object correlationId = request.getAttribute(CorrelationIdFilter.MDC_KEY);
        return ResponseEntity.status(status)
            .body(new ErrorResponse(code, message, Map.of(),
                correlationId == null ? null : correlationId.toString(), Instant.now(), null));
    }
}
