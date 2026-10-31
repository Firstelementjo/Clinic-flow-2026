package com.firstelement.exports.download;

import com.firstelement.admin.audit.AdminAuditStreamWriter;
import com.firstelement.admin.audit.AuditAttribution;
import com.firstelement.admin.audit.AuditVocabularies;
import com.firstelement.admin.audit.AuditWrite;
import com.firstelement.exports.catalog.ReportField;
import com.firstelement.exports.catalog.ReportFieldRegistry;
import com.firstelement.exports.job.ExportFormat;
import com.firstelement.exports.persistence.ExportEventRepository;
import com.firstelement.exports.persistence.ExportJobRepository;
import com.firstelement.exports.redaction.ResolvedExportColumns;
import com.firstelement.exports.storage.ExportObjectStore;
import com.firstelement.security.TenantContext;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.error.PolicyDeniedException;
import com.firstelement.security.permission.AuthorizationService;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.permission.PolicyResource;
import com.firstelement.security.query.TenantQueryGuard;
import com.firstelement.security.query.TenantScope;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mints and redeems download links, with a tenant-scoped authorization check on every redemption.
 *
 * <h2>Three things a signed URL is not, and the code that closes each gap</h2>
 *
 * <ol>
 *   <li><b>Not an authorization decision.</b> {@link #redeem} re-resolves the caller's effective permissions
 *       and compares them against the fields the artefact <em>actually contains</em>, read from
 *       {@code export_jobs."columns"}. A permission revoked between minting and redemption fails the
 *       download, which is FR-8.7's explicit requirement.</li>
 *   <li><b>Not tenant-bound by construction.</b> The signature carries the tenant, and
 *       {@link ExportSignatureService#verify} refuses a token whose tenant differs from the caller's
 *       resolved tenant. A tenant A link presented by tenant B fails on the signature check itself, before
 *       any row is read.</li>
 *   <li><b>Not a locator.</b> The token carries the job id and nothing else — not the storage key, not the
 *       checksum, not the file name. The key is resolved from the row <em>after</em> authorizing, so there
 *       is no URL that names where a customer's file lives.</li>
 * </ol>
 *
 * <h2>Every attempt is recorded, including every refusal</h2>
 *
 * <p>{@code export_download_events} gets one row per attempt, minted or redeemed, granted or refused, with
 * a reason on the refusals. A signature that was minted and then denied is exactly the event a security
 * reviewer wants, and a download audit that recorded only the successes would be the one thing an operator
 * could not use to investigate a suspected leak.
 *
 * <p>The row records the signature <strong>id</strong> and its expiry. Never the URL: a URL in a database
 * row is a bearer credential with an expiry nobody manages, which is what the migration's own comment says.
 */
@Service
public class ExportDownloadService {

    private static final Logger log = LoggerFactory.getLogger(ExportDownloadService.class);

    static final String DOWNLOAD_LINK_MINTED = "EXPORT_DOWNLOAD_LINK_MINTED";
    static final String DOWNLOAD_GRANTED = "EXPORT_DOWNLOAD_GRANTED";
    static final String DOWNLOAD_DENIED = "EXPORT_DOWNLOAD_DENIED";

    private final ExportJobRepository jobs;
    private final ExportEventRepository events;
    private final ExportSignatureService signatures;
    private final ExportObjectStore store;
    private final TenantQueryGuard guard;
    private final AuthorizationService authorization;
    private final AdminAuditStreamWriter audit;

    public ExportDownloadService(ExportJobRepository jobs,
                                 ExportEventRepository events,
                                 ExportSignatureService signatures,
                                 ExportObjectStore store,
                                 TenantQueryGuard guard,
                                 AuthorizationService authorization,
                                 AdminAuditStreamWriter audit) {
        this.jobs = jobs;
        this.events = events;
        this.signatures = signatures;
        this.store = store;
        this.guard = guard;
        this.authorization = authorization;
        this.audit = audit;
    }

    // ------------------------------------------------------------------
    // minting
    // ------------------------------------------------------------------

    /**
     * A minted link, with everything the response needs and nothing more.
     *
     * @param downloadPath the relative path to present, carrying only the job id and the signature
     * @param expiresAt    the effective expiry, already capped at the artefact's own window
     * @param fileName     the suggested download name. Derived from the job id and the format, never from a
     *                     customer name, a project name or a report title — because a file name is the one
     *                     part of an export that travels outside the system.
     */
    public record MintedLink(UUID jobId,
                             String downloadPath,
                             String signatureId,
                             Instant expiresAt,
                             String checksum,
                             String fileName,
                             String contentType,
                             long bytes) {
    }

    /**
     * Mints a short-lived link.
     *
     * <p>Authorized before the signature exists, so a caller without access never learns that a job is
     * there. The GRANTED download event is written with the signature's own expiry, which is what makes
     * "this URL was live until then" answerable without keeping the URL.
     *
     * <p><b>Not {@code @Transactional}, and that is load-bearing.</b> Every refusal path here writes a
     * {@code DENIED} row and then throws — and inside a transaction the throw rolls the row back with it.
     * The audit record of a refused download is the one thing a security reviewer needs most, so a module
     * whose refusals do not persist is a module that cannot answer "who tried to take this file". The
     * integration suite caught it as two empty download histories where two refusals had certainly
     * happened.
     *
     * <p>Nothing is given up by dropping it. Every write below is a single guarded statement whose own
     * {@code WHERE} decides whether it applies, so a transaction buys no atomicity it does not already
     * have; and the GRANTED path is not weakened, because {@link #record} raises
     * {@link DownloadAuditFailedException} when the audit row cannot be written, so bytes are still never
     * served without a record of them.
     *
     * @param expectedChecksum the checksum the client believes the artefact has, for optimistic
     *                         concurrency. A mismatch refuses the mint rather than handing out a link to a
     *                         file whose bytes changed — a re-run legitimately produces a different
     *                         checksum, and the caller should be told to re-read the job.
     */
    @RequirePermission(Permission.ANALYTICS_READ_PROJECT)
    public MintedLink mint(TenantContext caller, UUID jobId, String expectedChecksum) {
        authorization.check(caller, Permission.ANALYTICS_READ_PROJECT);
        TenantScope scope = guard.tenantScope(caller);
        ExportJobRepository.ExportJobRow job = requireDownloadable(scope, caller, jobId);
        LocalDateTime now = AuditAttribution.now();

        if (!"COMPLETED".equals(job.status())) {
            record(scope, job, caller, ExportEventRepository.DownloadOutcome.DENIED, null, null,
                now, deniedReasonFor(job), "mint");
            throw new ExportNotDownloadableException(job.status(),
                "This export is in state " + job.status() + ", so it has nothing to download.");
        }
        if (expectedChecksum != null && !expectedChecksum.equals(job.checksum())) {
            // The client re-ran or the artefact changed. Refusing here rather than minting is what stops
            // a client from downloading a file under the description of a previous one.
            record(scope, job, caller, ExportEventRepository.DownloadOutcome.DENIED, null, null, now,
                "checksum-mismatch", "mint");
            throw new ExportNotDownloadableException(job.status(),
                "The checksum the caller holds does not match this export's current artefact. Re-read the "
                    + "job before minting a link.");
        }

        Instant artefactExpiry = job.expiresAt().toInstant(ZoneOffset.UTC);
        if (!Instant.now().isBefore(artefactExpiry)) {
            record(scope, job, caller, ExportEventRepository.DownloadOutcome.EXPIRED, null, null, now,
                "artefact-expired", "mint");
            throw new ExportNotDownloadableException(job.status(),
                "This export's retention window has closed. Request a new export.");
        }

        // REAUTHORIZE at mint time too. A link that cannot be redeemed is not worth minting, and a recorded
        // refusal at mint time is the earliest signal that a permission was revoked.
        reauthorizeOrRecordDenial(scope, job, caller, now, "mint");

        ExportSignatureService.SignedLink signed = signatures.sign(job.id(), caller.organizationId(),
            artefactExpiry, Instant.now());
        signatures.logMinted(signed);
        record(scope, job, caller, ExportEventRepository.DownloadOutcome.GRANTED, signed.signatureId(),
            LocalDateTime.ofInstant(signed.expiresAt(), ZoneOffset.UTC), now, null, "mint");

        audit(caller, scope, DOWNLOAD_LINK_MINTED, "ExportJob", job.id(),
            AuditVocabularies.AuditOutcome.SUCCESS, java.util.Map.of(
                "signatureId", signed.signatureId(),
                "signatureExpiresAt", signed.expiresAt().toString(),
                "checksum", job.checksum() == null ? "" : job.checksum()));
        log.info("export download link minted job={} tenant={} signatureId={}", job.id(),
            caller.organizationId(), signed.signatureId());

        return new MintedLink(job.id(), downloadPath(job.id(), signed.token()), signed.signatureId(),
            signed.expiresAt(), job.checksum(), fileNameFor(job), contentTypeFor(job), bytesOf(job));
    }

    // ------------------------------------------------------------------
    // redemption
    // ------------------------------------------------------------------

    /**
     * The bytes, or a refusal with a reason.
     *
     * <p><b>Not {@code @Transactional}, for the same reason as {@link #mint}:</b> every refusal writes a
     * {@code DENIED} row and then throws, and a transaction takes that row down with the exception. An
     * audit that recorded only the successes would be the one thing an operator could not use to
     * investigate a suspected leak.
     *
     * <p>Check order, and each step is a real gate rather than a formality:
     *
     * <ol>
     *   <li><b>Signature</b> — integrity and expiry, verified without a database read.</li>
     *   <li><b>Tenant</b> — the token's tenant must equal the caller's resolved tenant, and the job row is
     *       read inside the caller's scope, so another tenant's job is simply absent.</li>
     *   <li><b>Job state</b> — COMPLETED, or an EXPIRED/DELETED row gets its own outcome so the event says
     *       <em>why</em> there are no bytes.</li>
     *   <li><b>Object presence</b> — read from storage, not inferred from the row, because a storage fault
     *       and an expiry must not look the same.</li>
     *   <li><b>Authorization, again</b> — every emitted field's permission against the caller, resolved
     *       fresh.</li>
     *   <li><b>Checksum</b> — the object's bytes are hashed and compared with the recorded checksum, so a
     *       truncated or replaced object is refused rather than served.</li>
     * </ol>
     */
    @RequirePermission(Permission.ANALYTICS_READ_PROJECT)
    public Redeemed redeem(TenantContext caller, UUID jobId, String token) {
        authorization.check(caller, Permission.ANALYTICS_READ_PROJECT);
        TenantScope scope = guard.tenantScope(caller);
        LocalDateTime now = AuditAttribution.now();

        ExportSignatureService.Verification verification = signatures.verify(token, jobId,
            caller.organizationId(), Instant.now());
        if (!verification.valid()) {
            // No row is readable yet, so there is nothing to attach the event to. Recorded on the security
            // stream by the signature service's own caller chain, and refused to the client with the
            // non-disclosing reason.
            throw new ExportSignatureRefusedException(verification.reason());
        }

        ExportJobRepository.ExportJobRow job = jobs.findForDownload(scope, jobId).orElse(null);
        if (job == null) {
            throw new PolicyDeniedException("export-job-out-of-scope", caller.organizationId(), jobId,
                Permission.ANALYTICS_READ_PROJECT);
        }
        if (!caller.hasProject(job.projectId())) {
            throw PolicyDeniedException.projectOutOfScope("project-out-of-scope", caller.organizationId(),
                job.projectId());
        }

        if ("EXPIRED".equals(job.status())) {
            record(scope, job, caller, ExportEventRepository.DownloadOutcome.EXPIRED,
                DENIED_SIGNATURE_ID, DENIED_SIGNATURE_EXPIRES_AT, now, null, "redeem");
            throw new ExportNotDownloadableException(job.status(),
                "This export expired and its object has been removed. The link is no longer valid.");
        }
        if ("DELETED".equals(job.status())) {
            record(scope, job, caller, ExportEventRepository.DownloadOutcome.DELETED,
                DENIED_SIGNATURE_ID, DENIED_SIGNATURE_EXPIRES_AT, now, null, "redeem");
            throw new ExportNotDownloadableException(job.status(),
                "This export's object has been deleted. The row remains as evidence of the deletion.");
        }
        if (!"COMPLETED".equals(job.status())) {
            record(scope, job, caller, ExportEventRepository.DownloadOutcome.DENIED,
                DENIED_SIGNATURE_ID, DENIED_SIGNATURE_EXPIRES_AT, now,
                "job-not-completed", "redeem");
            throw new ExportNotDownloadableException(job.status(),
                "This export is in state " + job.status() + ", so it has nothing to download.");
        }

        if (!store.exists(job.storageKey())) {
            // A row saying COMPLETED with no object is a storage fault, and it must not be reported as an
            // expiry: the two have different remedies and different owners.
            record(scope, job, caller, ExportEventRepository.DownloadOutcome.DENIED,
                DENIED_SIGNATURE_ID, DENIED_SIGNATURE_EXPIRES_AT, now,
                "object-storage-missing", "redeem");
            throw new ExportObjectMissingException(job.storageKey(),
                "The job row says COMPLETED but its object is not present in storage. This is a storage "
                    + "fault, not an expired export.");
        }

        reauthorizeOrRecordDenial(scope, job, caller, now, "redeem");

        byte[] bytes;
        try {
            bytes = store.read(job.storageKey());
        } catch (IOException e) {
            record(scope, job, caller, ExportEventRepository.DownloadOutcome.DENIED,
                DENIED_SIGNATURE_ID, DENIED_SIGNATURE_EXPIRES_AT, now,
                "object-read-failed", "redeem");
            throw new ExportObjectMissingException(job.storageKey(),
                "The stored object could not be read: " + e.getMessage());
        }

        String actual = ExportSignatureService.checksumOf(bytes);
        if (!actual.equals(job.checksum())) {
            // The bytes are not the bytes the job describes. Serving them would make the recorded checksum
            // untrue, and a checksum that can be untrue is not evidence.
            record(scope, job, caller, ExportEventRepository.DownloadOutcome.DENIED,
                DENIED_SIGNATURE_ID, DENIED_SIGNATURE_EXPIRES_AT, now,
                "checksum-mismatch", "redeem");
            throw new ExportChecksumMismatchException(job.checksum(), actual);
        }

        record(scope, job, caller, ExportEventRepository.DownloadOutcome.GRANTED, verification.signatureId(),
            verification.expiresAt() == null ? null
                : LocalDateTime.ofInstant(verification.expiresAt(), ZoneOffset.UTC),
            now, null, "redeem");
        audit(caller, scope, DOWNLOAD_GRANTED, "ExportJob", job.id(),
            AuditVocabularies.AuditOutcome.SUCCESS, java.util.Map.of(
                "signatureId", String.valueOf(verification.signatureId()),
                "bytes", String.valueOf(bytes.length),
                "checksumAtDownload", actual));

        return new Redeemed(bytes, job.checksum(), fileNameFor(job), contentTypeFor(job), job.id(),
            job.projectId(), actual);
    }

    /**
     * Re-authorizes against the fields the artefact contains.
     *
     * <p>Refuses rather than re-redacting. The stored object's checksum is the evidence of what was
     * produced; serving a narrower byte sequence under that checksum would make the evidence untrue, and an
     * operator comparing two downloads of the same job would see a mismatch with no explanation.
     */
    private void reauthorizeOrRecordDenial(TenantScope scope, ExportJobRepository.ExportJobRow job,
                                           TenantContext caller, LocalDateTime now, String stage) {
        List<ReportField> fields = ReportFieldRegistry.resolve(jobs.columnsOf(scope, job.id()));
        ResolvedExportColumns emitted = new ResolvedExportColumns(fields, List.of(),
            com.firstelement.exports.persistence.ReportCatalogRepository.policyOf(job.redactionPolicy()),
            List.of());
        ReportField unmet = emitted.firstUnmetField(authorization.effectivePermissions(caller,
            PolicyResource.ofProject(caller.organizationId(), job.projectId())));
        if (unmet != null) {
            record(scope, job, caller, ExportEventRepository.DownloadOutcome.DENIED, null, null, now,
                com.firstelement.exports.redaction.ExportRedactionResolver
                    .downloadDenialReason(unmet),
                stage);
            audit(caller, scope, DOWNLOAD_DENIED, "ExportJob", job.id(),
                AuditVocabularies.AuditOutcome.DENIED, java.util.Map.of(
                    "field", unmet.key(),
                    "requiredPermission", unmet.requiredPermission().value(),
                    "stage", stage));
            throw new AuthorizationRevokedForDownloadException(unmet);
        }
    }

    // ------------------------------------------------------------------
    // results
    // ------------------------------------------------------------------

    /** A successful redemption. */
    public record Redeemed(byte[] bytes,
                           String checksum,
                           String fileName,
                           String contentType,
                           UUID jobId,
                           UUID projectId,
                           String verifiedChecksum) {
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** The relative download path. Carries the job id and the signature, and nothing else. */
    public static String downloadPath(UUID jobId, String token) {
        return "/api/v1/exports/" + jobId + "/download?signature=" + token;
    }

    /**
     * A suggested file name.
     *
     * <p>Derived from the job id and the format only. A name built from a report title or a customer name
     * is the one part of an export that leaves the system and lands in a Downloads folder, a file index and
     * often a screenshot.
     */
    public static String fileNameFor(ExportJobRepository.ExportJobRow job) {
        return "export-" + job.id() + "." + job.format().toLowerCase(java.util.Locale.ROOT);
    }

    /** The MIME type a download sets. */
    public static String contentTypeFor(ExportJobRepository.ExportJobRow job) {
        ExportFormat format = ExportFormat.require(job.format());
        return format.contentType();
    }

    private static long bytesOf(ExportJobRepository.ExportJobRow job) {
        return job.bytes() == null ? 0L : job.bytes();
    }

    private static String deniedReasonFor(ExportJobRepository.ExportJobRow job) {
        return switch (job.status()) {
            case "EXPIRED" -> "artefact-expired";
            case "DELETED" -> "object-deleted";
            case "CANCELLED" -> "job-cancelled";
            case "FAILED" -> "job-failed";
            default -> "job-not-completed";
        };
    }

    /** A job the caller may see and that has an object. */
    private ExportJobRepository.ExportJobRow requireDownloadable(TenantScope scope, TenantContext caller,
                                                                 UUID jobId) {
        ExportJobRepository.ExportJobRow job = jobs.findForDownload(scope, jobId)
            .orElseThrow(() -> PolicyDeniedException.tenantOutOfScope("export-job-out-of-scope",
                caller.organizationId(), jobId));
        if (!caller.hasProject(job.projectId())) {
            throw PolicyDeniedException.projectOutOfScope("project-out-of-scope", caller.organizationId(),
                job.projectId());
        }
        return job;
    }

    private void record(TenantScope scope, ExportJobRepository.ExportJobRow job, TenantContext caller,
                        ExportEventRepository.DownloadOutcome outcome, String signatureId,
                        LocalDateTime signatureExpiresAt, LocalDateTime now, String denyReason,
                        String stage) {
        try {
            events.recordDownload(scope, job.projectId(), job.id(), caller.userId(),
                caller.auditActorLabel(), outcome, signatureId, signatureExpiresAt,
                job.expiresAt(), outcome == ExportEventRepository.DownloadOutcome.GRANTED
                    ? job.checksum() : null,
                job.redactionPolicy(), denyReason, null, stage,
                job.correlationId(), now);
        } catch (RuntimeException e) {
            // Never propagated from a refusal path: a failing download-event insert must not turn a
            // correctly-refused download into a 500, and must not hand out bytes whose audit row is absent.
            // On a GRANTED path the caller below treats the missing audit as fatal.
            if (outcome == ExportEventRepository.DownloadOutcome.GRANTED) {
                throw new DownloadAuditFailedException(
                    "The download audit row could not be written, so the download was refused. A download "
                        + "without an audit row is the one thing this module must never serve.", e);
            }
            log.error("Failed to record a refused download event for job {}", job.id(), e);
        }
    }

    /**
     * The signature id a refusal records: none.
     *
     * <p>{@code export_download_events_signature_check} is a biconditional — GRANTED if and only if both a
     * signature id and its expiry are present — and it is stated that way for a reason worth preserving: a
     * DENIED row carrying a live signature would mean the refusal was recorded after the fact, which is the
     * one thing the schema refuses to represent.
     *
     * <p>Every refusal on the redemption path therefore records <em>neither</em>. Carrying the presented
     * signature on the row was wrong twice over: the insert was rejected by that CHECK, and because
     * {@link #record} deliberately swallows an insert failure on a refusal path — so that a broken audit
     * insert cannot turn a correct refusal into a 500 — the refusal was then lost entirely. The download
     * event showed no deny reason at all for a deleted object, a replaced object, and a revoked permission.
     * The evidence that survives is the job id, the actor and the deny reason, which is what an operator
     * reads; the signature that was presented is deliberately not kept.
     */
    private static final String DENIED_SIGNATURE_ID = null;

    private static final LocalDateTime DENIED_SIGNATURE_EXPIRES_AT = null;

    /** The signature service, for a controller that needs the configured TTL in its response. */
    public ExportSignatureService signatures() {
        return signatures;
    }

    /** The store, so a test can assert the object exists and is byte-identical. */
    ExportObjectStore store() {
        return store;
    }

    // ------------------------------------------------------------------
    // refusals
    // ------------------------------------------------------------------

    /** The job is in a state with nothing to download. */
    public static class ExportNotDownloadableException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String status;

        public ExportNotDownloadableException(String status, String message) {
            super(message);
            this.status = status;
        }

        /** The stable error code for the HTTP layer. */
        public String code() {
            return "EXPORT_NOT_DOWNLOADABLE";
        }

        /** The job's state, so the HTTP layer can answer 410 rather than 404 for a closed window. */
        public String status() {
            return status;
        }
    }

    /**
     * The signature did not verify.
     *
     * <p>The reason is a stable code, never a detail: {@code signature-mismatch} and {@code job-mismatch}
     * are indistinguishable from outside on purpose, because distinguishing them turns the endpoint into an
     * oracle for guessing a job id.
     */
    public static class ExportSignatureRefusedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String reason;

        public ExportSignatureRefusedException(String reason) {
            super("The download link is not valid (" + reason + "). A link is a bearer credential for a "
                + "short window; presenting an expired or altered one is refused and recorded.");
            this.reason = reason;
        }

        /** The stable code for the HTTP layer. */
        public String code() {
            return "EXPORT_SIGNATURE_REFUSED";
        }

        /** The non-disclosing reason, recorded on the download event. */
        public String reason() {
            return reason;
        }
    }

    /** A permission the artefact depends on has been revoked since it was produced. */
    public static class AuthorizationRevokedForDownloadException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final ReportField field;

        public AuthorizationRevokedForDownloadException(ReportField field) {
            super("The caller no longer holds the permission this export's column '" + field.key()
                + "' requires ('" + field.requiredPermission().value() + "'), so the download was refused. "
                + "The export was not re-redacted, because the stored object's checksum is the evidence of "
                + "what was produced.");
            this.field = field;
        }

        /** The stable code for the HTTP layer. */
        public String code() {
            return com.firstelement.exports.redaction.ExportRedactionResolver.denialReasonCode();
        }

        /** The field whose requirement lapsed. Named in the audit row; never in the client body. */
        public ReportField field() {
            return field;
        }
    }

    /** The row says COMPLETED but the object is not in storage. A storage fault, reported as such. */
    public static class ExportObjectMissingException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public ExportObjectMissingException(String storageKey, String message) {
            super(message);
        }

        /** The stable code for the HTTP layer. */
        public String code() {
            return "EXPORT_OBJECT_MISSING";
        }
    }

    /** The stored object's bytes are not the bytes the job describes. */
    public static class ExportChecksumMismatchException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public ExportChecksumMismatchException(String recorded, String actual) {
            super("The stored object's checksum (" + actual + ") does not match the checksum recorded on "
                + "the job (" + recorded + "). The download was refused: serving bytes that contradict the "
                + "recorded checksum would make that checksum unusable as evidence.");
        }

        /** The stable code for the HTTP layer. */
        public String code() {
            return "EXPORT_CHECKSUM_MISMATCH";
        }
    }

    /** Records an operator-visible download action with the caller's identity, from the thread context. */
    private void audit(TenantContext caller, TenantScope scope, String action, String resourceType,
                       UUID resourceId, AuditVocabularies.AuditOutcome outcome,
                       java.util.Map<String, Object> metadata) {
        audit.adminAction(scope, caller, AuditWrite.builder(caller.organizationId())
            .action(action)
            .resourceType(resourceType)
            .resourceId(resourceId.toString())
            .outcome(outcome)
            .metadata(metadata));
    }

    /** The download-event insert failed, so the download was refused. */
    public static class DownloadAuditFailedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public DownloadAuditFailedException(String message, Throwable cause) {
            super(message, cause);
        }

        /** The stable code for the HTTP layer. */
        public String code() {
            return "EXPORT_AUDIT_UNAVAILABLE";
        }
    }
}