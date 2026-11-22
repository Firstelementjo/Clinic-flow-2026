package com.firstelement.inbox.web;

import com.firstelement.crm.clinic.intake.PatientIntakeModels;
import com.firstelement.crm.clinic.intake.PatientIntakeService;
import com.firstelement.security.auth.LoginRateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, write-only endpoint surface for the standalone Patient Intake Portal.
 *
 * <p>Security properties:
 * <ul>
 *   <li>Authenticated strictly by clinic QR key + 1-hour single-use code (then 30-min session token).</li>
 *   <li>No clinic session cookies are read or issued; CORS runs with {@code allowCredentials=false}.</li>
 *   <li>Never exposes patient records, phone numbers, DOBs, or medical histories.</li>
 *   <li>Public submissions write only to a {@code PENDING_APPROVAL} quarantine record.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/public/intake")
public class PatientIntakePublicController {

    private final PatientIntakeService intakeService;
    private final LoginRateLimiter verifyRateLimiter = new LoginRateLimiter(15, Duration.ofMinutes(1));

    public PatientIntakePublicController(PatientIntakeService intakeService) {
        this.intakeService = intakeService;
    }

    @GetMapping("/{clinicKey}/config")
    @Operation(summary = "Get clinic branding and canonical intake form schema")
    public PatientIntakeModels.PublicClinicConfig getConfig(@PathVariable("clinicKey") String clinicKey) {
        return intakeService.getPublicConfig(clinicKey);
    }

    @PostMapping("/{clinicKey}/verify")
    @Operation(summary = "Verify a 1-hour patient intake code and obtain a temporary submission token")
    public ResponseEntity<?> verifyCode(
            @PathVariable("clinicKey") String clinicKey,
            @RequestBody(required = false) PatientIntakeModels.VerifyCodeRequest request,
            HttpServletRequest httpRequest) {
        String ip = currentIp(httpRequest);
        LoginRateLimiter.Decision decision = verifyRateLimiter.recordAttempt(ip, ip + ":" + clinicKey);
        if (!decision.allowed()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf(decision.retryAfterSeconds()))
                    .body(Map.of(
                            "code", "RATE_LIMITED",
                            "message", "Too many verification attempts. Please wait a minute and try again.",
                            "details", Map.of("retryAfterSeconds", String.valueOf(decision.retryAfterSeconds()))));
        }

        String code = request == null ? null : request.code();
        PatientIntakeModels.VerifyCodeResponse response = intakeService.verifyCode(clinicKey, code);
        verifyRateLimiter.reset(ip, ip + ":" + clinicKey);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{clinicKey}/submit")
    @Operation(summary = "Submit patient demographic, medical, dental, and chief complaint intake")
    public PatientIntakeModels.SubmitIntakeResult submitIntake(
            @PathVariable("clinicKey") String clinicKey,
            @RequestBody PatientIntakeModels.SubmitIntakeRequest request) {
        return intakeService.submitPublicIntake(clinicKey, request);
    }

    private static String currentIp(HttpServletRequest request) {
        String ip = MDC.get("remoteIp");
        if (ip != null && !ip.isBlank()) {
            return ip;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }
}
