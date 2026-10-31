package com.firstelement.channels.connector;

import com.firstelement.channels.api.ConnectorCapabilities;
import com.firstelement.channels.api.ConnectorCapability;

/**
 * The outbound pre-flight check, ported from
 * {@code checkOutboundText} in {@code packages/connectors/src/types.ts}.
 *
 * <h2>Ask the channel before acting</h2>
 *
 * <p>The legacy dispatcher called this before every send: "Ask the channel before acting rather
 * than assuming parity." Two failures it prevents:
 *
 * <ul>
 *   <li>a message longer than the platform accepts, which would otherwise become an opaque 400;</li>
 *   <li>a send outside the customer-service window on a channel that only allows approved
 *       templates there — which is the WhatsApp case, and the difference between "your message was
 *       too long" and "this conversation has been closed for four days".</li>
 * </ul>
 *
 * <p>It returns the reason rather than throwing so the caller can record it on the message row and
 * dead-letter the job, instead of the exception surfacing as a 500 to an employee who has already
 * pressed Send.
 */
public final class OutboundGuard {

    private OutboundGuard() {
    }

    /** Whether {@code text} may be sent on this channel right now. */
    public static Verdict check(ConnectorCapabilities capabilities, String text) {
        return check(capabilities, text, false);
    }

    /**
     * @param insideSessionWindow whether the customer has messaged within
     *                             {@code sessionWindowHours}; ignored by channels with no window
     */
    public static Verdict check(ConnectorCapabilities capabilities, String text,
                                boolean insideSessionWindow) {
        if (!capabilities.sendText()) {
            return Verdict.refused(ConnectorCapability.SEND_TEXT,
                capabilities.channel() + " cannot send text messages");
        }
        String body = text == null ? "" : text;
        if (body.length() > capabilities.maxTextLength()) {
            return Verdict.refused(ConnectorCapability.SEND_TEXT,
                "Message is " + body.length() + " characters; " + capabilities.channel()
                    + " allows " + capabilities.maxTextLength());
        }
        if (capabilities.sessionWindowHours() != null && !insideSessionWindow
            && capabilities.requiresTemplateOutsideSession()) {
            return Verdict.refused(ConnectorCapability.REQUIRES_TEMPLATE_OUTSIDE_SESSION,
                capabilities.channel() + " allows only approved templates outside the "
                    + capabilities.sessionWindowHours() + "-hour customer-service window; this "
                    + "conversation is outside it");
        }
        return Verdict.permitted();
    }

    /** The outcome of a pre-flight check. */
    public record Verdict(boolean allowed, ConnectorCapability refusedCapability, String reason) {

        public static Verdict permitted() {
            return new Verdict(true, null, null);
        }

        public static Verdict refused(ConnectorCapability capability, String reason) {
            return new Verdict(false, capability, reason);
        }
    }
}