package com.firstelement.channels.connector.telegram;

import com.firstelement.channels.api.WebhookVerification;
import com.firstelement.channels.connector.meta.MetaSignatureVerifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Telegram webhook verification, ported from {@code TelegramConnector.verifyWebhook}.
 *
 * <h2>Telegram does not sign the payload</h2>
 *
 * <p>The legacy comment says it exactly: "Telegram does not sign payloads, so this shared token is
 * the whole authentication story — which is why it is compared in constant time and why the webhook
 * must be HTTPS." FR-4.3 requires "Telegram secrets using constant-time comparisons", and this is
 * that comparison.
 *
 * <h3>Why the length check is not a weakness here</h3>
 *
 * <p>The secret is sent in full on every request, so its length is not a secret to leak — an
 * attacker who guesses the length learns nothing they did not already know. The bytes themselves
 * are compared in constant time, which is what makes a wrong secret indistinguishable from a right
 * one except by its truth value.
 *
 * <h3>There is no hex decode and no prefix</h3>
 *
 * <p>The token is an opaque shared string, not a digest. Truncating or normalising it would
 * introduce acceptance of a token the operator never configured.
 */
public final class TelegramSecretVerifier {

    static final String HEADER = "X-Telegram-Bot-Api-Secret-Token";

    private TelegramSecretVerifier() {
    }

    /**
     * @param webhookSecret the connection's configured secret; blank means the connection is
     *                      unusable for inbound delivery and is reported as such rather than
     *                      accepted
     */
    public static WebhookVerification verify(Map<String, String> headers, String webhookSecret) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            return WebhookVerification.failed(WebhookVerification.Reason.NOT_CONFIGURED,
                "the connection has no Telegram webhook secret");
        }
        String provided = MetaSignatureVerifier.header(headers, HEADER);
        if (provided == null || provided.isBlank()) {
            return WebhookVerification.failed(WebhookVerification.Reason.MISSING_HEADER,
                HEADER + " was absent");
        }
        byte[] providedBytes = provided.getBytes(StandardCharsets.UTF_8);
        byte[] expectedBytes = webhookSecret.getBytes(StandardCharsets.UTF_8);
        if (providedBytes.length != expectedBytes.length) {
            return WebhookVerification.failed(WebhookVerification.Reason.SIGNATURE_MISMATCH,
                "the shared secret did not match");
        }
        return MessageDigest.isEqual(providedBytes, expectedBytes)
            ? WebhookVerification.success()
            : WebhookVerification.failed(WebhookVerification.Reason.SIGNATURE_MISMATCH,
                "the shared secret did not match");
    }
}