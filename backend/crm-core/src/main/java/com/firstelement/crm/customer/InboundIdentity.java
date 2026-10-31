package com.firstelement.crm.customer;

import com.firstelement.crm.domain.ChannelType;
import java.util.Map;
import java.util.UUID;

/**
 * The input to identity resolution: one external handle, plus whatever the platform vouched for.
 *
 * <h2>Verified signals only</h2>
 *
 * <p>{@code phone} and {@code email} are documented in the legacy source as "Verified signals
 * only — a phone the platform vouches for, not typed text". They are the only signals strong
 * enough to link a new handle to an existing customer without asking a human. A display name is
 * never that.
 */
public record InboundIdentity(UUID organizationId,
                               UUID projectId,
                               ChannelType platform,
                               String externalId,
                               String displayName,
                               String phone,
                               String email,
                               String locale,
                               Map<String, Object> rawProfile) {

    public InboundIdentity {
        if (organizationId == null || projectId == null) {
            throw new IllegalArgumentException(
                "An inbound identity carries the tenant and project it was resolved for");
        }
        if (platform == null || externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException("An inbound identity needs a platform and an external id");
        }
        rawProfile = rawProfile == null ? Map.of() : Map.copyOf(rawProfile);
    }

    /** A handle with nothing but the external id. */
    public static InboundIdentity handleOnly(UUID organizationId, UUID projectId,
                                             ChannelType platform, String externalId) {
        return new InboundIdentity(organizationId, projectId, platform, externalId, null, null,
            null, null, null);
    }
}