package com.firstelement.exports;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared fixture for the reporting-exports integration suites.
 *
 * <h2>Two tenants, two projects in one of them, and a distinct principal per role</h2>
 *
 * <p>Tenant A has {@code PROJECT_A} and {@code PROJECT_A2} so the project predicate is exercised
 * independently of the tenant one — a bug that only ever compared {@code organizationId} passes every
 * tenant test and is caught here. Tenant B exists so that "no rows" can never be confused with "the fixture
 * seeded nothing".
 *
 * <h2>No skip path</h2>
 *
 * <p>The container starts in {@link #startContainer()} and every property supplier starts it first, so a
 * Docker problem fails the build instead of turning an integration suite green. A skipped DB fixture is not
 * a pass.
 *
 * <h2>Flyway is left enabled, and that is the point</h2>
 *
 * <p>Every statement in this module was transcribed from {@code V20261005060000__report_definitions} and
 * {@code V20261005070000__export_jobs} column by column. Running the real chain means a column that does
 * not exist is a test failure here rather than a production surprise — which is exactly what a hand-rolled
 * schema in the test would have hidden.
 *
 * <h2>No test-supplied wiring</h2>
 *
 * <p>{@link ExportsTestApplication} supplies no {@code @Bean}. A harness that supplies wiring production
 * supplies hides a missing bean from the only test that would notice it — the Phase-8 lesson recorded in
 * {@code FinanceIntegrationTestBase}. Everything these suites need is discovered by component scan exactly
 * as a deployment would discover it.
 */
@Import(ExportsTestBase.TestConfig.class)
abstract class ExportsTestBase {

    static final DockerImageName POSTGRES_IMAGE =
        DockerImageName.parse("pgvector/pgvector:pg16");

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE)
        .withDatabaseName("firstelement")
        .withUsername("firstelement")
        .withPassword("firstelement")
        .withEnv("TZ", "UTC");

    // ------------------------------------------------------------------
    // tenants and projects
    // ------------------------------------------------------------------

    static final java.util.UUID ORG_A = java.util.UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    static final java.util.UUID ORG_B = java.util.UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    static final java.util.UUID PROJECT_A = java.util.UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000a1");
    static final java.util.UUID PROJECT_A2 = java.util.UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000a2");
    static final java.util.UUID PROJECT_B = java.util.UUID.fromString("bbbbbbbb-0000-0000-0000-0000000000b1");

    static final java.util.UUID ADMIN_A = java.util.UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000a0");
    static final java.util.UUID EMPLOYEE_A = java.util.UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000a5");
    static final java.util.UUID ADMIN_B = java.util.UUID.fromString("bbbbbbbb-0000-0000-0000-0000000000b0");

    static final java.util.UUID CUSTOMER_A1 = java.util.UUID.fromString("aaaaaaaa-0000-0000-0000-000000000c01");
    static final java.util.UUID CUSTOMER_A2 = java.util.UUID.fromString("aaaaaaaa-0000-0000-0000-000000000c02");
    static final java.util.UUID CUSTOMER_B1 = java.util.UUID.fromString("bbbbbbbb-0000-0000-0000-000000000c01");

    @org.junit.jupiter.api.BeforeAll
    static void startContainer() {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
    }

    /**
     * A project-scoped tenant A scope, for the repository calls a suite makes directly.
     *
     * <p>On the base class rather than the fixture because the suites call {@code ExportJobRepository}
     * and {@code ExportEventRepository} themselves, and every one of those calls must take a scope from
     * somewhere that cannot be a caller-supplied string.
     */
    static com.firstelement.security.query.TenantScope scopeA(java.util.UUID projectId) {
        return new com.firstelement.security.query.TenantScope(ORG_A, projectId);
    }

    /** Tenant B's project scope, for a repository read that must find nothing. */
    static com.firstelement.security.query.TenantScope scopeB() {
        return new com.firstelement.security.query.TenantScope(ORG_B, PROJECT_B);
    }

    @org.springframework.test.context.DynamicPropertySource
    static void datasource(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> {
            startContainer();
            return POSTGRES.getJdbcUrl();
        });
        registry.add("spring.datasource.username", () -> {
            startContainer();
            return POSTGRES.getUsername();
        });
        registry.add("spring.datasource.password", () -> {
            startContainer();
            return POSTGRES.getPassword();
        });
        registry.add("firstelement.environment", () -> "TEST");
        // The dispatcher would claim the rows a test just seeded, and neither the export queue nor the
        // analytics rollup queue is what these suites prove — they call the generation entry point directly.
        registry.add("firstelement.worker.dispatcher.enabled", () -> "false");
        registry.add("spring.data.redis.repositories.enabled", () -> "false");
        registry.add("firstelement.realtime.bridge-enabled", () -> "false");
        registry.add("firstelement.admin.evidence-dir", () -> "");
        registry.add("firstelement.exports.signing-key", () -> "an-integration-suite-signing-key-of-length-32");
    }

    /**
     * The suites' Spring application.
     *
     * <p>A whole-context scan of {@code com.firstelement}, mirroring {@code ApiApplication} and the sibling
     * finance harness. A narrowed scan would be cheaper, but the properties under test here — the real
     * {@code TenantQueryGuard}, the real {@code AuthorizationService}, the real Flyway chain — are only
     * true in the presence of the wiring a deployment actually gets, and a test that supplied its own
     * would prove the harness rather than the module.
     */
    @SpringBootApplication(scanBasePackages = "com.firstelement")
    @ConfigurationPropertiesScan("com.firstelement.config")
    static class ExportsTestApplication {
    }

    /** Deliberately empty. See the class comment: a harness must not supply production's wiring. */
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
    }
}
