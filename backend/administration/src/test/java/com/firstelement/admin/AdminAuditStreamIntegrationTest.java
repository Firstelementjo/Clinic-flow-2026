package com.firstelement.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstelement.admin.audit.AdminAuditStreamWriter;
import com.firstelement.admin.audit.AuditStreamRepository;
import com.firstelement.admin.audit.AuditVocabularies;
import com.firstelement.admin.audit.AuditWrite;
import com.firstelement.security.TenantContext;
import com.firstelement.security.query.TenantQueryGuard;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * ADR-0005 §5 made physical: the three append-only streams, their writers, and their read models.
 *
 * <h2>What is being falsified</h2>
 *
 * <ol>
 *   <li>An Admin action lands on {@code admin_audit_events} with the actor resolved from the
 *       {@code TenantContext} and never from the request;</li>
 *   <li>the retention class is resolved from the tenant's own {@code retention_classes} row, and an
 *       undeclared stream is written as the visible {@code UNDECLARED} token rather than a
 *       plausible-looking default;</li>
 *   <li>a worker outcome lands on {@code job_integration_events} and a FAILED row with no
 *       {@code failureCode} is refused before the database sees it;</li>
 *   <li>a DENIED security row without a {@code denyReason} is refused, because a denial stream of
 *       timestamps with no reasons is worthless;</li>
 *   <li>the read model unions the three new tables <em>and</em> the legacy {@code audit_logs} table,
 *       and says which table each row came from — because during the strangler window the legacy
 *       writer is still live and omitting it would make logins appear to have stopped.</li>
 * </ol>
 */
@SpringBootTest(classes = AdminTestApplication.class)
class AdminAuditStreamIntegrationTest extends AdminIntegrationTestBase {

    @Autowired
    private AdminAuditStreamWriter writer;
    @Autowired
    private AuditStreamRepository streams;
    @Autowired
    private TenantQueryGuard guard;
    @Autowired
    private AdminFixture fixture;

    @BeforeEach
    void reset() {
        fixture.reset();
    }

    @Nested
    @DisplayName("the admin stream")
    class AdminStream {

        @Test
        @DisplayName("an Admin action is recorded with the actor from the resolved context")
        void writesWithResolvedActor() {
            TenantContext admin = PrincipalScope.adminA();

            PrincipalScope.run(admin, () -> writer.admin(
                AuditWrite.builder(AdminFixture.ORG_A)
                    .principalType(AuditVocabularies.AuditPrincipalType.USER)
                    .principalName("USER:" + AdminFixture.ADMIN_A)
                    .actorUserId(AdminFixture.ADMIN_A)
                    .action("test.recorded")
                    .resourceType("Seeded")
                    .resourceId(AdminFixture.PROJECT_A.toString())
                    .metadata(Map.of("count", "1"))
                    .retentionClass("ADMIN_ACTIONS")
                    .build()));

            var scope = guard.tenantScope(admin);
            var events = streams.events(scope, AdminFixture.now().minusHours(1), 50);
            assertThat(events).hasSize(1);
            AuditStreamRepository.AuditRow row = events.get(0);
            assertThat(row.source()).isEqualTo("ADMIN_AUDIT_EVENT");
            assertThat(row.action()).isEqualTo("test.recorded");
            assertThat(row.actorUserId()).isEqualTo(AdminFixture.ADMIN_A);
            assertThat(row.principalName()).isEqualTo("USER:" + AdminFixture.ADMIN_A);
            assertThat(row.outcome()).isEqualTo("SUCCESS");
            assertThat(row.retentionClass()).isEqualTo("ADMIN_ACTIONS");
            assertThat(row.metadata()).containsEntry("count", "1");
        }

        @Test
        @DisplayName("metadata is sanitized at write time, so a careless key writes [redacted]")
        void sanitizesMetadata() {
            TenantContext admin = PrincipalScope.adminA();

            PrincipalScope.run(admin, () -> writer.admin(
                AuditWrite.builder(AdminFixture.ORG_A)
                    .principalType(AuditVocabularies.AuditPrincipalType.USER)
                    .principalName("USER:" + AdminFixture.ADMIN_A)
                    .actorUserId(AdminFixture.ADMIN_A)
                    .action("test.careless")
                    .resourceType("Seeded")
                    // The rule is "ids, counts, booleans, policy names"; this key breaks it, and the
                    // mechanical backstop is what makes the mistake a redacted row rather than a leak.
                    .metadata(Map.of("apiKey", "sk-live-should-never-be-persisted"))
                    .build()));

            var scope = guard.tenantScope(admin);
            var row = streams.events(scope, AdminFixture.now().minusHours(1), 10).get(0);
            assertThat(row.metadata()).containsEntry("apiKey", "[redacted]");
            assertThat(row.metadata().toString())
                .doesNotContain("sk-live-should-never-be-persisted");
        }

        @Test
        @DisplayName("a row without a tenant is refused: organizationId is NOT NULL on every stream")
        void refusesTenantlessRow() {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                AuditWrite.builder(null).action("x").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("organizationId");
        }
    }

    @Nested
    @DisplayName("the retention class")
    class RetentionClass {

        @Test
        @DisplayName("a declared class is resolved from the tenant's own configuration")
        void resolvesDeclaredClass() {
            fixture.retentionClass(AdminFixture.ORG_A, null, "ADMIN_ACTIONS", "ADMIN", 3650);
            TenantContext admin = PrincipalScope.adminA();

            PrincipalScope.run(admin, () -> writer.adminAction(guard.tenantScope(admin), admin,
                AuditWrite.builder(AdminFixture.ORG_A)
                    .action("test.resolved")
                    .resourceType("Seeded")));

            var row = streams.events(guard.tenantScope(admin), AdminFixture.now().minusHours(1), 10)
                .get(0);
            assertThat(row.retentionClass())
                .as("the class on the row must be the one the tenant configured, not a constant")
                .isEqualTo("ADMIN_ACTIONS");
        }

        @Test
        @DisplayName("an undeclared stream writes UNDECLARED, never a plausible-looking default")
        void undeclaredIsVisible() {
            // No retention_classes row for ORG_A.
            TenantContext admin = PrincipalScope.adminA();

            PrincipalScope.run(admin, () -> writer.admin(AuditWrite.builder(AdminFixture.ORG_A)
                .principalType(AuditVocabularies.AuditPrincipalType.USER)
                .principalName("USER:" + AdminFixture.ADMIN_A)
                .actorUserId(AdminFixture.ADMIN_A)
                .action("test.undeclared")
                .resourceType("Seeded")
                .build()));

            var row = streams.events(guard.tenantScope(admin), AdminFixture.now().minusHours(1), 10)
                .get(0);
            assertThat(row.retentionClass())
                .as("ADR-0009 rule 4: a purge must not run until the class is populated, so an "
                    + "unpopulated class has to be visible rather than plausible")
                .isEqualTo(AuditVocabularies.UNDECLARED_RETENTION_CLASS);
        }
    }

    @Nested
    @DisplayName("the security stream")
    class SecurityStream {

        @Test
        @DisplayName("a denial with no reason is refused before the database sees it")
        void refusesUnreasonedDenial() {
            TenantContext admin = PrincipalScope.adminA();
            AuditWrite write = AuditWrite.builder(AdminFixture.ORG_A)
                .projectId(AdminFixture.PROJECT_A)
                .principalType(AuditVocabularies.AuditPrincipalType.USER)
                .principalName("USER:" + AdminFixture.ADMIN_A)
                .actorUserId(AdminFixture.ADMIN_A)
                .action("test.denied")
                .resourceType("Seeded")
                .outcome(AuditVocabularies.AuditOutcome.DENIED)
                .build();

            org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> writer.security(write, AuditVocabularies.SecurityEventKind.PERMISSION_DENIED,
                    "session-under-test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("denyReason");
        }

        @Test
        @DisplayName("a denial with a reason is written, and shows up on the denial list")
        void writesReasonedDenial() {
            TenantContext admin = PrincipalScope.adminA();
            fixture.deniedSecurityEvent(AdminFixture.ORG_A, AdminFixture.PROJECT_A,
                AdminFixture.EMPLOYEE_USER_A, "PERMISSION_DENIED", "user-out-of-scope");

            var scope = guard.scope(admin, AdminFixture.PROJECT_A);
            var denials = streams.denials(scope, AdminFixture.now().minusHours(1), 20);
            assertThat(denials).hasSize(1);
            assertThat(denials.get(0).eventType()).isEqualTo("PERMISSION_DENIED");
            assertThat(denials.get(0).denyReason()).isEqualTo("user-out-of-scope");
        }

        @Test
        @DisplayName("a security event with no project is not written and the caller is told")
        void refusesTenantWideSecurityEvent() {
            TenantContext admin = PrincipalScope.adminA();
            boolean written = PrincipalScope.call(admin, () -> writer.security(
                AuditWrite.builder(AdminFixture.ORG_A)
                    .principalType(AuditVocabularies.AuditPrincipalType.USER)
                    .principalName("USER:" + AdminFixture.ADMIN_A)
                    .actorUserId(AdminFixture.ADMIN_A)
                    .action("test.no-project")
                    .resourceType("Seeded")
                    .outcome(AuditVocabularies.AuditOutcome.DENIED)
                    .denyReason("organization-out-of-scope")
                    .build(),
                AuditVocabularies.SecurityEventKind.PERMISSION_DENIED, "session-under-test"));

            assertThat(written)
                .as("security_events.projectId is NOT NULL by design; inventing a project to "
                    + "satisfy the constraint would attribute the event to a project not involved")
                .isFalse();
        }
    }

    @Nested
    @DisplayName("the job/integration stream")
    class JobStream {

        @Test
        @DisplayName("a FAILED row with no failureCode is refused")
        void refusesUncodedFailure() {
            TenantContext admin = PrincipalScope.adminA();
            AuditWrite write = AuditWrite.builder(AdminFixture.ORG_A)
                .principalType(AuditVocabularies.AuditPrincipalType.JOB)
                .principalName("JOB:worker")
                .action("test.failed")
                .resourceType("OutboxEvent")
                .outcome(AuditVocabularies.AuditOutcome.FAILED)
                .build();

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> writer.jobIntegration(write,
                AuditVocabularies.JobEventKind.JOB_FAILED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failureCode");
        }

        @Test
        @DisplayName("a replay outcome is recorded with its queue and the worker as JOB, not WORKER")
        void recordsWorkerAsJob() {
            TenantContext admin = PrincipalScope.adminA();

            PrincipalScope.run(admin, () -> writer.jobIntegration(
                AuditWrite.builder(AdminFixture.ORG_A)
                    .projectId(AdminFixture.PROJECT_A)
                    .principalType(AuditVocabularies.AuditPrincipalType.JOB)
                    .principalName("JOB:fe-worker@pod-7")
                    .action("maintenance.replay")
                    .resourceType("OutboxEvent")
                    .resourceId(AdminFixture.ORG_A.toString())
                    .outcome(AuditVocabularies.AuditOutcome.SUCCESS)
                    .queueName("incoming-message")
                    .jobId("job-1")
                    .build(),
                AuditVocabularies.JobEventKind.JOB_REPLAYED));

            var scope = guard.tenantScope(admin);
            var events = streams.integrationEventsForTenant(scope, AdminFixture.now().minusHours(1));
            assertThat(events).hasSize(1);
            assertThat(events.get(0).eventType()).isEqualTo("JOB_REPLAYED");
            assertThat(events.get(0).queueName()).isEqualTo("incoming-message");
        }
    }

    @Nested
    @DisplayName("the merged read model")
    class MergedReadModel {

        @Test
        @DisplayName("all four physical sources appear, each labelled with the table it came from")
        void unionsAllFourSources() {
            fixture.legacyAudit(AdminFixture.ORG_A, AdminFixture.ADMIN_A, "User.Login.Succeeded");
            fixture.deniedSecurityEvent(AdminFixture.ORG_A, AdminFixture.PROJECT_A,
                AdminFixture.EMPLOYEE_USER_A, "PERMISSION_DENIED", "user-out-of-scope");
            PrincipalScope.run(PrincipalScope.adminA(), () -> writer.admin(
                AuditWrite.builder(AdminFixture.ORG_A)
                    .principalType(AuditVocabularies.AuditPrincipalType.USER)
                    .principalName("USER:" + AdminFixture.ADMIN_A)
                    .actorUserId(AdminFixture.ADMIN_A)
                    .action("test.merged")
                    .resourceType("Seeded")
                    .build()));

            var scope = guard.tenantScope(PrincipalScope.adminA());
            var events = streams.events(scope, AdminFixture.now().minusHours(1), 100);
            assertThat(events.stream().map(AuditStreamRepository.AuditRow::source).toList())
                .contains("LEGACY_AUDIT_LOG", "SECURITY_EVENT", "ADMIN_AUDIT_EVENT");
            assertThat(streams.streamCounts(scope, AdminFixture.now().minusHours(1)))
                .containsEntry("LEGACY_AUDIT_LOG", 1)
                .containsEntry("SECURITY", 1)
                .containsEntry("ADMIN", 1)
                .containsEntry("JOB_INTEGRATION", 0);
        }

        @Test
        @DisplayName("a legacy row reports no outcome, because audit_logs has no outcome column")
        void legacyOutcomeIsNull() {
            fixture.legacyAudit(AdminFixture.ORG_A, AdminFixture.ADMIN_A, "User.Login.Succeeded");
            var scope = guard.tenantScope(PrincipalScope.adminA());
            var legacy = streams.events(scope, AdminFixture.now().minusHours(1), 50).stream()
                .filter(row -> "LEGACY_AUDIT_LOG".equals(row.source()))
                .findFirst()
                .orElseThrow();
            assertThat(legacy.outcome())
                .as("reporting SUCCESS would be a fact the table does not carry")
                .isNull();
            assertThat(legacy.action()).isEqualTo("User.Login.Succeeded");
        }
    }

    @Nested
    @DisplayName("cross-tenant negatives")
    class CrossTenant {

        @Test
        @DisplayName("one tenant's audit stream contains none of another's rows")
        void streamsAreTenantScoped() {
            fixture.legacyAudit(AdminFixture.ORG_A, AdminFixture.ADMIN_A, "Alpha.Only");
            fixture.legacyAudit(AdminFixture.ORG_B, AdminFixture.ADMIN_B, "Beta.Only");

            var alphaEvents = streams.events(guard.tenantScope(PrincipalScope.adminA()),
                AdminFixture.now().minusHours(1), 100);
            assertThat(alphaEvents).isNotEmpty();
            assertThat(alphaEvents).allSatisfy(row ->
                assertThat(row.organizationId()).isEqualTo(AdminFixture.ORG_A));
            assertThat(alphaEvents.stream().map(AuditStreamRepository.AuditRow::action).toList())
                .doesNotContain("Beta.Only");
        }

        @Test
        @DisplayName("one tenant's security denials contain none of another's")
        void denialsAreTenantScoped() {
            fixture.deniedSecurityEvent(AdminFixture.ORG_A, AdminFixture.PROJECT_A,
                AdminFixture.EMPLOYEE_USER_A, "PERMISSION_DENIED", "alpha-denial");
            fixture.deniedSecurityEvent(AdminFixture.ORG_B, AdminFixture.PROJECT_B,
                AdminFixture.SUBJECT_B, "PERMISSION_DENIED", "beta-denial");

            var betaDenials = streams.denials(guard.scope(PrincipalScope.adminB(),
                AdminFixture.PROJECT_B), AdminFixture.now().minusHours(1), 50);
            assertThat(betaDenials).isNotEmpty();
            assertThat(betaDenials).allSatisfy(row ->
                assertThat(row.denyReason()).isEqualTo("beta-denial"));
        }

        @Test
        @DisplayName("stream counts are per tenant, so a beta operator cannot see alpha's volume")
        void countsAreTenantScoped() {
            fixture.legacyAudit(AdminFixture.ORG_A, AdminFixture.ADMIN_A, "Alpha.Only");
            var alphaCounts = streams.streamCounts(guard.tenantScope(PrincipalScope.adminA()),
                AdminFixture.now().minusHours(1));
            var betaCounts = streams.streamCounts(guard.tenantScope(PrincipalScope.adminB()),
                AdminFixture.now().minusHours(1));

            assertThat(alphaCounts.get("LEGACY_AUDIT_LOG")).isEqualTo(1);
            assertThat(betaCounts.get("LEGACY_AUDIT_LOG")).isZero();
        }
    }

    @Nested
    @DisplayName("audit failure isolation")
    class FailureIsolation {

        @Test
        @DisplayName("a writer error does not propagate: an action already taken must not be undone")
        void writerNeverThrows() {
            // The composite tenant FK on actorUserId means a user from another tenant is refused by
            // the database. The writer logs and swallows it, which is the documented contract.
            UUID foreignUser = AdminFixture.ADMIN_B;
            TenantContext admin = PrincipalScope.adminA();

            org.assertj.core.api.Assertions.assertThatCode(() -> PrincipalScope.run(admin, () ->
                writer.admin(AuditWrite.builder(AdminFixture.ORG_A)
                    .principalType(AuditVocabularies.AuditPrincipalType.USER)
                    .principalName("USER:" + foreignUser)
                    .actorUserId(foreignUser)
                    .action("test.cross-tenant-actor")
                    .resourceType("Seeded")
                    .build())))
                .doesNotThrowAnyException();

            // State, not the message: nothing was written for the wrong tenant either.
            assertThat(fixture.tenantRows("admin_audit_events", AdminFixture.ORG_B)).isZero();
        }
    }
}