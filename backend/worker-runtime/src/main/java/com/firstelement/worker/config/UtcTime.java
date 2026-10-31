package com.firstelement.worker.config;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * The only clock the durable queue is allowed to read.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Every timestamp in this schema is {@code timestamp(3) without time zone} and every stored value
 * is UTC — that is the Phase-5a contract, and the canonical statements compare with
 * {@code (now() AT TIME ZONE 'UTC')} precisely so a lease is judged against UTC "whatever the
 * connection's TimeZone is".
 *
 * <p>{@link LocalDateTime#now()} is the trap: it reads the <em>JVM's</em> default zone. On a host
 * configured for {@code Asia/Riyadh} a row written with it is three hours ahead of every comparison
 * the dispatcher makes, and the row becomes permanently un-claimable while looking perfectly
 * reasonable in the database. The DB-backed fixture found exactly that: a seeded
 * {@code nextAttemptAt} three hours in the future made the canonical claim return zero rows, which
 * looks like a broken predicate and is actually a broken clock.
 *
 * <h2>Why a class and not a bean</h2>
 *
 * <p>It has no state and no configuration. A bean would invite someone to make it configurable,
 * and a configurable zone is how this bug comes back.
 */
public final class UtcTime {

    private UtcTime() {
    }

    /** The current UTC wall clock, in the shape the schema stores. */
    public static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    /** The current instant, for an {@code Instant}-typed boundary. */
    public static Instant instant() {
        return Instant.now();
    }

    /** {@code now().minusSeconds(n)}, UTC. */
    public static LocalDateTime minusSeconds(long seconds) {
        return now().minusSeconds(seconds);
    }

    /** {@code now().plusSeconds(n)}, UTC. */
    public static LocalDateTime plusSeconds(long seconds) {
        return now().plusSeconds(seconds);
    }
}