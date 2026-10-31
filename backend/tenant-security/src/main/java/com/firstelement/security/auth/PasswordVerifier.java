package com.firstelement.security.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Password hashing and the constant-time failure behaviour.
 *
 * <h2>bcrypt, deliberately</h2>
 *
 * <p>Legacy hashes in {@code users."passwordHash"} are bcrypt cost 12 produced by
 * {@code bcryptjs} and look like {@code $2a$12$...}. {@link BCryptPasswordEncoder} with
 * strength 12 verifies those directly, so existing logins survive the cutover with no
 * forced re-login window. A {@code DelegatingPasswordEncoder} would require a
 * {@code {bcrypt}} prefix that no legacy hash carries and would reject every one of
 * them, so it is not used.
 *
 * <h2>Why {@link #burnComparison()} exists</h2>
 *
 * <p>Without it, "no such account" returns measurably faster than "wrong password" and
 * the login endpoint becomes a user-enumeration oracle. {@code burnComparison} performs
 * an equivalent bcrypt comparison against a hash of a value nobody knows, so the
 * unknown-account and wrong-password paths cost the same. It is the direct successor of
 * {@code burnPasswordComparison} / {@code DUMMY_PASSWORD_HASH} in
 * {@code packages/auth/src/password.ts}, and the dummy hash is the same constant, so
 * both runtimes stay comparable in cost.
 */
public final class PasswordVerifier {

    /** bcrypt cost 12 hash of a value nobody knows. */
    public static final String DUMMY_PASSWORD_HASH =
        "$2a$12$C6UzMDM.H6dfI/f/IKcEe.5vHZm9O7DkfHRzD1zZLM3wDW8Xn9Wia";

    private static final int COST = 12;

    private final BCryptPasswordEncoder encoder;

    public PasswordVerifier() {
        this.encoder = new BCryptPasswordEncoder(COST);
    }

    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    public boolean matches(String rawPassword, String storedHash) {
        if (rawPassword == null || storedHash == null || storedHash.isBlank()) {
            return false;
        }
        return encoder.matches(rawPassword, storedHash);
    }

    /**
     * Spends the same work as a real comparison so the "no such account" path cannot
     * be timed apart from the "wrong password" path.
     */
    public void burnComparison() {
        encoder.matches("not-the-password", DUMMY_PASSWORD_HASH);
    }

    /** Normalizes an email for lookup without changing what is stored. */
    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Rejects obviously malformed input before it reaches the database.
     *
     * <p>Returns a fixed message rather than a per-rule message, so the failure does
     * not tell an attacker which constraint they just learned about.
     */
    public static boolean isAcceptableEmail(String email) {
        if (email == null) {
            return false;
        }
        String trimmed = email.trim();
        int at = trimmed.indexOf('@');
        return trimmed.length() >= 3 && trimmed.length() <= 320 && at > 0
            && at == trimmed.lastIndexOf('@') && at < trimmed.length() - 1;
    }

    /** A non-reversible, non-secret-safe fingerprint. Never used as an identifier. */
    public static String fingerprint(String value) {
        if (value == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                digest.digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK spec", e);
        }
    }
}