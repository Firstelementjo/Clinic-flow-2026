package com.firstelement.worker.queue;

import com.firstelement.security.TenantContext;

/**
 * The one shape of "durable asynchronous work" in this system.
 *
 * <h2>Contract</h2>
 *
 * <ul>
 *   <li><b>No external send may happen before the state change that justifies it is
 *       committed.</b> A handler receives a row that already exists.</li>
 *   <li><b>Idempotent.</b> The dispatcher is at-least-once: a crash after the side effect and
 *       before the completion write causes a re-delivery. The handler must therefore be safe to
 *       run twice, and the uniqueness that makes it safe lives in PostgreSQL, not in the handler's
 *       memory.</li>
 *   <li><b>Tenant-scoped.</b> The context is bound by {@code TenantScopedExecutor} before
 *       {@link #handle} is called; a handler must never build its own scope from the payload.</li>
 *   <li><b>No request thread.</b> Long-running work belongs here (AGENTS.md), never in a
 *       controller.</li>
 * </ul>
 *
 * <h2>Failure is a value</h2>
 *
 * <p>A handler reports failure by throwing; the dispatcher classifies it with
 * {@link RetryClassifier}. A handler that knows a failure is permanent throws
 * {@link PermanentFailureException} so the classification is a fact rather than a guess.
 */
public interface JobHandler {

    /** The queue this handler drains. One handler per queue. */
    QueueName queue();

    /**
     * Executes one attempt.
     *
     * @param context the validated tenant scope, already bound to the thread and the
     *                {@code SecurityContext}
     * @param job     the claimed unit of work
     * @return a short, redacted summary recorded as the attempt's {@code jobType} detail; must not
     *         contain customer content
     * @throws RuntimeException to fail; the dispatcher classifies it
     */
    String handle(TenantContext context, JobEnvelope job) throws Exception;
}