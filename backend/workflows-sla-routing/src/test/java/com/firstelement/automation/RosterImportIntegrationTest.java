package com.firstelement.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.automation.domain.RosterImport;
import com.firstelement.automation.service.RosterImportService;
import com.firstelement.security.TenantContext;
import org.springframework.security.access.AccessDeniedException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The roster import against a real database: preview, apply, and what is deliberately NOT done.
 *
 * <h2>The rule this suite exists for</h2>
 *
 * <p>Legacy: "**never blindly import malformed rows.** A silent skip means an ops manager believes
 * 40 agents exist when 37 do, and discovers the gap when escalations start queueing." Every row is
 * therefore either applied or reported with its row number and a reason. There is no path here that
 * returns success while dropping a row.
 *
 * <h2>What is out of scope, and stated rather than hidden</h2>
 *
 * <p>User provisioning is not ported: the legacy {@code commitImport} also created a {@code users}
 * row, minted a password-reset token and granted a membership, and all three belong to
 * {@code :backend:identity-access}. This module applies the roster to employees that already exist
 * and reports {@code USER_NOT_FOUND} per row for the rest. XLSX is not ported either — CSV only.
 */
@SpringBootTest(classes = WorkflowTestApplication.class)
class RosterImportIntegrationTest extends WorkflowIntegrationTestBase {

    @Autowired
    private WorkflowFixture fixture;

    @Autowired
    private RosterImportService roster;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    private TenantContext admin;

    @BeforeEach
    void reset() {
        fixture.reset();
        admin = PrincipalScope.adminA();
    }

    private RosterImport.Preview preview(String csv) {
        return PrincipalScope.call(admin, () -> roster.preview(admin, csv, null));
    }

    /**
     * A preview with its duplicate rows moved into the valid set.
     *
     * <p>A roster that <em>updates</em> an employee necessarily names somebody the tenant already
     * holds, so the duplicate bucket is where every legitimate update lands first. Confirming those
     * rows away is what an operator does in the admin screen, and it is the only way to reach
     * {@code apply} for an existing person — which is exactly the behaviour under test.
     */
    private RosterImport.Preview confirmingDuplicates(String csv) {
        RosterImport.Preview parsed = preview(csv);
        List<RosterImport.ValidatedRow> valid = new java.util.ArrayList<>(parsed.validRows());
        valid.addAll(parsed.duplicateRows());
        return new RosterImport.Preview(parsed.headers(), parsed.totalRows(), valid,
            parsed.invalidRows(), List.of());
    }

    private int count(String sql, Map<String, Object> parameters) {
        return fixture.count(sql, parameters);
    }

    /**
     * Creates a user, a membership and an employee in tenant A, so a roster row has something real
     * to apply to. User creation is the fixture's job precisely because the module does not do it.
     */
    private java.util.UUID seedEmployee(String email, String department) {
        java.util.UUID userId = java.util.UUID.randomUUID();
        java.util.UUID employeeId = java.util.UUID.randomUUID();
        java.time.LocalDateTime now = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
        jdbc.update("""
            insert into users ("id", "organizationId", "email", "passwordHash", "fullName", "role",
                               "locale", "isActive", "createdAt", "updatedAt")
            values (:id, :org, :email, 'not-a-real-hash', :name, cast('EMPLOYEE' as "UserRole"),
                    cast('ar' as "Locale"), true, :now, :now)
            """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("id", userId).addValue("org", WorkflowFixture.ORG_A).addValue("email", email)
            .addValue("name", "Roster " + department).addValue("now", now));
        jdbc.update("""
            insert into project_memberships ("id", "organizationId", "projectId", "userId", "createdAt")
            values (:id, :org, :project, :userId, :now)
            """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("id", java.util.UUID.randomUUID()).addValue("org", WorkflowFixture.ORG_A)
            .addValue("project", WorkflowFixture.PROJECT_A).addValue("userId", userId)
            .addValue("now", now));
        jdbc.update("""
            insert into employees ("id", "organizationId", "userId", "department",
                "availabilityStatus", "maxConcurrentConversations", "activeConversationCount",
                "createdAt", "updatedAt")
            values (:id, :org, :userId, :department, cast('ONLINE' as "EmployeeAvailabilityStatus"),
                    5, 0, :now, :now)
            """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("id", employeeId).addValue("org", WorkflowFixture.ORG_A)
            .addValue("userId", userId).addValue("department", department).addValue("now", now));
        return employeeId;
    }

    private Integer capacityOf(java.util.UUID employeeId) {
        return jdbc.queryForObject("""
            select e."maxConcurrentConversations" from employees e where e."id" = :id
            """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("id", employeeId), Integer.class);
    }

    private String departmentOf(java.util.UUID employeeId) {
        return jdbc.queryForObject("select e.\"department\" from employees e where e.\"id\" = :id",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("id", employeeId), String.class);
    }

    private List<String> skillsOf(java.util.UUID employeeId) {
        return jdbc.queryForList("""
            select s."skill" from employee_skills s where s."employeeId" = :id
            """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("id", employeeId), String.class);
    }

    private int windowsOf(java.util.UUID employeeId) {
        return jdbc.queryForObject("""
            select count(*) from employee_availability a where a."employeeId" = :id
            """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("id", employeeId), Integer.class);
    }

    // ------------------------------------------------------------------
    // preview
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a preview writes nothing and reports every row it cannot use")
    void previewIsReadOnlyAndComplete() {
        java.util.UUID seeded = seedEmployee("a@a.test", "old");

        RosterImport.Preview preview = preview("""
            email,fullname,department,max conversations,skills,working hours
            a@a.test,Valid Person,billing,7,python;sql,Mon 09:00-17:00
            b@a.test,Valid Person,billing,3,java,Mon 09:00-17:00
            not-an-email,Broken,Support,2,,Mon 09:00-17:00
            ,No Email,Support,2,,Mon 09:00-17:00
            c@a.test,Bad Capacity,Support,999999,,Mon 09:00-17:00
            d@a.test,Bad Hours,Support,3,go,not a window
            """);

        assertThat(preview.totalRows()).isEqualTo(6);
        assertThat(preview.validRows())
            .as("six data rows: two usable, one a duplicate of the seeded employee, three unusable")
            .hasSize(1);
        assertThat(preview.duplicateRows())
            .as("the seeded a@a.test employee is a duplicate, reported rather than merged")
            .hasSize(1);
        assertThat(preview.duplicateRows().get(0).duplicateOf()).isEqualTo("EXISTING_USER");
        assertThat(preview.invalidRows())
            .as("every unusable row is reported with its row number, and none is dropped")
            .hasSize(4);
        assertThat(preview.invalidRows()).extracting(RosterImport.ValidatedRow::rowNumber)
            .containsExactly(3, 4, 5, 6);
        assertThat(String.join(" | ", preview.invalidRows().get(0).errors()))
            .contains("not-an-email").contains("valid email");
        assertThat(String.join(" | ", preview.invalidRows().get(1).errors()))
            .contains("Email is required");
        assertThat(String.join(" | ", preview.invalidRows().get(2).errors()))
            .contains("Capacity").contains("between 1 and 200");
        assertThat(String.join(" | ", preview.invalidRows().get(3).errors()))
            .contains("Working hours");

        assertThat(departmentOf(seeded))
            .as("a preview writes nothing, so the seeded employee keeps its original department "
                + "rather than taking the roster's")
            .isEqualTo("old");
        assertThat(capacityOf(seeded))
            .as("and keeps its original capacity, not the roster's 7")
            .isEqualTo(5);
        assertThat(skillsOf(seeded))
            .as("and gains no skills from the roster the preview just parsed")
            .isEmpty();
        assertThat(windowsOf(seeded))
            .as("and no working hours either: a preview that applied hours would create an agent "
                + "routable at 3am")
            .isZero();
    }

    @Test
    @DisplayName("an unparseable working-hours cell is an error, not a silently dropped value")
    void badWorkingHoursAreAnError() {
        RosterImport.Preview preview = preview("""
            email,fullname,department,working hours
            hours@a.test,Bad Hours,Support,not a window
            overnight@a.test,Overnight,Support,Mon 22:00-02:00
            """);

        assertThat(preview.validRows()).isEmpty();
        assertThat(preview.invalidRows()).hasSize(2);
        assertThat(String.join(" | ", preview.invalidRows().get(0).errors()))
            .contains("Working hours");
        assertThat(String.join(" | ", preview.invalidRows().get(1).errors()))
            .as("a roster that silently drops hours produces an agent who is routable at 3am, and an "
                + "overnight shift must be two rows for the same reason the calendar table refuses it")
            .contains("overnight");
    }

    @Test
    @DisplayName("a row the tenant already holds is reported as a duplicate, not merged")
    void duplicatesAreReportedNotMerged() {
        seedEmployee("dup@a.test", "old");

        RosterImport.Preview preview = preview("""
            email,fullname,department
            dup@a.test,Someone,Support
            """);

        assertThat(preview.duplicateRows())
            .as("a file listing the same person twice usually means two different intended records")
            .hasSize(1);
        assertThat(preview.duplicateRows().get(0).duplicateOf()).isEqualTo("EXISTING_USER");
    }

    @Test
    @DisplayName("an employee cannot import a roster")
    void employeeCannotImport() {
        assertThatThrownBy(() -> PrincipalScope.call(PrincipalScope.employeeA(), () ->
            roster.preview(PrincipalScope.employeeA(), "email,fullname\na@a.test,A\n", null)))
            .as("EMPLOYEE_IMPORT is the legacy permission for exactly this and is ADMIN-only")
            .isInstanceOf(AccessDeniedException.class);
    }

    // ------------------------------------------------------------------
    // apply
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a row the tenant already holds is reported as a duplicate and NOT applied")
    void duplicateIsReportedAndNotApplied() {
        java.util.UUID employeeId = seedEmployee("dupe@a.test", "original");

        RosterImport.Preview preview = preview("""
            email,fullname,department,max conversations
            dupe@a.test,Overwrite Attempt,billing,99
            """);

        assertThat(preview.duplicateRows())
            .as("a file listing the same person twice usually means two different intended records, "
                + "so it is reported rather than merged")
            .hasSize(1);
        RosterImportService.ApplyReport report =
            PrincipalScope.call(admin, () -> roster.apply(admin, preview));

        assertThat(report.applied()).isZero();
        assertThat(report.skipped()).anySatisfy(reason -> assertThat(reason)
            .contains("duplicate").contains("not merged"));
        assertThat(departmentOf(employeeId))
            .as("the operator's existing record must survive an ambiguous file untouched")
            .isEqualTo("original");
        assertThat(capacityOf(employeeId)).isEqualTo(5);
    }

    @Test
    @DisplayName("applying sets department, capacity, skills and working hours")
    void applyWritesTheWholeRoster() {
        java.util.UUID employeeId = seedEmployee("apply@a.test", "old");

        // The roster that updates somebody IS expected to name somebody this tenant already holds,
        // so the duplicate check is confirmed away the way an operator confirms it in the admin
        // screen: by moving that row into the valid set explicitly. Everything else about the
        // preview — parsing, mapping, validation — is unchanged.
        RosterImport.Preview confirmed = confirmingDuplicates("""
            email,fullname,department,max conversations,skills,working hours
            apply@a.test,Applied Person,billing,9,python;sql,Mon 09:00-17:00; Wed 10:00-14:00
            """);
        assertThat(confirmed.validRows()).hasSize(1);
        RosterImportService.ApplyReport report =
            PrincipalScope.call(admin, () -> roster.apply(admin, confirmed));

        assertThat(report.applied()).isEqualTo(1);
        assertThat(report.skipped()).isEmpty();
        assertThat(departmentOf(employeeId)).isEqualTo("billing");
        assertThat(capacityOf(employeeId)).isEqualTo(9);
        assertThat(skillsOf(employeeId)).containsExactlyInAnyOrder("python", "sql");
        assertThat(windowsOf(employeeId))
            .as("two window entries, each with its own timezone from the row")
            .isEqualTo(2);
    }

    @Test
    @DisplayName("a row with no matching user is reported USER_NOT_FOUND, never silently dropped")
    void unknownUserIsReportedPerRow() {
        seedEmployee("known@a.test", "old");

        // Two rows: one this tenant already holds, one it does not. The first is confirmed away as a
        // duplicate (an update to an existing person); the second has no user at all and is the
        // subject of the assertion.
        RosterImport.Preview preview = confirmingDuplicates("""
            email,fullname,department
            known@a.test,Known,Support
            ghost@a.test,Unknown,Support
            """);

        RosterImportService.ApplyReport report =
            PrincipalScope.call(admin, () -> roster.apply(admin, preview));

        assertThat(report.applied()).as("only the row that resolved").isEqualTo(1);
        assertThat(report.skipped()).hasSize(1);
        assertThat(report.skipped().get(0))
            .as("the operator must learn WHICH row was skipped and why, or they believe the roster "
                + "landed in full")
            .contains("row 2").contains("USER_NOT_FOUND").contains("ghost@a.test");
    }

    @Test
    @DisplayName("applying replaces skills and windows wholesale, so a removed skill goes away")
    void applyReplacesRatherThanMerges() {
        java.util.UUID employeeId = seedEmployee("replace@a.test", "old");
        fixture.employeeSkill(WorkflowFixture.ORG_A, employeeId, "obsolete", 1);
        fixture.employeeWindow(WorkflowFixture.ORG_A, employeeId, 1, 540, 1020, "UTC");

        RosterImport.Preview preview = confirmingDuplicates("""
            email,fullname,department,skills,working hours
            replace@a.test,Replaced,billing,keep-me,Mon 09:00-17:00
            """);
        PrincipalScope.call(admin, () -> roster.apply(admin, preview));

        assertThat(skillsOf(employeeId))
            .as("a partial merge is how somebody keeps a skill they were removed from last quarter")
            .containsExactly("keep-me");
        assertThat(windowsOf(employeeId)).isEqualTo(1);
    }

    @Test
    @DisplayName("a blank capacity leaves the stored value alone rather than clearing it")
    void blankCapacityIsNotAClear() {
        java.util.UUID employeeId = seedEmployee("blank@a.test", "old");
        assertThat(capacityOf(employeeId)).isEqualTo(5);

        RosterImport.Preview preview = confirmingDuplicates("""
            email,fullname,department,max conversations
            blank@a.test,No Capacity,billing,
            """);
        PrincipalScope.call(admin, () -> roster.apply(admin, preview));

        assertThat(capacityOf(employeeId))
            .as("an operator importing a name-and-email list should not reset everybody to the default")
            .isEqualTo(5);
        assertThat(departmentOf(employeeId)).isEqualTo("billing");
    }

    @Test
    @DisplayName("applying the same file twice is idempotent in effect")
    void applyIsIdempotentInEffect() {
        java.util.UUID employeeId = seedEmployee("idem@a.test", "old");
        RosterImport.Preview preview = confirmingDuplicates("""
            email,fullname,department,max conversations,skills,working hours
            idem@a.test,Idempotent,billing,7,python,Mon 09:00-17:00
            """);

        PrincipalScope.call(admin, () -> roster.apply(admin, preview));
        int skillsAfterFirst = skillsOf(employeeId).size();
        int windowsAfterFirst = windowsOf(employeeId);

        PrincipalScope.call(admin, () -> roster.apply(admin, preview));

        assertThat(skillsOf(employeeId)).hasSize(skillsAfterFirst);
        assertThat(windowsOf(employeeId)).isEqualTo(windowsAfterFirst);
        assertThat(capacityOf(employeeId)).isEqualTo(7);
    }

    @Test
    @DisplayName("the apply writes no roster contents anywhere an operator list would leak them")
    void applyLeavesNoRosterContentsBehind() {
        seedEmployee("audit@a.test", "old");
        RosterImport.Preview preview = confirmingDuplicates("""
            email,fullname,department
            audit@a.test,Audited Person,billing
            """);
        RosterImportService.ApplyReport report =
            PrincipalScope.call(admin, () -> roster.apply(admin, preview));

        assertThat(report.applied()).isEqualTo(1);
        // The apply itself must not create a users row: user provisioning is identity-access's job,
        // and an import that quietly created accounts would be a second account system.
        assertThat(count("select count(*) from users u where u.\"email\" = 'audit@a.test'",
            Map.of()))
            .as("the roster applies to existing employees and creates nobody")
            .isEqualTo(1);
        assertThat(count("select count(*) from sessions", Map.of()))
            .as("and mints no password-reset token, which the legacy import did")
            .isZero();
    }

    @Test
    @DisplayName("an empty file previews as empty rather than failing")
    void emptyFileIsHarmless() {
        RosterImport.Preview preview = preview("");

        assertThat(preview.totalRows()).isZero();
        assertThat(preview.validRows()).isEmpty();
        RosterImportService.ApplyReport report =
            PrincipalScope.call(admin, () -> roster.apply(admin, preview));
        assertThat(report.applied()).isZero();
    }

    @Test
    @DisplayName("the row cap is enforced, and the operator sees a bounded preview")
    void oversizedFileIsBounded() {
        StringBuilder csv = new StringBuilder("email,fullname\n");
        for (int row = 0; row < RosterImport.MAX_IMPORT_ROWS + 50; row++) {
            csv.append("bulk").append(row).append("@a.test,Bulk ").append(row).append('\n');
        }

        RosterImport.Preview preview = preview(csv.toString());

        assertThat(preview.totalRows())
            .as("the legacy cap is a truncation, not an error: the operator sees 5000 rows and a "
                + "note, not a stack trace")
            .isEqualTo(RosterImport.MAX_IMPORT_ROWS);
    }
}
