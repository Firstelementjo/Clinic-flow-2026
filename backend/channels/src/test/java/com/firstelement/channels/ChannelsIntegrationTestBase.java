package com.firstelement.channels;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Shared container lifecycle for the DB-backed channels tests.
 *
 * <h2>No skip path</h2>
 *
 * <p>The container starts in {@link #startContainer()} and every property supplier starts it first,
 * so a Docker problem fails the build instead of turning a duplicate-webhook test green.
 *
 * <h2>The credential decryptor is supplied by the test, on purpose</h2>
 *
 * <p>{@code ConnectorRegistry.CredentialDecryptor} defaults to <em>refusing</em> in production code,
 * because treating ciphertext as a token would violate FR-4.8. The test supplies a pass-through so
 * the Meta and Telegram signature paths can be exercised against real connectors — and it is a
 * test-owned bean, which is the honest place for it.
 */
@Import(ChannelsIntegrationTestBase.ChannelsTestConfig.class)
abstract class ChannelsIntegrationTestBase {

    @BeforeAll
    static void startContainer() {
        if (!ChannelsFixture.POSTGRES.isRunning()) {
            ChannelsFixture.POSTGRES.start();
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> {
            startContainer();
            return ChannelsFixture.POSTGRES.getJdbcUrl();
        });
        registry.add("spring.datasource.username", () -> {
            startContainer();
            return ChannelsFixture.POSTGRES.getUsername();
        });
        registry.add("spring.datasource.password", () -> {
            startContainer();
            return ChannelsFixture.POSTGRES.getPassword();
        });
        registry.add("firstelement.environment", () -> "TEST");
        // The unsigned Mock webhook is a development affordance; these suites drive the service
        // directly and assert the gate separately.
        registry.add("firstelement.channels.mock-webhook-enabled", () -> "false");
        registry.add("firstelement.worker.dispatcher.enabled", () -> "false");
        registry.add("spring.data.redis.repositories.enabled", () -> "false");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ChannelsTestConfig {

        /**
         * A pass-through decryptor for tests only.
         *
         * <p>The fixture stores the credential JSON as-is, so "decrypt" is "read". Production
         * supplies a real one; production's default is to refuse.
         */
        @Bean
        com.firstelement.channels.connector.ConnectorRegistry.CredentialDecryptor
            testCredentialDecryptor() {
            return (connectionId, stored) -> stored;
        }

        @Bean("channelProviderRequestTimeout")
        java.time.Duration channelProviderRequestTimeout() {
            // Short, so a test that exercises an unreachable provider fails fast rather than
            // waiting the production 20 seconds.
            return java.time.Duration.ofMillis(750);
        }
    }
}