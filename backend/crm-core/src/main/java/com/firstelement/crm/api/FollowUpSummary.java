package com.firstelement.crm.api;

import java.util.UUID;

/**
 * A lead/follow-up attached to a customer, as the legacy customer list rendered it.
 *
 * <p>Read-only here. {@code Lead} rows are owned by the CRM-middle workspace module; this
 * projection exists because the legacy {@code GET /customers} response contained it and the
 * compatibility surface has to keep containing it.
 */
public record FollowUpSummary(
    UUID id,
    String name,
    String status,
    String priority,
    String ticketNumber,
    String ticketDate,
    String followUpDate,
    String createdAt
) {
}