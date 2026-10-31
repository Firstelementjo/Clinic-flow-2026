package com.firstelement.channels.config;

import com.firstelement.channels.connector.ConnectorRegistry;
import com.firstelement.config.FirstElementProperties;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Channel wiring.
 *
 * <h2>Only two beans, both deliberately replaceable</h2>
 *
 * <p>{@link ConnectorRegistry.CredentialDecryptor} defaults to <em>refusing</em>, so a process with
 * no key-management module refuses every real channel rather than treating ciphertext as a token —
 * FR-4.8 ("The system must never pretend an unconfigured real connector succeeded"). Supplying the
 * bean is how the credential owner makes real connectors work, and nothing else changes.
 *
 * <p>The provider timeout is a bean so {@code ConnectorRegistry} can take a {@link Duration}
 * without reaching for {@code @Value}, and so a test can shorten it.
 *
 * <p>There is deliberately no {@code @Bean} for {@code ChannelConnectionRepository},
 * {@code OutboundDeliveryRepository} or the codec: they are {@code @Repository}/{@code @Component}
 * and declaring them twice would create two instances with two connections' worth of state.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChannelsProperties.class)
public class ChannelsConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ChannelsConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    ConnectorRegistry.CredentialDecryptor credentialDecryptor() {
        return ConnectorRegistry.CredentialDecryptor.REFUSES;
    }

    @Bean("channelProviderRequestTimeout")
    @ConditionalOnMissingBean(name = "channelProviderRequestTimeout")
    Duration channelProviderRequestTimeout(ChannelsProperties properties) {
        return properties.getProviderRequestTimeout();
    }

    /**
     * Refuses a production profile with the unsigned Mock webhook enabled.
     *
     * <p>Checked at startup rather than per request, because the failure mode of the alternative is
     * an unsigned write path into every tenant's inbox that nobody notices until a customer reports
     * a message they never sent.
     *
     * @throws IllegalStateException when the combination is unsafe
     */
    @Bean
    MockWebhookProductionGate mockWebhookProductionGate(ChannelsProperties properties,
                                                         FirstElementProperties application) {
        properties.assertSafeFor(application.getEnvironment().name());
        log.info("channel module ready: mockWebhookEnabled={} providerTimeout={}",
            properties.isMockWebhookEnabled(), properties.getProviderRequestTimeout());
        return new MockWebhookProductionGate();
    }

    /** Marker for the startup gate above, so a test can assert the gate bean exists. */
    public static final class MockWebhookProductionGate {
    }
}