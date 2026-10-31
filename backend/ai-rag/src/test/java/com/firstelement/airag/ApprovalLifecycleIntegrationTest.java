package com.firstelement.airag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.airag.approval.AiApprovalService;
import com.firstelement.airag.policy.AiRefusalReason;
import com.firstelement.airag.policy.AiRefusedException;
import com.firstelement.airag.principal.AiPrincipalScope;
import com.firstelement.airag.tools.AppointmentProposalTool;
import com.firstelement.security.TenantContext;
import com.firstelement.security.query.TenantScope;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The approval state machine: request, approve, reject, revoke, expire — and the four ways it must refuse.
 *
 * <h2>Why this is a state machine test and not a workflow test</h2>
 *
 * <p>ADR 0007 Part 2: "the AI proposes, a human disposes". The dangerous transitions are the ones a
 * workflow makes easy — granting twice, granting to yourself, spending an approval that has lapsed, and
 * spending an approval for <em>different arguments</em>. Each is a separate test here, and each asserts the
 * refusal reason code rather than merely the absence of an appointment, because "it did not happen" and
 * "it was refused for a named reason" are different guarantees: only the second tells an auditor that the
 * control fired.
 *
 * <h2>Who may approve</h2>
 *
 * <p>Two admins are seeded ({@code ADMIN_A} and {@code APPROVER_A}) precisely so that "a different human
 * approved it" is expressible. The AI agent principal holds no {@code ADMIN} role, so a self-approval test
 * that used the agent would prove nothing — the refusal would come from the role, not from the
 * self-approval check. So the requester is a named admin and the approver is the other one, which is the
 * only shape in which {@code requireNotSelf} can be the thing under test.
 */
@SpringBootTest(classes = AiRagTestApplication.class)
class ApprovalLifecycleIntegrationTest extends AiIntegrationTestBase {

    @Autowired
    private AiFixture fixture;

    @Autowired
    private AiApprovalService approvals;

    private AiFixture.AgentVersion agent;
    private UUID conversation;
    private UUID runId;

    /** The arguments the model proposed. Carries a value that must never be persisted. */
    private static final Map<String, Object> ARGUMENTS = Map.of(
        "doctorId", "11111111-1111-1111-1111-111111111111",
        "day", "2030-01-01",
        "durationMinutes", 45,
        "note", "SECRET-CUSTOMER-NOTE");

    @BeforeEach
    void seed() {
        fixture.reset();
        UUID knowledgeBase = fixture.knowledgeBase(AiFixture.ORG_A, AiFixture.PROJECT_A, "KB");
        agent = fixture.agentWithVersion(AiFixture.ORG_A, AiFixture.PROJECT_A, AiFixture.ADMIN_A,
            "MOCK", "mock-deterministic-v1", "[]", "[\"" + knowledgeBase + "\"]", 0.0d,
            "أجب من المصادر فقط");
        conversation = fixture.conversation(AiFixture.ORG_A, AiFixture.PROJECT_A, "AI");
        runId = fixture.aiRun(AiFixture.ORG_A, AiFixture.PROJECT_A, conversation, agent.agentId(),
            agent.versionId());
    }

    @Nested
    @DisplayName("the happy path")
    class Happy {

        @Test
        @DisplayName("a request is PENDING, names the CONSEQUENTIAL class, and has an expiry")
        void requestIsPendingAndBounded() {
            UUID id = request();

            AiApprovalService.ApprovalRow row = approvals.require(scopeA(), id);
            assertThat(row.status()).isEqualTo("PENDING");
            // The schema CHECKs actionClass to 'CONSEQUENTIAL', so there is no approval path for an
            // answerable or forbidden action and the constraint makes that unrepresentable.
            assertThat(row.actionClass()).isEqualTo("CONSEQUENTIAL");
            assertThat(approvals.approvableClass()).isEqualTo("CONSEQUENTIAL");
            assertThat(row.action()).isEqualTo(AiFixture.CONSEQUENTIAL_TOOL + "#" + runId);
            // An approval with no expiry is a permanent delegation.
            assertThat(row.expiresAt()).isAfter(LocalDateTime.now(ZoneOffset.UTC));
            assertThat(approvals.DEFAULT_VALIDITY_MINUTES).isEqualTo(30L);
        }

        @Test
        @DisplayName("a second approver can grant it, and the grant is recorded twice")
        void aSecondHumanCanApprove() {
            UUID id = request();

            approvals.approve(scopeA(), approver(), id, "verified the patient asked for this");

            AiApprovalService.ApprovalRow row = approvals.require(scopeA(), id);
            assertThat(row.status()).isEqualTo("APPROVED");
            assertThat(row.decidedByUserId()).isEqualTo(AiFixture.APPROVER_A);
            // ai_approval_decisions is APPEND-ONLY: the request, then the grant. The mutable row is state;
            // this is the evidence.
            assertThat(decisionCount(id, "PENDING")).isEqualTo(1);
            assertThat(decisionCount(id, "APPROVED")).isEqualTo(1);
        }

        @Test
        @DisplayName("an approval made without a note still records that it was a bare rubber stamp")
        void aBareApprovalIsStillRecorded() {
            UUID id = request();

            approvals.approve(scopeA(), approver(), id, null);

            assertThat(approvals.require(scopeA(), id).decisionNote())
                .isEqualTo("approved without a note");
        }

        @Test
        @DisplayName("an approved request is spendable by the run that asked and not by another")
        void approvedIsSpendableByItsOwnRun() {
            UUID id = request();
            approvals.approve(scopeA(), approver(), id, "ok");
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

            assertThat(approvals.consume(scopeA(), id, runId, AiFixture.CONSEQUENTIAL_TOOL, ARGUMENTS, now))
                .isTrue();
            assertThat(approvals.consume(scopeA(), id, UUID.randomUUID(), AiFixture.CONSEQUENTIAL_TOOL,
                ARGUMENTS, now))
                .as("an approval id is exactly the thing a model can carry across turns")
                .isFalse();
        }

        @Test
        @DisplayName("an approval cannot be spent by a DIFFERENT tool under the same id")
        void approvedIsBoundToItsTool() {
            UUID id = request();
            approvals.approve(scopeA(), approver(), id, "ok");
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

            assertThat(approvals.consume(scopeA(), id, runId, AiFixture.READ_TOOL, ARGUMENTS, now))
                .as("the stored action names the tool, and a prefix match on a different name must fail")
                .isFalse();
        }

        @Test
        @DisplayName("an approval is bound to the ARGUMENT SHAPE it was granted for")
        void approvedIsBoundToItsArgumentShape() {
            UUID id = request();
            approvals.approve(scopeA(), approver(), id, "ok");
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

            // Approving "Tuesday 10:00 with doctor X" and then running it for "Tuesday 14:00 with doctor Y"
            // would be an approval for something nobody approved. Only names and types are stored, so this
            // is a SHAPE check, and it is documented as weaker than a value check on the service's javadoc —
            // Phase 12, when a tool performs, must record and compare the approved values.
            Map<String, Object> differentShape = Map.of("doctorId", "x", "day", "y");

            assertThat(approvals.consume(scopeA(), id, runId, AiFixture.CONSEQUENTIAL_TOOL, ARGUMENTS, now))
                .isTrue();
            assertThat(approvals.consume(scopeA(), id, runId, AiFixture.CONSEQUENTIAL_TOOL, differentShape,
                now)).isFalse();
        }
    }

    @Nested
    @DisplayName("the four refusals that matter")
    class Refusals {

        @Test
        @DisplayName("nobody approves their own request")
        void selfApprovalIsRefused() {
            UUID id = request();

            assertThatThrownBy(() -> approvals.approve(scopeA(), requester(), id, "my own request"))
                .isInstanceOf(AiRefusedException.class)
                .satisfies(thrown -> {
                    AiRefusedException refusal = (AiRefusedException) thrown;
                    assertThat(refusal.reason()).isEqualTo(AiRefusalReason.HUMAN_APPROVAL_REQUIRED);
                    assertThat(refusal.detail()).contains("cannot be granted by the user who requested it");
                });
            // Refused before the write, so the row is untouched.
            assertThat(approvals.require(scopeA(), id).status()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("self-REJECTION is refused too, so a requester cannot bury their own proposal")
        void selfRejectionIsRefused() {
            UUID id = request();

            assertThatThrownBy(() -> approvals.reject(scopeA(), requester(), id, "no"))
                .isInstanceOf(AiRefusedException.class)
                .satisfies(thrown -> assertThat(((AiRefusedException) thrown).reason())
                    .isEqualTo(AiRefusalReason.HUMAN_APPROVAL_REQUIRED));
        }

        @Test
        @DisplayName("a second grant of the same approval is refused, because the decision is made once")
        void doubleApprovalIsRefused() {
            UUID id = request();
            approvals.approve(scopeA(), approver(), id, "first");

            assertThatThrownBy(() -> approvals.approve(scopeA(), approver(), id, "again"))
                .isInstanceOf(AiRefusedException.class)
                .satisfies(thrown -> {
                    AiRefusedException refusal = (AiRefusedException) thrown;
                    assertThat(refusal.reason()).isEqualTo(AiRefusalReason.HUMAN_APPROVAL_REQUIRED);
                    assertThat(refusal.detail()).contains("APPROVED").contains("not PENDING");
                });
            // Exactly one grant in the history, however many times the button was pressed.
            assertThat(decisionCount(id, "APPROVED")).isEqualTo(1);
        }

        @Test
        @DisplayName("an EXPIRED approval is a refusal, not a grant")
        void expiredApprovalIsRefused() {
            UUID id = request();
            // Both timestamps move, because ai_approvals_expiry_check is `expiresAt > requestedAt` and
            // ageing only expiresAt would be refused by the schema rather than by the gate. The state under
            // test is "the clock ran out", which needs a coherent pair.
            agePastExpiry(id);

            assertThatThrownBy(() -> approvals.approve(scopeA(), approver(), id, "too late"))
                .isInstanceOf(AiRefusedException.class)
                .satisfies(thrown -> assertThat(((AiRefusedException) thrown).reason())
                    .isEqualTo(AiRefusalReason.HUMAN_APPROVAL_REQUIRED));
            // EXPIRED, not REJECTED: an approval that timed out was neither approved nor refused, and
            // recording it as a decision would put a decision in the history that no human made.
            assertThat(approvals.require(scopeA(), id).status()).isEqualTo("EXPIRED");
            assertThat(approvals.require(scopeA(), id).decidedByUserId())
                .as("nobody decided it, so nobody is named as having decided it")
                .isNull();
            assertThat(decisionCount(id, "EXPIRED")).isEqualTo(1);
        }

        @Test
        @DisplayName("an approval that lapsed AFTER being granted cannot be spent")
        void expiredGrantIsNotSpendable() {
            UUID id = request();
            approvals.approve(scopeA(), approver(), id, "ok");
            agePastExpiry(id);

            assertThat(approvals.consume(scopeA(), id, runId, AiFixture.CONSEQUENTIAL_TOOL, ARGUMENTS,
                LocalDateTime.now(ZoneOffset.UTC)))
                .as("an approval that sat in a queue over lunch cannot be spent in the afternoon on a slot "
                    + "that has since gone")
                .isFalse();
        }

        @Test
        @DisplayName("a revocation needs a reason, because 'why was this taken back?' is asked first")
        void revocationNeedsAReason() {
            UUID id = request();
            approvals.approve(scopeA(), approver(), id, "ok");

            assertThatThrownBy(() -> approvals.revoke(scopeA(), approver(), id, "  "))
                .isInstanceOf(AiRefusedException.class)
                .satisfies(thrown -> assertThat(((AiRefusedException) thrown).detail())
                    .contains("must carry a reason"));
        }

        @Test
        @DisplayName("a revocation takes the grant back and PRESERVES who granted it")
        void revocationTakesTheGrantBack() {
            UUID id = request();
            approvals.approve(scopeA(), approver(), id, "ok");
            // The grant's decision, captured BEFORE the revocation so the assertion compares against what was
            // actually written rather than against itself afterwards.
            LocalDateTime grantedAt = approvals.require(scopeA(), id).decidedAt();

            approvals.revoke(scopeA(), revoker(), id, "the patient withdrew the request");

            AiApprovalService.ApprovalRow row = approvals.require(scopeA(), id);
            assertThat(row.status()).isEqualTo("REVOKED");
            assertThat(row.revokeReason()).isEqualTo("the patient withdrew the request");

            // THE RESTORED BEHAVIOUR. revoke() used to write decidedByUserId = NULL and decidedAt = NULL,
            // because ai_approvals_approval_check admitted a decidedAt on APPROVED only. That satisfied the
            // constraint by replacing "who granted this" with "who revoked it" -- the row looked complete and
            // had lost the answer to the first question an incident review asks. V20261006100000 added
            // 'REVOKED' to the decision-bearing states, so the grant's decision survives the withdrawal.
            assertThat(row.decidedByUserId())
                .as("the GRANT's decider, not the revoker's")
                .isEqualTo(AiFixture.APPROVER_A);
            assertThat(row.decidedAt())
                .as("and the grant's timestamp, unchanged")
                .isEqualTo(grantedAt);

            // The withdrawal is a SEPARATE record, governed by ai_approvals_revocation_check. Two actors, two
            // columns, two questions -- which is why the revoker below is not APPROVER_A, the grantor.
            // Read through the SERVICE, not through raw SQL: the read model has to carry both actors, or every
            // caller ends up hand-writing a query to find out who revoked something.
            assertThat(row.revokedByUserId())
                .as("the WITHDRAWER, distinct from the grantor above")
                .isEqualTo(AiFixture.REVOKER_A);
            assertThat(row.revokedAt())
                .as("and it records WHEN the grant was withdrawn")
                .isNotNull();

            assertThat(approvals.consume(scopeA(), id, runId, AiFixture.CONSEQUENTIAL_TOOL, ARGUMENTS,
                LocalDateTime.now(ZoneOffset.UTC)))
                .as("revoked is not a grant")
                .isFalse();
        }

        @Test
        @DisplayName("a PENDING approval cannot be revoked, because there is nothing to take back")
        void pendingCannotBeRevoked() {
            UUID id = request();

            assertThatThrownBy(() -> approvals.revoke(scopeA(), approver(), id, "changed my mind"))
                .isInstanceOf(AiRefusedException.class)
                .satisfies(thrown -> assertThat(((AiRefusedException) thrown).detail())
                    .contains("is not APPROVED and cannot be revoked"));
        }

        @Test
        @DisplayName("a rejection without a note is refused, by this class and by the schema")
        void rejectionNeedsANote() {
            UUID id = request();

            // ai_approvals_rejection_check refuses a REJECTED row with no decisionNote. Refused here first so
            // the caller gets a reason code rather than a constraint violation three frames down.
            assertThatThrownBy(() -> approvals.reject(scopeA(), approver(), id, null))
                .isInstanceOf(AiRefusedException.class)
                .satisfies(thrown -> assertThat(((AiRefusedException) thrown).detail())
                    .contains("rejection must carry a note"));
        }

        @Test
        @DisplayName("a rejection is terminal, and names its decider on BOTH the row and the history")
        void rejectionIsTerminal() {
            UUID id = request();

            approvals.reject(scopeA(), approver(), id, "the slot was taken while it waited");

            AiApprovalService.ApprovalRow row = approvals.require(scopeA(), id);
            assertThat(row.status()).isEqualTo("REJECTED");
            assertThat(row.decisionNote()).isEqualTo("the slot was taken while it waited");

            // The RESTORED behaviour, and strictly more than this test asserted before. It used to assert
            // only the append-only decider, because ai_approvals_approval_check read
            // ("status" = 'APPROVED') = ("decidedAt" IS NOT NULL) and a REJECTED row could not carry a
            // decision -- so the mutable row, which is what an operator reads in the queue, hid who decided.
            // V20261006100000 corrected the constraint; a rejection now says who and when on the row itself.
            assertThat(row.decidedByUserId())
                .as("a rejection an auditor cannot attribute is not a decision")
                .isEqualTo(AiFixture.APPROVER_A);
            assertThat(row.decidedAt())
                .as("and it says WHEN, not just who")
                .isNotNull();

            // The append-only history keeps the same fact. This is NOT a substitute for the row-level
            // record above -- it is the same fact recorded twice, on purpose: the row is current state, the
            // decisions table is the evidence, and the two agreeing is what makes the state auditable.
            assertThat(fixture.uuidValue("""
                select d."actorUserId" from ai_approval_decisions d
                 where d."approvalId" = :id and d."toStatus" = cast('REJECTED' as "AiApprovalStatus")
                """, Map.of("id", id)))
                .isEqualTo(AiFixture.APPROVER_A);
            // Compare the INSTANTS, not their two renderings.
//
// This compared strings: `row.decidedAt()` arrives as a java.sql.Timestamp, which renders at least
// three fractional digits (".090"), while the same value cast to text by PostgreSQL drops a trailing
// zero (".09"). Both are the same instant and the same stored millisecond -- but the assertion passed
// or failed depending on whether the wall clock happened to land on a millisecond ending in zero. It
// is a clock-dependent test that had nothing to do with the behaviour it claimed to check.
//
// The schema is `timestamp(3) without time zone`, so truncating both sides to MILLIS is exact and
// removes the formatting difference entirely.
String historyDecidedAt = fixture.text("""
                select d."decidedAt"::text from ai_approval_decisions d
                 where d."approvalId" = :id and d."toStatus" = cast('REJECTED' as "AiApprovalStatus")
                """, Map.of("id", id));
String rowDecidedAt = row.decidedAt().toString();
assertThat(java.time.LocalDateTime.parse(historyDecidedAt.replace(' ', 'T'))
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS))
                .as("the history's timestamp and the row's are the SAME fact, not two nearby ones")
                .isEqualTo(java.time.LocalDateTime.parse(rowDecidedAt.replace(' ', 'T'))
                        .truncatedTo(java.time.temporal.ChronoUnit.MILLIS));

            // Still terminal, still unspendable, still not reversible into a grant.
            assertThat(approvals.consume(scopeA(), id, runId, AiFixture.CONSEQUENTIAL_TOOL, ARGUMENTS,
                LocalDateTime.now(ZoneOffset.UTC))).isFalse();
            assertThatThrownBy(() -> approvals.approve(scopeA(), approver(), id, "changed my mind"))
                .isInstanceOf(AiRefusedException.class);
        }

        @Test
        @DisplayName("a rejected row's decision pair is WHOLE, never half a pair")
        void aRejectionsDecisionPairIsWhole() {
            UUID id = request();

            approvals.reject(scopeA(), approver(), id, "the slot was taken while it waited");

            // ai_approvals_decision_pair_check is a biconditional: (decidedAt IS NULL) = (decidedByUserId IS
            // NULL). A migration that fixed decidedAt and left decidedByUserId NULL would have traded one
            // unauditable approval for another, so the half-pair state is asserted directly rather than
            // inferred from both columns happening to be set.
            assertThat(fixture.intValue("""
                select count(*)::int from ai_approvals a
                 where a."id" = :id and ("decidedAt" IS NULL) <> ("decidedByUserId" IS NULL)
                """, Map.of("id", id)))
                .isZero();
        }

        @Test
        @DisplayName("the grant AND the revocation are both on the append-only history, each with its own actor")
        void revocationLeavesACompleteHistory() {
            UUID id = request();
            approvals.approve(scopeA(), approver(), id, "ok");
            approvals.revoke(scopeA(), revoker(), id, "the patient withdrew the request");

            // ai_approval_decisions is APPEND-ONLY and is the evidence the mutable row's own comment points
            // at. This assertion predates the constraint repair and is unchanged by it: the restoration added
            // the row-level decider, it removed nothing from the history.
            assertThat(fixture.count("""
                select 1 from ai_approval_decisions d where d."approvalId" = :id
                """, Map.of("id", id)))
                .as("request, grant, then revocation")
                .isEqualTo(3);
            assertThat(fixture.text("""
                select d."fromStatus"::text || '->' || d."toStatus"::text
                  from ai_approval_decisions d
                 where d."approvalId" = :id and d."toStatus" = cast('REVOKED' as "AiApprovalStatus")
                """, Map.of("id", id)))
                .isEqualTo("APPROVED->REVOKED");

            // STRONGER than before: the two transitions carry DIFFERENT actors, and each matches what the
            // mutable row records. Before the repair this was only checkable on the decisions table, because
            // the row had no grantor to compare against.
            assertThat(actorOn(id, "APPROVED")).isEqualTo(AiFixture.APPROVER_A);
            assertThat(actorOn(id, "REVOKED")).isEqualTo(AiFixture.REVOKER_A);
            AiApprovalService.ApprovalRow row = approvals.require(scopeA(), id);
            assertThat(row.decidedByUserId())
                .as("the row's grantor and the history's grantor are the same fact")
                .isEqualTo(actorOn(id, "APPROVED"));
            assertThat(row.revokedByUserId())
                .as("and so is the revoker")
                .isEqualTo(actorOn(id, "REVOKED"));
        }

        /** The user who performed the transition into {@code toStatus}, from the append-only history. */
        private UUID actorOn(UUID approvalId, String toStatus) {
            return fixture.uuidValue("""
                select d."actorUserId" from ai_approval_decisions d
                 where d."approvalId" = :id and d."toStatus" = cast(:status as "AiApprovalStatus")
                """, Map.of("id", approvalId, "status", toStatus));
        }
    }

    @Nested
    @DisplayName("tenant isolation and attribution")
    class Isolation {

        @Test
        @DisplayName("another tenant's principal cannot read or grant this approval")
        void anotherTenantCannotReachIt() {
            UUID id = request();

            assertThatThrownBy(() -> approvals.require(scopeB(), id))
                .isInstanceOf(AiRefusedException.class)
                .satisfies(thrown -> assertThat(((AiRefusedException) thrown).detail())
                    .contains("no approval"));
            assertThatThrownBy(() -> approvals.approve(scopeB(), adminB(), id, "not mine to approve"))
                .isInstanceOf(AiRefusedException.class);
            // Untouched: a cross-tenant attempt is a refusal, never a write.
            assertThat(approvals.require(scopeA(), id).status()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("another PROJECT in the same tenant cannot reach it either")
        void anotherProjectCannotReachIt() {
            UUID id = request();
            TenantScope otherProject = new TenantScope(AiFixture.ORG_A, AiFixture.PROJECT_A2);

            assertThatThrownBy(() -> approvals.require(otherProject, id))
                .isInstanceOf(AiRefusedException.class);
        }

        @Test
        @DisplayName("the approval is attributed to the run, agent version and tenant that asked")
        void attributionIsComplete() {
            UUID id = request();

            AiApprovalService.ApprovalRow row = approvals.require(scopeA(), id);
            assertThat(row.runId()).isEqualTo(runId);
            assertThat(row.organizationId()).isEqualTo(AiFixture.ORG_A);
            assertThat(row.projectId()).isEqualTo(AiFixture.PROJECT_A);
            assertThat(row.requestedByUserId()).isEqualTo(AiFixture.ADMIN_A);
            assertThat(row.requestedByPrincipal()).isNotBlank();
        }

        @Test
        @DisplayName("the persisted argument shape carries names and types, never the customer's note")
        void argumentValuesAreNeverPersisted() {
            UUID id = request();

            String shape = approvals.require(scopeA(), id).argumentsRedacted();

            assertThat(shape).contains("doctorId").contains("day").contains("durationMinutes");
            assertThat(shape)
                .doesNotContain("SECRET-CUSTOMER-NOTE")
                .doesNotContain("11111111-1111-1111-1111-111111111111")
                .doesNotContain("2030-01-01");
            // And nothing in the whole approval history repeats it.
            assertThat(fixture.column("select d.\"note\" from ai_approval_decisions d where d.\"organizationId\""
                + " = :org", Map.of("org", AiFixture.ORG_A)))
                .noneMatch(note -> note.contains("SECRET-CUSTOMER-NOTE"));
            assertThat(fixture.column("select x.\"argumentsRedacted\"::text from ai_tool_executions x"
                + " where x.\"organizationId\" = :org", Map.of("org", AiFixture.ORG_A)))
                .noneMatch(persisted -> persisted.contains("SECRET-CUSTOMER-NOTE"));
        }

        @Test
        @DisplayName("a request's execution row is written WITH it, so the service can read its own row back")
        void requestWritesItsOwnExecutionRow() {
            // The regression. FIND_APPROVAL joins ai_approvals to ai_tool_executions on approvalId, because the
            // approved argument shape lives on the execution row. A version of request() that wrote only the
            // approval produced a PENDING row that approve(), reject() and consume() could never see --
            // failing as "no approval in this tenant" for a row that plainly exists.
            UUID id = request();

            assertThat(fixture.count("""
                select 1 from ai_tool_executions x
                 where x."organizationId" = :org and x."approvalId" = :approval
                """, Map.of("org", AiFixture.ORG_A, "approval", id)))
                .as("without this row the approval is unreadable by its own service")
                .isEqualTo(1);
            assertThat(fixture.text("""
                select x."decision"::text from ai_tool_executions x
                 where x."organizationId" = :org and x."approvalId" = :approval
                """, Map.of("org", AiFixture.ORG_A, "approval", id)))
                .isEqualTo("REQUIRE_APPROVAL");
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private UUID request() {
        return request(requester(), ARGUMENTS, null);
    }

    private UUID request(TenantContext principal, Map<String, Object> arguments, Long validityMinutes) {
        return approvals.request(scopeA(), principal, runId, AiFixture.CONSEQUENTIAL_TOOL,
            "the model requested " + AiFixture.CONSEQUENTIAL_TOOL + " for run " + runId, arguments,
            validityMinutes).approvalId();
    }

    private TenantContext requester() {
        return AiPrincipalScope.admin(AiFixture.ORG_A, AiFixture.ADMIN_A, List.of(AiFixture.PROJECT_A));
    }

    private TenantContext approver() {
        return AiPrincipalScope.admin(AiFixture.ORG_A, AiFixture.APPROVER_A, List.of(AiFixture.PROJECT_A));
    }

    /**
     * A third administrator: neither the requester nor the approver.
     *
     * <p>Used for revocation so the two actors are distinguishable. With the grantor as the revoker, an
     * implementation that overwrote {@code decidedByUserId} with the revoker's id would pass every
     * assertion — the single most likely way for the grant/withdrawal distinction to be lost silently.
     */
    private TenantContext revoker() {
        return AiPrincipalScope.admin(AiFixture.ORG_A, AiFixture.REVOKER_A, List.of(AiFixture.PROJECT_A));
    }

    private TenantContext adminB() {
        return AiPrincipalScope.admin(AiFixture.ORG_B, AiFixture.ADMIN_B, List.of(AiFixture.PROJECT_B));
    }

    private TenantScope scopeA() {
        return new TenantScope(AiFixture.ORG_A, AiFixture.PROJECT_A);
    }

    private TenantScope scopeB() {
        return new TenantScope(AiFixture.ORG_B, AiFixture.PROJECT_B);
    }

    private int decisionCount(UUID approvalId, String toStatus) {
        return fixture.count("""
            select 1 from ai_approval_decisions d
             where d."organizationId" = :org and d."approvalId" = :approval
               and d."toStatus" = cast(:status as "AiApprovalStatus")
            """, Map.of("org", AiFixture.ORG_A, "approval", approvalId, "status", toStatus));
    }

    /**
     * Moves an approval's whole window into the past.
     *
     * <p>Both timestamps, because {@code ai_approvals_expiry_check} is {@code expiresAt > requestedAt}:
     * ageing only {@code expiresAt} would be refused by the schema and the test would be asserting a
     * constraint rather than the gate's decision.
     */
    private void agePastExpiry(UUID approvalId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        fixture.jdbcUpdate("""
            update ai_approvals set "requestedAt" = :requested, "expiresAt" = :expires where "id" = :id
            """, Map.of("id", approvalId, "requested", now.minusMinutes(60), "expires", now.minusMinutes(30)));
    }

    /** Keeps the permission literal referenced, so its absence from {@code Permission} stays visible. */
    @Test
    @DisplayName("the consequential tool's permission is a literal, because no Permission value matches it")
    void thePermissionIsALiteral() {
        // Reported as a hook rather than worked around: AppointmentProposalTool.REQUIRED_PERMISSION is
        // "appointment:propose", and tenant-security has no Permission constant for it. A literal in a
        // constant is honest about the gap; inventing a Permission here would put an AI-phase enum constant
        // into a module this phase does not own, and the guard compares strings.
        assertThat(AppointmentProposalTool.REQUIRED_PERMISSION).isEqualTo("appointment:propose");
    }
}
