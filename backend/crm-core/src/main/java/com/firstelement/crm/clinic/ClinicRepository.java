package com.firstelement.crm.clinic;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The clinic read model, over the real {@code doctors}, {@code procedures} and
 * {@code appointments} tables.
 *
 * <h2>What this reads, and what it deliberately does not</h2>
 *
 * <p>Three tables, no materialized aggregate, no cache, and every row produced by a
 * {@code select} whose tenant predicate is supplied by the caller as a {@link ClinicScope}.
 * There is no code path from a controller to a statement that has not passed the membership
 * check, because the scope object has no unchecked constructor.
 *
 * <p><b>No clinician contact details.</b> {@code doctors.email} and {@code doctors.phone}
 * are not projected. Phase 33 drew a line between the bookable roster and the roster plus
 * contact details, and put the second behind {@code doctor:read-management}. This module
 * routes only {@code doctor:read}, so projecting the phone number here would hand every
 * front-desk clerk the roster's contact list through the back door of a read authorized for
 * something narrower. The columns are absent from the SQL rather than masked in the
 * response, which is the stronger guarantee: a value that was never read cannot leak through
 * a log line, an exception message or a future projection change.
 *
 * <h2>Why each metric statement returns the rows that produced its own number</h2>
 *
 * <p>ADR 0013 requires that every clinic metric drill to source rows, and the mission makes
 * it the deciding requirement: "an analytics number that cannot be traced back to the rows
 * that produced it is a number nobody can defend to a finance owner."
 *
 * <p>So each metric statement computes the number and collects the source ids <b>in the same
 * grouped statement</b>. That is not a convenience. A count from one {@code select count(*)}
 * and a row list from a second {@code select ... limit n} are two queries that can disagree
 * — one filters on a status the other does not, one has a different date boundary — and the
 * disagreement is invisible because both look correct on their own. Here
 * {@code count(*)} and {@code array_agg(id)} are evaluated over the same rows by construction.
 *
 * <p>Every row also carries {@code sourceRowCount}: the <b>unbounded</b> truth next to the
 * bounded sample, so a reader is told when they are looking at a sample rather than being
 * left to assume the array is everything.
 *
 * <h2>How the predicates compose</h2>
 *
 * <p>Each statement is split into {@link #SELECT_DOCTORS} (projection + from) and a tail, so
 * a caller can append its own predicate to the tenant one <b>inside the same {@code WHERE}</b>.
 * Applying an id filter after the fact instead would be a real defect: the limit could be
 * consumed by rows that are then discarded, answering "not found" for a clinician that
 * exists and is in scope.
 */
@Repository
public class ClinicRepository {

    // ------------------------------------------------------------------
    // roster and catalogue
    // ------------------------------------------------------------------

    /** Projection + from for the roster. The tenant predicate and any caller filter follow. */
    private static final String SELECT_DOCTORS = """
        select d."id", d."organizationId", d."projectId", d."name", d."specialty", d."department",
               d."workingDays", d."startHour", d."endHour", d."slotDuration", d."isActive",
               d."createdAt", d."updatedAt"
          from doctors d
         where %s
        """;

    private static final String SELECT_PROCEDURES = """
        select p."id", p."organizationId", p."projectId", p."name", p."specialty",
               p."durationMinutes", p."bufferMinutes", p."price", p."isActive",
               p."createdAt", p."updatedAt",
               p."productCode", p."subCategory", p."clinicId",
               cl."name" as "clinicName", p."timeSlotMinutes", p."type",
               p."description", p."discount", p."imageUrl", p."systemService",
               p."advancedPayment"
          from procedures p
          left join clinic_locations cl
                 on cl."id" = p."clinicId"
                and cl."organizationId" = p."organizationId"
         where %s
        """;

    /**
     * The schedule, ordered by exactly the key the cursor paginates on.
     *
     * <p>{@code ("appointmentDate" desc, "id" desc)} — the tie-break is mandatory, not
     * cosmetic. Two appointments at the same instant would otherwise be ordered arbitrarily
     * between pages, so one is returned twice and another is skipped.
     *
     * <h2>Why every parameter is bound even when it is absent</h2>
     *
     * <p>Two details here were measured, not reasoned about, and both turn an ordinary first
     * page into a 500 if they are got wrong:
     *
     * <ul>
     *   <li><b>Omitting a named parameter is a 500.</b> {@code NamedParameterJdbcTemplate} raises
     *       when a placeholder has no value, so a first page with no cursor answers
     *       {@code INTERNAL_ERROR} instead of the page that was asked for. Every parameter is
     *       therefore always bound, {@code null} included.</li>
     *   <li><b>{@code ::type} is an escape, not a cast.</b> Spring's named-parameter parser reads a
     *       doubled colon as a literal colon, so {@code :status::text} is not "the status
     *       parameter cast to text". {@link #CAST} is the explicit form, and it is applied through
     *       {@link String#formatted} rather than concatenated so the statement text is read once.</li>
     * </ul>
     *
     * <p>The alternative — two statement variants, one with each predicate and one without — was
     * rejected: two copies of a statement are exactly the pair that drifts apart, and the copy
     * nobody edits is the one that starts returning a wider set.
     *
     * <p>Substitution is <b>positional</b> ({@code %2$s}) rather than named ({@code %(status)s}).
     * {@code String.formatted} passes a plain varargs array, so there are no parameter names for
     * {@code %(name)s} to resolve and it raises
     * {@code FormatFlagsConversionMismatchException: Conversion = s, Flags = (} at run time —
     * a 500 on the first page of the schedule, which is what it did until this was measured.
     */
    private static final String SELECT_APPOINTMENTS = """
        select a."id", a."organizationId", a."projectId", a."customerId", a."patientId",
               a."clinicId", a."doctorId", a."procedureId", a."title", a."patientName",
               a."patientEmail", a."appointmentDate", a."durationMinutes",
               a."status", a."service", a."doctorName", d."imageUrl" as "doctorImageUrl",
               a."price", a."quantity", (a."price" * a."quantity") as "totalAmount",
               a."paymentStatus", a."medicalReportName", a."medicalReportUrl",
               a."isAiProposed", a."ticketNumber", a."createdAt", a."updatedAt"
          from appointments a
          left join doctors d
                 on d."id" = a."doctorId"
                and d."organizationId" = a."organizationId"
         where %s
           and a."appointmentDate" > :from and a."appointmentDate" <= :to
           and (%2$s is null or a."status" = %2$s)
           and (%5$s is null or a."paymentStatus" = %5$s)
           and (%6$s is null or (
                lower(coalesce(a."patientName", '') || ' '
                    || coalesce(a."patientPhone", '') || ' '
                    || coalesce(a."patientEmail", '') || ' '
                    || coalesce(a."doctorName", '') || ' '
                    || coalesce(a."service", '') || ' '
                    || coalesce(a."ticketNumber", '')) like %6$s))
           and (%3$s is null
                or (a."appointmentDate", a."id") < (%3$s, %4$s))
         order by a."appointmentDate" desc, a."id" desc
         limit :fetchLimit
        """;

    /**
     * The explicit cast forms, applied to the schedule statement's optional predicates.
     *
     * <p>Named rather than inline so a reader sees the type once instead of having to notice
     * that {@code :x::y} does not mean what it looks like.
     */
    private static final Map<String, String> CAST = Map.of(
        "status", "cast(:status as text)",
        "paymentStatus", "cast(:paymentStatus as text)",
        "search", "cast(:search as text)",
        "cursorDate", "cast(:cursorDate as timestamp)",
        "cursorId", "cast(:cursorId as uuid)");

    /**
     * One appointment by id, through the caller's scope.
     *
     * <p>A dedicated statement rather than "page until the id turns up": paging with an unbounded
     * window would make a detail route cost a whole page to return one row, and a reader of this
     * class could not tell which columns the two paths project.
     */
    private static final String SELECT_APPOINTMENT_BY_ID = """
        select a."id", a."organizationId", a."projectId", a."customerId", a."patientId",
               a."clinicId", a."doctorId", a."procedureId", a."title", a."patientName",
               a."patientEmail", a."appointmentDate", a."durationMinutes",
               a."status", a."service", a."doctorName", d."imageUrl" as "doctorImageUrl",
               a."price", a."quantity", (a."price" * a."quantity") as "totalAmount",
               a."paymentStatus", a."medicalReportName", a."medicalReportUrl",
               a."isAiProposed", a."ticketNumber", a."createdAt", a."updatedAt"
          from appointments a
          left join doctors d
                 on d."id" = a."doctorId"
                and d."organizationId" = a."organizationId"
         where %s and a."id" = :appointmentId
         limit 1
        """;

    // ------------------------------------------------------------------
    // metrics
    // ------------------------------------------------------------------

    /**
     * The appointment funnel: a count and the source ids for each status in the window.
     *
     * <p><b>No {@code limit}, and the reason is structural rather than stylistic.</b> A
     * {@code limit} on a grouped statement truncates the <em>groups</em>: a funnel missing its
     * {@code CANCELLED} row is a funnel that silently under-reports, and under-reporting is the
     * exact defect class this module exists to remove. The sample is therefore bounded
     * <em>inside</em> the group, with {@code row_number()}, and every status survives.
     *
     * <p>The bounds are bound parameters ({@code :sampleLimit}), never spliced into the text.
     * Two earlier shapes failed here and both failures are worth recording:
     *
     * <ul>
     *   <li>{@code array_agg(...)[1:50]} — Spring's named-parameter parser reads the {@code :}
     *       of the array subscript as a parameter marker and raises
     *       {@code No value supplied for the SQL parameter '50'}. An array slice and a named
     *       parameter are syntactically indistinguishable to that parser.</li>
     *   <li>{@code limit 50} spliced as text — correct SQL, but it puts a value in the statement
     *       rather than in the parameters, which is the habit that eventually produces a
     *       statement nobody can bound safely.</li>
     * </ul>
     *
     * <p>And the surviving shape had to be {@code array_agg(x ORDER BY ...) FILTER (WHERE ...)},
     * not {@code array_agg(x) FILTER (WHERE ...) ORDER BY ...}. PostgreSQL puts {@code FILTER}
     * after the aggregate's own {@code ORDER BY}, and the other order is a syntax error at the
     * word {@code order}. Measured, not remembered.
     */
    static final String FUNNEL_BY_STATUS = """
        select s."status" as "status",
               count(*) as "appointmentCount",
               coalesce(array_agg(s."id" order by s."rowNumber")
                   filter (where s."rowNumber" <= :sampleLimit), '{}') as "sourceRowIds"
          from (
              select a."id", a."status",
                     row_number() over (partition by a."status"
                                        order by a."appointmentDate" desc, a."id" desc)
                       as "rowNumber"
                from appointments a
               where %1$s
                 and a."appointmentDate" > :from and a."appointmentDate" <= :to
               ) s
         group by s."status"
         order by s."status" asc
        """;

    /**
     * Procedure demand: bookings and booked minutes per procedure, with the source ids.
     *
     * <p>A {@code left join} from {@code procedures} onto a tenant-scoped appointment
     * subquery, so a procedure with zero bookings appears with zero rather than being absent.
     * "No data" and "zero" are different facts and FR-7.6 requires them to look different; an
     * inner join renders an unused procedure as though it did not exist.
     *
     * <p>The subquery carries its <b>own</b> copy of the tenant predicate rather than relying on
     * the outer one. The join condition is on {@code procedureId} alone, so without it a
     * cross-tenant appointment could reach a procedure row this tenant owns — and Phase 35's
     * composite foreign keys make that impossible at the database but not at the query, and a
     * read model should not depend on a constraint it does not own.
     *
     * <p>Booked minutes are a {@code sum} over the stored {@code durationMinutes} of the
     * appointment rows — a sum a reader can list, not a rate or an extrapolation.
     *
     * <p>No money is derived here even though {@code procedures.price} is projected on the
     * catalogue route. Multiplying a catalogue price by a booking count is a revenue
     * definition, and ADR 0007 Part 1 puts revenue behind a versioned rate card rather than a
     * column on a procedure row.
     */
    static final String DEMAND_BY_PROCEDURE = """
        select p."id" as "procedureId", p."name" as "name", p."specialty" as "specialty",
               count(b."id") as "appointmentCount",
               coalesce(sum(b."durationMinutes"), 0) as "bookedMinutes",
               coalesce(array_agg(b."id" order by b."rowNumber")
                   filter (where b."rowNumber" <= :sampleLimit), '{}') as "sourceRowIds"
          from procedures p
          left join (
              select a."id", a."procedureId", a."durationMinutes",
                     row_number() over (partition by a."procedureId"
                                        order by a."appointmentDate" desc, a."id" desc)
                       as "rowNumber"
                from appointments a
               where %1$s
                 and a."appointmentDate" > :from and a."appointmentDate" <= :to
               ) b on b."procedureId" = p."id"
         where %2$s
         group by p."id", p."name", p."specialty"
         order by count(b."id") desc, p."name" asc
         limit :groupLimit
        """;

    /**
     * Clinician workload: appointments and booked minutes per doctor, with the source ids.
     *
     * <p>Left join for the same reason as {@link #DEMAND_BY_PROCEDURE}: a clinician with no
     * bookings in the window is a real row saying zero.
     *
     * <p>There is deliberately <b>no utilization percentage</b>. The legacy service computed
     * one as {@code bookings / (weeklySlots * 4) * 100 * 3}, clamped into {@code [25, 100]} —
     * a number whose inputs are a slot-duration guess, a four-week guess, a factor of three and
     * a floor, with no row behind any of it. This module publishes the two facts that do have
     * rows (bookings, booked minutes) and leaves the ratio to whoever owns the capacity policy.
     * A percentage is a stop-and-report item, not a formula to guess at.
     */
    static final String WORKLOAD_BY_DOCTOR = """
        select d."id" as "doctorId", d."name" as "name", d."specialty" as "specialty",
               count(b."id") as "appointmentCount",
               coalesce(sum(b."durationMinutes"), 0) as "bookedMinutes",
               coalesce(array_agg(b."id" order by b."rowNumber")
                   filter (where b."rowNumber" <= :sampleLimit), '{}') as "sourceRowIds"
          from doctors d
          left join (
              select a."id", a."doctorId", a."durationMinutes",
                     row_number() over (partition by a."doctorId"
                                        order by a."appointmentDate" desc, a."id" desc)
                       as "rowNumber"
                from appointments a
               where %1$s
                 and a."appointmentDate" > :from and a."appointmentDate" <= :to
               ) b on b."doctorId" = d."id"
         where %2$s
         group by d."id", d."name", d."specialty"
         order by count(b."id") desc, d."name" asc
         limit :groupLimit
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public ClinicRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // roster
    // ------------------------------------------------------------------

    /** The clinician roster in scope, bounded. */
    public List<ClinicModels.Doctor> doctors(ClinicScope scope, int limit) {
        return jdbc.query(SELECT_DOCTORS.formatted(scope.predicate("d"))
                + " order by d.\"name\" asc, d.\"id\" asc limit :limit",
            scope.bind(Map.of("limit", limit)),
            (rs, rowNum) -> readDoctor(rs));
    }

    /**
     * One clinician.
     *
     * <p>The id predicate is in the same {@code WHERE} as the tenant predicate. Applying it
     * after the fact would consume the limit on rows that are then discarded, which answers
     * "not found" for a clinician that exists and is in scope.
     */
    public Optional<ClinicModels.Doctor> doctor(ClinicScope scope, UUID doctorId) {
        return jdbc.query(SELECT_DOCTORS.formatted(scope.predicate("d"))
                + " and d.\"id\" = :doctorId limit 1",
            scope.bind(Map.of("doctorId", doctorId)),
            (rs, rowNum) -> readDoctor(rs)).stream().findFirst();
    }

    /** The procedure catalogue in scope, bounded. */
    public List<ClinicModels.Procedure> procedures(ClinicScope scope, int limit) {
        return jdbc.query(SELECT_PROCEDURES.formatted(scope.predicate("p"))
                + " order by p.\"name\" asc, p.\"id\" asc limit :limit",
            scope.bind(Map.of("limit", limit)),
            (rs, rowNum) -> readProcedure(rs));
    }

    /** One procedure, read through the caller's scope. */
    public Optional<ClinicModels.Procedure> procedure(ClinicScope scope, UUID procedureId) {
        return jdbc.query(SELECT_PROCEDURES.formatted(scope.predicate("p"))
                + " and p.\"id\" = :procedureId limit 1",
            scope.bind(Map.of("procedureId", procedureId)),
            (rs, rowNum) -> readProcedure(rs)).stream().findFirst();
    }

    // ------------------------------------------------------------------
    // schedule
    // ------------------------------------------------------------------

    /** One page of the schedule, over-fetched by one row so the cursor can see "more". */
    public List<ClinicModels.Appointment> appointments(ClinicScope scope,
                                                        LocalDateTime from,
                                                        LocalDateTime to,
                                                        String status,
                                                        Optional<ClinicScope.CursorBoundary> cursor,
                                                        int fetchLimit) {
        return appointments(scope, from, to, status, null, null, cursor, fetchLimit);
    }

    /** The appointment list, with the payment and free-text filters the screen offers. */
    public List<ClinicModels.Appointment> appointments(ClinicScope scope,
                                                        LocalDateTime from,
                                                        LocalDateTime to,
                                                        String status,
                                                        String paymentStatus,
                                                        String search,
                                                        Optional<ClinicScope.CursorBoundary> cursor,
                                                        int fetchLimit) {
        MapSqlParameterSource parameters = scope.bind(Map.of(
            "from", from, "to", to, "fetchLimit", fetchLimit));
        // Bound unconditionally, including when absent -- see SELECT_APPOINTMENTS' header for
        // why an unbound named parameter is a 500 rather than an ignored filter.
        parameters.addValue("status", status);
        parameters.addValue("paymentStatus", paymentStatus);
        parameters.addValue("search", search == null || search.isBlank()
                ? null
                : "%" + search.trim().toLowerCase(java.util.Locale.ROOT) + "%");
        if (cursor.isPresent()) {
            parameters.addValue("cursorDate", cursor.get().appointmentDate())
                .addValue("cursorId", cursor.get().id());
        } else {
            parameters.addValue("cursorDate", null).addValue("cursorId", null);
        }
        return jdbc.query(
            SELECT_APPOINTMENTS.formatted(scope.predicate("a"), CAST.get("status"),
                CAST.get("cursorDate"), CAST.get("cursorId"), CAST.get("paymentStatus"),
                CAST.get("search")),
            parameters,
            (rs, rowNum) -> readAppointment(rs));
    }

    /**
     * One appointment by id.
     *
     * <p>The id predicate sits in the same {@code WHERE} as the tenant predicate, so a foreign id
     * is simply not found rather than found and then filtered.
     */
    public Optional<ClinicModels.Appointment> appointment(ClinicScope scope, UUID appointmentId) {
        return jdbc.query(
            SELECT_APPOINTMENT_BY_ID.formatted(scope.predicate("a")),
            scope.bind(Map.of("appointmentId", appointmentId)),
            (rs, rowNum) -> readAppointment(rs)).stream().findFirst();
    }

    /**
     * The cursor boundary for a cursor id.
     *
     * <p>Read tenant-scoped: a cursor naming another tenant's appointment resolves to nothing,
     * and the keyset predicate then degenerates to "no boundary", which returns the first page
     * of the caller's <em>own</em> schedule. That is the right answer to a question about
     * somebody else's row — the caller is not shown that it exists, and is not shown its data.
     */
    public Optional<ClinicScope.CursorBoundary> cursorBoundary(ClinicScope scope, UUID cursor) {
        return jdbc.query("""
            select a."appointmentDate", a."id"
              from appointments a
             where %s and a."id" = :cursor
            """.formatted(scope.predicate("a")),
            scope.bind(Map.of("cursor", cursor)),
            (rs, rowNum) -> new ClinicScope.CursorBoundary(
                rs.getObject("appointmentDate", LocalDateTime.class),
                rs.getObject("id", UUID.class)))
            .stream().findFirst();
    }

    // ------------------------------------------------------------------
    // metrics
    // ------------------------------------------------------------------

    /** Funnel rows: one per appointment status present in the window. */
    public List<MetricRows.FunnelRow> funnelByStatus(ClinicScope scope, LocalDateTime from,
                                                      LocalDateTime to, int sampleLimit) {
        return jdbc.query(FUNNEL_BY_STATUS.formatted(scope.predicate("a")),
            scope.bind(Map.of("from", from, "to", to, "sampleLimit", sampleLimit)),
            (rs, rowNum) -> new MetricRows.FunnelRow(
                rs.getString("status"),
                rs.getLong("appointmentCount"),
                uuidArray(rs.getArray("sourceRowIds"))));
    }

    /** Procedure demand rows, busiest first, bounded by {@code groupLimit}. */
    public List<MetricRows.ProcedureDemandRow> demandByProcedure(ClinicScope scope, LocalDateTime from,
                                                                  LocalDateTime to, int sampleLimit,
                                                                  int groupLimit) {
        return jdbc.query(
            // Two copies of the tenant predicate: the appointment subquery and the procedure
            // table. Both are needed -- the join is on procedureId alone.
            DEMAND_BY_PROCEDURE.formatted(scope.predicate("a"), scope.predicate("p")),
            scope.bind(Map.of("from", from, "to", to, "sampleLimit", sampleLimit,
                "groupLimit", groupLimit)),
            (rs, rowNum) -> new MetricRows.ProcedureDemandRow(
                rs.getObject("procedureId", UUID.class),
                rs.getString("name"),
                rs.getString("specialty"),
                rs.getLong("appointmentCount"),
                rs.getLong("bookedMinutes"),
                uuidArray(rs.getArray("sourceRowIds"))));
    }

    /** Clinician workload rows, busiest first, bounded by {@code groupLimit}. */
    public List<MetricRows.DoctorWorkloadRow> workloadByDoctor(ClinicScope scope, LocalDateTime from,
                                                                LocalDateTime to, int sampleLimit,
                                                                int groupLimit) {
        return jdbc.query(
            WORKLOAD_BY_DOCTOR.formatted(scope.predicate("a"), scope.predicate("d")),
            scope.bind(Map.of("from", from, "to", to, "sampleLimit", sampleLimit,
                "groupLimit", groupLimit)),
            (rs, rowNum) -> new MetricRows.DoctorWorkloadRow(
                rs.getObject("doctorId", UUID.class),
                rs.getString("name"),
                rs.getString("specialty"),
                rs.getLong("appointmentCount"),
                rs.getLong("bookedMinutes"),
                uuidArray(rs.getArray("sourceRowIds"))));
    }

    // ------------------------------------------------------------------
    // row readers
    // ------------------------------------------------------------------

    private static ClinicModels.Doctor readDoctor(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ClinicModels.Doctor(
            rs.getObject("id", UUID.class),
            rs.getObject("organizationId", UUID.class),
            rs.getObject("projectId", UUID.class),
            rs.getString("name"),
            rs.getString("specialty"),
            rs.getString("department"),
            intArray(rs.getArray("workingDays")),
            rs.getString("startHour"),
            rs.getString("endHour"),
            rs.getInt("slotDuration"),
            rs.getBoolean("isActive"),
            rs.getObject("createdAt", LocalDateTime.class),
            rs.getObject("updatedAt", LocalDateTime.class));
    }

    private static ClinicModels.Procedure readProcedure(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        return new ClinicModels.Procedure(
            rs.getObject("id", UUID.class),
            rs.getObject("organizationId", UUID.class),
            rs.getObject("projectId", UUID.class),
            rs.getString("name"),
            rs.getString("specialty"),
            rs.getInt("durationMinutes"),
            rs.getInt("bufferMinutes"),
            (Double) rs.getObject("price"),
            rs.getBoolean("isActive"),
            rs.getObject("createdAt", LocalDateTime.class),
            rs.getObject("updatedAt", LocalDateTime.class),
            rs.getString("productCode"),
            rs.getString("subCategory"),
            rs.getObject("clinicId", UUID.class),
            rs.getString("clinicName"),
            (Integer) rs.getObject("timeSlotMinutes"),
            rs.getString("type"),
            rs.getString("description"),
            (Double) rs.getObject("discount"),
            rs.getString("imageUrl"),
            rs.getString("systemService"),
            rs.getBoolean("advancedPayment"));
    }

    private static ClinicModels.Appointment readAppointment(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        java.math.BigDecimal price = rs.getBigDecimal("price");
        java.math.BigDecimal total = rs.getBigDecimal("totalAmount");
        return new ClinicModels.Appointment(
            rs.getObject("id", UUID.class),
            rs.getObject("organizationId", UUID.class),
            rs.getObject("projectId", UUID.class),
            rs.getObject("customerId", UUID.class),
            rs.getObject("patientId", UUID.class),
            rs.getObject("clinicId", UUID.class),
            rs.getObject("doctorId", UUID.class),
            rs.getObject("procedureId", UUID.class),
            rs.getString("title"),
            rs.getString("patientName"),
            rs.getString("patientEmail"),
            rs.getObject("appointmentDate", LocalDateTime.class),
            rs.getInt("durationMinutes"),
            rs.getString("status"),
            rs.getString("service"),
            rs.getString("doctorName"),
            rs.getString("doctorImageUrl"),
            price == null ? null : price.doubleValue(),
            rs.getInt("quantity"),
            total == null ? null : total.doubleValue(),
            rs.getString("paymentStatus"),
            rs.getString("medicalReportName"),
            rs.getString("medicalReportUrl"),
            rs.getBoolean("isAiProposed"),
            rs.getString("ticketNumber"),
            rs.getObject("createdAt", LocalDateTime.class),
            rs.getObject("updatedAt", LocalDateTime.class));
    }

    // ------------------------------------------------------------------
    // array helpers
    // ------------------------------------------------------------------

    /**
     * A PostgreSQL {@code int[]} as a Java array.
     *
     * <p>A SQL NULL maps to an empty array rather than to {@code null}, so a roster row with
     * no working days configured is a readable "no days" instead of a caller-side NPE.
     */
    private static int[] intArray(java.sql.Array array) throws java.sql.SQLException {
        if (array == null) {
            return new int[0];
        }
        Object raw = array.getArray();
        if (!(raw instanceof Integer[] values)) {
            return new int[0];
        }
        int[] copy = new int[values.length];
        for (int index = 0; index < values.length; index++) {
            copy[index] = values[index] == null ? 0 : values[index];
        }
        return copy;
    }

    /**
     * A PostgreSQL {@code uuid[]} as a Java list.
     *
     * <p>The driver returns {@code UUID[]} for a {@code uuid[]}, but the JDBC contract only
     * promises {@code Object[]}, so both are handled. A NULL array is an empty list: "no source
     * rows" is a fact the metric reports, not a missing value.
     */
    private static List<UUID> uuidArray(java.sql.Array array) throws java.sql.SQLException {
        if (array == null) {
            return List.of();
        }
        Object raw = array.getArray();
        if (raw instanceof UUID[] values) {
            return Arrays.asList(values);
        }
        if (raw instanceof Object[] values) {
            return Arrays.stream(values)
                .map(value -> value instanceof UUID uuid ? uuid : null)
                .filter(java.util.Objects::nonNull)
                .toList();
        }
        return List.of();
    }
}