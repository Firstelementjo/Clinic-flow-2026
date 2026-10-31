package com.firstelement.crm.clinic.audit;

import com.firstelement.crm.clinic.ClinicScope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Repository for break-glass emergency access, immutable audit logging, and curated reports
 * (AUD-01 to AUD-06, RPT-01 to RPT-05).
 */
@Repository
public class ClinicAuditRepository {

    public static final String GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000";

    private final NamedParameterJdbcTemplate jdbc;

    public ClinicAuditRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // 1. Break-Glass Grants
    // ------------------------------------------------------------------

    public ClinicAuditModels.BreakGlassGrantRow insertBreakGlass(
            ClinicScope scope,
            UUID grantId,
            UUID patientId,
            UUID requestedByUserId,
            String reason,
            Instant grantedAt,
            Instant expiresAt) {
        return scope.read(() -> {
            UUID orgId = scope.queryScope().organizationId();
            UUID projId = scope.projectId() != null
                    ? scope.projectId()
                    : (!scope.accessibleProjectIds().isEmpty() ? scope.accessibleProjectIds().get(0) : null);

            MapSqlParameterSource params = scope.bind()
                    .addValue("id", grantId)
                    .addValue("orgId", orgId)
                    .addValue("projId", projId)
                    .addValue("patientId", patientId)
                    .addValue("requestedByUserId", requestedByUserId)
                    .addValue("reason", reason)
                    .addValue("grantedAt", Timestamp.from(grantedAt))
                    .addValue("expiresAt", Timestamp.from(expiresAt));

            jdbc.update("""
                    insert into clinic_break_glass_grants (
                        id, "organizationId", "projectId", "patientId",
                        "requestedByUserId", "reason", "grantedAt", "expiresAt", "reviewStatus"
                    ) values (
                        :id, :orgId, :projId, :patientId,
                        :requestedByUserId, :reason, :grantedAt, :expiresAt, 'PENDING'
                    )
                    """, params);

            return findBreakGlassById(scope, grantId)
                    .orElseThrow(() -> new IllegalStateException("Failed to retrieve created break-glass grant"));
        });
    }

    public Optional<ClinicAuditModels.BreakGlassGrantRow> findBreakGlassById(ClinicScope scope, UUID grantId) {
        return scope.read(() -> {
            MapSqlParameterSource params = scope.bind().addValue("id", grantId);
            List<ClinicAuditModels.BreakGlassGrantRow> list = jdbc.query("""
                    select bg.id, bg."organizationId", bg."projectId", bg."patientId",
                           bg."requestedByUserId", bg."reason", bg."grantedAt", bg."expiresAt",
                           bg."revokedAt", bg."revokedByUserId", bg."revocationReason",
                           bg."reviewStatus", bg."reviewedByUserId", bg."reviewedAt", bg."reviewNotes"
                      from clinic_break_glass_grants bg
                     where %s
                       and bg.id = :id
                    """.formatted(scope.predicate("bg")), params, this::mapBreakGlassRow);
            return list.isEmpty() ? Optional.empty() : Optional.of(list.getFirst());
        });
    }

    public Optional<ClinicAuditModels.BreakGlassGrantRow> findActiveBreakGlassForUserAndPatient(
            ClinicScope scope, UUID userId, UUID patientId, Instant now) {
        return scope.read(() -> {
            MapSqlParameterSource params = scope.bind()
                    .addValue("userId", userId)
                    .addValue("patientId", patientId)
                    .addValue("now", Timestamp.from(now));

            List<ClinicAuditModels.BreakGlassGrantRow> list = jdbc.query("""
                    select bg.id, bg."organizationId", bg."projectId", bg."patientId",
                           bg."requestedByUserId", bg."reason", bg."grantedAt", bg."expiresAt",
                           bg."revokedAt", bg."revokedByUserId", bg."revocationReason",
                           bg."reviewStatus", bg."reviewedByUserId", bg."reviewedAt", bg."reviewNotes"
                      from clinic_break_glass_grants bg
                     where %s
                       and bg."requestedByUserId" = :userId
                       and (:patientId::uuid is null or bg."patientId" is null or bg."patientId" = :patientId::uuid)
                       and bg."revokedAt" is null
                       and bg."expiresAt" > :now
                     order by bg."grantedAt" desc
                     limit 1
                    """.formatted(scope.predicate("bg")), params, this::mapBreakGlassRow);
            return list.isEmpty() ? Optional.empty() : Optional.of(list.getFirst());
        });
    }

    public List<ClinicAuditModels.BreakGlassGrantRow> listActiveBreakGlass(ClinicScope scope, Instant now) {
        return scope.read(() -> {
            MapSqlParameterSource params = scope.bind().addValue("now", Timestamp.from(now));
            return jdbc.query("""
                    select bg.id, bg."organizationId", bg."projectId", bg."patientId",
                           bg."requestedByUserId", bg."reason", bg."grantedAt", bg."expiresAt",
                           bg."revokedAt", bg."revokedByUserId", bg."revocationReason",
                           bg."reviewStatus", bg."reviewedByUserId", bg."reviewedAt", bg."reviewNotes"
                      from clinic_break_glass_grants bg
                     where %s
                       and bg."revokedAt" is null
                       and bg."expiresAt" > :now
                     order by bg."grantedAt" desc
                    """.formatted(scope.predicate("bg")), params, this::mapBreakGlassRow);
        });
    }

    public List<ClinicAuditModels.BreakGlassGrantRow> listBreakGlassPendingReview(ClinicScope scope) {
        return scope.read(() -> {
            MapSqlParameterSource params = scope.bind();
            return jdbc.query("""
                    select bg.id, bg."organizationId", bg."projectId", bg."patientId",
                           bg."requestedByUserId", bg."reason", bg."grantedAt", bg."expiresAt",
                           bg."revokedAt", bg."revokedByUserId", bg."revocationReason",
                           bg."reviewStatus", bg."reviewedByUserId", bg."reviewedAt", bg."reviewNotes"
                      from clinic_break_glass_grants bg
                     where %s
                       and bg."reviewStatus" = 'PENDING'
                     order by bg."grantedAt" desc
                    """.formatted(scope.predicate("bg")), params, this::mapBreakGlassRow);
        });
    }

    public boolean reviewBreakGlass(
            ClinicScope scope, UUID grantId, UUID reviewerUserId, String status, Instant reviewedAt, String notes) {
        return scope.read(() -> {
            MapSqlParameterSource params = scope.bind()
                    .addValue("id", grantId)
                    .addValue("reviewerUserId", reviewerUserId)
                    .addValue("status", status)
                    .addValue("reviewedAt", Timestamp.from(reviewedAt))
                    .addValue("notes", notes);

            int updated = jdbc.update("""
                    update clinic_break_glass_grants bg
                       set "reviewStatus" = :status,
                           "reviewedByUserId" = :reviewerUserId,
                           "reviewedAt" = :reviewedAt,
                           "reviewNotes" = :notes
                     where %s
                       and bg.id = :id
                    """.formatted(scope.predicate("bg")), params);
            return updated > 0;
        });
    }

    public boolean revokeBreakGlass(
            ClinicScope scope, UUID grantId, UUID revokerUserId, Instant revokedAt, String reason) {
        return scope.read(() -> {
            MapSqlParameterSource params = scope.bind()
                    .addValue("id", grantId)
                    .addValue("revokerUserId", revokerUserId)
                    .addValue("revokedAt", Timestamp.from(revokedAt))
                    .addValue("reason", reason);

            int updated = jdbc.update("""
                    update clinic_break_glass_grants bg
                       set "revokedAt" = :revokedAt,
                           "revokedByUserId" = :revokerUserId,
                           "revocationReason" = :reason
                     where %s
                       and bg.id = :id
                       and bg."revokedAt" is null
                    """.formatted(scope.predicate("bg")), params);
            return updated > 0;
        });
    }

    // ------------------------------------------------------------------
    // 2. Tamper-Evident Audit Trail
    // ------------------------------------------------------------------

    public String findLatestAuditHash(ClinicScope scope) {
        return scope.read(() -> {
            MapSqlParameterSource params = scope.bind();
            List<String> list = jdbc.query("""
                    select al."tamperHash"
                      from clinic_audit_log al
                     where %s
                     order by al."occurredAt" desc, al.id desc
                     limit 1
                    """.formatted(scope.predicate("al")), params, (rs, rowNum) -> rs.getString("tamperHash"));
            return list.isEmpty() ? GENESIS_HASH : list.getFirst();
        });
    }

    public ClinicAuditModels.ClinicAuditRow insertAuditLog(
            ClinicScope scope,
            UUID auditId,
            UUID patientId,
            UUID actorUserId,
            String actorUserName,
            String actorRole,
            String action,
            String resourceType,
            String resourceId,
            UUID breakGlassGrantId,
            String ipAddress,
            String userAgent,
            String metadataJson,
            String tamperHash,
            Instant occurredAt) {
        return scope.read(() -> {
            UUID orgId = scope.queryScope().organizationId();
            UUID projId = scope.projectId() != null
                    ? scope.projectId()
                    : (!scope.accessibleProjectIds().isEmpty() ? scope.accessibleProjectIds().get(0) : null);

            MapSqlParameterSource params = scope.bind()
                    .addValue("id", auditId)
                    .addValue("orgId", orgId)
                    .addValue("projId", projId)
                    .addValue("patientId", patientId)
                    .addValue("actorUserId", actorUserId)
                    .addValue("actorUserName", actorUserName)
                    .addValue("actorRole", actorRole)
                    .addValue("action", action)
                    .addValue("resourceType", resourceType)
                    .addValue("resourceId", resourceId)
                    .addValue("breakGlassGrantId", breakGlassGrantId)
                    .addValue("ipAddress", ipAddress)
                    .addValue("userAgent", userAgent)
                    .addValue("metadataJson", metadataJson)
                    .addValue("tamperHash", tamperHash)
                    .addValue("occurredAt", Timestamp.from(occurredAt));

            jdbc.update("""
                    insert into clinic_audit_log (
                        id, "organizationId", "projectId", "patientId",
                        "actorUserId", "actorUserName", "actorRole", "action",
                        "resourceType", "resourceId", "breakGlassGrantId",
                        "ipAddress", "userAgent", "metadataJson", "tamperHash", "occurredAt"
                    ) values (
                        :id, :orgId, :projId, :patientId,
                        :actorUserId, :actorUserName, :actorRole, :action,
                        :resourceType, :resourceId, :breakGlassGrantId,
                        :ipAddress, :userAgent, :metadataJson, :tamperHash, :occurredAt
                    )
                    """, params);

            return new ClinicAuditModels.ClinicAuditRow(
                    auditId,
                    orgId,
                    projId,
                    patientId,
                    actorUserId,
                    actorUserName,
                    actorRole,
                    action,
                    resourceType,
                    resourceId,
                    breakGlassGrantId,
                    ipAddress,
                    userAgent,
                    metadataJson,
                    tamperHash,
                    occurredAt);
        });
    }

    public List<ClinicAuditModels.ClinicAuditRow> queryAuditLog(
            ClinicScope scope,
            UUID patientId,
            UUID actorUserId,
            String action,
            String resourceType,
            Instant from,
            Instant to,
            int limit) {
        return scope.read(() -> {
            MapSqlParameterSource params = scope.bind()
                    .addValue("patientId", patientId)
                    .addValue("actorUserId", actorUserId)
                    .addValue("action", action)
                    .addValue("resourceType", resourceType)
                    .addValue("from", from != null ? Timestamp.from(from) : null)
                    .addValue("to", to != null ? Timestamp.from(to) : null)
                    .addValue("limit", Math.max(1, Math.min(limit, 500)));

            return jdbc.query("""
                    select al.id, al."organizationId", al."projectId", al."patientId",
                           al."actorUserId", al."actorUserName", al."actorRole", al."action",
                           al."resourceType", al."resourceId", al."breakGlassGrantId",
                           al."ipAddress", al."userAgent", al."metadataJson", al."tamperHash", al."occurredAt"
                      from clinic_audit_log al
                     where %s
                       and (:patientId::uuid is null or al."patientId" = :patientId::uuid)
                       and (:actorUserId::uuid is null or al."actorUserId" = :actorUserId::uuid)
                       and (:action::varchar is null or al."action" = :action::varchar)
                       and (:resourceType::varchar is null or al."resourceType" = :resourceType::varchar)
                       and (:from::timestamptz is null or al."occurredAt" >= :from::timestamptz)
                       and (:to::timestamptz is null or al."occurredAt" <= :to::timestamptz)
                     order by al."occurredAt" desc
                     limit :limit
                    """.formatted(scope.predicate("al")), params, this::mapAuditRow);
        });
    }

    // ------------------------------------------------------------------
    // 3. Curated Parameterized Reports
    // ------------------------------------------------------------------

    public List<ClinicAuditModels.AppointmentProductivityRow> queryAppointmentProductivity(
            ClinicScope scope, Instant from, Instant to, UUID doctorId) {
        return scope.read(() -> {
            MapSqlParameterSource params = scope.bind()
                    .addValue("from", Timestamp.from(from))
                    .addValue("to", Timestamp.from(to))
                    .addValue("doctorId", doctorId);

            return jdbc.query("""
                    select d.id as "doctorId", d.name as "doctorName", d.specialty,
                           count(a.id) as total_appointments,
                           count(a.id) filter (where a.status = 'COMPLETED') as completed_appointments,
                           count(a.id) filter (where a.status = 'CANCELLED') as cancelled_appointments,
                           count(a.id) filter (where a.status in ('NO_SHOW', 'NO_SHOW_RECORDED')) as noshow_appointments,
                           coalesce(inv.total_billed, 0.000) as total_invoiced
                      from doctors d
                      left join appointments a on a."doctorId" = d.id and a."organizationId" = d."organizationId"
                           and a."appointmentDate" >= :from and a."appointmentDate" <= :to
                      left join (
                          select ce."doctorId", sum(ci."totalAmount") as total_billed
                            from clinic_invoices ci
                            join clinical_encounters ce on ci."encounterId" = ce.id and ci."organizationId" = ce."organizationId"
                           where %s and ci."status" != 'CANCELLED'
                             and ci."issuedAt" >= :from and ci."issuedAt" <= :to
                           group by ce."doctorId"
                      ) inv on inv."doctorId" = d.id
                     where %s
                       and (:doctorId::uuid is null or d.id = :doctorId::uuid)
                     group by d.id, d.name, d.specialty, inv.total_billed
                     order by total_appointments desc, d.name asc
                    """.formatted(scope.predicate("ci"), scope.predicate("d")), params, (rs, rowNum) -> {
                long total = rs.getLong("total_appointments");
                long completed = rs.getLong("completed_appointments");
                double completionRate = total > 0 ? ((double) completed / total) * 100.0 : 0.0;
                return new ClinicAuditModels.AppointmentProductivityRow(
                        (UUID) rs.getObject("doctorId"),
                        rs.getString("doctorName"),
                        rs.getString("specialty"),
                        total,
                        completed,
                        rs.getLong("cancelled_appointments"),
                        rs.getLong("noshow_appointments"),
                        Math.round(completionRate * 10.0) / 10.0,
                        rs.getBigDecimal("total_invoiced").setScale(3, RoundingMode.HALF_UP));
            });
        });
    }

    public List<ClinicAuditModels.ProcedureUtilizationRow> queryProcedureUtilization(
            ClinicScope scope, Instant from, Instant to) {
        return scope.read(() -> {
            MapSqlParameterSource params = scope.bind()
                    .addValue("from", Timestamp.from(from))
                    .addValue("to", Timestamp.from(to));

            return jdbc.query("""
                    select coalesce(tpi."procedureId"::text, tpi."procedureName") as procedure_code,
                           tpi."procedureName" as procedure_name,
                           coalesce(p.specialty, 'GENERAL') as category,
                           count(tpi.id) as procedure_count,
                           coalesce(sum(tpi."invoicedAmount"), 0.000) as total_revenue,
                           coalesce(avg(tpi."estimatedCost"), 0.000) as avg_price
                      from treatment_plan_items tpi
                      left join procedures p on tpi."procedureId" = p.id and tpi."organizationId" = p."organizationId"
                     where %s
                       and tpi."createdAt" >= :from and tpi."createdAt" <= :to
                     group by tpi."procedureId", tpi."procedureName", p.specialty
                     order by procedure_count desc, total_revenue desc
                    """.formatted(scope.predicate("tpi")), params, (rs, rowNum) -> new ClinicAuditModels.ProcedureUtilizationRow(
                    rs.getString("procedure_code"),
                    rs.getString("procedure_name"),
                    rs.getString("category"),
                    rs.getLong("procedure_count"),
                    rs.getBigDecimal("total_revenue").setScale(3, RoundingMode.HALF_UP),
                    rs.getBigDecimal("avg_price").setScale(3, RoundingMode.HALF_UP)));
        });
    }

    public List<ClinicAuditModels.FinancialCollectionSummaryRow> queryFinancialCollectionSummary(
            ClinicScope scope, Instant from, Instant to) {
        return scope.read(() -> {
            MapSqlParameterSource params = scope.bind()
                    .addValue("from", Timestamp.from(from))
                    .addValue("to", Timestamp.from(to));

            return jdbc.query("""
                    select ci."currency",
                           coalesce(sum(ci."totalAmount") filter (where ci."status" != 'CANCELLED'), 0.000) as total_invoiced,
                           coalesce(sum(ci."paidAmount") filter (where ci."status" != 'CANCELLED'), 0.000) as total_collected,
                           coalesce(sum(ci."balanceDue") filter (where ci."status" != 'CANCELLED'), 0.000) as total_outstanding,
                           count(ci.id) filter (where ci."status" != 'CANCELLED') as invoice_count,
                           coalesce(pmt.payment_count, 0) as payment_count
                      from clinic_invoices ci
                      left join (
                          select cp."currency", count(cp.id) as payment_count
                            from clinic_payments cp
                           where %s
                             and cp."paymentDate" >= :from and cp."paymentDate" <= :to
                           group by cp."currency"
                      ) pmt on pmt."currency" = ci."currency"
                     where %s
                       and ci."issuedAt" >= :from and ci."issuedAt" <= :to
                     group by ci."currency", pmt.payment_count
                    """.formatted(scope.predicate("cp"), scope.predicate("ci")), params, (rs, rowNum) -> {
                BigDecimal invoiced = rs.getBigDecimal("total_invoiced");
                BigDecimal collected = rs.getBigDecimal("total_collected");
                double rate = invoiced.compareTo(BigDecimal.ZERO) > 0
                        ? collected.divide(invoiced, 4, RoundingMode.HALF_UP).doubleValue() * 100.0
                        : 0.0;
                return new ClinicAuditModels.FinancialCollectionSummaryRow(
                        rs.getString("currency"),
                        invoiced.setScale(3, RoundingMode.HALF_UP),
                        collected.setScale(3, RoundingMode.HALF_UP),
                        rs.getBigDecimal("total_outstanding").setScale(3, RoundingMode.HALF_UP),
                        Math.round(rate * 10.0) / 10.0,
                        rs.getLong("invoice_count"),
                        rs.getLong("payment_count"));
            });
        });
    }

    public List<ClinicAuditModels.PatientRetentionRecallRow> queryPatientRetentionRecall(
            ClinicScope scope, int overdueDaysThreshold) {
        return scope.read(() -> {
            Instant now = Instant.now();
            MapSqlParameterSource params = scope.bind()
                    .addValue("now", Timestamp.from(now))
                    .addValue("thresholdDays", overdueDaysThreshold);

            return jdbc.query("""
                    select p.id as "patientId",
                           p."displayName" as "patientName",
                           coalesce(p."primaryPhone", '') as phone,
                           max(ce."visitDate") as last_visit_date
                      from patients p
                      left join clinical_encounters ce on ce."patientId" = p.id and ce."organizationId" = p."organizationId"
                     where %s
                       and p."deletedAt" is null
                     group by p.id, p."displayName", p."primaryPhone"
                    """.formatted(scope.predicate("p")), params, (rs, rowNum) -> {
                Timestamp ts = rs.getTimestamp("last_visit_date");
                Instant lastVisit = ts != null ? ts.toInstant() : null;
                long daysSince = lastVisit != null ? Duration.between(lastVisit, now).toDays() : 999;
                String status;
                if (daysSince >= overdueDaysThreshold) {
                    status = "OVERDUE";
                } else if (daysSince >= overdueDaysThreshold - 30) {
                    status = "DUE_SOON";
                } else {
                    status = "UP_TO_DATE";
                }
                return new ClinicAuditModels.PatientRetentionRecallRow(
                        (UUID) rs.getObject("patientId"),
                        rs.getString("patientName"),
                        rs.getString("phone"),
                        lastVisit,
                        daysSince,
                        status,
                        "ROUTINE_PROPHYLAXIS_EXAM");
            });
        });
    }

    public List<ClinicAuditModels.BreakGlassComplianceRow> queryBreakGlassCompliance(
            ClinicScope scope, Instant from, Instant to) {
        return scope.read(() -> {
            Instant now = Instant.now();
            MapSqlParameterSource params = scope.bind()
                    .addValue("from", Timestamp.from(from))
                    .addValue("to", Timestamp.from(to))
                    .addValue("now", Timestamp.from(now));

            return jdbc.query("""
                    select bg.id as "grantId",
                           coalesce(u."fullName", u.email) as "requesterName",
                           coalesce(p."displayName", 'ALL_PATIENTS') as "patientName",
                           bg.reason,
                           bg."grantedAt",
                           bg."expiresAt",
                           (bg."revokedAt" is null and bg."expiresAt" > :now) as is_active,
                           bg."reviewStatus",
                           coalesce(ru."fullName", ru.email) as "reviewerName",
                           bg."reviewedAt",
                           bg."reviewNotes"
                      from clinic_break_glass_grants bg
                      join users u on bg."requestedByUserId" = u.id and bg."organizationId" = u."organizationId"
                      left join patients p on bg."patientId" = p.id and bg."organizationId" = p."organizationId"
                      left join users ru on bg."reviewedByUserId" = ru.id and bg."organizationId" = ru."organizationId"
                     where %s
                       and bg."grantedAt" >= :from and bg."grantedAt" <= :to
                     order by bg."grantedAt" desc
                    """.formatted(scope.predicate("bg")), params, (rs, rowNum) -> new ClinicAuditModels.BreakGlassComplianceRow(
                    (UUID) rs.getObject("grantId"),
                    rs.getString("requesterName"),
                    rs.getString("patientName"),
                    rs.getString("reason"),
                    rs.getTimestamp("grantedAt").toInstant(),
                    rs.getTimestamp("expiresAt").toInstant(),
                    rs.getBoolean("is_active"),
                    rs.getString("reviewStatus"),
                    rs.getString("reviewerName"),
                    rs.getTimestamp("reviewedAt") != null ? rs.getTimestamp("reviewedAt").toInstant() : null,
                    rs.getString("reviewNotes")));
        });
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private ClinicAuditModels.BreakGlassGrantRow mapBreakGlassRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp grantedAt = rs.getTimestamp("grantedAt");
        Timestamp expiresAt = rs.getTimestamp("expiresAt");
        Timestamp revokedAt = rs.getTimestamp("revokedAt");
        Timestamp reviewedAt = rs.getTimestamp("reviewedAt");

        return new ClinicAuditModels.BreakGlassGrantRow(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("organizationId"),
                (UUID) rs.getObject("projectId"),
                (UUID) rs.getObject("patientId"),
                (UUID) rs.getObject("requestedByUserId"),
                rs.getString("reason"),
                grantedAt != null ? grantedAt.toInstant() : null,
                expiresAt != null ? expiresAt.toInstant() : null,
                revokedAt != null ? revokedAt.toInstant() : null,
                (UUID) rs.getObject("revokedByUserId"),
                rs.getString("revocationReason"),
                rs.getString("reviewStatus"),
                (UUID) rs.getObject("reviewedByUserId"),
                reviewedAt != null ? reviewedAt.toInstant() : null,
                rs.getString("reviewNotes"));
    }

    private ClinicAuditModels.ClinicAuditRow mapAuditRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp occurredAt = rs.getTimestamp("occurredAt");
        return new ClinicAuditModels.ClinicAuditRow(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("organizationId"),
                (UUID) rs.getObject("projectId"),
                (UUID) rs.getObject("patientId"),
                (UUID) rs.getObject("actorUserId"),
                rs.getString("actorUserName"),
                rs.getString("actorRole"),
                rs.getString("action"),
                rs.getString("resourceType"),
                rs.getString("resourceId"),
                (UUID) rs.getObject("breakGlassGrantId"),
                rs.getString("ipAddress"),
                rs.getString("userAgent"),
                rs.getString("metadataJson"),
                rs.getString("tamperHash"),
                occurredAt != null ? occurredAt.toInstant() : null);
    }
}
