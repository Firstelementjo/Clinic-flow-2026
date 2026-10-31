package com.firstelement.crm.persistence;

import com.firstelement.crm.domain.ChannelType;
import com.firstelement.crm.domain.MessageAuthorType;
import com.firstelement.crm.domain.MessageDeliveryStatus;
import com.firstelement.crm.domain.MessageDirection;
import com.firstelement.crm.query.CrmQueryScope;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code messages} — the transcript.
 *
 * <h2>Two uniqueness guarantees, and they mean different things</h2>
 *
 * <ul>
 *   <li>{@code messages_projectId_channel_externalMessageId_key} — a provider redelivered a
 *       message we already stored. The legacy pipeline decided this with a
 *       {@code catch} on the unique violation rather than a preceding {@code SELECT}, and the
 *       comment says why: "a prior SELECT would race under concurrent deliveries and
 *       double-post to the customer". {@link #insertInbound} keeps that shape by translating
 *       the violation into a {@link DuplicateInboundMessageException}.</li>
 *   <li>{@code metadata->>'idempotencyKey'} — the caller retried an employee reply. Handled by
 *       {@link #findByIdempotencyKey} plus the unique-shaped lookup, and it is the mechanism
 *       that stops a double-clicked Send from sending a customer two identical messages.</li>
 * </ul>
 *
 * <h2>Persist first, send second</h2>
 *
 * <p>A row is written before any provider is contacted, and it starts {@code PENDING}. A send
 * that succeeds while the write fails would leave the customer holding a reply the CRM has no
 * record of, which is the failure this ordering exists to prevent.
 */
@Repository
public class MessageRepository {

    static final String SELECT = """
        select m."id", m."organizationId", m."projectId", m."conversationId", m."channel",
               m."direction", m."authorType", m."authorUserId", m."body", m."deliveryStatus",
               m."failureReason", m."externalMessageId", m."metadata", m."sentAt", m."createdAt",
               u."fullName" as "authorFullName"
        from messages m
        left join users u on u."id" = m."authorUserId"
        """;

    static final RowMapper<MessageRow> ROW = (rs, rowNum) -> new MessageRow(
        rs.getObject("id", UUID.class),
        rs.getObject("organizationId", UUID.class),
        rs.getObject("projectId", UUID.class),
        rs.getObject("conversationId", UUID.class),
        rs.getString("channel"),
        rs.getString("direction"),
        rs.getString("authorType"),
        rs.getObject("authorUserId", UUID.class),
        rs.getString("body"),
        rs.getString("deliveryStatus"),
        rs.getString("failureReason"),
        rs.getString("externalMessageId"),
        rs.getString("metadata"),
        rs.getObject("sentAt", LocalDateTime.class),
        rs.getObject("createdAt", LocalDateTime.class),
        rs.getString("authorFullName"));

    private final NamedParameterJdbcTemplate jdbc;

    public MessageRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A thread's messages, oldest first. The legacy detail view capped this at 300. */
    public List<MessageRow> forConversation(CrmQueryScope scope, UUID conversationId, int limit) {
        CrmQueryScope.Statement statement = scope.select(SELECT, "m",
            "m.\"conversationId\" = :conversationId",
            CrmQueryScope.params("conversationId", conversationId))
            .orderedBy("m.\"sentAt\" asc, m.\"id\" asc", limit);
        return jdbc.query(statement.sql(), statement.parameters(), ROW);
    }

    /**
     * The customer-facing transcript.
     *
     * <p>The separation from the internal view is enforced here, in the {@code WHERE} clause,
     * rather than in a response filter:
     *
     * <ul>
     *   <li>{@code m."authorType" <> 'SYSTEM'} — system notices are operational, not part of
     *       what the customer was told.</li>
     *   <li>{@code m."failureReason" is not selected at all} — a delivery failure is the
     *       provider's state, not the customer's business.</li>
     * </ul>
     *
     * <p>Internal notes never appear here at all: they are not messages.
     */
    public List<MessageRow> customerVisibleTranscript(CrmQueryScope scope,
                                                       UUID conversationId,
                                                       int limit) {
        CrmQueryScope.Statement statement = scope.select(
            """
            select m."id", m."organizationId", m."projectId", m."conversationId", m."channel",
                   m."direction", m."authorType", m."authorUserId", m."body", m."deliveryStatus",
                   null::text as "failureReason", m."externalMessageId", m."metadata",
                   m."sentAt", m."createdAt", null::text as "authorFullName"
            from messages m
            """, "m",
            "m.\"conversationId\" = :conversationId"
                + " and m.\"authorType\" <> cast('SYSTEM' as \"MessageAuthorType\")",
            CrmQueryScope.params("conversationId", conversationId))
            .orderedBy("m.\"sentAt\" asc, m.\"id\" asc", limit);
        return jdbc.query(statement.sql(), statement.parameters(), ROW);
    }

    /**
     * A previously stored reply for this {@code Idempotency-Key}, or empty.
     *
     * <p>The lookup is tenant- and project-scoped like every other statement, so a key is
     * private to the conversation it was used on.
     */
    public java.util.Optional<MessageRow> findByIdempotencyKey(CrmQueryScope scope,
                                                               UUID conversationId,
                                                               String idempotencyKey) {
        CrmQueryScope.Statement statement = scope.select(SELECT, "m",
            "m.\"conversationId\" = :conversationId"
                + " and m.\"metadata\"->>'idempotencyKey' = :idempotencyKey",
            CrmQueryScope.params("conversationId", conversationId,
                "idempotencyKey", idempotencyKey));
        return jdbc.query(statement.sql(), statement.parameters(), ROW).stream().findFirst();
    }

    /**
     * Writes an employee reply.
     *
     * <p>The {@code idempotencyKey} is stored inside {@code metadata} rather than in a new
     * column or table: it is per-message, tenant-private, and {@code jsonb} is already the
     * column the legacy runtime used for message attributes. See
     * {@code IdempotencyKeyStore} for why no separate table exists yet.
     */
    public MessageRow insertOutbound(CrmQueryScope scope,
                                     UUID messageId,
                                     UUID conversationId,
                                     ChannelType channel,
                                     String body,
                                     UUID authorUserId,
                                     String idempotencyKey,
                                     LocalDateTime sentAt) {
        MapSqlParameterSource parameters = scope.bind(CrmQueryScope.params(
            "id", messageId,
            "conversationId", conversationId,
            "channel", channel.name(),
            "body", body,
            "authorUserId", authorUserId,
            "metadata", metadata(idempotencyKey),
            "sentAt", sentAt));
        jdbc.update("""
            insert into messages ("id", "organizationId", "projectId", "conversationId", "channel",
                "direction", "authorType", "authorUserId", "body", "deliveryStatus", "metadata",
                "sentAt", "createdAt")
            values (:id, :__fe_crm_organization_id, :__fe_crm_project_id, :conversationId,
                cast(:channel as "ChannelType"), cast('OUTBOUND' as "MessageDirection"),
                cast('EMPLOYEE' as "MessageAuthorType"), :authorUserId, :body,
                cast('PENDING' as "MessageDeliveryStatus"), CAST(:metadata AS jsonb), :sentAt, :sentAt)
            """, parameters);
        return new MessageRow(messageId, scope.organizationId(), scope.projectId(), conversationId,
            channel.name(), MessageDirection.OUTBOUND.name(), MessageAuthorType.EMPLOYEE.name(),
            authorUserId, body, MessageDeliveryStatus.PENDING.name(), null, null, null,
            sentAt, sentAt, null);
    }

    /**
     * Writes an inbound customer message.
     *
     * @throws DuplicateInboundMessageException when the provider redelivered it
     */
    public MessageRow insertInbound(CrmQueryScope scope,
                                    UUID messageId,
                                    UUID conversationId,
                                    ChannelType channel,
                                    String body,
                                    String externalMessageId,
                                    String attachments,
                                    String metadata,
                                    LocalDateTime sentAt) {
        MapSqlParameterSource parameters = scope.bind(CrmQueryScope.params(
            "id", messageId,
            "conversationId", conversationId,
            "channel", channel.name(),
            "body", body,
            "externalMessageId", externalMessageId,
            "attachments", attachments,
            "metadata", metadata,
            "sentAt", sentAt));
        try {
            jdbc.update("""
                insert into messages ("id", "organizationId", "projectId", "conversationId", "channel",
                    "direction", "authorType", "body", "attachments", "externalMessageId",
                    "deliveryStatus", "metadata", "sentAt", "createdAt")
                values (:id, :__fe_crm_organization_id, :__fe_crm_project_id, :conversationId,
                    cast(:channel as "ChannelType"), cast('INBOUND' as "MessageDirection"),
                    cast('CUSTOMER' as "MessageAuthorType"), :body, CAST(:attachments AS jsonb),
                    :externalMessageId, cast('DELIVERED' as "MessageDeliveryStatus"),
                    CAST(:metadata AS jsonb), :sentAt, :sentAt)
                """, parameters);
        } catch (DuplicateKeyException e) {
            throw new DuplicateInboundMessageException(externalMessageId);
        }
        return new MessageRow(messageId, scope.organizationId(), scope.projectId(), conversationId,
            channel.name(), MessageDirection.INBOUND.name(), MessageAuthorType.CUSTOMER.name(),
            null, body, MessageDeliveryStatus.DELIVERED.name(), null, externalMessageId, metadata,
            sentAt, sentAt, null);
    }

    /**
     * An AI reply. Separate from {@link #insertOutbound} because the author type differs, and
     * a guard here makes "who produced this" impossible to get wrong by accident.
     */
    public MessageRow insertAiOutbound(CrmQueryScope scope,
                                       UUID messageId,
                                       UUID conversationId,
                                       ChannelType channel,
                                       String body,
                                       String externalMessageId,
                                       String metadata,
                                       LocalDateTime sentAt) {
        MapSqlParameterSource parameters = scope.bind(CrmQueryScope.params(
            "id", messageId,
            "conversationId", conversationId,
            "channel", channel.name(),
            "body", body,
            "externalMessageId", externalMessageId,
            "metadata", metadata,
            "sentAt", sentAt));
        jdbc.update("""
            insert into messages ("id", "organizationId", "projectId", "conversationId", "channel",
                "direction", "authorType", "body", "externalMessageId", "deliveryStatus",
                "metadata", "sentAt", "createdAt")
            values (:id, :__fe_crm_organization_id, :__fe_crm_project_id, :conversationId,
                cast(:channel as "ChannelType"), cast('OUTBOUND' as "MessageDirection"),
                cast('AI' as "MessageAuthorType"), :body, :externalMessageId,
                cast('SENT' as "MessageDeliveryStatus"), CAST(:metadata AS jsonb), :sentAt, :sentAt)
            """, parameters);
        return new MessageRow(messageId, scope.organizationId(), scope.projectId(), conversationId,
            channel.name(), MessageDirection.OUTBOUND.name(), MessageAuthorType.AI.name(), null,
            body, MessageDeliveryStatus.SENT.name(), null, externalMessageId, metadata, sentAt,
            sentAt, null);
    }

    private static String metadata(String idempotencyKey) {
        return idempotencyKey == null || idempotencyKey.isBlank()
            ? "{}"
            : "{\"idempotencyKey\":\"" + jsonEscape(idempotencyKey) + "\"}";
    }

    /** Minimal JSON string escaping; the key is a client-supplied header value. */
    public static String jsonEscape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /** One stored message. */
    public record MessageRow(UUID id,
                             UUID organizationId,
                             UUID projectId,
                             UUID conversationId,
                             String channel,
                             String direction,
                             String authorType,
                             UUID authorUserId,
                             String body,
                             String deliveryStatus,
                             String failureReason,
                             String externalMessageId,
                             String metadata,
                             LocalDateTime sentAt,
                             LocalDateTime createdAt,
                             String authorFullName) {
    }

    /** The provider redelivered a message that is already stored; no second copy is written. */
    public static class DuplicateInboundMessageException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String externalMessageId;

        public DuplicateInboundMessageException(String externalMessageId) {
            super("Inbound message already stored for this project/channel");
            this.externalMessageId = externalMessageId;
        }

        public String externalMessageId() {
            return externalMessageId;
        }
    }
}