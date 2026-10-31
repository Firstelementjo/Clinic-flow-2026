package com.firstelement.crm.clinic;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The clinic list responses, as typed records rather than maps.
 *
 * <h2>Why this exists instead of {@code Map.of("items", ..., "count", ...)}</h2>
 *
 * <p>The first draft returned a {@code Map<String, Object>} from every list route. It worked,
 * and the OpenAPI document published each of those responses as a free-form string map — which is
 * exactly the ADR 0016 defect a second time: a generated client cannot tell an item from a count,
 * and {@code items} arrives as {@code Record<string, string>}.
 *
 * <p>The contract suite caught it by asserting that the roster projection is a typed component,
 * and it failed, which is the correct outcome for a document nobody had checked. A record makes
 * the shape the only shape the wire can carry, and the item type flows into the schema map
 * automatically.
 *
 * <h2>Why {@code nextCursor} is a component and not omitted</h2>
 *
 * <p>It is {@code null} on the last page, and {@code spring.jackson.default-property-inclusion:
 * non_null} omits it. That is the established convention in this API — {@code CursorPage} on the
 * customer and conversation routes behaves identically — so a client reads "no {@code nextCursor}
 * key" as "no further page" rather than "no cursor field was ever defined".
 *
 * @param items  the rows for this page, never null
 * @param count  {@code items().size()}. Published so a client does not have to count, and so a
 *               truncated page is distinguishable from a short one.
 */
public record ClinicCataloguePage<T>(List<T> items, int count) {

    public ClinicCataloguePage {
        items = items == null ? List.of() : List.copyOf(items);
        count = count == 0 ? items.size() : count;
    }

    /** A page of the roster or the catalogue. */
    public static <T> ClinicCataloguePage<T> of(List<T> rows) {
        return new ClinicCataloguePage<>(rows, rows == null ? 0 : rows.size());
    }
}
