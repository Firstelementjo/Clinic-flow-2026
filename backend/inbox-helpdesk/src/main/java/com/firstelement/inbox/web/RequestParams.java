package com.firstelement.inbox.web;

import com.firstelement.crm.error.ValidationFailedException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * Request parsing shared by the {@code /api/v1} inbox controllers.
 *
 * <h2>The rule this class exists to keep visible</h2>
 *
 * <p>A {@code projectId}, a {@code cursor} or an {@code employeeId} in a query string, a path
 * segment or a header is a <em>request</em>. Every one of them is parsed here and then handed to
 * {@code TenantQueryGuard}, which decides whether it is legal. Nothing downstream reads a client
 * id and trusts it.
 */
final class RequestParams {

    private RequestParams() {
    }

    /**
     * The concurrency token from {@code If-Match}.
     *
     * <p>Accepted forms: a bare ISO-8601 timestamp, or a weak/strong ETag wrapping one. An
     * absent header means "no token", which preserves the legacy last-writer-wins behaviour for
     * a client that has not adopted {@code If-Match} yet.
     *
     * @throws ValidationFailedException when the header is present but not a timestamp
     */
    static LocalDateTime ifMatch(@RequestHeader(name = "If-Match", required = false) String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            return null;
        }
        String value = ifMatch.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2).trim();
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.equals("*")) {
            return null;
        }
        try {
            return com.firstelement.crm.api.Timestamps.parseStored(value);
        } catch (IllegalArgumentException e) {
            throw ValidationFailedException.code("INVALID_IF_MATCH",
                "If-Match must carry the resource's version timestamp", Map.of());
        }
    }

    /** The {@code ETag} value for a version, so the client has a token to send back. */
    static String etag(LocalDateTime version) {
        return version == null ? null : "\"" + com.firstelement.crm.api.Timestamps.toIso(version) + "\"";
    }

    /** An optional UUID path/query parameter; a malformed value is a 400, never a null. */
    static UUID optionalUuid(String raw, String name) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw ValidationFailedException.code("VALIDATION_FAILED", name + " must be a UUID",
                Map.of("field", name));
        }
    }

    /** A required UUID path parameter. */
    static UUID requiredUuid(String raw, String name) {
        UUID value = optionalUuid(raw, name);
        if (value == null) {
            throw ValidationFailedException.code("VALIDATION_FAILED", name + " is required",
                Map.of("field", name));
        }
        return value;
    }

    /**
     * A bounded page size.
     *
     * <p>Clamped rather than rejected so a client asking for 500 gets 100 instead of a 400: the
     * legacy zod schema rejected it, but a bounded clamp is the friendlier and equally safe
     * behaviour and the response is still complete and cursor-paginated.
     */
    static int limit(String raw, int defaultValue, int max) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            if (parsed < 1) {
                return 1;
            }
            return Math.min(parsed, max);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** An optional integer request/response field. */
    static Integer optionalInt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}