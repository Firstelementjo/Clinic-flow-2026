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
 * Reads the retention and legal-hold state ADR-0008 §2 rule 5 makes a precondition of every purge.
 *
 * <h2>Why this is a repository and not a boolean</h2>
 *
 * <p>"A purge job refuses to run for a tenant under legal hold. This is a Phase 7 dependency: Admin
 * must expose hold state before the first purge job is enabled." Exposing it means an operator can
 * answer three different questions — is this tenant held at all, what is held and why, and when was
 * that decided — so the shape is a row plus its append-only history rather than a yes/no.
 *
 * <h2>Scope resolution, in one place</h2>
 *
 * <p>{@code V20261005050000} documents the lookup as tenant-led with the project last: the index is
 * {@code ("organizationId", "status", "placedAt")} and {@code ("organizationId", "projectId",
 * "status")}, and "if the purge is per project, this project" is the question. {@link #activeHolds}
 * therefore answers it with an explicit {@code coversProject} test rather than an {@code OR} over
 * {@code projectId}: a {@code TENANT}-scoped hold covers everything, a {@code PROJECT}-scoped hold
 * covers only its own project, and a hold on <em>another</em> project never covers this one.
 *
 * <p>The {@code OR} alternative would defeat both indexes and, worse, would be ambiguous with the
 * nullable column: {@code (projectId is null or projectId = :project)} and
 * {@code projectId = :project} are different questions, and only the second is the right one for a
 * per-project purge.
 */
@Repository
public class LegalHoldRepository {

    /** The active holds, tenant-wide. Ordered oldest first: the oldest hold is the one that bites. */
    private static final String ACTIVE = """
        select "id", "organizationId", "projectId", "scopeType"::text as "scopeType", "scopeRef",
               "reason", "status", "placedAt", "placedByUserId", "expiresAt", "releasedAt",
               "releasedByUserId", "releaseReason", "version", "createdAt"
          from legal_holds h
         where h."organizationId" = :__fe_tenant_organization_id
           and h."status" = 'ACTIVE'
         order by h."placedAt", h."id"
         limit :limit
        """;

    /**
     * The active holds that cover a given purge scope.
     *
     * <p>Bound as {@code coversProject}: a {@code TENANT} hold has a NULL project and covers
     * everything; a {@code PROJECT} hold covers only that project; a {@code TABLE}/{@code RECORD}
     * hold names its scope in {@code scopeRef} and is reported but never treated as covering a
     * whole tenant's purge, because "we kept this table because of a hold" is a per-table decision
     * and pretending otherwise would silently excuse a purge of the rest.
     *
     * <p>{@code coversProject} is bound with {@link java.sql.Types#OTHER}. Without the explicit
     * SQL type the driver infers {@code varchar} from a null value and the statement cannot be
     * prepared; with it, {@code cast(:p as uuid)} is a normal, portable spelling.
     */
    private static final String ACTIVE_COVERING = """
        select "id", "organizationId", "projectId", "scopeType"::text as "scopeType", "scopeRef",
               "reason", "status", "placedAt", "placedByUserId", "expiresAt", "releasedAt",
               "releasedByUserId", "releaseReason", "version", "createdAt"
          from legal_holds h
         where h."organizationId" = :__fe_tenant_organization_id
           and h."status" = 'ACTIVE'
           and ((cast(:coversProject as uuid) is null) or h."scopeType" = 'TENANT'
                or (h."scopeType" = 'PROJECT' and h."projectId" = cast(:coversProject as uuid)))
         order by h."placedAt", h."id"
         limit :limit
        """;

    /** One hold's append-only history, oldest first. */
    private static final String HISTORY = """
        select "id", "legalHoldId", "eventType", "actorUserId", "actorPrincipal", "note", "eventAt"
          from legal_hold_events e
         where e."organizationId" = :__fe_tenant_organization_id
           and e."legalHoldId" = :holdId
         order by e."eventAt", e."id"
         limit :limit
        """;

    /**
     * {@code PLACE}. The schema CHECKs carry the invariants this statement relies on:
     * {@code legal_holds_scope_pair_check} (a PROJECT hold names a project), {@code _reason_check}
     * (a hold says why) and {@code _version_check}. The status starts {@code ACTIVE} with a NULL
     * {@code releasedAt}, which {@code legal_holds_released_pair_check} requires as a biconditional.
     */
    private static final String INSERT_HOLD = """
        insert into legal_holds (
            "id", "organizationId", "projectId", "scopeType", "scopeRef", "reason", "status",
            "placedAt", "placedByUserId", "expiresAt", "version", "createdAt", "updatedAt")
        values (
            :id, :__fe_tenant_organization_id, :projectId, cast(:scopeType as "LegalHoldScopeType"),
            :scopeRef, :reason, 'ACTIVE', :now, :placedByUserId, :expiresAt, 0, :now, :now)
        returning "id"
        """;

    private static final String INSERT_EVENT = """
        insert into legal_hold_events (
            "id", "organizationId", "projectId", "legalHoldId", "eventType", "actorUserId",
            "actorPrincipal", "note", "eventAt")
        values (
            :id, :__fe_tenant_organization_id, :projectId, :holdId, :eventType, :actorUserId,
            :actorPrincipal, :note, :now)
        """;

    /**
     * {@code RELEASE}. Guarded on {@code status = 'ACTIVE'} and carrying the optimistic-concurrency
     * version, so two operators releasing the same hold produce one success and one zero-row
     * update — which is what makes the command idempotent rather than merely repeatable.
     *
     * <p>{@code legal_holds_released_actor_check} refuses a RELEASED row without
     * {@code releasedByUserId} and {@code releaseReason}, so "we released it" is never anonymous.
     */
    private static final String RELEASE_HOLD = """
        update legal_holds h
           set "status" = 'RELEASED', "releasedAt" = :now, "releasedByUserId" = :releasedBy,
               "releaseReason" = :releaseReason, "version" = h."version" + 1, "updatedAt" = :now
         where h."id" = :id
           and h."organizationId" = :__fe_tenant_organization_id
           and h."status" = 'ACTIVE'
           and h."version" = :expectedVersion
        """;

    private static final String FIND_HOLD = """
        select "id", "organizationId", "projectId", "scopeType"::text as "scopeType", "scopeRef",
               "reason", "status", "placedAt", "placedByUserId", "expiresAt", "releasedAt",
               "releasedByUserId", "releaseReason", "version", "createdAt"
          from legal_holds h
         where h."id" = :id
           and h."organizationId" = :__fe_tenant_organization_id
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public LegalHoldRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One hold.
     *
     * @param projectId {@code null} for a {@code TENANT}-scoped hold
     * @param scopeType TENANT, PROJECT, TABLE or RECORD
     */
    public record HoldRow(UUID id,
                          UUID organizationId,
                          UUID projectId,
                          String scopeType,
                          String scopeRef,
                          String reason,
                          String status,
                          LocalDateTime placedAt,
                          UUID placedByUserId,
                          LocalDateTime expiresAt,
                          LocalDateTime releasedAt,
                          UUID releasedByUserId,
                          String releaseReason,
                          Integer version,
                          LocalDateTime createdAt) {
    }

    /** One append-only decision about a hold. */
    public record HoldEventRow(UUID id,
                               UUID legalHoldId,
                               String eventType,
                               UUID actorUserId,
                               String actorPrincipal,
                               String note,
                               LocalDateTime eventAt) {
    }

    /** Every active hold for the tenant, oldest first. */
    public List<HoldRow> activeHolds(TenantScope scope, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("limit", Math.max(1, limit)));
        return jdbc.query(ACTIVE, parameters, HOLD);
    }

    /** The active holds that cover a purge of {@code projectId} (or the whole tenant when null). */
    public List<HoldRow> activeHoldsCovering(TenantScope scope, UUID projectId, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("limit", Math.max(1, limit)));
        parameters.addValue("coversProject", projectId, java.sql.Types.OTHER);
        return jdbc.query(ACTIVE_COVERING, parameters, HOLD);
    }

    /** One hold, read through the tenant scope. Empty when it belongs to another tenant. */
    public java.util.Optional<HoldRow> find(TenantScope scope, UUID holdId) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of("id", holdId));
        return jdbc.query(FIND_HOLD, parameters, HOLD).stream().findFirst();
    }

    /** The history of one hold: "was this tenant ever held", which the mutable row cannot answer. */
    public List<HoldEventRow> history(TenantScope scope, UUID holdId, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("holdId", holdId, "limit", Math.max(1, limit)));
        return jdbc.query(HISTORY, parameters, (rs, rowNum) -> new HoldEventRow(
            rs.getObject("id", UUID.class),
            rs.getObject("legalHoldId", UUID.class),
            rs.getString("eventType"),
            rs.getObject("actorUserId", UUID.class),
            rs.getString("actorPrincipal"),
            rs.getString("note"),
            rs.getObject("eventAt", LocalDateTime.class)));
    }

    /** Inserts a hold. @return its id */
    public UUID insertHold(TenantScope scope, UUID id, String scopeType, UUID projectId,
                           String scopeRef, String reason, UUID placedByUserId,
                           LocalDateTime placedAt, LocalDateTime expiresAt) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of(
            "id", id,
            "scopeType", scopeType,
            "reason", reason,
            "placedByUserId", placedByUserId,
            "now", placedAt));
        parameters.addValue("projectId", projectId);
        parameters.addValue("scopeRef", scopeRef);
        parameters.addValue("expiresAt", expiresAt);
        return jdbc.queryForObject(INSERT_HOLD, parameters, UUID.class);
    }

    /** Appends one history row. */
    public void insertEvent(TenantScope scope, UUID id, UUID holdId, UUID projectId, String eventType,
                            UUID actorUserId, String actorPrincipal, String note,
                            LocalDateTime at) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of(
            "id", id,
            "holdId", holdId,
            "eventType", eventType,
            "actorUserId", actorUserId,
            "actorPrincipal", actorPrincipal,
            "note", note,
            "now", at));
        parameters.addValue("projectId", projectId);
        jdbc.update(INSERT_EVENT, parameters);
    }

    /**
     * Releases a hold.
     *
     * @param expectedVersion the version the caller read; 0 rows means somebody else moved first
     * @return {@code true} when this call released it
     */
    public boolean release(TenantScope scope, UUID holdId, int expectedVersion, UUID releasedBy,
                           String releaseReason, LocalDateTime at) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of(
            "id", holdId,
            "expectedVersion", expectedVersion,
            "releasedBy", releasedBy,
            "releaseReason", releaseReason,
            "now", at));
        return jdbc.update(RELEASE_HOLD, parameters) == 1;
    }

    /** How many active holds cover the given scope, without materialising the rows. */
    public int activeCountCovering(TenantScope scope, UUID projectId) {
        Map<String, Object> result = coveringCounts(scope, projectId);
        Object value = result.get("covering");
        return value instanceof Number number ? number.intValue() : 0;
    }

    /** A small, stable summary for the overview and the purge gate. */
    public Map<String, Object> coveringCounts(TenantScope scope, UUID projectId) {
        String sql = """
            select count(*) filter (where "scopeType" = 'TENANT') as tenant_wide,
                   count(*) filter (where "scopeType" = 'PROJECT') as project_wide,
                   count(*) filter (where "scopeType" in ('TABLE', 'RECORD')) as narrow,
                   count(*) as covering
              from legal_holds h
             where h."organizationId" = :__fe_tenant_organization_id
               and h."status" = 'ACTIVE'
               and ((cast(:coversProject as uuid) is null)
                    or h."scopeType" = 'TENANT'
                    or (h."scopeType" = 'PROJECT' and h."projectId" = cast(:coversProject as uuid)))
            """;
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of());
        // The ::uuid cast is PostgreSQL's own explicit-cast operator, which — unlike
        // cast(? as uuid) — tells the driver the parameter's type so it binds a uuid rather than
        // leaving it unknown. Without it, the query cannot be prepared when the parameter is non-null.
        parameters.addValue("coversProject", projectId, java.sql.Types.OTHER);
        return new LinkedHashMap<>(jdbc.queryForMap(sql, parameters));
    }

    private static final org.springframework.jdbc.core.RowMapper<HoldRow> HOLD =
        (rs, rowNum) -> new HoldRow(
            rs.getObject("id", UUID.class),
            rs.getObject("organizationId", UUID.class),
            rs.getObject("projectId", UUID.class),
            rs.getString("scopeType"),
            rs.getString("scopeRef"),
            rs.getString("reason"),
            rs.getString("status"),
            rs.getObject("placedAt", LocalDateTime.class),
            rs.getObject("placedByUserId", UUID.class),
            rs.getObject("expiresAt", LocalDateTime.class),
            rs.getObject("releasedAt", LocalDateTime.class),
            rs.getObject("releasedByUserId", UUID.class),
            rs.getString("releaseReason"),
            rs.getObject("version", Integer.class),
            rs.getObject("createdAt", LocalDateTime.class));
}