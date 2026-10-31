package com.firstelement.exports.job;

/**
 * The {@code public."ExportFormat"} enum of {@code V20261005070000}.
 *
 * <h2>Mirrored as a Java enum so a typo fails in review</h2>
 *
 * <p>The schema declares CSV, XLSX, DOCX, PDF and JSON. A string passed straight to
 * {@code cast(:format as "ExportFormat")} would be a runtime constraint violation for a value a reader
 * could have caught, so the vocabulary is a closed enum here and a request binds to it.
 *
 * <h2>Only two are renderable, and the others fail loudly</h2>
 *
 * <p>FR-8.3 and FR-8.4 ask for CSV and DOCX. XLSX, PDF and JSON are declared by the schema — which is
 * forward-looking, not an instruction — so this module refuses them rather than producing a file whose
 * extension lies about its contents. {@link #renderableByThisModule()} is the predicate, and the job
 * records {@code EXPORT_FORMAT_UNSUPPORTED} when it is false.
 *
 * <p>{@link #wireValue()} is the enum label, so the stored value is byte-identical to the database's.
 */
public enum ExportFormat {

    CSV("csv", true),
    XLSX("xlsx", false),
    DOCX("docx", true),
    PDF("pdf", false),
    JSON("json", false);

    private final String wireValue;
    private final boolean renderable;

    ExportFormat(String wireValue, boolean renderable) {
        this.wireValue = wireValue;
        this.renderable = renderable;
    }

    /**
     * The value stored in {@code export_jobs."format"}. Identical to the enum label.
     *
     * <p>{@code @JsonValue} so that a response typed as {@code ExportFormat} — {@link
     * com.firstelement.exports.catalog.ExportCatalogue#formats()} — serialises to the SAME bytes a
     * {@code String}-typed field has always published. Without it Jackson would emit the constant name, which
     * happens to be identical here ({@code CSV}) and would <em>not</em> be identical for a format whose
     * stored label and constant name differ. The catalogue's published vocabulary is a wire value, so it is
     * serialised as the wire value, and the OpenAPI enum the document carries is generated from
     * {@link #acceptedValues()} rather than from whatever Jackson would have inferred.
     */
    @com.fasterxml.jackson.annotation.JsonValue
    public String wireValue() {
        return name();
    }

    /** The stored {@code name()}, which is what PostgreSQL stores for this enum. */
    public String storedValue() {
        return wireValue;
    }

    /** Whether this module can produce the format today. */
    public boolean renderableByThisModule() {
        return renderable;
    }

    /** The file extension, for the minted object key. Only meaningful for a renderable format. */
    public String extension() {
        return wireValue;
    }

    /**
     * The MIME type a download sets.
     *
     * <p>Explicit per format rather than derived from a map: a CSV served as {@code text/plain} opens in a
     * browser as a page, and a DOCX served as {@code application/octet-stream} prompts a download of a
     * file the user cannot identify.
     */
    public String contentType() {
        return switch (this) {
            case CSV -> "text/csv; charset=utf-8";
            case DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case XLSX -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case PDF -> "application/pdf";
            case JSON -> "application/json; charset=utf-8";
        };
    }

    /** The format a stored value names, or {@code null} when it names none of ours. */
    public static ExportFormat fromWireValue(String value) {
        if (value == null) {
            return null;
        }
        for (ExportFormat format : values()) {
            if (format.name().equals(value.trim().toUpperCase(java.util.Locale.ROOT))) {
                return format;
            }
        }
        return null;
    }

    /** The format a stored value names, refusing an unknown or unimplementable one. */
    public static ExportFormat require(String value) {
        ExportFormat format = fromWireValue(value);
        if (format == null) {
            // The ACCEPTED list is named first, because for a caller of POST that is the actionable one;
            // the schema's declared list is named second because it explains where the others came from.
            throw new IllegalArgumentException(
                "Export format " + com.firstelement.exports.catalog.RefusalText.of(value)
                    + " is not declared. Accepted formats are " + acceptedValues()
                    + ". The database's \"ExportFormat\" enum additionally declares "
                    + java.util.Arrays.toString(values()) + ", which this renderer does not produce.");
        }
        return format;
    }

    /** Every renderable format, for the request surface's catalogue. */
    public static java.util.List<ExportFormat> renderable() {
        return java.util.Arrays.stream(values()).filter(ExportFormat::renderableByThisModule).toList();
    }

    /**
     * The format used when a request names none.
     *
     * <p><b>Named, not positional.</b> The catalogue used to publish
     * {@code renderable().get(0).wireValue()}, which is CSV today because CSV is declared first in the
     * PostgreSQL enum. That is an accident of declaration order wearing the costume of a decision: reorder
     * the enum in the schema and every catalogue silently starts publishing DOCX as its default. The default
     * is a product decision — CSV opens in every spreadsheet and needs no template — so it is stated.
     */
    public static ExportFormat defaultFormat() {
        return CSV;
    }

    /**
     * The formats {@code POST /api/v1/exports} accepts — the renderable ones, as their stored values.
     *
     * <h2>Why the renderable subset and not the declared enum</h2>
     *
     * <p>This is the list the OpenAPI document publishes as {@code enum} on {@code format}, and it has to
     * be the list the create route actually accepts — nothing more and nothing less. Publishing all five
     * would advertise {@code PDF}, which the create route refuses with {@code EXPORT_FORMAT_UNSUPPORTED}
     * (422): a client generated from that enum would offer a user a button the server always rejects, and
     * the "nothing is silently produced" property would be replaced by "the UI offers a lie".
     *
     * <p>{@code wireValue()} is the enum label, so these are {@code CSV} and {@code DOCX} — byte-identical
     * to what {@code export_jobs."format"} holds and to what {@code QueuedExport.format()} echoes back.
     */
    public static java.util.List<String> acceptedValues() {
        return renderable().stream().map(ExportFormat::wireValue).toList();
    }

    /**
     * The format a create request may name, refusing anything else.
     *
     * <p>Two refusals, and they are different facts so they say different things:
     *
     * <ul>
     *   <li>a value in <b>no</b> declared format — {@code "parquet"} — is a malformed request, rendered as
     *       {@code 400} with the accepted values named, because the caller can fix it in one edit;</li>
     *   <li>a value this schema <b>declares</b> but this renderer cannot produce — {@code "PDF"},
     *       {@code "XLSX"}, {@code "JSON"} — is a real capability gap, rendered as {@code 422} with the
     *       stable code {@code EXPORT_FORMAT_UNSUPPORTED}. It says "we have heard you and we cannot do
     *       this", which is a different answer from "that is not a thing".</li>
     * </ul>
     *
     * <p>Called from {@code ExportJobService.create} before any query is built, so a request that names
     * an unrenderable format never reaches the database and never produces a QUEUED row.
     */
    public static ExportFormat requireAccepted(String value) {
        ExportFormat format = require(value);
        com.firstelement.exports.job.ExportGenerationRunner.assertRenderable(format);
        return format;
    }
}