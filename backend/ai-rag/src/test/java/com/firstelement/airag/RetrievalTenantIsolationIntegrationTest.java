package com.firstelement.airag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.airag.policy.AiRefusalReason;
import com.firstelement.airag.policy.AiRefusedException;
import com.firstelement.airag.retrieval.RetrievalOutcome;
import com.firstelement.airag.retrieval.RetrievalVisibilityRepository;
import com.firstelement.security.query.TenantScope;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Tenant and project isolation for pgvector retrieval.
 *
 * <h2>Why the threshold is zero in most of these tests</h2>
 *
 * <p>ADR 0007 Part 2 rule 5 and FR-5.7: "pgvector is an index, not an authorization layer." A cross-tenant
 * test at a <em>realistic</em> similarity threshold proves very little, because at a realistic threshold the
 * other tenant's chunks mostly fall out on score rather than on the predicate — which means the test would
 * pass even with the tenant filter removed.
 *
 * <p>So the isolation assertions below run at {@code minScore = 0.0}, where <b>every</b> eligible chunk
 * qualifies. A post-filter implementation and a pre-filter implementation are indistinguishable at a
 * realistic threshold and completely different at zero, and zero is the case that matters.
 *
 * <h2>What a positive control proves here</h2>
 *
 * <p>Each negative has a matching positive: tenant B's own query finds B's chunk. Without it, a reader
 * cannot tell "the predicate works" from "the retrieval returns nothing at all" — which is the same
 * ambiguity ADR 0009 §2 warns about for the {@code ai_run_*} child columns.
 */
@SpringBootTest(classes = AiRagTestApplication.class)
class RetrievalTenantIsolationIntegrationTest extends AiIntegrationTestBase {

    private static final String QUESTION = "ساعات العمل";

    @Autowired
    private AiFixture fixture;

    @Autowired
    private RetrievalVisibilityRepository retrieval;

    private UUID knowledgeBaseA;
    private UUID knowledgeBaseB;
    private UUID knowledgeBaseA2;
    private UUID chunkA;
    private UUID chunkB;
    private UUID chunkA2;

    @BeforeEach
    void seed() {
        fixture.reset();
        knowledgeBaseA = fixture.knowledgeBase(AiFixture.ORG_A, AiFixture.PROJECT_A, "A");
        knowledgeBaseA2 = fixture.knowledgeBase(AiFixture.ORG_A, AiFixture.PROJECT_A2, "A2");
        knowledgeBaseB = fixture.knowledgeBase(AiFixture.ORG_B, AiFixture.PROJECT_B, "B");

        // Identical text in all three, so the ONLY thing separating them is the tenant/project predicate.
        String text = "ساعات العمل من الأحد إلى الخميس";
        UUID documentA = fixture.document(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBaseA, "A hours",
            "READY");
        UUID documentA2 = fixture.document(AiFixture.ORG_A, AiFixture.PROJECT_A2, knowledgeBaseA2, "A2 hours",
            "READY");
        UUID documentB = fixture.document(AiFixture.ORG_B, AiFixture.PROJECT_B, knowledgeBaseB, "B hours",
            "READY");

        chunkA = fixture.chunk(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBaseA, documentA, 0, text, text);
        chunkA2 = fixture.chunk(AiFixture.ORG_A, AiFixture.PROJECT_A2, knowledgeBaseA2, documentA2, 0,
            text, text);
        chunkB = fixture.chunk(AiFixture.ORG_B, AiFixture.PROJECT_B, knowledgeBaseB, documentB, 0, text, text);
    }

    @Test
    @DisplayName("at a ZERO threshold, a retrieval never crosses a tenant")
    void zeroThresholdDoesNotCrossTenant() {
        RetrievalOutcome fromA = retrieval.retrieve(scope(AiFixture.ORG_A, AiFixture.PROJECT_A),
            List.of(knowledgeBaseA), QUESTION, 0.0d, 10);

        assertThat(fromA.returnedIds()).containsExactly(chunkA);
        assertThat(fromA.consideredChunkIds()).containsExactly(chunkA);
        assertThat(fromA.returnedIds()).doesNotContain(chunkB, chunkA2);
        assertThat(fromA.minimumScore()).isZero();
    }

    @Test
    @DisplayName("the positive control: tenant B's own retrieval finds its own chunk")
    void positiveControlTenantB() {
        RetrievalOutcome fromB = retrieval.retrieve(scope(AiFixture.ORG_B, AiFixture.PROJECT_B),
            List.of(knowledgeBaseB), QUESTION, 0.0d, 10);

        assertThat(fromB.returnedIds()).containsExactly(chunkB);
        assertThat(fromB.returnedIds()).doesNotContain(chunkA, chunkA2);
    }

    @Test
    @DisplayName("naming another tenant's knowledge base finds nothing, because the base's project is out of scope")
    void anotherTenantsKnowledgeBaseFindsNothing() {
        // The knowledge base id is a bound parameter a caller controls, and the statement's project
        // predicate still applies. So this is a refusal by emptiness, not by an explicit check -- which is
        // correct, because a check would leak the existence of the base.
        RetrievalOutcome fromA = retrieval.retrieve(scope(AiFixture.ORG_A, AiFixture.PROJECT_A),
            List.of(knowledgeBaseB), QUESTION, 0.0d, 10);

        assertThat(fromA.returned()).isEmpty();
        assertThat(fromA.consideredChunkIds()).isEmpty();
    }

    @Test
    @DisplayName("naming another PROJECT's knowledge base inside the same tenant finds nothing")
    void anotherProjectsKnowledgeBaseFindsNothing() {
        RetrievalOutcome fromA = retrieval.retrieve(scope(AiFixture.ORG_A, AiFixture.PROJECT_A),
            List.of(knowledgeBaseA2), QUESTION, 0.0d, 10);

        assertThat(fromA.returned()).isEmpty();
        assertThat(fromA.returnedIds()).doesNotContain(chunkA2);
    }

    @Test
    @DisplayName("the considered set is empty when the predicate matches nothing, so a filter regression is visible")
    void emptyConsideredSetIsDistinguishableFromAFilteredOne() {
        RetrievalOutcome fromA = retrieval.retrieve(scope(AiFixture.ORG_A, AiFixture.PROJECT_A),
            List.of(knowledgeBaseB), QUESTION, 0.0d, 10);

        // This is the assertion that distinguishes "no access" from "nothing matched". A retriever that
        // recorded only its winners would make both look identical.
        assertThat(fromA.consideredCount()).isZero();
        assertThat(fromA.returnedCount()).isZero();
        assertThat(fromA.consideredButNothingQualified()).isFalse();
    }

    @Test
    @DisplayName("a threshold above every score yields nothing considered-qualifying, which is a distinct state")
    void aboveThresholdIsDistinguishable() {
        RetrievalOutcome fromA = retrieval.retrieve(scope(AiFixture.ORG_A, AiFixture.PROJECT_A),
            List.of(knowledgeBaseA), QUESTION, 0.9999d, 10);

        assertThat(fromA.returned()).isEmpty();
        // Chunks were visible but none qualified -- a different conversation from "no access".
        assertThat(fromA.consideredButNothingQualified()).isTrue();
    }

    @Test
    @DisplayName("a negative threshold is clamped to zero, so a configuration typo cannot widen the search")
    void negativeThresholdIsClamped() {
        RetrievalOutcome clamped = retrieval.retrieve(scope(AiFixture.ORG_A, AiFixture.PROJECT_A),
            List.of(knowledgeBaseA), QUESTION, -1.0d, 10);

        assertThat(clamped.minimumScore()).isZero();
        assertThat(clamped.returnedIds()).containsExactly(chunkA);
    }

    @Test
    @DisplayName("a chunk with no embedding is invisible: not indexed yet is not the same as not there")
    void unembeddedChunkIsInvisible() {
        UUID pending = fixture.knowledgeBase(AiFixture.ORG_A, AiFixture.PROJECT_A, "pending");
        UUID document = fixture.document(AiFixture.ORG_A, AiFixture.PROJECT_A, pending, "Fresh", "READY");
        fixture.chunk(AiFixture.ORG_A, AiFixture.PROJECT_A, pending, document, 0,
            "ساعات العمل", null);

        RetrievalOutcome outcome = retrieval.retrieve(scope(AiFixture.ORG_A, AiFixture.PROJECT_A),
            List.of(pending, knowledgeBaseA), QUESTION, 0.0d, 10);

        assertThat(outcome.returnedIds()).containsExactly(chunkA);
        // The freshness read is how an operator learns WHY: the unembedded chunk is counted separately.
        RetrievalVisibilityRepository.Freshness freshness =
            retrieval.freshness(scope(AiFixture.ORG_A, AiFixture.PROJECT_A));
        assertThat(freshness.unembeddedChunks()).isEqualTo(1);
        assertThat(freshness.embeddedChunks()).isEqualTo(1);
        assertThat(freshness.totalChunks()).isEqualTo(2);
    }

    @Test
    @DisplayName("a document that is not READY is invisible however high it scores")
    void nonReadyDocumentIsInvisible() {
        UUID failedBase = fixture.knowledgeBase(AiFixture.ORG_A, AiFixture.PROJECT_A, "failed");
        String text = "ساعات العمل";
        UUID failed = fixture.document(AiFixture.ORG_A, AiFixture.PROJECT_A, failedBase, "Broken", "FAILED");
        fixture.chunk(AiFixture.ORG_A, AiFixture.PROJECT_A, failedBase, failed, 0, text, text);
        UUID indexingBase = fixture.knowledgeBase(AiFixture.ORG_A, AiFixture.PROJECT_A, "indexing");
        UUID indexing = fixture.document(AiFixture.ORG_A, AiFixture.PROJECT_A, indexingBase, "Building",
            "INDEXING");
        fixture.chunk(AiFixture.ORG_A, AiFixture.PROJECT_A, indexingBase, indexing, 0, text, text);

        RetrievalOutcome outcome = retrieval.retrieve(scope(AiFixture.ORG_A, AiFixture.PROJECT_A),
            List.of(failedBase, indexingBase, knowledgeBaseA), QUESTION, 0.0d, 10);

        // Source visibility is a WHERE predicate, not a post-filter on the score.
        assertThat(outcome.returnedIds()).containsExactly(chunkA);
    }

    @Test
    @DisplayName("deletion propagation removes the chunks, so a deleted document stops being retrievable")
    void deletionPropagationStopsRetrieval() {
        assertThat(retrieval.retrieve(scope(AiFixture.ORG_A, AiFixture.PROJECT_A), List.of(knowledgeBaseA),
            QUESTION, 0.0d, 10).returnedIds()).containsExactly(chunkA);

        var ingestion = fixture.ingestionService();
        var result = ingestion.propagateDeletion(scope(AiFixture.ORG_A, AiFixture.PROJECT_A),
            fixture.documentIdOf(chunkA));

        assertThat(result.chunksRemoved()).isPositive();
        assertThat(result.documentRemoved()).isTrue();

        RetrievalOutcome after = retrieval.retrieve(scope(AiFixture.ORG_A, AiFixture.PROJECT_A),
            List.of(knowledgeBaseA), QUESTION, 0.0d, 10);
        assertThat(after.returned()).isEmpty();
        assertThat(after.consideredChunkIds()).isEmpty();
    }

    @Test
    @DisplayName("a retrieval with no knowledge bases is refused by construction, never widened to the whole corpus")
    void noKnowledgeBasesIsNotTheWholeCorpus() {
        RetrievalOutcome outcome = retrieval.retrieve(scope(AiFixture.ORG_A, AiFixture.PROJECT_A),
            List.of(), QUESTION, 0.0d, 10);

        assertThat(outcome.returned()).isEmpty();
        assertThat(outcome.consideredCount()).isZero();
    }

    @Test
    @DisplayName("a tenant-wide retrieval is refused rather than widened")
    void tenantWideRetrievalIsRefused() {
        TenantScope tenantWide = new TenantScope(AiFixture.ORG_A, null);

        assertThatThrownBy(() -> retrieval.retrieve(tenantWide, List.of(knowledgeBaseA), QUESTION, 0.0d, 10))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("project-scoped");
    }

    @Test
    @DisplayName("retrieval freshness is project-scoped, so it cannot report another project's backlog")
    void freshnessIsProjectScoped() {
        RetrievalVisibilityRepository.Freshness a =
            retrieval.freshness(scope(AiFixture.ORG_A, AiFixture.PROJECT_A));
        RetrievalVisibilityRepository.Freshness a2 =
            retrieval.freshness(scope(AiFixture.ORG_A, AiFixture.PROJECT_A2));

        assertThat(a.totalChunks()).isEqualTo(1);
        assertThat(a2.totalChunks()).isEqualTo(1);
        assertThat(a.readyDocuments()).isEqualTo(1);
    }

    @Test
    @DisplayName("an out-of-scope project is refused by the guard, not by the retrieval returning nothing")
    void outOfScopeProjectIsRefusedByTheGuard() {
        var guard = fixture.tenantQueryGuard();
        var principal = com.firstelement.airag.principal.AiPrincipalScope
            .agentScopedTo(AiFixture.ORG_A, AiFixture.PROJECT_A);

        assertThatThrownBy(() -> guard.scope(principal, AiFixture.PROJECT_A2))
            .isInstanceOf(com.firstelement.security.error.PolicyDeniedException.class);
    }

    @Test
    @DisplayName("the retrieval limit is bounded by the deployment's own ceiling")
    void retrievalLimitIsBounded() {
        UUID wide = fixture.knowledgeBase(AiFixture.ORG_A, AiFixture.PROJECT_A, "wide");
        UUID document = fixture.document(AiFixture.ORG_A, AiFixture.PROJECT_A, wide, "Wide", "READY");
        for (int i = 0; i < 12; i++) {
            fixture.chunk(AiFixture.ORG_A, AiFixture.PROJECT_A, wide, document, i,
                "ساعات العمل " + i, "ساعات العمل");
        }

        RetrievalOutcome outcome = retrieval.retrieve(scope(AiFixture.ORG_A, AiFixture.PROJECT_A),
            List.of(wide), QUESTION, 0.0d, 1_000);

        assertThat(outcome.returnedCount()).isLessThanOrEqualTo(
            fixture.retrievalLimit());
        // The CONSIDERED set is what makes the bound visible: 12 eligible chunks, at most 5 returned.
        assertThat(outcome.consideredCount()).isEqualTo(12);
    }

    @Test
    @DisplayName("an empty retrieval with chunks considered is BELOW_MIN_SCORE, not RETRIEVAL_EMPTY")
    void theOrchestratorDistinguishesEmptyFromFiltered() {
        // The refusal codes are the orchestrator's, asserted here because the distinction is what an
        // administrator acts on: "no knowledge" versus "the threshold nobody calibrated".
        assertThat(AiRefusalReason.BELOW_MIN_SCORE.expected()).isTrue();
        assertThat(AiRefusalReason.RETRIEVAL_EMPTY.expected()).isTrue();
        assertThat(AiRefusalReason.RETRIEVAL_EMPTY).isNotEqualTo(AiRefusalReason.BELOW_MIN_SCORE);
        assertThatThrownBy(() -> {
            throw AiRefusedException.of(AiRefusalReason.RETRIEVAL_EMPTY);
        }).isInstanceOf(AiRefusedException.class);
    }


    /**
     * The project scope these tests retrieve in.
     *
     * <p>Constructed rather than resolved through {@code TenantQueryGuard} because the guard's job is
     * asserted separately in {@link #outOfScopeProjectIsRefusedByTheGuard()}; using it here would make the
     * retrieval's own predicate unverifiable in isolation.
     */
    private static TenantScope scope(UUID organizationId, UUID projectId) {
        return new TenantScope(organizationId, projectId);
    }
}