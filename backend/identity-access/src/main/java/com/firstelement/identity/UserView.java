package com.firstelement.identity;

import com.firstelement.security.TenantContext;
import com.firstelement.security.UserRole;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The only {@code users} projection that may be serialized.
 *
 * <p>Kept as a distinct type rather than reusing {@link UserRecord} so that adding a
 * column to the internal record cannot silently make it serializable. The email address
 * is present because the Admin user list needs it; it is not present in the session
 * principal, in audit metadata, or in any event stream.
 *
 * @param projectIds the server-resolved project scope for this user, recomputed per
 *                   request and never cached in a token
 */
public record UserView(
        UUID id,
        UUID organizationId,
        String email,
        String fullName,
        UserRole role,
        String locale,
        boolean active,
        LocalDateTime lastLoginAt,
        java.util.List<UUID> projectIds
) {

    public static UserView from(TenantContext context, UserRecord user) {
        return new UserView(user.id(), user.organizationId(), user.email(), user.fullName(),
            user.role(), user.locale(), user.active(), user.lastLoginAt(),
            java.util.List.copyOf(context == null ? java.util.Set.of() : context.projectIds()));
    }
}