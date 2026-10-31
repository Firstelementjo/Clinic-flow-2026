package com.firstelement.worker;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Shared container lifecycle for the DB-backed worker-runtime tests.
 *
 * <h2>No skip path</h2>
 *
 * <p>The container starts in {@link #startContainer()} and every property supplier starts it first,
 * so a Docker problem fails the build instead of turning a recovery test green. See AGENTS.md: "a
 * skipped integration, security, browser, migration, or restore test is not a pass."
 *
 * <p>Flyway is <b>not</b> disabled in this module: the outbox claim predicate, the lease reaper and
 * the dead-letter upsert are only meaningful against the real Phase-5a schema, its real CHECK
 * constraints and its real unique indexes.
 *
 * <p>The dispatcher switch is <b>not</b> set here: a {@code DynamicPropertySource} property outranks
 * {@code @SpringBootTest(properties = ...)}, so setting it per class is the only way one suite can enable
 * the loop while the others keep it off and drive the dispatcher synchronously.
 */
abstract class WorkerIntegrationTestBase {

    @BeforeAll
    static void startContainer() {
        if (!WorkerFixture.POSTGRES.isRunning()) {
            WorkerFixture.POSTGRES.start();
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> {
            startContainer();
            return WorkerFixture.POSTGRES.getJdbcUrl();
        });
        registry.add("spring.datasource.username", () -> {
            startContainer();
            return WorkerFixture.POSTGRES.getUsername();
        });
        registry.add("spring.datasource.password", () -> {
            startContainer();
            return WorkerFixture.POSTGRES.getPassword();
        });
        registry.add("firstelement.environment", () -> "TEST");
        registry.add("spring.data.redis.repositories.enabled", () -> "false");
    }
}