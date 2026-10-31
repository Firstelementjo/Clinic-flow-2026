package com.firstelement.crm.clinic;

import java.time.LocalDateTime;
import java.util.List;
/**
 * One page of the schedule.
 *
 * <p>The window is a component, and that is the point: a schedule page whose bounds a reader
 * cannot name cannot be reconciled against the metrics derived from it, and the metrics publish
 * theirs. Both publishing the same shape is what lets the drill-through path actually land on
 * the rows the numbers came from.
 *
 * @param nextCursor the id of the last row, or {@code null} on the last page
 * @param from       the inclusive lower bound the query bound — echoed, never re-derived
 * @param to         the inclusive upper bound the query bound
 */
public record ClinicAppointmentPage(List<ClinicModels.Appointment> items,
                                    int count,
                                    String nextCursor,
                                    LocalDateTime from,
                                    LocalDateTime to) {

    public ClinicAppointmentPage {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** Whether another page exists, so a client does not infer it from a null the serializer dropped. */
    public boolean hasNextPage() {
        return nextCursor != null && !nextCursor.isBlank();
    }
}