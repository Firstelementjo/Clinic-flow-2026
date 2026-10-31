package com.firstelement.airag.drafts;

import com.firstelement.security.query.ScopedWhere;
import com.firstelement.security.query.TenantScope;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The durable home of a governed AI <b>draft</b>, and the queries that decide whether one may be
 * offered to a human.
 *
 * <h2>Why {@code notes} and not a new table</h2>
 *
 * <p>The mission's constraint is "the draft's home is the {@code ai_runs} row the run already writes.
 * No new table." {@code ai_runs} carries the <em>governance</em> of a turn — status, decision,
 * provider, model, latency, the refusal code, and the join key for every {@code ai_run_*} child row —
 * and {@link AiTraceRepository} already writes all of it. What it does not carry is the answer
 * <em>text</em>, and that is a deliberate omission rather than a gap: ADR 0007 Part 2 rule 4 and
 * FR-5.4 keep prose off the trace tables, and {@code AiRunResult} deliberately exposes the answer only
 * in memory. So the text needs a home, and the schema already has the right one:
 *
 * <ul>
 *   <li>{@code notes} is tenant- and project-scoped with an FK to {@code projects};</li>
 *   <li>it is polymorphic ({@code resourceType}, {@code resourceId}) with an index built for exactly
 *       this access pattern — {@code notes_organizationId_projectId_resourceType_resourceId_crea_idx};</li>
 *   <li>it is <b>not</b> {@code messages}. A note is never rendered as a sent message, never picked up
 *       by {@code OutboundSendJobHandler}, and never reaches a channel. That property is the whole
 *       reason the draft is stored here and not in a message row: the ruling is "a human still presses
 *       send", and the storage shape enforces it structurally rather than by remembering.</li>
 * </ul>
 *
 * <p>The alternative — adding an answer column to {@code ai_runs} — is a schema migration in a module
 * this phase does not own, and it would put customer-visible prose on a trace table that the
 * post-condition guard deliberately keeps free of text.
 *
 * <h2>Why {@code authorUserId} is null</h2>
 *
 * <p>The author of a draft is an AI agent, and {@code ai_runs} is where that is recorded, with the
 * agent, version, prompt, model and provider. A note's {@code authorUserId} names a <em>human</em>, and
 * attributing an AI-authored draft to whichever operator happened to be signed in would put a person's
 * name on text they did not write. It is left null.
 *
 * <h2>Why uniqueness is checked in the consumer, not by {@code ON CONFLICT}</h2>
 *
 * <p>An earlier draft of this comment claimed a partial unique index on
 * {@code (organizationId, resourceType, resourceId)} and used
 * {@code on conflict ... do nothing}. <b>No such index exists</b> — {@code notes} carries only a
 * <em>non-unique</em> {@code notes_organizationId_projectId_resourceType_resourceId_crea_idx} — so that
 * statement would have failed with "no unique or exclusion constraint matching the ON CONFLICT
 * specification" the first time a draft was stored, i.e. on the happy path of the feature.
 *
 * <p>The unique guarantee for a draft is {@code ai_runs."triggerMessageId"}: {@code AiTraceRepository
 * .startRun} documents that {@code on conflict} is deliberately absent there "because two runs for one
 * trigger message is a real defect and the caller dedupes on the conversation plus the trigger message
 * id before reaching here". That caller is {@code AiResponseJobHandler}, which is why exactly-one-run
 * is enforced there, and why a draft row is written only for a run that actually happened. Storing is
 * therefore a plain insert whose row count is reported, not an upsert that pretends to be atomic.
 */
@Repository
public class AiDraftRepository {

    /** {@code notes."resourceType"} for an AI draft. Namespaced so it cannot collide with a note kind. */
    public static final String RESOURCE_TYPE = "AiDraft";

    private static final String INSERT_DRAFT = """
        insert into notes ("id", "organizationId", "projectId", "resourceType", "resourceId", "body",
            "authorUserId", "createdAt", "updatedAt")
        values (:id, :__fe_tenant_organization_id, :__fe_tenant_project_id, :resourceType, :runId,
            :body, null, :now, :now)
        """;

    private static final String SELECT_DRAFT = """
        select n."id", n."body", n."createdAt"
          from notes n
         where n."organizationId" = :%s
           and n."projectId" = :%s
           and n."resourceType" = :resourceType
           and n."resourceId" = :runId
         limit 1
        """.formatted(ScopedWhere.ORGANIZATION_PARAM, ScopedWhere.PROJECT_PARAM);

    private static final String SELECT_LATEST = """
        select n."id", n."resourceId", n."body", n."createdAt"
          from notes n
          join conversations c
            on c."id" = :conversationId
           and c."organizationId" = :%s
           and c."projectId" = :%s
         where n."organizationId" = :%s
           and n."projectId" = :%s
           and n."resourceType" = :resourceType
           and n."resourceId" in (select r."id" from ai_runs r
                                   where r."organizationId" = :%s
                                     and r."projectId" = :%s
                                     and r."conversationId" = :conversationId
                                     and r."status" = cast('COMPLETED' as "AIRunStatus")
                                     and r."decision" is not null
                                     and r."decision" <> cast('NO_ACTION' as "AIRunDecision")
                                     and r."decisionReason" is distinct from :withheld
                                   order by r."startedAt" desc, r."id" desc
                                   limit 1)
           and c."mode" = cast('AI' as "ConversationMode")
        """.formatted(ScopedWhere.ORGANIZATION_PARAM, ScopedWhere.PROJECT_PARAM,
            ScopedWhere.ORGANIZATION_PARAM, ScopedWhere.PROJECT_PARAM,
            ScopedWhere.ORGANIZATION_PARAM, ScopedWhere.PROJECT_PARAM);

    private final NamedParameterJdbcTemplate jdbc;

    public AiDraftRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Stores the answer text for one completed run.
     *
     * <p>{@link Propagation#REQUIRES_NEW} for the same reason as {@code AiTraceRepository}: the run
     * commits independently of the caller's transaction, so the draft must not be able to roll back
     * with it and leave an {@code ANSWERED} run with nothing a human can read.
     *
     * @return rows written: 1 stored, 0 when the caller reached here twice for one run
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean storeDraft(TenantScope scope, UUID runId, String body, LocalDateTime now) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of());
        parameters.addValue("id", UUID.randomUUID());
        parameters.addValue("resourceType", RESOURCE_TYPE);
        parameters.addValue("runId", runId);
        parameters.addValue("body", body);
        parameters.addValue("now", now);
        return jdbc.update(INSERT_DRAFT, parameters) > 0;
    }

    /** The draft for one run, in the caller's scope. Empty for a run that produced none. */
    public Optional<DraftRow> findDraft(TenantScope scope, UUID runId) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of());
        parameters.addValue("resourceType", RESOURCE_TYPE);
        parameters.addValue("runId", runId);
        return jdbc.query(SELECT_DRAFT, parameters, (rs, rowNum) -> new DraftRow(
            rs.getObject("id", UUID.class), rs.getObject("resourceId", UUID.class),
            rs.getString("body"), rs.getObject("createdAt", LocalDateTime.class))).stream().findFirst();
    }

    /**
     * The newest offerable draft on a conversation, or empty.
     *
     * <p>Ordered newest-first by the run's {@code startedAt}, with {@code id} as the deterministic
     * tie-break so two runs started in the same millisecond cannot make the offered draft depend on
     * row order.
     *
     * <p><b>Two</b> conditions have to hold, and both are load-bearing:
     *
     * <ol>
     *   <li>{@code r."decisionReason" is distinct from :withheld} — the run-side half of the takeover
     *       guard. A run withheld at write time recorded {@code HUMAN_TAKEOVER} and is never offered.</li>
     *   <li>{@code c."mode" = 'AI'} — the conversation-side half. The write-side check is a point in
     *       time, and a human can take the conversation over <em>after</em> the draft was stored.
     *       Without this predicate the route would keep offering a draft for a conversation a human
     *       has already claimed, which is the exact outcome a takeover exists to prevent — reached
     *       through the read path instead of the write path.</li>
     * </ol>
     *
     * <p>The {@code conversations} join repeats the tenant predicate rather than trusting
     * {@code notes}'s: a join that could widen scope is the shape of the legacy cross-tenant breach,
     * and repeating the predicate costs nothing at this cardinality.
     */
    public Optional<DraftRow> latestOfferableDraft(TenantScope scope, UUID conversationId,
                                                   String withheldReason) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of());
        parameters.addValue("resourceType", RESOURCE_TYPE);
        parameters.addValue("conversationId", conversationId);
        parameters.addValue("withheld", withheldReason);
        return jdbc.query(SELECT_LATEST, parameters, (rs, rowNum) -> new DraftRow(
            rs.getObject("id", UUID.class), rs.getObject("resourceId", UUID.class),
            rs.getString("body"), rs.getObject("createdAt", LocalDateTime.class))).stream().findFirst();
    }

    /** Every draft note for a tenant/project, for the tests that assert what exists. */
    public List<DraftRow> allDrafts(TenantScope scope) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of());
        parameters.addValue("resourceType", RESOURCE_TYPE);
        return jdbc.query("""
            select n."id", n."resourceId", n."body", n."createdAt"
              from notes n
             where n."organizationId" = :__fe_tenant_organization_id
               and n."projectId" = :__fe_tenant_project_id
               and n."resourceType" = :resourceType
             order by n."createdAt" desc, n."id" desc
            """, parameters, (rs, rowNum) -> new DraftRow(
                rs.getObject("id", UUID.class), rs.getObject("resourceId", UUID.class),
                rs.getString("body"), rs.getObject("createdAt", LocalDateTime.class)));
    }

    /** One stored draft. {@code id} is the note id; {@code runId} is the {@code ai_runs} row it belongs to. */
    public record DraftRow(UUID id, UUID runId, String body, LocalDateTime createdAt) {
    }
}