package com.firstelement.airag.orchestration;

import com.firstelement.airag.config.AiRagProperties;
import com.firstelement.airag.costs.AiUsageAccounting;
import com.firstelement.airag.policy.AiModelGate;
import com.firstelement.airag.policy.AiPolicyRepository;
import com.firstelement.airag.policy.AiPolicyResolver;
import com.firstelement.airag.policy.AiPolicySnapshot;
import com.firstelement.airag.policy.AiRateLimiter;
import com.firstelement.airag.policy.AiRefusalReason;
import com.firstelement.airag.policy.AiRefusedException;
import com.firstelement.airag.policy.BudgetGate;
import com.firstelement.airag.provider.AiProviderKind;
import com.firstelement.airag.provider.ProviderAttempt;
import com.firstelement.airag.provider.ProviderFailoverExecutor;
import com.firstelement.airag.provider.ProviderOutcome;
import com.firstelement.airag.provider.SecretScrubber;
import com.firstelement.airag.redaction.AiRedactionEngine;
import com.firstelement.airag.redaction.RedactionOutcome;
import com.firstelement.airag.redaction.RedactionPolicyDefinition;
import com.firstelement.airag.retrieval.RetrievalOutcome;
import com.firstelement.airag.retrieval.RetrievalVisibilityRepository;
import com.firstelement.airag.retrieval.RetrievedChunk;
import com.firstelement.airag.tools.AiToolExecutor;
import com.firstelement.airag.tools.AiToolInvocation;
import com.firstelement.airag.tools.AiToolResult;
import com.firstelement.airag.trace.AiTraceRepository;
import com.firstelement.airag.trace.AiTraceRepository.StepDetail;
import com.firstelement.security.PrincipalType;
import com.firstelement.security.TenantContext;
import com.firstelement.security.query.TenantQueryGuard;
import com.firstelement.security.query.TenantScope;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single entry point for an AI run. Spring AI's {@code ChatModel} and {@code Prompt} are used here and
 * nowhere else in this module.
 *
 * <h2>The order, and why each step precedes the next</h2>
 *
 * <ol>
 *   <li><b>Rate limit</b> — before anything reads a row.</li>
 *   <li><b>Resolve the agent and its pinned version.</b> By id, never by "the active version", so a run is
 *       attributable to the version that produced it (FR-5.3).</li>
 *   <li><b>Resolve the policy.</b> No policy, or no APPROVED version → refused.</li>
 *   <li><b>Resolve the model</b> against the allow-list and the residency zone.</li>
 *   <li><b>Redaction policy.</b> Before the budget, because an unknown policy name must not be discovered
 *       after tokens have been estimated.</li>
 *   <li><b>Budget.</b> Token, cost and wall-clock ceilings, on the estimate, before the call.</li>
 *   <li><b>Retrieve</b>, tenant-filtered inside the similarity statement.</li>
 *   <li><b>Redact the prompt, then frame it.</b></li>
 *   <li><b>Generate</b> through the failover chain, with a per-attempt timeout.</li>
 *   <li><b>Tool calls</b> the model asked for, each re-authorized inside {@code AiToolExecutor}.</li>
 *   <li><b>Redact the response.</b></li>
 *   <li><b>Record</b>: steps, retrievals, tool calls, provenance, usage.</li>
 * </ol>
 *
 * <p>Authorization is therefore complete before the first byte of customer content is read (step 7 comes
 * after 1–6) and before any tool acts (step 10 re-authorizes). No step reaches a provider without the budget
 * having said yes.
 *
 * <h2>Every refusal is recorded before it is thrown</h2>
 *
 * <p>{@link #refuse} writes a step carrying a reason <b>code</b>, finishes the run as FAILED, writes the
 * provenance row with {@code disposition = 'REFUSED'}, and returns. The caller then sees an
 * {@link AiRefusedException} whose message is a fixed string and whose code is the reason. So a refusal is
 * visible in the operator surface even when the caller's own transaction rolls back.
 *
 * <h2>Retrieval empty is a refusal, not an empty answer</h2>
 *
 * <p>An agent whose {@code minRetrievalScore} forbids an ungrounded answer gets
 * {@link AiRefusalReason#RETRIEVAL_EMPTY}, and the conversation falls back to a human (ADR 0007 rule 3). It
 * does not get a confident answer built from nothing, which is the failure mode that makes a RAG assistant
 * dangerous in a clinic.
 *
 * <h2>No send here</h2>
 *
 * <p>This class produces an answer. It never writes a {@code messages} row and never publishes an outbox
 * intent: Phase 6's {@code AiSendService} owns the send, and it re-checks the conversation mode and the
 * takeover state under the conversation lock. An orchestrator that also sent would be a second place
 * enforcing ADR 0007 rule 2.
 */
@Service
public class AiOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AiOrchestrator.class);

    /**
     * The sequence number every refusal step is written at.
     *
     * <p>900, past any real step, because a refusal may happen at any point and the step list is ordered by
     * {@code sequence}. A refusal recorded at, say, 3 would sort among the CONTEXT steps and read as
     * "context failed" when policy actually refused it.
     */
    private static final int REFUSAL_SEQUENCE = 900;

    private final AiPolicyResolver policies;
    private final AiPolicyRepository policyRepository;
    private final AiModelGate modelGate;
    private final BudgetGate budgetGate;
    private final AiRateLimiter rateLimiter;
    private final RetrievalVisibilityRepository retrieval;
    private final AiRedactionEngine redaction;
    private final AiToolExecutor tools;
    private final AiUsageAccounting accounting;
    private final AiTraceRepository trace;
    private final TenantQueryGuard queryGuard;
    private final ProviderFailoverExecutor providers;
    private final AiRagProperties properties;

    public AiOrchestrator(AiPolicyResolver policies,
                          AiPolicyRepository policyRepository,
                          AiModelGate modelGate,
                          BudgetGate budgetGate,
                          AiRateLimiter rateLimiter,
                          RetrievalVisibilityRepository retrieval,
                          AiRedactionEngine redaction,
                          AiToolExecutor tools,
                          AiUsageAccounting accounting,
                          AiTraceRepository trace,
                          TenantQueryGuard queryGuard,
                          ProviderFailoverExecutor providers,
                          AiRagProperties properties) {
        this.policies = policies;
        this.policyRepository = policyRepository;
        this.modelGate = modelGate;
        this.budgetGate = budgetGate;
        this.rateLimiter = rateLimiter;
        this.retrieval = retrieval;
        this.redaction = redaction;
        this.tools = tools;
        this.accounting = accounting;
        this.trace = trace;
        this.queryGuard = queryGuard;
        this.providers = providers;
        this.properties = properties;
    }

    /** What the caller asks for. */
    public record RunRequest(TenantContext principal,
                             UUID projectId,
                             UUID conversationId,
                             UUID triggerMessageId,
                             UUID agentId,
                             UUID agentVersionId,
                             String question) {

        public RunRequest {
            if (principal == null) {
                throw new IllegalArgumentException("An AI run needs a resolved TenantContext");
            }
            if (question == null || question.isBlank()) {
                throw new IllegalArgumentException("An AI run needs a question");
            }
        }
    }

    /**
     * Runs one AI turn end to end.
     *
     * @throws AiRefusedException for every policy refusal, after the refusal has been recorded
     */
    @Transactional
    public AiRunResult run(RunRequest request) {
        TenantContext principal = request.principal();
        LocalDateTime startedAt = LocalDateTime.now(ZoneOffset.UTC);
        rateLimiter.acquire(principal.organizationId());

        // The project's scope comes from the guard, never from the request: a caller-supplied project id is
        // a request, and this is where it is checked.
        TenantScope scope = queryGuard.scope(principal, request.projectId());

        AiPolicyRepository.AgentRow agent = policyRepository.agent(scope, request.projectId(), request.agentId())
            .orElseThrow(() -> AiRefusedException.of(AiRefusalReason.POLICY_MISSING,
                "no enabled agent " + request.agentId() + " in this project"));
        UUID versionId = request.agentVersionId() != null ? request.agentVersionId() : agent.activeVersionId();
        if (versionId == null) {
            throw AiRefusedException.of(AiRefusalReason.POLICY_MISSING,
                "agent " + agent.id() + " has no active version and the request named none");
        }
        AiPolicyRepository.AgentVersionRow version = policyRepository
            .agentVersion(queryGuard.tenantScope(principal), versionId)
            .orElseThrow(() -> AiRefusedException.of(AiRefusalReason.POLICY_MISSING,
                "no agent version " + versionId + " in this tenant"));

        AiProviderKind agentProvider = AiProviderKind.parseOrNull(version.provider());
        UUID runId = trace.startRun(scope, request.conversationId(), agent.id(), version.id(),
            request.triggerMessageId(), agentProvider == null ? AiProviderKind.MOCK : agentProvider,
            version.model(), startedAt);

        try {
            return execute(request, principal, scope, agent, version, runId, startedAt);
        } catch (AiRefusedException refusal) {
            refuse(scope, agent, version, runId, refusal, startedAt);
            throw refusal;
        } catch (RuntimeException failure) {
            // An unexpected failure becomes a refusal whose reason is the exception CLASS, never its message.
            // A message is where a provider's echo of the prompt would end up, and this is the one place a
            // bug in this module could leak it into a persisted row.
            log.error("AI run failed unexpectedly run={} conversation={} reason={}", runId,
                request.conversationId(), failure.getClass().getSimpleName());
            AiRefusedException wrapped = new AiRefusedException(AiRefusalReason.PROVIDER_UNAVAILABLE,
                "run-failed:" + failure.getClass().getSimpleName(), failure);
            refuse(scope, agent, version, runId, wrapped, startedAt);
            throw wrapped;
        }
    }

    /** The body of a run, once the run row exists so every refusal has something to attach to. */
    private AiRunResult execute(RunRequest request,
                                TenantContext principal,
                                TenantScope scope,
                                AiPolicyRepository.AgentRow agent,
                                AiPolicyRepository.AgentVersionRow version,
                                UUID runId,
                                LocalDateTime startedAt) {
        int step = 0;

        // Policy, then the model allow-list and the residency zone.
        AiPolicySnapshot snapshot = policies.resolve(scope, version.provider());
        trace.appendStep(scope, runId, step++, "CONTEXT", "OK", null,
            StepDetail.of("POLICY_RESOLVED", snapshot.key()), startedAt);

        AiPolicyRepository.ModelRow model = modelGate.requireModel(scope, snapshot, version.model(),
            snapshot.failoverChain());
        trace.appendStep(scope, runId, step++, "CONTEXT", "OK", null,
            StepDetail.of("MODEL_RESOLVED", model.provider() + "/" + model.model()), startedAt);

        RedactionPolicyDefinition redactionPolicy =
            redaction.policyForOrRefuse(snapshot, properties.getFallbackRedactionPolicy());

        // Budget first: the prompt's token estimate is needed, and estimating it does not require reading
        // any customer content, so the cheapest refusal is the one that happens before retrieval.
        int preflightTokens = com.firstelement.airag.provider.MockTokenCounter.countAll(
            List.of(version.systemInstructions() == null ? "" : version.systemInstructions(),
                request.question()));
        if (preflightTokens > budgetGate.effectiveMaxTokens(snapshot)) {
            throw new AiRefusedException(AiRefusalReason.POLICY_TOKEN_LIMIT,
                "preflight estimate of " + preflightTokens + " tokens exceeds the effective ceiling of "
                    + budgetGate.effectiveMaxTokens(snapshot));
        }

        // Retrieve. The tenant predicate lives inside the similarity statement; this class only supplies the
        // scope and the knowledge bases.
        List<UUID> knowledgeBases = knowledgeBases(version);
        double minScore = Math.max(version.minRetrievalScore(), 0.0d);
        RetrievalOutcome retrieved = retrieval.retrieve(scope, knowledgeBases, request.question(),
            minScore, properties.getRetrievalLimit());
        trace.appendStep(scope, runId, step++, "RETRIEVAL", "OK", null,
            new StepDetail("RETRIEVAL", null, retrieved.consideredCount(), retrieved.returnedCount(),
                retrieved.minimumScore(), List.of(), null, 0, 0, 0, 0L), startedAt);

        if (retrieved.empty()) {
            // "Nothing indexed" and "everything below the threshold" are different conversations with an
            // administrator, so the two get different codes.
            throw AiRefusedException.of(
                retrieved.consideredButNothingQualified() ? AiRefusalReason.BELOW_MIN_SCORE
                    : AiRefusalReason.RETRIEVAL_EMPTY,
                "considered " + retrieved.consideredCount() + " chunk(s) at threshold "
                    + retrieved.minimumScore());
        }

        List<UUID> retrievalIds = new ArrayList<>();
        for (RetrievedChunk chunk : retrieved.returned()) {
            retrievalIds.add(trace.appendRetrieval(scope, runId, chunk.knowledgeBaseId(), chunk.documentId(),
                chunk.chunkId(), chunk.documentTitle(), chunk.score(), true, startedAt));
        }

        // Redact the untrusted content, the question and the agent's instructions, then frame.
        RedactionOutcome redactedSources = redaction.redactUntrustedContent(redactionPolicy,
            retrieved.returned());
        RedactionOutcome redactedQuestion = redaction.redact(redactionPolicy,
            AiRedactionEngine.STAGE_SYSTEM, request.question());
        RedactionOutcome redactedInstructions = redaction.redact(redactionPolicy,
            AiRedactionEngine.STAGE_MEMORY, version.systemInstructions());

        // Each snippet carries the same redacted text, because the engine redacts the joined section. That
        // is a deliberate coarsening and it is stated: the compliance question is "what left the
        // building", not "which of five chunks contained it", and per-chunk redaction would need a column
        // this schema does not have.
        List<RetrievedChunk> framed = retrieved.returned().stream()
            .map(chunk -> new RetrievedChunk(chunk.chunkId(), chunk.documentId(), chunk.knowledgeBaseId(),
                chunk.documentTitle(), redactedSources.text(), chunk.score(), chunk.documentStatus(),
                chunk.documentUpdatedAt()))
            .toList();
        UntrustedContentAdvisor.FramedPrompt prompt = UntrustedContentAdvisor.frame(
            redactedInstructions.text(), framed, redactedQuestion.text());

        // The budget, on the estimate of the prompt that is about to be sent.
        BudgetGate.BudgetDecision budget = budgetGate.check(scope, snapshot, prompt.sections(), startedAt,
            modelGate.inputPrice(model), model.costCurrency());
        trace.appendStep(scope, runId, step++, "CONTEXT", "OK", null,
            new StepDetail("BUDGET_CHECKED", budget.withinLimits() ? "within-limits" : "over-ceiling-alerted",
                0, 0, 0d, List.of(), null, budget.estimatedPromptTokens(), 0, 0, 0L), startedAt);

        String promptVersion = promptVersion(redactedInstructions.text());

        // Generate through the chain.
        Prompt springPrompt = new Prompt(prompt.messages(), ChatOptions.builder()
            .model(model.model())
            .temperature(version.temperature())
            .maxTokens(version.maxTokens())
            .build());
        ProviderOutcome outcome = providers.execute(snapshot.failoverChain(), springPrompt, model.model());
        ProviderAttempt attempt = outcome.winningAttempt();
        trace.appendStep(scope, runId, step++, "GENERATION", outcome.answered() ? "OK" : "FAILED", null,
            new StepDetail("GENERATION", outcome.refusalCode(), 0, 0, 0d,
                outcome.attemptedProviders(), promptVersion,
                attempt == null ? 0 : attempt.inputTokens(), attempt == null ? 0 : attempt.outputTokens(),
                0, 0L), startedAt);

        if (!outcome.answered()) {
            throw new AiRefusedException(AiRefusalReason.PROVIDER_UNAVAILABLE,
                "providers " + outcome.attemptedProviders() + " failed: "
                    + outcome.failures().stream().map(f -> f.provider() + "=" + f.failure().name()).toList());
        }

        // Tool calls the model asked for. Each re-authorized inside the executor.
        List<AiRunResult.ToolOutcome> toolOutcomes = new ArrayList<>();
        AiToolExecutor.RunBudget runBudget = new AiToolExecutor.RunBudget(
            budgetGate.effectiveMaxToolCalls(snapshot), 0);
        AiToolExecutor.Execution execution = new AiToolExecutor.Execution(principal, scope, snapshot, runId,
            version.id(), runBudget, redactionPolicy);
        for (ProviderAttempt.AssistantToolCall requested : attempt.toolCalls()) {
            AiToolResult toolResult = tools.execute(execution,
                new AiToolInvocation(requested.name(), parseArguments(requested.arguments()), null, false,
                    null, false));
            runBudget = runBudget.next();
            toolOutcomes.add(new AiRunResult.ToolOutcome(requested.name(), toolResult.decision(),
                toolResult.approvalId(), toolResult.refusal()));
            if (toolResult.awaitingApproval()) {
                // The model wanted something consequential. The disposition becomes ESCALATED and no answer
                // is shipped: telling a customer "done" before a human approved is the single worst outcome
                // ADR 0007 Part 2 exists to prevent.
                trace.appendStep(scope, runId, step++, "DECISION", "ESCALATED", null,
                    StepDetail.of("TOOL_APPROVAL_REQUESTED",
                        requested.name() + ":" + toolResult.approvalId()), startedAt);
                return escalated(scope, agent, version, snapshot, model, runId, request, promptVersion,
                    retrieved, retrievalIds, toolOutcomes, startedAt);
            }
        }

        // Redact the response. A failure here is a refusal, not a pass.
        RedactionOutcome redactedAnswer = redaction.redactResponse(redactionPolicy, attempt.text());
        if (redactedAnswer.text() == null || redactedAnswer.text().isBlank()) {
            throw AiRefusedException.of(AiRefusalReason.EMPTY_COMPLETION,
                "the provider returned no usable text after redaction");
        }

        List<RedactionOutcome> allRedactions = List.of(redactedSources, redactedQuestion,
            redactedInstructions, redactedAnswer);
        int redactionCount = redaction.totalValues(allRedactions);
        for (Map.Entry<String, RedactionOutcome.Applied> entry : redaction.merge(allRedactions).entrySet()) {
            RedactionOutcome.Applied applied = entry.getValue();
            trace.writeRedaction(scope, runId, snapshot.policyId(), snapshot.policyVersionId(),
                applied.fieldPath(), applied.ruleId(), applied.fieldPath(), applied.valuesRedacted(),
                applied.charactersRedacted(), startedAt);
        }

        // The charges come from the SAME BudgetDecision that decided whether the run could start, so the
        // posting lands in the bucket the gate summed over. Passing null here — which an earlier version
        // did — leaves ai_budget_consumptions empty forever, and a gate whose only ledger is empty can
        // never stop a tenant who has already spent their month.
        AiUsageAccounting.AiCost cost = accounting.record(scope, principal, runId,
            com.firstelement.finance.catalog.UsageDimension.AI_TOKEN, model, attempt.inputTokens(),
            attempt.outputTokens(), (int) attempt.latencyMillis(), startedAt, budget.charges());

        AiRunDisposition disposition = AiRunDisposition.ANSWERED;
        LocalDateTime finishedAt = LocalDateTime.now(ZoneOffset.UTC);
        int latency = (int) Math.min(Integer.MAX_VALUE,
            java.time.Duration.between(startedAt, finishedAt).toMillis());
        trace.appendStep(scope, runId, step++, "DECISION", "OK", latency,
            StepDetail.of("DISPOSITION", disposition.name()), finishedAt);
        trace.finishRun(scope, runId, disposition.runStatus(), disposition.runDecision(), null,
            disposition.name(), model.model(), null, latency, finishedAt, null);
        trace.writeProvenance(scope, runId, agent.id(), version.id(), promptVersion, model.id(),
            AiProviderKind.parseOrNull(model.provider()), model.model(), snapshot.policyId(),
            snapshot.policyVersionId(), retrievalIds, toolOutcomes.size(),
            (int) toolOutcomes.stream().filter(o -> "DENY".equals(o.decision())).count(), null,
            attempt.inputTokens(), attempt.outputTokens(), cost.amount(), cost.currency(), redactionCount,
            false, null, disposition.name(), finishedAt);

        AiRunResult result = new AiRunResult(runId, disposition, redactedAnswer.text(), null,
            retrieved.returned().stream().map(c -> new AiRunResult.Citation(c.documentTitle(), c.score()))
                .toList(),
            retrievalIds, retrieved.consideredChunkIds(), toolOutcomes, promptVersion,
            outcome.attemptedProviders(), cost.amount(), cost.currency(), attempt.inputTokens(),
            attempt.outputTokens(), redactionCount, prompt.signals());
        log.info("AI run completed run={} conversation={} disposition={} retrieved={} tools={} "
                + "redactions={} providers={}", runId, request.conversationId(), disposition,
            retrieved.returnedCount(), toolOutcomes.size(), redactionCount, outcome.attemptedProviders());
        return result;
    }

    /**
     * Finishes a run that escalated on a consequential tool request. No answer is shipped.
     *
     * <p>The provider's tokens are still accounted for, because they were spent: refusing to record them
     * would make the budget understate what the tenant paid for. The disposition is {@code ESCALATED}, not
     * {@code REFUSED}, because nothing was refused — the model asked, and the application routed it to a
     * human.
     */
    private AiRunResult escalated(TenantScope scope,
                                  AiPolicyRepository.AgentRow agent,
                                  AiPolicyRepository.AgentVersionRow version,
                                  AiPolicySnapshot snapshot,
                                  AiPolicyRepository.ModelRow model,
                                  UUID runId,
                                  RunRequest request,
                                  String promptVersion,
                                  RetrievalOutcome retrieved,
                                  List<UUID> retrievalIds,
                                  List<AiRunResult.ToolOutcome> toolOutcomes,
                                  LocalDateTime startedAt) {
        LocalDateTime finishedAt = LocalDateTime.now(ZoneOffset.UTC);
        int latency = (int) Math.min(Integer.MAX_VALUE,
            java.time.Duration.between(startedAt, finishedAt).toMillis());
        trace.finishRun(scope, runId, AiRunDisposition.ESCALATED.runStatus(),
            AiRunDisposition.ESCALATED.runDecision(), null, "human approval required", model.model(), null,
            latency, finishedAt, null);
        UUID approvalId = toolOutcomes.stream().map(AiRunResult.ToolOutcome::approvalId)
            .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        trace.writeProvenance(scope, runId, agent.id(), version.id(), promptVersion, model.id(),
            AiProviderKind.parseOrNull(model.provider()), model.model(), snapshot.policyId(),
            snapshot.policyVersionId(), retrievalIds, toolOutcomes.size(), 0, approvalId, null, null,
            null, null, 0, false, null, AiRunDisposition.ESCALATED.name(), finishedAt);
        log.info("AI run escalated run={} conversation={} approvals={}", runId, request.conversationId(),
            toolOutcomes.size());
        return new AiRunResult(runId, AiRunDisposition.ESCALATED, null, null,
            retrieved.returned().stream().map(c -> new AiRunResult.Citation(c.documentTitle(), c.score()))
                .toList(),
            retrievalIds, retrieved.consideredChunkIds(), toolOutcomes, promptVersion,
            List.of(model.provider()), null, null, null, null, 0, List.of());
    }

    /**
     * Records a refusal: a step, a finished run, a provenance row.
     *
     * <p>The step goes first so the reason exists even if the run update is refused by a concurrent write,
     * and the provenance row last so it reflects the terminal state. A failure to record is logged with the
     * exception class only — never its message — and the original refusal is still what the caller sees.
     */
    private void refuse(TenantScope scope,
                        AiPolicyRepository.AgentRow agent,
                        AiPolicyRepository.AgentVersionRow version,
                        UUID runId,
                        AiRefusedException refusal,
                        LocalDateTime startedAt) {
        LocalDateTime finishedAt = LocalDateTime.now(ZoneOffset.UTC);
        try {
            trace.appendStep(scope, runId, REFUSAL_SEQUENCE, "DECISION", "REFUSED", null,
                StepDetail.of("REFUSED", refusal.code()), finishedAt);
            trace.finishRun(scope, runId, "FAILED", null, null, refusal.code(), version.model(),
                refusal.code(), (int) Math.min(Integer.MAX_VALUE,
                    java.time.Duration.between(startedAt, finishedAt).toMillis()), finishedAt, null);
            trace.writeProvenance(scope, runId, agent.id(), version.id(), null, null, null, null, null, null,
                List.of(), 0, 0, null, null, null, null, null, 0, true, null,
                AiRunDisposition.REFUSED.name(), finishedAt);
        } catch (RuntimeException e) {
            log.error("Failed to record an AI refusal run={} reason={} recordFailure={}", runId,
                refusal.code(), e.getClass().getSimpleName());
        }
    }

    /**
     * The prompt version an answer is attributed to.
     *
     * <p>A content hash of the framed instructions, not the text. Storing the prompt would be a durable copy
     * of the agent's configuration that drifts from the version it claims to represent; a hash is stable,
     * comparable, and carries no content. FR-5.8 requires the "prompt/template version used", and this is
     * what makes that checkable months later without keeping the prompt.
     */
    static String promptVersion(String systemInstructions) {
        return "sha-" + SecretScrubber.fingerprint(systemInstructions == null ? "" : systemInstructions);
    }

    /** The knowledge bases the agent version names. Never the tenant's whole corpus. */
    private static List<UUID> knowledgeBases(AiPolicyRepository.AgentVersionRow version) {
        List<UUID> bases = new ArrayList<>();
        for (String raw : AiPolicyResolver.strings(version.knowledgeBaseIds())) {
            try {
                bases.add(UUID.fromString(raw));
            } catch (IllegalArgumentException e) {
                // A malformed id in the agent version's jsonb is skipped rather than fatal: the run
                // retrieves from the bases it can name and the trace shows how many chunks it considered.
                log.warn("agent version {} names a knowledge base that is not a uuid", version.id());
            }
        }
        return bases;
    }

    /**
     * Parses a model's argument JSON.
     *
     * <p>Tolerant on purpose: an unparseable argument object becomes a shape the validator refuses, so the
     * model gets a recorded DENY rather than an exception from three frames up. Returning
     * {@code {"__unparseableArguments": "true"}} is an <em>undeclared</em> argument name, which the
     * validator refuses as {@code ARGUMENT_NOT_ALLOWED} before anything reads it.
     */
    static Map<String, Object> parseArguments(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(json, Map.class);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            return new LinkedHashMap<>(Map.of("__unparseableArguments", "true"));
        }
    }

    /** Whether AI is switched on for this deployment. */
    public boolean enabled() {
        return properties.isEnabled();
    }

    /** The principal type an AI run is attributed to. Never a USER, even when a user triggered it. */
    public static PrincipalType runPrincipalType() {
        return PrincipalType.AI_AGENT;
    }
}