package com.firstelement.admin;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Shared container lifecycle for the Admin suites.
 *
 * <h2>No skip path</h2>
 *
 * <p>The container starts in {@link #startContainer()} and every property supplier starts it first, so
 * a Docker problem fails the build instead of turning an Admin suite green.
 *
 * <h2>Flyway is left enabled on purpose</h2>
 *
 * <p>These suites run against the real migration chain, including
 * {@code V20261005050000__audit_streams}, {@code V20261005070000__export_jobs} and
 * {@code V20261005100000__ai_policy_and_governance}, so a column name that does not exist is a test
 * failure here rather than a surprise in production. That is the whole reason this phase's read
 * models were written against the migrations rather than against a mental model of them.
 *
 * <h2>No evidence root is configured</h2>
 *
 * <p>{@code firstelement.admin.evidence-dir} is deliberately left empty, which is the production
 * default. The backup/restore read model therefore answers {@code NO_EVIDENCE_ROOT} — and one test
 * asserts that, because "not configured" and "zero rehearsals" must never look the same.
 */
@Import(AdminIntegrationTestBase.AdminTestConfig.class)
abstract class AdminIntegrationTestBase {

    @BeforeAll
    static void startContainer() {
        if (!AdminFixture.POSTGRES.isRunning()) {
            AdminFixture.POSTGRES.start();
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> {
            startContainer();
            return AdminFixture.POSTGRES.getJdbcUrl();
        });
        registry.add("spring.datasource.username", () -> {
            startContainer();
            return AdminFixture.POSTGRES.getUsername();
        });
        registry.add("spring.datasource.password", () -> {
            startContainer();
            return AdminFixture.POSTGRES.getPassword();
        });
        registry.add("firstelement.environment", () -> "TEST");
        // A running dispatcher would claim the rows a diagnosis test just seeded and make the
        // worklist non-deterministic. This phase is about the read models, not the loop.
        registry.add("firstelement.worker.dispatcher.enabled", () -> "false");
        registry.add("spring.data.redis.repositories.enabled", () -> "false");
        registry.add("firstelement.channels.mock-webhook-enabled", () -> "false");
        registry.add("firstelement.realtime.bridge-enabled", () -> "false");
        registry.add("firstelement.admin.evidence-dir", () -> "");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class AdminTestConfig {
    }
}