package com.firstelement.security.platform;

import com.firstelement.security.auth.PasswordVerifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single-use, expiring, cryptographically-hashed invite tokens for Clinic Admins.
 *
 * <p>Specification compliance:
 * - Operator enters clinic admin name + email/phone.
 * - System produces a single-use invite link backed by a SHA-256 hashed token.
 * - Operator NEVER sees or sets the clinic admin's password.
 * - The clinic admin sets their own password and MFA when claiming the invite.
 * - After redemption, the token is permanently invalidated (used_at set, single-use).
 */
public class InviteTokenService {

    public static final Duration DEFAULT_INVITE_TTL = Duration.ofHours(48);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final NamedParameterJdbcTemplate jdbc;
    private final PlatformAuditRepository audit;
    private final PasswordVerifier passwordVerifier;

    public InviteTokenService(NamedParameterJdbcTemplate jdbc, PlatformAuditRepository audit) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.passwordVerifier = new PasswordVerifier();
    }

    public record InviteTokenCreated(UUID inviteId, String rawToken, Instant expiresAt) {
    }

    public record InviteDetails(UUID inviteId, UUID organizationId, UUID projectId, UUID userId,
                                String email, String clinicName, String adminName,
                                Instant expiresAt, boolean used) {
    }

    public static class InviteTokenException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public InviteTokenException(String message) {
            super(message);
        }
    }

    public static class InviteExpiredException extends InviteTokenException {
        private static final long serialVersionUID = 1L;
        public InviteExpiredException() {
            super("Invite token has expired. Please contact support or request a new invite.");
        }
    }

    public static class InviteAlreadyUsedException extends InviteTokenException {
        private static final long serialVersionUID = 1L;
        public InviteAlreadyUsedException() {
            super("Invite token has already been used. Please sign in with your password.");
        }
    }

    public static class InvalidInviteTokenException extends InviteTokenException {
        private static final long serialVersionUID = 1L;
        public InvalidInviteTokenException() {
            super("Invalid invite token.");
        }
    }

    /**
     * Issues a fresh single-use invite token.
     * The raw token is returned ONCE to the caller to transmit in an invite link.
     * The database stores only the SHA-256 hash.
     */
    @Transactional
    public InviteTokenCreated createInvite(UUID organizationId,
                                           UUID projectId,
                                           UUID userId,
                                           String email,
                                           UUID operatorId,
                                           Duration ttl) {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            ttl = DEFAULT_INVITE_TTL;
        }

        byte[] rawBytes = new byte[32];
        RANDOM.nextBytes(rawBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(rawBytes);
        String tokenHash = hashToken(rawToken);

        UUID inviteId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttl);

        jdbc.update("""
            insert into "public"."invite_tokens"
                ("id", "organizationId", "projectId", "userId", "email",
                 "tokenHash", "expiresAt", "createdByOperatorId", "createdAt")
            values
                (:id, :orgId, :projId, :userId, :email,
                 :tokenHash, :expiresAt, :operatorId, :createdAt)
            """,
            new MapSqlParameterSource()
                .addValue("id", inviteId)
                .addValue("orgId", organizationId)
                .addValue("projId", projectId)
                .addValue("userId", userId)
                .addValue("email", PasswordVerifier.normalizeEmail(email))
                .addValue("tokenHash", tokenHash)
                .addValue("expiresAt", Timestamp.from(expiresAt))
                .addValue("operatorId", operatorId)
                .addValue("createdAt", Timestamp.from(now)));

        // Flag the user account as having a pending invite
        jdbc.update("""
            update "public"."users"
               set "invitePending" = true,
                   "updatedAt" = :now
             where "id" = :userId
            """,
            new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("now", Timestamp.from(now)));

        return new InviteTokenCreated(inviteId, rawToken, expiresAt);
    }

    /**
     * Validates an invite token without redeeming it.
     */
    public InviteDetails validateInvite(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new InvalidInviteTokenException();
        }
        String tokenHash = hashToken(rawToken);

        List<InviteDetails> rows = jdbc.query("""
            select it."id"             as "inviteId",
                   it."organizationId" as "organizationId",
                   it."projectId"      as "projectId",
                   it."userId"         as "userId",
                   it."email"          as "email",
                   it."expiresAt"      as "expiresAt",
                   it."usedAt"         as "usedAt",
                   p."name"            as "clinicName",
                   u."fullName"        as "adminName"
              from "public"."invite_tokens" it
              join "public"."projects" p on p."id" = it."projectId"
              join "public"."users" u on u."id" = it."userId"
             where it."tokenHash" = :tokenHash
            """,
            new MapSqlParameterSource("tokenHash", tokenHash),
            (rs, i) -> new InviteDetails(
                rs.getObject("inviteId", UUID.class),
                rs.getObject("organizationId", UUID.class),
                rs.getObject("projectId", UUID.class),
                rs.getObject("userId", UUID.class),
                rs.getString("email"),
                rs.getString("clinicName"),
                rs.getString("adminName"),
                rs.getTimestamp("expiresAt").toInstant(),
                rs.getTimestamp("usedAt") != null
            ));

        if (rows.isEmpty()) {
            throw new InvalidInviteTokenException();
        }
        InviteDetails details = rows.get(0);
        if (details.used()) {
            throw new InviteAlreadyUsedException();
        }
        if (details.expiresAt().isBefore(Instant.now())) {
            throw new InviteExpiredException();
        }
        return details;
    }

    /**
     * Accepts and redeems the invite: sets user's password, marks token as used.
     * Single-use is strictly enforced.
     */
    @Transactional
    public void acceptInvite(String rawToken, String newPassword, String totpSecretEnc) {
        InviteDetails details = validateInvite(rawToken);

        if (newPassword == null || newPassword.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters in length.");
        }

        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);
        String passwordHash = passwordVerifier.hash(newPassword);

        // 1. Mark invite token as used (optimistic condition: usedAt must still be null)
        int updatedTokens = jdbc.update("""
            update "public"."invite_tokens"
               set "usedAt" = :now
             where "id" = :inviteId
               and "usedAt" is null
            """,
            new MapSqlParameterSource()
                .addValue("inviteId", details.inviteId())
                .addValue("now", nowTs));

        if (updatedTokens == 0) {
            throw new InviteAlreadyUsedException();
        }

        // 2. Set user credentials and activate
        MapSqlParameterSource userParams = new MapSqlParameterSource()
            .addValue("userId", details.userId())
            .addValue("passwordHash", passwordHash)
            .addValue("now", nowTs);

        String sql;
        if (totpSecretEnc != null && !totpSecretEnc.isBlank()) {
            sql = """
                update "public"."users"
                   set "passwordHash" = :passwordHash,
                       "verificationStatus" = 'VERIFIED',
                       "invitePending" = false,
                       "isActive" = true,
                       "totpSecretEnc" = :totpSecretEnc,
                       "mfaEnrolledAt" = :now,
                       "updatedAt" = :now
                 where "id" = :userId
                """;
            userParams.addValue("totpSecretEnc", totpSecretEnc);
        } else {
            sql = """
                update "public"."users"
                   set "passwordHash" = :passwordHash,
                       "verificationStatus" = 'VERIFIED',
                       "invitePending" = false,
                       "isActive" = true,
                       "updatedAt" = :now
                 where "id" = :userId
                """;
        }
        jdbc.update(sql, userParams);

        // 3. Record audit trail
        audit.record("INVITE_ACCEPTED", null, null, details.organizationId(),
            details.projectId(), "User", details.userId().toString(),
            "Clinic admin redeemed invite token and set credentials",
            "email=" + details.email(), PlatformAuditRepository.ALLOWED, null, null);
    }

    /**
     * Resends invite: invalidates previous unused tokens for the clinic's admin and issues a new one.
     */
    @Transactional
    public InviteTokenCreated resendInvite(UUID organizationId, UUID projectId, UUID userId,
                                          String email, UUID operatorId) {
        Instant now = Instant.now();
        // Invalidate previous unspent tokens for this user
        jdbc.update("""
            update "public"."invite_tokens"
               set "usedAt" = :now
             where "userId" = :userId
               and "usedAt" is null
            """,
            new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("now", Timestamp.from(now)));

        InviteTokenCreated created = createInvite(organizationId, projectId, userId, email, operatorId, DEFAULT_INVITE_TTL);

        audit.record("INVITE_RESENT", operatorId, null, organizationId, projectId, "User",
            userId.toString(), "Operator regenerated invite token",
            "email=" + email, PlatformAuditRepository.ALLOWED, null, null);

        return created;
    }

    public static String hashToken(String rawToken) {
        if (rawToken == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must be supported", e);
        }
    }
}
