package com.firstelement.crm.clinic.schedule;

import com.firstelement.crm.clinic.ClinicScope;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Answers "when can this dentist see a patient, for this service".
 *
 * <h2>DEC-001: the buffer is padding, not patient time</h2>
 *
 * <p>A 30-minute service with a 10-minute buffer books the patient for 30 minutes and occupies
 * the dentist for 40. The next patient starts at +40.
 *
 * <p>The rule is applied to <b>both</b> sides, and that is the whole point. Applying it to
 * candidates but not to existing bookings is how a chair gets double-booked across a procedure
 * that carries a buffer — and it is precisely where the two legacy implementations disagreed:
 * the AI path widened candidates by the buffer and left existing bookings at their bare
 * duration. {@link ScheduleRepository#busyOn} therefore LEFT JOINs {@code procedures} and
 * returns each booking already expanded.
 *
 * <h2>A known gap, stated rather than hidden</h2>
 *
 * <p>Slots are generated in <b>clinic-local wall-clock minutes</b>, which is the same convention
 * {@code appointments.appointmentDate} already uses (it is {@code timestamp without time zone}).
 *
 * <p>This does <b>not</b> yet handle a window that crosses a DST transition, and
 * {@code clinic_schedules.timezone} records the clinic's zone for exactly that work rather than
 * leaving it undefined. When the calendar tier lands it must adopt the DST-aware helpers the SLA
 * module already has ({@code BusinessTime}, {@code WorkingHours}), not add a second answer. A
 * silently wrong conversion would move real appointments by an hour twice a year, which is worse
 * than a documented gap.
 */
@Service
public class AvailabilityService {

    private final ScheduleRepository repository;

    public AvailabilityService(ScheduleRepository repository) {
        this.repository = repository;
    }

    /**
     * Bookable slots for one dentist on one date.
     *
     * @param serviceDurationMinutes how long the patient is actually with the dentist
     * @param bufferMinutes          DEC-001 padding, which occupies the schedule but is not
     *                               patient time
     * @param dentistOverrideMinutes DEC-001 nullable per-dentist override of the service
     *                               duration; null means use {@code serviceDurationMinutes}
     */
    public List<ScheduleModels.Slot> slotsFor(
            ClinicScope scope,
            UUID doctorId,
            LocalDate date,
            int serviceDurationMinutes,
            int bufferMinutes,
            Integer dentistOverrideMinutes) {

        if (serviceDurationMinutes <= 0) {
            throw new IllegalArgumentException(
                    "A service duration must be positive, got " + serviceDurationMinutes);
        }
        if (bufferMinutes < 0) {
            throw new IllegalArgumentException(
                    "A buffer cannot be negative, got " + bufferMinutes);
        }

        // Leave, holidays and training days remove the whole day. Checked BEFORE the windows so
        // an absent schedule and a day off are not confused for one another by a caller.
        if (repository.isAway(scope, doctorId, date)) {
            return List.of();
        }

        List<ScheduleModels.Window> windows = repository.windows(
                scope, doctorId, date.getDayOfWeek().getValue(), date);

        if (windows.isEmpty()) {
            return List.of();
        }

        int patientMinutes = dentistOverrideMinutes == null
                ? serviceDurationMinutes
                : dentistOverrideMinutes;
        if (patientMinutes <= 0) {
            throw new IllegalArgumentException(
                    "DEC-001 per-dentist override must be positive when present, got "
                            + dentistOverrideMinutes);
        }

        // Step is patient time PLUS padding, because the padding occupies the schedule.
        int step = patientMinutes + bufferMinutes;

        List<ScheduleModels.Busy> busy = new ArrayList<>(repository.busyOn(scope, doctorId, date));
        busy.addAll(repository.activeHoldsAsBusyOn(scope, doctorId, date));

        List<ScheduleModels.Slot> slots = new ArrayList<>();
        for (ScheduleModels.Window window : windows) {
            if (window.startMinute() >= window.endMinute()) {
                // The CHECK constraint refuses this at write time; reaching it here means the
                // constraint was bypassed, and silently returning nothing would hide that.
                throw new IllegalStateException(
                        "Schedule window " + window.id() + " has startMinute >= endMinute, "
                                + "which the CHECK constraint should have refused.");
            }

            for (int minute = window.startMinute(); minute + step <= window.endMinute();
                    minute += step) {
                if (isFree(minute, minute + patientMinutes, busy)) {
                    slots.add(new ScheduleModels.Slot(
                            atMinute(minute),
                            patientMinutes,
                            atMinute(minute + step)));
                }
            }
        }
        return slots;
    }

    /**
     * Whether the patient's interval is clear, in minutes-of-day.
     *
     * <p>Compares the PATIENT interval only, not the padded one. Padding is what keeps the NEXT
     * candidate from being generated too early — which the step already handles — and comparing
     * it here would double-count: a booking that occupies 09:00-09:40 would refuse a 09:30
     * candidate twice over and also refuse the 09:40 one that legitimately follows it.
     */
    private static boolean isFree(
            int startMinute, int endMinute, List<ScheduleModels.Busy> busy) {
        for (ScheduleModels.Busy b : busy) {
            int bStart = b.startsAt().toLocalTime().toSecondOfDay() / 60;
            int bEnd = bStart + b.blockedMinutes();
            // Half-open on both sides: touching intervals do not overlap, so a booking whose
            // padded interval ends at 09:40 does not block a 09:40 start.
            if (startMinute < bEnd && bStart < endMinute) {
                return false;
            }
        }
        return true;
    }

    private static LocalTime atMinute(int minuteOfDay) {
        return LocalTime.ofSecondOfDay(Math.min(Math.max(minuteOfDay, 0), 86_399) * 60L);
    }

    // ------------------------------------------------------------------
    // writes
    // ------------------------------------------------------------------

    /** Adds a working window. Two rows on one weekday is a split shift. */
    public ScheduleModels.Window addWindow(
            ClinicScope scope,
            UUID doctorId,
            int weekday,
            int startMinute,
            int endMinute,
            Integer durationOverrideMinutes,
            String timezone,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("doctorId", doctorId);
        parameters.put("weekday", weekday);
        parameters.put("startMinute", startMinute);
        parameters.put("endMinute", endMinute);
        parameters.put("durationOverrideMinutes", durationOverrideMinutes);
        parameters.put("timezone", timezone);
        parameters.put("effectiveFrom", effectiveFrom);
        parameters.put("effectiveTo", effectiveTo);
        parameters.put("isActive", true);

        UUID id = repository.insertWindow(scope, parameters);
        return repository.windows(scope, doctorId, weekday, effectiveFrom).stream()
                .filter(w -> w.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Schedule window " + id + " was written but could not be read back in scope."));
    }

    /** Records leave, a holiday or a training day. Both ends inclusive. */
    public UUID addTimeOff(
            ClinicScope scope, UUID doctorId, LocalDate startsOn, LocalDate endsOn, String reason) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("doctorId", doctorId);
        parameters.put("startsOn", startsOn);
        parameters.put("endsOn", endsOn);
        parameters.put("reason", reason);
        return repository.insertTimeOff(scope, parameters);
    }

    /** Registers a shared chair or piece of equipment. DEC-002: no dentist owns it. */
    public UUID addResource(ClinicScope scope, String name) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("name", name);
        parameters.put("isActive", true);
        return repository.insertResource(scope, parameters);
    }

    /** Tags a resource with a capability. DEC-002 leaves the tag vocabulary unseeded. */
    public UUID addCapability(ClinicScope scope, UUID resourceId, String capability) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("resourceId", resourceId);
        parameters.put("capability", capability);
        return repository.insertCapability(scope, parameters);
    }

    public List<ScheduleModels.Resource> resources(ClinicScope scope) {
        return repository.resources(scope, 200);
    }
}