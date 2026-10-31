package com.firstelement.crm.clinic.policy;

import com.firstelement.crm.clinic.ClinicScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Scoped repository for clinic settings and policies (USR-09, BIL-07).
 */
@Repository
public class ClinicPolicyRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ClinicPolicyRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Finds active policy for a clinic project, or returns standard defaults if unconfigured.
     */
    public ClinicPolicyModels.ClinicPolicyRow findPolicy(ClinicScope scope) {
        return scope.read(() -> {
            try {
                return jdbc.queryForObject("""
                        select cp."organizationId", cp."projectId", cp."doctorSeesAllPatients",
                               cp."defaultSlotDurationMinutes", cp."allowDoubleBooking",
                               cp."primaryCurrency", cp."toothNotationSystem",
                               cp."updatedAt", cp."updatedBy", u."fullName" as "updatedByName"
                          from "clinic_policies" cp
                          left join "users" u on u."id" = cp."updatedBy"
                         where %s
                        """.formatted(scope.predicate("cp")),
                        scope.bind(),
                        ClinicPolicyRepository::mapPolicyRow);
            } catch (EmptyResultDataAccessException e) {
                return new ClinicPolicyModels.ClinicPolicyRow(
                        scope.queryScope().organizationId(),
                        scope.projectId(),
                        false,
                        30,
                        false,
                        "JOD",
                        "FDI",
                        LocalDateTime.now(),
                        null,
                        null
                );
            }
        });
    }

    /**
     * Upserts policy configuration for the scoped clinic.
     */
    public void upsertPolicy(
            ClinicScope scope,
            boolean doctorSeesAllPatients,
            int defaultSlotDurationMinutes,
            boolean allowDoubleBooking,
            String primaryCurrency,
            String toothNotationSystem,
            UUID updatedBy) {
        scope.read(() -> {
            MapSqlParameterSource params = scope.bind()
                    .addValue("orgId", scope.queryScope().organizationId())
                    .addValue("projId", scope.projectId())
                    .addValue("doctorSeesAllPatients", doctorSeesAllPatients)
                    .addValue("defaultSlotDurationMinutes", defaultSlotDurationMinutes)
                    .addValue("allowDoubleBooking", allowDoubleBooking)
                    .addValue("primaryCurrency", primaryCurrency)
                    .addValue("toothNotationSystem", toothNotationSystem)
                    .addValue("updatedBy", updatedBy);

            jdbc.update("""
                    insert into clinic_policies (
                        "organizationId", "projectId", "doctorSeesAllPatients",
                        "defaultSlotDurationMinutes", "allowDoubleBooking",
                        "primaryCurrency", "toothNotationSystem", "updatedAt", "updatedBy"
                    ) values (
                        :orgId, :projId, :doctorSeesAllPatients,
                        :defaultSlotDurationMinutes, :allowDoubleBooking,
                        :primaryCurrency, :toothNotationSystem, (now() at time zone 'UTC'), :updatedBy
                    )
                    on conflict ("organizationId", "projectId")
                    do update set
                        "doctorSeesAllPatients" = :doctorSeesAllPatients,
                        "defaultSlotDurationMinutes" = :defaultSlotDurationMinutes,
                        "allowDoubleBooking" = :allowDoubleBooking,
                        "primaryCurrency" = :primaryCurrency,
                        "toothNotationSystem" = :toothNotationSystem,
                        "updatedAt" = (now() at time zone 'UTC'),
                        "updatedBy" = :updatedBy
                    """, params);
            return null;
        });
    }

    private static ClinicPolicyModels.ClinicPolicyRow mapPolicyRow(ResultSet rs, int rowNum) throws SQLException {
        return new ClinicPolicyModels.ClinicPolicyRow(
                (UUID) rs.getObject("organizationId"),
                (UUID) rs.getObject("projectId"),
                rs.getBoolean("doctorSeesAllPatients"),
                rs.getInt("defaultSlotDurationMinutes"),
                rs.getBoolean("allowDoubleBooking"),
                rs.getString("primaryCurrency"),
                rs.getString("toothNotationSystem"),
                rs.getTimestamp("updatedAt").toLocalDateTime(),
                (UUID) rs.getObject("updatedBy"),
                rs.getString("updatedByName")
        );
    }
}
