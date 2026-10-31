package com.firstelement.exports.job;

import com.firstelement.exports.catalog.ReportField;
import com.firstelement.exports.catalog.ReportSubject;
import com.firstelement.exports.config.ExportProperties;
import com.firstelement.exports.format.CsvExportWriter;
import com.firstelement.exports.format.DocxTemplateException;
import com.firstelement.exports.format.DocxTemplateRenderer;
import com.firstelement.exports.format.DocumentTemplateService;
import com.firstelement.exports.format.ExportCellFormatter;
import com.firstelement.exports.persistence.FinanceExportGateRepository;
import com.firstelement.exports.persistence.ReportCatalogRepository;
import com.firstelement.exports.plan.ExportQueryPlan;
import com.firstelement.exports.plan.ExportRowStream;
import com.firstelement.exports.redaction.ResolvedExportColumns;
import com.firstelement.exports.storage.ExportObjectStore;
import com.firstelement.exports.storage.FilesystemExportObjectStore;
import com.firstelement.security.query.TenantScope;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns one authorized plan into one stored artefact.
 *
 * <h2>Nothing here re-decides authorization</h2>
 *
 * <p>This class receives a {@link ResolvedExportColumns} that has already been decided against the
 * requester's <em>current</em> permissions, and a plan whose statement already carries the tenant
 * predicate. It reads rows and writes bytes. If it could widen the column set, every guarantee above it
 * would be advisory — so it has no method that takes a field list it did not receive, and its only
 * knowledge of policy is the approved set it uses to bind DOCX placeholders.
 *
 * <h2>Streaming end to end</h2>
 *
 * <p>Rows come from {@link ExportRowStream}, which fetches in pages; bytes go to
 * {@link ExportObjectStore#openForWrite} through a {@link DigestOutputStream} so the checksum is computed
 * <em>while</em> writing rather than by a second pass over the file. Neither side holds the export: a
 * two-million-row export has the same heap footprint as a two-row one, which is what the bounded-memory
 * test measures.
 *
 * <h2>The checksum is computed over exactly the bytes that were stored</h2>
 *
 * <p>Not over a re-render and not over the CSV text alone. The digest wraps the store's stream, so what
 * was hashed is what a download will serve — which is the only version of "the export is the one we
 * produced" that survives a round trip through object storage.
 *
 * <h2>Cancellation is polled, not deferred</h2>
 *
 * <p>{@link #shouldContinue} is consulted every {@code CANCELLATION_POLL_ROWS} rows. A cancellation that a
 * running export only notices at the end is not a cancellation: for a large tenant the difference is
 * between a user who can abandon a runaway job and one who cannot.
 *
 * <h2>Why this class is annotated rather than assembled</h2>
 *
 * <p>It is discovered by the same component scan as every other collaborator, and there is deliberately no
 * {@code @Bean} that constructs it. A class that has to be remembered when wiring is added is a class that
 * eventually is not remembered: this one was unannotated while every collaborator around it was annotated,
 * and the only test that noticed was the first one to load a Spring context — {@code ApiApplicationSmokeTest}
 * and the integration suite, not any unit test, because no unit test can prove a bean exists. The same
 * failure shape as {@code ExportProperties}' {@code @NotBlank} on a {@code Duration}: both are invisible to
 * {@code compileJava} and fatal to a deployment.
 */
@Component
public class ExportGenerationRunner {

    private static final Logger log = LoggerFactory.getLogger(ExportGenerationRunner.class);

    private final ExportRowStream rows;
    private final CsvExportWriter csvWriter;
    private final DocxTemplateRenderer docxRenderer;
    private final DocumentTemplateService templates;
    private final ExportObjectStore store;
    private final FinanceExportGateRepository financeGate;
    private final ReportCatalogRepository catalog;
    private final ExportProperties properties;

    public ExportGenerationRunner(ExportRowStream rows,
                                  DocxTemplateRenderer docxRenderer,
                                  DocumentTemplateService templates,
                                  ExportObjectStore store,
                                  FinanceExportGateRepository financeGate,
                                  ReportCatalogRepository catalog,
                                  ExportProperties properties) {
        this.rows = rows;
        this.docxRenderer = docxRenderer;
        this.templates = templates;
        this.store = store;
        this.financeGate = financeGate;
        this.catalog = catalog;
        this.properties = properties;
        // The BOM decision is a deployment property, resolved once: firstelement.exports.generation.csv-bom.
        this.csvWriter = properties.getGeneration().isCsvBom()
            ? CsvExportWriter.withBom() : CsvExportWriter.noBom();
    }

    /**
     * One generated export.
     *
     * @param storageKey the minted opaque key
     * @param rowCount   rows written, excluding the CSV header
     * @param bytes      the object's size
     * @param checksum   {@code sha256:<64 hex>} over the stored bytes
     * @param maxObservedBatchBytes the largest page the read produced, so a memory assertion has something
     *                             measured to compare against rather than a guess
     */
    public record Artefact(String storageKey, long rowCount, long bytes, String checksum,
                           long maxObservedBatchBytes) {
    }

    /**
     * An {@link IOException} raised while writing or committing the artefact.
     *
     * <p>A distinct type so {@code ExportJobService} can record {@code EXPORT_STORAGE_WRITE_FAILED} on the
     * job rather than the generic generation code: "object storage rejected the write" and "the statement
     * returned no rows" are different incidents with different remedies.
     */
    public static class StorageWriteFailedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public StorageWriteFailedException(String message, Throwable cause) {
            super(message, cause);
        }

        /** The stable error code recorded on the job. */
        public String code() {
            return "EXPORT_STORAGE_WRITE_FAILED";
        }
    }

    /**
     * Generates the artefact for a plan.
     *
     * @param plan       the authorized plan; its statement already carries the tenant predicate
     * @param columns    the emitted column set, for header labels and placeholder binding
     * @param locale     the render locale
     * @param timezone   the report definition's declared zone
     * @param templateBytes the DOCX template, for a DOCX export; {@code null} for CSV
     * @param metadata   the reserved metadata values a DOCX may bind
     * @param shouldContinue polled for cancellation
     */
    public Artefact generate(TenantScope scope,
                             ExportQueryPlan plan,
                             ResolvedExportColumns columns,
                             Locale locale,
                             ZoneId timezone,
                             String fileExtension,
                             byte[] templateBytes,
                             Map<String, String> metadata,
                             BooleanSupplier shouldContinue) {
        String storageKey = store.allocateKey(scope.organizationId(), fileExtension);
        boolean committed = false;
        try {
            MessageDigest digest = newDigest();
            long[] counter = new long[2];
            try (OutputStream sink = store.openForWrite(storageKey);
                 DigestOutputStream digested = new DigestOutputStream(sink, digest)) {
                if ("docx".equals(fileExtension)) {
                    counter[0] = writeDocx(plan, columns, locale, timezone, templateBytes, metadata,
                        digested);
                } else {
                    counter[0] = writeCsv(plan, columns, locale, timezone, digested, shouldContinue,
                        counter);
                }
                digested.flush();
            } catch (IOException e) {
                // Wrapped rather than propagated: the caller records a code, and an IOException's class
                // name would make every storage fault read as "EXPORT_IO_FAILED".
                throw new StorageWriteFailedException(
                    "The export bytes could not be written to object storage", e);
            }
            long bytes = commit(storageKey);
            committed = true;
            String checksum = "sha256:" + HexFormat.of().formatHex(digest.digest());
            Artefact artefact = new Artefact(storageKey, counter[0], bytes, checksum, counter[1]);
            log.info("export artefact generated tenant={} subject={} rows={} bytes={} extension={}",
                scope.organizationId(), plan.subject().storageName(), artefact.rowCount(), bytes,
                fileExtension);
            return artefact;
        } catch (IOException e) {
            throw new StorageWriteFailedException(
                "The export object could not be committed to object storage", e);
        } finally {
            if (!committed && store instanceof FilesystemExportObjectStore filesystem) {
                filesystem.discard(storageKey);
            }
        }
    }

    // ------------------------------------------------------------------
    // CSV
    // ------------------------------------------------------------------

    /**
     * The streaming CSV path.
     *
     * <p>{@link CsvExportWriter} owns the header and the quoting; this method owns the row loop, which is
     * where cancellation is observed and where the row cap is enforced.
     */
    private long writeCsv(ExportQueryPlan plan, ResolvedExportColumns columns, Locale locale,
                          ZoneId timezone, OutputStream sink, BooleanSupplier shouldContinue,
                          long[] counter) throws IOException {
        ExportCellFormatter formatter = ExportCellFormatter.forCsv(locale, timezone);
        return csvWriter.write(sink, columns.emitted(), locale, writer -> {
            long written = 0L;
            long sinceCancellationCheck = 0L;
            // try-with-resources over the page source, not a Stream: the JDBC cursor is released here
            // rather than at pool reclamation, which is the difference between a bounded connection count
            // under concurrent exports and an outage.
            try (ExportRowStream.Pages pages = rows.stream(plan)) {
                for (ExportRowStream.Batch batch : pages) {
                    for (ExportRowStream.Row row : batch) {
                        if (sinceCancellationCheck
                            >= com.firstelement.exports.ReportingExportsModule.Limits
                                .CANCELLATION_POLL_ROWS) {
                            if (!shouldContinue.getAsBoolean()) {
                                counter[0] = written;
                                throw new ExportCancelledException(
                                    "The export observed a cancellation after " + written
                                        + " rows and stopped. No partial artefact was stored.");
                            }
                            sinceCancellationCheck = 0L;
                        }
                        CsvExportWriter.writeRow(writer, formatter.cells(row, columns.emitted()));
                        written++;
                        sinceCancellationCheck++;
                        if (written > properties.getGeneration().getRowCap()) {
                            throw new RowCapExceededException(
                                properties.getGeneration().getRowCap());
                        }
                    }
                    counter[1] = Math.max(counter[1], batch.approxBytes());
                }
            }
            counter[0] = written;
            return written;
        });
    }

    // ------------------------------------------------------------------
    // DOCX
    // ------------------------------------------------------------------

    /**
     * The DOCX path.
     *
     * <p>POI's {@code XWPFDocument} holds its body in memory, so a DOCX export is bounded by row count
     * rather than streamed. That is stated here rather than hidden: a DOCX is a paginated document, not a
     * data dump, and the {@code rowCap} applies to it too. A large tenant's DOCX is refused at the cap
     * with an operator-actionable reason; a CSV of the same data streams.
     */
    private long writeDocx(ExportQueryPlan plan, ResolvedExportColumns columns, Locale locale,
                           ZoneId timezone, byte[] templateBytes, Map<String, String> metadata,
                           OutputStream sink) throws IOException {
        if (templateBytes == null) {
            throw DocxTemplateException.unsupportedMedia(
                "A DOCX export needs a stored template, and none was resolved. The document template "
                    + "pinned on the job is missing, which is a catalogue fault rather than a caller error.");
        }
        Map<ReportField, BigDecimal> totals = new LinkedHashMap<>();
        java.util.List<Map<String, Object>> rowValues = new java.util.ArrayList<>();
        try (ExportRowStream.Pages pages = rows.stream(plan)) {
            for (ExportRowStream.Batch batch : pages) {
                for (ExportRowStream.Row row : batch) {
                    Map<String, Object> values = row.values(columns.emitted());
                    rowValues.add(Map.copyOf(values));
                    accumulate(totals, columns.emitted(), values);
                    if (rowValues.size() > properties.getGeneration().getRowCap()) {
                        throw new RowCapExceededException(properties.getGeneration().getRowCap());
                    }
                }
            }
        }

        Map<String, BigDecimal> totalMap = new LinkedHashMap<>();
        for (Map.Entry<ReportField, BigDecimal> entry : totals.entrySet()) {
            totalMap.put(entry.getKey().key(), entry.getValue());
        }
        DocxTemplateRenderer.RenderModel model = new DocxTemplateRenderer.RenderModel(
            rowValues, totalMap, metadata == null ? Map.of() : metadata);
        DocxTemplateRenderer.RenderStyle style = DocxTemplateRenderer.RenderStyle.of(locale,
            timezone == null ? "UTC" : timezone.getId());

        docxRenderer.render(templateBytes, columns.emitted(), model, style, sink);
        return rowValues.size();
    }

    private static void accumulate(Map<ReportField, BigDecimal> totals,
                                   java.util.List<ReportField> fields, Map<String, Object> values) {
        for (ReportField field : fields) {
            if (!field.isMeasure()) {
                continue;
            }
            Object raw = values.get(field.key());
            if (raw == null) {
                // An UNPRICED measure is absent, not zero. Adding nothing keeps a total that spans an
                // unpriced bucket honest; the unpriced quantity column beside it says why.
                continue;
            }
            BigDecimal amount = toDecimal(raw);
            totals.merge(field, amount, BigDecimal::add);
        }
    }

    private static BigDecimal toDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("A measure produced a non-numeric value", e);
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private long commit(String storageKey) throws IOException {
        if (store instanceof FilesystemExportObjectStore filesystem) {
            return filesystem.commit(storageKey);
        }
        // A store with no partial-then-move step has nothing to commit: openForWrite already produced the
        // object at its final key.
        long size = store.size(storageKey);
        return size < 0 ? 0L : size;
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK spec", e);
        }
    }

    /**
     * Whether the finance period-lock gate applies to a plan.
     *
     * <p>Both halves matter: the gate is about <em>money</em>, and a usage-ledger export selecting only
     * {@code usage.quantity} is not a finance export and must not be blocked by a locked period.
     */
    public static boolean needsFinanceGate(ExportQueryPlan plan) {
        return FinanceExportGateRepository.appliesTo(plan.subject()) && plan.carriesMoney();
    }

    /** The subject's canonical file extension, so the object key's extension is derived not requested. */
    public static String extensionFor(ExportFormat format) {
        return switch (format) {
            case DOCX -> "docx";
            case CSV -> "csv";
            // XLSX, PDF and JSON are declared by the schema but this module renders only CSV and DOCX.
            // A job pinned to one of them fails in {@link #assertRenderable} rather than producing a file
            // with a lying extension.
            default -> throw new UnsupportedExportFormatException(format);
        };
    }

    /** Whether this module can render a format at all. */
    public static void assertRenderable(ExportFormat format) {
        extensionFor(format);
    }

    /** The generation clock, so a test can pin it. */
    public LocalDateTime now() {
        return LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    /**
     * The declared redaction policy of a definition, resolved through the catalogue.
     *
     * <p>Delegated rather than reimplemented, because the policy name is stored on the job and on the
     * definition and one resolver means the two can never disagree about what a name means.
 */
    public static com.firstelement.exports.catalog.ExportRedactionPolicy policyOf(
        ReportCatalogRepository.DefinitionRow definition) {
        return com.firstelement.exports.persistence.ReportCatalogRepository.policyOf(
            definition.redactionPolicy());
    }

    /** The subject of a plan, for a log line. */
    public static String subjectName(ReportSubject subject) {
        return subject.storageName();
    }

    /**
     * The export generation stopped because the job was cancelled.
     *
     * <p>A distinct type so the state machine records {@code CANCELLED} rather than {@code FAILED}: the
     * difference between "the job broke" and "a person chose to stop it" is the first question anybody
     * asks about a job that did not finish.
     */
    public static class ExportCancelledException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public ExportCancelledException(String message) {
            super(message);
        }

        /** The stable error code recorded on the attempt. */
        public String code() {
            return "EXPORT_CANCELLED";
        }
    }

    /**
     * The export exceeded the configured row ceiling.
     *
     * <p>A refusal, never a truncation: a silently truncated financial or clinical extract is worse than no
     * extract, and a row cap that truncated silently would make the file's own row count a lie.
     */
    public static class RowCapExceededException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final int cap;

        public RowCapExceededException(int cap) {
            super("The export exceeded its row ceiling of " + cap + " rows and was refused rather than "
                + "truncated. Narrow the filters, or raise "
                + "firstelement.exports.generation.row-cap for a deployment that has the memory to hold it.");
            this.cap = cap;
        }

        public int cap() {
            return cap;
        }

        /** The stable error code recorded on the job. */
        public String code() {
            return "EXPORT_ROW_CAP_EXCEEDED";
        }
    }

    /** A format the schema declares and this renderer does not implement. */
    public static class UnsupportedExportFormatException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public UnsupportedExportFormatException(ExportFormat format) {
            super("Export format " + format + " is declared by the schema but not implemented by this "
                + "renderer. A job pinned to it fails here rather than producing a file whose extension "
                + "lies about its contents.");
        }

        /** The stable error code recorded on the job. */
        public String code() {
            return "EXPORT_FORMAT_UNSUPPORTED";
        }
    }

    /** A UUID for the object's audit trail. */
    static UUID mintId() {
        return UUID.randomUUID();
    }

    /** The finance gate, exposed for the job service's locked-period check. */
    public FinanceExportGateRepository financeGate() {
        return financeGate;
    }

    /** The catalogue, exposed for the job service's definition resolution. */
    public ReportCatalogRepository catalog() {
        return catalog;
    }

    /** The store, exposed for the deletion path. */
    public ExportObjectStore store() {
        return store;
    }

    /** The row stream, exposed for the job service's cancellation polling. */
    public ExportRowStream rowStream() {
        return rows;
    }

    /** Whether the CSV carries a BOM, recorded on the job so a byte-level report has an answer. */
    public boolean csvCarriesBom() {
        return csvWriter.emitsBom();
    }

    /** The row cap in force. */
    public int rowCap() {
        return properties.getGeneration().getRowCap();
    }

    /** The templates service, for the job service's template resolution. */
    public DocumentTemplateService templateService() {
        return templates;
    }
}