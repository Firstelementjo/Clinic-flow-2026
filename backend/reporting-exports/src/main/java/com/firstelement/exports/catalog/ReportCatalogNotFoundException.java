package com.firstelement.exports.catalog;

import java.util.UUID;

/**
 * A catalogue row that does not exist inside the caller's tenant.
 *
 * <h2>Why a 404 and not a 403</h2>
 *
 * <p>PROJECT_REQUIREMENTS §6 requires "problem details that never reveal cross-tenant existence". A
 * tenant A caller asking for tenant B's report definition gets exactly the answer a genuinely missing id
 * gets, and the real reason goes to the security stream — which is what
 * {@code TenantQueryGuard.assertRowScope} does for every other read in this system.
 *
 * <p>The distinction from {@link com.firstelement.security.error.PolicyDeniedException} is therefore
 * deliberate: this type is raised where a <em>definition id</em> was resolved inside an
 * already-authorized scope, and it carries the non-disclosing wording rather than a scope reason.
 */
public class ReportCatalogNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String resourceType;
    private final String reference;

    public ReportCatalogNotFoundException(String resourceType, String reference) {
        super(resourceType + " " + reference + " does not exist");
        this.resourceType = resourceType;
        this.reference = reference;
    }

    public ReportCatalogNotFoundException(String resourceType, int versionNumber) {
        this(resourceType, "version " + versionNumber);
    }

    public ReportCatalogNotFoundException(String resourceType, UUID reference) {
        this(resourceType, String.valueOf(reference));
    }

    /** The stable error code for the HTTP layer. */
    public String code() {
        return "NOT_FOUND";
    }

    public String resourceType() {
        return resourceType;
    }

    /** The version number or id that was asked for. */
    public String reference() {
        return reference;
    }
}