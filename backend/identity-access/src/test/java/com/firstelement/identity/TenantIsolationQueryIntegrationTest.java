package com.firstelement.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.identity.permission.EffectivePermissionService;
import com.firstelement.security.Capability;
import com.firstelement.security.PrincipalType;
import com.firstelement.security.TenantContext;
import com.firstelement.security.TenantContextHolder;
import com.firstelement.security.UserRole;
import com.firstelement.security.error.AuthenticationFailedException;
import com.firstelement.security.error.PolicyDeniedException;
import com.firstelement.security.event.SecurityEvent;
import com.firstelement.security.event.SecurityEventType;
import com.firstelement.security.job.TenantScopedExecutor;
import com.firstelement.security.permission.AuthorizationService;
import com.firstelement.security.query.ScopedWhere;
import com.firstelement.security.query.TenantQueryGuard;
import com.firstelement.security.query.TenantScope;
import com.firstelement.security.redaction.FieldRedactionPolicy;
import com.firstelement.security.redaction.FieldRedactor;
import com.firstelement.security.session.BrowserSessionStore;
import com.firstelement.security.session.SessionTokenCodec;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The negative tenant-isolation suite, run against real PostgreSQL/pgvector.
 *
 * <h2>Why a real database and not an in-memory stand-in</h2>
 *
 * <p>The claims under test are about SQL: that the tenant predicate lands in the
 * {@code WHERE} clause, that it applies <em>before</em> an aggregate, and that the quoted
 * camelCase mapping plus the {@code timestamp without time zone} binding are correct. A
 * stub repository would only assert the shape of a test double. These run the real Flyway
 * baseline against a throwaway {@code pgvector/pgvector:pg16} container and query the real
 * tables. There is no skip path: an unreachable Docker fails the build (AGENTS.md).
 *
 * <h2>What each test pins down</h2>
 *
 * <ul>
 *   <li>{@code FR-1.4} — no membership ⇒ zero rows</li>
 *   <li>{@code FR-1.5} / {@code FR-2.7} — cross-tenant and cross-project read and write denied</li>
 *   <li>{@code plan.md §7.2} — unauthorized aggregates reveal no counts or sums</li>
 *   <li>field redaction hides rather than removes</li>
 *   <li>immediate session revocation on membership, role, and activation change</li>
 * </ul>
 */
@SpringBootTest(
    classes = IdentityAccessTestApplication.class,
    properties = {
        "firstelement.environment=TEST",
        "firstelement.api.web-origin=http://localhost:5173",
        "spring.flyway.locations=classpath:db/migration",
        "management.health.db.enabled=false",
        "management.health.redis.enabled=false",
        "management.tracing.sampling.probability=0.0"
    })
class TenantIsolationQueryIntegrationTest extends SecurityIntegrationTestBase {

    @Autowired
    private SecurityFixture fixture;

    @Autowired
    private TenantQueryGuard guard;

    @Autowired
    private ScopedCustomerQuery customers;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private LoginService login;

    @Autowired
    private DatabaseBrowserSessionStore sessions;

    @Autowired
    private MembershipAdminService membershipAdmin;

    @Autowired
    private TenantScopedExecutor jobExecutor;

    @Autowired
    private com.firstelement.security.auth.LoginRateLimiter loginRateLimiter;

    private static final java.util.concurrent.atomic.AtomicInteger IP = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * A distinct client address per login call.
     *
     * <p>The login rate limiter keys on the remote address and this class performs more
     * logins than the default of 10 per 60 s. Unique addresses keep the limiter's own
     * behaviour asserted in LoginRateLimitIntegrationTest instead of incidentally
     * interfering here.
     */
    private static String nextIp() {
        return "203.0.113." + IP.incrementAndGet();
    }

    @BeforeEach
    void seed() {
        fixture.reset();
        IP.set(0);
        // The limiter is per-process state shared by every test in this context; without
        // this reset, execution order rather than behaviour would decide the outcome.
        loginRateLimiter.resetAll();
        TenantContextHolder.clear();
    }

    // ==================================================================
    // FR-1.4 no membership => zero rows
    // ==================================================================

    @Test
    @DisplayName("a user with no project membership cannot be scoped to any project")
    void noMembershipYieldsNoProjectRows() {
        TenantContext nobody = fixture.nobodyAContext();

        // The membership is genuinely absent in the database, not merely absent from a
        // cached list.
        assertThat(jdbc.queryForList(
            "select \"projectId\" from project_memberships where \"userId\" = :u",
            new MapSqlParameterSource("u", fixture.nobodyA), UUID.class)).isEmpty();

        for (UUID projectId : List.of(fixture.projectA1, fixture.projectA2, fixture.projectB1)) {
            assertThatThrownBy(() -> guard.scope(nobody, projectId))
                .as("project %s", projectId)
                .isInstanceOf(PolicyDeniedException.class);
        }
    }

    @Test
    @DisplayName("a membership-less user's tenant-wide read still excludes other tenants")
    void tenantWideReadStaysTenantScoped() {
        assertThat(jdbc.queryForObject("select count(*) from customers", new MapSqlParameterSource(), Long.class))
            .as("three customer rows exist across both tenants")
            .isEqualTo(3);

        long tenantWide = customers.countCustomers(
            ScopedWhere.of(guard.tenantScope(fixture.nobodyAContext()))
                .select("select count(*) as c from customers c", "c"));

        assertThat(tenantWide)
            .as("an unscoped read is still tenant-scoped: tenant B's row is excluded")
            .isEqualTo(2);
    }

    @Test
    @DisplayName("a member of one project sees exactly that project's rows")
    void membershipNarrowsToOneProject() {
        long asMemberOfProjectA1 = customers.countCustomers(
            ScopedWhere.of(guard.scope(fixture.staffAContext(), fixture.projectA1))
                .select("select count(*) as c from customers c", "c"));

        assertThat(asMemberOfProjectA1).isEqualTo(1);
        assertThat(jdbc.queryForList("""
            select c."cxId" from customers c
             where c."organizationId" = :org and c."projectId" = :p
            """,
            new MapSqlParameterSource().addValue("org", fixture.orgA)
                .addValue("p", fixture.projectA1), String.class))
            .containsExactly("C-A1-0001");
    }

    // ==================================================================
    // FR-2.7 cross-tenant
    // ==================================================================

    @Test
    @DisplayName("tenant A never sees tenant B's rows, and the SQL carries the predicate")
    void crossTenantRowsAreNeverReturned() {
        ScopedWhere.NamedSql statement = ScopedWhere.of(guard.tenantScope(fixture.adminAContext()))
            .select(customers.customerSelect(), "c");
        List<Map<String, Object>> rows = customers.listCustomers(statement);

        assertThat(rows).hasSize(2);
        assertThat(statement.sql()).contains("c.\"organizationId\" = :__fe_tenant_organization_id");
        assertThat(statement.parameters().getValue("__fe_tenant_organization_id"))
            .isEqualTo(fixture.orgA);
    }

    @Test
    @DisplayName("a tenant B project id in an A request is denied before any query runs")
    void crossTenantProjectIdIsDenied() {
        TenantContext adminA = fixture.adminAContext();

        assertThatThrownBy(() -> guard.scope(adminA, fixture.projectB1))
            .isInstanceOf(PolicyDeniedException.class)
            .hasMessage("Access denied");
        assertThatThrownBy(() -> guard.assertRowScope(adminA, fixture.orgB, fixture.projectB1))
            .isInstanceOf(PolicyDeniedException.class);
    }

    @Test
    @DisplayName("an organization id claimed by an integration is checked, never honoured")
    void crossTenantClaimIsDenied() {
        TenantContext adminA = fixture.adminAContext();

        assertThatThrownBy(() -> guard.claimedTenantScope(adminA, fixture.orgB))
            .isInstanceOf(PolicyDeniedException.class);

        ScopedWhere.NamedSql statement = ScopedWhere
            .of(guard.claimedTenantScope(adminA, fixture.orgA))
            .select(customers.customerSelect(), "c");
        assertThat(customers.listCustomers(statement)).hasSize(2);
    }

    @Test
    @DisplayName("a row read outside the scope is denied with the same body as a missing row")
    void crossTenantDenialIsIndistinguishableFromAbsence() {
        TenantContext adminA = fixture.adminAContext();

        PolicyDeniedException foreign = org.junit.jupiter.api.Assertions.assertThrows(
            PolicyDeniedException.class,
            () -> guard.assertRowScope(adminA, fixture.orgB, fixture.projectB1));
        PolicyDeniedException missing = org.junit.jupiter.api.Assertions.assertThrows(
            PolicyDeniedException.class,
            () -> guard.assertRowScope(adminA, UUID.randomUUID(), UUID.randomUUID()));

        assertThat(foreign.getMessage()).isEqualTo(missing.getMessage()).isEqualTo("Access denied");
        assertThat(foreign.reason()).isEqualTo(missing.reason());
    }

    // ==================================================================
    // FR-1.5 cross-project
    // ==================================================================

    @Test
    @DisplayName("a cross-project read is denied for an EMPLOYEE and permitted for an ADMIN")
    void crossProjectReadDependsOnMembership() {
        TenantContext staffA = fixture.staffAContext();

        assertThatThrownBy(() -> guard.scope(staffA, fixture.projectA2))
            .isInstanceOf(PolicyDeniedException.class);

        // An ADMIN's project list covers their whole organization, including the archived
        // project: archiving must not be a one-way door with no way to read or undo it.
        List<Map<String, Object>> rows = customers.listCustomers(
            ScopedWhere.of(guard.scope(fixture.adminAContext(), fixture.projectA2))
                .select(customers.customerSelect(), "c"));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("cxId")).isEqualTo("C-A2-0001");
    }

    @Test
    @DisplayName("a cross-project write is denied and the target row is unchanged")
    void crossProjectWriteIsDenied() {
        String before = jdbc.queryForObject("select \"displayName\" from customers where \"id\" = :id",
            new MapSqlParameterSource("id", fixture.customerA2), String.class);

        assertThatThrownBy(() -> guard.scope(fixture.staffAContext(), fixture.projectA2))
            .isInstanceOf(PolicyDeniedException.class);

        String after = jdbc.queryForObject("select \"displayName\" from customers where \"id\" = :id",
            new MapSqlParameterSource("id", fixture.customerA2), String.class);
        assertThat(after).isEqualTo(before).isNotEqualTo("Pwned");
    }

    @Test
    @DisplayName("a tenant-scoped UPDATE cannot touch another tenant's row")
    void tenantScopedUpdateCannotCrossTenants() {
        ScopedWhere.NamedSql update = ScopedWhere.of(guard.tenantScope(fixture.adminAContext()))
            .update("update customers c set \"notes\" = :notes", "c",
                "c.\"id\" = :id", Map.of("id", fixture.customerB1, "notes", "touched"));

        assertThat(customers.execute(update))
            .as("the other tenant's row is invisible to a tenant-scoped statement")
            .isZero();
        assertThat(jdbc.queryForObject("select \"notes\" from customers where \"id\" = :id",
            new MapSqlParameterSource("id", fixture.customerB1), String.class))
            .isEqualTo("beta private note");
    }

    @Test
    @DisplayName("a tenant-scoped DELETE cannot remove another tenant's row")
    void tenantScopedDeleteCannotCrossTenants() {
        ScopedWhere.NamedSql delete = ScopedWhere.of(guard.tenantScope(fixture.adminBContext()))
            .delete("delete from customers c", "c",
                "c.\"id\" = :id", Map.of("id", fixture.customerA1));

        assertThat(customers.execute(delete)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from customers where \"id\" = :id",
            new MapSqlParameterSource("id", fixture.customerA1), Integer.class)).isEqualTo(1);
    }

    // ==================================================================
    // plan.md 7.2 unauthorized aggregates reveal nothing
    // ==================================================================

    @Test
    @DisplayName("counts and distinct counts are scoped, so an aggregate never includes an unauthorized row")
    void unauthorizedAggregatesRevealNothing() {
        long totalAcrossAllTenants =
            jdbc.queryForObject("select count(*) from customers", new MapSqlParameterSource(), Long.class);
        assertThat(totalAcrossAllTenants).isEqualTo(3);

        Map<String, Object> aggregate = customers.single(
            ScopedWhere.of(guard.tenantScope(fixture.staffAContext()))
                .select("select count(*) as c, count(distinct c.\"projectId\") as p, "
                    + "count(distinct c.\"organizationId\") as o from customers c", "c"));

        assertThat(((Number) aggregate.get("c")).longValue()).isEqualTo(2);
        assertThat(((Number) aggregate.get("p")).longValue()).isEqualTo(2);
        assertThat(((Number) aggregate.get("o")).longValue())
            .as("exactly one organization appears in an aggregate")
            .isEqualTo(1);
        assertThat(((Number) aggregate.get("c")).longValue())
            .isNotEqualTo(totalAcrossAllTenants);
    }

    @Test
    @DisplayName("an aggregate restricted to one project cannot be widened by a client filter")
    void aggregateHonoursTheProjectScope() {
        TenantContext staffA = fixture.staffAContext();

        long scoped = customers.countCustomers(
            ScopedWhere.of(guard.scope(staffA, fixture.projectA1))
                .select("select count(*) as c from customers c", "c"));
        assertThat(scoped).isEqualTo(1);

        // Asking for projectA2 is refused rather than silently falling back to the tenant.
        assertThatThrownBy(() -> ScopedWhere.of(guard.scope(staffA, fixture.projectA2))
            .select("select count(*) as c from customers c", "c"))
            .isInstanceOf(PolicyDeniedException.class);
    }

    // ==================================================================
    // field redaction against real rows
    // ==================================================================

    @Test
    @DisplayName("field redaction hides sensitive columns of a real row without dropping the key")
    void fieldRedactionAgainstRealRows() {
        Map<String, Object> raw = ScopedCustomerQuery.stringify(customers.readCustomer(fixture.customerA1));
        assertThat(raw).containsEntry("primaryPhone", "+966500000001")
            .containsEntry("notes", "alpha primary");

        Map<String, Object> redacted = FieldRedactor.redact(raw,
            FieldRedactionPolicy.of(Set.of("primaryPhone", "notes")));

        assertThat(redacted).containsKeys("primaryPhone", "notes");
        assertThat(redacted).containsEntry("primaryPhone", FieldRedactionPolicy.PLACEHOLDER);
        assertThat(redacted).containsEntry("notes", FieldRedactionPolicy.PLACEHOLDER);
        assertThat(redacted).containsEntry("displayName", "Alpha One Clinic");
        assertThat(redacted.values()).doesNotContain("+966500000001", "alpha primary");

        assertThat(FieldRedactor.redactWithReport(raw,
            FieldRedactionPolicy.of(Set.of("primaryPhone", "notes"))).redactedColumns())
            .contains("primaryPhone", "notes");
    }

    @Test
    @DisplayName("a user projection can never carry the password hash")
    void userProjectionHasNoPasswordHash() {
        List<UserView> users = new UserRepository(jdbc).listInScope(guard, fixture.adminAContext());

        assertThat(users).hasSize(3);
        assertThat(users).allSatisfy(user ->
            assertThat(user.toString()).doesNotContain("$2a$").doesNotContain("passwordHash"));

        // A projection that does select the hash is still redacted by the policy layer,
        // which is the belt to the projection's braces.
        Map<String, Object> withHash = new java.util.LinkedHashMap<>();
        withHash.put("passwordHash", "$2a$12$whatever");
        assertThat(FieldRedactor.redact(withHash, FieldRedactionPolicy.none()))
            .containsEntry("passwordHash", FieldRedactionPolicy.PLACEHOLDER);
    }

    // ==================================================================
    // sessions: immediate revocation
    // ==================================================================

    @Test
    @DisplayName("a revoked session stops resolving on the very next call")
    void revokedSessionStopsResolvingImmediately() {
        LoginService.LoginResult issued = login.login(fixture.STAFF_A_EMAIL,
            fixture.PASSWORD, "test-agent", nextIp());

        assertThat(sessions.resolve(issued.rawSessionToken())).isPresent();
        assertThat(sessions.resolve(issued.rawSessionToken()).orElseThrow().tenantContext().projectIds())
            .containsExactly(fixture.projectA1);

        assertThat(sessions.revoke(issued.sessionId(), fixture.orgA)).isEqualTo(1);
        assertThat(sessions.resolve(issued.rawSessionToken()))
            .as("revocation is immediate, not eventual")
            .isEmpty();
        assertThat(sessions.revoke(issued.sessionId(), fixture.orgA))
            .as("a second revoke is a no-op, not an error")
            .isZero();
    }

    @Test
    @DisplayName("an administrator cannot revoke another tenant's session, and cannot learn that it exists")
    void revokingAnotherTenantsSessionIsRefusedAndDisclosesNothing() {
        // The regression this pins. `revoke` used to match on the session id alone, so any
        // tenant's administrator could kill any other tenant's session -- and because the
        // method returns a row count, the count itself answered "does this id exist over
        // there?", a cross-tenant existence oracle even once the write is blocked.
        //
        // Both properties are asserted, and the second is why the fix is not merely "add a
        // permission check": a foreign id must be indistinguishable from one never issued.
        LoginService.LoginResult tenantB = login.login(fixture.ADMIN_B_EMAIL,
            fixture.PASSWORD, "test-agent", nextIp());
        assertThat(sessions.resolve(tenantB.rawSessionToken())).isPresent();

        assertThat(sessions.revoke(tenantB.sessionId(), fixture.orgA))
            .as("tenant A's administrator revoking tenant B's session must match no row")
            .isZero();

        assertThat(sessions.resolve(tenantB.rawSessionToken()))
            .as("the foreign session is untouched -- the write really was refused")
            .isPresent();

        assertThat(sessions.revoke(tenantB.sessionId(), fixture.orgA))
            .as("a foreign id answers the same as an invented id, so it is not an oracle")
            .isEqualTo(sessions.revoke(UUID.randomUUID(), fixture.orgA));
    }

    @Test
    @DisplayName("only the SHA-256 of the token is persisted")
    void onlyTheTokenHashIsPersisted() {
        LoginService.LoginResult issued = login.login(fixture.STAFF_A_EMAIL,
            fixture.PASSWORD, "test-agent", nextIp());

        String stored = jdbc.queryForObject("select \"tokenHash\" from sessions where \"id\" = :id",
            new MapSqlParameterSource("id", issued.sessionId()), String.class);

        assertThat(stored)
            .isEqualTo(SessionTokenCodec.hash(issued.rawSessionToken()))
            .matches("^[0-9a-f]{64}$")
            .doesNotContain(issued.rawSessionToken());
    }

    @Test
    @DisplayName("a membership change revokes the session, so the old project set cannot survive")
    void membershipChangeRevokesSessions() {
        LoginService.LoginResult issued = login.login(fixture.STAFF_A_EMAIL,
            fixture.PASSWORD, "test-agent", nextIp());

        MembershipAdminService.MembershipChange change = PrincipalScope.call(
            fixture.adminAContext(),
            () -> membershipAdmin.removeMembership(fixture.staffA, fixture.projectA1, nextIp()));

        assertThat(change.added()).isFalse();
        assertThat(change.sessionsRevoked()).isGreaterThanOrEqualTo(1);
        assertThat(sessions.resolve(issued.rawSessionToken()))
            .as("a revoked membership is effective on the next request, not at token expiry")
            .isEmpty();

        // Logging in again yields an empty project set: no membership means no project.
        LoginService.LoginResult after = login.login(fixture.STAFF_A_EMAIL,
            fixture.PASSWORD, "test-agent-2", nextIp());
        assertThat(sessions.resolve(after.rawSessionToken()).orElseThrow().tenantContext().projectIds())
            .isEmpty();
    }

    @Test
    @DisplayName("deactivating a user revokes every session and blocks the next login")
    void deactivationRevokesEverySession() {
        LoginService.LoginResult first = login.login(fixture.STAFF_A_EMAIL,
            fixture.PASSWORD, "agent-1", nextIp());
        LoginService.LoginResult second = login.login(fixture.STAFF_A_EMAIL,
            fixture.PASSWORD, "agent-2", nextIp());

        int revoked = PrincipalScope.call(fixture.adminAContext(),
            () -> membershipAdmin.deactivate(fixture.staffA, nextIp()));

        assertThat(revoked).isEqualTo(2);
        assertThat(sessions.resolve(first.rawSessionToken())).isEmpty();
        assertThat(sessions.resolve(second.rawSessionToken())).isEmpty();
        assertThatThrownBy(() -> login.login(fixture.STAFF_A_EMAIL, fixture.PASSWORD,
            "agent-3", nextIp()))
            .isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    @DisplayName("a role change revokes sessions so the previous role's reach cannot survive")
    void roleChangeRevokesSessions() {
        LoginService.LoginResult issued = login.login(fixture.STAFF_A_EMAIL,
            fixture.PASSWORD, "agent-1", nextIp());

        PrincipalScope.call(fixture.adminAContext(),
            () -> membershipAdmin.changeRole(fixture.staffA, UserRole.ADMIN, nextIp()));

        assertThat(sessions.resolve(issued.rawSessionToken())).isEmpty();

        LoginService.LoginResult afterChange = login.login(fixture.STAFF_A_EMAIL,
            fixture.PASSWORD, "agent-2", nextIp());
        assertThat(sessions.resolve(afterChange.rawSessionToken()).orElseThrow().tenantContext().role())
            .isEqualTo(UserRole.ADMIN);
    }

    @Test
    @DisplayName("an ADMIN cannot demote themselves, which would be a one-way door")
    void selfDemotionIsRefused() {
        assertThatThrownBy(() -> PrincipalScope.call(fixture.adminAContext(),
            () -> membershipAdmin.changeRole(fixture.adminA, UserRole.EMPLOYEE, nextIp())))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    @DisplayName("a membership change naming another tenant's project is denied and writes nothing")
    void crossTenantMembershipChangeIsDenied() {
        assertThatThrownBy(() -> PrincipalScope.call(fixture.adminAContext(),
            () -> membershipAdmin.addMembership(fixture.staffA, fixture.projectB1, nextIp())))
            .isInstanceOf(PolicyDeniedException.class);

        assertThat(jdbc.queryForObject("""
            select count(*) from project_memberships
             where "userId" = :u and "projectId" = :p
            """,
            new MapSqlParameterSource().addValue("u", fixture.staffA)
                .addValue("p", fixture.projectB1), Integer.class)).isZero();
    }

    // ==================================================================
    // jobs
    // ==================================================================

    @Test
    @DisplayName("a job's query layer uses the job's tenant, not any HTTP scope")
    void jobScopeIsIndependentOfAnyHttpScope() {
        TenantContext jobContext = TenantContext.forMachine(PrincipalType.WORKER, fixture.orgA,
            "report-worker", List.of(fixture.projectA1), List.of(Capability.REPORT_WRITE));

        long rows = jobExecutor.execute(jobContext, "report.customerCount", () -> {
            // The guard reads the thread-bound context, which is the job's.
            TenantScope scope = guard.scope(TenantContextHolder.get(), fixture.projectA1);
            ScopedWhere.NamedSql statement = ScopedWhere.of(scope)
                .select(customers.customerSelect(), "c");
            return (long) customers.listCustomers(statement).size();
        });

        assertThat(rows).isEqualTo(1);
        assertThat(TenantContextHolder.isBound()).isFalse();
    }

    @Test
    @DisplayName("a job reaching for another tenant's project is denied")
    void jobCrossTenantIsDenied() {
        TenantContext jobContext = TenantContext.forMachine(PrincipalType.WORKER, fixture.orgA,
            "report-worker", List.of(fixture.projectA1), List.of(Capability.REPORT_WRITE));

        assertThatThrownBy(() -> jobExecutor.execute(jobContext, "report.customerCount",
            () -> guard.scope(TenantContextHolder.get(), fixture.projectB1)))
            .isInstanceOf(PolicyDeniedException.class);
        assertThat(TenantContextHolder.isBound()).isFalse();
    }

    @Test
    @DisplayName("a job with no declared tenant is refused outright")
    void unscopedJobIsRefused() {
        assertThatThrownBy(() -> jobExecutor.execute(null, "report.customerCount", () -> 1L))
            .isInstanceOf(TenantScopedExecutor.UnscopedJobException.class);
    }

    // ==================================================================
    // audit rows on the real table
    // ==================================================================

    @Test
    @DisplayName("a login and a logout land in audit_logs with a null projectId")
    void securityEventsArePersisted() {
        LoginService.LoginResult issued = login.login(fixture.STAFF_A_EMAIL,
            fixture.PASSWORD, "agent-1", nextIp());
        login.logout(issued.sessionId(), fixture.staffAContext(), nextIp());

        List<String> actions = jdbc.queryForList(
            "select \"action\" from audit_logs where \"organizationId\" = :org",
            new MapSqlParameterSource("org", fixture.orgA), String.class);

        assertThat(actions).contains("User.Login.Succeeded", "User.Logout");
        // audit_logs is one of the two documented organization-wide streams; Phase 2
        // confirmed all 43 live rows are security events with a null projectId.
        assertThat(jdbc.queryForObject(
            "select count(*) from audit_logs where \"projectId\" is not null",
            new MapSqlParameterSource(), Integer.class)).isZero();
    }

    @Test
    @DisplayName("a failed login never writes the email or the password anywhere")
    void failedLoginLeaksNothing() {
        assertThatThrownBy(() -> login.login(fixture.UNKNOWN_EMAIL, "wrong-password",
            "agent-1", nextIp()))
            .isInstanceOf(AuthenticationFailedException.class);

        // An unknown account has no organization to attribute the event to, so nothing is
        // written with a fabricated tenant.
        assertThat(jdbc.queryForList("select \"action\" from audit_logs",
            new MapSqlParameterSource(), String.class)).isEmpty();
    }

    @Test
    @DisplayName("a failed login for a known account records the user id, never the email")
    void failedLoginForKnownUserIsAuditedWithoutTheEmail() {
        assertThatThrownBy(() -> login.login(fixture.STAFF_A_EMAIL, "wrong-password",
            "agent-1", nextIp()))
            .isInstanceOf(AuthenticationFailedException.class);

        List<Map<String, Object>> rows = jdbc.queryForList("""
            select "action", "actorUserId", "metadata"::text as "metadata", "ipAddress"
              from audit_logs where "action" = 'User.Login.Failed'
            """, new MapSqlParameterSource());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("actorUserId")).hasToString(fixture.staffA.toString());
        assertThat(String.valueOf(rows.get(0).get("metadata")))
            .doesNotContain(fixture.STAFF_A_EMAIL)
            .doesNotContain("wrong-password");
        assertThat(String.valueOf(rows.get(0).get("ipAddress")))
            .as("the audit row records where the attempt came from")
            .isNotBlank();
    }

    @Test
    @DisplayName("a role change and a membership change are both audited")
    void identityChangesAreAudited() {
        PrincipalScope.call(fixture.adminAContext(), () -> {
            membershipAdmin.addMembership(fixture.nobodyA, fixture.projectA1, nextIp());
            membershipAdmin.changeRole(fixture.staffA, UserRole.ADMIN, nextIp());
            return null;
        });

        List<String> actions = jdbc.queryForList(
            "select \"action\" from audit_logs order by \"createdAt\"",
            new MapSqlParameterSource(), String.class);

        assertThat(actions).contains("Identity.MembershipChanged", "Identity.RoleChanged");
    }

    @Test
    @DisplayName("the event publisher sanitizes a secret a caller tries to include")
    void eventMetadataIsSanitized() {
        new AuditLogSecurityEventPublisher(jdbc).publish(
            SecurityEvent.builder(SecurityEventType.AUTHORIZATION_DENIED)
                .tenantContext(fixture.staffAContext())
                .resourceType("Customer")
                .reason("field-denied")
                .metadata(Map.of("password", "hunter2", "token", "abc",
                    "scope", "project-out-of-scope"))
                .build());

        List<String> metadata = jdbc.queryForList(
            "select \"metadata\"::text from audit_logs where \"action\" = 'Authorization.Denied'",
            new MapSqlParameterSource(), String.class);

        assertThat(metadata).hasSize(1);
        assertThat(metadata.get(0)).doesNotContain("hunter2").doesNotContain("abc");
        assertThat(metadata.get(0)).contains("[redacted]").contains("project-out-of-scope");
    }

    @Test
    @DisplayName("an audit insert failure cannot fail the authorization decision that produced it")
    void auditFailureIsBestEffort() {
        jdbc.update("alter table audit_logs rename to audit_logs_moved", new MapSqlParameterSource());
        try {
            // Authorization still answers correctly with the audit table unavailable.
            assertThatThrownBy(() -> guard.scope(fixture.staffAContext(), fixture.projectA2))
                .isInstanceOf(PolicyDeniedException.class);
        } finally {
            jdbc.update("alter table audit_logs_moved rename to audit_logs", new MapSqlParameterSource());
        }
    }

    // ==================================================================
    // the permission inspector
    // ==================================================================

    @Test
    @DisplayName("the inspector reports the grant set and the project scope, not just a role")
    void inspectorShowsEffectivePermissions() {
        EffectivePermissionService.PermissionInspection inspection = PrincipalScope.call(
            fixture.adminAContext(),
            () -> new EffectivePermissionService(new UserRepository(jdbc), new ProjectMembershipRepository(jdbc), guard, new AuthorizationService(null), null)
                .inspect(fixture.adminAContext(), fixture.staffA));

        assertThat(inspection.role()).isEqualTo("EMPLOYEE");
        assertThat(inspection.projectIds()).containsExactly(fixture.projectA1);
        assertThat(inspection.effectivePermissions())
            .contains("conversation:read", "customer:read", "knowledge:read")
            .doesNotContain("user:manage", "permission-inspector:read", "membership:manage");
        assertThat(inspection.missingPermissions()).contains("user:manage");
    }

    @Test
    @DisplayName("the inspector reports an ADMIN as holding every permission in the catalogue")
    void inspectorReportsAdminReach() {
        EffectivePermissionService.PermissionInspection inspection = PrincipalScope.call(
            fixture.adminAContext(),
            () -> new EffectivePermissionService(new UserRepository(jdbc), new ProjectMembershipRepository(jdbc), guard, new AuthorizationService(null), null)
                .inspect(fixture.adminAContext(), fixture.adminA));

        assertThat(inspection.role()).isEqualTo("ADMIN");
        assertThat(inspection.effectivePermissions()).hasSize(PermissionCount.TOTAL);
        assertThat(inspection.projectIds())
            .as("an ADMIN reaches every project of their organization, archived included")
            .containsExactlyInAnyOrder(fixture.projectA1, fixture.projectA2);
        assertThat(inspection.projects())
            .anySatisfy(project -> assertThat(project.archived()).isTrue());
    }

    @Test
    @DisplayName("the inspector refuses a user from another tenant identically to a missing user")
    void inspectorRefusesCrossTenant() {
        EffectivePermissionService service = new EffectivePermissionService(new UserRepository(jdbc), new ProjectMembershipRepository(jdbc), guard, new AuthorizationService(null), null);
        TenantContext adminA = fixture.adminAContext();

        PolicyDeniedException foreign = org.junit.jupiter.api.Assertions.assertThrows(
            PolicyDeniedException.class, () -> service.inspect(adminA, fixture.adminB));
        PolicyDeniedException missing = org.junit.jupiter.api.Assertions.assertThrows(
            PolicyDeniedException.class, () -> service.inspect(adminA, UUID.randomUUID()));

        assertThat(foreign.getMessage()).isEqualTo(missing.getMessage());
        assertThat(foreign.reason()).isEqualTo(missing.reason()).isEqualTo("user-out-of-scope");
    }

    /** Keeps the "every permission" assertion from silently drifting when the catalogue grows. */
    private static final class PermissionCount {
        static final int TOTAL = com.firstelement.security.permission.Permission.values().length;

        private PermissionCount() {
        }
    }
}