package com.firstelement.crm.error;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * An optimistic-concurrency collision: the caller's read is stale.
 *
 * <h2>What the caller is told</h2>
 *
 * <p>409 with {@code VERSION_CONFLICT}, and the resource's <em>current</em> version so the
 * client can re-read and decide. It is deliberately not {@code 404} and not {@code 403}: the
 * caller proved they may see the row by reaching this check, and hiding a lost update behind
 * a permission error is how two employees overwrite each other's work in silence.
 *
 * <h2>What the caller is not told</h2>
 *
 * <p>Nothing about who changed the row, when, or what they changed. The value is enough to
 * recover, not enough to profile a colleague.
 *
 * @param resourceLabel which aggregate collided, e.g. {@code "Conversation"}
 * @param expectedVersion the version the caller based its write on
 * @param currentVersion  the version actually stored
 */
public class VersionConflictException extends CrmDomainException {

    private static final long serialVersionUID = 1L;

    public VersionConflictException(String resourceLabel, Object expectedVersion, Object currentVersion) {
        super(HttpStatus.CONFLICT, "VERSION_CONFLICT",
            resourceLabel + " was changed by someone else. Re-read it and try again.",
            details(resourceLabel, expectedVersion, currentVersion),
            // Not retryable: a blind retry would overwrite the other person's change.
            Boolean.FALSE);
    }

    /** The mode transition lost a race; the caller asked to take over a thread someone else owns. */
    public static VersionConflictException modeChanged(String resourceLabel, Object expected, Object current) {
        return new VersionConflictException(resourceLabel, expected, current);
    }

    private static Map<String, Object> details(String resourceLabel, Object expected, Object current) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("resource", resourceLabel);
        details.put("expectedVersion", expected);
        details.put("currentVersion", current);
        return details;
    }
}