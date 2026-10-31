package com.firstelement.exports.catalog;

import com.firstelement.admin.audit.AdminAuditStreamWriter;
import com.firstelement.admin.audit.AuditAttribution;
import com.firstelement.admin.audit.AuditVocabularies;
import com.firstelement.admin.audit.AuditWrite;
import com.firstelement.crm.persistence.JsonCodec;
import com.firstelement.exports.ReportingExportsModule;
import com.firstelement.exports.persistence.ReportCatalogRepository;
import com.firstelement.security.TenantContext;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.error.PolicyDeniedException;
import com.firstelement.security.permission.AuthorizationService;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.query.TenantQueryGuard;
import com.firstelement.security.query.TenantScope;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authoring, versioning, publishing and retiring report definitions.
 *
 * <h2>Authorization: REPORT_MANAGE, and nothing borrowed</h2>
 *
 * <p>Every authoring entry point calls {@link ReportAuthoringGuard#requireAuthoring} as its
 * <b>first statement</b>, which requires {@code Permission.REPORT_MANAGE}.
 *
 * <p>Until Phase 31 these methods required {@code Permission.WORKFLOW_MANAGE} and this class javadoc
 * recorded that a dedicated permission was owed. The debt is paid: a workflow administrator could change
 * what a finance export contained, and an audit row attributing that change to {@code workflow:manage} is a
 * row asserting something untrue (ADR 0010 rule 2, "a permission is named after what it authorizes").
 * {@link ReportAuthoringGuard} also documents why the {@code @RequirePermission} annotation is
 * deliberately absent here rather than used with the new value: the annotation renders a denial as 403,
 * and this surface owes its caller the same non-disclosing 404 it gives a cross-tenant id.
 *
 * <p>The next version number comes from {@code report_definitions."currentVersion"} rather than from a
 * {@code max(versionNumber)}, and {@code report_versions_definition_version_key} is the arbiter: a
 * concurrent double authoring loses on that index rather than on application ordering.
 *
 * <h2>Nothing is authored that cannot be resolved</h2>
 *
 * <p>Every declared key is resolved through {@link ReportFieldRegistry} <em>before</em> the write. A
 * version naming a column the registry does not declare is refused at authoring time, which means the
 * database never holds a version that would fail later — and, more importantly, an operator reviewing the
 * catalogue sees only configurations that were checked.
 *
 * <h2>Publishing is one transaction, and a conflict rolls it back</h2>
 *
 * <p>{@link #publish} retires the incumbent and publishes the successor in one transaction. A
 * {@link DuplicateKeyException} from {@code report_versions_one_published_per_definition} means a
 * concurrent publication won, and the whole transaction rolls back so a definition can never be left with
 * no live body — which would be a report that silently stops producing anything.
 */
@Service
public class ReportDefinitionService {

    private static final Logger log = LoggerFactory.getLogger(ReportDefinitionService.class);

    static final String DEFINITION_CREATED = "REPORT_DEFINITION_CREATED";
    static final String DEFINITION_RENAMED = "REPORT_DEFINITION_RENAMED";
    static final String VERSION_CREATED = "REPORT_VERSION_CREATED";
    static final String VERSION_PUBLISHED = "REPORT_VERSION_PUBLISHED";
    static final String VERSION_RETIRED = "REPORT_VERSION_RETIRED";

    private final ReportCatalogRepository catalog;
    private final TenantQueryGuard guard;
    private final ReportAuthoringGuard authoring;
    private final AuthorizationService authorization;
    private final AdminAuditStreamWriter audit;
    private final JsonCodec json;

    public ReportDefinitionService(ReportCatalogRepository catalog,
                                   TenantQueryGuard guard,
                                   ReportAuthoringGuard authoring,
                                   AuthorizationService authorization,
                                   AdminAuditStreamWriter audit,
                                   JsonCodec json) {
        this.catalog = catalog;
        this.guard = guard;
        this.authoring = authoring;
        this.authorization = authorization;
        this.audit = audit;
        this.json = json;
    }

    // ------------------------------------------------------------------
    // authoring
    // ------------------------------------------------------------------

    /** Creates a definition. The key is tenant-unique; a duplicate is a constraint violation. */
    @Transactional
    public ReportCatalogRepository.DefinitionRow createDefinition(TenantContext caller,
                                                                  UUID projectId,
                                                                  String key,
                                                                  String nameAr,
                                                                  String nameEn,
                                                                  String descriptionAr,
                                                                  String descriptionEn,
                                                                  String timezone,
                                                                  String localeDefault,
                                                                  List<String> requiredPermissions,
                                                                  String redactionPolicy,
                                                                  String retentionClass) {
        requireAuthoring(caller);
        TenantScope scope = guard.scope(caller, projectId);
        // Every field below is required by a CHECK on report_definitions, and the CHECK is the BACKSTOP --
        // it fires a DataIntegrityViolationException, which this module's advice does not handle, so it
        // reaches the application floor as 500 INTERNAL_ERROR at ERROR level for a client mistake. The
        // rules live in AuthoringRequirements because the served OpenAPI document publishes the same list
        // as its `required` array: Phase 32 shipped a contract that taught a generated client to omit
        // retentionClass, redactionPolicy and timezone, and every one of those omissions was a 500-shaped
        // answer or a message naming the literal string "null".
        AuthoringRequirements.CREATE_REPORT_DEFINITION.require(AuthoringRequirements.values(
            "key", key,
            "nameAr", nameAr,
            "nameEn", nameEn,
            "timezone", timezone,
            "redactionPolicy", redactionPolicy,
            "retentionClass", retentionClass));
        String trimmedKey = key.trim();
        ExportRedactionPolicy policy = ReportCatalogRepository.policyOf(redactionPolicy);
        String locale = ReportCatalogRepository.normalizeLocale(localeDefault);
        String zone = requireTimezone(timezone);

        UUID id = catalog.insertDefinition(scope, projectId, trimmedKey, nameAr.trim(), nameEn.trim(),
            blankToNull(descriptionAr), blankToNull(descriptionEn), zone, locale,
            declaredPermissions(requiredPermissions), policy.wireValue(), retentionClass.trim(),
            AuditAttribution.now());

        audit(caller, scope, DEFINITION_CREATED, "ReportDefinition", id,
            AuditVocabularies.AuditOutcome.SUCCESS, Map.of(
                "key", trimmedKey,
                "redactionPolicy", policy.wireValue(),
                "projectId", projectId == null ? "" : projectId.toString()));
        log.info("report definition created id={} key={} policy={} by {}", id, trimmedKey,
            policy.wireValue(), caller.auditActorLabel());
        return requireDefinition(scope, id);
    }

    /**
     * Renames a definition.
     *
     * <p>Guarded on the version the caller read, so a concurrent rename loses rather than overwrites.
     * Bodies are untouched, which is why a published definition can be renamed without invalidating the
     * exports that pinned it.
     */
    @Transactional
    public ReportCatalogRepository.DefinitionRow renameDefinition(TenantContext caller, UUID definitionId,
                                                                  String nameAr, String nameEn,
                                                                  String descriptionAr,
                                                                  String descriptionEn,
                                                                  Integer expectedVersion) {
        requireAuthoring(caller);
        TenantScope scope = guard.tenantScope(caller);
        requireDefinition(scope, definitionId);
        // After the target is resolved, not before: every authoring refusal on this surface is a
        // non-disclosing 404, and a 400 that arrives earlier tells a caller without report:manage that this
        // endpoint validates its body -- a difference between routes on a surface whose whole answer
        // vocabulary is meant to be uniform. See AuthoringRequirements.
        AuthoringRequirements.RENAME_REPORT_DEFINITION.require(AuthoringRequirements.values(
            "nameAr", nameAr,
            "nameEn", nameEn,
            "expectedVersion", expectedVersion));
        catalog.renameDefinition(scope, definitionId, nameAr.trim(), nameEn.trim(),
            blankToNull(descriptionAr), blankToNull(descriptionEn), expectedVersion,
            AuditAttribution.now());
        audit(caller, scope, DEFINITION_RENAMED, "ReportDefinition", definitionId,
            AuditVocabularies.AuditOutcome.SUCCESS,
            Map.of("expectedVersion", String.valueOf(expectedVersion.intValue())));
        return requireDefinition(scope, definitionId);
    }

    /**
     * Authors a new version.
     *
     * <p>The body is validated <em>before</em> the write. A DRAFT that cannot resolve, cannot group, or
     * names a filter that does not exist would be activatable by an operator who does not read the jsonb —
     * so the refusal happens here, where the message names the offending key.
     *
     * <p>"Editing a report creates a version", never an edit of the body an export shipped against.
     */
    @Transactional
    public ReportCatalogRepository.VersionRow createVersion(TenantContext caller, UUID definitionId,
                                                            List<String> columns,
                                                            List<String> dimensions,
                                                            List<String> measures,
                                                            Map<String, Object> filters,
                                                            List<String> defaultSort,
                                                            Map<String, Object> denominatorRules,
                                                            Map<String, Object> metricSemantics,
                                                            String changeNote) {
        requireAuthoring(caller);
        TenantScope scope = guard.tenantScope(caller);
        ReportCatalogRepository.DefinitionRow definition = requireDefinition(scope, definitionId);

        // THE 500 THAT WAS A 400. report_versions_measures_present_check is
        // CHECK (jsonb_array_length("measures") >= 1), and nothing here refused first: an absent or empty
        // `measures` passed every registry check -- requireMeasuresAreMeasures returns early for null, and
        // an empty list has nothing to reject -- and was written as '[]', so the CHECK fired. A
        // DataIntegrityViolationException has no handler in ExportExceptionHandler, so it reached the
        // application floor and answered 500 INTERNAL_ERROR, logged at ERROR, for a client that omitted one
        // array. The refusal belongs here, beside every other authoring refusal, naming the field.
        // `columns` is refused here too, for the same reason in the other direction: it used to fall through
        // to ReportFieldRegistry.resolve and be answered 422 REPORT_COLUMN_UNDECLARED, which names an
        // undeclared COLUMN when the caller omitted a FIELD -- a different problem with a different fix.
        AuthoringRequirements.CREATE_REPORT_VERSION.require(AuthoringRequirements.values(
            "columns", columns,
            "measures", measures));

        // Resolve first: an unresolvable column must never reach the database.
        List<ReportField> resolvedColumns = ReportFieldRegistry.resolve(columns);
        ReportSubject subject = ReportFieldRegistry.subjectOf(columns)
            .orElseThrow(() -> new IllegalArgumentException("A report version must declare a subject"));
        requireDimensionsAreDimensions(dimensions, subject);
        requireMeasuresAreMeasures(measures, subject);
        requireSortProjects(dimensions, defaultSort);
        requireMoneyHasCurrency(resolvedColumns);
        ReportFilterShape.validate(filters, subject);

        int nextVersion = (definition.currentVersion() == null ? 0 : definition.currentVersion()) + 1;
        UUID versionId = catalog.insertVersion(scope, definition, nextVersion,
            json.write(dimensions == null ? List.of() : dimensions),
            json.write(measures == null ? List.of() : measures),
            json.write(filters == null ? Map.of() : filters),
            // The STORED sort shape is [{"field":…,"direction":"asc|desc"}], checked by
            // firstelement_json_list_is_sort. The caller's entries are the planner's "field:asc" strings,
            // and writing those straight through would violate that check on every publication — so the
            // conversion lives in ReportSortShape rather than being inlined here.
            json.write(ReportSortShape.toStored(defaultSort, subject)),
            json.write(columns),
            json.write(denominatorRules == null ? Map.of() : denominatorRules),
            json.write(metricSemantics == null ? Map.of() : metricSemantics),
            blankToNull(changeNote), caller.userId(), AuditAttribution.now());

        audit(caller, scope, VERSION_CREATED, "ReportVersion", versionId,
            AuditVocabularies.AuditOutcome.SUCCESS, Map.of(
                "definitionId", definitionId.toString(),
                "versionNumber", String.valueOf(nextVersion),
                "columnCount", String.valueOf(resolvedColumns.size()),
                "subject", subject.storageName()));
        return requireVersion(scope, versionId);
    }

    /**
     * Publishes a version: retire the incumbent, publish the successor, move the pointer.
     *
     * <p>One transaction. A conflict on the partial unique index rolls the whole thing back and leaves the
     * incumbent live.
     */
    @Transactional
    public ReportCatalogRepository.DefinitionRow publish(TenantContext caller, UUID definitionId,
                                                        int versionNumber) {
        requireAuthoring(caller);
        TenantScope scope = guard.tenantScope(caller);
        ReportCatalogRepository.DefinitionRow definition = requireDefinition(scope, definitionId);
        ReportCatalogRepository.VersionRow target = catalog
            .findVersion(scope, definitionId, versionNumber)
            .orElseThrow(() -> new ReportCatalogNotFoundException("ReportVersion", versionNumber));

        LocalDateTime now = AuditAttribution.now();
        Integer published = definition.publishedVersion();
        if (published != null && published != versionNumber) {
            Optional<ReportCatalogRepository.VersionRow> incumbent =
                catalog.findVersion(scope, definitionId, published);
            if (incumbent.isPresent()) {
                catalog.retireVersion(scope, incumbent.get().id(), target.id(), now);
            }
        }
        try {
            if (!catalog.publishVersion(scope, target.id(), caller.userId(), now)) {
                throw new ReportCatalogConflictException(
                    "Report version " + versionNumber + " is not a DRAFT, so it cannot be published. "
                        + "A published body is immutable; author a new version instead.");
            }
        } catch (DuplicateKeyException e) {
            // The partial unique index did its job. A definition is never left with no live body because
            // the transaction rolls back with the incumbent untouched.
            throw new ReportCatalogConflictException(
                "Another publication of this report won the race; the incumbent is still live and nothing "
                    + "was changed. Re-read the definition and try again.");
        }
        catalog.markPublished(scope, definitionId, versionNumber, caller.userId(), now);

        audit(caller, scope, VERSION_PUBLISHED, "ReportVersion", target.id(),
            AuditVocabularies.AuditOutcome.SUCCESS, Map.of(
                "definitionId", definitionId.toString(),
                "versionNumber", String.valueOf(versionNumber),
                "retiredVersionNumber", published == null ? "" : String.valueOf(published)));
        log.info("report version published definition={} version={} by {}", definitionId, versionNumber,
            caller.auditActorLabel());
        return requireDefinition(scope, definitionId);
    }

    /**
     * Retires a published version, naming its successor.
     *
     * <p>A retirement that does not say where it went is the rollback problem Phase 6a already recorded, so
     * {@code report_versions_retired_check} requires both {@code retiredAt} and
     * {@code supersededByVersionId}.
     *
     * <p>Two checks happen here that used to happen, or did not happen, elsewhere. The successor requirement
     * was enforced in the controller, which meant it ran <em>before</em> this method's authorization guard:
     * a caller without {@code report:manage} posting an empty body was answered {@code 400} while every
     * other authoring refusal is a non-disclosing {@code 404}. It is enforced here, after the target is
     * resolved, from the same table the OpenAPI document publishes.
     *
     * <p>The self-supersession check did not happen anywhere. {@code report_versions_supersede_self_check}
     * refuses a version that supersedes itself, and nothing looked first, so a client naming the version it
     * was retiring reached the database and got a {@code 500} for it.
     *
     * @param successorNumber the version that replaced this one; {@code null} is a refusal naming it
     */
    @Transactional
    public void retire(TenantContext caller, UUID definitionId, int versionNumber, Integer successorNumber) {
        requireAuthoring(caller);
        TenantScope scope = guard.tenantScope(caller);
        requireDefinition(scope, definitionId);
        AuthoringRequirements.RETIRE_VERSION.require(AuthoringRequirements.values(
            "successorVersion", successorNumber));
        ReportCatalogRepository.VersionRow version = catalog
            .findVersion(scope, definitionId, versionNumber)
            .orElseThrow(() -> new ReportCatalogNotFoundException("ReportVersion", versionNumber));
        if (successorNumber == versionNumber) {
            throw new SelfSupersessionRefusal(
                "Report version " + versionNumber + " cannot be retired naming itself as its own successor. "
                    + "report_versions_supersede_self_check refuses a version that supersedes itself, and a "
                    + "retirement pointing at the body it is replacing records no history at all -- the "
                    + "successor is meant to be the version that took over.",
                "ReportVersion", version.id());
        }
        ReportCatalogRepository.VersionRow successor = catalog
            .findVersion(scope, definitionId, successorNumber.intValue())
            .orElseThrow(() -> new ReportCatalogNotFoundException("ReportVersion", successorNumber));
        if (!catalog.retireVersion(scope, version.id(), successor.id(), AuditAttribution.now())) {
            throw new ReportCatalogConflictException(
                "Report version " + versionNumber + " is not PUBLISHED, so it cannot be retired");
        }
        audit(caller, scope, VERSION_RETIRED, "ReportVersion", version.id(),
            AuditVocabularies.AuditOutcome.SUCCESS, Map.of(
                "definitionId", definitionId.toString(),
                "versionNumber", String.valueOf(versionNumber),
                "supersededByVersionNumber", String.valueOf(successorNumber)));
    }

    // ------------------------------------------------------------------
    // reads
    // ------------------------------------------------------------------

    /** The tenant's report catalogue. */
    @Transactional(readOnly = true)
    @RequirePermission(Permission.ANALYTICS_READ_PROJECT)
    public List<ReportCatalogRepository.DefinitionRow> list(TenantContext caller, UUID projectId) {
        authorization.check(caller, Permission.ANALYTICS_READ_PROJECT);
        TenantScope scope = guard.scope(caller, projectId);
        return catalog.listDefinitions(scope, projectId, ReportingExportsModule.Limits.CATALOGUE_PAGE);
    }

    /** One definition, plus every version, for the catalogue screen. */
    @Transactional(readOnly = true)
    @RequirePermission(Permission.ANALYTICS_READ_PROJECT)
    public ReportCatalogRepository.DefinitionRow detail(TenantContext caller, UUID definitionId) {
        authorization.check(caller, Permission.ANALYTICS_READ_PROJECT);
        TenantScope scope = guard.tenantScope(caller);
        return requireDefinition(scope, definitionId);
    }

    /** A definition's version timeline. */
    @Transactional(readOnly = true)
    @RequirePermission(Permission.ANALYTICS_READ_PROJECT)
    public List<ReportCatalogRepository.VersionRow> versions(TenantContext caller, UUID definitionId) {
        authorization.check(caller, Permission.ANALYTICS_READ_PROJECT);
        TenantScope scope = guard.tenantScope(caller);
        requireDefinition(scope, definitionId);
        return catalog.listVersions(scope, definitionId, ReportingExportsModule.Limits.VERSION_PAGE);
    }

    /**
     * The body a job pinned, resolved from its immutable version row.
     *
     * <p>This is the whole reproducibility mechanism. The job stores
     * {@code (reportDefinitionId, reportVersion)}; this method re-reads that version, which is immutable
     * and never purged (ADR-0009 §4), so the definition may be edited any number of times afterwards
     * without altering the export. An export pinning version 1 keeps producing version 1's columns forever,
     * whether version 1 is still PUBLISHED or has been RETIRED.
     */
    @Transactional(readOnly = true)
    public Optional<ReportCatalogRepository.VersionRow> pinnedVersion(TenantScope scope, UUID definitionId,
                                                                     int versionNumber) {
        return catalog.findVersion(scope, definitionId, versionNumber);
    }

    /** One definition by its tenant-unique key. */
    @Transactional(readOnly = true)
    @RequirePermission(Permission.ANALYTICS_READ_PROJECT)
    public Optional<ReportCatalogRepository.DefinitionRow> byKey(TenantContext caller, String key) {
        authorization.check(caller, Permission.ANALYTICS_READ_PROJECT);
        return catalog.findDefinitionByKey(guard.tenantScope(caller), key);
    }

    // ------------------------------------------------------------------
    // validation
    // ------------------------------------------------------------------

    /**
     * The declared dimensions must actually be dimensions of the version's subject.
     *
     * <p>{@code report_versions_dimensions_shape_check} only proves the value is an array of strings; this
     * proves it means something. A version that lists a measure as a dimension would produce a
     * {@code GROUP BY} over an aggregate, which PostgreSQL refuses at run time — and a refusal at run time
     * is a failed export job rather than a refused authoring call.
     */
    private static void requireDimensionsAreDimensions(List<String> dimensions, ReportSubject subject) {
        if (dimensions == null) {
            return;
        }
        for (String key : dimensions) {
            ReportField field = ReportField.byKey(key).orElseThrow(
                () -> new ReportFieldRegistry.UnknownReportFieldException(key,
                    "Declared dimensions are " + ReportFieldRegistry.fieldKeysOf(subject) + "."));
            if (field.subject() != subject) {
                throw new IllegalArgumentException(
                    "Dimension " + RefusalText.of(key) + " belongs to the " + field.subject()
                        + " subject, not " + subject);
            }
            if (field.isMeasure()) {
                throw new IllegalArgumentException(
                    "Column " + RefusalText.of(key) + " is a measure and cannot be a grouping dimension. "
                        + "Declare it in measures; a GROUP BY over an aggregate is refused by PostgreSQL "
                        + "at run time, which would fail a job rather than this call.");
            }
        }
    }

    private static void requireMeasuresAreMeasures(List<String> measures, ReportSubject subject) {
        if (measures == null) {
            return;
        }
        for (String key : measures) {
            ReportField field = ReportField.byKey(key).orElseThrow(
                () -> new ReportFieldRegistry.UnknownReportFieldException(key,
                    "Declared measures are " + ReportFieldRegistry.fieldKeysOf(subject) + "."));
            if (field.subject() != subject) {
                throw new IllegalArgumentException(
                    "Measure " + RefusalText.of(key) + " belongs to the " + field.subject()
                        + " subject, not " + subject);
            }
            if (!field.isMeasure()) {
                throw new IllegalArgumentException(
                    "Column " + RefusalText.of(key) + " is a dimension, not a measure, so summing it would "
                        + "either double-count it or produce an aggregate of a grouping key. Declare it in "
                        + "dimensions.");
            }
        }
    }

    /** A sort must name one of the version's grouping dimensions. */
    private static void requireSortProjects(List<String> dimensions, List<String> defaultSort) {
        if (defaultSort == null || defaultSort.isEmpty()) {
            return;
        }
        Set<String> sortable = new LinkedHashSet<>(dimensions == null ? List.of() : dimensions);
        for (String entry : defaultSort) {
            // `{"defaultSort": [null]}` binds a null element, and entry.lastIndexOf on it was a
            // NullPointerException -- a 500 at the floor for a body a person could type. Refused here,
            // named, like every other thing this method refuses.
            if (entry == null || entry.isBlank()) {
                throw new IllegalArgumentException(
                    "A report version requires each 'defaultSort' entry to name a field, but one entry is "
                        + "absent. A sort entry with no field is a suggestion the reader cannot act on, "
                        + "which is what report_versions_sort_entries_check refuses at the database.");
            }
            String field = entry;
            int colon = entry.lastIndexOf(':');
            if (colon > 0) {
                String direction = entry.substring(colon + 1).toLowerCase(java.util.Locale.ROOT);
                if (!direction.equals("asc") && !direction.equals("desc")) {
                    throw new IllegalArgumentException(
                        "Sort direction must be 'asc' or 'desc', got " + RefusalText.of(direction) + " in "
                            + RefusalText.of(entry));
                }
                field = entry.substring(0, colon);
            }
            if (!sortable.contains(field)) {
                throw new IllegalArgumentException(
                    "Sort entry " + RefusalText.of(entry) + " must name one of the version's grouping "
                        + "dimensions " + sortable + ". Sorting by a measure, or by a dimension the version "
                        + "does not group by, produces an order the statement cannot express.");
            }
        }
    }

    /** A money column with no currency column beside it is refused here, not at write time. */
    private static void requireMoneyHasCurrency(List<ReportField> columns) {
        if (!com.firstelement.exports.format.ExportValueFormatter.hasCurrencyBesideMoney(columns)) {
            throw new IllegalArgumentException(
                "This version selects a money column but not usage.currency. ADR 0007 rule 6: there is "
                    + "no amount without a currency, and a money column whose currency is only implied by "
                    + "the report is exactly the case that rule forbids.");
        }
    }

    private ReportCatalogRepository.DefinitionRow requireDefinition(TenantScope scope, UUID definitionId) {
        return catalog.findDefinition(scope, definitionId)
            .orElseThrow(() -> PolicyDeniedException.tenantOutOfScope("report-definition-out-of-scope",
                scope.organizationId(), definitionId));
    }

    private ReportCatalogRepository.VersionRow requireVersion(TenantScope scope, UUID versionId) {
        return catalog.findVersion(scope, versionId)
            .orElseThrow(() -> PolicyDeniedException.tenantOutOfScope("report-version-out-of-scope",
                scope.organizationId(), versionId));
    }

    /**
     * Every authoring call requires a resolved, authorized caller, and it is the FIRST statement of each.
     *
     * <p>Delegated to {@link ReportAuthoringGuard} so the permission, the shape of the refusal and the
     * machine-principal refusal live in one place rather than being restated per method. Called before the
     * tenant scope is resolved: there is no statement to be built, and therefore nothing to leak, before
     * the caller is known to be allowed to ask.
     */
    private void requireAuthoring(TenantContext caller) {
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * A real IANA zone, checked here rather than only by the schema.
     *
     * <p>{@code firstelement_valid_timezone} already refuses {@code Asia/Riyaddah} at the database, but the
     * check here names the field in the message; the schema's refusal is the backstop, not the only gate.
     *
     * <p>Presence is not checked here. It is {@code CREATE_REPORT_DEFINITION}'s rule, which runs first and
     * names the field with the constraint that requires it — and a private helper that re-derived the same
     * message would be a second answer to the same question.
     */
    private static String requireTimezone(String timezone) {
        String zone = timezone.trim();
        if (!java.time.ZoneId.getAvailableZoneIds().contains(zone)) {
            throw new IllegalArgumentException(
                "timezone " + RefusalText.of(zone) + " is not a zone this JVM knows. A report that buckets by "
                    + "a day must not inherit the server's zone, so the zone is declared and must be real.");
        }
        return zone;
    }

    /** The permissions a version demands, deduplicated and blank-free. */
    private static List<String> declaredPermissions(List<String> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return List.of();
        }
        Set<String> declared = new LinkedHashSet<>();
        for (String permission : permissions) {
            if (permission == null || permission.isBlank()) {
                // report_definitions_required_permissions_check refuses a blank entry, and a blank
                // permission is one nobody can hold, which would look configured in the Admin UI.
                throw new IllegalArgumentException(
                    "requiredPermissions may not contain a blank entry: a permission nobody can hold is "
                        + "not a requirement, it is a typo.");
            }
            Permission.fromValue(permission.trim());
            declared.add(permission.trim());
        }
        return List.copyOf(declared);
    }

    /**
     * The catalogue of declared keys, for the authoring surface.
     *
     * @return per-subject field and filter keys, the declared redaction policies, and the bounded DOCX
     *         metadata placeholder names. Never a SQL expression: a client that could read the expressions
     *         could start choosing columns rather than keys, which is the boundary this module exists to
     *         hold.
     */
    public Map<String, Object> catalogue() {
        Map<String, Map<String, List<String>>> bySubject = new java.util.LinkedHashMap<>();
        for (ReportSubject subject : ReportSubject.values()) {
            Map<String, List<String>> entry = new java.util.LinkedHashMap<>();
            entry.put("fields", ReportFieldRegistry.fieldKeysOf(subject));
            entry.put("filters", ReportFieldRegistry.filterKeysOf(subject));
            bySubject.put(subject.storageName(), entry);
        }
        return Map.of("subjects", bySubject,
            "redactionPolicies", ExportRedactionPolicy.declared(),
            "metadataPlaceholders",
            com.firstelement.exports.format.DocxTemplateRenderer.metaNames());
    }

    /**
     * Every declared field, for the authoring surface's column picker with its labels.
     *
     * <p>Static because it is a property of the registry rather than of any tenant's catalogue: it reads
     * no row and no permission, so giving it an instance would imply a scope it does not have.
     */
    public static List<Map<String, String>> fieldCatalogue() {
        List<Map<String, String>> entries = new ArrayList<>();
        for (ReportField field : ReportField.values()) {
            entries.add(ExportValueFormatterMetadata.of(field));
        }
        return entries;
    }

    /** The version timeline of a definition, newest first. */
    public List<ReportCatalogRepository.VersionRow> timeline(TenantContext caller, UUID definitionId) {
        List<ReportCatalogRepository.VersionRow> versions = versions(caller, definitionId);
        List<ReportCatalogRepository.VersionRow> newestFirst = new ArrayList<>(versions);
        java.util.Collections.reverse(newestFirst);
        return List.copyOf(newestFirst);
    }
}