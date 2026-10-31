package com.firstelement.finance.ledger;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The only writer of {@code usage_events}.
 *
 * <h2>What it does that no other class may do</h2>
 *
 * <p>There is exactly one {@code insert into usage_events} in this module and it is here. Not as a
 * convention — as a structural consequence of the class being the only thing that has the statement,
 * the entity and the propagation.
 *
 * <h2>Idempotency, and why it is not a query first</h2>
 *
 * <p>The suppression key is
 * {@code usage_events_source_dimension_key} UNIQUE over
 * {@code (organizationId, sourceEventType, sourceEventId, dimension)}, and this class leans on it with
 * {@code on conflict do nothing}. The obvious alternative — {@code select} then {@code insert} if
 * absent — is the wrong shape twice over:
 *
 * <ul>
 *   <li><b>It is racy.</b> Two workers processing the same redelivered webhook both see "no row" and
 *       both insert; one gets a unique violation and the caller has to distinguish that from a real
 *       failure. {@code on conflict do nothing} is atomic, and the losing writer is told it lost.</li>
 *   <li><b>It hides a real conflict.</b> The suppressed row may carry entirely different values —
 *       a different quantity, a different cost — and the second writer must be able to find out.
 *       {@link #existing} exists for that.</li>
 * </ul>
 *
 * <p>Note what the key does <em>not</em> include: the row {@code id}. Two emissions of the same
 * source event in the same dimension are the same event, so the second is suppressed rather than
 * stored. And it does not include a cost, currency or timestamp, so a retried job cannot produce a
 * second row with a slightly different number — which is precisely the double-counting the ledger
 * exists to prevent.
 *
 * <h2>Same transaction as the domain change</h2>
 *
 * <p>{@code Propagation.MANDATORY}. A usage event that commits in a different transaction from the
 * message it measures is a usage event that survives a rolled-back message, and a usage event whose
 * message never commits is missing usage. Neither is recoverable by a later job, because the job has
 * no way to know it should have written one. So the propagation is not configurable: a caller outside
 * a transaction gets an exception naming this rule, not a silently separate commit.
 *
 * <h2>What the writer never does</h2>
 *
 * <ul>
 *   <li><b>No update, no delete, no upsert.</b> The table carries
 *       {@code firstelement_append_only()} on UPDATE and DELETE; the statement below could not express
 *       a mutation if it tried.</li>
 *   <li><b>No revenue, no markup, no rate.</b> The insert names no such column, and
 *       {@code V20261005080000} fails its own migration if one ever appears.</li>
 *   <li><b>No tenant-scope decision from the caller.</b> {@link #record} takes a validated
 *       {@link UsageEvent} whose organization and project are set by whoever built it through
 *       {@link UsageEventFactory}; the writer's job is to refuse an event that contradicts the
 *       catalogue, not to authorise it.</li>
 * </ul>
 */
@Component
public class UsageEventWriter {

    /**
     * The one statement. Every column the table has, named explicitly, in the table's own order.
     *
     * <p>{@code on conflict do nothing} targets the suppression index by name rather than by omitting
     * the inference clause, so a future unique index cannot change what this statement silently
     * absorbs. If a <em>different</em> constraint were violated — a CHECK, a composite tenant foreign
     * key — the exception still propagates, which is the point: a cross-tenant correction or a
     * negative cost must fail loudly rather than be mistaken for a duplicate.
     */
    private static final String INSERT = """
        insert into usage_events (
            "id", "organizationId", "projectId", "customerId", "dimension", "quantity", "unit",
            "sourceEventType", "sourceEventId", "sourceEntityType", "sourceEntityId", "actorUserId",
            "principalType", "principalName", "occurredAt", "periodStart", "periodEnd",
            "billingPeriodId", "currency", "costAmount", "fxRate", "fxSource", "fxRateDate",
            "taxClassification", "metadata", "correctionOfId", "correctionReason")
        values (
            :id, :organizationId, :projectId, :customerId, cast(:dimension as "UsageDimension"),
            :quantity, :unit, :sourceEventType, :sourceEventId, :sourceEntityType, :sourceEntityId,
            :actorUserId, :principalType, :principalName, :occurredAt, :periodStart, :periodEnd,
            :billingPeriodId, :currency, :costAmount, :fxRate, :fxSource, :fxRateDate,
            :taxClassification, cast(:metadata as jsonb), :correctionOfId, :correctionReason)
        on conflict ("organizationId", "sourceEventType", "sourceEventId", "dimension") do nothing
        """;

    private static final String EXISTING = """
        select u."id", u."organizationId", u."projectId", u."customerId",
               u."dimension"::text as "dimension", u."quantity", u."unit", u."sourceEventType",
               u."sourceEventId", u."sourceEntityType", u."sourceEntityId", u."actorUserId",
               u."principalType", u."principalName", u."occurredAt", u."periodStart", u."periodEnd",
               u."billingPeriodId", u."currency", u."costAmount", u."fxRate", u."fxSource",
               u."fxRateDate", u."taxClassification", u."metadata"::text as "metadata",
               u."correctionOfId", u."correctionReason"
          from usage_events u
         where u."organizationId" = :organizationId
           and u."sourceEventType" = :sourceEventType
           and u."sourceEventId" = :sourceEventId
           and u."dimension" = cast(:dimension as "UsageDimension")
        """;

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final UsageWriteAuditor auditor;

    public UsageEventWriter(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc,
                            ObjectMapper mapper,
                            UsageWriteAuditor auditor) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.auditor = auditor;
    }

    /**
     * Writes one usage event, inside the caller's transaction.
     *
     * <p>With no transaction on the calling thread, Spring's {@code MANDATORY} enforcement refuses
     * before the body runs — {@code IllegalTransactionStateException}, naming the propagation rather
     * than the rule. That is the intended refusal: usage outside a transaction produces either an
     * event that outlives a rolled-back message or a missing one for a committed message, and neither
     * is recoverable by a later job.
     *
     * @return {@link UsageWriteResult#inserted} or
     *         {@link UsageWriteResult#suppressed}; both are successes
     * @throws IllegalArgumentException when the event contradicts the metric catalogue — an
     *         undeclared source event type, or a source type belonging to a different dimension
     */
    @org.springframework.transaction.annotation.Transactional(
        propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public UsageWriteResult record(UsageEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("A usage event must not be null");
        }
        UsagePeriodResolver.requireCatalogueDeclared(event);

        int inserted = jdbc.update(INSERT, parameters(event));
        if (inserted > 0) {
            auditor.recorded(event);
            return UsageWriteResult.inserted(event);
        }
        // Suppressed. Read back so the caller learns what the database actually kept rather than
        // assuming its own values won: a redelivered webhook carrying a different quantity must not
        // be reported as if it had been stored.
        UsageEvent existing = existing(event);
        auditor.suppressed(event, existing);
        return UsageWriteResult.suppressed(event, existing);
    }

    /**
     * Reads back the row the database holds for this suppression key.
     *
     * @return the stored row, or {@code null} when the suppression was caused by something other than
     *         the named index — which should be impossible, and returning {@code null} lets the
     *         caller notice rather than reporting a phantom row
     */
    UsageEvent existing(UsageEvent attempted) {
        java.util.List<UsageEvent> found = jdbc.query(EXISTING,
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("organizationId", attempted.organizationId())
                .addValue("sourceEventType", attempted.sourceEventType())
                .addValue("sourceEventId", attempted.sourceEventId())
                .addValue("dimension", attempted.dimension().wireValue()),
            (rs, rowNum) -> {
                java.math.BigDecimal fxRate = rs.getBigDecimal("fxRate");
                String fxSource = rs.getString("fxSource");
                java.sql.Date fxRateDate = rs.getDate("fxRateDate");
                com.firstelement.finance.money.FxAttribution fx = fxRate == null ? null
                    : new com.firstelement.finance.money.FxAttribution(fxRate, fxSource,
                        fxRateDate.toLocalDate(), rs.getString("currency"), rs.getString("currency"));
                java.util.UUID corrects = rs.getObject("correctionOfId", java.util.UUID.class);
                String correctsReason = rs.getString("correctionReason");
                String currency = rs.getString("currency");

                return UsageEvent.builder(rs.getObject("organizationId", java.util.UUID.class),
                        rs.getObject("projectId", java.util.UUID.class))
                    .id(rs.getObject("id", java.util.UUID.class))
                    .customerId(rs.getObject("customerId", java.util.UUID.class))
                    .dimension(com.firstelement.finance.catalog.UsageDimension.fromWire(
                        rs.getString("dimension")))
                    .quantity(rs.getBigDecimal("quantity"))
                    .unit(rs.getString("unit"))
                    .sourceEventType(rs.getString("sourceEventType"))
                    .sourceEventId(rs.getObject("sourceEventId", java.util.UUID.class))
                    .sourceEntity(rs.getString("sourceEntityType"),
                        rs.getObject("sourceEntityId", java.util.UUID.class))
                    .actorUserId(rs.getObject("actorUserId", java.util.UUID.class))
                    .principal(com.firstelement.finance.ledger.LedgerPrincipalType.fromValue(
                            rs.getString("principalType")),
                        rs.getString("principalName"))
                    .occurredAt(rs.getObject("occurredAt", java.time.LocalDateTime.class))
                    .bucket(rs.getObject("periodStart", java.time.LocalDate.class),
                        rs.getObject("periodEnd", java.time.LocalDate.class))
                    .billingPeriodId(rs.getObject("billingPeriodId", java.util.UUID.class))
                    .currency(currency)
                    .cost(rs.getBigDecimal("costAmount"))
                    .fx(fx)
                    .taxClassification(rs.getString("taxClassification"))
                    .metadata(readMetadata(rs.getString("metadata")))
                    .correction(corrects, correctsReason)
                    // The origin is not a column; the reader supplies it. A row that names what it
                    // corrects is an adjustment whatever the writer believed at the time.
                    .origin(corrects == null ? attempted.origin() : UsageEventOrigin.ADJUSTMENT)
                    .build();
            });
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * Reads a {@code metadata} column back into a map.
     *
     * <p>Static and lenient for the same reason {@code JsonCodec.readMap} is: a corrupt jsonb value
     * must not make a duplicate-suppression read fail, because the caller only wanted to know which row
     * the database kept. Leniency here loses diagnostic detail on a path that is already reporting a
     * duplicate; it does not affect the ledger, whose rows are written by {@link #writeMetadata} and
     * never by this method.
     */
    private static Map<String, Object> readMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return new ObjectMapper().readValue(json, MAP_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private org.springframework.jdbc.core.namedparam.MapSqlParameterSource parameters(
            UsageEvent event) {
        return new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("id", event.id())
            .addValue("organizationId", event.organizationId())
            .addValue("projectId", event.projectId())
            .addValue("customerId", event.customerId())
            .addValue("dimension", event.dimension().wireValue())
            .addValue("quantity", event.quantity())
            .addValue("unit", event.unit())
            .addValue("sourceEventType", event.sourceEventType())
            .addValue("sourceEventId", event.sourceEventId())
            .addValue("sourceEntityType", event.sourceEntityType())
            .addValue("sourceEntityId", event.sourceEntityId())
            .addValue("actorUserId", event.actorUserId())
            .addValue("principalType", event.principalType().value())
            .addValue("principalName", event.principalName())
            .addValue("occurredAt", event.occurredAt())
            .addValue("periodStart", event.periodStart())
            .addValue("periodEnd", event.periodEnd())
            .addValue("billingPeriodId", event.billingPeriodId())
            .addValue("currency", event.currency())
            .addValue("costAmount", event.costOrNull())
            // The FX triple travels together, in both directions (usage_events_fx_pair_check). A rate
            // with no source is not reproducible and a source with no rate is a leftover, so an
            // attribution that exists writes all three and one that does not writes none.
            .addValue("fxRate", event.fx() == null ? null : event.fx().rate())
            .addValue("fxSource", event.fx() == null ? null : event.fx().source())
            .addValue("fxRateDate", event.fx() == null ? null : event.fx().rateDate())
            .addValue("taxClassification", event.taxClassification())
            .addValue("metadata", writeMetadata(event.metadata()))
            .addValue("correctionOfId", event.correctionOfId())
            .addValue("correctionReason", event.correctionReason());
    }

    private String writeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "{}";
        }
        try {
            return mapper.writeValueAsString(metadata);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "usage_events.metadata must be a jsonb object; the value supplied could not be "
                    + "serialized. A usage event that cannot store its attribution cannot be audited.",
                e);
        }
    }

}
