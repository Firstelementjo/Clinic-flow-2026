package com.firstelement.notifications;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The application used only by the {@code :backend:notifications-realtime} tests.
 *
 * <p>Mirrors {@code com.firstelement.ApiApplication}: the notification service, the SSE stream and
 * the security boundary are wired exactly as they are in the running API.
 */
@SpringBootApplication(scanBasePackages = "com.firstelement")
@ConfigurationPropertiesScan("com.firstelement.config")
public class NotificationsTestApplication {
}