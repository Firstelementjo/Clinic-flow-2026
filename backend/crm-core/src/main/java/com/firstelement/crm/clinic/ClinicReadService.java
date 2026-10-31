package com.firstelement.crm.clinic;

import com.firstelement.crm.query.CursorPage;
import com.firstelement.security.TenantContext;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.permission.AuthorizationService;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.permission.PolicyResource;
import com.firstelement.security.query.TenantQueryGuard;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The clinic read service: doctors, procedures, appointments, and the analytics derived from
 * them.
 *
 * <h2>Where authorization happens, and why it is here rather than in the controller</h2>
 *
 * <p>{@code plan.md} §0.4: "No controller-only authorization. Enforce tenant, role, row, field,
 * and action policy in Spring services/repositories." Every method below therefore carries
 * {@link RequirePermission} <em>and</em> calls {@link AuthorizationService} explicitly, and
 * both are load-bearing:
 *
 * <ul>
 *   <li>the annotation is what makes the requirement <b>reviewable at the HTTP edge</b>, which is
 *       why {@code ClinicRoutePermissionTest} can assert every handler declares one;</li>
 *   <li>the explicit {@code check} is what makes it <b>unbypassable</b> — a future caller
 *       reaching this service from a job, a report or an export gets the same decision without
 *       having to remember the annotation.</li>
 * </ul>
 *
 * <h2>Why the scope is derived, never defaulted</h2>
 *
 * <p>Every method takes a {@code TenantContext} and builds its {@link ClinicScope} through
 * {@code forCaller}, {@code forRequestedProject} or {@code forRow}. A caller-supplied
 * {@code projectId} is a <b>request</b>: it goes through {@link TenantQueryGuard#scope} and is
 * refused with a non-disclosing 404 when it is not in the principal's server-resolved list.
 * There is no overload that accepts a project id without that comparison — which is the whole
 * point, and is the lesson of the 2026-10-08 cross-tenant breach (migration ledger §10), where
 * a project named in a query string or a body produced an <em>empty</em> scope that reached
 * every tenant's rows.
 *
 * <h2>Why nothing here writes</h2>
 *
 * <p>There is no mutation in this service and no permission that would authorize one. The
 * bounded slice is read-only, and a read module that acquires a write later acquires a write
 * whose tenant scope has been quietly assumed rather than checked.
 */
@Service
public class ClinicReadService {

    /**
     * Page bounds.
     *
     * <p>Bounded because an unbounded {@code select} against a tenant with a million
     * appointments is how the schedule page becomes the outage it was built to diagnose. These
     * are collected in one place so a deployment has a single answer to "how large can a
     * response get".
     */
    public static final class Limits {

        private Limits() {
        }

        /** The roster and the catalogue. Both are small, fixed-size catalogues. */
        public static final int CATALOGUE_PAGE = 200;

        /** The schedule. Matches the inbox page bound so a client has one page size to learn. */
        public static final int SCHEDULE_PAGE = 50;

        /**
         * How many source ids ride on each metric row.
         *
         * <p>Small on purpose: this is a sample that proves a number is traceable, not a bulk
         * export. A reader who needs the rest follows {@code drillThroughPath} with its own
         * filters.
         */
        public static final int METRIC_SAMPLE = 50;

        /** How many procedures / clinicians one metric group returns. */
        public static final int METRIC_GROUP_LIMIT = 50;

        /**
         * The default analytics window, in days, ending now.
         *
         * <p>A default exists so the route is callable without arguments, but the resolved
         * window is always <b>echoed in the response</b>. A metric with an unstated window is a
         * number whose denominator nobody can name.
         */
        public static final int DEFAULT_WINDOW_DAYS = 30;

        /**
         * The widest window a caller may ask for. Bounds a query that would otherwise scan a
         * decade.
         *
         * <p>731, not 366: the appointment list opens on today -365 through today +365 so a
         * booking desk can see last year's history and next year's bookings in one view. A
         * 366-day cap made that default a 400 before it ever reached SQL.
         */
        public static final int MAX_WINDOW_DAYS = 731;
    }

    private final ClinicRepository repository;
    private final TenantQueryGuard guard;
    private final AuthorizationService authorization;

    public ClinicReadService(ClinicRepository repository, TenantQueryGuard guard,
                             AuthorizationService authorization) {
        this.repository = repository;
        this.guard = guard;
        this.authorization = authorization;
    }

    // ==================================================================
    // roster
    // ==================================================================

    /**
     * The clinician roster.
     *
     * <p>A null {@code projectId} means "every project I may reach", which for a principal with
     * no memberships is an empty result rather than the whole tenant.
     */
    @RequirePermission(Permission.DOCTOR_READ)
    @Transactional(readOnly = true)
    public List<ClinicModels.Doctor> doctors(TenantContext caller, UUID requestedProjectId) {
        require(caller, Permission.DOCTOR_READ);
        return repository.doctors(
            ClinicScope.forRequestedProject(guard, caller, requestedProjectId),
            Limits.CATALOGUE_PAGE);
    }

    /**
     * One clinician.
     *
     * <p>Returns empty for a foreign clinician, a nonexistent id and an id belonging to a
     * project the caller left. All three produce the same 404 with the same body, because the
     * lookup is tenant-scoped and a miss is a miss — there is no branch here that could tell
     * them apart.
     */
    @RequirePermission(Permission.DOCTOR_READ)
    @Transactional(readOnly = true)
    public Optional<ClinicModels.Doctor> doctor(TenantContext caller, UUID doctorId) {
        require(caller, Permission.DOCTOR_READ);
        ClinicScope scope = ClinicScope.forCaller(caller);
        Optional<ClinicModels.Doctor> found = repository.doctor(scope, doctorId);
        // Second check, on the row's own scope. The lookup was already tenant-scoped; this
        // exists so a row reached by any other path still fails closed rather than being
        // returned because some earlier layer assumed the scope was right.
        found.ifPresent(doctor -> ClinicScope.forRow(caller, doctor.organizationId(), doctor.projectId()));
        return found;
    }

    // ==================================================================
    // catalogue
    // ==================================================================

    /** The procedure catalogue. */
    @RequirePermission(Permission.PROCEDURE_READ)
    @Transactional(readOnly = true)
    public List<ClinicModels.Procedure> procedures(TenantContext caller, UUID requestedProjectId) {
        require(caller, Permission.PROCEDURE_READ);
        return repository.procedures(
            ClinicScope.forRequestedProject(guard, caller, requestedProjectId),
            Limits.CATALOGUE_PAGE);
    }

    /** One procedure, or empty. */
    @RequirePermission(Permission.PROCEDURE_READ)
    @Transactional(readOnly = true)
    public Optional<ClinicModels.Procedure> procedure(TenantContext caller, UUID procedureId) {
        require(caller, Permission.PROCEDURE_READ);
        ClinicScope scope = ClinicScope.forCaller(caller);
        Optional<ClinicModels.Procedure> found = repository.procedure(scope, procedureId);
        found.ifPresent(procedure ->
            ClinicScope.forRow(caller, procedure.organizationId(), procedure.projectId()));
        return found;
    }

    // ==================================================================
    // schedule
    // ==================================================================

    /**
     * One page of the appointment schedule.
     *
     * @param status an optional stored status; an unknown value is <b>refused</b>, never
     *               silently dropped, because a dropped filter widens the result set and the
     *               widened set looks exactly like an honest one
     */
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Transactional(readOnly = true)
    public CursorPage<ClinicModels.Appointment> appointments(TenantContext caller,
                                                              UUID requestedProjectId,
                                                              LocalDateTime from,
                                                              LocalDateTime to,
                                                              String status,
                                                              UUID cursor,
                                                              int limit) {
        return appointments(caller, requestedProjectId, from, to, status, null, null, cursor, limit);
    }

    /** The appointment list, with the payment and free-text filters the screen offers. */
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Transactional(readOnly = true)
    public CursorPage<ClinicModels.Appointment> appointments(TenantContext caller,
                                                              UUID requestedProjectId,
                                                              LocalDateTime from,
                                                              LocalDateTime to,
                                                              String status,
                                                              String paymentStatus,
                                                              String search,
                                                              UUID cursor,
                                                              int limit) {
        require(caller, Permission.APPOINTMENT_READ);
        ClinicScope scope = ClinicScope.forRequestedProject(guard, caller, requestedProjectId);
        // Parsed ONCE. Calling parse twice was harmless when it returned null and threw when it
        // did not, but a caller that sent an unknown status would have been refused on the first
        // call anyway -- the double call only made the failure path harder to read.
        ClinicModels.AppointmentStatus parsed = ClinicModels.AppointmentStatus.parse(status);
        String storedStatus = parsed == null ? null : parsed.wireValue();
        ClinicModels.PaymentStatus parsedPayment = ClinicModels.PaymentStatus.parse(paymentStatus);
        String storedPayment = parsedPayment == null ? null : parsedPayment.wireValue();

        Optional<ClinicScope.CursorBoundary> boundary = cursor == null
            ? Optional.empty()
            : repository.cursorBoundary(scope, cursor);

        List<ClinicModels.Appointment> overFetched = repository.appointments(
            scope, from, to, storedStatus, storedPayment, search, boundary, limit + 1);
        return CursorPage.of(overFetched, limit, appointment -> appointment.id().toString());
    }

    /** One appointment, or empty. */
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Transactional(readOnly = true)
    public Optional<ClinicModels.Appointment> appointment(TenantContext caller, UUID appointmentId) {
        require(caller, Permission.APPOINTMENT_READ);
        ClinicScope scope = ClinicScope.forCaller(caller);
        return repository.appointment(scope, appointmentId)
            .map(appointment -> {
                // Second check, on the row's own scope. The lookup was already tenant-scoped; this
                // exists so a row reached by any other path still fails closed rather than being
                // returned because some earlier layer assumed the scope was right.
                ClinicScope.forRow(caller, appointment.organizationId(), appointment.projectId());
                return appointment;
            });
    }

    // ==================================================================
    // analytics
    // ==================================================================

    /**
     * The clinic analytics read model.
     *
     * <p>Three metric groups, each derived from {@code appointments} and each returning the
     * rows it counted. This is ADR 0013 §1.2's requirement, and it is bounded on purpose: a
     * small set of metrics that each drill to source rows beats a wide set that cannot.
     *
     * <h2>What is deliberately absent, and it is a stop condition not an omission</h2>
     *
     * <ul>
     *   <li><b>Revenue.</b> {@code procedures.price} exists and the legacy service multiplied
     *       it by a booking count to publish {@code estimatedRevenueJod}, then floored the
     *       result at 4850. ADR 0007 Part 1 decides that revenue is derived through a
     *       <em>versioned rate card</em>, not from a column on a procedure row — so a revenue
     *       figure here would need a finance ruling this module cannot make from the code.</li>
     *   <li><b>AI conversion rate.</b> Published as {@code Math.max(75, rawAiRate)}. The floor
     *       is a constant reported as an observation. The underlying fact — how many bookings
     *       were AI-proposed — <em>is</em> publishable, and it is exposed as the
     *       {@code isAiProposed} flag on each appointment, so a reader can compute the ratio
     *       themselves and see the denominator.</li>
     *   <li><b>Confirmation rate, no-show mitigation, saved hours, AI response time, patient
     *       satisfaction, daily trends.</b> Every one was a hard-coded literal or a fabricated
     *       series in the legacy service. There is no row behind any of them.</li>
     *   <li><b>Clinician utilization.</b> See {@link MetricRows.DoctorWorkloadRow}.</li>
     * </ul>
     *
     * <p>Each of those is reported to the orchestrator as a decision needed rather than
     * guessed at, because inventing the semantics is how a dashboard ends up asserting a
     * number nobody can defend.
     */
    @RequirePermission(Permission.ANALYTICS_READ_PROJECT)
    @Transactional(readOnly = true)
    public ClinicAnalytics clinicAnalytics(TenantContext caller, UUID requestedProjectId,
                                           LocalDateTime from, LocalDateTime to) {
        require(caller, Permission.ANALYTICS_READ_PROJECT);
        ClinicScope scope = ClinicScope.forRequestedProject(guard, caller, requestedProjectId);
        return ClinicAnalytics.of(from, to,
            repository.funnelByStatus(scope, from, to, Limits.METRIC_SAMPLE),
            repository.demandByProcedure(scope, from, to, Limits.METRIC_SAMPLE,
                Limits.METRIC_GROUP_LIMIT),
            repository.workloadByDoctor(scope, from, to, Limits.METRIC_SAMPLE,
                Limits.METRIC_GROUP_LIMIT),
            Limits.METRIC_SAMPLE);
    }

    // ------------------------------------------------------------------

    /**
     * The explicit grant check.
     *
     * <p>{@code PolicyResource.NONE} because a missing grant is a <b>403</b> — there is no
     * object involved and therefore nothing to disclose. Scope denials are the opposite case
     * and are raised as {@link com.firstelement.security.error.PolicyDeniedException} by
     * {@link ClinicScope}, which renders as a non-disclosing 404. Keeping the two apart is
     * the whole design: conflating them either turns a legitimate refusal into a 500 or
     * makes a 404 carry the fact that a row exists somewhere.
     */
    private void require(TenantContext caller, Permission permission) {
        if (caller == null) {
            throw new IllegalStateException(
                "No TenantContext is bound; refusing to serve a clinic read unscoped.");
        }
        authorization.check(caller, permission, PolicyResource.NONE);
    }
}