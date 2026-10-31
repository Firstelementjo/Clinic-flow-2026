package com.firstelement.identity;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The Spring Boot application used only by the {@code :backend:identity-access} tests.
 *
 * <p>Mirrors {@code com.firstelement.ApiApplication} (same scan base package, same
 * {@code @ConfigurationPropertiesScan}) so the security boundary and the identity beans
 * are wired exactly as they are in the running API. Keeping a test-owned application
 * avoids depending on {@code :backend:app-bootstrap}, whose sources belong to another
 * phase.
 */
@SpringBootApplication(scanBasePackages = "com.firstelement")
@ConfigurationPropertiesScan("com.firstelement.config")
public class IdentityAccessTestApplication {
}