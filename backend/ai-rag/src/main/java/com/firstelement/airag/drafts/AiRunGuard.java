package com.firstelement.airag.drafts;

import com.firstelement.security.TenantContext;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The exactly-once arbiter for "one AI run per inbound message".
 *
 * <h2>Why a claim and not a SELECT</h2>
 *
 * <p>A prior {@code SELECT} is the race the inbound pipeline already documents against: "a prior SELECT
 * would race under concurrent deliveries and double-post to the customer". The same is true here, and
 * the consequence is worse than a double post — it is a second provider call and a <b>second charge</b>
 * on the tenant's budget for one customer message.
 *
 * <p>So the arbiter is {@code idempotency_records}, whose
 * {@code idempotency_records_organizationId_scope_idempotencyKey_key} is UNIQUE on
 * {@code (organizationId, scope, idempotencyKey)}. The insert is {@code ON CONFLICT DO NOTHING} and the
 * affected-row count is the verdict. This is the same mechanism {@code :backend:channels} uses for
 * provider events, under a different {@code scope}, so the two stores cannot collide.
 *
 * <h2>Why the tenant predicate is present on the write and the delete</h2>
 *
 * <p>{@code organizationId} is bound from the caller's resolved {@link TenantContext}, never from the
 * job payload. A job that named another tenant's organization would insert a claim in its own name and
 * then be refused at {@code ConversationRepository.findInScope}, so the payload cannot widen anything —
 * but the predicate is written anyway, because "the payload cannot widen it" is a property of the
 * <em>whole</em> chain and this is the one link that writes.
 *
 * <h2>Why a claim is released rather than deleted on failure</h2>
 *
 * <p>{@code V20261004001000} states the state machine: {@code IN_PROGRESS} while the work runs,
 * {@code COMPLETED} when it produced something, {@code FAILED} when it could not. A release is a
 * transition to {@code FAILED} plus clearing the lock, so the dispatcher can retry a transient failure
 * instead of being permanently suppressed by a claim left behind by a crash. The record is never
 * deleted — "a record is never deleted to make room" — so the attempt stays visible.
 */
@Repository
public class AiRunGuard {

    /** {@code idempotency_records."scope"} for an AI turn. Distinct from channels' PROVIDER_EVENT. */
    public static final String SCOPE = "AI_RESPONSE";

    /**
     * How long a claim is treated as in-flight.
     *
     * <p>Long enough that a slow provider plus a slow database cannot have two workers believe they own
     * the same turn, short enough that a crashed worker's claim is not a permanent suppression. Five
     * minutes against a provider per-attempt timeout measured in seconds.
     */
    private static final int CLAIM_WINDOW_MINUTES = 5;

    private static final String CLAIM = """
        insert into idempotency_records ("id", "organizationId", "projectId", "scope", "idempotencyKey",
            "state", "lockExpiresAt", "correlationId", "createdAt", "updatedAt")
        values (:id, :organizationId, :projectId, :scope, :key,
            cast('IN_PROGRESS' as "IdempotencyState"), :lockExpiresAt, :correlationId, :now, :now)
        on conflict ("organizationId", "scope", "idempotencyKey") do nothing
        """;

    /**
     * The terminal transition.
     *
     * <p>There is no {@code COMPLETED} form here, and the absence is deliberate — see
     * {@link #complete}. {@code idempotency_records_completed_result_check} requires a
     * {@code responseStatus} whenever the state is {@code COMPLETED}, and a job has no HTTP response
     * to record. Writing a fake status to satisfy it would be a fabricated answer.
     */
    private static final String FAIL = """
        update idempotency_records
           set "state" = cast('FAILED' as "IdempotencyState"),
               "resourceType" = :resourceType,
               "resourceId" = :runId,
               "responseBodyHash" = :runHash,
               "lockExpiresAt" = null,
               "updatedAt" = :now
         where "organizationId" = :organizationId
           and "scope" = :scope
           and "idempotencyKey" = :key
        """;

    private static final String FIND = """
        select r."id", r."resourceId"
          from idempotency_records r
         where r."organizationId" = :organizationId
           and r."scope" = :scope
           and r."idempotencyKey" = :key
         limit 1
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public AiRunGuard(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Attempts to become the single owner of the turn for one inbound message.
     *
     * @return {@link Claim#won()} true for the caller that inserted the row; false for a redelivery,
     *         whose {@link Claim#runId()} is the run the winner produced (or null if it had not
     *         finished yet)
     */
    public Claim claim(TenantContext context, UUID projectId, UUID messageId, String correlationId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("id", UUID.randomUUID())
            .addValue("organizationId", context.organizationId())
            .addValue("projectId", projectId)
            .addValue("scope", SCOPE)
            .addValue("key", keyFor(messageId))
            .addValue("lockExpiresAt", now.plusMinutes(CLAIM_WINDOW_MINUTES))
            .addValue("correlationId", correlationId == null ? "" : correlationId)
            .addValue("now", now);

        if (jdbc.update(CLAIM, parameters) > 0) {
            return new Claim(true, null);
        }
        // Someone already owns it. Reading the row is only for the log line and the test assertion;
        // nothing branches on it, because the insert's row count already decided.
        return new Claim(false, existingRunId(context, messageId));
    }

    /**
     * Marks the claim finished, naming the run it produced.
     *
     * <p>The run id is an <b>argument</b> rather than a field of {@link Claim} because the claim is
     * taken before the run exists. Threading it back through the record would mean a mutable claim
     * shared across the run, which is the handler's memory deciding something the database should.
     * Neither the answer nor its hash is stored: {@code responseBodyHash} carries the run id so the
     * record stays operational metadata and never a second copy of customer data.
     *
     * <p>The terminal state is {@code FAILED}, not {@code COMPLETED}, and that is the schema's own
     * ruling rather than a convenience. {@code idempotency_records_completed_result_check} is the
     * biconditional {@code ("state" = 'COMPLETED') = ("responseStatus" IS NOT NULL)}, and its stated
     * meaning is "a finished record must be able to say what it produced". This record has no HTTP
     * response — a job is not a request — so claiming {@code COMPLETED} would require inventing a
     * status code to satisfy a constraint, which is precisely "a stored answer the operation never
     * produced". {@code FAILED} plus {@code resourceId} naming the run is the honest terminal state:
     * the turn will not be run again, and the row points an operator at what it produced.
     */
    public void complete(TenantContext context, UUID projectId, UUID messageId, Claim claim,
                         UUID runId) {
        if (!claim.won()) {
            return;
        }
        finish(context, projectId, messageId, FAIL, "AiRun", runId);
    }

    /**
     * Releases the claim so a retry is possible.
     *
     * <p>Also {@code FAILED}: the distinction between "finished" and "retryable" is carried by the
     * presence of a {@code resourceId} naming a run — a released claim has none — not by the state
     * enum, which the schema reserves for the HTTP-shaped vocabulary it was designed around.
     */
    public void release(TenantContext context, UUID projectId, UUID messageId, Claim claim) {
        if (!claim.won()) {
            return;
        }
        finish(context, projectId, messageId, FAIL, null, null);
    }

    private void finish(TenantContext context, UUID projectId, UUID messageId, String sql,
                        String resourceType, UUID runId) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("organizationId", context.organizationId())
            .addValue("scope", SCOPE)
            .addValue("key", keyFor(messageId))
            .addValue("resourceType", resourceType)
            // Bound as the UUID itself, not its text. `idempotency_records."resourceId"` and
            // `"responseBodyHash"` are uuid and text respectively, and a String bound to the uuid
            // column fails at run time with `column "resourceId" is of type uuid but expression is of
            // type character varying` -- which is why this is written once, here, rather than at each
            // call site with a different assumption about the column's type.
            .addValue("runId", runId)
            .addValue("runHash", runId == null ? null : runId.toString())
            .addValue("now", LocalDateTime.now(ZoneOffset.UTC));
        jdbc.update(sql, parameters);
    }

    private UUID existingRunId(TenantContext context, UUID messageId) {
        return jdbc.query(FIND, Map.of("organizationId", context.organizationId(), "scope", SCOPE,
                "key", keyFor(messageId)),
            (rs, rowNum) -> rs.getObject("resourceId", UUID.class)).stream().findFirst().orElse(null);
    }

    /**
     * The idempotency key for a trigger message.
     *
     * <p>Keyed on the message alone, not on the conversation: a message belongs to exactly one
     * conversation, and including the conversation would let the same message be claimed twice by two
     * jobs that disagreed about where it lived.
     */
    static String keyFor(UUID messageId) {
        return "ai-response:" + messageId;
    }

    /**
     * The outcome of a claim.
     *
     * @param won   whether this caller inserted the row and therefore owns the turn
     * @param runId the run a previous owner recorded, or null if it had not finished. Only ever a
     *              reported fact for logging and assertions; never an input to a decision
     */
    public record Claim(boolean won, UUID runId) {
    }
}