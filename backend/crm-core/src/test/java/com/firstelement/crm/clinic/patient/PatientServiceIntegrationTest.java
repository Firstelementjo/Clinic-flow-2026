package com.firstelement.crm.clinic.patient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.crm.CrmIntegrationTestBase;
import com.firstelement.crm.clinic.ClinicScope;
import com.firstelement.security.TenantContext;
import com.firstelement.security.query.TenantQueryGuard;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Patient reads, creation and duplicate detection against real PostgreSQL.
 *
 * <p>The first test is structural and is the one that matters: {@code PatientService} has no merge
 * operation of any kind. The Phase 3 rule is "no auto-merge on weak signals", and the strongest way
 * to satisfy it is that the capability does not exist to be misused.
 */
@SpringBootTest(classes = com.firstelement.crm.CrmTestApplication.class)
class PatientServiceIntegrationTest extends CrmIntegrationTestBase {

    @Autowired
    private PatientRepository repository;

    @Autowired
    private PatientService service;

    @Autowired
    private TenantQueryGuard guard;

    @Autowired
    private org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;

    // ------------------------------------------------------------------
    // the structural guarantee
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the service has no merge, link or deduplicate operation to call")
    void thereIsNoMergeOperation() {
        List<String> forbidden = List.of("merge", "link", "attach", "deduplicate", "dedupe",
                "autoMerge", "consolidate");

        List<String> offenders = Arrays.stream(PatientService.class.getMethods())
                .map(Method::getName)
                .filter(name -> forbidden.stream()
                        .anyMatch(bad -> name.toLowerCase(java.util.Locale.ROOT).contains(
                                bad.toLowerCase(java.util.Locale.ROOT))))
                .toList();

        assertThat(offenders)
                .as("Phase 3 forbids auto-merge on weak signals. A merge method that exists but is "
                        + "gated on a threshold can be gated wrongly, called directly, or have its "
                        + "threshold lowered later. Two patients merged by accident is not "
                        + "recoverable. The capability is absent instead.")
                .isEmpty();
    }

    // ------------------------------------------------------------------
    // duplicates
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a shared surname alone is not reported; a matching phone is")
    void duplicateSignalsAreWeighted() {
        ClinicScope scope = tenantAScope();

        service.create(scope, "P-001", "Sara Ahmed", "Sara", "Ahmed",
                LocalDate.of(1990, 1, 1), "+966500000001", "sara@example.test", "ar", null);
        service.create(scope, "P-002", "Sara Ahmed", "Sara", "Ahmed",
                LocalDate.of(1990, 1, 1), "+966500000002", "sara2@example.test", "ar", null);
        service.create(scope, "P-003", "Omar Ahmed", "Omar", "Ahmed",
                LocalDate.of(1975, 6, 6), "+966500000003", "omar@example.test", "ar", null);

        // Same surname and given name AND birth date as P-001: 10 + 10 + 15 + 20 bonus = 55.
        var onName = service.duplicateCandidates(scope, "Sara", "Ahmed",
                LocalDate.of(1990, 1, 1), null, null);
        assertThat(onName)
                .as("identical name and birth date is a real candidate")
                .isNotEmpty();
        assertThat(onName).anySatisfy(c -> assertThat(c.matchedOn()).contains("familyName"));

        // A matching phone on an otherwise unrelated record: 60, above the floor.
        var onPhone = service.duplicateCandidates(scope, null, null, null, "+966500000003", null);
        assertThat(onPhone)
                .as("a matching phone number is an identity signal and must be reported")
                .anySatisfy(c -> {
                    assertThat(c.patientNo()).isEqualTo("P-003");
                    assertThat(c.matchedOn()).contains("phone");
                });

        // A different person sharing only a surname must NOT appear.
        assertThat(onName)
                .as("a shared surname is not a duplicate signal on its own")
                .noneSatisfy(c -> assertThat(c.patientNo()).isEqualTo("P-003"));
    }

    @Test
    @DisplayName("a patient is never its own duplicate candidate")
    void aPatientIsNotItsOwnCandidate() {
        ClinicScope scope = tenantAScope();

        var self = service.create(scope, "P-010", "Layla Hassan", "Layla", "Hassan",
                LocalDate.of(1988, 3, 3), "+966500000010", "layla@example.test", "ar", null);

        assertThat(service.duplicateCandidates(scope, "Layla", "Hassan",
                LocalDate.of(1988, 3, 3), "+966500000010", self.id()))
                .as("excluding by id is what stops an edit looking like a duplicate of itself")
                .isEmpty();
    }

    // ------------------------------------------------------------------
    // uniqueness, per DEC-016
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the same phone is refused twice in one clinic")
    void phoneIsUniquePerTenant() {
        ClinicScope scope = tenantAScope();

        service.create(scope, "P-020", "First", "First", "One", null,
                "+966500000020", null, "ar", null);

        assertThatThrownBy(() -> service.create(scope, "P-021", "Second", "Second", "Two", null,
                "+966500000020", null, "ar", null))
                .as("DEC-016: phone is unique per tenant, enforced by the database")
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the same phone is allowed in a different tenant")
    void phoneIsUniquePerTenantNotGlobally() {
        ClinicScope first = tenantAScope();
        service.create(first, "P-030", "First", "First", "One", null,
                "+966500000030", null, "ar", null);

        ClinicScope second = tenantBScope();
        assertThat(service.create(second, "P-031", "Second", "Second", "Two", null,
                "+966500000030", null, "ar", null))
                .as("a global unique index would make the second clinic's patient impossible")
                .isNotNull();
    }

    // ------------------------------------------------------------------
    // tenant isolation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a patient is invisible to another organization")
    void patientsAreTenantIsolated() {
        ClinicScope owner = tenantAScope();
        var mine = service.create(owner, "P-040", "Private Person", "Private", "Person",
                null, "+966500000040", null, "ar", null);

        ClinicScope other = tenantBScope();
        assertThat(service.find(other, mine.id()))
                .as("a patient id from another tenant must read as absent, not as forbidden")
                .isEmpty();
        assertThat(service.list(other, 50))
                .extracting(PatientModels.PatientRow::patientNo)
                .doesNotContain("P-040");
        assertThat(service.search(other, "Private", 50))
                .as("search must not leak across tenants either")
                .isEmpty();
    }

    @Test
    @DisplayName("a soft-deleted patient is not returned")
    void softDeletedPatientsAreHidden() {
        ClinicScope scope = tenantAScope();
        var p = service.create(scope, "P-050", "Gone Patient", "Gone", "Patient", null,
                "+966500000050", null, "ar", null);

        jdbc.update("""
                update "patients"
                   set "deletedAt" = (current_timestamp at time zone 'utc'),
                       "deletionReason" = 'test'
                 where "id" = :id
                """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("id", p.id()));

        assertThat(service.find(scope, p.id())).isEmpty();
        assertThat(service.list(scope, 50)).extracting(PatientModels.PatientRow::patientNo)
                .doesNotContain("P-050");
    }

    @Test
    @DisplayName("a patient under legal hold cannot be soft-deleted")
    void legalHoldBlocksDeletion() {
        ClinicScope scope = tenantAScope();
        UUID id = service.create(scope, "P-060", "Held Patient", "Held", "Patient", null,
                "+966500000060", null, "ar", null).id();

        jdbc.update("""
                update "patients" set "legalHold" = true where "id" = :id
                """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("id", id));

        // DEC-007: "a legal-hold flag blocks deletion", enforced at the database rather than left
        // to whichever service remembers.
        assertThatThrownBy(() -> jdbc.update("""
                update "patients"
                   set "deletedAt" = (current_timestamp at time zone 'utc')
                 where "id" = :id
                """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("id", id)))
                .as("the CHECK constraint is the control, not the application")
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("creating a patient without naming a clinic is refused")
    void creationRequiresAProjectScope() {
        // A caller who may reach several clinics has no single correct projectId, and writing the
        // first accessible one would file the record under a clinic the patient does not belong to.
        assertThatThrownBy(() -> service.create(ambiguousScope(), "P-070", "X", "X", "X", null,
                null, null, "ar", null))
                .as("a patient belongs to exactly one clinic, so a caller who may reach several "
                        + "must name the clinic rather than be filed under the first accessible one")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("one clinic");
    }

    // ------------------------------------------------------------------

    /**
     * The base class documents that the database is reset per test but does not do it; each
     * concrete class calls CrmFixture.reset() from its own @BeforeEach. Without this, patients
     * and vocabulary accumulate across tests in one class and every test that adds a term
     * collides with the last one's code.
     */
    @BeforeEach
    void resetDatabase() {
        fixture.reset();
    }

    @Autowired
    private com.firstelement.crm.CrmFixture fixture;

    /** Project A1 in organization A, as its admin. */
    private ClinicScope tenantAScope() {
        return ClinicScope.forRequestedProject(guard, fixture.adminAContext(), fixture.projectA1);
    }

    /** Project B1 in a DIFFERENT organization. */
    private ClinicScope tenantBScope() {
        return ClinicScope.forRequestedProject(guard, fixture.adminBContext(), fixture.projectB1);
    }

    /** A caller who may reach two projects, so a scope derived from them is not project-scoped. */
    private ClinicScope ambiguousScope() {
        return ClinicScope.forCaller(fixture.adminAContext());
    }
}
