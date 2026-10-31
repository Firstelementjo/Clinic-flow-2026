package com.firstelement.airag.policy;

import com.firstelement.security.query.TenantScope;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The model allow-list and the residency check. Both run before retrieval.
 *
 * <h2>Why this is a gate and not a query</h2>
 *
 * <p>Because the model is chosen from configuration, and a run whose model is not registered is a run whose
 * cost cannot be computed, whose residency is unknown, and whose provenance cannot name the price that
 * produced it. All three are consequences of the same lookup failing, and catching it before retrieval
 * means the retrieval — which reads customer content — does not happen at all.
 *
 * <h2>What "allowed" means precisely</h2>
 *
 * <ol>
 *   <li>the registry has a row for {@code (provider, model)} visible to this scope;</li>
 *   <li>that row is enabled — the registry is deny-by-default, so an absent row and a disabled row are both
 *       {@link AiRefusalReason#MODEL_NOT_ALLOWED};</li>
 *   <li>the row's provider equals the chain's first hop, so the tenant's registered model and the agent's
 *       provider cannot disagree silently;</li>
 *   <li>the row's {@code dataRetentionPolicy} is admitted by the snapshot's residency zone.</li>
 * </ol>
 *
 * <h2>Which row wins when both a tenant-wide and a project row exist</h2>
 *
 * <p>The <b>project</b> row. A tenant-wide row is the default; a project row is the explicit override, and
 * "most specific wins" is the same rule {@code RateCardResolver} uses for cards. The comparison is on
 * {@code (provider, model)}, so a project can disable a model the tenant enabled without deleting the
 * tenant's row for the other projects that still use it.
 */
@Component
public class AiModelGate {

    private final AiPolicyRepository policies;

    public AiModelGate(AiPolicyRepository policies) {
        this.policies = policies;
    }

    /**
     * Resolves the model for this run, or refuses.
     *
     * @param chain the already-resolved provider chain; its first hop is the provider the model must belong to
     * @throws AiRefusedException {@link AiRefusalReason#MODEL_NOT_ALLOWED},
     *         {@link AiRefusalReason#MODEL_PROVIDER_MISMATCH} or
     *         {@link AiRefusalReason#RESIDENCY_VIOLATION}
     */
    public AiPolicyRepository.ModelRow requireModel(TenantScope scope,
                                                    AiPolicySnapshot snapshot,
                                                    String requestedModel,
                                                    List<com.firstelement.airag.provider.AiProviderKind> chain) {
        if (requestedModel == null || requestedModel.isBlank()) {
            throw AiRefusedException.of(AiRefusalReason.MODEL_NOT_ALLOWED,
                "the agent version names no model");
        }
        if (chain == null || chain.isEmpty()) {
            throw AiRefusedException.of(AiRefusalReason.NO_PROVIDER_CHAIN);
        }
        String leadProvider = chain.get(0).wireValue();

        List<AiPolicyRepository.ModelRow> registry = policies.models(scope);
        Optional<AiPolicyRepository.ModelRow> tenantWide = registry.stream()
            .filter(row -> row.projectId() == null)
            .filter(row -> leadProvider.equals(row.provider()) && requestedModel.equals(row.model()))
            .findFirst();
        Optional<AiPolicyRepository.ModelRow> projectScoped = registry.stream()
            .filter(row -> row.projectId() != null)
            .filter(row -> leadProvider.equals(row.provider()) && requestedModel.equals(row.model()))
            .findFirst();

        AiPolicyRepository.ModelRow row = projectScoped.or(() -> tenantWide)
            .orElseThrow(() -> AiRefusedException.of(AiRefusalReason.MODEL_NOT_ALLOWED,
                "model " + requestedModel + " is not in the registry for provider " + leadProvider));

        if (!row.enabled()) {
            throw AiRefusedException.of(AiRefusalReason.MODEL_NOT_ALLOWED,
                "model " + requestedModel + " is registered but disabled");
        }
        if (!leadProvider.equals(row.provider())) {
            throw AiRefusedException.of(AiRefusalReason.MODEL_PROVIDER_MISMATCH,
                "chain leads with " + leadProvider + " but the registry row says " + row.provider());
        }
        if (!AiPolicyResolver.residencyAdmits(snapshot, row.dataRetentionPolicy())) {
            throw AiRefusedException.of(AiRefusalReason.RESIDENCY_VIOLATION,
                "model " + requestedModel + " declares retention policy '" + row.dataRetentionPolicy()
                    + "' which the residency zone " + snapshot.residencyZone() + " does not admit");
        }
        return row;
    }

    /**
     * Whether this deployment's registry sends customer content.
     *
     * <p>{@code ai_model_registry."sendsCustomerData"} is the switch a data-protection review looks for, and
     * it defaults to {@code false}. It is surfaced on the provenance row so an answer can be explained
     * later with the knowledge of whether the provider was allowed to see the text at all.
     */
    public boolean sendsCustomerData(AiPolicyRepository.ModelRow row) {
        return row != null && row.sendsCustomerData();
    }

    /** The per-1k input price, or {@code null} when the tenant registered none. */
    public BigDecimal inputPrice(AiPolicyRepository.ModelRow row) {
        return row == null ? null : row.inputCostPer1kTokens();
    }

    /** The per-1k output price, or {@code null} when the tenant registered none. */
    public BigDecimal outputPrice(AiPolicyRepository.ModelRow row) {
        return row == null ? null : row.outputCostPer1kTokens();
    }
}