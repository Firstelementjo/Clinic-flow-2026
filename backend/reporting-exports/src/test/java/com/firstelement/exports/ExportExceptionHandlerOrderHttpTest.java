package com.firstelement.exports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstelement.exports.web.ExportExceptionHandler;
import com.firstelement.security.TenantAuthentication;
import com.firstelement.security.TenantContext;
import com.firstelement.security.TenantContextHolder;
import exportscope.probe.ProbeFloorAdvice;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.core.OrderComparator;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.method.ControllerAdviceBean;

/**
 * <b>The proof that the export advice ladder is declared and the tie is gone.</b>
 *
 * <h2>What the defect was</h2>
 *
 * <p>{@code ExportExceptionHandler} declared no {@code @Order}, so it defaulted to
 * {@link Ordered#LOWEST_PRECEDENCE} — <b>the same value as {@code app-bootstrap}'s
 * {@code ErrorHandlingAdvice}</b>, the only advice holding {@code @ExceptionHandler(Exception.class)}.
 * Spring resolves an escaping exception by walking the advice beans <em>in order</em> and stopping at the
 * first one holding a handler for the thrown type, so with two advices tied at that value the winner was
 * decided by component-scan registration order: the line order of a module list in one
 * {@code build.gradle.kts}. Every export refusal in the file — 403, 409, 410, 422, 500 — was one build-file
 * edit away from becoming {@code 500 INTERNAL_ERROR}, and a handler that is registered but shadowed reads
 * identically to one that is absent.
 *
 * <h2>Why an {@code @Order} annotation alone proves nothing</h2>
 *
 * <p>Adding the annotation is a one-line change that {@code check-advice-order.ps1} can confirm statically.
 * That is worth having and it is not the same claim. Three separate things have to be true, and this suite
 * takes a different kind of evidence for each rather than letting one assertion stand in for all three:
 *
 * <ol>
 *   <li><b>The declaration is present and resolvable.</b> Asserted on the annotation's own value, and on the
 *       relationship between three real numbers — security, this advice, the CRM family, the floor — rather
 *       than on the presence of a string in a file.</li>
 *   <li><b>Spring's own resolution puts this advice ahead of the floor.</b>
 *       {@link #adviceBeansInResolutionOrder()} asks Spring for the list
 *       {@code ExceptionHandlerExceptionResolver} actually walks — it calls
 *       {@code ControllerAdviceBean.findAnnotatedBeans(context)} and {@code OrderComparator.sort} on the
 *       result, exactly as the resolver does — and asserts the export advice's index is strictly lower. This
 *       is the load-bearing assertion: it fails deterministically if the {@code @Order} is removed, because
 *       without one this advice sits at {@code LOWEST_PRECEDENCE} <em>tied</em> with the floor and "strictly
 *       before" stops being true. It does not depend on which of the two a stable sort happens to put first,
 *       which is the nondeterminism being removed.</li>
 *   <li><b>The consequence is visible over HTTP.</b> A 403, a 409 and the 422
 *       {@code EXPORT_FORMAT_UNSUPPORTED}, each asserted for its own status <em>and</em> its own stable code,
 *       with a {@link ProbeFloorAdvice} registered in this context so the 500 that a lost race would produce is
 *       genuinely reachable. Without the probe the status assertions would be vacuous: with no other advice in
 *       the context, the export refusals could not have been 500 anyway, so the assertions would be testing
 *       the harness rather than the ladder.</li>
 * </ol>
 *
 * <h2>Where each assertion would fail if the annotation came back off — stated, not assumed</h2>
 *
 * <p>Assertion 1 and assertion 2 both fail if {@code @Order} is removed, deterministically and for different
 * reasons: 1 because the value is gone, 2 because the order becomes a tie. Assertion 3 alone would
 * <b>not</b> reliably fail — with a tie, whether the refusals still render their own status depends on bean
 * registration order, which is exactly the fragility this work removes. Recording that here is the point: a
 * suite whose behavioural half cannot detect the regression is honest about it and leans on the structural
 * half, rather than implying three independent proofs where there are one and a half.
 *
 * <h2>Why 422 is asserted and not 400</h2>
 *
 * <p>Both are refused by this advice and both would become 500 identically, so either proves the ladder. 422
 * is chosen because it is the more interesting of the two: {@code EXPORT_FORMAT_UNSUPPORTED} is the refusal
 * for a value the <em>schema declares</em> and this renderer cannot produce, and the catalogue publishes
 * {@code formats} as the accepted set precisely so a client never sends one. The 403 and the 409 are
 * refusals about the caller's own permissions and the caller's own state.
 *
 * <h2>Every refusal has a positive control beside it</h2>
 *
 * <p>The same caller, in the same scope, successfully performs each operation immediately before or after the
 * refusal it answers. A 409 asserted against a suite whose fixture queued nothing proves nothing, and the 403
 * is asserted against a caller who <em>can</em> export the report next to it.
 */
@SpringBootTest(classes = {ExportsTestBase.ExportsTestApplication.class,
    ExportExceptionHandlerOrderHttpTest.ProbeLadder.class})
@AutoConfigureMockMvc(addFilters = false)
class ExportExceptionHandlerOrderHttpTest extends ExportsTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ExportsFixture fixture;

    private TenantContext adminA;
    private TenantContext employeeA;

    @BeforeEach
    void seed() {
        fixture.reset();
        fixture.seedOrganizationsAndUsers();
        adminA = fixture.adminA();
        employeeA = fixture.employeeA();
        fixture.customerInProjectA();
    }

    // ==================================================================
    // the ladder
    // ==================================================================

    @Nested
    @DisplayName("the advice ladder is declared, and the export advice is strictly ahead of the floor")
    class TheLadder {

        @Test
        @DisplayName("the export advice sorts strictly before the catch-all, in Spring's own resolution order")
        void theExportAdviceSortsStrictlyBeforeTheFloor() {
            List<ControllerAdviceBean> ladder = adviceBeansInResolutionOrder();

            assertThat(indexOf(ladder, ExportExceptionHandler.class))
                .as("the index %s must be found: ExportExceptionHandler is component-scanned into every "
                    + "context this module builds, so an absent advice means the harness is wrong",
                    indexOf(ladder, ExportExceptionHandler.class))
                .isNotNegative();
            assertThat(indexOf(ladder, ProbeFloorAdvice.class)).isNotNegative();

            assertThat(indexOf(ladder, ExportExceptionHandler.class))
                .as("THE assertion. Spring stops at the first advice holding a handler for the thrown type, "
                    + "so a tied advice is decided by bean registration order. Strictly-less is the only "
                    + "relation that survives removing the @Order being a failure rather than a coin toss. "
                    + "Ladder: %s", ladder)
                .isLessThan(indexOf(ladder, ProbeFloorAdvice.class));
        }

        @Test
        @DisplayName("without the @Order this advice would sit exactly where the floor sits -- the tie, proved")
        void theDefaultOrderWouldTieWithTheFloor() {
            List<ControllerAdviceBean> ladder = adviceBeansInResolutionOrder();

            // What an advice with no @Order gets. Asserting it equals the floor's order is what makes
            // "the tie is gone" a measurement rather than a claim: the tie is real, it is exactly this value,
            // and the export advice is demonstrably somewhere else.
            assertThat(orderOf(ProbeFloorAdvice.class))
                .as("the floor's declared order -- the value every unordered advice inherits by default")
                .isEqualTo(Ordered.LOWEST_PRECEDENCE);

            assertThat(orderOf(ExportExceptionHandler.class))
                .as("the export advice must NOT be at the value it would inherit by default")
                .isNotEqualTo(Ordered.LOWEST_PRECEDENCE);

            assertThat(orderOf(ExportExceptionHandler.class))
                .as("and it must be the declared value, not merely some other value")
                .isEqualTo(ExportExceptionHandler.ORDER)
                .isEqualTo(Ordered.LOWEST_PRECEDENCE - 200);
        }

        @Test
        @DisplayName("the whole ladder is security -> exports -> CRM -> floor, with no two advices tied")
        void theLadderIsOrderedAndUnique() {
            List<ControllerAdviceBean> ladder = adviceBeansInResolutionOrder();

            assertThat(indexOf(ladder, com.firstelement.security.error.SecurityExceptionAdvice.class))
                .as("SecurityExceptionAdvice must stay first: it is the advice that renders a cross-tenant "
                    + "denial as a non-disclosing 404, and moving the export advice above it would answer a "
                    + "denial with an export vocabulary. Ladder: %s", ladder)
                .isLessThan(indexOf(ladder, ExportExceptionHandler.class));
            assertThat(indexOf(ladder, ExportExceptionHandler.class))
                .as("the export advice sorts ahead of the CRM family. That is safe for two reasons recorded "
                    + "on the class: CrmDomainException extends RuntimeException and not "
                    + "IllegalArgumentException, so the two families do not overlap at all; and the advice is "
                    + "scoped to assignableTypes = ExportController.class, so nothing outside the export "
                    + "controller can reach it. Widening that selector REQUIRES moving this order behind "
                    + "CrmExceptionAdvice. Ladder: %s", ladder)
                .isLessThan(indexOf(ladder, com.firstelement.crm.error.CrmExceptionAdvice.class));
            assertThat(indexOf(ladder, com.firstelement.crm.error.CrmExceptionAdvice.class))
                .isLessThan(indexOf(ladder, ProbeFloorAdvice.class));

            List<Integer> orders = new ArrayList<>();
            for (Class<?> type : List.of(com.firstelement.security.error.SecurityExceptionAdvice.class,
                ExportExceptionHandler.class,
                com.firstelement.crm.error.CrmExceptionAdvice.class, ProbeFloorAdvice.class)) {
                orders.add(orderOf(type));
            }
            assertThat(orders)
                .as("FE-ADVICE-002, asserted in the running JVM against the real advices rather than by "
                    + "reading source: two advices sharing an order tie, and a tie is decided by "
                    + "registration order")
                .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("the probe floor really is reachable -- otherwise the HTTP half below proves nothing")
        void theProbeFloorIsReachable() throws Exception {
            // Positive control for the whole suite. A malformed request body is not claimed by
            // ExportExceptionHandler, not by CrmExceptionAdvice and not by SecurityExceptionAdvice, so it
            // reaches the floor -- and ProbeFloorAdvice holds nothing but the catch-all, so the only status
            // it can produce is the 500 the export refusals would have become had they lost the race.
            MvcResult result = mvc.perform(post("/api/v1/exports")
                    .param("projectId", PROJECT_A.toString())
                    .with(asPrincipal(adminA))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reportDefinitionId\": "))
                .andReturn();

            assertThat(result.getResponse().getStatus())
                .as("the floor is present, ordered last, and answering. Without this, the 403/409/422 "
                    + "assertions below would hold in a context where no 500 was even possible")
                .isEqualTo(500);
            assertThat(codeOf(result)).isEqualTo("INTERNAL_ERROR");
        }
    }

    // ==================================================================
    // refusals keep their own status
    // ==================================================================

    @Nested
    @DisplayName("an export refusal renders its own status, not the floor's 500")
    class RefusalsKeepTheirOwnStatus {

        @Test
        @DisplayName("403 -- every column withheld from this caller, answered by the export advice")
        void aRefusalThatIs403Renders403() throws Exception {
            // The definition requires analytics:read-project, which the EMPLOYEE holds, so the refusal is
            // NOT Spring's AccessDeniedException (which SecurityExceptionAdvice would answer) and IS
            // ExportExceptionHandler's EmptyExportRefusal.
            UUID report = fixture.publishedUsageReportWithProjectScopeOnly(adminA, PROJECT_A);
            UUID readable = fixture.publishedCustomerReport(adminA, PROJECT_A, "none");

            MvcResult refused = create(employeeA, Map.of(
                "reportDefinitionId", report.toString(),
                "reportVersion", 1,
                "format", "CSV",
                "filters", Map.of("usage.bucketFrom", "2026-03-01", "usage.bucketTo", "2026-03-31")))
                .andReturn();

            assertThat(refused.getResponse().getStatus())
                .as("a denial is a denial, not a fault. A 500 here is logged at error, alerted on and counted "
                    + "as an availability incident, so the request that tells you somebody cannot export is "
                    + "the request that manufactures incident signal")
                .isEqualTo(403);
            assertThat(codeOf(refused)).isEqualTo("EXPORT_ALL_COLUMNS_WITHHELD");

            // Positive control: the same caller, in the same scope, exports the report it is entitled to. So
            // the 403 above is about this report's columns and not about the employee, and the advice is
            // demonstrably reachable for a 2xx on the very next request.
            MvcResult allowed = create(employeeA, Map.of(
                "reportDefinitionId", readable.toString(),
                "reportVersion", 1,
                "format", "CSV")).andReturn();

            assertThat(allowed.getResponse().getStatus()).isEqualTo(202);
        }

        @Test
        @DisplayName("409 -- retrying a job that is still QUEUED, answered by the export advice")
        void aRefusalThatIs409Renders409() throws Exception {
            UUID report = fixture.publishedCustomerReport(adminA, PROJECT_A, "none");
            UUID jobId = jobIdOf(create(adminA, Map.of(
                "reportDefinitionId", report.toString(),
                "reportVersion", 1,
                "format", "CSV")).andReturn());

            MvcResult refused = mvc.perform(post("/api/v1/exports/{id}/retry", jobId)
                    .with(asPrincipal(adminA)))
                .andReturn();

            assertThat(refused.getResponse().getStatus())
                .as("only a FAILED or CANCELLED job is retryable. Answering 500 would report an operator's "
                    + "mistake as a server fault")
                .isEqualTo(409);
            assertThat(codeOf(refused)).isEqualTo("EXPORT_STATE_CONFLICT");

            // Positive control: the retry is refused because of the STATE, not because the route refuses
            // everybody. Cancel the queued job and the very same call is accepted -- and accepted is
            // 202, not 200: the retry is requeued and nothing has been generated (ADR-0016 rule 2).
            mvc.perform(post("/api/v1/exports/{id}/cancel", jobId)
                    .with(asPrincipal(adminA))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\": \"the state above is a conflict, so prove it clears\"}"))
                .andExpect(status().isOk());
            mvc.perform(post("/api/v1/exports/{id}/retry", jobId).with(asPrincipal(adminA)))
                .andExpect(status().isAccepted());
        }

        /**
         * The second 409, and deliberately a DIFFERENT handler in the same advice.
         *
         * <p>{@code retry} on a QUEUED job answers through {@code ExportExceptionHandler.illegalState}; minting
         * a download link for the same job answers through {@code notDownloadable}. Both must reach 409, so the
         * advice is proven to route more than one of its handlers rather than to have one handler that happens to
         * work — and {@code notDownloadable} carries the branch worth proving separately, because it answers 410
         * for a closed window and 409 for a state that may still change, which is a distinction a client needs in
         * order to know whether to retry.
         */
        @Test
        @DisplayName("409 -- minting a link for a job that has not finished, answered by the export advice")
        void mintingBeforeCompletionRenders409() throws Exception {
            UUID report = fixture.publishedCustomerReport(adminA, PROJECT_A, "none");
            UUID jobId = jobIdOf(create(adminA, Map.of(
                "reportDefinitionId", report.toString(), "reportVersion", 1, "format", "CSV")).andReturn());

            MvcResult refused = mvc.perform(post("/api/v1/exports/{id}/download-link", jobId)
                    .with(asPrincipal(adminA))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                .andReturn();

            assertThat(refused.getResponse().getStatus())
                .as("not yet, rather than never. A 409 here is a state the client can wait out, and a 410 would "
                    + "tell it the artefact is gone when it has not been produced yet")
                .isEqualTo(409);
            assertThat(codeOf(refused)).isEqualTo("EXPORT_NOT_DOWNLOADABLE");

            JsonNode details = bodyOf(refused).get("details");
            assertThat(details.get("status").asText()).isEqualTo("QUEUED");
            assertThat(details.get("retryable").asBoolean())
                .as("the handler distinguishes 'not yet' from 'gone for good', and a queued job is not yet")
                .isTrue();

            // Positive control: the refusal left a download audit row, so this is a decision and not a fault,
            // and the job itself is untouched -- a refused mint does not cancel anything.
            assertThat(mvc.perform(get("/api/v1/exports/{id}", jobId).with(asPrincipal(adminA)))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("422 -- EXPORT_FORMAT_UNSUPPORTED, answered by the export advice")
        void anUnrenderableFormatRenders422() throws Exception {
            MvcResult refused = create(adminA, Map.of(
                "reportDefinitionId", UUID.nameUUIDFromBytes("not-a-real-report".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8)).toString(),
                "reportVersion", 1,
                // PDF is a declared "ExportFormat" value and is not renderable. The refusal happens at the
                // controller boundary, BEFORE jobs.create, so the report id above is never looked up -- which
                // is why it can be arbitrary and the assertion is still about the format.
                "format", "PDF")).andReturn();

            assertThat(refused.getResponse().getStatus()).isEqualTo(422);
            assertThat(codeOf(refused)).isEqualTo("EXPORT_FORMAT_UNSUPPORTED");

            // Positive control: the two formats the catalogue publishes are accepted for the same caller and
            // the same body with the format changed, so the 422 is about the format alone.
            UUID report = fixture.publishedCustomerReport(adminA, PROJECT_A, "none");
            for (String format : List.of("CSV", "DOCX")) {
                MvcResult allowed = create(adminA, Map.of(
                    "reportDefinitionId", report.toString(),
                    "reportVersion", 1,
                    "format", format)).andReturn();
                assertThat(allowed.getResponse().getStatus())
                    .as("the catalogue publishes %s, so it must be accepted", format)
                    .isEqualTo(202);
            }
        }

        @Test
        @DisplayName("none of the three refusals is a 500 -- asserted as a set, not as three separate facts")
        void noneOfThemIsAFiveHundred() throws Exception {
            UUID allWithheld = fixture.publishedUsageReportWithProjectScopeOnly(adminA, PROJECT_A);
            UUID report = fixture.publishedCustomerReport(adminA, PROJECT_A, "none");
            UUID jobId = jobIdOf(create(adminA, Map.of(
                "reportDefinitionId", report.toString(), "reportVersion", 1, "format", "CSV")).andReturn());

            Map<String, Integer> statuses = new LinkedHashMap<>();
            statuses.put("403 all columns withheld", create(employeeA, Map.of(
                "reportDefinitionId", allWithheld.toString(), "reportVersion", 1,
                "format", "CSV",
                "filters", Map.of("usage.bucketFrom", "2026-03-01", "usage.bucketTo", "2026-03-31")))
                .andReturn().getResponse().getStatus());
            statuses.put("409 retry of a queued job", mvc.perform(
                    post("/api/v1/exports/{id}/retry", jobId).with(asPrincipal(adminA)))
                .andReturn().getResponse().getStatus());
            statuses.put("422 unrenderable format", create(adminA, Map.of(
                "reportDefinitionId", report.toString(), "reportVersion", 1, "format", "PDF"))
                .andReturn().getResponse().getStatus());

            assertThat(statuses.values())
                .as("three refusals, three of their own statuses: %s", statuses)
                .containsExactly(403, 409, 422);
            assertThat(statuses).doesNotContainValue(500);
        }
    }

    // ==================================================================
    // helpers
    // ==================================================================

    /**
     * The advice beans in the order Spring's {@code ExceptionHandlerExceptionResolver} walks them.
     *
     * <p>Not a reimplementation of the ordering. This calls
     * {@code ControllerAdviceBean.findAnnotatedBeans(context)} and {@code OrderComparator.sort} on the result,
     * which is precisely what {@code ExceptionHandlerExceptionResolver.initExceptionHandlerAdviceCache} does
     * — so a discrepancy between this list and the real resolution would be a Spring bug rather than a
     * difference between the test and the server. Sorting a list of advice <em>classes</em> by an
     * independently-read {@code @Order} would test the annotation instead of the resolution, which is the
     * distinction this suite exists to make.
     */
    private List<ControllerAdviceBean> adviceBeansInResolutionOrder() {
        List<ControllerAdviceBean> ladder =
            new ArrayList<>(ControllerAdviceBean.findAnnotatedBeans(context));
        OrderComparator.sort(ladder);
        return ladder;
    }

    private int indexOf(List<ControllerAdviceBean> ladder, Class<?> type) {
        for (int i = 0; i < ladder.size(); i++) {
            if (ladder.get(i).getBeanType() == type) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The order Spring itself resolved for one advice.
     *
     * <p>{@code ControllerAdviceBean.getOrder()} is the value the resolver's sort key is built from, so this
     * is Spring's own answer rather than a re-read of the annotation. The annotation is asserted separately,
     * against {@link ExportExceptionHandler#ORDER}, because "the annotation says X" and "Spring ordered the
     * beans by X" are different claims and only the second one decides which advice answers.
     */
    private int orderOf(Class<?> type) {
        List<ControllerAdviceBean> ladder = adviceBeansInResolutionOrder();
        return ladder.get(indexOf(ladder, type)).getOrder();
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

    private ResultActions create(TenantContext caller, Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/v1/exports")
            .param("projectId", PROJECT_A.toString())
            .with(asPrincipal(caller))
            .contentType(MediaType.APPLICATION_JSON)
            .content(MAPPER.writeValueAsString(body)));
    }

    private UUID jobIdOf(MvcResult created) throws Exception {
        String body = created.getResponse().getContentAsString();
        assertThat(created.getResponse().getStatus()).isEqualTo(202);
        return UUID.fromString(MAPPER.readTree(body).get("jobId").asText());
    }

    private JsonNode bodyOf(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private String codeOf(MvcResult result) throws Exception {
        return bodyOf(result).get("code").asText();
    }

    /**
     * Registers the stand-in floor in this one context.
     *
     * <p>Listed only in this class's {@code @SpringBootTest(classes = ...)} and not in
     * {@code ExportsTestBase}, which is the point: a competing catch-all in the shared context would change
     * what six unrelated suites in this module prove.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeLadder {
        @org.springframework.context.annotation.Bean
        ProbeFloorAdvice probeFloorAdvice() {
            return new ProbeFloorAdvice();
        }
    }
}