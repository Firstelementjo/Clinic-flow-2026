package com.firstelement.crm.api;

import java.util.UUID;

/**
 * {@code POST /api/v1/notes} and {@code PATCH /api/v1/notes/{id}} bodies.
 *
 * <h2>{@code projectId} on create is a request, not authorization</h2>
 *
 * <p>The service validates it against the caller's server-resolved project scope and then pins
 * the write to it. A project the caller cannot reach is refused, never silently widened to "any
 * project I can see".
 */
public record NoteRequest(String resourceType,
                          UUID resourceId,
                          String body,
                          UUID projectId) {

    public static final int MAX_BODY = 10_000;

    /** {@code PATCH} carries only the new text. */
    public record UpdateNoteRequest(String body, UUID projectId) {
    }
}