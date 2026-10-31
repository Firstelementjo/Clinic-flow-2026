package com.firstelement.worker.runtime;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Dispatcher configuration.
 *
 * <h2>Nothing here is stored in the database</h2>
 *
 * <p>{@code V20261004000100} is explicit: "the intervals below are ILLUSTRATIVE. Lease window and
 * backoff come from configuration; the schema deliberately stores no policy." A policy that lived
 * in a row could be edited by whoever can edit rows.
 *
 * <h2>The lease window is the interesting number</h2>
 *
 * <p>{@code leaseSeconds} must exceed the slowest thing a handler can do. A worker that holds a
 * lease shorter than its own handler is guaranteed to double-deliver: the reaper reclaims the row
 * while the first attempt is still running, and the uniqueness that makes delivery idempotent then
 * has to carry the whole weight. 60 seconds with a 20-second provider timeout is the shape.
 */
@Validated
@ConfigurationProperties(prefix = "firstelement.worker.dispatcher")
public class DispatcherProperties {

    /**
     * Master switch.
     *
     * <p>Defaults to <b>false</b>, and the worker process's own {@code application.yml} turns it on.
     * That is the safe direction for a process that merely carries the queue classes on its
     * classpath: an API instance must not silently start claiming work because a module was added,
     * and "long-running work belongs in durable workers" (AGENTS.md) is better served by an
     * explicit switch than by a default.
     */
    private boolean enabled = false;

    /** Rows claimed per tenant per sweep. */
    @Min(1)
    private int batchSize = 20;

    /** How long a claim is owned. Must exceed the slowest handler. */
    @Min(5)
    private int leaseSeconds = 60;

    /** Sweep period. Small enough that queue lag is visible before users notice it. */
    @Min(50)
    private long pollMillis = 500;

    /** How often the reaper runs. Independent of the sweep so recovery is bounded. */
    @Min(200)
    private long reapMillis = 5_000;

    /** Rows the reaper reclaims per pass. */
    @Min(1)
    private int reapBatchSize = 100;

    /** Concurrent jobs for one tenant. The fairness ceiling. */
    @Min(1)
    private int maxInFlightPerTenant = 4;

    /** How long a graceful drain waits for in-flight jobs before the process exits. */
    @Min(1)
    private int drainSeconds = 20;

    /** Attempts older than this are "failed"; drives the queue-health failure rate. */
    @Min(60)
    private int failureWindowSeconds = 900;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getLeaseSeconds() {
        return leaseSeconds;
    }

    public void setLeaseSeconds(int leaseSeconds) {
        this.leaseSeconds = leaseSeconds;
    }

    public long getPollMillis() {
        return pollMillis;
    }

    public void setPollMillis(long pollMillis) {
        this.pollMillis = pollMillis;
    }

    public long getReapMillis() {
        return reapMillis;
    }

    public void setReapMillis(long reapMillis) {
        this.reapMillis = reapMillis;
    }

    public int getReapBatchSize() {
        return reapBatchSize;
    }

    public void setReapBatchSize(int reapBatchSize) {
        this.reapBatchSize = reapBatchSize;
    }

    public int getMaxInFlightPerTenant() {
        return maxInFlightPerTenant;
    }

    public void setMaxInFlightPerTenant(int maxInFlightPerTenant) {
        this.maxInFlightPerTenant = maxInFlightPerTenant;
    }

    public int getDrainSeconds() {
        return drainSeconds;
    }

    public void setDrainSeconds(int drainSeconds) {
        this.drainSeconds = drainSeconds;
    }

    public int getFailureWindowSeconds() {
        return failureWindowSeconds;
    }

    public void setFailureWindowSeconds(int failureWindowSeconds) {
        this.failureWindowSeconds = failureWindowSeconds;
    }
}