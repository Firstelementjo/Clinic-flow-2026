package com.firstelement.airag.redaction;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A named set of {@link RedactionRule}s. {@code ai_policies."redactionPolicy"} names one by string.
 *
 * <h2>Three policies, and why the strictest is the default</h2>
 *
 * <ul>
 *   <li>{@link #STRICT_CLINICAL} — everything {@link #CLINICAL} does, plus credentials and payment data.
 *       The default for a deployment that has not chosen, so a configuration typo degrades towards
 *       privacy.</li>
 *   <li>{@link #CLINICAL} — national identifiers, contact details and dates of birth.</li>
 *   <li>{@link #LIGHT} — only credentials. For a tenant whose knowledge base has been reviewed.</li>
 * </ul>
 *
 * <h2>Why an unknown policy name is a refusal</h2>
 *
 * <p>Because the alternatives are both worse. Falling back to {@code LIGHT} would send unredacted patient
 * data to a provider; falling back to {@code STRICT_CLINICAL} would quietly apply different rules than the
 * approved policy version recorded, and the redaction report would name a policy that did not run. The
 * resolver refuses and names the declared policies instead.
 */
public final class RedactionPolicyDefinition {

    /*
     * Rule patterns are declared ONCE as constants and referenced by every policy that contains the rule.
     *
     * <p>Two reasons. First, a rule that appears in two policies with two regexes is a rule whose
     * behaviour depends on which policy a tenant happens to have, and the compliance report then describes
     * a rule id whose meaning is not the one that ran. Second, java.util.regex.Pattern does not override
     * equals(), so two equal-looking copies are NOT equal; sharing the instances is what makes the
     * superset relationship between the policies assertable at all — and it is asserted by rule ID,
     * because Pattern identity is not a property anyone should depend on.
     */

    private static final java.util.regex.Pattern BEARER_TOKEN =
        java.util.regex.Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._\\-]{8,}");

    private static final java.util.regex.Pattern CREDENTIAL_ASSIGNMENT =
        java.util.regex.Pattern.compile(
            "(?i)\\b(api[-_]?key|authorization|token|secret|password|client[-_]?secret)"
                + "\\b\\s*[:=]\\s*\\S+");

    private static final java.util.regex.Pattern IBAN =
        java.util.regex.Pattern.compile("\\b[A-Z]{2}\\d{2}[A-Z0-9]{10,30}\\b");

    private static final java.util.regex.Pattern CARD_NUMBER =
        java.util.regex.Pattern.compile("\\b(?:\\d[ -]?){13,19}\\b");

    private static final java.util.regex.Pattern SAUDI_NATIONAL_ID =
        java.util.regex.Pattern.compile("\\b\\d{10}\\b");

    private static final java.util.regex.Pattern EMAIL =
        java.util.regex.Pattern.compile("\\b[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}\\b");

    /**
     * A phone number, with an explicit date-time exclusion.
     *
     * <p>The bare shape {@code \+?\d[\d\- ]{7,}\d} matches {@code 2026-10-06 00:22} and
     * {@code 2026-10-06T00:22}, because a date is a run of digits and separators. That was found in
     * practice: a tool result came back as {@code lastMessageAt="[REDACTED]:22:24.481"}, and a redacted
     * audit timestamp is a diagnostic destroyed rather than a privacy gain — a timestamp is not patient
     * data and the record has to stay readable.
     *
     * <p>So the match must not be adjacent to clock punctuation. The trailing lookahead rejects a
     * following digit, a colon, or a time of day ({@code T00:22} or {@code  00:22}); the leading lookbehind
     * rejects a preceding digit or colon, which is what stops the engine re-matching the tail of a longer
     * run one character along. Both halves are load-bearing and are asserted in the redaction suite.
     *
     * <p>What it deliberately does NOT exclude is a bare date with no time, {@code 1984-02-11}. A phone
     * number and a date share that shape, and there is no honest way to tell them apart without a length
     * and country-code convention this system has not been given. Redacting it is the safe direction: the
     * value is removed either way, and {@code DATE_OF_BIRTH_LABEL} still fires and still records under its
     * own id.
     */
    private static final java.util.regex.Pattern PHONE =
        java.util.regex.Pattern.compile("(?<![\\d:])\\+?\\d[\\d\\- ]{7,}\\d(?![\\d:]|[T ]\\d{1,2}:\\d{2})");

    /**
     * A date of birth stated as a labelled field.
     *
     * <p>The Arabic label is an alternative in its own right and carries no {@code \b}. {@code \b} is
     * defined over {@code \w}, which without {@code UNICODE_CHARACTER_CLASS} excludes Arabic letters, so a
     * {@code \b}-anchored Arabic alternative can never match: the rule would silently never fire on the
     * Arabic records this Arabic-first product actually stores. The English alternatives keep their
     * {@code \b}, because there it means what it should.
     *
     * <p>The label is written with escapes rather than as an Arabic literal, and the two words are split
     * across lines, so the reassembly is exact and reviewable: {@code تاريخ} + space +
     * {@code الميلاد}. Written as a literal in source it would be one careless copy-paste away from being
     * a different word, and a redaction rule that quietly stops matching is not a visible failure.
     */
    private static final java.util.regex.Pattern DATE_OF_BIRTH_LABEL =
        java.util.regex.Pattern.compile(
            "(?i)(?:\\bdate\\s*of\\s*birth|\\bdob\\b|\\u062a\\u0627\\u0631\\u064a\\u062e"
                + "\\u0020\\u0627\\u0644\\u0645\\u064a\\u0644\\u0627\\u062f)\\s*[:\\-]?\\s*\\S+");

    private static RedactionRule rule(String id, java.util.regex.Pattern pattern) {
        return new RedactionRule(id, "text", pattern);
    }

    /** Credentials and bearer tokens. In every policy, including LIGHT. */
    public static final RedactionPolicyDefinition STRICT_CLINICAL =
        new RedactionPolicyDefinition("STRICT_CLINICAL",
            List.of(rule("BEARER_TOKEN", BEARER_TOKEN),
                rule("CREDENTIAL_ASSIGNMENT", CREDENTIAL_ASSIGNMENT),
                rule("IBAN", IBAN),
                rule("CARD_NUMBER", CARD_NUMBER),
                rule("SAUDI_NATIONAL_ID", SAUDI_NATIONAL_ID),
                rule("EMAIL", EMAIL),
                rule("PHONE", PHONE),
                rule("DATE_OF_BIRTH_LABEL", DATE_OF_BIRTH_LABEL)),
            "Every credential shape plus clinical identifiers, contact details and payment data.");

    /** Clinical identifiers, contact details and dates of birth. */
    public static final RedactionPolicyDefinition CLINICAL =
        new RedactionPolicyDefinition("CLINICAL",
            List.of(rule("SAUDI_NATIONAL_ID", SAUDI_NATIONAL_ID),
                rule("EMAIL", EMAIL),
                rule("PHONE", PHONE),
                rule("DATE_OF_BIRTH_LABEL", DATE_OF_BIRTH_LABEL)),
            "Clinical identifiers, contact details and dates of birth.");

    /** Credentials only. */
    public static final RedactionPolicyDefinition LIGHT =
        new RedactionPolicyDefinition("LIGHT",
            List.of(rule("BEARER_TOKEN", BEARER_TOKEN),
                rule("CREDENTIAL_ASSIGNMENT", CREDENTIAL_ASSIGNMENT)),
            "Credentials only, for a knowledge base that has been reviewed.");

    private static final Map<String, RedactionPolicyDefinition> BY_NAME = Map.of(
        STRICT_CLINICAL.name(), STRICT_CLINICAL,
        CLINICAL.name(), CLINICAL,
        LIGHT.name(), LIGHT);

    private final String name;
    private final List<RedactionRule> rules;
    private final String descriptionAr;

    private RedactionPolicyDefinition(String name, List<RedactionRule> rules, String descriptionAr) {
        this.name = name;
        this.rules = List.copyOf(rules);
        this.descriptionAr = descriptionAr;
    }

    public String name() {
        return name;
    }

    public List<RedactionRule> rules() {
        return rules;
    }

    public String descriptionAr() {
        return descriptionAr;
    }

    /** @return the declared policy, or {@code null} when the name is not one this build implements. */
    public static RedactionPolicyDefinition byName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return BY_NAME.get(name.trim().toUpperCase(Locale.ROOT));
    }

    /** The declared names, for a refusal message that has to tell an operator what to choose. */
    public static List<String> declaredNames() {
        return List.of(STRICT_CLINICAL.name(), CLINICAL.name(), LIGHT.name());
    }
}