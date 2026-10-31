package com.firstelement.exports.catalog;

import java.util.UUID;

/**
 * A retirement that names the version it is retiring as its own successor.
 *
 * <h2>Why this is a refusal and not a {@code 500}</h2>
 *
 * <p>Both {@code report_versions_supersede_self_check} and {@code document_template_versions_supersede_self_check}
 * refuse it — {@code CHECK (supersededByVersionId IS NULL OR supersededByVersionId <> id)} — and before this
 * class existed, nothing checked first. The write reached the database, the CHECK fired, the exception was
 * a {@code DataIntegrityViolationException}, and this module's advice has no handler for it, so it reached
 * the application floor: {@code 500 INTERNAL_ERROR}, logged at ERROR, for a client that sent a body a
 * person could type by accident.
 *
 * <p>This is the second instance of the Phase 32 shape and it was found by the sweep rather than by a
 * caller: an empty {@code measures} was reported from the outside, and a version retiring itself is
 * reachable by the same route with the same effort.
 *
 * <h2>Why {@code 409} rather than {@code 400}</h2>
 *
 * <p>The request is syntactically fine and names a real row of the same definition, so it is not a
 * malformed body — that is {@code 400}, and {@code 400} would teach the caller that {@code successorVersion}
 * is a syntax problem. What is true is that the row's own state cannot carry out the instruction: a body
 * cannot be replaced by itself. That is the same family as "this version is not PUBLISHED, so it cannot be
 * retired", which is already a {@link ReportCatalogConflictException} and a {@code 409}.
 *
 * <h2>Its own code, not the generic one</h2>
 *
 * <p>{@code REPORT_CATALOG_CONFLICT} covers a lost publication race and a wrong-state transition. A client
 * recovering from either of those retries; a client whose retirement names itself has to choose a different
 * successor, and a retry loop over a self-named successor is a loop that never succeeds. The stable code is
 * {@code VERSION_SUPERSEDES_SELF} so the two are distinguishable without parsing a message.
 */
public class SelfSupersessionRefusal extends ReportCatalogConflictException {

    private static final long serialVersionUID = 1L;

    public SelfSupersessionRefusal(String message, String resourceType, UUID resourceId) {
        super(message, resourceType, resourceId);
    }

    /** The stable error code for the HTTP layer. */
    @Override
    public String code() {
        return "VERSION_SUPERSEDES_SELF";
    }
}