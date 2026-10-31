package com.firstelement.crm.clinic;

import com.firstelement.crm.query.CrmQueryScope;
import com.firstelement.security.TenantContext;
import com.firstelement.security.error.PolicyDeniedException;
import com.firstelement.security.query.TenantQueryGuard;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * The tenant/project scope every clinic statement carries, and the one place a
 * client-supplied project id is allowed to become one.
 *
 * <h2>Why this is not just {@link CrmQueryScope}</h2>
 *
 * <p>{@link CrmQueryScope} is the right primitive and every clinic query uses it. What
 * this class adds is the rule that {@code doctors}, {@code procedures} and
 * {@code appointments} are read through a scope that was <b>derived</b>, never
 * defaulted.
 *
 * <h2>The failure this exists to make impossible</h2>
 *
 * <p>On 2026-10-08 the legacy runtime scoped a project only from
 * {@code request.params.projectId}. A route naming a project in a query string or a
 * request body received an <em>empty</em> scope, and the handler became the last
 * remaining layer — which reached every tenant's rows for 40+ sites, including one
 * cross-tenant <em>write</em>. The lesson is one sentence, and it is the sentence on
 * {@code AGENTS.md}: <b>a scope taken from the client is a request, never
 * authorization.</b>
 *
 * <p>So the rule here is structural. There are exactly three ways to obtain a clinic
 * scope and none of them accepts an unchecked id:
 *
 * <ol>
 *   <li>{@link #forCaller} — no project named. The scope is every project the
 *       server resolved for this principal. A principal with no memberships gets
 *       {@code 1 = 0}, never "the whole tenant".</li>
 *   <li>{@link #forRequestedProject} — a project named in the query. It is compared
 *       against the server-resolved list first and refused with a non-disclosing
 *       {@link PolicyDeniedException} when it is absent.</li>
 *   <li>{@link #forRow} — a row already read. Its own {@code organizationId} and
 *       {@code projectId} are re-checked, so a read-then-check path still fails
 *       closed.</li>
 * </ol>
 *
 * <p>There is no constructor and no overload that takes a {@code projectId}
 * <em>without</em> the membership comparison. That is the whole design: a caller
 * cannot assemble an unsafe scope even by accident, because the way to ask is the
 * way that checks.
 *
 * <h2>Why a denial is a 404 and not a 403</h2>
 *
 * <p>{@link PolicyDeniedException} is rendered by the security advice as
 * {@code 404 {code: NOT_FOUND, message: 'Resource not found'}} — byte-identical to a
 * nonexistent row. This surface carries patient names and phone numbers, so
 * distinguishing "you may not" from "not yours" from "does not exist" is an
 * existence oracle. Phase 33 closed the legacy half of that; this is the Spring half.
 */
public final class ClinicScope {

    private final CrmQueryScope queryScope;

    private ClinicScope(CrmQueryScope queryScope) {
        this.queryScope = queryScope;
    }

    /**
     * The scope for a caller that named no project.
     *
     * @param caller the server-resolved context; never {@code null}
     * @throws IllegalStateException when no context is bound, rather than silently
     *         reading unscoped — an unbound scope is a programming fault, and the
     *         honest response to it is to stop
     */
    public static ClinicScope forCaller(TenantContext caller) {
        requireCaller(caller);
        return new ClinicScope(CrmQueryScope.accessibleProjects(caller));
    }

    /**
     * The scope for a caller naming a project in the query string.
     *
     * <p>The guard runs <em>before</em> any SQL is assembled. A refusal here therefore
     * never reaches the database at all, so a probe cannot distinguish "denied" from
     * "empty" by timing or by row count.
     *
     * @throws PolicyDeniedException rendered as a non-disclosing 404
     */
    public static ClinicScope forRequestedProject(TenantQueryGuard guard, TenantContext caller,
                                                  UUID requestedProjectId) {
        requireCaller(caller);
        guard.scope(caller, requestedProjectId);
        return new ClinicScope(CrmQueryScope.forProject(guard, caller, requestedProjectId));
    }

    /**
     * The scope for a row already read, tenant- and project-checked.
     *
     * <p>Used by every {@code /{id}} route. The lookup that produced the row is already
     * tenant-scoped, so this is the second of the two independent checks; it exists so
     * that a row read through a join or a parent lookup still fails closed rather than
     * being returned because some earlier layer assumed the scope.
     */
    public static ClinicScope forRow(TenantContext caller, UUID rowOrganizationId,
                                     UUID rowProjectId) {
        requireCaller(caller);
        if (rowOrganizationId == null || !rowOrganizationId.equals(caller.organizationId())) {
            throw PolicyDeniedException.tenantOutOfScope(
                "organization-out-of-scope", caller.organizationId(), rowProjectId);
        }
        if (rowProjectId != null && !caller.hasProject(rowProjectId)) {
            throw PolicyDeniedException.projectOutOfScope(
                "project-out-of-scope", caller.organizationId(), rowProjectId);
        }
        return new ClinicScope(CrmQueryScope.accessibleProjects(caller));
    }

    /** Runs {@code work} under this scope, returning whatever it returns. */
    public <T> T read(Supplier<T> work) {
        return work.get();
    }

    /** The underlying CRM scope, which is what builds every predicate and binds every value. */
    public CrmQueryScope queryScope() {
        return queryScope;
    }

    /**
     * The tenant predicate for one alias, e.g. {@code d."organizationId" = ...}.
     *
     * <p><b>This method is the red-to-green anchor for the whole module.</b> Stripping the
     * {@code "organizationId" = ...} clause out of what it returns is a two-line change that
     * reproduces the 2026-10-08 breach exactly — a scope assembled from something other than the
     * authenticated context — and it was measured: every tenant-isolation assertion in
     * {@code ClinicReadModelHttpIntegrationTest} goes red with it, on every route, including the
     * ones that take a project in the query string. Restored, they pass.
     *
     * <p>Two things are worth recording from that experiment. First, the earlier attempt —
     * removing the {@code guard.scope(...)} call from {@link #forRequestedProject} — did
     * <b>not</b> go red, because {@code CrmQueryScope.forProject} delegates to the same guard.
     * The check is enforced twice on that path, so removing one copy proves nothing. That is the
     * right shape, and it is also why the anchor is here, at the single point every statement's
     * predicate comes from.
     *
     * <p>Second, the breakage surfaced as {@code 500} on most routes rather than as a leak,
     * because the bound tenant parameter then had no placeholder to bind to. A refusal is the
     * honest outcome and it is what the assertions check; the 500 is a side effect of the
     * experiment's bluntness, not a property of the system.
     */
    public String predicate(String alias) {
        return queryScope.predicate(alias);
    }

    /** Caller parameters with the reserved tenant parameters written last. */
    public MapSqlParameterSource bind(java.util.Map<String, ?> callerParameters) {
        return queryScope.bind(callerParameters);
    }

    /** Caller parameters with no additional filters. */
    public MapSqlParameterSource bind() {
        return queryScope.bind();
    }

    /** Whether this scope is pinned to exactly one project. */
    public boolean isProjectScoped() {
        return queryScope.isProjectScoped();
    }

    /** The single project this scope is pinned to, or {@code null} when it spans several. */
    public UUID projectId() {
        return queryScope.projectId();
    }

    /** Every project the caller may reach, sorted, for a response that names its own scope. */
    public java.util.List<UUID> accessibleProjectIds() {
        return queryScope.accessibleProjectIds();
    }

    private static void requireCaller(TenantContext caller) {
        if (caller == null) {
            throw new IllegalStateException(
                "No TenantContext is bound; refusing to read clinic rows unscoped. An unbound "
                    + "scope is a wiring fault, and the honest response to it is to stop rather "
                    + "than to return every tenant's schedule.");
        }
    }

    /**
     * The keyset boundary a schedule page ended on.
     *
     * <p>Both halves are carried, never just the id. A single-column cursor needs a
     * {@code subquery} to recover its sort key, which re-reads a row the caller may no longer
     * be able to see and turns a pagination step into a second authorization question. The
     * pair is resolved in one tenant-scoped read and reused as a row comparison.
     *
     * @param appointmentDate the last row's {@code "appointmentDate"}
     * @param id              the last row's {@code "id"}, the tie-break
     */
    public record CursorBoundary(java.time.LocalDateTime appointmentDate, UUID id) {
    }
}