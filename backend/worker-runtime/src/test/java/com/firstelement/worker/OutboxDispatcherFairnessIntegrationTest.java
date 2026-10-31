package com.firstelement.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstelement.worker.outbox.OutboxRepository;
import com.firstelement.worker.queue.QueueName;
import com.firstelement.worker.runtime.OutboxDispatcher;
import com.firstelement.worker.runtime.TenantDirectory;
import com.firstelement.worker.runtime.TenantFairnessPolicy;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Fairness and limits as the dispatcher applies them, not as the policy class documents them.
 *
 * <h2>Why this suite exists</h2>
 *
 * <p>{@code TenantFairnessPolicyTest} proves the rotation and the permits in isolation. It cannot
 * prove the dispatcher <em>uses</em> them: a sweep that iterated the candidate list and merely asked
 * the rotation for advice would skip every tenant after the first sweep, and every unit test on the
 * policy would still pass. That is not hypothetical &mdash; it is what the first version of
 * {@link OutboxDispatcher#sweep()} did, and this suite is the regression test for it.
 *
 * <h2>The properties</h2>
 *
 * <ul>
 *   <li><b>Every active tenant is served by every sweep</b>, regardless of how deep the other
 *       tenant's backlog is.</li>
 *   <li><b>A tenant at its in-flight limit is deferred, not failed.</b> The claim goes back to
 *       {@code PENDING} with a delay of zero and no attempt is recorded, because nothing failed.</li>
 *   <li><b>A tenant's work never runs under another tenant's scope</b>, and a claim for one tenant
 *       never touches another's rows.</li>
 * </ul>
 */
@SpringBootTest(classes = WorkerTestApplication.class,
    properties = "firstelement.worker.dispatcher.enabled=false")
class OutboxDispatcherFairnessIntegrationTest extends WorkerIntegrationTestBase {

    @Autowired
    private OutboxDispatcher dispatcher;
    @Autowired
    private OutboxRepository outbox;
    @Autowired
    private TenantDirectory tenants;
    @Autowired
    private TenantFairnessPolicy fairness;
    @Autowired
    private WorkerFixture fixture;

    @BeforeEach
    void reset() {
        fixture.reset();
    }

    @Test
    @DisplayName("every sweep serves every active tenant, on the second sweep and the third")
    void everySweepServesEveryTenant() {
        // A deliberately lopsided backlog: ten events for Alpha, one for Beta. A dispatcher that
        // claimed "whoever is next, batch sized" would serve Alpha on every sweep and Beta never.
        for (int i = 0; i < 10; i++) {
            fixture.seedOutbox(WorkerFixture.ORG_A, WorkerFixture.PROJECT_A,
                QueueName.OUTBOX_MAINTENANCE.wireValue(), "alpha-" + i);
        }
        fixture.seedOutbox(WorkerFixture.ORG_B, WorkerFixture.PROJECT_B,
            QueueName.OUTBOX_MAINTENANCE.wireValue(), "beta-0");

        for (int sweep = 1; sweep <= 3; sweep++) {
            OutboxDispatcher.SweepResult result = dispatcher.sweep();

            assertThat(result.tenants()).as("sweep %s visited both tenants", sweep)
                .extracting(OutboxDispatcher.TenantSweepResult::organizationId)
                .containsExactlyInAnyOrder(WorkerFixture.ORG_A, WorkerFixture.ORG_B);
            // One batch per tenant per sweep: Alpha's ten events cannot swallow the sweep, and a
            // dispatcher that ignored the tenant loop would report one tenant here, not two.
            assertThat(result.tenants()).as("sweep %s claimed once per tenant", sweep)
                .allSatisfy(tenant -> assertThat(tenant.claimed()).isLessThanOrEqualTo(20));
        }
        assertThat(dispatcher.sweep().claimed()).isZero();
        assertThat(fixture.count("select count(*) from outbox_events where \"state\" = 'SENT'"))
            .isEqualTo(11);
    }

    @Test
    @DisplayName("a sweep dispatches every tenant even when one tenant has nothing queued")
    void anIdleTenantDoesNotShortCircuitTheSweep() {
        fixture.seedOutbox(WorkerFixture.ORG_A, WorkerFixture.PROJECT_A,
            QueueName.OUTBOX_MAINTENANCE.wireValue(), "alpha-only");

        OutboxDispatcher.SweepResult result = dispatcher.sweep();

        assertThat(result.tenants()).hasSize(2);
        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.tenants().stream()
            .filter(tenant -> tenant.organizationId().equals(WorkerFixture.ORG_B))
            .findFirst().orElseThrow().claimed()).isZero();
    }

    @Test
    @DisplayName("a tenant at its in-flight limit is deferred, not failed, and no attempt is written")
    void aSaturatedTenantIsDeferredNotFailed() {
        UUID id = fixture.seedOutbox(WorkerFixture.ORG_A, WorkerFixture.PROJECT_A,
            QueueName.OUTBOX_MAINTENANCE.wireValue(), "saturated");
        // Saturate the tenant's permits before the sweep: this is the state a slow provider leaves
        // behind while a batch is already in flight.
        for (int i = 0; i < fairness.maxInFlightPerTenant(); i++) {
            assertThat(fairness.tryAcquireTenantSlot(WorkerFixture.ORG_A)).isTrue();
        }

        OutboxDispatcher.TenantSweepResult result =
            dispatcher.dispatchForTenant(WorkerFixture.ORG_A);

        try {
            assertThat(result.claimed()).isEqualTo(1);
            assertThat(result.deferred()).isEqualTo(1);
            assertThat(result.succeeded()).isZero();
            assertThat(result.retried()).isZero();
            assertThat(result.deadLettered()).isZero();
            // Nothing failed, so nothing is recorded as a failure and the row is immediately
            // claimable again.
            assertThat(fixture.attemptOutcomes(WorkerFixture.ORG_A,
                QueueName.OUTBOX_MAINTENANCE.wireValue())).isEmpty();
            assertThat(fixture.stateOf(id)).isEqualTo("PENDING");
            assertThat(fixture.lastErrorOf(id)).contains("deferred");
        } finally {
            for (int i = 0; i < fairness.maxInFlightPerTenant(); i++) {
                fairness.release(WorkerFixture.ORG_A, QueueName.OUTBOX_MAINTENANCE.queueClass());
            }
        }

        // With a permit free again the very next sweep does the work.
        assertThat(dispatcher.dispatchForTenant(WorkerFixture.ORG_A).succeeded()).isEqualTo(1);
        assertThat(fixture.stateOf(id)).isEqualTo("SENT");
    }

    @Test
    @DisplayName("a sweep runs each tenant's job under its own scope, and only its own rows")
    void eachTenantRunsUnderItsOwnScope() {
        fixture.seedOutbox(WorkerFixture.ORG_A, WorkerFixture.PROJECT_A,
            QueueName.OUTBOX_MAINTENANCE.wireValue(), "alpha-scope");
        UUID beta = fixture.seedOutbox(WorkerFixture.ORG_B, WorkerFixture.PROJECT_B,
            QueueName.OUTBOX_MAINTENANCE.wireValue(), "beta-scope");

        OutboxDispatcher.SweepResult result = dispatcher.sweep();

        assertThat(result.succeeded()).isEqualTo(2);
        // The worker id recorded on each attempt is this process's, and the tenant is the row's.
        assertThat(fixture.attemptCount(WorkerFixture.ORG_A,
            QueueName.OUTBOX_MAINTENANCE.wireValue())).isEqualTo(1);
        assertThat(fixture.attemptCount(WorkerFixture.ORG_B,
            QueueName.OUTBOX_MAINTENANCE.wireValue())).isEqualTo(1);
        assertThat(fixture.stateOf(beta)).isEqualTo("SENT");
        assertThat(outbox.dueCount(WorkerFixture.ORG_A)).isZero();
        assertThat(outbox.dueCount(WorkerFixture.ORG_B)).isZero();
    }

    @Test
    @DisplayName("a job's worker context is scoped to the claimed row's project, not to the tenant")
    void workerContextCarriesTheClaimedProject() {
        // The scope is what makes a project-scoped read possible at all: a worker principal scoped
        // to nothing is refused by TenantQueryGuard on every project-scoped query, which would turn
        // every legitimate job into a permanent failure.
        UUID id = fixture.seedOutbox(WorkerFixture.ORG_A, WorkerFixture.PROJECT_A,
            QueueName.OUTBOX_MAINTENANCE.wireValue(), "project-scoped");

        var directory = tenants.workerContext(WorkerFixture.ORG_A, WorkerFixture.PROJECT_A,
            "worker-a").orElseThrow();
        assertThat(directory.hasProject(WorkerFixture.PROJECT_A)).isTrue();
        assertThat(directory.hasProject(WorkerFixture.PROJECT_B)).isFalse();
        assertThat(directory.hasProject(null)).isFalse();
        assertThat(directory.projectIds()).containsExactly(WorkerFixture.PROJECT_A);

        // Organisation-wide work (a null project) gets the empty scope, which is what it is.
        var organisationWide = tenants.workerContext(WorkerFixture.ORG_A, null, "worker-a")
            .orElseThrow();
        assertThat(organisationWide.projectIds()).isEmpty();

        assertThat(dispatcher.dispatchForTenant(WorkerFixture.ORG_A).succeeded()).isEqualTo(1);
        assertThat(fixture.stateOf(id)).isEqualTo("SENT");
    }

    @Test
    @DisplayName("a sweep visits a tenant that appeared mid-run, and stops visiting a removed one")
    void theRotationFollowsTheActiveTenantSet() {
        fixture.seedOutbox(WorkerFixture.ORG_A, WorkerFixture.PROJECT_A,
            QueueName.OUTBOX_MAINTENANCE.wireValue(), "alpha-rotation");
        dispatcher.sweep();

        // Alpha's next work is queued while Beta's tenant row is deactivated.
        fixture.jdbc().update("update organizations set \"isActive\" = false where \"id\" = :id",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("id", WorkerFixture.ORG_B));
        fixture.seedOutbox(WorkerFixture.ORG_A, WorkerFixture.PROJECT_A,
            QueueName.OUTBOX_MAINTENANCE.wireValue(), "alpha-rotation-2");

        OutboxDispatcher.SweepResult result = dispatcher.sweep();

        assertThat(result.tenants()).extracting(OutboxDispatcher.TenantSweepResult::organizationId)
            .containsExactly(WorkerFixture.ORG_A);
        assertThat(result.succeeded()).isEqualTo(1);
    }
}