package com.firstelement.crm.clinic.operations;

import com.firstelement.crm.clinic.ClinicScope;
import com.firstelement.crm.error.ResourceNotFoundException;
import com.firstelement.crm.error.ValidationFailedException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Persistence operations for Clinic Treatment Room Management & Utilization Analytics (Req 4). */
final class TreatmentRoomOperationsStore {

    private final NamedParameterJdbcTemplate jdbc;

    TreatmentRoomOperationsStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<ClinicOperationsModels.TreatmentRoomRow> listRooms(ClinicScope scope, UUID clinicId) {
        return scope.read(() -> {
            StringBuilder sql = new StringBuilder("""
                    select tr.id, tr."organizationId", tr."projectId", tr."clinicId",
                           coalesce(cl."name", 'Main Branch') as "clinicName",
                           tr."name", tr."roomNumber", tr."roomType",
                           tr."assignedDoctorId", coalesce(d."name", '') as "assignedDoctorName",
                           tr."operationalStartHour", tr."operationalEndHour", tr."isActive",
                           (select coalesce(occ."doctorName", d2."name", 'Assigned Clinician')
                              from appointments occ
                              left join doctors d2 on d2.id = occ."doctorId" and d2."organizationId" = occ."organizationId"
                             where occ."organizationId" = tr."organizationId"
                               and occ."treatmentRoomId" = tr.id
                               and occ."status" not in ('CANCELLED', 'NO_SHOW', 'COMPLETED')
                               and (occ."status" = 'IN_PROGRESS'
                                    or (occ."appointmentDate" <= (now() at time zone 'utc')
                                        and (occ."appointmentDate" + (occ."durationMinutes" * interval '1 minute')) > (now() at time zone 'utc')))
                             order by occ."appointmentDate" desc
                             limit 1) as "occupiedByDoctor",
                           coalesce((select count(*)
                                       from appointments a1
                                      where a1."organizationId" = tr."organizationId"
                                        and a1."treatmentRoomId" = tr.id
                                        and a1."status" not in ('CANCELLED', 'NO_SHOW')), 0) as "totalAppointmentsCount",
                           coalesce((select count(*)
                                       from appointments a2
                                      where a2."organizationId" = tr."organizationId"
                                        and a2."treatmentRoomId" = tr.id
                                        and a2."status" = 'COMPLETED'), 0) as "completedProceduresCount",
                           coalesce((select sum(a3."durationMinutes")
                                       from appointments a3
                                      where a3."organizationId" = tr."organizationId"
                                        and a3."treatmentRoomId" = tr.id
                                        and a3."status" not in ('CANCELLED', 'NO_SHOW')
                                        and a3."appointmentDate" >= (now() at time zone 'utc') - interval '30 days'), 0) as "bookedMinutes30d",
                           coalesce((select sum(coalesce(a4."price", 0) * coalesce(a4."quantity", 1))
                                       from appointments a4
                                      where a4."organizationId" = tr."organizationId"
                                        and a4."treatmentRoomId" = tr.id
                                        and a4."status" = 'COMPLETED'), 0.000) as "roomRevenue"
                      from treatment_rooms tr
                      left join clinic_locations cl on cl.id = tr."clinicId" and cl."organizationId" = tr."organizationId"
                      left join doctors d on d.id = tr."assignedDoctorId" and d."organizationId" = tr."organizationId"
                     where %s
                    """.formatted(scope.predicate("tr")));
            MapSqlParameterSource params = scope.bind();
            if (clinicId != null) {
                sql.append(" and tr.\"clinicId\" = :clinicId");
                params.addValue("clinicId", clinicId);
            }
            sql.append(" order by tr.\"isActive\" desc, tr.\"name\" asc");
            return jdbc.query(sql.toString(), params, TreatmentRoomOperationsStore::mapRoom);
        });
    }

    ClinicOperationsModels.TreatmentRoomRow createRoom(
            ClinicScope scope, ClinicOperationsModels.CreateTreatmentRoomRequest req) {
        if (!scope.isProjectScoped()) {
            throw new ValidationFailedException("A treatment room must belong to one branch project.");
        }
        String startHour = normalizeHour(req.operationalStartHour(), "09:00");
        String endHour = normalizeHour(req.operationalEndHour(), "17:00");
        String roomType = (req.roomType() != null && !req.roomType().isBlank())
                ? req.roomType().trim().toUpperCase()
                : "GENERAL_OPERATORY";
        boolean active = req.isActive() == null || req.isActive();

        return scope.read(() -> {
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    insert into treatment_rooms
                        (id, "organizationId", "projectId", "clinicId", "name", "roomNumber",
                         "roomType", "assignedDoctorId", "operationalStartHour", "operationalEndHour", "isActive")
                    values
                        (:id, :orgId, :projId, :clinicId, :name, :roomNumber,
                         :roomType, :assignedDoctorId, :startHour, :endHour, :isActive)
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", id)
                            .addValue("orgId", scope.queryScope().organizationId())
                            .addValue("projId", scope.projectId())
                            .addValue("clinicId", req.clinicId())
                            .addValue("name", req.name().trim())
                            .addValue("roomNumber", trimToNull(req.roomNumber()))
                            .addValue("roomType", roomType)
                            .addValue("assignedDoctorId", req.assignedDoctorId())
                            .addValue("startHour", startHour)
                            .addValue("endHour", endHour)
                            .addValue("isActive", active));
            return findRoom(scope, id);
        });
    }

    ClinicOperationsModels.TreatmentRoomRow updateRoom(
            ClinicScope scope, UUID roomId, ClinicOperationsModels.CreateTreatmentRoomRequest req) {
        return scope.read(() -> {
            ClinicOperationsModels.TreatmentRoomRow existing = findRoom(scope, roomId);
            String name = (req.name() != null && !req.name().isBlank()) ? req.name().trim() : existing.name();
            String roomNumber = req.roomNumber() != null ? trimToNull(req.roomNumber()) : existing.roomNumber();
            String roomType = (req.roomType() != null && !req.roomType().isBlank())
                    ? req.roomType().trim().toUpperCase() : existing.roomType();
            UUID clinicId = req.clinicId() != null ? req.clinicId() : existing.clinicId();
            UUID doctorId = req.assignedDoctorId() != null ? req.assignedDoctorId() : existing.assignedDoctorId();
            String startHour = req.operationalStartHour() != null
                    ? normalizeHour(req.operationalStartHour(), existing.operationalStartHour())
                    : existing.operationalStartHour();
            String endHour = req.operationalEndHour() != null
                    ? normalizeHour(req.operationalEndHour(), existing.operationalEndHour())
                    : existing.operationalEndHour();
            boolean isActive = req.isActive() != null ? req.isActive() : existing.isActive();

            jdbc.update("""
                    update treatment_rooms tr
                       set "clinicId" = :clinicId,
                           "name" = :name,
                           "roomNumber" = :roomNumber,
                           "roomType" = :roomType,
                           "assignedDoctorId" = :doctorId,
                           "operationalStartHour" = :startHour,
                           "operationalEndHour" = :endHour,
                           "isActive" = :isActive,
                           "updatedAt" = (now() at time zone 'utc')
                     where %s and tr.id = :id
                    """.formatted(scope.predicate("tr")),
                    scope.bind()
                            .addValue("id", roomId)
                            .addValue("clinicId", clinicId)
                            .addValue("name", name)
                            .addValue("roomNumber", roomNumber)
                            .addValue("roomType", roomType)
                            .addValue("doctorId", doctorId)
                            .addValue("startHour", startHour)
                            .addValue("endHour", endHour)
                            .addValue("isActive", isActive));
            return findRoom(scope, roomId);
        });
    }

    boolean deleteRoom(ClinicScope scope, UUID roomId) {
        return scope.read(() -> {
            jdbc.update("""
                    update appointments a
                       set "treatmentRoomId" = null
                     where %s and a."treatmentRoomId" = :roomId
                    """.formatted(scope.predicate("a")),
                    scope.bind().addValue("roomId", roomId));
            return jdbc.update("""
                    delete from treatment_rooms tr
                     where %s and tr.id = :id
                    """.formatted(scope.predicate("tr")),
                    scope.bind().addValue("id", roomId)) > 0;
        });
    }

    private ClinicOperationsModels.TreatmentRoomRow findRoom(ClinicScope scope, UUID roomId) {
        List<ClinicOperationsModels.TreatmentRoomRow> rows = jdbc.query("""
                select tr.id, tr."organizationId", tr."projectId", tr."clinicId",
                       coalesce(cl."name", 'Main Branch') as "clinicName",
                       tr."name", tr."roomNumber", tr."roomType",
                       tr."assignedDoctorId", coalesce(d."name", '') as "assignedDoctorName",
                       tr."operationalStartHour", tr."operationalEndHour", tr."isActive",
                       null as "occupiedByDoctor",
                       0 as "totalAppointmentsCount",
                       0 as "completedProceduresCount",
                       0 as "bookedMinutes30d",
                       0.000 as "roomRevenue"
                  from treatment_rooms tr
                  left join clinic_locations cl on cl.id = tr."clinicId" and cl."organizationId" = tr."organizationId"
                  left join doctors d on d.id = tr."assignedDoctorId" and d."organizationId" = tr."organizationId"
                 where %s and tr.id = :id
                 limit 1
                """.formatted(scope.predicate("tr")),
                scope.bind().addValue("id", roomId),
                TreatmentRoomOperationsStore::mapRoom);
        return rows.stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Treatment room not found"));
    }

    private static ClinicOperationsModels.TreatmentRoomRow mapRoom(ResultSet rs, int n) throws SQLException {
        boolean isActive = rs.getBoolean("isActive");
        String occupiedBy = rs.getString("occupiedByDoctor");
        String status = !isActive ? "INACTIVE" : (occupiedBy != null && !occupiedBy.isBlank() ? "OCCUPIED" : "AVAILABLE");
        String startStr = rs.getString("operationalStartHour");
        String endStr = rs.getString("operationalEndHour");
        int booked30d = rs.getInt("bookedMinutes30d");

        double utilization = 0.0;
        try {
            LocalTime start = LocalTime.parse(startStr);
            LocalTime end = LocalTime.parse(endStr);
            long dailyMinutes = Math.max(60, java.time.Duration.between(start, end).toMinutes());
            // 26 working days in 30-day window
            double capacityMinutes = dailyMinutes * 26.0;
            utilization = Math.min(100.0, Math.round((booked30d / capacityMinutes) * 1000.0) / 10.0);
        } catch (Exception ignored) {
            utilization = 0.0;
        }

        BigDecimal rev = rs.getBigDecimal("roomRevenue");
        return new ClinicOperationsModels.TreatmentRoomRow(
                rs.getObject("id", UUID.class),
                rs.getObject("organizationId", UUID.class),
                rs.getObject("projectId", UUID.class),
                rs.getObject("clinicId", UUID.class),
                rs.getString("clinicName"),
                rs.getString("name"),
                rs.getString("roomNumber"),
                rs.getString("roomType"),
                rs.getObject("assignedDoctorId", UUID.class),
                rs.getString("assignedDoctorName"),
                startStr,
                endStr,
                isActive,
                status,
                occupiedBy,
                rs.getInt("totalAppointmentsCount"),
                rs.getInt("completedProceduresCount"),
                booked30d,
                utilization,
                rev != null ? rev.setScale(3, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(3));
    }

    private static String normalizeHour(String raw, String fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        return raw.trim();
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }
}
