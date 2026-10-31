package com.firstelement.observ.search;

import com.firstelement.admin.audit.AuditAttribution;
import com.firstelement.security.query.ScopedWhere;
import com.firstelement.security.query.TenantScope;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Search and vector freshness: is the index current, what is pending, and what is still propagating.
 *
 * <h2>The four questions FR-6 asks</h2>
 *
 * <p>"search/vector freshness, pending index work, deletion propagation, and knowledge gaps" map onto
 * four answers that all exist as columns, and the reason the mapping is this easy is that Phase 2
 * built the ingestion state machine properly:
 *
 * <ul>
 *   <li><b>freshness</b> — {@code documents."status"} is a five-state machine
 *       (UPLOADED → PROCESSING → INDEXING → READY, or FAILED) and {@code "processedAt"} says when.
 *       A document sitting in {@code INDEXING} past the window is the freshness signal.</li>
 *   <li><b>pending index work</b> — chunks whose {@code embedding} is still null, counted per
 *       document. This is the number that says "retrieval will miss these".</li>
 *   <li><b>deletion propagation</b> — a document that is {@code READY} with zero chunks is the
 *       state a deletion leaves behind if propagation half-happened; and a chunk whose document has
 *       left the ready set is the other half.</li>
 *   <li><b>knowledge gaps</b> — {@code knowledge_gaps} is the table Phase 2 created for exactly this:
 *       a retrieval that found nothing above the threshold.</li>
 * </ul>
 *
 * <h2>No chunk content and no gap query text</h2>
 *
 * <p>{@code document_chunks."content"} is the indexed document text and {@code knowledge_gaps."query"}
 * is, by construction, something a customer typed that found nothing. Neither is selected: the
 * read model returns counts, states and timestamps, and {@code knowledge_gaps} is projected by id,
 * score and time only. An operator needs "how many gaps, how old, how well-scoring" — not the text.
 */
@Repository
public class SearchFreshnessRepository {

    /**
     * Per-document freshness.
     *
     * <p>The chunk counts are correlated subqueries rather than a join because a left join over
     * {@code document_chunks} would multiply the document rows and make the count wrong in a way that
     * only shows up on documents with many chunks.
     */
    private static final String DOCUMENTS = """
        select d."id", d."projectId", d."knowledgeBaseId", d."title", d."status"::text as "status",
               d."sourceType"::text as "sourceType", d."sizeBytes", d."chunkCount", d."failureReason",
               d."processedAt", d."createdAt", d."updatedAt",
               (select count(*) from document_chunks c
                 where c."documentId" = d."id") as actual_chunks,
               (select count(*) from document_chunks c
                 where c."documentId" = d."id" and c."embedding" is null) as unembedded_chunks
          from documents d
         where d."organizationId" = :__fe_tenant_organization_id
           and (cast(:filterProjectId as uuid) is null or d."projectId" = cast(:filterProjectId as uuid))
         order by d."updatedAt" desc, d."id" desc
         limit :limit
        """;

    /** Freshness by state, with the oldest stuck row in each state. */
    private static final String BY_STATUS = """
        select d."status"::text as status, count(*) as occurrences,
               min(d."createdAt") as oldest_created, max(d."updatedAt") as newest_updated
          from documents d
         where d."organizationId" = :__fe_tenant_organization_id
         group by d."status"
         order by d."status"
        """;

    /** Documents whose ingestion has not reached READY and are older than the window. */
    private static final String STUCK = """
        select d."id", d."projectId", d."title", d."status"::text as "status", d."failureReason",
               d."createdAt", d."updatedAt",
               extract(epoch from ((now() AT TIME ZONE 'UTC') - d."updatedAt"))::bigint
                 as idle_seconds
          from documents d
         where d."organizationId" = :__fe_tenant_organization_id
           and d."status" <> cast('READY' as "DocumentStatus")
           and d."updatedAt" < (now() AT TIME ZONE 'UTC') - make_interval(secs => :idleSeconds)
         order by d."updatedAt"
         limit :limit
        """;

    /**
     * Knowledge gaps.
     *
     * <p>No {@code "query"} column: it is the text a customer typed that matched nothing, and the
     * count, the best score and the age are what an operator acts on.
     */
    private static final String GAPS = """
        select g."id", g."projectId", g."conversationId", g."bestScore", g."retrievedCount",
               g."resolvedAt", g."createdAt"
          from knowledge_gaps g
         where g."organizationId" = :__fe_tenant_organization_id
           and (cast(:filterProjectId as uuid) is null
                or g."projectId" = cast(:filterProjectId as uuid))
           and (cast(:onlyOpen as boolean) is null or (g."resolvedAt" is null) = cast(:onlyOpen as boolean))
         order by g."createdAt" desc, g."id" desc
         limit :limit
        """;

    /** Knowledge-gap tallies. */
    private static final String GAP_COUNTS = """
        select count(*) as total,
               count(*) filter (where "resolvedAt" is null) as unresolved,
               avg("bestScore")::double precision as average_best_score,
               min("createdAt") as oldest_open
          from knowledge_gaps g
         where g."organizationId" = :__fe_tenant_organization_id
        """;

    /**
     * Deletion-propagation evidence.
     *
     * <p>A document that claims {@code READY} while holding no chunks is the state a half-finished
     * deletion leaves behind: retrieval reports the document is indexable and finds nothing in it.
     * Counting it is the cheapest honest propagation check this schema supports.
     */
    private static final String PROPAGATION = """
        select (select count(*) from documents d
                 where d."organizationId" = :__fe_tenant_organization_id
                   and d."status" = cast('READY' as "DocumentStatus")
                   and not exists (select 1 from document_chunks c where c."documentId" = d."id"))
                 as ready_but_empty,
               (select count(*) from document_chunks c
                 where c."organizationId" = :__fe_tenant_organization_id
                   and not exists (select 1 from documents d
                                    where d."id" = c."documentId"
                                      and d."status" = cast('READY' as "DocumentStatus")))
                 as orphaned_chunks,
               (select count(*) from documents d
                 where d."organizationId" = :__fe_tenant_organization_id
                   and d."status" = cast('READY' as "DocumentStatus")
                   and d."chunkCount" > 0
                   and (select count(*) from document_chunks c where c."documentId" = d."id")
                       = 0)
                 as chunk_count_disagreements
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public SearchFreshnessRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One document's ingestion state and chunk counts. Never the chunk content. */
    public record DocumentFreshness(UUID id,
                                    UUID projectId,
                                    UUID knowledgeBaseId,
                                    String title,
                                    String status,
                                    String sourceType,
                                    Integer sizeBytes,
                                    int chunkCount,
                                    String failureReason,
                                    LocalDateTime processedAt,
                                    LocalDateTime createdAt,
                                    LocalDateTime updatedAt,
                                    long actualChunks,
                                    long unembeddedChunks) {

        /** Whether the stored chunk count agrees with what is actually there. */
        public boolean chunkCountAgrees() {
            return chunkCount == actualChunks;
        }

        /** Whether every chunk carries an embedding, i.e. retrieval can actually hit it. */
        public boolean fullyEmbedded() {
            return unembeddedChunks == 0;
        }
    }

    /** A document that has not reached READY and has stopped moving. */
    public record StuckDocument(UUID id,
                                UUID projectId,
                                String title,
                                String status,
                                String failureReason,
                                LocalDateTime createdAt,
                                LocalDateTime updatedAt,
                                long idleSeconds) {
    }

    /** One knowledge gap, without the query that produced it. */
    public record KnowledgeGap(UUID id,
                               UUID projectId,
                               UUID conversationId,
                               Double bestScore,
                               Integer retrievedCount,
                               LocalDateTime resolvedAt,
                               LocalDateTime createdAt) {
    }

    /** Deletion-propagation evidence. */
    public record Propagation(long readyButEmpty,
                              long orphanedChunks,
                              long chunkCountDisagreements) {
    }

    /** Knowledge-gap rollup. {@code averageBestScore} is null when there are no gaps. */
    public record GapCounts(long total,
                            long unresolved,
                            Double averageBestScore,
                            LocalDateTime oldestOpen) {
    }

    /** Per-document freshness, newest activity first. */
    public List<DocumentFreshness> documents(TenantScope scope, UUID projectId, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("limit", Math.max(1, limit)));
        parameters.addValue("filterProjectId", projectId);
        return jdbc.query(DOCUMENTS, parameters, (rs, rowNum) -> new DocumentFreshness(
            rs.getObject("id", UUID.class),
            rs.getObject("projectId", UUID.class),
            rs.getObject("knowledgeBaseId", UUID.class),
            rs.getString("title"),
            rs.getString("status"),
            rs.getString("sourceType"),
            rs.getObject("sizeBytes", Integer.class),
            rs.getInt("chunkCount"),
            rs.getString("failureReason"),
            rs.getObject("processedAt", LocalDateTime.class),
            rs.getObject("createdAt", LocalDateTime.class),
            rs.getObject("updatedAt", LocalDateTime.class),
            rs.getLong("actual_chunks"),
            rs.getLong("unembedded_chunks")));
    }

    /** Freshness by state. Every declared state is present, including at zero. */
    public Map<String, Object> byStatus(TenantScope scope) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of());
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String state : List.of("UPLOADED", "PROCESSING", "INDEXING", "READY", "FAILED")) {
            counts.put(state, 0L);
        }
        for (Map<String, Object> row : jdbc.queryForList(BY_STATUS, parameters)) {
            counts.put(String.valueOf(row.get("status")), row.get("occurrences"));
        }
        return counts;
    }

    /** Documents that stopped moving before reaching READY. */
    public List<StuckDocument> stuck(TenantScope scope, int idleSeconds, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("idleSeconds", Math.max(60, idleSeconds), "limit", Math.max(1, limit)));
        return jdbc.query(STUCK, parameters, (rs, rowNum) -> new StuckDocument(
            rs.getObject("id", UUID.class),
            rs.getObject("projectId", UUID.class),
            rs.getString("title"),
            rs.getString("status"),
            rs.getString("failureReason"),
            rs.getObject("createdAt", LocalDateTime.class),
            rs.getObject("updatedAt", LocalDateTime.class),
            rs.getLong("idle_seconds")));
    }

    /** Knowledge gaps. */
    public List<KnowledgeGap> gaps(TenantScope scope, UUID projectId, Boolean onlyOpen, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("limit", Math.max(1, limit)));
        parameters.addValue("filterProjectId", projectId);
        parameters.addValue("onlyOpen", onlyOpen);
        return jdbc.query(GAPS, parameters, (rs, rowNum) -> new KnowledgeGap(
            rs.getObject("id", UUID.class),
            rs.getObject("projectId", UUID.class),
            rs.getObject("conversationId", UUID.class),
            rs.getObject("bestScore", Double.class),
            rs.getObject("retrievedCount", Integer.class),
            rs.getObject("resolvedAt", LocalDateTime.class),
            rs.getObject("createdAt", LocalDateTime.class)));
    }

    /** Knowledge-gap rollup. */
    public GapCounts gapCounts(TenantScope scope) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of());
        return jdbc.queryForObject(GAP_COUNTS, parameters, (rs, rowNum) -> new GapCounts(
            rs.getLong("total"),
            rs.getLong("unresolved"),
            rs.getObject("average_best_score", Double.class),
            rs.getObject("oldest_open", LocalDateTime.class)));
    }

    /** Deletion-propagation evidence. */
    public Propagation propagation(TenantScope scope) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of());
        return jdbc.queryForObject(PROPAGATION, parameters, (rs, rowNum) -> new Propagation(
            rs.getLong("ready_but_empty"),
            rs.getLong("orphaned_chunks"),
            rs.getLong("chunk_count_disagreements")));
    }

    /** The default freshness window: an hour of no movement in an ingestion pipeline is a stall. */
    public static LocalDateTime defaultIdleThreshold(int hours) {
        return AuditAttribution.now().minusHours(Math.max(1, Math.min(hours, 8760)));
    }
}