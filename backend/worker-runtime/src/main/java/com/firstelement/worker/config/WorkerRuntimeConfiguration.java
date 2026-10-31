package com.firstelement.worker.config;

import com.firstelement.config.FirstElementProperties;
import com.firstelement.worker.runtime.DispatcherProperties;
import com.firstelement.worker.runtime.TenantFairnessPolicy;
import com.firstelement.worker.runtime.WorkerIdentity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The worker runtime's own beans.
 *
 * <h2>Two beans are overridable on purpose</h2>
 *
 * <p>{@link WorkerIdentity} and {@link TenantFairnessPolicy} are {@code @ConditionalOnMissingBean}
 * so a test can substitute a deterministic worker id and a single-slot fairness policy without
 * editing properties. Everything else is wired once: a second dispatcher or a second identity would
 * mean two components believing they own the same lease.
 *
 * <h2>The pool size comes from the shared properties</h2>
 *
 * <p>{@code firstelement.worker.concurrency} was already declared in Phase 1's
 * {@code FirstElementProperties}. Phase 5 does not introduce a second number for the same thing —
 * the class reservations in {@link TenantFairnessPolicy} are computed from it.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DispatcherProperties.class)
public class WorkerRuntimeConfiguration {

    @Bean
    @ConditionalOnMissingBean
    WorkerIdentity workerIdentity() {
        return WorkerIdentity.fromProcess();
    }

    @Bean
    @ConditionalOnMissingBean
    TenantFairnessPolicy tenantFairnessPolicy(DispatcherProperties properties,
                                              FirstElementProperties application) {
        return new TenantFairnessPolicy(properties.getMaxInFlightPerTenant(),
            application.getWorker().getConcurrency());
    }
}