# ADR 0010 — Phase 9 hook requests: export queue, report permission, template ownership

Date: 2026-10-06
Status: **Accepted** (orchestrator rulings on the Phase 9 escalations)

Phase 9 could not complete three integration points from inside its ownership
globs. Each is decided here.

---

## 1. `EXPORT_RENDER` gets its own queue — ACCEPTED

`ExportJobHandler` currently publishes to `QueueName.DOCUMENT_PROCESSING`, borrowed
from the document pipeline. The agent's argument is the deciding factor: the two
workloads have **different failure profiles**, and sharing a queue lets one starve
the other.

That is a real risk, not a hypothetical one. Document rendering is bounded CPU work
on a fixed template; export rendering holds a **streaming database cursor** for the
length of the read, which means it holds a pooled connection and an open MVCC
snapshot. A burst of exports on a shared queue would consume connections that
document rendering needs, and the degradation would appear in the wrong workload —
the symptom would be "documents are slow", investigated in the wrong place.

Rules:

1. `QueueName.EXPORT_RENDER` is added, **separate** from `DOCUMENT_PROCESSING`.
2. Exports get their **own lease and batch configuration**, because a cursor-holding
   job and a bounded job do not share safe batch sizes.
3. The queue is tenant-scoped like every other claim, and shares no claim path with
   the org-wide sweep (ADR 0008: that sweep remains unimplemented).
4. Because an export can be cancelled mid-read, its dead-letter evidence records the
   cancellation distinctly from a provider failure — a cancelled export is not a
   failed one, and an operator must be able to tell them apart.

## 2. `REPORT_MANAGE` replaces `WORKFLOW_MANAGE` as the authoring permission — ACCEPTED

`ReportDefinitionService` gates report authoring on `Permission.WORKFLOW_MANAGE`,
documented as a named ADMIN-only proxy across 7 call sites. A dedicated
`REPORT_MANAGE` is the honest name: authoring a finance report is not workflow
management, and borrowing another domain's permission makes the audit record
(`REPORT_DEFINITION_CREATED` under a workflow grant) say something untrue.

Same pattern Phase 6a used for `WORKFLOW_MANAGE` / `SLA_MANAGE`. ADMIN receives it
automatically via `EnumSet.allOf`; no explicit grant to EMPLOYEE, so it cannot leak
the way an enumerated list can.

**Rule:** a permission is named after what it authorizes. If two domains need the
same grant, that is a signal to name the shared permission for what it is, not to
reuse one domain's name for the other.

## 3. `DocumentTemplateService` stays in the exports module — ACCEPTED

The templates are created by the exports migration pair and their only consumer is
report generation. The admin control plane may *read* template state (Phase 7 already
exposes export governance); **authoring** stays here.

Rationale: the template is part of a report's reproducibility contract. A version is
referenced by historical runs, so template authoring must obey the same
immutable-version discipline as `report_versions`. Splitting authoring away from the
code that resolves templates would put that discipline behind a module boundary
rather than in the code that depends on it.

The exposure the agent asked for is therefore an **operations read model**, not a
bean move: Phase 7's admin surface gains template inventory (which templates exist,
which versions, which reports pin each version), and that is a query, not a
dependency.

## 4. Recorded for the ledger, not decided here

**Attempt-history shape changed.** An attempt now writes one terminal row rather than
a `STARTED` row that nothing ever closed. This is a better record — a `STARTED` row
with no terminal is a gap, not evidence — but anything reading
`export_job_attempts` expecting `STARTED` needs to know. Recorded in the Phase 12
release notes.

**Download refusals are no longer transactionally atomic.** Each write is a single
guarded statement, so a partial sequence is not impossible in principle. The agent
judged a **persisted refusal record** worth more than the atomicity guarantee, and
agreed: a refusal that rolls itself back is not evidence of anything, and the
schema constraints already make the important steps individually atomic. Accepted as
a deliberate trade, and noted so a future reader does not "fix" it back.