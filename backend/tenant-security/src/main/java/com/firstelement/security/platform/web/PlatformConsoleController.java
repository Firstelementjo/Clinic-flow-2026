package com.firstelement.security.platform.web;

import com.firstelement.config.FirstElementProperties;
import com.firstelement.security.config.SecurityProperties;
import com.firstelement.security.platform.PlatformAuditRepository;
import com.firstelement.security.platform.PlatformAuthService;
import com.firstelement.security.platform.PlatformCapability;
import com.firstelement.security.platform.PlatformClinicService;
import com.firstelement.security.platform.PlatformOperatorAuthenticationFilter;
import com.firstelement.security.platform.PlatformOperatorContext;
import com.firstelement.security.platform.PlatformOperatorContextHolder;
import com.firstelement.security.platform.PlatformOperatorRepository;
import com.firstelement.security.platform.SubscriptionPaymentService;
import com.firstelement.security.platform.SubscriptionStatusTransitionService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * <h2>Why this class keeps its stereotype while its collaborators do not</h2>
 *
 * <p>{@code @RestController} is load-bearing here and must not be removed. On Spring Framework 6.2
 * a controller registered by a plain {@code @Bean} method is not treated as a handler, so its routes
 * silently never register and every request 404s with no other symptom -- verified by dumping the
 * mapping table: the bean exists, carries {@code @RequestMapping}, is not proxied, and still has no
 * mappings. So the stereotype stays and component scanning owns this class.
 *
 * <p>That is only safe because the class is gated by {@code @ConditionalOnProperty}, which component
 * scanning does honour. Scanned, gated this way, it appears when the console is enabled and is absent
 * entirely when it is not -- so {@code /api/v1/platform/**} has no handler in a deployment that never
 * opted in. {@code PlatformConsoleDisabledTest} asserts both halves.
 *
 * <h2>Authentication here is not the application's authentication</h2>
 *
 * <p>These endpoints sit under {@code /api/v1/platform/**}, read a different cookie, resolve to a
 * different principal type, and are authorised against {@link PlatformCapability} rather than
 * {@code Permission}. A customer session cannot reach them: the platform filter never reads the
 * customer cookie, and the customer filter never runs here in a way that would set a platform
 * principal.
 *
 * <h2>What is deliberately absent</h2>
 *
 * <p>No endpoint executes SQL, runs a shell, or returns a secret. There is no such capability to
 * hold, so there is nothing to check at the route. The three steps are login → second factor →
 * elevation, and a caller that stops after the second has read-only reach.
 */
@RestController
@ConditionalOnProperty(prefix = "firstelement.platform.console", name = "enabled", havingValue = "true")
@RequestMapping("/api/v1/platform")
public class PlatformConsoleController {

    private final PlatformAuthService auth;
    private final PlatformOperatorRepository operators;
    private final PlatformClinicService clinics;
    private final PlatformAuditRepository audit;
    private final SecurityProperties securityProperties;
    private final SubscriptionPaymentService paymentService;
    private final SubscriptionStatusTransitionService transitionService;
    private final com.firstelement.security.platform.PlatformPlanService planService;
    private final com.firstelement.security.platform.PlatformSupportAccessService supportAccessService;
    private final com.firstelement.security.platform.PlatformDashboardService dashboardService;
    private final boolean production;

    public PlatformConsoleController(PlatformAuthService auth,
                                     PlatformOperatorRepository operators,
                                     PlatformClinicService clinics,
                                     PlatformAuditRepository audit,
                                     SecurityProperties securityProperties,
                                     SubscriptionPaymentService paymentService,
                                     SubscriptionStatusTransitionService transitionService,
                                     com.firstelement.security.platform.PlatformPlanService planService,
                                     com.firstelement.security.platform.PlatformSupportAccessService supportAccessService,
                                     com.firstelement.security.platform.PlatformDashboardService dashboardService,
                                     FirstElementProperties applicationProperties) {
        this.auth = auth;
        this.operators = operators;
        this.clinics = clinics;
        this.audit = audit;
        this.securityProperties = securityProperties;
        this.paymentService = paymentService;
        this.transitionService = transitionService;
        this.planService = planService;
        this.supportAccessService = supportAccessService;
        this.dashboardService = dashboardService;
        this.production = applicationProperties.getEnvironment()
                == FirstElementProperties.Environment.PRODUCTION;
    }

    // ------------------------------------------------------------------
    // step 1 and 2: password, then second factor
    // ------------------------------------------------------------------

    /** Step one. Returns a session that carries no authority until step two completes. */
    public record PasswordStep(String email, String password) {
    }

    @PostMapping("/auth/password")
    public ResponseEntity<Void> startLogin(@RequestBody PasswordStep request, HttpServletRequest http) {
        PlatformAuthService.PendingLogin pending = auth.beginLogin(
                request.email(), request.password(), userAgent(http), currentIp(http), Instant.now());

        // Returned as a Set-Cookie header, not as a body. A ResponseCookie returned from a handler
        // is serialised as JSON -- it does not become a cookie -- so the pending token has to be put
        // on the header explicitly. The pending token is useless on its own: a session with no
        // satisfied second factor resolves to no operator at all.
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE,
                        platformCookie(pending.rawToken(), PlatformAuthService.PENDING_SESSION_TTL).toString())
                .build();
    }

    /** Step two. With this, the operator may read the platform. Not yet write. */
    public record SecondFactorStep(String code) {
    }

    public record OperatorView(String email, String displayName, boolean elevated,
                               Instant elevatedUntil, List<String> capabilities) {
    }

    @PostMapping("/auth/second-factor")
    public OperatorView completeLogin(@RequestBody SecondFactorStep request, HttpServletRequest http) {
        String token = readPlatformCookie(http);
        PlatformAuthService.Authenticated authenticated = auth.completeLogin(token, request.code(),
                Instant.now());
        PlatformOperatorContextHolder.set(authenticated.operator());
        return view(authenticated.operator());
    }

    /** Step three. With this, and for fifteen minutes, the operator may write. */
    public record ElevationRequest(String reason, String code) {
    }

    @PostMapping("/auth/elevate")
    public OperatorView elevate(@RequestBody ElevationRequest request) {
        PlatformOperatorContext operator = PlatformOperatorContextHolder.require();
        if (!operator.has(PlatformCapability.READ_PLATFORM_OVERVIEW)) {
            // The console exists to be operated; an operator who cannot read the platform has no
            // business elevating to write it.
            throw new PlatformOperatorContextHolder.PlatformAccessDenied(
                    "READ_PLATFORM_OVERVIEW is required before elevation.");
        }
        // The code is required: a fresh second factor, every time. See PlatformAuthService.elevate.
        return view(auth.elevate(operator.sessionId(), request.reason(), request.code(),
                Instant.now()));
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout() {
        PlatformOperatorContext operator = PlatformOperatorContextHolder.current().orElse(null);
        if (operator != null) {
            auth.logout(operator.sessionId(), operator);
        }
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE,
                        platformCookie("", java.time.Duration.ZERO).toString())
                .build();
    }

    @GetMapping("/auth/me")
    public OperatorView me() {
        return view(PlatformOperatorContextHolder.require());
    }

    // ------------------------------------------------------------------
    // reading the platform
    // ------------------------------------------------------------------

    /**
     * Platform operator dashboard overview: KPIs, Needs Attention triage feed,
     * monthly platform subscription revenue, clinic status breakdown, AI usage vs quota,
     * and recent audit stream.
     */
    @GetMapping("/dashboard/overview")
    public com.firstelement.security.platform.PlatformDashboardService.OperatorDashboardOverviewDto dashboardOverview() {
        PlatformOperatorContext operator = PlatformOperatorContextHolder.require();
        if (!operator.permits(PlatformCapability.READ_PLATFORM_OVERVIEW, Instant.now())) {
            audit.record(PlatformAuditRepository.CAPABILITY_DENIED, operator.operatorId(),
                    operator.sessionId(), null, null, "PlatformDashboard", null, "viewing dashboard overview",
                    "required=READ_PLATFORM_OVERVIEW", PlatformAuditRepository.DENIED,
                    MDC.get("correlationId"), null);
            throw new PlatformOperatorContextHolder.PlatformAccessDenied(
                    "READ_PLATFORM_OVERVIEW is required.");
        }
        return dashboardService.getOverview(operator, MDC.get("correlationId"));
    }

    /** Every clinic in the deployment. Identifiers, names and counts; never clinical content. */
    @GetMapping("/clinics")
    public List<Map<String, Object>> clinics() {
        PlatformOperatorContext operator = PlatformOperatorContextHolder.require();
        if (!operator.permits(PlatformCapability.READ_PLATFORM_OVERVIEW, Instant.now())) {
            audit.record(PlatformAuditRepository.CAPABILITY_DENIED, operator.operatorId(),
                    operator.sessionId(), null, null, "Project", null, "listing clinics",
                    "required=READ_PLATFORM_OVERVIEW", PlatformAuditRepository.DENIED,
                    MDC.get("correlationId"), null);
            throw new PlatformOperatorContextHolder.PlatformAccessDenied(
                    "READ_PLATFORM_OVERVIEW is required.");
        }
        audit.record(PlatformAuditRepository.VIEWED, operator.operatorId(), operator.sessionId(),
                null, null, "Project", null, "listing clinics", "count=" + operators.listAllClinics().size(),
                PlatformAuditRepository.ALLOWED, MDC.get("correlationId"), null);
        return operators.listAllClinics();
    }

    /** The cross-tenant audit with full search and filtering. */
    @GetMapping("/audit")
    public List<PlatformAuditRepository.EnrichedAuditEntry> auditTrail(
            @RequestParam(required = false) UUID clinicId,
            @RequestParam(required = false) UUID operatorId,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) UUID organizationId,
            @RequestParam(defaultValue = "100") int limit,
            HttpServletRequest request) {
        PlatformOperatorContext operator = PlatformOperatorContextHolder.require();
        if (!operator.permits(PlatformCapability.READ_PLATFORM_AUDIT, Instant.now())) {
            audit.record(PlatformAuditRepository.CAPABILITY_DENIED, operator.operatorId(),
                    operator.sessionId(), null, null, "Audit", null, "reading the audit",
                    "required=READ_PLATFORM_AUDIT", PlatformAuditRepository.DENIED,
                    MDC.get("correlationId"), currentIp(request));
            throw new PlatformOperatorContextHolder.PlatformAccessDenied(
                    "READ_PLATFORM_AUDIT is required.");
        }
        return audit.search(new PlatformAuditRepository.AuditFilter(
                clinicId, organizationId, operatorId, actor, action, from, to, outcome, limit));
    }

    /** The distinct action types logged in the platform audit for filtering. */
    @GetMapping("/audit/actions")
    public List<String> auditActions() {
        PlatformOperatorContext operator = PlatformOperatorContextHolder.require();
        if (!operator.permits(PlatformCapability.READ_PLATFORM_AUDIT, Instant.now())) {
            throw new PlatformOperatorContextHolder.PlatformAccessDenied(
                    "READ_PLATFORM_AUDIT is required.");
        }
        return audit.distinctActions();
    }

    // ------------------------------------------------------------------
    // writing
    // ------------------------------------------------------------------

    public record CreateClinicRequest(UUID organizationId, String name, String slug,
                                      String administratorEmail, String administratorName,
                                      String administratorPassword, String reason) {
    }

    /**
     * Creates a clinic and its first administrator, in one action.
     *
     * <p>The password arrives in the body and is never echoed, logged or returned. It is hashed
     * immediately and the plaintext does not outlive this method.
     */
    @PostMapping("/clinics")
    public PlatformClinicService.ClinicCreated createClinic(@RequestBody CreateClinicRequest request) {
        PlatformOperatorContext operator = PlatformOperatorContextHolder.require();
        PlatformClinicService.ClinicCreated created = clinics.createClinic(
                operator, request.organizationId(), request.name(), request.slug(),
                request.administratorEmail(), request.administratorName(),
                request.administratorPassword(), request.reason(), MDC.get("correlationId"));
        return created;
    }

    /**
     * Tenant lifecycle: Onboarding wizard.
     * Generates a single-use invite link for the clinic admin; operator never sees or sets passwords.
     */
    @PostMapping("/clinics/wizard")
    public PlatformClinicService.ClinicWizardResult createClinicWizard(
            @RequestBody PlatformClinicService.CreateClinicWizardRequest request) {
        PlatformOperatorContext operator = PlatformOperatorContextHolder.require();
        return clinics.createClinicWizard(operator, request, MDC.get("correlationId"));
    }

    public record SimpleReasonRequest(String reason) {}

    @PostMapping("/clinics/{id}/invite/resend")
    public Map<String, Object> resendInvite(@PathVariable("id") UUID clinicId,
                                            @RequestBody(required = false) SimpleReasonRequest request) {
        PlatformOperatorContext operator = PlatformOperatorContextHolder.require();
        String reason = request == null ? "Regenerating invite link" : request.reason();
        return clinics.resendInvite(operator, clinicId, reason, MDC.get("correlationId"));
    }

    @PostMapping("/clinics/{id}/admin/reset-mfa")
    public Map<String, Object> resetAdminMfa(@PathVariable("id") UUID clinicId,
                                             @RequestBody(required = false) SimpleReasonRequest request) {
        PlatformOperatorContext operator = PlatformOperatorContextHolder.require();
        String reason = request == null ? "Resetting MFA" : request.reason();
        clinics.resetAdminMfa(operator, clinicId, reason, MDC.get("correlationId"));
        return Map.of("success", true, "message", "MFA reset successfully");
    }

    @PostMapping("/clinics/{id}/admin/disable")
    public Map<String, Object> disableAdmin(@PathVariable("id") UUID clinicId,
                                            @RequestBody(required = false) SimpleReasonRequest request) {
        PlatformOperatorContext operator = PlatformOperatorContextHolder.require();
        String reason = request == null ? "Disabling administrator account" : request.reason();
        clinics.setAdminActive(operator, clinicId, false, reason, MDC.get("correlationId"));
        return Map.of("success", true, "message", "Account disabled");
    }

    @PostMapping("/clinics/{id}/admin/enable")
    public Map<String, Object> enableAdmin(@PathVariable("id") UUID clinicId,
                                           @RequestBody(required = false) SimpleReasonRequest request) {
        PlatformOperatorContext operator = PlatformOperatorContextHolder.require();
        String reason = request == null ? "Reactivating administrator account" : request.reason();
        clinics.setAdminActive(operator, clinicId, true, reason, MDC.get("correlationId"));
        return Map.of("success", true, "message", "Account enabled");
    }

    @PostMapping("/clinics/{id}/suspend")
    public Map<String, Object> suspendClinic(@PathVariable("id") UUID clinicId,
                                             @RequestBody(required = false) SimpleReasonRequest request) {
        PlatformOperatorContext operator = PlatformOperatorContextHolder.require();
        String reason = request == null ? "Administrative suspension" : request.reason();
        clinics.suspendClinic(operator, clinicId, reason, MDC.get("correlationId"));
        return Map.of("success", true, "status", "SUSPENDED", "message", "Clinic suspended. Data retained under DEC-007.");
    }

    @PostMapping("/clinics/{id}/reactivate")
    public Map<String, Object> reactivateClinic(@PathVariable("id") UUID clinicId,
                                                @RequestBody(required = false) SimpleReasonRequest request) {
        PlatformOperatorContext operator = PlatformOperatorContextHolder.require();
        String reason = request == null ? "Administrative reactivation" : request.reason();
        clinics.reactivateClinic(operator, clinicId, reason, MDC.get("correlationId"));
        return Map.of("success", true, "status", "ACTIVE", "message", "Clinic reactivated.");
    }

    public record ExtendSubscriptionRequest(int days, String reason) {}

    @PostMapping("/clinics/{id}/extend")
    public Map<String, Object> extendSubscription(@PathVariable("id") UUID clinicId,
                                                  @RequestBody ExtendSubscriptionRequest request) {
        PlatformOperatorContext operator = PlatformOperatorContextHolder.require();
        clinics.extendSubscription(operator, clinicId, request.days(), request.reason(), MDC.get("correlationId"));
        return Map.of("success", true, "message", "Subscription extended by " + request.days() + " days.");
    }

    @PutMapping("/clinics/{id}/limits")
    public Map<String, Object> updateLimits(@PathVariable("id") UUID clinicId,
                                            @RequestBody PlatformClinicService.LimitsOverrideRequest request) {
        PlatformOperatorContext operator = PlatformOperatorContextHolder.require();
        clinics.updateClinicLimits(operator, clinicId, request, MDC.get("correlationId"));
        return Map.of("success", true, "message", "Limits updated successfully.");
    }

    @PutMapping("/clinics/{id}")
    public Map<String, Object> editClinic(@PathVariable("id") UUID clinicId,
                                          @RequestBody PlatformClinicService.EditClinicRequest request) {
        PlatformOperatorContext operator = PlatformOperatorContextHolder.require();
        clinics.editClinic(operator, clinicId, request, MDC.get("correlationId"));
        return Map.of("success", true, "message", "Clinic details updated.");
    }

    @PostMapping("/clinics/{id}/admins")
    public Map<String, Object> addClinicAdmin(@PathVariable("id") UUID clinicId,
                                              @RequestBody PlatformClinicService.AddClinicAdminRequest request) {
        PlatformOperatorContext operator = PlatformOperatorContextHolder.require();
        return clinics.addClinicAdmin(operator, clinicId, request, MDC.get("correlationId"));
    }

    @GetMapping("/clinics/{id}/intake-qr")
    public Map<String, Object> getClinicIntakeQr(@PathVariable("id") UUID clinicId) {
        PlatformOperatorContext operator = PlatformOperatorContextHolder.require();
        return clinics.getClinicIntakeQr(operator, clinicId);
    }

    @PostMapping("/clinics/{id}/intake-qr/regenerate")
    public Map<String, Object> regenerateClinicIntakeQr(
            @PathVariable("id") UUID clinicId,
            @RequestBody(required = false) SimpleReasonRequest request) {
        PlatformOperatorContext operator = PlatformOperatorContextHolder.require();
        String reason = request == null ? "Regenerated clinic intake QR key" : request.reason();
        return clinics.regenerateClinicIntakeQr(operator, clinicId, reason, MDC.get("correlationId"));
    }

    // ------------------------------------------------------------------
    // Subscriptions and Append-Only Payments Ledger (Slice 3)
    // ------------------------------------------------------------------

    @GetMapping("/subscriptions")
    public List<SubscriptionPaymentService.SubscriptionOverviewDto> listSubscriptions() {
        PlatformOperatorContextHolder.require();
        return paymentService.listSubscriptions();
    }

    @GetMapping("/clinics/{id}/payments")
    public List<SubscriptionPaymentService.PaymentRecordDto> listClinicLedger(@PathVariable("id") UUID clinicId) {
        PlatformOperatorContextHolder.require();
        return paymentService.listLedger(clinicId);
    }

    public record RecordPaymentPayload(
        String idempotencyKey,
        java.math.BigDecimal amount,
        String currency,
        String paymentMethod,
        String referenceNumber,
        int periodMonths,
        String notes
    ) {}

    @PostMapping("/clinics/{id}/payments")
    public SubscriptionPaymentService.PaymentRecordDto recordPayment(@PathVariable("id") UUID clinicId,
                                                                     @RequestBody RecordPaymentPayload payload) {
        PlatformOperatorContext operator = PlatformOperatorContextHolder.require();
        SubscriptionPaymentService.RecordPaymentCommand cmd = new SubscriptionPaymentService.RecordPaymentCommand(
            payload.idempotencyKey(), payload.amount(), payload.currency(),
            payload.paymentMethod(), payload.referenceNumber(), payload.periodMonths(), payload.notes()
        );
        return paymentService.recordPayment(operator, clinicId, cmd, MDC.get("correlationId"));
    }

    public record ReversePaymentPayload(String reason) {}

    @PostMapping("/clinics/{id}/payments/{paymentId}/reverse")
    public SubscriptionPaymentService.PaymentRecordDto reversePayment(@PathVariable("id") UUID clinicId,
                                                                      @PathVariable("paymentId") UUID paymentId,
                                                                      @RequestBody ReversePaymentPayload payload) {
        PlatformOperatorContext operator = PlatformOperatorContextHolder.require();
        return paymentService.reversePayment(operator, clinicId, paymentId, payload.reason(), MDC.get("correlationId"));
    }

    @PostMapping("/subscriptions/run-transition-job")
    public SubscriptionStatusTransitionService.TransitionResult runTransitionJob() {
        PlatformOperatorContext operator = PlatformOperatorContextHolder.require();
        return transitionService.runTransitionJob(Instant.now(), operator, MDC.get("correlationId"));
    }

    // ------------------------------------------------------------------
    // Plans and Quota Limits Management (Slice 4)
    // ------------------------------------------------------------------

    @GetMapping("/plans")
    public List<com.firstelement.security.platform.PlatformPlanService.PlanSummaryDto> listPlans() {
        PlatformOperatorContextHolder.require();
        return planService.listPlans();
    }

    @GetMapping("/plans/{id}")
    public com.firstelement.security.platform.PlatformPlanService.PlanDetailDto getPlan(@PathVariable("id") UUID planId) {
        PlatformOperatorContextHolder.require();
        return planService.getPlan(planId);
    }

    @PutMapping("/plans/{id}")
    public com.firstelement.security.platform.PlatformPlanService.PlanDetailDto updatePlan(
            @PathVariable("id") UUID planId,
            @RequestBody com.firstelement.security.platform.PlatformPlanService.UpdatePlanCommand command) {
        PlatformOperatorContext operator = PlatformOperatorContextHolder.require();
        return planService.updatePlan(operator, planId, command, MDC.get("correlationId"));
    }

    @GetMapping("/clinics/{id}/limits")
    public com.firstelement.security.platform.PlatformPlanService.ClinicLimitsDto getClinicLimits(
            @PathVariable("id") UUID clinicId) {
        PlatformOperatorContextHolder.require();
        return planService.getClinicLimits(clinicId);
    }

    public record EntitlementOverridePayload(boolean enabled, String reason) {}

    @PutMapping("/clinics/{id}/entitlements/{featureKey}")
    public Map<String, Object> overrideEntitlement(
            @PathVariable("id") UUID clinicId,
            @PathVariable("featureKey") String featureKey,
            @RequestBody EntitlementOverridePayload payload) {
        PlatformOperatorContext operator = PlatformOperatorContextHolder.require();
        planService.updateClinicEntitlementOverride(operator, clinicId, featureKey, payload.enabled(),
                payload.reason(), MDC.get("correlationId"));
        return Map.of("success", true, "featureKey", featureKey, "enabled", payload.enabled());
    }

    // ------------------------------------------------------------------
    // Support Access (Break-Glass) Management (Slice 5)
    // ------------------------------------------------------------------

    public record RequestSupportAccessPayload(
        UUID projectId,
        String reason,
        int durationMinutes,
        boolean isReadOnly
    ) {}

    public record RevokeSupportAccessPayload(
        String reason
    ) {}

    @GetMapping("/support-access")
    public List<com.firstelement.security.platform.PlatformSupportAccessService.SupportAccessGrantDto> listSupportGrants(
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) String status) {
        PlatformOperatorContextHolder.require();
        return supportAccessService.listGrants(projectId, status);
    }

    @PostMapping("/support-access")
    public com.firstelement.security.platform.PlatformSupportAccessService.SupportAccessGrantDto requestSupportAccess(
            @RequestBody RequestSupportAccessPayload payload) {
        PlatformOperatorContext operator = PlatformOperatorContextHolder.require();
        return supportAccessService.requestAccess(
                operator,
                new com.firstelement.security.platform.PlatformSupportAccessService.RequestAccessCommand(
                        payload.projectId(), payload.reason(), payload.durationMinutes(), payload.isReadOnly()
                ),
                MDC.get("correlationId")
        );
    }

    @GetMapping("/support-access/{id}")
    public com.firstelement.security.platform.PlatformSupportAccessService.SupportAccessGrantDto getSupportGrant(
            @PathVariable("id") UUID grantId) {
        PlatformOperatorContextHolder.require();
        return supportAccessService.getGrant(grantId);
    }

    @PostMapping("/support-access/{id}/revoke")
    public com.firstelement.security.platform.PlatformSupportAccessService.SupportAccessGrantDto revokeSupportAccess(
            @PathVariable("id") UUID grantId,
            @RequestBody(required = false) RevokeSupportAccessPayload payload) {
        PlatformOperatorContext operator = PlatformOperatorContextHolder.require();
        String reason = payload != null && payload.reason() != null ? payload.reason() : "Manually revoked by operator";
        return supportAccessService.revokeAccess(
                operator,
                new com.firstelement.security.platform.PlatformSupportAccessService.RevokeAccessCommand(grantId, reason),
                MDC.get("correlationId")
        );
    }

    @GetMapping("/support-access/{id}/patients")
    public List<Map<String, Object>> getClinicPatients(@PathVariable("id") UUID grantId) {
        PlatformOperatorContext operator = PlatformOperatorContextHolder.require();
        return supportAccessService.getClinicPatients(operator, grantId, MDC.get("correlationId"));
    }

    @GetMapping("/support-access/{id}/appointments")
    public List<Map<String, Object>> getClinicAppointments(@PathVariable("id") UUID grantId) {
        PlatformOperatorContext operator = PlatformOperatorContextHolder.require();
        return supportAccessService.getClinicAppointments(operator, grantId, MDC.get("correlationId"));
    }

    @GetMapping("/support-access/{id}/encounters")
    public List<Map<String, Object>> getClinicEncounters(@PathVariable("id") UUID grantId) {
        PlatformOperatorContext operator = PlatformOperatorContextHolder.require();
        return supportAccessService.getClinicEncounters(operator, grantId, MDC.get("correlationId"));
    }

    @GetMapping("/support-access/{id}/audit")
    public List<Map<String, Object>> getClinicAuditLog(@PathVariable("id") UUID grantId) {
        PlatformOperatorContext operator = PlatformOperatorContextHolder.require();
        return supportAccessService.getClinicAuditLog(operator, grantId, MDC.get("correlationId"));
    }

    @PostMapping("/support-access/{id}/patients")
    public void rejectPatientCreate(@PathVariable("id") UUID grantId) {
        PlatformOperatorContextHolder.require();
        supportAccessService.rejectMutation();
    }

    public record CreateAccountRequest(UUID organizationId, UUID projectId, String email,
                                       String fullName, String role, String password, String reason) {
    }

    @PostMapping("/accounts")
    public Map<String, Object> createAccount(@RequestBody CreateAccountRequest request) {
        PlatformOperatorContext operator = PlatformOperatorContextHolder.require();
        UUID userId = clinics.createAccount(operator, request.organizationId(), request.projectId(),
                request.email(), request.fullName(), request.role(), request.password(),
                request.reason(), MDC.get("correlationId"));
        return Map.of("userId", userId);
    }

    public record GrantAccessRequest(UUID organizationId, UUID projectId, UUID userId, String reason) {
    }

    @PostMapping("/access")
    public Map<String, Object> grantAccess(@RequestBody GrantAccessRequest request) {
        PlatformOperatorContext operator = PlatformOperatorContextHolder.require();
        clinics.grantAccess(operator, request.organizationId(), request.projectId(),
                request.userId(), request.reason(), MDC.get("correlationId"));
        return Map.of("granted", true);
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private OperatorView view(PlatformOperatorContext operator) {
        Instant now = Instant.now();
        return new OperatorView(operator.email(), operator.displayName(), operator.isElevated(now),
                operator.elevatedUntil(),
                operator.capabilities().stream().map(Enum::name).sorted().toList());
    }

    private ResponseCookie platformCookie(String value, java.time.Duration maxAge) {
        return ResponseCookie.from(PlatformOperatorAuthenticationFilter.COOKIE_NAME, value)
                .httpOnly(true)
                // Same resolution as the customer cookie, deliberately. See C-1 in the Stage 1b
                // review: an inlined Boolean.TRUE.equals(null) made this the one cookie in the
                // product issued without Secure in production.
                .secure(securityProperties.resolvedSessionCookieSecure(production))
                .path("/api/v1/platform")
                .sameSite("Strict")
                .maxAge(maxAge)
                .build();
    }

    private String readPlatformCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
            if (PlatformOperatorAuthenticationFilter.COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private String userAgent(HttpServletRequest request) {
        return request.getHeader(HttpHeaders.USER_AGENT);
    }

    private String currentIp(HttpServletRequest request) {
        String ip = MDC.get("remoteIp");
        return ip == null ? request.getRemoteAddr() : ip;
    }
}