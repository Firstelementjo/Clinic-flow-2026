package com.firstelement.finance.ledger;

import com.firstelement.finance.catalog.UsageDimension;
import com.firstelement.finance.money.FxAttribution;
import com.firstelement.finance.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * An immutable usage event, as a validated value.
 *
 * <h2>The one rule this record exists to encode</h2>
 *
 * <p>ADR 0007 Part 1: "a usage event stores <b>cost</b>. A versioned rate card with an effective-dated
 * markup converts that cost into an internal billable/revenue amount. Revenue is a derived value,
 * never a stored input." So this record has a {@code cost} and no revenue field, no markup, no rate
 * and no billable amount, and there is no builder method that would let one be attached.
 * {@code V20261005080000} enforces the same absence in the database with a post-condition guard that
 * <em>fails the migration</em> if a column matching {@code %revenue%}, {@code %billable%},
 * {@code %margin%} or {@code price}/{@code markupRate} ever appears — so the two halves cannot drift.
 *
 * <h2>What the validation here catches before the column does</h2>
 *
 * <p>The database is the authority and this class is not a substitute for it. What it adds is
 * legibility at the point of the mistake, and three checks the schema cannot express:
 *
 * <ol>
 *   <li><b>The dimension's unit must be its canonical unit.</b>
 *       {@code usage_rollup_snapshots} is unique per
 *       {@code (organizationId, projectId, grain, bucketStart, dimension)} with no unit column, so two
 *       events of one dimension in different units would be summed into a quantity of nothing. By the
 *       time a snapshot exists it has already been summed, so this is the only place it can be
 *       caught.</li>
 *   <li><b>The source event type must be declared for that dimension</b>, by
 *       {@link com.firstelement.finance.catalog.FinanceMetricCatalogue}. {@code sourceEventType} is
 *       free text; without the catalogue an event's meaning is whatever a reader guesses.</li>
 *   <li><b>A cost may only be supplied for a dimension whose family knows cost at emit time</b>, which
 *       is what stops a caller asserting an {@code AI_TOKEN} cost and thereby pricing tokens from a
 *       hand-typed number instead of from the rate card.</li>
 * </ol>
 *
 * <h2>Why {@code periodStart}/{@code periodEnd} are not optional</h2>
 *
 * <p>The schema derives and immutably stores the calendar bucket
 * ({@code usage_events_period_contains_event_check} requires {@code occurredAt::date} to fall inside
 * it). It is a constructor argument rather than something this class computes, because the bucket
 * depends on a tenant's billing configuration — a monthly bucket for a monthly subscriber, a daily
 * one for a daily subscriber — and that configuration is not finance's to assume. An event whose
 * bucket the caller states is checkable; one this class guessed is not.
 *
 * @param occurredAt       when the usage happened, {@code timestamp(3) without time zone} in UTC
 * @param periodStart      first day of the billing bucket, inclusive
 * @param periodEnd        last day of the billing bucket, inclusive
 */
public record UsageEvent(
        UUID id,
        UUID organizationId,
        UUID projectId,
        UUID customerId,
        UsageDimension dimension,
        BigDecimal quantity,
        String unit,
        String sourceEventType,
        UUID sourceEventId,
        String sourceEntityType,
        UUID sourceEntityId,
        UUID actorUserId,
        LedgerPrincipalType principalType,
        String principalName,
        LocalDateTime occurredAt,
        LocalDate periodStart,
        LocalDate periodEnd,
        UUID billingPeriodId,
        String currency,
        BigDecimal costAmount,
        FxAttribution fx,
        String taxClassification,
        Map<String, Object> metadata,
        UsageEventOrigin origin,
        UUID correctionOfId,
        String correctionReason
) {

    public UsageEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(sourceEventType, "sourceEventType");
        Objects.requireNonNull(sourceEventId, "sourceEventId");
        Objects.requireNonNull(principalType, "principalType");
        Objects.requireNonNull(principalName, "principalName");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(periodStart, "periodStart");
        Objects.requireNonNull(periodEnd, "periodEnd");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(origin, "origin");

        if (quantity.signum() < 0) {
            throw new IllegalArgumentException(
                "usage_events.quantity_check forbids a negative quantity; got " + quantity);
        }
        if (unit.isBlank()) {
            throw new IllegalArgumentException("usage_events.unit_check requires a non-blank unit");
        }
        if (sourceEventType.isBlank()) {
            throw new IllegalArgumentException(
                "usage_events.source_type_check requires a non-blank sourceEventType; a usage event "
                    + "that cannot name its source is unrepresentable by design (ADR 0007 rule 1)");
        }
        if (sourceEntityId != null && (sourceEntityType == null || sourceEntityType.isBlank())) {
            throw new IllegalArgumentException(
                "usage_events.source_entity_check requires a sourceEntityType whenever a "
                    + "sourceEntityId is present");
        }
        if (principalName.isBlank()) {
            throw new IllegalArgumentException(
                "usage_events.principal_check requires a principal; actorUserId is optional because a "
                    + "job, an AI run and a connector are all legitimate causes, but the principal "
                    + "label is not");
        }
        if (periodEnd.isBefore(periodStart)) {
            throw new IllegalArgumentException(
                "A billing bucket with periodEnd < periodStart contains nothing and every rollup that "
                    + "reads it would silently skip the event (usage_events_period_check)");
        }
        // occurredAt is a LocalDateTime in UTC by convention (ADR-0002: every timestamp(3) column is
        // `without time zone` and the database session is UTC), so the date the schema compares is
        // simply the local date of that value.
        LocalDate usageDate = occurredAt.toLocalDate();
        if (usageDate.isBefore(periodStart) || usageDate.isAfter(periodEnd)) {
            throw new IllegalArgumentException(
                "usage_events_period_contains_event_check requires occurredAt to fall inside the "
                    + "billing bucket it claims; " + usageDate + " is outside [" + periodStart + ", "
                    + periodEnd + "]");
        }
        if (!currency.matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException(
                "Every amount carries a currency and public.firstelement_currency_code() checks the "
                    + "shape; '" + currency + "' is not an ISO-4217 alphabetic code");
        }
        if (costAmount != null && costAmount.signum() < 0) {
            throw new IllegalArgumentException(
                "usage_events.cost_check forbids a negative cost (" + costAmount.toPlainString()
                    + "). A negative cost is a sign error; a discount is a rate-card line or an "
                    + "approved finance adjustment.");
        }
        if ((correctionOfId == null) != (correctionReason == null)) {
            throw new IllegalArgumentException(
                "usage_events.correction_check ties correctionOfId and correctionReason together: a "
                    + "correction that names a row must say why, because an unexplained correction is "
                    + "indistinguishable from tampering with money.");
        }
        if (correctionReason != null && correctionReason.isBlank()) {
            throw new IllegalArgumentException(
                "usage_events.correction_reason_check requires a non-blank correction reason");
        }
        if (correctionOfId != null && correctionOfId.equals(id)) {
            throw new IllegalArgumentException(
                "usage_events.correction_not_self_check: a usage event cannot correct itself");
        }
        metadata = metadata == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    /**
     * Whether this event carries a measured cost.
     *
     * <p>False is a first-class state, not a defect: it means the cost is unknown at write time and
     * must come from a rate-card line, or the bucket is unpriced.
     */
    public boolean hasCost() {
        return costAmount != null;
    }

    /** The cost as a {@link Money}, or {@link Money#unpriced} when no cost was recorded. */
    public Money cost() {
        return costAmount == null ? Money.unpriced(currency) : Money.of(costAmount, currency);
    }

    /** The cost as a plain decimal, or {@code null}. Never {@code 0} for an absent cost. */
    public BigDecimal costOrNull() {
        return costAmount;
    }

    /** Whether this event corrects another. */
    public boolean isCorrection() {
        return correctionOfId != null;
    }

    /**
     * The idempotency key, as the database computes it.
     *
     * <p>{@code usage_events_source_dimension_key} is UNIQUE over
     * {@code (organizationId, sourceEventType, sourceEventId, dimension)}. That tuple <em>is</em> the
     * suppression key, which is why it includes the tenant: a global key would collide two tenants
     * whose providers both emit {@code evt-1} and the violation would disclose to the second tenant
     * that the first has an event of that name.
     *
     * <p>It includes the dimension because one source row may legitimately produce two events — an AI
     * run produces one {@code AI_REQUEST} and one {@code AI_TOKEN} — so the dimension has to
     * disambiguate. A <em>correction</em> of the same source row in the same dimension is therefore
     * also a collision, which is the correct behaviour: a correction is a new event with a
     * <em>different</em> source event id (the adjustment's own id), not a second row under the
     * original's key.
     */
    public String idempotencyKey() {
        return organizationId + "|" + sourceEventType + "|" + sourceEventId + "|" + dimension;
    }

    /** A builder, so a caller cannot accidentally pass arguments in the wrong order. */
    public static Builder builder(UUID organizationId, UUID projectId) {
        return new Builder(organizationId, projectId);
    }

    /** Fluent construction. Every field is explicit; there are no defaults for money. */
    public static final class Builder {

        private final UUID organizationId;
        private final UUID projectId;
        private UUID id = UUID.randomUUID();
        private UUID customerId;
        private UsageDimension dimension;
        private BigDecimal quantity;
        private String unit;
        private String sourceEventType;
        private UUID sourceEventId;
        private String sourceEntityType;
        private UUID sourceEntityId;
        private UUID actorUserId;
        private LedgerPrincipalType principalType;
        private String principalName;
        private LocalDateTime occurredAt;
        private LocalDate periodStart;
        private LocalDate periodEnd;
        private UUID billingPeriodId;
        private String currency;
        private BigDecimal costAmount;
        private FxAttribution fx;
        private String taxClassification;
        private Map<String, Object> metadata = Map.of();
        private UsageEventOrigin origin = UsageEventOrigin.LIVE_METERED;
        private UUID correctionOfId;
        private String correctionReason;

        private Builder(UUID organizationId, UUID projectId) {
            this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
            this.projectId = Objects.requireNonNull(projectId, "projectId");
        }

        public Builder id(UUID value) {
            this.id = value;
            return this;
        }

        public Builder customerId(UUID value) {
            this.customerId = value;
            return this;
        }

        public Builder dimension(UsageDimension value) {
            this.dimension = value;
            return this;
        }

        public Builder quantity(BigDecimal value) {
            this.quantity = value;
            return this;
        }

        public Builder quantity(long value) {
            this.quantity = BigDecimal.valueOf(value);
            return this;
        }

        public Builder unit(String value) {
            this.unit = value;
            return this;
        }

        public Builder sourceEventType(String value) {
            this.sourceEventType = value;
            return this;
        }

        public Builder sourceEventId(UUID value) {
            this.sourceEventId = value;
            return this;
        }

        public Builder sourceEntity(String type, UUID entityId) {
            this.sourceEntityType = type;
            this.sourceEntityId = entityId;
            return this;
        }

        public Builder actorUserId(UUID value) {
            this.actorUserId = value;
            return this;
        }

        public Builder principal(LedgerPrincipalType type, String name) {
            this.principalType = type;
            this.principalName = name;
            return this;
        }

        /**
         * Takes a whole {@link LedgerPrincipal}.
         *
         * <p>Preferred over the two-argument form, because {@code principalType} and {@code principalName}
         * are one fact and splitting them is how a USER row ends up with a job's name on it.
         */
        public Builder principal(LedgerPrincipal principal) {
            this.principalType = principal.type();
            this.principalName = principal.name();
            this.actorUserId = principal.userId();
            return this;
        }

        public Builder occurredAt(LocalDateTime value) {
            this.occurredAt = value;
            return this;
        }

        public Builder bucket(LocalDate start, LocalDate end) {
            this.periodStart = start;
            this.periodEnd = end;
            return this;
        }

        public Builder billingPeriodId(UUID value) {
            this.billingPeriodId = value;
            return this;
        }

        public Builder currency(String value) {
            this.currency = value;
            return this;
        }

        public Builder cost(BigDecimal value) {
            this.costAmount = value;
            return this;
        }

        public Builder cost(Money value) {
            Objects.requireNonNull(value, "cost");
            if (value.isAbsent()) {
                this.costAmount = null;
            } else {
                this.costAmount = value.amount();
            }
            this.currency = value.currency();
            return this;
        }

        public Builder fx(FxAttribution value) {
            this.fx = value;
            return this;
        }

        public Builder taxClassification(String value) {
            this.taxClassification = value;
            return this;
        }

        public Builder metadata(Map<String, Object> value) {
            this.metadata = value;
            return this;
        }

        public Builder origin(UsageEventOrigin value) {
            this.origin = value;
            return this;
        }

        public Builder correction(UUID correctsEventId, String reason) {
            this.correctionOfId = correctsEventId;
            this.correctionReason = reason;
            // Only a non-null target makes this a correction. Passing (null, null) is how a read-back
            // row that is NOT a correction is rebuilt, and marking it ADJUSTMENT would make every
            // ordinary event claim to be a correction in the drill-through.
            if (correctsEventId != null) {
                this.origin = UsageEventOrigin.ADJUSTMENT;
            }
            return this;
        }

        public UsageEvent build() {
            return new UsageEvent(id, organizationId, projectId, customerId, dimension, quantity,
                unit, sourceEventType, sourceEventId, sourceEntityType, sourceEntityId, actorUserId,
                principalType, principalName, occurredAt, periodStart, periodEnd, billingPeriodId,
                currency, costAmount, fx, taxClassification, metadata, origin, correctionOfId,
                correctionReason);
        }
    }
}
