package com.firstelement.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liveness ping under /api/v1; readiness/health come from Actuator probes. */
@RestController
public class StatusController {

    @GetMapping("/api/v1/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(java.util.Map.of("status", "UP", "version", "v1"));
    }
}
