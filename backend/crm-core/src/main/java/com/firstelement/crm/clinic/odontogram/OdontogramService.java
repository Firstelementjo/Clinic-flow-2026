package com.firstelement.crm.clinic.odontogram;

import com.firstelement.crm.clinic.ClinicScope;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Recording and reading dental findings.
 *
 * <h2>Correction is an insert, never an update</h2>
 *
 * <p>This class has no method that changes an existing finding. {@link #correct} takes a finding
 * id and writes a <em>new</em> row pointing back at it through {@code supersededBy}. The original
 * row is never touched, so the chart shows both what was believed and what replaced it.
 *
 * <p>The tempting alternative Ã¢â‚¬â€ updating the row in place Ã¢â‚¬â€ would leave a record that says only
 * one thing ever happened. A clinical record exists precisely to settle whether it was believed
 * before it was corrected.
 */
@Service
public class OdontogramService {

    private final OdontogramRepository repository;

    public OdontogramService(OdontogramRepository repository) {
        this.repository = repository;
    }

    /** One patient's chart, newest first, each entry flagged with whether it still stands. */
    public List<OdontogramModels.ChartEntry> chart(ClinicScope scope, UUID patientId, int limit) {
        return repository.chart(scope, patientId, limit).stream()
                .map(f -> new OdontogramModels.ChartEntry(f, f.supersededBy() != null))
                .toList();
    }

    /** One tooth's history, oldest first. */
    public List<OdontogramModels.FindingRow> toothHistory(
            ClinicScope scope, UUID patientId, int toothFdi) {
        return repository.toothHistory(scope, patientId, toothFdi);
    }

    /** One finding. A finding in another tenant reads as absent, not as forbidden. */
    public OdontogramModels.FindingRow find(ClinicScope scope, UUID findingId) {
        return repository.find(scope, findingId);
    }

    /** Records an observation. */
    public OdontogramModels.FindingRow record(
            ClinicScope scope,
            UUID patientId,
            int toothFdi,
            String scopeKind,
            List<String> surfaces,
            String code,
            String attributesJson,
            String state,
            String source,
            UUID plannedProcedureId,
            UUID recordedBy,
            LocalDateTime recordedAt,
            String note) {

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("patientId", patientId);
        parameters.put("toothFdi", toothFdi);
        parameters.put("scope", scopeKind);
        parameters.put("surfaces", surfaces == null || surfaces.isEmpty() ? null : surfaces.toArray(new String[0]));
        parameters.put("code", code);
        parameters.put("attributes", attributesJson);
        parameters.put("state", state);
        parameters.put("source", source);
        parameters.put("plannedProcedureId", plannedProcedureId);
        parameters.put("recordedBy", recordedBy);
        parameters.put("recordedAt", recordedAt);
        parameters.put("note", note);
        parameters.put("supersededBy", null);

        // Copy-on-first-use. The clinic catalogue starts empty and is populated from the
        // global template the first time this clinic records anything, so recording a
        // finding never fails merely because nobody has seeded a vocabulary yet. After the
        // first call this is one indexed count and copies nothing.
        repository.copyTemplateInto(scope);

        UUID id = repository.insert(scope, parameters);
        return requireReadBack(scope, id);
    }

    /**
     * Records a correction.
     *
     * <p>The original must already be visible in this scope, or the correction would be a way to
     * point at a finding in another tenant. Reading it first is what makes that impossible.
     *
     * <p>The original is NOT modified. Both rows remain on the chart.
     */
    public OdontogramModels.FindingRow correct(
            ClinicScope scope,
            UUID correctsFindingId,
            String code,
            String attributesJson,
            String note) {

        OdontogramModels.FindingRow original = repository.find(scope, correctsFindingId);
        if (original == null) {
            // Absent and foreign are the same answer, so this cannot be used to probe another
            // tenant's finding ids.
            throw new IllegalArgumentException(
                    "No finding " + correctsFindingId + " is visible in this clinic.");
        }

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("patientId", original.patientId());
        parameters.put("toothFdi", original.toothFdi());
        parameters.put("scope", original.scope());
        parameters.put("surfaces", original.surfaces().isEmpty()
                ? null
                : original.surfaces().toArray(new String[0]));
        parameters.put("code", code);
        parameters.put("attributes", attributesJson);
        parameters.put("state", original.state());
        parameters.put("source", original.source());
        parameters.put("plannedProcedureId", original.plannedProcedureId());
        parameters.put("recordedBy", original.recordedBy());
        parameters.put("recordedAt", LocalDateTime.now());
        parameters.put("note", note);
        parameters.put("supersededBy", correctsFindingId);

        UUID id = repository.insert(scope, parameters);
        return requireReadBack(scope, id);
    }

    /** The clinic's finding vocabulary. */

    /**
     * Copies the published template into this clinic's own catalogue if it has none.
     *
     * <p>Exposed so an administration screen can seed a clinic explicitly rather than waiting
     * for a clinician's first finding. Safe to call repeatedly.
     *
     * @return how many terms were copied; 0 when the clinic already had a catalogue
     */
    public int ensureVocabulary(ClinicScope scope) {
        return repository.copyTemplateInto(scope);
    }

    /** The clinic's finding vocabulary. */
    public List<OdontogramModels.VocabularyTerm> vocabulary(ClinicScope scope) {
        return repository.vocabulary(scope, 200);
    }

    /**
     * Adds a term to the clinic's vocabulary.
     *
     * <p>{@code isClinicianReviewed} defaults to false. DEC-009 puts the vocabulary "pending
     * clinician review", so a term added through this path is marked provisional unless the
     * caller explicitly says a clinician confirmed it. Nothing invents that confirmation.
     */
    public OdontogramModels.VocabularyTerm addTerm(
            ClinicScope scope,
            String code,
            String category,
            String labelAr,
            String labelEn,
            boolean clinicianReviewed,
            String attributeSchemaJson) {

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("code", code);
        parameters.put("category", category);
        parameters.put("labelAr", labelAr);
        parameters.put("labelEn", labelEn);
        parameters.put("descriptionAr", null);
        parameters.put("descriptionEn", null);
        parameters.put("attributeSchema", attributeSchemaJson);
        parameters.put("isClinicianReviewed", clinicianReviewed);
        parameters.put("isActive", true);

        UUID id = repository.insertTerm(scope, parameters);
        return repository.vocabulary(scope, 200).stream()
                .filter(t -> t.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Vocabulary term " + id + " was written but could not be read back in scope."));
    }

    /** Creates a new periodontal exam session with optional 6-site measurements. */
    public OdontogramModels.PeriodontalExamDetail createPeriodontalExam(
            ClinicScope scope, UUID patientId, OdontogramModels.CreatePeriodontalExamRequest req) {
        UUID examId = repository.recordPeriodontalExam(
                scope, patientId, req.examinedByUserId(), req.examinedAt(), req.notes());
        if (req.measurements() != null && !req.measurements().isEmpty()) {
            repository.recordPeriodontalMeasurements(scope, examId, patientId, req.measurements());
        }
        return repository.getPeriodontalExamDetail(scope, examId);
    }

    /** Lists a patient's periodontal exam sessions. */
    public List<OdontogramModels.PeriodontalExamHeader> listPeriodontalExams(ClinicScope scope, UUID patientId) {
        return repository.listPeriodontalExams(scope, patientId);
    }

    /** Gets detail for a single periodontal exam session. */
    public OdontogramModels.PeriodontalExamDetail getPeriodontalExam(ClinicScope scope, UUID examId) {
        return repository.getPeriodontalExamDetail(scope, examId);
    }

    /** Gets the latest periodontal measurements per tooth for a patient. */
    public List<OdontogramModels.PeriodontalMeasurement> getLatestPeriodontalChart(ClinicScope scope, UUID patientId) {
        return repository.latestPeriodontalChart(scope, patientId);
    }

    private OdontogramModels.FindingRow requireReadBack(ClinicScope scope, UUID id) {
        OdontogramModels.FindingRow row = repository.find(scope, id);
        if (row == null) {
            throw new IllegalStateException(
                    "Finding " + id + " was written but could not be read back in scope.");
        }
        return row;
    }
}
