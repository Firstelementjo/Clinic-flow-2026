package com.firstelement.airag;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Shared container lifecycle for the AI suites.
 *
 * <h2>No skip path</h2>
 *
 * <p>The container starts in {@link #startContainer()} and every property supplier starts it first, so a
 * Docker problem fails the build instead of turning an AI suite green. There is no conditional skip and no
 * {@code @Disabled} anywhere in this module: a suite that needs a database has one, and a suite that needs a
 * provider key is <b>BLOCKED</b>, not skipped.
 *
 * <h2>Flyway is left enabled on purpose</h2>
 *
 * <p>These suites run against the real chain, including
 * {@code V20261005100000__ai_policy_and_governance},
 * {@code V20261005110000__ai_run_child_tenant_scope_columns} and
 * {@code V20261005120000__ai_run_child_tenant_scope_indexes}. Every statement in {@code backend/ai-rag} was
 * transcribed from those files column by column, so a column name that does not exist is a test failure here
 * rather than a surprise in production. That is the entire reason this base class exists.
 *
 * <h2>No provider key is configured, ever</h2>
 *
 * <p>FR-5.2 and ADR 0007 Part 2 rule 3. The suites run on the deterministic mock provider, and there is no
 * test that needs a key — so the "no provider key configured" acceptance gate is proved by construction
 * rather than by a property someone might set.
 */
abstract class AiIntegrationTestBase {

    @BeforeAll
    static void startContainer() {
        if (!AiFixture.POSTGRES.isRunning()) {
            AiFixture.POSTGRES.start();
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> {
            startContainer();
            return AiFixture.POSTGRES.getJdbcUrl();
        });
        registry.add("spring.datasource.username", () -> {
            startContainer();
            return AiFixture.POSTGRES.getUsername();
        });
        registry.add("spring.datasource.password", () -> {
            startContainer();
            return AiFixture.POSTGRES.getPassword();
        });
        registry.add("firstelement.environment", () -> "TEST");
        registry.add("firstelement.worker.dispatcher.enabled", () -> "false");
        registry.add("spring.data.redis.repositories.enabled", () -> "false");
        registry.add("firstelement.realtime.bridge-enabled", () -> "false");
        registry.add("firstelement.channels.mock-webhook-enabled", () -> "false");
        registry.add("firstelement.admin.evidence-dir", () -> "");
        // AI is switched ON, with no provider configured beyond the deterministic mock. A suite that needs
        // AI switched off sets it locally through the registry rather than here, so the default every
        // suite inherits is the acceptance gate's state.
        registry.add("firstelement.ai.enabled", () -> "true");
        registry.add("firstelement.ai.providers", () -> "MOCK");
    }
}