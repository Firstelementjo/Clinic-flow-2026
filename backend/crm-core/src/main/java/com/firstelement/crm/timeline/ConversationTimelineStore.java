package com.firstelement.crm.timeline;

import com.firstelement.crm.domain.ConversationEventType;
import com.firstelement.crm.domain.MessageAuthorType;
import com.firstelement.crm.persistence.JsonCodec;
import com.firstelement.crm.query.CrmQueryScope;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Append-only access to {@code conversation_events}.
 *
 * <h2>There is deliberately no update and no delete</h2>
 *
 * <p>A conversation timeline is the record of what happened to a customer's thread. If an
 * entry could be edited, "what did this conversation do" would become "what does this row
 * say now", and an audit of the product's behaviour would be worthless. The only methods
 * here are {@link #append} and reads. Phase 6's escalation and scoring work and Phase 5's
 * worker both feed it through {@link #append}, which is why it lives in {@code crm-core}
 * rather than in the inbox module.
 *
 * <h2>The legacy failure policy is preserved</h2>
 *
 * <p>{@code TimelineRecordingPublisher} caught and logged write failures so "a timeline write
 * must not roll back the message it describes". {@link #append} does not throw for that
 * reason either: a timeline gap is recoverable from the messages and the audit stream, while
 * losing the customer's message is not.
 *
 * <h2>Tenant scope</h2>
 *
 * <p>Writes bind {@code organizationId}/{@code projectId} from the event, which came from the
 * scoped read that produced the change — never from the request. Reads go through
 * {@link CrmQueryScope} like every other CRM statement.
 */
@Component
public class ConversationTimelineStore {

    private static final Logger log = LoggerFactory.getLogger(ConversationTimelineStore.class);

    private static final String INSERT = """
        insert into conversation_events ("id", "organizationId", "projectId", "conversationId",
            "type", "actorType", "actorUserId", "payload", "occurredAt")
        values (:id, :org, :project, :conversation, cast(:type as "ConversationEventType"),
            cast(:actorType as "MessageAuthorType"), :actorUserId, CAST(:payload AS jsonb), :occurredAt)
        """;

    private static final RowMapper<TimelineRow> ROW = (rs, rowNum) -> new TimelineRow(
        rs.getObject("id", UUID.class),
        rs.getObject("conversationId", UUID.class),
        ConversationEventType.fromValue(rs.getString("type")),
        MessageAuthorType.fromValue(rs.getString("actorType")),
        rs.getObject("actorUserId", UUID.class),
        rs.getString("payload"),
        rs.getObject("occurredAt", LocalDateTime.class));

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonCodec json;

    public ConversationTimelineStore(NamedParameterJdbcTemplate jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Maps and appends one domain event.
     *
     * @return {@code true} when a row was written; {@code false} when the event has no
     *         conversation timeline representation (the legacy {@code null} branch)
     */
    public boolean append(CrmDomainEvent event) {
        TimelineEventMapper.TimelineEntry entry = TimelineEventMapper.map(event);
        if (entry == null) {
            return false;
        }
        appendEntry(event.organizationId(), event.projectId(), entry, event.actorUserId(),
            event.occurredAt());
        return true;
    }

    /** Appends an already-mapped entry. Used by {@link #append} and by Phase 5/6 producers. */
    public void appendEntry(UUID organizationId,
                            UUID projectId,
                            TimelineEventMapper.TimelineEntry entry,
                            UUID actorUserId,
                            LocalDateTime occurredAt) {
        try {
            jdbc.update(INSERT, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("org", organizationId)
                .addValue("project", projectId)
                .addValue("conversation", entry.conversationId())
                .addValue("type", entry.type().name())
                .addValue("actorType", entry.actorType().name())
                .addValue("actorUserId", actorUserId)
                .addValue("payload", json.write(entry.payload()))
                .addValue("occurredAt", occurredAt));
        } catch (RuntimeException e) {
            // Never propagate: see the class note. The message itself is not rolled back.
            log.error("Failed to append timeline entry conversation={} type={}",
                entry.conversationId(), entry.type(), e);
        }
    }

    /**
     * The thread's history, oldest first.
     *
     * <p>Ordered by {@code occurredAt, id} so two entries recorded in the same millisecond
     * still have a deterministic order — the legacy query ordered by {@code occurredAt} alone.
     */
    public List<TimelineRow> history(CrmQueryScope scope, UUID conversationId, int limit) {
        CrmQueryScope.Statement statement = scope.select(
            """
            select e."id", e."conversationId", e."type", e."actorType", e."actorUserId",
                   e."payload", e."occurredAt"
            from conversation_events e
            """,
            "e",
            "e.\"conversationId\" = :conversationId",
            CrmQueryScope.params("conversationId", conversationId))
            .orderedBy("e.\"occurredAt\" asc, e.\"id\" asc", limit);
        return jdbc.query(statement.sql(), statement.parameters(), ROW);
    }

    /** A raw timeline row. */
    public record TimelineRow(UUID id,
                              UUID conversationId,
                              ConversationEventType type,
                              MessageAuthorType actorType,
                              UUID actorUserId,
                              String payload,
                              LocalDateTime occurredAt) {
    }
}