package com.firstelement.worker.queue;

/** The stored {@code outbox_events."eventType"} names no declared queue. */
public class UnknownQueueException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final String value;

    public UnknownQueueException(String value) {
        super("No declared queue is named '" + value + "'. An unknown eventType is dead-lettered "
            + "rather than discarded: silently dropping it would lose work the domain transaction "
            + "already promised.");
        this.value = value;
    }

    public String value() {
        return value;
    }
}