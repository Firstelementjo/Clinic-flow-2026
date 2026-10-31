package errorcontract.probe;

import com.firstelement.kernel.web.CorrelationIdFilter;
import com.firstelement.security.event.SecurityEvent;
import com.firstelement.security.event.SecurityEventPublisher;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * The two beans that must exist in the error-mapping suite's context and in <em>no other</em>.
 *
 * <h2>Why this class is outside {@code com.firstelement}</h2>
 *
 * <p>{@code ApiApplication} component-scans {@code com.firstelement}, and this module's test
 * classpath is scanned with it, so a {@code @Configuration} here would be registered into every
 * context in this module. That is acceptable for the probe beans themselves — they are harmless and
 * {@code @Hidden} keeps them out of the published document — but it is not acceptable for a
 * {@code @Primary} {@link SecurityEventPublisher}, which would silently replace the real one for
 * {@code ApiApplicationSmokeTest} and {@code OpenApiDocumentExportTest} as well. Listed explicitly
 * in {@code @SpringBootTest(classes = ...)}, it exists in exactly one context.
 *
 * <h2>Why the security event publisher is replaced at all</h2>
 *
 * <p>The production publisher writes to {@code audit_logs}, which needs a database. This suite
 * asserts HTTP status and body, so a database would buy nothing and cost a container. Replacing it
 * buys something better: the cross-tenant negative can assert that the denial still <em>publishes a
 * security event</em>, which is the half of the security contract a status code cannot see. A denial
 * that stops being published has stopped looking like a security event even if it still answers 404.
 */
@Configuration(proxyBeanMethods = false)
public class ErrorMappingProbeConfiguration {

    /**
     * Puts the correlation id filter back after the security chain is taken out.
     *
     * <p>{@code requestId} is part of the error contract, so a suite that asserted error bodies
     * without one would be asserting a shape no client ever receives.
     */
    @Bean
    MockMvcBuilderCustomizer correlationIdFilterOnly() {
        return builder -> builder.addFilters(new CorrelationIdFilter());
    }

    /** Primary so {@code SecurityExceptionAdvice} is given the recorder rather than the JDBC one. */
    @Bean
    @Primary
    RecordingSecurityEventPublisher recordingSecurityEventPublisher() {
        return new RecordingSecurityEventPublisher();
    }

    /** Captures what the advice published, so a denial can be asserted to still be an event. */
    public static class RecordingSecurityEventPublisher implements SecurityEventPublisher {

        private final List<SecurityEvent> published = new CopyOnWriteArrayList<>();

        @Override
        public void publish(SecurityEvent event) {
            if (event != null && event.type() != null) {
                published.add(event);
            }
        }

        public List<SecurityEvent> published() {
            return List.copyOf(published);
        }

        public void reset() {
            published.clear();
        }
    }
}