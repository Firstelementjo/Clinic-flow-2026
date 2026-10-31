package com.firstelement.crm.clinic;

import com.firstelement.crm.clinic.ClinicDoctorModels.DoctorProfile;
import com.firstelement.crm.clinic.ClinicDoctorModels.DoctorProfileInput;
import com.firstelement.crm.clinic.ClinicDoctorModels.Qualification;
import com.firstelement.crm.clinic.ClinicDoctorModels.QualificationInput;
import com.firstelement.crm.clinic.FrontDeskModels.FrontDeskOfficer;
import com.firstelement.crm.clinic.FrontDeskModels.FrontDeskOfficerInput;
import com.firstelement.crm.clinic.rbac.ClinicPermissionCatalog;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Doctor-profile and front-desk persistence.
 *
 * <h2>Two aliases, two scopes</h2>
 *
 * <p>Doctors carry {@code organizationId} and {@code projectId} themselves, so
 * their statements use {@code scope.predicate("d")}. A user does not carry a
 * project, so every front-desk statement scopes the
 * {@code project_memberships} alias instead. Using the user alias there would
 * reference a column that does not exist; using only the organization would
 * hand a revoked membership every colleague in the tenant.
 *
 * <h2>Why the front-desk assignment clears first</h2>
 *
 * <p>{@code clinic_locations.frontDeskOfficerId} is one column, not a join
 * table. Reassigning an officer has to null the previous location before it
 * sets the new one, otherwise the list's "Clinic" column would be ambiguous.
 * Both statements are in the same transaction.
 *
 * <h2>Why there is no password projection</h2>
 *
 * <p>No SELECT in this file names {@code users."passwordHash"}. The create and
 * edit paths write a hash, but no read path can return one.
 */
@Repository
public class ClinicStaffRepository {

    /** Small team lists, but bounded like every other catalogue read. */
    public static final int STAFF_PAGE = 200;

    private static final String SELECT_DOCTORS = """
        select d."id", d."organizationId", d."projectId",
               coalesce(nullif(d."firstName", ''), split_part(d."name", ' ', 1)) as "firstName",
               coalesce(nullif(d."lastName", ''),
                        nullif(trim(substr(d."name", length(split_part(d."name", ' ', 1)) + 1)), '')
               ) as "lastName",
               d."name", d."email", d."phone", d."gender", d."imageUrl", d."aboutSelf",
               d."expert", d."specialty", d."commission", d."clinicId", cl."name" as "clinicName",
               d."serviceId", p."name" as "serviceName", d."experienceYears", d."country",
               d."state", d."city", d."postalCode", d."latitude", d."longitude",
               d."facebookUrl", d."instagramUrl", d."twitterUrl", d."dribbbleUrl",
               d."signatureUrl", d."verificationStatus", d."isActive", d."createdAt", d."updatedAt"
          from "doctors" d
          left join "clinic_locations" cl
                 on cl."id" = d."clinicId" and cl."organizationId" = d."organizationId"
          left join "procedures" p
                 on p."id" = d."serviceId" and p."organizationId" = d."organizationId"
         where %s
           and d."deletedAt" is null
        """;

    private static final String SELECT_FRONT_DESK = """
        select u."id" as "userId", u."organizationId", m."projectId", u."email",
               u."fullName",
               coalesce(nullif(u."firstName", ''), split_part(u."fullName", ' ', 1)) as "firstName",
               coalesce(nullif(u."lastName", ''),
                        nullif(trim(substr(u."fullName", length(split_part(u."fullName", ' ', 1)) + 1)), '')
               ) as "lastName",
               u."phone", u."gender", u."dateOfBirth", u."profileImageUrl", u."addressLine",
               u."country", u."state", u."city", u."postalCode", u."verificationStatus",
               u."isActive", u."createdAt", u."updatedAt",
               l."id" as "clinicId", l."name" as "clinicName",
               coalesce(rb."primaryRole",
                   case when u."role" = 'EMPLOYEE' then 'doctor' else 'receptionist' end
               ) as "staffRole",
               coalesce(rb."isAccountant", false) as "isAccountant"
          from "project_memberships" m
          join "users" u
            on u."id" = m."userId" and u."organizationId" = m."organizationId"
          left join lateral (
              select cl."id", cl."name"
                from "clinic_locations" cl
               where cl."frontDeskOfficerId" = u."id"
                 and cl."organizationId" = u."organizationId"
               order by cl."name" asc, cl."id" asc
               limit 1
          ) l on true
          left join lateral (
              select
                  coalesce(
                      max(case when r."roleKey" = 'doctor' then 'doctor'
                               when r."roleKey" = 'receptionist' then 'receptionist'
                               else null end),
                      max(case when r."roleKey" = 'accountant' then 'accountant' else null end)
                  ) as "primaryRole",
                  bool_or(r."roleKey" = 'accountant') as "isAccountant"
                from "clinic_user_roles" ur
                join "clinic_roles" r on r."id" = ur."roleId"
               where ur."userId" = u."id"
                 and ur."organizationId" = u."organizationId"
          ) rb on true
         where %s
           and u."role" in ('CLINIC_STAFF', 'EMPLOYEE')
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public ClinicStaffRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // doctors
    // ------------------------------------------------------------------

    public List<DoctorProfile> doctors(ClinicScope scope, String q, String status, int limit) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("q", blankToNull(q));
        parameters.put("status", blankToNull(status));
        parameters.put("limit", limit);
        return jdbc.query(
            SELECT_DOCTORS.formatted(scope.predicate("d")) + """
                   and (cast(:q as text) is null
                        or lower(d."name") like lower('%' || cast(:q as text) || '%')
                        or lower(coalesce(d."email", '')) like lower('%' || cast(:q as text) || '%')
                        or lower(coalesce(d."phone", '')) like lower('%' || cast(:q as text) || '%')
                        or lower(coalesce(d."specialty", '')) like lower('%' || cast(:q as text) || '%'))
                   and (cast(:status as text) is null
                        or d."isActive" = (cast(:status as text) = 'ACTIVE'))
                 order by d."name" asc, d."id" asc
                 limit :limit
                """,
            scope.bind(parameters),
            (rs, rowNum) -> readDoctor(rs));
    }

    public Optional<DoctorProfile> doctor(ClinicScope scope, UUID doctorId) {
        return jdbc.query(
            SELECT_DOCTORS.formatted(scope.predicate("d"))
                + " and d.\"id\" = :doctorId limit 1",
            scope.bind(Map.of("doctorId", doctorId)),
            (rs, rowNum) -> readDoctor(rs)).stream().findFirst();
    }

    public List<Qualification> qualifications(ClinicScope scope, UUID doctorId) {
        return jdbc.query("""
                select "id", "degree", "university", "graduationYear"
                  from "doctor_qualifications"
                 where "organizationId" = :org and "doctorId" = :doctorId
                 order by "sortOrder" asc, "createdAt" asc, "id" asc
                """,
            new MapSqlParameterSource()
                .addValue("org", scope.queryScope().organizationId())
                .addValue("doctorId", doctorId),
            (rs, rowNum) -> new Qualification(
                rs.getObject("id", UUID.class),
                rs.getString("degree"),
                rs.getString("university"),
                rs.getObject("graduationYear", Integer.class)));
    }

    public UUID insertDoctor(ClinicScope scope, DoctorProfileInput input, String name,
                             String specialty, boolean isActive, String verificationStatus) {
        requireProject(scope, "A doctor must be created inside one project; name the project explicitly.");
        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                insert into "doctors"
                    ("id", "organizationId", "projectId", "name", "specialty", "email", "phone",
                     "firstName", "lastName", "imageUrl", "gender", "aboutSelf", "expert",
                     "commission", "clinicId", "serviceId", "experienceYears", "country", "state",
                     "city", "postalCode", "latitude", "longitude", "facebookUrl", "instagramUrl",
                     "twitterUrl", "dribbbleUrl", "signatureUrl", "verificationStatus",
                     "isActive", "createdAt", "updatedAt")
                values
                    (:id, :org, :project, :name, :specialty, :email, :phone,
                     :firstName, :lastName, :imageUrl, :gender, :aboutSelf, :expert,
                     :commission, :clinicId, :serviceId, :experienceYears, :country, :state,
                     :city, :postalCode, :latitude, :longitude, :facebookUrl, :instagramUrl,
                     :twitterUrl, :dribbbleUrl, :signatureUrl, :verificationStatus,
                     :isActive, :now, :now)
                """,
            scope.bind(values(
                "id", id,
                "org", scope.queryScope().organizationId(),
                "project", scope.projectId(),
                "name", name,
                "specialty", specialty,
                "email", nullable(input.email()),
                "phone", nullable(input.phone()),
                "firstName", nullable(input.firstName()),
                "lastName", nullable(input.lastName()),
                "imageUrl", nullable(input.imageUrl()),
                "gender", nullable(input.gender()),
                "aboutSelf", nullable(input.aboutSelf()),
                "expert", nullable(input.expert()),
                "commission", input.commission(),
                "clinicId", input.clinicId(),
                "serviceId", input.serviceId(),
                "experienceYears", input.experienceYears(),
                "country", nullable(input.country()),
                "state", nullable(input.state()),
                "city", nullable(input.city()),
                "postalCode", nullable(input.postalCode()),
                "latitude", input.latitude(),
                "longitude", input.longitude(),
                "facebookUrl", nullable(input.facebookUrl()),
                "instagramUrl", nullable(input.instagramUrl()),
                "twitterUrl", nullable(input.twitterUrl()),
                "dribbbleUrl", nullable(input.dribbbleUrl()),
                "signatureUrl", nullable(input.signatureUrl()),
                "verificationStatus", verificationStatus,
                "isActive", isActive,
                "now", now)));
        return id;
    }

    public boolean updateDoctor(ClinicScope scope, UUID doctorId, DoctorProfileInput input,
                                String name, String specialty, boolean isActive,
                                String verificationStatus) {
        int rows = jdbc.update("""
                update "doctors" d
                   set "name" = :name,
                       "specialty" = :specialty,
                       "email" = :email,
                       "phone" = :phone,
                       "firstName" = :firstName,
                       "lastName" = :lastName,
                       "imageUrl" = :imageUrl,
                       "gender" = :gender,
                       "aboutSelf" = :aboutSelf,
                       "expert" = :expert,
                       "commission" = :commission,
                       "clinicId" = :clinicId,
                       "serviceId" = :serviceId,
                       "experienceYears" = :experienceYears,
                       "country" = :country,
                       "state" = :state,
                       "city" = :city,
                       "postalCode" = :postalCode,
                       "latitude" = :latitude,
                       "longitude" = :longitude,
                       "facebookUrl" = :facebookUrl,
                       "instagramUrl" = :instagramUrl,
                       "twitterUrl" = :twitterUrl,
                       "dribbbleUrl" = :dribbbleUrl,
                       "signatureUrl" = :signatureUrl,
                       "verificationStatus" = :verificationStatus,
                       "isActive" = :isActive,
                       "updatedAt" = :now
                 where %s
                   and d."id" = :doctorId
                   and d."deletedAt" is null
                """.formatted(scope.predicate("d")),
            scope.bind(values(
                "doctorId", doctorId,
                "name", name,
                "specialty", specialty,
                "email", nullable(input.email()),
                "phone", nullable(input.phone()),
                "firstName", nullable(input.firstName()),
                "lastName", nullable(input.lastName()),
                "imageUrl", nullable(input.imageUrl()),
                "gender", nullable(input.gender()),
                "aboutSelf", nullable(input.aboutSelf()),
                "expert", nullable(input.expert()),
                "commission", input.commission(),
                "clinicId", input.clinicId(),
                "serviceId", input.serviceId(),
                "experienceYears", input.experienceYears(),
                "country", nullable(input.country()),
                "state", nullable(input.state()),
                "city", nullable(input.city()),
                "postalCode", nullable(input.postalCode()),
                "latitude", input.latitude(),
                "longitude", input.longitude(),
                "facebookUrl", nullable(input.facebookUrl()),
                "instagramUrl", nullable(input.instagramUrl()),
                "twitterUrl", nullable(input.twitterUrl()),
                "dribbbleUrl", nullable(input.dribbbleUrl()),
                "signatureUrl", nullable(input.signatureUrl()),
                "verificationStatus", verificationStatus,
                "isActive", isActive,
                "now", LocalDateTime.now())));
        return rows > 0;
    }

    public boolean softDeleteDoctor(ClinicScope scope, UUID doctorId) {
        int rows = jdbc.update("""
                update "doctors" d
                   set "deletedAt" = :now,
                       "isActive" = false,
                       "updatedAt" = :now
                 where %s
                   and d."id" = :doctorId
                   and d."deletedAt" is null
                """.formatted(scope.predicate("d")),
            scope.bind(Map.of("doctorId", doctorId, "now", LocalDateTime.now())));
        return rows > 0;
    }

    public void replaceQualifications(ClinicScope scope, UUID doctorId,
                                      List<QualificationInput> qualifications) {
        UUID org = scope.queryScope().organizationId();
        jdbc.update("""
                delete from "doctor_qualifications"
                 where "organizationId" = :org and "doctorId" = :doctorId
                """,
            new MapSqlParameterSource().addValue("org", org).addValue("doctorId", doctorId));
        int sortOrder = 0;
        for (QualificationInput qualification : qualifications) {
            jdbc.update("""
                    insert into "doctor_qualifications"
                        ("id", "organizationId", "doctorId", "degree", "university",
                         "graduationYear", "sortOrder", "createdAt")
                    values
                        (:id, :org, :doctorId, :degree, :university,
                         :graduationYear, :sortOrder, :now)
                    """,
                new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID())
                    .addValue("org", org)
                    .addValue("doctorId", doctorId)
                    .addValue("degree", qualification.degree())
                    .addValue("university", nullable(qualification.university()))
                    .addValue("graduationYear", qualification.graduationYear())
                    .addValue("sortOrder", sortOrder++)
                    .addValue("now", LocalDateTime.now()));
        }
    }

    public boolean clinicInScope(ClinicScope scope, UUID clinicId) {
        Integer count = jdbc.queryForObject(
            "select count(*) from \"clinic_locations\" l where "
                + scope.predicate("l") + " and l.\"id\" = :clinicId",
            scope.bind(Map.of("clinicId", clinicId)), Integer.class);
        return count != null && count > 0;
    }

    public boolean serviceInScope(ClinicScope scope, UUID serviceId) {
        Integer count = jdbc.queryForObject(
            "select count(*) from \"procedures\" p where "
                + scope.predicate("p") + " and p.\"id\" = :serviceId",
            scope.bind(Map.of("serviceId", serviceId)), Integer.class);
        return count != null && count > 0;
    }

    // ------------------------------------------------------------------
    // front desk
    // ------------------------------------------------------------------

    public List<FrontDeskOfficer> frontDeskOfficers(ClinicScope scope, String q,
                                                    String status, int limit) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("q", blankToNull(q));
        parameters.put("status", blankToNull(status));
        parameters.put("limit", limit);
        return jdbc.query(
            SELECT_FRONT_DESK.formatted(scope.predicate("m")) + """
                   and (cast(:q as text) is null
                        or lower(u."fullName") like lower('%' || cast(:q as text) || '%')
                        or lower(u."email") like lower('%' || cast(:q as text) || '%')
                        or lower(coalesce(u."phone", '')) like lower('%' || cast(:q as text) || '%')
                        or lower(coalesce(l."name", '')) like lower('%' || cast(:q as text) || '%'))
                   and (cast(:status as text) is null
                        or u."isActive" = (cast(:status as text) = 'ACTIVE'))
                 order by u."fullName" asc, u."id" asc
                 limit :limit
                """,
            scope.bind(parameters),
            (rs, rowNum) -> readFrontDesk(rs));
    }

    public Optional<FrontDeskOfficer> frontDeskOfficer(ClinicScope scope, UUID userId) {
        return jdbc.query(
            SELECT_FRONT_DESK.formatted(scope.predicate("m"))
                + " and u.\"id\" = :userId limit 1",
            scope.bind(Map.of("userId", userId)),
            (rs, rowNum) -> readFrontDesk(rs)).stream().findFirst();
    }

    public boolean emailTaken(String email, UUID excludeUserId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from "users" u
                 where u."email" = :email
                   and (cast(:exclude as uuid) is null or u."id" <> cast(:exclude as uuid))
                """,
            new MapSqlParameterSource().addValue("email", email)
                .addValue("exclude", excludeUserId),
            Integer.class);
        return count != null && count > 0;
    }

    public UUID insertFrontDesk(ClinicScope scope, FrontDeskOfficerInput input, String fullName,
                                String email, String passwordHash, boolean isActive,
                                String verificationStatus) {
        requireProject(scope, "A front-desk officer must be created inside one project; name the project explicitly.");
        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        String staffRole = input.role() == null ? "receptionist" : input.role().toLowerCase();
        String dbUserRole = "doctor".equals(staffRole) ? "EMPLOYEE" : "CLINIC_STAFF";
        boolean isAccountant = Boolean.TRUE.equals(input.isAccountant()) || "accountant".equals(staffRole);
        jdbc.update("""
                insert into "users"
                    ("id", "organizationId", "email", "passwordHash", "fullName", "role",
                     "locale", "isActive", "createdAt", "updatedAt", "firstName", "lastName",
                     "phone", "gender", "dateOfBirth", "profileImageUrl", "addressLine",
                     "country", "state", "city", "postalCode", "verificationStatus")
                values
                    (:id, :org, :email, :passwordHash, :fullName,
                     cast(:dbUserRole as "UserRole"), cast('ar' as "Locale"),
                     :isActive, :now, :now, :firstName, :lastName,
                     :phone, :gender, :dateOfBirth, :profileImageUrl, :addressLine,
                     :country, :state, :city, :postalCode, :verificationStatus)
                """,
            scope.bind(values(
                "id", id,
                "dbUserRole", dbUserRole,
                "org", scope.queryScope().organizationId(),
                "email", email,
                "passwordHash", passwordHash,
                "fullName", fullName,
                "isActive", isActive,
                "now", now,
                "firstName", nullable(input.firstName()),
                "lastName", nullable(input.lastName()),
                "phone", nullable(input.phone()),
                "gender", nullable(input.gender()),
                "dateOfBirth", input.dateOfBirth(),
                "profileImageUrl", nullable(input.profileImageUrl()),
                "addressLine", nullable(input.addressLine()),
                "country", nullable(input.country()),
                "state", nullable(input.state()),
                "city", nullable(input.city()),
                "postalCode", nullable(input.postalCode()),
                "verificationStatus", verificationStatus)));
        addMembership(scope, id);
        syncUserClinicRoles(scope, id, input.clinicId(), staffRole, isAccountant);
        return id;
    }

    public boolean updateFrontDesk(ClinicScope scope, UUID userId, FrontDeskOfficerInput input,
                                   String fullName, String email, String passwordHash,
                                   boolean isActive, String verificationStatus) {
        String staffRole = input.role() == null ? "receptionist" : input.role().toLowerCase();
        String dbUserRole = "doctor".equals(staffRole) ? "EMPLOYEE" : "CLINIC_STAFF";
        boolean isAccountant = Boolean.TRUE.equals(input.isAccountant()) || "accountant".equals(staffRole);
        int rows = jdbc.update("""
                update "users" u
                   set "email" = :email,
                       "passwordHash" = coalesce(:passwordHash, u."passwordHash"),
                       "fullName" = :fullName,
                       "role" = cast(:dbUserRole as "UserRole"),
                       "isActive" = :isActive,
                       "updatedAt" = :now,
                       "firstName" = :firstName,
                       "lastName" = :lastName,
                       "phone" = :phone,
                       "gender" = :gender,
                       "dateOfBirth" = :dateOfBirth,
                       "profileImageUrl" = :profileImageUrl,
                       "addressLine" = :addressLine,
                       "country" = :country,
                       "state" = :state,
                       "city" = :city,
                       "postalCode" = :postalCode,
                       "verificationStatus" = :verificationStatus
                 where u."organizationId" = :org
                   and u."id" = :userId
                   and u."role" in ('CLINIC_STAFF', 'EMPLOYEE')
                """,
            scope.bind(values(
                "userId", userId,
                "dbUserRole", dbUserRole,
                "org", scope.queryScope().organizationId(),
                "email", email,
                "passwordHash", passwordHash,
                "fullName", fullName,
                "isActive", isActive,
                "now", LocalDateTime.now(),
                "firstName", nullable(input.firstName()),
                "lastName", nullable(input.lastName()),
                "phone", nullable(input.phone()),
                "gender", nullable(input.gender()),
                "dateOfBirth", input.dateOfBirth(),
                "profileImageUrl", nullable(input.profileImageUrl()),
                "addressLine", nullable(input.addressLine()),
                "country", nullable(input.country()),
                "state", nullable(input.state()),
                "city", nullable(input.city()),
                "postalCode", nullable(input.postalCode()),
                "verificationStatus", verificationStatus)));
        if (rows > 0) {
            syncUserClinicRoles(scope, userId, input.clinicId(), staffRole, isAccountant);
        }
        return rows > 0;
    }

    public void syncUserClinicRoles(ClinicScope scope, UUID userId, UUID clinicId,
                                    String primaryRole, boolean isAccountant) {
        UUID orgId = scope.queryScope().organizationId();
        UUID roleIdPrimary = ensurePresetClinicRole(orgId, primaryRole);
        jdbc.update("""
                delete from "clinic_user_roles"
                 where "organizationId" = :org and "userId" = :userId
                """,
            new MapSqlParameterSource().addValue("org", orgId).addValue("userId", userId));

        if (roleIdPrimary != null) {
            jdbc.update("""
                    insert into "clinic_user_roles"
                        ("organizationId", "clinicId", "userId", "roleId", "assignedAt")
                    values (:org, :clinicId, :userId, :roleId, :now)
                    on conflict ("userId", "roleId") do nothing
                    """,
                new MapSqlParameterSource()
                    .addValue("org", orgId)
                    .addValue("clinicId", clinicId)
                    .addValue("userId", userId)
                    .addValue("roleId", roleIdPrimary)
                    .addValue("now", LocalDateTime.now()));
        }

        if (isAccountant && !"accountant".equalsIgnoreCase(primaryRole)) {
            UUID roleIdAccountant = ensurePresetClinicRole(orgId, "accountant");
            if (roleIdAccountant != null) {
                jdbc.update("""
                        insert into "clinic_user_roles"
                            ("organizationId", "clinicId", "userId", "roleId", "assignedAt")
                        values (:org, :clinicId, :userId, :roleId, :now)
                        on conflict ("userId", "roleId") do nothing
                        """,
                    new MapSqlParameterSource()
                        .addValue("org", orgId)
                        .addValue("clinicId", clinicId)
                        .addValue("userId", userId)
                        .addValue("roleId", roleIdAccountant)
                        .addValue("now", LocalDateTime.now()));
            }
        }
    }

    private UUID ensurePresetClinicRole(UUID orgId, String roleKey) {
        if (roleKey == null || roleKey.isBlank()) {
            return null;
        }
        String key = roleKey.toLowerCase();
        List<UUID> existing = jdbc.queryForList("""
                select "id" from "clinic_roles"
                 where "organizationId" = :org and "roleKey" = :roleKey
                 order by "createdAt" asc limit 1
                """,
            new MapSqlParameterSource().addValue("org", orgId).addValue("roleKey", key),
            UUID.class);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }

        UUID roleId = UUID.randomUUID();
        String displayName = switch (key) {
            case "clinic_admin" -> "Clinic Admin";
            case "doctor" -> "Doctor";
            case "accountant" -> "Accountant";
            default -> "Receptionist";
        };
        jdbc.update("""
                insert into "clinic_roles"
                    ("id", "organizationId", "clinicId", "roleKey", "displayName", "isPreset", "createdAt")
                values (:id, :org, null, :roleKey, :displayName, true, :now)
                """,
            new MapSqlParameterSource()
                .addValue("id", roleId)
                .addValue("org", orgId)
                .addValue("roleKey", key)
                .addValue("displayName", displayName)
                .addValue("now", LocalDateTime.now()));

        Set<String> perms = ClinicPermissionCatalog.defaultPermissionsForRole(key);
        for (String perm : perms) {
            jdbc.update("""
                    insert into "clinic_role_permissions" ("roleId", "permissionKey")
                    values (:roleId, :perm)
                    on conflict do nothing
                    """,
                new MapSqlParameterSource().addValue("roleId", roleId).addValue("perm", perm));
        }
        return roleId;
    }

    public void addMembership(ClinicScope scope, UUID userId) {
        requireProject(scope, "A front-desk membership needs one project.");
        jdbc.update("""
                insert into "project_memberships"
                    ("id", "organizationId", "projectId", "userId", "createdAt")
                select :id, :org, :project, :userId, :now
                 where not exists (
                    select 1 from "project_memberships" m
                     where m."organizationId" = :org
                       and m."projectId" = :project
                       and m."userId" = :userId)
                """,
            scope.bind(new HashMap<>(Map.of(
                "id", UUID.randomUUID(),
                "org", scope.queryScope().organizationId(),
                "project", scope.projectId(),
                "userId", userId,
                "now", LocalDateTime.now()))));
    }

    public int removeMembership(ClinicScope scope, UUID userId) {
        return jdbc.update("""
                delete from "project_memberships" m
                 where %s and m."userId" = :userId
                """.formatted(scope.predicate("m")),
            scope.bind(Map.of("userId", userId)));
    }

    public void assignClinic(ClinicScope scope, UUID userId, UUID clinicId) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                update "clinic_locations" l
                   set "frontDeskOfficerId" = null,
                       "updatedAt" = :now
                 where %s
                   and l."frontDeskOfficerId" = :userId
                   and l."id" <> :clinicId
                """.formatted(scope.predicate("l")),
            scope.bind(values("userId", userId, "clinicId", clinicId, "now", now)));
        jdbc.update("""
                update "clinic_locations" l
                   set "frontDeskOfficerId" = :userId,
                       "updatedAt" = :now
                 where %s and l."id" = :clinicId
                """.formatted(scope.predicate("l")),
            scope.bind(Map.of("userId", userId, "clinicId", clinicId, "now", now)));
    }

    public void clearClinicAssignments(ClinicScope scope, UUID userId) {
        jdbc.update("""
                update "clinic_locations" l
                   set "frontDeskOfficerId" = null,
                       "updatedAt" = :now
                 where %s
                   and l."frontDeskOfficerId" = :userId
                """.formatted(scope.predicate("l")),
            scope.bind(values("userId", userId, "now", LocalDateTime.now())));
    }

    public void deactivateIfNoMemberships(ClinicScope scope, UUID userId) {
        jdbc.update("""
                update "users" u
                   set "isActive" = false,
                       "updatedAt" = :now
                 where u."organizationId" = :org
                   and u."id" = :userId
                   and not exists (
                       select 1 from "project_memberships" m
                        where m."userId" = u."id"
                          and m."organizationId" = u."organizationId")
                """,
            new MapSqlParameterSource()
                .addValue("org", scope.queryScope().organizationId())
                .addValue("userId", userId)
                .addValue("now", LocalDateTime.now()));
    }

    public boolean setFrontDeskActive(ClinicScope scope, UUID userId, boolean active) {
        int rows = jdbc.update("""
                update "users" u
                   set "isActive" = :active,
                       "updatedAt" = :now
                 where u."organizationId" = :org
                   and u."id" = :userId
                   and u."role" in ('CLINIC_STAFF', 'EMPLOYEE')
                """,
            new MapSqlParameterSource()
                .addValue("org", scope.queryScope().organizationId())
                .addValue("userId", userId)
                .addValue("active", active)
                .addValue("now", LocalDateTime.now()));
        return rows > 0;
    }

    // ------------------------------------------------------------------
    // mappers and helpers
    // ------------------------------------------------------------------

    private static DoctorProfile readDoctor(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DoctorProfile(
            rs.getObject("id", UUID.class),
            rs.getObject("organizationId", UUID.class),
            rs.getObject("projectId", UUID.class),
            rs.getString("firstName"),
            rs.getString("lastName"),
            rs.getString("name"),
            rs.getString("email"),
            rs.getString("phone"),
            rs.getString("gender"),
            rs.getString("imageUrl"),
            rs.getString("aboutSelf"),
            rs.getString("expert"),
            rs.getString("specialty"),
            rs.getObject("commission", Double.class),
            rs.getObject("clinicId", UUID.class),
            rs.getString("clinicName"),
            rs.getObject("serviceId", UUID.class),
            rs.getString("serviceName"),
            rs.getObject("experienceYears", Integer.class),
            rs.getString("country"),
            rs.getString("state"),
            rs.getString("city"),
            rs.getString("postalCode"),
            rs.getObject("latitude", Double.class),
            rs.getObject("longitude", Double.class),
            rs.getString("facebookUrl"),
            rs.getString("instagramUrl"),
            rs.getString("twitterUrl"),
            rs.getString("dribbbleUrl"),
            rs.getString("signatureUrl"),
            rs.getString("verificationStatus"),
            rs.getBoolean("isActive"),
            rs.getObject("createdAt", LocalDateTime.class),
            rs.getObject("updatedAt", LocalDateTime.class),
            List.of());
    }

    private static FrontDeskOfficer readFrontDesk(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        return new FrontDeskOfficer(
            rs.getObject("userId", UUID.class),
            rs.getObject("organizationId", UUID.class),
            rs.getObject("projectId", UUID.class),
            rs.getString("email"),
            rs.getString("fullName"),
            rs.getString("firstName"),
            rs.getString("lastName"),
            rs.getString("phone"),
            rs.getString("gender"),
            rs.getObject("dateOfBirth", java.time.LocalDate.class),
            rs.getString("profileImageUrl"),
            rs.getString("addressLine"),
            rs.getString("country"),
            rs.getString("state"),
            rs.getString("city"),
            rs.getString("postalCode"),
            rs.getObject("clinicId", UUID.class),
            rs.getString("clinicName"),
            rs.getString("verificationStatus"),
            rs.getBoolean("isActive"),
            rs.getObject("createdAt", LocalDateTime.class),
            rs.getObject("updatedAt", LocalDateTime.class),
            rs.getString("staffRole"),
            rs.getBoolean("isAccountant"));
    }

    /**
     * A null-tolerant parameter map.
     *
     * <p>{@code Map.of}/{@code Map.ofEntries} reject null values, and most of
     * the profile columns are nullable. A {@link HashMap} is the honest
     * container here: the SQL statement decides which columns may be null.
     */
    private static Map<String, Object> values(Object... keyValuePairs) {
        Map<String, Object> values = new HashMap<>();
        for (int index = 0; index < keyValuePairs.length; index += 2) {
            values.put((String) keyValuePairs[index], keyValuePairs[index + 1]);
        }
        return values;
    }

    private static void requireProject(ClinicScope scope, String message) {
        if (!scope.isProjectScoped()) {
            throw new IllegalStateException(message);
        }
    }

    private static String nullable(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String blankToNull(String value) {
        return nullable(value);
    }
}
