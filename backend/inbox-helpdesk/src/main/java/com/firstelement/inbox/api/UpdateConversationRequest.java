package com.firstelement.inbox.api;

import com.firstelement.crm.domain.ConversationPriority;
import com.firstelement.crm.domain.ConversationStatus;

/**
 * {@code PATCH /api/v1/conversations/{id}} body — the legacy {@code updateConversationSchema}.
 *
 * <p>An absent field is not written; a JSON {@code null} for {@code subject} clears it. The
 * distinction is preserved because the legacy code spread the parsed object into Prisma's
 * {@code data}, which also left absent fields alone.
 */
public record UpdateConversationRequest(ConversationStatus status,
                                        ConversationPriority priority,
                                        String subject) {

    public static final int MAX_SUBJECT = 200;

    public boolean subjectProvided() {
        return subject != null;
    }
}