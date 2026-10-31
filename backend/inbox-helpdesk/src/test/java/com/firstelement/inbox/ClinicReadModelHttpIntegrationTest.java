package com.firstelement.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.firstelement.security.UserRole;
import com.firstelement.security.error.PolicyDeniedException;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.permission.RolePermissions;
import com.firstelement.security.session.BrowserSessionStore;
import org.springframework.security.access.AccessDeniedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The clinic read model and its analytics, over real HTTP and a real PostgreSQL.
 *
 * <h2>What makes this suite worth running against a database</h2>
 *
 * <p>Three claims in this slice cannot be established without one:
 *
 * <ol>
 *   <li><b>A metric drills to source rows.</b> Each figure is compared against the
 *       <em>independent</em> population {@link ClinicFixture#appointmentIdsInWindow} re-derives
 *       with its own SQL — not against a number produced by the repository under test. A
 *       wrong derivation therefore fails even when the count happens to be right, which is
 *       exactly the failure the mission names.</li>
 *   <li><b>The window is applied.</b> One seeded appointment sits outside the analytics window
 *       precisely so a dropped date filter <em>inflates</em> a count rather than merely
 *       emptying one.</li>
 *   <li><b>Tenant isolation is negative.</b> Tenant B's rows exist, and a tenant-A actor gets
 *       nothing of them on every route — including the ones that take a {@code projectId} in
 *       the query string, which is the shape the 2026-10-08 breach exploited.</li>
 * </ol>
 *
 * <h2>Why these run over HTTP rather than against the service</h2>
 *
 * <p>Because the refusal shape is a property of the whole chain: the filter chain's 401, the
 * method-security 403, and the advice's non-disclosing 404. A test that calls the service
 * directly would prove the service refuses and say nothing about what a client is told — and
 * "what a client is told" is the security property here.
 */
@SpringBootTest(classes = InboxTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClinicReadModelHttpIntegrationTest extends InboxIntegrationTestBase {

    private static final String WINDOW_FROM = "2026-08-01T00:00:00.000Z";
    private static final String WINDOW_TO = "2026-09-01T00:00:00.000Z";

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private BrowserSessionStore sessions;
    @Autowired
    private com.firstelement.security.config.SecurityProperties securityProperties;
    @Autowired
    private InboxFixture fixture;
    @Autowired
    private ClinicFixture clinic;
    @Autowired
    private com.firstelement.crm.clinic.ClinicReadService clinicService;

    @BeforeEach
    void reset() {
        fixture.reset();
        // Explicit, not part of the shared reset: seeding clinic rows into InboxFixture.reset()
        // changes what every other inbox suite observes, and it broke the legacy byte-for-byte
        // comparison. See the note in InboxFixture#reset.
        clinic.seedClinic();
    }

    // ==================================================================
    // the read model
    // ==================================================================

    @Nested
    @DisplayName("the clinic read model")
    class ReadModel {

        @Test
        @DisplayName("the roster returns a clinician's working week and never their contact details")
        void theRosterOmitsContactDetails() {
            String cookie = sessionFor(InboxFixture.USER_ADMIN_A);

            JsonNode response = get(cookie, "/api/v1/clinic/doctors");

            assertThat(response.get("count").asInt())
                .as("an ADMIN's server-resolved project list is every project of their own "
                    + "organization, so they see A1's two clinicians and A2's one. Observed over "
                    + "HTTP, not assumed -- the first version of this assertion expected 2 and "
                    + "was wrong about the admin's scope rather than about the query")
                .isEqualTo(3);
            JsonNode doctor = response.get("items").get(0);
            assertThat(doctor.get("name").asText()).isNotBlank();
            assertThat(doctor.get("specialty").asText()).isNotBlank();
            assertThat(doctor.get("startHour").asText()).isNotBlank();
            assertThat(doctor.get("workingDays")).isNotEmpty();

            assertThat(response.toString())
                .as("Phase 33 put clinician email and phone behind doctor:read-management. The "
                    + "values ARE in the table -- ClinicFixture seeds them -- so this is an "
                    + "assertion about the projection, not about an absent column")
                .doesNotContain("clinic.test")
                .doesNotContain("\"email\"")
                .doesNotContain("\"phone\"");
        }

        @Test
        @DisplayName("an employee sees only their own project's clinicians")
        void anEmployeeSeesOnlyItsOwnProjects() {
            // The sharper version of the assertion above: the admin's scope is the whole
            // organization, so the roster count alone proves nothing about scoping. The employee
            // holds A1 only, and A2's clinician must simply not be there.
            String cookie = sessionFor(InboxFixture.USER_STAFF_A);

            JsonNode response = get(cookie, "/api/v1/clinic/doctors");

            assertThat(response.get("count").asInt()).isEqualTo(2);
            assertThat(response.toString())
                .doesNotContain("Dr. Salma Idrissi")
                .doesNotContain(ClinicFixture.DOCTOR_A2_PROJECT.toString());
        }

        @Test
        @DisplayName("the list accepts the two-year window it opens with")
        void theListAcceptsItsDefaultWindow() {
            String cookie = sessionFor(InboxFixture.USER_ADMIN_A);
            java.time.LocalDate today = java.time.LocalDate.now(java.time.Clock.systemUTC());

            JsonNode response = get(cookie,
                "/api/v1/clinic/appointments?from=" + today.minusDays(365) + "T00:00:00"
                    + "&to=" + today.plusDays(365) + "T23:59:59");

            assertThat(response.get("from").asText()).isNotBlank();
            assertThat(response.get("to").asText()).isNotBlank();
        }

        @Test
        @DisplayName("the schedule publishes the patient email the list needs but withholds the phone")
        void theScheduleProjectsOnlyWhatAPermissionNames() {
            String cookie = sessionFor(InboxFixture.USER_ADMIN_A);

            JsonNode response = get(cookie,
                "/api/v1/clinic/appointments?from=" + WINDOW_FROM + "&to=" + WINDOW_TO);

            assertThat(response.get("count").asInt()).isPositive();
            JsonNode appointment = response.get("items").get(0);
            assertThat(appointment.get("patientName").asText())
                .as("a receptionist must be able to greet the patient")
                .isNotBlank();
            assertThat(appointment.get("status").asText()).isNotBlank();

            assertThat(response.toString())
                .as("CLINICAL-NOTES-MARKER is seeded into appointments.\"notes\" by "
                    + "ClinicFixture, so its absence proves the column was never projected "
                    + "rather than proving the column is empty")
                .doesNotContain("CLINICAL-NOTES-MARKER")
                .doesNotContain("aiProposalReason")
                .doesNotContain("\"notes\"");

            assertThat(appointment.has("patientEmail"))
                .as("the Phase 3 appointment list identifies a patient by name and email, so the "
                    + "email is part of the projection rather than a second round trip per row")
                .isTrue();
            assertThat(appointment.has("patientPhone"))
                .as("appointment:read names the schedule; it does not name a patient's contact "
                    + "details. In the legacy matrix that disclosure belongs to GET "
                    + "/clinic/reminders behind REMINDER_READ, which is not in the Permission "
                    + "enum yet. ClinicFixture seeds a phone on every appointment, so this is an "
                    + "assertion about the projection and not about an empty column")
                .isFalse();
            assertThat(response.toString()).doesNotContain("\"patientPhone\"");
        }

        @Test
        @DisplayName("the catalogue keeps a NULL price distinct from zero")
        void aNullPriceIsNotZero() {
            String cookie = sessionFor(InboxFixture.USER_ADMIN_A);

            JsonNode response = get(cookie, "/api/v1/clinic/procedures");

            assertThat(response.get("count").asInt()).isEqualTo(3);
            Map<String, JsonNode> byName = new java.util.HashMap<>();
            response.get("items").forEach(item -> byName.put(item.get("name").asText(), item));

            assertThat(byName.get("Consultation").get("price").asDouble())
                .as("ClinicFixture prices this one at 150.0")
                .isEqualTo(150.0);
            assertThat(byName.get("ECG").get("price").isNull())
                .as("'not priced' and 'priced at 0' are different facts and FR-7.6 requires "
                    + "them to look different. ClinicFixture leaves this one NULL on purpose")
                .isTrue();
            assertThat(byName.get("MRI Scan").get("price").asDouble()).isEqualTo(400.0);
        }

        @Test
        @DisplayName("the schedule pages by keyset and never repeats or skips a row")
        void theSchedulePagesWithoutDuplicates() {
            String cookie = sessionFor(InboxFixture.USER_ADMIN_A);
            String base = "/api/v1/clinic/appointments?projectId=" + InboxFixture.PROJECT_A1
                + "&from=" + WINDOW_FROM + "&to=" + WINDOW_TO + "&limit=2";

            List<UUID> paged = new ArrayList<>();
            String cursor = null;
            for (int page = 0; page < 10; page++) {
                JsonNode response = get(cookie, cursor == null ? base : base + "&cursor=" + cursor);
                response.get("items").forEach(item -> paged.add(UUID.fromString(item.get("id").asText())));
                String next = response.get("nextCursor").asText(null);
                if (next == null || next.isBlank()) {
                    cursor = null;
                    break;
                }
                cursor = next;
            }

            assertThat(cursor).as("the keyset must terminate, not loop").isNull();
            assertThat(paged).as("no row is returned twice and none is skipped").doesNotHaveDuplicates();
            assertThat(paged)
                .as("every in-window row in project A1 is reachable by paging")
                .containsExactlyInAnyOrderElementsOf(
                    clinic.appointmentIdsInWindow(InboxFixture.ORG_A, InboxFixture.PROJECT_A1,
                        ClinicFixture.WINDOW_FROM, ClinicFixture.WINDOW_TO));
        }

        @Test
        @DisplayName("an unrecognised status is refused, never silently dropped")
        void anUnknownStatusIsRefused() {
            String cookie = sessionFor(InboxFixture.USER_ADMIN_A);

            JsonNode response = getExpectingFailure(cookie, HttpStatus.BAD_REQUEST,
                "/api/v1/clinic/appointments?from=" + WINDOW_FROM + "&to=" + WINDOW_TO
                    + "&status=NOT_A_STATUS");

            assertThat(response.get("code").asText()).isEqualTo("VALIDATION_FAILED");
            assertThat(response.get("message").asText())
                .as("the refusal names the accepted set, because a silently ignored filter widens "
                    + "the result and the widened result looks like an honest one")
                .contains("PENDING").contains("NO_SHOW");
            assertThat(response.get("details").get("accepted").toString())
                .as("and publishes them, so a generated client can render the options rather than "
                    + "parse them out of a sentence")
                .contains("NO_SHOW");
        }

        @Test
        @DisplayName("APPROVED is refused now, not answered with an empty page")
        void theRemovedEighthStatusIsRefusedByName() {
            // This is the negative DEC-017 puts on the wire, and the reason the ruling is not
            // merely a database constraint. A deployed client -- or the deployed AI booking path
            // itself -- is still holding "APPROVED" and still sends it as a filter. If the server
            // stopped storing APPROVED but kept accepting it as a filter, that client would get
            // 200 and an empty list, and would read that empty list as "this clinic has no
            // appointments in that status". An empty page is a wrong answer that looks right.
            //
            // So APPROVED has to leave the accepted set too, and be refused BY NAME, with the
            // replacement named in the refusal. NOT_A_STATUS above proves unknown values are
            // refused; this proves the value that used to be real is now the refused one, and
            // that the rename is discoverable rather than folklore.
            String cookie = sessionFor(InboxFixture.USER_ADMIN_A);

            JsonNode response = getExpectingFailure(cookie, HttpStatus.BAD_REQUEST,
                "/api/v1/clinic/appointments?from=" + WINDOW_FROM + "&to=" + WINDOW_TO
                    + "&status=APPROVED");

            assertThat(response.get("code").asText()).isEqualTo("VALIDATION_FAILED");
            assertThat(response.get("message").asText())
                .as("the refusal names the accepted set, which includes the value that took "
                    + "APPROVED's place rather than leaving the caller to guess the rename")
                .contains("CONFIRMED");
            assertThat(response.get("details").get("accepted").toString())
                .contains("CONFIRMED");
            assertThat(response.get("details").get("accepted").toString())
                .as("and APPROVED is absent from it -- if it were still listed the client would "
                    + "have no reason to migrate, and the removal would be a lie")
                .doesNotContain("APPROVED");
        }
    }

    // ==================================================================
    // analytics -- the number AND the rows
    // ==================================================================

    @Nested
    @DisplayName("every metric carries the rows it was computed from")
    class MetricsDrillToRows {

        @Test
        @DisplayName("the funnel's counts equal an independently derived population")
        void theFunnelCountsTheRealRows() {
            String cookie = sessionFor(InboxFixture.USER_ADMIN_A);

            JsonNode analytics = analytics(cookie);

            // The independent population: the fixture's own query, not the repository's.
            List<UUID> inWindow = clinic.appointmentIdsInWindow(InboxFixture.ORG_A,
                InboxFixture.PROJECT_A1, ClinicFixture.WINDOW_FROM, ClinicFixture.WINDOW_TO);
            long totalCounted = 0;
            List<UUID> sampled = new ArrayList<>();
            for (JsonNode row : analytics.get("funnelByStatus")) {
                totalCounted += row.get("appointmentCount").asLong();
                row.get("sourceRowIds").forEach(id -> sampled.add(UUID.fromString(id.asText())));
            }

            assertThat(totalCounted)
                .as("the funnel's counts must add up to the rows in the window. The population is "
                    + "re-derived by ClinicFixture with its own SQL, so a wrong derivation fails "
                    + "here even when the individual counts happen to look right")
                .isEqualTo(inWindow.size());
            assertThat(totalCounted)
                .as("and specifically NOT the whole project: one seeded appointment sits outside "
                    + "the window, so a dropped date filter inflates this rather than emptying it")
                .isLessThan(clinic.appointmentIdsInProject(InboxFixture.ORG_A,
                    InboxFixture.PROJECT_A1).size());
            assertThat(sampled)
                .as("every sampled id is one of the rows the count came from")
                .allSatisfy(id -> assertThat(inWindow).contains(id));
        }

        @Test
        @DisplayName("each funnel row's sampled ids are exactly the rows behind its own count")
        void eachFunnelRowCarriesItsOwnRows() {
            String cookie = sessionFor(InboxFixture.USER_ADMIN_A);

            JsonNode analytics = analytics(cookie);

            for (JsonNode row : analytics.get("funnelByStatus")) {
                String status = row.get("status").asText();
                long count = row.get("appointmentCount").asLong();
                JsonNode ids = row.get("sourceRowIds");

                assertThat(ids)
                    .as("status %s has a count but no rows, which is the defect this read model "
                        + "exists to remove", status)
                    .isNotEmpty();
                assertThat(ids.size())
                    .as("status %s: the sample must cover its count unless the sample limit bit",
                        status)
                    .isEqualTo((int) count);
            }
        }

        @Test
        @DisplayName("the funnel reports every status present, with no floored or invented KPI")
        void theFunnelHasNoFloors() {
            String cookie = sessionFor(InboxFixture.USER_ADMIN_A);

            JsonNode analytics = analytics(cookie);

            List<String> statuses = new ArrayList<>();
            analytics.get("funnelByStatus").forEach(row -> statuses.add(row.get("status").asText()));

            assertThat(statuses)
                .as("the seeded statuses, and only statuses that actually have rows. The legacy "
                    + "service floored its headline KPI at 14 and its confirmed count at 12 -- a "
                    + "floor is a constant wearing a number's clothes")
                .containsExactlyInAnyOrder("CONFIRMED", "COMPLETED", "NO_SHOW", "PENDING");

            assertThat(analytics.toString())
                .as("no hard-coded KPI survives anywhere in the response")
                .doesNotContain("estimatedRevenue")
                .doesNotContain("revenueJod")
                .doesNotContain("aiSavedHours")
                .doesNotContain("utilizationRate")
                .doesNotContain("noShowMitigationImprovement")
                .doesNotContain("patientSatisfactionScore")
                .doesNotContain("dailyTrends")
                .doesNotContain("channelAcquisition");
        }

        @Test
        @DisplayName("procedure demand counts each procedure's own appointments, and keeps zero")
        void procedureDemandCountsAndKeepsZero() {
            String cookie = sessionFor(InboxFixture.USER_ADMIN_A);

            JsonNode analytics = analytics(cookie);

            Map<String, JsonNode> byName = new java.util.HashMap<>();
            analytics.get("demandByProcedure").forEach(row -> byName.put(row.get("name").asText(), row));

            assertThat(byName)
                .as("a procedure with zero bookings must be PRESENT with a zero, not absent. "
                    + "FR-7.6 requires 'no data' and 'zero' to look different, and the inner "
                    + "join the legacy shape would have used renders 'unused' as 'does not exist'")
                .containsKeys("Consultation", "ECG", "MRI Scan");

            assertThat(byName.get("Consultation").get("appointmentCount").asLong())
                .as("A1, A2 and the out-of-window appointment are the only Consultation bookings, "
                    + "and only the first two are in the window")
                .isEqualTo(2);
            assertThat(byName.get("MRI Scan").get("appointmentCount").asLong())
                .as("seeded with zero bookings on purpose")
                .isZero();
            assertThat(byName.get("MRI Scan").get("sourceRowIds"))
                .as("zero rows behind a zero count is correct, and the ids array must be empty "
                    + "rather than absent so the shape does not change with the value")
                .isEmpty();

            JsonNode consultation = byName.get("Consultation");
            assertThat(consultation.get("bookedMinutes").asLong())
                .as("sum of the stored durationMinutes over the two in-window rows (30 + 30)")
                .isEqualTo(60);
            assertThat(consultation.get("sourceRowIds"))
                .hasSize(2)
                .allSatisfy(id -> assertThat(
                    clinic.appointmentIdsInWindow(InboxFixture.ORG_A, InboxFixture.PROJECT_A1,
                        ClinicFixture.WINDOW_FROM, ClinicFixture.WINDOW_TO))
                    .contains(UUID.fromString(id.asText())));
        }

        @Test
        @DisplayName("clinician workload counts each clinician's own appointments, with no percentage")
        void clinicianWorkloadCountsWithoutInventingARatio() {
            String cookie = sessionFor(InboxFixture.USER_ADMIN_A);

            JsonNode analytics = analytics(cookie);

            Map<String, JsonNode> byName = new java.util.HashMap<>();
            analytics.get("workloadByDoctor").forEach(row -> byName.put(row.get("name").asText(), row));

            assertThat(byName)
                .as("the two clinicians of PROJECT_A1. A2's clinician is absent because the "
                    + "request pinned projectId -- an unpinned admin call spans the whole "
                    + "organization and correctly includes them")
                .containsOnlyKeys("Dr. Amina Haddad", "Dr. Youssef Nader");
            assertThat(byName.get("Dr. Amina Haddad").get("appointmentCount").asLong())
                .as("A1, A2 and the January appointment are hers; only the first two are in the "
                    + "window, so a dropped window filter would report 3 and fail here")
                .isEqualTo(2);
            assertThat(byName.get("Dr. Amina Haddad").get("bookedMinutes").asLong())
                .as("sum of the stored durationMinutes over those two rows (30 + 30)")
                .isEqualTo(60);
            assertThat(byName.get("Dr. Youssef Nader").get("appointmentCount").asLong())
                .isEqualTo(1);

            assertThat(analytics.toString())
                .as("the legacy utilization was bookings/(weeklySlots*4)*100*3 clamped into "
                    + "[25,100] -- a slot-length guess, a four-week guess, a factor of three and "
                    + "a floor, with no row behind any of it. A percentage is a capacity policy, "
                    + "so the read model publishes the two sourced counts and stops")
                .doesNotContain("utilizationRate")
                .doesNotContain("weeklySlots");
        }

        @Test
        @DisplayName("an unpinned admin call spans their whole organization, and says so in the rows")
        void anUnpinnedAdminCallSpansTheOrganization() {
            // The counterpart to the pinned assertions above, and the reason they pin. An ADMIN's
            // server-resolved project list is every project of their own organization, so the
            // tenant-wide view really does include A2 -- and it must include it by ROW, not by
            // inflating a total.
            String cookie = sessionFor(InboxFixture.USER_ADMIN_A);

            JsonNode pinned = analytics(cookie);
            JsonNode unpinned = get(cookie,
                "/api/v1/clinic/analytics?from=" + WINDOW_FROM + "&to=" + WINDOW_TO);

            List<UUID> pinnedIds = new ArrayList<>();
            pinned.get("funnelByStatus")
                .forEach(row -> row.get("sourceRowIds")
                    .forEach(id -> pinnedIds.add(UUID.fromString(id.asText()))));
            List<UUID> unpinnedIds = new ArrayList<>();
            unpinned.get("funnelByStatus")
                .forEach(row -> row.get("sourceRowIds")
                    .forEach(id -> unpinnedIds.add(UUID.fromString(id.asText()))));

            assertThat(unpinnedIds)
                .as("the wider scope reaches strictly more rows, and the extra one is A2's "
                    + "appointment -- a scope that widened the number without widening the rows "
                    + "would be the failure this whole assertion exists to rule out")
                .containsAll(pinnedIds)
                .contains(ClinicFixture.APPOINTMENT_A2_PROJECT);
            assertThat(pinnedIds).doesNotContain(ClinicFixture.APPOINTMENT_A2_PROJECT);
        }

        @Test
        @DisplayName("each metric group names its source table, its window and its derivation")
        void eachGroupPublishesItsProvenance() {
            String cookie = sessionFor(InboxFixture.USER_ADMIN_A);

            JsonNode analytics = analytics(cookie);

            for (String group : List.of("funnelProvenance", "demandProvenance",
                "workloadProvenance")) {
                JsonNode provenance = analytics.get(group);
                assertThat(provenance).as("%s must be published", group).isNotNull();
                assertThat(provenance.get("sourceTable").asText())
                    .as("%s names the physical relation its number came from", group)
                    .isNotBlank();
                assertThat(provenance.get("derivation").asText())
                    .as("%s states how it was derived; an undescribed number is the defect this "
                        + "read model exists to remove", group)
                        .isNotBlank();
                assertThat(provenance.get("windowStart").get("iso8601").asText())
                    .as("%s publishes the LOWER bound the query actually bound", group)
                    .isEqualTo(WINDOW_FROM);
                assertThat(provenance.get("windowEnd").get("iso8601").asText())
                    .as("%s publishes the UPPER bound", group)
                    .isEqualTo(WINDOW_TO);
                assertThat(provenance.get("sourceRowSampleLimit").asInt()).isPositive();
            }

            assertThat(analytics.get("drillThroughPath").asText())
                .as("a reader must be able to leave the number and reach the rows with the SAME "
                    + "filters, which means the path carries the resolved window")
                .contains("from=" + WINDOW_FROM)
                .contains("to=" + WINDOW_TO)
                .startsWith("/api/v1/clinic/appointments?")
                .doesNotContain(InboxFixture.ORG_A.toString())
                .doesNotContain(InboxFixture.PROJECT_A1.toString())
                .as("and it must not carry a tenant or project id: those are resolved from the "
                    + "session on the other end, and a link that named one would mean something "
                    + "different depending on who clicked it");
        }

        @Test
        @DisplayName("the drill-through path is followable: it returns the rows the metrics counted")
        void theDrillThroughPathReturnsTheRows() {
            String cookie = sessionFor(InboxFixture.USER_ADMIN_A);

            JsonNode analytics = analytics(cookie);
            String path = analytics.get("drillThroughPath").asText()
                + "&projectId=" + InboxFixture.PROJECT_A1;

            List<UUID> followed = new ArrayList<>();
            get(cookie, path).get("items")
                .forEach(item -> followed.add(UUID.fromString(item.get("id").asText())));

            List<UUID> counted = new ArrayList<>();
            analytics.get("funnelByStatus")
                .forEach(row -> row.get("sourceRowIds")
                    .forEach(id -> counted.add(UUID.fromString(id.asText()))));

            assertThat(followed)
                .as("following the published path with the same project reaches every row the "
                    + "counts were derived from -- which is what 'drills to source rows' has to "
                    + "mean if it is a claim and not a slogan")
                .containsAll(counted);
        }

        @Test
        @DisplayName("a sample is always distinguishable from a complete population")
        void aSampleSaysItIsOne() {
            String cookie = sessionFor(InboxFixture.USER_ADMIN_A);

            JsonNode analytics = analytics(cookie);

            assertThat(analytics.get("sourceRowSampleLimit").asInt())
                .as("the bound is published, so a reader knows the arrays are capped")
                .isPositive();
            assertThat(analytics.get("funnelByStatus").get(0).has("sourceRowIds")).isTrue();
        }

        @Test
        @DisplayName("the sampled ids union back to the rows the funnel counted")
        void theSampledIdsBelongToThePopulation() {
            String cookie = sessionFor(InboxFixture.USER_ADMIN_A);

            JsonNode analytics = analytics(cookie);

            List<UUID> population = clinic.appointmentIdsInWindow(InboxFixture.ORG_A,
                InboxFixture.PROJECT_A1, ClinicFixture.WINDOW_FROM, ClinicFixture.WINDOW_TO);
            List<UUID> sampled = new ArrayList<>();
            analytics.get("funnelByStatus")
                .forEach(row -> row.get("sourceRowIds")
                    .forEach(id -> sampled.add(UUID.fromString(id.asText()))));

            assertThat(sampled).doesNotHaveDuplicates();
            assertThat(sampled).allSatisfy(id -> assertThat(population).contains(id));
            assertThat(sampled)
                .as("with a sample limit of 50 and four in-window rows, the sample IS the "
                    + "population -- so the drill-through claim is checkable here and not only "
                    + "in principle")
                .hasSameSizeAs(population);
        }
    }

    // ==================================================================
    // tenant isolation -- negative, on every route
    // ==================================================================

    @Nested
    @DisplayName("a tenant-A actor receives nothing of tenant B")
    class TenantIsolation {

        @Test
        @DisplayName("every route refuses tenant B's ids with the same non-disclosing 404")
        void foreignIdsAreIndistinguishableFromNonexistentOnes() {
            String cookie = sessionFor(InboxFixture.USER_ADMIN_A);
            String foreign = "/api/v1/clinic/";
            String nonexistent = "/api/v1/clinic/";

            List<String[]> routes = List.of(
                new String[] { foreign + "doctors/" + ClinicFixture.DOCTOR_B1,
                    nonexistent + "doctors/" + UUID.randomUUID() },
                new String[] { foreign + "procedures/" + ClinicFixture.PROCEDURE_B1,
                    nonexistent + "procedures/" + UUID.randomUUID() },
                new String[] { foreign + "appointments/" + ClinicFixture.APPOINTMENT_B1,
                    nonexistent + "appointments/" + UUID.randomUUID() });

            for (String[] pair : routes) {
                JsonNode foreignBody = getExpectingFailure(cookie, HttpStatus.NOT_FOUND, pair[0]);
                JsonNode missingBody = getExpectingFailure(cookie, HttpStatus.NOT_FOUND, pair[1]);

                assertThat(foreignBody.get("code").asText())
                    .as("%s must be refused", pair[0]).isEqualTo("NOT_FOUND");
                assertThat(foreignBody.get("message").asText())
                    .as("a per-resource message ('Doctor not found') was itself an existence "
                        + "oracle in Phase 26: it separates 'not yours' from 'does not exist'")
                    .isEqualTo("Resource not found")
                    .isEqualTo(missingBody.get("message").asText());
                assertThat(foreignBody.get("code").asText())
                    .isEqualTo(missingBody.get("code").asText());
            }
        }

        @Test
        @DisplayName("a tenant-B projectId in the query string is refused, and no row is returned")
        void aForeignProjectIdInTheQueryIsRefused() {
            String cookie = sessionFor(InboxFixture.USER_ADMIN_A);

            for (String route : List.of(
                "/api/v1/clinic/doctors?projectId=" + InboxFixture.PROJECT_B1,
                "/api/v1/clinic/procedures?projectId=" + InboxFixture.PROJECT_B1,
                "/api/v1/clinic/appointments?projectId=" + InboxFixture.PROJECT_B1
                    + "&from=" + WINDOW_FROM + "&to=" + WINDOW_TO,
                "/api/v1/clinic/analytics?projectId=" + InboxFixture.PROJECT_B1
                    + "&from=" + WINDOW_FROM + "&to=" + WINDOW_TO)) {
                JsonNode response = getExpectingFailure(cookie, HttpStatus.NOT_FOUND, route);
                assertThat(response.get("code").asText()).isEqualTo("NOT_FOUND");
                assertThat(response.toString())
                    .as("%s must not echo the project it refused", route)
                    .doesNotContain(InboxFixture.PROJECT_B1.toString());
            }
        }

        @Test
        @DisplayName("a project in the caller's own tenant but outside their membership is refused")
        void aSameTenantProjectOutsideMembershipIsRefused() {
            // The employee holds PROJECT_A1 only, inside tenant A. PROJECT_A2 is in their OWN
            // tenant, so this is the cross-PROJECT question rather than the cross-tenant one --
            // and it is the case a bare organizationId predicate would wave through.
            String cookie = sessionFor(InboxFixture.USER_STAFF_A);

            JsonNode response = getExpectingFailure(cookie, HttpStatus.NOT_FOUND,
                "/api/v1/clinic/appointments?projectId=" + InboxFixture.PROJECT_A2
                    + "&from=" + WINDOW_FROM + "&to=" + WINDOW_TO);
            assertThat(response.get("code").asText()).isEqualTo("NOT_FOUND");

            assertThat(status(cookie, HttpMethod.GET, "/api/v1/clinic/appointments/"
                + ClinicFixture.APPOINTMENT_A2_PROJECT, null))
                .as("an appointment in a project the employee has left is a 404, not a 403")
                .isEqualTo(404);
        }

        @Test
        @DisplayName("no tenant-A list contains a tenant-B name, id or phone")
        void noListLeaksTheOtherTenant() {
            String cookie = sessionFor(InboxFixture.USER_ADMIN_A);

            for (String route : List.of(
                "/api/v1/clinic/doctors",
                "/api/v1/clinic/procedures",
                "/api/v1/clinic/appointments?from=" + WINDOW_FROM + "&to=" + WINDOW_TO,
                "/api/v1/clinic/analytics?from=" + WINDOW_FROM + "&to=" + WINDOW_TO)) {
                JsonNode response = get(cookie, route);
                assertThat(response.toString())
                    .as("%s must contain nothing of tenant B", route)
                    .doesNotContain("Dr. Beta Only")
                    .doesNotContain("Beta Procedure")
                    .doesNotContain(ClinicFixture.DOCTOR_B1.toString())
                    .doesNotContain(ClinicFixture.PROCEDURE_B1.toString())
                    .doesNotContain(ClinicFixture.APPOINTMENT_B1.toString())
                    .doesNotContain("+966500000003");
            }
        }

        @Test
        @DisplayName("an actor with no project membership receives nothing at all")
        void noMembershipMeansNoRows() {
            // FR-1.4. The outsider is a legitimate authenticated user of tenant A with zero
            // project_memberships rows. An organization-only predicate would return the whole
            // tenant to them.
            String cookie = sessionFor(InboxFixture.USER_OUTSIDER_A);

            assertThat(get(cookie, "/api/v1/clinic/doctors").get("count").asInt()).isZero();
            assertThat(get(cookie, "/api/v1/clinic/procedures").get("count").asInt()).isZero();
            assertThat(get(cookie,
                "/api/v1/clinic/appointments?from=" + WINDOW_FROM + "&to=" + WINDOW_TO)
                .get("count").asInt()).isZero();

            JsonNode analytics = get(cookie,
                "/api/v1/clinic/analytics?from=" + WINDOW_FROM + "&to=" + WINDOW_TO);
            assertThat(analytics.get("funnelByStatus")).isEmpty();
            assertThat(analytics.get("demandByProcedure"))
                .as("even the left-joined metrics are empty: the scope predicate is on the "
                    + "procedures table itself, so no group is formed at all")
                .isEmpty();
            assertThat(analytics.get("workloadByDoctor")).isEmpty();

            assertThat(status(cookie, HttpMethod.GET,
                "/api/v1/clinic/analytics?projectId=" + InboxFixture.PROJECT_A1
                    + "&from=" + WINDOW_FROM + "&to=" + WINDOW_TO, null))
                .as("naming a project they are not a member of is a 404, not an empty result. The "
                    + "empty result above is what an UNSCOPED request must produce, and the two "
                    + "must not be the same answer: one says 'there is nothing here for you', the "
                    + "other says 'not yours'")
                .isEqualTo(404);
        }

        @Test
        @DisplayName("tenant B's own actor sees only tenant B")
        void tenantsSeeOnlyThemselves() {
            String cookie = sessionFor(InboxFixture.USER_ADMIN_B);

            assertThat(get(cookie, "/api/v1/clinic/doctors").get("count").asInt()).isEqualTo(1);
            assertThat(get(cookie, "/api/v1/clinic/procedures").get("count").asInt()).isEqualTo(1);
            assertThat(status(cookie, HttpMethod.GET,
                "/api/v1/clinic/doctors/" + ClinicFixture.DOCTOR_A1, null))
                .isEqualTo(404);
            assertThat(status(cookie, HttpMethod.GET,
                "/api/v1/clinic/appointments/" + ClinicFixture.APPOINTMENT_A1, null))
                .isEqualTo(404);
        }

        @Test
        @DisplayName("an unauthenticated caller gets 401, which is a different failure from 404")
        void unauthenticatedIsFourOhOne() {
            for (String route : List.of("/api/v1/clinic/doctors", "/api/v1/clinic/procedures",
                "/api/v1/clinic/appointments", "/api/v1/clinic/analytics",
                "/api/v1/clinic/doctors/" + ClinicFixture.DOCTOR_A1)) {
                assertThat(status(null, HttpMethod.GET, route, null))
                    .as("%s with no session", route)
                    .isEqualTo(401);
            }
        }
    }

    // ==================================================================
    // role refusals
    // ==================================================================

    @Nested
    @DisplayName("role refusals")
    class Roles {

        @Test
        @DisplayName("a principal without the clinic grant is refused by every service method")
        void aPrincipalWithoutTheGrantIsRefused() {
            // A machine principal holds capabilities, never roles, and every clinic permission is
            // a human-role permission. A worker holding nothing is therefore refused for a reason
            // that is about the PERMISSION rather than about the route -- which is what makes
            // this a sharper probe than deleting a row would be.
            var machine = com.firstelement.security.TenantContext.forMachine(
                com.firstelement.security.PrincipalType.WORKER, InboxFixture.ORG_A,
                "clinic-probe", List.of(InboxFixture.PROJECT_A1), List.of());

            assertThatThrownBy(() -> PrincipalScope.call(machine,
                    () -> clinicService.doctors(machine, null)))
                .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> PrincipalScope.call(machine,
                    () -> clinicService.doctor(machine, ClinicFixture.DOCTOR_A1)))
                .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> PrincipalScope.call(machine,
                    () -> clinicService.procedures(machine, null)))
                .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> PrincipalScope.call(machine,
                    () -> clinicService.procedure(machine, ClinicFixture.PROCEDURE_A1)))
                .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> PrincipalScope.call(machine,
                    () -> clinicService.appointment(machine, ClinicFixture.APPOINTMENT_A1)))
                .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> PrincipalScope.call(machine,
                    () -> clinicService.clinicAnalytics(machine, null,
                        ClinicFixture.WINDOW_FROM, ClinicFixture.WINDOW_TO)))
                .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("a refusal is a 403, never a 404 and never a 500")
        void aMissingGrantIsAForbidden() {
            // Distinct from a scope denial, which is a 404. Conflating them would either put an
            // existence oracle on a surface carrying patient names, or turn a legitimate refusal
            // into a not-found a client cannot act on.
            //
            // The MESSAGE is asserted only as "denied" and not as a fixed string. The annotation
            // interceptor raises Spring Security's own `AuthorizationDeniedException` before the
            // service's explicit `authorization.check` ever runs, so the text is Spring's, not
            // ours -- and asserting an exact sentence here would pin a framework message we do not
            // control. What matters is the TYPE, which is what decides the status.
            var machine = com.firstelement.security.TenantContext.forMachine(
                com.firstelement.security.PrincipalType.WORKER, InboxFixture.ORG_A,
                "no-grants", List.of(InboxFixture.PROJECT_A1), List.of());

            assertThatThrownBy(() -> PrincipalScope.call(machine,
                    () -> clinicService.doctors(machine, null)))
                .isInstanceOf(AccessDeniedException.class)
                .isNotInstanceOf(PolicyDeniedException.class);
        }

        @Test
        @DisplayName("an employee holds the three clinic READ grants and nothing else of the vocabulary")
        void anEmployeeHoldsTheReadHalf() {
            var employee = com.firstelement.security.TenantContext.forUser(UUID.randomUUID(),
                InboxFixture.USER_STAFF_A, InboxFixture.ORG_A, UserRole.EMPLOYEE,
                List.of(InboxFixture.PROJECT_A1));

            assertThat(RolePermissions.forRole(UserRole.EMPLOYEE))
                .contains(Permission.DOCTOR_READ, Permission.PROCEDURE_READ,
                    Permission.APPOINTMENT_READ, Permission.ANALYTICS_READ_PROJECT);

            assertThat(PrincipalScope.call(employee, () -> clinicService.doctors(employee, null)))
                .as("an employee is the front desk: the roster read is the whole job")
                .hasSize(2);
            assertThat(PrincipalScope.call(employee,
                    () -> clinicService.clinicAnalytics(employee, InboxFixture.PROJECT_A1,
                        ClinicFixture.WINDOW_FROM, ClinicFixture.WINDOW_TO))
                    .funnelByStatus())
                .as("and ANALYTICS_READ_PROJECT was already granted before this phase, so the "
                    + "analytics route needed no new permission")
                .isNotEmpty();
        }
    }

    // ==================================================================
    // helpers
    // ==================================================================
/**
     * The analytics for one project, with the window the assertions expect.
     *
     * <p><b>Pinned to PROJECT_A1 on purpose.</b> An administrator's server-resolved project list
     * is every project of their own organization, so an unpinned call spans A1 <em>and</em> A2 and
     * the counts legitimately include A2's appointment. That is correct behaviour, not a bug —
     * the first version of these assertions compared a tenant-wide figure against a
     * single-project population and failed, which says the assertion was wrong and not the query.
     *
     * <p>Pinning also makes each assertion about <em>one</em> thing: the window, or the project
     * filter, rather than both at once.
     */
    private JsonNode analytics(String cookie) {
        return analytics(cookie, InboxFixture.PROJECT_A1);
    }

    private JsonNode analytics(String cookie, UUID projectId) {
        return get(cookie, "/api/v1/clinic/analytics?projectId=" + projectId
            + "&from=" + WINDOW_FROM + "&to=" + WINDOW_TO);
    }

    private JsonNode get(String cookie, String path) {
        ResponseEntity<JsonNode> response = http.exchange(path, HttpMethod.GET,
            new HttpEntity<>(headers(cookie)), JsonNode.class);
        assertThat(response.getStatusCode())
            .as("GET %s returned %s: %s", path, response.getStatusCode(), response.getBody())
            .isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private JsonNode getExpectingFailure(String cookie, HttpStatus expected, String path) {
        ResponseEntity<JsonNode> response = http.exchange(path, HttpMethod.GET,
            new HttpEntity<>(headers(cookie)), JsonNode.class);
        assertThat(response.getStatusCode())
            .as("GET %s returned %s: %s", path, response.getStatusCode(), response.getBody())
            .isEqualTo(expected);
        return response.getBody();
    }

    private int status(String cookie, HttpMethod method, String path, String body) {
        return http.exchange(path, method, new HttpEntity<>(body, headers(cookie)), String.class)
            .getStatusCode().value();
    }

    private HttpHeaders headers(String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (cookie != null) {
            headers.set(HttpHeaders.COOKIE, cookie);
        }
        return headers;
    }

    /** A real revocable session for a seeded user; the raw token never leaves this method. */
    private String sessionFor(UUID userId) {
        BrowserSessionStore.IssuedSession issued =
            sessions.issue(userId, "clinic-read-http-test", "127.0.0.1", 1);
        assertThat(issued).as("a session was issued for user %s", userId).isNotNull();
        return securityProperties.getSessionCookieName() + "=" + issued.rawToken();
    }
}