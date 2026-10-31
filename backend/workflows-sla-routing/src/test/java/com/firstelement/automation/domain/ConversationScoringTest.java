package com.firstelement.automation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Conversation scoring: deterministic, bilingual, and explainable.
 *
 * <p>The legacy rationale for a lexical evaluator is kept as the test's premise: it "is reproducible so
 * an escalation can be explained after the fact, and it cannot be talked out of its own numbers by a
 * customer's text". Reproducibility is therefore the first assertion, not an afterthought.
 */
class ConversationScoringTest {

    private static ConversationScoring.Turn turn(String text, List<String> previous, int aiTurns,
                                                 boolean lastAiFailed) {
        return new ConversationScoring.Turn(text, previous, aiTurns, lastAiFailed);
    }

    @Test
    @DisplayName("the same message always scores the same")
    void deterministic() {
        ConversationScoring.Turn subject = turn("This is unacceptable and ridiculous!!",
            List.of("this is unacceptable"), 2, false);
        ConversationScoring.Scores first = ConversationScoring.evaluate(subject);
        for (int attempt = 0; attempt < 500; attempt++) {
            ConversationScoring.Scores again = ConversationScoring.evaluate(subject);
            assertThat(again.frustration()).isEqualTo(first.frustration());
            assertThat(again.sentiment()).isEqualTo(first.sentiment());
            assertThat(again.urgency()).isEqualTo(first.urgency());
            assertThat(again.risk()).isEqualTo(first.risk());
            assertThat(again.intent()).isEqualTo(first.intent());
        }
    }

    @Test
    @DisplayName("every signal is normalized to 0..100")
    void scoresAreClamped() {
        ConversationScoring.Scores furious = ConversationScoring.evaluate(turn(
            "THIS IS ABSOLUTELY UNACCEPTABLE!!! RIDICULOUS!!! WORST EVER!!! SCAM!!! I WILL SUE MY "
                + "MANAGER ABOUT THE REFUND RIGHT NOW IT IS URGENT AS AN EMERGENCY TODAY!!! "
                + "Fraud. Chargeback. Cancel my account.", List.of(), 9, true));
        ConversationScoring.Scores happy = ConversationScoring.evaluate(turn(
            "thanks, great, perfect, helpful, appreciate it", List.of(), 0, false));

        assertThat(furious.frustration()).isBetween(0, 100);
        assertThat(furious.risk()).isBetween(0, 100);
        assertThat(furious.urgency()).isBetween(0, 100);
        assertThat(happy.sentiment()).isBetween(0, 100);
        assertThat(happy.frustration()).isEqualTo(0);
        assertThat(furious.frustration()).isGreaterThan(happy.frustration());
        assertThat(furious.sentiment()).isLessThan(happy.sentiment());
    }

    @Test
    @DisplayName("cue occurrences count, not patterns, and each pattern is capped")
    void occurrenceCounting() {
        ConversationScoring.Scores once = ConversationScoring.evaluate(turn("unacceptable",
            List.of(), 0, false));
        ConversationScoring.Scores twice = ConversationScoring.evaluate(turn("unacceptable unacceptable",
            List.of(), 0, false));

        assertThat((Integer) twice.signals().get("frustrationCues")).isEqualTo(2);
        assertThat(twice.frustration()).isGreaterThan(once.frustration());

        // The cap: five repeats score no differently from three.
        ConversationScoring.Scores capped = ConversationScoring.evaluate(turn(
            "unacceptable unacceptable unacceptable unacceptable unacceptable", List.of(), 0, false));
        assertThat((Integer) capped.signals().get("frustrationCues")).isEqualTo(3);
    }

    @Test
    @DisplayName("shouting only counts for Latin script, and only when it is sustained")
    void shoutingRatio() {
        assertThat(((Number) ConversationScoring.evaluate(turn("Stop it now", List.of(), 0, false))
            .signals().get("shouting")).doubleValue()).isZero();
        assertThat(((Number) ConversationScoring.evaluate(turn(
            "THIS IS COMPLETELY AND TOTALLY UNACCEPTABLE", List.of(), 0, false))
            .signals().get("shouting")).doubleValue()).isGreaterThan(0.6d);
    }

    @Test
    @DisplayName("repetition raises frustration, because it is invisible to a per-message reader")
    void repetition() {
        String complaint = "my order has not arrived and nobody has replied to my messages";
        ConversationScoring.Scores firstTime = ConversationScoring.evaluate(turn(complaint,
            List.of(), 0, false));
        ConversationScoring.Scores thirdTime = ConversationScoring.evaluate(turn(complaint,
            List.of("hello", "anything there", complaint), 0, false));

        assertThat((Integer) thirdTime.signals().get("repetition")).isEqualTo(1);
        assertThat(thirdTime.frustration()).isGreaterThan(firstTime.frustration());
    }

    @Test
    @DisplayName("only the last three earlier messages are compared for repetition")
    void repetitionWindowIsThree() {
        String complaint = "my order has not arrived and nobody has replied to my messages";
        ConversationScoring.Scores deep = ConversationScoring.evaluate(turn(complaint,
            List.of(complaint, complaint, complaint, complaint, complaint, complaint), 0, false));

        assertThat((Integer) deep.signals().get("repetition")).isEqualTo(3);
    }

    @Test
    @DisplayName("unanswered AI turns raise frustration")
    void aiTurnsRaiseFrustration() {
        ConversationScoring.Scores fresh = ConversationScoring.evaluate(
            turn("still nothing", List.of(), 0, false));
        ConversationScoring.Scores abandoned = ConversationScoring.evaluate(
            turn("still nothing", List.of(), 4, false));

        assertThat(abandoned.frustration()).isGreaterThan(fresh.frustration());
    }

    @Test
    @DisplayName("a failed AI run raises frustration")
    void failedAiRunRaisesFrustration() {
        assertThat(ConversationScoring.evaluate(turn("hello", List.of(), 0, true)).frustration())
            .isGreaterThan(ConversationScoring.evaluate(turn("hello", List.of(), 0, false))
                .frustration());
    }

    @Test
    @DisplayName("Arabic frustration cues are first class, not a translation of the English list")
    void arabicFrustrationCues() {
        ConversationScoring.Scores arabic = ConversationScoring.evaluate(
            turn("غاضب جدا، هذا غير مقبول ومافي فايدة", List.of(), 0, false));

        assertThat((Integer) arabic.signals().get("frustrationCues")).isGreaterThanOrEqualTo(2);
        assertThat(arabic.frustration()).isGreaterThan(50);
    }

    @Test
    @DisplayName("an Arabic escalation demand raises risk and urgency")
    void arabicEscalationDemand() {
        ConversationScoring.Scores demand = ConversationScoring.evaluate(
            turn("أريد التحدث مع مشرف وسأرفع قضية", List.of(), 0, false));

        assertThat(demand.signals().get("demandsHuman")).isEqualTo(Boolean.TRUE);
        assertThat(demand.risk()).isGreaterThan(0);
        assertThat(demand.urgency()).isGreaterThan(0);
    }

    @Test
    @DisplayName("Arabic intents are classified")
    void arabicIntent() {
        assertThat(ConversationScoring.evaluate(turn("أريد استرجاع الفلوسي", List.of(), 0, false))
            .intent()).isEqualTo("refund");
        assertThat(ConversationScoring.evaluate(turn("وين طلبي؟ التوصيل تأخر", List.of(), 0, false))
            .intent()).isEqualTo("delivery");
    }

    @Test
    @DisplayName("English intents are classified in declaration order, complaint before cancellation")
    void englishIntents() {
        assertThat(ConversationScoring.evaluate(turn("I want a refund", List.of(), 0, false)).intent())
            .isEqualTo("refund");
        assertThat(ConversationScoring.evaluate(turn("where is my order", List.of(), 0, false)).intent())
            .isEqualTo("delivery");
        assertThat(ConversationScoring.evaluate(turn("I want to cancel my subscription",
            List.of(), 0, false)).intent()).isEqualTo("cancellation");
        assertThat(ConversationScoring.evaluate(turn("cannot log in, error", List.of(), 0, false))
            .intent()).isEqualTo("technical");
        assertThat(ConversationScoring.evaluate(turn("good morning", List.of(), 0, false)).intent())
            .isNull();
    }

    @Test
    @DisplayName("intent confidence is evidence, not certainty")
    void intentConfidenceIsModest() {
        assertThat(ConversationScoring.evaluate(turn("I want a refund", List.of(), 0, false))
            .intentConfidence()).isEqualTo(70);
        assertThat(ConversationScoring.evaluate(turn("good morning", List.of(), 0, false))
            .intentConfidence()).isEqualTo(25);
    }

    @Test
    @DisplayName("aiConfidence is absent for a customer turn, not zero")
    void aiConfidenceIsNull() {
        assertThat(ConversationScoring.evaluate(turn("hello", List.of(), 0, false)).aiConfidence())
            .isNull();
    }

    @Test
    @DisplayName("asking for a person is detected in English and Arabic, and only then")
    void explicitHumanRequest() {
        assertThat(ConversationScoring.explicitHumanRequest("please connect me to a human")).isTrue();
        assertThat(ConversationScoring.explicitHumanRequest("I want a live agent")).isTrue();
        assertThat(ConversationScoring.explicitHumanRequest("أريد التحدث مع موظف")).isTrue();
        assertThat(ConversationScoring.explicitHumanRequest("error: connection refused")).isFalse();
        assertThat(ConversationScoring.explicitHumanRequest("")).isFalse();
    }

    @Test
    @DisplayName("a positive message is not scored as angry")
    void positiveCuesOffsetFrustration() {
        ConversationScoring.Scores scored = ConversationScoring.evaluate(
            turn("thanks, that is great, really helpful", List.of(), 0, false));

        assertThat(scored.frustration()).isZero();
        assertThat(scored.sentiment()).isGreaterThan(50);
    }

    @Test
    @DisplayName("an empty message scores zero rather than throwing")
    void emptyText() {
        ConversationScoring.Scores scores = ConversationScoring.evaluate(
            new ConversationScoring.Turn(null, null, 0, false));

        assertThat(scores.frustration()).isZero();
        assertThat(scores.intent()).isNull();
        assertThat(scores.evaluator()).isEqualTo(ConversationScoring.EVALUATOR);
    }

    @Test
    @DisplayName("the cue map explains the score")
    void signalsAreRecorded() {
        ConversationScoring.Scores scores = ConversationScoring.evaluate(
            turn("URGENT!!! where is my order", List.of(), 1, false));

        assertThat(scores.signals()).containsKeys("frustrationCues", "demandsHuman", "shouting",
            "exclamations", "repetition", "aiTurnsWithoutResolution");
        assertThat(scores.signals().get("aiTurnsWithoutResolution")).isEqualTo(1);
    }
}
