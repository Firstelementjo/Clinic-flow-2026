package com.firstelement.identity;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Shared container lifecycle for the DB-backed security tests.
 *
 * <h2>No skip path</h2>
 *
 * <p>The container is started in {@link #startContainer()} and every property supplier
 * starts it first, so there is no availability check that could turn a Docker problem into
 * a green build. If Docker is unreachable the context fails to start and the build fails,
 * which is what AGENTS.md requires: "a skipped integration, security, browser, migration, or
 * restore test is not a pass."
 *
 * <h2>Why the container is shared</h2>
 *
 * <p>Three test classes share one {@code pgvector/pgvector:pg16} instance. The database is
 * reset per test by {@link SecurityFixture#reset()}, so no test can observe another's rows,
 * and startup cost is paid once. It is not stopped between classes because a sibling class
 * in the same JVM reuses it; Testcontainers reclaims it at JVM exit.
 */
abstract class SecurityIntegrationTestBase {

    @BeforeAll
    static void startContainer() {
        if (!SecurityFixture.POSTGRES.isRunning()) {
            SecurityFixture.POSTGRES.start();
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> {
            startContainer();
            return SecurityFixture.POSTGRES.getJdbcUrl();
        });
        registry.add("spring.datasource.username", () -> {
            startContainer();
            return SecurityFixture.POSTGRES.getUsername();
        });
        registry.add("spring.datasource.password", () -> {
            startContainer();
            return SecurityFixture.POSTGRES.getPassword();
        });
    }
}