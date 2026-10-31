package com.firstelement.identity;

import com.firstelement.security.TenantContext;
import com.firstelement.security.UserRole;
import com.firstelement.security.query.ScopedWhere;
import com.firstelement.security.query.TenantQueryGuard;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes {@code users}, mapped <b>explicitly</b> to the verified physical
 * schema.
 *
 * <h2>Mapping rules (ADR-0002)</h2>
 *
 * <ul>
 *   <li>Identifiers are the quoted camelCase column names, written by hand in every
 *       statement. No naming strategy exists anywhere in the runtime, so a column cannot
 *       be renamed in the database and silently unbound.</li>
 *   <li>Every timestamp is bound and read as {@link LocalDateTime} because all 98 of them
 *       are {@code timestamp(3) without time zone}. {@code Instant} would apply a zone
 *       shift and would be wrong.</li>
 *   <li>Tenant-scoped reads go through {@link TenantQueryGuard} and {@link ScopedWhere},
 *       so there is no way to issue an unbounded read here even by mistake.</li>
 * </ul>
 *
 * <h2>The one statement that is not tenant-scoped</h2>
 *
 * <p>{@link #findByEmail(String)} exists because login must answer "is this email known?"
 * before a tenant scope exists. It is not exposed to any other caller and it returns only
 * the columns login needs. The rate limiter plus the constant-time bcrypt comparison in
 * {@code PasswordVerifier} are what keep it from being an enumeration oracle.
 */
@Repository
public class UserRepository {

    /** Includes the password hash: login only. */
    private static final String LOGIN_COLUMNS = """
        u."id", u."organizationId", u."email", u."passwordHash", u."fullName",
        u."role", u."locale", u."isActive", u."lastLoginAt", u."createdAt", u."updatedAt"
        """;

    /** No password hash: any projection that may leave the process. */
    private static final String PUBLIC_COLUMNS = """
        u."id", u."organizationId", u."email", u."fullName", u."role",
        u."locale", u."isActive", u."lastLoginAt"
        """;

    static final RowMapper<UserRecord> LOGIN_MAPPER = (rs, rowNum) -> new UserRecord(
        rs.getObject("id", UUID.class),
        rs.getObject("organizationId", UUID.class),
        rs.getString("email"),
        rs.getString("passwordHash"),
        rs.getString("fullName"),
        UserRole.fromValue(rs.getString("role")),
        rs.getString("locale"),
        rs.getBoolean("isActive"),
        rs.getObject("lastLoginAt", LocalDateTime.class),
        rs.getObject("createdAt", LocalDateTime.class),
        rs.getObject("updatedAt", LocalDateTime.class));

    static final RowMapper<UserView> VIEW_MAPPER = (rs, rowNum) -> new UserView(
        rs.getObject("id", UUID.class),
        rs.getObject("organizationId", UUID.class),
        rs.getString("email"),
        rs.getString("fullName"),
        UserRole.fromValue(rs.getString("role")),
        rs.getString("locale"),
        rs.getBoolean("isActive"),
        rs.getObject("lastLoginAt", LocalDateTime.class),
        List.of());

    private final NamedParameterJdbcTemplate jdbc;

    public UserRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // login-only reads
    // ------------------------------------------------------------------

    /**
     * Login lookup by email. Global by necessity; see the class note.
     *
     * <p>The comparison is exact against the value the caller already normalized, rather
     * than {@code LOWER(email) = :email}, which cannot use the unique index on
     * {@code users.email}.
     */
    public Optional<UserRecord> findByEmail(String normalizedEmail) {
        if (normalizedEmail == null || normalizedEmail.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query("select " + LOGIN_COLUMNS + " from users u where u.\"email\" = :email",
            new MapSqlParameterSource("email", normalizedEmail), LOGIN_MAPPER)
            .stream().findFirst();
    }

    /** Whether the organization itself is active. A login into a disabled tenant fails. */
    public boolean isOrganizationActive(UUID organizationId) {
        Integer count = jdbc.queryForObject(
            "select count(*) from organizations where \"id\" = :id and \"isActive\" = true",
            new MapSqlParameterSource("id", organizationId), Integer.class);
        return count != null && count > 0;
    }

    public boolean exists(UUID organizationId, UUID userId) {
        Integer count = jdbc.queryForObject(
            "select count(*) from users where \"organizationId\" = :org and \"id\" = :id",
            new MapSqlParameterSource().addValue("org", organizationId).addValue("id", userId),
            Integer.class);
        return count != null && count > 0;
    }

    // ------------------------------------------------------------------
    // tenant-scoped reads
    // ------------------------------------------------------------------

    /**
     * One user inside the caller's scope.
     *
     * <p>Returns {@link Optional#empty()} for a user that does not exist and for a user
     * outside the scope. The caller cannot tell the two apart, which is the point.
     */
    public Optional<UserRecord> findInScope(TenantQueryGuard guard,
                                            TenantContext context,
                                            UUID userId) {
        ScopedWhere.NamedSql query = ScopedWhere.of(guard.tenantScope(context))
            .select("select " + LOGIN_COLUMNS + " from users u", "u", "u.\"id\" = :id",
                Map.of("id", userId));
        return jdbc.query(query.sql(), query.parameters(), LOGIN_MAPPER).stream().findFirst();
    }

    /** The tenant's user list, inactive users included, never with a password hash. */
    public List<UserView> listInScope(TenantQueryGuard guard, TenantContext context) {
        ScopedWhere.NamedSql query = ScopedWhere.of(guard.tenantScope(context))
            .select("select " + PUBLIC_COLUMNS + " from users u", "u");
        return jdbc.query(query.sql(), query.parameters(), VIEW_MAPPER);
    }

    // ------------------------------------------------------------------
    // mutations
    // ------------------------------------------------------------------

    public void recordLogin(UUID userId, LocalDateTime at) {
        jdbc.update("update users set \"lastLoginAt\" = :at, \"updatedAt\" = :at where \"id\" = :id",
            new MapSqlParameterSource().addValue("id", userId).addValue("at", at));
    }

    /**
     * Changes a role.
     *
     * <p>The explicit {@code cast(... as "UserRole")} is required: the column is the
     * {@code UserRole} enum and the driver binds a String as {@code varchar}. There is no
     * ORM conversion to hide that here, which is precisely why the mapping is explicit.
     *
     * @return rows changed; 0 proves the target was not in scope rather than assuming it
     */
    public int changeRole(TenantQueryGuard guard,
                          TenantContext context,
                          UUID userId,
                          UserRole newRole) {
        ScopedWhere.NamedSql query = ScopedWhere.of(guard.tenantScope(context))
            .update("update users u set \"role\" = cast(:role as \"UserRole\"), \"updatedAt\" = :at", "u",
                "u.\"id\" = :id",
                Map.of("id", userId, "role", newRole.value(), "at", LocalDateTime.now()));
        return jdbc.update(query.sql(), query.parameters());
    }

    /** Sets the active flag. Deactivation also revokes every session for the user. */
    public int setActive(TenantQueryGuard guard,
                         TenantContext context,
                         UUID userId,
                         boolean active) {
        ScopedWhere.NamedSql query = ScopedWhere.of(guard.tenantScope(context))
            .update("update users u set \"isActive\" = :active, \"updatedAt\" = :at", "u",
                "u.\"id\" = :id",
                Map.of("id", userId, "active", active, "at", LocalDateTime.now()));
        return jdbc.update(query.sql(), query.parameters());
    }

    /**
     * Documents the one mapping constraint that is easy to break silently.
     *
     * <p>Every timestamp column in this schema is {@code timestamp(3) without time zone}
     * (Phase-2 finding V6). Reading one as {@code Instant} or {@code OffsetDateTime}
     * applies a zone shift, so {@link LocalDateTime} is the only correct binding. Every
     * mapper in this class uses it.
     */
    @SuppressWarnings("unused")
    private static void timestampMappingContract(ResultSet rs, String column) throws SQLException {
        rs.getObject(column, LocalDateTime.class);
    }
}