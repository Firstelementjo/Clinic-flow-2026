package com.firstelement.exports.job;

import com.firstelement.admin.audit.AdminAuditStreamWriter;
import com.firstelement.admin.audit.AuditAttribution;
import com.firstelement.admin.audit.AuditVocabularies;
import com.firstelement.admin.audit.AuditWrite;
import com.firstelement.admin.audit.RetentionClassResolver;
import com.firstelement.crm.persistence.JsonCodec;
import com.firstelement.exports.ReportingExportsModule;
import com.firstelement.exports.catalog.ExportRedactionPolicy;
import com.firstelement.exports.catalog.ReportCatalogConflictException;
import com.firstelement.exports.catalog.ReportCatalogNotFoundException;
import com.firstelement.exports.catalog.ReportDefinitionService;
import com.firstelement.exports.catalog.ReportField;
import com.firstelement.exports.catalog.ReportFieldRegistry;
import com.firstelement.exports.catalog.ReportFilterShape;
import com.firstelement.exports.catalog.ReportSubject;
import com.firstelement.exports.catalog.ReportVersionNotPublishedException;
import com.firstelement.exports.config.ExportProperties;
import com.firstelement.exports.format.DocumentTemplateService;
import com.firstelement.exports.persistence.ExportEventRepository;
import com.firstelement.exports.persistence.ExportJobRepository;
import com.firstelement.exports.persistence.FinanceExportGateRepository;
import com.firstelement.exports.persistence.ReportCatalogRepository;
import com.firstelement.exports.plan.ExportFilter;
import com.firstelement.exports.plan.ExportQueryPlan;
import com.firstelement.exports.plan.ExportQueryPlanner;
import com.firstelement.exports.plan.ExportRowStream;
import com.firstelement.exports.redaction.ResolvedExportColumns;
import com.firstelement.exports.storage.ExportObjectStore;
import com.firstelement.finance.ledger.LedgerPrincipal;
import com.firstelement.finance.ledger.UsageEventFactory;
import com.firstelement.finance.ledger.UsageEventWriter;
import com.firstelement.security.TenantContext;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.error.PolicyDeniedException;
import com.firstelement.security.permission.AuthorizationService;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.permission.PolicyResource;
import com.firstelement.security.query.TenantQueryGuard;
import com.firstelement.security.query.TenantScope;
import com.firstelement.worker.outbox.OutboxEnqueuer;
import com.firstelement.worker.outbox.OutboxIntent;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates, inspects, cancels, retries and generates export jobs.
 *
 * <h2>Persist first, send second — and the outbox is in the same transaction</h2>
 *
 * <p>Phase-9 hard rule 1. {@link #create} writes the job row <em>and</em> publishes the outbox intent in
 * one transaction, so a rolled-back request leaves no job and a committed one leaves a job already
 * promised to a worker. It never generates anything: the request returns a {@code QUEUED} row and a
 * correlation id, and the bytes appear later. A two-million-row export on a request thread is the outage
 * this class exists to prevent, and PROJECT_REQUIREMENTS §7 lists "no synchronous … export work on
 * request threads" as a non-functional requirement rather than an optimisation.
 *
 * <h2>Reauthorize three times, because a permission can lapse between any two of them</h2>
 *
 * <ol>
 *   <li><b>Creation</b> resolves the caller's effective permission set and writes the resulting column set
 *       plus the withheld-column evidence onto the job.</li>
 *   <li><b>Generation</b> ({@link #generate}) re-resolves from the requester's <em>current</em> role and
 *       memberships, never from the snapshot. A snapshot is evidence of what was permitted when the
 *       request arrived; a generation that trusted it would produce money columns for someone who has
 *       since lost {@code analytics:read-organization}.</li>
 *   <li><b>Download</b> re-checks every <em>emitted</em> field's permission against the caller holding the
 *       link, and refuses if any lapsed. It does not re-redact: the stored object's checksum is evidence of
 *       what was produced, and serving different bytes under the same checksum makes that evidence
 *       untrue.</li>
 * </ol>
 *
 * <h2>The pinned definition is re-read at generation time</h2>
 *
 * <p>{@code (reportDefinitionId, reportVersion)} is a biconditional in the schema, and version bodies are
 * immutable and never purged (ADR-0009 §4: "a version is referenced by historical runs"). So a queued job
 * re-reads exactly the body it was created against — whether that version is still PUBLISHED or has since
 * been RETIRED — and a definition edited ten times in between cannot alter one byte of the artefact.
 *
 * <h2>Failure is a recorded state, not an exception</h2>
 *
 * <p>{@link #generate} returns a {@link GenerationOutcome} for an expected failure rather than throwing.
 * A failed export is a state with a reason an operator can read, plus an append-only attempt row; it is not
 * an exception unwinding a worker loop.
 */
@Service
public class ExportJobService {

    private static final Logger log = LoggerFactory.getLogger(ExportJobService.class);

    static final String JOB_REQUESTED = "EXPORT_JOB_REQUESTED";
    static final String JOB_CANCELLED = "EXPORT_JOB_CANCELLED";
    static final String JOB_RETRIED = "EXPORT_JOB_RETRIED";
    static final String JOB_FAILED = "EXPORT_JOB_FAILED";

    /**
     * The authorization-snapshot key holding the locale the requester pinned.
     *
     * <p>Not a column on {@code export_jobs}, and deliberately so: {@code export_jobs} has no locale, and
     * adding one is a Phase 12 migration on a table whose shape a decision like this should not force.
     * The snapshot is already the request's own record — it carries the pinned report version and template
     * version — so this is where a pinned locale belongs until a column exists.
     */
    static final String REQUESTED_LOCALE = "requestedLocale";

    /** The {@code retention_classes.stream} label exports stamp on their rows. */
    static final String EXPORT_RETENTION_STREAM = "ADMIN";

    /**
     * The conventional class code for export artefacts.
     *
     * <p>Resolved through {@link RetentionClassResolver} rather than stamped, because ADR-0009 §4 rule 4
     * says "no table in this ADR is purged by a scheduled job until the retention-class column is
     * populated for it", and a hard-coded code would satisfy the NOT NULL CHECK while defeating the rule.
     */
    static final String EXPORT_ARTEFACT_CLASS = "EXPORT_ARTEFACTS";

    private final ExportJobRepository jobs;
    private final ExportEventRepository events;
    private final ReportCatalogRepository catalog;
    private final ReportDefinitionService definitions;
    private final ExportQueryPlanner planner;
    private final FinanceExportGateRepository financeGate;
    private final ExportGenerationRunner runner;
    private final DocumentTemplateService templates;
    private final ExportObjectStore store;
    private final TenantQueryGuard guard;
    private final AuthorizationService authorization;
    private final AdminAuditStreamWriter audit;
    private final RetentionClassResolver retentionClasses;
    private final OutboxEnqueuer outbox;
    private final UsageEventWriter usageEvents;
    private final JsonCodec json;
    private final ExportProperties properties;

    public ExportJobService(ExportJobRepository jobs,
                            ExportEventRepository events,
                            ReportCatalogRepository catalog,
                            ReportDefinitionService definitions,
                            ExportQueryPlanner planner,
                            FinanceExportGateRepository financeGate,
                            ExportGenerationRunner runner,
                            DocumentTemplateService templates,
                            ExportObjectStore store,
                            TenantQueryGuard guard,
                            AuthorizationService authorization,
                            AdminAuditStreamWriter audit,
                            RetentionClassResolver retentionClasses,
                            OutboxEnqueuer outbox,
                            UsageEventWriter usageEvents,
                            JsonCodec json,
                            ExportProperties properties) {
        this.jobs = jobs;
        this.events = events;
        this.catalog = catalog;
        this.definitions = definitions;
        this.planner = planner;
        this.financeGate = financeGate;
        this.runner = runner;
        this.templates = templates;
        this.store = store;
        this.guard = guard;
        this.authorization = authorization;
        this.audit = audit;
        this.retentionClasses = retentionClasses;
        this.outbox = outbox;
        this.usageEvents = usageEvents;
        this.json = json;
        this.properties = properties;
    }

    // ------------------------------------------------------------------
    // creation
    // ------------------------------------------------------------------

    /** What a caller asked for. Every field is a request; none of them is authorization. */
    public record ExportRequest(UUID reportDefinitionId,
                                Integer reportVersion,
                                UUID documentTemplateId,
                                Integer documentTemplateVersion,
                                String format,
                                Map<String, Object> filters,
                                List<String> sort,
                                String locale,
                                Integer expiresInDays) {

        /**
         * The locale this request pins, or {@code null} when it expresses no preference.
         *
         * <p>Validated here rather than only at the HTTP boundary, because this service is also reachable
         * from a console and from a job — the same reason {@link #create} re-checks the permission rather
         * than trusting the annotation.
         *
         * <p>Blank is {@code null} and not {@code "ar"}: "no preference" and "Arabic please" are different
         * requests, and collapsing them would let a request that said nothing override a definition that
         * declares English.
         */
        public String normalizedLocale() {
            return locale == null || locale.isBlank() ? null : ExportLocale.require(locale).wireValue();
        }
    }

    /** The queued job, plus what a client needs to follow it. */
    public record QueuedExport(UUID jobId,
                               String status,
                               String format,
                               String queue,
                               LocalDateTime expiresAt,
                               List<String> columns,
                               List<String> withheldColumns,
                               String redactionPolicy,
                               String correlationId) {
    }

    /**
     * Creates and queues an export.
     *
     * @throws PolicyDeniedException when the project is outside the caller's scope, or the definition
     *                               belongs to another tenant — both rendered as an identical 404
     */
    @Transactional
    @RequirePermission(Permission.ANALYTICS_READ_PROJECT)
    public QueuedExport create(TenantContext caller, UUID projectId, ExportRequest request,
                               String correlationId) {
        authorization.check(caller, Permission.ANALYTICS_READ_PROJECT);
        if (projectId == null) {
            // export_jobs."projectId" is NOT NULL and deliberately so: "an export is always about one
            // project's data", so a NULL could only mean unknown scope.
            throw new IllegalArgumentException(
                "An export must name the project it is about. export_jobs.projectId is NOT NULL because an "
                    + "export with no project scope would be an organisation-wide extraction, which is the "
                    + "one thing this module's project predicate exists to prevent.");
        }
        TenantScope scope = guard.scope(caller, projectId);
        if (caller.userId() == null) {
            throw new IllegalStateException(
                "Only an authenticated user may request an export; a machine principal has no user id to "
                    + "record in requestedByUserId, and an unattributable export is not evidence.");
        }

        ExportFormat format = ExportFormat.require(request.format());
        ExportGenerationRunner.assertRenderable(format);

        UUID definitionId = requirePinnedDefinition(request);
        int versionNumber = request.reportVersion();
        ReportCatalogRepository.DefinitionRow definition = catalog.findDefinition(scope, definitionId)
            .orElseThrow(() -> PolicyDeniedException.tenantOutOfScope("report-definition-out-of-scope",
                caller.organizationId(), definitionId));
        requireVersionPermissions(caller, definition);

        List<ReportField> declared = declaredFields(scope, definitionId, versionNumber);
        ReportSubject subject = declared.get(0).subject();
        ExportRedactionPolicy policy = ReportCatalogRepository.policyOf(definition.redactionPolicy());

        // The template half of the pin, validated HERE rather than left to generation. A DOCX request that
        // names a version nobody has published would otherwise queue a job and fail it inside the renderer
        // on a worker: a worse answer for the caller, a queue slot spent, and an operator reading a job
        // failure for what is really a bad request. A CSV request names no template and skips this.
        if (request.documentTemplateId() != null && request.documentTemplateVersion() != null) {
            templates.requirePublishableVersion(scope, request.documentTemplateId(),
                request.documentTemplateVersion());
        }

        List<ExportFilter> filters = ReportFilterShape.toFilters(
            ReportFilterShape.normalizeRequest(request.filters()), subject);
        // The caller's own filters are pushed at generation alongside the version's declared defaults, so
        // they are recorded here as part of the request but NOT trusted until the planner refuses or
        // accepts them. A filter that cannot be pushed fails the JOB, which is where an operator sees it.

        ResolvedExportColumns columns = com.firstelement.exports.redaction.ExportRedactionResolver
            .resolve(declared, policy, definition.requiredPermissions(),
                authorization.effectivePermissions(caller, PolicyResource.ofProject(caller.organizationId(), projectId)));
        if (columns.emitted().isEmpty()) {
            throw new EmptyExportRefusal(columns);
        }

        LocalDateTime now = AuditAttribution.now();
        int retentionDays = request.expiresInDays() == null
            ? properties.getRetention().getDefaultWindowDays() : request.expiresInDays();
        if (retentionDays < 1 || retentionDays > 3650) {
            throw new IllegalArgumentException(
                "expiresInDays must be between 1 and 3650. An export with no expiry is a permanent copy of "
                    + "patient data in object storage, which is a data-protection defect rather than a "
                    + "missing feature.");
        }
        LocalDateTime expiresAt = now.plusDays(retentionDays);

        Map<String, Object> snapshot = snapshotOf(columns, caller, definition, subject, request, now);
        String retentionClass = retentionClasses.resolve(scope, EXPORT_RETENTION_STREAM,
            EXPORT_ARTEFACT_CLASS);

        Map<String, Object> storedFilters = new LinkedHashMap<>();
        storedFilters.put("__requested", request.filters() == null ? Map.of() : request.filters());
        // Only "__requested" is written here. "__applied" is added by
        // ExportJobRepository.recordAppliedFilters once the statement is known, because a filter the
        // planner refuses is never applied and must not appear as though it had been. Writing the
        // requester's keys at creation and treating that as evidence of what ran is the specific
        // dishonesty this split exists to prevent.

        UUID jobId = jobs.insert(scope, projectId, caller.userId(), definitionId, versionNumber,
            request.documentTemplateId(), request.documentTemplateVersion(), format.name(),
            storedFilters, new ArrayList<>(columns.emittedKeys()), columns.policy().wireValue(),
            retentionClass, snapshot, expiresAt, now, correlationId);

        outbox.publish(OutboxIntent.of(caller.organizationId(), projectId, "ExportJob", jobId,
            ExportJobRepository.QUEUE_WIRE_VALUE,
            "{\"exportJobId\":\"" + jobId + "\"}",
            "export-job:" + jobId, correlationId, jobId.toString()));

        audit(caller, scope, JOB_REQUESTED, "ExportJob", jobId, AuditVocabularies.AuditOutcome.SUCCESS,
            Map.of("format", format.name(),
                "columnCount", String.valueOf(columns.emitted().size()),
                "withheldCount", String.valueOf(columns.withheld().size()),
                "redactionPolicy", columns.policy().wireValue(),
                "expiresAt", expiresAt.toString(),
                "reportVersion", String.valueOf(versionNumber)));
        log.info("export job queued job={} tenant={} project={} format={} columns={} withheld={}",
            jobId, caller.organizationId(), projectId, format, columns.emitted().size(),
            columns.withheld().size());

        return new QueuedExport(jobId, "QUEUED", format.name(), ExportJobRepository.QUEUE_WIRE_VALUE,
            expiresAt, List.copyOf(columns.emittedKeys()), List.copyOf(columns.withheldKeys()),
            columns.policy().wireValue(), correlationId);
    }

    // ------------------------------------------------------------------
    // generation (worker side)
    // ------------------------------------------------------------------

    /**
     * Generates one job's artefact.
     *
     * <p>Reached from {@link ExportJobHandler} through the dispatcher, and directly from an operator replay
     * and from the integration suites. It re-authorizes before producing a byte, resolves the pinned
     * definition version, runs the finance gate when money is involved, writes the object, then records the
     * terminal state and an append-only attempt row.
     *
     * <p><b>Not {@code @Transactional}, deliberately.</b> Generation opens its own streaming read with
     * autocommit off and rolls that read back to release the server-side cursor. Run inside a
     * caller-owned transaction, that read joins the caller's transaction, and any failure inside the
     * generation marks it rollback-only - so {@code UnexpectedRollbackException} escapes the catch blocks
     * below <em>after</em> they have already recorded a terminal state, and the job ends up both recorded
     * as failed and rolled back at the same time. Holding one transaction open across a multi-second
     * streaming read of a tenant's whole table is wrong on its own terms too: it pins a connection and a
     * long-lived MVCC snapshot for the length of the export.
     *
     * <p>Each step is therefore its own short transaction ({@code jobs.claim},
     * {@code events.recordAttempt}, {@code jobs.complete}), which is also the honest unit of work: a job's
     * claim and its terminal state are separate facts, and an artefact already written to object storage
     * must survive a later database failure so it can be reconciled rather than orphaned.
     *
     * @param requester the requester's <em>current</em> context, not the one from creation time
     */
    public GenerationOutcome generate(TenantContext requester, TenantScope scope, UUID jobId,
                                       String workerId) {
        ExportJobRepository.ExportJobRow job = jobs.findForDownload(scope, jobId).orElse(null);
        if (job == null) {
            return GenerationOutcome.refused("EXPORT_JOB_NOT_FOUND");
        }
        int attemptNumber = job.attemptCount() + 1;
        LocalDateTime startedAt = AuditAttribution.now();

        if (!jobs.claim(scope, jobId, job.version(), startedAt)) {
            // Another worker holds it, or it is no longer QUEUED. Recorded as SKIPPED so the attempt
            // history shows the duplicate suppression rather than a gap.
            events.recordAttempt(scope, job.projectId(), jobId, attemptNumber,
                ExportEventRepository.AttemptOutcome.SKIPPED, workerId, startedAt, null, null, null,
                null, null, job.correlationId());
            return GenerationOutcome.skipped("EXPORT_JOB_NOT_CLAIMABLE");
        }
        // Deliberately NOT recording a STARTED row here. `export_job_attempts_job_attempt_key` is a
        // UNIQUE index on (organizationId, exportJobId, attemptNumber): one attempt number, one row. A
        // STARTED row would therefore collide with the terminal outcome written below, and
        // ExportEventRepository swallows that DuplicateKeyException as a duplicate-suppression win — so
        // every attempt recorded STARTED and nothing else. No attempt ever reported its outcome, its
        // duration or its row count, which is the entire reason the table exists.
        //
        // The table's own CHECK constraints settle which row was intended: rowsProduced is required on
        // SUCCEEDED, an error code and message are required on FAILED, and finishedAt and durationMs are a
        // biconditional. All three describe a TERMINAL row. The cost is that a worker killed mid-flight
        // leaves no attempt row and the job stays RUNNING — which is a visible signal rather than a silent
        // one, and is strictly better than a STARTED row that nothing ever closes.

        try {
            ResolvedExportColumns columns = reauthorize(scope, job, requester);
            ExportFormat format = ExportFormat.require(job.format());
            ExportGenerationRunner.assertRenderable(format);

            ExportQueryPlan plan = planFor(scope, job, columns, format);
            recordProgress(scope, jobId, 5);

            byte[] templateBytes = null;
            if (format == ExportFormat.DOCX) {
                templateBytes = resolveTemplate(scope, job);
            }
            // Resolved once and passed to both consumers. Reading it twice would read the job's
            // authorization snapshot twice for one artefact, which is a query the generation path does not
            // need to pay twice.
            Locale renderLocale = localeFor(scope, job);
            Map<String, String> metadata = metadataFor(renderLocale, zoneFor(scope, job), job, columns);

            ExportGenerationRunner.Artefact artefact = runner.generate(scope, plan, columns,
                renderLocale, zoneFor(scope, job), format.extension(), templateBytes, metadata,
                () -> isStillRunning(scope, jobId));

            LocalDateTime finishedAt = AuditAttribution.now();
            if (!jobs.complete(scope, jobId, artefact.rowCount(), artefact.checksum(),
                artefact.storageKey(), artefact.bytes(), finishedAt)) {
                // The job stopped being RUNNING while generating — a cancellation won the race. The object
                // is removed rather than orphaned, because an object no row names is a file nobody can
                // ever delete.
                store.delete(artefact.storageKey());
                return recordTerminal(scope, job, jobId, attemptNumber, workerId, startedAt,
                    ExportEventRepository.AttemptOutcome.CANCELLED, "EXPORT_CANCELLED_DURING_GENERATION",
                    "The job stopped being RUNNING while it was generating, so the produced object was "
                        + "deleted rather than left without a row naming it.");
            }
            recordProgress(scope, jobId, 100);
            int durationMs = duration(startedAt, finishedAt);
            events.recordAttempt(scope, job.projectId(), jobId, attemptNumber,
                ExportEventRepository.AttemptOutcome.SUCCEEDED, workerId, startedAt, finishedAt, durationMs,
                artefact.rowCount(), null, null, job.correlationId());
            emitUsageEvent(scope, job, requester, finishedAt);

            log.info("export job completed job={} rows={} bytes={} checksum={} durationMs={}",
                jobId, artefact.rowCount(), artefact.bytes(), artefact.checksum(), durationMs);
            return GenerationOutcome.completed(artefact.rowCount(), artefact.checksum(),
                artefact.bytes(), durationMs);
        } catch (ExportGenerationRunner.ExportCancelledException e) {
            return recordTerminal(scope, job, jobId, attemptNumber, workerId, startedAt,
                ExportEventRepository.AttemptOutcome.CANCELLED, e.code(), e.getMessage());
        } catch (ExportJobRefusal e) {
            return recordTerminal(scope, job, jobId, attemptNumber, workerId, startedAt,
                ExportEventRepository.AttemptOutcome.FAILED, e.code(), e.getMessage());
        } catch (RuntimeException e) {
            // Anything else — a refused filter, a bad template, an incomplete locked period, a JDBC type the
            // formatter does not convert, a row cap — is a permanent, actionable failure. Retrying produces
            // the same result, and the code is read off the exception where it declares one so the job's
            // errorMessage names the actual cause rather than a generic failure.
            String code = errorCodeOf(e);
            log.warn("export job failed job={} code={}", jobId, code, e);
            return recordTerminal(scope, job, jobId, attemptNumber, workerId, startedAt,
                ExportEventRepository.AttemptOutcome.FAILED, code, diagnosticMessage(e));
        }
    }

    // ------------------------------------------------------------------
    // lifecycle
    // ------------------------------------------------------------------

    /** Cancels a queued or running export. */
    @Transactional
    @RequirePermission(Permission.ANALYTICS_READ_PROJECT)
    public ExportJobRepository.ExportJobRow cancel(TenantContext caller, UUID jobId, String reason) {
        authorization.check(caller, Permission.ANALYTICS_READ_PROJECT);
        TenantScope scope = guard.tenantScope(caller);
        ExportJobRepository.ExportJobRow job = requireVisibleJob(scope, caller, jobId);
        String effectiveReason = requireReason(reason);
        if (!jobs.cancel(scope, jobId, caller.userId(), effectiveReason, AuditAttribution.now())) {
            throw new IllegalStateException(
                "Export job " + jobId + " is in state " + job.status() + ", which is not cancellable. A "
                    + "completed, expired or deleted job has already ended its attempt, and cancelling it "
                    + "would rewrite a state an operator has already seen.");
        }
        audit(caller, scope, JOB_CANCELLED, "ExportJob", jobId, AuditVocabularies.AuditOutcome.SUCCESS,
            Map.of("reason", effectiveReason, "previousState", job.status()));
        return jobs.findForDownload(scope, jobId).orElseThrow();
    }

    /**
     * Retries a failed or cancelled export.
     *
     * <p>Requeues with the <em>same</em> pinned report version, which is the whole point: a retry is the
     * same export, not a new one against whatever the definition says now. The outbox idempotency key
     * includes the attempt count so two retries of the same job produce two distinct intents.
     */
    @Transactional
    @RequirePermission(Permission.ANALYTICS_READ_PROJECT)
    public ExportJobRepository.ExportJobRow retry(TenantContext caller, UUID jobId) {
        authorization.check(caller, Permission.ANALYTICS_READ_PROJECT);
        TenantScope scope = guard.tenantScope(caller);
        ExportJobRepository.ExportJobRow job = requireVisibleJob(scope, caller, jobId);
        LocalDateTime now = AuditAttribution.now();
        if (!jobs.requeue(scope, jobId, now, now)) {
            throw new IllegalStateException(
                "Export job " + jobId + " is in state " + job.status() + ", which is not retryable. Only a "
                    + "FAILED or CANCELLED job is.");
        }
        outbox.publish(OutboxIntent.of(caller.organizationId(), job.projectId(), "ExportJob", jobId,
            ExportJobRepository.QUEUE_WIRE_VALUE, "{\"exportJobId\":\"" + jobId + "\"}",
            "export-job-retry:" + jobId + ":" + job.attemptCount(), job.correlationId(),
            jobId.toString()));
        audit(caller, scope, JOB_RETRIED, "ExportJob", jobId, AuditVocabularies.AuditOutcome.SUCCESS,
            Map.of("previousAttemptCount", String.valueOf(job.attemptCount()),
                "pinnedReportVersion", job.reportVersion() == null ? "" :
                    String.valueOf(job.reportVersion())));
        return jobs.findForDownload(scope, jobId).orElseThrow();
    }

    // ------------------------------------------------------------------
    // reads
    // ------------------------------------------------------------------

    /** One job, for a caller entitled to see it. */
    @Transactional(readOnly = true)
    @RequirePermission(Permission.ANALYTICS_READ_PROJECT)
    public ExportJobRepository.ExportJobRow get(TenantContext caller, UUID jobId) {
        authorization.check(caller, Permission.ANALYTICS_READ_PROJECT);
        return requireVisibleJob(guard.tenantScope(caller), caller, jobId);
    }

    /** One job's full detail, including the filters and the authorization snapshot. */
    @Transactional(readOnly = true)
    @RequirePermission(Permission.ANALYTICS_READ_PROJECT)
    public ExportJobRepository.ExportJobDetail detail(TenantContext caller, UUID jobId) {
        authorization.check(caller, Permission.ANALYTICS_READ_PROJECT);
        TenantScope scope = guard.tenantScope(caller);
        requireVisibleJob(scope, caller, jobId);
        return jobs.findDetail(scope, jobId)
            .orElseThrow(() -> new ReportCatalogNotFoundException("ExportJob", jobId));
    }

    /**
     * The export jobs a caller may see.
     *
     * <p>An org-wide caller sees the tenant's jobs for the project; anyone else sees only their own. The
     * branch is on the effective permission rather than on a request flag, so a caller cannot widen their
     * own list by asking for it.
     */
    @Transactional(readOnly = true)
    @RequirePermission(Permission.ANALYTICS_READ_PROJECT)
    public List<ExportJobRepository.ExportJobRow> list(TenantContext caller, UUID projectId,
                                                       String status) {
        authorization.check(caller, Permission.ANALYTICS_READ_PROJECT);
        TenantScope scope = guard.scope(caller, projectId);
        int limit = ExportJobRepository.pageSize();
        if (!authorization.allows(caller, Permission.ANALYTICS_READ_ORGANIZATION)) {
            return jobs.listByRequester(scope, caller.userId(), limit);
        }
        return jobs.list(scope, projectId, status, limit);
    }

    /** One job's attempt history. */
    @Transactional(readOnly = true)
    @RequirePermission(Permission.ANALYTICS_READ_PROJECT)
    public List<ExportEventRepository.AttemptRow> attempts(TenantContext caller, UUID jobId) {
        authorization.check(caller, Permission.ANALYTICS_READ_PROJECT);
        TenantScope scope = guard.tenantScope(caller);
        requireVisibleJob(scope, caller, jobId);
        return events.attempts(scope, jobId, ReportingExportsModule.Limits.EXPORT_EVENT_PAGE);
    }

    /** One job's download history, including the refusals. */
    @Transactional(readOnly = true)
    @RequirePermission(Permission.ANALYTICS_READ_PROJECT)
    public List<ExportEventRepository.DownloadRow> downloads(TenantContext caller, UUID jobId) {
        authorization.check(caller, Permission.ANALYTICS_READ_PROJECT);
        TenantScope scope = guard.tenantScope(caller);
        requireVisibleJob(scope, caller, jobId);
        return events.downloads(scope, jobId, ReportingExportsModule.Limits.EXPORT_EVENT_PAGE);
    }

    /** One job's object-storage deletions. */
    @Transactional(readOnly = true)
    @RequirePermission(Permission.ANALYTICS_READ_PROJECT)
    public List<ExportEventRepository.DeletionRow> deletions(TenantContext caller, UUID jobId) {
        authorization.check(caller, Permission.ANALYTICS_READ_PROJECT);
        TenantScope scope = guard.tenantScope(caller);
        requireVisibleJob(scope, caller, jobId);
        return events.deletions(scope, jobId, ReportingExportsModule.Limits.EXPORT_EVENT_PAGE);
    }

    // ------------------------------------------------------------------
    // resolution
    // ------------------------------------------------------------------

    /**
     * The authorization snapshot written at creation.
     *
     * <p>Non-empty by construction — the schema's CHECK refuses {@code {}} — and it answers "which
     * permissions did this person actually have on the day" <em>after</em> the export has been served. The
     * withheld columns are named with their reason, because a column that is absent from the file and a
     * column that was never declared look identical otherwise.
     */
    private Map<String, Object> snapshotOf(ResolvedExportColumns columns, TenantContext caller,
                                           ReportCatalogRepository.DefinitionRow definition,
                                           ReportSubject subject, ExportRequest request,
                                           LocalDateTime now) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("principalType", caller.principalType().value());
        snapshot.put("principalRole", caller.role() == null ? "" : caller.role().name());
        snapshot.put("subject", subject.storageName());
        snapshot.put("policy", columns.policy().wireValue());
        snapshot.put("evaluatedAt", now.toString());
        List<Map<String, String>> withheld = new ArrayList<>();
        for (ResolvedExportColumns.WithheldColumn column : columns.withheld()) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("field", column.field().key());
            entry.put("reason", column.reason().name());
            entry.put("requiredPermission", column.requiredPermission());
            withheld.add(entry);
        }
        snapshot.put("withheldColumns", withheld);
        snapshot.put("emittedColumns", new ArrayList<>(columns.emittedKeys()));
        snapshot.put("requiredPermissions", definition.requiredPermissions());
        // The locale the REQUESTER pinned, recorded because generation honours it in preference to the
        // definition's declared default. Recorded here rather than left implicit so the job row answers
        // "in which language was this produced" months later, from the artefact that no longer exists.
        String requestedLocale = request.normalizedLocale();
        if (requestedLocale != null) {
            snapshot.put(REQUESTED_LOCALE, requestedLocale);
        }
        if (request.reportVersion() != null) {
            snapshot.put("reportVersion", request.reportVersion());
        }
        if (request.documentTemplateVersion() != null) {
            snapshot.put("documentTemplateVersion", request.documentTemplateVersion());
        }
        return snapshot;
    }

    private UUID requirePinnedDefinition(ExportRequest request) {
        if (request.reportDefinitionId() == null) {
            throw new IllegalArgumentException(
                "An export must name the report definition it is produced from. export_jobs' report-pin "
                    + "biconditional makes (definition, version) all-or-nothing, and a definition is what "
                    + "makes the export reproducible and auditable months later.");
        }
        if (request.reportVersion() == null) {
            throw new IllegalArgumentException(
                "An export must pin a report version. Resolving 'whatever is published when the job runs' is "
                    + "the reproducibility bug export_jobs_report_pin_check exists to prevent.");
        }
        return request.reportDefinitionId();
    }

    /** A version's declared {@code requiredPermissions}, enforced at creation and again at generation. */
    private void requireVersionPermissions(TenantContext caller,
                                           ReportCatalogRepository.DefinitionRow definition) {
        for (String permission : definition.requiredPermissions()) {
            Permission required;
            try {
                required = Permission.fromValue(permission);
            } catch (IllegalArgumentException e) {
                throw new ReportCatalogConflictException(
                    "The report definition requires the permission '" + permission + "', which is not in "
                        + "the permission catalogue. A requirement nobody can be evaluated against is a "
                        + "definition defect.", "ReportDefinition", definition.id());
            }
            if (!authorization.allows(caller, required)) {
                // 403 rather than 404: the caller can see the definition, and "you lack a permission this
                // report requires" is the actionable message.
                throw new org.springframework.security.access.AccessDeniedException(
                    "This report requires the permission '" + permission + "', which the caller does not "
                        + "hold");
            }
        }
    }

    /**
     * The pinned version's declared fields.
     *
     * <p>Resolved from the immutable version row, which is what makes the artefact reproducible. A request
     * cannot narrow or widen this set: an export must be the report its definition says it is.
     *
     * <p>A DRAFT is refused — see {@link ReportVersionNotPublishedException} for why that rule exists and
     * why RETIRED stays legal. The check is here rather than only at {@link #create} so generation and
     * download re-resolve it too: a pin is re-read on every hop that needs the body, and a check that only
     * ran once would be a check that a later state could contradict.
     */
    private List<ReportField> declaredFields(TenantScope scope, UUID definitionId, int versionNumber) {
        ReportCatalogRepository.VersionRow version = definitions.pinnedVersion(scope, definitionId,
            versionNumber).orElseThrow(() -> new ReportCatalogNotFoundException("ReportVersion",
                versionNumber + " of definition " + definitionId));
        if ("DRAFT".equals(version.state())) {
            throw new ReportVersionNotPublishedException(definitionId, versionNumber, version.state(),
                "Report definition");
        }
        return ReportFieldRegistry.resolve(readStringList(version.columnsJson()));
    }

    private List<ReportField> pinnedFields(TenantScope scope, ExportJobRepository.ExportJobRow job) {
        return declaredFields(scope, job.reportDefinitionId(), job.reportVersion());
    }

    /**
     * Re-authorizes for generation, against the requester's <em>current</em> permissions.
     *
     * <p>Never the snapshot. And for a job whose column set shrank between queueing and generation — because
     * the requester was downgraded — the export is REFUSED rather than produced with a different column
     * set: the job row says which columns were emitted at request time, and an artefact that does not match
     * that record is a file whose evidence does not describe it.
     *
     * <h2>The comparison is against the job's own columns, not the declaration</h2>
     *
     * <p>The question is "did this caller's column set SHRINK since the job was created", so the baseline
     * has to be what the job was created with — {@code export_jobs."columns"} — and not
     * {@code pinnedFields}. A tenant on {@code no-contact-details} creates a four-column report and the
     * job is created with <em>three</em>, because the policy withheld the email at creation. Comparing three
     * against four reports a permission change that never happened, so <b>every</b> generation of a
     * policy-withheld report failed with {@code AUTHORIZATION_REVOKED}: the one report shape whose whole
     * purpose is to be produced with a column missing could never be produced at all.
     *
     * <p>The baseline is the pinned set, so a genuine downgrade — a caller who held
     * {@code analytics:read-organization} at creation and does not at generation — still shrinks the
     * emission and is still refused. Nothing is weakened; the comparison is simply anchored where the
     * evidence is.
     */
    private ResolvedExportColumns reauthorize(TenantScope scope, ExportJobRepository.ExportJobRow job,
                                              TenantContext requester) {
        if (job.reportDefinitionId() == null) {
            throw new ExportJobRefusal("EXPORT_DEFINITION_ABSENT",
                "The job carries no report definition, which export_jobs_report_pin_check makes "
                    + "unrepresentable. This is a repository defect, not a caller error.");
        }
        ReportCatalogRepository.DefinitionRow definition = catalog
            .findDefinition(scope, job.reportDefinitionId())
            .orElseThrow(() -> PolicyDeniedException.tenantOutOfScope("report-definition-out-of-scope",
                scope.organizationId(), job.reportDefinitionId()));
        requireVersionPermissions(requester, definition);

        List<ReportField> declared = pinnedFields(scope, job);
        ExportRedactionPolicy policy = ReportCatalogRepository.policyOf(definition.redactionPolicy());
        ResolvedExportColumns resolved = com.firstelement.exports.redaction.ExportRedactionResolver
            .resolve(declared, policy, definition.requiredPermissions(),
                authorization.effectivePermissions(requester, PolicyResource.ofProject(scope.organizationId(), job.projectId())));

        // The baseline is what the job was CREATED with, which is already redacted. See the javadoc: a
        // policy that withheld a column at creation must not look like a permission that lapsed since.
        List<String> pinnedAtCreation = jobs.columnsOf(scope, job.id());
        java.util.Set<String> lost = new java.util.LinkedHashSet<>(pinnedAtCreation);
        lost.removeAll(resolved.emittedKeys());
        java.util.Set<String> gained = new java.util.LinkedHashSet<>(resolved.emittedKeys());
        gained.removeAll(pinnedAtCreation);
        if (!lost.isEmpty() || !gained.isEmpty()) {
            throw new AuthorizationRevokedAtGenerationException(
                "The requester's column set differs from the one this job was created with: no longer "
                    + "visible to them (withheld: " + lost + "), newly visible: " + gained + ". The export "
                    + "was refused rather than produced with a column set that no longer matches the job "
                    + "row. A policy that withheld a column at CREATION is not a change — the job was "
                    + "pinned without it, and this comparison is against that pinned set.");
        }
        if (resolved.emitted().isEmpty()) {
            throw new EmptyExportRefusal(resolved);
        }
        return resolved;
    }

    // ------------------------------------------------------------------
    // planning
    // ------------------------------------------------------------------

    private ExportQueryPlan planFor(TenantScope scope, ExportJobRepository.ExportJobRow job,
                                    ResolvedExportColumns columns, ExportFormat format) {
        ReportSubject subject = columns.emitted().get(0).subject();
        ReportCatalogRepository.VersionRow version = definitions.pinnedVersion(scope,
            job.reportDefinitionId(), job.reportVersion()).orElseThrow(
                () -> new ReportCatalogNotFoundException("ReportVersion", job.reportVersion()));

        List<ExportFilter> declared = ReportFilterShape.toFilters(json.readMap(version.filtersJson()),
            subject);
        // The caller's requested filters are pushed at PLANNING time, not at creation, so a refusal is
        // recorded on the job as a FAILED attempt with a reason rather than silently dropped.
        ExportJobRepository.ExportJobDetail detail = jobs.findDetail(scope, job.id()).orElse(null);
        List<ExportFilter> requested = requestedFilters(detail, subject);
        List<String> sort = declaredSort(version);

        List<String> lockedPeriodKeys = List.of();
        if (FinanceExportGateRepository.appliesTo(subject) && columns.carriesMoney()) {
            LocalDate[] window = FinanceExportGateRepository.windowOf(
                ExportFilter.merge(declared, requested));
            FinanceExportGateRepository.WindowGate gate = financeGate.gate(scope, job.projectId(),
                window[0], window[1], ReportingExportsModule.Limits.FINANCE_PERIOD_SCAN);
            // A LOCKED period's figures are approved; an incomplete window is refused rather than printed
            // with a silently disagreeing figure.
            financeGate.requireCompleteForLockedPeriods(gate, window[0], window[1]);
            lockedPeriodKeys = gate.lockedPeriodKeys();
        }

        ExportQueryPlan plan = planner.plan(scope, columns, declared, requested, sort, lockedPeriodKeys);
        // Record the filters that actually ran, so the job row answers "what did this export apply"
        // rather than "what did it ask for".
        recordAppliedFilters(scope, job.id(), plan.appliedFiltersAsJson());
        return plan;
    }

    /** The caller's requested filters, read back off the job's stored request. */
    private List<ExportFilter> requestedFilters(ExportJobRepository.ExportJobDetail detail,
                                                ReportSubject subject) {
        if (detail == null) {
            return List.of();
        }
        Object requested = detail.filters().get("__requested");
        if (!(requested instanceof Map<?, ?> map)) {
            return List.of();
        }
        Map<String, Object> typed = new LinkedHashMap<>();
        map.forEach((key, value) -> typed.put(String.valueOf(key), value));
        return ReportFilterShape.toFilters(typed, subject);
    }

    /**
     * The version's declared sort, in the planner's shape.
     *
     * <p>The stored column is a JSON array of field/direction objects, checked by
     * {@code firstelement_json_list_is_sort}; the planner reads {@code "field:asc"} strings. The conversion
     * is {@link com.firstelement.exports.catalog.ReportSortShape}'s, so the storage contract and the
     * planner's contract each stay where they belong.
     *
     * <p>No filtering happens here. The planner refuses a sort naming a column that is not emitted, which is
     * what makes a sort on a withheld field an error rather than an ordering oracle over data the caller may
     * not see — silently dropping the entry would turn that refusal into an unsorted file.
     */
    List<String> declaredSort(ReportCatalogRepository.VersionRow version) {
        if (version.defaultSortJson() == null) {
            return List.of();
        }
        return com.firstelement.exports.catalog.ReportSortShape.fromStored(
            json.readList(version.defaultSortJson()));
    }

    private void recordAppliedFilters(TenantScope scope, UUID jobId, Map<String, Object> applied) {
        // Two writes, not one: the filters answer "what did this export apply", the progress answers
        // "how far along is it". Collapsing them would mean either the job row claims 10% as a side
        // effect of planning, or the applied filters are never recorded at all.
        if (!jobs.recordAppliedFilters(scope, jobId, applied, AuditAttribution.now())) {
            // The row is no longer RUNNING — cancelled or expired while the statement was being planned.
            // The plan is discarded by the caller's next guard rather than written to a job that is no
            // longer producing bytes, which is what keeps a cancelled job from gaining evidence of a
            // statement it never ran.
            throw new ExportJobRefusal("EXPORT_JOB_NOT_RUNNING",
                "The export stopped running while its filters were being applied, so the applied filters "
                    + "were not recorded. The job is in a terminal state and no artefact will be "
                    + "produced for it.");
        }
        recordProgress(scope, jobId, 10);
    }

    private byte[] resolveTemplate(TenantScope scope, ExportJobRepository.ExportJobRow job) {
        UUID templateId = job.documentTemplateId();
        if (templateId == null) {
            throw new ExportJobRefusal("DOCX_TEMPLATE_MISSING",
                "The job is a DOCX export with no document template pin. "
                    + "export_jobs_template_pin_check makes the absence a constraint violation, so this is a "
                    + "repository defect rather than a caller error.");
        }
        return templates.resolve(scope, templateId, job.documentTemplateVersion()).bytes();
    }

    private Map<String, String> metadataFor(Locale locale, ZoneId zone,
                                            ExportJobRepository.ExportJobRow job,
                                            ResolvedExportColumns columns) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("generatedAt", AuditAttribution.now().toString());
        metadata.put("generatedBy", job.requestedByUserId().toString());
        metadata.put("reportKey", job.reportDefinitionId().toString());
        metadata.put("reportVersion", job.reportVersion() == null ? ""
            : String.valueOf(job.reportVersion()));
        metadata.put("reportTitle", job.reportDefinitionId().toString());
        metadata.put("reportTitleSecondary", job.reportDefinitionId().toString());
        metadata.put("locale", locale.toLanguageTag());
        metadata.put("timezone", zone.getId());
        metadata.put("redactionPolicy", columns.policy().wireValue());
        metadata.put("legalNotice", ReportField.isArabic(locale.toLanguageTag())
            ? com.firstelement.exports.format.DocxTemplateRenderer.LEGAL_NOTICE_AR
            : com.firstelement.exports.format.DocxTemplateRenderer.LEGAL_NOTICE_EN);
        return metadata;
    }

    /**
     * The render locale: the REQUEST's pinned locale, else the report definition's declared default.
 *
     * <h2>Why the request wins, and why it used not to be honoured at all</h2>
     *
     * <p>Before Phase 16 the {@code locale} field was bound, stored on nothing, and ignored — the render
     * locale came from {@code report_definitions.localeDefault} unconditionally. That is the silent
     * fallback this change removes: a caller who asked for English received Arabic and had no way to tell
     * from the file that the request had been discarded. A field that is accepted, documented and ignored
     * is worse than one that is refused.
     *
     * <p>So the request is honoured when it names one, and the definition's declaration is the fallback
     * when it does not — which is why the definition carries a default at all. The choice is recorded in
     * the job's authorization snapshot so the produced artefact is explainable from the row that named it.
     *
     * <p>An unrecognised stored value falls back to the definition rather than throwing: generation must
     * not fail over a locale on a job that has already been paid for and is already queued.
     */
    private Locale localeFor(TenantScope scope, ExportJobRepository.ExportJobRow job) {
        ExportLocale requested = requestedLocale(scope, job);
        if (requested != null) {
            return requested.toLocale();
        }
        ExportLocale declared = catalog.findDefinition(scope, job.reportDefinitionId())
            .map(definition -> ExportLocale.fromWireValue(definition.localeDefault()))
            .orElse(null);
        return (declared == null ? ExportLocale.defaultLocale() : declared).toLocale();
    }

    /** The locale the requester pinned, or {@code null} when the request expressed no preference. */
    private ExportLocale requestedLocale(TenantScope scope, ExportJobRepository.ExportJobRow job) {
        ExportJobRepository.ExportJobDetail detail = jobs.findDetail(scope, job.id()).orElse(null);
        if (detail == null || detail.authorizationSnapshot() == null) {
            return null;
        }
        Object requested = detail.authorizationSnapshot().get(REQUESTED_LOCALE);
        return requested == null ? null : ExportLocale.fromWireValue(String.valueOf(requested));
    }

    /**
     * The report's declared timezone.
     *
     * <p>Read from the definition, never the JVM default. Two workers with different zones would produce
     * two different files for one export version, and the checksum comparison that makes a re-download
     * verifiable would then be meaningless.
     */
    private ZoneId zoneFor(TenantScope scope, ExportJobRepository.ExportJobRow job) {
        return catalog.findDefinition(scope, job.reportDefinitionId())
            .map(definition -> {
                try {
                    return ZoneId.of(definition.timezone());
                } catch (java.time.DateTimeException e) {
                    return ZoneId.of("UTC");
                }
            })
            .orElse(ZoneId.of("UTC"));
    }

    // ------------------------------------------------------------------
    // terminal states
    // ------------------------------------------------------------------

    private GenerationOutcome recordTerminal(TenantScope scope, ExportJobRepository.ExportJobRow job,
                                             UUID jobId, int attemptNumber, String workerId,
                                             LocalDateTime startedAt,
                                             ExportEventRepository.AttemptOutcome outcome,
                                             String code, String message) {
        LocalDateTime finishedAt = AuditAttribution.now();
        int durationMs = duration(startedAt, finishedAt);
        boolean failed = outcome == ExportEventRepository.AttemptOutcome.FAILED;
        if (failed) {
            jobs.fail(scope, jobId, code + ": " + message, finishedAt);
        } else {
            jobs.cancel(scope, jobId, job.requestedByUserId(),
                "cancelled during generation: " + code, finishedAt);
        }
        events.recordAttempt(scope, job.projectId(), jobId, attemptNumber, outcome, workerId, startedAt,
            finishedAt, durationMs, null, failed ? code : null, failed ? message : null,
            job.correlationId());
        if (failed) {
            auditJob(job, JOB_FAILED, AuditVocabularies.AuditOutcome.FAILED,
                Map.of("code", code == null ? "EXPORT_GENERATION_FAILED" : code,
                    "attemptNumber", String.valueOf(attemptNumber),
                    "message", message == null ? "" : abbreviate(message)));
            log.warn("export job failed job={} code={} attempt={}", jobId, code, attemptNumber);
            return GenerationOutcome.failed(code == null ? "EXPORT_GENERATION_FAILED" : code, message);
        }
        log.info("export job cancelled during generation job={} code={}", jobId, code);
        return GenerationOutcome.cancelled(code);
    }

    private void auditJob(ExportJobRepository.ExportJobRow job, String action,
                          AuditVocabularies.AuditOutcome outcome, Map<String, Object> metadata) {
        try {
            // Not adminAction(scope, context, ...): a generation-time audit is attributed to the JOB, not
            // to the requester, because the requester is not the principal that ran the work. The
            // requester's identity is on the job row it references.
            audit.admin(AuditWrite.builder(job.organizationId())
                .projectId(job.projectId())
                .actorUserId(job.requestedByUserId())
                .principalType(AuditVocabularies.AuditPrincipalType.JOB)
                .principalName("JOB:export-generation")
                .action(action)
                .resourceType("ExportJob")
                .resourceId(job.id().toString())
                .outcome(outcome)
                .retentionClass(EXPORT_ARTEFACT_CLASS)
                .correlationId(job.correlationId())
                .causationId(job.id().toString())
                .queueName(ExportJobRepository.QUEUE_WIRE_VALUE)
                .jobId(job.id().toString())
                .metadata(metadata)
                .occurredAt(AuditAttribution.now())
                .build());
        } catch (RuntimeException e) {
            // Never propagated: the export's own state is already committed, and an audit insert that
            // fails must not undo a completed or a failed job.
            log.error("Failed to audit an export job terminal state for job {}", job.id(), e);
        }
    }

    /** Records an operator-visible action with the caller's identity, resolved from the thread context. */
    private void audit(TenantContext caller, TenantScope scope, String action, String resourceType,
                       UUID resourceId, AuditVocabularies.AuditOutcome outcome,
                       Map<String, Object> metadata) {
        audit.adminAction(scope, caller, AuditWrite.builder(caller.organizationId())
            .action(action)
            .resourceType(resourceType)
            .resourceId(resourceId.toString())
            .outcome(outcome)
            .metadata(metadata));
    }

    private void emitUsageEvent(TenantScope scope, ExportJobRepository.ExportJobRow job,
                                TenantContext requester, LocalDateTime completedAt) {
        try {
            // FR-7.1 lists "reports/exports" as a declared billable dimension and this is its emit point.
            // REPORT_EXPORT takes no emit-time cost by construction — UsageEventFactory has no cost
            // parameter for it — so the cost comes from the rate card effective on the completion date.
            usageEvents.record(UsageEventFactory.reportExport(
                scope.organizationId(), job.projectId(), job.id(), completedAt,
                completedAt.toLocalDate(), completedAt.toLocalDate(),
                LedgerPrincipal.from(requester), "USD"));
        } catch (RuntimeException e) {
            // The usage ledger is finance's, and a failure there must not fail a completed export. The
            // export's own evidence — checksum, row count, attempt history — is already committed.
            log.error("Failed to record the REPORT_EXPORT usage event for export job {}; the export "
                + "itself completed and its evidence is intact", job.id(), e);
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void recordProgress(TenantScope scope, UUID jobId, int pct) {
        jobs.progress(scope, jobId, pct, AuditAttribution.now());
    }

    private boolean isStillRunning(TenantScope scope, UUID jobId) {
        return jobs.findForDownload(scope, jobId)
            .map(row -> "RUNNING".equals(row.status()))
            .orElse(false);
    }

    private List<String> readStringList(String jsonText) {
        List<Object> raw = json.readList(jsonText);
        List<String> values = new ArrayList<>(raw.size());
        for (Object value : raw) {
            if (value != null) {
                values.add(String.valueOf(value));
            }
        }
        return values;
    }

    /**
     * The message recorded on the job, with the root cause appended.
     *
     * <p>This exists because a wrapper message alone is not diagnosable. During Phase 9 every generation
     * failure recorded the same text — "Column 1 could not be read from the export statement" — with the
     * driver's own {@code PSQLException} swallowed underneath. Twenty-five tests failed with one
     * uninformative sentence, and the reason was visible in neither the job row, the assertion, nor the
     * build log. An operator reading a failed export needs the SQL that actually failed; so does the next
     * engineer. Only the root cause's own message is appended, and only when it adds something.
     */
    private static String diagnosticMessage(RuntimeException e) {
        String own = e.getMessage();
        Throwable cause = e.getCause();
        String root = cause == null ? null : cause.getMessage();
        if (root == null || root.isBlank() || (own != null && own.contains(root))) {
            return own;
        }
        return own == null || own.isBlank() ? root : own + " (cause: " + root + ")";
    }

    private static String errorCodeOf(RuntimeException e) {
        // Read the code off the exception where it declares one, so the job's errorMessage names the real
        // cause rather than a generic failure. Reflection rather than a marker interface because the
        // refusals live in this module's format/plan/persistence packages and a shared interface would be
        // a dependency every one of them takes for a single method.
        for (java.lang.reflect.Method method : e.getClass().getMethods()) {
            if (method.getName().equals("code") && method.getParameterCount() == 0
                && method.getReturnType() == String.class) {
                try {
                    Object value = method.invoke(e);
                    if (value instanceof String code && !code.isBlank()) {
                        return code;
                    }
                } catch (ReflectiveOperationException ignored) {
                    // Fall through to the class-derived code.
                }
            }
        }
        String simpleName = e.getClass().getSimpleName();
        return simpleName.endsWith("Exception") ? simpleName.substring(0, simpleName.length() - 9)
            : "EXPORT_GENERATION_FAILED";
    }

    private static int duration(LocalDateTime from, LocalDateTime to) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, java.time.Duration.between(from, to).toMillis()));
    }

    private static String abbreviate(String message) {
        if (message == null) {
            return "";
        }
        String trimmed = message.trim();
        return trimmed.length() <= 300 ? trimmed : trimmed.substring(0, 300) + "…";
    }

    /**
     * A job the caller may see.
     *
     * <p>Three refusals, all non-disclosing and all producing the same answer as a missing id: outside the
     * tenant, outside the caller's project, or another user's job without the org-wide analytics
     * permission. A caller must not be able to distinguish "exists but is not yours" from "does not
     * exist".
     */
    private ExportJobRepository.ExportJobRow requireVisibleJob(TenantScope scope, TenantContext caller,
                                                               UUID jobId) {
        ExportJobRepository.ExportJobRow job = jobs.findForDownload(scope, jobId)
            .orElseThrow(() -> PolicyDeniedException.tenantOutOfScope("export-job-out-of-scope",
                caller.organizationId(), jobId));
        if (!caller.hasProject(job.projectId())) {
            throw PolicyDeniedException.projectOutOfScope("project-out-of-scope",
                caller.organizationId(), job.projectId());
        }
        if (!job.requestedByUserId().equals(caller.userId())
            && !authorization.allows(caller, Permission.ANALYTICS_READ_ORGANIZATION)) {
            throw PolicyDeniedException.tenantOutOfScope("export-job-out-of-scope",
                caller.organizationId(), jobId);
        }
        return job;
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                "A cancellation must state a reason. A cancellation with no reason is indistinguishable "
                    + "from a mistake, and the reason is the half an operator reviews.");
        }
        String trimmed = reason.trim();
        return trimmed.length() <= 500 ? trimmed : trimmed.substring(0, 500) + "…";
    }

    // ------------------------------------------------------------------
    // outcomes and refusals
    // ------------------------------------------------------------------

    /**
     * The outcome of one generation attempt.
     *
     * <p>{@code message} is part of the outcome rather than only of the job row, because the class's own
     * contract is that "a failed export is a state with a reason an operator can read" — and a caller that
     * holds only this record (an operator replay, a console, a test) would otherwise have a code with no
     * explanation attached to it. The message is the same text the job row carries, and it is bounded there
     * before it is stored.
     */
    public record GenerationOutcome(String outcome,
                                    String code,
                                    Long rowCount,
                                    String checksum,
                                    Long bytes,
                                    Integer durationMs,
                                    String message) {

        static GenerationOutcome completed(long rowCount, String checksum, long bytes, int durationMs) {
            return new GenerationOutcome("COMPLETED", null, rowCount, checksum, bytes, durationMs, null);
        }

        static GenerationOutcome failed(String code, String message) {
            return new GenerationOutcome("FAILED", code, null, null, null, null, message);
        }

        static GenerationOutcome cancelled(String code) {
            return new GenerationOutcome("CANCELLED", code, null, null, null, null, null);
        }

        static GenerationOutcome skipped(String code) {
            return new GenerationOutcome("SKIPPED", code, null, null, null, null, null);
        }

        static GenerationOutcome refused(String code) {
            return new GenerationOutcome("REFUSED", code, null, null, null, null, null);
        }

        /** Whether an artefact exists. */
        public boolean succeeded() {
            return "COMPLETED".equals(outcome);
        }
    }

    /** A permanent failure with a stable code, recorded on the job and the attempt row. */
    public static class ExportJobRefusal extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String code;

        public ExportJobRefusal(String code, String message) {
            super(message);
            this.code = code;
        }

        /** The stable code recorded on {@code export_jobs."errorMessage"} and the attempt row. */
        public String code() {
            return code;
        }
    }

    /**
     * The requester's permissions no longer cover what this job needs.
     *
     * <p>Distinct from a download refusal because the remedy differs: at generation the export was never
     * produced, so the operator can re-request it under the new permissions; at download the artefact
     * exists and the operator must decide what to do with it.
     */
    public static class AuthorizationRevokedAtGenerationException extends ExportJobRefusal {

        private static final long serialVersionUID = 1L;

        public AuthorizationRevokedAtGenerationException(String message) {
            super("AUTHORIZATION_REVOKED", message);
        }
    }

    /**
     * Every column the version declares is withheld from this caller.
     *
     * <p>A refusal rather than an empty file: a file with no columns cannot be told apart from a report of
     * nothing, and that distinction is the whole point of FR-7.6 ("display no data distinctly from
     * zero").
     */
    public static class EmptyExportRefusal extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public EmptyExportRefusal(ResolvedExportColumns resolved) {
            super("Every column this report version declares is withheld from this caller ("
                + resolved.withheldKeys() + "), so the export was refused rather than written with an "
                + "empty header. A file with no columns is indistinguishable from a report of nothing.");
        }

        /** The stable code recorded on the job. */
        public String code() {
            return "EXPORT_ALL_COLUMNS_WITHHELD";
        }
    }

    /**
     * Whether a job is still RUNNING, which is what generation polls for cancellation.
     *
     * <p>Public because the handler and the integration suite both need to observe the state machine
     * without a repository reference, and a method that answers "may I keep going" is clearer at a call
     * site than a repository read followed by a string comparison.
     */
    public boolean isRunning(TenantScope scope, UUID jobId) {
        return isStillRunning(scope, jobId);
    }
}
