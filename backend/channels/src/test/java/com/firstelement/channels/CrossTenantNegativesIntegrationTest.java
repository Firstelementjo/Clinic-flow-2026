package com.firstelement.channels;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstelement.channels.persistence.ChannelConnectionRepository;
import com.firstelement.channels.persistence.ProviderEventStore;
import com.firstelement.security.TenantAuthentication;
import com.firstelement.security.TenantContext;
import com.firstelement.security.TenantContextHolder;
import com.firstelement.security.UserRole;
import com.firstelement.security.realtime.RealtimeTopicPolicy;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Cross-tenant negatives, with data rather than with mocks.
 *
 * <h2>plan.md Phase 5 gate</h2>
 *
 * <p>"Cross-tenant and cross-project HTTP, service, repository, report, export, worker, realtime, and
 * AI-tool paths" must be covered by negative tests. This suite covers the connectors and the
 * realtime topics; the worker paths are covered in {@code :backend:worker-runtime}.
 *
 * <h2>What "negative" means here</h2>
 *
 * <p>Not an exception — <b>absence</b>. Beta probing Alpha's connection id gets nothing, Beta's
 * provider-event key does not collide with Alpha's, and Beta's realtime subscription to Alpha's
 * project topic is refused and recorded. A refusal that threw would still have to be caught
 * somewhere, and the answer should be indistinguishable from "there is nothing there".
 */
@SpringBootTest(classes = ChannelsTestApplication.class)
class CrossTenantNegativesIntegrationTest extends ChannelsIntegrationTestBase {

    @Autowired
    private ChannelConnectionRepository connections;
    @Autowired
    private ProviderEventStore providerEvents;
    @Autowired
    private RealtimeTopicPolicy topicPolicy;
    @Autowired
    private ChannelsFixture fixture;

    @BeforeEach
    void reset() {
        fixture.reset();
    }

    @Test
    @DisplayName("an enabled-connection lookup is scoped: Beta cannot name Alpha's project")
    void enabledLookupIsTenantScoped() {
        fixture.connection(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A, "MOCK", "{}");

        assertThat(connections.findEnabled(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            com.firstelement.crm.domain.ChannelType.MOCK)).isPresent();
        assertThat(connections.findEnabled(ChannelsFixture.ORG_B, ChannelsFixture.PROJECT_A,
            com.firstelement.crm.domain.ChannelType.MOCK)).isEmpty();
        assertThat(connections.findEnabled(ChannelsFixture.ORG_B, ChannelsFixture.PROJECT_B,
            com.firstelement.crm.domain.ChannelType.MOCK)).isEmpty();
    }

    @Test
    @DisplayName("the admin connector panel returns only the caller's tenant, and no secret")
    void thePanelIsTenantScopedAndSecretFree() {
        fixture.connection(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A, "MOCK", "{\"a\":1}");
        fixture.connection(ChannelsFixture.ORG_B, ChannelsFixture.PROJECT_B, "MOCK", "{\"b\":2}");

        var alpha = connections.describe(ChannelsFixture.ORG_A);
        assertThat(alpha).hasSize(1);
        assertThat(alpha.get(0).organizationId()).isEqualTo(ChannelsFixture.ORG_A);
        assertThat(alpha.get(0).hasCredentials()).isTrue();

        // Presence, never the value: the projection selects booleans, not the column.
        String rendered = alpha.get(0).toString();
        assertThat(rendered).doesNotContain("\"a\":1").doesNotContain("\"b\":2");

        // And the Connection record redacts both secret columns even in a stack trace.
        var raw = connections.findById(alpha.get(0).id()).orElseThrow();
        assertThat(raw.toString()).contains("credentials=[REDACTED]")
            .contains("webhookSecret=[REDACTED]");
    }

    @Test
    @DisplayName("the same provider event id in two tenants is two reservations, not a collision")
    void providerEventReservationsAreTenantScoped() {
        assertThat(providerEvents.reserveFirstSighting(ChannelsFixture.ORG_A,
            ChannelsFixture.PROJECT_A, "MOCK", "wamid.SHARED", null)).isTrue();
        assertThat(providerEvents.reserveFirstSighting(ChannelsFixture.ORG_B,
            ChannelsFixture.PROJECT_B, "MOCK", "wamid.SHARED", null)).isTrue();
        // The redelivery within each tenant is the one that is refused.
        assertThat(providerEvents.reserveFirstSighting(ChannelsFixture.ORG_A,
            ChannelsFixture.PROJECT_A, "MOCK", "wamid.SHARED", null)).isFalse();
        assertThat(providerEvents.reserveFirstSighting(ChannelsFixture.ORG_B,
            ChannelsFixture.PROJECT_B, "MOCK", "wamid.SHARED", null)).isFalse();

        assertThat(providerEvents.find(ChannelsFixture.ORG_A, "MOCK", "wamid.SHARED")).isPresent();
        assertThat(providerEvents.find(ChannelsFixture.ORG_B, "MOCK", "wamid.SHARED")).isPresent();
        // A tenant that has never seen the event has nothing, and that absence must not disclose
        // the other tenant's record.
        assertThat(providerEvents.find(ChannelsFixture.ORG_A, "TELEGRAM", "wamid.SHARED")).isEmpty();
    }

    @Test
    @DisplayName("a realtime subscription cannot name another tenant's organization or project topic")
    void realtimeTopicsAreTenantScoped() {
        TenantContext beta = adminB();

        var result = topicPolicy.filter(beta, List.of(
            RealtimeTopicPolicy.organizationTopic(ChannelsFixture.ORG_A),
            RealtimeTopicPolicy.projectTopic(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A),
            RealtimeTopicPolicy.organizationTopic(ChannelsFixture.ORG_B),
            RealtimeTopicPolicy.projectTopic(ChannelsFixture.ORG_B, ChannelsFixture.PROJECT_B)));

        assertThat(result.allowed()).containsExactlyInAnyOrder(
            RealtimeTopicPolicy.organizationTopic(ChannelsFixture.ORG_B),
            RealtimeTopicPolicy.projectTopic(ChannelsFixture.ORG_B, ChannelsFixture.PROJECT_B));
        // Two denials: Beta's own organization id embedded in a topic it may not read, and its own
        // project under another tenant's organization.
        assertThat(result.denied()).containsExactlyInAnyOrder(
            RealtimeTopicPolicy.organizationTopic(ChannelsFixture.ORG_A),
            RealtimeTopicPolicy.projectTopic(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A));
        assertThat(result.fullyAuthorized()).isFalse();

        assertThat(topicPolicy.canSubscribe(beta,
            RealtimeTopicPolicy.projectTopic(ChannelsFixture.ORG_B, ChannelsFixture.PROJECT_A)))
            .isFalse();
        assertThat(topicPolicy.canSubscribe(beta,
            RealtimeTopicPolicy.projectTopic(ChannelsFixture.ORG_B, ChannelsFixture.PROJECT_B)))
            .isTrue();
    }

    @Test
    @DisplayName("a machine principal with no project scope gets only the organization topic")
    void aWorkerPrincipalGetsNoProjectTopic() {
        TenantContext worker = TenantContext.forMachine(
            com.firstelement.security.PrincipalType.WORKER, ChannelsFixture.ORG_A, "worker-1",
            List.of(), List.of(com.firstelement.security.Capability.WEBHOOK_INGEST));

        assertThat(topicPolicy.allowedTopics(worker)).containsExactly(
            RealtimeTopicPolicy.organizationTopic(ChannelsFixture.ORG_A));
        // A null context yields nothing rather than a wildcard.
        assertThat(topicPolicy.allowedTopics(null)).isEmpty();
    }

    @Test
    @DisplayName("a connection in another tenant cannot be marked inbound or given an error")
    void crossTenantConnectionMaintenanceIsRefused() {
        UUID alphaConnection = fixture.connection(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            "MOCK", "{}");

        assertThat(connections.markInbound(ChannelsFixture.ORG_B, alphaConnection)).isFalse();
        assertThat(connections.recordError(ChannelsFixture.ORG_B, alphaConnection, "probe"))
            .isFalse();
        assertThat(connections.clearError(ChannelsFixture.ORG_B, alphaConnection)).isFalse();

        // Alpha's row is untouched: no lastError, no lastInboundAt.
        var row = connections.findById(alphaConnection).orElseThrow();
        assertThat(row.lastError()).isNull();
        assertThat(row.lastInboundAt()).isNull();

        assertThat(connections.markInbound(ChannelsFixture.ORG_A, alphaConnection)).isTrue();
    }

    @Test
    @DisplayName("an unknown connection id yields nothing, which is what a probe should see")
    void unknownConnectionYieldsNothing() {
        assertThat(connections.findById(UUID.randomUUID())).isEmpty();
        assertThat(connections.findEnabled(ChannelsFixture.ORG_A, UUID.randomUUID(),
            com.firstelement.crm.domain.ChannelType.MOCK)).isEmpty();
    }

    @Test
    @DisplayName("a disabled connection is not selectable for delivery")
    void disabledConnectionIsNotSelectable() {
        UUID connection = fixture.connection(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            "MOCK", "{}");
        fixture.jdbc().update("update channel_connections set \"isEnabled\" = false where \"id\" = :id",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("id", connection));

        assertThat(connections.findEnabled(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            com.firstelement.crm.domain.ChannelType.MOCK)).isEmpty();
        // The row still exists, because the webhook path needs to answer "not found" identically for
        // a disabled and an unknown connection.
        assertThat(connections.findById(connection)).isPresent();
    }

    @Test
    @DisplayName("no connector adapter ever renders a credential")
    void adaptersRedactTheirCredentials() {
        var meta = new com.firstelement.channels.connector.meta.MetaConnector.MetaCredentials(
            "phone", "the-access-token", "the-app-secret", "the-verify-token", "v21.0");
        var telegram = new com.firstelement.channels.connector.telegram.TelegramConnector
            .TelegramCredentials("the-bot-token", "the-webhook-secret");

        assertThat(meta.toString()).isEqualTo("MetaCredentials[REDACTED]");
        assertThat(telegram.toString()).isEqualTo("TelegramCredentials[REDACTED]");
        assertThat(meta.toString()).doesNotContain("the-access-token")
            .doesNotContain("the-app-secret").doesNotContain("the-verify-token");
        assertThat(telegram.toString()).doesNotContain("the-bot-token")
            .doesNotContain("the-webhook-secret");
    }

    // ------------------------------------------------------------------

    private static TenantContext adminB() {
        return TenantContext.forUser(UUID.randomUUID(), ChannelsFixture.USER_B, ChannelsFixture.ORG_B,
            UserRole.ADMIN, Set.of(ChannelsFixture.PROJECT_B));
    }

    private static SecurityContext authentication(TenantContext context) {
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new TenantAuthentication(context));
        return securityContext;
    }

    @SuppressWarnings("unused")
    private static <T> T as(TenantContext context, java.util.function.Supplier<T> work) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContextHolder.setContext(authentication(context));
        try {
            return TenantContextHolder.call(context, work);
        } finally {
            SecurityContextHolder.setContext(previous);
            TenantContextHolder.clear();
        }
    }
}