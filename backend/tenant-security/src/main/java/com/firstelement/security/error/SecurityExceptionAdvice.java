package com.firstelement.security.error;

import com.firstelement.kernel.error.ErrorResponse;
import com.firstelement.kernel.web.CorrelationIdFilter;
import com.firstelement.security.event.SecurityEvent;
import com.firstelement.security.event.SecurityEventPublisher;
import com.firstelement.security.event.SecurityEventType;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates security failures raised <em>inside</em> a controller or service into the
 * stable error body.
 *
 * <p>{@code ExceptionTranslationFilter} only handles failures that propagate out of the
 * filter chain. A {@link PolicyDeniedException} thrown by a repository inside a
 * controller is an ordinary exception from Spring MVC's point of view and would
 * otherwise be rendered by the application's generic advice as
 * {@code 500 INTERNAL_ERROR}. This advice makes it a first-class, non-disclosing
 * {@code 404}.
 *
 * <p>{@link Ordered#HIGHEST_PRECEDENCE} places it ahead of the application module's
 * generic advice, which is the {@code Exception} catch-all.
 *
 * <h2>Why this advice is not scoped to a package</h2>
 *
 * <p>It used to be declared {@code basePackages = "com.firstelement"}, which is the
 * narrowest scope available and the one this advice can least afford. Every handler below
 * translates a security failure into the non-disclosing body, and the floor answers
 * {@code 500 INTERNAL_ERROR} for everything it does not claim. A {@code @RestControllerAdvice}
 * that declares {@code basePackages} is consulted <em>only</em> for a controller in that
 * package, so a {@link PolicyDeniedException} raised from a controller outside it is not
 * rendered as {@code 404}: it falls past this advice to the catch-all and a cross-tenant denial
 * is answered {@code 500}. That inverts the whole point of the boundary, twice over. A 500 is
 * logged at {@code error} and paged, so the worst-behaved request in the system is the one that
 * manufactures the most incident signal; and a 404 that only happens for controllers somebody
 * remembered to place in a namespace is a security control held by a convention rather than by
 * the configuration.
 *
 * <p>This was found the honest way rather than reasoned about: during Phase 18 the error-mapping
 * probe fixture was first written outside {@code com.firstelement}, and every cross-tenant
 * negative came back {@code INTERNAL_ERROR} while the domain ones were correct. So the trap was
 * real, and it was a property of the advice's scope, not of one route.
 *
 * <h2>Widening is safe for every handler in this class, and why</h2>
 *
 * <p>Widening an advice is only safe if no handler in it competes with another advice for the
 * same exception, and if nothing it claims would change meaning when applied everywhere. Both
 * were checked against the assembled application rather than assumed:
 *
 * <ul>
 *   <li>{@code PolicyDeniedException} is the reason for the change. Nothing else handles it:
 *       {@code CrmExceptionAdvice}'s javadoc records that it extends {@code AccessDeniedException}
 *       and not {@code CrmDomainException}, and {@code ExportExceptionHandler} explicitly refuses
 *       to handle it so the non-disclosing answer cannot be replaced by a disclosing one.</li>
 *   <li>{@code AccessDeniedException} is thrown directly by services in at least four modules
 *       ({@code MembershipAdminService}, {@code PeriodLifecycleService}, {@code AdjustmentService},
 *       {@code RateCardService}, {@code ExportJobService}) and {@code PolicyDeniedException} is
 *       its only subclass in the repository. Spring resolves the most specific handler, so the
 *       404 for a scope denial and the 403 for a capability denial coexist without either
 *       shadowing the other.</li>
 *   <li>{@code AuthenticationException}, {@code AuthenticationFailedException} and
 *       {@code RateLimitedAuthenticationException} look like the login-flow vocabulary and are,
 *       but that is a property of where their types are <em>constructed</em>
 *       ({@code LoginService}, plus the rate limiter), not of the advice. {@code
 *       AuthenticationFailedException} and {@code RateLimitedAuthenticationException} extend
 *       {@code RuntimeException}, not {@code AuthenticationException}, so the three handlers are
 *       disjoint and none can mask another. No other advice claims any of the three.</li>
 *   <li>{@code CsrfException} is the only {@code AuthenticationException} subclass involved and
 *       has no subclass in the repository; no other advice claims it. Note that the common CSRF
 *       failure never reaches here at all - it propagates out of the filter chain and
 *       {@code SecurityErrorRenderer} writes it - so this handler exists for the case a service
 *       validates a token itself, which is a case that must be 403 and not 500 wherever it is
 *       called from.</li>
 * </ul>
 *
 * <p>What this advice deliberately still does not claim, so that widening changes nothing else:
 * {@code IllegalArgumentException}, {@code IllegalStateException}, the data-access exceptions
 * and every domain refusal. Those are the application's floor and the CRM domain family's, and
 * {@code ExportExceptionHandler}'s scoped handlers for them are unaffected.
 *
 * <p>The scope that remains is not a package. It is the exception type, which is the only scope a
 * security refusal can safely be keyed on.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class SecurityExceptionAdvice {

    private static final Logger log = LoggerFactory.getLogger(SecurityExceptionAdvice.class);

    private final SecurityEventPublisher events;

    public SecurityExceptionAdvice(SecurityEventPublisher events) {
        this.events = events == null ? event -> { } : events;
    }

    /**
     * A denial that involved a tenant/project object. Rendered exactly as a missing
     * resource, so the existence of another tenant's object is never disclosed.
     */
    @ExceptionHandler(PolicyDeniedException.class)
    public ResponseEntity<ErrorResponse> policyDenied(PolicyDeniedException ex, HttpServletRequest request) {
        publish(SecurityEventType.AUTHORIZATION_DENIED, request, "web", ex.reason(),
            Map.of("scope", ex.reason(), "surface", "http"));
        log.info("Request rejected path={}", request.getRequestURI());
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found", request);
    }

    /** A denial that involved only a permission/capability. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> accessDenied(AccessDeniedException ex, HttpServletRequest request) {
        publish(SecurityEventType.ACCESS_DENIED, request, "web", "permission-denied",
            Map.of("surface", "http"));
        log.info("Access denied path={}", request.getRequestURI());
        return build(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access denied", request);
    }

    /** An authentication failure that reached the dispatcher, e.g. from a service. */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> authentication(AuthenticationException ex, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Authentication required", request);
    }

    /**
     * A failed credential check.
     *
     * <p>{@code INVALID_CREDENTIALS} for an unknown email, an inactive user, an inactive
     * organization, and a wrong password alike — the body cannot be used to tell which.
     */
    @ExceptionHandler(AuthenticationFailedException.class)
    public ResponseEntity<ErrorResponse> invalidCredentials(AuthenticationFailedException ex,
                                                            HttpServletRequest request) {
        publish(SecurityEventType.LOGIN_FAILED, request, "Session", "credentials-rejected",
            Map.of("surface", "http"));
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .header("Retry-After", "0")
            .body(new ErrorResponse("INVALID_CREDENTIALS", ex.getMessage(), Map.of(),
                correlationId(request), java.time.Instant.now(), Boolean.FALSE));
    }

    /** The login rate limiter refused the attempt. */
    @ExceptionHandler(RateLimitedAuthenticationException.class)
    public ResponseEntity<ErrorResponse> rateLimited(RateLimitedAuthenticationException ex,
                                                     HttpServletRequest request) {
        publish(SecurityEventType.LOGIN_RATE_LIMITED, request, "Session", "login-rate-limited",
            Map.of("surface", "http", "retryAfterSeconds", ex.retryAfterSeconds()));
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header("Retry-After", String.valueOf(ex.retryAfterSeconds()))
            .body(new ErrorResponse("LOGIN_RATE_LIMITED", ex.getMessage(), Map.of(),
                correlationId(request), java.time.Instant.now(), Boolean.TRUE));
    }

    /**
     * A missing or invalid CSRF token on a cookie-authenticated mutation. {@code 403} with
     * a distinct code so the Angular client can re-fetch a token, and audited because a
     * burst of these is a CSRF probe.
     */
    @ExceptionHandler(CsrfException.class)
    public ResponseEntity<ErrorResponse> csrf(CsrfException ex, HttpServletRequest request) {
        publish(SecurityEventType.CSRF_REJECTED, request, "web", ex.getClass().getSimpleName(),
            Map.of("surface", "http"));
        return build(HttpStatus.FORBIDDEN, "CSRF_TOKEN_INVALID", "CSRF token missing or invalid", request);
    }

    private void publish(SecurityEventType type, HttpServletRequest request,
                         String resourceType, String reason, Map<String, Object> metadata) {
        try {
            events.publish(SecurityEvent.builder(type)
                .resourceType(resourceType)
                .reason(reason)
                .metadata(metadata)
                .correlationId(correlationId(request))
                .build());
        } catch (RuntimeException e) {
            log.error("Failed to publish {}", type, e);
        }
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String code, String message,
                                                HttpServletRequest request) {
        return ResponseEntity.status(status)
            .body(new ErrorResponse(code, message, Map.of(), correlationId(request),
                java.time.Instant.now(), null));
    }

    private String correlationId(HttpServletRequest request) {
        Object value = request.getAttribute(CorrelationIdFilter.MDC_KEY);
        return value == null ? null : value.toString();
    }
}