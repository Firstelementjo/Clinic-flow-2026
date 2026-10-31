package com.firstelement.notifications;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Shared container lifecycle for the DB-backed notifications/realtime tests.
 *
 * <h2>Redis is not started, on purpose</h2>
 *
 * <p>The bridge is best-effort by contract: "when Redis is unreachable, {@code publish} logs and
 * delivers locally. A broker outage degrades realtime, not the product." Running these tests without
 * a broker therefore proves the degradation path rather than skipping it, and it keeps the suite
 * dependent on nothing but PostgreSQL — which is where the record lives.
 */
abstract class NotificationsIntegrationTestBase {

    @BeforeAll
    static void startContainer() {
        if (!NotificationsFixture.POSTGRES.isRunning()) {
            NotificationsFixture.POSTGRES.start();
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> {
            startContainer();
            return NotificationsFixture.POSTGRES.getJdbcUrl();
        });
        registry.add("spring.datasource.username", () -> {
            startContainer();
            return NotificationsFixture.POSTGRES.getUsername();
        });
        registry.add("spring.datasource.password", () -> {
            startContainer();
            return NotificationsFixture.POSTGRES.getPassword();
        });
        registry.add("firstelement.environment", () -> "TEST");
        registry.add("firstelement.worker.dispatcher.enabled", () -> "false");
        // The bridge is optional; these tests assert the single-instance behaviour explicitly.
        registry.add("firstelement.realtime.bridge-enabled", () -> "false");
        // The production value is 1800s. A test that failed to abort a stream would then wait 30
        // minutes for an emitter nothing else ends, so the ceiling is pinned to the documented
        // 30s floor: a stalled stream fails its assertions instead of hanging the suite.
        registry.add("firstelement.realtime.stream-timeout-seconds", () -> "30");
        // Point the Redis client at a closed port so the best-effort path is the one under test
        // rather than an accidental connection to a developer's local broker.
        registry.add("spring.data.redis.url", () -> "redis://127.0.0.1:1");
        registry.add("spring.data.redis.repositories.enabled", () -> "false");
    }
}