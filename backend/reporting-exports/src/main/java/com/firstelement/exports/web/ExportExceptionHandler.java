package com.firstelement.exports.web;

import com.firstelement.exports.catalog.ReportCatalogConflictException;
import com.firstelement.exports.catalog.ReportCatalogNotFoundException;
import com.firstelement.exports.catalog.ReportFieldRegistry;
import com.firstelement.exports.download.ExportDownloadService;
import com.firstelement.exports.format.DocxTemplateException;
import com.firstelement.exports.job.ExportGenerationRunner;
import com.firstelement.exports.job.ExportJobService;
import com.firstelement.exports.persistence.FinanceExportGateRepository;
import com.firstelement.exports.persistence.ReportCatalogRepository;
import com.firstelement.exports.plan.ExportQueryPlanner;
import com.firstelement.exports.plan.FilterNotPushableException;
import com.firstelement.exports.retention.ExportRetentionService;
import com.firstelement.kernel.error.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The export module's own error mapping.
 *
 * <h2>Why a scoped advice and not just the global one</h2>
 *
 * <p>{@code app-bootstrap}'s {@code ErrorHandlingAdvice} catches {@code Exception} and answers
 * {@code 500 INTERNAL_ERROR}. That is correct as a floor, and useless for a client that asked for an export
 * with an unpushable filter: "something went wrong" tells them nothing and tells an operator nothing either.
 *
 * <p>So each failure this module defines gets its own status and its own code:
 *
 * <table>
 *   <caption>Failure to status</caption>
 *   <tr><th>Failure</th><th>Status</th><th>Why</th></tr>
 *   <tr><td>an unpushable filter</td><td>422</td>
 *       <td>The request is well-formed and permanently unsatisfiable. 400 would suggest a syntax problem;
 *           409 would suggest a retry might help.</td></tr>
 *   <tr><td>an unapproved template field</td><td>422</td>
 *       <td>The template is configuration that must be fixed. The job fails; this is the authoring-time
 *           preview of the same condition.</td></tr>
 *   <tr><td>a sort on a non-emitted column</td><td>422</td>
 *       <td>Same class of problem as a filter that cannot be pushed.</td></tr>
 *   <tr><td>an undeclared report column</td><td>422</td>
 *       <td>The definition names something the registry does not declare.</td></tr>
 *   <tr><td>a locked finance period with no snapshot</td><td>409</td>
 *       <td>A genuine conflict with approved figures. The remedy is a recomputation or a narrower window,
 *           both of which are operator actions.</td></tr>
 *   <tr><td>an expired or deleted artefact</td><td>410</td>
 *       <td>It existed and is gone. 404 would say "never existed", which is a different fact an operator
 *           needs to distinguish.</td></tr>
 *   <tr><td>a revoked permission at download</td><td>403</td>
 *       <td>The caller authenticated and holds the base permission; what lapsed is a column requirement.
 *           That is a real authorization denial, not a missing row.</td></tr>
 *   <tr><td>a catalogue conflict</td><td>409</td>
 *       <td>A lost publication race or a version that is not DRAFT.</td></tr>
 *   <tr><td>purge requested while disabled</td><td>409</td>
 *       <td>The capability exists and is off. The body says what it would have done.</td></tr>
 * </table>
 *
 * <h2>No body ever names another tenant</h2>
 *
 * <p>{@link com.firstelement.security.error.PolicyDeniedException} is already handled by
 * {@code SecurityExceptionAdvice} in {@code :backend:tenant-security}, which renders it as a 404 identical
 * to a missing row. This advice does not handle it, and deliberately does not shadow it: two handlers
 * competing for one exception is how the non-disclosing answer gets replaced by a disclosing one.
 *
 * <h2>Why the order is declared rather than discovered</h2>
 *
 * <p>Spring resolves an escaping exception by walking the advice beans <em>in order</em> and stopping at
 * the first one holding a handler for the thrown type. This advice declared no {@code @Order}, so it
 * defaulted to {@link Ordered#LOWEST_PRECEDENCE} — <b>the same value as the floor</b> — and which of the two
 * answered every export refusal was decided by component-scan registration order, which is the line order
 * of a module list in one {@code build.gradle.kts}. A handler that is registered but shadowed reads
 * identically to one that is absent, so the fix was invisible: the refusals in the table above were all
 * rendering correctly at the time this was found, and would have become {@code 500 INTERNAL_ERROR} the day
 * somebody moved a line in that file.
 *
 * <p>So {@link #ORDER} places this advice {@link Ordered#LOWEST_PRECEDENCE} {@code - 200}: ahead of
 * {@code CrmExceptionAdvice} at {@code - 100}, behind {@code SecurityExceptionAdvice} at
 * {@link Ordered#HIGHEST_PRECEDENCE}, and behind the floor, which is the only advice holding
 * {@code @ExceptionHandler(Exception.class)} and must stay last. The value is stated as a named constant
 * rather than an inline arithmetic expression for the same reason {@code CrmExceptionAdvice} does it that
 * way: the ladder is something an operator reads, and {@code check-advice-order.ps1} resolves a named
 * constant but reports an unresolvable expression as {@code FE-ADVICE-006}.
 *
 * <h2>Why sorting ahead of {@code CrmExceptionAdvice} is safe, stated rather than assumed</h2>
 *
 * <p>An export advice consulted before the CRM family could in principle swallow a CRM refusal, because
 * this advice handles {@link IllegalArgumentException} and {@link IllegalStateException} — two types broad
 * enough to appear anywhere. Two independent facts make it safe today, and both are recorded here because
 * the second is the one that would stop being true silently:
 *
 * <ol>
 *   <li>{@code com.firstelement.crm.error.CrmDomainException} extends {@link RuntimeException} directly,
 *       <em>not</em> {@link IllegalArgumentException} — so the two families do not overlap at all, and the
 *       {@link RuntimeException} handlers here cannot claim a CRM refusal however early this advice sorts.</li>
 *   <li>{@code @RestControllerAdvice(assignableTypes = ExportController.class)} scopes every handler in this
 *       class to the one controller that owns these exception types. Even a {@code RuntimeException} from a
 *       CRM controller cannot reach it. <b>This is the load-bearing guarantee.</b> If the selector is ever
 *       widened or removed — the {@code FE-ADVICE-005} advisory the gate records is exactly the signal that
 *       this narrowing exists — then {@link #ORDER} must move behind {@code CrmExceptionAdvice}, or this
 *       advice will start answering for controllers it does not own.</li>
 * </ol>
 */
@Order(ExportExceptionHandler.ORDER)
@RestControllerAdvice(assignableTypes = ExportController.class)
public class ExportExceptionHandler {

    /**
     * {@code Ordered.LOWEST_PRECEDENCE - 200}: ahead of the CRM family, behind security, behind the floor.
     *
     * <p>Named rather than inlined so the ladder has one addressable value per advice, and so
     * {@code ExportExceptionHandlerOrderHttpTest} can assert the relationship between three real numbers
     * instead of asserting that a string is present in a file.
     */
    public static final int ORDER = Ordered.LOWEST_PRECEDENCE - 200;

    private static final Logger log = LoggerFactory.getLogger(ExportExceptionHandler.class);

    /** A filter the planner refuses to push, which must reach the {@code WHERE} clause to be true. */
    @ExceptionHandler(FilterNotPushableException.class)
    public ResponseEntity<ErrorResponse> filterNotPushable(FilterNotPushableException ex,
                                                           HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.code(), ex.getMessage(),
            Map.of("filterKey", String.valueOf(ex.filterKey()), "retryable", false), request);
    }

    /** A sort the plan cannot project — including a sort on a column withheld from this caller. */
    @ExceptionHandler(ExportQueryPlanner.SortNotProjectableException.class)
    public ResponseEntity<ErrorResponse> sortNotProjectable(
        ExportQueryPlanner.SortNotProjectableException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.code(), ex.getMessage(),
            Map.of("fieldKey", ex.fieldKey(), "retryable", false), request);
    }

    /** A template referencing a field outside the approved set. */
    @ExceptionHandler(DocxTemplateException.class)
    public ResponseEntity<ErrorResponse> docxTemplate(DocxTemplateException ex,
                                                      HttpServletRequest request) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", ex.reason().name());
        details.put("offendingPlaceholders", ex.offendingNames());
        details.put("retryable", false);
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.code(), ex.getMessage(), details, request);
    }

    /** A report column the registry does not declare, or a version mixing two subjects. */
    @ExceptionHandler({ReportFieldRegistry.UnknownReportFieldException.class,
        ReportFieldRegistry.MixedSubjectException.class})
    public ResponseEntity<ErrorResponse> unknownColumn(RuntimeException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "REPORT_COLUMN_UNDECLARED", ex.getMessage(),
            Map.of("retryable", false), request);
    }

    /** A locked finance period whose window is not fully snapshotted. */
    @ExceptionHandler(FinanceExportGateRepository.LockedPeriodIncompleteException.class)
    public ResponseEntity<ErrorResponse> lockedPeriod(
        FinanceExportGateRepository.LockedPeriodIncompleteException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.code(), ex.getMessage(),
            Map.of("lockedPeriods", ex.gate().lockedPeriodKeys(),
                "bucketsWithoutSnapshot", ex.gate().bucketsWithoutSnapshot(),
                "retryable", false),
            request);
    }

    /** An artefact that is expired, deleted, cancelled or still running. */
    @ExceptionHandler(ExportDownloadService.ExportNotDownloadableException.class)
    public ResponseEntity<ErrorResponse> notDownloadable(
        ExportDownloadService.ExportNotDownloadableException ex, HttpServletRequest request) {
        // 410 for a closed window and 409 for a state that may still change: the client must be able to
        // tell "gone for good" from "not yet", because the remedy differs.
        HttpStatus status = "EXPIRED".equals(ex.status()) || "DELETED".equals(ex.status())
            ? HttpStatus.GONE : HttpStatus.CONFLICT;
        return build(status, ex.code(), ex.getMessage(),
            Map.of("status", ex.status(), "retryable",
                Boolean.valueOf("QUEUED".equals(ex.status()) || "RUNNING".equals(ex.status()))),
            request);
    }

    /** A signature that did not verify. Never explains which part failed beyond a stable code. */
    @ExceptionHandler(ExportDownloadService.ExportSignatureRefusedException.class)
    public ResponseEntity<ErrorResponse> signatureRefused(
        ExportDownloadService.ExportSignatureRefusedException ex, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, ex.code(), ex.getMessage(),
            Map.of("reason", ex.reason(), "retryable", false), request);
    }

    /** A permission the artefact depends on has lapsed since it was produced. */
    @ExceptionHandler(ExportDownloadService.AuthorizationRevokedForDownloadException.class)
    public ResponseEntity<ErrorResponse> downloadRevoked(
        ExportDownloadService.AuthorizationRevokedForDownloadException ex, HttpServletRequest request) {
        // The field key is named in the body: it is metadata about the caller's own permission set, and
        // telling them which requirement lapsed is the difference between a fixable problem and a support
        // ticket. The withheld VALUE never existed in the response, so nothing is disclosed.
        return build(HttpStatus.FORBIDDEN, ex.code(), ex.getMessage(),
            Map.of("fieldKey", ex.field().key(),
                "requiredPermission", ex.field().requiredPermission().value(),
                "retryable", false),
            request);
    }

    /** A row says COMPLETED but the object is gone. A storage fault, reported as one. */
    @ExceptionHandler(ExportDownloadService.ExportObjectMissingException.class)
    public ResponseEntity<ErrorResponse> objectMissing(
        ExportDownloadService.ExportObjectMissingException ex, HttpServletRequest request) {
        log.error("An export download was refused because its stored object is missing", ex);
        return build(HttpStatus.CONFLICT, ex.code(), ex.getMessage(),
            Map.of("retryable", Boolean.FALSE), request);
    }

    /** The stored bytes contradict the recorded checksum. */
    @ExceptionHandler(ExportDownloadService.ExportChecksumMismatchException.class)
    public ResponseEntity<ErrorResponse> checksumMismatch(
        ExportDownloadService.ExportChecksumMismatchException ex, HttpServletRequest request) {
        // An ERROR, not a conflict: the stored artefact no longer matches its own record, and an operator
        // must look at it. The recorded and actual digests are NOT in the body — a checksum is a locator
        // for the file, and a client that failed this check should not be handed one.
        log.error("An export download was refused because the stored object's checksum did not match", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ex.code(), ex.getMessage(),
            Map.of("retryable", Boolean.FALSE), request);
    }

    /** The download-event row could not be written, so the download was refused. */
    @ExceptionHandler(ExportDownloadService.DownloadAuditFailedException.class)
    public ResponseEntity<ErrorResponse> auditUnavailable(
        ExportDownloadService.DownloadAuditFailedException ex, HttpServletRequest request) {
        log.error("An export download was refused because its audit row could not be written", ex);
        return build(HttpStatus.SERVICE_UNAVAILABLE, ex.code(), ex.getMessage(),
            Map.of("retryable", Boolean.TRUE), request);
    }

    /** Every column withheld from this caller. */
    @ExceptionHandler(ExportJobService.EmptyExportRefusal.class)
    public ResponseEntity<ErrorResponse> allColumnsWithheld(ExportJobService.EmptyExportRefusal ex,
                                                            HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, ex.code(), ex.getMessage(),
            Map.of("retryable", Boolean.FALSE), request);
    }

    /** The requester lost a permission between queuing and generation. */
    @ExceptionHandler(ExportJobService.AuthorizationRevokedAtGenerationException.class)
    public ResponseEntity<ErrorResponse> generationRevoked(
        ExportJobService.AuthorizationRevokedAtGenerationException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, ex.code(), ex.getMessage(),
            Map.of("retryable", Boolean.FALSE), request);
    }

    /** A row the caller may not see, rendered identically to a genuinely missing one. */
    @ExceptionHandler(ReportCatalogNotFoundException.class)
    public ResponseEntity<ErrorResponse> catalogNotFound(ReportCatalogNotFoundException ex,
                                                         HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.code(), ex.getMessage(),
            Map.of("retryable", Boolean.FALSE), request);
    }

    /**
 * A report version pinned by a request that has never been published.
 *
 * <p>409, not 404: the version row exists and is this tenant's, so the caller is told what is wrong and
 * what to do. The catalogue publishes the same decision on the version's {@code exportable} field, so a
 * client that reads only {@code /catalogue} never reaches this; the handler exists for a client that
 * guessed a version number, and for a version that was a DRAFT when the catalogue was read and is still one
 * now.
 */
    @ExceptionHandler(com.firstelement.exports.catalog.ReportVersionNotPublishedException.class)
    public ResponseEntity<ErrorResponse> versionNotPublished(
        com.firstelement.exports.catalog.ReportVersionNotPublishedException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.code(), ex.getMessage(),
            Map.of("versionNumber", Integer.valueOf(ex.versionNumber()),
                "state", ex.state(), "retryable", Boolean.FALSE), request);
    }

    /**
     * A lost optimistic-concurrency race on a definition's labels.
     *
     * <p>Phase 31 added the route that reaches this, and with it the observation: the exception extends
     * {@link RuntimeException} directly, so before this handler it reached the application's {@code Exception}
     * floor and answered {@code 500 INTERNAL_ERROR}. A rename that lost a race is a conflict, not a server
     * fault, and a 500 for it pages somebody.
     */
    @ExceptionHandler(ReportCatalogRepository.ConcurrentDefinitionChangeException.class)
    public ResponseEntity<ErrorResponse> concurrentDefinitionChange(
        ReportCatalogRepository.ConcurrentDefinitionChangeException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "REPORT_CATALOG_CONCURRENT_CHANGE", ex.getMessage(),
            Map.of("retryable", Boolean.FALSE), request);
    }

    /** A lost publication race, or a version that is not in a state the transition accepts. */
    @ExceptionHandler(ReportCatalogConflictException.class)
    public ResponseEntity<ErrorResponse> catalogConflict(ReportCatalogConflictException ex,
                                                         HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.code(), ex.getMessage(),
            Map.of("retryable", Boolean.valueOf(ex.isPublicationRace())), request);
    }

    /** A row cap, an unsupported format, or a storage write failure. */
    @ExceptionHandler({ExportGenerationRunner.RowCapExceededException.class,
        ExportGenerationRunner.UnsupportedExportFormatException.class,
        ExportGenerationRunner.StorageWriteFailedException.class,
        ExportGenerationRunner.ExportCancelledException.class})
    public ResponseEntity<ErrorResponse> generationRefused(RuntimeException ex,
                                                            HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, errorCode(ex), ex.getMessage(),
            Map.of("retryable", Boolean.FALSE), request);
    }

    /** An illegal state transition on a job: cancelling a completed one, retrying a running one. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> illegalState(IllegalStateException ex,
                                                       HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "EXPORT_STATE_CONFLICT", ex.getMessage(),
            Map.of("retryable", Boolean.FALSE), request);
    }

    /** A malformed request: a missing reason, an undeclared column, an out-of-range window. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> badRequest(IllegalArgumentException ex,
                                                    HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage(),
            Map.of("retryable", Boolean.FALSE), request);
    }

    /** A duplicate key: a tenant-scoped report key or template key that already exists. */
    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    public ResponseEntity<ErrorResponse> duplicate(org.springframework.dao.DuplicateKeyException ex,
                                                   HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "DUPLICATE_KEY",
            "That key already exists inside this tenant. Report definition keys are tenant-unique, so a "
                + "duplicate is a collision rather than a global conflict.",
            Map.of("retryable", Boolean.FALSE), request);
    }

    private static String errorCode(RuntimeException ex) {
        for (java.lang.reflect.Method method : ex.getClass().getMethods()) {
            if (method.getName().equals("code") && method.getParameterCount() == 0
                && method.getReturnType() == String.class) {
                try {
                    Object value = method.invoke(ex);
                    if (value instanceof String code && !code.isBlank()) {
                        return code;
                    }
                } catch (ReflectiveOperationException ignored) {
                    // Fall through.
                }
            }
        }
        return "EXPORT_GENERATION_REFUSED";
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String code, String message,
                                                Map<String, Object> details,
                                                HttpServletRequest request) {
        Object correlationId = request.getAttribute(
            com.firstelement.kernel.web.CorrelationIdFilter.MDC_KEY);
        return ResponseEntity.status(status)
            .body(new ErrorResponse(code, message, details,
                correlationId == null ? null : correlationId.toString(),
                java.time.Instant.now(), null));
    }

    }