package com.firstelement.crm.api;

import java.util.UUID;

/**
 * The note view for {@code GET /api/v1/notes} — the legacy projection, unchanged.
 *
 * <h2>The legacy API returned three different note shapes</h2>
 *
 * <ul>
 *   <li>list for a resource: {@code id, resourceType, resourceId, body, authorUserId,
 *       author{id, fullName}, createdAt, updatedAt}</li>
 *   <li>list by project: the same plus {@code projectId}</li>
 *   <li>create: {@code id, resourceType, resourceId, body, authorUserId, projectId, createdAt}</li>
 *   <li>update: {@code id, body, updatedAt}</li>
 * </ul>
 *
 * <p>They are modelled as four records rather than one nullable-everything record, because the
 * alternative is a response whose key set varies with which method produced it — a client cannot
 * validate that, and a shared DTO invites "just add the field".
 *
 * <p>Note also that the author is a nested {@code {id, fullName}} object, not a flattened name.
 */
public final class NoteViews {

    private NoteViews() {
    }

    /** {@code GET /api/v1/notes?resourceType=&resourceId=}. */
    public record ForResource(UUID id,
                              String resourceType,
                              UUID resourceId,
                              String body,
                              UUID authorUserId,
                              AuthorRef author,
                              String createdAt,
                              String updatedAt) {
    }

    /** {@code GET /api/v1/notes/project}. The legacy selection added {@code projectId}. */
    public record ForProject(UUID id,
                             UUID projectId,
                             String resourceType,
                             UUID resourceId,
                             String body,
                             UUID authorUserId,
                             AuthorRef author,
                             String createdAt,
                             String updatedAt) {
    }

    /** {@code POST /api/v1/notes}. No author object and no {@code updatedAt}. */
    public record Created(UUID id,
                          String resourceType,
                          UUID resourceId,
                          String body,
                          UUID authorUserId,
                          UUID projectId,
                          String createdAt) {
    }

    /** {@code PATCH /api/v1/notes/{id}} — three fields, nothing else. */
    public record Updated(UUID id, String body, String updatedAt) {
    }

    /** The nested author projection the legacy select produced. */
    public record AuthorRef(UUID id, String fullName) {

        /** A note with no author is possible (a system-created note); legacy rendered {@code null}. */
        public static AuthorRef of(UUID id, String fullName) {
            return id == null ? null : new AuthorRef(id, fullName);
        }
    }
}