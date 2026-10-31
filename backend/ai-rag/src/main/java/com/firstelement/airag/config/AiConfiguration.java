package com.firstelement.airag.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * The module's configuration: it registers {@link AiRagProperties}.
 *
 * <h2>Why a plain {@code @Configuration} and not the auto-configuration class</h2>
 *
 * <p>Because an {@code @AutoConfiguration} class is only honoured when the deployment registers it through
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}, and this
 * migration has no such file for any module — every {@code :backend} module is discovered by
 * {@code @SpringBootApplication(scanBasePackages = "com.firstelement")} instead. An auto-configuration
 * class that no imports file names is therefore <em>not</em> processed, so putting
 * {@code @EnableConfigurationProperties} there would leave every consumer of these properties unsatisfied.
 *
 * <p>This is the same reason {@code :backend:reporting-exports} has {@code ExportConfiguration} carrying
 * {@code @EnableConfigurationProperties(ExportProperties.class)}. The convention is followed rather than
 * reinvented, and {@link AiRagModule} keeps its own annotation so the module is also correct under a
 * future imports-file registration.
 *
 * <h2>Why the failure is a context failure and not a test failure</h2>
 *
 * <p>Worth recording, because it is the shape Phase 8 warns about. With the properties unregistered, every
 * bean depending on them — the mock provider, the provider registry, the ingestion service — fails, and
 * <em>all seventeen tests in the suite fail identically with a context-load error</em>. The signal is real
 * but the message names the context, not the missing registration. The root cause is one annotation on one
 * class; everything else was downstream of it.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiRagProperties.class)
public class AiConfiguration {
}