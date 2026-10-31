package com.firstelement.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Fail-fast external configuration for every FirstElement process (API, workers).
 * Secrets come from the environment and are never logged or defaulted in committed
 * files beyond local development.
 */
@Validated
@ConfigurationProperties(prefix = "firstelement")
public class FirstElementProperties {

    public enum Environment { LOCAL, TEST, STAGING, PRODUCTION }

    @NotNull
    private Environment environment = Environment.LOCAL;

    private final Api api = new Api();
    private final Worker worker = new Worker();

    public Environment getEnvironment() {
        return environment;
    }

    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    public Api getApi() {
        return api;
    }

    public Worker getWorker() {
        return worker;
    }

    public @Valid Api apiProps() {
        return api;
    }

    public static class Api {
        @Min(1)
        private int port = 4000;

        @NotBlank
        private String webOrigin = "http://localhost:5173";

        @Size(min = 32, message = "SESSION_SECRET must be at least 32 characters")
        private String sessionSecret = "change-me-to-a-long-random-string-min-32-chars";

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getWebOrigin() {
            return webOrigin;
        }

        public void setWebOrigin(String webOrigin) {
            this.webOrigin = webOrigin;
        }

        public String getSessionSecret() {
            return sessionSecret;
        }

        public void setSessionSecret(String sessionSecret) {
            this.sessionSecret = sessionSecret;
        }
    }

    public static class Worker {
        @Min(0)
        private int concurrency = 4;

        @Min(1)
        private int shutdownDrainSeconds = 20;

        public int getConcurrency() {
            return concurrency;
        }

        public void setConcurrency(int concurrency) {
            this.concurrency = concurrency;
        }

        public int getShutdownDrainSeconds() {
            return shutdownDrainSeconds;
        }

        public void setShutdownDrainSeconds(int shutdownDrainSeconds) {
            this.shutdownDrainSeconds = shutdownDrainSeconds;
        }
    }
}
