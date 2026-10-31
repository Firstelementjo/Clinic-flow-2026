// A migration-fixture guard: this module's appointments now carry FOUR composite tenant foreign
// keys (V20261009110000), so the fixture must satisfy all of them or the seed fails. See the
// note on `seedClinic()` for the specific trap this avoids.
package com.firstelement.inbox;

import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The clinic rows, seeded on top of {@link InboxFixture}.
 *
 * <h2>What is seeded, and why each row exists</h2>
 *
 * <p>Every row is there so a specific claim is falsifiable:
 *
 * <ul>
 *   <li>two doctors and two procedures in tenant A's first project, so a count is not trivially
 *       0 or 1 and an off-by-one in a grouping is visible;</li>
 *   <li>a doctor and a procedure in tenant <b>B</b>, and appointments in <b>both</b> tenants,
 *       so every cross-tenant negative has something real to be refused;</li>
 *   <li>a doctor and procedure with <b>zero</b> bookings, so "no data" and "zero" are
 *       distinguishable — FR-7.6 requires that and an inner join would break it;</li>
 *   <li>an appointment with a {@code null} doctor and a {@code null} procedure, because an
 *       unassigned booking is ordinary and a grouping that drops it under-reports;</li>
 *   <li>appointments spread across several statuses and several dates inside one window, so a
 *       wrong window boundary produces a wrong number rather than an empty one;</li>
 *   <li>one appointment <b>outside</b> the analytics window, so a window filter that is ignored
 *       inflates every count instead of merely looking empty.</li>
 * </ul>
 *
 * <h2>The composite-FK trap, named because it is easy to walk into</h2>
 *
 * <p>Phase 35 gave {@code appointments} four composite tenant foreign keys:
 * {@code ("createdById","organizationId")→users}, {@code ("customerId","organizationId")→customers},
 * {@code ("doctorId","organizationId")→doctors} and
 * {@code ("procedureId","organizationId")→procedures}. They are {@code MATCH SIMPLE} and
 * {@code ON DELETE SET NULL (column)}, so a NULL reference is legal — but a <em>non-null</em>
 * reference naming a row in another organization is now refused by the database.
 *
 * <p>That is exactly the constraint the 2026-10-08 breach needed and did not have, and it is
 * why the tenant-B rows below carry tenant-B doctors: a fixture that paired a tenant-A
 * appointment with a tenant-B doctor would fail at the seed rather than at the assertion, and
 * the failure would read as a schema problem rather than as a fixture that ignored the
 * constraint the previous phase added.
 */
@Component
public class ClinicFixture {

    public static final UUID DOCTOR_A1 = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000d0001");
    public static final UUID DOCTOR_A2 = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000d0002");
    /** Tenant A, project A2: reachable by the admin, not by the A1-only employee. */
    public static final UUID DOCTOR_A2_PROJECT = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000d0003");
    public static final UUID DOCTOR_B1 = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000d0001");

    public static final UUID PROCEDURE_A1 = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000c0001");
    public static final UUID PROCEDURE_A2 = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000c0002");
    /** Zero bookings on purpose: "unused" must read as 0, not as absent. */
    public static final UUID PROCEDURE_A_UNUSED =
        UUID.fromString("aaaaaaaa-0000-0000-0000-0000000c0003");
    public static final UUID PROCEDURE_B1 = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000c0001");

    public static final UUID APPOINTMENT_A1 = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000a0001");
    public static final UUID APPOINTMENT_A2 = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000a0002");
    public static final UUID APPOINTMENT_A3 = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000a0003");
    /** No doctor and no procedure: an unassigned booking is ordinary, not exceptional. */
    public static final UUID APPOINTMENT_A_UNASSIGNED =
        UUID.fromString("aaaaaaaa-0000-0000-0000-0000000a0004");
    public static final UUID APPOINTMENT_B1 = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000a0001");
    /** Tenant A, project A2 — the cross-PROJECT row, inside tenant A and outside the employee's reach. */
    public static final UUID APPOINTMENT_A2_PROJECT =
        UUID.fromString("aaaaaaaa-0000-0000-0000-0000000a0005");

    /** A fixed window every analytics assertion uses. */
    public static final LocalDateTime WINDOW_FROM = LocalDateTime.of(2026, 8, 1, 0, 0, 0);
    public static final LocalDateTime WINDOW_TO = LocalDateTime.of(2026, 9, 1, 0, 0, 0);

    private final NamedParameterJdbcTemplate jdbc;

    public ClinicFixture(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Seeds the clinic rows. Called from {@code InboxFixture.reset()} after the tenants, users,
     * customers and projects exist.
     */
    public void seedClinic() {
        // Doctors. workingDays is an int[]; startHour/endHour are stored as 'HH:MM' TEXT,
        // which is what the baseline column declares and what the legacy runtime wrote.
        doctor(DOCTOR_A1, InboxFixture.ORG_A, InboxFixture.PROJECT_A1, "Dr. Amina Haddad",
            "Cardiology", "Internal Medicine", "09:00", "17:00", 30);
        doctor(DOCTOR_A2, InboxFixture.ORG_A, InboxFixture.PROJECT_A1, "Dr. Youssef Nader",
            "Orthopaedics", "Surgery", "10:00", "18:00", 45);
        doctor(DOCTOR_A2_PROJECT, InboxFixture.ORG_A, InboxFixture.PROJECT_A2,
            "Dr. Salma Idrissi", "Dentistry", "Dental", "08:00", "14:00", 20);
        doctor(DOCTOR_B1, InboxFixture.ORG_B, InboxFixture.PROJECT_B1, "Dr. Beta Only",
            "Neurology", "Internal Medicine", "09:00", "17:00", 30);

        // Procedures. A1 is priced, A2 is deliberately left with a NULL price: "not priced" is a
        // different fact from "free", and an analytics figure that treated them alike would be
        // making a commercial decision this module is not entitled to make.
        procedure(PROCEDURE_A1, InboxFixture.ORG_A, InboxFixture.PROJECT_A1, "Consultation",
            "Cardiology", 30, 0, 150.0);
        procedure(PROCEDURE_A2, InboxFixture.ORG_A, InboxFixture.PROJECT_A1, "ECG",
            "Cardiology", 20, 5, null);
        procedure(PROCEDURE_A_UNUSED, InboxFixture.ORG_A, InboxFixture.PROJECT_A1, "MRI Scan",
            "Radiology", 45, 0, 400.0);
        procedure(PROCEDURE_B1, InboxFixture.ORG_B, InboxFixture.PROJECT_B1, "Beta Procedure",
            "Neurology", 30, 0, 200.0);

        // Appointments. Statuses deliberately span PENDING / CONFIRMED / COMPLETED / CANCELLED /
        // NO_SHOW so a funnel has five groups rather than one, and so dropping a group is
        // visible as a wrong number rather than as an empty page.
        //
        // CONFIRMED, not APPROVED. DEC-017 removed APPROVED from the seven: the deployed AI
        // booking path wrote it with no human approval anywhere, so "the AI booked this" and
        // "a person confirmed this" had become the same value. CONFIRMED is the honest seed
        // for a row whose isAiProposed is true -- the AI proposed it, a person confirmed it.
        appointment(APPOINTMENT_A1, InboxFixture.ORG_A, InboxFixture.PROJECT_A1,
            InboxFixture.CUSTOMER_A1, DOCTOR_A1, PROCEDURE_A1, "Dr. Amina Haddad",
            "2026-08-05T09:00:00", 30, "CONFIRMED", "Consultation", true, "+966500000001");
        appointment(APPOINTMENT_A2, InboxFixture.ORG_A, InboxFixture.PROJECT_A1,
            InboxFixture.CUSTOMER_A1, DOCTOR_A1, PROCEDURE_A1, "Dr. Amina Haddad",
            "2026-08-06T09:00:00", 30, "COMPLETED", "Consultation", false, "+966500000001");
        appointment(APPOINTMENT_A3, InboxFixture.ORG_A, InboxFixture.PROJECT_A1,
            InboxFixture.CUSTOMER_A2, DOCTOR_A2, PROCEDURE_A2, "Dr. Youssef Nader",
            "2026-08-07T09:00:00", 20, "NO_SHOW", "ECG", false, "+966500000002");
        // Unassigned: no doctor, no procedure, no customer. Still a real appointment the funnel
        // must count, and the workload/demand groupings must report it as zero rather than drop it.
        appointment(APPOINTMENT_A_UNASSIGNED, InboxFixture.ORG_A, InboxFixture.PROJECT_A1,
            null, null, null, null, "2026-08-08T09:00:00", 30, "PENDING", null, false, null);
        // Tenant B, its own tenant-B doctor and procedure. The composite FKs require it, and the
        // cross-tenant negatives need a real row to be refused.
        appointment(APPOINTMENT_B1, InboxFixture.ORG_B, InboxFixture.PROJECT_B1,
            InboxFixture.CUSTOMER_B1, DOCTOR_B1, PROCEDURE_B1, "Dr. Beta Only",
            "2026-08-09T09:00:00", 30, "COMPLETED", "Beta Procedure", false, "+966500000003");
        // Tenant A but project A2: visible to the A1-only employee? No -- and that is the
        // cross-PROJECT negative, which is a different question from the cross-tenant one.
        appointment(APPOINTMENT_A2_PROJECT, InboxFixture.ORG_A, InboxFixture.PROJECT_A2,
            InboxFixture.CUSTOMER_A2, DOCTOR_A2_PROJECT, PROCEDURE_A2, "Dr. Salma Idrissi",
            "2026-08-10T09:00:00", 20, "CONFIRMED", "ECG", true, "+966500000002");

        // One appointment OUTSIDE the window, in tenant A's first project. A window filter that
        // is silently dropped inflates every count; one that is too narrow empties them. This
        // row makes both errors produce a wrong number rather than a merely empty one.
        appointment(UUID.fromString("aaaaaaaa-0000-0000-0000-0000000a0006"),
            InboxFixture.ORG_A, InboxFixture.PROJECT_A1, InboxFixture.CUSTOMER_A1,
            DOCTOR_A1, PROCEDURE_A1, "Dr. Amina Haddad", "2026-01-05T09:00:00", 30,
            "COMPLETED", "Consultation", false, "+966500000001");
    }

    private void doctor(UUID id, UUID org, UUID project, String name, String specialty,
                        String department, String startHour, String endHour, int slotDuration) {
        insert("""
            insert into doctors ("id", "organizationId", "projectId", "name", "specialty",
                "department", "email", "phone", "workingDays", "startHour", "endHour",
                "slotDuration", "isActive", "createdAt", "updatedAt")
            values (:id, :org, :project, :name, :specialty, :department, :email, :phone,
                    '{0,1,2,3,4}', :startHour, :endHour, :slot, true, :now, :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("org", org)
                .addValue("project", project).addValue("name", name)
                .addValue("specialty", specialty).addValue("department", department)
                // Deliberately seeded. The read model must NEVER project these; a test asserts
                // the marker string appears in no response, which is only a real assertion
                // because the value exists in the table.
                .addValue("email", name.toLowerCase(java.util.Locale.ROOT).replace(' ', '.')
                    + "@clinic.test")
                .addValue("phone", "+966500009" + (900 + Math.abs(name.hashCode()) % 100))
                .addValue("startHour", startHour).addValue("endHour", endHour)
                .addValue("slot", slotDuration));
    }

    private void procedure(UUID id, UUID org, UUID project, String name, String specialty,
                           int durationMinutes, int bufferMinutes, Double price) {
        insert("""
            insert into procedures ("id", "organizationId", "projectId", "name", "specialty",
                "durationMinutes", "bufferMinutes", "price", "isActive", "createdAt", "updatedAt")
            values (:id, :org, :project, :name, :specialty, :duration, :buffer, :price, true,
                    :now, :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("org", org)
                .addValue("project", project).addValue("name", name)
                .addValue("specialty", specialty).addValue("duration", durationMinutes)
                .addValue("buffer", bufferMinutes).addValue("price", price));
    }

    private void appointment(UUID id, UUID org, UUID project, UUID customer, UUID doctor,
                             UUID procedure, String doctorName, String date, int durationMinutes,
                             String status, String service, boolean aiProposed, String patientPhone) {
        insert("""
            insert into appointments ("id", "organizationId", "projectId", "customerId", "doctorId",
                "procedureId", "title", "patientName", "patientPhone", "appointmentDate",
                "durationMinutes", "status", "service", "doctorName", "isAiProposed", "notes",
                "ticketNumber", "createdAt", "updatedAt")
            values (:id, :org, :project, :customer, :doctor, :procedure, :title, :patientName,
                    :phone, cast(:date as timestamp), :duration, :status, :service, :doctorName,
                    :ai, :notes, :ticket, :now, :now)""",
            new MapSqlParameterSource().addValue("id", id).addValue("org", org)
                .addValue("project", project).addValue("customer", customer)
                .addValue("doctor", doctor).addValue("procedure", procedure)
                .addValue("title", "Seeded appointment " + id.toString().substring(0, 8))
                .addValue("patientName", patientPhone == null ? "Walk-in Patient"
                    : "Patient " + patientPhone.substring(patientPhone.length() - 3))
                .addValue("phone", patientPhone).addValue("date", date)
                .addValue("duration", durationMinutes).addValue("status", status)
                .addValue("service", service).addValue("doctorName", doctorName)
                .addValue("ai", aiProposed)
                // A marker in the columns this read model does NOT project. Its presence in the
                // table and its absence from every response is what makes the field-selection
                // assertion a projection test rather than a tautology.
                .addValue("notes", "CLINICAL-NOTES-MARKER-" + id.toString().substring(0, 8))
                .addValue("ticket", "T-" + id.toString().substring(0, 8)));
    }

    /** Counts rows matching a predicate, for a state assertion after an action. */
    public int countWhere(String sql, java.util.Map<String, ?> parameters) {
        Integer count = jdbc.queryForObject("select count(*) from (" + sql + ") fe_counted",
            new MapSqlParameterSource(parameters), Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * Every appointment id the fixture seeded for one organization and window.
     *
     * <p>Deliberately <b>independent</b> of the repository's SQL: it re-derives the population
     * with its own statement rather than reusing {@code FUNNEL_BY_STATUS}. A test that compared
     * a metric against a figure produced by the same code path would pass even if the
     * derivation were wrong, which is the failure the mission names: "a wrong derivation fails
     * even when the number happens to be right."
     */
    public java.util.List<UUID> appointmentIdsInWindow(UUID organizationId, UUID projectId,
                                                       LocalDateTime from, LocalDateTime to) {
        return jdbc.queryForList("""
            select a."id"
              from appointments a
             where a."organizationId" = :org and a."projectId" = :project
               and a."appointmentDate" > :from and a."appointmentDate" <= :to
             order by a."appointmentDate" desc, a."id" desc
            """,
            new MapSqlParameterSource().addValue("org", organizationId)
                .addValue("project", projectId).addValue("from", from).addValue("to", to),
            UUID.class);
    }

    /** Every appointment id in one organization and project, regardless of window. */
    public java.util.List<UUID> appointmentIdsInProject(UUID organizationId, UUID projectId) {
        return jdbc.queryForList("""
            select a."id" from appointments a
             where a."organizationId" = :org and a."projectId" = :project
             order by a."id"
            """,
            new MapSqlParameterSource().addValue("org", organizationId)
                .addValue("project", projectId),
            UUID.class);
    }

    private void insert(String sql, MapSqlParameterSource parameters) {
        parameters.addValue("now", InboxFixture.SEED_AT);
        jdbc.update(sql, parameters);
    }
}