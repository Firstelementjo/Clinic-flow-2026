package com.firstelement.exports.persistence;

import com.firstelement.exports.catalog.ExportRedactionPolicy;
import com.firstelement.exports.catalog.ReportSubject;
import com.firstelement.security.error.PolicyDeniedException;
import com.firstelement.security.query.ScopedWhere;
import com.firstelement.security.query.TenantScope;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The versioned report catalogue: {@code report_definitions}, {@code report_versions},
 * {@code document_templates} and {@code document_template_versions}.
 *
 * <h2>Why a version is immutable and a definition is not</h2>
 *
 * <p>The migration draws the line itself: {@code report_versions} has no {@code updatedAt} and its
 * post-condition guard fails the migration if one appears, while {@code report_definitions} does have one
 * so a DRAFT can be renamed. That is the whole reproducibility argument: a published number must be
 * reproducible after the report has been edited, so "fix the typo in the measure" is a <em>new version</em>,
 * never an update of the one an export already shipped against.
 *
 * <h2>Publication is a partial-index race, decided by the database</h2>
 *
 * <p>{@code report_versions_one_published_per_definition} is a partial unique index on
 * {@code (organizationId, reportDefinitionId) WHERE state = 'PUBLISHED'}. A concurrent double publication
 * therefore loses <em>on the index</em>, not on application ordering — which is why {@link #publish}
 * retires the incumbent and publishes the successor inside one transaction and treats a
 * {@code DuplicateKeyException} as the concurrent case rather than as a bug.
 *
 * <h2>Nothing here executes a report</h2>
 *
 * <p>The jsonb columns are read as strings and handed to the registry for resolution. No method in this
 * class assembles SQL from them, and none of them can reach a statement except through
 * {@code ExportQueryPlanner}. That separation is the reason "a report definition is a declared, versioned
 * shape, not a query" is a structural property rather than a convention.
 */
@Repository
public class ReportCatalogRepository {

    // ------------------------------------------------------------------
    // shapes
    // ------------------------------------------------------------------

    /** One report definition, the mutable row. */
    public record DefinitionRow(UUID id,
                                UUID organizationId,
                                UUID projectId,
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
                                LocalDateTime publishedAt,
                                UUID publishedByUserId,
                                int version,
                                LocalDateTime createdAt,
                                LocalDateTime updatedAt) {
    }

    /** One immutable report version body. */
    public record VersionRow(UUID id,
                             UUID organizationId,
                             UUID projectId,
                             UUID reportDefinitionId,
                             int versionNumber,
                             String dimensionsJson,
                             String measuresJson,
                             String filtersJson,
                             String defaultSortJson,
                             String columnsJson,
                             String denominatorRulesJson,
                             String metricSemanticsJson,
                             String state,
                             String changeNote,
                             LocalDateTime publishedAt,
                             UUID publishedByUserId,
                             LocalDateTime retiredAt,
                             UUID supersededByVersionId,
                             UUID createdByUserId,
                             LocalDateTime createdAt) {
    }

    /** One document template definition. */
    public record TemplateRow(UUID id,
                              UUID organizationId,
                              UUID projectId,
                              String key,
                              String nameAr,
                              String nameEn,
                              String kind,
                              String status,
                              Integer currentVersion,
                              Integer publishedVersion,
                              LocalDateTime publishedAt,
                              UUID publishedByUserId,
                              String locale,
                              String timezone,
                              int version,
                              LocalDateTime createdAt,
                              LocalDateTime updatedAt) {
    }

    /** One immutable template version. */
    public record TemplateVersionRow(UUID id,
                                     UUID organizationId,
                                     UUID projectId,
                                     UUID documentTemplateId,
                                     int versionNumber,
                                     String locale,
                                     String storageKey,
                                     String contentHash,
                                     String placeholderSchemaJson,
                                     String state,
                                     String changeNote,
                                     LocalDateTime publishedAt,
                                     UUID publishedByUserId,
                                     LocalDateTime retiredAt,
                                     UUID supersededByVersionId,
                                     UUID createdByUserId,
                                     LocalDateTime createdAt) {
    }

    // ------------------------------------------------------------------
    // statements
    // ------------------------------------------------------------------

    private static final String FIND_DEFINITION = """
        select d."id", d."organizationId", d."projectId", d."key", d."nameAr", d."nameEn",
               d."descriptionAr", d."descriptionEn", d."status"::text as "status", d."timezone",
               d."localeDefault"::text as "localeDefault", d."requiredPermissions", d."redactionPolicy",
               d."retentionClass", d."currentVersion", d."publishedVersion", d."publishedAt",
               d."publishedByUserId", d."version", d."createdAt", d."updatedAt"
          from report_definitions d
         where d."organizationId" = :__fe_tenant_organization_id
           and d."id" = :id
        """;

    private static final String FIND_DEFINITION_BY_KEY = """
        select d."id", d."organizationId", d."projectId", d."key", d."nameAr", d."nameEn",
               d."descriptionAr", d."descriptionEn", d."status"::text as "status", d."timezone",
               d."localeDefault"::text as "localeDefault", d."requiredPermissions", d."redactionPolicy",
               d."retentionClass", d."currentVersion", d."publishedVersion", d."publishedAt",
               d."publishedByUserId", d."version", d."createdAt", d."updatedAt"
          from report_definitions d
         where d."organizationId" = :__fe_tenant_organization_id
           and d."key" = :key
        """;

    private static final String LIST_DEFINITIONS = """
        select d."id", d."organizationId", d."projectId", d."key", d."nameAr", d."nameEn",
               d."descriptionAr", d."descriptionEn", d."status"::text as "status", d."timezone",
               d."localeDefault"::text as "localeDefault", d."requiredPermissions", d."redactionPolicy",
               d."retentionClass", d."currentVersion", d."publishedVersion", d."publishedAt",
               d."publishedByUserId", d."version", d."createdAt", d."updatedAt"
          from report_definitions d
         where d."organizationId" = :__fe_tenant_organization_id
           and (cast(:projectId as uuid) is null or d."projectId" = cast(:projectId as uuid)
                or d."projectId" is null)
         order by d."key"
         limit :limit
        """;

    private static final String FIND_VERSION = """
        select v."id", v."organizationId", v."projectId", v."reportDefinitionId", v."versionNumber",
               v."dimensions"::text as "dimensionsJson", v."measures"::text as "measuresJson",
               v."filters"::text as "filtersJson", v."defaultSort"::text as "defaultSortJson",
               v."columns"::text as "columnsJson", v."denominatorRules"::text as "denominatorRulesJson",
               v."metricSemantics"::text as "metricSemanticsJson", v."state"::text as "state",
               v."changeNote", v."publishedAt", v."publishedByUserId", v."retiredAt",
               v."supersededByVersionId", v."createdByUserId", v."createdAt"
          from report_versions v
         where v."organizationId" = :__fe_tenant_organization_id
           and v."id" = :id
        """;

    /** One version by number, which is how a job re-resolves its pinned definition body. */
    private static final String FIND_VERSION_BY_NUMBER = """
        select v."id", v."organizationId", v."projectId", v."reportDefinitionId", v."versionNumber",
               v."dimensions"::text as "dimensionsJson", v."measures"::text as "measuresJson",
               v."filters"::text as "filtersJson", v."defaultSort"::text as "defaultSortJson",
               v."columns"::text as "columnsJson", v."denominatorRules"::text as "denominatorRulesJson",
               v."metricSemantics"::text as "metricSemanticsJson", v."state"::text as "state",
               v."changeNote", v."publishedAt", v."publishedByUserId", v."retiredAt",
               v."supersededByVersionId", v."createdByUserId", v."createdAt"
          from report_versions v
         where v."organizationId" = :__fe_tenant_organization_id
           and v."reportDefinitionId" = :reportDefinitionId
           and v."versionNumber" = :versionNumber
        """;

    private static final String LIST_VERSIONS = """
        select v."id", v."organizationId", v."projectId", v."reportDefinitionId", v."versionNumber",
               v."dimensions"::text as "dimensionsJson", v."measures"::text as "measuresJson",
               v."filters"::text as "filtersJson", v."defaultSort"::text as "defaultSortJson",
               v."columns"::text as "columnsJson", v."denominatorRules"::text as "denominatorRulesJson",
               v."metricSemantics"::text as "metricSemanticsJson", v."state"::text as "state",
               v."changeNote", v."publishedAt", v."publishedByUserId", v."retiredAt",
               v."supersededByVersionId", v."createdByUserId", v."createdAt"
          from report_versions v
         where v."organizationId" = :__fe_tenant_organization_id
           and v."reportDefinitionId" = :reportDefinitionId
         order by v."versionNumber"
         limit :limit
        """;

    private static final String FIND_TEMPLATE = """
        select t."id", t."organizationId", t."projectId", t."key", t."nameAr", t."nameEn", t."kind",
               t."status"::text as "status", t."currentVersion", t."publishedVersion", t."publishedAt",
               t."publishedByUserId", t."locale"::text as "locale", t."timezone", t."version",
               t."createdAt", t."updatedAt"
          from document_templates t
         where t."organizationId" = :__fe_tenant_organization_id
           and t."id" = :id
        """;

    private static final String LIST_TEMPLATES = """
        select t."id", t."organizationId", t."projectId", t."key", t."nameAr", t."nameEn", t."kind",
               t."status"::text as "status", t."currentVersion", t."publishedVersion", t."publishedAt",
               t."publishedByUserId", t."locale"::text as "locale", t."timezone", t."version",
               t."createdAt", t."updatedAt"
          from document_templates t
         where t."organizationId" = :__fe_tenant_organization_id
           and (cast(:projectId as uuid) is null or t."projectId" = cast(:projectId as uuid)
                or t."projectId" is null)
         order by t."key"
         limit :limit
        """;

    private static final String FIND_TEMPLATE_VERSION = """
        select tv."id", tv."organizationId", tv."projectId", tv."documentTemplateId", tv."versionNumber",
               tv."locale"::text as "locale", tv."storageKey", tv."contentHash",
               tv."placeholderSchema"::text as "placeholderSchemaJson", tv."state"::text as "state",
               tv."changeNote", tv."publishedAt", tv."publishedByUserId", tv."retiredAt",
               tv."supersededByVersionId", tv."createdByUserId", tv."createdAt"
          from document_template_versions tv
         where tv."organizationId" = :__fe_tenant_organization_id
           and tv."documentTemplateId" = :documentTemplateId
           and tv."versionNumber" = :versionNumber
        """;

    /** The published version of a template, which is what a DOCX export resolves. */
    private static final String FIND_PUBLISHED_TEMPLATE_VERSION = """
        select tv."id", tv."organizationId", tv."projectId", tv."documentTemplateId", tv."versionNumber",
               tv."locale"::text as "locale", tv."storageKey", tv."contentHash",
               tv."placeholderSchema"::text as "placeholderSchemaJson", tv."state"::text as "state",
               tv."changeNote", tv."publishedAt", tv."publishedByUserId", tv."retiredAt",
               tv."supersededByVersionId", tv."createdByUserId", tv."createdAt"
          from document_template_versions tv
         where tv."organizationId" = :__fe_tenant_organization_id
           and tv."documentTemplateId" = :documentTemplateId
           and tv."state" = 'PUBLISHED'
        """;

    /**
     * Every version of one template, oldest first.
     *
     * <p>The catalogue reads this rather than scanning version numbers one at a time. A scan is correct
     * only because the numbering happens to be dense, and "correct only because an unrelated invariant
     * holds" is not a property a catalogue should depend on: a template whose versions were numbered 1, 2
     * and 7 would publish 3, 4, 5 and 6 as absent by accident rather than by design.
     */
    private static final String LIST_TEMPLATE_VERSIONS = """
        select tv."id", tv."organizationId", tv."projectId", tv."documentTemplateId", tv."versionNumber",
               tv."locale"::text as "locale", tv."storageKey", tv."contentHash",
               tv."placeholderSchema"::text as "placeholderSchemaJson", tv."state"::text as "state",
               tv."changeNote", tv."publishedAt", tv."publishedByUserId", tv."retiredAt",
               tv."supersededByVersionId", tv."createdByUserId", tv."createdAt"
          from document_template_versions tv
         where tv."organizationId" = :__fe_tenant_organization_id
           and tv."documentTemplateId" = :documentTemplateId
         order by tv."versionNumber"
         limit :limit
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public ReportCatalogRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // definitions
    // ------------------------------------------------------------------

    /**
     * Writes a definition.
     *
     * <p>{@code localeDefault} and {@code timezone} are explicit parameters with no server default,
     * because the schema declares them that way for a reason: a report that buckets by day must not
     * produce different numbers on two workers, so the zone is the report's own declaration and never the
     * JVM's.
     */
    public UUID insertDefinition(TenantScope scope,
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
                                 String retentionClass,
                                 LocalDateTime now) {
        UUID id = UUID.randomUUID();
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of())
            .addValue("id", id)
            .addValue("projectId", projectId, java.sql.Types.OTHER)
            .addValue("key", key)
            .addValue("nameAr", nameAr)
            .addValue("nameEn", nameEn)
            .addValue("descriptionAr", descriptionAr)
            .addValue("descriptionEn", descriptionEn)
            .addValue("timezone", timezone)
            .addValue("localeDefault", localeDefault)
            .addValue("requiredPermissions", requiredPermissions.toArray(new String[0]))
            .addValue("redactionPolicy", redactionPolicy)
            .addValue("retentionClass", retentionClass)
            .addValue("now", now);
        jdbc.update("""
            insert into report_definitions ("id", "organizationId", "projectId", "key", "nameAr",
                "nameEn", "descriptionAr", "descriptionEn", "status", "timezone", "localeDefault",
                "requiredPermissions", "redactionPolicy", "retentionClass", "version", "createdAt",
                "updatedAt")
            values (:id, :__fe_tenant_organization_id, :projectId, :key, :nameAr, :nameEn,
                :descriptionAr, :descriptionEn, cast('DRAFT' as "ReportStatus"), :timezone,
                cast(:localeDefault as "Locale"), :requiredPermissions, :redactionPolicy,
                :retentionClass, 0, :now, :now)
            """, parameters);
        return id;
    }

    /**
     * Renames a definition.
     *
     * <p>The only mutable operation on a definition, and it touches the labels and the version pointer —
     * never a body. A published definition can therefore be renamed without invalidating the export that
     * pinned it.
     */
    public void renameDefinition(TenantScope scope, UUID definitionId, String nameAr, String nameEn,
                                 String descriptionAr, String descriptionEn, int expectedVersion,
                                 LocalDateTime now) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of())
            .addValue("id", definitionId)
            .addValue("nameAr", nameAr)
            .addValue("nameEn", nameEn)
            .addValue("descriptionAr", descriptionAr)
            .addValue("descriptionEn", descriptionEn)
            .addValue("expectedVersion", expectedVersion)
            .addValue("now", now);
        int updated = jdbc.update("""
            update report_definitions
               set "nameAr" = :nameAr, "nameEn" = :nameEn, "descriptionAr" = :descriptionAr,
                   "descriptionEn" = :descriptionEn, "version" = "version" + 1, "updatedAt" = :now
             where "organizationId" = :__fe_tenant_organization_id
               and "id" = :id
               and "version" = :expectedVersion
            """, parameters);
        if (updated != 1) {
            throw new ConcurrentDefinitionChangeException(
                "Report definition " + definitionId + " changed while the rename was in flight; "
                    + "re-read it and try again. updatedRows=" + updated);
        }
    }

    /** One definition by id. */
    public Optional<DefinitionRow> findDefinition(TenantScope scope, UUID definitionId) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of())
            .addValue("id", definitionId);
        return jdbc.query(FIND_DEFINITION, parameters, ReportCatalogRepository::mapDefinition)
            .stream().findFirst();
    }

    /**
     * One definition by its tenant-unique key.
     *
     * <p>The key is unique PER TENANT, never globally: a global index would turn "that key already
     * exists" into a cross-tenant existence oracle, which is a disclosure in its own right.
     */
    public Optional<DefinitionRow> findDefinitionByKey(TenantScope scope, String key) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of("key", key));
        return jdbc.query(FIND_DEFINITION_BY_KEY, parameters, ReportCatalogRepository::mapDefinition)
            .stream().findFirst();
    }

    /** The tenant's report catalogue, tenant-wide entries plus one project's. */
    public List<DefinitionRow> listDefinitions(TenantScope scope, UUID projectId, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("limit", Math.max(1, limit)));
        parameters.addValue("projectId", projectId, java.sql.Types.OTHER);
        return jdbc.query(LIST_DEFINITIONS, parameters, ReportCatalogRepository::mapDefinition);
    }

    // ------------------------------------------------------------------
    // versions
    // ------------------------------------------------------------------

    /**
     * Writes one immutable version body.
     *
     * <p>Every jsonb column is passed already serialized. Nothing is merged with an existing body and
     * nothing is derived from the previous version: "editing a report creates a version", which is what
     * makes a pinned export reproducible.
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public UUID insertVersion(TenantScope scope,
                              DefinitionRow definition,
                              int versionNumber,
                              String dimensionsJson,
                              String measuresJson,
                              String filtersJson,
                              String defaultSortJson,
                              String columnsJson,
                              String denominatorRulesJson,
                              String metricSemanticsJson,
                              String changeNote,
                              UUID createdByUserId,
                              LocalDateTime now) {
        UUID id = UUID.randomUUID();
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of())
            .addValue("id", id)
            .addValue("projectId", definition.projectId(), java.sql.Types.OTHER)
            .addValue("definitionId", definition.id())
            .addValue("versionNumber", versionNumber)
            .addValue("dimensionsJson", dimensionsJson, java.sql.Types.OTHER)
            .addValue("measuresJson", measuresJson, java.sql.Types.OTHER)
            .addValue("filtersJson", filtersJson, java.sql.Types.OTHER)
            .addValue("defaultSortJson", defaultSortJson, java.sql.Types.OTHER)
            .addValue("columnsJson", columnsJson, java.sql.Types.OTHER)
            .addValue("denominatorRulesJson", denominatorRulesJson, java.sql.Types.OTHER)
            .addValue("metricSemanticsJson", metricSemanticsJson, java.sql.Types.OTHER)
            .addValue("changeNote", changeNote)
            .addValue("author", createdByUserId)
            .addValue("now", now);
        jdbc.update("""
            insert into report_versions ("id", "organizationId", "projectId", "reportDefinitionId",
                "versionNumber", "dimensions", "measures", "filters", "defaultSort", "columns",
                "denominatorRules", "metricSemantics", "state", "changeNote", "createdByUserId",
                "createdAt")
            values (:id, :__fe_tenant_organization_id, :projectId, :definitionId, :versionNumber,
                cast(:dimensionsJson as jsonb), cast(:measuresJson as jsonb),
                cast(:filtersJson as jsonb), cast(:defaultSortJson as jsonb),
                cast(:columnsJson as jsonb), cast(:denominatorRulesJson as jsonb),
                cast(:metricSemanticsJson as jsonb), cast('DRAFT' as "ReportStatus"), :changeNote,
                :author, :now)
            """, parameters);
        advanceCurrentVersion(scope, definition.id(), versionNumber, now);
        return id;
    }

    /**
     * {@code DRAFT -> PUBLISHED}.
     *
     * <p>Guarded on the DRAFT state and on the author, and it moves {@code publishedAt} and
     * {@code publishedByUserId} together because {@code report_versions_published_pair_check} is a
     * biconditional over exactly those two.
     */
    public boolean publishVersion(TenantScope scope, UUID versionId, UUID publishedByUserId,
                                  LocalDateTime now) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of())
            .addValue("id", versionId)
            .addValue("actor", publishedByUserId)
            .addValue("now", now);
        return jdbc.update("""
            update report_versions
               set "state" = cast('PUBLISHED' as "ReportStatus"),
                   "publishedAt" = :now,
                   "publishedByUserId" = :actor
             where "organizationId" = :__fe_tenant_organization_id
               and "id" = :id
               and "state" = cast('DRAFT' as "ReportStatus")
            """, parameters) == 1;
    }

    /**
     * {@code PUBLISHED -> RETIRED}, naming what replaced it.
     *
     * <p>The publication columns are KEPT, and that is the schema talking.
     *
     * <p>{@code report_versions_published_state_check} is
     * {@code CHECK ((state = 'DRAFT') = (publishedAt IS NULL))} — a biconditional over DRAFT and nothing
     * else. It says {@code publishedAt} is null exactly when the version is a DRAFT; it does not say a
     * retired version was never published, and the truth table makes clearing the column impossible:
     * {@code (false) = (true)} is false, so an UPDATE that set {@code publishedAt = null} while moving to
     * {@code RETIRED} is refused by the database.
     *
     * <p>It previously did exactly that, so <b>retiring a report version had never worked</b> — every call
     * raised {@code DataIntegrityViolationException} and the web layer answered 500. Phase 31 found it by
     * asserting the lifecycle the schema permits rather than the lifecycle the code described. The comment
     * here previously claimed the opposite ("RETIRED is not DRAFT, so the pair must go together"), which is
     * a correct statement of the biconditional's left-hand side used to justify an inference its right-hand
     * side contradicts.
     *
     * <p>Keeping both columns is also the better record: when a retired version was live is a fact about
     * the report's history, and an audit reader asking "which version produced the March number" needs it.
     * {@code report_definitions."publishedVersion"} moves on; the retired row's own attribution does not get
     * rewritten.
     */
    public boolean retireVersion(TenantScope scope, UUID versionId, UUID supersededByVersionId,
                                 LocalDateTime now) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of())
            .addValue("id", versionId)
            .addValue("successor", supersededByVersionId)
            .addValue("now", now);
        return jdbc.update("""
            update report_versions
               set "state" = cast('RETIRED' as "ReportStatus"),
                   "retiredAt" = :now,
                   "supersededByVersionId" = :successor
             where "organizationId" = :__fe_tenant_organization_id
               and "id" = :id
               and "state" = cast('PUBLISHED' as "ReportStatus")
            """, parameters) == 1;
    }

    /** Moves the definition's version pointers after a publish. */
    public void markPublished(TenantScope scope, UUID definitionId, int versionNumber, UUID actorId,
                              LocalDateTime now) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of())
            .addValue("id", definitionId)
            .addValue("versionNumber", versionNumber)
            .addValue("actor", actorId)
            .addValue("now", now);
        jdbc.update("""
            update report_definitions
               set "status" = cast('PUBLISHED' as "ReportStatus"),
                   "publishedVersion" = :versionNumber,
                   "publishedAt" = :now,
                   "publishedByUserId" = :actor,
                   "version" = "version" + 1,
                   "updatedAt" = :now
             where "organizationId" = :__fe_tenant_organization_id
               and "id" = :id
            """, parameters);
    }

    /** Moves {@code currentVersion} to the newest authored body. */
    public void advanceCurrentVersion(TenantScope scope, UUID definitionId, int versionNumber,
                                      LocalDateTime now) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of())
            .addValue("id", definitionId)
            .addValue("versionNumber", versionNumber)
            .addValue("now", now);
        jdbc.update("""
            update report_definitions
               set "currentVersion" = :versionNumber, "version" = "version" + 1, "updatedAt" = :now
             where "organizationId" = :__fe_tenant_organization_id
               and "id" = :id
            """, parameters);
    }

    /** One version by id. */
    public Optional<VersionRow> findVersion(TenantScope scope, UUID versionId) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of())
            .addValue("id", versionId);
        return jdbc.query(FIND_VERSION, parameters, ReportCatalogRepository::mapVersion)
            .stream().findFirst();
    }

    /**
     * One version by definition and number.
     *
     * <p>This is how a job re-resolves its pinned definition body at generation time, and the reason
     * "a later definition change cannot alter a job already queued" is true: the version row is immutable
     * and never purged (ADR-0009 §4: "a version is referenced by historical runs"), so re-reading it
     * returns exactly the body the job was created against — whether it is the PUBLISHED one or an older
     * RETIRED one.
     */
    public Optional<VersionRow> findVersion(TenantScope scope, UUID definitionId, int versionNumber) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of())
            .addValue("reportDefinitionId", definitionId)
            .addValue("versionNumber", versionNumber);
        return jdbc.query(FIND_VERSION_BY_NUMBER, parameters, ReportCatalogRepository::mapVersion)
            .stream().findFirst();
    }

    /** Every version of one definition, oldest first. */
    public List<VersionRow> listVersions(TenantScope scope, UUID definitionId, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of(
            "reportDefinitionId", definitionId,
            "limit", Math.max(1, limit)));
        return jdbc.query(LIST_VERSIONS, parameters, ReportCatalogRepository::mapVersion);
    }

    // ------------------------------------------------------------------
    // document templates
    // ------------------------------------------------------------------

    /** Writes a template definition. */
    public UUID insertTemplate(TenantScope scope,
                               UUID projectId,
                               String key,
                               String nameAr,
                               String nameEn,
                               String kind,
                               String locale,
                               String timezone,
                               LocalDateTime now) {
        UUID id = UUID.randomUUID();
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of())
            .addValue("id", id)
            .addValue("projectId", projectId, java.sql.Types.OTHER)
            .addValue("key", key)
            .addValue("nameAr", nameAr)
            .addValue("nameEn", nameEn)
            .addValue("kind", kind)
            .addValue("locale", locale)
            .addValue("timezone", timezone)
            .addValue("now", now);
        jdbc.update("""
            insert into document_templates ("id", "organizationId", "projectId", "key", "nameAr",
                "nameEn", "kind", "status", "locale", "timezone", "version", "createdAt", "updatedAt")
            values (:id, :__fe_tenant_organization_id, :projectId, :key, :nameAr, :nameEn, :kind,
                cast('DRAFT' as "DocumentTemplateStatus"), cast(:locale as "Locale"), :timezone, 0,
                :now, :now)
            """, parameters);
        return id;
    }

    /** Writes one immutable template version, with the stored object's key and its content hash. */
    public UUID insertTemplateVersion(TenantScope scope,
                                      TemplateRow template,
                                      int versionNumber,
                                      String locale,
                                      String storageKey,
                                      String contentHash,
                                      String placeholderSchemaJson,
                                      String changeNote,
                                      UUID createdByUserId,
                                      LocalDateTime now) {
        UUID id = UUID.randomUUID();
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of())
            .addValue("id", id)
            .addValue("projectId", template.projectId(), java.sql.Types.OTHER)
            .addValue("templateId", template.id())
            .addValue("versionNumber", versionNumber)
            .addValue("locale", locale)
            .addValue("storageKey", storageKey)
            .addValue("contentHash", contentHash)
            .addValue("placeholderSchemaJson", placeholderSchemaJson, java.sql.Types.OTHER)
            .addValue("changeNote", changeNote)
            .addValue("author", createdByUserId)
            .addValue("now", now);
        jdbc.update("""
            insert into document_template_versions ("id", "organizationId", "projectId",
                "documentTemplateId", "versionNumber", "locale", "storageKey", "contentHash",
                "placeholderSchema", "state", "changeNote", "createdByUserId", "createdAt")
            values (:id, :__fe_tenant_organization_id, :projectId, :templateId, :versionNumber,
                cast(:locale as "Locale"), :storageKey, :contentHash,
                cast(:placeholderSchemaJson as jsonb), cast('DRAFT' as "DocumentTemplateStatus"),
                :changeNote, :author, :now)
            """, parameters);
        MapSqlParameterSource pointer = ScopedWhere.of(scope).bindNamed(Map.of())
            .addValue("id", template.id())
            .addValue("versionNumber", versionNumber)
            .addValue("now", now);
        jdbc.update("""
            update document_templates
               set "currentVersion" = :versionNumber, "version" = "version" + 1, "updatedAt" = :now
             where "organizationId" = :__fe_tenant_organization_id
               and "id" = :id
            """, pointer);
        return id;
    }

    /**
     * {@code DRAFT -> PUBLISHED} for a template version.
     *
     * <p>The publish step is what validates the placeholder vocabulary: the schema's partial unique index
     * makes a second PUBLISHED template version impossible, so a publication is the moment a template's
     * field list becomes real.
     */
    public boolean publishTemplateVersion(TenantScope scope, UUID versionId, UUID actorId,
                                          LocalDateTime now) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of())
            .addValue("id", versionId)
            .addValue("actor", actorId)
            .addValue("now", now);
        return jdbc.update("""
            update document_template_versions
               set "state" = cast('PUBLISHED' as "DocumentTemplateStatus"),
                   "publishedAt" = :now,
                   "publishedByUserId" = :actor
             where "organizationId" = :__fe_tenant_organization_id
               and "id" = :id
               and "state" = cast('DRAFT' as "DocumentTemplateStatus")
            """, parameters) == 1;
    }

    /**
     * {@code PUBLISHED -> RETIRED} for a template version, naming its successor.
     *
     * <p>Keeps {@code publishedAt} and {@code publishedByUserId} for exactly the reason
     * {@link #retireVersion} does: {@code document_template_versions_published_state_check} is the same
     * biconditional over DRAFT, so clearing them would be refused by the database and template retirement
     * has the same history behind it.
     */
    public boolean retireTemplateVersion(TenantScope scope, UUID versionId, UUID successorId,
                                         LocalDateTime now) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of())
            .addValue("id", versionId)
            .addValue("successor", successorId)
            .addValue("now", now);
        return jdbc.update("""
            update document_template_versions
               set "state" = cast('RETIRED' as "DocumentTemplateStatus"),
                   "retiredAt" = :now,
                   "supersededByVersionId" = :successor
             where "organizationId" = :__fe_tenant_organization_id
               and "id" = :id
               and "state" = cast('PUBLISHED' as "DocumentTemplateStatus")
            """, parameters) == 1;
    }

    /** Moves a template's published pointer. */
    public void markTemplatePublished(TenantScope scope, UUID templateId, int versionNumber, UUID actorId,
                                      LocalDateTime now) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of())
            .addValue("id", templateId)
            .addValue("versionNumber", versionNumber)
            .addValue("actor", actorId)
            .addValue("now", now);
        jdbc.update("""
            update document_templates
               set "status" = cast('PUBLISHED' as "DocumentTemplateStatus"),
                   "publishedVersion" = :versionNumber,
                   "publishedAt" = :now,
                   "publishedByUserId" = :actor,
                   "version" = "version" + 1,
                   "updatedAt" = :now
             where "organizationId" = :__fe_tenant_organization_id
               and "id" = :id
            """, parameters);
    }

    /** One template by id. */
    public Optional<TemplateRow> findTemplate(TenantScope scope, UUID templateId) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of())
            .addValue("id", templateId);
        return jdbc.query(FIND_TEMPLATE, parameters, ReportCatalogRepository::mapTemplate)
            .stream().findFirst();
    }

    /** The tenant's template catalogue. */
    public List<TemplateRow> listTemplates(TenantScope scope, UUID projectId, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("limit", Math.max(1, limit)));
        parameters.addValue("projectId", projectId, java.sql.Types.OTHER);
        return jdbc.query(LIST_TEMPLATES, parameters, ReportCatalogRepository::mapTemplate);
    }

    /**
     * One template version by number.
     *
     * <p>A DOCX export pins a template version the same way it pins a report version, so an export
     * produced against template v1 stays renderable after v2 is published.
     */
    public Optional<TemplateVersionRow> findTemplateVersion(TenantScope scope, UUID templateId,
                                                           int versionNumber) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of())
            .addValue("documentTemplateId", templateId)
            .addValue("versionNumber", versionNumber);
        return jdbc.query(FIND_TEMPLATE_VERSION, parameters,
            ReportCatalogRepository::mapTemplateVersion).stream().findFirst();
    }

    /** The currently published template version, which is what a DOCX export resolves by default. */
    public Optional<TemplateVersionRow> findPublishedTemplateVersion(TenantScope scope, UUID templateId) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of())
            .addValue("documentTemplateId", templateId);
        return jdbc.query(FIND_PUBLISHED_TEMPLATE_VERSION, parameters,
            ReportCatalogRepository::mapTemplateVersion).stream().findFirst();
    }

    /**
     * Every version of one template, oldest first.
     *
     * <p>What the export catalogue publishes, so a client can pin a template version by number instead of
     * discovering it. Tenant-scoped like every read here: a template id from another tenant resolves to no
     * versions at all, which is the same empty answer a nonexistent id produces.
     */
    public List<TemplateVersionRow> listTemplateVersions(TenantScope scope, UUID templateId, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of(
            "documentTemplateId", templateId,
            "limit", Math.max(1, limit)));
        return jdbc.query(LIST_TEMPLATE_VERSIONS, parameters, ReportCatalogRepository::mapTemplateVersion);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static DefinitionRow mapDefinition(java.sql.ResultSet rs, int rowNum)
        throws java.sql.SQLException {
        java.sql.Array array = rs.getArray("requiredPermissions");
        List<String> permissions = array == null ? List.of()
            : List.of((String[]) array.getArray());
        return new DefinitionRow(
            rs.getObject("id", UUID.class),
            rs.getObject("organizationId", UUID.class),
            rs.getObject("projectId", UUID.class),
            rs.getString("key"),
            rs.getString("nameAr"),
            rs.getString("nameEn"),
            rs.getString("descriptionAr"),
            rs.getString("descriptionEn"),
            rs.getString("status"),
            rs.getString("timezone"),
            rs.getString("localeDefault"),
            permissions,
            rs.getString("redactionPolicy"),
            rs.getString("retentionClass"),
            rs.getObject("currentVersion", Integer.class),
            rs.getObject("publishedVersion", Integer.class),
            rs.getObject("publishedAt", LocalDateTime.class),
            rs.getObject("publishedByUserId", UUID.class),
            rs.getInt("version"),
            rs.getObject("createdAt", LocalDateTime.class),
            rs.getObject("updatedAt", LocalDateTime.class));
    }

    private static VersionRow mapVersion(java.sql.ResultSet rs, int rowNum)
        throws java.sql.SQLException {
        return new VersionRow(
            rs.getObject("id", UUID.class),
            rs.getObject("organizationId", UUID.class),
            rs.getObject("projectId", UUID.class),
            rs.getObject("reportDefinitionId", UUID.class),
            rs.getInt("versionNumber"),
            rs.getString("dimensionsJson"),
            rs.getString("measuresJson"),
            rs.getString("filtersJson"),
            rs.getString("defaultSortJson"),
            rs.getString("columnsJson"),
            rs.getString("denominatorRulesJson"),
            rs.getString("metricSemanticsJson"),
            rs.getString("state"),
            rs.getString("changeNote"),
            rs.getObject("publishedAt", LocalDateTime.class),
            rs.getObject("publishedByUserId", UUID.class),
            rs.getObject("retiredAt", LocalDateTime.class),
            rs.getObject("supersededByVersionId", UUID.class),
            rs.getObject("createdByUserId", UUID.class),
            rs.getObject("createdAt", LocalDateTime.class));
    }

    private static TemplateRow mapTemplate(java.sql.ResultSet rs, int rowNum)
        throws java.sql.SQLException {
        return new TemplateRow(
            rs.getObject("id", UUID.class),
            rs.getObject("organizationId", UUID.class),
            rs.getObject("projectId", UUID.class),
            rs.getString("key"),
            rs.getString("nameAr"),
            rs.getString("nameEn"),
            rs.getString("kind"),
            rs.getString("status"),
            rs.getObject("currentVersion", Integer.class),
            rs.getObject("publishedVersion", Integer.class),
            rs.getObject("publishedAt", LocalDateTime.class),
            rs.getObject("publishedByUserId", UUID.class),
            rs.getString("locale"),
            rs.getString("timezone"),
            rs.getInt("version"),
            rs.getObject("createdAt", LocalDateTime.class),
            rs.getObject("updatedAt", LocalDateTime.class));
    }

    private static TemplateVersionRow mapTemplateVersion(java.sql.ResultSet rs, int rowNum)
        throws java.sql.SQLException {
        return new TemplateVersionRow(
            rs.getObject("id", UUID.class),
            rs.getObject("organizationId", UUID.class),
            rs.getObject("projectId", UUID.class),
            rs.getObject("documentTemplateId", UUID.class),
            rs.getInt("versionNumber"),
            rs.getString("locale"),
            rs.getString("storageKey"),
            rs.getString("contentHash"),
            rs.getString("placeholderSchemaJson"),
            rs.getString("state"),
            rs.getString("changeNote"),
            rs.getObject("publishedAt", LocalDateTime.class),
            rs.getObject("publishedByUserId", UUID.class),
            rs.getObject("retiredAt", LocalDateTime.class),
            rs.getObject("supersededByVersionId", UUID.class),
            rs.getObject("createdByUserId", UUID.class),
            rs.getObject("createdAt", LocalDateTime.class));
    }

    /**
     * The subject a version's columns name, resolved once so a caller does not have to.
     *
     * <p>Used by the finance period gate to decide whether the lock check applies, and by the authoring
     * surface to show which filters are legal for the version.
     */
    public static ReportSubject subjectOf(String columnsJson, com.firstelement.crm.persistence.JsonCodec json) {
        List<String> keys = json.readList(columnsJson).stream().map(String::valueOf).toList();
        return com.firstelement.exports.catalog.ReportFieldRegistry.subjectOf(keys)
            .orElseThrow(() -> new IllegalArgumentException(
                "A report version with no resolvable columns declares no subject: " + columnsJson));
    }

    /**
     * The declared redaction policy of a definition, resolved.
     *
     * <p>The refusal renders the value through {@link com.firstelement.exports.catalog.RefusalText}, so an
     * absent one reads {@code (absent)} rather than {@code 'null'}. Phase 32 recorded the alternative
     * arriving on a live API: a request that omitted {@code redactionPolicy} was answered
     * {@code 400 … names the redaction policy 'null'}, which tells the caller they sent the word "null"
     * and tells an operator a policy was rejected when none was ever offered. The required-field check now
     * answers that request first, which is the correct answer, but every other absent value in this module
     * reaches a message, so the rendering is fixed rather than only ordered around.
     */
    public static ExportRedactionPolicy policyOf(String wireValue) {
        return ExportRedactionPolicy.fromWireValue(wireValue).orElseThrow(
            () -> new IllegalArgumentException(
                "Report definition names the redaction policy "
                    + com.firstelement.exports.catalog.RefusalText.of(wireValue) + ", which is not one of "
                    + String.join(", ", ExportRedactionPolicy.declared()) + ". An undeclared policy would "
                    + "make the job's redactionPolicy column a claim rather than a decision."));
    }

    /**
     * Whether a locale string is one the database's {@code Locale} enum accepts.
     *
     * <p>Delegates to {@link com.firstelement.exports.job.ExportLocale} rather than restating the switch,
     * so the vocabulary a report declaration is checked against, the vocabulary a create request is
     * refused against, and the {@code enum} published in the OpenAPI document are the same list read from
     * one place. Three hand-written copies of {@code ar, en} would eventually disagree, and the one that
     * disagreed silently would be the request boundary.
     */
    public static String normalizeLocale(String locale) {
        if (locale == null || locale.isBlank()) {
            return com.firstelement.exports.job.ExportLocale.defaultLocale().wireValue();
        }
        return com.firstelement.exports.job.ExportLocale.require(locale).wireValue();
    }

    /**
     * A concurrent modification of a definition.
     *
     * <p>Distinct from {@link PolicyDeniedException} on purpose: this is a lost optimistic-concurrency
     * race, not a denial, and it must be reported as a conflict rather than rendered as a 404.
     */
    public static class ConcurrentDefinitionChangeException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public ConcurrentDefinitionChangeException(String message) {
            super(message);
        }
    }
}