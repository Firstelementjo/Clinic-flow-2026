package com.firstelement.airag.redaction;

import java.util.List;

/**
 * The result of redacting one section, with the evidence a compliance report needs and nothing else.
 *
 * @param text        the redacted text. The only field a caller may use as content.
 * @param applied     one entry per rule that fired, with counts
 * @param totalValues the sum of every rule's value count
 * @param totalCharacters the sum of every rule's removed character count
 * @param policy      the policy name that actually ran, recorded on
 *                    {@code ai_redaction_records."redactionPolicy"}
 */
public record RedactionOutcome(String text,
                               List<Applied> applied,
                               int totalValues,
                               int totalCharacters,
                               String policy) {

    public RedactionOutcome {
        applied = applied == null ? List.of() : List.copyOf(applied);
    }

    /**
     * One rule's contribution.
     *
     * @param fieldPath the schema path, e.g. {@code prompt.untrusted}. Never a JSON pointer into the text
     *                  and never the text itself.
     */
    public record Applied(String ruleId,
                          String fieldPath,
                          int valuesRedacted,
                          Integer charactersRedacted) {
    }

    public boolean redactedAnything() {
        return totalValues > 0;
    }

    /** The field paths that fired, for the run's step detail. */
    public List<String> fieldPaths() {
        return applied.stream().map(Applied::fieldPath).distinct().toList();
    }
}