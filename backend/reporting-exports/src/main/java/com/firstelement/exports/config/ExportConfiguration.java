package com.firstelement.exports.config;

import com.firstelement.config.FirstElementProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The export module's own configuration: the startup guards.
 *
 * <h2>Why this lives here and not in {@code application.yml}</h2>
 *
 * <p>{@code backend/app-bootstrap/src/**} belongs to the bootstrap phase, so this module declares its
 * own {@code @ConfigurationProperties} and enables it explicitly. The guards are beans rather than
 * {@code @PostConstruct} methods on the properties class so that a misconfigured deployment fails at
 * <em>context refresh</em>, where an operator sees it, rather than at the first download.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ExportProperties.class)
public class ExportConfiguration {

    /**
     * Fails startup when the export module is configured in a way that would leak or destroy customer
     * data.
     *
     * <p>Two refusals, both recorded in {@link ExportProperties#validateFor}: a production deployment
     * left on the development signing key, and artefact purge enabled anywhere. The second is refused
     * in <em>every</em> environment on purpose — see the property's comment for why it is not a flag
     * an operator should be flipping.
     */
    @Bean
    ExportStartupGuard exportStartupGuard(ExportProperties properties, FirstElementProperties app) {
        properties.validateFor(app.getEnvironment());
        return new ExportStartupGuard(properties.getRetention().isPurgeEnabled(),
            properties.getSignatureTtl());
    }

    /**
     * The resolved startup gate, for the controller and the retention service.
     *
     * @param purgeEnabled always {@code false}; it is recorded so an operator surface can SHOW that the
     *        capability exists and is off, rather than the capability being absent and therefore
     *        invisible
     * @param signatureTtl the effective download-link lifetime
     */
    public record ExportStartupGuard(boolean purgeEnabled, java.time.Duration signatureTtl) {
    }
}