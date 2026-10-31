package com.firstelement.channels.web;

import com.firstelement.channels.api.ConnectorCapabilities;
import com.firstelement.channels.connector.ConnectorCatalog;
import com.firstelement.channels.persistence.ChannelConnectionRepository;
import com.firstelement.crm.domain.ChannelType;
import com.firstelement.security.TenantContext;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.query.TenantQueryGuard;
import com.firstelement.security.realtime.RealtimeTopicPolicy;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Connector capability and health, with no credential and no cross-tenant exposure.
 *
 * <h2>Two different questions, two different scopes</h2>
 *
 * <ul>
 *   <li>{@code GET /api/v1/channels/capabilities} is a <b>static declaration</b> per channel: what
 *       the platform can do. It is tenant-independent by nature, and it is the surface the composer
 *       and the outbound path ask before acting. It is still behind an authenticated principal,
 *       because it is part of the product contract rather than public information.</li>
 *   <li>{@code GET /api/v1/channels/connections} is the tenant's own rows: status, last inbound and
 *       outbound, last error, and whether a credential exists — never the credential. The statement
 *       carries {@code organizationId} from the resolved context, so one tenant's operator cannot see
 *       another's connections by naming an id.</li>
 * </ul>
 *
 * <p>The realtime topic each connection would publish to is derived with
 * {@link RealtimeTopicPolicy}, so the admin surface and the SSE stream agree on scoping by
 * construction rather than by two implementations of the same rule.
 */
@RestController
@RequestMapping("/api/v1/channels")
public class ChannelCapabilityController {

    private final ChannelConnectionRepository connections;
    private final TenantQueryGuard guard;

    public ChannelCapabilityController(ChannelConnectionRepository connections,
                                       TenantQueryGuard guard) {
        this.connections = connections;
        this.guard = guard;
    }

    /** The declared capabilities of every supported channel. */
    @GetMapping("/capabilities")
    @RequirePermission(com.firstelement.security.permission.Permission.CHANNEL_READ)
    public Map<String, Object> capabilities() {
        Map<String, Object> payload = new LinkedHashMap<>();
        ConnectorCatalog.all().forEach((channel, capabilities) ->
            payload.put(channel.name(), describe(capabilities)));
        return payload;
    }

    /** The tenant's own connections, with health and no secret. */
    @GetMapping("/connections")
    @RequirePermission(com.firstelement.security.permission.Permission.CHANNEL_READ)
    public List<Map<String, Object>> connectionHealth(TenantContext context) {
        guard.tenantScope(context);
        return connections.describe(context.organizationId()).stream()
            .map(row -> describe(row))
            .toList();
    }

    private static Map<String, Object> describe(ConnectorCapabilities capabilities) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("channel", capabilities.channel().name());
        payload.put("sendText", capabilities.sendText());
        payload.put("sendMedia", capabilities.sendMedia());
        payload.put("markAsRead", capabilities.markAsRead());
        payload.put("editMessage", capabilities.editMessage());
        payload.put("deleteMessage", capabilities.deleteMessage());
        payload.put("sessionWindowHours", capabilities.sessionWindowHours());
        payload.put("requiresTemplateOutsideSession", capabilities.requiresTemplateOutsideSession());
        payload.put("maxTextLength", capabilities.maxTextLength());
        payload.put("supportedAttachmentTypes",
            capabilities.supportedAttachmentTypes().stream().map(Enum::name).toList());
        payload.put("verifiesWebhookSignature", capabilities.verifiesWebhookSignature());
        return payload;
    }

    private static Map<String, Object> describe(
        ChannelConnectionRepository.ConnectionDescription row) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", row.id());
        payload.put("projectId", row.projectId());
        payload.put("channel", row.channel());
        payload.put("displayName", row.displayName());
        payload.put("enabled", row.enabled());
        payload.put("status", row.status());
        payload.put("lastError", row.lastError());
        payload.put("lastInboundAt", iso(row.lastInboundAt()));
        payload.put("lastOutboundAt", iso(row.lastOutboundAt()));
        // Presence only. FR-4.7: secrets are write-only through APIs.
        payload.put("hasCredentials", row.hasCredentials());
        payload.put("hasWebhookSecret", row.hasWebhookSecret());
        payload.put("capabilities", describe(ConnectorCatalog.forChannel(
            ChannelType.fromValue(row.channel()))));
        payload.put("realtimeTopic",
            RealtimeTopicPolicy.projectTopic(row.organizationId(), row.projectId()));
        return payload;
    }

    private static String iso(LocalDateTime timestamp) {
        return timestamp == null ? null : timestamp + "Z";
    }

    /** Kept for the record shape used by the admin read models. */
    static Map<String, Object> empty() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("connections", List.of());
        return payload;
    }

    /** A stable, non-secret label for logs. */
    static String label(UUID connectionId) {
        return "channel-connection:" + connectionId;
    }
}