package com.firstelement.crm.domain;

/**
 * The conversation state machine's status axis. Ported verbatim from the verified
 * {@code "ConversationStatus"} enum.
 *
 * <h2>The transitions the legacy runtime performs</h2>
 *
 * <p>These are the <em>only</em> status writes the CRM performs, and they are preserved
 * exactly (see {@code ConversationStateMachine}):
 *
 * <ul>
 *   <li>{@code OPEN|PENDING -> OPEN} on an employee reply when the thread was
 *       {@code RESOLVED} or {@code CLOSED} (a reply reopens a finished thread).</li>
 *   <li>{@code RESOLVED -> RESOLVED} is not a transition; only the first
 *       {@code -> RESOLVED} stamps {@code resolvedAt}.</li>
 *   <li>{@code -> CLOSED} stamps {@code closedAt}; {@code CLOSED -> OPEN} clears
 *       {@code resolvedAt} (the legacy case-reopen action).</li>
 * </ul>
 */
public enum ConversationStatus {
    OPEN,
    PENDING,
    RESOLVED,
    CLOSED;

    public static ConversationStatus fromValue(String value) {
        for (ConversationStatus status : values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown conversation status: " + value);
    }

    /** OPEN and PENDING are the states routing and workload count as "active". */
    public boolean isActive() {
        return this == OPEN || this == PENDING;
    }
}