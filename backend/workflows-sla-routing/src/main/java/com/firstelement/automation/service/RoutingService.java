package com.firstelement.automation.service;

import com.firstelement.automation.domain.RoutingDecider;
import com.firstelement.automation.domain.RoutingOutcome;
import com.firstelement.automation.domain.RoutingRequirement;
import com.firstelement.automation.domain.RoutingStage;
import com.firstelement.automation.persistence.RoutingRepository;
import com.firstelement.crm.audit.CrmAuditRecord;
import com.firstelement.crm.audit.CrmAuditWriter;
import com.firstelement.crm.error.ValidationFailedException;
import com.firstelement.inbox.service.ConversationService;
import com.firstelement.security.TenantContext;
import com.firstelement.security.permission.AuthorizationService;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.query.TenantQueryGuard;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deterministic routing, and the explainable unassigned queue.
 *
 * <h2>The decision is a pure function</h2>
 *
 * <p>{@link RoutingDecider} does the choosing; this class does the I/O and the recording. Splitting
 * them is what makes "deterministic and explainable" provable: the same roster and the same instant
 * produce the same answer a thousand times over, and the answer carries the per-stage survivor counts
 * that say why.
 *
 * <h2>What is refused, in the legacy order</h2>
 *
 * <ol>
 *   <li>A conversation nobody can read in this tenant.</li>
 *   <li>A conversation that already has an owner: "A conversation someone already owns is not
 *       re-routed: reassigning under an agent mid-reply is worse than a slightly uneven queue."</li>
 *   <li>A conversation not in AI-free, active, unassigned state — which is
 *       {@code UnassignedQueueRepository.UNASSIGNED_ACTIVE}, reused rather than re-derived
 *       (ADR 0006 §5).</li>
 * </ol>
 *
 * <p>The legacy engine also refused a conversation "still with the AI". That check is deliberately
 * <b>absent</b> here: in this runtime the escalation path moves the mode to {@code HUMAN} in the same
 * transaction that creates the escalation, and the router runs after that, so requiring
 * {@code HUMAN} would make the legacy ordering impossible. The refusal is recorded here instead of
 * being half-enforced.
 *
 * <h2>Assignment is the inbox's write</h2>
 *
 * <p>The write goes through {@link ConversationService#assign}, which carries the
 * {@code CONVERSATION_ASSIGN} permission, the {@code updatedAt} optimistic-concurrency token, the
 * eligibility check, the capacity check, the workload recount and the timeline entry. A router with its
 * own UPDATE would duplicate all of that and drift.
 */
@Service
public class RoutingService {

    private static final Logger log = LoggerFactory.getLogger(RoutingService.class);

    /** The bound on the roster a single routing decision considers. */
    public static final int CANDIDATE_BATCH_LIMIT = 500;

    /** The bound on the rules a single routing decision considers. */
    public static final int RULE_BATCH_LIMIT = 100;

    private final RoutingRepository routing;
    private final ConversationService conversations;
    private final TenantQueryGuard guard;
    private final AuthorizationService authorization;
    private final CrmAuditWriter audit;

    public RoutingService(RoutingRepository routing,
                          ConversationService conversations,
                          TenantQueryGuard guard,
                          AuthorizationService authorization,
                          CrmAuditWriter audit) {
        this.routing = routing;
        this.conversations = conversations;
        this.guard = guard;
        this.authorization = authorization;
        this.audit = audit;
    }

    /** What a routing attempt did, and everything that produced the answer. */
    public record RoutingResult(Status status,
                                RoutingOutcome outcome,
                                RoutingRepository.RoutingRuleRow rule,
                                String reason,
                                Map<String, Object> explanation) {

        public enum Status {
            ASSIGNED,
            /** Nobody qualified: the ticket stays in the unassigned queue with the blocking stage. */
            QUEUED,
            /** The conversation is not routable (owned, closed, or not in the queue). */
            SKIPPED
        }
    }

    /**
     * Routes a conversation.
     *
     * @param intent       the classified intent, used only by a rule that names one
     * @param department   the escalation's department, or null
     * @param now          the instant the working-hours stage judges
     */
    @Transactional
    public RoutingResult route(TenantContext context, UUID projectId, UUID conversationId,
                               String intent, String department, Instant now) {
        authorization.check(context, Permission.CONVERSATION_ASSIGN);
        // Project scope first: a client-supplied project id is a request, and the guard refuses it
        // before any statement is built.
        guard.scope(context, projectId);

        if (!routing.isUnassignedAndActive(context, projectId, conversationId)) {
            return skipped("the conversation is not unassigned and active; routing would reassign "
                + "work somebody already owns (UNASSIGNED_ACTIVE is the shared membership rule)");
        }

        List<RoutingRepository.RoutingRuleRow> rules =
            routing.rules(context, projectId, RULE_BATCH_LIMIT);
        RoutingRepository.RoutingRuleRow rule = RoutingRepository.firstMatch(rules, intent, department);

        List<com.firstelement.automation.domain.RoutingCandidate> candidates =
            routing.candidates(context, projectId, CANDIDATE_BATCH_LIMIT);

        RoutingRequirement requirement = new RoutingRequirement(
            rule != null ? rule.department() : department,
            rule != null ? rule.requiredSkill() : null,
            rule != null && "ROUND_ROBIN".equals(rule.strategy())
                ? RoutingRequirement.Strategy.ROUND_ROBIN
                : RoutingRequirement.Strategy.LEAST_LOADED);

        RoutingOutcome outcome = RoutingDecider.select(candidates, requirement, now);

        Map<String, Object> explanation = explain(rule, requirement, outcome, now);
        if (outcome.queued()) {
            log.info("routing queued conversation={} blockedAt={} considered={}",
                conversationId, outcome.blockedAt(), outcome.considered());
            return new RoutingResult(RoutingResult.Status.QUEUED, outcome, rule,
                outcome.blockedExplanation(), explanation);
        }

        // The inbox owns the write: permission, optimistic concurrency, eligibility, capacity, workload
        // recount and the timeline entry all happen there.
        conversations.assign(context, conversationId,
            new com.firstelement.inbox.api.AssignConversationRequest(outcome.candidate().employeeId()),
            null, null);

        audit.record(CrmAuditRecord.of(context.organizationId(), projectId, context.userId(),
            CrmAuditRecord.EMPLOYEE_ASSIGNED_CONVERSATION, "Conversation", conversationId,
            Map.of("employeeId", outcome.candidate().employeeId().toString(),
                "reason", "ROUTING", "strategy", requirement.strategy().name()), null));

        log.info("routing assigned conversation={} employee={} rule={}", conversationId,
            outcome.candidate().employeeId(), rule == null ? "none" : rule.name());
        return new RoutingResult(RoutingResult.Status.ASSIGNED, outcome, rule,
            "assigned to " + outcome.candidate().fullName(), explanation);
    }

    /**
     * Explains a decision without performing one.
     *
     * <p>The acceptance gate's "a decision returns the inputs that produced it". The output is the same
     * structure {@link #route} writes into an action's {@code inputSnapshot}: the rule, the
     * requirement, every stage's survivor count, the winner's workload and the exact instant the
     * working-hours stage judged.
     */
    public Map<String, Object> explain(TenantContext context, UUID projectId, String intent,
                                       String department, Instant now) {
        List<RoutingRepository.RoutingRuleRow> rules =
            routing.rules(context, projectId, RULE_BATCH_LIMIT);
        RoutingRepository.RoutingRuleRow rule = RoutingRepository.firstMatch(rules, intent, department);
        List<com.firstelement.automation.domain.RoutingCandidate> candidates =
            routing.candidates(context, projectId, CANDIDATE_BATCH_LIMIT);
        RoutingRequirement requirement = new RoutingRequirement(
            rule != null ? rule.department() : department,
            rule != null ? rule.requiredSkill() : null,
            rule != null && "ROUND_ROBIN".equals(rule.strategy())
                ? RoutingRequirement.Strategy.ROUND_ROBIN
                : RoutingRequirement.Strategy.LEAST_LOADED);
        return explain(rule, requirement, RoutingDecider.select(candidates, requirement, now), now);
    }

    private Map<String, Object> explain(RoutingRepository.RoutingRuleRow rule,
                                        RoutingRequirement requirement, RoutingOutcome outcome,
                                        Instant now) {
        Map<String, Object> explanation = new LinkedHashMap<>();
        explanation.put("judgedAt", now.toString());
        explanation.put("rule", rule == null ? null
            : Map.of("id", rule.id().toString(), "name", rule.name(), "priority", rule.priority(),
                "strategy", rule.strategy()));
        explanation.put("department", requirement.department());
        explanation.put("requiredSkill", requirement.requiredSkill());
        explanation.put("strategy", requirement.strategy().name());
        explanation.put("considered", outcome.considered());
        explanation.put("eligible", outcome.eligible());
        explanation.put("stages", outcome.stageTrace().stream()
            .map(trace -> Map.of("stage", trace.stage().name(), "before", trace.before(),
                "after", trace.after()))
            .toList());
        explanation.put("blockedAt", outcome.blockedAt() == null ? null : outcome.blockedAt().name());
        explanation.put("blockedExplanation", outcome.blockedExplanation());
        explanation.put("chosen", outcome.candidate() == null ? null
            : Map.of("employeeId", outcome.candidate().employeeId().toString(),
                "fullName", outcome.candidate().fullName(),
                "activeConversationCount", outcome.candidate().activeConversationCount(),
                "maxConcurrentConversations", outcome.candidate().maxConcurrentConversations(),
                "availabilityStatus", outcome.candidate().availabilityStatus(),
                "department", outcome.candidate().department() == null
                    ? "" : outcome.candidate().department()));
        explanation.put("reasons", outcome.reasons().stream()
            .map(reason -> Map.of(reason.key(), reason.value()))
            .toList());
        return explanation;
    }

    private RoutingResult skipped(String reason) {
        RoutingStage stage = RoutingStage.MEMBERSHIP;
        return new RoutingResult(RoutingResult.Status.SKIPPED, null, null, reason,
            Map.of("blockedAt", stage.name(), "blockedExplanation", reason));
    }

    /** Validates a routing request's own shape before any I/O. */
    public static void requireProject(UUID projectId) {
        if (projectId == null) {
            throw new ValidationFailedException("projectId is required to route a conversation");
        }
    }
}