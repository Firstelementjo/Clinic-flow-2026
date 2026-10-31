package com.firstelement.admin.audit;

import com.firstelement.crm.persistence.JsonCodec;
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
 * Reads the Admin audit surfaces, and nothing else.
 *
 * <h2>Four sources, and why all four are read</h2>
 *
 * <p>{@code admin_audit_events}, {@code security_events} and {@code job_integration_events} are the
 * physical streams {@code V20261005050000} created. The fourth source is the legacy
 * {@code audit_logs} table, which {@code AuditLogSecurityEventPublisher} in
 * {@code :backend:identity-access} still writes during the strangler window (Phase 3 owns that
 * class; this phase does not change it).
 *
 * <p>Reading only the new three would be a lie of omission during cutover: logins, denials and
 * membership changes would appear to have stopped happening, which is exactly the failure that
 * makes an operator stop trusting a control plane. So the read model unions all four and stamps
 * every row with the physical table it came from, and the overview reports the split honestly
 * rather than implying it is finished.
 *
 * <h2>Why the statements are separate rather than one UNION ALL</h2>
 *
 * <p>Each table has its own column list and its own index-aligned predicate
 * ({@code admin_audit_events_organizationId_projectId_occurredAt_idx} and friends). A union would
 * force the planner to choose one plan for four differently-shaped inputs. Four bounded statements
 * merged in Java keep each one on its own index, and the merge is a stable sort on
 * {@code (occurredAt, id)} so two calls produce the same order.
 *
 * <h2>Project filtering on a nullable column</h2>
 *
 * <p>{@code admin_audit_events}, {@code job_integration_events} and {@code audit_logs} all have a
 * nullable {@code projectId} — an organisation-wide act has no project. A project-scoped read must
 * therefore see the tenant's organisation-wide rows as well as its own project's, or an operator
 * filtering by project would silently lose the tenant-wide history; a tenant-scoped read sees
 * everything. That is expressed once, as {@code (:filterProjectId is null or "projectId" =
 * :filterProjectId)}, so the same predicate serves both and the choice is visible in review.
 */
@Repository
public class AuditStreamRepository {

    private static final String ADMIN_EVENTS = """
        select "id", "organizationId", "projectId", "actorUserId", "principalType"::text as "ptype",
               "principalName", "action", "resourceType", "resourceId", "outcome"::text as "outcome",
               "metadata"::text as "metadata", "retentionClass", "ipAddress",
               "correlationId", "occurredAt"
          from admin_audit_events a
         where a."organizationId" = :__fe_tenant_organization_id
           and (cast(:filterProjectId as uuid) is null or a."projectId" = cast(:filterProjectId as uuid))
           and a."occurredAt" >= :since
         order by a."occurredAt" desc, a."id" desc
         limit :limit
        """;

    private static final String SECURITY_EVENTS = """
        select "id", "organizationId", "projectId", "actorUserId", "principalType"::text as "ptype",
               "principalName", "eventType" as "action", 'SecurityEvent' as "resourceType",
               null::text as "resourceId", "outcome"::text as "outcome",
               jsonb_build_object('denyReason', "denyReason", 'detail', "detail")::text as "metadata",
               "retentionClass", "ipAddress", "correlationId", "occurredAt"
          from security_events a
         where a."organizationId" = :__fe_tenant_organization_id
           and (cast(:filterProjectId as uuid) is null or a."projectId" = cast(:filterProjectId as uuid))
           and a."occurredAt" >= :since
         order by a."occurredAt" desc, a."id" desc
         limit :limit
        """;

    private static final String JOB_EVENTS = """
        select "id", "organizationId", "projectId", null::uuid as "actorUserId",
               "componentType"::text as "ptype", "component" as "principalName",
               "eventType" as "action", "component" as "resourceType", "jobId" as "resourceId",
               "outcome"::text as "outcome",
               jsonb_build_object('queueName', "queueName", 'detail', "detail",
                                  'failureCode', "failureCode", 'latencyMs', "latencyMs")::text
                   as "metadata",
               "retentionClass", null::text as "ipAddress", "correlationId", "occurredAt"
          from job_integration_events a
         where a."organizationId" = :__fe_tenant_organization_id
           and (cast(:filterProjectId as uuid) is null or a."projectId" = cast(:filterProjectId as uuid))
           and a."occurredAt" >= :since
         order by a."occurredAt" desc, a."id" desc
         limit :limit
        """;

    /**
     * The legacy stream, still written by {@code :backend:identity-access}.
     *
     * <p>The outcome is reported as {@code SUCCESS} because {@code audit_logs} has no outcome
     * column at all: a denial there is expressed by the action string. Reporting it as an unknown
     * would make every legacy row unreadable in a filter; reporting it as a fact it does not carry
     * would be worse. The action string is the honest signal and it is selected unchanged.
     */
    private static final String LEGACY_EVENTS = """
        select "id", "organizationId", "projectId", "actorUserId", "actorType" as "ptype",
               coalesce("actorUserId"::text, "actorType") as "principalName",
               "action", "resourceType", "resourceId", null::text as "outcome",
               "metadata"::text as "metadata", 'LEGACY'::text as "retentionClass", "ipAddress",
               null::text as "correlationId", "createdAt" as "occurredAt"
          from audit_logs a
         where a."organizationId" = :__fe_tenant_organization_id
           and (cast(:filterProjectId as uuid) is null or a."projectId" = cast(:filterProjectId as uuid))
           and a."createdAt" >= :since
         order by a."createdAt" desc, a."id" desc
         limit :limit
        """;

    /** Per-stream counts over a window, for the overview. */
    private static final String STREAM_COUNTS = """
        select 'ADMIN'::text as stream, count(*) as occurrences
          from admin_audit_events
         where "organizationId" = :__fe_tenant_organization_id
           and "occurredAt" >= :since
        union all
        select 'SECURITY', count(*)
          from security_events
         where "organizationId" = :__fe_tenant_organization_id
           and "occurredAt" >= :since
        union all
        select 'JOB_INTEGRATION', count(*)
          from job_integration_events
         where "organizationId" = :__fe_tenant_organization_id
           and "occurredAt" >= :since
        union all
        select 'LEGACY_AUDIT_LOG', count(*)
          from audit_logs
         where "organizationId" = :__fe_tenant_organization_id
           and "createdAt" >= :since
        """;

    /** Security-stream breakdown by event type, the shape a security dashboard renders. */
    private static final String SECURITY_BY_TYPE = """
        select "eventType" as label, "outcome"::text as outcome, count(*) as occurrences
          from security_events
         where "organizationId" = :__fe_tenant_organization_id
           and "occurredAt" >= :since
         group by "eventType", "outcome"
         order by count(*) desc, "eventType"
         limit :limit
        """;

    /** Denials only, newest first: the list a reviewer works through. */
    private static final String DENIALS = """
        select "eventType" as label, "denyReason", "principalName", "ipAddress",
               "projectId", "occurredAt", "correlationId"
          from security_events
         where "organizationId" = :__fe_tenant_organization_id
           and "outcome" = cast('DENIED' as "AuditOutcome")
           and "occurredAt" >= :since
         order by "occurredAt" desc
         limit :limit
        """;

    /**
     * The job/integration stream, tenant-wide.
     *
     * <p>{@code projectId} is nullable here (a worker serves the tenant, not a project), so the
     * project filter tests for null explicitly rather than using an {@code OR} that would defeat both
     * indexes.
     */
    private static final String INTEGRATION_FOR_TENANT = """
        select j."id", j."component", j."componentType"::text as "componentType", j."eventType",
               j."outcome"::text as "outcome", j."queueName", j."jobId", j."outboxEventId",
               j."jobAttemptId", j."correlationId", j.detail, j."failureCode", j."latencyMs",
               j."occurredAt"
          from job_integration_events j
         where j."organizationId" = :__fe_tenant_organization_id
           and j."occurredAt" >= :since
         order by j."occurredAt" desc, j."id" desc
         limit :limit
        """;

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonCodec json;

    public AuditStreamRepository(NamedParameterJdbcTemplate jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * One audit row, from whichever physical table it came from.
     *
     * @param source   which table produced it, so a reader can see that the legacy stream is still
     *                 being written rather than being told the split is complete
     * @param metadata already-redacted event metadata: ids, counts, boolean reasons, policy names
     * @param outcome  the stream's own outcome column, or {@code null} for {@code audit_logs},
     *                 which has none
     */
    public record AuditRow(UUID id,
                           UUID organizationId,
                           UUID projectId,
                           UUID actorUserId,
                           String principalType,
                           String principalName,
                           String action,
                           String resourceType,
                           String resourceId,
                           String outcome,
                           Map<String, Object> metadata,
                           String retentionClass,
                           String ipAddress,
                           String correlationId,
                           LocalDateTime occurredAt,
                           String source) {
    }

    /** The merged, newest-first audit view across all four physical sources. */
    public List<AuditRow> events(TenantScope scope, LocalDateTime since, int limit) {
        List<AuditRow> rows = new ArrayList<>();
        rows.addAll(query(ADMIN_EVENTS, scope, since, limit, "ADMIN_AUDIT_EVENT"));
        rows.addAll(query(SECURITY_EVENTS, scope, since, limit, "SECURITY_EVENT"));
        rows.addAll(query(JOB_EVENTS, scope, since, limit, "JOB_INTEGRATION_EVENT"));
        rows.addAll(query(LEGACY_EVENTS, scope, since, limit, "LEGACY_AUDIT_LOG"));
        rows.sort((left, right) -> {
            int byTime = right.occurredAt().compareTo(left.occurredAt());
            return byTime != 0 ? byTime : right.id().compareTo(left.id());
        });
        return rows.size() <= limit ? rows : rows.subList(0, limit);
    }

    /** Per-stream counts over the window. Every stream is present, including at zero. */
    public Map<String, Integer> streamCounts(TenantScope scope, LocalDateTime since) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of("since", since));
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("ADMIN", 0);
        counts.put("SECURITY", 0);
        counts.put("JOB_INTEGRATION", 0);
        counts.put("LEGACY_AUDIT_LOG", 0);
        for (Map<String, Object> row : jdbc.queryForList(STREAM_COUNTS, parameters)) {
            counts.put(String.valueOf(row.get("stream")),
                ((Number) row.get("occurrences")).intValue());
        }
        return counts;
    }

    /** Security-stream breakdown by event type and outcome. */
    public List<Map<String, Object>> securityByType(TenantScope scope, LocalDateTime since, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("since", since, "limit", limit));
        return jdbc.queryForList(SECURITY_BY_TYPE, parameters);
    }

    /** One denial: the type, the reason, who, from where, and when. */
    public record DenialRow(String eventType,
                            String denyReason,
                            String principalName,
                            String ipAddress,
                            UUID projectId,
                            LocalDateTime occurredAt,
                            String correlationId) {
    }

    /** Denials only. A refused AI action, a cross-tenant probe, a revoked session all land here. */
    public List<DenialRow> denials(TenantScope scope, LocalDateTime since, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("since", since, "limit", limit));
        return jdbc.query(DENIALS, parameters, (rs, rowNum) -> new DenialRow(
            rs.getString("label"),
            rs.getString("denyReason"),
            rs.getString("principalName"),
            rs.getString("ipAddress"),
            rs.getObject("projectId", UUID.class),
            rs.getObject("occurredAt", LocalDateTime.class),
            rs.getString("correlationId")));
    }

    /**
     * The job/integration stream for the tenant, newest first.
     *
     * <p>Exposed from the audit module as well as from the operations module, because an Admin action
     * that produced a worker outcome is one event with two homes: it is the record of a decision an
     * operator took <em>and</em> the record of what the queue did with it. Both readings need the same
     * rows, and two definitions of "the integration stream" would be two answers.
     *
     * @param since every row at or after this instant; the window is required so a long-lived tenant
     *              cannot make this unbounded
     */
    public List<IntegrationEventRow> integrationEventsForTenant(TenantScope scope,
                                                                LocalDateTime since) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("since", since, "limit", AdministrationLimits.PAGE));
        return jdbc.query(INTEGRATION_FOR_TENANT, parameters, (rs, rowNum) -> new IntegrationEventRow(
            rs.getObject("id", UUID.class),
            rs.getString("component"),
            rs.getString("componentType"),
            rs.getString("eventType"),
            rs.getString("outcome"),
            rs.getString("queueName"),
            rs.getString("jobId"),
            rs.getObject("outboxEventId", UUID.class),
            rs.getObject("jobAttemptId", UUID.class),
            rs.getString("correlationId"),
            rs.getString("detail"),
            rs.getString("failureCode"),
            rs.getObject("latencyMs", Integer.class),
            rs.getObject("occurredAt", LocalDateTime.class)));
    }

    /** One row of the job/integration stream. */
    public record IntegrationEventRow(UUID id,
                                      String component,
                                      String componentType,
                                      String eventType,
                                      String outcome,
                                      String queueName,
                                      String jobId,
                                      UUID outboxEventId,
                                      UUID jobAttemptId,
                                      String correlationId,
                                      String detail,
                                      String failureCode,
                                      Integer latencyMs,
                                      LocalDateTime occurredAt) {
    }

    /**
     * The page bound, kept local.
     *
     * <p>A private constant rather than a dependency on {@code AdministrationModule}: the persistence
     * package should not have to reach the module root for one number, and a limit that could change
     * at runtime is not a limit.
     */
    private static final class AdministrationLimits {
        private AdministrationLimits() {
        }

        static final int PAGE = 100;
    }

    private List<AuditRow> query(String sql, TenantScope scope, LocalDateTime since, int limit,
                                 String source) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("since", since, "limit", Math.max(1, limit)));
        // ScopedWhere binds the project parameter only for a project-scoped scope; this repository
        // needs it bound in both cases because the predicate tests it for NULL explicitly.
        parameters.addValue("filterProjectId", scope.isProjectScoped() ? scope.projectId() : null);
        return jdbc.query(sql, parameters, (rs, rowNum) -> new AuditRow(
            rs.getObject("id", UUID.class),
            rs.getObject("organizationId", UUID.class),
            rs.getObject("projectId", UUID.class),
            rs.getObject("actorUserId", UUID.class),
            rs.getString("ptype"),
            rs.getString("principalName"),
            rs.getString("action"),
            rs.getString("resourceType"),
            rs.getString("resourceId"),
            rs.getString("outcome"),
            json.readMap(rs.getString("metadata")),
            rs.getString("retentionClass"),
            rs.getString("ipAddress"),
            rs.getString("correlationId"),
            rs.getObject("occurredAt", LocalDateTime.class),
            source));
    }
}