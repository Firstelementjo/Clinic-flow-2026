package com.firstelement.automation.service;

import com.firstelement.automation.domain.WorkflowStateMachine;
import com.firstelement.automation.persistence.WorkflowCatalogRepository;
import com.firstelement.automation.persistence.WorkflowRunActionRepository;
import com.firstelement.automation.persistence.WorkflowRunRepository;
import com.firstelement.crm.error.ValidationFailedException;
import com.firstelement.crm.persistence.JsonCodec;
import com.firstelement.security.TenantContext;
import com.firstelement.security.UserRole;
import com.firstelement.security.job.TenantScopedExecutor;
import com.firstelement.security.query.ScopedWhere;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import com.firstelement.security.query.TenantQueryGuard;
import com.firstelement.security.query.TenantScope;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Starting, executing and finishing workflow runs.
 *
 * <h2>No unrestricted scheduled identity</h2>
 *
 * <p>plan.md's hard rule: "no unrestricted scheduled 'Administrator' identity: a workflow run executes
 * as a named principal with a bounded capability set, recorded per execution." This class makes that
 * structural rather than documentary:
 *
 * <ul>
 *   <li>{@link #start} <b>refuses</b> a run with no {@code principalUserId}. There is no overload that
 *       defaults one and no "system" escape hatch.</li>
 *   <li>The principal must be an active {@code users} row in <b>this</b> tenant; the run's tenant is
 *       that user's tenant, and the composite foreign keys refuse a cross-tenant
 *       {@code conversationId} even if a caller tried.</li>
 *   <li>The capability set is the version's {@code capabilityScope}, copied onto the run, and every
 *       action is checked against it — so widening the version later cannot retroactively enable what
 *       an old run was allowed to do.</li>
 *   <li>{@link #runTenantContext} reconstructs the execution scope <b>from the run row</b> and runs it
 *       through {@link TenantScopedExecutor}. A job therefore cannot inherit the submitting thread's
 *       tenant: the only tenant a run ever executes in is the one recorded on it.</li>
 * </ul>
 *
 * <h2>Loop prevention</h2>
 *
 * <p>Three layers, described in {@link WorkflowStateMachine}. This class owns two: the
 * {@code workflow_runs.(organizationId, triggerInstanceId)} unique index, so a re-fired trigger
 * inserts nothing, and the per-run {@code loopDepth}/{@code maxLoopDepth} budget, refused here with
 * the numbers that explain it.
 *
 * <h2>One attempt is one row, written once</h2>
 *
 * <p>The unique index is
 * {@code ("organizationId","workflowRunId","actionKey","attemptNumber")}, so an attempt is recorded
 * exactly once, in its terminal state. There is no "started" row to revise, which is why an attempt
 * is <em>not</em> written before it runs: the observable in-flight state of an AI send is the
 * {@code PENDING} {@code messages} row plus a {@code RUNNING} run with no abort request, and
 * {@code AiSendService} cancels both.
 *
 * <h2>Retry policy, stated once</h2>
 *
 * <p>{@code RETRYABLE} is the only outcome the engine may retry. {@code FAILED} is a business-state
 * failure — the conversation is already with a human, the policy is retired, the employee does not
 * exist — and re-running it is the "retry a non-retryable business-state failure" the hard rules
 * forbid, so {@link #replayAction} refuses one. The schema states the same distinction from the
 * other side: "failed in a way the engine may retry" is a fact about one action, so it is an
 * <em>outcome</em>, not a run status.
 */
@Service
public class WorkflowRunService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunService.class);

    /** The default per-run loop budget. Matches the column default. */
    public static final int DEFAULT_MAX_LOOP_DEPTH = 5;

    /** The action key a staged AI reply uses. */
    public static final String AI_SEND_STAGE = "AI_SEND_STAGE";

    /** The action key that publishes the delivery intent. */
    public static final String AI_SEND_CONFIRM = "AI_SEND_CONFIRM";

    /** One action's terminal result, as the caller reports it. */
    public record ActionResult(String actionKey,
                               String actionType,
                               boolean succeeded,
                               boolean retryable,
                               String errorCode,
                               String errorMessage,
                               Map<String, Object> inputs,
                               Map<String, Object> result,
                               UUID outboxEventId) {

        public ActionResult {
            if (actionKey == null || actionKey.isBlank()) {
                throw new ValidationFailedException("actionKey is required: the history and the "
                    + "explanation are keyed on it");
            }
            if (actionType == null || actionType.isBlank()) {
                throw new ValidationFailedException("actionType is required");
            }
            inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
            result = result == null ? Map.of() : Map.copyOf(result);
        }

        public static ActionResult success(String actionKey, String actionType,
                                           Map<String, Object> inputs, Map<String, Object> result) {
            return new ActionResult(actionKey, actionType, true, false, null, null, inputs, result,
                null);
        }

        /** A failure the engine may retry: a provider timeout, an expired lease. */
        public static ActionResult retryableFailure(String actionKey, String actionType,
                                                    Map<String, Object> inputs, String code,
                                                    String message) {
            return new ActionResult(actionKey, actionType, false, true, code, message, inputs,
                Map.of(), null);
        }

        /** A business-state failure. Never retried; see the class comment. */
        public static ActionResult terminalFailure(String actionKey, String actionType,
                                                   Map<String, Object> inputs, String code,
                                                   String message) {
            return new ActionResult(actionKey, actionType, false, false, code, message, inputs,
                Map.of(), null);
        }

        /** A deliberate skip: the work did not run, and that is the correct outcome. */
        public static ActionResult skipped(String actionKey, String actionType,
                                           Map<String, Object> inputs, Map<String, Object> result) {
            return new ActionResult(actionKey, actionType, false, false, null, null, inputs, result,
                null);
        }

        /** The {@code WorkflowActionOutcome} this result maps to. */
        public String outcome() {
            if (succeeded) {
                return "SUCCEEDED";
            }
            if (errorCode != null) {
                return retryable ? "RETRYABLE" : "FAILED";
            }
            return "SKIPPED";
        }
    }

    /** What starting a run produced. */
    public record StartOutcome(boolean started, UUID runId, String reason) {

        static StartOutcome skipped(String reason) {
            return new StartOutcome(false, null, reason);
        }
    }

    private final WorkflowCatalogRepository catalog;
    private final WorkflowRunRepository runs;
    private final WorkflowRunActionRepository actions;
    private final TenantQueryGuard guard;
    private final TenantScopedExecutor scopedExecutor;
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonCodec json;

    public WorkflowRunService(WorkflowCatalogRepository catalog,
                              WorkflowRunRepository runs,
                              WorkflowRunActionRepository actions,
                              TenantQueryGuard guard,
                              TenantScopedExecutor scopedExecutor,
                              NamedParameterJdbcTemplate jdbc,
                              JsonCodec json) {
        this.catalog = catalog;
        this.runs = runs;
        this.actions = actions;
        this.guard = guard;
        this.scopedExecutor = scopedExecutor;
        this.jdbc = jdbc;
        this.json = json;
    }

    // ------------------------------------------------------------------
    // starting
    // ------------------------------------------------------------------

    /**
     * Starts a run for a conversation.
     *
     * @param principalUserId  the <b>named</b> principal. Required; there is no default
     * @param triggerInstanceId the loop-prevention key, unique per tenant together with the
     *                          organizationId. A re-fired trigger returns
     *                          {@link StartOutcome#started()} {@code false} rather than duplicating
     * @param loopDepth        this run's depth in its trigger chain
     */
    @Transactional
    public StartOutcome start(TenantContext context, UUID definitionId, UUID conversationId,
                              UUID principalUserId, String triggerInstanceId, int loopDepth,
                              int maxLoopDepth, String correlationId) {
        if (principalUserId == null) {
            throw new UnnamedPrincipalException(
                "Refusing to start a workflow run with no named principal. plan.md forbids an "
                    + "unrestricted scheduled identity, and workflow_runs.principalUserId is NOT NULL "
                    + "for the same reason: 'no principal' must be unexpressible.");
        }
        if (triggerInstanceId == null || triggerInstanceId.isBlank()) {
            throw new ValidationFailedException(
                "triggerInstanceId is required: it is the loop-prevention key");
        }
        if (maxLoopDepth < 1) {
            throw new ValidationFailedException("maxLoopDepth must be >= 1");
        }
        if (loopDepth > maxLoopDepth) {
            throw new LoopBudgetExceededException(
                "Refusing to start workflow run at loop depth " + loopDepth + " with a budget of "
                    + maxLoopDepth + ". The trigger chain is looping, and the budget exists so a "
                    + "looping workflow fails once and visibly instead of running forever.");
        }

        TenantScope tenantScope = guard.tenantScope(context);
        WorkflowCatalogRepository.DefinitionRow definition =
            catalog.findDefinition(tenantScope, definitionId)
                .orElseThrow(() -> new ValidationFailedException(
                    "No workflow definition " + definitionId + " in tenant " + context.organizationId()));
        if (!definition.enabled()) {
            return StartOutcome.skipped("the workflow definition is disabled");
        }
        WorkflowCatalogRepository.VersionRow version = catalog.findActiveVersion(tenantScope, definitionId)
            .orElseThrow(() -> new ValidationFailedException(
                "This workflow has no ACTIVE version, so there is nothing to run"));
        if (version.capabilityScope() == null || version.capabilityScope().isBlank()
            || json.readList(version.capabilityScope()).isEmpty()) {
            return StartOutcome.skipped(
                "the active version declares an empty capability scope, so a run would be "
                    + "unrestricted; refusing rather than defaulting to everything");
        }

        requireActivePrincipal(tenantScope, principalUserId, context.organizationId());
        TenantScope runScope = guard.scope(narrowToProject(context, conversationProjectId(
            tenantScope, conversationId)), conversationProjectId(tenantScope, conversationId));

        Optional<UUID> started = runs.start(runScope, UUID.randomUUID(), definitionId, version.id(),
            conversationId, triggerInstanceId, principalUserId, version.capabilityScope(), loopDepth,
            maxLoopDepth, correlationId);
        if (started.isEmpty()) {
            return StartOutcome.skipped(
                "this trigger instance already has a run (loop prevention: the unique index on "
                    + "(organizationId, triggerInstanceId) refused a second run)");
        }
        log.info("workflow run started run={} definition={} version={} conversation={} principal={}",
            started.get(), definitionId, version.id(), conversationId, principalUserId);
        return new StartOutcome(true, started.get(), "started");
    }

    /**
     * Narrows the caller's scope to one project, using {@link TenantContext#withProjects}.
     *
     * <p>{@code withProjects} "never widens by accident because callers still have to pass ids that
     * already belong to this tenant", and the {@code TenantQueryGuard} call that follows is what
     * refuses a project outside the caller's server-resolved scope.
     */
    private TenantContext narrowToProject(TenantContext context, UUID projectId) {
        return context.withProjects(List.of(projectId));
    }

    /**
     * The project a conversation belongs to, resolved from the run's own tenant.
     *
     * <p>Read tenant-scoped rather than accepted from the caller, so a run cannot be started
     * against a conversation that is not in the tenant the run will execute under. The project id
     * is then what the run row is written with, which is why the run needs no payload-supplied
     * project at all.
     */
    private UUID conversationProjectId(TenantScope tenantScope, UUID conversationId) {
        return jdbc.query("""
            select c."projectId" from conversations c
             where c."organizationId" = :__fe_tenant_organization_id and c."id" = :id
            """, bind(tenantScope, Map.of("id", conversationId)),
            (rs, rowNum) -> rs.getObject("projectId", UUID.class)).stream().findFirst()
            .orElseThrow(() -> new ValidationFailedException(
                "Conversation " + conversationId + " is not in tenant "
                    + tenantScope.organizationId()));
    }

    /**
     * The principal must be an active user of this tenant.
     *
     * <p>Read through the tenant scope, so an id from another tenant is simply not found and the
     * refusal never discloses that the user exists elsewhere.
     */
    private void requireActivePrincipal(TenantScope tenantScope, UUID principalUserId,
                                        UUID organizationId) {
        Integer count = jdbc.queryForObject("""
            select count(*) from users u
             where u."organizationId" = :__fe_tenant_organization_id
               and u."id" = :id and u."isActive"
            """, bind(tenantScope, Map.of("id", principalUserId)), Integer.class);
        if (count == null || count == 0) {
            throw new UnnamedPrincipalException(
                "The named principal " + principalUserId + " is not an active user in tenant "
                    + organizationId + ". A run cannot be executed by somebody outside the tenant "
                    + "whose data it is about, and an unattributable decision is worse than none.");
        }
    }

    // ------------------------------------------------------------------
    // execution
    // ------------------------------------------------------------------

    /**
     * Reconstructs a run's execution scope <b>from the run row</b>.
     *
     * <p>This is the rule "every workflow run executes under {@code TenantScopedExecutor} with the
     * run's own tenant; a job may not inherit the submitting thread's tenant": the organizationId and
     * projectId come from {@code workflow_runs}, never from a payload or from the ambient thread. A
     * job that submits tenant B's work while running under tenant A therefore executes under B, and a
     * run that is not visible to the caller's tenant is refused here rather than read.
     */
    public TenantContext runTenantContext(TenantScope callerScope, UUID runId) {
        WorkflowRunRepository.RunRow run = runs.findRun(callerScope, runId).orElseThrow(
            () -> new ValidationFailedException(
                "No workflow run " + runId + " is visible to tenant " + callerScope.organizationId()));
        UUID principalUserId = run.principalUserId();
        String role = jdbc.queryForObject("""
            select u."role"::text from users u
             where u."organizationId" = :__fe_tenant_organization_id and u."id" = :id
            """, bind(callerScope, Map.of("id", principalUserId)), String.class);
        UserRole resolvedRole = role == null ? UserRole.EMPLOYEE : UserRole.valueOf(role);
        return TenantContext.forUser(
            UUID.nameUUIDFromBytes(("workflow-run:" + runId).getBytes(StandardCharsets.UTF_8)),
            principalUserId, run.organizationId(), resolvedRole, List.of(run.projectId()));
    }

    /** Runs {@code work} under {@link #runTenantContext}. The only supported way to execute a run. */
    public <T> T execute(UUID runId, TenantScope callerScope, java.util.function.Supplier<T> work) {
        return scopedExecutor.execute(runTenantContext(callerScope, runId), "workflow.run.execute",
            work);
    }

    /**
     * The project-scoped view of a run's own tenant.
     *
     * <p>{@code workflow_runs.projectId} is NOT NULL, so every statement that reads a run's children
     * needs a project-scoped predicate. This builds it from the run's context, whose only project is
     * the run's, and through the guard — so it cannot be used to reach a project the run does not own.
     */
    public TenantScope runScope(TenantContext runContext, UUID projectId) {
        return guard.scope(runContext, projectId);
    }

    /**
     * Walks the state machine, appending one action record per step.
     *
     * <p>Bounded twice: by {@code maxSteps} (the document's own budget, capped at
     * {@link WorkflowStateMachine#HARD_MAX_STEPS}) and by the fact that a document with a cycle cannot
     * be parsed in the first place. Each step's capability requirement is checked against the run's
     * copied scope, and a refusal is <b>recorded</b> as a terminal {@code SKIPPED}/{@code FAILED}
     * action rather than dropped — "no silent rule failure".
     *
     * @return the node ids that were attempted, in order
     */
    @Transactional
    public List<String> walk(UUID runId, TenantScope callerScope,
                            java.util.function.Function<WorkflowStateMachine.Node, ActionResult> handler) {
        TenantContext runContext = runTenantContext(callerScope, runId);
        return scopedExecutor.execute(runContext, "workflow.run.walk", () -> {
            // Read the run under the tenant-only scope first, then narrow to the run's OWN project.
            // workflow_runs.projectId is NOT NULL and every action row is scoped to it, so the walk
            // needs a project-scoped predicate to read and append its own history — but the project
            // must come from the run row rather than from a caller argument, or a walk could be
            // pointed at a project the run does not own.
            WorkflowRunRepository.RunRow run = runs.findRun(guard.tenantScope(runContext), runId)
                .orElseThrow(() -> new ValidationFailedException("run disappeared mid-walk"));
            TenantScope scope = runScope(runContext, run.projectId());
            WorkflowCatalogRepository.VersionRow version =
                catalog.findVersion(scope, run.workflowVersionId()).orElseThrow(
                    () -> new ValidationFailedException(
                        "the run's workflow version is not visible in the run's own tenant"));
            List<Object> capabilityScope = json.readList(version.capabilityScope());
            WorkflowStateMachine machine = WorkflowStateMachine.parse(
                json.readMap(version.stateMachine()), capabilityScope,
                WorkflowStateMachine.HARD_MAX_STEPS);

            List<String> granted = capabilityScope.stream().map(String::valueOf).toList();
            List<String> attempted = new ArrayList<>();
            Optional<WorkflowStateMachine.Node> node = Optional.of(machine.first());
            int steps = 0;

            while (node.isPresent()) {
                if (++steps > machine.maxSteps()) {
                    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
                    record(scope, runId, ActionResult.terminalFailure("STEP_BUDGET", "STEP_BUDGET",
                        Map.of("steps", steps, "maxSteps", machine.maxSteps()),
                        "LOOP_BUDGET_EXHAUSTED",
                        "the document allowed " + machine.maxSteps() + " steps and this run reached "
                            + steps + "; refusing to continue"), now, now);
                    break;
                }
                WorkflowStateMachine.Node current = node.get();
                WorkflowStateMachine.CapabilityCheck check =
                    machine.checkCapabilities(current.id(), granted);
                if (!check.allowed()) {
                    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
                    record(scope, runId, ActionResult.terminalFailure(current.id(), current.actionType(),
                        Map.of("capabilityScopeInForce", granted, "missing",
                            check.missingCapabilities()),
                        "CAPABILITY_NOT_GRANTED",
                        "the run's capabilityScope does not include "
                            + check.missingCapabilities()), now, now);
                    break;
                }
                LocalDateTime startedAt = LocalDateTime.now(ZoneOffset.UTC);
                ActionResult result = handler.apply(current);
                record(scope, runId, result, startedAt, LocalDateTime.now(ZoneOffset.UTC));
                attempted.add(current.id());
                if (!result.succeeded() && !"RETRYABLE".equals(result.outcome())) {
                    break;
                }
                node = machine.after(current.id());
            }
            return attempted;
        });
    }

    /**
     * Appends one action attempt.
     *
     * <p>The attempt number comes from the table, so a retry is a new row rather than an edit, and
     * the unique index turns a duplicate into a conflict rather than a double-counted history.
     *
     * <p>Narrows the caller's scope to the run's own project for the same reason {@link #timeline}
     * does: {@code workflow_run_actions.projectId} is NOT NULL, and the run row is the only place
     * the right project can come from.
     */
    public UUID record(TenantScope scope, UUID runId, ActionResult result, LocalDateTime startedAt,
                       LocalDateTime finishedAt) {
        TenantScope runScope = scopeOfRun(scope, runId);
        long millis = Duration.between(startedAt, finishedAt).toMillis();
        int durationMs = (int) Math.max(0L, Math.min(Integer.MAX_VALUE, millis));
        return actions.append(runScope, UUID.randomUUID(), runId, result.actionKey(),
            result.actionType(), actions.nextAttemptNumber(runScope, runId, result.actionKey()),
            result.outcome(), result.inputs(), result.result(), null,
            "workflow:" + result.actionKey(), result.outboxEventId(), startedAt, finishedAt,
            durationMs, result.errorCode(), result.errorMessage(), false, null, null);
    }

    /**
     * Replays a retryable action.
     *
     * <p>Refuses a non-retryable business-state failure: the original must be {@code RETRYABLE}. A
     * {@code FAILED} action fails identically on the next attempt, and re-running it is exactly the
     * "retry a non-retryable business-state failure" the hard rules forbid.
     *
     * <p>The replay row is {@code SKIPPED} — it records that a replay was authorised and names what it
     * replays; the replayed attempt itself then records its own outcome with the next attempt number.
     * The original row is never touched.
     *
     * @return the replay row's id
     */
    @Transactional
    public UUID replayAction(TenantScope scope, UUID runId, String actionKey) {
        TenantScope runScope = scopeOfRun(scope, runId);
        WorkflowRunActionRepository.ActionRow original =
            actions.latestForAction(runScope, runId, actionKey)
            .orElseThrow(() -> new ValidationFailedException(
                "No action '" + actionKey + "' in run " + runId));
        if (!"RETRYABLE".equals(original.outcome())) {
            throw new NonRetryableFailureException(
                "Action '" + actionKey + "' finished as " + original.outcome()
                    + ", which is a business-state failure and will fail identically on replay. Only "
                    + "a RETRYABLE action may be replayed; fix the cause or start a new run.");
        }
        UUID replayId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        actions.append(runScope, replayId, runId, original.actionKey(), original.actionType(),
            actions.nextAttemptNumber(runScope, runId, actionKey), "SKIPPED",
            Map.of("replayOf", original.id().toString(),
                "replayOfAttemptNumber", original.attemptNumber(),
                "replayOfOutcome", original.outcome()),
            Map.of("replayAuthorised", true), null, "workflow:replay", null, now, now, 0,
            null, "replay authorised for a retryable failure", true, original.id(), null);
        return replayId;
    }

    // ------------------------------------------------------------------
    // finishing
    // ------------------------------------------------------------------

    @Transactional
    public boolean succeed(TenantScope scope, UUID runId) {
        return runs.succeed(scope, runId) == 1;
    }

    @Transactional
    public boolean fail(TenantScope scope, UUID runId, String reason) {
        return runs.fail(scope, runId, reason) == 1;
    }

    /** Records a takeover request. Idempotent: a second request updates nothing. */
    @Transactional
    public int requestAbort(TenantScope scope, UUID conversationId, UUID humanUserId, String reason) {
        return runs.requestAbort(scope, conversationId, humanUserId, reason);
    }

    /** Confirms the abort once the in-flight work has actually been dropped. */
    @Transactional
    public boolean confirmAbort(TenantScope scope, UUID runId) {
        return runs.confirmAbort(scope, runId) == 1;
    }

    public Optional<WorkflowRunRepository.RunRow> findRun(TenantScope scope, UUID runId) {
        return runs.findRun(scope, runId);
    }

    /**
     * One run's action timeline.
     *
     * <p>Takes a caller scope and narrows it to the run's OWN project internally, so a caller cannot
     * hand this a tenant-only scope and get a "no value supplied for {@code __fe_tenant_project_id}"
     * failure: {@code workflow_run_actions.projectId} is NOT NULL, and making every caller remember
     * that is how a read model ends up unreadable.
     */
    public List<WorkflowRunActionRepository.ActionRow> timeline(TenantScope scope, UUID runId) {
        return actions.timelineForRun(scopeOfRun(scope, runId), runId);
    }

    /**
     * The project-scoped scope of a run, derived from the run row.
     *
     * <p>The run is read through the caller's scope first, so a run belonging to another tenant is
     * simply not found rather than returned under a project the caller guessed. The project comes
     * from the row, never from an argument.
     */
    private TenantScope scopeOfRun(TenantScope callerScope, UUID runId) {
        WorkflowRunRepository.RunRow run = runs.findRun(callerScope, runId).orElseThrow(
            () -> new ValidationFailedException(
                "No workflow run " + runId + " is visible to tenant " + callerScope.organizationId()));
        return new TenantScope(run.organizationId(), run.projectId());
    }

    /** The run's copied capability scope, as the recorded list. */
    public List<String> capabilityScopeOf(TenantScope scope, UUID runId) {
        return runs.findRun(scope, runId)
            .map(run -> json.readList(run.capabilityScope()).stream().map(String::valueOf).toList())
            .orElse(List.of());
    }

    /** Refused: a run with no named principal. Never defaulted. */
    public static class UnnamedPrincipalException extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        public UnnamedPrincipalException(String message) {
            super(message);
        }
    }

    /** Refused: the trigger chain exceeded its recorded budget. */
    public static class LoopBudgetExceededException extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        public LoopBudgetExceededException(String message) {
            super(message);
        }
    }

    /** Refused: a replay of a non-retryable business-state failure. */
    public static class NonRetryableFailureException extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        public NonRetryableFailureException(String message) {
            super(message);
        }
    }

    /**
     * Binds caller values together with the scope's tenant parameters.
     *
     * <p>{@link ScopedWhere#bindNamed} is the only sanctioned way: it writes the tenant values last and
     * refuses a caller parameter of the reserved name, so a payload cannot smuggle a different tenant
     * into a statement that looks scoped. Both statements in this class that read {@code users} go
     * through it rather than naming {@code organizationId} by hand, because a hand-written
     * {@code :organizationId} is exactly the shape that lets a run read another tenant's principal.
     */
    private static MapSqlParameterSource bind(TenantScope scope, Map<String, ?> values) {
        return ScopedWhere.of(scope).bindNamed(values);
    }
}
