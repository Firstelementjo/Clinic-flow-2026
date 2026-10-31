package com.firstelement.inbox.config;

import com.firstelement.config.FirstElementProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The inbox module's own configuration: the development inbound simulator's gate.
 *
 * <h2>Why this property lives here and not in {@code application.yml}</h2>
 *
 * <p>{@code backend/app-bootstrap/src/**} belongs to the bootstrap phase, so this module
 * declares its own {@code @ConfigurationProperties} and enables it explicitly. The default is
 * {@code false}: a build that never sets the flag never exposes the simulator, which is the
 * correct default for a message-injection endpoint.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InboxProperties.class)
public class InboxConfiguration {

    /**
     * Fails startup when the inbound simulator is enabled in production.
     *
     * <p>Ported from the legacy configuration check, whose comment was that "a
     * message-injection endpoint in production would be a way to impersonate customers". The
     * check is kept even though it is one property, because the alternative — discovering this
     * in an incident — is how a test account becomes a real one.
     */
    @Bean
    InboxProductionGuard inboxProductionGuard(InboxProperties properties,
                                              FirstElementProperties app) {
        properties.validateFor(app.getEnvironment());
        return new InboxProductionGuard(properties.isMockConnectorEnabled());
    }

    /** The resolved gate value, for the controller. */
    public record InboxProductionGuard(boolean mockConnectorEnabled) {
    }
}