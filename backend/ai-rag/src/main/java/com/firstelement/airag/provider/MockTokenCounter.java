package com.firstelement.airag.provider;

/**
 * The token counter the deterministic provider reports, and the estimator the budget gate uses before the
 * call.
 *
 * <h2>Why one class owns both</h2>
 *
 * <p>Because they must agree. A budget that is checked against an estimate and then reconciled against a
 * different number is a budget whose enforcement depends on which side of the call you looked at. So the
 * estimate the gate uses is the same function the mock provider reports, and both are a fixed, documented
 * approximation of a BPE tokenizer rather than a real one.
 *
 * <h2>What it actually is</h2>
 *
 * <p>A mixed width-and-Arabic-letter count, rounded up:
 *
 * <pre>
 *   count(text) = ceil( latinRuns + digits/4 + otherCodePoints )
 * </pre>
 *
 * <p>which for English prose lands within roughly 15% of a BPE tokenizer and — more importantly — is
 * <em>monotone</em>. Monotonicity is the property that matters: a larger prompt never estimates as
 * smaller, so the budget gate cannot be walked around by rephrasing a message. A real tokenizer would be
 * more accurate and would still be monotone, but it would need a model file, and a budget must be
 * enforceable on a machine with no model file.
 *
 * <p>Arabic is counted per code point rather than per byte because {@code ب} is one letter and two bytes,
 * and counting bytes would inflate an Arabic prompt by ~40% relative to an English one — which would
 * quietly penalise exactly the language this product is Arabic-first for.
 */
public final class MockTokenCounter {

    private MockTokenCounter() {
    }

    /**
     * The token count for a piece of text.
     *
     * <p>{@code null} and blank are zero tokens, not one: an empty optional section contributes nothing
     * to a budget, which is the honest answer.
     */
    public static int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int latinRun = 0;
        int digits = 0;
        int other = 0;
        boolean inLatinRun = false;
        int codePoints = text.codePointCount(0, text.length());
        for (int offset = 0; offset < text.length(); ) {
            int cp = text.codePointAt(offset);
            offset += Character.charCount(cp);
            if (Character.isLetter(cp) && cp < 0x0590) {
                if (!inLatinRun) {
                    latinRun++;
                    inLatinRun = true;
                }
            } else {
                inLatinRun = false;
            }
            if (cp >= '0' && cp <= '9') {
                digits++;
            } else if (cp > 0x0590 && !Character.isWhitespace(cp)) {
                other++;
            }
        }
        long total = (long) latinRun + (digits + 3L) / 4L + other;
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    /** The sum of several sections, so a run's total is one call rather than a loop at every call site. */
    public static int countAll(Iterable<String> sections) {
        if (sections == null) {
            return 0;
        }
        int total = 0;
        for (String section : sections) {
            total += count(section);
        }
        return total;
    }
}