package com.firstelement.airag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.airag.ingestion.KnowledgeIngestionService;
import com.firstelement.airag.orchestration.AiOrchestrator;
import com.firstelement.airag.orchestration.AiRunResult;
import com.firstelement.airag.policy.AiRefusalReason;
import com.firstelement.airag.policy.AiRefusedException;
import com.firstelement.airag.principal.AiPrincipalScope;
import com.firstelement.airag.retrieval.RetrievalVisibilityRepository;
import com.firstelement.security.TenantContext;
import com.firstelement.security.query.TenantScope;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The knowledge index: what becomes retrievable, when, and what stops being retrievable.
 *
 * <h2>Why "not yet" and "never again" need different tests</h2>
 *
 * <p>Retrieval visibility is a function of three things, and conflating them is how a RAG assistant ends
 * up serving deleted content or refusing to serve indexed content:
 *
 * <ul>
 *   <li>{@code documents."status" = 'READY'} — the source-visibility predicate, applied INSIDE the
 *       similarity statement, so an unindexed or failed document is invisible by construction rather than by
 *       a check somebody might forget;</li>
 *   <li>an embedding is present — a freshly uploaded document has a NULL vector;</li>
 *   <li>the tenant and project predicates — covered by {@code RetrievalTenantIsolationIntegrationTest} and
 *       repeated here once, because a stale-index bug that also widens scope is a worse bug.</li>
 * </ul>
 *
 * <h2>What "stale" means here</h2>
 *
 * <p>A reprocessed document. {@code KnowledgeIngestionService.ingest} deletes the previous chunks before
 * writing the new ones rather than appending, because two live vectors for one document is a retrieval that
 * can return content the document no longer says — and the old vector is customer content derived from text
 * that may since have been corrected. The test asserts the count, not just the newest chunk.
 */
@SpringBootTest(classes = AiRagTestApplication.class)
class KnowledgeIndexLifecycleIntegrationTest extends AiIntegrationTestBase {

    @Autowired
    private AiFixture fixture;

    @Autowired
    private AiOrchestrator orchestrator;

    @Autowired
    private KnowledgeIngestionService ingestion;

    @Autowired
    private RetrievalVisibilityRepository retrieval;

    private UUID knowledgeBase;
    private AiFixture.AgentVersion agent;
    private UUID conversation;
    private UUID triggerMessage;

    @BeforeEach
    void seed() {
        fixture.reset();
        knowledgeBase = fixture.knowledgeBase(AiFixture.ORG_A, AiFixture.PROJECT_A, "KB");
        agent = fixture.agentWithVersion(AiFixture.ORG_A, AiFixture.PROJECT_A, AiFixture.ADMIN_A,
            "MOCK", "mock-deterministic-v1", "[]", "[\"" + knowledgeBase + "\"]", 0.0d,
            "أجب من المصادر فقط");
        fixture.policy(AiFixture.ORG_A, AiFixture.PROJECT_A, "default", 4000, 4, "STRICT_CLINICAL",
            AiFixture.ADMIN_A);
        fixture.model(AiFixture.ORG_A, AiFixture.PROJECT_A, "MOCK", "mock-deterministic-v1", true,
            "LOCAL", false, "USD", "0.00000150", "0.00000600");
        conversation = fixture.conversation(AiFixture.ORG_A, AiFixture.PROJECT_A, "AI");
        triggerMessage = fixture.customerMessage(AiFixture.ORG_A, AiFixture.PROJECT_A, conversation,
            "ما ساعات العمل؟");
    }

    @Nested
    @DisplayName("what becomes retrievable")
    class Visibility {

        @Test
        @DisplayName("an UPLOADED document is invisible, however well its content would match")
        void uploadedIsInvisible() {
            UUID document = fixture.document(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase,
                "ساعات العمل", "UPLOADED");
            fixture.chunk(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, document, 0,
                "ساعات العمل من الأحد", "ساعات العمل");

            // Even though the chunk carries an embedding and the text is an exact match: the source
            // predicate is what makes an unindexed document invisible, and it lives inside the statement.
            assertThat(retrieve("ساعات العمل").empty()).isTrue();
        }

        @Test
        @DisplayName("a FAILED document is invisible, so a partial index cannot answer")
        void failedIsInvisible() {
            UUID document = fixture.document(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase,
                "فشل", "FAILED");
            fixture.chunk(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, document, 0,
                "ساعات العمل", "ساعات العمل");

            assertThat(retrieve("ساعات العمل").empty()).isTrue();
        }

        @Test
        @DisplayName("a chunk with a NULL embedding is invisible, because there is nothing to rank")
        void unembeddedChunkIsInvisible() {
            UUID document = fixture.document(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase,
                "جاهز", "READY");
            fixture.chunk(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, document, 0,
                "ساعات العمل من الأحد", null);

            assertThat(retrieve("ساعات العمل").empty()).isTrue();
        }

        @Test
        @DisplayName("a READY document with an embedding IS retrievable")
        void readyWithEmbeddingIsVisible() {
            UUID document = fixture.document(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase,
                "ساعات العمل", "READY");
            fixture.chunk(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, document, 0,
                "ساعات العمل من الأحد إلى الخميس", "ساعات العمل من الأحد");

            assertThat(retrieve("ساعات العمل").returnedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("a knowledge base the agent did not name is not searched, even when it would match")
        void unnamedKnowledgeBaseIsNotSearched() {
            UUID otherBase = fixture.knowledgeBase(AiFixture.ORG_A, AiFixture.PROJECT_A, "Not named");
            UUID document = fixture.document(AiFixture.ORG_A, AiFixture.PROJECT_A, otherBase, "ممتاز",
                "READY");
            fixture.chunk(AiFixture.ORG_A, AiFixture.PROJECT_A, otherBase, document, 0,
                "ساعات العمل ممتازة", "ساعات العمل");

            // The agent version names exactly one base; a retrieval that searched the tenant's whole corpus
            // would be a tenant-wide search no administrator approved.
            assertThat(retrieve("ساعات العمل").empty()).isTrue();
        }
    }

    @Nested
    @DisplayName("ingestion")
    class Ingest {

        @Test
        @DisplayName("ingestion walks UPLOADED to READY and leaves an embedded chunk behind")
        void ingestionMakesADocumentRetrievable() {
            UUID document = fixture.document(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase,
                "دليل", "UPLOADED");

            assertThat(retrieve("ساعات العمل").empty())
                .as("nothing indexed yet, so nothing is retrievable")
                .isTrue();

            KnowledgeIngestionService.IngestionResult result = ingestion.ingest(scopeA(), document,
                knowledgeBase, "ساعات العمل من الأحد إلى الخميس من nine صباحا حتى خمس مساء.");

            assertThat(result.ready()).isTrue();
            assertThat(result.chunks()).isPositive();
            assertThat(result.embedded()).isEqualTo(result.chunks());
            assertThat(documentStatus(document)).isEqualTo("READY");
            assertThat(retrieve("ساعات العمل").returnedCount()).isPositive();
        }

        @Test
        @DisplayName("the vector is written under the DOCUMENT's own tenant and project, never a global one")
        void embeddingsAreTenantScoped() {
            UUID document = fixture.document(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase,
                "دليل", "UPLOADED");
            ingestion.ingest(scopeA(), document, knowledgeBase, "ساعات العمل من الأحد.");

            String scope = fixture.text("""
                select c."organizationId"::text || '/' || c."projectId"::text
                  from document_chunks c where c."documentId" = :document
                """, Map.of("document", document));

            assertThat(scope).isEqualTo(AiFixture.ORG_A + "/" + AiFixture.PROJECT_A);
        }

        @Test
        @DisplayName("a REPROCESS REPLACES the chunks, so no stale vector survives the correction")
        void reprocessReplacesRatherThanAppends() {
            UUID document = fixture.document(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase,
                "دليل", "UPLOADED");
            // Long enough to split into several chunks, so "replaced" is not accidentally satisfied by a
            // single-chunk document that happens to overwrite itself.
            ingestion.ingest(scopeA(), document, knowledgeBase,
                "الفقرة الأولى عن المواعيد. " + "نص إضافي. ".repeat(200));
            int afterFirst = chunkCount(document);
            assertThat(afterFirst).isGreaterThan(1);

            ingestion.ingest(scopeA(), document, knowledgeBase, "ساعات العمل من الأحد فقط.");

            // Replace, not append: the old vectors are customer content derived from text that has since
            // been corrected, and two live vectors for one document is a retrieval that can return content
            // the document no longer says.
            assertThat(chunkCount(document))
                .as("the previous %d chunk(s) must be gone, not merely outranked", afterFirst)
                .isEqualTo(1);
            assertThat(fixture.text("""
                select c."content" from document_chunks c where c."documentId" = :document
                """, Map.of("document", document)))
                .as("and the surviving chunk is the corrected text, not the original")
                .contains("فقط");
        }

        @Test
        @DisplayName("chunking breaks on a sentence boundary and never drops content")
        void chunkingNeverDropsContent() {
            UUID document = fixture.document(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase,
                "دليل", "UPLOADED");
            String text = "الجملة الأولى عن المواعيد. الجملة الثانية عن الأسعار. الجملة الثالثة عن السياسات.";

            KnowledgeIngestionService.IngestionResult result = ingestion.ingest(scopeA(), document,
                knowledgeBase, text);

            assertThat(result.chunks()).isPositive();
            assertThat(totalContentLength(document))
                .as("Arabic is written with and without spaces between words, so a whitespace-word splitter "
                    + "loses text; every character has to survive the split")
                .isGreaterThanOrEqualTo(text.length() - result.chunks());
            for (String sentence : List.of("المواعيد", "الأسعار", "السياسات")) {
                assertThat(fixture.column("select c.\"content\" from document_chunks c where c.\"documentId\""
                    + " = :document", Map.of("document", document)))
                    .anyMatch(content -> content.contains(sentence));
            }
        }

        @Test
        @DisplayName("an EMPTY text produces no chunks and does NOT mark the document READY")
        void emptyTextIsNotReady() {
            UUID document = fixture.document(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase,
                "فارغ", "UPLOADED");

            KnowledgeIngestionService.IngestionResult result = ingestion.ingest(scopeA(), document,
                knowledgeBase, "   ");

            // A document with zero chunks marked READY reads as "indexed and empty", and an operator cannot
            // tell it apart from one whose extraction silently produced nothing.
            assertThat(result.chunks()).isZero();
            assertThat(result.embedded()).isZero();
            assertThat(retrieve("ساعات العمل").empty()).isTrue();
        }

        @Test
        @DisplayName("ingestion into another tenant's document is REFUSED, not silently ignored")
        void ingestionIsTenantScoped() {
            UUID document = fixture.document(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase,
                "tenant A", "UPLOADED");

            // A refused call, not a no-op. Every statement carries both tenant predicates, so the status
            // UPDATE matched zero rows; treating that as success wrote chunk rows whose organizationId and
            // projectId came from the CALLER's scope while their documentId pointed into tenant A. The
            // database had no reason to refuse them -- document_chunks_documentId_fkey is a single-column
            // reference to documents(id) -- and only the retrieval statement's tenant predicate kept the
            // result unreadable. Failing closed is the whole fix; the missing composite FK is reported as a
            // schema hook, because the chain is not this module's to change.
            assertThatThrownBy(() -> ingestion.ingest(scopeB(), document, knowledgeBase,
                "ساعات العمل من الأحد."))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No document")
                .hasMessageContaining("scope and the document disagree");

            assertThat(chunkCount(document)).isZero();
            assertThat(documentStatus(document)).isEqualTo("UPLOADED");
        }
    }

    @Nested
    @DisplayName("deletion propagation")
    class Deletion {

        @Test
        @DisplayName("a deleted document's chunks AND its row are gone, and nothing is retrievable")
        void deletionRemovesChunksAndTheDocument() {
            UUID document = fixture.document(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase,
                "سيُحذف", "READY");
            fixture.chunk(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, document, 0,
                "ساعات العمل من الأحد", "ساعات العمل");
            assertThat(retrieve("ساعات العمل").returnedCount()).isEqualTo(1);

            KnowledgeIngestionService.DeletionResult result = ingestion.propagateDeletion(scopeA(), document);

            assertThat(result.chunksRemoved()).isEqualTo(1);
            assertThat(result.documentRemoved()).isTrue();
            // The gate is "a deleted document is not retrievable once propagation completes", and a deletion
            // that removed the document row but left the chunks would leave the vectors reachable through a
            // knowledge-base query that names no document -- exactly the query retrieval makes.
            assertThat(retrieve("ساعات العمل").empty()).isTrue();
            assertThat(documentCount(document)).isZero();
            // The embedding went with them: a stored vector for deleted content is customer data with no owner.
            assertThat(fixture.count("""
                select 1 from document_chunks c where c."documentId" = :document
                """, Map.of("document", document))).isZero();
        }

        @Test
        @DisplayName("deleting one document does not touch another's")
        void deletionIsScoped() {
            UUID keep = fixture.document(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, "يبقى",
                "READY");
            UUID drop = fixture.document(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, "يُحذف",
                "READY");
            fixture.chunk(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, keep, 0, "ساعات العمل",
                "ساعات العمل");
            fixture.chunk(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, drop, 0, "الأسعار",
                "الأسعار");

            KnowledgeIngestionService.DeletionResult result = ingestion.propagateDeletion(scopeA(), drop);

            // Asserted on the ROWS rather than on a retrieval query, because the deterministic embedding puts
            // every pair of short strings at some non-zero similarity and a threshold-based "is it gone?"
            // query would be testing the embedding provider rather than the deletion.
            assertThat(result.chunksRemoved()).isEqualTo(1);
            assertThat(chunkCount(drop)).isZero();
            assertThat(chunkCount(keep))
                .as("the DELETE is scoped by documentId AND both tenant predicates, so a sibling document "
                    + "is not collateral damage")
                .isEqualTo(1);
            assertThat(documentCount(keep)).isEqualTo(1);
            assertThat(retrieve("ساعات العمل").returnedIds()).containsExactly(chunkIdOf(keep));
        }

        @Test
        @DisplayName("deleting another tenant's document removes NOTHING")
        void deletionCannotCrossTenants() {
            UUID documentA = fixture.document(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, "A",
                "READY");
            fixture.chunk(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, documentA, 0, "ساعات العمل",
                "ساعات العمل");
            UUID documentB = fixture.document(AiFixture.ORG_B, AiFixture.PROJECT_B,
                fixture.knowledgeBase(AiFixture.ORG_B, AiFixture.PROJECT_B, "B"), "B", "READY");
            fixture.chunk(AiFixture.ORG_B, AiFixture.PROJECT_B,
                fixture.knowledgeBase(AiFixture.ORG_B, AiFixture.PROJECT_B, "B2"), documentB, 0, "B",
                "B");

            KnowledgeIngestionService.DeletionResult result = ingestion.propagateDeletion(scopeA(), documentB);

            assertThat(result.chunksRemoved()).isZero();
            assertThat(result.documentRemoved()).isFalse();
            assertThat(documentCount(documentB))
                .as("tenant A's delete call must not remove tenant B's document")
                .isEqualTo(1);
        }

        @Test
        @DisplayName("deleting a document that is already gone is a no-op, not an error")
        void deletingTwiceIsSafe() {
            UUID document = fixture.document(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, "مكرر",
                "READY");
            fixture.chunk(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, document, 0, "ساعات",
                "ساعات");
            ingestion.propagateDeletion(scopeA(), document);

            KnowledgeIngestionService.DeletionResult second = ingestion.propagateDeletion(scopeA(), document);

            // At-least-once delivery means a retried deletion is normal; it must not throw.
            assertThat(second.chunksRemoved()).isZero();
            assertThat(second.documentRemoved()).isFalse();
        }
    }

    @Nested
    @DisplayName("what the operator can see")
    class Freshness {

        @Test
        @DisplayName("freshness separates embedded from unembedded, because they are different problems")
        void freshnessSeparatesTheTwoProblems() {
            UUID ready = fixture.document(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, "جاهز",
                "READY");
            fixture.chunk(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, ready, 0, "مفهرس",
                "مفهرس");
            UUID uploaded = fixture.document(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase,
                "غير مفهرس", "UPLOADED");
            fixture.chunk(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, uploaded, 0, "بلا متجه", null);

            RetrievalVisibilityRepository.Freshness report = retrieval.freshness(scopeA());

            assertThat(report.totalChunks()).isEqualTo(2);
            assertThat(report.embeddedChunks()).isEqualTo(1);
            assertThat(report.unembeddedChunks())
                .as("an unembedded chunk means the embedding job has not run; an empty corpus means "
                    + "nothing was uploaded. Same symptom for the customer, different fix for the operator")
                .isEqualTo(1);
            assertThat(report.readyDocuments()).isEqualTo(1);
        }

        @Test
        @DisplayName("a run with nothing indexed is refused as RETRIEVAL_EMPTY, not answered from nothing")
        void emptyCorpusIsARefusal() {
            assertThatThrownBy(() -> run())
                .isInstanceOf(AiRefusedException.class)
                .satisfies(thrown -> {
                    AiRefusedException refusal = (AiRefusedException) thrown;
                    // "Nothing indexed" and "everything below the threshold" are different conversations
                    // with an administrator, so they get different codes.
                    assertThat(refusal.reason()).isEqualTo(AiRefusalReason.RETRIEVAL_EMPTY);
                    assertThat(refusal.reason()).isNotEqualTo(AiRefusalReason.BELOW_MIN_SCORE);
                });
        }

        @Test
        @DisplayName("content that is indexed but below the threshold is BELOW_MIN_SCORE, a different code")
        void belowThresholdIsADifferentCode() {
            UUID document = fixture.document(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase,
                "غير مطابق", "READY");
            fixture.chunk(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, document, 0,
                "محتوى لا علاقة له بالسؤال", "محتوى لا علاقة له");
            // A similarity threshold no deterministic-embedding pair can reach, so the corpus is considered
            // and nothing qualifies -- the state the CONSIDERED query exists to distinguish.
            fixture.jdbcUpdate("""
                update ai_agent_versions set "minRetrievalScore" = cast(1.0 as double precision)
                 where "id" = :version
                """, Map.of("version", agent.versionId()));

            assertThatThrownBy(() -> run())
                .isInstanceOf(AiRefusedException.class)
                .satisfies(thrown -> assertThat(((AiRefusedException) thrown).reason())
                    .isEqualTo(AiRefusalReason.BELOW_MIN_SCORE));
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private AiRunResult run() {
        TenantContext principal = AiPrincipalScope.agentScopedTo(AiFixture.ORG_A, AiFixture.PROJECT_A);
        return orchestrator.run(new AiOrchestrator.RunRequest(principal, AiFixture.PROJECT_A, conversation,
            triggerMessage, agent.agentId(), agent.versionId(), "ما ساعات العمل؟"));
    }

    private com.firstelement.airag.retrieval.RetrievalOutcome retrieve(String query) {
        return retrieval.retrieve(scopeA(), List.of(knowledgeBase), query, 0.0d, 5);
    }

    private TenantScope scopeA() {
        return new TenantScope(AiFixture.ORG_A, AiFixture.PROJECT_A);
    }

    private TenantScope scopeB() {
        return new TenantScope(AiFixture.ORG_B, AiFixture.PROJECT_B);
    }

    private String documentStatus(UUID documentId) {
        return fixture.text("select d.\"status\"::text from documents d where d.\"id\" = :id",
            Map.of("id", documentId));
    }

    private int chunkCount(UUID documentId) {
        return fixture.count("select 1 from document_chunks c where c.\"documentId\" = :id",
            Map.of("id", documentId));
    }

    private int documentCount(UUID documentId) {
        return fixture.count("select 1 from documents d where d.\"id\" = :id", Map.of("id", documentId));
    }

    private UUID chunkIdOf(UUID documentId) {
        return fixture.uuidValue("select c.\"id\" from document_chunks c where c.\"documentId\" = :id"
            + " order by c.\"chunkIndex\" limit 1", Map.of("id", documentId));
    }

    private int totalContentLength(UUID documentId) {
        return fixture.intValue("""
            select coalesce(sum(length(c."content")), 0)::int from document_chunks c
             where c."documentId" = :id
            """, Map.of("id", documentId));
    }
}
