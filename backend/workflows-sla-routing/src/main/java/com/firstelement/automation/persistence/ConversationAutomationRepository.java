package com.firstelement.automation.persistence;

import com.firstelement.security.query.ScopedWhere;
import com.firstelement.security.query.TenantScope;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The conversation rows this module needs: the mode transition, the staged AI reply, and the
 * timestamps the SLA clocks read.
 *
 * <h2>Why the mode write lives here and not in {@code ConversationService}</h2>
 *
 * <p>The <em>human</em> path goes through {@code ConversationService#changeMode}, so there is exactly
 * one definition of the guarded transition and its permission. This repository holds the
 * <b>escalation</b> path's conditional write, which is the same statement with a different trigger:
 * an escalation and a manual takeover race each other, and both must lose on {@code modeVersion}.
 * Legacy rationale, kept: "The mode transition is therefore a conditional update guarded by the
 * version it was read at — whoever loses is told they lost rather than silently overwriting the
 * winner."
 *
 * <h2>Why {@link #lockForAiSend} exists and takes the row lock FIRST</h2>
 *
 * <p>This is the phase-6 gate. The AI send is two-phase — stage the message, then confirm it — and
 * both halves must serialise on the <b>same row, in the same order</b> as the takeover:
 *
 * <ol>
 *   <li>human takeover: {@code UPDATE conversations ... WHERE mode='AI' AND "modeVersion"=:expected}
 *       takes the conversation row lock, then {@code UPDATE workflow_runs SET "abortRequestedAt"}.</li>
 *   <li>AI send: {@code SELECT ... FROM conversations WHERE id AND mode='AI' FOR UPDATE} takes the
 *       same lock first, then reads the run's abort state.</li>
 * </ol>
 *
 * <p>Taking the conversation lock before the run lock in <em>both</em> paths is what prevents a
 * deadlock and makes the ordering meaningful. With that order, a takeover that commits first makes
 * the send's {@code mode='AI'} test fail, and a send that got the lock first is legitimately
 * "already in flight" — and is then cancelled at confirm time by the abort request, which is why
 * confirmation re-reads both.
 */
@Repository
public class ConversationAutomationRepository {

    /** The fields the mode guard and the clocks read. */
    public record ConversationState(UUID id,
                                    UUID organizationId,
                                    UUID projectId,
                                    UUID customerId,
                                    String channel,
                                    String status,
                                    String mode,
                                    String priority,
                                    UUID assignedEmployeeId,
                                    int modeVersion,
                                    LocalDateTime createdAt,
                                    LocalDateTime lastMessageAt,
                                    LocalDateTime firstResponseAt,
                                    LocalDateTime resolvedAt) {
    }

    /** One message line for the handover summary. */
    public record MessageLine(UUID id, String authorType, String body, LocalDateTime sentAt) {
    }

    private static final String UTC_NOW = "(now() AT TIME ZONE 'UTC')";

    private final NamedParameterJdbcTemplate jdbc;

    public ConversationAutomationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SELECT = """
        select c."id", c."organizationId", c."projectId", c."customerId", c."channel", c."status",
               c."mode", c."priority", c."assignedEmployeeId", c."modeVersion", c."createdAt",
               c."lastMessageAt", c."firstResponseAt", c."resolvedAt"
          from conversations c
        """;

    public Optional<ConversationState> find(TenantScope scope, UUID conversationId) {
        String sql = SELECT + " where c.\"organizationId\" = :" + ScopedWhere.ORGANIZATION_PARAM
            + " and c.\"projectId\" = :" + ScopedWhere.PROJECT_PARAM
            + " and c.\"id\" = :id";
        return jdbc.query(sql, bind(scope, Map.of("id", conversationId)), (rs, rowNum) ->
            new ConversationState(
                rs.getObject("id", UUID.class),
                rs.getObject("organizationId", UUID.class),
                rs.getObject("projectId", UUID.class),
                rs.getObject("customerId", UUID.class),
                rs.getString("channel"),
                rs.getString("status"),
                rs.getString("mode"),
                rs.getString("priority"),
                rs.getObject("assignedEmployeeId", UUID.class),
                rs.getInt("modeVersion"),
                rs.getObject("createdAt", LocalDateTime.class),
                rs.getObject("lastMessageAt", LocalDateTime.class),
                rs.getObject("firstResponseAt", LocalDateTime.class),
                rs.getObject("resolvedAt", LocalDateTime.class))).stream().findFirst();
    }

    /**
     * The atomic AI to HUMAN transition.
     *
     * <p>Conditional on both the current mode and the version read a moment ago, exactly as the
     * legacy {@code EscalationEngine} defined it. 0 rows means somebody else won the race and the
     * caller must report that rather than continue.
     *
     * @param newPriority the legacy rule: {@code URGENT} at urgency &ge; 70, otherwise {@code HIGH}
     */
    public int transitionToHuman(TenantScope scope, UUID conversationId, int expectedModeVersion,
                                 String newPriority) {
        String sql = """
            update conversations c
               set "mode" = cast('HUMAN' as "ConversationMode"),
                   "modeVersion" = c."modeVersion" + 1,
                   "status" = cast('PENDING' as "ConversationStatus"),
                   "priority" = cast(:newPriority as "ConversationPriority"),
                   "updatedAt" = %s
             where c."organizationId" = :%s
               and c."projectId" = :%s
               and c."id" = :id
               and c."mode" = cast('AI' as "ConversationMode")
               and c."modeVersion" = :expectedModeVersion
            """.formatted(UTC_NOW, ScopedWhere.ORGANIZATION_PARAM, ScopedWhere.PROJECT_PARAM);
        return jdbc.update(sql, bind(scope, Map.of(
            "id", conversationId, "expectedModeVersion", expectedModeVersion, "newPriority", newPriority)));
    }

    /**
     * Takes the conversation row lock and reports whether it is still in AI mode.
     *
     * <p>{@code FOR UPDATE} is the serialisation point, not a formality. See the class comment for the
     * lock order both sides must respect.
     *
     * @return the mode observed under the lock; empty when the conversation is not in this tenant
     */
    public Optional<String> lockForAiSend(TenantScope scope, UUID conversationId) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", conversationId);
        MapSqlParameterSource parameters = bind(scope, values);
        return jdbc.query("""
            select c."mode"
              from conversations c
             where c."organizationId" = :__fe_tenant_organization_id
               and c."projectId" = :__fe_tenant_project_id
               and c."id" = :id
             for update
            """, parameters, (rs, rowNum) -> rs.getString("mode")).stream().findFirst();
    }

    /**
     * Stages an AI reply as a {@code PENDING} outbound message.
     *
     * <p>Persist first, send second: the row exists before any provider sees it, and Phase 5b's
     * dispatcher moves it to {@code SENT} or {@code FAILED}. This is the "in flight" state the human
     * takeover aborts — the message exists, nothing has been sent, and
     * {@link #cancelStagedMessage} can make that permanent.
     */
    public UUID stageOutbound(TenantScope scope, UUID messageId, UUID conversationId, String channel,
                              String body, LocalDateTime sentAt) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", messageId);
        values.put("conversationId", conversationId);
        values.put("channel", channel);
        values.put("body", body);
        values.put("sentAt", sentAt);
        // Marks the row as staged by automation, so an operator reading the transcript can tell a
        // pending AI reply from a pending employee reply, and so a cancellation is attributable.
        values.put("metadata", "{\"origin\":\"AI\",\"stagedBy\":\"workflow\",\"pending\":\"true\"}");
        jdbc.update("""
            insert into messages
                ("id", "organizationId", "projectId", "conversationId", "channel", "direction",
                 "authorType", "body", "deliveryStatus", "metadata", "sentAt", "createdAt")
            values (:id, :__fe_tenant_organization_id, :__fe_tenant_project_id, :conversationId,
                    cast(:channel as "ChannelType"), cast('OUTBOUND' as "MessageDirection"),
                    cast('AI' as "MessageAuthorType"), :body,
                    cast('PENDING' as "MessageDeliveryStatus"), cast(:metadata as jsonb), :sentAt, :sentAt)
            """, bind(scope, values));
        return messageId;
    }

    /** Cancels a staged AI message so the dispatcher can never send it. */
    public int cancelStagedMessage(TenantScope scope, UUID messageId, String reason) {
        String sql = """
            update messages m
               set "deliveryStatus" = cast('FAILED' as "MessageDeliveryStatus"),
                   "failureReason" = :reason,
                   "metadata" = m."metadata" || cast(:metadata as jsonb)
             where m."organizationId" = :%s
               and m."projectId" = :%s
               and m."id" = :id
               and m."authorType" = cast('AI' as "MessageAuthorType")
               and m."deliveryStatus" = cast('PENDING' as "MessageDeliveryStatus")
            """.formatted(ScopedWhere.ORGANIZATION_PARAM, ScopedWhere.PROJECT_PARAM);
        return jdbc.update(sql, bind(scope, Map.of("id", messageId, "reason", reason,
            "metadata", "{\"cancelled\":\"HUMAN_TAKEOVER\"}")));
    }

    /**
     * Cancels every staged AI message on a conversation.
     *
     * <p>Keyed by the <b>conversation</b>, not by a run, and that is the whole point: a staged message
     * has no column naming the run that produced it, so cancelling "the message for run X" cannot be
     * expressed as a statement. Cancelling per run-id would silently update zero rows and leave the
     * reply sendable — which is exactly the failure this module's phase gate exists to prevent.
     *
     * <p>Restricted to {@code authorType = 'AI'} and {@code deliveryStatus = 'PENDING'} so it can
     * never touch a reply a human sent or a message already delivered.
     *
     * @return the number of staged messages cancelled
     */
    public int cancelStagedAiMessagesForConversation(TenantScope scope, UUID conversationId,
                                                     String reason) {
        String sql = """
            update messages m
               set "deliveryStatus" = cast('FAILED' as "MessageDeliveryStatus"),
                   "failureReason" = :reason,
                   "metadata" = m."metadata" || cast(:metadata as jsonb)
             where m."organizationId" = :%s
               and m."projectId" = :%s
               and m."conversationId" = :conversationId
               and m."authorType" = cast('AI' as "MessageAuthorType")
               and m."deliveryStatus" = cast('PENDING' as "MessageDeliveryStatus")
            """.formatted(ScopedWhere.ORGANIZATION_PARAM, ScopedWhere.PROJECT_PARAM);
        return jdbc.update(sql, bind(scope, Map.of("conversationId", conversationId,
            "reason", reason, "metadata", "{\"cancelled\":\"HUMAN_TAKEOVER\"}")));
    }

    /** Whether a staged message is still sendable (i.e. nobody cancelled it). */
    public boolean isStillPending(TenantScope scope, UUID messageId) {
        String sql = """
            select count(*)
              from messages m
             where m."organizationId" = :%s
               and m."projectId" = :%s
               and m."id" = :id
               and m."deliveryStatus" = cast('PENDING' as "MessageDeliveryStatus")
            """.formatted(ScopedWhere.ORGANIZATION_PARAM, ScopedWhere.PROJECT_PARAM);
        Integer count = jdbc.queryForObject(sql, bind(scope, Map.of("id", messageId)), Integer.class);
        return count != null && count > 0;
    }

    /**
     * Stamps {@code firstResponseAt} on the first reply that lands.
     *
     * <p>Conditional on being null, so two agents answering at once cannot move it and the
     * first-response SLA clock keeps its original anchor.
     */
    public int stampFirstResponse(TenantScope scope, UUID conversationId, LocalDateTime at) {
        String sql = """
            update conversations c
               set "firstResponseAt" = :at, "updatedAt" = :at
             where c."organizationId" = :%s
               and c."projectId" = :%s
               and c."id" = :id
               and c."firstResponseAt" is null
            """.formatted(ScopedWhere.ORGANIZATION_PARAM, ScopedWhere.PROJECT_PARAM);
        return jdbc.update(sql, bind(scope, Map.of("id", conversationId, "at", at)));
    }

    /** The customer's own messages, oldest first, for scoring and for the summary. */
    public List<MessageLine> customerMessages(TenantScope scope, UUID conversationId, int limit) {
        String sql = """
            select m."id", m."authorType", m."body", m."sentAt"
              from messages m
             where m."organizationId" = :%s
               and m."projectId" = :%s
               and m."conversationId" = :conversationId
               and m."authorType" = cast('CUSTOMER' as "MessageAuthorType")
             order by m."sentAt", m."id"
             limit :limit
            """.formatted(ScopedWhere.ORGANIZATION_PARAM, ScopedWhere.PROJECT_PARAM);
        return jdbc.query(sql, bind(scope, Map.of("conversationId", conversationId, "limit", limit)),
            (rs, rowNum) -> new MessageLine(rs.getObject("id", UUID.class),
                rs.getString("authorType"), rs.getString("body"),
                rs.getObject("sentAt", LocalDateTime.class)));
    }

    /**
     * The transcript for the handover summary, newest {@code limit} then oldest-first.
     *
     * <p>Legacy {@code buildHandoverSummary} read the last 12 messages and reversed them; the same
     * shape is used here, with the reversal expressed in the ORDER BY so the read is one statement.
     */
    public List<MessageLine> transcriptForSummary(TenantScope scope, UUID conversationId, int limit) {
        String sql = """
            select t."id", t."authorType", t."body", t."sentAt"
              from (select m."id", m."authorType", m."body", m."sentAt",
                           row_number() over (order by m."sentAt" desc, m."id" desc) as rn
                      from messages m
                     where m."organizationId" = :%s
                       and m."projectId" = :%s
                       and m."conversationId" = :conversationId) t
             where t.rn <= :limit
             order by t.rn desc
            """.formatted(ScopedWhere.ORGANIZATION_PARAM, ScopedWhere.PROJECT_PARAM);
        return jdbc.query(sql, bind(scope, Map.of("conversationId", conversationId, "limit", limit)),
            (rs, rowNum) -> new MessageLine(rs.getObject("id", UUID.class),
                rs.getString("authorType"), rs.getString("body"),
                rs.getObject("sentAt", LocalDateTime.class)));
    }

    /** The last outbound message's send time, or {@code null} when nothing has been sent. */
    public java.util.Optional<LocalDateTime> lastOutboundAt(TenantScope scope, UUID conversationId) {
        String sql = """
            select max(m."sentAt")
              from messages m
             where m."organizationId" = :%s
               and m."projectId" = :%s
               and m."conversationId" = :conversationId
               and m."direction" = cast('OUTBOUND' as "MessageDirection")
            """.formatted(ScopedWhere.ORGANIZATION_PARAM, ScopedWhere.PROJECT_PARAM);
        LocalDateTime last = jdbc.queryForObject(sql, bind(scope, Map.of("conversationId", conversationId)),
            LocalDateTime.class);
        return Optional.ofNullable(last);
    }

    /** How many AI replies have landed without the thread resolving: the "repeated AI" signal. */
    public int countAiMessages(TenantScope scope, UUID conversationId) {
        String sql = """
            select count(*)
              from messages m
             where m."organizationId" = :%s
               and m."projectId" = :%s
               and m."conversationId" = :conversationId
               and m."authorType" = cast('AI' as "MessageAuthorType")
            """.formatted(ScopedWhere.ORGANIZATION_PARAM, ScopedWhere.PROJECT_PARAM);
        Integer count = jdbc.queryForObject(sql, bind(scope, Map.of("conversationId", conversationId)),
            Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * Binds caller values together with the scope's tenant parameters.
     *
     * <p>{@link ScopedWhere#bindNamed} is the only sanctioned way: it writes the tenant values last
     * and refuses a caller parameter of the reserved name, so a filter string cannot smuggle a
     * different tenant into a statement that looks scoped. {@code TenantScope} itself deliberately
     * carries no binding method, so the predicate and the parameters are always produced together.
     */
    private static MapSqlParameterSource bind(TenantScope scope, Map<String, ?> values) {
        return ScopedWhere.of(scope).bindNamed(values);
    }
}
