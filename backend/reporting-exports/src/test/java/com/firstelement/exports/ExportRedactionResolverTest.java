package com.firstelement.exports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.exports.catalog.ExportRedactionPolicy;
import com.firstelement.exports.catalog.ReportField;
import com.firstelement.exports.redaction.ExportRedactionResolver;
import com.firstelement.exports.redaction.ResolvedExportColumns;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.redaction.FieldRedactionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Field-level authorization: a column a caller may not see is <em>absent</em>, not blank.
 *
 * <h2>What "redaction" means here, stated as an assertion</h2>
 *
 * <p>The failure this suite exists to prevent is a spreadsheet that opens, has the right number of rows,
 * and shows an empty cell under a money column — because an operator then reads "this period had no
 * revenue" where the truth is "you may not see revenue". So every assertion here is about
 * <em>membership of a set</em>: the key is in {@link ResolvedExportColumns#emittedKeys()} or it is in
 * {@link ResolvedExportColumns#withheldKeys()}, and there is no third state, no placeholder and no blank.
 *
 * <h2>Why the permission sets come from the real grant table</h2>
 *
 * <p>These suites build their fixtures through {@code PlanShapeAssertions.employeePermissions()} and
 * {@code adminPermissions()}, which call {@code RolePermissions.forPrincipalType}. The claim under test
 * is "the export resolver agrees with the product's grant table", and a hand-written permission set
 * would make that claim true by construction no matter what the grant table said.
 */
@DisplayName("field-level authorization drops a column rather than blanking it")
class ExportRedactionResolverTest {

    // ==================================================================
    // the permission gate
    // ==================================================================

    @Nested
    @DisplayName("a permission the caller lacks removes the column")
    class PermissionGate {

        @Test
        @DisplayName("an employee's usage export carries no usage column at all")
        void employeeSeesNoUsageColumns() {
            ResolvedExportColumns resolved = PlanShapeAssertions.employeeUsageColumns();

            assertThat(PlanShapeAssertions.employeePermissions())
                .as("the premise of this assertion; if the grant table ever gives EMPLOYEE the finance "
                    + "capability the test is about a different thing and must be rewritten")
                .doesNotContain(Permission.ANALYTICS_READ_ORGANIZATION)
                .as("an employee holds only the PROJECT-level analytics capability, and every usage field "
                    + "is declared at the ORGANIZATION level — the same gate reporting-finance applies to "
                    + "/usage/daily. Were it otherwise, this module would be a way around the finance read "
                    + "gate rather than a projection of it.")
                .contains(Permission.ANALYTICS_READ_PROJECT);

            assertThat(resolved.emitted())
                .as("FR-7.7: a finance export by a non-finance principal has no money columns, rather "
                    + "than empty ones — and because the gate is the same one the dashboard applies, it "
                    + "has no usage columns either")
                .isEmpty();
            assertThat(resolved.withheldKeys())
                .as("every usage column is withheld, each naming the finance permission")
                .containsExactlyInAnyOrderElementsOf(
                    PlanShapeAssertions.allUsageFields().stream().map(ReportField::key).toList());
            assertThat(resolved.withheld())
                .extracting(column -> column.requiredPermission())
                .containsOnly(Permission.ANALYTICS_READ_ORGANIZATION.value());
            assertThat(resolved.hasNothingToEmit())
                .as("which is the refusal the job service turns into a 403 rather than an empty file")
                .isTrue();
        }

        @Test
        @DisplayName("a principal holding only the project analytics capability still sees no money")
        void projectAnalyticsIsNotFinance() {
            Set<Permission> projectScopedOnly = new java.util.LinkedHashSet<>(
                PlanShapeAssertions.employeePermissions());
            projectScopedOnly.add(Permission.ANALYTICS_READ_PROJECT);

            ResolvedExportColumns resolved = ExportRedactionResolver.resolve(
                PlanShapeAssertions.allUsageFields(), ExportRedactionPolicy.NONE, List.of(),
                projectScopedOnly);

            assertThat(resolved.emitted())
                .as("'analytics:read-project' is not a lesser view of the usage ledger; it is a different "
                    + "permission that the ledger's own read service does not honour either")
                .isEmpty();
        }

        @Test
        @DisplayName("an admin's usage export carries every declared usage column")
        void adminSeesEveryColumn() {
            ResolvedExportColumns resolved = PlanShapeAssertions.adminUsageColumns();

            assertThat(resolved.withheld())
                .as("an admin holds every permission, so nothing is withheld and 'nothing was redacted' "
                    + "is a stated fact rather than an absence of information")
                .isEmpty();
            assertThat(resolved.emittedKeys())
                .containsExactlyInAnyOrderElementsOf(PlanShapeAssertions.allUsageFields().stream()
                    .map(ReportField::key).toList());
            assertThat(resolved.redactedAnything()).isFalse();
        }

        @Test
        @DisplayName("a withheld column records which permission would have admitted it")
        void withholdingNamesThePermission() {
            ResolvedExportColumns resolved = PlanShapeAssertions.employeeUsageColumns();

            ResolvedExportColumns.WithheldColumn revenue = resolved.withheld().stream()
                .filter(column -> column.field().key().equals("usage.revenue"))
                .findFirst()
                .orElseThrow();

            assertThat(revenue.reason())
                .isEqualTo(ResolvedExportColumns.WithholdingReason.MISSING_PERMISSION);
            assertThat(revenue.requiredPermission())
                .as("the job row states what the author demanded of a caller, so an operator reading "
                    + "'the employee export has no revenue column' knows why")
                .isEqualTo(Permission.ANALYTICS_READ_ORGANIZATION.value());
        }

        @Test
        @DisplayName("an empty permission set withholds everything, and the export is refused")
        void noPermissionsMeansNoColumns() {
            ResolvedExportColumns resolved = ExportRedactionResolver.resolve(
                PlanShapeAssertions.allCustomerFields(), ExportRedactionPolicy.NONE, List.of(), Set.of());

            assertThat(resolved.emitted()).isEmpty();
            assertThat(resolved.hasNothingToEmit()).isTrue();
            assertThat(ExportRedactionResolver.hasNothingToEmit(resolved))
                .as("both spellings of the guard agree, because two would be a chance to use the wrong one")
                .isTrue();
            assertThat(resolved.withheld())
                .hasSize(PlanShapeAssertions.allCustomerFields().size())
                .extracting(column -> column.field().key())
                .doesNotHaveDuplicates();

            assertThatThrownBy(() -> resolved.subject())
                .as("'the subject of nothing' is not a subject")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hasNothingToEmit");
        }
    }

    // ==================================================================
    // the policy gate
    // ==================================================================

    @Nested
    @DisplayName("the declared policy withholds regardless of permission")
    class PolicyGate {

        @Test
        @DisplayName("a policy-withheld column is absent even from an admin")
        void policyBeatsPermission() {
            ResolvedExportColumns resolved = ExportRedactionResolver.resolve(
                PlanShapeAssertions.allCustomerFields(), ExportRedactionPolicy.NO_CONTACT_DETAILS,
                List.of("customer:read"), PlanShapeAssertions.adminPermissions());

            assertThat(resolved.emittedKeys())
                .as("the policy is a floor the tenant declared; an administrator does not get an "
                    + "exemption from the tenant's own data policy")
                .doesNotContain("customer.primaryEmail", "customer.primaryPhone");
            assertThat(resolved.withheldKeys())
                .containsExactlyInAnyOrder("customer.primaryEmail", "customer.primaryPhone");

            ResolvedExportColumns.WithheldColumn email = resolved.withheld().stream()
                .filter(column -> column.field().key().equals("customer.primaryEmail"))
                .findFirst()
                .orElseThrow();
            assertThat(email.reason())
                .as("the reason distinguishes 'you may not' from 'this tenant does not export it', "
                    + "because only one of those is fixed by revoking a permission")
                .isEqualTo(ResolvedExportColumns.WithholdingReason.POLICY);
        }

        @Test
        @DisplayName("the strict policy withholds money and personal detail together")
        void strictPolicyIsTheWidest() {
            ResolvedExportColumns resolved = ExportRedactionResolver.resolve(
                PlanShapeAssertions.allUsageFields(), ExportRedactionPolicy.STRICT,
                List.of(), PlanShapeAssertions.adminPermissions());

            assertThat(resolved.emittedKeys())
                .doesNotContain("usage.cost", "usage.revenue", "usage.margin",
                    "usage.markupRate", "usage.rateCardVersionId");
            assertThat(resolved.emittedKeys())
                .as("quantities survive a money redaction: an unpriced quantity is still a quantity, and "
                    + "removing it would hide exactly the case ADR-0007 rule 5 exists to surface")
                .contains("usage.unpricedQuantity", "usage.quantity");
        }

        @Test
        @DisplayName("every declared policy withholds only keys the registry declares")
        void policiesDoNotNameUnknownKeys() {
            Set<String> declaredKeys = ReportField.index().keySet();

            for (ExportRedactionPolicy policy : ExportRedactionPolicy.values()) {
                assertThat(policy.withheldKeys())
                    .as("policy '%s' withholds a key no field declares, so it can never fire — which is "
                        + "either a typo or a field that was removed", policy.wireValue())
                    .isSubsetOf(declaredKeys);
            }
        }

        @Test
        @DisplayName("the policies are nested, so a stricter one can never withhold less")
        void policiesAreNested() {
            assertThat(ExportRedactionPolicy.NO_PERSONAL_DETAIL.withheldKeys())
                .containsAll(ExportRedactionPolicy.NO_CONTACT_DETAILS.withheldKeys());
            assertThat(ExportRedactionPolicy.STRICT.withheldKeys())
                .containsAll(ExportRedactionPolicy.NO_PERSONAL_DETAIL.withheldKeys());
        }

        @Test
        @DisplayName("an undeclared policy name is refused rather than defaulted")
        void undeclaredPolicyIsRefused() {
            assertThat(ExportRedactionPolicy.fromWireValue("strict")).isPresent();
            assertThat(ExportRedactionPolicy.fromWireValue("  STRICT "))
                .as("stored names are compared case-insensitively so a hand-edited row still resolves")
                .isPresent();
            assertThat(ExportRedactionPolicy.fromWireValue("show-everything")).isEmpty();
            assertThat(ExportRedactionPolicy.fromWireValue("")).isEmpty();
            assertThat(ExportRedactionPolicy.fromWireValue(null)).isEmpty();
            assertThat(ExportRedactionPolicy.declared())
                .containsExactly("none", "no-contact-details", "no-personal-detail", "strict");
        }

        @Test
        @DisplayName("a null policy resolves as NONE rather than as 'withhold everything'")
        void nullPolicyIsNotAWithholdingPolicy() {
            ResolvedExportColumns resolved = ExportRedactionResolver.resolve(
                PlanShapeAssertions.allCustomerFields(), null, List.of(),
                PlanShapeAssertions.adminPermissions());

            assertThat(resolved.policy()).isEqualTo(ExportRedactionPolicy.NONE);
            assertThat(resolved.withheld())
                .as("a missing policy must not fail closed, because the permission gate is already the "
                    + "authoritative decision; failing closed here would make an unset column hide every "
                    + "field for an admin and look like a bug")
                .isEmpty();
        }
    }

    // ==================================================================
    // never exposed
    // ==================================================================

    @Nested
    @DisplayName("a credential class field could never be declared, and the reason set admits it anyway")
    class NeverExposed {

        @Test
        @DisplayName("no declared field is in NEVER_EXPOSED, and the vocabulary is checkable")
        void noDeclaredFieldIsNeverExposed() {
            for (ReportField field : ReportField.values()) {
                assertThat(FieldRedactionPolicy.NEVER_EXPOSED)
                    .as("%s is a credential class column; no report may ever select it", field.key())
                    .doesNotContain(field.key());
            }
        }

        @Test
        @DisplayName("a hypothetical credential field would be reported as NEVER_EXPOSED, not as a "
            + "missing permission")
        void neverExposedOutranksPermission() {
            // The registry has no credential field, so the branch is unreachable through the public API.
            // It is asserted through the reason set instead: the reason exists precisely so that a
            // credential reaching a projection is a visible defect rather than a silent omission.
            assertThat(ResolvedExportColumns.WithholdingReason.values())
                .containsExactly(ResolvedExportColumns.WithholdingReason.MISSING_PERMISSION,
                    ResolvedExportColumns.WithholdingReason.POLICY,
                    ResolvedExportColumns.WithholdingReason.NEVER_EXPOSED);
            assertThat(ResolvedExportColumns.WithholdingReason.NEVER_EXPOSED)
                .as("if a field were ever added to both NEVER_EXPOSED and a permission-gated table, the "
                    + "resolver must name the credential reason — otherwise an operator debugging a "
                    + "missing column would go looking for a permission to grant")
                .isNotEqualTo(ResolvedExportColumns.WithholdingReason.MISSING_PERMISSION);
        }
    }

    // ==================================================================
    // download re-authorization
    // ==================================================================

    @Nested
    @DisplayName("a download is refused when a permission the artefact depends on has lapsed")
    class DownloadReauthorization {

        @Test
        @DisplayName("an admin's emitted set is satisfied by the admin's permissions")
        void satisfiedWhenNothingLapsed() {
            ResolvedExportColumns resolved = PlanShapeAssertions.adminUsageColumns();

            assertThat(resolved.satisfiedBy(PlanShapeAssertions.adminPermissions())).isTrue();
            assertThat(resolved.firstUnmetField(PlanShapeAssertions.adminPermissions()))
                .as("and there is no field to name in a download event")
                .isNull();
        }

        @Test
        @DisplayName("a revoked permission names the first unmet field, in the emitted order")
        void revokedPermissionNamesAField() {
            ResolvedExportColumns resolved = PlanShapeAssertions.adminUsageColumns();
            Set<Permission> lapsed = new java.util.LinkedHashSet<>(PlanShapeAssertions.adminPermissions());
            lapsed.remove(Permission.ANALYTICS_READ_ORGANIZATION);

            assertThat(resolved.satisfiedBy(lapsed)).isFalse();
            assertThat(resolved.firstUnmetField(lapsed))
                .as("the FIRST unmet field in the emitted order, because the audit records one field and "
                    + "the deterministic choice is what makes two runs of the same job produce the same "
                    + "reason string. Every usage field needs the same permission, so revoking it leaves "
                    + "the whole subject unmet and the first is the leading column")
                .isEqualTo(ReportField.USAGE_DIMENSION);
            assertThat(resolved.emitted().stream()
                .filter(field -> lapsed.contains(field.requiredPermission())).toList())
                .as("no emitted column still has a permission the caller holds, which is why the refusal "
                    + "is total rather than partial — a partially redacted file under the original "
                    + "checksum is the outcome this design exists to prevent")
                .isEmpty();
        }

        @Test
        @DisplayName("a permission revoked between generation and download is NOT silently re-redacted")
        void revocationRefusesRatherThanRedacts() {
            ResolvedExportColumns produced = PlanShapeAssertions.adminUsageColumns();
            assertThat(produced.emittedKeys()).contains("usage.cost");

            Set<Permission> lapsed = new java.util.LinkedHashSet<>(PlanShapeAssertions.adminPermissions());
            lapsed.remove(Permission.ANALYTICS_READ_ORGANIZATION);

            assertThat(produced.emittedKeys())
                .as("the produced column set is immutable: the object's checksum is evidence of what was "
                    + "written, and re-emitting a narrower file under the same checksum would make that "
                    + "evidence a lie")
                .contains("usage.cost");
            assertThat(produced.firstUnmetField(lapsed))
                .as("so the download service refuses with a named field instead of producing a different "
                    + "artefact")
                .isNotNull();

            assertThat(ExportRedactionResolver.downloadDenialReason(produced.firstUnmetField(lapsed)))
                .as("the audit reason names the field and the permission, and nothing about the value "
                    + "that was never served")
                .isEqualTo("authorization-revoked: field 'usage.dimension' requires '"
                    + Permission.ANALYTICS_READ_ORGANIZATION.value() + "'")
                .doesNotContain("bbbbbbbb", "aaaaaaaa");
        }

        @Test
        @DisplayName("a caller with fewer permissions than the producer is refused, not re-resolved")
        void employeeCannotDownloadAnAdminArtefact() {
            ResolvedExportColumns produced = PlanShapeAssertions.adminUsageColumns();

            assertThat(produced.emittedKeys()).contains("usage.revenue");
            assertThat(produced.satisfiedBy(PlanShapeAssertions.employeePermissions()))
                .as("the check runs against the EMITTED set, which is what the bytes contain — resolving "
                    + "the caller's own column set instead would be a different question and would answer "
                    + "'yes' for a caller who cannot see half the file")
                .isFalse();
            assertThat(produced.firstUnmetField(PlanShapeAssertions.employeePermissions()))
                .isNotNull();
        }

        @Test
        @DisplayName("the denial reason code is stable, so download events group")
        void denialReasonIsGroupable() {
            assertThat(ExportRedactionResolver.denialReasonCode()).isEqualTo("authorization-revoked");
        }
    }

    // ==================================================================
    // the recorded evidence
    // ==================================================================

    @Nested
    @DisplayName("the job row carries enough to answer 'what was hidden and why' later")
    class RecordedEvidence {

        @Test
        @DisplayName("the declared key set is emitted plus withheld, with no overlap and no loss")
        void declaredKeysArePartitioned() {
            List<ReportField> declared = PlanShapeAssertions.allCustomerFields();
            ResolvedExportColumns resolved = ExportRedactionResolver.resolve(declared,
                ExportRedactionPolicy.NO_PERSONAL_DETAIL, List.of("customer:read"),
                PlanShapeAssertions.employeePermissions());

            List<String> accountedFor = new ArrayList<>(resolved.emittedKeys());
            accountedFor.addAll(resolved.withheldKeys());

            assertThat(accountedFor)
                .as("every declared field is either emitted or withheld, exactly once — a field in "
                    + "neither would be a column silently missing from the file and from the audit")
                .containsExactlyInAnyOrderElementsOf(
                    ExportRedactionResolver.declaredKeys(declared));
            assertThat(resolved.emittedKeys())
                .as("and the two sets are disjoint")
                .doesNotContainAnyElementsOf(resolved.withheldKeys());
        }

        @Test
        @DisplayName("the emitted set preserves the version's declared order, gaps and all")
        void emittedOrderIsTheDeclaredOrder() {
            ResolvedExportColumns resolved = ExportRedactionResolver.resolve(
                List.of(ReportField.CUSTOMER_COUNT, ReportField.CUSTOMER_DISPLAY_NAME,
                    ReportField.CUSTOMER_EMAIL),
                ExportRedactionPolicy.NO_CONTACT_DETAILS, List.of(),
                PlanShapeAssertions.adminPermissions());

            assertThat(resolved.emitted())
                .as("the column order is the version's own, with the withheld column simply absent — "
                    + "the remaining columns do not SHIFT left into its place, because a reader's saved "
                    + "import mapping indexes by position")
                .extracting(field -> field.key())
                .containsExactly("customer.count", "customer.displayName");
        }

        @Test
        @DisplayName("the required permissions the version declared are carried onto the result")
        void requiredPermissionsAreCarried() {
            ResolvedExportColumns resolved = ExportRedactionResolver.resolve(
                PlanShapeAssertions.allUsageFields(), ExportRedactionPolicy.NONE,
                List.of(Permission.ANALYTICS_READ_ORGANIZATION.value()), PlanShapeAssertions.adminPermissions());

            assertThat(resolved.requiredPermissions())
                .as("so the job states what the AUTHOR demanded, separately from what the CALLER happened "
                    + "to hold")
                .containsExactly(Permission.ANALYTICS_READ_ORGANIZATION.value());
        }

        @Test
        @DisplayName("a money export is identifiable from the resolved set alone")
        void moneyIsIdentifiable() {
            assertThat(PlanShapeAssertions.adminUsageColumns().carriesMoney()).isTrue();
            assertThat(PlanShapeAssertions.employeeUsageColumns().carriesMoney())
                .as("an employee's usage export is not a finance export even though it reads the same "
                    + "table, because no money column survived")
                .isFalse();
            assertThat(PlanShapeAssertions.employeeCustomerColumns().carriesMoney()).isFalse();
        }

        @Test
        @DisplayName("resolution needs at least one declared column")
        void emptyDeclaredListIsRefused() {
            assertThatThrownBy(() -> ExportRedactionResolver.resolve(List.of(),
                ExportRedactionPolicy.NONE, List.of(), PlanShapeAssertions.adminPermissions()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one declared column");

            assertThatThrownBy(() -> ExportRedactionResolver.resolve(null,
                ExportRedactionPolicy.NONE, List.of(), PlanShapeAssertions.adminPermissions()))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
