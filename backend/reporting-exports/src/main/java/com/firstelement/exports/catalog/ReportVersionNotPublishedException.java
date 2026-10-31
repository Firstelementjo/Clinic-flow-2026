package com.firstelement.exports.catalog;

import java.util.UUID;

/**
 * A version pinned by an export request that has never been published.
 *
 * <h2>The rule</h2>
 *
 * <p>{@code CreateExportRequest} pins {@code (reportDefinitionId, reportVersion)} and, for DOCX,
 * {@code (documentTemplateId, documentTemplateVersion)}. Both halves must name a version that has been
 * <b>live at some point</b>: {@code PUBLISHED} or {@code RETIRED}. A {@code DRAFT} is refused.
 *
 * <h2>Why, when the schema does not say so</h2>
 *
 * <p>{@code export_jobs_report_pin_check} makes the pin all-or-nothing and says nothing about the version's
 * state, so before Phase 31 the only requirement was that a row with that number existed. A definition with
 * <em>no published version at all</em> was therefore exportable by naming its draft, and the export
 * succeeded. That is a silent success for the exact case the export catalogue exists to prevent: a client
 * that follows the catalogue sees no usable version, while a client that guesses a version number gets a
 * file produced from an unreviewed configuration.
 *
 * <p>It is also the reproduction rule stated backwards. The point of pinning is that the number a report
 * shipped under is answerable afterwards; a version that was never live has no such moment, so an artefact
 * built from it can never be reproduced from a published state.
 *
 * <h2>Why RETIRED stays legal</h2>
 *
 * <p>Because retiring is not deleting. A version body is immutable and never purged (ADR-0009 §4: "a version
 * is referenced by historical runs"), so an export pinning a retired version keeps producing exactly the
 * columns it pinned, forever. Refusing RETIRED would break every historical re-download in the product.
 *
 * <h2>Where the same decision is published</h2>
 *
 * <p>{@link ExportCatalogueService} computes the same answer per version and publishes it on
 * {@code exportable}/{@code refusal}, so a client that reads only {@code GET /api/v1/exports/catalogue}
 * never reaches this. {@code ExportCatalogueCapabilityIntegrationTest} then closes the loop empirically by
 * posting every offered version and asserting every withheld one is refused — a shared code path would prove
 * only that the code is shared.
 */
public class ReportVersionNotPublishedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UUID resourceId;
    private final int versionNumber;
    private final String state;

    public ReportVersionNotPublishedException(UUID resourceId, int versionNumber, String state,
                                              String resourceType) {
        super(resourceType + " " + resourceId + " version " + versionNumber + " is " + state
            + ", and a version must be PUBLISHED or RETIRED before an export may pin it. A version that has "
            + "never been live is configuration nobody has reviewed, so an export of it is refused rather "
            + "than produced from a draft. Publish the version and request the export again, or pin a "
            + "version the catalogue reports as exportable.");
        this.resourceId = resourceId;
        this.versionNumber = versionNumber;
        this.state = state;
    }

    /** The stable error code, on the response body and on the job's failure record. */
    public String code() {
        return "REPORT_VERSION_NOT_PUBLISHED";
    }

    /** The definition or template the refused version belongs to. */
    public UUID resourceId() {
        return resourceId;
    }

    /** The version number that was pinned. */
    public int versionNumber() {
        return versionNumber;
    }

    /** The state the pinned version was actually in. */
    public String state() {
        return state;
    }
}