# ADR 0006 — Phase 4/5 open decisions

Date: 2026-10-04
Status: Accepted

Closes the questions raised by the Phase 4 (CRM) and Phase 5a (outbox schema)
agent reports. Recorded here because they shape the next phases and one of them
(§1) is a correctness risk that must not be left ambiguous.

## 1. Idempotency ownership (reply keys vs the general store)

Phase 4 implemented reply idempotency by writing the caller's
`Idempotency-Key` into `messages.metadata` — the legacy-compatible path, chosen
so the strangler migration could compare old and new behaviour on identical
requests. Phase 5a introduced the general `idempotency_records` table with
tenant-scoped uniqueness.

**Decision:** the general table is the system of record for **delivery, job,
and outbox** idempotency. `messages.metadata` remains authoritative for
**conversation replies only**, until the CRM module moves to
`idempotency_records` behind a feature flag.

Rationale: the two stores answer different questions. A reply's key is part of
the conversation's observable behaviour and must keep working identically to the
legacy runtime for as long as the legacy client can still send it. A delivery or
job key is an infrastructure concern that the legacy runtime never had, and that
the dispatcher must be able to query cheaply. Migrating the reply path later is
a contained change; leaving the split undocumented is not acceptable.

Constraint: while both exist, a reply key must never be written to both — the
CRM service is the only writer of `messages.metadata`, and the dispatcher is
forbidden from treating it as a delivery key.

## 2. Optimistic concurrency token

There is no integer `version` column in the verified schema, so Phase 4 uses
`updatedAt` as the concurrency token and returns it as `ETag`/`If-Match`. That
is correct for the current cutover but coarse: two edits inside the same
timestamp granularity do not collide.

**Decision:** add integer `version` columns to `customers`, `conversations`,
and `notes` through a normal additive Flyway migration before the CRM write
path is cut over. It is a prerequisite for `idempotency_records` to be usable
for replies, and it is explicitly **not** part of this migration's schema
renaming project.

## 3. `MockChannelProbe` ownership

Phase 4 added `MockChannelProbe` as a seam so the inbound path could be tested
without a real connector. **Decision:** it moves to the channels module
(Phase 5b) the moment the connector port exists. Until then it stays in
`inbox-helpdesk` and stays inert in production — it 404s unless explicitly
flagged and refuses to start when flagged with a production profile.

## 4. Phase 10 seams already fixed by Phase 4

Two behaviours are deliberately provisional and must not be reported as final:

- `CaseSolutionRetriever` is a seam; Phase 10 supplies the pgvector-backed
  implementation and its tenant/ACL prefilter.
- New conversations start in `HUMAN` mode because `hasActiveAgent` does not
  exist yet; Phase 10 restores the legacy rule.

Both are recorded in `docs/migration/phases/phase-04.md` as open risks and must
appear in the Phase 10 gate as "closed", not as "unchanged".

## 5. The unassigned-queue definition is shared, not duplicated

`UnassignedQueueRepository.UNASSIGNED_ACTIVE` is the single definition of
"unassigned and active" for Phase 4's queue read model. Phase 6's router must
**reuse** it rather than re-deriving membership rules, so the queue an operator
sees and the queue the router fills cannot drift apart.

## 6. The legacy `views` surface is not yet placed

The legacy saved-views surface was not migrated in Phase 4. **Decision:** it
belongs to the reporting/exports work (Phase 9) as versioned report
definitions, with a compatibility read for the legacy shape. It is not CRM-core
and must not be smuggled into it.

## 7. Gradle build caching is not a test result

`./gradlew build` can report `UP-TO-DATE` or restore from the build cache
without executing the database-backed fixture. A migration or isolation test
that did not execute is **not** a pass. Until CI exists, every verification in
this migration runs as:

```powershell
.\gradlew.bat build --rerun-tasks --no-build-cache --no-daemon --console=plain
```