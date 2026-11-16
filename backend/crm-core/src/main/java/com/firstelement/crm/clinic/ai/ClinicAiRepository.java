package com.firstelement.crm.clinic.ai;

import com.firstelement.crm.clinic.ClinicScope;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Repository
public class ClinicAiRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ClinicAiRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ClinicAiModels.AiRunRow> list(ClinicScope scope, String status, int limit) {
        return scope.read(() -> {
            StringBuilder sql = new StringBuilder("""
                    select r."id", r."projectId", r."conversationId", r."agentId",
                           r."status"::text as "status",
                           r."decision"::text as "decision",
                           r."confidence", r."decisionReason",
                           r."provider"::text as "provider",
                           r."model", r."errorMessage", r."latencyMs",
                           r."startedAt", r."finishedAt"
                      from "public"."ai_runs" r
                     where\s""");
            sql.append(scope.predicate("r"));

            MapSqlParameterSource params = scope.bind()
                    .addValue("limit", Math.min(Math.max(limit, 1), 200));

            if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
                sql.append(" and r.\"status\"::text = :status");
                params.addValue("status", status.trim().toUpperCase());
            }

            sql.append(" order by r.\"startedAt\" desc, r.\"id\" desc limit :limit");

            return jdbc.query(sql.toString(), params, (rs, rowNum) -> mapRunRow(rs));
        });
    }

    public ClinicAiModels.ClinicAiOverview overview(ClinicScope scope) {
        return scope.read(() -> {
            String sql = """
                    select count(*)::int as total_runs,
                           count(case when r."status"::text = 'SUCCEEDED' then 1 end)::int as succeeded_runs,
                           count(case when r."status"::text in ('FAILED', 'TIMED_OUT') then 1 end)::int as failed_runs,
                           coalesce(avg(r."latencyMs"), 0)::float8 as avg_latency
                      from "public"."ai_runs" r
                     where\s""" + scope.predicate("r");

            return jdbc.queryForObject(sql, scope.bind(), (rs, rowNum) -> {
                int total = rs.getInt("total_runs");
                int succeeded = rs.getInt("succeeded_runs");
                int failed = rs.getInt("failed_runs");
                double avgLatency = rs.getDouble("avg_latency");

                List<ClinicAiModels.AiRunRow> recent = list(scope, null, 15);

                return new ClinicAiModels.ClinicAiOverview(
                        total,
                        succeeded,
                        failed,
                        avgLatency,
                        true, // CLN-09: clinical suggestions strictly disabled
                        recent
                );
            });
        });
    }

    // -------------------------------------------------------------------------
    // Governed Proposals (Slice S9)
    // -------------------------------------------------------------------------

    public void insertProposal(
            UUID id,
            UUID organizationId,
            UUID projectId,
            UUID patientId,
            String proposalType,
            String status,
            String model,
            String provider,
            String promptTemplate,
            String inputPayloadRedacted,
            String outputContent,
            String transparentFactors,
            Integer confidencePercent,
            boolean phiRedacted
    ) {
        String sql = """
                insert into "public"."clinic_ai_proposals" (
                    "id", "organizationId", "projectId", "patientId",
                    "proposalType", "status", "model", "provider", "promptTemplate",
                    "inputPayloadRedacted", "outputContent", "transparentFactors",
                    "confidencePercent", "phiRedacted", "createdAt"
                ) values (
                    :id, :organizationId, :projectId, :patientId,
                    :proposalType, :status, :model, :provider, :promptTemplate,
                    :inputPayloadRedacted::jsonb, :outputContent::jsonb, :transparentFactors::jsonb,
                    :confidencePercent, :phiRedacted, current_timestamp
                )
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("organizationId", organizationId)
                .addValue("projectId", projectId)
                .addValue("patientId", patientId)
                .addValue("proposalType", proposalType)
                .addValue("status", status)
                .addValue("model", model)
                .addValue("provider", provider)
                .addValue("promptTemplate", promptTemplate)
                .addValue("inputPayloadRedacted", inputPayloadRedacted == null ? "{}" : inputPayloadRedacted)
                .addValue("outputContent", outputContent == null ? "{}" : outputContent)
                .addValue("transparentFactors", transparentFactors == null ? "{}" : transparentFactors)
                .addValue("confidencePercent", confidencePercent)
                .addValue("phiRedacted", phiRedacted);

        jdbc.update(sql, params);
    }

    public Optional<ClinicAiModels.ClinicAiProposalRow> findProposalById(ClinicScope scope, UUID proposalId) {
        return scope.read(() -> {
            String sql = """
                    select p."id", p."organizationId", p."projectId", p."patientId",
                           coalesce(pat."displayName", pat."givenName" || ' ' || pat."familyName") as "patientName",
                           p."proposalType", p."status", p."model", p."provider", p."promptTemplate",
                           p."inputPayloadRedacted"::text as "inputPayloadRedacted",
                           p."outputContent"::text as "outputContent",
                           p."transparentFactors"::text as "transparentFactors",
                           p."confidencePercent", p."phiRedacted",
                           p."approvedByUserId",
                           coalesce(u."fullName", u.email) as "approverName",
                           p."approvedAt", p."rejectionReason", p."createdAt"
                      from "public"."clinic_ai_proposals" p
                      left join "public"."patients" pat on pat."id" = p."patientId"
                      left join "public"."users" u on u."id" = p."approvedByUserId"
                     where p."id" = :proposalId and\s""" + scope.predicate("p");

            MapSqlParameterSource params = scope.bind().addValue("proposalId", proposalId);
            List<ClinicAiModels.ClinicAiProposalRow> rows = jdbc.query(sql, params, (rs, rowNum) -> mapProposalRow(rs));
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
        });
    }

    public List<ClinicAiModels.ClinicAiProposalRow> listProposals(
            ClinicScope scope,
            UUID patientId,
            String proposalType,
            String status,
            int limit
    ) {
        return scope.read(() -> {
            StringBuilder sql = new StringBuilder("""
                    select p."id", p."organizationId", p."projectId", p."patientId",
                           coalesce(pat."displayName", pat."givenName" || ' ' || pat."familyName") as "patientName",
                           p."proposalType", p."status", p."model", p."provider", p."promptTemplate",
                           p."inputPayloadRedacted"::text as "inputPayloadRedacted",
                           p."outputContent"::text as "outputContent",
                           p."transparentFactors"::text as "transparentFactors",
                           p."confidencePercent", p."phiRedacted",
                           p."approvedByUserId",
                           coalesce(u."fullName", u.email) as "approverName",
                           p."approvedAt", p."rejectionReason", p."createdAt"
                      from "public"."clinic_ai_proposals" p
                      left join "public"."patients" pat on pat."id" = p."patientId"
                      left join "public"."users" u on u."id" = p."approvedByUserId"
                     where\s""");
            sql.append(scope.predicate("p"));

            MapSqlParameterSource params = scope.bind()
                    .addValue("limit", Math.min(Math.max(limit, 1), 100));

            if (patientId != null) {
                sql.append(" and p.\"patientId\" = :patientId::uuid");
                params.addValue("patientId", patientId);
            }
            if (proposalType != null && !proposalType.isBlank() && !"ALL".equalsIgnoreCase(proposalType)) {
                sql.append(" and p.\"proposalType\" = :proposalType");
                params.addValue("proposalType", proposalType.trim());
            }
            if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
                sql.append(" and p.\"status\" = :status");
                params.addValue("status", status.trim().toUpperCase());
            }

            sql.append(" order by p.\"createdAt\" desc, p.\"id\" desc limit :limit");

            return jdbc.query(sql.toString(), params, (rs, rowNum) -> mapProposalRow(rs));
        });
    }

    public boolean updateProposalStatus(
            ClinicScope scope,
            UUID proposalId,
            String status,
            UUID approvedByUserId,
            String rejectionReason
    ) {
        String sql = """
                update "public"."clinic_ai_proposals" p
                   set "status" = :status,
                       "approvedByUserId" = :approvedByUserId::uuid,
                       "approvedAt" = case when :status = 'APPROVED' then current_timestamp else null end,
                       "rejectionReason" = :rejectionReason
                 where p."id" = :proposalId
                   and p."status" = 'DRAFT'
                   and\s""" + scope.predicate("p");

        MapSqlParameterSource params = scope.bind()
                .addValue("proposalId", proposalId)
                .addValue("status", status)
                .addValue("approvedByUserId", approvedByUserId)
                .addValue("rejectionReason", rejectionReason);

        return jdbc.update(sql, params) > 0;
    }

    // -------------------------------------------------------------------------
    // Emergency Kill Switch & Governance (Slice S9)
    // -------------------------------------------------------------------------

    public ClinicAiModels.ClinicAiGovernanceRow getGovernance(ClinicScope scope) {
        return scope.read(() -> {
            String sql = """
                    select g."id", g."organizationId", g."projectId", g."killSwitchActive",
                           g."killSwitchReason", g."killSwitchEngagedByUserId",
                           coalesce(u."fullName", u.email) as "killSwitchEngagedByName",
                           g."killSwitchEngagedAt", g."clinicalSuggestionsDisabled", g."updatedAt"
                      from "public"."clinic_ai_governance" g
                      left join "public"."users" u on u."id" = g."killSwitchEngagedByUserId"
                     where\s""" + scope.predicate("g");

            List<ClinicAiModels.ClinicAiGovernanceRow> rows = jdbc.query(sql, scope.bind(), (rs, rowNum) -> {
                Timestamp engaged = rs.getTimestamp("killSwitchEngagedAt");
                Timestamp updated = rs.getTimestamp("updatedAt");
                return new ClinicAiModels.ClinicAiGovernanceRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("organizationId", UUID.class),
                        rs.getObject("projectId", UUID.class),
                        rs.getBoolean("killSwitchActive"),
                        rs.getString("killSwitchReason"),
                        rs.getObject("killSwitchEngagedByUserId", UUID.class),
                        rs.getString("killSwitchEngagedByName"),
                        engaged == null ? null : engaged.toInstant(),
                        rs.getBoolean("clinicalSuggestionsDisabled"),
                        updated == null ? Instant.now() : updated.toInstant()
                );
            });

            if (!rows.isEmpty()) {
                return rows.get(0);
            }

            // Default safe governance row if not yet initialized
            return new ClinicAiModels.ClinicAiGovernanceRow(
                    UUID.randomUUID(),
                    scope.queryScope().organizationId(),
                    scope.projectId() != null ? scope.projectId() : (!scope.accessibleProjectIds().isEmpty() ? scope.accessibleProjectIds().get(0) : null),
                    false,
                    null,
                    null,
                    null,
                    null,
                    true,
                    Instant.now()
            );
        });
    }

    public void updateKillSwitch(
            ClinicScope scope,
            boolean killSwitchActive,
            String reason,
            UUID engagedByUserId
    ) {
        UUID orgId = scope.queryScope().organizationId();
        UUID projId = scope.projectId() != null
                ? scope.projectId()
                : (!scope.accessibleProjectIds().isEmpty() ? scope.accessibleProjectIds().get(0) : null);

        String checkSql = """
                select "id" from "public"."clinic_ai_governance"
                 where "organizationId" = :orgId and (:projId::uuid is null or "projectId" = :projId::uuid)
                 limit 1
                """;
        MapSqlParameterSource checkParams = new MapSqlParameterSource()
                .addValue("orgId", orgId)
                .addValue("projId", projId);

        List<UUID> existing = jdbc.query(checkSql, checkParams, (rs, rowNum) -> rs.getObject("id", UUID.class));

        if (existing.isEmpty()) {
            String insertSql = """
                    insert into "public"."clinic_ai_governance" (
                        "id", "organizationId", "projectId", "killSwitchActive",
                        "killSwitchReason", "killSwitchEngagedByUserId", "killSwitchEngagedAt",
                        "clinicalSuggestionsDisabled", "updatedAt"
                    ) values (
                        :id, :orgId, :projId, :killSwitchActive,
                        :reason, :engagedByUserId::uuid,
                        case when :killSwitchActive then current_timestamp else null end,
                        true, current_timestamp
                    )
                    """;
            MapSqlParameterSource insertParams = new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID())
                    .addValue("orgId", orgId)
                    .addValue("projId", projId)
                    .addValue("killSwitchActive", killSwitchActive)
                    .addValue("reason", reason)
                    .addValue("engagedByUserId", engagedByUserId);
            jdbc.update(insertSql, insertParams);
        } else {
            String updateSql = """
                    update "public"."clinic_ai_governance"
                       set "killSwitchActive" = :killSwitchActive,
                           "killSwitchReason" = :reason,
                           "killSwitchEngagedByUserId" = :engagedByUserId::uuid,
                           "killSwitchEngagedAt" = case when :killSwitchActive then current_timestamp else null end,
                           "updatedAt" = current_timestamp
                     where "id" = :id
                    """;
            MapSqlParameterSource updateParams = new MapSqlParameterSource()
                    .addValue("id", existing.get(0))
                    .addValue("killSwitchActive", killSwitchActive)
                    .addValue("reason", reason)
                    .addValue("engagedByUserId", engagedByUserId);
            jdbc.update(updateSql, updateParams);
        }
    }

    // -------------------------------------------------------------------------
    // Clinical Data Fetchers for Governed Proposals
    // -------------------------------------------------------------------------

    public Map<String, Object> fetchEncounterSummaryData(ClinicScope scope, UUID patientId, UUID encounterId) {
        return scope.read(() -> {
            Map<String, Object> data = new HashMap<>();

            // 1. Patient basic profile
            String patSql = """
                    select p."id", p."displayName", p."givenName", p."familyName", p."dateOfBirth", p."locale"
                      from "public"."patients" p
                     where p."id" = :patientId::uuid and\s""" + scope.predicate("p");
            List<Map<String, Object>> patList = jdbc.queryForList(patSql, scope.bind().addValue("patientId", patientId));
            if (!patList.isEmpty()) {
                data.put("patient", patList.get(0));
            }

            // 2. Encounter info
            if (encounterId != null) {
                String encSql = """
                        select e."id", e."chiefComplaint", e."diagnosis", e."status", e."visitDate",
                               coalesce(d."name", coalesce(u."fullName", u.email)) as "doctorName"
                          from "public"."clinical_encounters" e
                          left join "public"."doctors" d on d."id" = e."doctorId"
                          left join "public"."users" u on u."id" = d."userId"
                         where e."id" = :encounterId::uuid and\s""" + scope.predicate("e");
                List<Map<String, Object>> encList = jdbc.queryForList(encSql, scope.bind().addValue("encounterId", encounterId));
                if (!encList.isEmpty()) {
                    data.put("encounter", encList.get(0));
                }
            }

            // 3. Structured medical history / alerts
            String medSql = """
                    select mh."allergies", mh."conditions", mh."currentMedications", mh."bleedingDisorder", mh."cardiacCondition", mh."diabetes"
                      from "public"."patient_medical_histories" mh
                     where mh."patientId" = :patientId::uuid and\s""" + scope.predicate("mh");
            List<Map<String, Object>> medList = jdbc.queryForList(medSql, scope.bind().addValue("patientId", patientId));
            if (!medList.isEmpty()) {
                data.put("medicalHistory", medList.get(0));
            }

            return data;
        });
    }

    public List<Map<String, Object>> fetchRecallCandidateMetrics(ClinicScope scope, int overdueDaysThreshold, int limit) {
        return scope.read(() -> {
            String sql = """
                    select p."id" as "patientId",
                           coalesce(p."displayName", p."givenName" || ' ' || p."familyName") as "patientName",
                           p."primaryPhone" as "phone",
                           max(a."appointmentDate") as "lastVisitDate",
                           coalesce(extract(day from (current_timestamp - max(a."appointmentDate")))::int, 999) as "daysSinceLastVisit",
                           count(case when a."status"::text = 'NO_SHOW' then 1 end)::int as "noShowCount",
                           count(case when a."status"::text = 'COMPLETED' then 1 end)::int as "completedCount"
                      from "public"."patients" p
                      left join "public"."appointments" a
                        on (a."patientId" = p."id" or (a."patientId" is null and a."customerId" is not null and a."customerId" = p."customerId"))
                       and a."organizationId" = p."organizationId"
                     where p."deletedAt" is null
                       and\s""" + scope.predicate("p") + """
                     group by p."id", p."displayName", p."givenName", p."familyName", p."primaryPhone"
                    having max(a."appointmentDate") is null or max(a."appointmentDate") < (current_timestamp - (:overdueDaysThreshold || ' days')::interval)
                     order by "daysSinceLastVisit" desc
                     limit :limit
                    """;

            MapSqlParameterSource params = scope.bind()
                    .addValue("overdueDaysThreshold", Math.max(overdueDaysThreshold, 30))
                    .addValue("limit", Math.min(Math.max(limit, 1), 50));

            return jdbc.queryForList(sql, params);
        });
    }

    public Map<String, Object> fetchMissingFieldAudits(ClinicScope scope, UUID patientId) {
        return scope.read(() -> {
            Map<String, Object> audit = new LinkedHashMap<>();

            // 1. Check patient profile completeness
            String patSql = """
                    select p."id", p."displayName", p."dateOfBirth", p."primaryPhone", p."primaryEmail", p."locale"
                      from "public"."patients" p
                     where p."id" = :patientId::uuid and\s""" + scope.predicate("p");
            List<Map<String, Object>> patList = jdbc.queryForList(patSql, scope.bind().addValue("patientId", patientId));
            if (!patList.isEmpty()) {
                Map<String, Object> p = patList.get(0);
                audit.put("hasPhone", p.get("primaryPhone") != null);
                audit.put("hasEmail", p.get("primaryEmail") != null);
                audit.put("hasDob", p.get("dateOfBirth") != null);
            }

            // 2. Check active consents
            String conSql = """
                    select count(*)::int as active_consents
                      from "public"."patient_consents" c
                     where c."patientId" = :patientId::uuid
                       and c."status" = 'GRANTED'
                       and c."revokedAt" is null
                       and\s""" + scope.predicate("c");
            Integer activeConsents = jdbc.queryForObject(conSql, scope.bind().addValue("patientId", patientId), Integer.class);
            audit.put("activeConsentsCount", activeConsents == null ? 0 : activeConsents);

            // 3. Check medical history
            String medSql = """
                    select count(*)::int as med_records,
                           max(mh."createdAt") as "lastMedHistoryDate"
                      from "public"."patient_medical_histories" mh
                     where mh."patientId" = :patientId::uuid and\s""" + scope.predicate("mh");
            List<Map<String, Object>> med = jdbc.queryForList(medSql, scope.bind().addValue("patientId", patientId));
            if (!med.isEmpty()) {
                audit.put("hasMedicalHistory", ((Number) med.get(0).get("med_records")).intValue() > 0);
                audit.put("lastMedicalHistoryUpdate", med.get(0).get("lastMedHistoryDate"));
            }

            // 4. Check emergency contact
            String relSql = """
                    select count(*)::int as emergency_contacts
                      from "public"."patient_relationships" r
                     where r."patientId" = :patientId::uuid
                       and r."isEmergencyContact" = true
                       and\s""" + scope.predicate("r");
            Integer emergencyCount = jdbc.queryForObject(relSql, scope.bind().addValue("patientId", patientId), Integer.class);
            audit.put("hasEmergencyContact", emergencyCount != null && emergencyCount > 0);

            return audit;
        });
    }

    public List<Map<String, Object>> fetchUnpaidInvoices(ClinicScope scope, UUID patientId) {
        return scope.read(() -> {
            String sql = """
                    select i."id" as "invoiceId", i."invoiceNumber", i."totalAmount", i."paidAmount",
                           (i."totalAmount" - i."paidAmount") as "balanceDue", i."currency", i."createdAt"
                      from "public"."clinic_invoices" i
                     where (:patientId::uuid is null or i."patientId" = :patientId::uuid)
                       and i."status" in ('ISSUED', 'PARTIALLY_PAID')
                       and (i."totalAmount" - i."paidAmount") > 0
                       and\s""" + scope.predicate("i") + """
                     order by i."createdAt" asc
                    """;
            MapSqlParameterSource params = scope.bind().addValue("patientId", patientId);
            return jdbc.queryForList(sql, params);
        });
    }

    // -------------------------------------------------------------------------
    // Row Mappers
    // -------------------------------------------------------------------------

    private static ClinicAiModels.AiRunRow mapRunRow(ResultSet rs) throws SQLException {
        Timestamp started = rs.getTimestamp("startedAt");
        Timestamp finished = rs.getTimestamp("finishedAt");

        return new ClinicAiModels.AiRunRow(
                rs.getObject("id", UUID.class),
                rs.getObject("projectId", UUID.class),
                rs.getObject("conversationId", UUID.class),
                rs.getObject("agentId", UUID.class),
                rs.getString("status"),
                rs.getString("decision"),
                rs.getObject("confidence", Integer.class),
                rs.getString("decisionReason"),
                rs.getString("provider"),
                rs.getString("model"),
                rs.getString("errorMessage"),
                rs.getObject("latencyMs", Integer.class),
                started == null ? null : started.toInstant(),
                finished == null ? null : finished.toInstant()
        );
    }

    private static ClinicAiModels.ClinicAiProposalRow mapProposalRow(ResultSet rs) throws SQLException {
        Timestamp approved = rs.getTimestamp("approvedAt");
        Timestamp created = rs.getTimestamp("createdAt");

        return new ClinicAiModels.ClinicAiProposalRow(
                rs.getObject("id", UUID.class),
                rs.getObject("organizationId", UUID.class),
                rs.getObject("projectId", UUID.class),
                rs.getObject("patientId", UUID.class),
                rs.getString("patientName"),
                rs.getString("proposalType"),
                rs.getString("status"),
                rs.getString("model"),
                rs.getString("provider"),
                rs.getString("promptTemplate"),
                rs.getString("inputPayloadRedacted"),
                rs.getString("outputContent"),
                rs.getString("transparentFactors"),
                rs.getObject("confidencePercent", Integer.class),
                rs.getBoolean("phiRedacted"),
                rs.getObject("approvedByUserId", UUID.class),
                rs.getString("approverName"),
                approved == null ? null : approved.toInstant(),
                rs.getString("rejectionReason"),
                created == null ? Instant.now() : created.toInstant()
        );
    }
}
