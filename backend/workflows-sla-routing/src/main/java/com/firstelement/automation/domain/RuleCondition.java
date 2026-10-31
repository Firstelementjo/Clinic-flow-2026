package com.firstelement.automation.domain;

/**
 * One condition of an escalation rule. Ported from
 * {@code packages/ai/src/scoring/rules.ts}.
 *
 * <h2>Conditions are data, not code</h2>
 *
 * <p>"An ops manager changes 'escalate at 70' in the UI, and the change takes effect without a
 * deploy." A rule is therefore a list of these, stored in {@code escalation_rules.conditions}
 * (jsonb), and the parse is strict: an unparseable condition is <b>dropped with the rule reported
 * as invalid</b>, never coerced to a condition that cannot be true.
 */
public record RuleCondition(ScoreSignal signal, RuleOperator operator, int value) {

    /** The comparison vocabulary, verbatim from the legacy {@code RuleOperator}. */
    public enum RuleOperator {
        GTE("gte", "≥"),
        LTE("lte", "≤"),
        GT("gt", ">"),
        LT("lt", "<"),
        EQ("eq", "=");

        private final String wireValue;
        private final String symbol;

        RuleOperator(String wireValue, String symbol) {
            this.wireValue = wireValue;
            this.symbol = symbol;
        }

        public String wireValue() {
            return wireValue;
        }

        /** The operator as an operator reads it in the handover reason. */
        public String symbol() {
            return symbol;
        }

        /** @return the operator, or {@code null} when the stored value names no operator. */
        public static RuleOperator fromWireValue(String value) {
            if (value == null) {
                return null;
            }
            for (RuleOperator operator : values()) {
                if (operator.wireValue.equals(value)) {
                    return operator;
                }
            }
            return null;
        }
    }

    public RuleCondition {
        if (signal == null) {
            throw new IllegalArgumentException("signal is required");
        }
        if (operator == null) {
            throw new IllegalArgumentException("operator is required");
        }
    }

    /**
     * Evaluates the condition against {@code facts}.
     *
     * <p>A signal the facts do not carry is {@code false}, never zero. The legacy comment is the
     * reason: "A signal the evaluator did not produce (aiConfidence on a customer turn) must not
     * silently count as zero and fire an '&le; 30' rule." Getting this wrong escalates every
     * customer turn, and the cost of that is a queue nobody reads.
     */
    public boolean test(RuleFacts facts) {
        Integer actual = facts.read(signal);
        if (actual == null) {
            return false;
        }
        return switch (operator) {
            case GTE -> actual >= value;
            case LTE -> actual <= value;
            case GT -> actual > value;
            case LT -> actual < value;
            case EQ -> actual == value;
        };
    }

    /** How this condition reads in the sentence an employee sees first. */
    public String describe() {
        return signal.wireValue() + " " + operator.symbol() + " " + value;
    }
}