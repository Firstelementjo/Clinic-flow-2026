package com.firstelement.kernel.error;

import java.time.Instant;
import java.util.Map;

/**
 * The stable FirstElement API error body. All controllers and advice layers must
 * produce this shape for non-2xx responses.
 */
public record ErrorResponse(
    String code,
    String message,
    Map<String, Object> details,
    String requestId,
    Instant timestamp,
    Boolean retryable
) {
    public static ErrorResponse of(String code, String message, String requestId) {
        return new ErrorResponse(code, message, Map.of(), requestId, Instant.now(), null);
    }

    public static ErrorResponse retryable(String code, String message, String requestId) {
        return new ErrorResponse(code, message, Map.of(), requestId, Instant.now(), true);
    }
}
