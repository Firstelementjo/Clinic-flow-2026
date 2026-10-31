# ADR 0008 — Outbox retention and the org-wide claim sweep

Date: 2026-10-05
Status: **Accepted** (operational decision; Phase 5a risks 4 and 7)

Closes two questions Phase 5a and Phase 5b left open. Both were flagged as
risks that Phase 7's Admin surfaces would otherwise depend on silently.

---

## 1. The org-wide claim sweep is not implemented

Phase 5a created `outbox_events_dispatch_sweep_idx` to support a tenant-less
sweep across all organizations. Phase 5b deliberately did not implement that
path, and the index is currently unused.

**Decision: do not implement it.** A tenant-less dispatcher query is a
cross-tenant read path that exists for operational convenience and would need its
own authorization argument every time it is touched. The per-tenant claim
(`claimFor`, which refuses a null tenant) is the only claim path.

**Consequences:**

- `outbox_events_dispatch_sweep_idx` is unused. It is **not** dropped: dropping an
  index is destructive DDL and the ADR-0005 §3 rule applies. It stays until the
  schema-normalization project that is explicitly out of scope for this
  migration, and its presence is documented here so nobody reads it as a
  missing feature.
- Recovery from "a tenant's queue appears stuck" is an explicit, audited,
  tenant-scoped operator replay — not a global sweep.
- If a genuine operational need for a cross-tenant sweep appears, it is raised as
  a change with its own threat analysis, not enabled by flipping a flag.

## 2. Retention for the Phase-5a tables

`job_attempts` is the highest-write table in the system and nothing deleted from
it. Unbounded growth in an append-only attempt history is a real availability
risk, so the windows are decided now rather than at cutover.

| Table | Window | Basis |
| --- | --- | --- |
| `job_attempts` | 30 days after the attempt's terminal outcome | operational history; the aggregate that matters (queue lag, retry rates) lives in metrics, not in the row |
| `job_dead_letters` | **never deleted by retention** — retained until resolved, then 90 days | an operator decision record; deleting it destroys the evidence for "we did not replay this" |
| `idempotency_records` | 24 hours after completion | an idempotency window longer than the longest retry cycle buys nothing and creates a table that grows forever |
| `outbox_events` in `SENT` | 7 days after delivery | after that the row is only a duplicate-suppression key for a cycle that has long since passed |
| `outbox_events` in `DEAD` | never deleted by retention; resolved by operator action | same basis as dead letters |

**Rules that make this safe rather than convenient:**

1. **Retention never deletes a row a human decision depends on.** Dead letters
   and anything in `DEAD` are exempt. Purging those to save space would remove
   the evidence trail that makes replay and resolution auditable.
2. **Purge is tenant-scoped, batched, and audited.** A purge job runs per tenant
   with a bounded batch size, records how many rows it removed per table, and is
   itself an audited job visible in Admin. It is never a raw `DELETE` issued by
   an operator.
3. **Purge is idempotent and resumable**, exactly like the Phase-2 backfills:
   checkpoint in the same transaction as each batch, so an interrupted purge
   resumes without double counting.
4. **`payloadSummary` and `payload` are not customer content** and no purged
   table holds message bodies, so purging cannot destroy a record that a
   retention or legal-hold class protects.
5. **Legal hold beats retention.** A purge job refuses to run for a tenant under
   legal hold. This is a Phase 7 dependency: Admin must expose hold state before
   the first purge job is enabled.

**Status and ownership:** the windows above are the accepted defaults. The purge
*job* is Phase 7 work and must ship disabled until legal-hold state exists. If
the operators later want different windows, this ADR is amended — the numbers
are configuration, not code.

## 3. What this does not decide

- Backups and their retention (separate: backup encryption is still open in ADR
  0005 §4).
- Audit, security, and finance retention, which are governed by their own
  retention classes and are not touched by this.
- `sessions` expiry, which already has its own index and sweep.