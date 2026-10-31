package com.firstelement.security.platform.web;

import com.firstelement.security.platform.InviteTokenService;
import com.firstelement.security.platform.TotpVerifier;
import java.time.Instant;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public invite redemption controller for invited Clinic Admins.
 *
 * <p>Enables newly invited clinic admins to claim their clinic, inspect invite details,
 * and set their initial strong password and optional MFA.
 * No session required — authenticated strictly by single-use hashed invite token.
 */
@RestController
@ConditionalOnProperty(prefix = "firstelement.platform.console", name = "enabled", havingValue = "true")
@RequestMapping("/api/v1/invites")
public class InvitePublicController {

    private final InviteTokenService inviteService;

    public InvitePublicController(InviteTokenService inviteService) {
        this.inviteService = inviteService;
    }

    public record InviteView(String clinicName, String adminName, String email, Instant expiresAt) {
    }

    public record AcceptInviteRequest(String password, String totpSecretEnc, String totpCode) {
    }

    @GetMapping("/{token}")
    public InviteView getInvite(@PathVariable("token") String token) {
        InviteTokenService.InviteDetails details = inviteService.validateInvite(token);
        return new InviteView(details.clinicName(), details.adminName(), details.email(), details.expiresAt());
    }

    @PostMapping("/{token}/accept")
    public ResponseEntity<Map<String, Object>> acceptInvite(
            @PathVariable("token") String token,
            @RequestBody AcceptInviteRequest request) {

        if (request.password() == null || request.password().length() < 8) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "INVALID_PASSWORD",
                "message", "Password must be at least 8 characters long."
            ));
        }

        inviteService.acceptInvite(token, request.password(), request.totpSecretEnc());

        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "Account activated successfully. You can now sign in with your email and password."
        ));
    }

    @ExceptionHandler(InviteTokenService.InviteAlreadyUsedException.class)
    public ResponseEntity<Map<String, Object>> handleAlreadyUsed(InviteTokenService.InviteAlreadyUsedException ex) {
        return ResponseEntity.status(HttpStatus.GONE).body(Map.of(
            "error", "INVITE_ALREADY_USED",
            "message", ex.getMessage()
        ));
    }

    @ExceptionHandler(InviteTokenService.InviteExpiredException.class)
    public ResponseEntity<Map<String, Object>> handleExpired(InviteTokenService.InviteExpiredException ex) {
        return ResponseEntity.status(HttpStatus.GONE).body(Map.of(
            "error", "INVITE_EXPIRED",
            "message", ex.getMessage()
        ));
    }

    @ExceptionHandler(InviteTokenService.InvalidInviteTokenException.class)
    public ResponseEntity<Map<String, Object>> handleInvalid(InviteTokenService.InvalidInviteTokenException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
            "error", "INVALID_INVITE",
            "message", ex.getMessage()
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of(
            "error", "BAD_REQUEST",
            "message", ex.getMessage()
        ));
    }
}
