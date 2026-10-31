package com.firstelement.airag.drafts;

import com.firstelement.airag.orchestration.AiOrchestrator;
import com.firstelement.airag.orchestration.AiRunDisposition;
import com.firstelement.airag.orchestration.AiRunResult;
import com.firstelement.airag.policy.AiPolicyRepository;
import com.firstelement.airag.policy.AiRefusedException;
import com.firstelement.airag.trace.AiTraceRepository;
import com.firstelement.crm.domain.ConversationMode;
import com.firstelement.crm.persistence.ConversationRepository;
import com.firstelement.crm.persistence.ConversationRow;
import com.firstelement.crm.persistence.MessageRepository;
import com.firstelement.crm.persistence.MessageRepository.MessageRow;
import com.firstelement.crm.query.CrmQueryScope;
import com.firstelement.security.Capability;
import com.firstelement.security.PrincipalType;
import com.firstelement.security.TenantContext;
import com.firstelement.security.query.TenantQueryGuard;
import com.firstelement.security.query.TenantScope;
import com.firstelement.worker.queue.JobEnvelope;
import com.firstelement.worker.queue.JobHandler;
import com.firstelement.worker.queue.PermanentFailureException;
import com.firstelement.worker.queue.QueueName;
import com.firstelement.worker.queue.RetryDecision;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The consumer for {@link QueueName#AI_RESPONSE}: one AI turn for one accepted inbound message,
 * recorded as a governed <b>draft</b>.
 *
 * <h2>Why this class exists in {@code ai-rag} and not in {@code worker-runtime}</h2>
 *
 * <p>{@code :backend:ai-rag} already declares {@code api(project(":backend:worker-runtime"))} and
 * {@code api(project(":backend:crm-core"))}, so it can implement {@link JobHandler} and read
 * conversations and messages with no new module edge. The arrow cannot point the other way —
 * {@code worker-runtime} is depended on <em>by</em> {@code ai-rag} — so a consumer there could not
 * see the orchestrator. {@code check-architecture.ps1} FE-ARCH-001 records both halves of this.
 *
 * <h2>What this class does NOT do, which is the ruling</h2>
 *
 * <p>It does not send. There is no reference to {@code AiSendService}, no {@code messages} write, no
 * {@code outbound-send} intent, and no {@code workflow_runs} row. The orchestrator decides and records;
 * a human presses send through the existing route. Because nothing is staged, ADR 0007 Part 2 is
 * satisfied more strictly than a send would satisfy it: a consequential action needs approval, and here
 * there is no action at all.
 *
 * <h2>Exactly one run per inbound message</h2>
 *
 * <p>The dispatcher is at-least-once, and a redelivered provider event must not produce a second run
 * <em>or a second charge</em>. The arbiter is {@link AiRunGuard#claim}, which inserts into
 * {@code idempotency_records} under the tenant-scoped unique
 * {@code (organizationId, scope, idempotencyKey)} with {@code on conflict do nothing}: the database
 * decides, not this class's memory, which is exactly what the {@link JobHandler} contract demands. The
 * claim is released on every terminal path, so a run that was claimed and then crashed can be retried
 * by the dispatcher rather than being permanently suppressed.
 *
 * <h2>The human-takeover guard, and why it is a second read</h2>
 *
 * <p>{@code AiResponseTrigger} already checked the mode before enqueueing. That check is not sufficient
 * and must not be trusted: the provider call is slow, and a human can take the conversation over at any
 * point during it. This class therefore re-reads the mode <b>inside the transaction that decides
 * whether the draft is stored</b>, which reproduces the legacy semantics in
 * {@code packages/ai/src/orchestrator/orchestrator.ts} — whose comment says, on the re-read inside the
 * write: "The mode is re-read inside the same transaction that writes the message. Checking once at
 * the start and trusting it is the race that lets an AI reply land after a human took over (plan §10)."
 *
 * <p>When a takeover has landed, the outcome is {@code NO_ACTION} with the reason
 * {@link #WITHHELD_REASON}, recorded on the run so it is visible to an operator, and no draft is
 * stored. The answer text is discarded rather than parked for later: a draft nobody may act on is
 * indistinguishable from a draft that was never produced, and offering it later would be a way to
 * resurrect a decision a human already made.
 *
 * <h2>A refusal is an outcome, not a failure</h2>
 *
 * <p>{@link AiRefusedException} is caught, summarised and returned as a completed job. The orchestrator
 * has already recorded the refusal — step, terminal run row and provenance — before throwing, so the
 * operator surface shows why the AI went quiet. Re-throwing would make the dispatcher burn the tenant's
 * attempt budget on a decision that cannot change, and would convert a recorded policy outcome into a
 * dead letter.
 */
@Component
public class AiResponseJobHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(AiResponseJobHandler.class);

    /**
     * The {@code ai_runs."decisionReason"} a withheld draft carries.
     *
     * <p>Named rather than reused from the legacy string so the read side and the write side cannot
     * drift: {@link AiDraftRepository#latestOfferableDraft} is given this constant, so a draft whose
     * run carries this reason is not offered.
     */
    public static final String WITHHELD_REASON = "HUMAN_TAKEOVER";

    /** The sequence a withheld draft's decision step is written at, past the orchestrator's real steps. */
    private static final int WITHHELD_SEQUENCE = 800;

    private final AiOrchestrator orchestrator;
    private final AiPolicyRepository policies;
    private final AiTraceRepository trace;
    private final AiRunGuard guard;
    private final AiDraftRepository drafts;
    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final TenantQueryGuard queryGuard;

    public AiResponseJobHandler(AiOrchestrator orchestrator,
                                AiPolicyRepository policies,
                                AiTraceRepository trace,
                                AiRunGuard guard,
                                AiDraftRepository drafts,
                                ConversationRepository conversations,
                                MessageRepository messages,
                                TenantQueryGuard queryGuard) {
        this.orchestrator = orchestrator;
        this.policies = policies;
        this.trace = trace;
        this.guard = guard;
        this.drafts = drafts;
        this.conversations = conversations;
        this.messages = messages;
        this.queryGuard = queryGuard;
    }

    @Override
    public QueueName queue() {
        return QueueName.AI_RESPONSE;
    }

    @Override
    public String handle(TenantContext context, JobEnvelope job) {
        UUID conversationId = require(job, "conversationId");
        UUID messageId = require(job, "triggerMessageId");

        // The job's project is a request; the guard decides. This runs before the claim so a job
        // naming a project outside the worker's scope is refused rather than claimed.
        CrmQueryScope scope = CrmQueryScope.forProject(queryGuard, context, job.projectId());

        Optional<ConversationRow> found = conversations.findInScope(scope, conversationId);
        if (found.isEmpty()) {
            // The conversation is not in this tenant. Permanent, and deliberately indistinguishable
            // from a conversation that does not exist: a retry cannot make it appear.
            throw new PermanentFailureException(RetryDecision.INVALID_PAYLOAD, "NO_SUCH_CONVERSATION",
                "the ai-response envelope names a conversation that is not visible to this tenant");
        }
        if (!ConversationMode.AI.name().equals(found.get().mode())) {
            // The conversation was handed to a human between the enqueue and this claim. No run, no
            // charge, no draft. This is the guard's earliest possible statement and the cheapest.
            log.info("AI turn skipped: conversation {} is in {} mode", conversationId, found.get().mode());
            return "skipped mode=" + found.get().mode();
        }

        // The trigger message must be a real inbound customer message in this conversation. Resolved
        // through the tenant-scoped repositories rather than from the envelope, because the envelope is
        // a queue payload and a payload is not an authorization.
        Optional<MessageRow> trigger = messages.forConversation(scope, conversationId, 200).stream()
            .filter(row -> row.id().equals(messageId))
            .findFirst();
        if (trigger.isEmpty()) {
            throw new PermanentFailureException(RetryDecision.INVALID_PAYLOAD, "NO_SUCH_MESSAGE",
                "the ai-response envelope names a message that is not on this conversation");
        }
        MessageRow message = trigger.get();
        if (!com.firstelement.crm.domain.MessageAuthorType.CUSTOMER.name().equals(message.authorType())
            || !com.firstelement.crm.domain.MessageDirection.INBOUND.name().equals(message.direction())) {
            // An AI answering an AI (or an employee) is not a turn this trigger may start.
            log.info("AI turn skipped: message {} is {}/{} and not an inbound customer message",
                messageId, message.direction(), message.authorType());
            return "skipped not-inbound-customer";
        }
        if (message.body() == null || message.body().isBlank()) {
            // Nothing to answer. REFUSED upstream of the provider, because spending a model call on an
            // empty body is a charge for no information.
            return "skipped empty-body";
        }

        Optional<AiPolicyRepository.AgentRow> agent =
            policies.enabledAgentForProject(tenantScopeOf(scope), job.projectId());
        if (agent.isEmpty()) {
            // A conversation can be in AI mode with no enabled agent (one was disabled after the
            // conversation opened). No agent means no governed run, and inventing a default would run
            // an unattributable turn. ADR 0007 rule 7 makes attribution mandatory.
            log.info("AI turn skipped: project {} has no enabled agent", job.projectId());
            return "skipped no-agent";
        }

        // Exactly-one-run-per-message. Claimed AFTER every check that can decline, so a turn that was
        // never going to run does not consume the key.
        AiRunGuard.Claim claim = guard.claim(context, job.projectId(), messageId, job.correlationId());
        if (!claim.won()) {
            log.info("AI turn for message {} was already claimed run={}", messageId, claim.runId());
            return "duplicate run=" + claim.runId();
        }

        TenantContext principal = agentPrincipal(context);
        try {
            return run(context, principal, job.projectId(), scope, conversationId, message, agent.get(),
                claim);
        } catch (AiRefusedException refusal) {
            // Recorded by the orchestrator before it threw. Released so the dispatcher is not asked to
            // retry a policy decision, and reported as a completed job rather than a dead letter.
            guard.release(context, job.projectId(), messageId, claim);
            log.info("AI turn refused message={} reason={}", messageId, refusal.reason());
            return "refused reason=" + refusal.reason();
        } catch (RuntimeException failure) {
            // The claim is released so the dispatcher can retry a genuinely transient failure. The
            // orchestrator has already recorded a refusal row for the run, so nothing goes unrecorded.
            guard.release(context, job.projectId(), messageId, claim);
            log.error("AI turn failed message={} reason={}", messageId,
                failure.getClass().getSimpleName());
            throw failure;
        }
    }

    /** One turn, from the agent lookup through to a stored draft or a recorded refusal. */
    private String run(TenantContext context,
                       TenantContext principal,
                       UUID projectId,
                       CrmQueryScope scope,
                       UUID conversationId,
                       MessageRow message,
                       AiPolicyRepository.AgentRow agent,
                       AiRunGuard.Claim claim) {
        AiRunResult result = orchestrator.run(new AiOrchestrator.RunRequest(principal, projectId,
            conversationId, message.id(), agent.id(), agent.activeVersionId(), message.body()));

        if (result.disposition() == AiRunDisposition.ESCALATED) {
            // The model asked for something consequential. The orchestrator already routed it to a human
            // and shipped no answer, so there is nothing to draft and nothing to withhold.
            guard.complete(context, projectId, message.id(), claim, result.runId());
            return "escalated run=" + result.runId();
        }
        if (!result.hasAnswer()) {
            // ANSWERED is the only disposition that can carry text, but the check is stated rather
            // than assumed: a blank answer stored as a draft would be a draft nobody can send.
            guard.complete(context, projectId, message.id(), claim, result.runId());
            return "no-answer run=" + result.runId() + " disposition=" + result.disposition();
        }

        if (!storeUnlessTakenOver(scope, result, conversationId)) {
            guard.complete(context, projectId, message.id(), claim, result.runId());
            return "withheld reason=" + WITHHELD_REASON + " run=" + result.runId();
        }

        guard.complete(context, projectId, message.id(), claim, result.runId());
        log.info("AI draft ready run={} conversation={} citations={}", result.runId(), conversationId,
            result.citations().size());
        return "drafted run=" + result.runId() + " citations=" + result.citations().size();
    }

    /**
     * The takeover guard: store the draft only if the conversation is still AI-owned, decided by a
     * read taken inside the writing transaction.
     *
     * <p>Returns false when a human owns the conversation. In that case the run is finished as
     * {@code NO_ACTION} with {@link #WITHHELD_REASON}, so the decision is visible to an operator
     * reading the run, and no draft row exists at all.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean storeUnlessTakenOver(CrmQueryScope scope, AiRunResult result,
                                         UUID conversationId) {
        // Re-read under the conversation's own scope. This is the second read, and it is the one that
        // decides: the first happened before the provider was called.
        ConversationRow conversation = conversations.findInScope(scope, conversationId).orElse(null);
        if (conversation == null || !ConversationMode.AI.name().equals(conversation.mode())) {
            withhold(tenantScopeOf(scope), result);
            return false;
        }

        drafts.storeDraft(tenantScopeOf(scope), result.runId(), result.answer(),
            LocalDateTime.now(ZoneOffset.UTC));
        return true;
    }

    /**
     * Records the withheld decision on the run.
     *
     * <p>A step and a terminal run row, in that order, so the reason exists even if the run update is
     * refused. {@code decisionReason} is what {@link AiDraftRepository#latestOfferableDraft} refuses
     * to offer, so this write is what makes the withholding durable on the read side.
     */
    private void withhold(TenantScope scope, AiRunResult result) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        trace.appendStep(scope, result.runId(), WITHHELD_SEQUENCE, "DECISION", "OK", null,
            AiTraceRepository.StepDetail.of("NO_ACTION", WITHHELD_REASON), now);
        trace.finishRun(scope, result.runId(), "COMPLETED",
            AiRunDisposition.NO_ACTION.runDecision(), null, WITHHELD_REASON, null, null, null, now,
            null);
        log.info("AI draft withheld run={} reason={}", result.runId(), WITHHELD_REASON);
    }

    /**
     * The principal a run is attributed to.
     *
     * <p>{@link PrincipalType#AI_AGENT} always, never the worker that happened to claim the job and
     * never a user. The provenance row must say the AI produced this, because a run attributed to a
     * person is an approval that never happened. The capabilities are exactly the ones
     * {@code AiToolExecutionGuard} admits for an {@code AI_AGENT}; the project list is the job's own
     * resolved scope, so the run cannot reach a project the claim did not carry.
     */
    private static TenantContext agentPrincipal(TenantContext jobContext) {
        return TenantContext.forMachine(PrincipalType.AI_AGENT, jobContext.organizationId(),
            "ai-response-draft", jobContext.projectIds(),
            List.of(Capability.AI_RUN, Capability.KNOWLEDGE_RETRIEVE, Capability.AI_TOOL_EXECUTE));
    }

    /**
     * The {@link TenantScope} the AI repositories take, derived from a scope the guard has already
     * approved.
     *
     * <p>Not a second guard call and not a re-read of the request: the ids are copied out of the
     * {@link CrmQueryScope} the caller was handed, so the two cannot disagree about which project is
     * in play. The `ai_runs`/`notes` statements use the tenant predicate directly rather than CRM's
     * {@code CrmQueryScope}, so they need the tenant-security shape instead.
     */
    private static TenantScope tenantScopeOf(CrmQueryScope scope) {
        return new TenantScope(scope.organizationId(), scope.projectId());
    }

    /** A malformed envelope is permanent: the provider sends the same bytes again. */
    private static UUID require(JobEnvelope job, String field) {
        UUID value = job.payloadUuid(field);
        if (value == null) {
            throw new PermanentFailureException(RetryDecision.INVALID_PAYLOAD, "MISSING_FIELD",
                "the ai-response envelope is missing the required field '" + field + "'");
        }
        return value;
    }
}