package com.firstelement.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.admin.identity.IdentityDirectoryRepository;
import com.firstelement.admin.identity.IdentityDirectoryService;
import com.firstelement.security.Capability;
import com.firstelement.security.PrincipalType;
import com.firstelement.security.TenantContext;
import com.firstelement.security.error.PolicyDeniedException;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.permission.RolePermissions;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The identity read model, at the two questions an operator actually asks: "who can do what here"
 * and "is this account healthy".
 *
 * <h2>What is proved here</h2>
 *
 * <ol>
 *   <li><b>No projection selects a secret.</b> {@code users."passwordHash"} and
 *       {@code sessions."tokenHash"} are in the schema and in no SELECT list in
 *       {@link IdentityDirectoryRepository}. The assertions are about the <em>row shape</em>, not
 *       about redaction afterwards: a field that was never selected cannot be leaked by a later
 *       change to how a row is rendered.</li>
 *   <li><b>Effective permissions are delegated, not re-derived.</b> Phase 7 must not grow a second
 *       definition of what a person can do, so the inspector's answer is compared against
 *       {@link RolePermissions} — the same table the boundary enforces.</li>
 *   <li><b>A foreign user id is the same answer as a nonexistent one.</b> Both are refused, so the
 *       directory cannot be used to probe for user ids across tenants.</li>
 *   <li><b>The two FR-6 items with no table are answered honestly.</b> There is no {@code api_keys}
 *       table and no {@code service_principals} table in the promoted schema, so the read model
 *       returns the capability grammar plus the machine principals this tenant has actually been
 *       seen using, and reports credential <em>presence</em> as a database-computed boolean.</li>
 * </ol>
 */
@SpringBootTest(classes = AdminTestApplication.class)
class IdentityDirectoryIntegrationTest extends AdminIntegrationTestBase {

    @Autowired
    private IdentityDirectoryService identity;
    @Autowired
    private AdminFixture fixture;

    @BeforeEach
    void reset() {
        fixture.reset();
    }

    // ==================================================================
    // the directory
    // ==================================================================

    @Nested
    @DisplayName("the tenant directory")
    class Directory {

        @Test
        @DisplayName("it counts this tenant only, and every figure is present at zero")
        void countsOnlyThisTenant() {
            // A foreign tenant with real rows: if the filter leaked, the numbers would move.
            UUID foreignAdmin = UUID.randomUUID();
            fixture.user(foreignAdmin, AdminFixture.ORG_B, "other@b.test", "ADMIN");
            TenantContext admin = PrincipalScope.adminA();

            var view = PrincipalScope.call(admin, () -> identity.directory(admin));

            assertThat(view.summary().totalUsers()).isEqualTo(3);
            assertThat(view.summary().administrators()).isEqualTo(1);
            assertThat(view.summary().inactiveUsers()).isZero();
            assertThat(view.users()).extracting(IdentityDirectoryRepository.UserRow::id)
                .containsExactlyInAnyOrder(AdminFixture.ADMIN_A, AdminFixture.EMPLOYEE_USER_A,
                    AdminFixture.SUBJECT_A);
            assertThat(view.memberships()).hasSize(4);
            assertThat(view.users()).allSatisfy(user ->
                assertThat(user.organizationId()).isEqualTo(AdminFixture.ORG_A));
        }

        @Test
        @DisplayName("a user's live-session and membership counts are measured, not assumed")
        void countsAreMeasured() {
            fixture.session(AdminFixture.ORG_A, AdminFixture.SUBJECT_A, "live", "10.0.0.1",
                AdminFixture.now().plusHours(1), null);
            fixture.session(AdminFixture.ORG_A, AdminFixture.SUBJECT_A, "expired", "10.0.0.2",
                AdminFixture.now().minusHours(1), null);
            fixture.session(AdminFixture.ORG_A, AdminFixture.SUBJECT_A, "revoked", "10.0.0.3",
                AdminFixture.now().plusHours(1), AdminFixture.now());
            TenantContext admin = PrincipalScope.adminA();

            var view = PrincipalScope.call(admin, () -> identity.directory(admin));

            var subject = view.users().stream()
                .filter(user -> user.id().equals(AdminFixture.SUBJECT_A)).findFirst().orElseThrow();
            assertThat(subject.liveSessions())
                .as("an expired and a revoked session are both unusable, so one is live")
                .isEqualTo(1);
            assertThat(subject.membershipCount()).isEqualTo(1);
            assertThat(view.summary().liveSessions()).isEqualTo(1);
        }

        @Test
        @DisplayName("no user row carries a password hash: the column is absent from the projection")
        void noPasswordHash() {
            TenantContext admin = PrincipalScope.adminA();
            var view = PrincipalScope.call(admin, () -> identity.directory(admin));

            // The row shape is the assertion: there is no field to render, so there is nothing a
            // later serialisation change could start emitting.
            assertThat(IdentityDirectoryRepository.UserRow.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("passwordHash", "password", "tokenHash");
        }
    }

    // ==================================================================
    // sessions
    // ==================================================================

    @Nested
    @DisplayName("sessions")
    class Sessions {

        @Test
        @DisplayName("a session is readable without its token, and its liveness is answered")
        void sessionWithoutToken() {
            UUID live = fixture.session(AdminFixture.ORG_A, AdminFixture.SUBJECT_A, "browser",
                "10.0.0.9", AdminFixture.now().plusHours(2), null);
            UUID revoked = fixture.session(AdminFixture.ORG_A, AdminFixture.EMPLOYEE_USER_A,
                "browser", "10.0.0.10", AdminFixture.now().plusHours(2), AdminFixture.now());
            TenantContext admin = PrincipalScope.adminA();

            var rows = PrincipalScope.call(admin,
                () -> identity.sessions(admin, AdminFixture.SUBJECT_A));

            assertThat(rows).hasSize(1);
            var row = rows.get(0);
            assertThat(row.id()).isEqualTo(live);
            assertThat(row.userEmail()).isEqualTo("subject@a.test");
            assertThat(row.ipAddress()).isEqualTo("10.0.0.9");
            assertThat(row.live(AdminFixture.now())).isTrue();
            assertThat(revoked).isNotNull();

            assertThat(IdentityDirectoryRepository.SessionRow.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("tokenHash", "token");
        }

        @Test
        @DisplayName("another tenant's user id is refused, and so is a nonexistent one")
        void crossTenantUserIsRefused() {
            TenantContext admin = PrincipalScope.adminA();

            assertThatThrownBy(() -> PrincipalScope.call(admin,
                () -> identity.sessions(admin, AdminFixture.SUBJECT_B)))
                .isInstanceOf(PolicyDeniedException.class);
            assertThatThrownBy(() -> PrincipalScope.call(admin,
                () -> identity.sessions(admin, UUID.randomUUID())))
                .as("a nonexistent id must be indistinguishable from a foreign one")
                .isInstanceOf(PolicyDeniedException.class);
        }
    }

    // ==================================================================
    // effective permissions
    // ==================================================================

    @Nested
    @DisplayName("effective permissions")
    class EffectivePermissions {

        @Test
        @DisplayName("the inspector's answer is the enforcing table's answer, not a second opinion")
        void delegatedNotRederived() {
            TenantContext admin = PrincipalScope.adminA();

            var employee = PrincipalScope.call(admin,
                () -> identity.inspect(admin, AdminFixture.EMPLOYEE_USER_A));

            var expected = RolePermissions.forRole(com.firstelement.security.UserRole.EMPLOYEE);
            assertThat(employee.permissions().effectivePermissions())
                .containsExactlyInAnyOrderElementsOf(
                    expected.stream().map(Permission::value).toList());
            assertThat(employee.user().id()).isEqualTo(AdminFixture.EMPLOYEE_USER_A);
            assertThat(employee.user().email()).isEqualTo("staff@a.test");

            // The employee grant holds none of the administrative permissions this module's routes
            // ask for, which is why every one of them refuses an employee.
            assertThat(expected).doesNotContain(Permission.USER_MANAGE, Permission.SESSION_REVOKE,
                Permission.AUDIT_LOG_READ, Permission.SECURITY_EVENT_READ, Permission.ORGANIZATION_UPDATE,
                Permission.SYSTEM_HEALTH_READ, Permission.AI_AGENT_READ);
        }

        @Test
        @DisplayName("an admin holds every permission, because ADMIN is allOf(Permission)")
        void adminHoldsEverything() {
            TenantContext admin = PrincipalScope.adminA();

            var inspection = PrincipalScope.call(admin,
                () -> identity.inspect(admin, AdminFixture.ADMIN_A));

            assertThat(inspection.permissions().effectivePermissions())
                .hasSize(Permission.values().length);
        }

        @Test
        @DisplayName("inspectSelf answers for the caller without being handed an id")
        void inspectSelfNeedsNoId() {
            TenantContext admin = PrincipalScope.adminA();

            var self = PrincipalScope.call(admin, () -> identity.inspectSelf(admin));

            assertThat(self.subject().id()).isEqualTo(AdminFixture.ADMIN_A);
            assertThat(self.role()).isEqualTo("ADMIN");
        }

        @Test
        @DisplayName("a foreign user id is refused, and the refusal never names the row")
        void crossTenantInspectionIsRefused() {
            TenantContext beta = PrincipalScope.adminB();

            assertThatThrownBy(() -> PrincipalScope.call(beta,
                () -> identity.inspect(beta, AdminFixture.ADMIN_A)))
                .isInstanceOf(PolicyDeniedException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage())
                    .as("the message names the scope, never the user it refused")
                    .doesNotContain(AdminFixture.ADMIN_A.toString()));
        }
    }

    // ==================================================================
    // machine principals and credential presence
    // ==================================================================

    @Nested
    @DisplayName("service principals, with no table to read them from")
    class ServicePrincipals {

        @Test
        @DisplayName("the capability grammar is the enforcing table, so an operator cannot review a fiction")
        void vocabularyIsTheEnforcingTable() {
            TenantContext admin = PrincipalScope.adminA();

            var view = PrincipalScope.call(admin, () -> identity.directory(admin));

            assertThat(view.servicePrincipalVocabulary()).isNotEmpty();
            assertThat(view.servicePrincipalVocabulary()).allSatisfy(row -> {
                PrincipalType type = PrincipalType.fromValue(row.principalType());
                Capability capability = Capability.valueOf(row.capability());
                // Only combinations the boundary actually accepts are listed at all...
                assertThat(capability.mayBeHeldBy(type)).isTrue();
                // ...and each row's permission set is exactly what that capability grants.
                assertThat(row.permissions()).containsExactlyInAnyOrderElementsOf(
                    RolePermissions.forCapabilities(java.util.Set.of(capability)).stream()
                        .map(Permission::value).sorted().toList());
            });
            // The administrative capabilities are marked, so an operator can see which ones an AI
            // agent may never hold (Capability#mayBeHeldBy refuses it structurally).
            assertThat(view.servicePrincipalVocabulary())
                .filteredOn(row -> "MEMBERSHIP_MANAGE".equals(row.capability()))
                .allSatisfy(row -> assertThat(row.administrative()).isTrue()
                    .satisfies(administrative ->
                        assertThat(row.principalType()).isEqualTo("SERVICE")));
        }

        @Test
        @DisplayName("observed machine principals come from this tenant's own audit streams")
        void observedPrincipalsAreTenantScoped() {
            // Alpha used a worker and a connector; Beta used a different worker. Only Alpha's must
            // appear for the Alpha administrator.
            fixture.jobIntegrationEvent(AdminFixture.ORG_A, "JOB", "fe-worker@pod-1");
            fixture.jobIntegrationEvent(AdminFixture.ORG_A, "CONNECTOR", "twilio-connector");
            fixture.jobIntegrationEvent(AdminFixture.ORG_B, "JOB", "fe-worker@other-tenant");
            TenantContext admin = PrincipalScope.adminA();

            var view = PrincipalScope.call(admin, () -> identity.directory(admin));

            assertThat(view.observedMachinePrincipals())
                .extracting(IdentityDirectoryRepository.ObservedPrincipal::principalName)
                .containsExactlyInAnyOrder("fe-worker@pod-1", "twilio-connector");
        }

        @Test
        @DisplayName("credential presence is a boolean the database computes, never a value")
        void credentialPresenceIsABoolean() {
            fixture.connection(AdminFixture.ORG_A, AdminFixture.PROJECT_A, "WHATSAPP",
                "{\"accountSid\":\"AC-real\",\"authToken\":\"super-secret\"}",
                "whsec-real", null);
            fixture.connection(AdminFixture.ORG_B, AdminFixture.PROJECT_B, "WHATSAPP",
                "{\"accountSid\":\"AC-b\"}", null, null);
            TenantContext admin = PrincipalScope.adminA();

            var view = PrincipalScope.call(admin, () -> identity.directory(admin));

            assertThat(view.credentialPresence()).hasSize(1);
            var presence = view.credentialPresence().get(0);
            assertThat(presence.hasCredentials()).isTrue();
            assertThat(presence.hasWebhookSecret()).isTrue();
            // FR-6 asks for "API keys". This schema has no api_keys table, so what exists is
            // presence per connector, and it is reported as such rather than invented.
            assertThat(IdentityDirectoryRepository.CredentialPresence.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("credentials", "webhookSecret", "apiKey", "secret");
        }
    }
}