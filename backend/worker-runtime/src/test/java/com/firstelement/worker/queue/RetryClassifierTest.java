package com.firstelement.worker.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstelement.security.error.PolicyDeniedException;
import com.firstelement.security.job.TenantScopedExecutor;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Retry classification, as a table.
 *
 * <h2>The rule being tested</h2>
 *
 * <p>Retry when the same request could succeed later with no change from anyone; do not retry when
 * the request itself is wrong. Everything in {@link Nested} exists because a wrong answer here is
 * not cosmetic: a permanent failure that is retried spends a tenant's attempt budget five times and
 * delays the dead letter an operator is waiting for, and a transient failure classified as permanent
 * loses a customer's message.
 */
class RetryClassifierTest {

    @Nested
    @DisplayName("transient failures are retried")
    class Transient {

        @Test
        void timeout() {
            RetryDecision decision = RetryClassifier.classify(new TimeoutException("no answer"));
            assertThat(decision.retryable()).isTrue();
            assertThat(decision.retryClass()).isEqualTo(RetryDecision.TIMEOUT);
        }

        @Test
        void socketTimeout() {
            assertThat(RetryClassifier.classify(new SocketTimeoutException("read timed out"))
                .retryable()).isTrue();
        }

        @Test
        void networkFailure() {
            RetryDecision decision = RetryClassifier.classify(new IOException("connection reset"));
            assertThat(decision.retryable()).isTrue();
            assertThat(decision.retryClass()).isEqualTo(RetryDecision.TRANSIENT_PROVIDER);
        }

        @Test
        void databaseContentionAndConnectionLoss() {
            // 40 = transaction rollback/deadlock, 08 = connection exception, 53 = resource shortage.
            assertThat(RetryClassifier.classify(new SQLException("deadlock", "40001")).retryable())
                .isTrue();
            assertThat(RetryClassifier.classify(new SQLException("gone", "08006")).retryable())
                .isTrue();
            assertThat(RetryClassifier.classify(new SQLException("too many", "53300")).retryable())
                .isTrue();
        }

        @Test
        void aHandlerThatRejectsItsOwnInputIsADefectLoop() {
            // IllegalStateException is what a handler throws when its own invariant is violated.
            // Retrying it five times would spend a tenant's attempt budget to reach the same dead
            // letter, so it is classified as a defect and dead-lettered immediately.
            RetryDecision decision = RetryClassifier.classify(new IllegalStateException(
                "the envelope named no messageId and has no aggregateId"));
            assertThat(decision.retryable()).isFalse();
            assertThat(decision.retryClass()).isEqualTo(RetryDecision.INTERNAL_ERROR);
            assertThat(decision.errorCode()).isEqualTo("IllegalStateException");
            assertThat(decision.reason()).contains("defect loop");
        }

        @Test
        void anUnrecognisedFailureIsRetriedAndCountedRatherThanSwallowed() {
            // The opposite default: a type the classifier does not know is retried and recorded for
            // triage, because a permanent verdict on an unknown failure is how a message is lost.
            RetryDecision decision = RetryClassifier.classify(
                new java.util.concurrent.CompletionException(new Exception("provider client bug")));
            assertThat(decision.retryable()).isTrue();
            assertThat(decision.errorCode()).isEqualTo("CompletionException");
            assertThat(decision.reason()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("permanent failures are not retried")
    class Permanent {

        @Test
        void providerRejectedTheRequest() {
            RetryDecision decision = RetryClassifier.classifyHttpStatus(400,
                "{\"error\":{\"message\":\"invalid parameter\"}}");
            assertThat(decision.retryable()).isFalse();
            assertThat(decision.retryClass()).isEqualTo(RetryDecision.INVALID_REQUEST);
            assertThat(decision.errorCode()).isEqualTo("400");
        }

        @Test
        void credentialRejected() {
            RetryDecision decision = RetryClassifier.classifyHttpStatus(401, "bad token");
            assertThat(decision.retryable()).isFalse();
            assertThat(decision.retryClass()).isEqualTo(RetryDecision.AUTHENTICATION);
        }

        @Test
        void theJobLostItsTenantScope() {
            // A security outcome, not a delivery outcome. Retrying cannot widen a scope.
            RetryDecision denied = RetryClassifier.classify(
                PolicyDeniedException.tenantOutOfScope("x", java.util.UUID.randomUUID(),
                    java.util.UUID.randomUUID()));
            assertThat(denied.retryable()).isFalse();
            assertThat(denied.retryClass()).isEqualTo(RetryDecision.SCOPE_REFUSED);

            RetryDecision unscoped = RetryClassifier.classify(
                new TenantScopedExecutor.UnscopedJobException("no tenant"));
            assertThat(unscoped.retryable()).isFalse();
            assertThat(unscoped.retryClass()).isEqualTo(RetryDecision.SCOPE_REFUSED);
        }

        @Test
        void anUnsupportedCapability() {
            RetryDecision decision = RetryClassifier.classify(
                new PermanentFailureException(RetryDecision.UNSUPPORTED_CAPABILITY,
                    "UNSUPPORTED_CAPABILITY", "TELEGRAM does not support MARK_AS_READ"));
            assertThat(decision.retryable()).isFalse();
            assertThat(decision.reason()).contains("MARK_AS_READ");
        }

        @Test
        void aDatabaseRejectionIsOurBug() {
            RetryDecision decision = RetryClassifier.classify(new SQLException("syntax error", "42601"));
            assertThat(decision.retryable()).isFalse();
            assertThat(decision.retryClass()).isEqualTo(RetryDecision.INTERNAL_ERROR);
        }

        @Test
        void anUndeclaredQueue() {
            RetryDecision decision = RetryClassifier.classify(new UnknownQueueException("nope"));
            assertThat(decision.retryable()).isFalse();
            assertThat(decision.retryClass()).isEqualTo(RetryDecision.UNKNOWN_QUEUE);
        }
    }

    @Nested
    @DisplayName("rate limiting is retryable but bounded by the queue policy")
    class RateLimited {

        @Test
        void http429IsRetryable() {
            RetryDecision decision = RetryClassifier.classifyHttpStatus(429,
                "{\"error\":{\"code\":4,\"error_subcode\":33}}");
            assertThat(decision.retryable()).isTrue();
            assertThat(decision.retryClass()).isEqualTo(RetryDecision.RATE_LIMITED);
        }

        @Test
        void thePolicyDecidesTheLastAttempt() {
            QueuePolicy policy = QueuePolicyCatalog.of(QueueName.INCOMING_MESSAGE);
            RetryDecision decision = RetryClassifier.classifyHttpStatus(429, null);

            assertThat(decision.retryable()).isTrue();
            assertThat(decision.shouldRetryGiven(policy, 1)).isTrue();
            assertThat(decision.shouldRetryGiven(policy, policy.maxAttempts() - 1)).isTrue();
            assertThat(decision.shouldRetryGiven(policy, policy.maxAttempts())).isFalse();
        }

        @Test
        void aPermanentFailureIsNotRetriedEvenWithBudgetLeft() {
            RetryDecision decision = RetryClassifier.classifyHttpStatus(400, null);
            assertThat(decision.shouldRetryGiven(QueuePolicyCatalog.of(QueueName.INCOMING_MESSAGE), 1))
                .isFalse();
        }
    }

    @Test
    @DisplayName("a provider summary is flattened and truncated before it is recorded")
    void providerSummariesAreBounded() {
        RetryDecision decision = RetryClassifier.classifyHttpStatus(500,
            "line one\nline two\t" + "x".repeat(400));
        assertThat(decision.reason()).doesNotContain("\n").doesNotContain("\t");
        assertThat(decision.reason().length()).isLessThan(260);
    }

    @Test
    @DisplayName("a null error still produces a classified, non-empty decision")
    void nullErrorIsClassified() {
        RetryDecision decision = RetryClassifier.classify(null);
        assertThat(decision.retryable()).isFalse();
        assertThat(decision.errorCode()).isEqualTo("NULL_ERROR");
    }
}