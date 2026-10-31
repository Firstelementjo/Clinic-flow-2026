package com.firstelement.crm.api;

import java.util.List;
import java.util.Map;

/**
 * {@code PATCH /api/v1/customers/{customerId}} — the legacy {@code updateCustomerSchema},
 * field for field, including which fields are optional.
 *
 * <h2>Validation mirrors the legacy zod schema exactly</h2>
 *
 * <ul>
 *   <li>{@code displayName}: trimmed, 1..160</li>
 *   <li>{@code primaryEmail}: trimmed, lower-cased, email format, nullable</li>
 *   <li>{@code primaryPhone}: trimmed, {@code ^\+?[0-9\s-]{6,20}$}, nullable — international
 *       format, so a local-format number is rejected at the edge rather than stored unusable</li>
 *   <li>{@code locale}: {@code ar} or {@code en}</li>
 *   <li>{@code notes}: trimmed, at most 5000, nullable</li>
 *   <li>{@code tags}: at most 30 entries, each trimmed 1..40</li>
 *   <li>{@code metadata}: a free object</li>
 * </ul>
 *
 * <h2>Nullability is meaningful</h2>
 *
 * <p>A JSON {@code null} for {@code primaryEmail}/{@code primaryPhone}/{@code notes} is an
 * instruction to clear the field; an absent field is not sent at all. The distinction is why
 * this record keeps {@code null} separate from "not provided" and why the service builds an
 * explicit assignment list.
 *
 * <p>{@code id}, {@code organizationId} and {@code projectId} are absent by design: a client
 * cannot move a customer between tenants or projects, and {@code If-Match} (not a body field)
 * carries the concurrency token.
 */
public record UpdateCustomerRequest(
    String displayName,
    String primaryEmail,
    String primaryPhone,
    String locale,
    String notes,
    List<String> tags,
    Map<String, Object> metadata
) {

    public static final int MAX_NAME = 160;
    public static final int MAX_NOTES = 5000;
    public static final int MAX_TAGS = 30;
    public static final int MAX_TAG_LENGTH = 40;
    public static final int MAX_EMAIL = 320;
    public static final String PHONE_PATTERN = "^\\+?[0-9\\s-]{6,20}$";

    /** A trivial empty patch. The legacy runtime accepted it and wrote nothing but the timestamp. */
    public boolean isEmpty() {
        return displayName == null && primaryEmail == null && primaryPhone == null
            && locale == null && notes == null && tags == null && metadata == null;
    }

    /** Convenience for tests and for callers that hold a typed customer id. */
    public static UpdateCustomerRequest rename(String displayName) {
        return new UpdateCustomerRequest(displayName, null, null, null, null, null, null);
    }
}