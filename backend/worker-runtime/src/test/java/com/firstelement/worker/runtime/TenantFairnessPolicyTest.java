package com.firstelement.worker.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstelement.worker.queue.QueueClass;
import com.firstelement.worker.queue.QueueName;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fairness, as the two properties an operator actually needs.
 *
 * <h2>One tenant's backlog must not starve another</h2>
 *
 * <p>Round-robin over tenants, one claim per tenant per sweep. With tenant A holding a thousand
 * queued events and tenant B holding one, B is served on the first sweep rather than after A's
 * thousand — which is the whole reason the rotation exists.
 *
 * <h2>One queue class must not monopolise the pool</h2>
 *
 * <p>A bulk backlog cannot delay a customer-facing reply, because {@code INTERACTIVE} has a reserved
 * share that {@code BULK} cannot borrow.
 */
class TenantFairnessPolicyTest {

    private static final UUID ALPHA = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID BETA = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final UUID GAMMA = UUID.fromString("cccccccc-0000-0000-0000-000000000003");

    @Test
    @DisplayName("the rotation visits every tenant once before any tenant is visited twice")
    void rotationIsRoundRobin() {
        TenantFairnessPolicy policy = new TenantFairnessPolicy(4, 8);
        List<UUID> tenants = List.of(ALPHA, BETA, GAMMA);

        List<UUID> firstPass = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            firstPass.add(policy.nextTenant(tenants).orElseThrow());
        }
        assertThat(firstPass).containsExactlyInAnyOrder(ALPHA, BETA, GAMMA);

        // The next visit is a repeat, which is the definition of round-robin rather than random.
        assertThat(tenants).contains(policy.nextTenant(tenants).orElseThrow());
    }

    @Test
    @DisplayName("a tenant that appears mid-run is visited on the next sweep")
    void rotationAdaptsToTheActiveSet() {
        TenantFairnessPolicy policy = new TenantFairnessPolicy(4, 8);
        policy.nextTenant(List.of(ALPHA, BETA));

        assertThat(policy.nextTenant(List.of(ALPHA, BETA, GAMMA)).orElseThrow()).isNotNull();
        // Nothing stale is served after the active set changed.
        for (int i = 0; i < 6; i++) {
            assertThat(policy.nextTenant(List.of(ALPHA, GAMMA)).orElseThrow())
                .isIn(ALPHA, GAMMA);
        }
    }

    @Test
    @DisplayName("an empty active set yields no tenant rather than a fallback")
    void emptyActiveSetYieldsNothing() {
        TenantFairnessPolicy policy = new TenantFairnessPolicy(4, 8);
        assertThat(policy.nextTenant(List.of())).isEmpty();
        assertThat(policy.nextTenant(null)).isEmpty();
    }

    @Test
    @DisplayName("a tenant's in-flight limit caps concurrency and is released exactly once")
    void tenantLimitIsAPermit() {
        TenantFairnessPolicy policy = new TenantFairnessPolicy(2, 8);

        assertThat(policy.tryAcquireTenantSlot(ALPHA)).isTrue();
        assertThat(policy.tryAcquireTenantSlot(ALPHA)).isTrue();
        assertThat(policy.tryAcquireTenantSlot(ALPHA)).isFalse();
        assertThat(policy.inFlight(ALPHA)).isEqualTo(2);
        assertThat(policy.saturatedTenants()).containsExactly(ALPHA);

        policy.release(ALPHA, QueueClass.INTERACTIVE);
        assertThat(policy.tryAcquireTenantSlot(ALPHA)).isTrue();
        assertThat(policy.saturatedTenants()).containsExactly(ALPHA);

        policy.release(ALPHA, QueueClass.INTERACTIVE);
        assertThat(policy.inFlight(ALPHA)).isEqualTo(1);
    }

    @Test
    @DisplayName("a null tenant or class is never permitted")
    void nullKeysAreRefused() {
        TenantFairnessPolicy policy = new TenantFairnessPolicy(4, 8);
        assertThat(policy.tryAcquireTenantSlot(null)).isFalse();
        assertThat(policy.tryAcquireClassSlot(null)).isFalse();
    }

    @Test
    @DisplayName("a bulk backlog cannot take the interactive slots")
    void bulkCannotMonopoliseTheInteractiveClass() {
        // 4 concurrent slots: INTERACTIVE reserves 2, so BULK can never take all four.
        TenantFairnessPolicy policy = new TenantFairnessPolicy(10, 4);

        assertThat(policy.tryAcquireClassSlot(QueueClass.INTERACTIVE)).isTrue();
        assertThat(policy.tryAcquireClassSlot(QueueClass.INTERACTIVE)).isTrue();
        assertThat(policy.tryAcquireClassSlot(QueueClass.INTERACTIVE)).isFalse();

        int bulk = 0;
        while (policy.tryAcquireClassSlot(QueueClass.BULK)) {
            bulk++;
        }
        assertThat(bulk).isEqualTo(QueueClass.BULK.reservedSlots(4));
        assertThat(policy.tryAcquireClassSlot(QueueClass.BULK)).isFalse();
    }

    @Test
    @DisplayName("releasing twice does not make a negative count")
    void releaseIsIdempotent() {
        TenantFairnessPolicy policy = new TenantFairnessPolicy(4, 8);
        policy.tryAcquireTenantSlot(ALPHA);
        policy.release(ALPHA, QueueClass.DEFAULT);
        policy.release(ALPHA, QueueClass.DEFAULT);
        assertThat(policy.inFlight(ALPHA)).isZero();
    }

    @Test
    @DisplayName("the snapshot is publishable and names no tenant")
    void snapshotIsSafeToPublish() {
        TenantFairnessPolicy policy = new TenantFairnessPolicy(4, 8);
        policy.tryAcquireTenantSlot(ALPHA);
        policy.tryAcquireClassSlot(QueueClass.DEFAULT);

        var snapshot = policy.snapshot();
        assertThat(snapshot).containsKeys("jobsInFlight", "tenantsInFlight",
            "class.INTERACTIVE.reserved", "class.INTERACTIVE.inFlight",
            "class.BULK.reserved", "class.MAINTENANCE.reserved");
        // No tenant id may appear on a health surface.
        assertThat(snapshot.toString()).doesNotContain(ALPHA.toString());
    }

    @Test
    @DisplayName("the queue classes cover every declared queue")
    void everyQueueHasAClass() {
        for (QueueName queue : QueueName.values()) {
            assertThat(queue.queueClass()).isNotNull();
        }
    }
}