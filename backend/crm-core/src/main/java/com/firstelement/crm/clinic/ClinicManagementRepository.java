package com.firstelement.crm.clinic;

import com.firstelement.crm.clinic.ClinicLocationModels.ClinicLocation;
import com.firstelement.crm.clinic.ClinicLocationModels.ClinicLocationInput;
import com.firstelement.crm.clinic.ClinicLocationModels.ProcedureInput;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The write side of the clinic master data.
 *
 * <h2>Why this is separate from {@link ClinicRepository}</h2>
 *
 * <p>{@code ClinicRepository} is the read model, and its class comment makes a
 * promise that matters: there is no mutation in it, so a future caller cannot
 * acquire a clinic write by importing the read module. This repository is the
 * mutation it deliberately did not have. Both use the same {@link ClinicScope}, so
 * a write cannot be scoped more loosely than a read.
 *
 * <h2>Every statement carries the scope</h2>
 *
 * <p>The insert methods bind {@code organizationId} and {@code projectId} from the
 * scope, never from the input record. The update methods put the scope predicate in
 * the same {@code WHERE} as the id, so a foreign row is a no-op rather than a
 * read-then-write race. The service above this class still re-reads the row through
 * the scoped read model before returning it, so a caller cannot receive a row it
 * did not write.
 */
@Repository
public class ClinicManagementRepository {

    /** The list is small, but an unbounded catalogue is still an unbounded response. */
    public static final int LOCATION_PAGE = 200;

    private static final String SELECT_LOCATIONS = """
        select l."id", l."organizationId", l."projectId", l."name",
               l."frontDeskOfficerId", u."fullName" as "frontDeskOfficerName",
               l."contactNumber", l."description", l."addressLine", l."country",
               l."state", l."city", l."postalCode", l."slotMinutes",
               l."brandImageUrl", l."isActive", l."createdAt", l."updatedAt"
          from "clinic_locations" l
          left join "users" u
                 on u."id" = l."frontDeskOfficerId"
                and u."organizationId" = l."organizationId"
         where %s
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public ClinicManagementRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // locations
    // ------------------------------------------------------------------

    /**
     * Locations in scope, optionally filtered by a name/contact term and status.
     *
     * <p>Both filters are applied by the database rather than by the browser: a
     * client-side filter over one page reports "no results" for a clinic that is
     * on the next page. The term is matched against name, contact number, city and
     * the assigned officer's name.
     */
    public List<ClinicLocation> locations(ClinicScope scope, String q, String status, int limit) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("q", blankToNull(q));
        parameters.put("status", blankToNull(status));
        parameters.put("limit", limit);
        return jdbc.query(
                SELECT_LOCATIONS.formatted(scope.predicate("l")) + """
                   and (cast(:q as text) is null
                        or lower(l."name") like lower('%' || cast(:q as text) || '%')
                        or lower(coalesce(l."contactNumber", '')) like lower('%' || cast(:q as text) || '%')
                        or lower(coalesce(l."city", '')) like lower('%' || cast(:q as text) || '%')
                        or lower(coalesce(u."fullName", '')) like lower('%' || cast(:q as text) || '%'))
                   and (cast(:status as text) is null
                        or l."isActive" = (cast(:status as text) = 'ACTIVE'))
                 order by l."name" asc, l."id" asc
                 limit :limit
                """,
                scope.bind(parameters),
                (rs, rowNum) -> readLocation(rs));
    }

    /** One location, or empty when it does not exist or is outside the scope. */
    public Optional<ClinicLocation> location(ClinicScope scope, UUID locationId) {
        return jdbc.query(
                SELECT_LOCATIONS.formatted(scope.predicate("l"))
                    + " and l.\"id\" = :locationId limit 1",
                scope.bind(Map.of("locationId", locationId)),
                (rs, rowNum) -> readLocation(rs)).stream().findFirst();
    }

    /** Creates a location inside exactly one project. */
    public UUID insertLocation(ClinicScope scope, ClinicLocationInput input, int slotMinutes,
                               boolean isActive) {
        if (!scope.isProjectScoped()) {
            throw new IllegalStateException(
                "A clinic location must be created inside one project; name the project explicitly.");
        }
        UUID id = UUID.randomUUID();
        scope.read(() -> jdbc.update("""
                insert into "clinic_locations"
                    ("id", "organizationId", "projectId", "name", "frontDeskOfficerId",
                     "contactNumber", "description", "addressLine", "country", "state",
                     "city", "postalCode", "slotMinutes", "brandImageUrl", "isActive",
                     "createdAt", "updatedAt")
                values
                    (:id, :org, :proj, :name, :frontDeskOfficerId,
                     :contactNumber, :description, :addressLine, :country, :state,
                     :city, :postalCode, :slotMinutes, :brandImageUrl, :isActive,
                     (now() at time zone 'utc'), (now() at time zone 'utc'))
                """, scope.bind()
                .addValue("id", id)
                .addValue("org", scope.queryScope().organizationId())
                .addValue("proj", scope.projectId())
                .addValue("name", input.name())
                .addValue("frontDeskOfficerId", input.frontDeskOfficerId())
                .addValue("contactNumber", input.contactNumber())
                .addValue("description", input.description())
                .addValue("addressLine", input.addressLine())
                .addValue("country", input.country())
                .addValue("state", input.state())
                .addValue("city", input.city())
                .addValue("postalCode", input.postalCode())
                .addValue("slotMinutes", slotMinutes)
                .addValue("brandImageUrl", input.brandImageUrl())
                .addValue("isActive", isActive)));
        return id;
    }

    /** Seeds initial treatment rooms when a new clinic location (branch) is created. */
    public void seedTreatmentRoomsForLocation(ClinicScope scope, UUID clinicId, int roomsCount) {
        if (!scope.isProjectScoped() || clinicId == null) {
            return;
        }
        int safeCount = Math.min(Math.max(roomsCount, 1), 50);
        scope.read(() -> {
            Integer existing = jdbc.queryForObject("""
                    select count(*) from "treatment_rooms" tr
                     where tr."organizationId" = :org and tr."projectId" = :proj and tr."clinicId" = :clinicId
                    """,
                    scope.bind()
                            .addValue("org", scope.queryScope().organizationId())
                            .addValue("proj", scope.projectId())
                            .addValue("clinicId", clinicId),
                    Integer.class);
            if (existing != null && existing > 0) {
                return 0;
            }
            for (int i = 1; i <= safeCount; i++) {
                jdbc.update("""
                        insert into "treatment_rooms"
                            ("id", "organizationId", "projectId", "clinicId", "name", "roomNumber",
                             "roomType", "operationalStartHour", "operationalEndHour", "isActive")
                        values
                            (:id, :org, :proj, :clinicId, :name, :roomNumber,
                             'GENERAL_OPERATORY', '09:00', '17:00', true)
                        """,
                        scope.bind()
                                .addValue("id", UUID.randomUUID())
                                .addValue("org", scope.queryScope().organizationId())
                                .addValue("proj", scope.projectId())
                                .addValue("clinicId", clinicId)
                                .addValue("name", "Treatment Room " + i)
                                .addValue("roomNumber", "R-" + i));
            }
            return safeCount;
        });
    }

    /** Updates a location in scope. A foreign id changes no rows. */
    public boolean updateLocation(ClinicScope scope, UUID locationId, ClinicLocationInput input,
                                  int slotMinutes, boolean isActive) {
        return scope.read(() -> jdbc.update(
                """
                update "clinic_locations" l
                   set "name" = :name,
                       "frontDeskOfficerId" = :frontDeskOfficerId,
                       "contactNumber" = :contactNumber,
                       "description" = :description,
                       "addressLine" = :addressLine,
                       "country" = :country,
                       "state" = :state,
                       "city" = :city,
                       "postalCode" = :postalCode,
                       "slotMinutes" = :slotMinutes,
                       "brandImageUrl" = :brandImageUrl,
                       "isActive" = :isActive,
                       "updatedAt" = (now() at time zone 'utc')
                 where %s
                   and l."id" = :locationId
                """.formatted(scope.predicate("l")),
                scope.bind()
                    .addValue("name", input.name())
                    .addValue("frontDeskOfficerId", input.frontDeskOfficerId())
                    .addValue("contactNumber", input.contactNumber())
                    .addValue("description", input.description())
                    .addValue("addressLine", input.addressLine())
                    .addValue("country", input.country())
                    .addValue("state", input.state())
                    .addValue("city", input.city())
                    .addValue("postalCode", input.postalCode())
                    .addValue("slotMinutes", slotMinutes)
                    .addValue("brandImageUrl", input.brandImageUrl())
                    .addValue("isActive", isActive)
                    .addValue("locationId", locationId)) > 0);
    }

    /** Unlinks related records and deletes a clinic location in scope. */
    public boolean deleteLocation(ClinicScope scope, UUID locationId) {
        return scope.read(() -> {
            MapSqlParameterSource params = scope.bind()
                .addValue("locationId", locationId)
                .addValue("org", scope.queryScope().organizationId());

            jdbc.update("""
                update "doctors"
                   set "clinicId" = null
                 where "clinicId" = :locationId
                   and "organizationId" = :org
                """, params);

            jdbc.update("""
                update "appointments"
                   set "clinicId" = null
                 where "clinicId" = :locationId
                   and "organizationId" = :org
                """, params);

            jdbc.update("""
                update "procedures"
                   set "clinicId" = null
                 where "clinicId" = :locationId
                   and "organizationId" = :org
                """, params);

            return jdbc.update("""
                delete from "clinic_locations" l
                 where %s
                   and l."id" = :locationId
                """.formatted(scope.predicate("l")), params) > 0;
        });
    }

    // ------------------------------------------------------------------
    // service catalogue
    // ------------------------------------------------------------------

    /** Creates a procedure in the scoped project. */
    public UUID insertProcedure(ClinicScope scope, ProcedureInput input, int durationMinutes,
                                int bufferMinutes, boolean isActive, boolean advancedPayment,
                                String type) {
        if (!scope.isProjectScoped()) {
            throw new IllegalStateException(
                "A service must be created inside one project; name the project explicitly.");
        }
        UUID id = UUID.randomUUID();
        scope.read(() -> jdbc.update("""
                insert into "procedures"
                    ("id", "organizationId", "projectId", "name", "specialty",
                     "durationMinutes", "bufferMinutes", "price", "isActive",
                     "createdAt", "updatedAt", "productCode", "subCategory", "clinicId",
                     "timeSlotMinutes", "type", "description", "discount", "imageUrl",
                     "systemService", "advancedPayment")
                values
                    (:id, :org, :proj, :name, :specialty,
                     :durationMinutes, :bufferMinutes, :price, :isActive,
                     (now() at time zone 'utc'), (now() at time zone 'utc'),
                     :productCode, :subCategory, :clinicId,
                     :timeSlotMinutes, :type, :description, :discount, :imageUrl,
                     :systemService, :advancedPayment)
                """, scope.bind()
                .addValue("id", id)
                .addValue("org", scope.queryScope().organizationId())
                .addValue("proj", scope.projectId())
                .addValue("name", input.name())
                .addValue("specialty", input.specialty())
                .addValue("durationMinutes", durationMinutes)
                .addValue("bufferMinutes", bufferMinutes)
                .addValue("price", input.price())
                .addValue("isActive", isActive)
                .addValue("productCode", blankToNull(input.productCode()))
                .addValue("subCategory", blankToNull(input.subCategory()))
                .addValue("clinicId", input.clinicId())
                .addValue("timeSlotMinutes", input.timeSlotMinutes())
                .addValue("type", type)
                .addValue("description", blankToNull(input.description()))
                .addValue("discount", input.discount())
                .addValue("imageUrl", blankToNull(input.imageUrl()))
                .addValue("systemService", blankToNull(input.systemService()))
                .addValue("advancedPayment", advancedPayment)));
        return id;
    }

    /** Updates a procedure in scope. A foreign id changes no rows. */
    public boolean updateProcedure(ClinicScope scope, UUID procedureId, ProcedureInput input,
                                   int durationMinutes, int bufferMinutes, boolean isActive,
                                   boolean advancedPayment, String type) {
        return scope.read(() -> jdbc.update(
                """
                update "procedures" p
                   set "name" = :name,
                       "specialty" = :specialty,
                       "durationMinutes" = :durationMinutes,
                       "bufferMinutes" = :bufferMinutes,
                       "price" = :price,
                       "isActive" = :isActive,
                       "productCode" = :productCode,
                       "subCategory" = :subCategory,
                       "clinicId" = :clinicId,
                       "timeSlotMinutes" = :timeSlotMinutes,
                       "type" = :type,
                       "description" = :description,
                       "discount" = :discount,
                       "imageUrl" = :imageUrl,
                       "systemService" = :systemService,
                       "advancedPayment" = :advancedPayment,
                       "updatedAt" = (now() at time zone 'utc')
                 where %s
                   and p."id" = :procedureId
                """.formatted(scope.predicate("p")),
                scope.bind()
                    .addValue("name", input.name())
                    .addValue("specialty", input.specialty())
                    .addValue("durationMinutes", durationMinutes)
                    .addValue("bufferMinutes", bufferMinutes)
                    .addValue("price", input.price())
                    .addValue("isActive", isActive)
                    .addValue("productCode", blankToNull(input.productCode()))
                    .addValue("subCategory", blankToNull(input.subCategory()))
                    .addValue("clinicId", input.clinicId())
                    .addValue("timeSlotMinutes", input.timeSlotMinutes())
                    .addValue("type", type)
                    .addValue("description", blankToNull(input.description()))
                    .addValue("discount", input.discount())
                    .addValue("imageUrl", blankToNull(input.imageUrl()))
                    .addValue("systemService", blankToNull(input.systemService()))
                    .addValue("advancedPayment", advancedPayment)
                    .addValue("procedureId", procedureId)) > 0);
    }

    // ------------------------------------------------------------------
    // referential checks that need the caller's scope
    // ------------------------------------------------------------------

    /** Whether a clinic belongs to the caller's scope. Used before pointing a service at it. */
    public boolean locationInScope(ClinicScope scope, UUID locationId) {
        return scope.read(() -> jdbc.queryForObject(
                "select count(*) from \"clinic_locations\" l where "
                    + scope.predicate("l") + " and l.\"id\" = :locationId",
                scope.bind(Map.of("locationId", locationId)),
                Integer.class)) > 0;
    }

    /** Whether a front-desk officer belongs to the caller's organization. */
    public boolean officerInScope(ClinicScope scope, UUID userId) {
        return scope.read(() -> jdbc.queryForObject(
                """
                select count(*) from "users" u
                 where u."organizationId" = :org
                   and u."id" = :userId
                   and u."isActive" = true
                """,
                scope.bind(Map.of("userId", userId)),
                Integer.class)) > 0;
    }

    private static ClinicLocation readLocation(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        return new ClinicLocation(
            rs.getObject("id", UUID.class),
            rs.getObject("organizationId", UUID.class),
            rs.getObject("projectId", UUID.class),
            rs.getString("name"),
            rs.getObject("frontDeskOfficerId", UUID.class),
            rs.getString("frontDeskOfficerName"),
            rs.getString("contactNumber"),
            rs.getString("description"),
            rs.getString("addressLine"),
            rs.getString("country"),
            rs.getString("state"),
            rs.getString("city"),
            rs.getString("postalCode"),
            rs.getInt("slotMinutes"),
            rs.getString("brandImageUrl"),
            rs.getBoolean("isActive"),
            rs.getObject("createdAt", LocalDateTime.class),
            rs.getObject("updatedAt", LocalDateTime.class));
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
