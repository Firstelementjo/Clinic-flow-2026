package com.firstelement.crm.api;

import java.util.List;

/**
 * {@code PUT /api/v1/projects/{projectId}/classification-questions[/{questionId}]} body — the
 * legacy {@code saveClassificationQuestionSchema}, unchanged.
 *
 * <p>{@code options} needs at least two entries: a single-choice question is not a
 * classification, and permitting one produces an unanswerable "complete" case.
 */
public record SaveClassificationQuestionRequest(int position,
                                                String promptAr,
                                                String promptEn,
                                                List<ClassificationQuestionView.ClassificationOption> options,
                                                Boolean isRequired,
                                                Boolean isEnabled) {

    public static final int MIN_OPTIONS = 2;
    public static final int MAX_OPTIONS = 20;
    public static final int MAX_PROMPT = 300;
    public static final int MAX_VALUE = 60;
    public static final int MAX_LABEL = 160;

    public boolean isRequiredOrDefault() {
        return isRequired == null || isRequired;
    }

    public boolean isEnabledOrDefault() {
        return isEnabled == null || isEnabled;
    }
}