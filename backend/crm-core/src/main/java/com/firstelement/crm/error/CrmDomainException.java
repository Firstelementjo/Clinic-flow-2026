package com.firstelement.crm.error;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * The base of the CRM domain's failures.
 *
 * <h2>Why the code and the status travel together</h2>
 *
 * <p>PROJECT_REQUIREMENTS §6 requires one stable error body with {@code code}, {@code message},
 * {@code details}, {@code requestId} and an optional {@code retryable}. A domain failure
 * already knows its own machine code and its own status; carrying them in the exception
 * stops a controller from inventing a different code per route, which is what makes the
 * error contract stable enough for an Angular client to branch on.
 *
 * <h2>Messages are written to be rendered</h2>
 *
 * <p>The {@link #message} may reach a response body. Subclasses therefore never embed a
 * tenant, project, role, permission or another row's field value in it. Tenant facts go to
 * the security/audit stream, not to the caller.
 */
public class CrmDomainException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> details;
    private final Boolean retryable;

    public CrmDomainException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of(), null);
    }

    public CrmDomainException(HttpStatus status, String code, String message,
                              Map<String, Object> details, Boolean retryable) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(details));
        this.retryable = retryable;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    public Boolean retryable() {
        return retryable;
    }
}