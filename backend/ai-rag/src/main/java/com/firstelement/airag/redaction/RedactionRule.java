package com.firstelement.airag.redaction;

/**
 * One redaction rule, declared in code.
 *
 * <h2>What a rule may and may not be</h2>
 *
 * <p>A rule is a {@link java.util.regex.Pattern} over text plus an identifier. It is <b>not</b> a field
 * path, a SQL expression, or a value the caller supplies: a tenant can choose a <em>policy</em> by name,
 * never a rule by content. A configurable rule is a way for a prompt-injection payload to become a regex
 * that runs over every prompt in the deployment, and the pattern is also the thing that decides what is
 * dropped — so it is the one thing that must not be attacker-influenced.
 *
 * <h2>Why the rules are deliberately blunt</h2>
 *
 * <p>Each rule targets a <b>shape</b> (a Saudi national id, an IBAN, an email address, a bearer token) and
 * replaces the whole match. Bluntness is the point: a redaction rule that tries to be clever about
 * preserving the last four digits of a national id is a rule that will eventually preserve the last four
 * digits of a national id that was not formatted the way it expected.
 *
 * @param id       stable rule id, recorded in {@code ai_redaction_records."ruleId"}
 * @param fieldPath the schema path the rule applies to, recorded in {@code "fieldPath"}. Not a JSON path
 *                  into the text; a label describing <em>where in the run</em> the rule fired, e.g.
 *                  {@code prompt.untrusted}, {@code response.assistant}
 * @param pattern  what to remove
 */
public record RedactionRule(String id, String fieldPath, java.util.regex.Pattern pattern) {

    public RedactionRule {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("A redaction rule needs a stable id; the compliance report "
                + "groups by it and an unnamed rule cannot be reviewed");
        }
        if (pattern == null) {
            throw new IllegalArgumentException("A redaction rule needs a pattern");
        }
    }

    /** Replaces every match with {@link #placeholder()}, returning the new text and how many were removed. */
    public Match apply(String text) {
        if (text == null || text.isEmpty()) {
            return new Match(text, 0, 0);
        }
        java.util.regex.Matcher matcher = pattern.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        int count = 0;
        int removed = 0;
        int cursor = 0;
        while (matcher.find()) {
            out.append(text, cursor, matcher.start());
            out.append(placeholder());
            removed += matcher.end() - matcher.start();
            count++;
            cursor = matcher.end();
        }
        out.append(text, cursor, text.length());
        return new Match(out.toString(), count, removed);
    }

    /**
     * The replacement.
     *
     * <p>Identical for every removal of this rule and carrying no length information, so a redacted
     * document cannot leak the length of what was removed. {@code FieldRedactionPolicy.PLACEHOLDER} is the
     * same idea applied to columns, and the two are kept consistent so "redacted" looks the same wherever
     * it appears.
     */
    public static String placeholder() {
        return "[REDACTED]";
    }

    /** The outcome of one rule over one text. */
    public record Match(String text, int count, int charactersRemoved) {

        public boolean fired() {
            return count > 0;
        }
    }
}