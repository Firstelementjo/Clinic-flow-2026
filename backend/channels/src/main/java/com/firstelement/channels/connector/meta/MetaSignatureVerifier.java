package com.firstelement.channels.connector.meta;

import com.firstelement.channels.api.WebhookVerification;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Meta webhook signature verification, ported from
 * {@code packages/connectors/src/meta/signature.ts}.
 *
 * <h2>Two details decide whether this protects anything</h2>
 *
 * <ol>
 *   <li><b>It must run against the bytes as received.</b> Verifying a re-serialized
 *       {@code JSON.parse} result computes a different digest — key order and whitespace change —
 *       so the raw body is what arrives here. The caller is
 *       {@code RawBodyCapturingFilter}, which buffers the request body before anything reads it.
 *       A body that arrives empty is {@link WebhookVerification.Reason#MISSING_BODY}, not a
 *       silent "unsigned".</li>
 *   <li><b>The comparison is constant-time.</b> A {@code String.equals} on a digest leaks it one
 *       byte at a time to anyone willing to measure. {@link MessageDigest#isEqual} compares every
 *       byte regardless of where the first difference is.</li>
 * </ol>
 *
 * <h3>Why the length check comes first</h3>
 *
 * <p>A constant-time comparison over unequal lengths has nothing meaningful to hide: SHA-256
 * digests are fixed-width and public, so an attacker learns only the length they already know.
 * Checking it first lets the constant-time comparison assume equal-length inputs, which is the
 * precondition {@code MessageDigest.isEqual} documents.
 *
 * <h3>The app secret is never logged, never compared as a string, never echoed</h3>
 *
 * <p>{@link #auditLabel()} on the result carries the reason, not the digest.
 */
public final class MetaSignatureVerifier {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String PREFIX = "sha256=";
    private static final int HEX_DIGITS = 64;

    private MetaSignatureVerifier() {
    }

    /**
     * Verifies {@code X-Hub-Signature-256} over the raw body.
     *
     * @param rawBody       the request body exactly as received; null or empty means the caller
     *                      never captured it, which is a wiring defect and is reported as such
     * @param headers       case-insensitive header names
     * @param appSecret     the Meta app secret from the connection's encrypted credentials
     */
    public static WebhookVerification verify(byte[] rawBody, Map<String, String> headers,
                                             String appSecret) {
        if (appSecret == null || appSecret.isBlank()) {
            return WebhookVerification.failed(WebhookVerification.Reason.NOT_CONFIGURED,
                "the connection has no Meta app secret");
        }
        if (rawBody == null || rawBody.length == 0) {
            return WebhookVerification.failed(WebhookVerification.Reason.MISSING_BODY,
                "the raw request body was not captured, so nothing could be verified");
        }
        String header = header(headers, "X-Hub-Signature-256");
        if (header == null || header.isBlank()) {
            return WebhookVerification.failed(WebhookVerification.Reason.MISSING_HEADER,
                "X-Hub-Signature-256 was absent");
        }
        String normalized = header.trim();
        if (!normalized.toLowerCase(Locale.ROOT).startsWith(PREFIX)) {
            return WebhookVerification.failed(WebhookVerification.Reason.SIGNATURE_MISMATCH,
                "the signature header was not sha256-prefixed");
        }
        String provided = normalized.substring(PREFIX.length());
        byte[] providedBytes;
        try {
            providedBytes = hexToBytes(provided);
        } catch (IllegalArgumentException e) {
            return WebhookVerification.failed(WebhookVerification.Reason.SIGNATURE_MISMATCH,
                "the signature was not valid hex");
        }
        if (providedBytes.length != HEX_DIGITS / 2) {
            return WebhookVerification.failed(WebhookVerification.Reason.SIGNATURE_MISMATCH,
                "the signature length was not " + (HEX_DIGITS / 2) + " bytes");
        }

        byte[] expected = hmacSha256(appSecret, rawBody);
        // Constant-time. Returns false rather than throwing on a mismatch.
        return MessageDigest.isEqual(expected, providedBytes)
            ? WebhookVerification.success()
            : WebhookVerification.failed(WebhookVerification.Reason.SIGNATURE_MISMATCH,
                "the digest did not match");
    }

    /**
     * The GET handshake Meta performs when a webhook URL is first registered: echo
     * {@code hub.challenge} only when the verify token matches, compared in constant time.
     *
     * <p>Both the challenge and the token come from the query string, which is attacker
     * controlled, so the comparison is byte-wise and length-checked — the same reasoning as the
     * digest above.
     */
    public static boolean matchesVerifyToken(String provided, String expected) {
        if (provided == null || expected == null || expected.isBlank()) {
            return false;
        }
        byte[] providedBytes = provided.getBytes(StandardCharsets.UTF_8);
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        if (providedBytes.length != expectedBytes.length) {
            return false;
        }
        return MessageDigest.isEqual(providedBytes, expectedBytes);
    }

    private static byte[] hmacSha256(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac.doFinal(body);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(
                "HmacSHA256 is required by every JRE; refusing to accept an unverifiable webhook", e);
        }
    }

    private static byte[] hexToBytes(String hex) {
        if (hex.length() % 2 != 0) {
            throw new IllegalArgumentException("hex length must be even");
        }
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int high = Character.digit(hex.charAt(i * 2), 16);
            int low = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("not a hex digit");
            }
            bytes[i] = (byte) ((high << 4) | low);
        }
        return bytes;
    }

    /** Case-insensitive header lookup; HTTP header names are case-insensitive by RFC 7230. */
    public static String header(Map<String, String> headers, String name) {
        if (headers == null) {
            return null;
        }
        String direct = headers.get(name);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }
}