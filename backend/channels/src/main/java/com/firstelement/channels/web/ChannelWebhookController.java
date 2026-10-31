package com.firstelement.channels.web;

import com.firstelement.channels.connector.ConnectorRegistry;
import com.firstelement.channels.connector.meta.MetaConnector;
import com.firstelement.channels.connector.meta.MetaSignatureVerifier;
import com.firstelement.channels.persistence.ChannelConnectionRepository;
import com.firstelement.channels.service.InboundWebhookIntakeService;
import com.firstelement.crm.domain.ChannelType;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public webhook endpoints.
 *
 * <h2>The defences, stated</h2>
 *
 * <p>These are the only unauthenticated write paths in the API, so each one carries a decision that
 * is easy to get wrong:
 *
 * <ul>
 *   <li><b>Verification before meaning.</b> {@link RawBodyCapturingFilter} buffers the body,
 *       {@link InboundWebhookIntakeService} verifies it over those bytes, and only then is the
 *       payload parsed. A request that fails verification is never parsed and never queued.</li>
 *   <li><b>The connection id in the URL is not authorization.</b> It selects which credentials to
 *       verify against. An unknown connection, a disabled connection, a wrong channel family and a
 *       failed signature all produce the identical 404, so a probe cannot enumerate ids.</li>
 *   <li><b>Always 200 on a verified request</b>, even when processing fails: "Meta and Telegram
 *       retry aggressively on non-2xx and eventually disable a failing webhook; durability belongs
 *       to the queue, not to the HTTP response."</li>
 *   <li><b>No secret, no body, no decision detail in the response or the log.</b></li>
 * </ul>
 *
 * <h2>The Meta GET handshake and the credential boundary</h2>
 *
 * <p>Meta performs this when a webhook URL is registered, and the challenge is echoed only when the
 * verify token matches — compared in constant time by
 * {@link MetaSignatureVerifier#matchesVerifyToken}. The token lives in the connection's encrypted
 * credentials, so the handshake can only succeed where a
 * {@link ConnectorRegistry.CredentialDecryptor} is configured; with the default refusing decryptor
 * it answers 404, which is the same answer a wrong token gets and is therefore non-disclosing.
 */
@RestController
@RequestMapping("/webhooks")
public class ChannelWebhookController {

    private static final Logger log = LoggerFactory.getLogger(ChannelWebhookController.class);

    /** The only body a verified webhook ever receives. */
    private static final Map<String, Object> RECEIVED = Map.of("received", true);

    /**
     * Identical for every refusal, so a probe learns nothing: unknown id, disabled connection,
     * wrong channel family and failed signature all land here.
     */
    private static final Map<String, Object> NOT_FOUND_BODY = Map.of(
        "code", "NOT_FOUND",
        "message", "Not found",
        "details", Map.of());

    private final InboundWebhookIntakeService intake;
    private final ChannelConnectionRepository connections;
    private final ConnectorRegistry registry;

    public ChannelWebhookController(InboundWebhookIntakeService intake,
                                    ChannelConnectionRepository connections,
                                    ConnectorRegistry registry) {
        this.intake = intake;
        this.connections = connections;
        this.registry = registry;
    }

    /** The Meta GET handshake: echo the challenge only when the verify token matches. */
    @GetMapping("/meta/{connectionId}")
    public ResponseEntity<String> verifyMeta(@PathVariable UUID connectionId,
                                             @RequestParam(name = "hub.mode", required = false)
                                             String mode,
                                             @RequestParam(name = "hub.verify_token",
                                                 required = false) String token,
                                             @RequestParam(name = "hub.challenge",
                                                 required = false) String challenge) {
        if (!"subscribe".equals(mode) || token == null || token.isBlank() || challenge == null) {
            return notFound();
        }
        Optional<String> expected = verifyTokenOf(connectionId);
        if (expected.isEmpty() || !MetaSignatureVerifier.matchesVerifyToken(token,
            expected.get())) {
            return notFound();
        }
        return ResponseEntity.ok(challenge);
    }

    /** Signed Meta-shaped bodies for WhatsApp, Instagram and Messenger. */
    @PostMapping("/meta/{connectionId}")
    public ResponseEntity<Map<String, Object>> meta(@PathVariable UUID connectionId,
                                                   HttpServletRequest request) {
        return handle(connectionId, request, InboundWebhookIntakeService.META_CHANNELS);
    }

    /** Telegram's shared-secret scheme. */
    @PostMapping("/telegram/{connectionId}")
    public ResponseEntity<Map<String, Object>> telegram(@PathVariable UUID connectionId,
                                                        HttpServletRequest request) {
        return handle(connectionId, request, InboundWebhookIntakeService.TELEGRAM_CHANNELS);
    }

    /**
     * The unsigned development endpoint.
     *
     * <p>The Mock channel declares no webhook signature, so this path is an unauthenticated write
     * into a tenant's inbox whenever it is enabled. It is refused unless
     * {@code firstelement.channels.mock-webhook-enabled} is explicitly on, which
     * {@link com.firstelement.channels.config.ChannelsProperties#assertSafeFor} additionally
     * forbids in a production profile.
     *
     * <p>The gate is checked <em>per request</em>, not only at startup. {@code assertSafeFor} refuses
     * to boot a production profile with the flag on; it does not stop the route from existing, and a
     * default-off flag that nothing reads is not a control. Because {@code /webhooks/**} is served
     * without a session (see {@code SecurityConfiguration.PUBLIC_PATHS}), an ungated Mock endpoint
     * is an unauthenticated, unsigned write into every tenant's inbox on any non-production
     * deployment — staging, a demo, a developer's laptop — which is exactly where real tenants'
     * connections and webhooks are often copied. The refusal is the same empty 404 the signature
     * failures produce, so the gate discloses nothing.
     */
    @PostMapping("/mock/{connectionId}")
    public ResponseEntity<Map<String, Object>> mock(@PathVariable UUID connectionId,
                                                    HttpServletRequest request) {
        if (!intake.mockWebhookAllowed()) {
            log.warn("Mock webhook refused: the unsigned development endpoint is disabled "
                + "(firstelement.channels.mock-webhook-enabled=false) id={}", connectionId);
            return notFoundBody();
        }
        return handle(connectionId, request, InboundWebhookIntakeService.MOCK_CHANNELS);
    }

    private ResponseEntity<Map<String, Object>> handle(UUID connectionId,
                                                       HttpServletRequest request,
                                                       List<ChannelType> endpointFamily) {
        byte[] rawBody = RawBodyCapturingFilter.rawBytes(request);
        Map<String, String> headers = RawBodyCapturingFilter.headers(request);
        InboundWebhookIntakeService.IntakeResult result =
            intake.accept(connectionId, rawBody, headers, endpointFamily);
        return switch (result.outcome()) {
            // A verified request always answers 200. A signature rejection answers 404, identically
            case ACCEPTED, DUPLICATE, NO_MESSAGES -> ResponseEntity.ok(RECEIVED);
            case NOT_FOUND, SIGNATURE_REJECTED ->
                ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND_BODY);
        };
    }

    /**
     * The verify token for a Meta-family connection, read through the registry so the credential
     * boundary is respected.
     *
     * @return empty when the connection is unknown, is not a Meta channel, or its credential cannot
     *         be read — all three of which must produce the same 404
     */
    private Optional<String> verifyTokenOf(UUID connectionId) {
        Optional<ChannelConnectionRepository.Connection> connection =
            connections.findById(connectionId);
        if (connection.isEmpty() || !connection.get().enabled()) {
            return Optional.empty();
        }
        try {
            if (!(registry.connectorFor(connection.get()) instanceof MetaConnector meta)) {
                return Optional.empty();
            }
            return meta.verificationToken();
        } catch (ConnectorRegistry.CredentialUnavailableException e) {
            log.warn("Meta handshake refused: the connection credential is not readable id={}",
                connectionId);
            return Optional.empty();
        }
    }

    private static ResponseEntity<String> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body("");
    }

    /**
     * The same refusal with the JSON body the POST routes use.
     *
     * <p>Distinct from {@link #notFound()} only in body shape, which is dictated by the route's
     * declared response type. The status and the absence of any decision detail are identical, so a
     * refused Mock endpoint is indistinguishable from a refused signature.
     */
    private static ResponseEntity<Map<String, Object>> notFoundBody() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND_BODY);
    }

    /** The channel families these endpoints serve, for the OpenAPI description. */
    static List<ChannelType> metaFamily() {
        return List.of(ChannelType.WHATSAPP, ChannelType.INSTAGRAM, ChannelType.MESSENGER);
    }
}
