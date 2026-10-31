package com.firstelement.identity;

import com.firstelement.security.event.SecurityEvent;
import com.firstelement.security.event.SecurityEventPublisher;
import com.firstelement.security.event.SecurityEventSanitizer;
import com.firstelement.security.event.SecurityEventType;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the security/audit stream into the legacy {@code audit_logs} table.
 *
 * <h2>Schema mapping</h2>
 *
 * <p>{@code audit_logs} is one of the two documented organisation-wide streams: its
 * {@code projectId} is nullable and Phase 2 confirmed every one of the 43 live rows is a
 * login/logout security event with {@code projectId} null. Filling it in would be
 * fabricated data, so security events leave it null.
 *
 * <p>{@code actorType} is a {@code text} column in the schema, not an enum, and the
 * legacy runtime wrote {@code USER}/{@code SYSTEM}/{@code AI}. The Phase-3 taxonomy is
 * written verbatim ({@code USER}, {@code SERVICE}, {@code CONNECTOR}, {@code WORKER},
 * {@code AI_AGENT}) so the stream stays readable and the existing 43 rows remain
 * interpretable.
 *
 * <h2>Best-effort by design</h2>
 *
 * <p>{@link Propagation#REQUIRES_NEW} so an audit row survives the rollback of the
 * business transaction it describes — an audit trail that disappears with the failed
 * operation is not a trail. A failure is logged at ERROR and swallowed, because a broken
 * audit insert must not roll back or fail a legitimate action the user already completed.
 * That is the same call the legacy {@code AuditService} made, and it is the reason this
 * class is a {@link SecurityEventPublisher} port rather than a required dependency.
 *
 * <h2>Sanitization</h2>
 *
 * <p>Every event passes {@link SecurityEventSanitizer} before it is bound, so a
 * password, token, cookie, API key or secret cannot reach the table even if a future
 * caller adds it to the metadata by mistake.
 */
@Component
public class AuditLogSecurityEventPublisher implements SecurityEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(AuditLogSecurityEventPublisher.class);

    private final NamedParameterJdbcTemplate jdbc;

    public AuditLogSecurityEventPublisher(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publish(SecurityEvent event) {
        if (event == null || event.type() == null) {
            return;
        }
        UUID organizationId = event.organizationId();
        if (organizationId == null) {
            // The legacy table requires organizationId NOT NULL and there is no tenant to
            // attribute a pre-authentication failure to. Those events are logged at WARN
            // instead of inventing a tenant.
            log.warn("security-event-without-tenant action={} reason={}",
                event.type().auditAction(), event.reason());
            return;
        }
        try {
            jdbc.update("""
                insert into audit_logs ("id", "organizationId", "projectId", "actorUserId",
                    "actorType", "action", "resourceType", "resourceId", "metadata",
                    "ipAddress", "createdAt")
                values (:id, :org, null, :actorUserId, :actorType, :action, :resourceType,
                    :resourceId, CAST(:metadata AS jsonb), :ip, :createdAt)
                """,
                new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID())
                    .addValue("org", organizationId)
                    .addValue("actorUserId", event.actorUserId())
                    .addValue("actorType", event.actorType())
                    .addValue("action", event.type().auditAction())
                    .addValue("resourceType", event.resourceType())
                    .addValue("resourceId", event.resourceId())
                    .addValue("metadata", toJson(SecurityEventSanitizer.sanitize(event.metadata())))
                    .addValue("ip", event.ipAddress())
                    .addValue("createdAt", java.time.LocalDateTime.now()));
        } catch (RuntimeException e) {
            log.error("Failed to write security event action={} reason={} org={}",
                event.type().auditAction(), event.reason(), organizationId, e);
        }
    }

    /**
     * Minimal, injection-safe JSON serialization.
     *
     * <p>A hand-rolled writer is used rather than pulling Jackson into the security
     * module's dependency graph: the value space is a flat {@code Map<String,Object>} of
     * strings, numbers and booleans produced by this module's own event builders. Values
     * are escaped, so a reason string containing a quote cannot break out of the JSON.
     */
    static String toJson(Map<String, Object> metadata) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append(quote(entry.getKey())).append(':').append(value(entry.getValue()));
        }
        return json.append('}').toString();
    }

    private static String value(Object raw) {
        if (raw == null) {
            return "null";
        }
        if (raw instanceof Boolean || raw instanceof Number) {
            return raw.toString();
        }
        if (raw instanceof Iterable<?> items) {
            StringBuilder array = new StringBuilder("[");
            boolean first = true;
            for (Object item : items) {
                if (!first) {
                    array.append(',');
                }
                first = false;
                array.append(value(item));
            }
            return array.append(']').toString();
        }
        return quote(raw.toString());
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /** The correlation id of the current request/job, when one is active. */
    static String currentCorrelationId() {
        return MDC.get("correlationId");
    }
}