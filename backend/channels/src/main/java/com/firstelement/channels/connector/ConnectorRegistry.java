package com.firstelement.channels.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstelement.channels.api.MessagingConnector;
import com.firstelement.channels.api.WebhookVerification;
import com.firstelement.channels.connector.meta.MetaConnector;
import com.firstelement.channels.connector.telegram.TelegramConnector;
import com.firstelement.channels.persistence.ChannelConnectionRepository;
import com.firstelement.crm.domain.ChannelType;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Builds the right adapter for a {@code channel_connections} row.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>The legacy {@code ConnectorRegistry.get(connection)} is where the runtime stopped pretending
 * the platforms were interchangeable. Keeping that shape means the webhook path and the outbound
 * path each ask for "the connector for this row" and get an object that already knows its
 * capabilities, its credential and its signature scheme.
 *
 * <h2>Credential decryption is a seam, and the default is refusal</h2>
 *
 * <p>{@code channel_connections.credentials} is encrypted at rest (FR-4.7). The decryption key is
 * not configured in this phase — {@code backend:identity-access} owns the key material and Phase 5b
 * may not read a credential store — so the default {@link CredentialDecryptor} <b>fails</b> rather
 * than returning plaintext. That failure is the important part: FR-4.8 says "The system must never
 * pretend an unconfigured real connector succeeded", and a registry that silently built an adapter
 * with empty credentials would send real customers' messages nowhere while reporting success.
 *
 * <p>The Mock channel is the exception and needs no credential, which is exactly why the whole
 * pipeline is exercisable without live provider keys.
 *
 * <h2>Only the Mock adapter is reused, and why that is not a caching bug</h2>
 *
 * <p>The legacy connector registry returned one adapter per connection, and the Mock adapter's
 * documented inspection surface ({@code sent()}, {@code sendCountFor(messageId)} &mdash; "proves a
 * retry did not") only exists if the same instance is handed back twice. So the Mock channel is
 * memoised per connection id and every real channel is built fresh per call:
 *
 * <ul>
 *   <li>a real adapter is a stateless function of the credential that was just read, so rebuilding
 *       it means a credential rotation takes effect on the very next request;</li>
 *   <li>the Mock adapter holds the only per-connection state in the module, and that state is a
 *       development affordance rather than a record.</li>
 * </ul>
 *
 * <p>The cache is bounded and drops everything when full. It is a development seam, not a pool: a
 * cache that grew without limit would be a memory leak serving a log nobody may read.
 *
 * <h2>A decryption failure is never logged with the ciphertext</h2>
 *
 * <p>The legacy controller's message is the model: "Could not decrypt channel credentials — has
 * ENCRYPTION_KEY changed?" — with the connection id and the error class, and nothing else.
 */
@Component
public class ConnectorRegistry {

    private static final Logger log = LoggerFactory.getLogger(ConnectorRegistry.class);

    /** How many Mock adapters are retained. See the class note. */
    private static final int MOCK_CACHE_SIZE = 64;

    private final ChannelConnectionRepository connections;
    private final ObjectMapper mapper;
    private final CredentialDecryptor decryptor;
    private final Duration requestTimeout;
    private final java.util.Map<UUID, com.firstelement.channels.connector.mock.MockConnector> mocks =
        new java.util.concurrent.ConcurrentHashMap<>();

    public ConnectorRegistry(ChannelConnectionRepository connections,
                             ObjectMapper mapper,
                             CredentialDecryptor decryptor,
                             Duration requestTimeout) {
        this.connections = connections;
        this.mapper = mapper;
        this.decryptor = decryptor;
        this.requestTimeout = requestTimeout;
    }

    /**
     * The adapter for one connection row.
     *
     * <p>Does not check that the connection is enabled or belongs to the caller's tenant: those are
     * the caller's decisions, and making the registry guess them would hide a missing check.
     *
     * @throws CredentialUnavailableException when a real channel's credential cannot be read
     */
    public MessagingConnector connectorFor(ChannelConnectionRepository.Connection connection) {
        ChannelType channel = connection.channelType();
        return switch (channel) {
            case MOCK -> mockConnectorFor(connection);
            case WHATSAPP, INSTAGRAM, MESSENGER -> {
                JsonNode credentials = decrypt(channel, connection);
                yield new MetaConnector(channel, connection.id().toString(),
                    new MetaConnector.MetaCredentials(
                        text(credentials, "phoneNumberId"),
                        text(credentials, "accessToken"),
                        text(credentials, "appSecret"),
                        text(credentials, "verifyToken"),
                        text(credentials, "apiVersion")),
                    mapper, requestTimeout);
            }
            case TELEGRAM -> {
                String secret = connection.webhookSecret();
                JsonNode credentials = decrypt(channel, connection);
                String token = credentials == null ? null : text(credentials, "botToken");
                yield new TelegramConnector(connection.id().toString(),
                    new TelegramConnector.TelegramCredentials(token, secret), mapper, requestTimeout);
            }
        };
    }

    /**
     * The Mock adapter for one connection, memoised.
     *
     * <p>The same instance is returned for the same connection id, which is what makes the sent log
     * an inspection surface rather than a per-call temporary.
     */
    private com.firstelement.channels.connector.mock.MockConnector mockConnectorFor(
        ChannelConnectionRepository.Connection connection) {
        com.firstelement.channels.connector.mock.MockConnector existing = mocks.get(connection.id());
        if (existing != null) {
            return existing;
        }
        if (mocks.size() >= MOCK_CACHE_SIZE) {
            // Dropped whole rather than evicted one at a time: the log is a development affordance
            // with no continuity requirement, and a partial cache is harder to reason about.
            mocks.clear();
        }
        com.firstelement.channels.connector.mock.MockConnector created =
            new com.firstelement.channels.connector.mock.MockConnector(connection.id().toString());
        mocks.put(connection.id(), created);
        return created;
    }

    /**
     * Clears the memoised Mock adapters.
     *
     * <p>A test-support seam, and the honest place for one: it changes nothing about how any adapter
     * is built, and the only state it can clear is the development sent log.
     */
    public void clearMockConnectors() {
        mocks.clear();
    }

    /**
     * The adapter for a connection id, read from the database.
     *
     * <p>On the unauthenticated webhook path this is the <em>credential lookup</em>, never an
     * authorization decision.
     */
    public Optional<MessagingConnector> forConnectionId(UUID connectionId) {
        return connections.findById(connectionId)
            .map(this::connectorFor)
            .map(connector -> (MessagingConnector) connector);
    }

    private JsonNode decrypt(ChannelType channel,
                             ChannelConnectionRepository.Connection connection) {
        String stored = connection.credentials();
        if (stored == null || stored.isBlank()) {
            throw new CredentialUnavailableException(channel, connection.id(),
                "the connection has no stored credentials");
        }
        String plaintext;
        try {
            plaintext = decryptor.decrypt(connection.id(), stored);
        } catch (RuntimeException e) {
            log.error("Could not decrypt channel credentials — has ENCRYPTION_KEY changed? "
                + "connection={} channel={} error={}", connection.id(), channel,
                e.getClass().getSimpleName());
            throw new CredentialUnavailableException(channel, connection.id(),
                "the stored credential could not be decrypted");
        }
        if (plaintext == null || plaintext.isBlank()) {
            throw new CredentialUnavailableException(channel, connection.id(),
                "the decrypted credential was empty");
        }
        try {
            return mapper.readTree(plaintext);
        } catch (Exception e) {
            throw new CredentialUnavailableException(channel, connection.id(),
                "the stored credential is not a JSON object");
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /** A verification result for a connection whose credential cannot be read. */
    public static WebhookVerification credentialUnavailable() {
        return WebhookVerification.failed(WebhookVerification.Reason.NOT_CONFIGURED,
            "the connection's credential could not be read");
    }

    /**
     * Reads the encrypted credential column.
     *
     * <p>Implemented by the module that owns the key material. The default implementation refuses,
     * which is the safe default: an unconfigured decryption path must not fall back to treating the
     * ciphertext as plaintext.
     */
    @FunctionalInterface
    public interface CredentialDecryptor {

        CredentialDecryptor REFUSES = (connectionId, stored) -> {
            throw new IllegalStateException(
                "No CredentialDecryptor is configured. A real channel must not be given ciphertext "
                    + "as if it were a token: FR-4.8 forbids pretending an unconfigured connector "
                    + "succeeded. The Mock channel needs none.");
        };

        String decrypt(UUID connectionId, String storedCiphertext);
    }

    /** A real connector's credential cannot be produced. Never caught silently. */
    public static class CredentialUnavailableException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final ChannelType channel;
        private final UUID connectionId;

        public CredentialUnavailableException(ChannelType channel, UUID connectionId, String message) {
            super(message);
            this.channel = channel;
            this.connectionId = connectionId;
        }

        public ChannelType channel() {
            return channel;
        }

        public UUID connectionId() {
            return connectionId;
        }
    }
}