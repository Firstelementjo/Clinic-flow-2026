package com.firstelement.security.job;

import com.firstelement.security.TenantAuthentication;
import com.firstelement.security.TenantContext;
import com.firstelement.security.TenantContextHolder;
import com.firstelement.security.event.SecurityEvent;
import com.firstelement.security.event.SecurityEventPublisher;
import com.firstelement.security.event.SecurityEventType;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The only supported way to run tenant-scoped work off an HTTP request.
 *
 * <h2>Why jobs need their own boundary</h2>
 *
 * <p>plan.md §5 Phase 3 requires negative tests for "cross-tenant and cross-project
 * HTTP, service, repository, report, export, worker, realtime, and AI-tool" paths. A
 * worker has no {@code Authentication} and no request; if it simply called the
 * repositories, an unscoped read would succeed silently and an
 * {@code organizationId} from a job payload would be trusted.
 *
 * <p>So a job declares its {@link TenantContext} explicitly, this class binds it for the
 * duration, and every nested {@code TenantQueryGuard} / {@code AuthorizationService}
 * call resolves it. A job that arrives without one is refused and recorded, not
 * defaulted to a full-tenant read.
 *
 * <h2>Where the context comes from</h2>
 *
 * <p>Only from a place that already validated it: a job row's own
 * {@code organizationId} checked against the row it is processing, a worker's
 * claimed tenant, or a system job that runs for every tenant <em>once per tenant</em>
 * via this class. Never from a message payload written by a browser or an AI run.
 */
@Component
public class TenantScopedExecutor {

    private static final Logger log = LoggerFactory.getLogger(TenantScopedExecutor.class);

    private final SecurityEventPublisher events;

    public TenantScopedExecutor(SecurityEventPublisher events) {
        this.events = events == null ? event -> { } : events;
    }

    /**
     * Runs {@code work} bound to {@code context}.
     *
     * <p>Two bindings are established, because a job needs both:
     *
     * <ul>
     *   <li>the {@link TenantContext} thread binding, so repositories and query builders can
     *       resolve the scope without an {@code Authentication} parameter;</li>
     *   <li>a {@code SecurityContext} carrying a {@code TenantAuthentication}, so
     *       {@code @RequirePermission} works. Spring Security's
     *       {@code getAuthentication()} throws when the context is empty, so a job calling
     *       an annotated service without this would be denied for the wrong reason (a
     *       missing credential rather than a missing tenant) — and the tempting
     *       "no authentication? allow it" workaround would remove a real check.</li>
     * </ul>
     *
     * <p>Both are restored in a {@code finally}.
     *
     * @param context  the validated scope; must not be null
     * @param operation a stable name recorded on failure, e.g. {@code ai.run.execute}
     * @throws UnscopedJobException when {@code context} is null
     */
    public <T> T execute(TenantContext context, String operation, Supplier<T> work) {
        requireContext(context, operation);
        var previous = SecurityContextHolder.getContext();
        SecurityContextHolder.setContext(authenticationContext(context));
        try {
            return TenantContextHolder.call(context, () -> {
                putMdc(context);
                try {
                    return work.get();
                } catch (RuntimeException e) {
                    log.error("Tenant-scoped job failed operation={} principal={}",
                        operation, context.auditActorLabel(), e);
                    throw e;
                }
            });
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private SecurityContext authenticationContext(TenantContext context) {
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new TenantAuthentication(context));
        return securityContext;
    }

    public void execute(TenantContext context, String operation, Runnable work) {
        execute(context, operation, () -> {
            work.run();
            return null;
        });
    }

    /**
     * Runs the same work once per tenant, binding each in turn. The correct shape for a
     * fan-out system job: it cannot accidentally see a tenant it was not given.
     */
    public <T> java.util.List<T> executeForEachTenant(Iterable<TenantContext> contexts,
                                                      String operation,
                                                      Function1<TenantContext, T> work) {
        java.util.List<T> results = new java.util.ArrayList<>();
        for (TenantContext context : contexts) {
            requireContext(context, operation);
            results.add(execute(context, operation, () -> work.apply(context)));
        }
        return results;
    }

    /** Local alias so the method signature reads clearly without importing a raw type. */
    @FunctionalInterface
    public interface Function1<I, O> {
        O apply(I input);
    }

    /** Refusal to run tenant-scoped work without a scope. Never caught silently. */
    public static class UnscopedJobException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public UnscopedJobException(String message) {
            super(message);
        }
    }

    private void requireContext(TenantContext context, String operation) {
        if (context != null) {
            return;
        }
        try {
            events.publish(SecurityEvent.builder(SecurityEventType.UNSCOPED_JOB_REJECTED)
                .resourceType("Job")
                .resourceId(operation)
                .reason("no-tenant-context")
                .metadata(Map.of("operation", operation))
                .correlationId(MDC.get("correlationId"))
                .build());
        } catch (RuntimeException e) {
            log.error("Failed to publish UNSCOPED_JOB_REJECTED operation={}", operation, e);
        }
        throw new UnscopedJobException(
            "Refusing to run '" + operation + "' without a TenantContext. A tenant-scoped job must "
                + "declare the tenant it is acting for; defaulting to a full-tenant read is exactly "
                + "the failure this class exists to prevent.");
    }

    private void putMdc(TenantContext context) {
        MDC.put("tenantId", context.organizationId().toString());
        MDC.put("principalType", context.principalType().value());
    }
}