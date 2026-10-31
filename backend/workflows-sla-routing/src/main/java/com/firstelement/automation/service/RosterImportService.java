package com.firstelement.automation.service;

import com.firstelement.automation.domain.RosterImport;
import com.firstelement.security.TenantContext;
import com.firstelement.security.permission.AuthorizationService;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.query.TenantQueryGuard;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Employee roster import: preview, then apply.
 *
 * <h2>What is scoped out, and why it is stated rather than hidden</h2>
 *
 * <p>The legacy {@code commitImport} in {@code apps/api/src/routing/routing.service.ts} did four
 * things: created a {@code users} row, minted a password-reset token, granted a
 * {@code project_memberships} row, and set the roster (department, capacity, skills, working hours).
 * Only the fourth belongs to a routing module:
 *
 * <ul>
 *   <li>User creation, password reset and membership granting are {@code :backend:identity-access}
 *       behaviour and the Phase-6 task file does not own that module. Inventing a second user-creation
 *       path here would be a new account system.</li>
 *   <li>Therefore {@link #apply} reports {@code USER_NOT_FOUND} for every row whose email matches no
 *       existing user in this tenant. <b>Nothing is skipped silently</b> — the legacy rule is "every row
 *       is either valid or carries a row-numbered reason", and that holds for the rows this module
 *       cannot fulfil.</li>
 *   <li>XLSX is not ported: this module takes no spreadsheet dependency. CSV is ported in full. The
 *       gap is recorded in the Phase-6 report for the Phase-7/9 admin surface, which already owns
 *       document and export handling.</li>
 * </ul>
 *
 * <h2>Every write is scoped and bounded</h2>
 *
 * <p>{@link RosterImport#MAX_IMPORT_ROWS} bounds the batch, every statement carries the tenant's
 * organizationId, and a row for an employee in another tenant is not found rather than written.
 */
@Service
public class RosterImportService {

    private static final Logger log = LoggerFactory.getLogger(RosterImportService.class);

    /** Applied to one valid row. */
    public record AppliedRow(int rowNumber, String email, UUID employeeId, List<String> applied) {
    }

    /** The result of an apply pass: what landed, and what did not, with a reason for each. */
    public record ApplyReport(int applied, List<AppliedRow> rows, List<String> skipped) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final TenantQueryGuard guard;
    private final AuthorizationService authorization;

    public RosterImportService(NamedParameterJdbcTemplate jdbc, TenantQueryGuard guard,
                               AuthorizationService authorization) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.authorization = authorization;
    }

    /**
     * Parses, maps and validates a CSV roster without writing anything.
     *
     * <p>Requires {@link Permission#EMPLOYEE_IMPORT}, which is the legacy permission for exactly this
     * operation and is ADMIN-only.
     */
    @Transactional(readOnly = true)
    public RosterImport.Preview preview(TenantContext context, String csv,
                                        RosterImport.ColumnMapping mapping) {
        authorization.check(context, Permission.EMPLOYEE_IMPORT);
        guard.tenantScope(context);
        RosterImport.Sheet sheet = RosterImport.parseCsv(csv);
        RosterImport.ColumnMapping effective = mapping == null || mapping.byField().isEmpty()
            ? RosterImport.suggestMapping(sheet.headers()) : mapping;
        return RosterImport.validate(sheet, effective, existingEmails(context));
    }

    /**
     * Applies the valid rows.
     *
     * @param preview a preview produced by {@link #preview} for the same tenant; its invalid and
     *                 duplicate rows are <b>not</b> applied and are reported
     */
    @Transactional
    public ApplyReport apply(TenantContext context, RosterImport.Preview preview) {
        authorization.check(context, Permission.EMPLOYEE_IMPORT);
        MapSqlParameterSource tenant = new MapSqlParameterSource()
            .addValue("organizationId", context.organizationId());
        List<AppliedRow> applied = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        preview.invalidRows().forEach(row ->
            skipped.add("row " + row.rowNumber() + ": " + String.join("; ", row.errors())));
        preview.duplicateRows().forEach(row ->
            skipped.add("row " + row.rowNumber() + ": duplicate (" + row.duplicateOf() + "), reported "
                + "and not merged"));

        for (RosterImport.ValidatedRow row : preview.validRows()) {
            UUID employeeId = employeeIdFor(context, row.record().email());
            if (employeeId == null) {
                skipped.add("row " + row.rowNumber() + ": USER_NOT_FOUND — no employee with email "
                    + row.record().email() + " exists in this tenant, and creating users is not this "
                    + "module's job (see the class comment)");
                continue;
            }
            List<String> what = new ArrayList<>();
            updateEmployee(context, employeeId, row.record(), now, what);
            replaceSkills(context, employeeId, row.record(), what);
            replaceAvailability(context, employeeId, row.record(), what);
            applied.add(new AppliedRow(row.rowNumber(), row.record().email(), employeeId, List.copyOf(what)));
        }

        log.info("roster import applied tenant={} applied={} skipped={}",
            context.organizationId(), applied.size(), skipped.size());
        return new ApplyReport(applied.size(), List.copyOf(applied), List.copyOf(skipped));
    }

    /**
     * The emails already present, so the preview can report a duplicate instead of overwriting.
     *
     * <p>Bounded and tenant-scoped: this is a whole-column read, which is the one shape in this module
     * that is not keyed by an id. {@link RosterImport#MAX_IMPORT_ROWS} caps what it is compared
     * against, and a tenant with a million users still returns a bounded page rather than every
     * address.
     */
    public Set<String> existingEmails(TenantContext context) {
        Set<String> emails = new LinkedHashSet<>();
        for (String email : jdbc.queryForList("""
            select u."email" from users u
             where u."organizationId" = :organizationId
             order by u."id"
             limit :batchSize
            """, tenantParameters(context).addValue("batchSize", RosterImport.MAX_IMPORT_ROWS),
            String.class)) {
            emails.add(email == null ? "" : email.trim().toLowerCase(java.util.Locale.ROOT));
        }
        return emails;
    }

    /**
     * The employee carrying an email in this tenant, or null.
     *
     * <p>Null rather than an exception: the caller reports {@code USER_NOT_FOUND} per row, which is
     * the whole point of a preview. The join carries the tenant on both sides so an email that
     * exists in another tenant matches nothing here rather than a different person.
     */
    private UUID employeeIdFor(TenantContext context, String email) {
        List<UUID> ids = jdbc.queryForList("""
            select e."id" from employees e
              join users u on u."id" = e."userId" and u."organizationId" = e."organizationId"
             where e."organizationId" = :organizationId
               and lower(u."email") = :email
             order by e."id"
             limit 1
            """, tenantParameters(context)
                .addValue("email", email.toLowerCase(java.util.Locale.ROOT)), UUID.class);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /**
     * Sets department and capacity.
     *
     * <p>A null capacity in the roster leaves the stored value alone rather than clearing it: an
     * operator importing a name-and-email list should not silently reset everybody to the default.
     * A non-null one overwrites. The SET targets are unqualified because PostgreSQL does not accept
     * a target alias there.
     */
    private void updateEmployee(TenantContext context, UUID employeeId,
                                RosterImport.Record record, LocalDateTime now, List<String> applied) {
        MapSqlParameterSource parameters = tenantParameters(context)
            .addValue("employeeId", employeeId)
            .addValue("department", record.department())
            .addValue("maxConversations", record.maxConcurrentConversations())
            .addValue("now", now);
        if (parameters.getValue("department") == null) {
            parameters.addValue("department", null, java.sql.Types.VARCHAR);
        }
        if (parameters.getValue("maxConversations") == null) {
            parameters.addValue("maxConversations", null, java.sql.Types.INTEGER);
        }
        int changed = jdbc.update("""
            update employees e
               set "department" = coalesce(:department, e."department"),
                   "maxConcurrentConversations" = coalesce(:maxConversations,
                                                           e."maxConcurrentConversations"),
                   "updatedAt" = :now
             where e."id" = :employeeId and e."organizationId" = :organizationId
            """, parameters);
        if (changed == 1) {
            applied.add("department/capacity");
        }
    }

    /**
     * Replaces the employee's skills wholesale.
     *
     * <p>Delete-then-insert rather than a diff, because a roster is a complete statement of the
     * desired skills and a partial merge is how somebody keeps a skill they were removed from last
     * quarter. One transaction, so a failure cannot leave an employee with no skills at all.
     */
    private void replaceSkills(TenantContext context, UUID employeeId, RosterImport.Record record,
                               List<String> applied) {
        MapSqlParameterSource base = tenantParameters(context).addValue("employeeId", employeeId);
        jdbc.update("""
            delete from employee_skills s
             where s."employeeId" = :employeeId and s."organizationId" = :organizationId
            """, base);
        int level = 3;
        for (String skill : record.skills()) {
            jdbc.update("""
                insert into employee_skills ("id", "organizationId", "employeeId", "skill", "level",
                                              "createdAt")
                values (:id, :organizationId, :employeeId, :skill, :level, :now)
                """, tenantParameters(context)
                    .addValue("id", UUID.randomUUID())
                    .addValue("employeeId", employeeId)
                    .addValue("skill", skill)
                    .addValue("level", level)
                    .addValue("now", LocalDateTime.now(ZoneOffset.UTC)));
        }
        applied.add("skills=" + record.skills().size());
    }

    /**
     * Replaces the employee's weekly availability wholesale, for the same reason as the skills.
     *
     * <p>The window's timezone comes from the parsed row, not from the server, so a multi-region
     * roster keeps each person's own working week.
     */
    private void replaceAvailability(TenantContext context, UUID employeeId,
                                     RosterImport.Record record, List<String> applied) {
        MapSqlParameterSource base = tenantParameters(context).addValue("employeeId", employeeId);
        jdbc.update("""
            delete from employee_availability a
             where a."employeeId" = :employeeId and a."organizationId" = :organizationId
            """, base);
        for (com.firstelement.automation.domain.AvailabilityWindow window : record.windows()) {
            jdbc.update("""
                insert into employee_availability ("id", "organizationId", "employeeId", "dayOfWeek",
                    "startMinute", "endMinute", "timezone", "createdAt")
                values (:id, :organizationId, :employeeId, :dayOfWeek, :startMinute, :endMinute,
                        :timezone, :now)
                """, tenantParameters(context)
                    .addValue("id", UUID.randomUUID())
                    .addValue("employeeId", employeeId)
                    .addValue("dayOfWeek", window.dayOfWeek())
                    .addValue("startMinute", window.startMinute())
                    .addValue("endMinute", window.endMinute())
                    .addValue("timezone", window.timezone())
                    .addValue("now", LocalDateTime.now(ZoneOffset.UTC)));
        }
        applied.add("windows=" + record.windows().size());
    }

    private MapSqlParameterSource tenantParameters(TenantContext context) {
        return new MapSqlParameterSource().addValue("organizationId", context.organizationId());
    }

    /** The audit metadata an apply writes. Names, never values. */
    static Map<String, Object> auditMetadata(ApplyReport report) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("applied", report.rows().size());
        metadata.put("skipped", report.skipped().size());
        return Map.copyOf(metadata);
    }
}
