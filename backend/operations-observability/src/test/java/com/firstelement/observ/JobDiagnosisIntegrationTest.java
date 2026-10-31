package com.firstelement.observ;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstelement.admin.maintenance.MaintenanceOutcome;
import com.firstelement.observ.jobs.JobDiagnosisRepository;
import com.firstelement.observ.jobs.JobOperationsService;
import com.firstelement.security.TenantContext;
import com.firstelement.worker.queue.QueueName;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The acceptance gate this module exists to satisfy: <em>"an operator can diagnose a failed message,
 * webhook, export, vector ingestion, or worker without database access."</em>
 *
 * <h2>What is proved here</h2>
 *
 * <ol>
 *   <li><b>One call answers "what happened to this job".</b> The event's state, every attempt with
 *       its retry class and error code, the dead-letter record, and the integration-stream rows for
 *       the same correlation id.</li>
 *   <li><b>The diagnosis is content-free.</b> {@code outbox_events."payload"} and
 *       {@code job_attempts."errorMessage"} are seeded with markers and asserted absent — not
 *       "absent after redaction", but absent from the record shape, so no later serialisation
 *       change can start emitting them.</li>
 *   <li><b>The replay answer is in the diagnosis.</b> Whether the handler is replay-safe, what
 *       idempotency absorbs a duplicate, whether a replay is needed at all, and the legal-hold gate —
 *       before the operator clicks anything.</li>
 *   <li><b>A cross-tenant event id is an empty answer, not a denial.</b> The route turns it into the
 *       same 404 a nonexistent id produces, so the diagnosis cannot be used to probe for ids.</li>
 *   <li><b>The write path has exactly one allow-list.</b> Replay and dead-letter resolution go through
 *       {@code :backend:administration}'s service, so the refusal, the permission decision and the
 *       audit row are the ones the Admin surface already proved.</li>
 * </ol>
 */
@SpringBootTest(classes = ObservTestApplication.class)
class JobDiagnosisIntegrationTest extends ObservIntegrationTestBase {

    @Autowired
    private JobOperationsService jobs;
    @Autowired
    private ObservFixture fixture;

    @BeforeEach
    void reset() {
        fixture.reset();
    }

    // ==================================================================
    // the diagnosis
    // ==================================================================

    @Nested
    @DisplayName("diagnosing one failed job")
    class Diagnosis {

        @Test
        @DisplayName("one call answers what happened, with no database console in between")
        void oneCallAnswersTheQuestion() {
            UUID eventId = fixture.deadOutboxEvent(ObservFixture.ORG_A, ObservFixture.PROJECT_A,
                QueueName.INCOMING_MESSAGE.wireValue(), 12, "attempts exhausted");
            TenantContext admin = ObservPrincipalScope.adminA();

            Optional<JobOperationsService.Diagnosis> diagnosis =
                ObservPrincipalScope.call(admin, () -> jobs.diagnose(admin, eventId));

            assertThat(diagnosis).isPresent();
            JobOperationsService.Diagnosis found = diagnosis.orElseThrow();

            // The event.
            assertThat(found.event().id()).isEqualTo(eventId);
            assertThat(found.event().state()).isEqualTo("DEAD");
            assertThat(found.event().attempts()).isEqualTo(12);
            assertThat(found.event().deadLetterReason()).isEqualTo("attempts exhausted");
            assertThat(found.event().lastError()).isEqualTo("LAST_ERROR_CODE");
            assertThat(found.event().correlationId()).isNotBlank();

            // Every attempt, oldest first, each naming its retry class and error code.
            assertThat(found.attempts()).isNotEmpty();
            assertThat(found.attempts()).extracting(JobDiagnosisRepository.AttemptRow::attemptNumber)
                .isSorted();
            assertThat(found.attempts()).allSatisfy(attempt -> {
                assertThat(attempt.outboxEventId()).isEqualTo(eventId);
                assertThat(attempt.workerId()).isNotBlank();
                assertThat(attempt.retryClass()).isNotBlank();
                assertThat(attempt.errorCode()).isNotBlank();
            });

            // The dead letter.
            assertThat(found.deadLetters()).hasSize(1);
            assertThat(found.deadLetters().get(0).status()).isEqualTo("OPEN");
            assertThat(found.deadLetters().get(0).reason()).isEqualTo("attempts exhausted");

            // And the integration stream, which is how one message is followed end to end.
            assertThat(found.integrationEvents()).isNotEmpty();
            assertThat(found.integrationEvents())
                .extracting(JobDiagnosisRepository.IntegrationEventRow::eventType)
                .contains("JOB_FAILED");
        }

        @Test
        @DisplayName("the payload and the attempt error message are not in the record at all")
        void theDiagnosisCarriesNoCustomerContent() {
            UUID eventId = fixture.deadOutboxEvent(ObservFixture.ORG_A, ObservFixture.PROJECT_A,
                QueueName.INCOMING_MESSAGE.wireValue(), 12, "attempts exhausted");
            TenantContext admin = ObservPrincipalScope.adminA();

            JobOperationsService.Diagnosis found = ObservPrincipalScope.call(admin,
                () -> jobs.diagnose(admin, eventId)).orElseThrow();

            // The markers are in the database right now: this is not an assertion about empty columns.
            assertThat(fixture.count("select 1 from outbox_events where \"id\" = :id",
                java.util.Map.of("id", eventId))).isEqualTo(1);
            assertThat(fixture.count("select 1 from job_attempts where \"errorMessage\" like :marker",
                java.util.Map.of("marker", "%" + ObservFixture.ERROR_MESSAGE_MARKER + "%")))
                .as("the attempt error message exists in the table; it must not reach the projection")
                .isPositive();

            // The row shape is the guarantee: a field that was never selected cannot be rendered.
            assertThat(JobDiagnosisRepository.EventRow.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("payload");
            assertThat(JobDiagnosisRepository.AttemptRow.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("errorMessage", "message");

            // And the whole rendered diagnosis carries neither marker.
            String rendered = String.valueOf(found);
            assertThat(rendered).doesNotContain(ObservFixture.PAYLOAD_MARKER);
            assertThat(rendered).doesNotContain(ObservFixture.ERROR_MESSAGE_MARKER);
        }

        @Test
        @DisplayName("the replay answer is in the diagnosis, before the operator clicks anything")
        void theReplayAnswerComesWithTheDiagnosis() {
            UUID safe = fixture.deadOutboxEvent(ObservFixture.ORG_A, ObservFixture.PROJECT_A,
                QueueName.INCOMING_MESSAGE.wireValue(), 12, "attempts exhausted");
            UUID unsafe = fixture.deadOutboxEvent(ObservFixture.ORG_A, ObservFixture.PROJECT_A,
                QueueName.DOCUMENT_PROCESSING.wireValue(), 12, "embedding provider refused");
            TenantContext admin = ObservPrincipalScope.adminA();

            var safeDiagnosis = ObservPrincipalScope.call(admin,
                () -> jobs.diagnose(admin, safe)).orElseThrow().replayAssessment();
            var unsafeDiagnosis = ObservPrincipalScope.call(admin,
                () -> jobs.diagnose(admin, unsafe)).orElseThrow().replayAssessment();

            assertThat(safeDiagnosis.replaySafe()).isTrue();
            assertThat(safeDiagnosis.replayNeeded()).isTrue();
            assertThat(safeDiagnosis.evidence()).isNotBlank();
            assertThat(safeDiagnosis.legalHoldGate()).isEqualTo("LEGAL_HOLD_CLEAR");
            assertThat(safeDiagnosis.summary()).contains("replay-safe");

            // Deny by default: an undeclared queue is refused, and the refusal says what would have
            // to change.
            assertThat(unsafeDiagnosis.replaySafe()).isFalse();
            assertThat(unsafeDiagnosis.replayNeeded()).isTrue();
            assertThat(unsafeDiagnosis.evidence()).isNotBlank();
            assertThat(unsafeDiagnosis.summary()).contains("refused by default");
        }

        @Test
        @DisplayName("an event that is not DEAD says there is nothing to replay")
        void aLiveEventNeedsNoReplay() {
            UUID stranded = fixture.strandedLease(ObservFixture.ORG_A, ObservFixture.PROJECT_A,
                QueueName.INCOMING_MESSAGE.wireValue(), "fe-worker@dead",
                ObservFixture.now().minusHours(1));
            TenantContext admin = ObservPrincipalScope.adminA();

            var assessment = ObservPrincipalScope.call(admin,
                () -> jobs.diagnose(admin, stranded)).orElseThrow().replayAssessment();

            assertThat(assessment.replayNeeded()).isFalse();
            assertThat(assessment.summary()).contains("nothing to replay");
        }

        @Test
        @DisplayName("another tenant's event id is the same empty answer as a nonexistent one")
        void crossTenantIdIsEmpty() {
            UUID alphaEvent = fixture.deadOutboxEvent(ObservFixture.ORG_A, ObservFixture.PROJECT_A,
                QueueName.INCOMING_MESSAGE.wireValue(), 12, "attempts exhausted");
            TenantContext beta = ObservPrincipalScope.adminB();

            var foreign = ObservPrincipalScope.call(beta, () -> jobs.diagnose(beta, alphaEvent));
            var nonexistent = ObservPrincipalScope.call(beta,
                () -> jobs.diagnose(beta, UUID.randomUUID()));

            assertThat(foreign).isEmpty();
            assertThat(nonexistent).isEmpty();
            assertThat(String.valueOf(foreign))
                .as("the two answers must be indistinguishable to the caller")
                .isEqualTo(String.valueOf(nonexistent));
        }
    }

    // ==================================================================
    // the worklist and stranded leases
    // ==================================================================

    @Nested
    @DisplayName("the worklist")
    class Worklist {

        @Test
        @DisplayName("dead and stranded events appear, oldest problem first, with no payload")
        void theWorklistIsAWorklist() {
            fixture.deadOutboxEvent(ObservFixture.ORG_A, ObservFixture.PROJECT_A,
                QueueName.INCOMING_MESSAGE.wireValue(), 12, "attempts exhausted");
            fixture.strandedLease(ObservFixture.ORG_A, ObservFixture.PROJECT_A,
                QueueName.DOCUMENT_PROCESSING.wireValue(), "fe-worker@dead",
                ObservFixture.now().minusHours(2));
            TenantContext admin = ObservPrincipalScope.adminA();

            var rows = ObservPrincipalScope.call(admin, () -> jobs.worklist(admin, null, null, 50));

            assertThat(rows).hasSize(2);
            assertThat(rows).extracting(JobDiagnosisRepository.WorklistRow::state)
                .containsExactlyInAnyOrder("DEAD", "LEASED");
            assertThat(rows).extracting(JobDiagnosisRepository.WorklistRow::openDeadLetters)
                .contains(1, 0);
            assertThat(rows).allSatisfy(row ->
                assertThat(String.valueOf(row)).doesNotContain(ObservFixture.PAYLOAD_MARKER));
            assertThat(JobDiagnosisRepository.WorklistRow.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("payload");
        }

        @Test
        @DisplayName("another tenant's failures are not on this worklist")
        void crossTenantWorklistIsEmpty() {
            fixture.deadOutboxEvent(ObservFixture.ORG_A, ObservFixture.PROJECT_A,
                QueueName.INCOMING_MESSAGE.wireValue(), 12, "attempts exhausted");
            fixture.deadOutboxEvent(ObservFixture.ORG_B, ObservFixture.PROJECT_B,
                QueueName.INCOMING_MESSAGE.wireValue(), 12, "attempts exhausted");
            TenantContext beta = ObservPrincipalScope.adminB();

            var rows = ObservPrincipalScope.call(beta, () -> jobs.worklist(beta, null, null, 50));

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).projectId()).isEqualTo(ObservFixture.PROJECT_B);
        }

        @Test
        @DisplayName("a stranded lease names the worker that died and how overdue it is")
        void strandedLeasesAreNamed() {
            fixture.strandedLease(ObservFixture.ORG_A, ObservFixture.PROJECT_A,
                QueueName.INCOMING_MESSAGE.wireValue(), "fe-worker@pod-9",
                ObservFixture.now().minusHours(3));
            TenantContext admin = ObservPrincipalScope.adminA();

            var leases = ObservPrincipalScope.call(admin,
                () -> jobs.strandedLeases(admin, null, 50));

            assertThat(leases).hasSize(1);
            assertThat(leases.get(0).leaseOwner()).isEqualTo("fe-worker@pod-9");
            assertThat(leases.get(0).leaseOverdueSeconds()).isPositive();
        }
    }

    // ==================================================================
    // queue health
    // ==================================================================

    @Nested
    @DisplayName("queue health")
    class Queues {

        @Test
        @DisplayName("a failure rate of -1 means 'no attempts', not 'no failures'")
        void noAttemptsIsNotZeroPercent() {
            TenantContext admin = ObservPrincipalScope.adminA();

            var view = ObservPrincipalScope.call(admin, () -> jobs.queues(admin, null, null, null));

            assertThat(view.document()).containsEntry("attemptsInWindow", 0);
            assertThat(view.document()).containsEntry("failuresInWindow", 0);
            assertThat(view.document())
                .as("FR-7.6 applies to an operator gauge too")
                .containsEntry("failureRate", -1.0);
        }

        @Test
        @DisplayName("the panel publishes the replay-safety allow-list, so an operator knows before clicking")
        void theAllowListIsVisible() {
            TenantContext admin = ObservPrincipalScope.adminA();

            var view = ObservPrincipalScope.call(admin, () -> jobs.queues(admin, null, null, null));

            assertThat(view.document()).containsKey("replaySafety");
            assertThat(view.document().get("replaySafety")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.LIST).isNotEmpty();
        }

        @Test
        @DisplayName("the panel is this tenant's: another tenant's failures do not move the counts")
        void healthIsTenantScoped() {
            fixture.deadOutboxEvent(ObservFixture.ORG_B, ObservFixture.PROJECT_B,
                QueueName.INCOMING_MESSAGE.wireValue(), 12, "attempts exhausted");
            TenantContext admin = ObservPrincipalScope.adminA();

            var view = ObservPrincipalScope.call(admin, () -> jobs.queues(admin, null, null, null));

            assertThat(view.document()).containsEntry("openDeadLetters", 0);
            assertThat(view.document().get("worklist")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.LIST).isEmpty();
        }
    }

    // ==================================================================
    // the write path
    // ==================================================================

    @Nested
    @DisplayName("the two operational writes")
    class Writes {

        @Test
        @DisplayName("replay is the Admin allow-list's decision, not a second one")
        void replayDelegatesToTheOneAllowList() {
            UUID eventId = fixture.deadOutboxEvent(ObservFixture.ORG_A, ObservFixture.PROJECT_A,
                QueueName.INCOMING_MESSAGE.wireValue(), 12, "attempts exhausted");
            TenantContext admin = ObservPrincipalScope.adminA();
            // A SUCCESSFUL replay is not a refusal, so the Admin module writes it to the queue's own
            // job-integration stream rather than to admin_audit_events. Asserting on the admin stream
            // here would assert on a write that only happens for a refusal.
            int jobStreamBefore = fixture.tenantRows("job_integration_events", ObservFixture.ORG_A);

            MaintenanceOutcome outcome =
                ObservPrincipalScope.call(admin, () -> jobs.replay(admin, eventId));

            assertThat(outcome.applied()).isTrue();
            assertThat(outcome.outcome()).isEqualTo("REPLAYED");
            assertThat(outcome.command()).isEqualTo("REPLAY_DEAD_LETTERED_JOB");
            // STATE, not the message: the row is claimable again.
            assertThat(fixture.count("select 1 from outbox_events"
                + " where \"id\" = :id and \"state\" = 'PENDING'", java.util.Map.of("id", eventId)))
                .isEqualTo(1);
            // And the Admin module's audit writer is the one that recorded it.
            assertThat(fixture.tenantRows("job_integration_events", ObservFixture.ORG_A))
                .isGreaterThan(jobStreamBefore);
        }

        @Test
        @DisplayName("an undeclared queue is refused here exactly as it is on the Admin surface")
        void undeclaredQueueIsRefusedHereToo() {
            UUID eventId = fixture.deadOutboxEvent(ObservFixture.ORG_A, ObservFixture.PROJECT_A,
                QueueName.DOCUMENT_PROCESSING.wireValue(), 12, "embedding provider refused");
            TenantContext admin = ObservPrincipalScope.adminA();

            MaintenanceOutcome outcome =
                ObservPrincipalScope.call(admin, () -> jobs.replay(admin, eventId));

            assertThat(outcome.applied()).isFalse();
            assertThat(outcome.outcome()).isEqualTo("NOT_REPLAY_SAFE");
            assertThat(fixture.count("select 1 from outbox_events"
                + " where \"id\" = :id and \"state\" = 'DEAD'", java.util.Map.of("id", eventId)))
                .as("a refusal writes nothing")
                .isEqualTo(1);
        }

        @Test
        @DisplayName("a dead-letter resolution needs who and when, and the row carries them")
        void resolutionIsAttributable() {
            UUID eventId = fixture.deadOutboxEvent(ObservFixture.ORG_A, ObservFixture.PROJECT_A,
                QueueName.INCOMING_MESSAGE.wireValue(), 12, "attempts exhausted");
            UUID deadLetterId = fixture.deadLetterId(eventId);
            TenantContext admin = ObservPrincipalScope.adminA();

            MaintenanceOutcome outcome = ObservPrincipalScope.call(admin,
                () -> jobs.resolveDeadLetter(admin, deadLetterId, "RESOLVED", "provider config fixed"));

            assertThat(outcome.applied()).isTrue();
            assertThat(fixture.count("select 1 from job_dead_letters"
                + " where \"id\" = :id and \"status\" = 'RESOLVED'"
                + " and \"resolvedByUserId\" = :user and \"resolutionNote\" = :note",
                java.util.Map.of("id", deadLetterId, "user", ObservFixture.ADMIN_A,
                    "note", "provider config fixed")))
                .isEqualTo(1);
        }
    }
}
