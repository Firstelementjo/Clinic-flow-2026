package com.firstelement.airag.provider;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * A provider this build can actually reach.
 *
 * <h2>Why Spring AI's interfaces and not our own request/response records</h2>
 *
 * <p>FR-5.1 says "use Spring AI behind a FirstElement provider abstraction". So the wire types are
 * Spring AI's ({@code Prompt} in, {@code ChatResponse} out) and this module's own abstraction is the
 * thing that matters: {@link AiChatProvider} and {@link AiEmbeddingProvider} add exactly one
 * requirement Spring AI does not have, which is {@link #kind()}.
 *
 * <p>{@code kind()} is what makes the registry possible. Without it, two adapters for two providers
 * would be two beans of the same type with no way to say which one answers for {@code OPENAI} — and the
 * alternative, choosing by bean name or by injection order, is precisely the "delivery depends on bean
 * ordering" defect {@code JobHandlerRegistry} documents.
 *
 * <h2>What an adapter must never do</h2>
 *
 * <ul>
 *   <li><b>Return a credential, a header, or a request body</b> in an exception message. The failover
 *       executor scrubs what it persists, but an adapter that prints the key to {@code stderr} before
 *       throwing has already lost, and nothing downstream can undo it.</li>
 *   <li><b>Implement a retry loop.</b> Retries are the failover chain's decision, because a chain has a
 *       budget and a per-attempt loop does not know about it.</li>
 *   <li><b>Swallow an error into empty text.</b> A provider that returns an empty answer is a wrong
 *       answer, and this module must never convert "the provider failed" into "the model had nothing to
 *       say".</li>
 * </ul>
 */
public interface AiChatProvider extends ChatModel {

    /** Which {@code ai_model_registry."provider"} value this adapter answers for. */
    AiProviderKind kind();

    /**
     * Whether this adapter can serve the request at all right now.
     *
     * <p>Not "is it healthy" and not "is it reachable": a provider this build ships no credential for
     * answers {@code false}, and the registry turns that into {@link ProviderFailure#NOT_CONFIGURED}
     * rather than letting the attempt fail with a connection error. That distinction is what lets the
     * CRM run with no key and record <em>why</em>.
     */
    default boolean available() {
        return true;
    }

    /** A label safe to persist. Never a URL with a key in its query string, never a bean name. */
    default String describe() {
        return getClass().getSimpleName() + "(" + kind().wireValue() + ")";
    }
}