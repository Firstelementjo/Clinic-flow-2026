package com.firstelement.airag.policy;

import com.firstelement.airag.provider.AiProviderKind;
import java.util.List;
import java.util.UUID;

/**
 * The policy a run executes under, resolved once at the start and pinned for the whole run.
 *
 * <h2>Why resolved once</h2>
 *
 * <p>Because the run must be attributable. ADR 0007 Part 2 rule 7 requires every action to name the agent
 * version, the prompt version, the model and the provider — and a policy read three times can return
 * three different answers if an administrator edits it mid-run. So the resolver reads the live policy and
 * its APPROVED version <em>once</em>, and every later check in the run reads this record.
 *
 * <h2>Why it carries a version id</h2>
 *
 * <p>{@code ai_provenance_records."policyVersionId"} is nullable in the schema, and it is null here only
 * when {@link #policyVersionId()} is absent because no version was approved — which
 * {@link AiPolicyResolver} refuses rather than resolving. In other words the nullable column is
 * <em>not</em> a licence to run without an approved version; it exists because a refusal still has to be
 * attributable to <em>something</em>.
 *
 * @param policyId        the {@code ai_policies} row, never null for an accepted run
 * @param policyVersionId the APPROVED {@code ai_policy_versions} row
 * @param key             the tenant's own name for this policy, for the operator UI
 * @param redactionPolicy the policy name to hand the redaction engine; an unknown name is a refusal
 * @param retentionClass  the tenant's retention class, copied onto the audit rows
 * @param maxTokensPerRun the tenant's own prompt ceiling; {@code null} means the deployment's applies
 * @param maxToolCallsPerRun the tenant's tool ceiling; {@code null} means the deployment's applies
 * @param failoverChain   the ordered provider kinds. Never empty for an accepted run.
 * @param residencyZone   the zone this tenant is allowed to send customer content to
 * @param customerVisibleRequiresApproval   ADR 0007 action class: a customer-visible reply
 * @param financialActionRequiresApproval   booking/rescheduling, money
 * @param permissionChangeRequiresApproval  roles and memberships
 * @param externalSideEffectRequiresApproval anything that leaves the system
 */
public record AiPolicySnapshot(UUID policyId,
                               UUID policyVersionId,
                               String key,
                               String redactionPolicy,
                               String retentionClass,
                               Integer maxTokensPerRun,
                               Integer maxToolCallsPerRun,
                               List<AiProviderKind> failoverChain,
                               String residencyZone,
                               boolean customerVisibleRequiresApproval,
                               boolean financialActionRequiresApproval,
                               boolean permissionChangeRequiresApproval,
                               boolean externalSideEffectRequiresApproval,
                               int policyVersionNumber) {

    public AiPolicySnapshot {
        failoverChain = failoverChain == null ? List.of() : List.copyOf(failoverChain);
    }

    /**
     * Whether an action class needs a human approval before it may run.
     *
     * <p>The four booleans are OR-ed rather than selected by a string, because a run is consequential if
     * <em>any</em> of its facets is. Picking one class per action is exactly how "book an appointment"
     * would be classified as merely answerable because the request also mentioned a time.
     */
    public boolean requiresApprovalFor(ActionClass actionClass) {
        return switch (actionClass) {
            case ANSWERABLE -> false;
            case CONSEQUENTIAL -> customerVisibleRequiresApproval || financialActionRequiresApproval
                || permissionChangeRequiresApproval || externalSideEffectRequiresApproval;
            case FORBIDDEN -> true;
        };
    }

    /** ADR 0007 Part 2's three action classes, spelled exactly as the schema CHECKs spell them. */
    public enum ActionClass {
        ANSWERABLE,
        CONSEQUENTIAL,
        FORBIDDEN
    }
}