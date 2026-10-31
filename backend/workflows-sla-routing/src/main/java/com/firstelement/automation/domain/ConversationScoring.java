package com.firstelement.automation.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The deterministic, lexical conversation scorer. Ported from
 * {@code packages/ai/src/scoring/evaluator.ts}.
 *
 * <h2>Why it is not a model</h2>
 *
 * <p>The legacy rationale, kept because it is the reason and not an implementation detail:
 * "it costs nothing on every turn (scoring a model call with another model call doubles spend on
 * the busiest path), it is reproducible so an escalation can be explained after the fact, and it
 * cannot be talked out of its own numbers by a customer's text." Its weakness is the mirror — it
 * reads words, not meaning, and misses politely-worded fury — so the interface exists for a
 * per-project model-based evaluator to be added later without touching the rules.
 *
 * <h2>Arabic is a first-class cue list</h2>
 *
 * <p>Not a translation of the English list: the Arabic patterns carry the Gulf phrasing an
 * English-only list cannot match at all, and a "neutral" Arabic escalation would be a product
 * defect in an Arabic-first system.
 *
 * <h2>Determinism is the property under test</h2>
 *
 * <p>No randomness, no clock, no locale-dependent case folding beyond {@code toLowerCase} on the
 * default locale — so the same message always yields the same six numbers, and a score stored in
 * {@code conversation_scores} can be re-derived by anyone with the message.
 */
public final class ConversationScoring {

    /** The name recorded in {@code conversation_scores.evaluator}. */
    public static final String EVALUATOR = "heuristic";

    private static final int PER_PATTERN_CAP = 3;

    private static final List<Pattern> FRUSTRATION_CUES = List.of(
        Pattern.compile("\\b(angry|furious|unacceptable|ridiculous|terrible|awful|worst|useless|scam|fed up)\\b",
            Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(again|still|third time|no one|nobody)\\s+(has\\s+)?(not|n't|answered|replied|helped)?",
            Pattern.CASE_INSENSITIVE),
        Pattern.compile("(غاضب|غير مقبول|سخيف|سيء|أسوأ|مافي فايدة|محتال|نصب|زهقت|تعبت منكم)"),
        Pattern.compile("(مرة ثانية|للمرة الثالثة|ما حد|محد|ولا واحد)"));

    private static final List<Pattern> ESCALATION_DEMANDS = List.of(
        Pattern.compile("\\b(supervisor|manager|complaint|legal|lawyer|sue|consumer protection)\\b",
            Pattern.CASE_INSENSITIVE),
        Pattern.compile("(مشرف|مدير|شكوى|قانوني|محامي|حماية المستهلك|أرفع قضية)"));

    private static final List<Pattern> URGENCY_CUES = List.of(
        Pattern.compile("\\b(urgent|asap|immediately|right now|emergency|today)\\b",
            Pattern.CASE_INSENSITIVE),
        Pattern.compile("(عاجل|فورا|فوراً|حالا|حالاً|ضروري|اليوم|طارئ)"));

    private static final List<Pattern> RISK_CUES = List.of(
        Pattern.compile("\\b(refund|chargeback|cancel my|close my account|fraud|stolen|unauthorized|data breach)\\b",
            Pattern.CASE_INSENSITIVE),
        Pattern.compile("(استرجاع|إلغاء|أغلق حسابي|احتيال|مسروق|غير مصرح|تسريب)"));

    private static final List<Pattern> POSITIVE_CUES = List.of(
        Pattern.compile("\\b(thanks|thank you|great|perfect|appreciate|helpful)\\b",
            Pattern.CASE_INSENSITIVE),
        Pattern.compile("(شكرا|شكراً|ممتاز|رائع|تسلم|جزاك الله)"));

    private static final List<Intent> INTENTS = List.of(
        new Intent("refund", List.of(
            Pattern.compile("\\b(refund|money back|reimburse)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(استرجاع|استرداد|فلوسي)"))),
        new Intent("delivery", List.of(
            Pattern.compile("\\b(deliver|shipping|track|order status|where is my)\\b",
                Pattern.CASE_INSENSITIVE),
            Pattern.compile("(توصيل|شحن|طلبي|وين طلبي|تتبع)"))),
        new Intent("billing", List.of(
            Pattern.compile("\\b(charge|invoice|billing|payment|double.?charg)\\b",
                Pattern.CASE_INSENSITIVE),
            Pattern.compile("(فاتورة|دفع|خصم|سحب مرتين)"))),
        new Intent("technical", List.of(
            Pattern.compile("\\b(not working|error|broken|bug|cannot log ?in)\\b",
                Pattern.CASE_INSENSITIVE),
            Pattern.compile("(ما يشتغل|خطأ|معطل|ما أقدر أدخل)"))),
        new Intent("complaint", ESCALATION_DEMANDS),
        new Intent("cancellation", List.of(
            Pattern.compile("\\b(cancel|unsubscribe|close account)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(إلغاء|إيقاف الاشتراك|إغلاق الحساب)"))));

    /** Whether the customer explicitly asked for a person. Separate from ESCALATION_DEMANDS. */
    private static final List<Pattern> HUMAN_REQUEST_PATTERNS = List.of(
        Pattern.compile("\\b(speak|talk|connect|transfer)\\b[\\s\\S]{0,20}\\b(human|agent|person|representative|someone)\\b",
            Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(human|real person|live agent)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(أريد|ابغى|أبغى|كلمني|حوّلني|حولني)[\\s\\S]{0,20}(موظف|إنسان|شخص|بشري)"));

    private ConversationScoring() {
    }

    /** One turn to score. */
    public record Turn(String text,
                       List<String> previousCustomerTexts,
                       int aiTurnsWithoutResolution,
                       boolean lastAiFailed) {

        public Turn {
            text = text == null ? "" : text;
            previousCustomerTexts = previousCustomerTexts == null ? List.of()
                : List.copyOf(previousCustomerTexts);
        }
    }

    /** The normalized 0-100 signals, plus the cues that produced them. */
    public record Scores(int frustration,
                         int sentiment,
                         int urgency,
                         int risk,
                         int intentConfidence,
                         Integer aiConfidence,
                         String intent,
                         String evaluator,
                         Map<String, Object> signals) {

        public Scores {
            signals = signals == null ? Map.of() : Map.copyOf(signals);
        }
    }

    /** Scores one turn. Pure: same input, same numbers, every time. */
    public static Scores evaluate(Turn turn) {
        String text = turn.text();

        int frustrationCues = countMatches(text, FRUSTRATION_CUES);
        boolean demandsHuman = anyMatch(text, ESCALATION_DEMANDS);
        int urgencyCues = countMatches(text, URGENCY_CUES);
        int riskCues = countMatches(text, RISK_CUES);
        int positiveCues = countMatches(text, POSITIVE_CUES);

        double shouting = shoutingRatio(text);
        int exclamations = Math.min(3, countChars(text, '!') + countChars(text, '؟'));
        int repetition = repeatedComplaint(text, turn.previousCustomerTexts());

        Map<String, Object> signals = new LinkedHashMap<>();
        signals.put("frustrationCues", frustrationCues);
        signals.put("demandsHuman", demandsHuman);
        signals.put("shouting", Math.round(shouting * 100.0) / 100.0);
        signals.put("exclamations", exclamations);
        signals.put("repetition", repetition);
        signals.put("aiTurnsWithoutResolution", turn.aiTurnsWithoutResolution());

        int frustration = clamp(
            frustrationCues * 22
                + (demandsHuman ? 30 : 0)
                + (int) Math.round(shouting * 25)
                + exclamations * 5
                + repetition * 15
                + turn.aiTurnsWithoutResolution() * 12
                + (turn.lastAiFailed() ? 15 : 0)
                - positiveCues * 20);

        int sentiment = clamp(50 + positiveCues * 20 - frustrationCues * 18 - (int) Math.round(shouting * 20));
        int urgency = clamp(urgencyCues * 35 + (demandsHuman ? 20 : 0) + (int) Math.round(frustration * 0.2));
        int risk = clamp(riskCues * 30 + (demandsHuman ? 25 : 0) + (int) Math.round(frustration * 0.25));

        String intent = classifyIntent(text);
        // Confidence is deliberately modest: a lexical match is evidence, not certainty.
        int intentConfidence = intent != null ? 70 : 25;

        return new Scores(frustration, sentiment, urgency, risk, intentConfidence, null, intent,
            EVALUATOR, signals);
    }

    /** Whether the customer asked for a person in this turn. */
    public static boolean explicitHumanRequest(String text) {
        return anyMatch(text == null ? "" : text, HUMAN_REQUEST_PATTERNS);
    }

    /**
     * Counts cue <em>occurrences</em>, not how many patterns fired.
     *
     * <p>Legacy comment: "'Unacceptable and terrible' is angrier than 'unacceptable', and both match
     * the same pattern — counting patterns would score them identically." Each pattern is capped so
     * one repeated word cannot dominate the score.
     */
    private static int countMatches(String text, List<Pattern> patterns) {
        int total = 0;
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(text);
            int hits = 0;
            while (matcher.find() && hits < PER_PATTERN_CAP) {
                hits++;
            }
            total += hits;
        }
        return total;
    }

    private static boolean anyMatch(String text, List<Pattern> patterns) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private static int countChars(String text, char target) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == target) {
                count++;
            }
        }
        return count;
    }

    /** Proportion of letters typed in capitals — only meaningful for Latin script. */
    private static double shoutingRatio(String text) {
        int letters = 0;
        int upper = 0;
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (!Character.isLetter(value) || value > 127) {
                continue;
            }
            letters++;
            if (Character.isUpperCase(value)) {
                upper++;
            }
        }
        if (letters < 8) {
            return 0d;
        }
        double ratio = (double) upper / letters;
        return ratio > 0.6d ? ratio : 0d;
    }

    /**
     * Whether the customer is asking the same thing again.
     *
     * <p>"Repetition is the strongest signal that the agent is not helping, and it is invisible to
     * any evaluator that only reads the latest message." Only the last three earlier messages are
     * compared, legacy behaviour.
     */
    private static int repeatedComplaint(String text, List<String> previous) {
        if (previous.isEmpty()) {
            return 0;
        }
        Set<String> current = tokenSet(text);
        if (current.size() < 3) {
            return 0;
        }
        int repeats = 0;
        List<String> tail = previous.size() > 3
            ? previous.subList(previous.size() - 3, previous.size()) : previous;
        for (String earlier : tail) {
            Set<String> other = tokenSet(earlier);
            int overlap = 0;
            for (String token : current) {
                if (other.contains(token)) {
                    overlap++;
                }
            }
            if ((double) overlap / current.size() > 0.5d) {
                repeats++;
            }
        }
        return repeats;
    }

    private static Set<String> tokenSet(String text) {
        Set<String> tokens = new java.util.LinkedHashSet<>();
        Matcher matcher = Pattern.compile("[\\p{L}\\p{N}]{3,}").matcher(text);
        int taken = 0;
        while (matcher.find() && taken < 40) {
            tokens.add(matcher.group().toLowerCase(Locale.ROOT));
            taken++;
        }
        return tokens;
    }

    private static String classifyIntent(String text) {
        for (Intent intent : INTENTS) {
            if (anyMatch(text, intent.patterns())) {
                return intent.name();
            }
        }
        return null;
    }

    private static int clamp(double value) {
        return Math.max(0, Math.min(100, (int) Math.round(value)));
    }

    private record Intent(String name, List<Pattern> patterns) {
    }
}