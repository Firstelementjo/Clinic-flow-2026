package com.firstelement.crm.api;

import java.util.List;

/** {@code PUT /api/v1/conversations/{id}/classification} body. */
public record SaveClassificationRequest(
    List<ClassificationView.ClassificationAnswer> answers) {

    public static final int MAX_ANSWERS = 20;

    public SaveClassificationRequest {
        answers = answers == null ? List.of() : List.copyOf(answers);
    }
}