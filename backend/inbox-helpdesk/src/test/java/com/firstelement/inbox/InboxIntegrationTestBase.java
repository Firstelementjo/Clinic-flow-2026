package com.firstelement.inbox;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Shared container lifecycle for the DB-backed inbox tests.
 *
 * <h2>No skip path</h2>
 *
 * <p>The container starts in {@link #startContainer()} and every property supplier starts it
 * first, so a Docker problem fails the build instead of turning a security test green. See
 * AGENTS.md: "a skipped integration, security, browser, migration, or restore test is not a pass."
 *
 * <p>The database is reset per test by {@link InboxFixture#reset()}, so no test can observe
 * another's rows, and the startup cost is paid once for the whole module.
 */
abstract class InboxIntegrationTestBase {

    @BeforeAll
    static void startContainer() {
        if (!InboxFixture.POSTGRES.isRunning()) {
            InboxFixture.POSTGRES.start();
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> {
            startContainer();
            return InboxFixture.POSTGRES.getJdbcUrl();
        });
        registry.add("spring.datasource.username", () -> {
            startContainer();
            return InboxFixture.POSTGRES.getUsername();
        });
        registry.add("spring.datasource.password", () -> {
            startContainer();
            return InboxFixture.POSTGRES.getPassword();
        });
    }
}