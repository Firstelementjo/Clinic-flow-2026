package com.firstelement.worker.queue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The handler registry, built once from the beans on the classpath.
 *
 * <h2>Why a missing handler is a dead letter and not a skip</h2>
 *
 * <p>{@code outbox_events."eventType"} is the queue name, and it was written by a domain
 * transaction that has already committed. A queue with no handler therefore represents work the
 * product promised and cannot currently perform. Dropping it would lose a customer's message
 * silently — the failure mode AGENTS.md and FR-10.7 both forbid — so the dispatcher records a
 * non-retryable attempt with {@link RetryDecision#UNKNOWN_QUEUE} and dead-letters the row, where an
 * operator can see it and, once the handler is deployed, replay it.
 *
 * <h2>Duplicate registration is a startup failure</h2>
 *
 * <p>Two handlers for one queue would make delivery depend on bean ordering. That is a defect, and
 * it is reported at construction rather than being resolved by {@code Map.put} silently overwriting
 * one of them.
 */
@Component
public class JobHandlerRegistry {

    private static final Logger log = LoggerFactory.getLogger(JobHandlerRegistry.class);

    private final Map<QueueName, JobHandler> handlers;

    public JobHandlerRegistry(List<JobHandler> discovered) {
        Map<QueueName, JobHandler> byQueue = new HashMap<>();
        for (JobHandler handler : discovered == null ? List.<JobHandler>of() : discovered) {
            JobHandler existing = byQueue.put(handler.queue(), handler);
            if (existing != null) {
                throw new IllegalStateException("Two JobHandlers claim queue '" + handler.queue()
                    + "': " + existing.getClass().getName() + " and " + handler.getClass().getName()
                    + ". Delivery would depend on bean ordering.");
            }
        }
        this.handlers = Map.copyOf(byQueue);
        log.info("worker-runtime registered {} job handlers: {}", handlers.size(),
            handlers.keySet().stream().map(QueueName::wireValue).sorted().toList());
    }

    /** @return the handler, or empty when nothing drains this queue in this process. */
    public Optional<JobHandler> find(QueueName queue) {
        return Optional.ofNullable(handlers.get(queue));
    }

    /** The queues this process can actually drain. Exposed by the worker health indicator. */
    public Set<QueueName> registered() {
        return handlers.keySet();
    }

    /** Whether every declared queue has a handler; false is a health warning, not a crash. */
    public boolean coversEveryDeclaredQueue() {
        return handlers.keySet().containsAll(List.of(QueueName.values()));
    }
}