package com.firstelement.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.security.permission.Permission;
import com.firstelement.security.permission.RolePermissions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The three places a role has to exist, asserted to be the same answer.
 *
 * <p>{@code UserRole.fromValue} THROWS on a label it does not know. That is the correct behaviour --
 * an unrecognised role must not be silently treated as the least-privileged one -- but it means a
 * {@code users} row carrying a role this enum omits cannot be read at all. The user does not get a
 * denied action; the user gets a failed sign-in, with no permission decision to audit and no clue
 * which role was involved. That is a far worse symptom than a refusal, and it is invisible until
 * somebody assigns the role.
 *
 * <p>The role therefore lives in three places that must move together:
 *
 * <ol>
 *   <li>the Prisma enum member, {@code packages/database/prisma/schema.prisma};
 *   <li>the Flyway migration, {@code V20261009130000__user_role_clinic_staff.sql};
 *   <li>this enum.
 * </ol>
 *
 * <p>(1) is TypeScript and is asserted by {@code packages/auth/src/clinic-role-grant.test.ts}. This
 * class asserts (2) against (3), which is the pair a Java process can actually see. A role added to
 * the database without being added here is the failure this exists to catch, and it is precisely the
 * half-crossing the Phase-38 migration left open: the Prisma enum gained {@code CLINIC_STAFF} while
 * {@code UserRole} still named two roles, so the first person to create such a user would have
 * locked themselves out of that account.
 */
class UserRoleContractTest {

    /** The migration that adds the label, in the chain. */
    private static final String MIGRATION = "V20261009130000__user_role_clinic_staff.sql";

    /** The baseline that creates the type, for the "the chain names every role" half. */
    private static final String BASELINE = "V20260814000000__baseline_verified_live_schema.sql";

    private static final Pattern ADD_VALUE = Pattern.compile(
        "ADD\\s+VALUE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?'([^']+)'", Pattern.CASE_INSENSITIVE);

    private static final Pattern CREATE_TYPE = Pattern.compile(
        "CREATE\\s+TYPE\\s+(?:\"public\"\\.)?\"?UserRole\"?\\s+AS\\s+ENUM\\s*\\(([^)]*)\\)",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Test
    @DisplayName("every role in the database is one this enum can read, and every one it declares exists")
    void theEnumAndTheChainNameTheSameRoles() throws IOException {
        List<String> fromChain = labelsTheChainAdds();
        List<String> fromBaseline = labelsTheBaselineCreates();

        // The two sets, not one. ADMIN and EMPLOYEE are created by the BASELINE; only CLINIC_STAFF
        // is added later. An earlier draft of this test asserted the chain-added labels alone
        // equalled the enum, which is wrong by construction and would have failed for the right
        // reason with a misleading message.
        List<String> inDatabase = new ArrayList<>(fromBaseline);
        inDatabase.addAll(fromChain);

        assertThat(inDatabase)
            .as("the baseline creates %s and V20261009130000 adds %s; together they must be exactly "
                + "the roles this enum declares", fromBaseline, fromChain)
            .containsExactlyInAnyOrderElementsOf(java.util.Arrays.stream(UserRole.values())
                .map(UserRole::name)
                .toList());

        assertThat(fromChain)
            .as("a role added to the database but not to this enum makes every user carrying it "
                + "fail to sign in, because fromValue throws rather than defaulting")
            .doesNotHaveDuplicates();
        assertThat(fromChain)
            .as("CLINIC_STAFF is the label this phase added; if it is gone, the role was reverted "
                + "on one side only")
            .contains("CLINIC_STAFF");
    }

    @Test
    @DisplayName("the baseline creates the type with the roles that predate the chain")
    void theBaselineCreatesTheTypeWithItsOwnRoles() throws IOException {
        List<String> baselineLabels = labelsTheBaselineCreates();

        assertThat(baselineLabels).isNotEmpty();
        assertThat(baselineLabels)
            .as("the baseline's own roles must still be readable by this enum")
            .allSatisfy(name -> assertThat(UserRole.fromValue(name)).isNotNull());
        assertThat(baselineLabels)
            .as("CLINIC_STAFF did not exist when the baseline was written; if it does, the chain's "
                + "IF NOT EXISTS makes the migration a no-op and the two stop being one change")
            .doesNotContain("CLINIC_STAFF");
    }

    @Test
    @DisplayName("fromValue refuses an unknown label instead of defaulting to the least privilege")
    void fromValueRefusesAnUnknownLabel() {
        assertThat(UserRole.fromValue("CLINIC_STAFF")).isEqualTo(UserRole.CLINIC_STAFF);
        assertThat(UserRole.fromValue("clinic_staff"))
            .as("the column is a PostgreSQL enum, whose labels compare case-sensitively, but a "
                + "hand-written query string need not")
            .isEqualTo(UserRole.CLINIC_STAFF);
        assertThatThrownBy(() -> UserRole.fromValue("SHIFT_SUPERVISOR"))
            .as("a role this enum does not know must NOT become EMPLOYEE: that is an escalation by "
                + "omission, and it is how a role name typo becomes a privilege grant")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SHIFT_SUPERVISOR");
    }

    @Test
    @DisplayName("CLINIC_STAFF is granted, and is a strict subset of EMPLOYEE")
    void clinicStaffIsAStrictSubsetOfEmployee() {
        var clinicStaff = RolePermissions.forRole(UserRole.CLINIC_STAFF);
        var employee = RolePermissions.forRole(UserRole.EMPLOYEE);

        assertThat(clinicStaff)
            .as("a role that resolves to nothing is a role nobody can use; this is the failure mode "
                + "when an enum member is added without a matching matrix entry")
            .isNotEmpty();
        assertThat(clinicStaff)
            .as("CLINIC_STAFF must never be a superset: its entire value is in what it cannot reach")
            .isSubsetOf(employee)
            .isNotEqualTo(employee);
        assertThat(clinicStaff)
            .as("a booking clerk must not read clinical notes, customers or conversations -- that is "
                + "the reach EMPLOYEE carried and this role exists to withhold")
            .doesNotContain(Permission.NOTE_READ, Permission.CUSTOMER_READ,
                Permission.CONVERSATION_READ, Permission.DEAL_READ, Permission.TASK_READ);
        assertThat(clinicStaff)
            .as("and it must read the clinic it books for")
            .contains(Permission.DOCTOR_READ, Permission.PROCEDURE_READ, Permission.APPOINTMENT_READ);
    }

    @Test
    @DisplayName("every role this enum declares resolves to a grant")
    void everyDeclaredRoleResolvesToAGrant() {
        for (UserRole role : UserRole.values()) {
            assertThat(RolePermissions.forRole(role))
                .as("%s is declared in the enum, so it must resolve to a grant rather than to "
                    + "Set.of() -- a role with no permissions is indistinguishable from a bug", role)
                .isNotEmpty();
        }
    }

    private static List<String> labelsTheChainAdds() throws IOException {
        String sql = read(MIGRATION);
        List<String> labels = new ArrayList<>();
        Matcher matcher = ADD_VALUE.matcher(sql);
        while (matcher.find()) {
            labels.add(matcher.group(1));
        }
        assertThat(labels)
            .as("%s must contain an ALTER TYPE ... ADD VALUE, or this test is asserting nothing",
                MIGRATION)
            .isNotEmpty();
        return labels;
    }

    private static List<String> labelsTheBaselineCreates() throws IOException {
        String sql = read(BASELINE);
        Matcher matcher = CREATE_TYPE.matcher(sql);
        assertThat(matcher.find())
            .as("the baseline must CREATE TYPE ... AS ENUM for \"UserRole\"; if this stops matching, "
                + "this test is asserting nothing. It is anchored to the type name on purpose: an "
                + "unanchored pattern silently matched a DIFFERENT enum in this same file and "
                + "reported its labels as the role vocabulary")
            .isTrue();
        List<String> labels = new ArrayList<>();
        Matcher label = Pattern.compile("'([^']+)'").matcher(matcher.group(1));
        while (label.find()) {
            labels.add(label.group(1));
        }
        return labels;
    }

    private static String read(String migrationFileName) {
        Path path = chainDir().resolve(migrationFileName);
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(
                "cannot read " + path.toAbsolutePath()
                    + ". This test asserts the Java enum against the migration chain, so a missing "
                    + "file means the chain and the application have diverged, which is the failure "
                    + "it exists to catch.", e);
        }
    }

    /**
     * {@code ops/flyway/sql}, found by walking up from this module.
     *
     * <p>Read from the SOURCE chain rather than from a classpath resource on purpose. The packaged
     * copy lives in the {@code database} module, which {@code tenant-security} does not depend on,
     * and adding that dependency purely so a test can read a file would be a real coupling for no
     * product reason. {@code ops/flyway/sql} is the chain's single source of truth --
     * {@code docs/migration/flyway-runbook.md} section "Two copies of the chain" says so, and
     * {@code FlywayBaselineAcceptanceTest} already asserts the two copies are byte-identical -- so
     * reading it here compares the enum against the same text that is shipped.
     */
    private static Path chainDir() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve("ops").resolve("flyway").resolve("sql");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException(
            "could not find ops/flyway/sql walking up from " + Path.of("").toAbsolutePath()
                + ". Run this from inside the repository.");
    }
}
