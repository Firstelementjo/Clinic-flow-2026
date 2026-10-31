package com.firstelement.observ;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.observ.connectors.ConnectorHealthRepository;
import com.firstelement.observ.connectors.ConnectorHealthService;
import com.firstelement.observ.jobs.JobOperationsService;
import com.firstelement.observ.search.SearchFreshnessRepository;
import com.firstelement.observ.search.SearchFreshnessService;
import com.firstelement.security.TenantContext;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Connector health, webhook verification, and search/vector freshness.
 *
 * <h2>Why these two panels share a suite</h2>
 *
 * <p>They are the two halves of the same question FR-6 asks — "is the connection working, and is the
 * knowledge index current" — and they share the property that makes them hard to get right: each one
 * must answer the question while <em>not</em> showing the thing that caused it. A connector panel that
 * shows a credential has turned a health view into a credential store; a freshness panel that shows a
 * document's chunk text has turned an index view into a copy of the knowledge base. Both are asserted
 * here against seeded markers, so the assertions are about real content being absent rather than about
 * empty columns.
 *
 * <h2>The failure mode that matters for freshness</h2>
 *
 * <p>A {@code READY} document with unembedded chunks is <em>indexed for the operator and invisible to
 * retrieval</em>: the panel says the document is fine, and a customer's question gets no answer. That
 * case is seeded explicitly, because a freshness read model tested only against consistent documents
 * would pass while missing the whole point.
 */
@SpringBootTest(classes = ObservTestApplication.class)
class ConnectorAndSearchOperationsIntegrationTest extends ObservIntegrationTestBase {

    @Autowired
    private ConnectorHealthService connectors;
    @Autowired
    private SearchFreshnessService search;
    @Autowired
    private JobOperationsService jobs;
    @Autowired
    private ObservFixture fixture;

    @BeforeEach
    void reset() {
        fixture.reset();
    }

    // ==================================================================
    // connectors
    // ==================================================================

    @Nested
    @DisplayName("connector health")
    class Connectors {

        @Test
        @DisplayName("credential presence is a boolean the database computes, and no value is selected")
        void credentialsArePresenceOnly() {
            fixture.connection(ObservFixture.ORG_A, ObservFixture.PROJECT_A, "WHATSAPP",
                "{\"authToken\":\"" + ObservFixture.CREDENTIAL_MARKER + "\"}",
                "whsec-" + ObservFixture.CREDENTIAL_MARKER, "CONNECTED", null);
            TenantContext admin = ObservPrincipalScope.adminA();

            var view = ObservPrincipalScope.call(admin, () -> connectors.connectors(admin, null));
            var connection = view.document().get("connections");

            assertThat(connection).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.LIST).hasSize(1);
            String rendered = String.valueOf(connection);
            assertThat(rendered)
                .as("the credential is in the database; it must not be in the panel")
                .doesNotContain(ObservFixture.CREDENTIAL_MARKER);
            assertThat(com.firstelement.channels.persistence.ChannelConnectionRepository
                .ConnectionDescription.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("credentials", "webhookSecret");
        }

        @Test
        @DisplayName("a connection with no credential is counted as missing, which is the operator's clue")
        void missingCredentialsAreCounted() {
            fixture.connection(ObservFixture.ORG_A, ObservFixture.PROJECT_A, "WHATSAPP",
                null, null, "CONNECTED", null);
            fixture.connection(ObservFixture.ORG_A, ObservFixture.PROJECT_A, "TELEGRAM",
                "{\"token\":\"present\"}", null, "CONNECTED", null);
            TenantContext admin = ObservPrincipalScope.adminA();

            var view = ObservPrincipalScope.call(admin, () -> connectors.connectors(admin, null));

            var summary = (ConnectorHealthRepository.ConnectorSummary) view.document().get("summary");
            assertThat(summary.connections()).isEqualTo(2);
            assertThat(summary.missingCredentials())
                .as("a connection with no credential is the operator's clue, and it is a count")
                .isEqualTo(1);
        }

        @Test
        @DisplayName("a last error is surfaced, because 'it looks fine' is the complaint operators bring")
        void lastErrorIsSurfaced() {
            fixture.connection(ObservFixture.ORG_A, ObservFixture.PROJECT_A, "WHATSAPP",
                "{\"token\":\"x\"}", null, "ERROR", "provider token revoked");
            TenantContext admin = ObservPrincipalScope.adminA();

            var view = ObservPrincipalScope.call(admin, () -> connectors.connectors(admin, null));

            assertThat(String.valueOf(view.document().get("connections")))
                .contains("provider token revoked");
            var summary = (ConnectorHealthRepository.ConnectorSummary) view.document().get("summary");
            assertThat(summary.inError()).isEqualTo(1);
            assertThat(summary.withLastError()).isEqualTo(1);
        }

        @Test
        @DisplayName("another tenant's connections are not on this panel")
        void crossTenantConnectorsAreAbsent() {
            fixture.connection(ObservFixture.ORG_A, ObservFixture.PROJECT_A, "WHATSAPP",
                "{\"token\":\"a\"}", null, "CONNECTED", null);
            fixture.connection(ObservFixture.ORG_B, ObservFixture.PROJECT_B, "WHATSAPP",
                "{\"token\":\"b\"}", null, "CONNECTED", null);
            TenantContext beta = ObservPrincipalScope.adminB();

            var view = ObservPrincipalScope.call(beta, () -> connectors.connectors(beta, null));

            assertThat(view.document().get("connections")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.LIST).hasSize(1);
            assertThat(String.valueOf(view.document())).doesNotContain("\"token\":\"b\"");
        }
    }

    // ==================================================================
    // webhooks
    // ==================================================================

    @Nested
    @DisplayName("webhook health")
    class Webhooks {

        @Test
        @DisplayName("a signature failure is counted with its code, and the body is never stored")
        void signatureFailuresAreCounted() {
            fixture.integrationEvent(ObservFixture.ORG_A, ObservFixture.PROJECT_A, "CONNECTOR",
                "WEBHOOK_RECEIVED", "SUCCESS", null, null, "corr-wh-1", null);
            fixture.integrationEvent(ObservFixture.ORG_A, ObservFixture.PROJECT_A, "CONNECTOR",
                "WEBHOOK_REJECTED", "FAILED", null, "SIGNATURE_MISMATCH", "corr-wh-1", null);
            fixture.integrationEvent(ObservFixture.ORG_A, ObservFixture.PROJECT_A, "CONNECTOR",
                "WEBHOOK_REJECTED", "FAILED", null, "SIGNATURE_MISMATCH", "corr-wh-2", null);
            TenantContext admin = ObservPrincipalScope.adminA();

            var view = ObservPrincipalScope.call(admin, () -> connectors.webhooks(admin, null));

            assertThat(view.document()).containsEntry("received", 1L);
            assertThat(view.document()).containsEntry("rejected", 2L);
            // One rejection is a misconfiguration; two thousand is an attack. The ratio is what
            // separates them.
            assertThat(view.document()).containsEntry("rejectionRatio", 2.0 / 3.0);
            assertThat(String.valueOf(view.document()))
                .contains("SIGNATURE_MISMATCH");
        }

        @Test
        @DisplayName("no webhooks at all yields a null ratio, not a confident zero")
        void noWebhooksIsANullRatio() {
            TenantContext admin = ObservPrincipalScope.adminA();

            var view = ObservPrincipalScope.call(admin, () -> connectors.webhooks(admin, null));

            assertThat(view.document()).containsEntry("received", 0L);
            assertThat(view.document()).containsEntry("rejected", 0L);
            assertThat(view.document())
                .as("a 0.0 rejection rate would be a claim about a period with no traffic")
                .containsEntry("rejectionRatio", null);
        }

        @Test
        @DisplayName("a provider rate limit is visible as a failure-code bucket")
        void rateLimitsAreVisible() {
            fixture.integrationEvent(ObservFixture.ORG_A, ObservFixture.PROJECT_A, "JOB",
                "OUTBOX_DELIVERED", "FAILED", "outbound-send", "PROVIDER_RATE_LIMIT",
                "corr-rl", null);
            TenantContext admin = ObservPrincipalScope.adminA();

            var view = ObservPrincipalScope.call(admin, () -> connectors.webhooks(admin, null));

            assertThat(String.valueOf(view.document().get("providerFailures")))
                .contains("PROVIDER_RATE_LIMIT");
        }

        @Test
        @DisplayName("another tenant's webhook traffic does not move this panel's counters")
        void crossTenantWebhookTrafficIsAbsent() {
            fixture.integrationEvent(ObservFixture.ORG_A, ObservFixture.PROJECT_A, "CONNECTOR",
                "WEBHOOK_REJECTED", "FAILED", null, "SIGNATURE_MISMATCH", "c", null);
            TenantContext beta = ObservPrincipalScope.adminB();

            var view = ObservPrincipalScope.call(beta, () -> connectors.webhooks(beta, null));

            assertThat(view.document()).containsEntry("rejected", 0L);
        }
    }

    // ==================================================================
    // search freshness
    // ==================================================================

    @Nested
    @DisplayName("vector freshness")
    class Freshness {

        @Test
        @DisplayName("a READY document with unembedded chunks is the failure the panel exists for")
        void readyButInvisibleToRetrieval() {
            // chunkCount says 3, two chunks exist, and one of the two has no embedding. Every status
            // column says READY; retrieval would return nothing for it.
            fixture.document(ObservFixture.ORG_A, ObservFixture.PROJECT_A,
                ObservFixture.KNOWLEDGE_BASE_A, "Indexed but not searchable", "READY",
                3, 2, 1, ObservFixture.now().minusHours(2));
            TenantContext admin = ObservPrincipalScope.adminA();

            var view = ObservPrincipalScope.call(admin,
                () -> search.freshness(admin, null, 1, null));

            assertThat(view.document()).containsEntry("pendingIndexChunks", 1L);
            assertThat(view.document())
                .as("the stored count and the real count disagree, and that disagreement is a bug "
                    + "report in itself")
                .containsEntry("chunkCountDisagreements", 1L);
            assertThat(view.document().get("documents")).asString()
                .contains("Indexed but not searchable");
        }

        @Test
        @DisplayName("every declared document state is present at zero")
        void everyStateIsPresent() {
            TenantContext admin = ObservPrincipalScope.adminA();

            var view = ObservPrincipalScope.call(admin,
                () -> search.freshness(admin, null, 1, null));

            assertThat(view.document().get("byStatus")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsKeys("UPLOADED", "PROCESSING", "INDEXING", "READY", "FAILED");
        }

        @Test
        @DisplayName("a document that stopped moving before READY is a stuck document")
        void stuckDocumentsAreReported() {
            fixture.document(ObservFixture.ORG_A, ObservFixture.PROJECT_A,
                ObservFixture.KNOWLEDGE_BASE_A, "Stalled upload", "PROCESSING",
                0, 0, 0, ObservFixture.now().minusHours(9));
            TenantContext admin = ObservPrincipalScope.adminA();

            var view = ObservPrincipalScope.call(admin,
                () -> search.freshness(admin, null, 1, null));

            @SuppressWarnings("unchecked")
            var stuck = (java.util.List<SearchFreshnessRepository.StuckDocument>)
                view.document().get("stuck");
            assertThat(stuck).hasSize(1);
            assertThat(stuck.get(0).title()).isEqualTo("Stalled upload");
            assertThat(stuck.get(0).status()).isEqualTo("PROCESSING");
            assertThat(stuck.get(0).idleSeconds()).isPositive();
        }

        @Test
        @DisplayName("no chunk content is selected, so no statement here can leak document text")
        void noChunkContentIsProjected() {
            fixture.document(ObservFixture.ORG_A, ObservFixture.PROJECT_A,
                ObservFixture.KNOWLEDGE_BASE_A, "Document", "READY", 2, 2, 0,
                ObservFixture.now());
            TenantContext admin = ObservPrincipalScope.adminA();

            var view = ObservPrincipalScope.call(admin,
                () -> search.freshness(admin, null, 1, null));

            assertThat(SearchFreshnessRepository.DocumentFreshness.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("content", "embedding", "text");
            assertThat(String.valueOf(view.document())).doesNotContain("seeded chunk text");
        }

        @Test
        @DisplayName("another tenant's documents do not move this panel")
        void crossTenantDocumentsAreAbsent() {
            fixture.document(ObservFixture.ORG_A, ObservFixture.PROJECT_A,
                ObservFixture.KNOWLEDGE_BASE_A, "Alpha doc", "READY", 2, 2, 0, ObservFixture.now());
            fixture.document(ObservFixture.ORG_B, ObservFixture.PROJECT_B,
                ObservFixture.KNOWLEDGE_BASE_B, "Beta doc", "FAILED", 5, 0, 0,
                ObservFixture.now().minusDays(5));
            TenantContext beta = ObservPrincipalScope.adminB();

            var view = ObservPrincipalScope.call(beta,
                () -> search.freshness(beta, null, 1, null));

            assertThat(String.valueOf(view.document().get("documents")))
                .contains("Beta doc")
                .doesNotContain("Alpha doc");
        }
    }

    // ==================================================================
    // knowledge gaps
    // ==================================================================

    @Nested
    @DisplayName("knowledge gaps")
    class Gaps {

        @Test
        @DisplayName("a gap is a count and a score; the query a customer typed is not shown")
        void gapCountsWithoutTheQuery() {
            fixture.knowledgeGap(ObservFixture.ORG_A, ObservFixture.PROJECT_A, 0.21, 0,
                ObservFixture.now().minusDays(3));
            fixture.knowledgeGap(ObservFixture.ORG_A, ObservFixture.PROJECT_A, 0.44, 2,
                ObservFixture.now().minusDays(1));
            TenantContext admin = ObservPrincipalScope.adminA();

            var view = ObservPrincipalScope.call(admin,
                () -> search.knowledgeGaps(admin, null, null, null));

            assertThat(view.document().get("gaps")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.LIST).hasSize(2);
            assertThat(String.valueOf(view.document()))
                .as("the query text exists in the table; a gap is by definition something a customer "
                    + "typed that matched nothing")
                .doesNotContain(ObservFixture.GAP_QUERY_MARKER);
            assertThat(SearchFreshnessRepository.KnowledgeGap.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("query", "text");

            var counts = (SearchFreshnessRepository.GapCounts) view.document().get("counts");
            assertThat(counts.total()).isEqualTo(2);
            assertThat(counts.unresolved()).isEqualTo(2);
            assertThat(counts.averageBestScore()).isNotNull();
        }

        @Test
        @DisplayName("an average score over zero gaps is null, not zero")
        void noGapsIsANullAverage() {
            TenantContext admin = ObservPrincipalScope.adminA();

            var view = ObservPrincipalScope.call(admin,
                () -> search.knowledgeGaps(admin, null, null, null));

            var counts = (SearchFreshnessRepository.GapCounts) view.document().get("counts");
            assertThat(counts.total()).isZero();
            assertThat(counts.averageBestScore())
                .as("an average over nothing is not zero; it is unmeasured")
                .isNull();
            assertThat(counts.oldestOpen()).isNull();
        }

        @Test
        @DisplayName("another tenant's gaps are not counted")
        void crossTenantGapsAreAbsent() {
            fixture.knowledgeGap(ObservFixture.ORG_A, ObservFixture.PROJECT_A, 0.2, 0,
                ObservFixture.now());
            fixture.knowledgeGap(ObservFixture.ORG_B, ObservFixture.PROJECT_B, 0.3, 0,
                ObservFixture.now());
            TenantContext beta = ObservPrincipalScope.adminB();

            var view = ObservPrincipalScope.call(beta,
                () -> search.knowledgeGaps(beta, null, null, null));

            assertThat(((SearchFreshnessRepository.GapCounts) view.document().get("counts")).total())
                .isEqualTo(1);
        }
    }

    // ==================================================================
    // realtime
    // ==================================================================

    @Nested
    @DisplayName("realtime health")
    class Realtime {

        @Test
        @DisplayName("multiInstanceCapable answers 'configured', and the panel says so")
        void configuredIsNotConnected() {
            TenantContext admin = ObservPrincipalScope.adminA();

            var view = ObservPrincipalScope.call(admin, () -> search.realtime(admin));

            // The bridge is off in this configuration, so the panel must not imply a live subscriber.
            assertThat(view.document()).containsEntry("scope", "PROCESS");
            assertThat(view.document()).containsKey("localSubscribers");
            assertThat(view.document()).containsKey("resyncsSent");
            assertThat(String.valueOf(view.document().get("note")))
                .as("a reader who treats 'configured' as 'connected' pages at 3am")
                .contains("configured");
        }

        @Test
        @DisplayName("the topic list is derived from the caller, so it cannot name a topic they may not use")
        void topicsComeFromTheCaller() {
            TenantContext admin = ObservPrincipalScope.adminA();
            TenantContext beta = ObservPrincipalScope.adminB();

            var alphaView = ObservPrincipalScope.call(admin, () -> search.realtime(admin));
            var betaView = ObservPrincipalScope.call(beta, () -> search.realtime(beta));

            String alphaTopics = String.valueOf(alphaView.document().get("yourTopics"));
            String betaTopics = String.valueOf(betaView.document().get("yourTopics"));

            assertThat(alphaTopics).contains(ObservFixture.PROJECT_A.toString());
            assertThat(alphaTopics).doesNotContain(ObservFixture.PROJECT_B.toString());
            assertThat(betaTopics).contains(ObservFixture.PROJECT_B.toString());
            assertThat(betaTopics).doesNotContain(ObservFixture.PROJECT_A.toString());
        }
    }

    // ==================================================================
    // scope refusal
    // ==================================================================

    @Nested
    @DisplayName("a project outside the caller's scope")
    class OutOfScope {

        @Test
        @DisplayName("is refused before a query is built, on the freshness and gap panels")
        void aForeignProjectIsRefused() {
            TenantContext admin = ObservPrincipalScope.adminA();
            UUID foreign = ObservFixture.PROJECT_B;

            assertThatThrownBy(() -> ObservPrincipalScope.call(admin,
                () -> search.freshness(admin, foreign, 1, null)))
                .isInstanceOf(com.firstelement.security.error.PolicyDeniedException.class);
            assertThatThrownBy(() -> ObservPrincipalScope.call(admin,
                () -> search.knowledgeGaps(admin, foreign, null, null)))
                .isInstanceOf(com.firstelement.security.error.PolicyDeniedException.class);
        }

        @Test
        @DisplayName("and on the job worklist, which is the panel that would expose another tenant's work")
        void aForeignProjectIsRefusedOnTheWorklistToo() {
            TenantContext admin = ObservPrincipalScope.adminA();
            TenantContext caller = admin;

            assertThatThrownBy(() -> ObservPrincipalScope.call(caller,
                () -> jobs.worklist(caller, ObservFixture.PROJECT_B, null, 50)))
                .isInstanceOf(com.firstelement.security.error.PolicyDeniedException.class);
        }
    }
}
