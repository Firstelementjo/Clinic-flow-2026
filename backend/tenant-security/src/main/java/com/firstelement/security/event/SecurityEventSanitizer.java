package com.firstelement.security.event;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Mechanical enforcement of the "no secrets in the audit stream" rule.
 *
 * <p>Callers construct {@link SecurityEvent} metadata from request input, and a
 * future contributor will eventually add a field that should not be persisted. This
 * class turns that mistake from a data leak into a visible, failing event: any key
 * that looks sensitive is replaced by {@link #REDACTED_MARKER} before the event
 * leaves this module.
 *
 * <p>The key test is deliberately generous (substring match on a lowercased key), so
 * {@code password}, {@code passwordHash}, {@code token}, {@code sessionToken},
 * {@code authorization}, {@code cookie}, {@code secret}, {@code credential},
 * {@code apiKey}, and {@code encryptionKey} are all caught.
 */
public final class SecurityEventSanitizer {

    public static final String REDACTED_MARKER = "[redacted]";

    private static final Set<String> SENSITIVE_FRAGMENTS = Set.of(
        "password", "passwd", "token", "authorization", "cookie", "secret",
        "credential", "apikey", "api_key", "encryptionkey", "privatekey",
        "session_token", "bearer", "otp");

    private SecurityEventSanitizer() {
    }

    /** @return the same map with every sensitive-looking value replaced. */
    public static Map<String, Object> sanitize(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> cleaned = new java.util.LinkedHashMap<>();
        metadata.forEach((key, value) -> {
            if (key != null && isSensitive(key)) {
                cleaned.put(key, REDACTED_MARKER);
            } else if (value instanceof Map<?, ?> nested) {
                cleaned.put(key, sanitize(castStringKeys(nested)));
            } else {
                cleaned.put(key, value);
            }
        });
        return cleaned;
    }

    public static boolean isSensitive(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT).replace("-", "").replace(".", "");
        return SENSITIVE_FRAGMENTS.stream().anyMatch(normalized::contains);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castStringKeys(Map<?, ?> source) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        source.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }
}