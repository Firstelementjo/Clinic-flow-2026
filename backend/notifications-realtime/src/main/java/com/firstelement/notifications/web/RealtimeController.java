package com.firstelement.notifications.web;

import com.firstelement.notifications.persistence.NotificationRepository;
import com.firstelement.notifications.realtime.RealtimeStreamService;
import com.firstelement.security.TenantContext;
import com.firstelement.security.TenantContextHolder;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.permission.Permission;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The realtime stream and the notification API the stream is a hint about.
 *
 * <h2>The tenant scope comes from the thread, never from a parameter</h2>
 *
 * <p>{@code TenantContextHolder.get()} is used rather than a {@code TenantContext} method
 * argument. There is no {@code HandlerMethodArgumentResolver} in this application, so a
 * {@code TenantContext} parameter would be treated as a model attribute and fail at request time
 * &mdash; and the codebase convention is unambiguous: every Phase-4 controller reads
 * {@code TenantContextHolder.get()} inside its method. {@code TenantContextFilter} binds the
 * context from the authenticated principal, so by the time a handler runs the scope is already
 * resolved and cannot be influenced by the request.
 *
 * <h2>{@code GET /api/v1/realtime/stream}</h2>
 *
 * <p>Server-Sent Events, per ADR-0003. The response is text/event-stream; the browser reconnects on
 * its own and the first frame on every connection is {@code resync}, so a dropped frame is
 * recovered by refetching rather than by trusting the stream.
 *
 * <p>Topics arrive as query parameters. They are <em>requests</em>: the allowed set is computed from
 * the resolved {@code TenantContext} by {@code RealtimeTopicPolicy}, and anything outside it is
 * refused and recorded on the security stream. A client cannot widen its subscription by naming a
 * topic.
 *
 * <h2>{@code GET /api/v1/realtime/state}</h2>
 *
 * <p>The resync target the {@code resync} frame points at. It exists so "resync from the API" is a
 * concrete endpoint rather than a phrase: unread count, the notification list, and the server time
 * the client can compare against. Tenant-scoped and user-scoped like every other read.
 */
@RestController
@RequestMapping("/api/v1")
public class RealtimeController {

    private final RealtimeStreamService streams;
    private final NotificationRepository notifications;

    public RealtimeController(RealtimeStreamService streams, NotificationRepository notifications) {
        this.streams = streams;
        this.notifications = notifications;
    }

    /** The SSE stream. Authenticated by the shared cookie session; no second auth path. */
    @GetMapping(path = "/realtime/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(name = "topic", required = false) List<String> topics) {
        return streams.open(TenantContextHolder.get(),
            topics == null ? List.of() : topics);
    }

    /**
     * The resync target.
     *
     * <p>This is the answer to "what does the client do after a reconnect": fetch this, then refetch
     * whatever the frames referred to. It is deliberately cheap and deliberately complete enough to
     * restore a tab.
     */
    @GetMapping("/realtime/state")
    @RequirePermission(Permission.CONVERSATION_READ)
    public Map<String, Object> state(@RequestParam(name = "unreadOnly", defaultValue = "false")
                                     boolean unreadOnly) {
        TenantContext context = TenantContextHolder.get();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("organizationId", context.organizationId());
        payload.put("serverTime", java.time.LocalDateTime.now().toString());
        payload.put("unreadCount", notifications.unreadCount(context));
        payload.put("notifications", notifications.list(context, unreadOnly, 50).stream()
            .map(RealtimeController::describe).toList());
        payload.put("projects", context.projectIds());
        payload.put("realtime", streams.health());
        return payload;
    }

    /** Mark one notification read. The id is not a capability: the row must be the caller's. */
    @PostMapping("/notifications/{notificationId}/read")
    @RequirePermission(Permission.CONVERSATION_READ)
    public Map<String, Object> markRead(@PathVariable UUID notificationId) {
        TenantContext context = TenantContextHolder.get();
        boolean updated = notifications.markRead(context, notificationId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("updated", updated);
        // No "found" flag: a notification that belongs to someone else is reported exactly like one
        // that does not exist.
        return payload;
    }

    @PostMapping("/notifications/read-all")
    @RequirePermission(Permission.CONVERSATION_READ)
    public Map<String, Object> markAllRead() {
        return Map.of("updated", notifications.markAllRead(TenantContextHolder.get()));
    }

    @GetMapping("/notifications")
    @RequirePermission(Permission.CONVERSATION_READ)
    public List<Map<String, Object>> listNotifications(
            @RequestParam(name = "unreadOnly", defaultValue = "false") boolean unreadOnly) {
        TenantContext context = TenantContextHolder.get();
        List<Map<String, Object>> payload = new ArrayList<>();
        notifications.list(context, unreadOnly, 50).forEach(row -> payload.add(describe(row)));
        return payload;
    }

    private static Map<String, Object> describe(NotificationRepository.NotificationView row) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", row.id());
        payload.put("projectId", row.projectId());
        payload.put("type", row.type());
        payload.put("title", row.title());
        payload.put("body", row.body());
        payload.put("resourceType", row.resourceType());
        payload.put("resourceId", row.resourceId());
        payload.put("read", row.read());
        payload.put("readAt", row.readAt());
        payload.put("createdAt", row.createdAt());
        return payload;
    }
}