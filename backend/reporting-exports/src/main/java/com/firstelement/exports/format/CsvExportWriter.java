package com.firstelement.exports.format;

import com.firstelement.exports.catalog.ReportField;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Streaming RFC 4180 CSV.
 *
 * <h2>The four things this writer has to get right, and how each is proven</h2>
 *
 * <ol>
 *   <li><b>Arabic is written as UTF-8 bytes, never as an escape.</b> The encoder is
 *       {@link StandardCharsets#UTF_8} and nothing inspects the content: no backslash-u escape, no
 *       HTML entity, no transliteration. A round-trip test asserts the Arabic string is present
 *       byte-for-byte in the output and that the output is valid UTF-8 that decodes back to the
 *       original.</li>
 *   <li><b>Quoting follows RFC 4180 exactly.</b> A field is quoted when it contains a comma, a double
 *       quote, CR or LF, or when it has leading or trailing whitespace; quotes inside a quoted field are
 *       doubled; records end with CRLF. A field that needs no quoting is not quoted, so a spreadsheet
 *       reads a number as a number.</li>
 *   <li><b>Column order is stable.</b> The header is written from the plan's field list in order, once,
 *       and every row writes exactly that many cells. There is no "skip nulls" behaviour: a null is the
 *       {@link ExportValueFormatter#ABSENT} token, because a short row changes the meaning of every
 *       column after it.</li>
 *   <li><b>Nothing is buffered whole.</b> The writer holds one field's bytes at a time and flushes
 *       through to the {@link OutputStream}; it never accumulates rows. A 2-million-row export writes
 *       with the same heap as a 2-row export, which is what the bounded-memory test measures.</li>
 * </ol>
 *
 * <h2>The BOM decision</h2>
 *
 * <p>A UTF-8 BOM is written when the caller asks for it, and the choice is a property rather than a
 * constant because the two consumers disagree:
 *
 * <ul>
 *   <li><b>Spreadsheet users</b> (Excel, and Numbers to a lesser degree) need the BOM to detect UTF-8.
 *       Without it an Arabic name arrives as mojibake, which is the single most common complaint about
 *       an export in an Arabic-first product.</li>
 *   <li><b>ETL and {@code csv} module consumers</b> treat the BOM as data on the first header cell, so
 *       the first column's name arrives as {@code ﻿customer.displayName}.</li>
 * </ul>
 *
 * <p>So: default on for a human-facing export, and the {@link #noBom()} factory exists for a pipeline
 * one. {@code firstelement.exports.generation.csv-bom} is the deployment default and the job records
 * which was used, so a bug report about a stray leading character has an answer.
 */
public final class CsvExportWriter {

    /**
     * The UTF-8 byte order mark.
     *
     * <p>U+FEFF encoded as UTF-8: {@code EF BB BF}. Spelled as bytes rather than as a character so no
     * editor can normalize it away.
     */
    public static final byte[] BYTE_ORDER_MARK = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private static final String CRLF = "\r\n";
    private static final char QUOTE = '"';

    private final boolean withBom;

    private CsvExportWriter(boolean withBom) {
        this.withBom = withBom;
    }

    /** A writer that emits the UTF-8 BOM. The default for a human-facing export. */
    public static CsvExportWriter withBom() {
        return new CsvExportWriter(true);
    }

    /** A writer that omits the BOM, for a pipeline consumer that would read it as data. */
    public static CsvExportWriter noBom() {
        return new CsvExportWriter(false);
    }

    /** Whether this writer emits a BOM. Recorded on the job so a byte-level bug report has an answer. */
    public boolean emitsBom() {
        return withBom;
    }

    /**
     * Writes one export: the BOM, the header row, then every row handed to {@code rowWriter}.
     *
     * <p>{@code rowWriter} is invoked once per row and is handed a writer it must not close; the outer
     * writer owns the stream. That is what keeps the "one row at a time" contract enforceable: a
     * generator that wanted to buffer rows would have to hold them itself, and the bounded-memory test
     * would catch it.
     *
     * @param sink       the destination. Closed by this method.
     * @param fields     the emitted columns, in order
     * @param locale     the header locale
     * @param rowWriter  writes one row's already-formatted cells
     * @return the number of data rows written, excluding the header
     */
    public <T> long write(OutputStream sink, List<ReportField> fields, Locale locale,
                          RowWriter<T> rowWriter) throws IOException {
        try (OutputStream out = sink; Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            if (withBom) {
                out.write(BYTE_ORDER_MARK);
            }
            writeRow(writer, ExportValueFormatter.headerRow(fields, locale));
            return rowWriter.write(writer);
        }
    }

    /** Writes a row of already-formatted cells, RFC 4180 quoting, CRLF terminated. */
    public static void writeRow(Writer writer, List<String> cells) throws IOException {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                writer.write(',');
            }
            writer.write(quoteIfNeeded(cells.get(i)));
        }
        writer.write(CRLF);
    }

    /**
     * RFC 4180 quoting for one field.
     *
     * <p>Quotes when the field contains a delimiter, a quote, CR or LF, or has leading or trailing
     * whitespace — the last case because {@code "x "} and {@code "x"} are different values and a
     * consumer that trims would merge them.
     */
    public static String quoteIfNeeded(String field) {
        if (field == null || field.isEmpty()) {
            return "";
        }
        boolean needsQuotes = field.indexOf(',') >= 0
            || field.indexOf(QUOTE) >= 0
            || field.indexOf('\n') >= 0
            || field.indexOf('\r') >= 0
            || Character.isWhitespace(field.charAt(0))
            || Character.isWhitespace(field.charAt(field.length() - 1));
        if (!needsQuotes) {
            return field;
        }
        StringBuilder quoted = new StringBuilder(field.length() + 2);
        quoted.append(QUOTE);
        for (int i = 0; i < field.length(); i++) {
            char c = field.charAt(i);
            if (c == QUOTE) {
                quoted.append(QUOTE);
            }
            quoted.append(c);
        }
        return quoted.append(QUOTE).toString();
    }

    /**
     * Writes one row.
     *
     * @param <T> the row type, so a generator may be a typed stream of records rather than a list
     */
    @FunctionalInterface
    public interface RowWriter<T> {

        /**
         * Writes every data row.
         *
         * @return the number of rows written
         */
        long write(Writer writer) throws IOException;
    }
}