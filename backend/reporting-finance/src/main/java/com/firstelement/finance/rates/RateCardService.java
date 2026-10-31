package com.firstelement.finance.rates;

import com.firstelement.finance.catalog.UsageDimension;
import com.firstelement.security.TenantContext;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.permission.AuthorizationService;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.permission.PolicyResource;
import com.firstelement.security.query.TenantQueryGuard;
import com.firstelement.security.query.TenantScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rate-card authoring: create a card, publish a version, retire a version.
 *
 * <h2>Who may do this</h2>
 *
 * <p>ADR 0007's rate-card ownership section: "Rate cards are finance-owned configuration, not
 * engineering-owned. The first release maps rate-card authoring to the administrator capability set,
 * with the finance/operations role as the intended daily operator. Engineering may seed a default card
 * but must not silently change one."
 *
 * <p>In this schema there is no separate finance role — {@code UserRole} is {@code ADMIN} or
 * {@code EMPLOYEE} — so "the administrator capability set" means an {@code ADMIN} holding
 * {@link Permission#ANALYTICS_READ_ORGANIZATION}. That is a deliberate narrowing: the permission alone
 * would also be held by a {@code FINANCE_AGGREGATE} machine principal, and letting a worker publish a
 * price would make the card engineering-owned in practice. The gap is recorded as a follow-up rather
 * than worked around by widening the permission.
 *
 * <h2>Why every check appears twice</h2>
 *
 * <p>{@code @RequirePermission} makes the declaration reviewable in one place and is what the HTTP
 * edge and the route-permission test read. The explicit {@link #requireFinanceAdmin} call is what makes
 * it unbypassable, because this service is reachable from a scheduled job, a test, and eventually an
 * export generator — plan.md §0.4 requires the domain service to enforce it too.
 *
 * <h2>The overlap rule, checked here because DDL cannot</h2>
 *
 * <p>Two ACTIVE versions of one card with overlapping effective ranges would make "which rate applied
 * on this date" have two answers. {@code rate_card_versions_one_start_per_day} covers only the part
 * DDL can express (at most one version may <em>start</em> on a day); the overlap itself needs
 * {@code btree_gist} and an EXCLUDE constraint. So {@link #publish} refuses to publish a version that
 * overlaps an existing ACTIVE one, naming both. A deployment that wants the constraint enforced at the
 * database rather than in the application needs a migration, which is recorded as follow-up debt.
 */
@Service
public class RateCardService {

    private final RateCardRepository cards;
    private final TenantQueryGuard guard;
    private final AuthorizationService authorization;

    public RateCardService(RateCardRepository cards, TenantQueryGuard guard,
                           AuthorizationService authorization) {
        this.cards = cards;
        this.guard = guard;
        this.authorization = authorization;
    }

    // ------------------------------------------------------------------
    // reads
    // ------------------------------------------------------------------

    /** The tenant's cards. Readable by anyone holding organization analytics. */
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    public List<RateCardRepository.RateCardRow> cards(TenantContext caller, UUID projectId) {
        requireOrganizationAnalytics(caller);
        return cards.cards(guard.scope(caller, projectId), projectId);
    }

    /** One card's full version history, newest first. */
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    public List<java.util.Map<String, Object>> history(TenantContext caller, UUID rateCardId) {
        requireOrganizationAnalytics(caller);
        return cards.versionHistory(guard.tenantScope(caller), rateCardId);
    }

    // ------------------------------------------------------------------
    // writes
    // ------------------------------------------------------------------

    /** Creates a rate card in {@code DRAFT}. */
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    @Transactional
    public UUID createCard(TenantContext caller, UUID projectId, String key, String nameAr,
                           String nameEn, String currency) {
        requireFinanceAdmin(caller);
        TenantScope scope = guard.scope(caller, projectId);
        UUID userId = requireHuman(caller);
        return cards.createCard(scope, userId, scope.projectId(), key, nameAr, nameEn, currency);
    }

    /**
     * A drafted, not-yet-effective version.
     *
     * <p><b>This is a store-and-review shape, not a publish step.</b> The schema's narrow guard
     * ({@code firstelement_rate_card_version_guard()}) accepts exactly one UPDATE — {@code ACTIVE ->
     * RETIRED} — and refuses everything else, {@code DRAFT -> ACTIVE} included. A draft therefore
     * cannot be promoted in place, and {@code rate_card_versions_published_state_check} requires an
     * ACTIVE version to carry {@code publishedAt}, which the only permitted UPDATE could never set.
     *
     * <p>So {@link #publish} inserts a <em>new</em> ACTIVE version carrying the draft's intended
     * pricing and then points the card at it. The draft row stays, as the record of what was proposed;
     * it is never itself a rate, because a {@code DRAFT} is excluded from
     * {@code rate_card_versions_active_effective_idx} and from every pricing predicate. Reported to the
     * orchestrator as a schema defect: the guard should also accept {@code DRAFT -> ACTIVE} with the
     * publication fields set and no pricing column moved.
     */
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    @Transactional
    public UUID draftVersion(TenantContext caller, UUID rateCardId, BigDecimal markupRate,
                             LocalDate effectiveFrom, LocalDate effectiveTo, String changeNote) {
        requireFinanceAdmin(caller);
        TenantScope scope = guard.tenantScope(caller);
        UUID userId = requireHuman(caller);
        RateCardRepository.RateCardRow card = requireCard(scope, rateCardId);
        requireChangeNote(changeNote);
        return cards.insertDraftVersion(scope, userId, rateCardId, card.projectId(),
            nextVersionNumber(scope, rateCardId), card.currency(), markupRate, effectiveFrom,
            effectiveTo, changeNote);
    }

    /** Adds a priced line to a draft version. */
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    @Transactional
    public UUID addLine(TenantContext caller, UUID rateCardVersionId, UsageDimension dimension,
                        BigDecimal costAmount, BigDecimal markupRate, String taxClassification) {
        requireFinanceAdmin(caller);
        TenantScope scope = guard.tenantScope(caller);
        RateCardVersion version = cards.version(scope, rateCardVersionId)
            .orElseThrow(() -> new IllegalArgumentException(
                "No rate-card version " + rateCardVersionId + " in this tenant"));
        if (!"DRAFT".equals(version.status())) {
            throw new IllegalStateException(
                "A line may only be added to a DRAFT version; " + rateCardVersionId + " is "
                    + version.status() + ". rate_card_version_lines is append-only and its version's "
                    + "pricing is immutable once active, so a line added later could never be part of "
                    + "a rate that applied on a date.");
        }
        String unit = dimension.canonicalUnit();
        return cards.insertLine(scope, rateCardVersionId, version.projectId(), dimension, unit,
            costAmount, markupRate, version.currency(), taxClassification);
    }

    /**
     * Makes a pricing effective.
     *
     * <p>This is the real authoring path, and it is an INSERT of an {@code ACTIVE} version rather than a
     * status change on a draft. Two schema constraints force that shape, and both were found by
     * attempting the obvious thing:
     *
     * <ol>
     *   <li>{@code firstelement_rate_card_version_guard()} accepts exactly one UPDATE —
     *       {@code ACTIVE -> RETIRED} — and refuses everything else, {@code DRAFT -> ACTIVE} included. So
     *       a draft cannot be promoted in place, while
     *       {@code rate_card_versions_published_state_check} requires an ACTIVE version to carry
     *       {@code publishedAt}, which that one permitted UPDATE could never set.</li>
     *   <li>{@code rate_card_versions_one_start_per_day} is UNIQUE over
     *       {@code (organizationId, rateCardId, effectiveFrom)}. The draft occupies its own start day, so
     *       inserting an ACTIVE version with the same {@code effectiveFrom} violates it — and the draft
     *       can never be removed either, because the same guard refuses every {@code DELETE}.</li>
     * </ol>
     *
     * <p>Together those mean <b>a draft can never become a rate</b>. {@link #draftVersion} therefore
     * records a proposal and {@link #activate} is what actually prices, and the gap is reported to the
     * orchestrator as a schema defect: {@code firstelement_rate_card_version_guard()} should accept
     * {@code DRAFT -> ACTIVE} with the publication fields set and no pricing column moved.
     *
     * <p>Refuses an overlap with an existing ACTIVE version of the same card, naming both, because two
     * answers to "which rate applied" is the failure this whole table exists to prevent. DDL can only
     * express the one-start-per-day half; the overlap is checked here and reported as a zero-count
     * assertion by {@code V13_usage_finance_slice.sql} section 7.
     *
     * @return the new ACTIVE version id, which is what a rollup snapshot will pin
     */
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    @Transactional
    public UUID activate(TenantContext caller, UUID rateCardId, BigDecimal markupRate,
                         LocalDate effectiveFrom, LocalDate effectiveTo, String changeNote,
                         List<ProposedLine> lines) {
        requireFinanceAdmin(caller);
        TenantScope scope = guard.tenantScope(caller);
        UUID userId = requireHuman(caller);
        RateCardRepository.RateCardRow card = requireCard(scope, rateCardId);
        requireChangeNote(changeNote);
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException(
                "An ACTIVE version must price at least one dimension; a version that prices nothing "
                    + "while looking configured is the shape rate_card_version_lines_prices_something_check "
                    + "exists to forbid, one level up.");
        }
        for (ProposedLine line : lines) {
            if (line.costAmount() == null && line.markupRate() == null) {
                throw new IllegalArgumentException(
                    "Line for " + line.dimension() + " prices nothing; supply a unit cost, a markup, or "
                        + "both.");
            }
            if (!line.dimension().canonicalUnit().equals(line.unit())) {
                throw new IllegalArgumentException(
                    "Line for " + line.dimension() + " uses unit '" + line.unit() + "', but this "
                        + "dimension's canonical unit is '" + line.dimension().canonicalUnit() + "'. "
                        + "usage_rollup_snapshots has no unit column, so two units for one dimension "
                        + "would be summed into a quantity of nothing.");
            }
        }

        int versionNumber = nextVersionNumber(scope, rateCardId);
        UUID published = cards.insertActiveVersion(scope, userId, rateCardId, card.projectId(),
            versionNumber, card.currency(), markupRate, effectiveFrom, effectiveTo, changeNote);

        for (ProposedLine line : lines) {
            cards.insertLine(scope, published, card.projectId(), line.dimension(), line.unit(),
                line.costAmount(), line.markupRate(), card.currency(), line.taxClassification());
        }

        requireNoOverlap(scope, rateCardId, effectiveFrom, effectiveTo, published);
        cards.setCurrentVersion(scope, rateCardId, published, versionNumber, "ACTIVE");
        return published;
    }

    /**
     * A line the caller wants on a new ACTIVE version.
     *
     * <p>A record rather than a mutation, so the whole pricing decision is one value that either applies
     * in full or not at all — a partially-priced version would be a rate for some dimensions and silence
     * for the rest.
     */
    public record ProposedLine(UsageDimension dimension,
                               String unit,
                               BigDecimal costAmount,
                               BigDecimal markupRate,
                               String taxClassification) {

        public ProposedLine(UsageDimension dimension, BigDecimal costAmount, BigDecimal markupRate) {
            this(dimension, dimension.canonicalUnit(), costAmount, markupRate, null);
        }
    }

    /**
     * Refuses a draft being promoted, and says why.
     *
     * <p>Present as a named method rather than left absent, because "publish this draft" is the obvious
     * first thing anybody would call and a missing method is a worse answer than one that explains the
     * constraint. See {@link #activate} for the two schema rules and the shape that does work.
     */
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    public UUID publish(TenantContext caller, UUID rateCardVersionId) {
        throw new UnsupportedOperationException(
            "A DRAFT cannot be published in this schema. firstelement_rate_card_version_guard() accepts "
                + "only ACTIVE -> RETIRED, so DRAFT -> ACTIVE is refused; and "
                + "rate_card_versions_one_start_per_day is unique over "
                + "(organizationId, rateCardId, effectiveFrom), so an ACTIVE version cannot be inserted "
                + "on the day the draft already occupies -- and the draft can never be deleted either, "
                + "because the same guard refuses DELETE. Use activate(...), which inserts the ACTIVE "
                + "version directly with a start day no existing version uses. Reported to the "
                + "orchestrator as a schema defect on V20261005090000.");
    }

    /**
     * Retires an ACTIVE version, naming its successor.
     *
     * <p>The successor must already exist, so a historical snapshot's pinned version can never be
     * dangling. {@code rate_card_versions_retired_check} requires
     * {@code supersededByVersionId IS NOT NULL}, and a real id is better than a satisfied NOT NULL.
     */
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    @Transactional
    public void retire(TenantContext caller, UUID rateCardVersionId, UUID supersededByVersionId,
                       String reason) {
        requireFinanceAdmin(caller);
        TenantScope scope = guard.tenantScope(caller);
        UUID userId = requireHuman(caller);
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                "Retiring a rate must say why; rate_card_versions_retired_check requires retireReason "
                    + "and an unexplained retirement is how a card quietly stops applying.");
        }
        if (cards.version(scope, supersededByVersionId).isEmpty()) {
            throw new IllegalArgumentException(
                "supersededByVersionId " + supersededByVersionId + " does not exist in this tenant; a "
                    + "retirement that names nothing leaves historical snapshots pointing at a rate "
                    + "that no longer says what replaced it.");
        }
        int updated = cards.retireVersion(scope, rateCardVersionId, supersededByVersionId, userId,
            reason);
        if (updated == 0) {
            throw new IllegalStateException(
                "No ACTIVE version " + rateCardVersionId + " to retire in this tenant. A RETIRED "
                    + "version is frozen, so a second retirement is refused by the schema as well as "
                    + "here.");
        }
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    /**
     * The write-side policy: {@code ANALYTICS_READ_ORGANIZATION} <em>and</em> an {@code ADMIN} role.
     *
     * <p>Both, not either. The permission is what makes the intent reviewable; the role is what keeps a
     * {@code FINANCE_AGGREGATE} worker — which
     * {@code RolePermissions.forCapabilities} grants that same permission to — from authoring a price.
     * A worker may compute aggregates; it may not decide what things cost.
     */
    private void requireFinanceAdmin(TenantContext caller) {
        if (caller == null) {
            throw new IllegalStateException(
                "A rate-card write requires a resolved TenantContext; refusing to run unscoped.");
        }
        authorization.check(caller, Permission.ANALYTICS_READ_ORGANIZATION, PolicyResource.NONE);
        if (!caller.isAdmin()) {
            throw new AccessDeniedException(
                "Rate cards are finance-owned configuration (ADR 0007). Publishing, retiring or "
                    + "authoring a price requires the administrator capability set, which in this "
                    + "schema means an ADMIN role as well as analytics:read-organization. This "
                    + "principal is a " + caller.principalType() + ".");
        }
    }

    private void requireOrganizationAnalytics(TenantContext caller) {
        if (caller == null) {
            throw new IllegalStateException(
                "A finance read requires a resolved TenantContext; refusing to run unscoped.");
        }
        authorization.check(caller, Permission.ANALYTICS_READ_ORGANIZATION, PolicyResource.NONE);
    }

    private UUID requireHuman(TenantContext caller) {
        if (!caller.isUser() || caller.userId() == null) {
            throw new AccessDeniedException(
                "A rate-card write must be attributable to a person; "
                    + "rate_card_versions.createdByUserId is NOT NULL and its FK is a composite tenant "
                    + "key, so the database would refuse a machine actor anyway.");
        }
        return caller.userId();
    }

    private RateCardRepository.RateCardRow requireCard(TenantScope scope, UUID rateCardId) {
        return cards.cards(scope, null).stream()
            .filter(card -> card.id().equals(rateCardId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "No rate card " + rateCardId + " in this tenant"));
    }

    private int nextVersionNumber(TenantScope scope, UUID rateCardId) {
        return cards.versionHistory(scope, rateCardId).stream()
            .map(row -> {
                Object value = row.get("versionNumber");
                return value == null ? 0 : ((Number) value).intValue();
            })
            .max(Integer::compare)
            .orElse(0) + 1;
    }

    /**
     * Refuses an overlapping ACTIVE version of the same card, naming both.
     *
     * <p>Checked against the rows that exist <em>including</em> the one being published — which is why
     * this runs before the status update rather than after. A version whose range touches another's
     * (one ends the day the next begins) is not an overlap: the resolver's inclusive bounds make the
     * boundary day unambiguous, and {@code b."effectiveFrom" <= coalesce(a."effectiveTo", ...)} would
     * otherwise flag a clean hand-off.
     */
    /**
     * A version must say why it exists.
     *
     * <p>{@code rate_card_versions_change_note_check} requires a non-blank {@code changeNote} for
     * anything that is not a DRAFT. Checked at authoring time as well, because a rate with no stated
     * reason is one nobody can defend three months later, and by publish time the missing note is
     * already a migration failure rather than an authoring hint.
     */
    private void requireChangeNote(String changeNote) {
        if (changeNote == null || changeNote.isBlank()) {
            throw new IllegalArgumentException(
                "A rate-card version must carry a changeNote saying why it exists; the schema requires "
                    + "one for anything that is not a draft, and a price nobody can explain is a price "
                    + "nobody can defend.");
        }
    }

    /**
     * Rates whose effective ranges overlap among one card's ACTIVE versions.
     *
     * <p>Exposed so the scheduler and the reconciliation can both name an overlapping card, rather than
     * each re-deriving the interval arithmetic. DDL cannot express it — that is why it lives here — so
     * there has to be exactly one implementation of the question.
     */
    public List<RateCardRepository.Overlap> overlappingActiveVersions(TenantScope scope,
                                                                      UUID rateCardId) {
        return cards.overlappingActiveVersions(scope.organizationId(), rateCardId);
    }

    private void requireNoOverlap(TenantScope scope, UUID rateCardId, LocalDate from, LocalDate to,
                                 UUID justInserted) {
        List<RateCardRepository.Overlap> overlaps = cards.overlappingActiveVersions(
            scope.organizationId(), rateCardId).stream()
            .filter(overlap -> overlap.aVersionId().equals(justInserted)
                || overlap.bVersionId().equals(justInserted))
            .toList();
        if (overlaps.isEmpty()) {
            return;
        }
        RateCardRepository.Overlap first = overlaps.get(0);
        throw new IllegalStateException(
            "Activating " + from + ".." + (to == null ? "open" : to) + " would overlap an ACTIVE "
                + "version of the same card: " + first.aFrom() + ".."
                + (first.aTo() == null ? "open" : first.aTo()) + " and " + first.bFrom() + ".."
                + (first.bTo() == null ? "open" : first.bTo()) + ". Two rates effective on the same "
                + "date would make every snapshot pinned to one of them uncheckable, and the schema "
                + "cannot express this without a btree_gist EXCLUDE constraint.");
    }
}
