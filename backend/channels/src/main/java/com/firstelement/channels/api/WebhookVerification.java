package com.firstelement.channels.api;

/**
 * The outcome of verifying an inbound webhook.
 *
 * <h2>Why this is not a boolean</h2>
 *
 * <p>The legacy controller recorded one thing on failure: "Webhook signature verification failed".
 * That is not enough to fix anything. The operator's actual question is which of four situations
 * they are in:
 *
 * <ol>
 *   <li>the channel does not sign at all (the development Mock endpoint) — nothing to fix;</li>
 *   <li>the signature header was absent — usually a misconfigured proxy or a scanner;</li>
 *   <li>the digest did not match — the app secret is wrong, or the body was re-serialized;</li>
 *   <li>the channel's credential is not configured — the connection was created without a
 *       secret.</li>
 * </ol>
 *
 * <p>Each gets its own {@link Reason} so the connector panel can say something useful, and none of
 * them is ever returned to the caller: a probe must not learn which connection ids exist.
 */
public record WebhookVerification(boolean verified, Reason reason, String detail) {

    /** Why verification did or did not succeed. */
    public enum Reason {
        /** The signature or secret matched. */
        VERIFIED,
        /** This channel declares no signature scheme (the development Mock endpoint). */
        NOT_SIGNED,
        /** The expected header was absent. */
        MISSING_HEADER,
        /** The header was present but did not match. */
        SIGNATURE_MISMATCH,
        /** The connection has no credential to verify against. */
        NOT_CONFIGURED,
        /** The raw body was absent, so there was nothing to verify. */
        MISSING_BODY
    }

    public static WebhookVerification success() {
        return new WebhookVerification(true, Reason.VERIFIED, null);
    }

    public static WebhookVerification failed(Reason reason, String detail) {
        return new WebhookVerification(false, reason, detail);
    }

    /** A log-safe one-liner. Never contains the secret or the raw body. */
    public String auditLabel() {
        return reason.name() + (detail == null || detail.isBlank() ? "" : " (" + detail + ")");
    }
}