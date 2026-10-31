package com.firstelement.inbox.api;

import com.firstelement.crm.domain.ChannelType;
import com.firstelement.crm.domain.InboxFilter;
import java.util.UUID;

/**
 * {@code GET /api/v1/conversations} — the legacy {@code inboxQuerySchema}, unchanged.
 *
 * @param projectId optional; narrows the scope. Its absence means "every project I can see",
 *                  never "every project"
 * @param filter    the saved view; the legacy default is {@code assigned-to-me}
 * @param limit     1..100, default 30 (legacy)
 * @param cursor    the id of the last row of the previous page
 */
public record InboxQuery(UUID projectId,
                         InboxFilter filter,
                         ChannelType channel,
                         String search,
                         UUID cursor,
                         int limit) {

    public static final int DEFAULT_LIMIT = 30;
    public static final int MAX_LIMIT = 100;
    public static final int MAX_SEARCH = 200;

    public InboxQuery {
        if (filter == null) {
            filter = InboxFilter.DEFAULT;
        }
        if (limit <= 0) {
            limit = DEFAULT_LIMIT;
        }
        if (limit > MAX_LIMIT) {
            limit = MAX_LIMIT;
        }
    }

    public static InboxQuery defaults() {
        return new InboxQuery(null, null, null, null, null, DEFAULT_LIMIT);
    }
}