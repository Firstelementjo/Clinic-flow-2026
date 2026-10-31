package com.firstelement.notifications.config;

import com.firstelement.notifications.realtime.RealtimeStreamService;
import com.firstelement.notifications.realtime.RealtimeSubscriptionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Notifications and realtime wiring.
 *
 * <h2>The Redis bridge is optional by construction</h2>
 *
 * <p>{@link RedisMessageListenerContainer} is {@code @ConditionalOnMissingBean} and every call site
 * that uses it tolerates its absence, so a process without a reachable Redis still serves its local
 * subscribers and reports {@code multiInstanceCapable = false} on the realtime health surface. That
 * is the honest single-instance behaviour, not a silent partial deployment.
 *
 * <p>The subscription is made from an {@link org.springframework.boot.ApplicationRunner} rather than
 * in a constructor or {@code @PostConstruct}: a listener registered before the container is running
 * is registered on a container that has not started, and the failure mode of "the bridge looks
 * configured and delivers nothing" is exactly the one an operator would not notice. The runner runs
 * once the context is up, and {@code firstelement.realtime.bridge-enabled=false} skips it entirely.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RealtimeProperties.class)
public class RealtimeConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RealtimeConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    RealtimeSubscriptionRegistry realtimeSubscriptionRegistry() {
        return new RealtimeSubscriptionRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    RedisMessageListenerContainer redisMessageListenerContainer(
        RedisConnectionFactory connectionFactory, RealtimeProperties properties) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setSubscriptionExecutor(java.util.concurrent.Executors.newSingleThreadExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "fe-realtime-bridge");
                thread.setDaemon(true);
                return thread;
            }));
        log.info("realtime bridge container configured with a {}s timeout",
            properties.getBridgeTimeout().toSeconds());
        return container;
    }

    /**
     * Joins the bridge when it is enabled. A failure here is logged and swallowed by
     * {@code RealtimeStreamService}: this process then serves its local subscribers only, which is
     * a degraded realtime, never a failed API.
     */
    @Bean
    ApplicationRunner realtimeBridgeSubscription(RealtimeStreamService streams) {
        return args -> streams.subscribeToBridge();
    }
}