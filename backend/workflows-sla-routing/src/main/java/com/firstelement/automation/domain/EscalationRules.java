package com.firstelement.automation.domain;

import java.util.Comparator;
import java.util.List;

/**
 * Rule evaluation and the default rule set. Ported from
 * {@code packages/ai/src/scoring/rules.ts}.
 *
 * <h2>Ordering, and the hard rule it satisfies</h2>
 *
 * <p>{@link #ORDER} is {@code priority} then {@code id}. Priority is an explicit integer column on
 * {@code escalation_rules} ({@code DEFAULT 100 NOT NULL}), so "no alphabetical rule ordering"
 * (plan.md, phase-06 hard rule) is structural: a rule named "zebra" with priority 10 fires before
 * one named "alpha" with priority 20, and reordering is a data change rather than a rename. The id
 * is the tie-break for the same reason the router tie-breaks on employee id — a rule set where two
 * rules are equally true and the winner changes between runs is impossible to reason about or to
 * test. The comparator is a {@link java.util.Comparator} on two explicit fields, so it cannot
 * inherit a name-based order by accident.
 *
 * <h2>Why disabled and empty rules are dropped before ordering</h2>
 *
 * <p>Legacy behaviour, unchanged. A disabled rule must not escalate, and a rule with no conditions
 * would match {@code ALL} vacuously — which is how an unfinished draft escalates every customer.
 */
public final class EscalationRules {

    /**
     * The one ordering. Exposed so a repository ordering a rule set cannot invent a second one.
     */
    public static final Comparator<EscalationRule> ORDER =
        Comparator.comparingInt(EscalationRule::priority)
            .thenComparing(rule -> rule.id().toString());

    private EscalationRules() {
    }

    /**
     * First matching rule wins.
     *
     * @return the match, or {@code null} when no enabled rule fires
     */
    public static Match evaluate(List<EscalationRule> rules, RuleFacts facts) {
        if (rules == null || rules.isEmpty()) {
            return null;
        }
        for (EscalationRule rule : rules.stream()
            .filter(candidate -> candidate.enabled())
            .filter(candidate -> !candidate.conditions().isEmpty())
            .sorted(ORDER)
            .toList()) {
            Match match = rule.test(facts);
            if (match.isMatch()) {
                return match;
            }
        }
        return null;
    }

    /** A rule and the conditions that were true, for the escalation reason. */
    public record Match(EscalationRule rule, List<RuleCondition> matched, boolean isMatch) {

        /**
         * The one sentence an employee reads first.
         *
         * <p>Legacy {@code describeMatch}: the rule's name, then the conditions that held, joined
         * by the combinator. The reason is snapshotted onto the escalation row, so editing the rule
         * afterwards cannot rewrite why this fired.
         */
        public String describe() {
            String joined = matched.stream().map(RuleCondition::describe)
                .reduce((left, right) -> left + (rule.combinator() == EscalationRule.Combinator.ANY
                    ? " or " : " and ") + right)
                .orElse("");
            return joined.isBlank() ? rule.name() : rule.name() + " (" + joined + ")";
        }
    }

    /**
     * The rules a project with no stored rules starts with.
     *
     * <p>Ported from {@code DEFAULT_ESCALATION_RULES} with its priorities intact (10, 20, 30, 40,
     * 50), and the legacy framing kept: "They encode the plan's own examples and are fully
     * editable afterwards — defaults, not policy." They are returned with {@code id = null}-free
     * deterministic name-derived ids so a stored escalation's {@code ruleId} is {@code null} for a
     * default, exactly as the legacy engine did with its {@code default:<index>} prefix.
     */
    public static List<EscalationRule> defaults() {
        return List.of(
            EscalationRule.defaultRule("default:explicit-human-request",
                "Customer asked for a person", 10,
                EscalationRule.Combinator.ALL,
                List.of(new RuleCondition(ScoreSignal.EXPLICIT_HUMAN_REQUEST,
                    RuleCondition.RuleOperator.EQ, 1))),
            EscalationRule.defaultRule("default:high-risk", "High risk", 20,
                EscalationRule.Combinator.ALL,
                List.of(new RuleCondition(ScoreSignal.RISK, RuleCondition.RuleOperator.GTE, 75))),
            EscalationRule.defaultRule("default:frustration-threshold", "Frustration threshold", 30,
                EscalationRule.Combinator.ALL,
                List.of(new RuleCondition(ScoreSignal.FRUSTRATION, RuleCondition.RuleOperator.GTE, 70))),
            EscalationRule.defaultRule("default:frustrated-and-negative",
                "Frustrated and negative", 40,
                EscalationRule.Combinator.ALL,
                List.of(
                    new RuleCondition(ScoreSignal.FRUSTRATION, RuleCondition.RuleOperator.GTE, 50),
                    new RuleCondition(ScoreSignal.SENTIMENT, RuleCondition.RuleOperator.LTE, 25))),
            EscalationRule.defaultRule("default:repeated-ai-attempts", "Repeated AI attempts", 50,
                EscalationRule.Combinator.ALL,
                List.of(new RuleCondition(ScoreSignal.AI_TURNS_WITHOUT_RESOLUTION,
                    RuleCondition.RuleOperator.GTE, 4))));
    }
}