package com.firstelement.exports.catalog;

import com.firstelement.security.TenantContext;
import com.firstelement.security.error.PolicyDeniedException;
import com.firstelement.security.permission.AuthorizationService;
import com.firstelement.security.permission.Permission;
import org.springframework.stereotype.Component;

/**
 * The one place {@link Permission#REPORT_MANAGE} is enforced for authoring, and the reason it is a
 * component rather than an annotation.
 *
 * <h2>Why not {@code @RequirePermission(REPORT_MANAGE)}</h2>
 *
 * <p>Because the annotation cannot express the status this surface owes a caller.
 * {@code @RequirePermission} is {@code @PreAuthorize}, and a failing expression raises
 * {@code AccessDeniedException}, which {@code SecurityExceptionAdvice} renders as {@code 403 ACCESS_DENIED}.
 * A cross-tenant id on the same route raises {@code PolicyDeniedException}, which the same advice renders as
 * {@code 404 NOT_FOUND} — the answer a nonexistent id produces, because
 * {@code report_definitions_organizationId_key_key} is per-tenant and a global uniqueness index would turn
 * "that key exists" into a cross-tenant existence oracle.
 *
 * <p>So an authoring route annotated with the permission would answer {@code 403} for "you may not author"
 * and {@code 404} for "that is not yours", and the difference between the two answers would be a
 * discriminator on the route. The caller learns nothing about another tenant's rows from either — both are
 * decided before a statement is built — but the surface would be answering with two vocabularies for one
 * question, and an operator reading a 403 would be told "you lack report:manage" about a request that was
 * really "there is nothing there".
 *
 * <p>The refusal here is therefore {@code PolicyDeniedException}, deliberately, and every authoring entry
 * point goes through this guard first. One condition, one status, one body — identical to the answer for an
 * id that does not exist.
 *
 * <h2>What this gives up, stated rather than hidden</h2>
 *
 * <p>{@code @RequirePermission} is this repository's declarative statement of a route's permission
 * ({@code FE-WEB-003}) and it is present on every other handler in {@code ExportController}. Authoring
 * handlers deliberately do not carry it, because carrying it would make the answer 403. The cost is that the
 * declaration is no longer visible on the method signature; the compensation is threefold and all of it is
 * asserted:
 *
 * <ol>
 *   <li>Every authoring route delegates to a service method whose <b>first statement</b> is
 *       {@link #requireAuthoring}. There is no authoring write reachable without it.</li>
 *   <li>The check runs on a {@code TenantContext} argument, not on the ambient authentication, so it is
 *       enforced identically for an HTTP request, a console call and a job invocation — which is what the
 *       annotation's own javadoc says is the load-bearing placement.</li>
 *   <li>A reflection test asserts that no authoring handler and no authoring service method carries
 *       {@code @RequirePermission(WORKFLOW_MANAGE)}, so the Phase-30 conflation cannot return silently.
 *       See {@code ReportAuthoringIntegrationTest}.</li>
 * </ol>
 *
 * <h2>Why a resolved human caller is required</h2>
 *
 * <p>{@code report_versions."createdByUserId"} and {@code document_template_versions."createdByUserId"} are
 * NOT NULL and carry a composite tenant foreign key to {@code users(id, organizationId)}. A report body with
 * no attributable author is not evidence, and {@code report_definitions} has no author column at all — so a
 * machine principal allowed to create a definition would produce a definition nobody authored and then fail
 * on its first version. Refusing the machine principal here, at the same guard and with the same answer, is
 * why {@link Permission#REPORT_MANAGE} is granted to {@code ADMIN} only.
 */
@Component
public class ReportAuthoringGuard {

    /** The reason recorded on the security event. Never rendered in a response body. */
    static final String REASON = "report-authoring-not-permitted";

    private final AuthorizationService authorization;

    public ReportAuthoringGuard(AuthorizationService authorization) {
        this.authorization = authorization;
    }

    /**
     * Requires a resolved caller who holds {@link Permission#REPORT_MANAGE}.
     *
     * @throws PolicyDeniedException when there is no resolved caller, when the caller holds no
     *         {@code REPORT_MANAGE}, or when the caller is a machine principal — rendered as a
     *         non-disclosing {@code 404}, identical to the answer for an id that does not exist
     */
    public void requireAuthoring(TenantContext caller) {
        if (caller == null) {
            // Refusing rather than proceeding is the whole point: an unscoped write is the failure mode
            // this module's tenant predicate exists to prevent, and it is unreachable only because every
            // caller in this repository resolves a context first.
            throw denied(null, null);
        }
        if (caller.userId() == null || !caller.isUser()) {
            throw denied(caller.organizationId(), null);
        }
        if (!authorization.allows(caller, Permission.REPORT_MANAGE)) {
            throw denied(caller.organizationId(), null);
        }
    }

    /** Whether a caller may author, for a surface that wants to state it rather than throw. */
    public boolean mayAuthor(TenantContext caller) {
        return caller != null && caller.isUser() && caller.userId() != null
            && authorization.allows(caller, Permission.REPORT_MANAGE);
    }

    private static PolicyDeniedException denied(java.util.UUID organizationId, java.util.UUID projectId) {
        return new PolicyDeniedException(REASON, organizationId, projectId, Permission.REPORT_MANAGE);
    }
}