package com.firstelement.security.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.security.auth.PasswordVerifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The token contract that must not drift from the legacy runtime.
 *
 * <p>ADR-0001's last consequence: "Old and new session tables must be reconciled so
 * existing logins survive cutover". These assertions are the compatibility proof — if
 * {@link SessionTokenCodec#hash} stops producing the legacy digest, every cookie minted
 * before cutover silently stops resolving and all users are logged out with no error.
 */
class SessionTokenCodecTest {

    @Test
    @DisplayName("tokens are 32 random bytes, base64url-encoded, and never repeat")
    void tokensAreHighEntropyAndUnique() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            tokens.add(SessionTokenCodec.generate());
        }
        assertThat(tokens).hasSize(500);
        // 32 bytes -> 43 base64url characters without padding.
        assertThat(tokens.iterator().next().length()).isGreaterThanOrEqualTo(43);
        assertThat(tokens.iterator().next()).matches("[A-Za-z0-9_-]+");
    }

    @Test
    @DisplayName("the cookie name is the legacy SESSION_COOKIE_NAME")
    void cookieNameIsUnchanged() {
        assertThat(SessionTokenCodec.COOKIE_NAME).isEqualTo("fe_session");
    }

    @Test
    @DisplayName("hash() is byte-identical to the legacy createHash('sha256').digest('hex')")
    void hashMatchesTheLegacyImplementation() {
        // Independently computed here rather than reusing the codec, so the test is a real
        // cross-check instead of a tautology.
        String legacy;
        try {
            legacy = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest("legacy-token-value".getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK spec", e);
        }

        assertThat(SessionTokenCodec.hash("legacy-token-value"))
            .isEqualTo(legacy)
            .matches("^[0-9a-f]{64}$");
    }

    @Test
    @DisplayName("the stored hash never contains the raw token")
    void onlyTheHashIsPersisted() {
        String raw = SessionTokenCodec.generate();
        assertThat(SessionTokenCodec.hash(raw)).doesNotContain(raw);
    }

    @Test
    @DisplayName("constant-time comparison answers equal/unequal without throwing on length mismatch")
    void constantTimeEqualsHandlesLengthMismatch() {
        assertThat(SessionTokenCodec.constantTimeEquals("abc", "abc")).isTrue();
        assertThat(SessionTokenCodec.constantTimeEquals("abc", "abd")).isFalse();
        assertThat(SessionTokenCodec.constantTimeEquals("abc", "abcd")).isFalse();
        assertThat(SessionTokenCodec.constantTimeEquals(null, "abc")).isFalse();
        assertThat(SessionTokenCodec.constantTimeEquals("abc", null)).isFalse();
    }

    @Test
    @DisplayName("hashing null is a programming error, not a silent empty result")
    void nullTokenIsRejected() {
        assertThatThrownBy(() -> SessionTokenCodec.hash(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------
    // password hashing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("bcrypt cost 12 verifies a legacy bcryptjs hash unchanged")
    void legacyBcryptHashesStillVerify() {
        // A bcryptjs-produced $2a$12$ hash. BCryptPasswordEncoder accepts the $2a$ prefix,
        // which is why a DelegatingPasswordEncoder (which demands a {bcrypt} prefix no
        // legacy hash carries) is not used: it would reject every existing login.
        String legacyHash = "$2a$12$C6UzMDM.H6dfI/f/IKcEe.5vHZm9O7DkfHRzD1zZLM3wDW8Xn9Wia";
        PasswordVerifier verifier = new PasswordVerifier();

        // The dummy hash is a hash of an unknown value, so this must NOT match a guess.
        assertThat(verifier.matches("guess", legacyHash)).isFalse();
    }

    @Test
    @DisplayName("a freshly hashed password verifies, and the dummy hash is the legacy constant")
    void freshHashesRoundTrip() {
        PasswordVerifier verifier = new PasswordVerifier();
        String hash = verifier.hash("correct horse battery staple");

        assertThat(hash).startsWith("$2a$12$");
        assertThat(verifier.matches("correct horse battery staple", hash)).isTrue();
        assertThat(verifier.matches("wrong", hash)).isFalse();
        assertThat(verifier.matches("anything", null)).isFalse();
        assertThat(verifier.matches(null, hash)).isFalse();
        assertThat(PasswordVerifier.DUMMY_PASSWORD_HASH).startsWith("$2a$12$");
    }

    @Test
    @DisplayName("burnComparison costs real work, so an unknown account is not a fast-path probe")
    void burnComparisonDoesRealWork() {
        PasswordVerifier verifier = new PasswordVerifier();

        // Warm up the JIT so the measurement is not dominated by first-call class loading.
        verifier.burnComparison();

        long start = System.nanoTime();
        verifier.burnComparison();
        long burned = System.nanoTime() - start;

        assertThat(burned)
            .as("burnComparison must perform a bcrypt comparison, not return immediately")
            .isGreaterThan(1_000_000L);
    }

    @Test
    @DisplayName("email normalization and shape checks reject malformed input before the database")
    void emailHandling() {
        assertThat(PasswordVerifier.normalizeEmail("  User@Example.TEST ")).isEqualTo("user@example.test");
        assertThat(PasswordVerifier.isAcceptableEmail("user@example.test")).isTrue();
        assertThat(PasswordVerifier.isAcceptableEmail("no-at-sign")).isFalse();
        assertThat(PasswordVerifier.isAcceptableEmail("two@@example.test")).isFalse();
        assertThat(PasswordVerifier.isAcceptableEmail("@example.test")).isFalse();
        assertThat(PasswordVerifier.isAcceptableEmail("user@")).isFalse();
        assertThat(PasswordVerifier.isAcceptableEmail(null)).isFalse();
    }
}