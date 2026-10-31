package com.firstelement.crm.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Ticket and CX numbering, with no database.
 *
 * <p>Formats are a wire contract: {@code conversations."ticketNumber"} is
 * {@code varchar(14)} and {@code customers."cxId"} is {@code varchar(9)}, so a number of the
 * wrong length is rejected by the database at insert time. These tests assert the exact strings.
 */
class TicketNumberTest {

    private static final LocalDateTime AUGUST_14 = LocalDateTime.of(2026, 8, 14, 10, 0);

    @Test
    @DisplayName("a ticket number is #MMDD + a 9-digit sequence")
    void ticketFormat() {
        assertThat(TicketNumber.generateTicketNumber(AUGUST_14, 1))
            .isEqualTo("#0814000000001");
        assertThat(TicketNumber.generateTicketNumber(AUGUST_14, 42))
            .isEqualTo("#0814000000042");
    }

    @Test
    @DisplayName("a ticket number is 14 characters, matching varchar(14)")
    void ticketLength() {
        String ticket = TicketNumber.generateTicketNumber(AUGUST_14, 123456789);

        assertThat(ticket).hasSize(14).startsWith("#").matches("^#\\d{13}$");
    }

    @Test
    @DisplayName("the month and day are zero padded")
    void ticketMonthAndDayPadding() {
        assertThat(TicketNumber.generateTicketNumber(LocalDateTime.of(2026, 1, 2, 0, 0), 1))
            .startsWith("#0102");
    }

    @Test
    @DisplayName("a CX id is #33 + a 6-digit sequence, matching varchar(9)")
    void cxFormat() {
        assertThat(TicketNumber.generateCxId(0)).isEqualTo("#33000000").hasSize(9);
        assertThat(TicketNumber.generateCxId(123456)).isEqualTo("#33123456").hasSize(9);
    }

    @Test
    @DisplayName("padding is ASCII, not locale-dependent")
    void paddingIsAscii() {
        // A locale that renders Eastern Arabic digits would break the unique index and every
        // client comparison; manual padding cannot drift with the JVM's default locale.
        String ticket = TicketNumber.generateTicketNumber(AUGUST_14, 7);

        assertThat(ticket).matches("^#\\d{13}$");
        assertThat(TicketNumber.generateCxId(7)).matches("^#33\\d{6}$");
    }

    @Test
    @DisplayName("a sequence wider than the field is not truncated")
    void longSequenceIsNotTruncated() {
        assertThat(TicketNumber.generateCxId(1234567890L)).isEqualTo("#331234567890");
    }

    @Test
    @DisplayName("day boundaries are computed from the timestamp's own date")
    void dayBoundaries() {
        LocalDateTime start = TicketNumber.startOfDay(AUGUST_14);
        LocalDateTime next = TicketNumber.startOfNextDay(AUGUST_14);

        assertThat(start).isEqualTo(LocalDateTime.of(2026, 8, 14, 0, 0));
        assertThat(next).isEqualTo(LocalDateTime.of(2026, 8, 15, 0, 0));
    }

    @Test
    @DisplayName("every enum round-trips through its database value")
    void enumRoundTrips() {
        for (ChannelType channel : ChannelType.values()) {
            assertThat(ChannelType.fromValue(channel.name())).isEqualTo(channel);
        }
        for (ConversationStatus status : ConversationStatus.values()) {
            assertThat(ConversationStatus.fromValue(status.name())).isEqualTo(status);
        }
        assertThatThrownBy(() -> ChannelType.fromValue("SMS"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(ConversationStatus.RESOLVED.isActive()).isFalse();
        assertThat(ConversationStatus.OPEN.isActive()).isTrue();
        assertThat(ConversationStatus.PENDING.isActive()).isTrue();
    }
}