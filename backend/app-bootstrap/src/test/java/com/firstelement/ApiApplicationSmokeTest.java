package com.firstelement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.firstelement.airag.config.AiRagProperties;
import com.firstelement.airag.orchestration.AiOrchestrator;
import com.firstelement.airag.provider.AiProviderKind;
import com.firstelement.airag.provider.AiProviderRegistry;
import com.firstelement.airag.send.AiSendAuthorization;
import com.firstelement.airag.tools.AiToolExecutor;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "firstelement.environment=TEST",
    "management.health.db.enabled=false",
    "management.health.redis.enabled=false"
})
@AutoConfigureMockMvc
class ApiApplicationSmokeTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void statusEndpointAnswersWithVersion() throws Exception {
        mvc.perform(get("/api/v1/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.version").value("v1"));
    }

    @Test
    void correlationIdIsAssignedAndEchoed() throws Exception {
        mvc.perform(get("/api/v1/status"))
            .andExpect(status().isOk())
            .andExpect(header().exists("X-Correlation-Id"));
    }

    @Test
    void unknownRouteUsesStableErrorBody() throws Exception {
        mvc.perform(get("/api/v1/does-not-exist"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.requestId").exists());
    }

    /**
     * Phase 10: the AI graph is present in the ASSEMBLED application.
     *
     * <p>Phase 8's lesson, applied to this phase: a module's own suite proves its beans exist when its test
     * application scans its package, which says nothing about whether the production context finds them. If
     * {@code AiRagModule} were never processed -- the repository has no
     * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}, so an
     * {@code @AutoConfiguration} annotation alone is inert -- the module's 221 tests would stay green while
     * the deployed API had no AI at all. This test boots the real {@code ApiApplication} and asks.
     *
     * <p>It also asserts the no-provider-key acceptance gate on the deployed graph: the only reachable
     * provider is the deterministic mock, and no credential property exists to be set.
     */
    @Test
    void aiGraphIsWiredAndRunsWithNoProviderKey() {
        assertThat(orchestrator).as("AiOrchestrator is a production bean").isNotNull();
        assertThat(tools).as("AiToolExecutor is a production bean").isNotNull();
        assertThat(sendAuthorization).as("AiSendAuthorization is a production bean").isNotNull();
        assertThat(properties).as("firstelement.ai properties are registered").isNotNull();

        List<AiProviderKind> declared = List.copyOf(registry.declaredChatKinds());
        assertThat(declared)
            .as("ADR 0007 Part 2 rule 3: with no provider key the CRM stays fully usable, and the mock is "
                + "the only adapter that needs no credential")
            .containsExactly(AiProviderKind.MOCK);
        assertThat(registry.chat(AiProviderKind.MOCK)).isPresent();
        assertThat(registry.chat(AiProviderKind.OPENAI))
            .as("and no keyed provider is reachable, because no key is configured anywhere")
            .isEmpty();
        assertThat(orchestrator.enabled())
            .as("and the orchestrator itself answers for its own enabled state")
            .isTrue();
    }

    /** Exactly one adapter is registered, so the "duplicate kind" guard is not what is being observed. */
    @Test
    void theProviderRegistryHoldsOneAdapter() {
        assertThat(registry.declaredChatKinds()).hasSize(1);
        assertThat(registry.declaredChatKinds()).containsExactly(AiProviderKind.MOCK);
        assertThat(registry.defaultEmbedding())
            .as("ingestion has a reachable embedding adapter with no credential")
            .isPresent();
        // The adapter's own describe() is deliberately NOT asserted here: AiChatProvider extends Spring AI's
        // ChatModel, which :backend:ai-rag holds at `implementation` scope, so calling any method on an adapter
        // from this module would mean widening that scope to satisfy a test. The property it carries -- that
        // describe() never leaks a URL with a key in its query string -- is asserted by ProviderFailoverTest
        // inside the module that owns the type.
    }

    @Autowired
    private AiOrchestrator orchestrator;

    @Autowired
    private AiToolExecutor tools;

    @Autowired
    private AiSendAuthorization sendAuthorization;

    @Autowired
    private AiProviderRegistry registry;

    @Autowired
    private AiRagProperties properties;
}
