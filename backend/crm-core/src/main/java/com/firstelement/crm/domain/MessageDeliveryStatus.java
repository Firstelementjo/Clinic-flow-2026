package com.firstelement.crm.domain;

/**
 * Outbound delivery state. Ported verbatim from the verified
 * {@code "MessageDeliveryStatus"} enum.
 *
 * <p>An employee reply is persisted as {@code PENDING} and moved to {@code SENT} or
 * {@code FAILED} by the dispatcher (Phase 5). Persist-first is a Phase-4 invariant, not a
 * Phase-5 one: the message row exists before any provider is contacted.
 */
public enum MessageDeliveryStatus {
    PENDING,
    SENT,
    DELIVERED,
    READ,
    FAILED;

    public static MessageDeliveryStatus fromValue(String value) {
        for (MessageDeliveryStatus status : values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown delivery status: " + value);
    }
}