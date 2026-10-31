package com.firstelement.crm.query;

import java.util.List;
import java.util.function.Function;

/**
 * One page of a cursor-paginated list, and the only shape a CRM list returns.
 *
 * <h2>Why keyset and not offset</h2>
 *
 * <p>An inbox is read while new messages arrive. With {@code OFFSET} a customer message
 * that lands between two page fetches shifts every later row by one, and the employee
 * silently re-reads a conversation they already saw while losing the next one. The keyset
 * cursor pins the boundary to {@code (lastMessageAt, id)} — a value that never changes for
 * a given row — so a page is stable while the inbox moves underneath it.
 *
 * <p>This is also exactly what Prisma's {@code cursor}/{@code skip} did over
 * {@code orderBy: [{lastMessageAt: 'desc'}, {id: 'desc'}]}, which is why the ordering, the
 * tie-break, and the {@code limit + 1} over-fetch are all preserved.
 *
 * <h2>{@code nextCursor} is the id of the last returned row, and is {@code null} on the
 * last page — identical to the legacy {@code nextCursor: hasMore ? page.at(-1).id : null}.</h2>
 *
 * @param items      the rows for this page, never {@code null}
 * @param nextCursor the cursor to pass back for the following page, or {@code null}
 */
public record CursorPage<T>(List<T> items, String nextCursor) {

    public CursorPage {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /**
     * Slices an over-fetched result set (fetch {@code limit + 1}) into one page.
     *
     * @param rows     the over-fetched rows, in query order
     * @param limit    the requested page size
     * @param cursorOf extracts the cursor value from a row (the row id)
     */
    public static <T> CursorPage<T> of(List<T> rows, int limit, Function<T, String> cursorOf) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1, got " + limit);
        }
        boolean hasMore = rows.size() > limit;
        List<T> page = hasMore ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows);
        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            nextCursor = cursorOf.apply(page.get(page.size() - 1));
        }
        return new CursorPage<>(page, nextCursor);
    }

    /**
     * Deliberately not named {@code hasMore}/{@code isEmpty}.
     *
     * <p>Jackson turns {@code isXxx()} and {@code getXxx()} into JSON properties, so those two
     * names would have added {@code "more"} and {@code "empty"} to every paged response — a
     * legacy client validating the body would reject it, and the pagination contract would gain
     * two fields nobody asked for.
     */
    public boolean hasNextPage() {
        return nextCursor != null;
    }

    public boolean hasNoItems() {
        return items.isEmpty();
    }
}