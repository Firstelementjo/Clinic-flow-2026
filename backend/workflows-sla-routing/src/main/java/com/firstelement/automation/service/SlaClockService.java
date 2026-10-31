package com.firstelement.automation.service;

import com.firstelement.automation.domain.BusinessCalendar;
import com.firstelement.automation.domain.SlaClockEvaluator;
import com.firstelement.automation.domain.SlaPolicySnapshot;
import com.firstelement.automation.persistence.ConversationAutomationRepository;
import com.firstelement.automation.persistence.SlaRepository;
import com.firstelement.crm.error.ValidationFailedException;
import com.firstelement.security.TenantContext;
import com.firstelement.security.permission.AuthorizationService;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.query.TenantQueryGuard;
import com.firstelement.security.query.TenantScope;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The three SLA clocks, evaluated on read.
 *
 * <h2>Independent clocks over one calendar</h2>
 *
 * <p>A policy promises up to three independent budgets — first response, next response, resolution —
 * and this service evaluates all three from one policy and one calendar version. They are
 * independent because they have different anchors:
 *
 * <ul>
 *   <li>first response: conversation creation &rarr; the first reply that landed;</li>
 *   <li>next response: the last reply we sent &rarr; now;</li>
 *   <li>resolution: conversation creation &rarr; resolution (or now, if unresolved).</li>
 * </ul>
 *
 * <h2>Which calendar version applies</h2>
 *
 * <p>The version in force <b>on the date the conversation opened</b>, not the newest one. That is the
 * whole point of effective-dating: a March ticket keeps the working week that was promised to it after
 * somebody edits the calendar in October. A policy with {@code calendarId IS NULL} is an explicit
 * 24/7 choice and passes {@code null} to the evaluator.
 *
 * <h2>Pause and resume</h2>
 *
 * <p>No tick table, no per-conversation breach column: elapsed business time is derived from the
 * calendar on every read, so a clock pauses over a weekend, a holiday and a partial closure and
 * resumes with nothing to reconcile. {@code SlaClockEvaluatorTest} and
 * {@code SlaCalendarClockIntegrationTest} pin both halves.
 */
@Service
public class SlaClockService {

    /** The bound on the policies one evaluation considers. */
    public static final int POLICY_BATCH_LIMIT = 100;

    private final SlaRepository sla;
    private final ConversationAutomationRepository conversations;
    private final TenantQueryGuard guard;
    private final AuthorizationService authorization;

    public SlaClockService(SlaRepository sla,
                           ConversationAutomationRepository conversations,
                           TenantQueryGuard guard,
                           AuthorizationService authorization) {
        this.sla = sla;
        this.conversations = conversations;
        this.guard = guard;
        this.authorization = authorization;
    }

    /**
     * Evaluates every applicable policy against a conversation.
     *
     * @param now the instant to judge; passed rather than read so a batch evaluation is coherent
     * @return one assessment per applicable policy, in explicit priority order
     */
    @Transactional(readOnly = true)
    public List<SlaClockEvaluator.Assessment> assess(TenantContext context, UUID projectId,
                                                     UUID conversationId, LocalDateTime now) {
        authorization.check(context, Permission.CONVERSATION_READ);
        TenantScope scope = guard.scope(context, projectId);

        ConversationAutomationRepository.ConversationState conversation =
            conversations.find(scope, conversationId).orElseThrow(
                () -> new ValidationFailedException(
                    "Conversation " + conversationId + " is not visible to this tenant/project"));

        // §10e: fail loudly rather than pause the wrong working week.
        sla.assertNoCrossTenantCalendar(scope);

        LocalDate openedOn = toDate(conversation.createdAt());
        List<SlaRepository.PolicyRow> policies = sla.applicablePolicies(scope, projectId,
            POLICY_BATCH_LIMIT);
        String priority = conversation.priority();

        List<SlaClockEvaluator.Assessment> assessments = new java.util.ArrayList<>();
        for (SlaRepository.PolicyRow row : policies) {
            if (row.defaultForPriority() != null && !row.defaultForPriority().isBlank()
                && !row.defaultForPriority().equalsIgnoreCase(priority)) {
                continue;
            }
            SlaPolicySnapshot snapshot = SlaRepository.toSnapshot(row);
            BusinessCalendar calendar = calendarFor(scope, row, openedOn);
            assessments.add(SlaClockEvaluator.evaluate(snapshot, calendar,
                new SlaClockEvaluator.ClockStart(conversation.createdAt(),
                    lastReplyBoundary(scope, conversation), conversation.firstResponseAt(),
                    conversation.resolvedAt(), now)));
        }
        return assessments;
    }

    /**
 * The calendar version a policy used on {@code onDate}.
 *
     * <p>Three cases, in order:
     *
     * <ol>
     *   <li>the policy names no calendar &rarr; 24/7 ({@code null});</li>
     *   <li>the named calendar version's own effective range covers {@code onDate} &rarr; use it;</li>
     *   <li>it does not &rarr; resolve the version of the <b>same key</b> that was in force on
     *       {@code onDate}, and fall back to the named row when the history has no covering version.
     *       This is the "which working week applied on 2026-03-14" answer, and it is why the calendar
     *       is versioned and effective-dated rather than a single mutable row.</li>
     * </ol>
     *
     * <p>Falling back to the <em>tenant's</em> effective calendar is deliberately not done: it would
     * apply a different working week than the operator selected for this policy.
     */
    private BusinessCalendar calendarFor(TenantScope scope, SlaRepository.PolicyRow policy,
                                         LocalDate onDate) {
        if (policy.calendarId() == null) {
            return null;
        }
        BusinessCalendar named = sla.loadCalendar(scope, policy.calendarId());
        if (named.covers(onDate)) {
            return named;
        }
        List<BusinessCalendar> history = sla.calendarVersions(scope, named.key()).stream()
            .map(row -> sla.loadCalendar(scope, row.id()))
            .toList();
        BusinessCalendar resolved = com.firstelement.automation.domain.BusinessTime
            .resolveEffective(history, onDate);
        return resolved == null ? named : resolved;
    }

    private String calendarKeyOf(TenantScope scope, UUID calendarId) {
        return sla.findCalendar(scope, calendarId).map(SlaRepository.CalendarRow::key).orElseThrow(
            () -> new ValidationFailedException(
                "SLA policy references calendar " + calendarId + ", which is not in this tenant. The "
                    + "composite foreign key should have refused the row; refusing the read as well "
                    + "means a hand-edited database cannot pause a clock against another tenant's "
                    + "working week."));
    }

    /**
     * When the next-response clock starts: the last outbound message, or the first response.
     *
     * <p>{@code conversations.firstResponseAt} is the anchor when nothing has been recorded on the
     * messages table, and the first outbound reply is the real boundary afterwards. Returning null
     * means the clock has not started, which is a different state from "breached".
     */
    private LocalDateTime lastReplyBoundary(TenantScope scope,
                                            ConversationAutomationRepository.ConversationState conversation) {
        LocalDateTime lastOutbound = conversations.lastOutboundAt(scope, conversation.id()).orElse(null);
        if (lastOutbound != null) {
            return lastOutbound;
        }
        return conversation.firstResponseAt() != null ? conversation.firstResponseAt() : null;
    }

    private static LocalDate toDate(LocalDateTime utc) {
        return utc.toLocalDate();
    }

    /** The effective calendar on a date, for the admin read model and a manual due-date preview. */
    @Transactional(readOnly = true)
    public Optional<BusinessCalendar> effectiveCalendar(TenantContext context, UUID projectId,
                                                        LocalDate onDate) {
        authorization.check(context, Permission.CONVERSATION_READ);
        TenantScope scope = guard.scope(context, projectId);
        return sla.effectiveCalendar(scope, projectId, onDate).map(row -> sla.loadCalendar(scope, row.id()));
    }

    /** The now every default evaluation uses: UTC, the shape the schema stores. */
    public static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}