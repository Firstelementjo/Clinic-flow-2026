package com.firstelement.crm.customer;

import java.util.List;
import java.util.UUID;

/**
 * The outcome of identity resolution.
 *
 * <h2>{@code possibleMatches} is reported, never acted on</h2>
 *
 * <p>A customer who "looks like" an existing one was matched on a weak signal. The legacy
 * resolver returned these and never merged them, and that is preserved exactly: acting on a
 * name match merges two different people into one transcript, which is far worse than showing
 * an operator a suggestion.
 *
 * @param customerId      the resolved customer, new or existing
 * @param identityId      the handle row
 * @param created         {@code true} when a customer row was created
 * @param possibleMatches name-only candidates for a human to confirm
 */
public record ResolvedCustomer(UUID customerId,
                               UUID identityId,
                               boolean created,
                               List<PossibleMatch> possibleMatches) {

    /** A candidate matched on {@code displayName} only. */
    public record PossibleMatch(UUID customerId, String displayName, String reason) {

        public static final String REASON_NAME = "NAME";
    }

    public ResolvedCustomer {
        possibleMatches = possibleMatches == null ? List.of() : List.copyOf(possibleMatches);
    }
}