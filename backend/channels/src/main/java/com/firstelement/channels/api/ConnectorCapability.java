package com.firstelement.channels.api;

/**
 * One capability of a channel, as a closed vocabulary.
 *
 * <p>Declared rather than derived, because the UI and the outbound path must <em>ask</em> instead
 * of assuming parity: "a composer that silently allows a 5,000-character WhatsApp message, or an
 * agent that replies four days into a closed session window, fails at the platform boundary where
 * the failure is hardest to explain to a customer."
 */
public enum ConnectorCapability {

    SEND_TEXT,
    SEND_MEDIA,
    MARK_AS_READ,
    EDIT_MESSAGE,
    DELETE_MESSAGE,

    /** The session window is enforced: outside it only a pre-approved template may be sent. */
    REQUIRES_TEMPLATE_OUTSIDE_SESSION,

    /** Inbound payloads are authenticated by a signature or a shared secret. */
    VERIFIES_WEBHOOK_SIGNATURE
}