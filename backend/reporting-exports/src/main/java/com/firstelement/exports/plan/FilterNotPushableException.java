package com.firstelement.exports.plan;

/**
 * A filter the export cannot honour.
 *
 * <h2>Why this fails the export instead of narrowing it in memory</h2>
 *
 * <p>Phase-9 hard rule 6. Three reasons, and each of them alone is enough:
 *
 * <ol>
 *   <li><b>Aggregation.</b> A post-filter runs after {@code sum}/{@code avg}/{@code count}. A total
 *       that included a row the filter excluded is not the total the caller asked for, and no amount
 *       of correct redaction afterwards fixes a wrong number.</li>
 *   <li><b>Silent disagreement.</b> A silently narrowed file whose row count does not match the
 *       dashboard is a discrepancy nobody finds until a reconciliation, which is the single most
 *       expensive class of bug in a finance product.</li>
 *   <li><b>The alternative is worse.</b> "Log it and return everything" leaks rows. "Return nothing"
 *       is indistinguishable from a project with no data. Failing loudly with the offending key is the
 *       only honest third option.</li>
 * </ol>
 *
 * <p>This is a distinct type from {@link IllegalArgumentException} so the HTTP layer can answer
 * {@code 422 FILTER_NOT_PUSHABLE} with the key in the body, and so a test can assert the refusal
 * rather than a stack trace.
 */
public class FilterNotPushableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String filterKey;

    public FilterNotPushableException(String filterKey, String reason) {
        super("Export filter '" + filterKey + "' cannot be pushed into the query: " + reason
            + ". The export was refused rather than narrowed after the fact, because a filter that "
            + "cannot reach the WHERE clause cannot reach the aggregation either.");
        this.filterKey = filterKey;
    }

    /** The offending key. Named in the error body so an operator knows what to change. */
    public String filterKey() {
        return filterKey;
    }

    /** The stable error code for the HTTP layer. */
    public String code() {
        return "FILTER_NOT_PUSHABLE";
    }
}