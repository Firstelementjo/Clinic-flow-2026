package com.firstelement.airag.redaction;

import com.firstelement.airag.policy.AiPolicySnapshot;
import com.firstelement.airag.policy.AiRefusalReason;
import com.firstelement.airag.policy.AiRefusedException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Applies a tenant's redaction policy to the prompt and to the response, and refuses when it cannot.
 *
 * <h2>Both sides, and why the response side is the one people forget</h2>
 *
 * <p>Redacting the prompt only is the obvious mistake and it is a real one: a provider can echo a prompt,
 * a model can be induced to repeat one, and a retrieved snippet can survive into an answer through
 * paraphrase of a value the rules would have removed. FR-5.4/ADR 0007 Part 2 rule 4 requires the recorded
 * stages, and a customer-visible answer is the last place unredacted content may appear. So
 * {@link #redactResponse} is not optional and the orchestrator calls it on every answer, including a
 * refusal's own text.
 *
 * <h2>The policy is resolved, never assumed</h2>
 *
 * <p>{@link #policyFor(AiPolicySnapshot)} resolves the approved version's policy name through
 * {@link RedactionPolicyDefinition#byName} and <b>refuses</b> on an unknown name. It does not fall back.
 * The fallback in {@code AiRagProperties} is only consulted by the caller to decide <em>which</em> refusal
 * to record, and even then the run stops — see {@link #policyForOrRefuse}.
 *
 * <h2>Compounding the two sides</h2>
 *
 * <p>Redaction is applied per section rather than to the assembled prompt, because the section boundaries
 * are where the rules differ: {@code prompt.untrusted} is retrieved customer content and gets the full
 * clinical rule set, while {@code response.assistant} is the model's own words and gets the same rules
 * because a model can repeat anything. The records name the section, so a compliance report can say
 * <em>where</em> content was removed rather than only that it was.
 */
@Component
public class AiRedactionEngine {

    private static final Logger log = LoggerFactory.getLogger(AiRedactionEngine.class);

    /** The stages, named so a redaction record's field path is a closed vocabulary. */
    public static final String STAGE_SYSTEM = "prompt.system";
    public static final String STAGE_UNTRUSTED = "prompt.untrusted";
    public static final String STAGE_MEMORY = "prompt.memory";
    public static final String STAGE_RESPONSE = "response.assistant";

    /** Every stage name, so a caller cannot invent a field path that no record will match. */
    public static final List<String> STAGES = List.of(STAGE_SYSTEM, STAGE_UNTRUSTED, STAGE_MEMORY,
        STAGE_RESPONSE);

    /**
     * Resolves the policy for a run.
     *
     * @throws AiRefusedException {@link AiRefusalReason#REDACTION_FAILED} when the approved policy names a
     *         policy this build does not implement. The message names the declared policies so an
     *         administrator can fix the row without reading this source.
     */
    public RedactionPolicyDefinition policyFor(AiPolicySnapshot snapshot) {
        String name = snapshot == null ? null : snapshot.redactionPolicy();
        RedactionPolicyDefinition definition = RedactionPolicyDefinition.byName(name);
        if (definition == null) {
            throw AiRefusedException.of(AiRefusalReason.REDACTION_FAILED,
                "approved policy version names redaction policy '" + name + "', which this build does not "
                    + "implement. Declared policies are " + RedactionPolicyDefinition.declaredNames()
                    + ". No fallback is applied, because running different rules than the approved version "
                    + "recorded would make the compliance report untrue.");
        }
        return definition;
    }

    /** Resolves, or refuses with the deployment's declared fallback named in the message. */
    public RedactionPolicyDefinition policyForOrRefuse(AiPolicySnapshot snapshot, String fallbackName) {
        try {
            return policyFor(snapshot);
        } catch (AiRefusedException e) {
            RedactionPolicyDefinition fallback = RedactionPolicyDefinition.byName(fallbackName);
            throw AiRefusedException.of(AiRefusalReason.REDACTION_FAILED,
                "no usable redaction policy (approved='" + (snapshot == null ? "none"
                    : snapshot.redactionPolicy()) + "', declared fallback='"
                    + (fallback == null ? fallbackName : fallback.name()) + "'); declared policies are "
                    + RedactionPolicyDefinition.declaredNames());
        }
    }

    /**
     * Redacts one section.
     *
     * @param stage one of {@link #STAGES}; an unknown stage is refused rather than recorded, because
     *              {@code ai_redaction_records."fieldPath"} is a compliance surface and an arbitrary path
     *              would let a caller write "no content here" into it
     * @throws AiRedactionFailedException if the stage is unknown or a rule cannot be applied
     */
    public RedactionOutcome redact(RedactionPolicyDefinition policy, String stage, String text) {
        requireStage(stage);
        if (policy == null) {
            throw new AiRedactionFailedException(stage, "no redaction policy resolved", null);
        }
        if (text == null || text.isEmpty()) {
            return new RedactionOutcome(text, List.of(), 0, 0, policy.name());
        }

        String current = text;
        List<RedactionOutcome.Applied> applied = new ArrayList<>();
        int totalValues = 0;
        int totalCharacters = 0;
        for (RedactionRule rule : policy.rules()) {
            try {
                RedactionRule.Match match = rule.apply(current);
                if (match.fired()) {
                    current = match.text();
                    applied.add(new RedactionOutcome.Applied(rule.id(), stage, match.count(),
                        match.charactersRemoved()));
                    totalValues += match.count();
                    totalCharacters += match.charactersRemoved();
                }
            } catch (RuntimeException e) {
                // A StackOverflowError from a pathological backtrack surfaces as RuntimeException in some
                // engines and as an Error in others; both are "the rule did not complete", and both are
                // a failure. The text is not logged and not included in the message.
                throw new AiRedactionFailedException(stage,
                    "rule " + rule.id() + " could not be applied to a " + text.length()
                        + "-character section", e);
            }
        }
        if (totalValues > 0) {
            // Counts only. The removed characters are a number and the text never is, so a log line here
            // cannot become a disclosure.
            log.info("redaction applied policy={} stage={} values={} characters={}", policy.name(), stage,
                totalValues, totalCharacters);
        }
        return new RedactionOutcome(current, applied, totalValues, totalCharacters, policy.name());
    }

    /** Redacts the retrieved content that is about to enter a prompt. */
    public RedactionOutcome redactUntrustedContent(RedactionPolicyDefinition policy,
                                                   List<com.firstelement.airag.retrieval.RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return new RedactionOutcome("", List.of(), 0, 0, policy == null ? null : policy.name());
        }
        RedactionOutcome combined = redact(policy, STAGE_UNTRUSTED, join(chunks));
        // The per-chunk structure is lost by joining, so the count is the sum over chunks and the
        // individual redactions are recorded against the joined section. Recording per chunk would need a
        // second table column for the chunk id, and the compliance question is "what left the building",
        // not "which of five chunks contained it".
        return combined;
    }

    /** Redacts the model's answer before it can be sent. */
    public RedactionOutcome redactResponse(RedactionPolicyDefinition policy, String answer) {
        return redact(policy, STAGE_RESPONSE, answer);
    }

    /** Combines several outcomes into the run's totals, for the provenance row's redactionCount. */
    public int totalValues(List<RedactionOutcome> outcomes) {
        int total = 0;
        for (RedactionOutcome outcome : outcomes) {
            if (outcome != null) {
                total += outcome.totalValues();
            }
        }
        return total;
    }

    /** Merges the applied records of several outcomes, keyed by rule and stage. */
    public Map<String, RedactionOutcome.Applied> merge(List<RedactionOutcome> outcomes) {
        Map<String, RedactionOutcome.Applied> merged = new LinkedHashMap<>();
        for (RedactionOutcome outcome : outcomes) {
            if (outcome == null) {
                continue;
            }
            for (RedactionOutcome.Applied applied : outcome.applied()) {
                String key = applied.ruleId() + "@" + applied.fieldPath();
                merged.merge(key, applied, (left, right) -> new RedactionOutcome.Applied(
                    left.ruleId(), left.fieldPath(),
                    left.valuesRedacted() + right.valuesRedacted(),
                    left.charactersRedacted() == null || right.charactersRedacted() == null ? null
                        : left.charactersRedacted() + right.charactersRedacted()));
            }
        }
        return merged;
    }

    private static void requireStage(String stage) {
        if (!STAGES.contains(stage)) {
            throw new AiRedactionFailedException(String.valueOf(stage),
                "unknown redaction stage; declared stages are " + STAGES, null);
        }
    }

    private static String join(List<com.firstelement.airag.retrieval.RetrievedChunk> chunks) {
        StringBuilder text = new StringBuilder();
        for (com.firstelement.airag.retrieval.RetrievedChunk chunk : chunks) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(chunk.snippet() == null ? "" : chunk.snippet());
        }
        return text.toString();
    }
}