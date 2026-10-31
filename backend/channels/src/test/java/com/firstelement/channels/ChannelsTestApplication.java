package com.firstelement.channels;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The application used only by the {@code :backend:channels} tests.
 *
 * <p>Mirrors {@code com.firstelement.ApiApplication}: the connector beans, the CRM services, the
 * inbox pipeline and the security boundary are wired exactly as they are in the running API. A
 * test-owned application avoids depending on {@code :backend:app-bootstrap}, whose sources belong to
 * another phase.
 */
@SpringBootApplication(scanBasePackages = "com.firstelement")
@ConfigurationPropertiesScan("com.firstelement.config")
public class ChannelsTestApplication {
}