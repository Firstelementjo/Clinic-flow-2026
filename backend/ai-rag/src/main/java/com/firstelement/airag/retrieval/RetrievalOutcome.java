package com.firstelement.airag.retrieval;

import java.util.List;
import java.util.UUID;

/**
 * What one retrieval considered and what it returned.
 *
 * <h2>"Every retrieval is recorded with the ids it considered"</h2>
 *
 * <p>FR-5.7's traceability requirement, and the reason this type exists separately from the chunk list. A
 * retrieval that returned nothing is a <em>result</em>: "the tenant's corpus has nothing above the
 * threshold" and "the query was scoped to the wrong project" and "every chunk was deleted after the last
 * indexing run" are three different diagnoses, and only the first is a legitimate empty answer.
 *
 * <p>{@link #consideredChunkIds()} is what an operator reads to answer "why did this not find my
 * document?", and {@link #consideredCount()} against {@link #returnedCount()} is what proves the filter
 * ran at all. A retriever that recorded only its winners would make a filter regression invisible: an
 * empty result and a broken predicate look identical.
 *
 * @param consideredChunkIds every chunk that passed the tenant, project, knowledge-base, visibility and
 *                           embedding predicates, in score order, <em>before</em> the score threshold and
 *                           the limit were applied
 * @param returned           the chunks that became prompt context
 * @param consideredCount    the size of {@link #consideredChunkIds()}
 * @param minimumScore       the threshold in force, for the trace
 * @param queryFingerprint   a short non-reversible label for the query text, so two identical queries are
 *                           distinguishable in a log without the query itself ever being stored
 */
public record RetrievalOutcome(List<UUID> consideredChunkIds,
                               List<RetrievedChunk> returned,
                               int consideredCount,
                               double minimumScore,
                               String queryFingerprint) {

    public RetrievalOutcome {
        consideredChunkIds = consideredChunkIds == null ? List.of() : List.copyOf(consideredChunkIds);
        returned = returned == null ? List.of() : List.copyOf(returned);
    }

    public int returnedCount() {
        return returned.size();
    }

    public boolean empty() {
        return returned.isEmpty();
    }

    /**
     * Whether chunks were visible but all fell below the threshold.
     *
     * <p>The distinction the acceptance gate cares about: an empty result where the corpus is empty is a
     * legitimate "no knowledge"; an empty result where chunks were considered is a configuration problem
     * (a threshold nobody calibrated), and the refusal says so.
     */
    public boolean consideredButNothingQualified() {
        return returned.isEmpty() && !consideredChunkIds.isEmpty();
    }

    /** The chunk ids that became prompt context. Written to {@code ai_run_retrievals."chunkId"}. */
    public List<UUID> returnedIds() {
        return returned.stream().map(RetrievedChunk::chunkId).toList();
    }
}