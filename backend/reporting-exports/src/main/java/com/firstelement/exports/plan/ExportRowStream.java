package com.firstelement.exports.plan;

import com.firstelement.exports.config.ExportProperties;
import com.firstelement.exports.catalog.ReportField;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * Reads a plan's rows in pages, holding one page at a time.
 *
 * <h2>Why this exists rather than a repository returning a {@code List}</h2>
 *
 * <p>A tenant with two million conversations must produce an export with the same heap footprint as one
 * with two rows. A repository method returning {@code List<Map<String,Object>>} makes that impossible:
 * the list <em>is</em> the whole result set. So the read surface here is a {@link Stream} of
 * {@link Batch} pages, and every consumer in this module — the CSV writer, the DOCX renderer — consumes it
 * one page at a time.
 *
 * <h2>The fetch size is set explicitly, not left to the driver</h2>
 *
 * <p>PostgreSQL's JDBC driver defaults to fetching the <em>whole</em> result into client memory unless
 * {@code fetchSize} is set with autocommit off. That default is the single most common way a "streaming"
 * export turns out not to stream. So the connection here is autocommit-off for the duration of the read,
 * {@code fetchSize} is set from configuration, and the read is rolled back at the end — a read-only
 * transaction whose only purpose is to make the driver page.
 *
 * <h2>Values are converted once, at the page boundary</h2>
 *
 * <p>{@link Row#value(int)} converts through {@link #readColumn}, which handles the handful of JDBC types
 * this module's declared {@link ReportField.FieldKind} set can produce. Converting here rather than in each
 * formatter means a numeric column arrives as a {@link java.math.BigDecimal} rather than as whatever the
 * driver decided, which is what makes {@code usage.cost} render identically in a CSV and in a DOCX.
 *
 * <p>A type the conversion does not know is a {@link ColumnTypeUnsupportedException}, not a
 * {@code toString()}: a silently stringified money amount is how a precision loss becomes a reconciliation
 * discrepancy nobody can trace.
 */
@Component
public class ExportRowStream {

    private final DataSource dataSource;
    private final DataSource rawDataSource;
    private final ExportProperties properties;

    public ExportRowStream(DataSource dataSource, ExportProperties properties) {
        this.dataSource = dataSource;
        this.rawDataSource = unwrap(dataSource);
        this.properties = properties;
    }

    /**
     * The physical data source, bypassing any lazy proxy.
     *
     * <p>This matters, and getting it wrong is invisible in every unit test this class could have.
     * {@code LazyConnectionDataSourceProxy} hands a caller the <em>transaction-bound</em> connection so that
     * code participating in a transaction shares it. The export read is not participating in that
     * transaction: it deliberately sets autocommit off and <strong>rolls back</strong> at the end, to make
     * the driver's fetch size meaningful and to release the server-side cursor. Given the shared connection,
     * that rollback <em>undoes the caller's transaction</em> — the export job's own claim, its progress
     * writes, and the terminal state it was about to record.
     *
     * <p>The symptom is unusually confusing and is worth naming because it cost real time to diagnose: the
     * generation returns a FAILED outcome, every method reports success, and the row is still
     * {@code QUEUED} with {@code attemptCount = 0} when it is read back. Nothing reports an error, because
     * nothing errored — the work was committed and then undone.
     *
     * <p>So the read takes its own connection from the underlying pool. It costs one connection per
     * concurrent export, which is the correct price for a read that must not be able to roll back the
     * bookkeeping around it.
     */
    private static DataSource unwrap(DataSource candidate) {
        DataSource current = candidate;
        // Both proxies must come off. `LazyConnectionDataSourceProxy` and
        // `TransactionAwareDataSourceProxy` are different wrappers with the same failure mode: each hands
        // back the connection bound to the caller's transaction rather than a fresh one from the pool. The
        // export read then sets autocommit off on that shared connection and ROLLS IT BACK at the end,
        // which discards the job's own claim, progress rows and terminal state. Unwrapping only the lazy
        // proxy leaves the transaction-aware one in place, and the symptom is a job that reports every
        // method successful, is recorded still QUEUED with attemptCount 0, and surfaces as
        // UnexpectedRollbackException when the caller commits.
        for (;;) {
            if (current instanceof org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy lazy) {
                current = lazy.getTargetDataSource();
            } else if (current instanceof org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy tx) {
                current = tx.getTargetDataSource();
            } else {
                return current;
            }
        }
    }

    /**
     * One page of rows.
     *
     * @param approxBytes a rough size, used by the runner to report the largest page it saw. Not a
     *                    precise measurement and never used for accounting — it exists so a memory
     *                    assertion has something to compare against.
     */
    public record Batch(List<Row> rows, long approxBytes) implements Iterable<Row> {

        @Override
        public java.util.Iterator<Row> iterator() {
            return rows.iterator();
        }
    }

    /**
     * One row, its values already read and converted.
     *
     * <p>Holds values, not the {@link ResultSet}. A row that kept a handle to a forward-only cursor would
     * be unreadable the moment the iterator advanced — the cursor is only positioned on one row at a time,
     * and the page loop advances it before the caller sees the rows it already collected.
     */
    public record Row(List<Object> values) {

        /**
         * One column's value, already converted.
         *
         * @param oneBasedOrdinal the plan's {@code fe_col_<n>} position
         */
        public Object value(int oneBasedOrdinal) {
            int index = oneBasedOrdinal - 1;
            if (index < 0 || index >= values.size()) {
                throw new ColumnReadException(
                    "Column " + oneBasedOrdinal + " was requested but the row carries "
                        + values.size() + " column(s); the plan's column list and the statement disagree",
                    null);
            }
            return values.get(index);
        }

        /** Every column as a map keyed by the report field key. One map per row, discarded per page. */
        public Map<String, Object> values(List<ReportField> fields) {
            Map<String, Object> byKey = new LinkedHashMap<>(fields.size());
            for (int i = 0; i < fields.size(); i++) {
                byKey.put(fields.get(i).key(), value(i + 1));
            }
            return byKey;
        }

        /** A rough size for the page accounting. */
        long approxBytes() {
            return values.size() * 64L;
        }
    }

    /**
     * The rows, as pages.
     *
     * <p>The returned stream owns the JDBC resources and closes them on exhaustion, on a short-circuit, or
     * on the terminal operation's exception. Callers therefore must consume it inside a
     * try-with-resources or fully; a partially consumed stream rolls back its transaction on close and the
     * rows are simply not produced, which is the correct outcome for an aborted export.
     */
    /**
     * The rows, as pages, in a form the caller closes.
     *
     * <p>Returns an {@link AutoCloseable} {@link Iterable} rather than a {@link Stream}: a
     * {@code Stream} cannot be closed, and a row stream that leaks a connection in a pool of ten turns one
     * abandoned export into a service outage. A {@code try}-with-resources over the returned object is the
     * shape a caller cannot get wrong.
     */
    public Pages stream(ExportQueryPlan plan) {
        return new Pages(plan);
    }

    /**
     * The page source.
     *
     * <p>An {@code Iterable} because an iterator cannot be closed, and closing is what releases the JDBC
     * resources deterministically rather than at connection-pool reclamation.
     */
    public final class Pages implements Iterable<Batch>, AutoCloseable {

        private final ExportQueryPlan plan;
        private BatchIterator iterator;

        Pages(ExportQueryPlan plan) {
            this.plan = plan;
        }

        @Override
        public java.util.Iterator<Batch> iterator() {
            if (iterator != null) {
                throw new IllegalStateException(
                    "An export row source yields one iterator. Re-iterating would need a second cursor over "
                        + "the same tenant's rows, which is a resource decision and not a convenience.");
            }
            iterator = new BatchIterator(plan);
            return iterator;
        }

        /**
         * The same pages as a {@link Stream}, for a caller that prefers one.
         *
         * <p>The returned stream is {@link Stream#onClose( Runnable)}-wired to this object's {@code close},
         * so a try-with-resources on the stream releases the JDBC resources.
         */
        public Stream<Batch> asStream() {
            return StreamSupport.stream(
                java.util.Spliterators.spliteratorUnknownSize(iterator(),
                    java.util.Spliterator.ORDERED | java.util.Spliterator.NONNULL),
                false).onClose(this::close);
        }

        @Override
        public void close() {
            if (iterator != null) {
                iterator.close();
            }
        }
    }

    /**
     * The page iterator.
     *
     * <p>A dedicated class rather than a lambda because it owns three resources (connection, statement,
     * result set) and a flag for autocommit, and a lambda cannot express "restore what you changed".
     *
     * <h2>The one-page lookahead is not optional</h2>
     *
     * <p>{@link #pending} exists because {@link java.util.Iterator} is a two-method protocol and this one
     * has a side effect. A paged read cannot answer {@code hasNext()} without reading the next page, so
     * {@code hasNext()} must <em>keep</em> what it read or {@code next()} has nothing to return. Writing
     * {@code hasNext() { return nextBatch() != null; }} — which is the shape a stateless iterator takes —
     * silently discards every page it produces, and the consuming loop then fails on the second call with
     * {@link java.util.NoSuchElementException} while the first page was read and thrown away.
     *
     * <p>That defect is invisible to {@code compileJava}, invisible to a unit test that never opens a
     * connection, and fatal to every export the moment one runs: the whole artefact pipeline failed at the
     * first row. It was found by the integration suite, which is the only kind of test that can execute a
     * real cursor.
     */
    private final class BatchIterator implements java.util.Iterator<Batch>, AutoCloseable {

        private final ExportQueryPlan plan;
        private java.sql.Connection connection;
        private PreparedStatement statement;
        private ResultSet resultSet;
        private boolean initialAutocommit;
        private boolean exhausted;
        private boolean closed;
        private Batch pending;

        BatchIterator(ExportQueryPlan plan) {
            this.plan = plan;
        }

        @Override
        public boolean hasNext() {
            if (pending == null) {
                pending = nextBatch();
            }
            return pending != null;
        }

        @Override
        public Batch next() {
            if (!hasNext()) {
                throw new java.util.NoSuchElementException("The export row stream is exhausted");
            }
            Batch batch = pending;
            pending = null;
            return batch;
        }

        /**
         * The next page, or {@code null} at the end of the statement.
         *
         * <p>Cancellation of the read is not this class's business: a caller that stops consuming closes
         * the stream, which rolls the read-only transaction back and releases the server-side cursor. That
         * is a deterministic release rather than a {@code break} that leaves a cursor alive until the
         * connection happens to be reclaimed.
         *
         * <p>The page size is the configured fetch size, not the whole result, which is the entire point:
         * the caller's loop sees one page at a time and nothing accumulates.
         */
        private Batch nextBatch() {
            if (exhausted || closed) {
                close();
                return null;
            }
            try {
                open();
                if (!resultSet.next()) {
                    exhausted = true;
                    close();
                    return null;
                }
                int columnCount = resultSet.getMetaData().getColumnCount();
                List<Row> rows = new ArrayList<>(properties.getGeneration().getFetchSize());
                long approx = 0L;
                do {
                    // Read the values NOW, while the cursor is on this row. A Row that held the ResultSet
                    // and read on demand would be correct only for the last row of a page: `nextBatch` calls
                    // `resultSet.next()` to advance, so every row but the final one would be read from a
                    // cursor already positioned past it, and PostgreSQL reports exactly that as
                    // "ResultSet not positioned properly, perhaps you need to call next". That is a
                    // forward-only cursor behaving correctly, and the only sound fix is to materialise
                    // the page rather than to defer the read.
                    Row row = readCurrentRow(columnCount);
                    rows.add(row);
                    approx += row.approxBytes();
                } while (rows.size() < properties.getGeneration().getFetchSize()
                    && resultSet.next());
                return new Batch(List.copyOf(rows), approx);
            } catch (SQLException e) {
                close();
                throw new ColumnReadException(
                    "The export statement failed while reading rows. The statement was built only from "
                        + "the report field registry, so this is a storage or type fault rather than a "
                        + "malformed query: " + e.getMessage(), e);
            }
        }

        /**
         * Materialises the row the cursor is currently on.
         *
         * <p>Bounded by the declared column count, so a page still costs one page of memory rather than
         * one result set: the conversion happens per row and the {@link ResultSet} is released as soon as
         * this method returns.
         */
        private Row readCurrentRow(int columnCount) throws SQLException {
            // Arrays.asList, NOT List.of: a SQL NULL is a legitimate value in an export row — an unpriced
            // revenue, a customer with no first-seen date — and List.of rejects null elements outright.
            // That made every export containing a null column fail with a message-less NullPointerException
            // from inside the read, which then surfaced as `code=NullPointer message=null` on the job and
            // sent the diagnosis looking for a JDBC fault that was never there.
            Object[] values = new Object[columnCount];
            for (int i = 0; i < columnCount; i++) {
                values[i] = readColumn(resultSet, i + 1);
            }
            return new Row(java.util.Arrays.asList(values));
        }

        private void open() throws SQLException {
            if (statement != null) {
                return;
            }
            connection = rawDataSource.getConnection();
            initialAutocommit = connection.getAutoCommit();
            // Autocommit off is what makes the driver's fetch size meaningful: with it on, PostgreSQL
            // fetches the whole result regardless.
            connection.setAutoCommit(false);
            statement = connection.prepareStatement(plan.sql(),
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            statement.setFetchSize(properties.getGeneration().getFetchSize());
            statement.setQueryTimeout(properties.getGeneration().getStatementTimeoutSeconds());
            for (int i = 0; i < plan.parameters().size(); i++) {
                statement.setObject(i + 1, plan.parameters().get(i));
            }
            resultSet = statement.executeQuery();
        }

        /**
         * Closes the read and restores the connection.
         *
         * <p>The transaction is rolled back rather than committed: a read-only export has nothing to
         * commit, and rolling back releases the cursor deterministically. Restoring autocommit before
         * returning the connection to the pool is what stops the next borrower from inheriting an
         * off-autocommit connection.
         */
        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                if (resultSet != null) {
                    resultSet.close();
                }
            } catch (SQLException e) {
                // Closing is best-effort; the connection close below is what actually matters.
            }
            try {
                if (statement != null) {
                    statement.close();
                }
            } catch (SQLException e) {
                // As above.
            }
            try {
                if (connection != null) {
                    if (!initialAutocommit) {
                        connection.rollback();
                    }
                    connection.setAutoCommit(initialAutocommit);
                    connection.close();
                }
            } catch (SQLException e) {
                // A connection that cannot be returned to the pool must be reported, not swallowed: a
                // leaked connection in a pool of ten turns one bad export into a service outage.
                org.slf4j.LoggerFactory.getLogger(ExportRowStream.class)
                    .error("Failed to close an export read connection; the pool may be leaking", e);
            }
        }
    }

    /** One locale-independent conversion per declared column kind. */
    private static Object readColumn(ResultSet rs, int oneBasedOrdinal) throws SQLException {
        int type = rs.getMetaData().getColumnType(oneBasedOrdinal);
        Object raw = rs.getObject(oneBasedOrdinal);
        if (raw == null || rs.wasNull()) {
            return null;
        }
        return switch (type) {
            case java.sql.Types.NUMERIC, java.sql.Types.DECIMAL ->
                raw instanceof java.math.BigDecimal decimal ? decimal : new java.math.BigDecimal(raw.toString());
            case java.sql.Types.BIGINT -> raw instanceof Long value ? value : Long.valueOf(raw.toString());
            case java.sql.Types.INTEGER, java.sql.Types.SMALLINT, java.sql.Types.TINYINT ->
                raw instanceof Integer value ? value : Integer.valueOf(raw.toString());
            case java.sql.Types.DATE -> raw instanceof java.sql.Date value
                ? value.toLocalDate() : java.sql.Date.valueOf(raw.toString()).toLocalDate();
            case java.sql.Types.TIMESTAMP -> raw instanceof java.sql.Timestamp value
                ? value.toLocalDateTime() : java.sql.Timestamp.valueOf(raw.toString()).toLocalDateTime();
            case java.sql.Types.OTHER -> raw;
            default -> raw;
        };
    }

    /** A column could not be read, or the statement failed mid-stream. */
    public static class ColumnReadException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public ColumnReadException(String message, Throwable cause) {
            super(message, cause);
        }

        /** The stable error code recorded on the job. */
        public String code() {
            return "EXPORT_READ_FAILED";
        }
    }

    /**
     * A column produced a JDBC type this module cannot render.
     *
     * <p>Separate from {@link ColumnReadException} because the remedy differs: this is a registry gap that
     * needs a code change, whereas a read failure is transient.
     */
    public static class ColumnTypeUnsupportedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public ColumnTypeUnsupportedException(int jdbcType, int columnOrdinal) {
            super("Column " + columnOrdinal + " produced JDBC type " + jdbcType + ", which the export "
                + "value formatter does not convert. Rendering it as a string would let a numeric "
                + "precision loss reach a customer-facing artefact.");
        }

        /** The stable error code recorded on the job. */
        public String code() {
            return "EXPORT_COLUMN_TYPE_UNSUPPORTED";
        }
    }

    /** The declared field kinds, for the conversion table's documentation. */
    public static List<com.firstelement.exports.catalog.ReportField.FieldKind> supportedKinds() {
        return List.of(com.firstelement.exports.catalog.ReportField.FieldKind.values());
    }
}