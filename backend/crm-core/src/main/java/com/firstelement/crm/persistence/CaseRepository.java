package com.firstelement.crm.persistence;

import com.firstelement.crm.query.CrmQueryScope;
import com.firstelement.security.TenantContext;
import com.firstelement.security.query.TenantQueryGuard;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The case tables: {@code case_classification_questions},
 * {@code conversation_classifications}, {@code case_resolutions} and
 * {@code case_solution_mappings}.
 *
 * <h2>Both case records are one-per-conversation, enforced by the database</h2>
 *
 * <p>{@code conversation_classifications."conversationId"} and
 * {@code "case_resolutions"."conversationId"} are each uniquely indexed, which is what makes
 * saving a classification or a resolution idempotent: a second save is an update of the same
 * row, not a second history entry. The legacy service relied on the same indexes for its Prisma
 * {@code upsert}.
 *
 * <h2>A question id from a client is a claim, not a fact</h2>
 *
 * <p>{@link #enabledQuestions} exists so {@code CaseService} can drop answers that reference a
 * question belonging to another project before anything is written, rather than trusting the id.
 */
@Repository
public class CaseRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final TenantQueryGuard guard;

    public CaseRepository(NamedParameterJdbcTemplate jdbc, TenantQueryGuard guard) {
        this.jdbc = jdbc;
        this.guard = guard;
    }

    // ------------------------------------------------------------------
    // classification questions (configuration)
    // ------------------------------------------------------------------

    static final RowMapper<QuestionRow> QUESTION = (rs, rowNum) -> new QuestionRow(
        rs.getObject("id", UUID.class),
        rs.getInt("position"),
        rs.getString("promptAr"),
        rs.getString("promptEn"),
        rs.getString("options"),
        rs.getBoolean("isRequired"),
        rs.getBoolean("isEnabled"));

    private static final String QUESTION_SELECT = """
        select q."id", q."position", q."promptAr", q."promptEn", q."options", q."isRequired",
               q."isEnabled"
        from case_classification_questions q
        """;

    /** Every question of the project, by position. Legacy returned all, enabled or not. */
    public List<QuestionRow> questions(CrmQueryScope scope) {
        CrmQueryScope.Statement statement = scope.select(QUESTION_SELECT, "q", null)
            .orderedBy("q.\"position\" asc", 50);
        return jdbc.query(statement.sql(), statement.parameters(), QUESTION);
    }

    /** Only the enabled questions, which is what an agent is shown and what answers may cite. */
    public List<QuestionRow> enabledQuestions(CrmQueryScope scope) {
        CrmQueryScope.Statement statement = scope.select(QUESTION_SELECT, "q", "q.\"isEnabled\" = true")
            .orderedBy("q.\"position\" asc", 50);
        return jdbc.query(statement.sql(), statement.parameters(), QUESTION);
    }

    public Optional<QuestionRow> questionInScope(CrmQueryScope scope, UUID questionId) {
        CrmQueryScope.Statement statement = scope.select(QUESTION_SELECT, "q", "q.\"id\" = :questionId",
            CrmQueryScope.params("questionId", questionId));
        return jdbc.query(statement.sql(), statement.parameters(), QUESTION).stream().findFirst();
    }

    public void insertQuestion(CrmQueryScope scope,
                               UUID questionId,
                               int position,
                               String promptAr,
                               String promptEn,
                               String optionsJson,
                               boolean isRequired,
                               boolean isEnabled,
                               LocalDateTime now) {
        try {
            jdbc.update("""
                insert into case_classification_questions ("id", "organizationId", "projectId",
                    "position", "promptAr", "promptEn", "options", "isRequired", "isEnabled",
                    "createdAt", "updatedAt")
                values (:id, :__fe_crm_organization_id, :__fe_crm_project_id, :position, :promptAr,
                    :promptEn, CAST(:options AS jsonb), :isRequired, :isEnabled, :now, :now)
                """, scope.bind(CrmQueryScope.params(
                    "id", questionId, "position", position, "promptAr", promptAr,
                    "promptEn", promptEn, "options", optionsJson, "isRequired", isRequired,
                    "isEnabled", isEnabled, "now", now)));
        } catch (DuplicateKeyException e) {
            throw new PositionAlreadyUsedException(position);
        }
    }

    public int updateQuestion(CrmQueryScope scope,
                              UUID questionId,
                              int position,
                              String promptAr,
                              String promptEn,
                              String optionsJson,
                              boolean isRequired,
                              boolean isEnabled,
                              LocalDateTime now) {
        try {
            CrmQueryScope.Statement statement = scope.update(
                """
                update case_classification_questions q set "position" = :position,
                    "promptAr" = :promptAr, "promptEn" = :promptEn,
                    "options" = CAST(:options AS jsonb), "isRequired" = :isRequired,
                    "isEnabled" = :isEnabled, "updatedAt" = :now
                """, "q", "q.\"id\" = :questionId",
                CrmQueryScope.params("questionId", questionId, "position", position,
                    "promptAr", promptAr, "promptEn", promptEn, "options", optionsJson,
                    "isRequired", isRequired, "isEnabled", isEnabled, "now", now));
            return jdbc.update(statement.sql(), statement.parameters());
        } catch (DuplicateKeyException e) {
            throw new PositionAlreadyUsedException(position);
        }
    }

    public int deleteQuestion(CrmQueryScope scope, UUID questionId) {
        CrmQueryScope.Statement statement = scope.delete(
            "delete from case_classification_questions q", "q", "q.\"id\" = :questionId",
            CrmQueryScope.params("questionId", questionId));
        return jdbc.update(statement.sql(), statement.parameters());
    }

    // ------------------------------------------------------------------
    // conversation classification
    // ------------------------------------------------------------------

    static final RowMapper<ClassificationRow> CLASSIFICATION = (rs, rowNum) -> new ClassificationRow(
        rs.getObject("id", UUID.class),
        rs.getObject("conversationId", UUID.class),
        rs.getString("answers"),
        rs.getObject("suggestedDocumentId", UUID.class),
        rs.getObject("suggestionScore", Double.class),
        rs.getObject("classifiedByUserId", UUID.class),
        rs.getObject("classifiedAt", LocalDateTime.class));

    private static final String CLASSIFICATION_SELECT = """
        select cc."id", cc."conversationId", cc."answers", cc."suggestedDocumentId",
               cc."suggestionScore", cc."classifiedByUserId", cc."classifiedAt"
        from conversation_classifications cc
        """;

    public Optional<ClassificationRow> classification(CrmQueryScope scope, UUID conversationId) {
        CrmQueryScope.Statement statement = scope.select(CLASSIFICATION_SELECT, "cc",
            "cc.\"conversationId\" = :conversationId",
            CrmQueryScope.params("conversationId", conversationId));
        return jdbc.query(statement.sql(), statement.parameters(), CLASSIFICATION).stream().findFirst();
    }

    /**
     * Inserts or updates the conversation's classification.
     *
     * <p>An explicit {@code on conflict ("conversationId") do update} rather than a read followed
     * by an insert: two agents answering the questions at the same time must not produce two
     * rows, and the unique index is the arbiter.
     */
    public void upsertClassification(CrmQueryScope scope,
                                     UUID classificationId,
                                     UUID conversationId,
                                     String answersJson,
                                     UUID suggestedDocumentId,
                                     Double suggestionScore,
                                     UUID classifiedByUserId,
                                     LocalDateTime now) {
        jdbc.update("""
            insert into conversation_classifications ("id", "organizationId", "projectId",
                "conversationId", "answers", "suggestedDocumentId", "suggestionScore",
                "classifiedByUserId", "classifiedAt", "updatedAt")
            values (:id, :__fe_crm_organization_id, :__fe_crm_project_id, :conversationId,
                CAST(:answers AS jsonb), :suggestedDocumentId, :suggestionScore,
                :classifiedByUserId, :now, :now)
            on conflict ("conversationId") do update set
                "answers" = excluded."answers",
                "suggestedDocumentId" = excluded."suggestedDocumentId",
                "suggestionScore" = excluded."suggestionScore",
                "classifiedByUserId" = excluded."classifiedByUserId",
                "classifiedAt" = excluded."classifiedAt",
                "updatedAt" = excluded."updatedAt"
            """, scope.bind(CrmQueryScope.params(
                "id", classificationId, "conversationId", conversationId, "answers", answersJson,
                "suggestedDocumentId", suggestedDocumentId, "suggestionScore", suggestionScore,
                "classifiedByUserId", classifiedByUserId, "now", now)));
    }

    // ------------------------------------------------------------------
    // case resolution
    // ------------------------------------------------------------------

    static final RowMapper<ResolutionRow> RESOLUTION = (rs, rowNum) -> new ResolutionRow(
        rs.getObject("id", UUID.class),
        rs.getObject("conversationId", UUID.class),
        rs.getString("customerIssue"),
        rs.getString("agentAction"),
        rs.getBoolean("usedKnowledgeBase"),
        rs.getObject("documentId", UUID.class),
        rs.getObject("customerAccepted", Boolean.class),
        rs.getObject("customerSatisfied", Boolean.class),
        rs.getBoolean("escalationRequested"),
        rs.getBoolean("escalatedToSupervisor"),
        rs.getString("outcome"),
        rs.getString("notes"),
        rs.getObject("recordedByUserId", UUID.class),
        rs.getObject("createdAt", LocalDateTime.class));

    private static final String RESOLUTION_SELECT = """
        select r."id", r."conversationId", r."customerIssue", r."agentAction", r."usedKnowledgeBase",
               r."documentId", r."customerAccepted", r."customerSatisfied", r."escalationRequested",
               r."escalatedToSupervisor", r."outcome", r."notes", r."recordedByUserId", r."createdAt"
        from case_resolutions r
        """;

    public Optional<ResolutionRow> resolution(CrmQueryScope scope, UUID conversationId) {
        CrmQueryScope.Statement statement = scope.select(RESOLUTION_SELECT, "r",
            "r.\"conversationId\" = :conversationId",
            CrmQueryScope.params("conversationId", conversationId));
        return jdbc.query(statement.sql(), statement.parameters(), RESOLUTION).stream().findFirst();
    }

    /** Whether a resolution exists. Required before a case may be closed. */
    public boolean hasResolution(CrmQueryScope scope, UUID conversationId) {
        return resolution(scope, conversationId).isPresent();
    }

    public void upsertResolution(CrmQueryScope scope,
                                 UUID resolutionId,
                                 UUID conversationId,
                                 String customerIssue,
                                 String agentAction,
                                 boolean usedKnowledgeBase,
                                 UUID documentId,
                                 Boolean customerAccepted,
                                 Boolean customerSatisfied,
                                 boolean escalationRequested,
                                 boolean escalatedToSupervisor,
                                 String outcome,
                                 String notes,
                                 UUID recordedByUserId,
                                 LocalDateTime now) {
        jdbc.update("""
            insert into case_resolutions ("id", "organizationId", "projectId", "conversationId",
                "customerIssue", "agentAction", "usedKnowledgeBase", "documentId",
                "customerAccepted", "customerSatisfied", "escalationRequested",
                "escalatedToSupervisor", "outcome", "notes", "recordedByUserId",
                "createdAt", "updatedAt")
            values (:id, :__fe_crm_organization_id, :__fe_crm_project_id, :conversationId,
                :customerIssue, :agentAction, :usedKnowledgeBase, :documentId, :customerAccepted,
                :customerSatisfied, :escalationRequested, :escalatedToSupervisor,
                cast(:outcome as "CaseOutcome"), :notes, :recordedByUserId, :now, :now)
            on conflict ("conversationId") do update set
                "customerIssue" = excluded."customerIssue",
                "agentAction" = excluded."agentAction",
                "usedKnowledgeBase" = excluded."usedKnowledgeBase",
                "documentId" = excluded."documentId",
                "customerAccepted" = excluded."customerAccepted",
                "customerSatisfied" = excluded."customerSatisfied",
                "escalationRequested" = excluded."escalationRequested",
                "escalatedToSupervisor" = excluded."escalatedToSupervisor",
                "outcome" = excluded."outcome",
                "notes" = excluded."notes",
                "recordedByUserId" = excluded."recordedByUserId",
                "updatedAt" = excluded."updatedAt"
            """, scope.bind(CrmQueryScope.params(
                "id", resolutionId, "conversationId", conversationId, "customerIssue", customerIssue,
                "agentAction", agentAction, "usedKnowledgeBase", usedKnowledgeBase,
                "documentId", documentId, "customerAccepted", customerAccepted,
                "customerSatisfied", customerSatisfied, "escalationRequested", escalationRequested,
                "escalatedToSupervisor", escalatedToSupervisor, "outcome", outcome, "notes", notes,
                "recordedByUserId", recordedByUserId, "now", now)));
    }

    // ------------------------------------------------------------------
    // solution mappings
    // ------------------------------------------------------------------

    /**
     * The project's enabled answer→document mappings, most specific first.
     *
     * <p>Order is {@code priority asc, id asc} — an explicit priority, never alphabetical, so an
     * ops team's deliberate ordering is what decides which solution wins.
     */
    public List<SolutionMappingRow> solutionMappings(CrmQueryScope scope) {
        CrmQueryScope.Statement statement = scope.select(
            """
            select m."id", m."matchAnswers", m."documentId", m."priority"
            from case_solution_mappings m
            """, "m", "m.\"isEnabled\" = true")
            .orderedBy("m.\"priority\" asc, m.\"id\" asc", 100);
        return jdbc.query(statement.sql(), statement.parameters(),
            (rs, rowNum) -> new SolutionMappingRow(
                rs.getObject("id", UUID.class),
                rs.getString("matchAnswers"),
                rs.getObject("documentId", UUID.class),
                rs.getInt("priority")));
    }

    /**
     * A document's title and its first chunk, for the suggestion projection.
     *
     * <p>Scoped like every other read, so a mapping cannot surface another project's document.
     */
    public Optional<DocumentExcerptRow> documentExcerpt(CrmQueryScope scope, UUID documentId) {
        CrmQueryScope.Statement statement = scope.select(
            """
            select d."id", d."title",
                   (select dc."content" from document_chunks dc
                     where dc."documentId" = d."id"
                       and dc."organizationId" = d."organizationId"
                       and dc."projectId" = d."projectId"
                     order by dc."chunkIndex" asc limit 1) as "firstChunk"
            from documents d
            """, "d", "d.\"id\" = :documentId", CrmQueryScope.params("documentId", documentId));
        return jdbc.query(statement.sql(), statement.parameters(),
            (rs, rowNum) -> new DocumentExcerptRow(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getString("firstChunk"))).stream().findFirst();
    }

    /** Whether a document exists in this project. A cited document must belong to it. */
    public boolean documentInProject(CrmQueryScope scope, UUID documentId) {
        CrmQueryScope.Statement statement = scope.select(
            "select count(*) as \"count\" from documents d", "d", "d.\"id\" = :documentId",
            CrmQueryScope.params("documentId", documentId));
        Integer count = jdbc.queryForObject(statement.sql(), statement.parameters(), Integer.class);
        return count != null && count > 0;
    }

    /**
     * An employee's display name, for the {@code recordedByName} projection.
     *
     * <p>{@code users} has no {@code projectId} column — it is a tenant-scoped table — so this
     * read uses {@code ScopedWhere} over the guard's tenant scope rather than
     * {@link CrmQueryScope}, whose pinned shape would emit a {@code "projectId"} predicate the
     * column does not have. A resolution recorded by a user outside the caller's tenant resolves
     * to {@code null} rather than leaking a name.
     */
    public Optional<String> userFullName(TenantContext context, UUID userId) {
        if (userId == null) {
            return Optional.empty();
        }
        com.firstelement.security.query.ScopedWhere.NamedSql query =
            com.firstelement.security.query.ScopedWhere.of(guard.tenantScope(context))
                .select("select u.\"fullName\" from users u", "u", "u.\"id\" = :userId",
                    Map.of("userId", userId));
        return jdbc.queryForList(query.sql(), query.parameters(), String.class).stream()
            .findFirst();
    }

    // ------------------------------------------------------------------

    /** Two admins wrote the same {@code (projectId, position)}. */
    public static class PositionAlreadyUsedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final int position;

        public PositionAlreadyUsedException(int position) {
            super("Another question already uses this position");
            this.position = position;
        }

        public int position() {
            return position;
        }
    }

    public record QuestionRow(UUID id,
                              int position,
                              String promptAr,
                              String promptEn,
                              String options,
                              boolean isRequired,
                              boolean isEnabled) {
    }

    public record ClassificationRow(UUID id,
                                    UUID conversationId,
                                    String answers,
                                    UUID suggestedDocumentId,
                                    Double suggestionScore,
                                    UUID classifiedByUserId,
                                    LocalDateTime classifiedAt) {
    }

    public record ResolutionRow(UUID id,
                                UUID conversationId,
                                String customerIssue,
                                String agentAction,
                                boolean usedKnowledgeBase,
                                UUID documentId,
                                Boolean customerAccepted,
                                Boolean customerSatisfied,
                                boolean escalationRequested,
                                boolean escalatedToSupervisor,
                                String outcome,
                                String notes,
                                UUID recordedByUserId,
                                LocalDateTime createdAt) {
    }

    public record SolutionMappingRow(UUID id,
                                     String matchAnswers,
                                     UUID documentId,
                                     int priority) {
    }

    public record DocumentExcerptRow(UUID id, String title, String firstChunk) {
    }
}