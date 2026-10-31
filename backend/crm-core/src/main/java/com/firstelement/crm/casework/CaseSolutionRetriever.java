package com.firstelement.crm.casework;

import com.firstelement.crm.api.ClassificationQuestionView;
import com.firstelement.crm.api.ClassificationView;
import com.firstelement.security.TenantContext;
import java.util.List;
import java.util.UUID;

/**
 * Resolves a classification's answers to the solution they point at.
 *
 * <h2>Why this is a port and not a call</h2>
 *
 * <p>The legacy implementation had two paths, in this order:
 *
 * <ol>
 *   <li>An admin-configured answer→document mapping ({@code case_solution_mappings}). This is
 *       deterministic and belongs to this phase — the mapping table is part of the CRM case
 *       model.</li>
 *   <li>Otherwise a knowledge-base retrieval using the chosen answer labels as the query. That
 *       retrieval is pgvector over {@code document_chunks} and belongs to Phase 10's Spring AI
 *       module; calling an embedding provider from an HTTP mutation would also violate the
 *       "no synchronous AI on request threads" rule.</li>
 * </ol>
 *
 * <p>{@link CaseSolutionMappingRetriever} implements path 1 exactly and is the only bean bound,
 * so path 2 stays absent rather than being faked with a different algorithm. When it returns
 * {@code null}, {@code suggestion} is {@code null} — which the contract already allows — and the
 * Phase 10 module replaces this bean with one that also performs the search.
 *
 * <p>Registering a second implementation is a Phase 10 change in
 * {@code :backend:knowledge-ai}; this interface is the seam.
 *
 * <h2>The caller's context, not a constructed one</h2>
 *
 * <p>{@code context} is the caller's authenticated scope. An implementation must derive its
 * statements from it through {@code TenantQueryGuard}; it must never build a scope from the
 * arguments, or the {@code projectId} parameter would become authorization.
 */
public interface CaseSolutionRetriever {

    /**
     * @param context    the caller's authenticated scope
     * @param projectId  the conversation's own project; a solution may never come from another
     *                   project
     * @param questions  the project's enabled questions, for the label lookup
     * @param answers    the accepted answers
     * @return the suggestion, or {@code null} when nothing was found
     */
    ClassificationView.ClassificationSuggestion suggest(TenantContext context,
                                                       UUID projectId,
                                                       List<Question> questions,
                                                       List<ClassificationView.ClassificationAnswer> answers);

    /** The question shape the retriever needs; kept minimal so the port has no persistence type. */
    record Question(UUID id,
                    List<ClassificationQuestionView.ClassificationOption> options) {
    }
}