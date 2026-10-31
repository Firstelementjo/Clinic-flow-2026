package com.firstelement.security.error;

import com.firstelement.kernel.error.ErrorResponse;
import com.firstelement.kernel.web.CorrelationIdFilter;
import com.firstelement.security.event.SecurityEvent;
import com.firstelement.security.event.SecurityEventPublisher;
import com.firstelement.security.event.SecurityEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;

/**
 * Renders authentication and authorization failures as the stable FirstElement error
 * body.
 *
 * <h2>What must not appear in these bodies</h2>
 *
 * <ul>
 *   <li>Which of "no session", "expired session", "revoked session", or "session belongs
 *       to another user" applied. All four are {@code 401 AUTHENTICATION_REQUIRED} with
 *       an identical body.</li>
 *   <li>The requested tenant, project, role, permission, or the target object's
 *       existence. {@code PolicyDeniedException} becomes {@code 404 NOT_FOUND} with the
 *       same body a nonexistent id produces.</li>
 *   <li>The {@code reason} carried internally by a {@link PolicyDeniedException}; it
 *       goes to the security stream instead.</li>
 * </ul>
 *
 * <p>Defined here rather than in the application module's advice because the security
 * failures must be rendered identically no matter which module raised them, and because
 * the security boundary is the owner of its own failure contract.
 */
public class SecurityErrorRenderer implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(SecurityErrorRenderer.class);

    private final ObjectMapper objectMapper;
    private final transient SecurityEventPublisher events;

    public SecurityErrorRenderer(ObjectMapper objectMapper, SecurityEventPublisher events) {
        this.objectMapper = objectMapper;
        this.events = events;
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        write(response, request, HttpServletResponse.SC_UNAUTHORIZED,
            "AUTHENTICATION_REQUIRED", "Authentication required");
    }

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        // An object-scope denial is reported as "not found" so a cross-tenant id and a
        // nonexistent id are indistinguishable.
        if (accessDeniedException instanceof PolicyDeniedException) {
            write(response, request, HttpServletResponse.SC_NOT_FOUND, "NOT_FOUND", "Resource not found");
            return;
        }
        // A CSRF failure reaches here (ExceptionTranslationFilter) rather than an
        // @ExceptionHandler, so it gets its own code: the SPA needs a distinct signal to
        // re-fetch a token, and a burst of these is a probe worth recording.
        if (accessDeniedException instanceof CsrfException) {
            publish(request, SecurityEventType.CSRF_REJECTED,
                accessDeniedException.getClass().getSimpleName());
            write(response, request, HttpServletResponse.SC_FORBIDDEN,
                "CSRF_TOKEN_INVALID", "CSRF token missing or invalid");
            return;
        }
        write(response, request, HttpServletResponse.SC_FORBIDDEN, "ACCESS_DENIED", "Access denied");
    }

    private void publish(HttpServletRequest request, SecurityEventType type, String reason) {
        try {
            if (events != null) {
                events.publish(SecurityEvent.builder(type)
                    .resourceType("web")
                    .reason(reason)
                    .metadata(java.util.Map.of("surface", "http"))
                    .correlationId(correlationId(request))
                    .build());
            }
        } catch (RuntimeException e) {
            log.error("Failed to publish {}", type, e);
        }
    }

    private void write(HttpServletResponse response,
                       HttpServletRequest request,
                       int status,
                       String code,
                       String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        ErrorResponse body = new ErrorResponse(code, message, java.util.Map.of(),
            correlationId(request), java.time.Instant.now(), null);
        objectMapper.writeValue(response.getOutputStream(), body);
        log.debug("security response {} {} {} -> {}", request.getMethod(),
            request.getRequestURI(), status, code);
    }

    private String correlationId(HttpServletRequest request) {
        Object value = request.getAttribute(CorrelationIdFilter.MDC_KEY);
        return value == null ? null : value.toString();
    }
}