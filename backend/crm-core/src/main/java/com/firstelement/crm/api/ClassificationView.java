package com.firstelement.crm.api;

import java.util.List;

/**
 * A conversation's saved classification answers and the solution they point at.
 * Legacy {@code ClassificationView}, unchanged.
 *
 * @param isComplete {@code true} when every <em>required</em> question of the conversation's
 *                   project has an answer. Disabled questions are excluded entirely.
 * @param suggestion {@code null} when no solution was found; see
 *                   {@code com.firstelement.crm.case.CaseSolutionRetriever}
 */
public record ClassificationView(
    List<ClassificationAnswer> answers,
    String classifiedAt,
    ClassificationSuggestion suggestion,
    boolean isComplete
) {

    /** One answer: which question, and which of its values. */
    public record ClassificationAnswer(String questionId, String value) {
    }

    /**
     * @param source {@code MAPPING} when an admin-configured answer→document mapping matched;
     *               {@code SEARCH} when retrieval found it
     */
    public record ClassificationSuggestion(java.util.UUID documentId,
                                           String documentTitle,
                                           Double score,
                                           String excerpt,
                                           String source) {
    }
}