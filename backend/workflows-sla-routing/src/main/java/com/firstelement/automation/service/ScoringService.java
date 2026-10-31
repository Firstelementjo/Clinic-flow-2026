package com.firstelement.automation.service;

import com.firstelement.automation.domain.ConversationScoring;
import com.firstelement.automation.domain.RuleFacts;
import com.firstelement.automation.persistence.ConversationAutomationRepository;
import com.firstelement.automation.persistence.EscalationRepository;
import com.firstelement.crm.error.ValidationFailedException;
import com.firstelement.security.TenantContext;
import com.firstelement.security.permission.AuthorizationService;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.query.TenantQueryGuard;
import com.firstelement.security.query.TenantScope;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scoring a customer turn and deciding whether a rule fires.
 *
 * <h2>Only customer turns carry frustration</h2>
 *
 * <p>Legacy: "Only customer turns carry frustration. AI confidence is recorded on the run itself." A
 * non-customer message is a skip with a reason, never a zero-scored row, because a zero is a number an
 * escalation rule can compare against.
 *
 * <h2>The score is stored before anything is escalated</h2>
 *
 * <p>"Every customer turn is scored and the scores are kept as history." The score row is written
 * first; the rule evaluation that follows is a pure function of stored facts, so an escalation can be
 * re-derived from the row after the fact.
 *
 * <h2>What is not here</h2>
 *
 * <p>The mode transition and the escalation record belong to {@link EscalationService}, because the
 * atomic AI&rarr;HUMAN write and the handover summary are one decision and must commit together.
 */
@Service
public class ScoringService {

    private static final Logger log = LoggerFactory.getLogger(ScoringService.class);

    /** How many earlier customer messages are read for the repetition signal. */
    public static final int HISTORY_LIMIT = 50;

    private final ConversationAutomationRepository conversations;
    private final EscalationRepository escalations;
    private final TenantQueryGuard guard;
    private final AuthorizationService authorization;

    public ScoringService(ConversationAutomationRepository conversations,
                          EscalationRepository escalations,
                          TenantQueryGuard guard,
                          AuthorizationService authorization) {
        this.conversations = conversations;
        this.escalations = escalations;
        this.guard = guard;
        this.authorization = authorization;
    }

    /** What scoring a turn produced. */
    public record ScoredTurn(Status status,
                             UUID scoreId,
                             ConversationScoring.Scores scores,
                             RuleFacts facts,
                             String reason) {

        public enum Status {
            SCORED,
            /** Not a customer message; nothing to score. */
            SKIPPED
        }
    }

    /**
     * Scores one customer turn.
     *
     * @param text            the customer's latest message
     * @param lastAiFailed    whether the most recent AI run failed or produced nothing
     */
    @Transactional
    public ScoredTurn score(TenantContext context, UUID projectId, UUID conversationId, String text,
                            boolean lastAiFailed) {
        authorization.check(context, Permission.CONVERSATION_READ);
        TenantScope scope = guard.scope(context, projectId);
        if (text == null || text.isBlank()) {
            throw new ValidationFailedException("a turn to score must have text");
        }

        List<ConversationAutomationRepository.MessageLine> history =
            conversations.customerMessages(scope, conversationId, HISTORY_LIMIT);
        if (history.isEmpty()) {
            return new ScoredTurn(ScoredTurn.Status.SKIPPED, null, null, null,
                "no customer message to score in this conversation");
        }
        ConversationAutomationRepository.MessageLine latest = history.get(history.size() - 1);

        List<String> previous = new ArrayList<>();
        for (int index = 0; index < history.size() - 1; index++) {
            previous.add(history.get(index).body());
        }
        int aiTurns = conversations.countAiMessages(scope, conversationId);
        ConversationScoring.Scores scores = ConversationScoring.evaluate(
            new ConversationScoring.Turn(latest.body(), previous, aiTurns, lastAiFailed));

        boolean explicitHumanRequest = ConversationScoring.explicitHumanRequest(latest.body());
        RuleFacts facts = new RuleFacts(scores.frustration(), scores.sentiment(), scores.urgency(),
            scores.risk(), scores.intentConfidence(), scores.aiConfidence(), aiTurns,
            explicitHumanRequest);

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        UUID scoreId = UUID.randomUUID();
        java.util.Map<String, Object> signals = new java.util.LinkedHashMap<>(scores.signals());
        signals.put("explicitHumanRequest", explicitHumanRequest);
        escalations.insertScore(scope, scoreId, conversationId, latest.id(), scores.frustration(),
            scores.sentiment(), scores.urgency(), scores.risk(), scores.intentConfidence(),
            scores.aiConfidence(), scores.intent(), signals, now);

        log.debug("turn scored conversation={} frustration={} risk={} explicitHumanRequest={}",
            conversationId, scores.frustration(), scores.risk(), explicitHumanRequest);
        return new ScoredTurn(ScoredTurn.Status.SCORED, scoreId, scores, facts, "scored");
    }
}