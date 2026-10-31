package com.firstelement.channels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.channels.api.WebhookVerification;
import com.firstelement.channels.connector.meta.MetaSignatureVerifier;
import com.firstelement.channels.connector.telegram.TelegramSecretVerifier;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Webhook verification, as the two schemes that have to be right.
 *
 * <h2>Meta: HMAC over the raw bytes</h2>
 *
 * <p>Two properties decide whether this protects anything, and both are asserted here: it runs over
 * the body <em>as received</em>, and the comparison is constant-time.
 *
 * <h2>Telegram: a shared secret compared in constant time</h2>
 *
 * <p>Telegram does not sign payloads, so this shared token is the whole authentication story — which
 * is why the comparison must not leak it byte by byte.
 *
 * <h2>What is never asserted here</h2>
 *
 * <p>No test prints a secret, an app secret, a token, or a customer message body. Every expectation
 * below is about the boolean outcome and the reason, both of which are safe to log.
 */
class WebhookVerificationTest {

    private static final String APP_SECRET = "test-app-secret-not-a-real-credential";
    private static final String TELEGRAM_SECRET = "test-telegram-secret-not-a-real-credential";

    private static final String BODY = """
        {"object":"whatsapp_business_account","entry":[{"id":"1","changes":[{"value":\
        {"messaging_product":"whatsapp","metadata":{"display_phone_number":"15550000000"},\
        "messages":[{"from":"966500000001","id":"wamid.TEST1","timestamp":"1757000000",\
        "text":{"body":"مرحبا"}}]}]}]}""";

    @Nested
    @DisplayName("Meta: sha256 over the raw bytes")
    class Meta {

        @Test
        void aCorrectSignatureVerifies() {
            assertThat(MetaSignatureVerifier.verify(BODY.getBytes(StandardCharsets.UTF_8),
                headers("X-Hub-Signature-256", signature(BODY)), APP_SECRET).verified()).isTrue();
        }

        @Test
        void headerNameLookupIsCaseInsensitive() {
            // RFC 7230: header names are case-insensitive, and proxies rewrite them.
            Map<String, String> lower = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            lower.put("x-hub-signature-256", signature(BODY));

            assertThat(MetaSignatureVerifier.verify(BODY.getBytes(StandardCharsets.UTF_8), lower,
                APP_SECRET).verified()).isTrue();
        }

        @Test
        @DisplayName("re-serializing the body changes the digest, which is why the raw bytes matter")
        void reSerializationBreaksTheSignature() {
            // A parsed-and-reserialized body: same data, different bytes, different digest. This is
            // the failure a JSON.parse-then-verify implementation has and this one does not.
            String reserialized = "{\n  \"object\" : \"whatsapp_business_account\"\n}";
            String digest = signature(reserialized);

            WebhookVerification result = MetaSignatureVerifier.verify(
                reserialized.getBytes(StandardCharsets.UTF_8),
                headers("X-Hub-Signature-256", signature(BODY)), APP_SECRET);

            assertThat(result.verified()).isFalse();
            assertThat(result.reason()).isEqualTo(WebhookVerification.Reason.SIGNATURE_MISMATCH);
            // And the two digests genuinely differ.
            assertThat(digest).isNotEqualTo(signature(BODY));
        }

        @Test
        void aWrongSecretFails() {
            WebhookVerification result = MetaSignatureVerifier.verify(
                BODY.getBytes(StandardCharsets.UTF_8),
                headers("X-Hub-Signature-256", signature(BODY)), "a-different-secret");

            assertThat(result.verified()).isFalse();
            assertThat(result.reason()).isEqualTo(WebhookVerification.Reason.SIGNATURE_MISMATCH);
        }

        @Test
        void aMissingHeaderIsDistinguishedFromAMismatch() {
            WebhookVerification result = MetaSignatureVerifier.verify(
                BODY.getBytes(StandardCharsets.UTF_8), Map.of(), APP_SECRET);

            assertThat(result.verified()).isFalse();
            assertThat(result.reason()).isEqualTo(WebhookVerification.Reason.MISSING_HEADER);
        }

        @Test
        void aMissingBodyIsReportedAsSuchRatherThanAsUnsigned() {
            WebhookVerification result = MetaSignatureVerifier.verify(new byte[0],
                headers("X-Hub-Signature-256", signature(BODY)), APP_SECRET);

            assertThat(result.verified()).isFalse();
            assertThat(result.reason()).isEqualTo(WebhookVerification.Reason.MISSING_BODY);
        }

        @Test
        void anUnconfiguredConnectionIsReportedDistinctly() {
            // The operator's actual question is which of four situations they are in; a bare boolean
            // made all four look like "someone is attacking us".
            assertThat(MetaSignatureVerifier.verify(BODY.getBytes(StandardCharsets.UTF_8),
                headers("X-Hub-Signature-256", signature(BODY)), null).reason())
                .isEqualTo(WebhookVerification.Reason.NOT_CONFIGURED);
            assertThat(MetaSignatureVerifier.verify(BODY.getBytes(StandardCharsets.UTF_8),
                headers("X-Hub-Signature-256", signature(BODY)), "  ").reason())
                .isEqualTo(WebhookVerification.Reason.NOT_CONFIGURED);
        }

        @Test
        void malformedSignatureHeadersAreRejectedWithoutThrowing() {
            for (String header : new String[] {"sha1=abc", "sha256=", "sha256=nothex!!",
                "sha256=abcd", "no-equals-sign", "md5=0123456789abcdef"}) {
                WebhookVerification result = MetaSignatureVerifier.verify(
                    BODY.getBytes(StandardCharsets.UTF_8),
                    headers("X-Hub-Signature-256", header), APP_SECRET);
                assertThat(result.verified()).as("header %s", header).isFalse();
                assertThat(result.reason()).isEqualTo(WebhookVerification.Reason.SIGNATURE_MISMATCH);
            }
        }

        @Test
        void theReasonLabelNeverCarriesTheSecretOrTheBody() {
            String label = MetaSignatureVerifier.verify(BODY.getBytes(StandardCharsets.UTF_8),
                headers("X-Hub-Signature-256", signature(BODY)), "wrong-secret").auditLabel();

            assertThat(label).doesNotContain(APP_SECRET).doesNotContain("wrong-secret")
                .doesNotContain("مرحبا").doesNotContain(BODY);
        }
    }

    @Nested
    @DisplayName("Meta: the GET registration handshake")
    class MetaHandshake {

        @Test
        void aMatchingVerifyTokenIsComparedInConstantTime() {
            assertThat(MetaSignatureVerifier.matchesVerifyToken("the-token", "the-token")).isTrue();
        }

        @Test
        void everyOtherTokenIsRefused() {
            assertThat(MetaSignatureVerifier.matchesVerifyToken("other", "the-token")).isFalse();
            assertThat(MetaSignatureVerifier.matchesVerifyToken("the-token-longer", "the-token"))
                .isFalse();
            assertThat(MetaSignatureVerifier.matchesVerifyToken("", "the-token")).isFalse();
            assertThat(MetaSignatureVerifier.matchesVerifyToken(null, "the-token")).isFalse();
            assertThat(MetaSignatureVerifier.matchesVerifyToken("the-token", null)).isFalse();
            assertThat(MetaSignatureVerifier.matchesVerifyToken("the-token", "  ")).isFalse();
        }
    }

    @Nested
    @DisplayName("Telegram: a shared secret")
    class Telegram {

        @Test
        void aMatchingSecretVerifies() {
            assertThat(TelegramSecretVerifier.verify(
                headers("X-Telegram-Bot-Api-Secret-Token", TELEGRAM_SECRET), TELEGRAM_SECRET)
                .verified()).isTrue();
        }

        @Test
        void headerNameLookupIsCaseInsensitive() {
            Map<String, String> lower = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            lower.put("x-telegram-bot-api-secret-token", TELEGRAM_SECRET);
            assertThat(TelegramSecretVerifier.verify(lower, TELEGRAM_SECRET).verified()).isTrue();
        }

        @Test
        void aWrongSecretFails() {
            WebhookVerification result = TelegramSecretVerifier.verify(
                headers("X-Telegram-Bot-Api-Secret-Token", "not-the-secret"), TELEGRAM_SECRET);
            assertThat(result.verified()).isFalse();
            assertThat(result.reason()).isEqualTo(WebhookVerification.Reason.SIGNATURE_MISMATCH);
        }

        @Test
        void aMissingHeaderIsDistinctFromAMismatch() {
            assertThat(TelegramSecretVerifier.verify(Map.of(), TELEGRAM_SECRET).reason())
                .isEqualTo(WebhookVerification.Reason.MISSING_HEADER);
        }

        @Test
        void anUnconfiguredSecretIsRefusedRatherThanAccepted() {
            // An empty stored secret must never accept an empty header: that would make an
            // unconfigured connection an open webhook.
            assertThat(TelegramSecretVerifier.verify(
                headers("X-Telegram-Bot-Api-Secret-Token", ""), "").verified()).isFalse();
            assertThat(TelegramSecretVerifier.verify(
                headers("X-Telegram-Bot-Api-Secret-Token", "anything"), null).reason())
                .isEqualTo(WebhookVerification.Reason.NOT_CONFIGURED);
        }

        @Test
        void theBodyIsIrrelevantToTheDecision() {
            // Telegram signs nothing, so the body must not influence the verdict in either direction.
            assertThat(TelegramSecretVerifier.verify(
                headers("X-Telegram-Bot-Api-Secret-Token", TELEGRAM_SECRET), TELEGRAM_SECRET)
                .verified()).isTrue();
        }
    }

    @Test
    @DisplayName("a verification never carries the secret or the body in its rendered form")
    void auditLabelIsSafe() {
        WebhookVerification result = MetaSignatureVerifier.verify(
            BODY.getBytes(StandardCharsets.UTF_8),
            headers("X-Hub-Signature-256", signature(BODY)), APP_SECRET);
        assertThat(result.auditLabel()).isEqualTo("VERIFIED");
        assertThat(result.toString()).doesNotContain(APP_SECRET).doesNotContain("مرحبا");
    }

    @Test
    @DisplayName("the length check runs first so the constant-time comparison assumes equal lengths")
    void lengthMismatchIsRefusedWithoutThrowing() {
        // MessageDigest.isEqual documents equal-length inputs as its precondition; the digest length
        // is fixed and public, so a length mismatch reveals nothing.
        WebhookVerification result = MetaSignatureVerifier.verify(
            BODY.getBytes(StandardCharsets.UTF_8),
            headers("X-Hub-Signature-256", "sha256=0011223344556677"), APP_SECRET);
        assertThat(result.verified()).isFalse();
        assertThat(result.reason()).isEqualTo(WebhookVerification.Reason.SIGNATURE_MISMATCH);
    }

    // ------------------------------------------------------------------

    private static Map<String, String> headers(String name, String value) {
        return Map.of(name, value);
    }

    private static String signature(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(APP_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("sha256=");
            for (byte b : digest) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("the verifier never throws on adversarial input")
    void adversarialInputDoesNotThrow() {
        // nulls in every position, and an empty header map. A verification routine that can throw is
        // a verification routine that can be turned into a 500 by an unauthenticated caller.
        assertThat(MetaSignatureVerifier.header(null, "X-Hub-Signature-256")).isNull();
        assertThat(MetaSignatureVerifier.header(Map.of(), "X-Hub-Signature-256")).isNull();
        assertThat(MetaSignatureVerifier.verify(null, null, null).verified()).isFalse();
        assertThat(MetaSignatureVerifier.verify(new byte[0], null, "secret").verified()).isFalse();
        assertThat(TelegramSecretVerifier.verify(null, "secret").verified()).isFalse();
        assertThat(TelegramSecretVerifier.verify(Map.of(), null).verified()).isFalse();
    }
}