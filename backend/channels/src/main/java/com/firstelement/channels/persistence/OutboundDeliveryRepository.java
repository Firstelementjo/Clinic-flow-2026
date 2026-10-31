package com.firstelement.channels.persistence;

import com.firstelement.crm.query.CrmQueryScope;
import com.firstelement.crm.persistence.JsonCodec;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The outbound half of {@code messages}: delivery status tracking.
 *
 * <h2>FR-4.5, verbatim</h2>
 *
 * <p>"Persist outbound messages before delivery. Track pending, sent, delivered, read, failed,
 * provider ID, attempt count, and failure reason." The enum
 * {@code MessageDeliveryStatus (PENDING, SENT, DELIVERED, READ, FAILED)} already exists in the
 * baseline, so every column this class writes is one the schema declared for exactly this purpose.
 *
 * <h2>Attempt count has no column</h2>
 *
 * <p>The baseline has no {@code attempts} column on {@code messages}, and no migration was
 * permitted in this phase. The count therefore lives in {@code messages.metadata} — the same
 * {@code jsonb} column Phase 4 used for the reply idempotency key, and the column the legacy
 * Prisma schema used for message attributes. The authoritative attempt history is
 * {@code job_attempts}; this copy exists so the message row an employee reads can say "3rd
 * attempt" without a join.
 *
 * <h2>Every statement carries the tenant predicate</h2>
 *
 * <p>A message id in a job payload is a request, not authorization. {@link #load} goes through
 * {@link CrmQueryScope}, so the scope comes from the resolved {@code TenantContext} rather than from
 * the payload, and a job for another tenant's message finds nothing.
 */
@Repository
public class OutboundDeliveryRepository {

    /** The metadata keys this class owns. Namespaced so no future writer collides. */
    public static final String META_DELIVERY_ATTEMPTS = "deliveryAttempts";
    public static final String META_DELIVERY_CHANNEL = "deliveryChannel";
    public static final String META_DELIVERY_JOB = "deliveryJobId";

    private static final String SELECT = """
        select m."id", m."organizationId", m."projectId", m."conversationId", m."channel",
               m."direction", m."body", m."deliveryStatus"::text as "deliveryStatus",
               m."failureReason", m."externalMessageId", m."metadata"::text as "metadata",
               m."sentAt", c."customerId"
          from messages m
          join conversations c on c."id" = m."conversationId"
        """;

    /**
     * SENT: handed to the provider. Terminal for the dispatcher.
     *
     * <p>The attempt count is incremented here rather than by the caller so a concurrent attempt
     * cannot lose an increment by reading the same value. It is written as a plain jsonb merge
     * because {@code jsonb_set} with an {@code ARRAY[$1]} path needs a cast PostgreSQL will not
     * infer, and a delivery status write is the wrong place for a syntax experiment.
     */
    private static final String MARK_SENT = """
        update messages
           set "deliveryStatus"    = cast('SENT' as "MessageDeliveryStatus"),
               "externalMessageId" = coalesce(:providerMessageId, "externalMessageId"),
               "failureReason"     = null,
               "metadata"          = coalesce("metadata", '{}'::jsonb) || jsonb_build_object(
                   :attemptKey, to_jsonb((coalesce(
                       ("metadata"->>'deliveryAttempts')::int, 0) + 1)::text))
         where "id" = :id
           and "organizationId" = :__fe_crm_organization_id
           and "projectId" = :__fe_crm_project_id
        """;

    /**
     * FAILED: recorded on the message row, bounded, never swallowed.
     *
     * <p>The legacy dispatcher truncated to 500 characters for the same reason this does: "a
     * failure is shown on the message rather than swallowed."
     */
    private static final String MARK_FAILED = """
        update messages
           set "deliveryStatus" = cast('FAILED' as "MessageDeliveryStatus"),
               "failureReason"  = :reason,
               "metadata"       = coalesce("metadata", '{}'::jsonb) || jsonb_build_object(
                   :attemptKey, to_jsonb((coalesce(
                       ("metadata"->>'deliveryAttempts')::int, 0) + 1)::text))
         where "id" = :id
           and "organizationId" = :__fe_crm_organization_id
           and "projectId" = :__fe_crm_project_id
        """;

    /**
     * DELIVERED / READ: written when the provider reports a status callback.
     *
     * <p>Guarded on the current status so a late SENT cannot downgrade a DELIVERED row - a status
     * callback can arrive out of order, and downgrading would make the UI claim a message is still
     * in flight after the customer has read it.
     */
    private static final String MARK_PROVIDER_STATUS = """
        update messages
           set "deliveryStatus" = cast(:status as "MessageDeliveryStatus")
         where "id" = :id
           and "organizationId" = :__fe_crm_organization_id
           and "projectId" = :__fe_crm_project_id
           and "deliveryStatus" not in ('READ', 'DELIVERED')
        """;

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonCodec json;

    public OutboundDeliveryRepository(NamedParameterJdbcTemplate jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** The message the delivery job acts on, read under tenant scope. */
    public Optional<DeliverableMessage> load(CrmQueryScope scope, UUID messageId) {
        CrmQueryScope.Statement statement = scope.select(SELECT, "m",
            "m.\"id\" = :messageId", CrmQueryScope.params("messageId", messageId));
        return jdbc.query(statement.sql(), statement.parameters(), (rs, rowNum) -> {
            Map<String, Object> metadata = json.readMap(rs.getString("metadata"));
            return new DeliverableMessage(
                rs.getObject("id", UUID.class),
                rs.getObject("organizationId", UUID.class),
                rs.getObject("projectId", UUID.class),
                rs.getObject("conversationId", UUID.class),
                rs.getObject("customerId", UUID.class),
                rs.getString("channel"),
                rs.getString("direction"),
                rs.getString("body"),
                rs.getString("deliveryStatus"),
                rs.getString("failureReason"),
                rs.getString("externalMessageId"),
                attemptsFrom(metadata),
                rs.getObject("sentAt", LocalDateTime.class));
        }).stream().findFirst();
    }

    /**
     * Records a successful hand-off.
     *
     * @return false when the row is gone or belongs to another tenant; the caller must then treat
     *         the send as an error rather than as delivered
     */
    public boolean markSent(CrmQueryScope scope, UUID messageId, String providerMessageId) {
        return jdbc.update(MARK_SENT, scope.bind(CrmQueryScope.params(
            "id", messageId,
            "providerMessageId", providerMessageId,
            "attemptKey", META_DELIVERY_ATTEMPTS))) == 1;
    }

    public boolean markFailed(CrmQueryScope scope, UUID messageId, String reason) {
        return jdbc.update(MARK_FAILED, scope.bind(CrmQueryScope.params(
            "id", messageId,
            "reason", bound(reason),
            "attemptKey", META_DELIVERY_ATTEMPTS))) == 1;
    }

    /** @return true when the status advanced; false when the row was already further along. */
    public boolean markProviderStatus(CrmQueryScope scope, UUID messageId, String status) {
        return jdbc.update(MARK_PROVIDER_STATUS, scope.bind(CrmQueryScope.params(
            "id", messageId, "status", status))) == 1;
    }

    /**
     * The conversation's most recent inbound message time: the anchor for a platform's
     * customer-service window.
     *
     * <p>Tenant-scoped through {@link CrmQueryScope}. Uses
     * {@code messages_projectId_channel_externalMessageId_idx}'s sibling
     * {@code messages("conversationId")} access path rather than the tenant index, because the
     * conversation is already inside the pinned project.
     */
    public Optional<LocalDateTime> lastInboundAt(CrmQueryScope scope, UUID conversationId) {
        CrmQueryScope.Statement statement = scope.select("""
            select max(m."sentAt")
              from messages m
            """, "m",
            "m.\"conversationId\" = :conversationId"
                + " and m.\"direction\" = cast('INBOUND' as \"MessageDirection\")",
            CrmQueryScope.params("conversationId", conversationId));
        return java.util.Optional.ofNullable(
            jdbc.queryForObject(statement.sql(), statement.parameters(), LocalDateTime.class));
    }

    /** Whether a retry would be a duplicate send: the message already reached the provider. */
    public static boolean alreadyDelivered(String deliveryStatus) {
        return "SENT".equals(deliveryStatus) || "DELIVERED".equals(deliveryStatus)
            || "READ".equals(deliveryStatus);
    }

    private static int attemptsFrom(Map<String, Object> metadata) {
        Object value = metadata.get(META_DELIVERY_ATTEMPTS);
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String bound(String reason) {
        if (reason == null) {
            return null;
        }
        String flattened = reason.replaceAll("[\\r\\n\\t]", " ");
        return flattened.length() <= 500 ? flattened : flattened.substring(0, 500) + "…";
    }

    /**
     * The message a delivery job acts on.
     *
     * @param body the customer-facing text. It is read here and handed to the connector; it is
     *             never written to a log, a metric tag or a dead-letter summary.
     */
    public record DeliverableMessage(UUID id,
                                     UUID organizationId,
                                     UUID projectId,
                                     UUID conversationId,
                                     UUID customerId,
                                     String channel,
                                     String direction,
                                     String body,
                                     String deliveryStatus,
                                     String failureReason,
                                     String providerMessageId,
                                     int deliveryAttempts,
                                     LocalDateTime sentAt) {

        public boolean outbound() {
            return "OUTBOUND".equals(direction);
        }
    }
}
