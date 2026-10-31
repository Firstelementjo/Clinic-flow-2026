# Phase 7 — Admin control plane

**Status:** IMPLEMENTED — green on both owned modules.
**Owner agent:** `firstelement-admin`
**Depends on:** Phase 5b (outbox/jobs/DLQ read models), Phase 3 (effective
permissions + security events), Phase 6 (workflow versions and failed
executions). Requires the audit-stream split (`V202610040008`) if it is to be
promoted — otherwise it ships on the single `audit_logs` table by convention and
says so.

**Delivery note (2026-10-05).** The audit-stream split is promoted, so this phase
writes to the three new relations (`admin_audit_events`, `security_events`,
`job_integration_events`) through `AdminAuditStreamWriter`. The legacy
`AuditLogSecurityEventPublisher` in `:backend:identity-access` is still the live
`SecurityEventPublisher` bean, so worker-side security events still land in
`audit_logs`; the audit read model unions all four physical sources and labels
every row with the table it came from rather than pretending the split is
complete.

## Objective

Answer one question for an operator: **is the system controlled, secure, and
operating?** Every action is a typed, allow-listed, audited command with a scope,
an impact count, and a result.

## Scope

Build `backend/administration/**` and `backend/operations-observability/**`:

- system overview read models;
- users, memberships, sessions, service principals, API keys, and effective
  permissions;
- jobs, outbox depth, queue lag, retries, dead letters, and **safe replay**;
- connector and webhook health: attempts, signature failures, rate limits, last
  inbound/outbound;
- migration, backup, and restore evidence surfaces (read-only, from the Phase 2
  tooling);
- audit, security, retention class, legal hold, privacy deletion jobs, export
  activity;
- search/vector freshness, pending index work, deletion propagation, knowledge
  gaps;
- AI policy, provider status, approved models, tool policy, blocked calls,
  approvals, budgets, redaction failures (per ADR 0007 Part 2);
- realtime connection and resync health;
- allow-listed maintenance commands **only**.

## Ownership globs

- `backend/administration/**`, `backend/operations-observability/**`
- `settings.gradle.kts`, `backend/app-bootstrap/build.gradle.kts` (wiring only)
- `docs/migration/phases/phase-07.md`

## Forbidden — non-negotiable

- **No raw SQL console.** No arbitrary query surface, ever.
- **No secret display.** Connector secrets, API keys, tokens, and encryption
  keys are write-only and redacted. `FieldRedactor` already encodes this; an
  admin principal does not bypass it.
- **No arbitrary shell.**
- **No silent destructive action.** Tenant data deletion requires a typed
  request, impact count, policy check, retention/legal-hold check, and audit
  record.
- Replay must be **safe and idempotent** — only jobs whose handler declares
  replay-safe may be replayed, and the replay is itself audited.
- Do not widen any permission beyond what the phase needs.

## Acceptance gate

1. An operator can diagnose a failed message, webhook, export, vector ingestion,
   or worker **without database access**.
2. Every safe action shows its scope and result and produces an audit record.
3. Cross-tenant negatives on every admin read model, including effective
   permissions for a user in another tenant.
4. A deleted-or-disabled tool and a denied AI action are visible to an operator
   without exposing customer content.

## Test commands

```powershell
.\ops\quality\run-gates.ps1 -Stages "backend-compile,backend-unit,backend-integration,security-tenant-negative,no-hidden-skips"
```

## Rollback

Admin is read-mostly by construction; typed actions are individually
feature-flagged. Rollback is disabling the admin route set. No destructive
migration.

## Decision (orchestrator, 2026-10-05): `FE-WEB-003` resolved

`FE-WEB-003` was advisory: several controllers bind a client `projectId` and name
no permission at the HTTP edge, relying on service-layer enforcement (a
documented Phase 3/4 decision, proven by the integration tests).

**Decision: name the permission at the HTTP edge for every Admin and
operations-observability route in this phase.** Service-layer enforcement stays —
it is the boundary, not a substitute — but an administrative endpoint whose only
declaration is `authenticated()` is a route whose authorization nobody can review
from the controller. The stricter option is chosen because Admin is the surface
most likely to be attacked and least likely to be exercised by users, which is
exactly where an unreviewed default survives longest.

Recorded as an accepted, deliberate deviation: the CRM/inbox controllers migrated
in Phase 4 keep their current shape, and widening that is a per-route decision by
the CRM owner rather than a blanket change.

---

## What shipped

Two modules. `:backend:administration` answers "is the system controlled and
secure?"; `:backend:operations-observability` answers "is it operating?". The
split is along the read/write line rather than the domain line: administration
owns everything that **changes** state and every audit trail, and
operations-observability owns the diagnosis read models that sit next to a change
without making one.

`:backend:operations-observability` depends on `:backend:administration` rather
than duplicating it. The audit writers, the legal-hold gate and the maintenance
allow-list are owned there, so there is **one** allow-list, **one** permission
decision on an operator action, and **one** audit writer in the system. The two
operational write routes — replay and dead-letter resolution — are pure
delegations to `MaintenanceCommandService`.

### The allow-list

`MaintenanceCommand` is a closed enum. There is no route that accepts a command
name, a table name, a SQL fragment or a free-form row, and
`MigrationBackupAndOverviewIntegrationTest#SurfaceShape` reads every SQL constant
in all eight repositories reflectively and fails on `delete from`, `truncate`,
`drop`, `alter`, `; delete`, `information_schema` and `pg_catalog`.

`ReplaySafetyPolicy` declares exactly four replay-safe queues —
`incoming-message`, `outbound-send`, `notification-fanout`,
`outbox-maintenance` — each with the database-level idempotency that absorbs a
duplicate written next to it. Every other queue is refused, including queue names
the table has never heard of, so a new `QueueName` constant is inert until a
reviewer adds a row with a reason.

`EXECUTE_PRIVACY_DELETION` and `PURGE_RETENTION` ship **declared but disabled**
(ADR-0008 §2 rule 5, ADR-0009 §4). Both routes exist, both refuse, both return
`applied = false` with `DISABLED_BY_POLICY`, and both write an audit row. The
presence is what makes the capability visible to an operator and reviewable by a
regulator; the refusal is what keeps it from being a button.

### What is never projected

Asserted on the **record shape** and, over HTTP, on the **rendered response** —
so a later serialisation change cannot start emitting them:

| Never projected | Why |
| --- | --- |
| `users."passwordHash"`, `sessions."tokenHash"` | absent from the SELECT list, so there is no value to leak |
| `channel_connections."credentials"`, `"webhookSecret"` | presence booleans the database computes |
| `outbox_events."payload"` | for an inbound webhook this is the one place customer prose waits in this schema |
| `job_attempts."errorMessage"` | provider free text; `errorCode` + `retryClass` is the searchable part |
| `ai_tool_executions."argumentsRedacted"` / `"resultRedacted"` | the *shape* of a customer record is still customer content |
| `knowledge_gaps."query"` | a gap is by definition something a customer typed that matched nothing |
| `document_chunks."content"` | index freshness is a state view, not a copy of the knowledge base |
| `export_jobs."filters"`, `"authorizationSnapshot"` | a filter can name a date of birth; a snapshot records somebody's permissions on the day |
| `workflow_versions."stateMachine"` | an Admin read model is not a configuration editor |

### "Absent" is never zero

Every rollup seeds its declared vocabulary at zero, and four distinct answers are
kept distinct wherever a source can be missing:

- backup evidence — `NO_EVIDENCE_ROOT` (unset), `NO_REHEARSALS` (set, nothing
  written), `NO_EVIDENCE` (a directory with no artefacts), `CONFIGURED`;
- queue failure rate — `-1` means "no attempts in the window", not a healthy 0%;
- webhook rejection ratio — `null` over a window with no traffic;
- `retentionClass` — the visible token `UNDECLARED`, not a plausible-looking
  default, because ADR-0009 §4 rule 4 makes it the signal that a purge must not
  run.

## Defects this phase found and fixed in its own code

Recorded because each was a live failure, not a theoretical one, and each is now
covered by a regression test.

1. **`LegalHoldService.gate` had the purge gate inverted.** It passed
   `!covering.isEmpty()` as `mayPurge`, so the gate reported *open* while a legal
   hold was active — the exact failure ADR-0008 rule 5 exists to prevent. Found by
   `MaintenanceCommandIntegrationTest#gateFollowsHoldState`.
2. **`MigrationStateRepository.stamp()` read `count(*)` with `rs.getBoolean`.**
   The driver refuses it (`Cannot cast to boolean: 17`), so `GET
   /api/v1/admin/migrations` and `GET /api/v1/admin/overview` returned **500 on a
   correctly migrated database**. Found by the HTTP suite, which is the only layer
   that could observe it. Fixed by computing `stamped` as a boolean in SQL and
   selecting the count as the number it is.
3. **`BackupRestoreEvidenceReader`'s name pattern accepted `..`.**
   `[A-Za-z0-9._-]+` matches a traversal segment, so the traversal only walked
   through because of the resolve-and-contains assertion *below* the check —
   defence in depth doing the work the first check was supposed to do. Tightened to
   require a leading alphanumeric; `..` and `.hidden` are now refused at the name
   check in every configuration, and the name is validated **before** the root is
   consulted so the rule does not depend on whether a root is configured.
4. **`BackupRestoreEvidenceReader.latest()` collapsed two states.** A configured
   but empty evidence root answered `NO_EVIDENCE_ROOT`, telling an operator their
   directory is unset when it is set and empty. Split into `NO_REHEARSALS`.
5. **`PrivacyDeletionRepository.countsByStatus()` returned only the states it
   happened to have.** A rollup that lists only what exists cannot be read as a
   complete picture, so all six declared states are now seeded at zero.
6. **A retained deletion target used `RECORD` as its `targetType`.**
   `privacy_deletion_targets_target_type_check` has no `RECORD` value, so every
   request under a legal hold failed with a constraint violation. The target is now
   typed as the subject type, and `SubjectType.TENANT` — which has no counterpart in
   the target vocabulary — refuses with a message naming the reason instead of
   inventing a value the schema never declared.
7. **`WorkflowOperationsService` passed a tenant-scoped scope to two per-project
   statements.** `WorkflowHistoryRepository.RUN_SUMMARY` and `COLLISION_TALLY` bind
   `__fe_tenant_project_id` unconditionally, so both `/api/v1/operations/workflows`
   and `/api/v1/operations/workflow-failures` failed. Both now project through the
   caller's server-resolved `projectIds`, the same approach the Admin security-stream
   route takes.
8. **`LegalHoldRepository`'s covering statements omitted `"version"`.** The row
   mapper reads it, so every cover query threw.

## Schema gaps reported to the orchestrator (no DDL written by this phase)

1. **`legal_holds_scope_pair_check` has no arm for `TABLE` or `RECORD`.** It reads
   `(scopeType = 'TENANT' and scopeRef is null) or (scopeType = 'PROJECT' and
   projectId is not null)`, while `legal_holds_scope_ref_check` explicitly permits
   a `TABLE`/`RECORD` hold with a `scopeRef`, and `LegalHoldScopeType` declares both
   values. The conjunction is unsatisfiable for those two values, so a narrow hold
   cannot be placed at all and the `narrow` count in the covering read model is
   structurally unreachable. Asserted in
   `MigrationBackupAndOverviewIntegrationTest#aTableHoldIsStructurallyImpossible`.
2. **No `api_keys` table and no `service_principals` table.** FR-6 asks for both.
   Service principals are answered from the `PrincipalType × Capability` matrix the
   boundary enforces at runtime, plus the machine principals this tenant has
   actually been seen using, read from the audit streams. API keys have no
   representation at all, so credential **presence** per connector is reported as a
   database-computed boolean instead.
3. **`privacy_deletion_requests."subjectUserId"` is NOT NULL with a composite FK to
   `users`**, even when `subjectType = 'CUSTOMER`. A deletion for a customer who has
   no `users` row cannot be requested. The request path checks it before the insert
   so the failure is a non-disclosing denial rather than a foreign-key violation.

## Known follow-ups

- **No `JOB_OPERATE` permission.** Replay and dead-letter resolution run on
  `SYSTEM_HEALTH_READ`, which is an administrative read permission. A dedicated
  `JOB_OPERATE` owned by `:backend:tenant-security` would let an operations reader
  recover work without the administrative capability set.
- **No `WORKFLOW_READ` permission.** The workflow panel borrows
  `SYSTEM_HEALTH_READ` for the same reason. Asserted in
  `WorkflowAndRoutePermissionIntegrationTest` so the gap stays visible.
- **The search-freshness and knowledge-gap panels are reachable by an employee**,
  because `KNOWLEDGE_READ` has been in the employee grant since Phase 3 and this
  phase may not widen or add permissions. The read is ingestion-state metadata and
  counts, never chunk content; a dedicated read permission is the follow-up if that
  is judged too wide.

## Test commands actually run

```powershell
./gradlew.bat :backend:administration:test :backend:operations-observability:test `
  --rerun-tasks --no-build-cache --no-daemon --console=plain
```

**166 tests, 0 failures, 0 skipped.** `:backend:app-bootstrap:compileJava` is also
green with both modules wired in.

| Suite | Module | Count |
| --- | --- | --- |
| `AdminAuditStreamIntegrationTest` | administration | 16 |
| `AdminRoutePermissionHttpIntegrationTest` | administration | 12 |
| `AiAndExportGovernanceIntegrationTest` | administration | 16 |
| `IdentityDirectoryIntegrationTest` | administration | 12 |
| `MaintenanceCommandIntegrationTest` | administration | 30 |
| `MigrationBackupAndOverviewIntegrationTest` | administration | 23 |
| `ConnectorAndSearchOperationsIntegrationTest` | operations-observability | 20 |
| `JobDiagnosisIntegrationTest` | operations-observability | 14 |
| `OperationsRoutePermissionHttpIntegrationTest` | operations-observability | 11 |
| `WorkflowAndRoutePermissionIntegrationTest` | operations-observability | 12 |

Every suite runs against the real Flyway chain on a pgvector/pgvector:pg16
container, so a column name that does not exist is a test failure rather than a
production surprise.

## Hand-off: two contract files this phase cannot edit

Both are owned by the orchestrator, and both currently fail against this phase's
routes. Recorded here so the edits are reviewable rather than discovered.

### 1. `ops/quality/checks/check-architecture.ps1` — FE-ARCH-001

`$allowedMainDeps` needs the two modules and their permitted edges, plus
`administration` and `operations-observability` added to `app-bootstrap`'s
permitted list:

```powershell
    # Phase 7. Administration owns every state change and every audit trail; it may read
    # the worker's outbox/job history and the connector state, and must not depend on
    # inbox-helpdesk or notifications-realtime (an Admin panel is not a delivery path).
    'administration'          = @('shared-kernel', 'tenant-security', 'identity-access',
                                  'worker-runtime', 'channels', 'crm-core')
    # Phase 7. The operations module holds diagnosis read models only. It depends on
    # administration rather than duplicating it, so there is one allow-list and one audit writer.
    'operations-observability' = @('shared-kernel', 'tenant-security', 'administration',
                                    'channels', 'notifications-realtime',
                                    'workflows-sla-routing', 'worker-runtime')
    'app-bootstrap'           = @(..., 'administration', 'operations-observability')
```

**FE-TENANT-001 is at exactly its ceiling: 3 fragments, ceiling 3.** All three are
`MigrationStateRepository`'s reads of `flyway_schema_history`, which is
infrastructure state with no tenant and no tenant data. The ratchet must not rise;
a fourth such fragment in this phase should be composed through `ScopedWhere` so
the scan recognises it, exactly as Phase 6 did for `RoutingRepository`.

### 2. `tests/contracts/openapi-paths.json` — the route inventory

45 new routes are declared and none are in the committed inventory:
32 under `/api/v1/admin` (23 read models, 2 catalogue, 7 typed maintenance) and 13
under `/api/v1/operations`. Regenerate with the generator, never by hand — the
file says so, and the diff is the reviewable artefact:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File ops/quality/checks/check-openapi-contract.ps1
```

### 3. `tests/contracts/expected-suites.json` — the suite manifest

Ten entries, all `stage: backend-integration`, `services: ["postgresql", "pgvector"]`:

| suite | module | minTests | kind |
| --- | --- | --- | --- |
| `com.firstelement.admin.AdminAuditStreamIntegrationTest` | administration | 16 | integration |
| `com.firstelement.admin.AdminRoutePermissionHttpIntegrationTest` | administration | 12 | security |
| `com.firstelement.admin.AiAndExportGovernanceIntegrationTest` | administration | 16 | security |
| `com.firstelement.admin.IdentityDirectoryIntegrationTest` | administration | 12 | security |
| `com.firstelement.admin.MaintenanceCommandIntegrationTest` | administration | 30 | security |
| `com.firstelement.admin.MigrationBackupAndOverviewIntegrationTest` | administration | 23 | integration |
| `com.firstelement.observ.ConnectorAndSearchOperationsIntegrationTest` | operations-observability | 20 | integration |
| `com.firstelement.observ.JobDiagnosisIntegrationTest` | operations-observability | 14 | integration |
| `com.firstelement.observ.OperationsRoutePermissionHttpIntegrationTest` | operations-observability | 11 | security |
| `com.firstelement.observ.WorkflowAndRoutePermissionIntegrationTest` | operations-observability | 12 | security |

`minTests` is a floor, not the current count, so these are the reviewed numbers
and a suite that loses tests needs a decision rather than a smaller number.

### 4. One out-of-globs change in the working tree

`backend/worker-runtime/src/test/java/com/firstelement/worker/WorkerRuntimeHealthIntegrationTest.java`
is modified and is **not** in this phase's ownership globs. It changes
`claimFor` to claim whatever is actually claimable and then age that row, rather
than assuming the claim takes the row just seeded. It predates this session and
this phase did not touch it; the orchestrator should accept it or revert it
deliberately.
