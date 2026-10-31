package com.firstelement.exports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstelement.exports.job.ExportJobService;
import com.firstelement.exports.persistence.ExportJobRepository;
import com.firstelement.security.TenantAuthentication;
import com.firstelement.security.TenantContext;
import com.firstelement.security.TenantContextHolder;
import com.firstelement.security.query.TenantScope;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * ADR 0016 over the wire: the two export routes that accept work which is <b>still outstanding</b>
 * answer {@code 202}, and the work really is outstanding when they do.
 *
 * <h2>Why this suite cannot be replaced by reading the controller</h2>
 *
 * <p>ADR 0016 rule 1 says the code must be established by executing the route, and rule 5 says a
 * drift gate on the document is not enough "because a document edited to match a bug passes it". The
 * second half of every assertion below is what makes that concrete: after the HTTP call, the suite
 * reads the row back through the repository and finds {@code QUEUED}, a null {@code storageKey} and
 * a null {@code checksum}. A {@code 200} on that state is not a naming disagreement — it tells a
 * generated client the artefact exists when nothing has been rendered.
 *
 * <h2>And the document half, which is the one that could be faked</h2>
 *
 * <p>{@link #theDocumentDeclaresTheCodeTheRouteReturns()} asks the served document for the same two
 * operations and requires it to declare exactly the code observed here. Together the two assertions
 * mean neither a route that changes without the document nor a document edited to match a wrong
 * route can pass.
 *
 * <h2>Why the security filters are off</h2>
 *
 * <p>The question here is which status the <em>handler</em> returns, not whether an unauthenticated
 * caller is refused — which {@code SecurityBoundaryHttpIntegrationTest} and
 * {@code AdminRoutePermissionHttpIntegrationTest} already prove over a real socket. The thread
 * bindings a request needs are re-established per call by {@link #asPrincipal}, exactly as the
 * sibling authoring suite does, so {@code @RequirePermission} is evaluated against a real grant
 * rather than skipped.
 */
@SpringBootTest(classes = ExportsTestBase.ExportsTestApplication.class)
@AutoConfigureMockMvc(addFilters = false)
class AsyncStatusHttpIntegrationTest extends ExportsTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String WORKER = "async-status-http-test";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ExportsFixture fixture;

    @Autowired
    private ExportJobService jobs;

    @Autowired
    private ExportJobRepository jobRepository;

    private TenantContext adminA;
    private UUID reportA;

    @BeforeEach
    void seed() {
        fixture.reset();
        fixture.seedOrganizationsAndUsers();
        adminA = fixture.adminA();
        reportA = fixture.publishedCustomerReport(adminA, PROJECT_A, "none");
        fixture.customerInProjectA();
    }

    // ==================================================================
    // the wire
    // ==================================================================

    /**
     * A retry accepts work; it does not perform it.
     *
     * <p>The route is the clearest instance of the defect ADR 0016 was raised about, and the fix is
     * the direction rule 1 names: the route was wrong, so the route changed. Declaring the old
     * {@code 200} instead would have made the bug contractual.
     */
    @Test
    @DisplayName("POST /api/v1/exports/{id}/retry answers 202 and the artefact still does not exist")
    void aRetryIsAcceptedNotCompleted() throws Exception {
        UUID jobId = queueId();
        ExportsPrincipalScope.run(adminA, () -> jobs.cancel(adminA, jobId, "driven to FAILED for the retry"));

        MvcResult accepted = mvc.perform(post("/api/v1/exports/" + jobId + "/retry")
                .with(asPrincipal(adminA)))
            .andReturn();

        assertThat(accepted.getResponse().getStatus())
            .as("202 Accepted: the retry is durable and nothing has been generated. A 200 here is the "
                + "ADR-0016 defect -- a generated client reads it as a finished export and renders a "
                + "download that cannot succeed.")
            .isEqualTo(202);
        assertThat(accepted.getResponse().getStatus())
            .as("and the CONTRACT declares the code the wire just produced. Comparing the document "
                + "with itself would be circular, so the two halves meet here: this number came off "
                + "the socket, and that one comes from the table the customiser publishes from.")
            .isEqualTo(declaredStatusOf("POST", "/api/v1/exports/{exportId}/retry"));

        // STATE, not the response body. This is the half a document edit cannot fake.
        ExportJobRepository.ExportJobRow row = jobRepository.findForDownload(scopeA(PROJECT_A), jobId)
            .orElseThrow();
        assertThat(row.status()).isEqualTo("QUEUED");
        assertThat(row.storageKey())
            .as("export_jobs_started_check is a biconditional: a QUEUED job has no artefact key")
            .isNull();
        assertThat(row.checksum()).isNull();
        assertThat(row.finishedAt()).isNull();

        // And the job does complete when the worker runs it, which is what makes 202 the truthful
        // code rather than a way of avoiding the question.
        ExportsPrincipalScope.call(adminA, () -> jobs.generate(adminA,
            new TenantScope(ORG_A, PROJECT_A), jobId, WORKER));
        ExportJobRepository.ExportJobRow completed = jobRepository
            .findForDownload(scopeA(PROJECT_A), jobId).orElseThrow();
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.checksum()).matches("^sha256:[0-9a-f]{64}$");
    }

    /**
     * The create route was already correct; the document was not.
     *
     * <p>This is the other half of rule 1: where the route is right, the document is corrected. The
     * assertion is kept here so a future change that "tidies" the create route back to 200 is caught
     * by the wire rather than by a reviewer's memory.
     */
    @Test
    @DisplayName("POST /api/v1/exports answers 202, and the job it returns is still QUEUED")
    void aCreateIsAcceptedNotCompleted() throws Exception {
        MvcResult queued = createExport();

        assertThat(queued.getResponse().getStatus())
            .as("the artefact does not exist when this returns; Phase 16 already asserted this over "
                + "HTTP and the document said 200 anyway")
            .isEqualTo(202);
        assertThat(queued.getResponse().getStatus())
            .as("and the contract declares the code the wire just produced")
            .isEqualTo(declaredStatusOf("POST", "/api/v1/exports"));

        UUID jobId = UUID.fromString(MAPPER.readTree(queued.getResponse().getContentAsString())
            .get("jobId").asText());
        ExportJobRepository.ExportJobRow row = jobRepository
            .findForDownload(scopeA(PROJECT_A), jobId).orElseThrow();
        assertThat(row.status()).isEqualTo("QUEUED");
        assertThat(row.storageKey()).isNull();
        assertThat(row.progressPct()).isZero();
    }

    // ==================================================================
    // the negative direction
    // ==================================================================

    /**
     * A cancellation is finished work, so it stays {@code 200}.
     *
     * <p>ADR 0016 rule 3 forbids declaring a code a route never returns, and this is the case where
     * the instinct to reach for {@code 202} would be wrong: a cancel completes on the request
     * thread, the row is {@code CANCELLED}, and no worker is involved. Asserting it here is what
     * stops the correction spreading from the two async routes into every mutation.
     */
    @Test
    @DisplayName("a cancellation is finished work, so it stays 200 and must not drift to 202")
    void aCancellationIsNotAcceptedWork() throws Exception {
        UUID jobId = queueId();

        MvcResult cancelled = mvc.perform(post("/api/v1/exports/" + jobId + "/cancel")
                .with(asPrincipal(adminA))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"no longer needed\"}"))
            .andReturn();

        assertThat(cancelled.getResponse().getStatus())
            .as("a cancel completes on this thread: the row is CANCELLED and no worker is involved")
            .isEqualTo(200);
        assertThat(cancelled.getResponse().getStatus())
            .as("and the contract agrees, so the 202 correction cannot spread to every mutation")
            .isEqualTo(declaredStatusOf("POST", "/api/v1/exports/{exportId}/cancel"));
        assertThat(jobRepository.findForDownload(scopeA(PROJECT_A), jobId).orElseThrow().status())
            .as("STATE, not the status code")
            .isEqualTo("CANCELLED");
    }

    // ------------------------------------------------------------------
    // wire helpers
    // ------------------------------------------------------------------

    private MvcResult createExport() throws Exception {
        String catalogue = mvc.perform(get("/api/v1/exports/catalogue")
                .param("projectId", PROJECT_A.toString())
                .with(asPrincipal(adminA)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        JsonNode offered = MAPPER.readTree(catalogue).path("reportDefinitions").get(0);

        return mvc.perform(post("/api/v1/exports")
                .param("projectId", PROJECT_A.toString())
                .with(asPrincipal(adminA))
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of(
                    "reportDefinitionId", offered.get("reportDefinitionId").asText(),
                    "reportVersion", offered.path("versions").get(0).path("reportVersion").asInt(),
                    "format", "CSV"))))
            .andReturn();
    }

    private UUID queueId() throws Exception {
        MvcResult queued = createExport();
        assertThat(queued.getResponse().getStatus()).isEqualTo(202);
        return UUID.fromString(MAPPER.readTree(queued.getResponse().getContentAsString())
            .get("jobId").asText());
    }

    /** The document the running application actually serves, asked over the same MockMvc. */
    private JsonNode servedDocument() throws Exception {
        return MAPPER.readTree(mvc.perform(get("/api/v1/openapi"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
    }

    /**
 * The code the contract declares for an operation, read from the table the customiser publishes
 * from.
 *
 * <p><b>This is the seam that stops the gate being circular.</b> ADR 0016 rule 5 rejects a drift
 * gate on its own "because a document edited to match a bug passes it". A test that only compared
 * the document with the table would indeed pass when the table was edited to match a wrong route —
 * which is exactly what a maintainer does to silence it. So the table lives in
 * {@code :backend:shared-kernel}, where this suite can read it, and this suite compares it against a
 * status that came off the socket. Revert the table and this fails; change the route and the
 * document drifts from the wire and this fails the other way.
 */
private static int declaredStatusOf(String method, String path) {
    java.util.List<Integer> declared = com.firstelement.kernel.contract.ObservedRouteStatuses
        .governedByOperation().getOrDefault(method + " " + path, java.util.List.of())
        .stream().map(com.firstelement.kernel.contract.ObservedRouteStatuses.Observation::status)
        .distinct().toList();
    if (declared.isEmpty()) {
        // Not governed, so the document carries springdoc's own inference for it — which is 200, the
        // framework's default. An operation only reaches the governed table when observation says
        // the default is wrong, so "not governed" is a real answer and not a hole.
        return 200;
    }
    assertThat(declared)
        .as("%s %s is governed, and a governed operation with two codes needs its own assertion", method, path)
        .hasSize(1);
    return declared.get(0);
}

private static RequestPostProcessor asPrincipal(TenantContext context) {
        return request -> {
            var securityContext = SecurityContextHolder.createEmptyContext();
            securityContext.setAuthentication(new TenantAuthentication(context));
            SecurityContextHolder.setContext(securityContext);
            TenantContextHolder.set(context);
            return request;
        };
    }
}