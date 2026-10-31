package com.firstelement.worker.queue;

/**
 * Queue <em>class</em>: what a queue is competing for, not what it does.
 *
 * <h2>Why the legacy single worker pool is not enough</h2>
 *
 * <p>The BullMQ worker ran every queue on one process with per-queue concurrency numbers, which
 * means a burst of document ingestion (concurrency 2) and a customer's WhatsApp reply share one
 * event loop and one saturation signal. Two tenants' queues also shared it, so one tenant's
 * backlog could delay another's customer-visible reply indefinitely — which is exactly the
 * starvation AGENTS.md forbids ("tenant and project scope are mandatory in ... jobs").
 *
 * <p>A class splits the pool into reserved shares:
 *
 * <table>
 *   <caption>Reserved share of the worker pool</caption>
 *   <tr><th>Class</th><th>Share</th><th>Why</th></tr>
 *   <tr><td>{@link #INTERACTIVE}</td><td>50%</td>
 *       <td>A customer is waiting. A backlog of anything else must not delay this.</td></tr>
 *   <tr><td>{@link #DEFAULT}</td><td>30%</td>
 *       <td>Normal business work: AI turns, scoring, notifications.</td></tr>
 *   <tr><td>{@link #BULK}</td><td>15%</td>
 *       <td>Expensive, rate-limited, batch-shaped. Runs when the pool has room.</td></tr>
 *   <tr><td>{@link #MAINTENANCE}</td><td>5%</td>
 *       <td>Self-healing. Never allowed to consume the whole pool, or the queue it repairs
 *           stops being repaired.</td></tr>
 * </table>
 *
 * <p>The shares are a floor, not a ceiling: a class may use more when the others are idle, because
 * leaving capacity unused while a customer waits is the worse failure. What is forbidden is one
 * class taking the pool from another — that is what {@link TenantFairnessPolicy} enforces
 * separately, per tenant.
 */
public enum QueueClass {

    /** Customer-facing latency: inbound messages and outbound sends. */
    INTERACTIVE(0.50d),

    /** Normal business work with no external latency promise. */
    DEFAULT(0.30d),

    /** Expensive or batch-shaped work: embedding, rollups. */
    BULK(0.15d),

    /** Queue self-healing: lease reaping and health refresh. */
    MAINTENANCE(0.05d);

    private final double reservedShare;

    QueueClass(double reservedShare) {
        this.reservedShare = reservedShare;
    }

    /** The fraction of the worker pool this class may always count on, in {@code (0,1]}. */
    public double reservedShare() {
        return reservedShare;
    }

    /**
     * The number of slots reserved for this class.
     *
     * <p>{@code max(1, ...)} so a one-thread worker still runs maintenance work; a pool with no
     * maintenance slot can never reap a crashed worker's lease.
     */
    public int reservedSlots(int totalConcurrency) {
        if (totalConcurrency <= 0) {
            return 0;
        }
        return Math.max(1, (int) Math.floor(totalConcurrency * reservedShare));
    }
}