package securityscope.probe;

import com.firstelement.kernel.web.CorrelationIdFilter;
import com.firstelement.security.error.SecurityExceptionAdvice;
import com.firstelement.security.event.SecurityEvent;
import com.firstelement.security.event.SecurityEventPublisher;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * The context {@code SecurityExceptionAdviceScopeHttpTest} drives: the real advice, the probe
 * controller outside {@code com.firstelement}, and the two mirrors below it.
 *
 * <h2>Why this exists as a fixture and not as the product context</h2>
 *
 * <p>Booting {@code ApiApplication} from this module is impossible (see
 * {@code ProbeAdviceLadder}), and booting <em>no</em> context would assert nothing: the defect was
 * never in a handler, it was in which advice the dispatcher consulted. So the suite assembles the
 * minimum that reproduces the dispatcher's choice -- {@link SecurityExceptionAdvice} at
 * {@code HIGHEST_PRECEDENCE}, a refusal family at {@code LOWEST_PRECEDENCE - 100}, and a
 * {@code Exception} catch-all at {@code LOWEST_PRECEDENCE} -- and lets Spring MVC do the resolving.
 *
 * <h2>Why the security event publisher is replaced</h2>
 *
 * <p>The production publisher writes to {@code audit_logs} and needs a database. This suite asserts
 * the half of a denial a status code cannot show -- that it is still published, and that the event
 * still carries the correlation id -- so it needs to read what was published, and a container would
 * buy nothing for that. This is the same substitution {@code app-bootstrap}'s probe configuration
 * makes, and for the same reason.
 *
 * <h2>Why the correlation id filter is put back</h2>
 *
 * <p>{@code addFilters = false} takes the security chain out of the path so this suite is about
 * error mapping rather than authentication. {@code requestId} is part of the error contract and is
 * also what carries the correlation id onto the security event, so it is added back explicitly. A
 * suite asserting error bodies without one would assert a shape no client receives.
 */
@SpringBootConfiguration
// `spring-boot-starter-jdbc` is a production dependency of this module, so its auto-configuration
// would otherwise try to build a HikariDataSource with no url and fail the whole context. There is
// nothing to connect to and nothing here needs one: this suite is about which advice the dispatcher
// picks, and the security event publisher that would have written to the database is replaced by a
// recorder below. Excluding it is what keeps the suite free of a container and of any chance of
// opening a real database.
@EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
@Import({
    SecurityExceptionAdvice.class,
    SecurityScopeProbeRoutes.class,
    ProbeAdviceLadder.ProbeDomainRefusalAdvice.class,
    ProbeAdviceLadder.ProbeFloorAdvice.class
})
public class SecurityScopeProbeConfiguration {

    /** Puts the correlation id filter back after the security chain is taken out. */
    @Bean
    MockMvcBuilderCustomizer correlationIdFilterOnly() {
        return builder -> builder.addFilters(new CorrelationIdFilter());
    }

    @Bean
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
