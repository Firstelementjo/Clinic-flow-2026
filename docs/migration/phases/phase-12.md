# Phase 12 — Dual-run, cutover, and decommission

**Status:** TASK FILE — orchestrator-owned, with the quality agent. **Not a phase
an agent may declare complete on its own.**
**Depends on:** Phases 7-11 green; the first Flyway-enabled boot rehearsed.

## Objective

Remove the legacy runtime **only after evidence proves parity**. This phase
produces evidence and performs the cutover; it does not decide to retire
anything.

## Work

1. **Shadow-read comparison** for selected endpoints: run old and new read paths
   and compare row counts, authorization decisions, status transitions,
   analytics, finance rollups, and exports.
2. **Compare, do not reconcile silently.** Every difference is explained. A
   mismatch that cannot be explained is a blocker, not a rounding note.
3. **Route migration behind a feature flag** by role / tenant / project.
4. **Bounded rollback window.** The old API stays available, read-only where
   possible.
5. **Backup/restore and migration rehearsal immediately before cutover** — the
   first Flyway-enabled boot of `firstelement` happens here, on a verified backup,
   with the 17-migration chain and its checksums recorded.
6. **Switch writes by domain**, deliberately, one domain at a time — never as a
   side effect of infrastructure.
7. **Monitor** errors, queue lag, reconciliation drift, connector delivery,
   export failures, AI outcomes.
8. **Assemble the decommission package**: migration evidence, security/tenant
   evidence, finance metric catalogue, export redaction policy, AI policy
   record, rollback plan, legacy endpoint archive, and the final inventory.
9. **Do not delete legacy code in this phase.** Archiving and documenting it is
   the deliverable.

## Acceptance gate (for the cutover act)

- Rollback rehearsed, not just documented.
- Data, authorization, finance, export, and acceptance checks green in a
  production-like staging environment.
- No high-severity open issue.
- Backup/restore evidence current.

## Stop condition — read this before doing anything here

`plan.md` §5 requires **two agreed observation windows and sign-off from the
product, operations, security, and finance owners** before the Node/React/Prisma
runtime is retired. The orchestrator can build the evidence package, run the
comparison, and perform a rehearsed cutover, but **cannot declare the legacy
runtime decommissioned**. That decision belongs to those four owners.

If asked to "finish" this phase, the correct completion is: cutover rehearsed,
evidence package complete, owners notified, decommission pending sign-off — not
"done".

## Open items carried into this phase

- **Backup encryption mechanism** (ADR 0005 §4) — an operations decision, still
  open, and it must be settled before the first cutover backup is treated as
  restorable evidence.
- **Retention** for the Phase 7-10a tables (ADR 0009 §4) — defaults accepted,
  purge jobs ship disabled until legal-hold state exists.
- **`ai_run_*` tenant-scope backfill** (ADR 0009 §2) — must complete before AI
  runs are read through the new tenant filters.