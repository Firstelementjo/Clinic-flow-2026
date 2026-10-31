package com.firstelement.crm.clinic.schedule;

import com.firstelement.crm.clinic.ClinicScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Every schedule and availability statement, all scoped through {@link ClinicScope}. */
@Repository
public class ScheduleRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ScheduleRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Active windows for one dentist on one weekday that are in force on a date.
     *
     * <p>The effective range is inclusive at both ends, so a window ending the day the next one
     * begins does not leave a day with no schedule by accident.
     */
    public List<ScheduleModels.Window> windows(
            ClinicScope scope, UUID doctorId, int weekday, LocalDate on) {
        return scope.read(() -> jdbc.query("""
                select s."id", s."weekday", s."startMinute", s."endMinute",
                       s."durationOverrideMinutes", s."timezone",
                       s."effectiveFrom", s."effectiveTo"
                  from "clinic_schedules" s
                 where %s
                   and s."doctorId" = :doctorId
                   and s."isActive"
                   and s."weekday" = :weekday
                   and s."effectiveFrom" <= :on
                   and (s."effectiveTo" is null or s."effectiveTo" >= :on)
                 order by s."startMinute", s."id"
                """.formatted(scope.predicate("s")),
                scope.bind(Map.of("doctorId", doctorId, "weekday", weekday, "on", on)),
                ScheduleRepository::window));
    }

    /**
     * True when any leave, holiday or training day covers this date.
     *
     * <p>Both ends inclusive, so a single day is startsOn = endsOn. Overlapping ranges need no
     * merging: existence is the question, not the union.
     */
    public boolean isAway(ClinicScope scope, UUID doctorId, LocalDate on) {
        Integer count = scope.read(() -> jdbc.queryForObject("""
                select count(*)
                  from "clinic_time_off" t
                 where %s
                   and t."doctorId" = :doctorId
                   and t."startsOn" <= :on
                   and t."endsOn" >= :on
                """.formatted(scope.predicate("t")),
                scope.bind(Map.of("doctorId", doctorId, "on", on)),
                Integer.class));
        return count != null && count > 0;
    }

    /**
     * Bookings that still occupy the dentist, each ALREADY expanded by its own padding.
     *
     * <p>The status filter is {@code not in ('CANCELLED','NO_SHOW')} rather than
     * {@code in (five values)}. DEC-017 is open: the only deployed booking path writes an eighth
     * status, APPROVED, so the table has no CHECK and a positive list would have to be
     * maintained against a set that is still being decided. These two statuses are exactly the
     * ones DEC-003 says free the slot, so the filter is correct whether the set has seven values
     * or eight.
     *
     * <p>The LEFT JOIN to procedures is the load-bearing part. Applying the buffer to CANDIDATES
     * but not to EXISTING BOOKINGS is how a chair ends up double-booked across a procedure that
     * carries a buffer, and it is exactly where the legacy AI path and the legacy availability
     * path disagreed.
     */
    public List<ScheduleModels.Busy> busyOn(
            ClinicScope scope, UUID doctorId, LocalDate day) {
        LocalDateTime start = day.atStartOfDay();
        LocalDateTime end = day.plusDays(1).atStartOfDay();

        return scope.read(() -> jdbc.query("""
                select a."id", a."appointmentDate",
                       a."durationMinutes" + coalesce(p."bufferMinutes", 0) as "blockedMinutes"
                  from "appointments" a
                  left join "public"."procedures" p
                         on p."id" = a."procedureId"
                        and p."organizationId" = a."organizationId"
                 where %s
                   and a."doctorId" = :doctorId
                   and a."appointmentDate" >= :dayStart
                   and a."appointmentDate" < :dayEnd
                   and a."status" not in ('CANCELLED', 'NO_SHOW')
                 order by a."appointmentDate", a."id"
                """.formatted(scope.predicate("a")),
                scope.bind(Map.of("doctorId", doctorId, "dayStart", start, "dayEnd", end)),
                ScheduleRepository::busy));
    }

    /** Writes a working window. Requires a project-scoped scope, like every clinic write. */
    public UUID insertWindow(ClinicScope scope, Map<String, Object> parameters) {
        if (!scope.isProjectScoped()) {
            throw new IllegalStateException(
                    "A schedule must belong to one clinic; name the project explicitly.");
        }
        return scope.read(() -> {
            UUID id = parameters.get("id") == null ? UUID.randomUUID() : (UUID) parameters.get("id");
            MapSqlParameterSource bound = scope.bind(parameters)
                    .addValue("id", id)
                    .addValue("org", scope.queryScope().organizationId())
                    .addValue("proj", scope.projectId());
            jdbc.update("""
                    insert into "clinic_schedules"
                        ("id", "organizationId", "projectId", "doctorId", "weekday",
                         "startMinute", "endMinute", "durationOverrideMinutes", "timezone",
                         "effectiveFrom", "effectiveTo", "isActive", "createdAt")
                    values
                        (:id, :org, :proj, :doctorId, :weekday, :startMinute, :endMinute,
                         :durationOverrideMinutes, :timezone, :effectiveFrom, :effectiveTo,
                         :isActive, (current_timestamp at time zone 'utc'))
                    """, bound);
            return id;
        });
    }

    /** Records leave, a holiday or a training day. */
    public UUID insertTimeOff(ClinicScope scope, Map<String, Object> parameters) {
        if (!scope.isProjectScoped()) {
            throw new IllegalStateException(
                    "Leave must belong to one clinic; name the project explicitly.");
        }
        return scope.read(() -> {
            UUID id = parameters.get("id") == null ? UUID.randomUUID() : (UUID) parameters.get("id");
            MapSqlParameterSource bound = scope.bind(parameters)
                    .addValue("id", id)
                    .addValue("org", scope.queryScope().organizationId())
                    .addValue("proj", scope.projectId());
            jdbc.update("""
                    insert into "clinic_time_off"
                        ("id", "organizationId", "projectId", "doctorId",
                         "startsOn", "endsOn", "reason", "createdAt")
                    values
                        (:id, :org, :proj, :doctorId, :startsOn, :endsOn, :reason,
                         (current_timestamp at time zone 'utc'))
                    """, bound);
            return id;
        });
    }

    /** Shared resources for one clinic. Staff-only by DEC-002; the caller must hold a staff grant. */
    public List<ScheduleModels.Resource> resources(ClinicScope scope, int limit) {
        return scope.read(() -> jdbc.query("""
                select r."id", r."name", r."isActive"
                  from "clinic_resources" r
                 where %s
                 order by r."name", r."id"
                 limit :limit
                """.formatted(scope.predicate("r")),
                scope.bind(Map.of("limit", Math.min(Math.max(limit, 1), 200))),
                (rs, n) -> new ScheduleModels.Resource(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getBoolean("isActive"))));
    }

    public UUID insertResource(ClinicScope scope, Map<String, Object> parameters) {
        if (!scope.isProjectScoped()) {
            throw new IllegalStateException(
                    "A resource must belong to one clinic; name the project explicitly.");
        }
        return scope.read(() -> {
            UUID id = parameters.get("id") == null ? UUID.randomUUID() : (UUID) parameters.get("id");
            MapSqlParameterSource bound = scope.bind(parameters)
                    .addValue("id", id)
                    .addValue("org", scope.queryScope().organizationId())
                    .addValue("proj", scope.projectId());
            jdbc.update("""
                    insert into "clinic_resources"
                        ("id", "organizationId", "projectId", "name", "isActive", "createdAt")
                    values
                        (:id, :org, :proj, :name, :isActive,
                         (current_timestamp at time zone 'utc'))
                    """, bound);
            return id;
        });
    }

    public UUID insertCapability(ClinicScope scope, Map<String, Object> parameters) {
        if (!scope.isProjectScoped()) {
            throw new IllegalStateException(
                    "A capability must belong to one clinic; name the project explicitly.");
        }
        return scope.read(() -> {
            UUID id = parameters.get("id") == null ? UUID.randomUUID() : (UUID) parameters.get("id");
            MapSqlParameterSource bound = scope.bind(parameters)
                    .addValue("id", id)
                    .addValue("org", scope.queryScope().organizationId());
            jdbc.update("""
                    insert into "clinic_resource_capabilities"
                        ("id", "organizationId", "resourceId", "capability", "createdAt")
                    values
                        (:id, :org, :resourceId, :capability,
                         (current_timestamp at time zone 'utc'))
                    """, bound);
            return id;
        });
    }

    public List<ScheduleModels.ResourceDetail> listResourceDetails(ClinicScope scope, int limit) {
        return scope.read(() -> {
            String sql = """
                    select r."id", r."organizationId", r."projectId", r."name", r."isActive", r."createdAt",
                           coalesce(string_agg(c."capability", ','), '') as "caps"
                      from "clinic_resources" r
                      left join "clinic_resource_capabilities" c
                             on c."resourceId" = r."id"
                            and c."organizationId" = r."organizationId"
                     where %s
                     group by r."id", r."organizationId", r."projectId", r."name", r."isActive", r."createdAt"
                     order by r."name", r."id"
                     limit :limit
                    """.formatted(scope.predicate("r"));
            return jdbc.query(sql,
                    scope.bind(Map.of("limit", Math.min(Math.max(limit, 1), 200))),
                    (rs, n) -> {
                        String capsStr = rs.getString("caps");
                        List<String> caps = (capsStr == null || capsStr.isBlank())
                                ? List.of()
                                : List.of(capsStr.split(","));
                        return new ScheduleModels.ResourceDetail(
                                rs.getObject("id", UUID.class),
                                rs.getObject("organizationId", UUID.class),
                                rs.getObject("projectId", UUID.class),
                                rs.getString("name"),
                                rs.getBoolean("isActive"),
                                caps,
                                rs.getTimestamp("createdAt").toInstant());
                    });
        });
    }

    public int assignResource(ClinicScope scope, UUID appointmentId, UUID resourceId, int bufferMinutes) {
        return scope.read(() -> {
            MapSqlParameterSource bound = scope.bind(Map.of(
                    "appointmentId", appointmentId,
                    "bufferMinutes", Math.max(0, bufferMinutes)))
                    .addValue("resourceId", resourceId);
            return jdbc.update("""
                    update "appointments" a
                       set "resourceId" = :resourceId,
                           "bufferMinutes" = :bufferMinutes
                     where %s
                       and a."id" = :appointmentId
                    """.formatted(scope.predicate("a")), bound);
        });
    }

    public List<ScheduleModels.Busy> activeHoldsAsBusyOn(
            ClinicScope scope, UUID doctorId, LocalDate day) {
        LocalDateTime start = day.atStartOfDay();
        LocalDateTime end = day.plusDays(1).atStartOfDay();

        return scope.read(() -> jdbc.query("""
                select h."id", h."slotStartsAt",
                       (h."durationMinutes" + h."bufferMinutes") as "blockedMinutes"
                  from "appointment_holds" h
                 where %s
                   and h."doctorId" = :doctorId
                   and h."slotStartsAt" >= :dayStart
                   and h."slotStartsAt" < :dayEnd
                   and h."status" = 'ACTIVE'
                   and h."holdExpiresAt" > (current_timestamp at time zone 'utc')
                 order by h."slotStartsAt", h."id"
                """.formatted(scope.predicate("h")),
                scope.bind(Map.of("doctorId", doctorId, "dayStart", start, "dayEnd", end)),
                (rs, n) -> new ScheduleModels.Busy(
                        rs.getObject("id", UUID.class),
                        rs.getObject("slotStartsAt", LocalDateTime.class),
                        rs.getInt("blockedMinutes"))));
    }

    public List<ScheduleModels.HoldRow> listActiveHolds(
            ClinicScope scope, UUID doctorId, LocalDate day) {
        LocalDateTime start = day.atStartOfDay();
        LocalDateTime end = day.plusDays(1).atStartOfDay();

        return scope.read(() -> {
            Map<String, Object> params = new java.util.HashMap<>();
            params.put("dayStart", start);
            params.put("dayEnd", end);
            params.put("doctorId", doctorId);

            return jdbc.query("""
                    select h."id", h."organizationId", h."projectId", h."doctorId", h."resourceId",
                           h."slotStartsAt", h."durationMinutes", h."bufferMinutes", h."holdExpiresAt",
                           h."heldByUserId", h."patientIdentifier", h."status", h."createdAt"
                      from "appointment_holds" h
                     where %s
                       and (:doctorId::uuid is null or h."doctorId" = :doctorId::uuid)
                       and h."slotStartsAt" >= :dayStart
                       and h."slotStartsAt" < :dayEnd
                       and h."status" = 'ACTIVE'
                       and h."holdExpiresAt" > (current_timestamp at time zone 'utc')
                     order by h."slotStartsAt", h."id"
                    """.formatted(scope.predicate("h")),
                    scope.bind(params),
                    (rs, n) -> new ScheduleModels.HoldRow(
                            rs.getObject("id", UUID.class),
                            rs.getObject("organizationId", UUID.class),
                            rs.getObject("projectId", UUID.class),
                            rs.getObject("doctorId", UUID.class),
                            rs.getObject("resourceId", UUID.class),
                            rs.getObject("slotStartsAt", LocalDateTime.class),
                            rs.getInt("durationMinutes"),
                            rs.getInt("bufferMinutes"),
                            rs.getObject("holdExpiresAt", LocalDateTime.class),
                            rs.getObject("heldByUserId", UUID.class),
                            rs.getString("patientIdentifier"),
                            rs.getString("status"),
                            rs.getTimestamp("createdAt").toInstant()));
        });
    }

    public UUID insertHold(ClinicScope scope, ScheduleModels.CreateHoldRequest req, UUID userId) {
        if (!scope.isProjectScoped()) {
            throw new IllegalStateException("Hold must belong to one clinic; name project explicitly.");
        }
        return scope.read(() -> {
            UUID id = UUID.randomUUID();
            int duration = req.durationMinutes() != null ? req.durationMinutes() : 30;
            int buffer = req.bufferMinutes() != null ? req.bufferMinutes() : 0;
            int holdMins = req.holdMinutes() != null ? req.holdMinutes() : 15;
            LocalDateTime expires = LocalDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(holdMins);

            MapSqlParameterSource bound = scope.bind(Map.of(
                    "id", id,
                    "org", scope.queryScope().organizationId(),
                    "proj", scope.projectId(),
                    "doctorId", req.doctorId(),
                    "slotStartsAt", req.slotStartsAt(),
                    "durationMinutes", duration,
                    "bufferMinutes", buffer,
                    "holdExpiresAt", expires,
                    "status", "ACTIVE"))
                    .addValue("resourceId", req.resourceId())
                    .addValue("heldByUserId", userId)
                    .addValue("patientIdentifier", req.patientIdentifier());

            jdbc.update("""
                    insert into "appointment_holds"
                        ("id", "organizationId", "projectId", "doctorId", "resourceId",
                         "slotStartsAt", "durationMinutes", "bufferMinutes", "holdExpiresAt",
                         "heldByUserId", "patientIdentifier", "status", "createdAt", "updatedAt")
                    values
                        (:id, :org, :proj, :doctorId, :resourceId,
                         :slotStartsAt, :durationMinutes, :bufferMinutes, :holdExpiresAt,
                         :heldByUserId, :patientIdentifier, :status,
                         (current_timestamp at time zone 'utc'),
                         (current_timestamp at time zone 'utc'))
                    """, bound);
            return id;
        });
    }

    public int releaseHold(ClinicScope scope, UUID holdId) {
        return scope.read(() -> {
            MapSqlParameterSource bound = scope.bind(Map.of("id", holdId));
            return jdbc.update("""
                    update "appointment_holds" h
                       set "status" = 'RELEASED',
                           "updatedAt" = (current_timestamp at time zone 'utc')
                     where %s
                       and h."id" = :id
                       and h."status" = 'ACTIVE'
                    """.formatted(scope.predicate("h")), bound);
        });
    }

    public List<ScheduleModels.WaitlistRow> listWaitlist(ClinicScope scope, String status, int limit) {
        return scope.read(() -> {
            Map<String, Object> params = new java.util.HashMap<>();
            params.put("status", status);
            params.put("limit", Math.min(Math.max(limit, 1), 200));

            String sql = """
                    select w."id", w."organizationId", w."projectId", w."patientId", w."patientName",
                           w."patientPhone", w."preferredDoctorId", w."procedureId", w."preferredDays",
                           w."preferredTimeOfDay", w."priority", w."status", w."offeredSlotStartsAt",
                           w."notes", w."createdAt", w."updatedAt"
                      from "appointment_waitlist" w
                     where %s
                       and (:status::text is null or w."status" = :status::text)
                     order by case when w."priority" = 'URGENT' then 0 else 1 end,
                              w."createdAt" asc
                     limit :limit
                    """.formatted(scope.predicate("w"));
            return jdbc.query(sql,
                    scope.bind(params),
                    (rs, n) -> new ScheduleModels.WaitlistRow(
                            rs.getObject("id", UUID.class),
                            rs.getObject("organizationId", UUID.class),
                            rs.getObject("projectId", UUID.class),
                            rs.getObject("patientId", UUID.class),
                            rs.getString("patientName"),
                            rs.getString("patientPhone"),
                            rs.getObject("preferredDoctorId", UUID.class),
                            rs.getObject("procedureId", UUID.class),
                            rs.getString("preferredDays"),
                            rs.getString("preferredTimeOfDay"),
                            rs.getString("priority"),
                            rs.getString("status"),
                            rs.getObject("offeredSlotStartsAt", LocalDateTime.class),
                            rs.getString("notes"),
                            rs.getTimestamp("createdAt").toInstant(),
                            rs.getTimestamp("updatedAt").toInstant()));
        });
    }

    public UUID insertWaitlist(ClinicScope scope, ScheduleModels.CreateWaitlistRequest req) {
        if (!scope.isProjectScoped()) {
            throw new IllegalStateException("Waitlist must belong to one clinic; name project explicitly.");
        }
        return scope.read(() -> {
            UUID id = UUID.randomUUID();
            String priority = req.priority() != null ? req.priority().toUpperCase() : "NORMAL";
            MapSqlParameterSource bound = scope.bind(Map.of(
                    "id", id,
                    "org", scope.queryScope().organizationId(),
                    "proj", scope.projectId(),
                    "patientName", req.patientName(),
                    "patientPhone", req.patientPhone(),
                    "priority", priority,
                    "status", "WAITING"))
                    .addValue("patientId", req.patientId())
                    .addValue("preferredDoctorId", req.preferredDoctorId())
                    .addValue("procedureId", req.procedureId())
                    .addValue("preferredDays", req.preferredDays())
                    .addValue("preferredTimeOfDay", req.preferredTimeOfDay())
                    .addValue("notes", req.notes());

            jdbc.update("""
                    insert into "appointment_waitlist"
                        ("id", "organizationId", "projectId", "patientId", "patientName", "patientPhone",
                         "preferredDoctorId", "procedureId", "preferredDays", "preferredTimeOfDay",
                         "priority", "status", "notes", "createdAt", "updatedAt")
                    values
                        (:id, :org, :proj, :patientId, :patientName, :patientPhone,
                         :preferredDoctorId, :procedureId, :preferredDays, :preferredTimeOfDay,
                         :priority, :status, :notes,
                         (current_timestamp at time zone 'utc'),
                         (current_timestamp at time zone 'utc'))
                    """, bound);
            return id;
        });
    }

    public int updateWaitlistStatus(
            ClinicScope scope, UUID waitlistId, ScheduleModels.UpdateWaitlistStatusRequest req) {
        return scope.read(() -> {
            MapSqlParameterSource bound = scope.bind(Map.of(
                    "id", waitlistId,
                    "status", req.status().toUpperCase()))
                    .addValue("offeredSlotStartsAt", req.offeredSlotStartsAt());

            return jdbc.update("""
                    update "appointment_waitlist" w
                       set "status" = :status,
                           "offeredSlotStartsAt" = :offeredSlotStartsAt,
                           "updatedAt" = (current_timestamp at time zone 'utc')
                     where %s
                       and w."id" = :id
                    """.formatted(scope.predicate("w")), bound);
        });
    }

    public List<ScheduleModels.ReminderRow> listReminders(ClinicScope scope, String status, int limit) {
        return scope.read(() -> {
            Map<String, Object> params = new java.util.HashMap<>();
            params.put("status", status);
            params.put("limit", Math.min(Math.max(limit, 1), 200));

            String sql = """
                    select r."id", r."organizationId", r."projectId", r."appointmentId", r."patientId",
                           r."channel", r."recipientPhone", r."scheduledFor", r."sentAt", r."status",
                           r."templateCode", r."failureReason", r."createdAt"
                      from "appointment_reminders" r
                     where %s
                       and (:status::text is null or r."status" = :status::text)
                     order by r."scheduledFor" asc
                     limit :limit
                    """.formatted(scope.predicate("r"));
            return jdbc.query(sql,
                    scope.bind(params),
                    (rs, n) -> new ScheduleModels.ReminderRow(
                            rs.getObject("id", UUID.class),
                            rs.getObject("organizationId", UUID.class),
                            rs.getObject("projectId", UUID.class),
                            rs.getObject("appointmentId", UUID.class),
                            rs.getObject("patientId", UUID.class),
                            rs.getString("channel"),
                            rs.getString("recipientPhone"),
                            rs.getObject("scheduledFor", LocalDateTime.class),
                            rs.getObject("sentAt", LocalDateTime.class),
                            rs.getString("status"),
                            rs.getString("templateCode"),
                            rs.getString("failureReason"),
                            rs.getTimestamp("createdAt").toInstant()));
        });
    }

    public UUID insertReminder(ClinicScope scope, ScheduleModels.CreateReminderRequest req) {
        if (!scope.isProjectScoped()) {
            throw new IllegalStateException("Reminder must belong to one clinic; name project explicitly.");
        }
        return scope.read(() -> {
            UUID id = UUID.randomUUID();
            String channel = req.channel() != null ? req.channel().toUpperCase() : "WHATSAPP";
            MapSqlParameterSource bound = scope.bind(Map.of(
                    "id", id,
                    "org", scope.queryScope().organizationId(),
                    "proj", scope.projectId(),
                    "appointmentId", req.appointmentId(),
                    "channel", channel,
                    "recipientPhone", req.recipientPhone(),
                    "scheduledFor", req.scheduledFor(),
                    "templateCode", req.templateCode(),
                    "status", "PENDING"))
                    .addValue("patientId", req.patientId());

            jdbc.update("""
                    insert into "appointment_reminders"
                        ("id", "organizationId", "projectId", "appointmentId", "patientId",
                         "channel", "recipientPhone", "scheduledFor", "templateCode", "status", "createdAt")
                    values
                        (:id, :org, :proj, :appointmentId, :patientId,
                         :channel, :recipientPhone, :scheduledFor, :templateCode, :status,
                         (current_timestamp at time zone 'utc'))
                    """, bound);
            return id;
        });
    }

    public int updateReminderStatus(ClinicScope scope, UUID reminderId, String status, String failureReason) {
        return scope.read(() -> {
            MapSqlParameterSource bound = scope.bind(Map.of(
                    "id", reminderId,
                    "status", status.toUpperCase()))
                    .addValue("failureReason", failureReason);

            return jdbc.update("""
                    update "appointment_reminders" r
                       set "status" = :status,
                           "failureReason" = :failureReason,
                           "sentAt" = case when :status = 'SENT' then (current_timestamp at time zone 'utc') else r."sentAt" end
                     where %s
                       and r."id" = :id
                    """.formatted(scope.predicate("r")), bound);
        });
    }

    public ScheduleModels.AppointmentCreatedResponse insertAppointment(
            ClinicScope scope, ScheduleModels.CreateAppointmentRequest req, UUID callerUserId) {
        if (!scope.isProjectScoped()) {
            throw new IllegalStateException("Appointment must belong to one clinic; name project explicitly.");
        }
        if (req.appointmentDate() == null) {
            throw new com.firstelement.crm.error.ValidationFailedException(
                    "appointmentDate is required",
                    java.util.Map.of("field", "appointmentDate"));
        }

        int quantity = req.quantity() == null ? 1 : req.quantity();
        if (quantity < 1 || quantity > 999) {
            throw new com.firstelement.crm.error.ValidationFailedException(
                    "quantity must be between 1 and 999",
                    java.util.Map.of("field", "quantity"));
        }
        Double requestedPrice = req.price();
        if (requestedPrice != null && requestedPrice < 0) {
            throw new com.firstelement.crm.error.ValidationFailedException(
                    "price cannot be negative",
                    java.util.Map.of("field", "price"));
        }
        if (req.medicalReportUrl() != null && req.medicalReportUrl().length() > 5_000_000) {
            throw new com.firstelement.crm.error.ValidationFailedException(
                    "medicalReportUrl is too large",
                    java.util.Map.of("field", "medicalReportUrl"));
        }

        return scope.read(() -> {
            UUID id = UUID.randomUUID();
            int duration = req.durationMinutes() != null ? req.durationMinutes() : 30;
            int buffer = req.bufferMinutes() != null ? req.bufferMinutes() : 0;

            // A patient id is the identity. When it is present the server reads the
            // name, phone and email from the patients row instead of accepting the
            // browser's copy of them, so an edited form cannot rewrite who the
            // appointment belongs to.
            UUID patientId = req.patientId();
            String patientName = trimToNull(req.patientName());
            String patientPhone = trimToNull(req.patientPhone());
            String patientEmail = trimToNull(req.patientEmail());
            if (patientId != null) {
                var snapshots = jdbc.query("""
                        select p."displayName", p."primaryPhone", p."primaryEmail"
                          from "patients" p
                         where %s
                           and p."id" = :patientId
                           and p."deletedAt" is null
                         limit 1
                        """.formatted(scope.predicate("p")),
                        scope.bind().addValue("patientId", patientId),
                        (rs, n) -> new Object[] {
                            rs.getString("displayName"),
                            rs.getString("primaryPhone"),
                            rs.getString("primaryEmail")
                        });
                if (snapshots.isEmpty()) {
                    throw new com.firstelement.crm.error.ValidationFailedException(
                            "patientId does not name a patient in this clinic",
                            java.util.Map.of("field", "patientId"));
                }
                Object[] snapshot = snapshots.get(0);
                patientName = (String) snapshot[0];
                patientPhone = (String) snapshot[1];
                patientEmail = (String) snapshot[2];
            }
            if (patientName == null) {
                throw new com.firstelement.crm.error.ValidationFailedException(
                        "A registered patient or a walk-in patient name is required",
                        java.util.Map.of("field", "patientName"));
            }

            // The catalogue owns duration, buffer and the list price. A caller may
            // override the price (a discount agreed at the desk), but never the
            // service name shown on the row.
            UUID procedureId = req.procedureId();
            String service = trimToNull(req.service());
            UUID clinicId = req.clinicId();
            Double price = requestedPrice;
            if (procedureId != null) {
                var procedures = jdbc.query("""
                        select p."name", p."durationMinutes", p."bufferMinutes",
                               p."price", p."clinicId"
                          from "procedures" p
                         where %s
                           and p."id" = :procedureId
                         limit 1
                        """.formatted(scope.predicate("p")),
                        scope.bind().addValue("procedureId", procedureId),
                        (rs, n) -> new ProcedureSnapshot(
                            rs.getString("name"),
                            rs.getInt("durationMinutes"),
                            rs.getInt("bufferMinutes"),
                            rs.getBigDecimal("price") == null
                                ? null : rs.getBigDecimal("price").doubleValue(),
                            rs.getObject("clinicId", UUID.class)));
                if (procedures.isEmpty()) {
                    throw new com.firstelement.crm.error.ValidationFailedException(
                            "procedureId does not name a service in this clinic",
                            java.util.Map.of("field", "procedureId"));
                }
                ProcedureSnapshot procedure = procedures.get(0);
                service = procedure.name();
                if (req.durationMinutes() == null) {
                    duration = procedure.durationMinutes();
                }
                if (req.bufferMinutes() == null) {
                    buffer = procedure.bufferMinutes();
                }
                if (price == null) {
                    price = procedure.price();
                }
                if (clinicId == null) {
                    clinicId = procedure.clinicId();
                }
            }
            if (service == null) {
                service = "Consultation";
            }

            // A doctor name is read from the roster, not from the request, for the
            // same reason as the patient snapshot: the row must agree with the id.
            String doctorName = trimToNull(req.doctorName());
            if (req.doctorId() != null) {
                var names = jdbc.query("""
                        select d."name"
                          from "doctors" d
                         where %s
                           and d."id" = :doctorId
                         limit 1
                        """.formatted(scope.predicate("d")),
                        scope.bind().addValue("doctorId", req.doctorId()),
                        (rs, n) -> rs.getString("name"));
                if (names.isEmpty()) {
                    throw new com.firstelement.crm.error.ValidationFailedException(
                            "doctorId does not name a doctor in this clinic",
                            java.util.Map.of("field", "doctorId"));
                }
                doctorName = names.get(0);
            }

            if (clinicId != null) {
                Integer clinics = jdbc.queryForObject("""
                        select count(*)
                          from "clinic_locations" l
                         where %s
                           and l."id" = :clinicId
                        """.formatted(scope.predicate("l")),
                        scope.bind().addValue("clinicId", clinicId),
                        Integer.class);
                if (clinics == null || clinics == 0) {
                    throw new com.firstelement.crm.error.ValidationFailedException(
                            "clinicId does not name a clinic in this scope",
                            java.util.Map.of("field", "clinicId"));
                }
            }

            String paymentStatus = java.util.Optional.ofNullable(
                            com.firstelement.crm.clinic.ClinicModels.PaymentStatus.parse(
                                req.paymentStatus()))
                    .map(com.firstelement.crm.clinic.ClinicModels.PaymentStatus::wireValue)
                    .orElse("UNPAID");

            String ticket = "APT-" + (System.currentTimeMillis() % 1000000);
            String title = service + " - " + patientName;

            MapSqlParameterSource bound = scope.bind()
                    .addValue("id", id)
                    .addValue("org", scope.queryScope().organizationId())
                    .addValue("proj", scope.projectId())
                    .addValue("patientId", patientId)
                    .addValue("clinicId", clinicId)
                    .addValue("doctorId", req.doctorId())
                    .addValue("procedureId", procedureId)
                    .addValue("resourceId", req.resourceId())
                    .addValue("title", title)
                    .addValue("patientName", patientName)
                    .addValue("patientPhone", patientPhone)
                    .addValue("patientEmail", patientEmail)
                    .addValue("appointmentDate", req.appointmentDate())
                    .addValue("durationMinutes", duration)
                    .addValue("bufferMinutes", buffer)
                    .addValue("status", "CONFIRMED")
                    .addValue("service", service)
                    .addValue("doctorName", doctorName)
                    .addValue("price", price == null
                            ? null : java.math.BigDecimal.valueOf(price))
                    .addValue("quantity", quantity)
                    .addValue("paymentStatus", paymentStatus)
                    .addValue("medicalReportName", trimToNull(req.medicalReportName()))
                    .addValue("medicalReportContentType",
                            trimToNull(req.medicalReportContentType()))
                    .addValue("medicalReportUrl", trimToNull(req.medicalReportUrl()))
                    .addValue("notes", trimToNull(req.notes()))
                    .addValue("ticketNumber", ticket)
                    .addValue("createdById", callerUserId);

            jdbc.update("""
                    insert into "appointments"
                        ("id", "organizationId", "projectId", "patientId", "clinicId",
                         "doctorId", "procedureId", "resourceId", "title", "patientName",
                         "patientPhone", "patientEmail", "appointmentDate", "durationMinutes",
                         "bufferMinutes", "status", "service", "doctorName", "price",
                         "quantity", "paymentStatus", "medicalReportName",
                         "medicalReportContentType", "medicalReportUrl", "isAiProposed",
                         "notes", "ticketNumber", "createdById", "createdAt", "updatedAt")
                    values
                        (:id, :org, :proj, :patientId, :clinicId,
                         :doctorId, :procedureId, :resourceId, :title, :patientName,
                         :patientPhone, :patientEmail, :appointmentDate, :durationMinutes,
                         :bufferMinutes, :status, :service, :doctorName, :price,
                         :quantity, :paymentStatus, :medicalReportName,
                         :medicalReportContentType, :medicalReportUrl, false,
                         :notes, :ticketNumber, :createdById,
                         (current_timestamp at time zone 'utc'),
                         (current_timestamp at time zone 'utc'))
                    """, bound);

            return new ScheduleModels.AppointmentCreatedResponse(
                    id, ticket, "CONFIRMED", req.appointmentDate());
        });
    }

    private record ProcedureSnapshot(
            String name, int durationMinutes, int bufferMinutes, Double price, UUID clinicId) {
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Change the payment state. The database CHECK is the final word on the value. */
    public int updatePaymentStatus(
            ClinicScope scope, UUID appointmentId, String paymentStatus) {
        com.firstelement.crm.clinic.ClinicModels.PaymentStatus parsed =
                com.firstelement.crm.clinic.ClinicModels.PaymentStatus.parse(paymentStatus);
        if (parsed == null) {
            throw new com.firstelement.crm.error.ValidationFailedException(
                    "paymentStatus is required",
                    java.util.Map.of("field", "paymentStatus"));
        }
        return scope.read(() -> jdbc.update("""
                update "appointments" a
                   set "paymentStatus" = :paymentStatus,
                       "updatedAt" = (current_timestamp at time zone 'utc')
                 where %s
                   and a."id" = :id
                """.formatted(scope.predicate("a")),
                scope.bind()
                        .addValue("id", appointmentId)
                        .addValue("paymentStatus", parsed.wireValue())));
    }

    public int updateAppointmentStatus(
            ClinicScope scope, UUID appointmentId, String status, String reason, UUID callerUserId) {
        return scope.read(() -> {
            String normStatus = status.toUpperCase();
            boolean isCancel = "CANCELLED".equals(normStatus) || "NO_SHOW".equals(normStatus);

            MapSqlParameterSource bound = scope.bind(Map.of(
                    "id", appointmentId,
                    "status", normStatus))
                    .addValue("cancelledReason", isCancel ? (reason != null && !reason.isBlank() ? reason : "Cancelled by clinic staff") : null)
                    .addValue("cancelledBy", isCancel ? callerUserId : null);

            return jdbc.update("""
                    update "appointments" a
                       set "status" = :status,
                           "cancelledReason" = case when :status in ('CANCELLED', 'NO_SHOW') then coalesce(:cancelledReason, a."cancelledReason", 'Cancelled') else null end,
                           "cancelledBy" = case when :status in ('CANCELLED', 'NO_SHOW') then coalesce(:cancelledBy, a."cancelledBy") else null end,
                           "cancelledAt" = case when :status in ('CANCELLED', 'NO_SHOW') then coalesce(a."cancelledAt", (current_timestamp at time zone 'utc')) else null end,
                           "updatedAt" = (current_timestamp at time zone 'utc')
                     where %s
                       and a."id" = :id
                    """.formatted(scope.predicate("a")), bound);
        });
    }

    private static ScheduleModels.Window window(ResultSet rs, int n) throws SQLException {
        java.sql.Date from = rs.getDate("effectiveFrom");
        java.sql.Date to = rs.getDate("effectiveTo");
        int override = rs.getInt("durationOverrideMinutes");
        return new ScheduleModels.Window(
                rs.getObject("id", UUID.class),
                rs.getInt("weekday"),
                rs.getInt("startMinute"),
                rs.getInt("endMinute"),
                rs.wasNull() ? null : override,
                rs.getString("timezone"),
                from == null ? null : from.toLocalDate(),
                to == null ? null : to.toLocalDate());
    }

    private static ScheduleModels.Busy busy(ResultSet rs, int n) throws SQLException {
        return new ScheduleModels.Busy(
                rs.getObject("id", UUID.class),
                rs.getObject("appointmentDate", LocalDateTime.class),
                rs.getInt("blockedMinutes"));
    }
}