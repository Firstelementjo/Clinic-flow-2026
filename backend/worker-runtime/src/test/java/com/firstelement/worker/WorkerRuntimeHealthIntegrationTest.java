package com.firstelement.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstelement.worker.outbox.OutboxRepository;
import com.firstelement.worker.queue.QueueName;
import com.firstelement.worker.runtime.DispatcherLoop;
import com.firstelement.worker.runtime.OutboxDispatcher;
import com.firstelement.worker.runtime.WorkerHealthIndicator;
import com.firstelement.worker.runtime.WorkerIdentity;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The worker process itself: management health, and a job processed by the running runtime.
 *
 * <h2>Why this is a web-context test</h2>
 *
 * <p>FR-10.1/FR-10.2 ask for probes and a deliberately scoped Actuator surface, and plan.md item 8
 * asks for "Admin job and connector health read models". A context with the management endpoints
 * mounted is the only way to assert both exist and both answer — and an unreachable management port
 * is a deployment failure that no unit test can see.
 *
 * <h2>What DOWN must mean</h2>
 *
 * <p>Exactly two things: leases waiting to be reaped (a worker stopped answering) or a dispatcher
 * that is enabled but stalled. A handler-less queue is detail, not DOWN — the API process
 * legitimately runs no dispatcher.
 */
@SpringBootTest(classes = WorkerTestApplication.class,
    properties = {
        "firstelement.worker.dispatcher.enabled=true",
        "firstelement.worker.dispatcher.poll-millis=200",
        "firstelement.worker.dispatcher.reap-millis=300",
        "management.endpoints.web.exposure.include=health,info,metrics",
        "management.endpoint.health.show-details=always"})
@AutoConfigureMockMvc
// This is the only class in the module that runs a live dispatcher, and every class shares one
// PostgreSQL container. Without this the loop keeps claiming every other suite's seeded rows after
// the class ends — which is a real dispatcher doing exactly what it is supposed to do, and still
// makes another suite's claim assertions untrue. Closing the context here stops the loop.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkerRuntimeHealthIntegrationTest extends WorkerIntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private WorkerHealthIndicator health;
    @Autowired
    private OutboxDispatcher dispatcher;
    @Autowired
    private DispatcherLoop loop;
    @Autowired
    private OutboxRepository outbox;
    @Autowired
    private WorkerIdentity identity;
    @Autowired
    private WorkerFixture fixture;

    @BeforeEach
    void reset() {
        fixture.reset();
    }

    @Test
    @DisplayName("the management health endpoint answers and reports the worker identity")
    void managementHealthAnswers() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/actuator/health").accept("application/json"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .jsonPath("$.status").value("UP"));

        // The indicator itself, so the assertion does not depend on how details are rendered.
        var health0 = health.health();
        assertThat(health0.getStatus().getCode()).isEqualTo("UP");
        Map<String, Object> details = health0.getDetails();
        assertThat(details).containsKeys("dispatcherEnabled", "workerId", "registeredQueues",
            "jobsInFlight", "batchSize", "leaseSeconds", "maxInFlightPerTenant",
            "expiredLeasesAwaitingReap", "fairness");
        assertThat(details.get("workerId")).isEqualTo(identity.workerId());
        assertThat(details.get("leaseSeconds")).isEqualTo(60);
        // The maintenance handler is discovered from the classpath, so this process can prove the
        // whole claim/lease/attempt/complete path without depending on :backend:channels.
        assertThat(String.valueOf(details.get("registeredQueues")))
            .contains(QueueName.OUTBOX_MAINTENANCE.wireValue());
    }

    @Test
    @DisplayName("a lease stranded by a dead worker makes health DOWN, and reaping restores it")
    void strandedLeaseMakesHealthDown() {
        // This class runs the real scheduler (`reap-millis=300`), whose own reaper would return the
        // stranded row a moment after health reports it — and the call under test would then find
        // nothing, which is a race and not a defect. The loop is stopped for this test so the reaper
        // whose return value is asserted is the one this test calls, and started again afterwards so
        // `schedulerProcessesQueuedWorkAndDrains` is unaffected whatever the order.
        loop.stop();
        try {
            fixture.seedOutbox(WorkerFixture.ORG_A, WorkerFixture.PROJECT_A,
                QueueName.OUTBOX_MAINTENANCE.wireValue(), "key-health-crashed");
            // Claim whatever is actually claimable, then age the row the claim actually returned.
            // Assuming the claim takes the row just seeded is an ordering bug: this class shares one
            // container with suites that leave claimable rows behind, and `claimFor` is a scheduling
            // scan that takes the oldest due row, not the newest one this test made. Expiring the
            // wrong id leaves nothing stranded and health reports OUT_OF_SERVICE instead of DOWN,
            // which is the same failure seen intermittently before this was fixed.
            var claimed = outbox.claimFor(WorkerFixture.ORG_A, "worker-that-died", 10, 60);
            assertThat(claimed).isNotEmpty();
            UUID id = claimed.get(0).id();
            assertThat(fixture.stateOf(id)).isEqualTo("LEASED");
            fixture.expireLease(id);

            var down = health.health();
            assertThat(down.getStatus().getCode()).isEqualTo("DOWN");
            assertThat(down.getDetails()).containsEntry("expiredLeasesAwaitingReap", 1);

            assertThat(dispatcher.reap()).hasSize(1);
            assertThat(fixture.stateOf(id)).isEqualTo("PENDING");
            // Not DOWN any more. This test stopped the loop on purpose, so health says
            // OUT_OF_SERVICE rather than claiming a healthy dispatcher that is not running — which
            // is what an operator sees during a real shutdown too.
            assertThat(health.health().getStatus().getCode()).isNotEqualTo("DOWN");
            assertThat(health.health().getDetails()).containsEntry("expiredLeasesAwaitingReap", 0);
        } finally {
            loop.start();
        }
        // Serving again: that is the state the operator cares about, and the one the first test
        // asserts from a cold process.
        assertThat(health.health().getStatus().getCode()).isEqualTo("UP");
    }

    @Test
    @DisplayName("the running scheduler claims and processes queued work, then drains on stop")
    void schedulerProcessesQueuedWorkAndDrains() {
        fixture.seedOutbox(WorkerFixture.ORG_A, WorkerFixture.PROJECT_A,
            QueueName.OUTBOX_MAINTENANCE.wireValue(), "key-loop");
        UUID id = outboxIdsOf(WorkerFixture.ORG_A).get(0);

        // This class enables the dispatcher (see its @SpringBootTest properties), so SmartLifecycle
        // has already started the loop; the other suites in this module keep it off and drive the
        // dispatcher synchronously. Driving it here proves the real scheduling path.
        assertThat(loop.isRunning()).isTrue();

        long deadline = System.currentTimeMillis() + 15_000;
        String state = fixture.stateOf(id);
        while (!"SENT".equals(state) && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            state = fixture.stateOf(id);
        }

        // The whole claim -> lease -> handler -> attempt -> complete path, on a real process.
        assertThat(state).isEqualTo("SENT");
        assertThat(fixture.attemptOutcomes(WorkerFixture.ORG_A,
            QueueName.OUTBOX_MAINTENANCE.wireValue())).containsExactly("SUCCEEDED");
        assertThat(health.health().getDetails())
            .containsEntry("dispatcherEnabled", true)
            .containsEntry("draining", false);

        // Drain: stop() stops the loop and refuses further claims.
        loop.stop();
        try {
            assertThat(loop.isRunning()).isFalse();
            assertThat(dispatcher.isDraining()).isTrue();
            // A drained worker is OUT_OF_SERVICE, not DOWN: it is shutting down on purpose, and a
            // load balancer must not read that as a broken queue.
            assertThat(health.health().getStatus().getCode()).isEqualTo("OUT_OF_SERVICE");
            UUID second = fixture.seedOutbox(WorkerFixture.ORG_A, WorkerFixture.PROJECT_A,
                QueueName.OUTBOX_MAINTENANCE.wireValue(), "key-after-drain");
            assertThat(dispatcher.dispatchForTenant(WorkerFixture.ORG_A).claimed()).isZero();
            assertThat(fixture.stateOf(second)).isEqualTo("PENDING");
        } finally {
            // The drain is a property of that assertion, not of the process: JUnit does not promise
            // an order, and the other tests in this class need a running, non-draining loop.
            loop.start();
        }
    }
    private java.util.List<UUID> outboxIdsOf(UUID organizationId) {
        return fixture.jdbc().queryForList("select \"id\" from outbox_events"
            + " where \"organizationId\" = :org",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("org", organizationId), UUID.class);
    }
}
