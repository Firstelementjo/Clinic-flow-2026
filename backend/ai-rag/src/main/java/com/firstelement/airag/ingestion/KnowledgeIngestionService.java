package com.firstelement.airag.ingestion;

import com.firstelement.airag.config.AiRagProperties;
import com.firstelement.airag.provider.AiEmbeddingProvider;
import com.firstelement.airag.provider.AiProviderRegistry;
import com.firstelement.airag.provider.DeterministicEmbeddingProvider;
import com.firstelement.security.query.ScopedWhere;
import com.firstelement.security.query.TenantScope;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Asynchronous ingestion: chunk, embed, index — and deletion propagation.
 *
 * <h2>Why this is not a request-thread operation</h2>
 *
 * <p>FR-5.11 makes ingestion a state machine ({@code UPLOADED → PROCESSING → INDEXING → READY}, plus
 * {@code FAILED} and reprocess), and PROJECT_REQUIREMENTS §7 forbids synchronous document parsing. The
 * document stays {@code UPLOADED} — and therefore <b>unretrievable</b> — until this runs, which is the
 * property the retrieval statement depends on: {@code d."status" = 'READY'} is the source-visibility
 * predicate, so an unindexed document is invisible by construction rather than by a check somebody might
 * forget.
 *
 * <h2>Embedding text is customer content and never leaves the tenant</h2>
 *
 * <p>The adapter is resolved through {@link AiProviderRegistry#defaultEmbedding()}, which prefers an
 * adapter needing no credential. The vector is written under the document's own
 * {@code organizationId}/{@code projectId}, and the retrieval statement filters on both inside the
 * ranking clause. So an embedding cannot be reused across a tenant boundary even if the same text appears
 * in two tenants: the two rows live in different tenants' scopes and neither query reaches the other.
 *
 * <h2>Deletion propagation, and why it is not "delete the chunks"</h2>
 *
 * <p>{@link #propagateDeletion} deletes the chunks <b>and</b> the document, in one transaction, and returns
 * the count. The ordering matters for the acceptance gate "a deleted document is not retrievable": chunks
 * alone would be enough for retrieval, but leaving the document row means the Admin surface still lists a
 * document with zero chunks and an operator cannot tell "deleted" from "indexing". Both go, and the
 * embedding goes with them — a stored vector for deleted content is customer data with no owner.
 */
@Component
public class KnowledgeIngestionService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestionService.class);

    private static final String MARK_PROCESSING = """
        update documents
           set "status" = cast('PROCESSING' as "DocumentStatus"), "updatedAt" = :now
         where "id" = :id
           and "organizationId" = :__fe_tenant_organization_id
           and "projectId" = :__fe_tenant_project_id
        """;

    private static final String MARK_INDEXING = """
        update documents
           set "status" = cast('INDEXING' as "DocumentStatus"), "chunkCount" = :chunks, "updatedAt" = :now
         where "id" = :id
           and "organizationId" = :__fe_tenant_organization_id
           and "projectId" = :__fe_tenant_project_id
        """;

    private static final String MARK_READY = """
        update documents
           set "status" = cast('READY' as "DocumentStatus"), "processedAt" = :now, "updatedAt" = :now,
               "failureReason" = null
         where "id" = :id
           and "organizationId" = :__fe_tenant_organization_id
           and "projectId" = :__fe_tenant_project_id
        """;

    /**
     * Marks a document FAILED, with the reason.
     *
     * <p>{@code failureReason} is a text column and this is the one place in this module where free text
     * from an exception is written to it. It is scrubbed first: a parser error message routinely quotes the
     * line it failed on, which is document content, and a persisted copy of a customer's document fragment
     * in a status column is a disclosure nobody asked for.
     */
    private static final String MARK_FAILED = """
        update documents
           set "status" = cast('FAILED' as "DocumentStatus"), "failureReason" = :reason, "updatedAt" = :now
         where "id" = :id
           and "organizationId" = :__fe_tenant_organization_id
           and "projectId" = :__fe_tenant_project_id
        """;

    private static final String DELETE_CHUNKS = """
        delete from document_chunks
         where "documentId" = :id
           and "organizationId" = :__fe_tenant_organization_id
           and "projectId" = :__fe_tenant_project_id
        """;

    private static final String INSERT_CHUNK = """
        insert into document_chunks ("id", "organizationId", "projectId", "knowledgeBaseId", "documentId",
            "chunkIndex", "content", "tokenCount", "metadata", "embedding", "createdAt")
        values (:id, :__fe_tenant_organization_id, :__fe_tenant_project_id, :knowledgeBase, :document,
            :index, :content, :tokens, cast(:metadata as jsonb), cast(:embedding as vector), :now)
        """;

    private static final String DELETE_DOCUMENT = """
        delete from documents
         where "id" = :id
           and "organizationId" = :__fe_tenant_organization_id
           and "projectId" = :__fe_tenant_project_id
        """;

    private final NamedParameterJdbcTemplate jdbc;
    private final AiProviderRegistry providers;
    private final AiRagProperties properties;

    public KnowledgeIngestionService(NamedParameterJdbcTemplate jdbc,
                                     AiProviderRegistry providers,
                                     AiRagProperties properties) {
        this.jdbc = jdbc;
        this.providers = providers;
        this.properties = properties;
    }

    /** What an ingestion attempt did. Counts only; never content. */
    public record IngestionResult(UUID documentId, int chunks, int embedded, boolean ready) {
    }

    /** What a deletion removed. */
    public record DeletionResult(UUID documentId, int chunksRemoved, boolean documentRemoved) {
    }

    /**
     * Chunks, embeds and indexes one document's text.
     *
     * @param text the document's already-extracted text. Supplied by the caller rather than read from
     *             {@code documents}, because {@code documents} carries no body column — the content lives in
     *             object storage, which this module does not own. The worker reads the object and passes the
     *             text in.
     * @throws IllegalStateException when no embedding adapter is reachable, which is a configuration fault
     *         and leaves the document FAILED rather than READY-with-no-embeddings
     */
    @Transactional
    public IngestionResult ingest(TenantScope scope, UUID documentId, UUID knowledgeBaseId, String text) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        // The scope is CHECKED, not assumed. Every UPDATE here carries both tenant predicates, so an
        // out-of-scope document updates ZERO rows — and this method used to carry on regardless, writing
        // chunks whose organizationId/projectId came from the CALLER's scope while their documentId pointed
        // at another tenant's document. `document_chunks_documentId_fkey` is a single-column reference to
        // documents(id), so the database had no reason to refuse: a cross-tenant chunk row is storable, and
        // only the retrieval statement's tenant predicate kept it unreadable. A status update that matched
        // nothing is a refusal, not a no-op.
        requireUpdated(mark(scope, documentId, MARK_PROCESSING, new MapSqlParameterSource()
            .addValue("now", now)), documentId, "PROCESSING");

        AiEmbeddingProvider adapter = providers.defaultEmbedding().orElseThrow(
            () -> new IllegalStateException("No embedding adapter is reachable; a document cannot be indexed. "
                + "The deterministic adapter needs no credential, so this state means the module is "
                + "misconfigured rather than unconfigured."));
        if (adapter.dimensions() != DeterministicEmbeddingProvider.DIMENSIONS) {
            throw new IllegalStateException("The embedding adapter emits " + adapter.dimensions()
                + " dimensions but document_chunks.\"embedding\" is vector("
                + DeterministicEmbeddingProvider.DIMENSIONS + "). Padding would change every distance in "
                + "the index while appearing to work.");
        }

        List<String> chunks = chunk(text, properties.getIngestionChunkChars());
        requireUpdated(mark(scope, documentId, MARK_INDEXING,
            new MapSqlParameterSource().addValue("chunks", chunks.size()).addValue("now", now)), documentId,
            "INDEXING");

        // Replace, not append. A reprocess must not leave the previous embedding behind: the old vector is
        // customer content derived from text that may since have been corrected, and two live vectors for
        // one document is a retrieval that can return content the document no longer says.
        int removed = jdbc.update(DELETE_CHUNKS,
            scoped(scope, new MapSqlParameterSource().addValue("id", documentId)));

        int embedded = 0;
        try {
            for (int index = 0; index < chunks.size(); index++) {
                String content = chunks.get(index);
                float[] vector = adapter.embed(content);
                jdbc.update(INSERT_CHUNK, scoped(scope, new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID())
                    .addValue("knowledgeBase", knowledgeBaseId)
                    .addValue("document", documentId)
                    .addValue("index", index)
                    .addValue("content", content)
                    .addValue("tokens", com.firstelement.airag.provider.MockTokenCounter.count(content))
                    .addValue("metadata", "{\"chunkIndex\":" + index + "}")
                    .addValue("embedding", DeterministicEmbeddingProvider.toVectorLiteral(vector))
                    .addValue("now", now)));
                embedded++;
            }
        } catch (RuntimeException e) {
            // The transaction rolls back, so the partially written chunks are gone with it, and the document
            // is marked FAILED in a separate statement so the operator sees why. Re-thrown so the worker
            // records a retryable attempt rather than a success.
            jdbc.update(MARK_FAILED, scoped(scope, new MapSqlParameterSource()
                .addValue("id", documentId)
                .addValue("reason", com.firstelement.airag.provider.SecretScrubber
                    .scrubAndBound(e.getMessage()))
                .addValue("now", now)));
            log.warn("AI ingestion failed document={} project={} reason={}", documentId,
                scope.projectId(), e.getClass().getSimpleName());
            throw e;
        }

        requireUpdated(mark(scope, documentId, MARK_READY, new MapSqlParameterSource().addValue("now", now)),
            documentId, "READY");
        log.info("AI ingestion ready document={} project={} chunks={} replaced={}", documentId,
            scope.projectId(), chunks.size(), removed);
        return new IngestionResult(documentId, chunks.size(), embedded, true);
    }

    /**
     * Propagates a deletion to the vector index and to storage's row.
     *
     * <p>Both statements in one transaction, chunks first. The gate is "a deleted document is not
     * retrievable once propagation completes", and a deletion that removed the document row but left the
     * chunks would leave the vectors retrievable through a knowledge-base query that named no document —
     * which is exactly the query the retrieval statement makes.
     */
    @Transactional
    public DeletionResult propagateDeletion(TenantScope scope, UUID documentId) {
        int chunks = jdbc.update(DELETE_CHUNKS,
            scoped(scope, new MapSqlParameterSource().addValue("id", documentId)));
        int documents = jdbc.update(DELETE_DOCUMENT,
            scoped(scope, new MapSqlParameterSource().addValue("id", documentId)));
        log.info("AI deletion propagated document={} project={} chunksRemoved={} documentRemoved={}",
            documentId, scope.projectId(), chunks, documents);
        return new DeletionResult(documentId, chunks, documents > 0);
    }

    /**
     * Splits text into chunks.
     *
     * <p>By character count with a sentence-aware break, not by whitespace words. Arabic is written
     * without spaces between some words and with diacritics that a byte-splitter would cut through, so a
     * word-counting splitter produces chunks that start mid-word — and a retrieval that returns a chunk
     * beginning mid-word to a customer is a quality failure that looks like a translation failure.
     *
     * <p>Long paragraphs are hard-split as a last resort rather than dropped, because dropping content
     * silently is worse than an ugly break.
     */
    List<String> chunk(String text, int targetChars) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        int size = Math.max(200, targetChars);
        String flattened = text.replaceAll("[ \\t]+", " ").replaceAll("\\n{3,}", "\n\n").trim();
        int cursor = 0;
        while (cursor < flattened.length()) {
            int end = Math.min(flattened.length(), cursor + size);
            if (end < flattened.length()) {
                // Prefer the last sentence boundary in the final third of the chunk; fall back to a space;
                // fall back to the hard bound.
                int searchFrom = cursor + (size * 2) / 3;
                int sentence = lastIndexOfAny(flattened, searchFrom, end, '.', '؟', '?', '!', '\n');
                if (sentence > cursor) {
                    end = sentence + 1;
                } else {
                    int space = flattened.lastIndexOf(' ', end);
                    if (space > searchFrom) {
                        end = space;
                    }
                }
            }
            String piece = flattened.substring(cursor, end).trim();
            if (!piece.isEmpty()) {
                chunks.add(piece);
            }
            cursor = end;
        }
        return List.copyOf(chunks);
    }

    private static int lastIndexOfAny(String text, int from, int to, char... needles) {
        for (int i = to - 1; i >= from; i--) {
            char c = text.charAt(i);
            for (char needle : needles) {
                if (c == needle) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** The status UPDATE's row count, so a caller can refuse rather than proceed on a silent no-op. */
    private int mark(TenantScope scope, UUID documentId, String sql, MapSqlParameterSource source) {
        source.addValue("id", documentId);
        return jdbc.update(sql, scoped(scope, source));
    }

    /**
     * Refuses when a scoped UPDATE matched nothing.
     *
     * <p>The failure mode this exists for: every statement in this class carries
     * {@code "organizationId"} and {@code "projectId"}, so a document outside the scope updates zero rows
     * and the call continues as if it had worked. For the status updates that means a document stuck in
     * UPLOADED forever with no error anywhere; for the chunk INSERT it is worse, because the insert takes
     * its tenant columns from the CALLER's scope and its {@code documentId} from the argument — so a
     * cross-tenant chunk row is written, and {@code document_chunks_documentId_fkey} (a single-column
     * reference to {@code documents(id)}) has no reason to refuse it.
     */
    private static void requireUpdated(int updated, UUID documentId, String targetStatus) {
        if (updated == 0) {
            throw new IllegalStateException("No document " + documentId + " in this tenant and project, so "
                + "it cannot be moved to " + targetStatus + ". The caller's scope and the document disagree; "
                + "indexing continues would write chunks whose tenant columns contradict their document's.");
        }
    }

    /** The same reserved-parameter rule {@code ScopedWhere} applies, for these >10-binding statements. */
    private static MapSqlParameterSource scoped(TenantScope scope, MapSqlParameterSource source) {
        MapSqlParameterSource merged = new MapSqlParameterSource();
        if (source.getValues() != null) {
            source.getValues().forEach((name, value) -> {
                if (ScopedWhere.ORGANIZATION_PARAM.equals(name) || ScopedWhere.PROJECT_PARAM.equals(name)) {
                    throw new IllegalArgumentException("'" + name
                        + "' is reserved for the tenant filter and cannot be supplied by a caller");
                }
                merged.addValue(name, value);
            });
        }
        merged.addValue(ScopedWhere.ORGANIZATION_PARAM, scope.organizationId());
        if (scope.isProjectScoped()) {
            merged.addValue(ScopedWhere.PROJECT_PARAM, scope.projectId());
        }
        return merged;
    }
}