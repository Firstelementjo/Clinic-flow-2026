package com.firstelement.inbox.service;

import com.firstelement.crm.api.ConversationDetail;
import com.firstelement.crm.api.ConversationSummary;
import com.firstelement.crm.api.Timestamps;
import com.firstelement.crm.audit.CrmAuditRecord;
import com.firstelement.crm.audit.CrmAuditWriter;
import com.firstelement.crm.domain.ConversationMode;
import com.firstelement.crm.domain.ConversationPriority;
import com.firstelement.crm.domain.ConversationStatus;
import com.firstelement.crm.error.ResourceNotFoundException;
import com.firstelement.crm.error.ValidationFailedException;
import com.firstelement.crm.error.VersionConflictException;
import com.firstelement.crm.persistence.ConversationRepository;
import com.firstelement.crm.persistence.ConversationRow;
import com.firstelement.crm.persistence.EmployeeRepository;
import com.firstelement.crm.persistence.JsonCodec;
import com.firstelement.crm.persistence.MessageRepository;
import com.firstelement.crm.query.CrmQueryScope;
import com.firstelement.crm.timeline.ConversationTimelineStore;
import com.firstelement.crm.timeline.CrmDomainEvent;
import com.firstelement.inbox.api.AssignConversationRequest;
import com.firstelement.inbox.api.ChangeModeRequest;
import com.firstelement.inbox.api.CustomerTranscript;
import com.firstelement.inbox.api.ReplyResult;
import com.firstelement.inbox.api.SendMessageRequest;
import com.firstelement.inbox.api.UpdateConversationRequest;
import com.firstelement.security.TenantContext;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.query.TenantQueryGuard;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The conversation state machine, replies, assignment, and the internal thread view.
 *
 * <h2>Behaviour ported from {@code apps/api/src/conversations/conversations.service.ts}</h2>
 *
 * <ul>
 *   <li>Every write path loads the conversation <em>and</em> proves the caller may write to its
 *       project in the same step, so no path can forget.</li>
 *   <li>A reply moves the counters and the row in one transaction; the row is written before any
 *       delivery is attempted and starts {@code PENDING}.</li>
 *   <li>An assignment checks eligibility and capacity, then <em>recounts</em> both the previous
 *       and the new owner's workload.</li>
 *   <li>A status change recounts the assignee's workload only when the status actually moved.</li>
 *   <li>Closing resolves and unassigns in one update and publishes the resolved event.</li>
 * </ul>
 *
 * <h2>Optimistic concurrency: three different answers to three different races</h2>
 *
 * <table>
 *   <caption>Collision policy</caption>
 *   <tr><th>Operation</th><th>Token</th><th>Collision behaviour</th></tr>
 *   <tr><td>status / priority / subject</td><td>{@code updatedAt} via {@code If-Match}</td>
 *       <td>409 {@code VERSION_CONFLICT}</td></tr>
 *   <tr><td>assignment</td><td>{@code updatedAt} via {@code If-Match}</td>
 *       <td>409 {@code VERSION_CONFLICT}</td></tr>
 *   <tr><td>mode (AI⇄HUMAN)</td><td>{@code modeVersion}, always required</td>
 *       <td>409 {@code VERSION_CONFLICT}</td></tr>
 *   <tr><td>reply</td><td>{@code Idempotency-Key}</td>
 *       <td>replayed, not written twice</td></tr>
 * </table>
 *
 * <p>A reply deliberately has no version check. Two employees answering the same customer
 * simultaneously is normal, and refusing the second reply would lose a customer communication.
 * The race that matters — the same send arriving twice — is handled by the idempotency key and
 * the unique index, and a reply that finds the thread has moved reports
 * {@link ReplyResult#collisionDetected()} so the UI can tell the employee.
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    /** The legacy cap on the transcript and on the timeline returned with a thread. */
    static final int THREAD_LIMIT = 300;

    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final EmployeeRepository employees;
    private final ConversationTimelineStore timeline;
    private final JsonCodec json;
    private final TenantQueryGuard guard;
    private final CrmAuditWriter audit;

    public ConversationService(ConversationRepository conversations,
                               MessageRepository messages,
                               EmployeeRepository employees,
                               ConversationTimelineStore timeline,
                               JsonCodec json,
                               TenantQueryGuard guard,
                               CrmAuditWriter audit) {
        this.conversations = conversations;
        this.messages = messages;
        this.employees = employees;
        this.timeline = timeline;
        this.json = json;
        this.guard = guard;
        this.audit = audit;
    }

    // ------------------------------------------------------------------
    // reads
    // ------------------------------------------------------------------

    @RequirePermission(Permission.CONVERSATION_READ)
    public ConversationDetail getById(TenantContext context, UUID conversationId) {
        return getView(context, conversationId).detail();
    }

    /**
     * The thread plus its concurrency token.
     *
     * <p>The legacy thread body has no {@code updatedAt} field, so the version is returned beside
     * the body rather than inside it. The controller puts it in an {@code ETag}, which is where a
     * concurrency token belongs and where a strict legacy client ignores it.
     */
    @RequirePermission(Permission.CONVERSATION_READ)
    public ThreadView getView(TenantContext context, UUID conversationId) {
        CrmQueryScope scope = CrmQueryScope.accessibleProjects(context);
        ConversationRow row = requireConversation(context, conversationId);

        List<ConversationDetail.MessageView> transcript = messages
            .forConversation(scope, conversationId, THREAD_LIMIT).stream()
            .map(message -> new ConversationDetail.MessageView(
                message.id(),
                message.direction(),
                message.authorType(),
                message.authorFullName(),
                message.body(),
                message.deliveryStatus(),
                message.failureReason(),
                Timestamps.toIso(message.sentAt())))
            .toList();

        List<ConversationDetail.ConversationEventView> history = timeline
            .history(scope, conversationId, THREAD_LIMIT).stream()
            .map(event -> new ConversationDetail.ConversationEventView(
                event.id(),
                event.type().name(),
                event.actorType().name(),
                // The legacy projection hard-coded this to null; resolving it would put employee
                // names on a list the customer-facing surface does not use.
                null,
                json.readMap(event.payload()),
                Timestamps.toIso(event.occurredAt())))
            .toList();

        return new ThreadView(toDetail(row, transcript, history), row.updatedAt());
    }

    /** A thread and the version a mutation must quote. */
    public record ThreadView(ConversationDetail detail, LocalDateTime version) {
    }

    /**
     * The customer-facing transcript.
     *
     * <p>A separate query from {@link #getById}, not a filtered copy of it: the exclusions are
     * part of the {@code WHERE}/{@code SELECT} so no future message field can leak by default.
     */
    @RequirePermission(Permission.CONVERSATION_READ)
    public CustomerTranscript transcript(TenantContext context, UUID conversationId) {
        CrmQueryScope scope = CrmQueryScope.accessibleProjects(context);
        ConversationRow row = requireConversation(context, conversationId);

        List<CustomerTranscript.Entry> entries = new ArrayList<>();
        for (MessageRepository.MessageRow message
            : messages.customerVisibleTranscript(scope, conversationId, THREAD_LIMIT)) {
            entries.add(new CustomerTranscript.Entry(
                message.id(),
                message.direction(),
                message.authorType(),
                CustomerTranscript.Entry.labelFor(message.authorType()),
                message.body(),
                Timestamps.toIso(message.sentAt())));
        }

        return new CustomerTranscript(
            row.id(),
            row.ticketNumber(),
            row.channel(),
            row.status(),
            row.subject(),
            List.copyOf(entries));
    }

    // ------------------------------------------------------------------
    // reply
    // ------------------------------------------------------------------

    /**
     * An employee reply.
     *
     * <h2>Persist first, send second</h2>
     *
     * <p>The row is written in this transaction and starts {@code PENDING}; Phase 5's dispatcher
     * moves it to {@code SENT} or {@code FAILED}. A send that succeeded while the write failed
     * would leave the customer holding a reply the CRM has no record of.
     *
     * <h2>Idempotency</h2>
     *
     * <p>{@code Idempotency-Key} is stored in {@code messages.metadata} and checked before the
     * write. A replay returns the current thread with {@link ReplyResult#replayed()} set and
     * writes nothing, so a double-clicked Send cannot send a customer two identical messages.
     *
     * <h2>Collision reporting</h2>
     *
     * <p>{@code expectedMessageCount} lets a client detect that the thread moved between its read
     * and its send. The reply is still written — dropping it would lose a customer
     * communication — and {@link ReplyResult#collisionDetected()} tells the UI so the employee
     * can re-read before continuing.
     */
    @RequirePermission(Permission.CONVERSATION_REPLY)
    @Transactional
    public ReplyResult reply(TenantContext context,
                             UUID conversationId,
                             SendMessageRequest request,
                             String idempotencyKey,
                             Integer expectedMessageCount,
                             String ipAddress) {
        ConversationRow conversation = requireConversation(context, conversationId);
        CrmQueryScope scope = CrmQueryScope.forProject(guard, context, conversation.projectId());
        validateBody(request);

        boolean collisionDetected = expectedMessageCount != null
            && expectedMessageCount != conversation.messageCount();

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<MessageRepository.MessageRow> replay =
                messages.findByIdempotencyKey(scope, conversationId, idempotencyKey);
            if (replay.isPresent()) {
                log.debug("idempotent reply replay conversation={} message={}",
                    conversationId, replay.get().id());
                return new ReplyResult(getById(context, conversationId), true, collisionDetected,
                    replay.get().id(), conversation.updatedAt());
            }
        }

        LocalDateTime now = LocalDateTime.now();
        UUID messageId = UUID.randomUUID();
        messages.insertOutbound(scope, messageId, conversationId,
            com.firstelement.crm.domain.ChannelType.fromValue(conversation.channel()),
            request.body().trim(), context.userId(), idempotencyKey, now);
        conversations.applyReplyCounters(scope, conversationId, now, context.userId());

        publishMessageCreated(context, conversation, messageId, now);
        audit.record(CrmAuditRecord.of(context.organizationId(), conversation.projectId(),
            context.userId(), CrmAuditRecord.EMPLOYEE_REPLIED, "Conversation", conversationId,
            java.util.Map.of("messageId", messageId.toString()), ipAddress));

        return new ReplyResult(getById(context, conversationId), false, collisionDetected,
            messageId, conversation.updatedAt());
    }

    // ------------------------------------------------------------------
    // update
    // ------------------------------------------------------------------

    /**
     * Applies a status / priority / subject change.
     *
     * <p>{@code resolvedAt} is stamped only on the first transition into {@code RESOLVED}, and
     * {@code closedAt} only when the target is {@code CLOSED}. Both rules are the legacy ones.
     */
    @RequirePermission(Permission.CONVERSATION_RESOLVE)
    @Transactional
    public ConversationDetail update(TenantContext context,
                                     UUID conversationId,
                                     UpdateConversationRequest request,
                                     LocalDateTime expectedUpdatedAt,
                                     String ipAddress) {
        ConversationRow conversation = requireConversation(context, conversationId);
        CrmQueryScope scope = CrmQueryScope.forProject(guard, context, conversation.projectId());
        validateUpdate(request);

        ConversationStatus previousStatus = ConversationStatus.fromValue(conversation.status());
        boolean resolving = request.status() == ConversationStatus.RESOLVED
            && previousStatus != ConversationStatus.RESOLVED;
        LocalDateTime now = LocalDateTime.now();

        int changed = conversations.updateState(scope, conversationId, expectedUpdatedAt,
            request.status(), request.priority(), request.subject(), request.subjectProvided(),
            resolving ? now : null,
            request.status() == ConversationStatus.CLOSED ? now : null,
            now);
        if (changed == 0) {
            throw new VersionConflictException("Conversation", Timestamps.toIso(expectedUpdatedAt),
                Timestamps.toIso(conversation.updatedAt()));
        }

        // Workload drives routing, so it must not drift when a conversation moves between
        // active and inactive states. Recounting beats decrementing.
        if (conversation.assignedEmployeeId() != null && request.status() != null
            && request.status() != previousStatus) {
            employees.recountActiveConversations(context, conversation.assignedEmployeeId(), now);
        }

        if (resolving) {
            publishResolved(context, conversation, now);
            audit.record(CrmAuditRecord.of(context.organizationId(), conversation.projectId(),
                context.userId(), CrmAuditRecord.EMPLOYEE_RESOLVED_CONVERSATION, "Conversation",
                conversationId, java.util.Map.of(), ipAddress));
        } else {
            timeline.append(CrmDomainEvent.updated(context.organizationId(),
                conversation.projectId(), conversationId,
                request.status() == null ? null : request.status().name(),
                request.priority() == null ? null : request.priority().name(),
                previousStatus.name(), now, context.userId()));
        }

        return getById(context, conversationId);
    }

    // ------------------------------------------------------------------
    // assignment
    // ------------------------------------------------------------------

    /**
     * Assigns or unassigns a conversation.
     *
     * <p>An assignee must belong to this organization <em>and</em> have a membership in the
     * conversation's project — an id alone proves neither — and must be under their per-agent
     * conversation limit. A reassignment to the same employee skips the capacity test, because
     * from the load's perspective it is a no-op.
     */
    @RequirePermission(Permission.CONVERSATION_ASSIGN)
    @Transactional
    public ConversationDetail assign(TenantContext context,
                                     UUID conversationId,
                                     AssignConversationRequest request,
                                     LocalDateTime expectedUpdatedAt,
                                     String ipAddress) {
        ConversationRow conversation = requireConversation(context, conversationId);
        CrmQueryScope scope = CrmQueryScope.forProject(guard, context, conversation.projectId());
        LocalDateTime now = LocalDateTime.now();

        UUID requestedEmployee = request == null ? null : request.employeeId();
        if (requestedEmployee != null) {
            EmployeeRepository.EmployeeRow eligible =
                employees.findEligibleAssignee(context, conversation.projectId(), requestedEmployee)
                    .orElseThrow(() -> new ValidationFailedException(
                        "That employee is not a member of this project"));

            if (!requestedEmployee.equals(conversation.assignedEmployeeId())
                && eligible.atCapacity()) {
                throw new ValidationFailedException("That agent has reached their conversation limit");
            }
        }

        int changed = conversations.setAssignee(scope, conversationId, expectedUpdatedAt,
            requestedEmployee, now);
        if (changed == 0) {
            throw new VersionConflictException("Conversation", Timestamps.toIso(expectedUpdatedAt),
                Timestamps.toIso(conversation.updatedAt()));
        }

        // Both sides of a manual reassignment need their workload refreshed, or routing will
        // keep believing the previous owner is busy. `List.of` is avoided because both sides are
        // legitimately null — the common cases are an unassign and a first assignment.
        for (UUID employeeId : Arrays.asList(conversation.assignedEmployeeId(), requestedEmployee)) {
            if (employeeId != null) {
                employees.recountActiveConversations(context, employeeId, now);
            }
        }

        timeline.append(CrmDomainEvent.assigned(context.organizationId(),
            conversation.projectId(), conversationId, requestedEmployee,
            conversation.assignedEmployeeId(), "MANUAL", now, context.userId()));

        audit.record(CrmAuditRecord.of(context.organizationId(), conversation.projectId(),
            context.userId(), CrmAuditRecord.EMPLOYEE_ASSIGNED_CONVERSATION, "Conversation",
            conversationId,
            java.util.Map.of("employeeId", requestedEmployee == null ? "none"
                : requestedEmployee.toString()), ipAddress));

        return getById(context, conversationId);
    }

    /**
     * Resolves the conversation and releases it back to the queue.
     *
     * <p>The legacy {@code closeAndUnassign}: one update sets {@code RESOLVED}, stamps
     * {@code resolvedAt}, clears the assignee and records the actor as the last replier.
     */
    @RequirePermission(Permission.CONVERSATION_RESOLVE)
    @Transactional
    public ConversationDetail closeAndUnassign(TenantContext context,
                                               UUID conversationId,
                                               LocalDateTime expectedUpdatedAt,
                                               String ipAddress) {
        ConversationRow conversation = requireConversation(context, conversationId);
        CrmQueryScope scope = CrmQueryScope.forProject(guard, context, conversation.projectId());
        LocalDateTime now = LocalDateTime.now();

        int changed = conversations.updateState(scope, conversationId, expectedUpdatedAt,
            ConversationStatus.RESOLVED, null, null, false, now, null, now);
        if (changed == 0) {
            throw new VersionConflictException("Conversation", Timestamps.toIso(expectedUpdatedAt),
                Timestamps.toIso(conversation.updatedAt()));
        }
        int assigned = conversations.setAssignee(scope, conversationId, null, null, now);
        if (assigned == 0) {
            throw new VersionConflictException("Conversation", Timestamps.toIso(expectedUpdatedAt),
                Timestamps.toIso(conversation.updatedAt()));
        }

        if (conversation.assignedEmployeeId() != null) {
            employees.recountActiveConversations(context, conversation.assignedEmployeeId(), now);
        }

        publishResolved(context, conversation, now);
        // Releasing the assignee is itself an unassignment the queue has to explain.
        timeline.append(CrmDomainEvent.assigned(context.organizationId(),
            conversation.projectId(), conversationId, null, conversation.assignedEmployeeId(),
            "MANUAL", now, context.userId()));

        audit.record(CrmAuditRecord.of(context.organizationId(), conversation.projectId(),
            context.userId(), CrmAuditRecord.EMPLOYEE_RESOLVED_CONVERSATION, "Conversation",
            conversationId, java.util.Map.of(), ipAddress));

        return getById(context, conversationId);
    }

    // ------------------------------------------------------------------
    // mode (human takeover)
    // ------------------------------------------------------------------

    /**
     * The atomic AI⇄HUMAN transition.
     *
     * <p>The write is conditional on both the expected mode and {@code modeVersion}, exactly as
     * {@code ai-dispatch.ts} and {@code escalation-engine.ts} defined it: an escalation and a
     * manual takeover racing each other cannot both win, and a takeover cannot be overwritten by
     * an AI reply that was already in flight.
     *
     * <p>{@code modeVersion} is required rather than optional. Unlike {@code updatedAt}, it is
     * the guard the legacy code actually used for this transition, and a takeover without it
     * would be the un-guarded write the legacy code went out of its way to avoid.
     */
    @RequirePermission(Permission.CONVERSATION_TAKE_OVER)
    @Transactional
    public ConversationDetail changeMode(TenantContext context,
                                         UUID conversationId,
                                         ChangeModeRequest request,
                                         String ipAddress) {
        return changeModeInternal(context, conversationId, request, Permission.CONVERSATION_TAKE_OVER,
            ipAddress);
    }

    /** The same guarded transition, for the return-to-AI permission. */
    @RequirePermission(Permission.CONVERSATION_RETURN_TO_AI)
    @Transactional
    public ConversationDetail returnToAi(TenantContext context,
                                         UUID conversationId,
                                         ChangeModeRequest request,
                                         String ipAddress) {
        return changeModeInternal(context, conversationId, request,
            Permission.CONVERSATION_RETURN_TO_AI, ipAddress);
    }

    private ConversationDetail changeModeInternal(TenantContext context,
                                                  UUID conversationId,
                                                  ChangeModeRequest request,
                                                  Permission required,
                                                  String ipAddress) {
        if (request == null || request.mode() == null || request.modeVersion() == null) {
            throw new ValidationFailedException("mode and modeVersion are required");
        }
        if (request.mode() == ConversationMode.HUMAN
            && required != Permission.CONVERSATION_TAKE_OVER) {
            throw new ValidationFailedException(
                "Returning to AI cannot also set the mode to HUMAN");
        }

        ConversationRow conversation = requireConversation(context, conversationId);
        CrmQueryScope scope = CrmQueryScope.forProject(guard, context, conversation.projectId());

        ConversationMode expectedMode = ConversationMode.fromValue(conversation.mode());
        if (expectedMode == request.mode()) {
            // Already there. Not a collision: the desired end state is already true, so this is
            // idempotent and a worker retry must not fail.
            return getById(context, conversationId);
        }

        LocalDateTime now = LocalDateTime.now();
        int changed = conversations.changeMode(scope, conversationId, expectedMode,
            request.modeVersion(), request.mode(),
            // The legacy transitions also moved status to PENDING: a thread someone just took
            // over is waiting on a person, not on the AI.
            ConversationStatus.PENDING, null, now);
        if (changed == 0) {
            ConversationRow current = requireConversation(context, conversationId);
            throw new VersionConflictException("Conversation", request.modeVersion(),
                current.modeVersion());
        }

        timeline.append(CrmDomainEvent.modeChanged(context.organizationId(),
            conversation.projectId(), conversationId, expectedMode.name(), request.mode().name(),
            "MANUAL", now, context.userId()));

        if (request.mode() == ConversationMode.HUMAN) {
            audit.record(CrmAuditRecord.of(context.organizationId(), conversation.projectId(),
                context.userId(), CrmAuditRecord.EMPLOYEE_TOOK_CONVERSATION, "Conversation",
                conversationId, java.util.Map.of("modeVersion", request.modeVersion()), ipAddress));
        }

        return getById(context, conversationId);
    }

    /**
     * The case close/reopen state transition.
     *
     * <p>This is the only place a status is moved to {@code CLOSED} or back to {@code OPEN},
     * which is why the case controller delegates to it instead of writing the row. It stamps
     * {@code closedAt} on close, clears {@code resolvedAt} on reopen, recounts the assignee's
     * workload, and writes the timeline entry — the same effects {@link #update} performs for an
     * ordinary status change.
     *
     * @param action {@code CLOSED} or {@code OPEN}
     */
@RequirePermission(Permission.CONVERSATION_RESOLVE)
@Transactional
public com.firstelement.crm.api.ConversationDetail updateCaseState(TenantContext context,
                                                                    UUID conversationId,
                                                                    String action,
                                                                    LocalDateTime expectedUpdatedAt) {
    ConversationStatus target = ConversationStatus.valueOf(action);
    ConversationRow conversation = requireConversation(context, conversationId);
    CrmQueryScope scope = CrmQueryScope.forProject(guard, context, conversation.projectId());
    ConversationStatus previousStatus = ConversationStatus.fromValue(conversation.status());
    LocalDateTime now = LocalDateTime.now();

    if (target == ConversationStatus.CLOSED) {
        int changed = conversations.updateState(scope, conversationId, expectedUpdatedAt,
            ConversationStatus.CLOSED, null, null, false, null, now, now);
        if (changed == 0) {
            throw new VersionConflictException("Conversation", Timestamps.toIso(expectedUpdatedAt),
                Timestamps.toIso(conversation.updatedAt()));
        }
    } else {
        // Reopening clears both stamps: a reopened case is not closed and not resolved.
        int changed = conversations.updateState(scope, conversationId, expectedUpdatedAt,
            ConversationStatus.OPEN, null, null, false, null, null, now);
        if (changed == 0) {
            throw new VersionConflictException("Conversation", Timestamps.toIso(expectedUpdatedAt),
                Timestamps.toIso(conversation.updatedAt()));
        }
        conversations.clearResolvedAt(scope, conversationId, null, now);
    }

    if (conversation.assignedEmployeeId() != null && previousStatus != target) {
        employees.recountActiveConversations(context, conversation.assignedEmployeeId(), now);
    }

    timeline.append(CrmDomainEvent.updated(context.organizationId(), conversation.projectId(),
        conversationId, target.name(), null, previousStatus.name(), now, context.userId()));

    return getById(context, conversationId);
}

// ------------------------------------------------------------------
    // projections
    // ------------------------------------------------------------------

    /** The thread header plus its transcript and history. */
    public ConversationDetail toDetail(ConversationRow row,
                                       List<ConversationDetail.MessageView> transcript,
                                       List<ConversationDetail.ConversationEventView> history) {
        ConversationSummary summary = toSummary(row);
        return new ConversationDetail(
            summary.id(), summary.projectId(), summary.projectName(), summary.ticketNumber(),
            summary.channel(), summary.status(), summary.mode(), row.modeVersion(), summary.priority(),
            summary.subject(), summary.customer(), summary.assignedEmployee(),
            summary.lastMessagePreview(), summary.messageCount(), summary.lastMessageAt(),
            transcript, history);
    }

    /** The inbox row projection, identical to the legacy {@code toSummary}. */
    public static ConversationSummary toSummary(ConversationRow row) {
        return new ConversationSummary(
            row.id(),
            row.projectId(),
            row.projectName(),
            row.ticketNumber(),
            row.channel(),
            row.status(),
            row.mode(),
            row.priority(),
            row.subject(),
            new ConversationSummary.CustomerRef(row.customerId(), row.customerCxId(),
                row.customerDisplayName()),
            row.assignedEmployeeId() == null ? null
                : new ConversationSummary.AssignedEmployee(row.assignedEmployeeId(),
                    row.assigneeFullName()),
            ConversationSummary.preview(row.lastMessageBody()),
            row.messageCount(),
            Timestamps.toIso(row.lastMessageAt()));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * Loads a conversation for mutation and, in the same step, proves the caller may write to its
     * project. Every write path goes through here so none of them can forget.
     */
    ConversationRow requireConversation(TenantContext context, UUID conversationId) {
        CrmQueryScope readable = CrmQueryScope.accessibleProjects(context);
        ConversationRow row = conversations.findInScope(readable, conversationId)
            .orElseThrow(() -> new ResourceNotFoundException("Conversation"));
        // Throws (and publishes a security event) when the caller's scope does not include the row's
        // project, which cannot happen for a row just read through that same scope; it is the
        // explicit assertion that keeps the invariant true if the read path ever changes.
        guard.assertRowScope(context, row.organizationId(), row.projectId());
        return row;
    }

    void publishMessageCreated(TenantContext context,
                               ConversationRow conversation,
                               UUID messageId,
                               LocalDateTime now) {
        timeline.append(CrmDomainEvent.messageCreated(context.organizationId(),
            conversation.projectId(), conversation.id(), conversation.customerId(), messageId,
            conversation.channel(), com.firstelement.crm.domain.MessageDirection.OUTBOUND.name(),
            com.firstelement.crm.domain.MessageAuthorType.EMPLOYEE.name(),
            0, now, context.userId()));
    }

    private void publishResolved(TenantContext context, ConversationRow conversation,
                                 LocalDateTime now) {
        timeline.append(CrmDomainEvent.resolved(context.organizationId(),
            conversation.projectId(), conversation.id(),
            com.firstelement.crm.domain.MessageAuthorType.EMPLOYEE.name(),
            conversation.messageCount(), now, context.userId()));
    }

    private static void validateBody(SendMessageRequest request) {
        if (request == null || request.body() == null || request.body().trim().isEmpty()) {
            throw new ValidationFailedException("body is required");
        }
        if (request.body().trim().length() > SendMessageRequest.MAX_BODY) {
            throw new ValidationFailedException(
                "body must be at most " + SendMessageRequest.MAX_BODY + " characters");
        }
    }

    private static void validateUpdate(UpdateConversationRequest request) {
        if (request == null) {
            throw new ValidationFailedException("A body is required");
        }
        if (request.subject() != null
            && request.subject().trim().length() > UpdateConversationRequest.MAX_SUBJECT) {
            throw new ValidationFailedException(
                "subject must be at most " + UpdateConversationRequest.MAX_SUBJECT + " characters");
        }
        if (request.status() == null && request.priority() == null && request.subject() == null) {
            throw new ValidationFailedException(
                "At least one of status, priority or subject must be provided");
        }
    }

    /** Whether a status counts as active for workload and the queue. */
    public static boolean isActive(ConversationStatus status) {
        return status == ConversationStatus.OPEN || status == ConversationStatus.PENDING;
    }

    /** Whether a priority is one the CRM stores. Kept for the case module's validation. */
    public static ConversationPriority parsePriority(String raw) {
        return ConversationPriority.fromValue(raw);
    }
}