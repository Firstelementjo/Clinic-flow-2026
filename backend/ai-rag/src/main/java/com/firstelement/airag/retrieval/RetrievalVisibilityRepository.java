package com.firstelement.airag.retrieval;

import com.firstelement.airag.config.AiRagProperties;
import com.firstelement.airag.provider.AiEmbeddingProvider;
import com.firstelement.airag.provider.AiProviderRegistry;
import com.firstelement.airag.provider.DeterministicEmbeddingProvider;
import com.firstelement.security.query.ScopedWhere;
import com.firstelement.security.query.TenantScope;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The pgvector retrieval statement, and the only place a similarity search is assembled in this codebase.
 *
 * <h2>The tenant predicate is inside the ranking statement. Not around it.</h2>
 *
 * <p>This is FR-5.7 and ADR 0007 Part 2 rule 5: "pgvector is an index, not an authorization layer." The
 * difference between the two shapes is not stylistic:
 *
 * <ul>
 *   <li><b>Post-filter</b> — {@code select ... order by embedding &lt;=&gt; query limit N}, then filter in
 *       Java. The N nearest vectors are taken across <em>all tenants</em> and then most of them are
 *       discarded. Tenant B's text was read into this process. It is not in the answer, and it did not have
 *       to be — it was loaded, scored, ranked and discarded, which is a disclosure that happened.</li>
 *   <li><b>Pre-filter inside the statement</b> — the {@code WHERE} clause carries the tenant, project,
 *       knowledge-base, visibility and embedding predicates, so the rows that are read are the rows that are
 *       eligible. {@code order by} then ranks only those.</li>
 * </ul>
 *
 * <p>There is a second, subtler reason, and it is the one the acceptance gate turns on: a post-filter
 * cannot be proved at a <b>zero similarity threshold</b>. With {@code minScore = 0} the post-filter keeps
 * everything and the two shapes coincide, so a test at a realistic threshold proves nothing about the
> zero-threshold case. In this statement there is no separate threshold stage — the threshold is the same
 * {@code WHERE} clause as the tenant predicate, so the zero-threshold proof is the <em>same statement</em>
 * as the normal case.
 *
 * <h2>Why {@code considered} exists</h2>
 *
 * <p>The statement returns both the full considered set (everything that passed every predicate, before
 * the limit) and the ranked winners. Without it, "retrieval returned nothing" and "the tenant predicate
 * silently matched nothing" are the same observation, and the second is exactly the failure mode ADR 0009
 * §2 warns about for the {@code ai_run_*} child tables: a filter that returns nothing <em>reads as</em> "no
 * access" rather than as a bug.
 *
 * <h2>Why {@code vector_freshness} is computed in the same statement</h2>
 *
 * <p>"Vector freshness must be visible" (FR-6: search/vector freshness, pending index work, deletion
 * propagation). The cheapest honest freshness signal is the newest chunk timestamp and the number of
 * chunks that are <em>not</em> embedded yet — a chunk with a NULL embedding cannot be retrieved, and a
 * corpus that is 40% unembedded is why an answer stopped finding things. Both come from the same
 * predicates, so they cannot disagree with what was actually searched.
 */
@Repository
public class RetrievalVisibilityRepository {

    private static final Logger log = LoggerFactory.getLogger(RetrievalVisibilityRepository.class);

    /**
     * The ranked winners.
     *
     * <p>{@code cast(:embedding as vector)} rather than a string parameter cast in Java: pgvector's operator
     * needs the type, and binding it as text with an explicit cast keeps the JDBC layer free of a
     * {@code PGobject} dependency on a driver version this build does not pin.
     *
     * <p>{@code left(c."content", :snippetChars)} bounds the customer content this statement returns. The
     * bound is a bound parameter so a future caller can shorten it — and it is the <em>only</em> knob,
     * because a retrieval that can return an unbounded column is a retrieval that can put an unbounded
     * amount of customer content into a prompt.
     *
     * <p>{@code d."status" = 'READY'} is the source-visibility predicate. It is deliberately on the
     * <b>documents</b> table and joined on both {@code id} and {@code organizationId}: a chunk whose
     * document row is FAILED or still INDEXING is not yet safe to quote, and joining on id alone would
     * let a chunk belong to a document in another tenant's shape.
     */
    private static final String RANKED = """
        select c."id"                        as "chunkId",
               c."documentId"               as "documentId",
               c."knowledgeBaseId"          as "knowledgeBaseId",
               d."title"                    as "documentTitle",
               left(c."content", :snippetChars) as "snippet",
               1 - (c."embedding" <=> cast(:embedding as vector)) as "score",
               d."status"::text             as "documentStatus",
               d."updatedAt"                as "documentUpdatedAt"
          from document_chunks c
          join documents d
            on d."id" = c."documentId"
           and d."organizationId" = c."organizationId"
         where c."organizationId" = :__fe_tenant_organization_id
           and c."projectId" = :__fe_tenant_project_id
           and c."knowledgeBaseId" in (:knowledgeBaseIds)
           and c."embedding" is not null
           and d."status" = cast('READY' as "DocumentStatus")
           and (1 - (c."embedding" <=> cast(:embedding as vector))) >= :minScore
         order by c."embedding" <=> cast(:embedding as vector) asc
         limit :limit
        """;

    /**
     * Everything that passed the TENANT and VISIBILITY predicates, ranked but neither thresholded nor
     * limited.
     *
     * <h2>Why the score threshold is deliberately absent here</h2>
     *
     * <p>Because this statement answers "what could this search have seen?", and a threshold would make the
     * answer "what did it see after filtering?" — which is the same question as {@link #RANKED} and therefore
     * worthless. The distinction matters because {@link RetrievalOutcome#consideredButNothingQualified()}
     * decides whether a refusal is {@code BELOW_MIN_SCORE} (chunks were visible, the threshold excluded
     * them) or {@code RETRIEVAL_EMPTY} (nothing was visible at all).
     *
     * <p>With the threshold here, the two collapse: a corpus whose every chunk scores below the bar would
     * report zero considered chunks and the operator would be told the knowledge base was empty. That is
     * the same "filter that returns nothing looks like no access" failure ADR 0009 §2 warns about, applied to
     * retrieval.
     *
     * <p>The limit is a constant rather than a parameter so a caller cannot turn this into a full dump of
     * the tenant's corpus; the ids are what the trace needs, not the content.
     */
    private static final String CONSIDERED = """
        select c."id" as "chunkId"
          from document_chunks c
          join documents d
            on d."id" = c."documentId"
           and d."organizationId" = c."organizationId"
         where c."organizationId" = :__fe_tenant_organization_id
           and c."projectId" = :__fe_tenant_project_id
           and c."knowledgeBaseId" in (:knowledgeBaseIds)
           and c."embedding" is not null
           and d."status" = cast('READY' as "DocumentStatus")
         order by c."embedding" <=> cast(:embedding as vector) asc
         limit 200
        """;

    /**
     * Freshness and index health for the trace.
     *
     * <p>Counted under the same tenant predicate, so "how many of my chunks are unembedded" cannot report
     * another tenant's backlog. {@code unembeddedChunks} is the number that cannot be retrieved at all —
     * a NULL embedding is invisible to the ranking statement, which is why a freshly uploaded document
     * that has not been embedded yet produces a correct empty result.
     */
    private static final String FRESHNESS = """
        select count(*)::int                                      as "totalChunks",
               count(c."embedding")::int                          as "embeddedChunks",
               (count(*) - count(c."embedding"))::int             as "unembeddedChunks",
               max(c."createdAt")                                  as "newestChunkAt",
               (select count(*)::int from documents d2
                 where d2."organizationId" = :__fe_tenant_organization_id
                   and d2."projectId" = :__fe_tenant_project_id
                   and d2."status" = cast('READY' as "DocumentStatus")) as "readyDocuments"
          from document_chunks c
         where c."organizationId" = :__fe_tenant_organization_id
           and c."projectId" = :__fe_tenant_project_id
        """;

    private final NamedParameterJdbcTemplate jdbc;
    private final AiProviderRegistry providers;
    private final AiRagProperties properties;

    public RetrievalVisibilityRepository(NamedParameterJdbcTemplate jdbc,
                                         AiProviderRegistry providers,
                                         AiRagProperties properties) {
        this.jdbc = jdbc;
        this.providers = providers;
        this.properties = properties;
    }

    /** What the trace records about the index, so staleness is visible next to the answer. */
    public record Freshness(int totalChunks,
                            int embeddedChunks,
                            int unembeddedChunks,
                            java.time.LocalDateTime newestChunkAt,
                            int readyDocuments) {
    }

    /**
     * Runs the retrieval.
     *
     * @param scope           a <b>project-scoped</b> scope. {@code document_chunks."projectId"} is NOT NULL,
     *                        so a tenant-wide retrieval is not expressible and is refused rather than
     *                        widened.
     * @param knowledgeBaseIds the agent version's knowledge bases, already restricted to this project; an
     *                         empty list is {@link RetrievalOutcome} with nothing considered, because a
     *                         search with no corpus is not a search
     * @param minScore        the threshold; clamped at zero so no configuration can make this statement
     *                        retrieve <em>more</em> than the tenant predicate allows
     */
    public RetrievalOutcome retrieve(TenantScope scope,
                                     List<UUID> knowledgeBaseIds,
                                     String query,
                                     double minScore,
                                     int limit) {
        if (!scope.isProjectScoped()) {
            // Refused rather than widened. The retrieval predicates are written against a project id, and
            // there is no overload that omits it: FR-1.3 has no tenant-wide vector search.
            throw new IllegalArgumentException(
                "pgvector retrieval is project-scoped; a tenant-wide scope cannot be expressed and is "
                    + "refused rather than widened.");
        }
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            return new RetrievalOutcome(List.of(), List.of(), 0, minScore,
                com.firstelement.airag.provider.SecretScrubber.fingerprint(query));
        }

        String embedding = embeddingLiteral(query);
        // Clamped at zero: a negative threshold is a configuration error, and treating it as a wider search
        // would make a typo in a policy row a tenant-scope relaxation.
        double threshold = Math.max(0.0d, minScore);
        int boundedLimit = Math.max(1, Math.min(limit, properties.getRetrievalLimit()));

        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(java.util.Map.of(
                "embedding", embedding,
                "knowledgeBaseIds", dedupe(knowledgeBaseIds),
                "minScore", threshold,
                "limit", boundedLimit,
                "snippetChars", RetrievedChunk.DEFAULT_SNIPPET_CHARS));

        List<RetrievedChunk> ranked = jdbc.query(RANKED, parameters, (rs, rowNum) -> new RetrievedChunk(
            rs.getObject("chunkId", UUID.class),
            rs.getObject("documentId", UUID.class),
            rs.getObject("knowledgeBaseId", UUID.class),
            rs.getString("documentTitle"),
            rs.getString("snippet"),
            rs.getDouble("score"),
            rs.getString("documentStatus"),
            rs.getObject("documentUpdatedAt", java.time.LocalDateTime.class)));

        // No threshold bound here on purpose -- see the CONSIDERED statement's note.
        List<UUID> considered = jdbc.query(CONSIDERED,
            ScopedWhere.of(scope).bindNamed(java.util.Map.of(
                "embedding", embedding,
                "knowledgeBaseIds", dedupe(knowledgeBaseIds))),
            (rs, rowNum) -> rs.getObject("chunkId", UUID.class));

        log.debug("pgvector retrieval org={} project={} considered={} returned={} threshold={}",
            scope.organizationId(), scope.projectId(), considered.size(), ranked.size(), threshold);
        return new RetrievalOutcome(considered, ranked, considered.size(), threshold,
            com.firstelement.airag.provider.SecretScrubber.fingerprint(query));
    }

    /**
     * The vector literal for a query, from whichever embedding adapter is reachable.
     *
     * <p>The default adapter needs no credential, which is what makes retrieval work with no provider key.
     * An adapter whose width is not 1536 is refused: {@code document_chunks."embedding"} is
     * {@code vector(1536)}, and a width mismatch is a schema error to be reported, not something to pad
     * around.
     */
    String embeddingLiteral(String query) {
        AiEmbeddingProvider adapter = providers.defaultEmbedding()
            .orElseThrow(() -> new IllegalStateException(
                "No embedding adapter is available; vector retrieval cannot run. The deterministic "
                    + "adapter needs no credential, so this state means the module is misconfigured."));
        int declared = adapter.dimensions();
        if (declared != DeterministicEmbeddingProvider.DIMENSIONS) {
            throw new IllegalStateException("The embedding adapter emits " + declared
                + " dimensions but document_chunks.\"embedding\" is vector("
                + DeterministicEmbeddingProvider.DIMENSIONS + "). Padding or truncating would change every "
                + "distance in the index while still appearing to work.");
        }
        return DeterministicEmbeddingProvider.toVectorLiteral(adapter.embed(query));
    }

    /** Freshness for the tenant's corpus. Never another tenant's. */
    public Freshness freshness(TenantScope scope) {
        if (!scope.isProjectScoped()) {
            throw new IllegalArgumentException("vector freshness is project-scoped");
        }
        return jdbc.queryForObject(FRESHNESS, ScopedWhere.of(scope).bindNamed(java.util.Map.of()),
            (rs, rowNum) -> new Freshness(
                rs.getInt("totalChunks"),
                rs.getInt("embeddedChunks"),
                rs.getInt("unembeddedChunks"),
                rs.getObject("newestChunkAt", java.time.LocalDateTime.class),
                rs.getInt("readyDocuments")));
    }

    /** Deduplicated, so a knowledge base named twice in a jsonb array does not double the result set. */
    private static List<UUID> dedupe(List<UUID> ids) {
        Set<UUID> unique = new LinkedHashSet<>();
        for (UUID id : ids) {
            if (id != null) {
                unique.add(id);
            }
        }
        return new ArrayList<>(unique);
    }
}