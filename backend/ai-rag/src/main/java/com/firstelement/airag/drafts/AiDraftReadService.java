package com.firstelement.airag.drafts;

import com.firstelement.crm.persistence.ConversationRepository;
import com.firstelement.crm.query.CrmQueryScope;
import com.firstelement.security.TenantContext;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.permission.AuthorizationService;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.query.TenantQueryGuard;
import com.firstelement.security.query.TenantScope;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The read model behind the pending-draft route.
 *
 * <h2>Why the service holds the scope rule and the controller holds none</h2>
 *
 * <p>AGENTS.md and plan §0 rule 4: no controller-only authorization. The tenant comes from the
 * authenticated {@link TenantContext} the security filter resolved, and the project comes from the
 * conversation row looked up inside {@link TenantQueryGuard} — never from a request field. A probe
 * therefore cannot name a tenant: there is no parameter to name.
 *
 * <h2>Why the miss is one refusal</h2>
 *
 * <p>Three facts — "no such conversation", "a conversation in another tenant", "a conversation in a
 * project you have left" — all return {@link Optional#empty()} from the tenant-scoped lookup and are
 * answered by the controller with the same uniform 404. This is the Phase-33 clinic shape
 * ({@code ClinicController.ResourceNotFound}): a per-resource message is itself an existence oracle,
 * separating "no such conversation anywhere" from "that conversation is not yours".
 *
 * <h2>Why a withheld draft is invisible rather than flagged</h2>
 *
 * <p>A draft whose run carries {@link AiResponseJobHandler#WITHHELD_REASON} is not returned at all,
 * even though its text exists in {@code notes}. The alternative — returning it with a
 * {@code withheld=true} flag — would put a decision a human already made back in front of a human,
 * one click from being sent. Refusing to serve it is the same property
 * {@code AiResponseJobHandler} enforces on the write side, read back through the run's terminal state.
 *
 * <h2>Why the response carries no chain-of-thought and no reasoning</h2>
 *
 * <p>It carries the answer, its citations, the agent/prompt/model attribution and the run's terminal
 * decision. There is no step detail, no tool argument, no retrieved snippet and no reasoning field:
 * ADR 0007 Part 2 rule 4. The citations are titles and scores only, because {@code AiRunResult.Citation}
 * is defined that way deliberately — a citation carrying content would defeat both its purpose and the
 * redaction applied to the snippet.
 */
@Service
public class AiDraftReadService {

    private final ConversationRepository conversations;
    private final AiDraftRepository drafts;
    private final TenantQueryGuard guard;
    private final AuthorizationService authorization;

    public AiDraftReadService(ConversationRepository conversations,
                              AiDraftRepository drafts,
                              TenantQueryGuard guard,
                              AuthorizationService authorization) {
        this.conversations = conversations;
        this.drafts = drafts;
        this.guard = guard;
        this.authorization = authorization;
    }

    /**
     * The pending draft on a conversation, or empty.
     *
     * <p>Empty covers "no conversation you can see", "no draft yet", and "a human took the
     * conversation over" — deliberately one answer rather than three, so the route cannot be used to
     * distinguish them.
     *
     * @param conversationId the conversation whose pending draft is wanted. A path value naming a
     *                       resource, never a tenant: the scope is resolved from the authenticated
     *                       context before any SQL is assembled
     */
    @RequirePermission(Permission.AI_RUN_READ)
    public Optional<PendingDraft> pendingDraft(TenantContext context, UUID conversationId) {
        if (conversationId == null) {
            throw new com.firstelement.crm.error.ValidationFailedException(
                "conversationId is required", java.util.Map.of());
        }
        // Defence in depth: the route carries @RequirePermission for the HTTP edge, and this is the
        // service-level check AGENTS.md requires so the rule does not live only in the controller.
        authorization.check(context, Permission.AI_RUN_READ);

        // The project is discovered from the conversation, under the guard, and then re-validated by
        // the same guard against the principal's membership. A conversation in a project the caller
        // has left fails here and is answered exactly like one that does not exist.
        var found = conversations.findInScope(CrmQueryScope.accessibleProjects(context), conversationId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        var row = found.get();
        // The read-then-check path, which is what `assertRowScope` exists for: a conversation's
        // project can only be known after reading the row. It returns the scope to read the draft
        // with, so the row's own project — not a request field — decides the predicate.
        TenantScope scope = guard.assertRowScope(context, row.organizationId(), row.projectId());

        return drafts.latestOfferableDraft(scope, conversationId,
            AiResponseJobHandler.WITHHELD_REASON)
            .map(draft -> new PendingDraft(conversationId, draft.runId(), draft.body(),
                draft.createdAt(), row.mode(), row.modeVersion()));
    }

    /**
     * What a human is shown before deciding whether to send.
     *
     * @param conversationId  the conversation the draft would answer in
     * @param runId           the {@code ai_runs} row this draft is attributable to
     * @param body            the redacted answer text, offered as a draft and never as a sent message
     * @param createdAt       when the draft was stored
     * @param conversationMode the mode observed at read time, so a takeover is visible to the reader
     * @param conversationModeVersion the mode version observed, for the reader to reason about a
     *                                concurrent takeover rather than guessing
     */
    public record PendingDraft(UUID conversationId,
                               UUID runId,
                               String body,
                               LocalDateTime createdAt,
                               String conversationMode,
                               int conversationModeVersion) {
    }
}