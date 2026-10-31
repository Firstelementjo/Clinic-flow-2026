package com.firstelement.automation;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Shared container lifecycle for the DB-backed workflow suites.
 *
 * <h2>No skip path</h2>
 *
 * <p>The container starts in {@link #startContainer()} and every property supplier starts it first, so
 * a Docker problem fails the build instead of turning the human-takeover concurrency test green.
 *
 * <h2>Flyway is left enabled on purpose</h2>
 *
 * <p>These suites run against the real migration chain, including the four Phase-6a migrations, so a
 * column name that does not exist is a compile-time failure here rather than a surprise in production.
 */
@Import(WorkflowIntegrationTestBase.WorkflowTestConfig.class)
abstract class WorkflowIntegrationTestBase {

    @BeforeAll
    static void startContainer() {
        if (!WorkflowFixture.POSTGRES.isRunning()) {
            WorkflowFixture.POSTGRES.start();
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> {
            startContainer();
            return WorkflowFixture.POSTGRES.getJdbcUrl();
        });
        registry.add("spring.datasource.username", () -> {
            startContainer();
            return WorkflowFixture.POSTGRES.getUsername();
        });
        registry.add("spring.datasource.password", () -> {
            startContainer();
            return WorkflowFixture.POSTGRES.getPassword();
        });
        registry.add("firstelement.environment", () -> "TEST");
        // The dispatcher is not what these suites are about, and a running loop would make the
        // outbox assertions non-deterministic.
        registry.add("firstelement.worker.dispatcher.enabled", () -> "false");
        registry.add("spring.data.redis.repositories.enabled", () -> "false");
        registry.add("firstelement.channels.mock-webhook-enabled", () -> "false");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class WorkflowTestConfig {
    }
}