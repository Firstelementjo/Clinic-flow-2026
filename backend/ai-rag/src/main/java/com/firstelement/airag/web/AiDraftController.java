package com.firstelement.airag.web;

import com.firstelement.airag.drafts.AiDraftReadService;
import com.firstelement.security.TenantContextHolder;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.permission.Permission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/ai/drafts} — the pending AI draft a human is offered before deciding to send it.
 *
 * <pre>
 *   GET /api/v1/ai/drafts/conversations/{conversationId}   AI_RUN_READ
 * </pre>
 *
 * <h2>Read-only, and that is the ruling</h2>
 *
 * <p>There is no POST, no confirm and no send on this controller. ADR 0007 Part 2 says the model may
 * send customer-visible replies only in AI mode and that anything consequential needs human approval;
 * this surface satisfies it more strictly than a send would, because nothing here can reach a
 * customer. The decision to speak remains a human's, taken through the existing conversation-reply
 * route.
 *
 * <h2>Why {@code AI_RUN_READ} and not {@code AI_SEND}</h2>
 *
 * <p>ADR 0010 §2: a permission is named after what it authorizes. Reading a governed run is not
 * speaking to a customer, and Phase 22 recorded that a send which reused the read-only
 * {@code AI_RUN_READ} is precisely how {@code AI_SEND} came to exist. This route is the read half, so
 * it takes the read permission — and a principal holding {@code AI_RUN_READ} but not {@code AI_SEND}
 * can see a draft and still be unable to send it.
 *
 * <h2>Where the scope comes from</h2>
 *
 * <p>{@link TenantContextHolder#get()} only. There is no {@code organizationId}, {@code tenantId} or
 * {@code projectId} parameter on this route — {@code check-architecture.ps1} rule FE-SEC-002 fails the
 * build if a controller binds a client tenant identifier, and this one cannot. The project is derived
 * from the conversation row inside the tenant-scoped lookup, which is the legacy cross-tenant breach
 * (§10 of {@code docs/migration/migration-ledger.md}) answered structurally rather than by review.
 *
 * <h2>The uniform 404, and why it is one refusal</h2>
 *
 * <p>A nonexistent conversation, a conversation in another tenant, and a conversation in a project the
 * caller has left are the <b>same status, code and body</b> — the Phase-33 clinic shape. The lookup is
 * tenant-scoped and a miss is a miss, so there is no branch that could tell them apart. A withheld
 * draft (human takeover) and a conversation with no draft yet are likewise both this same 404: the
 * route cannot be used as an oracle for "did the AI answer, and was it stopped".
 *
 * <h2>Refusals, derived from what this route does</h2>
 *
 * <ul>
 *   <li><b>401</b> — the path is not public, so an unauthenticated caller is refused by the filter
 *       chain before the handler runs.</li>
 *   <li><b>403</b> — {@code @RequirePermission(AI_RUN_READ)}: an authenticated caller without the
 *       grant is refused.</li>
 *   <li><b>400</b> — the path variable is a typed {@link UUID}. An unparseable id raises
 *       {@code MethodArgumentTypeMismatchException}, which the application advice renders as
 *       {@code 400 VALIDATION_FAILED}. Declared because the route really produces one.</li>
 *   <li><b>404</b> — the operation names a resource, so it can answer "no such conversation in your
 *       scope", which is also the non-disclosing refusal for a foreign one.</li>
 * </ul>
 *
 * <p>Nothing else is declared. This route consumes no body, so there is no 415 and no body-binding
 * 400; it is a GET, so there is no CSRF refusal; and it performs no optimistic-concurrency check, so
 * there is no 409. A declared refusal a route cannot produce is the same lie as a wrong success code
 * (ADR 0016 rule 3).
 */
@RestController
@RequestMapping("/api/v1/ai/drafts")
@Tag(name = "AI Drafts",
    description = "The pending, human-reviewable AI draft on a conversation. Read-only by design: "
        + "nothing here sends anything.")
public class AiDraftController {

    private final AiDraftReadService drafts;

    public AiDraftController(AiDraftReadService drafts) {
        this.drafts = drafts;
    }

    /**
     * {@code GET /api/v1/ai/drafts/conversations/{conversationId}}
     *
     * <p>The pending draft for a conversation. Answered with the same 404 for a conversation that does
     * not exist, one in another tenant, and one whose draft a human took over — so this route is not an
     * oracle for any of those.
     */
    @GetMapping("/conversations/{conversationId}")
    @RequirePermission(Permission.AI_RUN_READ)
    @Operation(summary = "The pending AI draft on a conversation",
        description = "Returns the governed draft a human may review and then choose to send. The "
            + "body is the redacted answer as a DRAFT: this route neither sends nor stages it, and "
            + "no message row exists. A conversation with no pending draft — because none was "
            + "produced, or because a human took the conversation over — is answered with the same "
            + "404 as a conversation that is not yours, so this endpoint cannot be used to learn "
            + "whether another tenant's conversation exists.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "A pending draft, for a human to review"),
        @ApiResponse(responseCode = "400", description = "conversationId is not a UUID "
            + "(VALIDATION_FAILED)"),
        @ApiResponse(responseCode = "401", description = "No session (AUTHENTICATION_REQUIRED)"),
        @ApiResponse(responseCode = "403", description = "The caller does not hold ai-run:read "
            + "(ACCESS_DENIED)"),
        @ApiResponse(responseCode = "404", description = "No such conversation in your scope — the "
            + "same answer for a conversation in another tenant, and for a conversation whose draft "
            + "was withheld because a human took over (NOT_FOUND)")
    })
    public AiDraftView pendingDraft(
            @Parameter(description = "A conversation whose pending AI draft is wanted. The tenant and "
                + "project are resolved from the session, never from this request.")
            @PathVariable UUID conversationId) {
        AiDraftReadService.PendingDraft draft = drafts
            .pendingDraft(TenantContextHolder.get(), conversationId)
            // The same uniform 404 the clinic shape uses, raised in the same place.
            .orElseThrow(() -> new DraftNotFound());
        return new AiDraftView(draft.runId(), conversationId, draft.body(), draft.createdAt(),
            "DRAFT", draft.conversationMode(), draft.conversationModeVersion());
    }

    /**
     * The response.
     *
     * <p>A typed record rather than a map, so the published contract names its fields and their
     * nullability rather than describing a free-form string map.
     *
     * @param runId        the {@code ai_runs} row this draft is attributable to — the join key for the
     *                     agent, prompt, model, retrieval and cost provenance
     * @param conversationId the conversation the draft would answer in
     * @param body         the redacted answer, offered as a draft
     * @param createdAt    when the draft was stored
     * @param state        always {@code DRAFT}. Published as a literal rather than derived, so a
     *                     client cannot be handed a value that reads as "sent"
     * @param conversationMode      the conversation's mode at read time, so a human can see whether
     *                              the AI still owns the thread
     * @param conversationModeVersion the mode version at read time
     */
    public record AiDraftView(UUID runId,
                              UUID conversationId,
                              String body,
                              LocalDateTime createdAt,
                              String state,
                              String conversationMode,
                              int conversationModeVersion) {
    }

    /**
     * The uniform 404.
     *
     * <p>Byte-identical to what {@code CrmExceptionAdvice} renders for a cross-tenant denial, which is
     * the same body a {@code PolicyDeniedException} gets. That identity is the whole non-disclosure
     * property, and it is why the message is {@code "Resource not found"} and not
     * {@code "Conversation not found"}.
     */
    private static final class DraftNotFound
            extends com.firstelement.crm.error.ResourceNotFoundException {

        private static final long serialVersionUID = 1L;

        DraftNotFound() {
            super("NOT_FOUND", "Resource not found", java.util.Map.of());
        }
    }
}