package com.firstelement.exports.catalog;

import com.firstelement.crm.persistence.JsonCodec;
import com.firstelement.exports.ReportingExportsModule;
import com.firstelement.exports.job.ExportFormat;
import com.firstelement.exports.job.ExportLocale;
import com.firstelement.exports.persistence.ReportCatalogRepository;
import com.firstelement.exports.redaction.ExportRedactionResolver;
import com.firstelement.exports.redaction.ResolvedExportColumns;
import com.firstelement.exports.storage.ExportObjectStore;
import com.firstelement.security.TenantContext;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.permission.AuthorizationService;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.permission.PolicyResource;
import com.firstelement.security.query.TenantQueryGuard;
import com.firstelement.security.query.TenantScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers "what can I actually ask for?", and refuses to answer with anything else.
 *
 * <h2>The defect this service exists to close</h2>
 *
 * <p>{@code POST /api/v1/exports} requires a report definition id, a report version, a document template
 * id and a document template version. Until Phase 16 no route published any of them, so the feature was
 * reachable only by a caller who already had database access — an unusable capability for an Angular
 * client that generates its requests from the contract.
 *
 * <h2>A catalogue offering an id is not enough; it has to offer a VERSION the create route accepts</h2>
 *
 * <p>This is the part that is easy to get wrong and expensive to get wrong. A catalogue that merely listed
 * definition X's versions would be worse than no catalogue if version 3 of X is a DRAFT whose columns the
 * registry cannot resolve, or a version whose every column is withheld from this caller: the client would
 * offer it, the create route would refuse it with a 404 or a 403, and the operator's conclusion would be
 * that the export feature is broken.
 *
 * <p>So {@link ExportCatalogue.VersionEntry} carries an {@code exportable} decision and, when false, the
 * reason — computed through <b>the same</b> {@link ReportFieldRegistry} resolution and
 * {@link ExportRedactionResolver} the create route uses, against the caller's own effective permissions.
 * {@code ExportCatalogueCapabilityIntegrationTest} then closes the loop empirically: every version this
 * service offers is posted and asserted accepted, and every version it withholds is posted and asserted
 * refused. A shared code path alone would not prove the two agree; the test does.
 *
 * <h2>Authorization, and the order of it</h2>
 *
 * <p>{@link TenantQueryGuard#scope} is the first statement, before {@link ReportCatalogRepository} is
 * asked anything. A project outside the caller's server-resolved memberships is refused there, so no SQL
 * is built for it — "refused before the query" is structural rather than a filter applied to results. The
 * tenant predicate is in every statement the repository issues
 * ({@code :__fe_tenant_organization_id}), so another tenant's definitions and templates are absent from
 * the answer rather than filtered out of it.
 *
 * <p>The project scope is <b>required</b>, never optional. {@code export_jobs."projectId"} is NOT NULL
 * because an export is always about one project's data, so a catalogue with no project scope could only
 * describe requests the create route will refuse.
 *
 * <h2>What the catalogue does not disclose</h2>
 *
 * <p>Field KEYS, filter keys, required permission names and template metadata — no SQL expression, no
 * storage key, no content hash, no row count, and nothing at all belonging to another tenant. Column keys
 * are already published by the pre-existing {@code fields} vocabulary, so listing a version's columns
 * discloses nothing new; what it adds is the ability to tell a withheld column from an undeclared one.
 */
@Service
public class ExportCatalogueService {

    private final ReportCatalogRepository catalog;
    private final TenantQueryGuard guard;
    private final AuthorizationService authorization;
    private final ExportObjectStore store;
    private final JsonCodec json;

    public ExportCatalogueService(ReportCatalogRepository catalog,
                                  TenantQueryGuard guard,
                                  AuthorizationService authorization,
                                  ExportObjectStore store,
                                  JsonCodec json) {
        this.catalog = catalog;
        this.guard = guard;
        this.authorization = authorization;
        this.store = store;
        this.json = json;
    }

    /**
     * The catalogue a client builds its create request from.
     *
     * @param projectId required: an export is always about one project, so a catalogue with no project
     *                  scope could only describe requests the create route refuses
     * @throws com.firstelement.security.error.PolicyDeniedException when the project is outside the
     *         caller's server-resolved memberships, before any query is built
     */
    @Transactional(readOnly = true)
    @RequirePermission(Permission.ANALYTICS_READ_PROJECT)
    public ExportCatalogue catalogue(TenantContext caller, UUID projectId) {
        // The explicit check mirrors ReportDefinitionService: method security covers an HTTP request, and
        // this service is also reachable from a console and from a job.
        authorization.check(caller, Permission.ANALYTICS_READ_PROJECT);
        TenantScope scope = guard.scope(caller, projectId);

        return new ExportCatalogue(
            projectId,
            // Both sets come from the same two factories POST refuses against, and the ExportCatalogue
            // constructor refuses to be built if either publishes a value outside them or if a default is not
            // a member of its own set. The named defaults are the point where this stopped being a positional
            // accident: `renderable().get(0)` was CSV only because CSV is declared first in the database enum.
            ExportFormat.acceptedValues(),
            ExportLocale.acceptedValues(),
            ExportFormat.defaultFormat().wireValue(),
            ExportLocale.defaultLocale().wireValue(),
            subjects(),
            ReportDefinitionService.fieldCatalogue(),
            com.firstelement.exports.format.DocxTemplateRenderer.metaNames(),
            ExportRedactionPolicy.declared(),
            reportDefinitions(caller, scope, projectId),
            documentTemplates(scope, projectId),
            ExportCatalogue.POLICY_NOTE);
    }

    // ------------------------------------------------------------------
    // report definitions
    // ------------------------------------------------------------------

    private List<ExportCatalogue.ReportDefinitionEntry> reportDefinitions(TenantContext caller,
                                                                         TenantScope scope,
                                                                         UUID projectId) {
        List<ReportCatalogRepository.DefinitionRow> rows =
            catalog.listDefinitions(scope, projectId, ReportingExportsModule.Limits.CATALOGUE_PAGE);
        // Resolved once, not once per definition: the answer is the caller's permission set for this
        // project and does not vary with the row being described.
        Set<Permission> granted = authorization.effectivePermissions(caller,
            PolicyResource.ofProject(caller.organizationId(), projectId));

        List<ExportCatalogue.ReportDefinitionEntry> entries = new ArrayList<>(rows.size());
        for (ReportCatalogRepository.DefinitionRow row : rows) {
            List<VersionDecision> decisions = versionDecisions(scope, row, granted);
            String subject = decisions.stream()
                .map(VersionDecision::subject)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
            entries.add(new ExportCatalogue.ReportDefinitionEntry(
                row.id(),
                row.key(),
                row.nameAr(),
                row.nameEn(),
                row.descriptionAr(),
                row.descriptionEn(),
                row.status(),
                subject,
                row.timezone(),
                row.localeDefault(),
                row.requiredPermissions(),
                row.redactionPolicy(),
                row.retentionClass(),
                row.currentVersion(),
                row.publishedVersion(),
                decisions.stream().map(VersionDecision::entry).toList()));
        }
        return List.copyOf(entries);
    }

    /** One version's decision, with the subject kept beside it so a definition needs no second pass. */
    private record VersionDecision(ExportCatalogue.VersionEntry entry, String subject) {
    }

    /**
     * Every version of one definition, each with the decision {@code POST /api/v1/exports} would make.
     *
     * <p>The required-permission check runs once per definition rather than once per version, because the
     * answer does not depend on the version: a definition demands the same permissions for all of them.
     */
    private List<VersionDecision> versionDecisions(TenantScope scope,
                                                   ReportCatalogRepository.DefinitionRow definition,
                                                   Set<Permission> granted) {
        List<ReportCatalogRepository.VersionRow> rows =
            catalog.listVersions(scope, definition.id(), ReportingExportsModule.Limits.VERSION_PAGE);
        String permissionRefusal = missingPermission(definition, granted);
        List<VersionDecision> decisions = new ArrayList<>(rows.size());
        for (ReportCatalogRepository.VersionRow row : rows) {
            decisions.add(versionDecision(definition, row, granted, permissionRefusal));
        }
        return List.copyOf(decisions);
    }

    private VersionDecision versionDecision(ReportCatalogRepository.DefinitionRow definition,
                                            ReportCatalogRepository.VersionRow row,
                                            Set<Permission> granted,
                                            String permissionRefusal) {
        if (permissionRefusal != null) {
            return new VersionDecision(
                new ExportCatalogue.VersionEntry(row.versionNumber(), row.state(),
                    Boolean.FALSE, null, permissionRefusal), null);
        }
        // A DRAFT has never been live, and POST /api/v1/exports refuses to pin one (409
        // REPORT_VERSION_NOT_PUBLISHED). This is checked BEFORE the column resolution below for the same
        // reason the create route checks it before anything is resolved: a version nobody has published is a
        // refusal with one reason, and the reason must not be "no columns resolve" when the real answer is
        // "it is a draft". Offering it as exportable would also make the catalogue and the create route
        // disagree, which is the defect this whole service exists to prevent.
        if ("DRAFT".equals(row.state())) {
            return new VersionDecision(
                new ExportCatalogue.VersionEntry(row.versionNumber(), row.state(),
                    Boolean.FALSE, null,
                    "This version is a DRAFT and has never been published, so an export may not pin it. "
                        + "Publish it first; a version body that has never been live is configuration nobody "
                        + "has reviewed."), null);
        }
        // Resolve the version's columns exactly as the create route does, so a version whose body the
        // registry cannot resolve is withheld rather than offered and then refused at the next hop.
        List<ReportField> declared;
        try {
            declared = ReportFieldRegistry.resolve(readStringList(row.columnsJson()));
        } catch (RuntimeException e) {
            return new VersionDecision(
                new ExportCatalogue.VersionEntry(row.versionNumber(), row.state(),
                    Boolean.FALSE, null,
                    "This version's columns do not resolve against the export field registry: "
                        + e.getMessage()), null);
        }
        String subject = declared.get(0).subject().storageName();
        ResolvedExportColumns columns;
        try {
            columns = ExportRedactionResolver.resolve(declared,
                ReportCatalogRepository.policyOf(definition.redactionPolicy()),
                definition.requiredPermissions(), granted);
        } catch (RuntimeException e) {
            return new VersionDecision(
                new ExportCatalogue.VersionEntry(row.versionNumber(), row.state(),
                    Boolean.FALSE, null,
                    "This version could not be resolved for the caller: " + e.getMessage()), subject);
        }
        if (columns.emitted().isEmpty()) {
            // Every column is withheld from this caller, so the create route would refuse with
            // EmptyExportRefusal (403). Saying so here is the difference between a catalogue and a list.
            return new VersionDecision(
                new ExportCatalogue.VersionEntry(row.versionNumber(), row.state(),
                    Boolean.FALSE, List.of(),
                    "Every column of this version is withheld from the caller by the declared redaction "
                        + "policy or by a permission they do not hold, so an export of it would be "
                        + "refused."), subject);
        }
        return new VersionDecision(
            new ExportCatalogue.VersionEntry(row.versionNumber(), row.state(),
                Boolean.TRUE, List.copyOf(columns.emittedKeys()), null), subject);
    }

    /** The first required permission the caller does not hold, or {@code null} when they hold them all. */
    private static String missingPermission(ReportCatalogRepository.DefinitionRow definition,
                                            Set<Permission> granted) {
        for (String permission : definition.requiredPermissions()) {
            Permission required;
            try {
                required = Permission.fromValue(permission);
            } catch (IllegalArgumentException e) {
                return "The report definition requires the permission '" + permission
                    + "', which is not in the permission catalogue. That is a definition defect, not a "
                    + "caller's.";
            }
            if (!granted.contains(required)) {
                return "This report requires the permission '" + permission
                    + "', which the caller does not hold.";
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // document templates
    // ------------------------------------------------------------------

    private List<ExportCatalogue.DocumentTemplateEntry> documentTemplates(TenantScope scope,
                                                                         UUID projectId) {
        List<ReportCatalogRepository.TemplateRow> rows =
            catalog.listTemplates(scope, projectId, ReportingExportsModule.Limits.CATALOGUE_PAGE);
        List<ExportCatalogue.DocumentTemplateEntry> entries = new ArrayList<>(rows.size());
        for (ReportCatalogRepository.TemplateRow row : rows) {
            entries.add(new ExportCatalogue.DocumentTemplateEntry(
                row.id(), row.key(), row.nameAr(), row.nameEn(), row.kind(), row.status(), row.locale(),
                row.timezone(), row.currentVersion(), row.publishedVersion(),
                templateVersions(scope, row)));
        }
        return List.copyOf(entries);
    }

    /**
     * Every version of one template, with the decision a DOCX export's template resolution would make.
     *
     * <p>{@link DocumentTemplateService#resolve} needs the version row <em>and</em> the stored object, so
     * both are checked here: a version whose row exists while its object does not would be offered and
     * then fail the job inside {@code DocumentTemplateService.StorageObjectMissingException}, which is a
     * worse experience than being told now that the template cannot be rendered.
     *
     * <p>The storage key itself is never published — {@link ExportObjectStore} mints it and this service
     * does not read it out — only the boolean answer.
     */
    private List<ExportCatalogue.TemplateVersionEntry> templateVersions(
        TenantScope scope, ReportCatalogRepository.TemplateRow template) {
        List<ReportCatalogRepository.TemplateVersionRow> rows = catalog.listTemplateVersions(scope,
            template.id(), ReportingExportsModule.Limits.VERSION_PAGE);
        List<ExportCatalogue.TemplateVersionEntry> entries = new ArrayList<>(rows.size());
        for (ReportCatalogRepository.TemplateVersionRow row : rows) {
            // Same rule, same reason and the same exception as the report half of the pin: a version that
            // has never been live is configuration nobody has reviewed, and POST /api/v1/exports refuses to
            // pin one (409 REPORT_VERSION_NOT_PUBLISHED) before a job row exists. Checked before the object
            // presence test so the reason published is the real one — a draft whose object happens to be
            // present is still a draft.
            if ("DRAFT".equals(row.state())) {
                entries.add(new ExportCatalogue.TemplateVersionEntry(
                    row.versionNumber(), row.locale(), row.state(), Boolean.FALSE,
                    "This template version is a DRAFT and has never been published, so a DOCX export may not "
                        + "pin it. Publish it first; a template body that has never been live is "
                        + "configuration nobody has reviewed."));
                continue;
            }
            boolean present = store.exists(row.storageKey());
            entries.add(new ExportCatalogue.TemplateVersionEntry(
                row.versionNumber(), row.locale(), row.state(),
                present ? Boolean.TRUE : Boolean.FALSE,
                present ? null
                    : "This template version's stored document is not present, so a DOCX export cannot be "
                        + "rendered from it."));
        }
        return List.copyOf(entries);
    }

    // ------------------------------------------------------------------
    // vocabularies
    // ------------------------------------------------------------------

    /** The per-subject field and filter vocabulary, unchanged from the pre-existing catalogue. */
    private static List<Map<String, Object>> subjects() {
        List<Map<String, Object>> subjects = new ArrayList<>();
        for (ReportSubject subject : ReportSubject.values()) {
            subjects.add(Map.of(
                "key", subject.storageName(),
                "fields", ReportFieldRegistry.fieldKeysOf(subject),
                "filters", ReportFieldRegistry.filterKeysOf(subject),
                "finance", subject.isFinance()));
        }
        return List.copyOf(subjects);
    }

    /** The jsonb column list of a version, as the strings the registry resolves. */
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
}