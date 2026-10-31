package com.firstelement.security;

import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.security.core.Authentication;

/**
 * Thread binding for {@link TenantContext}.
 *
 * <p>Exists because "controllers must not be the only enforcement point": a
 * repository, a job handler, a pgvector query builder, or a report aggregator has
 * no {@code Authentication} parameter, yet it still must not run unscoped. The
 * caller binds a context once at the request/job boundary and every nested call
 * resolves the same immutable value.
 *
 * <h2>Rules</h2>
 *
 * <ul>
 *   <li>Bound for the duration of a request or job and cleared in a
 *       {@code finally} — see {@link TenantContextFilter} and
 *       {@link com.firstelement.security.job.TenantScopedExecutor}.</li>
 *   <li>{@link #require()} throws rather than returning null, so an unscoped
 *       data access is a hard failure and never a silent full-tenant read.</li>
 *   <li>{@link #call(TenantContext, Supplier)} and {@link #run(TenantContext, Runnable)}
 *       always restore the previous value, so nesting a narrower scope inside a
 *       wider one cannot leak out.</li>
 *   <li>Nothing here is inherited: a child thread must bind its own context. That
 *       is intentional — a worker thread must never silently reuse the HTTP
 *       thread's scope. Use {@link #wrap(Runnable)} for hand-off.</li>
 * </ul>
 */
public final class TenantContextHolder {

    private static final ThreadLocal<TenantContext> CURRENT = new ThreadLocal<>();

    private TenantContextHolder() {
    }

    /** Binds {@code context} for the current thread, replacing any previous value. */
    public static void set(TenantContext context) {
        if (context == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(context);
        }
    }

    /** @return the bound context, or empty when this thread has none. */
    public static Optional<TenantContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** @return the bound context. */
    public static TenantContext get() {
        TenantContext context = CURRENT.get();
        if (context == null) {
            throw new IllegalStateException(
                "No TenantContext is bound to this thread. Tenant-scoped work must run inside a "
                    + "TenantContextFilter request, a TenantScopedExecutor job, or an explicit "
                    + "TenantContextHolder.call(...) block. Refusing to run unscoped.");
        }
        return context;
    }

    public static boolean isBound() {
        return CURRENT.get() != null;
    }

    /** Clears the binding. Always called in a {@code finally} block. */
    public static void clear() {
        CURRENT.remove();
    }

    /** Runs {@code work} bound to {@code context}, restoring the previous binding after. */
    public static <T> T call(TenantContext context, Supplier<T> work) {
        TenantContext previous = CURRENT.get();
        CURRENT.set(context);
        try {
            return work.get();
        } finally {
            restore(previous);
        }
    }

    /** Runs {@code work} bound to {@code context}, restoring the previous binding after. */
    public static void run(TenantContext context, Runnable work) {
        call(context, () -> {
            work.run();
            return null;
        });
    }

    /** Copies the current binding onto another thread's hand-off. */
    public static Runnable wrap(Runnable task) {
        TenantContext captured = CURRENT.get();
        return () -> run(captured, task);
    }

    public static <T> Supplier<T> wrap(Supplier<T> task) {
        TenantContext captured = CURRENT.get();
        return () -> call(captured, task);
    }

    /** Resolves the context carried by an authentication, if it is one of ours. */
    public static Optional<TenantContext> from(Authentication authentication) {
        if (authentication instanceof TenantScopedAuthentication scoped) {
            return Optional.of(scoped.tenantContext());
        }
        return Optional.empty();
    }

    private static void restore(TenantContext previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}