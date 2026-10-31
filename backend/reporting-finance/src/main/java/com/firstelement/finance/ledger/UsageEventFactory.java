package com.firstelement.finance.ledger;

import com.firstelement.finance.catalog.FinanceMetricCatalogue;
import com.firstelement.finance.catalog.UsageDimension;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The typed emit surface other modules call, one method per billable dimension.
 *
 * <h2>Why the factories exist at all</h2>
 *
 * <p>Emitting usage correctly takes five decisions — which dimension, which canonical unit, which
 * declared source event type, which principal, and whether a cost is known at write time — and every
 * one of them is a finance decision rather than a plumbing detail. A module that causes a message
 * should say "a message was delivered" and nothing else; it should not be choosing whether the unit
 * is {@code message} or whether it is allowed to attach a dollar figure.
 *
 * <p>So {@link UsageEventWriter} takes a {@link UsageEvent}, and this class is the only thing that
 * builds one. There is no generic {@code record(dimension, quantity, cost)} entry point, because that
 * would hand back exactly the five choices this class exists to make.
 *
 * <h2>Cost may only be supplied where the taxonomy says it is known</h2>
 *
 * <p>{@link #message} and its siblings accept a measured cost because a carrier's tariff produced it.
 * {@link #aiToken} and {@link #aiRequest} accept a cost of {@code null} <em>only</em>, and the refusal
 * message says why: ADR 0009 §3 decided that a mutable provider estimate must never enter the
 * immutable ledger as an amount, so the cost of an AI token comes from the rate card effective on the
 * usage date or the bucket is unpriced. That decision is not a convention this module documents; it
 * is a parameter a caller cannot satisfy.
 *
 * <h2>Every factory requires the same transaction as the domain change</h2>
 *
 * <p>All of these return a command for {@link UsageEventWriter}, which is
 * {@code Propagation.MANDATORY}: a usage event can only be written inside the transaction that made
 * the usage happen. A retried job therefore cannot double-emit (the unique suppression index) and a
 * rolled-back domain change emits nothing.
 */
public final class UsageEventFactory {

    private UsageEventFactory() {
    }

    /**
     * One message delivered to, or received from, a customer channel.
     *
     * @param cost the carrier's per-message cost when the channel meters one, otherwise {@code null}
     */
    public static UsageEvent message(UUID organizationId, UUID projectId, UUID sourceMessageId,
                                     BigDecimal quantity, LocalDateTime occurredAt,
                                     LocalDate bucketStart, LocalDate bucketEnd,
                                     LedgerPrincipal principal, String currency, BigDecimal cost,
                                     String taxClassification) {
        return delivery(organizationId, projectId, UsageDimension.MESSAGE, "messages.delivered",
            sourceMessageId, quantity, occurredAt, bucketStart, bucketEnd, principal, currency, cost,
            taxClassification);
    }

    /** One message received from a customer channel. */
    public static UsageEvent inboundMessage(UUID organizationId, UUID projectId,
                                            UUID sourceMessageId, BigDecimal quantity,
                                            LocalDateTime occurredAt, LocalDate bucketStart,
                                            LocalDate bucketEnd, LedgerPrincipal principal,
                                            String currency, BigDecimal cost,
                                            String taxClassification) {
        return delivery(organizationId, projectId, UsageDimension.MESSAGE, "messages.received",
            sourceMessageId, quantity, occurredAt, bucketStart, bucketEnd, principal, currency, cost,
            taxClassification);
    }

    /** One conversation reaching a countable state. */
    public static UsageEvent conversation(UUID organizationId, UUID projectId,
                                          UUID sourceConversationId, BigDecimal quantity,
                                          LocalDateTime occurredAt, LocalDate bucketStart,
                                          LocalDate bucketEnd, LedgerPrincipal principal,
                                          String currency, BigDecimal cost,
                                          String taxClassification) {
        return delivery(organizationId, projectId, UsageDimension.CONVERSATION,
            "conversations.resolved", sourceConversationId, quantity, occurredAt, bucketStart,
            bucketEnd, principal, currency, cost, taxClassification);
    }

    /**
     * Kilobytes carried on a channel connection, metered by the provider.
     *
     * <p>The unit is kilobytes and the cost is the provider's charge for them, so the two travel
     * together: a {@code CHANNEL_TRAFFIC} event with a cost but no quantity would be a billable
     * amount nobody can check against a byte count.
     */
    public static UsageEvent channelTraffic(UUID organizationId, UUID projectId, UUID sourceConnectionId,
                                            BigDecimal kilobytes, LocalDateTime occurredAt,
                                            LocalDate bucketStart, LocalDate bucketEnd,
                                            LedgerPrincipal principal, String currency,
                                            BigDecimal cost, String taxClassification) {
        return delivery(organizationId, projectId, UsageDimension.CHANNEL_TRAFFIC,
            "channel_traffic.metered", sourceConnectionId, kilobytes, occurredAt, bucketStart,
            bucketEnd, principal, currency, cost, taxClassification);
    }

    /** One licensed seat for one day. */
    public static UsageEvent seatDay(UUID organizationId, UUID projectId, UUID sourceUserId,
                                     LocalDate day, LedgerPrincipal principal, String currency,
                                     BigDecimal cost) {
        return delivery(organizationId, projectId, UsageDimension.SEAT, "seat_licenses.day",
            sourceUserId, BigDecimal.ONE, day.atStartOfDay(), day, day, principal, currency, cost,
            null);
    }

    /** Retained bytes for one day. */
    public static UsageEvent storageByteDay(UUID organizationId, UUID projectId, UUID sourceEntityId,
                                            String sourceEntityType, BigDecimal bytes, LocalDate day,
                                            LedgerPrincipal principal, String currency,
                                            BigDecimal cost) {
        UsageEvent.Builder builder = UsageEvent.builder(organizationId, projectId)
            .dimension(UsageDimension.STORAGE)
            .quantity(bytes)
            .sourceEventType("storage.byte_day")
            .sourceEventId(sourceEntityId)
            .sourceEntity(sourceEntityType, sourceEntityId)
            .occurredAt(day.atStartOfDay())
            .bucket(day, day)
            .principal(principal.type(), principal.name())
            .currency(currency)
            .origin(UsageEventOrigin.PROVIDER_REPORT);
        if (cost != null) {
            builder.cost(cost);
        }
        return builder.build();
    }

    /** One metered API operation. */
    public static UsageEvent apiOperation(UUID organizationId, UUID projectId, UUID sourceOperationId,
                                          LocalDateTime occurredAt, LocalDate bucketStart,
                                          LocalDate bucketEnd, LedgerPrincipal principal,
                                          String currency) {
        return usage(organizationId, projectId, UsageDimension.API_OPERATION,
            "api_operations.metered", sourceOperationId, BigDecimal.ONE, occurredAt, bucketStart,
            bucketEnd, principal, currency, null);
    }

    /** One workflow run. */
    public static UsageEvent workflowRun(UUID organizationId, UUID projectId, UUID sourceRunId,
                                         LocalDateTime occurredAt, LocalDate bucketStart,
                                         LocalDate bucketEnd, LedgerPrincipal principal,
                                         String currency) {
        return usage(organizationId, projectId, UsageDimension.WORKFLOW_RUN,
            "workflow_runs.started", sourceRunId, BigDecimal.ONE, occurredAt, bucketStart, bucketEnd,
            principal, currency, null);
    }

    /** One export artefact produced. */
    public static UsageEvent reportExport(UUID organizationId, UUID projectId, UUID sourceJobId,
                                          LocalDateTime occurredAt, LocalDate bucketStart,
                                          LocalDate bucketEnd, LedgerPrincipal principal,
                                          String currency) {
        return usage(organizationId, projectId, UsageDimension.REPORT_EXPORT,
            "export_jobs.completed", sourceJobId, BigDecimal.ONE, occurredAt, bucketStart, bucketEnd,
            principal, currency, null);
    }

    /** One indexed knowledge chunk. */
    public static UsageEvent documentChunk(UUID organizationId, UUID projectId, UUID sourceChunkId,
                                           LocalDateTime occurredAt, LocalDate bucketStart,
                                           LocalDate bucketEnd, LedgerPrincipal principal,
                                           String currency) {
        return usage(organizationId, projectId, UsageDimension.DOCUMENT_CHUNK,
            "document_chunks.indexed", sourceChunkId, BigDecimal.ONE, occurredAt, bucketStart,
            bucketEnd, principal, currency, null);
    }

    /**
     * One billable minute of approved effort.
     *
     * <p>Refuses a {@code null} quantity and refuses any derivation from a conversation count: this is
     * the one dimension whose whole purpose is to be entered by a human, and a module that computed
     * "billing minutes" from conversation durations would be doing exactly what ADR 0007 rule 3
     * forbids in the other direction.
     */
    public static UsageEvent timeEntry(UUID organizationId, UUID projectId, UUID sourceEntryId,
                                       BigDecimal minutes, LocalDateTime occurredAt,
                                       LocalDate bucketStart, LocalDate bucketEnd,
                                       LedgerPrincipal principal, String currency) {
        if (minutes == null) {
            throw new IllegalArgumentException(
                "A TIME_ENTRY event must state how many minutes; effort is entered, never derived.");
        }
        return usage(organizationId, projectId, UsageDimension.TIME_ENTRY,
            "time_entries.approved", sourceEntryId, minutes, occurredAt, bucketStart, bucketEnd,
            principal, currency, null);
    }

    /**
     * One AI run request.
     *
     * <p>No cost parameter, at all. The method signature is the decision: ADR 0009 §3 refused to
     * migrate {@code ai_usage.estimatedCost} into the ledger as an amount, so the cost of an AI run is
     * derived from the rate card effective on the usage date, and a bucket with no such rate card is
     * unpriced rather than zero.
     */
    public static UsageEvent aiRequest(UUID organizationId, UUID projectId, UUID sourceRunId,
                                       LocalDateTime occurredAt, LocalDate bucketStart,
                                       LocalDate bucketEnd, LedgerPrincipal principal,
                                       String currency) {
        return ai(organizationId, projectId, UsageDimension.AI_REQUEST, "ai_runs.started",
            sourceRunId, BigDecimal.ONE, occurredAt, bucketStart, bucketEnd, principal, currency);
    }

    /**
     * One AI token, input or output, as the provider reported it.
     *
     * <p>Two events are written per run — one request and one token — because the rate card prices
     * them separately. A rollup carrying only tokens could not name which of the two produced a cost.
     */
    public static UsageEvent aiToken(UUID organizationId, UUID projectId, UUID sourceRunId,
                                     BigDecimal tokens, LocalDateTime occurredAt,
                                     LocalDate bucketStart, LocalDate bucketEnd,
                                     LedgerPrincipal principal, String currency) {
        if (tokens == null || tokens.signum() < 0) {
            throw new IllegalArgumentException(
                "An AI_TOKEN event must state the token count the provider reported; got " + tokens);
        }
        return ai(organizationId, projectId, UsageDimension.AI_TOKEN, "ai_usage.tokens_recorded",
            sourceRunId, tokens, occurredAt, bucketStart, bucketEnd, principal, currency);
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private static UsageEvent delivery(UUID organizationId, UUID projectId, UsageDimension dimension,
                                       String sourceEventType, UUID sourceEventId,
                                       BigDecimal quantity, LocalDateTime occurredAt,
                                       LocalDate bucketStart, LocalDate bucketEnd,
                                       LedgerPrincipal principal, String currency, BigDecimal cost,
                                       String taxClassification) {
        if (!FinanceMetricCatalogue.costMayBeSuppliedAtEmitTime(dimension)) {
            throw new IllegalStateException(
                dimension + " does not accept a measured cost at emit time; its cost comes from the "
                    + "rate card effective on the usage date. See ADR 0009 section 3.");
        }
        UsageEvent.Builder builder = base(organizationId, projectId, dimension, sourceEventType,
            sourceEventId, quantity, occurredAt, bucketStart, bucketEnd, principal, currency);
        if (cost != null) {
            builder.cost(cost);
        }
        if (taxClassification != null) {
            builder.taxClassification(taxClassification);
        }
        return builder.build();
    }

    private static UsageEvent usage(UUID organizationId, UUID projectId, UsageDimension dimension,
                                     String sourceEventType, UUID sourceEventId,
                                     BigDecimal quantity, LocalDateTime occurredAt,
                                     LocalDate bucketStart, LocalDate bucketEnd,
                                     LedgerPrincipal principal, String currency, BigDecimal cost) {
        if (cost != null) {
            throw new IllegalStateException(
                dimension + " must not carry an emit-time cost (" + cost.toPlainString() + "). This "
                    + "dimension is priced from the rate card; a hand-supplied amount here would be "
                    + "a mutable number masquerading as finance truth (ADR 0007 rule 3).");
        }
        return base(organizationId, projectId, dimension, sourceEventType, sourceEventId, quantity,
            occurredAt, bucketStart, bucketEnd, principal, currency).build();
    }

    private static UsageEvent ai(UUID organizationId, UUID projectId, UsageDimension dimension,
                                 String sourceEventType, UUID sourceEventId, BigDecimal quantity,
                                 LocalDateTime occurredAt, LocalDate bucketStart,
                                 LocalDate bucketEnd, LedgerPrincipal principal, String currency) {
        UsageEvent.Builder builder = base(organizationId, projectId, dimension, sourceEventType,
            sourceEventId, quantity, occurredAt, bucketStart, bucketEnd, principal, currency);
        // AI provenance is evidence, not cache: the run is named so the drill-through lands on the
        // ai_runs row and not on a token count with nothing behind it.
        builder.sourceEntity("ai_runs", sourceEventId);
        return builder.build();
    }

    /**
     * The common shape, with the three catalogue checks applied.
     *
     * <p>Unit and source-type are taken from the catalogue rather than from the caller, so those two
     * decisions cannot be made wrongly; and the taxonomy decides whether a cost may be attached, which
     * is checked by the two helpers above rather than here so each dimension gets the message naming
     * its own family.
     */
    private static UsageEvent.Builder base(UUID organizationId, UUID projectId,
                                           UsageDimension dimension, String sourceEventType,
                                           UUID sourceEventId, BigDecimal quantity,
                                           LocalDateTime occurredAt, LocalDate bucketStart,
                                           LocalDate bucketEnd, LedgerPrincipal principal,
                                           String currency) {
        requireDeclaredSourceEventType(sourceEventType, dimension);
        return UsageEvent.builder(organizationId, projectId)
            .dimension(dimension)
            // The canonical unit, never a caller-supplied one: usage_rollup_snapshots has no unit
            // column, so two units for one dimension would be summed into a quantity of nothing.
            .unit(dimension.canonicalUnit())
            .quantity(quantity == null ? BigDecimal.ONE : quantity)
            .sourceEventType(sourceEventType)
            .sourceEventId(sourceEventId)
            .occurredAt(occurredAt)
            .bucket(bucketStart, bucketEnd)
            .principal(principal.type(), principal.name())
            .currency(currency);
    }

    private static void requireDeclaredSourceEventType(String sourceEventType,
                                                       UsageDimension dimension) {
        UsageDimension declared =
            FinanceMetricCatalogue.dimensionForSourceEventType(sourceEventType);
        if (declared == null) {
            throw new IllegalArgumentException(
                "Source event type '" + sourceEventType + "' is not in the finance metric catalogue. "
                    + "Declared types are "
                    + String.join(", ", FinanceMetricCatalogue.declaredSourceEventTypes()));
        }
        if (declared != dimension) {
            throw new IllegalArgumentException(
                "Source event type '" + sourceEventType + "' belongs to " + declared + ", not "
                    + dimension + ". One source row may produce at most one dimension.");
        }
    }
}
