package com.firstelement.crm.error;

import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * The row does not exist, or it exists and is outside the caller's scope.
 *
 * <h2>Both cases are the same answer, on purpose</h2>
 *
 * <p>FR-2.7: "Authorization failures must not disclose the existence of another tenant's
 * objects". A 403 for "belongs to another tenant" next to a 404 for "does not exist" turns
 * the status code into an existence oracle that can be walked one id at a time.
 *
 * <p>Every tenant-scoped repository therefore returns {@code Optional.empty()} for both, and
 * the service raises this exception with one message. The real reason is published on the
 * security event stream by {@code TenantQueryGuard}.
 */
public class ResourceNotFoundException extends CrmDomainException {

    private static final long serialVersionUID = 1L;

    public ResourceNotFoundException(String resourceLabel) {
        super(HttpStatus.NOT_FOUND, "NOT_FOUND", resourceLabel + " not found");
    }

    public ResourceNotFoundException(String code, String message, Map<String, Object> details) {
        super(HttpStatus.NOT_FOUND, code, message, details, null);
    }
}