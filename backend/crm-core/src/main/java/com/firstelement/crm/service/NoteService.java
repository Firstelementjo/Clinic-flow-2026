package com.firstelement.crm.service;

import com.firstelement.crm.api.NoteRequest;
import com.firstelement.crm.api.NoteViews;
import com.firstelement.crm.api.Timestamps;
import com.firstelement.crm.error.ResourceNotFoundException;
import com.firstelement.crm.error.ValidationFailedException;
import com.firstelement.crm.error.VersionConflictException;
import com.firstelement.crm.persistence.NoteRepository;
import com.firstelement.crm.query.CrmQueryScope;
import com.firstelement.security.TenantContext;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.query.TenantQueryGuard;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal notes. A port of {@code apps/api/src/notes/notes.service.ts}.
 *
 * <h2>Preserved</h2>
 *
 * <ul>
 *   <li>Notes for a record are newest first; the project-wide list is capped at 500 rows exactly
 *       as the legacy {@code take: 500} was.</li>
 *   <li>Update and delete first prove the note is in scope, then write. Both raise the same 404
 *       for "not yours" and "not there".</li>
 *   <li>A note's project always comes from the scope, never from the body.</li>
 *   <li>Each route returns its own legacy shape; see {@link NoteViews}.</li>
 * </ul>
 *
 * <h2>Changed, deliberately</h2>
 *
 * <p>The legacy service fell back to {@code organizationScope} when no project was supplied,
 * which would show every note in the tenant to an employee whose membership list is empty.
 * FR-1.4 forbids that, so an unpinned call is scoped to the caller's <em>accessible projects</em>
 * instead of the whole organization. The phase report records this as a strengthening.
 *
 * <h2>Optimistic concurrency</h2>
 *
 * <p>{@code If-Match} → conditional update on {@code updatedAt}; a collision is 409
 * {@code VERSION_CONFLICT} with the current version, because two people editing one note and one
 * of them losing their text silently is the failure this prevents.
 */
@Service
public class NoteService {

    /** The legacy caps. Both were {@code take: 500} / unbounded respectively. */
    private static final int PROJECT_LIST_LIMIT = 500;
    private static final int RESOURCE_LIST_LIMIT = 500;

    private final NoteRepository notes;
    private final TenantQueryGuard guard;

    public NoteService(NoteRepository notes, TenantQueryGuard guard) {
        this.notes = notes;
        this.guard = guard;
    }

    @RequirePermission(Permission.NOTE_READ)
    public List<NoteViews.ForResource> listForResource(TenantContext context,
                                                      String resourceType,
                                                      UUID resourceId,
                                                      UUID requestedProjectId) {
        if (resourceType == null || resourceType.isBlank() || resourceId == null) {
            throw new ValidationFailedException("resourceType and resourceId are required");
        }
        CrmQueryScope scope = CrmQueryScope.forProject(guard, context, requestedProjectId);
        return notes.forResource(scope, resourceType, resourceId, RESOURCE_LIST_LIMIT).stream()
            .map(NoteService::toForResource)
            .toList();
    }

    @RequirePermission(Permission.NOTE_READ)
    public List<NoteViews.ForProject> listProject(TenantContext context, UUID requestedProjectId) {
        CrmQueryScope scope = CrmQueryScope.forProject(guard, context, requestedProjectId);
        return notes.forProjects(scope, PROJECT_LIST_LIMIT).stream()
            .map(NoteService::toForProject)
            .toList();
    }

    @RequirePermission(Permission.NOTE_CREATE)
    @Transactional
    public NoteViews.Created create(TenantContext context, NoteRequest request) {
        if (request == null || request.resourceType() == null || request.resourceType().isBlank()
            || request.resourceId() == null) {
            throw new ValidationFailedException("resourceType and resourceId are required");
        }
        validateBody(request.body());
        if (request.projectId() == null) {
            throw new ValidationFailedException("projectId is required");
        }
        CrmQueryScope scope = CrmQueryScope.forProject(guard, context, request.projectId());
        LocalDateTime now = LocalDateTime.now();
        NoteRepository.NoteRow row = notes.insert(scope, UUID.randomUUID(), request.resourceType(),
            request.resourceId(), request.body().trim(), context.userId(), now);
        return new NoteViews.Created(row.id(), row.resourceType(), row.resourceId(), row.body(),
            row.authorUserId(), row.projectId(), Timestamps.toIso(row.createdAt()));
    }

    @RequirePermission(Permission.NOTE_UPDATE)
    @Transactional
    public NoteViews.Updated update(TenantContext context,
                                    UUID noteId,
                                    NoteRequest.UpdateNoteRequest request,
                                    LocalDateTime expectedUpdatedAt) {
        if (request == null) {
            throw new ValidationFailedException("A body is required");
        }
        validateBody(request.body());
        CrmQueryScope scope = CrmQueryScope.forProject(guard, context, request.projectId());
        NoteRepository.NoteRow existing = notes.findInScope(scope, noteId)
            .orElseThrow(() -> new ResourceNotFoundException("Note"));
        int changed = notes.updateBody(scope, noteId, expectedUpdatedAt, request.body().trim(),
            LocalDateTime.now());
        if (changed == 0) {
            throw new VersionConflictException("Note", Timestamps.toIso(expectedUpdatedAt),
                Timestamps.toIso(existing.updatedAt()));
        }
        NoteRepository.NoteRow updated = notes.findInScope(scope, noteId)
            .orElseThrow(() -> new ResourceNotFoundException("Note"));
        return new NoteViews.Updated(updated.id(), updated.body(),
            Timestamps.toIso(updated.updatedAt()));
    }

    @RequirePermission(Permission.NOTE_DELETE)
    @Transactional
    public Map<String, String> delete(TenantContext context, UUID noteId,
                                      UUID requestedProjectId) {
        CrmQueryScope scope = CrmQueryScope.forProject(guard, context, requestedProjectId);
        // Prove the note is in scope before deleting: the same 404 for "not yours" and "gone".
        notes.findInScope(scope, noteId).orElseThrow(() -> new ResourceNotFoundException("Note"));
        notes.delete(scope, noteId);
        return Map.of("id", noteId.toString());
    }

    // ------------------------------------------------------------------

    private static void validateBody(String body) {
        if (body == null || body.trim().isEmpty()) {
            throw new ValidationFailedException("body is required");
        }
        if (body.trim().length() > NoteRequest.MAX_BODY) {
            throw new ValidationFailedException(
                "body must be at most " + NoteRequest.MAX_BODY + " characters");
        }
    }

    static NoteViews.ForResource toForResource(NoteRepository.NoteRow row) {
        return new NoteViews.ForResource(row.id(), row.resourceType(), row.resourceId(), row.body(),
            row.authorUserId(), NoteViews.AuthorRef.of(row.authorUserId(), row.authorFullName()),
            Timestamps.toIso(row.createdAt()), Timestamps.toIso(row.updatedAt()));
    }

    static NoteViews.ForProject toForProject(NoteRepository.NoteRow row) {
        return new NoteViews.ForProject(row.id(), row.projectId(), row.resourceType(),
            row.resourceId(), row.body(), row.authorUserId(),
            NoteViews.AuthorRef.of(row.authorUserId(), row.authorFullName()),
            Timestamps.toIso(row.createdAt()), Timestamps.toIso(row.updatedAt()));
    }
}