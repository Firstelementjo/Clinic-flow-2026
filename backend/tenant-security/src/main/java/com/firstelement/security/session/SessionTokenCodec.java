package com.firstelement.security.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Opaque session-token generation and hashing, byte-compatible with the legacy
 * {@code packages/auth/src/session-token.ts}.
 *
 * <h2>The contract that must not drift</h2>
 *
 * <ul>
 *   <li>32 random bytes, base64url-encoded, no padding — the same shape the legacy
 *       runtime issues, so a cookie minted before cutover and a cookie minted after
 *       it are indistinguishable to the resolver.</li>
 *   <li>Only the lowercase hex SHA-256 is persisted, in {@code sessions."tokenHash"},
 *       which has a unique constraint.</li>
 *   <li>{@link #hash(String)} must stay byte-identical to the legacy
 *       {@code hashSessionToken} or every existing browser session silently dies at
 *       cutover. This is the single highest-risk compatibility point in ADR-0001 and
 *       is asserted directly in the tests.</li>
 * </ul>
 *
 * <p>A raw token appears in exactly two places: the inbound cookie and the response
 * that sets it. It is never logged, never audited, and never stored.
 */
public final class SessionTokenCodec {

    /** Cookie name. Matches the legacy {@code SESSION_COOKIE_NAME}. */
    public static final String COOKIE_NAME = "fe_session";

    private static final int TOKEN_BYTES = 32;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final SecureRandom RANDOM = new SecureRandom();

    private SessionTokenCodec() {
    }

    /** A fresh 256-bit token, base64url-encoded. */
    public static String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    /** Lowercase hex SHA-256, matching the legacy {@code hashSessionToken}. */
    public static String hash(String rawToken) {
        if (rawToken == null) {
            throw new IllegalArgumentException("rawToken must not be null");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK spec", e);
        }
    }

    /**
     * Constant-time comparison that does not throw on a length mismatch.
     *
     * <p>{@code MessageDigest.isEqual} is used rather than {@code String.equals} so
     * that a length difference is not a fast-path early return.
     */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
            a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}