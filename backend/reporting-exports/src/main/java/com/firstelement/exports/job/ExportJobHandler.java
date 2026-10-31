package com.firstelement.exports.job;

import com.firstelement.exports.persistence.ExportJobRepository;
import com.firstelement.security.TenantContext;
import com.firstelement.security.job.TenantScopedExecutor;
import com.firstelement.security.query.TenantScope;
import com.firstelement.worker.queue.JobEnvelope;
import com.firstelement.worker.queue.JobHandler;
import com.firstelement.worker.queue.QueueName;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The durable worker entry point for export generation.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>PROJECT_REQUIREMENTS §7 lists "no synchronous AI, document parsing, large report, or export work on
 * request threads" as a non-functional requirement, and FR-8.5 makes a large export an asynchronous
 * {@code ExportJob}. So the request thread creates a row and returns; this handler is what actually produces
 * the bytes, and it runs on the dispatcher.
 *
 * <p>{@link ExportJobService#generate} is the real implementation and is reachable without the dispatcher
 * (an operator replay, the integration suites). This class is a thin adapter — envelope to scope, scope to
 * context, context to service — and deliberately holds no generation logic, because logic reachable from
 * two entry points is logic that will eventually be reachable from the one that skips a check.
 *
 * <h2>The tenant scope is the job's own, never the payload's</h2>
 *
 * <p>{@link JobEnvelope#organizationId()} is the tenant the dispatcher <em>claimed for</em>, not a value
 * from the payload, and {@code TenantScopedExecutor} binds it before this runs. The job id is then resolved
 * inside that scope, so an envelope naming another tenant's job produces no row rather than another
 * tenant's bytes.
 *
 * <h2>Reauthorization happens here, not at claim time</h2>
 *
 * <p>The handler re-resolves the <em>requester's</em> context from the job row before generating, which is
 * what FR-8.7's "reauthorize at job start" means operationally: the person who queued the export is looked
 * up again, and their current permissions decide the column set. A permission revoked while the job sat in
 * the queue is honoured.
 */
@Component
public class ExportJobHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(ExportJobHandler.class);

    /**
     * The queue this handler drains.
     *
     * <p>{@code DOCUMENT_PROCESSING}, the BULK-class queue the worker runtime declared for stored-document
     * work. Its policy is 3 attempts with a 10-second base, which is right for an export: a template naming
     * an unapproved field fails identically on attempt two and should become a visible FAILED job rather
     * than three minutes of retries.
     *
     * <p>A dedicated {@code EXPORT_RENDER} queue is requested in the Phase-9 report rather than invented
     * here, because {@code QueueName} and {@code QueuePolicyCatalog} belong to {@code :backend:worker-runtime}.
     */
    private static final QueueName QUEUE = QueueName.require(ExportJobRepository.QUEUE_WIRE_VALUE);

    private final ExportJobService jobs;
    private final TenantScopedExecutor scopedExecutor;

    public ExportJobHandler(ExportJobService jobs, TenantScopedExecutor scopedExecutor) {
        this.jobs = jobs;
        this.scopedExecutor = scopedExecutor;
    }

    @Override
    public QueueName queue() {
        return QUEUE;
    }

    /**
     * One attempt.
     *
     * @return a short, redacted summary recorded as the attempt's detail. Never customer content: the
     *         summary is the terminal state, the row count and the byte count, all of which are already on
     *         the job row, so nothing new is disclosed by repeating them into the queue history.
     */
    @Override
    public String handle(TenantContext context, JobEnvelope job) {
        UUID jobId = resolveJobId(job);
        TenantScope scope = new TenantScope(job.organizationId(), job.projectId());

        // The requester is re-resolved from the job row, not taken from the envelope. This is the
        // "reauthorize at job start" step: whoever asked for the export is looked up again and their CURRENT
        // permissions decide the column set.
        return scopedExecutor.execute(context, "export.generate", () -> {
            ExportJobService.GenerationOutcome outcome = jobs.generate(context, scope, jobId, workerId());
            log.info("export attempt finished job={} outcome={} code={}", jobId, outcome.outcome(),
                outcome.code());
            return outcome.outcome() + (outcome.code() == null ? "" : ":" + outcome.code())
                + " rows=" + (outcome.rowCount() == null ? "-" : outcome.rowCount())
                + " bytes=" + (outcome.bytes() == null ? "-" : outcome.bytes());
        });
    }

    /**
     * The export job id from the envelope.
     *
     * <p>Read from a named field and parsed strictly. A payload that names no job is a permanent failure
     * rather than a silent no-op: an intent the queue could not act on is work the domain transaction
     * promised and the product cannot perform.
     */
    private static UUID resolveJobId(JobEnvelope envelope) {
        UUID jobId = envelope.payloadUuid("exportJobId");
        if (jobId == null) {
            jobId = envelope.aggregateId();
        }
        if (jobId == null) {
            throw new ExportJobService.ExportJobRefusal("EXPORT_JOB_ID_ABSENT",
                "The outbox intent names no export job. The enqueue derives the intent from the job row, so "
                    + "this is a producer defect rather than a caller error.");
        }
        return jobId;
    }

    /** The worker identity, for the attempt row's {@code workerId}. */
    private String workerId() {
        return "export-handler";
    }

    /** Whether this handler's queue is the one an operator replay would target. */
    public String queueWireValue() {
        return QUEUE.wireValue();
    }

    /** The service, so the retention path can re-run a job without the dispatcher. */
    ExportJobService service() {
        return jobs;
    }

    /** The executor, so a test can prove the scope is bound before generation. */
    TenantScopedExecutor executor() {
        return scopedExecutor;
    }

    /** Whether the handler is declared for the queue an export intent publishes. */
    @Transactional(readOnly = true)
    public boolean drainsExports() {
        return QUEUE.wireValue().equals(ExportJobRepository.QUEUE_WIRE_VALUE);
    }
}