package com.firstelement.automation.service;

import com.firstelement.automation.persistence.ConversationAutomationRepository;
import com.firstelement.automation.persistence.WorkflowRunRepository;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The AI reply, in two phases, and the human takeover that cancels it.
 *
 * <h2>Why two phases</h2>
 *
 * <p>An AI reply is a customer-visible side effect, and ADR 0007 Part 2 rule 2 says a human taking
 * over aborts any in-flight AI send. "In flight" has to be an observable, cancellable state, so the
 * send is split:
 *
 * <ol>
 *   <li>{@link #stageAiReply} writes the {@code PENDING} outbound {@code messages} row. The row
 *       exists, nothing has been sent, and Phase 5b's dispatcher cannot treat it as deliverable while
 *       it is {@code PENDING}. Persist first, send second.</li>
 *   <li>{@link #confirmAiSend} re-checks the guards and publishes the delivery intent, in the same
 *       transaction as the enqueue. This is the last moment a takeover can cancel.</li>
 * </ol>
 *
 * <h2>The guards, and the lock that makes them meaningful</h2>
 *
 * <p>Both phases take the <b>conversation row lock first</b> and then read the run's abort state — the
 * same order {@link HumanTakeoverService} uses (mode update, then abort request). Consequences:
 *
 * <ul>
 *   <li>Takeover committed first &rarr; the lock is released with {@code mode = 'HUMAN'}, so
 *       {@link ConversationAutomationRepository#lockForAiSend} returns {@code HUMAN} and the send is
 *       refused before anything is written.</li>
 *   <li>The send got the lock first &rarr; it legitimately staged and published before the human took
 *       over; {@link HumanTakeoverService} then cancels the staged message
 *       ({@code PENDING -> FAILED}) and requests the abort, which is exactly the intended
 *       cancellation.</li>
 * </ul>
 *
 * <p>Every refusal is recorded in the action history and returned as a typed status — never a silent
 * no-op. An AI that went quiet for an unknown reason is the failure mode this whole design exists to
 * rule out, so "no silent rule failure" applies to a refusal exactly as it applies to a rule.
 */
@Service
public class AiSendService {

    private static final Logger log = LoggerFactory.getLogger(AiSendService.class);

    /** The action types recorded in the history. Named so a reader can find them. */
    public static final String STAGE_ACTION_TYPE = "AI_SEND_STAGE";
    public static final String CONFIRM_ACTION_TYPE = "AI_SEND_CONFIRM";

    /** One action key for every refusal, so the timeline reads as a narrative rather than a list. */
    static final String REFUSED_ACTION_KEY = "AI_SEND_REFUSED";

    private final ConversationAutomationRepository conversations;
    private final WorkflowRunRepository runs;
    private final WorkflowRunService workflowRuns;
    private final TenantQueryGuard guard;
    private final AuthorizationService authorization;
    private final OutboxEnqueuer outbox;

    public AiSendService(ConversationAutomationRepository conversations,
                         WorkflowRunRepository runs,
                         WorkflowRunService workflowRuns,
                         TenantQueryGuard guard,
                         AuthorizationService authorization,
                         OutboxEnqueuer outbox) {
        this.conversations = conversations;
        this.runs = runs;
        this.workflowRuns = workflowRuns;
        this.guard = guard;
        this.authorization = authorization;
        this.outbox = outbox;
    }

    /** What one phase did. */
    public record SendOutcome(Status status, UUID messageId, String reason, Map<String, Object> detail) {

        public enum Status {
            /** The message row exists and is PENDING. Not yet publishable. */
            STAGED,
            /** The delivery intent is enqueued in this transaction. */
            PUBLISHED,
            /** A human already owns the conversation. */
            REFUSED_HUMAN_MODE,
            /** A takeover has requested the abort of this run. */
            REFUSED_ABORT_REQUESTED,
            /** The run is not RUNNING, or has already been aborted or finished. */
            REFUSED_RUN_NOT_SENDABLE,
            /** The action is outside the run's recorded capability scope. */
            REFUSED_CAPABILITY
        }

        public boolean refused() {
            return status != Status.STAGED && status != Status.PUBLISHED;
        }
    }

    /**
     * Phase 1: persist the AI reply.
     *
     * <h2>Why {@link Permission#AI_SEND} and not a read</h2>
     *
     * <p>Both phases authorize a customer-visible <b>write</b>, so both require a write permission.
     * ADR 0010 §2 — a permission is named after what it authorizes — rules out {@link
     * Permission#AI_RUN_READ}, and {@code AuthorizationServiceTest} records why: Phase 6 escalated a
     * send that reused the read-only {@code AI_RUN_READ}, which is exactly how {@code AI_SEND} came to
     * exist. Reading a run is not speaking to a customer.
     *
     * <p>The old check was not a formality. {@code RolePermissions} grants {@code AI_RUN_READ} to
     * {@code Capability.KNOWLEDGE_RETRIEVE} — a read-oriented retrieval capability — but not
     * {@code AI_SEND}, so a retrieval-only machine principal passed the gate on the path that writes an
     * AI-authored message into a customer's conversation. {@code AI_SEND} is granted to exactly
     * {@code Capability.AI_RUN} and to the {@code ADMIN} role.
     *
     * <p>This check is not the only gate, and not the interesting one. The conversation lock and the
     * run's copied capability scope decide whether <em>this</em> send is permitted; the permission
     * decides only whether the principal is the kind that may send one at all.
     *
     * @param requiredCapability the capability the run's body declared for the send; it must be in the
     *                           run's copied {@code capabilityScope}
     */
    @Transactional
    public SendOutcome stageAiReply(TenantContext runContext, UUID runId, String body,
                                    String requiredCapability) {
        if (body == null || body.isBlank()) {
            throw new ValidationFailedException("an AI reply body is required");
        }
        authorization.check(runContext, Permission.AI_SEND);

        WorkflowRunRepository.RunRow run = requireRun(runContext, runId);
        TenantScope scope = workflowRuns.runScope(runContext, run.projectId());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        // Lock order: conversation, then run. Identical to the takeover path, which is what makes the
        // race below a race rather than a deadlock.
        Optional<String> mode = conversations.lockForAiSend(scope, run.conversationId());
        if (mode.isEmpty()) {
            return refuse(scope, run, SendOutcome.Status.REFUSED_RUN_NOT_SENDABLE,
                "the conversation is not visible to the run's tenant", now);
        }
        if (!"AI".equals(mode.get())) {
            return refuse(scope, run, SendOutcome.Status.REFUSED_HUMAN_MODE,
                "the conversation is in " + mode.get() + " mode; an employee owns it", now);
        }
        if (runs.findSendableRun(scope, runId).isEmpty()) {
            return refuse(scope, run, SendOutcome.Status.REFUSED_ABORT_REQUESTED,
                "the run is no longer RUNNING, or a takeover requested its abort", now);
        }
        if (requiredCapability != null && !requiredCapability.isBlank()
            && !workflowRuns.capabilityScopeOf(scope, runId).contains(requiredCapability)) {
            return refuse(scope, run, SendOutcome.Status.REFUSED_CAPABILITY,
                "the run's capabilityScope does not include " + requiredCapability, now);
        }

        ConversationAutomationRepository.ConversationState conversation =
            conversations.find(scope, run.conversationId()).orElseThrow(
                () -> new ValidationFailedException("the conversation disappeared mid-stage"));
        UUID messageId = UUID.randomUUID();
        conversations.stageOutbound(scope, messageId, conversation.id(), conversation.channel(),
            body, now);

        workflowRuns.record(scope, runId, WorkflowRunService.ActionResult.success(
            WorkflowRunService.AI_SEND_STAGE, STAGE_ACTION_TYPE,
            Map.of("conversationId", run.conversationId().toString(),
                "channel", conversation.channel(),
                "requiredCapability", requiredCapability == null ? "" : requiredCapability),
            Map.of("messageId", messageId.toString(), "bodyLength", body.length())), now,
            LocalDateTime.now(ZoneOffset.UTC));

        log.info("AI reply staged run={} conversation={} message={}", runId, conversation.id(),
            messageId);
        return new SendOutcome(SendOutcome.Status.STAGED, messageId, "staged and pending delivery",
            Map.of("runId", runId.toString()));
    }

    /**
     * Phase 2: publish the delivery intent.
     *
     * <p>Requires {@link Permission#AI_SEND} for the same reason phase 1 does: it publishes a
     * customer-visible delivery intent, so it is a write, and it is re-checked rather than assumed —
     * the two phases may be reached by different callers, and a permission granted to neither must not
     * be able to publish by calling only this half.
     *
     * <p>Re-reads both guards under the same lock order. If the staged message was cancelled by a
     * takeover the enqueue does not happen — this is the exact assertion the Phase-6 gate turns on:
     * <b>no AI reply reaches the channel after a human took the conversation over</b>.
     */
    @Transactional
    public SendOutcome confirmAiSend(TenantContext runContext, UUID runId, UUID messageId) {
        authorization.check(runContext, Permission.AI_SEND);
        WorkflowRunRepository.RunRow run = requireRun(runContext, runId);
        TenantScope scope = workflowRuns.runScope(runContext, run.projectId());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        Optional<String> mode = conversations.lockForAiSend(scope, run.conversationId());
        if (mode.isEmpty() || !"AI".equals(mode.get())) {
            return refuse(scope, run, SendOutcome.Status.REFUSED_HUMAN_MODE,
                "the conversation is " + mode.orElse("no longer visible") + "; the staged AI reply was "
                    + "not sent", now);
        }
        if (runs.findSendableRun(scope, runId).isEmpty()) {
            return refuse(scope, run, SendOutcome.Status.REFUSED_ABORT_REQUESTED,
                "the run is no longer RUNNING, or a takeover requested its abort", now);
        }
        if (!conversations.isStillPending(scope, messageId)) {
            return refuse(scope, run, SendOutcome.Status.REFUSED_ABORT_REQUESTED,
                "the staged message " + messageId + " is no longer PENDING; it was cancelled", now);
        }

        UUID outboxEventId = outbox.publish(OutboxIntent.of(run.organizationId(), run.projectId(),
            "Message", messageId, QueueName.OUTBOUND_SEND.wireValue(),
            "{\"messageId\":\"" + messageId + "\",\"runId\":\"" + runId + "\"}",
            "ai-send:" + runId + ":" + messageId, run.correlationId(), runId.toString()));

        workflowRuns.record(scope, runId, WorkflowRunService.ActionResult.success(
            WorkflowRunService.AI_SEND_CONFIRM, CONFIRM_ACTION_TYPE,
            Map.of("messageId", messageId.toString()),
            Map.of("messageId", messageId.toString(),
                "outboxEventId", outboxEventId == null ? "" : outboxEventId.toString())), now,
            LocalDateTime.now(ZoneOffset.UTC));

        log.info("AI reply published run={} message={} outbox={}", runId, messageId, outboxEventId);
        return new SendOutcome(SendOutcome.Status.PUBLISHED, messageId, "delivery intent published",
            Map.of("runId", runId.toString()));
    }

    /** Loads the run through the tenant-only scope, so a cross-tenant run is simply not found. */
    private WorkflowRunRepository.RunRow requireRun(TenantContext runContext, UUID runId) {
        return runs.findRun(guard.tenantScope(runContext), runId).orElseThrow(
            () -> new ValidationFailedException("No workflow run " + runId + " in this tenant"));
    }

    /**
     * Records a refusal under one action key.
     *
     * <p>Recorded, never thrown and never swallowed: an operator reading the run's timeline must see
     * that the AI stayed quiet and why.
     */
    private SendOutcome refuse(TenantScope scope, WorkflowRunRepository.RunRow run,
                               SendOutcome.Status status, String reason, LocalDateTime now) {
        workflowRuns.record(scope, run.id(), WorkflowRunService.ActionResult.terminalFailure(
            REFUSED_ACTION_KEY, status.name(),
            Map.of("conversationId", run.conversationId().toString(), "refusal", status.name()),
            status.name(), reason), now, LocalDateTime.now(ZoneOffset.UTC));
        log.info("AI send refused run={} status={} reason={}", run.id(), status, reason);
        return new SendOutcome(status, null, reason, Map.of("runId", run.id().toString()));
    }
}
