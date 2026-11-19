package com.firstelement.crm.clinic.schedule;

import java.time.LocalDateTime;
import java.time.LocalTime;

/** Schedule and availability read models. */
public final class ScheduleModels {

    private ScheduleModels() {
    }

    /**
     * One working window for one dentist on one weekday.
     *
     * <p>{@code effectiveTo} is null for an open-ended window. {@code durationOverrideMinutes} is
     * DEC-001's nullable per-dentist override and is null when the service duration applies.
     */
    public record Window(
            java.util.UUID id,
            int weekday,
            int startMinute,
            int endMinute,
            Integer durationOverrideMinutes,
            String timezone,
            java.time.LocalDate effectiveFrom,
            java.time.LocalDate effectiveTo) {
    }

    /**
     * A booked interval, already expanded by its own padding.
     *
     * <p>{@code blockedUntil} is {@code startsAt + duration + buffer}. Storing the expanded end
     * rather than the raw duration is deliberate: the whole DEC-001 rule is that the buffer
     * occupies the schedule, and a model that kept the raw duration would let that be re-derived
     * wrongly somewhere else.
     */
    public record Busy(java.util.UUID id, LocalDateTime startsAt, int blockedMinutes) {
    }

    /**
     * One bookable slot.
     *
     * <p>{@code patientMinutes} is what the patient is booked for; {@code blockedUntil} is when
     * the dentist is actually free again. For DEC-001 these differ whenever a buffer applies.
     */
    public record Slot(LocalTime startsAt, int patientMinutes, LocalTime blockedUntil) {
    }

    /**
     * A shared chair or piece of equipment.
     *
     * <p>DEC-002: shared between dentists, which is why it carries no {@code doctorId}, and
     * staff-only, which is why reading it is a staff capability.
     */
    public record Resource(java.util.UUID id, String name, boolean active) {
    }

    public record ResourceDetail(
            java.util.UUID id,
            java.util.UUID organizationId,
            java.util.UUID projectId,
            String name,
            boolean active,
            java.util.List<String> capabilities,
            java.time.Instant createdAt) {
    }

    public record CreateResourceRequest(String name, java.util.List<String> capabilities) {
    }

    public record HoldRow(
            java.util.UUID id,
            java.util.UUID organizationId,
            java.util.UUID projectId,
            java.util.UUID doctorId,
            java.util.UUID resourceId,
            LocalDateTime slotStartsAt,
            int durationMinutes,
            int bufferMinutes,
            LocalDateTime holdExpiresAt,
            java.util.UUID heldByUserId,
            String patientIdentifier,
            String status,
            java.time.Instant createdAt) {
    }

    public record CreateHoldRequest(
            java.util.UUID doctorId,
            java.util.UUID resourceId,
            LocalDateTime slotStartsAt,
            Integer durationMinutes,
            Integer bufferMinutes,
            Integer holdMinutes,
            String patientIdentifier) {
    }

    public record WaitlistRow(
            java.util.UUID id,
            java.util.UUID organizationId,
            java.util.UUID projectId,
            java.util.UUID patientId,
            String patientName,
            String patientPhone,
            java.util.UUID preferredDoctorId,
            java.util.UUID procedureId,
            String preferredDays,
            String preferredTimeOfDay,
            String priority,
            String status,
            LocalDateTime offeredSlotStartsAt,
            String notes,
            java.time.Instant createdAt,
            java.time.Instant updatedAt) {
    }

    public record CreateWaitlistRequest(
            java.util.UUID patientId,
            String patientName,
            String patientPhone,
            java.util.UUID preferredDoctorId,
            java.util.UUID procedureId,
            String preferredDays,
            String preferredTimeOfDay,
            String priority,
            String notes) {
    }

    public record UpdateWaitlistStatusRequest(String status, LocalDateTime offeredSlotStartsAt) {
    }

    public record ReminderRow(
            java.util.UUID id,
            java.util.UUID organizationId,
            java.util.UUID projectId,
            java.util.UUID appointmentId,
            java.util.UUID patientId,
            String channel,
            String recipientPhone,
            LocalDateTime scheduledFor,
            LocalDateTime sentAt,
            String status,
            String templateCode,
            String failureReason,
            java.time.Instant createdAt) {
    }

    public record CreateReminderRequest(
            java.util.UUID appointmentId,
            java.util.UUID patientId,
            String channel,
            String recipientPhone,
            LocalDateTime scheduledFor,
            String templateCode) {
    }

    public record CreateAppointmentRequest(
            java.util.UUID doctorId,
            String doctorName,
            java.util.UUID patientId,
            String patientName,
            String patientPhone,
            String patientEmail,
            java.util.UUID clinicId,
            java.util.UUID procedureId,
            String service,
            LocalDateTime appointmentDate,
            Integer durationMinutes,
            Integer bufferMinutes,
            Integer quantity,
            Double price,
            String paymentStatus,
            String medicalReportName,
            String medicalReportContentType,
            String medicalReportUrl,
            java.util.UUID resourceId,
            String notes,
            java.util.UUID treatmentRoomId) {
        public CreateAppointmentRequest(
                java.util.UUID doctorId,
                String doctorName,
                java.util.UUID patientId,
                String patientName,
                String patientPhone,
                String patientEmail,
                java.util.UUID clinicId,
                java.util.UUID procedureId,
                String service,
                LocalDateTime appointmentDate,
                Integer durationMinutes,
                Integer bufferMinutes,
                Integer quantity,
                Double price,
                String paymentStatus,
                String medicalReportName,
                String medicalReportContentType,
                String medicalReportUrl,
                java.util.UUID resourceId,
                String notes) {
            this(doctorId, doctorName, patientId, patientName, patientPhone, patientEmail,
                 clinicId, procedureId, service, appointmentDate, durationMinutes, bufferMinutes,
                 quantity, price, paymentStatus, medicalReportName, medicalReportContentType,
                 medicalReportUrl, resourceId, notes, null);
        }
    }

    public record UpdateAppointmentStatusRequest(
            String status,
            String cancelledReason) {
    }

    public record AppointmentCreatedResponse(
            java.util.UUID id,
            String ticketNumber,
            String status,
            LocalDateTime appointmentDate) {
    }
}