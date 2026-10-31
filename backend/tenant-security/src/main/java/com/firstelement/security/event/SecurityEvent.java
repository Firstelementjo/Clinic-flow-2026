package com.firstelement.security.event;

import com.firstelement.security.PrincipalType;
import com.firstelement.security.TenantContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One security/audit fact. Immutable.
 *
 * <h2>What must never go in here</h2>
 *
 * <p>{@link #metadata()} may contain ids, counts, boolean reasons, and policy
 * names. It must never contain a password, a password hash, a session token, a
 * CSRF token, an API key, a connector secret, an email address, or customer
 * content. The audit stream answers "who did what to which resource", not "what was
 * said" — the same rule the legacy {@code AuditService} documented. Callers that
 * build metadata from request input are responsible for this;
 * {@link com.firstelement.security.event.SecurityEventSanitizer} enforces the
 * deny-list mechanically.
 *
 * @param type           what happened
 * @param tenantContext  the acting scope; {@code null} for pre-authentication events
 *                       (a rejected cookie, a CSRF failure) and for a failed login on a
 *                       known account, where no session exists yet
 * @param organizationId the tenant the event belongs to when there is no acting scope.
 *                       Never client-supplied: it is read from the {@code users} row the
 *                       server already resolved. Ignored when {@code tenantContext} is
 *                       present, and must then match it.
 * @param actorUserId    set when the actor is a known {@code USER}
 * @param resourceType   the resource class the event concerns
 * @param resourceId     the resource id, when it is safe to record (an opaque uuid,
 *                       never a token)
 * @param reason         a stable, non-disclosing machine-readable reason
 * @param metadata       extra, non-sensitive detail
 * @param ipAddress      remote address, for the login events
 * @param correlationId  request/job correlation id
 */
public record SecurityEvent(
        SecurityEventType type,
        TenantContext tenantContext,
        UUID organizationId,
        UUID actorUserId,
        String resourceType,
        String resourceId,
        String reason,
        Map<String, Object> metadata,
        String ipAddress,
        String correlationId
) {

    public SecurityEvent {
        metadata = metadata == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
        if (tenantContext != null && organizationId != null
            && !tenantContext.organizationId().equals(organizationId)) {
            throw new IllegalArgumentException(
                "SecurityEvent.organizationId must match the tenant context when both are present");
        }
    }

    public static Builder builder(SecurityEventType type) {
        return new Builder(type);
    }

    /** Convenience for the common "denial" shape. */
    public static SecurityEvent denial(SecurityEventType type,
                                       TenantContext context,
                                       String resourceType,
                                       String reason,
                                       Map<String, Object> metadata) {
        return builder(type)
            .tenantContext(context)
            .resourceType(resourceType)
            .reason(reason)
            .metadata(metadata)
            .build();
    }

    public String actorType() {
        return tenantContext == null
            ? PrincipalType.USER.value()
            : tenantContext.principalType().value();
    }

    /** Organization scope of the event, if one is known. */
    public UUID organizationId() {
        return tenantContext == null ? organizationId : tenantContext.organizationId();
    }

    /** The builder keeps call sites readable and prevents partially built events. */
    public static final class Builder {
        private final SecurityEventType type;
        private TenantContext tenantContext;
        private UUID organizationId;
        private UUID actorUserId;
        private String resourceType = "Unknown";
        private String resourceId;
        private String reason;
        private Map<String, Object> metadata = Map.of();
        private String ipAddress;
        private String correlationId;

        private Builder(SecurityEventType type) {
            this.type = java.util.Objects.requireNonNull(type, "type");
        }

        public Builder tenantContext(TenantContext context) {
            this.tenantContext = context;
            if (context != null && context.userId() != null) {
                this.actorUserId = context.userId();
            }
            return this;
        }

        /**
         * The tenant of a pre-authentication event.
         *
         * <p>Only for a case where the server already resolved the tenant without a
         * session — a failed login on a known account, where the tenant came from the
         * {@code users} row. Never from request input.
         */
        public Builder organizationId(UUID organizationId) {
            this.organizationId = organizationId;
            return this;
        }

        public Builder actorUserId(UUID userId) {
            this.actorUserId = userId;
            return this;
        }

        public Builder resourceType(String resourceType) {
            this.resourceType = resourceType;
            return this;
        }

        public Builder resourceId(String resourceId) {
            this.resourceId = resourceId;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata == null ? Map.of() : metadata;
            return this;
        }

        public Builder ipAddress(String ipAddress) {
            this.ipAddress = ipAddress;
            return this;
        }

        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public SecurityEvent build() {
            return new SecurityEvent(type, tenantContext, organizationId, actorUserId,
                resourceType, resourceId, reason, metadata, ipAddress, correlationId);
        }
    }
}