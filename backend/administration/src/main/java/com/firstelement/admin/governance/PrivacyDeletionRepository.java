package com.firstelement.admin.governance;

import com.firstelement.security.query.ScopedWhere;
import com.firstelement.security.query.TenantScope;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code privacy_deletion_requests} and {@code privacy_deletion_targets}: the state machine and the
 * evidence of what a deletion request decided.
 *
 * <h2>The request never deletes anything</h2>
 *
 * <p>Every method here is a write of a <em>decision record</em>. No statement removes a row of
 * customer data, and none is capable of being edited into one. The removal itself is a durable job
 * that ships disabled (ADR-0009 §4 rule 3), so what an operator can do from the Admin surface is
 * open a request with an impact count, see which holds block it, and read what was decided — which
 * is exactly the "no silent destructive action" requirement made structural.
 *
 * <h2>Targets are append-only and their decision is a biconditional</h2>
 *
 * <p>{@code privacy_deletion_targets_legal_hold_check} states, as a biconditional, that
 * {@code decision = 'RETAINED_LEGAL_HOLD'} exactly when a hold is named. That is what makes "we kept
 * this because a hold existed" a fact on the row rather than an inference, and it is why a request
 * that runs under a hold writes a target row for each affected scope with that decision instead of
 * silently producing no targets at all.
 */
@Repository
public class PrivacyDeletionRepository {

    private static final String INSERT_REQUEST = """
        insert into privacy_deletion_requests (
            "id", "organizationId", "projectId", "subjectUserId", "subjectType", "status", "reason",
            "requestedAt", "requestedByUserId", "evidenceReference", "version", "createdAt",
            "updatedAt")
        values (
            :id, :__fe_tenant_organization_id, :projectId, :subjectUserId, :subjectType,
            cast('REQUESTED' as "PrivacyDeletionRequestStatus"), :reason, :now, :requestedByUserId,
            :evidenceReference, 0, :now, :now)
        returning "id"
        """;

    private static final String INSERT_TARGET = """
        insert into privacy_deletion_targets (
            "id", "organizationId", "projectId", "deletionRequestId", "targetType", "targetId",
            "targetRef", "rowsRemoved", "decision", "decidedAt", "decidedByUserId", "legalHoldId")
        values (
            :id, :__fe_tenant_organization_id, :projectId, :requestId, :targetType, :targetId,
            :targetRef, :rowsRemoved, :decision, :now, :decidedByUserId, :legalHoldId)
        """;

    private static final String REQUESTS = """
        select r."id", r."organizationId", r."projectId", r."subjectUserId", r."subjectType",
               r."status"::text as "status", r."reason", r."requestedAt", r."requestedByUserId",
               r."verifiedAt", r."verifiedByUserId", r."startedAt", r."completedAt", r."cancelledAt",
               r."rejectionReason", r."evidenceReference", r."version", r."createdAt"
          from privacy_deletion_requests r
         where r."organizationId" = :__fe_tenant_organization_id
           and (cast(:filterProjectId as uuid) is null
                or r."projectId" = cast(:filterProjectId as uuid))
         order by r."requestedAt" desc, r."id" desc
         limit :limit
        """;

    private static final String TARGETS_FOR_REQUEST = """
        select "id", "deletionRequestId", "projectId", "targetType", "targetId", "targetRef",
               "rowsRemoved", "decision", "decidedAt", "decidedByUserId", "legalHoldId"
          from privacy_deletion_targets t
         where t."organizationId" = :__fe_tenant_organization_id
           and t."deletionRequestId" = :requestId
         order by t."decidedAt", t."id"
         limit :limit
        """;

    /** The outstanding queue: what is requested or verified and not yet decided. */
    private static final String OPEN_REQUESTS = """
        select r."id", r."organizationId", r."projectId", r."subjectUserId", r."subjectType",
               r."status"::text as "status", r."reason", r."requestedAt", r."requestedByUserId",
               r."verifiedAt", r."verifiedByUserId", r."startedAt", r."completedAt", r."cancelledAt",
               r."rejectionReason", r."evidenceReference", r."version", r."createdAt"
          from privacy_deletion_requests r
         where r."organizationId" = :__fe_tenant_organization_id
           and r."status" in (cast('REQUESTED' as "PrivacyDeletionRequestStatus"),
                              cast('VERIFIED' as "PrivacyDeletionRequestStatus"),
                              cast('IN_PROGRESS' as "PrivacyDeletionRequestStatus"))
         order by r."requestedAt", r."id"
         limit :limit
        """;

    /**
     * Subject-existence check, tenant-scoped.
     *
     * <p>{@code privacy_deletion_requests."subjectUserId"} is NOT NULL and carries a composite
     * foreign key over {@code ("subjectUserId", "organizationId")} to {@code users}. The promoted
     * schema therefore requires every deletion subject to be a {@code users} row of this tenant,
     * including a request whose {@code subjectType} is {@code CUSTOMER} — which means the caller
     * must name the tenant's own user record for the subject. That constraint is real and is
     * reported to the orchestrator; this read is how the request path checks it before the insert,
     * so the failure is a non-disclosing denial rather than a foreign-key violation.
     */
    private static final String SUBJECT_IN_TENANT = """
        select count(*) from users u
         where u."organizationId" = :__fe_tenant_organization_id and u."id" = :subjectUserId
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public PrivacyDeletionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One request row. */
    public record RequestRow(UUID id,
                             UUID organizationId,
                             UUID projectId,
                             UUID subjectUserId,
                             String subjectType,
                             String status,
                             String reason,
                             LocalDateTime requestedAt,
                             UUID requestedByUserId,
                             LocalDateTime verifiedAt,
                             UUID verifiedByUserId,
                             LocalDateTime startedAt,
                             LocalDateTime completedAt,
                             LocalDateTime cancelledAt,
                             String rejectionReason,
                             String evidenceReference,
                             Integer version,
                             LocalDateTime createdAt) {
    }

    /** One decided target. {@code decision} is the field that makes the row auditable. */
    public record TargetRow(UUID id,
                            UUID deletionRequestId,
                            UUID projectId,
                            String targetType,
                            UUID targetId,
                            String targetRef,
                            Long rowsRemoved,
                            String decision,
                            LocalDateTime decidedAt,
                            UUID decidedByUserId,
                            String legalHoldId) {
    }

    /** Opens a request. The subject must already be a user of this tenant. */
    public UUID insertRequest(TenantScope scope, UUID id, UUID projectId, UUID subjectUserId,
                              String subjectType, String reason, UUID requestedByUserId,
                              String evidenceReference, LocalDateTime at) {
        // A LinkedHashMap, not Map.of: the evidence reference is legitimately null on a freshly
        // opened request (the schema allows it, and Map.of would throw NPE before the DB saw it).
        Map<String, Object> named = new LinkedHashMap<>();
        named.put("id", id);
        named.put("subjectUserId", subjectUserId);
        named.put("subjectType", subjectType);
        named.put("reason", reason);
        named.put("requestedByUserId", requestedByUserId);
        named.put("evidenceReference", evidenceReference);
        named.put("now", at);
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(named);
        parameters.addValue("projectId", projectId);
        return jdbc.queryForObject(INSERT_REQUEST, parameters, UUID.class);
    }

    /**
     * Records one decided target.
     *
     * @param legalHoldId required exactly when {@code decision} is {@code RETAINED_LEGAL_HOLD}; the
     *                    schema states that as a biconditional and this does not work around it
     */
    public void insertTarget(TenantScope scope, UUID id, UUID requestId, UUID projectId,
                             String targetType, UUID targetId, String targetRef, Long rowsRemoved,
                             String decision, UUID decidedByUserId, String legalHoldId,
                             LocalDateTime at) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of(
            "id", id,
            "requestId", requestId,
            "targetType", targetType,
            "decision", decision,
            "decidedByUserId", decidedByUserId,
            "now", at));
        parameters.addValue("projectId", projectId);
        parameters.addValue("targetId", targetId);
        parameters.addValue("targetRef", targetRef);
        parameters.addValue("rowsRemoved", rowsRemoved);
        parameters.addValue("legalHoldId", legalHoldId);
        jdbc.update(INSERT_TARGET, parameters);
    }

    /** Every request for the tenant, newest first. */
    public List<RequestRow> requests(TenantScope scope, UUID projectId, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("limit", Math.max(1, limit)));
        parameters.addValue("filterProjectId", projectId);
        return jdbc.query(REQUESTS, parameters, REQUEST);
    }

    /** The outstanding queue, oldest first. */
    public List<RequestRow> openRequests(TenantScope scope, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("limit", Math.max(1, limit)));
        return jdbc.query(OPEN_REQUESTS, parameters, REQUEST);
    }

    /** What was decided for one request. */
    public List<TargetRow> targets(TenantScope scope, UUID requestId, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("requestId", requestId, "limit", Math.max(1, limit)));
        return jdbc.query(TARGETS_FOR_REQUEST, parameters, (rs, rowNum) -> new TargetRow(
            rs.getObject("id", UUID.class),
            rs.getObject("deletionRequestId", UUID.class),
            rs.getObject("projectId", UUID.class),
            rs.getString("targetType"),
            rs.getObject("targetId", UUID.class),
            rs.getString("targetRef"),
            rs.getObject("rowsRemoved", Long.class),
            rs.getString("decision"),
            rs.getObject("decidedAt", LocalDateTime.class),
            rs.getObject("decidedByUserId", UUID.class),
            rs.getString("legalHoldId")));
    }

    /** Whether the subject resolves to a user of this tenant. */
    public boolean subjectInTenant(TenantScope scope, UUID subjectUserId) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("subjectUserId", subjectUserId));
        Integer count = jdbc.queryForObject(SUBJECT_IN_TENANT, parameters, Integer.class);
        return count != null && count > 0;
    }

    /** Outstanding requests by status, for the overview. */
    public Map<String, Integer> countsByStatus(TenantScope scope) {
        String sql = """
            select r."status"::text as status, count(*) as occurrences
              from privacy_deletion_requests r
             where r."organizationId" = :__fe_tenant_organization_id
             group by r."status"
             order by r."status"
            """;
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of());
        Map<String, Integer> counts = new LinkedHashMap<>();
        // Every declared state is seeded at zero first, so "no deletion has ever been requested"
        // and "the state machine has six states" cannot be confused. FR-7.6's rule that absent is
        // not zero, applied to a rollup: a rollup that only lists the states it happens to have
        // cannot be read as a complete picture.
        for (String state : List.of("REQUESTED", "VERIFIED", "IN_PROGRESS", "COMPLETED", "REJECTED",
            "CANCELLED")) {
            counts.put(state, 0);
        }
        for (Map<String, Object> row : jdbc.queryForList(sql, parameters)) {
            counts.put(String.valueOf(row.get("status")),
                ((Number) row.get("occurrences")).intValue());
        }
        return counts;
    }

    private static final org.springframework.jdbc.core.RowMapper<RequestRow> REQUEST =
        (rs, rowNum) -> new RequestRow(
            rs.getObject("id", UUID.class),
            rs.getObject("organizationId", UUID.class),
            rs.getObject("projectId", UUID.class),
            rs.getObject("subjectUserId", UUID.class),
            rs.getString("subjectType"),
            rs.getString("status"),
            rs.getString("reason"),
            rs.getObject("requestedAt", LocalDateTime.class),
            rs.getObject("requestedByUserId", UUID.class),
            rs.getObject("verifiedAt", LocalDateTime.class),
            rs.getObject("verifiedByUserId", UUID.class),
            rs.getObject("startedAt", LocalDateTime.class),
            rs.getObject("completedAt", LocalDateTime.class),
            rs.getObject("cancelledAt", LocalDateTime.class),
            rs.getString("rejectionReason"),
            rs.getString("evidenceReference"),
            rs.getObject("version", Integer.class),
            rs.getObject("createdAt", LocalDateTime.class));
}