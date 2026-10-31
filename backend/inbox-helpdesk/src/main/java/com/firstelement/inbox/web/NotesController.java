package com.firstelement.inbox.web;

import com.firstelement.crm.api.NoteRequest;
import com.firstelement.crm.api.NoteViews;
import com.firstelement.crm.service.NoteService;
import com.firstelement.security.TenantContextHolder;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/notes} — the legacy note routes, unchanged.
 *
 * <pre>
 *   GET    /api/v1/notes?resourceType=&resourceId=  NOTE_READ
 *   GET    /api/v1/notes/project?projectId=         NOTE_READ
 *   POST   /api/v1/notes                            NOTE_CREATE
 *   PATCH  /api/v1/notes/{id}                       NOTE_UPDATE
 *   DELETE /api/v1/notes/{id}                       NOTE_DELETE
 * </pre>
 *
 * <p>{@code If-Match} on the update carries the note's version token; a concurrent edit of the
 * same note is 409 {@code VERSION_CONFLICT} rather than one person's text silently replacing
 * another's.
 *
 * <p>{@code /project} is declared before {@code /{id}} for the same reason as
 * {@code /customers/cx/{cxId}}: the literal pattern must win over the path variable, and Spring
 * resolves the more specific pattern first.
 */
@RestController
@RequestMapping("/api/v1/notes")
public class NotesController {

    private final NoteService notes;

    public NotesController(NoteService notes) {
        this.notes = notes;
    }

    @GetMapping
    public List<NoteViews.ForResource> listForResource(@RequestParam(required = false) String resourceType,
                                          @RequestParam(required = false) String resourceId,
                                          @RequestParam(required = false) String projectId) {
        return notes.listForResource(TenantContextHolder.get(), resourceType,
            RequestParams.optionalUuid(resourceId, "resourceId"),
            RequestParams.optionalUuid(projectId, "projectId"));
    }

    @GetMapping("/project")
    public List<NoteViews.ForProject> listProject(@RequestParam(required = false) String projectId) {
        return notes.listProject(TenantContextHolder.get(),
            RequestParams.optionalUuid(projectId, "projectId"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public NoteViews.Created create(@Valid @RequestBody NoteRequest body) {
        return notes.create(TenantContextHolder.get(), body);
    }

    @PatchMapping("/{noteId}")
    public NoteViews.Updated update(
        @PathVariable String noteId,
        @Valid @RequestBody NoteRequest.UpdateNoteRequest body,
        @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return notes.update(TenantContextHolder.get(),
            RequestParams.requiredUuid(noteId, "noteId"), body, RequestParams.ifMatch(ifMatch));
    }

    @DeleteMapping("/{noteId}")
    public Map<String, String> delete(@PathVariable String noteId,
                                      @RequestParam(required = false) String projectId) {
        return notes.delete(TenantContextHolder.get(),
            RequestParams.requiredUuid(noteId, "noteId"),
            RequestParams.optionalUuid(projectId, "projectId"));
    }
}