package com.firstelement.finance;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Shared container lifecycle for the finance suites.
 *
 * <h2>No skip path</h2>
 *
 * <p>The container starts in {@link #startContainer()} and every property supplier starts it first, so a
 * Docker problem fails the build instead of turning a finance suite green.
 *
 * <h2>Flyway is left enabled on purpose</h2>
 *
 * <p>These suites run against the real chain, including
 * {@code V20261005080000__usage_ledger} and {@code V20261005090000__finance_rate_cards_periods}. Every
 * statement in {@code backend/reporting-finance} was transcribed from those two files column by column,
 * so a column name that does not exist is a test failure here rather than a surprise in production. That
 * is the whole reason the module was written against the migrations rather than against a mental model
 * of them.
 *
 * <h2>The dispatcher and the bridge are off</h2>
 *
 * <p>A running dispatcher would claim the rows a test just seeded, and the realtime bridge is irrelevant
 * to finance. Neither is part of what this phase proves.
 */
@Import(FinanceIntegrationTestBase.FinanceTestConfig.class)
abstract class FinanceIntegrationTestBase {

    @BeforeAll
    static void startContainer() {
        if (!FinanceFixture.POSTGRES.isRunning()) {
            FinanceFixture.POSTGRES.start();
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> {
            startContainer();
            return FinanceFixture.POSTGRES.getJdbcUrl();
        });
        registry.add("spring.datasource.username", () -> {
            startContainer();
            return FinanceFixture.POSTGRES.getUsername();
        });
        registry.add("spring.datasource.password", () -> {
            startContainer();
            return FinanceFixture.POSTGRES.getPassword();
        });
        registry.add("firstelement.environment", () -> "TEST");
        registry.add("firstelement.worker.dispatcher.enabled", () -> "false");
        registry.add("spring.data.redis.repositories.enabled", () -> "false");
        registry.add("firstelement.channels.mock-webhook-enabled", () -> "false");
        registry.add("firstelement.realtime.bridge-enabled", () -> "false");
        registry.add("firstelement.admin.evidence-dir", () -> "");
    }

    /**
     * Deliberately declares no {@link com.firstelement.finance.ledger.UsageWriteAuditor}.
     *
     * <p>This class used to declare one, and that was the bug: the module's own tests supplied a bean
     * the application context did not have, so all eight suites passed while
     * {@code ApiApplicationSmokeTest} could not start. When the production default was added to
     * {@code UsageWriteAuditorDefault}, the two beans collided on the name {@code usageWriteAuditor} and
     * every suite in the module failed with {@code BeanDefinitionOverrideException}.
     *
     * <p>Both failures point at the same mistake, and it is worth stating because it recurs: <b>a test
     * harness must not supply wiring that production supplies.</b> Doing so hides a missing bean from
     * the only test that would notice it. The default now lives in {@code src/main}, discovered by the
     * same component scan as the services, and these tests use it exactly as a deployment would.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class FinanceTestConfig {
    }
}
