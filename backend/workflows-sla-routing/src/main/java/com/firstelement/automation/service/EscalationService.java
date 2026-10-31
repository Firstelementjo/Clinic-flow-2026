package com.firstelement.automation.service;

import com.firstelement.automation.domain.EscalationRule;
import com.firstelement.automation.domain.EscalationRules;
import com.firstelement.automation.domain.RuleCondition;
import com.firstelement.automation.domain.RuleFacts;
import com.firstelement.automation.domain.ScoreSignal;
import com.firstelement.automation.persistence.ConversationAutomationRepository;
import com.firstelement.automation.persistence.EscalationRepository;
import com.firstelement.crm.audit.CrmAuditRecord;
import com.firstelement.crm.audit.CrmAuditWriter;
import com.firstelement.crm.error.ValidationFailedException;
import com.firstelement.security.TenantContext;
import com.firstelement.security.permission.AuthorizationService;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.query.TenantQueryGuard;
import com.firstelement.security.query.TenantScope;
import com.firstelement.worker.outbox.OutboxEnqueuer;
import com.firstelement.worker.outbox.OutboxIntent;
import com.firstelement.worker.queue.QueueName;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Escalation rules, the atomic AI&rarr;HUMAN transition, and the handover summary.
 *
 * <h2>The atomic transition</h2>
 *
 * <p>ADR 0007 Part 2 rule 1: "The application owns execution." The write is conditional on both the
 * current mode and the {@code modeVersion} read a moment ago, so an escalation and a manual takeover
 * racing each other cannot both win; whoever loses is <b>told</b> it lost rather than silently
 * overwriting the winner. The legacy rationale is kept verbatim in
 * {@link ConversationAutomationRepository#transitionToHuman}.
 *
 * <h2>The handover summary is extractive, by design</h2>
 *
 * <p>The legacy engine preferred a project model and fell back to an extractive summary. Phase 10 owns
 * the provider abstraction, so this service <b>always</b> produces the extractive summary: "If the model
 * is unavailable it falls back to an extractive summary rather than escalating with nothing — an
 * escalation with no context is the failure mode this exists to prevent." Building a model call here
 * would also put a provider call on the escalation transaction, and ADR 0007 Part 2 rule 3 says the CRM
 * must not depend on a provider being up.
 *
 * <p>The summary is built from the transcript as <b>data</b> and truncated, and the transcript text is
 * not interpreted: a prompt injection inside a customer's message has nothing to execute here, because
 * there is no prompt and no tool call.
 *
 * <h2>Rules come from the database, or from the documented defaults</h2>
 *
 * <p>A project with no stored rules uses {@link EscalationRules#defaults()} — "so escalation works the
 * moment an agent is enabled rather than only after someone remembers to configure it" — and its
 * {@code ruleId} is written as null so a stored escalation never points at a rule that does not exist.
 * A stored rule whose conditions do not parse is <b>refused</b>, not treated as empty: an empty
 * {@code ALL} rule matches vacuously and would escalate every customer turn.
 */
@Service
public class EscalationService {

    private static final Logger log = LoggerFactory.getLogger(EscalationService.class);

    /** The summary length bound. Legacy {@code slice(0, 2_000)}. */
    public static final int SUMMARY_LIMIT = 2_000;

    /** The transcript window the extractive summary reads. Legacy took the last 12. */
    public static final int SUMMARY_TRANSCRIPT_LIMIT = 12;

    /** The legacy priority rule: an urgent score escalates as URGENT, otherwise HIGH. */
    static final int URGENT_URGENCY_THRESHOLD = 70;

    private final ConversationAutomationRepository conversations;
    private final EscalationRepository escalations;
    private final TenantQueryGuard guard;
    private final AuthorizationService authorization;
    private final CrmAuditWriter audit;
    private final OutboxEnqueuer outbox;

    public EscalationService(ConversationAutomationRepository conversations,
                             EscalationRepository escalations,
                             TenantQueryGuard guard,
                             AuthorizationService authorization,
                             CrmAuditWriter audit,
                             OutboxEnqueuer outbox) {
        this.conversations = conversations;
        this.escalations = escalations;
        this.guard = guard;
        this.authorization = authorization;
        this.audit = audit;
        this.outbox = outbox;
    }

    /** What an escalation attempt did. */
    public record EscalationOutcome(Status status,
                                    UUID escalationId,
                                    EscalationRules.Match match,
                                    String reason,
                                    String summary,
                                    boolean modeChanged) {

        public enum Status {
            /** The conversation moved to HUMAN and an escalation was recorded. */
            ESCALATED,
            /** No rule fired. The score stands on its own. */
            NO_MATCH,
            /** Somebody else already owns the conversation; nothing was written. */
            LOST_THE_RACE,
            /** The conversation was not in AI mode to begin with. */
            ALREADY_HUMAN
        }
    }

    /**
     * Evaluates the rules and, on a match, hands the conversation to a person.
     *
     * <p>Everything happens in one transaction: the guarded mode write, the escalation row, the audit
     * record and the outbox intent. A rollback therefore cannot leave an escalation with no handover,
     * and a committed handover cannot announce itself without an escalation.
     *
     * @param department an explicit department (an agent asking for a person), which overrides the
     *                   rule's own
     */
    @Transactional
    public EscalationOutcome escalate(TenantContext context, UUID projectId, UUID conversationId,
                                      RuleFacts facts, String trigger, String department) {
        authorization.check(context, Permission.CONVERSATION_TAKE_OVER);
        TenantScope scope = guard.scope(context, projectId);
        if (facts == null) {
            throw new ValidationFailedException("facts are required to evaluate an escalation rule");
        }

        EscalationRules.Match match = EscalationRules.evaluate(rulesFor(context, projectId), facts);
        if (match == null) {
            return new EscalationOutcome(EscalationOutcome.Status.NO_MATCH, null, null,
                "no enabled rule matched", null, false);
        }

        ConversationAutomationRepository.ConversationState conversation =
            conversations.find(scope, conversationId).orElseThrow(
                () -> new ValidationFailedException(
                    "Conversation " + conversationId + " is not visible to this tenant/project"));
        if (!"AI".equals(conversation.mode())) {
            return new EscalationOutcome(EscalationOutcome.Status.ALREADY_HUMAN, null, match,
                "the conversation is already in " + conversation.mode() + " mode", null, false);
        }

        String priority = (facts.urgency() != null && facts.urgency() >= URGENT_URGENCY_THRESHOLD)
            ? "URGENT" : "HIGH";
        int changed = conversations.transitionToHuman(scope, conversationId, conversation.modeVersion(),
            priority);
        if (changed == 0) {
            // Somebody won the race between our read and our write. Say so.
            log.info("escalation lost the race conversation={} expectedModeVersion={}", conversationId,
                conversation.modeVersion());
            return new EscalationOutcome(EscalationOutcome.Status.LOST_THE_RACE, null, match,
                "another writer changed the conversation mode first; nothing was overwritten",
                null, false);
        }

        String summary = buildHandoverSummary(scope, conversationId, match.describe());
        UUID escalationId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        escalations.insertEscalation(scope, escalationId, conversationId,
            storedRuleId(match.rule()), trigger, truncateReason(match.describe()), summary,
            // RuleFacts carries no classified intent; the conversation's own classification is the
            // source of truth, so this stays null rather than inventing one.
            null,
            department != null ? department : match.rule().department(), facts.asSnapshot(), now);

        audit.record(CrmAuditRecord.of(context.organizationId(), projectId, context.userId(),
            CrmAuditRecord.EMPLOYEE_TOOK_CONVERSATION, "Conversation", conversationId,
            Map.of("escalationId", escalationId.toString(), "trigger", trigger,
                "from", "AI", "to", "HUMAN", "priority", priority), null));

        outbox.publish(OutboxIntent.of(context.organizationId(), projectId, "Conversation",
            conversationId, QueueName.ESCALATION_ROUTING.wireValue(),
            "{\"event\":\"ESCALATED\",\"conversationId\":\"" + conversationId
                + "\",\"escalationId\":\"" + escalationId + "\"}",
            "escalated:" + conversationId, null, escalationId.toString()));

        log.info("conversation escalated conversation={} escalation={} rule={}", conversationId,
            escalationId, match.rule().name());
        return new EscalationOutcome(EscalationOutcome.Status.ESCALATED, escalationId, match,
            match.describe(), summary, true);
    }

    /**
     * The rules that apply: the project's own, or the documented defaults.
     *
     * <p>A stored rule with no parseable conditions is refused by throwing rather than being skipped,
     * because a silently-skipped rule is an escalation an operator configured that never fires.
     */
    public List<EscalationRule> rulesFor(TenantContext context, UUID projectId) {
        TenantScope scope = guard.scope(context, projectId);
        List<EscalationRepository.RuleRow> rows = escalations.enabledRules(scope, projectId, 200);
        if (rows.isEmpty()) {
            return EscalationRules.defaults();
        }
        List<EscalationRule> rules = new ArrayList<>();
        for (EscalationRepository.RuleRow row : rows) {
            rules.add(new EscalationRule(row.id(), row.name(), row.enabled(), row.priority(),
                toConditions(row.conditions()),
                EscalationRule.Combinator.fromWireValue(row.combinator()), row.department()));
        }
        return rules;
    }

    /** @throws ValidationFailedException when any stored condition does not name a real signal */
    private List<RuleCondition> toConditions(List<Map<String, Object>> stored) {
        if (stored.isEmpty()) {
            throw new ValidationFailedException(
                "An enabled escalation rule has no usable conditions. An ALL rule with no conditions "
                    + "matches vacuously, so it is refused rather than evaluated.");
        }
        List<RuleCondition> conditions = new ArrayList<>();
        for (Map<String, Object> raw : stored) {
            ScoreSignal signal = ScoreSignal.fromWireValue(String.valueOf(raw.get("signal")));
            RuleCondition.RuleOperator operator =
                RuleCondition.RuleOperator.fromWireValue(String.valueOf(raw.get("operator")));
            if (signal == null || operator == null
                || !(raw.get("value") instanceof Number value)) {
                throw new ValidationFailedException(
                    "An enabled escalation rule has a condition this version cannot evaluate: " + raw
                        + ". Refusing the rule rather than coercing it, because a coerced condition "
                        + "can fire an escalation nobody configured.");
            }
            conditions.add(new RuleCondition(signal, operator, value.intValue()));
        }
        return conditions;
    }

    /**
     * The summary the inheriting employee reads.
     *
     * <p>Extractive, deterministic and bounded. No provider call: the CRM must not depend on an AI
     * provider being up (ADR 0007 Part 2 rule 3), and an escalation that waits on a model is an
     * escalation that arrives after the customer gave up.
     */
    String buildHandoverSummary(TenantScope scope, UUID conversationId, String reason) {
        List<ConversationAutomationRepository.MessageLine> transcript =
            conversations.transcriptForSummary(scope, conversationId, SUMMARY_TRANSCRIPT_LIMIT);

        StringBuilder summary = new StringBuilder("Escalated: ").append(reason).append('\n');
        for (ConversationAutomationRepository.MessageLine line : transcript) {
            String author = "CUSTOMER".equals(line.authorType()) ? "Customer" : "Us";
            summary.append(author).append(": ").append(trim(line.body())).append('\n');
        }
        String text = summary.toString();
        return text.length() <= SUMMARY_LIMIT ? text : text.substring(0, SUMMARY_LIMIT);
    }

    private static String factsIntent(RuleFacts facts) {
        // Deliberately null. RuleFacts carries no classified intent, and the legacy engine wrote
        // whatever the classifier produced. Inventing one here would put a value on an operator-facing
        // surface that no rule actually tested; Phase 10's classifier owns this column.
        return null;
    }

    /** {@code escalations.reason} is unbounded text; the legacy slice was 500 characters. */
    private static String truncateReason(String reason) {
        return reason.length() <= 500 ? reason : reason.substring(0, 500);
    }

    private static UUID storedRuleId(EscalationRule rule) {
        // A default rule has no stored row; writing its synthetic id would point an escalation at a
        // rule id that does not exist.
        return rule.persisted() ? rule.id() : null;
    }

    private static String trim(String text) {
        String clean = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return clean.length() > 160 ? clean.substring(0, 160) + "…" : clean;
    }

    /** The escalations recorded for a conversation, newest first. */
    @Transactional(readOnly = true)
    public List<EscalationRepository.EscalationRow> escalationsFor(TenantContext context,
                                                                   UUID projectId,
                                                                   UUID conversationId, int limit) {
        authorization.check(context, Permission.CONVERSATION_READ);
        return escalations.escalationsForConversation(guard.scope(context, projectId), conversationId,
            Math.max(1, Math.min(limit, 200)));
    }

    /** Marks an escalation PENDING again when routing could not place it. */
    @Transactional
    public void returnToQueue(TenantContext context, UUID projectId, UUID escalationId,
                              String reason) {
        authorization.check(context, Permission.CONVERSATION_ASSIGN);
        TenantScope scope = guard.scope(context, projectId);
        if (escalations.markPending(scope, escalationId) == 0) {
            log.warn("escalation {} could not be returned to the queue: {}", escalationId, reason);
        }
    }

    /** Marks an escalation ASSIGNED. Called by {@link RoutingService} after a successful route. */
    @Transactional
    public void markAssigned(TenantContext context, UUID projectId, UUID escalationId,
                             UUID employeeId) {
        authorization.check(context, Permission.CONVERSATION_ASSIGN);
        TenantScope scope = guard.scope(context, projectId);
        escalations.markAssigned(scope, escalationId, employeeId, LocalDateTime.now(ZoneOffset.UTC));
    }

    /**
     * The legacy priority rule, exposed so a test pins this implementation rather than a copy of it.
     *
     * <p>"urgency &ge; 70 escalates as {@code URGENT}, otherwise {@code HIGH}" — a high-priority
     * escalation a human cannot see in the urgent queue is an escalation nobody picks up.
     */
    public static String priorityFor(RuleFacts facts) {
        return (facts.urgency() != null && facts.urgency() >= URGENT_URGENCY_THRESHOLD)
            ? "URGENT" : "HIGH";
    }

    /** Reads an escalation, tenant-scoped. */
    @Transactional(readOnly = true)
    public Optional<EscalationRepository.EscalationRow> find(TenantContext context, UUID projectId,
                                                             UUID escalationId) {
        authorization.check(context, Permission.CONVERSATION_READ);
        return escalations.findEscalation(guard.scope(context, projectId), escalationId);
    }
}
