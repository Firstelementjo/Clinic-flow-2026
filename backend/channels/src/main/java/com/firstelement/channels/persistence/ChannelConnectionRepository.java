package com.firstelement.channels.persistence;

import com.firstelement.crm.domain.ChannelType;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code channel_connections}: which tenant/project talks to which provider, and with what
 * credential.
 *
 * <h2>A connection id in a webhook URL is not authorization</h2>
 *
 * <p>The legacy controller's comment is the reason this repository is written the way it is: "The
 * connection id in the URL is not authorization. It selects which credentials to verify against;
 * the signature is what proves the caller is the platform." So {@link #findById} is a
 * <em>credential lookup</em>, not an authorization check, and it is only ever reached on the
 * unauthenticated webhook path — always followed by a signature verification and, on failure, a
 * 404 identical to an unknown id.
 *
 * <h2>No credential is ever returned to a caller</h2>
 *
 * <p>{@link Connection#toString()} emits {@code credentials=[REDACTED]}. The decrypting step is
 * the only code that sees the plaintext, and the admin read model uses
 * {@link #describeFor(ChannelType)}-style projections that select no credential column at all.
 *
 * <h2>Read scope</h2>
 *
 * <p>Both statements are keyed by {@code channel_connections.id}, which is a UUID; every projection
 * carries {@code organizationId} and {@code projectId} so the caller can re-check the row's scope
 * against the {@code TenantContext} it resolved for itself.
 */
@Repository
public class ChannelConnectionRepository {

    /**
     * The webhook lookup. Selects {@code credentials} because the signature check needs the secret;
     * the result's {@code toString} never does.
     */
    private static final String FIND_BY_ID = """
        select "id", "organizationId", "projectId", "channel", "displayName", "isEnabled",
               "status"::text as "status", "credentials", "webhookSecret", "lastError",
               "lastInboundAt", "lastOutboundAt"
          from channel_connections
         where "id" = :id
        """;

    /**
     * The enabled connection for a project and channel.
     *
     * <p>Tenant-scoped: a project in another tenant cannot be named here because the predicate
     * carries {@code organizationId} from the resolved context, not from the request.
     */
    private static final String FIND_ENABLED = """
        select "id", "organizationId", "projectId", "channel", "displayName", "isEnabled",
               "status"::text as "status", "credentials", "webhookSecret", "lastError",
               "lastInboundAt", "lastOutboundAt"
          from channel_connections
         where "organizationId" = :org
           and "projectId" = :project
           and "channel" = cast(:channel as "ChannelType")
           and "isEnabled" = true
         order by "createdAt"
         limit 1
        """;

    /**
     * The admin connector panel. Selects no credential column: the operator sees status, capability
     * and last-error, and never the secret (FR-4.7: "write-only through APIs, redacted in logs").
     */
    private static final String DESCRIBE = """
        select "id", "organizationId", "projectId", "channel"::text as "channel", "displayName",
               "isEnabled", "status"::text as "status", "lastError", "lastInboundAt", "lastOutboundAt",
               ("credentials" is not null and "credentials" <> '') as "hasCredentials",
               ("webhookSecret" is not null and "webhookSecret" <> '') as "hasWebhookSecret"
          from channel_connections
         where "organizationId" = :org
         order by "createdAt"
        """;

    /** Records a verified inbound delivery. */
    private static final String MARK_INBOUND = """
        update channel_connections
           set "lastInboundAt" = (now() AT TIME ZONE 'UTC'),
               "updatedAt" = (now() AT TIME ZONE 'UTC')
         where "id" = :id
           and "organizationId" = :org
        """;

    /**
     * Records the last error on the connection so a misconfigured app secret shows up on the
     * channel panel "rather than as silence" (the legacy controller's words).
     *
     * <p>The error text is bounded: it comes from a provider or from this module, and it must not
     * become an unbounded log-shaped column.
     */
    private static final String RECORD_ERROR = """
        update channel_connections
           set "lastError" = :lastError,
               "updatedAt" = (now() AT TIME ZONE 'UTC')
         where "id" = :id
           and "organizationId" = :org
        """;

    /** Clears the error after a successful verification or send. */
    private static final String CLEAR_ERROR = """
        update channel_connections
           set "lastError" = null,
               "lastOutboundAt" = (now() AT TIME ZONE 'UTC'),
               "updatedAt" = (now() AT TIME ZONE 'UTC')
         where "id" = :id
           and "organizationId" = :org
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public ChannelConnectionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The credential lookup for the unauthenticated webhook path.
     *
     * <p><b>Not an authorization check.</b> The caller must verify the signature (or the Telegram
     * secret) and must answer an unknown connection and a bad signature identically.
     */
    public Optional<Connection> findById(UUID connectionId) {
        return jdbc.query(FIND_BY_ID, new MapSqlParameterSource().addValue("id", connectionId),
            (rs, rowNum) -> map(rs)).stream().findFirst();
    }

    /** The enabled connection for one project and channel, under tenant scope. */
    public Optional<Connection> findEnabled(UUID organizationId, UUID projectId, ChannelType channel) {
        return jdbc.query(FIND_ENABLED, new MapSqlParameterSource()
            .addValue("org", organizationId)
            .addValue("project", projectId)
            .addValue("channel", channel.name()),
            (rs, rowNum) -> map(rs)).stream().findFirst();
    }

    /** The admin connector panel for one tenant. Never selects a credential. */
    public java.util.List<ConnectionDescription> describe(UUID organizationId) {
        return jdbc.query(DESCRIBE, new MapSqlParameterSource().addValue("org", organizationId),
            (rs, rowNum) -> new ConnectionDescription(
                rs.getObject("id", UUID.class),
                rs.getObject("organizationId", UUID.class),
                rs.getObject("projectId", UUID.class),
                rs.getString("channel"),
                rs.getString("displayName"),
                rs.getBoolean("isEnabled"),
                rs.getString("status"),
                rs.getString("lastError"),
                rs.getObject("lastInboundAt", LocalDateTime.class),
                rs.getObject("lastOutboundAt", LocalDateTime.class),
                rs.getBoolean("hasCredentials"),
                rs.getBoolean("hasWebhookSecret")));
    }

    public boolean markInbound(UUID organizationId, UUID connectionId) {
        return jdbc.update(MARK_INBOUND, new MapSqlParameterSource()
            .addValue("id", connectionId)
            .addValue("org", organizationId)) == 1;
    }

    /** Records a failure reason on the connection. Bounded and never contains a secret. */
    public boolean recordError(UUID organizationId, UUID connectionId, String reason) {
        return jdbc.update(RECORD_ERROR, new MapSqlParameterSource()
            .addValue("id", connectionId)
            .addValue("org", organizationId)
            .addValue("lastError", bound(reason))) == 1;
    }

    public boolean clearError(UUID organizationId, UUID connectionId) {
        return jdbc.update(CLEAR_ERROR, new MapSqlParameterSource()
            .addValue("id", connectionId)
            .addValue("org", organizationId)) == 1;
    }

    /** Provider errors are truncated; a connection panel is not a log store. */
    private static String bound(String reason) {
        if (reason == null) {
            return null;
        }
        String flattened = reason.replaceAll("[\\r\\n\\t]", " ");
        return flattened.length() <= 300 ? flattened : flattened.substring(0, 300) + "…";
    }

    private static Connection map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Connection(
            rs.getObject("id", UUID.class),
            rs.getObject("organizationId", UUID.class),
            rs.getObject("projectId", UUID.class),
            rs.getString("channel"),
            rs.getString("displayName"),
            rs.getBoolean("isEnabled"),
            rs.getString("status"),
            rs.getString("credentials"),
            rs.getString("webhookSecret"),
            rs.getString("lastError"),
            rs.getObject("lastInboundAt", LocalDateTime.class),
            rs.getObject("lastOutboundAt", LocalDateTime.class));
    }

    /**
     * One connection, with its decrypted-in-place credential.
     *
     * <p>{@code credentials} and {@code webhookSecret} are ciphertext or JSON as stored;
     * {@link #toString()} redacts both so a debug log cannot leak them.
     */
    public record Connection(UUID id,
                             UUID organizationId,
                             UUID projectId,
                             String channel,
                             String displayName,
                             boolean enabled,
                             String status,
                             String credentials,
                             String webhookSecret,
                             String lastError,
                             LocalDateTime lastInboundAt,
                             LocalDateTime lastOutboundAt) {

        public ChannelType channelType() {
            return ChannelType.fromValue(channel);
        }

        @Override
        public String toString() {
            return "Connection[id=" + id + ", channel=" + channel + ", status=" + status
                + ", credentials=[REDACTED], webhookSecret=[REDACTED]]";
        }
    }

    /** The operator-facing view: capability and health, never a secret. */
    public record ConnectionDescription(UUID id,
                                       UUID organizationId,
                                       UUID projectId,
                                       String channel,
                                       String displayName,
                                       boolean enabled,
                                       String status,
                                       String lastError,
                                       LocalDateTime lastInboundAt,
                                       LocalDateTime lastOutboundAt,
                                       boolean hasCredentials,
                                       boolean hasWebhookSecret) {
    }
}