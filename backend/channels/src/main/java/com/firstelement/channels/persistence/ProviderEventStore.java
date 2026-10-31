package com.firstelement.channels.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Provider-event deduplication, backed by {@code idempotency_records} with scope
 * {@code PROVIDER_EVENT}.
 *
 * <h2>What this is and is not responsible for</h2>
 *
 * <p>FR-4.4: "Use provider event IDs and database uniqueness for inbound idempotency. Queue job
 * IDs are an optimization, not the guarantee." Concretely, a duplicate webhook is stopped by three
 * independent layers and <b>this class is only the first</b>:
 *
 * <ol>
 *   <li>here: the provider event id is reserved in {@code idempotency_records} inside the same
 *       transaction that enqueues the intent, so a redelivery admitted a millisecond later is
 *       refused before any work is published;</li>
 *   <li>the outbox {@code idempotencyKey}, tenant-scoped unique, so even a bypass of (1) produces
 *       one intent;</li>
 *   <li>{@code messages_projectId_channel_externalMessageId_key}, which is what makes the
 *       <em>result</em> correct regardless of what the queue did. This is the guarantee; the other
 *       two are cheap early exits.</li>
 * </ol>
 *
 * <h2>The key is tenant-scoped and channel-scoped</h2>
 *
 * <p>Two tenants can both receive WhatsApp message {@code wamid.ABC} and neither should be told
 * the other already has it — the unique index leads with {@code organizationId} for that reason.
 * Within a tenant, the same provider id on two different channels is a different event, so the
 * channel is part of the key rather than part of the tenant.
 */
@Repository
public class ProviderEventStore {

    /** Scope recorded on the row. Matches {@code V20261004001000}'s documented scope list. */
    public static final String SCOPE = IdempotencyScope.PROVIDER_EVENT;

    /** The one reservation window: long enough to cover a redelivery burst. */
    private static final java.time.Duration RESERVATION_LOCK = java.time.Duration.ofMinutes(5);

    /** How long a provider-event record is kept before a retention purge may remove it. */
    private static final java.time.Duration RECORD_RETENTION = java.time.Duration.ofDays(30);

    /** Scopes on {@code idempotency_records}. Text, not an enum: the vocabulary will grow. */
    public static final class IdempotencyScope {
        private IdempotencyScope() {
        }

        public static final String PROVIDER_EVENT = "PROVIDER_EVENT";
        public static final String OUTBOUND_DELIVERY = "OUTBOUND_DELIVERY";
        public static final String API_REQUEST = "API_REQUEST";
    }

    private final NamedParameterJdbcTemplate jdbc;

    public ProviderEventStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Reserves {@code providerEventId} for this tenant, channel and connection.
     *
     * <p>Must run inside the caller's transaction: the reservation and the enqueue have the same
     * fate, so a webhook that cannot be admitted does not leave a "seen" marker behind, and an
     * admitted webhook always has its work queued.
     *
     * @return {@code true} when this caller is the first to see the event; {@code false} for a
     *         redelivery, which is a normal outcome and not an error
     */
    public boolean reserveFirstSighting(UUID organizationId, UUID projectId, String channel,
                                        String providerEventId, String correlationId) {
        String key = key(channel, providerEventId);
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("id", UUID.randomUUID())
            .addValue("org", organizationId)
            .addValue("project", projectId)
            .addValue("scope", SCOPE)
            .addValue("key", key)
            // The fingerprint is the channel the event arrived on: the same provider id on two
            // channels is a different event, and the reservation is what says so.
            .addValue("fingerprint", channel)
            .addValue("lockSeconds", RESERVATION_LOCK.toSeconds())
            .addValue("correlationId", correlationId)
            .addValue("expiresAt", java.time.LocalDateTime.now().plus(RECORD_RETENTION));
        // A `query` rather than `queryForObject`: ON CONFLICT DO NOTHING yields NO row at all when
        // the key is already reserved, and queryForObject turns that into
        // EmptyResultDataAccessException — which would make a redelivery look like a server error
        // instead of the normal, expected outcome it is.
        java.util.List<Integer> inserted = jdbc.query("""
            insert into idempotency_records (
                    "id", "organizationId", "projectId", "scope", "idempotencyKey",
                    "requestFingerprint", "state", "lockExpiresAt", "correlationId", "expiresAt",
                    "createdAt", "updatedAt")
            values (
                    :id, :org, :project, :scope, :key,
                    :fingerprint, 'IN_PROGRESS'::"IdempotencyState",
                    (now() AT TIME ZONE 'UTC') + make_interval(secs => :lockSeconds),
                    :correlationId, :expiresAt,
                    (now() AT TIME ZONE 'UTC'), (now() AT TIME ZONE 'UTC'))
            on conflict ("organizationId", "scope", "idempotencyKey") do nothing
            returning 1
            """, parameters, (rs, rowNum) -> rs.getInt(1));
        return !inserted.isEmpty();
    }

    /**
     * Completes the reservation, recording which row the event became.
     *
     * <p>Called after the intent is enqueued, in the same transaction. A crash between the reserve
     * and the complete leaves an {@code IN_PROGRESS} record whose lock expires, which is the
     * documented takeover path rather than a permanent suppression.
     */
    public void complete(UUID organizationId, String channel, String providerEventId,
                         String resourceType, UUID resourceId) {
        jdbc.update("""
            update idempotency_records
               set "state"          = 'COMPLETED'::"IdempotencyState",
                   "responseStatus" = 202,
                   "resourceType"   = :resourceType,
                   "resourceId"     = :resourceId,
                   "lockExpiresAt"  = null,
                   "updatedAt"      = (now() AT TIME ZONE 'UTC')
             where "organizationId" = :org
               and "scope" = :scope
               and "idempotencyKey" = :key
            """, new MapSqlParameterSource()
            .addValue("org", organizationId)
            .addValue("scope", SCOPE)
            .addValue("key", key(channel, providerEventId))
            .addValue("resourceType", resourceType)
            .addValue("resourceId", resourceId));
    }

    /** The record for one provider event, tenant-scoped. */
    public Optional<Reservation> find(UUID organizationId, String channel, String providerEventId) {
        return jdbc.query("""
            select "id", "state"::text as "state", "resourceType", "resourceId", "lockExpiresAt"
              from idempotency_records
             where "organizationId" = :org
               and "scope" = :scope
               and "idempotencyKey" = :key
            """, new MapSqlParameterSource()
            .addValue("org", organizationId)
            .addValue("scope", SCOPE)
            .addValue("key", key(channel, providerEventId)),
            (rs, rowNum) -> new Reservation(rs.getObject("id", UUID.class), rs.getString("state"),
                rs.getString("resourceType"), rs.getObject("resourceId", UUID.class),
                rs.getObject("lockExpiresAt", java.time.LocalDateTime.class))).stream().findFirst();
    }

    /**
     * The tenant-scoped, channel-scoped reservation key.
     *
     * <p>Length-bounded so it cannot become an unbounded column value from a hostile provider id.
     */
    public static String key(String channel, String providerEventId) {
        String bounded = providerEventId == null ? "" : providerEventId;
        if (bounded.length() > 180) {
            bounded = bounded.substring(0, 180);
        }
        return channel + ":" + bounded;
    }

    /** One provider-event reservation. */
    public record Reservation(UUID id, String state, String resourceType, UUID resourceId,
                              java.time.LocalDateTime lockExpiresAt) {
    }
}