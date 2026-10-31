package com.firstelement.crm.api;

import java.util.List;
import java.util.UUID;

/**
 * A configurable classification question. Legacy {@code ClassificationQuestionView}, unchanged.
 *
 * <p>Questions are configuration, not code: an admin writes them and they take effect
 * immediately. That is why the unique constraint is {@code (projectId, position)} and not an
 * id — two admins writing position 3 in the same project collide rather than silently
 * interleave.
 */
public record ClassificationQuestionView(
    UUID id,
    int position,
    String promptAr,
    String promptEn,
    List<ClassificationOption> options,
    boolean isRequired,
    boolean isEnabled
) {

    /** One choice. Both labels are mandatory because the question is asked in either locale. */
    public record ClassificationOption(String value, String labelAr, String labelEn) {
    }
}