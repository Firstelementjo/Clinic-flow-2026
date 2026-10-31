package com.firstelement.crm.clinic.encounter;

import com.firstelement.crm.clinic.ClinicScope;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Scoped repository for dental clinical encounters, prescriptions, vitals,
 * immutable signed notes, amendments, and patient medical history (CLN-01 to CLN-09).
 */
@Repository
public class EncounterRepository {

    private static final String MASKED_TEXT = "[محجوب - مصرح للكوادر الطبية فقط]";

    private final NamedParameterJdbcTemplate jdbc;

    public EncounterRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Lists all encounters for a patient, ordered by visitDate descending.
     */
    public List<EncounterModels.EncounterHeader> listEncountersForPatient(
            ClinicScope scope, UUID patientId, boolean maskMedicalData) {
        return scope.read(() -> jdbc.query("""
                select ce."id", ce."organizationId", ce."projectId", ce."patientId",
                       p."displayName" as "patientName", p."patientNo",
                       ce."appointmentId", ce."doctorId", d."name" as "doctorName",
                       ce."treatmentPlanId", tp."title" as "treatmentPlanTitle",
                       ce."treatmentPlanItemId", tpi."procedureName" as "treatmentPlanProcedureName",
                       ce."chiefComplaint", ce."examinationNotes", ce."diagnosis",
                       ce."diagnosisUpdatedAt", ce."diagnosisUpdatedBy", u."fullName" as "diagnosisUpdatedByName",
                       ce."clinicalNotes", ce."performedProcedures",
                       ce."toothFdi", ce."toothLocation", ce."status",
                       ce."visitDate", ce."durationMinutes",
                       ce."signedAt", ce."signedByUserId", u_sign."fullName" as "signedByUserName",
                       ce."signatureHash", ce."bloodPressureSystolic", ce."bloodPressureDiastolic",
                       ce."pulseRate", ce."temperatureC",
                       coalesce(presc.rx_count, 0) as "prescriptionCount",
                       ce."createdAt", ce."updatedAt"
                  from "clinical_encounters" ce
                  inner join "patients" p on p."id" = ce."patientId"
                  left join "doctors" d on d."id" = ce."doctorId"
                  left join "treatment_plans" tp on tp."id" = ce."treatmentPlanId"
                  left join "treatment_plan_items" tpi on tpi."id" = ce."treatmentPlanItemId"
                  left join "users" u on u."id" = ce."diagnosisUpdatedBy"
                  left join "users" u_sign on u_sign."id" = ce."signedByUserId"
                  left join (
                      select "encounterId", count("id") as rx_count
                        from "clinical_prescriptions"
                       group by "encounterId"
                  ) presc on presc."encounterId" = ce."id"
                 where %s
                   and ce."patientId" = :patientId
                 order by ce."visitDate" desc, ce."createdAt" desc
                """.formatted(scope.predicate("ce")),
                scope.bind().addValue("patientId", patientId),
                (rs, rowNum) -> mapEncounterHeader(rs, maskMedicalData)));
    }

    /**
     * Finds a single encounter by ID.
     */
    public Optional<EncounterModels.EncounterHeader> findEncounter(
            ClinicScope scope, UUID encounterId, boolean maskMedicalData) {
        return scope.read(() -> {
            try {
                return Optional.ofNullable(jdbc.queryForObject("""
                        select ce."id", ce."organizationId", ce."projectId", ce."patientId",
                               p."displayName" as "patientName", p."patientNo",
                               ce."appointmentId", ce."doctorId", d."name" as "doctorName",
                               ce."treatmentPlanId", tp."title" as "treatmentPlanTitle",
                               ce."treatmentPlanItemId", tpi."procedureName" as "treatmentPlanProcedureName",
                               ce."chiefComplaint", ce."examinationNotes", ce."diagnosis",
                               ce."diagnosisUpdatedAt", ce."diagnosisUpdatedBy", u."fullName" as "diagnosisUpdatedByName",
                               ce."clinicalNotes", ce."performedProcedures",
                               ce."toothFdi", ce."toothLocation", ce."status",
                               ce."visitDate", ce."durationMinutes",
                               ce."signedAt", ce."signedByUserId", u_sign."fullName" as "signedByUserName",
                               ce."signatureHash", ce."bloodPressureSystolic", ce."bloodPressureDiastolic",
                               ce."pulseRate", ce."temperatureC",
                               coalesce(presc.rx_count, 0) as "prescriptionCount",
                               ce."createdAt", ce."updatedAt"
                          from "clinical_encounters" ce
                          inner join "patients" p on p."id" = ce."patientId"
                          left join "doctors" d on d."id" = ce."doctorId"
                          left join "treatment_plans" tp on tp."id" = ce."treatmentPlanId"
                          left join "treatment_plan_items" tpi on tpi."id" = ce."treatmentPlanItemId"
                          left join "users" u on u."id" = ce."diagnosisUpdatedBy"
                          left join "users" u_sign on u_sign."id" = ce."signedByUserId"
                          left join (
                              select "encounterId", count("id") as rx_count
                                from "clinical_prescriptions"
                               group by "encounterId"
                          ) presc on presc."encounterId" = ce."id"
                         where %s
                           and ce."id" = :encounterId
                        """.formatted(scope.predicate("ce")),
                        scope.bind().addValue("encounterId", encounterId),
                        (rs, rowNum) -> mapEncounterHeader(rs, maskMedicalData)));
            } catch (EmptyResultDataAccessException e) {
                return Optional.empty();
            }
        });
    }

    /**
     * Inserts a new clinical encounter visit record with optional vitals.
     */
    public UUID insertEncounter(
            ClinicScope scope,
            UUID patientId,
            UUID doctorId,
            UUID appointmentId,
            UUID treatmentPlanId,
            UUID treatmentPlanItemId,
            String chiefComplaint,
            String examinationNotes,
            String diagnosis,
            String clinicalNotes,
            String performedProcedures,
            Integer toothFdi,
            String toothLocation,
            String status,
            LocalDateTime visitDate,
            int durationMinutes,
            Integer bpSystolic,
            Integer bpDiastolic,
            Integer pulseRate,
            BigDecimal temperatureC) {
        if (!scope.isProjectScoped()) {
            throw new IllegalStateException("An encounter must be recorded inside a scoped clinic project.");
        }
        return scope.read(() -> {
            UUID id = UUID.randomUUID();
            MapSqlParameterSource params = scope.bind()
                    .addValue("id", id)
                    .addValue("org", scope.queryScope().organizationId())
                    .addValue("proj", scope.projectId())
                    .addValue("patientId", patientId)
                    .addValue("doctorId", doctorId)
                    .addValue("appointmentId", appointmentId)
                    .addValue("treatmentPlanId", treatmentPlanId)
                    .addValue("treatmentPlanItemId", treatmentPlanItemId)
                    .addValue("chiefComplaint", chiefComplaint)
                    .addValue("examinationNotes", examinationNotes)
                    .addValue("diagnosis", diagnosis)
                    .addValue("clinicalNotes", clinicalNotes)
                    .addValue("performedProcedures", performedProcedures)
                    .addValue("toothFdi", toothFdi)
                    .addValue("toothLocation", toothLocation)
                    .addValue("status", status != null ? status : "COMPLETED")
                    .addValue("visitDate", visitDate != null ? Timestamp.valueOf(visitDate) : Timestamp.valueOf(LocalDateTime.now()))
                    .addValue("durationMinutes", durationMinutes > 0 ? durationMinutes : 30)
                    .addValue("bpSys", bpSystolic)
                    .addValue("bpDia", bpDiastolic)
                    .addValue("pulse", pulseRate)
                    .addValue("temp", temperatureC);

            jdbc.update("""
                    insert into "clinical_encounters" (
                        "id", "organizationId", "projectId", "patientId", "doctorId",
                        "appointmentId", "treatmentPlanId", "treatmentPlanItemId",
                        "chiefComplaint", "examinationNotes", "diagnosis", "clinicalNotes",
                        "performedProcedures", "toothFdi", "toothLocation", "status",
                        "visitDate", "durationMinutes",
                        "bloodPressureSystolic", "bloodPressureDiastolic", "pulseRate", "temperatureC"
                    ) values (
                        :id, :org, :proj, :patientId, :doctorId,
                        :appointmentId, :treatmentPlanId, :treatmentPlanItemId,
                        :chiefComplaint, :examinationNotes, :diagnosis, :clinicalNotes,
                        :performedProcedures, :toothFdi, :toothLocation, :status,
                        :visitDate, :durationMinutes,
                        :bpSys, :bpDia, :pulse, :temp
                    )
                    """, params);
            return id;
        });
    }

    /**
     * Updates diagnosis and clinical notes by attending clinician (CLN-02).
     */
    public boolean updateDiagnosis(
            ClinicScope scope,
            UUID encounterId,
            String diagnosis,
            String clinicalNotes,
            String examinationNotes,
            String performedProcedures,
            Integer toothFdi,
            String toothLocation,
            Integer bpSys,
            Integer bpDia,
            Integer pulse,
            BigDecimal temp,
            UUID updatedByUserId) {
        return scope.read(() -> {
            MapSqlParameterSource params = scope.bind()
                    .addValue("encounterId", encounterId)
                    .addValue("diagnosis", diagnosis)
                    .addValue("clinicalNotes", clinicalNotes)
                    .addValue("examinationNotes", examinationNotes)
                    .addValue("performedProcedures", performedProcedures)
                    .addValue("toothFdi", toothFdi)
                    .addValue("toothLocation", toothLocation)
                    .addValue("bpSys", bpSys)
                    .addValue("bpDia", bpDia)
                    .addValue("pulse", pulse)
                    .addValue("temp", temp)
                    .addValue("updatedByUserId", updatedByUserId)
                    .addValue("now", Timestamp.valueOf(LocalDateTime.now()));

            int rows = jdbc.update("""
                    update "clinical_encounters" ce
                       set "diagnosis" = :diagnosis,
                           "clinicalNotes" = :clinicalNotes,
                           "examinationNotes" = :examinationNotes,
                           "performedProcedures" = :performedProcedures,
                           "toothFdi" = :toothFdi,
                           "toothLocation" = :toothLocation,
                           "bloodPressureSystolic" = coalesce(:bpSys, ce."bloodPressureSystolic"),
                           "bloodPressureDiastolic" = coalesce(:bpDia, ce."bloodPressureDiastolic"),
                           "pulseRate" = coalesce(:pulse, ce."pulseRate"),
                           "temperatureC" = coalesce(:temp, ce."temperatureC"),
                           "diagnosisUpdatedAt" = :now,
                           "diagnosisUpdatedBy" = :updatedByUserId,
                           "updatedAt" = :now
                     where %s
                       and ce."id" = :encounterId
                       and ce."signedAt" is null
                    """.formatted(scope.predicate("ce")), params);
            return rows > 0;
        });
    }

    /**
     * Digitally signs an encounter note. Irreversible.
     */
    public boolean signEncounter(ClinicScope scope, UUID encounterId, UUID signedByUserId, String signatureHash) {
        return scope.read(() -> {
            MapSqlParameterSource params = scope.bind()
                    .addValue("encounterId", encounterId)
                    .addValue("signedByUserId", signedByUserId)
                    .addValue("signatureHash", signatureHash)
                    .addValue("now", Timestamp.valueOf(LocalDateTime.now()));

            int rows = jdbc.update("""
                    update "clinical_encounters" ce
                       set "signedAt" = :now,
                           "signedByUserId" = :signedByUserId,
                           "signatureHash" = :signatureHash,
                           "updatedAt" = :now
                     where %s
                       and ce."id" = :encounterId
                       and ce."signedAt" is null
                    """.formatted(scope.predicate("ce")), params);
            return rows > 0;
        });
    }

    /**
     * Lists immutable amendments for an encounter.
     */
    public List<EncounterModels.ClinicalNoteAmendmentRow> listAmendments(ClinicScope scope, UUID encounterId) {
        return scope.read(() -> jdbc.query("""
                select cna."id", cna."organizationId", cna."projectId", cna."encounterId",
                       cna."amendedByUserId", u."fullName" as "amendedByUserName",
                       cna."amendedAt", cna."reason", cna."amendmentContent",
                       cna."signatureHash", cna."createdAt"
                  from "clinical_note_amendments" cna
                  inner join "users" u on u."id" = cna."amendedByUserId"
                 where %s
                   and cna."encounterId" = :encounterId
                 order by cna."amendedAt" asc
                """.formatted(scope.predicate("cna")),
                scope.bind().addValue("encounterId", encounterId),
                EncounterRepository::mapAmendmentRow));
    }

    /**
     * Inserts an immutable note amendment.
     */
    public UUID insertAmendment(
            ClinicScope scope,
            UUID encounterId,
            UUID amendedByUserId,
            String reason,
            String amendmentContent,
            String signatureHash) {
        if (!scope.isProjectScoped()) {
            throw new IllegalStateException("An amendment must be added inside a scoped clinic project.");
        }
        return scope.read(() -> {
            UUID id = UUID.randomUUID();
            MapSqlParameterSource params = scope.bind()
                    .addValue("id", id)
                    .addValue("org", scope.queryScope().organizationId())
                    .addValue("proj", scope.projectId())
                    .addValue("encounterId", encounterId)
                    .addValue("amendedByUserId", amendedByUserId)
                    .addValue("reason", reason)
                    .addValue("content", amendmentContent)
                    .addValue("signatureHash", signatureHash)
                    .addValue("now", Timestamp.valueOf(LocalDateTime.now()));

            jdbc.update("""
                    insert into "clinical_note_amendments" (
                        "id", "organizationId", "projectId", "encounterId",
                        "amendedByUserId", "amendedAt", "reason",
                        "amendmentContent", "signatureHash", "createdAt"
                    ) values (
                        :id, :org, :proj, :encounterId,
                        :amendedByUserId, :now, :reason,
                        :content, :signatureHash, :now
                    )
                    """, params);
            return id;
        });
    }

    /**
     * Finds patient medical history.
     */
    public Optional<EncounterModels.MedicalHistoryRow> findMedicalHistory(ClinicScope scope, UUID patientId) {
        return scope.read(() -> {
            try {
                return Optional.ofNullable(jdbc.queryForObject("""
                        select pmh."id", pmh."organizationId", pmh."projectId", pmh."patientId",
                               pmh."allergies", pmh."conditions", pmh."currentMedications",
                               pmh."bleedingDisorder", pmh."cardiacCondition", pmh."diabetes",
                               pmh."notes", pmh."recordedByUserId", u."fullName" as "recordedByUserName",
                               pmh."createdAt", pmh."updatedAt"
                          from "patient_medical_histories" pmh
                          left join "users" u on u."id" = pmh."recordedByUserId"
                         where %s
                           and pmh."patientId" = :patientId
                        """.formatted(scope.predicate("pmh")),
                        scope.bind().addValue("patientId", patientId),
                        EncounterRepository::mapMedicalHistoryRow));
            } catch (EmptyResultDataAccessException e) {
                return Optional.empty();
            }
        });
    }

    /**
     * Upserts patient medical history.
     */
    public UUID upsertMedicalHistory(
            ClinicScope scope,
            UUID patientId,
            UUID recordedByUserId,
            EncounterModels.SaveMedicalHistoryRequest request) {
        if (!scope.isProjectScoped()) {
            throw new IllegalStateException("Medical history must be recorded inside a scoped clinic project.");
        }
        return scope.read(() -> {
            UUID id = UUID.randomUUID();
            MapSqlParameterSource params = scope.bind()
                    .addValue("id", id)
                    .addValue("org", scope.queryScope().organizationId())
                    .addValue("proj", scope.projectId())
                    .addValue("patientId", patientId)
                    .addValue("allergies", request.allergies())
                    .addValue("conditions", request.conditions())
                    .addValue("meds", request.currentMedications())
                    .addValue("bleeding", Boolean.TRUE.equals(request.bleedingDisorder()))
                    .addValue("cardiac", Boolean.TRUE.equals(request.cardiacCondition()))
                    .addValue("diabetes", Boolean.TRUE.equals(request.diabetes()))
                    .addValue("notes", request.notes())
                    .addValue("recordedByUserId", recordedByUserId)
                    .addValue("now", Timestamp.valueOf(LocalDateTime.now()));

            UUID actualId = jdbc.queryForObject("""
                    insert into "patient_medical_histories" (
                        "id", "organizationId", "projectId", "patientId",
                        "allergies", "conditions", "currentMedications",
                        "bleedingDisorder", "cardiacCondition", "diabetes",
                        "notes", "recordedByUserId", "createdAt", "updatedAt"
                    ) values (
                        :id, :org, :proj, :patientId,
                        :allergies, :conditions, :meds,
                        :bleeding, :cardiac, :diabetes,
                        :notes, :recordedByUserId, :now, :now
                    )
                    on conflict ("organizationId", "patientId") do update
                        set "allergies" = excluded."allergies",
                            "conditions" = excluded."conditions",
                            "currentMedications" = excluded."currentMedications",
                            "bleedingDisorder" = excluded."bleedingDisorder",
                            "cardiacCondition" = excluded."cardiacCondition",
                            "diabetes" = excluded."diabetes",
                            "notes" = excluded."notes",
                            "recordedByUserId" = excluded."recordedByUserId",
                            "updatedAt" = excluded."updatedAt"
                    returning "id"
                    """, params, UUID.class);
            return actualId != null ? actualId : id;
        });
    }

    /**
     * Lists prescriptions for an encounter.
     */
    public List<EncounterModels.PrescriptionRow> listPrescriptions(ClinicScope scope, UUID encounterId) {
        return scope.read(() -> jdbc.query("""
                select cp."id", cp."organizationId", cp."projectId", cp."encounterId",
                       cp."patientId", cp."doctorId", d."name" as "doctorName",
                       cp."medicationName", cp."dosage", cp."frequency", cp."duration",
                       cp."instructions", cp."createdAt", cp."updatedAt"
                  from "clinical_prescriptions" cp
                  left join "doctors" d on d."id" = cp."doctorId"
                 where %s
                   and cp."encounterId" = :encounterId
                 order by cp."createdAt" asc
                """.formatted(scope.predicate("cp")),
                scope.bind().addValue("encounterId", encounterId),
                EncounterRepository::mapPrescriptionRow));
    }

    /**
     * Adds a prescription to an encounter (CLN-06).
     */
    public UUID insertPrescription(
            ClinicScope scope,
            UUID encounterId,
            UUID patientId,
            UUID doctorId,
            String medicationName,
            String dosage,
            String frequency,
            String duration,
            String instructions) {
        if (!scope.isProjectScoped()) {
            throw new IllegalStateException("A prescription must be created inside a scoped clinic project.");
        }
        return scope.read(() -> {
            UUID id = UUID.randomUUID();
            MapSqlParameterSource params = scope.bind()
                    .addValue("id", id)
                    .addValue("org", scope.queryScope().organizationId())
                    .addValue("proj", scope.projectId())
                    .addValue("encounterId", encounterId)
                    .addValue("patientId", patientId)
                    .addValue("doctorId", doctorId)
                    .addValue("medicationName", medicationName)
                    .addValue("dosage", dosage)
                    .addValue("frequency", frequency)
                    .addValue("duration", duration)
                    .addValue("instructions", instructions);

            jdbc.update("""
                    insert into "clinical_prescriptions" (
                        "id", "organizationId", "projectId", "encounterId",
                        "patientId", "doctorId", "medicationName", "dosage",
                        "frequency", "duration", "instructions"
                    ) values (
                        :id, :org, :proj, :encounterId,
                        :patientId, :doctorId, :medicationName, :dosage,
                        :frequency, :duration, :instructions
                    )
                    """, params);
            return id;
        });
    }

    /**
     * Removes a prescription.
     */
    public boolean deletePrescription(ClinicScope scope, UUID prescriptionId) {
        return scope.read(() -> {
            int rows = jdbc.update("""
                    delete from "clinical_prescriptions" cp
                     where %s
                       and cp."id" = :prescriptionId
                    """.formatted(scope.predicate("cp")),
                    scope.bind().addValue("prescriptionId", prescriptionId));
            return rows > 0;
        });
    }

    /**
     * Marks linked treatment plan item as completed (CLN-05).
     */
    public void markTreatmentPlanItemCompleted(ClinicScope scope, UUID planItemId) {
        scope.read(() -> {
            MapSqlParameterSource params = scope.bind()
                    .addValue("planItemId", planItemId)
                    .addValue("now", Timestamp.valueOf(LocalDateTime.now()));

            jdbc.update("""
                    update "treatment_plan_items" tpi
                       set "status" = 'COMPLETED',
                           "completedAt" = :now,
                           "updatedAt" = :now
                     where %s
                       and tpi."id" = :planItemId
                       and tpi."status" != 'COMPLETED'
                    """.formatted(scope.predicate("tpi")), params);
            return null;
        });
    }

    /**
     * Lists all grouped clinic prescription sheets for the Prescriptions module.
     */
    public List<EncounterModels.ClinicPrescriptionSheet> listClinicPrescriptionSheets(ClinicScope scope) {
        return scope.read(() -> {
            List<EncounterModels.PrescriptionRow> allMeds = jdbc.query("""
                    select cp."id", cp."organizationId", cp."projectId", cp."encounterId",
                           cp."patientId", cp."doctorId", d."name" as "doctorName",
                           cp."medicationName", cp."dosage", cp."frequency", cp."duration",
                           cp."instructions", cp."createdAt", cp."updatedAt"
                      from "clinical_prescriptions" cp
                      left join "doctors" d on d."id" = cp."doctorId"
                     where %s
                     order by cp."createdAt" asc
                    """.formatted(scope.predicate("cp")),
                    scope.bind(),
                    EncounterRepository::mapPrescriptionRow);

            Map<UUID, List<EncounterModels.PrescriptionRow>> medsByEncounter = new LinkedHashMap<>();
            for (EncounterModels.PrescriptionRow med : allMeds) {
                medsByEncounter.computeIfAbsent(med.encounterId(), k -> new ArrayList<>()).add(med);
            }

            List<EncounterModels.ClinicPrescriptionSheet> rawList = jdbc.query("""
                    select ce."id", ce."organizationId", ce."projectId", ce."patientId",
                           p."displayName" as "patientName", p."patientNo", p."primaryPhone" as "patientPhone",
                           ce."doctorId", d."name" as "doctorName", d."specialty" as "doctorSpecialty",
                           ce."diagnosis", ce."clinicalNotes", ce."visitDate", ce."createdAt"
                      from "clinical_encounters" ce
                      inner join "patients" p on p."id" = ce."patientId"
                      left join "doctors" d on d."id" = ce."doctorId"
                     where %s
                       and exists (
                           select 1 from "clinical_prescriptions" cp
                            where cp."encounterId" = ce."id"
                       )
                     order by ce."visitDate" asc, ce."createdAt" asc
                    """.formatted(scope.predicate("ce")),
                    scope.bind(),
                    (rs, rowNum) -> {
                        UUID encId = rs.getObject("id", UUID.class);
                        List<EncounterModels.PrescriptionRow> meds = medsByEncounter.getOrDefault(encId, List.of());
                        String rxNum = "RX-" + (rowNum + 1);
                        return new EncounterModels.ClinicPrescriptionSheet(
                                encId,
                                rxNum,
                                rs.getObject("organizationId", UUID.class),
                                rs.getObject("projectId", UUID.class),
                                rs.getObject("patientId", UUID.class),
                                rs.getString("patientName"),
                                rs.getString("patientNo"),
                                rs.getString("patientPhone"),
                                rs.getObject("doctorId", UUID.class),
                                rs.getString("doctorName"),
                                rs.getString("doctorSpecialty"),
                                rs.getString("diagnosis"),
                                rs.getString("clinicalNotes"),
                                toLocalDateTime(rs.getTimestamp("visitDate")),
                                toLocalDateTime(rs.getTimestamp("createdAt")),
                                meds.size(),
                                meds);
                    });

            List<EncounterModels.ClinicPrescriptionSheet> reversed = new ArrayList<>(rawList);
            Collections.reverse(reversed);
            return reversed;
        });
    }

    /**
     * Finds a single prescription sheet by encounter ID.
     */
    public Optional<EncounterModels.ClinicPrescriptionSheet> findPrescriptionSheetById(ClinicScope scope, UUID encounterId) {
        return listClinicPrescriptionSheets(scope).stream()
                .filter(s -> s.id().equals(encounterId))
                .findFirst();
    }

    /**
     * Deletes all prescription items for an encounter sheet.
     */
    public boolean deletePrescriptionSheet(ClinicScope scope, UUID encounterId) {
        return scope.read(() -> {
            int rows = jdbc.update("""
                    delete from "clinical_prescriptions" cp
                     where %s
                       and cp."encounterId" = :encounterId
                    """.formatted(scope.predicate("cp")),
                    scope.bind().addValue("encounterId", encounterId));

            // Also clean up standalone encounters that were created solely for this prescription sheet
            jdbc.update("""
                    delete from "clinical_encounters" ce
                     where %s
                       and ce."id" = :encounterId
                       and not exists (
                           select 1 from "clinical_prescriptions" cp2 where cp2."encounterId" = ce."id"
                       )
                       and (ce."chiefComplaint" = 'Prescription' or ce."performedProcedures" is null)
                    """.formatted(scope.predicate("ce")),
                    scope.bind().addValue("encounterId", encounterId));
            return rows > 0;
        });
    }

    /**
     * Resolves a fallback active doctor ID in the clinic scope if not explicitly specified.
     */
    public Optional<UUID> resolveDefaultDoctorId(ClinicScope scope) {
        return scope.read(() -> {
            List<UUID> ids = jdbc.query("""
                    select d."id"
                      from "doctors" d
                     where %s
                       and d."isActive" = true
                     order by d."createdAt" asc
                     limit 1
                    """.formatted(scope.predicate("d")),
                    scope.bind(),
                    (rs, rowNum) -> rs.getObject("id", UUID.class));
            return ids.stream().findFirst();
        });
    }

    private void ensureLetterheadTable() {
        jdbc.getJdbcOperations().execute("""
                CREATE TABLE IF NOT EXISTS clinic_letterhead_settings (
                    "organizationId" UUID NOT NULL,
                    "projectId" UUID NOT NULL,
                    "clinicName" VARCHAR(255) NOT NULL DEFAULT 'BrightSmiles Dental Clinic',
                    "clinicSubtitle" VARCHAR(255) NOT NULL DEFAULT 'مركز تخصصي لطب وجراحة وتجميل الأسنان · Oral & Dental Care Center',
                    "logoUrl" TEXT NULL,
                    "address" VARCHAR(500) NOT NULL DEFAULT 'عمان - شارع المدينة المنورة - مجمع العيادات الطبية الحديثة',
                    "phone" VARCHAR(100) NOT NULL DEFAULT '+962 6 555 0199',
                    "whatsapp" VARCHAR(100) NULL DEFAULT '+962 79 888 0199',
                    "email" VARCHAR(255) NULL DEFAULT 'contact@brightsmilesclinic.com',
                    "licenseNumber" VARCHAR(100) NULL DEFAULT 'LIC-JO-2026-8841',
                    "defaultInstructions" TEXT NULL DEFAULT 'يرجى الالتزام بالجرعات والمواعيد المحددة بدقة، وعدم إيقاف المضاد الحيوي قبل إتمام المدة المقررة، ومراجعة العيادة عند الحاجة.',
                    "footerText" VARCHAR(500) NULL DEFAULT 'نتمنى لكم دوام الصحة والعافية · للاستفسارات الطارئة يرجى التواصل مع العيادة على مدار الساعة',
                    "signatureLabel" VARCHAR(255) NOT NULL DEFAULT 'توقيع وختم الطبيب المعالج / Doctor''s Signature',
                    "paperSize" VARCHAR(20) NOT NULL DEFAULT 'A4',
                    "prePrintedPaper" BOOLEAN NOT NULL DEFAULT FALSE,
                    "updatedAt" TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
                    PRIMARY KEY ("organizationId", "projectId")
                )
                """);
    }

    /**
     * Retrieves customizable letterhead settings for the scoped clinic.
     */
    public EncounterModels.ClinicLetterheadSettings getLetterheadSettings(ClinicScope scope) {
        ensureLetterheadTable();
        UUID orgId = scope.queryScope().organizationId();
        UUID projId = scope.projectId() != null
                ? scope.projectId()
                : (scope.accessibleProjectIds().isEmpty() ? orgId : scope.accessibleProjectIds().get(0));

        return scope.read(() -> {
            List<EncounterModels.ClinicLetterheadSettings> rows = jdbc.query("""
                    select "organizationId", "projectId", "clinicName", "clinicSubtitle",
                           "logoUrl", "address", "phone", "whatsapp", "email", "licenseNumber",
                           "defaultInstructions", "footerText", "signatureLabel", "paperSize",
                           "prePrintedPaper", "updatedAt"
                      from clinic_letterhead_settings
                     where "organizationId" = :orgId
                     order by case when "projectId" = :projId then 0 else 1 end, "updatedAt" desc
                     limit 1
                    """,
                    new MapSqlParameterSource().addValue("orgId", orgId).addValue("projId", projId),
                    (rs, rowNum) -> new EncounterModels.ClinicLetterheadSettings(
                            rs.getObject("organizationId", UUID.class),
                            rs.getObject("projectId", UUID.class),
                            rs.getString("clinicName"),
                            rs.getString("clinicSubtitle"),
                            rs.getString("logoUrl"),
                            rs.getString("address"),
                            rs.getString("phone"),
                            rs.getString("whatsapp"),
                            rs.getString("email"),
                            rs.getString("licenseNumber"),
                            rs.getString("defaultInstructions"),
                            rs.getString("footerText"),
                            rs.getString("signatureLabel"),
                            rs.getString("paperSize"),
                            rs.getBoolean("prePrintedPaper"),
                            toLocalDateTime(rs.getTimestamp("updatedAt"))));

            if (!rows.isEmpty()) {
                return rows.get(0);
            }

            return new EncounterModels.ClinicLetterheadSettings(
                    orgId,
                    projId,
                    "BrightSmiles Dental Clinic",
                    "مركز تخصصي لطب وجراحة وتجميل الأسنان · Oral & Dental Care Center",
                    null,
                    "عمان - شارع المدينة المنورة - مجمع العيادات الطبية الحديثة",
                    "+962 6 555 0199",
                    "+962 79 888 0199",
                    "contact@brightsmilesclinic.com",
                    "LIC-JO-2026-8841",
                    "يرجى الالتزام بالجرعات والمواعيد المحددة بدقة، وعدم إيقاف المضاد الحيوي قبل إتمام المدة المقررة، ومراجعة العيادة عند الحاجة.",
                    "نتمنى لكم دوام الصحة والعافية · للاستفسارات الطارئة يرجى التواصل مع العيادة على مدار الساعة",
                    "توقيع وختم الطبيب المعالج / Doctor's Signature",
                    "A4",
                    false,
                    LocalDateTime.now());
        });
    }

    /**
     * Upserts customizable letterhead settings for the scoped clinic.
     */
    public EncounterModels.ClinicLetterheadSettings upsertLetterheadSettings(
            ClinicScope scope, EncounterModels.UpdateLetterheadRequest req) {
        ensureLetterheadTable();
        UUID orgId = scope.queryScope().organizationId();
        UUID projId = scope.projectId() != null
                ? scope.projectId()
                : (scope.accessibleProjectIds().isEmpty() ? orgId : scope.accessibleProjectIds().get(0));

        EncounterModels.ClinicLetterheadSettings current = getLetterheadSettings(scope);

        String clinicName = req.clinicName() != null && !req.clinicName().isBlank() ? req.clinicName().trim() : current.clinicName();
        String clinicSubtitle = req.clinicSubtitle() != null ? req.clinicSubtitle().trim() : current.clinicSubtitle();
        String logoUrl = req.logoUrl() != null ? req.logoUrl().trim() : current.logoUrl();
        String address = req.address() != null ? req.address().trim() : current.address();
        String phone = req.phone() != null ? req.phone().trim() : current.phone();
        String whatsapp = req.whatsapp() != null ? req.whatsapp().trim() : current.whatsapp();
        String email = req.email() != null ? req.email().trim() : current.email();
        String licenseNumber = req.licenseNumber() != null ? req.licenseNumber().trim() : current.licenseNumber();
        String defaultInstructions = req.defaultInstructions() != null ? req.defaultInstructions().trim() : current.defaultInstructions();
        String footerText = req.footerText() != null ? req.footerText().trim() : current.footerText();
        String signatureLabel = req.signatureLabel() != null && !req.signatureLabel().isBlank() ? req.signatureLabel().trim() : current.signatureLabel();
        String paperSize = req.paperSize() != null && !req.paperSize().isBlank() ? req.paperSize().trim().toUpperCase() : current.paperSize();
        boolean prePrinted = req.prePrintedPaper() != null ? req.prePrintedPaper() : current.prePrintedPaper();

        scope.read(() -> {
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("orgId", orgId)
                    .addValue("projId", projId)
                    .addValue("clinicName", clinicName)
                    .addValue("clinicSubtitle", clinicSubtitle)
                    .addValue("logoUrl", logoUrl)
                    .addValue("address", address)
                    .addValue("phone", phone)
                    .addValue("whatsapp", whatsapp)
                    .addValue("email", email)
                    .addValue("licenseNumber", licenseNumber)
                    .addValue("defaultInstructions", defaultInstructions)
                    .addValue("footerText", footerText)
                    .addValue("signatureLabel", signatureLabel)
                    .addValue("paperSize", paperSize)
                    .addValue("prePrintedPaper", prePrinted);

            jdbc.update("""
                    insert into clinic_letterhead_settings (
                        "organizationId", "projectId", "clinicName", "clinicSubtitle",
                        "logoUrl", "address", "phone", "whatsapp", "email", "licenseNumber",
                        "defaultInstructions", "footerText", "signatureLabel", "paperSize",
                        "prePrintedPaper", "updatedAt"
                    ) values (
                        :orgId, :projId, :clinicName, :clinicSubtitle,
                        :logoUrl, :address, :phone, :whatsapp, :email, :licenseNumber,
                        :defaultInstructions, :footerText, :signatureLabel, :paperSize,
                        :prePrintedPaper, (now() at time zone 'UTC')
                    )
                    on conflict ("organizationId", "projectId")
                    do update set
                        "clinicName" = :clinicName,
                        "clinicSubtitle" = :clinicSubtitle,
                        "logoUrl" = :logoUrl,
                        "address" = :address,
                        "phone" = :phone,
                        "whatsapp" = :whatsapp,
                        "email" = :email,
                        "licenseNumber" = :licenseNumber,
                        "defaultInstructions" = :defaultInstructions,
                        "footerText" = :footerText,
                        "signatureLabel" = :signatureLabel,
                        "paperSize" = :paperSize,
                        "prePrintedPaper" = :prePrintedPaper,
                        "updatedAt" = (now() at time zone 'UTC')
                    """, params);
            return null;
        });

        return getLetterheadSettings(scope);
    }

    public void ensurePrescriptionPresetsTables() {
        jdbc.getJdbcOperations().execute("""
                CREATE TABLE IF NOT EXISTS clinic_medication_presets (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    "organizationId" UUID NOT NULL,
                    "projectId" UUID NOT NULL,
                    "medicationName" VARCHAR(255) NOT NULL,
                    "dosage" VARCHAR(100) NOT NULL DEFAULT '1 tablet',
                    "frequency" VARCHAR(100) NOT NULL DEFAULT 'Twice a day',
                    "duration" VARCHAR(100) NOT NULL DEFAULT '5 days',
                    "instructions" TEXT NULL,
                    "category" VARCHAR(100) NOT NULL DEFAULT 'General',
                    "isFavorite" BOOLEAN NOT NULL DEFAULT TRUE,
                    "createdAt" TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
                    "updatedAt" TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC')
                )
                """);

        jdbc.getJdbcOperations().execute("""
                CREATE TABLE IF NOT EXISTS clinic_diagnosis_presets (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    "organizationId" UUID NOT NULL,
                    "projectId" UUID NOT NULL,
                    "name" VARCHAR(255) NOT NULL,
                    "code" VARCHAR(50) NULL,
                    "createdAt" TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC')
                )
                """);
    }

    public List<EncounterModels.ClinicMedicationPreset> listMedicationPresets(ClinicScope scope) {
        ensurePrescriptionPresetsTables();
        UUID orgId = scope.queryScope().organizationId();
        UUID projId = scope.projectId() != null
                ? scope.projectId()
                : (scope.accessibleProjectIds().isEmpty() ? orgId : scope.accessibleProjectIds().get(0));

        return scope.read(() -> {
            List<EncounterModels.ClinicMedicationPreset> list = jdbc.query("""
                    select id, "organizationId", "projectId", "medicationName", "dosage",
                           "frequency", "duration", "instructions", "category", "isFavorite",
                           "createdAt", "updatedAt"
                      from clinic_medication_presets
                     where "organizationId" = :orgId
                     order by "isFavorite" desc, "category" asc, "medicationName" asc
                    """,
                    new MapSqlParameterSource().addValue("orgId", orgId),
                    (rs, rowNum) -> new EncounterModels.ClinicMedicationPreset(
                            rs.getObject("id", UUID.class),
                            rs.getObject("organizationId", UUID.class),
                            rs.getObject("projectId", UUID.class),
                            rs.getString("medicationName"),
                            rs.getString("dosage"),
                            rs.getString("frequency"),
                            rs.getString("duration"),
                            rs.getString("instructions"),
                            rs.getString("category"),
                            rs.getBoolean("isFavorite"),
                            toLocalDateTime(rs.getTimestamp("createdAt")),
                            toLocalDateTime(rs.getTimestamp("updatedAt"))));

            if (list.isEmpty()) {
                seedDefaultMedicationPresets(orgId, projId);
                return jdbc.query("""
                        select id, "organizationId", "projectId", "medicationName", "dosage",
                               "frequency", "duration", "instructions", "category", "isFavorite",
                               "createdAt", "updatedAt"
                          from clinic_medication_presets
                         where "organizationId" = :orgId
                         order by "isFavorite" desc, "category" asc, "medicationName" asc
                        """,
                        new MapSqlParameterSource().addValue("orgId", orgId),
                        (rs, rowNum) -> new EncounterModels.ClinicMedicationPreset(
                                rs.getObject("id", UUID.class),
                                rs.getObject("organizationId", UUID.class),
                                rs.getObject("projectId", UUID.class),
                                rs.getString("medicationName"),
                                rs.getString("dosage"),
                                rs.getString("frequency"),
                                rs.getString("duration"),
                                rs.getString("instructions"),
                                rs.getString("category"),
                                rs.getBoolean("isFavorite"),
                                toLocalDateTime(rs.getTimestamp("createdAt")),
                                toLocalDateTime(rs.getTimestamp("updatedAt"))));
            }
            return list;
        });
    }

    private void seedDefaultMedicationPresets(UUID orgId, UUID projId) {
        String[][] defaults = {
                {"Augmentin 1g (Amoxicillin/Clavulanate)", "1 tablet", "Twice a day (Every 12h)", "7 days", "بعد الطعام مع كوب ماء كبير", "المضادات الحيوية (Antibiotics)", "true"},
                {"Amoxicillin 500mg", "1 capsule", "Three times a day (Every 8h)", "5 days", "بعد الأكل كل 8 ساعات بانتظام", "المضادات الحيوية (Antibiotics)", "true"},
                {"Metronidazole 500mg (Flagyl)", "1 tablet", "Three times a day", "5 days", "تجنب الكحول، يؤخذ مع أو بعد الطعام", "المضادات الحيوية (Antibiotics)", "true"},
                {"Clindamycin 300mg (Dalacin C)", "1 capsule", "Three times a day", "5 days", "بديل البنسلين - يؤخذ مع كمية وفيرة من الماء", "المضادات الحيوية (Antibiotics)", "false"},
                {"Ibuprofen 600mg (Brufen)", "1 tablet", "Three times a day", "3-5 days", "بعد الطعام لتفادي اضطراب المعدة", "مسكنات ومضادات التهاب (NSAIDs)", "true"},
                {"Ibuprofen 400mg", "1 tablet", "Twice a day", "3 days", "عند اللزوم بعد الأكل", "مسكنات ومضادات التهاب (NSAIDs)", "true"},
                {"Diclofenac Potassium 50mg (Cataflam)", "1 tablet", "Twice a day", "3 days", "مسكن سريع للألم الحاد بعد الوجبة", "مسكنات ومضادات التهاب (NSAIDs)", "true"},
                {"Paracetamol 650mg / 1000mg (Panadol)", "1-2 tablets", "Every 6-8h as needed", "3 days", "الحد الأقصى 4 غرامات يومياً", "مسكنات وخافضات حرارة (Analgesics)", "true"},
                {"Chlorhexidine Mouthwash 0.12%", "15 ml", "Twice a day", "7 days", "مضمضة 30 ثانية صباحاً ومساءً بعد تفريش الأسنان", "مضامض وغسول فم (Mouthwash)", "true"},
                {"Dexamethasone 4mg", "1 tablet", "Once daily in morning", "2 days", "لتخفيف التورم بعد الجراحة المعقدة والخلع", "الستيرويدات ومضادات التورم", "false"}
        };
        for (String[] def : defaults) {
            jdbc.update("""
                    insert into clinic_medication_presets (
                        "organizationId", "projectId", "medicationName", "dosage",
                        "frequency", "duration", "instructions", "category", "isFavorite"
                    ) values (
                        :orgId, :projId, :medName, :dosage, :frequency, :duration, :inst, :cat, :fav
                    )
                    """, new MapSqlParameterSource()
                    .addValue("orgId", orgId)
                    .addValue("projId", projId)
                    .addValue("medName", def[0])
                    .addValue("dosage", def[1])
                    .addValue("frequency", def[2])
                    .addValue("duration", def[3])
                    .addValue("inst", def[4])
                    .addValue("cat", def[5])
                    .addValue("fav", Boolean.parseBoolean(def[6])));
        }
    }

    public EncounterModels.ClinicMedicationPreset createMedicationPreset(
            ClinicScope scope, EncounterModels.CreateMedicationPresetRequest req) {
        ensurePrescriptionPresetsTables();
        UUID orgId = scope.queryScope().organizationId();
        UUID projId = scope.projectId() != null
                ? scope.projectId()
                : (scope.accessibleProjectIds().isEmpty() ? orgId : scope.accessibleProjectIds().get(0));

        UUID id = UUID.randomUUID();
        String medName = req.medicationName().trim();
        String dosage = req.dosage() != null && !req.dosage().isBlank() ? req.dosage().trim() : "1 tablet";
        String frequency = req.frequency() != null && !req.frequency().isBlank() ? req.frequency().trim() : "Twice a day";
        String duration = req.duration() != null && !req.duration().isBlank() ? req.duration().trim() : "5 days";
        String instructions = req.instructions() != null ? req.instructions().trim() : null;
        String category = req.category() != null && !req.category().isBlank() ? req.category().trim() : "General";
        boolean isFav = req.isFavorite() != null ? req.isFavorite() : true;

        jdbc.update("""
                insert into clinic_medication_presets (
                    id, "organizationId", "projectId", "medicationName", "dosage",
                    "frequency", "duration", "instructions", "category", "isFavorite"
                ) values (
                    :id, :orgId, :projId, :medName, :dosage, :frequency, :duration, :instructions, :category, :isFav
                )
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("orgId", orgId)
                .addValue("projId", projId)
                .addValue("medName", medName)
                .addValue("dosage", dosage)
                .addValue("frequency", frequency)
                .addValue("duration", duration)
                .addValue("instructions", instructions)
                .addValue("category", category)
                .addValue("isFav", isFav));

        return new EncounterModels.ClinicMedicationPreset(
                id, orgId, projId, medName, dosage, frequency, duration, instructions, category, isFav,
                LocalDateTime.now(), LocalDateTime.now());
    }

    public boolean deleteMedicationPreset(ClinicScope scope, UUID id) {
        ensurePrescriptionPresetsTables();
        UUID orgId = scope.queryScope().organizationId();
        int rows = jdbc.update("""
                delete from clinic_medication_presets
                 where id = :id and "organizationId" = :orgId
                """, new MapSqlParameterSource().addValue("id", id).addValue("orgId", orgId));
        return rows > 0;
    }

    public List<EncounterModels.ClinicDiagnosisPreset> listDiagnosisPresets(ClinicScope scope) {
        ensurePrescriptionPresetsTables();
        UUID orgId = scope.queryScope().organizationId();
        UUID projId = scope.projectId() != null
                ? scope.projectId()
                : (scope.accessibleProjectIds().isEmpty() ? orgId : scope.accessibleProjectIds().get(0));

        return scope.read(() -> {
            List<EncounterModels.ClinicDiagnosisPreset> list = jdbc.query("""
                    select id, "organizationId", "projectId", "name", "code", "createdAt"
                      from clinic_diagnosis_presets
                     where "organizationId" = :orgId
                     order by "name" asc
                    """,
                    new MapSqlParameterSource().addValue("orgId", orgId),
                    (rs, rowNum) -> new EncounterModels.ClinicDiagnosisPreset(
                            rs.getObject("id", UUID.class),
                            rs.getObject("organizationId", UUID.class),
                            rs.getObject("projectId", UUID.class),
                            rs.getString("name"),
                            rs.getString("code"),
                            toLocalDateTime(rs.getTimestamp("createdAt"))));

            if (list.isEmpty()) {
                seedDefaultDiagnosisPresets(orgId, projId);
                return jdbc.query("""
                        select id, "organizationId", "projectId", "name", "code", "createdAt"
                          from clinic_diagnosis_presets
                         where "organizationId" = :orgId
                         order by "name" asc
                        """,
                        new MapSqlParameterSource().addValue("orgId", orgId),
                        (rs, rowNum) -> new EncounterModels.ClinicDiagnosisPreset(
                                rs.getObject("id", UUID.class),
                                rs.getObject("organizationId", UUID.class),
                                rs.getObject("projectId", UUID.class),
                                rs.getString("name"),
                                rs.getString("code"),
                                toLocalDateTime(rs.getTimestamp("createdAt"))));
            }
            return list;
        });
    }

    private void seedDefaultDiagnosisPresets(UUID orgId, UUID projId) {
        String[][] diags = {
                {"Pulpitis · التهاب عصب السن", "K04.0"},
                {"Post-extraction care · عناية ما بعد الخلع الجراحي", "Z48.8"},
                {"Symptomatic Apical Periodontitis · التهاب الأنسجة حول الذروية الحاد", "K04.4"},
                {"Acute Pericoronitis · التهاب اللثة حول ضرس العقل", "K05.22"},
                {"Acute Dentoalveolar Abscess · خراج سني حاد", "K04.7"},
                {"Post-implant Surgery Prophylaxis · وقاية ما بعد زراعة الأسنان", "Z48.0"},
                {"Gingivitis & Periodontal Care · التهاب اللثة والأنسجة الداعمة", "K05.1"},
                {"Dental Caries · تسوس أسنان عميق", "K02.9"}
        };
        for (String[] d : diags) {
            jdbc.update("""
                    insert into clinic_diagnosis_presets ("organizationId", "projectId", "name", "code")
                    values (:orgId, :projId, :name, :code)
                    """, new MapSqlParameterSource()
                    .addValue("orgId", orgId)
                    .addValue("projId", projId)
                    .addValue("name", d[0])
                    .addValue("code", d[1]));
        }
    }

    public EncounterModels.ClinicDiagnosisPreset createDiagnosisPreset(
            ClinicScope scope, EncounterModels.CreateDiagnosisPresetRequest req) {
        ensurePrescriptionPresetsTables();
        UUID orgId = scope.queryScope().organizationId();
        UUID projId = scope.projectId() != null
                ? scope.projectId()
                : (scope.accessibleProjectIds().isEmpty() ? orgId : scope.accessibleProjectIds().get(0));

        UUID id = UUID.randomUUID();
        String name = req.name().trim();
        String code = req.code() != null ? req.code().trim() : null;

        jdbc.update("""
                insert into clinic_diagnosis_presets (id, "organizationId", "projectId", "name", "code")
                values (:id, :orgId, :projId, :name, :code)
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("orgId", orgId)
                .addValue("projId", projId)
                .addValue("name", name)
                .addValue("code", code));

        return new EncounterModels.ClinicDiagnosisPreset(id, orgId, projId, name, code, LocalDateTime.now());
    }

    public boolean deleteDiagnosisPreset(ClinicScope scope, UUID id) {
        ensurePrescriptionPresetsTables();
        UUID orgId = scope.queryScope().organizationId();
        int rows = jdbc.update("""
                delete from clinic_diagnosis_presets
                 where id = :id and "organizationId" = :orgId
                """, new MapSqlParameterSource().addValue("id", id).addValue("orgId", orgId));
        return rows > 0;
    }

    private static LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts != null ? ts.toLocalDateTime() : null;
    }

    private static EncounterModels.EncounterHeader mapEncounterHeader(ResultSet rs, boolean maskMedicalData) throws SQLException {
        boolean masked = maskMedicalData;
        String complaint = masked ? MASKED_TEXT : rs.getString("chiefComplaint");
        String examNotes = masked ? null : rs.getString("examinationNotes");
        String diagnosis = masked ? MASKED_TEXT : rs.getString("diagnosis");
        String clinicalNotes = masked ? null : rs.getString("clinicalNotes");

        Integer bpSys = rs.getObject("bloodPressureSystolic", Integer.class);
        Integer bpDia = rs.getObject("bloodPressureDiastolic", Integer.class);
        Integer pulse = rs.getObject("pulseRate", Integer.class);
        BigDecimal temp = rs.getBigDecimal("temperatureC");

        return new EncounterModels.EncounterHeader(
                rs.getObject("id", UUID.class),
                rs.getObject("organizationId", UUID.class),
                rs.getObject("projectId", UUID.class),
                rs.getObject("patientId", UUID.class),
                rs.getString("patientName"),
                rs.getString("patientNo"),
                rs.getObject("appointmentId", UUID.class),
                rs.getObject("doctorId", UUID.class),
                rs.getString("doctorName"),
                rs.getObject("treatmentPlanId", UUID.class),
                rs.getString("treatmentPlanTitle"),
                rs.getObject("treatmentPlanItemId", UUID.class),
                rs.getString("treatmentPlanProcedureName"),
                complaint,
                examNotes,
                diagnosis,
                toLocalDateTime(rs.getTimestamp("diagnosisUpdatedAt")),
                rs.getObject("diagnosisUpdatedBy", UUID.class),
                rs.getString("diagnosisUpdatedByName"),
                clinicalNotes,
                rs.getString("performedProcedures"),
                rs.getObject("toothFdi", Integer.class),
                rs.getString("toothLocation"),
                rs.getString("status"),
                toLocalDateTime(rs.getTimestamp("visitDate")),
                rs.getInt("durationMinutes"),
                rs.getInt("prescriptionCount"),
                masked,
                toLocalDateTime(rs.getTimestamp("signedAt")),
                rs.getObject("signedByUserId", UUID.class),
                rs.getString("signedByUserName"),
                rs.getString("signatureHash"),
                masked ? null : bpSys,
                masked ? null : bpDia,
                masked ? null : pulse,
                masked ? null : temp,
                toLocalDateTime(rs.getTimestamp("createdAt")),
                toLocalDateTime(rs.getTimestamp("updatedAt")));
    }

    private static EncounterModels.PrescriptionRow mapPrescriptionRow(ResultSet rs, int rowNum) throws SQLException {
        return new EncounterModels.PrescriptionRow(
                rs.getObject("id", UUID.class),
                rs.getObject("organizationId", UUID.class),
                rs.getObject("projectId", UUID.class),
                rs.getObject("encounterId", UUID.class),
                rs.getObject("patientId", UUID.class),
                rs.getObject("doctorId", UUID.class),
                rs.getString("doctorName"),
                rs.getString("medicationName"),
                rs.getString("dosage"),
                rs.getString("frequency"),
                rs.getString("duration"),
                rs.getString("instructions"),
                toLocalDateTime(rs.getTimestamp("createdAt")),
                toLocalDateTime(rs.getTimestamp("updatedAt")));
    }

    private static EncounterModels.ClinicalNoteAmendmentRow mapAmendmentRow(ResultSet rs, int rowNum) throws SQLException {
        return new EncounterModels.ClinicalNoteAmendmentRow(
                rs.getObject("id", UUID.class),
                rs.getObject("organizationId", UUID.class),
                rs.getObject("projectId", UUID.class),
                rs.getObject("encounterId", UUID.class),
                rs.getObject("amendedByUserId", UUID.class),
                rs.getString("amendedByUserName"),
                toLocalDateTime(rs.getTimestamp("amendedAt")),
                rs.getString("reason"),
                rs.getString("amendmentContent"),
                rs.getString("signatureHash"),
                toLocalDateTime(rs.getTimestamp("createdAt")));
    }

    private static EncounterModels.MedicalHistoryRow mapMedicalHistoryRow(ResultSet rs, int rowNum) throws SQLException {
        return new EncounterModels.MedicalHistoryRow(
                rs.getObject("id", UUID.class),
                rs.getObject("organizationId", UUID.class),
                rs.getObject("projectId", UUID.class),
                rs.getObject("patientId", UUID.class),
                rs.getString("allergies"),
                rs.getString("conditions"),
                rs.getString("currentMedications"),
                rs.getBoolean("bleedingDisorder"),
                rs.getBoolean("cardiacCondition"),
                rs.getBoolean("diabetes"),
                rs.getString("notes"),
                rs.getObject("recordedByUserId", UUID.class),
                rs.getString("recordedByUserName"),
                toLocalDateTime(rs.getTimestamp("createdAt")),
                toLocalDateTime(rs.getTimestamp("updatedAt")));
    }
}
