package com.firstelement.finance.drill;

import com.firstelement.security.permission.Permission;
import com.firstelement.security.redaction.FieldRedactionPolicy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * How much a drill-through reveals, and what it never reveals.
 *
 * <h2>Why there are three levels</h2>
 *
 * <p>Because "drill down from the total to the events" is not one privilege. A finance reviewer needs
 * the count, the quantity and the source row ids. Somebody investigating an anomaly needs the source
 * entity too. Somebody investigating <em>who</em> needs the principal — and that is a different
 * question, answered by a different role, with a different blast radius if it leaks. Three levels is
 * the minimum that lets each be granted separately.
 *
 * <h2>What is never projected at any level</h2>
 *
 * <ul>
 *   <li><b>{@code metadata}</b> — never. It is free-form {@code jsonb} and the place a customer-shaped
 *       value ends up. Its <em>keys</em> are projected instead ({@link DrillThroughRow#metadataKeys()}),
 *       which is what makes the row auditable without carrying the values.</li>
 *   <li><b>{@code actorUserId}</b> and <b>{@code principalName}</b> — only at
 *       {@link Level#PRINCIPAL}. {@code principalName} is a label, never an email, so even at that level
 *       it discloses an opaque identity and not an account.</li>
 *   <li><b>{@code customerId}</b> — only at {@link Level#PRINCIPAL}, and it is a uuid rather than a
 *       name; resolving it to a patient is a different surface with its own permission.</li>
 * </ul>
 *
 * <h2>Why the redaction is structural</h2>
 *
 * <p>{@link #columns()} returns the exact column list for a level, and the repository's SQL is written
 * from that list rather than selecting everything and removing fields afterwards. A post-filter removes
 * fields from a <em>response</em>; it does not remove them from a log line, an exception message or a
 * debugger's heap dump. The levels are also allow-lists rather than deny-lists, so a column added to
 * the table later is invisible to every projection that was not updated to include it — which is the
 * direction a redaction policy must fail.
 */
public enum DrillThroughLevel {

    /** Counts, quantities and amounts only. No principal, no source entity, no metadata. */
    AGGREGATE(Permission.ANALYTICS_READ_ORGANIZATION),

    /**
     * Adds the source row: which table, which id, which dimension and unit.
     *
     * <p>This is the level ADR 0007 rule 1 requires — "a dashboard figure resolves to the contributing
     * immutable usage events" — and it is the default for a finance reviewer.
     */
    EVENT(Permission.ANALYTICS_READ_ORGANIZATION),

    /**
     * Adds who caused the usage and which customer it was for.
     *
     * <p>Held separately because it is the level that names people.
     */
    PRINCIPAL(Permission.ANALYTICS_READ_ORGANIZATION);

    private final Permission required;

    DrillThroughLevel(Permission required) {
        this.required = required;
    }

    /** The permission that permits this level. All three share one; the levels are policy, not grants. */
    public Permission requiredPermission() {
        return required;
    }

    /** The wire value, so a client can round-trip a level without parsing prose. */
    public String wireValue() {
        return name();
    }

    /** Parses a wire value, refusing anything undeclared rather than defaulting. */
    public static DrillThroughLevel fromWire(String value) {
        for (DrillThroughLevel level : values()) {
            if (level.name().equals(value)) {
                return level;
            }
        }
        throw new IllegalArgumentException(
            "Unknown DrillThroughLevel '" + value + "'. Declared levels are AGGREGATE,EVENT,PRINCIPAL.");
    }

    /** Whether this level reveals the source row. */
    public boolean revealsSource() {
        return this != AGGREGATE;
    }

    /** Whether this level reveals a principal. */
    public boolean revealsPrincipal() {
        return this == PRINCIPAL;
    }

    /**
     * Whether this level is at least as revealing as {@code other}.
     *
     * <p>Declared order is the disclosure order, so a caller asking for {@code EVENT} while holding only
     * {@code AGGREGATE} is clamped rather than refused. Clamping is deliberate: a reviewer who asked
     * for more than they may have gets the most they may have, and the response says which level was
     * applied so the truncation is visible rather than silent.
     */
    public DrillThroughLevel clampTo(DrillThroughLevel permitted) {
        return ordinal() <= permitted.ordinal() ? this : permitted;
    }

    /**
     * The exact columns this level projects.
     *
     * <p>An allow-list. Anything not named here is not selected, and the repository's SQL is written
     * against this list.
     */
    public Set<String> columns() {
        return switch (this) {
            case AGGREGATE -> Set.of(
                "id", "organizationId", "projectId", "dimension", "quantity", "unit", "currency",
                "costAmount", "occurredAt");
            case EVENT -> Set.of(
                "id", "organizationId", "projectId", "customerId", "dimension", "quantity", "unit",
                "currency", "costAmount", "fxRate", "fxSource", "fxRateDate", "sourceEventType",
                "sourceEventId", "sourceEntityType", "sourceEntityId", "occurredAt", "periodStart",
                "periodEnd", "billingPeriodId", "taxClassification", "metadataKeys",
                "correctionOfId", "correctionReason", "createdAt");
            case PRINCIPAL -> Set.of(
                "id", "organizationId", "projectId", "customerId", "dimension", "quantity", "unit",
                "currency", "costAmount", "fxRate", "fxSource", "fxRateDate", "sourceEventType",
                "sourceEventId", "sourceEntityType", "sourceEntityId", "actorUserId",
                "principalType", "principalName", "occurredAt", "periodStart", "periodEnd",
                "billingPeriodId", "taxClassification", "metadataKeys", "correctionOfId",
                "correctionReason", "createdAt");
        };
    }

    /** The field-redaction policy this level implies. */
    public FieldRedactionPolicy redactionPolicy() {
        return revealsPrincipal() ? FieldRedactionPolicy.none() : FieldRedactionPolicy.of(Set.of(
            "actorUserId", "principalName", "customerId", "metadata"));
    }

    /** Whether the caller may use this level. */
    public boolean isPermittedFor(Set<Permission> effectivePermissions) {
        return effectivePermissions != null && effectivePermissions.contains(required);
    }

    /** One drill-through row, with every level-dependent field nullable rather than absent. */
    public record DrillThroughRow(
            UUID id,
            UUID organizationId,
            UUID projectId,
            UUID customerId,
            String dimension,
            java.math.BigDecimal quantity,
            String unit,
            String currency,
            java.math.BigDecimal costAmount,
            java.math.BigDecimal fxRate,
            String fxSource,
            java.time.LocalDate fxRateDate,
            String sourceEventType,
            UUID sourceEventId,
            String sourceEntityType,
            UUID sourceEntityId,
            UUID actorUserId,
            String principalType,
            String principalName,
            java.time.LocalDateTime occurredAt,
            java.time.LocalDate periodStart,
            java.time.LocalDate periodEnd,
            UUID billingPeriodId,
            String taxClassification,
            List<String> metadataKeys,
            UUID correctionOfId,
            String correctionReason,
            java.time.LocalDateTime createdAt,
            DrillThroughLevel level
    ) {

        /**
         * The rate that priced this event, when one applies.
         *
         * <p>A usage event does not carry a rate — the schema forbids it and
         * {@code V20261005080000} fails its migration if a rate column appears. So the drill-through
         * answers "which rate priced this" by naming the snapshot that used it, and the caller reads
         * the pinned markup there. Returning empty here rather than a rate is the honest answer.
         */
        public java.util.Optional<java.util.UUID> rateCardVersionId() {
            return java.util.Optional.empty();
        }

        /** Whether this row is a correction of another. */
        public boolean isCorrection() {
            return correctionOfId != null;
        }
    }
}
