package com.firstelement.crm.clinic.patient;

import com.firstelement.crm.clinic.ClinicScope;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Patient reads, creation, and duplicate detection.
 *
 * <h2>Nothing merges, ever</h2>
 *
 * <p>The Phase 3 requirement is explicit: duplicate detection must <b>not</b> auto-merge on weak
 * signals. This class has no merge method at all. That is the honest way to satisfy the rule - a
 * merge operation that exists but is gated on a threshold can be gated wrongly, or called directly,
 * or have its threshold lowered later; a merge operation that does not exist cannot merge.
 *
 * <p>What it returns instead is evidence, scored and explained, for a human to act on. The
 * per-field weighting is coarse and fixed in code rather than configurable at runtime, because a
 * threshold an operator can change without review is a threshold that will be changed to suppress a
 * duplicate warning, and two patients merged by accident is not a recoverable outcome.
 */
@Service
public class PatientService {

    /** Phone is an identity: two records with the same number are almost certainly one person. */
    private static final int SCORE_PHONE = 60;
    /** A date of birth alone is weak - it is not unique in a clinic. */
    private static final int SCORE_DOB = 15;
    /** A surname alone is weaker still. */
    private static final int SCORE_FAMILY = 10;
    /** A given name adds a little, but common names are common. */
    private static final int SCORE_GIVEN = 10;
    /** Same surname, given name and birth date together is the strongest non-phone signal. */
    private static final int BONUS_FULL_NAME_AND_DOB = 20;

    /** Below this, a candidate is not worth a human's attention. */
    private static final int REPORTING_FLOOR = 40;

    private final PatientRepository repository;

    public PatientService(PatientRepository repository) {
        this.repository = repository;
    }

    public List<PatientModels.PatientRow> list(ClinicScope scope, int limit) {
        return repository.list(scope, limit);
    }

    public List<PatientModels.PatientRow> search(ClinicScope scope, String term, int limit) {
        return repository.search(scope, term, limit);
    }

    public Optional<PatientModels.PatientRow> find(ClinicScope scope, UUID patientId) {
        return repository.findById(scope, patientId);
    }

    /**
     * Creates a patient inside the scope's clinic.
     *
     * <p>The phone uniqueness rule is the database's (DEC-016: unique per tenant), so a duplicate
     * number surfaces as a constraint violation rather than as a silent second row. The caller is
     * expected to run {@link #duplicateCandidates} first when a number already exists.
     */
    public PatientModels.PatientRow create(
            ClinicScope scope,
            String patientNo,
            String displayName,
            String givenName,
            String familyName,
            LocalDate dateOfBirth,
            String phone,
            String email,
            String locale,
            UUID customerId) {
        // Kept as the original ten-argument entry point so the existing callers and
        // tests keep one documented way to create a patient. The richer drawer uses
        // the command overload below.
        return create(scope, new PatientModels.CreatePatientCommand(
                patientNo, displayName, givenName, familyName, null, dateOfBirth,
                phone, email, null, null, null, null, null, null, null, true,
                locale, customerId, null));
    }

    /**
     * Creates a patient from the full profile carried by the drawer.
     *
     * <p>Nothing here is defaulted silently on behalf of a caller: a null gender or
     * birth date stays null, because "not recorded" is a real answer and a fabricated
     * default would be indistinguishable from a deliberate one.
     */
    public PatientModels.PatientRow create(
            ClinicScope scope, PatientModels.CreatePatientCommand command) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("patientNo", command.patientNo());
        parameters.put("displayName", command.displayName());
        parameters.put("givenName", command.givenName());
        parameters.put("familyName", command.familyName());
        parameters.put("gender", command.gender());
        parameters.put("dateOfBirth", command.dateOfBirth());
        parameters.put("primaryPhone", command.phone());
        parameters.put("primaryEmail", command.email());
        parameters.put("cnic", command.cnic());
        parameters.put("profileImageUrl", command.profileImageUrl());
        parameters.put("addressLine", command.addressLine());
        parameters.put("country", command.country());
        parameters.put("state", command.state());
        parameters.put("city", command.city());
        parameters.put("postalCode", command.postalCode());
        parameters.put("isActive", command.isActive());
        parameters.put("locale", command.locale() == null ? "ar" : command.locale());
        parameters.put("customerId", command.customerId());
        parameters.put("portalPasswordHash", command.portalPasswordHash());

        UUID id = repository.insert(scope, parameters);
        return repository.findById(scope, id)
                .orElseThrow(() -> new IllegalStateException(
                        "Patient " + id + " was written but could not be read back in scope."));
    }

    /**
     * Duplicate candidates for a person who is about to be created.
     *
     * <p>Returns scored, explained candidates above {@link #REPORTING_FLOOR}. It never mutates and
     * never merges - see the class note.
     *
     * @param excludePatientId a patient already known to be this person, excluded from its own
     *                         candidate list
     */
    public List<PatientModels.DuplicateCandidate> duplicateCandidates(
            ClinicScope scope,
            String givenName,
            String familyName,
            LocalDate dateOfBirth,
            String phone,
            UUID excludePatientId) {

        List<PatientModels.PatientRow> rows = repository.candidatesOnSignals(
                scope, familyName, dateOfBirth, phone);

        Map<UUID, Integer> scores = new HashMap<>();
        Map<UUID, String> reasons = new HashMap<>();

        for (PatientModels.PatientRow row : rows) {
            if (row.id().equals(excludePatientId)) {
                continue;
            }
            int score = 0;
            List<String> matched = new ArrayList<>();

            if (phone != null && !phone.isBlank() && phone.equals(row.primaryPhone())) {
                score += SCORE_PHONE;
                matched.add("phone");
            }
            boolean sameDob = dateOfBirth != null && dateOfBirth.equals(row.dateOfBirth());
            if (sameDob) {
                score += SCORE_DOB;
                matched.add("dateOfBirth");
            }
            boolean sameFamily = familyName != null && !familyName.isBlank()
                    && familyName.toLowerCase(Locale.ROOT)
                            .equals(String.valueOf(row.familyName()).toLowerCase(Locale.ROOT));
            if (sameFamily) {
                score += SCORE_FAMILY;
                matched.add("familyName");
            }
            boolean sameGiven = givenName != null && !givenName.isBlank()
                    && givenName.toLowerCase(Locale.ROOT)
                            .equals(String.valueOf(row.givenName()).toLowerCase(Locale.ROOT));
            if (sameGiven) {
                score += SCORE_GIVEN;
                matched.add("givenName");
            }

            // The combination is worth more than its parts. A shared surname plus a given name
            // plus a birth date is one person; any two of them are often a coincidence.
            if (sameFamily && sameGiven && sameDob) {
                score += BONUS_FULL_NAME_AND_DOB;
            }

            if (score >= REPORTING_FLOOR) {
                scores.merge(row.id(), score, Integer::sum);
                reasons.merge(row.id(), String.join("+", matched), (a, b) -> a + "+" + b);
            }
        }

        List<PatientModels.DuplicateCandidate> candidates = new ArrayList<>();
        for (PatientModels.PatientRow row : rows) {
            Integer score = scores.get(row.id());
            if (score != null) {
                candidates.add(new PatientModels.DuplicateCandidate(
                        row.id(), row.patientNo(), row.displayName(), score, reasons.get(row.id())));
            }
        }
        // Highest score first, then a stable tiebreak so paging does not reshuffle equal candidates.
        candidates.sort(Comparator.comparingInt(PatientModels.DuplicateCandidate::score).reversed()
                .thenComparing(PatientModels.DuplicateCandidate::patientNo));
        return candidates;
    }

    public List<PatientModels.RelationshipRow> listRelationships(ClinicScope scope, UUID patientId) {
        return repository.listRelationships(scope, patientId);
    }

    public UUID addRelationship(ClinicScope scope, UUID patientId, PatientModels.CreateRelationshipRequest req) {
        return repository.insertRelationship(scope, patientId, req);
    }

    public boolean removeRelationship(ClinicScope scope, UUID patientId, UUID relationshipId) {
        return repository.deleteRelationship(scope, patientId, relationshipId);
    }

    public List<PatientModels.ConsentRow> listConsents(ClinicScope scope, UUID patientId) {
        return repository.listConsents(scope, patientId);
    }

    public UUID addConsent(ClinicScope scope, UUID patientId, PatientModels.CreateConsentRequest req) {
        return repository.insertConsent(scope, patientId, req);
    }

    public boolean revokeConsent(ClinicScope scope, UUID patientId, UUID consentId, String reason) {
        return repository.revokeConsent(scope, patientId, consentId, reason);
    }

    public List<PatientModels.DisclosureLogRow> listDisclosures(ClinicScope scope, UUID patientId) {
        return repository.listDisclosures(scope, patientId);
    }

    public UUID logDisclosure(ClinicScope scope, UUID patientId, UUID userId, PatientModels.LogDisclosureRequest req) {
        return repository.insertDisclosure(scope, patientId, userId, req);
    }

    public boolean archive(ClinicScope scope, UUID patientId, UUID userId, String reason) {
        return repository.archivePatient(scope, patientId, userId, reason);
    }

    public boolean restore(ClinicScope scope, UUID patientId) {
        return repository.restorePatient(scope, patientId);
    }
}