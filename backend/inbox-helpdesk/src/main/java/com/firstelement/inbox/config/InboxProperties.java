package com.firstelement.inbox.config;

import com.firstelement.config.FirstElementProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code firstelement.inbox.*} — the inbox module's configuration.
 *
 * <h2>{@code mock-connector-enabled} is a security switch, not a feature switch</h2>
 *
 * <p>It gates {@code POST /api/v1/dev/mock/incoming-message}, which injects a message as if a
 * customer had sent it. Enabling that in production would be a way to impersonate customers, so
 * {@link #validateFor} refuses to start when the environment is PRODUCTION and the flag is on.
 * That is the legacy check, unchanged.
 */
@ConfigurationProperties(prefix = "firstelement.inbox")
public class InboxProperties {

    /**
     * Whether the development inbound simulator answers. Default {@code false}.
     *
     * <p>Even when enabled the endpoint still requires a session with access to the target
     * project and an enabled MOCK channel connection in it — two gates, as the legacy controller
     * had, because one is easy to bypass by accident.
     */
    private boolean mockConnectorEnabled = false;

    public boolean isMockConnectorEnabled() {
        return mockConnectorEnabled;
    }

    public void setMockConnectorEnabled(boolean mockConnectorEnabled) {
        this.mockConnectorEnabled = mockConnectorEnabled;
    }

    /**
     * Refuses an unsafe configuration at startup.
     *
     * @throws IllegalStateException when the simulator is enabled in production
     */
    public void validateFor(FirstElementProperties.Environment environment) {
        if (mockConnectorEnabled
            && environment == FirstElementProperties.Environment.PRODUCTION) {
            throw new IllegalStateException(
                "firstelement.inbox.mock-connector-enabled must not be true when "
                    + "firstelement.environment=PRODUCTION: an inbound message-injection endpoint "
                    + "in production is a way to impersonate customers.");
        }
    }
}