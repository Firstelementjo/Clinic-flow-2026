package com.firstelement.security.platform;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The append-only cross-tenant audit.
 *
 * <p>Every method here is an insert or a read. There is no update and no delete, because the table
 * has a trigger that refuses both — this class not offering them is the application half of the same
 * rule.
 *
 * <p>Denials are recorded as well as successes. A platform operator probing what exists is exactly
 * the behaviour an operator audit exists to reveal, so a refused action is not an exception to the
 * record; it is one of the more valuable rows in it.
 */
public class PlatformAuditRepository {

    /** What happened. Kept as stable tokens so a query can rely on them. */
    public static final String LOGIN = "PLATFORM_LOGIN";
    public static final String LOGIN_FAILED = "PLATFORM_LOGIN_FAILED";
    public static final String MFA_FAILED = "PLATFORM_LOGIN_MFA_FAILED";
    public static final String ELEVATED = "PLATFORM_ELEVATED";
    public static final String ELEVATION_REFUSED = "PLATFORM_ELEVATION_REFUSED";
    public static final String PROJECT_CREATED = "PROJECT_CREATED";
    public static final String ACCOUNT_CREATED = "ACCOUNT_CREATED";
    public static final String ACCESS_GRANTED = "ACCESS_GRANTED";
    public static final String CAPABILITY_DENIED = "PLATFORM_CAPABILITY_DENIED";
    public static final String VIEWED = "PLATFORM_VIEWED";

    public static final String ALLOWED = "ALLOWED";
    public static final String DENIED = "DENIED";
    public static final String FAILED = "FAILED";

    /** Detail is size-bounded in the writer, not only by convention at the call site. */
    private static final int DETAIL_LIMIT = 2000;
    private static final int REASON_LIMIT = 500;

    private final NamedParameterJdbcTemplate jdbc;

    public PlatformAuditRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record AuditFilter(
        UUID clinicId,
        UUID organizationId,
        UUID operatorId,
        String actor,
        String action,
        Instant from,
        Instant to,
        String outcome,
        int limit
    ) {
        public AuditFilter {
            if (limit <= 0) limit = 100;
            if (limit > 500) limit = 500;
        }
    }

    public record EnrichedAuditEntry(
        UUID id,
        Instant occurredAt,
        UUID operatorId,
        String operatorName,
        String operatorEmail,
        String action,
        String resourceType,
        String resourceId,
        UUID projectId,
        String clinicName,
        UUID organizationId,
        String organizationName,
        String outcome,
        String ipAddress,
        String reason,
        String detail,
        String correlationId
    ) {}

    /**
     * Records one action, in its own transaction.
     *
     * <p>{@code REQUIRES_NEW} is load-bearing and not incidental. The caller is usually inside a
     * {@code @Transactional} method that is about to throw -- and an audit row written in that
     * transaction disappears with the rollback. That is the failure this method exists to prevent: the
     * denial is precisely the thing worth keeping, and it is exactly the case where the surrounding
     * transaction unwinds.
     *
     * <p>{@code ExportDownloadService} reaches the same conclusion from the other direction, by
     * declining to be transactional at all so a refused download's audit survives.
     *
     * @param organizationId may be null for a platform-wide action; the database requires a reason
     *                       in that case rather than accepting an unexplained blank scope
     */
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void record(String action,
                       UUID operatorId,
                       UUID sessionId,
                       UUID organizationId,
                       UUID projectId,
                       String resourceType,
                       String resourceId,
                       String reason,
                       String detail,
                       String outcome,
                       String correlationId,
                       String ipAddress) {
        String resolvedIp = ipAddress != null ? ipAddress : MDC.get("remoteIp");

        UUID validOperatorId = null;
        if (operatorId != null) {
            Integer count = jdbc.queryForObject(
                "select count(*) from platform_operators where id = :oid",
                new MapSqlParameterSource("oid", operatorId),
                Integer.class);
            if (count != null && count > 0) {
                validOperatorId = operatorId;
            }
        }

        UUID validSessionId = null;
        if (sessionId != null) {
            Integer count = jdbc.queryForObject(
                "select count(*) from platform_sessions where id = :sid",
                new MapSqlParameterSource("sid", sessionId),
                Integer.class);
            if (count != null && count > 0) {
                validSessionId = sessionId;
            }
        }

        jdbc.update("""
            insert into platform_audit_events
                ("id", "operatorId", "sessionId", "action", "resourceType", "resourceId",
                 "organizationId", "projectId", "reason", "detail", "correlationId",
                 "ipAddress", "outcome")
            values
                (:id, :operatorId, :sessionId, :action, :resourceType, :resourceId,
                 :organizationId, :projectId, :reason, :detail, :correlationId,
                 :ipAddress, :outcome)
            """,
            new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID())
                    .addValue("operatorId", validOperatorId)
                    .addValue("sessionId", validSessionId)
                    .addValue("action", action)
                    .addValue("resourceType", resourceType)
                    .addValue("resourceId", resourceId)
                    .addValue("organizationId", organizationId)
                    .addValue("projectId", projectId)
                    .addValue("reason", truncate(reason, REASON_LIMIT))
                    .addValue("detail", truncate(detail, DETAIL_LIMIT))
                    .addValue("correlationId", correlationId)
                    .addValue("ipAddress", resolvedIp)
                    .addValue("outcome", outcome));
    }

    /** Convenience for a successful, tenant-scoped action. */
    public void allowed(String action, PlatformOperatorContext operator, UUID organizationId,
                        UUID projectId, String resourceType, String resourceId, String reason,
                        String detail, String correlationId) {
        record(action, operator.operatorId(), operator.sessionId(), organizationId, projectId,
                resourceType, resourceId, reason, detail, ALLOWED, correlationId, null);
    }

    /** Convenience for a refusal. Recorded with the same weight as a success. */
    public void denied(String action, PlatformOperatorContext operator, String reason,
                       String correlationId) {
        record(action, operator == null ? null : operator.operatorId(),
                operator == null ? null : operator.sessionId(), null, null, null, null,
                reason, null, DENIED, correlationId, null);
    }

    /** Structured cross-tenant audit search with joins and full filtering. */
    public List<EnrichedAuditEntry> search(AuditFilter filter) {
        StringBuilder sql = new StringBuilder("""
            select pae."id",
                   pae."occurredAt",
                   pae."operatorId",
                   po."displayName" as "operatorName",
                   po."email" as "operatorEmail",
                   pae."action",
                   pae."resourceType",
                   pae."resourceId",
                   pae."projectId",
                   p."name" as "clinicName",
                   pae."organizationId",
                   o."name" as "organizationName",
                   pae."outcome",
                   pae."ipAddress",
                   pae."reason",
                   pae."detail",
                   pae."correlationId"
              from "public"."platform_audit_events" pae
              left join "public"."platform_operators" po on po."id" = pae."operatorId"
              left join "public"."projects" p on p."id" = pae."projectId"
              left join "public"."organizations" o on o."id" = pae."organizationId"
             where 1 = 1
            """);

        MapSqlParameterSource params = new MapSqlParameterSource();

        if (filter.clinicId() != null) {
            sql.append(" and pae.\"projectId\" = :clinicId");
            params.addValue("clinicId", filter.clinicId());
        }
        if (filter.organizationId() != null) {
            sql.append(" and pae.\"organizationId\" = :organizationId");
            params.addValue("organizationId", filter.organizationId());
        }
        if (filter.operatorId() != null) {
            sql.append(" and pae.\"operatorId\" = :operatorId");
            params.addValue("operatorId", filter.operatorId());
        }
        if (filter.actor() != null && !filter.actor().isBlank()) {
            sql.append(" and (lower(po.\"email\") like :actor or lower(po.\"displayName\") like :actor)");
            params.addValue("actor", "%" + filter.actor().trim().toLowerCase() + "%");
        }
        if (filter.action() != null && !filter.action().isBlank()) {
            sql.append(" and pae.\"action\" = :action");
            params.addValue("action", filter.action().trim());
        }
        if (filter.outcome() != null && !filter.outcome().isBlank()) {
            sql.append(" and pae.\"outcome\" = :outcome");
            params.addValue("outcome", filter.outcome().trim());
        }
        if (filter.from() != null) {
            sql.append(" and pae.\"occurredAt\" >= :from");
            params.addValue("from", Timestamp.from(filter.from()));
        }
        if (filter.to() != null) {
            sql.append(" and pae.\"occurredAt\" <= :to");
            params.addValue("to", Timestamp.from(filter.to()));
        }

        sql.append(" order by pae.\"occurredAt\" desc limit :limit");
        params.addValue("limit", filter.limit());

        return jdbc.query(sql.toString(), params, (rs, rowNum) -> {
            Timestamp ts = rs.getTimestamp("occurredAt");
            return new EnrichedAuditEntry(
                rs.getObject("id", UUID.class),
                ts != null ? ts.toInstant() : null,
                rs.getObject("operatorId", UUID.class),
                rs.getString("operatorName"),
                rs.getString("operatorEmail"),
                rs.getString("action"),
                rs.getString("resourceType"),
                rs.getString("resourceId"),
                rs.getObject("projectId", UUID.class),
                rs.getString("clinicName"),
                rs.getObject("organizationId", UUID.class),
                rs.getString("organizationName"),
                rs.getString("outcome"),
                rs.getString("ipAddress"),
                rs.getString("reason"),
                rs.getString("detail"),
                rs.getString("correlationId")
            );
        });
    }

    /** Returns all distinct action names logged in the audit table. */
    public List<String> distinctActions() {
        return jdbc.queryForList("""
            select distinct "action"
              from "public"."platform_audit_events"
             where "action" is not null
             order by "action"
            """, new MapSqlParameterSource(), String.class);
    }

    /** The most recent events, optionally narrowed to one operator or one tenant. */
    public List<Map<String, Object>> recent(UUID operatorId, UUID organizationId, int limit) {
        StringBuilder sql = new StringBuilder("""
            select pae."id",
                   pae."occurredAt",
                   pae."operatorId",
                   po."displayName" as "operatorName",
                   po."email" as "operatorEmail",
                   pae."action",
                   pae."resourceType",
                   pae."resourceId",
                   pae."projectId",
                   p."name" as "clinicName",
                   pae."organizationId",
                   o."name" as "organizationName",
                   pae."outcome",
                   pae."ipAddress",
                   pae."reason",
                   pae."detail",
                   pae."correlationId"
              from "public"."platform_audit_events" pae
              left join "public"."platform_operators" po on po."id" = pae."operatorId"
              left join "public"."projects" p on p."id" = pae."projectId"
              left join "public"."organizations" o on o."id" = pae."organizationId"
             where 1 = 1
            """);
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (operatorId != null) {
            sql.append(" and pae.\"operatorId\" = :operatorId");
            params.addValue("operatorId", operatorId);
        }
        if (organizationId != null) {
            sql.append(" and pae.\"organizationId\" = :organizationId");
            params.addValue("organizationId", organizationId);
        }
        sql.append(" order by pae.\"occurredAt\" desc limit :limit");
        params.addValue("limit", Math.max(1, Math.min(limit, 500)));
        return jdbc.queryForList(sql.toString(), params);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
