package com.firstelement.security.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.security.Capability;
import com.firstelement.security.PrincipalType;
import com.firstelement.security.TenantContext;
import com.firstelement.security.UserRole;
import com.firstelement.security.error.PolicyDeniedException;
import com.firstelement.security.event.SecurityEvent;
import com.firstelement.security.event.SecurityEventType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The "a client project/tenant id is a request, never authorization" rule, and the SQL it
 * produces.
 */
class TenantQueryGuardTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID OTHER_ORG = UUID.randomUUID();
    private static final UUID PROJECT_A = UUID.randomUUID();
    private static final UUID PROJECT_B = UUID.randomUUID();
    private static final UUID FOREIGN_PROJECT = UUID.randomUUID();

    private final List<SecurityEvent> events = new ArrayList<>();
    private TenantQueryGuard guard;

    @BeforeEach
    void setUp() {
        events.clear();
        guard = new TenantQueryGuard(event -> events.add(event));
    }

    private TenantContext employeeOf(UUID... projects) {
        return TenantContext.forUser(UUID.randomUUID(), UUID.randomUUID(), ORG,
            UserRole.EMPLOYEE, List.of(projects));
    }

    @Test
    @DisplayName("a requested project inside the server-resolved scope becomes the query scope")
    void requestedProjectIsValidatedNotTrusted() {
        TenantContext caller = employeeOf(PROJECT_A);
        TenantScope scope = guard.scope(caller, PROJECT_A);

        assertThat(scope.organizationId()).isEqualTo(ORG);
        assertThat(scope.projectId()).isEqualTo(PROJECT_A);
        assertThat(scope.isProjectScoped()).isTrue();
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("a requested project outside the scope is denied before any SQL exists")
    void outOfScopeProjectIsDeniedBeforeAQueryIsBuilt() {
        TenantContext caller = employeeOf(PROJECT_A);

        assertThatThrownBy(() -> guard.scope(caller, PROJECT_B))
            .isInstanceOf(PolicyDeniedException.class)
            .hasMessage("Access denied");
        assertThatThrownBy(() -> guard.scope(caller, FOREIGN_PROJECT))
            .isInstanceOf(PolicyDeniedException.class);
        assertThatThrownBy(() -> guard.scope(caller, OTHER_ORG))
            .isInstanceOf(PolicyDeniedException.class);

        assertThat(events).hasSize(3);
        assertThat(events).allSatisfy(event ->
            assertThat(event.type()).isEqualTo(SecurityEventType.AUTHORIZATION_DENIED));
        assertThat(events).allSatisfy(event ->
            assertThat(event.metadata()).containsEntry("guard", "tenant-query-guard"));
    }

    @Test
    @DisplayName("no project requested means the whole tenant scope, not the whole table")
    void nullProjectYieldsTenantScope() {
        TenantContext caller = employeeOf(PROJECT_A);
        TenantScope scope = guard.scope(caller, null);

        assertThat(scope.organizationId()).isEqualTo(ORG);
        assertThat(scope.projectId()).isNull();
        assertThat(scope.isProjectScoped()).isFalse();
    }

    @Test
    @DisplayName("a claimed tenant from an integration is checked, never honoured")
    void claimedTenantIsValidated() {
        TenantContext caller = TenantContext.forMachine(PrincipalType.CONNECTOR, ORG,
            "meta-webhook", List.of(PROJECT_A), List.of(Capability.WEBHOOK_INGEST));

        assertThat(guard.claimedTenantScope(caller, ORG).organizationId()).isEqualTo(ORG);
        assertThat(guard.claimedTenantScope(caller, null).organizationId()).isEqualTo(ORG);
        assertThatThrownBy(() -> guard.claimedTenantScope(caller, OTHER_ORG))
            .isInstanceOf(PolicyDeniedException.class);
    }

    @Test
    @DisplayName("a row read outside the scope is denied, and no-membership means no row")
    void rowScopeAssertion() {
        TenantContext caller = employeeOf(PROJECT_A);
        TenantContext nobody = employeeOf();

        assertThat(guard.assertRowScope(caller, ORG, PROJECT_A).projectId()).isEqualTo(PROJECT_A);
        // Organization-wide rows (sessions, audit_logs, users) carry no project.
        assertThat(guard.assertRowScope(caller, ORG, null).projectId()).isNull();

        assertThatThrownBy(() -> guard.assertRowScope(caller, OTHER_ORG, PROJECT_A))
            .isInstanceOf(PolicyDeniedException.class);
        assertThatThrownBy(() -> guard.assertRowScope(nobody, ORG, PROJECT_A))
            .isInstanceOf(PolicyDeniedException.class);
    }

    @Test
    @DisplayName("the guard refuses to work without a context instead of defaulting to a full read")
    void nullContextIsAFailureNotAFallback() {
        assertThatThrownBy(() -> guard.scope(null, PROJECT_A))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("refusing to build a tenant-scoped query unscoped");
        assertThatThrownBy(() -> guard.tenantScope(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> guard.claimedTenantScope(null, ORG))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> guard.assertRowScope(null, ORG, PROJECT_A))
            .isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------------
    // ScopedWhere
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a tenant-scoped statement binds the organization and, when asked, the project")
    void statementAlwaysCarriesTheTenantFilter() {
        TenantContext caller = employeeOf(PROJECT_A);
        TenantScope scope = guard.scope(caller, null);

        ScopedWhere.NamedSql statement = ScopedWhere.of(scope)
            .select("select c.\"id\" from customers c", "c");

        assertThat(statement.sql()).isEqualTo(
            "select c.\"id\" from customers c where c.\"organizationId\" = :__fe_tenant_organization_id");
        assertThat(statement.parameters().getValue("__fe_tenant_organization_id")).isEqualTo(ORG);
        assertThat(statement.parameters().hasValue("__fe_tenant_project_id")).isFalse();
    }

    @Test
    @DisplayName("a project-scoped statement adds the project predicate ahead of the caller's")
    void projectScopeAddsTheSecondPredicate() {
        TenantScope scope = guard.scope(employeeOf(PROJECT_A), PROJECT_A);

        ScopedWhere.NamedSql statement = ScopedWhere.of(scope)
            .select("select c.\"id\" from customers c", "c", "c.\"displayName\" like :name",
                Map.of("name", "%acme%"));

        assertThat(statement.sql()).isEqualTo(
            "select c.\"id\" from customers c"
                + " where c.\"organizationId\" = :__fe_tenant_organization_id"
                + " and c.\"projectId\" = :__fe_tenant_project_id"
                + " and (c.\"displayName\" like :name)");
        assertThat(statement.parameters().getValue("__fe_tenant_project_id")).isEqualTo(PROJECT_A);
        assertThat(statement.parameters().getValue("name")).isEqualTo("%acme%");
    }

    @Test
    @DisplayName("the caller cannot shadow the reserved tenant parameter names")
    void reservedParameterNamesCannotBeShadowed() {
        TenantScope scope = guard.scope(employeeOf(PROJECT_A), PROJECT_A);

        assertThatThrownBy(() -> ScopedWhere.of(scope).bindNamed(
            Map.of("__fe_tenant_organization_id", OTHER_ORG)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("reserved for the tenant filter");
    }

    @Test
    @DisplayName("the positional form binds tenant values first, then caller values")
    void positionalFormKeepsTheOrder() {
        TenantScope scope = guard.scope(employeeOf(PROJECT_A), PROJECT_A);

        ScopedWhere.ScopedSql statement = ScopedWhere.of(scope)
            .positional("select c.\"id\" from customers c", "c",
                "c.\"displayName\" = ?", "Acme");

        assertThat(statement.sql()).isEqualTo(
            "select c.\"id\" from customers c"
                + " where c.\"organizationId\" = ? and c.\"projectId\" = ?"
                + " and (c.\"displayName\" = ?)");
        assertThat(statement.parameters()).containsExactly(ORG, PROJECT_A, "Acme");
    }

    @Test
    @DisplayName("an unscoped statement is not constructible, and a SQL alias cannot smuggle SQL")
    void unscopedStatementsAndAliasInjectionAreRefused() {
        assertThatThrownBy(() -> ScopedWhere.of(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unscoped statement is not constructible");

        TenantScope scope = guard.scope(employeeOf(PROJECT_A), PROJECT_A);
        assertThatThrownBy(() -> ScopedWhere.of(scope).select("select 1", "c; drop table users"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("plain identifier");
    }

    @Test
    @DisplayName("TenantScope refuses to treat an organization id as a project id")
    void scopesAreNotInterchangeable() {
        assertThatThrownBy(() -> new TenantScope(ORG, ORG))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not interchangeable");
        assertThatThrownBy(() -> new TenantScope(null, PROJECT_A))
            .isInstanceOf(IllegalArgumentException.class);
    }
}