package com.firstelement.crm.clinic;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The clinic read-model projections, and the fields deliberately absent from them.
 *
 * <h2>The disclosure rule behind every projection here</h2>
 *
 * <p>A field is projected because {@code doctor:read} / {@code procedure:read} /
 * {@code appointment:read} authorizes knowing it, and it is absent because a <em>different</em>
 * permission authorizes knowing it. This is the Spring half of the split Phase 33 drew in the
 * legacy runtime, and it is why these records carry an {@code organizationId} and a
 * {@code projectId}: a reader can tell which scope a row came from without the server having
 * to guess which scope to disclose it in.
 *
 * <h2>What is absent, by name</h2>
 *
 * <ul>
 *   <li><b>{@code doctors.email}, {@code doctors.phone}</b> — {@code doctor:read-management}.
 *       See {@link ClinicRepository}'s class comment for why absence from the SQL beats
 *       redaction in the response.</li>
 *   <li><b>{@code appointments.notes}, {@code appointments.aiProposalReason}</b> — free-text
 *       written about a patient. The legacy breach (migration ledger §10) reached exactly this
 *       surface, and a notes column on a read model is a clinical record that no clinic
 *       permission in {@code Permission} names.</li>
 *   <li><b>{@code appointments.patientPhone} and {@code appointments.patientEmail}</b> — a
 *       patient's contact details. <b>This is the one that needed a decision and is worth
 *       spelling out.</b> A receptionist does need to reach a patient, and the legacy
 *       {@code APPOINTMENT_SELECT} carried the phone number, so projecting it looks like parity.
 *       It is not. In the legacy matrix the surface that legitimately exposes patient phone
 *       numbers is {@code GET /clinic/reminders}, and that route sits behind
 *       {@code REMINDER_READ} — a permission whose stated meaning is "returns patient names and
 *       phone numbers". Projecting the number here would hand the same disclosure to
 *       {@code appointment:read}, a permission that never named it, which is exactly the ADR-0010
 *       defect: an audit row reading {@code appointment:read} while the actor read a patient's
 *       phone number would assert something untrue.
 *       <p>{@code REMINDER_READ} is deliberately not in the {@code Permission} enum yet — this
 *       slice is read-only and there is no reminder route to authorize. When the reminder surface
 *       is migrated, it brings its own grant, and the contact details come back with it.</li>
 *   <li><b>Every rate-card, margin, revenue and currency column.</b> Nothing in this module
 *       computes money. See {@link ClinicAnalytics}.</li>
 * </ul>
 *
 * <h2>Why these are records and not entities</h2>
 *
 * <p>Nothing here is written. A mutable domain object on a read-only module is a class that
 * invites a setter later, and a setter on a clinic row with no permission check behind it is
 * the cross-tenant write this migration already suffered once.
 */
public final class ClinicModels {

    private ClinicModels() {
    }

    /**
     * One clinician, as {@code doctor:read} may see one.
     *
     * <p>{@code workingDays} is {@code 0 = Sunday} … {@code 6 = Saturday}, the convention the
     * legacy {@code Doctor.workingDays} column already used for a GCC clinic week.
     *
     * @param workingDays the days this clinician works; empty means "not configured"
     */
    public record Doctor(
            UUID id,
            UUID organizationId,
            UUID projectId,
            String name,
            String specialty,
            String department,
            int[] workingDays,
            String startHour,
            String endHour,
            int slotDuration,
            boolean isActive,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {

        /** {@code @Override} on an array component: two records with equal days are equal. */
        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Doctor that)) {
                return false;
            }
            return java.util.Objects.equals(id, that.id)
                && java.util.Arrays.equals(workingDays, that.workingDays);
        }

        @Override
        public int hashCode() {
            return 31 * java.util.Objects.hashCode(id) + java.util.Arrays.hashCode(workingDays);
        }

        @Override
        public String toString() {
            return "Doctor[" + id + "]";
        }
    }

    /**
     * One procedure.
     *
     * <p>{@code price} is projected because it is a catalogue attribute the legacy
     * {@code PROCEDURE_READ} also carried and it is a stored column on the row. It is
     * <b>nullable in the database</b> and stays nullable here: {@code null} means "this clinic
     * has not priced this procedure", which is a different fact from {@code 0} meaning "this
     * procedure is free" (FR-7.6).
     */
    public record Procedure(
            UUID id,
            UUID organizationId,
            UUID projectId,
            String name,
            String specialty,
            int durationMinutes,
            int bufferMinutes,
            Double price,
            boolean isActive,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            /*
             * The service-catalogue fields added in V20261102000000. They are
             * deliberately after the original projection: the first eleven
             * components are the identity of a procedure and every existing
             * caller reads them by position.
             *
             * A null price still means "not priced". A null discount means "no
             * discount", which is not the same as a discount of zero percent
             * when the field is displayed on an invoice.
             */
            String productCode,
            String subCategory,
            UUID clinicId,
            String clinicName,
            Integer timeSlotMinutes,
            String type,
            String description,
            Double discount,
            String imageUrl,
            String systemService,
            boolean advancedPayment
    ) {
    }

    /**
     * One appointment.
     *
     * @param status one of the seven DEC-003 values: {@code PENDING}, {@code CONFIRMED},
     *               {@code CHECKED_IN}, {@code IN_PROGRESS}, {@code COMPLETED} (these five
     *               hold the slot) and {@code CANCELLED}, {@code NO_SHOW} (these two free it).
     *               The list is enforced by {@code appointments_status_dec003}, so a row
     *               outside it cannot be stored at all.
     *               <b>Not an enum.</b> The column is a free {@code varchar} in the
     *               verified schema, so a Java enum would make an unmigrated row fail to
     *               deserialise and take a whole schedule page down with it. The values are
     *               validated at the <em>filter</em> boundary instead, where an unknown value is
     *               a caller error rather than a stored one.
     *               <b>DEC-017 removed the eighth value.</b> {@code APPROVED} used to be
     *               written by the deployed AI booking path with no human approval anywhere;
     *               the column is now closed, and confirming is {@code PENDING} to
     *               {@code CONFIRMED} by a person.
     * @param isAiProposed whether the AI receptionist proposed this booking; a stored fact on
     *                     the row, not an inference
     */
    public record Appointment(
            UUID id,
            UUID organizationId,
            UUID projectId,
            UUID customerId,
            UUID patientId,
            UUID clinicId,
            UUID doctorId,
            UUID procedureId,
            String title,
            String patientName,
            String patientEmail,
            LocalDateTime appointmentDate,
            int durationMinutes,
            String status,
            String service,
            String doctorName,
            String doctorImageUrl,
            Double price,
            int quantity,
            Double totalAmount,
            String paymentStatus,
            String medicalReportName,
            String medicalReportUrl,
            boolean isAiProposed,
            String ticketNumber,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }

    /**
     * The payment states an appointment can carry.
     *
     * <p>Like {@link AppointmentStatus}, this is the wire mirror of the database CHECK
     * and the filter boundary. An unknown value is refused with a 400 rather than
     * silently dropped, because a dropped payment filter widens the list and a wider
     * list looks exactly like an honest one.
     */
    public enum PaymentStatus {

        PAID, UNPAID, PARTIAL, REFUNDED;

        public String wireValue() {
            return name();
        }

        public static PaymentStatus parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            for (PaymentStatus candidate : values()) {
                if (candidate.name().equalsIgnoreCase(raw.trim())) {
                    return candidate;
                }
            }
            throw new com.firstelement.crm.error.ValidationFailedException(
                "paymentStatus must be one of " + java.util.Arrays.toString(values()),
                java.util.Map.of("field", "paymentStatus", "accepted",
                    java.util.Arrays.stream(values())
                        .map(PaymentStatus::wireValue).toList()));
        }
    }

    /**
     * The appointment statuses this module knows, and the only ones a filter may name.
     *
     * <p>Declared here rather than inferred from the column default so a client gets a 400
     * naming the accepted set instead of an empty page it has to guess about.
     *
     * <p><b>This list must stay identical to {@code appointments_status_dec003}.</b> DEC-017
     * made that the source of truth, and this enum is the wire mirror of it. When the two
     * drift the damage is silent and lands on a caller: a value added here but refused by the
     * CHECK is a filter that is accepted, admits a parameter, and then matches nothing — the
     * "accepted and then discarded" alternative the paragraph below rejects. Adding a status
     * means a migration first.
     *
     * <p>{@link #parse} is the <em>filter</em> boundary: an unknown status is refused rather
     * than silently dropped. Silently ignoring an unknown filter key is ADR 0017's rejected
     * alternative 3 — "a request parameter that is accepted and then discarded" — and it is
     * worse here, because a dropped filter widens the result and the widened result is
     * indistinguishable from an honest one.
     */
    public enum AppointmentStatus {

        PENDING, CONFIRMED, CHECKED_IN, IN_PROGRESS, COMPLETED, CANCELLED, NO_SHOW;

        /** The stored value, which is the enum's own name. */
        public String wireValue() {
            return name();
        }

        /**
         * Parses a caller-supplied status.
         *
         * @return the parsed status, or {@code null} when the caller sent nothing
         * @throws com.firstelement.crm.error.ValidationFailedException naming the accepted set,
         *         which {@code CrmExceptionAdvice} renders as {@code 400 VALIDATION_FAILED}
         */
        public static AppointmentStatus parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            for (AppointmentStatus candidate : values()) {
                if (candidate.name().equalsIgnoreCase(raw.trim())) {
                    return candidate;
                }
            }
            throw new com.firstelement.crm.error.ValidationFailedException(
                "status must be one of " + java.util.Arrays.toString(values()),
                java.util.Map.of("field", "status", "accepted",
                    java.util.Arrays.stream(values())
                        .map(AppointmentStatus::wireValue).toList()));
        }
    }
}