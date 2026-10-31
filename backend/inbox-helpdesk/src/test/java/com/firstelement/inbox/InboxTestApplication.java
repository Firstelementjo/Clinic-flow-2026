package com.firstelement.inbox;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The Spring Boot application used only by the {@code :backend:inbox-helpdesk} tests.
 *
 * <p>Mirrors {@code com.firstelement.ApiApplication} so the inbox beans, the CRM beans and the
 * security boundary are wired exactly as they are in the running API. A test-owned application
 * avoids depending on {@code :backend:app-bootstrap}, whose sources belong to another phase.
 */
@SpringBootApplication(scanBasePackages = "com.firstelement")
@ConfigurationPropertiesScan("com.firstelement.config")
public class InboxTestApplication {
}