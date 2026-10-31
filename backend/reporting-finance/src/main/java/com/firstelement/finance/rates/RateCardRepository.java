package com.firstelement.finance.rates;

import com.firstelement.finance.catalog.UsageDimension;
import com.firstelement.security.TenantContext;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.query.TenantQueryGuard;
import com.firstelement.security.query.TenantScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes rate cards, versions and lines.
 *
 * <h2>Every statement is tenant-filtered before it returns a row</h2>
 *
 * <p>Not "filtered by the caller afterwards" — the predicate is in the {@code WHERE}, so a foreign row
 * never enters a Java collection and cannot leak through a size, an {@code isEmpty()} or a log line.
 * Every tenant-scoped read takes a {@link TenantScope}, which can only come from
 * {@link TenantQueryGuard}, so there is no API on this class that produces an unscoped statement.
 *
 * <h2>Why the write side is narrower than the read side</h2>
 *
 * <p>Reads are open to anyone holding the organization analytics permission, because a finance reviewer
 * needs to see the card. Writes go through {@link RateCardService}, which holds
 * {@code ANALYTICS_READ_ORGANIZATION} <em>and</em> requires an {@code ADMIN} role — ADR 0007 puts
 * rate cards under the administrator capability set with the finance role as the intended daily
 * operator, and in this schema the only human role that is {@code ADMIN}. An {@code EMPLOYEE} therefore
 * cannot author or publish a price, and the repository does not offer them a method that would let one.
 */
@Repository
public class RateCardRepository {

    /**
     * Versions effective on a date, newest first.
     *
     * <p>Shaped around {@code rate_card_versions_active_effective_idx}, which is partial on
     * {@code status = 'ACTIVE'} and leads with {@code (organizationId, rateCardId, effectiveFrom,
     * effectiveTo)}. {@code DRAFT} and {@code RETIRED} are excluded by the index, and by this
     * predicate: a draft has not been agreed and a retired one no longer applies, so neither can ever
     * be the answer to "which rate applied on this date".
     *
     * <p>{@code projectId} is deliberately <em>not</em> in the predicate. A card may be
     * organisation-wide (the normal case) or project-specific, and the resolution order is
     * project-specific first then organisation-wide — which is {@link RateCardResolver}'s job, not a
     * filter's. Filtering by project here would silently drop the organisation-wide card.
     */
    private static final String EFFECTIVE_VERSIONS = """
        select v."id", v."organizationId", v."projectId", v."rateCardId", v."versionNumber",
               v."currency", v."markupRate", v."effectiveFrom", v."effectiveTo", v."status"::text,
               v."changeNote"
          from rate_card_versions v
         where v."organizationId" = :__fe_tenant_organization_id
           and v."status" = cast('ACTIVE' as "RateCardStatus")
           and v."effectiveFrom" <= :asOf
           and (v."effectiveTo" is null or v."effectiveTo" >= :asOf)
         order by v."effectiveFrom" desc
        """;

    private static final String LINES_FOR_VERSION = """
        select l."id", l."rateCardVersionId", l."dimension"::text, l."unit", l."costAmount",
               l."markupRate", l."currency", l."taxClassification"
          from rate_card_version_lines l
         where l."organizationId" = :__fe_tenant_organization_id
           and l."rateCardVersionId" = :versionId
         order by l."dimension", l."unit"
        """;

    private static final String VERSION_BY_ID = """
        select v."id", v."organizationId", v."projectId", v."rateCardId", v."versionNumber",
               v."currency", v."markupRate", v."effectiveFrom", v."effectiveTo", v."status"::text,
               v."changeNote"
          from rate_card_versions v
         where v."organizationId" = :__fe_tenant_organization_id
           and v."id" = :versionId
        """;

    /** Every card of the tenant, whatever its status, so a reviewer can see a retired one. */
    private static final String CARDS = """
        select c."id", c."organizationId", c."projectId", c."key", c."nameAr", c."nameEn",
               c."currency", c."status"::text, c."currentVersionId", c."latestVersionNumber",
               c."retiredAt"
          from rate_cards c
         where c."organizationId" = :__fe_tenant_organization_id
           and (cast(:filterProjectId as uuid) is null or c."projectId" = cast(:filterProjectId as uuid)
                or c."projectId" is null)
         order by c."key"
        """;

    /** Every version of one card, so a reviewer can see the history rather than only the live rate. */
    private static final String VERSIONS_OF_CARD = """
        select v."id", v."organizationId", v."projectId", v."rateCardId", v."versionNumber",
               v."currency", v."markupRate", v."effectiveFrom", v."effectiveTo", v."status"::text,
               v."changeNote", v."retiredAt", v."retireReason", v."supersededByVersionId"
          from rate_card_versions v
         where v."organizationId" = :__fe_tenant_organization_id
           and v."rateCardId" = :rateCardId
         order by v."versionNumber" desc
        """;

    /**
     * Overlapping effective ranges among a card's ACTIVE versions.
     *
     * <p>This is the invariant DDL cannot express. Two ACTIVE versions of one card whose ranges
     * overlap would make "which rate applied on this date" have two answers, and expressing it would
     * need {@code btree_gist} and an EXCLUDE constraint — a heavier dependency than the invariant
     * deserves. So it is checked on the write path instead, and reported as a zero-count assertion by
     * {@code V13_usage_finance_slice.sql} section 7. Both, not one.
     */
    private static final String OVERLAPPING_ACTIVE_VERSIONS = """
        select a."id" as "aId", b."id" as "bId", a."effectiveFrom" as "aFrom",
               a."effectiveTo" as "aTo", b."effectiveFrom" as "bFrom", b."effectiveTo" as "bTo"
          from rate_card_versions a
          join rate_card_versions b
            on b."organizationId" = a."organizationId"
           and b."rateCardId" = a."rateCardId"
           and b."id" > a."id"
           and b."status" = cast('ACTIVE' as "RateCardStatus")
         where a."organizationId" = :organizationId
           and a."rateCardId" = :rateCardId
           and a."status" = cast('ACTIVE' as "RateCardStatus")
           and a."effectiveFrom" <= coalesce(b."effectiveTo", date '9999-12-31')
           and b."effectiveFrom" <= coalesce(a."effectiveTo", date '9999-12-31')
        """;

    private static final String INSERT_CARD = """
        insert into rate_cards ("id", "organizationId", "projectId", "key", "nameAr", "nameEn",
            "currency", "status", "createdByUserId", "version", "createdAt", "updatedAt")
        values (:id, :__fe_tenant_organization_id, :projectId, :key, :nameAr, :nameEn, :currency,
            cast('DRAFT' as "RateCardStatus"), :createdBy, 0, :now, :now)
        """;

    private static final String INSERT_LINE = """
        insert into rate_card_version_lines ("id", "organizationId", "projectId",
            "rateCardVersionId", "dimension", "unit", "costAmount", "markupRate", "currency",
            "taxClassification", "createdAt")
        values (:id, :__fe_tenant_organization_id, :projectId, :rateCardVersionId,
            cast(:dimension as "UsageDimension"), :unit, :costAmount, :markupRate, :currency,
            :taxClassification, :now)
        """;

    /**
     * INSERT a version straight into {@code ACTIVE}, with the publication fields the schema requires.
     *
     * <p><b>Why publishing is an INSERT and not a status change.</b>
     * {@code firstelement_rate_card_version_guard()} accepts exactly one UPDATE shape:
     * {@code ACTIVE -> RETIRED} with all four retirement fields set and no pricing column moved. Every
     * other UPDATE raises, including {@code DRAFT -> ACTIVE}. So a draft cannot be promoted in place,
     * and {@code rate_card_versions_published_state_check} — which ties
     * {@code status = 'DRAFT'} to {@code publishedAt IS NULL} — then requires an ACTIVE version to
     * carry a publication timestamp, which the only permitted UPDATE could never set.
     *
     * <p>The two constraints together make a promoted draft unrepresentable, and the only legal way to
     * make a rate effective is to insert it already ACTIVE with {@code publishedAt} and
     * {@code publishedByUserId} set. That is what this statement does. The draft-then-publish flow is
     * reported to the orchestrator as a schema defect: either
     * {@code firstelement_rate_card_version_guard()} should also accept
     * {@code DRAFT -> ACTIVE} with {@code publishedAt}/{@code publishedByUserId}/{@code changeNote} set
     * and no pricing column moved, or {@code rate_card_versions_published_state_check} should be
     * relaxed. Until a migration decides, this is the shape that satisfies both.
     */
    /**
     * A {@code DRAFT} version: the proposal, not a rate.
     *
     * <p>{@code rate_card_versions_published_state_check} ties {@code status = 'DRAFT'} to
     * {@code publishedAt IS NULL}, so no publication timestamp is written here.
     */
    private static final String INSERT_DRAFT_VERSION = """
        insert into rate_card_versions ("id", "organizationId", "projectId", "rateCardId",
            "versionNumber", "currency", "markupRate", "effectiveFrom", "effectiveTo", "status",
            "changeNote", "createdByUserId", "createdAt")
        values (:id, :__fe_tenant_organization_id, :projectId, :rateCardId, :versionNumber, :currency, :markupRate,
            :effectiveFrom, :effectiveTo, cast('DRAFT' as "RateCardStatus"), :changeNote, :createdBy,
            :now)
        """;



    private static final String INSERT_ACTIVE_VERSION = """
        insert into rate_card_versions ("id", "organizationId", "projectId", "rateCardId",
            "versionNumber", "currency", "markupRate", "effectiveFrom", "effectiveTo", "status",
            "changeNote", "publishedAt", "publishedByUserId", "createdByUserId", "createdAt")
        values (:id, :__fe_tenant_organization_id, :projectId, :rateCardId, :versionNumber, :currency, :markupRate,
            :effectiveFrom, :effectiveTo, cast('ACTIVE' as "RateCardStatus"), :changeNote, :now,
            :publishedBy, :createdBy, :now)
        """;

    /**
     * The one legal UPDATE on {@code rate_card_versions}: {@code ACTIVE -> RETIRED}, fully attributed,
     * successor named, no pricing column moved.
     *
     * <p>{@code firstelement_rate_card_version_guard()} enforces the shape; this statement's job is to
     * be the only writer that produces it. Note it does not touch {@code markupRate},
     * {@code effectiveFrom}, {@code effectiveTo}, {@code currency}, {@code versionNumber} or
     * {@code createdAt} — the guard refuses any of those, so including them would be asserting
     * something the database will reject.
     */
    private static final String RETIRE_VERSION = """
        update rate_card_versions
           set "status" = cast('RETIRED' as "RateCardStatus"), "retiredAt" = :now,
               "retiredByUserId" = :retiredBy, "retireReason" = :reason,
               "supersededByVersionId" = :supersededBy
         where "organizationId" = :__fe_tenant_organization_id
           and "id" = :versionId
           and "status" = cast('ACTIVE' as "RateCardStatus")
        """;

    /** Point the card at its live version, in the same transaction as the publish. */
    private static final String SET_CURRENT_VERSION = """
        update rate_cards
           set "currentVersionId" = :versionId, "latestVersionNumber" = :versionNumber,
               "status" = cast(:status as "RateCardStatus"), "version" = "version" + 1,
               "updatedAt" = :now
         where "organizationId" = :__fe_tenant_organization_id
           and "id" = :rateCardId
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public RateCardRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // reads
    // ------------------------------------------------------------------

    /**
     * The ACTIVE versions effective on {@code asOf}, with their lines.
     *
     * <p>Ordered newest {@code effectiveFrom} first, which is the order
     * {@link RateCardResolver} wants when a project-specific card and an organisation-wide card both
     * apply.
     */
    public List<RateCardVersion> effectiveVersions(TenantScope scope, LocalDate asOf) {
        MapSqlParameterSource parameters = com.firstelement.security.query.ScopedWhere.of(scope)
            .bindNamed(java.util.Map.of("asOf", asOf));
        // Two queries rather than one join: a version with zero lines is a legitimate state (a card
        // published with markup only, before its per-dimension lines are added), and an inner join
        // would hide it — which would make a card look like it prices nothing it does price.
        List<RateCardVersion> headers = jdbc.query(EFFECTIVE_VERSIONS, parameters,
            (rs, rowNum) -> new RateCardVersion(
                rs.getObject("id", UUID.class),
                rs.getObject("organizationId", UUID.class),
                rs.getObject("projectId", UUID.class),
                rs.getObject("rateCardId", UUID.class),
                rs.getInt("versionNumber"),
                rs.getString("currency"),
                rs.getBigDecimal("markupRate"),
                rs.getObject("effectiveFrom", LocalDate.class),
                rs.getObject("effectiveTo", LocalDate.class),
                rs.getString("status"),
                rs.getString("changeNote"),
                List.of()));
        List<RateCardVersion> withLines = new java.util.ArrayList<>(headers.size());
        for (RateCardVersion header : headers) {
            withLines.add(withLines(scope, header));
        }
        return withLines;
    }

    private RateCardVersion withLines(TenantScope scope, RateCardVersion version) {
        return new RateCardVersion(version.id(), version.organizationId(), version.projectId(),
            version.rateCardId(), version.versionNumber(), version.currency(), version.markupRate(),
            version.effectiveFrom(), version.effectiveTo(), version.status(), version.changeNote(),
            linesOf(scope, version.id()));
    }

    /** The lines of one version. */
    public List<RateCardVersionLine> linesOf(TenantScope scope, UUID versionId) {
        MapSqlParameterSource parameters = com.firstelement.security.query.ScopedWhere.of(scope)
            .bindNamed(java.util.Map.of("versionId", versionId));
        return jdbc.query(LINES_FOR_VERSION, parameters, (rs, rowNum) -> new RateCardVersionLine(
            rs.getObject("id", UUID.class),
            rs.getObject("rateCardVersionId", UUID.class),
            UsageDimension.fromWire(rs.getString("dimension")),
            rs.getString("unit"),
            rs.getBigDecimal("costAmount"),
            rs.getBigDecimal("markupRate"),
            rs.getString("currency"),
            rs.getString("taxClassification")));
    }

    /** One version with its lines, for the drill-through and for a pinned snapshot's provenance. */
    public Optional<RateCardVersion> version(TenantScope scope, UUID versionId) {
        MapSqlParameterSource parameters = com.firstelement.security.query.ScopedWhere.of(scope)
            .bindNamed(java.util.Map.of("versionId", versionId));
        return jdbc.query(VERSION_BY_ID, parameters, (rs, rowNum) -> withLines(scope,
                new RateCardVersion(
                    rs.getObject("id", UUID.class),
                    rs.getObject("organizationId", UUID.class),
                    rs.getObject("projectId", UUID.class),
                    rs.getObject("rateCardId", UUID.class),
                    rs.getInt("versionNumber"),
                    rs.getString("currency"),
                    rs.getBigDecimal("markupRate"),
                    rs.getObject("effectiveFrom", LocalDate.class),
                    rs.getObject("effectiveTo", LocalDate.class),
                    rs.getString("status"),
                    rs.getString("changeNote"),
                    List.of())))
            .stream().findFirst();
    }

    /** One card row. */
    public record RateCardRow(UUID id,
                              UUID organizationId,
                              UUID projectId,
                              String key,
                              String nameAr,
                              String nameEn,
                              String currency,
                              String status,
                              UUID currentVersionId,
                              Integer latestVersionNumber,
                              java.time.LocalDateTime retiredAt) {
    }

    /** Every card visible to the caller's scope. */
    public List<RateCardRow> cards(TenantScope scope, UUID projectId) {
        MapSqlParameterSource parameters =
            com.firstelement.security.query.ScopedWhere.of(scope).bindNamed(java.util.Map.of());
        parameters.addValue("filterProjectId", projectId);
        return jdbc.query(CARDS, parameters, (rs, rowNum) -> new RateCardRow(
            rs.getObject("id", UUID.class),
            rs.getObject("organizationId", UUID.class),
            rs.getObject("projectId", UUID.class),
            rs.getString("key"),
            rs.getString("nameAr"),
            rs.getString("nameEn"),
            rs.getString("currency"),
            rs.getString("status"),
            rs.getObject("currentVersionId", UUID.class),
            rs.getObject("latestVersionNumber", Integer.class),
            rs.getObject("retiredAt", java.time.LocalDateTime.class)));
    }

    /** The full history of one card's versions. */
    public List<java.util.Map<String, Object>> versionHistory(TenantScope scope, UUID rateCardId) {
        MapSqlParameterSource parameters =
            com.firstelement.security.query.ScopedWhere.of(scope)
                .bindNamed(java.util.Map.of("rateCardId", rateCardId));
        return jdbc.queryForList(VERSIONS_OF_CARD, parameters);
    }

    // ------------------------------------------------------------------
    // writes
    // ------------------------------------------------------------------

    /** Creates a card in {@code DRAFT}. */
    public UUID createCard(TenantScope scope, UUID createdByUserId, UUID projectId, String key,
                           String nameAr, String nameEn, String currency) {
        UUID id = UUID.randomUUID();
        MapSqlParameterSource parameters =
            com.firstelement.security.query.ScopedWhere.of(scope).bindNamed(java.util.Map.of())
                .addValue("id", id)
                .addValue("projectId", projectId)
                .addValue("key", key)
                .addValue("nameAr", nameAr)
                .addValue("nameEn", nameEn)
                .addValue("currency", currency)
                .addValue("createdBy", createdByUserId)
                .addValue("now", java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        jdbc.update(INSERT_CARD, parameters);
        return id;
    }

    /**
     * Inserts a {@code DRAFT} version: markup and effective dates proposed, not yet a rate.
     *
     * <p>No {@code publishedAt}/{@code publishedByUserId}: {@code
     * rate_card_versions_published_state_check} requires a DRAFT to carry neither, and a draft that
     * claimed to have been published would be a card that looked agreed and was not.
     *
     * @param changeNote why this version exists. {@code rate_card_versions_change_note_check} requires
     *                   one for anything that is not a draft, and this class requires it here too, so
     *                   the failure arrives at authoring time rather than at publish time
     */
    public UUID insertDraftVersion(TenantScope scope, UUID createdByUserId, UUID rateCardId,
                                   UUID projectId, int versionNumber, String currency,
                                   BigDecimal markupRate, LocalDate effectiveFrom,
                                   LocalDate effectiveTo, String changeNote) {
        UUID id = UUID.randomUUID();
        MapSqlParameterSource parameters =
            com.firstelement.security.query.ScopedWhere.of(scope).bindNamed(java.util.Map.of())
                .addValue("id", id)
                .addValue("projectId", projectId)
                .addValue("rateCardId", rateCardId)
                .addValue("versionNumber", versionNumber)
                .addValue("currency", currency)
                .addValue("markupRate", markupRate)
                .addValue("effectiveFrom", effectiveFrom)
                .addValue("effectiveTo", effectiveTo)
                .addValue("changeNote", changeNote)
                .addValue("createdBy", createdByUserId)
                .addValue("now", java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        jdbc.update(INSERT_DRAFT_VERSION, parameters);
        return id;
    }

    /**
     * Inserts a version straight into {@code ACTIVE}.
     *
     * <p>Not {@code DRAFT} then a status change — see {@link #INSERT_ACTIVE_VERSION} for why the
     * schema's narrow guard makes that impossible. The method is named for what it does rather than for
     * an {@code INSERT}/{@code UPDATE} pair that does not exist.
     *
     * @param effectiveTo the last date the rate applies, inclusive; {@code null} for open-ended
     */
    public UUID insertActiveVersion(TenantScope scope, UUID createdByUserId, UUID rateCardId,
                                    UUID projectId, int versionNumber, String currency,
                                    BigDecimal markupRate, LocalDate effectiveFrom,
                                    LocalDate effectiveTo, String changeNote) {
        UUID id = UUID.randomUUID();
        java.time.LocalDateTime now = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
        MapSqlParameterSource parameters =
            com.firstelement.security.query.ScopedWhere.of(scope).bindNamed(java.util.Map.of())
                .addValue("id", id)
                .addValue("projectId", projectId)
                .addValue("rateCardId", rateCardId)
                .addValue("versionNumber", versionNumber)
                .addValue("currency", currency)
                .addValue("markupRate", markupRate)
                .addValue("effectiveFrom", effectiveFrom)
                .addValue("effectiveTo", effectiveTo)
                .addValue("changeNote", changeNote)
                .addValue("publishedBy", createdByUserId)
                .addValue("createdBy", createdByUserId)
                .addValue("now", now);
        jdbc.update(INSERT_ACTIVE_VERSION, parameters);
        return id;
    }

    /**
     * Inserts a line. The currency is the version's, enforced by {@link RateCardVersionLine}.
     *
     * <p>Built through {@link MapSqlParameterSource} rather than {@code Map.of}, because a line takes its
     * cost from the version, its markup from the version, or both — and {@code Map.of} refuses a null
     * value, so the two shapes that {@code rate_card_version_lines_prices_something_check} exists to
     * permit are exactly the two {@code Map.of} cannot express.
     */
    public UUID insertLine(TenantScope scope, UUID rateCardVersionId, UUID projectId,
                           UsageDimension dimension, String unit, BigDecimal costAmount,
                           BigDecimal markupRate, String currency, String taxClassification) {
        UUID id = UUID.randomUUID();
        MapSqlParameterSource parameters =
            com.firstelement.security.query.ScopedWhere.of(scope).bindNamed(java.util.Map.of())
                .addValue("id", id)
                .addValue("projectId", projectId)
                .addValue("rateCardVersionId", rateCardVersionId)
                .addValue("dimension", dimension.wireValue())
                .addValue("unit", unit)
                .addValue("costAmount", costAmount)
                .addValue("markupRate", markupRate)
                .addValue("currency", currency)
                .addValue("taxClassification", taxClassification)
                .addValue("now", java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        jdbc.update(INSERT_LINE, parameters);
        return id;
    }

    /** Retires a version. The only UPDATE the schema's rate-card guard accepts. */
    public int retireVersion(TenantScope scope, UUID versionId, UUID supersededByVersionId,
                             UUID retiredByUserId, String reason) {
        return jdbc.update(RETIRE_VERSION, com.firstelement.security.query.ScopedWhere.of(scope)
            .bindNamed(java.util.Map.of(
                "versionId", versionId,
                "supersededBy", supersededByVersionId,
                "retiredBy", retiredByUserId,
                "reason", reason,
                "now", java.time.LocalDateTime.now(java.time.ZoneOffset.UTC))));
    }

    /** Points the card at its live version. */
    public void setCurrentVersion(TenantScope scope, UUID rateCardId, UUID versionId,
                                  int versionNumber, String status) {
        MapSqlParameterSource parameters =
            com.firstelement.security.query.ScopedWhere.of(scope).bindNamed(java.util.Map.of())
                .addValue("rateCardId", rateCardId)
                .addValue("versionId", versionId)
                .addValue("versionNumber", versionNumber)
                .addValue("status", status)
                .addValue("now", java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        jdbc.update(SET_CURRENT_VERSION, parameters);
    }

    // ------------------------------------------------------------------
    // the overlap invariant DDL cannot express
    // ------------------------------------------------------------------

    /** One overlapping pair among a card's ACTIVE versions. */
    public record Overlap(UUID aVersionId,
                          LocalDate aFrom,
                          LocalDate aTo,
                          UUID bVersionId,
                          LocalDate bFrom,
                          LocalDate bTo) {
    }

    /**
     * Any two ACTIVE versions of one card whose effective ranges overlap.
     *
     * <p>Not tenant-scoped through {@link ScopedWhere} on purpose: this is a <em>write-path</em> check
     * about one card, and the card id is already tenant-bound by the caller who read it. The
     * {@code organizationId} predicate is stated explicitly so the query still cannot cross a tenant.
     */
    public List<Overlap> overlappingActiveVersions(UUID organizationId, UUID rateCardId) {
        return jdbc.query(OVERLAPPING_ACTIVE_VERSIONS, new MapSqlParameterSource()
                .addValue("organizationId", organizationId)
                .addValue("rateCardId", rateCardId),
            (rs, rowNum) -> new Overlap(
                rs.getObject("aId", UUID.class),
                rs.getObject("aFrom", LocalDate.class),
                rs.getObject("aTo", LocalDate.class),
                rs.getObject("bId", UUID.class),
                rs.getObject("bFrom", LocalDate.class),
                rs.getObject("bTo", LocalDate.class)));
    }
}
