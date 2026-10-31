package com.firstelement.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.automation.domain.SlaClockEvaluator;
import com.firstelement.automation.domain.SlaClockType;
import com.firstelement.automation.persistence.SlaRepository;
import com.firstelement.automation.service.SlaClockService;
import com.firstelement.security.TenantContext;
import com.firstelement.security.query.TenantScope;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The SLA clocks against a real calendar: effective dating, weekends, holidays, and DST.
 *
 * <h2>Why the database is involved at all</h2>
 *
 * <p>{@code SlaClockEvaluatorTest} already proves the arithmetic on an in-memory calendar. What
 * cannot be proven that way is that the calendar actually <em>loaded</em> was the one promised: the
 * effective-dating read, the weekday windows, the closures, and the timezone each row carries. A
 * repository that quietly returned the newest calendar would make every arithmetic test pass while
 * March tickets were judged by an October working week.
 *
 * <h2>Why the instants are UTC but the hours are local</h2>
 *
 * <p>Columns are {@code timestamp without time zone} and hold UTC; a window is a local time in the
 * calendar's zone. The DST cases here are the ones where those two meet and get it wrong: a
 * 09:00&ndash;17:00 window must read as 09:00 local on <em>both</em> sides of a transition, which in
 * absolute terms is 08:00 and 10:00 respectively.
 *
 * <h2>Clocks are independent</h2>
 *
 * <p>First response, next response and resolution are three budgets with three anchors. A calendar
 * test that only checked one would pass even if all three shared an anchor.
 */
@SpringBootTest(classes = WorkflowTestApplication.class)
class SlaCalendarIntegrationTest extends WorkflowIntegrationTestBase {

    @Autowired
    private WorkflowFixture fixture;

    @Autowired
    private SlaRepository sla;

    @Autowired
    private SlaClockService clocks;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    private TenantContext admin;

    @BeforeEach
    void reset() {
        fixture.reset();
        admin = PrincipalScope.adminA();
    }

    private TenantScope scope() {
        return new TenantScope(WorkflowFixture.ORG_A, WorkflowFixture.PROJECT_A);
    }

    /**
     * A Monday&ndash;Friday 09:00&ndash;17:00 calendar, effective from 2026-01-01.
     *
     * @param zone the timezone the window is expressed in
     */
    private UUID businessHoursCalendar(String zone) {
        UUID calendarId = UUID.randomUUID();
        PrincipalScope.call(admin, () -> {
            sla.insertCalendar(scope(), calendarId, WorkflowFixture.PROJECT_A, "business-hours",
                "Business hours", 1, zone, LocalDate.of(2026, 1, 1), WorkflowFixture.ADMIN_A);
            PrincipalScope.call(admin, () -> sla.activateCalendar(scope(), calendarId));
            for (int day = 1; day <= 5; day++) {
                sla.insertWindow(scope(), UUID.randomUUID(), calendarId, day, 9 * 60, 17 * 60,
                    "Business hours");
            }
            return null;
        });
        return calendarId;
    }

    /**
     * A 24/7 policy with no calendar: the explicit "business hours do not apply" choice.
     *
     * <p>First and next budgets are omitted so the {@code NOT_PROMISED} path is reachable, and the
     * resolution budget is comfortably larger than the others because
     * {@code sla_policies_clock_ordering_check} refuses a policy that would breach its
     * first-response clock before its resolution clock could ever be reached.
     */
    private UUID alwaysOnPolicy(String key) {
        UUID policyId = UUID.randomUUID();
        PrincipalScope.call(admin, () -> sla.insertPolicy(scope(), policyId,
            WorkflowFixture.PROJECT_A, key, key, null, null, 10,
            SlaClockType.RESOLUTION, null, null, 240, null, null, null, WorkflowFixture.ADMIN_A));
        return policyId;
    }

    /**
     * A first-response policy bound to a calendar.
     *
     * <p>Only the first-response budget is set: the other clocks then have no promise at all, which
     * is the {@code NOT_PROMISED} case the evaluator has to distinguish from a breach.
     */
    private UUID calendarPolicy(String key, UUID calendarId, int firstResponseMinutes) {
        UUID policyId = UUID.randomUUID();
        PrincipalScope.call(admin, () -> sla.insertPolicy(scope(), policyId,
            WorkflowFixture.PROJECT_A, key, key, null, null, 10,
            SlaClockType.FIRST_RESPONSE, firstResponseMinutes, null, null, calendarId, null, null,
            WorkflowFixture.ADMIN_A));
        return policyId;
    }

    private UUID conversationCreatedAt(LocalDateTime createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            insert into conversations ("id", "organizationId", "projectId", "customerId",
                "ticketNumber", "channel", "status", "mode", "priority", "messageCount",
                "lastMessageAt", "createdAt", "updatedAt")
            values (:id, :org, :project, :customer, :ticket, cast('MOCK' as "ChannelType"),
                    cast('OPEN' as "ConversationStatus"), cast('AI' as "ConversationMode"),
                    cast('NORMAL' as "ConversationPriority"), 0, :createdAt, :createdAt, :createdAt)
            """, new MapSqlParameterSource().addValue("id", id).addValue("org", WorkflowFixture.ORG_A)
            .addValue("project", WorkflowFixture.PROJECT_A)
            .addValue("customer", WorkflowFixture.CUSTOMER_A)
            .addValue("ticket", "#SLA" + Math.abs(id.hashCode() % 100000))
            .addValue("createdAt", createdAt));
        return id;
    }

    private SlaClockEvaluator.Clock firstResponseFor(UUID conversationId, LocalDateTime now) {
        List<SlaClockEvaluator.Assessment> assessments = PrincipalScope.call(admin, () ->
            clocks.assess(admin, WorkflowFixture.PROJECT_A, conversationId, now));
        assertThat(assessments).as("at least one policy must apply for the assertion to mean "
            + "anything").isNotEmpty();
        return assessments.get(0).clock(SlaClockType.FIRST_RESPONSE);
    }

    // ------------------------------------------------------------------
    // effective dating
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the calendar version in force on a date is the one effective then")
    void effectiveDatingPicksTheRightVersion() {
        UUID oldVersion = businessHoursCalendar("UTC");
        // A second version, effective later, with a visibly different week: 06:00-10:00.
        UUID newVersion = UUID.randomUUID();
        PrincipalScope.call(admin, () -> {
            sla.insertCalendar(scope(), newVersion, WorkflowFixture.PROJECT_A, "business-hours",
                "Business hours v2", 2, "UTC", LocalDate.of(2026, 10, 1), WorkflowFixture.ADMIN_A);
            sla.activateCalendar(scope(), newVersion);
            sla.insertWindow(scope(), UUID.randomUUID(), newVersion, 1, 6 * 60, 10 * 60, "v2");
            return null;
        });

        // A March ticket must be judged by the week promised to it, not by the October edit.
        assertThat(PrincipalScope.call(admin, () ->
            sla.effectiveCalendar(scope(), WorkflowFixture.PROJECT_A, LocalDate.of(2026, 3, 16))))
            .as("March predates the second version")
            .map(SlaRepository.CalendarRow::id).contains(oldVersion);
        assertThat(PrincipalScope.call(admin, () ->
            sla.effectiveCalendar(scope(), WorkflowFixture.PROJECT_A, LocalDate.of(2026, 11, 16))))
            .map(SlaRepository.CalendarRow::id).contains(newVersion);
    }

    @Test
    @DisplayName("a March ticket keeps March's working week after the calendar is edited")
    void historicalTicketKeepsItsWorkingWeek() {
        UUID firstVersion = businessHoursCalendar("UTC");
        // A March conversation that opened on a Monday at 09:00.
        UUID conversationId = conversationCreatedAt(LocalDateTime.of(2026, 3, 16, 9, 0));
        calendarPolicy("march-policy", firstVersion, 120);

        // October: somebody edits the calendar to a completely different week.
        UUID secondVersion = UUID.randomUUID();
        PrincipalScope.call(admin, () -> {
            sla.insertCalendar(scope(), secondVersion, WorkflowFixture.PROJECT_A, "business-hours",
                "Business hours v2", 2, "UTC", LocalDate.of(2026, 10, 1), WorkflowFixture.ADMIN_A);
            sla.activateCalendar(scope(), secondVersion);
            sla.insertWindow(scope(), UUID.randomUUID(), secondVersion, 1, 6 * 60, 7 * 60, "v2");
            return null;
        });

        // Two hours of business time elapsed on the Monday (09:00-11:00), under either version's
        // window the Monday IS open, so the clock is what proves which week was used: under v2 the
        // Monday window is 06:00-07:00 and 09:00 would be outside it entirely.
        SlaClockEvaluator.Clock clock = firstResponseFor(conversationId,
            LocalDateTime.of(2026, 3, 16, 11, 0));
        assertThat(clock.elapsedMinutes())
            .as("the March promise was 09:00-17:00, so two business hours elapsed")
            .isEqualTo(120);
    }

    // ------------------------------------------------------------------
    // weekends, holidays and 24/7
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a weekend does not consume the budget: the clock pauses and resumes")
    void weekendPausesTheClock() {
        UUID calendarId = businessHoursCalendar("UTC");
        UUID policyId = calendarPolicy("weekend", calendarId, 60);
        // Friday 2026-08-14 at 16:00. One business hour before close, so 60 minutes is reachable
        // before the weekend; anything after is met only once Monday opens.
        UUID conversationId = conversationCreatedAt(LocalDateTime.of(2026, 8, 14, 16, 0));

        SlaClockEvaluator.Clock fridayEvening = firstResponseFor(conversationId,
            LocalDateTime.of(2026, 8, 14, 17, 0));
        assertThat(fridayEvening.elapsedMinutes())
            .as("Friday 16:00 to 17:00 is the last business hour of the week").isEqualTo(60);

        // Sunday: the whole day is outside the window, so nothing accrues.
        SlaClockEvaluator.Clock sunday = firstResponseFor(conversationId,
            LocalDateTime.of(2026, 8, 16, 12, 0));
        assertThat(sunday.elapsedMinutes())
            .as("two days of weekend must not be billed to the customer").isEqualTo(60);

        // Monday 09:30: the clock resumes and crosses the 60-minute budget.
        SlaClockEvaluator.Clock monday = firstResponseFor(conversationId,
            LocalDateTime.of(2026, 8, 17, 9, 30));
        assertThat(monday.elapsedMinutes()).isEqualTo(90);
        assertThat(monday.state()).isEqualTo(SlaClockEvaluator.ClockState.BREACHED);
        assertThat(policyId).isNotNull();
    }

    @Test
    @DisplayName("a dated closure pauses the clock for that day only")
    void holidayPausesTheClock() {
        UUID calendarId = businessHoursCalendar("UTC");
        calendarPolicy("holiday", calendarId, 60);
        PrincipalScope.call(admin, () -> sla.insertHoliday(scope(), UUID.randomUUID(), calendarId,
            LocalDate.of(2026, 8, 17), "Company holiday", null, null));

        UUID conversationId = conversationCreatedAt(LocalDateTime.of(2026, 8, 14, 16, 0));

        // Friday's hour plus all of Monday (a holiday) plus Tuesday's first half hour.
        SlaClockEvaluator.Clock tuesday = firstResponseFor(conversationId,
            LocalDateTime.of(2026, 8, 18, 9, 30));
        assertThat(tuesday.elapsedMinutes())
            .as("Monday is a closure, so only Friday's hour and Tuesday's half hour count")
            .isEqualTo(90);
    }

    @Test
    @DisplayName("a partial closure removes only its own minutes")
    void partialClosureRemovesOnlyItsMinutes() {
        UUID calendarId = businessHoursCalendar("UTC");
        calendarPolicy("half-day", calendarId, 60);
        // Lunch: 12:00-13:00 closed, bounded rather than all-day.
        PrincipalScope.call(admin, () -> sla.insertHoliday(scope(), UUID.randomUUID(), calendarId,
            LocalDate.of(2026, 8, 19), "Lunch", 12 * 60, 13 * 60));

        UUID conversationId = conversationCreatedAt(LocalDateTime.of(2026, 8, 19, 11, 0));

        SlaClockEvaluator.Clock afternoon = firstResponseFor(conversationId,
            LocalDateTime.of(2026, 8, 19, 14, 0));
        assertThat(afternoon.elapsedMinutes())
            .as("11:00-12:00 and 13:00-14:00 are open; the lunch hour is not")
            .isEqualTo(120);
    }

    @Test
    @DisplayName("a policy with no calendar is an explicit 24/7 clock, not a frozen one")
    void nullCalendarMeansAlwaysOn() {
        alwaysOnPolicy("around-the-clock");
        UUID conversationId = conversationCreatedAt(LocalDateTime.of(2026, 8, 15, 23, 0));

        // Saturday night, an hour later: a 24/7 policy accrues, a business-hours one would not.
        SlaClockEvaluator.Assessment assessment = PrincipalScope.call(admin, () ->
            clocks.assess(admin, WorkflowFixture.PROJECT_A, conversationId,
                LocalDateTime.of(2026, 8, 16, 0, 0))).get(0);

        assertThat(assessment.clock(SlaClockType.RESOLUTION).elapsedMinutes())
            .as("a null calendar is the operator saying 'business hours do not apply here', so the "
                + "clock must run over a weekend")
            .isEqualTo(60);
        assertThat(assessment.clock(SlaClockType.RESOLUTION).elapsedMinutes())
            .isNotZero();
    }

    // ------------------------------------------------------------------
    // DST
    // ------------------------------------------------------------------

    @Test
    @DisplayName("DST spring forward: a 09:00 window is still 09:00 local afterwards")
    void springForwardKeepsTheLocalHour() {
        // Europe/London springs forward on 2026-03-29. A 09:00-17:00 window must remain 09:00
        // LOCAL on the far side, which in absolute terms is 08:00 UTC.
        UUID calendarId = businessHoursCalendar("Europe/London");
        calendarPolicy("dst-spring", calendarId, 60);
        // 2026-03-30 is the Monday after the transition: 09:00 local is 08:00 UTC.
        UUID conversationId = conversationCreatedAt(LocalDateTime.of(2026, 3, 30, 8, 0));

        SlaClockEvaluator.Clock clock = firstResponseFor(conversationId,
            LocalDateTime.of(2026, 3, 30, 9, 0));

        assertThat(clock.elapsedMinutes())
            .as("08:00-09:00 UTC on the Monday after the transition is 09:00-10:00 local, so one "
                + "business hour elapsed. A clock that added 24h to the local window would report 60 "
                + "here and 0 or 120 either side of the change.")
            .isEqualTo(60);
    }

    @Test
    @DisplayName("DST autumn: the duplicated hour accrues once, not twice")
    void autumnDuplicatedHourIsNotDoubleCounted() {
        // Europe/London falls back on 2026-10-25. The window is local, so the business day is still
        // 09:00-17:00 LOCAL and contains the same number of business minutes.
        UUID calendarId = businessHoursCalendar("Europe/London");
        calendarPolicy("dst-autumn", calendarId, 60);
        UUID conversationId = conversationCreatedAt(LocalDateTime.of(2026, 10, 27, 9, 0));

        SlaClockEvaluator.Clock clock = firstResponseFor(conversationId,
            LocalDateTime.of(2026, 10, 27, 11, 0));

        assertThat(clock.elapsedMinutes())
            .as("two local hours is two business hours, whether or not the UTC offset changed inside "
                + "the interval")
            .isEqualTo(120);
    }

    @Test
    @DisplayName("an interval that spans the transition accrues the same minutes as one that does not")
    void anIntervalAcrossDstIsTheSameLengthAsOneAcrossAStableWeek() {
        // A seven-day calendar, because the transitions fall on a SUNDAY: with a Mon-Fri week the
        // interval could never contain one, and the comparison would be vacuous. This is the shape
        // that catches a clock derived from absolute minutes.
        UUID calendarId = UUID.randomUUID();
        PrincipalScope.call(admin, () -> {
            sla.insertCalendar(scope(), calendarId, WorkflowFixture.PROJECT_A, "always-open",
                "Always open", 1, "Europe/London", LocalDate.of(2026, 1, 1), WorkflowFixture.ADMIN_A);
            sla.activateCalendar(scope(), calendarId);
            for (int day = 0; day <= 6; day++) {
                sla.insertWindow(scope(), UUID.randomUUID(), calendarId, day, 0, 1440, "open");
            }
            return null;
        });
        calendarPolicy("dst-interval", calendarId, 60000);

        // Europe/London springs forward on 2026-03-29, so Sat 21:00 UTC to Sun 21:00 UTC spans it.
        // In absolute terms that is 23 hours, but the calendar is a local 07:00-to-07:00 window on
        // consecutive local days, so the honest answer is 1440 business minutes either way.
        UUID acrossTransition = conversationCreatedAt(LocalDateTime.of(2026, 3, 28, 12, 0));
        UUID stable = conversationCreatedAt(LocalDateTime.of(2026, 8, 15, 12, 0));

        long dstElapsed = firstResponseFor(acrossTransition,
            LocalDateTime.of(2026, 3, 29, 12, 0)).elapsedMinutes();
        long stableElapsed = firstResponseFor(stable, LocalDateTime.of(2026, 8, 16, 12, 0))
            .elapsedMinutes();

        assertThat(dstElapsed)
            .as("a calendar spanning a spring-forward must still report whole local days; a clock "
                + "that added absolute minutes would report 1380 here (the day that is 23 hours long)")
            .isEqualTo(1440).isEqualTo(stableElapsed);
    }

    // ------------------------------------------------------------------
    // independent clocks
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the three clocks are independent: answering the first does not reset resolution")
    void clocksHaveIndependentAnchors() {
        UUID calendarId = businessHoursCalendar("UTC");
        UUID policyId = UUID.randomUUID();
        // One policy promising all three, with budgets the ordering CHECK accepts:
        // next(30) >= first(60) is a violation, so first is 30 and next is 45 here.
        PrincipalScope.call(admin, () -> sla.insertPolicy(scope(), policyId,
            WorkflowFixture.PROJECT_A, "three-clocks", "Three clocks", null, null, 10,
            SlaClockType.RESOLUTION, 30, 45, 600, calendarId, null, null, WorkflowFixture.ADMIN_A));

        UUID conversationId = conversationCreatedAt(LocalDateTime.of(2026, 8, 17, 9, 0));
        // The first reply lands at 09:20, so the next-response clock starts there.
        jdbc.update("update conversations set \"firstResponseAt\" = :at where \"id\" = :id",
            new MapSqlParameterSource().addValue("at", LocalDateTime.of(2026, 8, 17, 9, 20))
                .addValue("id", conversationId));
        jdbc.update("""
            insert into messages ("id", "organizationId", "projectId", "conversationId", "channel",
                "direction", "authorType", "body", "deliveryStatus", "sentAt", "createdAt")
            values (:id, :org, :project, :conversationId, cast('MOCK' as "ChannelType"),
                    cast('OUTBOUND' as "MessageDirection"), cast('AI' as "MessageAuthorType"),
                    'first reply', cast('SENT' as "MessageDeliveryStatus"), :at, :at)
            """, new MapSqlParameterSource().addValue("id", UUID.randomUUID())
            .addValue("org", WorkflowFixture.ORG_A).addValue("project", WorkflowFixture.PROJECT_A)
            .addValue("conversationId", conversationId)
            .addValue("at", LocalDateTime.of(2026, 8, 17, 9, 20)));

        SlaClockEvaluator.Assessment assessment = PrincipalScope.call(admin, () ->
            clocks.assess(admin, WorkflowFixture.PROJECT_A, conversationId,
                LocalDateTime.of(2026, 8, 17, 10, 0))).get(0);

        assertThat(assessment.clock(SlaClockType.FIRST_RESPONSE).state())
            .as("20 minutes against a 30-minute budget: met and stopped")
            .isEqualTo(SlaClockEvaluator.ClockState.MET);
        assertThat(assessment.clock(SlaClockType.FIRST_RESPONSE).elapsedMinutes()).isEqualTo(20);

        assertThat(assessment.clock(SlaClockType.NEXT_RESPONSE).state())
            .as("40 minutes since the last reply against a 45-minute budget: still inside it, and the "
                + "first reply's success must not have reset the anchor")
            .isEqualTo(SlaClockEvaluator.ClockState.ON_TRACK);
        assertThat(assessment.clock(SlaClockType.NEXT_RESPONSE).elapsedMinutes())
            .as("anchored at the 09:20 reply, not at the 09:00 creation")
            .isEqualTo(40);

        assertThat(assessment.clock(SlaClockType.RESOLUTION).state())
            .as("resolution is anchored at creation, so it keeps accruing")
            .isEqualTo(SlaClockEvaluator.ClockState.ON_TRACK);
        assertThat(assessment.clock(SlaClockType.RESOLUTION).elapsedMinutes()).isEqualTo(60);
    }

    @Test
    @DisplayName("a clock with no promised budget is NOT_PROMISED, never BREACHED")
    void absentBudgetIsNotABreach() {
        alwaysOnPolicy("partial");
        UUID conversationId = conversationCreatedAt(LocalDateTime.of(2026, 8, 17, 9, 0));

        // Inside the promised resolution budget, so nothing at all is breached. The point of the
        // assertion is that the two UNPROMISED clocks are not counted as breaches while the
        // promised one genuinely has not been missed.
        SlaClockEvaluator.Assessment assessment = PrincipalScope.call(admin, () ->
            clocks.assess(admin, WorkflowFixture.PROJECT_A, conversationId,
                LocalDateTime.of(2026, 8, 17, 10, 0))).get(0);

        assertThat(assessment.clock(SlaClockType.FIRST_RESPONSE).state())
            .as("the policy promises a resolution budget only; the other clocks promised nothing, and "
                + "an operator must be able to tell 'we promised nothing' from 'we missed'")
            .isEqualTo(SlaClockEvaluator.ClockState.NOT_PROMISED);
        assertThat(assessment.clock(SlaClockType.NEXT_RESPONSE).state())
            .isEqualTo(SlaClockEvaluator.ClockState.NOT_PROMISED);
        assertThat(assessment.breachedClocks())
            .as("a clock nobody promised cannot appear in the breached list")
            .isEmpty();
        assertThat(assessment.breached()).isFalse();
    }

    @Test
    @DisplayName("the resolution clock stops when the conversation resolves")
    void resolutionStopsAtResolution() {
        alwaysOnPolicy("resolved");
        UUID conversationId = conversationCreatedAt(LocalDateTime.of(2026, 8, 17, 9, 0));
        LocalDateTime resolvedAt = LocalDateTime.of(2026, 8, 17, 11, 0);
        jdbc.update("update conversations set \"resolvedAt\" = :at, \"status\" = "
            + "cast('RESOLVED' as \"ConversationStatus\") where \"id\" = :id",
            new MapSqlParameterSource().addValue("at", resolvedAt).addValue("id", conversationId));

        SlaClockEvaluator.Assessment assessment = PrincipalScope.call(admin, () ->
            clocks.assess(admin, WorkflowFixture.PROJECT_A, conversationId,
                LocalDateTime.of(2026, 8, 20, 9, 0))).get(0);

        assertThat(assessment.clock(SlaClockType.RESOLUTION).state())
            .isEqualTo(SlaClockEvaluator.ClockState.MET);
        assertThat(assessment.clock(SlaClockType.RESOLUTION).elapsedMinutes())
            .as("a resolved ticket must not keep accruing after resolution, however long ago it was")
            .isEqualTo(120);
    }

    // ------------------------------------------------------------------
    // cross-tenant
    // ------------------------------------------------------------------

    @Test
    @DisplayName("another tenant's calendar is never reachable, and a policy may not name one")
    void crossTenantCalendarIsRefused() {
        UUID calendarId = businessHoursCalendar("UTC");
        UUID policyId = calendarPolicy("mine", calendarId, 60);

        assertThat(PrincipalScope.call(admin, () ->
            sla.countOtherTenantPolicies(scope(), policyId)))
            .as("another tenant's policies are never reachable").isZero();
        assertThat(PrincipalScope.call(PrincipalScope.adminB(), () ->
            sla.countOtherTenantPolicies(
                new TenantScope(WorkflowFixture.ORG_B, WorkflowFixture.PROJECT_B), policyId)))
            .as("and a tenant B scope must not see tenant A's policy either")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("the database itself refuses a policy pointing at another tenant's calendar")
    void crossTenantCalendarReferenceIsRefusedByTheSchema() {
        UUID calendarId = businessHoursCalendar("UTC");
        UUID policyId = calendarPolicy("mine", calendarId, 60);

        // Not an application check: the composite foreign key over (calendarId, organizationId)
        // makes the write itself fail, so the read-path assertion in §10e has nothing to catch in a
        // database that was migrated correctly. Asserted here so the guarantee is visible as a
        // statement rather than as a comment.
        assertThatThrownBy(() -> jdbc.update(
            "update sla_policies set \"calendarId\" = :calendarId where \"id\" = :id",
            new MapSqlParameterSource().addValue("calendarId", UUID.randomUUID())
                .addValue("id", policyId)))
            .as("a policy must not be able to name a calendar that does not exist in its tenant")
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        // And the ordinary case is silent: no exception when nothing is wrong.
        org.assertj.core.api.Assertions.assertThatCode(
            () -> PrincipalScope.call(admin, () -> sla.assertNoCrossTenantCalendar(scope())))
            .as("§10e must not throw for a healthy database, or every evaluation would fail")
            .doesNotThrowAnyException();
    }
}