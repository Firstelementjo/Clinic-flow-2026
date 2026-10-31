# ADR 0005 — Phase 2/3 open decisions (orchestrator decisions)

Date: 2026-10-04
Status: Accepted (Phase 2 and Phase 3 close-out)

These decisions close the open questions raised by the Phase 2 (database) and
Phase 3 (security) agent reports. Anything that touches tenant isolation,
irreversible migration behaviour, external billing, or customer-visible AI
behaviour is recorded here explicitly.

## 1. Quarantined Prisma init migration

The `packages/database/prisma/migrations/20260814000000_init/migration.sql`
file is **quarantined**, not corrected. It disagrees with the verified live
schema (14 of 45 tables absent, `CaseOutcome` absent, wrong
`EmployeeAvailabilityStatus` and `AIRunDecision` enum lists).

- Flyway owns schema history for the Spring runtime from now on.
- `prisma migrate deploy` and `prisma db push` are **forbidden** against the
  shared `firstelement` database for the remainder of the migration. The legacy
  NestJS runtime may run locally, but any local schema change goes through
  Flyway or a disposable database.
- The file stays on disk for historical reference only, with a header comment
  pointing at `docs/migration/schema-drift-report.md`.

## 2. First Flyway-enabled boot

The baseline stamp (`20260814000000`) plus the additive HNSW index migration
(`V20260814000100`) happen on the first boot of the Spring API against the
target database, in the cutover window, with a fresh `ops/backup-restore`
backup taken immediately before. Phase 3 boots deliberately ran with
`spring.flyway.enabled=false` so the dev database was never stamped
implicitly.

## 3. `CREATE INDEX CONCURRENTLY` vs maintenance window

`ops/flyway/pending/V202610040009__ai_run_child_tenant_scope.sql.todo` remains
pending. If promoted, it runs **outside** a transaction
(`executeInTransaction=false`) with `CREATE INDEX CONCURRENTLY`, because the
AI-trace tables are append-heavy. No promotion happens without a verified
backup and a row-count/checksum comparison of the AI-run stream before and after.

## 4. Backup encryption

Open risk from Phase 2: `ops/backup-restore/backup.ps1` deliberately holds no
key. Decision: backups are encrypted **outside** the script — by the platform's
volume/disk encryption at rest, plus an operator-supplied `age` recipient when
copying dumps off-host. The script keeps refusing to embed key material.
Restore rehearsals must state which of the two protections applies.

## 5. Audit/security stream separation

Phase 3 records security events on the existing `audit_logs` table. Splitting
into `SecurityEvent` / `AdminAuditEvent` tables waits on promotion of
`V202610040008__audit_streams.sql.todo` (Phase 7). Until then the separation is
by `SecurityEventType` convention and must not be described as a physical
separation.

## 6. Login rate limiting across replicas

The login limiter is per-process. Redis-backed counters are the additive fix and
are required before the first multi-replica deployment. This is tracked as a
Release-A blocker for horizontal scale, not for single-node operation.

## 7. Clinic/appointments permission decorators

The legacy `ClinicController` and `AppointmentsController` shipped without
permission decorators, and `/clinic/reminders/respond` lacked a tenant check
(Phase 0 finding). The Spring EMPLOYEE grant set was **not** widened to paper over
this. When the clinic module is migrated, each route gets an explicit permission
decision; until then those endpoints rely on `anyRequest().authenticated()` plus
service-layer checks, which is recorded as an accepted interim gap.

## 8. Operator surface on a separate management port

Actuator, metrics, and the OpenAPI/Swagger surfaces are served on a separate
management port (`MANAGEMENT_PORT`, default 4101 for the API and the worker's own
port) and remain behind the same authenticated Spring Security chain. The API port
no longer serves `/api/v1/openapi`. Unauthenticated access returns 401; there is no
anonymous documentation surface.