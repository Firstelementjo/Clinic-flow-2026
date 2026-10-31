package com.firstelement.crm.clinic.odontogram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.crm.CrmFixture;
import com.firstelement.crm.CrmIntegrationTestBase;
import com.firstelement.crm.clinic.ClinicScope;
import com.firstelement.crm.clinic.patient.PatientService;
import com.firstelement.security.query.TenantQueryGuard;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Odontogram recording, correction and isolation against real PostgreSQL.
 *
 * <p>The append-only property is asserted three ways on purpose: the service has no mutating
 * method, the database refuses a raw UPDATE, and a correction demonstrably leaves the original
 * row alone. Any one of those could hold while the others did not.
 */
@SpringBootTest(classes = com.firstelement.crm.CrmTestApplication.class)
class OdontogramServiceIntegrationTest extends CrmIntegrationTestBase {

    @Autowired
    private OdontogramRepository repository;

    @Autowired
    private OdontogramService service;

    @Autowired
    private PatientService patients;

    @Autowired
    private CrmFixture fixture;

    @Autowired
    private TenantQueryGuard guard;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;
    /**
     * The base class documents that the database is reset per test but does not do it;
     * each concrete class calls CrmFixture.reset() from its own @BeforeEach. Without this,
     * patients and vocabulary accumulate across tests in one class, and every test that adds
     * a term collides with the previous one's code.
     */
    @BeforeEach
    void resetDatabase() {
        fixture.reset();
    }


    // ------------------------------------------------------------------
    // append-only, in three independent layers
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the service exposes no way to change or remove an existing finding")
    void thereIsNoMutatingOperation() {
        List<String> forbidden = List.of("update", "delete", "remove", "amend", "edit", "patch");

        List<String> offenders = Arrays.stream(OdontogramService.class.getMethods())
                .map(Method::getName)
                .filter(name -> forbidden.stream()
                        .anyMatch(bad -> name.toLowerCase(java.util.Locale.ROOT)
                                .contains(bad)))
                .toList();

        assertThat(offenders)
                .as("a clinical record that can be edited in place has no defensible history. "
                        + "The capability is absent rather than restricted.")
                .isEmpty();

        List<String> repoOffenders = Arrays.stream(OdontogramRepository.class.getMethods())
                .map(Method::getName)
                .filter(name -> forbidden.contains(name.toLowerCase(java.util.Locale.ROOT)))
                .toList();
        assertThat(repoOffenders)
                .as("the repository is the layer a careless caller reaches for, so it carries "
                        + "the same absence")
                .isEmpty();
    }

    @Test
    @DisplayName("the database itself refuses to rewrite a finding, even from raw SQL")
    void theDatabaseRefusesToRewriteAFinding() {
        ClinicScope scope = tenantAScope();
        var term = addTerm(scope, "CARIES-ICDAS-3", "CARIES");
        var patient = newPatient(scope, "P-900");
        var finding = record(scope, patient, 16, term.code());

        assertThatThrownBy(() -> jdbc.update(
                "update \"patient_odontogram_findings\" set \"note\" = 'quietly changed'"
                        + " where \"id\" = :id",
                new MapSqlParameterSource("id", finding.id())))
                .as("firstelement_append_only is the control, not the service layer")
                .isInstanceOf(DataIntegrityViolationException.class);

        // Assert on state AFTER the attempt, not on the message.
        assertThat(jdbc.queryForObject(
                "select \"note\" from \"patient_odontogram_findings\" where \"id\" = :id",
                new MapSqlParameterSource("id", finding.id()), String.class))
                .as("the row is byte-for-byte what was recorded")
                .isEqualTo(finding.note());
    }

    @Test
    @DisplayName("a correction adds a row and leaves the original exactly as recorded")
    void aCorrectionAddsARowAndChangesNothing() {
        ClinicScope scope = tenantAScope();
        var term = addTerm(scope, "CARIES-ICDAS-3", "CARIES");
        var patient = newPatient(scope, "P-910");
        var original = record(scope, patient, 16, term.code());

        var correction = service.correct(scope, original.id(), term.code(), null,
                "re-read the radiograph: ICDAS 4");

        assertThat(correction.id())
                .as("a correction is a NEW row, never a rewrite")
                .isNotEqualTo(original.id());
        assertThat(correction.supersededBy()).isEqualTo(original.id());

        // The original still stands, unchanged, and is still readable.
        var reread = service.find(scope, original.id());
        assertThat(reread).isNotNull();
        assertThat(reread.note()).isEqualTo(original.note());
        assertThat(reread.supersededBy())
                .as("the original is NOT marked; supersession is recorded on the correction")
                .isNull();

        // Both are on the chart, and the chart says which is which.
        var chart = service.chart(scope, patient, 50);
        assertThat(chart).hasSize(2);
        assertThat(chart).filteredOn(e -> e.finding().id().equals(original.id()))
                .allMatch(e -> !e.superseded());
        assertThat(chart).filteredOn(e -> e.finding().id().equals(correction.id()))
                .allMatch(OdontogramModels.ChartEntry::superseded);
    }

    @Test
    @DisplayName("correcting a finding in another clinic is refused, and reads as absent")
    void aCorrectionCannotTargetAnotherTenant() {
        ClinicScope mine = tenantAScope();
        var term = addTerm(mine, "CARIES-ICDAS-3", "CARIES");
        var patient = newPatient(mine, "P-920");
        var finding = record(mine, patient, 16, term.code());

        ClinicScope theirs = tenantBScope();
        assertThatThrownBy(() -> service.correct(theirs, finding.id(), term.code(), null, "probe"))
                .as("reading the original first is what makes this impossible, and the refusal "
                        + "is the same one a nonexistent id would give")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("visible in this clinic");

        // Assert on state AFTER the attempt, not on the message: a refused correction leaves the
        // chart exactly as it was.
        assertThat(service.chart(theirs, finding.patientId(), 50))
                .as("nothing was written into the other clinic")
                .isEmpty();
        assertThat(service.chart(mine, patient, 50))
                .as("and the original is untouched, still uncorrected")
                .hasSize(1);
        assertThat(service.find(mine, finding.id()).supersededBy())
                .as("the original was not quietly marked as superseded")
                .isNull();
    }

    // ------------------------------------------------------------------
    // FDI, transcribed from DEC-009 rather than interpreted
    // ------------------------------------------------------------------

    @Test
    @DisplayName("all 52 canonical FDI teeth are accepted and the gaps between them are not")
    void onlyCanonicalTeethAreAccepted() {
        ClinicScope scope = tenantAScope();
        var term = addTerm(scope, "TOOTH-PRESENT", "TOOTH_STATUS");
        var patient = newPatient(scope, "P-930");

        List<Integer> canonical = new java.util.ArrayList<>();
        for (int base : new int[] {11, 21, 31, 41}) {
            for (int i = 0; i < 8; i++) {
                canonical.add(base + i);
            }
        }
        for (int base : new int[] {51, 61, 71, 81}) {
            for (int i = 0; i < 5; i++) {
                canonical.add(base + i);
            }
        }
        assertThat(canonical)
                .as("32 permanent + 20 deciduous")
                .hasSize(52);

        for (int tooth : canonical) {
            assertThat(record(scope, patient, tooth, term.code()).toothFdi())
                    .as("FDI %d is in the DEC-009 sets", tooth)
                    .isEqualTo(tooth);
        }

        // The gaps BETWEEN quadrants, and numbers that are not teeth at all. 39, 40 and 50 sit
        // between the adult quadrants; 56, 66, 76 and 86 sit after each deciduous run; 9 is not a
        // tooth. Each is a typo, not a judgement, which is why the schema makes it a CHECK.
        //
        // Note 46 IS a tooth (upper right first molar, 41-48). An earlier version of this test
        // listed it as invalid and the database correctly accepted it.
        for (int wrong : new int[] {0, 9, 19, 20, 29, 39, 40, 49, 50, 56, 66, 76, 86, 99}) {
            assertThatThrownBy(() -> record(scope, patient, wrong, term.code()))
                    .as("FDI %d is not a tooth", wrong)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        assertThat(service.toothHistory(scope, patient, 11))
                .as("only the accepted teeth are stored, and 11 has exactly one")
                .hasSize(1);
        assertThat(service.toothHistory(scope, patient, 46))
                .as("46 is a real permanent tooth and must have been accepted")
                .hasSize(1);
    }

    // ------------------------------------------------------------------
    // vocabulary
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the vocabulary starts empty, because DEC-009 leaves it for a clinician")
    void theVocabularyStartsEmpty() {
        ClinicScope scope = tenantAScope();
        assertThat(service.vocabulary(scope))
                .as("V20261014000000 asserts this at migration time; nothing seeds it after")
                .isEmpty();
    }

    @Test
    @DisplayName("a finding under a code the clinic has not defined is refused")
    void aFindingNeedsATermTheClinicOwns() {
        ClinicScope scope = tenantAScope();
        var patient = newPatient(scope, "P-940");

        assertThatThrownBy(() -> record(scope, patient, 16, "NOT-A-REAL-CODE"))
                .as("the vocabulary is not documentation, it is a foreign key")
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(service.chart(scope, patient, 50))
                .as("nothing was written")
                .isEmpty();
    }

    @Test
    @DisplayName("the first finding seeds the clinic's catalogue from the published template")
    void theFirstFindingSeedsTheCatalogue() {
        ClinicScope scope = tenantAScope();
        var patient = newPatient(scope, "P-965");

        assertThat(service.vocabulary(scope))
                .as("a clinic that has never recorded anything has no catalogue of its own")
                .isEmpty();

        assertThat(service.record(scope, patient, 16, "TOOTH", List.of(),
                "CARIES-ICDAS-3", null, "EXISTING", "CLINICAL", null, null,
                LocalDateTime.now(), "from the published template"))
                .as("recording copies the template first, so a clinician never has to seed a "
                        + "vocabulary before they can diagnose")
                .isNotNull();

        assertThat(service.vocabulary(scope))
                .as("and the catalogue holds the published term set")
                .hasSize(37)
                .allSatisfy(term -> assertThat(term.labelEn()).isNotBlank())
                .anySatisfy(term -> assertThat(term.code()).isEqualTo("CARIES-ICDAS-3"));

        assertThat(service.ensureVocabulary(scope))
                .as("calling it again copies nothing")
                .isZero();
        assertThat(service.vocabulary(scope)).hasSize(37);
    }

    @Test
    @DisplayName("a clinic's OWN term cannot be used by another clinic")
    void aClinicsOwnTermCannotBeUsedByAnotherClinic() {
        ClinicScope mine = tenantAScope();
        // A term this clinic invented, which the published template does not contain.
        var myTerm = service.addTerm(mine, "LOCAL-BRIDGE-REPAIR", "PROSTHETIC_ENDO",
                "Bridge repair (local protocol)", "Bridge repair (local protocol)", false, null);

        ClinicScope theirs = tenantBScope();
        var theirPatient = newPatient(theirs, "P-966");
        assertThatThrownBy(() -> service.record(theirs, theirPatient, 16, "TOOTH", List.of(),
                myTerm.code(), null, "EXISTING", "CLINICAL", null, null,
                LocalDateTime.now(), null))
                .as("the composite FK is (code, projectId, organizationId), so a clinic-specific "
                        + "term cannot be written under another clinic even though the code is "
                        + "well formed. Published template terms DO exist in both clinics by "
                        + "design - they are the same standard - which is why this test uses a "
                        + "clinic's own term rather than a standard one.")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a term carries both an Arabic and an English label, and neither may be blank")
    void aTermNeedsBothLabels() {
        ClinicScope scope = tenantAScope();
        assertThatThrownBy(() -> service.addTerm(scope, "X-1", "CARIES", "  ", "Caries", false, null))
                .as("an Arabic label with no English counterpart leaves half the clinic unable "
                        + "to read the chart, and vice versa")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a term added without clinician review is marked provisional")
    void anUnreviewedTermIsMarkedProvisional() {
        ClinicScope scope = tenantAScope();
        var term = service.addTerm(scope, "CARIES-ICDAS-3", "CARIES",
                "تسوس من الدرجة الثالثة", "Caries ICDAS 3", false, null);

        assertThat(term.clinicianReviewed())
                .as("DEC-009 leaves the vocabulary pending clinician review, so nothing here "
                        + "may assert that a clinician confirmed it")
                .isFalse();
    }

    // ------------------------------------------------------------------
    // isolation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a finding is invisible to another organization")
    void findingsAreTenantIsolated() {
        ClinicScope mine = tenantAScope();
        var term = addTerm(mine, "CARIES-ICDAS-3", "CARIES");
        var patient = newPatient(mine, "P-960");
        var finding = record(mine, patient, 16, term.code());

        ClinicScope theirs = tenantBScope();
        assertThat(service.find(theirs, finding.id()))
                .as("absent, not forbidden, so an id cannot be probed")
                .isNull();
        assertThat(service.chart(theirs, patient, 50)).isEmpty();
        assertThat(service.toothHistory(theirs, patient, 16)).isEmpty();
    }

    // ------------------------------------------------------------------
    // Slice S4: Tooth notation conversion (FDI <-> Universal <-> Palmer)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("tooth notation round-trip converts adult and deciduous sets")
    void toothNotationRoundTrip() {
        // Adult maxillary right central incisor: FDI 11 -> Universal 8 -> Palmer 1 upper-right
        assertThat(ToothNotationConverter.fdiToUniversal(11)).isEqualTo("8");
        assertThat(ToothNotationConverter.universalToFdi("8")).isEqualTo(11);
        ToothNotationConverter.NotationDisplay display11 = ToothNotationConverter.toDisplay(11);
        assertThat(display11.universal()).isEqualTo("8");
        assertThat(display11.palmerQuadrant()).isEqualTo("UR");
        assertThat(display11.palmerIndex()).isEqualTo("1");

        // Adult mandibular left third molar: FDI 38 -> Universal 17 -> Palmer 8 lower-left
        assertThat(ToothNotationConverter.fdiToUniversal(38)).isEqualTo("17");
        assertThat(ToothNotationConverter.universalToFdi("17")).isEqualTo(38);
        ToothNotationConverter.NotationDisplay display38 = ToothNotationConverter.toDisplay(38);
        assertThat(display38.universal()).isEqualTo("17");
        assertThat(display38.palmerQuadrant()).isEqualTo("LL");
        assertThat(display38.palmerIndex()).isEqualTo("8");

        // Deciduous maxillary right second molar: FDI 55 -> Universal A -> Palmer E upper-right
        assertThat(ToothNotationConverter.fdiToUniversal(55)).isEqualTo("A");
        assertThat(ToothNotationConverter.universalToFdi("A")).isEqualTo(55);
        ToothNotationConverter.NotationDisplay display55 = ToothNotationConverter.toDisplay(55);
        assertThat(display55.universal()).isEqualTo("A");
        assertThat(display55.palmerQuadrant()).isEqualTo("UR");
        assertThat(display55.palmerIndex()).isEqualTo("E");

        // Deciduous mandibular right central incisor: FDI 81 -> Universal P -> Palmer A lower-right
        assertThat(ToothNotationConverter.fdiToUniversal(81)).isEqualTo("P");
        assertThat(ToothNotationConverter.universalToFdi("P")).isEqualTo(81);
        ToothNotationConverter.NotationDisplay display81 = ToothNotationConverter.toDisplay(81);
        assertThat(display81.universal()).isEqualTo("P");
        assertThat(display81.palmerQuadrant()).isEqualTo("LR");
        assertThat(display81.palmerIndex()).isEqualTo("A");

        // Invalid FDI tooth numbers throw IllegalArgumentException
        assertThatThrownBy(() -> ToothNotationConverter.validateFdi(99))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ToothNotationConverter.validateFdi(10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ToothNotationConverter.validateFdi(49))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------
    // Slice S4: Derived CAL computation (CAL = PD + GM)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("derived CAL computes PD + GM consistently across positive recession and negative hyperplasia")
    void derivedCalComputation() {
        // Normal pocket with 2mm recession: PD=4, GM=2 => CAL = 6mm
        assertThat(ToothNotationConverter.deriveCal(4, 2)).isEqualTo(6);

        // Gingival enlargement/overgrowth: PD=5, GM=-2 => CAL = 3mm
        assertThat(ToothNotationConverter.deriveCal(5, -2)).isEqualTo(3);

        // Normal margin at CEJ: PD=3, GM=0 => CAL = 3mm
        assertThat(ToothNotationConverter.deriveCal(3, 0)).isEqualTo(3);

        // Margin null is treated as 0: PD=4, GM=null => CAL = 4mm
        assertThat(ToothNotationConverter.deriveCal(4, null)).isEqualTo(4);

        // PD null means no measurement recorded => CAL = null
        assertThat(ToothNotationConverter.deriveCal(null, 2)).isNull();
        assertThat(ToothNotationConverter.deriveCal(null, null)).isNull();
    }

    // ------------------------------------------------------------------
    // Slice S4: 6-site periodontal probing exam recording and readback
    // ------------------------------------------------------------------

    @Test
    @DisplayName("records a periodontal examination session with 6-site measurements and derived CAL")
    void recordAndRetrievePeriodontalExam() {
        ClinicScope scope = tenantAScope();
        UUID patientId = newPatient(scope, "P-PERIO-1");
        UUID examinerId = fixture.adminAContext().userId();

        var m16 = new OdontogramModels.PeriodontalMeasurementInput(
                16,
                4, 3, 5, 3, 2, 4,       // PD: MB, B, DB, ML, L, DL
                1, 0, 2, 0, 0, 1,       // GM: MB, B, DB, ML, L, DL
                true, false, true, false, false, true, // BOP
                false, false, false, false, false, false, // Suppuration
                true, false, true, false, false, false, // Plaque
                1, 0                    // Furcation class 1, mobility 0
        );

        var m21 = new OdontogramModels.PeriodontalMeasurementInput(
                21,
                2, 2, 2, 2, 2, 2,       // PD: 2mm healthy
                0, 0, 0, 0, 0, 0,       // GM: 0mm
                false, false, false, false, false, false, // BOP
                false, false, false, false, false, false,
                false, false, false, false, false, false,
                null, 0
        );

        var request = new OdontogramModels.CreatePeriodontalExamRequest(
                examinerId,
                LocalDateTime.now().minusHours(1),
                "Baseline periodontal exam - moderate periodontitis on molar 16",
                List.of(m16, m21)
        );

        OdontogramModels.PeriodontalExamDetail created = service.createPeriodontalExam(scope, patientId, request);

        assertThat(created).isNotNull();
        assertThat(created.exam().patientId()).isEqualTo(patientId);
        assertThat(created.exam().notes()).contains("Baseline periodontal exam");
        assertThat(created.exam().measurementCount()).isEqualTo(2);
        assertThat(created.measurements()).hasSize(2);

        // Assert CAL derived correctly: CAL = PD + GM
        var tooth16Measurement = created.measurements().stream()
                .filter(m -> m.toothFdi() == 16)
                .findFirst()
                .orElseThrow();

        assertThat(tooth16Measurement.calMb()).isEqualTo(5); // 4 + 1
        assertThat(tooth16Measurement.calB()).isEqualTo(3);  // 3 + 0
        assertThat(tooth16Measurement.calDb()).isEqualTo(7); // 5 + 2
        assertThat(tooth16Measurement.calMl()).isEqualTo(3); // 3 + 0
        assertThat(tooth16Measurement.calL()).isEqualTo(2);  // 2 + 0
        assertThat(tooth16Measurement.calDl()).isEqualTo(5); // 4 + 1
        assertThat(tooth16Measurement.bopMb()).isTrue();
        assertThat(tooth16Measurement.bopDb()).isTrue();
        assertThat(tooth16Measurement.furcationClass()).isEqualTo(1);

        // Read back list of exams
        List<OdontogramModels.PeriodontalExamHeader> exams = service.listPeriodontalExams(scope, patientId);
        assertThat(exams).hasSize(1);
        assertThat(exams.get(0).id()).isEqualTo(created.exam().id());

        // Read back detail
        OdontogramModels.PeriodontalExamDetail readBack = service.getPeriodontalExam(scope, created.exam().id());
        assertThat(readBack).isNotNull();
        assertThat(readBack.measurements()).hasSize(2);
    }

    @Test
    @DisplayName("latestPeriodontalChart returns the newest measurement per tooth across exams")
    void latestPeriodontalChartAcrossExams() {
        ClinicScope scope = tenantAScope();
        UUID patientId = newPatient(scope, "P-PERIO-2");
        UUID examinerId = fixture.adminAContext().userId();

        // Exam 1: 30 days ago, tooth 11 measured PD=6
        var oldTooth11 = new OdontogramModels.PeriodontalMeasurementInput(
                11,
                6, 5, 6, 4, 3, 4,
                2, 2, 2, 1, 1, 1,
                true, true, true, false, false, false,
                false, false, false, false, false, false,
                true, true, true, false, false, false,
                null, 1
        );
        service.createPeriodontalExam(scope, patientId, new OdontogramModels.CreatePeriodontalExamRequest(
                examinerId, LocalDateTime.now().minusDays(30), "Initial exam", List.of(oldTooth11)));

        // Exam 2: Today, tooth 11 re-evaluated at PD=3, and tooth 21 also measured
        var newTooth11 = new OdontogramModels.PeriodontalMeasurementInput(
                11,
                3, 3, 3, 3, 2, 3,
                1, 1, 1, 0, 0, 0,
                false, false, false, false, false, false,
                false, false, false, false, false, false,
                false, false, false, false, false, false,
                null, 0
        );
        var tooth21 = new OdontogramModels.PeriodontalMeasurementInput(
                21,
                2, 2, 2, 2, 2, 2,
                0, 0, 0, 0, 0, 0,
                false, false, false, false, false, false,
                false, false, false, false, false, false,
                false, false, false, false, false, false,
                null, 0
        );
        service.createPeriodontalExam(scope, patientId, new OdontogramModels.CreatePeriodontalExamRequest(
                examinerId, LocalDateTime.now(), "Post-SRP follow-up", List.of(newTooth11, tooth21)));

        List<OdontogramModels.PeriodontalMeasurement> latest = service.getLatestPeriodontalChart(scope, patientId);
        assertThat(latest).hasSize(2);

        var latest11 = latest.stream().filter(m -> m.toothFdi() == 11).findFirst().orElseThrow();
        assertThat(latest11.probingDepthMb()).isEqualTo(3); // newer measurement from Exam 2
        assertThat(latest11.bopMb()).isFalse();

        var latest21 = latest.stream().filter(m -> m.toothFdi() == 21).findFirst().orElseThrow();
        assertThat(latest21.probingDepthMb()).isEqualTo(2);
    }

    @Test
    @DisplayName("periodontal exams and measurements are tenant-isolated")
    void periodontalExamsAreTenantIsolated() {
        ClinicScope mine = tenantAScope();
        UUID patientId = newPatient(mine, "P-PERIO-ISO");
        var m = new OdontogramModels.PeriodontalMeasurementInput(
                11, 3, 3, 3, 3, 3, 3, 0, 0, 0, 0, 0, 0,
                false, false, false, false, false, false,
                false, false, false, false, false, false,
                false, false, false, false, false, false, null, null);

        OdontogramModels.PeriodontalExamDetail created = service.createPeriodontalExam(mine, patientId,
                new OdontogramModels.CreatePeriodontalExamRequest(
                        fixture.adminAContext().userId(), LocalDateTime.now(), "Private exam", List.of(m)));

        ClinicScope theirs = tenantBScope();
        assertThat(service.listPeriodontalExams(theirs, patientId)).isEmpty();
        assertThat(service.getPeriodontalExam(theirs, created.exam().id())).isNull();
        assertThat(service.getLatestPeriodontalChart(theirs, patientId)).isEmpty();
    }

    @Test
    @DisplayName("recording without naming a clinic is refused")
    void recordingRequiresAProjectScope() {
        ClinicScope ambiguous = ClinicScope.forCaller(fixture.adminAContext());
        var term = addTerm(tenantAScope(), "CARIES-ICDAS-3", "CARIES");
        var patient = newPatient(tenantAScope(), "P-970");

        assertThatThrownBy(() -> service.record(ambiguous, patient, 16, "TOOTH",
                List.of(), term.code(), null, "EXISTING", "CLINICAL", null, null,
                LocalDateTime.now(), null))
                .as("a finding belongs to exactly one clinic")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("one clinic");
    }

    @Test
    @DisplayName("the database refuses an invalid FDI tooth number in periodontal measurements")
    void databaseRefusesInvalidToothFdiInMeasurements() {
        ClinicScope scope = tenantAScope();
        UUID patientId = newPatient(scope, "P-PERIO-FDI");
        var invalidTooth = new OdontogramModels.PeriodontalMeasurementInput(
                99, 3, 3, 3, 3, 3, 3, 0, 0, 0, 0, 0, 0,
                false, false, false, false, false, false,
                false, false, false, false, false, false,
                false, false, false, false, false, false, null, null);

        assertThatThrownBy(() -> service.createPeriodontalExam(scope, patientId,
                new OdontogramModels.CreatePeriodontalExamRequest(
                        fixture.adminAContext().userId(), LocalDateTime.now(), "Invalid FDI", List.of(invalidTooth))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ClinicScope tenantAScope() {
        return ClinicScope.forRequestedProject(guard, fixture.adminAContext(), fixture.projectA1);
    }

    private ClinicScope tenantBScope() {
        return ClinicScope.forRequestedProject(guard, fixture.adminBContext(), fixture.projectB1);
    }

    private OdontogramModels.VocabularyTerm addTerm(ClinicScope scope, String code, String category) {
        return service.addTerm(scope, code, category, "Unused in this test", "Unused in this test",
                false, null);
    }

    private UUID newPatient(ClinicScope scope, String patientNo) {
        return patients.create(scope, patientNo, "Test Patient", "Test", "Patient", null,
                "+9665" + Math.abs(patientNo.hashCode() % 100000000), null, "ar", null).id();
    }

    private OdontogramModels.FindingRow record(
            ClinicScope scope, UUID patientId, int toothFdi, String code) {
        return service.record(scope, patientId, toothFdi, "TOOTH", List.of(), code, null,
                "EXISTING", "CLINICAL", null, null, LocalDateTime.now(), "as recorded");
    }
}
