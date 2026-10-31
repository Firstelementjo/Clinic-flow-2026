package com.firstelement.observ;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Shared container lifecycle for the operations-observability suites.
 *
 * <h2>No skip path</h2>
 *
 * <p>The container starts in {@link #startContainer()} and every property supplier starts it first, so
 * a Docker problem fails the build instead of turning an operations suite green.
 *
 * <h2>Flyway is left enabled on purpose</h2>
 *
 * <p>These suites run against the real migration chain, so a column name that does not exist is a test
 * failure here rather than a surprise in production. That is the whole reason this phase's diagnosis
 * read models were written against the migrations rather than against a mental model of them.
 *
 * <h2>The dispatcher is off and the bridge is off</h2>
 *
 * <p>A running dispatcher would claim the rows a diagnosis test just seeded and make the worklist
 * non-deterministic. The realtime bridge stays off so the panel reads the same counters a broker-less
 * deployment reports rather than a live subscription count.
 */
@Import(ObservIntegrationTestBase.ObservTestConfig.class)
abstract class ObservIntegrationTestBase {

    @BeforeAll
    static void startContainer() {
        if (!ObservFixture.POSTGRES.isRunning()) {
            ObservFixture.POSTGRES.start();
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> {
            startContainer();
            return ObservFixture.POSTGRES.getJdbcUrl();
        });
        registry.add("spring.datasource.username", () -> {
            startContainer();
            return ObservFixture.POSTGRES.getUsername();
        });
        registry.add("spring.datasource.password", () -> {
            startContainer();
            return ObservFixture.POSTGRES.getPassword();
        });
        registry.add("firstelement.environment", () -> "TEST");
        registry.add("firstelement.worker.dispatcher.enabled", () -> "false");
        registry.add("spring.data.redis.repositories.enabled", () -> "false");
        registry.add("firstelement.channels.mock-webhook-enabled", () -> "false");
        registry.add("firstelement.realtime.bridge-enabled", () -> "false");
        registry.add("firstelement.admin.evidence-dir", () -> "");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ObservTestConfig {
    }
}
