package com.firstelement.crm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The timestamp rendering rule, with no database.
 *
 * <p>Two properties matter and both are asserted here: the rendering is byte-identical to the
 * legacy JavaScript {@code toISOString()}, and it does not depend on the JVM's default zone. The
 * second is what stops the same row reading differently on a Riyadh operator's machine and a
 * London operator's machine.
 */
class TimestampsTest {

    private static final LocalDateTime STORED = LocalDateTime.of(2026, 8, 14, 9, 30, 15, 123_000_000);

    @Test
    @DisplayName("a stored value renders as the legacy toISOString() string")
    void isoRendering() {
        assertThat(Timestamps.toIso(STORED)).isEqualTo("2026-08-14T09:30:15.123Z");
    }

    @Test
    @DisplayName("millisecond precision is always present")
    void alwaysMilliseconds() {
        assertThat(Timestamps.toIso(LocalDateTime.of(2026, 1, 1, 0, 0))).isEqualTo("2026-01-01T00:00:00.000Z");
    }

    @Test
    @DisplayName("a null renders as null rather than an empty string")
    void nullIsNull() {
        assertThat(Timestamps.toIso(null)).isNull();
        assertThat(Timestamps.toInstant(null)).isNull();
        assertThat(Timestamps.parseStored(null)).isNull();
        assertThat(Timestamps.parseStored("  ")).isNull();
    }

    @Test
    @DisplayName("rendering does not depend on the JVM default zone")
    void independentOfDefaultZone() {
        java.util.TimeZone original = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Riyadh"));
            String riyadh = Timestamps.toIso(STORED);
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/London"));
            String london = Timestamps.toIso(STORED);
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"));
            String newYork = Timestamps.toIso(STORED);

            assertThat(riyadh).isEqualTo(london).isEqualTo(newYork);
        } finally {
            java.util.TimeZone.setDefault(original);
        }
    }

    @Test
    @DisplayName("the stored value round-trips through the ETag token")
    void roundTrip() {
        assertThat(Timestamps.parseStored(Timestamps.toIso(STORED))).isEqualTo(STORED);
        assertThat(Timestamps.parseStored("2026-08-14T09:30:15.123Z")).isEqualTo(STORED);
        assertThat(Timestamps.parseStored("2026-08-14T09:30:15.123")).isEqualTo(STORED);
    }

    @Test
    @DisplayName("a malformed token is rejected rather than silently ignored")
    void malformedTokenIsRejected() {
        assertThatThrownBy(() -> Timestamps.parseStored("not-a-timestamp"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Timestamps.parseStored("2026-13-45T99:99:99Z"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the preview truncation matches the legacy 140-character rule")
    void previewTruncation() {
        String long140 = "a".repeat(140);
        assertThat(ConversationSummary.preview(long140)).isEqualTo(long140);
        assertThat(ConversationSummary.preview("a".repeat(141)))
            .isEqualTo("a".repeat(140) + "…");
        assertThat(ConversationSummary.preview(null)).isNull();
    }
}