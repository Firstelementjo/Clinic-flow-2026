package com.firstelement.exports;

import com.firstelement.exports.catalog.ExportRedactionPolicy;
import com.firstelement.exports.catalog.ReportField;
import com.firstelement.exports.catalog.ReportFieldRegistry;
import com.firstelement.exports.catalog.ReportSubject;
import com.firstelement.exports.plan.ExportQueryPlan;
import com.firstelement.exports.redaction.ResolvedExportColumns;
import com.firstelement.security.permission.Permission;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Shared helpers for the planner and redaction suites.
 *
 * <h2>Why these live in one place</h2>
 *
 * <p>Two suites assert on the same shapes — a resolved column set and the statement it produces — and a
 * helper duplicated into both would drift. Worse, a suite that builds its own tenant scope could build it
 * <em>differently</em>, and then a planner bug would look like a fixture bug. So there is exactly one
 * construction of "an admin's view of the customers subject" and one of "an employee's view", and both are
 * obviously correct by inspection.
 *
 * <p>Every helper here is deliberately free of a database, a Spring context and a container, because both
 * suites that use it are pure-JVM: {@code ExportQueryPlannerTest} and
 * {@code ExportRedactionResolverTest}. Neither needs a {@code TenantScope} from a guard — they construct
 * one directly, which is legitimate here precisely because the property under test is <em>what the planner
 * does with a scope it was handed</em>.
 */
final class PlanShapeAssertions {

    private PlanShapeAssertions() {
    }

    // ------------------------------------------------------------------
    // scopes
    // ------------------------------------------------------------------

    /** A scope for a caller who holds every permission. */
    static com.firstelement.security.query.TenantScope adminScope() {
        return new com.firstelement.security.query.TenantScope(
            java.util.UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001"),
            java.util.UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000a1"));
    }

    /** A scope for tenant B's project, used to prove a foreign scope reads nothing. */
    static com.firstelement.security.query.TenantScope foreignScope() {
        return new com.firstelement.security.query.TenantScope(
            java.util.UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002"),
            java.util.UUID.fromString("bbbbbbbb-0000-0000-0000-0000000000b1"));
    }

    // ------------------------------------------------------------------
    // permission sets
    // ------------------------------------------------------------------

    /**
     * The permissions an {@code ADMIN} holds, computed through the real grant table.
     *
     * <p>Not a hand-written set: {@code RolePermissions} is the single grant table and this suite's claim is
     * that the resolver agrees with it. Hand-writing the set would make the test pass even if the grant
     * table changed.
     */
    static Set<Permission> adminPermissions() {
        return com.firstelement.security.permission.RolePermissions
            .forPrincipalType(com.firstelement.security.PrincipalType.USER,
                com.firstelement.security.UserRole.ADMIN, Set.of());
    }

    /**
     * The permissions an {@code EMPLOYEE} holds, through the same table.
     *
     * <p>The point of most redaction assertions: an employee holds {@code CUSTOMER_READ} and
     * {@code CONVERSATION_READ} but NOT {@code ANALYTICS_READ_ORGANIZATION}, so an employee's usage export
     * carries no money columns.
     */
    static Set<Permission> employeePermissions() {
        return com.firstelement.security.permission.RolePermissions
            .forPrincipalType(com.firstelement.security.PrincipalType.USER,
                com.firstelement.security.UserRole.EMPLOYEE, Set.of());
    }

    // ------------------------------------------------------------------
    // column sets
    // ------------------------------------------------------------------

    /** Every declared customer field, resolved. */
    static List<ReportField> allCustomerFields() {
        return ReportField.ofSubject(ReportSubject.CUSTOMERS);
    }

    /** Every declared conversation field, resolved. */
    static List<ReportField> allConversationFields() {
        return ReportField.ofSubject(ReportSubject.CONVERSATIONS);
    }

    /**
     * The conversation fields an employee may see.
     *
     * <p>Used wherever a test exercises a {@code conversation.*} filter or sort, because a filter for one
     * subject on a version of another is itself a refusal — the planner enforces that, and a fixture that
     * got it wrong would test the refusal instead of the filter.
     */
    static ResolvedExportColumns employeeConversationColumns() {
        return ExportRedactionResolverFixture.resolve(allConversationFields(),
            ExportRedactionPolicy.NONE, employeePermissions());
    }

    /** Every conversation field, for an admin. */
    static ResolvedExportColumns adminConversationColumns() {
        return ExportRedactionResolverFixture.resolve(allConversationFields(),
            ExportRedactionPolicy.NONE, adminPermissions());
    }

    /** A deliberately narrow customer column set, for the sort-withheld-column assertion. */
    static ResolvedExportColumns customerNameAndCountOnly() {
        return ExportRedactionResolverFixture.resolve(
            List.of(ReportField.CUSTOMER_DISPLAY_NAME, ReportField.CUSTOMER_COUNT),
            ExportRedactionPolicy.NONE, adminPermissions());
    }

    /** The customer fields an employee may see: everything except the contact-detail policy withholds. */
    static ResolvedExportColumns employeeCustomerColumns() {
        return ExportRedactionResolverFixture.resolve(allCustomerFields(), ExportRedactionPolicy.NONE,
            employeePermissions());
    }

    /**
     * The customer fields an admin may see.
     *
     * <p>An admin holds every permission, so under the {@code NONE} policy nothing is withheld — which
     * makes this the right baseline for the tests that need a full column set and are not about redaction.
     */
    static ResolvedExportColumns adminCustomerColumns() {
        return ExportRedactionResolverFixture.resolve(allCustomerFields(), ExportRedactionPolicy.NONE,
            adminPermissions());
    }

    /** Every declared finance field, resolved. */
    static List<ReportField> allUsageFields() {
        return ReportField.ofSubject(ReportSubject.USAGE_LEDGER);
    }

    /** An admin's view of the usage ledger: every field, nothing withheld. */
    static ResolvedExportColumns adminUsageColumns() {
        return ExportRedactionResolverFixture.resolve(allUsageFields(), ExportRedactionPolicy.NONE,
            adminPermissions());
    }

    /**
     * An employee's view of the usage ledger: nothing at all.
     *
     * <p>An employee holds {@code ANALYTICS_READ_PROJECT}; every {@code usage.*} field requires
     * {@code ANALYTICS_READ_ORGANIZATION}, which is the same gate {@code FinanceUsageReadService} applies
     * to {@code /usage/daily}. Declaring a lesser finance field at the project level would have made this
     * module a way around that gate rather than a projection of it, so the fixture resolves to an empty
     * emission — which is what FR-7.7 requires: no money columns, rather than empty money columns.
     */
    static ResolvedExportColumns employeeUsageColumns() {
        return ExportRedactionResolverFixture.resolve(allUsageFields(), ExportRedactionPolicy.NONE,
            employeePermissions());
    }

    /** The money fields, for the "currency must be beside money" check. */
    static List<ReportField> moneyFields() {
        return allUsageFields().stream().filter(ReportField::isMoney).toList();
    }

    // ------------------------------------------------------------------
    // plan shape
    // ------------------------------------------------------------------

    /**
     * Whether the statement carries a tenant predicate on the subject's own alias.
     *
     * <p>The alias is emitted unquoted — {@link com.firstelement.security.query.ScopedWhere} validates it
     * against {@code [A-Za-z_][A-Za-z0-9_]*} and then writes it bare — while the column is quoted
     * camelCase per ADR-0002. So the expected fragment is built from the alias and {@code ScopedWhere}'s
     * own spelling rather than hand-written, which is what keeps this assertion honest if the security
     * module ever changes how it qualifies a column.
     */
    static boolean hasTenantPredicate(ExportQueryPlan plan) {
        return plan.sql().contains(tenantFragment(plan.subject().alias(), "organizationId"));
    }

    /** Whether the statement carries a project predicate, which a project-scoped export must. */
    static boolean hasProjectPredicate(ExportQueryPlan plan) {
        return plan.sql().contains(tenantFragment(plan.subject().alias(), "projectId"));
    }

    /**
     * The exact fragment {@code ScopedWhere} writes for one tenant column, in its positional form.
     *
     * <p>Not a hand-written literal: {@code ScopedWhere.positionalPredicate} is the single place that
     * decides the spelling, so deriving the expectation from it is what keeps the assertion about
     * <em>policy presence</em> rather than about a formatting detail that could legitimately change.
     */
    static String tenantFragment(String alias, String column) {
        String predicate = com.firstelement.security.query.ScopedWhere
            .of(new com.firstelement.security.query.TenantScope(
                java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
                java.util.UUID.fromString("00000000-0000-0000-0000-000000000002")))
            .positionalPredicate(alias);
        return java.util.Arrays.stream(predicate.split(" and "))
            .filter(part -> part.contains("\"" + column + "\""))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "ScopedWhere wrote no predicate on \"" + column + "\": " + predicate));
    }

    /**
     * The character offset at which the tenant predicate begins.
     *
     * <p>Used to prove <b>policy before aggregation</b>: the tenant predicate must appear before the
     * {@code group by}, because a predicate that lands after an aggregate has already included the rows it
     * was meant to exclude.
     */
    static int offsetOf(ExportQueryPlan plan, String fragment) {
        int index = plan.sql().indexOf(fragment);
        if (index < 0) {
            throw new AssertionError("The statement does not contain '" + fragment + "': " + plan.sql());
        }
        return index;
    }

    /** The permissions the finance fields require, for the redaction assertions. */
    static Set<Permission> financePermissions() {
        Set<Permission> required = EnumSet.noneOf(Permission.class);
        for (ReportField field : allUsageFields()) {
            required.add(field.requiredPermission());
        }
        return required;
    }

    /** A namespace so the fixture helper reads as a fixture rather than as a static import of the API. */
    static final class ExportRedactionResolverFixture {

        private ExportRedactionResolverFixture() {
        }

        static ResolvedExportColumns resolve(List<ReportField> declared, ExportRedactionPolicy policy,
                                             Set<Permission> permissions) {
            return com.firstelement.exports.redaction.ExportRedactionResolver.resolve(declared, policy,
                List.of(), permissions);
        }
    }
}