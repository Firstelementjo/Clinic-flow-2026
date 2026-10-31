package com.firstelement.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.security.TenantAuthentication;
import com.firstelement.security.TenantContext;
import com.firstelement.security.TenantContextHolder;
import com.firstelement.security.UserRole;
import com.firstelement.worker.outbox.OutboxEnqueuer;
import com.firstelement.worker.outbox.OutboxIntent;
import com.firstelement.worker.queue.QueueName;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * "Persist before external send" as a mechanical guarantee, not a convention.
 *
 * <h2>The three refusals this suite proves</h2>
 *
 * <ol>
 *   <li><b>No transaction.</b> Publishing outside a domain transaction is refused, so a dispatcher
 *       can never announce work for a change that rolled back.</li>
 *   <li><b>No tenant context.</b> A queue row is tenant-scoped (FR-1.2); an unscoped write is a
 *       cross-tenant risk, not a default.</li>
 *   <li><b>Wrong tenant context.</b> The thread-bound tenant and the intent's tenant must agree, so
 *       a payload writer cannot publish another tenant's work.</li>
 * </ol>
 *
 * <h2>And the one thing it must get right</h2>
 *
 * <p>A publish inside a transaction that <em>rolls back</em> leaves nothing behind, and the same
 * publish inside a transaction that commits is durable. That is the whole outbox contract in two
 * assertions.
 */
@SpringBootTest(classes = WorkerTestApplication.class,
    properties = "firstelement.worker.dispatcher.enabled=false")
class OutboxEnqueuerTransactionTest extends WorkerIntegrationTestBase {

    @Autowired
    private OutboxEnqueuer enqueuer;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private WorkerFixture fixture;

    @BeforeEach
    void reset() {
        fixture.reset();
    }

    @Test
    @DisplayName("publishing outside a transaction is refused, and nothing is written")
    void publishOutsideATransactionIsRefused() {
        TenantContext alpha = adminA();
        assertThatThrownBy(() -> as(alpha, () -> enqueuer.publish(intent(WorkerFixture.ORG_A, "k1"))))
            .isInstanceOf(OutboxEnqueuer.OutboxPublishedOutsideTransactionException.class)
            .hasMessageContaining("commit or roll back together");
        assertThat(fixture.count("select count(*) from outbox_events")).isZero();
    }

    @Test
    @DisplayName("publishing with no bound tenant is refused")
    void publishWithoutATenantIsRefused() {
        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
            enqueuer.publish(intent(WorkerFixture.ORG_A, "k1"))))
            .isInstanceOf(OutboxEnqueuer.UnscopedOutboxPublishException.class)
            .hasMessageContaining("TenantContext");
        assertThat(fixture.count("select count(*) from outbox_events")).isZero();
    }

    @Test
    @DisplayName("publishing for another tenant than the bound one is refused")
    void publishForAnotherTenantIsRefused() {
        TenantContext alpha = adminA();
        assertThatThrownBy(() -> asVoid(alpha, () -> transactions.executeWithoutResult(status ->
            enqueuer.publish(intent(WorkerFixture.ORG_B, "k1")))))
            .isInstanceOf(OutboxEnqueuer.UnscopedOutboxPublishException.class)
            .hasMessageContaining("from a thread bound to tenant");
        assertThat(fixture.count("select count(*) from outbox_events")).isZero();
    }

    @Test
    @DisplayName("an intent whose eventType names no declared queue is refused at write time")
    void publishRefusesAnUndeclaredQueue() {
        TenantContext alpha = adminA();
        assertThatThrownBy(() -> asVoid(alpha, () -> transactions.executeWithoutResult(status ->
            enqueuer.publish(intent(WorkerFixture.ORG_A, "k1", "not-a-queue")))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not a declared queue");
        assertThat(fixture.count("select count(*) from outbox_events")).isZero();
    }

    @Test
    @DisplayName("a rolled-back domain transaction publishes nothing")
    void rollbackPublishesNothing() {
        TenantContext alpha = adminA();
        org.assertj.core.api.ThrowableAssert.ThrowingCallable runnable = () -> asVoid(alpha,
            () -> transactions.executeWithoutResult(status -> {
                enqueuer.publish(intent(WorkerFixture.ORG_A, "rollback-key"));
                throw new IllegalStateException("the domain change failed");
            }));

        assertThatThrownBy(runnable)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("the domain change failed");

        assertThat(fixture.count("select count(*) from outbox_events")).isZero();
        assertThat(fixture.count("select count(*) from outbox_events where \"idempotencyKey\" = "
            + "'rollback-key'")).isZero();
    }

    @Test
    @DisplayName("a committed domain transaction leaves a claimable, duplicate-suppressed intent")
    void commitIsDurableAndDuplicateSuppressed() {
        TenantContext alpha = adminA();
        UUID first = as(alpha, () -> transactions.execute(
            status -> enqueuer.publish(intent(WorkerFixture.ORG_A, "commit-key"))));
        assertThat(first).isNotNull();
        assertThat(fixture.count("select count(*) from outbox_events")).isEqualTo(1);

        // The same key again is a no-op, not an error: "a conflict means this exact work is already
        // queued".
        UUID second = as(alpha, () -> transactions.execute(
            status -> enqueuer.publish(intent(WorkerFixture.ORG_A, "commit-key"))));
        assertThat(second).isNull();
        assertThat(fixture.count("select count(*) from outbox_events")).isEqualTo(1);
    }

    @Test
    @DisplayName("the same idempotency key in another tenant is a different event")
    void duplicateSuppressionIsTenantScoped() {
        TenantContext alpha = adminA();
        TenantContext beta = TenantContext.forUser(UUID.randomUUID(), WorkerFixture.USER_B,
            WorkerFixture.ORG_B, UserRole.ADMIN, Set.of(WorkerFixture.PROJECT_B));

        UUID alphaPublish = as(alpha, () -> transactions.execute(
            status -> enqueuer.publish(intent(WorkerFixture.ORG_A, "shared-key"))));
        assertThat(alphaPublish).isNotNull();
        UUID betaPublish = as(beta, () -> transactions.execute(
            status -> enqueuer.publish(intent(WorkerFixture.ORG_B, "shared-key"))));
        assertThat(betaPublish).isNotNull();

        assertThat(fixture.count("select count(*) from outbox_events")).isEqualTo(2);
    }

    private static OutboxIntent intent(UUID organizationId, String key) {
        return intent(organizationId, key, QueueName.OUTBOUND_SEND.wireValue());
    }

    private static OutboxIntent intent(UUID organizationId, String key, String eventType) {
        return OutboxIntent.of(organizationId, WorkerFixture.PROJECT_A, "Message", UUID.randomUUID(),
            eventType, "{\"messageId\":\"" + UUID.randomUUID() + "\"}", key, "corr", null);
    }

    private static TenantContext adminA() {
        return TenantContext.forUser(UUID.randomUUID(), WorkerFixture.USER_A, WorkerFixture.ORG_A,
            UserRole.ADMIN, Set.of(WorkerFixture.PROJECT_A));
    }

    /** Runs work with both bindings a request (or a job) would have. */
    private static <T> T as(TenantContext context, java.util.function.Supplier<T> work) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContextHolder.setContext(authentication(context));
        try {
            return TenantContextHolder.call(context, work);
        } finally {
            SecurityContextHolder.setContext(previous);
            TenantContextHolder.clear();
        }
    }

    /** {@link #as} for work that returns nothing, which keeps the nested generics readable. */
    private static void asVoid(TenantContext context, Runnable work) {
        as(context, () -> {
            work.run();
            return null;
        });
    }

    private static SecurityContext authentication(TenantContext context) {
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new TenantAuthentication(context));
        return securityContext;
    }
}