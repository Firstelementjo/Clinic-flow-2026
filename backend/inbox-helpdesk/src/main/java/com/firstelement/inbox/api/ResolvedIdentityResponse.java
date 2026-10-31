package com.firstelement.inbox.api;

import java.util.List;
import java.util.UUID;

/**
 * {@code POST /api/v1/customers/resolve-identity} response.
 *
 * @param created        {@code true} when this call created the customer row
 * @param possibleMatches name-only candidates. Reported, never merged: a similar name is not
 *                        evidence that two records are the same person, and merging them would
 *                        put one customer's transcript under another.
 */
public record ResolvedIdentityResponse(UUID customerId,
                                       UUID identityId,
                                       boolean created,
                                       List<PossibleMatch> possibleMatches) {

    /** A name-only candidate for a human to confirm. */
    public record PossibleMatch(UUID customerId, String displayName, String reason) {
    }
}