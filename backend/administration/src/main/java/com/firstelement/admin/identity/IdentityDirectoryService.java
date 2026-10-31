package com.firstelement.admin.identity;

import com.firstelement.admin.AdministrationModule;
import com.firstelement.identity.permission.EffectivePermissionService;
import com.firstelement.security.TenantContext;
import com.firstelement.security.error.PolicyDeniedException;
import com.firstelement.security.query.TenantQueryGuard;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The identity read model an operator uses to answer "who can do what here".
 *
 * <h2>Effective permissions are delegated, never re-derived</h2>
 *
 * <p>{@link EffectivePermissionService} in {@code :backend:identity-access} already answers "what
 * can this person actually do", including the project scope that decides reach. Phase 7 must not
 * grow a second definition: two answers to the same question is how an operator ends up trusting
 * the one that is wrong. This service delegates and adds only the directory context around it.
 *
 * <h2>The cross-tenant answer is the non-disclosing one</h2>
 *
 * <p>{@link #inspect} never resolves a user outside the caller's organization. The delegated service
 * raises the same {@code PolicyDeniedException} for a foreign user and for a nonexistent one, which
 * the web layer renders as {@code 404} — so probing for user ids across tenants yields nothing and
 * records the attempt on the security stream.
 */
@Service
@Transactional(readOnly = true)
public class IdentityDirectoryService {

    private final IdentityDirectoryRepository directory;
    private final EffectivePermissionService permissions;
    private final TenantQueryGuard guard;

    public IdentityDirectoryService(IdentityDirectoryRepository directory,
                                    EffectivePermissionService permissions,
                                    TenantQueryGuard guard) {
        this.directory = directory;
        this.permissions = permissions;
        this.guard = guard;
    }

    /** The whole directory for one tenant. */
    public DirectoryView directory(TenantContext caller) {
        var scope = guard.tenantScope(caller);
        return new DirectoryView(
            directory.summary(scope),
            directory.users(scope, AdministrationModule.Limits.DIRECTORY_PAGE),
            directory.memberships(scope, AdministrationModule.Limits.DIRECTORY_PAGE),
            IdentityDirectoryRepository.servicePrincipalVocabulary(),
            directory.observedMachinePrincipals(scope, AdministrationModule.Limits.DIRECTORY_PAGE),
            directory.credentialPresence(scope, AdministrationModule.Limits.DIRECTORY_PAGE));
    }

    /** Sessions, optionally narrowed to one user of this tenant. */
    public List<IdentityDirectoryRepository.SessionRow> sessions(TenantContext caller, UUID userId) {
        var scope = guard.tenantScope(caller);
        if (userId != null && !containsUser(scope, userId)) {
            throw PolicyDeniedException.tenantOutOfScope("user-out-of-scope",
                caller.organizationId(), userId);
        }
        return directory.sessions(scope, userId, AdministrationModule.Limits.DIRECTORY_PAGE);
    }

    /**
     * One user's effective permissions, plus that user's own directory row.
     *
     * <p>The two halves answer the two questions an operator actually has: "what can they do" and
     * "is the account healthy" (active, live sessions, project count).
     */
    public InspectionView inspect(TenantContext caller, UUID userId) {
        // Delegated first, so a foreign user id is refused before anything else is read.
        EffectivePermissionService.PermissionInspection inspection = permissions.inspect(caller, userId);
        IdentityDirectoryRepository.UserRow row = directory.users(guard.tenantScope(caller),
            AdministrationModule.Limits.DIRECTORY_PAGE).stream()
            .filter(user -> user.id().equals(userId))
            .findFirst()
            .orElseThrow(() -> PolicyDeniedException.tenantOutOfScope("user-out-of-scope",
                caller.organizationId(), userId));
        return new InspectionView(row, inspection);
    }

    /** The caller's own effective permissions, for the SPA to hide what would be refused. */
    public EffectivePermissionService.PermissionInspection inspectSelf(TenantContext caller) {
        return permissions.inspectSelf(caller);
    }

    /**
     * The directory summary plus its rows.
     *
     * @param servicePrincipalVocabulary the global capability grammar, which is code rather than data
     * @param observedMachinePrincipals  this tenant's own machine principals, from the audit streams
     */
    public record DirectoryView(IdentityDirectoryRepository.DirectorySummary summary,
                                List<IdentityDirectoryRepository.UserRow> users,
                                List<IdentityDirectoryRepository.MembershipRow> memberships,
                                List<IdentityDirectoryRepository.ServicePrincipalRow>
                                    servicePrincipalVocabulary,
                                List<IdentityDirectoryRepository.ObservedPrincipal>
                                    observedMachinePrincipals,
                                List<IdentityDirectoryRepository.CredentialPresence>
                                    credentialPresence) {
    }

    /** One user's directory row beside their effective permissions. */
    public record InspectionView(IdentityDirectoryRepository.UserRow user,
                                 EffectivePermissionService.PermissionInspection permissions) {
    }

    private boolean containsUser(com.firstelement.security.query.TenantScope scope, UUID userId) {
        return directory.users(scope, AdministrationModule.Limits.DIRECTORY_PAGE).stream()
            .anyMatch(user -> user.id().equals(userId));
    }
}