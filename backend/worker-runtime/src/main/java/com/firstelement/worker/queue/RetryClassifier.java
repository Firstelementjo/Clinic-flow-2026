package com.firstelement.worker.queue;

import com.firstelement.security.error.PolicyDeniedException;
import com.firstelement.security.job.TenantScopedExecutor;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.sql.SQLException;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLException;

/**
 * Turns a throwable or a provider status into a {@link RetryDecision}.
 *
 * <h2>The rule</h2>
 *
 * <p>Retry when the <em>same request could succeed later without anyone changing anything</em>;
 * do not retry when the request itself is wrong. A 400 from WhatsApp, a malformed payload, a
 * missing connection, and a bug in our own code are all deterministic failures: running them again
 * spends the tenant's attempt budget and delays the dead letter an operator is waiting for.
 *
 * <h2>Order matters</h2>
 *
 * <p>{@link PolicyDeniedException} and {@link TenantScopedExecutor.UnscopedJobException} are
 * checked before the generic runtime handling, so a job that lost its tenant scope is never
 * mistaken for a transient bug and retried. That is a security outcome, not a delivery outcome,
 * and the refusal is already on the security stream by the time it reaches here.
 */
public final class RetryClassifier {

    private RetryClassifier() {
    }

    /** Classify a throwable. Never throws; never returns null. */
    public static RetryDecision classify(Throwable error) {
        if (error == null) {
            return RetryDecision.permanent(RetryDecision.INTERNAL_ERROR, "NULL_ERROR",
                "A handler reported a failure with no throwable");
        }

        if (error instanceof PolicyDeniedException) {
            return RetryDecision.permanent(RetryDecision.SCOPE_REFUSED, "POLICY_DENIED",
                "The job's tenant scope does not authorize this operation; retrying cannot change "
                    + "that");
        }
        if (error instanceof TenantScopedExecutor.UnscopedJobException) {
            return RetryDecision.permanent(RetryDecision.SCOPE_REFUSED, "UNSCOPED_JOB",
                "The job ran without a TenantContext and was refused");
        }
        // The dispatcher wraps a handler's checked exception so it can cross the Supplier
        // boundary; unwrap before classifying so a provider IOException is not hidden behind a
        // generic wrapper and misfiled as an internal error.
        if (error.getClass().getName().endsWith("HandlerFailedException") && error.getCause() != null) {
            return classify(error.getCause());
        }
        if (error instanceof PermanentFailureException permanent) {
            return RetryDecision.permanent(permanent.retryClass(), permanent.errorCode(),
                permanent.getMessage());
        }
        if (error instanceof UnknownQueueException unknown) {
            return RetryDecision.permanent(RetryDecision.UNKNOWN_QUEUE, "UNKNOWN_QUEUE",
                "No handler is registered for event type '" + unknown.value() + "'");
        }
        if (error instanceof TimeoutException || error instanceof SocketTimeoutException
            || error instanceof HttpTimeoutException) {
            return RetryDecision.retryable(RetryDecision.TIMEOUT, "TIMEOUT",
                "The operation timed out; the same request may succeed later");
        }
        if (error instanceof SSLException) {
            // A TLS failure is usually a CA/configuration problem, which is deterministic, but it
            // is also indistinguishable from a truncated handshake by the time we see it. Retrying
            // is cheaper than silently losing a customer's message.
            return RetryDecision.retryable(RetryDecision.TRANSIENT_PROVIDER, "TLS",
                "The provider TLS connection failed");
        }
        if (error instanceof IOException) {
            return RetryDecision.retryable(RetryDecision.TRANSIENT_PROVIDER, "IO",
                "The provider connection failed before a response was received");
        }
        if (error instanceof SQLException sql) {
            String state = sql.getSQLState();
            // 08xxx connection exceptions, 40xxx transaction rollback / deadlock, 53xxx resource
            // shortage. Everything else (a syntax error, a constraint we violate) is a defect.
            if (state != null && (state.startsWith("08") || state.startsWith("40")
                || state.startsWith("53"))) {
                return RetryDecision.retryable(RetryDecision.DEPENDENCY_UNAVAILABLE, state,
                    "The database refused the work transiently (SQLSTATE " + state + ")");
            }
            return RetryDecision.permanent(RetryDecision.INTERNAL_ERROR,
                state == null ? "SQL" : state,
                "The database rejected the statement; this is a defect, not an outage");
        }
        if (error instanceof IllegalArgumentException || error instanceof IllegalStateException) {
            return RetryDecision.permanent(RetryDecision.INTERNAL_ERROR,
                error.getClass().getSimpleName(),
                "The handler rejected its own input; retrying would be a defect loop");
        }
        return RetryDecision.retryable(RetryDecision.INTERNAL_ERROR,
            error.getClass().getSimpleName(),
            "An unexpected failure; recorded for triage rather than silently dropped");
    }

    /** Classify a provider HTTP status, the way both Meta and Telegram behave. */
    public static RetryDecision classifyHttpStatus(int status, String summary) {
        if (status == 429) {
            return RetryDecision.retryable(RetryDecision.RATE_LIMITED, "429",
                "The provider rate-limited this connection" + suffix(summary));
        }
        if (status >= 500) {
            return RetryDecision.retryable(RetryDecision.TRANSIENT_PROVIDER, Integer.toString(status),
                "The provider returned a server error" + suffix(summary));
        }
        if (status == 401 || status == 403) {
            return RetryDecision.permanent(RetryDecision.AUTHENTICATION, Integer.toString(status),
                "The connector credential was rejected" + suffix(summary));
        }
        if (status >= 400) {
            return RetryDecision.permanent(RetryDecision.INVALID_REQUEST, Integer.toString(status),
                "The provider rejected the request" + suffix(summary));
        }
        return RetryDecision.retryable(RetryDecision.TRANSIENT_PROVIDER, Integer.toString(status),
            "Unexpected provider status" + suffix(summary));
    }

    private static String suffix(String summary) {
        if (summary == null || summary.isBlank()) {
            return "";
        }
        // Provider summaries are truncated hard: this string lands in job_attempts."errorMessage"
        // and in an operator-facing dead-letter reason, and a provider may echo the request back.
        String flattened = summary.replaceAll("[\\r\\n\\t]", " ");
        return ": " + (flattened.length() > 180 ? flattened.substring(0, 180) + "…" : flattened);
    }
}