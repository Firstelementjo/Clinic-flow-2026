package com.firstelement.security.platform.web;

import com.firstelement.security.platform.PlatformAuthService;
import com.firstelement.security.platform.PlatformClinicService;
import com.firstelement.security.platform.PlatformOperatorContextHolder;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders the platform tier's refusals.
 *
 * <h2>Why it is separate from {@code SecurityExceptionAdvice}</h2>
 *
 * <p>The customer advice is tenant-scoped and its whole purpose is non-disclosure: a forbidden
 * project and a nonexistent one produce byte-identical 404s. The platform console has the opposite
 * problem — the operator is already the most privileged principal in the system, so there is nothing
 * to non-disclose <em>about them</em>, and a 404 would misdescribe a capability problem as a missing
 * route. This advice answers 403 with a stable code and nothing else.
 *
 * <p>The messages carry no capability values and no operator detail, so an error body cannot be used
 * to enumerate what an operator holds.
 */
@RestControllerAdvice(assignableTypes = PlatformConsoleController.class)
@ConditionalOnProperty(prefix = "firstelement.platform.console", name = "enabled", havingValue = "true")
public class PlatformExceptionAdvice {

    private static final Logger log = LoggerFactory.getLogger(PlatformExceptionAdvice.class);

    /**
     * A request reached a console route without a platform principal, or without the capability.
     *
     * <p>Logged at debug, not info: a refused probe is already recorded in the cross-tenant audit
     * with its reason, and duplicating it into the application log would put cross-tenant activity
     * in two places with different retention.
     */
    @ExceptionHandler(PlatformOperatorContextHolder.PlatformAccessDenied.class)
    public ResponseEntity<Map<String, Object>> accessDenied(
            PlatformOperatorContextHolder.PlatformAccessDenied ex) {
        log.debug("Platform request refused: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("code", "PLATFORM_ACCESS_DENIED"));
    }

    /**
     * A failed handshake.
     *
     * <p>One shape for every cause — unknown account, wrong password, wrong code, not yet
     * elevated. The distinction is in the audit trail, which the operator owns, and not in the
     * response, which an attacker also receives.
     */
    @ExceptionHandler(PlatformAuthService.AuthenticationFailed.class)
    public ResponseEntity<Map<String, Object>> authenticationFailed(
            PlatformAuthService.AuthenticationFailed ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("code", "PLATFORM_AUTHENTICATION_FAILED"));
    }

    /** An organization the operator named does not exist. */
    @ExceptionHandler(PlatformClinicService.UnknownOrganization.class)
    public ResponseEntity<Map<String, Object>> unknownOrganization(
            PlatformClinicService.UnknownOrganization ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("code", "ORGANIZATION_NOT_FOUND"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("code", "BAD_REQUEST", "message", ex.getMessage() != null ? ex.getMessage() : "Invalid request"));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", "CONFLICT", "message", ex.getMessage() != null ? ex.getMessage() : "Conflict state"));
    }
}