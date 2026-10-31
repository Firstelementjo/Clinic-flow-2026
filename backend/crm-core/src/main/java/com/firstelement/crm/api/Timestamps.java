package com.firstelement.crm.api;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * The one timestamp rendering rule for the CRM surface.
 *
 * <h2>Why a stored value is treated as UTC</h2>
 *
 * <p>Every timestamp column in this schema is {@code timestamp(3) without time zone}, so the
 * database carries no offset. The values were written by the legacy runtime from JavaScript
 * {@code Date} objects, which are instants, and the driver stored them as UTC wall-clock. The
 * stored value is therefore a UTC wall-clock reading, and reading it as
 * {@link LocalDateTime} and re-attaching {@link ZoneOffset#UTC} round-trips the original
 * instant exactly.
 *
 * <p>The alternative — attaching the JVM default zone — would make the same row render
 * differently on a Riyadh operator's machine and a London operator's machine, which is the
 * kind of drift a {@code timestamp without time zone} column exists to make impossible.
 *
 * <h2>Why the format is fixed</h2>
 *
 * <p>The legacy API emitted JavaScript {@code toISOString()}: always millisecond precision,
 * always {@code Z}. The compatibility surface must emit the identical string, so the format
 * is pinned here rather than left to a Jackson default that could change with a Spring Boot
 * upgrade. {@code timestamp(3)} means the millisecond digits are exact, not rounded.
 */
public final class Timestamps {

    private static final DateTimeFormatter ISO_MILLIS =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    private Timestamps() {
    }

    /** The legacy {@code toISOString()} rendering of a stored UTC wall-clock value. */
    public static String toIso(LocalDateTime stored) {
        if (stored == null) {
            return null;
        }
        return ISO_MILLIS.format(stored);
    }

    /** The same instant as a real {@link Instant}; used for age/waiting calculations. */
    public static Instant toInstant(LocalDateTime stored) {
        return stored == null ? null : stored.toInstant(ZoneOffset.UTC);
    }

    /** Parses a stored value back; used by the optimistic-concurrency {@code If-Match} token. */
    public static LocalDateTime parseStored(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        String trimmed = iso.trim();
        try {
            if (trimmed.endsWith("Z")) {
                return LocalDateTime.ofInstant(Instant.parse(trimmed), ZoneOffset.UTC);
            }
            return LocalDateTime.parse(trimmed);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                "Expected an ISO-8601 timestamp such as 2026-08-14T00:00:00.000Z, got: " + iso);
        }
    }
}