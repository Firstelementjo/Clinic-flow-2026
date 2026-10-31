package com.firstelement.crm.clinic.report;

import com.firstelement.crm.clinic.ClinicScope;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Service orchestrating dental clinic reports and analytics (RPT-01 to RPT-05, BIL-08).
 */
@Service
public class ClinicReportService {

    private final ClinicReportRepository repository;

    public ClinicReportService(ClinicReportRepository repository) {
        this.repository = repository;
    }

    /**
     * Computes the aggregated clinic report for a given time window.
     */
    public ClinicReportModels.ClinicReportView getClinicReport(
            ClinicScope scope, String window, LocalDateTime customStart, LocalDateTime customEnd) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start;
        LocalDateTime end = customEnd != null ? customEnd : now;

        String label = window != null ? window.toLowerCase() : "30d";
        switch (label) {
            case "7d":
                start = now.minusDays(7);
                break;
            case "90d":
            case "quarter":
                start = now.minusDays(90);
                break;
            case "365d":
            case "year":
                start = now.minusDays(365);
                break;
            case "custom":
                start = customStart != null ? customStart : now.minusDays(30);
                break;
            case "30d":
            default:
                start = now.minusDays(30);
                label = "30d";
                break;
        }

        List<ClinicReportModels.FinancialSummaryRow> financials = repository.getFinancialSummary(scope, start, end);
        List<ClinicReportModels.DoctorProductivityRow> doctorProductivity = repository.getDoctorProductivity(scope, start, end);
        ClinicReportModels.AppointmentMetrics appointments = repository.getAppointmentMetrics(scope, start, end);
        ClinicReportModels.PatientMetrics patients = repository.getPatientMetrics(scope, start, end);
        ClinicReportModels.PatientAgeDistribution ageDistribution =
                repository.getPatientAgeDistribution(scope, start, end);

        return new ClinicReportModels.ClinicReportView(
                start, end, label, financials, doctorProductivity, appointments, patients, ageDistribution
        );
    }
}
