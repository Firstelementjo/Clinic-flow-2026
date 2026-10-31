package com.firstelement.crm.clinic.treatment;

import com.firstelement.crm.clinic.ClinicScope;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Scoped repository for dental treatment plans and procedure items.
 *
 * <p>All operations are strictly bounded by {@link ClinicScope}.
 */
@Repository
public class TreatmentPlanRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public TreatmentPlanRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Lists all treatment plans for a patient, newest first.
     */
    public List<TreatmentPlanModels.TreatmentPlanHeader> listPlansForPatient(ClinicScope scope, UUID patientId) {
        return scope.read(() -> jdbc.query("""
                select tp."id", tp."organizationId", tp."projectId", tp."patientId",
                       tp."title", tp."status", tp."version", tp."parentPlanId", tp."planType",
                       coalesce(tp."totalDiscount", 0.000) as "totalDiscount",
                       coalesce(tp."insuranceEstimatedCoverage", 0.000) as "insuranceEstimatedCoverage",
                       coalesce(tp."patientEstimatedPortion", 0.000) as "patientEstimatedPortion",
                       tp."patientConsent", tp."consentAt",
                       tp."consentByUserId", u."fullName" as "consentRegistrarName",
                       tp."consentNotes", tp."approvedAt", tp."approvedByUserId",
                       u_app."fullName" as "approvedByUserName",
                       tp."cancellationReason", tp."cancelledAt", tp."notes",
                       coalesce(agg."totalEstimatedCost", 0.000) as "totalEstimatedCost",
                       coalesce(agg."totalInvoicedAmount", 0.000) as "totalInvoicedAmount",
                       coalesce(agg."totalCollectedAmount", 0.000) as "totalCollectedAmount",
                       coalesce(agg."itemCount", 0) as "itemCount",
                       coalesce(agg."completedItemCount", 0) as "completedItemCount",
                       tp."createdAt", tp."updatedAt"
                  from "treatment_plans" tp
                  left join "users" u on u."id" = tp."consentByUserId"
                  left join "users" u_app on u_app."id" = tp."approvedByUserId"
                  left join (
                      select "planId",
                             sum("estimatedCost") as "totalEstimatedCost",
                             sum("invoicedAmount") as "totalInvoicedAmount",
                             sum("collectedAmount") as "totalCollectedAmount",
                             count("id") as "itemCount",
                             count("id") filter (where "status" = 'COMPLETED') as "completedItemCount"
                        from "treatment_plan_items"
                       group by "planId"
                  ) agg on agg."planId" = tp."id"
                 where %s
                   and tp."patientId" = :patientId
                 order by tp."createdAt" desc
                """.formatted(scope.predicate("tp")),
                scope.bind().addValue("patientId", patientId),
                TreatmentPlanRepository::mapPlanHeader));
    }

    /**
     * Finds a single treatment plan by ID.
     */
    public Optional<TreatmentPlanModels.TreatmentPlanHeader> findPlan(ClinicScope scope, UUID planId) {
        return scope.read(() -> {
            try {
                return Optional.ofNullable(jdbc.queryForObject("""
                        select tp."id", tp."organizationId", tp."projectId", tp."patientId",
                               tp."title", tp."status", tp."version", tp."parentPlanId", tp."planType",
                               coalesce(tp."totalDiscount", 0.000) as "totalDiscount",
                               coalesce(tp."insuranceEstimatedCoverage", 0.000) as "insuranceEstimatedCoverage",
                               coalesce(tp."patientEstimatedPortion", 0.000) as "patientEstimatedPortion",
                               tp."patientConsent", tp."consentAt",
                               tp."consentByUserId", u."fullName" as "consentRegistrarName",
                               tp."consentNotes", tp."approvedAt", tp."approvedByUserId",
                               u_app."fullName" as "approvedByUserName",
                               tp."cancellationReason", tp."cancelledAt", tp."notes",
                               coalesce(agg."totalEstimatedCost", 0.000) as "totalEstimatedCost",
                               coalesce(agg."totalInvoicedAmount", 0.000) as "totalInvoicedAmount",
                               coalesce(agg."totalCollectedAmount", 0.000) as "totalCollectedAmount",
                               coalesce(agg."itemCount", 0) as "itemCount",
                               coalesce(agg."completedItemCount", 0) as "completedItemCount",
                               tp."createdAt", tp."updatedAt"
                          from "treatment_plans" tp
                          left join "users" u on u."id" = tp."consentByUserId"
                          left join "users" u_app on u_app."id" = tp."approvedByUserId"
                          left join (
                              select "planId",
                                     sum("estimatedCost") as "totalEstimatedCost",
                                     sum("invoicedAmount") as "totalInvoicedAmount",
                                     sum("collectedAmount") as "totalCollectedAmount",
                                     count("id") as "itemCount",
                                     count("id") filter (where "status" = 'COMPLETED') as "completedItemCount"
                                from "treatment_plan_items"
                               group by "planId"
                          ) agg on agg."planId" = tp."id"
                         where %s
                           and tp."id" = :planId
                        """.formatted(scope.predicate("tp")),
                        scope.bind().addValue("planId", planId),
                        TreatmentPlanRepository::mapPlanHeader));
            } catch (EmptyResultDataAccessException e) {
                return Optional.empty();
            }
        });
    }

    /**
     * Lists all procedure items in a plan, ordered by sequenceOrder then createdAt.
     */
    public List<TreatmentPlanModels.TreatmentPlanItemRow> listPlanItems(ClinicScope scope, UUID planId) {
        return scope.read(() -> jdbc.query("""
                select tpi."id", tpi."organizationId", tpi."projectId", tpi."planId",
                       tpi."sequenceOrder", coalesce(tpi."stage", 'PHASE_1') as "stage",
                       coalesce(tpi."plannedVisits", 1) as "plannedVisits",
                       coalesce(tpi."completedVisits", 0) as "completedVisits",
                       tpi."procedureId", tpi."procedureName",
                       tpi."toothFdi", tpi."doctorId", d."name" as "doctorName",
                       tpi."estimatedCost",
                       coalesce(tpi."discountAmount", 0.000) as "discountAmount",
                       coalesce(tpi."insuranceCoverage", 0.000) as "insuranceCoverage",
                       coalesce(tpi."patientPortion", 0.000) as "patientPortion",
                       tpi."invoicedAmount", tpi."collectedAmount",
                       tpi."status", tpi."notes", tpi."outcomeNotes", tpi."completedAt",
                       tpi."createdAt", tpi."updatedAt"
                  from "treatment_plan_items" tpi
                  left join "doctors" d on d."id" = tpi."doctorId"
                 where %s
                   and tpi."planId" = :planId
                 order by tpi."sequenceOrder" asc, tpi."createdAt" asc
                """.formatted(scope.predicate("tpi")),
                scope.bind().addValue("planId", planId),
                TreatmentPlanRepository::mapPlanItem));
    }

    /**
     * Inserts a new treatment plan in DRAFT status.
     */
    public UUID insertPlan(ClinicScope scope, UUID patientId, String title, String notes,
                           int version, UUID parentPlanId, String planType,
                           BigDecimal totalDiscount, BigDecimal insuranceEstimatedCoverage,
                           BigDecimal patientEstimatedPortion) {
        if (!scope.isProjectScoped()) {
            throw new IllegalStateException("A treatment plan must be created inside one clinic; project must be specified.");
        }
        return scope.read(() -> {
            UUID id = UUID.randomUUID();
            MapSqlParameterSource params = scope.bind()
                    .addValue("id", id)
                    .addValue("org", scope.queryScope().organizationId())
                    .addValue("proj", scope.projectId())
                    .addValue("patientId", patientId)
                    .addValue("title", title)
                    .addValue("notes", notes)
                    .addValue("version", version > 0 ? version : 1)
                    .addValue("parentPlanId", parentPlanId)
                    .addValue("planType", planType != null ? planType : "PRIMARY")
                    .addValue("totalDiscount", totalDiscount != null ? totalDiscount : BigDecimal.ZERO)
                    .addValue("insuranceEstimatedCoverage", insuranceEstimatedCoverage != null ? insuranceEstimatedCoverage : BigDecimal.ZERO)
                    .addValue("patientEstimatedPortion", patientEstimatedPortion != null ? patientEstimatedPortion : BigDecimal.ZERO);
            jdbc.update("""
                    insert into "treatment_plans"
                        ("id", "organizationId", "projectId", "patientId", "title", "status",
                         "version", "parentPlanId", "planType", "totalDiscount",
                         "insuranceEstimatedCoverage", "patientEstimatedPortion",
                         "patientConsent", "notes", "createdAt", "updatedAt")
                    values
                        (:id, :org, :proj, :patientId, :title, 'DRAFT',
                         :version, :parentPlanId, :planType, :totalDiscount,
                         :insuranceEstimatedCoverage, :patientEstimatedPortion,
                         false, :notes, (current_timestamp at time zone 'utc'), (current_timestamp at time zone 'utc'))
                    """, params);
            return id;
        });
    }

    /**
     * Updates plan status and notes.
     */
    public boolean updatePlanStatus(ClinicScope scope, UUID planId, String status, String notes) {
        return scope.read(() -> {
            MapSqlParameterSource params = scope.bind()
                    .addValue("planId", planId)
                    .addValue("status", status)
                    .addValue("notes", notes);
            int rows = jdbc.update("""
                    update "treatment_plans" tp
                       set "status" = :status,
                           "notes" = coalesce(:notes, tp."notes"),
                           "updatedAt" = (current_timestamp at time zone 'utc')
                     where %s
                       and tp."id" = :planId
                    """.formatted(scope.predicate("tp")), params);
            return rows > 0;
        });
    }

    /**
     * Approves an active treatment plan with patient consent.
     */
    public boolean approvePlan(ClinicScope scope, UUID planId, UUID approverUserId, String consentNotes) {
        return scope.read(() -> {
            MapSqlParameterSource params = scope.bind()
                    .addValue("planId", planId)
                    .addValue("userId", approverUserId)
                    .addValue("notes", consentNotes);
            int rows = jdbc.update("""
                    update "treatment_plans" tp
                       set "status" = 'ACTIVE',
                           "patientConsent" = true,
                           "consentAt" = (current_timestamp at time zone 'utc'),
                           "consentByUserId" = :userId,
                           "consentNotes" = coalesce(:notes, tp."consentNotes"),
                           "approvedAt" = (current_timestamp at time zone 'utc'),
                           "approvedByUserId" = :userId,
                           "updatedAt" = (current_timestamp at time zone 'utc')
                     where %s
                       and tp."id" = :planId
                    """.formatted(scope.predicate("tp")), params);
            return rows > 0;
        });
    }

    /**
     * Cancels a treatment plan with recorded cancellation reason and timestamp.
     */
    public boolean cancelPlan(ClinicScope scope, UUID planId, String cancellationReason) {
        return scope.read(() -> {
            MapSqlParameterSource params = scope.bind()
                    .addValue("planId", planId)
                    .addValue("reason", cancellationReason);
            int rows = jdbc.update("""
                    update "treatment_plans" tp
                       set "status" = 'CANCELLED',
                           "cancellationReason" = :reason,
                           "cancelledAt" = (current_timestamp at time zone 'utc'),
                           "updatedAt" = (current_timestamp at time zone 'utc')
                     where %s
                       and tp."id" = :planId
                    """.formatted(scope.predicate("tp")), params);
            return rows > 0;
        });
    }

    /**
     * Records or updates patient consent (TRT-04).
     */
    public boolean recordConsent(ClinicScope scope, UUID planId, boolean consent, UUID consentByUserId, String consentNotes) {
        return scope.read(() -> {
            MapSqlParameterSource params = scope.bind()
                    .addValue("planId", planId)
                    .addValue("consent", consent)
                    .addValue("userId", consentByUserId)
                    .addValue("notes", consentNotes);
            int rows = jdbc.update("""
                    update "treatment_plans" tp
                       set "patientConsent" = :consent,
                           "consentAt" = case when :consent then (current_timestamp at time zone 'utc') else null end,
                           "consentByUserId" = case when :consent then :userId else null end,
                           "consentNotes" = :notes,
                           "updatedAt" = (current_timestamp at time zone 'utc')
                     where %s
                       and tp."id" = :planId
                    """.formatted(scope.predicate("tp")), params);
            return rows > 0;
        });
    }

    /**
     * Inserts an item into a treatment plan.
     */
    public UUID insertItem(ClinicScope scope, UUID planId, int sequenceOrder, String stage,
                           int plannedVisits, UUID procedureId, String procedureName,
                           Integer toothFdi, UUID doctorId, BigDecimal estimatedCost,
                           BigDecimal discountAmount, BigDecimal insuranceCoverage,
                           BigDecimal patientPortion, String notes) {
        if (!scope.isProjectScoped()) {
            throw new IllegalStateException("Treatment plan item must belong to a scoped project.");
        }
        return scope.read(() -> {
            UUID id = UUID.randomUUID();
            MapSqlParameterSource params = scope.bind()
                    .addValue("id", id)
                    .addValue("org", scope.queryScope().organizationId())
                    .addValue("proj", scope.projectId())
                    .addValue("planId", planId)
                    .addValue("sequenceOrder", sequenceOrder > 0 ? sequenceOrder : 1)
                    .addValue("stage", stage != null ? stage : "PHASE_1")
                    .addValue("plannedVisits", plannedVisits > 0 ? plannedVisits : 1)
                    .addValue("procedureId", procedureId)
                    .addValue("procedureName", procedureName)
                    .addValue("toothFdi", toothFdi)
                    .addValue("doctorId", doctorId)
                    .addValue("estimatedCost", estimatedCost != null ? estimatedCost : BigDecimal.ZERO)
                    .addValue("discountAmount", discountAmount != null ? discountAmount : BigDecimal.ZERO)
                    .addValue("insuranceCoverage", insuranceCoverage != null ? insuranceCoverage : BigDecimal.ZERO)
                    .addValue("patientPortion", patientPortion != null ? patientPortion : BigDecimal.ZERO)
                    .addValue("notes", notes);
            jdbc.update("""
                    insert into "treatment_plan_items"
                        ("id", "organizationId", "projectId", "planId", "sequenceOrder",
                         "stage", "plannedVisits", "completedVisits",
                         "procedureId", "procedureName", "toothFdi", "doctorId",
                         "estimatedCost", "discountAmount", "insuranceCoverage", "patientPortion",
                         "invoicedAmount", "collectedAmount", "status",
                         "notes", "createdAt", "updatedAt")
                    values
                        (:id, :org, :proj, :planId, :sequenceOrder,
                         :stage, :plannedVisits, 0,
                         :procedureId, :procedureName, :toothFdi, :doctorId,
                         :estimatedCost, :discountAmount, :insuranceCoverage, :patientPortion,
                         0.000, 0.000, 'PROPOSED',
                         :notes, (current_timestamp at time zone 'utc'), (current_timestamp at time zone 'utc'))
                    """, params);
            return id;
        });
    }

    /**
     * Updates an existing treatment plan item.
     */
    public boolean updateItem(ClinicScope scope, UUID itemId, Integer sequenceOrder, String stage,
                              Integer plannedVisits, Integer completedVisits, UUID procedureId,
                              String procedureName, Integer toothFdi, UUID doctorId,
                              BigDecimal estimatedCost, BigDecimal discountAmount,
                              BigDecimal insuranceCoverage, BigDecimal patientPortion,
                              BigDecimal invoicedAmount, BigDecimal collectedAmount,
                              String status, String notes, String outcomeNotes) {
        return scope.read(() -> {
            boolean isCompleted = "COMPLETED".equalsIgnoreCase(status);
            MapSqlParameterSource params = scope.bind()
                    .addValue("itemId", itemId)
                    .addValue("sequenceOrder", sequenceOrder)
                    .addValue("stage", stage)
                    .addValue("plannedVisits", plannedVisits)
                    .addValue("completedVisits", completedVisits)
                    .addValue("procedureId", procedureId)
                    .addValue("procedureName", procedureName)
                    .addValue("toothFdi", toothFdi)
                    .addValue("doctorId", doctorId)
                    .addValue("estimatedCost", estimatedCost != null ? estimatedCost : BigDecimal.ZERO)
                    .addValue("discountAmount", discountAmount != null ? discountAmount : BigDecimal.ZERO)
                    .addValue("insuranceCoverage", insuranceCoverage != null ? insuranceCoverage : BigDecimal.ZERO)
                    .addValue("patientPortion", patientPortion != null ? patientPortion : BigDecimal.ZERO)
                    .addValue("invoicedAmount", invoicedAmount != null ? invoicedAmount : BigDecimal.ZERO)
                    .addValue("collectedAmount", collectedAmount != null ? collectedAmount : BigDecimal.ZERO)
                    .addValue("status", status)
                    .addValue("notes", notes)
                    .addValue("outcomeNotes", outcomeNotes)
                    .addValue("isCompleted", isCompleted);
            int rows = jdbc.update("""
                    update "treatment_plan_items" tpi
                       set "sequenceOrder" = coalesce(:sequenceOrder, tpi."sequenceOrder"),
                           "stage" = coalesce(:stage, tpi."stage"),
                           "plannedVisits" = coalesce(:plannedVisits, tpi."plannedVisits"),
                           "completedVisits" = coalesce(:completedVisits, tpi."completedVisits"),
                           "procedureId" = :procedureId,
                           "procedureName" = coalesce(:procedureName, tpi."procedureName"),
                           "toothFdi" = :toothFdi,
                           "doctorId" = :doctorId,
                           "estimatedCost" = coalesce(:estimatedCost, tpi."estimatedCost"),
                           "discountAmount" = coalesce(:discountAmount, tpi."discountAmount"),
                           "insuranceCoverage" = coalesce(:insuranceCoverage, tpi."insuranceCoverage"),
                           "patientPortion" = coalesce(:patientPortion, tpi."patientPortion"),
                           "invoicedAmount" = coalesce(:invoicedAmount, tpi."invoicedAmount"),
                           "collectedAmount" = coalesce(:collectedAmount, tpi."collectedAmount"),
                           "status" = coalesce(:status, tpi."status"),
                           "notes" = coalesce(:notes, tpi."notes"),
                           "outcomeNotes" = coalesce(:outcomeNotes, tpi."outcomeNotes"),
                           "completedAt" = case
                               when :isCompleted then coalesce(tpi."completedAt", (current_timestamp at time zone 'utc'))
                               else null
                           end,
                           "updatedAt" = (current_timestamp at time zone 'utc')
                     where %s
                       and tpi."id" = :itemId
                    """.formatted(scope.predicate("tpi")), params);
            return rows > 0;
        });
    }

    /**
     * Deletes a plan item.
     */
    public boolean deleteItem(ClinicScope scope, UUID itemId) {
        return scope.read(() -> {
            MapSqlParameterSource params = scope.bind().addValue("itemId", itemId);
            int rows = jdbc.update("""
                    delete from "treatment_plan_items" tpi
                     where %s
                       and tpi."id" = :itemId
                    """.formatted(scope.predicate("tpi")), params);
            return rows > 0;
        });
    }

    /**
     * Deletes an entire treatment plan.
     */
    public boolean deletePlan(ClinicScope scope, UUID planId) {
        return scope.read(() -> {
            MapSqlParameterSource params = scope.bind().addValue("planId", planId);
            int rows = jdbc.update("""
                    delete from "treatment_plans" tp
                     where %s
                       and tp."id" = :planId
                    """.formatted(scope.predicate("tp")), params);
            return rows > 0;
        });
    }

    private static java.time.LocalDateTime toLocalDateTime(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    private static TreatmentPlanModels.TreatmentPlanHeader mapPlanHeader(ResultSet rs, int rowNum) throws SQLException {
        return new TreatmentPlanModels.TreatmentPlanHeader(
                rs.getObject("id", UUID.class),
                rs.getObject("organizationId", UUID.class),
                rs.getObject("projectId", UUID.class),
                rs.getObject("patientId", UUID.class),
                rs.getString("title"),
                rs.getString("status"),
                rs.getInt("version"),
                rs.getObject("parentPlanId", UUID.class),
                rs.getString("planType"),
                rs.getBigDecimal("totalDiscount"),
                rs.getBigDecimal("insuranceEstimatedCoverage"),
                rs.getBigDecimal("patientEstimatedPortion"),
                rs.getBoolean("patientConsent"),
                toLocalDateTime(rs.getTimestamp("consentAt")),
                rs.getObject("consentByUserId", UUID.class),
                rs.getString("consentRegistrarName"),
                rs.getString("consentNotes"),
                toLocalDateTime(rs.getTimestamp("approvedAt")),
                rs.getObject("approvedByUserId", UUID.class),
                rs.getString("approvedByUserName"),
                rs.getString("cancellationReason"),
                toLocalDateTime(rs.getTimestamp("cancelledAt")),
                rs.getString("notes"),
                rs.getBigDecimal("totalEstimatedCost"),
                rs.getBigDecimal("totalInvoicedAmount"),
                rs.getBigDecimal("totalCollectedAmount"),
                rs.getInt("itemCount"),
                rs.getInt("completedItemCount"),
                toLocalDateTime(rs.getTimestamp("createdAt")),
                toLocalDateTime(rs.getTimestamp("updatedAt")));
    }

    private static TreatmentPlanModels.TreatmentPlanItemRow mapPlanItem(ResultSet rs, int rowNum) throws SQLException {
        return new TreatmentPlanModels.TreatmentPlanItemRow(
                rs.getObject("id", UUID.class),
                rs.getObject("organizationId", UUID.class),
                rs.getObject("projectId", UUID.class),
                rs.getObject("planId", UUID.class),
                rs.getInt("sequenceOrder"),
                rs.getString("stage"),
                rs.getInt("plannedVisits"),
                rs.getInt("completedVisits"),
                rs.getObject("procedureId", UUID.class),
                rs.getString("procedureName"),
                rs.getObject("toothFdi", Integer.class),
                rs.getObject("doctorId", UUID.class),
                rs.getString("doctorName"),
                rs.getBigDecimal("estimatedCost"),
                rs.getBigDecimal("discountAmount"),
                rs.getBigDecimal("insuranceCoverage"),
                rs.getBigDecimal("patientPortion"),
                rs.getBigDecimal("invoicedAmount"),
                rs.getBigDecimal("collectedAmount"),
                rs.getString("status"),
                rs.getString("notes"),
                rs.getString("outcomeNotes"),
                toLocalDateTime(rs.getTimestamp("completedAt")),
                toLocalDateTime(rs.getTimestamp("createdAt")),
                toLocalDateTime(rs.getTimestamp("updatedAt")));
    }
}
