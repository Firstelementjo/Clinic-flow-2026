package com.firstelement.crm.clinic.report;

import com.firstelement.crm.clinic.ClinicScope;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Scoped repository computing clinic financial and operational reports (RPT-01 to RPT-05, BIL-08).
 */
@Repository
public class ClinicReportRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ClinicReportRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Aggregates revenue, collections, and discounts grouped by currency (BIL-08).
     */
    public List<ClinicReportModels.FinancialSummaryRow> getFinancialSummary(
            ClinicScope scope, LocalDateTime start, LocalDateTime end) {
        return scope.read(() -> {
            MapSqlParameterSource params = scope.bind()
                    .addValue("start", start)
                    .addValue("end", end);

            return jdbc.query("""
                    select ci."currency",
                           coalesce(sum(ci."totalAmount") filter (where ci."status" != 'CANCELLED'), 0.000) as total_invoiced,
                           coalesce(sum(ci."paidAmount") filter (where ci."status" != 'CANCELLED'), 0.000) as total_collected,
                           coalesce(sum(ci."discountAmount") filter (where ci."status" != 'CANCELLED'), 0.000) as total_discount,
                           coalesce(sum(ci."balanceDue") filter (where ci."status" != 'CANCELLED'), 0.000) as total_balance_due,
                           count(ci.id) as invoice_count,
                           coalesce(p.pmt_count, 0) as payment_count
                      from "clinic_invoices" ci
                      left join (
                          select cp."currency", count(cp.id) as pmt_count
                            from "clinic_payments" cp
                           where %s
                             and cp."paymentDate" >= :start and cp."paymentDate" <= :end
                           group by cp."currency"
                      ) p on p."currency" = ci."currency"
                     where %s
                       and ci."issuedAt" >= :start and ci."issuedAt" <= :end
                     group by ci."currency", p.pmt_count
                    """.formatted(scope.predicate("cp"), scope.predicate("ci")),
                    params,
                    (rs, rowNum) -> new ClinicReportModels.FinancialSummaryRow(
                            rs.getString("currency"),
                            rs.getBigDecimal("total_invoiced"),
                            rs.getBigDecimal("total_collected"),
                            rs.getBigDecimal("total_discount"),
                            rs.getBigDecimal("total_balance_due"),
                            rs.getInt("invoice_count"),
                            rs.getInt("payment_count")
                    ));
        });
    }

    /**
     * Clinician productivity and clinical encounter counts (RPT-03).
     */
    public List<ClinicReportModels.DoctorProductivityRow> getDoctorProductivity(
            ClinicScope scope, LocalDateTime start, LocalDateTime end) {
        return scope.read(() -> {
            MapSqlParameterSource params = scope.bind()
                    .addValue("start", start)
                    .addValue("end", end);

            return jdbc.query("""
                    select d.id as "doctorId", d.name as "doctorName", d.specialty,
                           coalesce(appts.appt_count, 0) as appointment_count,
                           coalesce(encs.enc_count, 0) as encounter_count
                      from "doctors" d
                      left join (
                          select a."doctorId", count(a.id) as appt_count
                            from "appointments" a
                           where %s
                             and a."appointmentDate" >= :start and a."appointmentDate" <= :end
                           group by a."doctorId"
                      ) appts on appts."doctorId" = d.id
                      left join (
                          select ce."doctorId", count(ce.id) as enc_count
                            from "clinical_encounters" ce
                           where %s
                             and ce."visitDate" >= :start and ce."visitDate" <= :end
                           group by ce."doctorId"
                      ) encs on encs."doctorId" = d.id
                     where %s
                       and d."isActive" = true
                     order by appt_count desc, enc_count desc
                    """.formatted(scope.predicate("a"), scope.predicate("ce"), scope.predicate("d")),
                    params,
                    (rs, rowNum) -> new ClinicReportModels.DoctorProductivityRow(
                            (UUID) rs.getObject("doctorId"),
                            rs.getString("doctorName"),
                            rs.getString("specialty"),
                            rs.getInt("appointment_count"),
                            rs.getInt("encounter_count"),
                            BigDecimal.ZERO,
                            BigDecimal.ZERO
                    ));
        });
    }

    /**
     * Appointment performance metrics (RPT-01, RPT-02, RPT-04).
     */
    public ClinicReportModels.AppointmentMetrics getAppointmentMetrics(
            ClinicScope scope, LocalDateTime start, LocalDateTime end) {
        return scope.read(() -> {
            MapSqlParameterSource params = scope.bind()
                    .addValue("start", start)
                    .addValue("end", end);

            return jdbc.queryForObject("""
                    select count(a.id) as total_count,
                           count(a.id) filter (where a.status = 'COMPLETED') as completed_count,
                           count(a.id) filter (where a.status in ('CONFIRMED', 'ARRIVED', 'IN_TREATMENT')) as confirmed_count,
                           count(a.id) filter (where a.status = 'CANCELLED') as cancelled_count,
                           count(a.id) filter (where a.status in ('NO_SHOW', 'NO_SHOW_RECORDED')) as noshow_count
                      from "appointments" a
                     where %s
                       and a."appointmentDate" >= :start and a."appointmentDate" <= :end
                    """.formatted(scope.predicate("a")),
                    params,
                    (rs, rowNum) -> {
                        int total = rs.getInt("total_count");
                        int completed = rs.getInt("completed_count");
                        int confirmed = rs.getInt("confirmed_count");
                        int cancelled = rs.getInt("cancelled_count");
                        int noshow = rs.getInt("noshow_count");
                        double rate = total > 0 ? ((double) (cancelled + noshow) / total) * 100.0 : 0.0;
                        return new ClinicReportModels.AppointmentMetrics(
                                total, completed, confirmed, cancelled, noshow, Math.round(rate * 10.0) / 10.0
                        );
                    });
        });
    }

    /**
     * Patient cohort metrics (RPT-05).
     */
    public ClinicReportModels.PatientMetrics getPatientMetrics(
            ClinicScope scope, LocalDateTime start, LocalDateTime end) {
        return scope.read(() -> {
            MapSqlParameterSource params = scope.bind()
                    .addValue("start", start)
                    .addValue("end", end);

            return jdbc.queryForObject("""
                    select count(p.id) as total_patients,
                           count(p.id) filter (where p."createdAt" >= :start and p."createdAt" <= :end) as new_patients
                      from "patients" p
                     where %s
                       and p."deletedAt" is null
                    """.formatted(scope.predicate("p")),
                    params,
                    (rs, rowNum) -> new ClinicReportModels.PatientMetrics(
                            rs.getInt("total_patients"),
                            rs.getInt("new_patients")
                    ));
        });
    }

    /**
     * The patients who visited inside the window, banded by age (RPT-05).
     *
     * <p>The link between a visit and a patient is {@code appointments.customerId =
     * patients.customerId}, which is the same link the recall report uses
     * ({@code ClinicAiRepository.fetchRecallCandidateMetrics}). It is the only link
     * there is: {@code appointments} carries no {@code patientId}.
     *
     * <p>A patient is counted <b>once</b>, however many visits they made in the
     * window — the panel is a head count, not a visit count. A patient whose
     * {@code dateOfBirth} is not recorded cannot be banded, so they are left out of
     * all three bands rather than being placed in one by guesswork.
     *
     * <p>Age is measured at the END of the window, so a band does not change
     * underneath a report as the report is re-read with a different end date.
     */
    public ClinicReportModels.PatientAgeDistribution getPatientAgeDistribution(
            ClinicScope scope, LocalDateTime start, LocalDateTime end) {
        return scope.read(() -> {
            MapSqlParameterSource params = scope.bind()
                    .addValue("start", start)
                    .addValue("end", end)
                    .addValue("onDate", end.toLocalDate());

            return jdbc.queryForObject("""
                    select count(*) filter (where v.years < 26) as age_0_25,
                           count(*) filter (where v.years between 26 and 50) as age_26_50,
                           count(*) filter (where v.years > 50) as age_50_plus
                      from (
                          select distinct p.id,
                                 extract(year from age(cast(:onDate as date), p."dateOfBirth"))::int as years
                            from "patients" p
                            join "appointments" a
                              on a."customerId" = p."customerId"
                             and a."organizationId" = p."organizationId"
                           where %s
                             and %s
                             and p."deletedAt" is null
                             and p."customerId" is not null
                             and p."dateOfBirth" is not null
                             and a."appointmentDate" >= :start and a."appointmentDate" <= :end
                      ) v
                    """.formatted(scope.predicate("p"), scope.predicate("a")),
                    params,
                    (rs, rowNum) -> new ClinicReportModels.PatientAgeDistribution(
                            rs.getInt("age_0_25"),
                            rs.getInt("age_26_50"),
                            rs.getInt("age_50_plus")
                    ));
        });
    }
}
