package com.firstelement.automation.service;

import com.firstelement.automation.persistence.CollisionRepository;
import com.firstelement.automation.persistence.ConversationAutomationRepository;
import com.firstelement.automation.persistence.WorkflowRunRepository;
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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The human takeover, and the abort of everything the AI had in flight.
 *
 * <h2>This is the phase gate</h2>
 *
 * <p>ADR 0007 Part 2 rule 2: "Human takeover wins. A human taking over a conversation aborts any
 * in-flight AI send." plan.md's Phase-6 gate: "Concurrency tests prove that a human takeover prevents
 * an in-flight AI response from being sent." Both halves are here and in {@link AiSendService}, and
 * the order is the design:
 *
 * <ol>
 *   <li>{@code ConversationService#changeMode} performs the guarded {@code AI -> HUMAN} transition
 *       (one definition of it, with the {@code CONVERSATION_TAKE_OVER} permission and the timeline
 *       entry). This takes the conversation row lock first.</li>
 *   <li>The abort REQUEST lands on every {@code RUNNING} run for that conversation. Idempotent: a
 *       second request updates nothing ({@code workflow-sla-statements.sql} §6a).</li>
 *   <li>Every staged AI message for the conversation is cancelled: {@code PENDING -> FAILED} with a
 *       named reason, so the Phase-5b dispatcher can never pick it up.</li>
 *   <li>Each aborted run is confirmed {@code TAKEOVER} (§6b) and a {@code collision_records} row is
 *       written (§6c) — the one thing in this schema that is not derivable, because both sides
 *       succeeded.</li>
 * </ol>
 *
 * <p>Because step 1 holds the conversation lock and {@code AiSendService} takes the same lock before
 * reading the run, a takeover that has committed makes the AI send's {@code mode='AI'} test fail, and
 * a send that already staged its message is cancelled by step 3. That is the concurrency proof, and
 * it lives in a real transaction rather than in a check performed after the fact.
 */
@Service
public class HumanTakeoverService {

    private static final Logger log = LoggerFactory.getLogger(HumanTakeoverService.class);

    /** The reason recorded on a run whose send was cancelled by a takeover. */
    public static final String ABORT_REASON = "HUMAN_TAKEOVER";

    /** The reason written on a cancelled staged message. An employee reads this. */
    public static final String SEND_CANCELLED_REASON =
        "Cancelled: an employee took this conversation over before the AI reply was sent";

    private final ConversationAutomationRepository conversations;
    private final WorkflowRunRepository runs;
    private final CollisionRepository collisions;
    private final TenantQueryGuard guard;
    private final AuthorizationService authorization;
    private final CrmAuditWriter audit;
    private final OutboxEnqueuer outbox;

    public HumanTakeoverService(ConversationAutomationRepository conversations,
                                WorkflowRunRepository runs,
                                CollisionRepository collisions,
                                TenantQueryGuard guard,
                                AuthorizationService authorization,
                                CrmAuditWriter audit,
                                OutboxEnqueuer outbox) {
        this.conversations = conversations;
        this.runs = runs;
        this.collisions = collisions;
        this.guard = guard;
        this.authorization = authorization;
        this.audit = audit;
        this.outbox = outbox;
    }

    /**
     * Records the abort for one conversation, after the mode transition has already committed.
     *
     * <p>Separate from {@link #takeOver} so the escalation path and the manual path can share it: an
     * escalation that beats a manual takeover aborts the AI send for exactly the same reason, and two
     * implementations of "abort the in-flight send" would drift.
     *
     * @return what was aborted, for the caller to report and for the audit stream
     */
    @Transactional
    public AbortResult abortInFlightSends(TenantContext context, UUID projectId, UUID conversationId,
                                          UUID humanUserId, String reason) {
        authorization.check(context, Permission.CONVERSATION_TAKE_OVER);
        TenantScope scope = guard.scope(context, projectId);

        int requested = runs.requestAbort(scope, conversationId, humanUserId, reason);
        List<WorkflowRunRepository.RunRow> inFlight = runs.runningRunsForConversation(scope, conversationId);

        List<AbortedRun> aborted = new ArrayList<>();
        long epochSeconds = LocalDateTime.now(ZoneOffset.UTC).toEpochSecond(java.time.ZoneOffset.UTC);

        // The staged sends are cancelled FIRST and for the whole conversation: that is what makes them
        // un-sendable, and the run states below are the audit record of it. Cancelling once here
        // rather than per run is deliberate — a staged message carries no run id, so a per-run
        // cancel would match no row and leave the reply sendable, which is precisely the failure
        // this gate exists to prevent.
        int cancelledMessages =
            conversations.cancelStagedAiMessagesForConversation(scope, conversationId,
                SEND_CANCELLED_REASON);

        for (WorkflowRunRepository.RunRow run : inFlight) {
            boolean confirmed = runs.confirmAbort(scope, run.id()) == 1;
            UUID collisionId = null;
            if (confirmed) {
                collisionId = collisions.record(scope, new CollisionRepository.NewCollision(
                    UUID.randomUUID(), "CONVERSATION", "Conversation", conversationId,
                    conversationId, null, null, run.id().toString(), "WORKFLOW_RUN", null,
                    "ABORTED_BY_HUMAN",
                    "A human took the conversation over while workflow run " + run.id()
                        + " had an AI reply in flight; the staged send was cancelled and the run was "
                        + "stopped as TAKEOVER.",
                    run.id()), epochSeconds, run.correlationId(), run.id().toString()).orElse(null);
            }
            aborted.add(new AbortedRun(run.id(), confirmed, collisionId));
        }

        audit.record(CrmAuditRecord.of(context.organizationId(), projectId, humanUserId,
            CrmAuditRecord.EMPLOYEE_TOOK_CONVERSATION, "Conversation", conversationId,
            Map.of("abortedRuns", aborted.size(), "abortRequested", requested,
                "cancelledMessages", cancelledMessages, "reason", reason), null));

        log.info("human takeover aborted in-flight AI sends conversation={} requested={} confirmed={} "
                + "cancelledMessages={}", conversationId, requested, aborted.size(), cancelledMessages);
        return new AbortResult(requested, List.copyOf(aborted), reason);
    }

    /**
     * Aborts the in-flight AI send for a conversation whose mode has <b>already</b> been moved to
     * {@code HUMAN}.
     *
     * <p>The ordering is the contract and the method name says so. The mode transition belongs to
     * {@code ConversationService#changeMode}: it carries the {@code CONVERSATION_TAKE_OVER}
     * permission, the {@code modeVersion} guard, the status/priority side effects and the timeline
     * entry, and duplicating any of them here would be exactly how two takeover paths drift apart. A
     * caller therefore moves the mode first and calls this immediately afterwards, in the same
     * transaction when it can.
     *
     * <p>The outbox intent is written here rather than by the mode transition, and in this
     * transaction, so a dispatcher that polls after the commit cannot announce a takeover that rolled
     * back — and so "a human took this over" reaches the escalation router as one durable fact.
     */
    @Transactional
    public AbortResult takeOverAfterModeChange(TenantContext context, UUID conversationId,
                                               UUID projectId) {
        AbortResult result = abortInFlightSends(context, projectId, conversationId,
            context.userId(), ABORT_REASON);
        outbox.publish(OutboxIntent.of(context.organizationId(), projectId, "Conversation",
            conversationId, com.firstelement.worker.queue.QueueName.ESCALATION_ROUTING.wireValue(),
            "{\"event\":\"HUMAN_TAKEOVER\",\"conversationId\":\"" + conversationId
                + "\",\"abortedRuns\":" + result.abortedRuns().size() + "}",
            "human-takeover:" + conversationId, null, null));
        return result;
    }

    /** One aborted run. */
    public record AbortedRun(UUID runId, boolean confirmed, UUID collisionRecordId) {
    }

    /** What one abort pass did. */
    public record AbortResult(int requested, List<AbortedRun> abortedRuns, String reason) {

        /** Whether anything was actually in flight. */
        public boolean abortedAnything() {
            return !abortedRuns.isEmpty();
        }
    }

    /** Validates that the caller quoted a mode version; the transition itself is elsewhere. */
    public static void requireModeVersion(Integer modeVersion) {
        if (modeVersion == null) {
            throw new ValidationFailedException(
                "modeVersion is required for a takeover. Unlike updatedAt it is the guard the legacy "
                    + "mode transition actually used, and a takeover without it is the un-guarded "
                    + "write the legacy code went out of its way to avoid.");
        }
    }
}
