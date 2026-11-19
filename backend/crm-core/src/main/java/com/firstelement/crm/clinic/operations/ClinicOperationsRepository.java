package com.firstelement.crm.clinic.operations;

import com.firstelement.crm.clinic.ClinicScope;
import com.firstelement.crm.error.ResourceNotFoundException;
import com.firstelement.crm.error.ValidationFailedException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Tenant-scoped persistence for the operations screens.
 *
 * <p>Every statement runs inside {@link ClinicScope#read}, which is the same
 * door the rest of the clinic module uses: the scope predicate carries the
 * organization and the project, so a row from another clinic is not merely
 * filtered out of a result, it is unreachable.
 */
@Repository
public class ClinicOperationsRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final InventoryOperationsStore inventoryStore;
    private final HrLiteOperationsStore hrLiteStore;
    private final FinanceOperationsStore financeStore;
    private final TreatmentRoomOperationsStore treatmentRoomStore;
    private final ImagingOperationsStore imagingStore;

    public ClinicOperationsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.inventoryStore = new InventoryOperationsStore(jdbc);
        this.hrLiteStore = new HrLiteOperationsStore(jdbc);
        this.financeStore = new FinanceOperationsStore(jdbc);
        this.treatmentRoomStore = new TreatmentRoomOperationsStore(jdbc);
        this.imagingStore = new ImagingOperationsStore(jdbc);
    }

    InventoryOperationsStore inventory() {
        return inventoryStore;
    }

    HrLiteOperationsStore hrLite() {
        return hrLiteStore;
    }

    FinanceOperationsStore finance() {
        return financeStore;
    }

    TreatmentRoomOperationsStore treatmentRooms() {
        return treatmentRoomStore;
    }

    ImagingOperationsStore imaging() {
        return imagingStore;
    }

    // ------------------------------------------------------------------
    // Clear Aligner Lab
    // ------------------------------------------------------------------

    public List<ClinicOperationsModels.AlignerCaseRow> listAlignerCases(
            ClinicScope scope, String status, String q) {
        return scope.read(() -> {
            StringBuilder sql = new StringBuilder("""
                    select ac.id, ac."organizationId", ac."projectId", ac."patientId",
                           coalesce(p."displayName", 'Patient') as "patientName",
                           ac."doctorId", coalesce(d."name", 'Doctor') as "doctorName",
                           ac."caseType", ac."impressionType", ac."shade", ac."status",
                           ac."upperSteps", ac."lowerSteps", ac."teethSummary", ac."iprSummary",
                           ac."attachmentsCount", ac."rxNotes", ac."files",
                           ac."createdAt", ac."updatedAt"
                      from clinic_aligner_cases ac
                      left join "patients" p on p."id" = ac."patientId" and p."organizationId" = ac."organizationId"
                      left join "doctors" d on d."id" = ac."doctorId" and d."organizationId" = ac."organizationId"
                     where %s
                    """.formatted(scope.predicate("ac")));
            MapSqlParameterSource params = scope.bind();
            if (status != null && !status.isBlank()) {
                sql.append(" and ac.\"status\" = :status");
                params.addValue("status", status.trim().toUpperCase());
            }
            if (q != null && !q.isBlank()) {
                sql.append(" and (p.\"displayName\" ilike :q or d.\"name\" ilike :q")
                        .append(" or ac.\"caseType\" ilike :q or ac.\"teethSummary\" ilike :q)");
                params.addValue("q", "%" + q.trim() + "%");
            }
            sql.append(" order by ac.\"createdAt\" desc");
            return jdbc.query(sql.toString(), params, ClinicOperationsRepository::mapAlignerCase);
        });
    }

    public ClinicOperationsModels.AlignerCaseRow createAlignerCase(
            ClinicScope scope, ClinicOperationsModels.CreateAlignerCaseRequest req) {
        if (!scope.isProjectScoped()) {
            throw new ValidationFailedException("An aligner case must belong to one clinic.");
        }
        return scope.read(() -> {
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    insert into clinic_aligner_cases
                        (id, "organizationId", "projectId", "patientId", "doctorId",
                         "caseType", "impressionType", "shade", "status",
                         "upperSteps", "lowerSteps", "teethSummary", "iprSummary",
                         "attachmentsCount", "rxNotes", "files")
                    values
                        (:id, :orgId, :projId, :patientId, :doctorId,
                         :caseType, :impressionType, :shade, 'SENT_TO_LAB',
                         :upperSteps, :lowerSteps, :teethSummary, :iprSummary,
                         :attachmentsCount, :rxNotes, :files)
                    """, new MapSqlParameterSource()
                    .addValue("id", id)
                    .addValue("orgId", scope.queryScope().organizationId())
                    .addValue("projId", scope.projectId())
                    .addValue("patientId", req.patientId())
                    .addValue("doctorId", req.doctorId())
                    .addValue("caseType", req.caseType().trim())
                    .addValue("impressionType", req.impressionType())
                    .addValue("shade", req.shade())
                    .addValue("upperSteps", req.upperSteps() != null ? req.upperSteps() : 0)
                    .addValue("lowerSteps", req.lowerSteps() != null ? req.lowerSteps() : 0)
                    .addValue("teethSummary", req.teethSummary())
                    .addValue("iprSummary", req.iprSummary())
                    .addValue("attachmentsCount", req.attachmentsCount() != null ? req.attachmentsCount() : 0)
                    .addValue("rxNotes", req.rxNotes())
                    .addValue("files", req.files() != null ? req.files().toArray(String[]::new) : null));
            return findAlignerCase(scope, id);
        });
    }

    public ClinicOperationsModels.AlignerCaseRow findAlignerCase(ClinicScope scope, UUID id) {
        return scope.read(() -> {
            List<ClinicOperationsModels.AlignerCaseRow> rows = jdbc.query("""
                    select ac.id, ac."organizationId", ac."projectId", ac."patientId",
                           coalesce(p."displayName", 'Patient') as "patientName",
                           ac."doctorId", coalesce(d."name", 'Doctor') as "doctorName",
                           ac."caseType", ac."impressionType", ac."shade", ac."status",
                           ac."upperSteps", ac."lowerSteps", ac."teethSummary", ac."iprSummary",
                           ac."attachmentsCount", ac."rxNotes", ac."files",
                           ac."createdAt", ac."updatedAt"
                      from clinic_aligner_cases ac
                      left join "patients" p on p."id" = ac."patientId" and p."organizationId" = ac."organizationId"
                      left join "doctors" d on d."id" = ac."doctorId" and d."organizationId" = ac."organizationId"
                     where %s and ac.id = :id
                    """.formatted(scope.predicate("ac")),
                    scope.bind().addValue("id", id),
                    ClinicOperationsRepository::mapAlignerCase);
            return rows.stream().findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("Resource not found"));
        });
    }

    public ClinicOperationsModels.AlignerCaseRow updateAlignerCaseStatus(
            ClinicScope scope, UUID id, String status) {
        String normalized = status.trim().toUpperCase();
        return scope.read(() -> {
            int updated = jdbc.update("""
                    update clinic_aligner_cases ac
                       set "status" = :status, "updatedAt" = (now() at time zone 'utc')
                     where %s and ac.id = :id
                    """.formatted(scope.predicate("ac")),
                    scope.bind().addValue("id", id).addValue("status", normalized));
            if (updated == 0) {
                throw new ResourceNotFoundException("Resource not found");
            }
            return findAlignerCase(scope, id);
        });
    }

    public boolean deleteAlignerCase(ClinicScope scope, UUID id) {
        return scope.read(() -> jdbc.update("""
                delete from clinic_aligner_cases ac
                 where %s and ac.id = :id
                """.formatted(scope.predicate("ac")),
                scope.bind().addValue("id", id)) > 0);
    }

    // ------------------------------------------------------------------
    // Patient Feedback
    // ------------------------------------------------------------------

    public List<ClinicOperationsModels.FeedbackRow> listFeedback(
            ClinicScope scope, String q, UUID doctorId, Integer minRating) {
        return scope.read(() -> {
            StringBuilder sql = new StringBuilder("""
                    select f.id, f."organizationId", f."projectId", f."patientId",
                           f."patientName", f."patientEmail", f."patientPhone", f."patientAge",
                           f."doctorId", f."doctorName", f."serviceName", f."treatmentsReceived",
                           f."clinicLocation", f."referralSource",
                           f."ratingExplanation", f."ratingHygiene", f."ratingCourtesy",
                           f."ratingComfort", f."ratingRecommend", f."message",
                           f."submittedAt", f."createdAt"
                      from clinic_feedback f
                     where %s
                    """.formatted(scope.predicate("f")));
            MapSqlParameterSource params = scope.bind();
            if (q != null && !q.isBlank()) {
                sql.append(" and (f.\"patientName\" ilike :q or f.\"patientEmail\" ilike :q")
                        .append(" or f.\"doctorName\" ilike :q or f.\"serviceName\" ilike :q)");
                params.addValue("q", "%" + q.trim() + "%");
            }
            if (doctorId != null) {
                sql.append(" and f.\"doctorId\" = :doctorId");
                params.addValue("doctorId", doctorId);
            }
            if (minRating != null) {
                sql.append(" and (f.\"ratingExplanation\" + f.\"ratingHygiene\" + f.\"ratingCourtesy\"")
                        .append(" + f.\"ratingComfort\" + f.\"ratingRecommend\") >= :minRating * 5");
                params.addValue("minRating", minRating);
            }
            sql.append(" order by f.\"submittedAt\" desc");
            return jdbc.query(sql.toString(), params, ClinicOperationsRepository::mapFeedback);
        });
    }

    public ClinicOperationsModels.FeedbackRow createFeedback(
            ClinicScope scope, ClinicOperationsModels.CreateFeedbackRequest req) {
        if (!scope.isProjectScoped()) {
            throw new ValidationFailedException("Feedback must belong to one clinic.");
        }
        return scope.read(() -> {
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    insert into clinic_feedback
                        (id, "organizationId", "projectId", "patientId", "doctorId",
                         "patientName", "patientEmail", "patientPhone", "patientAge",
                         "doctorName", "serviceName", "treatmentsReceived", "clinicLocation",
                         "referralSource", "ratingExplanation", "ratingHygiene", "ratingCourtesy",
                         "ratingComfort", "ratingRecommend", "message", "submittedAt")
                    values
                        (:id, :orgId, :projId, :patientId, :doctorId,
                         :patientName, :patientEmail, :patientPhone, :patientAge,
                         :doctorName, :serviceName, :treatmentsReceived, :clinicLocation,
                         :referralSource, :ratingExplanation, :ratingHygiene, :ratingCourtesy,
                         :ratingComfort, :ratingRecommend, :message, (now() at time zone 'utc'))
                    """, new MapSqlParameterSource()
                    .addValue("id", id)
                    .addValue("orgId", scope.queryScope().organizationId())
                    .addValue("projId", scope.projectId())
                    .addValue("patientId", req.patientId())
                    .addValue("doctorId", req.doctorId())
                    .addValue("patientName", req.patientName().trim())
                    .addValue("patientEmail", req.patientEmail())
                    .addValue("patientPhone", req.patientPhone())
                    .addValue("patientAge", req.patientAge())
                    .addValue("doctorName", req.doctorName())
                    .addValue("serviceName", req.serviceName())
                    .addValue("treatmentsReceived", req.treatmentsReceived())
                    .addValue("clinicLocation", req.clinicLocation())
                    .addValue("referralSource", req.referralSource())
                    .addValue("ratingExplanation", rating(req.ratingExplanation()))
                    .addValue("ratingHygiene", rating(req.ratingHygiene()))
                    .addValue("ratingCourtesy", rating(req.ratingCourtesy()))
                    .addValue("ratingComfort", rating(req.ratingComfort()))
                    .addValue("ratingRecommend", rating(req.ratingRecommend()))
                    .addValue("message", req.message()));
            return findFeedback(scope, id);
        });
    }

    private ClinicOperationsModels.FeedbackRow findFeedback(ClinicScope scope, UUID id) {
        List<ClinicOperationsModels.FeedbackRow> rows = jdbc.query("""
                select f.id, f."organizationId", f."projectId", f."patientId",
                       f."patientName", f."patientEmail", f."patientPhone", f."patientAge",
                       f."doctorId", f."doctorName", f."serviceName", f."treatmentsReceived",
                       f."clinicLocation", f."referralSource",
                       f."ratingExplanation", f."ratingHygiene", f."ratingCourtesy",
                       f."ratingComfort", f."ratingRecommend", f."message",
                       f."submittedAt", f."createdAt"
                  from clinic_feedback f
                 where %s and f.id = :id
                """.formatted(scope.predicate("f")),
                scope.bind().addValue("id", id),
                ClinicOperationsRepository::mapFeedback);
        return rows.stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Resource not found"));
    }

    public boolean deleteFeedback(ClinicScope scope, UUID id) {
        return scope.read(() -> jdbc.update("""
                delete from clinic_feedback f
                 where %s and f.id = :id
                """.formatted(scope.predicate("f")),
                scope.bind().addValue("id", id)) > 0);
    }

    // ------------------------------------------------------------------
    // Doctor Commission & Payouts
    // ------------------------------------------------------------------

    public List<ClinicOperationsModels.DoctorCommissionRow> doctorCommission(
            ClinicScope scope, LocalDateTime from, LocalDateTime to) {
        return scope.read(() -> jdbc.query("""
                select d.id as "doctorId", d."name" as "doctorName", d."specialty",
                       coalesce(d."commission", 0) as "commissionRate",
                       count(a.id) as "appointmentCount",
                       coalesce(sum(coalesce(a."price", 0) * a."quantity"), 0) as "grossRevenue",
                       coalesce(sum(coalesce(a."price", 0) * a."quantity") * coalesce(d."commission", 0) / 100.0, 0) as "commissionAmount",
                       coalesce((select sum(p."commissionAmount")
                                   from clinic_doctor_payouts p
                                  where p."organizationId" = d."organizationId"
                                    and p."projectId" = d."projectId"
                                    and p."doctorId" = d.id
                                    and p."status" = 'PAID'
                                    and p."periodEnd" >= cast(:from as date)
                                    and p."periodStart" <= cast(:to as date)), 0) as "paidAmount",
                       coalesce((select cp."primaryCurrency"
                                   from "clinic_policies" cp
                                  where cp."organizationId" = d."organizationId"
                                    and cp."projectId" = d."projectId"), 'JOD') as "currency"
                  from "doctors" d
                  left join "appointments" a
                         on a."doctorId" = d.id
                        and a."organizationId" = d."organizationId"
                        and a."projectId" = d."projectId"
                        and a."status" = 'COMPLETED'
                        and a."appointmentDate" >= :from
                        and a."appointmentDate" < :to
                 where %s and d."deletedAt" is null
                 group by d.id, d."name", d."specialty", d."commission", d."organizationId", d."projectId"
                 order by "commissionAmount" desc, d."name"
                """.formatted(scope.predicate("d")),
                scope.bind()
                        .addValue("from", Timestamp.valueOf(from))
                        .addValue("to", Timestamp.valueOf(to)),
                (rs, rowNum) -> {
                    BigDecimal rate = scaled(rs.getBigDecimal("commissionRate"));
                    BigDecimal gross = scaled(rs.getBigDecimal("grossRevenue"));
                    BigDecimal commission = scaled(rs.getBigDecimal("commissionAmount"));
                    BigDecimal paid = scaled(rs.getBigDecimal("paidAmount"));
                    return new ClinicOperationsModels.DoctorCommissionRow(
                            rs.getObject("doctorId", UUID.class),
                            rs.getString("doctorName"),
                            rs.getString("specialty"),
                            rate,
                            rs.getInt("appointmentCount"),
                            gross,
                            commission,
                            paid,
                            commission.subtract(paid),
                            rs.getString("currency"));
                }));
    }

    public List<ClinicOperationsModels.DoctorPayoutRow> listPayouts(
            ClinicScope scope, UUID doctorId, String status) {
        return scope.read(() -> {
            StringBuilder sql = new StringBuilder("""
                    select p.id, p."organizationId", p."projectId", p."doctorId",
                           coalesce(d."name", 'Doctor') as "doctorName",
                           p."periodStart", p."periodEnd", p."grossAmount", p."commissionRate",
                           p."commissionAmount", p."currency", p."status", p."paidAt",
                           p."notes", p."createdAt", p."updatedAt"
                      from clinic_doctor_payouts p
                      left join "doctors" d on d."id" = p."doctorId" and d."organizationId" = p."organizationId"
                     where %s
                    """.formatted(scope.predicate("p")));
            MapSqlParameterSource params = scope.bind();
            if (doctorId != null) {
                sql.append(" and p.\"doctorId\" = :doctorId");
                params.addValue("doctorId", doctorId);
            }
            if (status != null && !status.isBlank()) {
                sql.append(" and p.\"status\" = :status");
                params.addValue("status", status.trim().toUpperCase());
            }
            sql.append(" order by p.\"periodStart\" desc, p.\"createdAt\" desc");
            return jdbc.query(sql.toString(), params, ClinicOperationsRepository::mapPayout);
        });
    }

    public ClinicOperationsModels.DoctorPayoutRow createPayout(
            ClinicScope scope,
            ClinicOperationsModels.CreateDoctorPayoutRequest req,
            UUID createdBy) {
        if (!scope.isProjectScoped()) {
            throw new ValidationFailedException("A payout must belong to one clinic.");
        }
        return scope.read(() -> {
            List<ClinicOperationsModels.DoctorPayoutRow> existing = jdbc.query("""
                    select p.id, p."organizationId", p."projectId", p."doctorId",
                           d."name" as "doctorName",
                           p."periodStart", p."periodEnd", p."grossAmount", p."commissionRate",
                           p."commissionAmount", p."currency", p."status", p."paidAt",
                           p."notes", p."createdAt", p."updatedAt"
                      from clinic_doctor_payouts p
                      left join "doctors" d on d."id" = p."doctorId" and d."organizationId" = p."organizationId"
                     where p."organizationId" = :orgId and p."projectId" = :projId
                       and p."doctorId" = :doctorId
                       and p."periodStart" = :periodStart and p."periodEnd" = :periodEnd
                    """, new MapSqlParameterSource()
                    .addValue("orgId", scope.queryScope().organizationId())
                    .addValue("projId", scope.projectId())
                    .addValue("doctorId", req.doctorId())
                    .addValue("periodStart", java.sql.Date.valueOf(req.periodStart()))
                    .addValue("periodEnd", java.sql.Date.valueOf(req.periodEnd())),
                    ClinicOperationsRepository::mapPayout);
            if (!existing.isEmpty()) {
                throw new ValidationFailedException(
                        "A payout already exists for this doctor and period.",
                        java.util.Map.of("payoutId", existing.get(0).id()));
            }

            List<MapSqlParameterSource> doctors = jdbc.query("""
                    select d.id as "doctorId", coalesce(d."commission", 0) as "commissionRate",
                           coalesce((select sum(coalesce(a."price", 0) * a."quantity")
                                       from "appointments" a
                                      where a."doctorId" = d.id
                                        and a."organizationId" = d."organizationId"
                                        and a."projectId" = d."projectId"
                                        and a."status" = 'COMPLETED'
                                        and a."appointmentDate" >= :from
                                        and a."appointmentDate" < :to), 0) as "grossAmount",
                           coalesce((select cp."primaryCurrency"
                                       from "clinic_policies" cp
                                      where cp."organizationId" = d."organizationId"
                                        and cp."projectId" = d."projectId"), 'JOD') as "currency"
                      from "doctors" d
                     where d."organizationId" = :orgId and d."projectId" = :projId
                       and d.id = :doctorId and d."deletedAt" is null
                    """, new MapSqlParameterSource()
                    .addValue("orgId", scope.queryScope().organizationId())
                    .addValue("projId", scope.projectId())
                    .addValue("doctorId", req.doctorId())
                    .addValue("from", Timestamp.valueOf(req.periodStart().atStartOfDay()))
                    .addValue("to", Timestamp.valueOf(req.periodEnd().plusDays(1).atStartOfDay())),
                    (rs, rowNum) -> new MapSqlParameterSource()
                            .addValue("rate", scaled(rs.getBigDecimal("commissionRate")))
                            .addValue("gross", scaled(rs.getBigDecimal("grossAmount")))
                            .addValue("currency", rs.getString("currency")));
            if (doctors.isEmpty()) {
                throw new ResourceNotFoundException("Resource not found");
            }
            BigDecimal rate = (BigDecimal) doctors.get(0).getValue("rate");
            BigDecimal gross = (BigDecimal) doctors.get(0).getValue("gross");
            String currency = (String) doctors.get(0).getValue("currency");
            BigDecimal commission = gross
                    .multiply(rate)
                    .divide(new BigDecimal("100"), 3, RoundingMode.HALF_UP);

            UUID id = UUID.randomUUID();
            jdbc.update("""
                    insert into clinic_doctor_payouts
                        (id, "organizationId", "projectId", "doctorId", "periodStart", "periodEnd",
                         "grossAmount", "commissionRate", "commissionAmount", "currency",
                         "status", "notes", "createdByUserId")
                    values
                        (:id, :orgId, :projId, :doctorId, :periodStart, :periodEnd,
                         :grossAmount, :commissionRate, :commissionAmount, :currency,
                         'PENDING', :notes, :createdBy)
                    """, new MapSqlParameterSource()
                    .addValue("id", id)
                    .addValue("orgId", scope.queryScope().organizationId())
                    .addValue("projId", scope.projectId())
                    .addValue("doctorId", req.doctorId())
                    .addValue("periodStart", java.sql.Date.valueOf(req.periodStart()))
                    .addValue("periodEnd", java.sql.Date.valueOf(req.periodEnd()))
                    .addValue("grossAmount", gross)
                    .addValue("commissionRate", rate)
                    .addValue("commissionAmount", commission)
                    .addValue("currency", currency)
                    .addValue("notes", req.notes())
                    .addValue("createdBy", createdBy));

            return findPayout(scope, id);
        });
    }

    public ClinicOperationsModels.DoctorPayoutRow updatePayoutStatus(
            ClinicScope scope, UUID id, String status) {
        String normalized = status.trim().toUpperCase();
        return scope.read(() -> {
            int updated = jdbc.update("""
                    update clinic_doctor_payouts p
                       set "status" = :status,
                           "paidAt" = case when :status = 'PAID' then (now() at time zone 'utc') else null end,
                           "updatedAt" = (now() at time zone 'utc')
                     where %s and p.id = :id
                    """.formatted(scope.predicate("p")),
                    scope.bind().addValue("id", id).addValue("status", normalized));
            if (updated == 0) {
                throw new ResourceNotFoundException("Resource not found");
            }
            return findPayout(scope, id);
        });
    }

    private ClinicOperationsModels.DoctorPayoutRow findPayout(ClinicScope scope, UUID id) {
        List<ClinicOperationsModels.DoctorPayoutRow> rows = jdbc.query("""
                select p.id, p."organizationId", p."projectId", p."doctorId",
                       coalesce(d."name", 'Doctor') as "doctorName",
                       p."periodStart", p."periodEnd", p."grossAmount", p."commissionRate",
                       p."commissionAmount", p."currency", p."status", p."paidAt",
                       p."notes", p."createdAt", p."updatedAt"
                  from clinic_doctor_payouts p
                  left join "doctors" d on d."id" = p."doctorId" and d."organizationId" = p."organizationId"
                 where %s and p.id = :id
                """.formatted(scope.predicate("p")),
                scope.bind().addValue("id", id),
                ClinicOperationsRepository::mapPayout);
        return rows.stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Resource not found"));
    }

    // ------------------------------------------------------------------
    // Mapping
    // ------------------------------------------------------------------

    private static ClinicOperationsModels.AlignerCaseRow mapAlignerCase(ResultSet rs, int rowNum)
            throws SQLException {
        return new ClinicOperationsModels.AlignerCaseRow(
                rs.getObject("id", UUID.class),
                rs.getObject("organizationId", UUID.class),
                rs.getObject("projectId", UUID.class),
                rs.getObject("patientId", UUID.class),
                rs.getString("patientName"),
                rs.getObject("doctorId", UUID.class),
                rs.getString("doctorName"),
                rs.getString("caseType"),
                rs.getString("impressionType"),
                rs.getString("shade"),
                rs.getString("status"),
                rs.getInt("upperSteps"),
                rs.getInt("lowerSteps"),
                rs.getString("teethSummary"),
                rs.getString("iprSummary"),
                rs.getInt("attachmentsCount"),
                rs.getString("rxNotes"),
                toStringList(rs.getArray("files")),
                toLocalDateTime(rs.getTimestamp("createdAt")),
                toLocalDateTime(rs.getTimestamp("updatedAt")));
    }

    private static ClinicOperationsModels.FeedbackRow mapFeedback(ResultSet rs, int rowNum)
            throws SQLException {
        return new ClinicOperationsModels.FeedbackRow(
                rs.getObject("id", UUID.class),
                rs.getObject("organizationId", UUID.class),
                rs.getObject("projectId", UUID.class),
                rs.getObject("patientId", UUID.class),
                rs.getString("patientName"),
                rs.getString("patientEmail"),
                rs.getString("patientPhone"),
                rs.getObject("patientAge", Integer.class),
                rs.getObject("doctorId", UUID.class),
                rs.getString("doctorName"),
                rs.getString("serviceName"),
                rs.getString("treatmentsReceived"),
                rs.getString("clinicLocation"),
                rs.getString("referralSource"),
                rs.getInt("ratingExplanation"),
                rs.getInt("ratingHygiene"),
                rs.getInt("ratingCourtesy"),
                rs.getInt("ratingComfort"),
                rs.getInt("ratingRecommend"),
                rs.getString("message"),
                toLocalDateTime(rs.getTimestamp("submittedAt")),
                toLocalDateTime(rs.getTimestamp("createdAt")));
    }

    private static ClinicOperationsModels.DoctorPayoutRow mapPayout(ResultSet rs, int rowNum)
            throws SQLException {
        java.sql.Date start = rs.getDate("periodStart");
        java.sql.Date end = rs.getDate("periodEnd");
        return new ClinicOperationsModels.DoctorPayoutRow(
                rs.getObject("id", UUID.class),
                rs.getObject("organizationId", UUID.class),
                rs.getObject("projectId", UUID.class),
                rs.getObject("doctorId", UUID.class),
                rs.getString("doctorName"),
                start != null ? start.toLocalDate() : null,
                end != null ? end.toLocalDate() : null,
                scaled(rs.getBigDecimal("grossAmount")),
                scaled(rs.getBigDecimal("commissionRate")),
                scaled(rs.getBigDecimal("commissionAmount")),
                rs.getString("currency"),
                rs.getString("status"),
                toLocalDateTime(rs.getTimestamp("paidAt")),
                rs.getString("notes"),
                toLocalDateTime(rs.getTimestamp("createdAt")),
                toLocalDateTime(rs.getTimestamp("updatedAt")));
    }

    private static BigDecimal scaled(BigDecimal value) {
        return value != null ? value.setScale(3, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(3);
    }

    private static int rating(Integer value) {
        if (value == null) {
            return 0;
        }
        if (value < 0 || value > 10) {
            throw new ValidationFailedException("A rating must be between 0 and 10.");
        }
        return value;
    }

    private static List<String> toStringList(java.sql.Array array) {
        if (array == null) {
            return List.of();
        }
        try {
            String[] values = (String[]) array.getArray();
            return values != null ? List.of(values) : List.of();
        } catch (SQLException e) {
            return List.of();
        }
    }

    private static LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts != null ? ts.toLocalDateTime() : null;
    }
}
