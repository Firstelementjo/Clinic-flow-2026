package com.firstelement.exports.catalog;

import java.util.UUID;

/**
 * A report/template catalogue operation that lost a race or reached a state it cannot change.
 *
 * <h2>Why this is separate from {@code IllegalArgumentException}</h2>
 *
 * <p>Three distinct HTTP answers are needed and collapsing them makes an operator's log useless:
 * a caller mistake is {@code 422}, a lost optimistic-concurrency race is {@code 409 CONFLICT}, and a
 * cross-tenant id is a non-disclosing {@code 404}. This type is the {@code 409}, and its message is
 * written so it can be shown to an operator verbatim: it names what lost and what is still true.
 */
public class ReportCatalogConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String resourceType;
    private final UUID resourceId;

    public ReportCatalogConflictException(String message) {
        this(message, null, null);
    }

    public ReportCatalogConflictException(String message, String resourceType, UUID resourceId) {
        super(message);
        this.resourceType = resourceType;
        this.resourceId = resourceId;
    }

    /** The stable error code for the HTTP layer. */
    public String code() {
        return "REPORT_CATALOG_CONFLICT";
    }

    /** The resource whose state blocked the operation. */
    public String resourceType() {
        return resourceType;
    }

    /** The resource id, when the conflict is about one specific row. */
    public UUID resourceId() {
        return resourceId;
    }

    /** Whether this conflict is specifically a lost publication race. */
    public boolean isPublicationRace() {
        return getMessage() != null && getMessage().contains("won the race");
    }
}