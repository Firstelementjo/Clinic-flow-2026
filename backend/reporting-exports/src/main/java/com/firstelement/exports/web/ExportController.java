package com.firstelement.exports.web;

import com.firstelement.exports.config.ExportProperties;
import com.firstelement.exports.catalog.ExportCatalogue;
import com.firstelement.exports.catalog.ExportCatalogueService;
import com.firstelement.exports.catalog.ReportAuthoringGuard;
import com.firstelement.exports.catalog.ReportDefinitionService;
import com.firstelement.exports.download.ExportDownloadService;
import com.firstelement.exports.format.DocumentTemplateService;
import com.firstelement.exports.job.ExportFormat;
import com.firstelement.exports.job.ExportJobRepositoryHolder;
import com.firstelement.exports.job.ExportJobService;
import com.firstelement.exports.job.ExportLocale;
import com.firstelement.exports.persistence.ExportEventRepository;
import com.firstelement.exports.persistence.ExportJobRepository;
import com.firstelement.exports.persistence.FinanceExportGateRepository;
import com.firstelement.exports.persistence.ReportCatalogRepository;
import com.firstelement.exports.retention.ExportRetentionService;
import com.firstelement.security.TenantContextHolder;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.permission.Permission;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The export HTTP surface: authoring, then create, follow, cancel, retry, mint a link, download, expire.
 *
 * <h2>No request field names a tenant</h2>
 *
 * <p>FE-SEC-002 is hard in this repository: {@code @RequestParam}/{@code @PathVariable} may not bind
 * {@code organizationId}, {@code tenantId}, {@code organization} or {@code tenant}. The tenant comes from
 * {@link TenantContextHolder} on every route, and {@code projectId} — which the legacy client sends — is
 * validated against the caller's server-resolved memberships by {@code TenantQueryGuard} inside the service.
 * Naming a project is a request; it is never authorization.
 *
 * <h2>Nothing here generates an artefact</h2>
 *
 * <p>Every route either inspects state, changes a state, or authors configuration. The bytes are produced by
 * {@code ExportJobHandler} on the worker, and the create route returns {@code 202 Accepted} with a
 * {@code QUEUED} row and a correlation id. There is no route that can be made to render a DOCX, because a
 * route that can is a route that can block a request thread on a two-million-row export.
 *
 * <h2>Every route names its permission, with ONE declared exception</h2>
 *
 * <p>Declared explicitly rather than relying on {@code authenticated()}, because an export endpoint whose
 * only declaration is a session is an endpoint that can disclose a tenant's margins, its customer contact
 * details, or both. Export lifecycle routes take {@code ANALYTICS_READ_PROJECT}; destructive retention takes
 * {@code ORGANIZATION_UPDATE}.
 *
 * <p><b>The authoring routes below are the exception, and it is deliberate.</b> They do not carry
 * {@code @RequirePermission}: the annotation renders a denial as {@code 403 ACCESS_DENIED}, while every other
 * refusal on these routes — a foreign definition, a nonexistent one — is the non-disclosing
 * {@code 404 NOT_FOUND}. One authoring surface answering with two vocabularies for the question "may I act
 * here, and is it mine?" is a discriminator on the surface, so {@link ReportAuthoringGuard} is the single
 * enforcement point instead and every authoring handler delegates to a service whose first statement is that
 * guard. {@link ReportAuthoringGuard}'s javadoc states the whole trade-off, including what is given up and
 * what compensates for it.
 */
@RestController
@RequestMapping("/api/v1/exports")
public class ExportController {

    private static final Logger log = LoggerFactory.getLogger(ExportController.class);

    private final ExportJobService jobs;
    private final ExportDownloadService downloads;
    private final ExportRetentionService retention;
    private final ExportCatalogueService catalogueService;
    private final ExportEventRepository events;
    private final FinanceExportGateRepository financeGate;
    private final com.firstelement.security.query.TenantQueryGuard guard;
    private final ExportProperties properties;
    private final ReportDefinitionService definitions;
    private final DocumentTemplateService templates;
    private final ReportAuthoringGuard authoring;

    public ExportController(ExportJobService jobs,
                            ExportDownloadService downloads,
                            ExportRetentionService retention,
                            ExportCatalogueService catalogueService,
                            ExportEventRepository events,
                            FinanceExportGateRepository financeGate,
                            com.firstelement.security.query.TenantQueryGuard guard,
                            ExportProperties properties,
                            ReportDefinitionService definitions,
                            DocumentTemplateService templates,
                            ReportAuthoringGuard authoring) {
        this.jobs = jobs;
        this.downloads = downloads;
        this.retention = retention;
        this.catalogueService = catalogueService;
        this.events = events;
        this.financeGate = financeGate;
        this.guard = guard;
        this.properties = properties;
        this.definitions = definitions;
        this.templates = templates;
        this.authoring = authoring;
    }

    // ------------------------------------------------------------------
    // requests
    // ------------------------------------------------------------------

    /**
     * A create request.
     *
     * <p>Fields are deliberately narrow: a definition and a version to pin, an optional template, a format,
     * a filter map of declared keys, a sort list, a locale and a retention hint. There is no
     * {@code columns} field, because the column set is the report version's — an export that let a caller
     * choose its columns would no longer be the report its definition names, and would no longer be
     * reproducible from the version it pins.
     *
     * <h2>Why {@code format} and {@code locale} stay {@code String}</h2>
     *
     * <p>Typing them as {@link ExportFormat} / {@link ExportLocale} is the obvious answer and it is wrong,
     * for the reason Phase 15 measured on the inbox {@code filter} parameter: Spring binds an enum
     * parameter <b>by Java constant name</b>, so {@code "csv"} would become a {@code 400} while the
     * document advertised {@code CSV}. The wire values are the compatibility surface.
     *
     * <p>So the binding is untouched and the <b>document</b> carries the constraint instead —
     * {@code ExportVocabularyOpenApiCustomizer} declares both as {@code enum} from
     * {@link ExportFormat#acceptedValues()} and {@link ExportLocale#acceptedValues()}, the same single
     * source the create route refuses against. The pairing is asserted from both directions in
     * {@code ExportVocabularyContractTest}.
     *
     * @param expectedChecksum reserved for optimistic concurrency on a re-request; not yet honoured, and
     *                        recorded here so its arrival is visible rather than silently ignored
     */
    public record CreateExportRequest(UUID reportDefinitionId,
                                      Integer reportVersion,
                                      UUID documentTemplateId,
                                      Integer documentTemplateVersion,
                                      String format,
                                      Map<String, Object> filters,
                                      List<String> sort,
                                      String locale,
                                      Integer expiresInDays,
                                      String expectedChecksum) {
    }

    /** A cancellation request. The reason is required; a cancellation with no reason is a mistake. */
    public record CancelExportRequest(String reason) {
    }

    /** A link-minting request, optionally guarded by the checksum the client holds. */
    public record MintLinkRequest(String expectedChecksum) {
    }

    // ------------------------------------------------------------------
    // authoring: requests and responses
    // ------------------------------------------------------------------

    /**
     * {@code POST /api/v1/exports/report-definitions} — a definition.
     *
     * <p>Every field maps to a column {@code V20261005060000} declares, and every one of them is required by
     * a CHECK rather than by this record, so the record exists to name them rather than to police them:
     *
     * <ul>
     *   <li>{@code nameAr} and {@code nameEn} are both required — a report with only an English name is
     *       dead configuration and {@code report_definitions_name_ar_check} refuses it. The product is
     *       Arabic-first and the clinical design constraints are part of the product, not a preference.</li>
     *   <li>{@code timezone} is required and must be a real IANA zone. A report that buckets by day must
     *       not inherit the server's zone: the same report would produce different numbers on two workers.</li>
     *   <li>{@code requiredPermissions} are ADDITIVE to the caller's own permissions — never a substitute.
     *       Each entry is resolved against the {@code Permission} catalogue at authoring time, so a
     *       requirement nobody can be evaluated against is refused here rather than failing every export
     *       that pins this definition.</li>
     *   <li>{@code redactionPolicy} must be one of the declared names; an undeclared one would make the
     *       job's {@code redactionPolicy} column a claim rather than a decision.</li>
     * </ul>
     *
     * @param projectId the project the definition is scoped to, or {@code null} for an organisation-wide
     *                  definition. {@code report_definitions."projectId"} is nullable on purpose: a tenant's
     *                  report catalogue is not per project, and forcing per-project copies is how two
     *                  reports with the same key drift apart.
     */
    public record CreateReportDefinitionRequest(String key,
                                                String nameAr,
                                                String nameEn,
                                                String descriptionAr,
                                                String descriptionEn,
                                                String timezone,
                                                String localeDefault,
                                                List<String> requiredPermissions,
                                                String redactionPolicy,
                                                String retentionClass) {
    }

    /**
     * {@code PATCH /api/v1/exports/report-definitions/{id}} — labels only.
     *
     * <p>{@code expectedVersion} is the row's own {@code version} column, and the update is guarded on it, so
     * a concurrent rename loses rather than overwrites. Only labels and descriptions move: a version body is
     * written once and never updated, which is what makes a published export reproducible after the
     * definition has been renamed.
     */
    public record RenameReportDefinitionRequest(String nameAr,
                                                String nameEn,
                                                String descriptionAr,
                                                String descriptionEn,
                                                Integer expectedVersion) {
    }

    /**
     * {@code POST /api/v1/exports/report-definitions/{id}/versions} — one immutable version body.
     *
     * <p>Editing a report creates a version; it never edits the body an export already shipped against.
     * {@code columns} is the pinned export column set and {@code measures}/{@code dimensions} are the
     * aggregations and the grouping, all of them KEYS into the typed field registry and never SQL. A key the
     * registry does not declare is a refusal naming it, which is the difference between a versioned metadata
     * registry and a query language.
     *
     * <p>{@code metricSemantics} and {@code denominatorRules} are stored as data rather than inferred, per
     * ADR-0007 rule 5: "0% of tickets" and "no tickets" must stay distinguishable in every read model, and
     * that is not derivable from the statement.
     */
    public record CreateReportVersionRequest(List<String> columns,
                                            List<String> dimensions,
                                            List<String> measures,
                                            Map<String, Object> filters,
                                            List<String> defaultSort,
                                            Map<String, Object> denominatorRules,
                                            Map<String, Object> metricSemantics,
                                            String changeNote) {
    }

    /** {@code .../retire} — the successor is named, because a retirement that does not say where it landed is not a retirement. */
    public record RetireVersionRequest(Integer successorVersion) {
    }

    /**
     * {@code POST /api/v1/exports/document-templates} — a DOCX template definition.
     *
     * <p>{@code kind} is absent from the request on purpose. {@code document_templates_kind_check} accepts
     * DOCX and PDF because the schema does not know what a renderer supports; this module renders DOCX
     * only, so a PDF template would be a job that fails after it has already been paid for. The service pins
     * it.
     */
    public record CreateDocumentTemplateRequest(String key,
                                                String nameAr,
                                                String nameEn,
                                                String locale,
                                                String timezone) {
    }

    /**
     * A definition, as the authoring surface returns it.
     *
     * <p>Named {@code reportDefinitionId} rather than {@code id} because that is the exact name of the
     * create request's field: the whole capability being added here is a client being able to read an id
     * out of one response and send it in the next without a translation table.
     */
    public record ReportDefinitionResponse(UUID reportDefinitionId,
                                           String key,
                                           String nameAr,
                                           String nameEn,
                                           String descriptionAr,
                                           String descriptionEn,
                                           String status,
                                           String timezone,
                                           String localeDefault,
                                           List<String> requiredPermissions,
                                           String redactionPolicy,
                                           String retentionClass,
                                           Integer currentVersion,
                                           Integer publishedVersion,
                                           Integer rowVersion) {
    }

    /**
     * One immutable report version body, as stored.
     *
     * <p>{@code defaultSort} is the stored form — {@code [{"field": …, "direction": "asc"|"desc"}]} — and is
     * echoed rather than reformatted, because this response is an authoring surface and an author pasting
     * {@code defaultSort} from one version into the next should paste the same bytes.
     */
    public record ReportVersionResponse(UUID reportDefinitionId,
                                        int reportVersion,
                                        String state,
                                        List<String> columns,
                                        List<String> dimensions,
                                        List<String> measures,
                                        Map<String, Object> filters,
                                        List<Object> defaultSort,
                                        Map<String, Object> denominatorRules,
                                        Map<String, Object> metricSemantics,
                                        String changeNote,
                                        LocalDateTime publishedAt,
                                        UUID publishedByUserId,
                                        LocalDateTime retiredAt,
                                        UUID supersededByVersionId) {
    }

    /** A document template, as the authoring surface returns it. */
    public record DocumentTemplateResponse(UUID documentTemplateId,
                                           String key,
                                           String nameAr,
                                           String nameEn,
                                           String kind,
                                           String status,
                                           String locale,
                                           String timezone,
                                           Integer currentVersion,
                                           Integer publishedVersion,
                                           Integer rowVersion) {
    }

    /**
     * One template version, as the authoring surface returns it.
     *
     * <p><b>No storage key and no content hash.</b> Both are withheld for the same reason the export
     * catalogue withholds them: a storage key is a locator for a document in object storage, and a locator
     * in a JSON response ends up in a browser's network tab, a proxy log and a support ticket. What the
     * author gets instead is the contract they actually need — which placeholders the version declares, and
     * its state.
     */
    public record DocumentTemplateVersionResponse(UUID documentTemplateId,
                                                  int documentTemplateVersion,
                                                  String locale,
                                                  String state,
                                                  List<String> placeholders,
                                                  String changeNote,
                                                  LocalDateTime publishedAt,
                                                  UUID publishedByUserId,
                                                  LocalDateTime retiredAt,
                                                  UUID supersededByVersionId) {
    }

    // ------------------------------------------------------------------
    // authoring: report definitions
    // ------------------------------------------------------------------

    /**
     * {@code POST /api/v1/exports/report-definitions} — authors a definition.
     *
     * <p>{@code 201 Created}: the definition exists and the response carries the id a client needs for
     * everything that follows. Before Phase 31 no route created one, so the catalogue was empty on a real
     * database and {@code POST /api/v1/exports} had nothing valid to reference.
     *
     * <p>Authorization is {@link ReportAuthoringGuard}: {@code report:manage}, ADMIN-only, refused as a
     * non-disclosing {@code 404}. This method carries no {@code @RequirePermission} and the class javadoc
     * states why.
     */
    @PostMapping("/report-definitions")
    public ResponseEntity<ReportDefinitionResponse> createReportDefinition(
        @RequestParam(name = "projectId", required = false) UUID projectId,
        @RequestBody CreateReportDefinitionRequest request) {
        ReportCatalogRepository.DefinitionRow created = definitions.createDefinition(
            TenantContextHolder.get(), projectId,
            request.key(), request.nameAr(), request.nameEn(),
            request.descriptionAr(), request.descriptionEn(), request.timezone(),
            request.localeDefault(), request.requiredPermissions(),
            request.redactionPolicy(), request.retentionClass());
        return ResponseEntity.created(java.net.URI.create(
                "/api/v1/exports/report-definitions/" + created.id()))
            .body(definitionResponse(created));
    }

    /**
     * {@code GET /api/v1/exports/report-definitions/{id}} — one definition.
     *
     * <p>A <b>read</b>, so it takes {@code ANALYTICS_READ_PROJECT} like the catalogue it duplicates. Reading
     * a report's shape is an analytics read; deciding what it contains is {@code REPORT_MANAGE}. An author
     * reviewing a definition they did not write needs this route and does not need authoring.
     */
    @GetMapping("/report-definitions/{definitionId}")
    @RequirePermission(Permission.ANALYTICS_READ_PROJECT)
    public ReportDefinitionResponse getReportDefinition(
        @PathVariable("definitionId") UUID definitionId) {
        return definitionResponse(definitions.detail(TenantContextHolder.get(), definitionId));
    }

    /**
     * {@code PATCH /api/v1/exports/report-definitions/{id}} — renames a definition.
     *
     * <p>Labels only, guarded on the row version the caller read. A published definition can therefore be
     * renamed without invalidating the export that pinned it.
     */
    @PatchMapping("/report-definitions/{definitionId}")
    public ReportDefinitionResponse renameReportDefinition(
        @PathVariable("definitionId") UUID definitionId,
        @RequestBody RenameReportDefinitionRequest request) {
        // The expectedVersion refusal is NOT made here. It was, and that meant it ran before the
        // service's authorization guard, so a caller without report:manage was answered 400 while every
        // other authoring refusal on this surface is a non-disclosing 404. AuthoringRequirements enforces
        // it inside the service, after authorization and after the target is resolved, and the served
        // document publishes the same rule as `required`.
        return definitionResponse(definitions.renameDefinition(TenantContextHolder.get(), definitionId,
            request.nameAr(), request.nameEn(), request.descriptionAr(), request.descriptionEn(),
            request.expectedVersion()));
    }

    /**
     * {@code GET /api/v1/exports/report-definitions/{id}/versions} — the version timeline, newest first.
     *
     * <p>Oldest first from the repository and reversed here, because a version timeline is read backwards.
     */
    @GetMapping("/report-definitions/{definitionId}/versions")
    @RequirePermission(Permission.ANALYTICS_READ_PROJECT)
    public List<ReportVersionResponse> listReportVersions(
        @PathVariable("definitionId") UUID definitionId) {
        return definitions.timeline(TenantContextHolder.get(), definitionId).stream()
            .map(ExportController::versionResponse)
            .toList();
    }

    /**
     * {@code POST /api/v1/exports/report-definitions/{id}/versions} — authors a version body.
     *
     * <p>The version number is allocated from the definition's own {@code currentVersion} and arbitrated by
     * {@code report_versions_definition_version_key}, so it is never a client-supplied value and a
     * concurrent double authoring loses on that index.
     */
    @PostMapping("/report-definitions/{definitionId}/versions")
    public ResponseEntity<ReportVersionResponse> createReportVersion(
        @PathVariable("definitionId") UUID definitionId,
        @RequestBody CreateReportVersionRequest request) {
        ReportCatalogRepository.VersionRow created = definitions.createVersion(
            TenantContextHolder.get(), definitionId,
            request.columns(), request.dimensions(), request.measures(), request.filters(),
            request.defaultSort(), request.denominatorRules(), request.metricSemantics(),
            request.changeNote());
        return ResponseEntity.created(java.net.URI.create(
                "/api/v1/exports/report-definitions/" + definitionId
                    + "/versions/" + created.versionNumber()))
            .body(versionResponse(created));
    }

    /**
     * {@code POST /api/v1/exports/report-definitions/{id}/versions/{n}/publish} — makes a body live.
     *
     * <p>One transaction: the incumbent is retired naming this version as its successor and this version is
     * published. {@code report_versions_one_published_per_definition} is a partial unique index, so a
     * concurrent double publication loses on the index rather than on application ordering, and the
     * transaction rolls back leaving the incumbent live — a definition must never be left with no live body,
     * which would be a report that silently stops producing anything.
     */
    @PostMapping("/report-definitions/{definitionId}/versions/{versionNumber}/publish")
    public ReportDefinitionResponse publishReportVersion(
        @PathVariable("definitionId") UUID definitionId,
        @PathVariable("versionNumber") int versionNumber) {
        return definitionResponse(definitions.publish(TenantContextHolder.get(), definitionId, versionNumber));
    }

    /**
     * {@code POST /api/v1/exports/report-definitions/{id}/versions/{n}/retire} — retires a live body.
     *
     * <p>{@code 204}: the version row is not returned because a retired version's identity is the definition
     * and the number, both of which the caller supplied. The retirement itself — {@code retiredAt} and the
     * successor — is readable afterwards through the timeline above.
     */
    @PostMapping("/report-definitions/{definitionId}/versions/{versionNumber}/retire")
    public ResponseEntity<Void> retireReportVersion(
        @PathVariable("definitionId") UUID definitionId,
        @PathVariable("versionNumber") int versionNumber,
        @RequestBody(required = false) RetireVersionRequest request) {
        // The successor requirement is enforced in ReportDefinitionService, not here -- see the rename
        // route above for why a refusal made in the controller runs before authorization.
        definitions.retire(TenantContextHolder.get(), definitionId, versionNumber,
            request == null ? null : request.successorVersion());
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------
    // authoring: document templates
    // ------------------------------------------------------------------

    /**
     * {@code POST /api/v1/exports/document-templates} — authors a template definition.
     *
     * <p>Before Phase 31 no route created one either, so a DOCX export was equally unreachable: the create
     * request names a template and a template version, and no HTTP response contained either.
     */
    @PostMapping("/document-templates")
    public ResponseEntity<DocumentTemplateResponse> createDocumentTemplate(
        @RequestParam(name = "projectId", required = false) UUID projectId,
        @RequestBody CreateDocumentTemplateRequest request) {
        ReportCatalogRepository.TemplateRow created = templates.createTemplate(TenantContextHolder.get(),
            projectId, request.key(), request.nameAr(), request.nameEn(), request.locale(),
            request.timezone());
        return ResponseEntity.created(java.net.URI.create(
                "/api/v1/exports/document-templates/" + created.id()))
            .body(templateResponse(created));
    }

    /**
     * {@code POST /api/v1/exports/document-templates/{id}/versions} — stores one template version.
     *
     * <p>The body is the raw DOCX. Not {@code multipart/form-data}, and the reason is that a multipart part
     * has to be spooled to a file or read into memory before it can be validated, which turns a bounded
     * document upload into an unbounded one.
     *
     * <p>The bound is stated honestly rather than overclaimed: {@code Content-Length}, when the client sends
     * one, is refused here before the body is read, and
     * {@code firstelement.exports.generation.max-template-bytes} is enforced by the service whatever the
     * client sent. A chunked request is therefore bounded once the service sees it and not before — which is
     * the truth about raw request bodies, and why the service check is the one that matters.
     *
     * <p>The service allocates the storage key, writes the bytes, <b>commits</b> them to their final key and
     * reads them back before writing the version row. The read-back is the point: Phase 16 shipped a
     * {@code createVersion} that wrote the bytes and never committed them, so the row named an object that
     * was not there and every DOCX export failed at generation. A version row that can only be written after
     * its own key has been read back is a row that cannot name a missing object.
     */
    @PostMapping("/document-templates/{templateId}/versions")
    public ResponseEntity<DocumentTemplateVersionResponse> createDocumentTemplateVersion(
        @PathVariable("templateId") UUID templateId,
        @RequestParam(name = "locale", required = false) String locale,
        @RequestParam(name = "changeNote", required = false) String changeNote,
        @RequestBody byte[] document) {
        // Cheap pre-read bound for a client that declares its length. Not the only one: the service
        // enforces the same number on the bytes themselves, which is what holds for a chunked request.
        int bound = properties.getGeneration().getMaxTemplateBytes();
        if (document != null && document.length > bound) {
            throw new IllegalArgumentException(
                "The uploaded template is " + document.length + " bytes and the configured maximum is " + bound
                    + " (firstelement.exports.generation.max-template-bytes).");
        }
        ReportCatalogRepository.TemplateVersionRow created = templates.createVersion(
            TenantContextHolder.get(), templateId, document, locale, changeNote);
        return ResponseEntity.created(java.net.URI.create(
                "/api/v1/exports/document-templates/" + templateId
                    + "/versions/" + created.versionNumber()))
            .body(templateVersionResponse(created));
    }

    /** {@code GET /api/v1/exports/document-templates/{id}/versions} — a template's versions, oldest first. */
    @GetMapping("/document-templates/{templateId}/versions")
    @RequirePermission(Permission.ANALYTICS_READ_PROJECT)
    public List<DocumentTemplateVersionResponse> listDocumentTemplateVersions(
        @PathVariable("templateId") UUID templateId) {
        return templateVersionTimeline(TenantContextHolder.get(), templateId);
    }

    /**
     * {@code POST /api/v1/exports/document-templates/{id}/versions/{n}/publish} — makes a template live.
     *
     * <p>Publication re-validates the stored placeholder vocabulary against the registry, so a version whose
     * {@code placeholderSchema} names a field nothing declares is refused here rather than published and
     * then failing a job inside the renderer.
     */
    @PostMapping("/document-templates/{templateId}/versions/{versionNumber}/publish")
    public DocumentTemplateResponse publishDocumentTemplateVersion(
        @PathVariable("templateId") UUID templateId,
        @PathVariable("versionNumber") int versionNumber) {
        return templateResponse(
            templates.publish(TenantContextHolder.get(), templateId, versionNumber));
    }

    /** {@code .../retire} — names its successor, and the retired body stays readable forever. */
    @PostMapping("/document-templates/{templateId}/versions/{versionNumber}/retire")
    public ResponseEntity<Void> retireDocumentTemplateVersion(
        @PathVariable("templateId") UUID templateId,
        @PathVariable("versionNumber") int versionNumber,
        @RequestBody(required = false) RetireVersionRequest request) {
        templates.retire(TenantContextHolder.get(), templateId, versionNumber,
            request == null ? null : request.successorVersion());
        return ResponseEntity.noContent().build();
    }

    /**
     * Whether <em>this</em> caller may author, so a client can hide an authoring screen rather than
     * offering buttons that all answer 404.
     *
     * <p>UX only. The routes refuse regardless, and backend policy is authoritative — this exists so the
     * Angular surface is not built out of speculative 404s, which is exactly how a client ends up shipping a
     * dead authoring screen and calling it a server bug.
     */
    @GetMapping("/authoring")
    @RequirePermission(Permission.ANALYTICS_READ_PROJECT)
    public Map<String, Object> authoring() {
        boolean mayAuthor = authoring.mayAuthor(TenantContextHolder.get());
        return Map.of(
            "mayAuthor", mayAuthor,
            "permission", Permission.REPORT_MANAGE.value(),
            "redactionPolicies", com.firstelement.exports.catalog.ExportRedactionPolicy.declared(),
            "locales", ExportLocale.acceptedValues(),
            "fields", com.firstelement.exports.catalog.ReportDefinitionService.fieldCatalogue(),
            "filterVocabulary", definitions.catalogue(),
            "metadataPlaceholders",
                com.firstelement.exports.format.DocxTemplateRenderer.metaNames(),
            "note", "mayAuthor is a hint for the UI, not an authorization decision. Every route below checks "
                + "report:manage itself and answers a caller without it with a non-disclosing 404, whatever "
                + "this says.");
    }

    // ------------------------------------------------------------------
    // authoring: mapping helpers
    // ------------------------------------------------------------------

    private static ReportDefinitionResponse definitionResponse(ReportCatalogRepository.DefinitionRow row) {
        return new ReportDefinitionResponse(row.id(), row.key(), row.nameAr(), row.nameEn(),
            row.descriptionAr(), row.descriptionEn(), row.status(), row.timezone(), row.localeDefault(),
            row.requiredPermissions(), row.redactionPolicy(), row.retentionClass(),
            row.currentVersion(), row.publishedVersion(), Integer.valueOf(row.version()));
    }

    private static ReportVersionResponse versionResponse(ReportCatalogRepository.VersionRow row) {
        return new ReportVersionResponse(row.reportDefinitionId(), row.versionNumber(), row.state(),
            strings(ExportController.readList(row.columnsJson())),
            strings(ExportController.readList(row.dimensionsJson())),
            strings(ExportController.readList(row.measuresJson())),
            ExportController.readMap(row.filtersJson()),
            ExportController.readObjects(row.defaultSortJson()),
            ExportController.readMap(row.denominatorRulesJson()),
            ExportController.readMap(row.metricSemanticsJson()),
            row.changeNote(), row.publishedAt(), row.publishedByUserId(),
            row.retiredAt(), row.supersededByVersionId());
    }

    private static DocumentTemplateResponse templateResponse(ReportCatalogRepository.TemplateRow row) {
        return new DocumentTemplateResponse(row.id(), row.key(), row.nameAr(), row.nameEn(), row.kind(),
            row.status(), row.locale(), row.timezone(), row.currentVersion(), row.publishedVersion(),
            Integer.valueOf(row.version()));
    }

    private DocumentTemplateVersionResponse templateVersionResponse(
        ReportCatalogRepository.TemplateVersionRow row) {
        return new DocumentTemplateVersionResponse(row.documentTemplateId(), row.versionNumber(),
            row.locale(), row.state(),
            placeholdersOf(row), row.changeNote(), row.publishedAt(), row.publishedByUserId(),
            row.retiredAt(), row.supersededByVersionId());
    }

    /**
     * Every version of one template, as the authoring surface returns it.
     *
     * <p>Tenant-scoped: {@code DocumentTemplateService.list} filters on the caller's organization in the
     * statement, so another tenant's template id yields the empty list a nonexistent id yields.
     */
    private List<DocumentTemplateVersionResponse> templateVersionTimeline(
        com.firstelement.security.TenantContext caller, UUID templateId) {
        return templates.versions(caller, templateId).stream()
            .map(this::templateVersionResponse)
            .toList();
    }

    /**
     * The declared placeholders, read back out of the stored {@code placeholderSchema}.
     *
     * <p>Read rather than remembered from the upload, because the stored schema is what the catalogue
     * publishes and what the renderer fills — and a response echoing what the author typed would be a
     * different thing from what was saved if the two ever disagreed.
     */
    private static List<String> placeholdersOf(ReportCatalogRepository.TemplateVersionRow row) {
        Object declared = ExportController.readMap(row.placeholderSchemaJson()).get("placeholders");
        if (!(declared instanceof List<?> names)) {
            return List.of();
        }
        return names.stream().map(String::valueOf).toList();
    }

    private static List<String> strings(List<Object> raw) {
        return raw.stream().map(String::valueOf).toList();
    }

    /**
     * A stored jsonb array or object, parsed.
     *
     * <p>Hand-rolled rather than routed through {@code JsonCodec}, because this class is the HTTP layer and
     * the repository contract deliberately hands jsonb to callers as text. Jackson is already on the
     * classpath for the request bodies; using it here keeps the echo of what was stored honest without
     * giving the web layer a codec it could then use to <em>write</em> configuration.
     */
    private static List<Object> readObjects(String json) {
        Object parsed = parse(json);
        return parsed instanceof List<?> list ? List.copyOf(list) : List.of();
    }

    private static List<Object> readList(String json) {
        return readObjects(json);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readMap(String json) {
        Object parsed = parse(json);
        return parsed instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static Object parse(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            // readValue, NOT readTree: this must yield real java.util collections so the instanceof checks
            // above decide anything. A JsonNode is neither a List nor a Map, so every parsed column would
            // silently come back empty -- a response that looks well formed and states nothing.
            return JSON.readValue(json, Object.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(
                "A stored jsonb column could not be parsed for the authoring response. The value was written "
                    + "by this module as jsonb, so this is a storage fault rather than a bad request.", e);
        }
    }

    /** One mapper, because a per-call mapper is a per-call allocation on a route that may map 50 versions. */
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
        new com.fasterxml.jackson.databind.ObjectMapper();

    // ------------------------------------------------------------------
    // lifecycle
    // ------------------------------------------------------------------

    /**
     * {@code POST /api/v1/exports} — queues an export.
 *
 * <p>{@code 202 Accepted}: the work is durable and the response carries the job id and a correlation id.
 * A {@code 201 Created} would be a lie, because no artefact exists yet.
 *
 * <h2>The vocabularies are closed at this boundary, before any query is built</h2>
 *
 * <p>{@code format} resolves through {@link ExportFormat#requireAccepted} and {@code locale} through
 * {@link ExportLocale#require}. An undeclared value in either is a {@code 400} naming the accepted values —
 * never a {@code 500} from a PostgreSQL enum cast, and never a silent substitution. The locale case is
 * the one that matters most: a request naming a locale the system cannot render used to be accepted,
 * stored on nothing, and answered with whatever language the report definition happened to declare, which
 * is a file that looks correct and is wrong.
 *
 * <p>Both refusals happen before {@code jobs.create}, so a request that names an unrenderable format or
 * an undeclared locale never reaches the database and never leaves a QUEUED row behind.
 */
    @PostMapping
    @RequirePermission(Permission.ANALYTICS_READ_PROJECT)
    public ResponseEntity<ExportJobService.QueuedExport> create(
        @RequestParam(name = "projectId", required = false) UUID projectId,
        @RequestBody CreateExportRequest request) {
        String correlationId = org.slf4j.MDC.get(
            com.firstelement.kernel.web.CorrelationIdFilter.MDC_KEY);
        // Refused here rather than inside the service so the refusal is about the REQUEST and happens
        // before a job row, an outbox intent and an audit row exist for a request that can never run.
        ExportFormat format = ExportFormat.requireAccepted(
            request.format() == null ? ExportFormat.CSV.name() : request.format());
        ExportLocale locale = ExportLocale.require(request.locale());
        ExportJobService.QueuedExport queued = jobs.create(TenantContextHolder.get(), projectId,
            new ExportJobService.ExportRequest(
                request.reportDefinitionId(),
                request.reportVersion(),
                request.documentTemplateId(),
                request.documentTemplateVersion(),
                format.name(),
                request.filters(),
                request.sort(),
                locale.wireValue(),
                request.expiresInDays()),
            correlationId);
        log.info("export queued through the API job={} correlationId={}", queued.jobId(), correlationId);
        return ResponseEntity.accepted().body(queued);
    }

    /** {@code GET /api/v1/exports} — the caller's jobs. */
    @GetMapping
    @RequirePermission(Permission.ANALYTICS_READ_PROJECT)
    public List<ExportJobRepository.ExportJobRow> list(
        @RequestParam(name = "projectId", required = false) UUID projectId,
        @RequestParam(name = "status", required = false) String status) {
        return jobs.list(TenantContextHolder.get(), projectId, status);
    }

    /** {@code GET /api/v1/exports/{id}} — one job. */
    @GetMapping("/{exportId}")
    @RequirePermission(Permission.ANALYTICS_READ_PROJECT)
    public ExportJobRepository.ExportJobDetail get(@PathVariable("exportId") UUID exportId) {
        return jobs.detail(TenantContextHolder.get(), exportId);
    }

    /** {@code GET /api/v1/exports/{id}/attempts} — the retry history. */
    @GetMapping("/{exportId}/attempts")
    @RequirePermission(Permission.ANALYTICS_READ_PROJECT)
    public List<ExportEventRepository.AttemptRow> attempts(@PathVariable("exportId") UUID exportId) {
        return jobs.attempts(TenantContextHolder.get(), exportId);
    }

    /** {@code GET /api/v1/exports/{id}/downloads} — the download history, refusals included. */
    @GetMapping("/{exportId}/downloads")
    @RequirePermission(Permission.ANALYTICS_READ_PROJECT)
    public List<ExportEventRepository.DownloadRow> downloads(@PathVariable("exportId") UUID exportId) {
        return jobs.downloads(TenantContextHolder.get(), exportId);
    }

    /** {@code GET /api/v1/exports/{id}/deletions} — the object-storage deletion history. */
    @GetMapping("/{exportId}/deletions")
    @RequirePermission(Permission.ANALYTICS_READ_PROJECT)
    public List<ExportEventRepository.DeletionRow> deletions(@PathVariable("exportId") UUID exportId) {
        return jobs.deletions(TenantContextHolder.get(), exportId);
    }

    /** {@code POST /api/v1/exports/{id}/cancel} — stops a queued or running export. */
    @PostMapping("/{exportId}/cancel")
    @RequirePermission(Permission.ANALYTICS_READ_PROJECT)
    public ExportJobRepository.ExportJobRow cancel(@PathVariable("exportId") UUID exportId,
                                                   @RequestBody CancelExportRequest request) {
        return jobs.cancel(TenantContextHolder.get(), exportId,
            request == null ? null : request.reason());
    }

    /** {@code POST /api/v1/exports/{id}/retry} — requeues a failed or cancelled export. */
    @PostMapping("/{exportId}/retry")
    @RequirePermission(Permission.ANALYTICS_READ_PROJECT)
    public ResponseEntity<ExportJobRepository.ExportJobRow> retry(@PathVariable("exportId") UUID exportId) {
        // ADR-0016 rule 2: a retry is work ACCEPTED and still outstanding. The row comes back QUEUED
        // with a null storageKey and a null checksum, because no artefact exists yet -- exactly the
        // state a queued create returns, which is why it answers 202 and not 200. A 200 here told a
        // generated client the export was finished, and the failure surfaced later, on a download
        // that was never going to succeed.
        return ResponseEntity.accepted().body(jobs.retry(TenantContextHolder.get(), exportId));
    }

    // ------------------------------------------------------------------
    // download
    // ------------------------------------------------------------------

    /**
     * {@code POST /api/v1/exports/{id}/download-link} — mints a short-lived signed link.
     *
     * <p>The response carries the relative path, the signature id and the expiry. It does NOT carry the
     * storage key, and the signature does not either — a locator for a file of customer data in a URL is a
     * URL that ends up in a proxy log.
     */
    @PostMapping("/{exportId}/download-link")
    @RequirePermission(Permission.ANALYTICS_READ_PROJECT)
    public ExportDownloadService.MintedLink mintLink(@PathVariable("exportId") UUID exportId,
                                                     @RequestBody(required = false) MintLinkRequest request) {
        return downloads.mint(TenantContextHolder.get(), exportId,
            request == null ? null : request.expectedChecksum());
    }

    /**
     * {@code GET /api/v1/exports/{id}/download} — redeems a link.
     *
     * <p>This is the re-authorization point. The signature proves the link is intact and unexpired; the
     * service then re-resolves the caller's permissions against the fields the artefact contains and
     * refuses if any lapsed. The response is {@code no-store}, because a cached copy of a customer's export
     * is a copy nobody is tracking.
     */
    @GetMapping("/{exportId}/download")
    @RequirePermission(Permission.ANALYTICS_READ_PROJECT)
    public ResponseEntity<byte[]> download(@PathVariable("exportId") UUID exportId,
                                           @RequestParam("signature") String signature) {
        ExportDownloadService.Redeemed redeemed = downloads.redeem(TenantContextHolder.get(), exportId,
            signature);
        ContentDisposition disposition = ContentDisposition.attachment()
            .filename(redeemed.fileName(), StandardCharsets.UTF_8)
            .build();
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate")
            .header(HttpHeaders.PRAGMA, "no-cache")
            .header("X-Content-Type-Options", "nosniff")
            // The verified digest travels with the bytes so a client can confirm what it received is what
            // the job recorded, without trusting the transport.
            .header("X-Export-Checksum", redeemed.verifiedChecksum())
            .contentType(MediaType.parseMediaType(redeemed.contentType()))
            .body(redeemed.bytes());
    }

    // ------------------------------------------------------------------
    // retention
    // ------------------------------------------------------------------

    /** A deletion request. The reason is recorded on the audit row and the deletion event. */
    public record DeleteArtefactRequest(String reason) {
    }

    /**
     * {@code DELETE /api/v1/exports/{id}/artefact} — removes one export's object and tombstones the row.
     *
     * <p>Enabled, unlike the scheduled purge: an operator asking to remove a specific customer export is a
     * privacy action with a human behind it. Goes through the same legal-hold gate as the sweep.
     */
    @DeleteMapping("/{exportId}/artefact")
    @RequirePermission(Permission.ORGANIZATION_UPDATE)
    public ExportRetentionService.DeletionOutcome deleteArtefact(
        @PathVariable("exportId") UUID exportId,
        @RequestBody(required = false) DeleteArtefactRequest request) {
        return retention.deleteArtefact(TenantContextHolder.get(), exportId,
            request == null ? null : request.reason());
    }

    /**
     * {@code POST /api/v1/exports/retention/expire} — expires completed artefacts past their window.
     *
     * <p>A tenant-scoped, bounded, audited pass. Never an org-wide sweep: an export's expiry removes customer
     * data, and a sweep with no tenant scope is a cross-tenant deletion path.
     */
    @PostMapping("/retention/expire")
    @RequirePermission(Permission.ORGANIZATION_UPDATE)
    public ExportRetentionService.ExpiryOutcome expireDue(
        @RequestParam(name = "projectId", required = false) UUID projectId) {
        return retention.expireDue(TenantContextHolder.get(), projectId);
    }

    /**
     * {@code POST /api/v1/exports/retention/purge} — <b>refuses</b>, and says what it would have done.
     *
     * <p>The capability exists, is reachable, and is off. That is testable by asserting the artefact rows are
     * still there <em>afterwards</em>, which is ADR-0009 §5's rule: the refusal is a claim, the unchanged
     * table is the evidence.
     */
    @PostMapping("/retention/purge")
    @RequirePermission(Permission.ORGANIZATION_UPDATE)
    public ExportRetentionService.PurgeRefusal purgeExpired(
        @RequestParam(name = "projectId", required = false) UUID projectId) {
        return retention.purgeExpired(TenantContextHolder.get(), projectId);
    }

    // ------------------------------------------------------------------
    // catalogue
    // ------------------------------------------------------------------

    /**
     * {@code GET /api/v1/exports/catalogue} — everything a client needs to build the create request.
     *
     * <p>Report definitions with their versions, document templates with their versions, the closed
     * {@code format} and {@code locale} vocabularies, and the declared field/filter vocabulary — all
     * scoped to the caller's tenant and to the project named here.
     *
     * <h2>Why this route exists at all</h2>
     *
     * <p>{@code POST /api/v1/exports} requires a report definition id, a report version, a document
     * template id and a document template version. Until Phase 16 no route published any of them, so the
     * feature was reachable only by a caller who already had database access — four opaque ids that no
     * HTTP response contained. A Phase-11 Angular client built against the contract could not create a
     * single export.
     *
     * <h2>Why {@code projectId} is required rather than optional</h2>
     *
     * <p>Because {@code export_jobs."projectId"} is NOT NULL and deliberately so: an export is always
     * about one project's data. A catalogue resolved with no project scope could only describe requests
     * the create route refuses, so offering one would be the same defect in a new place. Required also
     * means {@code TenantQueryGuard} validates it against the caller's server-resolved memberships
     * <em>before</em> a statement is built, which is where "refused before the query" comes from.
     *
     * <h2>Why the response type is a record</h2>
     *
     * <p>It used to be {@code Map<String, Object>}, which springdoc publishes as
     * {@code type: object, additionalProperties: {}} — a generated client typed the response as
     * {@code unknown} and could render nothing. Returning {@link ExportCatalogue} makes the contract
     * derive from the type the server actually returns.
     */
    @GetMapping("/catalogue")
    @RequirePermission(Permission.ANALYTICS_READ_PROJECT)
    public ExportCatalogue catalogue(@RequestParam("projectId") UUID projectId) {
        return catalogueService.catalogue(TenantContextHolder.get(), projectId);
    }

    /** The signed-link lifetime, for a client that wants to render an expiry countdown. */
    @GetMapping("/download-policy")
    @RequirePermission(Permission.ANALYTICS_READ_PROJECT)
    public Map<String, Object> downloadPolicy() {
        Duration ttl = properties.getSignatureTtl();
        return Map.of(
            "signatureTtlSeconds", ttl.toSeconds(),
            "retentionWindowDays", properties.getRetention().getDefaultWindowDays(),
            "artefactPurgeEnabled", properties.getRetention().isPurgeEnabled(),
            "csvCarriesUtf8Bom", properties.getGeneration().isCsvBom(),
            "formats", ExportFormat.acceptedValues(),
            "locales", ExportLocale.acceptedValues(),
            "note", "A download link is a bearer credential for a short window. Authorization is checked "
                + "again at redemption, so a permission revoked after minting fails the download.");
    }

    /** The queue this module's handler drains, so an operator can see where an export is waiting. */
    @GetMapping("/queue")
    @RequirePermission(Permission.ANALYTICS_READ_PROJECT)
    public Map<String, String> queue() {
        return Map.of("wireValue", ExportJobRepository.QUEUE_WIRE_VALUE,
            "handler", ExportJobRepositoryHolder.HANDLER_NAME);
    }

    /**
     * {@code GET /api/v1/exports/finance-gate} — the lock answer for a window.
     *
     * <p>There so an operator can see, before requesting an export, whether the window intersects a locked
     * period and whether every bucket in it has a current snapshot. A finance export that would be refused
 * *explains itself on this route rather than only as a failed job.
     */
    @GetMapping("/finance-gate")
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    public Map<String, Object> financeGate(
        @RequestParam(name = "projectId") UUID projectId,
        @RequestParam(name = "from") LocalDate from,
        @RequestParam(name = "to") LocalDate to) {
        var scope = guard.scope(TenantContextHolder.get(), projectId);
        FinanceExportGateRepository.WindowGate gate = financeGate.gate(scope, projectId, from, to,
            com.firstelement.exports.ReportingExportsModule.Limits.FINANCE_PERIOD_SCAN);
        return Map.of(
            "intersectsLockedPeriod", gate.intersectsLockedPeriod(),
            "lockedPeriods", gate.lockedPeriodKeys(),
            "bucketsWithoutSnapshot", gate.bucketsWithoutSnapshot(),
            "note", "A locked period's figures are approved, so an export of an incomplete window is "
                + "refused rather than printed with a silently disagreeing figure.");
    }
}