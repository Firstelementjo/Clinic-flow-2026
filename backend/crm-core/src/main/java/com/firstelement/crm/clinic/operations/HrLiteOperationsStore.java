package com.firstelement.crm.clinic.operations;

import com.firstelement.crm.clinic.ClinicScope;
import com.firstelement.crm.error.ResourceNotFoundException;
import com.firstelement.crm.error.ValidationFailedException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Persistence operations for HR Lite Staff Scheduling & Leave Management (Req 2). */
final class HrLiteOperationsStore {

    private static final Set<String> SCHEDULE_TYPES = Set.of("WEEKLY_TEMPLATE", "DATE_ROSTER");
    private static final Set<String> LEAVE_TYPES = Set.of(
            "ANNUAL_VACATION", "SICK_LEAVE", "EMERGENCY_LEAVE", "UNPAID_LEAVE");
    private static final Set<String> LEAVE_DECISIONS = Set.of("APPROVED", "REJECTED");

    private final NamedParameterJdbcTemplate jdbc;

    HrLiteOperationsStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<ClinicOperationsModels.StaffScheduleRow> listSchedules(
            ClinicScope scope, UUID userId, UUID doctorId, boolean mineOnly, UUID callerUserId, String scheduleType) {
        return scope.read(() -> {
            StringBuilder sql = new StringBuilder("""
                    select s.id, s."organizationId", s."projectId", s."clinicId",
                           coalesce(cl."name", 'All Branches') as "clinicName",
                           s."userId", s."doctorId", s."staffName", s."staffRole",
                           s."scheduleType", s."weekday", s."shiftDate",
                           s."startTime", s."endTime", s."notes", s."isActive", s."createdAt"
                      from clinic_staff_schedules s
                      left join clinic_locations cl
                             on cl.id = s."clinicId" and cl."organizationId" = s."organizationId"
                     where %s
                    """.formatted(scope.predicate("s")));
            MapSqlParameterSource params = scope.bind();
            if (mineOnly && callerUserId != null) {
                sql.append(" and s.\"userId\" = :callerUserId");
                params.addValue("callerUserId", callerUserId);
            } else {
                if (userId != null) {
                    sql.append(" and s.\"userId\" = :userId");
                    params.addValue("userId", userId);
                }
                if (doctorId != null) {
                    sql.append(" and s.\"doctorId\" = :doctorId");
                    params.addValue("doctorId", doctorId);
                }
            }
            if (scheduleType != null && !scheduleType.isBlank()) {
                sql.append(" and s.\"scheduleType\" = :scheduleType");
                params.addValue("scheduleType", scheduleType.trim().toUpperCase());
            }
            sql.append(" order by s.\"scheduleType\" asc, coalesce(s.\"weekday\", 0) asc, s.\"shiftDate\" asc nulls last, s.\"startTime\" asc");
            return jdbc.query(sql.toString(), params, HrLiteOperationsStore::mapSchedule);
        });
    }

    ClinicOperationsModels.StaffScheduleRow createSchedule(
            ClinicScope scope, ClinicOperationsModels.CreateStaffScheduleRequest req) {
        if (!scope.isProjectScoped()) {
            throw new ValidationFailedException("A staff schedule must belong to one branch project.");
        }
        String type = normalizeScheduleType(req.scheduleType());
        validateScheduleFields(type, req.weekday(), req.shiftDate(), req.startTime(), req.endTime());
        String staffName = resolveStaffName(scope, req.userId(), req.doctorId(), req.staffName());
        String staffRole = (req.staffRole() != null && !req.staffRole().isBlank())
                ? req.staffRole().trim().toUpperCase()
                : (req.doctorId() != null ? "DOCTOR" : "CLINIC_STAFF");

        return scope.read(() -> {
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    insert into clinic_staff_schedules
                        (id, "organizationId", "projectId", "clinicId", "userId", "doctorId",
                         "staffName", "staffRole", "scheduleType", "weekday", "shiftDate",
                         "startTime", "endTime", "notes", "isActive")
                    values
                        (:id, :orgId, :projId, :clinicId, :userId, :doctorId,
                         :staffName, :staffRole, :type, :weekday, :shiftDate,
                         :startTime, :endTime, :notes, true)
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", id)
                            .addValue("orgId", scope.queryScope().organizationId())
                            .addValue("projId", scope.projectId())
                            .addValue("clinicId", req.clinicId())
                            .addValue("userId", req.userId())
                            .addValue("doctorId", req.doctorId())
                            .addValue("staffName", staffName)
                            .addValue("staffRole", staffRole)
                            .addValue("type", type)
                            .addValue("weekday", "WEEKLY_TEMPLATE".equals(type) ? req.weekday() : null)
                            .addValue("shiftDate", "DATE_ROSTER".equals(type) && req.shiftDate() != null
                                    ? java.sql.Date.valueOf(req.shiftDate()) : null)
                            .addValue("startTime", req.startTime().trim())
                            .addValue("endTime", req.endTime().trim())
                            .addValue("notes", trimToNull(req.notes())));
            return findSchedule(scope, id);
        });
    }

    ClinicOperationsModels.StaffScheduleRow updateSchedule(
            ClinicScope scope, UUID scheduleId, ClinicOperationsModels.CreateStaffScheduleRequest req) {
        return scope.read(() -> {
            ClinicOperationsModels.StaffScheduleRow existing = findSchedule(scope, scheduleId);
            String type = req.scheduleType() != null ? normalizeScheduleType(req.scheduleType()) : existing.scheduleType();
            Integer weekday = req.weekday() != null ? req.weekday() : existing.weekday();
            LocalDate shiftDate = req.shiftDate() != null ? req.shiftDate() : existing.shiftDate();
            String startTime = (req.startTime() != null && !req.startTime().isBlank()) ? req.startTime().trim() : existing.startTime();
            String endTime = (req.endTime() != null && !req.endTime().isBlank()) ? req.endTime().trim() : existing.endTime();
            validateScheduleFields(type, weekday, shiftDate, startTime, endTime);

            String staffName = (req.staffName() != null && !req.staffName().isBlank())
                    ? req.staffName().trim() : existing.staffName();
            String staffRole = (req.staffRole() != null && !req.staffRole().isBlank())
                    ? req.staffRole().trim().toUpperCase() : existing.staffRole();
            UUID clinicId = req.clinicId() != null ? req.clinicId() : existing.clinicId();
            String notes = req.notes() != null ? trimToNull(req.notes()) : existing.notes();

            jdbc.update("""
                    update clinic_staff_schedules s
                       set "clinicId" = :clinicId,
                           "staffName" = :staffName,
                           "staffRole" = :staffRole,
                           "scheduleType" = :type,
                           "weekday" = :weekday,
                           "shiftDate" = :shiftDate,
                           "startTime" = :startTime,
                           "endTime" = :endTime,
                           "notes" = :notes,
                           "updatedAt" = (now() at time zone 'utc')
                     where %s and s.id = :id
                    """.formatted(scope.predicate("s")),
                    scope.bind()
                            .addValue("id", scheduleId)
                            .addValue("clinicId", clinicId)
                            .addValue("staffName", staffName)
                            .addValue("staffRole", staffRole)
                            .addValue("type", type)
                            .addValue("weekday", "WEEKLY_TEMPLATE".equals(type) ? weekday : null)
                            .addValue("shiftDate", "DATE_ROSTER".equals(type) && shiftDate != null
                                    ? java.sql.Date.valueOf(shiftDate) : null)
                            .addValue("startTime", startTime)
                            .addValue("endTime", endTime)
                            .addValue("notes", notes));
            return findSchedule(scope, scheduleId);
        });
    }

    boolean deleteSchedule(ClinicScope scope, UUID scheduleId) {
        return scope.read(() -> jdbc.update("""
                delete from clinic_staff_schedules s
                 where %s and s.id = :id
                """.formatted(scope.predicate("s")),
                scope.bind().addValue("id", scheduleId)) > 0);
    }

    List<ClinicOperationsModels.LeaveRequestRow> listLeaveRequests(
            ClinicScope scope, String status, boolean mineOnly, UUID callerUserId) {
        return scope.read(() -> {
            StringBuilder sql = new StringBuilder("""
                    select lr.id, lr."organizationId", lr."projectId", lr."clinicId",
                           coalesce(cl."name", 'All Branches') as "clinicName",
                           lr."userId", lr."doctorId", lr."staffName", lr."staffRole",
                           lr."leaveType", lr."startDate", lr."endDate", lr."reason",
                           lr."status", lr."reviewedByUserId", lr."reviewedByName",
                           lr."reviewedAt", lr."reviewNotes", lr."createdAt"
                      from clinic_leave_requests lr
                      left join clinic_locations cl
                             on cl.id = lr."clinicId" and cl."organizationId" = lr."organizationId"
                     where %s
                    """.formatted(scope.predicate("lr")));
            MapSqlParameterSource params = scope.bind();
            if (mineOnly && callerUserId != null) {
                sql.append(" and lr.\"userId\" = :callerUserId");
                params.addValue("callerUserId", callerUserId);
            }
            if (status != null && !status.isBlank()) {
                sql.append(" and lr.\"status\" = :status");
                params.addValue("status", status.trim().toUpperCase());
            }
            sql.append(" order by case when lr.\"status\" = 'PENDING' then 0 else 1 end, lr.\"startDate\" desc, lr.\"createdAt\" desc");
            return jdbc.query(sql.toString(), params, HrLiteOperationsStore::mapLeaveRequest);
        });
    }

    ClinicOperationsModels.LeaveRequestRow createLeaveRequest(
            ClinicScope scope, ClinicOperationsModels.CreateLeaveRequest req, UUID callerUserId, String callerName) {
        if (!scope.isProjectScoped()) {
            throw new ValidationFailedException("Leave request must belong to one branch project.");
        }
        String leaveType = (req.leaveType() != null && !req.leaveType().isBlank())
                ? req.leaveType().trim().toUpperCase()
                : "ANNUAL_VACATION";
        if (!LEAVE_TYPES.contains(leaveType)) {
            throw new ValidationFailedException("Invalid leaveType. Allowed: " + LEAVE_TYPES);
        }
        if (req.startDate() == null || req.endDate() == null) {
            throw new ValidationFailedException("startDate and endDate are required.");
        }
        if (req.endDate().isBefore(req.startDate())) {
            throw new ValidationFailedException("endDate must not be before startDate.");
        }

        UUID effectiveUserId = req.userId() != null ? req.userId() : (req.doctorId() == null ? callerUserId : null);
        String staffName = resolveStaffName(scope, effectiveUserId, req.doctorId(),
                (req.staffName() != null && !req.staffName().isBlank()) ? req.staffName() : callerName);
        String staffRole = (req.staffRole() != null && !req.staffRole().isBlank())
                ? req.staffRole().trim().toUpperCase()
                : (req.doctorId() != null ? "DOCTOR" : "CLINIC_STAFF");

        return scope.read(() -> {
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    insert into clinic_leave_requests
                        (id, "organizationId", "projectId", "clinicId", "userId", "doctorId",
                         "staffName", "staffRole", "leaveType", "startDate", "endDate",
                         "reason", "status")
                    values
                        (:id, :orgId, :projId, :clinicId, :userId, :doctorId,
                         :staffName, :staffRole, :leaveType, :startDate, :endDate,
                         :reason, 'PENDING')
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", id)
                            .addValue("orgId", scope.queryScope().organizationId())
                            .addValue("projId", scope.projectId())
                            .addValue("clinicId", req.clinicId())
                            .addValue("userId", effectiveUserId)
                            .addValue("doctorId", req.doctorId())
                            .addValue("staffName", staffName)
                            .addValue("staffRole", staffRole)
                            .addValue("leaveType", leaveType)
                            .addValue("startDate", java.sql.Date.valueOf(req.startDate()))
                            .addValue("endDate", java.sql.Date.valueOf(req.endDate()))
                            .addValue("reason", trimToNull(req.reason())));
            return findLeaveRequest(scope, id);
        });
    }

    ClinicOperationsModels.LeaveConflictReport getLeaveConflicts(ClinicScope scope, UUID requestId) {
        return scope.read(() -> {
            ClinicOperationsModels.LeaveRequestRow req = findLeaveRequest(scope, requestId);
            List<String> details = new ArrayList<>();
            int apptCount = 0;

            if (req.doctorId() != null) {
                List<String> appts = jdbc.query("""
                        select a."appointmentDate", coalesce(a."patientName", 'Patient') as "patientName",
                               coalesce(a."service", 'Appointment') as "service"
                          from appointments a
                         where %s
                           and a."doctorId" = :doctorId
                           and a."status" not in ('CANCELLED', 'NO_SHOW')
                           and cast(a."appointmentDate" as date) >= :startDate
                           and cast(a."appointmentDate" as date) <= :endDate
                         order by a."appointmentDate" asc
                         limit 20
                        """.formatted(scope.predicate("a")),
                        scope.bind()
                                .addValue("doctorId", req.doctorId())
                                .addValue("startDate", java.sql.Date.valueOf(req.startDate()))
                                .addValue("endDate", java.sql.Date.valueOf(req.endDate())),
                        (rs, n) -> "Appointment: " + rs.getTimestamp("appointmentDate").toLocalDateTime().toString().replace('T', ' ')
                                + " — " + rs.getString("patientName") + " (" + rs.getString("service") + ")");
                apptCount = appts.size();
                details.addAll(appts);
            }

            Set<Integer> coveredWeekdays = new HashSet<>();
            LocalDate cursor = req.startDate();
            int safety = 0;
            while (!cursor.isAfter(req.endDate()) && safety < 14) {
                coveredWeekdays.add(cursor.getDayOfWeek().getValue());
                cursor = cursor.plusDays(1);
                safety++;
            }

            int shiftCount = 0;
            if (req.userId() != null || req.doctorId() != null) {
                List<ClinicOperationsModels.StaffScheduleRow> shifts = jdbc.query("""
                        select s.id, s."organizationId", s."projectId", s."clinicId",
                               coalesce(cl."name", 'All Branches') as "clinicName",
                               s."userId", s."doctorId", s."staffName", s."staffRole",
                               s."scheduleType", s."weekday", s."shiftDate",
                               s."startTime", s."endTime", s."notes", s."isActive", s."createdAt"
                          from clinic_staff_schedules s
                          left join clinic_locations cl on cl.id = s."clinicId" and cl."organizationId" = s."organizationId"
                         where %s
                           and s."isActive" = true
                           and (
                                (cast(:userId as uuid) is not null and s."userId" = cast(:userId as uuid))
                                or (cast(:doctorId as uuid) is not null and s."doctorId" = cast(:doctorId as uuid))
                           )
                        """.formatted(scope.predicate("s")),
                        scope.bind()
                                .addValue("userId", req.userId())
                                .addValue("doctorId", req.doctorId()),
                        HrLiteOperationsStore::mapSchedule);
                for (ClinicOperationsModels.StaffScheduleRow s : shifts) {
                    if ("DATE_ROSTER".equals(s.scheduleType()) && s.shiftDate() != null
                            && !s.shiftDate().isBefore(req.startDate()) && !s.shiftDate().isAfter(req.endDate())) {
                        shiftCount++;
                        details.add("Scheduled Shift: " + s.shiftDate() + " (" + s.startTime() + "–" + s.endTime() + ")");
                    } else if ("WEEKLY_TEMPLATE".equals(s.scheduleType()) && s.weekday() != null
                            && coveredWeekdays.contains(s.weekday())) {
                        shiftCount++;
                        details.add("Weekly Shift (Day " + s.weekday() + "): " + s.startTime() + "–" + s.endTime());
                    }
                }
            }

            String warning = (apptCount > 0 || shiftCount > 0)
                    ? "Warning: Approving this leave impacts " + apptCount + " scheduled appointment(s) and "
                            + shiftCount + " shift(s). Another employee should be assigned to cover."
                    : null;
            return new ClinicOperationsModels.LeaveConflictReport(apptCount, shiftCount, details, warning);
        });
    }

    ClinicOperationsModels.LeaveRequestRow reviewLeaveRequest(
            ClinicScope scope,
            UUID requestId,
            ClinicOperationsModels.ReviewLeaveRequest req,
            UUID reviewerUserId,
            String reviewerName) {
        if (req == null || req.status() == null) {
            throw new ValidationFailedException("status is required.");
        }
        String decision = req.status().trim().toUpperCase();
        if (!LEAVE_DECISIONS.contains(decision)) {
            throw new ValidationFailedException("Decision status must be APPROVED or REJECTED.");
        }
        return scope.read(() -> {
            ClinicOperationsModels.LeaveRequestRow existing = findLeaveRequest(scope, requestId);
            String resolvedReviewer = (reviewerName != null && !reviewerName.isBlank())
                    ? reviewerName
                    : resolveUserDisplayName(scope, reviewerUserId, "Clinic Manager");
            jdbc.update("""
                    update clinic_leave_requests lr
                       set "status" = :decision,
                           "reviewedByUserId" = :reviewerId,
                           "reviewedByName" = :reviewerName,
                           "reviewedAt" = (now() at time zone 'utc'),
                           "reviewNotes" = :reviewNotes,
                           "updatedAt" = (now() at time zone 'utc')
                     where %s and lr.id = :id
                    """.formatted(scope.predicate("lr")),
                    scope.bind()
                            .addValue("id", requestId)
                            .addValue("decision", decision)
                            .addValue("reviewerId", reviewerUserId)
                            .addValue("reviewerName", resolvedReviewer)
                            .addValue("reviewNotes", trimToNull(req.reviewNotes())));

            if ("APPROVED".equals(decision) && existing.doctorId() != null) {
                jdbc.update("""
                        insert into clinic_time_off
                            (id, "organizationId", "projectId", "doctorId", "startsOn", "endsOn", "reason")
                        values
                            (:id, :orgId, :projId, :doctorId, :startsOn, :endsOn, :reason)
                        """,
                        new MapSqlParameterSource()
                                .addValue("id", UUID.randomUUID())
                                .addValue("orgId", existing.organizationId())
                                .addValue("projId", existing.projectId())
                                .addValue("doctorId", existing.doctorId())
                                .addValue("startsOn", java.sql.Date.valueOf(existing.startDate()))
                                .addValue("endsOn", java.sql.Date.valueOf(existing.endDate()))
                                .addValue("reason", existing.leaveType() + (existing.reason() != null ? ": " + existing.reason() : "")));
            }
            return findLeaveRequest(scope, requestId);
        });
    }

    private ClinicOperationsModels.StaffScheduleRow findSchedule(ClinicScope scope, UUID id) {
        List<ClinicOperationsModels.StaffScheduleRow> rows = jdbc.query("""
                select s.id, s."organizationId", s."projectId", s."clinicId",
                       coalesce(cl."name", 'All Branches') as "clinicName",
                       s."userId", s."doctorId", s."staffName", s."staffRole",
                       s."scheduleType", s."weekday", s."shiftDate",
                       s."startTime", s."endTime", s."notes", s."isActive", s."createdAt"
                  from clinic_staff_schedules s
                  left join clinic_locations cl on cl.id = s."clinicId" and cl."organizationId" = s."organizationId"
                 where %s and s.id = :id
                 limit 1
                """.formatted(scope.predicate("s")),
                scope.bind().addValue("id", id),
                HrLiteOperationsStore::mapSchedule);
        return rows.stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Staff schedule not found"));
    }

    private ClinicOperationsModels.LeaveRequestRow findLeaveRequest(ClinicScope scope, UUID id) {
        List<ClinicOperationsModels.LeaveRequestRow> rows = jdbc.query("""
                select lr.id, lr."organizationId", lr."projectId", lr."clinicId",
                       coalesce(cl."name", 'All Branches') as "clinicName",
                       lr."userId", lr."doctorId", lr."staffName", lr."staffRole",
                       lr."leaveType", lr."startDate", lr."endDate", lr."reason",
                       lr."status", lr."reviewedByUserId", lr."reviewedByName",
                       lr."reviewedAt", lr."reviewNotes", lr."createdAt"
                  from clinic_leave_requests lr
                  left join clinic_locations cl on cl.id = lr."clinicId" and cl."organizationId" = lr."organizationId"
                 where %s and lr.id = :id
                 limit 1
                """.formatted(scope.predicate("lr")),
                scope.bind().addValue("id", id),
                HrLiteOperationsStore::mapLeaveRequest);
        return rows.stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Leave request not found"));
    }

    private String resolveStaffName(ClinicScope scope, UUID userId, UUID doctorId, String providedName) {
        if (providedName != null && !providedName.isBlank()) {
            return providedName.trim();
        }
        if (doctorId != null) {
            List<String> names = jdbc.query("""
                    select d."name" from doctors d
                     where d."organizationId" = :org and d.id = :id limit 1
                    """,
                    new MapSqlParameterSource().addValue("org", scope.queryScope().organizationId()).addValue("id", doctorId),
                    (rs, n) -> rs.getString("name"));
            if (!names.isEmpty()) return names.get(0);
        }
        if (userId != null) {
            List<String> names = jdbc.query("""
                    select u."fullName" from users u
                     where u."organizationId" = :org and u.id = :id limit 1
                    """,
                    new MapSqlParameterSource().addValue("org", scope.queryScope().organizationId()).addValue("id", userId),
                    (rs, n) -> rs.getString("fullName"));
            if (!names.isEmpty() && names.get(0) != null) return names.get(0);
        }
        return "Staff Member";
    }

    private String resolveUserDisplayName(ClinicScope scope, UUID userId, String fallback) {
        if (userId == null) return fallback;
        List<String> names = jdbc.query("""
                select u."fullName" from users u
                 where u."organizationId" = :org and u.id = :id limit 1
                """,
                new MapSqlParameterSource().addValue("org", scope.queryScope().organizationId()).addValue("id", userId),
                (rs, n) -> rs.getString("fullName"));
        return (!names.isEmpty() && names.get(0) != null && !names.get(0).isBlank()) ? names.get(0) : fallback;
    }

    private static String normalizeScheduleType(String raw) {
        String type = (raw != null && !raw.isBlank()) ? raw.trim().toUpperCase() : "WEEKLY_TEMPLATE";
        if (!SCHEDULE_TYPES.contains(type)) {
            throw new ValidationFailedException("scheduleType must be WEEKLY_TEMPLATE or DATE_ROSTER.");
        }
        return type;
    }

    private static void validateScheduleFields(
            String type, Integer weekday, LocalDate shiftDate, String startTime, String endTime) {
        if (startTime == null || startTime.isBlank() || endTime == null || endTime.isBlank()) {
            throw new ValidationFailedException("startTime and endTime are required.");
        }
        if (startTime.trim().compareTo(endTime.trim()) >= 0) {
            throw new ValidationFailedException("startTime must be before endTime.");
        }
        if ("WEEKLY_TEMPLATE".equals(type) && (weekday == null || weekday < 1 || weekday > 7)) {
            throw new ValidationFailedException("weekday (1..7) is required for WEEKLY_TEMPLATE schedules.");
        }
        if ("DATE_ROSTER".equals(type) && shiftDate == null) {
            throw new ValidationFailedException("shiftDate is required for DATE_ROSTER schedules.");
        }
    }

    private static ClinicOperationsModels.StaffScheduleRow mapSchedule(ResultSet rs, int n) throws SQLException {
        java.sql.Date sd = rs.getDate("shiftDate");
        Integer weekday = rs.getObject("weekday", Integer.class);
        return new ClinicOperationsModels.StaffScheduleRow(
                rs.getObject("id", UUID.class),
                rs.getObject("organizationId", UUID.class),
                rs.getObject("projectId", UUID.class),
                rs.getObject("clinicId", UUID.class),
                rs.getString("clinicName"),
                rs.getObject("userId", UUID.class),
                rs.getObject("doctorId", UUID.class),
                rs.getString("staffName"),
                rs.getString("staffRole"),
                rs.getString("scheduleType"),
                weekday,
                sd != null ? sd.toLocalDate() : null,
                rs.getString("startTime"),
                rs.getString("endTime"),
                rs.getString("notes"),
                rs.getBoolean("isActive"),
                toLocalDateTime(rs.getTimestamp("createdAt")));
    }

    private static ClinicOperationsModels.LeaveRequestRow mapLeaveRequest(ResultSet rs, int n) throws SQLException {
        java.sql.Date start = rs.getDate("startDate");
        java.sql.Date end = rs.getDate("endDate");
        return new ClinicOperationsModels.LeaveRequestRow(
                rs.getObject("id", UUID.class),
                rs.getObject("organizationId", UUID.class),
                rs.getObject("projectId", UUID.class),
                rs.getObject("clinicId", UUID.class),
                rs.getString("clinicName"),
                rs.getObject("userId", UUID.class),
                rs.getObject("doctorId", UUID.class),
                rs.getString("staffName"),
                rs.getString("staffRole"),
                rs.getString("leaveType"),
                start != null ? start.toLocalDate() : null,
                end != null ? end.toLocalDate() : null,
                rs.getString("reason"),
                rs.getString("status"),
                rs.getObject("reviewedByUserId", UUID.class),
                rs.getString("reviewedByName"),
                toLocalDateTime(rs.getTimestamp("reviewedAt")),
                rs.getString("reviewNotes"),
                toLocalDateTime(rs.getTimestamp("createdAt")));
    }

    private static LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts != null ? ts.toLocalDateTime() : null;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }
}
