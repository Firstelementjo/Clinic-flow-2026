package com.firstelement.worker.runtime;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

/**
 * A stable identity for this worker process, and the lease owner recorded on every claim.
 *
 * <h2>Why the id matters operationally</h2>
 *
 * <p>{@code outbox_events."leaseOwner"} is what {@code reference/outbox-claim.sql} §7 reports when
 * it reaps an expired lease: "An operator seeing rows here is seeing a worker that died." A
 * per-process id that names the host and the pid is therefore the difference between "reaped
 * {@code worker-a} on pod-7 pid 41" and "reaped <something>".
 *
 * <p>It must not contain a secret. It contains a hostname, a pid and a random process id, and it is
 * recorded in audit-visible columns.
 */
public final class WorkerIdentity {

    private final String workerId;

    public WorkerIdentity(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId is required: it is the lease owner");
        }
        this.workerId = workerId;
    }

    /** {@code fe-worker@<host>-<pid>-<short random>}. */
    public static WorkerIdentity fromProcess() {
        return new WorkerIdentity("fe-worker@" + host() + "-" + processId() + "-"
            + UUID.randomUUID().toString().substring(0, 8));
    }

    /** Deterministic identity for tests and for a single-instance local run. */
    public static WorkerIdentity named(String name) {
        return new WorkerIdentity(name);
    }

    public String workerId() {
        return workerId;
    }

    @Override
    public String toString() {
        return workerId;
    }

    private static String host() {
        try {
            String name = InetAddress.getLocalHost().getHostName();
            return name == null || name.isBlank() ? "unknown-host" : name;
        } catch (UnknownHostException e) {
            return "unknown-host";
        }
    }

    /** The Java 9+ process id, read reflectively so this class compiles on any JDK. */
    private static String processId() {
        try {
            return String.valueOf(ProcessHandle.current().pid());
        } catch (RuntimeException e) {
            return "0";
        }
    }
}