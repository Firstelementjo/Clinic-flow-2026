package com.firstelement.channels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.firstelement.channels.service.InboundWebhookIntakeService;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The webhook HTTP boundary, over the product's own filter chain, against real PostgreSQL.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>{@link com.firstelement.channels.web.ChannelWebhookController} claimed to be "the only
 * unauthenticated write paths in the API", and the signature verifier had good unit tests. Nothing
 * connected the two: no HTTP test had ever performed an unauthenticated provider POST, so the claim
 * was untested in exactly the place where it is load-bearing. It was wrong.
 *
 * <p>With no {@code /webhooks/**} entry in {@code SecurityConfiguration.PUBLIC_PATHS},
 * {@code anyRequest().authenticated()} answered {@code 401} for every inbound message, and the
 * {@code CsrfFilter} answered {@code 403 CSRF_TOKEN_INVALID} first. Both are the filter chain;
 * neither is the provider's signature. Only an HTTP test can tell those apart, which is why the
 * absence of one was the finding.
 *
 * <p>This class asserts the boundary in both directions, because the failure mode of a fix here is
 * one-sided: permitting the path without proving the signature still governs it converts a total
 * outage into an open write into every tenant's inbox.
 *
 * <h2>How "the refusal is the signature's" is proved, and not assumed</h2>
 *
 * <p>A 404 alone is not evidence, because a 404 is also what a nonexistent or unusable connection
 * returns. The two are told apart by a second, independent observation:
 *
 * <ul>
 *   <li>the error {@code code} is the controller's own {@code NOT_FOUND}, never the filter chain's
 *       {@code AUTHENTICATION_REQUIRED} or {@code CSRF_TOKEN_INVALID};</li>
 *   <li>{@code channel_connections."lastError"} records the verification failure. A row only the
 *       intake service writes, so it proves the request <em>reached</em> the signature path — and
 *       its absence proves the request was refused before the verifier ran.</li>
 * </ul>
 *
 * <p>Both paths additionally assert that nothing was queued.
 *
 * <h2>What is deliberately not asserted</h2>
 *
 * <p>No test here prints an app secret, a webhook secret, a session token or a customer message body.
 * Every expectation is about a status, an error code, or a row count.
 */
@SpringBootTest(classes = ChannelsTestApplication.class)
@AutoConfigureMockMvc
class WebhookAuthenticationBoundaryHttpIntegrationTest extends ChannelsIntegrationTestBase {

    /** A fabricated Meta app secret. Not a credential and not a real one. */
    private static final String APP_SECRET = "test-meta-app-secret-not-a-credential";
    private static final String VERIFY_TOKEN = "test-meta-verify-token-not-a-credential";
    /**
     * {@code ChannelsFixture.connection} writes this into
     * {@code channel_connections."webhookSecret"}, which is where TelegramConnector reads its
     * shared secret from.
     */
    private static final String TELEGRAM_SECRET = "test-webhook-secret";

    private static final String META_SIGNATURE_HEADER = "X-Hub-Signature-256";
    private static final String TELEGRAM_SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token";

    /**
     * A realistic WhatsApp Cloud API payload.
     *
     * <p>The Arabic customer turn is written as unicode escapes rather than literal characters. A
     * source file whose encoding is not UTF-8 turns the literal into {@code ?????}, which is still
     * valid JSON — so the payload would silently become a different, shorter message rather than
     * failing loudly. The escapes make the bytes identical whatever the compiler is configured
     * with.
     */
    private static final String META_BODY = """
        {"object":"whatsapp_business_account","entry":[{"id":"1","changes":[{"value":\
        {"messaging_product":"whatsapp","metadata":{"display_phone_number":"15550000000"},\
        "contacts":[{"wa_id":"966500000001","profile":{"name":"\\u0639\\u0645\\u064a\\u0644"}}],\
        "messages":[{"from":"966500000001","id":"wamid.HTTP1","timestamp":"1757000000",\
        "type":"text","text":{"body":"\\u0645\\u0631\\u062d\\u0628\\u0627"}}]}}]}]}""";

    private static final String TELEGRAM_BODY = """
        {"update_id":1,"message":{"message_id":42,"date":1757000000,\
        "chat":{"id":966500000001},\
        "from":{"id":966500000001,"first_name":"\\u0639\\u0645\\u064a\\u0644"},\
        "text":"\\u0645\\u0631\\u062d\\u0628\\u0627"}}""";

    /** The Arabic customer turn as it appears at runtime, used to assert it is never disclosed. */
    private static final String CUSTOMER_TEXT = "مرحبا";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ChannelsFixture fixture;
    @Autowired
    private InboundWebhookIntakeService intake;
    @Autowired
    private List<RequestMappingHandlerMapping> handlerMappings;

    @BeforeEach
    void reset() {
        fixture.reset();
    }

    // ==================================================================
    // 1. the fact: a provider authenticates by signature, not by session
    // ==================================================================

    @Nested
    @DisplayName("an unauthenticated provider POST is admitted on its signature")
    class ProviderAuthentication {

        @Test
        @DisplayName("a correctly signed Meta webhook is accepted and queued, with no cookie of any kind")
        void aSignedMetaWebhookIsAccepted() throws Exception {
            UUID connection = metaConnection();

            // No session cookie, no CSRF token, no Authorization header: exactly what Meta sends.
            MvcResult result = mvc.perform(post("/webhooks/meta/" + connection)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(META_SIGNATURE_HEADER, signature(META_BODY))
                    .content(META_BODY.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true))
                .andReturn();

            assertThat(result.getRequest().getCookies())
                .as("the request presented no cookie at all, so nothing ambient authenticated it")
                .isNull();

            assertThat(fixture.count("select count(*) from outbox_events"))
                .as("the verified payload must be durably queued")
                .isEqualTo(1);
            assertThat(fixture.count("select count(*) from idempotency_records")).isEqualTo(1);
            assertThat(fixture.lastErrorOf(connection))
                .as("a verified request clears the connection's error")
                .isNull();
        }

        @Test
        @DisplayName("a correctly signed Telegram webhook is accepted")
        void aSignedTelegramWebhookIsAccepted() throws Exception {
            UUID connection = telegramConnection();

            mvc.perform(post("/webhooks/telegram/" + connection)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(TELEGRAM_SECRET_HEADER, TELEGRAM_SECRET)
                    .content(TELEGRAM_BODY.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true));

            assertThat(fixture.count("select count(*) from outbox_events")).isEqualTo(1);
        }

        @Test
        @DisplayName("the Meta GET handshake echoes the challenge only for the matching token")
        void theHandshakeIsTokenGuarded() throws Exception {
            UUID connection = metaConnection();

            mvc.perform(get("/webhooks/meta/" + connection)
                    .param("hub.mode", "subscribe")
                    .param("hub.verify_token", VERIFY_TOKEN)
                    .param("hub.challenge", "31415"))
                .andExpect(status().isOk())
                .andExpect(content().string("31415"));

            // A wrong token, an absent token, a wrong mode and an absent challenge are all the same
            // empty 404: the handshake must not be a verify-token oracle either.
            assertHandshakeRefused(connection, VERIFY_TOKEN + "x", "31415", "subscribe");
            assertHandshakeRefused(connection, null, "31415", "subscribe");
            assertHandshakeRefused(connection, VERIFY_TOKEN, "31415", "unsubscribe");
            assertHandshakeRefused(connection, VERIFY_TOKEN, null, "subscribe");
            assertHandshakeRefused(connection, null, null, null);
        }

        @Test
        @DisplayName("a redelivery is acknowledged idempotently rather than duplicated")
        void redeliveryIsIdempotent() throws Exception {
            UUID connection = metaConnection();

            for (int i = 0; i < 3; i++) {
                mvc.perform(post("/webhooks/meta/" + connection)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(META_SIGNATURE_HEADER, signature(META_BODY))
                        .content(META_BODY.getBytes(StandardCharsets.UTF_8)))
                    .andExpect(status().isOk());
            }

            assertThat(fixture.count("select count(*) from outbox_events")).isEqualTo(1);
            assertThat(fixture.count("select count(*) from idempotency_records")).isEqualTo(1);
        }
    }

    // ==================================================================
    // 2. the negative: permitting the path must not permit anything
    // ==================================================================

    @Nested
    @DisplayName("an unauthenticated request without a valid signature is refused")
    class SignatureIsTheBoundary {

        @Test
        @DisplayName("an absent signature is refused, and the refusal is the verifier's")
        void anAbsentSignatureIsRefused() throws Exception {
            UUID connection = metaConnection();

            assertSignatureRefusal(connection, post("/webhooks/meta/" + connection)
                .contentType(MediaType.APPLICATION_JSON)
                .content(META_BODY.getBytes(StandardCharsets.UTF_8)));
        }

        @Test
        @DisplayName("a tampered body is refused even when a well-formed signature header is present")
        void aTamperedBodyIsRefused() throws Exception {
            UUID connection = metaConnection();

            // The signature is syntactically perfect and was computed over different bytes. This is
            // the attack a re-serialized-body implementation would accept, so it is the one that
            // must not be merely asserted in prose.
            assertSignatureRefusal(connection, post("/webhooks/meta/" + connection)
                .contentType(MediaType.APPLICATION_JSON)
                .header(META_SIGNATURE_HEADER, signature("{\"tampered\":true}"))
                .content(META_BODY.getBytes(StandardCharsets.UTF_8)));
        }

        @Test
        @DisplayName("a signature computed with the wrong secret is refused")
        void aWrongSecretIsRefused() throws Exception {
            UUID connection = metaConnection();

            assertSignatureRefusal(connection, post("/webhooks/meta/" + connection)
                .contentType(MediaType.APPLICATION_JSON)
                .header(META_SIGNATURE_HEADER, signatureWith("a-different-secret", META_BODY))
                .content(META_BODY.getBytes(StandardCharsets.UTF_8)));
        }

        @Test
        @DisplayName("a malformed signature header is refused without reaching the parser")
        void aMalformedSignatureIsRefused() throws Exception {
            UUID connection = metaConnection();

            for (String header : List.of("sha1=abc", "sha256=", "sha256=nothex!!", "no-equals")) {
                assertSignatureRefusal(connection, post("/webhooks/meta/" + connection)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(META_SIGNATURE_HEADER, header)
                    .content(META_BODY.getBytes(StandardCharsets.UTF_8)));
            }
        }

        @Test
        @DisplayName("a Telegram webhook is refused both without the secret header and with a wrong one")
        void telegramWithoutItsSecretIsRefused() throws Exception {
            UUID connection = telegramConnection();

            // No header at all: the URL's family requires one, so the request never reaches the
            // verifier and the connection row stays clean.
            assertEndpointNotFound(connection, post("/webhooks/telegram/" + connection)
                .contentType(MediaType.APPLICATION_JSON)
                .content(TELEGRAM_BODY.getBytes(StandardCharsets.UTF_8)));

            // The header is present but wrong: this one does reach the verifier, and the recorded
            // verification failure is what distinguishes it internally from the case above.
            assertSignatureRefusal(connection, post("/webhooks/telegram/" + connection)
                .contentType(MediaType.APPLICATION_JSON)
                .header(TELEGRAM_SECRET_HEADER, "not-the-secret")
                .content(TELEGRAM_BODY.getBytes(StandardCharsets.UTF_8)));

            assertThat(fixture.count("select count(*) from outbox_events"))
                .as("a refused webhook must never reach the queue")
                .isZero();
        }

        @Test
        @DisplayName("a disabled connection is refused before the verifier runs")
        void aDisabledConnectionIsRefused() throws Exception {
            UUID connection = metaConnection();
            fixture.jdbc().update(
                "update channel_connections set \"isEnabled\" = false where \"id\" = :id",
                new MapSqlParameterSource("id", connection));

            // A correctly signed payload, so the only reason to refuse is that the connection is
            // switched off. It is the same 404 a bad signature gets.
            assertEndpointNotFound(connection, post("/webhooks/meta/" + connection)
                .contentType(MediaType.APPLICATION_JSON)
                .header(META_SIGNATURE_HEADER, signature(META_BODY))
                .content(META_BODY.getBytes(StandardCharsets.UTF_8)));
        }

        @Test
        @DisplayName("a connection from another channel family is refused at this endpoint")
        void aCrossFamilyConnectionIsRefused() throws Exception {
            UUID connection = telegramConnection();

            // A Telegram credential posted at the Meta URL. The URL's family is the authority, not
            // the headers the caller chose to send.
            assertEndpointNotFound(connection, post("/webhooks/meta/" + connection)
                .contentType(MediaType.APPLICATION_JSON)
                .header(META_SIGNATURE_HEADER, signature(META_BODY))
                .content(META_BODY.getBytes(StandardCharsets.UTF_8)));
        }

        @Test
        @DisplayName("the unsigned development endpoint is closed unless explicitly enabled")
        void theMockEndpointIsNotAnOpenDoor() throws Exception {
            UUID connection = fixture.connection(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
                "MOCK", "{}");

            assertThat(intake.mockWebhookAllowed())
                .as("the suite runs with firstelement.channels.mock-webhook-enabled=false, so this "
                    + "404 is attributable to the flag rather than to a signature failure")
                .isFalse();

            // /webhooks/** is served without a session. An ungated Mock endpoint would therefore be
            // an unauthenticated, unsigned write into a tenant's inbox on any non-production
            // deployment, which is the specific way this fix could have made things worse.
            assertEndpointNotFound(connection, post("/webhooks/mock/" + connection)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"hello\"}"));

            assertThat(fixture.count("select count(*) from outbox_events")).isZero();
            assertThat(fixture.count("select count(*) from idempotency_records")).isZero();
        }

        @Test
        @DisplayName("an unknown connection id is indistinguishable from a bad signature")
        void unknownConnectionIsIndistinguishable() throws Exception {
            UUID known = metaConnection();
            UUID unknown = UUID.randomUUID();

            String badSignature = mvc.perform(post("/webhooks/meta/" + known)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(META_BODY.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

            String noSuchConnection = mvc.perform(post("/webhooks/meta/" + unknown)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(META_SIGNATURE_HEADER, signature(META_BODY))
                    .content(META_BODY.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

            assertThat(stripRequestId(noSuchConnection))
                .as("an id probe must not learn whether a connection id exists")
                .isEqualTo(stripRequestId(badSignature));
        }

        @Test
        @DisplayName("a refusal body names neither the secret, nor the payload, nor the reason")
        void refusalsDiscloseNothing() throws Exception {
            UUID connection = metaConnection();

            String body = mvc.perform(post("/webhooks/meta/" + connection)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(META_SIGNATURE_HEADER, signatureWith("a-different-secret", META_BODY))
                    .content(META_BODY.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

            assertThat(body)
                .doesNotContain(APP_SECRET)
                .doesNotContain("a-different-secret")
                .doesNotContain(CUSTOMER_TEXT)
                .doesNotContain("wamid")
                .doesNotContain("SIGNATURE_MISMATCH")
                .doesNotContain("MISSING_HEADER");
        }
    }

    // ==================================================================
    // 3. the boundary did not widen
    // ==================================================================

    @Nested
    @DisplayName("the exemption is scoped to /webhooks/** and nothing else")
    class TheBoundaryDidNotWiden {

        @Test
        @DisplayName("an unauthenticated /api/v1 read is still refused")
        void apiReadsStillRequireASession() throws Exception {
            for (String path : List.of("/api/v1/channels/capabilities",
                "/api/v1/channels/connections", "/api/v1/inbox/unassigned")) {
                mvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
            }
        }

        @Test
        @DisplayName("a client-supplied tenant header is not authorization")
        void aTenantHeaderDoesNotAuthenticate() throws Exception {
            mvc.perform(get("/api/v1/channels/connections")
                    .header("X-Organization-Id", ChannelsFixture.ORG_A.toString())
                    .header("X-Tenant-Id", ChannelsFixture.ORG_A.toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        }

        @Test
        @DisplayName("an unauthenticated inbox write is still refused on both CSRF and session")
        void apiWritesAreStillRefused() throws Exception {
            // The same "push a message into an inbox" intent as a webhook, but under /api/v1.
            mvc.perform(post("/api/v1/dev/mock/incoming-message")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));

            mvc.perform(post("/api/v1/dev/mock/incoming-message")
                    .cookie(new jakarta.servlet.http.Cookie("XSRF-TOKEN", "test-csrf-token"))
                    .header("X-XSRF-TOKEN", "test-csrf-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        }

        @Test
        @DisplayName("a signed webhook payload does not authenticate anything outside /webhooks/**")
        void aSignatureIsNotACredentialElsewhere() throws Exception {
            mvc.perform(get("/api/v1/channels/connections")
                    .header(META_SIGNATURE_HEADER, signature(META_BODY)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        }

        @Test
        @DisplayName("an unmapped webhook-shaped path answers 404 and never reaches a handler")
        void unmappedWebhookPathIsNotFound() throws Exception {
            mvc.perform(post("/webhooks/meta")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(META_SIGNATURE_HEADER, signature(META_BODY))
                    .content(META_BODY.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("the secure header policy still applies to webhook responses")
        void headersStillApply() throws Exception {
            UUID connection = metaConnection();

            MvcResult result = mvc.perform(post("/webhooks/meta/" + connection)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(META_SIGNATURE_HEADER, signature(META_BODY))
                    .content(META_BODY.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andReturn();

            assertThat(result.getResponse().getHeader("X-Frame-Options")).isEqualTo("DENY");
            assertThat(result.getResponse().getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        }
    }

    // ==================================================================
    // 4. the enumeration: a new route must not ship closed or open
    // ==================================================================

    @Nested
    @DisplayName("every /webhooks/** route carries the same treatment")
    class EveryWebhookRouteIsAccountedFor {

        @Test
        @DisplayName("every mapped /webhooks/** route is reachable without a session")
        void everyWebhookRouteIsPermitted() throws Exception {
            Set<String> routes = webhookRoutes();

            assertThat(routes)
                .as("no /webhooks/** route is mapped, so this class would be asserting nothing")
                .isNotEmpty();

            Set<String> publicPaths = declaredPatterns("PUBLIC_PATHS");
            for (String route : routes) {
                assertThat(covers(publicPaths, route))
                    .as("%s is a provider route with no FirstElement session. Without an entry in "
                        + "SecurityConfiguration.PUBLIC_PATHS it answers 401 and the provider's "
                        + "message is silently dropped, which is exactly the outage this class "
                        + "exists for. Add the path, then prove the signature still governs it.",
                        route)
                    .isTrue();
            }
        }

        @Test
        @DisplayName("every mapped /webhooks/** route is exempt from CSRF")
        void everyWebhookRouteIsCsrfExempt() throws Exception {
            Set<String> routes = webhookRoutes();
            Set<String> exempt = declaredPatterns("CSRF_EXEMPT_PATHS");

            assertThat(exempt)
                .as("SecurityConfiguration.CSRF_EXEMPT_PATHS is the set the CsrfFilter is configured "
                    + "with; without it a provider POST answers 403 before the controller runs")
                .isNotEmpty();

            for (String route : routes) {
                assertThat(covers(exempt, route))
                    .as("%s would be refused with 403 CSRF_TOKEN_INVALID. CSRF defends against a "
                        + "browser being induced to carry a cookie; a provider carries no cookie, so "
                        + "CSRF is not this route's control and would leave the exemption "
                        + "unreachable in production.", route)
                    .isTrue();
            }
        }

        @Test
        @DisplayName("the exemption is a /webhooks/** rule, not a blanket relaxation")
        void theExemptionIsNarrow() {
            Set<String> publicPaths = declaredPatterns("PUBLIC_PATHS");

            assertThat(publicPaths).contains("/webhooks/**");
            assertThat(publicPaths)
                .as("a pattern matching /api/v1/**, or /**, would open the whole API; the exemption "
                    + "must be the narrowest pattern that covers the provider routes")
                .doesNotContain("/**", "/api/v1/**", "/api/**", "/*", "/webhooks");
            assertThat(publicPaths)
                .as("the pre-existing public surface must not have changed while fixing this")
                .contains("/api/v1/status", "/api/v1/health", "/api/v1/auth/login",
                    "/api/v1/auth/csrf", "/actuator/health", "/actuator/health/**",
                    "/actuator/info", "/error");
            assertThat(publicPaths).hasSize(9);

            assertThat(declaredPatterns("CSRF_EXEMPT_PATHS"))
                .as("the CSRF exemption must be the webhook paths and nothing more")
                .containsExactly("/webhooks/**");
        }

        @Test
        @DisplayName("the documented provider routes are all still mapped")
        void theDocumentedRoutesAreAllMapped() {
            assertThat(webhookRoutes()).containsExactlyInAnyOrder(
                "GET /webhooks/meta/{connectionId}",
                "POST /webhooks/meta/{connectionId}",
                "POST /webhooks/telegram/{connectionId}",
                "POST /webhooks/mock/{connectionId}");
        }
    }

    // ==================================================================
    // helpers
    // ==================================================================

    /**
     * Every {@code /webhooks/**} route the running context actually maps, read from the handler
     * mapping rather than from a list of controllers — so a webhook added next month in any module
     * is covered whether or not this file has been updated.
     */
    private Set<String> webhookRoutes() {
        Set<String> routes = new TreeSet<>();
        for (RequestMappingHandlerMapping mapping : handlerMappings) {
            mapping.getHandlerMethods().forEach((info, method) -> {
                if (info.getPathPatternsCondition() == null) {
                    return;
                }
                for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                    if (!pattern.startsWith("/webhooks")) {
                        continue;
                    }
                    for (org.springframework.web.bind.annotation.RequestMethod verb
                        : info.getMethodsCondition().getMethods()) {
                        routes.add(verb.name() + " " + pattern);
                    }
                }
            });
        }
        return routes;
    }

    /**
     * The paths the filter chain actually uses, read by reflection from the same fields the
     * configuration and the OpenAPI exporter read. A copy here would be a second answer to a
     * question {@link com.firstelement.security.config.SecurityConfiguration} already answers.
     */
    private static Set<String> declaredPatterns(String fieldName) {
        try {
            Field field = com.firstelement.security.config.SecurityConfiguration.class
                .getDeclaredField(fieldName);
            field.setAccessible(true);
            return new TreeSet<>(List.of((String[]) field.get(null)));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not read SecurityConfiguration." + fieldName
                + ", so this test cannot tell which routes the chain lets through without a "
                + "session. Failing is the honest answer.", e);
        }
    }

    /** Whether any declared pattern covers a concrete route, Ant-style and template-aware. */
    private static boolean covers(Set<String> patterns, String route) {
        String method = route.substring(0, route.indexOf(' '));
        String path = route.substring(route.indexOf(' ') + 1);
        return patterns.stream().anyMatch(pattern -> matches(pattern, method, path));
    }

    private static boolean matches(String pattern, String method, String path) {
        StringBuilder regex = new StringBuilder();
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '*' && i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                regex.append(".*");
                i += 2;
                if (i < pattern.length() && pattern.charAt(i) == '/') {
                    i++;
                }
            } else if (c == '*') {
                regex.append("[^/]*");
                i++;
            } else if ("{}".indexOf(c) >= 0) {
                regex.append("[^/]+");
                i++;
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
                i++;
            }
        }
        if (!path.matches(regex.toString())) {
            return false;
        }
        // A declared pattern that names an HTTP verb constrains the verb too.
        int space = pattern.indexOf(' ');
        return space < 0 || pattern.substring(0, space).equals(method);
    }

    private void assertHandshakeRefused(UUID connection, String token, String challenge, String mode)
        throws Exception {
        MockHttpServletRequestBuilder request = get("/webhooks/meta/" + connection);
        if (mode != null) {
            request = request.param("hub.mode", mode);
        }
        if (token != null) {
            request = request.param("hub.verify_token", token);
        }
        if (challenge != null) {
            request = request.param("hub.challenge", challenge);
        }
        mvc.perform(request).andExpect(status().isNotFound()).andExpect(content().string(""));
    }

    /**
     * Asserts a refusal was the verifier's.
     *
     * <p>Two independent observations, either of which would fail if the filter chain had answered
     * first: the error code is the controller's own {@code NOT_FOUND}, and the connection row
     * records the verification failure — something only the intake service writes.
     */
    private void assertSignatureRefusal(UUID connection,
                                        MockHttpServletRequestBuilder request) throws Exception {
        assertNotAFilterChainRefusal(request);
        assertThat(fixture.lastErrorOf(connection))
            .as("the intake service reached the verifier and refused there")
            .isNotNull()
            .contains("Webhook verification failed");
        assertNothingQueued();
    }

    /**
     * Asserts a refusal happened before the verifier ran: an unknown, disabled, wrong-family or
     * disabled-feature connection.
     *
     * <p>The {@code lastError} row must be <em>absent</em> here. That is what tells the two refusal
     * paths apart internally, while both answer the caller with the same body.
     */
    private void assertEndpointNotFound(UUID connection,
                                        MockHttpServletRequestBuilder request) throws Exception {
        assertNotAFilterChainRefusal(request);
        assertThat(fixture.lastErrorOf(connection))
            .as("an unknown, disabled or wrong-family connection is refused before the verifier "
                + "runs, so the row stays clean")
            .isNull();
        assertNothingQueued();
    }

    private void assertNotAFilterChainRefusal(MockHttpServletRequestBuilder request)
        throws Exception {
        MvcResult result = mvc.perform(request)
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andReturn();

        assertThat(result.getResponse().getContentAsString())
            .as("the refusal must not be an authentication or CSRF challenge: either would mean the "
                + "signature path never ran")
            .doesNotContain("AUTHENTICATION_REQUIRED")
            .doesNotContain("CSRF_TOKEN_INVALID");
    }

    private void assertNothingQueued() {
        assertThat(fixture.count("select count(*) from outbox_events"))
            .as("a refused webhook is never queued")
            .isZero();
        assertThat(fixture.count("select count(*) from idempotency_records"))
            .as("a refused webhook never reserves its provider event id")
            .isZero();
    }

    /** An enabled WhatsApp connection whose stored credential carries {@link #APP_SECRET}. */
    private UUID metaConnection() {
        return fixture.connection(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A, "WHATSAPP",
            "{\"phoneNumberId\":\"1\",\"accessToken\":\"test-access-token\",\"appSecret\":\""
                + APP_SECRET + "\",\"verifyToken\":\"" + VERIFY_TOKEN + "\"}");
    }

    private UUID telegramConnection() {
        return fixture.connection(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A, "TELEGRAM",
            "{\"botToken\":\"test-bot-token\"}");
    }

    private static String signature(String body) {
        return signatureWith(APP_SECRET, body);
    }

    private static String signatureWith(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("sha256=");
            for (byte b : digest) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String stripRequestId(String body) {
        return body.replaceAll("\"requestId\":(\"[^\"]*\"|null)", "\"requestId\":\"*\"")
            .replaceAll("\"timestamp\":\"[^\"]*\"", "\"timestamp\":\"*\"");
    }
}