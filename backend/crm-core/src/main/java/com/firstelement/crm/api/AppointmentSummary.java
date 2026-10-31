package com.firstelement.crm.api;

import java.util.UUID;

/**
 * An appointment attached to a customer, as the legacy customer list and customer 360
 * rendered it.
 *
 * <p>Read-only here. {@code appointments} rows belong to the clinic module; this projection
 * exists because the legacy customer response contained it.
 */
public record AppointmentSummary(
    UUID id,
    String title,
    String appointmentDate,
    Integer durationMinutes,
    String status,
    String service,
    String doctorName,
    String notes,
    String ticketNumber,
    String ticketDate,
    String createdAt
) {

    /**
     * The narrower shape the customer <em>list</em> used. The legacy list projection omitted
     * {@code notes}, {@code ticketNumber}, {@code ticketDate} and {@code createdAt}, and a
     * compatibility surface that adds fields would break a strict client.
     */
    public AppointmentSummary asListItem() {
        return new AppointmentSummary(id, title, appointmentDate, durationMinutes, status,
            service, doctorName, null, null, null, null);
    }
}