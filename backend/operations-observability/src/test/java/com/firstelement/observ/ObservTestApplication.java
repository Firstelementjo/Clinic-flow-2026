package com.firstelement.observ;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The application used only by the {@code :backend:operations-observability} tests.
 *
 * <p>Mirrors {@code com.firstelement.ApiApplication}: the security boundary, the identity services, the
 * worker runtime, the connectors, the workflows and the Admin module are wired exactly as they are in
 * the running API, which is what makes "the operations panel delegates replay to the Admin allow-list"
 * a real assertion rather than a claim about a mock.
 */
@SpringBootApplication(scanBasePackages = "com.firstelement")
@ConfigurationPropertiesScan("com.firstelement.config")
public class ObservTestApplication {
}
