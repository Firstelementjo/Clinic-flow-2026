package com.firstelement.exports.format;

import java.util.List;

/**
 * A DOCX template that cannot be rendered safely.
 *
 * <h2>Why a bad template FAILS rather than degrading</h2>
 *
 * <p>Phase-9 hard rule 5: "DOCX field vocabulary is bounded. A template referencing an unapproved
 * field fails the job rather than emitting an empty cell." The empty-cell case is the dangerous one:
 * a document that omits the phone number column because the template's author misspelled the field
 * looks identical to a document about customers who have no phone numbers, and it is distributed.
 *
 * <p>This type exists so the renderer can distinguish three failures that must all stop the job:
 *
 * <ul>
 *   <li><b>{@link Reason#UNAPPROVED_FIELD}</b> — the template names a field the report version's
 *       approved set does not contain. This is the main case: it also covers a field that exists in the
 *       registry but was <em>withheld from this caller</em>, because a template that could reach a
 *       withheld field is a redaction bypass.</li>
 *   <li><b>{@link Reason#MALFORMED_PLACEHOLDER}</b> — an unbalanced or empty placeholder such as
 *       {@code {{}}}. Rendering it literally would put template syntax in a customer-facing document.</li>
 *   <li><b>{@link Reason#UNSUPPORTED_MEDIA}</b> — the stored template is not a DOCX this renderer can
 *       read. The schema restricts {@code document_templates."kind"} to DOCX and PDF, and this module
 *       renders DOCX only, so a PDF template is a refusal rather than a wrong-format file with a DOCX
 *       extension.</li>
 * </ul>
 *
 * <p>Every one of these is a job failure with a message an operator can act on, recorded on
 * {@code export_jobs."errorMessage"} and on the attempt row.
 */
public class DocxTemplateException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why rendering stopped. */
    public enum Reason {
        /** The template names a field outside the approved set. */
        UNAPPROVED_FIELD,
        /** The template contains an unbalanced or empty placeholder. */
        MALFORMED_PLACEHOLDER,
        /** The stored template is not a readable DOCX. */
        UNSUPPORTED_MEDIA
    }

    private final Reason reason;
    private final List<String> offendingNames;

    public DocxTemplateException(Reason reason, String message, List<String> offendingNames) {
        super(message);
        this.reason = reason;
        this.offendingNames = List.copyOf(offendingNames == null ? List.of() : offendingNames);
    }

    public Reason reason() {
        return reason;
    }

    /** The placeholder names that caused the refusal, for the job's failure message. */
    public List<String> offendingNames() {
        return offendingNames;
    }

    /** The stable error code, recorded on the job and in the attempt history. */
    public String code() {
        return "DOCX_TEMPLATE_" + reason.name();
    }

    public static DocxTemplateException unapprovedField(String name) {
        return new DocxTemplateException(Reason.UNAPPROVED_FIELD,
            "The DOCX template references '" + name + "', which is not in this report version's "
                + "approved field set. The job failed rather than rendering an empty cell, because an "
                + "empty cell reads as 'no value' and a redacted field must read as absent.",
            List.of(name));
    }

    public static DocxTemplateException malformedPlaceholder(String detail) {
        return new DocxTemplateException(Reason.MALFORMED_PLACEHOLDER,
            "The DOCX template contains a malformed placeholder: " + detail
                + ". Rendering it literally would put template syntax in a customer-facing document.",
            List.of());
    }

    public static DocxTemplateException unsupportedMedia(String detail) {
        return new DocxTemplateException(Reason.UNSUPPORTED_MEDIA,
            "The stored document template cannot be rendered: " + detail, List.of());
    }
}