package com.firstelement.crm.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.security.TenantContext;
import com.firstelement.security.UserRole;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The tenant/project predicate, with no database.
 *
 * <p>These are the assertions that FR-1.4 ("a user with no membership must receive no tenant
 * data") rests on. If {@link CrmQueryScope} ever emitted an organization-only predicate for the
 * unpinned case, this test is what would catch it.
 */
class CrmQueryScopeTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID OTHER_ORG = UUID.randomUUID();
    private static final UUID PROJECT_A = UUID.randomUUID();
    private static final UUID PROJECT_B = UUID.randomUUID();

    private static TenantContext employee(UUID... projects) {
        return TenantContext.forUser(UUID.randomUUID(), UUID.randomUUID(), ORG, UserRole.EMPLOYEE,
            List.of(projects));
    }

    @Test
    @DisplayName("a pinned project emits organization AND project")
    void pinnedProjectNarrowsBothColumns() {
        CrmQueryScope scope = CrmQueryScope.accessibleProjects(employee(PROJECT_A, PROJECT_B));
        String predicate = scope.predicate("c");

        assertThat(predicate)
            .isEqualTo("c.\"organizationId\" = :__fe_crm_organization_id"
                + " and c.\"projectId\" in (:__fe_crm_project_ids)");

        var parameters = scope.bind(java.util.Map.of());
        assertThat(parameters.getValue("__fe_crm_organization_id")).isEqualTo(ORG);
        assertThat((java.util.List<UUID>) parameters.getValue("__fe_crm_project_ids"))
            .containsExactlyInAnyOrder(PROJECT_A, PROJECT_B);
    }

    @Test
    @DisplayName("a principal with no memberships receives no project ids at all")
    void noMembershipMeansNoProjects() {
        // The single most important assertion in this class: an employee whose memberships were
        // all revoked must produce a predicate that matches nothing.
        CrmQueryScope scope = CrmQueryScope.accessibleProjects(employee());

        var parameters = scope.bind(java.util.Map.of());
        assertThat((java.util.List<?>) parameters.getValue("__fe_crm_project_ids")).isEmpty();
        assertThat(scope.isProjectScoped()).isFalse();
    }

    @Test
    @DisplayName("an empty project set is `1 = 0`, never the PostgreSQL syntax error `in ()`")
    void emptyProjectSetIsValidSql() {
        // This one was found by the container test, not by inspection: `in ()` is a parse error,
        // so the no-membership read returned a 500 on exactly the request that most needs to be a
        // quiet, empty, authorized answer.
        CrmQueryScope scope = CrmQueryScope.accessibleProjects(employee());

        assertThat(scope.predicate("c"))
            .isEqualTo("c.\"organizationId\" = :__fe_crm_organization_id and 1 = 0");
        assertThat(scope.predicate("c")).doesNotContain("in ()");
    }

    @Test
    @DisplayName("a requested project is validated by the guard before the scope is built")
    void requestedProjectOutsideScopeIsDenied() {
        // The guard is what refuses; the scope itself never sees the id.
        var context = employee(PROJECT_A);
        var guard = new com.firstelement.security.query.TenantQueryGuard(event -> { });

        assertThatThrownBy(() -> CrmQueryScope.forProject(guard, context, PROJECT_B))
            .isInstanceOf(com.firstelement.security.error.PolicyDeniedException.class);
    }

    @Test
    @DisplayName("a null TenantContext is a hard failure, never an unscoped query")
    void nullContextIsRefused() {
        assertThatThrownBy(() -> CrmQueryScope.accessibleProjects(null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("unscoped");
    }

    @Test
    @DisplayName("a caller cannot shadow the tenant filter with a parameter of the same name")
    void reservedParametersCannotBeSupplied() {
        CrmQueryScope scope = CrmQueryScope.accessibleProjects(employee(PROJECT_A));

        assertThatThrownBy(() -> scope.bind(java.util.Map.of(
            "__fe_crm_organization_id", OTHER_ORG)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("reserved");
    }

    @Test
    @DisplayName("an alias must be a plain identifier")
    void aliasIsValidated() {
        CrmQueryScope scope = CrmQueryScope.accessibleProjects(employee(PROJECT_A));

        assertThatThrownBy(() -> scope.predicate("c; drop table users --"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("plain identifier");
    }

    @Test
    @DisplayName("multi-alias statements get a copy of the predicate per alias")
    void multiAliasPredicate() {
        CrmQueryScope scope = CrmQueryScope.accessibleProjects(employee(PROJECT_A));

        assertThat(scope.predicate("c", "cu", "e"))
            .contains("c.\"organizationId\"")
            .contains("cu.\"organizationId\"")
            .contains("e.\"organizationId\"");
    }

    @Test
    @DisplayName("a pinned scope binds a project parameter and refuses an insert without one")
    void insertRequiresTheProjectParameter() {
        var context = employee(PROJECT_A);
        var guard = new com.firstelement.security.query.TenantQueryGuard(event -> { });
        CrmQueryScope scope = CrmQueryScope.forProject(guard, context, PROJECT_A);

        assertThat(scope.isProjectScoped()).isTrue();
        assertThatThrownBy(() -> scope.insert("insert into notes (\"id\") values (:id)"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(scope.insert("insert into notes (\"organizationId\", \"projectId\") "
            + "values (:__fe_crm_organization_id, :__fe_crm_project_id)").sql())
            .contains("__fe_crm_project_id");
    }

    @Test
    @DisplayName("an ordered statement carries order by and the limit")
    void orderedStatement() {
        CrmQueryScope scope = CrmQueryScope.accessibleProjects(employee(PROJECT_A));
        CrmQueryScope.Statement statement = scope
            .select("select c.\"id\" from conversations c", "c")
            .orderedBy("c.\"id\" desc", 31);

        assertThat(statement.sql()).contains("order by c.\"id\" desc").contains("limit 31");
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("a machine principal's scope is its granted projects and nothing else")
    void machinePrincipalScope() {
        TenantContext worker = TenantContext.forMachine(
            com.firstelement.security.PrincipalType.WORKER, ORG, "worker-1",
            Set.of(PROJECT_B), Set.of(com.firstelement.security.Capability.WEBHOOK_INGEST));

        CrmQueryScope scope = CrmQueryScope.accessibleProjects(worker);
        var parameters = scope.bind(java.util.Map.of());

        assertThat((List<UUID>) parameters.getValue("__fe_crm_project_ids"))
            .containsExactly(PROJECT_B);
    }
}