package com.firstelement.inbox.web;

import com.firstelement.crm.api.ConversationDetail;
import com.firstelement.inbox.api.AssignConversationRequest;
import com.firstelement.inbox.service.ConversationService;
import com.firstelement.security.TenantContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/assignments/{conversationId}} — the resource-shaped alias for assignment.
 *
 * <h2>Why an alias rather than only the legacy route</h2>
 *
 * <p>The strangler target's endpoint family list names {@code /assignments} as a first-class
 * family (PROJECT_REQUIREMENTS §6). The legacy API expressed assignment as
 * {@code PATCH /conversations/{id}/assignment}, which reads as a sub-resource of a conversation
 * and conflates "who owns this" with "change the conversation". Angular is migrating route by
 * route, so both shapes are served by the same service call and the same permission; there is no
 * second code path to keep in step.
 *
 * <pre>
 *   PUT /api/v1/assignments/{conversationId}   CONVERSATION_ASSIGN
 * </pre>
 *
 * <p>{@code If-Match} behaves identically to the legacy route: absent means last-writer-wins,
 * present means a stale token is 409 {@code VERSION_CONFLICT}.
 */
@RestController
@RequestMapping("/api/v1/assignments")
public class AssignmentsController {

    private final ConversationService conversations;

    public AssignmentsController(ConversationService conversations) {
        this.conversations = conversations;
    }

    @PutMapping("/{conversationId}")
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

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}