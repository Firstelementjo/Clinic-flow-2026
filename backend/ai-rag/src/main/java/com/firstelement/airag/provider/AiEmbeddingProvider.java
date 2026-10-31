package com.firstelement.airag.provider;

import org.springframework.ai.embedding.EmbeddingModel;

/**
 * The embedding side of a provider, kept separate from {@link AiChatProvider}.
 *
 * <h2>Why they are not one interface</h2>
 *
 * <p>Because they genuinely fail independently and are configured independently. A tenant may run its
 * answers through one provider and its embeddings through a local model — that is the
 * {@code dataResidencyZone = LOCAL_ONLY} case, where customer content must never leave the deployment
 * even for the vector. Making them one interface would force a deployment that has no answer provider at
 * all to also declare an embedding provider, or to declare both and get whichever failure the single
 * {@code available()} reports.
 */
public interface AiEmbeddingProvider extends EmbeddingModel {

    /** Which provider kind produces these vectors. Also the dimension contract's owner. */
    AiProviderKind kind();

    /**
     * The vector width this adapter emits.
     *
     * <p>{@code document_chunks."embedding"} is {@code vector(1536)} in the verified baseline, so an
     * adapter whose width differs cannot write there at all. The ingestion service checks this before it
     * starts, and refuses rather than truncating or padding: a padded vector silently changes every
     * distance in the index while still looking like it worked.
     */
    default int dimensions() {
        return org.springframework.ai.embedding.EmbeddingModel.super.dimensions();
    }

    default boolean available() {
        return true;
    }
}