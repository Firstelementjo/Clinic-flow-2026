package com.firstelement.exports.catalog;

import com.firstelement.exports.job.ExportFormat;
import com.firstelement.exports.job.ExportLocale;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What {@code GET /api/v1/exports/catalogue} returns: everything a client needs to build a
 * {@code CreateExportRequest}, and nothing it does not.
 *
 * <h2>Why this is a record and not a hand-written schema</h2>
 *
 * <p>The catalogue route declared {@code Map<String, Object>}, which springdoc publishes as
 * {@code type: object, additionalProperties: {}}. A generated client types that as {@code unknown} and
 * cannot render a single field of it, so the one route that exists to make {@code POST /api/v1/exports}
 * reachable published nothing a client could use. Declaring the response as THIS type — the type the
 * server actually returns — makes the contract derive from the code, so a field added here appears in the
 * document and a field removed here disappears, with no second copy to forget.
 *
 * <h2>The shape answers a question, it is not a settings dump</h2>
 *
 * <p>A client that has read this has, for every report definition and document template in its scope:
 * the id to send, the versions that exist, and — per version — whether this caller may export it and why
 * not if they may not. That is the whole capability. The declared field/filter vocabulary travels with it
 * because a filter the caller cannot name cannot be sent either.
 *
 * <h2>Nothing here is a secret, and nothing here is another tenant's</h2>
 *
 * <p>Field KEYS, filter keys, permission names, locale tags, timezone names and template metadata. Never a
 * SQL expression (the caller chooses a key, not a column), never a storage key or content hash (a
 * locator for a file of customer data is not something to publish in a catalogue), never a row count, and
 * never an entry belonging to another tenant — the tenant predicate is in the statement, not applied to
 * the answer.
 *
 * <p>The one thing that is disclosed about the <em>caller</em> is which report versions they cannot
 * export and why, which is metadata about their own permission set and is the difference between a
 * catalogue and a list of ids.
 */
public record ExportCatalogue(

    /**
     * The project this catalogue was read for, echoed back.
     *
     * <p>Required on the request. {@code export_jobs."projectId"} is NOT NULL because an export is always
     * about one project's data, so a catalogue resolved without a project scope could only describe
     * requests the create route refuses. Echoing it lets a client confirm it sent what it meant to.
     */
    UUID projectId,

    /** The {@code format} values {@code POST /api/v1/exports} accepts, as their wire values. */
    List<String> formats,

    /** The {@code locale} values {@code POST /api/v1/exports} accepts, as their wire values. */
    List<String> locales,

    /** The {@code format} used when the request names none. Must be a member of {@code formats}. */
    String defaultFormat,

    /**
     * The {@code locale} used when the request names none: Arabic, because the product is Arabic-first. Must
     * be a member of {@code locales}.
     */
    String defaultLocale,

    /** Per-subject field and filter keys. The filter keys are exactly what {@code filters} may contain. */
    List<Map<String, Object>> subjects,

    /** Every declared field with its bilingual labels and the permission it requires. No SQL expression. */
    List<Map<String, String>> fields,

    /** The bounded metadata placeholder names a DOCX template may reference. */
    List<String> metadataPlaceholders,

    /** The redaction policies a report definition may declare. */
    List<String> redactionPolicies,

    /** The tenant's and project's report definitions, with the versions {@code POST} would accept. */
    List<ReportDefinitionEntry> reportDefinitions,

    /** The tenant's and project's document templates, with the versions a DOCX export could resolve. */
    List<DocumentTemplateEntry> documentTemplates,

    /**
     * The retention and download policy, so a client can render an expiry countdown without inventing one.
     *
     * <p>A duration, never a deadline: the deadline is per-artefact and per-tenant, and publishing one
     * here would be a claim about a job that does not exist yet.
     */
    String note) {

    /**
     * Refuses a catalogue that would advertise a choice {@code POST /api/v1/exports} rejects.
     *
     * <h2>Why this is a constructor and not only a test</h2>
     *
     * <p>{@code formats} and {@code locales} are {@code List<String>}, so the compiler permits a catalogue
     * carrying {@code "PDF"} — a value the create route refuses with {@code EXPORT_FORMAT_UNSUPPORTED} (422).
     * Nothing in the type stopped it, and nothing in the OpenAPI document would have either: springdoc
     * resolves a component typed {@code List<String>} to {@code type: array, items: {type: string}} whatever
     * the values happen to be. The two sets and the two defaults were therefore four independent
     * {@code String}s that happened to be computed from the same two factories — until somebody computed them
     * from somewhere else, at which point the catalogue would advertise a dropdown entry that always fails
     * and nothing in the build would say so.
     *
     * <p>{@link com.firstelement.exports.web.ExportVocabularyOpenApiCustomizer} now stamps the four
     * properties in the served document with the same two closed sets, so the contract is derived rather than
     * restated. This constructor is the other half: it makes the drift <b>impossible to construct</b> rather
     * than merely undocumented, and it fails at the call that would have published the lie rather than at the
     * request that reads the catalogue.
     *
     * <h2>Why it throws rather than silently trimming</h2>
     *
     * <p>Trimming would publish a catalogue that is not the one the service built, and the whole value of the
     * catalogue is that a client can trust every value in it. Silently dropping the offender would preserve
     * that property and destroy the ability to find the bug that introduced it. This is a module defect
     * rather than a caller's, so it is an {@link IllegalArgumentException} naming the offender.
     */
    public ExportCatalogue {
        requirePublished("format", formats, ExportFormat.acceptedValues());
        requirePublished("locale", locales, ExportLocale.acceptedValues());
        requireMember("defaultFormat", defaultFormat, formats);
        requireMember("defaultLocale", defaultLocale, locales);
    }

    /**
     * Every published value of one vocabulary must be one the create route accepts.
     *
     * <p>Checked against {@code acceptedValues()} — what {@code POST} refuses against — and not against the
     * database's declared enum. {@code "PDF"} is a declared {@code "ExportFormat"} value and is still not
     * accepted, which is the entire distinction {@link ExportFormat#requireAccepted()} exists to draw.
     */
    private static void requirePublished(String vocabulary, List<String> published, List<String> accepted) {
        if (published == null || published.isEmpty()) {
            throw new IllegalArgumentException(
                "An export catalogue must publish the " + vocabulary + " values the create route accepts. An "
                    + "empty published set describes a client that cannot build a request, which is the defect "
                    + "the catalogue exists to remove. The accepted set is " + accepted + ".");
        }
        for (String value : published) {
            if (!accepted.contains(value)) {
                throw new IllegalArgumentException(
                    "An export catalogue published " + vocabulary + " '" + value + "', which "
                        + "POST /api/v1/exports refuses. The accepted set is " + accepted + ". A catalogue that "
                        + "offers a value the create route rejects is a dropdown that always fails.");
            }
        }
    }

    /** A default that is not in the set it defaults from is a default nobody can rely on. */
    private static void requireMember(String field, String value, List<String> published) {
        if (value == null || !published.contains(value)) {
            throw new IllegalArgumentException(
                "An export catalogue's " + field + " ('" + value + "') must be a member of the set it "
                    + "advertises (" + published + "). A default outside the published set is a value a client "
                    + "is told to rely on and that the server never offers.");
        }
    }

    /** What a client is told about the shape of the request it can now build. */
    public static final String POLICY_NOTE =
        "Build POST /api/v1/exports from this response: take reportDefinitionId and a reportVersion whose "
            + "version.exportable is true, and — for format DOCX — a documentTemplateId with a "
            + "documentTemplateVersion whose version.exportable is true. A CSV export needs no template. "
            + "filters accepts only the declared filter keys of the version's subject, and an undeclared "
            + "key is refused rather than ignored. An unknown format or locale is refused. "
            + "An export always names one project, which is why projectId is required here.";

    /**
     * One report definition and every version of it.
     *
     * <p>The id is named {@code reportDefinitionId} — the exact name of the create request's field — so a
     * client can copy it across without a translation table. That is the whole defect this record fixes,
     * so it is worth being pedantic about the name.
     */
    public record ReportDefinitionEntry(UUID reportDefinitionId,
                                        String key,
                                        String nameAr,
                                        String nameEn,
                                        String descriptionAr,
                                        String descriptionEn,
                                        String status,
                                        String subject,
                                        String timezone,
                                        String localeDefault,
                                        List<String> requiredPermissions,
                                        String redactionPolicy,
                                        String retentionClass,
                                        Integer currentVersion,
                                        Integer publishedVersion,
                                        List<VersionEntry> versions) {
    }

    /**
     * One version of a report definition, and whether {@code POST} would accept a request pinning it.
     *
     * <h2>Why {@code exportable} exists at all</h2>
     *
     * <p>Because a catalogue that lists a version the create route refuses is worse than no catalogue: the
     * client offers it, the route answers 404 or 403, and the operator concludes the feature is broken.
     * The decision is computed through the same registry resolution and the same redaction resolver the
     * create route uses, against the caller's own permissions — and
     * {@code ExportCatalogueCapabilityIntegrationTest} posts every value here and asserts the route agrees,
     * so the two cannot drift apart unobserved.
     *
     * <p>A RETIRED version is normally still exportable, and deliberately so: a version body is immutable
     * and never purged (ADR-0009 §4), so an export pinned to it stays reproducible, which is the entire
     * point of pinning.
     *
     * @param reportVersion the number to send as {@code reportVersion}
     * @param state         {@code DRAFT}, {@code PUBLISHED} or {@code RETIRED}, as declared
     * @param exportable    whether {@code POST /api/v1/exports} accepts this version for this caller
     * @param columns       the column keys this caller would receive, or {@code null} when the refusal is a
     *                      permission rather than a redaction
     * @param refusal       why it is not exportable, or {@code null} when it is
     */
    public record VersionEntry(Integer reportVersion,
                               String state,
                               Boolean exportable,
                               List<String> columns,
                               String refusal) {
    }

    /** One document template and every version of it. */
    public record DocumentTemplateEntry(UUID documentTemplateId,
                                        String key,
                                        String nameAr,
                                        String nameEn,
                                        String kind,
                                        String status,
                                        String locale,
                                        String timezone,
                                        Integer currentVersion,
                                        Integer publishedVersion,
                                        List<TemplateVersionEntry> versions) {
    }

    /**
     * One version of a document template, and whether a DOCX export could resolve it.
     *
     * <p>Exportability is "the version row exists AND its stored document is present", because that is
     * exactly what {@code DocumentTemplateService.resolve} needs. A version whose object is gone would
     * otherwise be offered and then fail the job inside the renderer, which is a worse experience than
     * being told now.
     *
     * @param documentTemplateVersion the number to send as {@code documentTemplateVersion}
     */
    public record TemplateVersionEntry(Integer documentTemplateVersion,
                                       String locale,
                                       String state,
                                       Boolean exportable,
                                       String refusal) {
    }
}