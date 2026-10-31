package com.firstelement.crm;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Shared container lifecycle for the DB-backed CRM tests.
 *
 * <h2>No skip path</h2>
 *
 * <p>The container starts in {@link #startContainer()} and every property supplier starts it
 * first, so there is no availability check that could turn a Docker problem into a green build.
 * If Docker is unreachable the context fails to start and the build fails, which is what
 * AGENTS.md requires: "a skipped integration, security, browser, migration, or restore test is
 * not a pass."
 *
 * <p>The database is reset per test by {@link CrmFixture#reset()}, so no test can observe
 * another's rows, and startup cost is paid once for the whole module.
 */
public abstract class CrmIntegrationTestBase {

    @BeforeAll
    static void startContainer() {
        if (!CrmFixture.POSTGRES.isRunning()) {
            CrmFixture.POSTGRES.start();
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> {
            startContainer();
            return CrmFixture.POSTGRES.getJdbcUrl();
        });
        registry.add("spring.datasource.username", () -> {
            startContainer();
            return CrmFixture.POSTGRES.getUsername();
        });
        registry.add("spring.datasource.password", () -> {
            startContainer();
            return CrmFixture.POSTGRES.getPassword();
        });
    }
}