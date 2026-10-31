package com.firstelement.exports.persistence;

import com.firstelement.crm.persistence.JsonCodec;
import com.firstelement.security.query.ScopedWhere;
import com.firstelement.security.query.TenantScope;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The append-only history beside {@code export_jobs}: attempts, downloads and object deletions.
 *
 * <h2>Three streams, three audiences, one reason they are separate</h2>
 *
 * <ul>
 *   <li>{@code export_job_attempts} answers "did this job try, and what happened". Append-only:
 *       {@code export_job_attempts_job_attempt_key} is unique per
 *       {@code (organizationId, exportJobId, attemptNumber)}, so two racing workers cannot both record
 *       attempt 2 and the retry count cannot be inflated.</li>
 *   <li>{@code export_download_events} answers "who obtained the bytes, and who was refused". It
 *       records the <em>refusals</em> too, which is the half that matters: a signature that was minted
 *       and then denied is exactly the event a security reviewer wants. It stores the signature
 *       <strong>id</strong> and its expiry and never the URL, because a URL in a database row is a bearer
 *       credential with an expiry nobody manages.</li>
 *   <li>{@code export_deletion_events} answers "was the object really removed from storage". ADR-0009 §4
 *       rule 1 is explicit that a purge which leaves the file behind is worse than no purge, because the
 *       row no longer records that it exists — so the deletion and its byte count are evidence, not
 *       bookkeeping.</li>
 * </ul>
 *
 * <p>None of the three has an {@code updatedAt}: the migration's post-condition guard fails if one ever
 * appears. Correcting an event is appending a correction.
 */
@Repository
public class ExportEventRepository {

    // ------------------------------------------------------------------
    // attempts
    // ------------------------------------------------------------------

    /** The declared {@code export_job_attempts."outcome"} values. */
    public enum AttemptOutcome {
        /** An attempt began. Carries no error and no row count. */
        STARTED,
        /** The attempt produced rows. */
        SUCCEEDED,
        /** The attempt failed. Carries an error code and message, and nothing else may. */
        FAILED,
        /** The attempt observed a cancellation and stopped. */
        CANCELLED,
        /** The attempt was refused before it started (authorization revoked, artefact gone). */
        SKIPPED
    }

    /** One append-only attempt. */
    public record AttemptRow(UUID id,
                             UUID exportJobId,
                             int attemptNumber,
                             String outcome,
                             String workerId,
                             LocalDateTime startedAt,
                             LocalDateTime finishedAt,
                             Integer durationMs,
                             Long rowsProduced,
                             String errorCode,
                             String correlationId) {
    }

    private static final String INSERT_ATTEMPT = """
        insert into export_job_attempts ("id", "organizationId", "projectId", "exportJobId",
            "attemptNumber", "outcome", "workerId", "startedAt", "finishedAt", "durationMs",
            "rowsProduced", "errorCode", "errorMessage", "correlationId", "createdAt")
        values (:id, :__fe_tenant_organization_id, :projectId, :exportJobId, :attemptNumber, :outcome,
            :workerId, :startedAt, :finishedAt, :durationMs, :rowsProduced, :errorCode, :errorMessage,
            :correlationId, :startedAt)
        """;

    private static final String LIST_ATTEMPTS = """
        select a."id", a."exportJobId", a."attemptNumber", a."outcome", a."workerId", a."startedAt",
               a."finishedAt", a."durationMs", a."rowsProduced", a."errorCode", a."correlationId"
          from export_job_attempts a
         where a."organizationId" = :__fe_tenant_organization_id
           and a."exportJobId" = :jobId
         order by a."attemptNumber"
         limit :limit
        """;

    /**
     * Records one attempt.
     *
     * @param durationMs the duration, which the schema pairs biconditionally with {@code finishedAt}:
     *                    {@code export_job_attempts_finished_check} is
     *                    {@code (finishedAt IS NULL) = (durationMs IS NULL)}, so a STARTED attempt
     *                    passes both nulls and a finished attempt passes both values.
     * @return false when this attempt number already exists, which is a duplicate suppression win rather
     *         than an error
     */
    public boolean recordAttempt(TenantScope scope,
                                 UUID projectId,
                                 UUID exportJobId,
                                 int attemptNumber,
                                 AttemptOutcome outcome,
                                 String workerId,
                                 LocalDateTime startedAt,
                                 LocalDateTime finishedAt,
                                 Integer durationMs,
                                 Long rowsProduced,
                                 String errorCode,
                                 String errorMessage,
                                 String correlationId) {
        // A FAILED attempt must carry a code AND a message; a non-FAILED attempt must carry neither.
// The message is normalised rather than required, because a Java throwable is allowed to have a null
// message (a bare `NullPointerException`, for one) and losing the whole failure record over a missing
// sentence is the wrong trade: the code, the attempt row and the job's own FAILED state are the evidence.
// Before this, a null-messaged failure raised IllegalArgumentException from inside the catch that was
// recording it, so the original fault was replaced by a second one and the job stayed RUNNING forever.
String normalizedMessage = errorMessage == null || errorMessage.isBlank()
            ? (errorCode == null ? "no further detail" : errorCode)
            : errorMessage;
        if ((outcome == AttemptOutcome.FAILED) != (errorCode != null)) {
            throw new IllegalArgumentException(
                "Only a FAILED attempt may carry an error, and a FAILED attempt must: outcome="
                    + outcome + " code=" + errorCode);
        }
        if (outcome != AttemptOutcome.FAILED) {
            errorCode = null;
            errorMessage = null;
        } else {
            errorMessage = normalizedMessage;
        }
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of())
            .addValue("id", UUID.randomUUID())
            .addValue("projectId", projectId)
            .addValue("exportJobId", exportJobId)
            .addValue("attemptNumber", attemptNumber)
            .addValue("outcome", outcome.name())
            .addValue("workerId", workerId)
            .addValue("startedAt", startedAt)
            .addValue("finishedAt", finishedAt)
            .addValue("durationMs", durationMs)
            .addValue("rowsProduced", rowsProduced)
            .addValue("errorCode", errorCode)
            .addValue("errorMessage", errorMessage)
            .addValue("correlationId", correlationId);
        try {
            return jdbc.update(INSERT_ATTEMPT, parameters) == 1;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // The unique index did its job: two workers did not both record attempt N.
            return false;
        }
    }

    /** The retry history of one export. */
    public List<AttemptRow> attempts(TenantScope scope, UUID jobId, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of(
            "jobId", jobId,
            "limit", Math.max(1, limit)));
        return jdbc.query(LIST_ATTEMPTS, parameters, (rs, rowNum) -> new AttemptRow(
            rs.getObject("id", UUID.class),
            rs.getObject("exportJobId", UUID.class),
            rs.getInt("attemptNumber"),
            rs.getString("outcome"),
            rs.getString("workerId"),
            rs.getObject("startedAt", LocalDateTime.class),
            rs.getObject("finishedAt", LocalDateTime.class),
            rs.getObject("durationMs", Integer.class),
            rs.getObject("rowsProduced", Long.class),
            rs.getString("errorCode"),
            rs.getString("correlationId")));
    }

    // ------------------------------------------------------------------
    // downloads
    // ------------------------------------------------------------------

    /** The declared {@code export_download_events."outcome"} values. */
    public enum DownloadOutcome {
        /** A signature was minted for a redemption. Carries the signature id and its expiry. */
        GRANTED,
        /** The redemption was refused. Carries a reason and nothing else. */
        DENIED,
        /** The artefact's window had closed. */
        EXPIRED,
        /** The artefact had been deleted. */
        DELETED
    }

    /** One download attempt, granted or refused. */
    public record DownloadRow(UUID id,
                              UUID exportJobId,
                              UUID actorUserId,
                              String principalName,
                              String outcome,
                              String signatureId,
                              LocalDateTime signatureExpiresAt,
                              String checksumAtDownload,
                              String redactionPolicyApplied,
                              String denyReason,
                              String ipAddress,
                              LocalDateTime occurredAt,
                              String correlationId) {
    }

    private static final String INSERT_DOWNLOAD = """
        insert into export_download_events ("id", "organizationId", "projectId", "exportJobId",
            "actorUserId", "principalName", "outcome", "signatureId", "signatureExpiresAt",
            "exportExpiresAt", "checksumAtDownload", "redactionPolicyApplied", "denyReason",
            "ipAddress", "userAgent", "correlationId", "occurredAt", "createdAt")
        values (:id, :__fe_tenant_organization_id, :projectId, :exportJobId, :actorUserId,
            :principalName, cast(:outcome as "ExportDownloadOutcome"), :signatureId,
            :signatureExpiresAt, :exportExpiresAt, :checksumAtDownload, :redactionPolicyApplied,
            :denyReason, :ipAddress, :userAgent, :correlationId, :occurredAt, :occurredAt)
        """;

    private static final String LIST_DOWNLOADS = """
        select d."id", d."exportJobId", d."actorUserId", d."principalName", d."outcome"::text as "outcome",
               d."signatureId", d."signatureExpiresAt", d."checksumAtDownload",
               d."redactionPolicyApplied", d."denyReason", d."ipAddress", d."occurredAt",
               d."correlationId"
          from export_download_events d
         where d."organizationId" = :__fe_tenant_organization_id
           and (cast(:jobId as uuid) is null or d."exportJobId" = cast(:jobId as uuid))
         order by d."occurredAt" desc, d."id" desc
         limit :limit
        """;

    /**
     * Records one download attempt.
     *
     * <p>The schema's biconditionals are checked here rather than discovered:
     * {@code export_download_events_signature_check} ties GRANTED to a signature id and expiry and says
     * nothing else may carry one (so a refusal cannot be recorded after the fact with a live signature on
     * it), and {@code export_download_events_deny_reason_check} ties DENIED to a reason.
     */
    public void recordDownload(TenantScope scope,
                               UUID projectId,
                               UUID exportJobId,
                               UUID actorUserId,
                               String principalName,
                               DownloadOutcome outcome,
                               String signatureId,
                               LocalDateTime signatureExpiresAt,
                               LocalDateTime exportExpiresAt,
                               String checksumAtDownload,
                               String redactionPolicyApplied,
                               String denyReason,
                               String ipAddress,
                               String userAgent,
                               String correlationId,
                               LocalDateTime occurredAt) {
        if ((outcome == DownloadOutcome.GRANTED) != (signatureId != null && signatureExpiresAt != null)) {
            throw new IllegalArgumentException(
                "A GRANTED download must carry a signature id and expiry, and nothing else may. outcome="
                    + outcome + " signatureId=" + (signatureId == null ? "null" : "present"));
        }
        if ((outcome == DownloadOutcome.DENIED) != (denyReason != null && !denyReason.isBlank())) {
            throw new IllegalArgumentException(
                "A refused download must say why, and only a refusal may. outcome=" + outcome);
        }
        if (principalName == null || principalName.isBlank()) {
            throw new IllegalArgumentException(
                "A download event must name a principal. The actor user column is nullable because a "
                    + "machine principal has no user id, but 'we forgot' must not be a representable state.");
        }
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of())
            .addValue("id", UUID.randomUUID())
            .addValue("projectId", projectId)
            .addValue("exportJobId", exportJobId)
            .addValue("actorUserId", actorUserId)
            .addValue("principalName", principalName)
            .addValue("outcome", outcome.name())
            .addValue("signatureId", signatureId)
            .addValue("signatureExpiresAt", signatureExpiresAt)
            .addValue("exportExpiresAt", exportExpiresAt)
            .addValue("checksumAtDownload", checksumAtDownload)
            .addValue("redactionPolicyApplied", redactionPolicyApplied)
            .addValue("denyReason", denyReason)
            .addValue("ipAddress", ipAddress)
            .addValue("userAgent", userAgent)
            .addValue("correlationId", correlationId)
            .addValue("occurredAt", occurredAt);
        jdbc.update(INSERT_DOWNLOAD, parameters);
    }

    /** Download attempts for a job, or across the tenant when {@code jobId} is null. */
    public List<DownloadRow> downloads(TenantScope scope, UUID jobId, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("limit", Math.max(1, limit)));
        parameters.addValue("jobId", jobId, java.sql.Types.OTHER);
        return jdbc.query(LIST_DOWNLOADS, parameters, (rs, rowNum) -> new DownloadRow(
            rs.getObject("id", UUID.class),
            rs.getObject("exportJobId", UUID.class),
            rs.getObject("actorUserId", UUID.class),
            rs.getString("principalName"),
            rs.getString("outcome"),
            rs.getString("signatureId"),
            rs.getObject("signatureExpiresAt", LocalDateTime.class),
            rs.getString("checksumAtDownload"),
            rs.getString("redactionPolicyApplied"),
            rs.getString("denyReason"),
            rs.getString("ipAddress"),
            rs.getObject("occurredAt", LocalDateTime.class),
            rs.getString("correlationId")));
    }

    // ------------------------------------------------------------------
    // deletions
    // ------------------------------------------------------------------

    /** The declared {@code export_deletion_events."outcome"} values. */
    public enum DeletionOutcome {
        /** The object was removed from storage. The only outcome that reclaims anything. */
        STORAGE_OBJECT_DELETED,
        /** The storage delete failed. Carries a code and a message; the row keeps its key. */
        STORAGE_DELETE_FAILED,
        /** The job row was tombstoned. Records that the row was not removed. */
        ROW_TOMBSTONED
    }

    /** One object-storage deletion. */
    public record DeletionRow(UUID id,
                              UUID exportJobId,
                              String outcome,
                              String storageKeyAtDelete,
                              Long bytesReclaimed,
                              UUID requestedByUserId,
                              String failureCode,
                              String failureMessage,
                              boolean legalHoldChecked,
                              String legalHoldId,
                              LocalDateTime occurredAt,
                              String correlationId) {
    }

    private static final String INSERT_DELETION = """
        insert into export_deletion_events ("id", "organizationId", "projectId", "exportJobId",
            "outcome", "storageKeyAtDelete", "bytesReclaimed", "requestedByUserId", "failureCode",
            "failureMessage", "legalHoldChecked", "legalHoldId", "correlationId", "occurredAt",
            "createdAt")
        values (:id, :__fe_tenant_organization_id, :projectId, :exportJobId,
            cast(:outcome as "ExportDeletionOutcome"), :storageKeyAtDelete, :bytesReclaimed,
            :requestedByUserId, :failureCode, :failureMessage, :legalHoldChecked, :legalHoldId,
            :correlationId, :occurredAt, :occurredAt)
        """;

    private static final String LIST_DELETIONS = """
        select e."id", e."exportJobId", e."outcome"::text as "outcome", e."storageKeyAtDelete",
               e."bytesReclaimed", e."requestedByUserId", e."failureCode", e."failureMessage",
               e."legalHoldChecked", e."legalHoldId", e."occurredAt", e."correlationId"
          from export_deletion_events e
         where e."organizationId" = :__fe_tenant_organization_id
           and (cast(:jobId as uuid) is null or e."exportJobId" = cast(:jobId as uuid))
         order by e."occurredAt" desc, e."id" desc
         limit :limit
        """;

    /**
     * Records one object deletion, with the legal-hold answer that was asked for.
     *
     * <p>{@code legalHoldChecked} is ADR-0008 rule 5's evidence column: "a deletion event with
     * legalHoldChecked false is a deletion that skipped the question". It is written on every event,
     * including the refusals, so an operator can see which deletions asked.
     */
    public void recordDeletion(TenantScope scope,
                               UUID projectId,
                               UUID exportJobId,
                               DeletionOutcome outcome,
                               String storageKeyAtDelete,
                               Long bytesReclaimed,
                               UUID requestedByUserId,
                               String failureCode,
                               String failureMessage,
                               boolean legalHoldChecked,
                               String legalHoldId,
                               String correlationId,
                               LocalDateTime occurredAt) {
        if ((outcome == DeletionOutcome.STORAGE_DELETE_FAILED)
            != (failureCode != null && failureMessage != null)) {
            throw new IllegalArgumentException(
                "Only a failed storage deletion may carry a failure code and message, and it must. "
                    + "outcome=" + outcome);
        }
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of())
            .addValue("id", UUID.randomUUID())
            .addValue("projectId", projectId)
            .addValue("exportJobId", exportJobId)
            .addValue("outcome", outcome.name())
            .addValue("storageKeyAtDelete", storageKeyAtDelete)
            .addValue("bytesReclaimed", bytesReclaimed)
            .addValue("requestedByUserId", requestedByUserId)
            .addValue("failureCode", failureCode)
            .addValue("failureMessage", failureMessage)
            .addValue("legalHoldChecked", legalHoldChecked)
            .addValue("legalHoldId", legalHoldId)
            .addValue("correlationId", correlationId)
            .addValue("occurredAt", occurredAt);
        jdbc.update(INSERT_DELETION, parameters);
    }

    /** Object deletions for a job, or across the tenant when {@code jobId} is null. */
    public List<DeletionRow> deletions(TenantScope scope, UUID jobId, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("limit", Math.max(1, limit)));
        parameters.addValue("jobId", jobId, java.sql.Types.OTHER);
        return jdbc.query(LIST_DELETIONS, parameters, (rs, rowNum) -> new DeletionRow(
            rs.getObject("id", UUID.class),
            rs.getObject("exportJobId", UUID.class),
            rs.getString("outcome"),
            rs.getString("storageKeyAtDelete"),
            rs.getObject("bytesReclaimed", Long.class),
            rs.getObject("requestedByUserId", UUID.class),
            rs.getString("failureCode"),
            rs.getString("failureMessage"),
            rs.getBoolean("legalHoldChecked"),
            rs.getString("legalHoldId"),
            rs.getObject("occurredAt", LocalDateTime.class),
            rs.getString("correlationId")));
    }

    private final NamedParameterJdbcTemplate jdbc;

    public ExportEventRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }
}