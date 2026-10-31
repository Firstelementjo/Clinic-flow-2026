package com.firstelement.worker.runtime;

import com.firstelement.security.Capability;
import com.firstelement.security.PrincipalType;
import com.firstelement.security.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The tenant list a fan-out worker iterates.
 *
 * <h2>Why this is a cross-tenant read, and why it is safe</h2>
 *
 * <p>Per-tenant claiming (the canonical claim) requires knowing which tenants exist. That read
 * returns <b>ids of active organizations and nothing else</b> — no name, no slug, no row content.
 * It is the same class of statement as the tenant-less scheduling index
 * {@code outbox_events_dispatch_sweep_idx}: a scheduler must be able to enumerate work, and it
 * carries nothing a user could read.
 *
 * <p>Every actual claim and every actual handler run is then scoped to one tenant, through
 * {@code TenantScopedExecutor}, with a {@link TenantContext} built here and nowhere else. A tenant
 * id read here is the <em>input</em> to a scope check, never the authorization.
 *
 * <h2>Machine principals, not a privileged user</h2>
 *
 * <p>{@link #workerContext} produces a {@link PrincipalType#WORKER} principal carrying an explicit
 * capability set. It has no role, no user id and no session, so "run the job as the tenant
 * administrator" has no representation to express it in — which is the structural property
 * {@code Capability.mayBeHeldBy} exists to guarantee.
 */
@Repository
public class TenantDirectory {

    private static final Logger log = LoggerFactory.getLogger(TenantDirectory.class);

    /**
     * The capabilities a delivery worker holds.
     *
     * <p>{@code MESSAGING_SEND} grants {@code CONVERSATION_REPLY} + {@code CHANNEL_READ} and
     * {@code WEBHOOK_INGEST} grants {@code CONVERSATION_READ} + {@code CHANNEL_READ}, so a worker
     * can run the inbound pipeline and hand a persisted message to a connector — and cannot read
     * customers, notes, exports or finance.
     */
    public static final List<Capability> WORKER_CAPABILITIES = List.of(
        Capability.WEBHOOK_INGEST, Capability.MESSAGING_SEND);

    private static final String ACTIVE_ORGANIZATIONS = """
        select "id"
          from organizations
         where "isActive" = true
         order by "id"
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public TenantDirectory(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The active tenants, in a deterministic order. Ids only. */
    public List<UUID> activeOrganizationIds() {
        return jdbc.query(ACTIVE_ORGANIZATIONS, new MapSqlParameterSource(),
            (rs, rowNum) -> rs.getObject("id", UUID.class));
    }

    /** One tenant's scope for a worker claim. */
    public Optional<TenantContext> workerContext(UUID organizationId, String workerId) {
        if (organizationId == null) {
            return Optional.empty();
        }
        return Optional.of(TenantContext.forMachine(PrincipalType.WORKER, organizationId,
            workerId, List.of(), WORKER_CAPABILITIES));
    }

    /**
     * One tenant's scope for a claimed job, narrowed to the project the durable row names.
     *
     * <h2>Why the project must come from the row</h2>
     *
     * <p>A worker principal with an empty project scope satisfies the tenant rule and then fails
     * every project check: {@code TenantQueryGuard.scope(context, projectId)} refuses anything the
     * principal's server-resolved {@code projectIds} does not contain, and an outbound delivery
     * reads its message under {@code CrmQueryScope.forProject(guard, context, message.projectId())}.
     * So the scope has to be granted, per claim, from {@code outbox_events."projectId"} &mdash; a
     * durable server-side value that the domain transaction wrote and that the claim re-read under
     * tenant scope.
     *
     * <p>This is granting, not trusting a request: the id arrives from a row this worker leased for
     * this tenant, and the row is re-read under {@code "organizationId"} before the context is
     * built. A {@code null} project means organisation-wide work, and the resulting context is the
     * empty project scope &mdash; which is the correct scope for it.
     *
     * @param projectId the claimed row's project, or {@code null} for organisation-wide work
     */
    public Optional<TenantContext> workerContext(UUID organizationId, UUID projectId,
                                                 String workerId) {
        return workerContext(organizationId, workerId)
            .map(context -> context.withProjects(projectId == null ? List.of() : List.of(projectId)));
    }

    /**
     * How many tenants this worker will be polling.
     *
     * <p>Logged once at startup: a worker that suddenly sees zero tenants is either a fresh install
     * or a broken {@code isActive} query, and both deserve a log line rather than a mystery.
     */
    public int logPopulation() {
        int size = activeOrganizationIds().size();
        log.info("worker tenant directory holds {} active organization(s)", size);
        return size;
    }
}