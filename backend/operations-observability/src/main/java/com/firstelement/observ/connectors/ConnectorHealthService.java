package com.firstelement.observ.connectors;

import com.firstelement.admin.audit.AuditAttribution;
import com.firstelement.channels.persistence.ChannelConnectionRepository;
import com.firstelement.security.TenantContext;
import com.firstelement.security.query.TenantQueryGuard;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Connector and webhook health for one tenant.
 *
 * <h2>The question this answers</h2>
 *
 * <p>FR-6: "connector status, webhook attempts, signature failures, provider rate limits, and last
 * inbound/outbound timestamps". Those are five questions, and the read model answers all five from
 * columns that already exist, which is why this phase needs no migration.
 *
 * <h2>Why no credential appears anywhere here</h2>
 *
 * <p>The connection projection is {@code ChannelConnectionRepository.ConnectionDescription}, which
 * selects {@code hasCredentials} and {@code hasWebhookSecret} — booleans the database computes with
 * {@code (c."credentials" is not null and c."credentials" &lt;&gt; '')} — and selects no credential
 * column at all. So there is no value in this process for a log line, an exception message or a
 * response to leak, and {@code FieldRedactor}'s {@code NEVER_EXPOSED} list is a second line of defence
 * rather than the only one.
 */
@Service
@Transactional(readOnly = true)
public class ConnectorHealthService {

    private final ConnectorHealthRepository health;
    private final TenantQueryGuard guard;
    private final com.firstelement.security.permission.AuthorizationService authorization;

    public ConnectorHealthService(ConnectorHealthRepository health,
                                  TenantQueryGuard guard,
                                  com.firstelement.security.permission.AuthorizationService
                                      authorization) {
        this.health = health;
        this.guard = guard;
        this.authorization = authorization;
    }

    /**
     * The permission check, here as well as on the route.
     *
     * <p>{@code CHANNEL_READ} is what {@code OperationsController} declares, and it is the same
     * permission the connector module already gates its own admin projection with — one declaration
     * rather than two. The service check makes it unbypassable for a caller that is not HTTP.
     */
    private com.firstelement.security.query.TenantScope authorize(TenantContext caller) {
        if (caller == null) {
            throw new IllegalStateException(
                "An operations read requires a resolved TenantContext; refusing to run unscoped.");
        }
        authorization.check(caller, com.firstelement.security.permission.Permission.CHANNEL_READ,
            com.firstelement.security.permission.PolicyResource.NONE);
        return guard.tenantScope(caller);
    }

    /** The connector panel: every connection, its health, and the declared capability grammar. */
    public ConnectorView connectors(TenantContext caller, Integer windowHours) {
        var scope = authorize(caller);
        int window = windowHours == null || windowHours < 1 ? 24 : Math.min(windowHours, 8760);
        LocalDateTime since = ConnectorHealthRepository.defaultSince(window);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("organizationId", caller.organizationId());
        payload.put("windowHours", window);
        payload.put("summary", health.summary(scope));
        payload.put("connections", health.connections(scope,
            com.firstelement.observ.OperationsObservabilityModule.Limits.CONNECTORS));
        payload.put("declaredCapabilities", ConnectorHealthRepository.declaredCapabilities());
        payload.put("outboundOutcomes", health.outboundOutcomes(scope, since,
            com.firstelement.observ.OperationsObservabilityModule.Limits.CONNECTORS));
        payload.put("note", "hasCredentials and hasWebhookSecret are presence booleans computed by "
            + "the database. No credential column is selected by any statement behind this panel, so "
            + "there is no secret value in this process to leak.");
        return new ConnectorView(payload);
    }

    /** Webhook attempts, signature failures and provider rate limits. */
    public WebhookView webhooks(TenantContext caller, Integer windowHours) {
        var scope = authorize(caller);
        int window = windowHours == null || windowHours < 1 ? 168 : Math.min(windowHours, 8760);
        LocalDateTime since = ConnectorHealthRepository.defaultSince(window);

        List<ConnectorHealthRepository.WebhookEventRow> events = health.webhookEvents(scope, since,
            com.firstelement.observ.OperationsObservabilityModule.Limits.CONNECTORS);
        long received = events.stream()
            .filter(row -> "WEBHOOK_RECEIVED".equals(row.eventType()))
            .mapToLong(ConnectorHealthRepository.WebhookEventRow::occurrences).sum();
        long rejected = events.stream()
            .filter(row -> "WEBHOOK_REJECTED".equals(row.eventType()))
            .mapToLong(ConnectorHealthRepository.WebhookEventRow::occurrences).sum();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("organizationId", caller.organizationId());
        payload.put("windowHours", window);
        payload.put("received", received);
        payload.put("rejected", rejected);
        // Rejections are not a rate: one signature failure is a misconfiguration, a thousand is an
        // attack or a broken secret. Reporting the ratio is what separates the two, and reporting it
        // as 0.0 when nothing has been received would be the "no data is not zero" mistake.
        payload.put("rejectionRatio", received + rejected == 0
            ? null : (double) rejected / (double) (received + rejected));
        payload.put("events", events);
        payload.put("providerFailures", health.failures(scope, since,
            com.firstelement.observ.OperationsObservabilityModule.Limits.CONNECTORS));
        payload.put("note", "A WEBHOOK_REJECTED row is a verification failure, a secret mismatch or "
            + "an unparseable body. The failure code is recorded; the body is never stored on the "
            + "stream and never projected here.");
        return new WebhookView(payload);
    }

    /** The connector panel document. */
    public record ConnectorView(Map<String, Object> document) {
    }

    /** The webhook-health document. */
    public record WebhookView(Map<String, Object> document) {
    }
}