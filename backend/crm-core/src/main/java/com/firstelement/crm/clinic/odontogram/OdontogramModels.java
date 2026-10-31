package com.firstelement.crm.clinic.odontogram;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Odontogram read models. */
public final class OdontogramModels {

    private OdontogramModels() {
    }

    /** One finding, as the chart reads it. */
    public record FindingRow(
            UUID id,
            UUID patientId,
            int toothFdi,
            String scope,
            List<String> surfaces,
            String code,
            String attributes,
            String state,
            String source,
            UUID plannedProcedureId,
            UUID recordedBy,
            LocalDateTime recordedAt,
            String note,
            UUID supersededBy) {
    }

    /**
     * A finding together with whether it still stands.
     *
     * <p>{@code superseded} is computed at read time from {@code supersededBy}, not stored. A
     * stored flag would need updating, and this table is append-only.
     */
    public record ChartEntry(FindingRow finding, boolean superseded) {
    }

    /** One term in the clinic's finding vocabulary. */
    public record VocabularyTerm(
            UUID id,
            String code,
            String category,
            String labelAr,
            String labelEn,
            boolean clinicianReviewed,
            boolean active) {
    }

    /** Request payload to record a new odontogram observation (CLN-03, Slice S4). */
    public record RecordFindingRequest(
            int toothFdi,
            String scope,
            List<String> surfaces,
            String code,
            String attributesJson,
            String state,
            String source,
            UUID plannedProcedureId,
            String note) {
    }

    /** Request payload to record a correction superseding an earlier finding (Slice S4). */
    public record CorrectFindingRequest(
            String code,
            String attributesJson,
            String note) {
    }

    /** Periodontal exam session header (Slice S4). */
    public record PeriodontalExamHeader(
            UUID id,
            UUID organizationId,
            UUID projectId,
            UUID patientId,
            UUID examinedByUserId,
            String examinedByUserName,
            LocalDateTime examinedAt,
            String notes,
            int measurementCount,
            LocalDateTime createdAt) {
    }

    /** 6-site periodontal measurement on a single tooth (Slice S4). */
    public record PeriodontalMeasurement(
            UUID id,
            UUID organizationId,
            UUID projectId,
            UUID examId,
            UUID patientId,
            int toothFdi,
            Integer probingDepthMb,
            Integer probingDepthB,
            Integer probingDepthDb,
            Integer probingDepthMl,
            Integer probingDepthL,
            Integer probingDepthDl,
            Integer gingivalMarginMb,
            Integer gingivalMarginB,
            Integer gingivalMarginDb,
            Integer gingivalMarginMl,
            Integer gingivalMarginL,
            Integer gingivalMarginDl,
            Integer calMb,
            Integer calB,
            Integer calDb,
            Integer calMl,
            Integer calL,
            Integer calDl,
            boolean bopMb,
            boolean bopB,
            boolean bopDb,
            boolean bopMl,
            boolean bopL,
            boolean bopDl,
            boolean suppurationMb,
            boolean suppurationB,
            boolean suppurationDb,
            boolean suppurationMl,
            boolean suppurationL,
            boolean suppurationDl,
            boolean plaqueMb,
            boolean plaqueB,
            boolean plaqueDb,
            boolean plaqueMl,
            boolean plaqueL,
            boolean plaqueDl,
            Integer furcationClass,
            Integer mobilityGrade,
            LocalDateTime createdAt) {
    }

    /** Input payload for a single tooth measurement during a perio exam. */
    public record PeriodontalMeasurementInput(
            int toothFdi,
            Integer probingDepthMb,
            Integer probingDepthB,
            Integer probingDepthDb,
            Integer probingDepthMl,
            Integer probingDepthL,
            Integer probingDepthDl,
            Integer gingivalMarginMb,
            Integer gingivalMarginB,
            Integer gingivalMarginDb,
            Integer gingivalMarginMl,
            Integer gingivalMarginL,
            Integer gingivalMarginDl,
            boolean bopMb,
            boolean bopB,
            boolean bopDb,
            boolean bopMl,
            boolean bopL,
            boolean bopDl,
            boolean suppurationMb,
            boolean suppurationB,
            boolean suppurationDb,
            boolean suppurationMl,
            boolean suppurationL,
            boolean suppurationDl,
            boolean plaqueMb,
            boolean plaqueB,
            boolean plaqueDb,
            boolean plaqueMl,
            boolean plaqueL,
            boolean plaqueDl,
            Integer furcationClass,
            Integer mobilityGrade) {
    }

    /** Full periodontal exam detail with all tooth measurements. */
    public record PeriodontalExamDetail(
            PeriodontalExamHeader exam,
            List<PeriodontalMeasurement> measurements) {
    }

    /** Request payload to create a new periodontal exam session. */
    public record CreatePeriodontalExamRequest(
            UUID examinedByUserId,
            LocalDateTime examinedAt,
            String notes,
            List<PeriodontalMeasurementInput> measurements) {
    }
}