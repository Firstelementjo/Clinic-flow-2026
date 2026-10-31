package com.firstelement.identity;

import com.firstelement.security.UserRole;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A row of the legacy {@code users} table.
 *
 * <p>ADR-0002 mapping: the physical column names are quoted camelCase and the
 * timestamps are {@code timestamp(3) without time zone}, so {@link LocalDateTime} is the
 * correct Java type. {@link Instant} would silently apply a zone shift and would be
 * wrong.
 *
 * <p>{@code passwordHash} is present because login must compare against it, and it is
 * absent from every response type in this module. It is the canonical example of a
 * column that {@code FieldRedactionPolicy.NEVER_EXPOSED} covers unconditionally.
 */
public record UserRecord(
        UUID id,
        UUID organizationId,
        String email,
        String passwordHash,
        String fullName,
        UserRole role,
        String locale,
        boolean active,
        LocalDateTime lastLoginAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    /** The projection that may leave the process. Never includes {@code passwordHash}. */
    public UserView toView() {
        return new UserView(id, organizationId, email, fullName, role, locale, active,
            lastLoginAt, java.util.List.of());
    }
}