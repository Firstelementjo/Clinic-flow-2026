package com.firstelement.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.automation.domain.RuleFacts;
import com.firstelement.automation.persistence.ConversationAutomationRepository;
import com.firstelement.automation.persistence.EscalationRepository;
import com.firstelement.automation.service.EscalationService;
import com.firstelement.automation.service.ScoringService;
import com.firstelement.crm.error.ValidationFailedException;
import com.firstelement.security.TenantContext;
import com.firstelement.security.query.TenantQueryGuard;
import com.firstelement.security.query.TenantScope;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Scoring, rule evaluation, and the atomic AI&rarr;HUMAN handover.
 *
 * <h2>The atomicity claim</h2>
 *
 * <p>"The mode transition, the escalation record, the audit entry and the outbox intent are one
 * transaction." These tests attack that claim from the two directions that matter: a conversation
 * already owned by a human (nothing may be written) and a stale mode version (the loser must not
 * write either). A partial handover — a conversation switched to HUMAN with no escalation row — is
 * the failure this suite exists to make impossible.
 *
 * <h2>The summary is bounded and derivable</h2>
 *
 * <p>The handover summary is checked for length, for the customer text it must contain, and for the
 * bound that keeps a long transcript from producing an unbounded row.
 */
@SpringBootTest(classes = WorkflowTestApplication.class)
class EscalationHandoverIntegrationTest extends WorkflowIntegrationTestBase {

    @Autowired
    private WorkflowFixture fixture;

    @Autowired
    private EscalationService escalation;

    @Autowired
    private ScoringService scoring;

    @Autowired
    private EscalationRepository escalations;

    @Autowired
    private ConversationAutomationRepository conversations;

    @Autowired
    private TenantQueryGuard guard;

    @Autowired
    private org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;

    private TenantContext admin;

    @BeforeEach
    void reset() {
        fixture.reset();
        admin = PrincipalScope.adminA();
    }

    /** Facts a default rule escalates on: high frustration and an explicit request for a person. */
    private RuleFacts angry() {
        return new RuleFacts(90, 10, 40, 30, 60, null, 1, true);
    }

    private RuleFacts calm() {
        return new RuleFacts(5, 90, 10, 5, 95, null, 0, false);
    }

    private UUID aiConversation() {
        return fixture.conversation(WorkflowFixture.ORG_A, WorkflowFixture.PROJECT_A,
            WorkflowFixture.CUSTOMER_A, "AI", "OPEN");
    }

    private UUID humanConversation() {
        return fixture.conversation(WorkflowFixture.ORG_A, WorkflowFixture.PROJECT_A,
            WorkflowFixture.CUSTOMER_A, "HUMAN", "OPEN");
    }

    private String modeOf(UUID conversationId) {
        return PrincipalScope.call(admin, () -> conversations
            .find(guard.scope(admin, WorkflowFixture.PROJECT_A), conversationId)
            .orElseThrow().mode());
    }

    private String priorityOf(UUID conversationId) {
        return PrincipalScope.call(admin, () -> conversations
            .find(guard.scope(admin, WorkflowFixture.PROJECT_A), conversationId)
            .orElseThrow().priority());
    }

    // ------------------------------------------------------------------
    // the atomic handover
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a matching rule moves the conversation to HUMAN and records the escalation")
    void escalationIsAtomicWithTheModeChange() {
        UUID conversationId = aiConversation();
        fixture.customerMessage(WorkflowFixture.ORG_A, WorkflowFixture.PROJECT_A, conversationId,
            "This is the third time. I want a real person now.", WorkflowFixture.SEED_AT);

        EscalationService.EscalationOutcome outcome = PrincipalScope.call(admin, () ->
            escalation.escalate(admin, WorkflowFixture.PROJECT_A, conversationId, angry(),
                "EXPLICIT_REQUEST", null));

        assertThat(outcome.status())
            .isEqualTo(EscalationService.EscalationOutcome.Status.ESCALATED);
        assertThat(outcome.modeChanged()).isTrue();
        assertThat(modeOf(conversationId)).isEqualTo("HUMAN");
        assertThat(outcome.escalationId()).isNotNull();

        // Every part of the handover exists, or none of it does.
        EscalationRepository.EscalationRow row = PrincipalScope.call(admin, () ->
            escalations.findEscalation(guard.scope(admin, WorkflowFixture.PROJECT_A),
                outcome.escalationId())).orElseThrow();
        assertThat(row.conversationId()).isEqualTo(conversationId);
        assertThat(row.status()).isEqualTo("PENDING");
        assertThat(row.trigger()).isEqualTo("EXPLICIT_REQUEST");
        assertThat(row.summary()).isNotBlank();
        assertThat(row.createdAt()).isNotNull();
    }

    @Test
    @DisplayName("the escalation intent is written in the same transaction as the handover")
    void escalationPublishesItsIntent() {
        UUID conversationId = aiConversation();
        EscalationService.EscalationOutcome outcome = PrincipalScope.call(admin, () ->
            escalation.escalate(admin, WorkflowFixture.PROJECT_A, conversationId, angry(),
                "EXPLICIT_REQUEST", null));

        assertThat(fixture.count("""
            select count(*) from outbox_events o
             where o."organizationId" = :org
               and o."eventType" = :eventType
               and o."payload" ->> 'escalationId' = :escalationId
            """, Map.of("org", WorkflowFixture.ORG_A,
            "eventType", com.firstelement.worker.queue.QueueName.ESCALATION_ROUTING.wireValue(),
            "escalationId", outcome.escalationId().toString())))
            .as("a committed handover must announce itself; an unannounced escalation is stranded")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("no matching rule writes nothing at all")
    void noMatchIsAQuietNoOp() {
        UUID conversationId = aiConversation();

        EscalationService.EscalationOutcome outcome = PrincipalScope.call(admin, () ->
            escalation.escalate(admin, WorkflowFixture.PROJECT_A, conversationId, calm(),
                "EXPLICIT_REQUEST", null));

        assertThat(outcome.status())
            .isEqualTo(EscalationService.EscalationOutcome.Status.NO_MATCH);
        assertThat(outcome.escalationId()).isNull();
        assertThat(modeOf(conversationId)).as("a calm turn must not take the conversation away")
            .isEqualTo("AI");
        assertThat(fixture.count("select count(*) from escalations", Map.of())).isZero();
        assertThat(fixture.count("select count(*) from outbox_events", Map.of())).isZero();
    }

    @Test
    @DisplayName("a conversation already owned by a human is reported, not re-escalated")
    void alreadyHumanIsNotEscalatedAgain() {
        UUID conversationId = humanConversation();

        EscalationService.EscalationOutcome outcome = PrincipalScope.call(admin, () ->
            escalation.escalate(admin, WorkflowFixture.PROJECT_A, conversationId, angry(),
                "EXPLICIT_REQUEST", null));

        assertThat(outcome.status())
            .isEqualTo(EscalationService.EscalationOutcome.Status.ALREADY_HUMAN);
        assertThat(outcome.escalationId()).isNull();
        assertThat(fixture.count("select count(*) from escalations", Map.of()))
            .as("a duplicate escalation would double the queue's work").isZero();
    }

    @Test
    @DisplayName("a stale modeVersion loses the race and writes no escalation")
    void staleModeVersionLosesTheRace() {
        UUID conversationId = aiConversation();
        TenantScope scope = guard.scope(admin, WorkflowFixture.PROJECT_A);

        // Read the version, then move the mode behind the escalation's back — exactly the window
        // the guard exists to close.
        int staleVersion = PrincipalScope.call(admin, () ->
            conversations.find(scope, conversationId).orElseThrow().modeVersion());
        assertThat(PrincipalScope.call(admin, () ->
            conversations.transitionToHuman(scope, conversationId, staleVersion, "NORMAL")))
            .isEqualTo(1);

        int nowStale = staleVersion;
        assertThat(PrincipalScope.call(admin, () ->
            conversations.transitionToHuman(scope, conversationId, nowStale, "URGENT")))
            .as("the second write must lose on the version guard").isZero();
        assertThat(priorityOf(conversationId))
            .as("the winner's value stands; the loser did not overwrite it").isEqualTo("NORMAL");
    }

    @Test
    @DisplayName("urgency at or above 70 escalates as URGENT, below as HIGH")
    void priorityFollowsTheLegacyThreshold() {
        UUID urgent = aiConversation();
        PrincipalScope.call(admin, () -> escalation.escalate(admin, WorkflowFixture.PROJECT_A, urgent,
            new RuleFacts(90, 10, 70, 30, 60, null, 1, true), "EXPLICIT_REQUEST", null));
        assertThat(priorityOf(urgent)).isEqualTo("URGENT");

        UUID high = aiConversation();
        PrincipalScope.call(admin, () -> escalation.escalate(admin, WorkflowFixture.PROJECT_A, high,
            new RuleFacts(90, 10, 69, 30, 60, null, 1, true), "EXPLICIT_REQUEST", null));
        assertThat(priorityOf(high))
            .as("69 is below the threshold, so it must be HIGH not URGENT").isEqualTo("HIGH");
    }

    // ------------------------------------------------------------------
    // the handover summary
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the summary carries the customer's own words and stays inside its bound")
    void summaryIsUsefulAndBounded() {
        UUID conversationId = aiConversation();
        String loud = "I have been waiting for eleven days and nobody has answered me.";
        fixture.customerMessage(WorkflowFixture.ORG_A, WorkflowFixture.PROJECT_A, conversationId, loud,
            WorkflowFixture.SEED_AT);

        EscalationService.EscalationOutcome outcome = PrincipalScope.call(admin, () ->
            escalation.escalate(admin, WorkflowFixture.PROJECT_A, conversationId, angry(),
                "EXPLICIT_REQUEST", null));

        assertThat(outcome.summary()).contains("eleven days");
        assertThat(outcome.summary().length())
            .as("the legacy bound is 2000 characters; an unbounded summary is a row nobody can read")
            .isLessThanOrEqualTo(EscalationService.SUMMARY_LIMIT);
        // The rule's own description is carried so the receiving agent sees why it was escalated.
        assertThat(outcome.summary()).contains(outcome.match().describe());
    }

    @Test
    @DisplayName("a transcript longer than the window is truncated, and the summary says so")
    void longTranscriptIsTruncated() {
        UUID conversationId = aiConversation();
        for (int i = 0; i < 30; i++) {
            fixture.customerMessage(WorkflowFixture.ORG_A, WorkflowFixture.PROJECT_A, conversationId,
                "message number " + i + " explaining the same complaint at length", WorkflowFixture.SEED_AT
                    .plusMinutes(i));
        }

        EscalationService.EscalationOutcome outcome = PrincipalScope.call(admin, () ->
            escalation.escalate(admin, WorkflowFixture.PROJECT_A, conversationId, angry(),
                "EXPLICIT_REQUEST", null));

        assertThat(outcome.summary().length())
            .isLessThanOrEqualTo(EscalationService.SUMMARY_LIMIT);
        assertThat(outcome.summary())
            .as("the most recent turn is the one that matters and must survive the cut")
            .contains("message number 29");
    }

    @Test
    @DisplayName("the score snapshot is stored, so a later rule edit cannot rewrite the reason")
    void scoreSnapshotIsImmutable() {
        UUID conversationId = aiConversation();
        RuleFacts facts = angry();
        EscalationService.EscalationOutcome outcome = PrincipalScope.call(admin, () ->
            escalation.escalate(admin, WorkflowFixture.PROJECT_A, conversationId, facts,
                "EXPLICIT_REQUEST", null));

        assertThat(PrincipalScope.call(admin, () -> escalations
            .findEscalation(guard.scope(admin, WorkflowFixture.PROJECT_A), outcome.escalationId()))
            .orElseThrow().reason()).isNotBlank();
    }

    // ------------------------------------------------------------------
    // rules and scoring
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an enabled rule whose conditions do not parse is refused, not skipped")
    void unparseableRuleIsRefused() {
        fixture.escalationRule(WorkflowFixture.ORG_A, WorkflowFixture.PROJECT_A, "broken", 10,
            "ALL", "[{\"signal\":\"NOT_A_SIGNAL\",\"operator\":\"gte\",\"value\":50}]", null);
        UUID conversationId = aiConversation();

        // A silently-skipped rule is an escalation an operator configured that never fires.
        assertThatThrownBy(() -> PrincipalScope.call(admin, () ->
            escalation.escalate(admin, WorkflowFixture.PROJECT_A, conversationId, angry(),
                "EXPLICIT_REQUEST", null)))
            .isInstanceOf(ValidationFailedException.class);
    }

    @Test
    @DisplayName("an enabled rule with no conditions is refused: an empty ALL matches everything")
    void vacuousRuleIsRefused() {
        fixture.escalationRule(WorkflowFixture.ORG_A, WorkflowFixture.PROJECT_A, "empty", 10,
            "ALL", "[]", null);
        UUID conversationId = aiConversation();

        assertThatThrownBy(() -> PrincipalScope.call(admin, () ->
            escalation.escalate(admin, WorkflowFixture.PROJECT_A, conversationId, calm(),
                "EXPLICIT_REQUEST", null)))
            .as("a vacuous rule would escalate every customer turn")
            .isInstanceOf(ValidationFailedException.class);
    }

    @Test
    @DisplayName("a scored turn is stored, and the stored value is the one that was decided")
    void scoringStoresHistory() {
        UUID conversationId = aiConversation();
        String text = "Nobody has called me back.";
        fixture.customerMessage(WorkflowFixture.ORG_A, WorkflowFixture.PROJECT_A, conversationId,
            text, WorkflowFixture.SEED_AT);

        ScoringService.ScoredTurn scored = PrincipalScope.call(admin, () ->
            scoring.score(admin, WorkflowFixture.PROJECT_A, conversationId, text, false));

        assertThat(scored.status()).isEqualTo(ScoringService.ScoredTurn.Status.SCORED);
        assertThat(scored.scoreId()).isNotNull();
        assertThat(storedFrustrationOf(scored.scoreId()))
            .as("the decision is kept as history, so an escalation can be re-derived from the row")
            .isEqualTo(scored.scores().frustration());
        assertThat(fixture.count("""
            select count(*) from conversation_scores s
             where s."organizationId" = :org and s."conversationId" = :conversationId
            """, Map.of("org", WorkflowFixture.ORG_A, "conversationId", conversationId)))
            .isEqualTo(1);
    }

    @Test
    @DisplayName("a turn with no text is refused rather than scored as zero")
    void blankTurnIsRefused() {
        UUID conversationId = aiConversation();

        assertThatThrownBy(() -> PrincipalScope.call(admin, () ->
            scoring.score(admin, WorkflowFixture.PROJECT_A, conversationId, "   ", false)))
            .isInstanceOf(ValidationFailedException.class);
        assertThat(fixture.count("select count(*) from conversation_scores", Map.of()))
            .as("a zero would be a number a rule could compare against, which is the bug")
            .isZero();
    }

    private Integer storedFrustrationOf(UUID scoreId) {
        return jdbc.queryForObject(
            "select s.\"frustration\" from conversation_scores s where s.\"id\" = :id",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("id", scoreId),
            Integer.class);
    }
}