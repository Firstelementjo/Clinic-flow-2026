package com.firstelement.crm.clinic.media;

import com.firstelement.crm.clinic.ClinicScope;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Scoped repository for dental clinic media, radiographs, documents, and access audit trail (Slice S7).
 * All queries are strictly guarded by {@link ClinicScope} ensuring tenant isolation.
 */
@Repository
public class ClinicMediaRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ClinicMediaRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a new clinical media/attachment record.
     */
    public ClinicMediaModels.MediaRow insertMedia(ClinicScope scope, ClinicMediaModels.MediaRow media) {
        return scope.read(() -> {
            MapSqlParameterSource params = scope.bind()
                    .addValue("id", media.id())
                    .addValue("orgId", media.organizationId())
                    .addValue("projectId", media.projectId())
                    .addValue("patientId", media.patientId())
                    .addValue("fileName", media.fileName())
                    .addValue("contentType", media.contentType())
                    .addValue("byteSize", media.byteSize())
                    .addValue("storageKey", media.storageKey())
                    .addValue("sha256", media.sha256())
                    .addValue("category", media.category())
                    .addValue("toothFdi", media.toothFdi())
                    .addValue("acquisitionDate", Timestamp.from(media.acquisitionDate()))
                    .addValue("radiographerName", media.radiographerName())
                    .addValue("encrypted", media.encrypted())
                    .addValue("encryptionAlgorithm", media.encryptionAlgorithm())
                    .addValue("scanStatus", media.scanStatus())
                    .addValue("retentionClass", media.retentionClass())
                    .addValue("retentionExpiresAt", media.retentionExpiresAt() != null ? Timestamp.from(media.retentionExpiresAt()) : null)
                    .addValue("legalHold", media.legalHold())
                    .addValue("notes", media.notes())
                    .addValue("createdAt", Timestamp.from(media.createdAt()))
                    .addValue("updatedAt", Timestamp.from(media.updatedAt()));

            jdbc.update("""
                    insert into "public"."patient_attachments" (
                        "id", "organizationId", "projectId", "patientId",
                        "fileName", "contentType", "byteSize", "storageKey", "sha256",
                        "category", "toothFdi", "acquisitionDate", "radiographerName",
                        "encrypted", "encryptionAlgorithm", "scanStatus",
                        "retentionClass", "retentionExpiresAt", "legalHold", "notes",
                        "createdAt", "updatedAt"
                    ) values (
                        :id, :orgId, :projectId, :patientId,
                        :fileName, :contentType, :byteSize, :storageKey, :sha256,
                        :category, :toothFdi, :acquisitionDate, :radiographerName,
                        :encrypted, :encryptionAlgorithm, :scanStatus,
                        :retentionClass, :retentionExpiresAt, :legalHold, :notes,
                        :createdAt, :updatedAt
                    )
                    """, params);

            return findById(scope, media.id()).orElseThrow();
        });
    }

    /**
     * Finds media row by ID within tenant scope (ignoring soft-deleted).
     */
    public Optional<ClinicMediaModels.MediaRow> findById(ClinicScope scope, UUID mediaId) {
        return scope.read(() -> {
            MapSqlParameterSource params = scope.bind()
                    .addValue("id", mediaId);

            List<ClinicMediaModels.MediaRow> list = jdbc.query("""
                    select a.*
                      from "public"."patient_attachments" a
                     where\s""" + scope.predicate("a") + """
                       and a."id" = :id
                       and a."deletedAt" is null
                    """, params, (rs, rowNum) -> mapMediaRow(rs));

            return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
        });
    }

    /**
     * Lists all clinical media for a patient, ordered newest first.
     */
    public List<ClinicMediaModels.MediaRow> listByPatient(
            ClinicScope scope,
            UUID patientId,
            String category,
            Integer toothFdi,
            Boolean legalHoldOnly
    ) {
        return scope.read(() -> {
            StringBuilder sql = new StringBuilder("""
                    select a.*
                      from "public"."patient_attachments" a
                     where\s""" + scope.predicate("a") + """
                       and a."patientId" = :patientId
                       and a."deletedAt" is null
                    """);

            MapSqlParameterSource params = scope.bind()
                    .addValue("patientId", patientId);

            if (category != null && !category.isBlank() && !"ALL".equalsIgnoreCase(category)) {
                sql.append(" and a.\"category\" = :category ");
                params.addValue("category", category);
            }

            if (toothFdi != null) {
                sql.append(" and a.\"toothFdi\" = :toothFdi ");
                params.addValue("toothFdi", toothFdi);
            }

            if (Boolean.TRUE.equals(legalHoldOnly)) {
                sql.append(" and a.\"legalHold\" = true ");
            }

            sql.append(" order by a.\"acquisitionDate\" desc, a.\"createdAt\" desc ");

            return jdbc.query(sql.toString(), params, (rs, rowNum) -> mapMediaRow(rs));
        });
    }

    /**
     * Sets or releases legal hold on attachment.
     */
    public boolean updateLegalHold(ClinicScope scope, UUID mediaId, boolean legalHold) {
        return scope.read(() -> {
            MapSqlParameterSource params = scope.bind()
                    .addValue("orgId", scope.queryScope().organizationId())
                    .addValue("id", mediaId)
                    .addValue("legalHold", legalHold)
                    .addValue("now", Timestamp.from(Instant.now()));

            int rows = jdbc.update("""
                    update "public"."patient_attachments"
                       set "legalHold" = :legalHold,
                           "updatedAt" = :now
                     where "organizationId" = :orgId
                       and "id" = :id
                       and "deletedAt" is null
                    """, params);

            return rows > 0;
        });
    }

    /**
     * Soft deletes media attachment (refused if legalHold is active).
     */
    public boolean softDelete(ClinicScope scope, UUID mediaId, UUID deletedBy, String deletionReason) {
        return scope.read(() -> {
            MapSqlParameterSource params = scope.bind()
                    .addValue("orgId", scope.queryScope().organizationId())
                    .addValue("id", mediaId)
                    .addValue("deletedBy", deletedBy)
                    .addValue("deletionReason", deletionReason)
                    .addValue("now", Timestamp.from(Instant.now()));

            int rows = jdbc.update("""
                    update "public"."patient_attachments"
                       set "deletedAt" = :now,
                           "deletedBy" = :deletedBy,
                           "deletionReason" = :deletionReason,
                           "updatedAt" = :now
                     where "organizationId" = :orgId
                       and "id" = :id
                       and "legalHold" = false
                       and "deletedAt" is null
                    """, params);

            return rows > 0;
        });
    }

    /**
     * Tenant-checked direct lookup for signed-token redemption.
     */
    public Optional<ClinicMediaModels.MediaRow> findByIdAndOrg(UUID organizationId, UUID mediaId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("orgId", organizationId)
                .addValue("id", mediaId);

        List<ClinicMediaModels.MediaRow> list = jdbc.query("""
                select a.*
                  from "public"."patient_attachments" a
                 where a."organizationId" = :orgId
                   and a."id" = :id
                   and a."deletedAt" is null
                """, params, (rs, rowNum) -> mapMediaRow(rs));

        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * Records access audit for signed-token download.
     */
    public ClinicMediaModels.MediaAccessAuditRow recordAccessAuditForOrg(
            UUID organizationId,
            UUID projectId,
            UUID patientId,
            UUID attachmentId,
            String action,
            UUID accessedByUserId,
            String accessedByUserName,
            String accessIp,
            String notes
    ) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("orgId", organizationId)
                .addValue("projectId", projectId)
                .addValue("patientId", patientId)
                .addValue("attachmentId", attachmentId)
                .addValue("action", action)
                .addValue("accessedByUserId", accessedByUserId)
                .addValue("accessedByUserName", accessedByUserName)
                .addValue("accessIp", accessIp)
                .addValue("notes", notes)
                .addValue("accessedAt", Timestamp.from(now));

        jdbc.update("""
                insert into "public"."clinic_media_access_audit" (
                    "id", "organizationId", "projectId", "patientId", "attachmentId",
                    "action", "accessedByUserId", "accessedByUserName", "accessIp",
                    "notes", "accessedAt"
                ) values (
                    :id, :orgId, :projectId, :patientId, :attachmentId,
                    :action, :accessedByUserId, :accessedByUserName, :accessIp,
                    :notes, :accessedAt
                )
                """, params);

        return new ClinicMediaModels.MediaAccessAuditRow(
                id, organizationId, projectId, patientId, attachmentId,
                action, accessedByUserId, accessedByUserName, accessIp, notes, now
        );
    }

    /**
     * Records an immutable access audit entry.
     */
    public ClinicMediaModels.MediaAccessAuditRow recordAccessAudit(
            ClinicScope scope,
            UUID projectId,
            UUID patientId,
            UUID attachmentId,
            String action,
            UUID accessedByUserId,
            String accessedByUserName,
            String accessIp,
            String notes
    ) {
        return scope.read(() -> {
            UUID id = UUID.randomUUID();
            Instant now = Instant.now();
            UUID orgId = scope.queryScope().organizationId();

            MapSqlParameterSource params = scope.bind()
                    .addValue("id", id)
                    .addValue("orgId", orgId)
                    .addValue("projectId", projectId)
                    .addValue("patientId", patientId)
                    .addValue("attachmentId", attachmentId)
                    .addValue("action", action)
                    .addValue("accessedByUserId", accessedByUserId)
                    .addValue("accessedByUserName", accessedByUserName)
                    .addValue("accessIp", accessIp)
                    .addValue("notes", notes)
                    .addValue("accessedAt", Timestamp.from(now));

            jdbc.update("""
                    insert into "public"."clinic_media_access_audit" (
                        "id", "organizationId", "projectId", "patientId", "attachmentId",
                        "action", "accessedByUserId", "accessedByUserName", "accessIp",
                        "notes", "accessedAt"
                    ) values (
                        :id, :orgId, :projectId, :patientId, :attachmentId,
                        :action, :accessedByUserId, :accessedByUserName, :accessIp,
                        :notes, :accessedAt
                    )
                    """, params);

            return new ClinicMediaModels.MediaAccessAuditRow(
                    id, orgId, projectId, patientId, attachmentId,
                    action, accessedByUserId, accessedByUserName, accessIp, notes, now
            );
        });
    }

    /**
     * Lists access audit logs for a media attachment.
     */
    public List<ClinicMediaModels.MediaAccessAuditRow> listAccessAudit(ClinicScope scope, UUID attachmentId) {
        return scope.read(() -> {
            MapSqlParameterSource params = scope.bind()
                    .addValue("attachmentId", attachmentId);

            return jdbc.query("""
                    select *
                      from "public"."clinic_media_access_audit" a
                     where\s""" + scope.predicate("a") + """
                       and a."attachmentId" = :attachmentId
                     order by a."accessedAt" desc
                    """, params, (rs, rowNum) -> mapAuditRow(rs));
        });
    }

    /**
     * Lists all access audit logs for a patient's media.
     */
    public List<ClinicMediaModels.MediaAccessAuditRow> listPatientAccessAudit(ClinicScope scope, UUID patientId) {
        return scope.read(() -> {
            MapSqlParameterSource params = scope.bind()
                    .addValue("patientId", patientId);

            return jdbc.query("""
                    select *
                      from "public"."clinic_media_access_audit" a
                     where\s""" + scope.predicate("a") + """
                       and a."patientId" = :patientId
                     order by a."accessedAt" desc
                     limit 100
                    """, params, (rs, rowNum) -> mapAuditRow(rs));
        });
    }

    private ClinicMediaModels.MediaRow mapMediaRow(ResultSet rs) throws SQLException {
        Timestamp acq = rs.getTimestamp("acquisitionDate");
        Timestamp retExp = rs.getTimestamp("retentionExpiresAt");
        Timestamp created = rs.getTimestamp("createdAt");
        Timestamp updated = rs.getTimestamp("updatedAt");
        Timestamp deleted = rs.getTimestamp("deletedAt");
        int tooth = rs.getInt("toothFdi");
        Integer toothFdi = rs.wasNull() ? null : tooth;

        return new ClinicMediaModels.MediaRow(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("organizationId"),
                (UUID) rs.getObject("projectId"),
                (UUID) rs.getObject("patientId"),
                rs.getString("fileName"),
                rs.getString("contentType"),
                rs.getLong("byteSize"),
                rs.getString("storageKey"),
                rs.getString("sha256"),
                rs.getString("category"),
                toothFdi,
                acq != null ? acq.toInstant() : Instant.now(),
                rs.getString("radiographerName"),
                rs.getBoolean("encrypted"),
                rs.getString("encryptionAlgorithm"),
                rs.getString("scanStatus"),
                rs.getString("retentionClass"),
                retExp != null ? retExp.toInstant() : null,
                rs.getBoolean("legalHold"),
                rs.getString("notes"),
                created != null ? created.toInstant() : Instant.now(),
                updated != null ? updated.toInstant() : Instant.now(),
                deleted != null ? deleted.toInstant() : null,
                (UUID) rs.getObject("deletedBy"),
                rs.getString("deletionReason")
        );
    }

    private ClinicMediaModels.MediaAccessAuditRow mapAuditRow(ResultSet rs) throws SQLException {
        Timestamp accessed = rs.getTimestamp("accessedAt");
        return new ClinicMediaModels.MediaAccessAuditRow(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("organizationId"),
                (UUID) rs.getObject("projectId"),
                (UUID) rs.getObject("patientId"),
                (UUID) rs.getObject("attachmentId"),
                rs.getString("action"),
                (UUID) rs.getObject("accessedByUserId"),
                rs.getString("accessedByUserName"),
                rs.getString("accessIp"),
                rs.getString("notes"),
                accessed != null ? accessed.toInstant() : Instant.now()
        );
    }
}
