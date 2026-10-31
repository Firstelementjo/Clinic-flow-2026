package com.firstelement.airag;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The application used only by the {@code :backend:ai-rag} tests.
 *
 * <p>Mirrors {@code com.firstelement.ApiApplication}: the security boundary, the identity services, the
 * admin audit streams, the finance ledger and the worker SPI are wired exactly as they are in the running
 * API, because several AI behaviours are only true in the presence of the real filter chain — a capability
 * that passes under an injected context can fail in the deployed route, and the cross-tenant negatives are
 * only meaningful if the tenant context really came from a session.
 *
 * <p>A test-owned application avoids depending on {@code :backend:app-bootstrap}, whose sources belong to
 * another phase.
 *
 * <h2>What this class deliberately does NOT declare</h2>
 *
 * <p>No beans. Phase 8's lesson is recorded on
 * {@code com.firstelement.finance.FinanceIntegrationTestBase.FinanceTestConfig} and it applies verbatim: a
 * test harness that supplies a bean the application context does not have hides a missing production bean
 * from the only test that would notice it — {@code ApiApplicationSmokeTest}. Everything these suites need is
 * a production bean in {@code src/main}: the mock provider, the deterministic embedding adapter, the
 * provider registry, the failover executor and every service.
 */
@SpringBootApplication(scanBasePackages = "com.firstelement")
@ConfigurationPropertiesScan("com.firstelement.config")
public class AiRagTestApplication {
}