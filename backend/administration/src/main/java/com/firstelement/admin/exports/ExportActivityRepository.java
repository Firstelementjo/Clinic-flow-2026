package com.firstelement.admin.exports;

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
 * Export activity from {@code V20261005070000}: the jobs, their attempts, every download attempt
 * including the refusals, and every object-storage deletion.
 *
 * <h2>What an operator is diagnosing here</h2>
 *
 * <p>"A large export must never run on a request thread, and every export must be reproducible,
 * re-authorised at job start AND at download, redacted, checksummed, expiring, and
 * download-audited." Those are seven claims, and each is a column or a table the operator can read:
 *
 * <ul>
 *   <li><b>asynchronous</b> — {@code status} is a seven-state machine, not a boolean;</li>
 *   <li><b>reproducible</b> — {@code reportDefinitionId} with {@code reportVersion}, pinned as a
 *       biconditional, so "the one we produced" is answerable;</li>
 *   <li><b>re-authorised</b> — {@code export_download_events} records every attempt with its outcome,
 *       including {@code DENIED} with a reason;</li>
 *   <li><b>redacted</b> — {@code redactionPolicy} on the job and {@code redactionPolicyApplied} on
 *       each download;</li>
 *   <li><b>checksummed</b> — {@code checksum} in {@code ^sha256:[0-9a-f]{64}$} form, checked by the
 *       schema, so it can actually be compared;</li>
 *   <li><b>expiring</b> — {@code expiresAt} is NOT NULL, because an export with no expiry is a
 *       permanent copy of patient data in object storage;</li>
 *   <li><b>download-audited</b> — the event table, which is {@code ON DELETE RESTRICT} precisely so
 *       it cannot vanish with the job.</li>
 * </ul>
 *
 * <h2>What is never selected</h2>
 *
 * <p>{@code filters} and {@code authorizationSnapshot} are customer-shaped: a filter can name a
 * customer's date of birth, and a snapshot is a record of which permissions a person had on the day.
 * Neither is needed to answer "did this export work", so neither is in this file. The checksums, the
 * redaction policy, the state machine and the download outcomes are.
 */
@Repository
public class ExportActivityRepository {

    private static final String JOBS = """
        select j."id", j."organizationId", j."projectId", j."requestedByUserId", j."reportDefinitionId",
               j."reportVersion", j."documentTemplateId", j."documentTemplateVersion",
               j."format"::text as "format", j."status"::text as "status", j."redactionPolicy",
               j."retentionClass", j."progressPct", j."attemptCount", j."lastAttemptAt",
               j."nextAttemptAt", j."startedAt", j."finishedAt", j."rowCount", j."checksum",
               j."storageKey" is not null as "hasObject", j."bytes", j."errorMessage",
               j."cancelledAt", j."cancelledByUserId", j."cancelReason", j."expiredAt",
               j."deletedAt", j."deletedByUserId", j."expiresAt", j."requestedAt", j."correlationId",
               u."email" as "requestedByEmail"
          from export_jobs j
          join users u on u."id" = j."requestedByUserId"
                      and u."organizationId" = j."organizationId"
         where j."organizationId" = :__fe_tenant_organization_id
           and (cast(:filterProjectId as uuid) is null or j."projectId" = cast(:filterProjectId as uuid))
           and (cast(:filterStatus as text) is null
                or j."status"::text = cast(:filterStatus as text))
         order by j."requestedAt" desc, j."id" desc
         limit :limit
        """;

    private static final String ATTEMPTS = """
        select a."id", a."exportJobId", a."attemptNumber", a."outcome", a."workerId", a."startedAt",
               a."finishedAt", a."durationMs", a."rowsProduced", a."errorCode", a."correlationId"
          from export_job_attempts a
         where a."organizationId" = :__fe_tenant_organization_id
           and a."exportJobId" = :jobId
         order by a."attemptNumber"
         limit :limit
        """;

    /**
     * Download attempts, including the refusals.
     *
     * <p>The signature <em>id</em> is selected and the signed URL is not, because the migration says
     * so: the URL is a bearer credential with an expiry nobody manages, so the row records the
     * signature id and its own expiry and never the URL.
     */
    private static final String DOWNLOADS = """
        select d."id", d."exportJobId", d."actorUserId", d."principalName", d."outcome"::text as "outcome",
               d."signatureId", d."signatureExpiresAt", d."checksumAtDownload",
               d."redactionPolicyApplied", d."denyReason", d."ipAddress", d."occurredAt",
               d."correlationId"
          from export_download_events d
         where d."organizationId" = :__fe_tenant_organization_id
           and (cast(:filterProjectId as uuid) is null or d."projectId" = cast(:filterProjectId as uuid))
           and (cast(:filterJobId as uuid) is null or d."exportJobId" = cast(:filterJobId as uuid))
         order by d."occurredAt" desc, d."id" desc
         limit :limit
        """;

    /**
     * Object-storage deletions.
     *
     * <p>{@code legalHoldChecked} is the ADR-0008 rule 5 evidence column: "a deletion event with
     * legalHoldChecked false is a deletion that skipped the question", and it is selected so an
     * operator can see which deletions asked and which did not.
     */
    private static final String DELETIONS = """
        select e."id", e."exportJobId", e."outcome"::text as "outcome", e."storageKeyAtDelete",
               e."bytesReclaimed", e."requestedByUserId", e."failureCode", e."failureMessage",
               e."legalHoldChecked", e."legalHoldId", e."occurredAt", e."correlationId"
          from export_deletion_events e
         where e."organizationId" = :__fe_tenant_organization_id
           and (cast(:filterJobId as uuid) is null or e."exportJobId" = cast(:filterJobId as uuid))
         order by e."occurredAt" desc, e."id" desc
         limit :limit
        """;

    /** Export tallies by status. Every state the enum declares is present, including at zero. */
    private static final String COUNTS_BY_STATUS = """
        select j."status"::text as status, count(*) as occurrences
          from export_jobs j
         where j."organizationId" = :__fe_tenant_organization_id
         group by j."status"
         order by j."status"
        """;

    /** Exports whose object has outlived its window and is still on disk. */
    private static final String EXPIRED_BUT_PRESENT = """
        select count(*)
          from export_jobs j
         where j."organizationId" = :__fe_tenant_organization_id
           and j."status" = cast('COMPLETED' as "ExportJobStatus")
           and j."expiresAt" <= (now() AT TIME ZONE 'UTC')
           and j."storageKey" is not null
        """;

    /** Downloads refused, and how many. */
    private static final String DENIED_DOWNLOADS = """
        select d."denyReason" as reason, count(*) as occurrences, max(d."occurredAt") as last_seen
          from export_download_events d
         where d."organizationId" = :__fe_tenant_organization_id
           and d."outcome" = cast('DENIED' as "ExportDownloadOutcome")
         group by d."denyReason"
         order by count(*) desc
         limit :limit
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public ExportActivityRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One export job.
     *
     * @param hasObject whether the produced object is still in storage. The key itself is not
     *                   returned: it is opaque by schema, but it is still a locator for patient data
     *                   and an Admin list has no reason to carry it
     * @param checksum   {@code sha256:<64 hex>}, comparable against a re-run
     */
    public record ExportJobRow(UUID id,
                               UUID organizationId,
                               UUID projectId,
                               UUID requestedByUserId,
                               String requestedByEmail,
                               UUID reportDefinitionId,
                               Integer reportVersion,
                               UUID documentTemplateId,
                               Integer documentTemplateVersion,
                               String format,
                               String status,
                               String redactionPolicy,
                               String retentionClass,
                               int progressPct,
                               int attemptCount,
                               LocalDateTime lastAttemptAt,
                               LocalDateTime nextAttemptAt,
                               LocalDateTime startedAt,
                               LocalDateTime finishedAt,
                               Long rowCount,
                               String checksum,
                               boolean hasObject,
                               Long bytes,
                               String errorMessage,
                               LocalDateTime cancelledAt,
                               UUID cancelledByUserId,
                               String cancelReason,
                               LocalDateTime expiredAt,
                               LocalDateTime deletedAt,
                               UUID deletedByUserId,
                               LocalDateTime expiresAt,
                               LocalDateTime requestedAt,
                               String correlationId) {
    }

    /** One append-only attempt. */
    public record ExportAttemptRow(UUID id,
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

    /** One download attempt, granted or refused. */
    public record ExportDownloadRow(UUID id,
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

    /** One object-storage deletion. */
    public record ExportDeletionRow(UUID id,
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

    /** The tenant's export jobs, newest first. */
    public List<ExportJobRow> jobs(TenantScope scope, UUID projectId, String status, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("limit", Math.max(1, limit)));
        parameters.addValue("filterProjectId", projectId);
        parameters.addValue("filterStatus", status);
        return jdbc.query(JOBS, parameters, (rs, rowNum) -> new ExportJobRow(
            rs.getObject("id", UUID.class),
            rs.getObject("organizationId", UUID.class),
            rs.getObject("projectId", UUID.class),
            rs.getObject("requestedByUserId", UUID.class),
            rs.getString("requestedByEmail"),
            rs.getObject("reportDefinitionId", UUID.class),
            rs.getObject("reportVersion", Integer.class),
            rs.getObject("documentTemplateId", UUID.class),
            rs.getObject("documentTemplateVersion", Integer.class),
            rs.getString("format"),
            rs.getString("status"),
            rs.getString("redactionPolicy"),
            rs.getString("retentionClass"),
            rs.getInt("progressPct"),
            rs.getInt("attemptCount"),
            rs.getObject("lastAttemptAt", LocalDateTime.class),
            rs.getObject("nextAttemptAt", LocalDateTime.class),
            rs.getObject("startedAt", LocalDateTime.class),
            rs.getObject("finishedAt", LocalDateTime.class),
            rs.getObject("rowCount", Long.class),
            rs.getString("checksum"),
            rs.getBoolean("hasObject"),
            rs.getObject("bytes", Long.class),
            rs.getString("errorMessage"),
            rs.getObject("cancelledAt", LocalDateTime.class),
            rs.getObject("cancelledByUserId", UUID.class),
            rs.getString("cancelReason"),
            rs.getObject("expiredAt", LocalDateTime.class),
            rs.getObject("deletedAt", LocalDateTime.class),
            rs.getObject("deletedByUserId", UUID.class),
            rs.getObject("expiresAt", LocalDateTime.class),
            rs.getObject("requestedAt", LocalDateTime.class),
            rs.getString("correlationId")));
    }

    /** The retry history of one export. */
    public List<ExportAttemptRow> attempts(TenantScope scope, UUID jobId, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("jobId", jobId, "limit", Math.max(1, limit)));
        return jdbc.query(ATTEMPTS, parameters, (rs, rowNum) -> new ExportAttemptRow(
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

    /** Download attempts. */
    public List<ExportDownloadRow> downloads(TenantScope scope, UUID projectId, UUID jobId,
                                              int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("limit", Math.max(1, limit)));
        parameters.addValue("filterProjectId", projectId);
        parameters.addValue("filterJobId", jobId);
        return jdbc.query(DOWNLOADS, parameters, (rs, rowNum) -> new ExportDownloadRow(
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

    /** Object-storage deletions. */
    public List<ExportDeletionRow> deletions(TenantScope scope, UUID jobId, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("limit", Math.max(1, limit)));
        parameters.addValue("filterJobId", jobId);
        return jdbc.query(DELETIONS, parameters, (rs, rowNum) -> new ExportDeletionRow(
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

    /** Counts by state. Every declared state is present, including at zero. */
    public Map<String, Integer> countsByStatus(TenantScope scope) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of());
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (String state : List.of("QUEUED", "RUNNING", "COMPLETED", "FAILED", "CANCELLED",
            "EXPIRED", "DELETED")) {
            counts.put(state, 0);
        }
        for (Map<String, Object> row : jdbc.queryForList(COUNTS_BY_STATUS, parameters)) {
            counts.put(String.valueOf(row.get("status")),
                ((Number) row.get("occurrences")).intValue());
        }
        return counts;
    }

    /**
     * Completed exports whose object is still in storage past its window.
     *
     * <p>This is the number that makes an unrun expiry sweeper visible: the job row says COMPLETED
     * because it completed, and only this count says its bytes are still there.
     */
    public int expiredButStillPresent(TenantScope scope) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of());
        Integer count = jdbc.queryForObject(EXPIRED_BUT_PRESENT, parameters, Integer.class);
        return count == null ? 0 : count;
    }

    /** Refused downloads, by reason. */
    public List<Map<String, Object>> deniedDownloads(TenantScope scope, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("limit", Math.max(1, limit)));
        return jdbc.queryForList(DENIED_DOWNLOADS, parameters);
    }
}