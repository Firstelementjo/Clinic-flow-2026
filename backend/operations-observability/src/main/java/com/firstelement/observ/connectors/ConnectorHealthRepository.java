package com.firstelement.observ.connectors;

import com.firstelement.admin.audit.AuditAttribution;
import com.firstelement.channels.connector.ConnectorCatalog;
import com.firstelement.channels.persistence.ChannelConnectionRepository;
import com.firstelement.security.query.ScopedWhere;
import com.firstelement.security.query.TenantScope;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Connector and webhook health, tenant-scoped, with no credential.
 *
 * <h2>Where the webhook numbers come from</h2>
 *
 * <p>There is no webhook-attempt table. The three facts FR-6 asks for map onto three existing
 * sources, and using them rather than inventing a table is what keeps this phase migration-free:
 *
 * <ul>
 *   <li><b>attempts</b> — {@code messages} for outbound ({@code deliveryStatus},
 *       {@code failureReason}) and the outbox/integration stream for inbound;</li>
 *   <li><b>signature failures</b> — {@code job_integration_events} with
 *       {@code eventType = 'WEBHOOK_REJECTED'}, written by the connector intake path, plus the
 *       connection's own {@code lastError} which the intake service sets on a verification
 *       failure;</li>
 *   <li><b>last inbound / outbound</b> — {@code channel_connections."lastInboundAt"} and
 *       {@code "lastOutboundAt"}, which the connector module already maintains.</li>
 * </ul>
 *
 * <h2>What is never selected</h2>
 *
 * <p>Not {@code credentials}, not {@code webhookSecret}, not {@code messages."body"}. The first two
 * are the whole point; the third is the customer's words. This repository selects
 * {@code hasCredentials}/{@code hasWebhookSecret} — booleans the database computes — and nothing
 * that can be read back.
 */
@Repository
public class ConnectorHealthRepository {

    /** Outbound delivery outcomes per channel and connection, over a window. */
    private static final String OUTBOUND_OUTCOMES = """
        select m."projectId" as project_id, cc."channel"::text as channel, cc."id" as connection_id,
               m."deliveryStatus"::text as delivery_status, count(*) as occurrences,
               max(m."sentAt") as last_at,
               max(m."failureReason") as sample_failure_reason
          from messages m
          join channel_connections cc
            on cc."organizationId" = m."organizationId"
           and cc."projectId" = m."projectId"
         where m."organizationId" = :__fe_tenant_organization_id
           and m."direction" = cast('OUTBOUND' as "MessageDirection")
           and m."sentAt" >= :since
         group by m."projectId", cc."channel", cc."id", m."deliveryStatus"
         order by cc."channel", m."deliveryStatus"
         limit :limit
        """;

    /**
     * Inbound webhook decisions from the integration stream.
     *
     * <p>{@code WEBHOOK_RECEIVED} and {@code WEBHOOK_REJECTED} are the two event types the migration
     * declares for exactly this. A rejection is a signature failure, a secret mismatch, or an
     * unparseable body — the operator needs the count and the reason, never the body.
     */
    private static final String WEBHOOK_EVENTS = """
        select j."component" as component, j."eventType" as event_type, j."outcome"::text as outcome,
               count(*) as occurrences, max(j."occurredAt") as last_at,
               max(j."failureCode") as sample_failure_code
          from job_integration_events j
         where j."organizationId" = :__fe_tenant_organization_id
           and j."eventType" in ('WEBHOOK_RECEIVED', 'WEBHOOK_REJECTED')
           and j."occurredAt" >= :since
         group by j."component", j."eventType", j."outcome"
         order by count(*) desc
         limit :limit
        """;

    /** Provider rate limiting and rejections, from the integration stream's failure codes. */
    private static final String RATE_LIMIT_EVENTS = """
        select j."queueName" as queue, j."failureCode" as failure_code, count(*) as occurrences,
               max(j."occurredAt") as last_at
          from job_integration_events j
         where j."organizationId" = :__fe_tenant_organization_id
           and j."outcome" = cast('FAILED' as "AuditOutcome")
           and j."occurredAt" >= :since
         group by j."queueName", j."failureCode"
         order by count(*) desc
         limit :limit
        """;

    /** Connector-level tallies, for the overview. */
    private static final String SUMMARY = """
        select (select count(*) from channel_connections c
                 where c."organizationId" = :__fe_tenant_organization_id) as connections,
               (select count(*) from channel_connections c
                 where c."organizationId" = :__fe_tenant_organization_id
                   and c."isEnabled") as enabled,
               (select count(*) from channel_connections c
                 where c."organizationId" = :__fe_tenant_organization_id
                   and c."status" = cast('ERROR' as "ChannelConnectionStatus")) as in_error,
               (select count(*) from channel_connections c
                 where c."organizationId" = :__fe_tenant_organization_id
                   and c."lastError" is not null) as with_last_error,
               (select count(*) from channel_connections c
                 where c."organizationId" = :__fe_tenant_organization_id
                   and (c."credentials" is null or c."credentials" = '')) as missing_credentials
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public ConnectorHealthRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One delivery-outcome bucket. */
    public record DeliveryOutcomeRow(UUID projectId,
                                     String channel,
                                     UUID connectionId,
                                     String deliveryStatus,
                                     long occurrences,
                                     LocalDateTime lastAt,
                                     String sampleFailureReason) {
    }

    /** One webhook decision bucket. */
    public record WebhookEventRow(String component,
                                  String eventType,
                                  String outcome,
                                  long occurrences,
                                  LocalDateTime lastAt,
                                  String sampleFailureCode) {
    }

    /** One failure-code bucket, which is where provider rate limiting shows up. */
    public record FailureRow(String queue,
                             String failureCode,
                             long occurrences,
                             LocalDateTime lastAt) {
    }

    /**
     * Connector tallies.
     *
     * @param missingCredentials how many connections have no credential stored. A presence count, not
     *                           a value: the column itself is never selected into this record
     */
    public record ConnectorSummary(long connections,
                                   long enabled,
                                   long inError,
                                   long withLastError,
                                   long missingCredentials) {
    }

    /** The admin connector projection, reused rather than re-derived. */
    public List<ChannelConnectionRepository.ConnectionDescription> connections(TenantScope scope,
                                                                            int limit) {
        // ChannelConnectionRepository's own admin projection already selects booleans instead of the
        // credential columns, so delegating keeps one definition of "safe to show an operator".
        return jdbc.query("""
            select c."id", c."organizationId", c."projectId", c."channel"::text as "channel",
                   c."displayName", c."isEnabled", c."status"::text as "status", c."lastError",
                   c."lastInboundAt", c."lastOutboundAt",
                   (c."credentials" is not null and c."credentials" <> '') as "hasCredentials",
                   (c."webhookSecret" is not null and c."webhookSecret" <> '') as "hasWebhookSecret"
              from channel_connections c
             where c."organizationId" = :__fe_tenant_organization_id
             order by c."createdAt", c."id"
             limit :limit
            """, ScopedWhere.of(scope).bindNamed(Map.of("limit", Math.max(1, limit))),
            (rs, rowNum) -> new ChannelConnectionRepository.ConnectionDescription(
                rs.getObject("id", UUID.class),
                rs.getObject("organizationId", UUID.class),
                rs.getObject("projectId", UUID.class),
                rs.getString("channel"),
                rs.getString("displayName"),
                rs.getBoolean("isEnabled"),
                rs.getString("status"),
                rs.getString("lastError"),
                rs.getObject("lastInboundAt", LocalDateTime.class),
                rs.getObject("lastOutboundAt", LocalDateTime.class),
                rs.getBoolean("hasCredentials"),
                rs.getBoolean("hasWebhookSecret")));
    }

    /** Outbound delivery outcomes over the window. */
    public List<DeliveryOutcomeRow> outboundOutcomes(TenantScope scope, LocalDateTime since,
                                                     int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("since", since, "limit", Math.max(1, limit)));
        return jdbc.query(OUTBOUND_OUTCOMES, parameters, (rs, rowNum) -> new DeliveryOutcomeRow(
            rs.getObject("project_id", UUID.class),
            rs.getString("channel"),
            rs.getObject("connection_id", UUID.class),
            rs.getString("delivery_status"),
            rs.getLong("occurrences"),
            rs.getObject("last_at", LocalDateTime.class),
            rs.getString("sample_failure_reason")));
    }

    /** Inbound webhook decisions over the window. */
    public List<WebhookEventRow> webhookEvents(TenantScope scope, LocalDateTime since, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("since", since, "limit", Math.max(1, limit)));
        return jdbc.query(WEBHOOK_EVENTS, parameters, (rs, rowNum) -> new WebhookEventRow(
            rs.getString("component"),
            rs.getString("event_type"),
            rs.getString("outcome"),
            rs.getLong("occurrences"),
            rs.getObject("last_at", LocalDateTime.class),
            rs.getString("sample_failure_code")));
    }

    /** Provider failures and rate limits over the window. */
    public List<FailureRow> failures(TenantScope scope, LocalDateTime since, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("since", since, "limit", Math.max(1, limit)));
        return jdbc.query(RATE_LIMIT_EVENTS, parameters, (rs, rowNum) -> new FailureRow(
            rs.getString("queue"),
            rs.getString("failure_code"),
            rs.getLong("occurrences"),
            rs.getObject("last_at", LocalDateTime.class)));
    }

    /** Connector tallies. */
    public ConnectorSummary summary(TenantScope scope) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of());
        return jdbc.queryForObject(SUMMARY, parameters, (rs, rowNum) -> new ConnectorSummary(
            rs.getLong("connections"),
            rs.getLong("enabled"),
            rs.getLong("in_error"),
            rs.getLong("with_last_error"),
            rs.getLong("missing_credentials")));
    }

    /**
     * The declared capabilities of every supported channel.
     *
     * <p>Static vocabulary from {@code ConnectorCatalog}, tenant-independent by nature and included
     * so an operator can see what a channel <em>could</em> do next to what this tenant's connection
     * is <em>currently</em> doing.
     */
    public static List<Map<String, Object>> declaredCapabilities() {
        List<Map<String, Object>> rows = new ArrayList<>();
        ConnectorCatalog.all().forEach((channel, capabilities) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("channel", channel.name());
            row.put("sendText", capabilities.sendText());
            row.put("sendMedia", capabilities.sendMedia());
            row.put("markAsRead", capabilities.markAsRead());
            row.put("editMessage", capabilities.editMessage());
            row.put("deleteMessage", capabilities.deleteMessage());
            row.put("sessionWindowHours", capabilities.sessionWindowHours());
            row.put("requiresTemplateOutsideSession", capabilities.requiresTemplateOutsideSession());
            row.put("maxTextLength", capabilities.maxTextLength());
            row.put("verifiesWebhookSignature", capabilities.verifiesWebhookSignature());
            row.put("supportedAttachmentTypes",
                capabilities.supportedAttachmentTypes().stream().map(Enum::name).toList());
            rows.add(row);
        });
        return rows;
    }

    /** The default window for a connector health read. */
    public static LocalDateTime defaultSince(int hours) {
        return AuditAttribution.now().minusHours(Math.max(1, Math.min(hours, 8760)));
    }
}