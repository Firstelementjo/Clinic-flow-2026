package com.firstelement.identity.web;

import com.firstelement.identity.MembershipAdminService;
import com.firstelement.security.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/admin/identity} — role, membership, and session commands.
 *
 * <h2>Deliberately typed, never a SQL console</h2>
 *
 * <p>PROJECT_REQUIREMENTS §8 forbids arbitrary SQL, arbitrary row edits, and secret display
 * from the Admin UI. Every route here is a named, validated, audited command with a fixed
 * write set. There is no endpoint that accepts SQL, a column name, or a free-form row.
 *
 * <h2>Responses carry the security effect, not just success</h2>
 *
 * <p>Each mutation returns the number of sessions it revoked, so an operator can see that
 * a role change actually cut off the previous reach rather than assuming it did.
 */
@RestController
@RequestMapping("/api/v1/admin/identity")
public class IdentityAdminController {

    private final MembershipAdminService admin;

    public IdentityAdminController(MembershipAdminService admin) {
        this.admin = admin;
    }

    /** {@code PATCH /api/v1/admin/identity/users/{userId}/role} */
    public record ChangeRoleRequest(@NotNull UserRole role) {
    }

    /** The outcome of a role change. */
    public record RoleChangeResponse(UUID userId, UserRole role, int sessionsRevoked) {
    }

    @PatchMapping("/users/{userId}/role")
    public ResponseEntity<RoleChangeResponse> changeRole(@PathVariable UUID userId,
                                                        @Valid @RequestBody ChangeRoleRequest body,
                                                        HttpServletRequest request) {
        int revoked = admin.changeRole(userId, body.role(), clientIp(request));
        return ResponseEntity.ok(new RoleChangeResponse(userId, body.role(), revoked));
    }

    /** {@code DELETE /api/v1/admin/identity/users/{userId}} — deactivate + revoke. */
    public record DeactivateResponse(UUID userId, int sessionsRevoked) {
    }

    @DeleteMapping("/users/{userId}")
    public ResponseEntity<DeactivateResponse> deactivate(@PathVariable UUID userId,
                                                        HttpServletRequest request) {
        int revoked = admin.deactivate(userId, clientIp(request));
        return ResponseEntity.ok(new DeactivateResponse(userId, revoked));
    }

    /** {@code POST /api/v1/admin/identity/users/{userId}/projects/{projectId}} */
    @PostMapping("/users/{userId}/projects/{projectId}")
    public ResponseEntity<MembershipAdminService.MembershipChange> addMembership(
        @PathVariable UUID userId, @PathVariable UUID projectId, HttpServletRequest request) {
        return ResponseEntity.ok(admin.addMembership(userId, projectId, clientIp(request)));
    }

    /** {@code DELETE /api/v1/admin/identity/users/{userId}/projects/{projectId}} */
    @DeleteMapping("/users/{userId}/projects/{projectId}")
    public ResponseEntity<MembershipAdminService.MembershipChange> removeMembership(
        @PathVariable UUID userId, @PathVariable UUID projectId, HttpServletRequest request) {
        return ResponseEntity.ok(admin.removeMembership(userId, projectId, clientIp(request)));
    }

    /** {@code DELETE /api/v1/admin/identity/sessions/{sessionId}} — revoke one session. */
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<List<SessionRevocation>> revokeSession(@PathVariable UUID sessionId,
                                                                  HttpServletRequest request) {
        int revoked = admin.revokeSession(sessionId, clientIp(request));
        return ResponseEntity.ok(List.of(new SessionRevocation(sessionId, revoked)));
    }

    public record SessionRevocation(UUID sessionId, int sessionsRevoked) {
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}