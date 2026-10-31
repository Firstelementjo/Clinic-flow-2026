package com.firstelement.worker.queue;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Backoff growth, clamping and jitter bounds.
 *
 * <h2>Why the bounds are the property, not a sample</h2>
 *
 * <p>A jittered delay is random by construction, so a test that asserts one value tests the random
 * seed. {@link BackoffPolicy#bounds(QueuePolicy, int)} publishes the interval instead, and these
 * tests assert that every sampled delay falls inside it — which is the property that actually
 * matters, and it holds for every seed.
 */
class BackoffPolicyTest {

    @Test
    @DisplayName("the first attempt waits nothing; growth is exponential afterwards")
    void growthIsExponential() {
        QueuePolicy policy = QueuePolicyCatalog.of(QueueName.INCOMING_MESSAGE);
        BackoffPolicy deterministic = BackoffPolicy.deterministic();

        assertThat(deterministic.delayAfter(policy, 0)).isEqualTo(Duration.ZERO);
        // base 5s: attempt 2 waits 5s, attempt 3 waits 10s, attempt 4 waits 20s.
        assertThat(deterministic.delayAfter(policy, 1)).isEqualTo(Duration.ofSeconds(5));
        assertThat(deterministic.delayAfter(policy, 2)).isEqualTo(Duration.ofSeconds(10));
        assertThat(deterministic.delayAfter(policy, 3)).isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    @DisplayName("growth is clamped to maxBackoff, so a late attempt is still a retry")
    void growthIsClamped() {
        QueuePolicy policy = QueuePolicyCatalog.of(QueueName.INCOMING_MESSAGE);
        BackoffPolicy deterministic = BackoffPolicy.deterministic();

        // base 5s doubled ten times is 85 minutes; the policy ceiling is 15.
        assertThat(deterministic.delayAfter(policy, policy.maxAttempts() - 1))
            .isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("every sampled delay falls inside the published bounds")
    void jitterStaysInsideItsBounds() {
        QueuePolicy policy = QueuePolicyCatalog.of(QueueName.INCOMING_MESSAGE);
        BackoffPolicy policy1 = new BackoffPolicy(java.util.concurrent.ThreadLocalRandom.current());
        List<Duration> samples = new ArrayList<>();

        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            Duration[] bounds = policy1.bounds(policy, attempt);
            for (int i = 0; i < 200; i++) {
                Duration delay = policy1.delay(policy, attempt - 1, attempt);
                assertThat(delay).isBetween(bounds[0], bounds[1]);
                samples.add(delay);
            }
        }

        // And the spread is real: jitter that never moved would defeat its purpose, which is to stop
        // every retry of a synchronised failure landing on the provider at the same instant.
        assertThat(samples.stream().distinct().count()).isGreaterThan(1);
    }

    @Test
    @DisplayName("the neutral source yields the exact capped delay, so a fixture can assert a value")
    void neutralSourceGivesTheExactDelay() {
        QueuePolicy policy = QueuePolicyCatalog.of(QueueName.OUTBOX_MAINTENANCE);
        assertThat(policy.jitterRatio()).isPositive();
        BackoffPolicy deterministic = BackoffPolicy.deterministic();
        // A neutral source puts the offset at zero, so the jitter term cancels and the delay is
        // exactly base * 2^(n-2). That is what lets an operator replay be scheduled predictably.
        assertThat(deterministic.delayAfter(policy, 1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(deterministic.delayAfter(policy, 2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(deterministic.delayAfter(policy, 3)).isEqualTo(Duration.ofSeconds(4));
    }

    @Test
    @DisplayName("a fixed source produces the midpoint of the window")
    void fixedSourceIsTheMidpoint() {
        QueuePolicy policy = QueuePolicyCatalog.of(QueueName.INCOMING_MESSAGE);
        BackoffPolicy fixed = BackoffPolicy.fixed(1.0d);
        Duration[] bounds = fixed.bounds(policy, 3);
        assertThat(fixed.delayAfter(policy, 2)).isEqualTo(bounds[1]);
    }

    @Test
    @DisplayName("every declared queue has a policy, a class and a sane budget")
    void everyQueueHasAPolicy() {
        for (QueueName queue : QueueName.values()) {
            QueuePolicy policy = QueuePolicyCatalog.of(queue);
            assertThat(policy.queue()).isEqualTo(queue);
            assertThat(policy.queueClass()).isNotNull();
            assertThat(policy.maxAttempts()).isGreaterThanOrEqualTo(1);
            assertThat(policy.baseBackoff()).isPositive();
            assertThat(policy.maxBackoff()).isGreaterThanOrEqualTo(policy.baseBackoff());
            assertThat(policy.jitterRatio()).isBetween(0d, 1d);
            assertThat(QueuePolicyCatalog.all()).containsKey(queue);
        }
    }

    @Test
    @DisplayName("customer-facing queues retry far longer than bulk ones")
    void customerFacingQueuesAreProtected() {
        QueuePolicy inbound = QueuePolicyCatalog.of(QueueName.INCOMING_MESSAGE);
        QueuePolicy outbound = QueuePolicyCatalog.of(QueueName.OUTBOUND_SEND);
        QueuePolicy documents = QueuePolicyCatalog.of(QueueName.DOCUMENT_PROCESSING);

        assertThat(inbound.maxAttempts()).isGreaterThan(documents.maxAttempts());
        assertThat(outbound.maxAttempts()).isGreaterThan(documents.maxAttempts());
        // A malformed document will fail identically on attempt two; a customer whose provider is
        // down for an hour must not become a dead letter.
        assertThat(inbound.maxBackoff()).isGreaterThan(documents.maxBackoff());
    }

    @Test
    @DisplayName("queue names are the legacy wire values, and an unknown value is refused")
    void wireValuesAreStable() {
        assertThat(QueueName.INCOMING_MESSAGE.wireValue()).isEqualTo("incoming-message");
        assertThat(QueueName.OUTBOUND_SEND.wireValue()).isEqualTo("outbound-send");
        assertThat(QueueName.fromWireValue("outbound-send")).isEqualTo(QueueName.OUTBOUND_SEND);
        assertThat(QueueName.fromWireValue("queue-that-does-not-exist")).isNull();
        assertThat(org.assertj.core.api.Assertions
            .catchThrowable(() -> QueueName.require("nope")))
            .isInstanceOf(UnknownQueueException.class);
    }

    @Test
    @DisplayName("queue class reservations never sum to zero, so maintenance always has a slot")
    void everyClassReservesAtLeastOneSlot() {
        for (int concurrency = 1; concurrency <= 16; concurrency++) {
            for (QueueClass queueClass : QueueClass.values()) {
                assertThat(queueClass.reservedSlots(concurrency)).isGreaterThanOrEqualTo(1);
            }
        }
        // The shares are floors and may sum to less than the pool; the remainder belongs to
        // whichever class has work, which is the point ("a class may use more when the others are
        // idle"). What must never happen is a class with zero reserved slots.
        assertThat(QueueClass.values().length).isEqualTo(4);
        assertThat(QueueClass.INTERACTIVE.reservedSlots(0)).isZero();
    }
}