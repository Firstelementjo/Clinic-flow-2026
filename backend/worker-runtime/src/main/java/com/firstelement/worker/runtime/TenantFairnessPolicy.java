package com.firstelement.worker.runtime;

import com.firstelement.worker.queue.QueueClass;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-tenant and per-class fairness.
 *
 * <h2>The starvation this prevents</h2>
 *
 * <p>AGENTS.md: "tenant and project scope are mandatory in HTTP, service, repository, <b>jobs</b>,
 * exports, realtime, object storage, audit, finance, and vector retrieval" and "one tenant's
 * backlog must not starve another" (Phase 5a, outbox claim §2). A naive dispatcher that loops
 * "claim 50 rows for whoever is next" gives a tenant with 50 000 backlogged messages the entire
 * batch, every time, and the second tenant's customer waits.
 *
 * <p>The rotation here is <b>round-robin over tenants, one claim per tenant per sweep</b>. Tenant
 * A's backlog therefore cannot consume the sweep; A is served once, then B, then A again. Each
 * claim is still batch-sized so a single tenant does not need 50 round trips for 50 rows.
 *
 * <h2>Two independent limits</h2>
 *
 * <ul>
 *   <li>{@link #tryAcquireTenantSlot} caps <em>in-flight</em> work per tenant, so a slow provider
 *       for one tenant cannot occupy every dispatcher thread.</li>
 *   <li>{@link #tryAcquireClassSlot} reserves the worker pool per {@link QueueClass}, so a bulk
 *       backlog cannot delay a customer-facing reply.</li>
 * </ul>
 *
 * <p>Both are permit-style: a lease is taken before the work and released in a {@code finally},
 * including when the handler throws. A leaked permit is a permanently degraded worker, which is why
 * this class is not a counter someone else decrements.
 */
public class TenantFairnessPolicy {

    private final int maxInFlightPerTenant;
    private final Map<QueueClass, Integer> classSlots;
    private final Map<UUID, Integer> inFlightByTenant = new HashMap<>();
    private final Map<QueueClass, Integer> inFlightByClass = new HashMap<>();
    private final Deque<UUID> rotation = new ArrayDeque<>();

    /**
     * @param maxInFlightPerTenant hard cap on concurrent jobs for one tenant
     * @param totalConcurrency     the dispatcher pool size
     */
    public TenantFairnessPolicy(int maxInFlightPerTenant, int totalConcurrency) {
        this.maxInFlightPerTenant = Math.max(1, maxInFlightPerTenant);
        this.classSlots = new HashMap<>();
        for (QueueClass queueClass : QueueClass.values()) {
            this.classSlots.put(queueClass, queueClass.reservedSlots(totalConcurrency));
        }
    }

    /**
     * The next tenant to poll.
     *
     * <p>Rotates, so a sweep visits every tenant once before any tenant is visited twice — and a
     * tenant added mid-run is visited on the next sweep rather than after every existing one.
     *
     * <p>Use this only when a single tenant is being selected. A whole sweep must call
     * {@link #nextRound}: pairing {@code nextTenant} with a loop over the candidate list in its
     * natural order compares two independent orderings and skips every tenant — the rotation is
     * ahead of the loop, not aligned with it.
     *
     * @param candidates the active tenants for this sweep
     * @return the next tenant, or empty when there is none
     */
    public java.util.Optional<UUID> nextTenant(List<UUID> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return java.util.Optional.empty();
        }
        rotation.retainAll(candidates);
        if (rotation.isEmpty()) {
            rotation.addAll(candidates);
        }
        UUID next = rotation.pollFirst();
        rotation.addLast(next);
        return java.util.Optional.of(next);
    }

    /**
     * The order a whole sweep must visit its tenants in.
     *
     * <p>Every active tenant appears exactly once, starting where the previous sweep left off. This
     * is what makes "one tenant's backlog must not starve another" true of a sweep: tenant B is
     * served in the same sweep as tenant A, on the sweep A was already due for, regardless of how
     * deep A's backlog is.
     *
     * <p>The candidates arrive in a deterministic order from {@code TenantDirectory} and the
     * rotation preserves that on its first pass, so the behaviour is reproducible rather than
     * dependent on map iteration.
     *
     * @param candidates the active tenants for this sweep
     * @return the tenants in visit order; empty when there are none
     */
    public List<UUID> nextRound(List<UUID> candidates) {
        List<UUID> order = new ArrayList<>(candidates == null ? List.<UUID>of() : candidates);
        if (order.isEmpty()) {
            return List.of();
        }
        rotation.retainAll(order);
        if (rotation.isEmpty()) {
            rotation.addAll(order);
        }
        List<UUID> visits = new ArrayList<>(order.size());
        for (int i = 0; i < order.size(); i++) {
            UUID next = rotation.pollFirst();
            rotation.addLast(next);
            visits.add(next);
        }
        return List.copyOf(visits);
    }

    /** Whether this tenant may start another job now. */
    public boolean tryAcquireTenantSlot(UUID organizationId) {
        if (organizationId == null) {
            return false;
        }
        int current = inFlightByTenant.getOrDefault(organizationId, 0);
        if (current >= maxInFlightPerTenant) {
            return false;
        }
        inFlightByTenant.merge(organizationId, 1, Integer::sum);
        return true;
    }

    /** Whether this queue class may start another job now. */
    public boolean tryAcquireClassSlot(QueueClass queueClass) {
        if (queueClass == null) {
            return false;
        }
        int slot = classSlots.getOrDefault(queueClass, 1);
        int current = inFlightByClass.getOrDefault(queueClass, 0);
        if (current >= slot) {
            return false;
        }
        inFlightByClass.merge(queueClass, 1, Integer::sum);
        return true;
    }

    /** Releases both permits. Always call in a {@code finally}. */
    public void release(UUID organizationId, QueueClass queueClass) {
        decrement(inFlightByTenant, organizationId);
        decrement(inFlightByClass, queueClass);
    }

    private static <K> void decrement(Map<K, Integer> counters, K key) {
        if (key == null) {
            return;
        }
        counters.computeIfPresent(key, (k, value) -> value <= 1 ? null : value - 1);
    }

    /** Concurrent jobs for one tenant right now. Exposed by the health indicator. */
    public int inFlight(UUID organizationId) {
        return inFlightByTenant.getOrDefault(organizationId, 0);
    }

    /** Concurrent jobs in one queue class right now. */
    public int inFlight(QueueClass queueClass) {
        return inFlightByClass.getOrDefault(queueClass, 0);
    }

    /** A snapshot for the management-port health payload. */
    public Map<String, Integer> snapshot() {
        Map<String, Integer> snapshot = new HashMap<>();
        snapshot.put("tenantsInFlight", inFlightByTenant.size());
        snapshot.put("jobsInFlight", inFlightByTenant.values().stream().mapToInt(Integer::intValue).sum());
        for (Map.Entry<QueueClass, Integer> entry : classSlots.entrySet()) {
            snapshot.put("class." + entry.getKey().name() + ".reserved", entry.getValue());
            snapshot.put("class." + entry.getKey().name() + ".inFlight",
                inFlightByClass.getOrDefault(entry.getKey(), 0));
        }
        return snapshot;
    }

    /** Tenants currently holding at least one permit. */
    public List<UUID> saturatedTenants() {
        List<UUID> saturated = new ArrayList<>();
        inFlightByTenant.forEach((tenant, count) -> {
            if (count >= maxInFlightPerTenant) {
                saturated.add(tenant);
            }
        });
        return saturated;
    }

    public int maxInFlightPerTenant() {
        return maxInFlightPerTenant;
    }
}