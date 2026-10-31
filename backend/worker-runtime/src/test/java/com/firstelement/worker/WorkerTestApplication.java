package com.firstelement.worker;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The application used only by the worker-runtime tests.
 *
 * <p>Mirrors {@code com.firstelement.WorkerApplication} so the queue beans, the security boundary
 * and the dispatcher are wired exactly as they are in the running process. A test-owned application
 * avoids depending on {@code :backend:app-bootstrap}, whose sources belong to another phase.
 */
@SpringBootApplication(scanBasePackages = "com.firstelement")
@ConfigurationPropertiesScan("com.firstelement.config")
public class WorkerTestApplication {
}