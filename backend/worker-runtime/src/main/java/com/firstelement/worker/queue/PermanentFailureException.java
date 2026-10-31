package com.firstelement.worker.queue;

/**
 * A failure the handler knows is permanent, carrying the label to record.
 *
 * <h2>Why the SPI is an exception and not a return type</h2>
 *
 * <p>A handler deep inside a call stack — a connector adapter that discovers the channel cannot
 * send media, a provider parser that finds no message — has no convenient way to hand a decision
 * back up without threading a result type through every signature. Throwing this is the honest
 * signal, and it is <em>only</em> for known-permanent failures: anything else is a bug and must
 * stay a plain exception so {@link RetryClassifier} can treat it as one.
 *
 * <p>{@link RetryClassifier} maps it to a non-retryable decision with the supplied
 * {@code retryClass}/{@code errorCode}, which is what lands in
 * {@code job_attempts} and in {@code job_dead_letters."reason"}.
 */
public class PermanentFailureException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String retryClass;
    private final String errorCode;

    public PermanentFailureException(String retryClass, String errorCode, String message) {
        super(message);
        this.retryClass = retryClass;
        this.errorCode = errorCode;
    }

    public PermanentFailureException(String retryClass, String errorCode, String message,
                                     Throwable cause) {
        super(message, cause);
        this.retryClass = retryClass;
        this.errorCode = errorCode;
    }

    /** The stable label recorded in {@code job_attempts."retryClass"}. */
    public String retryClass() {
        return retryClass;
    }

    /** The short code recorded in {@code job_attempts."errorCode"}. */
    public String errorCode() {
        return errorCode;
    }
}