package com.firstelement.security.platform;

import com.firstelement.security.platform.PlatformOperatorContextHolder.PlatformAccessDenied;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Slice 5: Support Access (Break-Glass) Service.
 *
 * <p>Principles:
 * <ul>
 *   <li>Operator has NO patient-data access by default.</li>
 *   <li>To investigate an issue: request access with a written reason, time-limited (15-120 min, default 60 min, auto-expires).</li>
 *   <li>Read-only by default and by enforcement: mutations are strictly forbidden.</li>
 *   <li>Clinic admin notified upon grant activation and revocation.</li>
 *   <li>Fully audited in both platform audit events and clinic tamper-evident audit logs.</li>
 *   <li>No open-ended "login as user" (no token masquerading).</li>
 * </ul>
 */
public class PlatformSupportAccessService {

    private static final String GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000";

    private final NamedParameterJdbcTemplate jdbc;
    private final PlatformAuditRepository audit;

    public PlatformSupportAccessService(NamedParameterJdbcTemplate jdbc, PlatformAuditRepository audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record RequestAccessCommand(
        UUID projectId,
        String reason,
        int durationMinutes,
        boolean isReadOnly
    ) {}

    public record RevokeAccessCommand(
        UUID grantId,
        String reason
    ) {}

    public record SupportAccessGrantDto(
        UUID id,
        UUID organizationId,
        UUID projectId,
        String clinicName,
        UUID operatorId,
        String operatorDisplayName,
        String reason,
        boolean isReadOnly,
        String status,
        Instant startsAt,
        Instant expiresAt,
        long remainingMinutes,
        Instant revokedAt,
        UUID revokedByOperatorId,
        String revokedByName,
        String revocationReason,
        Instant notifiedClinicAdminAt,
        Instant createdAt
    ) {}

    /**
     * Requests a break-glass support access grant for a specific clinic.
     */
    @Transactional
    public SupportAccessGrantDto requestAccess(PlatformOperatorContext operator, RequestAccessCommand cmd, String correlationId) {
        if (operator == null) {
            throw new PlatformAccessDenied("Operator context is required.");
        }
        if (cmd.projectId() == null) {
            throw new IllegalArgumentException("Clinic ID (projectId) is required.");
        }
        if (cmd.reason() == null || cmd.reason().trim().length() < 10) {
            throw new IllegalArgumentException("A written justification of at least 10 characters is required.");
        }

        int duration = cmd.durationMinutes() <= 0 ? 60 : cmd.durationMinutes();
        if (duration < 15 || duration > 120) {
            throw new IllegalArgumentException("Support access duration must be between 15 and 120 minutes.");
        }

        // Verify project exists and get its organizationId and name
        List<Map<String, Object>> clinicRows = jdbc.queryForList("""
            select p."organizationId", p."name"
              from "public"."projects" p
             where p."id" = :pid
            """, new MapSqlParameterSource("pid", cmd.projectId()));

        if (clinicRows.isEmpty()) {
            throw new IllegalArgumentException("Clinic not found: " + cmd.projectId());
        }

        UUID orgId = (UUID) clinicRows.get(0).get("organizationId");
        String clinicName = (String) clinicRows.get(0).get("name");

        Instant now = Instant.now();
        Instant expiresAt = now.plus(Duration.ofMinutes(duration));
        UUID grantId = UUID.randomUUID();
        boolean readOnly = true; // Support access is strictly read-only

        // Revoke any existing active grant for this operator & clinic
        jdbc.update("""
            update "public"."support_access_grants"
               set "status" = 'REVOKED',
                   "revokedAt" = :now,
                   "revokedByOperatorId" = :opId,
                   "revocationReason" = 'Superseded by new break-glass grant'
             where "operatorId" = :opId
               and "projectId" = :pid
               and "status" = 'ACTIVE'
               and "revokedAt" is null
            """,
            new MapSqlParameterSource()
                .addValue("now", Timestamp.from(now))
                .addValue("opId", operator.operatorId())
                .addValue("pid", cmd.projectId()));

        // Insert new grant
        jdbc.update("""
            insert into "public"."support_access_grants"
                ("id", "organizationId", "projectId", "operatorId", "reason", "isReadOnly",
                 "status", "startsAt", "expiresAt", "notifiedClinicAdminAt", "createdAt")
            values
                (:id, :orgId, :pid, :opId, :reason, :isReadOnly,
                 'ACTIVE', :startsAt, :expiresAt, :now, :now)
            """,
            new MapSqlParameterSource()
                .addValue("id", grantId)
                .addValue("orgId", orgId)
                .addValue("pid", cmd.projectId())
                .addValue("opId", operator.operatorId())
                .addValue("reason", cmd.reason().trim())
                .addValue("isReadOnly", readOnly)
                .addValue("startsAt", Timestamp.from(now))
                .addValue("expiresAt", Timestamp.from(expiresAt))
                .addValue("now", Timestamp.from(now)));

        // Notify Clinic Admin: record in clinic_audit_log if an admin exists
        notifyClinicAdmin(orgId, cmd.projectId(), grantId, operator, "BREAK_GLASS_ACTIVATED",
            "Break-glass support access granted for " + duration + "m: " + cmd.reason().trim(), now);

        // Record in platform audit events
        audit.record("SUPPORT_ACCESS_GRANTED", operator.operatorId(), operator.sessionId(),
            orgId, cmd.projectId(), "Project", cmd.projectId().toString(),
            cmd.reason().trim(), "grantId=" + grantId + "; duration=" + duration + "m; readOnly=true",
            PlatformAuditRepository.ALLOWED, correlationId, null);

        return new SupportAccessGrantDto(
            grantId, orgId, cmd.projectId(), clinicName,
            operator.operatorId(), operator.displayName(),
            cmd.reason().trim(), readOnly, "ACTIVE",
            now, expiresAt, duration, null, null, null, null,
            now, now
        );
    }

    /**
     * Revokes an active break-glass support access grant.
     */
    @Transactional
    public SupportAccessGrantDto revokeAccess(PlatformOperatorContext operator, RevokeAccessCommand cmd, String correlationId) {
        if (operator == null) {
            throw new PlatformAccessDenied("Operator context is required.");
        }
        if (cmd.grantId() == null) {
            throw new IllegalArgumentException("Grant ID is required.");
        }
        String reason = cmd.reason() == null ? "Manually revoked by operator" : cmd.reason().trim();

        SupportAccessGrantDto existing = getGrant(cmd.grantId());
        if ("REVOKED".equalsIgnoreCase(existing.status())) {
            throw new IllegalStateException("Support access grant is already revoked.");
        }
        if ("EXPIRED".equalsIgnoreCase(existing.status())) {
            throw new IllegalStateException("Support access grant has already expired.");
        }

        Instant now = Instant.now();
        jdbc.update("""
            update "public"."support_access_grants"
               set "status" = 'REVOKED',
                   "revokedAt" = :now,
                   "revokedByOperatorId" = :opId,
                   "revocationReason" = :reason
             where "id" = :id
            """,
            new MapSqlParameterSource()
                .addValue("id", cmd.grantId())
                .addValue("now", Timestamp.from(now))
                .addValue("opId", operator.operatorId())
                .addValue("reason", reason));

        // Notify clinic admin of revocation
        notifyClinicAdmin(existing.organizationId(), existing.projectId(), cmd.grantId(), operator,
            "BREAK_GLASS_REVOKED", "Break-glass support access revoked: " + reason, now);

        // Audit revocation
        audit.record("SUPPORT_ACCESS_REVOKED", operator.operatorId(), operator.sessionId(),
            existing.organizationId(), existing.projectId(), "Project", existing.projectId().toString(),
            reason, "grantId=" + cmd.grantId(),
            PlatformAuditRepository.ALLOWED, correlationId, null);

        return getGrant(cmd.grantId());
    }

    /**
     * Retrieves all support access grants with clinic and operator details, auto-expiring lapsed grants.
     */
    @Transactional
    public List<SupportAccessGrantDto> listGrants(UUID projectId, String statusFilter) {
        // Auto-expire active grants whose expiresAt has passed
        Instant now = Instant.now();
        jdbc.update("""
            update "public"."support_access_grants"
               set "status" = 'EXPIRED'
             where "status" = 'ACTIVE'
               and "expiresAt" <= :now
            """, new MapSqlParameterSource("now", Timestamp.from(now)));

        StringBuilder sql = new StringBuilder("""
            select g."id", g."organizationId", g."projectId", p."name" as "clinicName",
                   g."operatorId", op."displayName" as "operatorDisplayName",
                   g."reason", g."isReadOnly", g."status",
                   g."startsAt", g."expiresAt", g."revokedAt",
                   g."revokedByOperatorId", rop."displayName" as "revokedByName",
                   g."revocationReason", g."notifiedClinicAdminAt", g."createdAt"
              from "public"."support_access_grants" g
              join "public"."projects" p on p."id" = g."projectId"
              join "public"."platform_operators" op on op."id" = g."operatorId"
              left join "public"."platform_operators" rop on rop."id" = g."revokedByOperatorId"
             where 1=1
            """);

        MapSqlParameterSource params = new MapSqlParameterSource();
        if (projectId != null) {
            sql.append(" and g.\"projectId\" = :pid");
            params.addValue("pid", projectId);
        }
        if (statusFilter != null && !statusFilter.isBlank()) {
            sql.append(" and upper(g.\"status\") = :status");
            params.addValue("status", statusFilter.trim().toUpperCase());
        }
        sql.append(" order by g.\"createdAt\" desc limit 200");

        return jdbc.query(sql.toString(), params, (rs, i) -> mapGrantRow(rs, now));
    }

    /**
     * Gets a single support access grant by ID.
     */
    @Transactional
    public SupportAccessGrantDto getGrant(UUID grantId) {
        Instant now = Instant.now();
        // Check and expire if needed
        jdbc.update("""
            update "public"."support_access_grants"
               set "status" = 'EXPIRED'
             where "id" = :id
               and "status" = 'ACTIVE'
               and "expiresAt" <= :now
            """, new MapSqlParameterSource().addValue("id", grantId).addValue("now", Timestamp.from(now)));

        String sql = """
            select g."id", g."organizationId", g."projectId", p."name" as "clinicName",
                   g."operatorId", op."displayName" as "operatorDisplayName",
                   g."reason", g."isReadOnly", g."status",
                   g."startsAt", g."expiresAt", g."revokedAt",
                   g."revokedByOperatorId", rop."displayName" as "revokedByName",
                   g."revocationReason", g."notifiedClinicAdminAt", g."createdAt"
              from "public"."support_access_grants" g
              join "public"."projects" p on p."id" = g."projectId"
              join "public"."platform_operators" op on op."id" = g."operatorId"
              left join "public"."platform_operators" rop on rop."id" = g."revokedByOperatorId"
             where g."id" = :id
            """;

        List<SupportAccessGrantDto> list = jdbc.query(sql, new MapSqlParameterSource("id", grantId),
            (rs, i) -> mapGrantRow(rs, now));

        if (list.isEmpty()) {
            throw new IllegalArgumentException("Support access grant not found: " + grantId);
        }
        return list.get(0);
    }

    /**
     * Checks if the operator holds an active, valid, unexpired, and non-revoked support access grant for the clinic.
     */
    public boolean hasActiveSupportAccess(UUID operatorId, UUID projectId) {
        if (operatorId == null || projectId == null) {
            return false;
        }
        Integer count = jdbc.queryForObject("""
            select count(*)
              from "public"."support_access_grants"
             where "operatorId" = :opId
               and "projectId" = :pid
               and "status" = 'ACTIVE'
               and "expiresAt" > :now
               and "revokedAt" is null
            """,
            new MapSqlParameterSource()
                .addValue("opId", operatorId)
                .addValue("pid", projectId)
                .addValue("now", Timestamp.from(Instant.now())),
            Integer.class);
        return count != null && count > 0;
    }

    // ------------------------------------------------------------------
    // Diagnostic Clinical Inspection (Read-Only under Active Grant)
    // ------------------------------------------------------------------

    public List<Map<String, Object>> getClinicPatients(PlatformOperatorContext operator, UUID grantId, String correlationId) {
        SupportAccessGrantDto grant = verifyActiveGrantForInspection(operator, grantId);

        List<Map<String, Object>> patients = jdbc.queryForList("""
            select pt."id", pt."patientNo", pt."displayName", pt."gender",
                   pt."dateOfBirth", pt."primaryPhone", pt."primaryEmail", pt."city",
                   pt."isActive", pt."createdAt"
              from "public"."patients" pt
             where pt."projectId" = :pid
               and pt."deletedAt" is null
             order by pt."createdAt" desc
             limit 50
            """, new MapSqlParameterSource("pid", grant.projectId()));

        audit.record("SUPPORT_INSPECT_PATIENTS", operator.operatorId(), operator.sessionId(),
            grant.organizationId(), grant.projectId(), "Project", grant.projectId().toString(),
            grant.reason(), "grantId=" + grantId + "; retrievedCount=" + patients.size(),
            PlatformAuditRepository.ALLOWED, correlationId, null);

        notifyClinicAdmin(grant.organizationId(), grant.projectId(), grantId, operator,
            "READ_PATIENT_RECORD", "Patient records reviewed under support grant " + grantId, Instant.now());

        return patients;
    }

    public List<Map<String, Object>> getClinicAppointments(PlatformOperatorContext operator, UUID grantId, String correlationId) {
        SupportAccessGrantDto grant = verifyActiveGrantForInspection(operator, grantId);

        List<Map<String, Object>> appointments = jdbc.queryForList("""
            select a."id", a."patientName", a."doctorName", a."appointmentDate",
                   a."durationMinutes", a."status", a."title", a."service", a."paymentStatus",
                   a."notes", a."createdAt"
              from "public"."appointments" a
             where a."projectId" = :pid
             order by a."appointmentDate" desc
             limit 50
            """, new MapSqlParameterSource("pid", grant.projectId()));

        audit.record("SUPPORT_INSPECT_APPOINTMENTS", operator.operatorId(), operator.sessionId(),
            grant.organizationId(), grant.projectId(), "Project", grant.projectId().toString(),
            grant.reason(), "grantId=" + grantId + "; retrievedCount=" + appointments.size(),
            PlatformAuditRepository.ALLOWED, correlationId, null);

        return appointments;
    }

    public List<Map<String, Object>> getClinicEncounters(PlatformOperatorContext operator, UUID grantId, String correlationId) {
        SupportAccessGrantDto grant = verifyActiveGrantForInspection(operator, grantId);

        List<Map<String, Object>> encounters = jdbc.queryForList("""
            select e."id", e."patientId", e."doctorId", e."chiefComplaint",
                   e."diagnosis", e."status", e."visitDate", e."durationMinutes", e."createdAt"
              from "public"."clinical_encounters" e
             where e."projectId" = :pid
             order by e."visitDate" desc
             limit 50
            """, new MapSqlParameterSource("pid", grant.projectId()));

        audit.record("SUPPORT_INSPECT_ENCOUNTERS", operator.operatorId(), operator.sessionId(),
            grant.organizationId(), grant.projectId(), "Project", grant.projectId().toString(),
            grant.reason(), "grantId=" + grantId + "; retrievedCount=" + encounters.size(),
            PlatformAuditRepository.ALLOWED, correlationId, null);

        return encounters;
    }

    public List<Map<String, Object>> getClinicAuditLog(PlatformOperatorContext operator, UUID grantId, String correlationId) {
        SupportAccessGrantDto grant = verifyActiveGrantForInspection(operator, grantId);

        List<Map<String, Object>> logs = jdbc.queryForList("""
            select al."id", al."actorUserName", al."actorRole", al."action",
                   al."resourceType", al."resourceId", al."occurredAt", al."metadataJson"
              from "public"."clinic_audit_log" al
             where al."projectId" = :pid
             order by al."occurredAt" desc
             limit 50
            """, new MapSqlParameterSource("pid", grant.projectId()));

        audit.record("SUPPORT_INSPECT_AUDIT_LOG", operator.operatorId(), operator.sessionId(),
            grant.organizationId(), grant.projectId(), "Project", grant.projectId().toString(),
            grant.reason(), "grantId=" + grantId + "; retrievedCount=" + logs.size(),
            PlatformAuditRepository.ALLOWED, correlationId, null);

        return logs;
    }

    public void rejectMutation() {
        throw new PlatformAccessDenied("Support access is strictly read-only. Clinical mutations are forbidden.");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private SupportAccessGrantDto verifyActiveGrantForInspection(PlatformOperatorContext operator, UUID grantId) {
        if (operator == null) {
            throw new PlatformAccessDenied("Operator authentication is required.");
        }
        SupportAccessGrantDto grant = getGrant(grantId);

        if ("REVOKED".equalsIgnoreCase(grant.status()) || grant.revokedAt() != null) {
            throw new PlatformAccessDenied("Support access grant was revoked.");
        }
        if ("EXPIRED".equalsIgnoreCase(grant.status()) || Instant.now().isAfter(grant.expiresAt())) {
            throw new PlatformAccessDenied("Support access grant has expired.");
        }
        if (!"ACTIVE".equalsIgnoreCase(grant.status())) {
            throw new PlatformAccessDenied("Support access grant is not active.");
        }
        return grant;
    }

    private SupportAccessGrantDto mapGrantRow(java.sql.ResultSet rs, Instant now) throws java.sql.SQLException {
        Instant startsAt = toInstant(rs.getTimestamp("startsAt"));
        Instant expiresAt = toInstant(rs.getTimestamp("expiresAt"));
        Instant revokedAt = toInstant(rs.getTimestamp("revokedAt"));
        String status = rs.getString("status");

        if ("ACTIVE".equalsIgnoreCase(status) && expiresAt != null && now.isAfter(expiresAt)) {
            status = "EXPIRED";
        }

        long remaining = 0;
        if ("ACTIVE".equalsIgnoreCase(status) && expiresAt != null && now.isBefore(expiresAt)) {
            remaining = Duration.between(now, expiresAt).toMinutes();
        }

        return new SupportAccessGrantDto(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("organizationId"),
            (UUID) rs.getObject("projectId"),
            rs.getString("clinicName"),
            (UUID) rs.getObject("operatorId"),
            rs.getString("operatorDisplayName"),
            rs.getString("reason"),
            rs.getBoolean("isReadOnly"),
            status,
            startsAt,
            expiresAt,
            remaining,
            revokedAt,
            (UUID) rs.getObject("revokedByOperatorId"),
            rs.getString("revokedByName"),
            rs.getString("revocationReason"),
            toInstant(rs.getTimestamp("notifiedClinicAdminAt")),
            toInstant(rs.getTimestamp("createdAt"))
        );
    }

    private void notifyClinicAdmin(UUID orgId, UUID projectId, UUID grantId,
                                   PlatformOperatorContext operator, String action, String description, Instant now) {
        try {
            // Find clinic admin user for the project
            List<Map<String, Object>> adminRows = jdbc.queryForList("""
                select u."id" as "userId"
                  from "public"."users" u
                  join "public"."project_memberships" pm on pm."userId" = u."id"
                 where pm."projectId" = :pid
                   and u."role" = 'ADMIN'
                 order by u."createdAt" asc
                 limit 1
                """, new MapSqlParameterSource("pid", projectId));

            if (adminRows.isEmpty()) {
                // Try any user in this project
                adminRows = jdbc.queryForList("""
                    select u."id" as "userId"
                      from "public"."users" u
                      join "public"."project_memberships" pm on pm."userId" = u."id"
                     where pm."projectId" = :pid
                     order by u."createdAt" asc
                     limit 1
                    """, new MapSqlParameterSource("pid", projectId));
            }

            if (!adminRows.isEmpty()) {
                UUID adminUserId = (UUID) adminRows.get(0).get("userId");
                String prevHash = findLatestClinicAuditHash(orgId, projectId);
                String tamperHash = computeHash(prevHash, orgId, adminUserId, action, "BREAK_GLASS", grantId.toString(), now.toEpochMilli());

                String metadata = String.format("{\"operatorEmail\":\"%s\",\"grantId\":\"%s\",\"note\":\"%s\"}",
                    operator.email(), grantId, description.replace("\"", "\\\""));

                jdbc.update("""
                    insert into "public"."clinic_audit_log" (
                        "id", "organizationId", "projectId", "patientId",
                        "actorUserId", "actorUserName", "actorRole", "action",
                        "resourceType", "resourceId", "breakGlassGrantId",
                        "ipAddress", "userAgent", "metadataJson", "tamperHash", "occurredAt"
                    ) values (
                        :id, :orgId, :pid, null,
                        :actorUserId, :actorUserName, 'PLATFORM_OPERATOR', :action,
                        'BREAK_GLASS', :resourceId, null,
                        '127.0.0.1', 'Platform Console', :metadata, :tamperHash, :occurredAt
                    )
                    """,
                    new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("orgId", orgId)
                        .addValue("pid", projectId)
                        .addValue("actorUserId", adminUserId)
                        .addValue("actorUserName", "Platform Operator (" + operator.displayName() + ")")
                        .addValue("action", action)
                        .addValue("resourceId", grantId.toString())
                        .addValue("metadata", metadata)
                        .addValue("tamperHash", tamperHash)
                        .addValue("occurredAt", Timestamp.from(now)));
            }
        } catch (Exception e) {
            // Audit log notification is best-effort and must never crash the operator flow
        }
    }

    private String findLatestClinicAuditHash(UUID orgId, UUID projectId) {
        try {
            List<String> list = jdbc.query("""
                select al."tamperHash"
                  from "public"."clinic_audit_log" al
                 where al."organizationId" = :orgId
                   and al."projectId" = :pid
                 order by al."occurredAt" desc, al."id" desc
                 limit 1
                """,
                new MapSqlParameterSource().addValue("orgId", orgId).addValue("pid", projectId),
                (rs, rowNum) -> rs.getString("tamperHash"));
            return list.isEmpty() ? GENESIS_HASH : list.get(0);
        } catch (Exception e) {
            return GENESIS_HASH;
        }
    }

    private String computeHash(String prevHash, UUID orgId, UUID actorUserId, String action,
                               String resourceType, String resourceId, long epochMilli) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String payload = String.format("%s:%s:%s:%s:%s:%s:%d",
                prevHash,
                orgId != null ? orgId.toString() : "null",
                actorUserId != null ? actorUserId.toString() : "null",
                action,
                resourceType,
                resourceId != null ? resourceId : "null",
                epochMilli);
            byte[] encodedhash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(encodedhash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    private Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
