package com.firstelement.admin.maintenance;

import com.firstelement.worker.queue.QueueName;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The closed list of queues whose handler has declared a replay safe.
 *
 * <h2>Why the declaration lives here and not on the handler</h2>
 *
 * <p>The Phase-7 rule is "Replay only for handlers that declare themselves replayable". The
 * {@code JobHandler} interface in {@code :backend:worker-runtime} has no such declaration and this
 * phase does not own that module, so the declaration is expressed here as an explicit table with a
 * written justification per entry.
 *
 * <p>The alternative — "replay anything that is {@code DEAD}" — is exactly the failure the rule
 * exists to prevent. {@code OutboxReplayService.replay} is idempotent and tenant-scoped, so a
 * careless replay is not a data breach; it is a <em>repeated side effect</em>, which is worse in the
 * one place it matters: a customer-visible AI send.
 *
 * <h2>Deny by default</h2>
 *
 * <p>A queue that is not in the table is refused. That includes every queue whose handler is
 * written in a later phase, and it includes a queue name this table has never heard of — so a new
 * {@code QueueName} constant is inert until someone adds a row here with a reason. Adding the row
 * is a reviewed code change; flipping a flag is not, and this is the difference.
 *
 * <p>{@link #explain(QueueName)} returns the reason alongside the decision so the refusal an
 * operator reads says <em>why</em> rather than "not allowed".
 */
public final class ReplaySafetyPolicy {

    private ReplaySafetyPolicy() {
    }

    /**
     * One declared entry.
     *
     * @param idempotencyEvidence what makes a second run a no-op. This is the whole argument: a
     *                             replay is only safe when something in PostgreSQL already refuses
     *                             the duplicate
     */
    public record Declaration(boolean replaySafe, String idempotencyEvidence) {
    }

    /**
     * The table.
     *
     * <p>Every entry names the uniqueness that absorbs the duplicate:
     *
     * <ul>
     *   <li>{@code incoming-message} — the provider-event reservation in {@code idempotency_records}
     *       plus {@code messages_projectId_channel_externalMessageId_key}: a redelivered inbound
     *       event produces no second message.</li>
     *   <li>{@code outbound-send} — {@code OutboundDeliveryService} refuses a row that is already
     *       {@code SENT}/{@code DELIVERED}/{@code READ}, so the second run cannot send twice.</li>
     *   <li>{@code notification-fanout} — the row is the record and the push is an optimization, so a
     *       second fan-out re-reads the same row and converges.</li>
     *   <li>{@code outbox-maintenance} — lease reaping and health refresh are idempotent by
     *       construction; the reaper only matches expired leases.</li>
     * </ul>
     *
     * <p>And every remaining queue is absent on purpose:
     * {@code ai-response} and {@code outbound-send}'s AI counterpart can reach a customer, so a
     * repeated send is a repeated customer-visible action; {@code scoring} and
     * {@code escalation-routing} mutate conversation state whose guard has not been proven
     * idempotent under repetition; {@code document-processing} and {@code analytics-rollup} have no
     * handler in this migration at all, so there is nothing to have declared anything.
     */
    private static final Map<QueueName, Declaration> TABLE = build();

    private static Map<QueueName, Declaration> build() {
        Map<QueueName, Declaration> table = new java.util.EnumMap<>(QueueName.class);
        table.put(QueueName.INCOMING_MESSAGE, new Declaration(true,
            "idempotency_records provider-event reservation plus "
                + "messages_projectId_channel_externalMessageId_key: a redelivered inbound event "
                + "produces no second message."));
        table.put(QueueName.OUTBOUND_SEND, new Declaration(true,
            "OutboundDeliveryService refuses a persisted row already SENT/DELIVERED/READ, so the "
                + "second attempt cannot send twice."));
        table.put(QueueName.NOTIFICATION_FANOUT, new Declaration(true,
            "The notification row is the record and the realtime push is an optimization; a second "
                + "fan-out re-reads the same row and converges."));
        table.put(QueueName.OUTBOX_MAINTENANCE, new Declaration(true,
            "Lease reaping matches only expired leases and is idempotent; health refresh is a "
                + "read."));
        return Map.copyOf(table);
    }

    /**
     * Whether this queue may be replayed.
     *
     * @param queue the queue, or {@code null} for a value that names no declared queue — which is
     *              itself a refusal, because {@code OutboxReplayService} treats an unknown
     *              {@code eventType} as a hard error
     */
    public static boolean isReplaySafe(QueueName queue) {
        return queue != null && TABLE.getOrDefault(queue, REFUSED).replaySafe();
    }

    /** The justification, or the reason for the refusal. */
    public static String explain(QueueName queue) {
        if (queue == null) {
            return "The event names no declared queue. An unknown eventType is refused rather than "
                + "guessed at, because a typo would otherwise be a second queue nothing ever drains.";
        }
        Declaration declaration = TABLE.get(queue);
        if (declaration == null) {
            return "No handler for '" + queue.wireValue() + "' has declared itself replay-safe in "
                + "this migration, so the policy refuses by default. Declaring it is a reviewed code "
                + "change to ReplaySafetyPolicy, with the idempotency evidence written down.";
        }
        return declaration.idempotencyEvidence();
    }

    /** The whole table, for the operator-facing catalogue. */
    public static List<Map<String, Object>> catalogue() {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (QueueName queue : QueueName.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("queue", queue.wireValue());
            row.put("queueClass", queue.queueClass().name());
            row.put("replaySafe", isReplaySafe(queue));
            row.put("evidence", explain(queue));
            rows.add(row);
        }
        return rows;
    }

    private static final Declaration REFUSED = new Declaration(false,
        "Not declared replay-safe.");
}