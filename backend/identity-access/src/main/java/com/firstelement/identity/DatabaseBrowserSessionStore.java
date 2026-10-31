package com.firstelement.identity;

import com.firstelement.security.TenantContext;
import com.firstelement.security.session.BrowserSession;
import com.firstelement.security.UserRole;
import com.firstelement.security.session.BrowserSessionStore;
import com.firstelement.security.session.SessionTokenCodec;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The revocable server-side browser session store, over the legacy {@code sessions}
 * table.
 *
 * <h2>Behaviour carried over from the legacy runtime without change</h2>
 *
 * <ul>
 *   <li>{@code "tokenHash"} stores the lowercase hex SHA-256 and carries a unique
 *       constraint, so only the hash is persisted and the raw token exists once, in the
 *       response that sets the cookie.</li>
 *   <li>Resolve checks {@code "revokedAt"} and {@code "expiresAt"} on <b>every</b>
 *       request, and recomputes the project scope from {@code users} and
 *       {@code project_memberships}. That is what makes revocation immediate.</li>
 *   <li>An inactive user or an inactive organization kills the session even though the
 *       row is still valid.</li>
 * </ul>
 *
 * <h2>What Phase 3 adds</h2>
 *
 * <ul>
 *   <li>The token hash is looked up as a plain equality on a unique index. Nothing about
 *       the lookup is timing-sensitive (a hash of an unguessable token cannot be guessed),
 *       but {@link SessionTokenCodec#constantTimeEquals} is still available for callers
 *       that compare secrets.</li>
 *   <li>Revocation is {@code update ... where "revokedAt" is null} and returns the row
 *       count, so "revoked" is proven rather than assumed.</li>
 * </ul>
 *
 * <h2>Never logged, never audited</h2>
 *
 * <p>No method here accepts or emits the raw token in a log line, an exception message, or
 * an audit event. {@code SESSION_ISSUED} and {@code SESSION_REVOKED} carry the session
 * <em>id</em>.
 */
@Repository
public class DatabaseBrowserSessionStore implements BrowserSessionStore {

    private static final Logger log = LoggerFactory.getLogger(DatabaseBrowserSessionStore.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final UserRepository users;
    private final ProjectMembershipRepository memberships;

    public DatabaseBrowserSessionStore(NamedParameterJdbcTemplate jdbc,
                                       UserRepository users,
                                       ProjectMembershipRepository memberships) {
        this.jdbc = jdbc;
        this.users = users;
        this.memberships = memberships;
    }

    @Override
    public IssuedSession issue(UUID userId, String userAgent, String ipAddress, int ttlHours) {
        UUID sessionId = UUID.randomUUID();
        String rawToken = SessionTokenCodec.generate();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plus(ttlHours, ChronoUnit.HOURS);

        int inserted = jdbc.update("""
            insert into sessions ("id", "organizationId", "userId", "tokenHash", "userAgent",
                                 "ipAddress", "expiresAt", "createdAt", "lastSeenAt")
            select :id, u."organizationId", u."id", :hash, :agent, :ip, :expires, :now, :now
              from users u
             where u."id" = :user
            """,
            new MapSqlParameterSource()
                .addValue("id", sessionId)
                .addValue("hash", SessionTokenCodec.hash(rawToken))
                .addValue("agent", truncate(userAgent, 500))
                .addValue("ip", ipAddress)
                .addValue("expires", expiresAt)
                .addValue("now", now)
                .addValue("user", userId));

        if (inserted != 1) {
            // The user disappeared between the password check and the insert. Returning
            // without a token keeps the caller on the invalid-credentials path.
            log.warn("Session insert matched {} rows for a user that was just authenticated", inserted);
            return null;
        }
        return new IssuedSession(rawToken, sessionId, expiresAt);
    }

    @Override
    public Optional<BrowserSession> resolve(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        String hash = SessionTokenCodec.hash(rawToken);

        List<SessionRow> rows = jdbc.query("""
            select s."id", s."organizationId", s."userId", s."userAgent", s."ipAddress",
                   s."expiresAt", s."createdAt", s."lastSeenAt",
                   u."isActive" as "userActive", u."role" as "userRole",
                   coalesce(u."is_platform_admin", false) as "isPlatformAdmin",
                   o."isActive" as "organizationActive"
              from sessions s
              join users u on u."id" = s."userId" and u."organizationId" = s."organizationId"
              join organizations o on o."id" = s."organizationId"
             where s."tokenHash" = :hash
               and s."revokedAt" is null
               and s."expiresAt" > :now
            """,
            new MapSqlParameterSource().addValue("hash", hash).addValue("now", LocalDateTime.now()),
            (rs, rowNum) -> new SessionRow(
                rs.getObject("id", UUID.class),
                rs.getObject("organizationId", UUID.class),
                rs.getObject("userId", UUID.class),
                rs.getString("userAgent"),
                rs.getString("ipAddress"),
                rs.getObject("expiresAt", LocalDateTime.class),
                rs.getObject("createdAt", LocalDateTime.class),
                rs.getObject("lastSeenAt", LocalDateTime.class),
                rs.getBoolean("userActive"),
                UserRole.fromValue(rs.getString("userRole")),
                rs.getBoolean("isPlatformAdmin"),
                rs.getBoolean("organizationActive")));

        if (rows.size() != 1) {
            // 0 covers unknown, revoked, and expired. All three are the same answer.
            return Optional.empty();
        }
        SessionRow row = rows.get(0);
        if (!row.userActive() || !row.organizationActive()) {
            return Optional.empty();
        }

        List<UUID> projectIds = memberships.resolveProjectIds(
            row.organizationId(), row.userId(), row.userRole());

        TenantContext context = TenantContext.forUser(row.id(), row.userId(), row.organizationId(),
            row.userRole(), projectIds, row.isPlatformAdmin());

        return Optional.of(new BrowserSession(row.id(), context, row.userAgent(), row.ipAddress(),
            row.createdAt(), row.lastSeenAt(), row.expiresAt()));
    }

    @Override
    public void touch(UUID sessionId) {
        jdbc.update("update sessions set \"lastSeenAt\" = :now where \"id\" = :id and \"revokedAt\" is null",
            new MapSqlParameterSource().addValue("id", sessionId).addValue("now", LocalDateTime.now()));
    }

    @Override
    public int revoke(UUID sessionId, UUID organizationId) {
        if (sessionId == null || organizationId == null) {
            return 0;
        }
        // The organization predicate is load-bearing, not decorative. Without it this
        // statement matched any session by id in any tenant, so an administrator of one
        // organization could revoke another organization's sessions -- and the returned
        // count told them whether the id existed, which is a cross-tenant existence
        // oracle. With it, a foreign id matches no row and returns 0, exactly as an
        // invented id does.
        return jdbc.update(
            "update sessions set \"revokedAt\" = :now"
                + " where \"id\" = :id and \"organizationId\" = :organizationId and \"revokedAt\" is null",
            new MapSqlParameterSource()
                .addValue("id", sessionId)
                .addValue("organizationId", organizationId)
                .addValue("now", LocalDateTime.now()));
    }

    @Override
    public int revokeAllForUser(UUID userId) {
        if (userId == null) {
            return 0;
        }
        return jdbc.update(
            "update sessions set \"revokedAt\" = :now where \"userId\" = :user and \"revokedAt\" is null",
            new MapSqlParameterSource().addValue("user", userId).addValue("now", LocalDateTime.now()));
    }

    /** The Admin session list for one user, already scoped by the caller. */
    public List<SessionSummary> listForUser(UUID userId) {
        return jdbc.query("""
            select "id", "organizationId", "userAgent", "ipAddress", "expiresAt", "createdAt", "lastSeenAt"
              from sessions
             where "userId" = :user and "revokedAt" is null
             order by "lastSeenAt" desc
            """,
            new MapSqlParameterSource("user", userId),
            (rs, rowNum) -> new SessionSummary(
                rs.getObject("id", UUID.class),
                rs.getObject("organizationId", UUID.class),
                rs.getString("userAgent"),
                rs.getString("ipAddress"),
                rs.getObject("expiresAt", LocalDateTime.class),
                rs.getObject("createdAt", LocalDateTime.class),
                rs.getObject("lastSeenAt", LocalDateTime.class)));
    }

    /** Count of live sessions for a user. Used by the deactivation flow's evidence. */
    public int liveSessionCount(UUID userId) {
        Integer count = jdbc.queryForObject(
            "select count(*) from sessions where \"userId\" = :user and \"revokedAt\" is null",
            new MapSqlParameterSource("user", userId), Integer.class);
        return count == null ? 0 : count;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private record SessionRow(UUID id, UUID organizationId, UUID userId, String userAgent,
                              String ipAddress, LocalDateTime expiresAt, LocalDateTime createdAt,
                              LocalDateTime lastSeenAt, boolean userActive, UserRole userRole,
                              boolean isPlatformAdmin, boolean organizationActive) {
    }

    /** One live session, safe to show in Admin. Carries no token. */
    public record SessionSummary(UUID sessionId, UUID organizationId, String userAgent,
                                 String ipAddress, LocalDateTime expiresAt,
                                 LocalDateTime createdAt, LocalDateTime lastSeenAt) {
    }
}