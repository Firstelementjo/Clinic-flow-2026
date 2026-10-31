package com.firstelement.crm.api;

import java.util.UUID;

/**
 * One channel handle a customer writes from. The legacy projection, unchanged.
 *
 * <p>{@code rawProfile} is deliberately absent: it holds whatever the channel provider sent,
 * which is unbounded, unvalidated, and not part of the legacy response.
 */
public record CustomerIdentityView(
    UUID id,
    String platform,
    String externalId,
    String displayName,
    boolean isVerified,
    String createdAt
) {
}