package com.firstelement.admin.maintenance;

import com.firstelement.admin.audit.AdminAuditStreamWriter;
import com.firstelement.admin.audit.AuditAttribution;
import com.firstelement.admin.audit.AuditVocabularies;
import com.firstelement.admin.audit.AuditWrite;
import com.firstelement.admin.governance.LegalHoldService;
import com.firstelement.admin.governance.PrivacyDeletionService;
import com.firstelement.security.TenantContext;
import com.firstelement.security.permission.AuthorizationService;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.permission.PolicyResource;
import com.firstelement.security.query.TenantQueryGuard;
import com.firstelement.security.query.TenantScope;
import com.firstelement.worker.outbox.OutboxRepository;
import com.firstelement.worker.outbox.OutboxState;
import com.firstelement.worker.queue.QueueName;
import com.firstelement.worker.runtime.OutboxReplayService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only place an Admin surface changes anything.
 *
 * <h2>One permission check, at two layers, for the same reason</h2>
 *
 * <p>Every method calls {@link #authorize} first: it checks the command's declared permission
 * against the resolved context, refuses a machine principal, and re-checks the project against the
 * server-resolved scope. The controller repeats the permission check at the HTTP edge (FE-WEB-003
 * requires the route to name its permission, and ADR-0005 §7's "service layer is the boundary"
 * decision still stands). The duplication is deliberate: the route makes the declaration reviewable
 * in one place, the service makes it impossible to reach the write without it.
 *
 * <h2>Every outcome is audited, including every refusal</h2>
 *
 * <p>"I clicked it and nothing happened" is the moment an operator most needs a record, and it is
 * the moment a silent implementation is least defensible. Every method writes an
 * {@code admin_audit_events} row with the outcome, the scope and the impact numbers, and a replay
 * additionally writes a {@code job_integration_events} row naming the queue.
 *
 * <h2>The two disabled commands refuse <em>and</em> write</h2>
 *
 * <p>They are reachable, they return {@link MaintenanceOutcome#applied} = {@code false} with a
 * reason, and they write an audit row. A caller cannot tell "disabled" from "succeeded" by the
 * absence of a response, and a test asserts on the state afterwards: the deletion request is still
 * {@code REQUESTED}, the hold is still {@code ACTIVE}, and the row counts are unchanged.
 */
@Service
public class MaintenanceCommandService {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceCommandService.class);

    private final TenantQueryGuard guard;
    private final AuthorizationService authorization;
    private final AdminAuditStreamWriter audit;
    private final OutboxReplayService replay;
    private final OutboxRepository outbox;
    private final LegalHoldService legalHolds;
    private final PrivacyDeletionService privacyDeletions;

    public MaintenanceCommandService(TenantQueryGuard guard,
                                     AuthorizationService authorization,
                                     AdminAuditStreamWriter audit,
                                     OutboxReplayService replay,
                                     OutboxRepository outbox,
                                     LegalHoldService legalHolds,
                                     PrivacyDeletionService privacyDeletions) {
        this.guard = guard;
        this.authorization = authorization;
        this.audit = audit;
        this.replay = replay;
        this.outbox = outbox;
        this.legalHolds = legalHolds;
        this.privacyDeletions = privacyDeletions;
    }

    /**
     * The allow-list, as the operator sees it.
     *
     * <p>Disabled commands are included with their reason. A catalogue that hid them would make
     * "the feature is missing" and "the feature is off by decision" indistinguishable.
     */
    public List<Map<String, Object>> catalogue(TenantContext caller) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MaintenanceCommand command : MaintenanceCommand.all()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("command", command.wireValue());
            row.put("requiredPermission", command.requiredPermission().value());
            row.put("destructive", command.destructive());
            row.put("enabled", command.enabled());
            row.put("description", command.description());
            row.put("callerHoldsPermission",
                authorization.allows(caller, command.requiredPermission()));
            rows.add(row);
        }
        return rows;
    }

    // ------------------------------------------------------------------
    // replay
    // ------------------------------------------------------------------

    /**
     * Replays one dead-lettered outbox event.
     *
     * <p>Three gates before anything is written:
     *
     * <ol>
     *   <li>{@link MaintenanceCommand#REPLAY_DEAD_LETTERED_JOB} must be enabled — it is, and the
     *       check is here anyway so a future edit to the catalogue cannot silently arm a command
     *       whose service was never written for it;</li>
     *   <li>the caller must be an authenticated user, because the replay records
     *       {@code replayedByUserId} and the schema's composite tenant FK would refuse a machine;</li>
     *   <li>the event's queue must be declared replay-safe by {@link ReplaySafetyPolicy}.</li>
     * </ol>
     *
     * <p>Then the delegated replay, which is itself idempotent and audited. A second call after a
     * successful replay returns {@code NOT_DEAD} and writes its own audit row, so the operator sees
     * "already done" rather than an error.
     */
    @Transactional
    public MaintenanceOutcome replayDeadLetteredJob(TenantContext caller, UUID outboxEventId) {
        MaintenanceCommand command = MaintenanceCommand.REPLAY_DEAD_LETTERED_JOB;
        TenantScope scope = authorize(caller, command);
        MaintenanceOutcome.Scope actionScope =
            new MaintenanceOutcome.Scope(caller.organizationId(), null, outboxEventId,
                "OutboxEvent");

        Optional<OutboxRepository.OutboxRow> found = outbox.reread(caller.organizationId(),
            outboxEventId);
        if (found.isEmpty()) {
            // Not found and not-in-tenant are the same answer, and the audit row is written against
            // the CALLER's tenant so the trail cannot confirm that another tenant's id exists.
            return refuse(caller, scope, command, actionScope, "NOT_FOUND",
                Map.of("replaySafety", "not-evaluated"),
                "No such event in your tenant.",
                "Nothing was written. Check the id, or list the open dead letters for this tenant.");
        }
        OutboxRepository.OutboxRow row = found.get();
        QueueName queue = QueueName.fromWireValue(row.eventType());

        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("state", row.state().name());
        impact.put("attempts", String.valueOf(row.attempts()));
        impact.put("replayCount", String.valueOf(row.replayCount() == null ? 0 : row.replayCount()));
        impact.put("queue", row.eventType());
        impact.put("replaySafe", String.valueOf(ReplaySafetyPolicy.isReplaySafe(queue)));
        impact.put("replaySafetyEvidence", ReplaySafetyPolicy.explain(queue));

        if (row.state() != OutboxState.DEAD) {
            // Already replayed, still in flight, or delivered. Refusing here — rather than letting
            // the delegated call decide — means the operator is told the state, which is the answer
            // they actually wanted.
            return refuse(caller, scope, command, actionScope, "NOT_DEAD", impact,
                "The event is " + row.state().name() + ", not DEAD; there is nothing to replay.",
                "Nothing was written. A live event is already being worked; replaying it would be a "
                    + "second delivery.");
        }
        if (!ReplaySafetyPolicy.isReplaySafe(queue)) {
            return refuse(caller, scope, command, actionScope, "NOT_REPLAY_SAFE", impact,
                "The handler for queue '" + row.eventType() + "' has not declared itself "
                    + "replay-safe, so replay is refused by default.",
                "Nothing was written. " + ReplaySafetyPolicy.explain(queue));
        }

        OutboxReplayService.ReplayOutcome result = replay.replay(caller, outboxEventId);
        writeJobEvent(caller, scope, command, actionScope, row, queue, result.name(),
            result == OutboxReplayService.ReplayOutcome.REPLAYED
                ? AuditVocabularies.AuditOutcome.SUCCESS
                : AuditVocabularies.AuditOutcome.DENIED,
            "outbox." + result.name().toLowerCase(Locale.ROOT), impact);

        boolean appliedResult = result == OutboxReplayService.ReplayOutcome.REPLAYED;
        return new MaintenanceOutcome(appliedResult, command.wireValue(), result.name(),
            actionScope, impact,
            appliedResult
                ? "The event is claimable again. The attempts do not reset, so if the cause is "
                    + "still present it will dead-letter again after one more try."
                : "The replay was a no-op: " + result.name() + ".",
            appliedResult
                ? "If the event dead-letters again, the cause is unchanged — read the latest "
                    + "job_attempts row rather than replaying again."
                : "Nothing was written.",
            List.of());
    }

    // ------------------------------------------------------------------
    // dead letters
    // ------------------------------------------------------------------

    /**
     * Resolves or discards a dead letter.
     *
     * <p>The outcome value is checked against the two the schema's {@code DeadLetterStatus} allows,
     * so an unrecognised value is a refusal here rather than a constraint violation later.
     */
    @Transactional
    public MaintenanceOutcome resolveDeadLetter(TenantContext caller, UUID deadLetterId,
                                               String status, String note) {
        MaintenanceCommand command = MaintenanceCommand.RESOLVE_DEAD_LETTER;
        TenantScope scope = authorize(caller, command);
        MaintenanceOutcome.Scope actionScope =
            new MaintenanceOutcome.Scope(caller.organizationId(), null, deadLetterId,
                "JobDeadLetter");

        String normalized = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!normalized.equals("RESOLVED") && !normalized.equals("DISCARDED")) {
            return refuse(caller, scope, command, actionScope, "INVALID_STATUS",
                Map.of("requestedStatus", normalized),
                "The status must be RESOLVED or DISCARDED.",
                "Nothing was written. DeadLetterStatus has no other terminal value.");
        }
        if (note == null || note.isBlank()) {
            // A resolution with no note records that somebody decided and not what they decided.
            return refuse(caller, scope, command, actionScope, "NOTE_REQUIRED", Map.of(),
                "A dead-letter resolution must say why.",
                "Nothing was written. Re-issue with a note; it becomes the resolutionNote column.");
        }

        boolean resolved = replay.resolve(caller, deadLetterId, normalized, note.trim());
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("status", normalized);
        impact.put("rowFound", String.valueOf(resolved));
        return resolved
            ? MaintenanceOutcome.applied(command, actionScope, normalized, impact,
                "The dead letter is closed. The row is retained as the evidence of the decision.",
                "Nothing to undo: the dead letter is retained by policy, so the decision can be "
                    + "audited later.")
            : refuse(caller, scope, command, actionScope, "NOT_FOUND", impact,
                "No such dead letter in your tenant, or it was already closed.",
                "Nothing was written.");
    }

    // ------------------------------------------------------------------
    // legal holds
    // ------------------------------------------------------------------

    /** Places a legal hold. Delegates to {@link LegalHoldService}, which writes both rows. */
    @Transactional
    public MaintenanceOutcome placeLegalHold(TenantContext caller,
                                            LegalHoldService.HoldScopeType scopeType,
                                            UUID projectId,
                                            String scopeRef,
                                            String reason,
                                            LocalDateTime expiresAt) {
        MaintenanceCommand command = MaintenanceCommand.PLACE_LEGAL_HOLD;
        TenantScope scope = authorize(caller, command);
        requireEnabled(command);
        LegalHoldService.HoldOutcome result = legalHolds.place(caller, scopeType, projectId,
            scopeRef, reason, expiresAt);
        MaintenanceOutcome.Scope actionScope = new MaintenanceOutcome.Scope(caller.organizationId(),
            projectId, result.holdId(), "LegalHold");
        Map<String, Object> impact = Map.of(
            "activeHoldsCovering", String.valueOf(result.activeHoldsCovering()));
        return result.applied()
            ? MaintenanceOutcome.applied(command, actionScope, result.outcome(), impact,
                result.message(),
                "Releasing the hold is a separate audited command that names who and why.")
            : refuse(caller, scope, command, actionScope, result.outcome(), impact, result.message(),
                "Nothing was written.");
    }

    /** Releases a legal hold. Delegates to {@link LegalHoldService}. */
    @Transactional
    public MaintenanceOutcome releaseLegalHold(TenantContext caller, UUID holdId,
                                              String releaseReason, int expectedVersion) {
        MaintenanceCommand command = MaintenanceCommand.RELEASE_LEGAL_HOLD;
        TenantScope scope = authorize(caller, command);
        requireEnabled(command);
        LegalHoldService.HoldOutcome result = legalHolds.release(caller, holdId, releaseReason,
            expectedVersion);
        MaintenanceOutcome.Scope actionScope = new MaintenanceOutcome.Scope(caller.organizationId(),
            null, holdId, "LegalHold");
        Map<String, Object> impact = Map.of(
            "activeHoldsCovering", String.valueOf(result.activeHoldsCovering()));
        return result.applied()
            ? MaintenanceOutcome.applied(command, actionScope, result.outcome(), impact,
                result.message(),
                "The release is in the hold's append-only history; the mutable row cannot hide it.")
            : refuse(caller, scope, command, actionScope, result.outcome(), impact, result.message(),
                "Nothing was written. Re-read the hold and retry with its current version.");
    }

    /** The purge gate every future purge job must run first. */
    public LegalHoldService.PurgeGate purgeGate(TenantContext caller, UUID projectId) {
        return legalHolds.gate(caller, projectId);
    }

    // ------------------------------------------------------------------
    // privacy deletion
    // ------------------------------------------------------------------

    /**
     * Measures a deletion without writing anything.
     *
     * <p>Delegates to {@link PrivacyDeletionService#preview}, which needs no command permission of
     * its own because it writes nothing. The route still requires
     * {@link Permission#ORGANIZATION_UPDATE}: an impact count is a disclosure about a person's
     * footprint in the system, and it is not something an unrelated principal should be able to ask
     * for.
     */
    public PrivacyDeletionService.DeletionRequestOutcome previewPrivacyDeletion(TenantContext caller,
                                                                                UUID subjectUserId,
                                                                                UUID projectId) {
        return privacyDeletions.preview(caller, subjectUserId, projectId);
    }

    /** Opens a typed privacy-deletion request. Delegates to {@link PrivacyDeletionService}. */
    @Transactional
    public MaintenanceOutcome requestPrivacyDeletion(TenantContext caller, UUID subjectUserId,
                                                     UUID projectId, String subjectType,
                                                     String reason, String evidenceReference) {
        MaintenanceCommand command = MaintenanceCommand.REQUEST_PRIVACY_DELETION;
        TenantScope scope = authorize(caller, command);
        requireEnabled(command);
        PrivacyDeletionService.DeletionRequestOutcome result = privacyDeletions.request(caller,
            subjectUserId, projectId, subjectType, reason, evidenceReference);
        MaintenanceOutcome.Scope actionScope = new MaintenanceOutcome.Scope(caller.organizationId(),
            projectId, result.requestId(), "PrivacyDeletionRequest");
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("impactTotalRows", String.valueOf(result.impact().total()));
        impact.put("removedRows", "0");
        impact.put("blockingHolds", String.valueOf(result.legalHoldGate().blockingCount()));
        impact.put("retainedTargets", String.valueOf(result.retainedTargets()));
        impact.put("legalHoldGate", result.legalHoldGate().refusalReason());
        return MaintenanceOutcome.applied(command, actionScope, result.outcome(), impact,
            result.message(),
            "Execution is a disabled job. Nothing has been removed; releasing the request is a "
                + "state transition on the request itself, not a delete.");
    }

    // ------------------------------------------------------------------
    // the disabled commands
    // ------------------------------------------------------------------

    /**
     * Executes a deletion request. <b>Always refuses.</b>
     *
     * <p>Declared so the capability is visible and the refusal is recorded; the removal itself is a
     * durable job. The returned outcome names the state a caller should verify: the request is still
     * {@code REQUESTED} and nothing was removed.
     */
    @Transactional
    public MaintenanceOutcome executePrivacyDeletion(TenantContext caller, UUID requestId,
                                                     UUID projectId) {
        MaintenanceCommand command = MaintenanceCommand.EXECUTE_PRIVACY_DELETION;
        TenantScope scope = authorize(caller, command);
        MaintenanceOutcome.Scope actionScope = new MaintenanceOutcome.Scope(caller.organizationId(),
            projectId, requestId, "PrivacyDeletionRequest");
        return refuseDisabled(caller, scope, command, actionScope,
            "Removal belongs to a durable, batched, checkpointed job, not to an HTTP request "
                + "thread (AGENTS.md). Verify that the request is still REQUESTED and that no "
                + "privacy_deletion_target carries decision = REMOVED.");
    }

    /**
     * Purges retention-expired rows. <b>Always refuses.</b>
     *
     * <p>ADR-0008 §2 rules 2–4 and ADR-0009 §4: the purge must be tenant-scoped, batched,
     * checkpointed, idempotent and audited, and it must ask about legal hold first. The gate answer
     * is included in the impact map, so an operator asking "could this tenant be purged?" gets a
     * real answer even though the command is off.
     */
    @Transactional
    public MaintenanceOutcome purgeRetention(TenantContext caller, UUID projectId, String classCode) {
        MaintenanceCommand command = MaintenanceCommand.PURGE_RETENTION;
        TenantScope scope = authorize(caller, command);
        MaintenanceOutcome.Scope actionScope = new MaintenanceOutcome.Scope(caller.organizationId(),
            projectId, null, "RetentionClass");
        LegalHoldService.PurgeGate gate = legalHolds.gate(caller, projectId);
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("legalHoldGate", gate.refusalReason());
        impact.put("blockingHolds", String.valueOf(gate.blockingCount()));
        impact.put("classCode", classCode == null ? "" : classCode);
        impact.put("removedRows", "0");
        return refuseDisabled(caller, scope, command, actionScope,
            gate.mayPurge()
                ? "No active legal hold covers this scope, but the purge job itself ships disabled. "
                    + "Verify that no row was removed and that every table has a declared retention "
                    + "class before enabling it."
                : "A legal hold covers this scope, and the purge job ships disabled regardless. "
                    + "Verify that no row was removed.");
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    /**
     * The permission check, and the scope resolution, in one place.
     *
     * @throws org.springframework.security.access.AccessDeniedException when the principal does not
     *         hold the command's declared permission
     * @throws com.firstelement.security.error.PolicyDeniedException when a project outside the
     *         server-resolved scope was named
     */
    private TenantScope authorize(TenantContext caller, MaintenanceCommand command) {
        if (caller == null) {
            throw new IllegalStateException(
                "A maintenance command requires a resolved TenantContext; refusing to run unscoped.");
        }
        if (caller.userId() == null) {
            // Every command in this catalogue attributes its action to a user id, so a machine
            // principal would produce an unattributable audit row. The schema would refuse it; this
            // refuses earlier with a message that names the reason.
            //
            // This check runs BEFORE the permission check on purpose. Every maintenance permission is
            // a human-role permission, and a machine principal holds capabilities, never roles, so
            // the permission check would refuse every machine principal with the same generic
            // "access denied" and this branch would be unreachable. Refusing on the structural
            // reason first is strictly narrower, not more permissive, and it is the reason an
            // operator actually needs.
            throw new IllegalStateException(
                "Maintenance command " + command.wireValue() + " requires an authenticated user; a "
                    + "machine principal has no id to record as the actor.");
        }
        authorization.check(caller, command.requiredPermission(), PolicyResource.NONE);
        return guard.tenantScope(caller);
    }

    private static void requireEnabled(MaintenanceCommand command) {
        if (!command.enabled()) {
            throw new IllegalStateException(
                "Command " + command.wireValue() + " is declared disabled; the service for it must "
                    + "not be reachable.");
        }
    }

    /** A refusal with the audit row written, and its metadata key returned for lookup. */
    private MaintenanceOutcome refuse(TenantContext caller,
                                      TenantScope scope,
                                      MaintenanceCommand command,
                                      MaintenanceOutcome.Scope actionScope,
                                      String outcome,
                                      Map<String, Object> impact,
                                      String message,
                                      String recovery) {
        Map<String, Object> metadata = new LinkedHashMap<>(impact);
        metadata.put("outcome", outcome);
        metadata.put("command", command.wireValue());
        metadata.put("requiredPermission", command.requiredPermission().value());
        audit.adminAction(scope, caller, AuditWrite.builder(caller.organizationId())
            .action("maintenance.refused")
            .resourceType(actionScope.resourceType())
            .resourceId(actionScope.resourceId() == null ? null : actionScope.resourceId().toString())
            .outcome(AuditVocabularies.AuditOutcome.DENIED)
            .metadata(metadata));
        log.info("maintenance command refused command={} outcome={} by {}", command.wireValue(),
            outcome, caller.auditActorLabel());
        return MaintenanceOutcome.refused(command, actionScope, outcome, impact, message, recovery,
            List.of("outcome=" + outcome));
    }

    /** A refusal of a command that is declared-but-disabled. Same audit shape. */
    private MaintenanceOutcome refuseDisabled(TenantContext caller,
                                              TenantScope scope,
                                              MaintenanceCommand command,
                                              MaintenanceOutcome.Scope actionScope,
                                              String message) {
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("enabled", "false");
        impact.put("destructive", String.valueOf(command.destructive()));
        impact.put("removedRows", "0");
        if (command == MaintenanceCommand.PURGE_RETENTION) {
            impact.put("legalHoldGate", legalHolds.gate(caller, actionScope.projectId())
                .refusalReason());
        }
        return refuse(caller, scope, command, actionScope, "DISABLED_BY_POLICY", impact, message,
            "Nothing was written. Enabling this command is a reviewed configuration decision with a "
                + "rehearsed rollback, not an operator action.");
    }

    /** The job-integration stream row for a replay, so the queue has its own audit trail. */
    private void writeJobEvent(TenantContext caller,
                               TenantScope scope,
                               MaintenanceCommand command,
                               MaintenanceOutcome.Scope actionScope,
                               OutboxRepository.OutboxRow row,
                               QueueName queue,
                               String outcome,
                               AuditVocabularies.AuditOutcome auditOutcome,
                               String reason,
                               Map<String, Object> impact) {
        AuditWrite.Builder builder = AuditWrite.builder(caller.organizationId())
            .projectId(row.projectId())
            .action("maintenance." + command.wireValue().toLowerCase(Locale.ROOT))
            .resourceType("OutboxEvent")
            .resourceId(row.id().toString())
            .outcome(auditOutcome)
            .queueName(row.eventType())
            .jobId(row.id().toString())
            .outboxEventId(row.id())
            .detail(reason)
            .metadata(new LinkedHashMap<>(impact));
        if (auditOutcome == AuditVocabularies.AuditOutcome.FAILED) {
            builder.failureCode(reason);
        }
        audit.jobIntegration(builder.build(), AuditVocabularies.JobEventKind.JOB_REPLAYED);
        if (queue == null) {
            log.debug("replay recorded for an event naming no declared queue: {}", row.eventType());
        }
    }

    /** The clock, named once so a test can reason about "now" in a refusal message. */
    static LocalDateTime now() {
        return AuditAttribution.now();
    }
}