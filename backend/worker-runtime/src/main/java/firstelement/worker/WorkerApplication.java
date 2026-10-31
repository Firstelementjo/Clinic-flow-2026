package firstelement.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The durable worker process's entry point.
 *
 * <h2>Why this class lives OUTSIDE {@code com.firstelement}</h2>
 *
 * <p>This module is both a deployable <em>and</em> a library. {@code :backend:channels} and
 * {@code :backend:notifications-realtime} compile against the queue SPI it declares, and {@code
 * :backend:app-bootstrap} carries all three so the API process can serve the webhook endpoints and
 * the SSE stream. Adding that dependency put two {@code @SpringBootConfiguration} classes on the
 * API's classpath, and Spring Boot's test bootstrap asserts there is exactly one:
 *
 * <blockquote>{@code Found multiple @SpringBootConfiguration annotated classes}</blockquote>
 *
 * <p>It finds candidates by scanning the <b>package of the test class</b> &mdash; here {@code
 * com.firstelement} &mdash; and this class used to live there. Moving the launcher to {@code
 * firstelement.worker} puts it outside that subtree, so the API's classpath again contains exactly
 * one configuration class (its own) while the worker process boots unchanged.
 *
 * <p>This is the same rule Spring Boot applies to any shared module: <b>only the deployable carries
 * the annotation</b>, and a deployable's launcher does not belong in the scan root of the
 * application it launches. Nothing about the worker process changed: the annotation set is
 * identical and the component scan is still {@code com.firstelement}.
 *
 * <h2>What runs here</h2>
 *
 * <p>{@code DispatcherLoop} claims outbox rows per tenant with {@code FOR UPDATE SKIP LOCKED}
 * leases, reaps leases whose worker stopped answering, and drains on shutdown. The
 * {@code JobHandler} beans that actually do the work are discovered from the classpath, so this
 * module carries the engine and its process, not the domain logic &mdash; the same split the
 * legacy worker had ("Its job is scheduling, retries, logging and shutdown; the domain logic is
 * shared").
 *
 * <p><b>Known composition limit, recorded rather than hidden:</b> the outbound-delivery and
 * inbound-pipeline handlers live in {@code :backend:channels} and the notification handler in
 * {@code :backend:notifications-realtime}. Those modules depend on <em>this</em> one for the
 * {@code JobHandler} SPI, so this module cannot depend back on them, and a queue with no handler in
 * this process dead-letters loudly rather than being dropped. The API process
 * ({@code :backend:app-bootstrap}) carries all three modules and can therefore run the same
 * dispatcher for {@code outbound-send}. See {@code docs/migration/phases/phase-05b.md} for the
 * follow-up that removes the limit.
 *
 * <h2>Management port</h2>
 *
 * <p>{@code /actuator/health} and {@code /actuator/metrics} report queue depth, lag, retries,
 * dead letters, reaped leases and worker saturation. See
 * {@code com.firstelement.worker.runtime.WorkerHealthIndicator} for what DOWN means.
 */
@SpringBootApplication(scanBasePackages = "com.firstelement")
@ConfigurationPropertiesScan("com.firstelement.config")
public class WorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkerApplication.class, args);
    }
}