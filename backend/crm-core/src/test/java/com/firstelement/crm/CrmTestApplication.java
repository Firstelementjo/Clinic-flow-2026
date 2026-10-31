package com.firstelement.crm;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The Spring Boot application used only by the {@code :backend:crm-core} tests.
 *
 * <p>Mirrors {@code com.firstelement.ApiApplication} (same scan base package, same
 * {@code @ConfigurationPropertiesScan}) so the CRM beans and the security boundary are wired
 * exactly as they are in the running API. Keeping a test-owned application avoids depending on
 * {@code :backend:app-bootstrap}, whose sources belong to another phase.
 */
@SpringBootApplication(scanBasePackages = "com.firstelement")
@ConfigurationPropertiesScan("com.firstelement.config")
public class CrmTestApplication {
}