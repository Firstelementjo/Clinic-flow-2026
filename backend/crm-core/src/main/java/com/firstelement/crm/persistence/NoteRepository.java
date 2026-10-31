package com.firstelement.crm.persistence;

import com.firstelement.crm.query.CrmQueryScope;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code notes} — internal notes, in their own table.
 *
 * <h2>Why notes are not messages</h2>
 *
 * <p>A note never goes to a customer and never appears on a transcript. Keeping it in a separate
 * table is what makes "customer-facing history and internal history must remain separate"
 * (FR-3.4) structural rather than a filter somebody can forget: the transcript query selects
 * from {@code messages} and cannot see a note at all.
 *
 * <h2>Scope</h2>
 *
 * <p>The legacy service fell back to {@code organizationScope} when the caller named no
 * project, which would hand an {@code EMPLOYEE} with no memberships every note in the tenant.
 * This class always scopes through {@link CrmQueryScope}, whose non-pinned form restricts to
 * the principal's accessible projects. FR-1.4 ("a user with no membership must receive no
 * tenant data") wins over a legacy convenience, and the change is recorded in the phase report.
 *
 * <h2>Ordering and cap</h2>
 *
 * <p>Newest first, as legacy. The project-wide list is capped at 500 exactly as the legacy
 * {@code take: 500} was, so an unbounded workspace query cannot be turned into a full-table dump.
 */
@Repository
public class NoteRepository {

    static final String SELECT = """
        select n."id", n."organizationId", n."projectId", n."resourceType", n."resourceId",
               n."body", n."authorUserId", n."createdAt", n."updatedAt",
               u."fullName" as "authorFullName"
        from notes n
        left join users u on u."id" = n."authorUserId"
        """;

    static final RowMapper<NoteRow> ROW = (rs, rowNum) -> new NoteRow(
        rs.getObject("id", UUID.class),
        rs.getObject("organizationId", UUID.class),
        rs.getObject("projectId", UUID.class),
        rs.getString("resourceType"),
        rs.getObject("resourceId", UUID.class),
        rs.getString("body"),
        rs.getObject("authorUserId", UUID.class),
        rs.getObject("createdAt", LocalDateTime.class),
        rs.getObject("updatedAt", LocalDateTime.class),
        rs.getString("authorFullName"));

    private final NamedParameterJdbcTemplate jdbc;

    public NoteRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Notes attached to one record, newest first. */
    public List<NoteRow> forResource(CrmQueryScope scope,
                                     String resourceType,
                                     UUID resourceId,
                                     int limit) {
        CrmQueryScope.Statement statement = scope.select(SELECT, "n",
            "n.\"resourceType\" = :resourceType and n.\"resourceId\" = :resourceId",
            CrmQueryScope.params("resourceType", resourceType, "resourceId", resourceId))
            .orderedBy("n.\"createdAt\" desc, n.\"id\" desc", limit);
        return jdbc.query(statement.sql(), statement.parameters(), ROW);
    }

    /** Every note in the caller's accessible projects, newest first, capped. */
    public List<NoteRow> forProjects(CrmQueryScope scope, int limit) {
        CrmQueryScope.Statement statement = scope.select(SELECT, "n", null)
            .orderedBy("n.\"createdAt\" desc, n.\"id\" desc", limit);
        return jdbc.query(statement.sql(), statement.parameters(), ROW);
    }

    public java.util.Optional<NoteRow> findInScope(CrmQueryScope scope, UUID noteId) {
        CrmQueryScope.Statement statement = scope.select(SELECT, "n", "n.\"id\" = :noteId",
            CrmQueryScope.params("noteId", noteId));
        return jdbc.query(statement.sql(), statement.parameters(), ROW).stream().findFirst();
    }

    /** Inserts a note. {@code projectId} must come from the scope, not from the request body. */
    public NoteRow insert(CrmQueryScope scope,
                          UUID noteId,
                          String resourceType,
                          UUID resourceId,
                          String body,
                          UUID authorUserId,
                          LocalDateTime now) {
        jdbc.update("""
            insert into notes ("id", "organizationId", "projectId", "resourceType", "resourceId",
                "body", "authorUserId", "createdAt", "updatedAt")
            values (:id, :__fe_crm_organization_id, :__fe_crm_project_id, :resourceType,
                :resourceId, :body, :authorUserId, :now, :now)
            """, scope.bind(CrmQueryScope.params(
                "id", noteId, "resourceType", resourceType, "resourceId", resourceId,
                "body", body, "authorUserId", authorUserId, "now", now)));
        return new NoteRow(noteId, scope.organizationId(), scope.projectId(), resourceType,
            resourceId, body, authorUserId, now, now, null);
    }

    /**
     * Replaces a note's body.
     *
     * <p>Conditional on {@code updatedAt} when an {@code If-Match} token was supplied, so two
     * people editing the same note cannot lose one another's text silently.
     *
     * @return rows changed: 1 applied, 0 collided, 0 not in scope
     */
    public int updateBody(CrmQueryScope scope,
                          UUID noteId,
                          java.time.LocalDateTime expectedUpdatedAt,
                          String body,
                          LocalDateTime now) {
        Map<String, Object> parameters = CrmQueryScope.params(
            "noteId", noteId, "body", body, "now", now);
        StringBuilder predicate = new StringBuilder("n.\"id\" = :noteId");
        if (expectedUpdatedAt != null) {
            predicate.append(" and n.\"updatedAt\" = :expectedUpdatedAt");
            parameters.put("expectedUpdatedAt", expectedUpdatedAt);
        }
        CrmQueryScope.Statement statement = scope.update(
            "update notes n set \"body\" = :body, \"updatedAt\" = :now", "n", predicate.toString(),
            parameters);
        return jdbc.update(statement.sql(), statement.parameters());
    }

    /** Deletes a note; returns 1 when it was in scope, 0 when it was not there to delete. */
    public int delete(CrmQueryScope scope, UUID noteId) {
        CrmQueryScope.Statement statement = scope.delete(
            "delete from notes n", "n", "n.\"id\" = :noteId",
            CrmQueryScope.params("noteId", noteId));
        return jdbc.update(statement.sql(), statement.parameters());
    }

    /** One stored note. */
    public record NoteRow(UUID id,
                          UUID organizationId,
                          UUID projectId,
                          String resourceType,
                          UUID resourceId,
                          String body,
                          UUID authorUserId,
                          LocalDateTime createdAt,
                          LocalDateTime updatedAt,
                          String authorFullName) {
    }
}