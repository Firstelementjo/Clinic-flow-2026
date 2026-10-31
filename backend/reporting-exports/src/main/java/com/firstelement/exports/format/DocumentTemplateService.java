package com.firstelement.exports.format;

import com.firstelement.admin.audit.AdminAuditStreamWriter;
import com.firstelement.admin.audit.AuditAttribution;
import com.firstelement.admin.audit.AuditVocabularies;
import com.firstelement.admin.audit.AuditWrite;
import com.firstelement.crm.persistence.JsonCodec;
import com.firstelement.exports.catalog.ReportCatalogConflictException;
import com.firstelement.exports.catalog.ReportCatalogNotFoundException;
import com.firstelement.exports.persistence.ReportCatalogRepository;
import com.firstelement.exports.storage.ExportObjectStore;
import com.firstelement.security.TenantContext;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.error.PolicyDeniedException;
import com.firstelement.security.permission.AuthorizationService;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.query.TenantQueryGuard;
import com.firstelement.security.query.TenantScope;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authoring, versioning and publishing DOCX templates.
 *
 * <h2>What a template MAY be</h2>
 *
 * <p>A DOCX whose text contains {@code {{report.<key>}}}, {@code {{meta.<name>}}} and
 * {@code {{totals.<key>}}} placeholders, where every key is drawn from the export field registry and every
 * metadata name from the bounded list. That is the entire vocabulary. There is no expression language, no
 * conditional, no loop, no include and no macro — and because the renderer rebuilds the document rather
 * than editing it, nothing in the template's own OOXML survives either.
 *
 * <h2>What publishing a version PROVES, and when it proves it</h2>
 *
 * <p>{@link #publish} re-validates the version's stored placeholder vocabulary against the registry before
 * the version becomes live, and refuses a version naming a field that does not exist. It cannot validate
 * against a specific report's <em>emitted</em> set — that set depends on the caller — so that check stays
 * at render time and fails the job loudly rather than producing a blank cell. Both halves are recorded
 * here because the first is a configuration error and the second is a per-request condition, and an
 * operator needs to know which one they have.
 *
 * <h2>Templates are configuration and are never purged</h2>
 *
 * <p>ADR-0009 §4 classes {@code document_templates} and its versions as never purged: "a template is
 * configuration", and deleting configuration that a historical export names would make that export
 * unrenderable. This service therefore has no delete operation at all — a template is retired, and the
 * retired version stays readable so an old export can still be reproduced.
 *
 * <h2>Authorization</h2>
 *
 * <p>Every authoring entry point calls {@code ReportAuthoringGuard.requireAuthoring} as its first
 * statement, which requires {@code Permission.REPORT_MANAGE}. Until Phase 31 these methods required
 * {@code Permission.WORKFLOW_MANAGE}, which meant a workflow administrator could change the document a
 * finance export is rendered through. That guard's javadoc also explains why
 * {@code @RequirePermission} is deliberately absent from these signatures.
 */
@Service
public class DocumentTemplateService {

    private static final Logger log = LoggerFactory.getLogger(DocumentTemplateService.class);

    static final String TEMPLATE_CREATED = "DOCUMENT_TEMPLATE_CREATED";
    static final String TEMPLATE_VERSION_CREATED = "DOCUMENT_TEMPLATE_VERSION_CREATED";
    static final String TEMPLATE_VERSION_PUBLISHED = "DOCUMENT_TEMPLATE_VERSION_PUBLISHED";
    static final String TEMPLATE_VERSION_RETIRED = "DOCUMENT_TEMPLATE_VERSION_RETIRED";

    /** The placeholder grammar, duplicated here only so authoring can refuse before storing. */
    private static final Pattern PLACEHOLDER =
        Pattern.compile("\\{\\{(report|meta|totals)\\.([a-zA-Z0-9_.\\-]+)}}");

    /** The only template media this renderer supports. */
    public static final String KIND_DOCX = "DOCX";

    private final ReportCatalogRepository catalog;
    private final TenantQueryGuard guard;
    private final com.firstelement.exports.catalog.ReportAuthoringGuard authoring;
    private final AuthorizationService authorization;
    private final AdminAuditStreamWriter audit;
    private final ExportObjectStore store;
    private final com.firstelement.exports.config.ExportProperties properties;
    private final JsonCodec json;

    public DocumentTemplateService(ReportCatalogRepository catalog,
                                   TenantQueryGuard guard,
                                   com.firstelement.exports.catalog.ReportAuthoringGuard authoring,
                                   AuthorizationService authorization,
                                   AdminAuditStreamWriter audit,
                                   ExportObjectStore store,
                                   com.firstelement.exports.config.ExportProperties properties,
                                   JsonCodec json) {
        this.catalog = catalog;
        this.guard = guard;
        this.authoring = authoring;
        this.authorization = authorization;
        this.audit = audit;
        this.store = store;
        this.properties = properties;
        this.json = json;
    }

    // ------------------------------------------------------------------
    // authoring
    // ------------------------------------------------------------------

    /** Creates a template definition. The key is unique per tenant per kind. */
    @Transactional
    public ReportCatalogRepository.TemplateRow createTemplate(TenantContext caller, UUID projectId,
                                                               String key, String nameAr, String nameEn,
                                                               String locale, String timezone) {
        requireAuthoring(caller);
        TenantScope scope = guard.scope(caller, projectId);
        // Same shape as the report side and the same source: AuthoringRequirements is what the served
        // OpenAPI document publishes as `required` for CreateDocumentTemplateRequest, and each rule names
        // the CHECK on document_templates that already refuses the absent value. `locale` is deliberately
        // not one of them -- it falls back to Arabic, which is a decision rather than an omission.
        com.firstelement.exports.catalog.AuthoringRequirements.CREATE_DOCUMENT_TEMPLATE.require(
            com.firstelement.exports.catalog.AuthoringRequirements.values(
                "key", key,
                "nameAr", nameAr,
                "nameEn", nameEn,
                "timezone", timezone));
        requireKind();
        String normalizedLocale = com.firstelement.exports.persistence.ReportCatalogRepository
            .normalizeLocale(locale);

        UUID id = catalog.insertTemplate(scope, projectId, key.trim(), nameAr.trim(), nameEn.trim(),
            KIND_DOCX, normalizedLocale, requireTimezone(timezone), AuditAttribution.now());
        audit(caller, scope, TEMPLATE_CREATED, "DocumentTemplate", id,
            AuditVocabularies.AuditOutcome.SUCCESS, Map.of(
                "key", key,
                "kind", KIND_DOCX,
                "locale", normalizedLocale));
        return requireTemplate(scope, id);
    }

    /**
     * Stores one template version.
     *
     * <p>Three things happen in this order, and the order matters:
     *
     * <ol>
     *   <li>the placeholder vocabulary is validated against the registry — a typo is refused here, before
     *       an object exists;</li>
     *   <li>the bytes are stored under a <strong>minted</strong> opaque key. The caller never supplies
     *       one, and {@code document_template_versions_storage_key_check} refuses {@code ?} and
     *       {@code ://} so a URL cannot be written into the column even by mistake;</li>
     *   <li>the row is written with the content hash, which is what makes "which template produced this
     *       export" answerable months later.</li>
     * </ol>
     *
     * <p>Object storage is written outside the transaction. That is the lesser of two evils and it is
     * deliberate: holding a database transaction open across an object-store write is how a slow disk
     * turns into a connection-pool exhaustion. The orphan is harmless — it is under a key no row names —
     * and {@link #abandonUnreferencedObject} exists for the operator who wants to sweep them.
     */
    @Transactional
    public ReportCatalogRepository.TemplateVersionRow createVersion(TenantContext caller,
                                                                   UUID templateId,
                                                                   byte[] templateBytes,
                                                                   String locale,
                                                                   String changeNote) {
        requireAuthoring(caller);
        TenantScope scope = guard.tenantScope(caller);
        ReportCatalogRepository.TemplateRow template = requireTemplate(scope, templateId);
        requireBounded(templateBytes);
        if (templateBytes == null || templateBytes.length == 0) {
            throw new IllegalArgumentException(
                "A template version must carry its document bytes; an empty template renders nothing");
        }
        requireReadableDocx(templateBytes);

        List<String> placeholders = validatePlaceholders(templateBytes);
        String normalizedLocale = com.firstelement.exports.persistence.ReportCatalogRepository
            .normalizeLocale(locale);

        String storageKey = store.allocateKey(caller.organizationId(), "docx");
        writeCommittedObject(storageKey, templateBytes, templateId);

        int nextVersion = (template.currentVersion() == null ? 0 : template.currentVersion()) + 1;
        String contentHash = com.firstelement.exports.download.ExportSignatureService.sha256(templateBytes);
        UUID versionId = catalog.insertTemplateVersion(scope, template, nextVersion, normalizedLocale,
            storageKey, contentHash, placeholderSchema(placeholders), blankToNull(changeNote),
            caller.userId(), AuditAttribution.now());

        audit(caller, scope, TEMPLATE_VERSION_CREATED, "DocumentTemplateVersion", versionId,
            AuditVocabularies.AuditOutcome.SUCCESS, Map.of(
                "templateId", templateId.toString(),
                "versionNumber", String.valueOf(nextVersion),
                "placeholderCount", String.valueOf(placeholders.size()),
                "contentHash", contentHash));
        log.info("document template version created template={} version={} placeholders={}",
            templateId, nextVersion, placeholders.size());
        return requireTemplateVersion(scope, templateId, nextVersion);
    }

    /**
     * Publishes a template version.
     *
     * <p>The publication is the moment the template becomes renderable, and the partial unique index on
     * {@code (organizationId, documentTemplateId) WHERE state = 'PUBLISHED'} makes two live versions
     * impossible. A concurrent publication therefore loses on the index rather than on ordering.
     */
    @Transactional
    public ReportCatalogRepository.TemplateRow publish(TenantContext caller, UUID templateId,
                                                       int versionNumber) {
        requireAuthoring(caller);
        TenantScope scope = guard.tenantScope(caller);
        ReportCatalogRepository.TemplateRow template = requireTemplate(scope, templateId);
        ReportCatalogRepository.TemplateVersionRow version = requireTemplateVersion(scope, templateId,
            versionNumber);
        LocalDateTime now = AuditAttribution.now();

        // THE PUBLICATION IS THE VALIDATION GATE. createVersion already refused an unknown placeholder
        // before any object existed; this repeats it against the STORED schema, which is the contract the
        // renderer and the catalogue both read. It is not redundant: the stored schema is a jsonb column,
        // so anything that wrote this row other than createVersion — a migration, a backfill, a future
        // authoring surface — can put a name in it that the registry does not declare. Publication is the
        // moment such a version becomes live, so it is the moment it is checked.
        requireApprovedPlaceholders(version);

        if (template.publishedVersion() != null && template.publishedVersion() != versionNumber) {
            Optional<ReportCatalogRepository.TemplateVersionRow> incumbent =
                catalog.findTemplateVersion(scope, templateId, template.publishedVersion());
            incumbent.ifPresent(row -> catalog.retireTemplateVersion(scope, row.id(), version.id(), now));
        }
        try {
            if (!catalog.publishTemplateVersion(scope, version.id(), caller.userId(), now)) {
                throw new ReportCatalogConflictException(
                    "Document template version " + versionNumber + " is not a DRAFT, so it cannot be "
                        + "published. Template bodies are immutable; author a new version instead.",
                    "DocumentTemplateVersion", version.id());
            }
        } catch (DuplicateKeyException e) {
            throw new ReportCatalogConflictException(
                "Another publication of this template won the race; nothing was changed and the incumbent "
                    + "is still live.", "DocumentTemplate", templateId);
        }
        catalog.markTemplatePublished(scope, templateId, versionNumber, caller.userId(), now);

        audit(caller, scope, TEMPLATE_VERSION_PUBLISHED, "DocumentTemplateVersion", version.id(),
            AuditVocabularies.AuditOutcome.SUCCESS, Map.of(
                "templateId", templateId.toString(),
                "versionNumber", String.valueOf(versionNumber)));
        return requireTemplate(scope, templateId);
    }

    /**
     * Retires a published template version, naming its successor. The retired body stays readable.
     *
     * <p>{@code document_template_versions_supersede_self_check} refuses a version that supersedes itself,
     * and nothing checked first: a client naming the version it was retiring reached the database and was
     * answered {@code 500}. The check is here, with the report side's.
     *
     * @param successorNumber the version that replaced this one; {@code null} is a refusal naming it
     */
    @Transactional
    public void retire(TenantContext caller, UUID templateId, int versionNumber, Integer successorNumber) {
        requireAuthoring(caller);
        TenantScope scope = guard.tenantScope(caller);
        requireTemplate(scope, templateId);
        com.firstelement.exports.catalog.AuthoringRequirements.RETIRE_VERSION.require(
            com.firstelement.exports.catalog.AuthoringRequirements.values(
                "successorVersion", successorNumber));
        ReportCatalogRepository.TemplateVersionRow version = requireTemplateVersion(scope, templateId,
            versionNumber);
        if (successorNumber == versionNumber) {
            throw new com.firstelement.exports.catalog.SelfSupersessionRefusal(
                "Document template version " + versionNumber + " cannot be retired naming itself as its own "
                    + "successor. document_template_versions_supersede_self_check refuses a version that "
                    + "supersedes itself, and a retirement pointing at the body it is replacing records no "
                    + "history at all -- the successor is meant to be the version that took over.",
                "DocumentTemplateVersion", version.id());
        }
        ReportCatalogRepository.TemplateVersionRow successor = requireTemplateVersion(scope, templateId,
            successorNumber.intValue());
        if (!catalog.retireTemplateVersion(scope, version.id(), successor.id(), AuditAttribution.now())) {
            throw new ReportCatalogConflictException(
                "Document template version " + versionNumber + " is not PUBLISHED, so it cannot be "
                    + "retired", "DocumentTemplateVersion", version.id());
        }
        audit(caller, scope, TEMPLATE_VERSION_RETIRED, "DocumentTemplateVersion", version.id(),
            AuditVocabularies.AuditOutcome.SUCCESS, Map.of(
                "templateId", templateId.toString(),
                "versionNumber", String.valueOf(versionNumber),
                "supersededByVersionNumber", String.valueOf(successorNumber)));
    }

    // ------------------------------------------------------------------
    // reads
    // ------------------------------------------------------------------

    /** The tenant's template catalogue. */
    @Transactional(readOnly = true)
    @RequirePermission(Permission.ANALYTICS_READ_PROJECT)
    public List<ReportCatalogRepository.TemplateRow> list(TenantContext caller, UUID projectId) {
        authorization.check(caller, Permission.ANALYTICS_READ_PROJECT);
        UUID resolvedProject = projectId;
        return catalog.listTemplates(guard.scope(caller, resolvedProject), resolvedProject,
            com.firstelement.exports.ReportingExportsModule.Limits.CATALOGUE_PAGE);
    }

    /**
     * One template's versions, oldest first, for the authoring surface.
     *
     * <p>Reads like {@link #list} — {@code ANALYTICS_READ_PROJECT}, not {@code report:manage} — because
     * reviewing a template's versions is reading configuration, not changing it. The tenant predicate is in
     * the statement, so another tenant's template id yields the empty list a nonexistent id yields.
     */
    @Transactional(readOnly = true)
    @RequirePermission(Permission.ANALYTICS_READ_PROJECT)
    public List<ReportCatalogRepository.TemplateVersionRow> versions(TenantContext caller, UUID templateId) {
        authorization.check(caller, Permission.ANALYTICS_READ_PROJECT);
        TenantScope scope = guard.tenantScope(caller);
        requireTemplate(scope, templateId);
        return catalog.listTemplateVersions(scope, templateId,
            com.firstelement.exports.ReportingExportsModule.Limits.VERSION_PAGE);
    }

    /**
     * Requires that a pinned template version has been live, for the create route.
     *
 * <p>Called by {@code ExportJobService.create} <em>before</em> a job row exists, so a request that pins a
 * version nobody has published is a refusal rather than a queued job that fails inside the renderer on the
 * worker — which is a worse answer for the caller and a worse use of a queue.
 *
 * <p>Same rule, same exception and same reason as the report half of the pin; see
 * {@link com.firstelement.exports.catalog.ReportVersionNotPublishedException}. {@code ExportCatalogueService}
 * publishes the identical decision on the template version's {@code exportable} field, so a client that
 * reads only the catalogue never reaches here.
 */
    @Transactional(readOnly = true)
    public void requirePublishableVersion(TenantScope scope, UUID templateId, int versionNumber) {
        ReportCatalogRepository.TemplateVersionRow version = requireTemplateVersion(scope, templateId,
            versionNumber);
        if ("DRAFT".equals(version.state())) {
            throw new com.firstelement.exports.catalog.ReportVersionNotPublishedException(templateId,
                versionNumber, version.state(), "Document template");
        }
    }

    /**
     * The template bytes a DOCX export renders against.
     *
     * <p>A pinned version number wins over "whatever is published now", so an export created against
     * template v1 is still renderable after v2 is published. A {@code null} version resolves to the
     * currently published one, which is the only non-deterministic path and it is the caller's choice to
     * take it.
     */
    @Transactional(readOnly = true)
    public ResolvedTemplate resolve(TenantScope scope, UUID templateId, Integer versionNumber) {
        ReportCatalogRepository.TemplateVersionRow version = versionNumber == null
            ? catalog.findPublishedTemplateVersion(scope, templateId)
                .orElseThrow(() -> new ReportCatalogNotFoundException("DocumentTemplateVersion",
                    "published for template " + templateId))
            : requireTemplateVersion(scope, templateId, versionNumber);
        try {
            byte[] bytes = store.read(version.storageKey());
            return new ResolvedTemplate(version, bytes);
        } catch (IOException e) {
            // The row names an object that is not there. That is a storage fault, not a caller error, and
            // it must not be rendered as "template not found": the template exists.
            throw new StorageObjectMissingException(templateId, version.versionNumber(),
                version.storageKey(), e);
        }
    }

    /** A resolved template: the row plus the bytes it names. */
    public record ResolvedTemplate(ReportCatalogRepository.TemplateVersionRow version, byte[] bytes) {

        /** The declared placeholders, read back from the stored schema. */
        @SuppressWarnings("unchecked")
        public List<String> placeholders(JsonCodec codec) {
            Map<String, Object> schema = codec.readMap(version.placeholderSchemaJson());
            Object declared = schema.get("placeholders");
            if (!(declared instanceof List<?> list)) {
                return List.of();
            }
            List<String> names = new ArrayList<>(list.size());
            for (Object entry : list) {
                names.add(String.valueOf(entry));
            }
            return List.copyOf(names);
        }
    }

    /** The template catalogue with its declared vocabulary, for the authoring surface. */
    public Map<String, Object> catalogue() {
        Map<String, Object> catalogue = new java.util.LinkedHashMap<>();
        catalogue.put("kinds", List.of(KIND_DOCX));
        catalogue.put("metadataPlaceholders", DocxTemplateRenderer.metaNames());
        catalogue.put("reportPlaceholders",
            com.firstelement.exports.catalog.ReportFieldRegistry
                .fieldKeysOf(com.firstelement.exports.catalog.ReportSubject.CUSTOMERS));
        return Map.copyOf(catalogue);
    }

    // ------------------------------------------------------------------
    // validation
    // ------------------------------------------------------------------

    /**
     * The placeholder names in a template, with every unknown name refused.
     *
     * <p>Refuses at authoring time for a field that does not exist in the registry at all. It cannot
     * refuse a field that exists but is withheld from a particular caller — that set is per request, and
     * the check that closes it belongs at render time.
     *
     * <h2>Why the bytes are unpacked rather than read as text</h2>
     *
     * <p>A DOCX is a ZIP whose {@code word/document.xml} is DEFLATE-compressed, so scanning the raw bytes
     * for {@code {{report.}}} finds nothing unless the compressor happened to leave that run uncompressed —
     * which is what happened every time this was called. {@link #documentText(byte[])} reads the parts
     * instead, so the vocabulary check has actually been checking anything at all.
     *
     * <p>That defect was invisible for one specific reason, and it is worth recording: a template with ZERO
     * recognised placeholders passes every other check in this service.
     * {@code document_template_versions_placeholder_entries_check} accepts an empty array,
     * {@code DocxTemplateRenderer} renders the literal text it finds, and the only observable consequence is
     * a finished document that still contains literal {@code {{report.customer.displayName}}} in a
     * customer-facing file. Nothing asserted that a placeholder was ever recognised, so nothing went red.
     * {@code ReportAuthoringIntegrationTest} asserts the declared list is non-empty for a template that
     * names a field, and that an unknown name is refused.
     */
    public List<String> validatePlaceholders(byte[] templateBytes) {
        String text = documentText(templateBytes);
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            String namespace = matcher.group(1);
            String key = matcher.group(2);
            if ("meta".equals(namespace)) {
                if (!DocxTemplateRenderer.metaNames().contains(key)) {
                    throw DocxTemplateException.malformedPlaceholder(
                        "'" + namespace + "." + key + "' is not a declared metadata name. Declared names "
                            + "are " + DocxTemplateRenderer.metaNames() + ".");
                }
            } else if (com.firstelement.exports.catalog.ReportField.byKey(key).isEmpty()) {
                throw DocxTemplateException.unapprovedField(namespace + "." + key);
            }
            names.add(namespace + "." + key);
        }
        return List.copyOf(names);
    }

    /**
     * Refuses a template body above the configured bound, naming the number.
     *
     * <p>Checked here rather than only in the container so the message says which limit was hit. A raw
     * request body reaches the handler as a byte array, so the servlet container's own request limit is the
     * first bound in transit and this is the second: it is the one the caller can act on, and it holds for a
     * chunked request as well as for one that declares a {@code Content-Length}.
     */
    private void requireBounded(byte[] bytes) {
        int bound = properties.getGeneration().getMaxTemplateBytes();
        if (bytes != null && bytes.length > bound) {
            throw new IllegalArgumentException(
                "The uploaded template is " + bytes.length + " bytes and the configured maximum is " + bound
                    + " (firstelement.exports.generation.max-template-bytes). A DOCX template is a document "
                    + "with placeholders in it; anything larger is not a template.");
        }
    }

    /** How much document text one template may contain before the reader refuses to keep reading. */
    private static final int MAX_DOCUMENT_TEXT_BYTES = 4 * 1024 * 1024;

    /**
     * The readable text of an OOXML package: every {@code word/*.xml} part, concatenated.
     *
     * <p>{@code word/document.xml} plus headers and footers, because a template's heading is very often in
     * a header and a placeholder there is exactly as unrenderable as one in the body. The XML tags are left
     * in place rather than stripped: braces are not XML metacharacters and a placeholder name is
     * {@code [a-zA-Z0-9_.-]}, so the match works over the part as written — and it has to, because a run
     * whose text POI split across {@code <w:t>} elements would be invisible to a scan of extracted run text.
     *
     * <p>Bounded, because an unbounded read of a caller-supplied ZIP is a decompression bomb: a few kilobytes
     * of upload can expand to gigabytes. {@link #MAX_DOCUMENT_TEXT_BYTES} is generous for a document
     * template and the refusal names the limit.
     */
    private static String documentText(byte[] templateBytes) {
        StringBuilder text = new StringBuilder();
        try (java.util.zip.ZipInputStream packageStream = new java.util.zip.ZipInputStream(
            new java.io.ByteArrayInputStream(templateBytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = packageStream.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith("word/") || !name.endsWith(".xml")) {
                    continue;
                }
                byte[] part = packageStream.readNBytes(MAX_DOCUMENT_TEXT_BYTES + 1);
                if (part.length > MAX_DOCUMENT_TEXT_BYTES) {
                    throw DocxTemplateException.unsupportedMedia(
                        "the part '" + name + "' expands beyond " + MAX_DOCUMENT_TEXT_BYTES
                            + " bytes, so the template is refused rather than decompressed without bound. A "
                            + "DOCX template is a document with placeholders in it.");
                }
                text.append(new String(part, java.nio.charset.StandardCharsets.UTF_8)).append('\n');
            }
        } catch (IOException e) {
            throw DocxTemplateException.unsupportedMedia(
                "the template is not a readable OOXML package, so no placeholder could be read from it: "
                    + e.getMessage());
        }
        return text.toString();
    }

    /**
     * Refuses a template that is not a readable DOCX.
     *
     * <p>Checked by reading the bytes as an OOXML package. A tenant that uploaded a PDF with a
     * {@code .docx} extension would otherwise produce a job that fails inside the renderer with a ZIP
     * error, which tells the operator nothing about the actual problem.
     */
    public static void requireReadableDocx(byte[] bytes) {
        try (var ignored = new org.apache.poi.xwpf.usermodel.XWPFDocument(
            new java.io.ByteArrayInputStream(bytes))) {
            // Reading it is the check. POI throws when the package is not a WordprocessingML document.
        } catch (IOException | RuntimeException e) {
            throw DocxTemplateException.unsupportedMedia(
                "the stored bytes are not a readable DOCX (a PDF renamed to .docx is the usual cause): "
                    + e.getMessage());
        }
    }

    /**
     * Writes an object to the store and <b>commits it to its final key</b>.
     *
     * <h2>Why the commit step is not optional</h2>
     *
     * <p>{@link com.firstelement.exports.storage.FilesystemExportObjectStore#openForWrite} deliberately
     * writes to a {@code .partial} sibling so a process killed mid-write leaves nothing an operator could
     * mistake for a finished artefact. Moving it into place is a separate {@code commit}, and forgetting
     * it is silent: the version row is written, the object is on disk, and it is at the <em>wrong</em>
     * key.
     *
     * <p>That is exactly what was happening here. {@code createVersion} opened the sink, wrote the bytes
     * and closed it without committing, so {@link #resolve} — which reads by the stored key — found
     * nothing and every DOCX export failed with {@link StorageObjectMissingException}. It went unnoticed
     * because no suite authored a template and then rendered from it: the template catalogue had no
     * round-trip test at all.
     *
     * <p>The Phase-16 export catalogue caught it, which is the argument for the catalogue being honest about
     * exportability rather than optimistic. It reported the template as <em>not exportable</em> because
     * {@code store.exists(storageKey)} was honestly false, and the capability test then failed on a
     * genuine defect instead of on a fixture that had quietly papered over one.
     *
     * <p>A store with no partial-then-move step has nothing to commit, which is the same rule
     * {@code ExportGenerationRunner} applies: check the type rather than assuming a filesystem.
     *
     * <p><b>And then it is read back through the same key the row will name.</b> The commit is what makes the
     * object exist at its final key, and a version row may only be written after that has been observed —
     * not assumed. {@code resolve} reads by the stored key, so the exact check is "can this be read back",
     * and a store that accepted the write and could not serve it produces an authoring refusal instead of
     * an export failure on a worker an hour later.
     */
    private void writeCommittedObject(String storageKey, byte[] content, UUID templateId) {
        boolean committed = false;
        try (var sink = store.openForWrite(storageKey)) {
            sink.write(content);
        } catch (IOException e) {
            throw new ExportObjectStore.StorageWriteException(
                "The template could not be written to object storage, so no version row was created", e);
        }
        try {
            if (store instanceof com.firstelement.exports.storage.FilesystemExportObjectStore filesystem) {
                filesystem.commit(storageKey);
            }
            committed = true;
        } catch (IOException e) {
            throw new ExportObjectStore.StorageWriteException(
                "The template was written but could not be committed to its final object key, so no "
                    + "version row was created. A committed object is the only thing a version may name: "
                    + "a row naming a .partial sibling renders nothing and fails at export time.", e);
        } finally {
            if (!committed && store instanceof com.firstelement.exports.storage.FilesystemExportObjectStore filesystem) {
                filesystem.discard(storageKey);
            }
        }
        requireReadableAtItsOwnKey(storageKey, content, templateId);
    }

    /**
     * The check that would have caught the Phase-16 defect, run on every authored version.
     *
     * <p>Reads the object back <em>by the key the version row is about to name</em> and compares it with the
     * bytes that were written. A shorter read is the failure the commit defect produced and the export would
     * have surfaced as {@link StorageObjectMissingException} at generation time; surfacing it here means the
     * author is told at authoring time and no version row exists to mislead anybody.
     */
    private void requireReadableAtItsOwnKey(String storageKey, byte[] written, UUID templateId) {
        byte[] readBack;
        try {
            readBack = store.read(storageKey);
        } catch (IOException e) {
            throw new StorageObjectMissingException(templateId, 0, storageKey, e);
        }
        if (readBack.length != written.length
            || !com.firstelement.exports.download.ExportSignatureService.sha256(readBack)
                .equals(com.firstelement.exports.download.ExportSignatureService.sha256(written))) {
            throw new ExportObjectStore.StorageWriteException(
                "The template was committed but the object at its final key does not match what was written, "
                    + "so no version row was created. A version row names one content hash, and a row whose "
                    + "hash would not match its own bytes is an export that fails at download.",
                null);
        }
    }

    /**
     * Refuses a version whose stored placeholder vocabulary names anything the registry does not declare.
     *
     * <p>Read from {@code placeholderSchema}, not re-parsed from the document: the stored schema is what
     * {@code DocumentTemplateService.resolve} hands to the renderer and what the catalogue publishes, so it
     * is the thing that has to be true. A name outside {@code report.*}, {@code totals.*} and the bounded
     * {@code meta.*} list is a placeholder the renderer cannot fill, and a template that renders a blank
     * where a patient's name belongs is the failure this whole refusal exists to prevent.
     */
    private void requireApprovedPlaceholders(ReportCatalogRepository.TemplateVersionRow version) {
        Map<String, Object> schema = json.readMap(version.placeholderSchemaJson());
        Object declared = schema.get("placeholders");
        if (declared == null) {
            return;
        }
        if (!(declared instanceof List<?> names)) {
            throw new DocxTemplateException(DocxTemplateException.Reason.MALFORMED_PLACEHOLDER,
                "Document template version " + version.versionNumber() + " of template "
                    + version.documentTemplateId() + " stores a placeholderSchema whose 'placeholders' is not "
                    + "a list, so nothing can say which names it approves. Publication is refused rather than "
                    + "published with an unknown vocabulary.",
                List.of(String.valueOf(declared)));
        }
        for (Object name : names) {
            validatePlaceholderName(String.valueOf(name));
        }
    }

    /**
     * One stored placeholder name, checked against the same vocabulary {@link #validatePlaceholders} applies
     * to the document bytes.
     */
    private void validatePlaceholderName(String name) {
        java.util.regex.Matcher matcher = PLACEHOLDER.matcher("{{" + name + "}}");
        if (!matcher.matches()) {
            throw DocxTemplateException.malformedPlaceholder(
                "Stored placeholder '" + name + "' is not one of the declared 'report.', 'totals.' or 'meta.' "
                    + "forms, so the renderer has no rule for filling it.");
        }
        String namespace = matcher.group(1);
        String key = matcher.group(2);
        if ("meta".equals(namespace)) {
            if (!DocxTemplateRenderer.metaNames().contains(key)) {
                throw DocxTemplateException.malformedPlaceholder(
                    "'" + namespace + "." + key + "' is not a declared metadata name. Declared names are "
                        + DocxTemplateRenderer.metaNames() + ".");
            }
        } else if (com.firstelement.exports.catalog.ReportField.byKey(key).isEmpty()) {
            throw DocxTemplateException.unapprovedField(namespace + "." + key);
        }
    }

    /** The stored placeholder schema, which is what the schema's CHECK validates at write time. */
    private static String placeholderSchema(List<String> placeholders) {
        return "{\"placeholders\":[" + placeholders.stream()
            .map(name -> "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
            .reduce((a, b) -> a + "," + b).orElse("") + "]}";
    }

    /** Deletes an object a failed authoring left behind. Never called for a referenced key. */
    public boolean abandonUnreferencedObject(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            return false;
        }
        return store.delete(storageKey);
    }

    private ReportCatalogRepository.TemplateRow requireTemplate(TenantScope scope, UUID templateId) {
        return catalog.findTemplate(scope, templateId)
            .orElseThrow(() -> PolicyDeniedException.tenantOutOfScope("document-template-out-of-scope",
                scope.organizationId(), templateId));
    }

    private ReportCatalogRepository.TemplateVersionRow requireTemplateVersion(TenantScope scope,
                                                                              UUID templateId,
                                                                              int versionNumber) {
        return catalog.findTemplateVersion(scope, templateId, versionNumber)
            .orElseThrow(() -> new ReportCatalogNotFoundException("DocumentTemplateVersion",
                versionNumber + " of template " + templateId));
    }

    private void requireAuthoring(TenantContext caller) {
        if (caller == null) {
            throw new IllegalStateException(
                "Template authoring requires a resolved TenantContext; refusing to author unscoped.");
        }
        authoring.requireAuthoring(caller);
    }

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

    private static void requireKind() {
        // document_templates_kind_check accepts DOCX and PDF. This module renders DOCX only, so the
        // service pins it: a PDF template is a job that would fail after it had been paid for.
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * A real IANA zone, checked here rather than only by the schema.
     *
     * <p>Presence is {@code CREATE_DOCUMENT_TEMPLATE}'s rule and runs before this; see
     * {@link com.firstelement.exports.catalog.AuthoringRequirements} for why the check is not restated.
     */
    private static String requireTimezone(String timezone) {
        String zone = timezone.trim();
        if (!java.time.ZoneId.getAvailableZoneIds().contains(zone)) {
            throw new IllegalArgumentException(
                "timezone " + com.firstelement.exports.catalog.RefusalText.of(zone)
                    + " is not a zone this JVM knows. A template that renders a date must agree with the "
                    + "report it renders, so the zone is declared and must be real.");
        }
        return zone;
    }

    /** The row names an object storage key that is not present. A storage fault, not a caller error. */
    public static class StorageObjectMissingException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public StorageObjectMissingException(UUID templateId, int versionNumber, String storageKey,
                                            Throwable cause) {
            super("Document template version " + versionNumber + " of template " + templateId
                + " names a stored object that is not present. The template record exists; the object "
                + "does not. This is an object-storage fault and not a missing template.", cause);
        }

        /** The stable error code recorded on the job. */
        public String code() {
            return "TEMPLATE_OBJECT_MISSING";
        }
    }
}