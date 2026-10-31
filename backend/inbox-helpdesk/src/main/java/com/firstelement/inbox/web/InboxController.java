package com.firstelement.inbox.web;

import com.firstelement.crm.domain.ChannelType;
import com.firstelement.inbox.api.UnassignedQueuePage;
import com.firstelement.inbox.service.InboxQueryService;
import com.firstelement.security.TenantContextHolder;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/inbox} — the queue and the counts.
 *
 * <pre>
 *   GET /api/v1/inbox/unassigned    CONVERSATION_READ
 *   GET /api/v1/inbox/counts        CONVERSATION_READ
 * </pre>
 *
 * <h2>The queue is exposed, the router is not</h2>
 *
 * <p>Phase 6 owns routing. This surface answers "what is waiting, and why", from facts already on
 * the thread: never assigned, released by an assignment change, or resolved and now active again.
 * No eligibility, availability, working-hours or capacity judgement is made here. Adding one would
 * be the router arriving early and unlabelled, and the resulting explanation would be wrong the
 * moment Phase 6's rules changed.
 *
 * <h2>Why the queue has its own route</h2>
 *
 * <p>It is not a filter value on the inbox list. The membership rule is written once
 * ({@code UnassignedQueueRepository#UNASSIGNED_ACTIVE}) and shared with the counts, so the badge
 * and the list cannot drift apart the way two separately-written definitions of "unassigned"
 * would.
 */
@RestController
@RequestMapping("/api/v1/inbox")
public class InboxController {

    private static final int DEFAULT_QUEUE_LIMIT = 30;
    private static final int MAX_QUEUE_LIMIT = 100;

    private final InboxQueryService inbox;

    public InboxController(InboxQueryService inbox) {
        this.inbox = inbox;
    }

    @GetMapping("/unassigned")
    public UnassignedQueuePage unassigned(@RequestParam(required = false) String projectId,
                                          @RequestParam(required = false) String channel,
                                          @RequestParam(required = false) String cursor,
                                          @RequestParam(name = "limit", required = false) @BoundedLimit String limit) {
        return inbox.unassigned(TenantContextHolder.get(),
            RequestParams.optionalUuid(projectId, "projectId"),
            ConversationsController.parseChannel(channel),
            RequestParams.optionalUuid(cursor, "cursor"),
            RequestParams.limit(limit, DEFAULT_QUEUE_LIMIT, MAX_QUEUE_LIMIT));
    }

    /**
     * The saved-view counts the inbox tabs show.
     *
     * <p>Computed with the same filter definition as the list and over the whole scope, not the
     * current page, so a badge never depends on what happens to be loaded.
     */
    @GetMapping("/counts")
    public Map<String, Integer> counts(@RequestParam(required = false) String projectId,
                                       @RequestParam(required = false) String channel,
                                       @RequestParam(required = false) String search) {
        return inbox.savedViewCounts(TenantContextHolder.get(),
            RequestParams.optionalUuid(projectId, "projectId"),
            ConversationsController.parseChannel(channel),
            search);
    }

    /** Kept so the channel parser is shared rather than duplicated per controller. */
    static ChannelType channel(String raw) {
        return ConversationsController.parseChannel(raw);
    }
}