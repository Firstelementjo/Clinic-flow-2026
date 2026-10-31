package com.firstelement.automation;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The application used only by the {@code :backend:workflows-sla-routing} tests.
 *
 * <p>Mirrors {@code com.firstelement.ApiApplication}: the CRM, inbox, worker-runtime and security
 * beans this module builds on are wired exactly as they are in the running API, so the DB-backed suites
 * exercise real beans rather than mocks. A test-owned application avoids depending on
 * {@code :backend:app-bootstrap}, whose sources belong to another phase.
 */
@SpringBootApplication(scanBasePackages = "com.firstelement")
@ConfigurationPropertiesScan("com.firstelement.config")
public class WorkflowTestApplication {
}