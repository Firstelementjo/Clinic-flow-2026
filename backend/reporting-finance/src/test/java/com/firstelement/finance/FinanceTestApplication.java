package com.firstelement.finance;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The application used only by the {@code :backend:reporting-finance} tests.
 *
 * <p>Mirrors {@code com.firstelement.ApiApplication}: the security boundary, the identity services, the
 * admin audit streams and the connectors are wired exactly as they are in the running API, because
 * several finance behaviours are only true in the presence of the real filter chain — a
 * {@code @RequirePermission} that passes under an injected context can fail in the deployed route, and
 * the cross-tenant negatives are only meaningful if the tenant context really came from a session.
 *
 * <p>A test-owned application avoids depending on {@code :backend:app-bootstrap}, whose sources belong
 * to another phase.
 */
@SpringBootApplication(scanBasePackages = "com.firstelement")
@ConfigurationPropertiesScan("com.firstelement.config")
public class FinanceTestApplication {
}
