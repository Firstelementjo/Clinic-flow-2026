package com.firstelement.airag.provider;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one place a string that may contain a credential passes through on its way to a log, an audit
 * metadata field, or a persisted provider error.
 *
 * <h2>What it removes</h2>
 *
 * <ul>
 *   <li><b>Key-shaped assignments.</b> {@code api_key=sk-...}, {@code Authorization: Bearer ...},
 *       {@code x-goog-api-key: ...}. The <b>whole</b> assignment is masked, name included — see "what the
 *       mask does not preserve" below.</li>
 *   <li><b>Bearer tokens.</b> Matched <b>before</b> the assignment shapes, because
 *       {@code authorization: Bearer <token>} is otherwise half-redacted: the assignment eats the word
 *       {@code Bearer} and orphans the token. See the comment on the order in {@link #scrubAndBound}.</li>
 *   <li><b>Provider SDK marker strings.</b> Long alphanumeric runs that are not ordinary words are the
 *       shape a bearer token has. Masking by entropy is what catches a token in an unfamiliar header
 *       name; a header allow-list alone would let a new provider's key through.</li>
 *   <li><b>Control characters and newlines</b>, so a provider message cannot forge a log line.</li>
 * </ul>
 *
 * <h2>What the mask does not preserve, and why</h2>
 *
 * <p>An earlier version of this note claimed the key's <em>name</em> survives, so an operator could tell
 * which credential leaked. It does not, and the claim was wrong rather than aspirational:
 * {@link #ASSIGNMENT}'s value is {@code \S+}, which runs to the next whitespace, so masking the value
 * leaves {@code api_key=} dangling and masking nothing at all for a space-separated form. Two options
 * existed — mask the name and value together, or preserve the name and mask only the value.
 *
 * <p>Masking both is what this class does. A preserved prefix is a <em>partial secret</em>: the leading
 * characters of a key are part of the key, {@code sk-live-…} identifies the provider account, and a
 * uniform mask is what makes {@code containsOnlyOnce(MASK)} a property anything can assert. The cost is
 * that the operator reads "a credential shape was here" rather than "it was this credential"; in
 * practice the surrounding provider text names it, and {@link #looksLikeSecret} answers the binary
 * question on the read side.
 *
 * <p>The same reasoning makes the entropy rule mask a <b>UUID</b>, which it does, because a UUID and a
 * key are indistinguishable by shape alone and this class fails towards the mask. The cost is that a
 * provider message naming a run id loses the id; the trace row still carries it, so nothing is actually
 * lost.
 *
 * <h2>What it deliberately does NOT do</h2>
 *
 * <p>It does not attempt to detect customer content. That is the redaction engine's job, and mixing the
 * two would produce a scrubber that is confident about credentials and hopeful about names. This class
 * removes the shapes it can recognise exactly; {@link com.firstelement.airag.redaction.AiRedactionEngine}
 * removes the content it was told about.
 *
 * <p>It also never <em>recovers</em> anything: there is no un-scrub path, and the scrubbed form is what
 * is stored, so a row written today cannot become a disclosure after a future code change.
 */
public final class SecretScrubber {

    /**
     * The hard bound on a scrubbed string.
     *
     * <p>240 characters. Long enough for a diagnostic, short enough that a provider echoing the prompt
     * back cannot put customer content into a column that is meant to hold a reason.
     */
    public static final int MAX_LENGTH = 240;

    /** The replacement. Identical for every removal, so its length says nothing about the original. */
    public static final String MASK = "[REDACTED]";

    private static final Pattern ASSIGNMENT = Pattern.compile(
        "(?i)\\b(api[-_]?key|authorization|bearer|token|secret|password|client[-_]?secret|"
            + "access[-_]?key|session[-_]?token|x-goog-api-key|x-api-key)\\b\\s*[:=]\\s*\\S+");

    private static final Pattern BEARER = Pattern.compile("(?i)\\bbearer\\s+\\S+");

    /**
     * A long run of letters and digits with no space: the shape a key has and a word does not.
     *
     * <p>24 characters is the threshold. An English or Arabic word, an id, a model name, a URL path and a
     * SQL fragment all stay under it; a token does not.
     */
    private static final Pattern HIGH_ENTROPY_RUN = Pattern.compile("\\b[A-Za-z0-9_\\-]{24,}\\b");

    /** Digits that look like a phone number or a national id, in any grouping. */
    private static final Pattern LONG_DIGIT_RUN = Pattern.compile("\\b\\d{9,}\\b");

    private SecretScrubber() {
    }

    /**
     * Scrubs, normalises and bounds a string destined for persistence or a log line.
     *
     * <p>{@code null} and blank become {@code null} rather than the empty string, so "no reason given"
     * and "the reason was blank" do not collapse into one value on the read side.
     */
    public static String scrubAndBound(String raw) {
        if (raw == null) {
            return null;
        }
        String collapsed = raw.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ")
            .replaceAll("\\s+", " ")
            .trim();
        if (collapsed.isEmpty()) {
            return null;
        }
        // Order is load-bearing: BEARER runs BEFORE ASSIGNMENT.
        //
        // `Authorization: Bearer aaaabbbbccccdddd` is the single most common shape of a credential in a
        // provider's echo of a failed request. Run the other way round, ASSIGNMENT matches
        // `authorization: Bearer` -- its value is one `\S+`, which is the word `Bearer` -- and replaces
        // that with the mask. The token is then orphaned: the word `bearer` is gone, so BEARER cannot match
        // it, and the secret survives into ai_runs."errorMessage" fully intact. This exact leak was found
        // by ProviderFailoverTest, which is why the order is stated here and not left to a reader.
        String scrubbed = ASSIGNMENT.matcher(BEARER.matcher(collapsed).replaceAll(MASK))
            .replaceAll(MASK);
        scrubbed = HIGH_ENTROPY_RUN.matcher(scrubbed).replaceAll(MASK);
        scrubbed = LONG_DIGIT_RUN.matcher(scrubbed).replaceAll(MASK);
        return scrubbed.length() <= MAX_LENGTH ? scrubbed : scrubbed.substring(0, MAX_LENGTH) + "…";
    }

    /**
     * Whether a string looks like it carries a credential.
     *
     * <p>Used by the tests and by {@code assertNoSecret} style guards: an assertion that a
     * persisted provider error does not echo a key should not depend on the scrubber being the only thing
     * standing between the two.
     */
    public static boolean looksLikeSecret(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        Matcher assignment = ASSIGNMENT.matcher(raw);
        Matcher bearer = BEARER.matcher(raw);
        Matcher entropy = HIGH_ENTROPY_RUN.matcher(raw);
        Matcher digits = LONG_DIGIT_RUN.matcher(raw);
        return assignment.find() || bearer.find() || entropy.find() || digits.find();
    }

    /**
     * A short, non-reversible label for a value used only to decide whether two things are equal.
     *
     * <p>For "did the model actually change" style questions, where the answer is a boolean. Never a
     * substitute for storing the value, and never used where the value itself is needed — the redaction
     * record stores counts, not hashes, so a hash cannot become a confirmation oracle for a
     * low-entropy value like a date of birth.
     */
    public static String fingerprint(String value) {
        if (value == null) {
            return null;
        }
        return Integer.toHexString(java.util.Objects.hash(SecretScrubber.class.getName(), value));
    }
}