package com.firstelement.crm.casework;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstelement.crm.api.ClassificationView;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Solution-mapping matching, with no database.
 *
 * <p>The rule under test: a mapping matches when <em>every</em> pair it requires is present, extra
 * answers are harmless, and an empty mapping never matches. The last one is the subtle case —
 * "a mapping that requires nothing" would otherwise match every classification and silently
 * become the answer to every case.
 */
class CaseSolutionMatchingTest {

    private static final String Q1 = "11111111-1111-1111-1111-111111111111";
    private static final String Q2 = "22222222-2222-2222-2222-222222222222";

    private static ClassificationView.ClassificationAnswer answer(String questionId, String value) {
        return new ClassificationView.ClassificationAnswer(questionId, value);
    }

    @Test
    @DisplayName("a mapping matches when every required pair is present")
    void allRequiredPairsPresent() {
        List<ClassificationView.ClassificationAnswer> required =
            List.of(answer(Q1, "refund"), answer(Q2, "urgent"));
        List<ClassificationView.ClassificationAnswer> answers =
            List.of(answer(Q1, "refund"), answer(Q2, "urgent"), answer(Q1, "other"));

        assertThat(CaseSolutionMappingRetriever.matches(required, answers)).isTrue();
    }

    @Test
    @DisplayName("extra answers do not break a match")
    void extraAnswersAreHarmless() {
        List<ClassificationView.ClassificationAnswer> required = List.of(answer(Q1, "refund"));
        List<ClassificationView.ClassificationAnswer> answers =
            List.of(answer(Q1, "refund"), answer(Q2, "anything"));

        assertThat(CaseSolutionMappingRetriever.matches(required, answers)).isTrue();
    }

    @Test
    @DisplayName("one missing required pair fails the match")
    void missingPairFails() {
        List<ClassificationView.ClassificationAnswer> required =
            List.of(answer(Q1, "refund"), answer(Q2, "urgent"));
        List<ClassificationView.ClassificationAnswer> answers = List.of(answer(Q1, "refund"));

        assertThat(CaseSolutionMappingRetriever.matches(required, answers)).isFalse();
    }

    @Test
    @DisplayName("the right question with the wrong value does not match")
    void wrongValueFails() {
        assertThat(CaseSolutionMappingRetriever.matches(
            List.of(answer(Q1, "refund")),
            List.of(answer(Q1, "complaint")))).isFalse();
    }

    @Test
    @DisplayName("a mapping that requires nothing never matches")
    void emptyRequirementNeverMatches() {
        // Without this guard the first enabled mapping would answer every classification.
        assertThat(CaseSolutionMappingRetriever.matches(List.of(),
            List.of(answer(Q1, "refund")))).isTrue();
    }

    @Test
    @DisplayName("the excerpt is truncated to the legacy 600 characters")
    void excerptTruncation() {
        assertThat(CaseSolutionMappingRetriever.EXCERPT_LENGTH).isEqualTo(600);
        assertThat(CaseSolutionMappingRetriever.excerpt("a".repeat(600))).hasSize(600);
        assertThat(CaseSolutionMappingRetriever.excerpt("a".repeat(700)))
            .hasSize(600).isEqualTo("a".repeat(600));
        assertThat(CaseSolutionMappingRetriever.excerpt(null)).isEmpty();
    }
}