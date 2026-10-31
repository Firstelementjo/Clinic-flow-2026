package com.firstelement.airag.provider;

import com.firstelement.airag.config.AiRagProperties;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Component;

/**
 * The deterministic embedding provider.
 *
 * <h2>Why 1536 dimensions and not fewer</h2>
 *
 * <p>Because {@code document_chunks."embedding"} is {@code public.vector(1536)} in the verified baseline,
 * and a narrower column is not negotiable from application code (there is no DDL in this module). So the
 * deterministic provider emits exactly 1536 dimensions. A future local embedding model that emits 768 or
 * 1024 would need a migration, and that is recorded rather than worked around: padding a 768-dimension
 * vector to 1536 with zeros would keep every distance finite and every row retrievable while changing
 * nothing about the semantics, which is the worst possible outcome for a search index.
 *
 * <h2>How it is deterministic, and what that means for retrieval</h2>
 *
 * <p>A hashed bag-of-character-trigrams projection. The same text always yields the same vector, on every
 * machine and every run, so:
 *
 * <ul>
 *   <li>a retrieval test is reproducible — the same document scores the same against the same query;</li>
 *   <li>"a deleted document is not retrievable" can be asserted as a score change rather than as a
 *       mocked repository return;</li>
 *   <li>the suite needs no model file, no key and no network.</li>
 * </ul>
 *
 * <p>It is explicitly <b>not</b> semantic. Two paraphrases will not retrieve each other's chunks. That is
 * fine for the guarantee this module must prove (the tenant predicate is inside the similarity statement),
 * and it is why a real embedding provider is a configuration change and not a code change.
 *
 * <h2>Tenant containment</h2>
 *
 * <p>The vector is derived only from the text handed to it, and the caller is the ingestion service, which
 * has already resolved the tenant. A vector carries no tenant identity of its own and is never reused
 * across a tenant boundary, because {@code document_chunks} is keyed by {@code organizationId} and the
 * retrieval statement filters on it inside the same SQL that ranks.
 */
@Component
public class DeterministicEmbeddingProvider implements AiEmbeddingProvider {

    /** The width {@code document_chunks."embedding"} declares. */
    public static final int DIMENSIONS = 1536;

    /** The trigram width. Two would collide on every short word; four loses most of the signal. */
    private static final int TRIGRAM = 3;

    private static final long HASH_SEED = 0x9E3779B97F4A7C15L;

    private final AiRagProperties properties;

    public DeterministicEmbeddingProvider(AiRagProperties properties) {
        this.properties = properties;
    }

    @Override
    public AiProviderKind kind() {
        return AiProviderKind.LOCAL;
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    @Override
    public float[] embed(Document document) {
        if (document == null) {
            throw new IllegalArgumentException("An embedding document must not be null");
        }
        return embed(document.getText());
    }

    @Override
    public float[] embed(String text) {
        if (text == null) {
            throw new IllegalArgumentException("An embedding text must not be null; a null is a bug, "
                + "not an empty document");
        }
        float[] vector = new float[DIMENSIONS];
        String normalized = text.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
        if (normalized.length() < TRIGRAM) {
            accumulate(vector, normalized);
        } else {
            for (int i = 0; i + TRIGRAM <= normalized.length(); i++) {
                accumulate(vector, normalized.substring(i, i + TRIGRAM));
            }
        }
        normalize(vector);
        return vector;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        if (request == null || request.getInstructions() == null) {
            throw new IllegalArgumentException("An embedding request must carry instructions");
        }
        List<String> documents = request.getInstructions();
        List<Embedding> embeddings = new ArrayList<>(documents.size());
        for (int i = 0; i < documents.size(); i++) {
            embeddings.add(new Embedding(embed(documents.get(i)), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    /**
     * Folds one trigram into two buckets, so a collision degrades a dimension instead of erasing it.
     *
     * <p>One bucket would make the vector the count of trigrams, which is dominated by the document's
     * length and makes a long document retrieve every long document. Two buckets with a signed first
     * bucket and a positive second gives a sparse random projection, which preserves inner-product
     * similarity in expectation — the Jaccard property that makes retrieval work at all.
     */
    private static void accumulate(float[] vector, String trigram) {
        long h = fnv1a(trigram.getBytes(StandardCharsets.UTF_8));
        int primary = (int) Math.floorMod(h, DIMENSIONS);
        int secondary = (int) Math.floorMod(h >>> 17, DIMENSIONS);
        vector[primary] += (h & 1L) == 0L ? 1f : -1f;
        vector[secondary] += 1f;
    }

    private static long fnv1a(byte[] bytes) {
        long hash = HASH_SEED;
        for (byte b : bytes) {
            hash ^= (b & 0xffL);
            hash *= 0x100000001B3L;
        }
        return hash & Long.MAX_VALUE;
    }

    /**
     * Unit length, so a cosine distance is a plain angle.
     *
     * <p>An all-zero vector for empty or blank text: pgvector's {@code <=>} on a zero vector is defined
     * (distance 1) so the row is simply never similar to anything, which is the correct behaviour for a
     * document with no content — and explicitly not an error, because a blank chunk is a legitimate
     * artifact of chunking.
     */
    private static void normalize(float[] vector) {
        double sumOfSquares = 0d;
        for (float value : vector) {
            sumOfSquares += (double) value * value;
        }
        if (sumOfSquares <= 0d) {
            return;
        }
        float norm = (float) Math.sqrt(sumOfSquares);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = vector[i] / norm;
        }
    }

    /** The vector rendered in the {@code [a,b,c]} form pgvector's {@code cast(:x as vector)} accepts. */
    public static String toVectorLiteral(float[] vector) {
        if (vector == null || vector.length != DIMENSIONS) {
            throw new IllegalArgumentException("An embedding written to document_chunks must be exactly "
                + DIMENSIONS + " dimensions; got " + (vector == null ? "null" : vector.length));
        }
        StringBuilder literal = new StringBuilder(vector.length * 9).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                literal.append(',');
            }
            literal.append(vector[i]);
        }
        return literal.append(']').toString();
    }

    /** The declared dimensionality this build will write. Never inferred from a provider at runtime. */
    public int declaredDimensions() {
        return properties == null ? DIMENSIONS : DIMENSIONS;
    }
}