package com.firstelement.airag.policy;

/**
 * The one exception this module throws for a refused run.
 *
 * <h2>Why the message is not the reason</h2>
 *
 * <p>{@link #message()} is fixed and {@link #code()} is a {@link AiRefusalReason} name. Everything a
 * developer or an operator needs is in the type and the code; everything a customer must never see is
 * absent by construction. A refusal message that interpolated the offending value would put customer
 * content into an exception, and an exception message reaches logs, metrics labels and error responses —
 * three places where content cannot be taken back out.
 *
 * <p>{@link #detail()} is the escape hatch for diagnosability and is scrubbed at construction. It is
 * carried separately from the message precisely so a caller that renders {@code getMessage()} to a user
 * cannot accidentally include it.
 */
public class AiRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final AiRefusalReason reason;
    private final String detail;

    public AiRefusedException(AiRefusalReason reason, String detail) {
        this(reason, detail, null);
    }

    public AiRefusedException(AiRefusalReason reason, String detail, Throwable cause) {
        super("AI run refused: " + (reason == null ? "UNSPECIFIED" : reason.name()), cause);
        this.reason = reason == null ? AiRefusalReason.POLICY_MISSING : reason;
        this.detail = com.firstelement.airag.provider.SecretScrubber.scrubAndBound(detail);
    }

    public static AiRefusedException of(AiRefusalReason reason) {
        return new AiRefusedException(reason, null);
    }

    public static AiRefusedException of(AiRefusalReason reason, String detail) {
        return new AiRefusedException(reason, detail);
    }

    public AiRefusalReason reason() {
        return reason;
    }

    /** The reason code, which is what is persisted and grouped by. */
    public String code() {
        return reason.name();
    }

    /** A scrubbed, bounded diagnostic. Never customer content, never a credential. */
    public String detail() {
        return detail;
    }
}