package com.firstelement.crm.clinic.report;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Domain models for clinic reports and operational metrics (RPT-01 to RPT-05, BIL-08).
 */
public final class ClinicReportModels {

    private ClinicReportModels() {
    }

    /**
     * Financial KPI summary broken down by currency (BIL-08, RPT-03).
     */
    public record FinancialSummaryRow(
            String currency,
            BigDecimal totalInvoiced,
            BigDecimal totalCollected,
            BigDecimal totalDiscount,
            BigDecimal totalBalanceDue,
            int invoiceCount,
            int paymentCount) {
    }

    /**
     * Clinician productivity and patient load (RPT-03).
     */
    public record DoctorProductivityRow(
            UUID doctorId,
            String doctorName,
            String specialty,
            int appointmentCount,
            int encounterCount,
            BigDecimal totalInvoicedAmount,
            BigDecimal totalCollectedAmount) {
    }

    /**
     * Appointment performance and cancellation rate (RPT-01, RPT-02, RPT-04).
     */
    public record AppointmentMetrics(
            int totalAppointments,
            int completedCount,
            int confirmedCount,
            int cancelledCount,
            int noShowCount,
            double cancellationRatePercent) {
    }

    /**
     * Patient cohort and retention metrics (RPT-05).
     */
    public record PatientMetrics(
            int totalPatients,
            int newPatientsInWindow) {
    }

    /**
     * The patients who visited in the window, banded by age (RPT-05).
     *
     * <p>"Visited" is the operative word: this counts a patient once, and only when
     * they actually came in during the window. Counting the whole patient list
     * instead would answer a different question — how many patients the clinic
     * has ever registered — and would answer it wrongly at the edges, because
     * {@code dateOfBirth} is optional and a patient without one has no band.
     *
     * @param age0to25  patients aged 25 or younger at the end of the window
     * @param age26to50 patients aged 26 to 50 at the end of the window
     * @param age50Plus patients over 50 at the end of the window
     */
    public record PatientAgeDistribution(
            int age0to25,
            int age26to50,
            int age50Plus) {
    }

    /**
     * Full aggregated clinic report view for a selected window.
     */
    public record ClinicReportView(
            LocalDateTime windowStart,
            LocalDateTime windowEnd,
            String windowLabel,
            List<FinancialSummaryRow> financials,
            List<DoctorProductivityRow> doctorProductivity,
            AppointmentMetrics appointments,
            PatientMetrics patients,
            PatientAgeDistribution patientAgeDistribution) {
    }
}
