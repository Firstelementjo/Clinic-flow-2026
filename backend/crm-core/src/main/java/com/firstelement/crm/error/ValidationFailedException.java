package com.firstelement.crm.error;

import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * The request was well-formed but breaks a domain rule — an assignee who is not a project
 * member, an employee at their conversation limit, a case closed without a recorded
 * resolution, a classification answer naming another project's question.
 *
 * <p>400 rather than 409: the caller's request cannot succeed against the current state and
 * retrying it unchanged changes nothing.
 */
public class ValidationFailedException extends CrmDomainException {

    private static final long serialVersionUID = 1L;

    public ValidationFailedException(String message) {
        this(message, Map.of());
    }

    public ValidationFailedException(String message, Map<String, Object> details) {
        super(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message, details, null);
    }

    public static ValidationFailedException code(String code, String message, Map<String, Object> details) {
        return new ValidationFailedException(code, message, details);
    }

    private ValidationFailedException(String code, String message, Map<String, Object> details) {
        super(HttpStatus.BAD_REQUEST, code, message, details, null);
    }
}