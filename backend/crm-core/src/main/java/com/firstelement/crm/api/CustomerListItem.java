package com.firstelement.crm.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One row of {@code GET /api/v1/customers}, identical to the legacy
 * {@code CustomersService.list} item.
 *
 * @param conversationCount the number of conversations this customer has (not messages)
 * @param latestTicket      their most recent conversation by {@code createdAt desc}, or {@code null}
 * @param followUps         the five most recent leads, {@code createdAt desc}
 * @param appointments      the five most recent appointments, {@code appointmentDate desc}
 */
public record CustomerListItem(
    UUID id,
    UUID projectId,
    String projectName,
    String cxId,
    String displayName,
    String primaryEmail,
    String primaryPhone,
    String locale,
    List<String> tags,
    Map<String, Object> metadata,
    int conversationCount,
    String lastSeenAt,
    String createdAt,
    LatestTicket latestTicket,
    List<FollowUpSummary> followUps,
    List<AppointmentSummary> appointments
) {

    /** The legacy {@code latestTicket} projection. */
    public record LatestTicket(UUID id,
                               String ticketNumber,
                               String createdAt,
                               String status,
                               String subject) {
    }
}