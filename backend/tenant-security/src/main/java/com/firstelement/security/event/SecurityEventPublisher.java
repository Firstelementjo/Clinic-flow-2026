package com.firstelement.security.event;

/**
 * The port the security boundary writes events through.
 *
 * <p>Kept as an interface so {@code :backend:tenant-security} owns no persistence.
 * {@code :backend:identity-access} supplies the {@code audit_logs} writer.
 *
 * <h2>Contract</h2>
 *
 * <ul>
 *   <li><b>Never throws.</b> An audit write failure must not roll back or fail a
 *       legitimate action the user already completed (the legacy
 *       {@code AuditService} made the same call). The implementation logs at
 *       ERROR so the gap stays visible.</li>
 *   <li><b>Sanitized.</b> Implementations must run {@link SecurityEvent} through
 *       {@link SecurityEventSanitizer} before persisting.</li>
 *   <li><b>Complete.</b> Login success/failure, logout, session issue/reject/revoke,
 *       authorization denial, role change, and membership change are all mandatory
 *       (FR-2.8: every privileged decision and denial must be observable).</li>
 * </ul>
 */
@FunctionalInterface
public interface SecurityEventPublisher {

    void publish(SecurityEvent event);
}