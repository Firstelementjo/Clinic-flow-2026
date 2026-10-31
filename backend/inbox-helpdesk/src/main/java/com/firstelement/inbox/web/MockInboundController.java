package com.firstelement.inbox.web;

import com.firstelement.crm.domain.ChannelType;
import com.firstelement.crm.error.ResourceNotFoundException;
import com.firstelement.crm.error.ValidationFailedException;
import com.firstelement.crm.query.CrmQueryScope;
import com.firstelement.inbox.api.InboundResult;
import com.firstelement.inbox.api.IncomingMessage;
import com.firstelement.inbox.config.InboxConfiguration;
import com.firstelement.inbox.persistence.MockChannelProbe;
import com.firstelement.inbox.service.InboundMessageService;
import com.firstelement.security.TenantContextHolder;
import com.firstelement.security.query.TenantQueryGuard;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/dev/mock/incoming-message} — the development inbound simulator.
 *
 * <h2>Two gates, because one is not enough</h2>
 *
 * <ol>
 *   <li>It answers 404 unless {@code firstelement.inbox.mock-connector-enabled} is set, and the
 *       configuration refuses to start with that flag on in production.</li>
 *   <li>It still requires a live session with access to the target project <em>and</em> an enabled
 *       MOCK channel connection in it.</li>
 * </ol>
 *
 * <p>Both are the legacy design, and the second one matters: a caller who reaches this endpoint
 * can only write into a project their own membership already covers, so the simulator cannot be
 * used to reach into another tenant.
 *
 * <h2>It drives the real pipeline</h2>
 *
 * <p>Resolve identity → find or create the thread → store idempotently → publish the timeline
 * event. It is not a shortcut that inserts a row, so what is exercised in development is the code
 * path a live webhook takes.
 */
@RestController
@RequestMapping("/api/v1/dev/mock")
public class MockInboundController {

    private final InboundMessageService inbound;
    private final MockChannelProbe channels;
    private final TenantQueryGuard guard;
    private final InboxConfiguration.InboxProductionGuard gate;

    public MockInboundController(InboundMessageService inbound,
                                 MockChannelProbe channels,
                                 TenantQueryGuard guard,
                                 InboxConfiguration.InboxProductionGuard gate) {
        this.inbound = inbound;
        this.channels = channels;
        this.guard = guard;
        this.gate = gate;
    }

    @PostMapping("/incoming-message")
    public ResponseEntity<InboundResult> incoming(@RequestBody MockIncomingRequest body) {
        if (!gate.mockConnectorEnabled()) {
            // The same 404 a nonexistent route returns: the endpoint does not exist here.
            throw new ResourceNotFoundException("Not found");
        }
        if (body == null) {
            throw new ValidationFailedException("A body is required");
        }

        var context = TenantContextHolder.get();
        UUID projectId = RequestParams.requiredUuid(
            body.projectId() == null ? null : body.projectId().toString(), "projectId");

        // projectScope inside this call is what proves the caller may write into the target
        // project — the body's projectId is a request, not an authorization.
        CrmQueryScope scope = CrmQueryScope.forProject(guard, context, projectId);
        if (!channels.hasEnabledMockConnection(scope, projectId)) {
            throw new ResourceNotFoundException("Not found");
        }

        InboundResult result = inbound.receive(context, projectId, null, new IncomingMessage(
            body.externalUserId(),
            body.text(),
            body.displayName(),
            body.phone(),
            body.email(),
            body.externalMessageId(),
            ChannelType.MOCK,
            Map.of(),
            Map.of(),
            LocalDateTime.now()));

        return ResponseEntity.ok(result);
    }

    /** The simulator's body; the legacy {@code mockIncomingMessageSchema}, unchanged. */
    public record MockIncomingRequest(UUID projectId,
                                     String externalUserId,
                                     String text,
                                     String displayName,
                                     String phone,
                                     String email,
                                     String externalMessageId) {
    }
}