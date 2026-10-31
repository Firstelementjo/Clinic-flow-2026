package com.firstelement.airag.approval;

import com.firstelement.airag.policy.AiPolicySnapshot;
import com.firstelement.airag.policy.AiRefusalReason;
import com.firstelement.airag.policy.AiRefusedException;
import com.firstelement.airag.tools.AiToolArgumentValidator;
import com.firstelement.airag.tools.AiToolContext;
import com.firstelement.airag.tools.AiToolInvocation;
import com.firstelement.airag.trace.AiTraceRepository;
import com.firstelement.security.TenantContext;
import com.firstelement.security.query.TenantScope;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The human approval record, and the only path by which a consequential tool may run.
 *
 * <h2>ADR 0007 Part 2, as a state machine</h2>
 *
 * <p>The AI <em>proposes</em>, a human <em>disposes</em>. This class is the proposal's storage and the
 * disposition's application. It has three operations and no fourth:
 *
 * <ul>
 *   <li>{@link #request} — the model asks. Writes an {@code ai_approvals} PENDING row. <b>Executes
 *       nothing.</b></li>
 *   <li>{@link #approve} / {@link #reject} — a human disposes. Appends an
 *       {@code ai_approval_decisions} row and transitions the mutable row.</li>
 *   <li>{@link #revoke} — an approval can be taken back, which the schema's biconditional requires to
 *       name who, when and why.</li>
 * </ul>
 *
 * <p>There is no auto-execution and no timeout-triggered execution. An expired approval is
 * {@code EXPIRED}, not a grant: {@link #consume} checks {@code expiresAt} against the session clock, so an
 * approval that sat in a queue over lunch cannot be spent in the afternoon on a slot that has since gone.
 *
 * <h2>Two records, not one: the grant and the withdrawal</h2>
 *
 * <p>Since {@code V20261006100000} the schema stores a grant and a revocation as two separate facts on one
 * row, because they are two separate questions:
 *
 * <table border="1">
 *   <caption>Columns, and the question each answers</caption>
 *   <tr><th>Columns</th><th>Question</th></tr>
 *   <tr><td>{@code decidedByUserId}, {@code decidedAt}, {@code decisionNote}</td>
 *       <td>who granted or refused this, and when</td></tr>
 *   <tr><td>{@code revokedByUserId}, {@code revokedAt}, {@code revokeReason}</td>
 *       <td>who took it back, when, and why</td></tr>
 * </table>
 *
 * <p>{@code ai_approvals_approval_check} makes the first trio mandatory on {@code APPROVED},
 * {@code REJECTED} and {@code REVOKED} and absent on {@code PENDING} and {@code EXPIRED} — so a REVOKED row
 * still answers "who granted this", and an EXPIRED row invents no decider for a request nobody decided.
 * {@link #revoke} therefore writes only the second trio, and an earlier version that cleared the first was
 * satisfying a schema defect by losing the answer to a question an incident review asks first.
 *
 * <p>Both rows remain on the append-only {@code ai_approval_decisions} as well; that is the history, and it
 * is not a substitute for the row-level record.
 *
 * <h2>Nobody approves their own request</h2>
 *
 * <p>{@code ai_approvals_not_self_approved_check} enforces it in the schema, and this class enforces it
 * before the write so the refusal is an exception naming the reason rather than a constraint violation
 * three frames down. plan.md forbids an unrestricted identity; self-approval is the same hole wearing a
 * workflow.
 *
 * <h2>The approval is bound to the arguments, not to the tool</h2>
 *
 * <p>{@link #matches} compares a stored request's argument <em>shape</em> against the invocation being
 * run. Approving "book Tuesday 10:00 with doctor X" and then running it for "Tuesday 14:00 with doctor Y"
 * would be an approval for something nobody approved, and an approval id is exactly the thing a model can
 * carry forward across turns. So a mismatch is
 * {@link AiRefusalReason#HUMAN_APPROVAL_REQUIRED} and a fresh request, and the mismatch is recorded.
 */
@Component
public class AiApprovalService {

    /** How long a request stays disposable. Bounded because an open-ended approval is a delegation. */
    public static final long DEFAULT_VALIDITY_MINUTES = 30L;

    private static final String FIND_APPROVAL = """
        select a."id", a."organizationId", a."projectId", a."aiRunId", a."action", a."actionClass",
               a."status"::text as "status", a."requestedByUserId", a."requestedByPrincipal",
               a."decidedByUserId", a."decidedAt", a."decisionNote",
               a."revokedByUserId", a."revokedAt", a."revokeReason",
               a."expiresAt", a."reason", t."argumentsRedacted"::text as "argumentsRedacted"
          from ai_approvals a
          join ai_tool_executions t on t."approvalId" = a."id"
         where a."organizationId" = :__fe_tenant_organization_id
           and a."projectId" = :__fe_tenant_project_id
           and a."id" = :approvalId
         limit 1
        """;

    private static final String TRANSITION = """
        update ai_approvals
           set "status" = cast(:status as "AiApprovalStatus"),
               "decidedByUserId" = :decidedByUserId,
               "decidedAt" = :decidedAt,
               "decisionNote" = :decisionNote,
               "updatedAt" = :updatedAt
         where "id" = :id
           and "organizationId" = :__fe_tenant_organization_id
           and "projectId" = :__fe_tenant_project_id
           and "status" = cast(:fromStatus as "AiApprovalStatus")
        """;

    /**
     * APPROVED → REVOKED.
     *
     * <p>Touches only the revocation trio. It deliberately does <b>not</b> name
     * {@code decidedAt}/{@code decidedByUserId}: a REVOKED row keeps the grant's decision, so whoever reads
     * this row still learns who approved it and when. Listing the columns and setting them to the
     * revoker's id was the earlier version, and it satisfied the old constraint by destroying the answer to
     * a different question.
     */
    private static final String REVOKE = """
        update ai_approvals
           set "status" = cast('REVOKED' as "AiApprovalStatus"),
               "revokedByUserId" = :revokedByUserId,
               "revokedAt" = :revokedAt,
               "revokeReason" = :revokeReason,
               "updatedAt" = :revokedAt
         where "id" = :id
           and "organizationId" = :__fe_tenant_organization_id
           and "projectId" = :__fe_tenant_project_id
           and "status" = cast('APPROVED' as "AiApprovalStatus")
        """;

    private final NamedParameterJdbcTemplate jdbc;
    private final AiTraceRepository trace;

    public AiApprovalService(NamedParameterJdbcTemplate jdbc, AiTraceRepository trace) {
        this.jdbc = jdbc;
        this.trace = trace;
    }

    /**
     * One approval, joined to the execution that requested it.
     *
     * <p>Carries <b>both</b> actors, and that is the point: {@link #decidedByUserId()} answers "who granted
     * or refused this" while {@link #revokedByUserId()} answers "who took it back". A single pair of actor
     * fields would have made the two events indistinguishable on the read side, which is how the grant's
     * decider went missing in the first place. {@code revokedByUserId} was absent from this projection until
     * {@code V20261006100000} made the distinction storable; a read model that cannot return it forces every
     * caller back to hand-written SQL, which is where a caller stops being tenant-scoped.
     */
    public record ApprovalRow(UUID id,
                              UUID organizationId,
                              UUID projectId,
                              UUID runId,
                              String action,
                              String actionClass,
                              String status,
                              UUID requestedByUserId,
                              String requestedByPrincipal,
                              UUID decidedByUserId,
                              LocalDateTime decidedAt,
                              String decisionNote,
                              UUID revokedByUserId,
                              LocalDateTime revokedAt,
                              String revokeReason,
                              LocalDateTime expiresAt,
                              String reason,
                              String argumentsRedacted) {
    }

    /**
     * Requests approval for a consequential action.
     *
     * <p>The action string is {@code toolName} plus the run, and the {@code argumentsRedacted} shape travels
     * on the {@code ai_tool_executions} row.
     *
     * <p><b>This method writes BOTH halves, and that is load-bearing rather than tidy.</b>
     * {@link #FIND_APPROVAL} joins {@code ai_approvals} to {@code ai_tool_executions} on {@code approvalId},
     * because the approved argument shape lives on the execution row and nowhere else. An earlier version
     * wrote only the approval and left the caller to write the execution — which meant {@link #request} on
     * its own produced an approval that {@link #require}, {@link #approve} and {@link #consume} could not
     * see: a PENDING row that no amount of approving would ever resolve, failing as "no approval in this
     * tenant". One write site is the only arrangement in which a new caller cannot get that wrong.
     *
     * <p>No values are persisted, per {@link AiToolArgumentValidator#redactedShape}.
     */
    @Transactional
    public Requested request(TenantScope scope,
                             TenantContext principal,
                             UUID runId,
                             String toolName,
                             String reason,
                             Map<String, Object> arguments,
                             Long validityMinutes) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        long minutes = validityMinutes == null || validityMinutes <= 0 ? DEFAULT_VALIDITY_MINUTES
            : validityMinutes;
        // expiresAt > requestedAt is a CHECK, and an approval with no expiry is a permanent delegation.
        LocalDateTime expires = now.plusMinutes(minutes);
        String action = toolName + "#" + runId;
        String shape = AiToolArgumentValidator.redactedShape(arguments);
        UUID approvalId = trace.requestApproval(scope, runId, action, principal.userId(),
            principal.auditActorLabel(), reason, now, expires);
        trace.recordApprovalDecision(scope, approvalId, null, "PENDING", null,
            principal.auditActorLabel(), "requested by " + principal.auditActorLabel()
                + "; arguments shape " + shape, now);
        UUID executionId = trace.writeToolExecution(scope, runId, toolName, approvableClass(), true,
            "REQUIRE_APPROVAL", null, approvalId, shape, "{}",
            "AI_AGENT", principal.userId(), null, null, now, null);
        return new Requested(approvalId, executionId);
    }

    /**
     * What one request wrote.
     *
     * @param approvalId the {@code ai_approvals} row an approver decides
     * @param executionId the {@code ai_tool_executions} row holding the approved argument shape. Returned
     *                    because the executor reports it as the tool-call record; making the caller re-derive
     *                    it would mean re-writing the row or leaving the caller with nothing
     */
    public record Requested(UUID approvalId, UUID executionId) {
    }

    /**
     * Approves a request.
     *
     * @throws AiRefusedException {@link AiRefusalReason#HUMAN_APPROVAL_REQUIRED} when the request is no
     *         longer PENDING, has expired, or the approver is the requester
     */
    @Transactional
    public void approve(TenantScope scope, TenantContext principal, UUID approvalId, String note) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        ApprovalRow row = require(scope, approvalId);
        if (!"PENDING".equals(row.status())) {
            throw AiRefusedException.of(AiRefusalReason.HUMAN_APPROVAL_REQUIRED,
                "approval " + approvalId + " is " + row.status() + ", not PENDING");
        }
        if (row.expiresAt() != null && !now.isBefore(row.expiresAt())) {
            expire(scope, approvalId, now);
            throw AiRefusedException.of(AiRefusalReason.HUMAN_APPROVAL_REQUIRED,
                "approval " + approvalId + " expired at " + row.expiresAt());
        }
        requireNotSelf(row, principal);

        int updated = jdbc.update(TRANSITION, scoped(scope, new MapSqlParameterSource()
            .addValue("id", approvalId)
            .addValue("status", "APPROVED")
            .addValue("fromStatus", "PENDING")
            .addValue("decidedByUserId", principal.userId())
            .addValue("decidedAt", now)
            // A note is NOT NULL on a REJECTED row only; an approval may be bare. It is still required
            // here because an approval nobody explained is how a queue fills with rubber stamps.
            .addValue("decisionNote", note == null || note.isBlank() ? "approved without a note" : note)
            .addValue("updatedAt", now)));
        if (updated == 0) {
            // The `status = PENDING` predicate is the compare-and-set. Two approvers racing means one gets
            // zero rows, and that is the correct answer: the decision is made once.
            throw AiRefusedException.of(AiRefusalReason.HUMAN_APPROVAL_REQUIRED,
                "approval " + approvalId + " was decided concurrently");
        }
        trace.recordApprovalDecision(scope, approvalId, "PENDING", "APPROVED", principal.userId(),
            principal.auditActorLabel(), note == null ? "approved" : note, now);
    }

    /**
     * Rejects a request.
     *
     * <p>A rejection without a note is refused by the schema's
     * {@code ai_approvals_rejection_check}: "a rejection without a reason is a decision nobody can learn
     * from". Refused here with a message that says so.
     *
     * <p><b>The decision pair names the real decider.</b> It used to be cleared, and that was a workaround
     * for a schema defect rather than a design choice: {@code ai_approvals_approval_check} read
     * {@code ("status" = 'APPROVED') = ("decidedAt" IS NOT NULL)}, so a REJECTED row could not carry a
     * {@code decidedAt}, and {@code ai_approvals_decision_pair_check} cleared {@code decidedByUserId} with
     * it. The only REJECTED row the schema admitted was one that hid who decided — which
     * {@code ai_approvals_rejection_check} simultaneously demands a record of, since it requires a reason
     * on every rejection.
     *
     * <p>{@code V20261006100000} corrected the constraint to
     * {@code ("status" IN ('APPROVED','REJECTED','REVOKED')) = ("decidedAt" IS NOT NULL)}, so a rejection
     * records its decider here again. The append-only {@code ai_approval_decisions} row written below is
     * <b>not</b> a substitute for it and is not the reason this is safe: the mutable row is what an operator
     * reads, and an approval queue that shows a rejection with no name on it is not auditable.
     */
    @Transactional
    public void reject(TenantScope scope, TenantContext principal, UUID approvalId, String note) {
        if (note == null || note.isBlank()) {
            throw AiRefusedException.of(AiRefusalReason.HUMAN_APPROVAL_REQUIRED,
                "a rejection must carry a note; the schema refuses one without a reason and so does this");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        ApprovalRow row = require(scope, approvalId);
        requireNotSelf(row, principal);
        int updated = jdbc.update(TRANSITION, scoped(scope, new MapSqlParameterSource()
            .addValue("id", approvalId)
            .addValue("status", "REJECTED")
            .addValue("fromStatus", "PENDING")
            // Both halves together: ai_approvals_decision_pair_check is a biconditional, so a decidedAt
            // without a decidedByUserId is itself a violation -- and a machine principal has no user id to
            // write, which is why this transition is only reachable by a human in the first place.
            .addValue("decidedByUserId", principal.userId())
            .addValue("decidedAt", now)
            .addValue("decisionNote", note)
            .addValue("updatedAt", now)));
        if (updated == 0) {
            throw AiRefusedException.of(AiRefusalReason.HUMAN_APPROVAL_REQUIRED,
                "approval " + approvalId + " is " + row.status() + ", not PENDING");
        }
        trace.recordApprovalDecision(scope, approvalId, "PENDING", "REJECTED", principal.userId(),
            principal.auditActorLabel(), note, now);
    }

    /**
     * Revokes an approval.
     *
     * <p>Possible only from APPROVED, which the predicate enforces. The reason is NOT NULL by biconditional:
     * "why was this permission taken back?" is the question an incident review asks first.
     *
     * <p><b>The grant's decision pair is left untouched, and that is the point.</b> A REVOKED row is an
     * approved grant that was later withdrawn, and {@code ai_approvals_revocation_check} already gives the
     * withdrawal its own record: {@code revokedAt}, {@code revokedByUserId} and {@code revokeReason}. So the
     * two events are stored separately — {@code decidedAt}/{@code decidedByUserId} answer <em>"who granted
     * this, and when"</em>, and the revoked trio answers <em>"who took it back, when, and why"</em>.
     *
     * <p>This method used to CLEAR the decision pair, because the old
     * {@code ai_approvals_approval_check} admitted a {@code decidedAt} on {@code APPROVED} only, so a revoked
     * row could not keep the grant's timestamp. Clearing it replaced "who granted this" with "who revoked
     * it": the row still satisfied the constraint and still lost the answer to the first question.
     * {@code V20261006100000} added {@code 'REVOKED'} to the decision-bearing states, which is what makes
     * both facts storable at once. {@code #REVOKE} therefore does not mention the decision pair at all.
     */
    @Transactional
    public void revoke(TenantScope scope, TenantContext principal, UUID approvalId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw AiRefusedException.of(AiRefusalReason.HUMAN_APPROVAL_REQUIRED,
                "a revocation must carry a reason");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int updated = jdbc.update(REVOKE, scoped(scope, new MapSqlParameterSource()
            .addValue("id", approvalId)
            .addValue("revokedByUserId", principal.userId())
            .addValue("revokedAt", now)
            .addValue("revokeReason", reason)));
        if (updated == 0) {
            throw AiRefusedException.of(AiRefusalReason.HUMAN_APPROVAL_REQUIRED,
                "approval " + approvalId + " is not APPROVED and cannot be revoked");
        }
        trace.recordApprovalDecision(scope, approvalId, "APPROVED", "REVOKED", principal.userId(),
            principal.auditActorLabel(), reason, now);
    }

    /**
     * Whether an approval may be spent right now.
     *
     * <p>Four conditions, all necessary: APPROVED, not expired, same run, and
     * {@link #matches} on the argument shape. Anything else is a refusal rather than a warning, because the
     * action behind an unverified approval is an external side effect.
     */
    public boolean consume(TenantScope scope, UUID approvalId, UUID runId, String toolName,
                           Map<String, Object> arguments, LocalDateTime now) {
        ApprovalRow row = require(scope, approvalId);
        if (!"APPROVED".equals(row.status())) {
            return false;
        }
        if (row.expiresAt() != null && !now.isBefore(row.expiresAt())) {
            return false;
        }
        if (row.runId() != null && !row.runId().equals(runId)) {
            return false;
        }
        if (!row.action().startsWith(toolName + "#")) {
            return false;
        }
        return matches(row.argumentsRedacted(), arguments);
    }

    /**
     * Whether a stored argument shape covers an invocation's shape.
     *
     * <p>Compared on the redacted shape — names and types — because that is what is stored. A stored shape
     * of {@code {"doctorId":"uuid","day":"date"}} therefore matches any invocation with those two
     * arguments and their types, which is <em>weaker</em> than comparing values. This is stated rather than
     * hidden: the binding that actually protects a specific slot is that the approved
     * {@code ai_tool_executions} row is the one the executor writes and the approver read. Phase 12, when a
     * tool performs, must record the approved values on the approval and compare those.
     */
    boolean matches(String storedShapeJson, Map<String, Object> arguments) {
        if (storedShapeJson == null || storedShapeJson.isBlank()) {
            // No recorded shape means nothing was approved about the arguments. Treated as no match, so an
            // approval row written by an older build cannot be spent by this one.
            return false;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> stored = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(storedShapeJson, Map.class);
            return stored != null
                && stored.equals(AiToolArgumentValidator.redactedShapeOf(arguments));
        } catch (Exception e) {
            // Fails closed: an approval whose bound arguments cannot be compared is not spendable.
            return false;
        }
    }

    /**
     * Marks a lapsed request EXPIRED.
     *
     * <p>Separate from {@link #reject} because it is nobody's decision: an approval that timed out was
     * neither approved nor refused, and recording it as either would put a decision in the history that no
     * human made.
     *
     * <p><b>The write is {@code REQUIRES_NEW} and it lives in {@link AiTraceRepository}, not here, and that
     * is load-bearing.</b> {@link #approve} calls this method and then throws; because a {@code RuntimeException}
     * rolls the caller's transaction back, a self-invoked {@code expire()} writing on this class's own
     * connection would be undone by the very refusal that called it — leaving a long-expired row PENDING
     * forever, so every later {@code approve} would keep re-reporting "expired" while the status never
     * changed. The transition has to commit independently of the decision that observed it, which is the
     * same reason every write in {@code AiTraceRepository} is {@code REQUIRES_NEW}.
     */
    public void expire(TenantScope scope, UUID approvalId, LocalDateTime now) {
        trace.markApprovalExpired(scope, approvalId, now);
    }

    /** The approval, or a refusal. Never a partial row. */
    public ApprovalRow require(TenantScope scope, UUID approvalId) {
        List<ApprovalRow> found = jdbc.query(FIND_APPROVAL,
            scoped(scope, new MapSqlParameterSource().addValue("approvalId", approvalId)),
            (rs, rowNum) -> new ApprovalRow(
                rs.getObject("id", UUID.class),
                rs.getObject("organizationId", UUID.class),
                rs.getObject("projectId", UUID.class),
                rs.getObject("aiRunId", UUID.class),
                rs.getString("action"),
                rs.getString("actionClass"),
                rs.getString("status"),
                rs.getObject("requestedByUserId", UUID.class),
                rs.getString("requestedByPrincipal"),
                rs.getObject("decidedByUserId", UUID.class),
                rs.getObject("decidedAt", LocalDateTime.class),
                rs.getString("decisionNote"),
                rs.getObject("revokedByUserId", UUID.class),
                rs.getObject("revokedAt", LocalDateTime.class),
                rs.getString("revokeReason"),
                rs.getObject("expiresAt", LocalDateTime.class),
                rs.getString("reason"),
                rs.getString("argumentsRedacted")));
        return found.stream().findFirst().orElseThrow(
            () -> AiRefusedException.of(AiRefusalReason.HUMAN_APPROVAL_REQUIRED,
                "no approval " + approvalId + " in this tenant and project"));
    }

    /**
     * The class of action an approval would cover.
     *
     * <p>{@code ai_approvals."actionClass"} is CHECK-constrained to {@code 'CONSEQUENTIAL'}, so this method
     * cannot return anything else: there is no approval path for an answerable or forbidden action, and the
     * constraint makes that unrepresentable rather than merely discouraged.
     */
    public String approvableClass() {
        return AiPolicySnapshot.ActionClass.CONSEQUENTIAL.name();
    }

    private static void requireNotSelf(ApprovalRow row, TenantContext principal) {
        if (row.requestedByUserId() != null && row.requestedByUserId().equals(principal.userId())) {
            throw AiRefusedException.of(AiRefusalReason.HUMAN_APPROVAL_REQUIRED,
                "an approval cannot be granted by the user who requested it");
        }
    }

    private static MapSqlParameterSource scoped(TenantScope scope, MapSqlParameterSource source) {
        MapSqlParameterSource merged = new MapSqlParameterSource();
        if (source.getValues() != null) {
            source.getValues().forEach((name, value) -> {
                if (com.firstelement.security.query.ScopedWhere.ORGANIZATION_PARAM.equals(name)
                    || com.firstelement.security.query.ScopedWhere.PROJECT_PARAM.equals(name)) {
                    throw new IllegalArgumentException("'" + name
                        + "' is reserved for the tenant filter and cannot be supplied by a caller");
                }
                merged.addValue(name, value);
            });
        }
        merged.addValue(com.firstelement.security.query.ScopedWhere.ORGANIZATION_PARAM,
            scope.organizationId());
        if (scope.isProjectScoped()) {
            merged.addValue(com.firstelement.security.query.ScopedWhere.PROJECT_PARAM, scope.projectId());
        }
        return merged;
    }

    /** The invocation an approval would authorise, for a caller's own assembly. */
    public static AiToolInvocation withApproval(AiToolInvocation invocation, UUID approvalId) {
        return invocation.approved(approvalId);
    }

    /** The tool context an approved invocation runs in. Exposed so a caller cannot build its own. */
    public static AiToolContext contextFor(TenantContext principal, TenantScope scope,
                                           AiPolicySnapshot snapshot, UUID runId, UUID agentVersionId,
                                           Map<String, Object> arguments, boolean dryRun) {
        return new AiToolContext(principal, scope, snapshot, runId, agentVersionId, arguments, dryRun);
    }
}