package com.firstelement.security.query;

import com.firstelement.security.TenantContext;
import com.firstelement.security.error.PolicyDeniedException;
import com.firstelement.security.event.SecurityEvent;
import com.firstelement.security.event.SecurityEventPublisher;
import com.firstelement.security.event.SecurityEventType;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The single place a client-supplied tenant or project id becomes a
 * {@link TenantScope}, or a denial.
 *
 * <h2>The rule this class exists to enforce</h2>
 *
 * <p>"A client-provided project or tenant ID is a request, never authorization"
 * (AGENTS.md). {@code TenantQueryGuard.scope(ctx, requestedProjectId)} compares the
 * request against the principal's server-resolved {@code projectIds} and refuses before
 * a query is built. There is no overload that accepts a project id without checking it.
 *
 * <h2>Ordering is the security property</h2>
 *
 * <p>The check must happen <em>before</em> the SQL is assembled, not as a post-filter on
 * rows. A post-filter that returns an empty list for an unauthorized project still runs
 * the query, still lets timing and row counts leak, and still trusts the join. This
 * guard pushes the filter into the {@code WHERE} clause instead — see {@link ScopedWhere}.
 *
 * <h2>Non-disclosing denial</h2>
 *
 * <p>A denial is {@link PolicyDeniedException}, which the web layer renders as
 * {@code 404 NOT_FOUND} with the same body a nonexistent id produces. The reason is
 * recorded on the security stream only.
 */
@Component
public class TenantQueryGuard {

    private static final Logger log = LoggerFactory.getLogger(TenantQueryGuard.class);

    private final SecurityEventPublisher events;

    public TenantQueryGuard(SecurityEventPublisher events) {
        this.events = events == null ? event -> { } : events;
    }

    /**
     * Validates a requested project against the principal's server-resolved scope.
     *
     * @param requestedProjectId the id from the request, or {@code null} for
     *                           "everything I may see in my tenant"
     * @throws PolicyDeniedException when the requested project is outside the scope
     */
    public TenantScope scope(TenantContext context, UUID requestedProjectId) {
        if (context == null) {
            throw new IllegalStateException(
                "No TenantContext is bound; refusing to build a tenant-scoped query unscoped.");
        }
        if (requestedProjectId == null) {
            return new TenantScope(context.organizationId(), null);
        }
        if (!context.hasProject(requestedProjectId)) {
            publish(context, requestedProjectId, "project-out-of-scope");
            throw PolicyDeniedException.projectOutOfScope(
                "project-out-of-scope", context.organizationId(), requestedProjectId);
        }
        return new TenantScope(context.organizationId(), requestedProjectId);
    }

    /**
     * A tenant-wide scope. Used by organization-level reads and by aggregations that
     * must still exclude other tenants.
     */
    public TenantScope tenantScope(TenantContext context) {
        if (context == null) {
            throw new IllegalStateException("No TenantContext is bound; refusing to build an unscoped query.");
        }
        return new TenantScope(context.organizationId(), null);
    }

    /**
     * Validates a tenant id supplied by an integration (a verified connector payload, a
     * signed webhook claim). A mismatch is a denial, never a scope switch.
     */
    public TenantScope claimedTenantScope(TenantContext context, UUID claimedOrganizationId) {
        if (context == null) {
            throw new IllegalStateException("No TenantContext is bound; refusing to trust a claimed tenant.");
        }
        if (claimedOrganizationId != null
            && !claimedOrganizationId.equals(context.organizationId())) {
            publish(context, claimedOrganizationId, "organization-out-of-scope");
            throw PolicyDeniedException.tenantOutOfScope(
                "organization-out-of-scope", context.organizationId(), claimedOrganizationId);
        }
        return new TenantScope(context.organizationId(), null);
    }

    /**
     * Confirms a row that has already been read belongs to the caller's scope.
     *
     * <p>For a row keyed only by an id, this is the read-then-check path. It is
     * <b>not</b> a substitute for {@link #scope} in the {@code WHERE} clause: it exists
     * for the cases where the row's scope can only be known after reading it (a parent
     * join, a job payload), and it exists so that a read-then-check path still fails
     * closed rather than returning the row.
     *
     * @param rowProjectId the row's project, or {@code null} for a table that is scoped
     *                     by tenant only
     */
    public TenantScope assertRowScope(TenantContext context,
                                      UUID rowOrganizationId,
                                      UUID rowProjectId) {
        if (context == null) {
            throw new IllegalStateException("No TenantContext is bound; refusing to authorize a row unscoped.");
        }
        if (rowOrganizationId == null || !rowOrganizationId.equals(context.organizationId())) {
            publish(context, rowProjectId, "organization-out-of-scope");
            throw PolicyDeniedException.tenantOutOfScope(
                "organization-out-of-scope", context.organizationId(), rowOrganizationId);
        }
        if (rowProjectId != null && !context.hasProject(rowProjectId)) {
            publish(context, rowProjectId, "project-out-of-scope");
            throw PolicyDeniedException.projectOutOfScope(
                "project-out-of-scope", context.organizationId(), rowProjectId);
        }
        return new TenantScope(context.organizationId(), rowProjectId);
    }

    private void publish(TenantContext context, UUID requested, String reason) {
        try {
            events.publish(SecurityEvent.denial(SecurityEventType.AUTHORIZATION_DENIED, context,
                "TenantScope", reason, Map.of("scope", reason, "guard", "tenant-query-guard")));
        } catch (RuntimeException e) {
            log.error("Failed to publish AUTHORIZATION_DENIED reason={}", reason, e);
        }
    }
}