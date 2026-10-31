package com.firstelement.airag.provider;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Provider kind to adapter. The only place a provider is chosen.
 *
 * <h2>Why a registry rather than injection order</h2>
 *
 * <p>Because two adapters must be able to coexist, and Spring resolves a single {@code AiChatProvider}
 * bean by type — so a second adapter either collides or silently replaces the first depending on scan
 * order. Keying by {@link AiChatProvider#kind()} makes the mapping explicit and makes a duplicate kind a
 * startup failure rather than a runtime surprise.
 *
 * <h2>Why "absent" and "unavailable" are different answers</h2>
 *
 * <p>Both end in a refusal, but they are different facts and an operator needs to tell them apart:
 *
 * <ul>
 *   <li><b>Absent</b> — no adapter for that kind exists in this build. This is the normal state with no
 *       API key, and it is exactly the case ADR 0007 Part 2 rule 3 requires to work.</li>
 *   <li><b>Unavailable</b> — an adapter exists but reports it cannot serve right now. A provider added
 *       later answers this when its credential is not supplied.</li>
 * </ul>
 *
 * <p>Both are {@link ProviderFailure#NOT_CONFIGURED} to the run, and both are recorded, so "why did the
 * run not use OpenAI?" is answerable from {@code ai_run_steps} without reading configuration.
 *
 * <h2>What this class never does</h2>
 *
 * <p>It never reads configuration for a credential, and it never logs a provider's identity beyond its
 * {@link AiChatProvider#describe()}. {@code ai_model_registry} has no credential column and this class is
 * the Java equivalent of that decision.
 */
@Component
public class AiProviderRegistry {

    private final Map<AiProviderKind, AiChatProvider> chat;
    private final Map<AiProviderKind, AiEmbeddingProvider> embeddings;

    /**
     * Built from whatever adapters are on the classpath.
     *
     * <p>A duplicate kind is a startup failure with a message naming both classes. Silently keeping the
     * first would make "which provider answered?" depend on classpath order, which is exactly the class
     * of bug that is invisible in one environment and present in another.
     */
    public AiProviderRegistry(List<AiChatProvider> chatProviders,
                              List<AiEmbeddingProvider> embeddingProviders) {
        this.chat = index(chatProviders, AiChatProvider::kind, "chat");
        this.embeddings = index(embeddingProviders, AiEmbeddingProvider::kind, "embedding");
    }

    private static <K extends Enum<K>, V> Map<K, V> index(List<V> values,
                                                          java.util.function.Function<V, K> key,
                                                          String label) {
        // EnumMap needs the enum class; the key function hides it, so the map is built through a plain
        // HashMap and rejected here if any key is not an AiProviderKind. The registry is constructed once
        // at startup, so the extra pass costs nothing and the type error cannot reach production.
        java.util.Map<K, V> byKey = new java.util.LinkedHashMap<>();
        for (V value : values == null ? List.<V>of() : values) {
            if (value == null) {
                continue;
            }
            V existing = byKey.put(key.apply(value), value);
            if (existing != null) {
                // The KIND is named, not just the two classes. Two adapters of the same class differing
                // only in a constructor argument — the ordinary shape for a test stub, and a plausible one
                // for a real adapter configured twice — produce an identical pair of class names, and a
                // message reading "X and X" tells an operator nothing about which provider is contested.
                throw new IllegalStateException("Two " + label + " providers claim the same kind "
                    + key.apply(value).name() + ": " + existing.getClass().getName() + " and "
                    + value.getClass().getName()
                    + ". Which provider answers would depend on classpath order.");
            }
        }
        return Map.copyOf(byKey);
    }

    /** The adapter for a kind, when one exists and it reports itself usable. */
    public Optional<AiChatProvider> chat(AiProviderKind kind) {
        AiChatProvider provider = kind == null ? null : chat.get(kind);
        return provider != null && provider.available() ? Optional.of(provider) : Optional.empty();
    }

    /** The adapter regardless of availability, for an operator read model that explains the refusal. */
    public Optional<AiChatProvider> declaredChat(AiProviderKind kind) {
        return kind == null ? Optional.empty() : Optional.ofNullable(chat.get(kind));
    }

    public Optional<AiEmbeddingProvider> embeddings(AiProviderKind kind) {
        AiEmbeddingProvider provider = kind == null ? null : embeddings.get(kind);
        return provider != null && provider.available() ? Optional.of(provider) : Optional.empty();
    }

    /** The embedding adapter that needs no credential, which is what ingestion falls back to. */
    public Optional<AiEmbeddingProvider> defaultEmbedding() {
        return embeddings.values().stream()
            .filter(AiEmbeddingProvider::available)
            .min(java.util.Comparator.comparing(provider -> provider.kind().requiresCredential()));
    }

    /** Every kind this build can serve, for the readiness report. Never a credential. */
    public Collection<AiProviderKind> declaredChatKinds() {
        return List.copyOf(chat.keySet());
    }

    public Collection<AiProviderKind> declaredEmbeddingKinds() {
        return List.copyOf(embeddings.keySet());
    }

    public boolean isKnown(AiProviderKind kind) {
        return kind != null && chat.containsKey(kind);
    }
}