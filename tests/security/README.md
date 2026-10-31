# Security negative-test specification

`plan.md §7.2`'s sixteen critical negative tests, expressed as an executable
specification, plus the validator that keeps the specification honest.

| File | Role |
| --- | --- |
| `negative-spec.json` | the sixteen items: 11 enforced, 5 staged |
| `check-negative-spec.ps1` | proves each item is backed by a suite that exists and ran |

## Reference, never duplicate

The right disposition for a critical negative test that already exists is a
**reference**. These tests live inside the Spring modules, next to the code
they describe, written by the agent that owns that code. A second copy in
`tests/security/` would:

- drift from the original within one phase;
- be the copy someone edits to make a build pass;
- give a reviewer two places to look and no reason to prefer either.

So every `enforced` entry names the suite, and the specific `@DisplayName`
where the exact assertion matters. The validator then fails when:

- one of the sixteen `plan.md §7.2` items has no entry at all;
- a requirement's text is a paraphrase rather than the plan's wording —
  a paraphrase is how a requirement quietly changes meaning;
- an `enforced` entry names a suite that is not in
  `tests/contracts/expected-suites.json` (it was renamed, deleted, or is not
  required to run);
- a named test case is not present in the JUnit report the run produced;
- an entry is both `enforced` and `staged`;
- a `staged` entry has no owning module, no phase, or no gate stage.

Run it:

```powershell
.\tests\security\check-negative-spec.ps1 `
    -ResultsDir backend/tenant-security/build/test-results/test,backend/identity-access/build/test-results/test
```

## Coverage

| ID | Requirement (plan.md §7.2) | Status | Backed by |
| --- | --- | --- | --- |
| NEG-01 | no membership returns no rows | enforced | `TenantIsolationQueryIntegrationTest`, `CrmCoreIntegrationTest`, `TenantQueryGuardTest` |
| NEG-02 | project ID from another tenant cannot be used for read or write | enforced | `TenantIsolationQueryIntegrationTest`, `CrmCoreIntegrationTest` |
| NEG-03 | unauthorized aggregates do not reveal counts or sums | enforced | `TenantIsolationQueryIntegrationTest` |
| NEG-04 | unauthorized rows/fields do not appear in CSV or DOCX | **staged** | `backend/exports`, Phase 9 |
| NEG-05 | export download reauthorizes and expires | **staged** | `backend/exports`, Phase 9 |
| NEG-06 | AI retrieval excludes another project even with a zero similarity threshold | **staged** | `backend/knowledge-ai`, Phase 10 |
| NEG-07 | AI tool arguments cannot override tenant scope | enforced | `NonHttpBoundaryTest$AiTools`, `TenantIsolationQueryIntegrationTest` |
| NEG-08 | disabled tools are denied and recorded | enforced | `NonHttpBoundaryTest$AiTools` |
| NEG-09 | human takeover aborts an in-flight AI send | enforced | `InboxJourneyIntegrationTest`, `OutboundCrashRecoveryIntegrationTest` |
| NEG-10 | duplicated webhooks create one message and one usage event | enforced (message half) | `ProviderEventDeduplicationIntegrationTest` |
| NEG-11 | worker crash after external send does not duplicate on retry | enforced | `OutboundCrashRecoveryIntegrationTest`, `OutboundDeliveryIntegrationTest`, `OutboxLifecycleIntegrationTest` |
| NEG-12 | outbox dispatch after commit does not publish rolled-back changes | enforced | `OutboxSameTransactionIntegrationTest`, `OutboxEnqueuerTransactionTest` |
| NEG-13 | replay is safe and audited | enforced | `OutboxDeadLetterReplayIntegrationTest`, `OutboxLifecycleIntegrationTest` |
| NEG-14 | rate-card or period changes do not mutate closed snapshots | **staged** | `backend/reporting-finance`, Phase 8 |
| NEG-15 | realtime reconnect resyncs state instead of trusting missed events | enforced | `NotificationDeliveryIntegrationTest`, `NonHttpBoundaryTest$Realtime` |
| NEG-16 | deleted knowledge is not retrievable after propagation completes | **staged** | `backend/knowledge-ai`, Phase 10 |

### Two honest partials

**NEG-10** — the *message* half is enforced today (one provider event id
produces exactly one message and one delivery intent, across five
redeliveries, and the database itself refuses a second row). The *usage
event* half cannot be: the usage ledger is Phase 8. A duplicate that produced
two usage events is a Phase-8 regression to add, and `NEG-14` covers the
ledger's other half.

**NEG-09** — the mode transition guarded by `modeVersion` and the
conditional outbound send are both asserted, but a dedicated concurrency
test that takes over a thread *while* a send is in flight inside the same
transaction window is still owed to Phase 6. It is recorded here rather than
claimed.

## The static half

`FE-SEC-002` in `ops/quality/checks/check-architecture.ps1` fails the build
if a controller binds a client-supplied tenant identifier. A client may never
*name* the tenant: it comes from `TenantContext`. `projectId` may still
appear on the wire, because the legacy client sends it and the server
validates it against the server-resolved scope.

`FE-WEB-003` is **advisory**: controllers that bind a `projectId` and name no
permission at the HTTP edge are recorded, not failed. Enforcement is on the
service — that is the documented Phase 3/4 decision and it is proven by the
cross-tenant integration tests. What is missing is that the route itself does
not say which permission it needs. Phase 7 closes it.

See [`docs/migration/phases/quality-gates.md`](../../docs/migration/phases/quality-gates.md) §2, stages 05 and 08.