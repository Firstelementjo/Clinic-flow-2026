package com.firstelement.crm.domain;

import java.time.LocalDateTime;

/**
 * Ticket and customer-reference numbering. A direct port of
 * {@code packages/shared/src/ticketing.ts}.
 *
 * <h2>Formats (unchanged)</h2>
 *
 * <pre>
 *   ticket  #MMDDXXXXXXXXX   13 digits + '#' = 14 chars, matching
 *                           conversations."ticketNumber" varchar(14)
 *   cx id   #33XXXXXX        6 digits + '#33' = 9 chars, matching customers."cxId" varchar(9)
 * </pre>
 *
 * <p>The sequence source is deliberately the <em>row count</em>, exactly as the legacy
 * implementation did it, because the legacy rows are numbered that way and reusing the
 * scheme is what keeps old and new tickets comparable in the UI.
 *
 * <h2>Why {@link #pad(long, int)} instead of {@code String.format}</h2>
 *
 * <p>{@code String.format("%09d", seq)} is locale-sensitive in exotic locales (an Arabic
 * locale may render Eastern Arabic digits) and would therefore produce a ticket number the
 * unique index and the client both treat as a different string. Manual padding cannot drift.
 *
 * <h2>Collisions</h2>
 *
 * <p>Two conversations created in the same second can compute the same sequence, and
 * {@code conversations_ticketNumber_key} is unique. {@link #nextTicketSequence} callers
 * retry with a bounded number of attempts; see
 * {@code com.firstelement.inbox.service.ConversationService}.
 */
public final class TicketNumber {

    private static final char TICKET_PREFIX = '#';
    private static final String CX_PREFIX = "#33";

    private TicketNumber() {
    }

    /**
     * {@code #MMDD} + a 9-digit zero-padded sequence.
     *
     * @param date the conversation's creation timestamp; month and day are read from it
     * @param seq  a 1-based per-day sequence
     */
    public static String generateTicketNumber(LocalDateTime date, long seq) {
        int month = date.getMonthValue();
        int day = date.getDayOfMonth();
        return TICKET_PREFIX
            + pad(month, 2)
            + pad(day, 2)
            + pad(seq, 9);
    }

    /**
     * {@code #33} + a 6-digit zero-padded sequence.
     *
     * <p><b>Not for allocating a customer reference.</b> The allocator is
     * {@code public.firstelement_next_customer_cx_id(uuid)} in the database
     * ({@code V20261007100000}); it returns the finished {@code #33XXXXXX} string and it is the
     * only supported way to obtain one — see ADR-0014. Passing its output through this method
     * double-formats it: {@code generateCxId(33000002)} is {@code #3333000002}, thirteen
     * characters, and customers."cxId" is {@code varchar(9)}.
     *
     * <p>This remains for the one case it is still the right tool for: reproducing the legacy
     * numbering deterministically when a <em>seed or migration</em> has to produce references
     * (the TypeScript packages call their own copy of the same function for exactly that).
     */
    public static String generateCxId(long seq) {
        return CX_PREFIX + pad(seq, 6);
    }

    /** Zero-pads without locale influence. */
    static String pad(long value, int width) {
        String digits = Long.toString(Math.abs(value));
        if (digits.length() >= width) {
            return digits;
        }
        return "0".repeat(width - digits.length()) + digits;
    }

    /** Midnight at the start of {@code date}'s day, and midnight at the start of the next. */
    public static LocalDateTime startOfDay(LocalDateTime date) {
        return date.toLocalDate().atStartOfDay();
    }

    public static LocalDateTime startOfNextDay(LocalDateTime date) {
        return date.toLocalDate().plusDays(1).atStartOfDay();
    }
}