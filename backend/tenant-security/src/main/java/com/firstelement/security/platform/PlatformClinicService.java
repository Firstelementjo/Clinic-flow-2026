package com.firstelement.security.platform;

import com.firstelement.security.auth.PasswordVerifier;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Onboarding: create a clinic and its first administrator as one action.
 *
 * <h2>Why these write tenant tables from the platform tier</h2>
 *
 * <p>ADR 0018 makes a clinic a project, so creating a clinic is an insert into {@code projects} and
 * its first administrator is an insert into {@code users}. Both are tenant-scoped tables, and this
 * class is the one place in the platform tier that writes them.
 *
 * <p>That is why the tenant key is a parameter that is validated to exist rather than something
 * derived from a request body: the operator names an organization, and the organization must already
 * exist. This service never creates an organization, so it cannot invent a tenant. Creating a client
 * as well as their clinics is a separate, deliberately separate operation.
 *
 * <h2>Every write is audited, including the denial</h2>
 *
 * <p>A refused onboarding attempt is recorded with the same weight as a successful one, because an
 * operator probing which organizations exist is exactly what the cross-tenant audit exists to reveal.
 */
public class PlatformClinicService {

    /** What {@link #createClinic} produced. Carries no credential. */
    public record ClinicCreated(UUID organizationId, UUID projectId, String clinicName,
                                UUID administratorUserId, String administratorEmail) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final PlatformAuditRepository audit;
    private final InviteTokenService inviteService;

    public PlatformClinicService(NamedParameterJdbcTemplate jdbc, PlatformAuditRepository audit) {
        this(jdbc, audit, new InviteTokenService(jdbc, audit));
    }

    public PlatformClinicService(NamedParameterJdbcTemplate jdbc,
                                 PlatformAuditRepository audit,
                                 InviteTokenService inviteService) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.inviteService = inviteService;
    }

    public record CreateClinicWizardRequest(
            String clinicName,
            String city,
            String contactNumber,
            String timezone,
            String language,
            String planCode,
            String startType,
            String adminName,
            String adminEmail,
            String adminPhone,
            String reason,
            String adminPassword
    ) {
        public CreateClinicWizardRequest(String clinicName, String city, String contactNumber,
                                         String timezone, String language, String planCode,
                                         String startType, String adminName, String adminEmail,
                                         String adminPhone, String reason) {
            this(clinicName, city, contactNumber, timezone, language, planCode, startType, adminName, adminEmail, adminPhone, reason, null);
        }
    }

    public record ClinicWizardResult(
            UUID organizationId,
            UUID projectId,
            String clinicName,
            String clinicSlug,
            String city,
            String contactNumber,
            String timezone,
            String status,
            String planCode,
            Instant paidUntil,
            UUID administratorUserId,
            String administratorName,
            String administratorEmail,
            String administratorPhone,
            String inviteToken,
            String inviteLink
    ) {}

    public record LimitsOverrideRequest(
            Integer maxUsers,
            Integer maxBranches,
            Integer storageQuotaMb,
            Integer monthlyAiQuota,
            String reason
    ) {}

    public record EditClinicRequest(
            String clinicName,
            String city,
            String contactNumber,
            String timezone,
            String language,
            String reason
    ) {}

    public record AddClinicAdminRequest(
            String adminName,
            String adminEmail,
            String adminPhone,
            String adminPassword,
            String reason
    ) {}

    /**
     * Tenant lifecycle: Onboarding wizard.
     *
     * <p>Creates clinic, associates with an organization, provisions subscription,
     * seeds initial admin account with a dummy non-functional password, and generates
     * a single-use expiring invite token so the clinic admin can set their own password and MFA.
     * Operator never sees or sets a password!
     */
    @Transactional
    public ClinicWizardResult createClinicWizard(PlatformOperatorContext operator,
                                                 CreateClinicWizardRequest request,
                                                 String correlationId) {
        if (operator == null || !operator.permits(PlatformCapability.CREATE_CLINIC, Instant.now())) {
            if (operator != null) {
                audit.record(PlatformAuditRepository.CAPABILITY_DENIED, operator.operatorId(),
                        operator.sessionId(), null, null, "Project", null,
                        request.reason() == null ? "no-reason" : request.reason(),
                        "required=CREATE_CLINIC", PlatformAuditRepository.DENIED, correlationId, null);
            }
            throw new PlatformOperatorContextHolder.PlatformAccessDenied("CREATE_CLINIC is required.");
        }

        if (request.clinicName() == null || request.clinicName().isBlank()) {
            throw new IllegalArgumentException("Clinic name is required.");
        }

        String normalizedEmail = PasswordVerifier.normalizeEmail(request.adminEmail());
        if (!PasswordVerifier.isAcceptableEmail(normalizedEmail)) {
            throw new IllegalArgumentException("The administrator address is not usable.");
        }

        // Generate clean URL slug from clinic name
        String baseSlug = request.clinicName().trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        if (baseSlug.isBlank()) {
            baseSlug = "clinic";
        }
        String clinicSlug = baseSlug + "-" + UUID.randomUUID().toString().substring(0, 8);

        // Find or create organization
        UUID orgId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
            insert into "public"."organizations"
                ("id", "name", "slug", "isActive", "createdAt", "updatedAt")
            values
                (:id, :name, :slug, true, :now, :now)
            """,
            new MapSqlParameterSource()
                .addValue("id", orgId)
                .addValue("name", request.clinicName().trim())
                .addValue("slug", clinicSlug + "-org")
                .addValue("now", now));

        UUID projectId = UUID.randomUUID();
        String city = (request.city() == null || request.city().isBlank()) ? "Amman" : request.city().trim();
        String contactNumber = request.contactNumber() == null ? null : request.contactNumber().trim();
        String timezone = (request.timezone() == null || request.timezone().isBlank()) ? "Asia/Amman" : request.timezone().trim();
        String language = (request.language() == null || !request.language().equalsIgnoreCase("en")) ? "ar" : "en";
        String startType = (request.startType() == null || !request.startType().equalsIgnoreCase("PAID")) ? "TRIAL" : "ACTIVE";

        jdbc.update("""
            insert into "public"."projects"
                ("id", "organizationId", "name", "slug", "intakePortalKey", "intakePortalEnabled",
                 "description", "defaultLocale",
                 "timezone", "city", "contactNumber", "status", "isArchived", "version", "createdAt", "updatedAt")
            values
                (:id, :organizationId, :name, :slug, :intakePortalKey, true,
                 :description, cast(:locale as "Locale"),
                 :timezone, :city, :contactNumber, :status, false, 0, :now, :now)
            """,
            new MapSqlParameterSource()
                    .addValue("id", projectId)
                    .addValue("organizationId", orgId)
                    .addValue("name", request.clinicName().trim())
                    .addValue("slug", clinicSlug)
                    .addValue("intakePortalKey", clinicSlug)
                    .addValue("description", "Provisioned via Platform Console Wizard.")
                    .addValue("locale", language)
                    .addValue("timezone", timezone)
                    .addValue("city", city)
                    .addValue("contactNumber", contactNumber)
                    .addValue("status", startType)
                    .addValue("now", now));

        // Create Clinic Admin account
        UUID adminUserId = UUID.randomUUID();
        String passwordHash;
        boolean invitePending;
        String verificationStatus;

        if (request.adminPassword() != null && !request.adminPassword().isBlank()) {
            passwordHash = new PasswordVerifier().hash(request.adminPassword().trim());
            invitePending = false;
            verificationStatus = "VERIFIED";
        } else {
            passwordHash = PasswordVerifier.DUMMY_PASSWORD_HASH;
            invitePending = true;
            verificationStatus = "PENDING";
        }

        jdbc.update("""
            insert into "public"."users"
                ("id", "organizationId", "email", "passwordHash", "fullName", "phone", "role", "locale",
                 "isActive", "invitePending", "verificationStatus", "createdAt", "updatedAt")
            values
                (:id, :organizationId, :email, :passwordHash, :fullName, :phone, cast('ADMIN' as "UserRole"), cast(:locale as "Locale"),
                 true, :invitePending, :verificationStatus, :now, :now)
            """,
            new MapSqlParameterSource()
                    .addValue("id", adminUserId)
                    .addValue("organizationId", orgId)
                    .addValue("email", normalizedEmail)
                    .addValue("passwordHash", passwordHash)
                    .addValue("fullName", request.adminName().trim())
                    .addValue("phone", request.adminPhone() == null ? null : request.adminPhone().trim())
                    .addValue("locale", language)
                    .addValue("invitePending", invitePending)
                    .addValue("verificationStatus", verificationStatus)
                    .addValue("now", now));

        // Project membership for admin
        jdbc.update("""
            insert into "public"."project_memberships" ("id", "organizationId", "projectId", "userId", "createdAt")
            values (:id, :organizationId, :projectId, :userId, :now)
            """,
            new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID())
                    .addValue("organizationId", orgId)
                    .addValue("projectId", projectId)
                    .addValue("userId", adminUserId)
                    .addValue("now", now));

        // Assign clinic_admin role in clinic_user_roles
        try {
            jdbc.update("""
                insert into "public"."clinic_user_roles" ("organizationId", "clinicId", "userId", "roleId", "assignedAt")
                select :orgId, :projectId, :userId, cr.id, :now
                from "public"."clinic_roles" cr
                where cr."roleKey" = 'clinic_admin'
                on conflict do nothing
                """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("projectId", projectId)
                        .addValue("userId", adminUserId)
                        .addValue("now", now));
        } catch (Exception ignored) {}

        // Subscription resolution
        String planCode = (request.planCode() == null || request.planCode().isBlank()) ? "GROWTH" : request.planCode().trim().toUpperCase(Locale.ROOT);
        List<UUID> planRows = jdbc.queryForList(
                "select \"id\" from \"public\".\"plans\" where upper(\"code\") = :code",
                new MapSqlParameterSource("code", planCode), UUID.class);
        UUID planId = planRows.isEmpty()
                ? UUID.fromString("a0000000-0000-0000-0000-000000000002") // Growth default
                : planRows.get(0);

        Instant periodStartsAt = Instant.now();
        Instant paidUntil = startType.equals("TRIAL")
                ? periodStartsAt.plus(Duration.ofDays(14))
                : periodStartsAt.plus(Duration.ofDays(30));
        Instant trialEndsAt = startType.equals("TRIAL") ? paidUntil : null;

        jdbc.update("""
            insert into "public"."clinic_subscriptions"
                ("id", "organizationId", "projectId", "planId", "status", "trialEndsAt",
                 "currentPeriodStartsAt", "paidUntil", "gracePeriodDays", "version", "createdAt", "updatedAt")
            values
                (:id, :organizationId, :projectId, :planId, :status, :trialEndsAt,
                 :currentPeriodStartsAt, :paidUntil, 7, 0, :now, :now)
            """,
            new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID())
                    .addValue("organizationId", orgId)
                    .addValue("projectId", projectId)
                    .addValue("planId", planId)
                    .addValue("status", startType)
                    .addValue("trialEndsAt", trialEndsAt == null ? null : Timestamp.from(trialEndsAt))
                    .addValue("currentPeriodStartsAt", Timestamp.from(periodStartsAt))
                    .addValue("paidUntil", Timestamp.from(paidUntil))
                    .addValue("now", now));

        // Generate single-use invite token (expires in 48h)
        InviteTokenService.InviteTokenCreated invite = inviteService.createInvite(
                orgId, projectId, adminUserId, normalizedEmail, operator.operatorId(),
                InviteTokenService.DEFAULT_INVITE_TTL);

        String inviteLink = "/invite?token=" + invite.rawToken();

        audit.record(PlatformAuditRepository.PROJECT_CREATED, operator.operatorId(),
                operator.sessionId(), orgId, projectId, "Project", projectId.toString(),
                request.reason(), "wizardCreated=" + clinicSlug + " admin=" + normalizedEmail,
                PlatformAuditRepository.ALLOWED, correlationId, null);

        return new ClinicWizardResult(
                orgId, projectId, request.clinicName().trim(), clinicSlug, city, contactNumber,
                timezone, startType, planCode, paidUntil, adminUserId, request.adminName().trim(),
                normalizedEmail, request.adminPhone(), invite.rawToken(), inviteLink);
    }

    /**
     * Resends single-use invite to clinic admin.
     */
    @Transactional
    public Map<String, Object> resendInvite(PlatformOperatorContext operator, UUID projectId,
                                           String reason, String correlationId) {
        if (operator == null || !operator.permits(PlatformCapability.CREATE_CLINIC, Instant.now())) {
            throw new PlatformOperatorContextHolder.PlatformAccessDenied("CREATE_CLINIC is required.");
        }
        Map<String, Object> admin = findClinicAdmin(projectId);
        UUID orgId = (UUID) admin.get("organizationId");
        UUID userId = (UUID) admin.get("userId");
        String email = (String) admin.get("email");

        InviteTokenService.InviteTokenCreated invite = inviteService.resendInvite(
                orgId, projectId, userId, email, operator.operatorId());

        String inviteLink = "/invite?token=" + invite.rawToken();
        return Map.of(
                "token", invite.rawToken(),
                "link", inviteLink,
                "expiresAt", invite.expiresAt()
        );
    }

    /**
     * Resets MFA enrollment for the clinic admin account.
     */
    @Transactional
    public void resetAdminMfa(PlatformOperatorContext operator, UUID projectId,
                              String reason, String correlationId) {
        if (operator == null || !operator.permits(PlatformCapability.CREATE_CLINIC, Instant.now())) {
            throw new PlatformOperatorContextHolder.PlatformAccessDenied("CREATE_CLINIC is required.");
        }
        Map<String, Object> admin = findClinicAdmin(projectId);
        UUID userId = (UUID) admin.get("userId");
        UUID orgId = (UUID) admin.get("organizationId");

        jdbc.update("""
            update "public"."users"
               set "totpSecretEnc" = null,
                   "mfaEnrolledAt" = null,
                   "updatedAt" = (current_timestamp at time zone 'utc')
             where "id" = :userId
            """,
            new MapSqlParameterSource("userId", userId));

        audit.record("MFA_RESET", operator.operatorId(), operator.sessionId(),
                orgId, projectId, "User", userId.toString(), reason,
                "Operator reset clinic admin MFA enrollment",
                PlatformAuditRepository.ALLOWED, correlationId, null);
    }

    /**
     * Toggles whether the clinic admin account is active.
     */
    @Transactional
    public void setAdminActive(PlatformOperatorContext operator, UUID projectId,
                               boolean active, String reason, String correlationId) {
        if (operator == null || !operator.permits(PlatformCapability.CREATE_CLINIC, Instant.now())) {
            throw new PlatformOperatorContextHolder.PlatformAccessDenied("CREATE_CLINIC is required.");
        }
        Map<String, Object> admin = findClinicAdmin(projectId);
        UUID userId = (UUID) admin.get("userId");
        UUID orgId = (UUID) admin.get("organizationId");

        jdbc.update("""
            update "public"."users"
               set "isActive" = :active,
                   "updatedAt" = (current_timestamp at time zone 'utc')
             where "id" = :userId
            """,
            new MapSqlParameterSource().addValue("userId", userId).addValue("active", active));

        if (!active) {
            // Revoke any active sessions immediately
            jdbc.update("delete from \"public\".\"sessions\" where \"userId\" = :userId",
                    new MapSqlParameterSource("userId", userId));
        }

        audit.record(active ? "ACCOUNT_ENABLED" : "ACCOUNT_DISABLED", operator.operatorId(),
                operator.sessionId(), orgId, projectId, "User", userId.toString(), reason,
                "Operator toggled admin active=" + active,
                PlatformAuditRepository.ALLOWED, correlationId, null);
    }

    /**
     * Suspends clinic: blocks write access across all staff while data is 100% retained (DEC-007).
     */
    @Transactional
    public void suspendClinic(PlatformOperatorContext operator, UUID projectId,
                              String reason, String correlationId) {
        if (operator == null || !operator.permits(PlatformCapability.CREATE_CLINIC, Instant.now())) {
            throw new PlatformOperatorContextHolder.PlatformAccessDenied("CREATE_CLINIC is required.");
        }
        UUID orgId = requireProjectOrg(projectId);
        Timestamp now = Timestamp.from(Instant.now());

        jdbc.update("""
            update "public"."projects"
               set "status" = 'SUSPENDED',
                   "suspendedAt" = :now,
                   "suspendedReason" = :reason,
                   "version" = "version" + 1,
                   "updatedAt" = :now
             where "id" = :projectId
            """,
            new MapSqlParameterSource()
                    .addValue("projectId", projectId)
                    .addValue("reason", reason)
                    .addValue("now", now));

        jdbc.update("""
            update "public"."clinic_subscriptions"
               set "status" = 'SUSPENDED',
                   "version" = "version" + 1,
                   "updatedAt" = :now
             where "projectId" = :projectId
            """,
            new MapSqlParameterSource().addValue("projectId", projectId).addValue("now", now));

        audit.record("CLINIC_SUSPENDED", operator.operatorId(), operator.sessionId(),
                orgId, projectId, "Project", projectId.toString(), reason,
                "Clinic suspended. Write operations blocked; medical data retained under DEC-007.",
                PlatformAuditRepository.ALLOWED, correlationId, null);
    }

    /**
     * Reactivates a suspended clinic.
     */
    @Transactional
    public void reactivateClinic(PlatformOperatorContext operator, UUID projectId,
                                 String reason, String correlationId) {
        if (operator == null || !operator.permits(PlatformCapability.CREATE_CLINIC, Instant.now())) {
            throw new PlatformOperatorContextHolder.PlatformAccessDenied("CREATE_CLINIC is required.");
        }
        UUID orgId = requireProjectOrg(projectId);
        Timestamp now = Timestamp.from(Instant.now());

        jdbc.update("""
            update "public"."projects"
               set "status" = 'ACTIVE',
                   "suspendedAt" = null,
                   "suspendedReason" = null,
                   "version" = "version" + 1,
                   "updatedAt" = :now
             where "id" = :projectId
            """,
            new MapSqlParameterSource().addValue("projectId", projectId).addValue("now", now));

        jdbc.update("""
            update "public"."clinic_subscriptions"
               set "status" = 'ACTIVE',
                   "version" = "version" + 1,
                   "updatedAt" = :now
             where "projectId" = :projectId
            """,
            new MapSqlParameterSource().addValue("projectId", projectId).addValue("now", now));

        audit.record("CLINIC_REACTIVATED", operator.operatorId(), operator.sessionId(),
                orgId, projectId, "Project", projectId.toString(), reason,
                "Clinic reactivated by operator.",
                PlatformAuditRepository.ALLOWED, correlationId, null);
    }

    /**
     * Extends clinic subscription period with mandatory justification reason.
     */
    @Transactional
    public void extendSubscription(PlatformOperatorContext operator, UUID projectId,
                                   int days, String reason, String correlationId) {
        if (operator == null || !operator.permits(PlatformCapability.CREATE_CLINIC, Instant.now())) {
            throw new PlatformOperatorContextHolder.PlatformAccessDenied("CREATE_CLINIC is required.");
        }
        if (days <= 0) {
            throw new IllegalArgumentException("Extension days must be positive.");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Extension reason is required.");
        }
        UUID orgId = requireProjectOrg(projectId);
        Timestamp now = Timestamp.from(Instant.now());

        jdbc.update("""
            update "public"."clinic_subscriptions"
               set "paidUntil" = greatest("paidUntil", :now) + (cast(:days as text) || ' days')::interval,
                   "status" = 'ACTIVE',
                   "version" = "version" + 1,
                   "updatedAt" = :now
             where "projectId" = :projectId
            """,
            new MapSqlParameterSource()
                    .addValue("projectId", projectId)
                    .addValue("days", days)
                    .addValue("now", now));

        jdbc.update("""
            update "public"."projects"
               set "status" = 'ACTIVE',
                   "version" = "version" + 1,
                   "updatedAt" = :now
             where "id" = :projectId and "status" in ('EXPIRED', 'SUSPENDED')
            """,
            new MapSqlParameterSource().addValue("projectId", projectId).addValue("now", now));

        audit.record("SUBSCRIPTION_EXTENDED", operator.operatorId(), operator.sessionId(),
                orgId, projectId, "Subscription", projectId.toString(), reason,
                "Extended by " + days + " days", PlatformAuditRepository.ALLOWED, correlationId, null);
    }

    /**
     * Updates per-clinic limits and quota overrides.
     */
    @Transactional
    public void updateClinicLimits(PlatformOperatorContext operator, UUID projectId,
                                   LimitsOverrideRequest request, String correlationId) {
        if (operator == null || !operator.permits(PlatformCapability.CREATE_CLINIC, Instant.now())) {
            throw new PlatformOperatorContextHolder.PlatformAccessDenied("CREATE_CLINIC is required.");
        }
        UUID orgId = requireProjectOrg(projectId);
        Timestamp now = Timestamp.from(Instant.now());

        jdbc.update("""
            update "public"."clinic_subscriptions"
               set "maxUsersOverride" = :maxUsers,
                   "maxBranchesOverride" = :maxBranches,
                   "storageQuotaMbOverride" = :storageQuotaMb,
                   "monthlyAiQuotaOverride" = :monthlyAiQuota,
                   "version" = "version" + 1,
                   "updatedAt" = :now
             where "projectId" = :projectId
            """,
            new MapSqlParameterSource()
                    .addValue("projectId", projectId)
                    .addValue("maxUsers", request.maxUsers())
                    .addValue("maxBranches", request.maxBranches())
                    .addValue("storageQuotaMb", request.storageQuotaMb())
                    .addValue("monthlyAiQuota", request.monthlyAiQuota())
                    .addValue("now", now));

        audit.record("LIMITS_UPDATED", operator.operatorId(), operator.sessionId(),
                orgId, projectId, "Subscription", projectId.toString(), request.reason(),
                "users=" + request.maxUsers() + " branches=" + request.maxBranches() + " ai=" + request.monthlyAiQuota(),
                PlatformAuditRepository.ALLOWED, correlationId, null);
    }

    /**
     * Edits clinic settings (name, city, contact number, timezone, locale).
     */
    @Transactional
    public void editClinic(PlatformOperatorContext operator, UUID projectId,
                           EditClinicRequest request, String correlationId) {
        if (operator == null || !operator.permits(PlatformCapability.CREATE_CLINIC, Instant.now())) {
            throw new PlatformOperatorContextHolder.PlatformAccessDenied("CREATE_CLINIC is required.");
        }
        UUID orgId = requireProjectOrg(projectId);
        Timestamp now = Timestamp.from(Instant.now());

        String lang = (request.language() == null || !request.language().equalsIgnoreCase("en")) ? "ar" : "en";
        String tz = (request.timezone() == null || request.timezone().isBlank()) ? "Asia/Amman" : request.timezone().trim();

        jdbc.update("""
            update "public"."projects"
               set "name" = :name,
                   "city" = :city,
                   "contactNumber" = :contactNumber,
                   "timezone" = :timezone,
                   "defaultLocale" = cast(:locale as "Locale"),
                   "version" = "version" + 1,
                   "updatedAt" = :now
             where "id" = :projectId
            """,
            new MapSqlParameterSource()
                    .addValue("projectId", projectId)
                    .addValue("name", request.clinicName().trim())
                    .addValue("city", request.city() == null ? null : request.city().trim())
                    .addValue("contactNumber", request.contactNumber() == null ? null : request.contactNumber().trim())
                    .addValue("timezone", tz)
                    .addValue("locale", lang)
                    .addValue("now", now));

        audit.record("CLINIC_UPDATED", operator.operatorId(), operator.sessionId(),
                orgId, projectId, "Project", projectId.toString(), request.reason(),
                "Updated details for clinic " + request.clinicName(),
                PlatformAuditRepository.ALLOWED, correlationId, null);
    }

    /**
     * Provisions an additional clinic_admin (supervisor / manager) for an existing clinic.
     * Exclusive to Platform Operators.
     */
    @Transactional
    public Map<String, Object> addClinicAdmin(PlatformOperatorContext operator, UUID projectId,
                                              AddClinicAdminRequest request, String correlationId) {
        if (operator == null || !operator.permits(PlatformCapability.CREATE_CLINIC, Instant.now())) {
            throw new PlatformOperatorContextHolder.PlatformAccessDenied("CREATE_CLINIC is required.");
        }
        UUID orgId = requireProjectOrg(projectId);
        if (request.adminName() == null || request.adminName().isBlank()) {
            throw new IllegalArgumentException("Administrator name is required.");
        }
        String normalizedEmail = PasswordVerifier.normalizeEmail(request.adminEmail());
        if (!PasswordVerifier.isAcceptableEmail(normalizedEmail)) {
            throw new IllegalArgumentException("Enter a valid administrator email address.");
        }
        Integer existingCount = jdbc.queryForObject(
                "select count(*) from \"public\".\"users\" where \"email\" = :email",
                new MapSqlParameterSource("email", normalizedEmail), Integer.class);
        if (existingCount != null && existingCount > 0) {
            throw new IllegalArgumentException("An account with that email already exists.");
        }

        UUID adminUserId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        String passwordHash;
        boolean invitePending;
        String verificationStatus;

        if (request.adminPassword() != null && !request.adminPassword().isBlank()) {
            passwordHash = new PasswordVerifier().hash(request.adminPassword().trim());
            invitePending = false;
            verificationStatus = "VERIFIED";
        } else {
            passwordHash = PasswordVerifier.DUMMY_PASSWORD_HASH;
            invitePending = true;
            verificationStatus = "PENDING";
        }

        jdbc.update("""
            insert into "public"."users"
                ("id", "organizationId", "email", "passwordHash", "fullName", "phone", "role", "locale",
                 "isActive", "invitePending", "verificationStatus", "createdAt", "updatedAt")
            values
                (:id, :organizationId, :email, :passwordHash, :fullName, :phone, cast('ADMIN' as "UserRole"), cast('ar' as "Locale"),
                 true, :invitePending, :verificationStatus, :now, :now)
            """,
            new MapSqlParameterSource()
                .addValue("id", adminUserId)
                .addValue("organizationId", orgId)
                .addValue("email", normalizedEmail)
                .addValue("passwordHash", passwordHash)
                .addValue("fullName", request.adminName().trim())
                .addValue("phone", request.adminPhone() == null ? null : request.adminPhone().trim())
                .addValue("invitePending", invitePending)
                .addValue("verificationStatus", verificationStatus)
                .addValue("now", now));

        jdbc.update("""
            insert into "public"."project_memberships" ("id", "organizationId", "projectId", "userId", "createdAt")
            values (:id, :organizationId, :projectId, :userId, :now)
            """,
            new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("organizationId", orgId)
                .addValue("projectId", projectId)
                .addValue("userId", adminUserId)
                .addValue("now", now));

        ensureClinicAdminRoleAssigned(orgId, projectId, adminUserId, now);

        audit.record(PlatformAuditRepository.ACCOUNT_CREATED, operator.operatorId(),
                operator.sessionId(), orgId, projectId, "User", adminUserId.toString(),
                request.reason() == null ? "Added clinic administrator" : request.reason(),
                "role=clinic_admin email=" + normalizedEmail,
                PlatformAuditRepository.ALLOWED, correlationId, null);

        return Map.of(
            "userId", adminUserId,
            "email", normalizedEmail,
            "fullName", request.adminName().trim(),
            "role", "clinic_admin",
            "projectId", projectId
        );
    }

    private void ensureClinicAdminRoleAssigned(UUID orgId, UUID projectId, UUID userId, Timestamp now) {
        try {
            List<UUID> existingRole = jdbc.queryForList("""
                select "id" from "public"."clinic_roles"
                 where "organizationId" = :orgId and "roleKey" = 'clinic_admin'
                 limit 1
                """,
                new MapSqlParameterSource("orgId", orgId), UUID.class);
            UUID roleId;
            if (existingRole.isEmpty()) {
                roleId = UUID.randomUUID();
                jdbc.update("""
                    insert into "public"."clinic_roles"
                        ("id", "organizationId", "clinicId", "roleKey", "displayName", "isPreset", "createdAt")
                    values (:id, :orgId, null, 'clinic_admin', 'Clinic Admin', true, :now)
                    """,
                    new MapSqlParameterSource()
                        .addValue("id", roleId)
                        .addValue("orgId", orgId)
                        .addValue("now", now));
            } else {
                roleId = existingRole.get(0);
            }
            jdbc.update("""
                insert into "public"."clinic_user_roles"
                    ("organizationId", "clinicId", "userId", "roleId", "assignedAt")
                values (:orgId, :projectId, :userId, :roleId, :now)
                on conflict do nothing
                """,
                new MapSqlParameterSource()
                    .addValue("orgId", orgId)
                    .addValue("projectId", projectId)
                    .addValue("userId", userId)
                    .addValue("roleId", roleId)
                    .addValue("now", now));
        } catch (Exception ignored) {}
    }

    private Map<String, Object> findClinicAdmin(UUID projectId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            select u."id" as "userId", u."organizationId" as "organizationId", u."email" as "email"
              from "public"."users" u
              join "public"."project_memberships" pm on pm."userId" = u."id"
             where pm."projectId" = :projectId
               and u."role" = 'ADMIN'
             order by u."createdAt" asc
             limit 1
            """, new MapSqlParameterSource("projectId", projectId));

        if (rows.isEmpty()) {
            throw new IllegalArgumentException("No administrator found for clinic " + projectId);
        }
        return rows.get(0);
    }

    private UUID requireProjectOrg(UUID projectId) {
        List<UUID> rows = jdbc.queryForList(
                "select \"organizationId\" from \"public\".\"projects\" where \"id\" = :id",
                new MapSqlParameterSource("id", projectId), UUID.class);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Clinic not found: " + projectId);
        }
        return rows.get(0);
    }

    public Map<String, Object> getClinicIntakeQr(PlatformOperatorContext operator, UUID projectId) {
        if (operator == null || !operator.permits(PlatformCapability.READ_PLATFORM_OVERVIEW, Instant.now())) {
            throw new PlatformOperatorContextHolder.PlatformAccessDenied("READ_PLATFORM_OVERVIEW is required.");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select p."id" as "projectId",
                       p."organizationId" as "organizationId",
                       p."name" as "clinicName",
                       p."slug" as "clinicSlug",
                       p."intakePortalKey" as "intakePortalKey",
                       coalesce(p."intakePortalEnabled", true) as "enabled",
                       cast(p."defaultLocale" as text) as "defaultLocale"
                  from "public"."projects" p
                 where p."id" = :id
                """, new MapSqlParameterSource("id", projectId));
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Clinic not found: " + projectId);
        }
        Map<String, Object> row = new java.util.LinkedHashMap<>(rows.get(0));
        String key = (String) row.get("intakePortalKey");
        if (key == null || key.isBlank()) {
            String slug = String.valueOf(row.getOrDefault("clinicSlug", "clinic"));
            key = buildUniqueIntakeKey(slug);
            jdbc.update("""
                    update "public"."projects"
                       set "intakePortalKey" = :key,
                           "updatedAt" = :now
                     where "id" = :id
                    """, new MapSqlParameterSource()
                    .addValue("id", projectId)
                    .addValue("key", key)
                    .addValue("now", Timestamp.from(Instant.now())));
            row.put("intakePortalKey", key);
        }
        return row;
    }

    @Transactional
    public Map<String, Object> regenerateClinicIntakeQr(
            PlatformOperatorContext operator,
            UUID projectId,
            String reason,
            String correlationId) {
        if (operator == null || !operator.permits(PlatformCapability.CREATE_CLINIC, Instant.now())) {
            throw new PlatformOperatorContextHolder.PlatformAccessDenied("CREATE_CLINIC is required.");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select p."organizationId" as "organizationId", p."slug" as "slug"
                  from "public"."projects" p
                 where p."id" = :id
                """, new MapSqlParameterSource("id", projectId));
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Clinic not found: " + projectId);
        }
        UUID orgId = (UUID) rows.get(0).get("organizationId");
        String slug = String.valueOf(rows.get(0).getOrDefault("slug", "clinic"));
        String newKey = buildUniqueIntakeKey(slug);
        jdbc.update("""
                update "public"."projects"
                   set "intakePortalKey" = :key,
                       "updatedAt" = :now
                 where "id" = :id
                """, new MapSqlParameterSource()
                .addValue("id", projectId)
                .addValue("key", newKey)
                .addValue("now", Timestamp.from(Instant.now())));

        audit.record(PlatformAuditRepository.VIEWED, operator.operatorId(),
                operator.sessionId(), orgId, projectId, "Project", projectId.toString(),
                reason == null ? "Regenerated patient intake QR key" : reason,
                "intakePortalKey=" + newKey,
                PlatformAuditRepository.ALLOWED, correlationId, null);

        return getClinicIntakeQr(operator, projectId);
    }

    private static String buildUniqueIntakeKey(String slug) {
        String base = (slug == null || slug.isBlank()) ? "clinic" : slug.replaceAll("[^a-zA-Z0-9_-]+", "-");
        if (base.length() > 48) {
            base = base.substring(0, 48);
        }
        return base + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** An organization that does not exist, or is deactivated. Refused, not created. */
    public static class UnknownOrganization extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public UnknownOrganization() {
            super("No such organization.");
        }
    }

    /**
     * Creates a clinic (a project) and its first administrator account, in one transaction.
     *
     * <p>One transaction because a clinic with no administrator is a clinic nobody can log into, and
     * an administrator belonging to a clinic that was not created is an account in a tenant that does
     * not exist. Half of this operation is never a valid state.
     *
     * @param operator    the authenticated platform operator; must hold {@code CREATE_CLINIC}
     * @param organizationId the organization that will own the clinic. Must already exist.
     * @param clinicName   display name for the clinic
     * @param clinicSlug   URL slug, unique within the organization
     * @param adminEmail   the first administrator's address
     * @param adminName    their display name
     * @param adminPassword a strong password, supplied out of band and never logged
     */
    @Transactional
    public ClinicCreated createClinic(PlatformOperatorContext operator,
                                      UUID organizationId,
                                      String clinicName,
                                      String clinicSlug,
                                      String adminEmail,
                                      String adminName,
                                      String adminPassword,
                                      String reason,
                                      String correlationId) {
        if (operator == null || !operator.permits(PlatformCapability.CREATE_CLINIC, Instant.now())) {
            if (operator != null) {
                audit.record(PlatformAuditRepository.CAPABILITY_DENIED, operator.operatorId(),
                        operator.sessionId(), organizationId, null, "Project", null,
                        reason == null ? "no-reason" : reason, "required=CREATE_CLINIC",
                        PlatformAuditRepository.DENIED, correlationId, null);
            }
            throw new PlatformOperatorContextHolder.PlatformAccessDenied("CREATE_CLINIC is required.");
        }

        if (organizationId == null || !organizationExists(organizationId)) {
            audit.record(PlatformAuditRepository.PROJECT_CREATED, operator.operatorId(),
                    operator.sessionId(), organizationId, null, "Organization",
                    organizationId == null ? null : organizationId.toString(),
                    reason == null ? "no-reason" : reason, "organization-does-not-exist",
                    PlatformAuditRepository.DENIED, correlationId, null);
            throw new UnknownOrganization();
        }

        String normalizedEmail = PasswordVerifier.normalizeEmail(adminEmail);
        if (!PasswordVerifier.isAcceptableEmail(normalizedEmail)) {
            throw new IllegalArgumentException("The administrator address is not usable.");
        }

        UUID projectId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        String hash = new PasswordVerifier().hash(adminPassword);

        String intakeKey = (clinicSlug == null || clinicSlug.isBlank() ? "clinic" : clinicSlug.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-"))
                + "-" + projectId.toString().substring(0, 8);
        jdbc.update("""
            insert into "projects"
                ("id", "organizationId", "name", "slug", "intakePortalKey", "intakePortalEnabled",
                 "description", "defaultLocale",
                 "timezone", "isArchived", "createdAt", "updatedAt")
            values
                (:id, :organizationId, :name, :slug, :intakePortalKey, true,
                 :description, cast(:locale as "Locale"), :timezone,
                 false, :now, :now)
            """,
            new MapSqlParameterSource()
                    .addValue("id", projectId)
                    .addValue("organizationId", organizationId)
                    .addValue("name", clinicName)
                    .addValue("slug", clinicSlug)
                    .addValue("intakePortalKey", intakeKey)
                    .addValue("description", "Created by the platform console.")
                    .addValue("locale", "ar")
                    .addValue("timezone", "Asia/Riyadh")
                    .addValue("now", now));

        UUID userId = UUID.randomUUID();
        jdbc.update("""
            insert into "users"
                ("id", "organizationId", "email", "passwordHash", "fullName", "role", "locale",
                 "isActive", "createdAt", "updatedAt")
            values
                (:id, :organizationId, :email, :passwordHash, :fullName, cast(:role as "UserRole"), cast(:locale as "Locale"),
                 true, :now, :now)
            """,
            new MapSqlParameterSource()
                    .addValue("id", userId)
                    .addValue("organizationId", organizationId)
                    .addValue("email", normalizedEmail)
                    .addValue("passwordHash", hash)
                    .addValue("fullName", adminName)
                    .addValue("role", "ADMIN")
                    .addValue("locale", "ar")
                    .addValue("now", now));

        // The membership is what makes the account able to reach the clinic it administers. Without
        // it the user exists in the organization but cannot see the project they were created for.
        jdbc.update("""
            insert into "project_memberships" ("id", "organizationId", "projectId", "userId", "createdAt")
            values (:id, :organizationId, :projectId, :userId, :now)
            """,
            new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID())
                    .addValue("organizationId", organizationId)
                    .addValue("projectId", projectId)
                    .addValue("userId", userId)
                    .addValue("now", now));

        audit.record(PlatformAuditRepository.PROJECT_CREATED, operator.operatorId(),
                operator.sessionId(), organizationId, projectId, "Project", projectId.toString(),
                reason, "clinic=" + clinicSlug + " firstAdmin=" + normalizedEmail,
                PlatformAuditRepository.ALLOWED, correlationId, null);
        audit.record(PlatformAuditRepository.ACCOUNT_CREATED, operator.operatorId(),
                operator.sessionId(), organizationId, projectId, "User", userId.toString(),
                reason, "first administrator of " + clinicSlug,
                PlatformAuditRepository.ALLOWED, correlationId, null);

        return new ClinicCreated(organizationId, projectId, clinicName, userId, normalizedEmail);
    }

    /**
     * Creates a further account inside an existing clinic.
     *
     * <p>Separate from {@link #createClinic} on purpose: the first administrator is a consequence of
     * the clinic existing, while this is an independent act and gets its own audit row with its own
     * reason.
     */
    @Transactional
    public UUID createAccount(PlatformOperatorContext operator,
                              UUID organizationId,
                              UUID projectId,
                              String email,
                              String fullName,
                              String role,
                              String password,
                              String reason,
                              String correlationId) {
        if (operator == null || !operator.permits(PlatformCapability.CREATE_ACCOUNT, Instant.now())) {
            // Audited like createClinic, because the audit's stated purpose is to reveal an
            // operator probing what they may reach. Two of three write paths recording denials and
            // this one staying silent defeats that.
            if (operator != null) {
                audit.record(PlatformAuditRepository.CAPABILITY_DENIED, operator.operatorId(),
                        operator.sessionId(), organizationId, projectId, "User", null, reason,
                        "required=CREATE_ACCOUNT", PlatformAuditRepository.DENIED, correlationId, null);
            }
            throw new PlatformOperatorContextHolder.PlatformAccessDenied("CREATE_ACCOUNT is required.");
        }
        UUID project = requireProject(organizationId, projectId);
        String normalizedEmail = PasswordVerifier.normalizeEmail(email);
        if (!PasswordVerifier.isAcceptableEmail(normalizedEmail)) {
            throw new IllegalArgumentException("The address is not usable.");
        }
        String upperRole = role == null ? "EMPLOYEE" : role.trim().toUpperCase(Locale.ROOT);

        UUID userId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
            insert into "users"
                ("id", "organizationId", "email", "passwordHash", "fullName", "role", "locale",
                 "isActive", "createdAt", "updatedAt")
            values
                (:id, :organizationId, :email, :passwordHash, :fullName, cast(:role as "UserRole"), cast('ar' as "Locale"), true, :now, :now)
            """,
            new MapSqlParameterSource()
                    .addValue("id", userId)
                    .addValue("organizationId", organizationId)
                    .addValue("email", normalizedEmail)
                    .addValue("passwordHash", new PasswordVerifier().hash(password))
                    .addValue("fullName", fullName)
                    .addValue("role", upperRole)
                    .addValue("now", now));

        jdbc.update("""
            insert into "project_memberships" ("id", "organizationId", "projectId", "userId", "createdAt")
            values (:id, :organizationId, :projectId, :userId, :now)
            """,
            new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID())
                    .addValue("organizationId", organizationId)
                    .addValue("projectId", project)
                    .addValue("userId", userId)
                    .addValue("now", now));

        audit.record(PlatformAuditRepository.ACCOUNT_CREATED, operator.operatorId(),
                operator.sessionId(), organizationId, project, "User", userId.toString(), reason,
                "role=" + upperRole, PlatformAuditRepository.ALLOWED, correlationId, null);
        return userId;
    }

    /** Grants an existing user access to one more clinic. */
    @Transactional
    public void grantAccess(PlatformOperatorContext operator,
                            UUID organizationId,
                            UUID projectId,
                            UUID userId,
                            String reason,
                            String correlationId) {
        if (operator == null || !operator.permits(PlatformCapability.GRANT_ACCESS, Instant.now())) {
            if (operator != null) {
                audit.record(PlatformAuditRepository.CAPABILITY_DENIED, operator.operatorId(),
                        operator.sessionId(), organizationId, projectId, "User",
                        userId == null ? null : userId.toString(), reason,
                        "required=GRANT_ACCESS", PlatformAuditRepository.DENIED, correlationId, null);
            }
            throw new PlatformOperatorContextHolder.PlatformAccessDenied("GRANT_ACCESS is required.");
        }
        UUID project = requireProject(organizationId, projectId);

        // The user must already belong to the same organization. Granting a membership across
        // tenants would be the cross-tenant write this whole tier exists to avoid.
        Integer belongs = jdbc.queryForObject(
                "select count(*) from \"users\" where \"id\" = :id and \"organizationId\" = :org",
                new MapSqlParameterSource().addValue("id", userId).addValue("org", organizationId),
                Integer.class);
        if (belongs == null || belongs == 0) {
            audit.record(PlatformAuditRepository.ACCESS_GRANTED, operator.operatorId(),
                    operator.sessionId(), organizationId, project, "User", userId.toString(), reason,
                    "user is not in this organization", PlatformAuditRepository.DENIED,
                    correlationId, null);
            throw new PlatformOperatorContextHolder.PlatformAccessDenied(
                    "That user does not belong to this organization.");
        }

        jdbc.update("""
            insert into "project_memberships" ("id", "organizationId", "projectId", "userId", "createdAt")
            values (:id, :organizationId, :projectId, :userId, :now)
            on conflict do nothing
            """,
            new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID())
                    .addValue("organizationId", organizationId)
                    .addValue("projectId", project)
                    .addValue("userId", userId)
                    .addValue("now", Timestamp.from(Instant.now())));

        audit.record(PlatformAuditRepository.ACCESS_GRANTED, operator.operatorId(),
                operator.sessionId(), organizationId, project, "User", userId.toString(), reason,
                null, PlatformAuditRepository.ALLOWED, correlationId, null);
    }

    private boolean organizationExists(UUID organizationId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from \"organizations\" where \"id\" = :id and \"isActive\"",
                new MapSqlParameterSource("id", organizationId), Integer.class);
        return count != null && count > 0;
    }

    // ------------------------------------------------------------------
    // verification helpers
    //
    // Public because an onboarding operation is only proven by what it left behind, and the
    // integration test needs to read that back through the same object rather than by writing its
    // own SQL. Each returns a count so a caller can assert a value, not merely that a row exists.

    /** How many rows in {@code projects} match this organization and project id. */
    public int requireProjectCount(UUID organizationId, UUID projectId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from \"projects\" where \"id\" = :id and \"organizationId\" = :org",
                new MapSqlParameterSource().addValue("id", projectId).addValue("org", organizationId),
                Integer.class);
        return count == null ? 0 : count;
    }

    /** How many {@code users} rows match this organization and user id. */
    public int accountCountIn(UUID organizationId, UUID userId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from \"users\" where \"id\" = :id and \"organizationId\" = :org",
                new MapSqlParameterSource().addValue("id", userId).addValue("org", organizationId),
                Integer.class);
        return count == null ? 0 : count;
    }

    /** How many memberships join this user to this clinic. */
    public int membershipCount(UUID projectId, UUID userId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from \"project_memberships\" where \"projectId\" = :p and \"userId\" = :u",
                new MapSqlParameterSource().addValue("p", projectId).addValue("u", userId),
                Integer.class);
        return count == null ? 0 : count;
    }

    private UUID requireProject(UUID organizationId, UUID projectId) {
        if (organizationId == null || projectId == null) {
            throw new PlatformOperatorContextHolder.PlatformAccessDenied("A clinic is required.");
        }
        Integer count = jdbc.queryForObject("""
                select count(*) from "projects"
                 where "id" = :id and "organizationId" = :org
                """,
                new MapSqlParameterSource().addValue("id", projectId).addValue("org", organizationId),
                Integer.class);
        if (count == null || count == 0) {
            throw new PlatformOperatorContextHolder.PlatformAccessDenied("No such clinic.");
        }
        return projectId;
    }
}