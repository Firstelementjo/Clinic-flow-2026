package com.firstelement.security.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TOTP, because it is the second factor the whole platform tier rests on.
 *
 * <p>The RFC 6238 test vectors are the reason this is not a hand-rolled check: an implementation
 * that agrees with itself but not with the specification would pass a self-consistent test and reject
 * every authenticator app.
 */
class TotpVerifierTest {

    /** RFC 4226 / RFC 6238 reference values for the ASCII seed "12345678901234567890". */
    private static final String RFC_SEED_BASE32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    private static final Instant T0 = Instant.ofEpochSecond(0L);

    @Test
    @DisplayName("generates a fresh secret each time")
    void secretsAreNotReused() {
        assertThat(TotpVerifier.generateSecret())
                .isNotEqualTo(TotpVerifier.generateSecret())
                .hasSizeGreaterThanOrEqualTo(32);
    }

    @Test
    @DisplayName("accepts the RFC 6238 reference codes")
    void matchesTheSpecification() {
        // RFC 6238 appendix B. The argument is the counter, not a timestamp: the specification
        // divides T by the step itself, so T=59 with X=30 is counter 1, not 59/30 computed here.
        // Getting that wrong would make this test agree with a broken implementation.
        assertThat(TotpVerifier.codeFor(seed(), 1L))
                .as("T=59, X=30 -> counter 1")
                .isEqualTo("287082");
        assertThat(TotpVerifier.codeFor(seed(), 0L))
                .as("T=0, X=30 -> counter 0 (RFC 4226 vector)")
                .isEqualTo("755224");
        assertThat(TotpVerifier.codeFor(seed(), 37_037_036L))
                .as("T=1111111109, X=30 -> counter 37037036")
                .isEqualTo("081804");
        assertThat(TotpVerifier.codeFor(seed(), 66_666_666L))
                .as("T=2000000000, X=30 -> counter 66666666")
                .isEqualTo("279037");
    }

    @Test
    @DisplayName("verifies a code generated for the current window")
    void acceptsItsOwnCode() {
        String secret = TotpVerifier.generateSecret();
        byte[] raw = decode(secret);
        long counter = NOW_EPOCH_SECONDS() / 30L;
        String code = TotpVerifier.codeFor(raw, counter);

        assertThat(TotpVerifier.verify(secret, code, NOW())).isTrue();
    }

    @Test
    @DisplayName("tolerates one step of clock skew either way, and refuses two")
    void windowIsExactlyOneStep() {
        String secret = TotpVerifier.generateSecret();
        byte[] raw = decode(secret);
        long counter = NOW_EPOCH_SECONDS() / 30L;

        assertThat(TotpVerifier.verify(secret, TotpVerifier.codeFor(raw, counter + 1), NOW()))
                .as("a device a step fast still authenticates")
                .isTrue();
        assertThat(TotpVerifier.verify(secret, TotpVerifier.codeFor(raw, counter - 1), NOW()))
                .as("a device a step slow still authenticates")
                .isTrue();
        assertThat(TotpVerifier.verify(secret, TotpVerifier.codeFor(raw, counter + 2), NOW()))
                .as("two steps is outside the window")
                .isFalse();
    }

    @Test
    @DisplayName("refuses malformed input without throwing")
    void malformedInputIsAFalseNotAnException() {
        String secret = TotpVerifier.generateSecret();

        // An exception here would let a caller distinguish "bad code" from "bad configuration",
        // and would turn a typo into a 500 on the login endpoint.
        assertThat(TotpVerifier.verify(secret, null, NOW())).isFalse();
        assertThat(TotpVerifier.verify(secret, "", NOW())).isFalse();
        assertThat(TotpVerifier.verify(secret, "abcdef", NOW())).isFalse();
        assertThat(TotpVerifier.verify(secret, "12345", NOW())).isFalse();
        assertThat(TotpVerifier.verify("not base32 at all!", "123456", NOW())).isFalse();
        assertThat(TotpVerifier.verify(null, "123456", NOW())).isFalse();
    }

    @Test
    @DisplayName("a code for another secret is refused")
    void codesDoNotTransferBetweenSecrets() {
        String code = TotpVerifier.codeFor(decode(TotpVerifier.generateSecret()),
                NOW_EPOCH_SECONDS() / 30L);
        assertThat(TotpVerifier.verify(TotpVerifier.generateSecret(), code, NOW())).isFalse();
    }

    // ------------------------------------------------------------------

    private static byte[] seed() {
        return decode(RFC_SEED_BASE32);
    }

    /** Mirrors the verifier's own base32 decoding, kept here so the test does not reach into it. */
    private static byte[] decode(String encoded) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        String clean = encoded.trim().toUpperCase(java.util.Locale.ROOT).replace("=", "");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int bits = 0;
        int value = 0;
        for (int i = 0; i < clean.length(); i++) {
            int index = alphabet.indexOf(clean.charAt(i));
            if (index < 0) {
                throw new IllegalArgumentException("not base32");
            }
            value = (value << 5) | index;
            bits += 5;
            if (bits >= 8) {
                out.write((value >>> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    private static Instant NOW() {
        return Instant.ofEpochSecond(1_700_000_000L);
    }

    private static long NOW_EPOCH_SECONDS() {
        return 1_700_000_000L;
    }
}