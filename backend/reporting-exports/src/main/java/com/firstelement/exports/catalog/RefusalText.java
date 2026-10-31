package com.firstelement.exports.catalog;

/**
 * Renders a caller-supplied value inside a refusal message, without ever printing the word {@code null}.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Java string concatenation renders {@code null} as the four characters {@code "null"}. A refusal that
 * quotes the value it refused therefore reads as though the caller had <em>sent</em> the word:
 *
 * <pre>
 *   "Report definition names the redaction policy 'null', which is not one of none, no-contact-details, ..."
 * </pre>
 *
 * <p>That is the message an <strong>omitted</strong> field produces, and it is wrong twice over. It tells
 * the caller they sent something they did not send, and it tells an operator reading a log that a policy
 * was rejected when none was ever offered. It is also the message a client cannot act on: the fix for
 * "you sent null" and the fix for "you sent nothing" are different, and only the second one is true.
 *
 * <p>Phase 32 recorded this after the Angular agent drove the real API and reported
 * {@code 400 … names the redaction policy 'null'} for a request that simply omitted the field. The
 * required-field check now answers that request first, which is the correct answer — but the stringifier
 * underneath it is still wrong for every <em>other</em> value that can be absent, and the next caller who
 * omits a different field gets the same misleading sentence. So the rendering is fixed at the source.
 *
 * <h2>Why {@code (absent)} and not {@code ""}</h2>
 *
 * <p>An empty string is a value a caller can send and is refused as a value: it is what a client that
 * serialises an unset form field produces. It has to stay distinguishable from {@code null}, and it is —
 * {@link #of(String)} quotes it as {@code ''}. {@code (absent)} therefore means exactly one thing: the
 * request did not carry the field at all.
 *
 * <h2>Not a logging concern</h2>
 *
 * <p>Nothing here is written to a log or an audit row; this is the <em>caller-facing</em> text of a
 * refusal, and the whole point is that a caller can read it and know what to change. Secrets are never
 * interpolated into one of these: the only values that reach this class are request fields that failed
 * validation, and a value that fails validation is by construction one the module has already refused to
 * interpret as anything.
 */
public final class RefusalText {

    private RefusalText() {
    }

    /**
     * A caller-supplied value, quoted, or {@code (absent)} when the request did not carry it.
     *
     * <p>Blank is <em>not</em> absent: {@code "   "} is quoted as {@code '   '} because it arrived. What
     * the caller must change is the same either way, and the distinction between "sent nothing" and "sent
     * whitespace" is not one an operator needs from a log line.
     */
    public static String of(String value) {
        return value == null ? "(absent)" : "'" + value + "'";
    }
}