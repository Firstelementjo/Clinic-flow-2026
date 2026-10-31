package com.firstelement.channels.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Channel module configuration.
 *
 * <h2>The development gate is configuration, and it fails closed</h2>
 *
 * <p>The Mock channel declares {@code verifiesWebhookSignature = false}, so the only thing standing
 * between the internet and the inbound pipeline is this property. Phase 4 made the same choice for
 * its dev simulator and refused to start in production; this module refuses too, because a Mock
 * webhook that accepted unsigned requests in a production profile would let anyone write into any
 * tenant's inbox.
 */
@Validated
@ConfigurationProperties(prefix = "firstelement.channels")
public class ChannelsProperties {

    /** Allow the unsigned Mock webhook. Never true in production. */
    private boolean mockWebhookEnabled = false;

    /** Provider request timeout. Must stay well under the dispatcher lease. */
    @NotNull
    private Duration providerRequestTimeout = Duration.ofSeconds(20);

    /** How long a provider-event reservation is held before takeover is allowed. */
    @NotNull
    private Duration providerEventLock = Duration.ofMinutes(5);

    /** Rows shown by the admin dead-letter and webhook read models. */
    @Min(1)
    private int readModelLimit = 50;

    public boolean isMockWebhookEnabled() {
        return mockWebhookEnabled;
    }

    public void setMockWebhookEnabled(boolean mockWebhookEnabled) {
        this.mockWebhookEnabled = mockWebhookEnabled;
    }

    public Duration getProviderRequestTimeout() {
        return providerRequestTimeout;
    }

    public void setProviderRequestTimeout(Duration providerRequestTimeout) {
        this.providerRequestTimeout = providerRequestTimeout;
    }

    public Duration getProviderEventLock() {
        return providerEventLock;
    }

    public void setProviderEventLock(Duration providerEventLock) {
        this.providerEventLock = providerEventLock;
    }

    public int getReadModelLimit() {
        return readModelLimit;
    }

    public void setReadModelLimit(int readModelLimit) {
        this.readModelLimit = readModelLimit;
    }

    /**
     * Refuses a production profile with the unsigned Mock webhook enabled.
     *
     * @param environment the resolved {@code firstelement.environment}
     * @throws IllegalStateException when the combination would expose an unsigned write path
     */
    public void assertSafeFor(String environment) {
        if (mockWebhookEnabled && "PRODUCTION".equalsIgnoreCase(environment)) {
            throw new IllegalStateException(
                "firstelement.channels.mock-webhook-enabled=true is refused when "
                    + "firstelement.environment=PRODUCTION. The Mock channel declares no webhook "
                    + "signature, so this setting is an unauthenticated write path into every "
                    + "tenant's inbox.");
        }
    }
}