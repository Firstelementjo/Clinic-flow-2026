package com.firstelement.crm.clinic.intake;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstelement.crm.clinic.ClinicScope;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persistence layer for Clinic Intake QR keys, 1-hour patient intake access codes,
 * and full 9-step patient history records.
 */
@Repository
public class PatientIntakeRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public PatientIntakeRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public record ProjectIntakeTarget(
            UUID projectId,
            UUID organizationId,
            String clinicName,
            String slug,
            String intakePortalKey,
            boolean intakePortalEnabled,
            String defaultLocale,
            String status,
            boolean archived) {

        public boolean isActiveForIntake() {
            return intakePortalEnabled
                    && !archived
                    && status != null
                    && !"SUSPENDED".equalsIgnoreCase(status)
                    && !"EXPIRED".equalsIgnoreCase(status);
        }
    }

    public record IntakeCodeRow(
            UUID id,
            UUID organizationId,
            UUID projectId,
            UUID patientId,
            String codeHash,
            String codeHint,
            Instant expiresAt,
            String sessionTokenHash,
            Instant sessionExpiresAt,
            Instant verifiedAt,
            Instant usedAt,
            Instant revokedAt,
            int failedAttempts,
            Instant createdAt) {
    }

    public Optional<ProjectIntakeTarget> findProjectById(ClinicScope scope, UUID projectId) {
        List<ProjectIntakeTarget> rows = scope.read(() -> jdbc.query("""
                select p."id", p."organizationId", p."name", p."slug",
                       p."intakePortalKey", p."intakePortalEnabled",
                       cast(p."defaultLocale" as text) as "defaultLocale",
                       p."status", p."isArchived"
                  from "public"."projects" p
                 where p."organizationId" = :org
                   and p."id" = :projectId
                """,
                bindScope(scope).addValue("projectId", projectId),
                (rs, n) -> mapProjectTarget(rs)));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<ProjectIntakeTarget> findProjectByIntakeKey(String key) {
        List<ProjectIntakeTarget> rows = jdbc.query("""
                select p."id", p."organizationId", p."name", p."slug",
                       p."intakePortalKey", p."intakePortalEnabled",
                       cast(p."defaultLocale" as text) as "defaultLocale",
                       p."status", p."isArchived"
                  from "public"."projects" p
                 where (p."intakePortalKey" = :key or p."slug" = :key)
                   and p."isArchived" = false
                 limit 1
                """,
                new MapSqlParameterSource("key", key),
                (rs, n) -> mapProjectTarget(rs));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public void updateIntakePortalKey(ClinicScope scope, UUID projectId, String newKey) {
        scope.read(() -> jdbc.update("""
                update "public"."projects"
                   set "intakePortalKey" = :key,
                       "updatedAt" = (current_timestamp at time zone 'utc')
                 where "organizationId" = :org
                   and "id" = :projectId
                """,
                bindScope(scope).addValue("projectId", projectId).addValue("key", newKey)));
    }

    public void revokeUnusedCodesForPatient(ClinicScope scope, UUID patientId) {
        scope.read(() -> jdbc.update("""
                update "public"."patient_intake_codes"
                   set "revokedAt" = (now() at time zone 'utc')
                 where "organizationId" = :org
                   and "patientId" = :patientId
                   and "usedAt" is null
                   and "revokedAt" is null
                """,
                bindScope(scope).addValue("patientId", patientId)));
    }

    public UUID insertIntakeCode(
            ClinicScope scope,
            UUID projectId,
            UUID patientId,
            String codeHash,
            String codeHint,
            Instant expiresAt,
            UUID generatedByUserId) {
        UUID id = UUID.randomUUID();
        scope.read(() -> jdbc.update("""
                insert into "public"."patient_intake_codes"
                    ("id", "organizationId", "projectId", "patientId", "codeHash", "codeHint",
                     "expiresAt", "generatedByUserId", "createdAt")
                values
                    (:id, :org, :proj, :patientId, :codeHash, :codeHint,
                     :expiresAt, :generatedByUserId, (now() at time zone 'utc'))
                """,
                bindScope(scope)
                        .addValue("id", id)
                        .addValue("proj", projectId)
                        .addValue("patientId", patientId)
                        .addValue("codeHash", codeHash)
                        .addValue("codeHint", codeHint)
                        .addValue("expiresAt", Timestamp.from(expiresAt))
                        .addValue("generatedByUserId", generatedByUserId)));
        return id;
    }

    public Optional<IntakeCodeRow> findLatestUnusedCodeForPatient(ClinicScope scope, UUID patientId) {
        List<IntakeCodeRow> rows = scope.read(() -> jdbc.query("""
                select c."id", c."organizationId", c."projectId", c."patientId",
                       c."codeHash", c."codeHint", c."expiresAt",
                       c."sessionTokenHash", c."sessionExpiresAt",
                       c."verifiedAt", c."usedAt", c."revokedAt",
                       c."failedAttempts", c."createdAt"
                  from "public"."patient_intake_codes" c
                 where c."organizationId" = :org
                   and c."patientId" = :patientId
                   and c."usedAt" is null
                   and c."revokedAt" is null
                 order by c."createdAt" desc
                 limit 1
                """,
                bindScope(scope).addValue("patientId", patientId),
                (rs, n) -> mapCodeRow(rs)));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<IntakeCodeRow> findActiveCodeByHash(UUID projectId, String codeHash) {
        List<IntakeCodeRow> rows = jdbc.query("""
                select c."id", c."organizationId", c."projectId", c."patientId",
                       c."codeHash", c."codeHint", c."expiresAt",
                       c."sessionTokenHash", c."sessionExpiresAt",
                       c."verifiedAt", c."usedAt", c."revokedAt",
                       c."failedAttempts", c."createdAt"
                  from "public"."patient_intake_codes" c
                 where c."projectId" = :projectId
                   and c."codeHash" = :codeHash
                   and c."usedAt" is null
                   and c."revokedAt" is null
                 order by c."createdAt" desc
                 limit 1
                """,
                new MapSqlParameterSource()
                        .addValue("projectId", projectId)
                        .addValue("codeHash", codeHash),
                (rs, n) -> mapCodeRow(rs));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public void attachSessionToken(UUID codeId, String sessionTokenHash, Instant sessionExpiresAt) {
        jdbc.update("""
                update "public"."patient_intake_codes"
                   set "sessionTokenHash" = :tokenHash,
                       "sessionExpiresAt" = :sessionExpiresAt,
                       "verifiedAt" = coalesce("verifiedAt", (now() at time zone 'utc'))
                 where "id" = :id
                   and "usedAt" is null
                   and "revokedAt" is null
                """,
                new MapSqlParameterSource()
                        .addValue("id", codeId)
                        .addValue("tokenHash", sessionTokenHash)
                        .addValue("sessionExpiresAt", Timestamp.from(sessionExpiresAt)));
    }

    public Optional<IntakeCodeRow> findActiveSessionByTokenHash(UUID projectId, String sessionTokenHash) {
        List<IntakeCodeRow> rows = jdbc.query("""
                select c."id", c."organizationId", c."projectId", c."patientId",
                       c."codeHash", c."codeHint", c."expiresAt",
                       c."sessionTokenHash", c."sessionExpiresAt",
                       c."verifiedAt", c."usedAt", c."revokedAt",
                       c."failedAttempts", c."createdAt"
                  from "public"."patient_intake_codes" c
                 where c."projectId" = :projectId
                   and c."sessionTokenHash" = :tokenHash
                   and c."usedAt" is null
                   and c."revokedAt" is null
                 order by c."createdAt" desc
                 limit 1
                """,
                new MapSqlParameterSource()
                        .addValue("projectId", projectId)
                        .addValue("tokenHash", sessionTokenHash),
                (rs, n) -> mapCodeRow(rs));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public boolean markCodeAsUsed(UUID codeId) {
        return jdbc.update("""
                update "public"."patient_intake_codes"
                   set "usedAt" = (now() at time zone 'utc')
                 where "id" = :id
                   and "usedAt" is null
                   and "revokedAt" is null
                """,
                new MapSqlParameterSource("id", codeId)) > 0;
    }

    public Optional<String> findPatientFirstNamePublic(UUID organizationId, UUID patientId) {
        List<String> names = jdbc.queryForList("""
                select coalesce(
                           nullif(trim(p."givenName"), ''),
                           split_part(trim(p."displayName"), ' ', 1)
                       )
                  from "public"."patients" p
                 where p."organizationId" = :orgId
                   and p."id" = :patientId
                   and p."deletedAt" is null
                   and p."isActive" = true
                """,
                new MapSqlParameterSource()
                        .addValue("orgId", organizationId)
                        .addValue("patientId", patientId),
                String.class);
        return names.isEmpty() ? Optional.empty() : Optional.ofNullable(names.get(0));
    }

    public UUID insertPublicIntakeHistory(
            UUID organizationId,
            UUID projectId,
            UUID patientId,
            Map<String, Object> demographics,
            Map<String, Object> medicalHistory,
            Map<String, Object> dentalHistory,
            Map<String, Object> chiefComplaint,
            Map<String, Object> clinicalExam,
            Map<String, Object> radiographs,
            Map<String, Object> diagnosisAndPlan,
            Map<String, Object> dentalChart,
            Map<String, Object> informedConsent) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into "public"."patient_history_intakes" (
                    "id", "organizationId", "projectId", "patientId",
                    "status", "source", "isComplete",
                    "demographicsJson", "medicalHistoryJson", "dentalHistoryJson", "chiefComplaintJson",
                    "clinicalExamJson", "radiographsJson", "diagnosisAndPlanJson",
                    "dentalChartJson", "informedConsentJson",
                    "submittedAt", "createdAt", "updatedAt"
                ) values (
                    :id, :orgId, :projId, :patientId,
                    'PENDING_APPROVAL', 'PATIENT_PORTAL', false,
                    cast(:demographics as jsonb), cast(:medicalHistory as jsonb),
                    cast(:dentalHistory as jsonb), cast(:chiefComplaint as jsonb),
                    cast(:clinicalExam as jsonb), cast(:radiographs as jsonb),
                    cast(:diagnosisAndPlan as jsonb), cast(:dentalChart as jsonb),
                    cast(:informedConsent as jsonb),
                    (now() at time zone 'utc'), (now() at time zone 'utc'), (now() at time zone 'utc')
                )
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("orgId", organizationId)
                        .addValue("projId", projectId)
                        .addValue("patientId", patientId)
                        .addValue("demographics", toJson(demographics))
                        .addValue("medicalHistory", toJson(medicalHistory))
                        .addValue("dentalHistory", toJson(dentalHistory))
                        .addValue("chiefComplaint", toJson(chiefComplaint))
                        .addValue("clinicalExam", toJson(clinicalExam))
                        .addValue("radiographs", toJson(radiographs))
                        .addValue("diagnosisAndPlan", toJson(diagnosisAndPlan))
                        .addValue("dentalChart", toJson(dentalChart))
                        .addValue("informedConsent", toJson(informedConsent)));
        return id;
    }

    public List<PatientIntakeModels.PatientHistoryIntakeRecord> listHistoryRecords(
            ClinicScope scope, UUID patientId) {
        return scope.read(() -> jdbc.query("""
                select h."id", h."patientId", h."encounterId", h."status", h."source", h."isComplete",
                       h."demographicsJson", h."medicalHistoryJson", h."dentalHistoryJson",
                       h."chiefComplaintJson", h."clinicalExamJson", h."radiographsJson",
                       h."diagnosisAndPlanJson", h."dentalChartJson", h."informedConsentJson",
                       h."submittedAt", h."approvedAt", h."approvedByUserId",
                       p."displayName" as "patientDisplayName"
                  from "public"."patient_history_intakes" h
                  join "public"."patients" p on p."id" = h."patientId"
                 where %s
                   and h."patientId" = :patientId
                 order by h."submittedAt" desc, h."createdAt" desc
                """.formatted(scope.predicate("h")),
                bindScope(scope).addValue("patientId", patientId),
                (rs, n) -> mapHistoryRecord(rs)));
    }

    public Optional<PatientIntakeModels.PatientHistoryIntakeRecord> findHistoryRecordById(
            ClinicScope scope, UUID patientId, UUID recordId) {
        List<PatientIntakeModels.PatientHistoryIntakeRecord> rows = scope.read(() -> jdbc.query("""
                select h."id", h."patientId", h."encounterId", h."status", h."source", h."isComplete",
                       h."demographicsJson", h."medicalHistoryJson", h."dentalHistoryJson",
                       h."chiefComplaintJson", h."clinicalExamJson", h."radiographsJson",
                       h."diagnosisAndPlanJson", h."dentalChartJson", h."informedConsentJson",
                       h."submittedAt", h."approvedAt", h."approvedByUserId",
                       p."displayName" as "patientDisplayName"
                  from "public"."patient_history_intakes" h
                  join "public"."patients" p on p."id" = h."patientId"
                 where %s
                   and h."patientId" = :patientId
                   and h."id" = :id
                """.formatted(scope.predicate("h")),
                bindScope(scope).addValue("patientId", patientId).addValue("id", recordId),
                (rs, n) -> mapHistoryRecord(rs)));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public UUID upsertHistoryRecord(
            ClinicScope scope,
            UUID projectId,
            UUID patientId,
            UUID existingId,
            UUID encounterId,
            String status,
            String source,
            boolean isComplete,
            Map<String, Object> demographics,
            Map<String, Object> medicalHistory,
            Map<String, Object> dentalHistory,
            Map<String, Object> chiefComplaint,
            Map<String, Object> clinicalExam,
            Map<String, Object> radiographs,
            Map<String, Object> diagnosisAndPlan,
            Map<String, Object> dentalChart,
            Map<String, Object> informedConsent,
            Instant approvedAt,
            UUID approvedByUserId) {
        return scope.read(() -> {
            if (existingId != null) {
                int updated = jdbc.update("""
                        update "public"."patient_history_intakes" h
                           set "encounterId" = coalesce(:encounterId, h."encounterId"),
                               "status" = :status,
                               "isComplete" = :isComplete,
                               "demographicsJson" = cast(:demographics as jsonb),
                               "medicalHistoryJson" = cast(:medicalHistory as jsonb),
                               "dentalHistoryJson" = cast(:dentalHistory as jsonb),
                               "chiefComplaintJson" = cast(:chiefComplaint as jsonb),
                               "clinicalExamJson" = cast(:clinicalExam as jsonb),
                               "radiographsJson" = cast(:radiographs as jsonb),
                               "diagnosisAndPlanJson" = cast(:diagnosisAndPlan as jsonb),
                               "dentalChartJson" = cast(:dentalChart as jsonb),
                               "informedConsentJson" = cast(:informedConsent as jsonb),
                               "approvedAt" = coalesce(:approvedAt, h."approvedAt"),
                               "approvedByUserId" = coalesce(:approvedByUserId, h."approvedByUserId"),
                               "updatedAt" = (now() at time zone 'utc')
                         where %s
                           and h."patientId" = :patientId
                           and h."id" = :id
                        """.formatted(scope.predicate("h")),
                        bindScope(scope)
                                .addValue("id", existingId)
                                .addValue("patientId", patientId)
                                .addValue("encounterId", encounterId)
                                .addValue("status", status)
                                .addValue("isComplete", isComplete)
                                .addValue("demographics", toJson(demographics))
                                .addValue("medicalHistory", toJson(medicalHistory))
                                .addValue("dentalHistory", toJson(dentalHistory))
                                .addValue("chiefComplaint", toJson(chiefComplaint))
                                .addValue("clinicalExam", toJson(clinicalExam))
                                .addValue("radiographs", toJson(radiographs))
                                .addValue("diagnosisAndPlan", toJson(diagnosisAndPlan))
                                .addValue("dentalChart", toJson(dentalChart))
                                .addValue("informedConsent", toJson(informedConsent))
                                .addValue("approvedAt", approvedAt != null ? Timestamp.from(approvedAt) : null)
                                .addValue("approvedByUserId", approvedByUserId));
                if (updated > 0) {
                    return existingId;
                }
            }

            UUID id = existingId != null ? existingId : UUID.randomUUID();
            jdbc.update("""
                    insert into "public"."patient_history_intakes" (
                        "id", "organizationId", "projectId", "patientId", "encounterId",
                        "status", "source", "isComplete",
                        "demographicsJson", "medicalHistoryJson", "dentalHistoryJson", "chiefComplaintJson",
                        "clinicalExamJson", "radiographsJson", "diagnosisAndPlanJson",
                        "dentalChartJson", "informedConsentJson",
                        "submittedAt", "approvedAt", "approvedByUserId", "createdAt", "updatedAt"
                    ) values (
                        :id, :org, :proj, :patientId, :encounterId,
                        :status, :source, :isComplete,
                        cast(:demographics as jsonb), cast(:medicalHistory as jsonb),
                        cast(:dentalHistory as jsonb), cast(:chiefComplaint as jsonb),
                        cast(:clinicalExam as jsonb), cast(:radiographs as jsonb),
                        cast(:diagnosisAndPlan as jsonb), cast(:dentalChart as jsonb),
                        cast(:informedConsent as jsonb),
                        (now() at time zone 'utc'), :approvedAt, :approvedByUserId,
                        (now() at time zone 'utc'), (now() at time zone 'utc')
                    )
                    """,
                    bindScope(scope)
                            .addValue("id", id)
                            .addValue("proj", projectId)
                            .addValue("patientId", patientId)
                            .addValue("encounterId", encounterId)
                            .addValue("status", status)
                            .addValue("source", source)
                            .addValue("isComplete", isComplete)
                            .addValue("demographics", toJson(demographics))
                            .addValue("medicalHistory", toJson(medicalHistory))
                            .addValue("dentalHistory", toJson(dentalHistory))
                            .addValue("chiefComplaint", toJson(chiefComplaint))
                            .addValue("clinicalExam", toJson(clinicalExam))
                            .addValue("radiographs", toJson(radiographs))
                            .addValue("diagnosisAndPlan", toJson(diagnosisAndPlan))
                            .addValue("dentalChart", toJson(dentalChart))
                            .addValue("informedConsent", toJson(informedConsent))
                            .addValue("approvedAt", approvedAt != null ? Timestamp.from(approvedAt) : null)
                            .addValue("approvedByUserId", approvedByUserId));
            return id;
        });
    }

    public boolean deleteHistoryRecord(ClinicScope scope, UUID patientId, UUID recordId) {
        return scope.read(() -> jdbc.update("""
                delete from "public"."patient_history_intakes" h
                 where %s
                   and h."patientId" = :patientId
                   and h."id" = :id
                """.formatted(scope.predicate("h")),
                bindScope(scope).addValue("patientId", patientId).addValue("id", recordId)) > 0);
    }

    public boolean isPhoneTakenByAnotherPatient(ClinicScope scope, UUID patientId, String phone) {
        if (phone == null || phone.isBlank()) {
            return false;
        }
        Integer count = scope.read(() -> jdbc.queryForObject("""
                select count(*)
                  from "public"."patients" p
                 where p."organizationId" = :org
                   and p."primaryPhone" = :phone
                   and p."id" <> :patientId
                """,
                bindScope(scope).addValue("phone", phone).addValue("patientId", patientId),
                Integer.class));
        return count != null && count > 0;
    }

    public void syncPatientDemographics(
            ClinicScope scope,
            UUID patientId,
            String displayName,
            String givenName,
            String familyName,
            LocalDate dateOfBirth,
            String gender,
            String occupation,
            String addressLine,
            String email,
            String phoneOrNullIfSkip) {
        scope.read(() -> {
            MapSqlParameterSource params = bindScope(scope)
                    .addValue("patientId", patientId)
                    .addValue("displayName", displayName)
                    .addValue("givenName", givenName)
                    .addValue("familyName", familyName)
                    .addValue("dob", dateOfBirth)
                    .addValue("gender", gender)
                    .addValue("occupation", occupation)
                    .addValue("addressLine", addressLine)
                    .addValue("email", email)
                    .addValue("phone", phoneOrNullIfSkip);

            jdbc.update("""
                    update "public"."patients" p
                       set "displayName" = coalesce(:displayName, p."displayName"),
                           "givenName" = coalesce(:givenName, p."givenName"),
                           "familyName" = coalesce(:familyName, p."familyName"),
                           "dateOfBirth" = coalesce(:dob, p."dateOfBirth"),
                           "gender" = coalesce(:gender, p."gender"),
                           "occupation" = coalesce(:occupation, p."occupation"),
                           "addressLine" = coalesce(:addressLine, p."addressLine"),
                           "primaryEmail" = coalesce(:email, p."primaryEmail"),
                           "primaryPhone" = coalesce(:phone, p."primaryPhone"),
                           "updatedAt" = (current_timestamp at time zone 'utc')
                     where p."organizationId" = :org
                       and p."id" = :patientId
                       and p."deletedAt" is null
                    """, params);
            return null;
        });
    }

    public void upsertEmergencyContact(
            ClinicScope scope,
            UUID projectId,
            UUID patientId,
            String contactName,
            String contactPhone) {
        if ((contactName == null || contactName.isBlank()) && (contactPhone == null || contactPhone.isBlank())) {
            return;
        }
        scope.read(() -> {
            List<UUID> existingIds = jdbc.queryForList("""
                    select r."id"
                      from "public"."patient_relationships" r
                     where r."organizationId" = :org
                       and r."patientId" = :patientId
                       and r."isEmergencyContact" = true
                     order by r."createdAt" desc
                     limit 1
                    """,
                    bindScope(scope).addValue("patientId", patientId),
                    UUID.class);
            if (!existingIds.isEmpty()) {
                jdbc.update("""
                        update "public"."patient_relationships"
                           set "relatedPersonName" = coalesce(:name, "relatedPersonName"),
                               "relatedPersonPhone" = coalesce(:phone, "relatedPersonPhone"),
                               "updatedAt" = (now() at time zone 'utc')
                         where "organizationId" = :org
                           and "id" = :id
                        """,
                        bindScope(scope)
                                .addValue("id", existingIds.get(0))
                                .addValue("name", contactName)
                                .addValue("phone", contactPhone));
            } else {
                jdbc.update("""
                        insert into "public"."patient_relationships" (
                            "id", "organizationId", "projectId", "patientId",
                            "relationshipType", "customRelationship",
                            "relatedPersonName", "relatedPersonPhone",
                            "isGuarantor", "isEmergencyContact", "notes",
                            "createdAt", "updatedAt"
                        ) values (
                            :id, :org, :proj, :patientId,
                            'OTHER', 'Emergency Contact',
                            :name, :phone,
                            false, true, 'Synced from Patient History Intake',
                            (now() at time zone 'utc'), (now() at time zone 'utc')
                        )
                        """,
                        bindScope(scope)
                                .addValue("id", UUID.randomUUID())
                                .addValue("proj", projectId)
                                .addValue("patientId", patientId)
                                .addValue("name", contactName)
                                .addValue("phone", contactPhone));
            }
            return null;
        });
    }

    public void upsertMedicalHistorySummary(
            ClinicScope scope,
            UUID projectId,
            UUID patientId,
            UUID recordedByUserId,
            String allergies,
            String conditions,
            String medications,
            boolean bleedingDisorder,
            boolean cardiacCondition,
            boolean diabetes,
            String notes) {
        scope.read(() -> {
            jdbc.update("""
                    insert into "public"."patient_medical_histories" (
                        "id", "organizationId", "projectId", "patientId",
                        "allergies", "conditions", "currentMedications",
                        "bleedingDisorder", "cardiacCondition", "diabetes",
                        "notes", "recordedByUserId", "createdAt", "updatedAt"
                    ) values (
                        :id, :org, :proj, :patientId,
                        :allergies, :conditions, :meds,
                        :bleeding, :cardiac, :diabetes,
                        :notes, :recordedByUserId, (now() at time zone 'utc'), (now() at time zone 'utc')
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
                            "updatedAt" = (now() at time zone 'utc')
                    """,
                    bindScope(scope)
                            .addValue("id", UUID.randomUUID())
                            .addValue("proj", projectId)
                            .addValue("patientId", patientId)
                            .addValue("allergies", allergies)
                            .addValue("conditions", conditions)
                            .addValue("meds", medications)
                            .addValue("bleeding", bleedingDisorder)
                            .addValue("cardiac", cardiacCondition)
                            .addValue("diabetes", diabetes)
                            .addValue("notes", notes)
                            .addValue("recordedByUserId", recordedByUserId));
            return null;
        });
    }

    private ProjectIntakeTarget mapProjectTarget(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ProjectIntakeTarget(
                rs.getObject("id", UUID.class),
                rs.getObject("organizationId", UUID.class),
                rs.getString("name"),
                rs.getString("slug"),
                rs.getString("intakePortalKey"),
                rs.getBoolean("intakePortalEnabled"),
                rs.getString("defaultLocale"),
                rs.getString("status"),
                rs.getBoolean("isArchived"));
    }

    private IntakeCodeRow mapCodeRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new IntakeCodeRow(
                rs.getObject("id", UUID.class),
                rs.getObject("organizationId", UUID.class),
                rs.getObject("projectId", UUID.class),
                rs.getObject("patientId", UUID.class),
                rs.getString("codeHash"),
                rs.getString("codeHint"),
                toInstant(rs.getTimestamp("expiresAt")),
                rs.getString("sessionTokenHash"),
                toInstant(rs.getTimestamp("sessionExpiresAt")),
                toInstant(rs.getTimestamp("verifiedAt")),
                toInstant(rs.getTimestamp("usedAt")),
                toInstant(rs.getTimestamp("revokedAt")),
                rs.getInt("failedAttempts"),
                toInstant(rs.getTimestamp("createdAt")));
    }

    private PatientIntakeModels.PatientHistoryIntakeRecord mapHistoryRecord(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        Map<String, Object> demographics = parseJson(rs.getString("demographicsJson"));
        Map<String, Object> medical = parseJson(rs.getString("medicalHistoryJson"));
        Map<String, Object> dental = parseJson(rs.getString("dentalHistoryJson"));
        Map<String, Object> complaint = parseJson(rs.getString("chiefComplaintJson"));
        Map<String, Object> clinicalExam = parseJson(rs.getString("clinicalExamJson"));
        Map<String, Object> radiographs = parseJson(rs.getString("radiographsJson"));
        Map<String, Object> diagnosisAndPlan = parseJson(rs.getString("diagnosisAndPlanJson"));
        Map<String, Object> dentalChart = parseJson(rs.getString("dentalChartJson"));
        Map<String, Object> informedConsent = parseJson(rs.getString("informedConsentJson"));

        Instant submittedAt = toInstant(rs.getTimestamp("submittedAt"));
        String date = submittedAt != null
                ? submittedAt.atZone(ZoneOffset.UTC).toLocalDate().toString()
                : LocalDate.now(ZoneOffset.UTC).toString();
        Object fullNameObj = demographics.get("fullName");
        String fullName = fullNameObj != null && !String.valueOf(fullNameObj).isBlank()
                ? String.valueOf(fullNameObj)
                : rs.getString("patientDisplayName");

        return new PatientIntakeModels.PatientHistoryIntakeRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("patientId", UUID.class),
                rs.getObject("encounterId", UUID.class),
                rs.getString("status"),
                rs.getString("source"),
                rs.getBoolean("isComplete"),
                date,
                fullName,
                demographics,
                medical,
                dental,
                complaint,
                clinicalExam,
                radiographs,
                diagnosisAndPlan,
                dentalChart,
                informedConsent,
                submittedAt,
                toInstant(rs.getTimestamp("approvedAt")),
                rs.getObject("approvedByUserId", UUID.class));
    }

    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map == null ? Map.of() : map);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize intake JSON", e);
        }
    }

    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private MapSqlParameterSource bindScope(ClinicScope scope) {
        return scope.bind().addValue("org", scope.queryScope().organizationId());
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
