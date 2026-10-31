package com.firstelement.crm.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Keyset pagination, with no database.
 *
 * <p>The behaviour that matters: a page is determined by an over-fetched result set, the cursor
 * is the <em>last returned row</em>, and it is {@code null} on the last page — exactly what the
 * legacy service returned, so a client's pagination loop keeps working unchanged.
 */
class CursorPageTest {

    private record Row(String id) {
    }

    private static List<Row> rows(int count) {
        return java.util.stream.IntStream.rangeClosed(1, count)
            .mapToObj(i -> new Row("row-" + i))
            .toList();
    }

    @Test
    @DisplayName("an over-fetched page is sliced to the limit")
    void slicesTheOverFetch() {
        CursorPage<Row> page = CursorPage.of(rows(4), 3, Row::id);

        assertThat(page.items()).hasSize(3);
        assertThat(page.items().get(2).id()).isEqualTo("row-3");
    }

    @Test
    @DisplayName("the cursor is the last returned row when more remain")
    void cursorIsTheLastReturnedRow() {
        CursorPage<Row> page = CursorPage.of(rows(4), 3, Row::id);

        assertThat(page.nextCursor()).isEqualTo("row-3");
        assertThat(page.hasNextPage()).isTrue();
    }

    @Test
    @DisplayName("the last page has a null cursor")
    void lastPageHasNoCursor() {
        CursorPage<Row> page = CursorPage.of(rows(3), 3, Row::id);

        assertThat(page.items()).hasSize(3);
        assertThat(page.nextCursor()).isNull();
        assertThat(page.hasNextPage()).isFalse();
    }

    @Test
    @DisplayName("an empty result set is an empty page, not a null list")
    void emptyResultSet() {
        CursorPage<Row> page = CursorPage.of(List.of(), 25, Row::id);

        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isNull();
        assertThat(page.hasNoItems()).isTrue();
    }

    @Test
    @DisplayName("a limit below 1 is a programming error, not a silent page of zero")
    void invalidLimitIsRefused() {
        assertThatThrownBy(() -> CursorPage.of(rows(3), 0, Row::id))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the page exposes no boolean bean getters Jackson would serialize")
    void noAccidentalJsonProperties() {
        // `hasMore`/`isEmpty` would have added "more" and "empty" to every paged response.
        CursorPage<Row> page = CursorPage.of(rows(2), 5, Row::id);
        List<String> beanGetters = java.util.Arrays.stream(CursorPage.class.getMethods())
            .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
            .map(java.lang.reflect.Method::getName)
            .filter(name -> (name.startsWith("get") || name.startsWith("is"))
                && !"getClass".equals(name))
            .toList();

        assertThat(beanGetters).isEmpty();
        assertThat(page.hasNoItems()).isFalse();
    }

    @Test
    @DisplayName("the page is immutable")
    void pageIsImmutable() {
        CursorPage<Row> page = CursorPage.of(rows(2), 5, Row::id);

        assertThatThrownBy(() -> page.items().add(new Row("x")))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}