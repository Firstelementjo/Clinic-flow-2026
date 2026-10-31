package com.firstelement.notifications.persistence;

import com.firstelement.security.TenantContext;
import com.firstelement.security.query.TenantQueryGuard;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code notifications}: the durable record.
 *
 * <h2>Why this table is the record and the push is not</h2>
 *
 * <p>The legacy service stated the rule: "An agent who was offline when a conversation was
 * assigned still has to find it at the start of their shift, and a realtime channel that drops a
 * frame must not lose the fact — so the row is the record and the push is an optimization."
 * PROJECT_REQUIREMENTS FR-1's product rule agrees: realtime loss must recover through API resync.
 *
 * <h2>Every read is scoped to the user, not to the tenant</h2>
 *
 * <p>A notification belongs to one person. The predicate therefore carries
 * {@code organizationId} <em>and</em> {@code userId}, so an admin cannot read another employee's
 * notifications by naming an id, and an employee cannot read a notification for a project they lost
 * access to. This mirrors {@code NotificationsService.list}, which scoped by
 * {@code userId + organizationId} in the legacy runtime too.
 */
@Repository
public class NotificationRepository {

    /** Valid {@code NotificationType} values in the baseline. */
    public static final String CONVERSATION_ASSIGNED = "CONVERSATION_ASSIGNED";
    public static final String CONVERSATION_ESCALATED = "CONVERSATION_ESCALATED";
    public static final String CONVERSATION_UNASSIGNED_QUEUE = "CONVERSATION_UNASSIGNED_QUEUE";
    public static final String MENTION = "MENTION";
    public static final String SYSTEM = "SYSTEM";

    private static final String INSERT = """
        insert into notifications (
                "id", "organizationId", "projectId", "userId", "type", "title", "body",
                "resourceType", "resourceId", "createdAt")
        values (
                :id, :org, :project, :user, cast(:type as "NotificationType"), :title, :body,
                :resourceType, :resourceId, (now() AT TIME ZONE 'UTC'))
        returning "id", "createdAt"
        """;

    private static final String SELECT = """
        select "id", "organizationId", "projectId", "userId", "type"::text as "type", "title",
               "body", "resourceType", "resourceId", "readAt", "createdAt"
          from notifications
        """;

    private static final String MARK_READ = """
        update notifications
           set "readAt" = (now() AT TIME ZONE 'UTC')
         where "id" = :id
           and "organizationId" = :org
           and "userId" = :user
           and "readAt" is null
        """;

    private static final String MARK_ALL_READ = """
        update notifications
           set "readAt" = (now() AT TIME ZONE 'UTC')
         where "organizationId" = :org
           and "userId" = :user
           and "readAt" is null
        """;

    private static final String UNREAD_COUNT = """
        select count(*)
          from notifications
         where "organizationId" = :org
           and "userId" = :user
           and "readAt" is null
        """;

    private final NamedParameterJdbcTemplate jdbc;
    private final TenantQueryGuard guard;

    public NotificationRepository(NamedParameterJdbcTemplate jdbc, TenantQueryGuard guard) {
        this.jdbc = jdbc;
        this.guard = guard;
    }

    /**
     * Writes one notification.
     *
     * @return the row's id and creation time; the pair the realtime frame carries
     */
    public NotificationRow insert(TenantContext context, UUID projectId, UUID userId, String type,
                                  String title, String body, String resourceType,
                                  UUID resourceId) {
        guard.tenantScope(context);
        UUID id = UUID.randomUUID();
        return jdbc.queryForObject(INSERT, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("org", context.organizationId())
            .addValue("project", projectId)
            .addValue("user", userId)
            .addValue("type", type)
            .addValue("title", title)
            .addValue("body", body)
            .addValue("resourceType", resourceType)
            .addValue("resourceId", resourceId),
            (rs, rowNum) -> new NotificationRow(rs.getObject("id", UUID.class),
                rs.getObject("createdAt", LocalDateTime.class)));
    }

    /** The caller's notifications, newest first. */
    public List<NotificationView> list(TenantContext context, boolean onlyUnread, int limit) {
        guard.tenantScope(context);
        UUID userId = context.userId();
        if (userId == null) {
            return List.of();
        }
        String sql = SELECT
            + " where \"organizationId\" = :org and \"userId\" = :user"
            + (onlyUnread ? " and \"readAt\" is null" : "")
            + " order by \"createdAt\" desc limit " + Math.max(1, limit);
        return jdbc.query(sql, new MapSqlParameterSource()
            .addValue("org", context.organizationId())
            .addValue("user", userId),
            (rs, rowNum) -> new NotificationView(
                rs.getObject("id", UUID.class),
                rs.getObject("organizationId", UUID.class),
                rs.getObject("projectId", UUID.class),
                rs.getString("type"),
                rs.getString("title"),
                rs.getString("body"),
                rs.getString("resourceType"),
                rs.getObject("resourceId", UUID.class),
                rs.getObject("readAt", LocalDateTime.class),
                rs.getObject("createdAt", LocalDateTime.class)));
    }

    /**
     * Marks one notification read.
     *
     * <p>Scoped by user, so a notification id is not a capability — the legacy comment's words.
     *
     * @return true when this call changed a row
     */
    public boolean markRead(TenantContext context, UUID notificationId) {
        guard.tenantScope(context);
        UUID userId = context.userId();
        if (userId == null) {
            return false;
        }
        return jdbc.update(MARK_READ, new MapSqlParameterSource()
            .addValue("id", notificationId)
            .addValue("org", context.organizationId())
            .addValue("user", userId)) == 1;
    }

    public int markAllRead(TenantContext context) {
        guard.tenantScope(context);
        UUID userId = context.userId();
        if (userId == null) {
            return 0;
        }
        return jdbc.update(MARK_ALL_READ, new MapSqlParameterSource()
            .addValue("org", context.organizationId())
            .addValue("user", userId));
    }

    public int unreadCount(TenantContext context) {
        guard.tenantScope(context);
        UUID userId = context.userId();
        if (userId == null) {
            return 0;
        }
        Integer count = jdbc.queryForObject(UNREAD_COUNT, new MapSqlParameterSource()
            .addValue("org", context.organizationId())
            .addValue("user", userId), Integer.class);
        return count == null ? 0 : count;
    }

    /** One written notification. */
    public record NotificationRow(UUID id, LocalDateTime createdAt) {
    }

    /**
     * One notification as the client sees it.
     *
     * @param readAt null while unread; "no data" and "zero" stay distinguishable (FR-7.6's
     *               discipline applied to a boolean state)
     */
    public record NotificationView(UUID id,
                                   UUID organizationId,
                                   UUID projectId,
                                   String type,
                                   String title,
                                   String body,
                                   String resourceType,
                                   UUID resourceId,
                                   LocalDateTime readAt,
                                   LocalDateTime createdAt) {

        public boolean read() {
            return readAt != null;
        }
    }
}