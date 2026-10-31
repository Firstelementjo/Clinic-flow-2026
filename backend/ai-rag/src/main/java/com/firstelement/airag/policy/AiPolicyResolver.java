package com.firstelement.airag.policy;

import com.firstelement.airag.config.AiRagProperties;
import com.firstelement.airag.provider.AiProviderKind;
import com.firstelement.security.query.TenantScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Resolves the policy a run executes under, and refuses when there is none.
 *
 * <h2>Deny by default, in the order the refusals are tried</h2>
 *
 * <ol>
 *   <li>{@code firstelement.ai.enabled}.</li>
 *   <li>a live, enabled {@code ai_policies} row for the project — {@link AiRefusalReason#POLICY_MISSING}.</li>
 *   <li>an APPROVED {@code ai_policy_versions} row — {@link AiRefusalReason#POLICY_VERSION_NOT_APPROVED}.</li>
 *   <li>a provider chain from the version's own {@code failoverChain}, falling back to the policy row's,
 *       falling back to the deployment default. An empty result is
 *       {@link AiRefusalReason#NO_PROVIDER_CHAIN}, never a silent default to whatever is on the
 *       classpath.</li>
 * </ol>
 *
 * <p>The order matters: a missing policy is reported as a missing policy even when the deployment default
 * provider would have answered, because "AI ran with no policy" is the failure ADR 0007 Part 2 exists to
 * prevent, and reporting it as {@code PROVIDER_UNAVAILABLE} would send an operator looking at the wrong
 * system.
 *
 * <h2>Why the chain comes from data, with a compiled floor</h2>
 *
 * <p>{@code ai_policies."failoverChain"} is {@code jsonb} holding an array of strings, constrained by the
 * migration to be an array of strings. Names outside {@link AiProviderKind} are dropped rather than
 * passed to the executor, so a typo in the database removes one hop instead of producing a provider kind
 * the registry cannot resolve. The compiled floor is the deployment default, appended last, which is what
 * makes "no provider key configured" work: the chain always ends somewhere that can answer.
 */
@Component
public class AiPolicyResolver {

    private final AiPolicyRepository policies;
    private final AiRagProperties properties;

    public AiPolicyResolver(AiPolicyRepository policies, AiRagProperties properties) {
        this.policies = policies;
        this.properties = properties;
    }

    /**
     * The snapshot for one run.
     *
     * @param scope a <b>project-scoped</b> scope. {@code ai_policies."projectId"} is NOT NULL and
     *              {@code ai_runs."projectId"} is NOT NULL, so a tenant-wide scope cannot name a policy;
     *              refusing here is clearer than a statement that fails on an unbound parameter.
     * @param agentProvider the provider named by the pinned agent version, which leads the chain
     * @throws AiRefusedException with a named reason
     */
    public AiPolicySnapshot resolve(TenantScope scope, String agentProvider) {
        if (!properties.isEnabled()) {
            throw AiRefusedException.of(AiRefusalReason.AI_DISABLED);
        }
        if (!scope.isProjectScoped()) {
            // A tenant-wide scope cannot name an ai_policies row. Refusing rather than defaulting to a
            // project is the whole point of FR-1.3.
            throw AiRefusedException.of(AiRefusalReason.POLICY_MISSING,
                "an AI policy is project-scoped; the caller's scope carries no project");
        }

        AiPolicyRepository.PolicyRow policy = policies.policy(scope, null)
            .orElseThrow(() -> AiRefusedException.of(AiRefusalReason.POLICY_MISSING,
                "no enabled ai_policies row for this project"));

        List<AiPolicyRepository.PolicyVersionRow> approved = policies.approvedVersions(scope, policy.id());
        if (approved.isEmpty()) {
            throw AiRefusedException.of(AiRefusalReason.POLICY_VERSION_NOT_APPROVED,
                "policy " + policy.key() + " has no APPROVED version");
        }
        if (approved.size() > 1) {
            // ai_policy_versions_one_approved_per_policy makes this impossible. Refusing is how the
            // invariant stays true rather than becoming a comment.
            throw AiRefusedException.of(AiRefusalReason.POLICY_VERSION_NOT_APPROVED,
                "policy " + policy.key() + " has " + approved.size() + " APPROVED versions; the partial "
                    + "unique index has been dropped");
        }
        AiPolicyRepository.PolicyVersionRow version = approved.get(0);

        List<AiProviderKind> chain = chain(version.failoverChain(), policy.failoverChain(), agentProvider);
        if (chain.isEmpty()) {
            throw AiRefusedException.of(AiRefusalReason.NO_PROVIDER_CHAIN,
                "policy " + policy.key() + " version " + version.versionNumber() + " names no provider");
        }

        return new AiPolicySnapshot(
            policy.id(),
            version.id(),
            policy.key(),
            // The VERSION's redaction policy wins over the mutable row's. A version is what was approved;
            // letting the live row change the rules under an approved version would make the version
            // unreproducible.
            version.redactionPolicy(),
            version.retentionClass(),
            policy.maxTokensPerRun(),
            policy.maxToolCallsPerRun(),
            chain,
            properties.getDataResidencyZone(),
            policy.customerVisibleRequiresApproval(),
            policy.financialActionRequiresApproval(),
            policy.permissionChangeRequiresApproval(),
            policy.externalSideEffectRequiresApproval(),
            version.versionNumber());
    }

    /**
     * The ordered chain: the agent's provider first, then the approved version's chain, then the policy
     * row's chain, then the deployment default.
     *
     * <p>Order, and why: the agent version is the thing the tenant pinned for this agent, so it leads. The
     * approved version's chain is the tenant's declared fallback order. The mutable row's chain is the
     * legacy spelling and is consulted last so it can never outrank an approved version. The deployment
     * default is last so the chain always terminates somewhere that answers without a key.
     */
    List<AiProviderKind> chain(String versionJson, String policyJson, String agentProvider) {
        List<String> raw = new ArrayList<>();
        AiProviderKind agent = AiProviderKind.parseOrNull(agentProvider);
        if (agent != null) {
            raw.add(agent.wireValue());
        }
        raw.addAll(strings(versionJson));
        raw.addAll(strings(policyJson));
        AiProviderKind fallback = AiProviderKind.parseOrNull(properties.getDefaultProvider());
        if (fallback != null) {
            raw.add(fallback.wireValue());
        }

        List<AiProviderKind> chain = new ArrayList<>();
        for (String candidate : raw) {
            AiProviderKind kind = AiProviderKind.parseOrNull(candidate);
            if (kind != null && !chain.contains(kind)) {
                chain.add(kind);
            }
        }
        return List.copyOf(chain);
    }

    /**
     * A {@code jsonb} string array, read leniently. Malformed JSON contributes no hops, not an error.
     *
     * <p>Leniency is deliberate and narrow: {@code ai_agent_versions."knowledgeBaseIds"} and
     * {@code ai_policies."failoverChain"} are arrays of strings by schema CHECK, so a malformed value means
     * something wrote to the column outside this runtime. Contributing nothing is the safe reading — a run
     * with an unreadable chain reaches no provider, and a run with an unreadable knowledge-base list
     * retrieves from nothing and is refused as {@code RETRIEVAL_EMPTY}. Both fail closed.
     */
    public static List<String> strings(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            Object parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(json, Object.class);
            if (!(parsed instanceof List<?> list)) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (Object value : list) {
                if (value instanceof String text && !text.isBlank()) {
                    out.add(text);
                }
            }
            return List.copyOf(out);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Whether the snapshot's residency zone admits a model's declared retention policy. */
    static boolean residencyAdmits(AiPolicySnapshot snapshot, String declaredZone) {
        if (declaredZone == null || declaredZone.isBlank()) {
            // The column is NOT NULL, so this is unreachable; a blank is treated as "undeclared" and
            // refused, because "we do not know where it goes" is not an answer to "may we send".
            return false;
        }
        String declared = declaredZone.trim().toUpperCase(java.util.Locale.ROOT);
        String allowed = snapshot.residencyZone() == null ? ""
            : snapshot.residencyZone().trim().toUpperCase(java.util.Locale.ROOT);
        if (allowed.equals("LOCAL_ONLY")) {
            return declared.equals("LOCAL") || declared.equals("ON_PREMISE") || declared.equals("IN_REGION");
        }
        return declared.equals(allowed) || declared.equals("IN_REGION") || declared.equals("ON_PREMISE");
    }

    /** The model gate's answer for one registry row, for the resolver's own unit tests. */
    Optional<AiProviderKind> providerOf(AiPolicyRepository.ModelRow row) {
        return row == null ? Optional.empty() : Optional.ofNullable(AiProviderKind.parseOrNull(row.provider()));
    }

    /** Exposed so the orchestrator can name the agent and version it pinned, for the provenance row. */
    public UUID policyIdOf(AiPolicySnapshot snapshot) {
        return snapshot == null ? null : snapshot.policyId();
    }
}