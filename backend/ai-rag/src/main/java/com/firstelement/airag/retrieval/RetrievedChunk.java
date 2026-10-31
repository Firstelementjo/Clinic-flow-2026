package com.firstelement.airag.retrieval;

import java.util.List;
import java.util.UUID;

/**
 * One retrieved chunk, as the retrieval statement returned it.
 *
 * <h2>Why the snippet is bounded here and not by the caller</h2>
 *
 * <p>{@code document_chunks."content"} is customer content and can be arbitrarily long. The statement
 * truncates it with {@code left(c."content", :snippetChars)} and the bound is a constant on this module,
 * not a parameter a caller may raise: a retrieval limit of 5 chunks each 10kB is 50kB of customer content
 * in a prompt, and the budget gate's token estimate is computed on exactly what is put in the prompt, so an
 * unbounded snippet is also an unbounded budget event.
 *
 * @param chunkId      the chunk that matched
 * @param documentId   the document it belongs to
 * @param knowledgeBaseId the knowledge base, which the agent version names
 * @param documentTitle the document title, for a citation
 * @param snippet      bounded content, already redacted before it reaches a prompt
 * @param score        cosine similarity in {@code [0,1]}; {@code 1 - (embedding <=> query)}
 * @param documentStatus the {@code documents."status"} at query time. Only READY is selected, so this is
 *                      always READY today and exists so a later visibility rule has a place to be visible
 * @param documentUpdatedAt when the document last changed, which is what makes index staleness checkable
 */
public record RetrievedChunk(UUID chunkId,
                             UUID documentId,
                             UUID knowledgeBaseId,
                             String documentTitle,
                             String snippet,
                             double score,
                             String documentStatus,
                             java.time.LocalDateTime documentUpdatedAt) {

    /** The default snippet bound. Deliberately a constant: see the record note. */
    public static final int DEFAULT_SNIPPET_CHARS = 900;

    /**
     * The citation a customer-facing answer carries.
     *
     * <p>A document title and a score, never the snippet. A citation is shown to an operator and often to
     * a customer, and a citation carrying the content it points at would defeat both the citation's
     * purpose and the redaction that was applied to the snippet.
     */
    public String citation() {
        return (documentTitle == null ? "document" : documentTitle) + " (score "
            + String.format(java.util.Locale.ROOT, "%.3f", score) + ")";
    }
}