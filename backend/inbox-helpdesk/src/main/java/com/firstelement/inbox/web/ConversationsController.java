package com.firstelement.inbox.web;

import com.firstelement.crm.api.ConversationDetail;
import com.firstelement.crm.api.ConversationSummary;
import com.firstelement.crm.domain.ChannelType;
import com.firstelement.crm.domain.ConversationMode;
import com.firstelement.crm.domain.ConversationPriority;
import com.firstelement.crm.domain.ConversationStatus;
import com.firstelement.crm.domain.InboxFilter;
import com.firstelement.crm.error.ValidationFailedException;
import com.firstelement.crm.persistence.ConversationRepository;
import com.firstelement.crm.query.CursorPage;
import com.firstelement.inbox.api.AssignConversationRequest;
import com.firstelement.inbox.api.ChangeModeRequest;
import com.firstelement.inbox.api.CustomerTranscript;
import com.firstelement.inbox.api.InboxQuery;
import com.firstelement.inbox.api.ReplyResult;
import com.firstelement.inbox.api.SendMessageRequest;
import com.firstelement.inbox.api.UpdateConversationRequest;
import com.firstelement.inbox.service.ConversationService;
import com.firstelement.inbox.service.InboxQueryService;
import com.firstelement.security.TenantContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/conversations} — the strangler-migration compatibility surface.
 *
 * <h2>Every legacy route is present, with the same body and status</h2>
 *
 * <pre>
 *   GET   /api/v1/conversations                       CONVERSATION_READ
 *   GET   /api/v1/conversations/{id}                  CONVERSATION_READ
 *   POST  /api/v1/conversations/{id}/messages          CONVERSATION_REPLY
 *   PATCH /api/v1/conversations/{id}/assignment       CONVERSATION_ASSIGN
 *   PATCH /api/v1/conversations/{id}                  CONVERSATION_RESOLVE
 *   PATCH /api/v1/conversations/{id}/close            CONVERSATION_RESOLVE
 * </pre>
 *
 * <h2>Routes added by this phase</h2>
 *
 * <pre>
 *   PATCH  /api/v1/conversations/{id}/mode            CONVERSATION_TAKE_OVER | RETURN_TO_AI
 *   GET    /api/v1/conversations/{id}/transcript       CONVERSATION_READ
 * </pre>
 *
 * <p>Neither existed in the legacy API. {@code /mode} exposes the guarded AI⇄HUMAN transition that
 * the worker used to own exclusively; {@code /transcript} is the customer-facing projection that
 * FR-3.4's history separation needs to be observable.
 *
 * <h2>Concurrency and idempotency on the wire</h2>
 *
 * <ul>
 *   <li>{@code If-Match} carries the version token on every mutation; a collision is 409
 *       {@code VERSION_CONFLICT}.</li>
 *   <li>{@code ETag} is returned on every read so the client has a token to send.</li>
 *   <li>{@code Idempotency-Key} on a reply makes a retry return the stored message instead of
 *       sending a second one.</li>
 *   <li>{@code X-Conversation-Collision: true} tells the client the thread moved between its read
 *       and its reply. The reply is still written; the header exists so the UI can warn.</li>
 * </ul>
 *
 * <p>None of these change the JSON body, so a client written against the legacy API keeps
 * working while it learns the headers.
 */
@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationsController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final ConversationService conversations;
    private final InboxQueryService inbox;

    public ConversationsController(ConversationService conversations, InboxQueryService inbox) {
        this.conversations = conversations;
        this.inbox = inbox;
    }

    // ------------------------------------------------------------------
    // reads
    // ------------------------------------------------------------------

    /**
     * The conversation list.
     *
     * <p>The {@code filter} parameter stays a {@code String} here on purpose, and the OpenAPI
     * document still publishes it as an {@code enum}. The two are not the same question:
     *
     * <ul>
     *   <li><b>Why not type it as {@link InboxFilter}?</b> Because springdoc renders a typed enum
     *       parameter as its <em>Java constant names</em> — {@code ASSIGNED_TO_ME} — and Spring
     *       binds an enum parameter by constant name. Typing it would therefore have done two bad
     *       things at once: it would have advertised a vocabulary that is not the wire vocabulary
     *       this API has always used, and it would have made {@code ?filter=assigned-to-me} a
     *       <b>400</b>, breaking the legacy client that has always sent it. Measured, not assumed:
     *       the first attempt of this change published
     *       {@code [UNASSIGNED, ALL_OPEN, …]} and would have refused every kebab-case value. The
     *       wire values are kebab-case because they are the legacy client's saved filters.</li>
     *   <li><b>So the enum is declared in the document</b> by
     *       {@link InboxFilterEnumOpenApiCustomizer}, from {@link InboxFilter#acceptedValues()}. The
     *       documented list is still derived from the one type the server accepts, so it cannot
     *       drift, and it is still an {@code enum} in the contract rather than a bare string.</li>
     * </ul>
     *
     * <p>An absent {@code filter} means {@link #defaultFilter()} — the work queue, {@code unassigned}.
     * See the note there for why, and {@code docs/migration/adr/0015-inbox-default-filter.md} for the
     * ruling and its compatibility consequence.
     */
    @GetMapping
    public CursorPage<ConversationSummary> list(@RequestParam(required = false) String projectId,
                                               @RequestParam(required = false) String filter,
                                               @RequestParam(required = false) String channel,
                                               @RequestParam(required = false) String search,
                                               @RequestParam(required = false) String cursor,
                                               @RequestParam(name = "limit", required = false) @BoundedLimit String limit) {
        return inbox.inbox(TenantContextHolder.get(), new InboxQuery(
            RequestParams.optionalUuid(projectId, "projectId"),
            parseFilter(filter),
            parseChannel(channel),
            search,
            RequestParams.optionalUuid(cursor, "cursor"),
            RequestParams.limit(limit, InboxQuery.DEFAULT_LIMIT, InboxQuery.MAX_LIMIT)));
    }

    @GetMapping("/{conversationId}")
    public ResponseEntity<ConversationDetail> getOne(@PathVariable String conversationId) {
        ConversationService.ThreadView view = conversations.getView(TenantContextHolder.get(),
            RequestParams.requiredUuid(conversationId, "conversationId"));
        return ResponseEntity.ok().eTag(RequestParams.etag(view.version())).body(view.detail());
    }

    /**
     * The customer-facing transcript.
     *
     * <p>A separate resource rather than a query flag, so the internal and customer-facing views
     * cannot be served from one object by accident.
     */
    @GetMapping("/{conversationId}/transcript")
    public CustomerTranscript transcript(@PathVariable String conversationId) {
        return conversations.transcript(TenantContextHolder.get(),
            RequestParams.requiredUuid(conversationId, "conversationId"));
    }

    // ------------------------------------------------------------------
    // writes
    // ------------------------------------------------------------------

    @PostMapping("/{conversationId}/messages")
    public ResponseEntity<ConversationDetail> reply(
        @PathVariable String conversationId,
        @RequestBody SendMessageRequest body,
        @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
        @RequestHeader(name = "X-Expected-Message-Count", required = false)
        String expectedMessageCount,
        HttpServletRequest request) {

        ReplyResult result = conversations.reply(TenantContextHolder.get(),
            RequestParams.requiredUuid(conversationId, "conversationId"), body,
            idempotencyKey, RequestParams.optionalInt(expectedMessageCount), clientIp(request));

        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (result.replayed()) {
            response.header("X-Idempotent-Replay", "true");
        }
        if (result.collisionDetected()) {
            response.header("X-Conversation-Collision", "true");
        }
        return response.eTag(RequestParams.etag(result.versionBefore())).body(result.detail());
    }

    @PatchMapping("/{conversationId}/assignment")
    public ResponseEntity<ConversationDetail> assign(
        @PathVariable String conversationId,
        @RequestBody AssignConversationRequest body,
        @RequestHeader(name = "If-Match", required = false) String ifMatch,
        HttpServletRequest request) {

        ConversationDetail detail = conversations.assign(TenantContextHolder.get(),
            RequestParams.requiredUuid(conversationId, "conversationId"), body,
            RequestParams.ifMatch(ifMatch), clientIp(request));
        return ResponseEntity.ok().body(detail);
    }

    @PatchMapping("/{conversationId}")
    public ResponseEntity<ConversationDetail> update(
        @PathVariable String conversationId,
        @RequestBody UpdateConversationRequest body,
        @RequestHeader(name = "If-Match", required = false) String ifMatch,
        HttpServletRequest request) {

        ConversationDetail detail = conversations.update(TenantContextHolder.get(),
            RequestParams.requiredUuid(conversationId, "conversationId"), body,
            RequestParams.ifMatch(ifMatch), clientIp(request));
        return ResponseEntity.ok().body(detail);
    }

    @PatchMapping("/{conversationId}/close")
    public ResponseEntity<ConversationDetail> close(
        @PathVariable String conversationId,
        @RequestHeader(name = "If-Match", required = false) String ifMatch,
        HttpServletRequest request) {

        ConversationDetail detail = conversations.closeAndUnassign(TenantContextHolder.get(),
            RequestParams.requiredUuid(conversationId, "conversationId"),
            RequestParams.ifMatch(ifMatch), clientIp(request));
        return ResponseEntity.ok().body(detail);
    }

    /**
     * The guarded AI⇄HUMAN transition.
     *
     * <p>The permission depends on the direction: taking over needs
     * {@code CONVERSATION_TAKE_OVER}, returning to AI needs {@code CONVERSATION_RETURN_TO_AI}.
     * Declaring one permission on the method would grant both, so the branch is inside the body
     * and the two guarded service methods are the enforcement point.
     */
    @PatchMapping("/{conversationId}/mode")
    public ResponseEntity<ConversationDetail> changeMode(
        @PathVariable String conversationId,
        @RequestBody ChangeModeRequest body,
        HttpServletRequest request) {

        ConversationDetail detail;
        UUID id = RequestParams.requiredUuid(conversationId, "conversationId");
        if (body != null && body.mode() == ConversationMode.AI) {
            detail = conversations.returnToAi(TenantContextHolder.get(), id, body, clientIp(request));
        } else {
            detail = conversations.changeMode(TenantContextHolder.get(), id, body, clientIp(request));
        }
        return ResponseEntity.ok().body(detail);
    }

    // ------------------------------------------------------------------
    // parsing
    // ------------------------------------------------------------------

    /**
     * The saved-view default when no filter is sent.
     *
     * <p><b>Changed from {@code assigned-to-me} to {@code unassigned} by orchestrator ruling</b>, on the
     * harm asymmetry rather than on taste.
     *
     * <p>With the old default an employee opening the inbox saw only threads already assigned to
     * them. An unassigned conversation — one nobody has picked up — was invisible by default, so an
     * inbox that looked empty could mean "no work exists" when it actually meant "no work is
     * <em>mine</em>". Those are very different situations and the UI renders them identically.
     *
     * <p>The asymmetry decides it. Showing an employee some conversations that are not theirs costs a
     * moment of scanning. Hiding an unassigned conversation from every employee at once costs a
     * customer waiting for a reply that nobody can see is outstanding. The second is not a UI
     * annoyance; the first is. Where a default can hide work, it must not.
     *
     * <p>This is a deliberate behaviour change, not a bug fix, and it affects the legacy client too
     * during the Phase-12 dual run — which is the correct outcome, because the legacy client had the
     * same blind spot and both clients should see the same queue while they coexist.
     *
     * <p>{@code assigned-to-me} remains a first-class filter and remains reachable by name; nothing is
     * removed, only the resting state changed. See
     * {@code docs/migration/adr/0015-inbox-default-filter.md}.
     */
    public static InboxFilter defaultFilter() {
        return InboxFilter.DEFAULT;
    }

    /**
     * Resolves a raw wire value, or refuses it.
     *
     * <p>The parameter is bound as {@link InboxFilter} by Spring, so this handles the cases Spring
     * cannot: an absent value, and the callers that build an {@link InboxQuery} directly.
     *
     * <p>An unrecognised value arriving through HTTP never reaches this method — Spring fails the
     * binding first and {@code CrmExceptionAdvice} renders that as a 400, which is the behaviour
     * requirement 5 asks for. It is kept so a direct caller cannot smuggle an unvalidated string
     * into a query, and so the refusal message carries the accepted values.
     *
     * <p>Public because it is the single parse point of a published vocabulary: the contract suite
     * asserts that what the OpenAPI document advertises and what this accepts are the same list,
     * and that assertion cannot live in another package if the method is package-private.
     */
    public static InboxFilter parseFilter(String raw) {
        if (raw == null || raw.isBlank()) {
            return defaultFilter();
        }
        try {
            return InboxFilter.fromValue(raw.trim());
        } catch (IllegalArgumentException e) {
            throw ValidationFailedException.code("VALIDATION_FAILED",
                "filter must be one of " + InboxFilter.acceptedValues(),
                java.util.Map.of("field", "filter", "accepted", InboxFilter.acceptedValues()));
        }
    }

    static ChannelType parseChannel(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return ChannelType.fromValue(raw.trim());
        } catch (IllegalArgumentException e) {
            throw ValidationFailedException.code("VALIDATION_FAILED",
                "channel is not a supported channel", java.util.Map.of("field", "channel"));
        }
    }

    static ConversationStatus parseStatus(String raw) {
        try {
            return ConversationStatus.fromValue(raw);
        } catch (IllegalArgumentException e) {
            throw ValidationFailedException.code("VALIDATION_FAILED",
                "status is not a supported status", java.util.Map.of("field", "status"));
        }
    }

    static ConversationPriority parsePriority(String raw) {
        try {
            return ConversationPriority.fromValue(raw);
        } catch (IllegalArgumentException e) {
            throw ValidationFailedException.code("VALIDATION_FAILED",
                "priority is not a supported priority", java.util.Map.of("field", "priority"));
        }
    }

    /**
     * The client address for the audit row. An audit attribute only; it never feeds an
     * authorization decision.
     */
    static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /** Kept so the ordering constant is reachable from tests. */
    static String recencyOrder() {
        return ConversationRepository.RECENCY_ORDER;
    }
}