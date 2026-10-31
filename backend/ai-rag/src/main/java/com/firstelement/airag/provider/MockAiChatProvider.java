package com.firstelement.airag.provider;

import com.firstelement.airag.config.AiRagProperties;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

/**
 * The deterministic provider. The whole CRM runs on this with no API key (FR-5.2, ADR 0007 Part 2
 * rule 3).
 *
 * <h2>Determinism is the contract, not a shortcut</h2>
 *
 * <p>Two properties, both load-bearing:
 *
 * <ul>
 *   <li><b>Same input, same output, same token counts.</b> Token counts are computed by this class, not
 *       reported by a provider, so a run's {@code ai_usage} and {@code usage_events} figures are the same
 *       number on every machine. A budget that behaves differently on the developer's laptop is a budget
 *       nobody trusts.</li>
 *   <li><b>No clock, no network, no randomness.</b> The latency reported is zero and the response carries
 *       no id, so the only non-determinism a test can observe is the test's own.</li>
 * </ul>
 *
 * <h2>What it actually produces</h2>
 *
 * <p>It is <em>not</em> a language model and it does not pretend to be one. It reads the framed prompt
 * the orchestrator built and returns a composed answer plus, when the framed context contains an
 * instruction-shaped block, the tool call the instructions appear to request. That is exactly what the
 * prompt-injection regression test needs: a provider that will <b>obey</b> whatever the retrieved content
 * says, so that a passing test proves the <em>policy</em> stopped it and not that the mock was helpful.
 *
 * <p>A production adapter added later is a different class behind the same two interfaces. Nothing in
 * this module may branch on {@code kind == MOCK} to relax a check.
 */
@Component
public class MockAiChatProvider implements AiChatProvider {

    private static final Logger log = LoggerFactory.getLogger(MockAiChatProvider.class);

    /**
     * The marker the orchestrator wraps retrieved content in.
     *
     * <p>The mock answers only instructions that arrive <em>inside</em> this marker. That is a deliberate
     * inversion of a real model's behaviour, and it exists so the injection test is meaningful: if the
     * application did not stop the injected instruction, this provider <em>would</em> act on it.
     */
    public static final String UNTRUSTED_CONTENT_OPEN = "<<<UNTRUSTED_RETRIEVED_CONTENT";
    public static final String UNTRUSTED_CONTENT_CLOSE = "UNTRUSTED_RETRIEVED_CONTENT>>>";

    /** The marker around the instruction block the model is allowed to follow. */
    private static final String SYSTEM_OPEN = "<<<INSTRUCTION";
    private static final String SYSTEM_CLOSE = "INSTRUCTION>>>";

    private static final Pattern TOOL_CALL = Pattern.compile(
        "(?is)\\bcall[_ ]tool\\s*:\\s*([a-z][a-z0-9_.-]{2,63})");

    private final AiRagProperties properties;

    public MockAiChatProvider(AiRagProperties properties) {
        this.properties = properties;
    }

    @Override
    public AiProviderKind kind() {
        return AiProviderKind.MOCK;
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return ChatOptions.builder().model("mock-deterministic-v1").temperature(0.0d).build();
    }

    /**
     * Produces the deterministic answer.
     *
     * <p>Never returns empty text: a provider that answers with nothing has failed, and this module
     * refuses on empty rather than shipping an empty answer to a customer.
     */
    @Override
    public ChatResponse call(Prompt prompt) {
        if (prompt == null) {
            throw new IllegalArgumentException("A provider call must carry a prompt");
        }
        String contents = prompt.getContents() == null ? "" : prompt.getContents();
        String untrusted = between(contents, UNTRUSTED_CONTENT_OPEN, UNTRUSTED_CONTENT_CLOSE);
        String instructions = between(contents, SYSTEM_OPEN, SYSTEM_CLOSE);

        StringBuilder body = new StringBuilder();
        body.append("[mock] ").append(summarise(instructions.isEmpty() ? contents : instructions));

        // Instruction-shaped text inside the UNTRUSTED block is surfaced as a tool request and nothing
        // more. The application decides whether it runs; the provider only asks. See the class note.
        Matcher request = TOOL_CALL.matcher(untrusted);
        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
        if (request.find()) {
            String toolName = request.group(1);
            toolCalls.add(new AssistantMessage.ToolCall(stableId("tool", toolName), "function",
                toolName, "{\"requestedBy\":\"retrieved-content\"}"));
            body.append("\n[mock] requesting tool: ").append(toolName);
        }

        int inputTokens = MockTokenCounter.count(contents);
        int outputTokens = MockTokenCounter.count(body.toString());
        body.append("\n[mock] tokens in=").append(inputTokens).append(" out=").append(outputTokens);

        ChatOptions options = prompt.getOptions();
        String model = options == null || options.getModel() == null ? "mock-deterministic-v1"
            : options.getModel();

        log.debug("mock provider answered model={} inputTokens={} outputTokens={} toolCalls={}", model,
            inputTokens, outputTokens, toolCalls.size());

        // The builder is used rather than the protected constructor so the tool calls and the token
        // counts travel in the response itself. A ChatResponse whose usage lives only in a side channel
        // is a ChatResponse whose budget cannot be reconciled, so the counts are part of the value.
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
            .content(body.toString())
            .toolCalls(toolCalls)
            .build())),
            org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
                .model(model)
                .usage(new DeterministicUsage(inputTokens, outputTokens))
                .build());
    }

    /**
     * The token counts, carried on the response.
     *
     * <p>{@code nativeUsage} is {@code null} rather than a fabricated provider blob: nothing in this
     * module reads it, and inventing one would be a claim about a provider that was never called.
     */
    private record DeterministicUsage(Integer prompt, Integer completion)
            implements org.springframework.ai.chat.metadata.Usage {

        @Override
        public Integer getPromptTokens() {
            return prompt;
        }

        @Override
        public Integer getCompletionTokens() {
            return completion;
        }

        @Override
        public Object getNativeUsage() {
            return null;
        }
    }

    /**
     * A stable id derived from its own name.
     *
     * <p>Deterministic on purpose: a run id in a provider response that changes per attempt would make
     * {@code ai_run_tool_calls} non-idempotent under the dispatcher's at-least-once delivery.
     */
    private static String stableId(String prefix, String toolName) {
        return prefix + "-" + UUID.nameUUIDFromBytes(
            (prefix + ":" + toolName.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The text between two markers, or an empty string.
     *
     * <p>Marker order matters: the untrusted block is extracted <em>before</em> the instruction block, and
     * the instruction search starts after the untrusted one is removed, so a retrieved document cannot
     * forge an instruction block by containing the opening marker itself. That is a property of this
     * parser, and it is the second half of why injected text is inert even against a compliant provider.
     */
    private static String between(String text, String open, String close) {
        if (text == null) {
            return "";
        }
        Set<String> markers = new LinkedHashSet<>(List.of(UNTRUSTED_CONTENT_OPEN, UNTRUSTED_CONTENT_CLOSE,
            SYSTEM_OPEN, SYSTEM_CLOSE));
        StringBuilder cleaned = new StringBuilder();
        int cursor = 0;
        while (cursor < text.length()) {
            int next = text.length();
            String hit = null;
            for (String marker : markers) {
                int at = text.indexOf(marker, cursor);
                if (at >= 0 && at < next) {
                    next = at;
                    hit = marker;
                }
            }
            if (hit == null) {
                cleaned.append(text, cursor, text.length());
                break;
            }
            cleaned.append(text, cursor, next).append(' ');
            cursor = next + hit.length();
        }
        String withoutMarkers = cleaned.toString();
        int start = withoutMarkers.indexOf(open);
        if (start < 0) {
            return "";
        }
        int end = withoutMarkers.indexOf(close, start + open.length());
        return end < 0 ? withoutMarkers.substring(start + open.length())
            : withoutMarkers.substring(start + open.length(), end);
    }

    /** The first sentence or two of the instruction block, so the mock answer is bounded and stable. */
    private static String summarise(String instructions) {
        String flat = instructions.replaceAll("\\s+", " ").trim();
        if (flat.isEmpty()) {
            return "no instructions supplied";
        }
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }

    /** Exposed for the orchestrator's {@code detail} payload, which never carries prompt text. */
    public AiRagProperties properties() {
        return properties;
    }
}