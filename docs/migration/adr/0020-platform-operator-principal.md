# ADR 0020: The platform operator is a separate principal above the organization

- Status: accepted
- Date: 2026-10-12
- Phase: clinic / platform foundation
- Scope: `DEC-011` — the platform control plane
- Raised by: product owner
- Decided by: product owner

## Context

Onboarding a clinic means creating a project, creating its first administrator, setting a seat count
and a feature set, and granting people access. None of that exists today, and none of it may be done
by a customer.

Every principal in the current system is scoped to a single organization: `ADMIN` (42 grants),
`EMPLOYEE`, `CLINIC_STAFF`, and `AI_AGENT` as a service principal. There is nothing above them.

That makes the platform operator the only principal in the product that legitimately crosses tenant
boundaries. It is therefore the highest-value target in the system, and the 2026-10-08 cross-tenant
incident is the reason to treat that as a design constraint rather than a theoretical concern.

## Decision

A **platform operator** principal is introduced, operated by a small named team. It owns:

- the list of all clinics and the ability to create them, which creates the project and its first
  administrator in one action;
- creation of further accounts, and granting a user access to specific projects;
- global platform status **with actions** — health, queue depth, failed jobs, connector state, and
  safe operational actions such as replaying a failed job or restarting a worker;
- database control, expressed **only** as validated, audited, dry-run-capable jobs: create or drop a
  per-clinic database, apply and verify migrations, backup/restore/verify, tenant offboarding and data
  deletion, quota and size management.

Properties that are part of the decision, not implementation detail:

- **Separate authentication from customer sign-in**, with MFA.
- **Just-in-time elevation** rather than a standing super-admin session.
- **Its own immutable audit** of every cross-tenant read and every write, with justification.
- **No shared authentication path with the customer application.** A clinic administrator must never
  be able to reach any part of this surface.
- **No arbitrary SQL, no shell, no secret display.** Every database action is a modelled command with
  validation, authorization, an audit record, and a documented recovery path — the same discipline the
  existing administration module already applies to its maintenance jobs.

## Consequences

- The operator surface is a new attack surface and gets the strongest authentication in the product.
  Where the customer application is hardened for breadth, this is hardened for depth.
- Database control is a set of named operations, not a console. Each one can be reviewed, tested,
  and given a rollback. This is a deliberate refusal: a SQL console would make every backup, migration
  and deletion irreversible and unauditable.
- Offboarding a clinic becomes an auditable operation with a recovery procedure rather than an
  ad-hoc deletion.
- The existing administration module keeps its current, correctly organization-scoped role. It is not
  widened to see across tenants; that separation is the point.