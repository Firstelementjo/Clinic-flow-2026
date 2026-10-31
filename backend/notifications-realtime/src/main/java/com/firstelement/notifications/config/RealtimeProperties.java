package com.firstelement.notifications.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Realtime configuration.
 *
 * <h2>The two numbers that decide whether the stream survives a proxy</h2>
 *
 * <p>{@code heartbeatSeconds} keeps intermediaries from closing an idle connection, and
 * {@code streamTimeoutSeconds} is the ceiling a client will wait before the browser reconnects. The
 * heartbeat must be comfortably below any proxy idle timeout, which is why it is 25 seconds rather
 * than "as long as possible".
 */
@Validated
@ConfigurationProperties(prefix = "firstelement.realtime")
public class RealtimeProperties {

    /** Keepalive interval. Must be below a typical proxy idle timeout (60s). */
    @Min(5)
    private int heartbeatSeconds = 25;

    /** Emitter timeout. The browser reconnects and resyncs when it elapses. */
    @Min(30)
    private long streamTimeoutSeconds = 1800;

    /** Cap on concurrent SSE connections per process. */
    @Min(1)
    private int maxConnections = 2_000;

    /** Whether to subscribe to the Redis bridge. Off in a single-instance deployment. */
    private boolean bridgeEnabled = true;

    /** The connect/read timeout for the bridge. */
    @NotNull
    private Duration bridgeTimeout = Duration.ofSeconds(5);

    public int getHeartbeatSeconds() {
        return heartbeatSeconds;
    }

    public void setHeartbeatSeconds(int heartbeatSeconds) {
        this.heartbeatSeconds = heartbeatSeconds;
    }

    public long getStreamTimeoutSeconds() {
        return streamTimeoutSeconds;
    }

    public void setStreamTimeoutSeconds(long streamTimeoutSeconds) {
        this.streamTimeoutSeconds = streamTimeoutSeconds;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    public boolean isBridgeEnabled() {
        return bridgeEnabled;
    }

    public void setBridgeEnabled(boolean bridgeEnabled) {
        this.bridgeEnabled = bridgeEnabled;
    }

    public Duration getBridgeTimeout() {
        return bridgeTimeout;
    }

    public void setBridgeTimeout(Duration bridgeTimeout) {
        this.bridgeTimeout = bridgeTimeout;
    }
}