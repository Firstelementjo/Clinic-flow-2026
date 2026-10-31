package com.firstelement.airag;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstelement.airag.provider.SecretScrubber;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The credential scrubber, on its own.
 *
 * <h2>Why it has its own suite when it is three regexes</h2>
 *
 * <p>Because it is the last thing between a provider's echo of a failed request and a persisted column, a
 * log line and an audit metadata field, and because the order in which its patterns are applied is not
 * visible in the pattern list. A reorder that still "scrubs something" passes any assertion that only checks
 * that <em>some</em> mask appeared. These tests assert that the <b>secret</b> is gone.
 *
 * <h2>What it does not claim</h2>
 *
 * <p>It removes credential <em>shapes</em>. It does not remove customer content — that is
 * {@code AiRedactionEngine}'s job, tested separately. Mixing the two would produce a scrubber that is
 * confident about keys and hopeful about names.
 */
class SecretScrubberTest {

    @Nested
    @DisplayName("credentials")
    class Credentials {

        @Test
        @DisplayName("a key-shaped assignment is masked IN FULL, name included")
        void assignmentIsRemoved() {
            String scrubbed = SecretScrubber.scrubAndBound("api_key=sk-live-AAAAAAAAAAAAAAAAAAAAAAAA");

            // The whole assignment goes, name and value. A preserved prefix is a partial secret -- the
            // leading characters of a key are part of the key -- and a uniform mask is what makes
            // "containsOnlyOnce(MASK)" assertable. The surrounding provider text is what names the
            // credential; looksLikeSecret answers the binary question on the read side.
            assertThat(scrubbed).isEqualTo(SecretScrubber.MASK);
        }

        @Test
        @DisplayName("Authorization: Bearer <token> is removed IN FULL, including the token")
        void bearerTokenIsRemovedInFull() {
            // The regression. Run in the other order, the assignment pattern consumes only the word
            // "Bearer" -- its value is one \S+ -- the word is masked, and the orphaned token survives into
            // ai_runs."errorMessage" intact. This is the most common credential shape in a provider's echo
            // of a failed request, which is why the order in SecretScrubber is stated and this is pinned.
            String scrubbed = SecretScrubber.scrubAndBound("Authorization: Bearer aaaabbbbccccdddd");

            assertThat(scrubbed).doesNotContain("aaaabbbbccccdddd");
        }

        @Test
        @DisplayName("a provider's whole echoed request is scrubbed, not just its key")
        void wholeEchoedRequestIsScrubbed() {
            String scrubbed = SecretScrubber.scrubAndBound(
                "401 unauthorized for api_key=sk-live-AAAAAAAAAAAAAAAAAAAAAAAA with "
                    + "Authorization: Bearer aaaabbbbccccdddd");

            assertThat(scrubbed)
                .doesNotContain("sk-live-AAAAAAAAAAAAAAAAAAAAAAAA")
                .doesNotContain("aaaabbbbccccdddd")
                .contains("unauthorized");
        }

        @Test
        @DisplayName("a token with no recognised prefix is caught by its shape")
        void unknownHeaderIsCaughtByShape() {
            // A header allow-list would let a new provider's key through on the day it ships. Entropy is
            // what catches it today, so this is asserted for a header name this build has never heard of.
            String scrubbed = SecretScrubber.scrubAndBound("x-vendor-internal-token: Zz09Qq77Ww11Nn44Rr55");

            assertThat(scrubbed).doesNotContain("Zz09Qq77Ww11Nn44Rr55");
        }

        @Test
        @DisplayName("a long digit run is removed, because a key is often all digits")
        void longDigitRunIsRemoved() {
            String scrubbed = SecretScrubber.scrubAndBound("session 9182736455463728190 expired");

            assertThat(scrubbed).doesNotContain("9182736455463728190");
        }

        @Test
        @DisplayName("the removal is one identical mask, so its length reveals nothing")
        void maskIsUniform() {
            String scrubbed = SecretScrubber.scrubAndBound(
                "api_key=sk-live-AAAAAAAAAAAAAAAAAAAAAAAA and Bearer aaaabbbbccccdddd");

            assertThat(scrubbed).doesNotContain("sk-live-AAAAAAAAAAAAAAAAAAAAAAAA")
                .doesNotContain("aaaabbbbccccdddd")
                .containsOnlyOnce(SecretScrubber.MASK + " and " + SecretScrubber.MASK);
        }

        @Test
        @DisplayName("looksLikeSecret agrees with what scrubbing removes")
        void looksLikeSecretAgreesWithScrubbing() {
            assertThat(SecretScrubber.looksLikeSecret("api_key=sk-live-AAAAAAAAAAAAAAAAAAAAAAAA")).isTrue();
            assertThat(SecretScrubber.looksLikeSecret("Authorization: Bearer aaaabbbbccccdddd")).isTrue();
            assertThat(SecretScrubber.looksLikeSecret("503 Service Unavailable")).isFalse();
            assertThat(SecretScrubber.looksLikeSecret("")).isFalse();
            assertThat(SecretScrubber.looksLikeSecret(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("what it leaves alone")
    class Benign {

        @Test
        @DisplayName("an ordinary diagnostic survives, because a scrubbed diagnostic is no diagnostic")
        void ordinaryDiagnosticsSurvive() {
            assertThat(SecretScrubber.scrubAndBound("503 Service Unavailable"))
                .isEqualTo("503 Service Unavailable");
        }

        @Test
        @DisplayName("a model name survives, because it is short")
        void modelNamesSurvive() {
            assertThat(SecretScrubber.scrubAndBound("model mock-deterministic-v1 rejected"))
                .isEqualTo("model mock-deterministic-v1 rejected");
        }

        @Test
        @DisplayName("a UUID is masked too, because it is indistinguishable from a key by shape")
        void uuidIsMaskedDeliberately() {
            // Asserted as a fact rather than left unremarked: the entropy rule is a blunt instrument and a
            // 36-character UUID trips it. Masking here fails towards the mask, and nothing is actually lost
            // -- the run id is in the trace row, not only in the provider's prose. A scrubber that carved
            // UUIDs out of the entropy rule would be a carve-out for a shape an attacker can also produce.
            String scrubbed = SecretScrubber.scrubAndBound("run 8f14e45f-ceea-467a-9ba3-1f2c0d4a1b77");

            assertThat(scrubbed).isEqualTo("run " + SecretScrubber.MASK);
        }

        @Test
        @DisplayName("Arabic text survives, because this is an Arabic-first product")
        void arabicSurvives() {
            String scrubbed = SecretScrubber.scrubAndBound("تعذر الاتصال بمزود الخدمة");

            assertThat(scrubbed).isEqualTo("تعذر الاتصال بمزود الخدمة");
        }

        @Test
        @DisplayName("an audit timestamp survives, so a scrubbed log line is still a usable log line")
        void timestampsSurvive() {
            assertThat(SecretScrubber.scrubAndBound("2026-10-06 00:22:24.481 failed"))
                .isEqualTo("2026-10-06 00:22:24.481 failed");
        }
    }

    @Nested
    @DisplayName("normalisation and bounds")
    class Shape {

        @Test
        @DisplayName("a null or blank value becomes null, so 'no reason' and 'blank reason' stay distinct")
        void nullAndBlankBecomeNull() {
            assertThat(SecretScrubber.scrubAndBound(null)).isNull();
            assertThat(SecretScrubber.scrubAndBound("")).isNull();
            assertThat(SecretScrubber.scrubAndBound("   \t ")).isNull();
        }

        @Test
        @DisplayName("a provider message cannot forge a log line by embedding a newline")
        void newlinesCannotForgeALogLine() {
            String scrubbed = SecretScrubber.scrubAndBound(
                "provider failed\nINFO admin login succeeded for user root");

            assertThat(scrubbed).doesNotContain("\n");
        }

        @Test
        @DisplayName("a message longer than the bound is truncated, so it cannot become a copy of a prompt")
        void longMessagesAreBounded() {
            String scrubbed = SecretScrubber.scrubAndBound("س ".repeat(1_000));

            assertThat(scrubbed.length()).isLessThanOrEqualTo(SecretScrubber.MAX_LENGTH + 1);
            assertThat(scrubbed).endsWith("…");
        }

        @Test
        @DisplayName("a fingerprint is stable, and says nothing about the value")
        void fingerprintIsStable() {
            String first = SecretScrubber.fingerprint("أجب من المصادر فقط");
            String second = SecretScrubber.fingerprint("أجب من المصادر فقط");

            assertThat(first).isEqualTo(second);
            assertThat(first).isNotEqualTo(SecretScrubber.fingerprint("أجب من مصادر أخرى"));
            assertThat(first).doesNotContain("أجب");
            assertThat(SecretScrubber.fingerprint(null)).isNull();
        }

        @Test
        @DisplayName("scrubbing is idempotent, so a value already scrubbed does not drift")
        void scrubbingIsIdempotent() {
            String once = SecretScrubber.scrubAndBound("Authorization: Bearer aaaabbbbccccdddd");

            assertThat(SecretScrubber.scrubAndBound(once)).isEqualTo(once);
        }
    }
}
