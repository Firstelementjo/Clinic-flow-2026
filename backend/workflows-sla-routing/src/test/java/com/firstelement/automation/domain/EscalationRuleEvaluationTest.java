package com.firstelement.automation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Escalation rule evaluation: explicit priority, no alphabetical ordering, and an absent signal that
 * never reads as zero.
 */
class EscalationRuleEvaluationTest {

    private static RuleFacts facts(int frustration, int sentiment, int urgency, int risk,
                                   Integer aiConfidence, int aiTurns, boolean humanRequest) {
        return new RuleFacts(frustration, sentiment, urgency, risk, 70, aiConfidence, aiTurns,
            humanRequest);
    }

    private static EscalationRule rule(String name, int priority, RuleCondition... conditions) {
        return new EscalationRule(UUID.randomUUID(), name, true, priority, List.of(conditions),
            EscalationRule.Combinator.ALL, null);
    }

    @Test
    @DisplayName("no rules means no escalation")
    void noRules() {
        assertThat(EscalationRules.evaluate(List.of(), facts(99, 0, 99, 99, null, 9, true))).isNull();
        assertThat(EscalationRules.evaluate(null, facts(99, 0, 99, 99, null, 9, true))).isNull();
    }

    @Test
    @DisplayName("the first matching rule in explicit priority order wins")
    void firstMatchByPriority() {
        EscalationRule lowPriority = rule("late", 90,
            new RuleCondition(ScoreSignal.RISK, RuleCondition.RuleOperator.GTE, 1));
        EscalationRule highPriority = rule("early", 10,
            new RuleCondition(ScoreSignal.RISK, RuleCondition.RuleOperator.GTE, 1));

        EscalationRules.Match match = EscalationRules.evaluate(
            new ArrayList<>(List.of(lowPriority, highPriority)), facts(0, 50, 0, 80, null, 0, false));

        assertThat(match.rule().name()).isEqualTo("early");
    }

    @Test
    @DisplayName("priority wins even when the names say the opposite")
    void priorityIsNeverAlphabetical() {
        EscalationRule zebra = rule("zebra", 10,
            new RuleCondition(ScoreSignal.RISK, RuleCondition.RuleOperator.GTE, 1));
        EscalationRule alpha = rule("alpha", 20,
            new RuleCondition(ScoreSignal.RISK, RuleCondition.RuleOperator.GTE, 1));

        for (int attempt = 0; attempt < 200; attempt++) {
            List<EscalationRule> order = attempt % 2 == 0
                ? new ArrayList<>(List.of(alpha, zebra)) : new ArrayList<>(List.of(zebra, alpha));
            assertThat(EscalationRules.evaluate(order, facts(0, 50, 0, 80, null, 0, false))
                .rule().name()).isEqualTo("zebra");
        }
    }

    @Test
    @DisplayName("a tie on priority is broken by the id, so the same rules always pick the same one")
    void tieBreaksOnId() {
        UUID first = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        UUID second = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        RuleCondition condition = new RuleCondition(ScoreSignal.RISK,
            RuleCondition.RuleOperator.GTE, 1);
        EscalationRule a = new EscalationRule(first, "named-b", true, 10, List.of(condition),
            EscalationRule.Combinator.ALL, null);
        EscalationRule b = new EscalationRule(second, "named-a", true, 10, List.of(condition),
            EscalationRule.Combinator.ALL, null);

        assertThat(EscalationRules.evaluate(List.of(a, b), facts(0, 50, 0, 80, null, 0, false))
            .rule().id()).isEqualTo(first);
        assertThat(EscalationRules.evaluate(List.of(b, a), facts(0, 50, 0, 80, null, 0, false))
            .rule().id()).isEqualTo(first);
    }

    @Test
    @DisplayName("a disabled rule never escalates")
    void disabledRuleIsIgnored() {
        EscalationRule disabled = new EscalationRule(UUID.randomUUID(), "off", false, 1,
            List.of(new RuleCondition(ScoreSignal.RISK, RuleCondition.RuleOperator.GTE, 1)),
            EscalationRule.Combinator.ALL, null);

        assertThat(EscalationRules.evaluate(List.of(disabled),
            facts(0, 50, 0, 99, null, 0, false))).isNull();
    }

    @Test
    @DisplayName("a rule with no conditions never matches, so a draft cannot escalate everyone")
    void emptyRuleNeverMatches() {
        EscalationRule empty = new EscalationRule(UUID.randomUUID(), "draft", true, 1, List.of(),
            EscalationRule.Combinator.ALL, null);

        assertThat(EscalationRules.evaluate(List.of(empty),
            facts(99, 0, 99, 99, null, 9, true))).isNull();
    }

    @Test
    @DisplayName("ALL needs every condition; ANY needs one")
    void combinators() {
        RuleCondition frustration = new RuleCondition(ScoreSignal.FRUSTRATION,
            RuleCondition.RuleOperator.GTE, 70);
        RuleCondition negative = new RuleCondition(ScoreSignal.SENTIMENT,
            RuleCondition.RuleOperator.LTE, 25);

        EscalationRule all = new EscalationRule(UUID.randomUUID(), "all", true, 1,
            List.of(frustration, negative), EscalationRule.Combinator.ALL, null);
        EscalationRule any = new EscalationRule(UUID.randomUUID(), "any", true, 2,
            List.of(frustration, negative), EscalationRule.Combinator.ANY, null);

        // Frustrated but not negative: only ANY fires.
        RuleFacts frustratedOnly = facts(80, 60, 0, 0, null, 0, false);
        assertThat(EscalationRules.evaluate(List.of(all), frustratedOnly)).isNull();
        assertThat(EscalationRules.evaluate(List.of(any), frustratedOnly)).isNotNull();

        RuleFacts both = facts(80, 10, 0, 0, null, 0, false);
        assertThat(EscalationRules.evaluate(List.of(all), both)).isNotNull();
    }

    @Test
    @DisplayName("an absent signal is false, never zero")
    void absentSignalIsNotZero() {
        // The legacy reason, verbatim: "A signal the evaluator did not produce (aiConfidence on a
        // customer turn) must not silently count as zero and fire an '<= 30' rule."
        EscalationRule lowConfidence = rule("low ai confidence", 1,
            new RuleCondition(ScoreSignal.AI_CONFIDENCE, RuleCondition.RuleOperator.LTE, 30));

        assertThat(EscalationRules.evaluate(List.of(lowConfidence),
            facts(0, 50, 0, 0, null, 0, false))).isNull();
        assertThat(EscalationRules.evaluate(List.of(lowConfidence),
            facts(0, 50, 0, 0, 20, 0, false))).isNotNull();
    }

    @Test
    @DisplayName("every operator compares the way it reads")
    void operators() {
        RuleFacts subject = facts(50, 50, 50, 50, 50, 5, false);

        assertThat(new RuleCondition(ScoreSignal.FRUSTRATION, RuleCondition.RuleOperator.GTE, 50)
            .test(subject)).isTrue();
        assertThat(new RuleCondition(ScoreSignal.FRUSTRATION, RuleCondition.RuleOperator.GTE, 51)
            .test(subject)).isFalse();
        assertThat(new RuleCondition(ScoreSignal.FRUSTRATION, RuleCondition.RuleOperator.GT, 49)
            .test(subject)).isTrue();
        assertThat(new RuleCondition(ScoreSignal.FRUSTRATION, RuleCondition.RuleOperator.LTE, 50)
            .test(subject)).isTrue();
        assertThat(new RuleCondition(ScoreSignal.FRUSTRATION, RuleCondition.RuleOperator.LT, 50)
            .test(subject)).isFalse();
        assertThat(new RuleCondition(ScoreSignal.FRUSTRATION, RuleCondition.RuleOperator.EQ, 50)
            .test(subject)).isTrue();
    }

    @Test
    @DisplayName("an explicit human request reads as 1, and only 1, never as an absent value")
    void explicitHumanRequest() {
        EscalationRule asked = rule("asked", 1,
            new RuleCondition(ScoreSignal.EXPLICIT_HUMAN_REQUEST, RuleCondition.RuleOperator.EQ, 1));
        EscalationRule notAsked = rule("not asked", 2,
            new RuleCondition(ScoreSignal.EXPLICIT_HUMAN_REQUEST, RuleCondition.RuleOperator.EQ, 0));

        assertThat(asked.test(facts(0, 50, 0, 0, null, 0, true)).isMatch()).isTrue();
        assertThat(notAsked.test(facts(0, 50, 0, 0, null, 0, true)).isMatch()).isFalse();
        assertThat(asked.test(facts(0, 50, 0, 0, null, 0, false)).isMatch()).isFalse();
        assertThat(notAsked.test(facts(0, 50, 0, 0, null, 0, false)).isMatch()).isTrue();
    }

    @Test
    @DisplayName("a match describes itself the way an employee reads it")
    void matchDescription() {
        EscalationRules.Match match = EscalationRules.evaluate(List.of(
            rule("Frustrated and negative", 40,
                new RuleCondition(ScoreSignal.FRUSTRATION, RuleCondition.RuleOperator.GTE, 50),
                new RuleCondition(ScoreSignal.SENTIMENT, RuleCondition.RuleOperator.LTE, 25))),
            facts(80, 10, 0, 0, null, 0, false));

        assertThat(match.describe())
            .isEqualTo("Frustrated and negative (frustration ≥ 50 and sentiment ≤ 25)");
        assertThat(match.matched()).hasSize(2);
    }

    @Test
    @DisplayName("an ANY match joins with 'or'")
    void anyMatchDescription() {
        EscalationRule any = new EscalationRule(UUID.randomUUID(), "either", true, 1,
            List.of(
                new RuleCondition(ScoreSignal.RISK, RuleCondition.RuleOperator.GTE, 75),
                new RuleCondition(ScoreSignal.URGENCY, RuleCondition.RuleOperator.GTE, 75)),
            EscalationRule.Combinator.ANY, null);

        assertThat(EscalationRules.evaluate(List.of(any), facts(0, 50, 80, 0, null, 0, false))
            .describe()).isEqualTo("either (urgency ≥ 75)");
    }

    @Test
    @DisplayName("the default rule set keeps the legacy names, priorities and thresholds")
    void defaultsAreTheLegacyRules() {
        List<EscalationRule> defaults = EscalationRules.defaults();

        assertThat(defaults).extracting(EscalationRule::name).containsExactly(
            "Customer asked for a person", "High risk", "Frustration threshold",
            "Frustrated and negative", "Repeated AI attempts");
        assertThat(defaults).extracting(EscalationRule::priority).containsExactly(10, 20, 30, 40, 50);
        assertThat(defaults).allSatisfy(rule -> {
            assertThat(rule.enabled()).isTrue();
            assertThat(rule.persisted()).isFalse();
            assertThat(rule.conditions()).isNotEmpty();
        });
    }

    @Test
    @DisplayName("the defaults escalate a furious customer and stay quiet about a thank-you")
    void defaultsBehave() {
        RuleFacts furious = facts(95, 5, 0, 0, null, 0, false);
        RuleFacts content = facts(0, 90, 0, 0, null, 0, false);

        EscalationRules.Match match = EscalationRules.evaluate(EscalationRules.defaults(), furious);
        assertThat(match).isNotNull();
        assertThat(match.rule().name()).isEqualTo("Frustration threshold");
        assertThat(EscalationRules.evaluate(EscalationRules.defaults(), content)).isNull();
    }

    @Test
    @DisplayName("an unknown stored signal or operator is not silently coerced")
    void unknownVocabulary() {
        assertThat(ScoreSignal.fromWireValue("vibes")).isNull();
        assertThat(ScoreSignal.fromWireValue("frustration")).isNotNull();
        assertThat(RuleCondition.RuleOperator.fromWireValue("approximately")).isNull();
        assertThat(RuleCondition.RuleOperator.fromWireValue("gte")).isNotNull();
        assertThatThrownBy(() -> new RuleCondition(null, RuleCondition.RuleOperator.GTE, 1))
            .isInstanceOf(IllegalArgumentException.class);
    }
}