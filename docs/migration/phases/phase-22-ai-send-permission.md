# Phase 22 — the AI send is authorized by a write permission (P0, ADR-0010 §2)

Status: **fixed and verified**. Two production lines, one new suite of 14 tests.
No migration. No grant-table change.

| | |
|---|---|
| Defect | `AiSendService` guarded a **customer-visible write** with `Permission.AI_RUN_READ`, a **read** permission |
| Consequence | `Capability.KNOWLEDGE_RETRIEVE` grants `AI_RUN_READ` but not `AI_SEND`, so a read-oriented retrieval principal passed the gate on the path that writes an AI-authored message into a customer's conversation |
| Production change | `backend/workflows-sla-routing/.../service/AiSendService.java` — `stageAiReply` and `confirmAiSend` now check `Permission.AI_SEND` |
| Verification | `AiSendPermissionIntegrationTest` — 14 tests. Against the pre-fix code, **7 of the 14 fail** |
| Migration impact | **none** |
| Grant table | **unchanged** — `AI_SEND` was already granted to exactly `Capability.AI_RUN` and to `ADMIN` |
| Blocking on | an orchestrator design ruling for the *reachability* of the send, already recorded in the ledger; this phase does not change that |

---

## 1. What was wrong

`AiSendService` is the two-phase AI send. `stageAiReply` writes the `PENDING`
outbound `messages` row; `confirmAiSend` publishes the delivery intent to the
outbox in the same transaction as the guard re-read. Both halves write something
a customer receives.

Both halves authorized that with `Permission.AI_RUN_READ`:

```java
// AiSendService, before
authorization.check(runContext, Permission.AI_RUN_READ);   // stageAiReply
authorization.check(runContext, Permission.AI_RUN_READ);   // confirmAiSend
```

ADR 0010 §2 — *a permission is named after what it authorizes* — rules that out.
And the history is recorded inside the test that guards the grant table:

> `AuthorizationServiceTest:317` — *"Phase 6 escalated one decision: authoring a
> workflow reused `ESCALATION_RULE_MANAGE` and a send reused the read-only
> `AI_RUN_READ`. Both were wrong in the same direction — a read or a narrow-rule
> grant standing in for a write — so these three exist as separate permissions."*

`Permission.WORKFLOW_MANAGE` and `Permission.SLA_MANAGE` were created for that
reason and were moved onto their call sites. `Permission.AI_SEND` was created for
the same reason and was **never moved onto its call site**. The two lines above
are the residue.

### Why it was reachable, not theoretical

`RolePermissions` grants `AI_RUN_READ` to two different capabilities:

```java
Map.entry(Capability.KNOWLEDGE_RETRIEVE,
    EnumSet.of(Permission.KNOWLEDGE_READ, Permission.AI_RUN_READ)),   // no AI_SEND
Map.entry(Capability.AI_RUN,
    EnumSet.of(Permission.AI_RUN_READ, Permission.AI_SEND)),          // AI_SEND
```

So a principal holding only `KNOWLEDGE_RETRIEVE` — a retrieval capability, named
`…RETRIEVE`, whose whole purpose is scoped vector search — passed the check that
guards an AI-authored customer reply. A principal holding `AI_TOOL_EXECUTE` did
not, and still does not: that maps to `{CONVERSATION_READ, CUSTOMER_READ}`.

This is the same defect class as the two the grant table already defends against,
and the module on the other side of the edge had it right:
`ai-rag`'s `AiSendAuthorization` checks `Permission.AI_SEND` alone, with a comment
recalling that an earlier version *also* required `Capability.AI_RUN` and thereby
locked every human out. Two implementations of the same gate had drifted in
opposite directions — `ai-rag` too strict for humans, `AiSendService` too loose
for retrieval principals.

## 2. The change

Two lines, and the Javadoc above them, which asserted the old behaviour as a
deliberate open decision ("`AI_RUN_READ` is the authorization a send-shaped
action can require today; the Phase-6 report records the open decision for a
dedicated AI-send capability"). Leaving that comment would have been a lie in the
file that documents the fix.

```java
// AiSendService, after
authorization.check(runContext, Permission.AI_SEND);   // stageAiReply
authorization.check(runContext, Permission.AI_SEND);   // confirmAiSend
```

Both phases, not just the first. `confirmAiSend` publishes the delivery intent,
so it is a write; and the two phases may be reached by different callers, so the
second cannot assume the first one's decision.

**Nothing else changed.** No grant, no capability, no role, no schema.

## 3. Who is now excluded — the falsifiable half

"A sender is still allowed" proves nothing here, because `AI_RUN` grants
`AI_RUN_READ` too. Both would pass before and after. So the suite is built around
the exclusion.

Two machine principals, both scoped to tenant A's first project, built through
`PrincipalScope` (new helpers `aiAgent()` and `knowledgeRetriever()`):

| Principal | Capabilities | `AI_RUN_READ` | `AI_SEND` |
|---|---|---|---|
| `aiAgent()` | `AI_RUN` | yes | **yes** |
| `knowledgeRetriever()` | `KNOWLEDGE_RETRIEVE` | yes | **no** |

Same conversation, same run, same `capabilityScope`. The only difference is the
permission.

| Nested class | Tests | Claims |
|---|---:|---|
| the send permission is granted to a send capability and nothing read-only | 4 | `AI_SEND` is in exactly one `BY_CAPABILITY` set, and that set is `AI_RUN`; no capability whose name ends `_READ` grants it; `EMPLOYEE` still lacks it, `ADMIN` has it |
| a principal holding `AI_RUN` can still send on both phases | 3 | `AI_RUN` stages (`STAGED`, row exists), confirms (`PUBLISHED`, exactly one intent); `ADMIN` reaches both — **no human path is locked out** |
| phase 1 refuses a principal with `AI_RUN_READ` but not `AI_SEND` | 3 | `AccessDeniedException`; **zero** AI `messages` rows, not merely zero `PENDING`; **zero** outbox intents; **zero** run actions |
| phase 2 refuses the same principal, after a legitimate stage | 2 | denied even when the staged message came from a legitimate sender; zero intents; staged row untouched |
| the refusal is audited like any other authorization failure | 2 | an `ACCESS_DENIED` row on the stream, surviving the refused transaction, attributed to the machine principal and its own tenant; metadata carries `ai-send` and **no customer content** |

**14 tests. Against the pre-fix code, 7 fail** — every test whose subject is the
refusal. The 7 that pass in both states are the grant-table facts and the
legitimate-sender positives, which is exactly the split that makes the suite
meaningful: the positives hold the door open and the negatives prove it was open
too wide.

### Asserting the refusal, not the exception

Each phase asserts four observable facts after the denial, so a future change
that caught the exception and carried on writing would fail:

- **the message row was not written.** Not "no `PENDING` row" — no `AI`-authored
  row at all, so a retry, a dispatcher poll or a manual confirm has nothing to
  pick up.
- **the outbox intent was not published.** Counted across the whole
  `OUTBOUND_SEND` queue for the tenant, not just for one message id, so a second
  route to delivery would be caught too.
- **no run action was appended.** The run's history must not claim a send that
  did not happen.
- **the denial names the permission.**

On that last point: `AuthorizationService#denyRole` publishes the permission's
wire value into the event metadata and the exception the caller receives is a
bare `"Access denied"`. So the suite reads the name off the audit record
(`metadata->>'permission'`), which is where it belongs. It is *not* read from
`resourceId` — that column is null for a grant denial, which is a property of
`SecurityEvent.denial`'s builder and is worth knowing before someone writes a
second test that assumes otherwise.

The audit assertion is a persistence claim, not a bean claim:
`AuditLogSecurityEventPublisher` is `REQUIRES_NEW`, so the row is written even
though the business transaction it describes rolled back. An audit trail that
disappears with the failed operation is not a trail.

## 4. `AI_SEND` in the grant table — read, not changed

Asked whether `AI_SEND` is granted anywhere it should not be. Read every entry of
`RolePermissions`:

| Granting set | Kind | `AI_SEND`? |
|---|---|---|
| `Capability.AI_RUN` → `{AI_RUN_READ, AI_SEND}` | machine, a send capability | **yes** |
| `UserRole.ADMIN` → `allOf(Permission)` | human administrator | yes, by construction |
| `UserRole.EMPLOYEE` | explicit list of 39 | **no** |
| `Capability.KNOWLEDGE_RETRIEVE` | read | no |
| `Capability.AI_TOOL_EXECUTE` | read | no |
| `Capability.MESSAGING_SEND` | write, but `CONVERSATION_REPLY`/`CHANNEL_READ` | no |
| every other capability entry | — | no |

**`AI_SEND` appears in exactly one capability set, and that set is not read-only.**
No capability whose name ends `_READ` grants it — asserted as a loop over
`Capability.values()`, so a future capability added to that set fails the suite
rather than slipping past a hand-written list. `AI_TOOL_EXECUTE` → not granted is
already asserted in `tenant-security` and in `ai-rag`'s
`SendHandoffRaceIntegrationTest > holding AI_TOOL_EXECUTE alone does not grant a
send`; the new assertion is the exhaustive one.

`MESSAGING_SEND` is the adjacent question and the answer is deliberate:
`CONVERSATION_REPLY` lets a principal reply in a conversation, `AI_SEND` decides
whether the principal is the kind that may put an *AI-authored* message in front
of a customer. Those are different acts and ADR 0007 Part 2 keeps the mode and
takeover decision in application policy. Widening either set would be a security
decision, not a bug fix, so nothing was changed.

## 5. Tests

```
gradlew.bat :backend:workflows-sla-routing:test \
  --tests "com.firstelement.automation.AiSendPermissionIntegrationTest"
```

`JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`, and
`-Dorg.gradle.jvmargs="-Xmx2g -Dfile.encoding=UTF-8 -Djava.net.preferIPv4Stack=true"`
(Testcontainers cannot otherwise bind on this host).

| Suite | Tests | Result |
|---|---:|---|
| `AiSendPermissionIntegrationTest` (new) | 14 | pass |
| `AiSendPermissionIntegrationTest` (against pre-fix code) | 14 | **7 fail** |
| `:backend:workflows-sla-routing:test` (whole module) | 245 | 0 fail, 0 skip |
| `SendHandoffRaceIntegrationTest` (`:backend:ai-rag`) | 18 | pass |
| `:backend:ai-rag:test` (whole module, run incidentally) | 223 | 0 fail, 0 skip |
| `AuthorizationServiceTest` (`:backend:tenant-security`) | 16 | pass |
| `HumanTakeoverConcurrencyIntegrationTest` | 9 | pass |

`HumanTakeoverConcurrencyIntegrationTest` is the suite that would catch a
regression here, and it exercises `AiSendService` through `PrincipalScope.adminA()`
— an `ADMIN`, who holds `AI_SEND`. It passes unchanged, unmodified. The
concurrency coverage is untouched: no test in it was edited, relaxed, re-run with
retries, or skipped.

`SendHandoffRaceIntegrationTest > who may send` asserts the same grant-table
facts from the other side of the module edge and passes unchanged, which is the
independent confirmation that the two send gates now agree.

## 6. Observed, not fixed

Two adjacent findings. Both were reported before this phase, both are recorded in
the ledger, both are outside this file's ownership, and **neither was touched**.

1. **`customerVisibleRequiresApproval` defaults to `true`** in the migration
   (~line 298). Any gate wired to it refuses every reply until a tenant opts out.
   The default is arguably the safe one; what is wrong is that the send path is
   the only consumer and nothing surfaces the opt-out. A design question.

2. **`AiOrchestrator` has zero production callers and there is no bridge** from an
   `ai_runs`-keyed result to the `workflow_runs`-keyed send. Confirmed and, in
   fact, worse than recorded: **`AiSendService` itself has zero production
   callers too** — a repo-wide search for `AiSendService`, `stageAiReply` and
   `confirmAiSend` finds only the class, two Javadoc mentions in other modules,
   and test suites. So the whole two-phase send is unreachable in production, and
   this fix hardens a code path nothing calls yet.

That second point is the honest limit of this phase and must not be reported as
more than it is. The gate is now correct; the send is still not reachable, for
the same reason it was not reachable before. Both halves need the orchestrator
design ruling, and the module edge is forbidden in both directions — a bridge is
an orchestrator decision, not a wiring change.