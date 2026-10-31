package com.firstelement.inbox.web;

import com.firstelement.crm.clinic.ClinicAnalytics;
import com.firstelement.crm.clinic.ClinicAppointmentPage;
import com.firstelement.crm.clinic.ClinicCataloguePage;
import com.firstelement.crm.clinic.ClinicModels;
import com.firstelement.crm.clinic.ClinicReadService;
import com.firstelement.crm.query.CursorPage;
import com.firstelement.security.TenantContextHolder;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.permission.Permission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/clinic} — the read-only clinic surface.
 *
 * <pre>
 *   GET /api/v1/clinic/doctors              DOCTOR_READ
 *   GET /api/v1/clinic/doctors/{doctorId}   DOCTOR_READ
 *   GET /api/v1/clinic/procedures           PROCEDURE_READ
 *   GET /api/v1/clinic/procedures/{id}      PROCEDURE_READ
 *   GET /api/v1/clinic/appointments         APPOINTMENT_READ
 *   GET /api/v1/clinic/appointments/{id}    APPOINTMENT_READ
 *   GET /api/v1/clinic/analytics            ANALYTICS_READ_PROJECT
 * </pre>
 *
 * <h2>Read-only, deliberately</h2>
 *
 * <p>The legacy runtime's clinic module had 22 non-public handlers (plus 2 more on a separate public
 * appointment controller, 24 in total) and this has seven, all of
 * them {@code GET}. That is the bounded slice, and it is bounded for a reason: the legacy
 * surface carried the write path that the 2026-10-08 cross-tenant breach reached — including
 * {@code POST /clinic/doctors}, whose handler spread {@code ...body} <em>after</em>
 * {@code organizationId} so a caller overrode the tenant outright. Re-migrating that write
 * path is a separate mission with its own permission set, and shipping it inside a read slice
 * would put it behind a smaller review than it deserves.
 *
 * <h2>The scope rule, stated on the class because it is the whole point</h2>
 *
 * <p>Every {@code projectId} below arrives as a {@link RequestParam}. It is parsed by
 * {@link RequestParams#optionalUuid} and handed to
 * {@code TenantQueryGuard.scope(caller, requestedProjectId)}, which compares it against the
 * principal's <em>server-resolved</em> project list and refuses with a non-disclosing
 * {@code 404} when it is absent. This is the fix for the breach: a project named in a query
 * string produced an <b>empty</b> scope in the legacy runtime, and the handler was then the
 * only layer left. Here the guard runs before any SQL is assembled, so a refused project
 * never reaches the database and cannot be distinguished from an empty one by timing or row
 * count.
 *
 * <p>Absent {@code projectId} means "every project I may reach" — never "every project". A
 * principal with no memberships receives {@code 1 = 0}, not the tenant.
 *
 * <h2>Refusals, and why each one is reachable</h2>
 *
 * <p>{@code RefusalRules} derives these from the routes' own shape rather than from a pasted
 * list: every route is non-public (401), every route is permission-guarded (403), and every
 * {@code /{id}} route can answer 404 — which is also the non-disclosing refusal for a
 * <em>foreign</em> id, so the two are one code rather than two.
 *
 * <p>Nothing here declares a {@code 400} that the route cannot produce, and nothing declares a
 * {@code 404} on a list route that has nothing to look up. That restraint is the ADR 0016 rule
 * applied to refusals: a declared refusal a route cannot produce is the same lie as a wrong
 * success code.
 */
@RestController
@RequestMapping("/api/v1/clinic")
@Tag(name = "Clinic", description = "Read-only clinic roster, catalogue, schedule and analytics.")
public class ClinicController {

    private final ClinicReadService clinic;

    public ClinicController(ClinicReadService clinic) {
        this.clinic = clinic;
    }

    // ==================================================================
    // roster
    // ==================================================================

    /**
     * {@code GET /api/v1/clinic/doctors}
     *
     * <p>The bookable roster: name, specialty, department, working days, working hours and slot
     * length. Deliberately <b>not</b> the clinician's email or phone — Phase 33 split those
     * behind {@code doctor:read-management}, and this route declares {@code doctor:read}.
     */
    @GetMapping("/doctors")
    @RequirePermission(Permission.DOCTOR_READ)
    @Operation(summary = "The clinician roster",
        description = "Clinicians in the caller's server-resolved scope. Exposes working days, "
            + "working hours and slot length — what a booking form needs — and NOT clinician "
            + "contact details, which are behind the separate doctor:read-management grant. "
            + "A projectId outside the caller's membership is a non-disclosing 404.")
    public ClinicCataloguePage<ClinicModels.Doctor> doctors(
            @Parameter(description = "Narrow to one project. A request, not an authorization: "
                + "an id outside the caller's membership is refused with the same 404 a "
                + "nonexistent project produces.")
            @RequestParam(required = false) String projectId) {
        return ClinicCataloguePage.of(clinic.doctors(TenantContextHolder.get(),
            RequestParams.optionalUuid(projectId, "projectId")));
    }

    /**
     * {@code GET /api/v1/clinic/doctors/{doctorId}}
     *
     * <p>A foreign id and a nonexistent id produce the same status, the same code and the same
     * message. The lookup is tenant-scoped and a miss is a miss, so there is no branch that
     * could tell them apart — the property Phase 33 closed in the legacy tree.
     */
    @GetMapping("/doctors/{doctorId}")
    @RequirePermission(Permission.DOCTOR_READ)
    @Operation(summary = "One clinician",
        description = "Answered with 404 for a nonexistent clinician, for one belonging to "
            + "another tenant, and for one in a project the caller has left. All three are the "
            + "same status, code and message.")
    public ClinicModels.Doctor doctor(@PathVariable UUID doctorId) {
        return clinic.doctor(TenantContextHolder.get(), doctorId)
            .orElseThrow(() -> new ResourceNotFound());
    }

    // ==================================================================
    // catalogue
    // ==================================================================

    /** {@code GET /api/v1/clinic/procedures} — the procedure catalogue. */
    @GetMapping("/procedures")
    @RequirePermission(Permission.PROCEDURE_READ)
    @Operation(summary = "The procedure catalogue",
        description = "Procedures in the caller's server-resolved scope, with their stored "
            + "duration, buffer and catalogue price. price is nullable and null means "
            + "'not priced', which is not the same as zero. Nothing in this API multiplies it "
            + "by a booking count: revenue is a finance decision (ADR 0007 Part 1).")
    public ClinicCataloguePage<ClinicModels.Procedure> procedures(
            @RequestParam(required = false) String projectId) {
        return ClinicCataloguePage.of(clinic.procedures(TenantContextHolder.get(),
            RequestParams.optionalUuid(projectId, "projectId")));
    }

    /** {@code GET /api/v1/clinic/procedures/{procedureId}} — one procedure. */
    @GetMapping("/procedures/{procedureId}")
    @RequirePermission(Permission.PROCEDURE_READ)
    @Operation(summary = "One procedure",
        description = "Answered with 404 for a nonexistent procedure and for one outside the "
            + "caller's scope, identically.")
    public ClinicModels.Procedure procedure(@PathVariable UUID procedureId) {
        return clinic.procedure(TenantContextHolder.get(), procedureId)
            .orElseThrow(() -> new ResourceNotFound());
    }

    // ==================================================================
    // schedule
    // ==================================================================

    /**
     * {@code GET /api/v1/clinic/appointments} — the schedule.
     *
     * <p>Keyset-paginated on {@code ("appointmentDate" desc, "id" desc)} with the mandatory
     * tie-break: two appointments at the same instant would otherwise be ordered arbitrarily
     * between pages, so one is returned twice and another is skipped.
     *
     * <p>{@code from} and {@code to} are required in practice — the service defaults them and
     * the resolved pair is what the query binds — but they are published on every response so
     * a reader always knows which window produced a list.
     */
    @GetMapping("/appointments")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "The appointment schedule",
        description = "Cursor-paginated appointments in the caller's scope. Patient name, phone "
            + "and email are projected because the appointment list identifies the patient and "
            + "the confirmation channel; they are the same contact values the patient register "
            + "already publishes under appointment:read. Clinical notes and AI proposal reasoning "
            + "are NOT projected. Optional status, paymentStatus and q filters are refused when "
            + "unknown, never silently dropped.")
    public ClinicAppointmentPage appointments(
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @Parameter(description = "A stored appointment status. An unrecognised value is a "
                + "400 naming the accepted set, never silently ignored — a dropped filter widens "
                + "the result and the widened result looks exactly like an honest one.")
            @RequestParam(required = false) String status,
            @Parameter(description = "PAID, UNPAID, PARTIAL or REFUNDED. An unrecognised value "
                + "is a 400, never a silently dropped filter.")
            @RequestParam(required = false) String paymentStatus,
            @Parameter(description = "Free text over patient, phone, email, doctor, service and "
                + "ticket number.")
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String cursor,
            @RequestParam(name = "limit", required = false) @BoundedLimit String limit) {
        ClinicWindow window = ClinicWindow.resolve(from, to);
        CursorPage<ClinicModels.Appointment> page = clinic.appointments(
            TenantContextHolder.get(),
            RequestParams.optionalUuid(projectId, "projectId"),
            window.from(), window.to(), status, paymentStatus, q,
            RequestParams.optionalUuid(cursor, "cursor"),
            RequestParams.limit(limit, ClinicReadService.Limits.SCHEDULE_PAGE,
                ClinicReadService.Limits.SCHEDULE_PAGE));

        // A typed page, not a map. The first draft returned Map.of(...) and the document
        // published every list response as a free-form string map -- the same contract defect ADR
        // 0016 was raised about, reached the other way round. The window rides on the page so a
        // reader can reconcile it against the metrics, which publish theirs.
        return new ClinicAppointmentPage(page.items(), page.items().size(),
            page.nextCursor(), window.from(), window.to());
    }

    /** {@code GET /api/v1/clinic/appointments/{appointmentId}} — one appointment. */
    @GetMapping("/appointments/{appointmentId}")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "One appointment",
        description = "Answered with 404 for a nonexistent appointment, for one belonging to "
            + "another tenant, and for one in a project the caller has left — identically.")
    public ClinicModels.Appointment appointment(@PathVariable UUID appointmentId) {
        return clinic.appointment(TenantContextHolder.get(), appointmentId)
            .orElseThrow(() -> new ResourceNotFound());
    }

    // ==================================================================
    // analytics
    // ==================================================================

    /**
     * {@code GET /api/v1/clinic/analytics}
     *
     * <p>Three metric groups, each returning the appointment ids it counted. The response
     * carries the resolved window, the source table and a derivation sentence for every group,
     * plus a {@code drillThroughPath} that reproduces the same filters against the source rows —
     * so a reader goes from the number to the rows without leaving the response.
     *
     * <p>There is no revenue, no conversion rate, no satisfaction score and no utilization
     * percentage here. Each of those was a hard-coded literal in the legacy service, or a
     * commercial definition this module is not entitled to invent. See
     * {@code ClinicReadService#clinicAnalytics} for which is which.
     */
    @GetMapping("/analytics")
    @RequirePermission(Permission.ANALYTICS_READ_PROJECT)
    @Operation(summary = "Clinic analytics, each metric carrying its source rows",
        description = "Appointment funnel by status, procedure demand and clinician workload. "
            + "Every count is computed in the same statement that collects the appointment ids "
            + "behind it, so the number and the rows cannot come from different predicates. "
            + "Each group publishes its source table, its window, the sample limit that was "
            + "applied, and a drill-through path. A sample is always distinguishable from a "
            + "complete population: sourceRowCount is the unbounded truth.")
    public ClinicAnalytics analytics(
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        ClinicWindow window = ClinicWindow.resolve(from, to);
        return clinic.clinicAnalytics(TenantContextHolder.get(),
            RequestParams.optionalUuid(projectId, "projectId"),
            window.from(), window.to());
    }

    // ==================================================================

    /**
     * The uniform 404.
     *
     * <p>{@code CrmExceptionAdvice} renders {@link com.firstelement.crm.error
     * .ResourceNotFoundException} as {@code 404 {code: NOT_FOUND, message: 'Resource not found'}}
     * — byte-identical to what {@code SecurityExceptionAdvice} produces for a
     * {@code PolicyDeniedException}, which is the <em>same</em> body a cross-tenant denial
     * gets. That identity is the whole non-disclosure property.
     *
     * <p>It is not {@code "Doctor not found"}. Phase 26 found that a per-resource message is
     * itself an existence oracle: it separates "no such doctor anywhere" from "that doctor is
     * not yours" from "you may not ask", and on a surface carrying patient names and phone
     * numbers that is the difference between a refusal and a directory of who is a patient.
     */
    private static final class ResourceNotFound
            extends com.firstelement.crm.error.ResourceNotFoundException {

        private static final long serialVersionUID = 1L;

        ResourceNotFound() {
            super("NOT_FOUND", "Resource not found", java.util.Map.of());
        }
    }

    /**
     * Why the path variables are typed {@code UUID} and what that costs the contract.
     *
     * <p>An id that is not a UUID raises {@code MethodArgumentTypeMismatchException}, which
     * {@code CrmExceptionAdvice} renders as {@code 400 VALIDATION_FAILED}. That is the honest
     * answer to a malformed id, and it is why the parameter is declared {@code UUID} rather than
     * {@code String}: springdoc then publishes the variable as <b>required</b>, {@code RefusalRules}
     * derives a 400 from that, and the route really does produce one. Declared and observed agree.
     *
     * <p>The first draft parsed a {@code String} here and answered 404 for an unparseable value.
     * That was tidier — one non-disclosing answer for "not a UUID", "not yours" and "no such
     * record" — and it was <b>wrong</b>: a required query/path parameter is what makes
     * {@code RefusalRules} stamp a 400, so the document went on declaring a 400 the route could
     * never produce. That is ADR 0016 rule 3's principle applied to refusals, and the contract
     * suite caught it by reading the served document rather than by reasoning about the route.
     *
     * <p>What is <b>not</b> given up is the property that matters. A foreign id and a nonexistent
     * id still produce byte-identical 404s: the lookup is tenant-scoped and a miss is a miss, so
     * no branch distinguishes them. {@code ClinicTenantIsolationHttpIntegrationTest} asserts that
     * identity, and it is unaffected by how the id was parsed.
     */
    /**
     * The analytics/schedule window, resolved once and published on every response.
     *
     * <p>A default exists so the route is callable bare, but the resolved pair is always
     * echoed. A figure whose window a reader cannot name is a figure they cannot reconcile
     * against the rows, which is the defect this whole read model exists to remove.
     */
    record ClinicWindow(LocalDateTime from, LocalDateTime to) {

        static ClinicWindow resolve(String from, String to) {
            LocalDateTime upper = parse("to", to, LocalDateTime.now(java.time.Clock.systemUTC())
                .withNano(0));
            LocalDateTime lower = parse("from", from, upper.minusDays(
                ClinicReadService.Limits.DEFAULT_WINDOW_DAYS));
            if (!lower.isBefore(upper)) {
                throw new com.firstelement.crm.error.ValidationFailedException(
                    "from must be strictly before to, or the window is empty",
                    java.util.Map.of("field", "from", "from", lower.toString(),
                        "to", upper.toString()));
            }
            if (java.time.Duration.between(lower, upper).toDays()
                > ClinicReadService.Limits.MAX_WINDOW_DAYS) {
                throw new com.firstelement.crm.error.ValidationFailedException(
                    "the window may not exceed " + ClinicReadService.Limits.MAX_WINDOW_DAYS + " days",
                    java.util.Map.of("field", "to",
                        "maxWindowDays", ClinicReadService.Limits.MAX_WINDOW_DAYS));
            }
            return new ClinicWindow(lower, upper);
        }

        /**
         * A window boundary, or a 400 naming the parameter and its expected form.
         *
         * <p>A bare {@code IllegalArgumentException} here would be a 500 in every module whose
         * test application does not carry {@code app-bootstrap}'s advice, and a 400 that names
         * only the problem in the real one. {@code CrmDomainException} is the type every advice in
         * the application agrees on, so the refusal is the same wherever this is called.
         */
        private static LocalDateTime parse(String field, String raw, LocalDateTime fallback) {
            if (raw == null || raw.isBlank()) {
                return fallback;
            }
            try {
                return com.firstelement.crm.api.Timestamps.parseStored(raw.trim());
            } catch (IllegalArgumentException e) {
                throw new com.firstelement.crm.error.ValidationFailedException(
                    field + " must be an ISO-8601 instant such as 2026-08-14T00:00:00.000Z",
                    java.util.Map.of("field", field, "expectedType", "ISO-8601 timestamp"));
            }
        }
    }
}