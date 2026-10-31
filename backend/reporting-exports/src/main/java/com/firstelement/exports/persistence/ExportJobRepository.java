package com.firstelement.exports.persistence;

import com.firstelement.crm.persistence.JsonCodec;
import com.firstelement.exports.ReportingExportsModule;
import com.firstelement.security.query.ScopedWhere;
import com.firstelement.security.query.TenantScope;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The {@code export_jobs} state machine of {@code V20261005070000}.
 *
 * <h2>Every transition is a guarded UPDATE whose guard is the expected state</h2>
 *
 * <p>There is no {@code update ... set status = ? where id = ?}. Every statement carries the state the
 * caller believes the row is in and returns the affected row count. That is the same two-part split
 * Phase 6a used for workflow activation, and for the same reason: a CHECK constraint cannot see the OLD
 * row, so the legality of a transition has to live in the {@code WHERE} clause. An illegal transition
 * then loses on a zero affected-row count rather than on application ordering or on whichever of two
 * threads happened to reach the row first.
 *
 * <h2>The CHECK constraints are a second line of defence, not the first</h2>
 *
 * <p>The schema encodes the state/column pairing — {@code export_jobs_started_check} is a biconditional,
 * {@code export_jobs_output_check} ties COMPLETED to having an object <em>and</em> a checksum
 * <em>and</em> a row count, and {@code export_jobs_deleted_check} refuses a DELETED job that still points
 * at storage. Every statement here satisfies all of them on the way in <em>and</em> reads the state back,
 * so a defect in this file surfaces as a failed transition with a message rather than as a constraint
 * violation discovered in production.
 *
 * <h2>What this repository refuses to put in a list projection</h2>
 *
 * <p>{@code "filters"} and {@code "authorizationSnapshot"} are customer-shaped: a filter can name a date
 * of birth, and a snapshot is a record of which permissions a person held on the day. Neither is needed
 * to answer "did this export work", so the list projection does not carry them.
 * {@link #findDetail} fetches them separately, so a list read can never leak them by accident.
 */
@Repository
public class ExportJobRepository {

    /**
     * The queue this module's job handler drains.
     *
     * <p>Declared in {@code QueueName} before this module existed. The two candidates are
     * {@code DOCUMENT_PROCESSING} (BULK class, 3 attempts, 10 s base) and {@code ANALYTICS_ROLLUP}.
     * Export generation is the production of a stored document from a declared shape, and the retry
     * profile suits it: a template naming an unapproved field fails identically on attempt two and
     * should become a visible FAILED job rather than three minutes of retries.
     *
     * <p>A dedicated {@code EXPORT_RENDER} queue is requested in the Phase-9 report rather than invented
     * here, because {@code QueueName} and {@code QueuePolicyCatalog} are owned by
     * {@code :backend:worker-runtime}.
     */
    public static final String QUEUE_WIRE_VALUE = "document-processing";

    /**
     * One export job.
     *
     * <p>The emitted column list is NOT a component of this record. It lives on {@link ExportJobDetail},
     * fetched separately, because the list projection must not carry the customer-shaped {@code filters}
     * and {@code authorizationSnapshot} — and one statement selecting all three for both purposes is one
     * statement a future edit could leak them through.
     */
    public record ExportJobRow(UUID id,
                               UUID organizationId,
                               UUID projectId,
                               UUID requestedByUserId,
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
                               String storageKey,
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
                               String correlationId,
                               int version) {
    }

    /** The customer-shaped detail, fetched only for a caller entitled to the export's own details. */
    public record ExportJobDetail(ExportJobRow job,
                                  Map<String, Object> filters,
                                  List<String> columns,
                                  Map<String, Object> authorizationSnapshot) {
    }

    private static final String COLUMNS = """
        j."id", j."organizationId", j."projectId", j."requestedByUserId", j."reportDefinitionId",
        j."reportVersion", j."documentTemplateId", j."documentTemplateVersion",
        j."format"::text as "format", j."status"::text as "status", j."redactionPolicy",
        j."retentionClass", j."progressPct", j."attemptCount", j."lastAttemptAt", j."nextAttemptAt",
        j."startedAt", j."finishedAt", j."rowCount", j."checksum", j."storageKey", j."bytes",
        j."errorMessage", j."cancelledAt", j."cancelledByUserId", j."cancelReason", j."expiredAt",
        j."deletedAt", j."deletedByUserId", j."expiresAt", j."requestedAt", j."correlationId",
        j."version"
        """;

    /**
     * {@code "select "} is an ordinary string literal rather than the tail of a text block.
     *
     * <p>A text block strips the trailing whitespace of every line, so writing {@code select """ + COLUMNS}
     * produces {@code selectj."id"} — valid enough to compile, invalid enough to fail at run time, and only
     * on the statement. That is the whole reason these statements are exercised against a real database in
     * the integration suite: {@code compileJava} cannot see a missing space.
     */
    private static final String FIND_ANY_PROJECT = "select " + COLUMNS + """
          from export_jobs j
         where j."organizationId" = :__fe_tenant_organization_id
           and j."id" = :id
        """;

    private static final String FIND_DETAIL = """
        select j."filters"::text as "filtersJson", j."columns"::text as "columnsJson",
               j."authorizationSnapshot"::text as "snapshotJson"
          from export_jobs j
         where j."organizationId" = :__fe_tenant_organization_id
           and j."id" = :id
        """;

    private static final String LIST_BY_PROJECT = "select " + COLUMNS + """
          from export_jobs j
         where j."organizationId" = :__fe_tenant_organization_id
           and (cast(:projectId as uuid) is null or j."projectId" = cast(:projectId as uuid))
           and (cast(:status as text) is null or j."status"::text = cast(:status as text))
         order by j."requestedAt" desc, j."id" desc
         limit :limit
        """;

    /** The "my exports" list. An employee sees their own jobs and nothing else. */
    private static final String LIST_BY_REQUESTER = "select " + COLUMNS + """
          from export_jobs j
         where j."organizationId" = :__fe_tenant_organization_id
           and j."requestedByUserId" = :requesterId
         order by j."requestedAt" desc, j."id" desc
         limit :limit
        """;

    /** Completed jobs past their window. The expiry sweep's bounded input. */
    private static final String EXPIRABLE = "select " + COLUMNS + """
          from export_jobs j
         where j."organizationId" = :__fe_tenant_organization_id
           and (cast(:projectId as uuid) is null or j."projectId" = cast(:projectId as uuid))
           and j."status" = cast('COMPLETED' as "ExportJobStatus")
           and j."expiresAt" <= :now
         order by j."expiresAt"
         limit :limit
        """;

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonCodec json;

    public ExportJobRepository(NamedParameterJdbcTemplate jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ------------------------------------------------------------------
    // writes
    // ------------------------------------------------------------------

    /**
     * Writes the job row, before any generation work exists.
     *
     * <p>"Persist first, send second" (Phase-9 hard rule 1). The row lands {@code QUEUED} with no start
     * time, no checksum and no object, which is what the schema's biconditionals require of a queued job,
     * and it is the row the outbox intent's {@code idempotencyKey} names — so a retried request cannot
     * produce two jobs.
     *
     * @param snapshot the authorization evidence. Must be a non-empty map; the schema refuses an empty
     *                 one because "an empty snapshot that looks recorded is worse than an absent one".
     * @param columns  the emitted column keys, pinned so a re-run is byte-comparable
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public UUID insert(TenantScope scope,
                       UUID projectId,
                       UUID requestedByUserId,
                       UUID reportDefinitionId,
                       Integer reportVersion,
                       UUID documentTemplateId,
                       Integer documentTemplateVersion,
                       String format,
                       Map<String, Object> filters,
                       List<String> columns,
                       String redactionPolicy,
                       String retentionClass,
                       Map<String, Object> snapshot,
                       LocalDateTime expiresAt,
                       LocalDateTime requestedAt,
                       String correlationId) {
        if (snapshot == null || snapshot.isEmpty()) {
            throw new IllegalArgumentException(
                "An export job must carry a non-empty authorizationSnapshot. The schema's CHECK "
                    + "refuses an empty one for the same reason: a snapshot that looks recorded but "
                    + "records nothing is worse than no snapshot.");
        }
        UUID id = UUID.randomUUID();
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of())
            .addValue("id", id)
            .addValue("projectId", projectId)
            .addValue("requestedByUserId", requestedByUserId)
            .addValue("reportDefinitionId", reportDefinitionId)
            .addValue("reportVersion", reportVersion)
            .addValue("documentTemplateId", documentTemplateId)
            .addValue("documentTemplateVersion", documentTemplateVersion)
            .addValue("format", format)
            .addValue("filtersJson", json.write(filters == null ? Map.of() : filters),
                java.sql.Types.OTHER)
            .addValue("columnsJson", json.write(columns == null ? List.of() : columns),
                java.sql.Types.OTHER)
            .addValue("redactionPolicy", redactionPolicy)
            .addValue("retentionClass", retentionClass)
            .addValue("snapshotJson", json.write(snapshot), java.sql.Types.OTHER)
            .addValue("expiresAt", expiresAt)
            .addValue("requestedAt", requestedAt)
            .addValue("correlationId", correlationId)
            .addValue("now", requestedAt);
        jdbc.update("""
            insert into export_jobs ("id", "organizationId", "projectId", "requestedByUserId",
                "reportDefinitionId", "reportVersion", "documentTemplateId", "documentTemplateVersion",
                "format", "status", "filters", "columns", "redactionPolicy", "retentionClass",
                "authorizationSnapshot", "progressPct", "attemptCount", "expiresAt", "requestedAt",
                "correlationId", "version", "createdAt", "updatedAt")
            values (:id, :__fe_tenant_organization_id, :projectId, :requestedByUserId,
                :reportDefinitionId, :reportVersion, :documentTemplateId, :documentTemplateVersion,
                cast(:format as "ExportFormat"), cast('QUEUED' as "ExportJobStatus"),
                cast(:filtersJson as jsonb), cast(:columnsJson as jsonb), :redactionPolicy,
                :retentionClass, cast(:snapshotJson as jsonb), 0, 0, :expiresAt, :requestedAt,
                :correlationId, 0, :now, :now)
            """, parameters);
        return id;
    }

    /**
     * {@code QUEUED -> RUNNING}: the claim.
     *
     * <p>Guarded on {@code status = 'QUEUED'} <em>and</em> on the version the caller read, so two
     * workers racing the same job produce one RUNNING row and the loser sees a zero affected-row count;
     * and a worker holding a stale version loses rather than overwriting a newer state.
     * {@code attemptCount} and {@code lastAttemptAt} move in the same statement, satisfying
     * {@code export_jobs_attempt_state_check} without a second write.
     *
     * @return true when this caller won the claim
     */
    public boolean claim(TenantScope scope, UUID jobId, int expectedVersion, LocalDateTime now) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of())
            .addValue("id", jobId)
            .addValue("now", now)
            .addValue("expectedVersion", expectedVersion);
        return jdbc.update("""
            update export_jobs
               set "status" = cast('RUNNING' as "ExportJobStatus"),
                   "startedAt" = :now,
                   "attemptCount" = "attemptCount" + 1,
                   "lastAttemptAt" = :now,
                   "nextAttemptAt" = null,
                   "progressPct" = 0,
                   "version" = "version" + 1,
                   "updatedAt" = :now
             where "organizationId" = :__fe_tenant_organization_id
               and "id" = :id
               and "status" = cast('QUEUED' as "ExportJobStatus")
               and "version" = :expectedVersion
            """, parameters) == 1;
    }

    /**
     * Records the filters that were actually PUSHED, overwriting the request-shaped value.
     *
     * <p>Guarded on {@code status = 'RUNNING'}, for two reasons. The first is ordering: this is written
     * between the claim and the terminal state, so a job that was never claimed must not accumulate
     * evidence about a statement that never ran. The second is retry: a second attempt re-plans and
     * re-writes, so the row ends up describing the attempt that produced the artefact rather than the
     * first attempt that failed — which is the only one an operator comparing a file to the job needs.
     *
     * <p>{@code "__requested"} is preserved, because "what was asked for" and "what ran" are different
     * questions and the difference is the evidence that a filter was not silently dropped. A filter
     * refused at planning raises instead of being recorded, so an absent key here means it was dropped,
     * not that it failed.
     */
    public boolean recordAppliedFilters(TenantScope scope, UUID jobId, Map<String, Object> applied,
                                        LocalDateTime now) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of())
            .addValue("id", jobId)
            .addValue("appliedJson", json.write(applied == null ? Map.of() : applied),
                java.sql.Types.OTHER)
            .addValue("now", now);
        return jdbc.update("""
            update export_jobs
               set "filters" = jsonb_build_object(
                       '__requested', coalesce("filters" -> '__requested', '{}'::jsonb),
                       '__applied', cast(:appliedJson as jsonb)),
                   "version" = "version" + 1,
                   "updatedAt" = :now
             where "organizationId" = :__fe_tenant_organization_id
               and "id" = :id
               and "status" = cast('RUNNING' as "ExportJobStatus")
            """, parameters) == 1;
    }

    /** Progress. Bounded by this method and re-checked by the schema's CHECK. */
    public boolean progress(TenantScope scope, UUID jobId, int pct, LocalDateTime now) {
        if (pct < 0 || pct > 100) {
            throw new IllegalArgumentException("progress must be within 0..100, got " + pct);
        }
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of())
            .addValue("id", jobId)
            .addValue("pct", pct)
            .addValue("now", now);
        return jdbc.update("""
            update export_jobs
               set "progressPct" = :pct, "version" = "version" + 1, "updatedAt" = :now
             where "organizationId" = :__fe_tenant_organization_id
               and "id" = :id
               and "status" = cast('RUNNING' as "ExportJobStatus")
            """, parameters) == 1;
    }

    /**
     * {@code RUNNING -> COMPLETED}.
     *
     * <p>Checksum, storage key and row count move together, because
     * {@code export_jobs_output_check} makes COMPLETED a biconditional over exactly those three. A writer
     * that set the checksum first and the key second would fail the CHECK in between.
     */
    public boolean complete(TenantScope scope, UUID jobId, long rowCount, String checksum,
                            String storageKey, long bytes, LocalDateTime now) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of())
            .addValue("id", jobId)
            .addValue("rowCount", rowCount)
            .addValue("checksum", checksum)
            .addValue("storageKey", storageKey)
            .addValue("bytes", bytes)
            .addValue("now", now);
        return jdbc.update("""
            update export_jobs
               set "status" = cast('COMPLETED' as "ExportJobStatus"),
                   "finishedAt" = :now,
                   "progressPct" = 100,
                   "rowCount" = :rowCount,
                   "checksum" = :checksum,
                   "storageKey" = :storageKey,
                   "bytes" = :bytes,
                   "errorMessage" = null,
                   "version" = "version" + 1,
                   "updatedAt" = :now
             where "organizationId" = :__fe_tenant_organization_id
               and "id" = :id
               and "status" = cast('RUNNING' as "ExportJobStatus")
            """, parameters) == 1;
    }

    /**
     * {@code RUNNING -> FAILED}.
     *
     * <p>Clears the output columns, because {@code export_jobs_output_check} refuses a non-COMPLETED job
     * that carries a checksum or an object — and rightly: "a FAILED job with a checksum invites an
     * operator to download a truncated file believing it is the export".
     */
    public boolean fail(TenantScope scope, UUID jobId, String errorMessage, LocalDateTime now) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of())
            .addValue("id", jobId)
            .addValue("errorMessage", truncate(errorMessage))
            .addValue("now", now);
        return jdbc.update("""
            update export_jobs
               set "status" = cast('FAILED' as "ExportJobStatus"),
                   "finishedAt" = :now,
                   "errorMessage" = :errorMessage,
                   "checksum" = null,
                   "storageKey" = null,
                   "rowCount" = null,
                   "bytes" = null,
                   "version" = "version" + 1,
                   "updatedAt" = :now
             where "organizationId" = :__fe_tenant_organization_id
               and "id" = :id
               and "status" = cast('RUNNING' as "ExportJobStatus")
            """, parameters) == 1;
    }

    /**
     * {@code QUEUED|RUNNING -> CANCELLED}.
     *
     * <p>The three cancellation columns move together ({@code export_jobs_cancelled_check} is a
     * biconditional over all of them), and the output columns are cleared for the reason
     * {@link #fail} clears them.
     *
     * <h2>Why {@code startedAt} is filled in for a job that never started</h2>
     *
     * <p>Two of the schema's constraints are jointly unsatisfiable without it.
     * {@code export_jobs_started_check} is a biconditional over
     * {@code (status IN RUNNING, COMPLETED, FAILED, CANCELLED, EXPIRED, DELETED)} — so a CANCELLED job
     * must carry a {@code startedAt} — while {@code export_jobs_finished_present_check} requires a
     * CANCELLED job to carry a {@code finishedAt}, and
     * {@code export_jobs_finished_requires_start_check} forbids a finish time without a start. A job
     * cancelled while still QUEUED therefore has exactly one representable shape, and this statement
     * writes it.
     *
     * <p>The reading is the migration's, not this module's: a CANCELLED job is one whose attempt ended,
     * and for a job cancelled before a worker claimed it that attempt is the cancellation. It is worth
     * knowing the row's timeline says "began and ended at the cancellation" rather than "never began" —
     * recorded here because a reader of the schema would not guess it. Changing the constraint instead
     * would mean a migration this module does not own, and a laxer row that lets a CANCELLED job have no
     * finish time would be worse evidence.
     */
    public boolean cancel(TenantScope scope, UUID jobId, UUID cancelledByUserId, String reason,
                          LocalDateTime now) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of())
            .addValue("id", jobId)
            .addValue("actor", cancelledByUserId)
            .addValue("reason", truncate(reason))
            .addValue("now", now);
        return jdbc.update("""
            update export_jobs
               set "status" = cast('CANCELLED' as "ExportJobStatus"),
                   "cancelledAt" = :now,
                   "cancelledByUserId" = :actor,
                   "cancelReason" = :reason,
                   "startedAt" = coalesce("startedAt", :now),
                   "finishedAt" = :now,
                   "checksum" = null,
                   "storageKey" = null,
                   "rowCount" = null,
                   "bytes" = null,
                   "version" = "version" + 1,
                   "updatedAt" = :now
             where "organizationId" = :__fe_tenant_organization_id
               and "id" = :id
               and "status" in (cast('QUEUED' as "ExportJobStatus"),
                                cast('RUNNING' as "ExportJobStatus"))
            """, parameters) == 1;
    }

    /**
     * {@code FAILED|CANCELLED -> QUEUED}: the retry.
     *
     * <p>Every column a terminal state owns is cleared back to its queued shape, and that is not tidiness
     * — it is required. {@code export_jobs_started_check} says a QUEUED job has no start time,
     * {@code export_jobs_output_check} says it has no checksum/key/rowCount,
     * {@code export_jobs_failure_reason_check} says it has no error message, and
     * {@code export_jobs_cancelled_check} says it has none of the three cancellation columns.
     *
     * <p>{@code attemptCount} and {@code lastAttemptAt} deliberately SURVIVE, because
     * {@code export_jobs_attempt_state_check} ties {@code attemptCount = 0} to {@code lastAttemptAt}
     * being null and this is attempt three. The retry count is the evidence that the job has been tried
     * more than once, and it is what an operator reads before deciding to retry again.
     */
    public boolean requeue(TenantScope scope, UUID jobId, LocalDateTime nextAttemptAt, LocalDateTime now) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of())
            .addValue("id", jobId)
            .addValue("nextAttemptAt", nextAttemptAt)
            .addValue("now", now);
        return jdbc.update("""
            update export_jobs
               set "status" = cast('QUEUED' as "ExportJobStatus"),
                   "progressPct" = 0,
                   "nextAttemptAt" = :nextAttemptAt,
                   "startedAt" = null,
                   "finishedAt" = null,
                   "errorMessage" = null,
                   "cancelledAt" = null,
                   "cancelledByUserId" = null,
                   "cancelReason" = null,
                   "checksum" = null,
                   "storageKey" = null,
                   "rowCount" = null,
                   "bytes" = null,
                   "version" = "version" + 1,
                   "updatedAt" = :now
             where "organizationId" = :__fe_tenant_organization_id
               and "id" = :id
               and "status" in (cast('FAILED' as "ExportJobStatus"),
                                cast('CANCELLED' as "ExportJobStatus"))
            """, parameters) == 1;
    }

    /**
     * {@code COMPLETED -> EXPIRED}: the window closed and the object is gone.
     *
     * <p>The output columns are cleared for the same biconditional reason as {@link #fail}, which means
     * the checksum does <em>not</em> survive expiry. That is the schema's decision, not an oversight, and
     * it is exactly why {@code export_download_events."checksumAtDownload"} exists: the evidence that a
     * download happened, and with which bytes, survives in the append-only download history where a
     * completed job's own checksum does not.
     *
     * <p>{@code bytes} is KEPT, so "how much was left on disk when the window closed" stays answerable.
     */
    public boolean expire(TenantScope scope, UUID jobId, LocalDateTime now) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of())
            .addValue("id", jobId)
            .addValue("now", now);
        return jdbc.update("""
            update export_jobs
               set "status" = cast('EXPIRED' as "ExportJobStatus"),
                   "expiredAt" = :now,
                   "checksum" = null,
                   "storageKey" = null,
                   "rowCount" = null,
                   "version" = "version" + 1,
                   "updatedAt" = :now
             where "organizationId" = :__fe_tenant_organization_id
               and "id" = :id
               and "status" = cast('COMPLETED' as "ExportJobStatus")
            """, parameters) == 1;
    }

    /**
     * {@code COMPLETED|EXPIRED -> DELETED}: the tombstone.
     *
     * <p>The row is never removed. {@code export_jobs_deleted_check} requires
     * {@code storageKey IS NULL}, so the object is gone before the state says so, and the evidence of the
     * deletion lives in {@code export_deletion_events}, whose foreign key is {@code ON DELETE RESTRICT}
     * precisely so that removing a job row could not destroy the proof its object was cleaned up.
     */
    public boolean tombstone(TenantScope scope, UUID jobId, UUID deletedByUserId, LocalDateTime now) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of())
            .addValue("id", jobId)
            .addValue("actor", deletedByUserId)
            .addValue("now", now);
        return jdbc.update("""
            update export_jobs
               set "status" = cast('DELETED' as "ExportJobStatus"),
                   "deletedAt" = :now,
                   "deletedByUserId" = :actor,
                   "storageKey" = null,
                   "checksum" = null,
                   "rowCount" = null,
                   "version" = "version" + 1,
                   "updatedAt" = :now
             where "organizationId" = :__fe_tenant_organization_id
               and "id" = :id
               and "status" in (cast('COMPLETED' as "ExportJobStatus"),
                                cast('EXPIRED' as "ExportJobStatus"))
            """, parameters) == 1;
    }

    // ------------------------------------------------------------------
    // reads
    // ------------------------------------------------------------------

    /**
     * One job for the download path, resolving the project from the row.
     *
     * <p>The download redemption must not accept a project id from the caller: the row's own
     * {@code projectId} is what the authorization check is performed against, so a caller cannot point a
     * download at a project they may see in order to obtain bytes belonging to one they may not.
     */
    public Optional<ExportJobRow> findForDownload(TenantScope scope, UUID jobId) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of())
            .addValue("id", jobId);
        return jdbc.query(FIND_ANY_PROJECT, parameters, ExportJobRepository::mapRow)
            .stream().findFirst();
    }

    /**
     * The customer-shaped detail.
     *
     * <p>Split from the list projection so a list read never carries a filter object or an authorization
     * snapshot, and so the one statement that does touch them is greppable in review.
     */
    public Optional<ExportJobDetail> findDetail(TenantScope scope, UUID jobId) {
        Optional<ExportJobRow> row = findForDownload(scope, jobId);
        if (row.isEmpty()) {
            return Optional.empty();
        }
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of())
            .addValue("id", jobId);
        List<ExportJobDetail> details = jdbc.query(FIND_DETAIL, parameters,
            (rs, rowNum) -> new ExportJobDetail(
                row.get(),
                json.readMap(rs.getString("filtersJson")),
                readStringList(rs.getString("columnsJson")),
                json.readMap(rs.getString("snapshotJson"))));
        return details.stream().findFirst();
    }

    /** A tenant's jobs for one project, or across the tenant when {@code projectId} is null. */
    public List<ExportJobRow> list(TenantScope scope, UUID projectId, String status, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("limit", Math.max(1, limit)));
        parameters.addValue("projectId", projectId, java.sql.Types.OTHER)
            .addValue("status", status);
        return jdbc.query(LIST_BY_PROJECT, parameters, ExportJobRepository::mapRow);
    }

    /** One requester's jobs. */
    public List<ExportJobRow> listByRequester(TenantScope scope, UUID requesterId, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of(
            "requesterId", requesterId,
            "limit", Math.max(1, limit)));
        return jdbc.query(LIST_BY_REQUESTER, parameters, ExportJobRepository::mapRow);
    }

    /** Completed jobs past their window, oldest first. The expiry sweep's bounded input. */
    public List<ExportJobRow> expirable(TenantScope scope, UUID projectId, LocalDateTime now, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("limit", Math.max(1, limit)));
        parameters.addValue("projectId", projectId, java.sql.Types.OTHER)
            .addValue("now", now);
        return jdbc.query(EXPIRABLE, parameters, ExportJobRepository::mapRow);
    }

    /** The declared queue wire value this module's handler drains. */
    public static String queueWireValue() {
        return QUEUE_WIRE_VALUE;
    }

    /** The bound page size an operator or requester list uses. */
    public static int pageSize() {
        return ReportingExportsModule.Limits.EXPORT_JOB_PAGE;
    }

    /**
     * The emitted column keys of one job.
     *
     * <p>Its own small statement rather than a component of {@link ExportJobRow}, for the reason that record
     * does not carry the customer-shaped columns. The download path needs this list on every redemption to
     * re-authorize against the fields the artefact actually contains, so it is a hot read and a hot read
     * should not be a wide one.
     */
    public List<String> columnsOf(TenantScope scope, UUID jobId) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of())
            .addValue("id", jobId);
        return jdbc.query("""
            select j."columns"::text as "columnsJson"
              from export_jobs j
             where j."organizationId" = :__fe_tenant_organization_id
               and j."id" = :id
            """, parameters, (rs, rowNum) -> rs.getString("columnsJson")).stream()
            .findFirst()
            .map(text -> readStringList(text))
            .orElse(List.of());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static ExportJobRow mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ExportJobRow(
            rs.getObject("id", UUID.class),
            rs.getObject("organizationId", UUID.class),
            rs.getObject("projectId", UUID.class),
            rs.getObject("requestedByUserId", UUID.class),
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
            rs.getString("storageKey"),
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
            rs.getString("correlationId"),
            rs.getInt("version"));
    }

    /** Reads a jsonb string array through the shared codec, so the shape is decided in one place. */
    private List<String> readStringList(String jsonText) {
        List<Object> raw = json.readList(jsonText);
        List<String> values = new java.util.ArrayList<>(raw.size());
        for (Object value : raw) {
            if (value != null) {
                values.add(String.valueOf(value));
            }
        }
        return List.copyOf(values);
    }

    /**
     * An error message bounded before it is stored.
     *
     * <p>500 characters because the column is for an operator to read in a list, and an unbounded stack
     * trace pasted into it turns the export list into a log viewer. The code and the actionable part of
     * the message both survive the truncation.
     */
    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        String trimmed = message.trim();
        return trimmed.length() <= 500 ? trimmed : trimmed.substring(0, 500) + "…";
    }
}