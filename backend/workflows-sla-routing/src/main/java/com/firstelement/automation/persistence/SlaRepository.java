package com.firstelement.automation.persistence;

import com.firstelement.automation.domain.AvailabilityWindow;
import com.firstelement.automation.domain.BusinessCalendar;
import com.firstelement.automation.domain.BusinessTime;
import com.firstelement.automation.domain.SlaClockType;
import com.firstelement.automation.domain.SlaPolicySnapshot;
import com.firstelement.security.query.ScopedWhere;
import com.firstelement.security.query.TenantScope;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code sla_calendars}, {@code sla_calendar_windows}, {@code sla_calendar_holidays} and
 * {@code sla_policies}.
 *
 * <h2>Statements copied from the canonical reference</h2>
 *
 * <p>§10a (effective calendar on a date), §10b (windows for one weekday), §10c (closures on a date)
 * and §10d (policies in explicit priority order). Each is written for the index it names:
 * {@code sla_calendars_organizationId_effectiveFrom_idx},
 * {@code sla_calendar_windows_calendar_day_idx} (the one tenant-less index in this slice),
 * {@code sla_calendar_holidays_calendar_date_key}, and
 * {@code sla_policies_organizationId_projectId_enabled_idx}.
 *
 * <h2>Why the two configuration children carry no tenant column</h2>
 *
 * <p>They inherit scope from the calendar, exactly as {@code ai_run_steps} inherits from
 * {@code ai_runs}. Duplicated scope on a configuration child could disagree with its parent; here it
 * cannot. Every read therefore starts from a calendar row already filtered by {@code organizationId}
 * and keys the children by {@code calendarId} — which is why the child reads carry no tenant
 * predicate and are still not an authorisation path.
 *
 * <h2>§10e is a check, not a comment</h2>
 *
 * <p>{@link #assertNoCrossTenantCalendar} is the one cross-tenant guarantee a foreign key already
 * provides by composite key, re-asserted with a loud failure so a hand-edited row cannot pause the
 * wrong clock silently. Phase 6a recorded it as "the one cross-tenant check a foreign key cannot do
 * for you"; the composite FK added later closed the write path, and this method closes the read path
 * for a database that predates it.
 */
@Repository
public class SlaRepository {

    /** One calendar row. */
    public record CalendarRow(UUID id,
                              UUID organizationId,
                              UUID projectId,
                              String key,
                              String name,
                              int version,
                              String state,
                              String timezone,
                              LocalDate effectiveFrom,
                              LocalDate effectiveTo,
                              UUID supersededById,
                              UUID createdByUserId) {
    }

    /** One window row. */
    public record WindowRow(UUID id, UUID calendarId, int dayOfWeek, int startMinute, int endMinute,
                            String label) {
    }

    /** One closure row. */
    public record HolidayRow(UUID id, UUID calendarId, LocalDate holidayDate, String label,
                             Integer startMinute, Integer endMinute) {
    }

    /** One policy row. */
    public record PolicyRow(UUID id,
                            UUID organizationId,
                            UUID projectId,
                            String key,
                            String name,
                            String defaultForPriority,
                            int priority,
                            boolean enabled,
                            String clockType,
                            Integer firstResponseMinutes,
                            Integer nextResponseMinutes,
                            Integer resolutionMinutes,
                            UUID calendarId,
                            Integer warnBeforeMinutes,
                            Integer escalateAfterMinutes) {
    }

    private static final RowMapper<CalendarRow> CALENDAR = (rs, rowNum) -> new CalendarRow(
        rs.getObject("id", UUID.class),
        rs.getObject("organizationId", UUID.class),
        rs.getObject("projectId", UUID.class),
        rs.getString("key"),
        rs.getString("name"),
        rs.getInt("version"),
        rs.getString("state"),
        rs.getString("timezone"),
        rs.getObject("effectiveFrom", LocalDate.class),
        rs.getObject("effectiveTo", LocalDate.class),
        rs.getObject("supersededById", UUID.class),
        rs.getObject("createdByUserId", UUID.class));

    private static final RowMapper<WindowRow> WINDOW = (rs, rowNum) -> new WindowRow(
        rs.getObject("id", UUID.class),
        rs.getObject("calendarId", UUID.class),
        rs.getInt("dayOfWeek"),
        rs.getInt("startMinute"),
        rs.getInt("endMinute"),
        rs.getString("label"));

    private static final RowMapper<HolidayRow> HOLIDAY = (rs, rowNum) -> new HolidayRow(
        rs.getObject("id", UUID.class),
        rs.getObject("calendarId", UUID.class),
        rs.getObject("holidayDate", LocalDate.class),
        rs.getString("label"),
        rs.getObject("startMinute", Integer.class),
        rs.getObject("endMinute", Integer.class));

    private static final RowMapper<PolicyRow> POLICY = (rs, rowNum) -> new PolicyRow(
        rs.getObject("id", UUID.class),
        rs.getObject("organizationId", UUID.class),
        rs.getObject("projectId", UUID.class),
        rs.getString("key"),
        rs.getString("name"),
        rs.getString("defaultForPriority"),
        rs.getInt("priority"),
        rs.getBoolean("isEnabled"),
        rs.getString("clockType"),
        rs.getObject("firstResponseMinutes", Integer.class),
        rs.getObject("nextResponseMinutes", Integer.class),
        rs.getObject("resolutionMinutes", Integer.class),
        rs.getObject("calendarId", UUID.class),
        rs.getObject("warnBeforeMinutes", Integer.class),
        rs.getObject("escalateAfterMinutes", Integer.class));

    private final NamedParameterJdbcTemplate jdbc;

    public SlaRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // authoring
    // ------------------------------------------------------------------

    /**
     * Creates a calendar in {@code DRAFT}.
     *
     * <p>The default state is {@code DRAFT} in the schema for a reason the rehearsal found: with
     * only {@code ACTIVE/SUPERSEDED/ARCHIVED} there is no way to author version 2 while version 1 is
     * live, because {@code sla_calendars_one_active_per_key} refuses the insert. The real fix is a
     * draft state, not a weaker index.
     */
    public UUID insertCalendar(TenantScope scope, UUID calendarId, UUID projectId, String key,
                               String name, int version, String timezone, LocalDate effectiveFrom,
                               UUID authorUserId) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", calendarId);
        values.put("projectId", projectId);
        values.put("key", key);
        values.put("name", name);
        values.put("version", version);
        values.put("timezone", timezone);
        values.put("effectiveFrom", effectiveFrom);
        values.put("authorUserId", authorUserId);
        jdbc.update("""
            insert into sla_calendars
                ("id", "organizationId", "projectId", "key", "name", "version", "state", "timezone",
                 "effectiveFrom", "createdByUserId", "createdAt", "updatedAt")
            values (:id, :__fe_tenant_organization_id, :projectId, :key, :name, :version,
                    cast('DRAFT' as "SlaCalendarState"), :timezone, :effectiveFrom, :authorUserId,
                    (now() AT TIME ZONE 'UTC'), (now() AT TIME ZONE 'UTC'))
            """, bind(scope, values));
        return calendarId;
    }

    /**
     * Activates a calendar and retires the incumbent for the same key.
     *
     * <p>One transaction, one order: retire the {@code ACTIVE} version for {@code (org, key)} so
     * {@code sla_calendars_one_active_per_key} frees up, then activate. A 0-row retire is legitimate
     * (first activation); anything else means a concurrent author.
     */
    public int activateCalendar(TenantScope scope, UUID calendarId) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("calendarId", calendarId);
        values.put("id", calendarId);
        // The incumbent stops being effective the day the successor starts, not today: a calendar
        // activated with a backdated effectiveFrom must leave the previous version covering the days
        // before it, or every clock between the two dates loses its working week entirely.
        values.put("today", LocalDate.now(java.time.ZoneOffset.UTC));
        MapSqlParameterSource parameters = bind(scope, values);

        int retired = jdbc.update("""
            update sla_calendars
               set "state" = cast('SUPERSEDED' as "SlaCalendarState"),
                   "supersededById" = :calendarId,
                   "effectiveTo" = :today,
                   "updatedAt" = (now() AT TIME ZONE 'UTC')
             where "organizationId" = :__fe_tenant_organization_id
               and "key" = (select k."key" from sla_calendars k
                               where k."id" = :calendarId
                                 and k."organizationId" = :__fe_tenant_organization_id)
               and "id" <> :calendarId
               and "state" = cast('ACTIVE' as "SlaCalendarState")
            """, parameters);
        if (retired > 1) {
            // Two live versions for one key means a concurrent author got in; the partial unique index
            // sla_calendars_one_active_per_key should make this unreachable, so refusing the write is
            // cheaper than letting one of the two silently win.
            throw new IllegalStateException(
                "Refusing to activate " + calendarId + ": " + retired
                    + " ACTIVE versions share its key, which the one-active-per-key index forbids.");
        }

        // DRAFT OR SUPERSEDED, mirroring workflow activation: re-activating a superseded version is
        // how a rollback works, so a DRAFT-only predicate would force the rollback path to be
        // written twice and the two copies to drift.
        return jdbc.update("""
            update sla_calendars
               set "state" = cast('ACTIVE' as "SlaCalendarState"),
                   "updatedAt" = (now() AT TIME ZONE 'UTC')
             where "id" = :id and "organizationId" = :__fe_tenant_organization_id
               and "state" in (cast('DRAFT' as "SlaCalendarState"),
                                 cast('SUPERSEDED' as "SlaCalendarState"))
            """, parameters);
    }

    /** Adds a weekly window. The same-day invariant is the table's CHECK, not this code's opinion. */
    public UUID insertWindow(TenantScope scope, UUID windowId, UUID calendarId, int dayOfWeek,
                             int startMinute, int endMinute, String label) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", windowId);
        values.put("calendarId", calendarId);
        values.put("dayOfWeek", dayOfWeek);
        values.put("startMinute", startMinute);
        values.put("endMinute", endMinute);
        values.put("label", label);
        jdbc.update("""
            insert into sla_calendar_windows
                ("id", "calendarId", "dayOfWeek", "startMinute", "endMinute", "label", "createdAt")
            values (:id, :calendarId, :dayOfWeek, :startMinute, :endMinute, :label,
                    (now() AT TIME ZONE 'UTC'))
            """, bind(scope, values));
        return windowId;
    }

    /** Adds a dated closure. {@code null} bounds close the whole day. */
    public UUID insertHoliday(TenantScope scope, UUID holidayId, UUID calendarId, LocalDate date,
                              String label, Integer startMinute, Integer endMinute) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", holidayId);
        values.put("calendarId", calendarId);
        values.put("holidayDate", date);
        values.put("label", label);
        MapSqlParameterSource parameters = bind(scope, values);
        parameters.addValue("startMinute", startMinute, java.sql.Types.INTEGER);
        parameters.addValue("endMinute", endMinute, java.sql.Types.INTEGER);
        jdbc.update("""
            insert into sla_calendar_holidays
                ("id", "calendarId", "holidayDate", "label", "startMinute", "endMinute", "createdAt")
            values (:id, :calendarId, :holidayDate, :label, :startMinute, :endMinute,
                    (now() AT TIME ZONE 'UTC'))
            """, parameters);
        return holidayId;
    }

    /** Creates a policy. The clock-ordering CHECK is the authority; see SlaPolicySnapshot. */
    public UUID insertPolicy(TenantScope scope, UUID policyId, UUID projectId, String key, String name,
                             String description, String defaultForPriority, int priority,
                             SlaClockType clockType, Integer firstResponseMinutes,
                             Integer nextResponseMinutes, Integer resolutionMinutes, UUID calendarId,
                             Integer warnBeforeMinutes, Integer escalateAfterMinutes, UUID authorUserId) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", policyId);
        values.put("projectId", projectId);
        values.put("key", key);
        values.put("name", name);
        values.put("description", description);
        values.put("defaultForPriority", defaultForPriority);
        values.put("priority", priority);
        values.put("clockType", clockType == null ? SlaClockType.RESOLUTION.name() : clockType.name());
        values.put("firstResponseMinutes", firstResponseMinutes);
        values.put("nextResponseMinutes", nextResponseMinutes);
        values.put("resolutionMinutes", resolutionMinutes);
        values.put("calendarId", calendarId);
        values.put("warnBeforeMinutes", warnBeforeMinutes);
        values.put("escalateAfterMinutes", escalateAfterMinutes);
        values.put("authorUserId", authorUserId);

        MapSqlParameterSource parameters = bind(scope, values);
        addNull(parameters, "description", java.sql.Types.VARCHAR);
        addNull(parameters, "defaultForPriority", java.sql.Types.VARCHAR);
        addNull(parameters, "firstResponseMinutes", java.sql.Types.INTEGER);
        addNull(parameters, "nextResponseMinutes", java.sql.Types.INTEGER);
        addNull(parameters, "resolutionMinutes", java.sql.Types.INTEGER);
        addNull(parameters, "calendarId", java.sql.Types.OTHER);
        addNull(parameters, "warnBeforeMinutes", java.sql.Types.INTEGER);
        addNull(parameters, "escalateAfterMinutes", java.sql.Types.INTEGER);
        addNull(parameters, "authorUserId", java.sql.Types.OTHER);

        jdbc.update("""
            insert into sla_policies
                ("id", "organizationId", "projectId", "key", "name", "description",
                 "defaultForPriority", "priority", "isEnabled", "clockType", "firstResponseMinutes",
                 "nextResponseMinutes", "resolutionMinutes", "calendarId", "warnBeforeMinutes",
                 "escalateAfterMinutes", "createdByUserId", "createdAt", "updatedAt")
            values (:id, :__fe_tenant_organization_id, :projectId, :key, :name, :description,
                    :defaultForPriority, :priority, true, cast(:clockType as "SlaClockType"),
                    :firstResponseMinutes, :nextResponseMinutes, :resolutionMinutes, :calendarId,
                    :warnBeforeMinutes, :escalateAfterMinutes, :authorUserId,
                    (now() AT TIME ZONE 'UTC'), (now() AT TIME ZONE 'UTC'))
            """, parameters);
        return policyId;
    }

    /** Binds a possibly-null value with its JDBC type, so the driver never has to guess. */
    private static void addNull(MapSqlParameterSource parameters, String name, int sqlType) {
        Object value = parameters.getValue(name);
        if (value == null) {
            parameters.addValue(name, null, sqlType);
        }
    }

    // ------------------------------------------------------------------
    // reads
    // ------------------------------------------------------------------

    /**
     * §10a — the calendar version in force on a date, newest {@code effectiveFrom} first.
     *
     * <p>{@code ACTIVE OR SUPERSEDED}, not {@code ACTIVE} alone, and the difference is the
     * whole effective-dating feature. Activating version 2 retires version 1 to
     * {@code SUPERSEDED}, so a query restricted to {@code ACTIVE} returns <em>nothing</em> for
     * any date before version 2 took effect — the March window of a ticket opened in March
     * would have no working week at all. A superseded version is still the truth for the
     * dates it covered; {@code effectiveTo} records the boundary, and
     * {@code SlaClockService#calendarFor} resolves the same answer from the key's version
     * history when a policy names a calendar outside its own range.
     */
    public Optional<CalendarRow> effectiveCalendar(TenantScope scope, UUID projectId, LocalDate onDate) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("projectId", projectId);
        values.put("onDate", onDate);
        MapSqlParameterSource parameters = bind(scope, values);
        addNull(parameters, "projectId", java.sql.Types.OTHER);
        return jdbc.query("""
            select c."id", c."organizationId", c."projectId", c."key", c."name", c."version",
                   c."state", c."timezone", c."effectiveFrom", c."effectiveTo", c."supersededById",
                   c."createdByUserId"
              from sla_calendars c
             where c."organizationId" = :__fe_tenant_organization_id
               and (c."projectId" = :projectId or c."projectId" is null)
               and c."state" in (cast('ACTIVE' as "SlaCalendarState"),
                                 cast('SUPERSEDED' as "SlaCalendarState"))
               and c."effectiveFrom" <= :onDate
               and (c."effectiveTo" is null or c."effectiveTo" >= :onDate)
             order by c."effectiveFrom" desc, c."version" desc
              limit 1
             """, parameters, CALENDAR).stream().findFirst();
    }

    /**
     * Every calendar version of a key, for the historical-SLA read.
     *
     * <p>The version history is what makes "which working week applied on 2026-03-14" answerable
     * after somebody edits the calendar.
     */
    public List<CalendarRow> calendarVersions(TenantScope scope, String key) {
        return jdbc.query("""
            select c."id", c."organizationId", c."projectId", c."key", c."name", c."version",
                   c."state", c."timezone", c."effectiveFrom", c."effectiveTo", c."supersededById",
                   c."createdByUserId"
              from sla_calendars c
             where c."organizationId" = :__fe_tenant_organization_id and c."key" = :key
             order by c."effectiveFrom", c."version"
            """, bind(scope, Map.of("key", key)), CALENDAR);
    }

    public Optional<CalendarRow> findCalendar(TenantScope scope, UUID calendarId) {
        return jdbc.query("""
            select c."id", c."organizationId", c."projectId", c."key", c."name", c."version",
                   c."state", c."timezone", c."effectiveFrom", c."effectiveTo", c."supersededById",
                   c."createdByUserId"
              from sla_calendars c
             where c."organizationId" = :__fe_tenant_organization_id and c."id" = :id
            """, bind(scope, Map.of("id", calendarId)), CALENDAR).stream().findFirst();
    }

    /** §10b — one weekday's windows. Uses the tenant-less child index; reachable only through a
     *  tenant-filtered calendar row. */
    public List<WindowRow> windowsForDay(UUID calendarId, int dayOfWeek) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("calendarId", calendarId)
            .addValue("dayOfWeek", dayOfWeek);
        return jdbc.query("""
            select w."id", w."calendarId", w."dayOfWeek", w."startMinute", w."endMinute", w."label"
              from sla_calendar_windows w
             where w."calendarId" = :calendarId and w."dayOfWeek" = :dayOfWeek
             order by w."startMinute"
            """, parameters, WINDOW);
    }

    /** Every window of a calendar, grouped for {@link BusinessTime}. */
    public List<AvailabilityWindow> allWindows(UUID calendarId, String calendarTimezone) {
        List<AvailabilityWindow> windows = new ArrayList<>();
        for (WindowRow row : jdbc.query("""
            select w."id", w."calendarId", w."dayOfWeek", w."startMinute", w."endMinute", w."label"
              from sla_calendar_windows w
             where w."calendarId" = :calendarId
             order by w."dayOfWeek", w."startMinute"
            """, new MapSqlParameterSource("calendarId", calendarId), WINDOW)) {
            windows.add(new AvailabilityWindow(row.dayOfWeek(), row.startMinute(), row.endMinute(),
                calendarTimezone));
        }
        return windows;
    }

    /** §10c — closures on a date. The unique index is both the guarantee and the read. */
    public List<HolidayRow> holidaysOn(UUID calendarId, LocalDate date) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("calendarId", calendarId)
            .addValue("holidayDate", date);
        return jdbc.query("""
            select h."id", h."calendarId", h."holidayDate", h."label", h."startMinute", h."endMinute"
              from sla_calendar_holidays h
             where h."calendarId" = :calendarId and h."holidayDate" = :holidayDate
             order by h."label"
            """, parameters, HOLIDAY);
    }

    /** Every closure of a calendar, keyed by date for the in-memory calendar. */
    public Map<LocalDate, BusinessCalendar.Holiday> allHolidays(UUID calendarId) {
        Map<LocalDate, BusinessCalendar.Holiday> holidays = new LinkedHashMap<>();
        for (HolidayRow row : jdbc.query("""
            select h."id", h."calendarId", h."holidayDate", h."label", h."startMinute", h."endMinute"
              from sla_calendar_holidays h
             where h."calendarId" = :calendarId
             order by h."holidayDate"
            """, new MapSqlParameterSource("calendarId", calendarId), HOLIDAY)) {
            holidays.put(row.holidayDate(),
                new BusinessCalendar.Holiday(row.holidayDate(), row.label(), row.startMinute(),
                    row.endMinute()));
        }
        return holidays;
    }

    /** Assembles the in-memory calendar a clock evaluates against. */
    public BusinessCalendar loadCalendar(TenantScope scope, UUID calendarId) {
        CalendarRow row = findCalendar(scope, calendarId).orElseThrow(
            () -> new IllegalArgumentException(
                "No SLA calendar " + calendarId + " in tenant " + scope.organizationId()));
        return new BusinessCalendar(row.id().toString(), row.key(), row.version(), row.timezone(),
            row.effectiveFrom(), row.effectiveTo(),
            BusinessTime.groupWindows(allWindows(row.id(), row.timezone())),
            allHolidays(row.id()));
    }

    /** §10d — policies that apply, in explicit priority order. */
    public List<PolicyRow> applicablePolicies(TenantScope scope, UUID projectId, int batchSize) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("projectId", projectId);
        values.put("batchSize", batchSize);
        MapSqlParameterSource parameters = bind(scope, values);
        addNull(parameters, "projectId", java.sql.Types.OTHER);
        return jdbc.query("""
            select p."id", p."organizationId", p."projectId", p."key", p."name", p."defaultForPriority",
                   p."priority", p."isEnabled", p."clockType", p."firstResponseMinutes",
                   p."nextResponseMinutes", p."resolutionMinutes", p."calendarId",
                   p."warnBeforeMinutes", p."escalateAfterMinutes"
              from sla_policies p
             where p."organizationId" = :__fe_tenant_organization_id
               and (p."projectId" = :projectId or p."projectId" is null)
               and p."isEnabled"
             order by p."priority", p."key"
             limit :batchSize
            """, parameters, POLICY);
    }

    /** The policy a conversation's priority resolves to, or empty when none applies. */
    public Optional<PolicyRow> policyForPriority(TenantScope scope, UUID projectId, String priority) {
        List<PolicyRow> candidates = new ArrayList<>();
        for (PolicyRow row : applicablePolicies(scope, projectId, 500)) {
            if (row.defaultForPriority() == null || row.defaultForPriority().isBlank()) {
                candidates.add(row);
            } else if (row.defaultForPriority().equalsIgnoreCase(priority)) {
                return Optional.of(row);
            }
        }
        return candidates.stream().findFirst();
    }

    public Optional<PolicyRow> findPolicy(TenantScope scope, UUID policyId) {
        return jdbc.query("""
            select p."id", p."organizationId", p."projectId", p."key", p."name", p."defaultForPriority",
                   p."priority", p."isEnabled", p."clockType", p."firstResponseMinutes",
                   p."nextResponseMinutes", p."resolutionMinutes", p."calendarId",
                   p."warnBeforeMinutes", p."escalateAfterMinutes"
              from sla_policies p
             where p."organizationId" = :__fe_tenant_organization_id and p."id" = :id
            """, bind(scope, Map.of("id", policyId)), POLICY).stream().findFirst();
    }

    /** Projects the row for the evaluator, which re-asserts the clock-ordering invariant. */
    public static SlaPolicySnapshot toSnapshot(PolicyRow row) {
        return new SlaPolicySnapshot(row.id().toString(), row.key(), row.priority(),
            SlaClockType.fromWireValue(row.clockType()), row.firstResponseMinutes(),
            row.nextResponseMinutes(), row.resolutionMinutes(),
            row.calendarId() == null ? null : row.calendarId().toString(),
            row.warnBeforeMinutes(), row.escalateAfterMinutes());
    }

    /**
     * §10e — fail loudly if a policy in this tenant resolves another tenant's calendar.
     *
     * <p>Never throws for the ordinary case; the method exists so the guarantee has a statement and
     * a test rather than only a composite foreign key.
     */
    public void assertNoCrossTenantCalendar(TenantScope scope) {
        Integer offenders = jdbc.queryForObject("""
            select count(*)
              from sla_policies p
              join sla_calendars c on c."id" = p."calendarId"
             where p."organizationId" = :__fe_tenant_organization_id
               and c."organizationId" <> p."organizationId"
            """, bind(scope, Map.of()), Integer.class);
        if (offenders != null && offenders > 0) {
            throw new IllegalStateException(
                "SLA policy references a calendar belonging to another tenant (organizationId="
                    + scope.organizationId() + "). The clock would pause against the wrong working "
                    + "week, which no test of this policy's own output would reveal.");
        }
    }

    /** §12e — another tenant's policies are never reachable. */
    public int countOtherTenantPolicies(TenantScope scope, UUID policyId) {
        String sql = """
            select count(*) from sla_policies p
             where p."id" = :id and p."organizationId" <> :__fe_tenant_organization_id
            """;
        Integer count = jdbc.queryForObject(sql, bind(scope, Map.of("id", policyId)), Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * Binds caller values together with the scope's tenant parameters.
     *
     * <p>{@link ScopedWhere#bindNamed} is the only sanctioned way: it writes the tenant values last
     * and refuses a caller parameter of the reserved name, so a filter string cannot smuggle a
     * different tenant into a statement that looks scoped. {@code TenantScope} itself deliberately
     * carries no binding method, so the predicate and the parameters are always produced together.
     */
    private static MapSqlParameterSource bind(TenantScope scope, Map<String, ?> values) {
        return ScopedWhere.of(scope).bindNamed(values);
    }
}
