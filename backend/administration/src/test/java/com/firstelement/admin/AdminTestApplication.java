package com.firstelement.admin;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The application used only by the {@code :backend:administration} tests.
 *
 * <p>Mirrors {@code com.firstelement.ApiApplication}: the security boundary, the identity services,
 * the worker runtime and the connectors are wired exactly as they are in the running API. A
 * test-owned application avoids depending on {@code :backend:app-bootstrap}, whose sources belong to
 * another phase.
 */
@SpringBootApplication(scanBasePackages = "com.firstelement")
@ConfigurationPropertiesScan("com.firstelement.config")
public class AdminTestApplication {
}